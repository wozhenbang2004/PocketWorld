import {useEffect,useRef,useState} from 'react';
import {Accordion,AccordionItem,Button} from './forms-library';
import {bridge} from './bridge';
import {remoteObject as object,remoteRows as rows,type RemoteData as Data} from './remote-task-workspace';

const REVIEW='remoteTask.reviewPhoneCopy',LINK='remoteTask.linkPhoneCopy';
interface FilePin { taskId:string;runId:string;coordinatorEpoch:number;jobId:string;snapshotId:string;snapshotVersion:string;path:string;fileVersion:string;sha256:string;bytes:number; }
const key=(p:FilePin)=>JSON.stringify([p.taskId,p.runId,p.coordinatorEpoch,p.jobId,p.snapshotId,p.snapshotVersion,p.path,p.fileVersion,p.sha256,p.bytes]);
function samePin(p:FilePin,r:Data){return Object.entries(p).every(([k,v])=>v===r[k]);}
function missing(task:Data,already:Data[]):FilePin[]{
  const versions=rows(task.remoteResultSnapshots),files:FilePin[]=[];
  if(versions.length>8||already.length>64||!Number.isSafeInteger(task.coordinatorEpoch)||task.contextAvailable===false||
    task.contextWithdrawn===true||task.sourceCurrentReadRequired===true||task.taskProjection==='source_unavailable')return files;
  for(const s of versions){
    const source=object(s.source),entries=rows(s.files);
    if(!s.snapshotId||!s.snapshotVersion||entries.length>8||source.taskId!==task.id||source.runId!==task.runId||
      source.coordinatorEpoch!==task.coordinatorEpoch||s.jobId!==source.jobId||!s.jobId||s.jobOperationId!==task.runId||
      s.status!=='completed'||s.immutableArtifact!==true||s.authorshipVerified!==false)continue;
    for(const f of entries){
      if(!f.path||!/^[a-f0-9]{64}$/.test(f.sha256)||!/^[a-f0-9]{64}$/.test(f.fileVersion)||!Number.isSafeInteger(f.bytes)||f.bytes<0||f.bytes>262144||
        already.some(r=>r.snapshotId===s.snapshotId&&r.path===f.path))continue;
      files.push({taskId:task.id,runId:task.runId,coordinatorEpoch:task.coordinatorEpoch,jobId:s.jobId,snapshotId:s.snapshotId,
        snapshotVersion:s.snapshotVersion,path:f.path,fileVersion:f.fileVersion,sha256:f.sha256,bytes:f.bytes});
    }
  }return files;
}

/** Only explicit selection reads a blob; this component never opens the online task observer. */
export function RemoteOfflinePickup({task,saved}:{task:Data;saved:Data[]}){
  const pins=missing(task,saved),live=useRef(true),turn=useRef(0),flight=useRef(false);
  const [selected,select]=useState(''),[token,review]=useState(''),[pending,spin]=useState(false),
    [note,tell]=useState(''),[failed,fail]=useState(false),[limit,show]=useState(3);
  const id=task.id,run=task.runId,epoch=task.coordinatorEpoch,rowsNow=useRef(pins);rowsNow.current=pins;
  const reset=()=>{++turn.current;flight.current=false;spin(false);select('');review('');tell('');fail(false)};
  useEffect(()=>{live.current=true;reset();const hidden=()=>{if(document.hidden)reset()};
    document.addEventListener('visibilitychange',hidden);return()=>{live.current=false;++turn.current;document.removeEventListener('visibilitychange',hidden)}},[id,run,epoch]);
  function foreground(){const state=bridge.getState();return !document.hidden&&object(state.view).taskId===id&&
    [REVIEW,LINK].every(cmd=>state.capabilities.commands.includes(cmd));}
  async function check(p:FilePin,confirmation=false){
    const identity=key(p);
    if(!foreground()||flight.current||!rowsNow.current.some(v=>key(v)===identity)||confirmation&&(!token||selected!==identity))return;
    const attempt=++turn.current,receipt=token;flight.current=true;spin(true);review('');select(identity);tell('');fail(false);
    const current=()=>live.current&&turn.current===attempt&&foreground();
    try{
      const r=await bridge.command<Data>(confirmation?LINK:REVIEW,confirmation?{taskId:id,reviewId:receipt}:{...p});
      if(!current())return;
      if(!samePin(p,r)||r.networkUsed!==false)throw new Error('未收到原文件的手机核对回执。');
      if(!confirmation&&r.state==='not_in_phone_book'){tell('在手机里没找到这份已保存原件。没有下载；要从原主机领取，请单独查询原主机。');return;}
      if(!r.attachmentId)throw new Error('返回版本与所选原件不同，未采信。');
      if(!confirmation&&r.state==='verified_on_phone'&&r.reviewId){review(r.reviewId);tell('在手机里找到了这份原件，字节数和 SHA-256 一致。还没有关联到任务。');return;}
      if(confirmation&&['already_linked','linked_on_phone'].includes(r.state)&&r.remoteExecution===false){tell('原文件已关联，可在「手机已存原件」打开。没有重新下载或运行。');return;}
      throw new Error('未收到完整确认回执，请回原任务核对。');
    }catch(e){if(current()){fail(true);tell(e instanceof Error?e.message:'暂时无法核对；没有自动重试。')}}
    finally{if(live.current&&turn.current===attempt){flight.current=false;spin(false)}}
  }
  if(!pins.length||![REVIEW,LINK].every(cmd=>bridge.getState().capabilities.commands.includes(cmd)))return null;
  const chosen=pins.find(v=>key(v)===selected);
  return <Accordion><AccordionItem id="original-frozen-file-on-phone" title="找回已领取的原件">
    <p className="pw-field-hint">上次领取中断？从这个作业选一份冻结文件，只查手机，不联系主机。</p>
    {pins.slice(0,limit).map(p=><div key={key(p)} className="pw-remote-file"><div><strong>{p.path}</strong><small>{p.bytes} 字节 · 冻结版本 {p.snapshotVersion.slice(0,8)}</small></div>
      <Button size="small" variant="ghost" disabled={pending||!foreground()} onClick={()=>void check(p)}>{pending&&selected===key(p)?'核对中…':'核对手机'}</Button></div>)}
    {pins.length>limit&&<Button size="small" variant="ghost" onClick={()=>show(limit+4)}>更多文件</Button>}
    {note&&<p role={failed?'alert':'status'} className={failed?'pw-field-error':'pw-field-hint'}>{note}</p>}
    {chosen&&token&&<div><p className="pw-field-hint">{chosen.path} · {chosen.bytes} 字节</p>
      <details className="pw-field-hint" style={{overflowWrap:'anywhere'}}><summary>核对原作业和版本</summary>原作业 {chosen.jobId}<br/>快照 {chosen.snapshotId}<br/>快照版本 {chosen.snapshotVersion}<br/>文件版本 {chosen.fileVersion}<br/>SHA-256 <code>{chosen.sha256}</code></details>
      <Button size="small" variant="secondary" disabled={pending||!foreground()} onClick={()=>void check(chosen,true)}>关联回这个原任务</Button></div>}
  </AccordionItem></Accordion>;
}
