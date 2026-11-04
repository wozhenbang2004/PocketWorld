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

export type PhoneResult = {
  taskId: string; runId: string; epoch: number; deliveryId: string;
  status: string; endedAt: number; summaryTruncated: boolean; summaryChars: number;
};
const endedPhoneStatuses = new Set(['completed', 'needs_confirmation', 'awaiting_user', 'failed', 'cancelled', 'interrupted', 'reconciling', 'paused']);

/** This native message snapshot describes one ended run, never the task's current state. */
export function messagePhoneResult(message: unknown): PhoneResult | null {
  if (!message || typeof message !== 'object') return null;
  const row = message as Record<string, any>, result = row.phoneResult;
  if (row.role !== 'assistant' || !result || typeof result !== 'object'
      || !identity(row.taskId) || result.taskId !== row.taskId || !identity(result.runId)
      || !Number.isSafeInteger(result.epoch) || result.epoch < 1
      || result.deliveryId !== `phone_handoff:${result.taskId}:${result.runId}:${result.epoch}`
      || !endedPhoneStatuses.has(result.status) || !Number.isSafeInteger(result.endedAt) || result.endedAt < 1
      || typeof result.summaryTruncated !== 'boolean' || !Number.isSafeInteger(result.summaryChars) || result.summaryChars < 0) return null;
  return { taskId: result.taskId, runId: result.runId, epoch: result.epoch, deliveryId: result.deliveryId,
    status: result.status, endedAt: result.endedAt, summaryTruncated: result.summaryTruncated, summaryChars: result.summaryChars };
}

/** Display association only. Opening and reviewing still use the original native task. */
export function phoneConversationId(task: unknown): string | null {
  if (!task || typeof task !== 'object') return null;
  const row = task as Record<string, any>, raw = row.raw;
  if (!identity(row.id) || !raw || raw.id !== row.id || raw.kind !== 'phone' || raw.actorId !== 'owner'
      || raw.spaceId !== 'private' || 'origin' in raw || 'parentTaskId' in raw) return null;
  // Bounded native task headers carry only these two verified display identities.
  if ('conversationPhoneLink' in raw) {
    const link = raw.conversationPhoneLink;
    return link && link.taskId === row.id ? identity(link.conversationId) || null : null;
  }
  // Detail windows retain the original association rather than the compact header.
  const link = raw.conversationPhoneHandoff;
  if (!link || link.format !== 'pocketworld.conversation-phone-handoff.v1'
      || link.taskId !== row.id || link.requestId !== row.id || !identity(link.conversationId)
      || link.source?.conversationId !== link.conversationId || link.goal !== raw.goal
      || !Number.isSafeInteger(link.createdAt) || link.createdAt < 1 || link.createdAt !== raw.createdAt) return null;
  return link.conversationId;
}
