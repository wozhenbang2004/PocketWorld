"""Win32 API-contract mocks only. These do not claim a Windows runtime pass."""
import ctypes
import os
import subprocess
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from driver_dispatch import PipeWriter, DispatchError


class ApiCall:
    def __init__(self, implementation):
        self.implementation = implementation
        self.argtypes = self.restype = None

    def __call__(self, *args):
        return self.implementation(*args)


class PipeApi:
    """Only documented synchronous PIPE_NOWAIT shapes, not an OS emulator."""
    handle = 0x123456780

    def __init__(self, counts=(), fail=None, on_write=None):
        self.mode = 0  # Anonymous CreatePipe uses byte-read mode, permitting partial writes.
        self.calls, self.output = [], bytearray()
        self.counts, self.fail, self.on_write = list(counts), fail, on_write
        # CreatePipe's write handle need not grant FILE_READ_ATTRIBUTES.
        self.GetNamedPipeHandleStateW = Mock(side_effect=AssertionError('GetMode is not authorized'))
        self.SetNamedPipeHandleState = ApiCall(self.set_mode)
        self.WriteFile = ApiCall(self.write)

    def set_mode(self, handle, mode, maximum, timeout):
        assert handle == self.handle
        assert maximum is timeout is None
        self.calls.append('set_mode')
        if self.fail == 'set_mode':
            return False
        self.mode = mode._obj.value
        return True

    def write(self, handle, data, size, written, overlapped):
        assert handle == self.handle
        assert self.mode & 1  # PIPE_NOWAIT must have succeeded before any write.
        assert overlapped is None  # Original subprocess/CreatePipe handles are synchronous.
        assert 0 < size <= 4096
        self.calls.append('write')
        if self.fail == 'write':
            return False
        count = min(self.counts.pop(0), size) if self.counts else size
        written._obj.value = count
        self.output.extend(ctypes.string_at(data, count))
        if self.on_write:
            self.on_write()
        return True


class WindowsPipeDispatchTests(unittest.TestCase):
    def setUp(self):
        self.process = SimpleNamespace(stdin=SimpleNamespace(fileno=lambda: 42))
        # Model the CPython 3.11 Windows surface: no os.set_blocking at all.
        self.legacy_os = SimpleNamespace(name='nt', write=Mock(side_effect=AssertionError('CRT write is forbidden')))
        self.patches = []

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()

    def use_api(self, api):
        crt = SimpleNamespace(get_osfhandle=Mock(return_value=api.handle))
        for item in (
            patch('driver_dispatch.os', self.legacy_os),
            patch.dict(sys.modules, {'msvcrt': crt}),
            patch('ctypes.WinDLL', return_value=api, create=True),
            patch('ctypes.get_last_error', return_value=109, create=True),
            patch('ctypes.WinError', side_effect=lambda code: OSError(code, 'controlled Win32 error'), create=True),
        ):
            item.start()
            self.patches.append(item)
        return crt

    def test_python311_without_os_set_blocking_sends_exact_one_frame(self):
        api = PipeApi()
        crt = self.use_api(api)
        writer = PipeWriter(self.process)
        ticket = writer.reserve({'id':'original', 'text':'原始输入'}, time.monotonic()+1, lambda: False)
        writer.send(ticket)
        self.assertEqual(ticket.data, bytes(api.output))
        self.assertEqual(1, api.mode)  # PIPE_NOWAIT on the original anonymous byte pipe.
        self.assertEqual(['set_mode', 'write'], api.calls)
        api.GetNamedPipeHandleStateW.assert_not_called()
        crt.get_osfhandle.assert_called_once_with(42)
        self.legacy_os.write.assert_not_called()
        self.assertFalse(hasattr(self.legacy_os, 'set_blocking'))
        self.assertIs(api.WriteFile.argtypes[0], ctypes.c_void_p)  # No 64-bit HANDLE truncation.
        self.assertEqual(5, len(api.WriteFile.argtypes))
        with self.assertRaises(DispatchError):
            writer.send(ticket)
        self.assertEqual(ticket.data, bytes(api.output))

    def test_full_pipe_zero_then_partial_progress_sends_only_unwritten_bytes(self):
        api = PipeApi(counts=(0, 7, 0, 11))
        self.use_api(api)
        writer = PipeWriter(self.process)
        ticket = writer.reserve({'id':'original', 'text':'x'*5000}, time.monotonic()+1, lambda: False)
        writer.send(ticket)
        self.assertEqual(ticket.data, bytes(api.output))
        self.assertGreater(api.calls.count('write'), 4)

    def test_full_pipe_reaches_original_deadline_and_rejects_replay(self):
        api = PipeApi(counts=[0]*100)
        self.use_api(api)
        writer = PipeWriter(self.process)
        ticket = writer.reserve({'id':'original'}, time.monotonic()+.04, lambda: False)
        with self.assertRaises(DispatchError):
            writer.send(ticket)
        calls = len(api.calls)
        for retry in (ticket, writer.reserve({'id':'replacement'}, time.monotonic()+1, lambda: False)):
            with self.assertRaises(DispatchError):
                writer.send(retry)
        self.assertEqual(calls, len(api.calls))
        self.assertEqual(b'', bytes(api.output))
        self.assertTrue(writer.failed)

    def test_cancel_after_partial_write_has_no_detached_remainder(self):
        stopped = threading.Event()
        api = PipeApi(counts=(9,), on_write=stopped.set)
        self.use_api(api)
        writer = PipeWriter(self.process)
        ticket = writer.reserve({'id':'original', 'text':'x'*5000}, time.monotonic()+1, stopped.is_set)
        with self.assertRaises(DispatchError):
            writer.send(ticket)
        self.assertEqual(ticket.data[:9], bytes(api.output))
        self.assertEqual(1, api.calls.count('write'))
        self.assertTrue(writer.failed)

    def test_native_mode_failure_never_falls_back_and_broken_pipe_is_unknown(self):
        for failure in ('set_mode', 'write'):
            with self.subTest(failure=failure):
                api = PipeApi(fail=failure)
                self.use_api(api)
                writer = PipeWriter(self.process)
                ticket = writer.reserve({'id':'original'}, time.monotonic()+1, lambda: False)
                with self.assertRaises(DispatchError):
                    writer.send(ticket)
                self.assertTrue(writer.failed)
                self.assertEqual(b'', bytes(api.output))
                if failure != 'write':
                    self.assertNotIn('write', api.calls)
                self.legacy_os.write.assert_not_called()


@unittest.skipUnless(os.name == 'nt', 'Needs actual Windows CreatePipe/WriteFile, not a contract mock')
class WindowsPipeRuntimeTests(unittest.TestCase):
    def test_original_native_pipe_does_not_require_newer_python_os_functions(self):
        process = subprocess.Popen([sys.executable, '-c',
            'import sys; sys.stdout.buffer.write(sys.stdin.buffer.readline()); sys.stdout.buffer.flush()'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        try:
            writer = PipeWriter(process)
            ticket = writer.reserve({'id':'original', 'text':'原生输入'}, time.monotonic()+2, lambda: False)
            with patch('driver_dispatch.os.set_blocking', side_effect=AssertionError('not supported on 3.11'), create=True), \
                    patch('driver_dispatch.os.write', side_effect=AssertionError('no CRT fallback')):
                writer.send(ticket)
            process.wait(timeout=2)
            self.assertEqual(ticket.data, process.stdout.read())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=2)
            process.stdin.close()
            process.stdout.close()


if __name__ == '__main__':
    unittest.main()
