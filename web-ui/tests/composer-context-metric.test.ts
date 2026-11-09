import test from 'node:test';
import assert from 'node:assert/strict';
import { composerContextMetric, contextPercentLabel } from '../src/composer-context-metric.ts';

function fixture() {
  return { host: { connected: true }, composer: { key: 'conversation:c1', epoch: 2 }, state: {
    conversationId: 'c1', foregroundTurn: { conversationId: 'c1', taskId: 't1', runId: 'r1', epoch: 3 },
    messages: [{ role: 'assistant', taskId: 't1' }], tasks: [{ id: 't1', status: 'completed', raw: {
      id: 't1', kind: 'assistant', actorId: 'owner', spaceId: 'private', conversationId: 'c1', runId: 'r1', coordinatorEpoch: 3,
      status: 'completed', events: [{ type: 'context_budget', taskId: 't1', runId: 'r1', epoch: 3, at: 10000, receipt: {
        modelWindowKnown: true, modelContextTokens: 100000, inputLimitTokens: 80000, outputReserveTokens: 16000,
        after: { estimatedInputTokens: 57000 }, summaryProjectionUsed: false, status: 'ready' } }],
    } }],
  } };
}
const key = 'conversation:c1:2';
const read = (value: unknown) => composerContextMetric(value, key);
const native = (value: ReturnType<typeof fixture>) => value.state.tasks[0].raw;
const receipt = (value: ReturnType<typeof fixture>) => native(value).events[0].receipt;

test('uses native last-request estimate, distinguishes window and input allowance', () => {
  const value = read(fixture());
  assert.equal(value.available, true); assert.ok(Math.abs(value.windowPercent! - 57) < 1e-10); assert.equal(value.inputPercent, 71.25);
  assert.equal(value.outputReserve, 16000); assert.equal(value.taskId, 't1'); assert.equal(value.at, 10000);
  assert.match(value.reason, /不含尚未发送的草稿/);
});
for (const [name, alter] of Object.entries({
  disconnected: (s: any) => s.host.connected = false,
  differentComposer: (s: any) => s.composer.epoch++,
  differentConversation: (s: any) => native(s).conversationId = 'other',
  foreignActor: (s: any) => native(s).actorId = 'channel',
  foreignSpace: (s: any) => native(s).spaceId = 'team',
  mismatchedTask: (s: any) => native(s).id = 'other',
  phoneTask: (s: any) => native(s).kind = 'phone',
  backgroundWork: (s: any) => native(s).executionRole = 'owner_work',
  wrongRun: (s: any) => native(s).runId = 'old',
  wrongEpoch: (s: any) => native(s).coordinatorEpoch++,
  eventWrongRun: (s: any) => native(s).events[0].runId = 'old',
  eventWrongEpoch: (s: any) => native(s).events[0].epoch--,
  eventWrongTask: (s: any) => native(s).events[0].taskId = 'other',
  unknownWindow: (s: any) => receipt(s).modelWindowKnown = false,
  zeroWindow: (s: any) => receipt(s).modelContextTokens = 0,
  stringEstimate: (s: any) => receipt(s).after.estimatedInputTokens = '57000',
  negativeEstimate: (s: any) => receipt(s).after.estimatedInputTokens = -1,
  fractionalEstimate: (s: any) => receipt(s).after.estimatedInputTokens = .5,
  unsafeEstimate: (s: any) => receipt(s).after.estimatedInputTokens = Number.MAX_SAFE_INTEGER + 1,
  infiniteEstimate: (s: any) => receipt(s).after.estimatedInputTokens = Infinity,
  zeroInputBudget: (s: any) => receipt(s).inputLimitTokens = 0,
  impossibleReservation: (s: any) => receipt(s).outputReserveTokens = 50000,
  newerMalformedRecord: (s: any) => native(s).events.push({ type: 'context_budget', taskId: 't1', receipt: null }),
  noEvents: (s: any) => native(s).events = [],
})) test(`unknown instead of invented zero: ${name}`, () => {
  const value = fixture(); alter(value); const result = read(value);
  assert.equal(result.available, false); assert.equal(result.windowPercent, null);
});
test('empty, phone and unbound composers are unknown', () => {
  assert.equal(composerContextMetric({}, key).available, false);
  assert.equal(composerContextMetric(fixture(), 'phone:2').available, false);
  assert.equal(composerContextMetric(fixture(), undefined).available, false);
});
test('a retained real zero is displayed, unlike missing data', () => {
  const value = fixture(); receipt(value).after.estimatedInputTokens = 0;
  assert.equal(read(value).windowPercent, 0); assert.equal(contextPercentLabel(0), '0%');
});
test('compaction failure supersedes prior successful check', () => {
  const value: any = fixture(); native(value).status = 'awaiting_user'; native(value).evidence = { contextCompaction: { status: 'failed' } };
  const result = read(value); assert.equal(result.available, false); assert.equal(result.attention, true);
});
test('blocked budget supersedes old event and is never clamped to success', () => {
  const value: any = fixture(); native(value).status = 'awaiting_user';
  native(value).evidence = { contextBudget: { ...receipt(value), after: { estimatedInputTokens: 120000 }, status: 'blocked' } };
  const result = read(value); assert.equal(result.windowPercent, 120); assert.equal(result.inputPercent, 150);
  assert.equal(result.attention, true); assert.equal(result.at, null);
});
test('missing most recent task does not fall back to an older known task', () => {
  const value: any = fixture(); value.state.foregroundTurn = null; value.state.messages.push({ taskId: 'new-missing' });
  assert.equal(read(value).available, false); assert.equal(read(value).taskId, 'new-missing');
});
test('completed foreground history can be read without an active run', () => {
  const value: any = fixture(); value.state.foregroundTurn = null; assert.ok(Math.abs(read(value).windowPercent! - 57) < 1e-10);
});
test('unrelated delivered task result never owns the composer meter', () => {
  const value: any = fixture(); value.state.foregroundTurn = null;
  value.state.messages.push({ taskId: 'unrelated', taskResult: { kind: 'owner_work' } });
  assert.equal(read(value).taskId, 't1');
});
test('summary and timestamps come from the check, not a UI timer', () => {
  const value = fixture(); receipt(value).summaryProjectionUsed = true;
  assert.equal(read(value).summaryUsed, true); assert.equal(read(value).at, 10000);
  native(value).events[0].at = 9e15; assert.equal(read(value).at, null);
});
test('percent labels preserve unknown, nonzero sub-percent and over-budget states', () => {
  assert.equal(contextPercentLabel(null), '未测得'); assert.equal(contextPercentLabel(NaN), '未测得');
  assert.equal(contextPercentLabel(.2), '<1%'); assert.equal(contextPercentLabel(99.8), '99%');
  assert.equal(contextPercentLabel(120), '120%');
});
test('reading does not mutate the native snapshot or infer from cumulative usage', () => {
  const value: any = fixture(); value.state.totalTokens = 900000000; value.state.composerDraft = 'x'.repeat(10000);
  const before = JSON.stringify(value); assert.ok(Math.abs(read(value).windowPercent! - 57) < 1e-10); assert.equal(JSON.stringify(value), before);
});
