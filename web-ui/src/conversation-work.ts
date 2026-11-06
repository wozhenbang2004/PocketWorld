import { phoneConversationId } from './phone-start.ts';

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

/** A background run never owns the main composer stop button or its thinking indicator. */
export function foregroundConversationTurn(state: Data): Data | null {
  const value = object(Object.hasOwn(state, 'foregroundTurn') ? state.foregroundTurn : state.chatActiveTurn);
  if (!value || value.executionRole === 'owner_work' || value.conversationId !== state.conversationId || !id(value.taskId)
      || !id(value.runId) || !positive(value.epoch)) return null;
  const task = Array.isArray(state.tasks) ? state.tasks.find((row: Data) => row.id === value.taskId) : null;
  return task?.raw?.executionRole === 'owner_work' ? null : value;
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
  return [...selected.values()].sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0));
}
