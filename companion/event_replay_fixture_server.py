"""Controlled loopback peer for original-job replay; no real CLI/account or network target."""
import time
from pathlib import Path
import read_reconnect_fixture_server as original

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
            emit({'type':'user','session_id':session,'uuid':'public-result-'+suffix,
                  'parent_tool_use_id':'toolu_child_'+suffix,
                  'message':{'content':[{'type':'tool_result','tool_use_id':'same-child-tool','content':'same public result'}]}})
"""
burst = next(line for line in original.CLAUDE.splitlines() if "range(96)" in line)
original.CLAUDE = original.CLAUDE.replace(burst, burst + child_blocks, 1)
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
        if self.command == "GET" and "/events?" in self.path and (root / "hold-event-reads").exists():
            (root / "event-read-held").write_text("Original persisted event page prepared; do not acknowledge after page disposal")
            until = time.monotonic() + 8
            while not (root / "release-event-reads").exists() and time.monotonic() < until:
                time.sleep(.005)
        super()._send(status, value)


original.CompanionHandler = Handler
if __name__ == "__main__":
    original.main()
