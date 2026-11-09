"""GitHub draft upload and verification candidate. Import is side-effect free.
This module never creates keys/builds APKs. Publication requires an explicit flag.
GitHub has no cross-writer compare-and-swap publish API: workflow concurrency
serializes our jobs; latest identity is checked again but other writers remain a risk.
"""
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO='wozhenbang2004/PocketWorld'
API='https://api.github.com'
UPLOAD='https://uploads.github.com'
CDN={'release-assets.githubusercontent.com','objects.githubusercontent.com'}
class Stop(RuntimeError):pass
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

class Client:
 def __init__(self,token):
  if not token:raise Stop('Missing GitHub token')
  self.token=token;self.opener=urllib.request.build_opener(NoRedirect())
 def response(self,url,method='GET',body=None,headers=None,limit=1048576):
  p=urllib.parse.urlsplit(url)
  if p.scheme!='https' or p.username or p.password or p.fragment or p.port not in (None,443):raise Stop('Invalid destination')
  if p.hostname not in {'api.github.com','uploads.github.com',*CDN}:raise Stop('Unexpected destination')
  h={'User-Agent':'PocketWorld-release','Accept':'application/vnd.github+json',**(headers or {})}
  if p.hostname in {'api.github.com','uploads.github.com'}:h['Authorization']='Bearer '+self.token
  # Recreated request per hop; Authorization never reaches asset CDN.
  req=urllib.request.Request(url,data=body,headers=h,method=method)
  try:response=self.opener.open(req,timeout=90)
  except urllib.error.HTTPError as e:response=e
  with response:
   if response.code in (301,302,303,307,308):
    return response.code,dict(response.headers.items()),b''
   data=response.read(limit+1)
   if len(data)>limit:raise Stop('Response exceeds bound')
   return response.code,dict(response.headers.items()),data
 def json(self,path,method='GET',value=None,allow404=False):
  if not path.startswith('/repos/'+REPO+'/'):raise Stop('Unexpected repository')
  data=None if value is None else json.dumps(value).encode()
  code,headers,body=self.response(API+path,method,data,{'Content-Type':'application/json'})
  if code==404 and allow404:return None
  if code not in (200,201):
   route=urllib.parse.urlsplit(path).path
   if re.fullmatch(r'[A-Za-z0-9_./-]+',route):
    print('PWERROR GitHub HTTP '+str(code)+' '+method+' '+route,file=sys.stderr)
   raise Stop('GitHub API operation failed')
  return json.loads(body)
 def upload(self,release_id,name,path):
  data=Path(path).read_bytes()
  url=UPLOAD+'/repos/'+REPO+'/releases/'+str(release_id)+'/assets?name='+urllib.parse.quote(name,safe='')
  code,headers,body=self.response(url,'POST',data,{'Content-Type':'application/octet-stream'})
  if code!=201:raise Stop('Upload failed; inspect draft before retry')
  return json.loads(body)
 def download(self,asset_id,expected_bytes,range_header=None):
  if type(asset_id) is not int or asset_id<=0:raise Stop('Invalid asset ID')
  url=API+'/repos/'+REPO+'/releases/assets/'+str(asset_id)
  seen=set()
  for hop in range(6):
   if url in seen:raise Stop('Download redirect loop')
   seen.add(url)
   headers={'Accept':'application/octet-stream','Accept-Encoding':'identity'}
   if range_header:headers['Range']=range_header
   code,h,data=self.response(url,headers=headers,limit=expected_bytes)
   lower={k.lower():v for k,v in h.items()}
   if code not in (301,302,303,307,308):return code,lower,data
   if hop==5:raise Stop('Too many download redirects')
   url=urllib.parse.urljoin(url,lower.get('location',''))
   p=urllib.parse.urlsplit(url)
   if p.hostname not in CDN or p.scheme!='https' or p.username or p.password or p.fragment or p.port not in (None,443):raise Stop('Unsafe asset redirect')
  raise Stop('Unreachable')

def latest_identity(client):
 item=client.json('/repos/'+REPO+'/releases/latest',allow404=True)
 return None if item is None else (item['id'],item['tag_name'],item.get('published_at'))

def validate_assets(assets):
 names=set()
 for item in assets:
  name=item['name'];path=Path(item['file'])
  if not re.fullmatch(r'[A-Za-z0-9._-]+',name) or name in names:raise Stop('Invalid/duplicate asset name')
  names.add(name)
  if path.stat().st_size!=item['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:raise Stop('Local asset changed')
 if 'release.json' not in names:raise Stop('Signed manifest asset required')

def check_tag(client,tag,head):
 ref=client.json('/repos/'+REPO+'/git/ref/tags/'+tag)
 obj=ref.get('object',{})
 # Only this publisher's lightweight immutable-version tag is accepted.
 if obj.get('type')!='commit' or obj.get('sha')!=head:raise Stop('Git tag does not point to frozen source commit')

def bind_manifest(verification,tag,assets):
 if not isinstance(verification,dict) or verification.get('verified') is not True:
  raise Stop('Manifest verifier did not explicitly approve')
 payload=verification.get('payload')
 if not isinstance(payload,dict) or type(payload.get('versionCode')) is not int or tag!='v'+str(payload['versionCode']) or payload.get('packageName')!='dev.pocketengine':
  raise Stop('Signed version/tag/package mismatch')
 expected={}
 for item in [payload.get('apk',{}),*payload.get('deltas',[])]:
  prefix='https://github.com/'+REPO+'/releases/download/'+tag+'/'
  url=item.get('url','')
  if not url.startswith(prefix):raise Stop('Signed artifact URL mismatch')
  name=url[len(prefix):]
  if not re.fullmatch(r'[A-Za-z0-9._-]+',name) or name in expected:raise Stop('Signed artifact name mismatch')
  expected[name]=(item.get('sha256'),item.get('bytes'))
 actual={a['name']:(a['sha256'],a['bytes']) for a in assets if a['name']!='release.json'}
 if expected!=actual:raise Stop('Signed asset set does not match uploaded APK/patch')

def publish(client,tag,head,assets,verify_manifest,activate=False,resume_release_id=None,recover_orphan_tag=False):
 """verify_manifest(bytes) MUST use existing App verifier and compare pinned identity.
 All bytes are fetched from uploaded draft asset IDs, not trusted upload responses.
 Failures preserve the draft for inspection; this function never deletes/replaces.
 """
 if not re.fullmatch(r'v[1-9][0-9]*',tag) or not re.fullmatch('[a-f0-9]{40}',head):raise Stop('Invalid immutable identity')
 validate_assets(assets)
 manifest=next(x for x in assets if x['name']=='release.json')
 bind_manifest(verify_manifest(Path(manifest['file']).read_bytes()),tag,assets)
 before=latest_identity(client)
 if before is not None:
  if not re.fullmatch(r'v[1-9][0-9]*',before[1]):raise Stop('Existing latest version cannot be ordered safely')
  if int(tag[1:])<=int(before[1][1:]):raise Stop('Release tag must exceed current GitHub latest tag')
 if resume_release_id is None:
  if client.json('/repos/'+REPO+'/releases/tags/'+tag,allow404=True) is not None:raise Stop('Tag already has a release; do not overwrite')
  existing_ref=client.json('/repos/'+REPO+'/git/ref/tags/'+tag,allow404=True)
  if existing_ref is not None:
   if not recover_orphan_tag:raise Stop('Tag exists; explicit orphan recovery required')
   check_tag(client,tag,head)
   # GET by tag may omit drafts; exhaust authenticated release pages before reuse.
   for page in range(1,101):
    releases=client.json('/repos/'+REPO+'/releases?per_page=100&page='+str(page))
    if not isinstance(releases,list):raise Stop('Cannot verify orphan tag')
    if any(r.get('tag_name')==tag for r in releases):raise Stop('Conflicting release exists; resume its explicit ID')
    if len(releases)<100:break
   else:raise Stop('Release history scan incomplete; cannot recover orphan')
  else:
   client.json('/repos/'+REPO+'/git/refs','POST',{'ref':'refs/tags/'+tag,'sha':head})
  check_tag(client,tag,head)
  draft=client.json('/repos/'+REPO+'/releases','POST',{'tag_name':tag,'target_commitish':head,'name':tag,'body':'Personal development build.','draft':True,'prerelease':False})
 else:
  if type(resume_release_id) is not int or resume_release_id<=0:raise Stop('Invalid resume release ID')
  draft=client.json('/repos/'+REPO+'/releases/'+str(resume_release_id))
  if draft.get('tag_name')!=tag or draft.get('target_commitish')!=head:raise Stop('Resume draft identity mismatch')
  check_tag(client,tag,head)
 rid=draft['id']
 if draft.get('draft') is not True:raise Stop('Server did not return a draft')
 existing={a['name']:a for a in draft.get('assets',[])}
 if len(existing)!=len(draft.get('assets',[])) or set(existing)-{x['name'] for x in assets}:raise Stop('Unexpected existing draft assets')
 uploaded=[]
 for item in sorted(assets,key=lambda a:(a['name']=='release.json',a['name'].endswith('.apk'),a['name'])):
  a=existing.get(item['name'])
  if a is None:
   try:a=client.upload(rid,item['name'],item['file'])
   except Exception:
    # Reconcile lost upload response by reading same draft; never blind duplicate.
    current=client.json('/repos/'+REPO+'/releases/'+str(rid))
    matches=[x for x in current.get('assets',[]) if x['name']==item['name']]
    if len(matches)!=1:raise
    a=matches[0]
  if a.get('name')!=item['name'] or a.get('size')!=item['bytes'] or a.get('state')!='uploaded':raise Stop('Asset registration mismatch')
  uploaded.append((item,a['id']))
 for item,aid in uploaded:
  code,h,data=client.download(aid,item['bytes'])
  if code!=200 or len(data)!=item['bytes'] or hashlib.sha256(data).hexdigest()!=item['sha256']:raise Stop('Remote asset hash/size mismatch')
  if item['name']=='release.json':bind_manifest(verify_manifest(data),tag,assets)
  length=min(64,item['bytes'])
  code,h,sample=client.download(aid,length,'bytes=0-'+str(length-1))
  if code!=206 or h.get('content-range')!='bytes 0-'+str(length-1)+'/'+str(item['bytes']) or sample!=data[:length]:raise Stop('Remote Range validation failed')
 validate_assets(assets)
 state=client.json('/repos/'+REPO+'/releases/'+str(rid))
 if state.get('draft') is not True or state.get('tag_name')!=tag or state.get('target_commitish')!=head:raise Stop('Draft identity changed')
 # Reject added/deleted/replaced assets since verification.
 actual={(x['id'],x['name'],x['size']) for x in state.get('assets',[])}
 expected={(aid,item['name'],item['bytes']) for item,aid in uploaded}
 if actual!=expected:raise Stop('Draft assets changed during validation')
 if latest_identity(client)!=before:raise Stop('Another release advanced; draft retained')
 check_tag(client,tag,head)
 if not activate:return {'status':'verified_draft','releaseId':rid,'published':False}
 try:
  result=client.json('/repos/'+REPO+'/releases/'+str(rid),'PATCH',{'draft':False,'make_latest':'true'})
 except Exception:
  # Lost reply is reconciled by read; never issue a blind second PATCH.
  result=client.json('/repos/'+REPO+'/releases/'+str(rid))
 if result.get('draft') is not False or result.get('tag_name')!=tag:raise Stop('Publication outcome unconfirmed')
 check_tag(client,tag,head)
 return {'status':'published','releaseId':rid,'published':True,'publicClientAcceptanceTested':False}
