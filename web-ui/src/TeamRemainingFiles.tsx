import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';

type Row={path:string;artifactId:string;version:number;revisionId:string};
type Approved={id:string;path:string;artifactId:string;expiresAt:number};
type Receipt={approvedFiles:Approved[];continuationKind:string;taskId:string;runId:string;epoch:number;reviewHandle:string;completed:Row[];remaining:Row[];grantExpiresAt:number;parentExpiresAt:number;operationsLeft:number;poolOperationsLeft:number;poolModelsLeft:number;executionAuthorized:boolean};
type Command=(action:string,input:Record<string,unknown>)=>Promise<unknown>;
const rows=(v:unknown,empty=false):v is Row[]=>Array.isArray(v)&&(empty||v.length>0)&&v.length<=32&&v.every(r=>r&&typeof r.path==='string'&&r.path&&typeof r.artifactId==='string'&&typeof r.revisionId==='string'&&Number.isSafeInteger(r.version));

/** Shown only in the original integration task, after a native file decision or reconciliation. Read/close are never a dispatch. */
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
        if(!r||r.taskId!==id||r.runId!==run||r.epoch!==epoch||r.executionAuthorized!==false||typeof r.reviewHandle!=='string'||!r.reviewHandle||!rows(r.remaining)||!Array.isArray(r.approvedFiles)
          ||!r.approvedFiles.every(a=>a&&typeof a.id==='string'&&Number.isSafeInteger(a.expiresAt)&&r.remaining.some(f=>a.path===f.path&&a.artifactId===f.artifactId))
          ||(r.approvedFiles.length>0?r.continuationKind!=='approved_original_files':r.continuationKind!=='recovered_remainder')||!rows(r.completed,r.approvedFiles.length>0))throw new Error('未核实同一次执行的文件清单，没有启动。');
        if(mounted.current)setReview(r);return;
      }
      const h=review!.reviewHandle;setReview(null);
      const r=await command('tasks.teamRemainder.continue',{taskId:id,reviewHandle:h,confirmed:true}) as {taskId?:string;fromRunId?:string;runId?:string;epoch?:number;started?:boolean};
      if(!r||r.taskId!==id||r.fromRunId!==run||!r.runId||r.runId===run||r.epoch!==epoch+1||r.started!==true)throw new Error('没有收到准确启动回执，请先重新读取原任务，不要重复提交。');
      if(mounted.current){setStarted(true);setNote('原整合执行已开始。是否写入，以每份文件实际返回的新回执为准。');}
    }catch(e){if(mounted.current){setReview(null);setNote(e instanceof Error?e.message:'未能核实原任务，请重新读它的状态。');}}
    finally{inFlight.current=false;if(mounted.current)setBusy(false);}
  }
  const approved=review?.approvedFiles??[],pending=review?.remaining.filter(f=>!approved.some(a=>a.path===f.path&&a.artifactId===f.artifactId))??[];
  const versions=(files:Row[])=>files.map(f=><li key={f.artifactId}>{f.path} · {f.version?'原基线第 '+f.version+' 版':'原基线尚无文件'}</li>);
  return <section className="pw-approval-item" aria-label="原文件续接">
    {!review&&!started&&<Button size="small" variant="secondary" disabled={disabled||busy} onClick={()=>void act('read')}>{busy?'正在核对原任务':'核对原文件与写回许可'}</Button>}
    {review&&<><h4>{approved.length?'继续已批准的原文件':'继续哪些原文件'}</h4>
      {review.completed.length>0&&<><p>已写回，不会重写</p><ul>{review.completed.map(f=><li key={f.artifactId}>{f.path} · 第 {f.version} 版</li>)}</ul></>}
      {approved.length>0&&<><p>以下逐文件许可已保存、尚未使用。上次请求停在批准前，并未派发写入。</p>
        <ul>{approved.map(a=><li key={a.id}>{a.path} · 许可至 {new Date(a.expiresAt).toLocaleTimeString()}</li>)}</ul></>}
      {pending.length>0&&<><p>{approved.length?'其余原文件不在本次已批准清单中，之后仍需逐份批准并继续':'将继续处理'}</p><ul>{versions(pending)}</ul></>}
      <p className="pw-muted">沿用原模型和到期时间（{new Date(Math.min(review.grantExpiresAt,review.parentExpiresAt)).toLocaleTimeString()}）。整合者剩余 {review.operationsLeft} 次操作；团队余 {review.poolOperationsLeft} 次工具、{review.poolModelsLeft} 次模型。需要的逐文件批准仍在原任务办理。</p>
      <Button size="small" disabled={disabled||busy} onClick={()=>void act('continue')}>{approved.length?'继续处理这 '+approved.length+' 份已批准文件':'继续处理这 '+review.remaining.length+' 份'}</Button>{' '}
      <Button size="small" variant="secondary" disabled={disabled||busy} onClick={()=>void act('close')}>暂不继续</Button>
    </>}
    {note&&<p role="status">{note}</p>}
  </section>;
}
