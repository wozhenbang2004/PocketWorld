import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { RiArrowLeftLine, RiCloseLine, RiCursorLine, RiRefreshLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Button, IconButton } from './forms-library';
import { ProgressCircle } from './library';
import { previewReturned, previewViewport, projectPreviewState, samePreviewWindow, type ProjectPreviewState, type PreviewWindow } from './project-preview';
import './project-preview.css';

type Data = Record<string, any>;
type Action = 'projects.preview.mode' | 'projects.preview.reload' | 'projects.preview.close' | 'projects.preview.return';
type Options = { value: unknown; projectId: string; resumeRevision: number; active: boolean; blocked: boolean; can: (action: string) => boolean; onReturned: () => void };

/** Local presentation intent. No element, URL or draft authority is constructed here. */
export function useProjectPreview(options: Options) {
  const [expanded, setExpanded] = useState(false), [opened, setOpened] = useState<ProjectPreviewState | null>(null);
  const [busy, setBusy] = useState(''), [error, setError] = useState(''), [unknown, setUnknown] = useState(false);
  const generation = useRef(0), pending = useRef(false), optionsRef = useRef(options);
  optionsRef.current = options;
  const candidate = projectPreviewState(options.value);
  const native = candidate?.metadata.projectId === options.projectId ? candidate : null;
  const view = native && (!opened || native.sessionId === opened.sessionId) ? native : null;
  const viewRef = useRef(view); viewRef.current = view;
  const ticket = (): PreviewWindow => ({ projectId: optionsRef.current.projectId, documentEpoch: bridge.getDocumentEpoch(), resumeRevision: optionsRef.current.resumeRevision });
  const current = (original: PreviewWindow, request: number) => generation.current === request && optionsRef.current.active && !optionsRef.current.blocked &&
    !document.hidden && samePreviewWindow(original, ticket());

  const hide = useCallback(() => {
    generation.current++; setExpanded(false);
    if (pending.current) { setUnknown(true); setError('原操作回执尚未核对；没有重发，草稿仍保留。'); }
    pending.current = false; setBusy('');
  }, []);
  useEffect(() => { if (!options.active) hide(); }, [options.active, hide]);
  useEffect(() => {
    generation.current++; pending.current = false; setBusy(''); setExpanded(false); setOpened(null); setError(''); setUnknown(false);
  }, [options.projectId]);
  useEffect(() => {
    let epoch = bridge.getDocumentEpoch();
    const invalidate = () => {
      const next = bridge.getDocumentEpoch();
      if (next !== epoch) { epoch = next; hide(); }
    };
    const visibility = () => { if (document.hidden) hide(); };
    const unsubscribe = bridge.subscribe(invalidate);
    document.addEventListener('visibilitychange', visibility);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', visibility); generation.current++; };
  }, [hide]);

  const open = () => {
    const context = optionsRef.current;
    if (pending.current || !context.active || document.hidden) return;
    // Returning to the existing view retains its native Document and staged refs.
    const retained = projectPreviewState(context.value);
    if (retained && retained.metadata.projectId === context.projectId) {
      setOpened(retained); setExpanded(true); setError(''); setUnknown(false); return;
    }
    if (!context.can('projects.preview')) return;
    const original = ticket(), request = ++generation.current;
    pending.current = true; setExpanded(true); setOpened(null); setBusy('open'); setError(''); setUnknown(false);
    void bridge.command('projects.preview', { projectId: original.projectId }).then(value => {
      if (!current(original, request)) return;
      const accepted = projectPreviewState(value);
      if (!accepted) throw new Error('未取得原项目预览的完整回执，请核对原状态。');
      setOpened(accepted);
    }).catch(failure => {
      if (!current(original, request)) return;
      setUnknown(failure?.code === 'outcome_unknown'); setError(failure instanceof Error ? failure.message : String(failure));
    }).finally(() => { if (generation.current === request) { pending.current = false; setBusy(''); } });
  };

  const action = (name: Action, extra: Data = {}) => {
    const originalView = viewRef.current, context = optionsRef.current;
    if (!originalView || pending.current || unknown || !context.active || context.blocked || document.hidden || !context.can(name)) return;
    const original = ticket(), request = ++generation.current;
    pending.current = true; setBusy(name); setError('');
    void bridge.command(name, { sessionId: originalView.sessionId, ...extra }).then(async value => {
      if (!current(original, request)) return;
      // Native may publish the released (null) view before its close receipt.
      // A replacement session still prevents the old callback touching its UI.
      const visibleSession = viewRef.current?.sessionId;
      if (name === 'projects.preview.close' ? visibleSession && visibleSession !== originalView.sessionId : visibleSession !== originalView.sessionId) return;
      if (name === 'projects.preview.return') {
        if (!previewReturned(value, originalView, originalView.conversationId, originalView.conversationId))
          throw new Error('原对话返回回执身份不符，草稿仍保留。');
        // Receipt and snapshot are separate native messages. Await only this
        // original target; a late snapshot never selects or creates a conversation.
        await new Promise<void>((resolve, reject) => {
          let unsubscribe = () => {};
          const timeout = window.setTimeout(() => {
            unsubscribe(); const failure = new Error('已收到返回回执，尚未读到原草稿；没有再次操作。');
            Object.assign(failure, { code: 'outcome_unknown' }); reject(failure);
          }, 5000);
          const check = () => {
            if (!current(original, request)) { clearTimeout(timeout); unsubscribe(); resolve(); return; }
            const now = bridge.getState();
            if (previewReturned(value, originalView, now.state.conversationId, now.composer?.conversationId)) {
              clearTimeout(timeout); unsubscribe(); resolve();
            }
          };
          unsubscribe = bridge.subscribe(check); check();
        });
        if (!current(original, request)) return;
        setExpanded(false); context.onReturned();
      } else if (name === 'projects.preview.close') {
        if (value?.closed !== true || value?.sessionId !== originalView.sessionId) throw new Error('未确认原预览已关闭，请核对原状态。');
        setOpened(null); setExpanded(false);
      }
    }).catch(failure => {
      if (!current(original, request)) return;
      setUnknown(failure?.code === 'outcome_unknown'); setError(failure instanceof Error ? failure.message : String(failure));
    }).finally(() => { if (generation.current === request) { pending.current = false; setBusy(''); } });
  };
  const handleBack = () => {
    if (!expanded) return false;
    if (view?.selecting && !busy && !unknown) action('projects.preview.mode', { enabled: false });
    else hide();
    return true;
  };
  return { expanded, view, busy, error, unknown, open, hide, action, handleBack, blocked: options.blocked, can: options.can };
}

export function ProjectPreviewPane({ control, composer }: { control: ReturnType<typeof useProjectPreview>; composer: Data }) {
  const viewport = useRef<HTMLDivElement>(null);
  const { expanded, view, busy, error, unknown, blocked, can } = control;
  const sessionId = view?.sessionId || '';
  useLayoutEffect(() => {
    if (!expanded || !sessionId || !can('projects.preview.viewport')) return;
    const epoch = bridge.getDocumentEpoch(); let frame = 0, disposed = false, last = '';
    const send = (visible: boolean) => {
      if (bridge.getDocumentEpoch() !== epoch) return;
      const node = viewport.current, bounds = node?.getBoundingClientRect();
      const payload = previewViewport(sessionId, visible && !!node?.isConnected && !blocked && !document.hidden,
        bounds ? { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height } : null, window.innerWidth, window.innerHeight);
      const encoded = JSON.stringify(payload); if (last === encoded) return; last = encoded;
      void bridge.command('projects.preview.viewport', payload).catch(() => { /* Native window invalidation also hides its renderer. */ });
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(() => { if (!disposed) send(true); }); };
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    if (viewport.current) observer?.observe(viewport.current);
    window.addEventListener('resize', schedule); window.addEventListener('scroll', schedule, true);
    window.visualViewport?.addEventListener('resize', schedule); window.visualViewport?.addEventListener('scroll', schedule);
    document.addEventListener('visibilitychange', schedule);
    send(true); schedule();
    return () => {
      disposed = true; cancelAnimationFrame(frame); observer?.disconnect();
      window.removeEventListener('resize', schedule); window.removeEventListener('scroll', schedule, true);
      window.visualViewport?.removeEventListener('resize', schedule); window.visualViewport?.removeEventListener('scroll', schedule);
      document.removeEventListener('visibilitychange', schedule); send(false);
    };
  }, [expanded, sessionId, blocked, can]);
  if (!expanded) return null;
  const refs = Array.isArray(composer.previewRefs) ? composer.previewRefs : [];
  const count = view ? refs.filter((ref: Data) => ref.previewId === view.metadata.previewId).length : 0;
  const problem = error || view?.error;
  return <section className="pw-preview-pane" aria-label="项目预览" aria-busy={!!busy}>
    <div className="pw-preview-actions">
      <Button variant={view?.selecting ? 'secondary' : 'primary'} leadingIcon={RiCursorLine} size="small"
        disabled={!view?.ready || !!busy || unknown || blocked || !can('projects.preview.mode')} aria-pressed={view?.selecting === true}
        onClick={() => control.action('projects.preview.mode', { enabled: !view?.selecting })}>{view?.selecting ? '退出选择' : '选择元素'}</Button>
      <span className="pw-grow"/>
      <IconButton icon={RiRefreshLine} aria-label="重新载入预览" title="重新载入会使旧元素引用失效" disabled={!view || !!busy || unknown || !can('projects.preview.reload')} onClick={() => control.action('projects.preview.reload')}/>
      <IconButton icon={RiCloseLine} aria-label="关闭预览" title="关闭后需要重新选择元素" disabled={!view || !!busy || unknown || !can('projects.preview.close')} onClick={() => control.action('projects.preview.close')}/>
    </div>
    <div className="pw-preview-context">
      <p className="pw-muted">{view?.selecting ? '点选画面中的元素，加入原对话草稿；不会执行页面按钮。' : '预览已采集的版本。开启选择后，点选元素加入草稿。'}</p>
      {count > 0 && <p className="pw-muted" role="status">已加入 {count} 处元素引用，尚未发送。</p>}
      {problem && <p className="pw-error" role="alert">{problem}</p>}
      {unknown && <p className="pw-muted">原操作结果待核对，没有重发。请返回后核对原预览与草稿。</p>}
    </div>
    <div ref={viewport} className="pw-preview-viewport" aria-label="原生隔离预览画面" data-preview-session={sessionId || undefined}>
      {!view?.ready && <div className="pw-preview-placeholder">{busy || view && !problem ? <><ProgressCircle isIndeterminate size="sm" aria-label="正在打开已采集预览"/><p>正在打开已采集预览…</p></> : <p>{problem ? '原文件和草稿仍保留。' : '当前项目没有可用的已采集预览。'}</p>}</div>}
    </div>
    <footer className="pw-preview-footer">
      <Button variant="ghost" leadingIcon={RiArrowLeftLine} size="small" onClick={control.hide}>返回项目文件</Button>
      {view && <Button variant="secondary" size="small" disabled={!!busy || unknown || !can('projects.preview.return')} onClick={() => control.action('projects.preview.return')}>返回原对话</Button>}
    </footer>
  </section>;
}
