import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import hashlib
s=importlib.util.spec_from_file_location('planner',Path(__file__).with_name('github-release-plan.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=Path(__file__).parent);self.addCleanup(self.tmp.cleanup)
  self.root=Path(self.tmp.name);self.sha=hashlib.sha256(b'apk').hexdigest()
  (self.root/'app.apk').write_bytes(b'apk')
  self.payload={'versionCode':2,'packageName':'dev.pocketengine','apk':{'sha256':self.sha,'bytes':3,'url':'https://old/app.apk'},'deltas':[]}
  self.evidence={'kind':'pocketworld-update-artifact-evidence','sameSignerSet':True,'strictlyNewerVersion':True,'target':{'versionCode':2,'sha256':self.sha}}
  self.upload={'artifactUploads':[{'file':'app.apk','sha256':self.sha,'bytes':3}]};self.save()
 def save(self):
  for name,value in [('unsigned-payload.json',self.payload),('artifact-evidence.json',self.evidence),('release-plan.json',self.upload)]: (self.root/name).write_text(json.dumps(value))
 def test_valid_unsigned_remap(self):
  r=m.plan(self.root,'v2');self.assertFalse(r['signed']);self.assertFalse(r['installedClientCompatible']);self.assertIn('/releases/download/v2/',r['payload']['apk']['url']);self.assertEqual(json.loads((self.root/'unsigned-payload.json').read_text()),self.payload)
 def test_changed_bytes(self):
  (self.root/'app.apk').write_bytes(b'bad');self.assertRaises(ValueError,m.plan,self.root,'v2')
 def test_signed_bundle(self):
  (self.root/'release.json').write_text('{}');self.assertRaises(ValueError,m.plan,self.root,'v2')
 def test_bad_tag(self): self.assertRaises(ValueError,m.plan,self.root,'../main')
 def test_no_signer_proof(self):
  self.evidence['sameSignerSet']=False;self.save();self.assertRaises(ValueError,m.plan,self.root,'v2')
 def test_escape(self):
  self.upload['artifactUploads'][0]['file']='../escape.apk';self.save();self.assertRaises(ValueError,m.plan,self.root,'v2')
 def test_delta_without_reconstruction(self):
  patch=b'patch';sha=hashlib.sha256(patch).hexdigest();(self.root/'update.gz').write_bytes(patch)
  self.payload['deltas']=[{'sha256':sha,'bytes':5,'url':'https://old/patch'}]
  self.upload['artifactUploads'].append({'file':'update.gz','sha256':sha,'bytes':5})
  self.evidence['delta']={'sha256':sha,'reconstructedByteExact':False}
  self.save();self.assertRaises(ValueError,m.plan,self.root,'v2')
  self.evidence['delta']['reconstructedByteExact']=True;self.save()
  self.assertEqual(len(m.plan(self.root,'v2')['assets']),2)
 def test_payload_identity(self):
  self.payload['versionCode']=1;self.save();self.assertRaises(ValueError,m.plan,self.root,'v2')
if __name__=='__main__':unittest.main()
