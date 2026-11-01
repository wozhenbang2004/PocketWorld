"""Controlled original Companion + a synthetic pipe CLI for the complete C6 team journey.
No real account/CLI. The accepted literal command actually invokes Node, then changes
only the selected native author outputs. HTTP/native tuple/SQL authority stay production.
"""
import json
import os
from pathlib import Path
import signal
import sys
import threading
sys.path.insert(0, os.environ["C6_COMPANION_PRODUCTION_SOURCE"])
from pocket_companion import CompanionHandler, CompanionServer, JobManager

PIPE = r'''
import json, os, re, subprocess, sys
from pathlib import Path
which=None; target=None

def emit(value): print(json.dumps(value),flush=True)
for line in sys.stdin:
 value=json.loads(line);method=value.get('method');params=value.get('params',{})
 if method=='initialize': emit({'id':value['id'],'result':{'userAgent':'explicit-complete-team-protocol-fixture'}})
 elif method=='config/read': emit({'id':value['id'],'result':{'config':{'mcp_servers':{}}}})
 elif method=='account/read': emit({'id':value['id'],'result':{'requiresOpenaiAuth':False}})
 elif method=='model/list': emit({'id':value['id'],'result':{'data':[{'model':'fixture-model','isDefault':True}]}})
 elif method=='thread/start':
  assert params['sandbox']=='workspaceWrite'
  emit({'id':value['id'],'result':{'thread':{'id':'full-team-thread'},'cwd':os.getcwd(),'sandbox':{'type':'workspaceWrite','writableRoots':[os.getcwd()],'networkAccess':False,'excludeTmpdirEnvVar':True,'excludeSlashTmp':True}}})
 elif method=='mcpServerStatus/list': emit({'id':value['id'],'result':{'data':[]}})
 elif method=='turn/start':
  match=re.search(r'C6_FULL_AUTHOR_([ab])_TARGET_(\d+)',json.dumps(params))
  assert match, 'explicit synthetic author target missing'
  which,target=match.group(1),int(match.group(2))
  assert params['sandboxPolicy']['writableRoots']==[os.getcwd()]
  emit({'id':value['id'],'result':{'turn':{'id':'full-team-turn'}}})
  emit({'method':'turn/started','params':{'threadId':'full-team-thread','turn':{'id':'full-team-turn'}}})
  emit({'id':42,'method':'item/commandExecution/requestApproval','params':{'threadId':'full-team-thread','turnId':'full-team-turn','itemId':'literal-fixture-command','command':'node --check '+which+'.js; explicit synthetic write of only native selected outputs','cwd':os.getcwd(),'grantRoot':None}})
 elif value.get('id')==42 and 'result' in value:
  if value['result']['decision']=='decline':
   emit({'method':'turn/completed','params':{'threadId':'full-team-thread','turn':{'id':'full-team-turn','status':'interrupted'}}});continue
  assert value['result']['decision']=='accept'
  subprocess.run([sys.argv[1],'--check',which+'.js'],check=True,timeout=5)
  Path(which+'.js').write_text('module.exports='+str(target)+';\n')
  if which=='a':
   Path('assets/opaque.bin').write_bytes(bytes([0,8,255,0,9]))
   if Path('old-name.txt').exists(): Path('old-name.txt').rename('new-name.txt')
   assert Path('new-name.txt').read_text()=='ORIGINAL_RENAME_BYTES\n'
  subprocess.run([sys.argv[1],'--check',which+'.js'],check=True,timeout=5)
  emit({'method':'item/agentMessage/delta','params':{'threadId':'full-team-thread','turnId':'full-team-turn','delta':'Actual copied bytes changed, Node syntax check exited zero; this is not phone combined validation or a real provider quality claim'}})
  emit({'method':'turn/completed','params':{'threadId':'full-team-thread','turn':{'id':'full-team-turn','status':'completed'}}})
 elif method=='turn/interrupt':
  emit({'id':value['id'],'result':{}});emit({'method':'turn/completed','params':{'threadId':'full-team-thread','turn':{'id':'full-team-turn','status':'interrupted'}}})
'''

def main():
    root=Path(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
    workspace=root/'configured-workspace';workspace.mkdir()
    driver=root/'explicit-team-pipe-cli.py';driver.write_text(PIPE)
    token='native-assignment-fixture-token-longer-than-32-characters'
    manager=JobManager({'stateDir':str(root/'state'),'environments':[{'id':'workspace','kind':'local','root':str(workspace),'workers':['command','codex_app_server'],'writeAllowed':True,'commands':{'python':sys.executable}}]},token)
    original=manager._launch
    manager._launch=lambda env,argv: ([sys.executable,str(driver),sys.argv[2]],env['root']) if argv[0]=='codex' else original(env,argv)
    lock=threading.Lock()
    class Handler(CompanionHandler):
        def record(self):
            with lock:
                with (root/'requests.jsonl').open('a') as out:out.write(json.dumps({'method':self.command,'path':self.path})+'\n')
        def do_POST(self):self.record();super().do_POST()
        def do_PUT(self):self.record();super().do_PUT()
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:
        server.server_close();manager.close();(root/'resource-release.json').write_text(json.dumps({'listenerClosed':True,'activeProcesses':len(manager.processes),'activeDrivers':len(manager.drivers),'syntheticCliOnly':True}))

if __name__=='__main__':main()
