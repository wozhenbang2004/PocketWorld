import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, CheckboxField } from './forms-library';
import { WorkingStatus } from './library/journey-motion';

const contract='pocketworld.original-phone-allocation.owner-reviewed-text.v1';
type Data=Record<string,any>;
type Pin={taskId:string;runId:string;epoch:number;operationId:string;sourceDeviceId:string;recordedAt:number};
type Visit={pin:Pin;request:string;document:number;resume:number};
type Review={visit:Visit;handle:string;digest:string;summary:string};
const token=(n:unknown):n is string=>typeof n==='string'&&/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(n);
const device=(n:unknown):n is string=>typeof n==='string'&&/^pw1_[a-f0-9]{64}$/.test(n);
const positive=(n:unknown):n is number=>Number.isSafeInteger(n)&&(n as number)>0;
const command={read:'receivedPhone.result.read',confirm:'receivedPhone.result.confirm',close:'receivedPhone.result.close'};

function original(task:Data|undefined) {
  const raw=task?.raw,o=raw?.origin,id=task?.id,runId=raw?.runId,epoch=raw?.coordinatorEpoch,source=o?.callerDeviceId;
  if(!token(id)||raw?.id!==id||!token(runId)||!positive(epoch)||!device(source)||!device(o.targetDeviceId)||source===o.targetDeviceId||
    raw.actorId!==`paired_device:${source}`||raw.spaceId!==`device:${source}`||raw.kind!=='assistant'||raw.executionMode!=='capabilities'||
    o.kind!=='paired_device'||o.textReturnContract!==contract||o.originDeviceId!==source||!token(o.remoteOperationId)||
    raw.contextWithdrawn===true||raw.contextAvailable===false||raw.sourceCurrentReadRequired===true||raw.taskProjection==='source_unavailable')return;
  return {taskId:id,runId,epoch,operationId:o.remoteOperationId,sourceDeviceId:source};
}
function pending(task:Data|undefined):Pin|undefined {
  const id=original(task), raw=task?.raw;
  if(!id||task?.status!=='needs_confirmation'||raw?.status!==task.status||raw.cancelRequested!==false)return;
  const value=raw.deviceGoalResult, slot=raw.receivedPhoneResultSlot;
  if(value){if(value.runId!==id.runId||value.executorEpoch!==id.epoch||value.status!=='needs_confirmation'||
    value.closed!==false||value.nativeRunnerExited!==true||value.nativeDispatchClosed!==true||!positive(value.recordedAt))return;
    return {...id,recordedAt:value.recordedAt};}
  if(slot?.runId===id.runId&&slot.epoch===id.epoch&&positive(slot.recordedAt))return {...id,recordedAt:slot.recordedAt};
}
const matches=(left:Partial<Pin>|undefined,right:Pin)=>!!left&&left.taskId===right.taskId&&left.runId===right.runId&&left.epoch===right.epoch&&
  left.operationId===right.operationId&&left.sourceDeviceId===right.sourceDeviceId;

export function OriginalReceivedTextResult({task,active,registerBack}:{
  task:Data;active:boolean;registerBack:(back:(()=>boolean)|null)=>void;
}) {
  const pin=pending(task);
  const [review,setReview]=useState<Review>();
  const [message,setMessage]=useState('');
  const [ack,acknowledge]=useState(false);
  const [progress,setProgress]=useState<'reading'|'saving'|''>('');
  const visit=useRef<Visit|undefined>(undefined), saving=useRef<Visit|undefined>(undefined), available=useRef(active);
  const trigger=useRef<HTMLButtonElement>(null), title=useRef<HTMLHeadingElement>(null);
  available.current=active;
  const current=(selected:Visit,requirePending:boolean)=>{
    const latest=bridge.getState(), v=latest.view as Data|undefined;
    if(!v||!available.current||document.hidden||bridge.getDocumentEpoch()!==selected.document||v.resumeRevision!==selected.resume||
      v.section!==null||v.taskId!==selected.pin.taskId||latest.state.conversationReader||latest.state.queuedInputsPage||
      latest.state.attachmentWorkspace?.open||latest.state.chatAttachmentPickerOpen||latest.state.filePath)return false;
    const rows=Array.isArray(latest.state.tasks)?latest.state.tasks.filter((row:Data)=>row.id===selected.pin.taskId):[];
    const task=rows.length===1?rows[0]:undefined;
    if(!requirePending)return matches(original(task),selected.pin);
    const observed=pending(task);
    return matches(observed,selected.pin)&&observed?.recordedAt===selected.pin.recordedAt;
  };
  const retire=()=>{
    const old=visit.current;
    visit.current=undefined; saving.current=undefined;
    if(old)void bridge.command(command.close,{clientRequest:old.request}).catch(()=>{});
  };
  const close=(focus=false)=>{
    retire(); setReview(undefined); acknowledge(false); setProgress(''); setMessage('');
    if(focus)requestAnimationFrame(()=>{if(available.current&&trigger.current?.isConnected)trigger.current.focus({preventScroll:true});});
  };
  useLayoutEffect(()=>{
    if(!active&&(visit.current||review))close();
  },[active,review]);
  useEffect(()=>{
    const check=()=>{const old=visit.current;if(old&&(!current(old,false)||!saving.current&&!current(old,true)))close();};
    const stop=bridge.subscribe(check),stopDocument=bridge.subscribeDocumentEpoch(check);
    document.addEventListener('visibilitychange',check);
    return ()=>{stop();stopDocument();document.removeEventListener('visibilitychange',check);retire();};
  },[]);
  useLayoutEffect(()=>{
    if(!active||!progress&&!review)return;
    registerBack(()=>{if(!saving.current)close(true);return true;});
    return ()=>registerBack(null);
  },[active,progress,review,registerBack]);
  useLayoutEffect(()=>{if(review&&current(review.visit,true))title.current?.focus({preventScroll:true});},[review]);

  const read=()=>{
    const v=bridge.getState().view as Data|undefined;
    if(!pin||visit.current||!Number.isSafeInteger(v?.resumeRevision))return;
    const request=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');
    const selected={pin,request,document:bridge.getDocumentEpoch(),resume:v!.resumeRevision};
    if(!current(selected,true))return;
    visit.current=selected;setMessage('');acknowledge(false);setProgress('reading');
    const {taskId,runId,epoch,operationId,recordedAt}=pin;
    void bridge.command<Data>(command.read,{taskId,runId,epoch,operationId,recordedAt,clientRequest:request}).then(value=>{
      if(visit.current!==selected||!current(selected,true)){if(visit.current===selected)close();return;}
      if(value?.kind!=='receivedPhoneResultReview'||value.readOnly!==true||!matches(value,pin)||value.recordedAt!==recordedAt||!token(value.handle)||
        !/^[a-f0-9]{64}$/.test(value.resultDigest||'')||value.canKeepOriginalText!==true||typeof value.summary!=='string'||value.summary.length>24_000)
        throw new Error('本机没有核对到这份原成果。');
      setReview({visit:selected,handle:value.handle,digest:value.resultDigest,summary:value.summary});setProgress('');
    }).catch(error=>{
      if(visit.current===selected&&current(selected,false)){close();setMessage(error instanceof Error?error.message:'未读取本机这次成果。');}
    });
  };
  const decide=(completed:boolean,keepTextLocal:boolean)=>{
    if(!ack||progress||saving.current||!review||visit.current!==review.visit||!current(review.visit,true))return;
    const {visit:selected,handle,digest}=review;
    // Prevent a second click synchronously; the native review is also single-use.
    saving.current=selected;setProgress('saving');
    void bridge.command<Data>(command.confirm,{handle,acknowledged:true,completed,keepTextLocal}).then(value=>{
      if(visit.current!==selected||!current(selected,false))return;
      if(value?.kind!=='receivedPhoneResultDecision'||value.handle!==handle||value.resultDigest!==digest||!matches(value,selected.pin)||
         value.recordedAt!==selected.pin.recordedAt||!positive(value.reviewedAt)||value.reviewedAt<selected.pin.recordedAt||
         value.decisionRecorded!==true||value.completed!==completed||value.keepTextLocal!==keepTextLocal||value.sentToPeer!==false)
        throw new Error('原决定尚未核对到保存，勿重复确认。');
      close();
      setMessage(!completed?'已保存：本次不确认完成。没有向发起设备发送文字。':keepTextLocal?
        '已确认完成。文字仍在本机，原设备只能按已有权限查看状态。':'已确认完成。原设备可按已有权限另行读取摘要，本次没有发送。');
    }).catch(error=>{
      if(visit.current===selected&&current(selected,false)){close();setMessage(error instanceof Error?error.message:'未核对到原决定，请查看这份原工作。');}
    });
  };

  const visible=active&&review&&current(review.visit,true);
  if(!active||!pin&&!message&&progress!=='saving')return null;
  return <section className="pw-outcome-document" aria-label="这次原文字成果">
    {visible?<div>
      <h3 ref={title} tabIndex={-1}>核对这次文字成果</h3>
      {review.summary.trim()?<p className="pw-source-copy" dir="auto" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{review.summary}</p>:<p>这次没有可返回的文字摘要。</p>}
      <p>来自本机这次原执行。发起设备须按已有权限另行读取，确认不会主动向它发送。</p>
      <CheckboxField isSelected={ack} onChange={acknowledge} isDisabled={!!progress}>我已核对这次原成果</CheckboxField>
      <div className="pw-form-actions">
        <Button variant="primary" disabled={!ack||!!progress} onClick={()=>decide(true,false)}>确认完成，允许读取摘要</Button>
        <Button variant="secondary" disabled={!ack||!!progress} onClick={()=>decide(true,true)}>确认完成，文字留在本机</Button>
        <Button variant="ghost" disabled={!ack||!!progress} onClick={()=>decide(false,false)}>结束本轮，不确认完成</Button>
        <Button variant="ghost" disabled={progress==='saving'} onClick={()=>close(true)}>返回原工作</Button>
      </div>
    </div>:pin&&!progress&&<Button ref={trigger} variant="secondary" onClick={read}>核对这次文字成果</Button>}
    <WorkingStatus active={!!progress}>{progress==='saving'?'正在核对本机保存决定…':'正在读取本机原成果…'}</WorkingStatus>
    {message&&<p role="status">{message}</p>}
  </section>;
}
