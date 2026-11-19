export type ScopeData = Record<string, any>;
export const scopeRows = (value: unknown): ScopeData[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
export type MaterialChoice = { id: string; sha256: string; path: string; expectedVersion: string };
export type RouteDraft = {
  selected: boolean; tools: string; models: string; deadline: string;
  localTools: string; localModels: string;
  shares: Record<string, { selected: boolean; tools: string; models: string }>;
  files: MaterialChoice[]; projects: MaterialChoice[]; missingShares: ScopeData[];
};
const sameResource = (a: ScopeData, b: ScopeData): boolean => {
  const canonical = (value: any): string => value && typeof value === 'object'
    ? Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']' : '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}'
    : JSON.stringify(value) ?? 'null';
  return canonical(a) === canonical(b);
};
export const phoneTextOnly = (route: ScopeData) => route.textReturnContract === 'pocketworld.original-phone-allocation.owner-reviewed-text.v1';
export function routeTitle(option: ScopeData, human = false): string {
  if (phoneTextOnly(option)) return "只让这部手机处理文字";
  const scope = option.resourceBinding || {};
  const phone = scope.screenMode === 'main_screen' ? '主屏' : scope.screenMode === 'background' ? '独立屏' : '';
  if (phone) return human ? '由你操作对方手机' + phone : option.kind === 'goal' ? '让对方手机在' + phone + '完成目标' : '本机助手使用对方手机' + phone + '工具';
  if (option.kind === 'goal') return '让对端模型处理目标';
  if (scope.kind === 'desktop') return scope.windowId ? '使用电脑的指定窗口' : '使用电脑桌面工具';
  if (scope.projectId && scope.checkoutId) return '使用已授权的项目工具';
  return scope.kind === 'workspace' ? '使用电脑工作区工具' : '使用对方已授权的工具';
}
export function routeDraft(option: ScopeData, view: ScopeData, now = Date.now()): RouteDraft {
  const old = scopeRows(view.selected?.routes).find(row => row.optionId === option.optionId);
  const tools = Math.min(12, Number(option.toolBudgetMaximum) || 0, Math.max(0, view.humanInputOnly ? Math.floor(Number(view.toolsAvailable) / 2) : Number(view.toolsAvailable) - 1));
  const models = option.kind === 'goal' ? Math.min(3, Number(option.modelBudgetMaximum) || 0, Number(view.modelsAvailable) || 0) : 0;
  const shares: RouteDraft['shares'] = {};
  scopeRows(option.downstreamOptions).forEach(candidate => {
    const prior = scopeRows(old?.downstreamShares).find(row => row.targetDeviceId === candidate.targetDeviceId && sameResource(row.resourceBinding, candidate.resourceBinding));
    shares[candidate.optionId] = { selected: !!prior, tools: String(prior?.toolBudget ?? 0), models: String(prior?.modelBudget ?? 0) };
  });
  const materials = (kind: 'files' | 'projects'): MaterialChoice[] => scopeRows(old?.[kind]).map(row => ({ id: row.id, sha256: row.sha256, path: row.path, expectedVersion: row.expectedVersion || 'absent' }));
  return {
    selected: !!old, tools: String(old?.toolBudget ?? tools), models: String(old?.modelBudget ?? models),
    deadline: String(old?.deadline ?? Math.min(now + 15 * 60_000, Number(view.deadlineMaximum), Number(option.deadlineMaximum))),
    localTools: String(old?.localToolShare ?? tools), localModels: String(old?.localModelShare ?? models),
    shares, files: materials('files'), projects: materials('projects'),
    missingShares: scopeRows(old?.downstreamShares).filter(prior => !scopeRows(option.downstreamOptions).some(candidate => prior.targetDeviceId === candidate.targetDeviceId && sameResource(prior.resourceBinding, candidate.resourceBinding)))
  };
}
const integer = (value: string, min: number, max: number, label: string) => {
  const number = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(number) || number < min || number > max) throw new Error(label + '需为 ' + min + '–' + max + ' 的整数。');
  return number;
};
/** Only translates user choices. The native Window resolves every option and validates authority. */
export function scopeSelection(view: ScopeData, drafts: Record<string, RouteDraft>, now = Date.now()): ScopeData[] {
  const selected=scopeRows(view.options).filter(option => drafts[option.optionId]?.selected);
  if(selected.length>1 && selected.some(phoneTextOnly)) throw new Error('只派发文字时不能同时使用屏幕或其他范围。请只保留一个选择。');
  return selected.map(option => {
    const draft = drafts[option.optionId];
    if (draft.missingShares.length) throw new Error('请明确处理当前无法核对的旧下游选择，再保存范围。');
    const toolBudget = integer(draft.tools, view.humanInputOnly ? 2 : option.kind === 'goal' ? 0 : 1, Math.min(400, option.toolBudgetMaximum, view.humanInputOnly ? Math.floor(Number(view.toolsAvailable) / 2) : 400), '工具份额');
    const modelBudget = integer(draft.models, option.kind === 'goal' ? 1 : 0, option.kind === 'goal' ? Math.min(1000, option.modelBudgetMaximum) : 0, '模型份额');
    const deadline = integer(draft.deadline, now + 1, Math.min(view.deadlineMaximum, option.deadlineMaximum), '设备范围截止时间');
    const shares = scopeRows(option.downstreamOptions).filter(row => draft.shares[row.optionId]?.selected).map(row => ({
      optionId: row.optionId, toolBudget: integer(draft.shares[row.optionId].tools, 0, toolBudget, '下游工具份额'),
      modelBudget: integer(draft.shares[row.optionId].models, 0, modelBudget, '下游模型份额')
    }));
    const localToolShare = shares.length ? integer(draft.localTools, 0, toolBudget, '对端本机工具份额') : toolBudget;
    const localModelShare = shares.length ? integer(draft.localModels, 1, modelBudget, '对端本机模型份额') : modelBudget;
    if (shares.reduce((sum, row) => sum + row.toolBudget, localToolShare) > toolBudget || shares.reduce((sum, row) => sum + row.modelBudget, localModelShare) > modelBudget) throw new Error('对端本机与下游份额之和不能超过这条范围的总份额。');
    return { optionId: option.optionId, toolBudget, modelBudget, deadline, localToolShare, localModelShare, shares,
      files: draft.files.map(row => ({ attachmentId: row.id, sha256: row.sha256, path: row.path, expectedVersion: row.expectedVersion })),
      projects: draft.projects.map(row => ({ artifactId: row.id, sha256: row.sha256, path: row.path })) };
  });
}
export function materialPath(name: string): string {
  return 'inbox/' + (name.replace(/[\\/\x00-\x1f]/g, '_').replace(/^\.+/, '').slice(0,160) || 'selected-file');
}
