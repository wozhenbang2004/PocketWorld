import { foregroundConversationTurn } from './conversation-work';

type Data = Record<string, any>;
const object = (value: unknown): Data | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Data : null;
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0;

export type ConversationStream = {
  taskId: string;
  runId: string;
  epoch: number;
  requestId: string;
  generation: number;
  sequence: number;
  text: string;
  turnEnded: boolean;
  failed: boolean;
  truncated: boolean;
};

/** Read-only projection of the native ephemeral draft. It never becomes a message,
 * a completion receipt, or input to a subsequent model request. */
export function foregroundConversationStream(state: Data): ConversationStream | null {
  const turn = foregroundConversationTurn(state);
  if (!turn || ['queued', 'retained', 'stopping'].includes(turn.resourceState)) return null;
  const task = Array.isArray(state.tasks) ? state.tasks.find((row: Data) => row.id === turn.taskId) : null;
  const raw = object(task?.raw), draft = object(object(state.modelDrafts)?.[turn.taskId]);
  if (!raw || !draft || raw.id !== turn.taskId || raw.runId !== turn.runId || raw.coordinatorEpoch !== turn.epoch
      || raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.conversationId !== state.conversationId
      || raw.executionRole === 'owner_work' || !['preparing', 'running'].includes(task.status)
      || draft.taskId !== turn.taskId || draft.runId !== turn.runId || draft.epoch !== turn.epoch
      || typeof draft.requestId !== 'string' || !/^[A-Za-z0-9_-]{1,160}$/.test(draft.requestId)
      || !positive(draft.generation) || !positive(draft.sequence) || typeof draft.text !== 'string'
      || !draft.text.trim() || draft.text.length > 32768 || typeof draft.turnEnded !== 'boolean'
      || typeof draft.failed !== 'boolean' || typeof draft.truncated !== 'boolean') return null;
  return { taskId: draft.taskId, runId: draft.runId, epoch: draft.epoch, requestId: draft.requestId,
    generation: draft.generation, sequence: draft.sequence, text: draft.text,
    turnEnded: draft.turnEnded, failed: draft.failed, truncated: draft.truncated };
}
