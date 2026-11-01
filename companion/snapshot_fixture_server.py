"""Offline native-journey fixture: production Companion, real temp files, fake Codex pipe only."""
import json
from pathlib import Path
import signal
import socket
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_codex_driver import FIXTURE


def main():
    root = Path(sys.argv[1]).resolve()
    workspace = root / 'workspace'
    workspace.mkdir(parents=True)
    (workspace / 'proof.txt').write_text('ORIGINAL IMMUTABLE NATIVE RESULT\n', encoding='utf-8')
    (workspace / '.env').write_text('PRIVATE FIXTURE NEVER CAPTURE', encoding='utf-8')
    driver = root / 'fake-codex-protocol.py'
    driver.write_text(FIXTURE)
    manager = JobManager({'stateDir': str(root / 'state'), 'environments': [{'id': 'workspace', 'kind': 'local', 'root': str(workspace), 'workers': ['codex_app_server'], 'writeAllowed': False}]}, 'native-snapshot-fixture-token-longer-than-32-characters')
    # Only this explicit fixture replaces CLI discovery; production driver/protocol/server/storage remain real.
    manager._launch = lambda env, argv: ([sys.executable, str(driver), 'final-without-start-ack'], str(workspace))
    class FixtureHandler(CompanionHandler):
        def do_POST(self):
            if self.path.endswith('/snapshots'):
                counter = root / 'capture-count'
                counter.write_text(str(int(counter.read_text()) + 1 if counter.exists() else 1))
            super().do_POST()

        def _send(self, status, value):
            marker = root / 'drop-next-capture-ack'
            if self.command == 'POST' and self.path.endswith('/snapshots') and status == 200 and marker.exists():
                marker.unlink()
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            super()._send(status, value)
    server = CompanionServer(('127.0.0.1', 0), manager)
    server.RequestHandlerClass = FixtureHandler
    signal.signal(signal.SIGTERM, lambda *_args: threading.Thread(target=server.shutdown, daemon=True).start())
    print(json.dumps({'baseUrl': 'http://127.0.0.1:' + str(server.server_port), 'workspace': str(workspace)}), flush=True)
    try:
        server.serve_forever(poll_interval=.02)
    finally:
        server.server_close()
        manager.close()


if __name__ == '__main__':
    main()
