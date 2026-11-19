import './blank-phone-starts.css';
import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {firstRecordLabel,phoneStartReceipt,type BlankFirstRecord,type PhoneStartReceipt} from './phone-start';

export type BlankFirstRun={draftRequestId:string;runId:string;epoch:1};
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(v);
function firstRun(v:any):BlankFirstRun|undefined{
  if(!v||!uuid(v.draftRequestId)||v.taskId!==v.draftRequestId||!uuid(v.runId)||v.epoch!==1)return;
  return {draftRequestId:v.taskId,runId:v.runId,epoch:1};
}
const admissions={admitted:'Android 已接纳首次服务请求',service_denied:'Android 已拒绝首次服务启动',
  component_unconfirmed:'系统没有返回原服务组件',run_recorded:'有首轮登记，服务是否接纳待核对',
  no_run:'只有任务，没有首轮登记',unconfirmed:'首次准入暂不能核实'} as const;
type Original={id:string;goal:string;at:number;first?:BlankFirstRun;record?:BlankFirstRecord;admission:keyof typeof admissions};
const when=(n:number)=>new Date(n).toLocaleString(undefined,{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
function FirstRecord({record}:{record:BlankFirstRecord}){
  return <div className="pw-draft-first-status"><span>首轮记录：<strong>{firstRecordLabel(record)}</strong></span>
    <small>{when(record.updatedAt)} 更新</small></div>;
}
function firstDetails(row:any):Pick<Original,'admission'|'first'|'record'>{
  const receipt=phoneStartReceipt(row),admission=receipt.admissionState??'unconfirmed';
  const first=admission==='no_run'||admission==='unconfirmed'?undefined:firstRun(row);
  return {admission,first,record:first?receipt.firstRecord:undefined};
}
type Rows={owner:string;document:number;rows:Original[]};
function originals(value:unknown):Original[]{
  if(!Array.isArray(value))return [];
  return value.slice(0,5).flatMap(row=>{
    if(!row||row.accepted!==true||typeof row.draftRequestId!=='string'||row.taskId!==row.draftRequestId||
      typeof row.goal!=='string'||!Number.isSafeInteger(row.createdAt)||row.createdAt<1)return [];
    return [{id:row.taskId,goal:row.goal,at:row.createdAt,...firstDetails(row)}];
  });
}

/** A private SQLite receipt lookup, not a device observer. An empty first-use composer stays empty. */
export function EarlierDraftPhoneStarts({composer,except,onTask}:{composer:string;except?:string;onTask?:(first:BlankFirstRun)=>void}){
  const [saved,setSaved]=useState<Rows|null>(null),[ids,setIds]=useState<string[]>([]),[open,setOpen]=useState(false),[busy,setBusy]=useState(false),[note,setNote]=useState('');
  const serial=useRef(0),doc=bridge.getDocumentEpoch();
  const here=()=>{const view=bridge.getState(),phone=view.phoneDraft as {channelOrigin?:boolean;phone?:boolean}|undefined;
    return !document.hidden&&bridge.getDocumentEpoch()===doc&&view.composer?.key===composer&&!view.state?.conversationId&&!phone?.channelOrigin&&phone?.phone===true};
  useEffect(()=>{
    let alive=true;const read=()=>{const ticket=++serial.current;setSaved(null);setIds([]);setNote('');setOpen(false);setBusy(false);if(!here())return;
      // First paint reads at most five opaque IDs, not task documents, goal text or history.
      void bridge.command('phone.draft.recentStarts',{composer}).then(result=>{
        if(alive&&ticket===serial.current&&here()&&result?.composer===composer&&Array.isArray(result.rows))
          setIds(result.rows.filter((id:unknown):id is string=>typeof id==='string'&&id!==except));
      }).catch(()=>{});
    };
    read();document.addEventListener('visibilitychange',read);
    return()=>{alive=false;++serial.current;document.removeEventListener('visibilitychange',read)};
  },[composer,doc,except]);
  const load=(id?:string)=>{if(!here()||busy)return;const ticket=++serial.current;setBusy(true);setNote('');setOpen(true);
    void bridge.command('phone.draft.recentStarts',id?{composer,draftRequestId:id}:{composer,details:true}).then(result=>{
      if(ticket!==serial.current||!here())return;
      if(id){
        if(result?.accepted!==true||result.draftRequestId!==id||result.taskId!==id||!ids.includes(id)){setNote('这项原建单记录已不能核对，没有打开其他任务。');return}
        const {admission,first,record}=firstDetails(result);
        setSaved(old=>old?.owner===composer&&old.document===doc?{...old,rows:old.rows.map(row=>row.id===id?{...row,first,admission,record}:row)}:old);
        setNote(first?'已核对原草稿的首次轮次。':'已核对建单记录，暂时没有可打开的首次轮次。');return
      }
      if(result?.composer!==composer)return;
      const found=originals(result.rows).filter(row=>ids.includes(row.id)&&row.id!==except);setSaved({owner:composer,document:doc,rows:found});if(!found.length)setNote('当前没有仍可核对的原任务。');
    }).catch(()=>{if(ticket===serial.current&&here())setNote('没有收到这份草稿的记录，可重新核对。')})
    .finally(()=>{if(ticket===serial.current)setBusy(false)});
  };
  const rows=saved?.owner===composer&&saved.document===doc?saved.rows:[];
  if(!ids.length)return null;
  return <section className="pw-input-receipt" aria-label="本草稿原先的手机任务">
    <Button size="small" variant="ghost" aria-expanded={open} onClick={()=>{if(!here())return;if(open){++serial.current;setBusy(false);setOpen(false);setSaved(null);setNote('')}else load()}}>本草稿最近的手机任务 · {ids.length}</Button>
    {open&&<>{busy&&<small role="status">正在核对原记录…</small>}{note&&<p role="status">{note} <Button size="small" variant="ghost" disabled={busy} onClick={()=>load()}>再查一次</Button></p>}
      {!!rows.length&&<ul className="pw-draft-start-list">{rows.map(row=><li key={row.id}><div><strong>{row.goal}</strong>
      {row.record&&<FirstRecord record={row.record}/>}
      <small>{row.record?'首次启动：':when(row.at)+' · '}{admissions[row.admission]}</small></div>
      {row.first&&onTask?<Button size="small" variant="secondary" disabled={busy} onClick={()=>{if(here()&&row.first)onTask(row.first)}}>查看首次记录</Button>:
        <Button size="small" variant="ghost" disabled={busy} onClick={()=>load(row.id)}>核对首轮登记</Button>}</li>)}</ul>}
      {!!rows.length&&!note&&<Button size="small" variant="ghost" disabled={busy} onClick={()=>load()}>重新核对这些首轮记录</Button>}</>}
  </section>;
}

export function BlankPhoneReceiptActions({receipt,pending,onCheck,onCloseWait,onTask}:{receipt:PhoneStartReceipt;pending:boolean;onCheck?:()=>void;onCloseWait?:()=>void;onTask?:(first:BlankFirstRun)=>void}){
  if(!receipt.draftRequestId)return null;
  if(receipt.status==='accepted'&&receipt.taskId===receipt.draftRequestId){
    const first=receipt.admissionState==='no_run'||receipt.admissionState==='unconfirmed'?undefined:firstRun(receipt);
    if(first)return <div className="pw-draft-first-return">
      {receipt.firstRecord?<FirstRecord record={receipt.firstRecord}/>:receipt.admissionState==='admitted'&&<small>Android 已接纳首次启动；这不是任务完成回执。</small>}
      {receipt.firstReadUnconfirmed&&<small role="status">本次没有取得新的首轮进展。</small>}
      <div className="pw-draft-start-actions">{onTask&&<Button size="small" variant="secondary" disabled={pending} onClick={()=>onTask(first)}>打开这轮原记录</Button>}
      {onCheck&&<Button size="small" variant="ghost" disabled={pending} onClick={onCheck}>{pending?'正在核对…':receipt.firstRecord?'重新核对首轮进展':'查询首轮进展'}</Button>}</div>
    </div>;
    return onCheck?<Button size="small" variant="ghost" disabled={pending} onClick={onCheck}>核对首轮登记</Button>:null;
  }
  if(receipt.status!=='unknown')return null;
  return <div className="pw-draft-start-actions">{onCheck&&<Button size="small" variant="secondary" disabled={pending} onClick={onCheck}>查这次启动</Button>}
    {receipt.canRetire&&onCloseWait&&<Button size="small" variant="ghost" disabled={pending} onClick={onCloseWait}>关闭未建成的请求，保留草稿</Button>}</div>;
}
