#!/usr/bin/env python3
"""Explicit recovery of immutable signed handoff; never rebuild or change GITHUB identity."""
import base64, hashlib, importlib.util, io, json, os, re, shutil, stat, subprocess, sys, time, urllib.parse, zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('release_cli',HERE/'release-cli.py')
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)
REPO=cli.publisher.REPO
MAX=300*1024*1024

def main():
 env=os.environ
 execution=cli.adapter.trusted(env)
 if env['GITHUB_EVENT_NAME']!='workflow_dispatch':raise ValueError('Recovery requires explicit main dispatch')
 source=env['SOURCE_SHA'];run_id=int(env['SOURCE_RUN']);artifact_id=int(env['SOURCE_ARTIFACT']);expected=env['SOURCE_ARTIFACT_SHA256']
 if not re.fullmatch('[a-f0-9]{40}',source) or not re.fullmatch('[a-f0-9]{64}',expected):raise ValueError('Invalid explicit source identity')
 client=cli.publisher.Client(env['GITHUB_TOKEN'])
 run=client.json('/repos/'+REPO+'/actions/runs/'+str(run_id))
 if run.get('head_sha')!=source or run.get('head_branch')!='main' or run.get('event') not in ('push','workflow_dispatch') or run.get('path')!='.github/workflows/github-release.yml' or run.get('repository',{}).get('full_name')!=REPO or run.get('head_repository',{}).get('full_name')!=REPO or run.get('status')!='completed':raise ValueError('Source run is not completed canonical main workflow')
 record=client.json('/repos/'+REPO+'/actions/artifacts/'+str(artifact_id))
 origin=record.get('workflow_run',{})
 if record.get('expired') is not False or origin.get('id')!=run_id or origin.get('head_sha')!=source or record.get('digest')!='sha256:'+expected or record.get('name')!='pocketworld-release-'+str(run_id)+'-'+env['SOURCE_ATTEMPT']:raise ValueError('Artifact identity differs from explicit source run')
 # GitHub artifact storage URL is followed once without forwarding Authorization.
 url='https://api.github.com/repos/'+REPO+'/actions/artifacts/'+str(artifact_id)+'/zip'
 req=__import__('urllib.request',fromlist=['Request']).Request(url,headers={'Authorization':'Bearer '+env['GITHUB_TOKEN'],'Accept':'application/vnd.github+json'})
 opener=__import__('urllib.request',fromlist=['build_opener']).build_opener(cli.publisher.NoRedirect())
 try:response=opener.open(req,timeout=90)
 except __import__('urllib.error',fromlist=['HTTPError']).HTTPError as error:response=error
 with response:
  if response.code not in (301,302,303,307,308):raise ValueError('Artifact archive did not return expected storage redirect')
  location=response.headers.get('Location','')
 parsed=urllib.parse.urlsplit(location)
 if parsed.scheme!='https' or parsed.username or parsed.password or parsed.port not in (None,443) or not (parsed.hostname.endswith('.blob.core.windows.net') or parsed.hostname.endswith('.githubusercontent.com')):raise ValueError('Unexpected artifact storage destination')
 # No redirect handler and no auth header on storage hop.
 with opener.open(__import__('urllib.request',fromlist=['Request']).Request(location),timeout=120) as response:
  raw=response.read(MAX+1)
 if len(raw)>MAX or hashlib.sha256(raw).hexdigest()!=expected:raise ValueError('Artifact archive checksum rejected')
 work=Path(env['RUNNER_TEMP'])/'recovery';work.mkdir(mode=0o700)
 folder=work/'original';folder.mkdir()
 with zipfile.ZipFile(io.BytesIO(raw)) as archive:
  names=set();total=0
  for item in archive.infolist():
   if item.is_dir():continue
   name=item.filename;total+=item.file_size
   if name in names or not re.fullmatch(r'(?:verification/)?[A-Za-z0-9._-]+',name) or stat.S_ISLNK(item.external_attr>>16) or total>MAX:raise ValueError('Unsafe artifact members')
   names.add(name);target=folder/name;target.parent.mkdir(exist_ok=True)
   target.write_bytes(archive.read(item))
 h=cli.read(folder/'handoff.json');data=json.loads(base64.b64decode(cli.read(folder/'release.json')['payload'],validate=True))
 if h.get('schema')!=1 or h.get('sourceCommit')!=source or h.get('runId')!=str(run_id) or h.get('repository')!=REPO or h.get('pin')!=cli.first.PIN or data!=h.get('payload'):raise ValueError('Original handoff provenance mismatch')
 code=data['versionCode'];legacy='v'+str(code);tag=cli.first.version_tag(code)
 if h['tag'] not in (legacy,tag) or data.get('deltas')!=[]:raise ValueError('Recovery currently accepts full first-release handoffs only')
 if data.get('packageName')!='dev.pocketengine':raise ValueError('Unexpected package')
 old_prefix='https://github.com/'+REPO+'/releases/download/'+h['tag']+'/'
 if not data['apk']['url'].startswith(old_prefix):raise ValueError('Original asset URL not bound to source tag')
 apkname=data['apk']['url'][len(old_prefix):]
 if not re.fullmatch('[a-f0-9]{64}-app-[0-9]+[.]apk',apkname):raise ValueError('Unexpected APK asset name')
 assets=[{**a,'file':str(folder/a['name'])} for a in h['assets']]
 cli.publisher.validate_assets(assets)
 if {a['name'] for a in assets}!={apkname,'release.json'} or names!={apkname,'release.json','handoff.json','verification/org.json.jar','verification/identity.json'}:raise ValueError('Unexpected handoff asset set')
 apk=folder/apkname
 if cli.sha(apk)!=data['apk']['sha256'] or apk.stat().st_size!=data['apk']['bytes']:raise ValueError('APK differs from signed payload')
 dep=cli.read(folder/'verification/identity.json')
 if dep!={'sourceCommit':source,'runId':str(run_id),'sha256':cli.sha(folder/'verification/org.json.jar')}:raise ValueError('Verifier dependency provenance mismatch')
 repo=Path(env['SOURCE_CHECKOUT']).resolve()
 if subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()!=source:raise ValueError('Source checkout mismatch')
 c={'java_home':env['JAVA_HOME'],'build_tools':str(Path(env['ANDROID_HOME'])/'build-tools/35.0.0'),'openssl':shutil.which('openssl'),'org_json_classpath':str(folder/'verification/org.json.jar'),'manifest_public_der':str(HERE/'manifest-signing-public.der')}
 runner=cli.runner_for(repo,work,c);cli.FrozenCore(cli.adapter.load_core(repo),source).source_state(runner)
 proofpath=work/'original-proof.json'
 runner.tool('verify-envelope','--envelope',folder/'release.json','--classpath',c['org_json_classpath'],'--output',proofpath)
 proof=cli.read(proofpath)
 if proof.get('signatureVerified') is not True or proof.get('pinSpkiSha256')!=cli.first.PIN or proof.get('apk')!=data['apk'] or proof.get('versionCode')!=code:raise ValueError('Original production signature verification rejected')
 runner.tool('verify-apk','--apk',apk,'--out',work/'apk-check','--expected-signer',cli.first.SIGNER)
 runtime_spec=importlib.util.spec_from_file_location('source_release_tools',repo/'tools/windows-release.py')
 runtime_module=importlib.util.module_from_spec(runtime_spec);runtime_spec.loader.exec_module(runtime_module)
 runtime=runtime_module.Runtime(repo,work/'temp',c['java_home'],c['build_tools'],c['openssl'])
 inspected=runtime.existing('inspect-update-artifacts').inspect_apk(apk,Path(c['build_tools']))
 if inspected['versionCode']!=code or inspected['versionName']!=data['versionName'] or inspected['minSdk']!=data['minSdk'] or inspected['signerCertificateSha256']!=[cli.first.SIGNER]:raise ValueError('Inspected APK identity differs from signed manifest')
 _,resources=runner.command([Path(c['build_tools'])/'aapt','dump','--values','resources',apk]);cli.first.check_resources(resources,cli.first.public_key(HERE/'manifest-signing-public.der'))
 keydir=work/'secrets';keydir.mkdir(mode=0o700)
 try:
  private=keydir/'manifest.pem';private.write_bytes(base64.b64decode(env['POCKETWORLD_MANIFEST_PRIVATE_KEY_B64'],validate=True));private.chmod(0o600)
  public=keydir/'public.pem'
  runner.command(['openssl','pkey','-pubin','-inform','DER','-in',HERE/'manifest-signing-public.der','-out',public])
  # Only public URL and validity timestamps change; APK bytes and Android identity stay fixed.
  data['apk']['url']='https://github.com/'+REPO+'/releases/download/'+tag+'/'+apkname
  data['issuedAt']=int(time.time()*1000);data['expiresAt']=data['issuedAt']+7*86400000
  unsigned=work/'unsigned.json';cli.write(unsigned,data)
  output=work/'prepared';output.mkdir();shutil.copyfile(apk,output/apkname)
  runner.tool('sign','--payload',unsigned,'--private-key',private,'--public-key',public,'--output',output/'release.json')
 finally:shutil.rmtree(keydir)
 assets=[{'name':p.name,'file':str(p),'bytes':p.stat().st_size,'sha256':cli.sha(p)} for p in (output/apkname,output/'release.json')]
 identity={'schema':2,'source':{'runId':str(run_id),'attempt':env['SOURCE_ATTEMPT'],'commit':source,'artifactId':artifact_id,'artifactSha256':expected},'execution':{'runId':env['GITHUB_RUN_ID'],'commit':execution},'tag':tag,'payload':data,'assets':[{k:v for k,v in a.items() if k!='file'} for a in assets]}
 cli.write(output/'recovery-origin.json',identity)
 verify=cli.verify_callback(runner,data,output);verify((output/'release.json').read_bytes())
 if env.get('GITHUB_OUTPUT'):
  with open(env['GITHUB_OUTPUT'],'a') as out:out.write('prepared='+str(output)+'\n')
 result=cli.publisher.publish(client,tag,source,assets,verify,activate=True)
 print(json.dumps(result))

if __name__=='__main__':
 try:main()
 except Exception as error:
  # Show safe type and traceback; request URL/body/token/private key never printed.
  import traceback
  print('PWERROR Recovery failed: '+type(error).__name__,file=sys.stderr)
  for frame in traceback.extract_tb(error.__traceback__):print('PWTRACE frame '+Path(frame.filename).name+':'+str(frame.lineno)+':'+frame.name,file=sys.stderr)
  sys.exit(1)
