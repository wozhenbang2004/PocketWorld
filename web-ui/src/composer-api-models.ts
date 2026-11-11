export type ApiModelRecord = Record<string, any>;
export type ApiModelFilter = 'all' | 'favorites' | 'recent';
const object = (value: any): ApiModelRecord => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const exactVersion = (value: any): boolean => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const identity = (value: any): value is string => typeof value === 'string' && value.trim().length > 0 && !value.includes('\0');
export const apiModelKey = (row: ApiModelRecord): string => JSON.stringify([row.connectionId, row.modelId]);
const booleanCapability = (fact: any): boolean => fact?.known === true && typeof fact.value === 'boolean';
export const apiCapability = (fact: any): string => {
  if (booleanCapability(fact)) return fact.value ? '支持' : '不支持';
  return ({ not_returned_by_catalog: '服务未提供', catalog_expired: '资料待更新', not_observed: '尚未读取', owner_marked_unknown: '待确认' } as Record<string, string>)[fact?.reason] || '暂未确认';
};
type ModelCapability = 'imageInput' | 'toolCalls';
const publicCapability = (row: ApiModelRecord, key: ModelCapability): boolean => !booleanCapability(row[key]) && row[key]?.source !== 'OWNER_OVERRIDE'
  && row.capabilityReference?.available === true && booleanCapability(row.capabilityReference[key]);
/** Public model metadata informs the picker without changing native request admission. */
export const apiModelCapability = (row: ApiModelRecord, key: ModelCapability): string => publicCapability(row, key)
  ? `${row.capabilityReference[key].value ? '支持' : '不支持'}（${row.capabilityReference.stale === true ? '缓存资料' : '公开资料'}）` : apiCapability(row[key]);
export function apiModelCapabilityNote(row: ApiModelRecord, index: ApiModelRecord = {}): string {
  if (publicCapability(row, 'imageInput') || publicCapability(row, 'toolCalls')) return '能力信息补充自 models.dev，当前接入的实际支持可能不同。';
  if (booleanCapability(row.imageInput) && booleanCapability(row.toolCalls)) return '';
  if (['imageInput', 'toolCalls'].some(key => row[key]?.source === 'OWNER_OVERRIDE' && !booleanCapability(row[key]))) return '有能力被手动标记为待确认，可在模型设置中调整。';
  if (index.refreshing === true) return '正在更新公开模型资料，稍后重新读取可查看。';
  if (index.available === false) return '公开模型资料暂不可用，未确认不代表模型不支持。';
  return '当前资料未提供完整能力信息，未确认不代表模型不支持。';
}
export function apiModelUnavailable(row: ApiModelRecord): string {
  if (row.available === true) return '';
  return ({ connection_disabled: '连接已停用', connection_unavailable: '原连接不可用', catalog_model_unavailable: '模型已不在原目录', catalog_protocol_mismatch: '此连接协议不适用于该模型', model_reference_not_supported: '此模型引用暂不支持' } as Record<string, string>)[row.unavailableReason] || '原连接或模型当前不可用';
}
export function apiModelProjectionCurrent(value: any, revision: any): boolean {
  const projection = object(value), rows = projection.models;
  if (!identity(projection.windowId) || projection.format !== 'pocketworld.model-picker.v1' || !exactVersion(revision) || projection.selectionRevision !== revision ||
      !exactVersion(projection.viewGeneration) || !Array.isArray(rows)) return false;
  const keys = new Set<string>();
  return rows.every(row => {
    if (!row || !identity(row.connectionId) || !identity(row.modelId) || !exactVersion(row.connectionRevision) ||
        typeof row.available !== 'boolean' || typeof row.favorite !== 'boolean' || row.available && row.connectionRevision < 1 || row.binding !== undefined) return false;
    const key = apiModelKey(row); if (keys.has(key)) return false; keys.add(key); return true;
  });
}
export function apiModelRows(value: any, revision: any, filter: ApiModelFilter = 'all'): ApiModelRecord[] {
  if (!apiModelProjectionCurrent(value, revision)) return [];
  // Native ordering and successful-use timestamps are authoritative; opening/selecting never adds a recent entry.
  return value.models.filter((row: ApiModelRecord) => filter === 'favorites' ? row.favorite : filter === 'recent' ? typeof row.lastUsedAt === 'number' && Number.isFinite(row.lastUsedAt) && row.lastUsedAt > 0 : true);
}
function originalRow(value: any, revision: any, key: string): ApiModelRecord {
  const row = apiModelRows(value, revision).find(row => apiModelKey(row) === key);
  if (!row) throw new Error('原连接或模型设置已变化，请重新读取列表。');
  return row;
}
export function apiModelReviewRequest(value: any, revision: any, key: string) {
  const row = originalRow(value, revision, key);
  return { windowId: value.windowId as string, connectionId: row.connectionId as string, modelId: row.modelId as string, expectedRevision: row.connectionRevision as number };
}
export function apiModelReviewCurrent(review: any, projection: any, revision: any): boolean {
  if (!apiModelProjectionCurrent(projection, revision) || review?.windowId !== projection.windowId || review.selectionRevision !== revision || review.viewGeneration !== projection.viewGeneration) return false;
  const row = object(review.model), original = apiModelRows(projection, revision).find(candidate => apiModelKey(candidate) === apiModelKey(row));
  return !!original && row.connectionRevision === original.connectionRevision && row.available === original.available && row.favorite === original.favorite;
}
export function apiModelSelection(review: any, projection: any, revision: any) {
  if (!apiModelReviewCurrent(review, projection, revision) || review.model.available !== true || !identity(review.reviewHandle)) throw new Error('原模型审核已变化，请重新核对。');
  return { windowId: review.windowId as string, reviewHandle: review.reviewHandle as string };
}
export function apiModelFavorite(review: any, projection: any, revision: any) {
  if (!apiModelReviewCurrent(review, projection, revision) || projection.preferencesAvailable !== true || review.model.available !== true && review.model.favorite !== true) throw new Error('这项模型的收藏状态暂不可更改，请重新读取。');
  return { ...apiModelReviewRequest(projection, revision, apiModelKey(review.model)), favorite: !review.model.favorite };
}
