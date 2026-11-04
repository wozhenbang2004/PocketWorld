import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  BROWSER_CHROME_ACTIONS, BROWSER_CHROME_INPUT_LIMIT, browserChromeAccepted, browserChromeActionAvailable,
  browserChromeActionPayload, browserChromeContext, browserChromeContextCurrent, browserChromeFailure,
  browserChromeLayout, browserChromeNoticeCanClear, browserChromePin, browserChromeReceipt, browserChromeReceiptCurrent, browserChromeReceiptOutcome, browserChromeReceiptReview, browserChromeProgress, browserChromeState, browserChromeSurface, browserChromeTabCount,
  editBrowserChromeDraft, emptyBrowserChromeDraft, reconcileBrowserChromeDraft, sameBrowserChrome,
  type BrowserChromeState,
} from '../src/browser-chrome-contract.ts';

const original = (): BrowserChromeState => ({ ready: true, windowId: 'native-toolbar', generation: 4, contextToken: 'opaque-original-page',
  address: 'https://example.test/original', title: '原网页', status: '', loading: false, progress: 100,
  canBack: true, canForward: false, pageCount: 3, researchAvailable: true, pageAvailable: true, navigationUnconfirmed: false, navigationReviewRevision: 4 });
const freshDraft = (state = original(), epoch = 7) => reconcileBrowserChromeDraft(emptyBrowserChromeDraft(), state, epoch);
const editingDraft = () => editBrowserChromeDraft(freshDraft(), original(), 7, '尚未提交的搜索词');

test('only an authenticated chrome surface supplies its own bounded native state', () => {
  const state = original();
  assert.equal(browserChromeSurface({ host: { surface: 'browser_chrome' } }), true);
  assert.equal(browserChromeSurface({ surface: 'browser_chrome' }), true);
  assert.equal(browserChromeSurface({ surface: 'browser_sources' }), false);
  assert.equal(browserChromeState({ surface: 'business', browserChrome: state }), null);
  assert.equal(browserChromeState({ surface: 'browser_chrome', browserChrome: state }), state);
  for (const change of [{ contextToken: '' }, { windowId: '' }, { generation: -1 }, { generation: 1.5 }, { generation: NaN }, { progress: Infinity }, { navigationReviewRevision: -1 }, { navigationReviewRevision: 0.5 }, { pageCount: -1 }, { canBack: 'true' }, { address: 'x'.repeat(8193) }])
    assert.equal(browserChromeState({ surface: 'browser_chrome', browserChrome: { ...state, ...change } }), null);
});
test('native pin and document epoch fence old clicks, edits, and late receipts', () => {
  const state = original(), pin = browserChromePin(state), context = browserChromeContext(state, 7);
  assert.deepEqual(Object.keys(pin).sort(), ['contextToken', 'generation', 'windowId']);
  assert.equal(sameBrowserChrome(pin, state), true);
  assert.equal(browserChromeContextCurrent(context, state, 7), true);
  assert.equal(browserChromeContextCurrent(context, state, 8), false);
  for (const patch of [{ windowId: 'successor-window' }, { generation: 5 }, { contextToken: 'new-page-same-address' }, { ready: false }]) {
    assert.equal(sameBrowserChrome(pin, { ...state, ...patch }), false);
    assert.equal(browserChromeContextCurrent(context, { ...state, ...patch }, 7), false);
  }
  assert.throws(() => browserChromePin({ ...state, ready: false }));
  assert.throws(() => browserChromeContext(state, -1));
});
test('same-page status/progress snapshots never overwrite an active address edit', () => {
  const draft = editingDraft();
  for (let progress = 0; progress <= 100; progress++) {
    const updated = { ...original(), progress, title: `加载 ${progress}`, status: '正在加载', loading: true };
    assert.equal(reconcileBrowserChromeDraft(draft, updated, 7), draft);
  }
  const focused = { ...freshDraft(), editing: true };
  assert.equal(reconcileBrowserChromeDraft(focused, { ...original(), address: 'https://example.test/redirect' }, 7), focused);
  const blurred = { ...draft, editing: false };
  assert.equal(reconcileBrowserChromeDraft(blurred, original(), 7), blurred);
  assert.equal(reconcileBrowserChromeDraft(freshDraft(), { ...original(), address: 'https://example.test/redirect' }, 7).value, 'https://example.test/redirect');
});
test('new page/context resets a draft even at the same URL; background clears it entirely', () => {
  const draft = editingDraft();
  for (const patch of [{ windowId: 'new-window' }, { generation: 5 }, { contextToken: 'different-page-same-url' }]) {
    const next = reconcileBrowserChromeDraft(draft, { ...original(), ...patch }, 7);
    assert.equal(next.value, original().address); assert.equal(next.editing, false); assert.equal(next.dirty, false);
  }
  assert.equal(reconcileBrowserChromeDraft(draft, original(), 8).value, original().address);
  for (const state of [null, { ...original(), ready: false }])
    assert.deepEqual(reconcileBrowserChromeDraft(draft, state, 7), emptyBrowserChromeDraft());
  assert.deepEqual(reconcileBrowserChromeDraft(draft, original(), 7, false), emptyBrowserChromeDraft());
  assert.throws(() => editBrowserChromeDraft(draft, { ...original(), contextToken: 'successor' }, 7, '旧输入事件'));
  assert.throws(() => editBrowserChromeDraft(draft, original(), 8, '旧文档输入事件'));
});
test('only navigate includes explicit current-context input; there is no automatic submission', () => {
  const state = original(), draft = editingDraft();
  const payload = browserChromeActionPayload(state, 'navigate', draft, 7);
  assert.deepEqual(payload, { ...browserChromePin(state), action: 'navigate', input: '尚未提交的搜索词' });
  assert.deepEqual(BROWSER_CHROME_ACTIONS, ['navigate', 'back', 'forward', 'reload', 'return_owner', 'tabs', 'research', 'sources', 'more']);
  for (const action of BROWSER_CHROME_ACTIONS.filter(action => action !== 'navigate' && action !== 'forward')) {
    assert.deepEqual(Object.keys(browserChromeActionPayload(state, action)).sort(), ['action', 'contextToken', 'generation', 'windowId']);
    assert.throws(() => browserChromeActionPayload(state, action, draft, 7));
  }
  assert.throws(() => browserChromeActionPayload(state, 'navigate', draft, 8));
  assert.throws(() => browserChromeActionPayload({ ...state, contextToken: 'successor' }, 'navigate', draft, 7));
  for (const value of ['  ', 'one\ntwo', 'one\rtwo', 'one\u0000two'])
    assert.throws(() => browserChromeActionPayload(state, 'navigate', { ...draft, value }, 7));
  assert.throws(() => browserChromeActionPayload(state, 'navigate', { ...draft, value: 'x'.repeat(8193) }, 7));
  assert.equal(browserChromeActionPayload(state, 'navigate', { ...draft, value: 'x'.repeat(BROWSER_CHROME_INPUT_LIMIT) }, 7).input?.length, 8192);
});
test('native page and research availability gate controls without inventing source counts', () => {
  assert.equal(browserChromeActionAvailable(original(), 'back'), true);
  assert.equal(browserChromeActionAvailable(original(), 'forward'), false);
  assert.equal(browserChromeActionAvailable({ ...original(), pageAvailable: false }, 'reload'), true);
  assert.deepEqual(browserChromeActionPayload({ ...original(), pageAvailable: false }, 'reload'), { ...browserChromePin(original()), action: 'reload' });
  assert.equal(browserChromeActionAvailable({ ...original(), pageAvailable: false }, 'navigate'), false);
  assert.equal(browserChromeActionAvailable({ ...original(), researchAvailable: false }, 'research'), false);
  assert.equal(browserChromeActionAvailable({ ...original(), researchAvailable: false }, 'sources'), false);
  for (const action of BROWSER_CHROME_ACTIONS) assert.equal(browserChromeActionAvailable({ ...original(), ready: false }, action), false);
  assert.equal(browserChromeActionAvailable({ ...original(), pageCount: 0 }, 'tabs'), true);
});
test('only an explicit native acceptance is confirmed; unknown outcomes remain visibly unconfirmed', () => {
  assert.equal(browserChromeAccepted({ accepted: true }), true);
  for (const value of [undefined, null, true, {}, { ok: true }, { accepted: false }, { accepted: 'true' }]) assert.equal(browserChromeAccepted(value), false);
  assert.equal(browserChromeFailure({ code: 'not_sent' }).kind, 'not_sent');
  assert.equal(browserChromeFailure({ code: 'outcome_unknown' }).kind, 'unconfirmed');
  assert.equal(browserChromeFailure(new Error('transport failed')).kind, 'unconfirmed');
  assert.match(browserChromeFailure().text, /尚未确认/);
});
test('unknown navigation survives context changes and blocks only webpage-changing gestures', () => {
  const uncertain = { ...original(), navigationUnconfirmed: true, contextToken: 'new-context-after-dispatch' };
  for (const action of ['navigate', 'back', 'forward', 'reload'] as const) {
    assert.equal(browserChromeActionAvailable(uncertain, action), false);
    assert.equal(browserChromeActionAvailable(original(), action, true), false);
  }
  for (const action of ['return_owner', 'tabs', 'research', 'sources', 'more'] as const)
    assert.equal(browserChromeActionAvailable(uncertain, action, true), true);
  const remembered = { windowId: original().windowId, reviewRevision: 4 };
  assert.equal(browserChromeNoticeCanClear(original(), remembered), false);
  // A fresh progress/context/generation snapshot is not evidence of owner review.
  assert.equal(browserChromeNoticeCanClear({ ...original(), progress: 50, generation: 9, contextToken: 'new-page' }, remembered), false);
  assert.equal(browserChromeNoticeCanClear({ ...original(), navigationReviewRevision: 3 }, remembered), false);
  assert.equal(browserChromeNoticeCanClear({ ...original(), navigationReviewRevision: 5 }, remembered), true);
  assert.equal(browserChromeNoticeCanClear({ ...uncertain, navigationReviewRevision: 5 }, remembered), false);
  assert.equal(browserChromeNoticeCanClear({ ...original(), ready: false, navigationReviewRevision: 5 }, remembered), false);
  assert.equal(browserChromeNoticeCanClear({ ...original(), windowId: 'other-window' }, remembered), true);
  assert.deepEqual(Object.keys(remembered).sort(), ['reviewRevision', 'windowId']);
});
test('state may advance before malformed receipt or client timeout without swallowing the warning', () => {
  const state = original(), originalContext = browserChromeContext(state, 7);
  const receipt = browserChromeReceipt(state, 9);
  const next = { ...state, contextToken: 'new-page', generation: 20, address: 'https://example.test/next', ready: false };
  assert.equal(browserChromeContextCurrent(originalContext, next, 8), false);
  // The waiting lock and read-only warning survive page/background/document retirement.
  assert.equal(browserChromeReceiptCurrent(receipt, next.windowId, 9), true);
  assert.equal(browserChromeReceiptOutcome(receipt, next.windowId, 9, {}, false), 'unconfirmed');
  assert.equal(browserChromeReceiptOutcome(receipt, next.windowId, 9, undefined, false), 'unconfirmed');
  const timeout = { code: 'outcome_unknown' };
  assert.equal(browserChromeReceiptCurrent(receipt, next.windowId, 9) && browserChromeFailure(timeout).kind === 'unconfirmed', true);
  assert.deepEqual(Object.keys(receipt).sort(), ['reviewRevision', 'serial', 'windowId']);
  assert.equal(browserChromeReceiptOutcome(receipt, next.windowId, 10, undefined, false), 'ignore');
  assert.equal(browserChromeReceiptOutcome(receipt, 'different-window', 9, undefined, false), 'ignore');
});
test('retired accepted receipts release waiting only and cannot update the successor input', () => {
  const receipt = browserChromeReceipt(original(), 2);
  assert.equal(browserChromeReceiptOutcome(receipt, receipt.windowId, 2, { accepted: true }, false), 'accepted_retired');
  assert.equal(browserChromeReceiptOutcome(receipt, receipt.windowId, 2, { accepted: true }, true), 'accepted_current');
  assert.equal(browserChromeReceiptCurrent(receipt, receipt.windowId, 2), true);
  assert.equal(browserChromeReceiptCurrent(null, receipt.windowId, 2), false);
  assert.throws(() => browserChromeReceipt(original(), 0));
  const alreadyReviewed = { ...original(), navigationReviewRevision: 6 };
  const warning = browserChromeReceiptReview(receipt, alreadyReviewed);
  assert.deepEqual(warning, { windowId: receipt.windowId, reviewRevision: 6 });
  assert.equal(browserChromeNoticeCanClear(alreadyReviewed, warning), false);
  assert.equal(browserChromeNoticeCanClear({ ...alreadyReviewed, navigationReviewRevision: 7 }, warning), true);
});
test('progress and visual tab counts are presentation-only and bounded', () => {
  assert.equal(browserChromeProgress(Infinity), 0);
  assert.equal(browserChromeProgress(-10), 0);
  assert.equal(browserChromeProgress(120), 100);
  assert.equal(browserChromeProgress(49.5), 50);
  assert.equal(browserChromeTabCount(100), '99+');
  assert.equal(browserChromeTabCount(3), '3');
});
test('font-scale row sizing mirrors native without applying a second text scale', () => {
  assert.deepEqual(browserChromeLayout(1), { firstRow: 52, secondRow: 44, statusRow: 16 });
  assert.deepEqual(browserChromeLayout(2), { firstRow: 72, secondRow: 56, statusRow: 32 });
  assert.deepEqual(browserChromeLayout(0.85), browserChromeLayout(1));
  assert.deepEqual(browserChromeLayout(NaN), browserChromeLayout(1));
});
test('production toolbar uses retained BoardUI and Affect primitives, with no fake page or Web navigation', () => {
  const code = readFileSync(new URL('../src/BrowserChrome.tsx', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../src/browser-chrome.css', import.meta.url), 'utf8');
  assert.match(code, /import \{ Button, IconButton \} from '\.\/forms-library'/);
  assert.match(code, /import \{ TextField, InputBase \} from '\.\/forms-library\/boardui\/components\/base\/input\/input'/);
  assert.match(code, /<InputBase ref=\{input\}/);
  assert.match(code, /<ShimmerText>/);
  assert.match(code, /<TextField key=\{contextKey\}/);
  assert.match(code, /onSubmit=\{event => \{ event\.preventDefault\(\)/);
  assert.match(code, /event\.nativeEvent\.isComposing/);
  assert.match(code, /bridge\.command<unknown>\('browserChrome\.action', payload\)/);
  assert.match(code, /browserChromeState\(bridge\.getState\(\)\)/);
  assert.match(code, /browserChromeContextCurrent\(ticket\.context/);
  assert.match(code, /if \(receiptCurrent\(\)\) recordFailure\(receipt, failure\)/);
  assert.match(code, /outcome === 'accepted_current' && action === 'navigate'/);
  assert.match(code, /waitingReceipt\.current && \(action !== 'return_owner'/);
  const retire = code.slice(code.indexOf('const original = operation.current;'), code.indexOf('if (state && waitingReceipt.current'));
  assert.doesNotMatch(retire, /setPending\(null\)|waitingReceipt\.current = null|receiptSerial\.current\+\+/);
  assert.doesNotMatch(code, /window\.addEventListener\('blur', suspend\)/);
  assert.match(code, /document\.visibilityState === 'hidden'/);
  assert.match(code, /visibilitychange/);
  assert.match(code, /browserChromeLayout\(snapshot\.host\?\.fontScale\)/);
  assert.doesNotMatch(code, /localStorage|sessionStorage|indexedDB|window\.open|location\.(assign|replace|href)|history\.(pushState|replaceState)|<iframe|<webview|<a\s|href=|dangerouslySetInnerHTML|Popover|Drawer|createPortal|sources\.(open|read)/);
  assert.match(css, /min-width: 44px; min-height: 44px/);
  assert.match(css, /grid-template-columns: repeat\(7, minmax\(44px, 1fr\)\)/);
  assert.match(css, /prefers-reduced-motion/);
  assert.match(css, /background: var\(--pw-surface\); color: var\(--pw-ink\)/);
  assert.doesNotMatch(css, /#[0-9a-fA-F]{3,8}\b/);
});
