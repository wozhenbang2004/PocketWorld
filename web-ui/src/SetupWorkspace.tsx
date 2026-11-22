import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useSurfaceEnter } from './library/surface-motion';
import { IconArrowLeft, IconChevronRight, IconClose, IconMicrophone, IconModel, IconMonitor, IconMoon, IconPhone, IconRefresh } from './icons';
import { Accordion, AccordionItem, Button } from './forms-library';
import { ProgressBar } from './library';
import { WorkspaceSectionHeading } from './WorkspaceSectionHeading';
import { PhoneAccessibilitySetupHelp } from './PhoneAccessibilitySetupHelp';
import { backgroundConnection } from './forms-library/host-contracts';
import { bridge, type PocketSnapshot } from './bridge';
import { permissionLabel, readSetup, readSetupRuntime, readSetupRuntimeReview, runtimeCancelInput, runtimeLogText, runtimePreparing, runtimeProgressLabel, sameOwnerPythonRepair,
  runtimePhaseLabel, runtimeRecoveryHint, runtimeStatusLabel, runtimeToolSummary, setupBytes, setupCapabilities, setupDetails, setupModelLabel, setupObject, setupSaveConfirmed, setupSaveInput,
  speechNetworkLabel, type OwnerPythonRepairObservation, type SetupCapability, type SetupPermission, type SetupPreferences, type SetupRead, type SetupRuntime, type SetupRuntimeReview, type SetupStep } from './setup-contract';
import './shell-library/affect-workflows.css';
import './setup.css';

export type SetupDestination = 'connections' | 'background' | 'appearance' | 'local';
type OriginalPythonRepair = { observation: OwnerPythonRepairObservation; current: (foregroundEpoch: number) => boolean };
type SubmittedPythonRepair = { requestId: string; observation: OwnerPythonRepairObservation };
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
  onReviewOriginalVite?: (runtimeIdentity:string) => void;
  originalPython?: {path:string; onReturn:(foregroundEpoch:number)=>void; repair?:OriginalPythonRepair};
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
type SetupCommand = (action: string, args?: Record<string, unknown>, stillCurrent?: () => boolean) => Promise<unknown>;
const repairReviewMatches = (review: SetupRuntimeReview, expected: OwnerPythonRepairObservation) =>
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(review.review.requestId) &&
  review.review.mode === 'repair' && review.review.runtimeIdentity === expected.runtimeIdentity &&
  review.review.catalogIdentity === expected.catalogIdentity && review.review.existingGenerationRetained;
const repairOperationMatches = (view: SetupRuntime | null, requestId: string) =>
  view?.operation?.taskId === requestId && view.operation.runId === requestId &&
  view.operation.operationId === requestId && view.operation.epoch === 1;
const originalRepairChanged = '未核对到原脚本离开时的同一工具状态。请刷新，或返回原副本重新核对。';
const originalRepairUnobserved = '尚未核对到本次修复的原记录。刷新只会查询，不会重复发起修复。';

function SetupRow({ title, status, children, action }: { title: string; status: string; children?: ReactNode; action?: ReactNode }) {
  return <div className="pw-setup-status-row"><div><strong>{title}</strong><span>{status}</span>{children && <p>{children}</p>}</div>{action}</div>;
}

/** One native preparation operation; polling observes it and never starts one. */
function useRuntimePreparation(enabled: boolean, can: (action: string) => boolean, resumeRevision: unknown, run: SetupCommand, runtimeTaskId?: string, pythonEpoch?:number, pythonRepair?:OriginalPythonRepair) {
  const [view, setView] = useState<SetupRuntime | null>(null), [review, setReview] = useState<SetupRuntimeReview | null>(null);
  const [reading, setReading] = useState(false), [working, setWorking] = useState(''), [error, setError] = useState('');
  const [log, setLog] = useState(''), [logTrimmed, setLogTrimmed] = useState(false), [needsRead, setNeedsRead] = useState(false);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), readingRef = useRef(false);
  const current = useRef(view), cursor = useRef(0), logOwner = useRef(''); current.current = view;
  const currentReview = useRef(review); currentReview.current = review;
  const reviewEpoch = useRef<number|undefined>(undefined), requestedReadEpoch = useRef<number|undefined>(undefined);
  const readProof = useRef<{view:SetupRuntime; epoch:number}|undefined>(undefined);
  const submittedRepair = useRef<SubmittedPythonRepair|undefined>(undefined);
  const latest = useRef({ enabled, can, run, pythonEpoch, pythonRepair }); latest.current = { enabled, can, run, pythonEpoch, pythonRepair };
  const ownsSubmittedRepair = (attempt: SubmittedPythonRepair, epoch: number) => {
    const repair = latest.current.pythonRepair;
    return submittedRepair.current === attempt && latest.current.enabled && !!repair &&
      repair.observation === attempt.observation && repair.current(epoch);
  };
  const currentPythonRepair = () => {
    const repair = latest.current.pythonRepair, fresh = readProof.current, epoch = bridge.getDocumentEpoch();
    if (!latest.current.enabled || !repair || !fresh || document.hidden || fresh.epoch !== epoch || fresh.view !== current.current ||
        fresh.view.ready || !fresh.view.canPrepare || runtimePreparing(fresh.view) || !repair.current(epoch) ||
        !sameOwnerPythonRepair(repair.observation, fresh.view.runtime)) return;
    return repair.observation;
  };
  const dismissReview = () => {
    const original = currentReview.current, epoch=reviewEpoch.current;
    currentReview.current = null; reviewEpoch.current=undefined; setReview(null);
    if (original && (latest.current.pythonEpoch===undefined || epoch===bridge.getDocumentEpoch()) &&
        latest.current.can('setup.runtime.dismiss')) void latest.current.run('setup.runtime.dismiss', { reviewHandle: original.reviewHandle }).catch(() => {});
  };
  const apply = (value: unknown, requestedTaskId?: string) => {
    const next = readSetupRuntime(value);
    if (submittedRepair.current && !repairOperationMatches(next, submittedRepair.current.requestId)) throw new Error(originalRepairUnobserved);
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
    if (!alive.current || !latest.current.can('setup.runtime.read') || readingRef.current || pending.current || document.hidden) return false;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(), attempt = submittedRepair.current;
    const ownerCurrent = attempt ? () => ownsSubmittedRepair(attempt, epoch) : undefined;
    if (ownerCurrent && !ownerCurrent()) return false;
    readingRef.current = true; setReading(true);
    requestedReadEpoch.current=epoch;
    try {
      const taskId = attempt?.requestId || current.current?.operation?.taskId || runtimeTaskId;
      const afterSequence = attempt && !repairOperationMatches(current.current, attempt.requestId) ? 0 : cursor.current;
      const result = await latest.current.run('setup.runtime.read', { ...(taskId ? { taskId } : {}), afterSequence, maxBytes: 32768 }, ownerCurrent);
      if (!alive.current || generation.current !== ticket || bridge.getDocumentEpoch() !== epoch || ownerCurrent && !ownerCurrent()) return false;
      readProof.current={view:apply(result,taskId),epoch};
      if (!preserveActionError) setError(''); return true;
    } catch (failure) { if (alive.current && generation.current === ticket) { setError(attempt ? originalRepairUnobserved : failureText(failure)); setNeedsRead(true); } return false; }
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
    if (pythonEpoch===undefined || !enabled || !readable || document.hidden) return;
    // A foreground token change leaves the install intact, but not its old review
    // or readiness observation. Only read the current/retained native install.
    if (currentReview.current && reviewEpoch.current!==pythonEpoch) dismissReview();
    if (!reading && !working && requestedReadEpoch.current!==pythonEpoch) void readRef.current();
  }, [enabled,readable,pythonEpoch,reading,working]);
  useEffect(() => {
    if (!enabled || !runtimePreparing(view) || needsRead || document.hidden) return;
    const timer = window.setTimeout(() => { void readRef.current(); }, 1500);
    return () => window.clearTimeout(timer);
  }, [enabled, view, reading, working, needsRead]);
  const action = async (command: string, args: Record<string, unknown> = {}, expectedRepair?:OwnerPythonRepairObservation, repairStartId?: string) => {
    if (!alive.current || pending.current || readingRef.current || !can(command) || document.hidden) return;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch();
    if (repairStartId) {
      if (submittedRepair.current || !expectedRepair || currentPythonRepair() !== expectedRepair) return;
      // Native uses this reviewed requestId as its TaskStore task/run/operation ID.
      // Retain a read locator, not an approval handle, before the single explicit Start.
      submittedRepair.current = { requestId: repairStartId, observation: expectedRepair };
    }
    const attempt = submittedRepair.current;
    if (attempt && (!ownsSubmittedRepair(attempt, epoch) ||
        !repairStartId && (command !== 'setup.runtime.cancel' || !repairOperationMatches(current.current, attempt.requestId)))) return;
    const ownerCurrent = attempt ? () => ownsSubmittedRepair(attempt, epoch) && (!expectedRepair || currentPythonRepair() === expectedRepair)
      : expectedRepair ? () => currentPythonRepair() === expectedRepair : undefined;
    pending.current = true; setWorking(command); setError('');
    try {
      const result = await latest.current.run(command, args, ownerCurrent);
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch || attempt && !ownsSubmittedRepair(attempt, epoch)) return;
      if (command === 'setup.runtime.review') {
        const next = readSetupRuntimeReview(result);
        if (expectedRepair && (currentPythonRepair() !== expectedRepair || !repairReviewMatches(next, expectedRepair))) {
          if (latest.current.can('setup.runtime.dismiss'))
            void latest.current.run('setup.runtime.dismiss', { reviewHandle: next.reviewHandle }).catch(() => {});
          throw new Error(originalRepairChanged);
        }
        reviewEpoch.current=epoch; setReview(next);
      } else {
        reviewEpoch.current=undefined; setReview(null);
        if (attempt || setupObject(result).format === 'pocketworld.runtime-setup-view.v1') apply(result);
      }
    } catch (failure) { if (alive.current && ticket === generation.current) { setReview(null); setError(repairStartId ? originalRepairUnobserved : failureText(failure)); setNeedsRead(true); } }
    finally {
      if (ticket === generation.current) { pending.current = false; if (alive.current) { setWorking(''); if (command !== 'setup.runtime.review') void readRef.current(!repairStartId); } }
    }
  };
  const attempt = submittedRepair.current;
  const repairUnobserved = !!attempt && !repairOperationMatches(view, attempt.requestId);
  const repairCurrent = !!pythonRepair && !attempt && !needsRead && currentPythonRepair() === pythonRepair.observation;
  const liveReview = (pythonEpoch===undefined||reviewEpoch.current===pythonEpoch) &&
    (!pythonRepair || repairCurrent && review && repairReviewMatches(review, pythonRepair.observation)) ? review : null;
  const readEpoch=readProof.current?.view===view?readProof.current?.epoch:undefined;
  const needsForegroundRead=()=>pythonEpoch!==undefined&&readEpoch!==bridge.getDocumentEpoch();
  return { view:repairUnobserved?null:view, review:liveReview, readEpoch, reading, working, error,
    log:repairUnobserved?'':log, logTrimmed:!repairUnobserved&&logTrimmed, needsRead, read, dismissReview,
    repairVisit:!!pythonRepair, repairCurrent, repairAttempted:!!attempt, repairUnobserved,
    reviewPreparation: async () => {
      if (!pythonRepair) return needsForegroundRead()?read():action('setup.runtime.review', { mode: view?.ready ? 'repair' : 'prepare' });
      const epoch = bridge.getDocumentEpoch(), expected = pythonRepair.observation;
      // Re-read the installed generation on this explicit click before requesting its native consent sheet.
      if (submittedRepair.current || !repairCurrent || !(await read())) return;
      if (bridge.getDocumentEpoch() !== epoch || !alive.current) return;
      if (currentPythonRepair() !== expected) { setError(originalRepairChanged); return; }
      return action('setup.runtime.review', { mode: 'repair' }, expected);
    },
    start: () => {
      if (submittedRepair.current) return;
      if (!liveReview || liveReview.review.expiresAt <= Date.now() ||
          pythonEpoch!==undefined&&reviewEpoch.current!==bridge.getDocumentEpoch()) {
        dismissReview(); setError('这份准备说明已过期，请重新查看后开始。'); return;
      }
      if (pythonRepair && (currentPythonRepair() !== pythonRepair.observation || !repairReviewMatches(liveReview, pythonRepair.observation))) {
        dismissReview(); setError(originalRepairChanged); return;
      }
      return action('setup.runtime.start', { reviewHandle: liveReview.reviewHandle }, pythonRepair?.observation,
        pythonRepair ? liveReview.review.requestId : undefined);
    },
    cancel: () => {
      if(needsForegroundRead())return read();
      if (view && !repairUnobserved) { try { return action('setup.runtime.cancel', runtimeCancelInput(view)); } catch (failure) { setError(failureText(failure)); } }
    },
  };
}

function RuntimePreparation({ preparation, can, blocked, projectReview }: { preparation: ReturnType<typeof useRuntimePreparation>; can: (action: string) => boolean; blocked: boolean; projectReview: boolean }) {
  const { view, review, reading, working, error, log, logTrimmed, needsRead, repairVisit, repairCurrent, repairAttempted, repairUnobserved } = preparation;
  const locked = blocked || reading || !!working, running = runtimePreparing(view), operation = view?.operation;
  const reviewDetails = review ? { effects: review.review.effects, runtime: review.review.runtimeIdentity, catalog: review.review.catalogIdentity } : null;
  const originalError = operation?.error;
  const recoveryHint = view ? runtimeRecoveryHint(view) : '';
  const failedPhase = setupObject(originalError?.details).failedPreparationPhase;
  return <section className="pw-setup-runtime" aria-label="开发工具准备" aria-busy={reading || !!working}>
    <div className="pw-setup-section-title"><div><h3>手机开发工具</h3><p role="status">{repairUnobserved ? locked ? '正在核对本次修复' : '本次修复待核对' : repairCurrent ? 'Python 工具需要修复' : view ? runtimeStatusLabel(view) : reading ? '正在检查' : '尚未检查'}</p></div>
      <Button variant="ghost" leadingIcon={IconRefresh} disabled={locked || !can('setup.runtime.read')} onClick={() => void preparation.read()}>刷新</Button></div>
    {view?.message && <p className="pw-setup-copy">{view.message}</p>}
    <p className="pw-setup-copy">用于 Web / Node.js 项目、Python 与本机命令。普通对话不需要安装，也不需要先开放手机控制权限。</p>
    {view && <Accordion className="pw-setup-details"><AccordionItem id="setup-runtime-capabilities" title="工具与支持范围" description={view.ready ? '基础工具可用，不等于项目已配置' : '安装内容与当前实际状态'}>
      {runtimeToolSummary(view).map(tool => <SetupRow key={tool.name} title={tool.label} status={tool.status}/>)}
      <p className="pw-setup-copy">项目还可能需要安装依赖、配置入口，并单独批准运行。本机工具不会自动挂载到模型，也不会代替执行审批。</p>
      <p className="pw-setup-copy">当前不包含完整的 JDK、Gradle 与 Android SDK。识别到 Android 项目，不代表已经能在手机构建 APK。</p>
    </AccordionItem></Accordion>}
    {!can('setup.runtime.read') && <p className="pw-setup-copy">当前应用尚未接入工具准备，请更新应用后重试。</p>}
    {running && operation && <div className="pw-setup-progress">
      <ProgressBar label={runtimePhaseLabel(operation.phase)} value={operation.fraction ?? undefined} maxValue={1} isIndeterminate={operation.fraction == null} showValueLabel={operation.fraction != null} size="sm"/>
      {runtimeProgressLabel(operation) && <p>{runtimeProgressLabel(operation)} · 当前阶段</p>}
    </div>}
    {error && <p className="pw-setup-error" role="alert">{error}</p>}
    {originalError?.message && <p className="pw-setup-error" role="alert">{originalError.message}</p>}
    {recoveryHint && <p className="pw-setup-copy" role="status">{recoveryHint}</p>}
    {view?.status === 'interrupted' && <p className="pw-setup-copy">上一次准备未确认完成。先刷新检查，再按当前状态继续。</p>}
    {repairVisit && !repairAttempted && view?.canPrepare && !view.ready && !repairCurrent && !locked && !running && !error &&
      <p className="pw-setup-copy" role="status">{originalRepairChanged}</p>}
    {review ? <section className="pw-setup-install-review" aria-label="确认工具准备">
      <h4>{review.review.title || '准备这些开发工具'}</h4><p>{review.review.summary}</p>
      <p className="pw-setup-download">{review.review.downloadBytes == null ? '下载大小尚未确认' : `基础下载约 ${setupBytes(review.review.downloadBytes)}`}{review.review.additionalDownloads ? '，安装过程中还会下载依赖。' : '。'}</p>
      {review.review.minimumFreeBytes != null && <p>开始前至少需要 {setupBytes(review.review.minimumFreeBytes)} 可用空间。</p>}
      <p>首次准备需要联网下载工具与系统依赖。网络中断后不会自动重试；返回本页可查看同一次准备和原日志。</p>
      {review.review.existingGenerationRetained && <p>已有工具版本和项目文件保留；修复会核对当前工具，必要时准备新版本，不清空原环境。</p>}
      <Accordion><AccordionItem id="setup-install-effects" title="查看安装内容"><pre>{setupDetails(reviewDetails)}</pre></AccordionItem></Accordion>
      <div className="pw-setup-actions"><Button variant="ghost" disabled={!!working} onClick={preparation.dismissReview}>{repairVisit?'暂不修复':'暂不安装'}</Button>
        <Button disabled={locked || !can('setup.runtime.start')} onClick={() => void preparation.start()}>{working === 'setup.runtime.start' ? '正在开始…' : repairVisit?'开始修复':'开始准备'}</Button></div>
    </section> : <div className="pw-setup-actions">
      {view && (view.canPrepare || view.canRetry) && !running && (!repairVisit || repairCurrent) && <Button variant={projectReview?'ghost':undefined}
        disabled={locked || needsRead || !can('setup.runtime.review')} onClick={() => void preparation.reviewPreparation()}>
        {working === 'setup.runtime.review' ? '正在读取安装内容…' : repairVisit || view.ready ? '检查与修复工具' : view.canRetry ? '重新准备' : '准备开发工具'}
      </Button>}
      {view?.canCancel && <Button variant="secondary" disabled={locked || !can('setup.runtime.cancel')} onClick={() => void preparation.cancel()}>{view.status === 'cancel_requested' ? '已请求停止' : '停止准备'}</Button>}
    </div>}
    {(originalError || log || view && ['failed', 'blocked', 'interrupted'].includes(view.status)) && <Accordion className="pw-setup-details">
      <AccordionItem id="setup-runtime-details" title="运行详情与日志" description={originalError?.code || undefined}>
        {operation?.phase && <p>原始阶段：{operation.phase}</p>}
        {typeof failedPhase === 'string' && failedPhase && <p>失败发生在：{runtimePhaseLabel(failedPhase)}（{failedPhase}）</p>}
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
export function SetupWorkspace({ snapshot, mode = 'center', initialCapability, initialView, runtimeTaskId, returnLabel, originGoal, onNavigate, onViewChange, onClose, onReviewOriginalVite, originalPython, registerBackHandler }: SetupWorkspaceProps) {
  const [data, setData] = useState<SetupRead | null>(null), [view, setView] = useState<SetupView>(initialCapability || initialView || (mode === 'onboarding' ? 'model' : 'overview'));
  const [loading, setLoading] = useState(false), [working, setWorking] = useState('');
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [needsRead, setNeedsRead] = useState(false);
  const [entered, setEntered] = useState(false);
  const alive = useRef(false), generation = useRef(0), pending = useRef(false), reading = useRef(false), entering = useRef(false);
  const returnRead = useRef(false), readModelRevision = useRef(snapshot.state.modelSelectionRevision);
  const latest = useRef({ data, snapshot, onNavigate, onClose, entered }); latest.current = { data, snapshot, onNavigate, onClose, entered };
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  // The native setup surface has one operation slot. A system-return refresh and
  // runtime polling share it; only still-visible, original-page requests dispatch.
  const run: SetupCommand = (action, args = {}, stillCurrent) => {
    const ticket = generation.current, epoch = bridge.getDocumentEpoch();
    const request = queue.current.then(() => {
      if (!alive.current || generation.current !== ticket || bridge.getDocumentEpoch() !== epoch || !latest.current.entered || document.hidden) throw new Error('准备页面已变化，返回后请重新读取状态。');
      if (stillCurrent && !stillCurrent()) throw new Error(originalRepairChanged);
      return bridge.command(action, args);
    });
    queue.current = request.then(() => undefined, () => undefined);
    return request;
  };
  const readCurrentSetup = async () => {
    const selected = latest.current.snapshot.state.modelSelectionRevision;
    const result = await run('setup.read');
    if (selected !== latest.current.snapshot.state.modelSelectionRevision) {
      returnRead.current = true;
      throw new Error('模型选择已变化，正在重新读取。');
    }
    return { value: result, selected };
  };
  const heading = useRef<HTMLHeadingElement>(null), stepBody = useRef<HTMLDivElement>(null), stage = useRef<HTMLDivElement>(null), back = useRef<() => boolean>(() => false);
  const can = (action: string) => snapshot.capabilities.commands.includes(action);
  const locked = !entered || loading || !!working || needsRead;
  const modelSelected = !!data?.model.configured && data.model.availability !== 'unavailable';
  const textTesting = working === 'setup.model.test' || !!data?.model.testing;
  const modelStateCurrent = !needsRead && snapshot.state.modelSelectionBusy !== true && readModelRevision.current === snapshot.state.modelSelectionRevision &&
    (!modelSelected || (data?.model.selectionRevision !== undefined && data.model.selectionRevision === snapshot.state.modelSelectionRevision));
  const textVerified = modelStateCurrent && modelSelected && !!data?.model.tested && !textTesting;
  const focused = !!initialCapability, onboarding = mode === 'onboarding';
  const resumeRevision = setupObject(snapshot.view).resumeRevision;
  const pythonEpoch=originalPython?bridge.getDocumentEpoch():undefined;
  const preparation = useRuntimePreparation(entered && !!data && (view === 'tools' || view === 'overview'), can, resumeRevision, run, runtimeTaskId, pythonEpoch, originalPython?.repair);
  const readyRuntime = preparation.view;
  const runtime = setupObject(readyRuntime?.runtime), tools = Array.isArray(runtime.tools)?runtime.tools.map(setupObject):[];
  const python=tools.filter(tool=>tool.name==='python3'), preparationReceipt=setupObject(readyRuntime?.operation?.receipt);
  const pythonReadyEpoch=pythonEpoch!==undefined && preparation.readEpoch===pythonEpoch && focused && !onboarding && view==='tools' &&
    !locked && !preparation.reading && !preparation.working && !preparation.review && !preparation.needsRead &&
    readyRuntime?.status==='ready' && readyRuntime.ready===true && !readyRuntime.operation?.error &&
    (!readyRuntime.operation || preparationReceipt.status==='completed' && preparationReceipt.receiptReceived===true &&
      preparationReceipt.resourcesReleased===true && preparationReceipt.ownedWorkerLive===false &&
      preparationReceipt.preparedEnvironmentIdentity===runtime.identity) &&
    runtime.runtimeId==='managed_process_v1' && runtime.canExecute===true &&
    typeof runtime.identity==='string' && /^[a-f0-9]{64}$/.test(runtime.identity) &&
    python.length===1 && python[0].installed===true && (python[0].probeVerified===true || python[0].hashVerified===true)
    ? pythonEpoch:undefined;
  const viteReady = !!onReviewOriginalVite && focused && !onboarding && view==='tools' &&
    !locked && !preparation.reading && !preparation.working && !preparation.review && !preparation.needsRead &&
    readyRuntime?.status==='ready' && readyRuntime.ready===true && !readyRuntime.operation?.error &&
    runtime?.runtimeId==='managed_process_v1' && runtime.canExecute===true && typeof runtime.identity==='string' &&
    /^[a-f0-9]{64}$/.test(runtime.identity) && ['node','npm'].every(name=>{
      const matches=tools.filter(tool=>tool.name===name), tool=matches[0];
      return matches.length===1&&tool.installed===true&&(tool.probeVerified===true||tool.hashVerified===true);
    }) ? runtime.identity : '';
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
  const load = async (): Promise<void> => {
    if (!alive.current || pending.current || reading.current || !latest.current.snapshot.capabilities.commands.includes('setup.read')) return;
    if (!latest.current.entered) { await enter(); return; }
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); reading.current = true; returnRead.current = false; setLoading(true);
    try {
      const result = await readCurrentSetup(), value = readSetup(result.value);
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return;
      if (!returnRead.current) { readModelRevision.current = result.selected; setData(value); setNeedsRead(false); setError(''); }
    } catch (failure) { if (alive.current && ticket === generation.current) { setError(failureText(failure)); setNeedsRead(true); } }
    finally { if (ticket === generation.current) { reading.current = false; if (alive.current) { setLoading(false); if (returnRead.current && !document.hidden) void loadRef.current(); } } }
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
  useLayoutEffect(() => { if (stepBody.current) stepBody.current.scrollTop = 0; heading.current?.focus({ preventScroll: true }); }, [view]);
  useSurfaceEnter(stage, view, { selector: '', active: !!data, depth: focused ? 0 : onboarding ? view === 'model' ? 0 : view === 'overview' ? 1 : 2 : view === 'overview' ? 0 : 1 });
  useEffect(() => { onViewChange?.(view); }, [view, onViewChange]);
  useEffect(() => { registerBackHandler?.(() => back.current()); return () => registerBackHandler?.(null); }, [registerBackHandler]);

  const save = async (next: SetupPreferences): Promise<boolean> => {
    const before = latest.current.data;
    if (!before || pending.current || reading.current || needsRead || !can('setup.save')) return false;
    if (before.preferences.step === next.step && before.preferences.status === next.status && before.preferences.capabilities.join(',') === next.capabilities.join(',')) return true;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); pending.current = true; setWorking('save'); setError(''); setNotice('');
    try {
      await run('setup.save', setupSaveInput(before, next));
      const result = await readCurrentSetup(), actual = setupSaveConfirmed(before, next, result.value);
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return false;
      readModelRevision.current = result.selected; setData(actual); latest.current.data = actual; return true;
    } catch (failure) { if (alive.current && ticket === generation.current) { setError(failureText(failure)); setNeedsRead(true); } return false; }
    finally { if (ticket === generation.current) { pending.current = false; if (alive.current) { setWorking(''); if (returnRead.current && !document.hidden) void loadRef.current(); } } }
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
  const canRecordProgress = !!data && entered && readable && can('setup.save') && !loading && !needsRead;
  const close = async () => {
    if (pending.current || preparation.working) return;
    if (!onboarding) { preparation.dismissReview(); onClose(); return; }
    // A pending read or unconfirmed receipt must not trap the original draft or replay a write.
    if (!data || !canRecordProgress || reading.current) { onClose(); return; }
    if (await save({ ...data.preferences, step: textVerified ? 'finish' : viewStep(view), status: textVerified ? 'completed' : 'skipped' })) onClose();
  };
  const command = async (action: string, args: Record<string, unknown> = {}) => {
    if (!data || pending.current || reading.current || needsRead || !can(action) || document.hidden) return;
    if (action === 'setup.model.test' && (!modelStateCurrent || data.model.testing || latest.current.snapshot.state.modelSelectionBusy === true ||
        args.expectedSelectionRevision !== latest.current.snapshot.state.modelSelectionRevision)) return;
    const ticket = generation.current, epoch = bridge.getDocumentEpoch(); pending.current = true; setWorking(action); setError(''); setNotice('');
    try {
      await run(action, args);
      if (action === 'setup.permission' || action === 'settings.openSystem' && args.section === 'accessibilityAppInfo') {
        if (alive.current && ticket === generation.current) setNotice('返回这里后会重新检查。你也可以随时点刷新。');
        return;
      }
      const result = await readCurrentSetup(), actual = readSetup(result.value);
      if (!alive.current || ticket !== generation.current || bridge.getDocumentEpoch() !== epoch) return;
      readModelRevision.current = result.selected; setData(actual); latest.current.data = actual;
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
      if (onboarding && canRecordProgress && !reading.current) void openView(parentView);
      else { preparation.dismissReview(); setNotice(''); setView(parentView); }
      return true;
    }
    if (onboarding && data) { void close(); return true; }
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
  const textIssue = modelStateCurrent && modelSelected && !textTesting && !textVerified ? data?.model.textTestIssue : undefined;
  const testAvailable = modelSelected && data?.model.selectionRevision !== undefined && can('setup.model.test');
  // Returning to a waiting first task does not require buying a separate probe.
  // A local choice is not a successful text result: keep its real status unchanged.
  const resumeFirstWork = onboarding && !!returnLabel && modelSelected && modelStateCurrent && !textTesting && !textIssue;
  const optionalTest = textVerified || resumeFirstWork;
  const checkSettingsFirst = !optionalTest && (!testAvailable || !!textIssue);
  const manageModel = <Button className={checkSettingsFirst ? 'pw-setup-model-main' : undefined} variant={checkSettingsFirst ? 'primary' : 'ghost'} trailingIcon={IconChevronRight}
    disabled={locked || !!preparation.working} onClick={() => void navigate('connections')}>
    {data?.model.availability === 'unavailable' || textIssue ? '检查模型配置' : modelSelected ? '管理模型连接' : '连接模型'}
  </Button>;
  const testFirst = !optionalTest && !checkSettingsFirst;
  const testButton = <Button className={testFirst ? 'pw-setup-model-main' : undefined} variant={testFirst ? 'primary' : 'secondary'}
    aria-describedby="pw-setup-test-cost" disabled={locked || !modelStateCurrent || textTesting || !testAvailable}
    onClick={() => void command('setup.model.test', { expectedSelectionRevision: data?.model.selectionRevision })}>
    {textTesting ? '正在等待回复…' : textVerified || textIssue ? '重新测试文本连接' : '测试文本连接'}
  </Button>;
  const testCost = <p id="pw-setup-test-cost" className="pw-setup-copy">{textIssue === 'unknown' ? '上次请求可能已产生用量。再次测试会另发一条消息。' : '会向所选服务发一条短消息，可能产生用量。'}这只检查文字回复，不验证工具或图片能力。</p>;
  const modelSection = data && <section className="pw-setup-model" aria-label="模型连接准备">
    <WorkspaceSectionHeading title={data.model.label || '连接模型服务'} description={<span role="status" aria-atomic="true">{
      !modelStateCurrent ? '当前模型状态待核对' : textTesting ? '正在等待这次文字回复' : modelSelected || data.model.availability === 'unavailable' ? setupModelLabel(data.model) : '登录 ChatGPT 账号，或添加兼容的 API 服务。'
    }</span>}/>
    {modelStateCurrent && data.model.message && <p className="pw-setup-copy" role={data.model.availability === 'unavailable' || textIssue === 'failed' ? 'alert' : 'status'}>{data.model.message}</p>}
    {textVerified && <p className="pw-setup-copy">这次只验证了文字回复，没有验证工具或图片能力。</p>}
    {resumeFirstWork && !textVerified && <p className="pw-setup-copy">已选好模型，尚未实测回复。可以直接返回发送你的第一件事。</p>}
    {testAvailable && !optionalTest && testCost}
    {modelSelected && !testAvailable && !textVerified && <p className="pw-setup-copy">当前入口不能测试文本连接，可前往模型设置核对。</p>}
    <div className="pw-setup-model-choices">
      {checkSettingsFirst && manageModel}
      {testAvailable && !optionalTest && testButton}
      {!checkSettingsFirst && manageModel}
    </div>
    {(data.model.details || (optionalTest && testAvailable)) && <Accordion className="pw-setup-model-check">
      {data.model.details && <AccordionItem id="setup-model-details" title="查看状态详情"><pre>{data.model.details}</pre></AccordionItem>}
      {optionalTest && testAvailable && <AccordionItem id="setup-model-check" title={textVerified ? '再次测试' : '单独测试文字回复'}><div className="pw-setup-model-test">{testCost}{testButton}</div></AccordionItem>}
    </Accordion>}
  </section>;
  const capabilityBody = (id: SetupCapability) => {
    if (!data) return null;
    if (id === 'tools') return <>
      {originalPython && <p className="pw-setup-copy">{originalPython.repair?'修复会核对整套手机开发工具。工具就绪后可返回':'准备完成后可返回'} <bdi>{originalPython.path}</bdi>。不会自动运行。</p>}
      <RuntimePreparation preparation={preparation} can={can} blocked={locked} projectReview={!!viteReady || pythonReadyEpoch!==undefined}/>
    </>;
    if (id === 'phone') return <>
      <p className="pw-setup-copy">聊天、查资料和生成文件可以直接在 App 内使用。需要操作手机界面时，再开启下面的权限。</p>
      <section className="pw-setup-purpose-group"><SetupRow title="界面读取与主屏控制" status={permissionLabel(data.permissions.accessibility)} action={permissionAction('accessibility', data.permissions.accessibility)}/><p className="pw-setup-copy">用手机主屏执行时，开启无障碍并保持手机解锁、亮屏。主屏模式免配对。</p>
        {connection.supported && data.permissions.accessibility === false && can('settings.openSystem') &&
          <PhoneAccessibilitySetupHelp readiness={snapshot.state.phoneReadiness} action={<Button variant="secondary" disabled={locked}
            onClick={() => void command('settings.openSystem', { section: 'accessibilityAppInfo' })}>打开口袋世界的应用信息</Button>} />}
      </section>
      <section className="pw-setup-purpose-group"><SetupRow title="系统通知" status={permissionLabel(data.permissions.notifications)} action={permissionAction('notifications', data.permissions.notifications, '允许任务通知')}/><p className="pw-setup-copy">手机工作需要保留通知中的停止入口。请同时检查系统通知和手机任务通知类别，返回后重新核对准备情况。</p></section>
      <section className="pw-setup-purpose-group"><SetupRow title="后台独立屏" status={phoneConnection} action={<Button variant="secondary" disabled={locked} onClick={() => void navigate('background')}>连接与检查</Button>}/><p className="pw-setup-copy">独立屏需要 Android 14 或以上。可在这台手机上完成无线调试配对，或使用已授权的 Shizuku。连接后再检查显示、输入和截图能力。</p></section>
      <Accordion className="pw-setup-purpose-details"><AccordionItem id="setup-calendar" title="向日历添加事项" description={`读取${permissionLabel(data.permissions.calendarRead)} · 写入${permissionLabel(data.permissions.calendarWrite)}`}>
        <SetupRow title="日历访问" status={permissionLabel(data.permissions.calendar)} action={permissionAction('calendar', data.permissions.calendar, '允许日历访问')}/><p className="pw-setup-copy">用于读取当前手机资料中的可写日历，并在你确认准确日历与完整事件后创建。授权本身不会写入；每批最多 50 项，不会访问其他工作资料。</p>
      </AccordionItem></Accordion>
    </>;
    if (id === 'voice') return <>
      <section className="pw-setup-purpose-group"><SetupRow title="系统语音服务" status={data.speech.available === true ? '检测到可用服务' : data.speech.available === false ? '未检测到可用服务' : '待检查'}/><p className="pw-setup-copy">{speechNetworkLabel(data.speech.networkUse)}。</p></section>
      <section className="pw-setup-purpose-group"><SetupRow title="麦克风" status={permissionLabel(data.permissions.microphone)} action={permissionAction('microphone', data.permissions.microphone, '允许麦克风')}/><p className="pw-setup-copy">回到对话后点语音按钮，核对听写文字再采用。这里使用系统语音服务。</p></section>
    </>;
    return <>
      <section className="pw-setup-purpose-group"><SetupRow title="系统通知" status={permissionLabel(data.permissions.notifications)} action={permissionAction('notifications', data.permissions.notifications, '允许通知')}/><p className="pw-setup-copy">任务需要处理或有新结果时，通过通知提醒你。手机工作还需开启手机任务通知类别，以便随时停止。</p></section>
      <section className="pw-setup-purpose-group"><SetupRow title="电池优化" status={data.permissions.batteryExempt === true ? '已允许不受电池优化限制' : data.permissions.batteryExempt === false ? '仍受系统电池优化限制' : '待检查'} action={permissionAction('battery', data.permissions.batteryExempt)}/><SetupRow title="厂商后台设置" status={data.permissions.vendorBackgroundVisited === true ? '已打开设置 · 请以系统实际选择为准' : '按这台手机的设置检查'} action={permissionAction('vendorBackground', null)}/><p className="pw-setup-copy">系统或厂商仍可能暂停后台工作。打开过设置不代表已经允许。</p></section>
    </>;
  };
  const title = view === 'overview' ? '能力准备' : view === 'model' ? onboarding ? '开始使用' : '模型连接' : capabilityCopy[view].title;
  const returning = returnLabel || (onboarding ? '进入管家' : '返回原页面');
  const skipLabel = `${modelSelected ? '暂不验证' : '稍后设置'}，${returning}`;
  const leaveLabel = onboarding && canRecordProgress && !optionalTest ? skipLabel : returning;
  const parentLabel = parentView === 'model' ? '返回模型连接' : '返回能力准备';
  const headerAction = () => { if (!back.current()) void close(); };
  return <section className="pw-setup-workspace" aria-label={title} data-setup-step={viewStep(view)} data-setup-view={view} data-setup-mode={mode} data-setup-focused={focused}>
    <header className="pw-setup-header">
      <Button variant="ghost" iconOnly leadingIcon={hasParent || focused ? IconArrowLeft : IconClose} className="pw-setup-close" aria-label={hasParent ? parentLabel : leaveLabel} title={hasParent ? parentLabel : leaveLabel} disabled={!!working || !!preparation.working} onClick={headerAction}/>
      <h1 ref={heading} tabIndex={-1}>{title}</h1>
      {data && <Button variant="ghost" iconOnly leadingIcon={IconRefresh} className="pw-setup-refresh" aria-label="刷新准备状态" title="刷新准备状态" disabled={loading || !!working || !readable} onClick={() => void load()}/>}
    </header>
    {!data ? <div className="pw-setup-loading"><h2>{!readable ? '准备入口暂不可用' : error ? '暂时无法读取准备状态' : '正在读取当前状态'}</h2><p>{focused ? '只检查这次需要的能力。' : '准备进度与权限以这台手机的实际状态为准。'}</p>{error && <p role="alert" className="pw-setup-error">{error}</p>}<Button variant="secondary" disabled={loading || !readable} onClick={() => void load()}>重新读取</Button></div> : <>
      <div className="pw-setup-body" ref={stepBody}>
        {originGoal?.trim() && <div className="pw-setup-origin"><span>当前要做的事</span><p>{originGoal}</p></div>}
        <div className="pw-setup-stage" ref={stage}>
          {view === 'overview' && <><div className="pw-setup-intro"><h2>准备想用的能力</h2><p>选择要做的事，查看对应状态和下一步。</p></div><div className="pw-setup-purpose-list">
            <Button variant="ghost" className="pw-setup-purpose-row" onClick={() => void openView('model')} disabled={locked || !!preparation.working}><IconModel size={24} aria-hidden="true"/><span><strong>对话与推理</strong><small>{modelStateCurrent ? setupModelLabel(data.model) : '当前模型状态待核对'}</small></span><IconChevronRight size={18} aria-hidden="true"/></Button>
            {setupCapabilities.map(id => { const copy = capabilityCopy[id], Icon = copy.icon; return <Button variant="ghost" key={id} className="pw-setup-purpose-row" onClick={() => void openView(id)} disabled={locked || !!preparation.working}><Icon size={24} aria-hidden="true"/><span><strong>{copy.title}</strong><small>{capabilitySummary(id)}</small></span><IconChevronRight size={18} aria-hidden="true"/></Button>; })}
          </div><p className="pw-setup-next-hint">准备只在你选择后进行。已开始的工具准备保留在原任务中，可随时查看或停止。</p></>}
          {view === 'model' && <>{modelSection}{onboarding && <div className="pw-setup-optional"><Button variant="ghost" trailingIcon={IconChevronRight} disabled={locked || !!preparation.working} onClick={() => void openView('overview')}>查看手机、开发与语音能力</Button></div>}</>}
          {view !== 'overview' && view !== 'model' && <div className="pw-setup-purpose-detail"><p className="pw-setup-purpose-description">{capabilityCopy[view].description}</p>{capabilityBody(view)}</div>}
        </div>
        {(error || needsRead || notice) && <div className="pw-setup-feedback">{error && <p role="alert" className="pw-setup-error">{error}</p>}{needsRead && readable && <Button variant="secondary" disabled={loading || !!working} onClick={() => void load()}>刷新并核对</Button>}{notice && <p role="status" className="pw-setup-notice">{notice}</p>}</div>}
      </div>
      <footer className="pw-setup-footer">
        <Button className="pw-setup-next" variant={viteReady||pythonReadyEpoch!==undefined?'primary':!onboarding?'secondary':canRecordProgress&&optionalTest?'primary':'ghost'}
          disabled={!!working || !!preparation.working} onClick={() => {
            if(pythonReadyEpoch!==undefined&&originalPython){
              if(!document.hidden&&bridge.getDocumentEpoch()===pythonReadyEpoch)originalPython.onReturn(pythonReadyEpoch);
              else void preparation.read();
            }else if(viteReady&&onReviewOriginalVite)onReviewOriginalVite(viteReady);
            else void close();
          }}>{pythonReadyEpoch!==undefined?'返回原脚本':viteReady?'返回原项目核对网页构建':working==='save'?'正在保存…':leaveLabel}</Button>
      </footer>
    </>}
  </section>;
}
