import {useState,type ReactNode} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {phoneReportInSource,phoneReportTarget,type PhoneReportTarget} from './PhoneReturnedReport';
import {cancelledPhoneSummaryNote,phoneRunIdentity,type PhoneResult} from './phone-start';
import './phone-message-return.css';

type Entry='overview'|'process';
const outcomes:Record<string,{title:string;entry:Entry;next:string}>={
  completed:{title:'已记为完成',entry:'overview',next:'查看这轮成果'},
  failed:{title:'执行失败',entry:'process',next:'查看这轮执行记录'},
  interrupted:{title:'执行中断',entry:'process',next:'查看这轮执行记录'},
  cancelled:{title:'已取消',entry:'process',next:'查看停止前的记录'},
  needs_confirmation:{title:'等待你确认',entry:'overview',next:'查看待确认事项'},
  awaiting_user:{title:'等待你处理',entry:'overview',next:'查看需要处理的事项'},
  reconciling:{title:'有操作结果待核对',entry:'overview',next:'查看待核对的记录'},
  paused:{title:'已暂停',entry:'overview',next:'查看这轮暂停记录'},
};
function matchingStep(r:PhoneResult){
  const rows=bridge.getState().state.tasks;
  if(!Array.isArray(rows))return null;
  const row=rows.find(it=>it?.id===r.taskId),identity=phoneRunIdentity(row);
  if(!row||!isPrivateLocalPhone(row)||identity?.taskId!==r.taskId||identity.runId!==r.runId||identity.epoch!==r.epoch)return null;
  return row.status===r.status?outcomes[r.status]:{entry:'overview' as const,next:'查看这轮后续进展'};
}
type Props={result:PhoneResult;conversation:string;messageId:string;revision:number;reader?:number;locked?:boolean;stoppedAfterFirstMainRead?:boolean;
  body:string;children:ReactNode;onOriginal?:(t:PhoneReportTarget)=>void;onRun?:(entry:Entry,t:PhoneReportTarget)=>void};
/** A persisted message is a historical receipt. Never derive its outcome from its prose or today's task status. */
export function PhoneMessageReturn({result:r,conversation,messageId,revision,reader=0,locked,stoppedAfterFirstMainRead,body,children,onOriginal,onRun}:Props){
  const outcome=outcomes[r.status],step=onRun&&onOriginal&&matchingStep(r);
  const [changed,setChanged]=useState('');
  // On zero native characters, this one exact fallback belongs to the delivery formatter, not the original summary.
  const cancellationNote=cancelledPhoneSummaryNote(r,body);
  const placeholder=r.summaryChars===0&&body==='本轮执行已结束，未提供文字总结；请查看原手机任务。';
  const target=(from:HTMLElement)=>phoneReportTarget(conversation,messageId,revision,r,from,reader);
  return <section className="pw-phone-message-return" aria-label={`手机任务第 ${r.epoch} 轮的原回执`}>
    <header><small>手机任务 · 第 {r.epoch} 轮回执</small><strong>{outcome?.title||'已有轮次记录'}</strong></header>
    {cancellationNote&&<p className="pw-field-hint">{cancellationNote}</p>}
    {reader===0&&r.status==='cancelled'&&stoppedAfterFirstMainRead&&<p className="pw-field-hint">停止前已读取当时的主屏界面。</p>}
    {placeholder?<p className="pw-phone-return-empty">{r.status==='failed'||r.status==='interrupted'?'这轮没有留下说明原因的文字。':
      '这轮没有留下文字总结。'}</p>:children}
    {(onOriginal||step)&&<div className="pw-phone-return-actions">
      {step&&onRun&&<Button size="small" variant="secondary" disabled={locked} onClick={event=>{
        const t=target(event.currentTarget),now=matchingStep(r);
        if(!locked&&now?.entry===step.entry&&now.next===step.next&&phoneReportInSource(t)&&bridge.getState().capabilities.commands.includes('tasks.open')){setChanged('');onRun(step.entry,t)}
        else setChanged(r.deliveryId);
      }}>{step.next}</Button>}
      {onOriginal&&<Button size="small" variant={step?'ghost':'secondary'} disabled={locked} onClick={event=>{
        const t=target(event.currentTarget);if(!locked&&phoneReportInSource(t)){setChanged('');onOriginal(t)}else setChanged(r.deliveryId);
      }}>{r.summaryTruncated?'阅读完整原文':'查看这轮原回执'}</Button>}
    </div>}
    {changed===r.deliveryId&&<small role="status">原消息或轮次已变化。请回到原回执重新核对，没有打开其他执行。</small>}
  </section>;
}
