import test from 'node:test';
import assert from 'node:assert/strict';
import { phoneStartReceipt, phoneStartFailure, phoneRunIdentity, messagePhoneResult, phoneConversationId } from '../src/phone-start.ts';

test('phone start needs an explicit original task receipt before clearing the reviewed draft', () => {
  assert.equal(phoneStartReceipt({ accepted: true }).status, 'unknown');
  assert.equal(phoneStartReceipt({ status: 'accepted' }).status, 'unknown');
  assert.deepEqual(phoneStartReceipt({ status: 'accepted', taskId: 'original-task' }), {
    status: 'accepted', taskId: 'original-task', message: '原手机任务已创建。',
  });
});

test('a known pre-dispatch refusal permits correction, while lost or ambiguous receipts never invite replay', () => {
  assert.equal(phoneStartReceipt({ status: 'rejected', message: '先连接后台助手' }).status, 'rejected');
  assert.equal(phoneStartFailure({ code: 'not_accepted', message: '原范围已变化' }).status, 'rejected');
  assert.equal(phoneStartFailure(new Error('等待回执超时')).status, 'unknown');
  assert.deepEqual(phoneStartReceipt({ status: 'unknown', taskId: 'original-task', message: '请核对原任务' }), {
    status: 'unknown', taskId: 'original-task', message: '请核对原任务',
  });
});

test('phone controls carry the exact displayed run and epoch, never only its reusable task id', () => {
  assert.deepEqual(phoneRunIdentity({ id: 'task', raw: { runId: 'old-run', coordinatorEpoch: 3 } }),
    { taskId: 'task', runId: 'old-run', epoch: 3 });
  assert.equal(phoneRunIdentity({ id: 'task', raw: {} }), null);
  assert.equal(phoneRunIdentity({ id: 'task', raw: { runId: 'run', coordinatorEpoch: -1 } }), null);
});

test('a linked start retains its original request, conversation and actual run receipt', () => {
  const expected = { requestId: 'original-task', conversationId: 'original-conversation' };
  const receipt = phoneStartReceipt({ ...expected, accepted: true, taskId: expected.requestId,
    runId: 'original-run', epoch: 4, admitted: true, status: 'running' }, expected);
  assert.equal(receipt.status, 'accepted');
  assert.equal(receipt.requestId, expected.requestId);
  assert.equal(receipt.conversationId, expected.conversationId);
  assert.equal(receipt.runId, 'original-run');
  assert.equal(receipt.epoch, 4);
  assert.equal(receipt.admitted, true);
  assert.equal(receipt.taskStatus, 'running');
});

test('creation acknowledgement is distinct from execution admission and a missing record is not permission to replay', () => {
  const expected = { requestId: 'original-task', conversationId: 'original-conversation' };
  const stored = phoneStartReceipt({ ...expected, accepted: true, taskId: expected.requestId, admitted: false, status: 'awaiting_user' }, expected);
  assert.equal(stored.status, 'accepted');
  assert.equal(stored.admitted, false);
  assert.equal(stored.runId, undefined);
  assert.equal(stored.taskStatus, 'awaiting_user');
  const absent = phoneStartReceipt({ ...expected, accepted: false, status: 'not_found' }, expected);
  assert.equal(absent.status, 'unknown');
  assert.equal(absent.requestId, expected.requestId);
  assert.equal(absent.conversationId, expected.conversationId);
});

test('a timeout keeps the reviewed identity so recovery only queries the original request', () => {
  const expected = { requestId: 'original-task', conversationId: 'original-conversation' };
  const receipt = phoneStartFailure({ code: 'outcome_unknown', message: '原回执迟到' }, expected);
  assert.deepEqual(receipt, { status: 'unknown', ...expected, message: '原回执迟到' });
  assert.equal(phoneStartFailure({ code: 'not_accepted' }, expected).status, 'rejected');
});

test('another conversation or task cannot consume the reviewed phone draft', () => {
  const expected = { requestId: 'original-task', conversationId: 'original-conversation' };
  for (const change of [{ conversationId: 'other' }, { requestId: 'other' }, { taskId: 'other' }]) {
    const receipt = phoneStartReceipt({ ...expected, taskId: expected.requestId, status: 'accepted', ...change }, expected);
    assert.equal(receipt.status, 'unknown');
    assert.equal(receipt.taskId, undefined);
    assert.equal(receipt.requestId, expected.requestId);
    assert.equal(receipt.conversationId, expected.conversationId);
  }
  assert.equal(phoneStartReceipt({ status: 'accepted', taskId: expected.requestId }, expected).status, 'unknown');
});

test('a phone result keeps its own ended run while the task resumes and later completes', () => {
  const frozen = { taskId: 'task', runId: 'run-one', epoch: 1, deliveryId: 'phone_handoff:task:run-one:1',
    status: 'failed', endedAt: 1793764860000, summaryTruncated: true, summaryChars: 25000 };
  const message = { role: 'assistant', taskId: 'task', content: 'Original failed result', phoneResult: frozen };
  const projection = messagePhoneResult(message);
  assert.deepEqual(projection, frozen);
  assert.notEqual(projection, frozen);
  assert.equal(messagePhoneResult({ ...message, phoneResult: { ...frozen, status: 'running' } }), null);
  for (const change of [{ taskId: 'another-task' }, { runId: 'run-two' }, { epoch: 2 }, { endedAt: 0 }, { summaryChars: '25000' }]) {
    assert.equal(messagePhoneResult({ ...message, phoneResult: { ...frozen, ...change } }), null);
  }
  assert.equal(messagePhoneResult({ ...message, role: 'user' }), null);
  assert.equal(messagePhoneResult({ role: 'assistant', taskId: 'task', content: message.content }), null);
  assert.equal(message.content, 'Original failed result');
});

test('a saved phone task is discoverable from its compact native header without a start receipt or message', () => {
  const task = { id: 'task', goal: 'Clipped goal', status: 'awaiting_user', raw: {
    id: 'task', kind: 'phone', actorId: 'owner', spaceId: 'private', taskProjection: 'summary',
    conversationPhoneLink: { taskId: 'task', conversationId: 'original-conversation' },
  } };
  assert.equal(phoneConversationId(task), 'original-conversation');
  for (const change of [{ kind: 'assistant' }, { actorId: 'other' }, { spaceId: 'group' }, { origin: {} }, { parentTaskId: 'parent' },
    { conversationPhoneLink: { taskId: 'other', conversationId: 'original-conversation' } }]) {
    assert.equal(phoneConversationId({ ...task, raw: { ...task.raw, ...change } }), null);
  }
});

test('a hot detail association must retain the original task and conversation identities', () => {
  const raw = { id: 'task', goal: 'Original goal', createdAt: 42, kind: 'phone', actorId: 'owner', spaceId: 'private',
    conversationPhoneHandoff: { format: 'pocketworld.conversation-phone-handoff.v1', taskId: 'task', requestId: 'task',
      conversationId: 'original-conversation', goal: 'Original goal', createdAt: 42, source: { conversationId: 'original-conversation' } } };
  assert.equal(phoneConversationId({ id: 'task', raw }), 'original-conversation');
  assert.equal(phoneConversationId({ id: 'task', raw: { ...raw, conversationPhoneHandoff: { ...raw.conversationPhoneHandoff, source: { conversationId: 'other' } } } }), null);
  assert.equal(phoneConversationId({ id: 'task', raw: { ...raw, conversationPhoneLink: null } }), null);
  assert.equal(phoneConversationId({ id: 'task', raw: { ...raw, createdAt: 43 } }), null);
});
