"""Independently authored CLI/control fixtures. No login, SDK or model calls."""
import concurrent.futures
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

from claude_driver import ClaudeStreamDriver, DriverError, REQUIRED_FLAGS, command, inspect_cli, session_uuid


FIXTURE = r'''
import json, os, sys, time
from pathlib import Path
mode = sys.argv[1]
args = sys.argv[2:]
if '--version' in args:
    print('2.1.259 (Claude Code)' if mode != 'old-cli' else '2.1.258 (Claude Code)')
    sys.exit()
if '--help' in args:
    print('--safe-mode --restricted --settings --input-format --output-format --verbose --include-partial-messages --permission-prompts --permission-prompt-tool --permission-mode --tools --allowedTools --disallowedTools --session-id --resume --max-turns --model')
    sys.exit()
assert '-p' in args
assert '--restricted' in args
settings=json.loads(args[args.index('--settings')+1])
assert settings['permissions'] == {'ask':['Edit','Write','Bash','AskUserQuestion']}
if mode.startswith('team-'):
    assert settings['disableAllHooks'] is True and settings['enabledPlugins']=={}
    assert '--safe-mode' not in args and '--strict-mcp-config' in args
    assert args[args.index('--setting-sources')+1]==''
    assert set(json.loads(args[args.index('--mcp-config')+1])['mcpServers'])=={'pocket_team'}
assert args[args.index('--input-format')+1] == 'stream-json'
assert args[args.index('--output-format')+1] == 'stream-json'
assert args[args.index('--permission-mode')+1] == 'default'
assert args[args.index('--permission-prompts')+1] == 'host'
assert args[args.index('--permission-prompt-tool')+1] == 'stdio'
assert args[args.index('--allowedTools')+1].startswith('Read,Glob,Grep')
if not mode.startswith('team-'): assert args[args.index('--allowedTools')+1] == 'Read,Glob,Grep'
session = args[args.index('--resume' if '--resume' in args else '--session-id')+1]
tools = args[args.index('--tools')+1].split(',')
team_tools=['mcp__pocket_team__'+name for name in ('team_send_message','team_read_messages','team_list_members')]
if mode.startswith('team-'): tools+=team_tools
child_progress = mode.startswith('child-progress')
progress_version = '2.1.318' if mode == 'child-progress-unknown-version' else '2.1.317'
def emit(value):
    print(json.dumps(value), flush=True)
def progress(suffix):
    return {'type':'tool_progress','session_id':session,'tool_use_id':'same-child-tool',
            'tool_name':'Read','parent_tool_use_id':'toolu_child_'+suffix,
            'uuid':'11223344-5566-7788-9900-11223344556'+('1' if suffix=='a' else '2'),
            'elapsed_time_seconds':1,'text':'NEVER_PROJECT_PROGRESS_TEXT',
            'task_id':'not-a-controllable-child','subagent_retry':{'agent_id':'NEVER_PROJECT_RETRY_AGENT'},
            'usage':{'total_tokens':999999}}
def result(error=False):
    emit({'type':'result', 'session_id':session, 'is_error':error,
          'subtype':'error_during_execution' if error else 'success',
          'result':'真实 fixture response', 'permission_denials':[]})
for line in sys.stdin:
    value=json.loads(line)
    kind=value.get('type')
    if kind == 'control_request' and value['request']['subtype'] == 'initialize':
        assert value['request'] == {'subtype':'initialize', 'hooks':None}
        if mode == 'no-handshake': sys.exit()
        if mode == 'handshake-wait': time.sleep(20)
        if child_progress:
            emit({'type':'control_response','response':{'subtype':'success',
                  'request_id':value['request_id'],'response':{} if mode=='child-progress-missing-version' else {'claude_code_version':progress_version}}})
            continue
        emit({'type':'control_response','response':{'subtype':'success',
              'request_id':'wrong-id' if mode=='wrong-handshake' else value['request_id'],'response':{}}})
    elif kind == 'user':
        assert value['session_id'] == session and value['uuid']
        assert value['parent_tool_use_id'] is None and value['message']['role'] == 'user'
        count=Path('claude-prompt-count')
        count.write_text(count.read_text()+'1' if count.exists() else '1')
        if mode == 'child-progress-before-init': emit(progress('a'))
        emit({'type':'system','subtype':'init','session_id':'wrong-session' if mode=='wrong-session' else session,
              'permissionMode':'bypassPermissions' if mode=='wrong-mode' else 'default',
              'tools':['mcp__external'] if mode=='wrong-tools' else tools,'mcp_servers':[{'name':'foreign' if mode=='team-foreign' else 'pocket_team','status':'connected'}] if mode.startswith('team-') else [],
              'model':'fixture-model',
              **({'claude_code_version':'2.1.318' if mode=='child-progress-system-version-changed' else progress_version} if child_progress and mode!='child-progress-missing-system-version' else {})})
        if mode == 'disconnect': sys.exit()
        if mode == 'invalid-json':
            print('not JSON',flush=True);sys.exit()
        if mode == 'large-frame':
            print('x'*1048577,flush=True);sys.exit()
        if mode in {'wait','cancel-no-result'}: continue
        if mode=='team-permission':
            emit({'type':'control_request','request_id':'tool-request','request':{'subtype':'can_use_tool','tool_name':team_tools[0],'tool_use_id':'peer-call','input':{'targetMemberId':'reviewer','text':'Check evidence'}}})
            continue
        if mode in {'approval','retract','duplicate','outside','lost-reply'}:
            request={'type':'control_request','request_id':'tool-request','request':{
                'subtype':'can_use_tool','tool_name':'Write','tool_use_id':'tool-use-1',
                'input':{'file_path':'../outside' if mode=='outside' else 'result.txt','content':'fixture'},
                'permission_suggestions':[{'type':'addRules','behavior':'allow','destination':'userSettings'}]}}
            emit(request)
            if mode=='duplicate': emit(request)
            if mode=='retract':
                emit({'type':'control_cancel_request','request_id':'tool-request'})
                result()
            continue
        emit({'type':'stream_event','session_id':session,'parent_tool_use_id':None,
              'event':{'type':'content_block_delta','delta':{'type':'text_delta','text':'streamed '}}})
        emit({'type':'stream_event','session_id':session,'parent_tool_use_id':'child-tool',
              'event':{'type':'content_block_delta','delta':{'type':'text_delta','text':'child private'}}})
        if child_progress:
            for suffix in ('a','b'):
                event=progress(suffix)
                if mode == 'child-progress-missing-session': event.pop('session_id')
                if mode == 'child-progress-invalid-parent': event['parent_tool_use_id']={'bad':'identity'}
                if mode == 'child-progress-missing-event-id': event.pop('uuid')
                if mode == 'child-progress-invalid-event-id': event['uuid']='not-a-uuid'
                if mode == 'child-progress-invalid-tool-id': event['tool_use_id']='bad\nidentity'
                if mode == 'child-progress-invalid-tool-name': event['tool_name']='bad\nname'
                if mode == 'child-progress-foreign-session': event['session_id']='22334455-6677-8899-0011-223344556677'
                if mode == 'child-progress-foreign-prompt': event['user_message_uuid']='22334455-6677-8899-0011-223344556677'
                if mode == 'child-progress-foreign-prompts': event['user_message_uuids']=[value['uuid'],'22334455-6677-8899-0011-223344556677']
                emit(event);emit(event)
        if mode == 'child-complete':
            for suffix in ('a','b'):
                child={'type':'assistant','session_id':session,'uuid':'event-child-'+suffix,
                       'parent_tool_use_id':'toolu_child_'+suffix,'agent_id':'agent-'+suffix,
                       'error':'rate_limit' if suffix=='a' else None,
                       'message':{'id':'message-child-'+suffix,'role':'assistant','content':[
                           {'type':'text','text':'same public child text'},
                           {'type':'thinking','thinking':'PRIVATE_INTERNAL_THINKING','signature':'private'},
                           {'type':'tool_use','id':'same-tool-id','name':'Read','input':{'unsafe':'not copied'}}]}}
                emit(child);emit(child)
                emit({'type':'user','session_id':session,'uuid':'result-child-'+suffix,
                      'parent_tool_use_id':'toolu_child_'+suffix,'agent_id':'agent-'+suffix,
                      'message':{'content':[{'type':'tool_result','tool_use_id':'same-tool-id',
                                            'content':'same public tool result','is_error':suffix=='a'}]}})
            emit({'type':'assistant','session_id':session,'uuid':'event-long-child',
                  'parent_tool_use_id':'toolu_child_a',
                  'message':{'content':[{'type':'text','text':'x'*6000}]}})
        if mode in {'child-malformed','child-missing-session','child-invalid-agent'}:
            emit({'type':'assistant','session_id':None if mode=='child-missing-session' else session,
                  'parent_tool_use_id':'bad\\nidentity' if mode=='child-malformed' else 'toolu_child_a',
                  'agent_id':{} if mode=='child-invalid-agent' else 'agent-a',
                  'message':{'content':[{'type':'text','text':'must not attach'}]}})
        if mode == 'output-limit':
            emit({'type':'stream_event','session_id':session,'parent_tool_use_id':None,
                  'event':{'type':'content_block_delta','delta':{'type':'text_delta','text':'x'*210000}}})
        if mode == 'session-result':
            emit({'type':'result','session_id':'other','is_error':False,'subtype':'success','result':'wrong'})
        else: result(mode=='error')
    elif kind == 'control_request' and value['request']['subtype'] == 'interrupt':
        emit({'type':'control_response','response':{'subtype':'success','request_id':value['request_id'],'response':{}}})
        if mode != 'cancel-no-result': result(True)
    elif kind == 'control_response':
        reply=value['response']
        assert reply['request_id']=='tool-request' and reply['subtype']=='success'
        decision=reply['response']
        assert 'updatedPermissions' not in decision
        Path('permission-reply').write_text(json.dumps(decision))
        if mode=='team-permission':
            assert decision['behavior']=='allow' and decision['updatedInput']=={'targetMemberId':'reviewer','text':'Check evidence'}
            result();continue
        if decision['behavior']=='allow':
            assert decision['updatedInput']=={'file_path':'result.txt','content':'fixture'}
            Path('result.txt').write_text('fixture effect')
        if mode=='lost-reply': sys.exit()
        result()
'''


class ClaudeDriverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-claude-protocol-")
        self.root = Path(self.temp.name)
        self.fixture = self.root / "fixture.py"
        self.fixture.write_text(FIXTURE)
        self.processes = []
        self.drivers = []
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def tearDown(self):
        for driver in self.drivers:
            driver.close_steering()
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
            for stream in (process.stdin, process.stdout):
                stream.close()
        self.pool.shutdown(wait=True)
        self.temp.cleanup()

    def driver(self, mode="success", stopped=None, write=False, timeout=5, state_callback=None, team=False):
        session = str(uuid.uuid4())
        descriptor={"name":"pocket_team","command":sys.executable,"args":["fixture-mcp"],"env":[]} if team else None
        process = subprocess.Popen([sys.executable, str(self.fixture), mode, *command(session, write,team_mcp=descriptor)[1:]],
                                   cwd=self.root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, bufsize=0)
        self.processes.append(process)
        states = []
        def state(value):
            states.append(value)
            if state_callback:
                state_callback(value)
        driver = ClaudeStreamDriver(process, session, state, stopped or (lambda: False), timeout,
                                    cwd=self.root, write_allowed=write,team_tools=team)
        self.drivers.append(driver)
        return driver, states

    def pending(self, states):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if states and states[-1].get("pendingApprovals"):
                return states[-1]["pendingApprovals"][0]
            time.sleep(.01)
        self.fail("No exact permission arrived")

    def test_command_uses_structured_stdin_fixed_tools_and_explicit_identity(self):
        selected = str(uuid.uuid4())
        argv = command(selected, True, "fixture-model", True)
        self.assertEqual(selected, argv[argv.index("--resume") + 1])
        self.assertNotIn("--session-id", argv)
        self.assertNotIn("--dangerously-skip-permissions", argv)
        self.assertEqual("Read,Glob,Grep", argv[argv.index("--allowedTools") + 1])
        self.assertEqual("fixture-model", argv[-1])
        for value in ["session-name", "/tmp/session.jsonl", selected.upper(), None]:
            with self.subTest(value=value), self.assertRaises(DriverError):
                session_uuid(value)

    def test_probe_distinguishes_cli_flags_account_protocol_and_inference(self):
        valid = inspect_cli("2.1.259 (Claude Code)", " ".join(REQUIRED_FLAGS))
        self.assertTrue(valid["available"])
        self.assertFalse(valid["protocolVerified"])
        self.assertIsNone(valid["accountConfigured"])
        self.assertFalse(valid["modelInferenceVerified"])
        self.assertFalse(inspect_cli("2.1.258", " ".join(REQUIRED_FLAGS))["available"])
        partial_help = inspect_cli("2.1.259", "--output-format")
        self.assertTrue(partial_help["available"])
        self.assertFalse(partial_help["protocolVerified"])
        self.assertEqual("documented_version_contract", partial_help["cliFlagEvidence"])
        self.assertFalse(partial_help["helpListsAllFlags"])
        self.assertFalse(inspect_cli("3.0.0", " ".join(REQUIRED_FLAGS))["available"])

    def test_team_mcp_is_the_only_loaded_server_and_bounded_tool_permission(self):
        driver,states=self.driver('team-permission',team=True)
        result=driver.run('Share evidence')
        self.assertEqual('completed',result['status'])
        self.assertTrue(result['teamTools'])
        self.assertFalse(any(state['pendingApprovals'] for state in states))
        self.assertFalse((self.root/'result.txt').exists())
        driver,_=self.driver('team-foreign',team=True)
        with self.assertRaises(DriverError):driver.run('Reject unexpected MCP server')

    def test_real_stdio_handshake_session_deltas_and_structured_result(self):
        driver, states = self.driver()
        result = driver.run("Read the project")
        self.assertEqual("completed", result["status"])
        self.assertTrue(result["sessionIdentityConfirmed"])
        self.assertEqual(driver.session_id, result["threadId"])
        self.assertEqual("真实 fixture response", result["output"])
        self.assertTrue(any(state["output"] == "streamed " for state in states))
        self.assertNotIn("child private", json.dumps(states))
        self.assertFalse(driver.can_steer())
        self.assertEqual("1", (self.root / "claude-prompt-count").read_text())

    def test_bad_handshake_never_dispatches_a_prompt(self):
        for mode in ["no-handshake", "wrong-handshake"]:
            with self.subTest(mode=mode):
                driver, _ = self.driver(mode)
                with self.assertRaises(DriverError):
                    driver.run("Must not dispatch")
                self.assertFalse(driver.turn_dispatched)
        self.assertFalse((self.root / "claude-prompt-count").exists())

    def test_complete_public_child_blocks_keep_exact_sources_without_changing_parent_outcome_or_tools(self):
        driver, states = self.driver("child-complete")
        result = driver.run("Read the project")
        self.assertEqual("completed", result["status"])
        self.assertEqual("真实 fixture response", result["output"])
        self.assertNotIn("child private", json.dumps(states))  # Unsupported child partial stream remains ignored.
        self.assertNotIn("PRIVATE_INTERNAL_THINKING", json.dumps(states))
        self.assertNotIn("not copied", json.dumps(states))
        rows = result["progress"]["events"]
        text = [row for row in rows if row["kind"] == "child_text"]
        self.assertEqual(3, len(text))
        self.assertEqual(["toolu_child_a", "toolu_child_b"], [row["parentToolUseId"] for row in text[:2]])
        self.assertEqual(text[0]["text"], text[1]["text"])
        self.assertEqual(4000, len(text[2]["text"]))
        self.assertTrue(text[2]["textTruncated"])
        tools = [row for row in rows if row["kind"] == "tool"]
        self.assertEqual(2, len(tools))
        self.assertEqual(["same-tool-id"] * 2, [row["itemId"] for row in tools])
        results = [row for row in rows if row["kind"] == "tool_result"]
        self.assertEqual(["failed", "completed"], [row["status"] for row in results])
        for row in text + tools + results:
            self.assertEqual("claude_complete_child_v1", row["childSourceProtocol"])
            self.assertEqual(driver.session_id, row["parentSessionId"])
            self.assertEqual(driver.prompt_id, row["parentPromptRequestId"])
        self.assertEqual([], result["pendingQuestions"])
        self.assertEqual([], result["pendingApprovals"])
        self.assertEqual("1", (self.root / "claude-prompt-count").read_text())

    def test_missing_or_malformed_child_lineage_fails_without_attaching_it_to_parent_or_replaying(self):
        for mode in ["child-malformed", "child-missing-session", "child-invalid-agent"]:
            with self.subTest(mode=mode):
                driver, states = self.driver(mode)
                with self.assertRaises(DriverError):
                    driver.run("Read only")
                self.assertNotIn("must not attach", json.dumps(states))
        self.assertEqual("111", (self.root / "claude-prompt-count").read_text())

    def test_parallel_child_tool_progress_keeps_original_sources_and_deduplicates_only_exact_events(self):
        driver, states = self.driver("child-progress")
        result = driver.run("Read the original project")
        rows = [row for row in result["progress"]["events"] if row["nativeType"] == "tool_progress"]
        self.assertEqual(2, len(rows))
        self.assertEqual(["toolu_child_a", "toolu_child_b"], [row["parentToolUseId"] for row in rows])
        self.assertEqual(["same-child-tool"] * 2, [row["itemId"] for row in rows])
        for row in rows:
            self.assertEqual("claude_complete_child_v1", row["childSourceProtocol"])
            self.assertEqual("2.1.317", row["nativeLineageVersion"])
            self.assertEqual(driver.session_id, row["parentSessionId"])
            self.assertEqual(driver.prompt_id, row["parentPromptRequestId"])
            self.assertEqual("Read", row["tool"])
            self.assertEqual("running", row["status"])
            self.assertEqual(str(uuid.UUID(row["nativeEventId"])), row["nativeEventId"])
            self.assertNotIn("text", row)
        self.assertEqual("2.1.317", result["modelRuntime"]["runtimeVersion"])
        self.assertEqual("真实 fixture response", result["output"])
        self.assertEqual([], result["pendingQuestions"])
        self.assertEqual([], result["pendingApprovals"])
        self.assertFalse(driver.can_steer())
        for private in ("NEVER_PROJECT_PROGRESS_TEXT", "NEVER_PROJECT_RETRY_AGENT", "not-a-controllable-child", "999999"):
            self.assertNotIn(private, json.dumps(states))
        self.assertEqual("1", (self.root / "claude-prompt-count").read_text())

    def test_child_progress_without_exact_existing_initialize_and_system_version_is_explicitly_unavailable(self):
        for mode in ("child-progress-unknown-version", "child-progress-missing-version", "child-progress-missing-system-version"):
            with self.subTest(mode=mode):
                driver, _ = self.driver(mode)
                result = driver.run("Original approved invocation only")
                rows = result["progress"]["events"]
                self.assertFalse(any(row["nativeType"] == "tool_progress" for row in rows))
                notice = [row for row in rows if row["nativeType"] == "claude/child_progress/unavailable"]
                self.assertEqual(1, len(notice))
                self.assertNotIn("parentToolUseId", notice[0])
                self.assertNotIn("tool", notice[0])
                self.assertNotIn("text", notice[0])
                self.assertEqual("completed", result["status"])
        self.assertEqual("111", (self.root / "claude-prompt-count").read_text())

    def test_child_progress_identity_failures_never_become_parent_progress_or_replayed_invocations(self):
        modes = ("child-progress-before-init", "child-progress-missing-session", "child-progress-invalid-parent",
                 "child-progress-missing-event-id", "child-progress-invalid-event-id", "child-progress-invalid-tool-id",
                 "child-progress-invalid-tool-name", "child-progress-foreign-session", "child-progress-foreign-prompt",
                 "child-progress-foreign-prompts", "child-progress-system-version-changed")
        for mode in modes:
            with self.subTest(mode=mode):
                driver, states = self.driver(mode)
                with self.assertRaises(DriverError):
                    driver.run("No replacement prompt")
                self.assertFalse(any(row["nativeType"] == "tool_progress" for state in states for row in state["progress"]["events"]))
        self.assertEqual("1" * len(modes), (self.root / "claude-prompt-count").read_text())

    def test_mismatched_identity_and_unconfirmed_permissions_never_become_success(self):
        for mode in ["wrong-session", "session-result", "wrong-mode", "wrong-tools", "invalid-json", "large-frame", "disconnect"]:
            with self.subTest(mode=mode):
                driver, _ = self.driver(mode)
                with self.assertRaises(DriverError):
                    driver.run("Inspect")

    def test_structured_model_failure_is_failed_not_process_exit_success(self):
        driver, _ = self.driver("error")
        result = driver.run("Inspect")
        self.assertEqual("failed", result["status"])
        self.assertEqual("error_during_execution", result["resultSubtype"])

    def test_output_is_bounded_while_final_text_replaces_partial_stream(self):
        driver, states = self.driver("output-limit")
        result = driver.run("Inspect")
        self.assertTrue(result["outputTruncated"])
        self.assertEqual("真实 fixture response", result["output"])
        self.assertLessEqual(max(len(state["output"]) for state in states), 196_608)

    def test_owner_permission_is_exact_once_without_persistent_grants(self):
        driver, states = self.driver("approval", write=True)
        future = self.pool.submit(driver.run, "Write a fixture")
        pending = self.pending(states)
        self.assertEqual("tool-use-1", pending["toolUseId"])
        self.assertEqual(driver.session_id, pending["sessionId"])
        self.assertNotEqual("tool-request", pending["requestId"])
        self.assertFalse((self.root / "result.txt").exists())
        for invalid in [None, [], {}]:
            with self.assertRaises(DriverError):
                driver.decide(pending["requestId"], invalid)
        driver.decide(pending["requestId"], "accept")
        with self.assertRaises(DriverError):
            driver.decide(pending["requestId"], "accept")
        result = future.result(timeout=3)
        self.assertEqual("completed", result["status"])
        self.assertEqual("fixture effect", (self.root / "result.txt").read_text())
        reply = json.loads((self.root / "permission-reply").read_text())
        self.assertNotIn("updatedPermissions", reply)
        self.assertEqual("sent", result["approvalReceipts"][0]["status"])
        with self.assertRaises(DriverError):
            driver.decide(pending["requestId"], "accept")

    def test_read_tools_and_outside_workspace_permissions_are_denied(self):
        for mode, write in [("approval", False), ("outside", True)]:
            with self.subTest(mode=mode):
                driver, states = self.driver(mode, write=write)
                self.assertEqual("completed", driver.run("Inspect")["status"])
                self.assertFalse(any(state["pendingApprovals"] for state in states))
                self.assertEqual("deny", json.loads((self.root / "permission-reply").read_text())["behavior"])
        self.assertFalse((self.root / "result.txt").exists())

    def test_retracted_permission_cannot_be_answered_and_does_not_reopen_turn(self):
        driver, states = self.driver("retract", write=True)
        result = driver.run("Inspect")
        pending = next(state["pendingApprovals"][0] for state in states if state["pendingApprovals"])
        self.assertEqual("completed", result["status"])
        with self.assertRaises(DriverError):
            driver.decide(pending["requestId"], "accept")
        self.assertFalse((self.root / "permission-reply").exists())

    def test_reused_wire_approval_id_is_protocol_failure(self):
        driver, _ = self.driver("duplicate", write=True)
        with self.assertRaises(DriverError):
            driver.run("Inspect")

    def test_cancel_uses_control_interrupt_and_requires_final_result(self):
        stopped = threading.Event()
        driver, states = self.driver("wait", stopped.is_set)
        future = self.pool.submit(driver.run, "Inspect")
        deadline = time.monotonic() + 3
        while not driver.protocol_verified and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(driver.protocol_verified)
        stopped.set()
        result = future.result(timeout=3)
        self.assertEqual("cancelled", result["status"])
        self.assertTrue(result["interruptAcknowledged"])
        self.assertEqual([], result["pendingApprovals"])

    def test_interrupt_ack_alone_does_not_finish_invocation(self):
        stopped = threading.Event()
        driver, _ = self.driver("cancel-no-result", stopped.is_set)
        future = self.pool.submit(driver.run, "Inspect")
        deadline = time.monotonic() + 3
        while not driver.protocol_verified and time.monotonic() < deadline:
            time.sleep(.01)
        stopped.set()
        while not driver.interrupt_acknowledged and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(driver.interrupt_acknowledged)
        self.assertFalse(future.done())
        driver.interrupt_deadline = time.monotonic() - 1
        with self.assertRaises(DriverError):
            future.result(timeout=2)

    def test_stop_during_persisting_approval_intent_sends_only_deny(self):
        stopped = threading.Event()
        def saving(state):
            if state.get("approvalReceipts"):
                stopped.set()
        driver, states = self.driver("approval", stopped.is_set, write=True, state_callback=saving)
        future = self.pool.submit(driver.run, "Inspect")
        driver.decide(self.pending(states)["requestId"], "accept")
        future.result(timeout=3)
        self.assertEqual("deny", json.loads((self.root / "permission-reply").read_text())["behavior"])
        self.assertFalse((self.root / "result.txt").exists())

    def test_lost_result_after_permission_keeps_effect_without_retry(self):
        driver, states = self.driver("lost-reply", write=True)
        future = self.pool.submit(driver.run, "Inspect")
        driver.decide(self.pending(states)["requestId"], "accept")
        with self.assertRaises(DriverError):
            future.result(timeout=3)
        self.assertEqual("fixture effect", (self.root / "result.txt").read_text())
        self.assertEqual("1", (self.root / "claude-prompt-count").read_text())


if __name__ == "__main__":
    unittest.main()
