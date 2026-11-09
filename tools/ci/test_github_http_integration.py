"""Runs actual Client request/redirect/upload code with fake HTTP transport only."""
import hashlib,importlib.util,json,tempfile,unittest,urllib.parse
from pathlib import Path
s=importlib.util.spec_from_file_location('publisher_http',Path(__file__).with_name('github-publisher.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Response:
 def __init__(self,code,data=b'',headers=None):self.code=code;self.data=data;self.headers=headers or {}
 def __enter__(self):return self
 def __exit__(self,*a):pass
 def read(self,limit):return self.data[:limit]
class HTTP:
 def __init__(self):self.tag=None;self.assets=[];self.data={};self.published=False;self.requests=[];self.corrupt=False;self.badhost=False
 def open(self,req,timeout=None):
  self.requests.append(req);p=urllib.parse.urlsplit(req.full_url);path=p.path;method=req.get_method()
  def reply(data,code=200):return Response(code,json.dumps(data).encode())
  if p.hostname.endswith('githubusercontent.com'):
   assert req.get_header('Authorization') is None,'credential forwarded to CDN'
   data=self.data[int(path.rsplit('/',1)[1])]
   if self.corrupt:data=b'BAD'
   if req.get_header('Range'):
    n=int(req.get_header('Range').rsplit('-',1)[1])+1
    return Response(206,data[:n],{'Content-Range':f'bytes 0-{n-1}/{len(data)}'})
   return Response(200,data)
  if path.endswith('/latest'):return reply({},404)
  if '/git/ref/tags/' in path:return reply({},404) if self.tag is None else reply({'object':{'type':'commit','sha':self.tag}})
  if path.endswith('/git/refs'):self.tag=json.loads(req.data)['sha'];return reply({'object':{'type':'commit','sha':self.tag}},201)
  if '/releases/tags/' in path:return reply({},404)
  if p.hostname=='uploads.github.com':
   name=urllib.parse.parse_qs(p.query)['name'][0];aid=len(self.assets)+1;self.data[aid]=req.data
   row={'id':aid,'name':name,'size':len(req.data),'state':'uploaded'};self.assets.append(row);return reply(row,201)
  if '/releases/assets/' in path:
   aid=int(path.rsplit('/',1)[1]);host='evil.example' if self.badhost else 'release-assets.githubusercontent.com'
   return Response(302,headers={'Location':f'https://{host}/files/{aid}?signature=test'})
  if path.endswith('/releases') and method=='POST':return reply({'id':1,'draft':True},201)
  if method=='PATCH':self.published=True
  return reply({'id':1,'draft':not self.published,'tag_name':'v259','target_commitish':'a'*40,'assets':self.assets})
class Tests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=Path(__file__).parent);self.addCleanup(self.tmp.cleanup);self.assets=[]
  for name,data in [('app.apk',b'APK'),('release.json',b'ENVELOPE')]:
   p=Path(self.tmp.name)/name;p.write_bytes(data);self.assets.append({'name':name,'file':str(p),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
  self.http=HTTP();self.client=m.Client('fake-not-a-real-token');self.client.opener=self.http
 def verify(self,data):
  a=self.assets[0];return {'verified':True,'payload':{'packageName':'dev.pocketengine','versionCode':259,'apk':{'url':f'https://github.com/{m.REPO}/releases/download/v259/app.apk','bytes':a['bytes'],'sha256':a['sha256']},'deltas':[]}}
 def run_flow(self):return m.publish(self.client,'v259','a'*40,self.assets,self.verify,True)
 def test_actual_http_client_flow_with_redirect_and_range(self):
  self.assertTrue(self.run_flow()['published']);self.assertTrue(any(urllib.parse.urlsplit(r.full_url).hostname=='release-assets.githubusercontent.com' for r in self.http.requests))
 def test_remote_corruption_stops_before_publish(self):
  self.http.corrupt=True;self.assertRaises(m.Stop,self.run_flow);self.assertFalse(self.http.published)
 def test_untrusted_redirect_stops_before_publish(self):
  self.http.badhost=True;self.assertRaises(m.Stop,self.run_flow);self.assertFalse(self.http.published)
if __name__=='__main__':unittest.main()
