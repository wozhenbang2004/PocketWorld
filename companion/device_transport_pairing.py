"""Isolated candidate server-auth TLS then native-confirmed exact-pin mTLS completion.

A standard-verifies the EXACT invitation B pin before sending any candidate token/offer.
The candidate lane cannot issue a business peer or Permission. The separate completion
lane passes actual mTLS proof ONLY to retained native offer/pending callbacks, never a
business DeviceConnection, DeviceIngress, or a JSON-supplied grant. No retries.
"""
from __future__ import annotations
import asyncio
from collections import deque
import hmac
import inspect
import ipaddress
import socket
import ssl
import threading
import time

from device_transport import FrameDecoder, MAX_PACKET, StreamByteChannel, WssByteChannel, TransportError, encode_control

from device_protocol import MAX_PAIRING_BYTES as MAX_CANDIDATE_BYTES
MAX_TLS_BYTES = 131_072
_LIMIT_LOCK = threading.Lock()
_STARTS = deque()
_ACTIVE = 0
_RESERVATION_PROOF = object()


def candidate_client_context(exact_invitation_certificate_der):
    from device_identity import pinned_context
    return pinned_context(exact_invitation_certificate_der, server_side=False)


def candidate_server_context(native_local_identity):
    # CERT_NONE here means NO CLIENT AUTH on an isolated bootstrap server, not trust-all
    # acceptance of a client identity. This SSLObject can never issue a business peer.
    result = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    result.minimum_version = ssl.TLSVersion.TLSv1_2
    native_local_identity.load_context(result)
    return result


def _deadline(expires_at_ms):
    now = time.time_ns() // 1_000_000
    if type(expires_at_ms) is not int or not now < expires_at_ms <= now + 300_000:
        raise TransportError("Native pairing invitation absent, expired or too long-lived")
    return time.monotonic() + min(15, (expires_at_ms - now) / 1000)


def _enter():
    global _ACTIVE
    now = time.monotonic()
    with _LIMIT_LOCK:
        while _STARTS and now - _STARTS[0] >= 60:
            _STARTS.popleft()
        if _ACTIVE >= 2 or len(_STARTS) >= 12:
            return False
        _ACTIVE += 1; _STARTS.append(now)
        return True


def _leave():
    global _ACTIVE
    with _LIMIT_LOCK:
        _ACTIVE -= 1


def _bounded(raw, maximum=MAX_CANDIDATE_BYTES):
    if not isinstance(raw, bytes) or not 0 < len(raw) <= maximum:
        raise TransportError("Pairing candidate/receipt outside isolated bound")
    return raw


class _CandidateTls:
    """Private SSLObject with no peer/permission accessor and no identity factory call."""
    def __init__(self, context, *, server_side, expected_server_der=None):
        if server_side:
            if context.protocol != ssl.PROTOCOL_TLS_SERVER or context.verify_mode != ssl.CERT_NONE:
                raise TransportError("Bootstrap server must be isolated server-auth TLS, not device admission")
        elif context.verify_mode != ssl.CERT_REQUIRED or not isinstance(expected_server_der, bytes):
            raise TransportError("Pairing client requires standard verification and exact B pin")
        self._incoming, self._outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
        self._ssl = context.wrap_bio(self._incoming, self._outgoing, server_side=server_side)
        self._server, self._expected = server_side, expected_server_der

    def handshake(self):
        try:
            self._ssl.do_handshake()
        except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
            return False
        if self._ssl.version() not in {"TLSv1.2", "TLSv1.3"}:
            raise TransportError("Pairing TLS version incompatible")
        if not self._server and not hmac.compare_digest(self._ssl.getpeercert(binary_form=True) or b"", self._expected):
            raise TransportError("Exact invitation target certificate changed")
        return True

    def feed(self, raw):
        if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_PACKET or self._incoming.pending + len(raw) > 2 * MAX_PACKET:
            raise TransportError("Pairing TLS input outside bound")
        self._incoming.write(raw)

    def encrypted(self):
        while self._outgoing.pending:
            yield self._outgoing.read(MAX_PACKET)

    def plaintext(self):
        while True:
            try:
                raw = self._ssl.read(MAX_CANDIDATE_BYTES + 4)
                if not raw: raise EOFError("Pairing channel closed")
                yield raw
            except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                return

    def write(self, raw):
        position = 0
        while position < len(raw):
            n = self._ssl.write(raw[position:])
            if n <= 0: raise TransportError("Pairing TLS write made no progress")
            position += n


class _Channel:
    def __init__(self, original):
        self.original, self.total = original, 0

    def count(self, raw):
        if raw is not None:
            self.total += len(raw)
            if len(raw) > MAX_PACKET or self.total > MAX_TLS_BYTES:
                raise TransportError("Pairing TLS byte budget exceeded")

    async def receive(self, timeout):
        raw = await self.original.receive(timeout); self.count(raw); return raw

    async def send(self, raw):
        self.count(raw); await self.original.send(raw)

    async def close(self):
        await self.original.close()


async def _flush(tls, channel):
    for raw in tls.encrypted():
        await channel.send(raw)


async def _handshake(tls, channel, deadline):
    while not tls.handshake():
        if time.monotonic() >= deadline: raise TransportError("Pairing TLS handshake timed out")
        await _flush(tls, channel)
        raw = await channel.receive(min(0.1, max(0.001, deadline - time.monotonic())))
        if raw is not None: tls.feed(raw)
    await _flush(tls, channel)


async def _receive_one(tls, channel, deadline, maximum=MAX_CANDIDATE_BYTES):
    decoder = FrameDecoder(max_control_bytes=maximum, allow_binary=False)
    while time.monotonic() < deadline:
        raw = await channel.receive(min(0.1, max(0.001, deadline - time.monotonic())))
        if raw is not None: tls.feed(raw)
        result = None
        for plain in tls.plaintext():
            if result is not None and plain:
                raise TransportError("Extra record on one-request pairing channel")
            values = [frame.control for frame in decoder.feed(plain)]
            if len(values) > 1 or values and decoder.incomplete:
                raise TransportError("Only one complete pairing request/receipt allowed")
            if values: result = _bounded(values[0], maximum)
        await _flush(tls, channel)
        if result is not None: return result
    raise TransportError("Pairing candidate channel timed out")


async def exchange_candidate(channel, *, context, exact_invitation_certificate_der, invitation_expires_at_ms, offer_factory):
    """No retry. offer_factory runs only AFTER actual standard TLS+exact B pin succeeds."""
    channel = _Channel(channel)
    try:
        deadline = _deadline(invitation_expires_at_ms)
        async with asyncio.timeout_at(deadline):
            tls = _CandidateTls(context, server_side=False, expected_server_der=exact_invitation_certificate_der)
            await _handshake(tls, channel, deadline)
            from device_identity import verified_pairing_target
            target = verified_pairing_target(tls._ssl, exact_invitation_certificate_der)
            offer = offer_factory(target)
            if inspect.isawaitable(offer): offer = await asyncio.wait_for(offer, max(0.001, deadline - time.monotonic()))
            _bounded(offer)
            if time.monotonic() >= deadline: raise TransportError("Native pairing offer expired")
            tls.write(encode_control(offer)); await _flush(tls, channel)
            return await _receive_one(tls, channel, deadline)
    finally:
        await channel.close()


async def serve_candidate(channel, *, context, owner_enabled, invitation_expires_at_ms, candidate_handler):
    """Separate owner-opened onboarding only. Handler is the native invitation/signature parser."""
    channel = _Channel(channel); entered = False
    try:
        deadline = _deadline(invitation_expires_at_ms)
        async with asyncio.timeout_at(deadline):
            if owner_enabled is not True or not _enter():
                raise TransportError("Native pairing disabled or onboarding rate limited")
            entered = True
            tls = _CandidateTls(context, server_side=True)
            await _handshake(tls, channel, deadline)
            offer = await _receive_one(tls, channel, deadline)
            receipt = candidate_handler(offer)
            if inspect.isawaitable(receipt): receipt = await asyncio.wait_for(receipt, max(0.001, deadline - time.monotonic()))
            _bounded(receipt)
            if time.monotonic() >= deadline: raise TransportError("Pairing candidate inspection expired")
            tls.write(encode_control(receipt)); await _flush(tls, channel)
    finally:
        if entered: _leave()
        await channel.close()


async def open_candidate_lan(host, port, **candidate_arguments):
    reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=MAX_PACKET), 10)
    return await exchange_candidate(StreamByteChannel(reader, writer), **candidate_arguments)


async def exchange_candidate_relay(endpoint, route_token, *, relay_ssl_context=None, **candidate_arguments):
    if not endpoint.endswith("/pairing"):
        raise TransportError("Onboarding requires its isolated pairing route lane")
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    return await exchange_candidate(channel, **candidate_arguments)


async def serve_candidate_relay(endpoint, route_token, *, relay_ssl_context=None, **candidate_arguments):
    if not endpoint.endswith("/pairing"):
        raise TransportError("Onboarding requires its isolated pairing route lane")
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    await serve_candidate(channel, **candidate_arguments)


async def listen_candidate_lan(host, port, *, context, owner_enabled, invitation_expires_at_ms, candidate_handler):
    _deadline(invitation_expires_at_ms)
    if owner_enabled is not True:
        raise TransportError("Onboarding listener requires an active native owner invitation")
    active = set()
    async def accepted(reader, writer):
        channel = StreamByteChannel(reader, writer); active.add(channel)
        try:
            await serve_candidate(channel, context=context, owner_enabled=True, invitation_expires_at_ms=invitation_expires_at_ms, candidate_handler=candidate_handler)
        except Exception:
            pass  # Candidate token/body/keys never enter diagnostics or a business dispatcher.
        finally:
            active.discard(channel); await channel.close()
    server = await asyncio.start_server(accepted, host, port, limit=MAX_PACKET, backlog=2)
    return CandidateLanListener(server, active, invitation_expires_at_ms)


class CandidateLanListener:
    def __init__(self, server, active, expires):
        self.server, self.active, self.closed = server, active, False
        self._expiry = asyncio.create_task(self._expire(expires))

    async def _expire(self, expires):
        await asyncio.sleep(max(0, (expires - time.time_ns() // 1_000_000) / 1000))
        await self.close()

    @property
    def sockets(self):
        return self.server.sockets

    async def close(self):
        if self.closed: return
        self.closed = True; self.server.close(); await self.server.wait_closed()
        await asyncio.gather(*(channel.close() for channel in tuple(self.active)), return_exceptions=True)
        if self._expiry is not asyncio.current_task():
            self._expiry.cancel()
            await asyncio.gather(self._expiry, return_exceptions=True)


async def exchange_completion(channel, *, context, exact_invitation_certificate_der, invitation_expires_at_ms, request_factory, completion_handler):
    """Actual mTLS peer goes ONLY to native retained-offer finalize/accept callbacks."""
    return await _exchange_completion(channel, context=context, expected_der=exact_invitation_certificate_der, expires=invitation_expires_at_ms,
        request_factory=request_factory, completion_handler=completion_handler, purpose="commit")


async def exchange_completion_recovery(channel, *, context, exact_invitation_certificate_der, recovery_expires_at_ms, request_factory, completion_handler):
    """Read original committed result only; native RecoveryOffer owns request/acceptance."""
    if type(recovery_expires_at_ms) is not int or not 0 < recovery_expires_at_ms - time.time_ns() // 1_000_000 <= 30000:
        await channel.close(); raise TransportError("Native short-lived committed recovery read required")
    return await _exchange_completion(channel, context=context, expected_der=exact_invitation_certificate_der, expires=recovery_expires_at_ms,
        request_factory=request_factory, completion_handler=completion_handler, purpose="recover")


async def _exchange_completion(channel, *, context, expected_der, expires, request_factory, completion_handler, purpose):
    from device_transport import MemoryBioTls
    from device_pairing import parse_pairing_finalize
    channel = _Channel(channel)
    try:
        deadline = _deadline(expires)
        async with asyncio.timeout_at(deadline):
            tls = MemoryBioTls(context, server_side=False, expected_certificate_der=expected_der)
            await _handshake(tls, channel, deadline)
            peer = tls.peer
            request = request_factory(peer)
            if inspect.isawaitable(request): request = await request
            if time.monotonic() >= deadline: raise TransportError("Native completion offer expired")
            _bounded(request, 4096)
            if parse_pairing_finalize(request)["purpose"] != purpose: raise TransportError("Completion request belongs to another native commit/recovery lane")
            tls.write(encode_control(request)); await _flush(tls, channel)
            receipt = await _receive_one(tls, channel, deadline)
            accepted = completion_handler(peer, receipt)
            if inspect.isawaitable(accepted): await accepted
            return receipt
    finally:
        await channel.close()


async def serve_completion(channel, *, context, native_pending, completion_handler):
    """Only native-confirmed pins can enter this mTLS finalize channel; no business dispatcher."""
    from device_pairing import PendingPairing
    if not isinstance(native_pending, PendingPairing):
        await channel.close(); raise TransportError("Native confirmed pairing object required")
    return await _serve_completion(channel, context=context, authority=native_pending, completion_handler=completion_handler, purpose="commit")


def _require_recovery(native_recovery):
    from device_pairing import CommittedPairingRecovery
    if not isinstance(native_recovery, CommittedPairingRecovery): raise TransportError("Native original committed recovery required; expired Pending is not recovery")
    now = time.time_ns() // 1_000_000
    native_recovery.check_current(now)
    if not 0 < native_recovery.expires_at - now <= 30000: raise TransportError("Committed recovery read exceeds its native short lifetime")


async def serve_completion_recovery(channel, *, context, native_recovery, completion_handler):
    """Distinct opaque SQL-issued read handle, never a Pending/Permission or business path."""
    try: _require_recovery(native_recovery)
    except BaseException:
        await channel.close(); raise
    return await _serve_completion(channel, context=context, authority=native_recovery, completion_handler=completion_handler, purpose="recover")


async def _serve_completion(channel, *, context, authority, completion_handler, purpose):
    from device_transport import MemoryBioTls
    from device_pairing import parse_pairing_finalize
    channel = _Channel(channel); entered = False
    try:
        authority.check_current(time.time_ns() // 1_000_000)
        deadline = _deadline(authority.expires_at)
        async with asyncio.timeout_at(deadline):
            if not _enter(): raise TransportError("Onboarding completion rate limited")
            entered = True
            tls = MemoryBioTls(context, server_side=True, expected_certificate_der=authority.peer_certificate_der)
            await _handshake(tls, channel, deadline)
            peer = tls.peer
            if (peer.device_id, peer.certificate_fingerprint) != (authority.expected_device_id, authority.certificate_fingerprint):
                raise TransportError("Completion peer differs from the native confirmed candidate")
            request = await _receive_one(tls, channel, deadline, maximum=4096)
            if parse_pairing_finalize(request)["purpose"] != purpose: raise TransportError("Completion request belongs to another native commit/recovery lane")
            authority.check_current(time.time_ns() // 1_000_000)
            receipt = completion_handler(peer, request)
            if inspect.isawaitable(receipt): receipt = await receipt
            authority.check_current(time.time_ns() // 1_000_000)
            if time.monotonic() >= deadline: raise TransportError("Native pairing completion expired")
            _bounded(receipt); tls.write(encode_control(receipt)); await _flush(tls, channel)
    finally:
        if entered: _leave()
        await channel.close()


async def open_completion_lan(host, port, **completion_arguments):
    reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=MAX_PACKET), 10)
    return await exchange_completion(StreamByteChannel(reader, writer), **completion_arguments)


async def open_completion_recovery_lan(host, port, **recovery_arguments):
    reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=MAX_PACKET), 10)
    return await exchange_completion_recovery(StreamByteChannel(reader, writer), **recovery_arguments)


class CompletionLanReservation:
    """Native bound socket, not a listener, pairing permission or wire endpoint grant.

    The exact socket transfers once to the completion listener after owner confirmation;
    no close/rebind window can silently change the port advertised in the invitation.
    """
    def __init__(self, proof, bound_socket, expires):
        if proof is not _RESERVATION_PROOF:
            raise TransportError("Native completion port reservation required")
        self._socket, self._expires = bound_socket, expires
        address = bound_socket.getsockname()
        self._address = (address[0], address[1])
        self._expiry = asyncio.create_task(self._expire())

    @property
    def address(self):
        return self._address

    @property
    def is_reserved(self):
        return self._socket is not None and self._socket.fileno() >= 0 and time.time_ns() // 1_000_000 < self._expires

    async def _expire(self):
        await asyncio.sleep(max(0, (self._expires - time.time_ns() // 1_000_000) / 1000))
        await self.close()

    async def close(self):
        owned, self._socket = self._socket, None
        if owned is not None: owned.close()
        if self._expiry is not asyncio.current_task():
            self._expiry.cancel()
            await asyncio.gather(self._expiry, return_exceptions=True)

    async def _take(self, expires, host, port):
        if not self.is_reserved or expires > self._expires:
            raise TransportError("Completion port reservation expired, consumed or cannot extend its invitation")
        if host is not None and host != self._address[0] or port is not None and port != self._address[1]:
            raise TransportError("Completion listener differs from its exact native reserved endpoint")
        owned, self._socket = self._socket, None
        self._expiry.cancel()
        try:
            await asyncio.gather(self._expiry, return_exceptions=True)
        except BaseException:
            owned.close()
            raise
        return owned


async def reserve_completion_lan(host, port=0, *, owner_enabled, invitation_expires_at_ms):
    """Bind exclusively without listening; only explicit native onboarding owns this port."""
    _deadline(invitation_expires_at_ms)
    if owner_enabled is not True or type(port) is not int or not 0 <= port <= 65535:
        raise TransportError("Native enabled invitation and a bounded integer port required")
    try:
        if not isinstance(host, str) or "%" in host: raise ValueError()
        address = ipaddress.ip_address(host)
        if address.is_unspecified or address.is_multicast: raise ValueError()
    except ValueError:
        raise TransportError("Explicit local literal LAN address required") from None
    bound = socket.socket(socket.AF_INET6 if address.version == 6 else socket.AF_INET, socket.SOCK_STREAM)
    try:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            bound.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        if address.version == 6: bound.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        bound.setblocking(False)
        bound.bind((address.compressed, port))
        return CompletionLanReservation(_RESERVATION_PROOF, bound, invitation_expires_at_ms)
    except BaseException:
        bound.close()
        raise


async def reserve_completion_recovery_lan(host, port=0, *, native_recovery):
    _require_recovery(native_recovery)
    return await reserve_completion_lan(host, port, owner_enabled=True, invitation_expires_at_ms=native_recovery.expires_at)


async def listen_completion_lan(host=None, port=None, *, context, native_pending, completion_handler, reservation=None):
    from device_pairing import PendingPairing
    if not isinstance(native_pending, PendingPairing): raise TransportError("Native confirmed pairing object required")
    return await _listen_completion_lan(host, port, context=context, authority=native_pending, completion_handler=completion_handler, reservation=reservation, purpose="commit")


async def listen_completion_recovery_lan(host=None, port=None, *, context, native_recovery, completion_handler, reservation=None):
    _require_recovery(native_recovery)
    return await _listen_completion_lan(host, port, context=context, authority=native_recovery, completion_handler=completion_handler, reservation=reservation, purpose="recover")


async def _listen_completion_lan(host, port, *, context, authority, completion_handler, reservation, purpose):
    authority.check_current(time.time_ns() // 1_000_000)
    _deadline(authority.expires_at)
    active = set()
    async def accepted(reader, writer):
        channel = StreamByteChannel(reader, writer); active.add(channel)
        try: await _serve_completion(channel, context=context, authority=authority, completion_handler=completion_handler, purpose=purpose)
        except Exception: pass
        finally: active.discard(channel); await channel.close()
    if reservation is not None:
        if not isinstance(reservation, CompletionLanReservation): raise TransportError("Native completion port reservation required")
        bound = await reservation._take(authority.expires_at, host, port)
        try:
            server = await asyncio.start_server(accepted, sock=bound, limit=MAX_PACKET, backlog=2)
        except BaseException:
            bound.close()
            raise
    else:
        if host is None or port is None: raise TransportError("Explicit completion listener endpoint required")
        server = await asyncio.start_server(accepted, host, port, limit=MAX_PACKET, backlog=2)
    return CandidateLanListener(server, active, authority.expires_at)


async def exchange_completion_relay(endpoint, route_token, *, relay_ssl_context=None, **completion_arguments):
    if not endpoint.endswith("/pairing-complete"):
        raise TransportError("Completion requires its separate confirmed-mTLS route lane")
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    return await exchange_completion(channel, **completion_arguments)


async def serve_completion_relay(endpoint, route_token, *, relay_ssl_context=None, **completion_arguments):
    if not endpoint.endswith("/pairing-complete"):
        raise TransportError("Completion requires its separate confirmed-mTLS route lane")
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    await serve_completion(channel, **completion_arguments)


async def exchange_completion_recovery_relay(endpoint, route_token, *, relay_ssl_context=None, **recovery_arguments):
    if not endpoint.endswith("/pairing-complete"): raise TransportError("Recovery requires the isolated completion mTLS route")
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    return await exchange_completion_recovery(channel, **recovery_arguments)


async def serve_completion_recovery_relay(endpoint, route_token, *, relay_ssl_context=None, **recovery_arguments):
    if not endpoint.endswith("/pairing-complete"): raise TransportError("Recovery requires the isolated completion mTLS route")
    channel = await WssByteChannel.connect(endpoint, route_token, relay_ssl_context=relay_ssl_context)
    await serve_completion_recovery(channel, **recovery_arguments)
