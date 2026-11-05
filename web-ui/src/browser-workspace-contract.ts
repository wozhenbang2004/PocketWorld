import type { PocketSnapshot } from './bridge';
type Data = Record<string, any>;
export const browserWorkspaceObject = (value: unknown): Data => value != null && typeof value === 'object' && !Array.isArray(value) ? value : {};
export const browserWorkspaceRows = (value: unknown): Data[] => Array.isArray(value) ? value.filter(row => row != null && typeof row === 'object' && !Array.isArray(row)) : [];
export type BrowserWorkspaceState = Data & { windowId: string; generation: number; snapshotId: string; ready: boolean; native: Data; draft: Data };
export function browserWorkspaceState(snapshot: PocketSnapshot): BrowserWorkspaceState | null {
  const state = browserWorkspaceObject(snapshot.browserWorkspace);
  if (typeof state.windowId !== 'string' || !state.windowId || !Number.isSafeInteger(state.generation) || state.generation < 0 || typeof state.snapshotId !== 'string' || !state.snapshotId && state.snapshotError !== true) return null;
  return { ...state, native: browserWorkspaceObject(state.native), draft: browserWorkspaceObject(state.draft) } as BrowserWorkspaceState;
}
export function browserWorkspaceContext(state: BrowserWorkspaceState | null, epoch: number): string {
  return state ? JSON.stringify([epoch, state.windowId, state.generation]) : '';
}
export function browserWorkspaceDraftKey(state: BrowserWorkspaceState | null): string {
  return typeof state?.native?.profileId === 'string' && state.native.profileId && typeof state.draft?.contextTaskId === 'string'
    ? JSON.stringify([state.native.profileId, state.draft.contextTaskId]) : '';
}
/** A foreground handshake must not erase text whose native receipt is still unknown. */
export function browserDraftAfterWindow(previous: { key: string; text: string; dirty: boolean }, state: BrowserWorkspaceState | null) {
  const key = browserWorkspaceDraftKey(state) || previous.key;
  const text = typeof state?.draft?.text === 'string' ? state.draft.text : '';
  if (previous.dirty && previous.key && previous.key === key && (state?.draft?.ready !== true || previous.text !== text)) return { ...previous, key };
  return { key, text, dirty: false };
}
export function browserWorkspacePayload(state: BrowserWorkspaceState | null, command: string, fields: Data = {}): Data {
  if (!state?.ready) throw new Error('浏览工作区尚未连接，请重新读取。');
  if (!state.snapshotId && !['refresh', 'backToPage', 'setView', 'editGoal', 'clearGoal', 'retryDraft', 'reviewDraftConflict', 'dismissDraftConflict', 'dismissReview', 'showPagePrompt'].includes(command)) throw new Error('原显示暂不可核对，请先刷新当前工作区。');
  if (['windowId', 'generation', 'snapshotId', 'command'].some(key => Object.hasOwn(fields, key))) throw new Error('操作不能替换原窗口身份。');
  return { windowId: state.windowId, generation: state.generation, snapshotId: state.snapshotId, command, ...fields };
}
export type BrowserWorkspaceActionWindow = {
  state: BrowserWorkspaceState | null; epoch: number; mounted: boolean; visible: boolean; connected: boolean; inFlight: boolean;
};
/** A rendered callback can use only its displayed native frame, including implicit task actions. */
export function browserWorkspaceActionForFrame(state: BrowserWorkspaceState | null, epoch: number, current: () => BrowserWorkspaceActionWindow) {
  const displayed = state ? { ...state } : null, context = browserWorkspaceContext(displayed, epoch);
  return (command: string, fields: Data = {}): Data => {
    const actual = current();
    if (!actual.mounted || !actual.visible || !actual.connected || !actual.state?.ready) throw new Error('原浏览工作区已关闭或离开前台，请重新核对。');
    if (!context || context !== browserWorkspaceContext(actual.state, actual.epoch) || displayed?.snapshotId !== actual.state?.snapshotId) throw new Error('原显示已更新，请核对当前任务后重新操作。');
    if (actual.inFlight) throw new Error('请等待本次原操作的受理回执。');
    if (actual.state?.busy && !['refresh', 'backToPage', 'dismissReview', 'stop'].includes(command)) throw new Error('原请求正在处理，请等待回执。');
    return browserWorkspacePayload(displayed, command, structuredClone(fields));
  };
}
export const emptyBrowserScope = () => ({ autoRead: false, saveReadSources: false, publicSeeds: '', extraFrameOrigins: '', autoActions: false, actionOrigins: '', actionBudget: '60', ordinaryInput: false, interactionClicks: false, interactionSelections: false, interactionChecks: false, interactionTargets: '', selectedInteractionCandidates: [] as Data[], scopeMinutes: 0 });
export type BrowserScopeDraft = ReturnType<typeof emptyBrowserScope>;
export function browserScopeOptions(draft: BrowserScopeDraft): BrowserScopeDraft {
  return { ...draft, saveReadSources: draft.autoRead && draft.saveReadSources, publicSeeds: draft.autoRead ? draft.publicSeeds : '',
    actionOrigins: draft.autoActions ? draft.actionOrigins : '', ordinaryInput: draft.autoActions && draft.ordinaryInput,
    interactionClicks: draft.autoActions && draft.interactionClicks, interactionSelections: draft.autoActions && draft.interactionSelections,
    interactionChecks: draft.autoActions && draft.interactionChecks,
    interactionTargets: draft.autoActions && (draft.interactionClicks || draft.interactionSelections || draft.interactionChecks) ? draft.interactionTargets : '',
    selectedInteractionCandidates: draft.autoActions && (draft.interactionClicks || draft.interactionSelections || draft.interactionChecks) ? draft.selectedInteractionCandidates : [] };
}
