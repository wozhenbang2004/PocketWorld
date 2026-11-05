export type PackData = Record<string, any>;
export const packRows = (value: unknown): PackData[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const string = (value: unknown): value is string => typeof value === 'string' && value.length > 0;
const keys = (value: unknown): value is string[] => Array.isArray(value) && value.every(string) && value.length <= 24 && new Set(value).size === value.length;

export function packIdentity(row: PackData): PackData {
  if (!string(row.id) || !string(row.installationId) || !Number.isSafeInteger(row.revision) || row.revision < 1) throw new Error('请重新读取这份功能包的准确安装。');
  return { id: row.id, installationId: row.installationId, revision: row.revision };
}
export function packReview(value: PackData, pageId: string): PackData {
  if (value?.pageId !== pageId || !string(value.reviewHandle) || !Number.isSafeInteger(value.selectionRevision) || value.selectionRevision < 0 ||
      !keys(value.selectedKeys) || typeof value.selectionMatches !== 'boolean' || !value.data || !Array.isArray(value.data.components) ||
      typeof value.data.ready !== 'boolean' || value.data.executed !== false || value.data.atomic !== false || value.data.permissionsRestored !== false) {
    throw new Error('原功能包未返回这次准确审阅，请重新读取。');
  }
  const parts = packRows(value.data.components), allKeys = parts.map(row => row.key);
  if (!keys(allKeys) || !value.selectedKeys.every((key: string) => parts.some(row => row.key === key && row.selectable === true))) throw new Error('组件清单与当前选择不一致。');
  return value;
}
export function packSelectionMatches(review: PackData | null, selected: unknown = review?.selectedKeys): boolean {
  if (!review || !keys(selected)) return false;
  const frozen = packRows(review.data?.components).filter(row => row.selected === true).map(row => row.key);
  return selected.length === frozen.length && frozen.every(key => selected.includes(key));
}
export const packConsentKey = (review: PackData): string => `${review.pageId}:${review.reviewHandle}:${review.selectionRevision}`;

/** Called against the live ref inside the click callback, including callbacks from an earlier render. */
export function packConfirmInput(current: PackData | null, displayed: PackData, consentKey: string): PackData {
  if (!current || packConsentKey(current) !== packConsentKey(displayed) || consentKey !== packConsentKey(current) ||
      current.data?.ready !== true || current.selectionMatches !== true || !packSelectionMatches(current)) {
    throw new Error('当前组件选择尚未完成这次审阅，请核对后重新确认。');
  }
  return { pageId: current.pageId, reviewHandle: current.reviewHandle, selectionRevision: current.selectionRevision, consent: true };
}
export function packReceipt(value: PackData, pageId: string): PackData {
  if (value?.pageId !== pageId || !Array.isArray(value.snapshot?.packs) || !value.receipt || value.replayAllowed !== false) throw new Error('原操作回执尚待核对，请读取当前实际安装。');
  const receipt = value.receipt;
  if (!string(receipt.taskId) || !string(receipt.id) || !string(receipt.installationId) || receipt.executed !== false || receipt.replayAllowed !== false ||
      !['completed', 'partial', 'unknown'].includes(receipt.status)) throw new Error('原操作未取得匹配的逐组件回执，请核对原任务。');
  return receipt;
}
