import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { Accordion, AccordionItem, Button, SelectField } from './forms-library';
import { bridge } from './bridge';
import { packagePreviewChoices, type PackagePreviewChoices } from './project-preview';

const READ='projects.savedBuildWeb.read';
type Copy={id:string;name:string;createdAt:number};
type Version={taskId:string;operationId:string;runId:string;epoch:number;projectId:string;checkoutId:string;
  sourceDigest:string;artifactId:string;archiveSha256:string;archiveBytes:number;createdAt:number;versionSequence:number;entryPath:'dist/index.html'};
type Shelf={checkoutId:string;checkouts:Copy[];items:Version[];hasMore:boolean;nextCursor:string;readLimited:boolean};
type CheckoutSelection={projectId:string;checkoutId:string};
type BuildSelection=CheckoutSelection&{taskId:string;sourceDigest:string;runId:string;epoch:number};
type ShelfSelection=(CheckoutSelection&{kind:'checkout'})|(BuildSelection&{kind:'build'});
export type SavedWebWork=Pick<Version,'taskId'|'checkoutId'|'runId'|'epoch'>;
export type SavedWebShelfVisit={restore:()=>void;openOriginalWork?:()=>void};
export type FindSavedBuildWeb=(selection:ShelfSelection)=>boolean;
// This link only reveals the existing receipt shelf in the same project workspace.
export const ProjectSavedWebLink=createContext<RefObject<FindSavedBuildWeb|null>|null>(null);
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
  project:string;active:boolean;canPreview:boolean;openWork?:(work:SavedWebWork,restoreShelf:()=>boolean)=>void;
  show:(choices:PackagePreviewChoices,stillCurrent:()=>boolean,visit:SavedWebShelfVisit)=>boolean;
}) {
  const [expanded,expand]=useState(false),[shelf,setShelf]=useState<Shelf|null>(null),[copy,setCopy]=useState('');
  const [selection,select]=useState<BuildSelection|null>(null);
  const link=useContext(ProjectSavedWebLink),heading=useRef<HTMLParagraphElement>(null),focusOnOpen=useRef(false);
  const [busy,setBusy]=useState(''),[error,setError]=useState('');
  const current=useRef({project,active,copy,selection,shelf});current.current={project,active,copy,selection,shelf};
  const restoreVersion=useRef<(()=>void)|null>(null);
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
    if(!cursor){setCopy(selected);setShelf(null);}
    const response=await bridge.command(READ,{projectId:project,checkoutId:selected,cursor});if(!here())return;
    const verified=page(response,project,selected,cursor);
    if(prior){
      const known=new Set(prior.items.map(v=>v.operationId));
      if(verified.items.some(v=>known.has(v.operationId)))throw Error('原版本分页重复，未混入其它记录。');
      verified.items=[...prior.items,...verified.items];verified.readLimited||=prior.readLimited;
    }
    setCopy(verified.checkoutId);setShelf(verified);
  });
  useLayoutEffect(()=>{
    if(!link||!active)return;
    const reveal:FindSavedBuildWeb=picked=>{
      if(picked.projectId!==project||!id(picked.checkoutId)||!current.current.active||!owner()())return false;
      const build=picked.kind==='build'?picked:null;
      if(build&&(!id(build.taskId)||!hash(build.sourceDigest)||typeof build.runId!=='string'||
        !build.runId||build.runId.length>240||!int(build.epoch,1)))return false;
      // A checkout visit removes only the source filter, never the exact original checkout.
      // Supersede only an old presentation read. Its token cannot open a late preview.
      request.current=null;focusOnOpen.current=true;expandedRef.current=true;expand(true);select(build);read(picked.checkoutId);
      return true;
    };
    link.current=reveal;return()=>{if(link.current===reveal)link.current=null;};
  });
  useLayoutEffect(()=>{
    if(!active||!expanded||!focusOnOpen.current||!heading.current||document.hidden)return;
    focusOnOpen.current=false;heading.current.focus({preventScroll:true});heading.current.scrollIntoView({block:'nearest'});
  });
  useLayoutEffect(()=>{
    const restore=restoreVersion.current;
    if(!active||!restore)return;
    // The parent work tab restores its own scroll when it becomes visible.
    const frame=requestAnimationFrame(()=>{
      if(restoreVersion.current!==restore)return;
      restoreVersion.current=null;
      if(current.current.active)restore();
    });
    return()=>cancelAnimationFrame(frame);
  },[active]);
  // A later model failure in the same task does not erase an earlier save. Do not
  // filter on the task's *current* run; every displayed row still owns its original run and operation.
  const matches=(v:Version)=>!selection||v.checkoutId===selection.checkoutId&&v.taskId===selection.taskId&&v.sourceDigest===selection.sourceDigest;
  const versions=shelf?.items.filter(matches)||[];
  const selectedName=selection&&shelf?.checkouts.find(c=>c.id===selection.checkoutId)?.name;
  const shelfFocus=(trigger:HTMLButtonElement,currentVersion:()=>boolean)=>{
    const scroll=trigger.closest<HTMLElement>('.pw-projects-page');
    const offset=scroll?trigger.getBoundingClientRect().top-scroll.getBoundingClientRect().top:0;
    return ()=>{
      if(!current.current.active||!currentVersion()||!scroll?.contains(trigger)||trigger.disabled||trigger.closest('[hidden],[inert]'))return false;
      scroll.scrollTop+=trigger.getBoundingClientRect().top-scroll.getBoundingClientRect().top-offset;
      trigger.focus({preventScroll:true});
      return true;
    };
  };
  const openShelfWork=(work:SavedWebWork,trigger:HTMLButtonElement,containsOriginal:()=>boolean)=>{
    const original=owner();
    const sameShelf=()=>original()&&expandedRef.current&&current.current.copy===work.checkoutId&&containsOriginal();
    if(sameShelf())openWork?.(work,shelfFocus(trigger,sameShelf));
  };
  const open=(picked:Version,trigger:HTMLButtonElement)=>{
    const originalShelf=owner();
    const sameShelf=()=>originalShelf()&&expandedRef.current&&current.current.copy===picked.checkoutId&&current.current.selection===selection&&
      current.current.shelf?.items.includes(picked)===true;
    const focusVersion=shelfFocus(trigger,sameShelf);
    void observe(picked.operationId,async(here,original)=>{
      if(!canPreview||!matches(picked)||picked.checkoutId!==shelf?.checkoutId||!shelf.items.includes(picked))return;
      const target={taskId:picked.taskId,operationId:picked.operationId};
      const response=await bridge.command('projects.package.preview.inspect',target);if(!here())return;
      const exact=packagePreviewChoices(response,target);
      if(!exact||exact.mode!=='web_output'||exact.projectId!==project||exact.checkoutId!==picked.checkoutId||
        exact.sourceDigest!==picked.sourceDigest||exact.artifactId!==picked.artifactId||exact.artifactSha256!==picked.archiveSha256||exact.entryPath!==picked.entryPath||
        exact.originalBuild&&(exact.originalBuild.runId!==picked.runId||exact.originalBuild.epoch!==picked.epoch))
        throw Error('选中的已保存网页与当前回执不同，没有改用其它版本。');
      if(selection&&!exact.originalBuild)throw Error('未核对到这条记录的原构建轮次，没有按该轮打开网页。可回原工作，或单独核对副本的归档版本。');
      if(!show(exact,()=>original()&&sameShelf(),{
        restore:()=>{restoreVersion.current=()=>{
          if(sameShelf()&&!focusVersion())heading.current?.focus({preventScroll:true});
        };},
        openOriginalWork:exact.originalBuild&&openWork?()=>{if(sameShelf())openWork(picked,focusVersion);}:undefined,
      }))throw Error('项目窗口已变化，原网页没有打开。');
    });
  };
  return <Accordion variant="divided" expandedKeys={expanded?['saved-build-web']:[]} onExpandedChange={keys=>{
    const next=keys.has('saved-build-web');expandedRef.current=next;expand(next);
    if(!next&&request.current){request.current=null;setBusy('');}
    if(next&&!request.current)read(selection?.checkoutId||copy);
  }}>
    <AccordionItem id="saved-build-web" title="已保存的构建网页" description="按原副本找回已保存的版本">
      <p ref={heading} tabIndex={-1} className="pw-reading-note">{selection?
        <>只看这项工作使用同一源码保存的网页：{selectedName||'私人副本'} <code>{selection.checkoutId.slice(0,8)}</code> · 源码 <code>{selection.sourceDigest.slice(0,10)}</code>。</>:
        '只列已确认保存的网页。'} 打开时核对原包，不重新构建，也不读取现在的生成目录。</p>
      {!selection&&shelf&&shelf.checkouts.length>1&&<SelectField label="原私人副本" value={copy} isDisabled={!!busy}
        options={[{id:'',label:'选择要看的副本'},...shelf.checkouts.map(c=>({id:c.id,label:`${c.name||'私人副本'} · ${c.id.slice(0,8)}`}))]}
        onChange={v=>{setCopy(v);setShelf(old=>old?{...old,checkoutId:'',items:[],hasMore:false,nextCursor:'',readLimited:false}:null);if(v)read(v)}} />}
      {!selection&&shelf?.checkouts.length===1&&<p className="pw-field-hint">{shelf.checkouts[0].name||'私人副本'} · {shelf.checkoutId.slice(0,8)}</p>}
      {busy==='list'&&<p role="status">正在核对原网页版本…</p>}
      {shelf&&!busy&&!error&&shelf.checkouts.length===0&&<p>这个项目没有可核对的私人副本。</p>}
      {shelf?.checkoutId&&!versions.length&&!busy&&!error&&(!shelf.readLimited||shelf.hasMore)&&<p role="status">{selection?
        shelf.hasMore?'已读取的记录里还没有这项工作用该源码保存的网页，可继续查更早的记录。':'没有查到这项工作用该源码保存的网页。可以回原工作查看日志、构建产物和保存步骤。':
        shelf.hasMore?'这一页没有已保存的网页，可继续查更早的记录。':'这个副本还没有已保存的构建网页。'}</p>}
      {shelf?.readLimited&&<p className="pw-reading-note" role="status">有些旧保存记录暂不可核对，未将它们当作可打开的网页。</p>}
      {!!versions.length&&<ul className="pw-form-flow">{versions.map(v=><li key={v.operationId}>
        <strong>{selection&&`第 ${v.epoch} 轮 · `}保存版本 {v.versionSequence}</strong> <time dateTime={new Date(v.createdAt).toISOString()}>{new Date(v.createdAt).toLocaleString()}</time>
        {selection&&(v.epoch!==selection.epoch||v.runId!==selection.runId)&&<p className="pw-field-hint">{v.epoch<selection.epoch?'较早轮次保存的网页':'另一轮保存的网页'}，不是进入时所选轮次的结果。</p>}
        <p className="pw-field-hint">源码 {v.sourceDigest.slice(0,10)} · 固定包 {v.archiveSha256.slice(0,10)}</p>
        <div className="pw-form-actions"><Button variant="secondary" size="small" disabled={!canPreview||!!busy||!active} onClick={event=>open(v,event.currentTarget)}>
          {busy===v.operationId?'正在核对原网页…':'打开这版网页'}</Button>
          {!selection&&openWork&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={event=>openShelfWork(v,event.currentTarget,
            ()=>!current.current.selection&&current.current.shelf?.items.includes(v)===true)}>查看原工作</Button>}</div>
      </li>)}</ul>}
      {error&&<p role="alert">{error}</p>}
      <div className="pw-form-actions">
        {shelf?.hasMore&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>read(shelf.checkoutId,shelf.nextCursor)}>更多原版本</Button>}
        <Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>read(selection?.checkoutId||copy)}>{copy?'刷新这份副本':'刷新副本清单'}</Button>
        {selection&&openWork&&<Button variant="secondary" size="small" disabled={!!busy||!active} onClick={event=>openShelfWork(selection,event.currentTarget,
          ()=>current.current.selection===selection)}>查看原构建任务</Button>}
        {selection&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>{select(null);read(selection.checkoutId);}}>查看这份副本的所有版本</Button>}
        {error&&copy&&!selection&&<Button variant="ghost" size="small" disabled={!!busy||!active} onClick={()=>read('')}>重新选择副本</Button>}
      </div>
    </AccordionItem>
  </Accordion>;
}
