import { ownerWorkIdentity, type WorkIdentity } from './conversation-work';

type Fields = Record<string, unknown>;
const record = (value: unknown): Fields => value && typeof value === 'object' && !Array.isArray(value) ? value as Fields : {};
const stopped = new Set(['paused', 'interrupted', 'failed', 'awaiting_user', 'needs_confirmation']);
const unavailable = '暂时无法核对原任务的续接条件。请重新读取原工作，不要重复发起。';
const blocked: Record<string, string> = {
  stop_requested: '这件工作已经请求停止，不能开始新一轮。请查看原工作的停止记录。',
  unresolved_effect: '有操作还没有确定结果。先在原工作核对是否已经发生，再决定要不要继续。',
  related_unavailable: '关联工作的状态还不能核对。先查看原协作记录，不要重复派发。',
  waiting_children: '关联工作还没结束，先等它交回结果，再决定要不要继续。',
  result_limit: '原工作的结果留存已满，不能再开一轮。已有结果不会因此删除。',
  deadline_reached: '原任务的执行期限已到，当前不能再开一轮。旧的执行记录仍在。',
  budget_spent: '原任务的工具或模型调用次数已用尽，不能用同一份额度再开一轮。',
  original_read_required: unavailable,
};

/** Reading state only. Original native controls, not a checkpoint hint or UI label, admit work. */
export function ownerWorkContinuationNote(task: unknown): string | null {
  const shown = record(task), raw = record(shown.raw), controls = record(raw.controls);
  if (!ownerWorkIdentity(task) || !stopped.has(String(shown.status))) return null;
  if (raw.contextAvailable === false || raw.taskProjection === 'source_unavailable' || raw.sourceCurrentReadRequired === true)
    return '原请求来源不可用，目前只能核对状态，不能继续执行。';
  if (controls.canContinue !== true)
    return blocked[String(controls.continuationState)] || (controls.resultCapacityState === 'full' ? blocked.result_limit : unavailable);
  const history = controls.continuationHistory;
  return history === 'saved_history' ? '上轮的对话记录已保存。新一轮会读取这些记录，而不是直接恢复上一步。未发送的补充不会一并提交。'
    : history === 'goal_only' ? '上轮没有保存可接续的对话。新一轮会按原目标和已有操作回执重新判断；未发送的补充不会一并提交。'
    : '尚未核实上轮保存了多少进展。新一轮仍在这件工作里进行，不保证接上原来那一步；未发送的补充不会一并提交。';
}

export type WorkControlFeedback = { pending: boolean; unknown: boolean; message: string; usedForContinuation?: boolean };
export type WorkContinuationResult = { feedback: WorkControlFeedback; next?: WorkIdentity };

/** A changed task snapshot by itself is not proof that this click admitted a particular run. */
export function ownerWorkContinuationReply(value: unknown, original: WorkIdentity): WorkContinuationResult {
  const receipt = record(value), next = record(receipt.continuationReceipt);
  if (receipt.status === 'rejected') return { feedback: { pending: false, unknown: false,
    message: typeof receipt.message === 'string' && receipt.message.trim() ? receipt.message : '这次续接未受理，请核对原工作后再试。' } };
  if (receipt.controlRequested !== 'continue' || receipt.taskId !== original.taskId || next.taskId !== original.taskId ||
    next.fromRunId !== original.runId || next.fromEpoch !== original.epoch || next.epoch !== original.epoch + 1 ||
    typeof next.runId !== 'string' || !next.runId.trim() || next.runId.length > 240 || next.runId === original.runId)
    return { feedback: { pending: false, unknown: true, message: '还不能确定新一轮是否已受理。请先核对原任务，不要再次提交。' } };
  return { next: { taskId: original.taskId, runId: next.runId, epoch: original.epoch + 1 },
    feedback: { pending: false, unknown: false, message: '原任务已受理新一轮，仍沿用原来的执行范围。实际进展见这件工作的记录。' } };
}
