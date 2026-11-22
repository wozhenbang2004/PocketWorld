import type { PreviewRect } from './project-live-preview';

export type RemoteData = Record<string, any>;
export type RemoteEditor = { id: string; revision: number; mode: string; text: string; scope: RemoteData | null };
export const remoteObject = (value: unknown): RemoteData => value != null && typeof value === 'object' && !Array.isArray(value) ? value as RemoteData : {};
export const remoteRows = (value: unknown): RemoteData[] => Array.isArray(value) ? value.filter(row => row != null && typeof row === 'object' && !Array.isArray(row)) : [];
let readHolds = 0;
/** A visible review keeps its exact native digest stable; it never grants execution. */
export function holdRemoteReads(): () => void { readHolds++; let released = false; return () => { if (!released) { released = true; readHolds--; } }; }
export const remoteReadsPaused = () => readHolds > 0;
/** An Android preview must disappear when its placeholder is covered or its own dialog retires. */
export function remotePreviewIsVisible(node: HTMLElement | null, page: Document, visibleRect?: PreviewRect): boolean {
  if (!node?.isConnected || page.hidden || node.closest('[hidden],[inert],[aria-hidden="true"]')) return false;
  const host = page.defaultView;
  if (!host) return false;
  for (let parent: HTMLElement | null = node; parent; parent = parent.parentElement) {
    const style = host.getComputedStyle(parent);
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) === 0) return false;
  }
  const dialogs = [...page.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]')].filter(dialog => {
    if (dialog.closest('[hidden],[inert],[aria-hidden="true"]') || !dialog.getClientRects().length) return false;
    const style = host.getComputedStyle(dialog); return style.display !== 'none' && style.visibility !== 'hidden';
  });
  const ownDialog = node.closest('[role="dialog"][aria-modal="true"]');
  if (dialogs.length && dialogs.at(-1) !== ownDialog) return false;
  // Hit-test the portion still inside the dialog's scroll viewport, not its hidden center.
  const rect = visibleRect || node.getBoundingClientRect(), left = Math.max(0, rect.x), top = Math.max(0, rect.y), right = Math.min(host.innerWidth, rect.x + rect.width), bottom = Math.min(host.innerHeight, rect.y + rect.height);
  if (right <= left || bottom <= top) return false;
  const hit = page.elementFromPoint((left + right) / 2, (top + bottom) / 2);
  return !!hit && (hit === node || node.contains(hit));
}
export function remoteApprovalClaim(snapshot: RemoteData, requestId: string) {
  return remoteRows(snapshot.task?.operations).find(operation => operation.tool === 'remote_approval' && operation.runId === snapshot.task?.runId && operation.arguments?.requestId === requestId);
}
export function sameRemoteScope(first: unknown, second: unknown): boolean {
  const a = remoteObject(first), b = remoteObject(second), keys = Object.keys(a);
  return keys.length > 0 && keys.length === Object.keys(b).length && keys.every(key => Object.hasOwn(b, key) && a[key] === b[key]);
}
export function remoteEditor(snapshot: RemoteData, id: string, mode?: string): RemoteEditor {
  const scopes = remoteObject(snapshot.draftScopes);
  const selected = mode ?? (scopes.next_turn ? 'next_turn' : scopes.steer ? 'steer' : 'next_turn');
  const scope = scopes[selected];
  return { id, revision: 0, mode: selected, text: '', scope: scope ? { ...scope } : null };
}
export function savedRemoteEditor(draft: RemoteData): RemoteEditor {
  return { id: draft.id, revision: draft.revision, mode: draft.mode, text: draft.text || '', scope: { ...remoteObject(draft.scope) } };
}
export function sameRemoteEditor(first: RemoteEditor, second: RemoteEditor): boolean {
  return first.id === second.id && first.revision === second.revision && first.mode === second.mode && first.text === second.text && sameRemoteScope(first.scope, second.scope);
}
export function remoteDraftState(snapshot: RemoteData, editor: RemoteEditor) {
  const delivery = remoteObject(snapshot.inputDelivery), current = remoteObject(delivery.currentTurn), next = remoteObject(delivery.nextTurn);
  const rows = remoteRows(snapshot.inputDrafts), saved = rows.find(row => row.id === editor.id);
  const modeMounted = editor.mode === 'next_turn' ? next.adapterMounted === true : editor.mode === 'steer' && current.adapterMounted === true;
  const sameTarget = !snapshot.teamRemoteWriter && snapshot.draftEditorAvailable !== false && delivery.protocolEvidenceReported === true && modeMounted &&
    sameRemoteScope(editor.scope, remoteObject(snapshot.draftScopes)[editor.mode]) &&
    (saved ? saved.targetCurrent === true && saved.revision === editor.revision && saved.mode === editor.mode &&
      ['draft', 'queued'].includes(saved.state) && sameRemoteScope(saved.scope, editor.scope) : editor.revision === 0);
  const fresh = snapshot.fresh === true && typeof snapshot.reviewId === 'string' && !!snapshot.reviewId && typeof snapshot.windowHandle === 'string' && !!snapshot.windowHandle;
  const validText = editor.text.trim().length > 0 && editor.text.length <= 12000 && !editor.text.includes('\0');
  const unchanged = !!saved && saved.text === editor.text && saved.mode === editor.mode && saved.revision === editor.revision;
  const available = editor.mode === 'steer' ? current.observedAvailable === true : next.observedAvailable === true && snapshot.canContinue === true;
  // Withdrawal only retires this exact unsent local draft. A newer remote turn
  // must not make old drafts impossible to remove from the bounded queue.
  const canWithdraw = fresh && !snapshot.teamRemoteWriter && !!saved && saved.revision === editor.revision &&
    saved.mode === editor.mode && ['draft', 'queued'].includes(saved.state) && sameRemoteScope(saved.scope, editor.scope);
  return { saved, sameTarget, fresh, validText, unchanged, canSave: sameTarget && fresh && validText,
    canWithdraw, canPaste: sameTarget && fresh,
    canSend: sameTarget && fresh && unchanged && saved?.state === 'queued' && available };
}
export function appendRemoteClipboard(existing: string, value: unknown): string {
  const receipt = remoteObject(value), imported = receipt.text;
  if (receipt.readOnce !== true || typeof imported !== 'string' || !imported.trim() || imported.length > 12000 || imported.includes('\0')) throw new Error('本次没有返回可核对的纯文本，原编辑文字保留。');
  const separator = !existing || existing.endsWith('\n') ? '' : '\n';
  if (existing.length + separator.length + imported.length > 12000) throw new Error('追加后超过 12000 字符；原编辑文字保留，未截断、未发送。');
  return existing + separator + imported;
}
type ManagedRemoteRow = RemoteData & { connectionLabel: string; environmentLabel: string; storedContext?: { key: string; host: string } };
const managedKey = (value: unknown): value is string => typeof value === 'string' && value.length === 64 && /^[a-f0-9]{64}$/.test(value);
const managedLabel = (value: unknown) => typeof value === 'string' && value === value.trim() && /^[A-Za-z0-9_-]{1,80}$/.test(value) ? value : '';
function storedRemoteHost(origin: unknown): string {
  // A saved companion endpoint is not a verified hardware identity. Display only an address, never a URL/path.
  if (typeof origin !== 'string' || origin.length > 270 || origin !== origin.trim() ||
      !/^https?:\/\/(?:[a-z0-9.-]+|\[[a-f0-9:.]+\])(?::[0-9]{1,5})?$/i.test(origin)) return '';
  try {
    const url = new URL(origin);
    return url.port !== '0' && (url.protocol === 'https:' || ['localhost','127.0.0.1','[::1]'].includes(url.hostname)) ? url.host : '';
  } catch { return ''; }
}
export function managedRemoteRows(value: RemoteData): ManagedRemoteRow[] {
  const local = value.dataOnly === true && value.authorizesExecution === false &&
    value.observation === 'last_stored_native_metadata_not_live' && value.deviceIdentitySource === 'configured_companion_endpoint_not_hardware_identity';
  return remoteRows(value.managedConnections).flatMap(connection => {
    const host = storedRemoteHost(connection.serviceOrigin);
    const connectionLabel = managedLabel(connection.profileId) || (host ? connection.serviceOrigin : '原连接');
    return remoteRows(connection.contexts).flatMap(context => remoteRows(context.sessions).map(row => {
      const bound = local && connection.deviceIdentityState === 'not_observed_endpoint_only' && row.deviceIdentityState === connection.deviceIdentityState &&
        row.provenance === 'original_taskstore_last_observation' && row.dataOnly === true && row.authorizesExecution === false &&
        managedKey(row.rowIdentity) && managedKey(connection.connectionKey) && managedKey(context.contextKey) &&
        row.connectionKey === connection.connectionKey && row.contextKey === context.contextKey &&
        row.profileId === connection.profileId && row.serviceOrigin === connection.serviceOrigin &&
        row.environmentId === context.environmentId && row.workspaceFingerprint === context.workspaceFingerprint;
      return { ...row, connectionLabel, environmentLabel: managedLabel(context.environmentId),
        storedContext: bound ? { key: JSON.stringify([connection.connectionKey, context.contextKey]), host } : undefined };
    }));
  });
}
export function managedRemoteGroups(rows: ManagedRemoteRow[]) {
  type Group = { key: string; label: string; connection: string; environment: string; rows: ManagedRemoteRow[]; detail: string };
  const groups = new Map<string, Group>();
  for (const row of rows) {
    const label = JSON.stringify([row.connectionLabel, row.environmentLabel]);
    const key = row.storedContext ? 'stored:' + row.storedContext.key : 'legacy:' + label;
    const group: Group = groups.get(key) ?? { key, label, connection: row.connectionLabel, environment: row.environmentLabel, rows: [], detail: '' };
    group.rows.push(row);
    groups.set(key, group);
  }
  const ordered = [...groups.values()];
  for (const group of ordered) {
    const original = group.rows[0].storedContext;
    if (!original) continue;
    const peers = ordered.filter(other => other.label === group.label && other.rows[0].storedContext);
    if (peers.length < 2) continue;
    const uniqueHost = original.host && peers.every(other => {
      const host = other.rows[0].storedContext?.host;
      return other === group || (!!host && host !== original.host);
    });
    group.detail = uniqueHost ? original.host : `本机记录 ${peers.indexOf(group) + 1}/${peers.length}`;
  }
  return ordered;
}
export function remoteProgress(snapshot: RemoteData, filter: string) {
  const progress = remoteObject(snapshot.progress);
  const rows = remoteRows(progress.events).filter(row => typeof row.source?.key === 'string' && row.source.executionAuthorized === false && row.source.childSessionControl === false && row.event && typeof row.event === 'object');
  const children = [...new Map(rows.filter(row => row.source.kind === 'child').map(row => [row.source.key, row.source])).values()];
  return { progress, children, visible: rows.filter(row => filter === '*' || row.source.key === filter) };
}

