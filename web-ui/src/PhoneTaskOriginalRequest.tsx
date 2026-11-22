import {useEffect,useRef,useState,useSyncExternalStore} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconChat} from './icons';
import {registerOverlayBack} from './overlay-back';
import {phoneConversationId} from './phone-start';
import {usePhoneOriginalReadingPlace} from './phone-original-reading-place';
import './phone-request-original.css';

type Task={id:string;raw?:Record<string,any>};
type Ticket={doc:number;resume:number;changes:number;identity:string;review:string;message:string;revision:number};
type Page={text:string;total:number;next:number;offset:number;picked:boolean};
/** Read where the original work already is. Never selects a conversation or stages anything in its composer. */
export function PhoneTaskOriginalRequest({task,active,resultCurrent}:{task:Task;active:boolean;resultCurrent?:()=>boolean}){
  useSyncExternalStore(bridge.subscribe,bridge.getState);
  const conversation=phoneConversationId(task),run=task.raw?.runId,epoch=task.raw?.coordinatorEpoch;
  const identity=JSON.stringify([task.id,run,epoch,conversation]);
  const trigger=useRef<HTMLButtonElement>(null),hold=useRef<Ticket|null>(null),history=useRef<number[]>([]),serial=useRef(0);
  const [open,setOpen]=useState(false),[busy,setBusy]=useState(false),[page,setPage]=useState<Page|null>(null),[error,setError]=useState('');
  const wanted=useRef(0),pending=useRef(false),activity=useRef({active,identity,resultCurrent});activity.current={active,identity,resultCurrent};
  function current(t:Ticket|null){
    const s=bridge.getState(),v=s.view as {section?:unknown;taskId?:string;resumeRevision?:number}|undefined;
    return !!t&&hold.current===t&&activity.current.active&&activity.current.identity===t.identity&&!document.hidden
      &&bridge.getDocumentEpoch()===t.doc&&!v?.section&&v?.taskId===task.id&&v.resumeRevision===t.resume&&s.state.traceSourceRevision===t.changes
      &&(!activity.current.resultCurrent||activity.current.resultCurrent()===true);
  }
  function close(focus=false){
    ++serial.current;hold.current=null;history.current=[];pending.current=false;setOpen(false);setPage(null);setError('');setBusy(false);
    if(focus)requestAnimationFrame(()=>{if(activity.current.active&&activity.current.identity===identity&&(!activity.current.resultCurrent||activity.current.resultCurrent()===true)&&trigger.current?.isConnected)trigger.current.focus({preventScroll:true});});
  }
  const valid=current(hold.current);
  const reading=usePhoneOriginalReadingPlace(hold.current,open&&valid,page?.offset,()=>current(hold.current));
  async function read(t:Ticket,offset:number,manual=true){
    if(pending.current||!current(t))return;
    if(manual)reading.leaving(offset);
    const attempt=++serial.current;pending.current=true;wanted.current=offset;setBusy(true);setPage(null);setError('');
    try{
      const p=await bridge.command('phone.request.original',{taskId:task.id,runId:run,epoch,offset,reviewDigest:t.review});
      if(attempt!==serial.current||!current(t))return;
      if(!p||p.taskId!==task.id||p.runId!==run||p.epoch!==epoch||p.conversationId!==conversation||typeof p.messageId!=='string'||!p.messageId
        ||!Number.isSafeInteger(p.sourceRevision)||p.sourceRevision<1||typeof p.picked!=='boolean'||p.executionAuthorized!==false
        ||typeof p.reviewDigest!=='string'||!/^[a-f0-9]{64}$/.test(p.reviewDigest)||t.review&&(t.review!==p.reviewDigest||t.message!==p.messageId||t.revision!==p.sourceRevision)
        ||typeof p.body!=='string'||p.body.length>6000||p.offset!==offset||!Number.isSafeInteger(p.totalChars)||p.totalChars<offset+p.body.length
        ||p.nextOffset!==(offset+p.body.length<p.totalChars?offset+p.body.length:-1)||p.nextOffset>=0&&p.nextOffset<=offset)
        throw new Error('这段原话不属于当时的手机任务，没有展示替代文字。');
      t.review=p.reviewDigest;t.message=p.messageId;t.revision=p.sourceRevision;
      const i=history.current.indexOf(offset);history.current=i<0?[...history.current,offset]:history.current.slice(0,i+1);
      setPage({text:p.body,offset,total:p.totalChars,next:p.nextOffset,picked:p.picked});
    }catch(e){if(attempt===serial.current&&current(t))setError(e instanceof Error?e.message:'当时的原话暂不可读。');}
    finally{if(attempt===serial.current){pending.current=false;setBusy(false);}}
  }
  function enter(){
    const s=bridge.getState();if(!active||!conversation||typeof run!=='string'||!Number.isSafeInteger(epoch)||epoch<0||(!run)!==(epoch===0))return;
    const t={doc:bridge.getDocumentEpoch(),resume:Number((s.view as {resumeRevision:number})?.resumeRevision),changes:Number(s.state.traceSourceRevision),identity,review:'',message:'',revision:0};
    hold.current=t;if(!current(t)){hold.current=null;return;}history.current=[];setOpen(true);void read(t,0,false);
  }
  useEffect(()=>{if(open&&!valid)close();},[open,valid]);
  useEffect(()=>{if(!open)return;const hidden=()=>{if(document.hidden)close();};document.addEventListener('visibilitychange',hidden);
    const remove=registerOverlayBack(()=>close(true));return()=>{document.removeEventListener('visibilitychange',hidden);remove();};},[open,identity]);
  useEffect(()=>()=>{++serial.current;hold.current=null;pending.current=false;},[]);
  return <div><Button ref={trigger} variant="ghost" leadingIcon={IconChat} disabled={!active} aria-expanded={open&&valid} onClick={()=>open?close(true):enter()}>
    {open&&valid?'收起原交代':resultCurrent?'对照当时的原话':'查看当时关联的原话'}</Button>
    {open&&valid&&<section aria-label="手机工作的原交代">{busy&&<p role="status">正在核对原消息…</p>}{error&&<p role="alert">{error}</p>}
      {page&&<><p className="pw-muted">{page.picked?'你在准备这个手机目标时选定了这条消息。':'这是创建手机任务时关联的对话消息。'}{resultCurrent?'它和下方的执行回执是两份原记录。':'上面是当时确认的任务目标。'}</p>
        {page.total===0?<p>原消息没有文字。</p>:<p ref={reading.body} tabIndex={-1} className="pw-phone-request-original" dir="auto">{page.text}</p>}
        {page.total>page.text.length&&<p className="pw-muted">原文位置 {page.offset+1}–{page.offset+page.text.length} / {page.total}</p>}
        <div className="pw-action-row">{history.current.length>1&&<Button variant="ghost" onClick={()=>hold.current&&void read(hold.current,history.current[history.current.length-2])}>上一段</Button>}
          {page.next>=0&&<Button variant="secondary" onClick={()=>hold.current&&void read(hold.current,page.next)}>下一段原话</Button>}</div></>}
      {error&&<div className="pw-action-row"><Button variant="secondary" onClick={()=>hold.current&&void read(hold.current,wanted.current)}>重读原消息</Button>
        {reading.prior!==undefined&&reading.prior!==wanted.current&&<Button variant="ghost" onClick={()=>hold.current&&void read(hold.current,reading.prior!)}>回到刚才读到的那段</Button>}</div>}
    </section>}</div>;
}
