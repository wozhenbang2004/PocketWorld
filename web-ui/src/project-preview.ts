/** Public presentation metadata only. The native document and source registry own admission. */
export type ProjectPreviewState = {
  kind: 'bundle';
  sessionId: string;
  taskId: string;
  conversationId: string;
  documentGeneration: number;
  ready: boolean;
  selecting: boolean;
  visible: boolean;
  error: string | null;
  packageOperationId?: string;
  selectionAvailable: boolean;
  selectionUnavailableReason: string | null;
  metadata: Record<string, unknown>;
};

export type PreviewWindow = { projectId: string; documentEpoch: number; resumeRevision: number };
export type PreviewRect = { x: number; y: number; width: number; height: number };
export type PackagePreviewTarget = { taskId: string; operationId: string };
export type ProjectPackageOutcome = PackagePreviewTarget & {
  runId: string; epoch: number; projectId: string; checkoutId: string; sourceDigest: string;
  artifactId: string; archiveSha256: string; archiveBytes: number; manifestIdentity: string;
  name: string; mode: 'web_output' | 'source_project'; entryPath: string;
  currentRun: boolean; fileCount?: number; omittedCount?: number; createdAt?: number;
};
export type PackagePreviewChoices = PackagePreviewTarget & {
  kind: 'package_preview_choices'; conversationId: string; projectId: string; checkoutId: string; sourceDigest: string;
  artifactId: string; artifactSha256: string; mode: 'web_output' | 'source_project';
  entryPath: string; entryChoices: string[];
};

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

export function projectPreviewState(value: unknown): ProjectPreviewState | null {
  const source = record(value);
  if (source.kind !== undefined && source.kind !== 'bundle') return null;
  if (typeof source.sessionId !== 'string' || !source.sessionId || typeof source.taskId !== 'string' || !source.taskId ||
      typeof source.conversationId !== 'string' || !source.conversationId ||
      !Number.isSafeInteger(source.documentGeneration) || Number(source.documentGeneration) < 0) return null;
  return {
    kind: 'bundle', sessionId: source.sessionId, taskId: source.taskId, conversationId: source.conversationId,
    documentGeneration: Number(source.documentGeneration), ready: source.ready === true && Number(source.documentGeneration) > 0,
    selecting: source.selecting === true && source.ready === true && Number(source.documentGeneration) > 0, visible: source.visible === true,
    error: typeof source.error === 'string' ? source.error : null,
    ...(typeof source.packageOperationId === 'string' && source.packageOperationId ? { packageOperationId: source.packageOperationId } : {}),
    selectionAvailable: source.selectionAvailable === true,
    selectionUnavailableReason: typeof source.selectionUnavailableReason === 'string' ? source.selectionUnavailableReason : null,
    metadata: record(source.metadata),
  };
}

const identifier = (value: unknown): value is string => typeof value === 'string' && !!value && value.length <= 200;
const sha256 = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const entry = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 240 &&
  value === value.trim() && !value.startsWith('/') && !value.includes('\\') && !/[\u0000-\u001f\u007f]/.test(value) &&
  value.split('/').every(part => part !== '' && part !== '.' && part !== '..');

export function projectPackageTaskIdentity(taskValue: unknown): { taskId: string; projectId: string; checkoutId: string } | null {
  const task = record(taskValue), raw = record(task.raw), development = record(raw.developmentContext);
  return identifier(task.id) && raw.kind === 'assistant' && raw.actorId === 'owner' && raw.spaceId === 'private' && raw.origin == null &&
    identifier(development.projectId) && identifier(development.checkoutId)
    ? { taskId: task.id, projectId: development.projectId, checkoutId: development.checkoutId } : null;
}

/** Metadata from a native original-operation read. Opening still verifies the
 * original SQL, artifact catalog and bytes; this projection grants no access. */
export function projectPackageOutcome(taskValue: unknown, value: unknown): ProjectPackageOutcome | null {
  const target = projectPackageTaskIdentity(taskValue), raw = record(record(taskValue).raw), source = record(value), artifact = record(source.packageArtifact);
  if (!target || source.format !== 'pocketworld.project-package-evidence.v1' || source.taskId !== target.taskId ||
      source.projectId !== target.projectId || source.checkoutId !== target.checkoutId || source.authorityAdded !== false ||
      !identifier(source.operationId) || !identifier(source.runId) || !Number.isSafeInteger(source.coordinatorEpoch) || Number(source.coordinatorEpoch) < 1 ||
      !sha256(source.planDigest) || !sha256(source.sourceDigest) || !sha256(source.manifestIdentity) || !sha256(source.archiveSha256) ||
      !Number.isSafeInteger(source.archiveBytes) || Number(source.archiveBytes) <= 0 ||
      artifact.kind !== 'project_bundle' || artifact.persisted !== true || !uuid(artifact.id) || artifact.versionId !== artifact.id || !uuid(artifact.ownerActionId) ||
      !Number.isSafeInteger(artifact.versionSequence) || Number(artifact.versionSequence) < 1 ||
      artifact.projectId !== target.projectId || artifact.checkoutId !== target.checkoutId || artifact.sourceDigest !== source.sourceDigest ||
      artifact.manifestIdentity !== source.manifestIdentity || artifact.sha256 !== source.archiveSha256 || artifact.sizeBytes !== source.archiveBytes ||
      (artifact.mode !== 'web_output' && artifact.mode !== 'source_project') || typeof artifact.entryPath !== 'string' ||
      (artifact.mode === 'web_output' ? !entry(artifact.entryPath) : artifact.entryPath !== '' && !entry(artifact.entryPath))) return null;
  const count = (value: unknown): number | undefined => Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : undefined;
  return { ...target, operationId: source.operationId, runId: source.runId, epoch: Number(source.coordinatorEpoch),
    sourceDigest: source.sourceDigest, artifactId: artifact.id, archiveSha256: source.archiveSha256, archiveBytes: Number(source.archiveBytes),
    manifestIdentity: source.manifestIdentity, name: typeof artifact.name === 'string' && artifact.name ? artifact.name : '项目固定包',
    mode: artifact.mode, entryPath: artifact.entryPath, currentRun: source.runId === raw.runId && source.coordinatorEpoch === raw.coordinatorEpoch,
    fileCount: count(artifact.fileCount), omittedCount: count(artifact.omittedCount), createdAt: count(artifact.createdAt) };
}

/** A display candidate from the original bounded operation, never artifact authority. */
export function packageOperationTarget(taskValue: unknown, operationValue: unknown): PackagePreviewTarget | null {
  const task = record(taskValue), raw = record(task.raw), development = record(raw.developmentContext), operation = record(operationValue);
  if (!identifier(task.id) || raw.kind !== 'assistant' || raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin != null ||
      !identifier(development.projectId) || !identifier(development.checkoutId) || operation.tool !== 'project_execute' || operation.state !== 'receipt' ||
      operation.readLimited === true || operation.bodyAvailable === false || !identifier(operation.operationId) || !identifier(operation.runId)) return null;
  const receipt = record(operation.receipt), route = record(receipt.projectExecution), actual = record(receipt.executionReceipt);
  if (receipt.code !== 'project_file_receipt' || receipt.status !== 'completed' || receipt.accepted !== true ||
      route.authority !== 'native_host_operation' || route.intent !== 'freeze_package' || route.taskId !== task.id ||
      route.operationId !== operation.operationId || route.runId !== operation.runId || route.projectId !== development.projectId || route.checkoutId !== development.checkoutId ||
      actual.status !== 'completed' || actual.artifactReady !== true || actual.receiptReceived !== true || actual.operationId !== operation.operationId) return null;
  return { taskId: task.id, operationId: operation.operationId };
}

/** Native-only choices are read as metadata; a later open still rechecks the original SQL and bytes. */
export function packagePreviewChoices(value: unknown, expected: PackagePreviewTarget): PackagePreviewChoices | null {
  const source = record(value);
  if (source.kind !== 'package_preview_choices' || source.taskId !== expected.taskId || source.operationId !== expected.operationId ||
      !identifier(source.projectId) || !identifier(source.checkoutId) || !identifier(source.conversationId) ||
      !sha256(source.sourceDigest) || !uuid(source.artifactId) || !sha256(source.artifactSha256) ||
      !['web_output', 'source_project'].includes(String(source.mode)) || typeof source.entryPath !== 'string' ||
      !Array.isArray(source.entryChoices) || source.entryChoices.length > 2000 || !source.entryChoices.every(entry) ||
      new Set(source.entryChoices).size !== source.entryChoices.length || 'sessionId' in source || 'ready' in source) return null;
  if (source.mode === 'web_output' && (!entry(source.entryPath) || source.entryChoices.length !== 1 || source.entryChoices[0] !== source.entryPath)) return null;
  if (source.mode === 'source_project' && source.entryPath !== '') return null;
  return { kind: 'package_preview_choices', ...expected, conversationId: source.conversationId, projectId: source.projectId, checkoutId: source.checkoutId, sourceDigest: source.sourceDigest,
    artifactId: source.artifactId, artifactSha256: source.artifactSha256, mode: source.mode as PackagePreviewChoices['mode'],
    entryPath: source.entryPath, entryChoices: [...source.entryChoices] };
}

export function packagePreviewMatches(view: ProjectPreviewState | null, choices: PackagePreviewChoices, entryPath: string): boolean {
  return !!view && view.taskId === choices.taskId && view.conversationId === choices.conversationId && view.packageOperationId === choices.operationId &&
    view.metadata.origin === 'fixed_workspace_project_artifact' && view.metadata.artifactId === choices.artifactId &&
    view.metadata.artifactSha256 === choices.artifactSha256 && view.metadata.projectId === choices.projectId && view.metadata.checkoutId === choices.checkoutId &&
    view.metadata.sourceDigest === choices.sourceDigest && view.metadata.entryPath === entryPath && choices.entryChoices.includes(entryPath);
}

export function samePreviewWindow(original: PreviewWindow, current: PreviewWindow): boolean {
  return original.projectId === current.projectId && original.documentEpoch === current.documentEpoch &&
    original.resumeRevision === current.resumeRevision;
}

export function previewReturned(value: unknown, original: Pick<ProjectPreviewState, 'sessionId' | 'conversationId'>,
  currentConversationId: unknown, currentComposerConversationId: unknown): boolean {
  const receipt = record(value);
  return receipt.returned === true && receipt.sessionId === original.sessionId &&
    receipt.conversationId === original.conversationId && currentConversationId === original.conversationId &&
    currentComposerConversationId === original.conversationId;
}

/** The rectangle is a layout hint, never an HTML/source/selection authority. */
export function previewViewport(sessionId: string, visible: boolean, rect: PreviewRect | null,
  viewportWidth: number, viewportHeight: number) {
  const validViewport = Number.isFinite(viewportWidth) && viewportWidth > 0 && Number.isFinite(viewportHeight) && viewportHeight > 0;
  const validRect = rect && Object.values(rect).every(Number.isFinite) && rect.width > 0 && rect.height > 0;
  const width = validViewport ? viewportWidth : 0, height = validViewport ? viewportHeight : 0;
  const left = validRect ? Math.max(0, Math.min(width, rect.x)) : 0;
  const top = validRect ? Math.max(0, Math.min(height, rect.y)) : 0;
  const right = validRect ? Math.max(left, Math.min(width, rect.x + rect.width)) : 0;
  const bottom = validRect ? Math.max(top, Math.min(height, rect.y + rect.height)) : 0;
  const shown = visible && validViewport && !!validRect && right > left && bottom > top;
  return { sessionId, visible: shown, rect: shown ? { x: left, y: top, width: right - left, height: bottom - top } : { x: 0, y: 0, width: 0, height: 0 }, viewportWidth: width, viewportHeight: height };
}

export function previewReferenceMessage(available: unknown): string {
  return available === true ? '发送时会重新核对预览和真实源码。' : '原预览已变化或不可用，请重新选择；保留的片段不能直接发送。';
}
