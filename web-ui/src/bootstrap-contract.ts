export type BootstrapPhase = 'configured' | 'data_opened' | 'migrated' | 'features_ready';
export type BootstrapReason = { code: string; message: string; nextStep: string };
export type RepairFile = { name: string; exists?: boolean; length?: number; sha256?: string; copiedLength?: number; copiedSha256?: string; verified?: boolean };
export type RepairConsistency = { quiescence?: string; sqliteLogicalIntegrity?: string; sourceBefore?: string; sourceAfter?: string | null; preCorruptionCallbackBytes?: string; copiesSha256AndLengthVerified?: boolean };
export type RepairMode = 'empty_database' | 'verified_copy';
export type RepairGap = { sourceConversationId: string; reason: string; recordCount: number | null };
export type RecoveredConversation = { sourceConversationId: string; restoredConversationId: string; messageCount: number; omittedCount: number; exportSha256: string; exportBytes: number };
export type RepairRestoration = {
  section: 'conversations'; sourceConversationCount: number; conversationCount: number; messageCount: number; omittedCount: number;
  unreadableConversationCount: number; partial: boolean; contentUtf8Bytes: number; sourceGeneration: string;
  manifestSha256: string; manifestBytes: number; conversations: RecoveredConversation[]; gaps: RepairGap[];
  sourceFingerprint: string; sourceReadOnly: true; restoresExistingIds: false; restoresActiveGrants: false;
  restoresTaskHistory: false; restoresAttachmentBytes: false; restoresCredentialsOrPairings: false; omissionMeaning: string;
};
export type MaintenanceView = {
  repairId: string | null; phase: string; sourceFingerprint: string; backupFingerprint: string;
  preservationVerified: boolean; consistency?: RepairConsistency | string; files: RepairFile[]; allowedCommands: string[];
  reason?: BootstrapReason; reviewModes?: string[]; originalDirectoryReferences?: string[]; originalRetryAvailable?: boolean;
  review?: { reviewId: string; mode: string; summary: string; restoration?: RepairRestoration } | null;
  result?: { mode?: string; summary?: string; executionEnabled?: boolean; restoration?: RepairRestoration } | null;
};
export type BootstrapView = {
  surface: 'bootstrap'; revision: number; generation: number;
  state: 'initializing' | 'failed' | 'maintenance' | 'ready'; stage: BootstrapPhase;
  recovery: 'not_started' | 'running' | 'completed' | 'failed'; reason?: BootstrapReason | null;
  installedVersion: string; requiredSchema: number; observedSchema: number | null;
  retryAvailable: boolean; optionalUnavailable: { capability: string; reason: string }[];
  maintenance: MaintenanceView | null;
};
export type BootstrapOperation = { requestId: string; command: string; generation: number; repairId: string; status: 'pending' | 'unknown' | 'completed' };
export type RepairReview = { generation: number; repairId: string; sourceFingerprint: string; backupFingerprint: string; reviewId: string; mode: RepairMode; summary: string; restoration?: RepairRestoration };
const record = (value: unknown): Record<string, any> => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : {};
export function bootstrapSurface(snapshot: unknown): boolean {
  const value = record(snapshot);
  return value.surface === 'bootstrap' || record(value.host).surface === 'bootstrap' || record(value.host).bridgeAvailable === false;
}
export function bootstrapView(value: unknown): BootstrapView | null {
  const item = record(value);
  if (!Number.isSafeInteger(item.generation) || item.generation < 0 || !Number.isSafeInteger(item.revision) || item.revision < 0 ||
      !['initializing', 'failed', 'maintenance', 'ready'].includes(item.state) ||
      !['configured', 'data_opened', 'migrated', 'features_ready'].includes(item.stage) ||
      !['not_started', 'running', 'completed', 'failed'].includes(item.recovery)) return null;
  return item as BootstrapView;
}
export function currentBootstrapView(native: BootstrapView | null, received: BootstrapView | null): BootstrapView | null {
  // Window-bound reviews can expire without changing the persisted revision.
  // A freshly published native snapshot wins an equal-revision command result.
  return received && (!native || received.revision > native.revision) ? received : native;
}
export function retainedBootstrapOperation(local: BootstrapOperation | null, saved?: BootstrapOperation): BootstrapOperation | undefined {
  if (local?.status === 'unknown') return { ...local, repairId: local.repairId || (saved?.requestId === local.requestId ? saved.repairId : '') };
  if (saved?.status === 'unknown') return saved;
  // Native completion is retained until this document has acknowledged delivery.
  // A lost delivery acknowledgement must leave a usable read-and-acknowledge path.
  if (saved?.status === 'completed' && !local) return { ...saved, status: 'unknown' };
  return local || saved;
}
export function repairAllowed(view: BootstrapView | null, command: string): boolean {
  return Boolean(view && Array.isArray(view.maintenance?.allowedCommands) && view.maintenance.allowedCommands.includes(command));
}
export function repairModeAllowed(view: BootstrapView | null, mode: RepairMode): boolean {
  return repairAllowed(view, 'repair.review') && Boolean(view?.maintenance?.reviewModes?.includes(mode));
}
export function repairConsistencyText(value: MaintenanceView['consistency']): string {
  if (typeof value === 'string') return value;
  if (!value) return '尚未确认';
  const facts: string[] = [];
  if (value.quiescence === 'original_business_handles_closed_before_capture') facts.push('已在采集前关闭原业务资源');
  if (value.copiesSha256AndLengthVerified === true) facts.push('副本 SHA-256 与长度已核验');
  if (value.sourceBefore && value.sourceBefore === value.sourceAfter) facts.push('保全前后原文件集合一致');
  if (value.sqliteLogicalIntegrity === 'not_claimed') facts.push('未确认原库逻辑完整性');
  if (value.preCorruptionCallbackBytes === 'not_claimed') facts.push('不能证明损坏回调前的字节未变化');
  return facts.length ? facts.join('；') + '。' : '尚未取得可确认的一致性结论';
}
export function repairPin(view: BootstrapView): Pick<RepairReview, 'generation' | 'repairId' | 'sourceFingerprint' | 'backupFingerprint'> {
  const item = view.maintenance;
  if (!item?.preservationVerified || !item.repairId || !item.sourceFingerprint || !item.backupFingerprint) throw new Error('尚未取得已验证的原件保全身份，不能准备替换。');
  return { generation: view.generation, repairId: item.repairId, sourceFingerprint: item.sourceFingerprint, backupFingerprint: item.backupFingerprint };
}
export function bootstrapRetryPayload(view: BootstrapView, requestId: string) {
  if (!view.retryAvailable || view.state === 'initializing') throw new Error('原启动尚未允许重试，请先读取当前状态。');
  const base = { expectedGeneration: view.generation, requestId };
  if (!view.maintenance?.originalRetryAvailable) return base;
  const pin = repairPin(view);
  return { ...base, repairId: pin.repairId, sourceFingerprint: pin.sourceFingerprint, backupFingerprint: pin.backupFingerprint };
}
export function repairRestoration(value: unknown, sourceFingerprint: string): RepairRestoration | null {
  const item = record(value);
  if (item.section !== 'conversations' || item.sourceFingerprint !== sourceFingerprint || item.sourceReadOnly !== true ||
      ['sourceConversationCount', 'conversationCount', 'messageCount', 'omittedCount', 'unreadableConversationCount', 'contentUtf8Bytes', 'manifestBytes'].some(key => !Number.isSafeInteger(item[key]) || item[key] < 0) ||
      ['restoresExistingIds', 'restoresActiveGrants', 'restoresTaskHistory', 'restoresAttachmentBytes', 'restoresCredentialsOrPairings'].some(key => item[key] !== false) ||
      typeof item.omissionMeaning !== 'string' || typeof item.sourceGeneration !== 'string' || !item.sourceGeneration ||
      typeof item.manifestSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(item.manifestSha256) || item.manifestBytes === 0 ||
      !Array.isArray(item.conversations) || item.conversations.length !== item.conversationCount || !Array.isArray(item.gaps) ||
      item.sourceConversationCount !== item.conversationCount + item.unreadableConversationCount ||
      item.partial !== (item.omittedCount > 0 || item.unreadableConversationCount > 0)) return null;
  if (item.conversations.some((entry: unknown) => {
    const row = record(entry);
    return typeof row.sourceConversationId !== 'string' || !row.sourceConversationId || typeof row.restoredConversationId !== 'string' || !row.restoredConversationId || row.sourceConversationId === row.restoredConversationId ||
      ['messageCount', 'omittedCount', 'exportBytes'].some(key => !Number.isSafeInteger(row[key]) || row[key] < 0) ||
      typeof row.exportSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(row.exportSha256);
  }) || item.gaps.some((entry: unknown) => {
    const gap = record(entry);
    return typeof gap.sourceConversationId !== 'string' || !gap.sourceConversationId || typeof gap.reason !== 'string' || !gap.reason ||
      (gap.recordCount !== null && (!Number.isSafeInteger(gap.recordCount) || gap.recordCount < 0));
  }) || (item.partial && item.gaps.length === 0)) return null;
  return item as RepairRestoration;
}
export function repairReviewPayload(view: BootstrapView, mode: RepairMode, requestId: string, includeConversationContent = false) {
  if (!repairModeAllowed(view, mode)) throw new Error('当前原生维护记录没有开放所选方案，请重新读取。');
  if (mode === 'verified_copy' && !includeConversationContent) throw new Error('请先明确选择在本机读取并恢复对话文字。');
  const pin = repairPin(view);
  return { expectedGeneration: pin.generation, requestId, repairId: pin.repairId, sourceFingerprint: pin.sourceFingerprint,
    backupFingerprint: pin.backupFingerprint, mode, ...(mode === 'verified_copy' ? { sections: ['conversations'], includeConversationContent: true } : {}) };
}
export function captureRepairReview(view: BootstrapView, requestedMode: RepairMode = 'empty_database'): RepairReview {
  const review = view.maintenance?.review;
  if (!repairAllowed(view, 'repair.confirm') || !review?.reviewId || review.mode !== requestedMode) throw new Error('当前没有与所选方案一致的原生维修审阅。');
  const pin = repairPin(view);
  const restoration = requestedMode === 'verified_copy' ? repairRestoration(review.restoration, pin.backupFingerprint) : null;
  if (requestedMode === 'verified_copy' && !restoration) throw new Error('所选副本的实际恢复范围或来源无法核对，不能确认，也不会改为空库。');
  return { ...pin, reviewId: review.reviewId, mode: requestedMode, summary: review.summary || '请核对原生维修方案与实际后果。',
    ...(restoration ? { restoration: structuredClone(restoration) } : {}) };
}
export function repairReviewCurrent(view: BootstrapView | null, review: RepairReview): boolean {
  if (!view || !repairAllowed(view, 'repair.confirm')) return false;
  try {
    const pin = repairPin(view);
    return pin.generation === review.generation && pin.repairId === review.repairId && pin.sourceFingerprint === review.sourceFingerprint &&
      pin.backupFingerprint === review.backupFingerprint && view.maintenance?.review?.reviewId === review.reviewId && view.maintenance.review.mode === review.mode &&
      (review.mode !== 'verified_copy' || JSON.stringify(repairRestoration(view.maintenance.review.restoration, pin.backupFingerprint)) === JSON.stringify(review.restoration));
  } catch { return false; }
}
export function repairConfirmPayload(view: BootstrapView | null, review: RepairReview, checked: boolean, requestId: string) {
  if (!checked || !repairReviewCurrent(view, review)) throw new Error('原维修身份已变化或尚未确认后果，请重新核对。');
  return { expectedGeneration: review.generation, requestId, repairId: review.repairId, sourceFingerprint: review.sourceFingerprint,
    backupFingerprint: review.backupFingerprint, reviewId: review.reviewId, confirmed: true };
}
export function repairOutcome(view: BootstrapView | null): string | null {
  if (view?.maintenance?.phase !== 'completed') return null;
  if (view.maintenance.result?.mode === 'empty_database') return '已保全原件并建立空库；原历史未恢复。新库不会继续旧任务或继承旧执行授权。';
  if (view.maintenance.result?.mode === 'verified_copy') {
    const restored = repairRestoration(view.maintenance.result.restoration, view.maintenance.backupFingerprint);
    return restored ? `已保全原件，并以新身份恢复 ${restored.conversationCount} 段对话、${restored.messageCount} 条文字消息。原任务历史、附件原件和执行授权未恢复。` : '本次维修结果已登记，但恢复范围尚无法完整核对。请读取同一维修记录。';
  }
  return view.maintenance.result?.summary || null;
}
