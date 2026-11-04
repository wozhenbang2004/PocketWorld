import test from 'node:test';
import assert from 'node:assert/strict';
import { teamMessageConfirmed, teamMessageDelivery, teamMessageRecorded } from '../src/team-message.ts';

test('native queued receipt confirms saving without claiming model consumption', () => {
  const original = { id: 'message-a', memberId: 'worker-a' };
  const receipt = { command: 'sendMessage', confirmed: true, messageId: original.id,
    result: { messageReceipt: { messageId: original.id, recipientMemberId: original.memberId, status: 'queued', executionAuthorized: false } } };
  assert.equal(teamMessageConfirmed(receipt, original), true);
  assert.equal(teamMessageConfirmed({ ...receipt, confirmed: 'true' }, original), false);
  assert.equal(teamMessageConfirmed(receipt, { ...original, memberId: 'worker-b' }), false);
  assert.equal(teamMessageConfirmed(receipt, { ...original, id: 'another-send' }), false);
  assert.equal(teamMessageConfirmed({ confirmed: true, status: 'accepted' }, original), false);
  assert.equal(teamMessageDelivery({ inbox: [{ id: original.id, status: 'pending' }] }, { deliveryId: original.id }), '已定向排队，尚未进入模型');
});
test('assignment and mailbox delivery retain their original states', () => {
  const run = { workPlan: { items: [{ assignmentTask: { messages: [{ messageId: 'm', state: 'included' }] } }] }, inbox: [{ id: 'm', status: 'consumed' }] };
  assert.equal(teamMessageDelivery(run, { deliveryId: 'm' }), '已选入原输入，尚未确认消费');
  assert.equal(teamMessageDelivery(run, { kind: 'assignment_question', state: 'answered_historical' }), '回答仅保留历史，没有唤醒旧任务');
  assert.equal(teamMessageDelivery({}, { deliveryId: 'not-read' }), '');
});
test('question acknowledgement must bind both original question and answer', () => {
  const original = { id: 'answer-a', memberId: 'worker-a', questionId: 'question-a' };
  const result = { command: 'answerAssignment', confirmed: true, messageId: 'answer-a', result: { answerId: 'answer-a', questionId: 'question-a', continuationState: 'waiting' } };
  assert.equal(teamMessageConfirmed(result, original), true);
  assert.equal(teamMessageConfirmed(result, { ...original, questionId: 'question-b' }), false);
  assert.equal(teamMessageConfirmed({ ...result, command: 'sendMessage' }, original), false);
});
test('unknown delivery is resolved only by the exact original sender and recipient', () => {
  const original = { id: 'm', memberId: 'worker-a' };
  assert.equal(teamMessageRecorded({ run: { inbox: [{ id: 'm', targetMemberId: 'worker-a' }] } }, original), true);
  assert.equal(teamMessageRecorded({ run: {}, messages: [{ messageId: 'm', recipientMemberId: 'worker-a', role: 'user' }] }, original), true);
  assert.equal(teamMessageRecorded({ run: {}, messages: [{ messageId: 'm', recipientMemberId: 'worker-a', role: 'parent_model' }] }, original), false);
  assert.equal(teamMessageRecorded({ run: { inbox: [{ id: 'm', targetMemberId: 'worker-b' }] } }, original), false);
  assert.equal(teamMessageRecorded({ run: {}, messages: [] }, original), false);
});
