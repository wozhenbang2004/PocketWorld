"""Controlled original Companion + original drivers. Installed CLI protocol peers are fixtures.
Their model HTTP requests really traverse the phone bridge; no provider/login account exists.
"""
import json
import os
from pathlib import Path
import signal
import sys
import threading
from pocket_companion import CompanionServer, JobManager
from test_codex_driver import FIXTURE as CODEX
from test_claude_driver import FIXTURE as CLAUDE

# These are CLI protocol peer fixtures only, not a replacement product Agent loop.
# Read the *actual* T04-managed config, not a test-provided provider credential.
WIRE = r'''
def infer():
    import urllib.request
    fixture=Path(os.environ['POCKETWORLD_GATEWAY_FIXTURE_ROOT']).resolve()
    if Path(sys.argv[0]).name == 'codex':
        assert Path(os.environ['CODEX_HOME']).resolve() == fixture/'codex-home'
        import tomllib
        c=tomllib.loads((Path(os.environ['CODEX_HOME'])/'config.toml').read_text())
        provider=c['model_providers'][c['model_provider']]
        base=provider['base_url'];token=provider['experimental_bearer_token']
        body={'model':'fixture-model','input':'original Codex fixture prompt','stream':True}
        path='/responses'
    else:
        assert Path(os.environ['CLAUDE_CONFIG_DIR']).resolve() == fixture/'claude-home'
        c=json.loads((Path(os.environ['CLAUDE_CONFIG_DIR'])/'settings.json').read_text())['env']
        base=c['ANTHROPIC_BASE_URL'];token=c['ANTHROPIC_AUTH_TOKEN']
        body={'model':'fixture-model','max_tokens':128,'messages':[{'role':'user','content':'original Claude fixture prompt'}],'stream':True}
        path='/v1/messages'
    body=json.dumps(body).encode()
    req=urllib.request.Request(base.rstrip('/')+path,data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    raw=urllib.request.urlopen(req,timeout=10).read()
    Path('gateway-wire-requests').open('a').write('1\n')
    # The expected native complete protocol sentinel must be present, not just a socket EOF.
    assert b'response.completed' in raw or b'message_stop' in raw
    return 'actual phone-gateway fixture inference'
'''

def main():
    root=Path(sys.argv[1]).resolve(); workspace=root/'workspace';workspace.mkdir(parents=True)
    executables=root/'bin';executables.mkdir()
    codex_home=root/'codex-home';codex_home.mkdir();(codex_home/'config.toml').write_text('# original controlled config\n')
    claude_home=root/'claude-home';claude_home.mkdir();(claude_home/'settings.json').write_text('{}\n')
    codex = CODEX.replace('import json,sys', 'import json,sys,os\nfrom pathlib import Path\n'+WIRE)
    # Preserve original handshake, native driver's approval routing and cancellation protocol.
    # The first turn now performs real HTTP before the original bounded tool approval.
    codex=codex.replace("        if mode=='disconnect': sys.exit(0)", "        infer()\n        if mode=='disconnect': sys.exit(0)")
    claude = CLAUDE.replace('mode = sys.argv[1]', WIRE+'\nmode = sys.argv[1]')
    claude=claude.replace("        if mode == 'disconnect': sys.exit()", "        infer()\n        if mode == 'disconnect': sys.exit()")
    for name,source,mode,version in [('codex',codex,'approval','codex-cli 0.164.0-alpha.11'),('claude',claude,'approval','2.1.259 (Claude Code)')]:
        file=executables/name
        header='#!'+sys.executable+'\nimport sys\n'
        if name=='codex':header+="if '--version' in sys.argv:\n print("+repr(version)+");sys.exit()\n"
        header+='sys.argv=[sys.argv[0],'+repr(mode)+',*sys.argv[1:]]\n'
        file.write_text(header+source);file.chmod(0o700)
    os.environ['PATH']=str(executables)+os.pathsep+os.environ['PATH']
    os.environ['POCKETWORLD_GATEWAY_FIXTURE_ROOT']=str(root)
    for key in ('CODEX_PROFILE','ANTHROPIC_BASE_URL','ANTHROPIC_API_KEY','ANTHROPIC_AUTH_TOKEN','ANTHROPIC_MODEL','CLAUDE_CODE_SETTINGS_PATH'):
        os.environ.pop(key,None)
    config={'stateDir':str(root/'state'),'environments':[{'id':'fixture','kind':'local','root':str(workspace),'writeAllowed':True,'workers':['codex_app_server','claude'],
        'harnesses':[{'bindingId':'fixture-codex','family':'codex','configRoot':str(codex_home),'executable':str(executables/'codex'),'scope':'PROJECT_SESSION'},
                     {'bindingId':'fixture-claude','family':'claude','configRoot':str(claude_home),'executable':str(executables/'claude'),'scope':'PROJECT_SESSION'}]}]}
    manager=JobManager(config,'fixture-only-paired-gateway-token-not-real-123456789012345')
    server=CompanionServer(('127.0.0.1',0),manager)
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port),'environmentBinding':manager.environment_binding('fixture'),'workspace':str(workspace)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:server.server_close();manager.close()

if __name__=='__main__':main()
