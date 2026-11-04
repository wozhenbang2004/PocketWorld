import test from 'node:test';
import assert from 'node:assert/strict';
import { tokenBudgetCount, tokenBudgetDraft, tokenBudgetRebasedDraft, tokenBudgetReview, tokenBudgetReviewInput, tokenBudgetSaved, tokenBudgetSnapshot } from '../src/token-budget-contract.ts';

const pool = { revision: 4, enabled: true, limit: 10000, knownTokens: 300, heldTokens: 500, zoneId: 'UTC', nextZoneId: 'Asia/Shanghai' };
const snapshot = () => ({ format: 'pocketworld.owner-token-budgets.v1', readOnly: true, executionAuthorized: false, windowId: 'original-window', defaults: { revision: 0, enabled: false, limit: 0 }, routes: [{ routeKey: 'connection:original', label: '原连接', budget: { ...pool } }], task: { taskId: 'original-task', ownerTaskId: 'original-task', editable: true, budget: { ...pool } } });

test('a task budget read cannot adopt a successor task or native window', () => {
  const value = snapshot(); assert.equal(tokenBudgetSnapshot(value, 'original-task', 'original-window'), value);
  assert.throws(() => tokenBudgetSnapshot(value, 'successor-task')); assert.throws(() => tokenBudgetSnapshot(value, 'original-task', 'successor-window'));
  assert.throws(() => tokenBudgetSnapshot({ ...value, defaults: { revision: 0, enabled: false } }));
  assert.throws(() => tokenBudgetSnapshot({ ...value, readOnly: false }));
});

test('empty, rounded, exponential and unknown token numbers never become an enabled zero limit', () => {
  const draft = tokenBudgetDraft('daily', 'connection:original', '原连接', pool);
  for (const limit of ['', '1.5', '1e6', 'Infinity', '-1', '1000000000001']) assert.throws(() => tokenBudgetReviewInput({ ...draft, limit }, 'window'));
  const result = tokenBudgetReviewInput({ ...draft, limit: '1000000000000' }, 'window'); assert.equal(result.limit, 1e12); assert.equal(result.id, 'connection:original'); assert.equal(result.expectedRevision, 4);
  assert.throws(() => tokenBudgetReviewInput({ ...draft, zoneId: 'not/a/timezone' }, 'window'));
  assert.throws(() => tokenBudgetDraft('task', 'id', 'task', { revision: 1, enabled: true, limit: null }));
});

test('disabling retains an explicit disable decision without inventing an absent token count', () => {
  const draft = tokenBudgetDraft('default', '', '新任务', { revision: 0, enabled: false, limit: 0 });
  assert.equal(draft.limit, ''); assert.deepEqual(tokenBudgetReviewInput(draft, 'w'), { windowId: 'w', scope: 'default', id: '', expectedRevision: 0, enabled: false, limit: 0, zoneId: '' });
  assert.equal(tokenBudgetCount(null), '尚未核对'); assert.equal(tokenBudgetCount(undefined), '尚未核对'); assert.equal(tokenBudgetCount(0), '0');
});

test('native review must preserve all original policy fields and cannot authorize task effects', () => {
  const input = tokenBudgetReviewInput(tokenBudgetDraft('task', 'original-task', '原任务', pool), 'window');
  const value = { windowId: 'window', reviewHandle: 'opaque-handle', input: { ...input }, previous: pool, executionStarted: false, effectsApproved: false };
  assert.equal(tokenBudgetReview(value, input), value);
  for (const field of ['scope', 'id', 'expectedRevision', 'enabled', 'limit', 'zoneId']) assert.throws(() => tokenBudgetReview({ ...value, input: { ...input, [field]: 'changed' } }, input));
  assert.throws(() => tokenBudgetReview({ ...value, effectsApproved: true }, input));
});

test('a stale or generic save acknowledgment does not clear an unknown write', () => {
  const review = { windowId: 'window', input: { scope: 'daily', id: 'connection:original', expectedRevision: 4, enabled: true, limit: 15000 } };
  const receipt = { windowId: 'window', saved: true, scope: 'daily', id: 'connection:original', receipt: { revision: 5, enabled: true, limit: 15000, knownTokens: 300, heldTokens: 500 }, executionStarted: false, effectsApproved: false };
  assert.equal(tokenBudgetSaved(receipt, review), receipt.receipt);
  assert.throws(() => tokenBudgetSaved({ accepted: true }, review)); assert.throws(() => tokenBudgetSaved({ ...receipt, id: 'connection:other' }, review));
  assert.throws(() => tokenBudgetSaved({ ...receipt, receipt: { ...receipt.receipt, revision: 4 } }, review));
  assert.throws(() => tokenBudgetSaved({ ...receipt, receipt: { ...receipt.receipt, limit: 20000 } }, review));
});

test('explicit post-unknown recheck updates only the original revision while preserving typed edits', () => {
  const draft = { ...tokenBudgetDraft('daily', 'connection:original', '原连接', pool), enabled: false, limit: '22500', zoneId: 'Asia/Tokyo' };
  const fresh = snapshot(); fresh.routes[0].budget = { ...pool, revision: 5, limit: 12300, heldTokens: 900 };
  const rebased = tokenBudgetRebasedDraft(draft, fresh);
  assert.equal(rebased.revision, 5); assert.equal(rebased.previous.heldTokens, 900);
  assert.equal(rebased.limit, '22500'); assert.equal(rebased.zoneId, 'Asia/Tokyo'); assert.equal(rebased.enabled, false);
  assert.equal(draft.revision, 4); assert.equal(draft.previous.heldTokens, 500);
  fresh.routes[0].routeKey = 'connection:replacement'; assert.throws(() => tokenBudgetRebasedDraft(draft, fresh));
  const child = tokenBudgetDraft('task', 'original-task', '原任务', pool), changed = snapshot(); changed.task.ownerTaskId = 'another-parent'; assert.throws(() => tokenBudgetRebasedDraft(child, changed));
});
