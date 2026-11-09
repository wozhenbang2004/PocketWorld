import copy
import importlib.util
from pathlib import Path
import unittest
s=importlib.util.spec_from_file_location('first',Path(__file__).with_name('first-github-release.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tests(unittest.TestCase):
 def setUp(self):self.meta={'packageName':'dev.pocketengine','signerCertificateSha256':[m.SIGNER],'versionCode':500,'versionName':'0.4.500','minSdk':30,'bytes':123,'sha256':'a'*64}
 def test_actual_approved_public_key(self):
  key=m.public_key(Path(__file__).with_name('manifest-signing-public.der'));self.assertTrue(key.startswith('MI'))
 def test_build_explicit_both_properties(self):
  args=m.build_command(Path(__file__).with_name('manifest-signing-public.der'),500,'0.4.500',499)
  self.assertIn('-PpocketworldVersionCode=500',args);self.assertIn('-PpocketworldVersionName=0.4.500',args);self.assertIn('-PpocketworldUpdateManifestUrl='+m.MANIFEST,args)
 def test_no_old_base_or_delta(self):
  data=m.payload(self.meta,'v500',1000000,499);self.assertEqual(data['deltas'],[]);self.assertEqual(data['expiresAt']-data['issuedAt'],7*86400000);self.assertIn('/download/v500/',data['apk']['url'])
 def test_wrong_signer(self):
  self.meta['signerCertificateSha256']=['b'*64];self.assertRaises(ValueError,m.payload,self.meta,'v500',1000000,499)
 def test_new_chain_pins_github_apk_signer(self):
  self.assertEqual(m.SIGNER,'b58e014e2a2e49b2e85ebd5b47a034a28cb0d313d71f379a5208d21afe388e58')
  spec=importlib.util.spec_from_file_location('delta',Path(__file__).with_name('prepare-github-release.py'))
  delta=importlib.util.module_from_spec(spec);spec.loader.exec_module(delta)
  self.assertEqual(delta.SIGNER,m.SIGNER)
  workflow=Path(__file__).with_name('github-release-workflow.candidate.yml').read_text()
  self.assertIn("certificate_sha256 = '"+m.SIGNER+"'",workflow)
  self.assertNotIn('ed896ec1199f5be4e591a3222ddd60486ea1fce6853f1bb850aa5b699f8b01a0',workflow)
 def test_old_chain_signer_rejected(self):
  self.meta['signerCertificateSha256']=['ed896ec1199f5be4e591a3222ddd60486ea1fce6853f1bb850aa5b699f8b01a0']
  self.assertRaises(ValueError,m.payload,self.meta,'v500',1000000,499)
 def test_wrong_tag(self):self.assertRaises(ValueError,m.payload,self.meta,'v501',1000000,499)
 def test_bad_version(self):
  self.meta['versionCode']=True;self.assertRaises(ValueError,m.payload,self.meta,'vTrue',1000000,499)
 def test_packaged_resources(self):
  text='resource 0x7f100001 dev.pocketengine:string/pocketworld_update_manifest_url: t=0x03\n  (string8) "'+m.MANIFEST+'"\nresource 0x7f100002 dev.pocketengine:string/pocketworld_update_public_key: t=0x03\n  (string8) "PUBLIC"\n'
  m.check_resources(text,'PUBLIC');self.assertRaises(ValueError,m.check_resources,text,'WRONG')
  self.assertRaises(ValueError,m.check_resources,text+text,'PUBLIC')
 def test_empty_resources(self):self.assertRaises(ValueError,m.check_resources,'','PUBLIC')
 def test_missing_floor_rejected(self):self.assertRaises(ValueError,m.payload,self.meta,'v500',1000000,None)
 def test_same_or_lower_version_rejected(self):
  for floor in (500,501):self.assertRaises(ValueError,m.payload,self.meta,'v500',1000000,floor)
 def test_version_one_not_bootstrap_exception(self):
  self.meta['versionCode']=1;self.assertRaises(ValueError,m.payload,self.meta,'v1',1000000,1)
if __name__=='__main__':unittest.main()
