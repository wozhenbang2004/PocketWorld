import test from 'node:test';
import assert from 'node:assert/strict';
import {ownerQuestionReceipt, ownerQuestionSavedMessage, submitOwnerQuestion,
  type OwnerQuestionView} from '../src/owner-task-question.ts';

const question: OwnerQuestionView = {questionKey: 'original-phone-hold', kind: 'phone',
  question: '选哪一项？', answered: false, options: [{id: 'first', label: '第一项'}, {id: 'second', label: '第二项'}]};
const saved = () => ({accepted: true, answerSaved: true, questionKey: question.questionKey, taskId: 'task-a',
  runId: 'run-a', coordinatorEpoch: 2, requestId: 'question-a', waitRevision: 3, messageId: 'saved-answer'});

test('text reply waits for the exact saved receipt and sends only answer data', async () => {
  let resolve!: (value: unknown) => void;
  let settled = false;
  const answer = {text: '保留这段原问题草稿'};
  const pending = submitOwnerQuestion(question, answer, (action, args) => {
    assert.equal(action, 'tasks.answerQuestion');
    assert.deepEqual(args, answer);
    assert.equal('questionIdentity' in args, false);
    return new Promise(done => { resolve = done; });
  });
  void pending.then(() => { settled = true; });
  await Promise.resolve();
  assert.equal(settled, false, 'dispatch is not a save receipt');
  resolve(saved());
  assert.equal((await pending).answerSaved, true);
  assert.equal(answer.text, '保留这段原问题草稿');
});

test('structured choice sends the offered ID, never a Web-provided label or resume', async () => {
  const calls: string[] = [];
  await submitOwnerQuestion(question, {optionId: 'second'}, async (action, args) => {
    calls.push(action); assert.deepEqual(args, {optionId: 'second'}); return saved();
  });
  assert.deepEqual(calls, ['tasks.answerQuestionChoice']);
});

test('bad choice, extra answer fields and answered questions never dispatch', async () => {
  let dispatched = 0;
  const command = async () => { dispatched++; return saved(); };
  await assert.rejects(submitOwnerQuestion(question, {optionId: 'missing'}, command));
  await assert.rejects(submitOwnerQuestion(question, {text: '  '}, command));
  await assert.rejects(submitOwnerQuestion(question, {text: 'x'.repeat(4001)}, command));
  await assert.rejects(submitOwnerQuestion(question, {optionId: 'first', text: 'other'} as any, command));
  await assert.rejects(submitOwnerQuestion({...question, answered: true}, {optionId: 'first'}, command));
  assert.equal(dispatched, 0);
});

test('acceptance alone, expired, missing or another wait receipt are not success', () => {
  for (const result of [undefined, {accepted: true}, {...saved(), answerSaved: false}, {...saved(), expired: true},
    {...saved(), questionKey: 'another-run-or-wait'}, {...saved(), messageId: ''}, {...saved(), waitRevision: 0},
    {...saved(), coordinatorEpoch: '2'}]) assert.throws(() => ownerQuestionReceipt(question, result));
});

test('failed or unknown save keeps the answer data and never retries automatically', async () => {
  const answer = {text: '失败后仍保留的回答'};
  let calls = 0;
  await assert.rejects(submitOwnerQuestion(question, answer, async () => { calls++; return {accepted: true}; }));
  assert.equal(calls, 1);
  assert.equal(answer.text, '失败后仍保留的回答');
});

test('saved replies remain saved when continuation is blocked or already completed', () => {
  for (const continuationState of ['saved_scope_expired', 'saved_budget_exhausted', 'saved_execution_unavailable',
    'saved_owner_action_required', 'already_saved']) {
    const receipt = ownerQuestionReceipt(question, {...saved(), continuationState, duplicate: continuationState === 'already_saved'});
    assert.equal(receipt.answerSaved, true);
    assert.match(ownerQuestionSavedMessage(question, receipt), /已保存|已保留/);
  }
  assert.match(ownerQuestionSavedMessage(question, ownerQuestionReceipt(question, {...saved(), continuationState: 'saved_budget_exhausted'})), /预算不足/);
  assert.match(ownerQuestionSavedMessage(question, ownerQuestionReceipt(question, {...saved(), continuationState: 'saved_scope_expired'})), /授权已到期/);
  assert.match(ownerQuestionSavedMessage(question), /原手机任务/);
  assert.match(ownerQuestionSavedMessage({...question, kind: 'capabilities'}), /原对话/);
});
