import test from 'node:test';
import assert from 'node:assert/strict';
import { phoneStartReceipt, phoneStartFailure, phoneRunIdentity } from '../src/phone-start.ts';

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
