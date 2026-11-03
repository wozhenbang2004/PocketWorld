import test from 'node:test';
import assert from 'node:assert/strict';
import { chatCancelFeedback, chatCancelIdentity, chatCancelKey, sameChatCancelIdentity } from '../src/chat-cancel.ts';

function state(c: string, task: string, run: string, epoch: number) {
  return { chatBusy: true, conversationId: c, chatActiveTurn: { conversationId: c, taskId: task, runId: run, epoch } };
}

test('late success and error only render for the original current conversation and run', () => {
  const a = state('A', 'task-A', 'run-A', 1);
  const expected = chatCancelIdentity(a)!;
  let visible = '';
  const deliver = (current: Record<string, any>, text: string) => {
    if (sameChatCancelIdentity(current, expected)) visible = text;
  };
  deliver(state('B', 'task-B', 'run-B', 1), 'late success'); assert.equal(visible, '');
  deliver(state('B', 'task-B', 'run-B', 1), 'late error'); assert.equal(visible, '');
  deliver(state('A', 'task-A', 'run-A2', 2), 'new run'); assert.equal(visible, '');
  deliver({ ...a, chatBusy: false }, 'finished'); assert.equal(visible, '');
  deliver(a, 'matching receipt'); assert.equal(visible, 'matching receipt');
});

test('missing identity never manufactures a stop target', () => {
  assert.equal(chatCancelIdentity({ chatBusy: true, conversationId: 'A' }), null);
  assert.equal(chatCancelIdentity(state('A', 'task-A', 'run-A', 0)), null);
  assert.equal(chatCancelIdentity({ ...state('A', 'task-A', 'run-A', 1), conversationId: 'B' }), null);
});

test('an already visible A result disappears on B and distinct-run pending stays separate', () => {
  const a = state('A', 'task-A', 'run-A', 1), b = state('B', 'task-B', 'run-B', 1);
  const aKey = chatCancelKey(chatCancelIdentity(a)!), bKey = chatCancelKey(chatCancelIdentity(b)!);
  const feedback = { [aKey]: 'A stop requested' };
  assert.equal(chatCancelFeedback(a, feedback), 'A stop requested');
  assert.equal(chatCancelFeedback(b, feedback), undefined);
  const pending = new Set([aKey]); assert.equal(pending.has(aKey), true); assert.equal(pending.has(bKey), false);
});
