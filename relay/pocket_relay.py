"""Optional WSS/443 opaque byte rendezvous. No task/model/artifact parsing or storage.

The operator provisions bounded, short-lived route leases out of band. A route lease only
joins two outbound sockets. Endpoints MUST authenticate each other with inner platform TLS.
Routes, timing and traffic volume remain visible to the relay operator. No VPN or hole punch.
No service starts on import; no route/account/long-term device credential is generated here.
"""
from __future__ import annotations
import argparse
import asyncio
from dataclasses import dataclass, field
from collections import deque
import hashlib
import hmac
import json
import logging
import os
from pathlib import Path
import re
import ssl
import time

MAX_PACKET = 65_536
SUBPROTOCOL = "pocketworld.byte-relay.v1"


@dataclass(frozen=True)
class RouteLease:
    route_id: str
    a_token_sha256: str
    b_token_sha256: str
    expires_at: float
    owner_bucket: str = ""  # Operator resource quota only, NEVER a device/business identity.

    def validate(self, now):
        if not re.fullmatch(r"[A-Za-z0-9_-]{22,80}", self.route_id) or any(not re.fullmatch(r"[a-f0-9]{64}", value) for value in (self.a_token_sha256, self.b_token_sha256)):
            raise ValueError("Invalid relay route lease")
        if not isinstance(self.owner_bucket, str) or self.owner_bucket and not re.fullmatch(r"[A-Za-z0-9_-]{8,80}", self.owner_bucket): raise ValueError("Invalid operator quota bucket")
        if not now < self.expires_at <= now + 3600:
            raise ValueError("Relay leases require a remaining lifetime of at most one hour")
        if hmac.compare_digest(self.a_token_sha256, self.b_token_sha256):
            raise ValueError("Relay endpoint slots require different tokens")


@dataclass
class Pair:
    sockets: dict = field(default_factory=dict)
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    closed: bool = False
    ready_sent: int = 0
    forwarding: asyncio.Event = field(default_factory=asyncio.Event)
    expires_at: float = float("inf")


class FairBandwidth:
    """One bounded global byte budget, round-robin per route/side, alternating CONTROL.

    No payloads or task ids enter this scheduler. At most one wait record per socket.
    """
    def __init__(self, bytes_per_second, maximum_waiters):
        self.rate, self.maximum = bytes_per_second, maximum_waiters
        self.condition = asyncio.Condition(); self.groups = ({}, {})
        self.order = (deque(), deque()); self.waiters = 0; self.turn = 0
        self.tokens, self.last = MAX_PACKET, time.monotonic()

    def _head(self):
        category = self.turn if self.order[self.turn] else 1 - self.turn
        if not self.order[category]: return None
        key = self.order[category][0]
        return category, key, self.groups[category][key][0]

    def _remove(self, category, key, ticket):
        queue = self.groups[category].get(key)
        if queue is None or ticket not in queue: return
        queue.remove(ticket); self.waiters -= 1
        if not queue:
            del self.groups[category][key]; self.order[category].remove(key)

    async def acquire(self, key, lane, size):
        if type(size) is not int or not 1 <= size <= MAX_PACKET: raise ValueError("Relay bandwidth record outside bound")
        category = 0 if lane == "control" else 1; ticket = object()
        async with self.condition:
            if self.waiters >= self.maximum: raise ValueError("Relay bandwidth wait bound exceeded")
            if key not in self.groups[category]:
                self.groups[category][key] = deque(); self.order[category].append(key)
            self.groups[category][key].append(ticket); self.waiters += 1
            self.condition.notify_all()
            try:
                while True:
                    now = time.monotonic(); self.tokens = min(MAX_PACKET, self.tokens + (now - self.last) * self.rate); self.last = now
                    head = self._head()
                    if head == (category, key, ticket) and self.tokens >= size:
                        self.tokens -= size; self._remove(category, key, ticket)
                        if key in self.groups[category]: self.order[category].rotate(-1)
                        self.turn = 1 - category; self.condition.notify_all(); return
                    delay = max(0.001, (size - self.tokens) / self.rate) if head == (category, key, ticket) else 0.1
                    try: await asyncio.wait_for(self.condition.wait(), delay)
                    except asyncio.TimeoutError: pass
            finally:
                self._remove(category, key, ticket); self.condition.notify_all()


class ByteRelay:
    def __init__(self, leases, *, bytes_per_second=1_048_576, max_connections=128, global_bytes_per_second=8_388_608, max_owner_connections=8):
        leases = list(leases)
        if not 1 <= len(leases) <= 256 or len({item.route_id for item in leases}) != len(leases):
            raise ValueError("One to 256 unique leased routes required")
        now = time.time()
        for lease in leases:
            lease.validate(now)
        if not 65_536 <= bytes_per_second <= 8_388_608 or not 4 <= max_connections <= 1024 or not 65_536 <= global_bytes_per_second <= 67_108_864 or not 4 <= max_owner_connections <= 64:
            raise ValueError("Relay limits outside bounded policy")
        self.leases = {item.route_id: item for item in leases}
        self.bytes_per_second = bytes_per_second
        self.max_connections = max_connections
        self.connections = 0
        self.bulk_connections = 0
        self.pairing_connections = 0
        self.noncontrol_connections = 0
        self.owner_connections = {}; self.owner_noncontrol = {}; self.max_owner_connections = max_owner_connections
        self.bandwidth = FairBandwidth(global_bytes_per_second, max_connections)
        self.pairs = {}
        self.lock = asyncio.Lock()
        # Intentionally only counters, no payload/routing token logging.
        self.forwarded_bytes = 0

    async def rotate_leases(self, native_operator_leases, *, overlap_seconds=30):
        """Private operator authority only: rotate rendezvous resources, NOT PairGrants.

        Existing device tasks/deadlines/allocations are absent from this API. Endpoints
        reconnect with their native current lease then query ORIGINAL operation ids only.
        """
        if not 0 <= overlap_seconds <= 60: raise ValueError("Relay rotation overlap outside bound")
        incoming = list(native_operator_leases); now = time.time()
        if not 1 <= len(incoming) <= 256 or len({lease.route_id for lease in incoming}) != len(incoming): raise ValueError("Bounded unique native leases required")
        for lease in incoming: lease.validate(now)
        replacements = {lease.route_id: lease for lease in incoming}
        async with self.lock:
            if replacements == self.leases: return
            retained, retiring = {}, []
            for route, old in self.leases.items():
                if route in replacements:
                    new = replacements[route]
                    if (old.a_token_sha256, old.b_token_sha256, old.owner_bucket) != (new.a_token_sha256, new.b_token_sha256, new.owner_bucket): raise ValueError("Rotate to a new route id; existing route identity is immutable")
                    continue
                expiry = min(old.expires_at, now + overlap_seconds)
                if expiry > now: retained[route] = RouteLease(route, old.a_token_sha256, old.b_token_sha256, expiry, old.owner_bucket)
                for (pair_route, _), pair in self.pairs.items():
                    if pair_route == route: retiring.append((pair, expiry))
            if len(replacements) + len(retained) > 256: raise ValueError("Overlapping route rotation exceeds bound")
            for pair, expiry in retiring: pair.expires_at = min(pair.expires_at, expiry)
            self.leases = {**retained, **replacements}

    def _route(self, connection):
        request = connection.request
        if request.headers.get("Origin") is not None or connection.subprotocol != SUBPROTOCOL:
            raise ValueError("Non-device or incompatible relay client")
        match = re.fullmatch(r"/v1/tunnel/([A-Za-z0-9_-]{22,80})/([ab])/(control|bulk|pairing|pairing-complete)", request.path)
        if not match:
            raise ValueError("Invalid leased route")
        route, side, lane = match.groups()
        lease = self.leases.get(route)
        values = request.headers.get_all("Authorization")
        if lease is None or len(values) != 1 or not values[0].startswith("Bearer ") or not time.time() < lease.expires_at:
            raise ValueError("Unavailable relay lease")
        token = values[0][7:]
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
            raise ValueError("Invalid relay lease token")
        expected = lease.a_token_sha256 if side == "a" else lease.b_token_sha256
        if not hmac.compare_digest(hashlib.sha256(token.encode("ascii")).hexdigest(), expected):
            raise ValueError("Unavailable relay lease")
        return route, side, lane, lease

    async def handle(self, connection):
        registered = False
        pair = None
        key = None
        try:
            route, side, lane, lease = self._route(connection)
            key = (route, lane); owner_bucket = lease.owner_bucket or route
            async with self.lock:
                if self.connections >= self.max_connections or lane != "control" and self.noncontrol_connections >= self.max_connections - 2 or self.owner_connections.get(owner_bucket, 0) >= self.max_owner_connections or lane != "control" and self.owner_noncontrol.get(owner_bucket, 0) >= self.max_owner_connections - 2 or lane.startswith("pairing") and self.pairing_connections >= 4:
                    raise ValueError("Relay at capacity")
                pair = self.pairs.setdefault(key, Pair())
                if pair.closed or side in pair.sockets:
                    raise ValueError("Relay slot already occupied")
                pair.sockets[side] = connection; pair.expires_at = min(pair.expires_at, lease.expires_at)
                self.owner_connections[owner_bucket] = self.owner_connections.get(owner_bucket, 0) + 1
                self.noncontrol_connections += int(lane != "control")
                self.owner_noncontrol[owner_bucket] = self.owner_noncontrol.get(owner_bucket, 0) + int(lane != "control")
                self.connections += 1; self.bulk_connections += int(lane == "bulk"); self.pairing_connections += int(lane.startswith("pairing")); registered = True
                if len(pair.sockets) == 2:
                    pair.ready.set()
            await asyncio.wait_for(pair.ready.wait(), min(15, max(0.001, lease.expires_at - time.time())))
            if pair.closed or len(pair.sockets) != 2:
                raise ValueError("Relay peer disconnected")
            await connection.send("ready")
            async with self.lock:
                pair.ready_sent += 1
                if pair.ready_sent == 2:
                    pair.forwarding.set()
            await asyncio.wait_for(pair.forwarding.wait(), 5)
            if pair.closed:
                raise ValueError("Relay peer disconnected")
            peer = pair.sockets["b" if side == "a" else "a"]
            # Rate/window and websocket write_limit bound every stream independently. Control
            # has its own pair and cannot be trapped behind a multi-MiB artifact message.
            window = time.monotonic(); sent = 0; pairing_bytes = 0
            channel_expires = min(lease.expires_at, time.time() + 15) if lane.startswith("pairing") else lease.expires_at
            while not pair.closed:
                remaining = min(channel_expires, pair.expires_at) - time.time()
                if remaining <= 0:
                    raise ValueError("Relay lease expired")
                try:
                    raw = await asyncio.wait_for(connection.recv(), min(remaining, 1))
                except asyncio.TimeoutError:
                    if remaining <= 1:
                        raise ValueError("Relay lease expired")
                    continue
                if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_PACKET:
                    raise ValueError("Relay accepts bounded ciphertext only")
                elapsed = time.monotonic() - window
                if elapsed >= 1:
                    window, sent = time.monotonic(), 0
                if sent + len(raw) > self.bytes_per_second:
                    await asyncio.sleep(max(0, 1 - elapsed)); window, sent = time.monotonic(), 0
                sent += len(raw)
                if lane.startswith("pairing"):
                    pairing_bytes += len(raw)
                    if pairing_bytes > 131_072:
                        raise ValueError("Pairing TLS byte budget exceeded")
                remaining = min(channel_expires, pair.expires_at) - time.time()
                if remaining <= 0: raise ValueError("Relay route expired before forwarding")
                await asyncio.wait_for(self.bandwidth.acquire((route, side), lane, len(raw)), min(10, remaining))
                if time.time() >= min(channel_expires, pair.expires_at): raise ValueError("Relay route expired before forwarding")
                await asyncio.wait_for(peer.send(raw), min(10, max(0.001, min(channel_expires, pair.expires_at) - time.time())))
                self.forwarded_bytes += len(raw)
        except asyncio.CancelledError:
            raise
        except Exception:
            # No exception repr: libraries may include request paths, tokens or binary content.
            pass
        finally:
            other = None
            if registered:
                async with self.lock:
                    count = self.owner_connections.get(owner_bucket, 1) - 1
                    if count: self.owner_connections[owner_bucket] = count
                    else: self.owner_connections.pop(owner_bucket, None)
                    noncontrol = self.owner_noncontrol.get(owner_bucket, 0) - int(lane != "control")
                    if noncontrol: self.owner_noncontrol[owner_bucket] = noncontrol
                    else: self.owner_noncontrol.pop(owner_bucket, None)
                    self.noncontrol_connections -= int(lane != "control")
                    self.connections -= 1; self.bulk_connections -= int(lane == "bulk"); self.pairing_connections -= int(lane.startswith("pairing"))
                    pair.sockets.pop(side, None); pair.closed = True; pair.ready.set(); pair.forwarding.set()
                    other = next(iter(pair.sockets.values()), None)
                    if not pair.sockets:
                        self.pairs.pop(key, None)
            if other is not None:
                await other.close(code=1001, reason="Route disconnected; reconcile original operations")
            await connection.close(code=1008, reason="Relay route unavailable")


async def serve_relay(relay, host, port, tls_context):
    if tls_context is None:
        raise ValueError("Relay requires outer TLS; no plaintext fallback")
    if tls_context.minimum_version < ssl.TLSVersion.TLSv1_2:
        raise ValueError("Relay TLS must require TLS 1.2 or newer")
    from websockets.asyncio.server import serve
    return await serve(relay.handle, host, port, ssl=tls_context, subprotocols=[SUBPROTOCOL],
                       compression=None, max_size=MAX_PACKET, max_queue=4, write_limit=16_384,
                       open_timeout=10, ssl_handshake_timeout=10, backlog=32, close_timeout=2, ping_interval=20, ping_timeout=20,
                       logger=logging.Logger("pocketworld.byte-relay", logging.WARNING))


def load_leases(path):
    info = os.stat(path, follow_symlinks=False)
    if os.name == "nt": raise ValueError("Audited native private relay configuration ACL backend is unavailable")
    if not os.path.isfile(path) or os.path.islink(path) or info.st_size > 262_144 or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_nlink != 1:
        raise ValueError("Relay config must be a private bounded regular file")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate relay config field")
            result[key] = value
        return result
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        actual = os.fstat(stream.fileno())
        if (actual.st_dev, actual.st_ino) != (info.st_dev, info.st_ino): raise ValueError("Native relay config changed during selection")
        raw = stream.read(262_145)
    if len(raw) > 262_144: raise ValueError("Native relay config exceeds bound")
    config = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    if set(config) != {"routes"} or not isinstance(config["routes"], list):
        raise ValueError("Invalid relay config")
    leases = []
    for value in config["routes"]:
        if not isinstance(value, dict) or set(value) - {"routeId", "aTokenSha256", "bTokenSha256", "expiresAt", "ownerBucket"} or not {"routeId", "aTokenSha256", "bTokenSha256", "expiresAt"} <= set(value):
            raise ValueError("Invalid relay route")
        expires = value["expiresAt"]
        if isinstance(expires, bool) or not isinstance(expires, (int, float)):
            raise ValueError("Invalid relay expiry")
        leases.append(RouteLease(value["routeId"], value["aTokenSha256"], value["bTokenSha256"], expires, value.get("ownerBucket", "")))
    return leases


async def main_async(args):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    relay = ByteRelay(load_leases(args.config))
    server = await serve_relay(relay, args.host, args.port, context)
    async def reload_native_config():
        while True:
            await asyncio.sleep(args.reload_seconds)
            try: await relay.rotate_leases(await asyncio.to_thread(load_leases, args.config))
            except Exception: pass  # Last valid leases expire normally; no token/body diagnostics.
    reload_task = asyncio.create_task(reload_native_config())
    try:
        await server.serve_forever()
    finally:
        reload_task.cancel(); await asyncio.gather(reload_task, return_exceptions=True)
        server.close(); await server.wait_closed()


def main():
    parser = argparse.ArgumentParser(description="Optional opaque PocketWorld WSS relay; endpoints still require inner mTLS")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--config", required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--reload-seconds", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.reload_seconds <= 60: parser.error("Native config reload interval outside bound")
    if not 1 <= args.port <= 65535:
        parser.error("Invalid TLS port")
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
