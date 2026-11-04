"""Bounded, owner-authorized WebRTC desktop viewing on the original Companion.

The phone supplies a gathered receive-only offer through device-v1. This module
never opens an HTTP listener, dispatches input, publishes model observations or
changes an Agent's UIA snapshot. Human input stays in the original desktop lease
and operation ledger; ``validate_input`` only checks its recent viewer geometry.

aiortc/PyAV are optional *prepared build* dependencies. No runtime installation,
public STUN default, placeholder frames or claimed hardware acceleration exists.
"""
from __future__ import annotations

import asyncio
from collections import OrderedDict
from concurrent.futures import Future, TimeoutError as FutureTimeout
import copy
import ctypes
from dataclasses import dataclass, field
from fractions import Fraction
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import queue
import re
import threading
import time
import uuid

from device_protocol import resource_equal
from platform_backend import PlatformError


METADATA_CHANNEL = "pocketworld.desktop.frames"
MAX_SDP_BYTES = 65536
MAX_NATIVE_PIXELS = 16777216
FRAME_TTL_MS = 2000
MAX_SESSION_MS = 300000
TERMINAL_STATES = frozenset({"closed", "failed", "expired"})


class DesktopStreamError(PlatformError):
    pass


class _GeometryChanged(DesktopStreamError):
    """Drop a capture taken during a resize/move; never publish its geometry."""


def _integer(value, minimum, maximum, name):
    if type(value) is not int or not minimum <= value <= maximum:
        raise DesktopStreamError("Invalid desktop viewer " + name, 400)
    return value


def _offer(value):
    if not isinstance(value, dict) or set(value) != {"type", "sdp"} or value["type"] != "offer":
        raise DesktopStreamError("A gathered receive-only WebRTC offer is required", 400)
    text = value["sdp"]
    if not isinstance(text, str) or not 1 <= len(text.encode("utf-8")) <= MAX_SDP_BYTES or "\x00" in text:
        raise DesktopStreamError("Desktop viewer offer exceeds its bound", 400)
    lines = text.splitlines()
    if len(lines) > 512 or any(len(line) > 4096 for line in lines):
        raise DesktopStreamError("Desktop viewer offer exceeds its line bound", 400)
    sections = []
    for line in lines:
        if line.startswith("m="):
            sections.append([line])
        elif sections:
            sections[-1].append(line)
    video = [section for section in sections if section[0].startswith("m=video ")]
    data = [section for section in sections if section[0].startswith("m=application ")]
    if (len(video) != 1 or len(data) > 1 or len(sections) != len(video) + len(data)
            or "a=recvonly" not in video[0] or any(line in {"a=sendrecv", "a=sendonly"} for line in video[0])
            or "UDP/TLS/RTP/SAVPF" not in video[0][0] or video[0][0].split()[1] == "0"
            or not any(line.startswith("a=fingerprint:") for line in lines)
            or sum(line.startswith("a=candidate:") for line in lines) > 64):
        raise DesktopStreamError("Only one encrypted receive-only video and optional metadata channel are allowed", 400)
    return copy.deepcopy(value)


def _ice_servers(values):
    if not isinstance(values, (list, tuple)) or len(values) > 4:
        raise DesktopStreamError("Desktop viewer ICE configuration exceeds its bound", 400)
    result = []
    for value in values:
        if not isinstance(value, dict) or set(value) - {"urls", "username", "credential"}:
            raise DesktopStreamError("Invalid desktop viewer ICE configuration", 400)
        urls = value.get("urls")
        if isinstance(urls, str):
            urls = [urls]
        if not isinstance(urls, list) or not 1 <= len(urls) <= 4:
            raise DesktopStreamError("Invalid desktop viewer ICE URLs", 400)
        for url in urls:
            if (not isinstance(url, str) or len(url) > 512 or any(character.isspace() for character in url)
                    or not re.fullmatch(r"(?:stun|turn|turns):(?:\[[0-9a-fA-F:]+\]|[A-Za-z0-9.-]+)(?::[0-9]{1,5})?(?:\?transport=(?:udp|tcp))?", url)):
                raise DesktopStreamError("Invalid desktop viewer ICE URL", 400)
        entry = {"urls": list(urls)}
        for key in ("username", "credential"):
            if key in value:
                if not isinstance(value[key], str) or not 1 <= len(value[key]) <= 1024 or "\x00" in value[key]:
                    raise DesktopStreamError("Invalid desktop viewer ICE credential", 400)
                entry[key] = value[key]
        if any(url.startswith(("turn:", "turns:")) for url in urls) and not {"username", "credential"} <= entry.keys():
            raise DesktopStreamError("Configured TURN requires explicit credentials", 400)
        result.append(entry)
    return result


def _resource(binding, target, health):
    if (not isinstance(binding, dict)
            or set(binding) not in ({"kind", "sessionId", "displayId"}, {"kind", "sessionId", "displayId", "windowId"})
            or binding.get("kind") != "desktop" or binding.get("displayId") != "primary"
            or type(binding.get("sessionId")) is not int):
        raise DesktopStreamError("Viewer requires an exact desktop resource grant", 403)
    if target is not None:
        if not isinstance(target, dict) or set(target) != {"pid", "windowId"}:
            raise DesktopStreamError("Viewer target must identify one original process and window", 400)
        _integer(target["pid"], 1, 0xFFFFFFFF, "pid")
        _integer(target["windowId"], 1, 9007199254740991, "windowId")
    if "windowId" in binding and (target is None or binding["windowId"] != target["windowId"]):
        raise DesktopStreamError("Whole desktop viewing is outside this window grant", 403)
    if (not health.get("available") or health.get("sessionId") != binding["sessionId"]
            or not isinstance(health.get("inputDesktop"), str) or health["inputDesktop"].casefold() != "default"):
        raise DesktopStreamError("Original interactive desktop is unavailable or changed", 409)
    displays = [display for display in health.get("displays", []) if display.get("primary") is True]
    if len(displays) != 1:
        raise DesktopStreamError("Viewer primary display is unavailable", 409)
    display = displays[0]
    bounds = display.get("bounds")
    if not isinstance(bounds, list) or len(bounds) != 4 or any(type(number) is not int for number in bounds):
        raise DesktopStreamError("Viewer physical display bounds are unavailable", 409)
    identity = {"sessionId": health["sessionId"], "inputDesktop": health["inputDesktop"], "displayId": display.get("id")}
    if target is not None:
        window = health.get("window", {})
        if (window.get("pid") != target["pid"] or window.get("windowId") != target["windowId"]
                or not window.get("processCreated") or not window.get("threadId")):
            raise DesktopStreamError("Viewer process/window identity changed", 409)
        rectangle = window.get("bounds")
        if (not isinstance(rectangle, list) or len(rectangle) != 4 or any(type(number) is not int for number in rectangle)
                or not (bounds[0] <= rectangle[0] < rectangle[2] <= bounds[2]
                        and bounds[1] <= rectangle[1] < rectangle[3] <= bounds[3])):
            raise DesktopStreamError("Viewer window must remain wholly on its granted primary display", 409)
        bounds = rectangle
        identity["window"] = {key: window[key] for key in ("pid", "windowId", "processCreated", "threadId")}
    width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
    if width <= 0 or height <= 0 or width * height > MAX_NATIVE_PIXELS:
        raise DesktopStreamError("Viewer native capture dimensions exceed their bound", 409)
    return identity, list(bounds), display


def _geometry(health, identity, bounds, display, width, height):
    # Focus/content may change while a user watches. Native geometry and original
    # process generation, rather than names or visual similarity, bind input.
    geometry = {"identity": identity, "display": display, "window": health.get("window"),
                "captureBounds": bounds, "width": width, "height": height}
    return hashlib.sha256(json.dumps(geometry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


class _BitmapInfoHeader(ctypes.Structure):
    _fields_ = [("size", ctypes.c_uint32), ("width", ctypes.c_int32), ("height", ctypes.c_int32),
                ("planes", ctypes.c_uint16), ("bitCount", ctypes.c_uint16), ("compression", ctypes.c_uint32),
                ("sizeImage", ctypes.c_uint32), ("xPixelsPerMeter", ctypes.c_int32), ("yPixelsPerMeter", ctypes.c_int32),
                ("colorsUsed", ctypes.c_uint32), ("colorsImportant", ctypes.c_uint32)]


def _window_capture_native():
    user = ctypes.WinDLL("user32", use_last_error=True)
    gdi = ctypes.WinDLL("gdi32", use_last_error=True)
    user.PrintWindow.argtypes, user.PrintWindow.restype = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32], ctypes.c_int
    gdi.CreateCompatibleDC.argtypes, gdi.CreateCompatibleDC.restype = [ctypes.c_void_p], ctypes.c_void_p
    gdi.CreateDIBSection.argtypes = [ctypes.c_void_p, ctypes.POINTER(_BitmapInfoHeader), ctypes.c_uint32,
                                   ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p, ctypes.c_uint32]
    gdi.CreateDIBSection.restype = ctypes.c_void_p
    gdi.SelectObject.argtypes, gdi.SelectObject.restype = [ctypes.c_void_p, ctypes.c_void_p], ctypes.c_void_p
    gdi.DeleteObject.argtypes, gdi.DeleteObject.restype = [ctypes.c_void_p], ctypes.c_int
    gdi.DeleteDC.argtypes, gdi.DeleteDC.restype = [ctypes.c_void_p], ctypes.c_int
    gdi.GdiFlush.argtypes, gdi.GdiFlush.restype = [], ctypes.c_int
    return user, gdi


def _capture_window(window_id, bounds):
    """Capture the full authorized HWND, including non-client window geometry.

    Pillow 13's HWND grab uses GetClientRect/BitBlt; it cannot represent the
    GetWindowRect physical viewport used by input. PrintWindow asks the owning
    application to render into a bounded DIB, without copying overlaid windows.
    It may block: the service's single bounded daemon worker owns this call.
    """
    from PIL import Image
    width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
    if min(width, height) <= 0 or width * height > MAX_NATIVE_PIXELS:
        raise DesktopStreamError("Viewer window capture dimensions exceed their bound", 409)
    user, gdi = _window_capture_native()
    context = gdi.CreateCompatibleDC(None)
    if not context:
        raise DesktopStreamError("Viewer window capture context is unavailable", 409)
    bitmap = previous = None
    try:
        info = _BitmapInfoHeader()
        info.size, info.width, info.height = ctypes.sizeof(info), width, -height  # top-down physical pixels
        info.planes, info.bitCount, info.compression = 1, 32, 0  # BI_RGB / BGRX
        info.sizeImage = width * height * 4
        bits = ctypes.c_void_p()
        bitmap = gdi.CreateDIBSection(context, ctypes.byref(info), 0, ctypes.byref(bits), None, 0)
        if not bitmap or not bits.value:
            raise DesktopStreamError("Viewer window pixel buffer is unavailable", 409)
        previous = gdi.SelectObject(context, bitmap)
        if not previous or previous == ctypes.c_void_p(-1).value:
            previous = None
            raise DesktopStreamError("Viewer window pixel buffer could not be selected", 409)
        # PW_RENDERFULLCONTENT (Windows 8.1+, also used by Chromium) includes
        # DirectComposition content; do not set PW_CLIENTONLY. No BitBlt fallback.
        if not user.PrintWindow(window_id, context, 2) or not gdi.GdiFlush():
            raise DesktopStreamError("Authorized window did not provide a native capture", 409)
        raw = ctypes.string_at(bits, info.sizeImage)
        return Image.frombytes("RGB", (width, height), raw, "raw", "BGRX", 0, 1)
    finally:
        if previous is not None:
            gdi.SelectObject(context, previous)
        if bitmap:
            gdi.DeleteObject(bitmap)
        gdi.DeleteDC(context)


class PillowDesktopCapture:
    """Native full-window/primary-display capture with prepared Pillow scaling.

    A window grant uses PrintWindow, never a screen rectangle which could reveal
    another app covering it. Native dimensions are checked before RGB scaling.
    """

    def __call__(self, target, bounds, max_edge):
        if os.name != "nt":
            raise DesktopStreamError("Windows interactive capture is required", 409)
        from PIL import Image, ImageGrab
        if target is None:
            image = ImageGrab.grab(include_layered_windows=True, all_screens=False)
        else:
            image = _capture_window(target["windowId"], bounds)
        try:
            if image.size != (bounds[2] - bounds[0], bounds[3] - bounds[1]):
                raise DesktopStreamError("Native viewer capture dimensions differ from the granted geometry", 409)
            # Even frame dimensions are accepted by both H.264 and VP8 encoders.
            scale = min(1, max_edge / max(image.size))
            width = max(2, int(image.width * scale) // 2 * 2)
            height = max(2, int(image.height * scale) // 2 * 2)
            rgb = image.convert("RGB")
            try:
                return rgb.resize((width, height), Image.Resampling.BILINEAR)
            finally:
                rgb.close()
        finally:
            image.close()


@dataclass
class _Captured:
    image: object
    health: dict
    bounds: list
    display: dict
    identity: dict
    captured_at: int


class _CaptureWorker:
    """One bounded daemon worker, including when a Windows capture blocks.

    No new worker is created after a capture timeout. A blocked OS capture can
    occupy this one worker until it returns; it cannot grow threads or publish
    pixels after its Future/session was cancelled.
    """
    def __init__(self):
        self.requests = queue.Queue(maxsize=2)
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, name="pocket-desktop-capture", daemon=True)
        self.thread.start()

    def submit(self, function):
        future = Future()
        if self.stopped.is_set():
            raise DesktopStreamError("Viewer capture worker is closed", 409)
        try:
            self.requests.put_nowait((function, future))
        except queue.Full as failure:
            raise DesktopStreamError("Viewer capture worker is busy", 409) from failure
        return future

    def _run(self):
        while not self.stopped.is_set():
            try:
                function, future = self.requests.get(timeout=0.2)
            except queue.Empty:
                continue
            if future.cancelled():
                continue
            try:
                result = function()
            except BaseException as failure:
                if not future.cancelled():
                    try:
                        future.set_exception(failure)
                    except Exception:
                        pass
            else:
                if future.cancelled() or self.stopped.is_set():
                    result.image.close()
                else:
                    try:
                        future.set_result(result)
                    except Exception:
                        result.image.close()
        self._cancel_queued()

    def _cancel_queued(self):
        while True:
            try:
                _, future = self.requests.get_nowait()
                future.cancel()
            except queue.Empty:
                return

    def close(self):
        self.stopped.set()
        self._cancel_queued()
        self.thread.join(timeout=0.3)
        return not self.thread.is_alive()


@dataclass
class _Session:
    id: str
    owner: str
    binding: dict
    target: dict | None
    identity: dict
    current: object
    created_at: int
    expires_at: int
    state: str = "negotiating"
    reason: str | None = None
    ended_at: int | None = None
    transport_closed: bool = False
    pc: object = None
    track: object = None
    channel: object = None
    sequence: int = 0
    frames: OrderedDict = field(default_factory=OrderedDict)
    last_frame: dict | None = None


def _load_rtc():
    try:
        from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription, VideoStreamTrack
        from aiortc.mediastreams import MediaStreamError
        from av import VideoFrame
    except (ImportError, OSError) as failure:
        raise DesktopStreamError("Prepared aiortc/PyAV desktop streaming dependencies are unavailable", 409) from failure
    return {"configuration": RTCConfiguration, "iceServer": RTCIceServer, "peer": RTCPeerConnection,
            "description": RTCSessionDescription, "track": VideoStreamTrack,
            "mediaError": MediaStreamError, "frame": VideoFrame}


def runtime_probe():
    """Exercise packaged native codecs without a peer, capture or user state.

    This is a build smoke check, not desktop/phone/network acceptance. It must
    import the real DTLS/SRTP dependencies and encode an in-memory synthetic VP8
    keyframe so a frozen executable missing native DLLs cannot pass on --help.
    """
    rtc = _load_rtc()
    from aiortc.codecs import CODECS, get_encoder
    from PIL import Image
    codec = next(codec for codec in CODECS["video"] if codec.mimeType.lower() == "video/vp8")
    encoder = get_encoder(codec)
    with Image.new("RGB", (64, 48), (30, 100, 170)) as image:
        frame = rtc["frame"].from_image(image)
    frame.pts, frame.time_base = 0, Fraction(1, 90000)
    payloads, _ = encoder.encode(frame, force_keyframe=True)
    encoded_bytes = sum(len(payload) for payload in payloads)
    if not payloads or not 1 <= encoded_bytes <= 262144:
        raise DesktopStreamError("Prepared VP8 encoder failed its synthetic runtime check", 409)
    return {"format": "pocketworld.desktop-stream-probe.v1", "available": True,
            "aiortcVersion": importlib.metadata.version("aiortc"),
            "pyavVersion": importlib.metadata.version("av"), "codec": "video/VP8",
            "width": 64, "height": 48, "encodedBytes": encoded_bytes,
            "networkOpened": False, "desktopCaptured": False}


class DesktopStreamService:
    def __init__(self, health, *, ice_servers=(), capture=None, clock=None, fps=10, max_edge=1200,
                 rtc_loader=None, negotiation_timeout=15.0, capture_timeout=2.0):
        self.health = health
        self.capture = capture if capture is not None else PillowDesktopCapture()
        self.clock = clock if clock is not None else lambda: int(time.time() * 1000)
        self.fps = _integer(fps, 1, 15, "fps")
        self.max_edge = _integer(max_edge, 240, 1200, "maxEdge")
        self.ice_servers = _ice_servers(ice_servers)
        self.rtc_loader = rtc_loader or _load_rtc
        self.negotiation_timeout = negotiation_timeout
        self.capture_timeout = capture_timeout
        self.lock = threading.RLock()
        self.sessions = {}
        self.loop = None
        self.thread = None
        self.worker = None
        self.closed = False

    def describe(self):
        missing = [name for name in ("aiortc", "av", "PIL") if importlib.util.find_spec(name) is None]
        available = not self.closed and not missing and (os.name == "nt" or not isinstance(self.capture, PillowDesktopCapture))
        return {"available": available, "transport": "webrtc", "inputChannel": False,
                "metadataChannel": METADATA_CHANNEL, "fpsLimit": self.fps, "maxEdge": self.max_edge,
                "maxSessions": 2, "sessionLimitMs": MAX_SESSION_MS, "frameLifetimeMs": FRAME_TTL_MS,
                "turnConfigured": any(url.startswith(("turn:", "turns:")) for item in self.ice_servers for url in item["urls"]),
                "reason": "prepared_stream_dependencies_missing" if missing else "windows_capture_required" if not available else None,
                "missingDependencies": missing, "hardwareEncodingVerified": False, "nativeGuiVerified": False}

    def client_configuration(self):
        # Only expose through the already authorized owner signalling route.
        # TURN credentials must not be copied into model history or public logs.
        return {"iceServers": copy.deepcopy(self.ice_servers), "bundlePolicy": "max-bundle"}

    def _ensure_loop(self):
        with self.lock:
            if self.closed:
                raise DesktopStreamError("Desktop viewer runtime is closed", 409)
            if self.loop is not None:
                return
            self.loop = asyncio.new_event_loop()
            self.worker = _CaptureWorker()
            ready = threading.Event()

            def run():
                asyncio.set_event_loop(self.loop)
                watchdog = self.loop.create_task(self._watchdog())
                ready.set()
                try:
                    self.loop.run_forever()
                finally:
                    watchdog.cancel()
                    pending = asyncio.all_tasks(self.loop)
                    for task in pending:
                        task.cancel()
                    if pending:
                        self.loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
                    self.loop.close()

            self.thread = threading.Thread(target=run, name="pocket-desktop-webrtc", daemon=True)
            self.thread.start()
            if not ready.wait(2):
                raise DesktopStreamError("Desktop viewer runtime did not start", 409)

    def _call(self, awaitable, timeout=5.0):
        try:
            self._ensure_loop()
        except BaseException:
            awaitable.close()
            raise
        if threading.current_thread() is self.thread:
            awaitable.close()
            raise RuntimeError("Synchronous desktop viewer call on its media loop")
        future = asyncio.run_coroutine_threadsafe(awaitable, self.loop)
        try:
            return future.result(timeout=timeout)
        except FutureTimeout as failure:
            future.cancel()
            raise DesktopStreamError("Desktop viewer operation timed out; reconnect explicitly", 409) from failure

    def _authorize(self, session):
        session.current()
        if session.expires_at <= self.clock():
            raise DesktopStreamError("Desktop viewer grant or explicit session expired", 409)
        if self.closed or session.state in TERMINAL_STATES:
            raise DesktopStreamError("Desktop viewer is closed", 409)

    def _health(self, session):
        self._authorize(session)
        target = session.target
        health = self.health.probe(target["pid"] if target else None, target["windowId"] if target else None)
        identity, bounds, display = _resource(session.binding, target, health)
        if not resource_equal(identity, session.identity):
            raise DesktopStreamError("Viewer original display or process instance changed", 409)
        return health, bounds, display

    def _capture_frame(self, session):
        before, bounds, display = self._health(session)
        image = self.capture(session.target, bounds, self.max_edge)
        try:
            if (not isinstance(image.width, int) or not isinstance(image.height, int)
                    or not 2 <= image.width <= self.max_edge or not 2 <= image.height <= self.max_edge
                    or image.width % 2 or image.height % 2 or image.mode != "RGB"):
                raise DesktopStreamError("Viewer capture did not return a bounded RGB video frame", 409)
            after, after_bounds, after_display = self._health(session)
            if (_geometry(before, session.identity, bounds, display, image.width, image.height)
                    != _geometry(after, session.identity, after_bounds, after_display, image.width, image.height)
                    or before.get("binding") != after.get("binding")
                    or before.get("lastInputTick") != after.get("lastInputTick")):
                raise _GeometryChanged("Viewer geometry changed during capture", 409)
            return _Captured(image, after, bounds, display, session.identity, self.clock())
        except BaseException:
            image.close()
            raise

    def _track(self, session, rtc):
        service = self

        class ViewerTrack(rtc["track"]):
            def __init__(self):
                super().__init__()
                self.started = time.monotonic()
                self.next_capture = self.started
                self.last_pts = -1

            async def recv(self):
                if self.readyState != "live":
                    raise rtc["mediaError"]
                try:
                    while True:
                        service._authorize(session)
                        await asyncio.sleep(max(0, self.next_capture - time.monotonic()))
                        service._authorize(session)
                        self.next_capture = time.monotonic() + 1 / service.fps
                        pending = service.worker.submit(lambda: service._capture_frame(session))
                        try:
                            captured = await asyncio.wait_for(asyncio.wrap_future(pending), service.capture_timeout)
                            break
                        except _GeometryChanged:
                            # Dragging/resizing is normal while watching. Skip
                            # only the uncertain frame, with the same FPS bound.
                            continue
                    try:
                        service._authorize(session)
                        frame = rtc["frame"].from_image(captured.image)
                        pts = max(self.last_pts + 1, int((time.monotonic() - self.started) * 90000))
                        self.last_pts = pts
                        frame.pts, frame.time_base = pts, Fraction(1, 90000)
                        service._publish(session, captured)
                        return frame
                    finally:
                        captured.image.close()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    # Closing a sender from its own recv stack can deadlock its
                    # task. Mark terminal synchronously, close on a separate task.
                    service._mark_terminal(session, "failed", "capture_or_authorization_unavailable")
                    asyncio.create_task(service._close_peer(session))
                    raise rtc["mediaError"]

        return ViewerTrack()

    def _publish(self, session, captured):
        with self.lock:
            self._authorize(session)
            session.sequence += 1
            frame_id = uuid.uuid4().hex
            revision = _geometry(captured.health, captured.identity, captured.bounds, captured.display,
                                 captured.image.width, captured.image.height)
            reference = {"streamId": session.id, "frameId": frame_id, "width": captured.image.width,
                         "height": captured.image.height, "geometryRevision": revision,
                         "capturedAt": captured.captured_at,
                         "expiresAt": min(captured.captured_at + FRAME_TTL_MS, session.expires_at)}
            cutoff = self.clock()
            session.frames = OrderedDict((key, frame) for key, frame in session.frames.items() if frame["reference"]["expiresAt"] > cutoff)
            while len(session.frames) >= 32:
                session.frames.popitem(last=False)
            session.frames[frame_id] = {"reference": reference, "bounds": captured.bounds, "health": captured.health}
            session.last_frame = reference
            channel = session.channel
            if channel is not None and channel.readyState == "open" and channel.bufferedAmount < 8192:
                channel.send(json.dumps({"type": "frame", **reference}, separators=(",", ":")))

    def open(self, binding, target, owner, offer, expires_at, current):
        if not isinstance(owner, str) or not 1 <= len(owner) <= 256 or not callable(current):
            raise DesktopStreamError("Viewer requires an authenticated original owner", 403)
        current()
        now = self.clock()
        _integer(expires_at, now + 1000, now + MAX_SESSION_MS, "expiresAt")
        offer = _offer(offer)
        rtc = self.rtc_loader()
        return self._call(self._open(binding, target, owner, offer, expires_at, current, rtc), self.negotiation_timeout + 3)

    async def _open(self, binding, target, owner, offer, expires_at, current, rtc):
        target = copy.deepcopy(target)
        if target is not None and not isinstance(target, dict):
            raise DesktopStreamError("Invalid viewer target", 400)
        health = self.health.probe(target.get("pid") if target else None, target.get("windowId") if target else None)
        identity, _, _ = _resource(binding, target, health)
        current()
        with self.lock:
            self._prune()
            if sum(session.state not in TERMINAL_STATES for session in self.sessions.values()) >= 2:
                raise DesktopStreamError("Maximum active desktop viewers reached", 409)
            session = _Session(uuid.uuid4().hex, owner, copy.deepcopy(binding), target, identity, current, self.clock(), expires_at)
            self.sessions[session.id] = session
        try:
            configuration = rtc["configuration"](iceServers=[rtc["iceServer"](**copy.deepcopy(server)) for server in self.ice_servers])
            pc = session.pc = rtc["peer"](configuration=configuration)
            track = session.track = self._track(session, rtc)

            @pc.on("connectionstatechange")
            async def connection_changed():
                if pc.connectionState in {"failed", "closed"}:
                    if session.state not in TERMINAL_STATES:
                        self._mark_terminal(session, "failed" if pc.connectionState == "failed" else "closed", "peer_connection_" + pc.connectionState)
                    await self._close_peer(session)
                elif pc.connectionState == "connected" and session.state not in TERMINAL_STATES:
                    session.state = "connected"

            @pc.on("datachannel")
            def datachannel(channel):
                if channel.label != METADATA_CHANNEL or session.channel is not None or not channel.ordered:
                    channel.close()
                    return
                session.channel = channel

                @channel.on("message")
                def no_input(_message):
                    channel.close()  # Input only passes through the owner/lease route.

            async def negotiate():
                await pc.setRemoteDescription(rtc["description"](sdp=offer["sdp"], type="offer"))
                self._authorize(session)
                pc.addTrack(track)
                for transceiver in pc.getTransceivers():
                    transceiver.direction = "sendonly" if transceiver.kind == "video" else "inactive"
                await pc.setLocalDescription(await pc.createAnswer())
                self._authorize(session)

            await asyncio.wait_for(negotiate(), self.negotiation_timeout)
            if session.state == "negotiating":
                session.state = "connecting"
            answer = pc.localDescription
            if answer is None or answer.type != "answer" or len(answer.sdp.encode("utf-8")) > MAX_SDP_BYTES:
                raise DesktopStreamError("Desktop viewer did not produce a bounded SDP answer", 409)
            return {**self._status(session), "answer": {"type": "answer", "sdp": answer.sdp}}
        except BaseException:
            self._mark_terminal(session, "failed", "negotiation_failed")
            await self._close_peer(session)
            raise

    def _get(self, stream_id, owner, binding, current):
        current()
        if not isinstance(stream_id, str) or not re.fullmatch(r"[a-f0-9]{32}", stream_id):
            raise DesktopStreamError("Invalid desktop viewer identity", 400)
        with self.lock:
            session = self.sessions.get(stream_id)
            if session is None or session.owner != owner or not resource_equal(session.binding, binding):
                raise DesktopStreamError("Desktop viewer belongs to another original owner/resource", 403)
            return session

    def _status(self, session):
        return {"streamId": session.id, "transport": "webrtc", "state": session.state, "reason": session.reason,
                "expiresAt": session.expires_at, "target": copy.deepcopy(session.target),
                "lastFrame": copy.deepcopy(session.last_frame), "inputChannel": False,
                "metadataChannel": METADATA_CHANNEL, "transportClosedVerified": session.transport_closed}

    def status(self, stream_id, owner, binding, current):
        session = self._get(stream_id, owner, binding, current)
        if session.state not in TERMINAL_STATES:
            try:
                self._authorize(session)
            except Exception:
                self._call(self._end(session, "expired", "authorization_expired_or_revoked"))
        with self.lock:
            return self._status(session)

    def validate_input(self, stream_id, owner, binding, frame_ref, current):
        session = self._get(stream_id, owner, binding, current)
        if not isinstance(frame_ref, dict):
            raise DesktopStreamError("A recent viewer frame reference is required", 409)
        with self.lock:
            self._authorize(session)
            frame = session.frames.get(frame_ref.get("frameId"))
            if not frame or not resource_equal(frame["reference"], frame_ref) or frame_ref["expiresAt"] <= self.clock():
                raise DesktopStreamError("Viewer frame is stale, unknown or belongs to another view", 409)
        health, bounds, display = self._health(session)
        revision = _geometry(health, session.identity, bounds, display, frame_ref["width"], frame_ref["height"])
        if revision != frame_ref["geometryRevision"] or bounds != frame["bounds"]:
            raise DesktopStreamError("Viewer geometry changed; wait for the current frame before input", 409)
        if (health.get("binding") != frame["health"].get("binding")
                or health.get("lastInputTick") != frame["health"].get("lastInputTick")):
            raise DesktopStreamError("Viewer foreground or local input changed; wait for a current frame before input", 409)
        current()
        with self.lock:
            self._authorize(session)
            if frame_ref.get("frameId") not in session.frames:
                raise DesktopStreamError("Viewer input was invalidated by a control handoff", 409)
        return {"target": copy.deepcopy(session.target), "healthBinding": copy.deepcopy(health),
                "captureBounds": bounds, "width": frame_ref["width"], "height": frame_ref["height"],
                "geometryRevision": revision, "frameId": frame_ref["frameId"],
                "capturedAt": frame_ref["capturedAt"], "expiresAt": frame_ref["expiresAt"]}

    def invalidate_inputs(self, stream_id, owner, binding, current):
        session = self._get(stream_id, owner, binding, current)
        with self.lock:
            self._authorize(session)
            session.frames.clear()
            session.last_frame = None

    def close(self, stream_id, owner, binding, current):
        session = self._get(stream_id, owner, binding, current)
        if session.state not in TERMINAL_STATES:
            self._call(self._end(session, "closed", "owner_closed"))
        return self._status(session)

    def _mark_terminal(self, session, state, reason):
        with self.lock:
            if session.state not in TERMINAL_STATES:
                session.state, session.reason, session.ended_at = state, reason, self.clock()
            session.frames.clear()
            session.last_frame = None
        if session.track is not None:
            session.track.stop()

    async def _close_peer(self, session):
        if session.pc is not None:
            # aiortc close is idempotent, including its connectionstate callback.
            pc, session.pc = session.pc, None
            try:
                await asyncio.wait_for(pc.close(), 3)
                session.transport_closed = True
            except Exception:
                # Track is already stopped and frame refs retired. Do not claim
                # verified network cleanup when a library close was interrupted.
                session.transport_closed = False

    async def _end(self, session, state, reason):
        self._mark_terminal(session, state, reason)
        await self._close_peer(session)

    def _prune(self):
        now = self.clock()
        expired = [key for key, value in self.sessions.items() if value.state in TERMINAL_STATES and (value.ended_at or now) + 60000 <= now]
        for key in expired:
            del self.sessions[key]
        while len(self.sessions) >= 32:
            key = next((key for key, value in self.sessions.items() if value.state in TERMINAL_STATES), None)
            if key is None:
                break
            del self.sessions[key]

    async def _watchdog(self):
        while not self.closed:
            await asyncio.sleep(0.25)
            with self.lock:
                sessions = list(self.sessions.values())
            for session in sessions:
                if session.state in TERMINAL_STATES:
                    continue
                try:
                    self._authorize(session)
                except Exception:
                    await self._end(session, "expired", "authorization_expired_or_revoked")
            with self.lock:
                self._prune()

    def close_all(self):
        with self.lock:
            if self.closed:
                return {"closed": True, "captureWorkerStoppedVerified": self.worker is None or not self.worker.thread.is_alive()}
            sessions = list(self.sessions.values())
            loop = self.loop
        async def end_all():
            await asyncio.gather(*(self._end(session, "closed", "companion_stopped") for session in sessions), return_exceptions=True)
        try:
            if loop is not None and self.thread.is_alive():
                self._call(end_all())
        finally:
            with self.lock:
                self.closed = True
            capture_stopped = self.worker is None or self.worker.close()
            if loop is not None and self.thread.is_alive():
                loop.call_soon_threadsafe(loop.stop)
                self.thread.join(timeout=2)
        return {"closed": True, "captureWorkerStoppedVerified": capture_stopped,
                "mediaLoopStoppedVerified": self.thread is None or not self.thread.is_alive()}
