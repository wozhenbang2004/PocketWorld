"""Native follow-up inputs fixture: original manager/binary protocol, synthetic CLI only."""
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_input_continuation import INPUT_PIPE
from test_claude_driver import FIXTURE as CLAUDE


def main():
    root = Path(sys.argv[1])
    root.mkdir(exist_ok=True)
    workspace = root / "workspace"
    workspace.mkdir()
    protocol = root / "codex-protocol"
    protocol.mkdir()
    codex = root / "controlled-codex.py"
    # The actual original restricted-read-only handshake remains verified; this is not a
    # product capability override or a model turn before ready.
    codex.write_text(INPUT_PIPE.replace(" elif method in {'thread/start','thread/resume'}:", """ elif method=='config/read':emit({'id':v['id'],'result':{'config':{'mcp_servers':{}}}})
 elif method=='mcpServerStatus/list':emit({'id':v['id'],'result':{'data':[]}})
 elif method in {'thread/start','thread/resume'}:""").replace("'result':{'thread':{'id':'fixture-thread'}}", "'result':{'thread':{'id':'fixture-thread'},'sandbox':{'type':'readOnly'}}"))
    claude = root / "controlled-claude.py"
    claude.write_text(CLAUDE.replace("    elif kind == 'user':\n", """    elif kind == 'user':
        import re,hashlib
        text=value['message']['content']
        paths=re.findall(r'^(.pocketworld-inputs/[^\\n]+) \\([0-9]+ bytes; SHA-256 [a-f0-9]{64}\\)$',text,re.M)
        Path('claude-input-bytes.json').write_text(json.dumps([{'path':path,'bytes':len(Path(path).read_bytes()),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest()}for path in paths]))
"""))
    # Advertised availability is verified by the real production which/version/help path.
    # Only isolated synthetic executables are installed into this fixture process PATH.
    executables = root / "fixture-bin"
    executables.mkdir()
    for name, script, extra in (("codex", codex, str(protocol)), ("claude", claude, "normal")):
        wrapper = executables / name
        wrapper.write_text("#!" + sys.executable + "\nimport os,sys\nos.execv(" + repr(sys.executable) + ", [" + repr(sys.executable) + "," + repr(str(script)) + "," + repr(extra) + ",*sys.argv[1:]])\n")
        wrapper.chmod(0o700)
    os.environ["PATH"] = str(executables) + os.pathsep + os.environ["PATH"]
    token = "native-input-files-fixture-token-longer-than-32-characters"
    manager = JobManager({"stateDir": str(root / "state"), "environments": [{"id": "workspace", "kind": "local", "root": str(workspace), "workers": ["codex_app_server", "claude"], "writeAllowed": True}]}, token)
    lock = threading.Lock()
    def count(name):
        with lock:
            path = root / name
            path.write_text(str(int(path.read_text()) + 1 if path.exists() else 1))
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path == "/v1/jobs":
                count("submit-count")
            if self.path.endswith("/start-with-inputs"):
                count("start-count")
            super().do_POST()
        def do_PUT(self):
            count("put-count")
            super().do_PUT()
        def _send(self, status, value):
            marker = root / "drop-next-upload-ack"
            if self.command == "PUT" and status == 200 and marker.exists():
                marker.unlink()
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            super()._send(status, value)
    server = CompanionServer(("127.0.0.1", 0), manager)
    server.RequestHandlerClass = Handler
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(json.dumps({"baseUrl": "http://127.0.0.1:" + str(server.server_port), "workspace": str(workspace)}), flush=True)
    try:
        server.serve_forever(poll_interval=.02)
    finally:
        server.server_close()
        manager.close()
        (root / "resource-release.json").write_text(json.dumps({"listenerClosed": True, "activeProcesses": len(manager.processes), "activeDrivers": len(manager.drivers), "resources": len(manager.resources), "syntheticCliOnly": True}))


if __name__ == "__main__":
    main()
