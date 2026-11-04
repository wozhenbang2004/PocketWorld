import test from 'node:test';
import assert from 'node:assert/strict';
import { continuationBlocks, continuationFailed, continuationReceiptMatches, continuationRequest, continuationSettled, continuationUnknown, privateProjectTask, projectContinuation, type ContinuationSubmission } from '../src/task-continuation.ts';

const review = () => ({ reviewHandle: 'native-review', task: { id: 'original-task', status: 'completed', raw: { id: 'original-task', kind: 'assistant', actorId: 'owner', spaceId: 'private', conversationId: 'original-conversation', runId: 'old-run', coordinatorEpoch: 4, developmentContext: { projectId: 'p', checkoutId: 'c' } } },
  projectContinuation: { feedbackRequired: true, conversationId: 'original-conversation' }, actions: [{ action: 'tasks.resume' }] });
const request = () => continuationRequest(review(), 'original-task', '  继续调整按钮间距。  ');
const receipt = () => ({ taskId: 'original-task', conversationId: 'original-conversation', continued: true, runId: 'new-run', epoch: 5, resourceState: 'queued' });
const pending = (): ContinuationSubmission => ({ original: request().original, status: 'pending', message: 'pending' });

test('project display hint cannot grant a resume action or substitute a conversation', () => {
  const value = review(); assert.equal(privateProjectTask(value.task), true);
  assert.equal(projectContinuation(value)?.conversationId, 'original-conversation');
  assert.throws(() => continuationRequest({ ...value, actions: [] }, 'original-task', '继续修改'));
  assert.equal(projectContinuation({ ...value, projectContinuation: { feedbackRequired: 'true', conversationId: 'original-conversation' } }), null);
  assert.equal(projectContinuation({ ...value, projectContinuation: { feedbackRequired: true, conversationId: 'other' } }), null);
  assert.equal(privateProjectTask({ ...value.task, raw: { ...value.task.raw, origin: { kind: 'group' } } }), false);
});

test('the owner continuation command has exactly three fields and explicit trimmed feedback', () => {
  const input = request(); assert.deepEqual(input.args, { reviewHandle: 'native-review', confirmed: true, feedback: '继续调整按钮间距。' });
  assert.deepEqual(Object.keys(input.args).sort(), ['confirmed', 'feedback', 'reviewHandle']);
  assert.deepEqual(input.original, { taskId: 'original-task', conversationId: 'original-conversation', runId: 'old-run', epoch: 4, reviewHandle: 'native-review' });
  const before = review(), after = structuredClone(before); continuationRequest(before, 'original-task', '继续修改'); assert.deepEqual(before, after);
});

test('blank, oversized, denied and mismatched original reviews cannot prepare a continuation', () => {
  for (const feedback of ['', ' \n\t ', '字'.repeat(8001)]) assert.throws(() => continuationRequest(review(), 'original-task', feedback));
  assert.equal(continuationRequest(review(), 'original-task', '字'.repeat(8000)).args.feedback.length, 8000);
  assert.throws(() => continuationRequest(review(), 'another-task', '继续修改'));
  const denied = review(); Object.assign(denied.projectContinuation, { reason: '原任务范围已到期' }); assert.throws(() => continuationRequest(denied, 'original-task', '继续修改'));
  for (const change of [{ id: 'another-task' }, { runId: '' }, { coordinatorEpoch: '4' }, { coordinatorEpoch: 0 }]) {
    const value = review(); Object.assign(value.task.raw, change); assert.throws(() => continuationRequest(value, 'original-task', '继续修改'));
  }
});

test('only an actual new original task/conversation native receipt matches', () => {
  const original = request().original; assert.equal(continuationReceiptMatches(receipt(), original), true);
  for (const change of [{ taskId: 'other-task' }, { conversationId: 'other-conversation' }, { continued: 'true' }, { continued: false }, { runId: 'old-run' }, { runId: '' }, { epoch: '5' }, { epoch: 4 }, { epoch: 0 }, { epoch: 2147483648 }])
    assert.equal(continuationReceiptMatches({ ...receipt(), ...change }, original), false, JSON.stringify(change));
  assert.equal(continuationReceiptMatches({ accepted: true, taskId: 'original-task' }, original), false);
});

test('current receipt proves continuation only and never a composer-send acknowledgment', () => {
  const value = continuationSettled(pending(), receipt(), true); assert.equal(value.status, 'continued'); assert.equal(continuationBlocks(value), false);
  assert.match(value.message, /草稿尚未发送/); assert.equal('clientMessageId' in value, false);
});

test('closing a sent window retains unknown and neither a read nor late success unlocks replay', () => {
  const unknown = continuationUnknown(pending()); assert.equal(unknown.status, 'unknown'); assert.equal(continuationBlocks(unknown), true);
  assert.strictEqual(continuationUnknown(unknown), unknown);
  assert.strictEqual(continuationSettled(unknown, receipt(), true), unknown);
  assert.strictEqual(continuationFailed(unknown, { code: 'not_accepted' }, true), unknown);
  assert.equal(continuationSettled(pending(), receipt(), false).status, 'unknown');
});

test('timeout and malformed result retain unknown, definite current non-admission permits fresh review', () => {
  for (const code of ['outcome_unknown', 'unknown', undefined]) assert.equal(continuationFailed(pending(), { code }, true).status, 'unknown');
  assert.equal(continuationSettled(pending(), { accepted: true }, true).status, 'unknown');
  for (const code of ['not_sent', 'not_accepted']) {
    const value = continuationFailed(pending(), { code, message: '原任务已变化' }, true); assert.equal(value.status, 'rejected'); assert.equal(continuationBlocks(value), false);
    assert.equal(continuationFailed(pending(), { code }, false).status, 'unknown');
  }
});
