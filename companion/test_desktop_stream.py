"""Viewer contract tests, plus an optional *real* aiortc loopback video test.

Synthetic Pillow frames below are labelled test fixtures. Neither these tests
nor SDP negotiation on Linux constitute Windows/Android or WAN acceptance.
"""
import asyncio
import copy
import importlib.util
import json
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from PIL import Image, ImageDraw

from desktop_stream import DesktopStreamError, DesktopStreamService, METADATA_CHANNEL, _ice_servers, _offer


OFFER = {"type": "offer", "sdp": (
    "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
    "a=fingerprint:sha-256 01:02\r\n"
    "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\na=rtcp-mux\r\n"
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")}
BINDING = {"kind": "desktop", "sessionId": 1, "displayId": "primary", "windowId": 456}
TARGET = {"pid": 123, "windowId": 456}


class FixtureHealth:
    def __init__(self):
        self.value = {"available": True, "sessionId": 1, "inputDesktop": "Default", "binding": "fixture-native-health",
                      "lastInputTick": 10, "foregroundWindowId": 456,
                      "displays": [{"id": "fixture-primary", "primary": True, "bounds": [0, 0, 1920, 1080], "dpi": [96, 96]}],
                      "window": {"pid": 123, "windowId": 456, "threadId": 789, "processCreated": "fixture-creation-1",
                                 "bounds": [20, 30, 340, 230], "dpi": 96}}

    def probe(self, pid=None, window_id=None):
        result = copy.deepcopy(self.value)
        if pid is None and window_id is None:
            result.pop("window", None)
        return result


class FixtureCapture:
    def __init__(self):
        self.calls = []
        self.on_capture = None

    def __call__(self, target, bounds, max_edge):
        self.calls.append((copy.deepcopy(target), list(bounds), max_edge))
        width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
        ratio = min(1, max_edge / max(width, height))
        image = Image.new("RGB", (max(2, int(width * ratio) // 2 * 2), max(2, int(height * ratio) // 2 * 2)), (30, 100, 170))
        ImageDraw.Draw(image).text((8, 8), "SYNTHETIC STREAM TEST", fill=(255, 255, 255))
        if self.on_capture:
            self.on_capture()
        return image


class FixtureMediaError(Exception):
    pass


class FixtureTrack:
    readyState = "live"

    def stop(self):
        self.readyState = "ended"


class FixtureFrame:
    @staticmethod
    def from_image(image):
        return SimpleNamespace(width=image.width, height=image.height, pts=None, time_base=None)


class FixtureChannel:
    def __init__(self, label=METADATA_CHANNEL, *, ordered=True):
        self.label, self.ordered = label, ordered
        self.readyState, self.bufferedAmount = "open", 0
        self.messages, self.handlers = [], {}

    def on(self, event):
        def register(function):
            self.handlers[event] = function
            return function
        return register

    def send(self, message):
        self.messages.append(json.loads(message))

    def close(self):
        self.readyState = "closed"


class FixturePeer:
    instances = []
    stall = False

    def __init__(self, configuration):
        self.configuration = configuration
        self.connectionState = "new"
        self.handlers, self.track, self.localDescription = {}, None, None
        self.transceiver = SimpleNamespace(kind="video", direction="recvonly")
        self.close_count = 0
        self.instances.append(self)

    def on(self, event):
        def register(function):
            self.handlers[event] = function
            return function
        return register

    async def setRemoteDescription(self, offer):
        self.offer = offer

    def addTrack(self, track):
        self.track = track

    def getTransceivers(self):
        return [self.transceiver]

    async def createAnswer(self):
        return SimpleNamespace(type="answer", sdp="v=0\r\nfixture-only-answer\r\n")

    async def setLocalDescription(self, answer):
        if self.stall:
            await asyncio.sleep(100)
        self.localDescription = answer
        self.connectionState = "connecting"

    async def close(self):
        if self.connectionState == "closed":
            return
        self.close_count += 1
        self.connectionState = "closed"
        await self.handlers["connectionstatechange"]()


class FixtureFrameObserver:
    """Fixture only: no claim that these timestamps came from a real RTP send."""
    def __init__(self, sender, publish, invalidate):
        self.publish, self.available, self.sequence = publish, True, 0

    def capture(self, frame, captured):
        self.sequence += 1
        self.publish(captured, self.sequence % (1 << 32))


def fixture_rtc():
    return {"configuration": lambda **values: values, "iceServer": lambda **values: values,
            "peer": FixturePeer, "description": lambda **values: SimpleNamespace(**values),
            "track": FixtureTrack, "mediaError": FixtureMediaError, "frame": FixtureFrame,
            "frameObserver": FixtureFrameObserver}


class DesktopStreamContractTests(unittest.TestCase):
    def setUp(self):
        FixturePeer.instances, FixturePeer.stall = [], False
        self.health, self.capture = FixtureHealth(), FixtureCapture()
        self.now, self.authorized = 100000, True
        self.service = DesktopStreamService(self.health, capture=self.capture, clock=lambda: self.now,
                                            rtc_loader=fixture_rtc, fps=15)

    def current(self):
        if not self.authorized:
            raise DesktopStreamError("Fixture grant revoked", 403)

    def tearDown(self):
        self.service.close_all()

    def open(self, **changes):
        values = {"binding": BINDING, "target": TARGET, "owner": "paired-peer-A", "offer": OFFER,
                  "expires_at": self.now + 30000, "current": self.current}
        values.update(changes)
        return self.service.open(**values)

    def next_frame(self, opened):
        peer = self.service.sessions[opened["streamId"]].pc
        frame = self.service._call(peer.track.recv())
        status = self.service.status(opened["streamId"], "paired-peer-A", BINDING, self.current)
        return frame, status["lastFrame"]

    def test_negotiate_send_only_offer_then_publish_video_geometry(self):
        opened = self.open()
        peer = FixturePeer.instances[-1]
        self.assertEqual("answer", opened["answer"]["type"])
        self.assertEqual("sendonly", peer.transceiver.direction)
        self.assertEqual([], peer.configuration["iceServers"])
        self.assertFalse(opened["inputChannel"])
        self.assertIsNone(opened["lastFrame"])
        channel = FixtureChannel()
        peer.handlers["datachannel"](channel)
        frame, reference = self.next_frame(opened)
        self.assertEqual((320, 200), (frame.width, frame.height))
        self.assertEqual({"type": "frame", **reference}, channel.messages[-1])
        geometry = self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, reference, self.current)
        self.assertEqual([20, 30, 340, 230], geometry["captureBounds"])
        self.assertEqual(TARGET, geometry["target"])
        self.assertEqual(self.health.value, geometry["healthBinding"])
        self.assertEqual([(TARGET, [20, 30, 340, 230], 1200)], self.capture.calls)
        self.assertNotIn("image", json.dumps(opened))

    def test_unknown_channels_and_inbound_messages_cannot_dispatch_input(self):
        opened = self.open()
        peer = FixturePeer.instances[-1]
        for channel in (FixtureChannel("input"), FixtureChannel(ordered=False)):
            peer.handlers["datachannel"](channel)
            self.assertEqual("closed", channel.readyState)
        channel = FixtureChannel()
        peer.handlers["datachannel"](channel)
        channel.handlers["message"]('{"click":[1,2]}')
        self.assertEqual("closed", channel.readyState)
        self.assertEqual([], self.capture.calls)
        self.assertEqual("connecting", self.service.status(opened["streamId"], "paired-peer-A", BINDING, self.current)["state"])

    def test_grant_cannot_widen_window_or_rebind_original_instance(self):
        for updates in ({"target": None}, {"target": {"pid": 123, "windowId": 999}},
                        {"binding": {**BINDING, "sessionId": 2}}, {"binding": {**BINDING, "displayId": "secondary"}}):
            with self.subTest(updates=updates), self.assertRaises(DesktopStreamError):
                self.open(**updates)
        self.assertEqual([], FixturePeer.instances)
        opened = self.open()
        _, reference = self.next_frame(opened)
        self.health.value["window"]["processCreated"] = "another-process-same-hwnd"
        with self.assertRaises(DesktopStreamError):
            self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, reference, self.current)

    def test_primary_desktop_capture_has_explicit_native_bounds(self):
        whole = {key: value for key, value in BINDING.items() if key != "windowId"}
        opened = self.open(binding=whole, target=None)
        peer = FixturePeer.instances[-1]
        frame = self.service._call(peer.track.recv())
        reference = self.service.status(opened["streamId"], "paired-peer-A", whole, self.current)["lastFrame"]
        geometry = self.service.validate_input(opened["streamId"], "paired-peer-A", whole, reference, self.current)
        self.assertIsNone(geometry["target"])
        self.assertEqual([0, 0, 1920, 1080], geometry["captureBounds"])
        self.assertEqual((1200, 674), (frame.width, frame.height))

    def test_recent_frames_remain_usable_but_stale_owner_geometry_and_handoff_do_not(self):
        opened = self.open()
        _, first = self.next_frame(opened)
        self.now += 100
        _, second = self.next_frame(opened)
        self.assertNotEqual(first["frameId"], second["frameId"])
        self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, first, self.current)
        for owner, binding, reference in (("other-peer", BINDING, first),
                                           ("paired-peer-A", {**BINDING, "windowId": 999}, first),
                                           ("paired-peer-A", BINDING, {**first, "width": 321})):
            with self.assertRaises(DesktopStreamError):
                self.service.validate_input(opened["streamId"], owner, binding, reference, self.current)
        self.health.value["window"]["bounds"][0] += 1
        with self.assertRaises(DesktopStreamError):
            self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, second, self.current)
        self.health.value["window"]["bounds"][0] -= 1
        self.service.invalidate_inputs(opened["streamId"], "paired-peer-A", BINDING, self.current)
        with self.assertRaises(DesktopStreamError):
            self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, second, self.current)
        _, third = self.next_frame(opened)
        self.now = third["expiresAt"]
        with self.assertRaises(DesktopStreamError):
            self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, third, self.current)

    def test_move_during_capture_drops_uncertain_frame_and_then_continues(self):
        opened = self.open()
        def move_once():
            self.health.value["window"]["bounds"] = [40, 50, 360, 250]
            self.capture.on_capture = None
        self.capture.on_capture = move_once
        _, reference = self.next_frame(opened)
        self.assertEqual(2, len(self.capture.calls))
        geometry = self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, reference, self.current)
        self.assertEqual([40, 50, 360, 250], geometry["captureBounds"])
        self.assertEqual(1, len(self.service.sessions[opened["streamId"]].frames))

    def test_control_handoff_drops_capture_already_in_flight(self):
        opened = self.open()
        def handoff_once():
            self.capture.on_capture = None
            self.service.invalidate_inputs(opened["streamId"], "paired-peer-A", BINDING, self.current)
        self.capture.on_capture = handoff_once
        _, reference = self.next_frame(opened)
        self.assertEqual(2, len(self.capture.calls))
        self.assertEqual(1, self.service.sessions[opened["streamId"]].sequence)
        self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, reference, self.current)

    def test_revocation_during_capture_never_publishes_that_frame(self):
        opened = self.open()
        self.capture.on_capture = lambda: setattr(self, "authorized", False)
        with self.assertRaises(FixtureMediaError):
            self.service._call(FixturePeer.instances[-1].track.recv())
        session = self.service.sessions[opened["streamId"]]
        self.assertIn(session.state, {"failed", "expired"})
        self.assertIsNone(session.last_frame)
        self.assertFalse(session.frames)

    def test_expiry_closes_even_when_no_video_packets_are_requested(self):
        opened = self.open()
        peer = FixturePeer.instances[-1]
        self.now += 30001
        deadline = time.monotonic() + 1
        while peer.connectionState != "closed" and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual("closed", peer.connectionState)
        self.assertEqual(0, len(self.capture.calls))
        self.assertEqual("expired", self.service.status(opened["streamId"], "paired-peer-A", BINDING, self.current)["state"])

    def test_capture_timeout_cannot_create_unbounded_threads_or_publish_late_pixels(self):
        opened = self.open()
        gate = threading.Event()
        self.capture.on_capture = lambda: gate.wait(1)
        self.service.capture_timeout = 0.02
        worker = self.service.worker.thread
        try:
            with self.assertRaises(FixtureMediaError):
                self.service._call(FixturePeer.instances[-1].track.recv())
            self.assertIs(worker, self.service.worker.thread)
            self.assertIsNone(self.service.sessions[opened["streamId"]].last_frame)
        finally:
            gate.set()

    def test_close_retires_frames_and_does_not_accept_a_new_owner(self):
        opened = self.open()
        _, reference = self.next_frame(opened)
        with self.assertRaises(DesktopStreamError):
            self.service.close(opened["streamId"], "paired-peer-B", BINDING, self.current)
        status = self.service.close(opened["streamId"], "paired-peer-A", BINDING, self.current)
        self.assertEqual("closed", status["state"])
        self.assertTrue(status["transportClosedVerified"])
        self.assertIsNone(status["lastFrame"])
        with self.assertRaises(DesktopStreamError):
            self.service.validate_input(opened["streamId"], "paired-peer-A", BINDING, reference, self.current)

    def test_bound_active_views_and_failed_negotiations_release_capacity(self):
        first, second = self.open(), self.open()
        with self.assertRaises(DesktopStreamError):
            self.open()
        self.service.close(first["streamId"], "paired-peer-A", BINDING, self.current)
        self.service.negotiation_timeout = 0.02
        FixturePeer.stall = True
        with self.assertRaises((DesktopStreamError, TimeoutError)):
            self.open()
        self.assertEqual("closed", FixturePeer.instances[-1].connectionState)
        FixturePeer.stall = False
        self.assertEqual("webrtc", self.open()["transport"])
        self.assertIn(second["streamId"], self.service.sessions)

    def test_missing_dependency_never_returns_a_fake_stream(self):
        def missing():
            raise DesktopStreamError("Prepared dependency missing", 409)
        self.service.rtc_loader = missing
        with self.assertRaises(DesktopStreamError):
            self.open()
        self.assertIsNone(self.service.loop)
        self.assertEqual({}, self.service.sessions)

    def test_no_public_ice_default_and_credential_free_describe_status(self):
        self.assertEqual([], self.service.client_configuration()["iceServers"])
        self.service.ice_servers = _ice_servers([{"urls": "turns:relay.example:5349?transport=tcp", "username": "fixture", "credential": "fixture-secret"}])
        opened = self.open()
        self.assertNotIn("fixture-secret", json.dumps(self.service.describe()))
        self.assertNotIn("fixture-secret", json.dumps(opened))
        self.assertEqual("fixture-secret", self.service.client_configuration()["iceServers"][0]["credential"])
        for invalid in ([{"urls": "https://relay.example"}], [{"urls": "turn:relay.example"}],
                        [{"urls": "stun:relay.example", "unexpected": True}], [{"urls": "stun:relay.example\n"}]):
            with self.assertRaises(DesktopStreamError):
                _ice_servers(invalid)

    def test_sdp_rejects_audio_sending_plain_rtp_and_unbounded_payloads(self):
        for sdp in (OFFER["sdp"].replace("recvonly", "sendrecv"),
                    OFFER["sdp"] + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n",
                    OFFER["sdp"].replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"),
                    "a" * 65537, OFFER["sdp"] + "\x00"):
            with self.assertRaises(DesktopStreamError):
                _offer({"type": "offer", "sdp": sdp})


HAS_RTC = importlib.util.find_spec("aiortc") is not None and importlib.util.find_spec("av") is not None


@unittest.skipUnless(HAS_RTC, "Prepared aiortc/PyAV are unavailable; real WebRTC loopback not exercised")
class DesktopStreamLoopbackTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_sdp_dtls_srtp_video_and_metadata_from_synthetic_source(self):
        from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
        # A true local-only media test: use the known loopback interface, not
        # container/private LAN enumeration (which some sandboxes prohibit).
        # ICE, UDP, DTLS, SRTP, SCTP, encoding and decoding remain real libraries.
        interfaces = patch("aioice.ice.get_host_addresses", return_value=["127.0.0.1"])
        interfaces.start()
        self.addCleanup(interfaces.stop)
        service = DesktopStreamService(FixtureHealth(), capture=FixtureCapture(), fps=5)
        receiver = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        transceiver = receiver.addTransceiver("video", direction="recvonly")
        channel = receiver.createDataChannel(METADATA_CHANNEL, ordered=True)
        matched = asyncio.get_running_loop().create_future()
        decoded_frames, references, received_timestamps, decoded_pts = {}, {}, set(), {}
        # Observe actual receiver RTP headers and the jitter buffer's timestamp
        # mapping, then compare the resulting real decoded AV frame. This does
        # not assume that decoder PTS still contains the random RTP origin.
        original_packet = transceiver.receiver._handle_rtp_packet
        async def received_packet(packet, arrival_time_ms):
            received_timestamps.add(packet.timestamp)
            await original_packet(packet, arrival_time_ms)
        transceiver.receiver._handle_rtp_packet = received_packet
        mapper = transceiver.receiver._RTCRtpReceiver__timestamp_mapper
        original_map = mapper.map
        def timestamp_map(timestamp):
            normalized = original_map(timestamp)
            decoded_pts[normalized] = timestamp
            return normalized
        mapper.map = timestamp_map

        @receiver.on("track")
        async def on_track(track):
            try:
                while not matched.done():
                    frame = await track.recv()
                    timestamp = decoded_pts.get(frame.pts)
                    self.assertIn(timestamp, received_timestamps)
                    decoded_frames[timestamp] = frame
                    if timestamp in references and not matched.done():
                        matched.set_result((frame, references[timestamp]))
            except BaseException as failure:
                if not matched.done():
                    matched.set_exception(failure)

        @channel.on("message")
        def on_metadata(message):
            reference = json.loads(message)
            timestamp = reference["rtpTimestamp"]
            references[timestamp] = reference
            if timestamp in decoded_frames and not matched.done():
                matched.set_result((decoded_frames[timestamp], reference))

        try:
            await receiver.setLocalDescription(await receiver.createOffer())
            offer = {"type": receiver.localDescription.type, "sdp": receiver.localDescription.sdp}
            opened = await asyncio.to_thread(service.open, BINDING, TARGET, "loopback-fixture-owner", offer,
                                             int(time.time() * 1000) + 30000, lambda: None)
            await receiver.setRemoteDescription(RTCSessionDescription(**opened["answer"]))
            frame, reference = await asyncio.wait_for(matched, 12)
            self.assertEqual((320, 200), (frame.width, frame.height))
            self.assertEqual(opened["streamId"], reference["streamId"])
            self.assertEqual("frame", reference.pop("type"))
            self.assertEqual(decoded_pts[frame.pts], reference["rtpTimestamp"])
            self.assertIn(reference["rtpTimestamp"], received_timestamps)
            self.rtp_evidence = {"metadata": copy.deepcopy(reference), "receivedRtpTimestamp": decoded_pts[frame.pts],
                                 "decodedPts": frame.pts, "decodedWidth": frame.width, "decodedHeight": frame.height}
            geometry = service.validate_input(opened["streamId"], "loopback-fixture-owner", BINDING, reference, lambda: None)
            self.assertEqual([20, 30, 340, 230], geometry["captureBounds"])
            from PIL import ImageStat
            with frame.to_image() as decoded:
                self.assertGreater(sum(ImageStat.Stat(decoded).mean) / 3, 20)
            self.assertEqual("connected", receiver.connectionState)
        finally:
            await receiver.close()
            await asyncio.to_thread(service.close_all)


if __name__ == "__main__":
    unittest.main()
