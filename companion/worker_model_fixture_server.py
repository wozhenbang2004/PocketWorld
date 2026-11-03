"""Actual Companion and original native-owner bridge; synthetic vendor CLI peers only."""
import json
import os
from pathlib import Path
import signal
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_codex_model_options import PEER as CODEX
from test_claude_model_options import PEER as CLAUDE


def main():
    root=Path(sys.argv[1]).resolve();workspace,executables=root/'workspace',root/'bin';workspace.mkdir();executables.mkdir()
    codex=CODEX.replace("if mode!='context-only':assert value['params']['effort']=='ultra'", "if 'effort' in value['params']:assert value['params']['effort'] in {'medium','ultra'}",1)
    codex=codex.replace("        from pathlib import Path\n        count=Path('codex-turn-count')", "        from pathlib import Path\n        Path('last-turn-request.json').write_text(json.dumps(value['params']))\n        count=Path('codex-turn-count')",1)
    claude=CLAUDE.replace('--session-id --resume --max-turns --model','--session-id --resume --max-turns --model --effort',1)
    for name,source in [('codex',codex),('claude',claude)]:
        header='#!'+sys.executable+'\nimport sys,json\nfrom pathlib import Path\n'
        header+="if '--version' not in sys.argv and '--help' not in sys.argv:Path('last-launch-args.json').write_text(json.dumps(sys.argv[1:]))\n"
        header+="sys.argv=[sys.argv[0],'success',*sys.argv[1:]]\n"
        header+='source='+repr(source)+'\n'
        header+='if Path('+repr(str(root/'change-model-options'))+").exists():source=source.replace('0.166.0-alpha.1','0.166.1').replace('2.1.259','2.1.260')\n"
        executable=executables/name;executable.write_text(header+'exec(compile(source,__file__,"exec"))\n');executable.chmod(0o700)
    os.environ['PATH']=str(executables)+os.pathsep+os.environ['PATH']
    manager=JobManager({'stateDir':str(root/'state'),'environments':[{'id':'workspace','root':str(workspace),'workers':['codex_app_server','claude']}]},'native-worker-model-options-fixture-token-longer-than-32-characters')
    lock=threading.Lock()
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path=='/v1/jobs':
                with lock:
                    path=root/'submit-count';path.write_text(str(int(path.read_text())+1 if path.exists() else 1))
            super().do_POST()
        def _send(self,status,value):
            if status>=400:(root/'last-error.json').write_text(json.dumps(value))
            super()._send(status,value)
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:
        server.server_close();manager.close();(root/'resource-release.json').write_text(json.dumps({'listenerClosed':server.socket.fileno()==-1,'activeProcesses':len(manager.processes),'activeDrivers':len(manager.drivers),'resources':len(manager.resources)}))


if __name__=='__main__':main()
