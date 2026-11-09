import importlib.util
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
s=importlib.util.spec_from_file_location('prepare',Path(__file__).with_name('prepare-github-release.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tests(unittest.TestCase):
 def test_missing_keys_stop_before_any_command(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as t:
   runner=SimpleNamespace(directory=Path(t),c={})
   with self.assertRaisesRegex(ValueError,'signing material'):m.prepare(None,runner,None,None,None,None,None,'v1')
 def test_no_network_or_publish_entrypoint(self):
  import ast
  tree=ast.parse(Path(m.__file__).read_text())
  names={n.func.attr for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute)}
  self.assertTrue({'upload_release','urlopen','request','post','get'}.isdisjoint(names-{'get'}))
  self.assertNotIn('dpapi',Path(m.__file__).read_text().lower())

class PackagedTargetTests(unittest.TestCase):
 def setUp(self):
  import base64
  self.tmp=tempfile.TemporaryDirectory(dir=Path(__file__).parent);self.addCleanup(self.tmp.cleanup)
  self.repo=Path(self.tmp.name)
  self.key=base64.b64encode(Path(__file__).with_name('manifest-signing-public.der').read_bytes()).decode()
  self.props='pocketworldUpdateManifestUrl='+m.MANIFEST+'\npocketworldUpdatePublicKey='+self.key+'\n'
  (self.repo/'gradle.properties').write_text(self.props)
  self.resources='resource 0x7f100001 dev.pocketengine:string/pocketworld_update_manifest_url: t=0x03\n  (string8) "'+m.MANIFEST+'"\nresource 0x7f100002 dev.pocketengine:string/pocketworld_update_public_key: t=0x03\n  (string8) "'+self.key+'"\n'
  self.commands=[]
  def command(args):self.commands.append(args);return 0,self.resources
  self.runner=SimpleNamespace(repo=self.repo,c={'build_tools':'/sdk/build-tools/35.0.0','manifest_public_der':str(Path(__file__).with_name('manifest-signing-public.der'))},command=command)
 def test_matching_packaged_key_and_url(self):
  m.verify_target_resources(self.runner,self.repo/'target.apk')
  self.assertEqual(self.commands[0][1:4],['dump','--values','resources'])
 def test_wrong_packaged_key_rejected(self):
  self.resources=self.resources.replace(self.key,'WRONG');self.assertRaises(ValueError,m.verify_target_resources,self.runner,self.repo/'target.apk')
 def test_old_packaged_url_rejected(self):
  self.resources=self.resources.replace(m.MANIFEST,'https://old.example/release.json');self.assertRaises(ValueError,m.verify_target_resources,self.runner,self.repo/'target.apk')
 def test_missing_resources_rejected(self):
  self.resources='';self.assertRaises(ValueError,m.verify_target_resources,self.runner,self.repo/'target.apk')
 def test_unapproved_explicit_public_der_rejected(self):
  bad=self.repo/'wrong-public.der';bad.write_bytes(b'wrong public key')
  self.runner.c['manifest_public_der']=str(bad)
  self.assertRaises(ValueError,m.verify_target_resources,self.runner,self.repo/'target.apk');self.assertEqual(self.commands,[])
 def test_old_checkout_defaults_remain_untouched(self):
  old='pocketworldUpdateManifestUrl=https://old.example/release.json\npocketworldUpdatePublicKey=OLD\n'
  (self.repo/'gradle.properties').write_text(old)
  m.verify_target_resources(self.runner,self.repo/'target.apk')
  self.assertEqual((self.repo/'gradle.properties').read_text(),old)
 def test_aapt_failure_rejected(self):
  self.runner.command=lambda args:(1,self.resources)
  self.assertRaises(ValueError,m.verify_target_resources,self.runner,self.repo/'target.apk')
 def test_guard_precedes_delta_and_sign(self):
  import inspect
  source=inspect.getsource(m.prepare)
  self.assertLess(source.index('verify_target_resources(runner,new)'),source.index("runner.tool('delta'"))
  self.assertLess(source.index('verify_target_resources(runner,new)'),source.index("runner.tool('sign'"))

if __name__=='__main__':unittest.main()
