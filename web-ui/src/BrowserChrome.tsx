import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { IconArrowLeft, IconArrowRight, IconBook, IconFileText, IconLayers, IconMore, IconRefresh, IconChat } from './icons';
import { bridge, type PocketSnapshot } from './bridge';
import { Button, IconButton } from './forms-library';
import { TextField, InputBase } from './forms-library/boardui/components/base/input/input';
import { ShimmerText } from './library';
import { MotionFeedback } from './library/journey-motion';
import { usePocketAppearance } from './usePocketAppearance';
import {
  BROWSER_CHROME_INPUT_LIMIT, browserChromeActionAvailable, browserChromeActionPayload,
  browserChromeContext, browserChromeContextCurrent, browserChromeFailure, browserChromeLayout, browserChromeProgress,
  browserChromeNoticeCanClear, browserChromeReceipt, browserChromeReceiptCurrent, browserChromeReceiptOutcome, browserChromeReceiptReview, browserChromeState, browserChromeTabCount, editBrowserChromeDraft, emptyBrowserChromeDraft,
  reconcileBrowserChromeDraft, type BrowserChromeAction, type BrowserChromeContext, type BrowserChromeNotice, type BrowserChromeNoticeReview, type BrowserChromeReceipt,
} from './browser-chrome-contract';
import './browser-chrome.css';
import './browser-depth.css';

/** Fixed native toolbar surface. The real webpage remains in its separate native WebView. */
export default function BrowserChrome({ snapshot }: { snapshot: PocketSnapshot }) {
  usePocketAppearance(snapshot.host?.appearance);
  const state = browserChromeState(snapshot);
  const epoch = bridge.getDocumentEpoch();
  const [binding, setBinding] = useState({ epoch, revisionFloor: -1 });
  const [suspended, setSuspended] = useState(document.visibilityState === 'hidden');
  const [draft, setDraft] = useState(emptyBrowserChromeDraft);
  const [pending, setPending] = useState<BrowserChromeAction | null>(null);
  const [notice, setNotice] = useState<BrowserChromeNotice | null>(null);
  const input = useRef<HTMLInputElement>(null), composing = useRef(false), noticeReview = useRef<BrowserChromeNoticeReview | null>(null);
  const mounted = useRef(true), sequence = useRef(0), lastEpoch = useRef(epoch);
  const receiptSerial = useRef(0), waitingReceipt = useRef<BrowserChromeReceipt | null>(null);
  const waitingAction = useRef<BrowserChromeAction | null>(null);
  const observedWindow = useRef<string | null>(state?.windowId ?? null);
  if (state) observedWindow.current = state.windowId;
  const operation = useRef<{ sequence: number; context: BrowserChromeContext; action: BrowserChromeAction } | null>(null);
  const awaitingFresh = binding.epoch !== epoch || (snapshot.revision ?? 0) <= binding.revisionFloor;
  const connected = !suspended && !awaitingFresh && !!state?.ready && snapshot.capabilities.commands.includes('browserChrome.action');
  const currentDraft = reconcileBrowserChromeDraft(draft, state, epoch, connected);
  const contextKey = currentDraft.context ? JSON.stringify(currentDraft.context) : 'unbound';
  const lastContext = useRef(contextKey);
  const localUnconfirmed = notice?.kind === 'unconfirmed';
  const live = useRef({ state, epoch, connected, draft: currentDraft, localUnconfirmed });
  live.current = { state, epoch, connected, draft: currentDraft, localUnconfirmed };
  const keyboardVisible = snapshot.host?.keyboardVisible === true;
  const layout = browserChromeLayout(snapshot.host?.fontScale);
  const rowStyle = { '--browser-first-row': `${layout.firstRow}px`, '--browser-second-row': `${layout.secondRow}px`, '--browser-status-row': `${layout.statusRow}px` } as CSSProperties;

  useEffect(() => {
    mounted.current = true;
    document.documentElement.dataset.pocketUi = 'ready';
    const suspend = () => {
      sequence.current++; operation.current = null; composing.current = false;
      live.current = { ...live.current, connected: false, draft: emptyBrowserChromeDraft() };
      setSuspended(true); setDraft(emptyBrowserChromeDraft()); setNotice(previous => previous?.kind === 'unconfirmed' ? previous : null);
    };
    const visibility = () => { if (document.visibilityState === 'hidden') suspend(); };
    document.addEventListener('visibilitychange', visibility);
    return () => {
      mounted.current = false; sequence.current++; operation.current = null; receiptSerial.current++; waitingReceipt.current = null; waitingAction.current = null;
      document.removeEventListener('visibilitychange', visibility);
      delete document.documentElement.dataset.pocketUi;
    };
  }, []);
  useLayoutEffect(() => {
    if (lastEpoch.current === epoch) return;
    lastEpoch.current = epoch;
    sequence.current++; operation.current = null; composing.current = false;
    setDraft(emptyBrowserChromeDraft()); setNotice(previous => previous?.kind === 'unconfirmed' ? previous : null);
    const revisionFloor = snapshot.revision ?? 0;
    // One handshake for a new native document epoch. Never replay an action or an old draft.
    void bridge.bindHostDocument().then(() => {
      if (mounted.current && bridge.getDocumentEpoch() === epoch) {
        setBinding({ epoch, revisionFloor });
        setSuspended(document.visibilityState === 'hidden');
      }
    }).catch(() => {
      if (mounted.current && bridge.getDocumentEpoch() === epoch) showNotSent('工具栏尚未重新连接，请使用系统返回键。');
    });
  }, [epoch]);
  useLayoutEffect(() => {
    setDraft(previous => reconcileBrowserChromeDraft(previous, state, epoch, connected));
    const original = operation.current;
    if (!connected || lastContext.current !== contextKey || original && !browserChromeContextCurrent(original.context, state, epoch)) {
      sequence.current++; operation.current = null; composing.current = false; setNotice(previous => previous?.kind === 'unconfirmed' ? previous : null);
    }
    if (state && waitingReceipt.current && waitingReceipt.current.windowId !== state.windowId) {
      receiptSerial.current++; waitingReceipt.current = null; waitingAction.current = null; setPending(null);
    }
    lastContext.current = contextKey;
  }, [state?.windowId, state?.generation, state?.contextToken, state?.ready, state?.address, epoch, connected, contextKey]);

  useEffect(() => {
    if (localUnconfirmed && browserChromeNoticeCanClear(state, noticeReview.current)) { noticeReview.current = null; setNotice(null); }
  }, [localUnconfirmed, state?.windowId, state?.navigationUnconfirmed, state?.navigationReviewRevision, state?.ready]);

  function recordFailure(receipt: BrowserChromeReceipt, failure?: unknown) {
    const next = browserChromeFailure(failure);
    if (next.kind === 'unconfirmed') {
      const latest = browserChromeState(bridge.getState()) ?? live.current.state;
      noticeReview.current = browserChromeReceiptReview(receipt, latest);
      live.current = { ...live.current, localUnconfirmed: true };
    }
    setNotice(previous => previous?.kind === 'unconfirmed' && next.kind !== 'unconfirmed' ? previous : next);
  }
  function showNotSent(text: string) {
    setNotice(previous => previous?.kind === 'unconfirmed' ? previous : { kind: 'not_sent', text });
  }
  function updateDraft(value: string) {
    const current = live.current;
    if (!current.connected || !current.state || !browserChromeContextCurrent(currentDraft.context, current.state, current.epoch)) return;
    try {
      const next = editBrowserChromeDraft(current.draft, current.state, current.epoch, value);
      live.current = { ...current, draft: next }; setDraft(next); setNotice(previous => previous?.kind === 'unconfirmed' ? previous : null);
    } catch { showNotSent('网页已变化或输入过长，请核对地址后再打开。'); }
  }
  function cancelDraft() {
    const current = live.current;
    if (!browserChromeContextCurrent(currentDraft.context, current.state, current.epoch)) return;
    setDraft(reconcileBrowserChromeDraft(emptyBrowserChromeDraft(), current.state, current.epoch, current.connected));
    input.current?.blur();
  }
  async function act(action: BrowserChromeAction) {
    const current = live.current;
    const nativeState = browserChromeState(bridge.getState());
    if (!current.connected || !nativeState || bridge.getDocumentEpoch() !== current.epoch || composing.current ||
        !browserChromeContextCurrent(currentDraft.context, nativeState, current.epoch) ||
        !browserChromeActionAvailable(nativeState, action, current.localUnconfirmed)) return;
    // A page/context update is not a transport receipt. Keep its waiting lock until it settles.
    // Returning to the assistant may supersede it; never automatically resend either action.
    if (waitingReceipt.current && (action !== 'return_owner' || waitingAction.current === 'return_owner')) return;
    let payload;
    try {
      payload = action === 'navigate'
        ? browserChromeActionPayload(nativeState, action, current.draft, current.epoch)
        : browserChromeActionPayload(nativeState, action);
    } catch (failure) {
      showNotSent(failure instanceof Error ? failure.message : '操作未发送，请核对当前网页。'); return;
    }
    const ticket = { sequence: ++sequence.current, context: browserChromeContext(nativeState, current.epoch), action };
    const receipt = browserChromeReceipt(nativeState, ++receiptSerial.current);
    operation.current = ticket; waitingReceipt.current = receipt; waitingAction.current = action;
    setPending(action); setNotice(previous => previous?.kind === 'unconfirmed' ? previous : null);
    try {
      const result = await bridge.command<unknown>('browserChrome.action', payload);
      if (!mounted.current) return;
      const outcome = browserChromeReceiptOutcome(receipt, currentReceiptWindow(), receiptSerial.current, result, stillCurrent());
      if (outcome === 'unconfirmed') recordFailure(receipt);
      // A retired accepted receipt releases only its waiting lock. It cannot touch the new input.
      else if (outcome === 'accepted_current' && action === 'navigate') input.current?.blur();
    } catch (failure) {
      // This narrow fence can display plain receipt text across context/generation changes.
      // It cannot grant authority, restore an address draft, or issue another command.
      if (receiptCurrent()) recordFailure(receipt, failure);
    } finally {
      if (receiptCurrent()) {
        waitingReceipt.current = null; waitingAction.current = null; setPending(null);
        if (operation.current?.sequence === ticket.sequence) operation.current = null;
      }
    }
    function currentReceiptWindow() {
      return browserChromeState(bridge.getState())?.windowId ?? observedWindow.current;
    }
    function receiptCurrent() {
      return mounted.current && browserChromeReceiptCurrent(receipt, currentReceiptWindow(), receiptSerial.current);
    }
    function stillCurrent() {
      return mounted.current && live.current.connected && ticket.sequence === sequence.current &&
        browserChromeContextCurrent(ticket.context, browserChromeState(bridge.getState()), bridge.getDocumentEpoch());
    }
  }
  function disabled(action: BrowserChromeAction) {
    return !connected || !browserChromeActionAvailable(state, action, localUnconfirmed) || !!pending && (action !== 'return_owner' || pending === 'return_owner');
  }
  const nativeUnconfirmed = state?.navigationUnconfirmed === true;
  const status = (nativeUnconfirmed ? '上一导航结果尚未确认。请在“更多”中核对后收起提示。' : notice?.text) || (!connected ? '正在连接浏览器…' : pending ? '等待原生受理…' : state?.status || (state?.loading ? '正在加载网页…' : state?.title || '输入地址或搜索词'));
  const loading = !nativeUnconfirmed && !notice && (!connected || !!pending || !!state?.loading);
  const count = state?.pageCount ?? 0;
  return <section className="browser-chrome" style={rowStyle} aria-label="浏览器工具栏" data-keyboard-visible={keyboardVisible || undefined}>
    <form className="browser-chrome-address-row" aria-label="打开地址或搜索" autoComplete="off" onSubmit={event => { event.preventDefault(); if (!composing.current) void act('navigate'); }}
      onCompositionStart={() => { composing.current = true; }} onCompositionEnd={() => { composing.current = false; }}
      onKeyDownCapture={event => {
        if (event.key === 'Enter' && (composing.current || event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
        if (event.key === 'Escape' && !composing.current) { event.preventDefault(); cancelDraft(); }
      }}>
      <IconButton icon={IconChat} className="browser-chrome-control browser-chrome-return" aria-label="返回助手" title="返回助手" disabled={disabled('return_owner')} onClick={() => void act('return_owner')} />
      <TextField key={contextKey} className="browser-chrome-address" fieldClassName="browser-chrome-address-shell" inputClassName="browser-chrome-address-input"
        aria-label="地址或搜索词" aria-describedby="browser-chrome-status" type="text"
        value={currentDraft.value} onChange={updateDraft} isDisabled={!connected} isReadOnly={!!pending}
        onFocus={() => { if (browserChromeContextCurrent(currentDraft.context, live.current.state, live.current.epoch)) setDraft(previous => ({ ...previous, editing: true })); }}
        onBlur={() => { if (browserChromeContextCurrent(currentDraft.context, live.current.state, live.current.epoch)) setDraft(previous => ({ ...previous, editing: false })); }}>
        <InputBase ref={input} placeholder="地址或搜索" inputMode="url" dir="ltr"
          autoComplete="off" autoCorrect="off" autoCapitalize="none" spellCheck={false} enterKeyHint="go" maxLength={BROWSER_CHROME_INPUT_LIMIT} />
      </TextField>
      <Button variant="ghost" type="submit" className="browser-chrome-control browser-chrome-open" disabled={disabled('navigate') || !currentDraft.value.trim()} title="打开地址或搜索">打开</Button>
    </form>
    <nav className="browser-chrome-actions" aria-label="网页导航与工具" hidden={keyboardVisible}>
      <IconButton icon={IconArrowLeft} className="browser-chrome-control" aria-label="上一页" title="上一页" disabled={disabled('back')} onClick={() => void act('back')} />
      <IconButton icon={IconArrowRight} className="browser-chrome-control" aria-label="下一页" title="下一页" disabled={disabled('forward')} onClick={() => void act('forward')} />
      <IconButton icon={IconRefresh} className="browser-chrome-control" aria-label={state?.pageAvailable ? '刷新当前网页' : '重新打开网页'} title={state?.pageAvailable ? '刷新当前网页' : '重新打开网页'} disabled={disabled('reload')} onClick={() => void act('reload')} />
      <span className="browser-chrome-tabs"><IconButton icon={IconLayers} className="browser-chrome-control" aria-label={`管理标签页，当前 ${count} 个`} title={`管理 ${count} 个标签页`} disabled={disabled('tabs')} onClick={() => void act('tabs')} /><span className="browser-chrome-count" aria-hidden="true">{browserChromeTabCount(count)}</span></span>
      <IconButton icon={IconBook} className="browser-chrome-control" aria-label="打开研究任务" title="研究任务" disabled={disabled('research')} onClick={() => void act('research')} />
      <IconButton icon={IconFileText} className="browser-chrome-control" aria-label="查看研究来源" title="研究来源" disabled={disabled('sources')} onClick={() => void act('sources')} />
      <IconButton icon={IconMore} className="browser-chrome-control" aria-label="更多浏览选项和状态" title="更多浏览选项和状态" disabled={disabled('more')} onClick={() => void act('more')} />
    </nav>
    <MotionFeedback identity={JSON.stringify([state?.windowId, nativeUnconfirmed, notice?.kind, notice?.text, connected, pending, state?.loading])} initial={false} id="browser-chrome-status" className="browser-chrome-status" data-notice={nativeUnconfirmed ? 'unconfirmed' : notice?.kind} role={nativeUnconfirmed || notice ? 'alert' : 'status'} aria-live={nativeUnconfirmed || notice ? 'assertive' : 'polite'} aria-atomic="true" title={status}>
      {loading ? <ShimmerText>{status}</ShimmerText> : <span>{status}</span>}
    </MotionFeedback>
    {connected && state?.loading && <div className="browser-chrome-progress" role="progressbar" aria-label="网页加载进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={browserChromeProgress(state.progress)}><span style={{ transform: `scaleX(${browserChromeProgress(state.progress) / 100})` }} /></div>}
  </section>;
}
