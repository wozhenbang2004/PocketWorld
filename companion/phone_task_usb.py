"""Owner-requested ADB forward only; existing paired mTLS still authorizes tasks.

No device discovery/auto-selection, app bootstrap, token fetch, install, pairing,
remote command execution or reconnect. Tests inject a controlled ADB process port.
"""
from __future__ import annotations
import re
from pathlib import Path
import subprocess
import threading


class UsbForwardError(Exception):
    pass


class OwnedUsbForward:
    def __init__(self, adb, serial, expected_boot, remote_port, *, run=subprocess.run):
        executable = Path(adb)
        if not executable.is_absolute() or not executable.is_file():
            raise UsbForwardError("Use an explicit existing absolute ADB executable")
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", serial) or type(expected_boot) is not int or expected_boot < 0:
            raise UsbForwardError("An exact owner-selected serial and boot_count are required")
        if type(remote_port) is not int or not 1 <= remote_port <= 65535:
            raise UsbForwardError("Use the existing phone's explicit native control-listener port")
        self.adb, self.serial, self.boot, self.remote_port = str(executable), serial, expected_boot, remote_port
        self.run, self.local_port = run, None
        self.state, self.cleanup_confirmed = "not_started", True
        # A cancelled asyncio.to_thread(open) may still be completing ADB. Cleanup
        # waits for that exact attempt rather than racing and leaving its port open.
        self._lock = threading.RLock()

    def _adb(self, *arguments):
        result = self.run([self.adb, "-s", self.serial, *arguments], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=6, check=False)
        if result.returncode != 0 or len(result.stdout) > 65536:
            raise UsbForwardError("Selected ADB operation was not confirmed")
        return result.stdout.decode("utf-8", "strict").strip()

    def check_boot(self):
        value = self._adb("shell", "settings", "get", "global", "boot_count")
        if not value.isdigit() or int(value) != self.boot:
            raise UsbForwardError("Selected phone boot changed; the old USB target cannot be reused")

    def _owned(self):
        if self.local_port is None:
            return False
        expected = (self.serial, "tcp:" + str(self.local_port), "tcp:" + str(self.remote_port))
        rows = [tuple(line.split()) for line in self._adb("forward", "--list").splitlines()]
        return expected in rows and sum(len(row) == 3 and row[1] == expected[1] for row in rows) == 1

    def open(self):
        with self._lock:
            return self._open()

    def _open(self):
        if self.state != "not_started":
            raise UsbForwardError("Original USB forward cannot be replayed")
        self.check_boot()
        self.state, self.cleanup_confirmed = "dispatch_prepared", False
        try:
            value = self._adb("forward", "--no-rebind", "tcp:0", "tcp:" + str(self.remote_port))
            if not value.isdigit() or not 1 <= int(value) <= 65535:
                raise UsbForwardError("Original ADB forward result is unknown; no retry")
            self.local_port = int(value)
            if not self._owned():
                raise UsbForwardError("ADB did not retain this exact original forward")
            self.check_boot()
            self.state = "ready"
            return self.local_port
        except Exception:
            self.state = "unknown"
            # A missing original port is not permission to clear all forwards.
            if self.local_port is not None:
                self.close()
            raise

    def close(self):
        with self._lock:
            return self._close()

    def check_current(self):
        """Read this exact serial/boot/forward; never discover, replace or reconnect."""
        with self._lock:
            if self.state != "ready":
                raise UsbForwardError("Original USB route is no longer ready")
            try:
                self.check_boot()
                if not self._owned():
                    raise UsbForwardError("Original USB forward changed or disappeared")
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
                    "ownedPortKnown": self.local_port is not None, "automaticReconnect": False}

    def _close(self):
        if self.local_port is None:
            return self.cleanup_confirmed
        try:
            # Removal never affects a forward replaced by another actor/serial.
            if self._owned():
                self._adb("forward", "--remove", "tcp:" + str(self.local_port))
            self.cleanup_confirmed = not self._owned()
            self.state = "closed" if self.cleanup_confirmed else "unknown"
        except Exception:
            self.cleanup_confirmed, self.state = False, "unknown"
        return self.cleanup_confirmed


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
        port = await asyncio.to_thread(forward.open)
        await asyncio.to_thread(forward.check_boot)
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
            receipt = service.receiver.trust.outgoing_grant(peer_id, grant_id)
            if receipt is None:
                await actual.close("revoked")
                return
            route = service.receiver.pairing.accept_outgoing_grant(peer, receipt, service.manager.now())
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
        connection = await transport.open_lan("127.0.0.1", port, context=context, expected_certificate_der=pin, lane="control",
                                              on_frame=lambda peer, frame: None, on_connection=bound)
        connection.channel = _CurrentUsbChannel(connection.channel, forward, current_peer)
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
