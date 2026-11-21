import {useEffect,useId,useLayoutEffect,useRef,useState,useSyncExternalStore} from 'react';
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
type SentVisit={ticket:Ticket;page:Page;viewport:HTMLElement|null;inset:number};
type SentRead={visit:SentVisit;text?:string;error?:string};

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
  const sentVisit=useRef<SentVisit|null>(null),returnFocus=useRef<SentVisit|null>(null);
  const sentTrigger=useRef<HTMLButtonElement>(null),sentHeading=useRef<HTMLHeadingElement>(null);
  const [sent,setSent]=useState<SentRead|null>(null);
  function current(t:Ticket|null){
    const s=bridge.getState(),v=s.view as {taskId?:string;section?:string;resumeRevision?:number}|undefined;
    if(!t||ticket.current!==t||document.hidden||v?.section||v?.taskId!==target.taskId||
       bridge.getDocumentEpoch()!==t.doc||v.resumeRevision!==t.resume||s.state.traceSourceRevision!==t.changes||
       !s.capabilities.commands.includes(command))return false;
    return sameOriginal(shownOriginal(s.state.tasks,target.taskId),target);
  }
  const valid=current(ticket.current),reading=usePhoneOriginalReadingPlace(ticket.current,open&&valid,page?.offset,()=>current(ticket.current));
  function close(focus=false){
    sentVisit.current=null;returnFocus.current=null;setSent(null);
    ++serial.current;ticket.current=null;inFlight.current=false;previous.current=[];setOpen(false);setBusy(false);setPage(null);setError('');
    if(focus&&!document.hidden)trigger.current?.focus({preventScroll:true});
  }
  function returnFromSent(){
    const visit=sentVisit.current;
    if(!visit)return false;
    ++serial.current;inFlight.current=false;sentVisit.current=null;
    returnFocus.current=current(visit.ticket)?visit:null;setSent(null);
    return true;
  }
  async function readSent(original:Page,button:HTMLButtonElement){
    const t=ticket.current;
    if(!t||!current(t)||!digest(t.digest)||t.messageId!==original.link.messageId||inFlight.current||sentVisit.current)return;
    const viewport=button.closest<HTMLElement>('.pw-drawer-body');
    const visit={ticket:t,page:original,viewport,inset:viewport?button.getBoundingClientRect().top-viewport.getBoundingClientRect().top:0};
    const attempt=++serial.current;inFlight.current=true;sentVisit.current=visit;setSent({visit});
    const stillOpen=()=>attempt===serial.current&&sentVisit.current===visit&&current(t);
    try{
      const {link}=original;
      const value=await bridge.command(command,{taskId:target.taskId,runId:target.runId,epoch:target.epoch,expectedDigest:t.digest,publishedMessage:true});
      if(!stillOpen())return;
      if(!value||value.format!=='pocketworld.phone-published-message.v1'||value.taskId!==target.taskId||value.runId!==target.runId||
         value.epoch!==target.epoch||value.conversationId!==link.conversationId||value.messageId!==link.messageId||value.sourceRevision!==1||
         value.deliveryId!==link.result.deliveryId||value.resultDigest!==t.digest||value.summaryTruncated!==link.result.summaryTruncated||
         value.executionAuthorized!==false||value.resultVerified!==false||typeof value.text!=='string'||!value.text.trim()||value.text.length>24000)
        throw Error('没有核对到原对话里的这条回执，未用其他内容替代。');
      setSent({visit,text:value.text});
    }catch(e){if(stillOpen())setSent({visit,error:e instanceof Error?e.message:'原对话里的这条回执暂不可读。'});}
    finally{if(attempt===serial.current)inFlight.current=false;}
  }
  useLayoutEffect(()=>{
    if(sentVisit.current&&sent?.visit===sentVisit.current&&current(sent.visit.ticket)){
      sentHeading.current?.focus({preventScroll:true});
      sentHeading.current?.scrollIntoView({block:'nearest'});
      return;
    }
    const visit=returnFocus.current;
    returnFocus.current=null;
    const button=sentTrigger.current;
    if(!visit||visit.page!==page||!current(visit.ticket)||!button?.isConnected||button.closest('[hidden],[inert]')||
       document.activeElement!==document.body&&document.activeElement!==document.documentElement)return;
    const host=visit.viewport;
    if(host?.contains(button))host.scrollTop+=button.getBoundingClientRect().top-host.getBoundingClientRect().top-visit.inset;
    button.focus({preventScroll:true});
  },[sent?.visit,page,valid]);
  async function read(t:Ticket,offset:number,manual=true){
    if(inFlight.current||sentVisit.current||!current(t))return;
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
    const back=registerOverlayBack(()=>{if(!returnFromSent())close(true)});
    return ()=>{document.removeEventListener('visibilitychange',hidden);back();};
  },[open]);
  useEffect(()=>()=>{++serial.current;ticket.current=null;inFlight.current=false;},[]);
  const showLocation=!!page&&page.total>0&&location?.available(page.link)===true;
  const shownSent=sent?.visit===sentVisit.current?sent:null;
  return <div>
    <Button ref={trigger} variant="ghost" className="h-auto min-h-11 max-w-full whitespace-normal"
      aria-expanded={open&&valid} aria-controls={region} onClick={()=>open?close(true):enter()}>{open&&valid?'收起原回执':`查看第 ${target.epoch} 轮原回执`}</Button>
    {open&&valid&&<section id={region} aria-label="这轮手机工作的原回执">
      {shownSent?<section aria-label="原对话收到的回执">
        <div className="pw-section-heading">
          <h3 ref={sentHeading} tabIndex={-1}>原对话收到的回执</h3>
          <Button variant="ghost" onClick={returnFromSent}>返回本轮工作回执</Button>
        </div>
        <p className="pw-field-hint">{shownSent.visit.page.link.result.summaryTruncated?'对话里保存的是当时发出的节选，完整文字仍在本轮工作回执。':'显示的是当时实际保存到原私人对话的文字。'}</p>
        {shownSent.text?<p className="pw-phone-report-text" dir="auto">{shownSent.text}</p>:
          shownSent.error?<p role="alert">{shownSent.error}</p>:<p role="status">正在核对这条原消息…</p>}
      </section>:<>
      {busy&&<p role="status">正在核对已保存的原回执…</p>}{error&&<p role="alert">{error}</p>}
      {page&&<><p className="pw-field-hint">当时的记录：{labels[page.status]}。这是原轮次的文字回执，不是当前画面或目标达成的凭据。</p>
        {page.note&&<p className="pw-field-hint">{page.note}</p>}
        {page.total===0?<p>这轮没有留下文字总结。</p>:<p ref={reading.body} tabIndex={-1} className="pw-phone-report-text" dir="auto">{page.text}</p>}
        {page.total>page.text.length&&<p className="pw-muted">原文位置 {page.offset+1}–{page.next} / {page.total}</p>}</>}
      {(error||page)&&<div className="pw-action-row">
        {page&&<Button ref={sentTrigger} variant="ghost" onClick={event=>{
          if(!current(ticket.current))return;
          if(showLocation){if(location?.available(page.link))location.open(page.link,event.currentTarget);}
          else void readSent(page,event.currentTarget);
        }}>{showLocation?'在原对话查看这条回执':'查看已发到原对话的消息'}</Button>}
        {page&&previous.current.length>1&&<Button variant="ghost" onClick={()=>ticket.current&&void read(ticket.current,previous.current[previous.current.length-2])}>上一段</Button>}
        {page?.more&&<Button variant="secondary" onClick={()=>ticket.current&&void read(ticket.current,page.next)}>下一段原文</Button>}
        {error&&<Button variant="secondary" onClick={()=>ticket.current&&void read(ticket.current,wanted.current)}>重读这一段</Button>}
      </div>}</>}
    </section>}
  </div>;
}

/** Read the immutable local report without looking for a bubble in a bounded conversation page. */
export function PhoneTaskOriginalReport({task,location}:{task:Row;location?:PhoneWorkReportLocation}){
  const target=original(task);
  return target?<OriginalPhoneReport key={JSON.stringify(target)} target={target} location={location}/>:null;
}
