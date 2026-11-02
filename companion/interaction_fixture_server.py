"""Native fixture only: original Companion/files/driver, deliberately fake installed CLI peers."""
import json
from pathlib import Path
import signal
import socket
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_remote_interactions import CODEX, CLAUDE


def main():
    root=Path(sys.argv[1]).resolve();workspace=root/'workspace';workspace.mkdir(parents=True)
    (workspace/'result.txt').write_text('ORIGINAL BASE')
    codex_peer=CODEX
    if len(sys.argv)>2 and sys.argv[2].startswith('team'):
        codex_peer=codex_peer.replace("'type':'workspaceWrite'","'type':p.get('sandbox','workspaceWrite')").replace("    elif m=='turn/start':", "    elif m=='mcpServerStatus/list': emit({'id':x['id'],'result':{'data':[]}})\n    elif m=='turn/start':")
        if sys.argv[2]=='team-approval':
            original=next(line for line in codex_peer.splitlines() if "emit({'id':'wire-question'" in line)
            approval="        emit({'id':'wire-approval','method':'item/commandExecution/requestApproval','params':{'threadId':'question-thread','turnId':turn,'itemId':'permission-item','command':'fixture command requires escalation','cwd':str(Path.cwd())}})"
            codex_peer=codex_peer.replace(original,approval).replace("    elif x.get('id')=='wire-question'", "    elif x.get('id')=='wire-approval' and 'result' in x:\n        assert x['result']=={'decision':'decline'}\n"+original+"\n    elif x.get('id')=='wire-question'")
    codex=root/'question-codex.py';codex.write_text(codex_peer)
    claude=root/'question-claude.py';claude.write_text(CLAUDE)
    manager=JobManager({'stateDir':str(root/'state'),'environments':[{'id':'workspace','kind':'local','root':str(workspace),'workers':['codex_app_server','claude'],'writeAllowed':True}]},'native-question-fixture-token-longer-than-32-characters')
    manager._launch=lambda env,argv:([sys.executable,str(claude),'normal',*argv[1:]] if argv[0]=='claude' else [sys.executable,str(codex),'normal'],str(workspace))
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path.endswith('/question-answer'):
                p=root/'question-post-count';p.write_text(str(int(p.read_text())+1 if p.exists() else 1))
            if self.path.endswith('/approval'):
                p=root/'approval-post-count';p.write_text(str(int(p.read_text())+1 if p.exists() else 1))
            super().do_POST()
        def _send(self,status,value):
            marker=root/'drop-next-question-ack'
            if self.command=='POST' and self.path.endswith('/question-answer') and status==200 and marker.exists():
                marker.unlink();self.close_connection=True;self.connection.shutdown(socket.SHUT_RDWR);return
            super()._send(status,value)
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port),'workspace':str(workspace)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:server.server_close();manager.close()


if __name__=='__main__':main()
