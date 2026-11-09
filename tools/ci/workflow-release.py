#!/usr/bin/env python3
"""Workflow glue. Tests inject run(); default executes real local preparation tools.
No publish unless explicit --activate. Never log secrets or child output.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('release_cli_glue',HERE/'release-cli.py')
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)

def run(command,env):
 result=subprocess.run(list(map(str,command)),env=env,capture_output=True,text=True)
 if result.returncode:
  import re
  for line in result.stderr.splitlines():
   if re.fullmatch(r'PWTRACE (?:exception [A-Za-z_]+|frame [A-Za-z0-9_.-]+:[0-9]+:[A-Za-z_<>]+)',line) or (line.startswith('PWERROR ') and len(line)<300):print(line,file=sys.stderr)
  raise ValueError('Release subprocess failed; output withheld')
 return result.stdout

def clean_env(env):
 return {k:v for k,v in env.items() if not k.startswith('POCKETWORLD_') and k not in ('GITHUB_TOKEN','GH_TOKEN','ACTIONS_RUNTIME_TOKEN')}

def context(env):
 head=env['GITHUB_SHA'];run_id=cli.identity(env,head)
 repo=Path(env['GITHUB_WORKSPACE']).resolve();temp=Path(env['RUNNER_TEMP']).resolve()
 if temp.is_relative_to(repo):raise ValueError('Secrets/temp must be outside checkout')
 return head,run_id,repo,temp

def download_baseline(repo,work,c,env):
 """Read-only prior GitHub release, validated with production historical verifier."""
 client=cli.publisher.Client(env.get('GITHUB_TOKEN',''))
 release=client.json('/repos/'+cli.publisher.REPO+'/releases/latest')
 if release.get('draft') or release.get('prerelease'):raise ValueError('No stable baseline')
 assets=release.get('assets',[])
 manifests=[a for a in assets if a.get('name')=='release.json']
 if len(manifests)!=1 or manifests[0].get('size',0)>96*1024:raise ValueError('Missing bounded baseline manifest')
 code,_,raw=client.download(manifests[0]['id'],96*1024)
 if code!=200:raise ValueError('Baseline manifest download failed')
 envelope=work/'baseline-release.json';envelope.write_bytes(raw)
 runner=cli.runner_for(repo,work,c)
 verified=work/'baseline-verified.json'
 runner.tool('verify-envelope','--envelope',envelope,'--classpath',c['org_json_classpath'],'--allow-expired-baseline','--output',verified)
 proof=cli.read(verified)
 if proof.get('signatureVerified') is not True or proof.get('pinSpkiSha256')!=cli.first.PIN:raise ValueError('Baseline trust mismatch')
 if cli.first.version_code(release['tag_name']) != proof['versionCode']:
  raise ValueError('Baseline public tag differs from signed Android versionCode')
 artifact=proof['apk']
 prefix='https://github.com/'+cli.publisher.REPO+'/releases/download/'+release['tag_name']+'/'
 if not artifact['url'].startswith(prefix):raise ValueError('Baseline URL not bound to current release')
 name=artifact['url'][len(prefix):]
 matches=[a for a in assets if a.get('name')==name and a.get('size')==artifact['bytes']]
 if len(matches)!=1:raise ValueError('Baseline APK asset mismatch')
 code,_,raw=client.download(matches[0]['id'],artifact['bytes'])
 if code!=200 or len(raw)!=artifact['bytes'] or hashlib.sha256(raw).hexdigest()!=artifact['sha256']:raise ValueError('Baseline APK bytes rejected')
 apk=work/'baseline.apk';apk.write_bytes(raw)
 return apk,envelope

def prepare(env,execute=run,fetch_baseline=download_baseline):
 head,run_id,repo,temp=context(env)
 mode=env.get('RELEASE_MODE','')
 if mode not in ('first','delta'):raise ValueError('Explicit first/delta mode required')
 version=int(env['RELEASE_VERSION_CODE']);floor=int(env['RELEASE_VERSION_FLOOR'])
 cli.first.require_version_floor(version,floor)
 if floor<258:raise ValueError('Floor below observed reference')
 directory=temp/'pocketworld-release-secrets'
 directory.mkdir(mode=0o700) # exclusive; never reuse old key files
 private=directory/'manifest-private.pem'
 try:
  secret=env.get('POCKETWORLD_MANIFEST_PRIVATE_KEY_B64','')
  if not secret:raise ValueError('Manifest signing Secret missing')
  raw=base64.b64decode(secret,validate=True)
  with private.open('xb') as f:f.write(raw)
  private.chmod(0o600)
  public_der=HERE/'manifest-signing-public.der';cli.first.public_key(public_der)
  public_pem=directory/'manifest-public.pem'
  child=clean_env(env)
  execute(['openssl','pkey','-pubin','-inform','DER','-in',public_der,'-out',public_pem],child)
  cache=Path(env.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))
  jars=list(cache.glob('caches/modules-2/files-2.1/org.json/json/20240303/*/*.jar'))
  if len(jars)!=1:raise ValueError('Exactly one existing locked org.json dependency required')
  c={'java_home':env['JAVA_HOME'],'build_tools':str(Path(env['ANDROID_HOME'])/'build-tools/35.0.0'),
     'openssl':shutil.which('openssl') or '/usr/bin/openssl','org_json_classpath':str(jars[0]),
     'manifest_private_key':str(private),'manifest_public_key':str(public_pem),'manifest_public_der':str(public_der)}
  config=directory/'config.json';cli.write(config,c)
  work=temp/'pocketworld-release-prepared'
  command=[sys.executable,'-B',HERE/'release-cli.py','prepare','--repo',repo,'--work',work,'--config',config,
           '--head',head,'--apk',repo/'app/build/outputs/apk/debug/app-debug.apk','--receipt',repo/'build-artifacts/apk-identity.json',
           '--public-der',public_der,'--version',str(version),'--minimum-version',str(floor),'--now',str(int(time.time()*1000))]
  if mode=='delta':
   baseline_dir=temp/'pocketworld-baseline';baseline_dir.mkdir()
   old,envelope=fetch_baseline(repo,baseline_dir,c,env)
   command+=['--base-apk',old,'--base-envelope',envelope]
  execute(command,child)
  handoff=work/'handoff'
  cli.verify_handoff(handoff,head,run_id)
  deps=handoff/'verification';deps.mkdir()
  shutil.copyfile(jars[0],deps/'org.json.jar')
  cli.write(deps/'identity.json',{'sourceCommit':head,'runId':run_id,'sha256':cli.sha(deps/'org.json.jar')})
  if env.get('GITHUB_OUTPUT'):
   with open(env['GITHUB_OUTPUT'],'a') as f:f.write('handoff='+str(handoff)+'\n')
  return handoff
 finally:
  # Nothing in this directory is uploaded; remove private key even on failures.
  shutil.rmtree(directory)

def publish(env,handoff,activate=False,resume=None,recover_orphan_tag=False,execute=run):
 if resume is not None or recover_orphan_tag:
  raise ValueError('Automated draft recovery is disabled; preserve the original handoff for manual reconciliation')
 head,run_id,repo,temp=context(env)
 handoff=Path(handoff).resolve();cli.verify_handoff(handoff,head,run_id)
 deps=handoff/'verification';identity=cli.read(deps/'identity.json')
 if identity!={'sourceCommit':head,'runId':run_id,'sha256':cli.sha(deps/'org.json.jar')}:
  raise ValueError('Cross-run verifier dependency rejected')
 directory=temp/'pocketworld-publish-config';directory.mkdir(mode=0o700)
 config=directory/'config.json'
 cli.write(config,{'java_home':env['JAVA_HOME'],'build_tools':str(Path(env.get('ANDROID_HOME','/unused'))/'build-tools/35.0.0'),
     'openssl':shutil.which('openssl') or '/usr/bin/openssl','org_json_classpath':str(deps/'org.json.jar'),'manifest_public_der':str(HERE/'manifest-signing-public.der')})
 command=[sys.executable,'-B',HERE/'release-cli.py','publish','--repo',repo,'--work',temp/'pocketworld-publish-verify',
          '--config',config,'--head',head,'--handoff',handoff]
 if activate:command+=['--publish']
 if resume is not None:command+=['--resume-release-id',str(resume)]
 if recover_orphan_tag:command+=['--recover-orphan-tag']
 child=clean_env(env);child['GITHUB_TOKEN']=env.get('GITHUB_TOKEN','')
 if not child['GITHUB_TOKEN']:raise ValueError('Missing publisher job token')
 return execute(command,child)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['prepare','publish'])
 p.add_argument('--handoff',type=Path);p.add_argument('--activate',action='store_true');p.add_argument('--resume-release-id',type=int);p.add_argument('--recover-orphan-tag',action='store_true')
 a=p.parse_args()
 if a.mode=='prepare':prepare(os.environ)
 else:
  if a.handoff is None:p.error('--handoff required')
  publish(os.environ,a.handoff,a.activate,a.resume_release_id,a.recover_orphan_tag)
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
  print('Workflow release stage stopped; no success claimed.',file=sys.stderr);sys.exit(1)
