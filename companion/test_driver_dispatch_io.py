"""Real OS-pipe backpressure fixtures; no CLI install, login, network or model calls."""
import concurrent.futures
import io
import json
import os
from types import SimpleNamespace
from unittest.mock import patch
import subprocess
import sys
import threading
import time
import unittest
import uuid

from codex_driver import CodexAppServerDriver, DriverError
from claude_driver import ClaudeStreamDriver
from pi_driver import PiRpcDriver
from driver_dispatch import PipeWriter, DispatchError


BLOCKED = r'''
import json, sys, time
if sys.argv[1] == 'claude':
    request = json.loads(sys.stdin.readline())
    print(json.dumps({'type':'control_response','response':{'subtype':'success',
        'request_id':request['request_id'],'response':{}}}), flush=True)
# The approved original child remains alive but never reads another byte.
time.sleep(30)
'''


class DriverDispatchPipeTests(unittest.TestCase):
    def blocked_prompt(self, worker, timeout, cancel):
        manager = threading.RLock()
        stopped = threading.Event()
        def is_stopped():
            with manager:
                return stopped.is_set()
        process = subprocess.Popen([sys.executable, '-c', BLOCKED, worker],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, bufsize=0)
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        try:
            if worker == 'codex':
                driver = CodexAppServerDriver(process, '.', False, lambda _: None,
                                              is_stopped, timeout, dispatch_guard=lambda: manager)
                action = lambda: driver._start_turn({'input':[{'type':'text','text':'x'*262144}]})
            elif worker == 'claude':
                driver = ClaudeStreamDriver(process, str(uuid.uuid4()), lambda _: None,
                                            is_stopped, timeout, dispatch_guard=lambda: manager)
                action = lambda: driver.run('x'*262144)
            else:
                driver = PiRpcDriver(process, lambda _: None, is_stopped, timeout,
                                     dispatch_guard=lambda: manager, native_session='fixture')
                # Isolate the already-approved prompt dispatch from catalog/session setup.
                driver._bootstrap = lambda *_args, **_kwargs: None
                action = lambda: driver.run('x'*262144)
            future = pool.submit(action)
            until = time.monotonic()+1
            while not driver.turn_dispatched and time.monotonic() < until:
                time.sleep(.005)
            self.assertTrue(driver.turn_dispatched)
            time.sleep(.04)  # Payload exceeds the OS pipe capacity.
            acquired = manager.acquire(timeout=.2)
            if acquired:
                try:
                    if cancel:
                        stopped.set()
                finally:
                    manager.release()
            self.assertTrue(acquired, 'Backpressured stdin held the global manager lock')
            with self.assertRaises(DriverError):
                future.result(timeout=.8)
        finally:
            process.terminate()
            process.wait(timeout=2)
            pool.shutdown(wait=True)
            process.stdin.close()
            process.stdout.close()

    def test_blocked_prompt_keeps_manager_available_and_cancel_is_bounded(self):
        for worker in ('codex', 'claude', 'pi'):
            with self.subTest(worker=worker):
                self.blocked_prompt(worker, 10, cancel=True)

    def test_blocked_prompt_observes_original_deadline_without_cancel(self):
        for worker in ('codex', 'claude', 'pi'):
            with self.subTest(worker=worker):
                self.blocked_prompt(worker, .2, cancel=False)


@unittest.skipIf(os.name == 'nt', 'POSIX descriptor checks; Windows native API contracts have separate tests')
class PipeWriterTicketTests(unittest.TestCase):
    def setUp(self):
        self.read_fd, fd = os.pipe()
        os.set_blocking(self.read_fd, False)
        self.stream = os.fdopen(fd, 'wb', buffering=0)
        self.process = SimpleNamespace(stdin=self.stream)
        self.writer = PipeWriter(self.process)

    def tearDown(self):
        self.stream.close()
        os.close(self.read_fd)

    def read_available(self):
        chunks = []
        while True:
            try:
                chunk = os.read(self.read_fd, 65536)
            except BlockingIOError:
                break
            if not chunk:
                break
            chunks.append(chunk)
        return b''.join(chunks)

    def test_original_ticket_freezes_exact_frame_and_can_be_consumed_only_once(self):
        value = {'id': 'original', 'text': '原始内容'}
        ticket = self.writer.reserve(value, time.monotonic()+1, lambda: False)
        value['text'] = 'later change'
        self.writer.send(ticket)
        self.assertEqual({'id': 'original', 'text': '原始内容'}, json.loads(self.read_available()))
        with self.assertRaises(DispatchError):
            self.writer.send(ticket)
        self.assertEqual(b'', self.read_available())

    def test_partial_timeout_poisoned_transport_cannot_replay_or_send_later_frame(self):
        ticket = self.writer.reserve({'id': 'original', 'text': 'x'*262144},
                                     time.monotonic()+.08, lambda: False)
        with self.assertRaises(DispatchError):
            self.writer.send(ticket)
        partial = self.read_available()
        self.assertGreater(len(partial), 0)
        self.assertLess(len(partial), len(ticket.data))
        self.assertFalse(partial.endswith(b'\n'))
        for next_ticket in (ticket, self.writer.reserve({'id':'replacement'}, time.monotonic()+1, lambda: False)):
            with self.assertRaises(DispatchError):
                self.writer.send(next_ticket)
        time.sleep(.04)  # There is no detached/background writer to finish the old frame.
        self.assertEqual(b'', self.read_available())

    def test_cancel_after_partial_write_stops_before_the_remainder(self):
        stopped = threading.Event()
        write = os.write
        def partial(fd, data):
            count = write(fd, data[:17])
            stopped.set()
            return count
        ticket = self.writer.reserve({'id':'original', 'text':'x'*10000}, time.monotonic()+1, stopped.is_set)
        with patch('driver_dispatch.os.write', side_effect=partial) as calls:
            with self.assertRaises(DispatchError):
                self.writer.send(ticket)
        self.assertEqual(1, calls.call_count)
        self.assertEqual(ticket.data[:17], self.read_available())
        self.assertTrue(self.writer.failed)

    def test_cancel_or_original_stream_replacement_before_write_sends_nothing(self):
        for cancelled in (True, False):
            with self.subTest(cancelled=cancelled):
                writer = PipeWriter(self.process)
                ticket = writer.reserve({'id':'original'}, time.monotonic()+1, lambda: cancelled)
                if not cancelled:
                    self.process.stdin = io.BytesIO()
                with self.assertRaises(DispatchError):
                    writer.send(ticket)
                self.assertEqual(b'', self.read_available())
                self.process.stdin = self.stream

    def test_unsupported_nonblocking_fails_without_blocking_write_or_flush(self):
        ticket = self.writer.reserve({'id':'original'}, time.monotonic()+1, lambda: False)
        with patch('driver_dispatch.os.set_blocking', side_effect=OSError('unsupported')), patch('driver_dispatch.os.write') as write:
            with self.assertRaises(DispatchError):
                self.writer.send(ticket)
        write.assert_not_called()
        self.assertEqual(b'', self.read_available())

    def test_write_budget_is_capped_by_original_deadline_and_five_seconds(self):
        now = time.monotonic()
        ticket = self.writer.reserve({}, now+60, lambda: False)
        self.assertLessEqual(ticket.deadline, now+5.02)
        original = now+.1
        self.assertEqual(original, self.writer.reserve({}, original, lambda: False).deadline)


class DriverAdmissionBoundaryTests(unittest.TestCase):
    def driver(self, worker, stopped=False):
        manager = threading.RLock()
        process = SimpleNamespace(stdin=io.BytesIO(), stdout=io.BytesIO())
        cls = {'codex': CodexAppServerDriver, 'claude': ClaudeStreamDriver, 'pi': PiRpcDriver}[worker]
        with patch.object(cls, '_read', return_value=None):
            if worker == 'codex':
                driver = cls(process, '.', False, lambda _: None, lambda: stopped, 5,
                             dispatch_guard=lambda: manager)
                driver.thread_id, driver.turn_id = 'original-thread', 'original-turn'
                driver.input_steering_verified = True
            elif worker == 'claude':
                driver = cls(process, str(uuid.uuid4()), lambda _: None, lambda: stopped, 5,
                             dispatch_guard=lambda: manager)
                driver.initialized = True
            else:
                driver = cls(process, lambda _: None, lambda: stopped, 5,
                             dispatch_guard=lambda: manager, native_session='original-session')
                driver.prompt_accepted = driver.agent_started = True
                driver.session_file = 'fixture-session.jsonl'
                driver.auto_compaction = False
            driver.protocol_verified = driver.turn_dispatched = True
        def outside_guard(ticket):
            self.assertFalse(manager._is_owned(), 'Pipe I/O ran under the original manager guard')
            self.assertIs(ticket.process, process)
            raise DispatchError('Controlled unconfirmed write')
        driver.writer.send = outside_guard
        return driver

    def test_codex_steer_approval_question_and_interrupt_write_outside_manager(self):
        for kind in ('steer', 'approval', 'question', 'interrupt'):
            with self.subTest(kind=kind):
                driver = self.driver('codex', stopped=kind == 'interrupt')
                if kind == 'steer':
                    statuses = []
                    driver.steers.put(({'expectedTurnId':'original-turn', 'messageId':'original-message', 'text':'input'}, lambda status, _: statuses.append(status)))
                    action = driver._pump
                elif kind == 'approval':
                    driver.pending['original-request'] = {'wireId':'wire-original'}
                    driver.decisions.put(('original-request', 'accept'))
                    action = driver._pump
                elif kind == 'question':
                    action = lambda: driver._question_send('wire-original', {'answers':{}})
                else:
                    action = driver._pump
                with self.assertRaises(DriverError):
                    action()
                if kind == 'steer':
                    self.assertEqual(['sending', 'unknown'], statuses)
                    self.assertTrue(driver.steering_unknown)
                elif kind == 'approval':
                    self.assertEqual('unknown', driver.approval_receipts[-1]['status'])

    def test_claude_approval_question_and_interrupt_write_outside_manager(self):
        for kind in ('approval', 'question', 'interrupt'):
            with self.subTest(kind=kind):
                driver = self.driver('claude', stopped=kind == 'interrupt')
                if kind == 'approval':
                    driver.pending['original-request'] = {'wireId':'wire-original', 'toolUseId':'tool-original', 'input':{}}
                    driver.decisions.put(('original-request', 'accept'))
                    action = driver._decisions
                elif kind == 'question':
                    action = lambda: driver._question_answer('wire-original', {'answers':{}})
                else:
                    action = driver._next
                with self.assertRaises(DriverError):
                    action()
                if kind == 'approval':
                    self.assertEqual('unknown', driver.approval_receipts[-1]['status'])

    def test_pi_steer_stop_clear_abort_and_settled_query_write_outside_manager(self):
        for kind in ('steer', 'clear', 'abort', 'state'):
            with self.subTest(kind=kind):
                driver = self.driver('pi')
                if kind == 'steer':
                    statuses = []
                    driver.steers.put(({'expectedPromptRequestId':driver.prompt_id, 'text':'input'}, lambda status, _: statuses.append(status)))
                elif kind in {'clear', 'abort'}:
                    driver.stop_requested = True
                    driver.abort_deadline = time.monotonic()+1
                    driver.stop_clear_done = kind == 'abort'
                else:
                    driver.settled = driver.final_clear_done = True
                with self.assertRaises(DriverError):
                    driver._pump_controls(time.monotonic())
                if kind == 'steer':
                    self.assertEqual(['sending', 'unknown'], statuses)
                    self.assertTrue(driver.steering_unknown)


if __name__ == '__main__':
    unittest.main()
