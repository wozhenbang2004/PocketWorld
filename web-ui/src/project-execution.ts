export type ProjectExecutionData = Record<string, any>;
export type ProjectExecutionRow = ProjectExecutionData & { operationId: string; interactive: boolean; routeKind: 'managed'|'termux'; failureStage?:'dependencies'|'build' };
const object = (value: any): ProjectExecutionData => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const operationId = (value: unknown): value is string => typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);

export type OriginalManagedNodePending = {taskId:string;runId:string;epoch:number;operationId:string;
  projectId:string;checkoutId:string;phase:'build'|'dependencies';canObserve:boolean;canCheck:boolean};

export function reviewedManagedNodePending(taskId:string, row:ProjectExecutionData):OriginalManagedNodePending|undefined {
  const pin=object(row.originalManagedNodePending);
  if(pin.format!=='pocketworld.original-managed-node-pending.v1'||!operationId(taskId)||pin.taskId!==taskId||
    row.tool!=='project_execute'||row.state!=='unknown'||!operationId(pin.runId)||row.runId!==pin.runId||
    !operationId(pin.operationId)||row.operationId!==pin.operationId||!operationId(pin.projectId)||!operationId(pin.checkoutId)||
    !Number.isSafeInteger(pin.epoch)||pin.epoch<1||(pin.phase!=='build'&&pin.phase!=='dependencies')||
    typeof pin.canObserve!=='boolean')return undefined;
  return {taskId,runId:pin.runId,epoch:pin.epoch,operationId:pin.operationId,projectId:pin.projectId,
    checkoutId:pin.checkoutId,phase:pin.phase,canObserve:pin.canObserve,canCheck:pin.canObserve===false&&pin.canCheck===true};
}

export const sameManagedNodeOperation=(row:ProjectExecutionData,pin:OriginalManagedNodePending)=>
  row.tool==='project_execute'&&row.operationId===pin.operationId&&row.runId===pin.runId;

/** A review is a locator, not a live result. Keep only the same visible native row. */
export function currentManagedNodeOperation(task:ProjectExecutionData|undefined,pin:OriginalManagedNodePending):ProjectExecutionData|undefined {
  const raw=object(task?.raw),scope=object(raw.developmentContext),build=object(scope.ownerDraftFirstBuild);
  if(task?.id!==pin.taskId||raw.id!==pin.taskId||raw.actorId!=='owner'||raw.spaceId!=='private'||raw.origin||
    raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||raw.kind!=='assistant'||raw.executionMode!=='capabilities'||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.runId!==pin.runId||raw.coordinatorEpoch!==pin.epoch||scope.runtime!=='managed_process_v1'||
    scope.projectId!==pin.projectId||scope.checkoutId!==pin.checkoutId||build.projectId!==pin.projectId||build.checkoutId!==pin.checkoutId||
    !Array.isArray(raw.operations))return undefined;
  return raw.operations.map(object).find((row:ProjectExecutionData)=>sameManagedNodeOperation(row,pin)&&
    (row.state==='unknown'||row.state==='receipt'));
}

export type BareManagedNodeCheck = OriginalManagedNodePending & {sourceDigest:string;planDigest:string};

/** The native review permits a new read of this exact unconfirmed row, not a log or dispatch. */
export function bareManagedNodeCheck(task:ProjectExecutionData|undefined,pin:OriginalManagedNodePending):BareManagedNodeCheck|undefined {
  const raw=object(task?.raw),scope=object(raw.developmentContext),build=object(scope.ownerDraftFirstBuild);
  const row=pin.canCheck&&!pin.canObserve?currentManagedNodeOperation(task,pin):undefined;
  const receipt=object(row?.receipt),route=object(receipt.projectExecution),args=object(row?.arguments);
  const sourceDigest=build.sourceDigest,planDigest=pin.phase==='build'?build.planDigest:object(build.lockedDependencies).planDigest;
  if(row?.state!=='unknown'||raw.cancelRequested!==false||build.existingCheckoutBuild!==true||scope.draftOnly===true||
    receipt.code!=='project_pending'||receipt.status!=='unknown'||receipt.accepted!==null||receipt.executionStatus!=='dispatching'||
    Object.hasOwn(receipt,'executionReceipt')||route.cancelRequested===true||args.stepId!==pin.phase||args.planDigest!==planDigest||
    typeof sourceDigest!=='string'||!/^[a-f0-9]{64}$/.test(sourceDigest)||scope.initialSourceDigest!==sourceDigest||
    typeof planDigest!=='string'||!/^[a-f0-9]{64}$/.test(planDigest))return undefined;
  const binding={authority:'native_host_operation',command:'project.runtime.run',runtimeId:'managed_process_v1',
    taskId:pin.taskId,runId:pin.runId,coordinatorEpoch:pin.epoch,operationId:pin.operationId,jobId:pin.operationId,
    projectId:pin.projectId,checkoutId:pin.checkoutId,sourceDigest,planDigest,stepId:pin.phase,intent:pin.phase};
  if(Object.entries(binding).some(([name,value])=>route[name]!==value))return undefined;
  return {...pin,sourceDigest,planDigest};
}

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

const compilerOutputFormat = 'pocketworld.owner-node-compiler-output.v1';
const compilerUuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const compilerDigest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const positiveNumber = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const directCompilerFormat = 'pocketworld.owner-direct-node-build.v1';
const directCompilerPath = (value: unknown): value is string =>
  typeof value === 'string' && /^dist\/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\.js$/.test(value);

function originalDirectNodeScope(task: ProjectExecutionData | undefined) {
  const raw = object(task?.raw), context = object(raw.developmentContext), scope = object(raw.ownerDirectNodeBuild), id = task?.id;
  if (!compilerUuid(id) || raw.id !== id || raw.runId !== id || raw.operationId !== id || raw.coordinatorEpoch !== 1 ||
      raw.status !== task?.status || raw.kind !== 'tool' || raw.feature !== 'local' || raw.command !== 'project.nodeBuild.owner' ||
      raw.nativeExecutionMode !== 'OWNER_DIRECT_NODE_BUILD' || raw.executionMode !== 'capabilities' ||
      raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin != null || raw.conversationId != null && raw.conversationId !== '' ||
      raw.parentTaskId != null || raw.teamRunId != null || raw.memberId != null || raw.teamAssignment != null ||
      raw.executionRole === 'owner_work' || raw.ownerLocalTerminal != null || raw.ownerDeviceWork != null ||
      raw.workProfileRef != null || raw.workProfileIntent != null || raw.developmentSession != null ||
      raw.contextAvailable === false || raw.contextWithdrawn === true || raw.sourceCurrentReadRequired === true ||
      raw.taskProjection !== 'detail_window' || raw.taskCoreReadLimited !== false || raw.auditBodyIncluded !== true ||
      scope.format !== directCompilerFormat || scope.requestId !== id || scope.runId !== id || scope.buildOperationId !== id ||
      scope.epoch !== 1 || scope.modelDispatchAllowed !== false || scope.replayAllowed !== false ||
      !operationId(scope.projectId) || !operationId(scope.checkoutId) || !directCompilerPath(scope.outputPath) ||
      ![scope.sourceDigest,scope.planDigest,scope.runtimeIdentity,scope.lockSha256].every(compilerDigest) ||
      scope.runtimeId !== 'managed_process_v1' || context.runtime !== scope.runtimeId ||
      context.format !== 'pocketworld.development-context.v1' || context.draftOnly === true ||
      context.ownerDraftFirstBuild != null || context.clientMessageId != null ||
      context.projectId !== scope.projectId || context.checkoutId !== scope.checkoutId || context.initialSourceDigest !== scope.sourceDigest) return;
  return {taskId:id,runId:id,epoch:1,buildOperationId:id,projectId:scope.projectId,checkoutId:scope.checkoutId,
    sourceDigest:scope.sourceDigest,planDigest:scope.planDigest,runtimeIdentity:scope.runtimeIdentity,outputPath:scope.outputPath};
}

export type DirectNodeExecution = {phase:'unverified'} | {
  phase:'waiting'|'stopping'|'uncertain'|'released'|'releasedAfterStop';
  pin:NonNullable<ReturnType<typeof originalDirectNodeScope>> & {requestDigest:string;approvalId:string};
  checkReceipt:boolean;
};

/** The direct Tool Work opens on registration, before the process's ended
 * receipt. Do not turn its original dispatch claim into a log reader. */
export function originalDirectNodeExecution(task: ProjectExecutionData | undefined, selected: ProjectExecutionData): DirectNodeExecution | undefined {
  const raw = object(task?.raw);
  if (raw.kind !== 'tool' || raw.command !== 'project.nodeBuild.owner' && raw.nativeExecutionMode !== 'OWNER_DIRECT_NODE_BUILD' &&
      raw.ownerDirectNodeBuild?.format !== directCompilerFormat) return;
  const scope = originalDirectNodeScope(task);
  if (!scope || selected.tool !== 'project_execute' || selected.runId !== scope.runId ||
      selected.operationId !== scope.buildOperationId || !Array.isArray(raw.operations)) return {phase:'unverified'};
  const rows = (raw.operations as unknown[]).map(object).filter(row => row.tool === 'project_execute' && row.runId === scope.runId);
  const row = rows[0], receipt = object(row?.receipt), route = object(receipt.projectExecution), actual = object(receipt.executionReceipt);
  if (rows.length !== 1 || row.operationId !== scope.buildOperationId || row.bodyAvailable === false || row.readLimited === true ||
      row.arguments?.stepId !== 'build' || row.arguments?.planDigest !== scope.planDigest ||
      !compilerDigest(route.directOwnerNodeCompilerVersion) || !compilerDigest(route.requestDigest) ||
      !compilerUuid(route.approvalId) || !compilerDigest(route.capabilityFingerprint) || route.jobId !== scope.buildOperationId ||
      Object.hasOwn(route,'existingNodeWebVersion') || Object.hasOwn(route,'editedNodeWebVersion')) return {phase:'unverified'};
  const binding = {authority:'native_host_operation',command:'project.runtime.run',runtimeId:'managed_process_v1',runtimeIdentity:scope.runtimeIdentity,
    taskId:scope.taskId,runId:scope.runId,coordinatorEpoch:scope.epoch,operationId:scope.buildOperationId,
    projectId:scope.projectId,checkoutId:scope.checkoutId,sourceDigest:scope.sourceDigest,planDigest:scope.planDigest,
    requestDigest:route.requestDigest,stepId:'build',intent:'build'};
  if (!Object.entries(binding).every(([name,value])=>route[name]===value)) return {phase:'unverified'};
  const pin = {...scope,requestDigest:route.requestDigest,approvalId:route.approvalId};
  const stopping = raw.cancelRequested === true || route.cancelRequested === true;
  const unfinalized = ['running','stopping','reconciling','interrupted'].includes(raw.status);
  if (row.state === 'unknown' && ['project_pending','project_cancel_pending_unknown'].includes(receipt.code) &&
      receipt.status === 'unknown' && receipt.accepted === null && receipt.replayAllowed === false)
    return {phase:stopping ? 'stopping' : raw.status==='running' ? 'waiting' : 'uncertain',pin,checkReceipt:unfinalized};
  if (row.state !== 'receipt' || receipt.code !== 'project_process_receipt' || receipt.accepted !== true ||
      !['completed','failed'].includes(actual.status) || receipt.status !== actual.status || receipt.executionStatus !== actual.status ||
      actual.provider !== 'managed_process' || actual.kind !== 'command' || actual.runMode !== 'foreground' ||
      actual.id !== scope.buildOperationId || actual.operationId !== scope.buildOperationId ||
      actual.checkoutId !== scope.checkoutId || actual.sourceDigest !== scope.sourceDigest ||
      actual.receiptReceived !== true || actual.resourcesReleased !== true ||
      !Object.entries(binding).every(([name,value])=>actual.projectExecution?.[name]===value)) return {phase:'unverified'};
  return {phase:stopping ? 'releasedAfterStop' : 'released',pin,checkReceipt:unfinalized || raw.status === 'awaiting_user'};
}

export function directNodeLogPin(view:DirectNodeExecution|undefined) {
  return view?.phase === 'released' || view?.phase === 'releasedAfterStop' ? view.pin : undefined;
}

export type DirectNodeExecutionPin=NonNullable<ReturnType<typeof directNodeLogPin>>;
export function sameDirectNodeExecution(view:DirectNodeExecution|undefined,pin:DirectNodeExecutionPin) {
  return view && 'pin' in view && Object.entries(pin).every(([name,value])=>view.pin[name as keyof DirectNodeExecutionPin]===value) ? view : undefined;
}

const directDependencyFormat='pocketworld.owner-direct-node-dependencies.v1';
export type DirectNodeDependencyPin = {
  taskId:string;runId:string;epoch:1;dependencyOperationId:string;projectId:string;checkoutId:string;
  sourceDigest:string;planDigest:string;runtimeIdentity:string;lockSha256:string;managerVersion:string;packages:number;
  requestDigest:string;approvalId:string;
};
type DependencyExit = {kind:'exit_code'|'signal'|'no_child';value:number};
type DirectNodeDependencyExecution = {phase:'unverified'} |
  {phase:'pending';pin:DirectNodeDependencyPin;canCheck:boolean} |
  {phase:'completed'|'failed'|'paused';pin:DirectNodeDependencyPin;exit:DependencyExit;nativeStatus:'completed'|'failed'};

/** The dependency command is its own private native Work. Unknown admission
 * is neither a terminal/log handle nor proof packages are ready for a build. */
export function originalDirectNodeDependencyExecution(task:ProjectExecutionData|undefined,shown?:ProjectExecutionData):DirectNodeDependencyExecution|undefined {
  const raw=object(task?.raw),context=object(raw.developmentContext),scope=object(raw.ownerDirectNodeDependencies),id=task?.id;
  if(raw.command!=='project.nodeDependencies.owner'&&raw.nativeExecutionMode!=='OWNER_DIRECT_NODE_DEPENDENCIES'&&
    scope.format!==directDependencyFormat)return;
  if(!compilerUuid(id)||raw.id!==id||raw.runId!==id||raw.operationId!==id||raw.coordinatorEpoch!==1||
    raw.status!==task?.status||raw.kind!=='tool'||raw.feature!=='local'||raw.command!=='project.nodeDependencies.owner'||
    raw.nativeExecutionMode!=='OWNER_DIRECT_NODE_DEPENDENCIES'||raw.executionMode!=='capabilities'||raw.actorId!=='owner'||raw.spaceId!=='private'||
    raw.origin!=null||raw.conversationId!=null&&raw.conversationId!==''||raw.parentTaskId!=null||raw.teamRunId!=null||raw.memberId!=null||
    raw.teamAssignment!=null||raw.workProfileRef!=null||raw.workProfileIntent!=null||raw.developmentSession!=null||
    raw.ownerDirectNodeBuild!=null||raw.ownerLocalTerminal!=null||raw.ownerDeviceWork!=null||raw.executionRole==='owner_work'||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||
    raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||raw.auditBodyIncluded!==true||
    scope.format!==directDependencyFormat||scope.requestId!==id||scope.runId!==id||scope.epoch!==1||scope.dependencyOperationId!==id||
    scope.modelDispatchAllowed!==false||scope.replayAllowed!==false||scope.runtimeId!=='managed_process_v1'||
    !operationId(scope.projectId)||!operationId(scope.checkoutId)||![scope.sourceDigest,scope.planDigest,scope.runtimeIdentity,scope.lockSha256].every(compilerDigest)||
    scope.manager!=='npm'||typeof scope.managerVersion!=='string'||!/^11\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$/.test(scope.managerVersion)||
    scope.lockPath!=='package-lock.json'||scope.registry!=='https://registry.npmjs.org/'||!positiveNumber(scope.packages)||scope.packages>8192||
    context.format!=='pocketworld.development-context.v1'||context.draftOnly===true||context.ownerDraftFirstBuild!=null||context.clientMessageId!=null||
    context.runtime!==scope.runtimeId||context.projectId!==scope.projectId||context.checkoutId!==scope.checkoutId||
    context.initialSourceDigest!==scope.sourceDigest||!Array.isArray(raw.operations)||
    shown&&(shown.tool!=='project_execute'||shown.runId!==id||shown.operationId!==id||shown.bodyAvailable!==true||shown.readLimited!==false))
    return {phase:'unverified'};
  const rows=(raw.operations as unknown[]).map(object).filter(row=>row.tool==='project_execute'&&row.runId===id);
  const row=rows[0],receipt=object(row?.receipt),route=object(receipt.projectExecution),actual=object(receipt.executionReceipt);
  if(rows.length!==1||row.operationId!==id||row.bodyAvailable===false||row.readLimited===true||
    row.arguments?.stepId!=='dependencies'||row.arguments?.planDigest!==scope.planDigest||
    !compilerDigest(route.requestDigest)||!compilerUuid(route.approvalId)||!compilerDigest(route.capabilityFingerprint)||route.jobId!==id||
    ['directOwnerNodeCompilerVersion','existingNodeWebVersion','editedNodeWebVersion'].some(key=>Object.hasOwn(route,key)))
    return {phase:'unverified'};
  const binding={authority:'native_host_operation',command:'project.runtime.run',runtimeId:scope.runtimeId,runtimeIdentity:scope.runtimeIdentity,
    taskId:id,runId:id,coordinatorEpoch:1,operationId:id,projectId:scope.projectId,checkoutId:scope.checkoutId,
    sourceDigest:scope.sourceDigest,planDigest:scope.planDigest,requestDigest:route.requestDigest,stepId:'dependencies',intent:'dependencies'};
  const matches=(value:ProjectExecutionData)=>Object.entries(binding).every(([key,expected])=>value[key]===expected);
  if(!matches(route))return {phase:'unverified'};
  const pin:DirectNodeDependencyPin={taskId:id,runId:id,epoch:1,dependencyOperationId:id,projectId:scope.projectId,checkoutId:scope.checkoutId,
    sourceDigest:scope.sourceDigest,planDigest:scope.planDigest,runtimeIdentity:scope.runtimeIdentity,lockSha256:scope.lockSha256,
    managerVersion:scope.managerVersion,packages:scope.packages,requestDigest:route.requestDigest,approvalId:route.approvalId};
  const unfinalized=['running','stopping','reconciling','interrupted'].includes(raw.status);
  // A nonterminal provider observation can be nested in this envelope. It
  // never changes the pending action into a log handle or a result.
  if(row.state==='unknown'&&['project_pending','project_cancel_pending_unknown'].includes(receipt.code)&&receipt.status==='unknown'&&
    receipt.accepted===null&&receipt.replayAllowed===false)
    return {phase:'pending',pin,canCheck:unfinalized};
  if(row.state!=='receipt'||receipt.code!=='project_process_receipt'||receipt.accepted!==true||
    !['completed','failed'].includes(actual.status)||receipt.status!==actual.status||receipt.executionStatus!==actual.status||
    actual.provider!=='managed_process'||actual.kind!=='command'||actual.runMode!=='foreground'||actual.requestedExecutable!=='npm'||
    !Array.isArray(actual.requestedArgv)||actual.requestedArgv[0]!=='ci'||actual.terminalMode==='pty'||
    actual.id!==id||actual.operationId!==id||actual.checkoutId!==scope.checkoutId||actual.sourceDigest!==scope.sourceDigest||
    actual.receiptReceived!==true||actual.resourcesReleased!==true||actual.processGroupAbsent!==true||actual.ownedWorkerLive!==false||
    typeof actual.cancelRequested!=='boolean'||typeof actual.timedOut!=='boolean'||!positiveNumber(actual.finishedAt)||
    actual.sourceCheckStatus!=='pre_exec_manifest_verified'||!matches(object(actual.projectExecution)))
    return {phase:'unverified'};
  let exit:DependencyExit|undefined;
  if(actual.launchPhase==='exit_observed'&&actual.processLaunched===true&&actual.processExited===true&&actual.noChildCreated===false&&actual.launchErrno===0){
    if(actual.terminationSignal===0&&Number.isInteger(actual.exitCode)&&actual.exitCode>=0&&actual.exitCode<=255)
      exit={kind:'exit_code',value:actual.exitCode};
    if(actual.exitCode===-1&&Number.isInteger(actual.terminationSignal)&&actual.terminationSignal>0&&actual.terminationSignal<=127)
      exit={kind:'signal',value:actual.terminationSignal};
  }else if(actual.launchPhase==='no_child_created'&&actual.processLaunched===false&&actual.processExited===false&&actual.noChildCreated===true&&
    actual.exitCode===-1&&actual.terminationSignal===0&&positiveNumber(actual.launchErrno)&&actual.launchErrno<=2147483647&&
    actual.timedOut===false&&actual.outputComplete===true)exit={kind:'no_child',value:actual.launchErrno};
  const nativeStatus=exit?.kind==='exit_code'&&exit.value===0&&!actual.cancelRequested&&!actual.timedOut?'completed':'failed';
  if(!exit||nativeStatus!==actual.status)return {phase:'unverified'};
  if(unfinalized||raw.status==='awaiting_user')return {phase:'pending',pin,canCheck:true};
  const phase=raw.cancelRequested===true?'paused':nativeStatus;
  if(raw.hasUnresolvedOperations!==false||raw.status!==phase)return {phase:'unverified'};
  return {phase,pin,exit,nativeStatus};
}

export function sameDirectNodeDependency(view:DirectNodeDependencyExecution|undefined,pin:DirectNodeDependencyPin) {
  return view&&'pin' in view&&Object.entries(pin).every(([key,value])=>view.pin[key as keyof DirectNodeDependencyPin]===value)?view:undefined;
}

export type OriginalNodeCompilerOutput = {
  format: typeof compilerOutputFormat; taskId: string; runId: string; epoch: number; buildOperationId: string;
  projectId: string; checkoutId: string; sourceDigest: string; planDigest: string; requestDigest: string; runtimeIdentity: string;
  checkoutSequence: number; path: string; bytes: number; sha256: string; beforeSha256: string;
};
const compilerWitnessFields = ['format','taskId','runId','epoch','buildOperationId','projectId','checkoutId','sourceDigest',
  'planDigest','requestDigest','runtimeIdentity','checkoutSequence','path','bytes','sha256','beforeSha256'] as const;
function compilerOutputWitness(value: unknown): OriginalNodeCompilerOutput | undefined {
  const pin = object(value);
  if (pin.format !== compilerOutputFormat || !compilerUuid(pin.taskId) || !compilerUuid(pin.runId) ||
      !positiveNumber(pin.epoch) || pin.epoch > 2147483647 || !operationId(pin.buildOperationId) || !operationId(pin.projectId) ||
      !operationId(pin.checkoutId) || !positiveNumber(pin.checkoutSequence) || !positiveNumber(pin.bytes) || pin.bytes > 256 * 1024 ||
      !directCompilerPath(pin.path) ||
      ![pin.sourceDigest,pin.planDigest,pin.requestDigest,pin.runtimeIdentity,pin.sha256].every(compilerDigest) ||
      (pin.beforeSha256 !== 'absent' && !compilerDigest(pin.beforeSha256)) || pin.beforeSha256 === pin.sha256) return;
  return Object.fromEntries(compilerWitnessFields.map(name => [name, pin[name]])) as OriginalNodeCompilerOutput;
}

/** Only the current original private tool row can offer a read. The native
 * journal, not this exit witness, authorizes review and confirms publication. */
export function originalNodeCompilerOutput(task: ProjectExecutionData | undefined, row: ProjectExecutionData): OriginalNodeCompilerOutput | undefined {
  const raw = object(task?.raw), scope = object(raw.developmentContext), build = object(scope.ownerDraftFirstBuild);
  const receipt = object(row.receipt), actual = object(receipt.executionReceipt);
  const pin = compilerOutputWitness(actual.nodeCompilerOutput);
  if (!pin || Object.keys(actual.nodeCompilerOutput).length !== compilerWitnessFields.length ||
      task?.id !== pin.taskId || raw.id !== pin.taskId || raw.runId !== pin.runId || raw.coordinatorEpoch !== pin.epoch ||
      raw.executionMode !== 'capabilities' || raw.actorId !== 'owner' || raw.spaceId !== 'private' ||
      raw.origin != null || raw.parentTaskId != null || raw.teamRunId != null || raw.memberId != null ||
      raw.executionRole === 'owner_work' || raw.ownerLocalTerminal != null || raw.ownerDeviceWork != null ||
      raw.workProfileRef != null || raw.workProfileIntent != null || raw.developmentSession != null ||
      raw.contextAvailable === false || raw.contextWithdrawn === true || raw.sourceCurrentReadRequired === true ||
      raw.taskProjection !== 'detail_window' || raw.taskCoreReadLimited !== false || raw.auditBodyIncluded !== true ||
      raw.cancelRequested !== false || raw.hasUnresolvedOperations !== false || raw.status !== task?.status ||
      !['completed','failed','needs_confirmation','awaiting_user'].includes(raw.status) ||
      scope.format !== 'pocketworld.development-context.v1' || scope.runtime !== 'managed_process_v1' || scope.draftOnly === true ||
      scope.projectId !== pin.projectId || scope.checkoutId !== pin.checkoutId || scope.initialSourceDigest !== pin.sourceDigest ||
      row.tool !== 'project_execute' || row.runId !== pin.runId || row.state !== 'receipt' || row.operationId !== pin.buildOperationId ||
      row.bodyAvailable !== true || row.readLimited !== false || row.arguments?.stepId !== 'build' || row.arguments?.planDigest !== pin.planDigest ||
      receipt.code !== 'project_process_receipt' || receipt.accepted !== true || receipt.status !== 'completed' || receipt.executionStatus !== 'completed' ||
      actual.provider !== 'managed_process' || actual.kind !== 'command' || actual.requestedExecutable !== 'npm' ||
      !Array.isArray(actual.requestedArgv) || actual.requestedArgv.length !== 2 || actual.requestedArgv[0] !== 'run' || actual.requestedArgv[1] !== 'build' ||
      actual.id !== pin.buildOperationId || actual.operationId !== pin.buildOperationId || actual.checkoutId !== pin.checkoutId ||
      actual.sourceDigest !== pin.sourceDigest || actual.checkoutSequence !== pin.checkoutSequence || actual.status !== 'completed' ||
      actual.runMode !== 'foreground' || actual.launchPhase !== 'exit_observed' || actual.sourceCheckStatus !== 'pre_exec_manifest_verified' ||
      actual.exitCode !== 0 || actual.terminationSignal !== 0 || actual.launchErrno !== 0 ||
      !['receiptReceived','processLaunched','processExited','processGroupAbsent','resourcesReleased','outputComplete'].every(name => actual[name] === true) ||
      !['ownedWorkerLive','cancelRequested','timedOut','outputTruncated','noChildCreated'].every(name => actual[name] === false)) return;
  const route = object(receipt.projectExecution), original = object(actual.projectExecution);
  const binding = {
    authority: 'native_host_operation', command: 'project.runtime.run', runtimeId: scope.runtime, runtimeIdentity: pin.runtimeIdentity,
    taskId: pin.taskId, runId: pin.runId, coordinatorEpoch: pin.epoch, operationId: pin.buildOperationId, projectId: pin.projectId,
    checkoutId: pin.checkoutId, planDigest: pin.planDigest, sourceDigest: pin.sourceDigest, requestDigest: pin.requestDigest, stepId: 'build', intent: 'build',
  };
  if (!Object.entries(binding).every(([name, value]) => route[name] === value && original[name] === value)) return;
  if (raw.kind === 'assistant') {
    return build.existingCheckoutBuild === true && build.manager === 'npm' && build.lockedDependencies?.review?.manager === 'npm' &&
      build.projectId === pin.projectId && build.checkoutId === pin.checkoutId && build.sourceDigest === pin.sourceDigest &&
      build.planDigest === pin.planDigest && build.runtimeIdentity === pin.runtimeIdentity &&
      compilerDigest(route.existingNodeWebVersion) && !Object.hasOwn(route,'editedNodeWebVersion') ? pin : undefined;
  }
  // A model-free owner build has its own native Work, never the assistant pin
  // or a shell's exit. The direct version marker belongs to the SQL route.
  const direct = directNodeLogPin(originalDirectNodeExecution(task,row));
  return raw.status === 'completed' && direct?.outputPath === pin.path && direct.requestDigest === pin.requestDigest &&
    direct.planDigest === pin.planDigest && direct.runtimeIdentity === pin.runtimeIdentity ? pin : undefined;
}

export const sameNodeCompilerOutput = (shown: OriginalNodeCompilerOutput, actual: OriginalNodeCompilerOutput | undefined) =>
  !!actual && compilerWitnessFields.every(name => shown[name] === actual[name]);
export type NodeCompilerOutputReply =
  { state: 'ready'; reviewHandle: string; expiresAt: number } | { state: 'pending' } | { state: 'saved'; artifactId: string };

export function nodeCompilerOutputReply(value: unknown, original: OriginalNodeCompilerOutput): NodeCompilerOutputReply | undefined {
  const result = object(value);
  if (!sameNodeCompilerOutput(original, compilerOutputWitness(value))) return;
  const fields = Object.keys(result).length - compilerWitnessFields.length;
  if (result.state === 'ready' && fields === 3 && compilerUuid(result.reviewHandle) &&
      positiveNumber(result.expiresAt) && result.expiresAt > Date.now())
    return { state: 'ready', reviewHandle: result.reviewHandle, expiresAt: result.expiresAt };
  if (result.state === 'pending' && fields === 1) return { state: 'pending' };
  if (result.state === 'saved' && fields === 2 && compilerUuid(result.artifactId)) return { state: 'saved', artifactId: result.artifactId };
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

