import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField } from './forms-library';
import { bridge } from './bridge';
import { packagePreviewChoices, type PackagePreviewChoices } from './project-preview';

const READ='projects.savedBuildWeb.read';
type Copy={id:string;name:string;createdAt:number};
type Version={taskId:string;operationId:string;runId:string;epoch:number;projectId:string;checkoutId:string;
  sourceDigest:string;artifactId:string;archiveSha256:string;archiveBytes:number;createdAt:number;versionSequence:number;entryPath:'dist/index.html'};
type Shelf={checkoutId:string;checkouts:Copy[];items:Version[];hasMore:boolean;nextCursor:string;readLimited:boolean};
const id=(v:unknown):v is string=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(v);
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const int=(v:unknown,min:number)=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=min;
function page(value:unknown,project:string,selected:string,cursor:string):Shelf {
  const r=value as Shelf&Record<string,unknown>;
  if(!r||r.format!=='pocketworld.saved-build-web.v1'||r.projectId!==project||r.bodyRead!==false||r.readOnly!==true||
      r.currentArtifactAvailabilityVerified!==false||r.executionAuthorized!==false||
      !Array.isArray(r.checkouts)||r.checkouts.length>128||r.checkouts.some(c=>!c||!id(c.id)||typeof c.name!=='string'||!int(c.createdAt,0))||
      new Set(r.checkouts.map(c=>c.id)).size!==r.checkouts.length||
      (selected?r.checkoutId!==selected:r.checkoutId!==(r.checkouts.length===1?r.checkouts[0].id:''))||
      r.checkoutId!==''&&!r.checkouts.some(c=>c.id===r.checkoutId)||!Array.isArray(r.items)||r.items.length>8||
      r.items.some(v=>!v||!r.checkoutId||v.projectId!==project||v.checkoutId!==r.checkoutId||!id(v.taskId)||!id(v.operationId)||
        typeof v.runId!=='string'||v.runId.length<1||v.runId.length>240||!int(v.epoch,1)||!hash(v.sourceDigest)||!id(v.artifactId)||
        !hash(v.archiveSha256)||!int(v.archiveBytes,1)||!int(v.createdAt,1)||v.createdAt>8.64e15||!int(v.versionSequence,1)||v.entryPath!=='dist/index.html')||
      new Set(r.items.map(v=>v.operationId)).size!==r.items.length||typeof r.readLimited!=='boolean'||typeof r.hasMore!=='boolean'||
      typeof r.nextCursor!=='string'||r.nextCursor.length>2048||(r.hasMore?(!r.nextCursor||r.nextCursor===cursor):r.nextCursor!==''))
    throw Error('没有核对到这份副本的原网页版本清单。');
  return r;
}

/** Exact local receipts only. Opening one listed row re-enters B4; visiting the shelf reads no webpage body. */
export function ProjectSavedBuildWeb({project,active,canPreview,openWork,show}: {
  project:string;active:boolean;canPreview:boolean;openWork?:(task:string,checkout:string)=>void;
  show:(choices:PackagePreviewChoices,stillCurrent:()=>boolean)=>boolean;
}) {
  const [expanded,expand]=useState(false),[shelf,setShelf]=useState<Shelf|null>(null),[copy,setCopy]=useState('');
  const [busy,setBusy]=useState(''),[error,setError]=useState('');
  const current=useRef({project,active,copy});current.current={project,active,copy};
  const alive=useRef(false),request=useRef<object|null>(null),expandedRef=useRef(false);
  const readWindow=()=>{const state=bridge.getState(),view=state.view as {resumeRevision?:number;taskId?:string|null;section?:string|null}|undefined;
    return {doc:bridge.getDocumentEpoch(),resume:view?.resumeRevision,project:state.state.projectId,file:state.state.filePath,task:view?.taskId,section:view?.section};};
  const owner=()=>{const at=readWindow();return ()=>{const now=readWindow();return alive.current&&!document.hidden&&current.current.project===project&&
    at.project===project&&now.project===project&&!at.file&&!now.file&&!at.task&&!now.task&&at.section==null&&now.section==null&&
    at.doc===now.doc&&at.resume===now.resume;};};
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current=null;};},[project]);
  useEffect(()=>{if(!active&&request.current){request.current=null;setBusy('');}},[active]);
  const observe=async(label:string,block:(here:()=>boolean,original:()=>boolean)=>Promise<void>)=>{
    const original=owner();if(!active||!expandedRef.current||request.current||!original())return;
    const token={};request.current=token;setBusy(label);setError('');
    const here=()=>original()&&current.current.active&&expandedRef.current&&request.current===token;
    try {await block(here,original);}catch(failure){if(here())setError(failure instanceof Error?failure.message:'原网页记录未核对，未打开其他版本。');}
    finally{if(request.current===token){request.current=null;if(alive.current)setBusy('');}}
  };
  const read=(selected:string,cursor='')=>void observe('list',async here=>{
    const prior=cursor?shelf:null;
    if(cursor&&(!prior||prior.checkoutId!==selected||prior.nextCursor!==cursor))return;
    const response=await bridge.command(READ,{projectId:project,checkoutId:selected,cursor});if(!here())return;
    const verified=page(response,project,selected,cursor);
    if(prior){
      const known=new Set(prior.items.map(v=>v.operationId));
      if(verified.items.some(v=>known.has(v.operationId)))throw Error('原版本分页重复，未混入其它记录。');
      verified.items=[...prior.items,...verified.items];verified.readLimited||=prior.readLimited;
    }
    setCopy(verified.checkoutId);setShelf(verified);
  });
  const open=(picked:Version)=>void observe(picked.operationId,async(here,original)=>{
    if(!canPreview||picked.checkoutId!==shelf?.checkoutId||!shelf.items.some(v=>v===picked))return;
    const target={taskId:picked.taskId,operationId:picked.operationId};
    const response=await bridge.command('projects.package.preview.inspect',target);if(!here())return;
    const exact=packagePreviewChoices(response,target);
    if(!exact||exact.mode!=='web_output'||exact.projectId!==project||exact.checkoutId!==picked.checkoutId||
      exact.sourceDigest!==picked.sourceDigest||exact.artifactId!==picked.artifactId||exact.artifactSha256!==picked.archiveSha256||exact.entryPath!==picked.entryPath)
      throw Error('选中的已保存网页与当前回执不同，没有改用其它版本。');
    if(!show(exact,()=>original()&&current.current.copy===picked.checkoutId))throw Error('项目窗口已变化，原网页没有打开。');
  });
  return <Accordion variant="divided" expandedKeys={expanded?['saved-build-web']:[]} onExpandedChange={keys=>{
    const next=keys.has('saved-build-web');expandedRef.current=next;expand(next);
    if(!next&&request.current){request.current=null;setBusy('');}
    if(next&&!shelf&&!request.current)read('');
  }}>
    <AccordionItem id="saved-build-web" title="已保存的构建网页" description="按原副本找回已保存的版本">
      <p className="pw-reading-note">只列已确认保存的网页记录。打开时核对原包，不重新构建，也不读取现在的生成目录。</p>
      {shelf&&shelf.checkouts.length>1&&<SelectField label="原私人副本" value={copy} isDisabled={!!busy}
        options={[{id:'',label:'选择要看的副本'},...shelf.checkouts.map(c=>({id:c.id,label:`${c.name||'私人副本'} · ${c.id.slice(0,8)}`}))]}
        onChange={v=>{setCopy(v);setShelf(old=>old?{...old,checkoutId:'',items:[],hasMore:false,nextCursor:'',readLimited:false}:null);if(v)read(v)}} />}
      {shelf?.checkouts.length===1&&<p className="pw-field-hint">{shelf.checkouts[0].name||'私人副本'} · {shelf.checkoutId.slice(0,8)}</p>}
      {busy==='list'&&<p role="status">正在核对原网页版本…</p>}
      {!shelf&&!busy&&!error&&<Button variant="secondary" size="small" disabled={!active} onClick={()=>read('')}>读取原网页版本</Button>}
      {shelf&&!busy&&!error&&shelf.checkouts.length===0&&<p>这个项目没有可核对的私人副本。</p>}
      {shelf?.checkoutId&&!shelf.items.length&&!busy&&!error&&!shelf.hasMore&&!shelf.readLimited&&<p>这个副本还没有已保存的构建网页。</p>}
      {shelf?.readLimited&&<p className="pw-reading-note" role="status">有些旧保存记录暂不可核对，未将它们当作可打开的网页。</p>}
      {!!shelf?.items.length&&<ul className="pw-form-flow">{shelf.items.map(v=><li key={v.operationId}>
        <strong>保存版本 {v.versionSequence}</strong> <time dateTime={new Date(v.createdAt).toISOString()}>{new Date(v.createdAt).toLocaleString()}</time>
        <p className="pw-field-hint">源码 {v.sourceDigest.slice(0,10)} · 固定包 {v.archiveSha256.slice(0,10)}</p>
        <div className="pw-form-actions"><Button variant="secondary" size="small" disabled={!canPreview||!!busy||!active} onClick={()=>open(v)}>
          {busy===v.operationId?'正在核对原网页…':'打开这版网页'}</Button>
          {openWork&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>openWork(v.taskId,v.checkoutId)}>查看原工作</Button>}</div>
      </li>)}</ul>}
      {error&&<p role="alert">{error}</p>}
      {(shelf?.hasMore||error)&&<div className="pw-form-actions">
        {shelf?.hasMore&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>read(shelf.checkoutId,shelf.nextCursor)}>更多原版本</Button>}
        {error&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>read('')}>重新核对副本与版本</Button>}
      </div>}
    </AccordionItem>
  </Accordion>;
}
