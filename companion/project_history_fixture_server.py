"""Original production Companion and actual local files; controlled Codex protocol only."""
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager

PEER = r'''
import json,os,sys
from pathlib import Path
home=Path(sys.argv[1]);home.mkdir(exist_ok=True)
if '--version' in sys.argv:print('codex-cli 0.150.0');sys.exit(0)
if '--help' in sys.argv:print('codex app-server');sys.exit(0)
def emit(v):print(json.dumps(v),flush=True)
for line in sys.stdin:
 v=json.loads(line);method=v.get('method');p=v.get('params',{})
 if method:
  with(home/'methods.jsonl').open('a')as out:out.write(json.dumps({'method':method,'params':p})+'\n')
 if method=='initialize':emit({'id':v['id'],'result':{'userAgent':'controlled-original-project-peer'}})
 elif method=='initialized':pass
 elif method=='turn/steer':emit({'id':v['id'],'error':{'code':-32600,'message':'thread not found: '+p['threadId']}})
 elif method=='config/read':emit({'id':v['id'],'result':{'config':{'mcp_servers':{}}}})
 elif method=='mcpServerStatus/list':emit({'id':v['id'],'result':{'data':[]}})
 elif method=='account/read':emit({'id':v['id'],'result':{'requiresOpenaiAuth':False,'account':{'type':'apiKey'}}})
 elif method=='model/list':emit({'id':v['id'],'result':{'data':[{'model':'controlled-original-model','displayName':'Synthetic original model','isDefault':True}]}})
 elif method in {'thread/read','thread/turns/list'}:
  state=json.loads((home/('branch.json' if p['threadId']=='fixture-branch' else 'stored.json')).read_text())
  if method=='thread/read':emit({'id':v['id'],'result':{'thread':{'id':state['threadId'],'cwd':os.getcwd(),'status':{'type':'notLoaded'}}}})
  else:emit({'id':v['id'],'result':{'data':[{'id':state['turnId'],'status':'completed'}],'nextCursor':None,'backwardsCursor':None}})
 elif method=='thread/fork':
  count=home/'forks';count.write_text(str(int(count.read_text())+1 if count.exists()else 1))
  if(home/'unsupported-fork').exists():emit({'id':v['id'],'error':{'code':-32601,'message':'installed synthetic peer has no fork'}});continue
  state=json.loads((home/'stored.json').read_text());assert p=={'threadId':state['threadId'],'lastTurnId':state['turnId'],'excludeTurns':True};state['threadId']='fixture-branch';(home/'branch.json').write_text(json.dumps(state))
  if(home/'drop-fork-ack').exists():sys.exit(0)
  emit({'id':v['id'],'result':{'thread':{'id':'fixture-branch','forkedFromId':'fixture-thread','sessionId':'fixture-thread'}}})
 elif method in {'thread/start','thread/resume'}:
  thread=p.get('threadId','fixture-thread');emit({'id':v['id'],'result':{'thread':{'id':thread},'sandbox':{'type':'readOnly'}if p.get('sandbox')=='readOnly' else {'type':'workspaceWrite'}}})
 elif method=='turn/start':
  count=home/'starts';n=int(count.read_text())+1 if count.exists()else 1;count.write_text(str(n));thread=p['threadId'];turn='actual-turn-'+str(n)
  emit({'id':v['id'],'result':{'turn':{'id':turn}}});emit({'method':'turn/started','params':{'threadId':thread,'turn':{'id':turn}}})
  text=p['input'][0]['text']
  if 'Modify selected public source' in text:
   Path('source.txt').write_text('worker version two\n');Path('binary.bin').write_bytes(bytes([0,255,128,7]))
   emit({'method':'item/completed','params':{'threadId':thread,'turnId':turn,'item':{'id':'actual-write','type':'fileChange','changes':[{'path':'source.txt'}]}}})
  if 'Continue from restored' in text:(home/'continued-source-bytes').write_bytes(Path('source.txt').read_bytes())
  (home/('branch.json' if thread=='fixture-branch' else 'stored.json')).write_text(json.dumps({'threadId':thread,'turnId':turn,'status':'completed','cwd':os.getcwd()}))
  emit({'method':'item/agentMessage/delta','params':{'threadId':thread,'turnId':turn,'delta':'Actual controlled source result '+str(n)}})
  emit({'method':'turn/completed','params':{'threadId':thread,'turn':{'id':turn,'status':'completed'}}})
 else:emit({'id':v.get('id'),'error':{'code':-32601,'message':'not in controlled peer'}})
'''


def main():
    root=Path(sys.argv[1]);root.mkdir(exist_ok=True)
    workspace=root/'workspace';workspace.mkdir()
    (workspace/'source.txt').write_text('owner original source\n')
    (workspace/'.env').write_text('DO_NOT_CAPTURE=private\n')
    protocol=root/'protocol';protocol.mkdir()
    peer=root/'controlled-codex.py';peer.write_text(PEER)
    binary=root/'fixture-bin';binary.mkdir()
    wrapper=binary/'codex';wrapper.write_text('#!'+sys.executable+'\nimport os,sys\nos.execv('+repr(sys.executable)+', ['+repr(sys.executable)+','+repr(str(peer))+','+repr(str(protocol))+',*sys.argv[1:]])\n');wrapper.chmod(0o700)
    os.environ['PATH']=str(binary)+os.pathsep+os.environ['PATH']
    token='original-project-history-fixture-token-longer-than-32'
    manager=JobManager({'stateDir':str(root/'state'),'environments':[{'id':'workspace','root':str(workspace),'kind':'local','workers':['codex_app_server'],'writeAllowed':True}]},token)
    class Handler(CompanionHandler):
        def do_POST(self):
            name='submit-count' if self.path=='/v1/jobs' else 'restore-count' if self.path.endswith('/restore-project-file') else None
            if name:
                file=root/name;file.write_text(str(int(file.read_text())+1 if file.exists()else 1))
            super().do_POST()
        def _send(self,status,value):
            marker=root/'drop-restore-ack'
            if self.command=='POST' and self.path.endswith('/restore-project-file') and status==200 and marker.exists():
                marker.unlink();self.close_connection=True;self.connection.shutdown(socket.SHUT_RDWR);return
            super()._send(status,value)
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:
        server.server_close();manager.close()
        (root/'resource-release.json').write_text(json.dumps({'listenerClosed':True,'activeProcesses':len(manager.processes),'activeDrivers':len(manager.drivers),'resources':len(manager.resources),'syntheticProtocolOnly':True}))


if __name__=='__main__':main()
