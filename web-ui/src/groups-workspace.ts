export type GroupData = Record<string, any>;
export const groupRows = (value: unknown): GroupData[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const object = (value: any): value is GroupData => !!value && typeof value === 'object' && !Array.isArray(value);
const revision = (value: any) => Number.isSafeInteger(value?.revision) && value.revision >= 0 ? value.revision : -1;

/** Queue acceptance and an older snapshot cannot become a saved group state. */
export function acceptGroupSnapshot(current: GroupData | null, value: unknown): GroupData | null {
  if (!object(value) || revision(value) < 0 || !['groups', 'requests', 'deliveries'].every(key => Array.isArray(value[key]))) return current;
  return revision(value) >= revision(current) ? value : current;
}
export function sameGroupVersion(before: GroupData, after: GroupData | undefined): boolean {
  return !!after && typeof before.id === 'string' && !!before.id && before.id === after.id && revision(before) > 0 && revision(before) === revision(after) && typeof before.policyEpoch === 'string' && before.policyEpoch === after.policyEpoch;
}
export function acceptGroupTask(reference: GroupData, current: GroupData | null, value: any): GroupData {
  if (!object(value) || typeof value.known !== 'boolean' || !object(value.reference) || !['requestId', 'taskId', 'groupId', 'spaceId'].every(key => typeof reference[key] === 'string' && reference[key] === value.reference[key])) throw new Error('原任务回执不属于这个群请求。');
  if (value.known && (!object(value.task) || value.task.taskId !== reference.taskId || revision(value.task) < 1)) throw new Error('原任务尚未返回完整身份与版本。');
  if (!value.known) return current?.known ? current : value;
  if (current?.task && revision(value.task) < revision(current.task)) return current;
  return value;
}
export function groupPresentation(reference: GroupData, task: GroupData | undefined, value: any): GroupData | null {
  if (!task || !object(value) || value.requestId !== reference.requestId || value.taskId !== reference.taskId || value.spaceId !== reference.spaceId || value.taskRevision !== task.revision || value.runId !== task.runId || value.coordinatorEpoch !== task.coordinatorEpoch || value.status !== task.status) return null;
  return value;
}
export function acceptGroupDelivery(reference: GroupData, value: any, deliveryId?: string, outputId?: string): GroupData {
  if (!object(value) || value.requestId !== reference.requestId || value.taskId !== reference.taskId || typeof value.deliveryId !== 'string' || !value.deliveryId || (deliveryId && value.deliveryId !== deliveryId) || (outputId && value.outputId !== outputId) || !['prepared', 'sending', 'accepted', 'delivered', 'unknown', 'failed'].includes(value.state)) throw new Error('原投递回执不属于这个请求或产物。');
  if (value.state === 'delivered' && (typeof value.platformMessageId !== 'string' || !value.platformMessageId.trim())) throw new Error('送达回执缺少平台消息身份。');
  return value;
}
export function confirmedGroupResult(value: any, command: string): GroupData {
  if (!object(value) || value.confirmed !== true || value.command !== command || !object(value.result)) throw new Error('尚未取得这次原操作的完整回执，请先刷新核对。');
  return value.result;
}
export const groupCapabilities = [
  ['public.answer', '公开问答'], ['public.search', '公开搜索'], ['group.files', '群空间文件'],
  ['media.image', '生成图片'], ['media.audio', '生成语音'], ['media.video', '生成视频'],
] as const;
export const groupQuotaFields = [
  ['dailyTasks', '全群每日任务数', 1, 500], ['memberDailyTasks', '每位成员每日任务数', 1, 100],
  ['dailyTokens', '全群每日模型预留额度', 1, 5_000_000], ['maxTokensPerTask', '每项任务模型预留上限', 1, 100_000],
  ['maxConcurrent', '同时运行任务数', 1, 8], ['dailyMedia', '全群每日媒体次数', 0, 100],
  ['memberDailyMedia', '每位成员每日媒体次数', 0, 20], ['maxMediaBytes', '每份媒体大小上限（字节）', 1, 20 * 1024 * 1024],
] as const;
export function groupPolicyDraft(group: GroupData): GroupData {
  return { enabled: group.enabled === true, visibility: group.visibility || 'requester', hours: '24',
    grants: groupRows(group.grants).map(row => ({ capability: row.capability, resource: row.resource, allowGroupAudience: row.allowGroupAudience === true })),
    quota: Object.fromEntries(groupQuotaFields.map(([key]) => [key, String(group.quota?.[key] ?? '')])) };
}
export function groupPolicyPayload(group: GroupData, draft: GroupData): GroupData {
  const integer = (raw: any, min: number, max: number, label: string) => {
    if (typeof raw !== 'string' || !/^\d+$/.test(raw)) throw new Error(`${label}需填写整数。`);
    const value = Number(raw); if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${label}需在 ${min}–${max} 之间。`); return value;
  };
  const hours = integer(draft.hours, 1, 744, '授权时长');
  const quota = Object.fromEntries(groupQuotaFields.map(([key, label, min, max]) => [key, integer(draft.quota[key], min, max, label)]));
  if (quota.memberDailyTasks > quota.dailyTasks || quota.maxTokensPerTask > quota.dailyTokens || quota.memberDailyMedia > quota.dailyMedia) throw new Error('成员额度与单次额度不能大于群总额度。');
  return { id: group.id, revision: group.revision, policyEpoch: group.policyEpoch, enabled: draft.enabled, visibility: draft.visibility,
    grants: groupRows(draft.grants).map(grant => ({ ...grant, hours })), quota };
}
export const groupTaskLabel = (status: any): string => ({ queued: '等待执行', preparing: '准备中', running: '正在执行', awaiting_user: '等待主人处理', needs_confirmation: '等待核对结果', paused: '已暂停', stopping: '正在停止', completed: '已完成', failed: '失败', cancelled: '已取消', interrupted: '已中断', reconciling: '效果待核对' } as Record<string, string>)[status] || '尚未读取任务状态';
export const groupDeliveryLabel = (status: any): string => ({ prepared: '待投递', sending: '投递中', accepted: '平台已接受，送达待核对', delivered: '已送达', unknown: '投递结果未知，只能查询', failed: '投递失败' } as Record<string, string>)[status] || '尚未读取投递状态';
