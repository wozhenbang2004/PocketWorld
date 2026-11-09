type Row = Record<string, any>;
const row = (value: any): Row => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const revision = (value: any): value is number => Number.isSafeInteger(value) && value >= 0;
const identity = (value: any): value is string => typeof value === 'string' && value.trim().length > 0 && !/[\u0000-\u001f]/.test(value);
export function conversationModelName(state: Row): string {
  const preference = row(state.conversationModel), target = row(preference.target);
  if (target.kind === 'api') return String(target.modelId || '');
  if (target.kind === 'account') return String(row(target.nativeRef).modelId || '');
  if (target.kind === 'group') return String(preference.modelName || '模型组');
  return String(state.modelName || '');
}
export function conversationSelectionRequest(target: { kind: string; args: Row; selectionRevision: number }, state: Row) {
  const preference = row(state.conversationModel), args = row(target.args);
  if (!identity(preference.scopeKey) || !revision(preference.revision) || !revision(target.selectionRevision))
    throw new Error('原对话模型范围尚未读取，请重新打开列表。');
  if (target.selectionRevision !== state.modelSelectionRevision) throw new Error('模型来源已变化，请重新读取。');
  const action = ({ api: 'models.conversation.api.select', account: 'models.conversation.accounts.select', group: 'models.conversation.group' } as Row)[target.kind];
  if (!action) throw new Error('这类模型尚未接通此对话。');
  if (target.kind === 'group' ? !identity(args.groupId) || !revision(args.expectedRevision) || args.expectedRevision < 1 : !identity(args.windowId) || !identity(args.reviewHandle))
    throw new Error('原模型审核或模型组修订尚未读取，请重新打开列表。');
  return { action: action as string, args: { ...args, expectedPreferenceRevision: preference.revision,
    ...(target.kind === 'group' ? { expectedSelectionRevision: target.selectionRevision } : {}) }, scopeKey: preference.scopeKey, previousRevision: preference.revision };
}
export function conversationSelectionReceipt(value: any, scopeKey: string, previousRevision: number): Row {
  const receipt = row(value), preference = row(receipt.preference);
  if (!identity(scopeKey) || !revision(previousRevision) || !revision(previousRevision + 1) || receipt.status !== 'saved' || receipt.scope !== 'conversation' || preference.scopeKey !== scopeKey || !revision(preference.revision) || preference.revision !== previousRevision + 1)
    throw new Error('此对话的模型保存结果待核对，请重新读取；没有自动重试。');
  return preference;
}
