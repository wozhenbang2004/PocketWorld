"""Owner-requested private ADB stream; paired mTLS still authorizes every task.

No device discovery/auto-selection, app bootstrap, token fetch, install, pairing,
or reconnect. The only shell command reads the exact selected phone's boot count.
No shared ADB forward is created or removed: that registry has no compare/delete.
"""
from __future__ import annotations
import ipaddress
import os
import re
from pathlib import Path
import socket
import subprocess
import threading
import time


class UsbForwardError(Exception):
    pass


def _adb_server_target(environment):
    """Freeze the selected TCP server with adb's environment precedence.

    ADB_SERVER_SOCKET takes precedence over ANDROID_ADB_SERVER_ADDRESS/PORT.
    Only explicit TCP endpoints are supported here; an unsupported socket must
    never silently select the default server instead.
    """
    configured = environment.get("ADB_SERVER_SOCKET")
    if configured is not None:
        if not isinstance(configured, str) or not configured.startswith("tcp:"):
            raise UsbForwardError("ADB_SERVER_SOCKET must use TCP for this private stream; non-TCP servers are not supported")
        endpoint = configured[4:]
        if re.fullmatch(r"[0-9]+", endpoint):
            host, port = "localhost", endpoint
        else:
            selected = re.fullmatch(r"\[([^\[\]]+)\]:([0-9]+)|([^:\[\]]+):([0-9]+)", endpoint)
            if selected is None:
                raise UsbForwardError("Use ADB_SERVER_SOCKET=tcp:<port>, tcp:<host>:<port>, or tcp:[<IPv6>]:<port>")
            host, port = (selected[1], selected[2]) if selected[1] is not None else (selected[3], selected[4])
    else:
        host = environment.get("ANDROID_ADB_SERVER_ADDRESS", "localhost")
        port = environment.get("ANDROID_ADB_SERVER_PORT") or "5037"
        if isinstance(host, str) and host.startswith("[") and host.endswith("]"):
            host = host[1:-1]
    if not isinstance(port, str) or not re.fullmatch(r"[0-9]{1,5}", port) or not 1 <= int(port) <= 65535:
        raise UsbForwardError("Selected ADB server port must be an explicit number from 1 to 65535")
    if not isinstance(host, str) or not 0 < len(host) <= 253:
        raise UsbForwardError("Selected ADB server address is invalid")
    if ":" in host:
        try:
            if not re.fullmatch(r"[0-9A-Fa-f:.]+(?:%[A-Za-z0-9_.-]+)?", host):
                raise ValueError()
            ipaddress.IPv6Address(host)
        except ValueError:
            raise UsbForwardError("Selected ADB server IPv6 address is invalid") from None
    elif not re.fullmatch(r"[A-Za-z0-9_.-]+", host):
        raise UsbForwardError("Selected ADB server address is invalid")
    return host, int(port)


class OwnedUsbForward:
    """Retained native API name; now owns one socket, never a global forward.

    ADB's documented host:transport:<serial> followed by tcp:<port> switches the
    same smart socket into a private stream to that exact transport. Replacing a
    shared forward cannot redirect it, and socket.close cannot delete a successor
    mapping. The ADB control and boot observation use the same frozen server.
    """
    def __init__(self, adb, serial, expected_boot, remote_port, *, run=subprocess.run, connect=socket.create_connection, environment=None):
        executable = Path(adb)
        if not executable.is_absolute() or not executable.is_file():
            raise UsbForwardError("Use an explicit existing absolute ADB executable")
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", serial) or type(expected_boot) is not int or expected_boot < 0:
            raise UsbForwardError("An exact owner-selected serial and boot_count are required")
        if type(remote_port) is not int or not 1 <= remote_port <= 65535:
            raise UsbForwardError("Use the existing phone's explicit native control-listener port")
        self.adb, self.serial, self.boot, self.remote_port = str(executable), serial, expected_boot, remote_port
        # Read configuration once, before any dispatch. Later environment changes
        # cannot move either boot observations or the original private stream.
        self._server = _adb_server_target(dict(os.environ if environment is None else environment))
        self.run, self.connect = run, connect
        self._stream = None
        self.state, self.cleanup_confirmed = "not_started", True
        # A cancelled asyncio.to_thread(open) may still be completing ADB. Cleanup
        # waits for that exact attempt and closes only its socket object.
        self._lock = threading.RLock()

    def _adb(self, *arguments):
        # adb ignores ADB_SERVER_SOCKET when -H/-P are explicit. Supply BOTH so
        # ANDROID_ADB_SERVER_ADDRESS/PORT cannot fill either from a changed env.
        host, port = self._server
        cli_host = f"[{host}]" if ":" in host else host
        result = self.run([self.adb, "-H", cli_host, "-P", str(port), "-s", self.serial, *arguments], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=6, check=False)
        if result.returncode != 0 or len(result.stdout) > 65536:
            raise UsbForwardError("Selected ADB operation was not confirmed")
        return result.stdout.decode("utf-8", "strict").strip()

    def check_boot(self):
        value = self._adb("shell", "settings", "get", "global", "boot_count")
        if not value.isdigit() or int(value) != self.boot:
            raise UsbForwardError("Selected phone boot changed; the old USB target cannot be reused")

    @staticmethod
    def _request(stream, service):
        data = service.encode("ascii", "strict")
        if not 0 < len(data) <= 65535:
            raise UsbForwardError("ADB service request outside bound")
        deadline = time.monotonic() + 6

        def remaining():
            left = deadline - time.monotonic()
            if left <= 0:
                raise UsbForwardError("Selected ADB service timed out")
            stream.settimeout(left)

        pending = memoryview(f"{len(data):04x}".encode("ascii") + data)
        while pending:
            remaining()
            sent = stream.send(pending)
            if sent <= 0:
                raise UsbForwardError("Selected ADB request stream closed")
            pending = pending[sent:]
        status = bytearray()
        while len(status) < 4:
            remaining()
            part = stream.recv(4 - len(status))
            if not part:
                raise UsbForwardError("Selected ADB service response was incomplete")
            status.extend(part)
        if status != b"OKAY":
            # A FAIL body is untrusted diagnostic text, not authority or a retry
            # request. Close this exact stream without opening any fallback.
            raise UsbForwardError("Selected ADB service was not accepted")

    def open(self):
        with self._lock:
            return self._open()

    def _open(self):
        if self.state != "not_started":
            raise UsbForwardError("Original USB stream cannot be replayed")
        self.state = "dispatch_prepared"
        try:
            self.check_boot()
            self._stream = self.connect(self._server, timeout=6)
            self.cleanup_confirmed = False
            self._request(self._stream, "host:transport:" + self.serial)
            self._request(self._stream, "tcp:" + str(self.remote_port))
            self.check_boot()
            self._stream.setblocking(False)
            self.state = "ready"
            return self._stream
        except BaseException:
            self._close()
            raise

    def close(self):
        with self._lock:
            return self._close()

    def check_current(self):
        """Check the original stream and serial/boot; never select a replacement."""
        with self._lock:
            if self.state != "ready":
                raise UsbForwardError("Original USB route is no longer ready")
            try:
                self.check_boot()
                if self._stream is None or self._stream.fileno() < 0:
                    raise UsbForwardError("Original USB stream closed")
            except Exception:
                self.state = "invalidated"
                raise

    def is_current(self):
        try:
            self.check_current()
            return True
        except Exception:
            return False

    def safe_status(self):
        """A bounded local observation, not proof of a phone task's execution."""
        with self._lock:
            return {"state": self.state, "cleanupConfirmed": self.cleanup_confirmed,
                    "ownedPortKnown": False, "ownedStreamKnown": self._stream is not None,
                    "transport": "adb_direct_stream", "sharedForwardCreated": False,
                    "cleanupScope": "owned_adb_stream", "automaticReconnect": False}

    def _close(self):
        try:
            stream = self._stream
            if stream is not None:
                try:
                    stream.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                stream.close()
            self.cleanup_confirmed = stream is None or stream.fileno() < 0
            self.state = "closed" if self.cleanup_confirmed else "unknown"
        except Exception:
            self.cleanup_confirmed, self.state = False, "unknown"
        return self.cleanup_confirmed


class _OwnedStreamChannel:
    """The existing TLS engine uses this socket without creating a listener."""
    def __init__(self, stream, forward):
        self.stream, self.forward = stream, forward

    async def receive(self, timeout=0.1):
        import asyncio
        import device_transport as transport
        try:
            raw = await asyncio.wait_for(asyncio.get_running_loop().sock_recv(self.stream, transport.MAX_PACKET), timeout)
        except asyncio.TimeoutError:
            return None
        if not raw:
            raise EOFError("Original ADB stream closed")
        return raw

    async def send(self, raw):
        import asyncio
        import device_transport as transport
        if not 0 < len(raw) <= transport.MAX_PACKET:
            raise transport.TransportError("TLS record outside bound")
        await asyncio.wait_for(asyncio.get_running_loop().sock_sendall(self.stream, raw), 10)

    async def close(self):
        import asyncio
        await asyncio.to_thread(self.forward.close)


class _CurrentUsbChannel:
    """The original byte channel with a current local route check at each write.

    The check also fences received bytes before the original TLS decoder can
    deliver callbacks. Empty receive polls do not run ADB. No packet is retried.
    """
    def __init__(self, original, forward, current_peer):
        self.original, self.forward, self.current_peer = original, forward, current_peer

    async def _current(self):
        import asyncio
        self.current_peer()
        await asyncio.to_thread(self.forward.check_current)
        self.current_peer()

    async def receive(self, timeout=0.1):
        raw = await self.original.receive(timeout)
        if raw is not None:
            await self._current()
        return raw

    async def send(self, raw):
        await self._current()
        await self.original.send(raw)

    async def close(self):
        await self.original.close()


async def attach(service, peer_id, grant_id, forward):
    """Native owner API: joins the existing service loop, with no weaker USB API."""
    import asyncio
    import base64
    import device_transport as transport
    known = service._paired(peer_id)
    if service.receiver.trust.outgoing_grant(peer_id, grant_id) is None:
        raise UsbForwardError("USB needs the same current native signed paired grant")
    connection = None
    admitted = asyncio.Event()
    try:
        # Keep creation inside the cleanup region: cancellation does not stop an
        # already running ADB child, and therefore cannot be called not-dispatched.
        stream = await asyncio.to_thread(forward.open)
        pin = base64.b64decode(known["certificateDer"], validate=True)
        def current_peer():
            if service.closed or getattr(service.manager, "closed", False):
                raise UsbForwardError("Original native device service closed")
            current = service._paired(peer_id)
            if (current["fingerprint"] != known["fingerprint"]
                    or current["authorizationEpoch"] != known["authorizationEpoch"]
                    or current["instanceId"] != known["instanceId"]):
                raise UsbForwardError("Selected paired identity changed; no USB reconnect")
        async def bound(actual, peer):
            current_peer()
            if peer.device_id != peer_id:
                await actual.close("revoked")
                return
            if service.receiver.trust.outgoing_grant(peer_id, grant_id) is None:
                await actual.close("revoked")
                return
            route = service.receiver.pairing.restore_outgoing_grant(peer, grant_id, service.manager.now())
            route.require_target_peer(peer, service.manager.now())
            await service._bind(actual, peer)
            if actual.closed or actual not in service.connections:
                raise UsbForwardError("Original native service did not retain this USB connection")
            original_close = actual.on_close
            async def closed(unknown, error):
                try:
                    if original_close:
                        await original_close(unknown, error)
                finally:
                    await asyncio.to_thread(forward.close)
            actual.on_close = closed
            admitted.set()
        context = transport.native_device_context(service.identity, [pin], server_side=False)
        channel = _CurrentUsbChannel(_OwnedStreamChannel(stream, forward), forward, current_peer)
        connection = transport.DeviceConnection(channel, transport.MemoryBioTls(context, server_side=False, expected_certificate_der=pin),
                                               lane="control", on_frame=lambda peer, frame: None, on_connection=bound)
        task = asyncio.create_task(connection.run())
        service.tasks.append(task)
        # Transport-online alone is insufficient: original native _bind must
        # have retained the exact connection before attachment can succeed.
        for _ in range(100):
            if admitted.is_set() and not connection.closed and connection.state in {"online", "degraded"}:
                return connection
            if connection.closed or task.done():
                raise UsbForwardError("USB mTLS connection was not accepted")
            await asyncio.sleep(0.05)
        raise UsbForwardError("USB mTLS connection timed out; no reconnect queued")
    except BaseException:
        if connection is not None:
            await connection.close()
        await asyncio.shield(asyncio.to_thread(forward.close))
        raise
