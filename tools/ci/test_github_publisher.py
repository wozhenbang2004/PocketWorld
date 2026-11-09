import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
s=importlib.util.spec_from_file_location('pub',Path(__file__).with_name('github-publisher.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Fake:
 def __init__(self):self.calls=[];self.assets=[];self.data={};self.bad_hash=False;self.bad_range=False;self.changed=False;self.latest_calls=0;self.loss=False;self.activated=False;self.tag=None;self.upload_loss=False;self.upload_calls=0
 def json(self,path,method='GET',value=None,allow404=False):
  self.calls.append((path,method,value))
  if '/releases?per_page=' in path:return []
  if path.endswith('/latest'):
   self.latest_calls+=1
   return {'id':9,'tag_name':'v259','published_at':'later'} if self.changed and self.latest_calls>1 else None
  if '/git/ref/tags/' in path:return None if self.tag is None else {'object':{'type':'commit','sha':self.tag}}
  if path.endswith('/git/refs') and method=='POST':self.tag=value['sha'];return {'object':{'type':'commit','sha':self.tag}}
  if '/releases/tags/' in path:return None
  if method=='POST':return {'id':1,'draft':True}
  if method=='PATCH':
   self.activated=True
   if self.loss:raise OSError('lost')
  return {'id':1,'draft':not self.activated,'tag_name':'v259','target_commitish':'a'*40,'assets':self.assets}
 def upload(self,rid,name,path):
  self.upload_calls+=1
  aid=len(self.assets)+1;data=Path(path).read_bytes();self.data[aid]=data
  row={'id':aid,'name':name,'size':len(data),'state':'uploaded'};self.assets.append(row)
  if self.upload_loss:self.upload_loss=False;raise OSError('lost upload reply')
  return row
 def download(self,aid,limit,range_header=None):
  data=self.data[aid]
  if range_header:return (200 if self.bad_range else 206),{'content-range':f'bytes 0-{min(64,len(data))-1}/{len(data)}'},data[:limit]
  return 200,{},b'bad' if self.bad_hash else data
class Tests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=Path(__file__).parent);self.addCleanup(self.tmp.cleanup)
  self.assets=[]
  for name,data in [('app.apk',b'APK'),('release.json',b'SIGNED')]:
   p=Path(self.tmp.name)/name;p.write_bytes(data);self.assets.append({'file':str(p),'name':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
  self.client=Fake();self.verified=[]
 def verify(self,raw):
  self.verified.append(raw)
  a=self.assets[0]
  return {'verified':True,'payload':{'packageName':'dev.pocketengine','versionCode':259,'apk':{'url':'https://github.com/'+m.REPO+'/releases/download/v259/'+a['name'],'sha256':a['sha256'],'bytes':a['bytes']},'deltas':[]}}
 def run_pub(self,activate=False):return m.publish(self.client,'v259','a'*40,self.assets,self.verify,activate)
 def test_draft_default(self):
  r=self.run_pub();self.assertFalse(r['published']);self.assertFalse(self.client.activated);self.assertEqual(self.verified,[b'SIGNED',b'SIGNED'])
 def test_explicit_publish_after_checks(self):self.assertTrue(self.run_pub(True)['published'])
 def test_hash_failure_never_publishes(self):
  self.client.bad_hash=True;self.assertRaises(m.Stop,self.run_pub,True);self.assertFalse(self.client.activated)
 def test_range_failure_never_publishes(self):
  self.client.bad_range=True;self.assertRaises(m.Stop,self.run_pub,True);self.assertFalse(self.client.activated)
 def test_verifier_failure_never_publishes(self):
  def reject(data):raise ValueError('signature')
  self.assertRaises(ValueError,m.publish,self.client,'v259','a'*40,self.assets,reject,True);self.assertFalse(self.client.activated)
 def test_concurrent_release_never_publishes(self):
  self.client.changed=True;self.assertRaises(m.Stop,self.run_pub,True);self.assertFalse(self.client.activated)
 def test_lost_publish_reply_reconciles_without_second_patch(self):
  self.client.loss=True;self.assertTrue(self.run_pub(True)['published']);self.assertEqual(sum(x[1]=='PATCH' for x in self.client.calls),1)
 def test_changed_local_file_rejected_before_network(self):
  Path(self.assets[0]['file']).write_bytes(b'changed');self.assertRaises(m.Stop,self.run_pub,True);self.assertEqual(self.client.calls,[])
 def test_false_verifier_cannot_activate(self):
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,lambda _:False,True);self.assertEqual(self.client.calls,[])
 def test_none_verifier_cannot_activate(self):
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,lambda _:None,True);self.assertEqual(self.client.calls,[])
 def test_signed_version_mismatch(self):
  def wrong(raw):
   v=self.verify(raw);v['payload']['versionCode']=260;return v
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,wrong,True);self.assertEqual(self.client.calls,[])
 def test_signed_hash_mismatch(self):
  def wrong(raw):
   v=self.verify(raw);v['payload']['apk']['sha256']='0'*64;return v
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,wrong,True);self.assertEqual(self.client.calls,[])
 def test_resume_verified_draft_does_not_upload_again(self):
  self.run_pub();before=self.client.upload_calls
  result=m.publish(self.client,'v259','a'*40,self.assets,self.verify,False,1)
  self.assertFalse(result['published']);self.assertEqual(self.client.upload_calls,before)
 def test_lost_upload_reply_reconciles(self):
  self.client.upload_loss=True;self.assertEqual(self.run_pub()['status'],'verified_draft');self.assertEqual(self.client.upload_calls,2)
 def test_moved_tag_rejects_resume(self):
  self.run_pub();self.client.tag='b'*40
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,self.verify,True,1);self.assertFalse(self.client.activated)
 def test_orphan_tag_explicit_recovery(self):
  self.client.tag='a'*40
  result=m.publish(self.client,'v259','a'*40,self.assets,self.verify,False,None,True)
  self.assertEqual(result['status'],'verified_draft')
  self.assertFalse(any(x[0].endswith('/git/refs') and x[1]=='POST' for x in self.client.calls))
 def test_orphan_tag_wrong_sha_rejected(self):
  self.client.tag='b'*40
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,self.verify,False,None,True)
 def test_orphan_tag_conflicting_draft_rejected(self):
  self.client.tag='a'*40;original=self.client.json
  def conflict(path,*a,**kw):
   if '/releases?per_page=' in path:return [{'id':9,'draft':True,'tag_name':'v259'}]
   return original(path,*a,**kw)
  self.client.json=conflict
  self.assertRaises(m.Stop,m.publish,self.client,'v259','a'*40,self.assets,self.verify,False,None,True)
if __name__=='__main__':unittest.main()
