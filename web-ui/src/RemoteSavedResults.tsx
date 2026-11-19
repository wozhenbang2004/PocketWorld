import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import {HostSavedProjectPreparation,type HostSourcePin} from './HostSavedProjectPreparation';
import { Accordion, AccordionItem, Button } from './forms-library';
import { remoteObject as object, type RemoteData as Data } from './remote-task-workspace';

function selection(taskId:string,row:Data) {
  const a=object(row.attachment), source=object(a.source);
  if(row.taskId!==taskId||!taskId||source.kind!=='remote_result_snapshot'||source.taskId!==taskId||source.immutableArtifact!==true||
     typeof a.id!=='string'||!a.id||!Number.isSafeInteger(row.bytes)||row.bytes<0||a.size!==row.bytes||source.bytes!==row.bytes||
     typeof row.sha256!=='string'||!/^([a-f0-9]{64})$/.test(row.sha256)||a.sha256!==row.sha256)return null;
  for(const k of ['snapshotId','snapshotVersion','path','fileVersion','sha256'] as const)
    if(typeof row[k]!=='string'||!row[k]||source[k]!==row[k])return null;
  return {taskId,attachmentId:a.id,snapshotId:row.snapshotId,snapshotVersion:row.snapshotVersion,path:row.path,fileVersion:row.fileVersion,sha256:row.sha256};
}

export function OpenRemoteSavedFile({taskId,row,disabled=false}:{taskId:string;row:Data;disabled?:boolean}) {
  const [error,setError]=useState(''),[pending,setPending]=useState(false),inFlight=useRef(false),mounted=useRef(true);
  const claim=selection(taskId,row);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
  const canRead=()=>document.visibilityState==='visible'&&object(bridge.getState().view).taskId===taskId&&
    bridge.getState().capabilities.commands.includes('remoteTask.openLocalSnapshot');
  const open=async()=>{
    if(!claim||disabled||inFlight.current||!canRead())return;
    inFlight.current=true;setPending(true);setError('');
    try{
      const receipt=await bridge.command<Data>('remoteTask.openLocalSnapshot',claim);
      if(receipt.taskId!==taskId||receipt.attachmentId!==claim.attachmentId||receipt.viewerRequested!==true||
         receipt.executionAuthorized!==false||receipt.networkUsed!==false)throw new Error('未核对到这份原件的阅读入口。');
    }catch(e){if(mounted.current)setError(e instanceof Error?e.message:'这份手机原件暂不可读。');}
    finally{inFlight.current=false;if(mounted.current)setPending(false);}
  };
  return <><Button variant="ghost" size="small" disabled={pending||disabled||!claim||!canRead()} onClick={()=>void open()}>{pending?'核对手机原件…':'打开手机原件'}</Button>{error&&<p className="pw-field-error" role="alert">{error}</p>}</>;
}

/** Source is the selected original TaskStore task, not the remote service or a global file inventory. */
export function RemoteSavedResults({taskId,rows,openProject}:{taskId:string;rows:Data[];openProject?:(pin:HostSourcePin)=>void}) {
  if(!rows.length)return null;
  const records=rows.length<=64?rows.filter(r=>selection(taskId,r)):[],limited=records.length!==rows.length;
  const files=<>{records.map(row=><div key={JSON.stringify([row.snapshotId,row.path,row.attachment.id])} className="pw-remote-file">
    <div><strong>{row.path}</strong><small>{row.bytes} 字节 · 此任务已存到手机</small></div><OpenRemoteSavedFile taskId={taskId} row={row}/>
  </div>)}{openProject&&records.length>0&&<HostSavedProjectPreparation task={taskId} files={records.map(row=>({claim:selection(taskId,row)!,bytes:row.bytes}))} openProject={openProject}/>}
    {limited&&<p className="pw-field-hint">有保存记录已无法核对，未改用其他文件。</p>}</>;
  return records.length===1&&!limited?<section aria-label="本机保存的原成果">{files}</section>:
    <Accordion><AccordionItem id="original-host-files-on-phone" title={`手机已存原件 · ${records.length}`}>{files}</AccordionItem></Accordion>;
}
