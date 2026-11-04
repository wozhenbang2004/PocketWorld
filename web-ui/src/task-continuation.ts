/** Presentation checks for the original native review. Public metadata never grants continuation. */
type Data = Record<string, any>;
const object = (value: unknown): Data => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
const epoch = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0 && Number(value) <= 2147483647;

export type ProjectContinuation = { feedbackRequired: true; conversationId: string; reason?: string };
export type ContinuationOriginal = { taskId: string; conversationId: string; runId: string; epoch: number; reviewHandle: string };
export type ContinuationSubmission = { original: ContinuationOriginal; status: 'pending' | 'unknown' | 'continued' | 'rejected'; message: string };

/** Lookup/display hint only; tasks.review still decides which actions are available. */
export function privateProjectTask(value: unknown): boolean {
  const task = object(value), raw = object(task.raw), context = object(raw.developmentContext);
  return text(task.id) && raw.kind === 'assistant' && raw.actorId === 'owner' && raw.spaceId === 'private' && raw.origin == null && text(context.projectId) && text(context.checkoutId);
}

export function projectContinuation(value: unknown): ProjectContinuation | null {
  const review = object(value), candidate = object(review.projectContinuation), task = object(review.task), raw = object(task.raw);
  if (!privateProjectTask(task) || candidate.feedbackRequired !== true || typeof candidate.conversationId !== 'string' ||
      candidate.conversationId !== (typeof raw.conversationId === 'string' ? raw.conversationId : '') ||
      candidate.reason !== undefined && !text(candidate.reason)) return null;
  return { feedbackRequired: true, conversationId: candidate.conversationId, ...(candidate.reason ? { reason: candidate.reason } : {}) };
}

export function continuationRequest(value: unknown, taskId: string, feedback: string) {
  const review = object(value), task = object(review.task), raw = object(task.raw), metadata = projectContinuation(review);
  const note = feedback.trim();
  if (!metadata || metadata.reason || !text(metadata.conversationId) || task.id !== taskId || raw.id !== taskId ||
      !text(review.reviewHandle) || !text(raw.runId) || !epoch(raw.coordinatorEpoch) ||
      !Array.isArray(review.actions) || !review.actions.some((action: unknown) => object(action).action === 'tasks.resume'))
    throw new Error('请先读取这个原项目任务的继续审阅。');
  if (!note || note.length > 8000) throw new Error('请填写 1–8000 字的本次修改要求。');
  const original: ContinuationOriginal = { taskId, conversationId: metadata.conversationId, runId: raw.runId, epoch: raw.coordinatorEpoch, reviewHandle: review.reviewHandle };
  return { original, args: { reviewHandle: original.reviewHandle, confirmed: true as const, feedback: note } };
}

export function continuationReceiptMatches(value: unknown, original: ContinuationOriginal): boolean {
  const result = object(value);
  return result.continued === true && result.taskId === original.taskId && result.conversationId === original.conversationId &&
    text(result.runId) && result.runId !== original.runId && epoch(result.epoch) && result.epoch > original.epoch;
}

export function continuationUnknown(current: ContinuationSubmission): ContinuationSubmission {
  return current.status === 'pending' ? { ...current, status: 'unknown', message: '尚未确认这次续接结果。请核对原任务状态；反馈和对话草稿均保留，没有再次提交。' } : current;
}

export function continuationSettled(current: ContinuationSubmission, result: unknown, windowCurrent: boolean): ContinuationSubmission {
  if (!windowCurrent || current.status !== 'pending') return continuationUnknown(current);
  if (!continuationReceiptMatches(result, current.original)) return continuationUnknown(current);
  return { ...current, status: 'continued', message: '原项目任务已续接。对话草稿尚未发送，请返回原对话后单独发送；实际进展以原任务记录为准。' };
}

export function continuationFailed(current: ContinuationSubmission, error: unknown, windowCurrent: boolean): ContinuationSubmission {
  if (!windowCurrent || current.status !== 'pending') return continuationUnknown(current);
  const failure = object(error);
  if (failure.code === 'not_sent' || failure.code === 'not_accepted') return { ...current, status: 'rejected', message: typeof failure.message === 'string' ? failure.message : '这次续接未受理，反馈和草稿仍保留。' };
  return continuationUnknown(current);
}

export function continuationBlocks(value?: ContinuationSubmission): boolean { return value?.status === 'pending' || value?.status === 'unknown'; }
