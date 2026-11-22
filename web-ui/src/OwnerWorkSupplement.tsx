import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {Button,IconButton,RadioOptions,TextAreaField} from './forms-library';
import {IconClose,IconRefresh} from './icons';
import {ThinkingLoader} from './library';
import {MotionFeedback} from './library/journey-motion';
import {bridge} from './bridge';
import {ownerWorkIdentity,workIdentityKey,type WorkIdentity} from './conversation-work';
import {ownerWorkPresentation} from './owner-work-presentation';

type Json=Record<string,any>;
export type WorkSupplementTarget=WorkIdentity&{conversationId:string;goal:string;overviewPlacement?:'progress'};
export type WorkSupplementLeave={flush:()=>Promise<boolean>;keepsReceipt:()=>boolean};
type CurrentDraftCopy={runId:string;epoch:number;revision:number};
type WorkView={section?:string|null;taskId?:string|null;resumeRevision?:number};
type Draft=WorkIdentity&{conversationId:string;key:string;revision:number;text:string;mode:'queue'|'steer';current:boolean;canQueue:boolean;canSteer:boolean;reason:string;pending:Json[];otherDrafts?:Json[];receipt?:Json;copyToCurrent?:CurrentDraftCopy};
type Props={target:WorkSupplementTarget;task:Json;can:(action:string)=>boolean;registerBeforeLeave:(guard:WorkSupplementLeave|null,panel:HTMLElement)=>void;onClose:(flush:WorkSupplementLeave['flush'])=>void;onSelectOriginal:(target:WorkIdentity)=>void};
const binding=(target:WorkSupplementTarget)=>({taskId:target.taskId,runId:target.runId,epoch:target.epoch,conversationId:target.conversationId});
const expectedKey=(target:WorkSupplementTarget)=>`work:${target.taskId}:${target.runId}:${target.epoch}`;
const failureText=(error:unknown)=>error instanceof Error?error.message:'原补充尚未确认，请保留本页文字并核对。';
const sameContent=(a:{text:string;mode:string},b:{text:string;mode:string})=>a.text===b.text&&a.mode===b.mode;
const queueLabel='等当前答复结束再加入',steerLabel='下个安全步骤加入';
const settledReceipt=(status:unknown)=>['accepted','queued','consumed','withdrawn','rejected','not_sent'].includes(String(status));

function readDraft(value:Json,target:WorkSupplementTarget):Draft{
 if(!value||value.key!==expectedKey(target)||value.taskId!==target.taskId||value.runId!==target.runId||value.epoch!==target.epoch||value.conversationId!==target.conversationId
  ||!Number.isSafeInteger(value.revision)||value.revision<1||typeof value.text!=='string'||value.text.length>12000||!['queue','steer'].includes(value.mode)||!Array.isArray(value.pending))throw new Error('原工作草稿身份不一致，未替换本页文字。');
 return value as Draft;
}
function copyTarget(draft:Draft|null,source:WorkSupplementTarget,task:Json|undefined):CurrentDraftCopy|undefined{
 const target=draft?.copyToCurrent,current=ownerWorkIdentity(task);
 if(!draft||!target||typeof target!=='object'||Object.keys(target).length!==3||draft.current!==false||draft.pending.length||
    !draft.text.trim()||!current||current.taskId!==source.taskId||task?.raw?.conversationId!==source.conversationId||
    target.runId!==current.runId||target.runId===source.runId||target.epoch!==current.epoch||target.epoch<=source.epoch||
    !Number.isSafeInteger(target.revision)||target.revision<1||target.revision>=Number.MAX_SAFE_INTEGER)return;
 return target;
}
function receiptLabel(receipt:Json|undefined):string{
 if(!receipt)return '';
 const timing=receipt.mode==='queue'?queueLabel:receipt.mode==='steer'?steerLabel:'';
 const labels:Record<string,string>={accepted:'这条补充已接收。',queued:timing?`已按「${timing}」登记，尚未确认采用。`:'补充已登记，尚未确认采用。',consumed:'原工作已采用这条补充。',withdrawn:'这条补充已撤回。',rejected:'原工作未接收这条补充，草稿仍保留。',not_sent:'这条补充没有发送，草稿仍保留。',unconfirmed:'这条补充的接收结果仍待核对。'};
 return labels[String(receipt.status)]||'原补充回执待核对。';
}

/** Retained BoardUI text/radio controls and the existing queued-input editor
 * relation. The host owns draft CAS, durable pending identity and every receipt. */
export function OwnerWorkSupplement({target,task,can,registerBeforeLeave,onClose,onSelectOriginal}:Props){
 const [draft,setDraft]=useState<Draft|null>(null),[text,setText]=useState(''),[mode,setMode]=useState<'queue'|'steer'>('queue');
 const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const [receipt,showReceipt]=useState<Json>(),[unknownClient,showUnknownClient]=useState<string|null>(null);
 const live=useRef<{draft:Draft|null;text:string;mode:'queue'|'steer';paused:boolean;receipt?:Json;unknownClient:string|null}>({draft:null,text:'',mode:'queue',paused:false,unknownClient:null});
 const setReceipt=(value:Json|undefined)=>{live.current.receipt=value;showReceipt(value)};
 const setUnknownClient=(client:string|null)=>{live.current.unknownClient=client;showUnknownClient(client)};
 const saveFlight=useRef<Promise<boolean>|null>(null),actionFlight=useRef<false|'refresh'|'receipt'|'copy'>(false),mounted=useRef(true),input=useRef<HTMLTextAreaElement>(null);
 const editSequence=useRef(0),submittedSequence=useRef<number|null>(null),panel=useRef<HTMLElement>(null);
 const currentIdentity=ownerWorkIdentity(task),current=!!currentIdentity&&workIdentityKey(currentIdentity)===workIdentityKey(target);
 const accepts=current&&task.raw?.controls?.canInput===true;
 const notifyError=(message:string)=>{if(mounted.current)setError(message)};
 const apply=(value:Json,preserveLocal:boolean)=>{
  const next=readDraft(value,target);live.current.draft=next;
  if(!preserveLocal){live.current.text=next.text;live.current.mode=next.mode;if(mounted.current){setText(next.text);setMode(next.mode)}}
  if(mounted.current)setDraft(next);
  return next;
 };
 const read=async(preserveLocal=false)=>{
  if(mounted.current)setLoading(true);
  try{const next=apply(await bridge.command('ownerWork.supplement.read',binding(target)),preserveLocal);if(next.receipt&&mounted.current)setReceipt(next.receipt);return next}
  catch(failure){notifyError(failureText(failure));return null}
  finally{if(mounted.current)setLoading(false)}
 };
 const flush=(explicit=false):Promise<boolean>=>{
  if(saveFlight.current)return saveFlight.current;
  if(!live.current.draft)return Promise.resolve(!live.current.text);
  if(sameContent(live.current,live.current.draft))return Promise.resolve(true);
  if(live.current.paused&&!explicit)return Promise.resolve(false);
  if(!can('ownerWork.supplement.edit'))return Promise.resolve(false);
  live.current.paused=false;if(mounted.current){setSaving(true);setError('')}
  const savingNow=(async()=>{
   while(live.current.draft&&!sameContent(live.current,live.current.draft)){
    const prior=live.current.draft,captured={text:live.current.text,mode:live.current.mode};
    try{
     const saved=readDraft(await bridge.command('ownerWork.supplement.edit',{...binding(target),key:prior.key,revision:prior.revision,...captured}),target);
     if(!sameContent(saved,captured))throw new Error('原补充保存内容不一致，本页文字仍保留。');
     apply(saved,true);
    }catch(failure){
     // A lost save response never justifies overwriting an unseen native revision.
     live.current.paused=true;notifyError(failureText(failure));
     const actual=await read(true);
     if(actual&&sameContent(live.current,actual)){live.current.paused=false;notifyError('');return true}
     return false;
    }
   }
   return true;
  })().finally(()=>{saveFlight.current=null;if(mounted.current)setSaving(false)});
  saveFlight.current=savingNow;return savingNow;
 };
 useEffect(()=>{mounted.current=true;void read().then(value=>{if(value&&mounted.current)input.current?.focus({preventScroll:true})});return()=>{mounted.current=false}},[]);
 useLayoutEffect(()=>{
  const shown=panel.current;if(!shown)return;
  registerBeforeLeave({flush:()=>flush(),keepsReceipt:()=>{
   const {draft,receipt,unknownClient}=live.current;
   return actionFlight.current==='receipt'||!!unknownClient||
    draft?.pending.some(row=>!settledReceipt(row.receipt?.status??row.status))===true||
    !!receipt&&!settledReceipt(receipt.status);
  }},shown);
  return()=>registerBeforeLeave(null,shown);
 });
 const edit=(next:string,nextMode=live.current.mode)=>{
  editSequence.current++;live.current.text=next;live.current.mode=nextMode;setText(next);setMode(nextMode);setReceipt(undefined);void flush();
 };
 const refresh=async()=>{
  if(actionFlight.current||saveFlight.current)return;
  actionFlight.current='refresh';setError('');
  const dirty=!!live.current.draft&&!sameContent(live.current,live.current.draft);
  try{await read(dirty)}finally{actionFlight.current=false}
 };
 const copyToCurrent=async(selected:CurrentDraftCopy)=>{
  const original=live.current.draft,doc=bridge.getDocumentEpoch(),element=panel.current;
  const resume=(bridge.getState().view as WorkView|undefined)?.resumeRevision;
  const showingOriginal=()=>{
   const current=bridge.getState(),state=current.state,view=current.view as WorkView|undefined;
   const rows=Array.isArray(state.tasks)?state.tasks.filter(row=>row?.id===target.taskId):[];
   return mounted.current&&!!element?.isConnected&&!element.closest('[hidden],[inert],[aria-hidden="true"]')&&!document.hidden&&
    bridge.getDocumentEpoch()===doc&&Number.isSafeInteger(resume)&&view?.section===null&&view.resumeRevision===resume&&
    view.taskId===target.taskId&&!state.conversationReader&&state.attachmentWorkspace?.open!==true&&
    live.current.draft===original&&rows.length===1&&copyTarget(original,target,rows[0])===selected;
  };
  if(!original||actionFlight.current||saveFlight.current||live.current.unknownClient||!sameContent(live.current,original)||
     !can('ownerWork.supplement.edit')||!showingOriginal())return;
  actionFlight.current='copy';setBusy(true);setError('');
  try{
   const saved=await bridge.command<Json>('ownerWork.supplement.edit',
    {...binding(target),key:original.key,revision:original.revision,copyToCurrent:selected});
   if(!showingOriginal())return;
   if(saved?.saved!==true||saved.sent!==false||saved.taskId!==target.taskId||saved.conversationId!==target.conversationId||
      saved.fromRunId!==target.runId||saved.fromEpoch!==target.epoch||saved.fromRevision!==original.revision||
      saved.runId!==selected.runId||saved.epoch!==selected.epoch||saved.revision!==selected.revision+1)
    throw new Error('复制结果尚未确认，请核对原草稿。没有发送补充。');
   const retained={...original,copyToCurrent:undefined};
   live.current.draft=retained;setDraft(retained);
   onSelectOriginal({taskId:target.taskId,runId:selected.runId,epoch:selected.epoch});
  }catch(failure){if(showingOriginal()){
   const retained={...original,copyToCurrent:undefined};
   live.current.draft=retained;setDraft(retained);
   notifyError(failureText(failure));
  }}
  finally{actionFlight.current=false;if(mounted.current)setBusy(false)}
 };
 const submit=async(retry?:Json)=>{
  if(actionFlight.current||!can('ownerWork.supplement.send')||!accepts)return;
  actionFlight.current='receipt';setBusy(true);setError('');
  try{
   if(!await flush(true))return;
   const original=live.current.draft;if(!original||!original.text.trim()||!original.current||original.pending.length&&!retry)return;
   const client=retry?.clientMessageId||crypto.randomUUID();submittedSequence.current=editSequence.current;setUnknownClient(client);
   try{
    const result=await bridge.command('ownerWork.supplement.send',{...binding(target),key:original.key,revision:original.revision,clientMessageId:client});
    const next=apply(result,false);setReceipt(next.receipt);
    setUnknownClient(settledReceipt(next.receipt?.status)?null:next.pending.some(row=>row.clientMessageId===client)?client:null);
   }catch(failure){
    notifyError(failureText(failure));
    if((failure as {code?:string})?.code==='not_accepted')setUnknownClient(null);
    await read(editSequence.current!==submittedSequence.current);
   }
  }finally{actionFlight.current=false;if(mounted.current)setBusy(false)}
 };
 const query=async(client:string)=>{
  if(actionFlight.current||!can('ownerWork.supplement.query')||!live.current.draft)return;
  actionFlight.current='receipt';setBusy(true);setError('');
  const original=live.current.draft,dirty=!sameContent(live.current,original)&&editSequence.current!==submittedSequence.current;
  try{
   const next=apply(await bridge.command('ownerWork.supplement.query',{...binding(target),key:original.key,clientMessageId:client}),dirty);
   setReceipt(next.receipt);setUnknownClient(next.pending.some(row=>row.clientMessageId===client)?client:null);
  }catch(failure){notifyError(failureText(failure));await read(true)}
  finally{actionFlight.current=false;if(mounted.current)setBusy(false)}
 };
 const dirty=!!draft&&!sameContent({text,mode},draft),pending=draft?.pending||[];
 const unknownOutsideList=unknownClient&&!pending.some(row=>row.clientMessageId===unknownClient)?unknownClient:null;
 const copy=can('ownerWork.supplement.edit')&&!unknownClient?copyTarget(draft,target,task):undefined;
 const canSend=can('ownerWork.supplement.send')&&!!draft&&accepts&&draft.current&&(mode==='queue'?draft.canQueue:draft.canSteer)&&!pending.length&&!unknownClient&&!loading&&!busy&&!!text.trim();
 // Only the native current draft plus a settled original Work can remove live delivery choices.
 // Saving text keeps the existing mode and never moves this draft to a later run.
 const raw=task.raw,work=raw?.ownerWork,ended=['completed','failed','cancelled'].includes(task.status);
 const handling=ended?ownerWorkPresentation(task):null;
 const keepLocal=current&&draft?.current===true&&draft.canQueue===false&&draft.canSteer===false&&
   raw?.kind==='assistant'&&raw.executionMode==='capabilities'&&raw.status===task.status&&ended&&
   raw.taskProjection==='detail_window'&&raw.taskCoreReadLimited===false&&raw.contextAvailable===true&&raw.contextWithdrawn!==true&&
   raw.sourceCurrentReadRequired!==true&&raw.hasUnresolvedOperations===false&&raw.controls?.canInput===false&&
   raw.origin==null&&raw.parentTaskId==null&&raw.teamRunId==null&&raw.teamAssignment==null&&raw.memberId==null&&
   raw.developmentContext==null&&raw.ownerPhoneControl==null&&raw.ownerDeviceWork==null&&
   work?.format==='pocketworld.owner-work.v1'&&work.workTaskId===target.taskId&&work.conversationId===target.conversationId&&
   !!handling&&!handling.waitingForChildren&&!handling.waitingForButler&&!handling.needsAttention&&!handling.teamsUnavailable;
 return <section ref={panel} className="pw-work-supplement" aria-label={`${keepLocal?'本机补充草稿':'补充给'}：${target.goal}`}>
  <header className="pw-work-supplement-heading"><div><small>{keepLocal?'本机补充草稿':'补充给这件工作'}</small><h3>{target.goal}</h3></div><div className="pw-action-row"><IconButton icon={IconRefresh} aria-label="重新读取原工作补充" disabled={loading||busy||saving} onClick={()=>void refresh()}/><IconButton icon={IconClose} aria-label="收起补充，保留草稿" disabled={saving} onClick={()=>onClose(()=>flush())}/></div></header>
  {loading&&!draft?<ThinkingLoader phase="正在读取这件工作的独立草稿"/>:draft?<>
   <p className="pw-muted">{!current?'工作已进入另一轮；这份草稿仍属于原执行，请核对后决定。':keepLocal?'修改只保存在本机，不会发送或开始新一轮。':draft.reason}</p>
   {copy&&<div className="pw-action-row"><Button variant="secondary" size="small" disabled={loading||saving||busy||dirty}
    onClick={()=>void copyToCurrent(copy)}>{actionFlight.current==='copy'?'正在复制草稿…':'复制到当前轮草稿'}</Button><small className="pw-muted">保留原草稿，不发送。</small></div>}
   {!!draft.otherDrafts?.length&&<div className="pw-work-pending-links" aria-label="保留的原执行草稿">{draft.otherDrafts.filter(row=>row.taskId===target.taskId&&row.conversationId===target.conversationId&&typeof row.runId==='string'&&Number.isSafeInteger(row.epoch)).map(row=><Button key={row.key} variant="ghost" size="small" disabled={busy||saving} onClick={()=>onSelectOriginal({taskId:row.taskId,runId:row.runId,epoch:row.epoch})}>查看另一轮保留的{row.hasText?'补充草稿':'待核对输入'}</Button>)}</div>}
   <TextAreaField ref={input} label="补充内容" value={text} onChange={value=>edit(value)} placeholder="补充要求、调整重点或提供新的信息…" hint={keepLocal?undefined:'文字单独保存；发送后会进入这件工作的原话题。'} rows={3} autoResize maxRows={8} maxLength={12000} isDisabled={busy||loading}/>
   {!keepLocal&&<RadioOptions label="补充何时加入这件工作" value={mode} onChange={value=>edit(live.current.text,value as 'queue'|'steer')} isDisabled={busy||loading} options={[{id:'queue',label:queueLabel},{id:'steer',label:steerLabel}]}/>}
   <div className="pw-work-supplement-footer"><span role="status">{saving?'正在保存草稿':dirty?'本页修改尚未保存':'草稿已保留'}</span><div className="pw-action-row">{dirty&&<Button variant="ghost" size="small" disabled={saving||busy} onClick={()=>void flush(true)}>保存本页补充</Button>}{!keepLocal&&<Button variant="primary" disabled={!canSend} onClick={()=>void submit()}>{busy?'正在核对回执':'发送到这件工作'}</Button>}</div></div>
   {pending.map(row=><div className="pw-work-supplement-receipt" key={row.clientMessageId}><p role="status">{receiptLabel(row.receipt)||'这条补充的接收结果仍待核对。'}</p><div className="pw-action-row"><Button variant="secondary" size="small" disabled={busy||saving} onClick={()=>void query(row.clientMessageId)}>核对原补充回执</Button>{row.canRetryLocallyUnsent===true&&<Button variant="ghost" size="small" disabled={busy||saving||dirty||!accepts} onClick={()=>void submit(row)}>重新保存并发送原补充</Button>}</div></div>)}
   {unknownOutsideList&&<div className="pw-work-supplement-receipt"><p role="status">这次补充的结果尚未确认，原输入身份仍保留。</p><Button variant="secondary" size="small" disabled={busy||saving} onClick={()=>void query(unknownOutsideList)}>查询这次原补充</Button></div>}
   {receipt&&!pending.length&&<MotionFeedback identity={`${receipt.clientMessageId}:${receipt.status}`} className="pw-muted" role="status">{receiptLabel(receipt)}</MotionFeedback>}
  </>:null}
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {(!draft||error||!current)&&<Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={loading||busy||saving} onClick={()=>void refresh()}>重新读取原草稿</Button>}
 </section>;
}
