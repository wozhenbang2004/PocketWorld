import {privateProjectTask} from './task-continuation';
type Data=Record<string,any>;
export type ExecutionExit={format:string;taskId:string;runId:string;epoch:number;executionId:string;projectId:string;checkoutId:string;planDigest:string;sourceDigest:string;
  stepId:string;kind:'exit_code'|'signal'|'no_child';value:number;nativeStatus:string;canPrepareBuildFeedback:boolean;timedOut?:boolean;cancelRequested?:boolean;resourcesReleased?:boolean;processGroupAbsent?:boolean};
const sha=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const word=(v:unknown)=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(v);
export function originalExecutionExit(raw:unknown,metadata:Data,status:unknown):ExecutionExit|null{
  const r=raw as ExecutionExit|undefined;if(!r||r.format!=='pocketworld.owner-managed-exit.v1'||r.nativeStatus!==status||
    !['exit_code','signal','no_child'].includes(r.kind)||!Number.isSafeInteger(r.value)||r.value<(r.kind==='exit_code'?0:1)||r.value>(r.kind==='signal'?127:r.kind==='exit_code'?255:2147483647)||
    !sha(r.planDigest)||!sha(r.sourceDigest)||!word(r.stepId)||!Number.isSafeInteger(r.epoch)||r.epoch<1||typeof r.canPrepareBuildFeedback!=='boolean'||
    !(['taskId','runId','executionId','projectId','checkoutId'] as const).every(k=>word(r[k])&&r[k]===metadata[k])||r.epoch!==metadata.epoch||r.planDigest!==metadata.planDigest)return null;
  return r;
}
export const exitFact=(r:ExecutionExit)=>r.kind==='no_child'?`未创建子进程，启动错误号 ${r.value}`:r.kind==='signal'?`原生终止信号 ${r.value}，不是进程退出码`:`实际退出码 ${r.value}`;
/** Exact run/checkout, not taskId alone. Freeform changes remain in the old draft if the run changes. */
export function projectFeedbackKey(task?:Data|null):string{
  const r=task?.raw,c=r?.developmentContext;
  return task&&privateProjectTask(task)&&r.id===task.id&&word(r.runId)&&Number.isSafeInteger(r.coordinatorEpoch)&&r.coordinatorEpoch>0&&
    typeof r.conversationId==='string'&&r.conversationId.trim()&&word(c.projectId)&&word(c.checkoutId)?
    JSON.stringify([task.id,r.conversationId,r.runId,r.coordinatorEpoch,c.projectId,c.checkoutId]):'';
}
export function failedBuildFeedback(task:Data,r:ExecutionExit):string|null{
  const raw=task.raw,c=raw?.developmentContext;
  if(!projectFeedbackKey(task)||task.id!==r.taskId||raw.runId!==r.runId||raw.coordinatorEpoch!==r.epoch||c.projectId!==r.projectId||c.checkoutId!==r.checkoutId||
    !['paused','interrupted','awaiting_user','needs_confirmation','failed','completed'].includes(task.status)||raw.cancelRequested===true||
    !r.canPrepareBuildFeedback||r.stepId!=='build'||r.nativeStatus!=='failed'||r.kind!=='exit_code'||r.value<=0||r.resourcesReleased!==true||
    r.processGroupAbsent!==true||r.cancelRequested!==false||r.timedOut!==false)return null;
  return `请先核对这份本机副本的构建失败：原操作 ${r.executionId}，实际退出码 ${r.value}。当时的源码清单 SHA-256：${r.sourceDigest}。日志正文未附；请在原任务已有范围内定位问题并提出修正，不自动重跑或扩大授权。`;
}
