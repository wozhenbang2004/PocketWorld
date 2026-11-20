export type DeviceData = Record<string, any>;
const object = (value: unknown): value is DeviceData => !!value && typeof value === 'object' && !Array.isArray(value);
const identity = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
const positiveInteger = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) > 0;
const digest = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
export const deviceRows = (value: unknown): DeviceData[] => Array.isArray(value) ? value.filter(object) : [];
export const deviceStrings = (value: unknown): string[] => Array.isArray(value) ? value.filter(item => typeof item === 'string') : [];

export function deviceSnapshot(value: unknown): DeviceData {
  if (!object(value) || value.format !== 'pocketworld.device-workspace.v1' || !positiveInteger(value.capturedAt) || !object(value.local) || !Array.isArray(value.peers) || !value.peers.every(object) || !Array.isArray(value.pendingPairings) || !value.pendingPairings.every(object)) throw new Error('原设备状态尚未完整读取，不能推断已经连接或授权。');
  return value;
}
export function deviceReceipt(value: unknown): { snapshot: DeviceData | null; value: DeviceData } {
  if (!object(value) || !object(value.value)) throw new Error('原设备操作没有返回可核对的回执。');
  return { snapshot: value.snapshot == null ? null : deviceSnapshot(value.snapshot), value: value.value };
}
export function deviceReview(value: unknown): DeviceData {
  if (!object(value) || !identity(value.viewHandle) || !object(value.view) || !['pair', 'grant', 'connection', 'relay', 'goal', 'action', 'abandon', 'recovery', 'result', 'approval', 'resume', 'history'].includes(value.kind)) throw new Error('原设备审阅身份不完整。');
  const view = value.view;
  if (value.kind === 'pair' && (!identity(view.inviteId) || !positiveInteger(view.expiresAt) || !['inviterFingerprint', 'joinerFingerprint'].every(key => digest(view[key])))) throw new Error('缺少原邀请或完整双端指纹，不能确认配对。');
  if (['grant', 'connection'].includes(value.kind) && (!identity(view.deviceId) || !digest(view.certificateFingerprint))) throw new Error('缺少原设备身份与完整证书指纹。');
  if (view.grantReview && (!object(view.grantReview) || !digest(view.grantReview.digest) || !positiveInteger(view.grantReview.expiresAt) || !Array.isArray(view.grantReview.capabilities) || !Array.isArray(view.grantReview.resources))) throw new Error('原生准确权限审阅不完整。');
  if (['goal', 'result', 'approval', 'resume', 'history'].includes(value.kind) && !identity(view.taskId)) throw new Error('审阅缺少原任务身份。');
  if (['result', 'approval', 'resume'].includes(value.kind) && (!identity(view.runId) || !positiveInteger(view.executorEpoch))) throw new Error('审阅缺少原执行与代次。');
  if (value.kind === 'result' && (!digest(view.resultDigest) || view.historicalReviewOnly !== true || view.nativeRunnerExited !== true || view.nativeDispatchClosed !== true)) throw new Error('原执行尚未提供已结束的准确成果审阅。');
  if (value.kind === 'approval' && (!identity(view.approvalId) || !digest(view.argumentsHash))) throw new Error('原操作审批或准确参数摘要缺失。');
  if (value.kind === 'resume' && (!identity(view.resumeRequestId) || !positiveInteger(view.reviewRevision) || view.ownerActionRequired !== true || view.newRun !== false || view.newGrant !== false)) throw new Error('缺少这次原执行的恢复请求。');
  if (value.kind === 'history' && (!identity(view.operationId) || !identity(view.targetDeviceId) || !identity(view.instanceId) || !digest(view.argumentsDigest) || !object(view.resourceBinding) || view.readOnly !== true || view.newRun !== false || view.retrySubmitted !== false || view.unknownRefunded !== false)) throw new Error('原操作的设备、实例或只读范围不完整。');
  return value;
}
export function deviceTaskDetails(value: unknown, taskId: string): DeviceData {
  if (!object(value) || value.kind !== 'taskDetails' || value.taskId !== taskId || !object(value.actions) || !positiveInteger(value.actions.capturedAt) || !object(value.history) || !Array.isArray(value.history.links) || !value.history.links.every((link: unknown) => object(link) && link.parentTaskId === taskId && identity(link.operationId) && identity(link.targetDeviceId))) throw new Error('原设备任务关联回执不完整或任务已变化。');
  return value;
}
export const PHONE_ACTION_WINDOW_MS = 30_000;
export function isReceivedPhoneTask(task: DeviceData, snapshot: DeviceData | null): boolean {
  const raw=task.raw, origin=raw?.origin, scope=raw?.deviceExecutionScope, resource=scope?.resourceBinding;
  const appOnly=raw?.kind==='assistant' && (['main_screen','background'].includes(raw.receivedZeroToolAppScope)
    || raw.deviceExecutionKind==='GOAL' && scope?.executionKind==='GOAL' && scope.toolLimit===0 && resource?.screenMode===scope.mode
      && ['main_screen','background'].includes(scope.mode) && Number.isSafeInteger(resource.androidUserId) && Array.isArray(resource.packageNames) && resource.packageNames.length>0);
  return object(origin) && origin.kind === 'paired_device' && identity(origin.callerDeviceId) && origin.callerDeviceId === origin.originDeviceId
    && identity(origin.targetDeviceId) && origin.targetDeviceId === snapshot?.local?.deviceId && identity(origin.remoteOperationId) && identity(origin.grantId)
    && (['main_screen','background'].includes(task.raw.executionMode) || task.raw.executionMode === 'capabilities'
      && (appOnly || origin.textReturnContract === 'pocketworld.direct-text.owner-reviewed-result.v1' && origin.rootTaskId === origin.remoteOperationId
        && origin.parentTaskId === origin.remoteOperationId && origin.remoteLocalTaskId === origin.remoteOperationId
        || origin.textReturnContract === 'pocketworld.original-phone-allocation.owner-reviewed-text.v1' && identity(origin.rootTaskId)
          && origin.rootTaskId === origin.parentTaskId && origin.rootTaskId !== origin.remoteOperationId
          && typeof origin.remoteLocalTaskId === 'string' && /^ba1_[a-f0-9]{64}$/.test(origin.remoteLocalTaskId)));
}
/** A local return bookmark, never a review, model selection, or execution authority. */
export type SentPhoneGoalFocus = { purpose?: 'partner_connection'; taskId: string; deviceId: string; instanceId: string; certificateFingerprint: string };
export type ReceivedPhoneGoalReturn = { taskId: string; instanceId: string; sourceId: string; targetId: string; parentTaskId: string; operationId: string; grantId: string; epoch: number; runId?:string; executorEpoch?:number; failedOriginalRun?:true; modelReturnHandle?:string; peerDetail: boolean; leftAt: number };
export function receivedPhoneGoalReturn(task: DeviceData, snapshot: DeviceData | null, peerDetail = false): ReceivedPhoneGoalReturn | undefined {
  if (!isReceivedPhoneTask(task, snapshot) || !identity(task.id) || !identity(snapshot?.local?.instanceId)) return;
  const raw = task.raw, origin = raw.origin;
  if (raw.deviceExecutionKind && raw.deviceExecutionKind !== 'GOAL' || raw.deviceNativeInstanceId && raw.deviceNativeInstanceId !== snapshot!.local.instanceId
    || !identity(origin.parentTaskId) || origin.rootTaskId !== origin.parentTaskId || !positiveInteger(origin.authorizationEpoch)) return;
  return { taskId: task.id, instanceId: snapshot!.local.instanceId, sourceId: origin.callerDeviceId, targetId: origin.targetDeviceId,
    parentTaskId: origin.parentTaskId, operationId: origin.remoteOperationId, grantId: origin.grantId, epoch: origin.authorizationEpoch, peerDetail, leftAt: Date.now() };
}
export function matchesReceivedPhoneGoal(task: DeviceData, snapshot: DeviceData | null, expected: ReceivedPhoneGoalReturn) {
  const value = receivedPhoneGoalReturn(task, snapshot);
  return !!value && value.taskId === expected.taskId && value.instanceId === expected.instanceId && value.sourceId === expected.sourceId
    && value.targetId === expected.targetId && value.parentTaskId === expected.parentTaskId && value.operationId === expected.operationId && value.grantId === expected.grantId && value.epoch === expected.epoch
    &&(!expected.runId || task.raw.runId===expected.runId && task.raw.coordinatorEpoch===expected.executorEpoch) && (!expected.failedOriginalRun || task.raw.status==='failed');
}
/** Only a deliberate per-task read can extend the native 40-row window. Facts cannot authorize
 * a click: expired or changed execution facts disappear; local reduction still uses native review. */
export function deviceOriginalTaskActions(task: DeviceData, details: DeviceData | undefined, snapshot: DeviceData | null, peerId: string, now: number): DeviceData {
  const aggregate = snapshot?.taskActions?.[task.id] || {}, bound = details?.phoneActionScope;
  const unavailable = (reason: string): DeviceData => ({ unavailable: true, phoneActionReason: reason });
  if (details?.phoneActionScopeChanged === true) return unavailable('读取时原执行已变化，请重新读取这项原工作。');
  const receiverPhone = isReceivedPhoneTask(task, snapshot);
  const knownGoal = bound || task.raw?.deviceExecutionKind === 'GOAL' || ['goalReview','pendingCancel','resultReview'].some(key => aggregate[key]?.available === true);
  if (!bound && (!receiverPhone || !knownGoal)) return aggregate;
  if (!bound && !positiveInteger(aggregate.capturedAt)) return {};
  const origin = task.raw?.origin, captured = bound ? details?.actions?.capturedAt : aggregate.capturedAt;
  if (!receiverPhone || (peerId && peerId !== origin.callerDeviceId)
      || (bound && (details?.taskId !== task.id || bound.taskId !== task.id || bound.instanceId !== snapshot?.local?.instanceId ||
        bound.sourceDeviceId !== origin.callerDeviceId || origin.targetDeviceId !== bound.targetDeviceId || origin.remoteOperationId !== bound.operationId ||
        origin.grantId !== bound.grantId || origin.authorizationEpoch !== bound.authorizationEpoch || bound.runId !== (task.raw.runId || '') ||
        bound.epoch !== (task.raw.coordinatorEpoch ?? 0) || bound.status !== task.status || bound.updatedAt !== task.raw.updatedAt)))
    return unavailable('原手机、派发或执行记录已变化，请重新读取这项原工作。');
  if (!positiveInteger(captured) || captured > now || now - captured >= PHONE_ACTION_WINDOW_MS || task.raw.updatedAt > captured)
    return unavailable('这次操作状态已过期或变化，请重新读取；不会自动联系对方。');
  let facts = !bound || positiveInteger(aggregate.capturedAt) && aggregate.capturedAt >= captured ? aggregate : details!.actions;
  if (facts.unavailable) return unavailable('当前原任务操作状态不可读，请重新读取。');
  const request = facts.resumeRequest;
  if (request && (request.taskId !== task.id || request.callerDeviceId !== origin.callerDeviceId ||
      request.runId !== (task.raw.runId || '') || request.executorEpoch !== (task.raw.coordinatorEpoch ?? 0)))
    facts = { ...facts, resumeRequest: undefined, resume: { available: false, reason: '恢复请求不属于当前原执行。' } };
  const peer = deviceRows(snapshot?.peers).find(row => row.deviceId === origin.callerDeviceId);
  const grant = deviceRows(peer?.grantScopes).find(row => row.grantId === origin.grantId && row.authorizationEpoch === origin.authorizationEpoch);
  const deadline = bound?.executionDeadline ?? task.raw.deviceExecutionScope?.deadline;
  if (positiveInteger(deadline) && deadline > now && peer?.revoked === false &&
      peer.authorizationEpoch === origin.authorizationEpoch && grant?.state === 'active' && positiveInteger(grant.expiresAt) && grant.expiresAt > now) return facts;
  const reason = !bound && deadline === undefined ? '这台手机已接收。请读取本机接手状态，核对这次目标的期限和可用操作。' : '原执行期限已到或当前授权范围无法核对，不能从这里开始或恢复。';
  return { stop: facts.stop, pendingCancel: facts.pendingCancel, resultReview: facts.resultReview,
    goalReview: { available: false, reason }, resume: { available: false, reason }, phoneActionReason: reason };
}
export function samePhoneGoalArchive(value: DeviceData | null, peer: DeviceData | undefined, local: DeviceData) {
  return !!value && !!peer && identity(local.deviceId) && identity(local.instanceId) && identity(peer.instanceId) && /^[a-f0-9]{64}$/.test(peer.certificateFingerprint || '')
    && value.targetDeviceId === peer.deviceId && value.instanceId === peer.instanceId && value.targetFingerprint === peer.certificateFingerprint
    && (value.side!=='received' || positiveInteger(value.receivedPeerEpoch) && value.receivedPeerEpoch===peer.authorizationEpoch)
    && ((value.side??'sent')!=='sent' || !!value.sourceTaskId || positiveInteger(value.sentPeerEpoch) && value.sentPeerEpoch===peer.authorizationEpoch)
    && value.localDeviceId === local.deviceId && value.localInstanceId === local.instanceId;
}
const startedPhases=new Set(['preparing','running','pausing','paused','stopping','verifying','awaiting_user','waiting_user','awaiting_approval','waiting_approval','waiting_permission','interrupted','reconciling','reconciliation_required','blocked_unknown']);
export function pickupUnavailable(row:DeviceData,now:number){
  if(row.waitingState==='interrupted_before_start')return '原工作在执行前中断，不能从这里重新开始。';
  if(row.pickupGrantBlock==='revoked')return '本机已撤回这份原授权或配对，不能再接手。';
  if(row.pickupGrantBlock==='changed')return '原设备或授权已换代，不能沿用这份原派单开始。';
  if(row.pickupGrantBlock==='unverifiable')return '本机不能核对这次原授权。可重读本机记录，不会重新申请。';
  if(row.pickupGrantBlock==='expired'||positiveInteger(row.pickupBy)&&row.pickupBy<=now)return '这份原派单已到接手时限，不能再启动。';
  if(row.pickupRetained===false)return '本机只留有原派单，没有当时的接手授权，不会重建。';
  return '';
}
export function waitingPhoneGoalPage(value: unknown, local: DeviceData, search='') {
  if (!object(value) || value.kind !== 'waitingPhoneGoals' || value.readOnly !== true
    || (value.searchText ?? '')!==search || search!==''&&(Array.from(search).length>64||typeof value.withdrawnOnly!=='boolean'||typeof value.declinedOnly!=='boolean'||value.failedOnly!==false||value.startedOnly!==false||value.resultOnly!==false)
    || value.withdrawnOnly!==undefined&&typeof value.withdrawnOnly!=='boolean'||value.withdrawnOnly===true&&(value.declinedOnly!==false||value.failedOnly!==false||value.startedOnly!==false||value.resultOnly!==false||value.rows?.length>6)
    || value.failedOnly!==undefined&&typeof value.failedOnly!=='boolean'||value.failedOnly===true&&(value.declinedOnly!==false||value.startedOnly!==false||value.resultOnly!==false)
    || value.declinedOnly!==undefined&&typeof value.declinedOnly!=='boolean'||value.declinedOnly===true&&(value.startedOnly!==false||value.resultOnly!==false||value.rows?.length>6)
    || value.startedOnly!==undefined && typeof value.startedOnly!=='boolean' || value.startedOnly===true && value.resultOnly!==false
    || (value.resultOnly === true || value.startedOnly===true || value.declinedOnly===true || value.failedOnly===true || value.withdrawnOnly===true) && (!Number.isSafeInteger(value.limitedCandidates) || value.limitedCandidates < 0 || value.limitedCandidates > 24) || !identity(local.deviceId) || !identity(local.instanceId)
    || value.localDeviceId !== local.deviceId || value.localInstanceId !== local.instanceId || !identity(value.waitingHandle) || !positiveInteger(value.capturedAt)
    || typeof value.hasMore !== 'boolean' || typeof value.canReadNewer !== 'boolean' || !Array.isArray(value.rows) || value.rows.length > 24
    || !value.rows.every((r: unknown) => object(r) && (r.pickupGrantBlock===undefined || ['not_started','interrupted_before_start'].includes(r.waitingState) && ['revoked','changed','expired','unverifiable'].includes(r.pickupGrantBlock)) && (r.matchedLater===undefined||!!search&&r.matchedLater===true) && identity(r.taskId) && identity(r.operationId) && typeof r.sourceDeviceId === 'string'
      && /^pw1_[a-f0-9]{64}$/.test(r.sourceDeviceId) && (value.withdrawnOnly===true
        ? r.waitingState==='withdrawn_before_start' && r.notDispatched===true && positiveInteger(r.withdrawnAt) && r.declinedAt===undefined && r.runId===undefined && r.epoch===undefined
        : value.declinedOnly===true
        ? r.waitingState==='declined_before_start' && r.notDispatched===true && positiveInteger(r.declinedAt) && r.runId===undefined && r.epoch===undefined
        : value.failedOnly===true
        ? r.waitingState==='failed_run' && identity(r.runId) && positiveInteger(r.epoch) && positiveInteger(r.registeredAt) && positiveInteger(r.stateRecordedAt) && r.stateRecordedAt>=r.registeredAt
        : value.startedOnly===true
        ? r.waitingState==='original_run' && identity(r.runId) && positiveInteger(r.epoch) && positiveInteger(r.registeredAt)&&typeof r.stopRequested==='boolean'
          && (r.taskState==='needs_confirmation'
            ? positiveInteger(r.reviewRecordedAt) && r.stopRequested===false && ['running','interrupted','needs_confirmation'].includes(r.runState)
            : startedPhases.has(r.taskState) && (startedPhases.has(r.runState)||r.runState==='needs_confirmation') && r.reviewRecordedAt===undefined)
        : value.resultOnly === true
        ? r.waitingState === 'result_review' && positiveInteger(r.reviewRecordedAt) && (r.candidateState===undefined || ['interrupted','failed','cancelled'].includes(r.candidateState)) : ['not_started','interrupted_before_start'].includes(r.waitingState) && (r.pickupRetained===undefined||typeof r.pickupRetained==='boolean') && (r.pickupBy===undefined||positiveInteger(r.pickupBy))) && identity(r.goal) && positiveInteger(r.createdAt) && (['main_screen','background'].includes(r.screenMode) || r.textOnly === true && r.screenMode === 'capabilities'))
    || new Set(value.rows.map((row: DeviceData) => JSON.stringify([row.taskId,row.operationId]))).size !== value.rows.length) throw new Error('这批待接手记录无法与本机原身份核对。');
  return value;
}
export type PhoneArchiveSide = 'sent' | 'received';
export function receivedPhoneArchiveReturn(value: unknown, row: DeviceData, peer: DeviceData, local: DeviceData): ReceivedPhoneGoalReturn {
  const runBound=row.screenDecision===true||['original_run','failed_run'].includes(row.waitingState);
  if (!object(value) || value.kind !== 'receivedGoalReturn' || value.readOnly !== true || value.taskId !== row.taskId || value.operationId !== row.operationId
    || value.targetId !== local.deviceId || value.instanceId !== local.instanceId || value.sourceId !== peer.deviceId
    || (row.screenDecision===true?value.screenDecision!==true||value.confirmed!==row.confirmed:value.screenDecision!==undefined)
    || (row.waitingState==='failed_run' ? value.failedOriginalRun!==true : value.failedOriginalRun!==undefined)
    || (runBound ? value.runId!==row.runId || value.executorEpoch!==row.epoch : value.runId!==undefined || value.executorEpoch!==undefined)
    || !identity(value.parentTaskId) || !identity(value.grantId) || !positiveInteger(value.epoch)) throw new Error('未能核对本机接纳的这次原任务。');
  return { taskId: value.taskId, operationId: value.operationId, targetId: value.targetId, instanceId: value.instanceId, sourceId: value.sourceId,
    parentTaskId: value.parentTaskId, grantId: value.grantId, epoch: value.epoch, ...(row.waitingState==='failed_run'?{failedOriginalRun:true as const}:{}), ...(runBound?{runId:value.runId,executorEpoch:value.executorEpoch}:{}), peerDetail: true, leftAt: Date.now() };
}
export type ArchivedPhoneStopAttempt={localPhase:'prepared_no_write'|'abandoned_before_write'|'write_unconfirmed'; preparedAt:number};
export function isArchivedPhoneStopAttempt(value:unknown):value is ArchivedPhoneStopAttempt{
  return object(value) && ['prepared_no_write','abandoned_before_write','write_unconfirmed'].includes(value.localPhase) && positiveInteger(value.preparedAt);
}
export function archivedPhoneStopAttemptLine(value:ArchivedPhoneStopAttempt,time:(at:number)=>string){
  const detail=value.localPhase==='abandoned_before_write'?'本机在写入前放弃':value.localPhase==='write_unconfirmed'?'本机已开始发送流程，送达未确认':'没有登记开始写入';
  return '本机曾在 '+time(value.preparedAt)+' 准备停止请求；'+detail+'。本机没有可核对的对方停止回执。';
}
export type ArchivedPhoneStop={state:'request_recorded'|'reported_stopped'|'reported_ended'; requestReceivedAt:number};
export function isArchivedPhoneStop(value:unknown):value is ArchivedPhoneStop {
  return object(value) && ['request_recorded','reported_stopped','reported_ended'].includes(value.state) && positiveInteger(value.requestReceivedAt);
}
export function archivedPhoneStopLine(value:ArchivedPhoneStop,time:(at:number)=>string) {
  const state=value.state==='request_recorded'?'已登记停止请求，当时未确认停下':value.state==='reported_stopped'?'当时报告已停下':'当时已结束，未登记停止请求';
  return '原停止回执：'+state+'。对方登记请求于 '+time(value.requestReceivedAt)+'。';
}
export type SourcePhoneSendPhase='abandoned_before_write'|'write_claimed';
export function isSourcePhoneSendPhase(value:unknown):value is SourcePhoneSendPhase {
  return value==='abandoned_before_write'||value==='write_claimed';
}
export function archivedPhoneAcceptanceLine(row:{targetAcceptedAt?:number;targetRunRecorded?:boolean;sourceSendPhase?:SourcePhoneSendPhase},time:(at:number)=>string) {
  if(!row.targetAcceptedAt && row.sourceSendPhase==='abandoned_before_write')return '本机已在写入前放弃这次派发；不必等待这次派发的接单回执。';
  if(!row.targetAcceptedAt && row.sourceSendPhase==='write_claimed')return '本机已开始这次发送流程，尚无可核对的接单回执。不能确认对方是否收到。';
  if(!row.targetAcceptedAt)return '没有可核对的原接单回执，不能据此判断是否送达。';
  return '原设备已于 '+time(row.targetAcceptedAt)+' 接收'+(row.targetRunRecorded?'；回执也留有当时的执行编号。':'。');
}
export function devicePhoneGoalArchive(value: unknown, peer: DeviceData, local: DeviceData, side: PhoneArchiveSide, sourceTaskId = '') {
  if (!object(value) || value.kind !== 'phoneGoalArchive' || (value.sourceTaskId ?? '') !== sourceTaskId
    || !!sourceTaskId && (side !== 'sent' || value.searchText) || value.searchText !== undefined && (typeof value.searchText !== 'string' || Array.from(value.searchText).length > 64) || (value.side ?? 'sent') !== side || !samePhoneGoalArchive(value, peer, local) || value.readOnly !== true || !identity(value.archiveHandle)
    || value.needsAcceptanceProof!==undefined && (value.needsAcceptanceProof!==true || side!=='sent' || !!sourceTaskId)
    || value.oldTargetIdentityChanged!==undefined && (value.oldTargetIdentityChanged!==true || side!=='sent' || !!sourceTaskId)
    || !Array.isArray(value.rows) || value.rows.length > 24 || typeof value.hasMore !== 'boolean' || typeof value.canReadNewer !== 'boolean'
    || !value.rows.every((row: unknown) => object(row) && identity(row.taskId) && (!sourceTaskId || row.taskId === sourceTaskId) && identity(row.operationId) && typeof row.goal === 'string' && !!row.goal
      && (row.targetAcceptedAt === undefined || side==='sent' && !value.needsAcceptanceProof && positiveInteger(row.targetAcceptedAt))
      && (row.sourceSendPhase === undefined || side==='sent' && row.targetAcceptedAt===undefined && isSourcePhoneSendPhase(row.sourceSendPhase))
      && (row.requestDeadline === undefined || value.needsAcceptanceProof===true && positiveInteger(row.requestDeadline))
      && (row.stopAttempt === undefined || !!sourceTaskId && side==='sent' && row.stopReceipt===undefined && isArchivedPhoneStopAttempt(row.stopAttempt))
      && (row.stopReceipt === undefined || !!sourceTaskId && side==='sent' && isArchivedPhoneStop(row.stopReceipt))
      && (row.targetRunRecorded === undefined || row.targetRunRecorded === true && positiveInteger(row.targetAcceptedAt))
      && (row.mayCheckLocalText===undefined || side==='received' && row.mayCheckLocalText===true)
      && (['main_screen', 'background'].includes(row.screenMode) || row.textOnly === true && row.screenMode === 'capabilities') && positiveInteger(row.createdAt))
    || new Set(value.rows.map((row: DeviceData) => JSON.stringify([row.taskId, row.operationId]))).size !== value.rows.length) throw new Error('原手机、本机派发或目录已变化，请重新读取。');
  return value;
}
export function localReviewedInbox(value:unknown,local:DeviceData,search=''){
  if(!object(value)||value.kind!=='reviewedPhoneInbox'||(value.searchText??'')!==search||Array.from(search).length>64||value.readOnly!==true||value.localDeviceId!==local.deviceId||value.localInstanceId!==local.instanceId||!identity(value.closedHandle)
    ||!Array.isArray(value.rows)||value.rows.length>6||typeof value.hasMore!=='boolean'||typeof value.canReadNewer!=='boolean'||!Number.isSafeInteger(value.limitedCandidates)||value.limitedCandidates<0
    ||!value.rows.every((r:unknown)=>object(r)&&identity(r.taskId)&&identity(r.operationId)&&identity(r.sourceDeviceId)&&typeof r.goal==='string'&&positiveInteger(r.reviewedAt)
      &&(r.screenDecision===true?typeof r.confirmed==='boolean'&&identity(r.runId)&&positiveInteger(r.epoch)&&r.notConfirmed===undefined&&r.hasText===undefined&&r.keptOnPhone===undefined&&r.matchPart===undefined&&r.matchPage===undefined&&r.bothParts===undefined
        :r.screenDecision!==undefined||r.confirmed!==undefined||r.runId!==undefined||r.epoch!==undefined?false:r.notConfirmed===true?r.hasText===undefined&&r.keptOnPhone===undefined&&r.matchPart===undefined&&r.matchPage===undefined&&r.bothParts===undefined
        :r.notConfirmed===undefined&&typeof r.keptOnPhone==='boolean'&&typeof r.hasText==='boolean'
      &&(search ? (r.matchPart==='goal'||r.matchPart==='result'&&r.hasText===true)&&Number.isSafeInteger(r.matchPage)&&r.matchPage>=0&&r.matchPage<188&&typeof r.bothParts==='boolean'
        : r.matchPart===undefined&&r.matchPage===undefined&&r.bothParts===undefined)))
    ||new Set(value.rows.map((r:DeviceData)=>JSON.stringify([r.taskId,r.operationId]))).size!==value.rows.length)throw new Error('本机已核对目录身份已变化，请重新读取。');
  return value;
}
/** Already reviewed on this receiving phone. This is not A's task.get or a new execution. */
export function localReceivedPhonePage(value: unknown, archive: DeviceData, row: DeviceData, page: number, before?: DeviceData, part='result') {
  const home=archive.kind==='reviewedPhoneInbox';
  if (!object(value)||value.kind!=='localReceivedReviewedText'||value.readOnly!==true||(value.part??'result')!==part
    ||value.longOriginalGoal!==undefined&&typeof value.longOriginalGoal!=='boolean'||(!home&&archive.side!=='received')||value.archiveHandle!==(home?archive.closedHandle:archive.archiveHandle)
    ||home&&(value.reviewedAt!==row.reviewedAt||value.keptOnPhone!==row.keptOnPhone)
    ||value.taskId!==row.taskId||value.operationId!==row.operationId||value.sourceId!==(home?row.sourceDeviceId:archive.targetDeviceId)||value.localId!==archive.localDeviceId||value.localInstance!==archive.localInstanceId
    ||typeof value.text!=='string'||Array.from(value.text).length>1400||!digest(value.stamp)||!identity(value.runId)||!positiveInteger(value.epoch)||!positiveInteger(value.reviewedAt)
    ||typeof value.keptOnPhone!=='boolean'||value.page!==page||!positiveInteger(value.pages)||value.pages>188||page<0||page>=value.pages
    ||before&&(before.stamp!==value.stamp||before.runId!==value.runId||before.epoch!==value.epoch||before.reviewedAt!==value.reviewedAt||before.keptOnPhone!==value.keptOnPhone))
    throw new Error('没有取得同一份本机原文，未用派单或结束状态替代。');
  return value;
}
/** This page contains only the local index, never new controls or the peer's business result. */
export function deviceTaskHistoryPage(value: unknown, taskId: string, targetDeviceId: string, kind: 'taskDetails' | 'taskHistoryPage') {
  if (!object(value) || value.kind !== kind || value.taskId !== taskId || !object(value.history)) throw new Error('本机派发页不属于这项原工作。');
  const page = value.history;
  if (page.readOnly !== true || !identity(page.pageHandle) || page.targetDeviceId !== targetDeviceId || typeof page.hasMore !== 'boolean'
      || typeof page.canReadNewer !== 'boolean' || !Array.isArray(page.links) || page.links.length > 24
      || !page.links.every((link: unknown) => object(link) && link.parentTaskId === taskId && identity(link.operationId) && identity(link.targetDeviceId)
        && (!targetDeviceId || link.targetDeviceId === targetDeviceId))
      || new Set(page.links.map((link: DeviceData) => link.operationId)).size !== page.links.length) throw new Error('原工作、设备或本机派发页已变化，请从最近记录重新读取。');
  return page;
}
export function deviceTaskHistoryLinks(details: DeviceData | undefined, taskId: string, peerId: string): DeviceData[] {
  if (!details || details.taskId !== taskId) return [];
  return deviceRows(details.history?.links).filter(link => link.parentTaskId === taskId && (!peerId || link.targetDeviceId === peerId));
}
export function deviceHistoryReceipt(value: unknown, review: DeviceData): DeviceData {
  if (!object(value) || value.kind !== 'historyReceipt' || value.viewHandle !== review.viewHandle || !['taskId', 'operationId', 'targetDeviceId'].every(key => value[key] === review.view[key]) || !object(value.receipt) || typeof value.stopSubmitted !== 'boolean') throw new Error('回执不属于当前原任务、原操作与设备窗口。');
  const receipt = value.receipt;
  if (receipt.managementOnly !== true && (receipt.readOnly !== true || receipt.newRun !== false || receipt.retrySubmitted !== false || receipt.unknownRefunded !== false || typeof receipt.unknown !== 'boolean' || !object(receipt.operationReceipt))) throw new Error('未取得原操作的只读执行回执。');
  return value;
}
export function devicePhoneGoalResult(value: unknown, review: DeviceData): DeviceData {
  if (review.kind !== 'history' || review.view?.phoneGoalResult !== true || !object(value) || value.kind !== 'phoneGoalResult'
    || value.viewHandle !== review.viewHandle || !['taskId', 'operationId', 'targetDeviceId'].every(key => value[key] === review.view[key]))
    throw new Error('目标结果不属于当前手机和原派发。');
  const result = value.result;
  if (!object(result) || result.readOnly !== true || result.instanceId !== review.view.instanceId || !identity(result.taskId)
    || !identity(result.runId) || !positiveInteger(result.executorEpoch) || !identity(result.state) || !positiveInteger(result.updatedAt)
    || !['confirmed','none','pending','not_confirmed','unverified','unavailable'].includes(result.review) || typeof result.summary !== 'string'
    || typeof result.truncated !== 'boolean' || result.summaryWithheld!==undefined && (result.summaryWithheld!==true || result.review!=='confirmed' || result.summary!=='' || result.truncated!==false)
    || (result.review !== 'confirmed' && (result.summary || result.truncated))) throw new Error('缺少原任务、原执行或准确结果回执。');
  if(result.summary && (!positiveInteger(result.textReadUntil) || !positiveInteger(result.textReadRemainingMs) || result.textReadRemainingMs>5000 || !digest(result.textTargetFingerprint)))
    throw new Error('原宿主未提供这次文字读取的有效期，未展示正文。');
  return result;
}
export function deviceHistoryLabel(value: DeviceData | null, view?: DeviceData): string {
  if (!value) return view?.phoneGoalResult === true
    ? view.localPhoneResultPath === 'original_run' ? '本机已保存原接单回执与执行编号；这不代表已读取成果。' : '这里显示本机派发记录，尚未在此窗口重读对方回执。'
    : '读取这次操作的最新回执。';
  if (value.managementOnly === true) return value.stopped === true ? '执行端报告已停止，请继续查询原操作结果。' : value.requested === true ? '执行端已接受停止请求，仍待核对停止结果。' : '停止结果待核对，请查询原操作回执。';
  if (view?.phoneGoalResult === true) {
    const r = value.operationReceipt;
    if (!object(r) || r.messageType !== 'OperationReceipt' || r.operationId !== view.operationId ||
        r.executorDeviceId !== view.targetDeviceId || r.instanceId !== view.instanceId ||
        (r.accepted === true && r.argumentsDigest !== view.argumentsDigest)) return '未取得这次手机派发可核对的接收记录。';
    if (r.accepted !== true) return r.status === 'rejected' ? '原手机拒绝了本次请求；这不是机主已读回执。'
      : '对端本次没有确认接收。不能据此推断未送达，或机主已经看过。';
    if (r.effectState === 'unknown') return '原手机已登记这次派发，操作效果仍未知。';
    const run = identity(r.runId) && positiveInteger(r.executorEpoch);
    if (r.status === 'cancel_requested') return '原手机已收到取消请求，不能当作已经停止。';
    if (r.status === 'cancelled') return run ? '原手机报告这次执行已取消，不代表之前的操作已撤销。' : '原手机报告目标已取消，未返回已开始的执行编号。';
    if (r.status === 'waiting_approval') return run ? '原手机上的这次执行正在等人处理。' : '原手机已接收，尚未报告开始执行。请在原手机查看待处理事项。';
    if (r.status === 'waiting_resource' || r.status === 'waiting_dependency') return '原手机已接收，正在等待执行条件。';
    if (run && r.status === 'running') return '原手机已开始执行这份目标；这不是成果回执。';
    if (run && (r.status === 'paused' || r.status === 'pause_requested')) return r.status === 'paused' ? '原手机报告这次执行已暂停。能否继续，以原手机当前许可为准。' : '原手机正在暂停这次执行，尚未确认停下。';
    if (run && (r.status === 'succeeded' || r.status === 'failed')) return '原手机报告这次执行已结束。这里是派发记录，不是机主核对过的成果。';
    return run ? '原手机已接收并返回了原执行编号，当前进展仍待核对。' : '原手机已接收，尚未返回已开始的执行编号。不代表机主已经看过。';
  }
  return value.unknown === true ? '执行效果仍未确认，保留原占用次数。' : '执行端已返回原操作回执，业务成果仍需核对。';
}
/** Navigation only. Opening a row obtains the complete original native task/review. */
export function deviceWorkspaceTaskMode(value: unknown): 'manual_phone' | 'agent_device' | '' {
  if (!object(value) || value.actorId !== 'owner' || value.spaceId !== 'private' || 'origin' in value) return '';
  const nav = value.deviceWorkspaceNavigation;
  const projected = object(nav) && nav.format === 'pocketworld.device-workspace-navigation.v1' && nav.readOnly === true && nav.executionAuthorized === false;
  if (value.kind === 'tool' && value.nativeExecutionMode === 'OWNER_PHONE_CONTROL' && (value.ownerPhoneControl?.format === 'pocketworld.owner-phone-control.v1' || projected && nav.mode === 'manual_phone')) return 'manual_phone';
  if (value.kind === 'assistant' && value.executionMode === 'capabilities' && (value.ownerDeviceWork?.format === 'pocketworld.owner-device-work.v1' || projected && nav.mode === 'agent_device')) return 'agent_device';
  return '';
}
export function deviceTaskRows(tasks: unknown, snapshot: DeviceData | null, peerId = ''): DeviceData[] {
  const links = deviceRows(Array.isArray(snapshot?.taskLinks) ? snapshot.taskLinks : snapshot?.taskLinks?.links);
  const originals = deviceRows(tasks).filter(task => identity(task.id));
  const result = originals.filter(task => {
    const raw = object(task.raw) ? task.raw : {}, origin = object(raw.origin) ? raw.origin : {}, operations = deviceRows(raw.operations);
    const prepared = deviceWorkspaceTaskMode(raw) !== '';
    const selected = deviceRows(raw.deviceSourceScope?.routes);
    const targets = deviceStrings(raw.deviceWorkspaceNavigation?.targetDeviceIds);
    return peerId ? prepared && (selected.some(route => route.targetDeviceId === peerId) || targets.includes(peerId) || raw.ownerDeviceWork?.directPhone?.deviceId === peerId) || links.some(link => link.targetDeviceId === peerId && link.parentTaskId === task.id) || origin.kind === 'paired_device' && ['originDeviceId', 'callerDeviceId', 'targetDeviceId'].some(key => origin[key] === peerId) || operations.some(operation => typeof operation.tool === 'string' && operation.tool.startsWith('device_') && operation.arguments?.targetDeviceId === peerId)
      : prepared || origin.kind === 'paired_device' || operations.some(operation => typeof operation.tool === 'string' && operation.tool.startsWith('device_')) || links.some(link => link.parentTaskId === task.id);
  });
  for (const link of links) if (identity(link.parentTaskId) && (!peerId || link.targetDeviceId === peerId) && !result.some(task => task.id === link.parentTaskId)) result.push({ id: link.parentTaskId, goal: '原设备关联任务', raw: {}, summaryOnly: true });
  return result;
}
export function deviceGrantInput(review: DeviceData, draft: DeviceData, now = Date.now()): DeviceData {
  const option = deviceRows(review.view.grantOptions).find(row => row.optionId === draft.optionId);
  if (!option || !identity(review.viewHandle)) throw new Error('请明确选择一种原生权限范围。');
  const integer = (value: unknown, min: number, max: number, name: string) => {
    if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max) || min < 0 || max < min || typeof value !== 'string' || !/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < min || Number(value) > max) throw new Error(`${name}需为 ${min}–${max} 之间的整数。`);
    return Number(value);
  };
  const choices = (value: unknown, max: number) => {
    if (!Array.isArray(value) || value.length > max || !value.every(identity) || new Set(value).size !== value.length) throw new Error('权限选择必须逐项明确，不能重复或加入其他来源。');
    return value as string[];
  };
  const toolBudget = integer(draft.toolBudget, option.toolBudgetMin ?? 0, option.toolBudgetMax, '工具次数');
  const modelBudget = integer(draft.modelBudget, option.modelBudgetMin ?? 0, option.modelBudgetMax, '模型次数');
  const durationMinutes = integer(draft.durationMinutes, 1, 43200, '授权分钟数');
  const historyReadMethods = choices(draft.historyReadMethods, 5), origins = choices(draft.additionalOriginOptionIds, 8), forwarding = choices(draft.forwardingOptionIds, 8);
  const historyDays = historyReadMethods.length ? integer(draft.historyDays, 1, 30, '结果读取天数') : 0;
  if (!historyReadMethods.every(method => deviceStrings(option.historyReadMethods).includes(method))) throw new Error('结果读取方法不在原生可选范围。');
  if (!origins.every(id => deviceRows(review.view.originOptions).some(row => row.optionId === id))) throw new Error('已认证转交来源已经变化。');
  const targets = forwarding.map(id => deviceRows(review.view.forwardingOptions).find(row => row.optionId === id));
  if (targets.some(target => !target) || forwarding.length && option.allowDelegation !== true) throw new Error('下游设备或原生允许委派的范围已经变化。');
  if (typeof draft.wholeDisplayCapture !== 'boolean' || draft.wholeDisplayCapture && option.canGrantWholeDisplayCapture !== true) throw new Error('当前范围没有整屏截图授权。');
  if (!positiveInteger(option.expiresAtMax) || option.expiresAtMax <= now || targets.some(target => !positiveInteger(target?.expiresAtMax) || target!.expiresAtMax <= now) || historyDays && (!positiveInteger(option.historyReadExpiresAtMax) || option.historyReadExpiresAtMax <= now)) throw new Error('原执行或结果读取期限已经到期，请重新核对。');
  const forwardingToolBudget = forwarding.length ? integer(draft.forwardingToolBudget, 0, Math.min(toolBudget, ...targets.map(target => target!.toolBudgetMax)), '下游工具次数') : 0;
  const forwardingModelBudget = forwarding.length ? integer(draft.forwardingModelBudget, 0, Math.min(modelBudget, ...targets.map(target => target!.modelBudgetMax)), '下游模型次数') : 0;
  if (forwarding.length && forwardingToolBudget + forwardingModelBudget === 0) throw new Error('请选择有限且非零的下游次数。');
  return { viewHandle: review.viewHandle, optionId: draft.optionId, toolBudget, modelBudget, durationMinutes, historyDays, historyReadMethods,
    additionalOriginOptionIds: origins, forwardingOptionIds: forwarding, forwardingToolBudget, forwardingModelBudget, wholeDisplayCapture: draft.wholeDisplayCapture };
}

/** UI result ownership only; native Window/review objects remain the authority. */
export class DeviceRequestScope {
  private generation = 0;
  private sequence = 0;
  private enabled = true;
  private pending = new Map<string, DeviceRequestTicket>();
  hasPending(lane = 'work'): boolean { return this.pending.has(lane); }
  begin(lane = 'work'): DeviceRequestTicket | null {
    if (!this.enabled || this.pending.has(lane)) return null;
    const ticket = { generation: this.generation, sequence: ++this.sequence, lane };
    this.pending.set(lane, ticket); return ticket;
  }
  owns(ticket: DeviceRequestTicket): boolean { return this.enabled && ticket.generation === this.generation && this.pending.get(ticket.lane) === ticket; }
  finish(ticket: DeviceRequestTicket): boolean { const current = this.owns(ticket); if (this.pending.get(ticket.lane) === ticket) this.pending.delete(ticket.lane); return current; }
  invalidate(enabled = this.enabled): void { this.generation++; this.enabled = enabled; this.pending.clear(); }
}
export type DeviceRequestTicket = { generation: number; sequence: number; lane: string };
// A native observation is not a live promise. DEGRADED still had an authenticated control channel.
export const deviceChannelWasConnected = (peer: DeviceData): boolean => !peer.revoked && (peer.connection?.state === 'ONLINE' || peer.connection?.state === 'DEGRADED');
/** A readable index of the two original grant lists, not a new capability or execution decision. */
export function deviceRecordedScopes(peer: DeviceData, fromPeer: boolean, now: number) {
  const scopes = deviceRows(fromPeer ? peer.receivedGrantScopes : peer.grantScopes);
  const current = scopes.filter(scope => peer.revoked !== true && peer.connection?.state !== 'REVOKED'
    && (fromPeer ? scope.receivedScope === true : scope.state === 'active')
    && positiveInteger(scope.expiresAt) && scope.expiresAt > now);
  const labels = new Set<string>();
  for (const scope of current) {
    const methods = deviceStrings(scope.capabilities), tools = deviceStrings(scope.toolNames);
    if (methods.includes('desktop.viewer')) labels.add('查看指定桌面或窗口');
    if (methods.includes('task.submitGoal')) labels.add('提交目标');
    if (methods.includes('tool.invoke') && tools.length) {
      for (const binding of deviceRows(scope.resources)) {
        if (binding.screenMode === 'main_screen') labels.add('指定主屏工具');
        else if (binding.screenMode === 'background') labels.add('指定独立屏工具');
        else if (binding.projectId && binding.checkoutId) labels.add('指定项目工具');
        else if (tools.includes('automation_propose_schedule')) labels.add('提交定时草稿');
        else if (binding.kind === 'desktop') labels.add('指定电脑工具');
        else labels.add('指定工具');
      }
    }
  }
  const names = [...labels];
  const workRecorded = current.some(scope => deviceStrings(scope.capabilities).includes('task.submitGoal')
    || deviceStrings(scope.capabilities).includes('tool.invoke') && deviceStrings(scope.toolNames).some(tool => !['automation_propose_schedule', 'automation_get_schedule_draft'].includes(tool)));
  return { count: current.length, workRecorded, text: names.length ? names.slice(0, 3).join('、') + (names.length > 3 ? `等 ${names.length} 类` : '')
    : current.length ? '有范围记录，详见准确方法' : scopes.length ? '本页未见仍在有效期内的范围' : fromPeer ? '尚未读到对方给本机的范围' : '本页未读到给对方的范围' };
}
export const deviceConnectionLabel = (peer: DeviceData): string => peer.revoked === true ? '本机已撤销信任' : ({ CONNECTING: '正在建立加密连接', ONLINE: '加密通道已连接', DEGRADED: '已连接，但不稳定', OFFLINE: '离线', REVOKED: '连接已撤销', INCOMPATIBLE: '协议不兼容，未自动重试' } as Record<string, string>)[peer.connection?.state] || '连接状态待核对';
export const deviceOperationLabels: Record<string, string> = { 'receiver.enable': '启用本机设备接收', 'receiver.disable': '停用本机设备接收', 'peer.connect': '连接原设备', 'peer.disconnect': '停止主动连接', 'peer.revoke': '撤销本机设备信任', 'relay.remove': '移除临时中继租约', 'task.stop': '请求原任务停止', 'task.pause': '请求原任务暂停', 'task.resume': '继续同一次执行', 'task.cancelPending': '取消本机未启动目标' };
export const deviceMethodLabels: Record<string, string> = { 'tool.invoke': '调用明确工具', 'task.submitGoal': '委派目标并使用已授权模型', 'task.cancel': '请求原任务停止', 'operation.cancel': '请求原操作停止', 'task.pause': '请求原任务暂停', 'task.get': '读取任务结果', 'task.events': '读取任务进展', 'operation.get': '读取操作回执', 'artifact.describe': '读取成果说明', 'artifact.readChunk': '读取成果内容', 'task.acquireControl': '申请原任务控制租约' };
