import {useEffect,useRef,useState} from 'react';
import {Button,IconButton,RadioOptions,TextAreaField} from './forms-library';
import {IconClose,IconRefresh} from './icons';
import {ThinkingLoader} from './library';
import {MotionFeedback} from './library/journey-motion';
import {bridge} from './bridge';
import {ownerWorkIdentity,workIdentityKey,type WorkIdentity} from './conversation-work';

type Json=Record<string,any>;
export type WorkSupplementTarget=WorkIdentity&{conversationId:string;goal:string};
export type WorkSupplementLeave=()=>Promise<boolean>;
type Draft=WorkIdentity&{conversationId:string;key:string;revision:number;text:string;mode:'queue'|'steer';current:boolean;canQueue:boolean;canSteer:boolean;reason:string;pending:Json[];otherDrafts?:Json[];receipt?:Json};
type Props={target:WorkSupplementTarget;task:Json;can:(action:string)=>boolean;registerBeforeLeave:(guard:WorkSupplementLeave|null)=>void;onClose:()=>void;onSelectOriginal:(target:WorkIdentity)=>void};
const binding=(target:WorkSupplementTarget)=>({taskId:target.taskId,runId:target.runId,epoch:target.epoch,conversationId:target.conversationId});
const expectedKey=(target:WorkSupplementTarget)=>`work:${target.taskId}:${target.runId}:${target.epoch}`;
const failureText=(error:unknown)=>error instanceof Error?error.message:'原补充尚未确认，请保留本页文字并核对。';
const sameContent=(a:{text:string;mode:string},b:{text:string;mode:string})=>a.text===b.text&&a.mode===b.mode;

function readDraft(value:Json,target:WorkSupplementTarget):Draft{
 if(!value||value.key!==expectedKey(target)||value.taskId!==target.taskId||value.runId!==target.runId||value.epoch!==target.epoch||value.conversationId!==target.conversationId
  ||!Number.isSafeInteger(value.revision)||value.revision<1||typeof value.text!=='string'||value.text.length>12000||!['queue','steer'].includes(value.mode)||!Array.isArray(value.pending))throw new Error('原工作草稿身份不一致，未替换本页文字。');
 return value as Draft;
}
function receiptLabel(receipt:Json|undefined):string{
 if(!receipt)return '';
 const labels:Record<string,string>={accepted:'这条补充已接收。',queued:receipt.mode==='steer'?'已加入原工作，将在下个安全步骤采用。':'已加入原工作，将在本轮结束后处理。',consumed:'原工作已采用这条补充。',withdrawn:'这条补充已撤回。',rejected:'原工作未接收这条补充，草稿仍保留。',not_sent:'这条补充没有发送，草稿仍保留。',unconfirmed:'这条补充的接收结果仍待核对。'};
 return labels[String(receipt.status)]||'原补充回执待核对。';
}

/** Retained BoardUI text/radio controls and the existing queued-input editor
 * relation. The host owns draft CAS, durable pending identity and every receipt. */
export function OwnerWorkSupplement({target,task,can,registerBeforeLeave,onClose,onSelectOriginal}:Props){
 const [draft,setDraft]=useState<Draft|null>(null),[text,setText]=useState(''),[mode,setMode]=useState<'queue'|'steer'>('queue');
 const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const [receipt,setReceipt]=useState<Json>(),[unknownClient,setUnknownClient]=useState<string|null>(null);
 const live=useRef<{draft:Draft|null;text:string;mode:'queue'|'steer';paused:boolean}>({draft:null,text:'',mode:'queue',paused:false});
 const saveFlight=useRef<Promise<boolean>|null>(null),actionFlight=useRef(false),mounted=useRef(true),input=useRef<HTMLTextAreaElement>(null);
 const editSequence=useRef(0),submittedSequence=useRef<number|null>(null);
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
 useEffect(()=>{registerBeforeLeave(()=>flush());return()=>registerBeforeLeave(null)});
 const edit=(next:string,nextMode=live.current.mode)=>{
  editSequence.current++;live.current.text=next;live.current.mode=nextMode;setText(next);setMode(nextMode);setReceipt(undefined);void flush();
 };
 const refresh=async()=>{
  if(actionFlight.current||saveFlight.current)return;
  actionFlight.current=true;setError('');
  const dirty=!!live.current.draft&&!sameContent(live.current,live.current.draft);
  try{await read(dirty)}finally{actionFlight.current=false}
 };
 const submit=async(retry?:Json)=>{
  if(actionFlight.current||!can('ownerWork.supplement.send')||!accepts)return;
  actionFlight.current=true;setBusy(true);setError('');
  try{
   if(!await flush(true))return;
   const original=live.current.draft;if(!original||!original.text.trim()||!original.current||original.pending.length&&!retry)return;
   const client=retry?.clientMessageId||crypto.randomUUID();submittedSequence.current=editSequence.current;setUnknownClient(client);
   try{
    const result=await bridge.command('ownerWork.supplement.send',{...binding(target),key:original.key,revision:original.revision,clientMessageId:client});
    const next=apply(result,false);setReceipt(next.receipt);
    setUnknownClient(['accepted','queued','consumed','withdrawn','rejected','not_sent'].includes(String(next.receipt?.status))?null:next.pending.some(row=>row.clientMessageId===client)?client:null);
   }catch(failure){
    notifyError(failureText(failure));
    if((failure as {code?:string})?.code==='not_accepted')setUnknownClient(null);
    await read(editSequence.current!==submittedSequence.current);
   }
  }finally{actionFlight.current=false;if(mounted.current)setBusy(false)}
 };
 const query=async(client:string)=>{
  if(actionFlight.current||!can('ownerWork.supplement.query')||!live.current.draft)return;
  actionFlight.current=true;setBusy(true);setError('');
  const original=live.current.draft,dirty=!sameContent(live.current,original)&&editSequence.current!==submittedSequence.current;
  try{
   const next=apply(await bridge.command('ownerWork.supplement.query',{...binding(target),key:original.key,clientMessageId:client}),dirty);
   setReceipt(next.receipt);setUnknownClient(next.pending.some(row=>row.clientMessageId===client)?client:null);
  }catch(failure){notifyError(failureText(failure));await read(true)}
  finally{actionFlight.current=false;if(mounted.current)setBusy(false)}
 };
 const close=async()=>{if(await flush())onClose()};
 const dirty=!!draft&&!sameContent({text,mode},draft),pending=draft?.pending||[];
 const unknownOutsideList=unknownClient&&!pending.some(row=>row.clientMessageId===unknownClient)?unknownClient:null;
 const canSend=can('ownerWork.supplement.send')&&!!draft&&accepts&&draft.current&&(mode==='queue'?draft.canQueue:draft.canSteer)&&!pending.length&&!unknownClient&&!loading&&!busy&&!!text.trim();
 return <section className="pw-work-supplement" aria-label={`补充给：${target.goal}`}>
  <header className="pw-work-supplement-heading"><div><small>补充给这件工作</small><h3>{target.goal}</h3></div><div className="pw-action-row"><IconButton icon={IconRefresh} aria-label="重新读取原工作补充" disabled={loading||busy||saving} onClick={()=>void refresh()}/><IconButton icon={IconClose} aria-label="收起补充，保留草稿" disabled={saving} onClick={()=>void close()}/></div></header>
  {loading&&!draft?<ThinkingLoader phase="正在读取这件工作的独立草稿"/>:draft?<>
   <p className="pw-muted">{!current?'工作已进入另一轮；这份草稿仍属于原执行，请核对后决定。':draft.reason}</p>
   {!!draft.otherDrafts?.length&&<div className="pw-work-pending-links" aria-label="保留的原执行草稿">{draft.otherDrafts.filter(row=>row.taskId===target.taskId&&row.conversationId===target.conversationId&&typeof row.runId==='string'&&Number.isSafeInteger(row.epoch)).map(row=><Button key={row.key} variant="ghost" size="small" disabled={busy||saving} onClick={()=>onSelectOriginal({taskId:row.taskId,runId:row.runId,epoch:row.epoch})}>查看另一轮保留的{row.hasText?'补充草稿':'待核对输入'}</Button>)}</div>}
   <TextAreaField ref={input} label="补充内容" value={text} onChange={value=>edit(value)} placeholder="补充要求、调整重点或提供新的信息…" hint="文字单独保存；发送后会进入这件工作的原话题。" rows={3} autoResize maxRows={8} maxLength={12000} isDisabled={busy||loading}/>
   <RadioOptions label="采用时机" value={mode} onChange={value=>edit(live.current.text,value as 'queue'|'steer')} isDisabled={busy||loading} options={[{id:'queue',label:'本轮结束后处理'},{id:'steer',label:'下个安全步骤加入'}]}/>
   <div className="pw-work-supplement-footer"><span role="status">{saving?'正在保存草稿':dirty?'本页修改尚未保存':'草稿已保留'}</span><div className="pw-action-row">{dirty&&<Button variant="ghost" size="small" disabled={saving||busy} onClick={()=>void flush(true)}>保存本页补充</Button>}<Button variant="primary" disabled={!canSend} onClick={()=>void submit()}>{busy?'正在核对回执':'发送到这件工作'}</Button></div></div>
   {pending.map(row=><div className="pw-work-supplement-receipt" key={row.clientMessageId}><p role="status">{receiptLabel(row.receipt)||'这条补充的接收结果仍待核对。'}</p><div className="pw-action-row"><Button variant="secondary" size="small" disabled={busy||saving} onClick={()=>void query(row.clientMessageId)}>核对原补充回执</Button>{row.canRetryLocallyUnsent===true&&<Button variant="ghost" size="small" disabled={busy||saving||dirty||!accepts} onClick={()=>void submit(row)}>重新保存并发送原补充</Button>}</div></div>)}
   {unknownOutsideList&&<div className="pw-work-supplement-receipt"><p role="status">这次补充的结果尚未确认，原输入身份仍保留。</p><Button variant="secondary" size="small" disabled={busy||saving} onClick={()=>void query(unknownOutsideList)}>查询这次原补充</Button></div>}
   {receipt&&!pending.length&&<MotionFeedback identity={`${receipt.clientMessageId}:${receipt.status}`} className="pw-muted" role="status">{receiptLabel(receipt)}</MotionFeedback>}
  </>:null}
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {(!draft||error||!current)&&<Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={loading||busy||saving} onClick={()=>void refresh()}>重新读取原草稿</Button>}
 </section>;
}
