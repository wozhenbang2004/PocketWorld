/**
 * Display-only projection of a121 TaskAuditStore.page and UiTask fields.
 * A bounded record is never an execution authority or proof that a goal succeeded.
 * That SQL projection has no execution duration; createdAt is registration time.
 */
type Row = Record<string, unknown>;

export type OperationStatus = 'running' | 'waiting' | 'needs_approval' | 'unknown' | 'error' | 'returned' | 'stopped';

export interface TaskOperation {
  operationId: string;
  tool: string;
  state: string;
  runId: string;
  position?: number;
  createdAt?: number;
  bodyBytes?: number;
  bodyAvailable: boolean;
  readLimited: boolean;
  requiresReconciliation?: boolean;
  /** Display-only classification supplied by the original native audit projection. */
  toolOutcome?: unknown;
  arguments?: unknown;
  receipt?: unknown;
}

export interface OperationPresentation {
  id: string;
  name: string;
  status: OperationStatus;
  stateLabel: string;
  title: string;
  detail: string;
  runId: string;
  readLimited: boolean;
  arguments?: unknown;
  result?: unknown;
  durationMs?: number;
}

export interface TaskProgress {
  title: string;
  detail: string;
  status: OperationStatus;
  stateLabel: string;
  source: 'event' | 'operation' | 'task';
  runId: string;
  operationId?: string;
  position?: number;
  partial: boolean;
}

export interface TaskEventRecord {
  id: string;
  position?: number;
  runId: string;
  isCurrentRun: boolean;
  type: string;
  status: string;
  title: string;
  detail: string;
  stateLabel: string;
  at?: number;
  bodyAvailable: boolean;
  readLimited: boolean;
  partial: boolean;
}

export interface TaskEventHistory {
  entries: TaskEventRecord[];
  source: 'page' | 'snapshot' | 'none';
  /** False alone does not establish complete history when no original page was read. */
  hasMore: boolean;
  readLimited: boolean;
  coreReadLimited: boolean;
  completeHistory: boolean;
  partial: boolean;
  unavailableReason?: 'foreign_page' | 'not_loaded';
}

const row = (value: unknown): Row | undefined => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : undefined;
const text = (value: unknown): string => typeof value === 'string' ? value : '';
const number = (value: unknown): number | undefined => typeof value === 'number' && Number.isFinite(value) ? value : undefined;
const rows = (value: unknown): Row[] => Array.isArray(value) ? value.map(row).filter((value): value is Row => value !== undefined) : [];
const has = (value: object, key: string) => Object.prototype.hasOwnProperty.call(value, key);
type ToolOutcomeKind = 'success' | 'correctable_failure' | 'needs_user' | 'effect_unknown';
function nativeOutcome(value: unknown): ToolOutcomeKind | 'invalid' | undefined {
  if (value === undefined) return undefined;
  const contract = row(value);
  const next: Record<string, string> = {success: 'continue', correctable_failure: 'revise_request', needs_user: 'ask_owner', effect_unknown: 'reconcile_original'};
  const kind = text(contract?.kind);
  return contract?.format === 'pocketworld.tool-outcome.v1' && has(next, kind) && contract.nextAction === next[kind] && contract.automaticReplayAllowed === false
    ? kind as ToolOutcomeKind : 'invalid';
}
const excerpt = (value: string, limit = 240): string => {
  const points = Array.from(value);
  return points.length > limit ? points.slice(0, limit).join('') + '…' : value;
};

function taskParts(task: unknown) {
  const header = row(task) ?? {};
  const raw = row(header.raw) ?? header;
  return {header, raw, id: text(header.id) || text(raw.id), runId: text(raw.runId)};
}

function pageFor(raw: Row, key: string, taskId: string, section: string): Row | undefined {
  const page = row(raw[key]);
  if (!page || text(page.taskId) && text(page.taskId) !== taskId || text(page.section) && text(page.section) !== section) return undefined;
  return page;
}

function operation(value: unknown): TaskOperation | undefined {
  const source = row(value);
  const operationId = text(source?.operationId);
  if (!source || !operationId) return undefined;
  const readLimited = source.readLimited === true || source.status === 'read_limited';
  const bodyAvailable = !readLimited && source.bodyAvailable !== false &&
    (source.bodyAvailable === true || source.status === 'available' || has(source, 'arguments') || has(source, 'receipt'));
  return {
    operationId, tool: text(source.tool), state: text(source.state), runId: text(source.runId),
    position: number(source.position), createdAt: number(source.createdAt), bodyBytes: number(source.bodyBytes),
    bodyAvailable, readLimited, requiresReconciliation: source.requiresReconciliation === true,
    ...(has(source, 'toolOutcome') ? {toolOutcome: source.toolOutcome} : {}),
    ...(bodyAvailable && has(source, 'arguments') ? {arguments: source.arguments} : {}),
    ...(bodyAvailable && has(source, 'receipt') ? {receipt: source.receipt} : {}),
  };
}

/** Preserve the original bounded page order, including earlier runs and limited rows. */
export function getTaskOperations(task: unknown): TaskOperation[] {
  const {raw, id} = taskParts(task);
  const page = pageFor(raw, 'historyOperationPage', id, 'operations');
  const result: TaskOperation[] = [];
  const seen = new Set<string>();
  for (const source of [...rows(page?.entries), ...rows(raw.operations)]) {
    if (text(source.taskId) && text(source.taskId) !== id) continue;
    const value = operation(source);
    if (!value) continue;
    const key = value.runId + '\u0000' + value.operationId;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(value);
  }
  return result;
}

/** Original task events are an activity history, not generated execution steps. */
export function getTaskEventHistory(task: unknown): TaskEventHistory {
  const {raw, id, runId} = taskParts(task);
  const declaredPage = row(raw.historyEventPage);
  const page = pageFor(raw, 'historyEventPage', id, 'events');
  const coreReadLimited = raw.taskCoreReadLimited === true;
  if (declaredPage && !page) {
    return {entries: [], source: 'none', hasMore: false, readLimited: false, coreReadLimited,
      completeHistory: false, partial: true, unavailableReason: 'foreign_page'};
  }
  const source = page ? 'page' : Array.isArray(raw.events) ? 'snapshot' : 'none';
  const titles: Record<string, string> = {model: '模型记录', tool: '工具调用', observation: '观察现场', verification: '结果核对', user_verification: '结果核对', run: '执行记录', result: '任务结果', owner_input: '收到补充', capability_changed: '能力变化', context_budget: '上下文整理', inference_receipt: '模型调用记录'};
  const labels: Record<string, string> = {running: '执行记录', completed: '已有该记录', failed: '该记录返回错误', unknown: '结果未核实', awaiting_user: '需要你处理', paused: '已暂停', cancelled: '已停止', canceled: '已停止', stopped: '已停止'};
  const entries: TaskEventRecord[] = [];
  const originalRows = page ? rows(page.entries) : rows(raw.events);
  originalRows.forEach((entry, index) => {
    const position = page ? number(entry.position) : undefined;
    // A limited row's embedded event is never read, even if a malformed snapshot includes one.
    const readLimited = page ? entry.status === 'read_limited' : false;
    const event = page ? entry.status === 'available' ? row(entry.event) : undefined : entry;
    const bodyAvailable = event !== undefined;
    if (!bodyAvailable && !text(entry.eventKey) && position === undefined) return;
    const eventRun = event ? text(event.runId) : '';
    const type = event ? text(event.type) || (page ? text(entry.type) : '') : text(entry.type);
    const status = event ? text(event.status) : '';
    const stateLabel = bodyAvailable ? labels[status] || '原记录' : '正文未载入';
    const message = event ? text(event.message) : '';
    // Snapshot fallback keys are only render keys and are never host event/action identities.
    const key = page ? text(entry.eventKey) || `event:${id}:${position ?? index}` :
      text(event?.eventKey) || text(event?.id) || `snapshot-event:${id}:${index}`;
    entries.push({id: key, position, runId: eventRun, isCurrentRun: !!runId && eventRun === runId,
      type, status, title: titles[type] || '任务记录',
      detail: bodyAvailable ? excerpt(message, 360) || stateLabel : '此条记录正文尚未载入', stateLabel,
      at: event ? number(event.at) ?? number(event.timestamp) : undefined,
      bodyAvailable, readLimited, partial: !bodyAvailable || Array.from(message).length > 360});
  });
  const hasMore = page?.hasMore === true;
  const readLimited = page?.readLimited === true || entries.some(entry => entry.readLimited || !entry.bodyAvailable);
  const completeHistory = page?.completeHistory === true && !hasMore && !readLimited && !coreReadLimited;
  return {entries, source, hasMore, readLimited, coreReadLimited, completeHistory,
    partial: !completeHistory, ...(source === 'none' ? {unavailableReason: 'not_loaded' as const} : {})};
}

/** Same conservative precedence as the host's TaskExecutionPresentation.operation. */
export function presentOperation(input: unknown): OperationPresentation {
  const op = operation(input);
  const receiptValue = op?.bodyAvailable ? op.receipt : undefined;
  const receipt = row(receiptValue);
  const state = op?.state ?? '';
  const receiptStatus = text(receipt?.status);
  const code = text(receipt?.code);
  const outcome = op?.bodyAvailable ? nativeOutcome(op.toolOutcome) : undefined;
  const outcomeContract = op?.bodyAvailable ? row(op.toolOutcome) : undefined;
  const completion = outcome !== undefined && outcome !== 'invalid' ? text(outcomeContract?.completion) : '';
  const pendingReceipt = ['accepted','queued','submitting','pending','submitted','dispatched','starting','preparing','running','active','in_progress','pausing','stopping','verifying'].includes(receiptStatus);
  const uncertain = ['unknown', 'uncertain', 'reconciliation_required', 'pending_reconciliation'].includes(state) ||
    state.endsWith('_unknown') || state.endsWith('_unconfirmed') || op?.requiresReconciliation === true || receipt?.requiresReconciliation === true || receipt?.reconciliationRequired === true ||
    receipt !== undefined && (has(receipt, 'accepted') && receipt.accepted === null || receiptStatus === 'unknown' || outcome === undefined && code.includes('unknown'));
  let status: OperationStatus = 'unknown';
  let stateLabel = state ? '原状态：' + excerpt(state, 80) : '记录状态尚不可读取';
  if (uncertain || outcome === 'effect_unknown' || outcome === 'invalid') { status = 'unknown'; stateLabel = '结果待核对'; }
  else if (state === 'project_running') { status = 'running'; stateLabel = '服务已有启动回执，尚无终态'; }
  else if (state === 'project_stopping') { status = 'waiting'; stateLabel = '服务正在收尾，尚未确认释放'; }
  else if (code === 'approval_required') { status = 'needs_approval'; stateLabel = '等待本次操作授权'; }
  else if (state === 'resolved' && receipt?.source === 'owner_reconciliation') {
    status = 'returned';
    stateLabel = receipt.outcome === 'occurred' ? '主人已核对发生，非自动验证' : receipt.outcome === 'not_occurred' ? '主人已核对未发生，不会自动重试' : '主人已记录核对结论';
  }
  else if (state === 'prepared') { status = 'waiting'; stateLabel = '已登记，等待结果'; }
  else if (['cancelled', 'canceled', 'stopped'].includes(receiptStatus)) { status = 'stopped'; stateLabel = '调用已停止，详见原回执'; }
  else if (outcome === 'needs_user') { status = 'waiting'; stateLabel = '需要你处理，已发生的操作仍保留'; }
  else if (outcome === 'correctable_failure') { status = 'error'; stateLabel = '请求可调整，不会自动重放原操作'; }
  else if (outcome === 'success' && (completion === 'pending' || pendingReceipt)) { status = 'waiting'; stateLabel = '已受理或仍在执行，尚无终态'; }
  // Compatibility for pre-contract snapshots only. Fresh native pages already
  // classify the original receipt; these display checks never authorize a retry.
  else if (receipt?.ok === false || receiptStatus === 'failed' || number(receipt?.exitCode) !== undefined && receipt?.exitCode !== 0) { status = 'error'; stateLabel = '调用出错，请核对操作结果'; }
  else if (receipt?.terminal === false) { status = 'waiting'; stateLabel = '已返回部分结果'; }
  else if (receiptValue !== undefined && receiptValue !== null) { status = 'returned'; stateLabel = '已返回，结果可展开查看'; }
  if (op?.readLimited && !uncertain) stateLabel += ' · 正文未在当前读取范围内';
  const name = op?.tool || '工具记录';
  return {
    id: op?.operationId ?? '', name, status, stateLabel, title: name,
    detail: stateLabel, runId: op?.runId ?? '', readLimited: op?.readLimited ?? false,
    ...(op?.bodyAvailable && has(op, 'arguments') ? {arguments: op.arguments} : {}),
    ...(receiptValue !== undefined && receiptValue !== null ? {result: receiptValue} : {}),
    // No durationMs or startedAt is derived from a registration timestamp or an arbitrary receipt field.
  };
}

/** A whole-journal native warning can refer to operations outside this page.
 * A recent successful event or a superficially completed task cannot clear it. */
export function taskNeedsReconciliation(task: unknown): boolean {
  const {header, raw, runId} = taskParts(task);
  const state = text(header.status) || text(raw.status);
  const operations = getTaskOperations(task);
  if (['reconciling', 'reconciliation_required', 'unknown'].includes(state) || row(raw.evidence)?.requiresReconciliation === true ||
      operations.some(op => presentOperation(op).status === 'unknown' && (op.bodyAvailable || op.state.includes('unknown') || op.requiresReconciliation))) return true;
  const unconfirmed = typeof raw.historyHasUnconfirmedOperations === 'boolean' ? raw.historyHasUnconfirmedOperations : raw.hasUnresolvedOperations === true;
  const active = ['preparing', 'running', 'verifying', 'pausing', 'stopping', 'cancelling'].includes(state);
  const knownInFlight = operations.some(op => !!runId && op.runId === runId && op.state === 'prepared');
  return unconfirmed && !(active && knownInFlight);
}

/** Attention is a reading route, not permission to confirm, resume or replay. */
export function taskNeedsAttention(task: unknown): boolean {
  if (taskNeedsReconciliation(task)) return true;
  const {header, raw} = taskParts(task);
  const state = text(header.status) || text(raw.status);
  if (['completed', 'failed', 'cancelled', 'canceled', 'stopped'].includes(state)) return false;
  return ['needs_confirmation', 'awaiting_user', 'waiting_user', 'waiting_approval', 'awaiting_approval', 'waiting_permission', 'interrupted', 'paused'].includes(state) ||
    rows(raw.approvals).some(approval => ['pending', 'waiting'].includes(text(approval.state) || text(approval.status)));
}

function latest<T>(values: T[], position: (value: T) => number | undefined, time: (value: T) => number | undefined): T | undefined {
  const positioned = values.filter(value => position(value) !== undefined);
  const candidates = positioned.length ? positioned : values.filter(value => time(value) !== undefined);
  return candidates.reduce<T | undefined>((best, value) => !best ||
    (positioned.length ? position(value)! > position(best)! : time(value)! > time(best)!) ? value : best, undefined);
}

function taskFallback(header: Row, raw: Row, runId: string, partial: boolean): TaskProgress {
  const state = text(header.status) || text(raw.status);
  const labels: Record<string, [string, OperationStatus]> = {
    queued: ['等待执行', 'waiting'], preparing: ['准备执行', 'running'], running: ['正在执行', 'running'],
    verifying: ['正在核对结果', 'running'], pausing: ['正在暂停，等待确认', 'waiting'],
    stopping: ['正在停止，等待确认', 'waiting'], cancelling: ['正在停止，等待确认', 'waiting'],
    waiting_approval: ['需要你的授权', 'needs_approval'], awaiting_approval: ['需要你的授权', 'needs_approval'],
    waiting_permission: ['需要你的授权', 'needs_approval'], awaiting_permission: ['需要你的授权', 'needs_approval'],
    needs_confirmation: ['已有结果，等待确认', 'waiting'], awaiting_user: ['需要你的下一步', 'waiting'],
    waiting_user: ['需要你的下一步', 'waiting'], paused: ['已暂停', 'waiting'], interrupted: ['执行已中断', 'waiting'],
    failed: ['这次执行未完成', 'error'], completed: ['任务已完成', 'returned'],
    cancelled: ['任务已停止', 'stopped'], canceled: ['任务已停止', 'stopped'], stopped: ['任务已停止', 'stopped'],
    reconciling: ['有操作结果待核对', 'unknown'], reconciliation_required: ['有操作结果待核对', 'unknown'], unknown: ['执行结果待核对', 'unknown'],
  };
  const current: [string, OperationStatus] = labels[state] ?? ['执行状态待核对', 'unknown'];
  const [title, status] = current;
  return {title, detail: excerpt(text(header.summary) || text(raw.summary), 360), status, stateLabel: title, source: 'task', runId, partial};
}

/** A recent readable record of this run; never an inferred next stage or progress total. */
export function getRecentTaskProgress(task: unknown): TaskProgress {
  const {header, raw, id, runId} = taskParts(task);
  const eventPage = pageFor(raw, 'historyEventPage', id, 'events');
  const operationPage = pageFor(raw, 'historyOperationPage', id, 'operations');
  const partial = raw.taskCoreReadLimited === true || eventPage?.hasMore === true || eventPage?.readLimited === true ||
    operationPage?.hasMore === true || operationPage?.readLimited === true ||
    rows(eventPage?.entries).some(entry => entry.status === 'read_limited') ||
    rows(operationPage?.entries).some(entry => entry.status === 'read_limited');
  if (taskNeedsReconciliation(task)) return {title: '有操作结果待核对', detail: '操作可能已经发生，请在原任务核对结果；不会自动重放。',
    status: 'unknown', stateLabel: '结果待核对', source: 'task', runId, partial};
  if (!runId) return taskFallback(header, raw, runId, partial);
  const pageEvents = rows(eventPage?.entries).flatMap(entry => {
    const event = row(entry.event);
    return event && entry.status === 'available' && text(event.runId) === runId ? [{event, position: number(entry.position)}] : [];
  });
  const rawEvents = rows(raw.events).filter(event => text(event.runId) === runId).map(event => ({event, position: undefined as number | undefined}));
  const recentEvent = latest(pageEvents.length ? pageEvents : rawEvents, value => value.position, value => number(value.event.at) ?? number(value.event.timestamp));
  if (recentEvent) {
    const event = recentEvent.event;
    const type = text(event.type);
    const eventTitles: Record<string, string> = {model: '模型记录', tool: '工具调用', observation: '观察现场', verification: '结果核对', user_verification: '结果核对', run: '执行记录', result: '任务结果', owner_input: '收到补充', capability_changed: '能力变化', context_budget: '上下文整理', inference_receipt: '模型调用记录'};
    const state = text(event.status);
    const status: OperationStatus = state === 'failed' ? 'error' : state === 'unknown' ? 'unknown' : state === 'running' ? 'running' : state === 'completed' ? 'returned' : ['cancelled', 'canceled', 'stopped'].includes(state) ? 'stopped' : ['awaiting_user', 'paused'].includes(state) ? 'waiting' : 'unknown';
    const stateLabel = state === 'completed' ? '已有该记录' : state === 'failed' ? '该记录返回错误' : state === 'unknown' ? '结果未核实' : state === 'running' ? '执行记录' : state === 'awaiting_user' ? '需要你处理' : state === 'paused' ? '已暂停' : status === 'stopped' ? '已停止' : '原记录';
    return {title: eventTitles[type] || '最近记录', detail: excerpt(text(event.message), 360) || stateLabel,
      status, stateLabel, source: 'event', runId, position: recentEvent.position, partial};
  }
  const recentOperation = latest(getTaskOperations(task).filter(op => op.runId === runId && op.bodyAvailable), op => op.position, op => op.createdAt);
  if (recentOperation) {
    const presented = presentOperation(recentOperation);
    return {title: presented.name, detail: presented.stateLabel, status: presented.status, stateLabel: presented.stateLabel,
      source: 'operation', runId, operationId: recentOperation.operationId, position: recentOperation.position, partial};
  }
  return taskFallback(header, raw, runId, partial);
}
