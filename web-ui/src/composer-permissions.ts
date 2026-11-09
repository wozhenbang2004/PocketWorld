type Row = Record<string, unknown>;
const record = (value: unknown): Row | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : null;
const records = (value: unknown): Row[] => Array.isArray(value) ? value.map(record).filter((x): x is Row => x !== null) : [];
const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 240;
const nonnegative = (value: unknown): number | null => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
export type ComposerPermissionView = {
  taskId: string; goal: string; status: string; needsReview: boolean;
  phase: string | null; toolsUsed: number | null; toolLimit: number | null;
  modelCallsUsed: number | null; modelCallLimit: number | null; deadlineAt: number | null;
  scopeVerified: boolean;
};
/** Read-only native task projection. Never use it for grant/admission or cross-conversation lookup. */
export function composerPermissionView(nativeState: unknown): ComposerPermissionView | null {
  const state = record(nativeState), conversation = state?.conversationId;
  if (!identifier(conversation)) return null;
  const tasks = records(state?.tasks), messages = records(state?.messages);
  const isOwner = (task: Row) => {
    const raw = record(task.raw);
    return identifier(task.id) && raw?.id === task.id && raw?.conversationId === conversation &&
      raw?.actorId === 'owner' && raw?.spaceId === 'private' && raw?.kind === 'assistant' &&
      raw?.executionRole !== 'owner_work';
  };
  const foreground = record(state?.foregroundTurn);
  let selected: Row | undefined;
  if (foreground?.conversationId === conversation && identifier(foreground.taskId)) {
    selected = tasks.find(t => isOwner(t) && t.id === foreground.taskId &&
      record(t.raw)?.runId === foreground.runId && record(t.raw)?.coordinatorEpoch === foreground.epoch);
    if (!selected) return null; // Never substitute an unrelated old task for a currently running turn.
  } else {
    const anchored = new Set(messages.map(m => m.taskId).filter(identifier));
    const eligible = tasks.filter(t => isOwner(t) && identifier(t.id) && anchored.has(t.id));
    selected = eligible.reverse().find(t => ['awaiting_approval','waiting_approval','waiting_permission','awaiting_user','needs_confirmation'].includes(String(t.status))) ?? eligible[0];
  }
  if (!selected) return null;
  const raw = record(selected.raw)!;
  const scope = record(raw.executionScope);
  return {
    taskId: String(selected.id), goal: String(selected.goal ?? '当前对话任务').slice(0,200),
    status: String(selected.status ?? raw.status ?? 'unknown'),
    needsReview: ['awaiting_approval','waiting_approval','waiting_permission','awaiting_user','needs_confirmation'].includes(String(selected.status)),
    phase: typeof scope?.executionPhase === 'string' ? scope.executionPhase : null,
    toolsUsed: nonnegative(scope?.toolsUsed), toolLimit: nonnegative(scope?.toolLimit),
    modelCallsUsed: nonnegative(scope?.modelCallsUsed), modelCallLimit: nonnegative(scope?.modelCallLimit),
    deadlineAt: nonnegative(scope?.deadlineAt), scopeVerified: scope !== null
  };
}
