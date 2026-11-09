#!/usr/bin/env python3
"""Trusted GitHub CI prepare/publish boundary; no implicit publication.
Prepared handoff includes only verified artifact files, never signing material.
Use --publish explicitly after real integration acceptance. No API is used by prepare.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys

HERE=Path(__file__).resolve().parent

def module(name):
 spec=importlib.util.spec_from_file_location(name.replace('-','_'),HERE/(name+'.py'))
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

first=module('first-github-release');delta=module('prepare-github-release')
publisher=module('github-publisher');adapter=module('ci-release')

def read(p):return json.loads(Path(p).read_text())
def write(p,data):
 with Path(p).open('x') as f:json.dump(data,f,separators=(',',':'))
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def identity(env,head):
 if adapter.trusted(env)!=head:raise ValueError('Handoff source differs from trusted workflow SHA')
 if not re.fullmatch(r'[1-9][0-9]*',env.get('GITHUB_RUN_ID','')):raise ValueError('Missing workflow run identity')
 return env['GITHUB_RUN_ID']

class FrozenCore:
 def __init__(self,core,head):self.core,self.head=core,head
 def __getattr__(self,name):return getattr(self.core,name)
 def source_state(self,runner):
  _,head=runner.git('rev-parse','HEAD')
  code,_=runner.git('diff','--quiet','HEAD','--',check=False)
  if head!=self.head or code:raise ValueError('Frozen checkout changed')
  return head

def runner_for(repo,work,config):
 # No publishing token in subprocess environment or subprocess diagnostics.
 runner=adapter.Runner(repo,work,config)
 for key in ('GITHUB_TOKEN','GH_TOKEN','ACTIONS_RUNTIME_TOKEN'):
  runner.env.pop(key,None)
 return runner

def verify_callback(runner,expected,source_dir):
 sequence=[0]
 def verify(raw):
  if not 0<len(raw)<=96*1024:raise ValueError('Manifest bound exceeded')
  sequence[0]+=1
  envelope=runner.directory/('remote-envelope-'+str(sequence[0])+'.json')
  envelope.write_bytes(raw)
  out=runner.directory/('remote-verified-'+str(sequence[0])+'.json')
  runner.tool('verify-envelope','--envelope',envelope,'--classpath',runner.c['org_json_classpath'],'--output',out)
  proof=read(out)
  if (proof.get('signatureVerified') is not True or proof.get('pinSpkiSha256')!=first.PIN or
      proof.get('versionCode')!=expected['versionCode'] or proof.get('apk')!=expected['apk']):
   raise ValueError('Downloaded signed manifest identity mismatch')
  # Compare exact signed envelope against same-run verified handoff, not a substitute.
  if hashlib.sha256(raw).hexdigest()!=sha(source_dir/'release.json'):
   raise ValueError('Remote manifest differs from verified same-run artifact')
  return {'verified':True,'payload':expected}
 return verify

def verify_handoff(folder,head,run_id):
 folder=Path(folder).resolve();h=read(folder/'handoff.json')
 if h.get('schema')!=1 or h.get('sourceCommit')!=head or h.get('runId')!=run_id:
  raise ValueError('Cross-run or cross-SHA artifact rejected')
 if h.get('pin')!=first.PIN or h.get('repository')!=publisher.REPO:
  raise ValueError('Artifact trust configuration mismatch')
 if h.get('tag')!='v'+str(h.get('payload',{}).get('versionCode')):
  raise ValueError('Tag/version mismatch')
 first.require_version_floor(h['payload']['versionCode'],h['minimumVersion'])
 if h['minimumVersion']<258:raise ValueError('Version floor below observed reference')
 assets=[]
 for item in h['assets']:
  name=item['name']
  if not re.fullmatch(r'[A-Za-z0-9._-]+',name):raise ValueError('Unsafe handoff path')
  path=(folder/name).resolve()
  if path.parent!=folder:raise ValueError('Asset escapes handoff')
  assets.append({**item,'file':str(path)})
 publisher.validate_assets(assets)
 signed=read(folder/'release.json')
 data=json.loads(base64.b64decode(signed['payload'],validate=True))
 if data!=h['payload']:raise ValueError('Handoff payload differs from envelope')
 expected={}
 for item in [data['apk'],*data.get('deltas',[])]:
  prefix='https://github.com/'+publisher.REPO+'/releases/download/'+h['tag']+'/'
  url=item['url']
  if not url.startswith(prefix):raise ValueError('Unexpected artifact URL')
  name=url[len(prefix):]
  if not re.fullmatch(r'[A-Za-z0-9._-]+',name) or name in expected:raise ValueError('Invalid manifest asset name')
  expected[name]=(item['sha256'],item['bytes'])
 actual={x['name']:(x['sha256'],x['bytes']) for x in assets if x['name']!='release.json'}
 if actual!=expected:raise ValueError('Handoff artifact set differs from signed payload')
 return h,assets

def prepare(args,env):
 run_id=identity(env,args.head)
 first.require_version_floor(args.version,args.minimum_version)
 if args.minimum_version<258:raise ValueError('Configure conservative floor at least 258; observed reference is not verified latest')
 repo,work=args.repo.resolve(),args.work.resolve()
 if work.exists() or work.is_relative_to(repo):raise ValueError('Use a new external preparation directory')
 work.mkdir(parents=True,mode=0o700)
 c=read(args.config);runner=runner_for(repo,work,c);core=FrozenCore(adapter.load_core(repo),args.head)
 if args.base_apk is None and args.base_envelope is None:
  runtime_spec=importlib.util.spec_from_file_location('release_tools',repo/'tools/windows-release.py')
  runtime_module=importlib.util.module_from_spec(runtime_spec);runtime_spec.loader.exec_module(runtime_module)
  runtime=runtime_module.Runtime(repo,work/'temp',c['java_home'],c['build_tools'],c['openssl'])
  inspector=runtime.existing('inspect-update-artifacts')
  c['minimum_version']=args.minimum_version
  result=first.prepare(core,runner,inspector,args.apk.resolve(),args.receipt,args.head,args.public_der,'v'+str(args.version),args.now)
  payload=result['payload'];sources=[{'file':result['apk'],'name':payload['apk']['url'].rsplit('/',1)[1]}]
 else:
  if args.base_apk is None or args.base_envelope is None:raise ValueError('Delta requires both exact baseline APK and signed envelope')
  result=delta.prepare(core,runner,args.base_envelope,args.base_apk.resolve(),args.apk.resolve(),args.receipt,args.head,'v'+str(args.version))
  payload=read(work/'github-unsigned-payload.json');sources=result['assets']
 if payload['versionCode']!=args.version:raise ValueError('Built APK differs from selected version')
 output=work/'handoff';output.mkdir()
 sources=[*sources,{'file':str(work/'release.json'),'name':'release.json'}]
 assets=[]
 for source in sources:
  name=source['name']
  if not re.fullmatch(r'[A-Za-z0-9._-]+',name):raise ValueError('Invalid output filename')
  target=output/name
  with target.open('xb') as out,Path(source['file']).open('rb') as src:shutil.copyfileobj(src,out)
  assets.append({'name':name,'bytes':target.stat().st_size,'sha256':sha(target)})
 write(output/'handoff.json',{'schema':1,'sourceCommit':args.head,'runId':run_id,'repository':publisher.REPO,
       'pin':first.PIN,'minimumVersion':args.minimum_version,'floorEvidence':'operator_configured; observed258_not_signature_verified',
       'tag':'v'+str(args.version),'payload':payload,'assets':assets})
 verify_handoff(output,args.head,run_id)
 return {'status':'prepared','directory':str(output),'published':False}

def publish(args,env):
 if args.resume_release_id is not None or args.recover_orphan_tag:
  raise ValueError('Automated recovery is disabled; do not rebuild or re-sign the original handoff')
 run_id=identity(env,args.head)
 h,assets=verify_handoff(args.handoff,args.head,run_id)
 repo,work=args.repo.resolve(),args.work.resolve()
 if work.exists() or work.is_relative_to(repo):raise ValueError('Use new external verification directory')
 work.mkdir(parents=True,mode=0o700)
 runner=runner_for(repo,work,read(args.config))
 core=FrozenCore(adapter.load_core(repo),args.head);core.source_state(runner)
 verify=verify_callback(runner,h['payload'],args.handoff.resolve())
 verify((args.handoff/'release.json').read_bytes()) # production App verifier before any API write
 client=publisher.Client(env.get('GITHUB_TOKEN',''))
 return publisher.publish(client,h['tag'],args.head,assets,verify,args.publish,args.resume_release_id,args.recover_orphan_tag)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['prepare','publish'])
 for name in ('repo','work','config'):p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--head',required=True)
 for name in ('apk','receipt','public-der','base-apk','base-envelope','handoff'):p.add_argument('--'+name,type=Path)
 p.add_argument('--version',type=int);p.add_argument('--minimum-version',type=int);p.add_argument('--now',type=int)
 p.add_argument('--recover-orphan-tag',action='store_true');p.add_argument('--publish',action='store_true');p.add_argument('--resume-release-id',type=int)
 args=p.parse_args()
 if args.mode=='prepare':
  if any(getattr(args,n) is None for n in ('apk','receipt','public_der','version','minimum_version','now')):p.error('prepare inputs required')
  result=prepare(args,os.environ)
 else:
  if args.handoff is None:p.error('--handoff required')
  result=publish(args,os.environ)
 print(json.dumps(result))
if __name__=='__main__':
 try:main()
 except Exception as error:
  import traceback
  print('PWTRACE exception '+type(error).__name__,file=sys.stderr)
  import ast
  safe_messages=set()
  for source in Path(__file__).parent.glob('*.py'):
   for node in ast.walk(ast.parse(source.read_text())):
    if isinstance(node,ast.Raise) and isinstance(node.exc,ast.Call) and node.exc.args and isinstance(node.exc.args[0],ast.Constant) and isinstance(node.exc.args[0].value,str):safe_messages.add(node.exc.args[0].value)
  if str(error) in safe_messages:print('PWERROR '+str(error),file=sys.stderr)
  for frame in traceback.extract_tb(error.__traceback__):
   print('PWTRACE frame '+Path(frame.filename).name+':'+str(frame.lineno)+':'+frame.name,file=sys.stderr)
  print('Release stopped; do not infer success. Inspect retained draft/artifacts before retry.',file=sys.stderr);sys.exit(1)
