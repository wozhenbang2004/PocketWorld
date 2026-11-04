export type PhoneStartReceipt = {
  status: 'accepted' | 'rejected' | 'unknown';
  taskId?: string;
  message: string;
};

/** Only a native receipt for this start can consume its reviewed draft. */
export function phoneStartReceipt(value: unknown): PhoneStartReceipt {
  const row = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  const taskId = typeof row.taskId === 'string' && row.taskId.trim() ? row.taskId : undefined;
  const status = row.status === 'rejected' ? 'rejected'
    : row.status === 'accepted' && taskId ? 'accepted' : 'unknown';
  const message = typeof row.message === 'string' && row.message ? row.message
    : status === 'accepted' ? '原手机任务已创建。'
    : status === 'rejected' ? '这次任务尚未接纳，草稿仍保留。'
    : '启动结果待核对。请先查看任务记录，避免再次提交同一目标。';
  return { status, ...(taskId ? { taskId } : {}), message };
}

export function phoneStartFailure(error: unknown): PhoneStartReceipt {
  const failure = error && typeof error === 'object' ? error as Record<string, unknown> : {};
  return phoneStartReceipt({
    status: failure.code === 'not_accepted' || failure.code === 'invalid_request' || failure.code === 'not_sent' ? 'rejected' : 'unknown',
    message: typeof failure.message === 'string' ? failure.message : undefined,
  });
}

export function phoneRunIdentity(task: unknown): { taskId: string; runId: string; epoch: number } | null {
  if (!task || typeof task !== 'object') return null;
  const row = task as Record<string, any>, raw = row.raw;
  if (typeof row.id !== 'string' || !row.id || !raw || typeof raw.runId !== 'string' || !raw.runId
      || !Number.isInteger(raw.coordinatorEpoch) || raw.coordinatorEpoch < 1) return null;
  return { taskId: row.id, runId: raw.runId, epoch: raw.coordinatorEpoch };
}
