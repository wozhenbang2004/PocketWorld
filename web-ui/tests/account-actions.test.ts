import test from 'node:test';
import assert from 'node:assert/strict';
import { accountActionReceipt, accountLoginArguments } from '../src/account-actions.ts';

test('new account supplies the native default label without credentials or another account identity', () => {
  assert.deepEqual(accountLoginArguments('  '), { label: '我的 ChatGPT 账号' });
  assert.deepEqual(accountLoginArguments(' 工作账号 '), { label: '工作账号' });
  assert.throws(() => accountLoginArguments('x'.repeat(81)));
  assert.throws(() => accountLoginArguments('name\u0000other'));
});
test('registration alone and an unrelated final receipt never claim login preparation', () => {
  const args = accountLoginArguments('');
  assert.throws(() => accountActionReceipt('accounts.beginLogin', args, { accepted: true }), { code: 'outcome_unknown' });
  const result = { status: 'completed', action: 'accounts.beginLogin', command: 'beginLogin', receipt: { sessionId: 'original-session', accountId: 'original-account', status: 'pending' } };
  assert.match(accountActionReceipt('accounts.beginLogin', args, result), /授权已准备/);
  assert.throws(() => accountActionReceipt('accounts.reauthorize', { accountId: 'other-account' }, { ...result, action: 'accounts.reauthorize' }));
  assert.throws(() => accountActionReceipt('accounts.beginLogin', args, { ...result, receipt: { ...result.receipt, status: 'connected' } }));
});
test('a cancellation receipt belongs only to the session actually cancelled', () => {
  const result = { status: 'completed', action: 'accounts.cancelLogin', command: 'cancelLogin', receipt: { sessionId: 'old-session', status: 'cancelled' } };
  assert.match(accountActionReceipt('accounts.cancelLogin', { sessionId: 'old-session' }, result), /已取消/);
  assert.throws(() => accountActionReceipt('accounts.cancelLogin', { sessionId: 'new-session' }, result));
});
