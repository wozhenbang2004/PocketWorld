"""Controlled loopback fixture: production Companion/driver, synthetic CLI peers only."""
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading
import time
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_remote_interactions import CODEX, CLAUDE

CODEX_VERSION = "0.109.0"


def main():
    root = Path(sys.argv[1]).resolve()
    workspace, executables = root / "workspace", root / "bin"
    workspace.mkdir(parents=True)
    executables.mkdir()
    question_line = next(line for line in CODEX.splitlines() if "emit({'id':'wire-question'" in line)
    approval = "        emit({'id':'wire-read-approval','method':'item/commandExecution/requestApproval','params':{'threadId':'question-thread','turnId':turn,'itemId':'tool-approval','command':'fixture-only observed command, not executed by this peer','cwd':str(Path.cwd())}})"
    response = "    elif x.get('id')=='wire-read-approval' and 'result' in x:\n        assert x['result']=={'decision':'accept'}\n        Path('read-approval-count').write_text('1')\n        emit({'method':'serverRequest/resolved','params':{'threadId':'question-thread','turnId':turn,'requestId':'wire-read-approval'}})\n" + question_line + "\n"
    codex = CODEX.replace(question_line, approval, 1).replace("    elif x.get('id')=='wire-question'", response + "    elif x.get('id')=='wire-question'", 1)
    for name, source in (("codex", codex), ("claude", CLAUDE)):
        header = "#!" + sys.executable + "\nimport sys\n"
        if name == "codex":
            header += "if '--version' in sys.argv: print('codex-cli " + CODEX_VERSION + " (synthetic read fixture)');sys.exit()\nif '--help' in sys.argv: print('app-server --model');sys.exit()\n"
        header += "sys.argv=[sys.argv[0],'normal',*sys.argv[1:]]\n"
        path = executables / name
        path.write_text(header + source)
        path.chmod(0o700)
    os.environ["PATH"] = str(executables) + os.pathsep + os.environ["PATH"]
    manager = JobManager({"stateDir": str(root / "state"), "environments": [{"id": "workspace", "kind": "local", "root": str(workspace), "workers": ["codex_app_server", "claude"], "writeAllowed": True}]}, "native-read-reconnect-fixture-token-longer-than-32-characters")
    counts = threading.Lock()

    def count(name):
        with counts:
            path = root / name
            temporary = path.with_suffix(".counting")
            temporary.write_text(str(int(path.read_text()) + 1 if path.exists() else 1))
            os.replace(temporary, path)

    class Handler(CompanionHandler):
        def do_POST(self):
            count("all-post-count")
            if self.path == "/v1/jobs":
                count("submit-count")
            if self.path.endswith("/approval"):
                count("approval-post-count")
            if self.path.endswith("/question-answer"):
                count("question-post-count")
            super().do_POST()

        def do_GET(self):
            if self.path.startswith("/v1/jobs/"):
                count("job-get-count")
                if (root / "drop-job-reads").exists():
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
            super().do_GET()

        def _send(self, status, value):
            if self.command == "GET" and self.path.startswith("/v1/jobs/") and value.get("result", {}).get("status") in {"running", "awaiting_approval", "awaiting_input"} and (root / "hold-job-reads").exists():
                (root / "job-read-held").write_text("Production job bytes already read; native view must reject late observations")
                until = time.monotonic() + 8
                while not (root / "release-job-reads").exists() and time.monotonic() < until:
                    time.sleep(.005)
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
        (root / "resource-release.json").write_text(json.dumps({"listenerClosed": server.socket.fileno() == -1, "activeProcesses": len(manager.processes)}))


if __name__ == "__main__":
    main()
