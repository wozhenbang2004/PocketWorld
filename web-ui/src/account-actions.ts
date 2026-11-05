type RecordValue = Record<string, any>;
export const ACCOUNT_RECEIPT_ACTIONS = new Set(['accounts.beginLogin', 'accounts.cancelLogin', 'accounts.select', 'accounts.refresh', 'accounts.retryPlanUsage', 'accounts.setEnabled', 'accounts.refreshAuthorization', 'accounts.reauthorize', 'accounts.acknowledgePlanWelcome']);

export function accountLoginArguments(label: string): { label: string } {
  const value = label.trim() || '我的 ChatGPT 账号';
  if (value.length > 80 || value.includes('\u0000')) throw new Error('请填写 1–80 个字符的账号名称。');
  return { label: value };
}

/** A bridge registration is not the final owner-request receipt, nor a completed login. */
export function accountActionReceipt(action: string, args: RecordValue, value: RecordValue): string {
  const command = action === 'accounts.refresh' ? 'snapshot' : action === 'accounts.refreshAuthorization' ? 'refresh' : action === 'accounts.reauthorize' ? 'beginLogin' : action.slice('accounts.'.length);
  const receipt = value?.receipt;
  const unknown = () => Object.assign(new Error('本次账号操作结果尚未确认。请先刷新账号状态；没有自动重试。'), { code: 'outcome_unknown' });
  if (value?.status !== 'completed' || value.action !== action || value.command !== command || !receipt || typeof receipt !== 'object' || Array.isArray(receipt)) throw unknown();
  if (command === 'beginLogin') {
    if (typeof receipt.sessionId !== 'string' || !receipt.sessionId || typeof receipt.accountId !== 'string' || !receipt.accountId || receipt.status !== 'pending' || (args.accountId && receipt.accountId !== args.accountId)) throw unknown();
    return '授权已准备。请在系统浏览器完成 ChatGPT 登录，返回后核对连接结果。';
  }
  if (command === 'cancelLogin') {
    if (receipt.sessionId !== args.sessionId || receipt.status !== 'cancelled') throw unknown();
    return '已取消这次授权；账号记录保留。';
  }
  if (action === 'accounts.refresh') return '已重新读取账号状态。';
  return '已收到本次账号操作回执，当前状态见账号记录。';
}

type LoginRecovery = { kind: 'none' | 'new' | 'disabled' | 'unavailable' } | { kind: 'existing'; accountId: string; expectedVersion: number };

export function accountStatusText(value: unknown): string {
  const labels: Record<string, string> = { pending: '等待浏览器授权', exchanging: '正在完成授权', complete: '授权已完成', expired: '授权已过期',
    cancelled: '已取消授权', interrupted: '授权已中断', failed: '授权未完成', authorizing: '等待授权',
    refreshing: '正在刷新授权', signed_out: '已退出', reauthorization_required: '需要重新登录',
    connected: '已连接', disconnected: '未连接', cooldown: '暂时冷却' };
  return typeof value === 'string' && Object.hasOwn(labels, value) ? labels[value] : '状态待核对';
}

/** The native snapshot may remove an unregistered account after expiry. Never reuse its old session. */
export function accountLoginRecovery(login: RecordValue, accounts: unknown): LoginRecovery {
  if (!login.sessionId || login.status !== 'expired') return { kind: 'none' };
  if (typeof login.accountId !== 'string' || !login.accountId.trim() || !Array.isArray(accounts)) return { kind: 'unavailable' };
  const account = accounts.find(row => row && row.id === login.accountId);
  if (!account) return { kind: 'new' };
  if (account.enabled === false) return { kind: 'disabled' };
  if (account.enabled !== true || !Number.isSafeInteger(account.version) || account.version < 0) return { kind: 'unavailable' };
  return { kind: 'existing', accountId: account.id, expectedVersion: account.version };
}

export function accountActionFailure(failure: unknown): { message: string; needsAccountRead: boolean } {
  const message = failure instanceof Error ? failure.message : String(failure);
  const stale = message.includes('STALE_ACCOUNT_VERSION');
  return {
    message: stale ? '账号状态已变化。请先刷新账号状态，再重新登录；本次没有沿用旧授权。' : message,
    needsAccountRead: stale || (failure as { code?: string } | null)?.code !== 'native_failure',
  };
}
