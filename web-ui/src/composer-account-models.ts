export type AccountModelRecord = Record<string, any>;
export type AccountModelOptions = { reasoningEffort: string; outputReserve: string; imageTokenReserve: string };
const object = (value: any): AccountModelRecord => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const rows = (value: any): AccountModelRecord[] => Array.isArray(value) ? value.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : [];
const exactVersion = (value: any): boolean => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const text = (value: any): string => typeof value === 'string' ? value : '';
const singleAccount = (projection: AccountModelRecord, id: string) => {
  const found = rows(object(projection.accountSnapshot).accounts).filter(row => row.id === id);
  return found.length === 1 ? found[0] : undefined;
};
export function accountModelProjectionCurrent(value: any, revision: any): boolean {
  const projection = object(value);
  return !!text(projection.windowId) && exactVersion(revision) && projection.selectionRevision === revision &&
    !!projection.accountSnapshot && object(projection.accountSnapshot).closed !== true;
}
function unavailable(row: AccountModelRecord, now: number): string {
  if (!text(row.id) || !exactVersion(row.version)) return '请重新读取账号状态';
  if (row.provider !== 'chatgpt_plan') return '这个连接不属于 ChatGPT 套餐';
  if (row.enabled !== true) return '请先在账号管理中启用';
  if (row.planUsageEnabled !== true) return '请先在账号管理中确认套餐使用权限';
  if (row.usageLimited === true) return '套餐请求已暂停，请先在账号管理中核对';
  if (!['connected', 'cooldown'].includes(row.status)) return '请先在账号管理中重新连接';
  const cooldown = row.cooldownUntil ?? 0;
  if (typeof cooldown !== 'number' || !Number.isFinite(cooldown) || cooldown > now) return '账号正在冷却，请稍后重新读取';
  return '';
}
export function accountModelAccountRows(value: any, revision: any, now = Date.now()) {
  const projection = object(value);
  if (!accountModelProjectionCurrent(projection, revision)) return [];
  const accounts = rows(object(projection.accountSnapshot).accounts), counts = new Map<any, number>();
  for (const row of accounts) counts.set(row.id, (counts.get(row.id) || 0) + 1);
  return accounts.filter(row => text(row.id)).map(row => {
    const reason = counts.get(row.id) === 1 ? unavailable(row, now) : '账号记录有重复，请重新读取';
    return { id: row.id as string, name: text(row.label) || 'ChatGPT 账号', description: text(row.plan || row.planName) || '读取此账号的可用模型', disabled: !!reason, reason };
  });
}
export function accountModelReadRequest(value: any, revision: any, accountId: string, now = Date.now()) {
  const projection = object(value), row = singleAccount(projection, accountId);
  if (!accountModelProjectionCurrent(projection, revision) || !row || unavailable(row, now)) throw new Error('原账号状态已变化，请重新读取后核对。');
  return { windowId: projection.windowId as string, accountId: row.id as string, expectedVersion: row.version as number };
}
export function accountModelCatalog(value: any, revision: any) {
  const projection = object(value), catalog = object(projection.catalog), row = singleAccount(projection, text(catalog.accountId));
  if (!accountModelProjectionCurrent(projection, revision) || !row || !exactVersion(catalog.accountVersion) || row.version !== catalog.accountVersion) return null;
  const candidates = rows(catalog.models), counts = new Map<any, number>();
  for (const model of candidates) counts.set(model.id, (counts.get(model.id) || 0) + 1);
  const models = candidates.filter(model => {
    const ref = object(model.nativeRef);
    return !!text(model.id) && counts.get(model.id) === 1 &&
      ref.provider === 'chatgpt_plan' && ref.accountId === catalog.accountId && ref.accountVersion === catalog.accountVersion && ref.modelId === model.id;
  });
  return { accountId: catalog.accountId as string, accountVersion: catalog.accountVersion as number, accountLabel: text(row.label) || 'ChatGPT 账号', models };
}
export function accountModelReviewRequest(value: any, revision: any, modelId: string) {
  const projection = object(value), catalog = accountModelCatalog(projection, revision);
  if (!catalog || !catalog.models.some(model => model.id === modelId)) throw new Error('原账号模型目录已变化，请重新读取。');
  return { windowId: projection.windowId as string, accountId: catalog.accountId, modelId };
}
export function accountModelReviewCurrent(value: any, revision: any): boolean {
  const review = object(value), row = singleAccount(review, text(review.accountId));
  return accountModelProjectionCurrent(review, revision) && !!text(review.reviewHandle) && !!text(review.modelId) &&
    !!row && exactVersion(review.accountVersion) && row.version === review.accountVersion;
}
export function accountModelReasoningLevels(value: any): string[] {
  const fact = object(object(object(value).catalogFacts).facts).REASONING_LEVELS;
  return fact?.known === true && Array.isArray(fact.value) ? fact.value.filter((level: any) => typeof level === 'string') : [];
}
export function accountModelSelection(value: any, revision: any, draft: AccountModelOptions) {
  const review = object(value);
  if (!accountModelReviewCurrent(review, revision)) throw new Error('原模型审核已变化，请重新核对。');
  const output = Number(draft.outputReserve), image = Number(draft.imageTokenReserve);
  if (!/^\d+$/.test(draft.outputReserve) || !Number.isSafeInteger(output) || output < 128 || output > 32768 ||
      !/^\d+$/.test(draft.imageTokenReserve) || !Number.isSafeInteger(image) || image < 0 || image > 2_000_000 ||
      (draft.reasoningEffort !== '' && !accountModelReasoningLevels(review).includes(draft.reasoningEffort))) throw new Error('请核对推理设置与本地预留范围。');
  return { windowId: review.windowId as string, reviewHandle: review.reviewHandle as string,
    options: { reasoningEffort: draft.reasoningEffort, outputReserve: output, imageTokenReserve: image } };
}
