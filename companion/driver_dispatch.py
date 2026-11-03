"""One-use original-process write tickets and cancellable, bounded pipe I/O.

Drivers reserve a ticket under their original authorization/cancellation guard,
then consume it only after releasing that guard. Admission is not a peer ACK.
An incomplete write poisons this transport: no remainder or frame is replayed.
"""
from dataclasses import dataclass
import json
import os
import threading
import time


class DispatchError(Exception):
    pass


class _WindowsPipe:
    """Nonblocking writes on the original synchronous CreatePipe handle.

    CPython <3.12 lacks os.set_blocking for Windows pipes, and its CRT write
    does not normalize full-pipe errors. Use the documented Win32 API directly:
    SetNamedPipeHandleState accepts anonymous pipes; PIPE_NOWAIT makes WriteFile
    return immediately with only the bytes actually accepted. No overlapped I/O.
    """
    def __init__(self, fd):
        import ctypes
        from ctypes import wintypes as w
        import msvcrt

        self.ctypes, self.DWORD = ctypes, w.DWORD
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.handle = msvcrt.get_osfhandle(fd)  # Borrowed, never close or replace.
        self.api.SetNamedPipeHandleState.argtypes = [w.HANDLE, ctypes.POINTER(w.DWORD),
            ctypes.POINTER(w.DWORD), ctypes.POINTER(w.DWORD)]
        self.api.SetNamedPipeHandleState.restype = w.BOOL
        self.api.WriteFile.argtypes = [w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p]
        self.api.WriteFile.restype = w.BOOL
        # Original Popen/WindowsJobProcess stdin is a CreatePipe byte pipe.
        # Do not query mode: its write-only handle lacks FILE_READ_ATTRIBUTES.
        mode = w.DWORD(1)  # PIPE_READMODE_BYTE | PIPE_NOWAIT; requires only GENERIC_WRITE.
        if not self.api.SetNamedPipeHandleState(self.handle, ctypes.byref(mode), None, None):
            raise ctypes.WinError(ctypes.get_last_error())

    def write(self, data):
        raw = bytes(data)
        buffer = self.ctypes.create_string_buffer(raw)
        written = self.DWORD()
        if not self.api.WriteFile(self.handle, buffer, len(raw), self.ctypes.byref(written), None):
            raise self.ctypes.WinError(self.ctypes.get_last_error())
        if not written.value:
            # A full PIPE_NOWAIT pipe can succeed while accepting zero bytes.
            raise BlockingIOError("Original pipe has no space yet")
        if written.value > len(raw):
            raise DispatchError("Original pipe returned an invalid byte count")
        return written.value


@dataclass
class WriteTicket:
    owner: object
    sequence: int
    process: object
    stream: object
    data: bytes
    deadline: float
    stopped: object
    consumed: bool = False


class PipeWriter:
    def __init__(self, process):
        self.process, self.stream = process, process.stdin
        self.sequence = self.last_sequence = 0
        self.failed = False
        self.lock = threading.Lock()
        self.waiter = threading.Event()

    def reserve(self, value, deadline, stopped):
        # Called within the driver's original short dispatch guard. No pipe I/O.
        self.sequence += 1
        return WriteTicket(self, self.sequence, self.process, self.stream,
                           (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8"),
                           min(deadline, time.monotonic() + 5), stopped)

    def _check(self, ticket):
        if (self.failed or ticket.owner is not self or ticket.process is not self.process
                or ticket.stream is not self.stream or self.process.stdin is not self.stream
                or time.monotonic() >= ticket.deadline or ticket.stopped()):
            raise DispatchError("Original pipe dispatch is no longer live")

    def send(self, ticket):
        acquired = False
        try:
            self._check(ticket)
            while not acquired:
                acquired = self.lock.acquire(timeout=.02)
                self._check(ticket)
            if ticket.consumed or ticket.sequence <= self.last_sequence:
                raise DispatchError("Original dispatch ticket was already consumed or superseded")
            ticket.consumed = True
            self.last_sequence = ticket.sequence
            fd = self.stream.fileno()
            # No buffered write/flush or background writer may outlive cancellation.
            # Windows uses the original native pipe even on CPython 3.10/3.11.
            if os.name == "nt":
                write = _WindowsPipe(fd).write
            else:
                os.set_blocking(fd, False)
                write = lambda data: os.write(fd, data)
            remaining = memoryview(ticket.data)
            while remaining:
                self._check(ticket)
                try:
                    written = write(remaining[:4096])
                except BlockingIOError:
                    self.waiter.wait(min(.02, max(0, ticket.deadline-time.monotonic())))
                    continue
                except InterruptedError:
                    continue
                if not written:
                    raise DispatchError("Original pipe accepted no data")
                remaining = remaining[written:]
        except Exception as error:
            self.failed = True
            raise DispatchError("Pipe write is unconfirmed; do not replay") from error
        finally:
            if acquired:
                self.lock.release()
