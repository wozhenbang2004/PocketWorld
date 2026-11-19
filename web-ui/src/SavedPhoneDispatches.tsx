import { useState } from 'react';
import { Button } from './forms-library';
import { archivedPhoneAcceptanceLine, archivedPhoneStopAttemptLine, archivedPhoneStopLine, isArchivedPhoneStopAttempt, isArchivedPhoneStop, type ArchivedPhoneStopAttempt, type ArchivedPhoneStop, type DeviceData, type SentPhoneGoalFocus } from './devices-workspace';

type Row = {goal:string; createdAt:number; targetAcceptedAt?:number; targetRunRecorded?:true; stopReceipt?:ArchivedPhoneStop; stopAttempt?:ArchivedPhoneStopAttempt};
type Page = {rows:Row[]; after:string; next:string; previous:string[]};
const positiveTime=(n:unknown):n is number=>typeof n==='number' && Number.isSafeInteger(n) && n>0;
const time=(n:number)=>new Date(n).toLocaleString();
type Props={source:SentPhoneGoalFocus; local:DeviceData; busy:boolean;
  read:(args:DeviceData,accept:(value:DeviceData)=>void)=>void};

/** A page of the sender's own original work. Deliberately has no path to the former peer. */
export function SavedPhoneDispatches({source,local,busy,read}:Props){
  const [page,setPage]=useState<Page|null>(null);
  const load=(after='',previous:string[]=[])=>{
    setPage(null); // Do not leave old private text on screen if revalidation fails.
    const target={deviceId:source.deviceId,instanceId:source.instanceId,certificateFingerprint:source.certificateFingerprint};
    read({sourceTaskId:source.taskId,localCopy:target,...(after?{after}:{})},value=>{
      if(value.kind!=='savedDirectedPhoneDispatches'||value.readOnly!==true||value.sourceTaskId!==source.taskId||value.after!==after
        ||value.localDeviceId!==local.deviceId||value.localInstanceId!==local.instanceId||value.target?.deviceId!==target.deviceId
        ||value.target.instanceId!==target.instanceId||value.target.certificateFingerprint!==target.certificateFingerprint
        ||typeof value.nextAfter!=='string'||value.nextAfter!==''&&(!/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value.nextAfter)||value.nextAfter===after)
        ||!Array.isArray(value.rows)||value.rows.length>24||!value.rows.every((r:Row)=>r&&typeof r.goal==='string'&&r.goal.trim()
          &&Array.from(r.goal).length<=161&&positiveTime(r.createdAt)&&(r.targetAcceptedAt===undefined||positiveTime(r.targetAcceptedAt))&&(r.targetRunRecorded===undefined||r.targetRunRecorded===true&&positiveTime(r.targetAcceptedAt))&&(r.stopReceipt===undefined||isArchivedPhoneStop(r.stopReceipt))
          &&(r.stopAttempt===undefined||r.stopReceipt===undefined&&isArchivedPhoneStopAttempt(r.stopAttempt))))
        throw new Error('留档与这份工作当时选定的手机不符，未显示其他派单。');
      setPage({rows:value.rows.map(({goal,createdAt,targetAcceptedAt,targetRunRecorded,stopReceipt,stopAttempt}:Row)=>({goal,createdAt,targetAcceptedAt,targetRunRecorded,stopReceipt,stopAttempt})),after,next:value.nextAfter,previous});
    });
  };
  return <div>
    <p>当前配对记录无法对应原手机。这里能查本机当时留下的派单，不会联系对方。</p>
    {page&&<>
      {!page.rows.length&&<p role="status">{page.next?'这页没有能核对的派单，可以再看较早记录。':'本机在这一页没有可核对的原派单。'}</p>}
      {page.rows.map((row,i)=><article className="pw-device-history-entry" key={page.after+':'+i}>
        <h4 dir="auto">{row.goal}</h4><p>本机留档 {time(row.createdAt)} · {archivedPhoneAcceptanceLine(row,time)}</p>
        {row.stopReceipt?<p>{archivedPhoneStopLine(row.stopReceipt,time)}</p>:row.stopAttempt&&<p>{archivedPhoneStopAttemptLine(row.stopAttempt,time)}</p>}
      </article>)}
      <p className="pw-device-footnote">设备接收不是机主已读；登记过执行也不代表现在仍在运行或已经完成。这里没有对方的成果或当前状态。</p>
    </>}
    <div className="pw-form-actions">
      {!page?<Button variant="secondary" disabled={busy} onClick={()=>load()}>查看本机派单留档</Button>:<>
        {page.previous.length>0&&<Button variant="ghost" disabled={busy} onClick={()=>load(page.previous[page.previous.length-1],page.previous.slice(0,-1))}>较新派单</Button>}
        {page.next&&<Button variant="secondary" disabled={busy} onClick={()=>load(page.next,[...page.previous,page.after].slice(-32))}>较早派单</Button>}
        <Button variant="ghost" disabled={busy} onClick={()=>load()}>{page.after?'回到最新留档':'重读本机留档'}</Button>
      </>}
    </div>
  </div>;
}
