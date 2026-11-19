import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog } from './forms-library';
import { InlineDiff } from './shell-library';
import { IconFile, IconRefresh } from './icons';

type Data=Record<string,any>;
type Draft={text:string;baseline:string;version:number;dirty:boolean};
type Item={projectId:string;path:string;keepId:string;baseVersion:number;baseRevisionId:string;baseSha256:string;sha256:string;bytes:number;keptAt:number;text?:string};
const isSha=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const isId=(v:unknown)=>typeof v==='string'&&/^[a-f0-9-]{36}$/.test(v);
const item=(v:Data,project:string,path?:string):v is Item=>v?.projectId===project&&typeof v.path==='string'&&v.path.length>0&&(!path||v.path===path)&&isId(v.keepId)&&isSha(v.sha256)&&
  Number.isSafeInteger(v.bytes)&&v.bytes>=0&&v.bytes<=262144&&Number.isSafeInteger(v.baseVersion)&&v.baseVersion>=0&&
  (v.baseVersion===0?v.baseRevisionId===''&&v.baseSha256==='absent':isId(v.baseRevisionId)&&isSha(v.baseSha256))&&Number.isSafeInteger(v.keptAt);
const stamp=(row:Item)=>new Date(row.keptAt).toLocaleString('zh-CN',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
const fingerprint=async(value:string)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))),b=>b.toString(16).padStart(2,'0')).join('');
const ownerView=()=>(bridge.getState().view||{}) as {taskId?:string;section?:string;resumeRevision?:number};
function useShelf(project:string,path?:string,version=0){
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[rows,setRows]=useState<Item[]>([]),[requested,setRequested]=useState<Item|null>(null);
  const alive=useRef(false),time=useRef(0),flight=useRef<object|null>(null);
  const seen=()=>{const {state}=bridge.getState(),view=ownerView();return alive.current&&!document.hidden&&!view.taskId&&view.section==null&&
    state.projectId===project&&(path?state.filePath===path&&state.fileVersion===version:!state.filePath);};
  const read=async(mode:string,args:Data={},consume?:(r:Data)=>void)=>{
    if(!seen()||flight.current)return;const token={},epoch=time.current,doc=bridge.getDocumentEpoch(),resume=ownerView().resumeRevision;
    const current=()=>seen()&&time.current===epoch&&flight.current===token&&bridge.getDocumentEpoch()===doc&&ownerView().resumeRevision===resume;
    flight.current=token;setBusy(true);setError('');
    try{const r=await bridge.command<Data>('projects.keptDrafts.'+mode,{projectId:project,...(path?{path,version}:{}),...args});
      if(!current())return;
      if(r.projectId!==project||r.path!==(path??null)||r.mode!==mode||r.projectVersionWritten!==false||r.networkUsed!==false)throw Error('回执不属于这个原项目或文件。');
      if(mode==='list'||mode==='forFile'){if(r.bodyRead!==false||!Array.isArray(r.entries)||r.entries.length>16||!r.entries.every((v:Data)=>item(v,project,path)&&!('text' in v)))throw Error('原项目的保留稿清单不完整。');setRows(r.entries);setRequested(r.entries.find((row:Item)=>row.keepId===r.selectedKeepId&&row.sha256===r.selectedKeepSha256)||null);}
      consume?.(r);return r;
    }catch(e){if(current())setError(e instanceof Error?e.message:'手机保留稿未能核对。');}
    finally{if(flight.current===token){flight.current=null;if(alive.current)setBusy(false);}}
  };
  useEffect(()=>{alive.current=true;time.current++;flight.current=null;setBusy(false);setError('');setRows([]);setRequested(null);
    const hidden=()=>{if(document.hidden){time.current++;flight.current=null;setBusy(false);}};
    document.addEventListener('visibilitychange',hidden);if(path)void read('forFile');
    return()=>{alive.current=false;time.current++;flight.current=null;document.removeEventListener('visibilitychange',hidden);};
  },[project,path,version]);
  return {busy,error,setError,rows,read,seen,requested};
}
export function ProjectKeptDraftIndex({project}:{project:string}) {
  const s=useShelf(project),[opened,setOpened]=useState(false);
  return <div className="pw-form-actions"><Button variant="ghost" size="small" leadingIcon={IconFile} disabled={s.busy} onClick={()=>{setOpened(true);void s.read('list')}}>本机保留的文件草稿</Button>
    {opened&&<SettingsDialog isOpen title="这个项目在手机保留的草稿" closeLabel="返回项目文件" onClose={()=>setOpened(false)}>
      <p className="pw-field-hint">只列你明确保留的输入，不是正式文件或云端副本。</p>
      {s.error&&<p role="alert">{s.error}</p>}{!s.busy&&!s.error&&!s.rows.length&&<p>此项目还没有登记保留稿。</p>}
      {s.rows.map(r=><div key={r.keepId}><Button variant="secondary" disabled={s.busy} onClick={()=>void s.read('open',{path:r.path,keepId:r.keepId,sha256:r.sha256})}><bdi>{r.path}</bdi></Button>
        <small> {r.baseVersion?'基于 v'+r.baseVersion:'未建正式版本'} · {stamp(r)} · {r.bytes} 字节</small></div>)}
      <Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={s.busy} onClick={()=>void s.read('list')}>重新读取清单</Button>
    </SettingsDialog>}</div>;
}
export function ProjectKeptFileDrafts({state,draft,edit,disabled}:{state:Data;draft?:Draft;edit:(s:string)=>void;disabled:boolean}){
  const project=String(state.projectId),path=String(state.filePath),version=Number(state.fileVersion||0),s=useShelf(project,path,version);
  const held=state.heldFormalDraft, nativeHeld=held?.projectId===project&&held.path===path&&typeof held.text==='string'?held:null;
  const choice=draft?.dirty?{text:draft.text,version:draft.version,baseline:draft.baseline}:nativeHeld?{text:String(nativeHeld.text),version:Number(nativeHeld.version),baseline:null}:null;
  const live=useRef({draft,choice,edit,disabled});live.current={draft,choice,edit,disabled};
  const [receipt,setReceipt]=useState(''),[chooseKept,setChooseKept]=useState(false),[selected,setSelected]=useState<{r:Item;text:string;review:string;same:boolean;old?:string}|null>(null),[removing,setRemoving]=useState(false);
  const prepare=useRef(false);
  useEffect(()=>{setSelected(null);setChooseKept(false);setRemoving(false);setReceipt('');const hide=()=>{if(document.hidden){setSelected(null);setChooseKept(false);setRemoving(false);}};
    document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide);},[project,path,version]);
  const keep=async()=>{
    const c=live.current.choice;if(!c||prepare.current||s.busy||disabled||!s.seen())return;prepare.current=true;
    try{const hash=c.baseline===null?'native_held':c.version===0?'absent':await fingerprint(c.baseline);
      if(!s.seen()||live.current.choice?.text!==c.text||live.current.choice?.version!==c.version)return;
      const saved=await s.read('keep',{baseVersion:c.version,baseSha256:hash,text:c.text},v=>{
        if(!item(v.item,project,path)||v.durablyReadBack!==true)throw Error('没有取得回读原稿的凭据。');
        setReceipt('这一份已在手机保留并回读。没有保存为正式版本。');});
      if(saved)await s.read('forFile');
    }catch{if(s.seen())s.setError('无法核对原稿的文字基线，未替换任何文件。');}finally{prepare.current=false;}
  };
  const inspect=(r:Item)=>{const previous=live.current.draft?.text;setChooseKept(false);setRemoving(false);void s.read('read',{keepId:r.keepId,sha256:r.sha256},v=>{
    if(!item(v.item,project,path)||v.item.keepId!==r.keepId||v.item.sha256!==r.sha256||typeof v.item.text!=='string'||typeof v.reviewHandle!=='string')throw Error('未取得这份原稿的完整文字。');
    setSelected({r,text:v.item.text,review:v.reviewHandle,same:v.sameFormalBase===true,old:previous});});};
  const lastIndexPick=useRef('');
  useEffect(()=>{const r=s.requested;if(!r||s.busy||disabled||selected||lastIndexPick.current===r.keepId)return;lastIndexPick.current=r.keepId;inspect(r);},
    [s.requested,s.busy,disabled,selected]);
  const stage=async()=>{const snap=selected;if(!snap||s.busy||disabled)return;
    if(live.current.draft?.text!==snap.old){s.setError('编辑区在比较后已变化，请重新打开保留稿；两份文字都没有被替换。');return;}
    await s.read('stage',{keepId:snap.r.keepId,sha256:snap.r.sha256,reviewHandle:snap.review},v=>{
      if(v.item?.keepId!==snap.r.keepId||v.item?.text!==snap.text||v.sameFormalBase!==true||live.current.disabled||live.current.draft?.text!==snap.old)
        throw Error('编辑文字或正式基线已变化，保留稿未写进编辑区。');
      live.current.edit(snap.text);setSelected(null);setReceipt('已放入当前文字草稿，仍须明确保存才更改正式版本。');});};
  const discard=async()=>{const snap=selected;if(!snap||!removing)return;const result=await s.read('discard',{keepId:snap.r.keepId,sha256:snap.r.sha256,reviewHandle:snap.review},v=>{
    if(v.removed!==true)throw Error('尚未核对原稿移除结果。');setSelected(null);setRemoving(false);setReceipt('已移除这份手机保留稿，未改正式文件。');});if(result)await s.read('forFile');};
  if(!choice&&!s.rows.length&&!s.error&&!receipt&&!selected)return null;
  return <section aria-label="仅在手机保留的编辑草稿">
    <div className="pw-form-actions">{choice&&<Button variant="secondary" size="small" disabled={s.busy||disabled} onClick={()=>void keep()}>在这部手机保留草稿</Button>}
      {s.rows.length>0&&<Button variant="ghost" size="small" disabled={s.busy||disabled} onClick={()=>s.rows.length===1?inspect(s.rows[0]):setChooseKept(true)}>{s.rows.length===1?'查看手机保留稿':`查看 ${s.rows.length} 份保留稿`}</Button>}
      {s.error&&<Button variant="ghost" size="small" disabled={s.busy} onClick={()=>void s.read('forFile')}>重新核对本机清单</Button>}</div>
    {choice&&<p className="pw-field-hint">只保留你点选的这一份，后续编辑不会自动备份。</p>}
    {receipt&&<p role="status">{receipt}</p>}{s.error&&<p role="alert">{s.error}</p>}
    {chooseKept&&!selected&&<SettingsDialog isOpen title="选择这个文件的一份保留稿" closeLabel="返回文件" onClose={()=>setChooseKept(false)}>
      {s.rows.map(r=><div key={r.keepId}><Button variant="secondary" disabled={s.busy} onClick={()=>inspect(r)}>{stamp(r)} · {r.bytes} 字节</Button>
        <small> {r.baseVersion?'基于 v'+r.baseVersion:'新文件原稿'} · {r.sha256.slice(0,12)}</small></div>)}
    </SettingsDialog>}
    {selected&&<SettingsDialog isOpen title={removing?'只移除这一份手机保留稿？':'核对手机保留的原文字'} closeLabel="返回正式文件" onClose={()=>{setSelected(null);setRemoving(false)}}>
      <p><bdi>{path}</bdi> · {selected.r.baseVersion?'基于 v'+selected.r.baseVersion:'创建正式文件之前'} · {selected.r.bytes} 字节</p>
      {removing?<p>这份手机保留稿不能在这里撤回。正式文件、其余保留稿和当前编辑文字不会改变。</p>:<>
        {typeof selected.old==='string'?<InlineDiff before={selected.old} after={selected.text} versionLabel="当前编辑区 → 所选手机保留稿" beforeLabel="当前编辑区" afterLabel="手机保留稿"/>:
          <pre className="pw-file-revision-content">{selected.text||'（原稿为空）'}</pre>}
        {!selected.same&&<p role="status">原来的正式基线已变化或不能核对，原稿可读，不会覆盖当前文件。</p>}
        <p className="pw-field-hint">草稿 SHA-256 {selected.r.sha256}</p></>}
      <div className="pw-form-actions">{removing?<><Button variant="secondary" disabled={s.busy} onClick={()=>setRemoving(false)}>保留原稿</Button><Button disabled={s.busy} onClick={()=>void discard()}>只移除这份原稿</Button></>:
        <>{selected.same&&!!draft&&state.fileReady&&draft.version===version&&<Button disabled={s.busy||disabled} onClick={()=>void stage()}>将这份文字放入当前草稿</Button>}
        <Button variant="ghost" disabled={s.busy} onClick={()=>setRemoving(true)}>移除这份手机保留稿…</Button></>}</div>
      {s.error&&<><p role="alert">{s.error}</p><Button variant="ghost" size="small" disabled={s.busy} onClick={()=>inspect(selected.r)}>重新核对这份原稿</Button></>}
    </SettingsDialog>}
  </section>;
}
