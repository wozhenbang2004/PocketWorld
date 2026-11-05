import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
import { bridge } from './bridge';
import { projectLiveBounds, type PreviewRect } from './project-live-preview';
type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.taskId, view.section, view.resumeRevision].join('|'); };

export function ProjectLivePreview({ taskId, run, nativeVisible = true, onBackChange }: { taskId: string; run: Run; nativeVisible?: boolean; onBackChange?: (handler: (() => boolean) | null) => void }) {
  const [view, setView] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const currentPage = useRef<Data | null>(null), alive = useRef(true), generation = useRef(0), active = useRef(false), viewport = useRef<HTMLDivElement>(null);
  const owner = useRef(ownerWindow()), displayed = useRef(nativeVisible); displayed.current = nativeVisible;
  const version = generation.current;
  const close = () => { const page = currentPage.current; currentPage.current = null; generation.current++; active.current = false; setView(null); setBusy(false); if (page) void run('projectLive.close', { pageHandle: page.pageHandle }).catch(() => {}); };
  const request = async (action: string, args: Data = {}) => {
    if (!alive.current || active.current || version !== generation.current || document.hidden) return;
    const page = currentPage.current, epoch = generation.current, window = ownerWindow();
    if (action !== 'projectLive.open' && !page) return;
    active.current = true; setBusy(true); setError('');
    try { const result = await run(action, action === 'projectLive.open' ? { taskId } : { ...args, pageHandle: page!.pageHandle });
      if (!alive.current || epoch !== generation.current || window !== ownerWindow()) { if (action === 'projectLive.open' && result?.pageHandle) void run('projectLive.close', { pageHandle: result.pageHandle }).catch(() => {}); return; }
      if (result.taskId !== taskId || !result.pageHandle || page && result.pageHandle !== page.pageHandle) throw new Error('原项目活页面回执身份不可核对');
      currentPage.current = result; setView(result); return result;
    } catch (failure) { if (alive.current && epoch === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (epoch === generation.current) { active.current = false; if (alive.current) setBusy(false); } }
  };
  useEffect(() => {
    alive.current = true; owner.current = ownerWindow();
    const inspect = () => { const current = ownerWindow(); if (current !== owner.current || document.hidden) { owner.current = current; close(); } };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', close);
    return () => { alive.current = false; close(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', close); };
  }, [taskId]);
  useEffect(() => { if (!view || !view.starting && !view.mounted) return; const timer = setTimeout(() => { void request('projectLive.read'); }, view.starting ? 160 : 1200); return () => clearTimeout(timer); }, [view, busy]);
  useEffect(() => { onBackChange?.(() => { if (!currentPage.current) return false; close(); return true; }); return () => onBackChange?.(null); }, [onBackChange]);
  useLayoutEffect(() => {
    if (!view?.mounted) return;
    const pageHandle = view.pageHandle, originalWindow = ownerWindow(); let disposed = false, frame = 0, last = '';
    const send = (show: boolean) => {
      if (ownerWindow() !== originalWindow) return;
      const node = viewport.current, original = node?.getBoundingClientRect(), clips: PreviewRect[] = [];
      if (node) for (let parent = node.parentElement; parent; parent = parent.parentElement) {
        const style = getComputedStyle(parent); if (/(auto|scroll|hidden|clip)/.test(`${style.overflowX} ${style.overflowY}`)) { const clip = parent.getBoundingClientRect(); clips.push({ x: clip.x, y: clip.y, width: clip.width, height: clip.height }); }
      }
      const bounds = projectLiveBounds(original ? { x: original.x, y: original.y, width: original.width, height: original.height } : { x: 0, y: 0, width: 0, height: 0 }, window.innerWidth, window.innerHeight, clips, show && displayed.current && !!node?.isConnected && !document.hidden);
      if (bounds.visible && node) {
        const top = document.elementFromPoint(bounds.rect.x + bounds.rect.width / 2, bounds.rect.y + bounds.rect.height / 2);
        // Child dialogs are registered independently; never let the Android view cover their controls.
        if (!top || top !== node && !node.contains(top) || node.closest('[aria-hidden="true"], [inert]')) bounds.visible = false;
      }
      const args = { pageHandle, ...bounds, viewportWidth: Math.max(1, window.innerWidth), viewportHeight: Math.max(1, window.innerHeight) };
      const encoded = JSON.stringify(args); if (last === encoded) return; last = encoded;
      void run('projectLive.viewport', args).catch(() => {});
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(() => { if (!disposed) send(true); }); };
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule); if (viewport.current) resize?.observe(viewport.current);
    const overlays = new MutationObserver(schedule); overlays.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['aria-hidden', 'inert', 'open', 'data-state'] });
    window.addEventListener('resize', schedule); window.addEventListener('scroll', schedule, true); document.addEventListener('focusin', schedule); window.visualViewport?.addEventListener('resize', schedule); window.visualViewport?.addEventListener('scroll', schedule);
    send(true); schedule();
    return () => { disposed = true; cancelAnimationFrame(frame); resize?.disconnect(); overlays.disconnect(); window.removeEventListener('resize', schedule); window.removeEventListener('scroll', schedule, true); document.removeEventListener('focusin', schedule); window.visualViewport?.removeEventListener('resize', schedule); window.visualViewport?.removeEventListener('scroll', schedule); send(false); };
  }, [view?.mounted, view?.pageHandle, nativeVisible]);
  return <section><div className="pw-section-heading"><div><h3>手机项目运行页面</h3><p className="pw-muted">连接原任务已运行的开发服务。</p></div><Button variant="secondary" disabled={busy || !nativeVisible} onClick={() => { if (view) close(); else void request('projectLive.open'); }}>{view ? '关闭页面' : '查看原开发服务'}</Button></div>
    {view && <>{rows(view.services).map(service => <section key={service.serviceHandle}><p>原服务 {service.executionId}</p>{service.sourceGeneration != null && <p className="pw-field-hint">已批准的源码代次 {service.sourceGeneration}。配置、依赖与新文件仍需原任务重新核对。</p>}<Button variant="secondary" disabled={busy || view.starting || !nativeVisible} onClick={() => void request('projectLive.show', { serviceHandle: service.serviceHandle })}>{view.starting ? '正在连接开发服务…' : '打开运行页面'}</Button></section>)}{!rows(view.services).length && <p>原任务当前没有可连接的已运行开发服务。请先核对原构建与运行回执。</p>}
      {view.mounted && <><div ref={viewport} className="pw-remote-preview-viewport" style={{ minHeight: 420 }} aria-label="原手机项目隔离运行页面" /><div className="pw-form-actions"><Button variant="ghost" disabled={busy || !nativeVisible} onClick={() => void request('projectLive.reload')}>刷新这个原页面</Button><Button variant="ghost" onClick={close}>关闭页面</Button></div></>}
      {view.observed && <Accordion><AccordionItem title="原连接与页面范围"><p>本次实际观察了原服务的 HTTP 与 WebSocket；这不代表业务验收通过。</p><p>页面随获准的源码更新。要保留这个版本，请通过原任务构建并收集固定成果。</p><p>页面使用独立临时资料，不接入应用权限或浏览器账号。离页、切后台会关闭此预览；是否停止服务仍以原任务回执为准。</p><p style={{ overflowWrap: 'anywhere' }}>源码摘要：{view.observed.sourceDigest}</p></AccordionItem></Accordion>}
    </>}{(error || view?.error) && <p role="alert">{error || view?.error}</p>}
  </section>;
}
