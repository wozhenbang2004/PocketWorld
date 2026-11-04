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
