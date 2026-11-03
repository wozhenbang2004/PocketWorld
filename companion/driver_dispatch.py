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
            # Unsupported platforms fail closed instead of falling back to blocking I/O.
            os.set_blocking(fd, False)
            remaining = memoryview(ticket.data)
            while remaining:
                self._check(ticket)
                try:
                    written = os.write(fd, remaining[:4096])
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
