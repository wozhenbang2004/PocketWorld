import './blank-phone-starts.css';
import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import type {PhoneStartReceipt} from './phone-start';

type Original={id:string;goal:string;at:number;admission:'admitted'|'run_recorded'|'no_run'|'unconfirmed'};
type Rows={owner:string;document:number;rows:Original[]};
function originals(value:unknown):Original[]{
  if(!Array.isArray(value))return [];
  return value.slice(0,5).flatMap(row=>{
    if(!row||row.accepted!==true||typeof row.draftRequestId!=='string'||row.taskId!==row.draftRequestId||
      typeof row.goal!=='string'||!Number.isSafeInteger(row.createdAt)||row.createdAt<1)return [];
    return [{id:row.taskId,goal:row.goal,at:row.createdAt,admission:row.admissionState==='admitted'?'admitted':row.admissionState==='run_recorded'?'run_recorded':row.admissionState==='no_run'?'no_run':'unconfirmed'}];
  });
}

/** A private SQLite receipt lookup, not a device observer. An empty first-use composer stays empty. */
export function EarlierDraftPhoneStarts({composer,except,onTask}:{composer:string;except?:string;onTask:(id:string)=>void}){
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
      if(id){if(result?.accepted===true&&result.draftRequestId===id&&result.taskId===id&&ids.includes(id))onTask(id);else setNote('这项原任务已不能核对，没有打开其他任务。');return}
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
      <small>{new Date(row.at).toLocaleString(undefined,{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})} · {row.admission==='admitted'?'有首次准入回执':row.admission==='run_recorded'?'有首次轮次，服务接收待核对':row.admission==='no_run'?'尚无执行轮次':'首次准入待核对'}</small></div>
      <Button size="small" variant="secondary" disabled={busy} onClick={()=>load(row.id)}>打开任务</Button></li>)}</ul>}</>}
  </section>;
}

export function BlankPhoneReceiptActions({receipt,pending,onCheck,onCloseWait,onTask}:{receipt:PhoneStartReceipt;pending:boolean;onCheck?:()=>void;onCloseWait?:()=>void;onTask:(id:string)=>void}){
  if(!receipt.draftRequestId)return null;
  if(receipt.status==='accepted'&&receipt.taskId===receipt.draftRequestId)return <Button size="small" variant="secondary" onClick={()=>onTask(receipt.taskId!)}>打开本次手机任务</Button>;
  if(receipt.status!=='unknown')return null;
  return <div className="pw-draft-start-actions">{onCheck&&<Button size="small" variant="secondary" disabled={pending} onClick={onCheck}>查这次启动</Button>}
    {receipt.canRetire&&onCloseWait&&<Button size="small" variant="ghost" disabled={pending} onClick={onCloseWait}>关闭未建成的请求，保留草稿</Button>}</div>;
}
