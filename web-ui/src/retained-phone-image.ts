// All identities here describe a read, never a current screen, control capability or permission to get another image.
import { bridge } from './bridge';
import { isPrivateLocalPhone } from './PhonePauseInstruction';

type Row = Record<string, any>;
export type ImageProof = { taskId: string; run: string; epoch: number; id: string; revision: number; digest: string;
  file: string; hash: string; expires: number };
const sha = (s: unknown) => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s);
const uuid = (s: unknown) => typeof s === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(s);

export function retainedImageProof(task: Row, src: string): ImageProof | null {
  if (!isPrivateLocalPhone(task)) return null;
  const raw = task.raw, ev = raw.evidence, img = ev?.screenshot;
  if (!img || img.format !== 'pocketworld.task-screenshot.v1' || img.taskId !== task.id ||
      img.runId !== raw.runId || img.epoch !== raw.coordinatorEpoch || !uuid(img.imageId) || img.encrypted !== true ||
      img.legacyRetained !== false || img.retainedFromPreviousResult === true || img.deletionPending === true ||
      img.state !== 'stored' || img.readState !== 'available' || img.remotePhoneSnapshot != null ||
      !Number.isSafeInteger(img.expiresAt) || img.expiresAt <= Date.now() ||
      !Number.isSafeInteger(raw.resultRevision) || raw.resultRevision <= 0 || !sha(raw.resultImageDigest) || !sha(ev.imageSha256) ||
      typeof ev.imageFile !== 'string' || !ev.imageFile || ev.imageDataUrl !== src ||
      !['image/jpeg','image/png','image/webp'].includes(img.mimeType) || !src.startsWith('data:' + img.mimeType + ';base64,')) return null;
  return { taskId: task.id, run: raw.runId, epoch: raw.coordinatorEpoch, id: img.imageId, revision: raw.resultRevision,
    digest: raw.resultImageDigest, file: ev.imageFile, hash: ev.imageSha256, expires: img.expiresAt };
}

/** Projected headers deliberately omit image bytes. Their original result digest covers the stored
 * evidence too; an absent, newer or explicitly unreadable/deleting record must not keep a detail open. */
export function currentImageWindow(p: ImageProof): string {
  const state = bridge.getState(), view = state.view as {section?: string|null; taskId?: string|null; resumeRevision?: number} | undefined;
  if (document.hidden || Date.now() >= p.expires || !view || view.section != null || view.taskId !== p.taskId ||
      !Number.isSafeInteger(view.resumeRevision) || bridge.getDocumentEpoch() <= 0) return '';
  const task = Array.isArray(state.state.tasks) && state.state.tasks.find((r: Row) => r?.id === p.taskId);
  if (!task || !isPrivateLocalPhone(task)) return '';
  const raw = task.raw, ev = raw.evidence, img = ev?.screenshot;
  if (raw.runId !== p.run || raw.coordinatorEpoch !== p.epoch || raw.resultImageDigest !== p.digest ||
      raw.resultRevision !== p.revision || ev?.imageSha256 !== p.hash || ev?.imageFile !== p.file) return '';
  if (img == null && raw.taskProjection !== 'summary') return '';
  if (img != null && (img.imageId !== p.id || img.taskId !== p.taskId || img.runId !== p.run || img.epoch !== p.epoch ||
      img.format !== 'pocketworld.task-screenshot.v1' || img.encrypted !== true || img.legacyRetained !== false ||
      img.state !== 'stored' || img.readState === 'unreadable' || img.deletionPending === true || img.retainedFromPreviousResult === true ||
      img.expiresAt !== p.expires || img.remotePhoneSnapshot != null)) return '';
  return JSON.stringify([bridge.getDocumentEpoch(), view.resumeRevision, view.taskId]);
}

export function subscribeImageWindow(check: () => void) {
  const offState = bridge.subscribe(check), offDocument = bridge.subscribeDocumentEpoch(check);
  document.addEventListener('visibilitychange', check);
  return () => { offState(); offDocument(); document.removeEventListener('visibilitychange',check); };
}
