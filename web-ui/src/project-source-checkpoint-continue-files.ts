import { bridge, type PocketSnapshot } from './bridge';
import { checkedCheckpointComparison, type Comparison, type Scope, type Version } from './ProjectSourceCheckpointScreen';

export const checkpointContinuePrefix = 'projects.development.files.checkpoints.continue.';
export const checkpointContinueCommands = ['review', 'confirm', 'dismiss', 'query', 'openFiles', 'returnFiles'] as const;
export type CheckpointContinueAction = typeof checkpointContinueCommands[number];
export const hasCheckpointContinue = (snapshot: PocketSnapshot = bridge.getState()) =>
  checkpointContinueCommands.every(command => snapshot.capabilities.commands.includes(checkpointContinuePrefix + command));

type Data = Record<string, unknown>;
export const checkpointWire = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
export const checkpointHandle = (value: unknown): value is string => typeof value === 'string' &&
  /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const hash = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

type Origin = Readonly<{ scope: Scope; before: Version | null; selected: Version }>;
type Resume = Readonly<{ visitId: string; shelfHandle: string; scope: Scope; comparison: Comparison | null }>;
type Arrival = Readonly<{
  viewHandle: string; transferHandle: string; projectId: string; checkoutId: string;
  previousFilesHandle: string; filesHandle: string; listing: Data;
}>;
export type CheckpointSiblingFiles = Arrival & Readonly<{ side: 'sibling'; original: Origin }>;
export type ReturnedCheckpointFiles = Arrival & Readonly<{ side: 'original'; fromComparison: boolean; resume: Resume | null }>;
export type CheckpointFilesArrival = CheckpointSiblingFiles | ReturnedCheckpointFiles;

/** These are only native renderer receipts. A Project owner must still accept
 * the exact prior child before it can replace the old Files page. */
function exactArrival(raw: Data, viewHandle: string, previous: string, projectId: string, checkoutId: string): Arrival {
  const files = checkpointWire(raw.files), listing = checkpointWire(files.listing);
  if (raw.viewHandle !== viewHandle || !checkpointHandle(raw.transferHandle) || files.previousFilesHandle !== previous ||
      files.projectId !== projectId || files.checkoutId !== checkoutId || !checkpointHandle(files.filesHandle) ||
      files.filesHandle === previous || listing.filesHandle !== files.filesHandle || listing.projectId !== projectId ||
      listing.checkoutId !== checkoutId || listing.readOnly !== true || !hash(listing.sourceDigest) ||
      !Array.isArray(listing.files) || listing.files.length > 40 || typeof listing.fileCount !== 'number' ||
      !Number.isSafeInteger(listing.fileCount) || listing.fileCount < 0 || typeof listing.nextPath !== 'string') throw Error();
  return { viewHandle, transferHandle: raw.transferHandle, projectId, checkoutId, previousFilesHandle: previous,
    filesHandle: files.filesHandle, listing };
}

export function checkedSiblingFiles(raw: Data, viewHandle: string, previous: string, checkoutId: string,
                                    original: Origin): CheckpointSiblingFiles {
  if (raw.status !== 'OPENED' || checkoutId === original.scope.checkoutId) throw Error();
  return { ...exactArrival(raw, viewHandle, previous, original.scope.projectId, checkoutId), side: 'sibling', original };
}

export async function returnCheckpointFiles(sibling: CheckpointSiblingFiles): Promise<ReturnedCheckpointFiles | null> {
  const { viewHandle, filesHandle, transferHandle, original } = sibling;
  const raw = checkpointWire(await bridge.command(checkpointContinuePrefix + 'returnFiles', { viewHandle, filesHandle, transferHandle }));
  if (raw.transferHandle !== transferHandle || raw.viewHandle !== viewHandle) throw Error();
  if ((raw.status === 'STOCK_CLOSE' || raw.status === 'ORIGINAL_UNAVAILABLE') && raw.filesHandle === filesHandle) return null;
  if (raw.status !== 'FILES' || !Object.hasOwn(raw, 'resume')) throw Error();
  const arrival = exactArrival(raw, viewHandle, filesHandle, original.scope.projectId, original.scope.checkoutId);
  const returned = { ...arrival, side: 'original' as const, fromComparison: original.before !== null };
  if (raw.resume === null) return { ...returned, resume: null };
  const { scope, before, selected } = original;
  if (!before) throw Error();
  const r = checkpointWire(raw.resume), s = checkpointWire(r.scope);
  if (r.opened !== true || r.viewHandle !== viewHandle || r.filesHandle !== arrival.filesHandle || !checkpointHandle(r.visitId) ||
      !checkpointHandle(r.shelfHandle) || s.projectId !== arrival.projectId || s.checkoutId !== arrival.checkoutId ||
      s.projectName !== original.scope.projectName || !Object.hasOwn(r, 'comparison')) throw Error();
  let comparison: Comparison | null = null;
  if (r.comparison !== null) {
    try { comparison = checkedCheckpointComparison(scope, before, selected, { ...checkpointWire(r.comparison), scope } as Comparison); }
    catch { /* The actual A Files is still known; its comparison isn't. */ }
  }
  return { ...returned, resume: { visitId: r.visitId, shelfHandle: r.shelfHandle, scope, comparison } };
}
