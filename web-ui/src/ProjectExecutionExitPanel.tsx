import {useState} from 'react';
import {Button} from './forms-library';
import {exitFact,failedBuildFeedback,type ExecutionExit} from './project-execution-exit';
type Data=Record<string,any>;
export function ProjectExecutionExitPanel({exit,task,active,prepare}:{exit:ExecutionExit;task:Data;active:boolean;prepare?:(r:ExecutionExit)=>void}){
  const [review,show]=useState(false),[ack,report]=useState('');
  const proposed=prepare&&failedBuildFeedback(task,exit),title=exit.stepId==='build'?'这次构建':exit.stepId==='dependencies'&&exit.canPrepareDependencyFeedback?'这次锁定依赖准备':'这次执行';
  return <section aria-label="原执行结果"><p role="status"><strong>{title} · {exitFact(exit)}</strong>
    {exit.timedOut===true?' · 原运行已超时':''}{exit.cancelRequested===true?' · 原运行记录了取消请求':''}。
    {exit.nativeStatus==='completed'?'进程已完成，不能据此断定产物可用。':exit.nativeStatus==='failed'?'原生回执记为失败。':'进程结果与资源回收分别核对。'}</p>
    {proposed&&(!review?<Button variant="secondary" size="small" disabled={!active} onClick={()=>{report('');show(true)}}>整理这次失败给原任务</Button>:
      <div role="region" aria-label="核对要带回的原执行反馈"><p>{proposed}</p><p className="pw-field-hint">只追加到这个原任务当前一轮的窗口草稿，不是任务记录。不会复制日志、发送给模型或重启构建。</p>
        <div className="pw-form-actions"><Button variant="ghost" size="small" disabled={!active} onClick={()=>show(false)}>返回原日志</Button>
          <Button variant="secondary" size="small" disabled={!active} onClick={()=>{try{prepare!(exit);show(false);report('已带到原任务的修改草稿，尚未提交。')}catch(e){report(e instanceof Error?e.message:String(e))}}}>加入原任务修改草稿</Button></div></div>)}
    {ack&&<p role="status">{ack}</p>}</section>;
}
