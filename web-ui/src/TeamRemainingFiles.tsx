import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';

type Row={path:string;artifactId:string;version:number;revisionId:string};
type Receipt={taskId:string;runId:string;epoch:number;reviewHandle:string;completed:Row[];remaining:Row[];grantExpiresAt:number;parentExpiresAt:number;operationsLeft:number;poolOperationsLeft:number;poolModelsLeft:number;executionAuthorized:boolean};
type Command=(action:string,input:Record<string,unknown>)=>Promise<unknown>;
const rows=(v:unknown):v is Row[]=>Array.isArray(v)&&v.length>0&&v.length<=32&&v.every(r=>r&&typeof r.path==='string'&&r.path&&typeof r.artifactId==='string'&&typeof r.revisionId==='string'&&Number.isSafeInteger(r.version));

/** Shown only for the original reconciled integration task. Read/close are never a dispatch. */
export function TeamRemainingFiles({id,run,epoch,disabled,command}:{id:string;run:string;epoch:number;disabled:boolean;command:Command}){
  const [review,setReview]=useState<Receipt|null>(null),[note,setNote]=useState(''),[busy,setBusy]=useState(false),[started,setStarted]=useState(false);
  const mounted=useRef(true),inFlight=useRef(false);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
  async function act(action:'read'|'close'|'continue'){
    if(disabled||inFlight.current||started||action!=='read'&&!review)return;
    inFlight.current=true;setBusy(true);setNote('');
    try{
      if(action==='close'){const h=review!.reviewHandle;setReview(null);await command('tasks.teamRemainder.close',{taskId:id,reviewHandle:h});return;}
      if(action==='read'){
        const r=await command('tasks.teamRemainder.read',{taskId:id,runId:run,epoch}) as Receipt;
        if(!r||r.taskId!==id||r.runId!==run||r.epoch!==epoch||r.executionAuthorized!==false||typeof r.reviewHandle!=='string'||!r.reviewHandle||!rows(r.completed)||!rows(r.remaining))throw new Error('未核实同一次执行的文件清单，没有启动。');
        if(mounted.current)setReview(r);return;
      }
      const h=review!.reviewHandle;setReview(null);
      const r=await command('tasks.teamRemainder.continue',{taskId:id,reviewHandle:h,confirmed:true}) as {taskId?:string;fromRunId?:string;runId?:string;epoch?:number;started?:boolean};
      if(!r||r.taskId!==id||r.fromRunId!==run||!r.runId||r.runId===run||r.epoch!==epoch+1||r.started!==true)throw new Error('没有收到准确启动回执，请先重新读取原任务，不要重复提交。');
      if(mounted.current){setStarted(true);setNote('已在原整合任务开始处理剩余文件。是否写入，以每份文件的新回执为准。');}
    }catch(e){if(mounted.current){setReview(null);setNote(e instanceof Error?e.message:'未能核实原任务，请重新读它的状态。');}}
    finally{inFlight.current=false;if(mounted.current)setBusy(false);}
  }
  return <section className="pw-approval-item" aria-label="原文件续接">
    {!review&&!started&&<Button size="small" variant="secondary" disabled={disabled||busy} onClick={()=>void act('read')}>{busy?'正在核对原任务':'查看剩余原文件'}</Button>}
    {review&&<><h4>继续哪些原文件</h4>
      <p>已写回，不会重写</p><ul>{review.completed.map(f=><li key={f.artifactId}>{f.path} · 第 {f.version} 版</li>)}</ul>
      <p>将继续处理</p><ul>{review.remaining.map(f=><li key={f.artifactId}>{f.path} · {f.version?'原基线第 '+f.version+' 版':'原基线尚无文件'}</li>)}</ul>
      <p className="pw-muted">沿用原模型和到期时间（{new Date(Math.min(review.grantExpiresAt,review.parentExpiresAt)).toLocaleTimeString()}）。整合者剩余 {review.operationsLeft} 次操作；团队余 {review.poolOperationsLeft} 次工具、{review.poolModelsLeft} 次模型。需要的逐文件批准仍在原任务办理。</p>
      <Button size="small" disabled={disabled||busy} onClick={()=>void act('continue')}>继续处理这 {review.remaining.length} 份</Button>{' '}
      <Button size="small" variant="secondary" disabled={disabled||busy} onClick={()=>void act('close')}>暂不继续</Button>
    </>}
    {note&&<p role="status">{note}</p>}
  </section>;
}
