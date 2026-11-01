"""Controlled original Companion for native project-package transport journeys; no real accounts."""
import json
from pathlib import Path
import signal
import socket
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_assignment_workspaces import CODING_PIPE


def main():
    root = Path(sys.argv[1])
    workspace = root / 'configured-workspace'
    workspace.mkdir(parents=True)
    pipe = CODING_PIPE.replace("Path('turn-started').write_text('one original turn')", "pass # Actual protocol receipt, no extra project output")
    pipe = pipe.replace("Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\\n')", "Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\\n');Path('new.bin').write_bytes(bytes([255,0,128,7]));Path('remove.bin').unlink()")
    driver = root / 'explicit-project-test-cli.py'
    driver.write_text(pipe)
    token = 'native-assignment-fixture-token-longer-than-32-characters'
    manager = JobManager({'stateDir': str(root / 'state'), 'environments': [{'id': 'workspace', 'kind': 'local', 'root': str(workspace), 'workers': ['command', 'codex_app_server'], 'writeAllowed': True, 'commands': {'python': sys.executable}}]}, token)
    original_launch = manager._launch
    def launch(env, argv):
        return ([sys.executable, str(driver), 'command-approval'], env['root']) if argv[0] == 'codex' else original_launch(env, argv)
    manager._launch = launch
    log_lock = threading.Lock()
    class Handler(CompanionHandler):
        def record(self):
            with log_lock:
                with (root / 'requests.jsonl').open('a') as output:
                    output.write(json.dumps({'method': self.command, 'path': self.path}) + '\n')
        def do_POST(self):
            self.record()
            super().do_POST()
        def do_PUT(self):
            self.record()
            super().do_PUT()
        def _send(self, status, value):
            # Controlled-fixture evidence only. No real account/credential is present here.
            with log_lock:
                with (root / 'responses.jsonl').open('a') as output:
                    output.write(json.dumps({'method': self.command, 'path': self.path, 'status': status, 'response': value}) + '\n')
            drop = root / 'drop-next-file-ack'
            if self.command == 'PUT' and status == 200 and drop.exists():
                drop.unlink()
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            super()._send(status, value)
    server = CompanionServer(('127.0.0.1', 0), manager)
    server.RequestHandlerClass = Handler
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(json.dumps({'baseUrl': 'http://127.0.0.1:' + str(server.server_port), 'token': token}), flush=True)
    try:
        server.serve_forever(poll_interval=.02)
    finally:
        server.server_close()
        manager.close()
        (root / 'resource-release.json').write_text(json.dumps({'listenerClosed': True, 'activeProcesses': len(manager.processes), 'activeDrivers': len(manager.drivers), 'syntheticCliOnly': True}))


if __name__ == '__main__':
    main()
