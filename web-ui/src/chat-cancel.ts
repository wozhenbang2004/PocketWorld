export type ChatCancelIdentity = { conversationId: string; taskId: string; runId: string; epoch: number };

/** Only already-projected native identity is used, with no content or inferred replacement run. */
export function chatCancelIdentity(state: Record<string, any>): ChatCancelIdentity | null {
  const conversationId = state.conversationId;
  const turn = state.chatActiveTurn;
  if (state.chatBusy !== true || typeof conversationId !== 'string' || !conversationId ||
      !turn || turn.conversationId !== conversationId ||
      typeof turn.taskId !== 'string' || !turn.taskId || typeof turn.runId !== 'string' || !turn.runId ||
      !Number.isInteger(turn.epoch) || turn.epoch < 1) return null;
  return { conversationId, taskId: turn.taskId, runId: turn.runId, epoch: turn.epoch };
}

export function sameChatCancelIdentity(state: Record<string, any>, expected: ChatCancelIdentity): boolean {
  const actual = chatCancelIdentity(state);
  return !!actual && actual.conversationId === expected.conversationId && actual.taskId === expected.taskId &&
    actual.runId === expected.runId && actual.epoch === expected.epoch;
}

export function chatCancelKey(identity: ChatCancelIdentity): string {
  return JSON.stringify([identity.conversationId, identity.taskId, identity.runId, identity.epoch]);
}

export function chatCancelFeedback<T>(state: Record<string, any>, feedback: Record<string, T>): T | undefined {
  const current = chatCancelIdentity(state);
  return current ? feedback[chatCancelKey(current)] : undefined;
}
