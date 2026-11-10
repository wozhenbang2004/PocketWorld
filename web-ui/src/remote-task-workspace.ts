export type RemoteData = Record<string, any>;
export type RemoteEditor = { id: string; revision: number; mode: string; text: string; scope: RemoteData | null };
export const remoteObject = (value: unknown): RemoteData => value != null && typeof value === 'object' && !Array.isArray(value) ? value as RemoteData : {};
export const remoteRows = (value: unknown): RemoteData[] => Array.isArray(value) ? value.filter(row => row != null && typeof row === 'object' && !Array.isArray(row)) : [];
let readHolds = 0;
/** A visible review keeps its exact native digest stable; it never grants execution. */
export function holdRemoteReads(): () => void { readHolds++; let released = false; return () => { if (!released) { released = true; readHolds--; } }; }
export const remoteReadsPaused = () => readHolds > 0;
/** An Android preview must disappear when its placeholder is covered or its own dialog retires. */
export function remotePreviewIsVisible(node: HTMLElement | null, page: Document): boolean {
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
  const rect = node.getBoundingClientRect(), left = Math.max(0, rect.left), top = Math.max(0, rect.top), right = Math.min(host.innerWidth, rect.right), bottom = Math.min(host.innerHeight, rect.bottom);
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
export function managedRemoteRows(value: RemoteData): RemoteData[] {
  return remoteRows(value.managedConnections).flatMap(connection => remoteRows(connection.contexts).flatMap(context =>
    remoteRows(context.sessions).map(row => ({ ...row, connectionLabel: connection.profileId || connection.serviceOrigin,
      environmentLabel: context.environmentId, workspaceLabel: context.workspaceFingerprint }))));
}
export function remoteProgress(snapshot: RemoteData, filter: string) {
  const progress = remoteObject(snapshot.progress);
  const rows = remoteRows(progress.events).filter(row => typeof row.source?.key === 'string' && row.source.executionAuthorized === false && row.source.childSessionControl === false && row.event && typeof row.event === 'object');
  const children = [...new Map(rows.filter(row => row.source.kind === 'child').map(row => [row.source.key, row.source])).values()];
  return { progress, children, visible: rows.filter(row => filter === '*' || row.source.key === filter) };
}

