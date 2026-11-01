"""Controlled native fixture: real Companion/files/job records, deliberately fake coding CLI."""
import json
from pathlib import Path
import signal
import socket
import sys
import threading
import time
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_codex_driver import FIXTURE


def main():
    root = Path(sys.argv[1]).resolve()
    workspace = root / "workspace"
    workspace.mkdir(parents=True)
    (workspace / "proof.txt").write_text("ORIGINAL IMMUTABLE NATIVE RESULT\n", encoding="utf-8")
    (workspace / "result.bin").write_bytes(bytes(range(256)) * 2 + b"\x00")
    (workspace / ".env").write_text("PRIVATE FIXTURE NEVER CAPTURE")
    (workspace / "subdirectory").mkdir()
    driver = root / "fake-codex-protocol.py"
    driver.write_text(FIXTURE)
    token = "native-result-files-fixture-token-longer-than-32-characters"
    manager = JobManager({"stateDir": str(root / "state"), "environments": [{
        "id": "workspace", "kind": "local", "root": str(workspace),
        "workers": ["codex_app_server"], "writeAllowed": False}]}, token)
    manager._launch = lambda env, argv: ([sys.executable, str(driver), "final-without-start-ack"], str(workspace))

    def count(name):
        path = root / name
        path.write_text(str(int(path.read_text()) + 1 if path.exists() else 1))

    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path.endswith("/snapshots"):
                count("capture-count")
            if self.path == "/v1/jobs":
                count("submit-count")
            super().do_POST()

        def _send(self, status, value):
            if self.command == "GET" and "/snapshots/" in self.path and "&path=" in self.path:
                count("snapshot-file-read-count")
                marker = root / "hold-snapshot-file-read"
                if marker.exists():
                    (root / "snapshot-read-entered").write_text("production bytes were read before this response barrier")
                    until = time.monotonic() + 5
                    while not (root / "release-snapshot-file-read").exists() and time.monotonic() < until:
                        time.sleep(.005)
            marker = root / "drop-next-capture-ack"
            if self.command == "POST" and self.path.endswith("/snapshots") and status == 200 and marker.exists():
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
