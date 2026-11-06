/** Read-only presentation of the original task execution. Cursors and nanosecond
 * strings are opaque: neither is reconstructed from a browser clock. */
export type TraceKind = 'model' | 'tool' | 'event';
export type TraceRole = 'input' | 'context' | 'assistant' | 'request' | 'tool' | 'subtool' | 'event';
export type TraceFilter = 'all' | 'model' | 'tool' | 'failed';
export type TraceData = Record<string, unknown>;
export type TraceNode = {
  id: string; parentId: string | null; kind: TraceKind; name: string; status: string;
  startedAt: number | null; finishedAt: number | null; elapsedNanos: string | null;
  durationMs: number | null; source: unknown; sequence: number; timingKnown: boolean; legacy: boolean;
  parentVisible?: boolean; runId?: string; epoch?: number; callId?: string; model?: string;
  usage?: TraceData; input?: unknown; output?: unknown; error?: unknown; operationId?: string;
  memberId?: string; role?: TraceRole; turnId?: string; turnIndex?: number; stepId?: string; stepIndex?: number; requestId?: string;
  logicalRequestId?: string; attemptNo?: number; route?: TraceData; hasError?: boolean;
  firstVisibleTextAt?: number | null; firstVisibleTextElapsedNanos?: string | null; timingEvidence?: string | null;
  generationDurationMs?: number | null; generationTimingMeaning?: string | null;
  contextMeaning?: string; optionsSource?: string;
  providerToolCallId?: string; purpose?: string; message?: unknown; sourceStatus?: string;
  contentLimited?: boolean; effectPending?: boolean; completionMeaning?: string; timingMeaning?: string; wallClockAdjusted?: boolean;
  preview?: unknown; raw?: unknown; options?: unknown; schema?: unknown; sourceRefs?: unknown;
  contentUnavailable?: unknown;
};
export type TraceRun = { runId: string; epoch: number; status: string; startedAt: number | null; updatedAt: number | null };
export type ExecutionTracePage = {
  format: 'pocketworld.execution-trace.v1'; taskId: string; runId: string; epoch: number; capturedAt: number;
  runs: TraceRun[]; nodes: TraceNode[]; cursor: string; nextCursor: string; hasMore: boolean; limited: boolean;
  readOnly: true; executionAuthorized: false;
};
export type TraceRow = { node: TraceNode; level: number; hasChildren: boolean; expanded: boolean; context: boolean; missingParent: boolean };
export type ExecutionTraceDetail = {
  format: 'pocketworld.execution-trace-detail.v1'; taskId: string; runId: string; epoch: number; capturedAt: number;
  node: TraceNode; sourceState: 'current' | 'unavailable' | 'metadata_only'; sourceReason?: string;
  readOnly: true; executionAuthorized: false;
};

const object = (value: unknown): TraceData | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as TraceData : null;
const text = (value: unknown) => typeof value === 'string' ? value : '';
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const timestamp = (value: unknown): number | null => integer(value) && value > 0 ? value : null;
const safeId = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 512;
const cursorValid = (value: unknown): value is string => typeof value === 'string' && value.length <= 4096;
const failure = () => new Error('轨迹的任务、执行或分页来源不可核对，请重新读取。');

/** A receipt from another task/run cannot replace the open task's history. */
export function executionTracePage(value: unknown, taskId: string, runId = '', cursor = '', prior: readonly string[] = [], epoch?: number): ExecutionTracePage {
  const page = object(value);
  if (!page || page.format !== 'pocketworld.execution-trace.v1' || page.taskId !== taskId ||
      typeof page.runId !== 'string' || runId && page.runId !== runId || !integer(page.epoch) ||
      epoch !== undefined && page.epoch !== epoch || !timestamp(page.capturedAt) ||
      page.readOnly !== true || page.executionAuthorized !== false || !Array.isArray(page.nodes) || page.nodes.length > 100 ||
      !Array.isArray(page.runs) || page.runs.length > 200 || !cursorValid(page.cursor) || !cursorValid(page.nextCursor) ||
      typeof page.hasMore !== 'boolean' || typeof page.limited !== 'boolean') throw failure();
  if (cursor && page.cursor !== cursor) throw failure();
  if (page.hasMore ? !page.nextCursor || page.nextCursor === cursor || page.nextCursor === page.cursor || prior.includes(page.nextCursor) : page.nextCursor !== '') throw failure();
  const ids = new Set<string>();
  const nodes = page.nodes.map(value => {
    const node = object(value);
    if (!node || !safeId(node.id) || ids.has(node.id) || !['model', 'tool', 'event'].includes(text(node.kind)) ||
        typeof node.name !== 'string' || typeof node.status !== 'string' || !integer(node.sequence) ||
        node.runId !== undefined && node.runId !== page.runId || node.epoch !== undefined && node.epoch !== page.epoch ||
        node.parentId != null && node.parentId !== '' && !safeId(node.parentId)) throw failure();
    ids.add(node.id);
    if (node.elapsedNanos != null && (typeof node.elapsedNanos !== 'string' || !/^\d{1,30}$/.test(node.elapsedNanos))) throw failure();
    if (node.durationMs != null && (typeof node.durationMs !== 'number' || !Number.isFinite(node.durationMs) || node.durationMs < 0 || node.durationMs > Number.MAX_SAFE_INTEGER)) throw failure();
    if (node.role != null && !['input', 'context', 'assistant', 'request', 'tool', 'subtool', 'event'].includes(text(node.role))) throw failure();
    for (const key of ['turnIndex', 'stepIndex']) if (node[key] != null && !integer(node[key])) throw failure();
    for (const key of ['turnId', 'stepId', 'requestId']) if (node[key] != null && node[key] !== '' && !safeId(node[key])) throw failure();
    if (node.firstVisibleTextElapsedNanos != null && (typeof node.firstVisibleTextElapsedNanos !== 'string' || !/^\d{1,30}$/.test(node.firstVisibleTextElapsedNanos))) throw failure();
    if (node.generationDurationMs != null && (typeof node.generationDurationMs !== 'number' || !Number.isFinite(node.generationDurationMs) || node.generationDurationMs < 0 || node.generationDurationMs > Number.MAX_SAFE_INTEGER)) throw failure();
    return { ...node, parentId: text(node.parentId) || null, startedAt: timestamp(node.startedAt), finishedAt: timestamp(node.finishedAt),
      elapsedNanos: node.elapsedNanos ?? null, durationMs: node.durationMs ?? null,
      timingKnown: node.timingKnown === true, legacy: node.legacy === true } as TraceNode;
  });
  const byId = new Map(nodes.map(node => [node.id, node]));
  for (const node of nodes) {
    const seen = new Set([node.id]);
    let parent = node.parentId;
    while (parent && byId.has(parent)) {
      if (seen.has(parent)) throw failure();
      seen.add(parent); parent = byId.get(parent)!.parentId;
    }
  }
  const runIds = new Set<string>();
  const runs = page.runs.map(value => {
    const run = object(value);
    if (!run || !safeId(run.runId) || !integer(run.epoch) || typeof run.status !== 'string' || runIds.has(run.runId)) throw failure();
    runIds.add(run.runId);
    return { ...run, startedAt: timestamp(run.startedAt), updatedAt: timestamp(run.updatedAt) } as TraceRun;
  });
  return { ...page, nodes, runs } as ExecutionTracePage;
}

/** Detail identity is checked independently of the page request. Body availability
 * never changes the read-only nature or the identity of the preserved metadata. */
export function executionTraceDetail(value: unknown, taskId: string, runId: string, epoch: number, nodeId: string): ExecutionTraceDetail {
  const detail = object(value);
  if (!detail || detail.format !== 'pocketworld.execution-trace-detail.v1' || detail.taskId !== taskId || detail.runId !== runId ||
      detail.epoch !== epoch || !timestamp(detail.capturedAt) || detail.readOnly !== true || detail.executionAuthorized !== false ||
      !['current', 'unavailable', 'metadata_only'].includes(text(detail.sourceState)) || object(detail.node)?.id !== nodeId ||
      detail.sourceReason != null && typeof detail.sourceReason !== 'string') throw failure();
  const parsed = executionTracePage({ ...detail, format: 'pocketworld.execution-trace.v1', nodes: [detail.node], runs: [],
    cursor: '', nextCursor: '', hasMore: false, limited: false }, taskId, runId, '', [], epoch);
  return { ...detail, node: parsed.nodes[0] } as ExecutionTraceDetail;
}

/** Pages may overlap while the active run grows. IDs, never arrival order, own
 * metadata; also check parent cycles that only become visible across pages. */
export function mergeTraceNodes(previous: readonly TraceNode[], incoming: readonly TraceNode[]): TraceNode[] {
  const byId = new Map(previous.map(node => [node.id, node]));
  for (const node of incoming) byId.set(node.id, node);
  const checked = new Set<string>();
  for (const node of byId.values()) {
    const path = new Set<string>(); let current: TraceNode | undefined = node;
    while (current && !checked.has(current.id)) {
      if (path.has(current.id)) throw failure();
      path.add(current.id); current = current.parentId ? byId.get(current.parentId) : undefined;
    }
    for (const id of path) checked.add(id);
  }
  return [...byId.values()].sort((a, b) => a.sequence - b.sequence || a.id.localeCompare(b.id));
}

const terminal = new Set(['COMPLETED', 'COMPLETE', 'SUCCESS', 'SUCCEEDED', 'DONE', 'FAILED', 'ERROR', 'TIMED_OUT', 'TIMEOUT', 'CANCELLED', 'CANCELED', 'STOPPED', 'REJECTED', 'INTERRUPTED', 'ABANDONED']);
export function traceFailed(node: Pick<TraceNode, 'status' | 'error' | 'hasError'>) {
  const state = node.status.toUpperCase();
  const recordedError = typeof node.error === 'string' ? node.error.trim().length > 0
    : node.error !== null && typeof node.error === 'object' && Object.keys(node.error).length > 0;
  return ['FAILED', 'ERROR', 'TIMED_OUT', 'TIMEOUT', 'REJECTED'].includes(state)
    || !['CANCELLED', 'CANCELED', 'STOPPED'].includes(state) && (node.hasError === true || recordedError);
}
export function traceInProgress(status: string) {
  const state = status.toUpperCase();
  return !terminal.has(state) && (['STARTED', 'RUNNING', 'WORKING', 'QUEUED', 'ACCEPTED', 'RECOVERING', 'STOPPING'].includes(state) || state.startsWith('WAITING'));
}
export function traceStatus(status: string): string {
  return ({ STARTED: '进行中', RUNNING: '进行中', WORKING: '进行中', COMPLETED: '已完成', COMPLETE: '已完成',
    SUCCESS: '已完成', SUCCEEDED: '已完成', DONE: '已完成', FAILED: '失败', ERROR: '失败', TIMED_OUT: '超时', TIMEOUT: '超时',
    CANCELLED: '已取消', CANCELED: '已取消', STOPPED: '已停止', REJECTED: '被拒绝', INTERRUPTED: '已中断',
    UNKNOWN: '待核对', OBSERVED: '已记录', QUEUED: '排队中', ACCEPTED: '已受理', STOPPING: '正在停止', RECOVERING: '恢复中',
    WAITING: '等待中', WAITING_OWNER: '等待回答', WAITING_APPROVAL: '等待授权', AWAITING_USER: '等待回答', NEEDS_CONFIRMATION: '等待确认' } as Record<string, string>)[status.toUpperCase()] || status || '状态未记录';
}
export const traceKind = (kind: TraceKind) => ({ model: '模型', tool: '工具', event: '事件' })[kind];

/** Only native observed elapsed time creates a duration bar. A wall clock
 * subtraction, snapshot time or STARTED receipt never becomes tool latency. */
export function traceDuration(node: TraceNode): number | null {
  // A finished tool handler can return UNKNOWN or request confirmation. Its
  // observed handler duration remains real while the action outcome is pending.
  if (!node.timingKnown || node.finishedAt === null) return null;
  if (node.durationMs !== null) return node.durationMs;
  if (node.elapsedNanos === null) return null;
  const nanos = BigInt(node.elapsedNanos), millis = nanos / 1_000_000n;
  return millis <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(millis) + Number(nanos % 1_000_000n) / 1_000_000 : null;
}
export function durationLabel(millis: number | null): string {
  if (millis === null) return '耗时未记录';
  if (millis === 0) return '0 ms';
  if (millis < 1) return '<1 ms';
  if (millis < 1000) return `${Math.round(millis)} ms`;
  if (millis < 60_000) return `${(millis / 1000).toFixed(millis < 10_000 ? 2 : 1).replace(/\.?0+$/, '')} 秒`;
  const minutes = Math.floor(millis / 60_000), seconds = Math.floor(millis % 60_000 / 1000);
  return `${minutes} 分${seconds ? ` ${seconds} 秒` : ''}`;
}
export function nodeDurationLabel(node: TraceNode): string {
  return traceDuration(node) !== null ? durationLabel(traceDuration(node)) : traceInProgress(node.status) ? '进行中' : '耗时未记录';
}

const order = (a: TraceNode, b: TraceNode) => (a.startedAt ?? Number.MAX_SAFE_INTEGER) - (b.startedAt ?? Number.MAX_SAFE_INTEGER) || a.sequence - b.sequence || a.id.localeCompare(b.id);
function searchText(node: TraceNode) {
  return [node.name, node.model, node.callId, node.operationId, node.memberId, node.turnId, node.logicalRequestId,
    node.stepId, node.requestId, traceRoleLabel(node), node.turnIndex == null ? '' : `Turn ${node.turnIndex}`,
    traceStatus(node.status), traceKind(node.kind), traceBody(node.error).text, traceBody(node.message).text].filter(Boolean).join(' ').toLocaleLowerCase();
}

export function traceRole(node: TraceNode): TraceRole { return node.role || (node.kind === 'model' ? 'request' : node.kind === 'tool' ? 'tool' : 'event'); }
export function traceRoleLabel(node: TraceNode): string {
  return ({ input: 'USER', context: 'CONTEXT', assistant: 'ASSISTANT', request: 'REQUEST', tool: 'TOOL', subtool: 'SUBTOOL', event: 'EVENT' })[traceRole(node)];
}
export type TraceEventRow = { node: TraceNode; request?: TraceNode };
export type TraceTurnGroup = { id: string; turnId?: string; turnIndex?: number; rows: TraceEventRow[] };
export function traceEvents(nodes: readonly TraceNode[]): TraceEventRow[] {
  const ordered = mergeTraceNodes([], nodes), requests = new Map(ordered.filter(node => node.kind === 'model').map(node => [node.id, node]));
  const attached = new Set(ordered.filter(node => traceRole(node) === 'assistant' && node.requestId).map(node => node.requestId!));
  return ordered.filter(node => node.kind !== 'model' || !attached.has(node.id)).map(node => ({ node,
    request: traceRole(node) === 'assistant' && node.requestId ? requests.get(node.requestId) : undefined }));
}
export function traceEventMatches(row: TraceEventRow, filter: TraceFilter, query: string): boolean {
  const role = traceRole(row.node), needle = query.trim().toLocaleLowerCase();
  const matches = filter === 'all' || (filter === 'failed' ? traceFailed(row.node) || !!row.request && traceFailed(row.request)
    : filter === 'model' ? role === 'assistant' || role === 'request' : row.node.kind === 'tool');
  return matches && (!needle || searchText(row.node).includes(needle) || !!row.request && searchText(row.request).includes(needle));
}
/** Native turn/step metadata is authoritative. An epoch, wall clock gap or user
 * message must never create a guessed model/tool turn. */
export function traceTurns(rows: readonly TraceEventRow[]): TraceTurnGroup[] {
  const groups: TraceTurnGroup[] = [], known = new Map<string, TraceTurnGroup>();
  const unassigned: TraceEventRow[] = [];
  for (const row of rows) {
    const { turnId, turnIndex } = row.node;
    if (!turnId && turnIndex == null) { unassigned.push(row); continue; }
    const key = turnId || `index:${turnIndex}`;
    const previous = known.get(key);
    if (previous) previous.rows.push(row);
    else { const group = { id: key, turnId, turnIndex, rows: [row] }; known.set(key, group); groups.push(group); }
  }
  if (unassigned.length) groups.push({ id: 'unassigned', rows: unassigned });
  return groups;
}
export function traceTurnCount(nodes: readonly TraceNode[]): number {
  return new Set(nodes.filter(node => node.turnId || node.turnIndex != null).map(node => node.turnId || `index:${node.turnIndex}`)).size;
}
export type TraceOverviewEntry = TraceEventRow & { lane: 0 | 1 | 2; start: number; end: number; duration: number | null };
/** Adapted from Whaleness TrajectoryOverviewLayout (MIT, Chaofan Li). The
 * minimum weight provides a hit/visibility affordance, never invented time. */
export function traceOverviewLayout(rows: readonly TraceEventRow[], mode: 'order' | 'duration') {
  // General audit events have no semantic lane. Keep them in the event list,
  // instead of falsely presenting verification/budget records as user input.
  const visible = rows.filter(row => traceRole(row.node) !== 'event');
  const durations = visible.map(row => traceDuration(row.request || row.node));
  const duration = durations.reduce<number>((sum, value) => sum + (value ?? 0), 0);
  const minimum = Math.max(1, duration / 500);
  const weights = durations.map(value => mode === 'duration' ? Math.max(value ?? 0, minimum) : 1);
  const total = weights.reduce((sum, value) => sum + value, 0) || 1;
  let cursor = 0;
  const entries: TraceOverviewEntry[] = visible.map((row, index) => {
    const role = traceRole(row.node), start = cursor / total; cursor += weights[index];
    return { ...row, lane: role === 'assistant' || role === 'request' ? 1 : role === 'tool' || role === 'subtool' ? 2 : 0,
      start, end: cursor / total, duration: durations[index] };
  });
  return { entries, duration, unknown: durations.filter(value => value === null).length };
}
/** Search visits every loaded node, including folded descendants. Ancestors are
 * retained as context while filtering; the user's fold set stays unchanged. */
export function traceRows(nodes: readonly TraceNode[], folded: ReadonlySet<string>, filter: TraceFilter = 'all', query = ''): TraceRow[] {
  const byId = new Map(nodes.map(node => [node.id, node]));
  const children = new Map<string | null, TraceNode[]>();
  for (const node of nodes) {
    const parent = node.parentId && byId.has(node.parentId) ? node.parentId : null;
    const siblings = children.get(parent) || []; siblings.push(node); children.set(parent, siblings);
  }
  for (const siblings of children.values()) siblings.sort(order);
  const needle = query.trim().toLocaleLowerCase();
  const filtering = filter !== 'all' || needle !== '';
  const matches = new Set(nodes.filter(node => (filter === 'all' || (filter === 'failed' ? traceFailed(node) : node.kind === filter)) && (!needle || searchText(node).includes(needle))).map(node => node.id));
  const included = new Set(matches);
  if (filtering) for (const id of matches) {
    let parent = byId.get(id)?.parentId;
    const seen = new Set<string>();
    while (parent && byId.has(parent) && !seen.has(parent)) { included.add(parent); seen.add(parent); parent = byId.get(parent)?.parentId; }
  }
  const rows: TraceRow[] = [], seen = new Set<string>();
  function visit(node: TraceNode, level: number) {
    if (seen.has(node.id) || filtering && !included.has(node.id)) return;
    seen.add(node.id);
    const descendants = (children.get(node.id) || []).filter(child => !filtering || included.has(child.id));
    const expanded = descendants.length > 0 && (filtering || !folded.has(node.id));
    rows.push({ node, level, hasChildren: descendants.length > 0, expanded, context: filtering && !matches.has(node.id), missingParent: !!node.parentId && !byId.has(node.parentId) });
    if (expanded) for (const child of descendants) visit(child, level + 1);
  }
  for (const node of children.get(null) || []) visit(node, 0);
  return rows;
}

export type TraceBounds = { start: number; duration: number } | null;
export function traceBounds(nodes: readonly TraceNode[]): TraceBounds {
  const timed = nodes.filter(node => node.startedAt !== null);
  if (!timed.length) return null;
  const start = Math.min(...timed.map(node => node.startedAt!));
  const end = Math.max(...timed.map(node => node.startedAt! + (traceDuration(node) ?? 0)));
  return { start, duration: Math.max(0, end - start) };
}
export function traceBar(node: TraceNode, bounds: TraceBounds): { left: number; width: number | null } | null {
  if (!bounds || node.startedAt === null) return null;
  const duration = traceDuration(node), scale = bounds.duration || 1;
  return { left: Math.min(100, Math.max(0, (node.startedAt - bounds.start) / scale * 100)),
    width: duration === null ? null : Math.min(100, duration / scale * 100) };
}

export type TraceCount = { value: bigint; final: boolean; sourcePath: string };
export function traceCount(usage: TraceData | undefined, key: string): TraceCount | null {
  const value = object(object(usage?.counters)?.[key]);
  if (!value || value.source !== 'provider_reported' || !integer(value.value)) return null;
  return { value: BigInt(value.value), final: value.stage === 'FINAL', sourcePath: text(value.sourcePath) };
}
/** These totals are normalized for display. Retain original provider counters
 * in details; caches/reasoning already included in input/output are not added. */
export function normalizedTraceTokens(usage?: TraceData): { input: TraceCount | null; output: TraceCount | null } {
  const add = (base: TraceCount | null, part: TraceCount | null) => base && part ? { ...base, value: base.value + part.value, final: base.final && part.final } : null;
  let input = traceCount(usage, 'INPUT'), output = traceCount(usage, 'OUTPUT');
  for (const [flag, key] of [['inputIncludesCacheRead', 'CACHE_READ'], ['inputIncludesCacheWrite', 'CACHE_WRITE']] as const) {
    if (usage?.[flag] === false) input = add(input, traceCount(usage, key));
    else if (usage?.[flag] !== true) input = null;
  }
  if (usage?.outputIncludesReasoning === false) output = add(output, traceCount(usage, 'REASONING'));
  else if (usage?.outputIncludesReasoning !== true) output = null;
  return { input, output };
}
export function traceSummary(nodes: readonly TraceNode[]) {
  const unique = [...new Map(nodes.map(node => [node.id, node])).values()];
  const models = unique.filter(node => node.kind === 'model');
  const sum = (side: 'input' | 'output') => {
    let value = 0n, known = 0, final = true;
    for (const node of models) { const count = normalizedTraceTokens(node.usage)[side]; if (count) { value += count.value; known++; final &&= count.final; } }
    return { value: known ? value : null, known, missing: models.length - known, final: known > 0 && final };
  };
  return { models: models.length, tools: unique.filter(node => node.kind === 'tool').length, failed: unique.filter(traceFailed).length, input: sum('input'), output: sum('output') };
}
export type TraceAggregate = { value: bigint | null; known: number; missing: number; final: boolean };
export function traceUsageBreakdown(nodes: readonly TraceNode[]) {
  const models = [...new Map(nodes.filter(node => node.kind === 'model').map(node => [node.id, node])).values()];
  const aggregate = (get: (node: TraceNode) => TraceCount | null): TraceAggregate => {
    let value = 0n, known = 0, final = true;
    for (const node of models) { const count = get(node); if (count) { value += count.value; known++; final &&= count.final; } }
    return { value: known ? value : null, known, missing: models.length - known, final: known > 0 && final };
  };
  const remainder = (total: TraceCount | null, part: TraceCount | null): TraceCount | null => total && part && total.value >= part.value
    ? { ...total, value: total.value - part.value, final: total.final && part.final } : null;
  return {
    input: aggregate(node => normalizedTraceTokens(node.usage).input), output: aggregate(node => normalizedTraceTokens(node.usage).output),
    cacheRead: aggregate(node => traceCount(node.usage, 'CACHE_READ')), cacheWrite: aggregate(node => traceCount(node.usage, 'CACHE_WRITE')),
    otherInput: aggregate(node => remainder(normalizedTraceTokens(node.usage).input, traceCount(node.usage, 'CACHE_READ'))),
    reasoning: aggregate(node => traceCount(node.usage, 'REASONING')),
    content: aggregate(node => remainder(normalizedTraceTokens(node.usage).output, traceCount(node.usage, 'REASONING'))),
    providerTotal: aggregate(node => traceCount(node.usage, 'TOTAL')), models: models.length,
  };
}
export function traceRequestTiming(node: TraceNode): { firstText: number | null; generation: number | null; throughput: number | null } {
  let firstText: number | null = null;
  if (node.timingEvidence === 'provider_text_delta' && typeof node.firstVisibleTextElapsedNanos === 'string' && /^\d{1,30}$/.test(node.firstVisibleTextElapsedNanos)) {
    const raw = BigInt(node.firstVisibleTextElapsedNanos), millis = raw / 1_000_000n;
    if (millis <= BigInt(Number.MAX_SAFE_INTEGER)) firstText = Number(millis) + Number(raw % 1_000_000n) / 1_000_000;
  }
  const generation = node.generationTimingMeaning === 'client_first_to_last_visible_text_delta' && node.generationDurationMs != null
    && node.finishedAt !== null && Number.isFinite(node.generationDurationMs) && node.generationDurationMs >= 0 ? node.generationDurationMs : null;
  const output = traceCount(node.usage, 'OUTPUT');
  const throughput = generation !== null && generation > 0 && output?.final ? Number(output.value) / (generation / 1000) : null;
  return { firstText, generation, throughput: throughput !== null && Number.isFinite(throughput) ? throughput : null };
}
export function tokenLabel(value: bigint | null): string { return value === null ? '未记录' : value.toLocaleString('zh-CN'); }
export function traceBody(value: unknown): { text: string; truncated: boolean } {
  if (value === undefined || value === null || value === '') return { text: '', truncated: false };
  const raw = typeof value === 'string' ? value : JSON.stringify(value, null, 2) || '';
  return { text: raw.slice(0,65_536), truncated: raw.length > 65_536 };
}
export function traceTime(value: number | null, detailed = false): string {
  return value === null ? '时间未记录' : new Date(value).toLocaleString('zh-CN', detailed
    ? { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 }
    : { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
