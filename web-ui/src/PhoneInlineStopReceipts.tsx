import {useEffect,useRef,useState} from 'react';
import {Button,IconButton} from './forms-library';
import {IconChevronRight,IconClose,IconHistory} from './icons';

export type ShownPhoneRun={taskId:string;runId:string;epoch:number;activityHandle:string};
export type PhoneStopControlPhase='checking'|'requested'|'local_only'|'not_submitted'|'unconfirmed';
type Item=ShownPhoneRun&{scope:string;ticket:number;phase:PhoneStopControlPhase};
const matches=(a:ShownPhoneRun,b:ShownPhoneRun)=>a.taskId===b.taskId&&a.runId===b.runId&&a.epoch===b.epoch&&a.activityHandle===b.activityHandle;

export function originalPhoneStopControl(value:unknown,shown:Pick<ShownPhoneRun,'taskId'|'runId'|'epoch'>):PhoneStopControlPhase{
  if(!value||typeof value!=='object')return 'unconfirmed';
  const r=value as Record<string,unknown>;
  if(r.taskId!==shown.taskId||r.runId!==shown.runId||r.epoch!==shown.epoch||r.controlRequested!==true||
    r.budgetIncreased!==false||r.cleanupComplete!==false)return 'unconfirmed';
  if(r.stopJournal==='unconfirmed'&&r.originalControlCancelled===true&&!('newStopRequest' in r))return 'local_only';
  return !('stopJournal' in r)&&typeof r.newStopRequest==='boolean'?'requested':'unconfirmed';
}
export function phoneStopControlFailure(error:unknown):PhoneStopControlPhase{
  const code=(error as {code?:unknown}|null)?.code;
  return code==='not_accepted'||code==='not_sent'?'not_submitted':'unconfirmed';
}

// Presentation only, for direct taps in this foreground owner window. Never stores goals,
// reads a task, polls, or reissues a stop. Each button opens an already-shown read witness.
export function useInlinePhoneStops(scope:string|null,original:(target:ShownPhoneRun)=>boolean){
  const [items,setItems]=useState<Item[]>([]);
  const saved=useRef<Item[]>([]),next=useRef(0),windowNow=useRef({scope,original});
  windowNow.current={scope,original};
  const publish=(rows:Item[])=>{saved.current=rows;setItems(rows)};
  useEffect(()=>{
    const hide=()=>{if(document.hidden&&saved.current.length)publish([])};
    document.addEventListener('visibilitychange',hide);
    return()=>{document.removeEventListener('visibilitychange',hide);saved.current=[]};
  },[]);
  useEffect(()=>{
    const valid=saved.current.filter(row=>row.scope===scope&&original(row));
    if(valid.length!==saved.current.length)publish(valid);
  },[scope,original]);
  const rows=scope&&!document.hidden?items.filter(row=>row.scope===scope&&original(row)):[];
  function request(shown:ShownPhoneRun,stop:()=>Promise<unknown>){
    if(!scope||windowNow.current.scope!==scope||document.hidden||!original(shown))return false;
    const prior=saved.current.find(item=>item.scope===scope&&matches(item,shown));
    if(prior&&prior.phase!=='unconfirmed'&&prior.phase!=='not_submitted')return true;
    const item:Item={taskId:shown.taskId,runId:shown.runId,epoch:shown.epoch,activityHandle:shown.activityHandle,
      ticket:++next.current,scope,phase:'checking'};
    publish([...saved.current.filter(row=>row.scope===scope&&row!==prior).slice(-3),item]);
    const settle=(phase:PhoneStopControlPhase)=>{
      if(document.hidden||windowNow.current.scope!==scope||!windowNow.current.original(shown)||
         !saved.current.some(row=>row.ticket===item.ticket))return;
      publish(saved.current.map(row=>row.ticket===item.ticket?{...row,phase}:row));
    };
    try{void stop().then(value=>settle(originalPhoneStopControl(value,shown)),error=>settle(phoneStopControlFailure(error)))}
    catch{settle('unconfirmed')}
    return true
  }
  return {rows,request,dismiss:(ticket:number)=>publish(saved.current.filter(row=>row.ticket!==ticket))};
}

export function phoneStopReceiptPresentation(phase:PhoneStopControlPhase,recordedStatus?:string){
  const cancelled=recordedStatus==='cancelled'||recordedStatus==='canceled'||recordedStatus==='stopped';
  const title=phase==='not_submitted'?'这次未另发停手':phase==='local_only'?'原控制器已收到停手':phase==='requested'?'已请求停手':phase==='checking'?'正在核对停手回执':'没有取得停手回执';
  const note=phase==='not_submitted'?'可核对原轮；若原通知仍在，也可从那里停手。':phase==='local_only'?'账本回执、在途动作与资源清理尚未核实。':cancelled?(phase==='requested'?'任务已登记取消，动作和资源清理以原轮记录为准。':'任务已登记取消，不能据此确定是刚才点击生效。'):
    phase==='requested'?'尚未核实原轮结束或资源清理。':'可查看原记录；不会再次发送停止。';
  return {title,note};
}

export function PhoneInlineStopReceipt({item,goal,recordedStatus,onOpen,onDismiss}:{
  item:Item;goal?:string;recordedStatus?:string;onOpen?:()=>void;onDismiss:()=>void
}){
  const {title,note}=phoneStopReceiptPresentation(item.phase,recordedStatus);
  return <section className="pw-phone-activity" aria-label="这次停手回执">
    <Button variant="ghost" className="pw-phone-activity-open" disabled={!onOpen} onClick={onOpen}
      aria-label={title+'：'+(goal||'这项手机工作')+'，核对第 '+item.epoch+' 轮原记录'} title={note}>
      <span className="pw-phone-activity-mark" aria-hidden="true"><IconHistory size={18}/></span>
      <span className="pw-phone-activity-copy"><strong dir="auto" aria-live="polite">{title}{goal?' · '+goal:''}</strong>
        <span>第 {item.epoch} 轮 · {note}</span></span><IconChevronRight size={15} aria-hidden="true"/>
    </Button>
    <IconButton icon={IconClose} aria-label="收起这次停手回执" onClick={onDismiss}/>
  </section>;
}
