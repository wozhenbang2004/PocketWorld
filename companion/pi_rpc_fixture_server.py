"""Loopback native-owner journey; real Companion, synthetic pinned Pi peer only."""
import json
import os
from pathlib import Path
import signal
import sys
import threading
import time
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_pi_driver import FIXTURE


def main():
    root=Path(sys.argv[1]).resolve();mode=sys.argv[2] if len(sys.argv)>2 else 'success'
    workspace,executables=root/'workspace',root/'bin';workspace.mkdir();executables.mkdir()
    executable=executables/'pi'
    executable.write_text('#!'+sys.executable+'\nimport sys\nsys.argv=[sys.argv[0],'+repr(mode)+',*sys.argv[1:]]\n'+FIXTURE)
    executable.chmod(0o700);os.environ['PATH']=str(executables)+os.pathsep+os.environ['PATH']
    manager=JobManager({'stateDir':str(root/'state'),'environments':[{'id':'workspace','root':str(workspace),'workers':['pi'],'writeAllowed':True}]},'native-pi-no-tools-fixture-token-longer-than-32-characters')
    lock=threading.Lock()
    def count(name):
        with lock:
            path=root/name;path.write_text(str(int(path.read_text())+1 if path.exists() else 1))
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path=='/v1/jobs':count('submit-count')
            if self.path.endswith('/approval'):count('approval-count')
            if self.path.endswith('/question-answer'):count('question-count')
            if self.path.endswith('/steer'):count('steer-count')
            super().do_POST()
        def _send(self,status,value):
            if status>=400:(root/"last-error.json").write_text(json.dumps(value))
            super()._send(status,value)
        def do_GET(self):
            if self.path.startswith('/v1/jobs/'):
                # Explicit fixture controls only; no timing guesses or real CLI are used.
                for marker in ('release-pi-steer-ack','release-pi-turn'):
                    if (root/marker).exists():
                        (root/marker).unlink();(workspace/marker).touch()
                if (root/'expire-pi-steer-ack').exists():
                    (root/'expire-pi-steer-ack').unlink()
                    with manager.lock:
                        for driver in manager.drivers.values():
                            for pending in driver.steer_replies.values():pending['deadline']=time.monotonic()-1
                if (root/'expire-pi-stop').exists():
                    (root/'expire-pi-stop').unlink()
                    with manager.lock:
                        for driver in manager.drivers.values():
                            if driver.abort_deadline is not None:driver.abort_deadline=time.monotonic()-1
            if self.path.startswith('/v1/jobs/') and (root/'mutate-session').exists():
                (root/'mutate-session').unlink()
                for file in (root/'state'/'pi-sessions').glob('*/*.jsonl'):
                    with file.open('a') as stream:stream.write('{"fixture":"external mutation"}\n')
            super().do_GET()
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:
        server.server_close();manager.close()
        (root/'resource-release.json').write_text(json.dumps({'listenerClosed':server.socket.fileno()==-1,'activeProcesses':len(manager.processes),'activeDrivers':len(manager.drivers),'resources':len(manager.resources)}))


if __name__=='__main__':main()
