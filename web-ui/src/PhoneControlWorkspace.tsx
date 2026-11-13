/** Uses the existing phone-viewer frame/card/controls adapted from OpenMuse (MIT,
 * ../THIRD-PARTY-NOTICES.md), and the project's forms-library. This is single-step
 * human input, not the automatic preview feed or a model's image authority. */
import { useEffect, useRef, useState, type PointerEvent } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, TextAreaField } from './forms-library';
import { IconRefresh, IconStop } from './icons';
import './phone-viewer.css';
import './device-control-reading.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const time = (value: number) => new Date(value).toLocaleTimeString();

export function PhoneControlWorkspace({ taskId, hasScope, started, visible, run, onChanged }: { taskId: string; hasScope: boolean; started: boolean; visible: boolean; run: Run; onChanged: () => void }) {
  const [view, setView] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [ack, setAck] = useState(false), [uncertain, setUncertain] = useState(false);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [decoded, setDecoded] = useState(''), [node, setNode] = useState(''), [text, setText] = useState(''), [app, setApp] = useState('');
  const page = useRef(''), epoch = useRef(0), working = useRef(false), mounted = useRef(true), current = useRef<Data | null>(null);
  const gesture = useRef<{ pointer: number; x: number; y: number; at: number; frame: string; serial: number; rect: { left: number; top: number; width: number; height: number } } | null>(null);
  current.current = view;
  const merge = (value: Data) => {
    if (value?.taskId && value.taskId !== taskId) throw new Error('回执不属于原手控任务。');
    if (value.pageHandle) page.current = value.pageHandle;
    setView(previous => {
      const previousFrame = previous?.frame;
      const frame = value.frame && value.active && previousFrame && previousFrame.frameHandle === value.frame.frameHandle && !value.frame.dataUrl ? { ...value.frame, dataUrl: previousFrame.dataUrl } : value.frame;
      return { ...value, frame: value.active ? frame : undefined };
    });
    setUncertain(false);
  };
  const command = async (action: string, args: Data = {}, quiet = false) => {
    if (working.current || document.visibilityState !== 'visible') return;
    const generation = epoch.current; working.current = true; if (!quiet) { setBusy(true); setError(''); }
    try { const result = await run(action, args); if (mounted.current && generation === epoch.current) { merge(result); if (action === 'phoneControl.start') onChanged(); } }
    catch (failure) { if (mounted.current && generation === epoch.current) { setError(failure instanceof Error ? failure.message : String(failure)); if (action === 'phoneControl.input' || action === 'phoneControl.start') { setUncertain(true); setDecoded(''); } } }
    finally { if (generation === epoch.current) { working.current = false; if (mounted.current) setBusy(false); } }
  };
  const close = () => {
    const handle = page.current; page.current = ''; epoch.current++; working.current = false; gesture.current = null; current.current = null;
    if (mounted.current) { setView(null); setBusy(false); setDecoded(''); setAck(false); setText(''); setControlsVisible(true); }
    if (handle) void run('phoneControl.close', { pageHandle: handle }).catch(() => {});
  };
  const closeCurrent = useRef(close); closeCurrent.current = close;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; closeCurrent.current(); }; }, [taskId]);
  useEffect(() => { if (!visible) closeCurrent.current(); }, [visible]);
  useEffect(() => { const hide = () => { if (document.visibilityState !== 'visible') closeCurrent.current(); }; const leave = () => closeCurrent.current(); document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', leave); return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', leave); }; }, []);
  useEffect(() => { setDecoded(''); setNode(''); gesture.current = null; }, [view?.frame?.frameHandle]);
  useEffect(() => {
    if (!view?.active || !page.current || !visible) return;
    // Checks original local authority/receipt only. Never observes the phone or spends a step.
    const timer = window.setInterval(() => { if (!working.current && page.current) void command('phoneControl.read', { pageHandle: page.current }, true); }, 3000);
    return () => window.clearInterval(timer);
  }, [view?.active, view?.sessionId, visible]);
  const frame = view?.frame, ready = !!view?.active && !busy && !uncertain && !view?.unresolved && view?.sourceCallsRemaining > 0 && decoded === frame?.frameHandle;
  const supports = (name: string) => Array.isArray(view?.availableTools) && view.availableTools.includes(name);
  const input = (tool: string, intent: Data = {}) => {
    const source = current.current;
    if (!source?.active || working.current || uncertain || source.unresolved || source.sourceCallsRemaining < 1) return;
    setDecoded(''); gesture.current = null; if (tool === 'phone_type') setText('');
    void command('phoneControl.input', { pageHandle: page.current, sessionId: source.sessionId, serial: source.serial, frameHandle: source.frame?.frameHandle || '', tool, intent });
  };
  const point = (event: PointerEvent<HTMLImageElement>) => {
    if (!frame) return null; const box = event.currentTarget.getBoundingClientRect(), scale = Math.min(box.width / frame.width, box.height / frame.height), width = frame.width * scale, height = frame.height * scale;
    const x = (event.clientX - box.left - (box.width - width) / 2) / width, y = (event.clientY - box.top - (box.height - height) / 2) / height;
    return Number.isFinite(x) && Number.isFinite(y) && x >= 0 && x <= 1 && y >= 0 && y <= 1 ? { x: Math.round(x * 1000), y: Math.round(y * 1000) } : null;
  };
  const down = (event: PointerEvent<HTMLImageElement>) => {
    if (!ready || (!supports('phone_tap') && !supports('phone_swipe')) || event.button !== 0 || !event.isPrimary) return; const at = point(event); if (!at) return;
    event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId); const box = event.currentTarget.getBoundingClientRect();
    gesture.current = { ...at, pointer: event.pointerId, at: performance.now(), frame: frame.frameHandle, serial: view!.serial, rect: { left: box.left, top: box.top, width: box.width, height: box.height } };
  };
  const up = (event: PointerEvent<HTMLImageElement>) => {
    const first = gesture.current; gesture.current = null;
    if (!first) return;
    const box = event.currentTarget.getBoundingClientRect();
    // A resize, scroll or chrome transition must not reinterpret a held touch
    // against a different image box. The owner can start a fresh gesture.
    if (box.left !== first.rect.left || box.top !== first.rect.top || box.width !== first.rect.width || box.height !== first.rect.height) return;
    const end = point(event);
    if (!ready || !first || first.pointer !== event.pointerId || first.frame !== frame.frameHandle || first.serial !== view!.serial || !end) return;
    event.preventDefault(); const distance = Math.hypot(end.x - first.x, end.y - first.y);
    if (distance < 12 && supports('phone_tap')) input('phone_tap', { x: end.x, y: end.y });
    else if (distance >= 12 && supports('phone_swipe')) input('phone_swipe', { x1: first.x, y1: first.y, x2: end.x, y2: end.y, durationMs: Math.max(100, Math.min(2000, Math.round(performance.now() - first.at))) });
  };
  const stop = async () => {
    const handle = page.current; if (!handle) return; epoch.current++; working.current = false; setBusy(false); setDecoded(''); setView(old => old ? { ...old, active: false, frame: undefined } : old);
    const generation = epoch.current;
    try { const result = await run('phoneControl.stop', { pageHandle: handle }); if (mounted.current && generation === epoch.current) { merge(result); onChanged(); } }
    catch (failure) { if (mounted.current && generation === epoch.current) setError('停止回执尚未确认，已禁用本页新输入。请核对原任务。' + (failure instanceof Error ? failure.message : '')); }
  };
  return <section className="pw-phone-viewer-card pw-phone-control-reading" aria-label="主人手动控制手机">
    <header className="pw-phone-viewer-card-header"><div className="pw-phone-viewer-card-title"><strong>手动控制手机</strong><span>手动操作 · 按需刷新画面</span></div>{view?.active && <Button variant="ghost" aria-expanded={controlsVisible} onClick={()=>{gesture.current=null;setControlsVisible(value=>!value)}}>{controlsVisible?'收起控件':'显示控件'}</Button>}{view?.active && <Button variant="secondary" leadingIcon={IconStop} onClick={() => void stop()}>停止</Button>}</header>
    {!view && <><p>{hasScope ? started ? '这份手控已有原执行。离页后不会恢复旧输入权限；请在原任务轨迹核对已有操作，需要再次控制请重新准备并审阅。' : '设备范围已保存。开始前再核对屏幕、全屏捕获与输入、期限和额度。' : '先在上方选择一个手机屏幕和准确应用范围。'}</p>{hasScope && !started && <Button disabled={busy} onClick={() => void command('phoneControl.open', { taskId })}>核对并开始手动控制</Button>}</>}
    {view?.reviewReady && <>
      <dl className="pw-facts"><div><dt>接收设备</dt><dd>{view.range.targetDeviceId}</dd></div><div><dt>屏幕</dt><dd>{view.range.resourceBinding.screenMode === 'background' ? '独立后台屏' : '手机主屏'}</dd></div><div><dt>应用名单</dt><dd>{view.range.resourceBinding.packageNames.join('、')}</dd></div><div><dt>截止</dt><dd>{time(Math.min(view.deadlineAt, view.range.deadline))}</dd></div><div><dt>有限调用</dt><dd>原任务合计 {view.toolLimit} 次，其中对端 {view.range.toolBudget} 次；模型 0 次</dd></div></dl>
      <p>全屏捕获与全屏输入：画面可能包含其他窗口，坐标和系统按键不能由应用名单隔离。你每次点击、滑动、输入或打开应用都是一次明确动作，不向助手授予自动操作权限。画面只在本页短时显示，原设备可能按它自己的策略保留观察成果。操作与输入参数会记入原任务审计，请勿填写密码等凭据；本机原安全门禁不变，敏感输入仍需在对端完成。</p>
      <CheckboxField isSelected={ack} isDisabled={busy} onChange={setAck}>我已核对这一个屏幕、应用、全屏捕获与输入范围、有限时长和额度；允许本页接收我的逐次操作</CheckboxField>
      <div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={close}>返回核对范围</Button><Button disabled={busy || !ack} onClick={() => void command('phoneControl.start', { pageHandle: page.current, acknowledged: true })}>开始本次手控</Button></div>
    </>}
    {view && !view.reviewReady && <>
      <p role="status">{view.notice}</p><p className="pw-field-hint">设备 {String(view.targetDeviceId || '').slice(-8)} · {view.resourceBinding?.screenMode === 'background' ? '独立后台屏' : '主屏'} · 截止 {time(view.deadlineAt)} · 本机已用 {view.toolsUsed}/{view.toolLimit} 次（另含对端预留）</p>
      {view.active && controlsVisible && <div className="pw-phone-viewer-controls pw-material-navigation"><Button leadingIcon={IconRefresh} disabled={busy || uncertain || view.unresolved || view.sourceCallsRemaining < 1 || !supports('phone_observe')} onClick={() => input('phone_observe')}>刷新画面</Button><Button variant="secondary" disabled={!ready || !supports('phone_back')} onClick={() => input('phone_back')}>返回</Button><Button variant="secondary" disabled={!ready || !supports('phone_home')} onClick={() => input('phone_home')}>主屏幕</Button></div>}
      {frame?.dataUrl && <figure className="pw-phone-viewer-frame"><div className="pw-phone-viewer-image"><img src={frame.dataUrl} width={frame.width} height={frame.height} alt="原手机本次观察画面；可点击或拖动操作一次" draggable={false} style={{ touchAction: ready ? 'none' : 'auto', cursor: ready ? 'crosshair' : 'default' }} onLoad={event => { const image = event.currentTarget; if (image.naturalWidth === frame.width && image.naturalHeight === frame.height) setDecoded(frame.frameHandle); else { setDecoded(''); setError('画面实际尺寸不匹配，已禁用输入。'); } }} onError={() => { setDecoded(''); setError('画面无法解码，已禁用输入。'); }} onPointerDown={down} onPointerUp={up} onPointerCancel={() => { gesture.current = null; }} /></div><figcaption><span>{ready ? supports('phone_tap') || supports('phone_swipe') ? `${supports('phone_tap') ? '可点击一次' : ''}${supports('phone_tap') && supports('phone_swipe') ? '或' : ''}${supports('phone_swipe') ? '拖动一次' : ''}；操作后刷新` : controlsVisible ? '当前范围未授权点图或滑动，可使用已授权控件操作' : '当前范围未授权点图或滑动，请先点“显示控件”使用已授权控件' : '画面尚不可操作'}</span><time>{time(frame.capturedAt)}</time></figcaption></figure>}
      {view.active && controlsVisible && <Accordion>
        {supports('phone_launch_app') && <AccordionItem id="open-app" title="打开授权应用"><SelectField label="本次范围内的应用" value={app} onChange={setApp} options={[{ id: '', label: '选择应用' }, ...(view.resourceBinding?.packageNames || []).map((name: string) => ({ id: name, label: name }))]} isDisabled={busy || uncertain || view.unresolved} /><Button disabled={busy || uncertain || view.unresolved || view.sourceCallsRemaining < 1 || !app} onClick={() => input('phone_launch_app', { packageName: app })}>打开所选应用</Button><p>打开后再刷新画面，不会继续自动点击。</p></AccordionItem>}
        {!!frame?.nodes?.length && <AccordionItem id="node-input" title="按当前控件点击或输入"><SelectField label="本次画面中的控件" value={node} onChange={setNode} isDisabled={!ready} options={[{ id: '', label: '选择控件' }, ...frame.nodes.map((item: Data, index: number) => ({ id: item.choiceId, label: `${index + 1}. ${item.label || '未命名控件'}${item.editable ? ' · 输入框' : ''}` }))]} /><TextAreaField label="输入文字" value={text} onChange={setText} isDisabled={!ready} /><div className="pw-form-actions"><Button variant="secondary" disabled={!ready || !node || !supports('phone_click_node')} onClick={() => input('phone_click_node', { nodeChoice: node })}>点击所选控件</Button><Button disabled={!ready || !node || !frame.nodes.find((item: Data) => item.choiceId === node)?.editable || text.length > 12000 || !supports('phone_type')} onClick={() => input('phone_type', { nodeChoice: node, text })}>输入到所选控件</Button></div></AccordionItem>}
      </Accordion>}
      {view.active && view.sourceCallsRemaining < 1 && <p role="status">本次可派发额度已用完，只能核对原回执或停止；不会自动补满。</p>}
      {(view.unresolved || uncertain) && <p role="alert">上次结果尚未确认，不能重复触摸或换编号重发。只核对原操作，或停止本次手控。</p>}
      <div className="pw-form-actions">{page.current && <Button variant="ghost" disabled={busy} onClick={() => void command('phoneControl.read', { pageHandle: page.current })}>核对当前状态</Button>}{view.active && view.lastOperationId && <Button variant="secondary" disabled={busy} onClick={() => void command('phoneControl.reconcile', { pageHandle: page.current, sessionId: view.sessionId })}>核对原操作</Button>}</div>
      {view.lastOperationId && <Accordion><AccordionItem id="original-receipt" title="原操作与审计回执"><p>原编号 {view.lastOperationId}</p><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(view.receipt, null, 2)}</pre></AccordionItem></Accordion>}
    </>}
    {error && <p role="alert" className="pw-phone-viewer-error">{error}</p>}
    {busy && <p role="status">正在取得原回执，不重复派发…</p>}
  </section>;
}
