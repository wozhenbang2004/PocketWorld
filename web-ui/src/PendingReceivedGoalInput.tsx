import { useEffect, useRef, useState } from 'react';
import { Button } from './forms-library';
import { ReceivedGoalInputReview, originalDeviceInputReady } from './ReceivedGoalInputReview';
import type { DeviceData as Data } from './devices-workspace';

// Local foreground preview only. It cannot be passed to the model-approval or task-control APIs.
export function PendingReceivedGoalInput({row,handle,here,instance,busy,active,select,read}:{
  row:Data;handle:string;here:string;instance:string;busy:boolean;active:boolean;select:()=>void;read:(done:(result:Data)=>void)=>void;
}){
  const [value,setValue]=useState<Data|null>(null),[notice,setNotice]=useState('');
  const serial=useRef(0),timer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
  const clear=()=>{++serial.current;clearTimeout(timer.current);setValue(null);setNotice('');};
  useEffect(()=>{if(!active)clear();},[active]);
  useEffect(()=>{
    const hide=()=>{if(document.visibilityState!=='visible')clear();};
    document.addEventListener('visibilitychange',hide);
    return()=>{++serial.current;clearTimeout(timer.current);document.removeEventListener('visibilitychange',hide);};
  },[]);
  const open=()=>{
    clear();select();const ticket=serial.current;
    read(out=>{
      if(ticket!==serial.current||document.visibilityState!=='visible')return;
      const v=out.input,now=Date.now();
      if(out.waitingHandle!==handle||out.localInstanceId!==instance||!originalDeviceInputReady(v)||v.taskId!==row.taskId||v.origin?.remoteOperationId!==row.operationId||
          v.origin?.callerDeviceId!==row.sourceDeviceId||v.origin?.originDeviceId!==row.sourceDeviceId||v.origin?.targetDeviceId!==here||
          !Number.isSafeInteger(v.viewUntil)||v.viewUntil<=now||!Number.isSafeInteger(v.remainingMs)||v.remainingMs<=0||v.remainingMs>25_000){
        setNotice('原派单或这次阅读窗口已变化，未显示内容。');return;
      }
      setValue(v);timer.current=setTimeout(()=>{clear();setNotice('阅读窗口已结束。');},Math.min(v.remainingMs,v.viewUntil-now));
    });
  };
  return <div><Button variant="ghost" disabled={busy} onClick={value&&active?clear:open}>{value&&active?'收起原请求':'先看对方交来的内容'}</Button>
    {active&&notice&&<p role="status">{notice}</p>}
    {active&&value&&<section className="pw-device-result" aria-label="本机收到的原请求">
      <p>只看这一次收到的资料。还没有接手，也没有使用模型或打开文件。</p>
      <p dir="auto" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{value.goal}</p>
      <ReceivedGoalInputReview view={value}/>
    </section>}
  </div>;
}
