import { phoneConversationId } from './phone-start.ts';
import { getRecentTaskProgress, taskNeedsAttention, taskNeedsReconciliation } from './task-presentation.ts';

type Data = Record<string, any>;
const object = (value: unknown): Data | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : null;
const id = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0 && value.length <= 240;
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0;
const ended = new Set(['completed', 'failed', 'cancelled', 'interrupted', 'needs_confirmation', 'awaiting_user', 'reconciling', 'paused']);

export type WorkIdentity = { taskId: string; runId: string; epoch: number };
export type ReplyContext = WorkIdentity & { replyId?: string | null; deliveryId?: string | null; replyToMessageIds: string[] };
export type TaskResult = WorkIdentity & { kind: 'automation' | 'team' | 'owner_work'; deliveryId: string; status: string; endedAt: number; summaryChars: number; summaryTruncated: boolean; noticeKind?: 'capacity_recovery' | 'capacity_cancel' | null };

/** These fields were checked by native projection; they only support reading and navigation. */
export function messageReplyContext(message: unknown): ReplyContext | null {
  const row = object(message), value = object(row?.replyContext);
  if (row?.role !== 'assistant' || !value || !id(row.taskId) || value.taskId !== row.taskId || !id(value.runId) || !positive(value.epoch)
      || !id(value.replyId) && !id(value.deliveryId) || !Array.isArray(value.replyToMessageIds) || value.replyToMessageIds.length > 500
      || !value.replyToMessageIds.every(id)) return null;
  return { taskId: row.taskId, runId: value.runId, epoch: value.epoch, replyId: id(value.replyId) ? value.replyId : null,
    deliveryId: id(value.deliveryId) ? value.deliveryId : null, replyToMessageIds: [...new Set<string>(value.replyToMessageIds)] };
}

export function messageTaskResult(message: unknown): TaskResult | null {
  const row = object(message), context = messageReplyContext(message), value = object(row?.taskResult);
  if (!context || !value || !['automation', 'team', 'owner_work'].includes(value.kind) || value.taskId !== context.taskId
      || value.runId !== context.runId || value.epoch !== context.epoch || !id(value.deliveryId) || value.deliveryId !== context.deliveryId
      || !(value.kind === 'owner_work' ? /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.deliveryId) : value.deliveryId.startsWith(`${value.kind}:`)) || !ended.has(value.status) || !positive(value.endedAt)
      || !Number.isSafeInteger(value.summaryChars) || value.summaryChars < 0 || typeof value.summaryTruncated !== 'boolean') return null;
  if (value.noticeKind != null && (value.kind !== 'owner_work' || !['capacity_recovery', 'capacity_cancel'].includes(value.noticeKind))) return null;
  return { kind: value.kind, taskId: value.taskId, runId: value.runId, epoch: value.epoch, deliveryId: value.deliveryId,
    status: value.status, endedAt: value.endedAt, summaryChars: value.summaryChars, summaryTruncated: value.summaryTruncated, noticeKind: value.noticeKind ?? null };
}

export function ownerWorkIdentity(task: unknown): WorkIdentity | null {
  const row = object(task), raw = object(row?.raw);
  if (!row || !raw || !id(row.id) || raw.id !== row.id || raw.actorId !== 'owner' || raw.spaceId !== 'private'
      || raw.executionRole !== 'owner_work') return null;
  const admitted = id(raw.runId) && positive(raw.coordinatorEpoch);
  const queued = raw.runId === '' && raw.coordinatorEpoch === 0 && row.status === 'queued' && raw.controls?.canStop === true;
  if (!admitted && !queued) return null;
  return { taskId: row.id, runId: raw.runId, epoch: raw.coordinatorEpoch };
}

export const workIdentityKey = (value: WorkIdentity) => JSON.stringify([value.taskId, value.runId, value.epoch]);

/** Navigation only. Returning to a conversation grants no permission to continue
 * a task; its current owner/run and controls must still be read by the host. */
export function privateTaskConversationId(task: unknown): string | null {
  const row = object(task), raw = object(row?.raw);
  if (!row || !raw || raw.id !== row.id || raw.actorId !== 'owner' || raw.spaceId !== 'private') return null;
  const phone = phoneConversationId(row);
  if (phone) return phone;
  return id(raw.conversationId) ? raw.conversationId : null;
}

/** A background run never owns the main composer stop button or its thinking indicator. */
export function foregroundConversationTurn(state: Data): Data | null {
  const value = object(Object.hasOwn(state, 'foregroundTurn') ? state.foregroundTurn : state.chatActiveTurn);
  if (!value || value.executionRole === 'owner_work' || value.conversationId !== state.conversationId || !id(value.taskId)
      || !id(value.runId) || !positive(value.epoch)) return null;
  const task = Array.isArray(state.tasks) ? state.tasks.find((row: Data) => row.id === value.taskId) : null;
  return task?.raw?.executionRole === 'owner_work' ? null : value;
}

/** A visible activity belongs to the exact foreground run, not another task or
 * an earlier attempt. Recorded details stay labelled as recent progress. */
export function conversationActivityLabel(state: Data): string {
  const turn = foregroundConversationTurn(state);
  if (!turn) return '';
  if (turn.resourceState === 'queued') return '等待执行';
  if (turn.resourceState === 'retained') return '等待补充';
  if (turn.resourceState === 'stopping') return '正在停止';
  const task = Array.isArray(state.tasks) ? state.tasks.find((row: Data) => row.id === turn.taskId) : null;
  const raw = object(task?.raw);
  if (!task || raw?.runId !== turn.runId || raw?.coordinatorEpoch !== turn.epoch) return '正在处理这次回复';
  const progress = getRecentTaskProgress(task);
  if (progress.runId !== turn.runId) return '正在处理这次回复';
  if (progress.status === 'unknown' && progress.source === 'task') return progress.title;
  if (progress.source !== 'task' && ['unknown', 'error', 'waiting', 'stopped'].includes(progress.status)) {
    const label = progress.status === 'unknown' ? '结果待核对' : progress.stateLabel;
    const detail = progress.source === 'event' ? progress.detail : progress.title;
    return detail && detail !== label ? `${label} · ${detail}` : label;
  }
  if (progress.source === 'operation') return `最近调用 · ${progress.title}`;
  if (progress.source === 'event' && progress.detail) return `最近进展 · ${progress.detail}`;
  return '正在处理这次回复';
}

/** Only authoritative success with no unresolved work may give space back to
 * the answer. A completed label never hides a reconciliation requirement. */
export function conversationTaskCompact(task: Data): boolean {
  return task.status === 'completed' && !taskNeedsAttention(task);
}

/** Summary priority only; opening the original task still owns all decisions. */
export function conversationWorkNeedsAttention(task: Data): boolean {
  return taskNeedsAttention(task) || task.status === 'failed' && task.raw?.executionRole === 'owner_work' && task.raw?.controls?.canContinue === true;
}

/** Only unresolved decisions and uncertain external effects interrupt the composer.
 * Routine goal confirmation, pauses and finished results live in task details. */
export function conversationInlineAttention(task: Data): boolean {
  if (!id(task?.id)) return false;
  const status = String(task.status || '');
  // A settled reply must never pin an old decision over the composer.
  // Unconfirmed audit history remains accessible in the original task detail.
  if (['completed', 'failed', 'cancelled', 'canceled', 'stopped'].includes(status)) return false;
  if (taskNeedsReconciliation(task)) return true;
  if (['waiting_approval', 'awaiting_approval', 'waiting_permission', 'awaiting_user', 'waiting_user'].includes(status)) return true;
  return Array.isArray(task.raw?.approvals) && task.raw.approvals.some((approval: Data) =>
    ['pending', 'waiting'].includes(String(approval?.state || approval?.status || '')));
}

export function conversationWorkActionLabel(task: Data): string {
  if (taskNeedsReconciliation(task)) return '核对操作';
  if (task.status === 'needs_confirmation') return '核对结果';
  if (['waiting_approval', 'awaiting_approval', 'waiting_permission'].includes(task.status)) return '处理授权';
  if (['awaiting_user', 'waiting_user'].includes(task.status)) return '补充信息';
  if (task.status === 'failed') return '查看原因';
  if (['paused', 'interrupted'].includes(task.status)) return '继续处理';
  if (taskNeedsAttention(task)) return '查看待办';
  if (task.status === 'queued') return '查看排队';
  if (['running', 'preparing', 'verifying', 'pausing', 'stopping', 'cancelling'].includes(task.status)) return '查看进展';
  return '查看详情';
}

export function backgroundConversationWork<T extends Data>(tasks: T[], ownerWork: T[], conversationId: unknown): T[] {
  if (!id(conversationId)) return [];
  const selected = new Map<string, T>();
  for (const task of [...tasks, ...ownerWork]) {
    const raw = object(task.raw);
    if (!id(task.id) || !raw || raw.id !== task.id || raw.actorId !== 'owner' || raw.spaceId !== 'private') continue;
    const phone = phoneConversationId(task) === conversationId;
    const associated = raw.conversationId === conversationId && (raw.executionRole === 'owner_work' || raw.feature === 'teams'
      || raw.origin?.kind === 'automation' && raw.origin?.originConversationId === conversationId);
    const resumable = raw.executionRole === 'owner_work' && raw.controls?.canContinue === true && ['failed', 'interrupted'].includes(task.status);
    if ((!phone && !associated) || ['completed', 'failed', 'cancelled', 'canceled', 'stopped', 'interrupted'].includes(task.status) && !resumable) continue;
    selected.set(task.id, task);
  }
  return [...selected.values()].map(task => ({ task, attention: conversationWorkNeedsAttention(task) }))
    .sort((a, b) => Number(b.attention) - Number(a.attention) || Number(b.task.updatedAt || 0) - Number(a.task.updatedAt || 0))
    .map(({ task }) => task);
}
