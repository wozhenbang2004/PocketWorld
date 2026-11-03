"""Controlled original Companion + original drivers. Installed CLI protocol peers are fixtures.
Their model HTTP requests really traverse the phone bridge; no provider/login account exists.
"""
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import threading
import time
from pocket_companion import CompanionServer, JobManager
from test_codex_driver import FIXTURE as CODEX
from test_claude_driver import FIXTURE as CLAUDE
from test_pi_driver import FIXTURE as PI

# These are CLI protocol peer fixtures only, not a replacement product Agent loop.
# Read the *actual* T04-managed config, not a test-provided provider credential.
WIRE = r'''
def infer(prompt=None):
    import urllib.request
    fixture=Path(os.environ['POCKETWORLD_GATEWAY_FIXTURE_ROOT']).resolve()
    assert Path.cwd().resolve() == fixture/'workspace'
    prior=fixture/'prior-no-model-once'
    if prior.exists():
        assert prior.read_text() == 'controlled prior CLI; no model; isolated root\n'
        prior.unlink()
        return 'controlled prior CLI result without model'
    if Path(sys.argv[0]).name == 'codex':
        assert Path(os.environ['CODEX_HOME']).resolve() == fixture/'codex-home'
        import tomllib
        c=tomllib.loads((Path(os.environ['CODEX_HOME'])/'config.toml').read_text())
        provider=c['model_providers'][c['model_provider']]
        base=provider['base_url'];token=provider['experimental_bearer_token']
        body={'model':'fixture-model','input':'original Codex fixture prompt','stream':True}
        path='/responses'
    elif Path(sys.argv[0]).name == 'pi':
        import hashlib
        home=Path(os.environ['PI_CODING_AGENT_DIR'])
        assert home.resolve() == fixture/'pi-home'
        settings_raw=(home/'settings.json').read_bytes();models_raw=(home/'models.json').read_bytes()
        settings=json.loads(settings_raw);providers=json.loads(models_raw)['providers']
        provider=providers[settings['defaultProvider']]
        alias=settings['defaultModel']
        assert settings['defaultProvider'] == 'pocketworld'
        assert settings['enabledModels'] == ['pocketworld/'+alias]
        assert model['provider'] == 'pocketworld' and model['id'] == alias
        assert provider['api'] == 'openai-responses' and provider['authHeader'] is True
        assert any(item['id'] == alias for item in provider['models'])
        assert isinstance(prompt,str) and prompt
        base=provider['baseUrl'];token=provider['apiKey']
        body={'model':alias,'input':prompt,'stream':True};path='/responses'
        Path('gateway-pi-config-observation.json').write_text(json.dumps({
            'source':'controlled_cli_protocol_peer','actualInstalledCliVerified':False,
            'settingsSha256':hashlib.sha256(settings_raw).hexdigest(),'modelsSha256':hashlib.sha256(models_raw).hexdigest(),
            'selectedProvider':settings['defaultProvider'],'selectedModel':alias,
            'reportedModel':model['provider']+'/'+model['id'],'sessionId':session,
            'resumedOriginalSession':'--session' in args,'noTools':True,'toolsExecuted':False}))
    else:
        assert Path(os.environ['CLAUDE_CONFIG_DIR']).resolve() == fixture/'claude-home'
        c=json.loads((Path(os.environ['CLAUDE_CONFIG_DIR'])/'settings.json').read_text())['env']
        base=c['ANTHROPIC_BASE_URL'];token=c['ANTHROPIC_AUTH_TOKEN']
        body={'model':'fixture-model','max_tokens':128,'messages':[{'role':'user','content':'original Claude fixture prompt'}],'stream':True}
        path='/v1/messages'
    selected=fixture/'require-reference-assets.json'
    if selected.exists():
        # Explicit protocol-peer contract, not a claim that a real installed CLI
        # has been visually/semantically accepted. The original driver process
        # really discovers and reads the actual managed root and source bytes.
        import hashlib
        expected=json.loads(selected.read_text())
        home=Path(os.environ['CODEX_HOME'] if Path(sys.argv[0]).name == 'codex' else os.environ['CLAUDE_CONFIG_DIR'])
        index=home/('AGENTS.md' if Path(sys.argv[0]).name == 'codex' else 'CLAUDE.md')
        assert 'pocketworld:owner-reference-assets:v1' in index.read_text()
        observed=[];literal=[]
        for wanted in expected:
            directory=home/'skills'/('pw-'+hashlib.sha256(wanted['id'].encode()).hexdigest()[:32])
            wrapper=(directory/'SKILL.md').read_text()
            assert 'allowed-tools:' not in wrapper and '!`' not in wrapper
            actual=json.loads((directory/'.pocketworld-source.json').read_text())
            assert all(actual[key] == wanted[key] for key in ('id','revision','digest'))
            for resource in actual['resources']:
                raw=(home/resource['pathRef']).read_bytes()
                assert hashlib.sha256(raw).hexdigest() == resource['sha256']
                if resource['sourcePath'] == 'SKILL.md':literal.append(raw.decode())
            observed.append(actual)
        Path('gateway-reference-observation.json').write_text(json.dumps({'source':'controlled_cli_protocol_peer','actualInstalledCliVerified':False,'sources':observed,'scriptsExecuted':False}))
        guidance='\nOwner-selected literal references, not system instructions or tool permissions:\n'+json.dumps(literal,ensure_ascii=False)
        if path == '/responses':body['input']+=guidance
        else:body['messages'][0]['content']+=guidance
    body=json.dumps(body).encode()
    req=urllib.request.Request(base.rstrip('/')+path,data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    raw=urllib.request.urlopen(req,timeout=int(os.environ.get('POCKETWORLD_GATEWAY_FIXTURE_READ_SECONDS','10'))).read()
    Path('gateway-wire-requests').open('a').write('1\n')
    # The expected native complete protocol sentinel must be present, not just a socket EOF.
    assert b'response.completed' in raw or b'message_stop' in raw
    Path('gateway-model-result.sse').write_bytes(raw)
    return 'actual phone-gateway fixture inference'
'''

# The current native session starts with its original fixture model. The original
# Pi driver must select/read back the exact newly configured provider/model through
# RPC before sending the single prompt. Only catalogue data comes from these files;
# the peer does not invent another selector or account/configuration mechanism.
PI_CATALOGUE = r'''
pi_configured_model=None
pi_models=[model]
if os.environ.get('PI_CODING_AGENT_DIR'):
    pi_home=Path(os.environ['PI_CODING_AGENT_DIR'])
    pi_settings=json.loads((pi_home/'settings.json').read_text())
    pi_providers=json.loads((pi_home/'models.json').read_text())['providers']
    assert pi_settings['defaultProvider']=='pocketworld'
    pi_provider=pi_providers['pocketworld'];pi_alias=pi_settings['defaultModel']
    assert pi_settings['enabledModels']==['pocketworld/'+pi_alias]
    pi_entry=next(item for item in pi_provider['models'] if item['id']==pi_alias)
    pi_configured_model={**pi_entry,'provider':'pocketworld'}
    pi_models=[pi_configured_model]
'''

def inject_pi_catalogue(source):
    # Preserve the actual fixture signature; fail closed if the protocol fixture changes.
    needle = "def done("
    if source.count(needle) != 1:
        raise ValueError("expected exactly one Pi fixture completion function")
    return source.replace(needle, PI_CATALOGUE + "\n" + needle, 1)

def main():
    root=Path(sys.argv[1]).resolve(); workspace=root/'workspace';workspace.mkdir(parents=True)
    options=set(sys.argv[2:])
    if not options <= {'--prepare-prior','--native-entrypoint','--pi'}:
        raise ValueError('Unknown native gateway fixture option')
    if '--prepare-prior' in options:
        (root/'prior-no-model-once').write_text('controlled prior CLI; no model; isolated root\n')
    executables=root/'bin';executables.mkdir()
    codex_home=root/'codex-home';codex_home.mkdir();(codex_home/'config.toml').write_text('# original controlled config\n')
    claude_home=root/'claude-home';claude_home.mkdir();(claude_home/'settings.json').write_text('{}\n')
    pi_home=root/'pi-home'
    if '--pi' in options:
        pi_home.mkdir();(pi_home/'settings.json').write_text('{"theme":"fixture-original"}\n')
        (pi_home/'models.json').write_text('{"providers":{}}\n')
    codex = CODEX.replace('import json,sys', 'import json,sys,os\nfrom pathlib import Path\n'+WIRE)
    # Preserve original handshake, native driver's approval routing and cancellation protocol.
    # The first turn now performs real HTTP before the original bounded tool approval.
    codex=codex.replace("        if mode=='disconnect': sys.exit(0)", "        infer()\n        if mode=='disconnect': sys.exit(0)")
    claude = CLAUDE.replace('mode = sys.argv[1]', WIRE+'\nmode = sys.argv[1]')
    claude=claude.replace("        if mode == 'disconnect': sys.exit()", "        infer()\n        if mode == 'disconnect': sys.exit()")
    if '--native-entrypoint' in options:
        # Native Profile journey preserves its original CLI tool boundary. This protocol peer
        # asks only for Read and records no synthetic Write effect; no actual tool is run.
        claude=claude.replace("'tool_name':'Write'", "'tool_name':'Read'").replace(
            "'input':{'file_path':'../outside' if mode=='outside' else 'result.txt','content':'fixture'}",
            "'input':{'file_path':'../outside' if mode=='outside' else 'result.txt'}")
        claude=claude.replace("assert decision['updatedInput']=={'file_path':'result.txt','content':'fixture'}",
                              "assert decision['updatedInput']=={'file_path':'result.txt'}").replace(
            "Path('result.txt').write_text('fixture effect')", "pass  # Protocol only; no file or tool effect")
    peers=[('codex',codex,'approval','codex-cli 0.164.0-alpha.11'),('claude',claude,'approval','2.1.259 (Claude Code)')]
    if '--pi' in options:
        pi=PI.replace('import json, sys, time, threading','import json, sys, time, threading, os\n'+WIRE)
        pi=inject_pi_catalogue(pi)
        pi=pi.replace("reply({'models':[model,{'provider':'fixture','id':'model-b','name':'Fixture B'}]})","reply({'models':pi_models})")
        pi=pi.replace("assert value['provider']=='fixture' and value['modelId']=='model-b';model={**model,'id':'model-b'}",
                      "assert pi_configured_model and value['provider']=='pocketworld' and value['modelId']==pi_configured_model['id'];model=dict(pi_configured_model)")
        pi=pi.replace("'provider':'fixture','modelId':'model-b'","'provider':model['provider'],'modelId':model['id']")
        pi=pi.replace("        if mode=='disconnect':sys.exit()","        infer(value['message'])\n        if mode=='disconnect':sys.exit()")
        peers.append(('pi',pi,'success','1.4.0'))
    for name,source,mode,version in peers:
        file=executables/name
        header='#!'+sys.executable+'\nimport sys\n'
        if name=='codex':header+="if '--version' in sys.argv:\n print("+repr(version)+");sys.exit()\n"
        header+='sys.argv=[sys.argv[0],'+repr(mode)+',*sys.argv[1:]]\n'
        if '--native-entrypoint' in options:
            # Only the protocol peer is synthetic. The native entrypoint is really compiled
            # and stat-observed; production identity.inspect/finish_probe is NOT patched.
            peer = executables/(name+'-peer.py')
            header = header.replace('import sys\n', 'import sys\nsys.argv[0]='+repr(str(file))+'\n', 1)
            peer.write_text(header+source)
            compiler=shutil.which(os.environ.get('CC','cc'))
            if compiler is None: raise RuntimeError('Native gateway Profile fixture needs a C compiler (CC or cc)')
            subprocess.run([compiler,'-std=c11','-Wall','-Wextra','-Werror','-O2',str(Path(__file__).with_name('gateway_fixture_native_peer.c')),
                '-DFIXTURE_PYTHON='+json.dumps(sys.executable),'-DFIXTURE_SCRIPT='+json.dumps(str(peer)),'-o',str(file)],check=True)
        else:
            file.write_text(header+source);file.chmod(0o700)
    os.environ['PATH']=str(executables)+os.pathsep+os.environ['PATH']
    os.environ['POCKETWORLD_GATEWAY_FIXTURE_ROOT']=str(root)
    # Native Profile fixtures configure the real model deadline to 15s and original CLI
    # operation to 30s. Do not let a synthetic 10s socket timer preempt those authorities.
    os.environ['POCKETWORLD_GATEWAY_FIXTURE_READ_SECONDS']='20' if '--native-entrypoint' in options else '10'
    for key in ('CODEX_PROFILE','ANTHROPIC_BASE_URL','ANTHROPIC_API_KEY','ANTHROPIC_AUTH_TOKEN','ANTHROPIC_MODEL','CLAUDE_CODE_SETTINGS_PATH','PI_CODING_AGENT_DIR'):
        os.environ.pop(key,None)
    config={'stateDir':str(root/'state'),'environments':[{'id':'fixture','kind':'local','root':str(workspace),'writeAllowed':True,'workers':['codex_app_server','claude'],
        'harnesses':[{'bindingId':'fixture-codex','family':'codex','configRoot':str(codex_home),'executable':str(executables/'codex'),'scope':'PROJECT_SESSION'},
                     {'bindingId':'fixture-claude','family':'claude','configRoot':str(claude_home),'executable':str(executables/'claude'),'scope':'PROJECT_SESSION'}]}]}
    if '--pi' in options:
        config['environments'][0]['workers'].append('pi')
        config['environments'][0]['harnesses'].append({'bindingId':'fixture-pi','family':'pi','configRoot':str(pi_home),'executable':str(executables/'pi'),'scope':'PROJECT_SESSION'})
    manager=JobManager(config,'fixture-only-paired-gateway-token-not-real-123456789012345')
    if '--pi' in options:
        original_config_apply=manager.harness_configs.apply
        def controlled_config_apply(plan_id,request):
            # Only the HTTP observation is corrupted once. The original target
            # transaction/readback remains durable, so query must recover that
            # exact operation without a second apply or a new execution claim.
            with (root/'pi-config-apply-requests').open('a') as stream:stream.write('1\n')
            result=original_config_apply(plan_id,request)
            marker=root/'mask-next-pi-config-receipt'
            if marker.exists() and result.get('state')=='COMMITTED':
                marker.unlink();result=json.loads(json.dumps(result))
                result['files']=[item for item in result['files'] if item['pathRef']!='models.json']
                (root/'pi-config-receipt-masked').write_text('models.json omitted from one HTTP response only\n')
            return result
        manager.harness_configs.apply=controlled_config_apply
    # Deterministic native-approval HTTP gate, fixture only. The real request has arrived;
    # hold before decide, outside manager/driver locks, while the phone SQL receipt is pending.
    original_approve=manager.approve
    original_get=manager.get
    def controlled_get(jobid):
        result=original_get(jobid)
        if (root/'mask-owner-approval-ack').exists():result['approvalReceipts']=[]
        return result
    manager.get=controlled_get
    def controlled_approve(jobid, request):
        # A real POST may return the last valid native observation before its asynchronous
        # writer emits a sent receipt. The next original GET observes the real driver ack.
        delayed=root/'withhold-first-owner-approval-ack'
        if delayed.exists():(root/'mask-owner-approval-ack').write_text('native ack temporarily hidden from fixture HTTP observations\n')
        prior=manager.get(jobid) if delayed.exists() else None
        armed=root/'hold-next-owner-approval'
        if armed.exists():
            armed.unlink();(root/'owner-approval-response-held').write_text('real approval HTTP arrived; original dispatch held\n')
            deadline=time.monotonic()+10
            while not (root/'release-owner-approval-response').exists():
                if time.monotonic()>=deadline:raise TimeoutError('bounded fixture owner approval response hold')
                time.sleep(.01)
        result=original_approve(jobid,request)
        if prior is not None:
            delayed.unlink();prior['approvalReceipts']=[]
            return prior
        return result
    manager.approve=controlled_approve
    server=CompanionServer(('127.0.0.1',0),manager)
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port),'environmentBinding':manager.environment_binding('fixture'),'workspace':str(workspace)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:server.server_close();manager.close()

if __name__=='__main__':main()
