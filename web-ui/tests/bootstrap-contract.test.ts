import test from 'node:test';
import assert from 'node:assert/strict';
import { bootstrapSurface, bootstrapView, bootstrapRetryPayload, currentBootstrapView, retainedBootstrapOperation, captureRepairReview, repairAllowed, repairModeAllowed, repairConsistencyText, repairConfirmPayload, repairReviewPayload, repairOutcome, type BootstrapView } from '../src/bootstrap-contract.ts';

const original = (): BootstrapView => ({ surface: 'bootstrap', revision: 8, generation: 3, state: 'maintenance', stage: 'data_opened', recovery: 'not_started', reason: null,
  installedVersion: 'test', requiredSchema: 10, observedSchema: 10, retryAvailable: false, optionalUnavailable: [], maintenance: {
    repairId: 'original-repair', phase: 'reviewed', sourceFingerprint: 'original-db', backupFingerprint: 'verified-backup', preservationVerified: true,
    files: [], allowedCommands: ['repair.read', 'repair.review', 'repair.confirm'], reviewModes: ['empty_database'], review: { reviewId: 'original-review', mode: 'empty_database', summary: '建立空库，历史未恢复' },
  } });
const verifiedCopy = (): BootstrapView => {
  const view = original(); view.maintenance!.reviewModes = ['empty_database', 'verified_copy'];
  view.maintenance!.review = { reviewId: 'original-review', mode: 'verified_copy', summary: '仅恢复所选对话文字', restoration: {
    section: 'conversations', conversationCount: 3, messageCount: 18, omittedCount: 2, contentUtf8Bytes: 2400,
    sourceFingerprint: 'verified-backup', sourceReadOnly: true, restoresExistingIds: false, restoresActiveGrants: false,
    restoresTaskHistory: false, restoresAttachmentBytes: false, restoresCredentialsOrPairings: false,
    omissionMeaning: '删除和非对话文字记录明确省略，读取失败拒绝本次暂存',
  } }; return view;
};

test('maintenance command availability never turns the startup surface into normal business UI', () => {
  assert.equal(bootstrapSurface({ surface: 'bootstrap', capabilities: { commands: ['bootstrap.retry'] } }), true);
  assert.equal(bootstrapSurface({ host: { surface: 'bootstrap' } }), true);
  assert.equal(bootstrapSurface({ host: { bridgeAvailable: false }, state: { messages: [] } }), true);
  assert.equal(bootstrapSurface({ state: {}, capabilities: { commands: ['app.ready'] } }), false, 'old normal endpoints remain business by default');
});
test('invalid native state or an unsafe generation never creates a usable bootstrap identity', () => {
  assert.equal(bootstrapView({ ...original(), generation: Number.MAX_SAFE_INTEGER + 1 }), null);
  assert.equal(bootstrapView({ ...original(), state: 'blocked' }), null);
  assert.equal(bootstrapView({ ...original(), recovery: 'complete' }), null);
  assert.ok(bootstrapView(original()));
});
test('only advertised native repair actions are offered; a preserved copy is not confirmation authority', () => {
  const view = original();
  assert.equal(repairAllowed(view, 'repair.confirm'), true);
  assert.equal(repairAllowed(view, 'repair.preserve'), false);
  view.maintenance!.allowedCommands = ['repair.read'];
  assert.throws(() => captureRepairReview(view));
});
test('confirmation requires the same generation, repair, source, backup and one-use review identity', () => {
  const view = original(), review = captureRepairReview(view);
  assert.throws(() => repairConfirmPayload(view, review, false, 'request'));
  assert.equal(repairConfirmPayload(view, review, true, 'request').confirmed, true);
  for (const field of ['repairId', 'sourceFingerprint', 'backupFingerprint'] as const) {
    const changed = original(); changed.maintenance![field] = 'successor';
    assert.throws(() => repairConfirmPayload(changed, review, true, 'request'), field);
  }
  assert.throws(() => repairConfirmPayload({ ...view, generation: 4 }, review, true, 'request'));
  const changed = original(); changed.maintenance!.review!.reviewId = 'later-review';
  assert.throws(() => repairConfirmPayload(changed, review, true, 'request'));
});
test('unverified preservation and unsupported restore modes cannot become empty-database consent', () => {
  const unverified = original(); unverified.maintenance!.preservationVerified = false;
  assert.throws(() => captureRepairReview(unverified));
  const otherMode = original(); otherMode.maintenance!.review!.mode = 'verified_copy';
  assert.throws(() => captureRepairReview(otherMode));
});
test('a new empty database is never described as recovered history', () => {
  const view = original(); view.maintenance!.result = { mode: 'empty_database', summary: 'ready' };
  assert.equal(repairOutcome(view), null, 'an unfinished cutover is not completed');
  view.maintenance!.phase = 'completed';
  assert.match(repairOutcome(view)!, /原历史未恢复/);
  assert.match(repairOutcome(view)!, /不会继续旧任务/);
});
test('native window expiry revokes an equal-revision review instead of keeping a previous command result', () => {
  const previous = original(), review = captureRepairReview(previous), native = original();
  native.maintenance!.review = null; native.maintenance!.allowedCommands = ['repair.read', 'repair.review'];
  const current = currentBootstrapView(native, previous);
  assert.equal(current, native);
  assert.throws(() => repairConfirmPayload(current, review, true, 'request'));
});
test('late preservation identity enriches only its original unknown request and never clears uncertainty', () => {
  const local = { requestId: 'original-request', command: 'repair.preserve', generation: 3, repairId: '', status: 'unknown' as const };
  const saved = { ...local, repairId: 'exact-native-repair', status: 'completed' as const };
  assert.deepEqual(retainedBootstrapOperation(local, saved), { ...local, repairId: 'exact-native-repair' });
  assert.equal(retainedBootstrapOperation(local, { ...saved, requestId: 'another-request' })!.repairId, '');
});
test('file-set consistency is readable without upgrading byte preservation into database integrity', () => {
  const summary = repairConsistencyText({ quiescence: 'original_business_handles_closed_before_capture', copiesSha256AndLengthVerified: true,
    sourceBefore: 'original', sourceAfter: 'original', sqliteLogicalIntegrity: 'not_claimed', preCorruptionCallbackBytes: 'not_claimed' });
  assert.match(summary, /副本 SHA-256 与长度已核验/);
  assert.match(summary, /未确认原库逻辑完整性/);
  assert.match(summary, /不能证明损坏回调前/);
  assert.doesNotMatch(repairConsistencyText({ copiesSha256AndLengthVerified: false, sourceBefore: 'a', sourceAfter: 'b' }), /已核验|集合一致/);
});
test('verified-copy review must be natively offered and explicitly selects only conversation content', () => {
  assert.equal(repairModeAllowed(original(), 'verified_copy'), false);
  assert.throws(() => repairReviewPayload(original(), 'verified_copy', 'request'));
  assert.deepEqual(repairReviewPayload(verifiedCopy(), 'verified_copy', 'request').sections, ['conversations']);
  assert.equal('sections' in repairReviewPayload(original(), 'empty_database', 'request'), false);
  assert.throws(() => captureRepairReview(verifiedCopy(), 'empty_database'), 'the requested empty mode cannot silently become another mode');
});
test('verified-copy consent binds actual counts, scope exclusions and the original backup instead of treating it as whole-database recovery', () => {
  const view = verifiedCopy(), review = captureRepairReview(view, 'verified_copy');
  assert.equal(review.restoration!.omittedCount, 2);
  assert.equal(repairConfirmPayload(view, review, true, 'request').repairId, 'original-repair');
  for (const patch of [{ conversationCount: -1 }, { sourceFingerprint: 'another-copy' }, { restoresExistingIds: true }, { restoresActiveGrants: true }, { restoresTaskHistory: true }]) {
    const changed = verifiedCopy(); Object.assign(changed.maintenance!.review!.restoration!, patch);
    assert.throws(() => captureRepairReview(changed, 'verified_copy'), JSON.stringify(patch));
  }
  view.maintenance!.review!.restoration!.messageCount = 19;
  assert.throws(() => repairConfirmPayload(view, review, true, 'request'), 'changed actual contents expire the original consent');
});
test('verified-copy completion reports only new-identity conversation text and never falls back to an empty success', () => {
  const view = verifiedCopy(); view.maintenance!.phase = 'completed';
  view.maintenance!.result = { mode: 'verified_copy', executionEnabled: false, restoration: view.maintenance!.review!.restoration };
  assert.match(repairOutcome(view)!, /3 段对话、18 条文字消息/);
  assert.match(repairOutcome(view)!, /原任务历史、附件原件和执行授权未恢复/);
  view.maintenance!.result!.restoration!.messageCount = 0;
  assert.match(repairOutcome(view)!, /未恢复文字消息/);
  view.maintenance!.result!.restoration = undefined;
  assert.match(repairOutcome(view)!, /恢复范围尚无法完整核对/);
  assert.doesNotMatch(repairOutcome(view)!, /空库/);
});
test('retry of a preserved original database needs both native gates and its exact file-set identity', () => {
  const view = original(); view.maintenance!.phase = 'preserved'; view.retryAvailable = true; view.maintenance!.originalRetryAvailable = true;
  assert.deepEqual(bootstrapRetryPayload(view, 'original-request'), { expectedGeneration: 3, requestId: 'original-request', repairId: 'original-repair', sourceFingerprint: 'original-db', backupFingerprint: 'verified-backup' });
  view.maintenance!.originalRetryAvailable = false;
  assert.throws(() => bootstrapRetryPayload(view, 'request'));
  view.maintenance!.originalRetryAvailable = true; view.retryAvailable = false;
  assert.throws(() => bootstrapRetryPayload(view, 'request'));
  view.retryAvailable = true; view.maintenance!.originalRetryAvailable = false; view.maintenance!.phase = 'completed';
  assert.deepEqual(bootstrapRetryPayload(view, 'new-request'), { expectedGeneration: 3, requestId: 'new-request' }, 'completed empty/copy cutover retains its normal explicit startup route');
});
