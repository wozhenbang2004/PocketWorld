import {useEffect,useId,useRef,useState,useSyncExternalStore} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {cancelledPhoneSummaryNote,messagePhoneResult,phoneConversationId,phoneRunIdentity,type PhoneResult} from './phone-start';
import {registerOverlayBack} from './overlay-back';
import {usePhoneOriginalReadingPlace} from './phone-original-reading-place';
import './phone-returned-report.css';

type Row=Record<string,any>;
type PhoneConversationOrigin={taskId:string;runId:string;epoch:number;conversationId:string};
type Ticket={doc:number;resume:unknown;changes:unknown;digest:string;messageId:string;summaryDigest:string};
export type DeliveredPhoneWorkReport=Readonly<{conversationId:string;messageId:string;result:PhoneResult}>;
export type PhoneWorkReportLocation={
  available:(link:DeliveredPhoneWorkReport)=>boolean;
  open:(link:DeliveredPhoneWorkReport,trigger:HTMLButtonElement)=>void;
};
type Page={offset:number;text:string;total:number;next:number;more:boolean;status:string;link:DeliveredPhoneWorkReport;note?:string};

/** The explicit original Work read has already checked the immutable publication. Here only match its native Chat row. */
export function loadedPhoneWorkReport(rows:unknown,link:DeliveredPhoneWorkReport):boolean{
  if(!Array.isArray(rows))return false;
  const matches=rows.filter(row=>row?.id===link.messageId);
  const saved=matches.length===1&&matches[0].sourceRevision===1?messagePhoneResult(matches[0]):null,r=link.result;
  return !!saved&&saved.taskId===r.taskId&&saved.runId===r.runId&&saved.epoch===r.epoch&&saved.deliveryId===r.deliveryId&&
    saved.status===r.status&&saved.endedAt===r.endedAt&&saved.summaryChars===r.summaryChars&&saved.summaryTruncated===r.summaryTruncated;
}
const command='phone.result.taskOriginal';
const labels:Record<string,string>={completed:'执行结束',needs_confirmation:'等待确认',awaiting_user:'需要你处理',
  failed:'执行失败',cancelled:'已停止',interrupted:'已中断',reconciling:'结果待核对',paused:'已暂停'};
const digest=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
function original(task:Row|undefined):PhoneConversationOrigin|null{
  if(!task||!isPrivateLocalPhone(task)||!Object.hasOwn(labels,task.status)||task.raw?.contextAvailable===false||
     task.raw?.contextWithdrawn===true||task.raw?.sourceCurrentReadRequired===true||task.raw?.taskProjection==='source_unavailable')return null;
  const conversationId=phoneConversationId(task),run=phoneRunIdentity(task);
  return conversationId&&run?{...run,conversationId}:null;
}
function sameOriginal(a:PhoneConversationOrigin|null,b:PhoneConversationOrigin){
  return a?.taskId===b.taskId&&a.runId===b.runId&&a.epoch===b.epoch&&a.conversationId===b.conversationId;
}
function shownOriginal(rows:unknown,taskId:string){
  const same=Array.isArray(rows)?rows.filter(row=>row?.id===taskId):[];
  return same.length===1?original(same[0]):null;
}
function OriginalPhoneReport({target,location}:{target:PhoneConversationOrigin;location?:PhoneWorkReportLocation}){
  useSyncExternalStore(bridge.subscribe,bridge.getState);
  const region=useId(),trigger=useRef<HTMLButtonElement>(null),ticket=useRef<Ticket|null>(null);
  const serial=useRef(0),inFlight=useRef(false),previous=useRef<number[]>([]),wanted=useRef(0);
  const [open,setOpen]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[page,setPage]=useState<Page|null>(null);
  function current(t:Ticket|null){
    const s=bridge.getState(),v=s.view as {taskId?:string;section?:string;resumeRevision?:number}|undefined;
    if(!t||ticket.current!==t||document.hidden||v?.section||v?.taskId!==target.taskId||
       bridge.getDocumentEpoch()!==t.doc||v.resumeRevision!==t.resume||s.state.traceSourceRevision!==t.changes||
       !s.capabilities.commands.includes(command))return false;
    return sameOriginal(shownOriginal(s.state.tasks,target.taskId),target);
  }
  const valid=current(ticket.current),reading=usePhoneOriginalReadingPlace(ticket.current,open&&valid,page?.offset,()=>current(ticket.current));
  function close(focus=false){
    ++serial.current;ticket.current=null;inFlight.current=false;previous.current=[];setOpen(false);setBusy(false);setPage(null);setError('');
    if(focus&&!document.hidden)trigger.current?.focus({preventScroll:true});
  }
  async function read(t:Ticket,offset:number,manual=true){
    if(inFlight.current||!current(t))return;
    if(manual)reading.leaving(offset);
    const attempt=++serial.current;inFlight.current=true;wanted.current=offset;setBusy(true);setPage(null);setError('');
    try{
      const p=await bridge.command(command,{taskId:target.taskId,runId:target.runId,epoch:target.epoch,offset,expectedDigest:t.digest});
      if(attempt!==serial.current||!current(t))return;
      if(!p||p.taskId!==target.taskId||p.runId!==target.runId||p.epoch!==target.epoch||p.conversationId!==target.conversationId||
         p.deliveryId!==`phone_handoff:${target.taskId}:${target.runId}:${target.epoch}`||typeof p.messageId!=='string'||!p.messageId||p.messageId.length>240||p.sourceRevision!==1||
         typeof p.status!=='string'||!Object.hasOwn(labels,p.status)||!Number.isSafeInteger(p.endedAt)||p.endedAt<1||
         !Number.isSafeInteger(p.summaryChars)||p.summaryChars<0||typeof p.summaryTruncated!=='boolean'||
         p.executionAuthorized!==false||p.resultVerified!==false||!digest(p.resultDigest)||!digest(p.summaryDigest)||
         t.digest&&(t.digest!==p.resultDigest||t.messageId!==p.messageId||t.summaryDigest!==p.summaryDigest)||
         p.offset!==offset||typeof p.text!=='string'||p.text.length>8000||p.nextOffset!==offset+p.text.length||
         p.nextOffset>p.summaryChars||p.hasMore!==(p.nextOffset<p.summaryChars)||p.hasMore&&!p.text.length)
        throw Error('这段文字与原轮次不一致，没有换读其他结果。');
      t.digest=p.resultDigest;t.messageId=p.messageId;t.summaryDigest=p.summaryDigest;
      const i=previous.current.indexOf(offset);previous.current=i<0?[...previous.current,offset]:previous.current.slice(0,i+1);
      const link={conversationId:target.conversationId,messageId:p.messageId,result:{taskId:target.taskId,runId:target.runId,epoch:target.epoch,
        status:p.status,deliveryId:p.deliveryId,endedAt:p.endedAt,summaryChars:p.summaryChars,summaryTruncated:p.summaryTruncated}};
      setPage({offset,text:p.text,total:p.summaryChars,next:p.nextOffset,more:p.hasMore,status:p.status,link,
        note:offset===0&&!p.hasMore?cancelledPhoneSummaryNote(p,p.text):undefined});
    }catch(e){if(attempt===serial.current&&current(t))setError(e instanceof Error?e.message:'这轮原回执暂不可读。');}
    finally{if(attempt===serial.current){inFlight.current=false;setBusy(false);}}
  }
  function enter(){
    const s=bridge.getState(),v=s.view as {resumeRevision?:number}|undefined;
    const t={doc:bridge.getDocumentEpoch(),resume:v?.resumeRevision,changes:s.state.traceSourceRevision,digest:'',messageId:'',summaryDigest:''};
    ticket.current=t;if(!current(t)){ticket.current=null;return;}
    setOpen(true);void read(t,0,false);
  }
  useEffect(()=>{if(open&&!valid)close();},[open,valid]);
  useEffect(()=>{
    if(!open)return;
    const hidden=()=>{if(document.hidden)close();};
    document.addEventListener('visibilitychange',hidden);
    const back=registerOverlayBack(()=>close(true));
    return ()=>{document.removeEventListener('visibilitychange',hidden);back();};
  },[open]);
  useEffect(()=>()=>{++serial.current;ticket.current=null;inFlight.current=false;},[]);
  const showLocation=!!page&&page.total>0&&location?.available(page.link)===true;
  return <div>
    <Button ref={trigger} variant="ghost" className="h-auto min-h-11 max-w-full whitespace-normal"
      aria-expanded={open&&valid} aria-controls={region} onClick={()=>open?close(true):enter()}>{open&&valid?'收起原回执':`查看第 ${target.epoch} 轮原回执`}</Button>
    {open&&valid&&<section id={region} aria-label="这轮手机工作的原回执">
      {busy&&<p role="status">正在核对已保存的原回执…</p>}{error&&<p role="alert">{error}</p>}
      {page&&<><p className="pw-field-hint">当时的记录：{labels[page.status]}。这是原轮次的文字回执，不是当前画面或目标达成的凭据。</p>
        {page.note&&<p className="pw-field-hint">{page.note}</p>}
        {page.total===0?<p>这轮没有留下文字总结。</p>:<p ref={reading.body} tabIndex={-1} className="pw-phone-report-text" dir="auto">{page.text}</p>}
        {page.total>page.text.length&&<p className="pw-muted">原文位置 {page.offset+1}–{page.next} / {page.total}</p>}</>}
      {(error||page&&(page.more||previous.current.length>1||showLocation))&&<div className="pw-action-row">
        {page&&location&&showLocation&&<Button variant="ghost" onClick={event=>{
          if(current(ticket.current)&&location.available(page.link))location.open(page.link,event.currentTarget);
        }}>在原对话查看这条回执</Button>}
        {page&&previous.current.length>1&&<Button variant="ghost" onClick={()=>ticket.current&&void read(ticket.current,previous.current[previous.current.length-2])}>上一段</Button>}
        {page?.more&&<Button variant="secondary" onClick={()=>ticket.current&&void read(ticket.current,page.next)}>下一段原文</Button>}
        {error&&<Button variant="secondary" onClick={()=>ticket.current&&void read(ticket.current,wanted.current)}>重读这一段</Button>}
      </div>}
    </section>}
  </div>;
}

/** Read the immutable local report without looking for a bubble in a bounded conversation page. */
export function PhoneTaskOriginalReport({task,location}:{task:Row;location?:PhoneWorkReportLocation}){
  const target=original(task);
  return target?<OriginalPhoneReport key={JSON.stringify(target)} target={target} location={location}/>:null;
}
