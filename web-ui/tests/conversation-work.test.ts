import test from 'node:test';
import assert from 'node:assert/strict';
import { backgroundConversationWork, foregroundConversationTurn, messageReplyContext, messageTaskResult, ownerWorkIdentity } from '../src/conversation-work.ts';

const work = (id = 'work-a', status = 'running') => ({ id, goal: '整理明天的安排', status, updatedAt: 42, raw: {
  id, actorId: 'owner', spaceId: 'private', conversationId: 'conversation', executionRole: 'owner_work', runId: 'work-run', coordinatorEpoch: 2,
  controls: { canStop: true, canInput: true, canContinue: false },
} });
const reply = (kind = 'owner_work') => ({ role: 'assistant', taskId: 'work-a', content: '当时核对的结果',
  replyContext: { taskId: 'work-a', runId: 'old-run', epoch: 1, deliveryId: kind === 'owner_work' ? 'b3f065b7-e049-4192-b798-39d01a8b69c7' : `${kind}:work-a:old-run:1`, replyToMessageIds: ['request-a'] },
  taskResult: { kind, taskId: 'work-a', runId: 'old-run', epoch: 1, deliveryId: kind === 'owner_work' ? 'b3f065b7-e049-4192-b798-39d01a8b69c7' : `${kind}:work-a:old-run:1`, status: 'failed', endedAt: 1793764860000, summaryChars: 8, summaryTruncated: false },
});

test('background work never supplies the foreground typing or stop identity', () => {
  const task = work(), background = { conversationId: 'conversation', taskId: task.id, runId: 'work-run', epoch: 2 };
  assert.equal(foregroundConversationTurn({ conversationId: 'conversation', tasks: [task], chatActiveTurn: background, chatBusy: true }), null);
  assert.equal(foregroundConversationTurn({ conversationId: 'conversation', tasks: [task], foregroundTurn: null, chatActiveTurn: background }), null);
  const foreground = { conversationId: 'conversation', taskId: 'reply-task', runId: 'reply-run', epoch: 1, executionRole: 'conversation_turn' };
  assert.deepEqual(foregroundConversationTurn({ conversationId: 'conversation', tasks: [task], foregroundTurn: foreground }), foreground);
  assert.equal(foregroundConversationTurn({ conversationId: 'other', foregroundTurn: foreground }), null);
});

test('multiple original work items survive a bounded task page without importing another conversation', () => {
  const first = work(), second = work('work-b'), foreign = work('foreign'); foreign.raw.conversationId = 'other';
  const done = work('ended', 'completed');
  assert.deepEqual(backgroundConversationWork([first], [first, second, foreign, done], 'conversation').map(task => task.id), ['work-a', 'work-b']);
  const group = work('group'); group.raw.spaceId = 'group';
  assert.deepEqual(backgroundConversationWork([group], [], 'conversation'), []);
  const interrupted = work('interrupted', 'interrupted'); interrupted.raw.controls.canContinue = true;
  assert.deepEqual(backgroundConversationWork([], [interrupted], 'conversation').map(task => task.id), ['interrupted']);
  interrupted.raw.controls.canContinue = false;
  assert.deepEqual(backgroundConversationWork([], [interrupted], 'conversation'), []);
});

test('old delivery status and original request stay frozen while the current task has a later run', () => {
  for (const kind of ['owner_work', 'automation', 'team']) {
    const message = reply(kind), frozen = messageTaskResult(message);
    assert.equal(frozen?.status, 'failed'); assert.equal(frozen?.runId, 'old-run');
    assert.deepEqual(messageReplyContext(message)?.replyToMessageIds, ['request-a']);
    const current = work(); current.raw.runId = 'later-run'; current.raw.coordinatorEpoch = 9;
    assert.equal(messageTaskResult(message)?.epoch, 1);
    assert.deepEqual(messageTaskResult(message), frozen);
  }
});

test('conflicting delivery identities cannot be presented as a verified outcome', () => {
  const message = reply();
  const oldId = 'owner_work:work-a:old-run:1';
  assert.equal(messageTaskResult({ ...message, replyContext: { ...message.replyContext, deliveryId: oldId }, taskResult: { ...message.taskResult, deliveryId: oldId } }), null);
  assert.equal(messageTaskResult({ ...message, role: 'user' }), null);
  assert.equal(messageTaskResult({ ...message, taskResult: { ...message.taskResult, runId: 'another-run' } }), null);
  assert.equal(messageTaskResult({ ...message, taskResult: { ...message.taskResult, status: 'running' } }), null);
  assert.equal(messageTaskResult({ ...message, taskResult: { ...message.taskResult, epoch: '1' } }), null);
  assert.equal(messageReplyContext({ ...message, replyContext: { ...message.replyContext, replyToMessageIds: [{}] } }), null);
  assert.equal(messageTaskResult({ role: 'assistant', content: '普通答复', taskId: 'work-a' }), null);
});

test('queued cancellation preserves the actual empty run and zero epoch; later controls keep the full tuple', () => {
  const task = work(); assert.deepEqual(ownerWorkIdentity(task), { taskId: 'work-a', runId: 'work-run', epoch: 2 });
  const queued = work('queued', 'queued'); queued.raw.runId = ''; queued.raw.coordinatorEpoch = 0; queued.raw.controls.canInput = false;
  assert.deepEqual(ownerWorkIdentity(queued), { taskId: 'queued', runId: '', epoch: 0 });
  queued.status = 'running'; assert.equal(ownerWorkIdentity(queued), null);
  assert.equal(ownerWorkIdentity({ ...task, raw: { ...task.raw, executionRole: 'conversation_turn' } }), null);
});
