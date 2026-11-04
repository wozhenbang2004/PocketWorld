type Data = Record<string, any>;
export type TokenBudgetDraft = { scope: 'default' | 'task' | 'daily'; id: string; title: string; revision: number; enabled: boolean; limit: string; zoneId: string; previous: Data };
const object = (value: any): value is Data => value != null && typeof value === 'object' && !Array.isArray(value);
const integer = (value: any, min: number, max = Number.MAX_SAFE_INTEGER) => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max;

export function tokenBudgetSnapshot(value: Data, taskId = '', windowId?: string): Data {
  if (!object(value) || value.format !== 'pocketworld.owner-token-budgets.v1' || value.readOnly !== true || value.executionAuthorized !== false || typeof value.windowId !== 'string' || !value.windowId || windowId && value.windowId !== windowId)
    throw new Error('原预算读取窗口不可核对，请重新读取。');
  if (!object(value.defaults) || !integer(value.defaults.revision, 0) || typeof value.defaults.enabled !== 'boolean' || !integer(value.defaults.limit, 0, 1e12) || !Array.isArray(value.routes))
    throw new Error('原预算策略没有完整返回，未按未启用处理。');
  if (taskId && (!object(value.task) || value.task.taskId !== taskId || typeof value.task.ownerTaskId !== 'string')) throw new Error('收到的预算不属于原任务。');
  for (const route of value.routes) if (!object(route) || typeof route.routeKey !== 'string' || typeof route.label !== 'string' || !object(route.budget)) throw new Error('原连接预算记录不完整。');
  return value;
}

export function tokenBudgetDraft(scope: TokenBudgetDraft['scope'], id: string, title: string, budget: Data): TokenBudgetDraft {
  if (!object(budget) || !integer(budget.revision, 0) || typeof budget.enabled !== 'boolean' || !integer(budget.limit, 0, 1e12)) throw new Error('请重新读取原预算策略，未知值不会作为 0 保存。');
  return { scope, id, title, revision: budget.revision, enabled: budget.enabled, limit: budget.limit > 0 ? String(budget.limit) : '', zoneId: scope === 'daily' ? String(budget.nextZoneId || budget.zoneId || '') : '', previous: budget };
}

export function tokenBudgetCurrentPolicy(snapshot: Data, draft: Pick<TokenBudgetDraft, 'scope' | 'id'>): Data {
  if (draft.scope === 'default' && draft.id === '' && object(snapshot.defaults)) return snapshot.defaults;
  if (draft.scope === 'task' && snapshot.task?.editable === true && snapshot.task.taskId === draft.id && snapshot.task.ownerTaskId === draft.id && object(snapshot.task.budget)) return snapshot.task.budget;
  if (draft.scope === 'daily' && Array.isArray(snapshot.routes)) {
    const rows = snapshot.routes.filter((route: Data) => route.routeKey === draft.id);
    if (rows.length === 1 && object(rows[0].budget)) return rows[0].budget;
  }
  throw new Error('原预算对象已变化，草稿保留；请回原对象核对，未套用到其它预算。');
}

/** Only called after the owner explicitly acknowledges a fresh read. Typed edits survive. */
export function tokenBudgetRebasedDraft(draft: TokenBudgetDraft, snapshot: Data): TokenBudgetDraft {
  const actual = tokenBudgetCurrentPolicy(snapshot, draft);
  const checked = tokenBudgetDraft(draft.scope, draft.id, draft.title, actual);
  return { ...draft, revision: checked.revision, previous: actual };
}

export function tokenBudgetReviewInput(draft: TokenBudgetDraft, windowId: string): Data {
  if (!windowId || !integer(draft.revision, 0) || typeof draft.enabled !== 'boolean' || !['default', 'task', 'daily'].includes(draft.scope) || typeof draft.id !== 'string' || draft.scope === 'default' && draft.id !== '' || draft.scope !== 'default' && !draft.id)
    throw new Error('请重新读取原预算范围。');
  const text = draft.limit.trim();
  const limit = text === '' && !draft.enabled ? 0 : /^[0-9]+$/.test(text) ? Number(text) : NaN;
  if (!integer(limit, draft.enabled ? 1 : 0, 1e12)) throw new Error('token 上限须为 1–1,000,000,000,000 的整数；请直接填写数字。');
  if (draft.scope === 'daily') {
    try { if (!draft.zoneId.trim() || draft.zoneId.length > 128) throw new Error(); new Intl.DateTimeFormat('zh-CN', { timeZone: draft.zoneId }).format(); }
    catch { throw new Error('请填写有效时区，例如 Asia/Shanghai。'); }
  }
  return { windowId, scope: draft.scope, id: draft.id, expectedRevision: draft.revision, enabled: draft.enabled, limit, zoneId: draft.scope === 'daily' ? draft.zoneId : '' };
}

export function tokenBudgetReview(value: Data, expected: Data): Data {
  if (!object(value) || value.windowId !== expected.windowId || typeof value.reviewHandle !== 'string' || !value.reviewHandle || !object(value.input) || !object(value.previous) || value.executionStarted !== false || value.effectsApproved !== false)
    throw new Error('未取得这次预算修改的完整审阅，请重新读取。');
  for (const field of ['scope', 'id', 'expectedRevision', 'enabled', 'limit', 'zoneId']) if (value.input[field] !== expected[field]) throw new Error('原生预算审阅与本次输入不一致，尚未保存。');
  return value;
}

export function tokenBudgetSaved(value: Data, review: Data): Data {
  if (!object(value) || value.windowId !== review.windowId || value.saved !== true || value.scope !== review.input.scope || value.id !== review.input.id || !object(value.receipt) || value.executionStarted !== false || value.effectsApproved !== false || value.receipt.revision !== review.input.expectedRevision + 1 || value.receipt.enabled !== review.input.enabled || review.input.enabled && value.receipt.limit !== review.input.limit)
    throw new Error('预算保存结果尚未核对，请重新读取原设置；不要重复提交。');
  return value.receipt;
}

export function tokenBudgetCount(value: any): string { return integer(value, 0) ? value.toLocaleString('zh-CN') : '尚未核对'; }
