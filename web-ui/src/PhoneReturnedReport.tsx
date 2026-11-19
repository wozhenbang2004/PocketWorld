import {useEffect,useRef,useState,useSyncExternalStore} from 'react';
import {bridge} from './bridge';
import {Drawer} from './shell-library';
import {registerOverlayBack} from './overlay-back';
import {Button,IconButton} from './forms-library';
import {IconClose} from './icons';
import {ShimmerText} from './library';
import {messagePhoneResult,type PhoneResult} from './phone-start';
import {finalScreenLine} from './PhoneFinalScreenReceipt';
import './phone-returned-report.css';

type WindowView={resumeRevision?:number;section?:string|null;taskId?:string|null};
export type PhoneReportTarget={conversationId:string;messageId:string;sourceRevision:number;phoneResult:PhoneResult;
  readerWindow:number;documentEpoch:number;resume:number;changes:number;returnFocus:HTMLElement;noticeOrigin?:string};
const view=()=>(bridge.getState().view||{}) as WindowView;
export function phoneReportTarget(conversationId:string,messageId:string,sourceRevision:number,phoneResult:PhoneResult,returnFocus:HTMLElement,readerWindow=0):PhoneReportTarget{
  return {conversationId,messageId,sourceRevision,phoneResult,readerWindow,documentEpoch:bridge.getDocumentEpoch(),resume:Number(view().resumeRevision),
    changes:Number(bridge.getState().state.traceSourceRevision),returnFocus};
}
type NoticeSource={token:string;taskId:string;runId:string;epoch:number;status:string};
type ShownReply={conversationId:string;messageId:string;sourceRevision:number;readerWindow:number;result:PhoneResult};
/** Join only already-present private receipt markers. No query for another chat or older messages. */
export function shownPhoneReply(n:NoticeSource):ShownReply|null{
  const {state,capabilities}=bridge.getState(),v=view(),note=state.originalPhoneNotification,h=state.conversationReader;
  if(document.hidden||v.section||v.taskId||!capabilities.commands.includes('phone.result.original')||
    note?.noticeHandle!==n.token||note.taskId!==n.taskId||note.runId!==n.runId||note.epoch!==n.epoch||note.status!==n.status)return null;
  if(h&&(!Number.isSafeInteger(h.window)||h.window<1||h.loading||h.error||h.limited))return null;
  const conversationId=h?h.conversation:state.conversationId,rows=h?h.messages:state.messages;
  if(typeof conversationId!=='string'||!conversationId||!Array.isArray(rows))return null;
  let found:ShownReply|null=null;
  for(const row of rows){
    if(typeof row?.id!=='string'||!row.id||row.sourceRevision!==1||row.taskId!==n.taskId)continue;
    const r=messagePhoneResult(row);
    if(!r||r.taskId!==n.taskId||r.runId!==n.runId||r.epoch!==n.epoch||r.status!==n.status)continue;
    if(found)return null;
    found={conversationId,messageId:row.id,sourceRevision:row.sourceRevision,readerWindow:h?h.window:0,result:r};
  }
  return found;
}
function current(t:PhoneReportTarget){
  const {state,capabilities}=bridge.getState(),v=view(),f=t.phoneResult;
  if(document.hidden||!capabilities.commands.includes('phone.result.original')||v.section||v.taskId||bridge.getDocumentEpoch()!==t.documentEpoch
    ||v.resumeRevision!==t.resume||state.traceSourceRevision!==t.changes||t.noticeOrigin&&state.originalPhoneNotification?.noticeHandle!==t.noticeOrigin)return false;
  if(t.noticeOrigin){
    const linked=shownPhoneReply({token:t.noticeOrigin,taskId:f.taskId,runId:f.runId,epoch:f.epoch,status:f.status});
    if(!linked||linked.messageId!==t.messageId||linked.sourceRevision!==t.sourceRevision||linked.conversationId!==t.conversationId||linked.readerWindow!==t.readerWindow)return false;
  }
  const h=state.conversationReader;
  const rows=t.readerWindow>0?(h?.window===t.readerWindow&&h.conversation===t.conversationId&&!h.loading&&!h.error?h.messages:null):
    !h&&state.conversationId===t.conversationId?state.messages:null;
  return Array.isArray(rows)&&rows.some((m:Record<string,unknown>)=>{
    if(m?.id!==t.messageId||m.sourceRevision!==t.sourceRevision)return false;
    const r=messagePhoneResult(m);return r&&r.deliveryId===f.deliveryId&&r.taskId===f.taskId&&r.runId===f.runId&&r.epoch===f.epoch
      &&r.status===f.status&&r.endedAt===f.endedAt&&r.summaryChars===f.summaryChars&&r.summaryTruncated===f.summaryTruncated;
  });
}
const states:Record<string,string>={completed:'执行结束',failed:'执行失败',cancelled:'已停止',interrupted:'已中断',
  paused:'已暂停',awaiting_user:'等待你处理',needs_confirmation:'等待确认',reconciling:'结果待核对'};
type Page={text:string;offset:number;next:number;more:boolean;finalScreen:string|null};
export function PhoneReturnedReport({target:t,onClose}:{target:PhoneReportTarget;onClose:()=>void}){
  useSyncExternalStore(bridge.subscribe,bridge.getState);
  const [expired,setExpired]=useState(false),[page,setPage]=useState<Page|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(true);
  const attempts=useRef(0),inFlight=useRef(false),digest=useRef(''),previous=useRef<number[]>([]),request=useRef(0);
  const original=t.phoneResult,valid=current(t),available=!expired&&valid;
  const Sheet=t.readerWindow>0||t.noticeOrigin?Drawer.NestedRoot:Drawer.Root;
  const close=useRef(onClose);close.current=onClose;
  useEffect(()=>registerOverlayBack(()=>close.current()),[]);
  useEffect(()=>{if(!valid){++attempts.current;inFlight.current=false;setPage(null);setExpired(true);}},[valid]);
  async function read(offset:number){
    if(inFlight.current||!current(t))return;
    const attempt=++attempts.current;inFlight.current=true;request.current=offset;setBusy(true);setPage(null);setError('');
    try{
      const p=await bridge.command('phone.result.original',{conversationId:t.conversationId,messageId:t.messageId,sourceRevision:t.sourceRevision,
        taskId:original.taskId,runId:original.runId,epoch:original.epoch,deliveryId:original.deliveryId,offset,readerWindow:t.readerWindow,expectedDigest:digest.current});
      if(attempt!==attempts.current||!current(t))return;
      if(!p||p.conversationId!==t.conversationId||p.messageId!==t.messageId||p.sourceRevision!==t.sourceRevision||p.readerWindow!==t.readerWindow
        ||p.taskId!==original.taskId||p.runId!==original.runId||p.epoch!==original.epoch||p.deliveryId!==original.deliveryId||p.status!==original.status
        ||p.endedAt!==original.endedAt||p.summaryChars!==original.summaryChars||p.summaryTruncated!==original.summaryTruncated
        ||p.executionAuthorized!==false||p.resultVerified!==false||typeof p.resultDigest!=='string'||!/^[a-f0-9]{64}$/.test(p.resultDigest)
        ||typeof p.summaryDigest!=='string'||!/^[a-f0-9]{64}$/.test(p.summaryDigest)||digest.current!==''&&p.resultDigest!==digest.current
        ||p.offset!==offset||typeof p.text!=='string'||p.text.length>8000||p.nextOffset!==offset+p.text.length
        ||p.nextOffset>original.summaryChars||p.hasMore!==(p.nextOffset<original.summaryChars)||p.hasMore&&!p.text.length)
        throw new Error('这段原文与所选回执不一致，没有改读其他结果。');
      digest.current=p.resultDigest;
      const index=previous.current.indexOf(offset);previous.current=index<0?[...previous.current,offset]:previous.current.slice(0,index+1);
      const finalScreen=finalScreenLine(p.finalScreenReceipt,original);
      setPage({text:p.text,offset,next:p.nextOffset,more:p.hasMore,finalScreen});
    }catch(e){if(attempt===attempts.current&&current(t))setError(e instanceof Error?e.message:'原文未读到，可以重读这段。');}
    finally{if(attempt===attempts.current){inFlight.current=false;setBusy(false);}}
  }
  useEffect(()=>{
    const obscure=()=>{if(document.hidden){++attempts.current;setExpired(true);setPage(null);}};
    document.addEventListener('visibilitychange',obscure);void read(0);
    return()=>{document.removeEventListener('visibilitychange',obscure);++attempts.current;inFlight.current=false;};
  },[t]);
  return <Sheet open onOpenChange={open=>{if(!open)onClose();}}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/>
    <Drawer.Content className="pw-drawer" onCloseAutoFocus={e=>{e.preventDefault();if(current(t)&&t.returnFocus.isConnected&&(t.noticeOrigin?t.returnFocus.dataset.phoneResultReturn===t.noticeOrigin:
      (t.readerWindow>0?t.returnFocus.closest<HTMLElement>('[data-reader-id]')?.dataset.readerId:t.returnFocus.closest<HTMLElement>('[data-message-id]')?.dataset.messageId)===t.messageId))t.returnFocus.focus({preventScroll:true});}}>
      <div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>这轮手机任务的原文</Drawer.Title>
        <Drawer.Description>第 {original.epoch} 轮 · {states[original.status]}。这里保留的是当时的文字总结，不会继续操作手机。</Drawer.Description></div>
        <IconButton icon={IconClose} aria-label="返回原回执" onClick={onClose}/></header>
      <div className="pw-drawer-body">{!available?<p role="status">原回执或私人窗口已变化。请返回对话，从原回执重新打开。</p>:<>
        {busy&&<ShimmerText>正在核对这轮原文…</ShimmerText>}{error&&<p role="alert">{error}</p>}
        {page&&<>{page.finalScreen?<p className="pw-field-hint"><strong>收尾时的屏幕：</strong>{page.finalScreen}</p>:
          original.status==='needs_confirmation'||original.status==='completed'?<p className="pw-muted">这份原报告没有独立的终屏回执，不能据此确认当时的页面。</p>:null}
          {original.summaryChars===0?<p>这轮没有留下文字总结。</p>:<p className="pw-phone-report-text" dir="auto">{page.text}</p>}
          {original.summaryChars>page.text.length&&<p className="pw-muted" role="status">原文位置 {page.offset+1}–{page.next} / {original.summaryChars}</p>}
          <div className="pw-action-row">
            {previous.current.length>1&&<Button variant="ghost" onClick={()=>void read(previous.current[previous.current.length-2])}>上一段</Button>}
            {page.more&&<Button variant="secondary" onClick={()=>void read(page.next)}>下一段原文</Button>}
          </div></>}
        {error&&<Button variant="secondary" onClick={()=>void read(request.current)}>重读这一段</Button>}
      </>}</div>
    </Drawer.Content></Drawer.Portal></Sheet>;
}
