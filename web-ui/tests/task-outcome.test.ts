import test from 'node:test';
import assert from 'node:assert/strict';
import { getTaskOutcome, taskMessageAnchors } from '../src/task-outcome.ts';

test('the reviewed evidence wins over incidental events and marks truncated source content', () => {
  const task = { id: 't', summary: '状态摘要', raw: { runId: 'r', coordinatorEpoch: 1, resultReviewDigest: 'host-owned',
    evidence: { nativeFinalAnswer: {format: 'pocketworld.runner-final-answer.v1', taskId: 't', runId: 'r', coordinatorEpoch: 1, inputBoundaryEmpty: true}, modelSummary: '这一版真实成果', modelSummaryTruncated: true, imageDataUrl: 'data:image/png;base64,YQ==' },
    events: [{ type: 'result', runId: 'r', at: 1, message: '较早的过程事件' }] } };
  const outcome = getTaskOutcome(task);
  assert.equal(outcome.source, 'evidence'); assert.equal(outcome.text, '这一版真实成果');
  assert.equal(outcome.partial, true); assert.equal(outcome.image, 'data:image/png;base64,YQ==');
  assert.equal(getTaskOutcome({ ...task, raw: { ...task.raw, evidence: { imageDataUrl: 'https://untrusted.example/image.png' } } }).image, undefined);
});

test('result evidence retains full current-run content and original page ordering semantics', () => {
  const long = '成果正文。'.repeat(200);
  const task = { id: 't', summary: '摘要', raw: { runId: 'new', historyEventPage: { taskId: 't', section: 'events', entries: [
    { position: 5, status: 'available', event: { type: 'user_verification', runId: 'new', message: '验收通过' } },
    { position: 3, status: 'available', event: { type: 'result', runId: 'new', message: '较早结果' } },
    { position: 4, status: 'available', event: { type: 'result', runId: 'new', message: long } },
    { position: 8, status: 'read_limited', event: { type: 'result', runId: 'new', message: '不允许读取' } },
    { position: 9, status: 'available', event: { type: 'result', runId: 'old', message: '旧运行结果' } },
  ] } } };
  const outcome = getTaskOutcome(task);
  assert.equal(outcome.text, long);
  assert.equal(outcome.source, 'result-event');
  assert.equal(outcome.partial, true);
});

test('resuming a task cannot promote retained evidence from the previous attempt', () => {
  for (const evidence of [
    { modelSummary: '旧结果', imageDataUrl: 'data:image/png;base64,YQ==' },
    { nativeFinalAnswer: {runId: 'old'}, modelSummary: '旧结果' },
  ]) {
    const task = { id: 't', status: 'running', summary: '正在继续处理', raw: { runId: 'new', evidence } };
    const loading = getTaskOutcome(task);
    assert.equal(loading.source, 'task-summary'); assert.equal(loading.text, '正在继续处理');
    assert.equal(loading.image, undefined); assert.equal(loading.retained?.text, '旧结果');
    const updated = getTaskOutcome({...task, raw: {...task.raw, events: [{ type: 'result', runId: 'new', at: 100, message: '本轮新结果' }]}});
    assert.equal(updated.text, '本轮新结果'); assert.equal(updated.source, 'result-event');
  }
});

test('submission-bound messages retain the same task entry as the original conversation UI', () => {
  const messages = [{id: 'm', role: 'assistant', content: '原任务答复', submission: {taskId: 't'}}];
  assert.equal(taskMessageAnchors(messages).get('t'), 0);
  assert.equal(getTaskOutcome({id: 't'}, messages).reply?.id, 'm');
});

test('foreign windows and missing run identity never manufacture an output', () => {
  for (const raw of [
    { runId: 'r', historyEventPage: { taskId: 'foreign', section: 'events', entries: [{ status: 'available', position: 1, event: { type: 'result', runId: 'r', message: 'foreign' } }] } },
    { events: [{ type: 'result', at: 1, message: 'unbound' }] },
    { runId: 'r', events: [{ type: 'result', runId: 'r', message: 'unordered' }] },
  ]) {
    const value = getTaskOutcome({ id: 't', raw });
    assert.equal(value.text, ''); assert.equal(value.source, 'none');
  }
});

test('a task-linked reply is labelled separately and is not promoted to current-run evidence', () => {
  const messages = [
    { id: 'a', role: 'assistant', taskId: 'other', content: '别的任务' },
    { id: 'b', role: 'assistant', taskId: 't', content: '关联的详细答复', at: 10, attachments: [{ id: 'file', name: 'result.md' }] },
    { id: 'c', role: 'user', taskId: 't', content: '我的后续问题' },
    { id: 'd', role: 'assistant', content: '无任务绑定的回复' },
  ];
  const value = getTaskOutcome({ id: 't', summary: '当前摘要', raw: { runId: 'current' } }, messages);
  assert.equal(value.source, 'task-summary'); assert.equal(value.text, '当前摘要');
  assert.equal(value.reply?.id, 'b'); assert.equal(value.reply?.text, '关联的详细答复');
  assert.deepEqual(value.reply?.attachments, [{ id: 'file', name: 'result.md' }]);
  assert.equal(taskMessageAnchors(messages).get('t'), 2);
});

test('identical reply and summary appear only once and empty evidence stays empty', () => {
  assert.equal(getTaskOutcome({ id: 't', summary: '结果' }, [{ id: 'a', taskId: 't', role: 'assistant', content: '结果' }]).reply, undefined);
  assert.deepEqual(getTaskOutcome({ id: 't' }), { text: '', source: 'none', reply: undefined, partial: false });
});

test('deduplicating a reply body preserves its actual attachments, including attachment-only replies', () => {
  for (const content of ['结果', '']) {
    const file = { id: 'attachment', name: 'result.md' };
    const value = getTaskOutcome({ id: 't', summary: '结果' }, [
      { id: 'a', taskId: 't', role: 'assistant', content, attachments: [file] },
    ]);
    assert.equal(value.reply?.id, 'a'); assert.equal(value.reply?.text, '');
    assert.deepEqual(value.reply?.attachments, [file]);
  }
});
