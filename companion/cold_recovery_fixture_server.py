"""Real original Companion/stdio pipeline, synthetic Codex only. Native cold-restore fixture."""
import json
from pathlib import Path
import signal
import sys
import threading
import time
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_cold_session import PIPE


def main():
    root = Path(sys.argv[1])
    root.mkdir(exist_ok=True)
    workspace = root / "workspace"
    workspace.mkdir()
    protocol = root / "protocol"
    protocol.mkdir()
    cli = root / "controlled-codex.py"
    cli.write_text(PIPE)
    token = "native-cold-recovery-controlled-token-more-than-32-characters"
    config = {"stateDir": str(root / "state"), "environments": [{"id": "workspace", "kind": "local", "root": str(workspace), "workers": ["codex_app_server"], "writeAllowed": True}]}
    def manager():
        value = JobManager(config, token)
        original = value._launch
        value._launch = lambda env, argv: ([sys.executable, str(cli), str(protocol)], env["root"]) if argv[0] == "codex" else original(env, argv)
        return value
    current = manager()
    counts = threading.Lock()
    def count(name):
        with counts:
            path = root / name
            path.write_text(str(int(path.read_text()) + 1 if path.exists() else 1))
    def wait(path, seconds=4):
        until = time.monotonic() + seconds
        while not path.exists() and time.monotonic() < until:
            time.sleep(.005)
        return path.exists()
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path == "/v1/jobs":
                count("submit-count")
            super().do_POST()
        def do_GET(self):
            if self.path.startswith("/v1/jobs/"):
                # Controlled lost-final-observation window; the phone cannot see the saved
                # terminal state before the explicit fixture simulates its final-save loss.
                if not (root / "restart-done").exists():
                    wait(root / "restart-done")
                if self.path.endswith("/session-recovery"):
                    count("recovery-read-count")
                    if (root / "hold-recovery-read").exists():
                        (root / "recovery-read-held").write_text("original native GET dispatched")
                        wait(root / "release-recovery-read")
            super().do_GET()
    server = CompanionServer(("127.0.0.1", 0), current)
    server.RequestHandlerClass = Handler
    stopped = threading.Event()
    def restart_once():
        nonlocal current
        if not wait(root / "simulate-cold-restart", 10):
            return
        until = time.monotonic() + 4
        while current.processes and time.monotonic() < until:
            time.sleep(.005)
        current.close()
        for path in (root / "state").glob("job-*.json"):
            actual = json.loads(path.read_text())
            assert actual["status"] == "completed" and actual.get("_coldProcess"), actual
            # Fixture fault injection only: emulate missing last supervisor status save,
            # preserving native-created operation/process/turn/output identities verbatim.
            actual["status"] = "running"
            path.write_text(json.dumps(actual))
        current = manager()
        server.manager = current
        (root / "restart-done").write_text("same original ledger/HTTP endpoint, new supervisor")
    restarter = threading.Thread(target=restart_once, daemon=True)
    restarter.start()
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(json.dumps({"baseUrl": "http://127.0.0.1:" + str(server.server_port)}), flush=True)
    try:
        server.serve_forever(poll_interval=.02)
    finally:
        stopped.set()
        (root / "release-recovery-read").write_text("release fixture barrier")
        server.server_close()
        current.close()
        restarter.join(timeout=1)
        (root / "resource-release.json").write_text(json.dumps({"listenerClosed": True, "activeProcesses": len(current.processes), "activeDrivers": len(current.drivers), "resources": len(current.resources), "syntheticCliOnly": True}))


if __name__ == "__main__":
    main()
