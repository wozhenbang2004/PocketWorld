import test from 'node:test';
import assert from 'node:assert/strict';
import { accountLoginRecovery, accountStatusText, accountActionFailure } from '../src/account-actions.ts';

const login = { sessionId: 'expired-session', accountId: 'original-account', status: 'expired' };
const original = { id: 'original-account', version: 7, enabled: true, status: 'reauthorization_required' };
test('expired authorization uses readable Chinese status for every native account/login state', () => {
  const expected = { pending: '等待浏览器授权', exchanging: '正在完成授权', complete: '授权已完成', expired: '授权已过期', cancelled: '已取消授权', interrupted: '授权已中断', failed: '授权未完成', authorizing: '等待授权', refreshing: '正在刷新授权', signed_out: '已退出', reauthorization_required: '需要重新登录', connected: '已连接', cooldown: '暂时冷却' };
  for (const [status, label] of Object.entries(expected)) assert.equal(accountStatusText(status), label);
  for (const status of ['unexpected_status', '__proto__', 'constructor', null]) assert.equal(accountStatusText(status), '状态待核对');
});
test('expired registered login reuses only the matching current identity and version', () => {
  assert.deepEqual(accountLoginRecovery(login, [{ ...original, id: 'other-account' }, original]), { kind: 'existing', accountId: 'original-account', expectedVersion: 7 });
  assert.deepEqual(accountLoginRecovery(login, [{ ...original, version: 9 }]), { kind: 'existing', accountId: 'original-account', expectedVersion: 9 });
});
test('removed unregistered login opens the existing new-account flow, never invents a version', () => {
  assert.deepEqual(accountLoginRecovery(login, []), { kind: 'new' });
  assert.deepEqual(accountLoginRecovery(login, [{ ...original, id: 'unrelated-account' }]), { kind: 'new' });
  for (const accounts of [undefined, null, {}]) assert.deepEqual(accountLoginRecovery(login, accounts), { kind: 'unavailable' });
});
test('disabled or invalid current identity cannot create a replacement or bypass native fences', () => {
  assert.deepEqual(accountLoginRecovery(login, [{ ...original, enabled: false }]), { kind: 'disabled' });
  for (const version of [undefined, -1, 2.5, '7', NaN, Number.MAX_SAFE_INTEGER + 1]) assert.deepEqual(accountLoginRecovery(login, [{ ...original, version }]), { kind: 'unavailable' });
  assert.deepEqual(accountLoginRecovery({ ...login, accountId: '' }, []), { kind: 'unavailable' });
});
test('active, complete and unrelated terminal states are not silently retried', () => {
  for (const status of ['pending', 'exchanging', 'complete', 'cancelled', 'failed', 'interrupted', 'unknown']) assert.deepEqual(accountLoginRecovery({ ...login, status }, [original]), { kind: 'none' });
  assert.deepEqual(accountLoginRecovery({ ...login, sessionId: '' }, [original]), { kind: 'none' });
});

test('stale native version and uncertain receipts require a fresh read, never automatic retry', () => {
  const stale = Object.assign(new Error('STALE_ACCOUNT_VERSION'), { code: 'native_failure' });
  assert.deepEqual(accountActionFailure(stale), { message: '账号状态已变化。请先刷新账号状态，再重新登录；本次没有沿用旧授权。', needsAccountRead: true });
  assert.equal(accountActionFailure(Object.assign(new Error('result unknown'), { code: 'outcome_unknown' })).needsAccountRead, true);
  assert.equal(accountActionFailure(Object.assign(new Error('explicit rejection'), { code: 'native_failure' })).needsAccountRead, false);
  const frozenRequest = accountLoginRecovery(login, [original]);
  const currentRequest = accountLoginRecovery(login, [{ ...original, version: 12 }]);
  assert.deepEqual(frozenRequest, { kind: 'existing', accountId: original.id, expectedVersion: 7 });
  assert.deepEqual(currentRequest, { kind: 'existing', accountId: original.id, expectedVersion: 12 });
});
