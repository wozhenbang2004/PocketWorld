import test from 'node:test';
import assert from 'node:assert/strict';
import { traceSteps } from '../src/trace-stepper.ts';
import type { TraceNode } from '../src/execution-trace.ts';

const node = (patch: Partial<TraceNode> = {}): TraceNode => ({ id: 'model:root', parentId: null, kind: 'model', name: '原模型请求', status: 'COMPLETED',
  startedAt: 1000, finishedAt: 2000, elapsedNanos: '1000000000', durationMs: 1000, source: 'native_inference_receipt', sequence: 1,
  timingKnown: true, legacy: false, ...patch });
const branch = () => [node(),
  node({ id: 'tool:child', kind: 'tool', parentId: 'model:root', name: '真实工具调用', sequence: 2 }),
  node({ id: 'tool:grandchild', kind: 'tool', role: 'subtool', parentId: 'tool:child', name: '真实下级调用', sequence: 3 }),
  node({ id: 'event:sibling', kind: 'event', parentId: 'model:root', name: '原事件', sequence: 4 })];

test('each native node is rendered once with its real parent depth and retained identity', () => {
  const nodes = branch(), original = structuredClone(nodes);
  const steps = traceSteps(nodes, new Set());
  assert.deepEqual(steps.map(step => [step.id, step.depth]), [['model:root', 0], ['tool:child', 1], ['tool:grandchild', 2], ['event:sibling', 1]]);
  assert.equal(new Set(steps.map(step => step.id)).size, nodes.length);
  assert.deepEqual(nodes, original, 'the display adapter does not rewrite source/status/parent fields');
  assert.deepEqual(steps.map(step => step.hasChildren), [true, true, false, false]);
  assert.equal(steps[2].statusLabel, 'SUBTOOL · 已完成');
  assert.ok(steps.every(step => !('progress' in step) && !('percent' in step) && !('children' in step)));
});

test('folding hides only descendants while filtering reopens their context without changing fold state', () => {
  const nodes = branch(), folded = new Set(['model:root']);
  nodes[2] = { ...nodes[2], status: 'UNKNOWN', hasError: true, name: 'connection reset 待核对' };
  assert.deepEqual(traceSteps(nodes, folded).map(step => step.id), ['model:root']);
  const found = traceSteps(nodes, folded, 'failed', 'connection reset');
  assert.deepEqual(found.map(step => [step.id, step.depth, step.context]), [['model:root', 0, true], ['tool:child', 1, true], ['tool:grandchild', 2, false]]);
  assert.ok(found.every(step => step.canToggle === false));
  assert.equal(found[0].expanded, true);
  assert.deepEqual([...folded], ['model:root']);
  assert.deepEqual(traceSteps(nodes, new Set(), 'tool').map(step => step.id), ['model:root', 'tool:child', 'tool:grandchild']);
});

test('returned effects, approval, cancellation and unfamiliar statuses never become inferred success', () => {
  for (const [status, effectPending, visual] of [
    ['UNKNOWN', true, 'returned'], ['UNKNOWN', false, 'unknown'], ['OBSERVED', false, 'unknown'],
    ['NEW_PROVIDER_STATUS', false, 'unknown'], ['NEEDS_CONFIRMATION', false, 'needs_approval'],
    ['WAITING_APPROVAL', false, 'needs_approval'], ['AWAITING_USER', false, 'waiting'],
    ['CANCELLED', false, 'stopped'], ['STARTED', false, 'running'], ['ACCEPTED', false, 'pending'],
  ] as const) {
    const [step] = traceSteps([node({ status, effectPending })], new Set());
    assert.equal(step.status, visual, status);
    if (effectPending) assert.match(step.statusLabel!, /动作效果待确认/);
    if (status === 'NEW_PROVIDER_STATUS') assert.match(step.statusLabel!, /NEW_PROVIDER_STATUS/);
  }
  const [unknown] = traceSteps([node({ status: 'UNKNOWN', hasError: true })], new Set());
  assert.equal(unknown.status, 'unknown'); assert.match(unknown.statusLabel!, /待核对 · 有错误记录/);
});

test('only native observed durations are sent; zero and sub-second evidence retain their precision', () => {
  for (const patch of [{ timingKnown: false }, { finishedAt: null }, { elapsedNanos: null, durationMs: null }]) {
    const [step] = traceSteps([node(patch)], new Set());
    assert.equal('durationMs' in step, false); assert.equal('durationLabel' in step, false);
    assert.match(step.statusLabel!, /耗时未记录/);
  }
  const [measured] = traceSteps([node({ durationMs: null, elapsedNanos: '125000000' })], new Set());
  assert.equal(measured.durationMs, 125); assert.equal(measured.durationLabel, '125 ms');
  const [zero] = traceSteps([node({ durationMs: 0, elapsedNanos: '0' })], new Set());
  assert.equal(zero.durationMs, 0); assert.equal(zero.durationLabel, '0 ms');
});

test('missing parents and malformed cyclic relations remain visible and explicitly unverified', () => {
  const nodes = [node({ id: 'orphan', parentId: 'outside', parentVisible: false }),
    node({ id: 'cycle:a', parentId: 'cycle:b', sequence: 2 }), node({ id: 'cycle:b', parentId: 'cycle:a', sequence: 3 }),
    node({ id: 'leaf', parentId: 'cycle:a', sequence: 4 })];
  const steps = traceSteps(nodes, new Set());
  assert.equal(steps.length, nodes.length);
  assert.match(steps.find(step => step.id === 'orphan')!.statusLabel!, /父记录未读入：outside/);
  for (const id of ['cycle:a', 'cycle:b']) {
    const step = steps.find(step => step.id === id)!;
    assert.equal(step.depth, 0); assert.match(step.statusLabel!, /父子关系待核对/);
  }
  assert.equal(steps.find(step => step.id === 'leaf')!.depth, 1);
  assert.throws(() => traceSteps([node(), node()], new Set()), /Duplicate trace step ID/);
});
