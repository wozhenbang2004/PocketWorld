import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconClose, IconCursor, IconHistory, IconRefresh } from './icons';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, SelectField } from './forms-library';
import { ProgressCircle } from './library';
import { MotionFeedback } from './library/journey-motion';
import { packagePreviewMatches, previewReturned, previewViewport, projectPreviewState, samePreviewWindow, type PackagePreviewChoices, type ProjectPreviewState, type PreviewWindow } from './project-preview';
import './project-preview.css';

type Data = Record<string, any>;
type Action = 'projects.preview.mode' | 'projects.preview.reload' | 'projects.preview.close' | 'projects.preview.return';
type PackageExportTarget = Pick<PackagePreviewChoices, 'taskId' | 'operationId' | 'artifactId' | 'artifactSha256'>;
type PackageExportState = PackageExportTarget & { kind: 'project_package_export'; requestId: string; projectId: string; status: string; message: string; exported: boolean; bytes?: number };
type Options = { value: unknown; exportValue?: unknown; projectId: string; resumeRevision: number; active: boolean; blocked: boolean; can: (action: string) => boolean; onReturned: () => void; onPackageTask: (taskId: string, entry?: 'process' | 'review') => void };
const exportPhases = new Set(['checking', 'document_requested', 'selection_returned', 'writing', 'exported', 'cancelled', 'rejected', 'unknown']);
const exportPendingPhases = new Set(['checking', 'document_requested', 'selection_returned', 'writing']);
const sameExportTarget = (value: PackageExportTarget | null, target: PackageExportTarget | null) => !!value && !!target &&
  value.taskId === target.taskId && value.operationId === target.operationId && value.artifactId === target.artifactId && value.artifactSha256 === target.artifactSha256;
function packageExportState(value: unknown): PackageExportState | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const source = value as Data;
  if (source.kind !== 'project_package_export' || !['taskId', 'operationId', 'artifactId', 'projectId', 'requestId'].every(key => typeof source[key] === 'string' && source[key].length > 0 && source[key].length <= 200) ||
      typeof source.artifactSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(source.artifactSha256) || !exportPhases.has(source.status) || typeof source.message !== 'string' ||
      source.exported !== (source.status === 'exported') || source.status === 'exported' && (!Number.isSafeInteger(source.bytes) || source.bytes <= 0)) return null;
  return source as PackageExportState;
}

/** Local presentation intent. No element, URL or draft authority is constructed here. */
export function useProjectPreview(options: Options) {
  const [expanded, setExpanded] = useState(false), [opened, setOpened] = useState<ProjectPreviewState | null>(null);
  const [busy, setBusy] = useState(''), [error, setError] = useState(''), [unknown, setUnknown] = useState(false);
  const [exportReceipt, setExportReceipt] = useState<PackageExportState | null>(null);
  const exportFlight = useRef<{ target: PackageExportTarget; previousRequestId: string } | null>(null);
  const [choices, setChoices] = useState<PackagePreviewChoices | null>(null), [selectedEntry, setSelectedEntry] = useState(''), [packageShowing, setPackageShowing] = useState(false);
  const generation = useRef(0), pending = useRef(false), optionsRef = useRef(options);
  optionsRef.current = options;
  const candidate = projectPreviewState(options.value);
  const native = candidate?.metadata.projectId === options.projectId ? candidate : null;
  const view = native && (!opened || native.sessionId === opened.sessionId) &&
    (!choices || packageShowing && packagePreviewMatches(native, choices, selectedEntry)) ? native : null;
  const viewRef = useRef(view); viewRef.current = view;
  const exportTarget: PackageExportTarget | null = choices ? { taskId: choices.taskId, operationId: choices.operationId, artifactId: choices.artifactId, artifactSha256: choices.artifactSha256 } :
    view?.metadata.origin === 'fixed_workspace_project_artifact' && view.packageOperationId && typeof view.metadata.artifactId === 'string' && typeof view.metadata.artifactSha256 === 'string' && /^[a-f0-9]{64}$/.test(view.metadata.artifactSha256) ?
      { taskId: view.taskId, operationId: view.packageOperationId, artifactId: view.metadata.artifactId, artifactSha256: view.metadata.artifactSha256 } : null;
  const nativeExport = packageExportState(options.exportValue);
  const currentNativeExport = nativeExport?.projectId === options.projectId && sameExportTarget(nativeExport, exportTarget) ? nativeExport : null;
  const currentExportReceipt = exportReceipt?.projectId === options.projectId && sameExportTarget(exportReceipt, exportTarget) ? exportReceipt : null;
  const exportState = currentExportReceipt && currentExportReceipt.requestId !== currentNativeExport?.requestId && exportPendingPhases.has(currentExportReceipt.status) ?
    currentExportReceipt : currentNativeExport || currentExportReceipt;
  const exportBusy = busy === 'package.export' || !!exportState && exportPendingPhases.has(exportState.status);
  const exportBusyRef = useRef(exportBusy); exportBusyRef.current = exportBusy;
  const ticket = (): PreviewWindow => ({ projectId: optionsRef.current.projectId, documentEpoch: bridge.getDocumentEpoch(), resumeRevision: optionsRef.current.resumeRevision });
  const current = (original: PreviewWindow, request: number) => generation.current === request && optionsRef.current.active && !optionsRef.current.blocked &&
    !document.hidden && Number((bridge.getState().view as { resumeRevision?: number })?.resumeRevision || 0) === original.resumeRevision && samePreviewWindow(original, ticket());

  const hide = useCallback(() => {
    generation.current++; setExpanded(false);
    if (pending.current) { setUnknown(true); setError('原操作回执尚未核对；没有重发，草稿仍保留。'); }
    pending.current = false; setBusy('');
  }, []);
  useEffect(() => { if (!options.active) hide(); }, [options.active, hide]);
  useEffect(() => {
    generation.current++; pending.current = false; setBusy(''); setExpanded(false); setOpened(null); setError(''); setUnknown(false);
    setChoices(null); setSelectedEntry(''); setPackageShowing(false);
    exportFlight.current = null; setExportReceipt(null);
  }, [options.projectId]);
  useEffect(() => {
    const flight = exportFlight.current, receipt = packageExportState(options.exportValue);
    if (!flight || !receipt || receipt.projectId !== options.projectId || receipt.requestId === flight.previousRequestId || !sameExportTarget(receipt, flight.target)) return;
    setExportReceipt(receipt);
    if (receipt.status !== 'checking') { exportFlight.current = null; pending.current = false; setBusy(''); setUnknown(false); setError(''); }
  }, [options.exportValue, options.projectId]);
  useEffect(() => {
    let epoch = bridge.getDocumentEpoch();
    const invalidate = () => {
      const next = bridge.getDocumentEpoch();
      if (next !== epoch) {
        epoch = next;
        if (!exportFlight.current && !exportBusyRef.current) hide();
        else { generation.current++; pending.current = false; setBusy(''); }
      }
    };
    const visibility = () => {
      if (!document.hidden) return;
      // The native picker suspends this document. Keep only the original package
      // presentation; native resume/window guards still own every later command.
      if (exportFlight.current || exportBusyRef.current) { generation.current++; pending.current = false; setBusy(''); }
      else hide();
    };
    const unsubscribe = bridge.subscribe(invalidate);
    document.addEventListener('visibilitychange', visibility);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', visibility); generation.current++; };
  }, [hide]);

  const open = () => {
    const context = optionsRef.current;
    if (pending.current || !context.active || document.hidden) return;
    if (choices && exportState) { setExpanded(true); return; }
    if (exportBusy) return;
    setChoices(null); setSelectedEntry(''); setPackageShowing(false);
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

  const showPackage = (original: PackagePreviewChoices) => {
    const context = optionsRef.current;
    if (!context.active || context.blocked || document.hidden || original.projectId !== context.projectId) return false;
    generation.current++; pending.current = false; setBusy(''); setError(''); setUnknown(false); setOpened(null);
    setChoices(original); setSelectedEntry(original.mode === 'web_output' ? original.entryPath : ''); setPackageShowing(false); setExpanded(true);
    return true;
  };
  const openPackage = () => {
    const context = optionsRef.current;
    if (!choices || !choices.entryChoices.includes(selectedEntry) || pending.current || exportBusy || unknown || !context.active || context.blocked || document.hidden || !context.can('projects.package.preview.open')) return;
    // Exact reuse is allowed only after the owner selected this original operation and entry.
    const retained = projectPreviewState(context.value);
    if (packagePreviewMatches(retained, choices, selectedEntry)) { setOpened(retained); setPackageShowing(true); setError(''); return; }
    const original = ticket(), request = ++generation.current;
    pending.current = true; setBusy('package.open'); setError('');
    void bridge.command('projects.package.preview.open', { taskId: choices.taskId, operationId: choices.operationId, entryPath: selectedEntry }).then(value => {
      if (!current(original, request)) return;
      const accepted = projectPreviewState(value);
      if (!packagePreviewMatches(accepted, choices, selectedEntry)) throw new Error('固定包预览回执与所选原操作或入口不符，未打开另一版本。');
      setOpened(accepted); setPackageShowing(true);
    }).catch(failure => {
      if (!current(original, request)) return;
      setUnknown(failure?.code === 'outcome_unknown'); setError(failure instanceof Error ? failure.message : String(failure));
    }).finally(() => { if (generation.current === request) { pending.current = false; setBusy(''); } });
  };

  const exportPackage = () => {
    const context = optionsRef.current, target = exportTarget;
    if (!target || pending.current || exportBusy || unknown || !context.active || context.blocked || document.hidden || !context.can('projects.package.export')) return;
    const original = ticket(), request = ++generation.current;
    const flight = { target, previousRequestId: exportState?.requestId || '' }; exportFlight.current = flight;
    pending.current = true; setBusy('package.export'); setError(''); setUnknown(false);
    void bridge.command('projects.package.export', target).then(value => {
      if (!current(original, request)) return;
      const accepted = packageExportState(value);
      if (!accepted || accepted.projectId !== original.projectId || !sameExportTarget(accepted, target) || accepted.requestId === flight.previousRequestId)
        throw Object.assign(new Error('原固定包导出回执尚未核对，请核对所选文件，没有重新请求。'), { code: 'outcome_unknown' });
      setExportReceipt(accepted);
    }).catch(failure => {
      if (!current(original, request)) return;
      setUnknown(failure?.code === 'outcome_unknown'); setError(failure instanceof Error ? failure.message : String(failure));
    }).finally(() => {
      if (generation.current === request) { pending.current = false; setBusy(''); if (exportFlight.current === flight) exportFlight.current = null; }
    });
  };

  const action = (name: Action, extra: Data = {}) => {
    const originalView = viewRef.current, context = optionsRef.current;
    if (!originalView || pending.current || exportBusy || unknown || !context.active || context.blocked || document.hidden || !context.can(name)) return;
    if (name === 'projects.preview.mode' && extra.enabled === true && !originalView.selectionAvailable) return;
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
    if (exportBusy) return true;
    if (view?.selecting && !busy && !unknown) action('projects.preview.mode', { enabled: false });
    else hide();
    return true;
  };
  return { expanded, view, busy, error, unknown, open, hide, action, handleBack, showPackage, choices, selectedEntry, setSelectedEntry, openPackage,
    exportPackage, exportState, exportBusy, exportTarget, resumeRevision: options.resumeRevision,
    returnToPackageTask: () => { const taskId = view?.taskId || choices?.taskId; if (taskId) { hide(); optionsRef.current.onPackageTask(taskId, view ? 'review' : 'process'); } }, blocked: options.blocked, can: options.can };
}

export function ProjectPreviewPane({ control, composer }: { control: ReturnType<typeof useProjectPreview>; composer: Data }) {
  const viewport = useRef<HTMLDivElement>(null);
  const { expanded, view, busy, error, unknown, blocked, can } = control;
  const working = !!busy || control.exportBusy;
  const sessionId = view?.sessionId || '';
  useLayoutEffect(() => {
    if (!expanded || !sessionId || !can('projects.preview.viewport')) return;
    const epoch = bridge.getDocumentEpoch(); let frame = 0, disposed = false, last = '';
    const send = (visible: boolean) => {
      if (bridge.getDocumentEpoch() !== epoch) return;
      const node = viewport.current, bounds = node?.getBoundingClientRect();
      let rect = bounds ? { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height } : null;
      const pane = node?.closest<HTMLElement>('.pw-preview-pane'), paneBounds = pane?.getBoundingClientRect();
      // The native renderer is a sibling of this Web UI, so CSS scrolling does
      // not clip it. Report only the part inside this same pane's client area.
      if (rect && pane && paneBounds) {
        const left = Math.max(rect.x, paneBounds.left + pane.clientLeft);
        const top = Math.max(rect.y, paneBounds.top + pane.clientTop);
        const right = Math.min(rect.x + rect.width, paneBounds.left + pane.clientLeft + pane.clientWidth);
        const bottom = Math.min(rect.y + rect.height, paneBounds.top + pane.clientTop + pane.clientHeight);
        rect = right > left && bottom > top ? { x: left, y: top, width: right - left, height: bottom - top } : null;
      }
      const payload = previewViewport(sessionId, visible && !!node?.isConnected && !blocked && !document.hidden,
        rect, window.innerWidth, window.innerHeight);
      const encoded = JSON.stringify(payload); if (last === encoded) return; last = encoded;
      void bridge.command('projects.preview.viewport', payload).catch(() => { /* Native window invalidation also hides its renderer. */ });
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(() => { if (!disposed) send(true); }); };
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    if (viewport.current) observer?.observe(viewport.current);
    const pane = viewport.current?.closest<HTMLElement>('.pw-preview-pane');
    if (pane) observer?.observe(pane);
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
  }, [expanded, sessionId, blocked, can, control.resumeRevision]);
  if (!expanded) return null;
  const refs = Array.isArray(composer.previewRefs) ? composer.previewRefs : [];
  const count = view ? refs.filter((ref: Data) => ref.previewId === view.metadata.previewId).length : 0;
  const problem = error || view?.error;
  const choices = control.choices, choosing = !!choices && !view;
  const fixed = view?.metadata.origin === 'fixed_workspace_project_artifact';
  return <section className="pw-preview-pane" data-motion-native-surface="project-preview" aria-label="项目预览" aria-busy={working}>
    <header className="pw-preview-heading"><div><h3>{choosing ? '固定项目包' : fixed ? '任务固定版本' : '已采集预览'}</h3><p>{choosing ? choices?.entryPath || '项目源码与资源' : view?.metadata.entryPath ? String(view.metadata.entryPath) : '原项目画面'}</p></div>{view?.selecting && <span className="pw-preview-selection-state">正在选择</span>}</header>
    {!choosing && <div className="pw-preview-actions">
      <Button variant={view?.selecting ? 'primary' : 'secondary'} leadingIcon={IconCursor} size="small"
        disabled={!view?.ready || !view.selectionAvailable && !view.selecting || working || unknown || blocked || !can('projects.preview.mode')} aria-pressed={view?.selecting === true}
        onClick={() => control.action('projects.preview.mode', { enabled: !view?.selecting })}>{view?.selecting ? '退出选择' : '选择元素'}</Button>
      {fixed && control.exportTarget && <Button variant="secondary" size="small" disabled={working || unknown || blocked || !can('projects.package.export')} onClick={control.exportPackage}>导出固定包</Button>}
      <span className="pw-grow"/>
      {view && can('tasks.open') && <IconButton icon={IconHistory} aria-label="核对原任务" title="核对原任务" disabled={working} onClick={control.returnToPackageTask}/>}
      <IconButton icon={IconRefresh} aria-label="重新载入预览" title="重新载入会使旧元素引用失效" disabled={!view || working || unknown || !can('projects.preview.reload')} onClick={() => control.action('projects.preview.reload')}/>
      <IconButton icon={IconClose} aria-label="关闭预览" title="关闭后需要重新选择元素" disabled={!view || working || unknown || !can('projects.preview.close')} onClick={() => control.action('projects.preview.close')}/>
    </div>}
    <MotionFeedback identity={JSON.stringify([sessionId, view?.ready, view?.selecting, view?.selectionAvailable, problem, unknown, choosing])} initial={false} className="pw-preview-context">
      <p className="pw-muted">{choosing ? '只打开这次任务保存的版本，不构建或启动任务。' : view?.selecting ? '点选元素加入原草稿，不会执行页面按钮。' : fixed ? '固定包不会随当前项目的修改而更新。' : '开启元素选择后，可把画面位置加入原草稿。'}</p>

      {view && !view.selectionAvailable && <p className="pw-muted" role="status">{view.selectionUnavailableReason || '暂未确认原来源仍可用于元素选择。'}{fixed ? ' 历史画面仍可查看，发送时还会重新核对来源。' : ''}</p>}
      {problem && <p className="pw-error" role="alert">{problem}</p>}
      {unknown && <p className="pw-muted">原操作结果待核对，没有重发。请返回后核对原预览与草稿。</p>}
      {busy === 'package.export' ? <p className="pw-muted" role="status">正在核对原固定包…</p> : control.exportState && <p className={['unknown', 'rejected'].includes(control.exportState.status) ? 'pw-error' : 'pw-muted'} role="status">{control.exportState.message}</p>}
    </MotionFeedback>
    {choosing && choices ? <section className="pw-preview-entry" aria-label="固定包入口">
      {choices.mode === 'web_output' ? <div><p className="pw-field-hint">清单声明的 HTML 入口</p><p className="pw-preview-entry-path">{choices.entryPath}</p></div> : choices.entryChoices.length ?
        <SelectField label="固定包中的 HTML 入口" value={control.selectedEntry} onChange={control.setSelectedEntry} options={choices.entryChoices.map(path => ({ id: path, label: path }))} isDisabled={working || unknown || blocked} /> :
        <p className="pw-muted">这份固定包包含项目源码与资源，没有可视 HTML 入口。可以导出完整包，在你选择的位置保存。</p>}
      <Accordion variant="divided"><AccordionItem id="package-preview-source" title="查看固定来源" headingLevel={4}>
        <dl className="pw-preview-source-details"><dt>原任务</dt><dd>{choices.taskId}</dd><dt>原操作</dt><dd>{choices.operationId}</dd><dt>固定版本</dt><dd>{choices.artifactId}</dd><dt>归档摘要</dt><dd>{choices.artifactSha256}</dd><dt>工作副本</dt><dd>{choices.checkoutId}</dd></dl>
        <p className="pw-muted">导出会重新核对这次任务、保存操作和固定包的完整文件。</p>
      </AccordionItem></Accordion>
      <div className="pw-preview-actions">
        {choices.entryChoices.length > 0 && <Button variant="primary" disabled={!control.selectedEntry || working || unknown || blocked || !can('projects.package.preview.open')} onClick={control.openPackage}>{busy === 'package.open' ? '正在打开原版本…' : '打开固定预览'}</Button>}
        <Button variant={choices.entryChoices.length > 0 ? 'secondary' : 'primary'} disabled={working || unknown || blocked || !can('projects.package.export')} onClick={control.exportPackage}>导出固定包</Button>
      </div>
    </section> : <div ref={viewport} className="pw-preview-viewport" aria-label="原生隔离预览画面" data-preview-session={sessionId || undefined}>
      {!view?.ready && <div className="pw-preview-placeholder">{busy || view && !problem ? <><ProgressCircle isIndeterminate size="sm" aria-label="正在打开已采集预览"/><p>正在打开已采集预览…</p></> : <p>{problem ? '原文件和草稿仍保留。' : '当前项目没有可用的已采集预览。'}</p>}</div>}
    </div>}
    {view && <MotionFeedback identity={`${sessionId}:${count}`} initial={false} className="pw-preview-draft-status" role="status">{count > 0 ? `草稿中有 ${count} 处元素引用，尚未发送` : '选择的元素会留在原对话草稿中'}</MotionFeedback>}
    {view && <Accordion variant="divided"><AccordionItem id="opened-package-source" title="来源与原任务" headingLevel={4}>
        <dl className="pw-preview-source-details"><dt>原任务</dt><dd>{view.taskId}</dd><dt>项目</dt><dd>{String(view.metadata.projectId || '')}</dd>{fixed && <><dt>原操作</dt><dd>{view.packageOperationId}</dd><dt>固定版本</dt><dd>{String(view.metadata.artifactId || '')}</dd><dt>归档摘要</dt><dd>{String(view.metadata.artifactSha256 || '')}</dd></>}</dl>
      </AccordionItem></Accordion>}
    <footer className="pw-preview-footer">
      <Button variant="ghost" leadingIcon={IconArrowLeft} size="small" disabled={control.exportBusy} onClick={control.hide}>返回项目文件</Button>
      {choosing && <Button variant="secondary" size="small" disabled={working} onClick={control.returnToPackageTask}>返回原任务</Button>}
      {view && <Button variant="primary" size="small" disabled={working || unknown || !can('projects.preview.return')} onClick={() => control.action('projects.preview.return')}>返回原对话</Button>}
    </footer>
  </section>;
}
