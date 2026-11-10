import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconAdd, IconArrowLeft, IconChevronRight, IconFolder, IconRefresh, IconTerminal } from './icons';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import TerminalSurface from './TerminalSurface';
import { bridge } from './bridge';
import { localReceiptPollable, projectExecutionShouldRead } from './project-execution';
import './local-workspace.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const identity = (task: Data) => ({ taskId: task.taskId || task.id, runId: task.runId || task.raw?.runId, epoch: task.epoch || task.raw?.coordinatorEpoch, executionId: task.executionId || task.id });
const statusLabel = (value: any) => ({ running: '运行中', completed: '已完成', failed: '已结束，需核对', stopped: '已停止', unknown: '结果待核对', reconciling: '结果待核对', paused: '已暂停', pending: '等待回执', observed: '已取得回执', not_dispatched: '未发送' } as Record<string, string>)[String(value)] || value || '状态待核对';

/** BoardUI's retained fields, disclosure and navigation-row composition around
 * the original local checkout/PTY workflow. No replacement terminal or authority. */
export function LocalWorkspace({ state, data, run, supported, registerTransientBack, onPrepareTools, onOpenProjects }: { state: Data; data: Data; run: Run; supported: (name: string) => boolean; registerTransientBack: (handler: (() => boolean) | null) => void; onPrepareTools?: () => void; onOpenProjects?: () => void }) {
  const [project, setProject] = useState(''), [checkout, setCheckout] = useState(''), [source, setSource] = useState<Data | null>(null);
  const [cwd, setCwd] = useState('.'), [env, setEnv] = useState('{}'), [seconds, setSeconds] = useState('300');
  const [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false), [original, setOriginal] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [view, setView] = useState<Data | null>(null), [output, setOutput] = useState<Data | null>(null), [input, setInput] = useState(''), [uncertain, setUncertain] = useState(false);
  const [inputReceipt, setInputReceipt] = useState<Data | null>(null), [panel, setPanel] = useState<'setup' | 'session'>('setup');
  const [reviewExpired, setReviewExpired] = useState(false);
  const [columns, setColumns] = useState('80'), [height, setHeight] = useState('24'), [stopReview, setStopReview] = useState(false), [stopRequested, setStopRequested] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [prepareAttempted, setPrepareAttempted] = useState(false);
  const [visible, setVisible] = useState(() => document.visibilityState === 'visible');
  const entered = useRef<object | null>(null), alive = useRef(true), viewHandle = useRef(''), reviewHandle = useRef('');
  const readingRoot = useRef<HTMLElement>(null), readingPositions = useRef<Record<string, number>>({}), previousPresentation = useRef('setup');
  const activePanel = useRef(panel); activePanel.current = panel;
  const presentation = panel === 'setup' ? 'setup' : view ? `screen:${view.handle}` : `session:${original?.executionId || ''}`;
  const rememberPosition = () => { readingPositions.current[presentation] = readingRoot.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; };
  const changePanel = (next: 'setup' | 'session') => { if (busy || review || stopReview || next === panel) return; rememberPosition(); setPanel(next); };
  const invoke = async (name: string, args: Data = {}, accept?: (value: Data) => void) => {
    if (entered.current || document.hidden) return;
    const token = {}, owner = ownerWindow(); entered.current = token; setBusy(true); setError('');
    const current = () => alive.current && entered.current === token && !document.hidden && ownerWindow() === owner;
    try {
      const result = await run(name, args);
      if (current()) { accept?.(result); return result; }
      else if ((name === 'local.terminal.open' || name === 'local.terminal.review') && result?.handle) void run('local.terminal.close', { handle: result.handle }).catch(() => {});
    }
    catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : String(failure)); throw failure; }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const closeViewer = () => {
    rememberPosition(); const handle = viewHandle.current; viewHandle.current = ''; setView(null); setOutput(null);
    if (handle) void run('local.terminal.close', { handle }).catch(() => {});
  };
  const closeReview = () => {
    const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    if (handle) void run('local.terminal.close', { handle }).catch(() => {});
  };
  useEffect(() => {
    alive.current = true; let owner = ownerWindow();
    const retire = () => {
      const handles = [viewHandle.current, reviewHandle.current]; viewHandle.current = ''; reviewHandle.current = ''; entered.current = null;
      // The native owner also retires these handles on background/window changes.
      // Keep the original execution and input draft; reopening only observes it.
      if (alive.current) { setView(null); setOutput(null); setReview(null); setAcknowledged(false); setStopReview(false); setBusy(false); setError(''); }
      for (const handle of handles) if (handle) void run('local.terminal.close', { handle }).catch(() => {});
    };
    const inspect = () => { const next = ownerWindow(); if (next !== owner || document.hidden) { owner = next; retire(); } setVisible(!document.hidden); };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, []);
  useEffect(() => {
    setReviewExpired(false);
    if (!review) return;
    const remaining = typeof review.expiresAt === 'number' ? review.expiresAt - Date.now() : NaN;
    if (!Number.isFinite(remaining) || remaining <= 0) { setReviewExpired(true); return; }
    const timer = window.setTimeout(() => setReviewExpired(true), remaining);
    return () => window.clearTimeout(timer);
  }, [review?.handle, review?.expiresAt]);
  useEffect(() => {
    registerTransientBack(() => {
      if (stopReview) { if (!busy) setStopReview(false); return true; }
      if (review) { if (!busy) closeReview(); return true; }
      if (panel === 'session' && view) { if (!busy) closeViewer(); return true; }
      if (panel === 'session') { if (!busy) changePanel('setup'); return true; }
      return false;
    });
    return () => registerTransientBack(null);
  }, [!!review, !!view, stopReview, panel, busy, registerTransientBack]);
  useLayoutEffect(() => {
    if (presentation === previousPresentation.current) return;
    const scroll = readingRoot.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) scroll.scrollTop = readingPositions.current[presentation] || 0;
    readingRoot.current?.querySelector<HTMLElement>('[data-local-panel]:not([hidden]) h2')?.focus({ preventScroll: true });
    previousPresentation.current = presentation;
  }, [presentation]);
  const open = (target: Data) => {
    if (busy) return;
    rememberPosition();
    void invoke('local.terminal.open', { taskId: target.taskId, executionId: target.executionId }, result => {
      viewHandle.current = result.handle; setView(result); setOutput(result.output); setUncertain(result.metadata?.inputUncertain === true || result.output?.inputUncertain === true); setOriginal(target); setPanel('session');
      setColumns(String(result.metadata?.columns || 80)); setHeight(String(result.metadata?.rows || 24)); if (target.taskId !== original?.taskId || target.executionId !== original?.executionId) setInput(''); setInputReceipt(null);
    }).catch(() => {});
  };
  useEffect(() => {
    // Process completion does not mean every bounded page of its logs was read.
    if (panel !== 'session' || !view?.handle || busy || error || !output || !projectExecutionShouldRead(output) || !visible) return;
    const handle = view.handle;
    const timer = window.setTimeout(() => { if (document.visibilityState === 'visible' && activePanel.current === 'session' && viewHandle.current === handle) void invoke('local.terminal.read', { handle }, value => { if (viewHandle.current === handle) { setOutput(value); if (typeof value.inputUncertain === 'boolean') setUncertain(value.inputUncertain); } }).catch(() => {}); }, 1200);
    return () => window.clearTimeout(timer);
  }, [panel, view?.handle, output, busy, error, visible]);
  const selected = rows(data.checkouts).find(row => row.id === checkout && row.projectId === project);
  const checkouts = rows(data.checkouts).filter(row => row.projectId === project && !row.teamProjectCopy);
  const terminals = rows(state.tasks).filter(row => row.raw?.nativeExecutionMode === 'OWNER_LOCAL_TERMINAL' || row.raw?.ownerLocalTerminal);
  const currentTask = terminals.find(task => identity(task).taskId === original?.taskId && identity(task).executionId === original?.executionId);
  const ready = !!selected && source?.checkoutId === checkout && source?.projectId === project;
  const canInput = panel === 'session' && !!view?.handle && !busy && !uncertain && !stopRequested && !view.metadata?.observingOnly && output?.nativeStatus === 'running' && !output?.terminal;
  const send = (payload: Data) => {
    if (activePanel.current !== 'session' || viewHandle.current !== view?.handle || !canInput) return;
    setUncertain(true); const text = input;
    void invoke('local.terminal.input', { handle: view!.handle, ...payload }, result => {
      setInputReceipt(result); setUncertain(result.nativeInputSettled !== true);
      if (payload.action === 'text' && payload.text === text + '\n' && result.nativeInputSettled && result.accepted) setInput(current => current === text ? '' : current);
    }).catch(() => {});
  };
  const queryInput = () => { if (view) void invoke('local.terminal.inputReceipt', { handle: view.handle }, result => { setInputReceipt(result); if (typeof result.inputUncertain === 'boolean') setUncertain(result.inputUncertain); else if (result.nativeInputSettled === true) setUncertain(false); }).catch(() => {}); };
  const selectSession = (task: Data) => {
    if (busy) return;
    const target = identity(task);
    if (target.taskId === original?.taskId && target.executionId === original?.executionId) { changePanel('session'); return; }
    if (resultUnknown || uncertain) return;
    rememberPosition(); closeViewer(); setOriginal(target); setInput(''); setReceipt(null); setInputReceipt(null); setUncertain(false); setStopRequested(false); setPanel('session');
    void invoke('local.terminal.query', target, setReceipt).catch(() => {});
  };
  const knownResults = state.moduleResults || {};
  const runtimes = rows(data.projectRuntimes?.runtimes || data.projectRuntimes);
  const inventory = knownResults['local.listJobs'] || data;
  const legacyReceiptRecovery = inventory.legacyReceiptRecovery || knownResults['local.probe']?.legacyReceiptRecovery || data.legacyReceiptRecovery;
  const legacyNeedsAttention = legacyReceiptRecovery && (legacyReceiptRecovery.originalInventoryUnconfirmed || legacyReceiptRecovery.coldUnconfirmedRecords > 0 || legacyReceiptRecovery.unreadableOriginalRecordsRetained > 0);
  const legacySummary = legacyReceiptRecovery && <div className="pw-local-feedback" role="status"><p>{legacyReceiptRecovery.message || '原只读命令的恢复清单只用于核对既有记录。'}</p><p className="pw-field-hint">可读原记录 {legacyReceiptRecovery.readableOriginalRecords ?? '尚未返回'} 条 · 冷恢复待核 {legacyReceiptRecovery.coldUnconfirmedRecords ?? '尚未返回'} 条 · 保留的不可读原记录 {legacyReceiptRecovery.unreadableOriginalRecordsRetained ?? '尚未返回'} 条。</p>{(legacyReceiptRecovery.originalInventoryUnconfirmed || legacyReceiptRecovery.coldUnconfirmedRecords > 0) && <p className="pw-field-hint">{legacyReceiptRecovery.blockingScope === 'all_checkouts' ? '全部工作副本的原写入范围仍受阻止。' : '相关原工作副本仍保持占用。'}重新打开页面不证明进程已经退出，也不会恢复执行权限。</p>}</div>;
  const projectRows = rows(state.projects), hasSetupSource = projectRows.length > 0 || !!project;
  const jobs: Data[] = [...rows(inventory.jobs).map(row => ({ ...row, backendLabel: '本机' })), ...rows(inventory.termuxJobs).map(row => ({ ...row, backendLabel: 'Termux' }))];
  const unresolvedJobs = jobs.filter(row => /unknown|uncertain/.test(String(row.status || row.state || '')));
  const inputBytes = new TextEncoder().encode(input + '\n').length;
  const currentStatus = output?.nativeStatus || receipt?.status || currentTask?.status;
  const resultUnknown = !!original && (!receipt || !/^(running|completed|failed|paused|stopped)$/.test(String(receipt.status)) || /^(unknown|prepared)$/.test(String(receipt.operationState)));
  const executionMessage = receipt?.message || receipt?.receipt?.message;
  return <section ref={readingRoot} className="pw-local-workspace pw-settings-section" data-local-view={panel}>
    <header className="pw-local-heading"><div><h2>本机终端</h2></div>{onPrepareTools && <Button variant="secondary" trailingIcon={IconChevronRight} onClick={onPrepareTools}>准备开发工具</Button>}</header>
    {error && <p role="alert" className="pw-local-feedback pw-secondary-error">{error}</p>}
    {knownResults['local.probe']?.message && <p className="pw-local-runtime-message" role="status">{knownResults['local.probe'].message}</p>}
    {legacyNeedsAttention && legacySummary}
    {unresolvedJobs.map((row, index) => <p className="pw-local-feedback" key={row.id || row.operationId || index}>{row.backendLabel}原操作 {row.operationId || row.id}：{row.message || row.summary || '结果尚未确认，请读取原运行回执，不要重复执行。'}</p>)}
    {original && resultUnknown && <div className="pw-local-feedback" role="status"><p>原执行结果待核对。请查询这条记录，不要重复启动。</p><Button variant="ghost" size="small" disabled={busy} onClick={() => { void invoke('local.terminal.query', original, setReceipt).catch(() => {}); }}>查询原执行</Button></div>}
    {original && stopRequested && <div className="pw-local-feedback" role="status"><p>{output?.terminal ? `原进程已返回终态；资源释放：${output.resourcesReleased ? '已确认' : '尚未确认'}。` : '已请求停止原进程，退出与资源回收仍以真实回执为准。'}</p><Button variant="ghost" size="small" disabled={busy} onClick={() => { void invoke('local.terminal.query', original, setReceipt).catch(() => {}); }}>查询停止结果</Button></div>}
    {panel === 'setup' && original && !resultUnknown && <p className="pw-local-runtime-message" role="status">原终端：{statusLabel(currentStatus)}{executionMessage ? ` · ${executionMessage}` : ''}</p>}
    {uncertain && <div className="pw-local-feedback" role="status"><p>上一输入尚未核对，已停止继续输入。不会自动重发。</p>{view ? <Button variant="secondary" size="small" disabled={busy} onClick={queryInput}>核对原输入回执</Button> : original && <Button variant="secondary" size="small" disabled={busy} onClick={() => open(original)}>打开原终端核对</Button>}</div>}
    <div className="pw-local-layout" data-has-sessions={terminals.length > 0 || !!original}>
      {(terminals.length > 0 || original) && <nav className="pw-local-sessions" aria-label="本机终端">
        <Button variant="ghost" className="pw-local-session-row pw-local-new" leadingIcon={IconAdd} aria-pressed={panel === 'setup'} disabled={busy} onClick={() => changePanel('setup')}>新建终端</Button>
        <div className="pw-local-session-label"><span>已有终端</span></div>
        {original && !currentTask && <Button variant="ghost" className="pw-local-session-row" leadingIcon={IconTerminal} aria-pressed={panel === 'session'} disabled={busy} onClick={() => changePanel('session')}><span className="pw-local-session-copy"><strong>本次手动终端</strong><small>{statusLabel(currentStatus)}</small></span></Button>}
        {terminals.map(task => {
          const active = identity(task).taskId === original?.taskId && identity(task).executionId === original?.executionId;
          return <Button variant="ghost" className="pw-local-session-row" leadingIcon={IconTerminal} key={task.id || task.taskId} aria-pressed={panel === 'session' && active} disabled={busy || (resultUnknown || uncertain) && !active} onClick={() => selectSession(task)}><span className="pw-local-session-copy"><strong>{task.goal || '手动终端'}</strong><small>{active ? statusLabel(currentStatus) : statusLabel(task.status || task.state)}</small></span></Button>;
        })}
      </nav>}
      <div className="pw-local-main">
        <section className="pw-local-panel" data-local-panel="setup" hidden={panel !== 'setup'}>
          {!hasSetupSource ? <div className="pw-local-project-empty"><IconFolder size={30} aria-hidden /><h2 tabIndex={-1}>{Array.isArray(state.projects) ? '从一个项目开始' : '先读取你的项目'}</h2><p>本机终端在项目的私人工作副本中运行。先前往项目创建或选择内容，再回来准备副本。</p>{onOpenProjects && <Button leadingIcon={IconFolder} disabled={busy} onClick={onOpenProjects}>前往项目</Button>}</div> : <>
          <header className="pw-local-panel-heading pw-local-setup-heading"><h2 tabIndex={-1}>新建手动终端</h2><Button disabled={busy || resultUnknown || uncertain || !ready || Number(seconds) < 1 || Number(seconds) > 300 || !supported('local.terminal.review')} trailingIcon={IconChevronRight} onClick={() => {
              if (activePanel.current !== 'setup' || entered.current || resultUnknown || uncertain) return;
              try { const environment = JSON.parse(env); if (!environment || Array.isArray(environment) || typeof environment !== 'object') throw new Error('环境变量需要 JSON 对象。');
                if (view) closeViewer();
                void invoke('local.terminal.review', { projectId: project, checkoutId: checkout, expectedSourceDigest: source!.sourceDigest, runtimeId: 'managed_process_v1', cwd, env: environment, timeoutMillis: Number(seconds) * 1000, columns: 80, rows: 24, inputByteLimit: 65536 }, result => { reviewHandle.current = result.handle; setReview(result); setAcknowledged(false); }).catch(() => {});
              } catch (failure) { setError(failure instanceof Error ? failure.message : '请核对表单'); }
            }}>核对并准备终端</Button></header>
          <div className="pw-local-setup-step">
            <h3><IconFolder size={17} aria-hidden />选择工作副本</h3>
            <div className="pw-form-grid"><SelectField label="原项目" value={project} isDisabled={busy} options={[{ id: '', label: '选择已有项目' }, ...projectRows.map(row => ({ id: row.id, label: row.name })), ...(project && !projectRows.some(row => row.id === project) ? [{ id: project, label: '原项目暂未在当前清单中' }] : [])]} onChange={value => { if (!busy) { setProject(value); setCheckout(''); setSource(null); setPrepareAttempted(false); } }} />
              <SelectField label="私人工作副本" value={checkout} isDisabled={busy || !project} options={[{ id: '', label: '选择或准备副本' }, ...checkouts.map(row => ({ id: row.id, label: (row.name || '工作副本') + ' · ' + new Date(row.createdAt || 0).toLocaleString() }))]} onChange={value => { if (!busy) { setCheckout(value); setSource(null); } }} /></div>
            <div className="pw-form-actions"><Button variant="secondary" disabled={!selected || busy} onClick={() => { void invoke('local.describeCheckout', { projectId: project, checkoutId: checkout }, setSource).catch(() => {}); }}>{ready ? '重新核对副本版本' : '读取这个副本的实际版本'}</Button><Button variant="ghost" disabled={!project || busy || prepareAttempted} onClick={() => { if (activePanel.current !== 'setup' || entered.current || prepareAttempted) return; setPrepareAttempted(true); void invoke('local.prepareCheckout', { projectId: project }, result => { setCheckout(result.id); setSource(null); }).catch(() => {}); }}>准备新的私人副本</Button></div>
            {prepareAttempted && <p className="pw-local-inline-notice" role="status">本次已请求准备副本。请核对真实副本列表；回执不明时不重复准备。</p>}
            {ready ? <div className="pw-local-source" role="status"><span>{source.fileCount} 个源码文件 · {source.bytes} 字节</span><small>版本已读取，启动前仍会核对是否变化。</small></div> : <p className="pw-field-hint">{project ? '先读取所选副本的实际版本，才能审阅终端。' : '使用已有项目的私人副本，不直接改动原项目。'}</p>}
          </div>
          <div className="pw-local-setup-step" hidden={!ready}>
            <h3><IconTerminal size={17} aria-hidden />设置这次终端</h3>
            <div className="pw-form-grid"><TextField label="副本中的工作目录" value={cwd} onChange={setCwd} isDisabled={busy} /><TextField label="最长运行秒数（1–300）" inputMode="numeric" value={seconds} isDisabled={busy} onChange={value => setSeconds(value.replace(/\D/g, '').slice(0, 3))} /></div>
            <Accordion className="pw-local-disclosures"><AccordionItem id="environment" title="环境变量" description={env.trim() === '{}' ? '可选，仅限普通配置值' : '已编辑普通环境变量，启动前会核对'}><TextAreaField label="普通环境变量（JSON 对象）" value={env} onChange={setEnv} isDisabled={busy} hint="例如 APP_LABEL；不能设置密钥或覆盖宿主运行环境。" /></AccordionItem></Accordion>
            <p className="pw-local-safety">终端操作可能修改私人副本，输入和输出会保留在原任务记录中。不支持密码等秘密输入。</p>
          </div>
          </>}
        </section>
        <section className="pw-local-panel" data-local-panel="session" hidden={panel !== 'session'}>
          <Button variant="ghost" size="small" className="pw-local-back" leadingIcon={IconArrowLeft} disabled={busy} onClick={() => changePanel('setup')}>返回工作副本</Button>
          <header className="pw-local-panel-heading"><span className="pw-local-eyebrow">{view ? '原终端屏幕' : '原任务记录'}</span><h2 tabIndex={-1}>{currentTask?.goal || '本次手动终端'}</h2></header>
          {original && <>
            <div className="pw-local-session-status"><span>{statusLabel(currentStatus)}</span>{executionMessage && <p role="status">{executionMessage}</p>}</div>
            <div className="pw-form-actions pw-local-session-actions">
              {!view && <Button disabled={busy} leadingIcon={IconTerminal} onClick={() => open(original)}>打开原终端</Button>}
              <Button variant="ghost" disabled={busy} leadingIcon={IconRefresh} onClick={() => { void invoke('local.terminal.query', original, setReceipt).catch(() => {}); }}>查询原执行</Button>
              <Button variant="ghost" disabled={busy} onClick={() => setStopReview(true)}>停止这个原进程</Button>
            </div>
            {view ? <>
              <div className="pw-local-screen-toolbar"><span>{view.metadata?.workingDirectory || '原工作目录'}</span><div><Button variant="ghost" size="small" disabled={busy} onClick={() => { void invoke('local.terminal.read', { handle: view.handle }, value => { setOutput(value); if (value.inputUncertain) setUncertain(true); }).catch(() => {}); }}>读取原屏幕</Button><Button variant="ghost" size="small" disabled={busy} onClick={closeViewer}>关闭视图</Button></div></div>
              <div className="pw-local-screen"><TerminalSurface key={view.handle} frame={output?.screen || null} /></div>
              <p className="pw-field-hint">关闭视图只结束查看，原进程按原时限继续。</p>
              {output?.terminal && <p className="pw-local-inline-notice" role="status">原进程已返回终态；资源释放：{output.resourcesReleased ? '已确认' : '尚未确认'}。{output.hasMore && '后续原日志仍待读取。'}</p>}
              {view.metadata?.observingOnly && <p className="pw-local-inline-notice">当前为只读观察，不会取得新的输入授权。</p>}
              <Accordion className="pw-local-disclosures"><AccordionItem id="local-input-tools" title="终端输入" description={output?.terminal?'进程已结束，保留的输入仍可查看':'普通输入、控制键与原输入回执'}><div className="pw-local-input">
                <TextAreaField label="普通终端输入" value={input} onChange={setInput} hint="发送一行会在末尾加入换行。不要输入密码、令牌等秘密。" />
                <div className="pw-local-input-actions"><Button disabled={!canInput || !input || inputBytes > 4096} onClick={() => send({ action: 'text', text: input + '\n' })}>发送这一行</Button><Button variant="secondary" disabled={!canInput} onClick={() => send({ action: 'text', text: '\u0003' })}>Ctrl+C</Button><Button variant="secondary" disabled={!canInput} onClick={() => send({ action: 'eof' })}>EOF</Button><small>{input ? `${inputBytes} / 4096 字节（含换行）` : '单次最多 4096 字节'}</small></div>
                <p className="pw-field-hint">原授权上限：{view.metadata?.inputByteLimit ?? '未提供'} 字节 · {view.metadata?.inputRequestLimit ?? '未提供'} 次动作 · {view.metadata?.timeoutMillis == null ? '时限未提供' : `${view.metadata.timeoutMillis / 1000} 秒`}。仍由原任务核对剩余额度。</p>
                {inputReceipt && <p className="pw-local-inline-notice" role="status">{inputReceipt.knownInput === false ? '这个视图没有待核输入；原任务记录保留。' : inputReceipt.message || (inputReceipt.nativeInputSettled ? inputReceipt.accepted ? '已取得原输入回执，没有再次发送。' : '已核对原输入回执。' : '输入结果仍待核对，没有重发。')}</p>}
                {!uncertain && <Button variant="ghost" size="small" disabled={busy} onClick={queryInput}>核对原输入回执</Button>}
              </div></AccordionItem></Accordion>
              <Accordion className="pw-local-disclosures"><AccordionItem id="geometry" title="终端尺寸" description={`${columns} 列 × ${height} 行，调整也计入原输入动作`}><div className="pw-form-grid"><TextField label="列数（20–300）" value={columns} onChange={setColumns} inputMode="numeric" isDisabled={busy} /><TextField label="行数（5–120）" value={height} onChange={setHeight} inputMode="numeric" isDisabled={busy} /></div><Button variant="secondary" disabled={!canInput || !/^\d+$/.test(columns) || !/^\d+$/.test(height) || Number(columns) < 20 || Number(columns) > 300 || Number(height) < 5 || Number(height) > 120} onClick={() => send({ action: 'resize', columns: Number(columns), rows: Number(height) })}>调整原终端尺寸</Button></AccordionItem></Accordion>
            </> : <div className="pw-local-closed"><IconTerminal size={24} aria-hidden /><p>打开原屏幕，继续查看输出。</p><small>查看不会新建进程，也不会自动发送输入。</small></div>}
            <Accordion className="pw-local-disclosures"><AccordionItem id="identity" title="执行来源与任务记录">{currentTask?.summary && <p className="pw-field-hint">{currentTask.summary}</p>}<dl className="pw-facts"><div><dt>任务</dt><dd>{original.taskId}</dd></div><div><dt>运行</dt><dd>{original.runId}</dd></div><div><dt>执行</dt><dd>{original.executionId}</dd></div><div><dt>代次</dt><dd>{original.epoch}</dd></div>{view?.metadata?.projectId && <div><dt>项目</dt><dd>{view.metadata.projectId}</dd></div>}{view?.metadata?.checkoutId && <div><dt>工作副本</dt><dd>{view.metadata.checkoutId}</dd></div>}</dl></AccordionItem></Accordion>
          </>}
        </section>
      </div>
    </div>
    <Accordion className="pw-local-runtime pw-local-disclosures"><AccordionItem id="runtime" title="运行环境与历史回执" description={runtimes.length ? runtimes.map(row => row.name || row.id || row.runtimeId).join(' · ') : '本机、Termux 与已读取的原运行记录'}>
      {!legacyNeedsAttention && legacySummary}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('local.inspectRuntime')} onClick={() => { void invoke('local.inspectRuntime').catch(() => {}); }}>核对运行时</Button><Button variant="secondary" disabled={busy || !supported('local.readReceipts')} onClick={() => { void invoke('local.readReceipts').catch(() => {}); }}>读取原运行回执</Button><Button variant="ghost" disabled={busy || !supported('local.termuxProbe')} onClick={() => { void invoke('local.termuxProbe').catch(() => {}); }}>核对 Termux</Button></div>
      {runtimes.map(row => <div className="pw-local-runtime-row" key={row.id || row.runtimeId}><h3>{row.name || row.id || row.runtimeId}</h3><p>{row.message || row.status || (row.available ? '当前可用' : '尚未确认可用')}</p></div>)}
      {Array.isArray(knownResults['local.probe']?.tools) && <p className="pw-field-hint">已发现工具：{knownResults['local.probe'].tools.map((tool: any) => typeof tool === 'string' ? tool : tool.name || tool.id).join('、') || '尚无'}</p>}
      <div className="pw-local-job-list"><h3>原本机与 Termux 运行回执</h3>{jobs.length ? jobs.map((row: Data, index) => <div className="pw-local-runtime-row" key={row.id || row.operationId || index}><h4>{row.backendLabel} · {statusLabel(row.status || row.state)}</h4><p>{row.message || row.summary}</p>{row.coldProcessUnconfirmed && <p className="pw-field-hint">原进程冷恢复后仍待核对；保留原记录，不重启、不重新派发。</p>}{!localReceiptPollable(String(row.status || row.state || ''), row.backendLabel === 'Termux') && <p className="pw-field-hint">当前状态不自动轮询。需要时可明确读取原运行回执。</p>}<dl className="pw-facts"><div><dt>原操作</dt><dd>{row.operationId || row.id}</dd></div>{row.originalPersistedStatus && <div><dt>原持久化阶段</dt><dd>{row.originalPersistedStatus}</dd></div>}{row.phase && <div><dt>原回执阶段</dt><dd>{row.phase}</dd></div>}<div><dt>退出状态</dt><dd>{row.exitCode == null ? '尚未返回' : row.exitCode}</dd></div></dl></div>) : <p className="pw-field-hint">本次读取没有返回运行回执。</p>}</div>
    </AccordionItem></Accordion>
    <SettingsDialog isOpen={!!review} title="打开这个手动终端？" onClose={() => { if (!busy) closeReview(); }}>{review && <div className="pw-local-review"><dl className="pw-facts"><div><dt>原目录</dt><dd>{review.command?.workingDirectory}</dd></div><div><dt>最长运行时间</dt><dd>{review.command?.timeoutMillis / 1000} 秒</dd></div><div><dt>输入额度</dt><dd>{review.command?.inputByteLimit} 字节，最多 {review.inputRequestLimit} 次</dd></div><div><dt>原项目</dt><dd>{review.projectId}</dd></div><div><dt>私人副本</dt><dd>{review.checkoutId}</dd></div><div><dt>源码版本</dt><dd>{review.sourceDigest}</dd></div></dl><p>只打开已登记的 sh -i。终端操作可能修改这个私人副本，是否导回项目另行核对。</p>{rows(review.warnings).map((warning, i) => <p key={i}>{String(warning)}</p>)}{reviewExpired && <p className="pw-local-inline-notice" role="status">这次终端审阅已过期。请返回重新核对目录和运行范围；这次终端尚未启动。</p>}<CheckboxField isSelected={acknowledged} isDisabled={busy || reviewExpired} onChange={setAcknowledged}>我已核对目录、时限与普通输入范围</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || reviewExpired || !acknowledged} onClick={() => { if (activePanel.current !== 'setup' || entered.current || !acknowledged || reviewHandle.current !== review.handle) return; if (reviewExpired || typeof review.expiresAt !== 'number' || !Number.isFinite(review.expiresAt) || review.expiresAt <= Date.now()) { setReviewExpired(true); setAcknowledged(false); return; } const selected = review; const handle = reviewHandle.current; reviewHandle.current = ''; rememberPosition(); setReview(null); setOriginal({ taskId: selected.requestId, runId: selected.requestId, epoch: 1, executionId: selected.requestId }); setInput(''); setReceipt(null); setInputReceipt(null); setUncertain(false); setStopRequested(false); setPanel('session'); void invoke('local.terminal.confirm', { handle }, setReceipt).catch(() => {}); }}>确认打开原终端</Button></div></div>}</SettingsDialog>
    <SettingsDialog isOpen={stopReview} title="停止这个原终端进程？" onClose={() => { if (!busy) setStopReview(false); }}><div className="pw-local-review"><p>只请求停止所选原进程。已写入的副本内容和原日志保留；退出与资源回收以真实回执为准。</p><dl className="pw-facts"><div><dt>原任务</dt><dd>{original?.taskId}</dd></div><div><dt>原执行</dt><dd>{original?.executionId}</dd></div></dl><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setStopReview(false)}>返回</Button><Button variant="danger" disabled={busy || !original} onClick={() => { if (activePanel.current !== 'session' || entered.current) return; setStopReview(false); setStopRequested(true); if (original) void invoke('local.terminal.stop', original, setReceipt).catch(() => {}); }}>停止这个原进程</Button></div></div></SettingsDialog>
  </section>;
}

