import base64,hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
s=importlib.util.spec_from_file_location('cli',Path(__file__).with_name('release-cli.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tests(unittest.TestCase):
 def test_detached_frozen_checkout_accepted(self):
  calls=[]
  def git(*args,**kw):calls.append(args);return (0,'a'*40) if args==('rev-parse','HEAD') else (0,'')
  self.assertEqual(m.FrozenCore(None,'a'*40).source_state(SimpleNamespace(git=git)),'a'*40)
  self.assertFalse(any('symbolic-ref' in a for a in calls))
 def test_changed_frozen_sha_rejected(self):
  self.assertRaises(ValueError,m.FrozenCore(None,'a'*40).source_state,SimpleNamespace(git=lambda *a,**k:(0,'b'*40)))
 def test_wrong_run_rejected(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as t:
   p=Path(t);(p/'handoff.json').write_text(json.dumps({'schema':1,'sourceCommit':'a'*40,'runId':'2'}))
   self.assertRaises(ValueError,m.verify_handoff,p,'a'*40,'1')
 def test_real_callback_invokes_production_tool_and_returns_explicit_proof(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as t:
   root=Path(t);source=root/'source';source.mkdir();(source/'release.json').write_bytes(b'envelope')
   expected={'versionCode':259,'apk':{'sha256':'b'*64,'bytes':5,'url':'https://github.com/x'}}
   calls=[]
   def tool(name,*args):
    calls.append((name,args));out=Path(args[args.index('--output')+1]);out.write_text(json.dumps({'signatureVerified':True,'pinSpkiSha256':m.first.PIN,**expected}))
   r=SimpleNamespace(directory=root,c={'org_json_classpath':'locked.jar'},tool=tool)
   verify=m.verify_callback(r,expected,source);self.assertEqual(verify(b'envelope'),{'verified':True,'payload':expected});self.assertEqual(calls[0][0],'verify-envelope')
 def test_callback_rejects_wrong_pin(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as t:
   root=Path(t);(root/'release.json').write_bytes(b'envelope')
   def tool(name,*args):Path(args[args.index('--output')+1]).write_text(json.dumps({'signatureVerified':True,'pinSpkiSha256':'bad'}))
   r=SimpleNamespace(directory=root,c={'org_json_classpath':'locked.jar'},tool=tool)
   self.assertRaises(ValueError,m.verify_callback(r,{'versionCode':259,'apk':{}},root),b'envelope')
if __name__=='__main__':unittest.main()
