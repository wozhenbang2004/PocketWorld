/** Presentation contract for the native browser's toolbar, never the webpage itself. */
export const BROWSER_CHROME_INPUT_LIMIT = 8192;
export const BROWSER_CHROME_ACTIONS = ['navigate', 'back', 'forward', 'reload', 'return_owner', 'tabs', 'research', 'sources', 'more'] as const;
export type BrowserChromeAction = typeof BROWSER_CHROME_ACTIONS[number];
export type BrowserChromeState = {
  ready: boolean; windowId: string; generation: number; contextToken: string;
  address: string; title: string; status: string; loading: boolean; progress: number;
  canBack: boolean; canForward: boolean; pageCount: number;
  researchAvailable: boolean; pageAvailable: boolean; navigationUnconfirmed: boolean; navigationReviewRevision: number;
};
export type BrowserChromePin = Pick<BrowserChromeState, 'windowId' | 'generation' | 'contextToken'>;
export type BrowserChromeContext = BrowserChromePin & { documentEpoch: number };
export type BrowserChromeDraft = { context: BrowserChromeContext | null; value: string; editing: boolean; dirty: boolean };
export type BrowserChromePayload = BrowserChromePin & { action: BrowserChromeAction; input?: string };
export type BrowserChromeNotice = { kind: 'not_sent' | 'unconfirmed'; text: string };
type Snapshot = { surface?: unknown; host?: Record<string, unknown>; browserChrome?: unknown };

export function browserChromeSurface(snapshot: Snapshot): boolean {
  return snapshot.surface === 'browser_chrome' || snapshot.host?.surface === 'browser_chrome';
}
export function browserChromeState(snapshot: Snapshot): BrowserChromeState | null {
  if (!browserChromeSurface(snapshot)) return null;
  const value = snapshot.browserChrome;
  if (!value || typeof value !== 'object') return null;
  const state = value as BrowserChromeState;
  if (!['ready', 'loading', 'canBack', 'canForward', 'researchAvailable', 'pageAvailable', 'navigationUnconfirmed'].every(key => typeof (value as Record<string, unknown>)[key] === 'boolean') ||
      !['windowId', 'contextToken', 'address', 'title', 'status'].every(key => typeof (value as Record<string, unknown>)[key] === 'string') ||
      !Number.isSafeInteger(state.generation) || state.generation < 0 ||
      !Number.isSafeInteger(state.navigationReviewRevision) || state.navigationReviewRevision < 0 || !Number.isSafeInteger(state.pageCount) || state.pageCount < 0 ||
      !Number.isFinite(state.progress) || state.address.length > BROWSER_CHROME_INPUT_LIMIT ||
      !state.windowId || !state.contextToken || state.windowId.length > 256 || state.contextToken.length > 512) return null;
  return state;
}
export function browserChromePin(state: BrowserChromeState): BrowserChromePin {
  if (!state.ready || !state.windowId || !state.contextToken || !Number.isSafeInteger(state.generation) || state.generation < 0)
    throw new Error('浏览器工具栏尚未与当前网页绑定。');
  return { windowId: state.windowId, generation: state.generation, contextToken: state.contextToken };
}
export function sameBrowserChrome(pin: BrowserChromePin, state: BrowserChromeState | null | undefined): boolean {
  return !!state?.ready && pin.windowId === state.windowId && pin.generation === state.generation && pin.contextToken === state.contextToken;
}
export function browserChromeContext(state: BrowserChromeState, documentEpoch: number): BrowserChromeContext {
  if (!Number.isSafeInteger(documentEpoch) || documentEpoch < 0) throw new Error('浏览器文档尚未就绪。');
  return { ...browserChromePin(state), documentEpoch };
}
export function browserChromeContextCurrent(context: BrowserChromeContext | null, state: BrowserChromeState | null | undefined, documentEpoch: number): boolean {
  return !!context && context.documentEpoch === documentEpoch && sameBrowserChrome(context, state);
}
export function emptyBrowserChromeDraft(): BrowserChromeDraft {
  return { context: null, value: '', editing: false, dirty: false };
}
/** Preserve an in-progress edit only inside the same live native page/document binding. */
export function reconcileBrowserChromeDraft(draft: BrowserChromeDraft, state: BrowserChromeState | null, documentEpoch: number, foreground = true): BrowserChromeDraft {
  if (!foreground || !state?.ready) return draft.context || draft.value || draft.editing || draft.dirty ? emptyBrowserChromeDraft() : draft;
  if (!browserChromeContextCurrent(draft.context, state, documentEpoch))
    return { context: browserChromeContext(state, documentEpoch), value: state.address, editing: false, dirty: false };
  if (draft.editing || draft.dirty || draft.value === state.address) return draft;
  return { ...draft, value: state.address };
}
export function editBrowserChromeDraft(draft: BrowserChromeDraft, state: BrowserChromeState, documentEpoch: number, value: string): BrowserChromeDraft {
  if (!browserChromeContextCurrent(draft.context, state, documentEpoch)) throw new Error('网页已变化，请重新输入地址或搜索词。');
  if (value.length > BROWSER_CHROME_INPUT_LIMIT) throw new Error('地址或搜索词不能超过 8192 个字符。');
  return { ...draft, value, editing: true, dirty: true };
}
export function browserChromeActionAvailable(state: BrowserChromeState | null, action: BrowserChromeAction, localUnconfirmed = false): boolean {
  if (!state?.ready || !(BROWSER_CHROME_ACTIONS as readonly string[]).includes(action)) return false;
  if ((state.navigationUnconfirmed || localUnconfirmed) && ['navigate', 'back', 'forward', 'reload'].includes(action)) return false;
  if (action === 'back') return state.canBack;
  if (action === 'forward') return state.canForward;
  if (action === 'navigate') return state.pageAvailable;
  // Reload is also the existing explicit native recovery entry after renderer loss.
  if (action === 'research' || action === 'sources') return state.researchAvailable;
  return true;
}
/** Only the native endpoint interprets URLs/search text and targets the actual webpage. */
export function browserChromeActionPayload(state: BrowserChromeState, action: BrowserChromeAction, draft?: BrowserChromeDraft, documentEpoch?: number): BrowserChromePayload {
  if (!browserChromeActionAvailable(state, action)) throw new Error('当前网页不可执行此操作。');
  const pin = browserChromePin(state);
  if (action !== 'navigate') {
    if (draft !== undefined) throw new Error('只有打开地址可携带输入。');
    return { ...pin, action };
  }
  if (!draft || !browserChromeContextCurrent(draft.context, state, documentEpoch ?? -1)) throw new Error('地址草稿所属的网页已变化，请重新输入。');
  if (!draft.value.trim() || draft.value.length > BROWSER_CHROME_INPUT_LIMIT || /[\u0000\r\n]/.test(draft.value))
    throw new Error('请输入不超过 8192 个字符的单行地址或搜索词。');
  return { ...pin, action, input: draft.value.trim() };
}
/** A transport success is not necessarily a native acceptance, nor a successful page load. */
export function browserChromeAccepted(value: unknown): boolean {
  return !!value && typeof value === 'object' && (value as Record<string, unknown>).accepted === true;
}
export function browserChromeFailure(error?: unknown): BrowserChromeNotice {
  const notSent = !!error && typeof error === 'object' && (error as { code?: unknown }).code === 'not_sent';
  return notSent
    ? { kind: 'not_sent', text: '操作未发送。请核对当前网页后再操作。' }
    : { kind: 'unconfirmed', text: '结果尚未确认。请先查看网页，不要连续重复操作。' };
}
export function browserChromeProgress(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value))) : 0;
}
export function browserChromeTabCount(count: number): string {
  return count > 99 ? '99+' : String(Math.max(0, Math.floor(Number.isFinite(count) ? count : 0)));
}

/** Match BrowserChromeController sizing. Native textZoom already scales the font itself. */
export function browserChromeLayout(fontScale: unknown): { firstRow: number; secondRow: number; statusRow: number } {
  const scale = typeof fontScale === 'number' && Number.isFinite(fontScale) ? Math.max(1, fontScale) : 1;
  return { firstRow: Math.max(52, 24 * scale + 24), secondRow: Math.max(44, 20 * scale + 16), statusRow: 16 * scale };
}

/** Only a deliberate native review can dismiss a client-only unknown outcome.
 * No page token, draft, or command is retained by this presentation receipt. */
export type BrowserChromeNoticeReview = { windowId: string; reviewRevision: number };
export function browserChromeNoticeCanClear(state: BrowserChromeState | null, remembered: BrowserChromeNoticeReview | null): boolean {
  if (!state || !remembered) return false;
  if (state.windowId !== remembered.windowId) return true;
  return state.ready && !state.navigationUnconfirmed && state.navigationReviewRevision > remembered.reviewRevision;
}

/** A pending transport receipt is presentation-only: it never authorizes a page action. */
export type BrowserChromeReceipt = { windowId: string; serial: number; reviewRevision: number };
export function browserChromeReceipt(state: BrowserChromeState, serial: number): BrowserChromeReceipt {
  browserChromePin(state);
  if (!Number.isSafeInteger(serial) || serial < 1) throw new Error('浏览器回执序号无效。');
  return { windowId: state.windowId, serial, reviewRevision: state.navigationReviewRevision };
}
export function browserChromeReceiptCurrent(receipt: BrowserChromeReceipt | null, windowId: string | null, latestSerial: number): boolean {
  return !!receipt && receipt.windowId === windowId && receipt.serial === latestSerial;
}
export function browserChromeReceiptOutcome(receipt: BrowserChromeReceipt, windowId: string | null, latestSerial: number, value: unknown, originalContextCurrent: boolean): 'ignore' | 'unconfirmed' | 'accepted_current' | 'accepted_retired' {
  if (!browserChromeReceiptCurrent(receipt, windowId, latestSerial)) return 'ignore';
  if (!browserChromeAccepted(value)) return 'unconfirmed';
  return originalContextCurrent ? 'accepted_current' : 'accepted_retired';
}
/** Review performed before the unknown message arrived does not acknowledge that later message. */
export function browserChromeReceiptReview(receipt: BrowserChromeReceipt, state: BrowserChromeState | null): BrowserChromeNoticeReview {
  return { windowId: receipt.windowId, reviewRevision: state?.windowId === receipt.windowId
    ? Math.max(receipt.reviewRevision, state.navigationReviewRevision) : receipt.reviewRevision };
}
