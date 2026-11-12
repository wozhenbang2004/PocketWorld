/** Read-only task evidence. Nothing here is a capability, an approval, or proof of success. */
import { projectPackageOutcome, type ProjectPackageOutcome } from './project-preview';
type Row = Record<string, unknown>;
const row = (value: unknown): Row => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : {};
const text = (value: unknown): string => typeof value === 'string' ? value : '';
const rows = (value: unknown): Row[] => Array.isArray(value) ? value.map(row) : [];
const finite = (value: unknown): number | undefined => typeof value === 'number' && Number.isFinite(value) ? value : undefined;

export interface TaskOutcome {
  text: string;
  source: 'evidence' | 'result-event' | 'task-summary' | 'none';
  /** A runner result can also end an attempt while waiting, paused or blocked. */
  resultStatus?: string;
  image?: string;
  remotePhoneSnapshot?: { image: string; deviceId: string; screenMode: 'main_screen' | 'background'; capturedAt: number; currentRun: boolean; width: number; height: number };
  remotePhoneSnapshotStatus?: 'not_observed' | 'retention_disabled' | 'too_large' | 'save_unconfirmed' | 'saved';
  retained?: { text: string; image?: string };
  evidenceRunBound?: boolean;
  screenshot?: { imageId: string; state: string; currentRun: boolean; encrypted: boolean; legacyRetained: boolean; createdAt?: number; expiresAt?: number; readState: string; deletionPending: boolean };
  /** A linked reply has task identity, but the message protocol has no run identity. */
  reply?: { id: string; text: string; at?: number; attachments: Row[] };
  projectPackages: ProjectPackageOutcome[];
  projectPackagesPartial: boolean;
  partial: boolean;
}

export function getTaskOutcome(input: unknown, messages: unknown = [], now: number = Date.now(), packageRead?: unknown): TaskOutcome {
  const task = row(input), raw = row(task.raw), id = text(task.id), runId = text(raw.runId);
  const page = row(raw.historyEventPage);
  const validPage = !!id && (!text(page.taskId) || page.taskId === id) && (!text(page.section) || page.section === 'events');
  const hasPage = Object.keys(page).length > 0;
  const candidates = hasPage
    ? (validPage ? rows(page.entries).flatMap(entry => entry.status === 'available'
      ? [{ event: row(entry.event), position: finite(entry.position) }] : []) : [])
    : rows(raw.events).map(event => ({ event, position: undefined as number | undefined }));
  // A record of another run, an unread body, and a verification verdict are never the output.
  const results = candidates.filter(({ event }) => runId && event.runId === runId &&
    event.type === 'result' && text(event.message).trim());
  const positioned = results.filter(value => value.position !== undefined);
  const ordered = positioned.length ? positioned : results.filter(value => finite(value.event.at ?? value.event.timestamp) !== undefined);
  const latest = ordered.reduce<typeof ordered[number] | undefined>((best, value) => {
    const rank = (entry: typeof value) => positioned.length ? entry.position! : finite(entry.event.at ?? entry.event.timestamp)!;
    return !best || rank(value) > rank(best) ? value : best;
  }, undefined);
  const evidence = row(raw.evidence);
  const evidenceText = text(evidence.modelSummary) || text(evidence.summary) || text(evidence.message);
  const summary = text(task.summary) || text(raw.summary);
  const proof = row(evidence.nativeFinalAnswer), evidenceRun = text(proof.runId);
  const currentEvidence = !!id && !!runId && proof.format === 'pocketworld.runner-final-answer.v1' &&
    proof.taskId === id && evidenceRun === runId && finite(raw.coordinatorEpoch) !== undefined &&
    proof.coordinatorEpoch === raw.coordinatorEpoch && proof.inputBoundaryEmpty === true;
  // TaskStore retains evidence when a new attempt starts. Unbound/older evidence
  // cannot replace a result event or be presented as the newly running attempt.
  const readingResult = ['needs_confirmation','completed','failed','cancelled','canceled','stopped'].includes(text(task.status) || text(raw.status));
  const useEvidence = !!evidenceText && (currentEvidence || (!latest && !evidenceRun && readingResult));
  const output = useEvidence ? evidenceText : latest ? text(latest.event.message) : summary;
  const imageData = text(evidence.imageDataUrl);
  const imageRecord = row(evidence.screenshot), hasImageRecord = Object.keys(imageRecord).length > 0;
  const imageOwned = imageRecord.format === 'pocketworld.task-screenshot.v1' && imageRecord.taskId === id && !!text(imageRecord.imageId) || imageRecord.imageId === 'legacy:' + id;
  const expiry = finite(imageRecord.expiresAt), expired = expiry !== undefined && expiry > 0 && now >= expiry;
  const screenshot = hasImageRecord && imageOwned ? {
    imageId: text(imageRecord.imageId), state: expired && imageRecord.state !== 'deleted' ? 'expired' : text(imageRecord.state),
    currentRun: !!runId && imageRecord.runId === runId && imageRecord.epoch === raw.coordinatorEpoch,
    encrypted: imageRecord.encrypted === true, legacyRetained: imageRecord.legacyRetained === true,
    createdAt: finite(imageRecord.createdAt), expiresAt: expiry, readState: text(imageRecord.readState), deletionPending: imageRecord.deletionPending === true,
  } : undefined;
  const mayShowImage = !hasImageRecord || screenshot?.state === 'stored' && screenshot.readState !== 'unreadable';
  const image = mayShowImage && /^data:image\/(?:png|jpeg|webp);base64,[a-zA-Z0-9+/=\r\n]+$/.test(imageData) ? imageData : undefined;
  const remote = row(imageRecord.remotePhoneSnapshot), deviceWork = row(raw.ownerDeviceWork);
  const isDeviceWork = deviceWork.format === 'pocketworld.owner-device-work.v1' && deviceWork.requestId === id;
  const hasPhoneScope = rows(row(raw.deviceSourceScope).routes).some(route => ['main_screen', 'background'].includes(text(row(route.resourceBinding).screenMode)));
  const snapshotStatus = row(raw.remotePhoneSnapshotStatus);
  const statusCurrent = snapshotStatus.format === 'pocketworld.owner-device-phone-snapshot-status.v1' && snapshotStatus.runId === runId && snapshotStatus.epoch === raw.coordinatorEpoch;
  const remotePhoneSnapshotStatus: TaskOutcome['remotePhoneSnapshotStatus'] = isDeviceWork && hasPhoneScope
    ? statusCurrent && ['retention_disabled', 'too_large', 'save_unconfirmed', 'saved'].includes(text(snapshotStatus.state))
      ? snapshotStatus.state as TaskOutcome['remotePhoneSnapshotStatus'] : 'not_observed' : undefined;
  const captureTime = finite(remote.capturedAt), width = finite(remote.imageWidthPx), height = finite(remote.imageHeightPx);
  const remotePhoneSnapshot: TaskOutcome['remotePhoneSnapshot'] = image && imageOwned &&
    isDeviceWork &&
    remote.format === 'pocketworld.owner-device-phone-snapshot.v1' && remote.coordinateAuthority === false && remote.liveVideo === false &&
    (remote.screenMode === 'main_screen' || remote.screenMode === 'background') && text(remote.targetDeviceId) &&
    captureTime !== undefined && captureTime > 0 && width !== undefined && width > 0 && height !== undefined && height > 0
    ? {image, deviceId: text(remote.targetDeviceId), screenMode: remote.screenMode, capturedAt: captureTime, currentRun: screenshot?.currentRun === true, width, height}
    : undefined;
  const linked = rows(messages).filter(message => id && (text(message.taskId) || text(row(message.submission).taskId)) === id && message.role === 'assistant' &&
    (text(message.content).trim() || rows(message.attachments).some(file => typeof file.id === 'string' && file.id))).at(-1);
  const attachments = rows(linked?.attachments).filter(file => typeof file.id === 'string' && file.id);
  const replyText = linked && text(linked.content) !== output ? text(linked.content) : '';
  const reply = linked && (replyText || attachments.length) ? {
    id: text(linked.id), text: replyText, at: finite(linked.at), attachments,
  } : undefined;
  const retained = !useEvidence && (evidenceText || image && !remotePhoneSnapshot) ? {text: evidenceText, ...(image && !remotePhoneSnapshot ? {image} : {})} : undefined;
  const packagePage = row(packageRead ?? raw.projectPackageOutcomes), hasPackagePage = Object.keys(packagePage).length > 0;
  const validPackagePage = packagePage.taskId === id && Array.isArray(packagePage.items);
  const packageCandidates = validPackagePage ? rows(packagePage.items) : [];
  const seenPackages = new Set<string>();
  let projectPackagesPartial = hasPackagePage && (!validPackagePage || packagePage.hasMore === true || packagePage.readLimited === true || rows(packagePage.unavailable).length > 0);
  const projectPackages: ProjectPackageOutcome[] = [];
  for (const candidate of packageCandidates) {
    const original = projectPackageOutcome(task, candidate);
    if (!original) { projectPackagesPartial = true; continue; }
    if (!seenPackages.has(original.operationId)) { seenPackages.add(original.operationId); projectPackages.push(original); }
  }
  return { text: output, source: useEvidence ? 'evidence' : latest ? 'result-event' : summary ? 'task-summary' : 'none', reply, ...(screenshot ? {screenshot} : {}),
    projectPackages, projectPackagesPartial,
    ...(latest ? {resultStatus: text(latest.event.status)} : {}),
    ...(useEvidence ? {evidenceRunBound: currentEvidence} : {}),
    ...(image && useEvidence && !remotePhoneSnapshot ? {image} : {}), ...(retained ? {retained} : {}), ...(remotePhoneSnapshot ? {remotePhoneSnapshot} : {}), ...(remotePhoneSnapshotStatus ? {remotePhoneSnapshotStatus} : {}),
    partial: evidence.modelSummaryTruncated === true || evidence.outputTruncated === true || evidence.diffTruncated === true ||
      raw.taskCoreReadLimited === true || hasPage && (!validPage || page.hasMore === true || page.readLimited === true ||
      rows(page.entries).some(entry => entry.status === 'read_limited')) };
}

/** One object entry per task, anchored to its latest loaded related message. */
export function taskMessageAnchors(messages: unknown): Map<string, number> {
  const result = new Map<string, number>();
  rows(messages).forEach((message, index) => { const id = text(message.taskId) || text(row(message.submission).taskId); if (id) result.set(id, index); });
  return result;
}
