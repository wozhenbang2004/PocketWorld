import base64,contextlib,importlib.util,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
s=importlib.util.spec_from_file_location('ci_tools',Path(__file__).with_name('ci-update-tools.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
DER=Path(__file__).with_name('manifest-signing-public.der')
class Tests(unittest.TestCase):
 def test_verifier_receives_same_approved_key_without_checkout_read(self):
  original=lambda repo:('old','old');observed=[]
  tools=SimpleNamespace(configured_pin=original)
  def verify(runtime,args):observed.append(tools.configured_pin('/never/read/checkout'));return 0
  tools.verify_envelope=verify
  self.assertEqual(m.dispatch(tools,None,'verify-envelope',['--envelope','fixture'],DER),0)
  self.assertEqual(observed[0][1],m.PIN);self.assertEqual(base64.b64decode(observed[0][0]),DER.read_bytes());self.assertIs(tools.configured_pin,original)
 def test_override_restored_on_verification_failure(self):
  original=lambda repo:('old','old');tools=SimpleNamespace(configured_pin=original)
  def fail(*a):raise ValueError('rejected')
  tools.verify_envelope=fail
  self.assertRaises(ValueError,m.dispatch,tools,None,'verify-envelope',[],DER);self.assertIs(tools.configured_pin,original)
 def test_prepare_uses_explicit_url_and_restores_default(self):
  original=lambda:'https://old.example/release.json';observed=[]
  module=SimpleNamespace(configured_manifest_url=original,__file__='original-prepare.py')
  module.main=lambda:observed.append(module.configured_manifest_url()) or 0
  runtime=SimpleNamespace(existing=lambda name:module,build_tools='/sdk',java_home='/java')
  @contextlib.contextmanager
  def arguments(path,args):yield
  self.assertEqual(m.dispatch(SimpleNamespace(arguments=arguments),runtime,'prepare',[],DER),0)
  self.assertEqual(observed,[m.MANIFEST]);self.assertIs(module.configured_manifest_url,original)
 def test_wrong_public_key_stops_before_original_tool(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as t:
   path=Path(t)/'wrong.der';path.write_bytes(b'public fixture')
   self.assertRaises(ValueError,m.dispatch,None,None,'verify-envelope',[],path)
if __name__=='__main__':unittest.main()
