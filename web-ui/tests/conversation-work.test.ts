import test from 'node:test';
import assert from 'node:assert/strict';
import { backgroundConversationWork, conversationActivityLabel, conversationTaskCompact, conversationWorkActionLabel, conversationWorkNeedsAttention, foregroundConversationTurn, messageReplyContext, messageTaskResult, ownerWorkIdentity } from '../src/conversation-work.ts';

const work = (id = 'work-a', status = 'running') => ({ id, goal: '整理明天的安排', status, updatedAt: 42, raw: {
  id, actorId: 'owner', spaceId: 'private', conversationId: 'conversation', executionRole: 'owner_work', runId: 'work-run', coordinatorEpoch: 2,
  controls: { canStop: true, canInput: true, canContinue: false },
} });
const reply = (kind = 'owner_work') => ({ role: 'assistant', taskId: 'work-a', content: '当时核对的结果',
  replyContext: { taskId: 'work-a', runId: 'old-run', epoch: 1, deliveryId: kind === 'owner_work' ? 'b3f065b7-e049-4192-b798-39d01a8b69c7' : `${kind}:work-a:old-run:1`, replyToMessageIds: ['request-a'] },
  taskResult: { kind, taskId: 'work-a', runId: 'old-run', epoch: 1, deliveryId: kind === 'owner_work' ? 'b3f065b7-e049-4192-b798-39d01a8b69c7' : `${kind}:work-a:old-run:1`, status: 'failed', endedAt: 1793764860000, summaryChars: 8, summaryTruncated: false },
});

test('the conversation activity never borrows an older run or another conversation', () => {
  const foreground = { conversationId: 'conversation', taskId: 'reply-task', runId: 'reply-run', epoch: 2, executionRole: 'conversation_turn' };
  const task = { id: 'reply-task', status: 'running', raw: { id: 'reply-task', runId: 'reply-run', coordinatorEpoch: 2,
    events: [{ runId: 'reply-run', type: 'tool', status: 'completed', message: '已读取这次选择的文件', at: 42 }] } };
  const state = { conversationId: 'conversation', foregroundTurn: foreground, tasks: [task] };
  assert.equal(conversationActivityLabel(state), '最近进展 · 已读取这次选择的文件');
  assert.equal(conversationActivityLabel({ ...state, conversationId: 'elsewhere' }), '');
  assert.equal(conversationActivityLabel({ ...state, tasks: [{ ...task, raw: { ...task.raw, runId: 'old-run' } }] }), '正在处理这次回复');
  assert.equal(conversationActivityLabel({ ...state, tasks: [{ ...task, raw: { ...task.raw, coordinatorEpoch: 1 } }] }), '正在处理这次回复');
  assert.equal(conversationActivityLabel({ ...state, tasks: [] }), '正在处理这次回复');
});

test('foreground queue and stop feedback take precedence over recent records', () => {
  const turn = { conversationId: 'conversation', taskId: 'reply-task', runId: 'reply-run', epoch: 1 };
  for (const [resourceState, expected] of [['queued', '等待执行'], ['retained', '等待补充'], ['stopping', '正在停止']]) {
    assert.equal(conversationActivityLabel({ conversationId: 'conversation', foregroundTurn: { ...turn, resourceState } }), expected);
  }
});

test('activity summaries retain unresolved, failed and waiting event states', () => {
  const turn = { conversationId: 'conversation', taskId: 'reply-task', runId: 'reply-run', epoch: 2 };
  for (const [status, label] of [['unknown', '结果待核对'], ['failed', '该记录返回错误'], ['awaiting_user', '需要你处理'], ['paused', '已暂停'], ['stopped', '已停止']]) {
    const task = { id: 'reply-task', status: 'running', raw: { runId: 'reply-run', coordinatorEpoch: 2,
      events: [{ runId: 'reply-run', type: 'tool', status, message: '请求已发送给外部服务', at: 42 }] } };
    assert.equal(conversationActivityLabel({ conversationId: 'conversation', foregroundTurn: turn, tasks: [task] }), `${label} · 请求已发送给外部服务`);
  }
});

test('only settled success becomes a compact conversation summary', () => {
  assert.equal(conversationTaskCompact(work('done', 'completed')), true);
  for (const status of ['running', 'failed', 'stopped', 'interrupted', 'waiting_approval', 'unknown']) {
    assert.equal(conversationTaskCompact(work('task', status)), false, status);
  }
  const done = work('unconfirmed', 'completed');
  assert.equal(conversationTaskCompact({ ...done, raw: { ...done.raw, historyHasUnconfirmedOperations: true } }), false);
  assert.equal(conversationTaskCompact({ ...done, raw: { ...done.raw, hasUnresolvedOperations: true } }), false);
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

test('older attention and resumable work stay ahead of newer activity in the two-row summary', () => {
  const running = { ...work('running'), updatedAt: 900 }, queued = { ...work('queued', 'queued'), updatedAt: 800 };
  const waiting = { ...work('waiting', 'awaiting_user'), updatedAt: 700 }, paused = { ...work('paused', 'paused'), updatedAt: 600 };
  const failed = { ...work('failed', 'failed'), updatedAt: 500 }; failed.raw.controls.canContinue = true;
  const unknown = { ...work('unknown'), updatedAt: 400, raw: { ...work('unknown').raw, historyHasUnconfirmedOperations: true } };
  const tasks = [running, queued, failed, paused, unknown, waiting], originalOrder = [...tasks];
  const selected = backgroundConversationWork(tasks, [], 'conversation');
  assert.deepEqual(selected.map(task => task.id), ['waiting', 'paused', 'failed', 'unknown', 'running', 'queued']);
  assert.deepEqual(selected.slice(0, 2), [waiting, paused]);
  assert.equal(selected[2], failed, 'sorting must retain the original task object and its control identity');
  assert.deepEqual(tasks, originalOrder, 'presentation ordering must not mutate the authoritative task list');
});

test('work summary priority uses native attention and the original resumable failure control', () => {
  for (const status of ['needs_confirmation', 'awaiting_user', 'waiting_user', 'waiting_approval', 'awaiting_approval', 'waiting_permission', 'paused', 'interrupted', 'unknown']) {
    assert.equal(conversationWorkNeedsAttention(work('task', status)), true, status);
  }
  for (const status of ['running', 'queued', 'completed', 'failed']) assert.equal(conversationWorkNeedsAttention(work('task', status)), false, status);
  const failed = work('failed', 'failed'); failed.raw.controls.canContinue = true;
  assert.equal(conversationWorkNeedsAttention(failed), true);
  assert.equal(conversationWorkNeedsAttention({ ...failed, raw: { ...failed.raw, executionRole: 'conversation_turn' } }), false);
  const running = work();
  assert.equal(conversationWorkNeedsAttention({ ...running, raw: { ...running.raw, approvals: [{ state: 'pending' }] } }), true);
});

test('work row actions distinguish waiting, paused and unresolved states without promising a replay', () => {
  for (const [status, action] of [['needs_confirmation', '核对结果'], ['waiting_approval', '处理授权'], ['awaiting_user', '补充信息'], ['paused', '继续处理'], ['interrupted', '继续处理'], ['failed', '查看原因'], ['queued', '查看排队'], ['running', '查看进展'], ['unknown', '核对操作']]) {
    assert.equal(conversationWorkActionLabel(work('task', status)), action, status);
  }
  const running = work();
  assert.equal(conversationWorkActionLabel({ ...running, raw: { ...running.raw, hasUnresolvedOperations: true } }), '核对操作');
  assert.equal(conversationWorkActionLabel({ ...running, raw: { ...running.raw, approvals: [{ state: 'pending' }] } }), '查看待办');
  const failed = work('failed', 'failed'); failed.raw.controls.canContinue = true;
  assert.equal(conversationWorkActionLabel(failed), '查看原因');
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
