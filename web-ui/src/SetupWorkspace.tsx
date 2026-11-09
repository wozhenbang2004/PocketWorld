import { useEffect, useRef, useState, type ReactNode } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { RiArrowLeftLine, RiArrowRightLine, RiArrowRightSLine, RiCheckLine, RiCloseLine, RiComputerLine, RiMicLine, RiMoonLine, RiRefreshLine, RiSmartphoneLine } from '@remixicon/react';
import { Accordion, AccordionItem, Button, CheckboxField } from './forms-library';
import { ProgressBar } from './library';
import { backgroundConnection } from './forms-library/host-contracts';
import { bridge, type PocketSnapshot } from './bridge';
import { permissionLabel, readSetup, readSetupRuntime, readSetupRuntimeReview, runtimeCancelInput, runtimeLogText, runtimePreparing, runtimeProgressLabel,
  runtimePhaseLabel, runtimeStatusLabel, setupBytes, setupCapabilities, setupDetails, setupModelLabel, setupObject, setupSaveConfirmed, setupSaveInput, setupSteps,
  speechNetworkLabel, type SetupCapability, type SetupPermission, type SetupPreferences, type SetupRead, type SetupRuntime, type SetupRuntimeReview, type SetupStep } from './setup-contract';
import './shell-library/affect-workflows.css';
import './setup.css';

export type SetupDestination = 'connections' | 'background' | 'appearance' | 'local';
export type SetupWorkspaceProps = {
  snapshot: PocketSnapshot;
  mode?: 'onboarding' | 'center';
  initialCapability?: SetupCapability;
  runtimeTaskId?: string;
  onNavigate: (target: SetupDestination) => void;
  onClose: () => void;
  registerBackHandler?: (handler: (() => boolean) | null) => void;
};

const capabilityCopy = {
  phone: { title: '操作手机', description: '读取界面、点击与输入', icon: RiSmartphoneLine },
  tools: { title: '运行开发工具', description: '在手机上运行项目和命令', icon: RiComputerLine },
  voice: { title: '语音输入', description: '使用这台手机的系统听写', icon: RiMicLine },
  background: { title: '后台继续工作', description: '任务通知与后台运行设置', icon: RiMoonLine },
} satisfies Record<SetupCapability, { title: string; description: string; icon: typeof RiSmartphoneLine }>;
const stepTitles: Record<SetupStep, string> = { welcome: '开始', model: '模型', capabilities: '用途', finish: '确认' };
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure || '操作暂未完成，请刷新后核对。');
type SetupCommand = (action: string, args?: Record<string, unknown>) => Promise<unknown>;

/** Adapted from the retained, approved Affect StepperForm (library commit
 * 0e69029b). Its step/body/review/footer structure is reused; native preferences
 * replace demo state and timers. BoardUI owns every actionable control. */
function SetupSteps({ step, disabled, onStep }: { step: SetupStep; disabled: boolean; onStep: (step: SetupStep) => void }) {
  const current = setupSteps.indexOf(step);
  return <ol className="pw-affect-workflow pw-affect-stepnav pw-setup-steps" aria-label="使用准备步骤">
    {setupSteps.map((id, index) => <li key={id} data-reached={index <= current} aria-current={id === step ? 'step' : undefined}>
      <Button variant="ghost" className="pw-setup-step" disabled={disabled || index >= current} onClick={() => onStep(id)} aria-label={`返回${stepTitles[id]}步骤`}>
        <span className="pw-setup-step-number" aria-hidden="true">{index < current ? <RiCheckLine size={15}/> : index + 1}</span><span>{stepTitles[id]}</span>
      </Button>
    </li>)}
  </ol>;
}

function SetupRow({ title, status, children, action }: { title: string; status: string; children?: ReactNode; action?: ReactNode }) {
  return <div className="pw-setup-status-row"><div><strong>{title}</strong><span>{status}</span>{children && <p>{children}</p>}</div>{action}</div>;
}

/** One native preparation operation; polling observes it and never starts one. */
function useRuntimePreparation(enabled: boolean, can: (action: string) => boolean, resumeRevision: unknown, run: SetupCommand, runtimeTaskId?: string) {
  const [view, setView] = useState<SetupRuntime | null>(null), [review, setReview] = useState<SetupRuntimeReview | null>(null);
  const [reading, setReading] = useState(false), [working, setWorking] = useState(''), [error, setError] = useState('');
  const [log, setLog] = useState(''), [logTrimmed, setLogTrimmed] = useState(false), [needsRead, setNeedsRead] = useState(false);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), readingRef = useRef(false);
  const current = useRef(view), cursor = useRef(0), logOwner = useRef(''); current.current = view;
  const currentReview = useRef(review); currentReview.current = review;
  const latest = useRef({ enabled, can, run }); latest.current = { enabled, can, run };
  const dismissReview = () => {
    const original = currentReview.current; currentReview.current = null; setReview(null);
    if (original && latest.current.can('setup.runtime.dismiss')) void latest.current.run('setup.runtime.dismiss', { reviewHandle: original.reviewHandle }).catch(() => {});
  };
  const apply = (value: unknown, requestedTaskId?: string) => {
    const next = readSetupRuntime(value);
    if (requestedTaskId && next.operation?.taskId !== requestedTaskId) throw new Error('没有读取到这条原准备记录，请刷新后核对。');
    const owner = next.operation ? `${next.operation.taskId}:${next.operation.runId}:${next.operation.epoch}` : '';
    const changed = owner !== logOwner.current;
    if (changed) { logOwner.current = owner; cursor.current = 0; setLog(''); setLogTrimmed(false); }
    const text = runtimeLogText(next.logs);
    if (text) setLog(previous => {
      const combined = (changed ? '' : previous) + text;
      if (combined.length > 131072) setLogTrimmed(true);
      return combined.slice(-131072);
    });
    if (next.logs) cursor.current = next.logs.nextCursor;
    current.current = next; setView(next); setNeedsRead(false); return next;
  };
  const read = async (preserveActionError = false) => {
    if (!alive.current || !latest.current.can('setup.runtime.read') || readingRef.current || pending.current) return false;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); readingRef.current = true; setReading(true);
    try {
      const taskId = current.current?.operation?.taskId || runtimeTaskId;
      const result = await latest.current.run('setup.runtime.read', { ...(taskId ? { taskId } : {}), afterSequence: cursor.current, maxBytes: 32768 });
      if (!alive.current || generation.current !== ticket || bridge.getDocumentEpoch() !== epoch) return false;
      apply(result, taskId); if (!preserveActionError) setError(''); return true;
    } catch (failure) { if (alive.current && generation.current === ticket) { setError(failureText(failure)); setNeedsRead(true); } return false; }
    finally { if (generation.current === ticket) { readingRef.current = false; if (alive.current) setReading(false); } }
  };
  const readRef = useRef(read); readRef.current = read;
  useEffect(() => {
    alive.current = true;
    const visible = () => { dismissReview(); if (!document.hidden && latest.current.enabled) void readRef.current(); };
    document.addEventListener('visibilitychange', visible);
    return () => { alive.current = false; generation.current++; dismissReview(); document.removeEventListener('visibilitychange', visible); };
  }, []);
  const readable = can('setup.runtime.read');
  useEffect(() => { if (enabled && readable) void readRef.current(); }, [enabled, readable, resumeRevision]);
  useEffect(() => {
    if (!enabled || !runtimePreparing(view) || needsRead || document.hidden) return;
    const timer = window.setTimeout(() => { void readRef.current(); }, 1500);
    return () => window.clearTimeout(timer);
  }, [enabled, view, reading, working, needsRead]);
  const action = async (command: string, args: Record<string, unknown> = {}) => {
    if (!alive.current || pending.current || readingRef.current || !can(command) || document.hidden) return;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); pending.current = true; setWorking(command); setError('');
    try {
      const result = await latest.current.run(command, args);
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return;
      if (command === 'setup.runtime.review') setReview(readSetupRuntimeReview(result));
      else { setReview(null); if (setupObject(result).format === 'pocketworld.runtime-setup-view.v1') apply(result); }
    } catch (failure) { if (alive.current && ticket === generation.current) { setReview(null); setError(failureText(failure)); setNeedsRead(true); } }
    finally {
      if (ticket === generation.current) { pending.current = false; if (alive.current) { setWorking(''); if (command !== 'setup.runtime.review') void readRef.current(true); } }
    }
  };
  return { view, review, reading, working, error, log, logTrimmed, needsRead, read, dismissReview,
    reviewPreparation: () => action('setup.runtime.review', { mode: view?.ready ? 'repair' : 'prepare' }),
    start: () => {
      if (!review || review.review.expiresAt <= Date.now()) { dismissReview(); setError('这份准备说明已过期，请重新查看后开始。'); return; }
      return action('setup.runtime.start', { reviewHandle: review.reviewHandle });
    },
    cancel: () => { if (view) { try { return action('setup.runtime.cancel', runtimeCancelInput(view)); } catch (failure) { setError(failureText(failure)); } } },
  };
}

function RuntimePreparation({ preparation, can, blocked }: { preparation: ReturnType<typeof useRuntimePreparation>; can: (action: string) => boolean; blocked: boolean }) {
  const { view, review, reading, working, error, log, logTrimmed, needsRead } = preparation;
  const locked = blocked || reading || !!working, running = runtimePreparing(view), operation = view?.operation;
  const reviewDetails = review ? { effects: review.review.effects, runtime: review.review.runtimeIdentity, catalog: review.review.catalogIdentity } : null;
  const originalError = operation?.error;
  return <section className="pw-setup-runtime" aria-label="开发工具准备" aria-busy={reading || !!working}>
    <div className="pw-setup-section-title"><div><h3>手机开发工具</h3><p role="status">{view ? runtimeStatusLabel(view) : reading ? '正在检查' : '尚未检查'}</p></div>
      <Button variant="ghost" leadingIcon={RiRefreshLine} disabled={locked || !can('setup.runtime.read')} onClick={() => void preparation.read()}>刷新</Button></div>
    {view?.message && <p className="pw-setup-copy">{view.message}</p>}
    {!can('setup.runtime.read') && <p className="pw-setup-copy">当前应用尚未接入工具准备，请更新应用后重试。</p>}
    {running && operation && <div className="pw-setup-progress">
      <ProgressBar label={runtimePhaseLabel(operation.phase)} value={operation.fraction ?? undefined} maxValue={1} isIndeterminate={operation.fraction == null} showValueLabel={operation.fraction != null} size="sm"/>
      {runtimeProgressLabel(operation) && <p>{runtimeProgressLabel(operation)} · 当前阶段</p>}
    </div>}
    {error && <p className="pw-setup-error" role="alert">{error}</p>}
    {originalError?.message && <p className="pw-setup-error" role="alert">{originalError.message}</p>}
    {view?.status === 'interrupted' && <p className="pw-setup-copy">上一次准备未确认完成。先刷新检查，再按当前状态继续。</p>}
    {review ? <section className="pw-setup-install-review" aria-label="确认工具准备">
      <h4>{review.review.title || '准备这些开发工具'}</h4><p>{review.review.summary}</p>
      <p className="pw-setup-download">{review.review.downloadBytes == null ? '下载大小尚未确认' : `基础下载约 ${setupBytes(review.review.downloadBytes)}`}{review.review.additionalDownloads ? '，安装过程中还会下载依赖。' : '。'}</p>
      {review.review.minimumFreeBytes != null && <p>开始前至少需要 {setupBytes(review.review.minimumFreeBytes)} 可用空间。</p>}
      {review.review.existingGenerationRetained && view?.ready && <p>现用工具版本会保留。</p>}
      <Accordion><AccordionItem id="setup-install-effects" title="查看安装内容"><pre>{setupDetails(reviewDetails)}</pre></AccordionItem></Accordion>
      <div className="pw-setup-actions"><Button variant="ghost" disabled={!!working} onClick={preparation.dismissReview}>暂不安装</Button>
        <Button disabled={locked || !can('setup.runtime.start')} onClick={() => void preparation.start()}>{working === 'setup.runtime.start' ? '正在开始…' : '开始准备'}</Button></div>
    </section> : <div className="pw-setup-actions">
      {view && (view.canPrepare || view.canRetry) && !running && <Button disabled={locked || needsRead || !can('setup.runtime.review')} onClick={() => void preparation.reviewPreparation()}>
        {working === 'setup.runtime.review' ? '正在读取安装内容…' : view.ready ? '检查与修复工具' : view.canRetry ? '重新准备' : '准备开发工具'}
      </Button>}
      {view?.canCancel && <Button variant="secondary" disabled={locked || !can('setup.runtime.cancel')} onClick={() => void preparation.cancel()}>{view.status === 'cancel_requested' ? '已请求停止' : '停止准备'}</Button>}
    </div>}
    {(originalError || log || view && ['failed', 'blocked', 'interrupted'].includes(view.status)) && <Accordion className="pw-setup-details">
      <AccordionItem id="setup-runtime-details" title="运行详情与日志" description={originalError?.code || undefined}>
        {operation?.phase && <p>原始阶段：{operation.phase}</p>}
        {originalError?.code && <p>错误代码：{originalError.code}</p>}{originalError?.details != null && <pre>{setupDetails(originalError.details)}</pre>}
        {log && <pre className="pw-setup-log" tabIndex={0}>{log}</pre>}
        {(logTrimmed || view?.logs?.outputTruncated) && <p>这里只保留部分日志；底层输出已截断。</p>}
        {view?.logs?.logIncompleteTail && <p>最后一段日志尚未完整写入。</p>}
        {view?.logs?.hasMore && <Button variant="ghost" disabled={locked} onClick={() => void preparation.read()}>读取后续日志</Button>}
        {!log && !originalError?.details && <pre>{setupDetails(view?.preparation)}</pre>}
      </AccordionItem>
    </Accordion>}
  </section>;
}

export function SetupWorkspace({ snapshot, mode = 'center', initialCapability, runtimeTaskId, onNavigate, onClose, registerBackHandler }: SetupWorkspaceProps) {
  const [data, setData] = useState<SetupRead | null>(null), [step, setStep] = useState<SetupStep>(mode === 'onboarding' ? 'welcome' : 'capabilities');
  const [expanded, setExpanded] = useState<SetupCapability | null>(initialCapability || null), [loading, setLoading] = useState(false), [working, setWorking] = useState('');
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [needsRead, setNeedsRead] = useState(false);
  const [entered, setEntered] = useState(false);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), reading = useRef(false), initialized = useRef(false);
  const returnRead = useRef(false);
  const latest = useRef({ data, snapshot, step, onNavigate, onClose, entered }); latest.current = { data, snapshot, step, onNavigate, onClose, entered };
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  // The native setup surface has one operation slot. A system-return refresh and
  // runtime polling share it; only still-visible, original-page requests dispatch.
  const run: SetupCommand = (action, args = {}) => {
    const ticket = generation.current, epoch = bridge.getDocumentEpoch();
    const request = queue.current.then(() => {
      if (!alive.current || generation.current !== ticket || bridge.getDocumentEpoch() !== epoch || !latest.current.entered || document.hidden) throw new Error('准备页面已变化，返回后请重新读取状态。');
      return bridge.command(action, args);
    });
    queue.current = request.then(() => undefined, () => undefined);
    return request;
  };
  const heading = useRef<HTMLHeadingElement>(null), stepBody = useRef<HTMLDivElement>(null), back = useRef<() => boolean>(() => false);
  const reducedMotion = useReducedMotion(), can = (action: string) => snapshot.capabilities.commands.includes(action);
  const capabilities = data?.preferences.capabilities || [], locked = !entered || loading || !!working || needsRead;
  const resumeRevision = setupObject(snapshot.view).resumeRevision;
  const preparation = useRuntimePreparation(entered && !!data && (capabilities.includes('tools') || expanded === 'tools'), can, resumeRevision, run, runtimeTaskId);
  const connection = backgroundConnection(setupObject(setupObject(snapshot.state.features).display), snapshot.state.activeTaskId, Array.isArray(snapshot.state.moduleBusy) ? snapshot.state.moduleBusy : []);
  const phoneConnection = connection.requiresConnectionCheck ? '连接状态待核对' : connection.helperConnected ? '后台助手已连接' : connection.loaded && !connection.supported ? '后台独立屏不受支持' : '后台助手尚未连接';
  const load = async () => {
    if (!alive.current || !latest.current.entered || pending.current || reading.current || !latest.current.snapshot.capabilities.commands.includes('setup.read')) return;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); reading.current = true; setLoading(true);
    try {
      const value = readSetup(await run('setup.read'));
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return;
      setData(value); setNeedsRead(false); setError(''); returnRead.current = false;
      if (!initialized.current) { initialized.current = true; if (mode === 'onboarding') setStep(value.preferences.step); }
    } catch (failure) { if (alive.current && ticket === generation.current) setError(failureText(failure)); }
    finally { if (ticket === generation.current) { reading.current = false; if (alive.current) setLoading(false); } }
  };
  const loadRef = useRef(load); loadRef.current = load;
  useEffect(() => {
    alive.current = true;
    const ticket = generation.current;
    void bridge.command('connections.enter', { section: 'setup' }).then(() => {
      if (alive.current && generation.current === ticket) setEntered(true);
    }).catch(failure => { if (alive.current && generation.current === ticket) setError(failureText(failure)); });
    const visible = () => { if (!document.hidden) { returnRead.current = true; void loadRef.current(); } };
    document.addEventListener('visibilitychange', visible);
    return () => { alive.current = false; generation.current++; document.removeEventListener('visibilitychange', visible); void bridge.command('connections.leave', { section: 'setup' }).catch(() => {}); };
  }, []);
  const readable = can('setup.read');
  useEffect(() => { if (entered && readable) { returnRead.current = true; void loadRef.current(); } }, [entered, readable, resumeRevision, snapshot.state.modelSelectionRevision]);
  useEffect(() => { stepBody.current?.scrollTo({ top: 0 }); heading.current?.focus({ preventScroll: true }); }, [step]);
  useEffect(() => { registerBackHandler?.(() => back.current()); return () => registerBackHandler?.(null); }, [registerBackHandler]);

  const save = async (next: SetupPreferences): Promise<boolean> => {
    const before = latest.current.data;
    if (!before || pending.current || reading.current || needsRead || !can('setup.save')) return false;
    if (before.preferences.step === next.step && before.preferences.status === next.status && before.preferences.capabilities.join(',') === next.capabilities.join(',')) return true;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); pending.current = true; setWorking('save'); setError(''); setNotice('');
    try {
      await run('setup.save', setupSaveInput(before, next));
      const actual = setupSaveConfirmed(before, next, await run('setup.read'));
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return false;
      setData(actual); latest.current.data = actual; return true;
    } catch (failure) { if (alive.current && ticket === generation.current) { setError(failureText(failure)); setNeedsRead(true); } return false; }
    finally { if (ticket === generation.current) { pending.current = false; if (alive.current) setWorking(''); } }
  };
  const move = async (next: SetupStep) => {
    if (!data || locked) return;
    if (await save({ ...data.preferences, step: mode === 'onboarding' ? next : data.preferences.step,
      status: mode === 'onboarding' || data.preferences.status === 'new' ? 'in_progress' : data.preferences.status })) { setStep(next); preparation.dismissReview(); }
  };
  const selectCapability = async (id: SetupCapability, selected: boolean) => {
    if (!data || locked) return;
    const next = setupCapabilities.filter(item => item === id ? selected : capabilities.includes(item));
    if (await save({ ...data.preferences, step: mode === 'onboarding' ? 'capabilities' : data.preferences.step, capabilities: next,
      status: mode === 'onboarding' || data.preferences.status === 'new' ? 'in_progress' : data.preferences.status })) { if (selected) setExpanded(id); else if (expanded === id) setExpanded(null); }
  };
  const navigate = async (target: SetupDestination) => {
    if (!data || locked || preparation.working) return;
    if (await save({ ...data.preferences, step: mode === 'onboarding' ? step : data.preferences.step,
      status: mode === 'onboarding' || data.preferences.status === 'new' ? 'in_progress' : data.preferences.status })) { preparation.dismissReview(); onNavigate(target); }
  };
  const finish = async (skip: boolean) => {
    if (!data || locked || preparation.working) return;
    if (await save({ ...data.preferences, step: skip ? step : 'finish', status: skip ? 'skipped' : 'completed' })) onClose();
  };
  const command = async (action: string, args: Record<string, unknown> = {}) => {
    if (!data || pending.current || reading.current || needsRead || !can(action) || document.hidden) return;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); pending.current = true; setWorking(action); setError(''); setNotice('');
    try {
      await run(action, args);
      if (action === 'setup.permission') {
        if (alive.current && ticket === generation.current) setNotice('返回这里后会重新检查。你也可以随时点刷新。');
        return;
      }
      const actual = readSetup(await run('setup.read'));
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return;
      setData(actual); latest.current.data = actual;
    } catch (failure) { if (alive.current && ticket === generation.current) { setError(failureText(failure)); setNeedsRead(true); } }
    finally { if (ticket === generation.current) { pending.current = false; if (alive.current) { setWorking(''); if (returnRead.current && !document.hidden) void loadRef.current(); } } }
  };
  const permission = (kind: SetupPermission) => void command('setup.permission', { kind });
  back.current = () => {
    if (pending.current || preparation.working) return true;
    if (preparation.review) { preparation.dismissReview(); return true; }
    if (step === 'capabilities' && expanded) { setExpanded(null); return true; }
    if (mode === 'onboarding' && setupSteps.indexOf(step) > 0) { void move(setupSteps[setupSteps.indexOf(step) - 1]); return true; }
    return false;
  };
  const capabilitySummary = (id: SetupCapability) => {
    if (!data) return '待检查';
    if (id === 'phone') return data.permissions.accessibility === true ? `界面读取已允许 · ${phoneConnection}` : '需要界面读取权限';
    if (id === 'tools') return preparation.view ? runtimeStatusLabel(preparation.view) : '尚未检查工具';
    if (id === 'voice') return data.speech.available === false ? '系统语音服务不可用' : data.speech.available === true && data.permissions.microphone === true ? '系统服务可用 · 尚未实际听写' : '需要检查系统服务与麦克风';
    return data.permissions.notifications === true ? '任务通知已允许' : '任务通知尚未允许';
  };
  const permissionAction = (kind: SetupPermission, value: boolean | null, label = '前往设置') => <Button variant="secondary" disabled={locked || !can('setup.permission')} onClick={() => permission(kind)}>{value === true ? '查看设置' : label}</Button>;
  const modelSection = data && <section className="pw-setup-model" aria-label="模型连接准备">
    <SetupRow title={data.model.label || '连接你的模型'} status={setupModelLabel(data.model)} action={<Button variant={data.model.configured ? 'secondary' : 'primary'} trailingIcon={RiArrowRightSLine} disabled={locked} onClick={() => void navigate('connections')}>{data.model.configured ? '管理模型' : '连接模型'}</Button>}/>
    {data.model.message && <p className="pw-setup-copy">{data.model.message}</p>}
    {data.model.configured && <div className="pw-setup-model-test"><Button variant="secondary" disabled={locked || !!data.model.testing || !can('setup.model.test')} onClick={() => void command('setup.model.test')}>{working === 'setup.model.test' || data.model.testing ? '正在测试文本连接…' : '测试文本连接'}</Button><p>会向当前服务发送一次测试请求，可能产生用量。工具与图像能力需分别测试。</p></div>}
  </section>;

  return <section className="pw-setup-workspace" aria-label={mode === 'onboarding' ? '首次使用准备' : '准备中心'} data-setup-step={step}>
    <header className="pw-setup-header"><div><p>{mode === 'onboarding' ? '首次使用' : '随时继续准备'}</p><h1>准备中心</h1></div>
      <div><Button variant="ghost" iconOnly leadingIcon={RiRefreshLine} aria-label="刷新准备状态" disabled={loading || !!working || !readable} onClick={() => void load()}/>
        <Button variant="ghost" iconOnly leadingIcon={RiCloseLine} aria-label={mode === 'onboarding' ? '暂时跳过准备' : '关闭准备中心'} disabled={!!working || !!preparation.working} onClick={() => mode === 'onboarding' ? void finish(true) : onClose()}/></div>
    </header>
    {mode === 'onboarding' && <SetupSteps step={step} disabled={locked || !data} onStep={next => void move(next)}/>}
    {!data ? <div className="pw-setup-loading"><h2>{readable ? '正在读取本机状态' : '准备中心暂不可用'}</h2><p>{readable ? '模型、权限和工具准备会分别显示。' : '当前宿主未提供准备入口，请更新应用。'}</p>{error && <p role="alert" className="pw-setup-error">{error}</p>}<Button variant="secondary" disabled={loading || !readable} onClick={() => void load()}>重新读取</Button><Button variant="ghost" onClick={onClose}>返回应用</Button></div> : <>
      <div className="pw-setup-body" ref={stepBody}>
        <motion.div className="pw-setup-stage" key={step} initial={reducedMotion ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: reducedMotion ? 0 : .22, ease: [.22, 1, .36, 1] }}>
          {step === 'welcome' && <><div className="pw-setup-intro"><h2 ref={heading} tabIndex={-1}>先连接模型，再选择想做的事</h2><p>文字对话只需模型连接。手机操作、开发工具和语音都可以按需准备。</p></div><div className="pw-setup-welcome-summary"><SetupRow title="模型" status={setupModelLabel(data.model)}/><SetupRow title="其他能力" status="由你选择，随时可以回来调整"/></div><Button variant="ghost" trailingIcon={RiArrowRightSLine} disabled={locked} onClick={() => void navigate('appearance')}>外观与字体</Button></>}
          {step === 'model' && <><div className="pw-setup-intro"><h2 ref={heading} tabIndex={-1}>连接一个模型</h2><p>添加服务并选择模型，也可以使用已有账号。返回这里继续准备。</p></div>{modelSection}</>}
          {step === 'capabilities' && <><div className="pw-setup-intro"><h2 ref={heading} tabIndex={-1}>{mode === 'onboarding' ? '还想用它做什么？' : '准备你需要的能力'}</h2><p>只选需要的用途；选择本身不会申请权限或下载工具。</p></div>{mode === 'center' && modelSection}
            <div className="pw-setup-capabilities">{setupCapabilities.map(id => {
              const copy = capabilityCopy[id], Icon = copy.icon, open = expanded === id;
              return <div className="pw-setup-capability" key={id} data-selected={capabilities.includes(id)} data-expanded={open}>
                <div className="pw-setup-capability-row"><Icon size={21} aria-hidden="true"/><CheckboxField className="pw-setup-choice" isSelected={capabilities.includes(id)} isDisabled={locked} onChange={selected => void selectCapability(id, selected)}><span><strong>{copy.title}</strong><small>{copy.description}</small></span></CheckboxField>
                  <Button variant="ghost" iconOnly leadingIcon={RiArrowRightSLine} className="pw-setup-expand" aria-label={`${open ? '收起' : '查看'}${copy.title}准备`} aria-expanded={open} aria-controls={`setup-${id}`} disabled={!!working || !!preparation.working} onClick={() => { preparation.dismissReview(); setExpanded(open ? null : id); }}/></div>
                {capabilities.includes(id) && <p className="pw-setup-capability-status">{capabilitySummary(id)}</p>}
                <motion.div id={`setup-${id}`} className="pw-setup-capability-reveal" initial={false} animate={{ height: open ? 'auto' : 0, opacity: open ? 1 : 0 }} transition={{ duration: reducedMotion ? 0 : .22, ease: [.22, 1, .36, 1] }} inert={!open} aria-hidden={!open}><div className="pw-setup-capability-content">
                  {id === 'phone' && <><SetupRow title="界面读取与主屏控制" status={permissionLabel(data.permissions.accessibility)} action={permissionAction('accessibility', data.permissions.accessibility)}/><SetupRow title="后台独立屏" status={phoneConnection} action={<Button variant="secondary" disabled={locked} onClick={() => void navigate('background')}>连接与检查</Button>}/><p className="pw-setup-copy">授权、助手连接和实际操作是不同状态。完成连接后，可在检查页试验读取、点击与输入。</p></>}
                  {id === 'tools' && <RuntimePreparation preparation={preparation} can={can} blocked={locked}/>}
                  {id === 'voice' && <><SetupRow title="系统语音服务" status={data.speech.available === true ? '检测到可用服务' : data.speech.available === false ? '未检测到可用服务' : '待检查'}/><p className="pw-setup-copy">{speechNetworkLabel(data.speech.networkUse)}。</p><SetupRow title="麦克风" status={permissionLabel(data.permissions.microphone)} action={permissionAction('microphone', data.permissions.microphone, '允许麦克风')}/><p className="pw-setup-copy">返回对话后点语音按钮，核对听写文字再采用。这里不下载独立离线语音模型。</p></>}
                  {id === 'background' && <><SetupRow title="任务通知" status={permissionLabel(data.permissions.notifications)} action={permissionAction('notifications', data.permissions.notifications, '允许通知')}/><SetupRow title="电池优化" status={data.permissions.batteryExempt === true ? '已允许不受电池优化限制' : data.permissions.batteryExempt === false ? '仍受系统电池优化限制' : '待检查'} action={permissionAction('battery', data.permissions.batteryExempt)}/><SetupRow title="厂商后台设置" status={data.permissions.vendorBackgroundVisited === true ? '已打开设置 · 请以系统实际选择为准' : '按这台手机的设置检查'} action={permissionAction('vendorBackground', null)}/><p className="pw-setup-copy">系统或厂商仍可能暂停后台工作。打开过设置不代表已经允许。</p></>}
                </div></motion.div>
              </div>;
            })}</div>
          </>}
          {step === 'finish' && <><div className="pw-setup-intro"><h2 ref={heading} tabIndex={-1}>看看现在准备到了哪里</h2><p>未完成的项目会保留，之后可从准备中心继续。</p></div><div className="pw-setup-review"><SetupRow title="文字对话" status={setupModelLabel(data.model)} action={<Button variant="ghost" disabled={locked} onClick={() => void move('model')}>调整</Button>}/>{capabilities.map(id => <SetupRow key={id} title={capabilityCopy[id].title} status={capabilitySummary(id)} action={<Button variant="ghost" disabled={locked} onClick={() => { setExpanded(id); void move('capabilities'); }}>继续准备</Button>}/>)}</div>{!data.model.configured && <p className="pw-setup-copy">尚未连接模型，进入应用后仍可继续设置。</p>}{runtimePreparing(preparation.view) && <p className="pw-setup-copy">开发工具仍在准备，进入应用后可回到这里查看进度或停止。</p>}</>}
        </motion.div>
        {error && <p role="alert" className="pw-setup-error">{error}</p>}{needsRead && <Button variant="secondary" disabled={loading || !!working} onClick={() => void load()}>刷新并核对</Button>}{notice && <p role="status" className="pw-setup-notice">{notice}</p>}
      </div>
      <footer className="pw-setup-footer">
        {mode === 'onboarding' && step !== 'welcome' ? <Button variant="ghost" leadingIcon={RiArrowLeftLine} disabled={locked || !!preparation.working} onClick={() => void move(setupSteps[setupSteps.indexOf(step) - 1])}>上一步</Button> : <Button variant="ghost" disabled={!!working || !!preparation.working || needsRead} onClick={() => mode === 'onboarding' ? void finish(true) : onClose()}>{mode === 'onboarding' ? '以后再说' : '返回'}</Button>}
        {step === 'finish' || mode === 'center' ? <Button disabled={locked || !!preparation.working} onClick={() => void finish(false)}>{working === 'save' ? '正在保存…' : mode === 'center' ? '完成' : '进入应用'}</Button> : <Button trailingIcon={RiArrowRightLine} disabled={locked || !!preparation.working} onClick={() => void move(setupSteps[setupSteps.indexOf(step) + 1])}>{step === 'welcome' ? '开始准备' : step === 'model' && !data.model.configured ? '稍后连接，继续' : step === 'capabilities' ? '查看准备状态' : '继续'}</Button>}
      </footer>
    </>}
  </section>;
}
