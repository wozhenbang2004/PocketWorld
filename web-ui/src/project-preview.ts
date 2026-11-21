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
export type SourceMember = { path: string; fileSha256: string; bytes: number; artifactId: string; artifactSha256: string; projectId: string; checkoutId: string };
export type SavedWebBuildOrigin = { runId: string; epoch: number; buildOperationId: string };
type SavedBuildWebPick = SavedWebBuildOrigin & { projectId: string; checkoutId: string; sourceDigest: string; artifactId: string; artifactSha256: string };
export type PackagePreviewTarget = { taskId: string; operationId: string; sourceMember?: SourceMember; savedBuildWeb?: SavedBuildWebPick };
export type OriginalVitePreviewTarget = SavedWebBuildOrigin & { taskId: string; projectId: string; checkoutId: string; sourceDigest: string };
export type ProjectPackageOutcome = PackagePreviewTarget & {
  runId: string; epoch: number; projectId: string; checkoutId: string; sourceDigest: string;
  artifactId: string; archiveSha256: string; archiveBytes: number; manifestIdentity: string;
  name: string; mode: 'web_output' | 'source_project'; entryPath: string;
  currentRun: boolean; fileCount?: number; omittedCount?: number; createdAt?: number;
};
export type PackagePreviewChoices = PackagePreviewTarget & {
  kind: 'package_preview_choices'; conversationId: string; projectId: string; checkoutId: string; sourceDigest: string;
  artifactId: string; artifactSha256: string; mode: 'web_output' | 'source_project';
  entryPath: string; entryChoices: string[]; originalBuild?: SavedWebBuildOrigin;
};

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

export function projectPreviewState(value: unknown): ProjectPreviewState | null {
  const source = record(value);
  return source.localWebWork===true?null:bundlePreviewState(source,false);
}

function bundlePreviewState(source:Record<string,unknown>, localWork:boolean): ProjectPreviewState | null {
  if (source.kind !== undefined && source.kind !== 'bundle') return null;
  if (typeof source.sessionId !== 'string' || !source.sessionId || typeof source.taskId !== 'string' || !source.taskId ||
      typeof source.conversationId !== 'string' || (localWork ? source.conversationId!=='' : !source.conversationId) ||
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

/** An exact local Work reader only. Never valid for the project/Chat handoff. */
export function localWebWorkPreview(value:unknown,target:PackagePreviewTarget):ProjectPreviewState|null {
  const source=record(value),saved=target.savedBuildWeb;
  if(!saved||source.kind!=='bundle'||source.localWebWork!==true||source.selectionAvailable!==false||source.selecting!==false)return null;
  const view=bundlePreviewState(source,true),meta=view?.metadata;
  return view&&meta&&view.taskId===target.taskId&&view.packageOperationId===target.operationId&&
    meta.origin==='fixed_workspace_project_artifact'&&meta.projectId===saved.projectId&&meta.checkoutId===saved.checkoutId&&
    meta.sourceDigest===saved.sourceDigest&&meta.artifactId===saved.artifactId&&meta.artifactSha256===saved.artifactSha256&&
    meta.entryPath==='dist/index.html'?view:null;
}

/** Nonpersistent original build evidence; never a fixed archive or Chat preview. */
export function originalViteWorkPreview(value:unknown,target:OriginalVitePreviewTarget):ProjectPreviewState|null {
  const source=record(value);
  if(source.kind!=='bundle'||source.localWebWork!==true||source.selectionAvailable!==false||source.selecting!==false||
    Object.hasOwn(source,'packageOperationId'))return null;
  const view=bundlePreviewState(source,true),meta=view?.metadata;
  return view&&meta&&view.taskId===target.taskId&&meta.origin==='original_local_vite_build'&&meta.persisted===false&&
    meta.authorityAdded===false&&meta.taskId===target.taskId&&meta.runId===target.runId&&meta.epoch===target.epoch&&
    meta.buildOperationId===target.buildOperationId&&meta.projectId===target.projectId&&meta.checkoutId===target.checkoutId&&
    meta.sourceDigest===target.sourceDigest&&meta.entryPath==='dist/index.html'&&meta.previewRoot==='dist'&&
    !Object.hasOwn(meta,'artifactId')&&!Object.hasOwn(meta,'artifactSha256')&&
    sha256(meta.previewId)&&sha256(meta.manifestIdentity)&&sha256(meta.archiveSha256)&&
    Number.isSafeInteger(meta.archiveBytes)&&Number(meta.archiveBytes)>0&&Number(meta.archiveBytes)<=20*1024*1024?view:null;
}

const identifier = (value: unknown): value is string => typeof value === 'string' && !!value && value.length <= 200;
const sha256 = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const entry = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 240 &&
  value === value.trim() && !value.startsWith('/') && !value.includes('\\') && !/[\u0000-\u001f\u007f]/.test(value) &&
  value.split('/').every(part => part !== '' && part !== '.' && part !== '..');

export function projectPackageTaskIdentity(taskValue: unknown): { taskId: string; projectId: string; checkoutId: string } | null {
  const task = record(taskValue), raw = record(task.raw), development = record(raw.developmentContext);
  return identifier(task.id) && raw.id === task.id && raw.contextAvailable !== false && raw.sourceCurrentReadRequired !== true && raw.taskProjection !== 'source_unavailable' && raw.kind === 'assistant' && raw.actorId === 'owner' && raw.spaceId === 'private' && raw.origin == null &&
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
  const savedBuildWeb = artifact.mode === 'web_output' && artifact.entryPath === 'dist/index.html' &&
      source.buildOutputAttribution === 'selected_output_observed_after_build' && sha256(source.buildReceiptIdentity) &&
      identifier(source.buildOperationId) && source.buildOperationId !== source.operationId
    ? { projectId: target.projectId, checkoutId: target.checkoutId, sourceDigest: source.sourceDigest, artifactId: artifact.id,
        artifactSha256: source.archiveSha256, runId: source.runId, epoch: Number(source.coordinatorEpoch), buildOperationId: source.buildOperationId }
    : undefined;
  return { ...target, operationId: source.operationId, runId: source.runId, epoch: Number(source.coordinatorEpoch),
    sourceDigest: source.sourceDigest, artifactId: artifact.id, archiveSha256: source.archiveSha256, archiveBytes: Number(source.archiveBytes),
    manifestIdentity: source.manifestIdentity, name: typeof artifact.name === 'string' && artifact.name ? artifact.name : '项目固定包',
    mode: artifact.mode, entryPath: artifact.entryPath, currentRun: source.runId === raw.runId && source.coordinatorEpoch === raw.coordinatorEpoch,
    fileCount: count(artifact.fileCount), omittedCount: count(artifact.omittedCount), createdAt: count(artifact.createdAt), savedBuildWeb };
}

/** The private work audit can point at a settled owner save. Native inspect
 * must still reread the original build, archive and chosen entry. */
function savedBuildWebAuditTarget(task: unknown, operation: Record<string, unknown>): PackagePreviewTarget | null {
  const owner = projectPackageTaskIdentity(task), pin = record(operation.arguments), artifact = record(operation.receipt);
  if (!owner || operation.bodyAvailable !== true || operation.requiresReconciliation === true ||
      !uuid(operation.operationId) || !identifier(operation.runId) || pin.operationId !== operation.operationId ||
      pin.taskId !== owner.taskId || pin.runId !== operation.runId || pin.projectId !== owner.projectId ||
      pin.checkoutId !== owner.checkoutId || !Number.isSafeInteger(pin.epoch) || Number(pin.epoch) < 1 ||
      !identifier(pin.buildOperationId) || pin.buildOperationId === operation.operationId ||
      !sha256(pin.buildReceiptIdentity) || !sha256(pin.planDigest) || !sha256(pin.sourceDigest) || !sha256(pin.archiveSha256) ||
      !sha256(pin.manifestIdentity) || !Number.isSafeInteger(pin.archiveBytes) || Number(pin.archiveBytes) <= 0 ||
      artifact.kind !== 'project_bundle' || artifact.persisted !== true || artifact.mode !== 'web_output' ||
      artifact.entryPath !== 'dist/index.html' || !uuid(artifact.id) || artifact.id !== artifact.versionId ||
      artifact.projectId !== owner.projectId || artifact.checkoutId !== owner.checkoutId || artifact.sourceDigest !== pin.sourceDigest ||
      artifact.sha256 !== pin.archiveSha256 || artifact.sizeBytes !== pin.archiveBytes || artifact.manifestIdentity !== pin.manifestIdentity)
    return null;
  return { taskId: owner.taskId, operationId: operation.operationId, savedBuildWeb: { projectId: owner.projectId, checkoutId: owner.checkoutId,
    sourceDigest: pin.sourceDigest, artifactId: artifact.id, artifactSha256: pin.archiveSha256, runId: operation.runId,
    epoch: Number(pin.epoch), buildOperationId: pin.buildOperationId } };
}

/** A display candidate from the original bounded operation, never artifact authority. */
export function packageOperationTarget(taskValue: unknown, operationValue: unknown): PackagePreviewTarget | null {
  const task = record(taskValue), raw = record(task.raw), development = record(raw.developmentContext), operation = record(operationValue);
  if (operation.tool === 'owner_keep_first_node_web_output' && operation.state === 'receipt' && operation.readLimited !== true) {
    return savedBuildWebAuditTarget(taskValue, operation);
  }
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
  const member = expected.sourceMember;
  if (member && (source.mode !== 'source_project' || !entry(member.path) || !sha256(member.fileSha256) || !Number.isSafeInteger(member.bytes) ||
      member.bytes < 0 || member.bytes > 65536 || source.projectId !== member.projectId || source.checkoutId !== member.checkoutId ||
      source.artifactId !== member.artifactId || source.artifactSha256 !== member.artifactSha256)) return null;
  if (source.mode === 'web_output' && (!entry(source.entryPath) || source.entryChoices.length !== 1 || source.entryChoices[0] !== source.entryPath)) return null;
  if (source.mode === 'source_project' && source.entryPath !== '') return null;
  let originalBuild: SavedWebBuildOrigin | undefined;
  if (source.originalBuild !== undefined) {
    const build = record(source.originalBuild);
    if (source.mode !== 'web_output' || source.entryPath !== 'dist/index.html' || !identifier(build.runId) ||
        !Number.isSafeInteger(build.epoch) || Number(build.epoch) < 1 || !identifier(build.buildOperationId) ||
        build.buildOperationId === expected.operationId) return null;
    originalBuild = { runId: build.runId, epoch: Number(build.epoch), buildOperationId: build.buildOperationId };
  }
  const saved = expected.savedBuildWeb;
  if (saved && (source.mode !== 'web_output' || source.entryPath !== 'dist/index.html' || source.projectId !== saved.projectId ||
      source.checkoutId !== saved.checkoutId || source.sourceDigest !== saved.sourceDigest || source.artifactId !== saved.artifactId ||
      source.artifactSha256 !== saved.artifactSha256 || originalBuild && (originalBuild.runId !== saved.runId ||
        originalBuild.epoch !== saved.epoch || originalBuild.buildOperationId !== saved.buildOperationId))) return null;
  return { kind: 'package_preview_choices', ...expected, conversationId: source.conversationId, projectId: source.projectId, checkoutId: source.checkoutId, sourceDigest: source.sourceDigest,
    artifactId: source.artifactId, artifactSha256: source.artifactSha256, mode: source.mode as PackagePreviewChoices['mode'],
    entryPath: source.entryPath, entryChoices: [...source.entryChoices], ...(originalBuild ? {originalBuild} : {}) };
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
