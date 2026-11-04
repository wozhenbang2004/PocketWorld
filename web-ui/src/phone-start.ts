export type PhoneStartReceipt = {
  status: 'accepted' | 'rejected' | 'unknown';
  requestId?: string;
  conversationId?: string;
  taskId?: string;
  runId?: string;
  epoch?: number;
  admitted?: boolean;
  taskStatus?: string;
  message: string;
};
type ReviewedPhoneIdentity = { requestId?: string; conversationId?: string };
const identity = (value: unknown) => typeof value === 'string' && value.trim() ? value : undefined;

/** Only a native receipt for this start can consume its reviewed draft. */
export function phoneStartReceipt(value: unknown, expected?: ReviewedPhoneIdentity): PhoneStartReceipt {
  const row = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  const requestId = identity(expected?.requestId) || identity(row.requestId);
  const conversationId = identity(expected?.conversationId) || identity(row.conversationId);
  const original = !requestId || !!conversationId && row.requestId === requestId && row.conversationId === conversationId && row.taskId === requestId;
  const taskId = original ? identity(row.taskId) : undefined;
  const status = row.status === 'rejected' ? 'rejected'
    : (row.status === 'accepted' || row.accepted === true) && taskId ? 'accepted' : 'unknown';
  const runId = original ? identity(row.runId) : undefined;
  const epoch = runId && Number.isSafeInteger(row.epoch) && (row.epoch as number) > 0 ? row.epoch as number : undefined;
  const taskStatus = identity(row.taskStatus) || (row.accepted === true && row.status !== 'accepted' ? identity(row.status) : undefined);
  const message = typeof row.message === 'string' && row.message ? row.message
    : status === 'accepted' ? '原手机任务已创建。'
    : status === 'rejected' ? '这次任务尚未接纳，草稿仍保留。'
    : '启动结果待核对。请先查看任务记录，避免再次提交同一目标。';
  return { status, ...(requestId ? { requestId } : {}), ...(conversationId ? { conversationId } : {}),
    ...(taskId ? { taskId } : {}), ...(runId && epoch ? { runId, epoch } : {}),
    ...(original && typeof row.admitted === 'boolean' ? { admitted: row.admitted } : {}),
    ...(original && taskStatus ? { taskStatus } : {}), message };
}

export function phoneStartFailure(error: unknown, expected?: ReviewedPhoneIdentity): PhoneStartReceipt {
  const failure = error && typeof error === 'object' ? error as Record<string, unknown> : {};
  return phoneStartReceipt({
    status: failure.code === 'not_accepted' || failure.code === 'invalid_request' || failure.code === 'not_sent' ? 'rejected' : 'unknown',
    message: typeof failure.message === 'string' ? failure.message : undefined,
  }, expected);
}

export function phoneRunIdentity(task: unknown): { taskId: string; runId: string; epoch: number } | null {
  if (!task || typeof task !== 'object') return null;
  const row = task as Record<string, any>, raw = row.raw;
  if (typeof row.id !== 'string' || !row.id || !raw || typeof raw.runId !== 'string' || !raw.runId
      || !Number.isInteger(raw.coordinatorEpoch) || raw.coordinatorEpoch < 1) return null;
  return { taskId: row.id, runId: raw.runId, epoch: raw.coordinatorEpoch };
}
