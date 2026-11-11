import { useEffect, useRef, useState, type ReactNode } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { IconArrowLeft, IconChevronRight, IconClose, IconMicrophone, IconModel, IconMonitor, IconMoon, IconPhone, IconRefresh } from './icons';
import { Accordion, AccordionItem, Button } from './forms-library';
import { ProgressBar } from './library';
import { backgroundConnection } from './forms-library/host-contracts';
import { bridge, type PocketSnapshot } from './bridge';
import { permissionLabel, readSetup, readSetupRuntime, readSetupRuntimeReview, runtimeCancelInput, runtimeLogText, runtimePreparing, runtimeProgressLabel,
  runtimePhaseLabel, runtimeStatusLabel, setupBytes, setupCapabilities, setupDetails, setupModelLabel, setupObject, setupSaveConfirmed, setupSaveInput,
  speechNetworkLabel, type SetupCapability, type SetupPermission, type SetupPreferences, type SetupRead, type SetupRuntime, type SetupRuntimeReview, type SetupStep } from './setup-contract';
import './shell-library/affect-workflows.css';
import './setup.css';

export type SetupDestination = 'connections' | 'background' | 'appearance' | 'local';
export type SetupWorkspaceProps = {
  snapshot: PocketSnapshot;
  mode?: 'onboarding' | 'center';
  initialCapability?: SetupCapability;
  initialView?: SetupView;
  runtimeTaskId?: string;
  returnLabel?: string;
  originGoal?: string;
  onNavigate: (target: SetupDestination) => void;
  onViewChange?: (view: SetupView) => void;
  onClose: () => void;
  registerBackHandler?: (handler: (() => boolean) | null) => void;
};

const capabilityCopy = {
  phone: { title: '操作手机', description: '读取界面、点击与输入', icon: IconPhone },
  tools: { title: '运行项目与命令', description: '准备手机内置的开发工具与运行环境', icon: IconMonitor },
  voice: { title: '语音输入', description: '使用这台手机的系统听写', icon: IconMicrophone },
  background: { title: '后台继续工作', description: '任务通知与后台运行设置', icon: IconMoon },
} satisfies Record<SetupCapability, { title: string; description: string; icon: typeof IconPhone }>;
export type SetupView = 'overview' | 'model' | SetupCapability;
const viewStep = (view: SetupView): SetupStep => view === 'model' ? 'model' : 'capabilities';
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure || '操作暂未完成，请刷新后核对。');
type SetupCommand = (action: string, args?: Record<string, unknown>) => Promise<unknown>;

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
      <Button variant="ghost" leadingIcon={IconRefresh} disabled={locked || !can('setup.runtime.read')} onClick={() => void preparation.read()}>刷新</Button></div>
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

/** The approved Affect task body/review/footer and BoardUI settings rows now
 * serve one preparation purpose at a time. Guide preferences remain separate
 * from real permissions, model availability and runtime operation receipts. */
export function SetupWorkspace({ snapshot, mode = 'center', initialCapability, initialView, runtimeTaskId, returnLabel, originGoal, onNavigate, onViewChange, onClose, registerBackHandler }: SetupWorkspaceProps) {
  const [data, setData] = useState<SetupRead | null>(null), [view, setView] = useState<SetupView>(initialCapability || initialView || (mode === 'onboarding' ? 'model' : 'overview'));
  const [loading, setLoading] = useState(false), [working, setWorking] = useState('');
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [needsRead, setNeedsRead] = useState(false);
  const [entered, setEntered] = useState(false);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), reading = useRef(false), entering = useRef(false);
  const returnRead = useRef(false);
  const latest = useRef({ data, snapshot, onNavigate, onClose, entered }); latest.current = { data, snapshot, onNavigate, onClose, entered };
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
  const locked = !entered || loading || !!working || needsRead;
  const focused = !!initialCapability, onboarding = mode === 'onboarding';
  const resumeRevision = setupObject(snapshot.view).resumeRevision;
  const preparation = useRuntimePreparation(entered && !!data && (view === 'tools' || view === 'overview'), can, resumeRevision, run, runtimeTaskId);
  const connection = backgroundConnection(setupObject(setupObject(snapshot.state.features).display), snapshot.state.activeTaskId, Array.isArray(snapshot.state.moduleBusy) ? snapshot.state.moduleBusy : []);
  const phoneConnection = connection.requiresConnectionCheck ? '连接状态待核对' : !connection.loaded ? '后台连接状态尚未读取' : connection.helperConnected ? '后台助手已连接' : !connection.supported ? '后台独立屏不受支持' : '后台助手尚未连接';
  const enter = async () => {
    if (!alive.current || entering.current || latest.current.entered) return;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); entering.current = true; setLoading(true);
    try {
      await bridge.command('connections.enter', { section: 'setup' });
      if (alive.current && generation.current === ticket && bridge.getDocumentEpoch() === epoch) { setEntered(true); setError(''); }
    } catch (failure) { if (alive.current && generation.current === ticket) setError(failureText(failure)); }
    finally { if (generation.current === ticket) { entering.current = false; if (alive.current) setLoading(false); } }
  };
  const load = async () => {
    if (!alive.current || pending.current || reading.current || !latest.current.snapshot.capabilities.commands.includes('setup.read')) return;
    if (!latest.current.entered) { await enter(); return; }
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); reading.current = true; setLoading(true);
    try {
      const value = readSetup(await run('setup.read'));
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return;
      setData(value); setNeedsRead(false); setError(''); returnRead.current = false;
    } catch (failure) { if (alive.current && ticket === generation.current) { setError(failureText(failure)); setNeedsRead(true); } }
    finally { if (ticket === generation.current) { reading.current = false; if (alive.current) setLoading(false); } }
  };
  const loadRef = useRef(load); loadRef.current = load;
  useEffect(() => {
    alive.current = true; void enter();
    const visible = () => { if (!document.hidden) { returnRead.current = true; void loadRef.current(); } };
    document.addEventListener('visibilitychange', visible);
    return () => { alive.current = false; generation.current++; document.removeEventListener('visibilitychange', visible); void bridge.command('connections.leave', { section: 'setup' }).catch(() => {}); };
  }, []);
  const readable = can('setup.read');
  useEffect(() => { if (entered && readable) { returnRead.current = true; void loadRef.current(); } }, [entered, readable, resumeRevision, snapshot.state.modelSelectionRevision]);
  useEffect(() => { stepBody.current?.scrollTo({ top: 0 }); heading.current?.focus({ preventScroll: true }); }, [view]);
  useEffect(() => { onViewChange?.(view); }, [view, onViewChange]);
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
  const openView = async (next: SetupView) => {
    if (!data || locked || preparation.working) return;
    if (onboarding && !await save({ ...data.preferences, step: viewStep(next), status: 'in_progress' })) return;
    preparation.dismissReview(); setNotice(''); setView(next);
  };
  const navigate = async (target: SetupDestination) => {
    if (!data || locked || preparation.working) return;
    if (onboarding && !await save({ ...data.preferences, step: viewStep(view), status: 'in_progress' })) return;
    preparation.dismissReview(); onNavigate(target);
  };
  const finish = async (skip: boolean) => {
    if (pending.current || preparation.working) return;
    if (!onboarding) { preparation.dismissReview(); onClose(); return; }
    if (!data || locked || !skip && (!data.model.configured || data.model.availability === 'unavailable')) return;
    if (await save({ ...data.preferences, step: skip ? viewStep(view) : 'finish', status: skip ? 'skipped' : 'completed' })) onClose();
  };
  const close = () => {
    if (pending.current || preparation.working) return;
    if (onboarding && data) void finish(true); else onClose();
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
  const hasParent = !focused && (onboarding ? view !== 'model' : view !== 'overview');
  const parentView: SetupView = onboarding && view === 'overview' ? 'model' : 'overview';
  back.current = () => {
    if (pending.current || preparation.working) return true;
    if (preparation.review) { preparation.dismissReview(); return true; }
    if (hasParent) {
      if (onboarding) void openView(parentView);
      else { preparation.dismissReview(); setNotice(''); setView(parentView); }
      return true;
    }
    if (onboarding && data) { void finish(true); return true; }
    return false;
  };
  const capabilitySummary = (id: SetupCapability) => {
    if (!data) return '待检查';
    if (id === 'phone') return data.permissions.accessibility === true ? `界面读取已允许 · ${phoneConnection}` : data.permissions.accessibility === false ? '需要界面读取权限' : '界面读取权限待检查';
    if (id === 'tools') return preparation.view ? runtimeStatusLabel(preparation.view) : '打开后检查工具状态';
    if (id === 'voice') return data.speech.available === false ? '系统语音服务不可用' : data.speech.available === true && data.permissions.microphone === true ? '系统服务可用 · 尚未实际听写' : '需要检查系统服务与麦克风';
    return data.permissions.notifications === true ? '任务通知已允许' : data.permissions.notifications === false ? '任务通知尚未允许' : '任务通知权限待检查';
  };
  const permissionAction = (kind: SetupPermission, value: boolean | null, label = '前往设置') => <Button variant={value === true ? 'secondary' : 'primary'} disabled={locked || !can('setup.permission')} onClick={() => permission(kind)}>{value === true ? '查看设置' : label}</Button>;
  const modelSection = data && <section className="pw-setup-model" aria-label="模型连接准备">
    <div className="pw-setup-model-summary"><IconModel size={24} aria-hidden="true"/><SetupRow title={data.model.label || '账号与服务'} status={setupModelLabel(data.model)}/></div>
    {data.model.message && <p className="pw-setup-copy" role={data.model.availability === 'unavailable' ? 'alert' : 'status'}>{data.model.message}</p>}
    <Button className="pw-setup-model-action" variant={data.model.configured ? 'secondary' : 'primary'} trailingIcon={IconChevronRight} disabled={locked || !!preparation.working} onClick={() => void navigate('connections')}>{data.model.availability === 'unavailable' ? '检查模型连接' : data.model.configured ? '管理模型连接' : '连接模型'}</Button>
    {(data.model.configured || data.model.details) && <Accordion className="pw-setup-model-check">
      {data.model.details && <AccordionItem id="setup-model-details" title="查看状态详情"><pre>{data.model.details}</pre></AccordionItem>}
      {data.model.configured && <AccordionItem id="setup-model-check" title="检查文本连接" description={data.model.testing ? '正在测试文本连接…' : undefined}>
        <div className="pw-setup-model-test"><p>会向当前服务发送一次测试请求，可能产生用量。工具与图像能力需分别测试。</p><Button variant="secondary" disabled={locked || !!data.model.testing || !can('setup.model.test')} onClick={() => void command('setup.model.test')}>{working === 'setup.model.test' || data.model.testing ? '正在测试文本连接…' : '测试文本连接'}</Button></div>
      </AccordionItem>}
    </Accordion>}
  </section>;
  const capabilityBody = (id: SetupCapability) => {
    if (!data) return null;
    if (id === 'tools') return <RuntimePreparation preparation={preparation} can={can} blocked={locked}/>;
    if (id === 'phone') return <>
      <section className="pw-setup-purpose-group"><SetupRow title="界面读取与主屏控制" status={permissionLabel(data.permissions.accessibility)} action={permissionAction('accessibility', data.permissions.accessibility)}/><p className="pw-setup-copy">允许后，助手才能读取手机界面并执行你授权的操作。</p></section>
      <section className="pw-setup-purpose-group"><SetupRow title="后台独立屏" status={phoneConnection} action={<Button variant="secondary" disabled={locked} onClick={() => void navigate('background')}>连接与检查</Button>}/><p className="pw-setup-copy">需要在后台独立屏操作时，再连接手机助手。连接状态与实际操作能力分别核对。</p></section>
      <Accordion className="pw-setup-purpose-details"><AccordionItem id="setup-calendar" title="向日历添加事项" description={`读取${permissionLabel(data.permissions.calendarRead)} · 写入${permissionLabel(data.permissions.calendarWrite)}`}>
        <SetupRow title="日历访问" status={permissionLabel(data.permissions.calendar)} action={permissionAction('calendar', data.permissions.calendar, '允许日历访问')}/><p className="pw-setup-copy">用于读取当前手机资料中的可写日历，并在你确认准确日历与完整事件后创建。授权本身不会写入；每批最多 50 项，不会访问其他工作资料。</p>
      </AccordionItem></Accordion>
    </>;
    if (id === 'voice') return <>
      <section className="pw-setup-purpose-group"><SetupRow title="系统语音服务" status={data.speech.available === true ? '检测到可用服务' : data.speech.available === false ? '未检测到可用服务' : '待检查'}/><p className="pw-setup-copy">{speechNetworkLabel(data.speech.networkUse)}。</p></section>
      <section className="pw-setup-purpose-group"><SetupRow title="麦克风" status={permissionLabel(data.permissions.microphone)} action={permissionAction('microphone', data.permissions.microphone, '允许麦克风')}/><p className="pw-setup-copy">回到对话后点语音按钮，核对听写文字再采用。这里使用系统语音服务。</p></section>
    </>;
    return <>
      <section className="pw-setup-purpose-group"><SetupRow title="任务通知" status={permissionLabel(data.permissions.notifications)} action={permissionAction('notifications', data.permissions.notifications, '允许通知')}/><p className="pw-setup-copy">任务需要处理或有新结果时，通过通知提醒你。</p></section>
      <section className="pw-setup-purpose-group"><SetupRow title="电池优化" status={data.permissions.batteryExempt === true ? '已允许不受电池优化限制' : data.permissions.batteryExempt === false ? '仍受系统电池优化限制' : '待检查'} action={permissionAction('battery', data.permissions.batteryExempt)}/><SetupRow title="厂商后台设置" status={data.permissions.vendorBackgroundVisited === true ? '已打开设置 · 请以系统实际选择为准' : '按这台手机的设置检查'} action={permissionAction('vendorBackground', null)}/><p className="pw-setup-copy">系统或厂商仍可能暂停后台工作。打开过设置不代表已经允许。</p></section>
    </>;
  };
  const title = view === 'overview' ? '能力准备' : view === 'model' ? onboarding ? '开始使用' : '模型连接' : capabilityCopy[view].title;
  const returning = returnLabel || '返回原页面';
  const canStartConversation = data?.model.configured === true && data.model.availability !== 'unavailable';
  const parentLabel = parentView === 'model' ? '返回模型连接' : '返回能力准备';
  const headerAction = () => { if (!back.current()) close(); };
  return <section className="pw-setup-workspace" aria-label={title} data-setup-step={viewStep(view)} data-setup-view={view} data-setup-mode={mode} data-setup-focused={focused}>
    <header className="pw-setup-header">
      <Button variant="ghost" iconOnly leadingIcon={hasParent || focused ? IconArrowLeft : IconClose} className="pw-setup-close" aria-label={hasParent ? parentLabel : onboarding ? '稍后设置，进入管家' : returning} title={hasParent ? parentLabel : onboarding ? '稍后设置' : returning} disabled={!!working || !!preparation.working || onboarding && !!data && (loading || needsRead)} onClick={headerAction}/>
      <h1 ref={heading} tabIndex={-1}>{title}</h1>
      {data && <Button variant="ghost" iconOnly leadingIcon={IconRefresh} className="pw-setup-refresh" aria-label="刷新准备状态" title="刷新准备状态" disabled={loading || !!working || !readable} onClick={() => void load()}/>}
    </header>
    {!data ? <div className="pw-setup-loading"><h2>{!readable ? '准备入口暂不可用' : error ? '暂时无法读取准备状态' : '正在读取当前状态'}</h2><p>{focused ? '只检查这次需要的能力。' : '准备进度与权限以这台手机的实际状态为准。'}</p>{error && <p role="alert" className="pw-setup-error">{error}</p>}<Button variant="secondary" disabled={loading || !readable} onClick={() => void load()}>重新读取</Button></div> : <>
      <div className="pw-setup-body" ref={stepBody}>
        {originGoal?.trim() && <div className="pw-setup-origin"><span>当前要做的事</span><p>{originGoal}</p></div>}
        <motion.div className="pw-setup-stage" key={view} initial={reducedMotion ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: reducedMotion ? 0 : .22, ease: [.22, 1, .36, 1] }}>
          {view === 'overview' && <><div className="pw-setup-intro"><h2>准备想用的能力</h2><p>选择要做的事，查看对应状态和下一步。</p></div><div className="pw-setup-purpose-list">
            <Button variant="ghost" className="pw-setup-purpose-row" onClick={() => void openView('model')} disabled={locked || !!preparation.working}><IconModel size={24} aria-hidden="true"/><span><strong>对话与推理</strong><small>{setupModelLabel(data.model)}</small></span><IconChevronRight size={18} aria-hidden="true"/></Button>
            {setupCapabilities.map(id => { const copy = capabilityCopy[id], Icon = copy.icon; return <Button variant="ghost" key={id} className="pw-setup-purpose-row" onClick={() => void openView(id)} disabled={locked || !!preparation.working}><Icon size={24} aria-hidden="true"/><span><strong>{copy.title}</strong><small>{capabilitySummary(id)}</small></span><IconChevronRight size={18} aria-hidden="true"/></Button>; })}
          </div><p className="pw-setup-next-hint">准备只在你选择后进行。已开始的工具准备保留在原任务中，可随时查看或停止。</p></>}
          {view === 'model' && <>{onboarding && <div className="pw-setup-intro"><h2>先连接一个模型</h2><p>登录已有账号，或添加模型服务，就能让管家接办事情。</p></div>}{modelSection}{onboarding && <div className="pw-setup-optional"><p>手机操作、开发与语音可以在需要时准备。</p><Button variant="ghost" trailingIcon={IconChevronRight} disabled={locked || !!preparation.working} onClick={() => void openView('overview')}>查看其他能力</Button></div>}</>}
          {view !== 'overview' && view !== 'model' && <div className="pw-setup-purpose-detail"><p className="pw-setup-purpose-description">{capabilityCopy[view].description}</p>{capabilityBody(view)}</div>}
        </motion.div>
        {(error || needsRead || notice) && <div className="pw-setup-feedback">{error && <p role="alert" className="pw-setup-error">{error}</p>}{needsRead && <Button variant="secondary" disabled={loading || !!working} onClick={() => void load()}>刷新并核对</Button>}{notice && <p role="status" className="pw-setup-notice">{notice}</p>}</div>}
      </div>
      <footer className="pw-setup-footer">
        {onboarding ? <>{canStartConversation && <Button className="pw-setup-next" disabled={locked || !!preparation.working} onClick={() => void finish(false)}>{working === 'save' ? '正在保存…' : '进入管家'}</Button>}<Button variant="ghost" className={canStartConversation ? 'pw-setup-skip' : 'pw-setup-next'} disabled={locked || !!preparation.working} onClick={() => void finish(true)}>稍后设置</Button></> : <Button className="pw-setup-next" variant="secondary" disabled={!!working || !!preparation.working} onClick={() => void finish(false)}>{returning}</Button>}
      </footer>
    </>}
  </section>;
}
