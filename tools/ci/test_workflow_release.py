import base64,hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('glue',Path(__file__).with_name('workflow-release.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=Path(__file__).parent);self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name).resolve();self.repo=self.root/'repo';self.repo.mkdir();self.temp=self.root/'temp';self.temp.mkdir()
  self.cache=self.root/'cache';jar=self.cache/'caches/modules-2/files-2.1/org.json/json/20240303/hash/json.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'fake-locked-jar')
  self.env={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'wozhenbang2004/PocketWorld','GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'123','GITHUB_WORKSPACE':str(self.repo),'RUNNER_TEMP':str(self.temp),'RELEASE_MODE':'first','RELEASE_VERSION_CODE':'259','RELEASE_VERSION_FLOOR':'258','POCKETWORLD_MANIFEST_PRIVATE_KEY_B64':base64.b64encode(b'fixture-not-a-key').decode(),'GITHUB_TOKEN':'fixture-token','JAVA_HOME':'/fake/java','ANDROID_HOME':'/fake/android','GRADLE_USER_HOME':str(self.cache)}
  self.calls=[]
 def execute(self,command,env):
  args=list(map(str,command));self.calls.append((args,env.copy()))
  if args[0]=='openssl':Path(args[args.index('-out')+1]).write_text('fixture-public');return ''
  self.assertNotIn('POCKETWORLD_MANIFEST_PRIVATE_KEY_B64',env)
  if 'prepare' in args:
   self.assertNotIn('GITHUB_TOKEN',env)
   work=Path(args[args.index('--work')+1]);output=work/'handoff';output.mkdir(parents=True)
   apk=b'fixture-apk';digest=hashlib.sha256(apk).hexdigest();name=digest+'-app-259.apk'
   payload={'versionCode':259,'apk':{'url':f'https://github.com/{m.cli.publisher.REPO}/releases/download/v259/{name}','bytes':len(apk),'sha256':digest},'deltas':[]}
   envelope=json.dumps({'payload':base64.b64encode(json.dumps(payload).encode()).decode(),'signature':'fixture'}).encode()
   assets=[]
   for filename,data in [(name,apk),('release.json',envelope)]:
    (output/filename).write_bytes(data);assets.append({'name':filename,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
   (output/'handoff.json').write_text(json.dumps({'schema':1,'sourceCommit':'a'*40,'runId':'123','repository':m.cli.publisher.REPO,'pin':m.cli.first.PIN,'minimumVersion':258,'tag':'v259','payload':payload,'assets':assets}))
  return ''
 def test_first_real_glue_uses_no_baseline_and_cleans_key(self):
  output=m.prepare(self.env,self.execute)
  args=self.calls[-1][0];self.assertNotIn('--base-apk',args);self.assertEqual(args[args.index('--head')+1],'a'*40)
  self.assertFalse((self.temp/'pocketworld-release-secrets').exists());self.assertTrue((output/'verification/org.json.jar').is_file())
 def test_delta_real_glue_requires_verified_baseline_provider(self):
  self.env['RELEASE_MODE']='delta';calls=[]
  def baseline(repo,work,c,env):calls.append(1);return work/'base.apk',work/'signed.json'
  m.prepare(self.env,self.execute,baseline);self.assertEqual(calls,[1]);self.assertIn('--base-apk',self.calls[-1][0]);self.assertIn('--base-envelope',self.calls[-1][0])
 def test_missing_secret_stops_and_cleans(self):
  self.env['POCKETWORLD_MANIFEST_PRIVATE_KEY_B64']='';self.assertRaises(ValueError,m.prepare,self.env,self.execute);self.assertEqual(self.calls,[]);self.assertFalse((self.temp/'pocketworld-release-secrets').exists())
 def test_failed_baseline_stops_before_prepare(self):
  self.env['RELEASE_MODE']='delta'
  def fail(*a):raise ValueError('untrusted')
  self.assertRaises(ValueError,m.prepare,self.env,self.execute,fail);self.assertEqual(len(self.calls),1);self.assertFalse((self.temp/'pocketworld-release-secrets').exists())
 def test_publish_same_run_exact_handoff_and_explicit_activation(self):
  output=m.prepare(self.env,self.execute);m.publish(self.env,output,True,None,False,self.execute)
  args,env=self.calls[-1];self.assertIn('--publish',args);self.assertNotIn('--resume-release-id',args);self.assertNotIn('--recover-orphan-tag',args);self.assertEqual(env['GITHUB_TOKEN'],'fixture-token');self.assertNotIn('POCKETWORLD_MANIFEST_PRIVATE_KEY_B64',env)
 def test_cross_run_prevents_publish_command(self):
  output=m.prepare(self.env,self.execute);before=len(self.calls);self.env['GITHUB_RUN_ID']='124'
  self.assertRaises(ValueError,m.publish,self.env,output,False,None,False,self.execute);self.assertEqual(len(self.calls),before)
 def test_rebuilt_run_cannot_request_automatic_resume(self):
  before=len(self.calls)
  self.assertRaises(ValueError,m.publish,self.env,self.root/'any',True,7,False,self.execute)
  self.assertEqual(len(self.calls),before)
if __name__=='__main__':unittest.main()
