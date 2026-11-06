import assert from 'node:assert/strict';
import test from 'node:test';
import { probeObservations, runtimeAttempts, skillMatches } from '../src/shell-library/affect-workflow-data.ts';

test('skill search uses actual metadata and every query word', () => {
  const skill = { name: '知识整理', description: '整理文档', source: 'Owner repo', requiredCapabilities: ['read_file'] };
  assert.equal(skillMatches(skill, '  REPO read_file  '), true);
  assert.equal(skillMatches(skill, 'repo unknown'), false);
  assert.equal(skillMatches(skill, '   '), true);
});

test('probe rows preserve unknown/not-run/not-requested instead of reporting success', () => {
  const rows = probeObservations({ text: 'passed', tools: 'unconfirmed', image: 'not_requested', diagnostics: [{ stage: 'tools', reason: 'SYNTHETIC_REPLY_UNCONFIRMED', message: '未返回测试函数', httpStatus: 0 }, { stage: 'other', message: '不是工具观察' }] });
  assert.deepEqual(rows.map(row => row.label), ['本次通过', '未证实', '未选择']);
  assert.equal(rows[1].diagnostics.length, 1);
  assert.equal(rows[0].diagnostics.length, 0);
  assert.equal(probeObservations({ text: 'not_run', tools: 'something_new' })[0].label, '未执行');
  assert.equal(probeObservations({ text: 'not_run', tools: 'something_new' })[1].label, '尚无观察');
});

test('native start/settle events become one operation only with exact session/operation/stage identities', () => {
  const rows = runtimeAttempts([
    { sessionId: 's1', operationId: 'o1', stage: 'model_request', outcome: 'started', time: 1, durationMs: 0 },
    { sessionId: 's1', operationId: 'o1', stage: 'model_request', outcome: 'failed', code: 'TIMEOUT', time: 4, durationMs: 3000 },
    { sessionId: 's1', operationId: 'o1', stage: 'tool_execution', outcome: 'started', time: 5 },
    { sessionId: 's2', operationId: 'o1', stage: 'model_request', outcome: 'succeeded', time: 6 },
    { stage: 'model_request', outcome: 'started', time: 7 },
    { stage: 'model_request', outcome: 'failed', time: 8 },
  ]);
  assert.equal(rows.length, 5);
  assert.equal(rows[0].events.length, 2);
  assert.equal(rows[0].outcome, 'failed');
  assert.equal(rows[0].durationMs, 3000);
  assert.equal(rows[1].stage, 'tool_execution');
  assert.equal(rows[2].sessionId, 's2');
  assert.equal(rows.some(row => 'fallback' in row || 'cost' in row || 'provider' in row), false);
});

test('out-of-order observations retain latest actual result; malformed evidence does not become zero duration', () => {
  const rows = runtimeAttempts([null, 'x', { sessionId: 's1', operationId: 'o1', stage: 'model_request', time: 20, outcome: 'unconfirmed', durationMs: '12' }, { sessionId: 's1', operationId: 'o1', stage: 'model_request', time: 10, outcome: 'started', durationMs: 0 }]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].outcome, 'unconfirmed');
  assert.equal(rows[0].durationMs, null);
  assert.equal(rows[0].events.length, 2);
  assert.deepEqual(runtimeAttempts({ events: [] }), []);
});
