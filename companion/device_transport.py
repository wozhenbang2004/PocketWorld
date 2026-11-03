"""Device byte transport only: platform TLS, bounded framing and no application retry.

Relay route leases aren't device identities. Every control request still needs the current
DeviceProtocol / PairingCoordinator gate. Queue/write/heartbeat is never business acceptance.
The existing HTTPS Companion remains unchanged.
"""
from __future__ import annotations
import asyncio
from collections import deque
from dataclasses import dataclass
import hmac
import hashlib
import logging
import ipaddress
import inspect
import random
import socket
import ssl
import struct
import threading
import time
from typing import Callable
from urllib.parse import urlsplit
import re
import uuid

MAX_CONTROL = 65_536
MAX_CHUNK = 262_144
MAX_BINARY = MAX_CHUNK + 56
MAX_PACKET = 65_536
SUBPROTOCOL = "pocketworld.byte-relay.v1"


class TransportError(Exception):
    pass


_LAN_RESERVATION_PROOF = object()


class LanPortReservation:
    """Native exclusive bound port, not a listener, device identity or permission.

    Socket ownership is locked so the original service loop can consume the opaque
    reservation without awaiting a Task owned by the pairing/UI loop. The creating
    lifecycle MUST close any unconsumed reservation before stopping its event loop.
    """
    def __init__(self, proof, bound, lane, expires):
        if proof is not _LAN_RESERVATION_PROOF: raise TransportError("Native LAN reservation required")
        self._socket, self._lane, self._expires = bound, lane, expires
        self._address = bound.getsockname()[:2]
        self._lock = threading.Lock()
        self._origin = asyncio.get_running_loop()
        self._expiry = self._origin.call_later(max(0, (expires - time.time_ns() // 1_000_000) / 1000), self._close)

    @property
    def address(self): return self._address

    @property
    def lane(self): return self._lane

    @property
    def is_reserved(self):
        with self._lock:
            return self._socket is not None and self._socket.fileno() >= 0 and time.time_ns() // 1_000_000 < self._expires

    def _cancel_expiry(self):
        try: self._origin.call_soon_threadsafe(self._expiry.cancel)
        except RuntimeError: pass  # Closed originating loop owns no live socket after close/take.

    def _close(self):
        with self._lock:
            bound, self._socket = self._socket, None
        if bound is not None: bound.close()
        self._cancel_expiry()

    async def close(self):
        self._close()  # No await of another loop's Task/Future.

    def _take(self, lane, host, port):
        with self._lock:
            if self._socket is None or self._socket.fileno() < 0 or time.time_ns() // 1_000_000 >= self._expires:
                raise TransportError("Native LAN reservation expired or consumed")
            if lane != self._lane or host is not None and host != self._address[0] or port is not None and port != self._address[1]:
                raise TransportError("Listener differs from the exact native reserved lane/endpoint")
            bound, self._socket = self._socket, None
        self._cancel_expiry()
        return bound


async def reserve_lan_port(host, port=0, *, lane, owner_enabled, expires_at_ms):
    """Explicit native control/bulk port reservation, at most five minutes, no listen."""
    now = time.time_ns() // 1_000_000
    if owner_enabled is not True or lane not in {"control", "bulk"} or type(port) is not int or not 0 <= port <= 65535 or type(expires_at_ms) is not int or not now < expires_at_ms <= now + 300_000:
        raise TransportError("Enabled native bounded LAN reservation required")
    try:
        if not isinstance(host, str) or "%" in host: raise ValueError()
        address = ipaddress.ip_address(host)
        if address.is_unspecified or address.is_multicast: raise ValueError()
    except ValueError:
        raise TransportError("Explicit local literal LAN address required") from None
    bound = socket.socket(socket.AF_INET6 if address.version == 6 else socket.AF_INET, socket.SOCK_STREAM)
    try:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"): bound.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        if address.version == 6: bound.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        bound.setblocking(False); bound.bind((address.compressed, port))
        return LanPortReservation(_LAN_RESERVATION_PROOF, bound, lane, expires_at_ms)
    except BaseException:
        bound.close(); raise


def encode_control(raw: bytes) -> bytes:
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_CONTROL:
        raise TransportError("Control frame outside bound")
    return struct.pack("!I", len(raw)) + raw


def encode_chunk(transfer_id: uuid.UUID, offset: int, raw: bytes) -> bytes:
    if not isinstance(transfer_id, uuid.UUID) or isinstance(offset, bool) or not isinstance(offset, int) or not 0 <= offset < 2**63 or not isinstance(raw, bytes) or len(raw) > MAX_CHUNK:
        raise TransportError("Artifact frame outside bound")
    return struct.pack("!I", 0x80000000 | (len(raw) + 56)) + transfer_id.bytes + struct.pack("!Q", offset) + hashlib.sha256(raw).digest() + raw


@dataclass(frozen=True)
class Frame:
    control: bytes | None = None
    transfer_id: uuid.UUID | None = None
    offset: int = 0
    data: bytes = b""


class FrameDecoder:
    """One bounded frame retained, irrespective of arbitrary TLS fragmentation."""
    def __init__(self, max_control_bytes=MAX_CONTROL, allow_binary=True):
        if type(max_control_bytes) is not int or not 1 <= max_control_bytes <= MAX_CONTROL or type(allow_binary) is not bool:
            raise TransportError("Frame limits outside shared protocol")
        self.max_control_bytes, self.allow_binary = max_control_bytes, allow_binary
        self._header = bytearray()
        self._body = bytearray()
        self._length = None
        self._binary = False

    def feed(self, raw: bytes):
        view = memoryview(raw)
        position = 0
        while position < len(view):
            if self._length is None:
                n = min(4 - len(self._header), len(view) - position)
                self._header.extend(view[position:position + n]); position += n
                if len(self._header) != 4:
                    break
                header = struct.unpack("!I", self._header)[0]
                self._binary = bool(header & 0x80000000)
                self._length = header & 0x7fffffff
                if self._binary and not self.allow_binary or not (56 if self._binary else 1) <= self._length <= (MAX_BINARY if self._binary else self.max_control_bytes):
                    raise TransportError("Invalid bounded device frame")
            n = min(self._length - len(self._body), len(view) - position)
            self._body.extend(view[position:position + n]); position += n
            if len(self._body) == self._length:
                body, binary = bytes(self._body), self._binary
                self._header.clear(); self._body.clear(); self._length = None
                if binary:
                    offset = struct.unpack("!Q", body[16:24])[0]
                    if offset >= 2**63:
                        raise TransportError("Negative/unrepresentable artifact offset")
                    data = body[56:]
                    if not hmac.compare_digest(body[24:56], hashlib.sha256(data).digest()):
                        raise TransportError("Artifact chunk checksum mismatch")
                    yield Frame(transfer_id=uuid.UUID(bytes=body[:16]), offset=offset, data=data)
                else:
                    yield Frame(control=body)

    @property
    def incomplete(self):
        return bool(self._header or self._body)


class MemoryBioTls:
    """A single-owner SSLObject; only the standard platform provider handles TLS.

    The context must require certificate verification. exact peer DER is a pairing pin,
    never supplied by a message. Both sides require the other's certificate.
    """
    def __init__(self, context: ssl.SSLContext, *, server_side: bool, expected_certificate_der: bytes):
        if context.verify_mode != ssl.CERT_REQUIRED or not expected_certificate_der:
            raise TransportError("Verified mutual TLS with an exact paired certificate is required")
        self.incoming, self.outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.ssl = context.wrap_bio(self.incoming, self.outgoing, server_side=server_side)
        pins = (expected_certificate_der,) if isinstance(expected_certificate_der, bytes) else tuple(expected_certificate_der)
        if not 1 <= len(pins) <= (64 if server_side else 1) or any(not isinstance(pin, bytes) or not 64 <= len(pin) <= 16384 for pin in pins):
            raise TransportError("Native paired certificate pins outside bound")
        self.expected_certificates = pins
        self.authenticated = False
        self._peer = None

    def handshake(self):
        if self.authenticated:
            return True
        try:
            self.ssl.do_handshake()
        except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
            return False
        if not any(hmac.compare_digest(self.ssl.getpeercert(binary_form=True) or b"", pin) for pin in self.expected_certificates):
            raise TransportError("Paired device certificate changed")
        if self.ssl.version() not in {"TLSv1.2", "TLSv1.3"}:
            raise TransportError("TLS version is incompatible")
        self.authenticated = True
        return True

    def feed(self, encrypted: bytes):
        if not isinstance(encrypted, bytes) or not 0 < len(encrypted) <= MAX_PACKET or self.incoming.pending + len(encrypted) > 2 * MAX_PACKET:
            raise TransportError("TLS record buffer exceeds bound")
        self.incoming.write(encrypted)

    def encrypted(self):
        while self.outgoing.pending:
            yield self.outgoing.read(MAX_PACKET)

    def plaintext(self):
        if not self.authenticated:
            raise TransportError("TLS handshake not authenticated")
        while True:
            try:
                raw = self.ssl.read(32_768)
                if not raw:
                    raise EOFError("Device TLS closed")
                yield raw
            except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                return

    def write(self, raw: bytes):
        if not self.authenticated or len(raw) > MAX_BINARY + 4:
            raise TransportError("Unauthenticated/oversized write")
        position = 0
        while position < len(raw):
            try:
                n = self.ssl.write(raw[position:position + 16_384])
            except (ssl.SSLWantReadError, ssl.SSLWantWriteError) as failure:
                raise TransportError("TLS write requires reconciliation, not application replay") from failure
            if n <= 0:
                raise TransportError("TLS write made no progress")
            position += n
            # Caller drains after each bounded frame. A frame never exceeds 17 TLS records.

    @property
    def peer(self):
        if not self.authenticated:
            raise TransportError("TLS handshake not authenticated")
        from device_identity import authenticated_peer
        if self._peer is None:
            self._peer = authenticated_peer(self.ssl)
        return self._peer


def native_device_context(local_identity, paired_certificates, *, server_side):
    """Only explicit native identity and bounded already-paired pins; no credential creation."""
    pins = tuple(paired_certificates)
    if not 1 <= len(pins) <= (64 if server_side else 1):
        raise TransportError("Native paired TLS pin set outside bound")
    from device_identity import certificate_fingerprint
    for pin in pins:
        certificate_fingerprint(pin)
    result = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER if server_side else ssl.PROTOCOL_TLS_CLIENT)
    result.minimum_version = ssl.TLSVersion.TLSv1_2
    result.check_hostname = False  # Public-key device identity, not a DNS identity.
    result.verify_mode = ssl.CERT_REQUIRED
    result.load_verify_locations(cadata="".join(ssl.DER_cert_to_PEM_cert(pin) for pin in pins))
    if hasattr(ssl, "VERIFY_X509_PARTIAL_CHAIN"):
        result.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
    local_identity.load_context(result)
    return result


class StreamByteChannel:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.reader, self.writer = reader, writer
        self.writer.transport.set_write_buffer_limits(high=MAX_PACKET, low=16_384)

    async def receive(self, timeout=0.1):
        try:
            raw = await asyncio.wait_for(self.reader.read(MAX_PACKET), timeout)
        except asyncio.TimeoutError:
            return None
        if not raw:
            raise EOFError("Device stream closed")
        return raw

    async def send(self, raw):
        if not 0 < len(raw) <= MAX_PACKET:
            raise TransportError("TLS record outside bound")
        self.writer.write(raw)
        await asyncio.wait_for(self.writer.drain(), 10)

    async def close(self):
        self.writer.close()
        try:
            await asyncio.wait_for(self.writer.wait_closed(), 2)
        except (OSError, asyncio.TimeoutError):
            pass


class WssByteChannel:
    """Both endpoints initiate outbound WSS; the relay only receives inner TLS ciphertext."""
    def __init__(self, websocket):
        self.websocket = websocket

    @classmethod
    async def connect(cls, endpoint: str, route_token: str, *, relay_ssl_context: ssl.SSLContext | None = None):
        uri = urlsplit(endpoint)
        if uri.scheme != "wss" or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or not re.fullmatch(r"/v1/tunnel/[A-Za-z0-9_-]{22,80}/[ab]/(?:control|bulk|pairing|pairing-complete)", uri.path):
            raise TransportError("Exact WSS relay route required")
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", route_token):
            raise TransportError("Short-lived route lease required")
        context = relay_ssl_context or ssl.create_default_context()
        if context.verify_mode != ssl.CERT_REQUIRED or not context.check_hostname:
            raise TransportError("Relay TLS requires platform chain and hostname verification")
        from websockets.asyncio.client import connect

        class ExactRouteConnect(connect):
            def process_redirect(self, exc):
                # Reject before another handshake can send this route's bearer.
                return exc

        websocket = await ExactRouteConnect(endpoint, ssl=context, additional_headers={"Authorization": "Bearer " + route_token},
                                  subprotocols=[SUBPROTOCOL], compression=None, max_size=MAX_PACKET, max_queue=4,
                                  write_limit=16_384, open_timeout=10, close_timeout=2, ping_interval=20, ping_timeout=20,
                                  proxy=None, logger=logging.Logger("pocketworld.device-wss", logging.WARNING))
        try:
            ready = await asyncio.wait_for(websocket.recv(), 15)
            if websocket.subprotocol != SUBPROTOCOL or ready != "ready":
                raise TransportError("Relay rendezvous failed")
        except BaseException:
            await websocket.close(); raise
        return cls(websocket)

    async def receive(self, timeout=0.1):
        try:
            raw = await asyncio.wait_for(self.websocket.recv(), timeout)
        except asyncio.TimeoutError:
            return None
        if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_PACKET:
            raise TransportError("Relay must carry bounded opaque binary records only")
        return raw

    async def send(self, raw):
        if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_PACKET:
            raise TransportError("TLS record outside bound")
        await asyncio.wait_for(self.websocket.send(raw), 10)

    async def close(self):
        await self.websocket.close()


class DeviceConnection:
    """One authenticated connection attempt; no buffered command survives close.

    on_frame(peer, frame) must run current per-request authorization and persist in the
    existing job/task store before replying accepted. on_close must not cancel accepted work.
    Reserve a separate connection for lane='control'; artifact frames cannot use that lane.
    """
    def __init__(self, channel, tls: MemoryBioTls, *, lane: str, on_frame: Callable, on_online=None, on_close=None, on_connection=None):
        if lane not in {"control", "bulk"}:
            raise TransportError("Unknown device lane")
        self.channel, self.tls, self.lane = channel, tls, lane
        self.on_frame, self.on_online, self.on_close = on_frame, on_online, on_close
        self.on_connection = on_connection
        self.state, self.delivery_may_be_unknown = "connecting", False
        self.closed = False
        self._terminal_state = "offline"
        self._online_task = None
        self._callback_error = None
        self._queue = asyncio.Queue(maxsize=16 if lane == "control" else 2)
        self._decoder = FrameDecoder()

    def send_control(self, raw: bytes, *, before_write=None):
        if self.lane != "control":
            raise TransportError("Control requires its reserved TLS lane")
        if before_write is not None and not callable(before_write):
            raise TransportError("Current control-write gate must be callable")
        return self._enqueue(encode_control(raw), before_write)

    def send_chunk(self, transfer_id, offset, raw, *, before_write):
        if self.lane != "bulk":
            raise TransportError("Artifacts require their separate TLS lane")
        if not callable(before_write): raise TransportError("Native current read/exact TLS peer gate required")
        return self._enqueue(bytearray(encode_chunk(transfer_id, offset, raw)), before_write)

    def _enqueue(self, frame, before_write=None):
        if self.closed or self.state not in {"online", "degraded"}:
            if isinstance(frame, bytearray): frame[:] = b"\0" * len(frame)
            raise TransportError("Device offline; no command retained for reconnect")
        try:
            self._queue.put_nowait((frame, before_write))
            return True  # queued only, NOT sent/accepted/executed
        except asyncio.QueueFull:
            if isinstance(frame, bytearray): frame[:] = b"\0" * len(frame)
            return False  # explicit bounded backpressure, caller yields

    async def _flush_tls(self):
        for raw in self.tls.encrypted():
            await self.channel.send(raw)

    async def run(self):
        error = None
        try:
            deadline = time.monotonic() + 15
            while not self.closed and not self.tls.handshake():
                if time.monotonic() >= deadline:
                    raise TransportError("Device TLS handshake timed out")
                await self._flush_tls()
                raw = await self.channel.receive(0.25)
                if raw is not None:
                    self.tls.feed(raw)
            if self.closed:
                return
            await self._flush_tls()
            peer = self.tls.peer
            self.state = "online"
            if self.on_connection is not None:
                await self.on_connection(self, peer)
            if self.on_online is not None:
                async def notify_online():
                    try: await self.on_online(peer)
                    except asyncio.CancelledError: raise
                    except Exception as failure:
                        self._callback_error = type(failure).__name__
                        await self.close()
                # One native reconcile task per bounded connection, not an effect runner.
                # Keep pumping so its ORIGINAL-id query can receive its own response.
                self._online_task = asyncio.create_task(notify_online())
            while not self.closed:
                try:
                    outgoing = self._queue.get_nowait()
                except asyncio.QueueEmpty:
                    outgoing = None
                if outgoing is not None:
                    frame, gate = outgoing
                    try:
                        if gate is not None:
                            checked = gate(peer)
                            if inspect.isawaitable(checked): checked = await checked
                            if checked is False: raise PermissionError("Native request withdrawn before write")
                        self.delivery_may_be_unknown = True
                        self.tls.write(frame)
                        await self._flush_tls()
                    finally:
                        if isinstance(frame, bytearray): frame[:] = b"\0" * len(frame)
                raw = await self.channel.receive(0.01 if outgoing else 0.1)
                if raw is not None:
                    self.tls.feed(raw)
                for plaintext in self.tls.plaintext():
                    for frame in self._decoder.feed(plaintext):
                        if (frame.control is not None) != (self.lane == "control"):
                            raise TransportError("Device frame arrived on the wrong TLS lane")
                        # Native admission/receipt handoff must return promptly; the original
                        # host worker, not this I/O callback, owns long tools and Goals.
                        await self.on_frame(peer, frame)
                await self._flush_tls()
        except asyncio.CancelledError:
            raise
        except Exception as failure:
            error = type(failure).__name__  # never include a token, body or filesystem path
        finally:
            self.closed, self.state = True, self._terminal_state
            if self._online_task is not None:
                self._online_task.cancel(); await asyncio.gather(self._online_task, return_exceptions=True)
            while not self._queue.empty():
                frame, gate = self._queue.get_nowait()
                if isinstance(frame, bytearray): frame[:] = b"\0" * len(frame)
            await self.channel.close()
            if self.on_close is not None:
                await self.on_close(self.delivery_may_be_unknown, error or self._callback_error)

    def mark_degraded(self):
        if self.state == "online": self.state = "degraded"

    async def close(self, state="offline"):
        if state not in {"offline", "revoked", "incompatible"}:
            raise TransportError("Unknown terminal transport state")
        if not self.closed or self._terminal_state == "offline": self._terminal_state = state
        self.closed, self.state = True, self._terminal_state
        await self.channel.close()


class ReconnectBackoff:
    """Only reconnect the channel. Callers re-authorize and query ORIGINAL IDs after TLS."""
    def __init__(self):
        self.failures = 0

    def authenticated(self):
        self.failures = 0

    def disconnected_delay(self):
        maximum = min(30, 0.5 * 2**min(6, self.failures))
        self.failures += 1
        return random.uniform(maximum / 2, maximum)


async def open_lan(host, port, *, context, expected_certificate_der, lane, on_frame, on_online=None, on_close=None, on_connection=None):
    # Address is only a hint; the same paired cert and current authorization apply as on relay.
    reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=MAX_PACKET), 10)
    return DeviceConnection(StreamByteChannel(reader, writer), MemoryBioTls(context, server_side=False, expected_certificate_der=expected_certificate_der),
                            lane=lane, on_frame=on_frame, on_online=on_online, on_close=on_close, on_connection=on_connection)


async def listen_lan(host=None, port=None, *, owner_enabled, context=None, expected_certificate_der=None, native_tls_factory=None, lane, on_frame, on_online=None, on_close=None, on_connection=None, reservation=None):
    """Native factory returns ONE current (verified SSLContext, exact paired DER tuple).

    Refresh per accept, not by rebinding or killing accepted jobs. Current grant/epoch
    checks on every request remain the business router's responsibility.
    """
    if owner_enabled is not True:
        raise TransportError("Device access disabled; no listener was started")
    if native_tls_factory is None:
        if context is None or context.verify_mode != ssl.CERT_REQUIRED:
            raise TransportError("LAN requires verified mutual TLS")
    elif context is not None or expected_certificate_der is not None or not callable(native_tls_factory):
        raise TransportError("Use either a native current TLS factory or fixed paired TLS snapshot")
    slots = asyncio.Semaphore(8)
    active = set()

    async def accepted(reader, writer):
        if slots.locked():
            writer.close(); await writer.wait_closed(); return
        async with slots:
            channel, connection = StreamByteChannel(reader, writer), None
            try:
                current_context, current_pins = (context, expected_certificate_der) if native_tls_factory is None else native_tls_factory()
                connection = DeviceConnection(channel, MemoryBioTls(current_context, server_side=True, expected_certificate_der=current_pins),
                                              lane=lane, on_frame=on_frame, on_online=on_online, on_close=on_close, on_connection=on_connection)
                active.add(connection)
                await connection.run()
            except Exception:
                pass  # Empty/revoked pins reject this socket. No trust-all or payload diagnostics.
            finally:
                if connection is not None: active.discard(connection)
                await channel.close()

    if reservation is not None:
        if not isinstance(reservation, LanPortReservation): raise TransportError("Native LAN port reservation required")
        bound = reservation._take(lane, host, port)
        try:
            server = await asyncio.start_server(accepted, sock=bound, limit=MAX_PACKET, backlog=8)
        except BaseException:
            bound.close(); raise
    else:
        if host is None or port is None: raise TransportError("Explicit native LAN listener endpoint required")
        server = await asyncio.start_server(accepted, host, port, limit=MAX_PACKET, backlog=8)
    # The lifecycle owner must close both listener and accepted channels. No import-time service.
    return LanListener(server, active)


class LanListener:
    def __init__(self, server, active):
        self.server, self.active = server, active

    @property
    def sockets(self):
        return self.server.sockets

    async def close(self):
        self.server.close(); await self.server.wait_closed()
        await asyncio.gather(*(item.close() for item in tuple(self.active)), return_exceptions=True)


async def open_relay(endpoint, route_token, *, context, expected_certificate_der, server_side, lane, on_frame, on_online=None, on_close=None, relay_ssl_context=None, on_connection=None):
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    try:
        return DeviceConnection(channel, MemoryBioTls(context, server_side=server_side, expected_certificate_der=expected_certificate_der),
                                lane=lane, on_frame=on_frame, on_online=on_online, on_close=on_close, on_connection=on_connection)
    except BaseException:
        await channel.close(); raise


class ReconnectingDeviceClient:
    """User-enabled lifecycle. Factory creates fresh TLS; on_online reauthorizes/queries.

    This object accepts no request payloads, so reconnect cannot replay a side effect. The
    original caller must persist operation/task/cursor state in its existing authority.
    """
    def __init__(self, *, owner_enabled, access_deadline_ms, factory):
        if not owner_enabled or access_deadline_ms <= time.time_ns() // 1_000_000:
            raise TransportError("Device access disabled or expired")
        self.deadline, self.factory = access_deadline_ms, factory
        self.closed, self.active = False, None
        self.backoff = ReconnectBackoff()
        self._stop = asyncio.Event()

    async def run(self):
        try:
            while not self.closed and time.time_ns() // 1_000_000 < self.deadline:
                try:
                    self.active = await self.factory()
                    if self.closed:
                        await self.active.close(); break
                    remaining = max(0.001, (self.deadline - time.time_ns() // 1_000_000) / 1000)
                    await asyncio.wait_for(self.active.run(), remaining)
                    if self.active.tls.authenticated:
                        self.backoff.authenticated()
                    if self.active.state in {"revoked", "incompatible"}:
                        self.closed = True; break
                except asyncio.CancelledError:
                    raise
                except Exception:
                    pass  # No body/token-bearing diagnostics, and no pending application frame.
                finally:
                    if self.active is not None:
                        await self.active.close(); self.active = None
                remaining = (self.deadline - time.time_ns() // 1_000_000) / 1000
                if self.closed or remaining <= 0:
                    break
                try:
                    await asyncio.wait_for(self._stop.wait(), min(remaining, self.backoff.disconnected_delay()))
                except asyncio.TimeoutError:
                    pass
        finally:
            self.closed = True
            if self.active is not None:
                await self.active.close(); self.active = None

    async def network_changed(self):
        if self.active is not None:
            await self.active.close()

    async def close(self):
        self.closed = True; self._stop.set()
        if self.active is not None:
            await self.active.close()
