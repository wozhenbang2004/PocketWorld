"""Controlled loopback peer for original-job replay; no real CLI/account or network target."""
import time
import sys
from pathlib import Path
import read_reconnect_fixture_server as original

# Existing approved initialize/session readbacks, never a new capability probe.
original.CLAUDE = original.CLAUDE.replace("2.1.316 (controlled question protocol fixture)", "2.1.317 (controlled question protocol fixture)", 1)
original.CLAUDE = original.CLAUDE.replace("'response':{}}})", "'response':{'claude_code_version':'2.1.317'}}})", 1)
original.CLAUDE = original.CLAUDE.replace("'mcp_servers':[]})", "'mcp_servers':[],'claude_code_version':'2.1.317'})", 1)
codex_line = next(line for line in original.CODEX.splitlines() if "emit({'method':'turn/started'" in line)
original.CODEX = original.CODEX.replace(codex_line, codex_line + "\n        for n in range(96): emit({'method':'item/commandExecution/outputDelta','params':{'threadId':'question-thread','turnId':turn,'itemId':'burst-item','delta':'controlled observed chunk '+str(n)}})", 1)
claude_line = next(line for line in original.CLAUDE.splitlines() if "emit({'type':'system','subtype':'init'" in line)
original.CLAUDE = original.CLAUDE.replace(claude_line, claude_line + "\n        for n in range(96): emit({'type':'tool_progress','session_id':session,'tool_use_id':'observed-'+str(n),'tool_name':'Read'})", 1)
child_blocks = """
        for suffix in ('a','b'):
            emit({'type':'assistant','session_id':session,'uuid':'public-child-'+suffix,
                  'parent_tool_use_id':'toolu_child_'+suffix,'agent_id':'agent-'+suffix,
                  'message':{'id':'message-child-'+suffix,'content':[
                      {'type':'text','text':'same public child observation'},
                      {'type':'thinking','thinking':'NEVER_EXPOSE_CHILD_THINKING'},
                      {'type':'tool_use','id':'same-child-tool','name':'Read','input':{}}]}})
            progress={'type':'tool_progress','session_id':session,'user_message_uuid':prompt,
                      'parent_tool_use_id':'toolu_child_'+suffix,'tool_use_id':'same-child-tool',
                      'tool_name':'Read','elapsed_time_seconds':1,
                      'uuid':'11223344-5566-7788-9900-11223344556'+('1' if suffix=='a' else '2'),
                      'subagent_retry':{'agent_id':'NEVER_EXPOSE_RETRY_AGENT'},
                      'text':'NEVER_EXPOSE_PROGRESS_TEXT'}
            emit(progress);emit(progress)
            emit({'type':'user','session_id':session,'uuid':'public-result-'+suffix,
                  'parent_tool_use_id':'toolu_child_'+suffix,
                  'message':{'content':[{'type':'tool_result','tool_use_id':'same-child-tool','content':'same public result'}]}})
"""
burst = next(line for line in original.CLAUDE.splitlines() if "range(96)" in line)
original.CLAUDE = original.CLAUDE.replace(burst, burst + child_blocks, 1)
# Optional test-only protocol peer; default original scenarios are unchanged.
if len(sys.argv) > 2 and sys.argv[2] == "codex-children":
    original.CODEX_VERSION = "0.166.0-alpha.1"
    original.CODEX = original.CODEX.replace("'userAgent':'question-fixture'", "'userAgent':'codex_cli_rs/0.166.0-alpha.1'", 1)
    original.CODEX = original.CODEX.replace("    elif m=='turn/steer':", "    elif m=='model/list': emit({'id':x['id'],'result':{'data':[{'model':'fixture-model','displayName':'Explicit synthetic pinned Codex','isDefault':True,'defaultReasoningEffort':'medium','supportedReasoningEfforts':[{'reasoningEffort':'medium','description':'fixture only'}]}]}})\n    elif m=='turn/steer':", 1)
    codex_children = """
        for child in ('codex-child-a','codex-child-b'):
            emit({'method':'thread/started','params':{'thread':{'id':child,'cliVersion':'0.166.0-alpha.1','parentThreadId':'question-thread','source':{'subAgent':{'thread_spawn':{'parent_thread_id':'question-thread','depth':1}}}}}})
            for _ in range(2): emit({'method':'item/agentMessage/delta','params':{'threadId':child,'turnId':'child-turn','itemId':'same-public-item','delta':'PUBLIC '+child}})
            emit({'method':'item/started','params':{'threadId':child,'turnId':'child-turn','item':{'id':'same-tool','type':'commandExecution','command':'PRIVATE_CHILD_COMMAND_NOT_DISPLAYED','cwd':'PRIVATE_CHILD_PATH'}}})
            emit({'method':'item/reasoning/textDelta','params':{'threadId':child,'turnId':'child-turn','itemId':'thinking','delta':'NEVER_EXPOSE_CODEX_CHILD_THINKING'}})
            emit({'method':'thread/tokenUsage/updated','params':{'threadId':child,'turnId':'child-turn','tokenUsage':{'total':{'totalTokens':999999}}}})
            emit({'method':'turn/completed','params':{'threadId':child,'turn':{'id':'child-turn','status':'completed'}}})
        emit({'id':'foreign-child-approval','method':'item/commandExecution/requestApproval','params':{'threadId':'codex-child-a','turnId':'child-turn','itemId':'child-approval','command':'not approved','cwd':str(Path.cwd())}})
"""
    burst = next(line for line in original.CODEX.splitlines() if "range(96)" in line)
    original.CODEX = original.CODEX.replace(burst, burst + codex_children, 1)
    response = "    elif x.get('id')=='foreign-child-approval' and 'result' in x:\n        assert x['result']=={'decision':'decline'}\n        Path('child-declined-count').write_text('1')\n"
    original.CODEX = original.CODEX.replace("    elif x.get('id')=='wire-question'", response + "    elif x.get('id')=='wire-question'", 1)
Base = original.CompanionHandler


class Handler(Base):
    def do_GET(self):
        if "/events?" in self.path:
            counter = self.server.manager.state.parent / "event-get-count"
            counter.write_text(str(int(counter.read_text()) + 1 if counter.exists() else 1))
        super().do_GET()

    def _send(self, status, value):
        root = self.server.manager.state.parent
        if self.command == "GET" and (root / "legacy-event-capabilities").exists():
            result = value.get("result", {})
            if isinstance(result, dict) and isinstance(result.get("capabilities"), dict):
                import copy
                value = copy.deepcopy(value)
                value["result"]["capabilities"].pop("originalJobEvents", None)
        if self.command == "GET" and "/events?" in self.path and (root / "corrupt-child-parent").exists():
            import copy
            value = copy.deepcopy(value)
            for row in value.get("result", {}).get("events", []):
                if row.get("childSourceProtocol") == "codex_public_child_v1":
                    row["nativeParentThreadId"] = "foreign-parent"
                    break
        if self.command == "GET" and "/events?" in self.path and (root / "hold-event-reads").exists():
            (root / "event-read-held").write_text("Original persisted event page prepared; do not acknowledge after page disposal")
            until = time.monotonic() + 8
            while not (root / "release-event-reads").exists() and time.monotonic() < until:
                time.sleep(.005)
        super()._send(status, value)


original.CompanionHandler = Handler
if __name__ == "__main__":
    original.main()
