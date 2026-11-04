import test from 'node:test';
import assert from 'node:assert/strict';
import {questionBinding} from '../src/question-binding.ts';

test('answer draft follows the original question, not unrelated task updates', () => {
  const task = {updatedAt: 1, raw: {runId: 'r1', coordinatorEpoch: 2, evidence: {ownerQuestion: {requestId: 'q1', waitRevision: 3}}}};
  const original = questionBinding('t1', task);
  assert.equal(questionBinding('t1', {...task, updatedAt: 99}), original);
  assert.notEqual(questionBinding('t2', task), original);
  assert.notEqual(questionBinding('t1', {...task, raw: {...task.raw, runId: 'r2'}}), original);
  assert.notEqual(questionBinding('t1', {...task, raw: {...task.raw, coordinatorEpoch: 3}}), original);
  assert.notEqual(questionBinding('t1', {...task, raw: {...task.raw, evidence: {ownerQuestion: {requestId: 'q2', waitRevision: 3}}}}), original);
  assert.notEqual(questionBinding('t1', {...task, raw: {...task.raw, evidence: {ownerQuestion: {requestId: 'q1', waitRevision: 4}}}}), original);
  const left = {raw: {runId: 'a:b', coordinatorEpoch: 2, evidence: {ownerQuestion: {requestId: 'q', waitRevision: 3}}}};
  const right = {raw: {runId: 'b', coordinatorEpoch: 2, evidence: {ownerQuestion: {requestId: 'q', waitRevision: 3}}}};
  assert.notEqual(questionBinding('t', left), questionBinding('t:a', right), 'separators inside fields cannot alias another identity');
});
