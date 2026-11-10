export const setupSteps = ['welcome', 'model', 'capabilities', 'finish'] as const;
export const setupCapabilities = ['phone', 'tools', 'voice', 'background'] as const;
export type SetupStep = typeof setupSteps[number];
export type SetupCapability = typeof setupCapabilities[number];
export type SetupStatus = 'new' | 'in_progress' | 'completed' | 'skipped';
export type SetupPermission = 'accessibility' | 'notifications' | 'microphone' | 'battery' | 'vendorBackground';
export type SetupPreferences = { step: SetupStep; capabilities: SetupCapability[]; status: SetupStatus };
export type SetupRead = {
  version: 1; revision: number; preferences: SetupPreferences;
  model: { configured: boolean; label: string; tested: boolean; testing?: boolean; message?: string;
    availability?: 'unconfigured' | 'unavailable'; details?: string };
  permissions: { accessibility: boolean | null; notifications: boolean | null; microphone: boolean | null; batteryExempt: boolean | null; vendorBackgroundVisited: boolean | null };
  speech: { available: boolean | null; networkUse: string };
};

export type RuntimeSetupStatus = 'unavailable' | 'not_prepared' | 'ready' | 'running' | 'cancel_requested' | 'cancelled' | 'failed' | 'interrupted' | 'blocked';
export type SetupRuntimeOperation = {
  taskId: string; runId: string; epoch: number; operationId: string; status: string; phase: string;
  completedBytes: number | null; totalBytes: number | null; progressUnit: 'bytes' | 'items' | null; fraction: number | null;
  error: { code: string; message: string; details: unknown } | null; receipt: unknown;
};
export type SetupRuntimeLogs = {
  chunks: { sequence: number; stream: string; dataBase64: string; bytes: number }[];
  nextCursor: number; hasMore: boolean; logIncompleteTail: boolean; outputTruncated: boolean;
};
export type SetupRuntime = {
  format: 'pocketworld.runtime-setup-view.v1'; status: RuntimeSetupStatus;
  ready: boolean; canPrepare: boolean; canRetry: boolean; canCancel: boolean; message: string;
  runtime: Record<string, unknown>; preparation: Record<string, unknown>;
  operation: SetupRuntimeOperation | null; logs: SetupRuntimeLogs | null;
};
export type SetupRuntimeReview = {
  reviewHandle: string;
  review: { requestId: string; expiresAt: number; mode: 'prepare' | 'repair'; title: string; summary: string;
    runtimeIdentity: unknown; catalogIdentity: unknown; effects: unknown; downloadBytes: number | null; minimumFreeBytes: number | null;
    additionalDownloads: boolean; existingGenerationRetained: boolean; executionAuthorized: false };
};

export function setupObject(value: unknown): Record<string, any> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : {};
}
const text = (value: unknown) => typeof value === 'string' ? value : '';
const observed = (value: unknown): boolean | null => typeof value === 'boolean' ? value : null;
const finiteNonnegative = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;

/** A guide preference is not a permission or proof that a capability works. */
export function readSetup(value: unknown): SetupRead {
  const row = setupObject(value), preferences = setupObject(row.preferences);
  if (row.version !== 1 || !Number.isSafeInteger(row.revision) || row.revision < 0 ||
      !setupSteps.includes(preferences.step) || !['new', 'in_progress', 'completed', 'skipped'].includes(preferences.status) ||
      !Array.isArray(preferences.capabilities) || preferences.capabilities.some((id: unknown) => !setupCapabilities.includes(id as SetupCapability)) ||
      new Set(preferences.capabilities).size !== preferences.capabilities.length) throw new Error('准备状态尚未完整读取，请刷新后继续。');
  const model = setupObject(row.model), permissions = setupObject(row.permissions), speech = setupObject(row.speech);
  return {
    version: 1, revision: row.revision,
    preferences: { step: preferences.step, status: preferences.status, capabilities: setupCapabilities.filter(id => preferences.capabilities.includes(id)) },
    model: { configured: model.configured === true, label: text(model.label), tested: model.configured === true && model.tested === true,
      testing: model.testing === true, message: text(model.message),
      availability: model.availability === 'unconfigured' || model.availability === 'unavailable' ? model.availability : undefined,
      details: text(model.details) },
    permissions: { accessibility: observed(permissions.accessibility), notifications: observed(permissions.notifications),
      microphone: observed(permissions.microphone), batteryExempt: observed(permissions.batteryExempt), vendorBackgroundVisited: observed(permissions.vendorBackgroundVisited) },
    speech: { available: observed(speech.available), networkUse: text(speech.networkUse) || 'unknown' },
  };
}

export function setupSaveInput(current: SetupRead, next: SetupPreferences) {
  const checked = readSetup({ ...current, preferences: next });
  return { expectedRevision: current.revision, ...checked.preferences };
}
export function setupSaveConfirmed(before: SetupRead, next: SetupPreferences, value: unknown): SetupRead {
  const actual = readSetup(value), expected = setupSaveInput(before, next);
  if (actual.revision <= before.revision || actual.preferences.step !== expected.step || actual.preferences.status !== expected.status ||
      actual.preferences.capabilities.join(',') !== expected.capabilities.join(',')) throw new Error('这次准备选择的保存结果待核对，请刷新；没有继续下一步。');
  return actual;
}
export function permissionLabel(value: boolean | null): string { return value === true ? '已允许' : value === false ? '未允许' : '待检查'; }
export function setupModelLabel(value: SetupRead['model']): string { return value.testing ? '正在测试文本连接' : value.availability === 'unavailable' ? '模型状态待核对' : value.tested ? '文本连接测试通过' : value.configured ? '已选择 · 尚未测试' : '尚未连接模型'; }
export function speechNetworkLabel(value: string): string {
  if (value === 'requires_network') return '系统服务需要网络';
  if (value === 'declares_no_network_requirement') return '系统服务声明无需网络，仍需实际听写验证';
  return '是否联网由系统服务决定，当前尚未确认';
}

export function readSetupRuntime(value: unknown): SetupRuntime {
  const row = setupObject(value);
  const statuses: RuntimeSetupStatus[] = ['unavailable', 'not_prepared', 'ready', 'running', 'cancel_requested', 'cancelled', 'failed', 'interrupted', 'blocked'];
  if (row.format !== 'pocketworld.runtime-setup-view.v1' || !statuses.includes(row.status)) throw new Error('工具准备状态尚未完整读取，请刷新后核对。');
  let operation: SetupRuntimeOperation | null = null;
  if (row.operation != null) {
    const op = setupObject(row.operation);
    if (!text(op.taskId) || !text(op.runId) || !Number.isSafeInteger(op.epoch) || op.epoch < 0) throw new Error('工具准备任务的身份不完整，请刷新后核对。');
    const error = op.error == null ? null : setupObject(op.error);
    const fraction = typeof op.fraction === 'number' && Number.isFinite(op.fraction) && op.fraction >= 0 && op.fraction <= 1 ? op.fraction : null;
    operation = { taskId: op.taskId, runId: op.runId, epoch: op.epoch, operationId: text(op.operationId), status: text(op.status), phase: text(op.phase),
      completedBytes: finiteNonnegative(op.completedBytes), totalBytes: finiteNonnegative(op.totalBytes),
      progressUnit: op.progressUnit === 'bytes' || op.progressUnit === 'items' ? op.progressUnit : null, fraction,
      error: error ? { code: text(error.code), message: text(error.message), details: error.details } : null, receipt: op.receipt };
  }
  let logs: SetupRuntimeLogs | null = null;
  if (row.logs != null) {
    const source = setupObject(row.logs);
    logs = { chunks: Array.isArray(source.chunks) ? source.chunks.filter((chunk: unknown) => {
      const entry = setupObject(chunk); return Number.isSafeInteger(entry.sequence) && entry.sequence >= 0 && typeof entry.dataBase64 === 'string';
    }).map((chunk: Record<string, any>) => ({ sequence: chunk.sequence, stream: text(chunk.stream), dataBase64: chunk.dataBase64, bytes: finiteNonnegative(chunk.bytes) || 0 })) : [],
      nextCursor: finiteNonnegative(source.nextCursor) || 0, hasMore: source.hasMore === true,
      logIncompleteTail: source.logIncompleteTail === true, outputTruncated: source.outputTruncated === true };
  }
  return { format: row.format, status: row.status, ready: row.ready === true, canPrepare: row.canPrepare === true, canRetry: row.canRetry === true,
    canCancel: row.canCancel === true && operation != null, message: text(row.message), runtime: setupObject(row.runtime), preparation: setupObject(row.preparation), operation, logs };
}
export function readSetupRuntimeReview(value: unknown): SetupRuntimeReview {
  const row = setupObject(value), review = setupObject(row.review);
  if (!text(row.reviewHandle) || !text(review.requestId) || !['prepare', 'repair'].includes(review.mode) || review.executionAuthorized !== false ||
      typeof review.expiresAt !== 'number' || !Number.isFinite(review.expiresAt)) throw new Error('工具准备内容未能完整读取，请重新查看。');
  return { reviewHandle: row.reviewHandle, review: { requestId: review.requestId, expiresAt: review.expiresAt, mode: review.mode,
    title: text(review.title), summary: text(review.summary), runtimeIdentity: review.runtimeIdentity, catalogIdentity: review.catalogIdentity,
    effects: review.effects, downloadBytes: finiteNonnegative(review.downloadBytes), minimumFreeBytes: finiteNonnegative(review.minimumFreeBytes), additionalDownloads: review.additionalDownloads === true,
    existingGenerationRetained: review.existingGenerationRetained === true, executionAuthorized: false } };
}
export function runtimeCancelInput(value: SetupRuntime) {
  if (!value.canCancel || !value.operation) throw new Error('当前没有可取消的准备任务，请刷新状态。');
  const { taskId, runId, epoch } = value.operation;
  return { taskId, runId, epoch };
}
export function runtimePreparing(value: SetupRuntime | null): boolean { return value?.status === 'running' || value?.status === 'cancel_requested'; }
export function runtimeStatusLabel(value: SetupRuntime): string {
  return ({ unavailable: '当前不可用', not_prepared: '尚未准备', ready: '工具已就绪', running: '正在准备', cancel_requested: '正在停止',
    cancelled: '已停止', failed: '准备失败', interrupted: '准备中断，需核对', blocked: '需要处理' })[value.status];
}
export function runtimePhaseLabel(phase: string): string {
  if (phase.startsWith('extracting_')) return '展开已下载的工具';
  return ({ queued: '等待开始', downloading: '下载工具', verifying_download: '核对下载文件',
    verifying_installed_tools: '检查已安装工具', configuring: '配置运行环境', recovering_package_configuration: '恢复依赖配置',
    updating_signed_package_indexes: '更新依赖目录', installing_system_tools: '安装工具与依赖', probing_tools: '检查工具是否可用',
    probing_child_process_and_venv: '检查命令与 Python 环境', probing_hardlinks_before_activation: '检查文件操作',
    activating: '启用准备好的工具', probing_hardlinks_after_activation: '确认工具运行环境', ready: '工具已就绪',
    failed: '准备失败', cancelled: '准备已停止', interrupted: '准备中断，需核对', repair_required: '工具需要修复' } as Record<string, string>)[phase] || '正在准备工具';
}
export function setupBytes(value: number | null): string {
  if (value == null) return '大小待确认';
  if (value < 1024) return `${Math.round(value)} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}
/** Only known native phase counts are formatted; no simulated total progress. */
export function runtimeProgressLabel(value: SetupRuntimeOperation): string {
  if (value.completedBytes == null) return '';
  const format = value.progressUnit === 'bytes' ? setupBytes : (count: number | null) => count == null ? '?' : `${Math.round(count)} 项`;
  if (value.progressUnit == null) return '';
  return `${format(value.completedBytes)}${value.totalBytes == null ? '' : ` / ${format(value.totalBytes)}`}`;
}
export function runtimeLogText(logs: SetupRuntimeLogs | null): string {
  if (!logs) return '';
  return logs.chunks.map(chunk => {
    try { return new TextDecoder().decode(Uint8Array.from(atob(chunk.dataBase64), char => char.charCodeAt(0))); }
    catch { return '[这一段日志无法解码，请核对原任务记录]\n'; }
  }).join('');
}
export function setupDetails(value: unknown): string {
  if (typeof value === 'string') return value;
  return value == null ? '' : JSON.stringify(value, null, 2);
}
