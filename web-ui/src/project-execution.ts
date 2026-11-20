export type ProjectExecutionData = Record<string, any>;
export type ProjectExecutionRow = ProjectExecutionData & { operationId: string; interactive: boolean; routeKind: 'managed'|'termux'; failureStage?:'dependencies'|'build' };
const object = (value: any): ProjectExecutionData => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const operationId = (value: unknown): value is string => typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);

/** Native detail locator only. Even if its operation is outside this history page,
 * the observation host rechecks the original task, execution and runtime receipt. */
export type FirstBuildFailure = ProjectExecutionRow & {runId:string;epoch:number;projectId:string;checkoutId:string;
  sourceDigest:string;planDigest:string;exitCode:number;approvedCommand?:string[]};
export function originalFirstBuildFailure(task: ProjectExecutionData): FirstBuildFailure | undefined {
  const raw=object(task.raw),fact=object(raw.originalFirstBuildFailure),scope=object(raw.developmentContext),pin=object(scope.ownerDraftFirstBuild);
  if(raw.taskProjection!=='detail_window'||raw.kind!=='assistant'||raw.actorId!=='owner'||raw.spaceId!=='private'||raw.origin||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||
    scope.runtime!=='managed_process_v1'||scope.draftOnly===true||raw.id!==task.id||fact.taskId!==task.id||
    !operationId(pin.projectId)||pin.projectId!==scope.projectId||!operationId(pin.checkoutId)||pin.checkoutId!==scope.checkoutId||
    typeof pin.sourceDigest!=='string'||!/^[a-f0-9]{64}$/.test(pin.sourceDigest)||pin.sourceDigest!==scope.initialSourceDigest||
    !operationId(fact.actionRunId)||fact.actionRunId!==raw.runId||!Number.isSafeInteger(fact.actionEpoch)||fact.actionEpoch<1||fact.actionEpoch!==raw.coordinatorEpoch||
    !operationId(fact.actionOperationId)||!Number.isInteger(fact.value)||fact.value<1||fact.value>255||
    !['dependency_exit','build_exit'].includes(fact.kind))return undefined;
  const dependency=fact.kind==='dependency_exit',plan=dependency?pin.lockedDependencies?.planDigest:pin.planDigest;
  if(typeof plan!=='string'||!/^[a-f0-9]{64}$/.test(plan))return undefined;
  const argv=fact.approvedCommand,approvedCommand=fact.planDigest===plan&&Array.isArray(argv)&&argv.length>1&&argv.length<=20&&
    argv[0]===pin.manager&&['npm','pnpm'].includes(argv[0])&&argv.every((word:unknown)=>typeof word==='string'&&/^[A-Za-z0-9._:/=-]{1,160}$/.test(word))?
    argv as string[]:undefined;
  return {operationId:fact.actionOperationId,runId:fact.actionRunId,epoch:fact.actionEpoch,interactive:false,routeKind:'managed',
    failureStage:dependency?'dependencies':'build',projectId:pin.projectId,checkoutId:pin.checkoutId,sourceDigest:pin.sourceDigest,planDigest:plan,exitCode:fact.value,approvedCommand};
}

export type OriginalBuildWebGap = 'unverified' | 'nested_vite_output';

/** A successful process is independent of both an output limitation and a save. */
export function originalBuildWebGap(task: ProjectExecutionData): OriginalBuildWebGap | undefined {
  const raw=object(task.raw),scope=object(raw.developmentContext),pin=object(scope.ownerDraftFirstBuild);
  const sha=(value:unknown)=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
  if(raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited===true||raw.kind!=='assistant'||raw.actorId!=='owner'||raw.spaceId!=='private'||raw.origin||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.cancelRequested===true||
    raw.hasUnresolvedOperations!==false||raw.id!==task.id||!operationId(task.id)||!operationId(raw.runId)||
    !Number.isSafeInteger(raw.coordinatorEpoch)||raw.coordinatorEpoch<1||pin.existingCheckoutBuild!==true||scope.runtime!=='managed_process_v1'||
    !operationId(pin.projectId)||pin.projectId!==scope.projectId||!operationId(pin.checkoutId)||pin.checkoutId!==scope.checkoutId||
    !sha(pin.sourceDigest)||pin.sourceDigest!==scope.initialSourceDigest||!sha(pin.planDigest)||!sha(pin.runtimeIdentity)||
    !['npm','pnpm'].includes(pin.manager)||!Array.isArray(raw.operations))return undefined;
  const current=(raw.operations as unknown[]).map(object).filter(row=>row.runId===raw.runId);
  // An outstanding or settled original archive action is not a missing-webpage-witness case.
  if(current.some(row=>row.tool==='owner_keep_first_node_web_output'))return undefined;
  const builds=current.filter(row=>row.tool==='project_execute'&&object(row.arguments).stepId==='build');
  if(builds.length!==1)return undefined;
  const row=builds[0],args=object(row.arguments),receipt=object(row.receipt),route=object(receipt.projectExecution);
  const actual=object(receipt.executionReceipt),observedRoute=object(actual.projectExecution);
  if(row.state!=='receipt'||!operationId(row.operationId)||args.planDigest!==pin.planDigest||
    receipt.code!=='project_process_receipt'||receipt.accepted!==true||receipt.status!=='completed'||receipt.executionStatus!=='completed'||
    !sha(route.requestDigest))return undefined;
  const binding={authority:'native_host_operation',command:'project.runtime.run',runtimeId:scope.runtime,runtimeIdentity:pin.runtimeIdentity,
    taskId:task.id,runId:raw.runId,coordinatorEpoch:raw.coordinatorEpoch,operationId:row.operationId,projectId:pin.projectId,
    checkoutId:pin.checkoutId,planDigest:pin.planDigest,sourceDigest:pin.sourceDigest,requestDigest:route.requestDigest,stepId:'build',intent:'build'};
  const proved = Object.entries(binding).every(([key,value])=>route[key]===value&&observedRoute[key]===value)&&
    actual.id===row.operationId&&actual.operationId===row.operationId&&actual.checkoutId===pin.checkoutId&&actual.sourceDigest===pin.sourceDigest&&
    actual.provider==='managed_process'&&actual.kind==='command'&&actual.requestedExecutable===pin.manager&&
    Array.isArray(actual.requestedArgv)&&actual.requestedArgv.length===2&&actual.requestedArgv[0]==='run'&&actual.requestedArgv[1]==='build'&&
    actual.status==='completed'&&actual.receiptReceived===true&&actual.exitCode===0&&actual.terminationSignal===0&&actual.launchErrno===0&&
    actual.processLaunched===true&&actual.processExited===true&&actual.processGroupAbsent===true&&actual.resourcesReleased===true&&
    actual.cancelRequested===false&&actual.timedOut===false&&actual.sourceCheckStatus==='pre_exec_manifest_verified'&&
    !Object.hasOwn(actual,'firstNodeWebOutput');
  if(!proved)return undefined;
  return actual.firstNodeWebOutputLimitation==='nested_vite_output'&&sha(route.existingNodeWebVersion)&&
    !Object.hasOwn(route,'editedNodeWebVersion')?'nested_vite_output':'unverified';
}

/** A locator for the original native journal. This projection grants no input or stop authority. */
export function projectExecutionRows(task: ProjectExecutionData, operation?: ProjectExecutionData): ProjectExecutionRow[] {
  const raw = object(task.raw);
  if (raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin || !raw.developmentContext && !raw.ownerLocalTerminal) return [];
  const source: ProjectExecutionData[] = operation ? [operation] : Array.isArray(raw.operations) ? raw.operations : [];
  return source.flatMap(row=>{
    if(row.tool!=='project_execute'||!operationId(row.operationId))return [];
    const receipt=object(row.receipt),route=object(receipt.projectExecution),context=object(raw.developmentContext);
    if(row.state==='receipt'&&receipt.status==='not_dispatched'&&receipt.accepted===false&&!Object.prototype.hasOwnProperty.call(receipt,'projectExecution'))return [];
    const command=route.authority==='native_host_operation'&&route.taskId===task.id&&route.runId===row.runId&&route.operationId===row.operationId?route.command:undefined;
    // A fixed package, probe or edit is still in process history, but is not a terminal command.
    if(typeof command==='string'&&!['project.runtime.run','termux.run'].includes(command))return [];
    const termux=context.runtime==='termux'||command==='termux.run';
    return [{...row,operationId:row.operationId,routeKind:termux?'termux' as const:'managed' as const,
      interactive:!termux&&row.runId===raw.runId&&object(receipt.executionReceipt).terminalMode==='pty'}];
  });
}

export function projectExecutionCanInput(metadata: ProjectExecutionData, output: ProjectExecutionData, accepted: boolean, uncertain: boolean, stopping: boolean) {
  return accepted && metadata.observingOnly === false && metadata.terminalMode === 'pty' && output.nativeStatus === 'running' &&
    !output.terminal && !output.outputTruncated && !output.logIncompleteTail && !output.screen?.gap && !output.inputUncertain && !metadata.inputUncertain &&
    !Number(metadata.pendingInputCount || 0) && !uncertain && !stopping;
}

export function projectExecutionShouldRead(output: ProjectExecutionData) {
  // A writer can still be completing the last record. Keep observing the same
  // cursor while its process is live; an incomplete tail is not an end marker.
  return output.terminal === true ? output.hasMore === true : ['queued', 'starting', 'preparing', 'running', 'stopping'].includes(output.nativeStatus);
}

/** Same display-only rule as native LocalWorkbenchGuard.pollable. Cold unknown receipts never recover execution or a polling loop. */
export const localReceiptPollable = (status: string, termux = false) => termux ? !['completed', 'failed'].includes(status) : ['queued', 'running', 'stopping'].includes(status);

/** Original runtime activity and original observation-window phases only. */
export function projectExecutionActivity(activity: ProjectExecutionData, execution: string) {
  if (activity.format !== 'pocketworld.runtime-activity.v1' || activity.operationId !== execution) return '';
  const state = ({ running: '原进程运行中', active_output: '原执行有输出更新', inferred_idle: '原执行暂时静默；仅为静默推测，不证明正在等输入或已经完成', starting: '等待原进程启动确认', preparing: '原工具仍在准备', stopping: '原执行已请求停止，仍需真实清场回执', awaiting_resource_release: '已观察原进程退出，仍在核对资源释放', exited: '原进程退出与资源释放已核对；业务结果仍需验收', not_launched: '原回执确认未创建子进程' } as Record<string, string>)[activity.state] || '原进程状态待核；只读日志，不自动重放';
  const phase = activity.observationWindow?.phase === 'foreground_wait' ? '原批准的前台观察时窗内。' : activity.observationWindow?.phase === 'background_observation' ? '前台时窗已到，仅观察同一个原执行，总期限未延长。' : '';
  const input = ({ delivered: ' 原输入已取得字节 ACK，未证明程序已经处理。', not_dispatched: ' 上次原输入确认未发送。', unknown: ' 原输入结果待核，只查询原操作，不重发。' } as Record<string, string>)[activity.input?.state] || '';
  return phase + state + input;
}

