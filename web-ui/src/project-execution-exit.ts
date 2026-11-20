import {privateProjectTask} from './task-continuation';
type Data=Record<string,any>;
export type ExecutionExit={format:string;taskId:string;runId:string;epoch:number;executionId:string;projectId:string;checkoutId:string;planDigest:string;sourceDigest:string;
  stepId:string;kind:'exit_code'|'signal'|'no_child';value:number;nativeStatus:string;canPrepareBuildFeedback:boolean;canPrepareDependencyFeedback?:boolean;dependencyLockSha256?:string;timedOut?:boolean;cancelRequested?:boolean;resourcesReleased?:boolean;processGroupAbsent?:boolean};
const sha=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const word=(v:unknown)=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(v);
type NextNodeBuild={taskId:string;runId:string;epoch:number;operationId:string;projectId:string;checkoutId:string;sourceDigest:string;
  manager:'npm'|'pnpm';lockPath:string;lockSha256:string;receiptWasLate?:true};
/** Native getForUi readback of the only settled command in this original run. */
export function originalNodeBuildAfterDependencies(task?:Data|null):NextNodeBuild|null{
  const raw=task?.raw,c=raw?.developmentContext,pin=c?.ownerDraftFirstBuild,r=raw?.originalFirstBuildNext;
  const lock=pin?.lockedDependencies?.review,status=task?.status;
  if(!task||!projectFeedbackKey(task)||(status!=='awaiting_user'&&status!=='needs_confirmation')||raw?.status!==status||raw.cancelRequested===true||
    raw.hasUnresolvedOperations!==false||pin?.existingCheckoutBuild!==true||!r||r.format!=='pocketworld.owner-node-build-next.v1'||
    r.dependencyExitCode!==0||r.buildStarted!==false||r.taskId!==task.id||r.runId!==raw.runId||r.epoch!==raw.coordinatorEpoch||
    !word(r.operationId)||r.projectId!==c.projectId||r.projectId!==pin.projectId||r.checkoutId!==c.checkoutId||r.checkoutId!==pin.checkoutId||
    !sha(r.sourceDigest)||r.sourceDigest!==pin.sourceDigest||r.sourceDigest!==c.initialSourceDigest||
    (r.manager!=='npm'&&r.manager!=='pnpm')||r.manager!==pin.manager||r.manager!==lock?.manager||
    r.lockPath!==(r.manager==='npm'?'package-lock.json':'pnpm-lock.yaml')||r.lockPath!==lock?.lockPath||
    !sha(r.lockSha256)||r.lockSha256!==lock?.lockSha256)return null;
  if(r.receiptWasLate!==undefined&&(r.receiptWasLate!==true||status!=='awaiting_user'||raw.cancelRequested!==false||
    raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||raw.historyHasUnconfirmedOperations!==false||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true))return null;
  return {taskId:r.taskId,runId:r.runId,epoch:r.epoch,operationId:r.operationId,projectId:r.projectId,checkoutId:r.checkoutId,
    sourceDigest:r.sourceDigest,manager:r.manager,lockPath:r.lockPath,lockSha256:r.lockSha256,
    ...(r.receiptWasLate===true?{receiptWasLate:true as const}:{})};
}
/** Read-only identity of a legacy original execution that cannot be continued. */
export function originalNodeBuildHoldKey(task?:Data|null):string{
  const raw=task?.raw,c=raw?.developmentContext,pin=c?.ownerDraftFirstBuild,r=raw?.originalFirstBuildContinuationHold;
  const owner=projectFeedbackKey(task);
  if(!owner||!word(task?.id)||task?.status!=='awaiting_user'||raw.status!==task.status||raw.cancelRequested!==false||
    raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||raw.hasUnresolvedOperations!==false||raw.historyHasUnconfirmedOperations!==false||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.originalFirstBuildNext!=null||
    c.format!=='pocketworld.development-context.v1'||c.runtime!=='managed_process_v1'||c.draftOnly===true||pin?.existingCheckoutBuild!==true||
    r?.format!=='pocketworld.owner-node-build-continuation-hold.v1'||r.reason!=='missing_original_end_witness'||
    r.dependencyExitCode!==0||r.buildStarted!==false||r.taskId!==task.id||r.runId!==raw.runId||r.epoch!==raw.coordinatorEpoch||
    !word(r.operationId)||r.projectId!==c.projectId||r.projectId!==pin.projectId||r.checkoutId!==c.checkoutId||r.checkoutId!==pin.checkoutId||
    !sha(r.sourceDigest)||r.sourceDigest!==pin.sourceDigest||r.sourceDigest!==c.initialSourceDigest)return '';
  return JSON.stringify([owner,r.operationId,r.sourceDigest]);
}
export function nodeBuildContinuationText(next:NextNodeBuild):string{
  return `请按原来的 ${next.lockPath} 核对同一副本中的实际依赖。若已就绪，请只提出这版源码的 ${next.manager} run build 供我另行批准。不改源码或锁，不自动下载、构建或启动服务。`;
}
export function originalExecutionExit(raw:unknown,metadata:Data,status:unknown):ExecutionExit|null{
  const r=raw as ExecutionExit|undefined;if(!r||r.format!=='pocketworld.owner-managed-exit.v1'||r.nativeStatus!==status||
    !['exit_code','signal','no_child'].includes(r.kind)||!Number.isSafeInteger(r.value)||r.value<(r.kind==='exit_code'?0:1)||r.value>(r.kind==='signal'?127:r.kind==='exit_code'?255:2147483647)||
    !sha(r.planDigest)||!sha(r.sourceDigest)||!word(r.stepId)||!Number.isSafeInteger(r.epoch)||r.epoch<1||typeof r.canPrepareBuildFeedback!=='boolean'||
    (r.canPrepareDependencyFeedback!=null||r.dependencyLockSha256!=null) && (r.canPrepareDependencyFeedback!==true||r.stepId!=='dependencies'||!sha(r.dependencyLockSha256)) ||
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
    r.nativeStatus!=='failed'||r.kind!=='exit_code'||r.value<=0||r.resourcesReleased!==true||
    r.processGroupAbsent!==true||r.cancelRequested!==false||r.timedOut!==false)return null;
  if(r.stepId==='dependencies'&&r.canPrepareDependencyFeedback===true&&sha(r.dependencyLockSha256)){
    const pinned=c.ownerDraftFirstBuild?.lockedDependencies;
    if(pinned?.planDigest!==r.planDigest||pinned?.review?.lockSha256!==r.dependencyLockSha256)return null;
    return `请在同一副本查看原依赖准备日志：操作 ${r.executionId}，退出码 ${r.value}。只核对原锁 SHA-256 ${r.dependencyLockSha256}，若需重新下载请再次向我申请，不改锁、不换环境、不自动构建。日志正文未附。`;
  }
  if(r.stepId!=='build'||!r.canPrepareBuildFeedback)return null;
  return `请先核对这份本机副本的构建失败：原操作 ${r.executionId}，实际退出码 ${r.value}。当时的源码清单 SHA-256：${r.sourceDigest}。日志正文未附；请在原任务已有范围内定位问题并提出修正，不自动重跑或扩大授权。`;
}
