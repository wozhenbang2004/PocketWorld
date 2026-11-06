"""Build a faithful screenshot report. PNG bytes remain unmodified; no generated UI art."""
import argparse,base64,hashlib,html,json,re,shutil,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--evidence-root',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
web=Path(__file__).resolve().parents[1];out=a.output;out.mkdir(parents=True,exist_ok=True);images=out/'screens';images.mkdir(exist_ok=True)
labels={'primary':'对话、任务与项目','settings':'设置与模型','knowledge':'记忆与目标','collaboration':'协作与设备','artifacts':'文件与成果','ecosystem':'技能与插件','browser':'浏览器与通话','overlays':'阅读与临时操作','models':'模型选择','reference-sessions':'路线参考与电脑会话'}
owners={'artifacts':'artifacts','settings':'settings','knowledge':'knowledge','collaboration':'collaboration','ecosystem':'ecosystem','browser':'browser','models':'ecosystem','primary':None,'overlays':None,'reference-sessions':'collaboration'}
def photo(v):return isinstance(v,str) and v.lower().endswith(('.png','.jpg','.jpeg','.webp'))
def entries(node,phase=None,width=None,sha=None):
 if isinstance(node,dict):
  phase=node.get('phase',phase);width=node.get('width',width);sha=node.get('sha256',sha)
  vp=node.get('viewport');width=(vp.get('width',width) if isinstance(vp,dict) else vp[0] if isinstance(vp,list) and vp else width)
  for k,v in node.items():
   ph=k if k in ['before','after'] else phase;w=int(k) if str(k).isdigit() and int(k)>200 else width
   if photo(v) and (k in ['before','after'] or k in ['path','screenshot'] and ph in ['before','after']):yield ph,w,v,sha
   elif isinstance(v,(dict,list)):yield from entries(v,ph,w,sha)
 elif isinstance(node,list):
  for v in node:yield from entries(v,phase,width,sha)
def resolve(s,module,doc):
 q=Path(s)
 if q.is_absolute():return q
 owner=a.evidence_root/('pw-depth-'+owners.get(module,module)) if owners.get(module,module) else web.parent
 for base in [owner/'web-ui',owner,web,web.parent,Path(doc.get('evidence_directory','/nonexistent'))]:
  if (base/q).is_file():return base/q
 raise FileNotFoundError(s)
scenes=[];copied={}
for manifest in sorted((web/'tests').glob('coverage-*.json')):
 module=manifest.stem.removeprefix('coverage-');doc=json.loads(manifest.read_text());items=doc.get('scenes',[])
 if not isinstance(items,list):continue
 for n,s in enumerate(items):
  ident=str(s.get('id') or s.get('scene') or s.get('name') or n);title=str(s.get('label') or s.get('title') or (s.get('scene') if re.search(r'[\u4e00-\u9fff]',str(s.get('scene',''))) else None) or s.get('task') or s.get('primary_task') or s.get('scene') or ident)
  item={'module':module,'group':labels.get(module,module),'id':ident,'title':title,'task':s.get('primaryTask')or s.get('primary_task')or s.get('mainTask')or s.get('task')or'', 'change':s.get('change')or s.get('changes')or'', 'verification':s.get('verification')or s.get('validation')or'', 'images':[],'manifest':manifest.name}
  for key in ['status','sourceUnchanged','limitation']:
   if key in s:item[key]=s[key]
  seen=set()
  for phase,width,src,sha in entries(s):
   if phase not in ['before','after'] or not width:continue
   original=resolve(src,module,doc);data=original.read_bytes();digest=hashlib.sha256(data).hexdigest()
   if sha and sha!=digest:raise ValueError('Screenshot identity changed: '+str(original))
   key=(phase,int(width))
   if key in seen:continue
   seen.add(key);name=module+'-'+hashlib.sha256(ident.encode()).hexdigest()[:12]+f'-{phase}-{width}'+original.suffix.lower();target=images/name
   shutil.copyfile(original,target);copied[name]=target
   item['images'].append({'phase':phase,'width':int(width),'path':'screens/'+name,'sha256':digest,'bytes':len(data)})
  if item['images']:scenes.append(item)
# Pick a readable overview while the ZIP keeps every scene, width and original PNG.
curated=[]
for module in dict.fromkeys(s['module'] for s in scenes):
 candidates=[s for s in scenes if s['module']==module and any(i['width']==360 and i['phase']=='after' for i in s['images'])]
 ranked=sorted(candidates,key=lambda s:(bool(re.search(r'error|pending|unknown|disabled|empty|stale|conflict|warning|failure',s['id'])),bool(re.search(r'review|edit|history|options|schema',s['id'])),scenes.index(s)))
 curated+=ranked[:7 if module=='settings' else 5]
report={'kind':'actual-component-screen-comparison','boundary':'Actual React components and production Web captures with synthetic native state. Not Android device screenshots, billing or native persistence verification.','scenes':scenes,'sceneCount':len(scenes),'imageCount':sum(len(s['images'])for s in scenes)}
(out/'screen-coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
style='''body{margin:0;background:#f5f5f5;color:#202020;font:15px/1.65 system-ui,sans-serif}main{max-width:1360px;margin:auto;padding:36px 20px}h1{font-size:30px;letter-spacing:-.04em;margin:0 0 8px}h2{font-size:20px;margin:0}p{margin:8px 0;color:#656565}.tools{display:flex;gap:10px;flex-wrap:wrap;position:sticky;top:0;background:#f5f5f5ed;padding:16px 0;z-index:2}input,select{font:inherit;border:1px solid #ddd;background:white;border-radius:10px;padding:9px 12px}article{margin:26px 0;padding:20px;background:white;border-radius:18px;box-shadow:0 3px 18px #00000006}.pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;align-items:start}figure{margin:8px 0;background:#fafafa;border-radius:12px;padding:8px}figcaption{color:#666;font-size:12px;margin:4px 0 8px}img{display:block;width:100%;height:auto;max-height:850px;object-fit:contain;object-position:top}small{color:#747474}details{margin-top:12px}a{color:inherit}button{font:inherit}@media(max-width:700px){main{padding:22px 12px}.pair{grid-template-columns:1fr}h1{font-size:25px}article{padding:14px}}'''
def document(chosen,embedded):
 body=[]
 for s in chosen:
  choices=[i for i in s['images'] if i['width']==360]or s['images'][:2]
  slots=[]
  for ph in ['before','after']:
   v=next((i for i in choices if i['phase']==ph),None)
   if not v:continue
   src='data:image/png;base64,'+base64.b64encode((out/v['path']).read_bytes()).decode() if embedded else v['path']
   slots.append('<figure><figcaption>'+('改造前'if ph=='before'else'当前')+' · '+str(v['width'])+'px</figcaption><img loading="lazy" src="'+src+'" alt="'+html.escape(s['title']+' '+ph)+'"></figure>')
  change=s['change'];change='；'.join(map(str,change))if isinstance(change,list)else str(change)
  wide=''
  if not embedded:
   views=[v for v in s['images'] if v not in choices]
   if views:wide='<details><summary>查看宽屏与其他尺寸</summary><div class="pair">'+''.join('<figure><figcaption>'+('改造前'if v['phase']=='before'else'当前')+' · '+str(v['width'])+'px</figcaption><img loading="lazy" src="'+v['path']+'" alt="'+html.escape(s['title']+' '+v['phase']+' '+str(v['width']))+'"></figure>'for v in views)+'</div></details>'
  retained=s.get('sourceUnchanged') is True or s.get('status')=='reviewed_retained'
  body.append('<article data-module="'+html.escape(s['module'])+'" data-find="'+html.escape(s['title']+' '+s['id']+' '+s['group'])+'"><small>'+html.escape(s['group'])+' · '+html.escape(s['id'])+(' · 已检查保留'if retained else'')+'</small><h2>'+html.escape(s['title'])+'</h2><p>'+html.escape(str(s['task']))+'</p><div class="pair">'+''.join(slots)+'</div>'+wide+'<details><summary>改动与验证范围</summary><p>'+html.escape(change)+'</p><p>'+html.escape(str(s.get('limitation','')))+'实际组件，模拟原生状态；完整场景、双宽度与PNG身份见 screen-coverage.json。</p></details></article>')
 options=''.join('<option value="'+html.escape(m)+'">'+html.escape(labels.get(m,m))+'</option>'for m in dict.fromkeys(s['module']for s in chosen))
 return '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PocketWorld 界面截图对照</title><style>'+style+'</style><main><h1>PocketWorld · 逐界面截图对照</h1><p>Web实际组件与构建 · 测试状态 · 非Android真机截图</p><p>完整记录 '+str(report['sceneCount'])+' 个场景、'+str(report['imageCount'])+' 张图。'+('本文件选取主要界面便于手机浏览；全部子界面与宽屏对照保留在完整ZIP。'if embedded else'本报告包含全部已记录场景，原始PNG未经重绘。')+'</p><div class="tools"><input id="q" placeholder="查找页面或场景"><select id="group"><option value="">全部范围</option>'+options+'</select></div>'+''.join(body)+'</main><script>const q=document.querySelector("#q"),g=document.querySelector("#group");function filter(){for(const x of document.querySelectorAll("article"))x.hidden=!!((g.value&&x.dataset.module!==g.value)||!x.dataset.find.toLowerCase().includes(q.value.toLowerCase()))}q.oninput=g.onchange=filter</script></html>'
(out/'index.html').write_text(document(scenes,False));preview=out/'PocketWorld-界面预览.html';preview.write_text(document(curated,True))
archive=out/'PocketWorld-全场景截图对照.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6)as z:
 for f in [out/'index.html',out/'screen-coverage.json',*copied.values()]:z.write(f,f.relative_to(out))
print(json.dumps({'scenes':report['sceneCount'],'images':report['imageCount'],'previewScenes':len(curated),'previewBytes':preview.stat().st_size,'zipBytes':archive.stat().st_size,'output':str(out)},ensure_ascii=False))
