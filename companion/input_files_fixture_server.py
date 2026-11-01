"""Native original-SQL/Book fixture. Real HTTP/Companion, synthetic installed CLI peers only."""
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading
import time
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_input_files import INPUT_FIXTURE
from test_claude_driver import FIXTURE as CLAUDE_FIXTURE


def main():
    root = Path(sys.argv[1]).resolve()
    workspace, executables = root / "workspace", root / "bin"
    workspace.mkdir(parents=True)
    executables.mkdir()
    summary = """        import re, hashlib
        text=value['message']['content']
        inputs=re.findall(r'^(.pocketworld-inputs/[^\\n]+) \\([0-9]+ bytes; SHA-256 [a-f0-9]{64}\\)$',text,re.M)
        summary=[{'path':path,'bytes':len(Path(path).read_bytes()),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest()} for path in inputs]
        Path('input-summary.json').write_text(json.dumps(summary))
        Path('proof.txt').write_text('INPUTS ACTUALLY READ BY CONTROLLED CLI: '+json.dumps(summary))
"""
    codex = INPUT_FIXTURE.replace("        Path('input-summary.json').write_text(json.dumps(summary))", "        Path('input-summary.json').write_text(json.dumps(summary))\n        Path('proof.txt').write_text('INPUTS ACTUALLY READ BY CONTROLLED CLI: '+json.dumps(summary))")
    claude = CLAUDE_FIXTURE.replace("    elif kind == 'user':\n", "    elif kind == 'user':\n" + summary)
    for executable, mode, source in (("codex", "final-without-start-ack", codex), ("claude", "success", claude)):
        header = "#!" + sys.executable + "\nimport sys\nsys.argv=[sys.argv[0]," + repr(mode) + ",*sys.argv[1:]]\n"
        path = executables / executable
        path.write_text(header + source)
        path.chmod(0o700)
    os.environ["PATH"] = str(executables) + os.pathsep + os.environ["PATH"]
    token = "native-input-files-fixture-token-longer-than-32-characters"
    manager = JobManager({"stateDir": str(root / "state"), "environments": [{
        "id": "workspace", "kind": "local", "root": str(workspace),
        "workers": ["codex_app_server", "claude"], "writeAllowed": True}]}, token)

    def count(name):
        path = root / name
        path.write_text(str(int(path.read_text()) + 1 if path.exists() else 1))

    original_input_states = {}

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
            actual = value.get("result", {}) if isinstance(value, dict) else {}
            if self.command == "POST" and self.path == "/v1/jobs" and status == 200 and actual.get("inputState"):
                original_input_states[actual["id"]] = json.loads(json.dumps(actual["inputState"]))
            if self.command == "GET" and self.path.startswith("/v1/jobs/") and status == 200 and actual.get("inputState"):
                # Explicit test-only mixed observation: real new job event plus an earlier authenticated input component.
                if (root / "send-old-input-with-new-job-message").exists():
                    with manager.lock:
                        job = manager.jobs[actual["id"]]
                        job.update(message="NEW ORIGINAL JOB EVENT WITH EARLIER INPUT COMPONENT", updatedAt=manager.now())
                        manager._save(job)
                        value = {"ok": True, "result": manager._public(job)}
                    value["result"]["inputState"] = json.loads(json.dumps(original_input_states[actual["id"]]))
                if (root / "same-input-revision-conflict").exists():
                    value = json.loads(json.dumps(value))
                    value["result"]["inputState"]["copySemantics"] = "conflicting_same_revision_claim"
            if self.command == "GET" and self.path == "/v1/environments" and (root / "old-input-capability").exists():
                value = json.loads(json.dumps(value))
                for environment in value["result"]["environments"]:
                    environment.pop("privateInputCapabilities", None)
            if self.command == "PUT" and status == 200:
                if (root / "hold-upload-response").exists():
                    (root / "upload-response-entered").write_text("Original server verified exact bytes before this real response barrier")
                    until = time.monotonic() + 8
                    while not (root / "release-upload-response").exists() and time.monotonic() < until:
                        time.sleep(.005)
                marker = root / "drop-upload-ack"
                if marker.exists():
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
        (root / "resource-release.json").write_text(json.dumps({
            "listenerClosed": server.socket.fileno() == -1, "activeProcesses": len(manager.processes)}))


if __name__ == "__main__":
    main()
