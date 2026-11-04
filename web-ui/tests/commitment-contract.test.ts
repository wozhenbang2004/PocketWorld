import test from 'node:test';
import assert from 'node:assert/strict';
import { commitmentDraft, commitmentReview, commitmentPolicyReview, commitmentMutationReceipt, OwnerWorkspaceRequest } from '../src/commitment-contract.ts';

test('editing keeps the original deadline unless explicitly changed and a lost read cannot become create', () => {
  const draft = commitmentDraft({ title: '交付原成果', details: '核验真实文件', taskId: 'original', dueAt: 12345 });
  assert.equal(draft.hours, '');
  const input = commitmentReview(draft, 'original-version');
  assert.equal(input.operation, 'update'); assert.equal(input.hours, undefined); assert.equal(input.taskId, 'original');
  assert.throws(() => commitmentReview(draft, ''));
  draft.hours = '1'; assert.equal(commitmentReview(draft, 'original-version').hours, 1);
  draft.hours = '0'; assert.throws(() => commitmentReview(draft, 'original-version'));
});

test('new commitments need an explicit future deadline and do not admit execution', () => {
  const draft = commitmentDraft(); draft.title = '原目标';
  assert.deepEqual(commitmentReview(draft), { operation: 'create', title: '原目标', details: '', taskId: '', hours: 24 });
  for (const hours of ['', 'NaN', '1.5', '8761']) assert.throws(() => commitmentReview({ ...draft, hours }));
});

test('policy drafts bind the displayed revision and invalid numbers cannot remove a daily limit', () => {
  const policy = { revision: 0, enabled: true, zoneId: 'Asia/Shanghai', cooldownMinutes: '5', quiet: false, pausedUntil: null, maxNotificationsPerDay: '3' };
  assert.equal(commitmentPolicyReview(policy).revision, 0); assert.equal(commitmentPolicyReview(policy).maxNotificationsPerDay, 3);
  assert.equal(commitmentPolicyReview({ ...policy, maxNotificationsPerDay: '' }).maxNotificationsPerDay, null);
  assert.equal(commitmentPolicyReview({ ...policy, maxNotificationsPerDay: '0' }).maxNotificationsPerDay, 0);
  for (const maxNotificationsPerDay of ['abc', 'NaN', 'Infinity', ' ', '-1', '1001', '1.5']) assert.throws(() => commitmentPolicyReview({ ...policy, maxNotificationsPerDay }));
  assert.throws(() => commitmentPolicyReview({ ...policy, revision: undefined }));
});

test('queued acknowledgements or another command cannot complete a commitment write', () => {
  assert.throws(() => commitmentMutationReceipt({ accepted: true }, 'createCommitment'));
  assert.throws(() => commitmentMutationReceipt({ confirmed: true, command: 'createCommitment', result: {} }, 'createCommitment'));
  assert.throws(() => commitmentMutationReceipt({ confirmed: true, command: 'cancelCommitment', result: { commitment: {} } }, 'createCommitment'));
  const result = { commitment: { id: 'original', revision: 5 } };
  assert.equal(commitmentMutationReceipt({ confirmed: true, command: 'updateCommitment', result }, 'updateCommitment'), result);
});

test('background or successor requests reject old results without clearing the current pending operation', () => {
  const scope = new OwnerWorkspaceRequest(), first = scope.begin()!;
  assert.equal(scope.begin(), null); assert.equal(scope.owns(first), true);
  scope.invalidate(false); assert.equal(scope.owns(first), false); assert.equal(scope.begin(), null);
  scope.invalidate(true); const second = scope.begin()!;
  assert.notEqual(first, second); assert.equal(scope.finish(first), false); assert.equal(scope.begin(), null);
  assert.equal(scope.finish(second), true); assert.equal(scope.owns(second), false); assert.notEqual(scope.begin(), null);
});
