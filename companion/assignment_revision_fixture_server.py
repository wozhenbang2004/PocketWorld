"""Controlled native SQL journey server: original production Companion, real copies, fake CLI only."""
import json
from pathlib import Path
import signal
import socket
import sys
import threading
import time
import uuid
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_assignment_workspaces import CODING_PIPE


def main():
    root=Path(sys.argv[1]);workspace=root/'source';workspace.mkdir(parents=True)
    driver=root/'explicit-fake-coding-pipe.py'
    needle="  Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\\n')"
    replacement="  Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\\n' if Path('proof.txt').read_text()=='ORIGINAL SOURCE BYTES\\n' else 'V2 REAL BYTES FROM NEW ORIGINAL JOB\\n')"
    if needle not in CODING_PIPE:raise RuntimeError('fixture pipe changed; no silent original fallback')
    driver.write_text(CODING_PIPE.replace(needle,replacement))
    manager=JobManager({'stateDir':str(root/'state'),'environments':[{'id':'workspace','kind':'local','root':str(workspace),'workers':['command','codex_app_server'],'writeAllowed':True,'commands':{'python':sys.executable}}]},'native-assignment-fixture-token-longer-than-32-characters')
    # Base is an actual original command job, not a fabricated remote receipt.
    source=manager.submit({'operationId':str(uuid.uuid4()),'environmentId':'workspace','worker':'command','argv':['python','-c',"from pathlib import Path;Path('proof.txt').write_text('ORIGINAL SOURCE BYTES\\n')"],'timeoutSeconds':5})
    until=time.monotonic()+5
    while manager.get(source['id'])['status'] not in {'completed','failed'} and time.monotonic()<until:time.sleep(.01)
    source=manager.get(source['id'])
    if source['status']!='completed':raise RuntimeError(str(source))
    original_launch=manager._launch
    def launch(env,argv):
        if argv[0]=='codex':return [sys.executable,str(driver),'write'],env['root']
        return original_launch(env,argv)
    manager._launch=launch
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path.endswith('/assignment-workspaces'):
                p=root/'prepare-count';p.write_text(str(int(p.read_text())+1 if p.exists() else 1))
            if self.path=='/v1/jobs':
                p=root/'submit-count';p.write_text(str(int(p.read_text())+1 if p.exists() else 1))
            super().do_POST()
        def _send(self,status,value):
            drop=root/'drop-next-prepare-ack'
            if self.command=='POST' and self.path.endswith('/assignment-workspaces') and status==200 and drop.exists():
                drop.unlink();self.close_connection=True;self.connection.shutdown(socket.SHUT_RDWR);return
            super()._send(status,value)
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port),'workspace':str(workspace),'source':source}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:server.server_close();manager.close()


if __name__=='__main__':main()
