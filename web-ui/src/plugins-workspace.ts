export type PluginData = Record<string, any>;
export const pluginRows = (value: unknown): PluginData[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
const revision = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const object = (value: unknown): value is PluginData => !!value && typeof value === 'object' && !Array.isArray(value);
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value));

/** A queued reply or another native page cannot replace this page's read model. */
export function acceptPluginView(previous: PluginData | null, value: unknown, fresh = false): PluginData | null {
  if (!object(value) || !text(value.pageRequestId) || !revision(value.revision) || typeof value.busy !== 'boolean' || typeof value.unresolved !== 'boolean'
      || !object(value.config) || !object(value.catalog) || !object(value.installed) || !object(value.jsInstalled)
      || !['idle', 'working', 'ready', 'cancelled', 'failed', 'completed', 'unknown'].includes(value.status)) return null;
  if (previous && !fresh && (value.pageRequestId !== previous.pageRequestId || value.revision < previous.revision)) return null;
  return value;
}

export function pluginIdentity(row: PluginData): PluginData {
  if (!text(row.id) || !text(row.installationId) || !revision(row.stateRevision)) throw new Error('请重新读取这个插件的安装身份。');
  return { id: row.id, installationId: row.installationId, stateRevision: row.stateRevision };
}
export function samePluginIdentity(left?: PluginData | null, right?: PluginData | null): boolean {
  return !!left && !!right && text(left.id) && text(left.installationId) && text(left.grantsDigest) && revision(left.stateRevision) && left.id === right.id
    && left.installationId === right.installationId && left.stateRevision === right.stateRevision
    && left.grantsDigest === right.grantsDigest && left.manifest?.version === right.manifest?.version;
}
export function jsPluginIdentity(row: PluginData): PluginData {
  if (!text(row.id) || !text(row.installationId) || !revision(row.approvalRevision)) throw new Error('请重新读取这个扩展的源码身份。');
  return { id: row.id, installationId: row.installationId, approvalRevision: row.approvalRevision };
}
export function sameJsIdentity(left?: PluginData | null, right?: PluginData | null): boolean {
  return !!left && !!right && text(left.installationId) && revision(left.approvalRevision) && left.id === right.id
    && left.installationId === right.installationId && left.approvalRevision === right.approvalRevision
    && left.sourceSha256 === right.sourceSha256 && left.version === right.version;
}

export function acceptPluginReview(value: PluginData, kind: string, original?: PluginData): PluginData {
  if (!text(value.reviewHandle) || value.kind !== kind || !object(value.data) || !text(value.data.id)) throw new Error('尚未取得这次操作的完整审阅。');
  const data = value.data;
  if (['enabled', 'grant', 'remove'].includes(kind) && !samePluginIdentity(original, data)) throw new Error('审阅对应的安装或工具授权已变化。');
  if (kind === 'restore' && (!original || data.id !== original.id || data.expectedInstallationId !== original.installationId || data.expectedStateRevision !== original.stateRevision || data.restoration !== true)) throw new Error('恢复预览没有对应当前安装。');
  if (['install', 'restore'].includes(kind) && (!object(data.manifest) || data.manifest.id !== data.id || !text(data.targetVersion) || data.targetVersion !== data.manifest.version || typeof data.expectedInstallationId !== 'string' || !revision(data.expectedStateRevision))) throw new Error('安装差异缺少准确的版本身份。');
  return value;
}

/** Only the actual, identity-matched native result can clear a submitted editor. */
export function confirmedPluginResult(review: PluginData, value: PluginData): PluginData {
  const data = review.data, kind = review.kind;
  const oldInstallation = ['install', 'restore'].includes(kind) ? data.expectedInstallationId : data.installationId;
  const oldRevision = ['install', 'restore'].includes(kind) ? data.expectedStateRevision : data.stateRevision;
  if (value.confirmed !== true || value.status !== 'confirmed' || value.replayAllowed !== false || value.kind !== kind || value.id !== data.id
      || value.previousInstallationId !== oldInstallation || value.previousStateRevision !== oldRevision || !object(value.result)) throw new Error('原操作尚未取得匹配回执，请读取当前安装核对。');
  const result = value.result;
  if (kind === 'remove') { if (result.removed !== true) throw new Error('卸载结果仍待核对。'); return result; }
  if (result.id !== data.id || !text(result.installationId) || !revision(result.stateRevision)) throw new Error('插件回执的安装身份不匹配。');
  if (['install', 'restore'].includes(kind)) {
    if (result.installationId === oldInstallation || result.manifest?.version !== data.targetVersion || result.enabled !== false || !Array.isArray(result.grants) || result.grants.length !== 0) throw new Error('新安装的版本、启用状态或授权与审阅不符。');
  } else {
    if (result.installationId !== oldInstallation || result.stateRevision < oldRevision) throw new Error('回执属于另一次安装或旧状态。');
    if (kind === 'enabled' && result.enabled !== data.nextEnabled) throw new Error('启停结果仍待核对。');
    if (kind === 'grant' && (!Array.isArray(result.grants) || result.grants.includes(`tool:${data.selectedTool?.name}`) !== data.nextGranted)) throw new Error('工具授权结果仍待核对。');
  }
  return result;
}

export function catalogUnknown(view: PluginData | null): boolean {
  const receipt = view?.operationReceipt;
  return view?.unresolved === true || view?.status === 'unknown' || !!receipt && (receipt.recorded !== true || receipt.receipt?.status === 'unknown');
}
export function catalogReview(view: PluginData): PluginData | null {
  if (view.status !== 'ready' || view.busy || catalogUnknown(view) || !text(view.reviewHandle) || !['declaration_install', 'js_install', 'js_permission'].includes(view.reviewKind) || !object(view.review)) return null;
  const data = view.review;
  if (view.reviewKind === 'declaration_install') {
    if (!text(data.packageReview?.preview?.id) || !text(data.packageReview?.preview?.targetVersion)) return null;
  } else if (!text(data.id) || !text(data.version) || typeof data.source !== 'string' || !data.source || !text(data.sourceSha256)) return null;
  return { kind: view.reviewKind, reviewHandle: view.reviewHandle, pageRequestId: view.pageRequestId, revision: view.revision, data };
}
export function catalogReceiptResult(view: PluginData, reviewed: PluginData, operation: string): PluginData {
  if (reviewed.kind === 'js_permission' ? !['approve', 'revoke', 'enable', 'disable', 'remove'].includes(operation) : operation !== 'install') throw new Error('这项操作与当前审阅类型不匹配。');
  const actual = view.operationReceipt;
  if (view.pageRequestId !== reviewed.pageRequestId || view.revision <= reviewed.revision || catalogUnknown(view) || view.status !== 'completed'
      || !actual || actual.recorded !== true || actual.requestId !== view.requestId || actual.command !== (operation === 'install' ? 'submit' : 'jsChange')) throw new Error('原目录操作尚未取得完整回执，请查询当前安装。');
  const result = actual.receipt, data = reviewed.data;
  if (!object(result)) throw new Error('原目录回执缺失。');
  if (reviewed.kind === 'declaration_install') {
    const preview = data.packageReview.preview;
    if (result.status === 'rediscovery_required' && result.installed === false) return result;
    if (result.status !== 'installed_disabled' || result.installed !== true || result.plugin?.id !== preview.id || !text(result.plugin?.installationId) || result.plugin.installationId === preview.expectedInstallationId || result.plugin?.manifest?.version !== preview.targetVersion || result.plugin?.enabled !== false || result.plugin?.grants?.length !== 0) throw new Error('声明包安装回执与审阅不符。');
  } else if (operation === 'install') {
    if (result.status !== 'installed_unapproved' || result.installed !== true || result.plugin?.id !== data.id || !text(result.plugin?.installationId) || result.plugin.installationId === data.current?.installationId || result.plugin?.version !== data.version || result.plugin?.sourceSha256 !== data.sourceSha256 || result.plugin?.approved !== false || result.plugin?.enabled !== false) throw new Error('源码安装回执与审阅不符。');
  } else if (operation === 'remove') {
    if (result.status !== 'removed' || result.removed !== true || result.id !== data.id || result.installationId !== data.installationId) throw new Error('源码卸载回执与原安装不符。');
  } else {
    const row = result.plugin;
    if (result.status !== 'saved' || !row || row.id !== data.id || row.installationId !== data.installationId || row.sourceSha256 !== data.sourceSha256 || row.version !== data.version || !revision(row.approvalRevision) || row.approvalRevision <= data.approvalRevision
        || operation === 'approve' && row.approved !== true || operation === 'revoke' && (row.approved !== false || row.enabled !== false)
        || operation === 'enable' && (row.enabled !== true || row.approved !== true) || operation === 'disable' && row.enabled !== false) throw new Error('源码权限回执与本次审阅不符。');
  }
  return result;
}

export function sourceDraft(source?: PluginData): PluginData {
  return { id: source?.id || '', label: source?.label || '', catalogUrl: source?.catalogUrl || '', enabled: source?.enabled ?? true, pinnedCatalogSha256: source?.pinnedCatalogSha256 || '' };
}
export function sourceConfiguration(config: PluginData, draft: PluginData, editingId: string | null, remove = false): PluginData {
  if (!text(config.revision) || !Array.isArray(config.sources)) throw new Error('请先读取来源配置。');
  const sources = copy(pluginRows(config.sources));
  if (editingId !== null && !sources.some(row => row.id === editingId)) throw new Error('原目录来源已经变化，请重新读取。');
  if (remove) return { expectedRevision: config.revision, sources: sources.filter(row => row.id !== editingId) };
  const id = String(draft.id).trim(), label = String(draft.label).trim(), catalogUrl = String(draft.catalogUrl).trim(), pin = String(draft.pinnedCatalogSha256 || '').trim().toLowerCase();
  if (!/^[a-zA-Z][a-zA-Z0-9_.-]{0,79}$/.test(id) || !label || label.length > 240 || catalogUrl.length > 2000) throw new Error('请填写有效的来源标识、名称与目录地址。');
  if (editingId !== null && id !== editingId) throw new Error('已保存来源的标识不能在旧表单中替换。');
  if (sources.some(row => row.id === id && row.id !== editingId)) throw new Error('这个来源标识已存在。');
  let url: URL; try { url = new URL(catalogUrl); } catch { throw new Error('目录地址需要完整的 HTTPS 地址。'); }
  if (url.username || url.password || url.search || url.hash || !url.hostname || url.port === '0' || /[\s\\\u0000-\u001f\u007f]/.test(catalogUrl)
      || !(url.protocol === 'https:' || url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) throw new Error('远程目录使用无凭据的 HTTPS 地址；HTTP 仅用于本机回环地址。');
  if (pin && !/^[a-f0-9]{64}$/.test(pin)) throw new Error('固定目录摘要需要 64 位 SHA-256。');
  if (typeof draft.enabled !== 'boolean') throw new Error('请核对来源启用状态。');
  const source: PluginData = { id, label, catalogUrl, enabled: draft.enabled }; if (pin) source.pinnedCatalogSha256 = pin;
  const next = sources.filter(row => row.id !== editingId); next.push(source);
  if (next.length > 16) throw new Error('最多保存 16 个目录来源。');
  return { expectedRevision: config.revision, sources: next };
}
export function confirmedSourceConfiguration(input: PluginData, view: PluginData): boolean {
  const rows = (value: unknown) => pluginRows(value).map(row => ({ id: row.id, label: row.label, catalogUrl: row.catalogUrl, enabled: row.enabled, pinnedCatalogSha256: row.pinnedCatalogSha256 || '' })).sort((a, b) => a.id.localeCompare(b.id));
  return view.status === 'ready' && !catalogUnknown(view) && text(view.config?.revision) && view.config.revision !== input.expectedRevision
    && view.operationReceipt?.command === 'configure' && view.operationReceipt?.requestId === view.requestId && view.operationReceipt?.recorded === true
    && JSON.stringify(rows(input.sources)) === JSON.stringify(rows(view.config.sources));
}
