import { useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Check, ChevronDown, CornerDownLeft, Hand, Keyboard, LoaderCircle, Maximize2, Minimize2, Minus, Monitor, MoreHorizontal, MousePointer2, MousePointerClick, Plus, RefreshCw, Send, X } from 'lucide-react';
import { DesktopViewerConnection, desktopPoint, desktopScopes, desktopText, type DesktopInput, type DesktopRecord, type DesktopRun, type DesktopViewerState } from './desktop-viewer';
import './desktop-viewer.css';

const idle: DesktopViewerState = { phase: 'idle', session: null, frameReady: false, controlPending: false, inputPending: false, inputUncertain: false, error: '' };
const clampZoom = (n: number) => Math.round(Math.max(1, Math.min(3, n)) * 10) / 10;
type Touch = { x: number; y: number; initialX: number; initialY: number; started: number; button: number; moved: boolean };

/** Embedded owner view. The AI's observations and this video have separate lifecycles. */
export function DesktopViewer({ peer, supported, run, onClose }: { peer: DesktopRecord; supported: (action: string) => boolean; run: DesktopRun; onClose: () => void }) {
  const scopes = useMemo(() => desktopScopes(peer), [peer]);
  const first = scopes.find(scope => scope.available && scope.resourceBinding.windowId) || scopes.find(scope => scope.available) || scopes[0];
  const [scopeKey, setScopeKey] = useState(first?.key || ''), [state, setState] = useState<DesktopViewerState>(idle);
  const [zoom, setZoom] = useState(1), [expanded, setExpanded] = useState(false), [keyboard, setKeyboard] = useState(false), [more, setMore] = useState(false);
  const [draft, setDraft] = useState(''), [textError, setTextError] = useState(''), [clickMode, setClickMode] = useState<'left' | 'double' | 'right'>('left');
  const video = useRef<HTMLVideoElement>(null), viewport = useRef<HTMLDivElement>(null), textarea = useRef<HTMLTextAreaElement>(null);
  const connection = useRef<DesktopViewerConnection | null>(null), runRef = useRef(run), mounted = useRef(false), composing = useRef(false), zoomRef = useRef(zoom);
  const pointers = useRef(new Map<number, Touch>()), pinch = useRef<{ distance: number; zoom: number } | null>(null), pinched = useRef(false);
  const scope = scopes.find(option => option.key === scopeKey) || first;
  const busy = state.phase === 'opening' || state.phase === 'connecting';
  const connected = state.phase === 'live', human = state.session?.control.owner === 'human';
  const canInput = connected && human && state.frameReady && !state.controlPending && !state.inputPending && !state.inputUncertain;
  const canScroll = canInput && !!connection.current?.canScroll();
  const hostReady = ['open', 'negotiate', 'status', 'control', 'input', 'close'].every(action => supported(`devices.desktop.${action}`));
  runRef.current = run; zoomRef.current = zoom;

  useEffect(() => {
    mounted.current = true;
    const owner = new DesktopViewerConnection({ run: (action, args) => runRef.current(action, args),
      onChange: next => { if (mounted.current) setState(next); },
      onStream: stream => { const element = video.current; if (!element) return; element.srcObject = stream; if (stream) void element.play().catch(() => {}); else element.pause(); },
    });
    connection.current = owner;
    const hide = () => { if (document.visibilityState !== 'visible') { owner.close(); onClose(); } };
    const leave = () => { owner.close(); };
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', leave);
    if (hostReady && first?.available && document.visibilityState === 'visible') void owner.open(peer.deviceId, first);
    return () => { mounted.current = false; document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', leave); owner.dispose(); connection.current = null; };
  }, [peer.deviceId]);

  useEffect(() => {
    if (!scopes.some(option => option.key === scopeKey) && first) setScopeKey(first.key);
  }, [scopes, scopeKey, first?.key]);
  useEffect(() => {
    if (peer.revoked === true || state.session && !scopes.some(option => option.key === scopeKey && option.available)) connection.current?.close();
  }, [peer.revoked, scopes, scopeKey, state.session?.sessionId]);
  useEffect(() => {
    if (!expanded) return;
    const previous = document.body.style.overflow; document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, [expanded]);
  useEffect(() => { if (keyboard && human) textarea.current?.focus({ preventScroll: true }); }, [keyboard, human]);
  useEffect(() => { if (!human) setKeyboard(false); }, [human]);
  useEffect(() => {
    const element = video.current; if (!element) return;
    let cancelled = false, callback = 0, time = -1;
    const decoded = () => {
      if (cancelled) return;
      connection.current?.decodedFrame(element.videoWidth, element.videoHeight);
      if (element.requestVideoFrameCallback) callback = element.requestVideoFrameCallback(decoded);
    };
    const changed = () => { if (element.currentTime !== time && element.readyState >= 2) { time = element.currentTime; connection.current?.decodedFrame(element.videoWidth, element.videoHeight); } };
    if (element.requestVideoFrameCallback) callback = element.requestVideoFrameCallback(decoded);
    else element.addEventListener('timeupdate', changed);
    return () => { cancelled = true; if (element.cancelVideoFrameCallback && callback) element.cancelVideoFrameCallback(callback); element.removeEventListener('timeupdate', changed); };
  }, []);
  useEffect(() => {
    const surface = viewport.current; if (!surface) return;
    const wheel = (event: WheelEvent) => {
      const owner = connection.current, element = video.current;
      if (!owner?.canInput() || !element || zoomRef.current > 1) return;
      const target = desktopPoint(event.clientX, event.clientY, element.getBoundingClientRect(), element.videoWidth, element.videoHeight); if (!target) return;
      event.preventDefault();
      const vertical = Math.abs(event.deltaY) >= Math.abs(event.deltaX), delta = vertical ? event.deltaY : event.deltaX;
      if (!delta) return;
      void owner.input({ kind: 'scroll', ...target, direction: vertical ? delta > 0 ? 'down' : 'up' : delta > 0 ? 'right' : 'left', amount: Math.max(1, Math.min(5, Math.ceil(Math.abs(delta) / 60))), by: 'line' });
    };
    surface.addEventListener('wheel', wheel, { passive: false });
    return () => surface.removeEventListener('wheel', wheel);
  }, []);

  const reconnect = () => { if (scope && hostReady) { setClickMode('left'); setTextError(''); void connection.current?.open(peer.deviceId, scope); } };
  const send = (input: DesktopInput) => { setTextError(''); void connection.current?.input(input); };
  const point = (x: number, y: number) => { const element = video.current; return element && desktopPoint(x, y, element.getBoundingClientRect(), element.videoWidth, element.videoHeight); };
  const setViewZoom = (next: number) => {
    const surface = viewport.current, before = zoomRef.current, value = clampZoom(next);
    const centerX = surface ? (surface.scrollLeft + surface.clientWidth / 2) / before : 0;
    const centerY = surface ? (surface.scrollTop + surface.clientHeight / 2) / before : 0;
    zoomRef.current = value; setZoom(value);
    requestAnimationFrame(() => { if (surface) { surface.scrollLeft = Math.max(0, centerX * value - surface.clientWidth / 2); surface.scrollTop = Math.max(0, centerY * value - surface.clientHeight / 2); } });
  };
  const pointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    if (event.pointerType !== 'touch' && event.button !== 0 && event.button !== 2) return;
    pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY, initialX: event.clientX, initialY: event.clientY, started: Date.now(), button: event.button, moved: false });
    if (pointers.current.size === 1) { pinch.current = null; pinched.current = false; }
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]; pinch.current = { distance: Math.hypot(a.x - b.x, a.y - b.y), zoom: zoomRef.current }; pinched.current = true;
    }
    if (zoomRef.current === 1 && connected) event.currentTarget.setPointerCapture(event.pointerId);
  };
  const pointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const saved = pointers.current.get(event.pointerId); if (!saved) return;
    saved.x = event.clientX; saved.y = event.clientY; saved.moved ||= Math.hypot(saved.x - saved.initialX, saved.y - saved.initialY) > 12;
    if (pointers.current.size === 2 && pinch.current && pinch.current.distance > 0) {
      const [a, b] = [...pointers.current.values()]; setViewZoom(pinch.current.zoom * Math.hypot(a.x - b.x, a.y - b.y) / pinch.current.distance);
    }
  };
  const pointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    const saved = pointers.current.get(event.pointerId); pointers.current.delete(event.pointerId);
    if (!saved || pinched.current || !connection.current?.canInput()) return;
    const dx = event.clientX - saved.initialX, dy = event.clientY - saved.initialY;
    if (saved.moved || Math.hypot(dx, dy) > 12) {
      if (event.pointerType === 'touch' && zoomRef.current === 1 && Date.now() - saved.started < 2000) {
        const target = point(saved.initialX, saved.initialY); if (!target) return;
        const vertical = Math.abs(dy) >= Math.abs(dx), delta = vertical ? dy : dx;
        send({ kind: 'scroll', ...target, direction: vertical ? delta < 0 ? 'down' : 'up' : delta < 0 ? 'right' : 'left', amount: Math.max(1, Math.min(5, Math.round(Math.abs(delta) / 45))), by: 'line' });
      }
      return;
    }
    const target = point(event.clientX, event.clientY); if (!target) return;
    const right = saved.button === 2 || clickMode === 'right' || event.pointerType === 'touch' && Date.now() - saved.started > 500;
    send({ kind: 'click', ...target, button: right ? 'right' : 'left', count: !right && clickMode === 'double' ? 2 : 1 });
    setClickMode('left');
  };
  const submitText = async () => {
    if (composing.current || !connection.current?.canInput()) return;
    let text: string; try { text = desktopText(draft); } catch (error) { setTextError(error instanceof Error ? error.message : String(error)); return; }
    setTextError(''); const sessionId = state.session?.sessionId;
    if (await connection.current.input({ kind: 'insert_text', text }) && mounted.current && connection.current?.state.session?.sessionId === sessionId) setDraft(current => current === text ? '' : current);
  };
  const status = state.controlPending ? '正在切换控制' : state.inputPending ? '正在输入' : state.phase === 'opening' ? '正在连接电脑' : state.phase === 'connecting' ? '正在连接画面' : state.phase === 'disconnected' ? '连接中断' : connected && !state.frameReady ? '等待新画面' : human ? state.inputUncertain ? '请核对输入结果' : '你正在操作' : connected ? '正在观看' : '未连接';
  const label = peer.label || peer.name || '电脑';

  return <section className={`pw-desktop-viewer${expanded ? ' is-expanded' : ''}`} aria-label="电脑实时桌面" data-phase={state.phase}>
    <header className="pw-desktop-heading">
      <div className="pw-desktop-source"><Monitor size={17} aria-hidden="true" /><span>{label}</span>
        {scopes.length > 1 ? <label className="pw-desktop-scope"><span className="pw-desktop-sr">观看范围</span><select value={scope?.key || ''} aria-label="观看范围" onChange={event => { const selected = scopes.find(option => option.key === event.target.value); connection.current?.close(); setScopeKey(event.target.value); setDraft(''); setTextError(''); setKeyboard(false); setViewZoom(1); if (selected?.available && hostReady) void connection.current?.open(peer.deviceId, selected); }}>{scopes.map((option, index) => <option key={option.key} value={option.key}>{option.label}{scopes.length > 1 ? ` ${index + 1}` : ''}{option.available ? '' : ' · 待授权'}</option>)}</select><ChevronDown size={12} aria-hidden="true" /></label> : <span className="pw-desktop-scope-label">{scope?.label || '桌面'}</span>}
      </div>
      <div className="pw-desktop-icons"><button type="button" onClick={() => setExpanded(value => !value)} aria-label={expanded ? '退出全屏桌面' : '全屏查看桌面'} title={expanded ? '退出全屏' : '全屏查看'}>{expanded ? <Minimize2 size={18} /> : <Maximize2 size={18} />}</button><button type="button" onClick={() => { connection.current?.close(); onClose(); }} aria-label="关闭桌面观看" title="关闭桌面"><X size={19} /></button></div>
    </header>

    <div ref={viewport} className={`pw-desktop-screen${human ? ' has-control' : ''}${zoom > 1 ? ' is-zoomed' : ''}`} style={{ aspectRatio: `${state.session?.width || 16} / ${state.session?.height || 10}` }}
      onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={event => pointers.current.delete(event.pointerId)} onContextMenu={event => event.preventDefault()}>
      <div className="pw-desktop-video-size" style={{ width: `${zoom * 100}%`, height: `${zoom * 100}%` }}><video ref={video} autoPlay playsInline muted aria-label="远程电脑实时画面" /></div>
      {state.phase !== 'live' && <div className="pw-desktop-overlay" aria-live="polite">
        {busy ? <><LoaderCircle className="pw-desktop-spin" size={24} /><span>{status}</span></> : <><Monitor size={28} strokeWidth={1.4} /><span>{!hostReady ? '请更新手机端以开启桌面观看' : !scope?.available ? scope?.reason || '请在电脑上授权桌面观看' : state.phase === 'disconnected' ? '正在恢复画面' : '电脑桌面'}</span>{hostReady && scope?.available && state.phase !== 'disconnected' && <button type="button" className="pw-desktop-primary" onClick={reconnect}><RefreshCw size={15} />{state.phase === 'failed' ? '重新连接' : '连接桌面'}</button>}</>}
      </div>}
    </div>

    <div className="pw-desktop-toolbar">
      <div className="pw-desktop-status" role="status">{busy || state.controlPending || state.inputPending ? <LoaderCircle size={14} className="pw-desktop-spin" aria-hidden="true" /> : <span className={`pw-desktop-status-dot${connected && state.frameReady ? ' is-live' : ''}`} />}<span>{status}</span></div>
      <div className="pw-desktop-control-actions">
        {human ? <button type="button" className="pw-desktop-primary" disabled={state.controlPending || state.inputPending || state.inputUncertain} onClick={() => { setClickMode('left'); void connection.current?.control('release'); }}><Check size={16} />交还 AI</button> : <button type="button" className="pw-desktop-primary" disabled={!connected || !state.frameReady || state.controlPending || state.inputPending || state.inputUncertain} onClick={() => void connection.current?.control('take')}><Hand size={16} />我来操作</button>}
        <button type="button" className="pw-desktop-icon" aria-label="打开远程键盘" aria-pressed={keyboard} title="远程键盘" disabled={!human} onClick={() => setKeyboard(value => !value)}><Keyboard size={19} /></button>
        <button type="button" className="pw-desktop-icon" aria-label="更多桌面操作" aria-expanded={more} title="更多操作" onClick={() => setMore(value => !value)}><MoreHorizontal size={19} /></button>
      </div>
    </div>
    {(state.error || textError) && <div className="pw-desktop-error" role="alert"><span>{textError || state.error}</span>{state.inputUncertain && <button type="button" onClick={reconnect} disabled={state.controlPending || state.inputPending}>重新连接核对</button>}</div>}
    {human && connected && !state.inputUncertain && <p className="pw-desktop-hint">{clickMode === 'double' ? '轻点画面执行一次双击' : clickMode === 'right' ? '轻点画面执行右键' : zoom > 1 ? '拖动画面查看 · 轻点操作' : '轻点点击 · 长按右键 · 滑动滚动'}</p>}

    {more && <div className="pw-desktop-more" aria-label="桌面工具">
      <div className="pw-desktop-zoom"><button type="button" onClick={() => setViewZoom(zoom - .5)} aria-label="缩小桌面" disabled={zoom <= 1}><Minus size={17} /></button><button type="button" onClick={() => setViewZoom(1)} aria-label="还原桌面大小">{Math.round(zoom * 100)}%</button><button type="button" onClick={() => setViewZoom(zoom + .5)} aria-label="放大桌面" disabled={zoom >= 3}><Plus size={17} /></button></div>
      <button type="button" disabled={!canInput} aria-pressed={clickMode === 'double'} onClick={() => setClickMode(mode => mode === 'double' ? 'left' : 'double')}><MousePointerClick size={16} />双击</button>
      <button type="button" disabled={!canInput} aria-pressed={clickMode === 'right'} onClick={() => setClickMode(mode => mode === 'right' ? 'left' : 'right')}><MousePointer2 size={16} />右键</button>
      <div className="pw-desktop-scroll"><button type="button" disabled={!canScroll} title={canInput && !canScroll ? '先点击要滚动的窗口' : undefined} onClick={() => send({ kind: 'scroll', direction: 'up', amount: 3, by: 'line' })} aria-label="向上滚动电脑"><ArrowUp size={16} /></button><button type="button" disabled={!canScroll} title={canInput && !canScroll ? '先点击要滚动的窗口' : undefined} onClick={() => send({ kind: 'scroll', direction: 'down', amount: 3, by: 'line' })} aria-label="向下滚动电脑"><ArrowDown size={16} /></button><button type="button" disabled={!canScroll} title={canInput && !canScroll ? '先点击要滚动的窗口' : undefined} onClick={() => send({ kind: 'scroll', direction: 'left', amount: 3, by: 'line' })} aria-label="向左滚动电脑"><ArrowLeft size={16} /></button><button type="button" disabled={!canScroll} title={canInput && !canScroll ? '先点击要滚动的窗口' : undefined} onClick={() => send({ kind: 'scroll', direction: 'right', amount: 3, by: 'line' })} aria-label="向右滚动电脑"><ArrowRight size={16} /></button></div>
      <button type="button" onClick={reconnect} disabled={!hostReady || !scope?.available || busy}><RefreshCw size={15} />重连</button>
    </div>}

    {keyboard && <div className="pw-desktop-keyboard">
      <div className="pw-desktop-shortcuts" aria-label="电脑快捷键"><button type="button" disabled={!canInput} onClick={() => send({ kind: 'press_key', key: 'escape' })}>Esc</button><button type="button" disabled={!canInput} onClick={() => send({ kind: 'press_key', key: 'tab' })}>Tab</button><button type="button" disabled={!canInput} onClick={() => send({ kind: 'press_key', key: 'enter' })}><CornerDownLeft size={14} />Enter</button>{['A', 'C', 'V', 'Z'].map(key => <button key={key} type="button" disabled={!canInput} onClick={() => send({ kind: 'hotkey', keys: ['CTRL', key] })}>Ctrl+{key}</button>)}</div>
      <form className="pw-desktop-text" onSubmit={event => { event.preventDefault(); void submitText(); }}>
        <label className="pw-desktop-sr" htmlFor="desktop-text-input">发送到电脑的文字</label><textarea id="desktop-text-input" ref={textarea} value={draft} rows={2} disabled={!human || state.inputUncertain} placeholder="在手机输入，发送到电脑…" onChange={event => { setDraft(event.target.value); setTextError(''); }} onCompositionStart={() => { composing.current = true; }} onCompositionEnd={() => { composing.current = false; }} onKeyDown={event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing && !composing.current) { event.preventDefault(); void submitText(); } }} />
        <button type="submit" className="pw-desktop-primary" disabled={!canInput || !draft} aria-label="发送文字到电脑" title="发送文字">{state.inputPending ? <LoaderCircle className="pw-desktop-spin" size={17} /> : <Send size={17} />}</button>
      </form>
    </div>}
  </section>;
}
