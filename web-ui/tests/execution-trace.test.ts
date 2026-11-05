import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { executionTracePage, traceDuration, durationLabel, nodeDurationLabel, traceFailed, traceRows, traceBounds, traceBar,
  normalizedTraceTokens, traceCount, traceSummary, traceBody, traceStatus, executionTraceDetail, mergeTraceNodes,
  traceEvents, traceTurns, traceTurnCount, traceOverviewLayout, traceUsageBreakdown, traceRequestTiming, traceEventMatches, type TraceNode } from '../src/execution-trace.ts';

const node = (patch: Partial<TraceNode> = {}): TraceNode => ({ id: 'model:one', parentId: null, kind: 'model', name: '模型调用', status: 'COMPLETED',
  startedAt: 1_000, finishedAt: 3_000, elapsedNanos: '2000000000', durationMs: 2_000, source: 'native_inference_receipt', sequence: 1,
  timingKnown: true, legacy: false, ...patch });
const page = (patch: Record<string, unknown> = {}) => ({ format: 'pocketworld.execution-trace.v1', taskId: 'task', runId: 'run', epoch: 3, capturedAt: 10_000,
  nodes: [node()], runs: [{ runId: 'run', epoch: 3, status: 'RUNNING', startedAt: 1_000, updatedAt: 10_000 }],
  cursor: 'opaque:first', nextCursor: 'opaque:older', hasMore: true, limited: false, readOnly: true, executionAuthorized: false, ...patch });
const count = (value: number | null, stage = 'FINAL') => ({ value, source: value === null ? 'unknown' : 'provider_reported', stage, sourcePath: 'usage.fixture' });
const usage = (counters: Record<string, ReturnType<typeof count>>, flags = {}) => ({ counters, inputIncludesCacheRead: true, inputIncludesCacheWrite: true, outputIncludesReasoning: true, ...flags });

test('trace receipt is bound to the task, run, epoch and read-only native contract', () => {
  assert.equal(executionTracePage(page(), 'task', 'run', '', [], 3).nodes[0].id, 'model:one');
  for (const patch of [{ taskId: 'other' }, { runId: 'other' }, { epoch: 4 }, { readOnly: false }, { executionAuthorized: true }, { format: 'other' }])
    assert.throws(() => executionTracePage(page(patch), 'task', 'run', '', [], 3));
  assert.throws(() => executionTracePage(page({ nodes: [node({ runId: 'other' })] }), 'task'));
  assert.throws(() => executionTracePage(page({ nodes: [node({ epoch: 8 })] }), 'task'));
  assert.throws(() => executionTracePage(page({ nodes: Array.from({ length: 101 }, (_, i) => node({ id: `model:${i}` })) }), 'task'));
});

test('opaque cursors must advance; duplicate ids and cycles cannot join unrelated nodes', () => {
  for (const patch of [{ nextCursor: '' }, { nextCursor: 'opaque:first' }, { hasMore: false, nextCursor: 'opaque:older' }])
    assert.throws(() => executionTracePage(page(patch), 'task'));
  assert.throws(() => executionTracePage(page(), 'task', 'run', 'opaque:older'));
  assert.throws(() => executionTracePage(page(), 'task', 'run', '', ['opaque:older']));
  assert.throws(() => executionTracePage(page({ cursor: 'different-window' }), 'task', 'run', 'opaque:first'));
  assert.throws(() => executionTracePage(page({ nodes: [node(), node()] }), 'task'));
  assert.throws(() => executionTracePage(page({ nodes: [node({ parentId: 'tool:two' }), node({ id: 'tool:two', parentId: 'model:one' })] }), 'task'));
  assert.equal(executionTracePage(page({ nodes: [node({ parentId: 'model:outside', parentVisible: false })] }), 'task').nodes[0].parentId, 'model:outside');
});

test('actual elapsed time survives UNKNOWN and confirmation outcomes and wall-clock adjustment', () => {
  for (const status of ['COMPLETED', 'FAILED', 'UNKNOWN', 'AWAITING_USER', 'NEEDS_CONFIRMATION']) {
    const observed = node({ kind: 'tool', status, finishedAt: 500, elapsedNanos: '1234567890', durationMs: null });
    assert.equal(traceDuration(observed), 1234.56789, status);
    assert.equal(nodeDurationLabel(observed), '1.23 秒', status);
  }
  assert.equal(traceDuration(node({ elapsedNanos: '0', durationMs: 0 })), 0);
  assert.equal(nodeDurationLabel(node({ elapsedNanos: '0', durationMs: 0 })), '0 ms');
});

test('unknown, legacy and unfinished durations never become zero or wall-clock elapsed', () => {
  const old = node({ legacy: true, timingKnown: false, elapsedNanos: null, durationMs: null });
  const running = node({ status: 'STARTED', finishedAt: null, timingKnown: false, elapsedNanos: null, durationMs: null });
  assert.equal(traceDuration(old), null); assert.equal(nodeDurationLabel(old), '耗时未记录');
  assert.equal(traceDuration(running), null); assert.equal(nodeDurationLabel(running), '进行中');
  assert.equal(traceDuration(node({ finishedAt: null })), null);
  assert.equal(durationLabel(null), '耗时未记录'); assert.equal(durationLabel(.2), '<1 ms');
  assert.equal(traceStatus('observed'), '已记录');
});

test('nanoseconds remain a decimal string through parsing, including above JS integer precision', () => {
  const elapsedNanos = '9007199254740993';
  const parsed = executionTracePage(page({ nodes: [node({ elapsedNanos, durationMs: null })] }), 'task');
  assert.equal(parsed.nodes[0].elapsedNanos, elapsedNanos);
  assert.equal(traceDuration(parsed.nodes[0]), 9007199254.740993);
  assert.throws(() => executionTracePage(page({ nodes: [node({ elapsedNanos: 9007199254740993 as unknown as string })] }), 'task'));
  assert.throws(() => executionTracePage(page({ nodes: [node({ elapsedNanos: '-1' })] }), 'task'));
});

test('search inspects folded descendants and keeps matched ancestor context without changing folds', () => {
  const parent = node(), child = node({ id: 'tool:child', kind: 'tool', parentId: parent.id, name: 'search_web', sequence: 2, error: 'original connection reset', status: 'UNKNOWN' });
  const other = node({ id: 'tool:other', kind: 'tool', parentId: parent.id, name: 'read_file', sequence: 3 });
  const folded = new Set([parent.id]);
  assert.deepEqual(traceRows([parent, child, other], folded).map(row => row.node.id), [parent.id]);
  const found = traceRows([parent, child, other], folded, 'all', 'connection reset');
  assert.deepEqual(found.map(row => [row.node.id, row.level, row.context]), [[parent.id, 0, true], [child.id, 1, false]]);
  assert.equal(found[0].expanded, true); assert.deepEqual([...folded], [parent.id]);
  assert.deepEqual(traceRows([parent, child, other], folded, 'failed').map(row => row.node.id), [parent.id, child.id]);
  assert.deepEqual(traceRows([parent, child, other], new Set(), 'model').map(row => row.node.id), [parent.id]);
});

test('errors on unknown outcomes are discoverable, while cancellation is not inferred to be failure', () => {
  assert.equal(traceFailed(node({ status: 'UNKNOWN', error: 'handler failed after entering' })), true);
  assert.equal(traceFailed(node({ status: 'UNKNOWN', error: { message: 'socket timeout' } })), true);
  assert.equal(traceFailed(node({ status: 'UNKNOWN', error: {} })), false);
  assert.equal(traceFailed(node({ status: 'UNKNOWN', error: ' ' })), false);
  assert.equal(traceFailed(node({ status: 'CANCELLED', error: 'cancelled by owner' })), false);
});

test('each parallel call has its own measured interval, while running and legacy calls only mark their starts', () => {
  const first = node(), parallel = node({ id: 'tool:parallel', startedAt: 2_000, finishedAt: 2_500, durationMs: 500 });
  const running = node({ id: 'model:running', startedAt: 4_000, finishedAt: null, timingKnown: false, durationMs: null, elapsedNanos: null, status: 'STARTED' });
  const bounds = traceBounds([first, parallel, running]);
  assert.deepEqual(bounds, { start: 1_000, duration: 3_000 });
  const bar = traceBar(parallel, bounds)!;
  assert.ok(Math.abs(bar.left - 100 / 3) < 1e-12); assert.ok(Math.abs(bar.width! - 100 / 6) < 1e-12);
  assert.deepEqual(traceBar(running, bounds), { left: 100, width: null });
  assert.equal(traceBar(node({ startedAt: null }), bounds), null);
  assert.equal(traceRows([node({ parentId: 'outside' })], new Set())[0].missingParent, true);
  assert.deepEqual(traceBounds([running]), { start: 4_000, duration: 0 });
  assert.deepEqual(traceBar(running, traceBounds([running])), { left: 0, width: null });
});

test('OpenAI-style cache and reasoning detail are subsets and never added twice', () => {
  const observed = usage({ INPUT: count(1000), OUTPUT: count(120), TOTAL: count(1120), CACHE_READ: count(700), REASONING: count(80) });
  const totals = normalizedTraceTokens(observed);
  assert.equal(totals.input?.value, 1000n); assert.equal(totals.output?.value, 120n);
  const summary = traceSummary([node({ usage: observed }), node({ id: 'tool:a', kind: 'tool', usage: observed })]);
  assert.equal(summary.input.value, 1000n); assert.equal(summary.output.value, 120n);
});

test('Anthropic exclusive caches and Gemini exclusive reasoning are normalized exactly once', () => {
  const exclusive = usage({ INPUT: count(50), OUTPUT: count(8), CACHE_READ: count(600), CACHE_WRITE: count(100), CACHE_WRITE_5M: count(70), CACHE_WRITE_1H: count(30) }, { inputIncludesCacheRead: false, inputIncludesCacheWrite: false });
  assert.equal(normalizedTraceTokens(exclusive).input?.value, 750n);
  assert.equal(normalizedTraceTokens(usage({ INPUT: count(20), OUTPUT: count(30), REASONING: count(40) }, { outputIncludesReasoning: false })).output?.value, 70n);
  assert.equal(normalizedTraceTokens(usage({ INPUT: count(50), CACHE_READ: count(600) }, { inputIncludesCacheRead: false, inputIncludesCacheWrite: false })).input, null);
});

test('missing and partial usage remains visibly incomplete and unsafe integer counters are not rounded', () => {
  const known = node({ usage: usage({ INPUT: count(25, 'PARTIAL'), OUTPUT: count(0) }) });
  const summary = traceSummary([known, node({ id: 'model:missing' })]);
  assert.equal(summary.input.value, 25n); assert.equal(summary.input.final, false); assert.equal(summary.input.missing, 1);
  assert.equal(summary.output.value, 0n); assert.equal(summary.output.missing, 1);
  assert.equal(normalizedTraceTokens({ counters: { INPUT: count(25) } }).input, null);
  assert.equal(traceCount(usage({ INPUT: count(Number.MAX_SAFE_INTEGER + 1) }), 'INPUT'), null);
  assert.equal(traceCount(usage({ INPUT: { ...count(1), source: 'estimate' } }), 'INPUT'), null);
});

test('large returned payloads are explicitly bounded without interpreting their markup', () => {
  const raw = '<script>fixture</script>' + 'x'.repeat(70_000);
  const body = traceBody(raw); assert.equal(body.text.length, 65_536); assert.equal(body.truncated, true);
  assert.ok(body.text.startsWith('<script>fixture</script>'));
  assert.deepEqual(traceBody(null), { text: '', truncated: false });
});

test('the controlled waterfall retains exact approved independent-reproduction sources', () => {
  const manifestUrl = new URL('../src/library/TRACE-PROVENANCE.json', import.meta.url);
  const manifest = JSON.parse(fs.readFileSync(manifestUrl, 'utf8'));
  for (const source of manifest.upstreamFiles) {
    const actual = createHash('sha256').update(fs.readFileSync(new URL(source.path, manifestUrl))).digest('hex');
    assert.equal(actual, source.sha256, source.path);
  }
  assert.match(manifest.sourceIdentity, /not official Affect UI Pro source/);
});

test('lazy detail validates task/run/epoch/node and body availability independently', () => {
  const detail = { format: 'pocketworld.execution-trace-detail.v1', taskId: 'task', runId: 'run', epoch: 3, capturedAt: 1000,
    node: node({ runId: 'run', epoch: 3, input: { messages: ['retained context'] } }), sourceState: 'current', readOnly: true, executionAuthorized: false };
  assert.deepEqual(executionTraceDetail(detail, 'task', 'run', 3, 'model:one').node.input, { messages: ['retained context'] });
  for (const patch of [{ taskId: 'other' }, { runId: 'other' }, { epoch: 4 }, { node: node({ id: 'other' }) }, { sourceState: 'guess' }, { executionAuthorized: true }])
    assert.throws(() => executionTraceDetail({ ...detail, ...patch }, 'task', 'run', 3, 'model:one'));
  const unavailable = executionTraceDetail({ ...detail, sourceState: 'unavailable', sourceReason: 'original source removed' }, 'task', 'run', 3, 'model:one');
  assert.equal(unavailable.sourceReason, 'original source removed'); assert.equal(unavailable.node.id, 'model:one');
});

test('same-run pages merge by ID, sort by sequence and detect cross-page parent cycles', () => {
  const first = node({ sequence: 9, startedAt: 1000 }), second = node({ id: 'tool:second', kind: 'tool', sequence: 2, startedAt: 5000 });
  const merged = mergeTraceNodes([first], [second, node({ name: 'updated', sequence: 9 })]);
  assert.deepEqual(merged.map(item => item.id), ['tool:second', 'model:one']); assert.equal(merged[1].name, 'updated');
  assert.throws(() => mergeTraceNodes([node({ parentId: 'tool:second' })], [node({ id: 'tool:second', parentId: 'model:one' })]));
});

test('25 real turns and >100 nodes pair assistants with physical requests without counting usage twice', () => {
  const nodes: TraceNode[] = [];
  for (let turn = 1; turn <= 25; turn++) {
    const common = { turnId: `turn-${turn}`, turnIndex: turn, stepId: `step-${turn}`, stepIndex: 1 };
    const request = node({ ...common, id: `model:${turn}`, role: 'request', sequence: turn * 10 + 1,
      usage: usage({ INPUT: count(1000), OUTPUT: count(120), CACHE_READ: count(700), REASONING: count(80) }) });
    nodes.push(node({ ...common, id: `event:context-${turn}`, kind: 'event', role: 'context', sequence: turn * 10 }), request,
      node({ ...common, id: `event:assistant-${turn}`, kind: 'event', role: 'assistant', sequence: turn * 10 + 3, requestId: request.id, usage: request.usage }),
      node({ ...common, id: `tool:${turn}`, kind: 'tool', role: 'tool', sequence: turn * 10 + 4 }),
      node({ ...common, id: `tool:sub-${turn}`, kind: 'tool', role: 'subtool', sequence: turn * 10 + 5 }));
    if (turn === 5) nodes.push(node({ id: 'event:budget', kind: 'event', role: 'event', sequence: turn * 10 + 2 }));
  }
  let merged: TraceNode[] = [];
  const reversed = nodes.toReversed();
  for (let i = 0; i < reversed.length; i += 40) merged = mergeTraceNodes(merged, reversed.slice(i, i + 40));
  assert.equal(merged.length, 126); assert.equal(traceTurnCount(merged), 25);
  const events = traceEvents(merged), groups = traceTurns(events);
  assert.equal(events.length, 101); assert.equal(groups.filter(group => group.turnId).length, 25);
  assert.equal(groups.at(-1)?.id, 'unassigned'); assert.equal(groups.find(group => group.turnIndex === 5)?.rows.length, 4);
  assert.equal(events.find(row => row.node.id === 'event:assistant-17')?.request?.id, 'model:17');
  assert.equal(traceSummary(merged).input.value, 25000n); assert.equal(traceSummary(merged).output.value, 3000n);
  assert.equal(traceUsageBreakdown(merged).otherInput.value, 7500n); assert.equal(traceUsageBreakdown(merged).content.value, 1000n);
  assert.equal(traceOverviewLayout(events, 'order').entries.length, 100, 'Generic audit event has no invented input lane');
});

test('request pending without an assistant stays visible and unknown metadata never invents turns', () => {
  const request = node({ role: 'request', status: 'STARTED', finishedAt: null });
  assert.equal(traceEvents([request]).length, 1); assert.equal(traceTurnCount([request]), 0);
  assert.equal(traceTurns(traceEvents([request]))[0].turnIndex, undefined);
  const assistant = node({ id: 'event:reply', kind: 'event', role: 'assistant', requestId: request.id, sequence: 2 });
  assert.deepEqual(traceEvents([request, assistant]).map(row => row.node.id), ['event:reply']);
});

test('three-lane order and measured-duration modes preserve real durations and missing timing', () => {
  const nodes = [node({ id: 'input', kind: 'event', role: 'input', durationMs: null, elapsedNanos: null, timingKnown: false, sequence: 1 }),
    node({ id: 'request', role: 'request', sequence: 2, durationMs: 2000 }), node({ id: 'tool', kind: 'tool', role: 'subtool', durationMs: 500, sequence: 3 })];
  const order = traceOverviewLayout(traceEvents(nodes), 'order'), duration = traceOverviewLayout(traceEvents(nodes), 'duration');
  assert.deepEqual(order.entries.map(entry => entry.lane), [0, 1, 2]);
  assert.equal(order.entries[0].end, 1 / 3); assert.equal(duration.duration, 2500); assert.equal(duration.unknown, 1);
  assert.equal(duration.entries[0].duration, null); assert.ok(duration.entries[1].end - duration.entries[1].start > duration.entries[2].end - duration.entries[2].start);
});

test('compact hasError preserves UNKNOWN failures and cancellation remains separate', () => {
  const compact = node({ kind: 'tool', status: 'UNKNOWN', hasError: true, error: undefined });
  assert.equal(traceFailed(compact), true); assert.equal(traceEventMatches({ node: compact }, 'failed', ''), true);
  assert.equal(traceFailed({ ...compact, status: 'CANCELLED' }), false);
  assert.equal(traceFailed({ ...compact, status: 'STOPPED' }), false);
});

test('generation and output rate require actual first-to-last observations, not request tail time', () => {
  const measured = node({ firstVisibleTextElapsedNanos: '350123456', timingEvidence: 'provider_text_delta', generationDurationMs: 1200,
    generationTimingMeaning: 'client_first_to_last_visible_text_delta', usage: usage({ OUTPUT: count(120) }) });
  assert.deepEqual(traceRequestTiming(measured), { firstText: 350.123456, generation: 1200, throughput: 100 });
  assert.equal(traceRequestTiming({ ...measured, generationDurationMs: undefined }).generation, null);
  assert.equal(traceRequestTiming({ ...measured, generationTimingMeaning: 'wall_clock_guess' }).generation, null);
  assert.equal(traceRequestTiming({ ...measured, generationDurationMs: 0 }).throughput, null);
  assert.equal(traceRequestTiming({ ...measured, finishedAt: null }).generation, null);
  assert.equal(traceRequestTiming({ ...measured, usage: usage({ OUTPUT: count(20, 'PARTIAL') }) }).throughput, null);
});
