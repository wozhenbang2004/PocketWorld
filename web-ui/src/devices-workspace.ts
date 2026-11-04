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
export function deviceHistoryReceipt(value: unknown, review: DeviceData): DeviceData {
  if (!object(value) || value.kind !== 'historyReceipt' || value.viewHandle !== review.viewHandle || !['taskId', 'operationId', 'targetDeviceId'].every(key => value[key] === review.view[key]) || !object(value.receipt) || typeof value.stopSubmitted !== 'boolean') throw new Error('回执不属于当前原任务、原操作与设备窗口。');
  const receipt = value.receipt;
  if (receipt.managementOnly !== true && (receipt.readOnly !== true || receipt.newRun !== false || receipt.retrySubmitted !== false || receipt.unknownRefunded !== false || typeof receipt.unknown !== 'boolean' || !object(receipt.operationReceipt))) throw new Error('未取得原操作的只读执行回执。');
  return value;
}
export function deviceHistoryLabel(value: DeviceData | null): string {
  if (!value) return '读取这次操作的最新回执。';
  if (value.managementOnly === true) return value.stopped === true ? '执行端报告已停止，请继续查询原操作结果。' : value.requested === true ? '执行端已接受停止请求，仍待核对停止结果。' : '停止结果待核对，请查询原操作回执。';
  return value.unknown === true ? '执行效果仍未确认，保留原占用次数。' : '执行端已返回原操作回执，业务成果仍需核对。';
}
export function deviceTaskRows(tasks: unknown, snapshot: DeviceData | null, peerId = ''): DeviceData[] {
  const links = deviceRows(Array.isArray(snapshot?.taskLinks) ? snapshot.taskLinks : snapshot?.taskLinks?.links);
  const originals = deviceRows(tasks).filter(task => identity(task.id));
  const result = originals.filter(task => {
    const raw = object(task.raw) ? task.raw : {}, origin = object(raw.origin) ? raw.origin : {}, operations = deviceRows(raw.operations);
    return peerId ? links.some(link => link.targetDeviceId === peerId && link.parentTaskId === task.id) || origin.kind === 'paired_device' && ['originDeviceId', 'callerDeviceId', 'targetDeviceId'].some(key => origin[key] === peerId) || operations.some(operation => typeof operation.tool === 'string' && operation.tool.startsWith('device_') && operation.arguments?.targetDeviceId === peerId)
      : origin.kind === 'paired_device' || operations.some(operation => typeof operation.tool === 'string' && operation.tool.startsWith('device_')) || links.some(link => link.parentTaskId === task.id);
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
export const deviceConnectionLabel = (peer: DeviceData): string => peer.revoked === true ? '本机已撤销信任' : ({ CONNECTING: '正在建立加密连接', ONLINE: '加密连接在线', DEGRADED: '连接不稳定', OFFLINE: '当前离线', REVOKED: '连接已撤销', INCOMPATIBLE: '协议不兼容，未自动重试' } as Record<string, string>)[peer.connection?.state] || '实时连接尚未读取';
export const deviceOperationLabels: Record<string, string> = { 'receiver.enable': '启用本机设备接收', 'receiver.disable': '停用本机设备接收', 'peer.connect': '连接原设备', 'peer.disconnect': '停止主动连接', 'peer.revoke': '撤销本机设备信任', 'relay.remove': '移除临时中继租约', 'task.stop': '请求原任务停止', 'task.pause': '请求原任务暂停', 'task.resume': '继续同一次执行', 'task.cancelPending': '取消本机未启动目标' };
export const deviceMethodLabels: Record<string, string> = { 'tool.invoke': '调用明确工具', 'task.submitGoal': '委派目标并使用已授权模型', 'task.cancel': '请求原任务停止', 'operation.cancel': '请求原操作停止', 'task.pause': '请求原任务暂停', 'task.get': '读取任务结果', 'task.events': '读取任务进展', 'operation.get': '读取操作回执', 'artifact.describe': '读取成果说明', 'artifact.readChunk': '读取成果内容', 'task.acquireControl': '申请原任务控制租约' };
