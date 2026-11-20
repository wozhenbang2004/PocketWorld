import type {ProjectDraftReturn} from './ProjectConventionDraftAction';
import type {LocalTerminalCheckout} from './LocalWorkspace';
import { checkedHostFiles, checkedHostText, ProjectHostComparedFiles, ProjectHostDiff, type HostComparedFile, type HostComparison, type HostDiff, type HostEditorFile } from './ProjectHostFileComparison';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, TextAreaField } from './forms-library';
import {ProjectTaskPackageOrigin} from './ProjectTaskPackageOrigin';
import { bridge } from './bridge';
import { ProjectCheckoutFiles } from './ProjectCheckoutFiles';
import { CheckoutGuide, checkoutKindSummary } from './ProjectWorkBinding';
import './local-workspace.css';
import './project-development.css';
import './project-workspace-manga.css';

type Data = Record<string, any>;
type DeliveryRequirements = { constraints: string; deliverable: string; completionCriteria: string };
type DevelopmentDraft = DeliveryRequirements & { goal: string; checkoutId: string };
const deliveryFields = ['constraints', 'deliverable', 'completionCriteria'] as const;
const deliveryRequirements = (value: Data = {}): DeliveryRequirements => ({
  constraints: typeof value.constraints === 'string' ? value.constraints : '',
  deliverable: typeof value.deliverable === 'string' ? value.deliverable : '',
  completionCriteria: typeof value.completionCriteria === 'string' ? value.completionCriteria : '',
});
const sameDraft = (draft: DevelopmentDraft, original: Data) => draft.goal === original.goal && draft.checkoutId === original.checkoutId &&
  deliveryFields.every(key => draft[key] === (original[key] || ''));
export type ProjectBuildCopy = { taskId:string; runId:string; epoch:number; projectId:string; checkoutId:string; sourceDigest:string };
type Props = { returnTarget?:ProjectDraftReturn; onDismissReturn?:()=>void;
  buildReturn?:ProjectBuildCopy; onBuildReturnHandled?:(target:ProjectBuildCopy)=>void; projectId: string; name: string; active: boolean; run: (action: string, args?: Data) => Promise<any>; can: (action: string) => boolean;
  onOpenTask: (id: string, checkoutId?: string) => void; onOpenHostOriginal?: (id: string) => void; onTaskRegistered: () => void; onPrepareTools: () => void; onConnectModel: () => void; onOpenTerminal: (checkout?: LocalTerminalCheckout) => void;
  registerBack: (handler: (() => boolean) | null) => void; onInteractionBusyChange?: (busy: boolean) => void };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const unresolved = (value: Data | null) => !!value && ['pending', 'unknown', 'unconfirmed'].includes(value.status);
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const scopedBuildReceipt = (receipt: Data) => receipt.independentNodeBuild === true || !!(receipt.versionedBuildSourceDigest || receipt.editedBuildSourceDigest);
function existingBuildBlocked(value: unknown): string {
  switch(value){
    case 'workspaces': return '这个副本使用工作区，本机快捷构建目前只支持单个 Node 项目。';
    case 'npmrc': return '这个副本有 .npmrc，本机快捷构建不会沿用该配置。';
    case 'manager_configuration': return '这个副本有当前快捷构建不支持的包管理器配置。';
    case 'manifest_configuration': return 'package.json 有当前快捷构建不支持的项目级配置。';
    default: return '';
  }
}
function versionBuildFor(source: Data | null, project: string, checkout: string): Data | null {
  const existing = source?.existingNodeBuild === true;
  const build = existing ? source?.existingNodeBuildTask : source?.editedDraftBuild;
  return source && (existing || source.editedDraftCopy === true) && /^[a-f0-9]{64}$/.test(source.sourceDigest) &&
    (existing ? build?.sourceDigest : build?.editedSourceDigest) === source.sourceDigest && build?.projectId === project && build?.checkoutId === checkout &&
    typeof build?.id === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(build.id) &&
    typeof build.runId === 'string' && !!build.runId && Number.isSafeInteger(build.epoch) && build.epoch > 0 ? build : null;
}

/** Read-only preparation evidence, never a claim that the selected phone can produce an APK. */
function AndroidBuildConditions({ wrapper, runtime }: { wrapper: Data; runtime: Data | undefined }) {
  const observed = new Set(rows(runtime?.tools).filter(tool => tool.probeVerified === true || tool.hashVerified === true).map(tool => tool.name));
  const missing = ['java', 'javac', 'gradle'].filter(name => !observed.has(name));
  let pin = '声明的摘要不在本次 Gradle 8.9 对照范围。';
  if (wrapper.state === 'invalid_properties' || wrapper.state === 'unreadable_properties') pin = '副本中的 Wrapper 配置无法读取。';
  else if (wrapper.state === 'properties_too_large') pin = 'Wrapper 配置超过本次读取范围，未解析其声明。';
  else if (wrapper.distributionPinState === 'not_declared') pin = '这个副本没有声明分发包 SHA-256。';
  else if (wrapper.distributionPinState === 'invalid_format') pin = '分发包 SHA-256 格式无效。';
  else if (wrapper.distributionPinState === 'conflicts_with_official_declaration') pin = '地址声明的版本或包型与已知官方摘要不一致。';
  else if (wrapper.distributionPinState === 'matches_published_reference') pin = `项目声明与 Gradle 8.9 ${wrapper.pinReferenceKind === 'all' ? '完整版' : '二进制版'}官方 SHA-256 相同。`;
  return <dl className="pw-facts" aria-label="Android 构建准备">
    <div><dt>项目内 Wrapper JAR</dt><dd>{wrapper.wrapperJarVerified === true ? '副本内文件的字节摘要与 Gradle 8.9 官方 JAR 一致。' : wrapper.wrapperJarState === 'not_in_snapshot' ? '这个副本没有标准路径的 Wrapper JAR。' : '文件存在，未命中 Gradle 8.9 基准；不代表文件损坏。'}</dd></div>
    <div><dt>分发包声明</dt><dd>{pin}{wrapper.distribution === 'unrecognized_or_dynamic_url_not_exposed' && ' 下载地址未核验。'}</dd></div>
    <div><dt>本机 Gradle 分发包</dt><dd>这次没有读取或下载 ZIP，不能据此认定已安装。</dd></div>
    <div><dt>手机编译工具</dt><dd>{missing.length ? `所选环境未观察到已登记的 ${missing.join('、')}。` : '已观察到 Java 和 Gradle 工具名，版本兼容性未核验。'} Android SDK 与 ARM64 构建工具也未核验。</dd></div>
  </dl>;
}

/** Existing project checkout + original Assistant admission. No command is
 * approved here, and reopening never repeats a submission or copy creation. */
export function ProjectDevelopmentWorkspace({ returnTarget, onDismissReturn, buildReturn, onBuildReturnHandled, projectId, name, active, run, can, onOpenTask, onOpenHostOriginal, onTaskRegistered, onPrepareTools, onConnectModel, onOpenTerminal, registerBack, onInteractionBusyChange }: Props) {
  const [view, setView] = useState<Data | null>(null), [goal, setGoal] = useState(''), [checkout, setCheckout] = useState('');
  const returned=useRef(''),focusReturned=useRef(false),noticeArea=useRef<HTMLDivElement>(null);
  const [deliveryOpen,setDeliveryOpen]=useState(false);
  const [hostOrigin, setHostOrigin] = useState<Data | null>(null);
  const [hostFiles, setHostFiles] = useState<HostComparison | null>(null), [hostDiff, setHostDiff] = useState<HostDiff | null>(null);
  const [hostEditor, setHostEditor] = useState<HostEditorFile | null>(null);
  const visibleHostDiff = useRef<HostDiff | null>(null); visibleHostDiff.current = hostDiff;
  const closeHostDiff = () => { visibleHostDiff.current = null; setHostDiff(null); };
  const [requirements, setRequirements] = useState<DeliveryRequirements>(() => deliveryRequirements());
  const [source, setSource] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [submission, setSubmission] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [expired, setExpired] = useState(false), [copyResult, setCopyResult] = useState<Data | null>(null);
  const [fileCheckout,setFileCheckout]=useState(''),[buildOrigin,setBuildOrigin]=useState<ProjectBuildCopy|null>(null);
  const buildCheckout=buildOrigin?.checkoutId||'';
  const [buildCopyMissing,setBuildCopyMissing]=useState(false);
  const filesOpen=!!fileCheckout, filesBack = useRef<(() => boolean) | null>(null);
  const registerFilesBack = useCallback((handler: (() => boolean) | null) => { filesBack.current = handler; }, []);
  const alive = useRef(false), generation = useRef(0), entered = useRef<object | null>(null), handle = useRef(''), activeReview = useRef<Data | null>(null);
  const keptNativePick=useRef(false);const [sourceConflict,setSourceConflict]=useState(false);
  const sourceAttempt = useRef(''), readAttempt = useRef(''), touched = useRef(false), draft = useRef<DevelopmentDraft>({ goal: '', checkoutId: '', ...deliveryRequirements() });
  const latest = useRef({ run, can, active }); latest.current = { run, can, active }; activeReview.current = review;
  const clearSubmittedDraft = (receipt: Data) => {
    if (receipt.status !== 'accepted' || scopedBuildReceipt(receipt) || receipt.draftSourceNeedsReview===true || !sameDraft(draft.current, receipt)) return;
    keptNativePick.current=false;draft.current = { ...draft.current, goal: '', ...deliveryRequirements() }; setGoal(''); setRequirements(deliveryRequirements());
  };
  const applySubmission = (receipt: Data, expected: Data) => {
    if (receipt.projectId !== projectId || receipt.clientMessageId !== expected.clientMessageId ||
        receipt.checkoutId !== expected.checkoutId || expected.independentNodeBuild === true && receipt.independentNodeBuild !== true ||
        expected.versionedBuildSourceDigest && receipt.versionedBuildSourceDigest !== expected.versionedBuildSourceDigest ||
        !['accepted', 'rejected', 'unknown'].includes(receipt.status))
      throw new Error('启动回执和原交办不一致，输入已保留；请查询原输入。');
    if (receipt.status === 'accepted' && (typeof receipt.taskId !== 'string' || !receipt.taskId))
      throw new Error('原工作身份尚未返回，请查询原输入。');
    setSubmission(receipt); clearSubmittedDraft(receipt);
    if (scopedBuildReceipt(receipt) && receipt.status !== 'unknown') { sourceAttempt.current = ''; setSource(null); }
    if (receipt.status === 'accepted' && (submission?.status !== 'accepted' || submission.taskId !== receipt.taskId)) onTaskRegistered();
  };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void) => {
    const ownsProject = () => bridge.getState().state.projectId === projectId && !bridge.getState().state.filePath;
    if (!latest.current.active || entered.current || document.hidden || !ownsProject() || !latest.current.can(action)) return;
    const token = {}, window = ownerWindow(), sequence = generation.current;
    entered.current = token; setBusy(true); setError('');
    const current = () => alive.current && latest.current.active && entered.current === token && generation.current === sequence && ownerWindow() === window && ownsProject() && !document.hidden;
    try {
      const value = await latest.current.run(action, args);
      if (current()) { accept?.(value); return value as Data; }
      if (action === 'projects.development.read' && value?.viewHandle) void latest.current.run('projects.development.close', { viewHandle: value.viewHandle }).catch(() => {});
    }
    catch (failure) { if (current()) setError(failureText(failure)); }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const read = () => {
    if (latest.current.active && !entered.current && !document.hidden && latest.current.can('projects.development.read')) readAttempt.current = ownerWindow();
    const pinned=returnTarget&&returned.current!==returnTarget.handle?returnTarget:null;
    return invoke('projects.development.read', {projectId,...(pinned?{draftReturnHandle:pinned.handle}:{})}, value => {
    if (value.projectId !== projectId || typeof value.viewHandle !== 'string') throw new Error('没有取得这个原项目的页面，请重新读取。');
    if(pinned){
      if(value.openedDraftReturn!==pinned.handle||value.draft?.projectId!==pinned.projectId||value.draft?.checkoutId!==pinned.checkoutId||
          value.draft?.ownerPickedConvention?.checkoutId!==pinned.checkoutId||!Number.isSafeInteger(value.draft?.ownerPickedConvention?.revision)){
        void latest.current.run('projects.development.close',{viewHandle:value.viewHandle}).catch(()=>{});throw new Error('原项目草稿的定位没有得到确认，没有显示其他草稿。');
      }
      returned.current=pinned.handle;focusReturned.current=true;setDeliveryOpen(true);
    }
    handle.current = value.viewHandle; setView(value); sourceAttempt.current = ''; setSource(null);
    const incoming = value.draft || {}, here = draft.current;
    const selectedCopy = incoming.ownerPickedConvention && (!here.constraints || keptNativePick.current) && (!here.checkoutId || here.checkoutId===incoming.checkoutId) &&
      here.goal === (incoming.goal||'') && here.deliverable === (incoming.deliverable||'') && here.completionCriteria === (incoming.completionCriteria||'');
    setSourceConflict(false);
    if (!touched.current || selectedCopy) {
      const retained = incoming, available = rows(value.checkouts);
      const selected = retained.checkoutId || (available.length === 1 ? available[0].checkoutId : '');
      draft.current = { goal: retained.goal || '', checkoutId: selected, ...deliveryRequirements(retained) };
      keptNativePick.current=!!incoming.ownerPickedConvention;
      setGoal(draft.current.goal); setCheckout(selected); setRequirements(deliveryRequirements(retained));
    } else if(keptNativePick.current && here.checkoutId===incoming.checkoutId){
      // The owner only edited other fields. Refresh the picked field without dropping those local edits.
      const constraints=deliveryRequirements(incoming).constraints;draft.current={...here,constraints};
      setRequirements(deliveryRequirements(draft.current));keptNativePick.current=!!incoming.ownerPickedConvention;
    } else if(incoming.ownerPickedConvention){
      // Equal text is not identity: neither a changed source nor an unsaved local paragraph wins silently.
      setSourceConflict(true);
    }
    setSubmission(value.draft?.submission || null);
    if (value.draft?.submission) clearSubmittedDraft(value.draft.submission);
    });
  };
  const readRef = useRef(read); readRef.current = read;
  const retain = (next: DevelopmentDraft) => {
    if(next.constraints!==draft.current.constraints||next.checkoutId!==draft.current.checkoutId)keptNativePick.current=false;
    touched.current = true; draft.current = next;
    const original = handle.current, sequence = generation.current;
    if (original) void latest.current.run('projects.development.draft', { viewHandle: original, ...next }).catch(failure => {
      if (alive.current && sequence === generation.current && handle.current === original) setError(`草稿未保存：${failureText(failure)}`);
    });
  };
  const acceptReview = (value: Data, draftBuild = false) => {
    const lock = value.lockedDependencies;
    if (lock && (!draftBuild || lock.manager !== value.manager || lock.registry !== 'https://registry.npmjs.org/' ||
      lock.lockPath !== (lock.manager==='npm'?'package-lock.json':'pnpm-lock.yaml') ||
      typeof lock.managerVersion!=='string' || !/^[a-f0-9]{64}$/.test(lock.lockSha256) ||
      !['current_packages_checked','needs_lock_review'].includes(value.dependencyState) ||
      !Number.isSafeInteger(lock.packages) || lock.packages<1)) throw new Error('没有取得这份副本的确切锁文件审阅，未开始。');
    const hasProjectDraft = !!draft.current.goal || deliveryFields.some(k => !!draft.current[k]);
    if (draftBuild && (value.draftBuild !== true || !['npm','pnpm'].includes(value.manager) || typeof value.scripts?.build!=='string' || !value.scripts.build.trim() ||
        hasProjectDraft && !(value.existingCopy === true && value.preservedProjectDraft === true)))
      throw new Error('没有核对到独立构建或原交办的保留状态，未替换目标。');
    if (!draftBuild && value.draftBuild === true) throw new Error('请在独立副本中重新核对构建。');
    if (draftBuild && (value.editedCopy !== (source?.editedDraftCopy === true) || value.existingCopy !== (source?.existingNodeBuild === true)))
      throw new Error('构建不属于当前看到的副本版本，请重新读取。');
    if (value.projectId !== projectId || value.checkoutId !== draft.current.checkoutId ||
        value.source?.projectId !== projectId || value.source?.checkoutId !== value.checkoutId || value.runtimeId != null && value.runtimeId !== 'managed_process_v1' ||
        typeof value.goal !== 'string' || !value.goal.trim() || !draftBuild && value.goal.trim() !== draft.current.goal.trim() ||
        !value.requestId || !value.reviewHandle || value.commandsAuthorized !== false || value.projectMemoryAuthorized !== false ||
        value.ownerPickedConventionRevision!==undefined&&(!Number.isSafeInteger(value.ownerPickedConventionRevision)||value.ownerPickedConventionRevision<1))
      throw new Error('审阅不属于这个项目或所选副本，没有开始工作。请重新读取项目条件。');
    if (!source?.sourceDigest || value.source.sourceDigest !== source.sourceDigest) {
      sourceAttempt.current = ''; setSource(null);
      throw new Error('副本内容已变化，还没有开始工作。请核对更新后的版本。');
    }
    if (value.deliveryContract?.format !== 'pocketworld.owner-work-contract.v1' || value.deliveryContract.revision !== 1 || value.deliveryContract.goal !== value.goal)
      throw new Error('项目交付约定没有完整载入，请重新核对。');
    if (draftBuild) { setReview(value); return; }
    draft.current = { goal: value.goal, checkoutId: value.checkoutId, ...deliveryRequirements(value.deliveryContract) };
    setGoal(draft.current.goal); setRequirements(deliveryRequirements(value.deliveryContract)); setReview(value);
  };
  const dismiss = () => {
    if (entered.current) return;
    const original = activeReview.current; activeReview.current = null; setReview(null);
    if (original && handle.current) void latest.current.run('projects.development.dismiss', { viewHandle: handle.current, reviewHandle: original.reviewHandle }).catch(() => {});
  };
  useEffect(() => {
    registerBack(() => { if (!latest.current.active) return false; if (visibleHostDiff.current) { closeHostDiff(); return true; } if (filesBack.current?.()) return true; if (entered.current) return true; if (activeReview.current) { dismiss(); return true; } return false; });
    return () => registerBack(null);
  }, [registerBack]);
  useEffect(() => {
    alive.current = true; let owner = ownerWindow();
    const retire = () => {
      generation.current++; entered.current = null;
      const original = handle.current; handle.current = ''; sourceAttempt.current = ''; readAttempt.current = ''; activeReview.current = null;
      if (alive.current) { closeHostDiff(); setHostEditor(null); setHostFiles(null); setHostOrigin(null); setView(null); setReview(null); setSource(null); setBusy(false); setFileCheckout(''); }
      if (original) void latest.current.run('projects.development.close', { viewHandle: original }).catch(() => {});
    };
    // Switching the start method retires its window, never its plain draft or
    // the identity of a submitted operation. Returning performs a fresh read.
    if (!active) { retire(); return () => { alive.current = false; }; }
    const inspect = () => {
      const next = ownerWindow();
      if (next !== owner || document.hidden) { owner = next; retire(); }
      if (latest.current.active && !document.hidden && !handle.current && !entered.current && readAttempt.current !== next && latest.current.can('projects.development.read')) void readRef.current();
    };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    void readRef.current();
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, [projectId, active]);
  const picked = view?.draft?.ownerPickedConvention;
  const currentPicked = !sourceConflict && keptNativePick.current && picked && Number.isSafeInteger(picked.revision) && picked.revision>0 && picked.checkoutId===checkout &&
    requirements.constraints && view?.draft?.constraints===requirements.constraints ? picked : null;
  useEffect(()=>{if(!active||!view||!focusReturned.current||!currentPicked||document.hidden)return;
    focusReturned.current=false;noticeArea.current?.focus({preventScroll:true});noticeArea.current?.scrollIntoView({block:'nearest',behavior:'auto'});
  },[active,view,currentPicked]);
  const removePicked = () => { setRequirements(value=>({...value,constraints:''})); retain({...draft.current,constraints:''});
    setView(value=>value ? {...value,draft:{...value.draft,ownerPickedConvention:null}} : value); };
  const readable = can('projects.development.read');
  const available = rows(view?.checkouts);
  const differentBuildCopy=buildCheckout&&checkout!==buildCheckout&&available.find(row=>row.projectId===projectId&&row.checkoutId===buildCheckout);
  useEffect(() => { if (active && readable && !handle.current) void readRef.current(); }, [active, readable]);
  useEffect(() => {
    if (!active || !view || !checkout || busy || review || filesOpen || buildReturn || differentBuildCopy || unresolved(submission)) return;
    const key = `${view.viewHandle}|${projectId}|${checkout}`;
    if (sourceAttempt.current === key) return;
    sourceAttempt.current = key; setSource(null);
    void invoke('projects.development.inspect', { viewHandle: view.viewHandle, checkoutId: checkout }, value => {
      if (draft.current.checkoutId !== checkout) return;
      if (value.projectId !== projectId || value.checkoutId !== checkout) throw new Error('源码不属于这个所选副本，请重新读取。');
      setSource(value);
    });
  }, [active, view, checkout, busy, review, filesOpen, buildReturn, differentBuildCopy, submission, projectId]);
  useEffect(() => {
    setExpired(false); if (!review) return;
    const remaining = Number(review.expiresAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) { setExpired(true); return; }
    const timer = window.setTimeout(() => setExpired(true), remaining); return () => window.clearTimeout(timer);
  }, [review]);
  const pending = unresolved(submission), disabled = !active || busy || pending || sourceConflict || !!review || filesOpen || !view || view.draftRecoveryRequired === true;
  const displayedSavedDraft = (displayed:DevelopmentDraft) => !touched.current && !view?.draft?.checkoutId
    ? { ...displayed, checkoutId: '' } : displayed; // A sole default copy is not a saved selection.
  const verifyDeparture = (displayed:DevelopmentDraft, expected:DevelopmentDraft, open:()=>void) =>
    invoke('projects.development.draft', { viewHandle: handle.current, ...expected, verifySavedForTools: true }, result => {
      if (result.projectId !== projectId || result.checkoutId !== expected.checkoutId ||
          result.draftVerifiedForTools !== true || result.taskStarted !== false || !sameDraft(draft.current, displayed))
        throw new Error('没有核对到本页同一份草稿，文字仍留在这里。');
      open();
    });
  const prepareTools = () => {
    if (disabled || !can('setup.runtime.read')) return;
    const displayed={...draft.current};
    void verifyDeparture(displayed,displayedSavedDraft(displayed),onPrepareTools);
  };
  const canPrepareTerminal = can('projects.development.draft') && can('connections.enter') &&
    can('local.describeCheckout') && can('local.terminal.review') && available.some(copy=>copy.projectId===projectId&&copy.checkoutId===checkout);
  const prepareTerminal = async () => {
    if(disabled||entered.current||!canPrepareTerminal||draft.current.checkoutId!==checkout)return;
    const displayed={...draft.current};
    // An explicit request for the currently displayed sole copy saves that
    // same choice, so Back doesn't guess again if the list has since changed.
    if(!displayedSavedDraft(displayed).checkoutId){
      const saved=await invoke('projects.development.draft',{viewHandle:handle.current,...displayed},value=>{
        if(value.saved!==true||value.projectId!==projectId||!sameDraft(draft.current,displayed))
          throw new Error('没有保存原副本的选择，未打开终端准备。');
        touched.current=true;
      });
      if(!saved)return;
    }
    await verifyDeparture(displayed,displayed,()=>onOpenTerminal({projectId,checkoutId:displayed.checkoutId}));
  };
  const reconcileSource=(useProject:boolean)=>{
    if(!sourceConflict||!active||busy||pending||filesOpen||!view||!view.draft?.ownerPickedConvention||document.hidden)return;
    const remote=view.draft,here=draft.current,scope=String(remote.checkoutId||'');
    if(!scope||here.checkoutId!==scope){setError('这两份输入属于不同副本，没有替换。请复制需保留的本页文字后，退出并打开原项目副本。');return;}
    if(useProject){
      const next={...here,constraints:deliveryRequirements(remote).constraints};draft.current=next;
      setRequirements(deliveryRequirements(next));keptNativePick.current=true;setSourceConflict(false);return;
    }
    void invoke('projects.development.draft',{viewHandle:handle.current,...here,dropSelectedSource:true},value=>{
      if(value.projectId!==projectId||value.saved!==true||value.removedSelectedSource!==true)throw new Error('原项目草稿需要重新核对。');
      keptNativePick.current=false;setSourceConflict(false);setView(v=>v?{...v,draft:{...v.draft,ownerPickedConvention:null,constraints:here.constraints}}:v);
    });
  };
  const copyUnconfirmed = copyResult?.unconfirmed === true || copyResult?.found === false && copyResult?.partial !== true
    || !copyResult && !!view?.draft?.copyRequestId && view?.draft?.copyStatus !== 'ready';
  const interactionBusy = active && (busy || pending || !!review || filesOpen || copyUnconfirmed);
  useEffect(() => { onInteractionBusyChange?.(interactionBusy); return () => onInteractionBusyChange?.(false); }, [interactionBusy, onInteractionBusyChange]);
  const closeFiles = () => { setFileCheckout(''); setHostEditor(null); setHostFiles(null); sourceAttempt.current = ''; setSource(null); };
  useEffect(()=>{
    if(!buildReturn||!active||!view||busy||review||fileCheckout||document.hidden||
       handle.current!==view.viewHandle||!latest.current.can('projects.development.files.open'))return;
    const live=bridge.getState(), nativeView=live.view as Data|undefined;
    if(live.state.projectId!==projectId||live.state.filePath||nativeView?.taskId||nativeView?.section!=null)return;
    onBuildReturnHandled?.(buildReturn);
    setBuildOrigin(null);setBuildCopyMissing(false);
    if(buildReturn.projectId!==projectId||view.projectId!==projectId||view.executionAuthorized!==false||
       !rows(view.checkouts).some(copy=>copy.projectId===projectId&&copy.checkoutId===buildReturn.checkoutId)){
      setBuildCopyMissing(true);return;
    }
    // The clicked task chooses only the editor. The current submission draft
    // and any other checkout selection are intentionally left unchanged.
    sourceAttempt.current='';setSource(null);setHostEditor(null);setBuildOrigin(buildReturn);setFileCheckout(buildReturn.checkoutId);
  },[buildReturn,onBuildReturnHandled,active,view,busy,review,fileCheckout,projectId]);
  const unfinishedInput=!!(goal||view?.draft?.goal||view?.draft?.ownerPickedConvention||deliveryFields.some(k=>requirements[k]||view?.draft?.[k]));
  const selectBuildCopy=()=>{
    if(!differentBuildCopy||unfinishedInput||disabled||!can('projects.development.draft'))return;
    const next={...draft.current,checkoutId:buildCheckout};
    void invoke('projects.development.draft',{viewHandle:handle.current,...next},value=>{
      if(value.saved!==true||value.projectId!==projectId)throw Error('原副本选择未确认，未开始工作。');
      touched.current=true;draft.current=next;setCheckout(next.checkoutId);
      setHostOrigin(null);setHostFiles(null);sourceAttempt.current='';setSource(null);
    });
  };
  const origin = hostOrigin?.viewHandle === view?.viewHandle && hostOrigin?.projectId === projectId && hostOrigin?.checkoutId === checkout ? hostOrigin : null;
  const readOrigin = (open: boolean) => {
    if (!checkout || !handle.current || open && (!origin?.taskVerified || !onOpenHostOriginal)) return;
    const viewHandle = handle.current, original = checkout;
    if (!open) { setHostOrigin(null); setHostFiles(null); }
    void invoke(open ? 'projects.development.resolveHostSource' : 'projects.development.hostSource',
      { viewHandle, checkoutId: original, ...(open ? { evidenceDigest: origin!.evidenceDigest } : {}) }, value => {
        if (draft.current.checkoutId !== original || handle.current !== viewHandle || value.projectId !== projectId || value.checkoutId !== original)
          throw new Error('这不是当前副本的原主机来源，请重新核对。');
        if (open) {
          if (value.taskVerified !== true || !origin || value.evidenceDigest !== origin.evidenceDigest || value.taskId !== origin.taskId || typeof value.taskId !== 'string' || !value.taskId)
            throw new Error('未取得同一原委托，没有打开其他任务。');
          onOpenHostOriginal!(value.taskId);
        } else setHostOrigin({ ...value, viewHandle });
      });
  };
  const compareHostFiles = () => {
    if (!origin?.localSourceRecorded || !checkout || !handle.current) return;
    const { evidenceDigest, snapshotVersion, files } = origin, viewHandle = handle.current, original = checkout;
    setHostFiles(null);
    void invoke('projects.development.compareHostSource', { viewHandle, checkoutId: original, evidenceDigest }, value => {
      if (handle.current !== viewHandle || draft.current.checkoutId !== original || value.projectId !== projectId || value.checkoutId !== original ||
          value.evidenceDigest !== evidenceDigest || value.snapshotVersion !== snapshotVersion || !Number.isSafeInteger(value.observedAt) || value.observedAt <= 0)
        throw new Error('副本或导入时的来源已变化，请回到原副本重新核对。');
      setHostFiles({ viewHandle, evidenceDigest, observedAt: value.observedAt, files: checkedHostFiles(value.files, files) });
    });
  };
  const readHostDiff = (file: HostComparedFile) => {
    if (!origin?.taskVerified || !hostFiles || file.state !== 'changed' || !hostFiles.files.includes(file)) return;
    const original = checkout, { evidenceDigest, snapshotVersion } = origin, viewHandle = handle.current;
    if (hostFiles.evidenceDigest !== evidenceDigest || hostFiles.viewHandle !== viewHandle) return;
    closeHostDiff();
    void invoke('projects.development.readHostDiff', { viewHandle, checkoutId: original, evidenceDigest, path: file.path,
        originalSha256: file.originalSha256, phoneSha256: file.phoneSha256 }, value => {
      if (handle.current !== viewHandle || draft.current.checkoutId !== original || value.projectId !== projectId || value.checkoutId !== original ||
          value.evidenceDigest !== evidenceDigest || value.snapshotVersion !== snapshotVersion) throw new Error('原项目或冻结版本已变，没有展示其他文件。');
      setHostDiff({ ...checkedHostText(value, file), path: file.path, originalSha256: file.originalSha256, phoneSha256: file.phoneSha256!, snapshotVersion, evidenceDigest, viewHandle });
    });
  };
  const copy = (query: boolean) => {
    if (!query) setCopyResult({ unconfirmed: true });
    return invoke(query ? 'projects.development.queryCopy' : 'projects.development.prepare', { viewHandle: handle.current }, value => {
    if (value.projectId !== projectId) throw new Error('副本准备回执不属于当前项目。');
    setCopyResult(value);
    setView(previous => previous ? { ...previous, draft: { ...previous.draft, copyRequestId: value.requestId },
      checkouts: value.found && value.checkout ? [...rows(previous.checkouts).filter(row => row.checkoutId !== value.checkoutId), { ...value.checkout, checkoutId: value.checkoutId }] : previous.checkouts } : previous);
    if (value.found) { setCheckout(value.checkoutId); retain({ ...draft.current, checkoutId: value.checkoutId }); sourceAttempt.current = ''; }
    });
  };
  const start = () => {
    const selected = activeReview.current;
    if (!active || !selected || busy || expired || Number(selected.expiresAt) <= Date.now()) return;
    if (document.hidden || selected.projectId !== bridge.getState().state.projectId || bridge.getState().state.filePath) {
      dismiss(); setError('项目页面已切换，没有开始工作。'); return;
    }
    const marker = { projectId, checkoutId: selected.checkoutId, clientMessageId: selected.requestId, goal: selected.goal,
      ...deliveryRequirements(selected.deliveryContract), status: 'unknown', message: '正在核对原输入回执。请勿重复开始。',
      ...(selected.draftBuild === true ? { independentNodeBuild: true } : {}),
      ...(selected.editedCopy === true || selected.existingCopy === true ? { versionedBuildSourceDigest: selected.source.sourceDigest } : {}) };
    setSubmission(marker); setReview(null); activeReview.current = null;
    void invoke('projects.development.start', { viewHandle: handle.current, reviewHandle: selected.reviewHandle }, receipt => applySubmission(receipt, marker));
  };
  const androidWrapper = source?.projectId === projectId && source.checkoutId === checkout && source.androidGradleWrapper?.sourceDigest === source.sourceDigest ? source.androidGradleWrapper : null;
  const archiveRoot = source?.projectId === projectId && source.checkoutId === checkout &&
    /^[a-f0-9]{64}$/.test(source.sourceDigest) ? source.retainedArchiveRoot : null;
  const versionBuild = versionBuildFor(source, projectId, checkout);
  const nodeBlocked = source?.projectId===projectId && source?.checkoutId===checkout ? existingBuildBlocked(source.existingNodeBuildBlocked) : '';
  const buildLookupUnavailable = source?.existingNodeBuild === true ?
    source.existingNodeBuildUnavailable === true || source.existingNodeBuildTask && !versionBuild :
    source?.editedDraftCopy === true && (source.editedDraftBuildUnavailable === true || source.editedDraftBuild && !versionBuild);
  const comparedBuild = buildOrigin && buildOrigin.projectId===projectId && buildOrigin.checkoutId===checkout &&
    source?.projectId===projectId && source.checkoutId===checkout && typeof source.sourceDigest==='string' &&
    /^[a-f0-9]{64}$/.test(source.sourceDigest) ? buildOrigin : null;
  const versionChanged = comparedBuild && comparedBuild.sourceDigest!==source?.sourceDigest;
  const reviewNodeBuild = () => void invoke('projects.development.reviewDraftBuild',{
    viewHandle:handle.current,checkoutId:checkout,retainedDraft:displayedSavedDraft({...draft.current}),
  },v=>acceptReview(v,true));
  const openVersionBuild = () => void invoke('projects.development.inspect', {viewHandle:handle.current, checkoutId:checkout}, fresh => {
    const current = versionBuildFor(fresh, projectId, checkout);
    if (fresh.projectId === projectId && fresh.checkoutId === checkout) setSource(fresh);
    if (!versionBuild || !current || fresh.sourceDigest !== source?.sourceDigest || fresh.existingNodeBuild !== source?.existingNodeBuild ||
        current.id !== versionBuild.id || current.runId !== versionBuild.runId || current.epoch !== versionBuild.epoch)
      throw new Error('副本版本或原任务已变化。没有打开另一版，请按更新后的记录选择。');
    onOpenTask(current.id, checkout);
  });
  if(returnTarget&&returned.current!==returnTarget.handle)return <section hidden={!active} className="pw-project-development pw-settings-section" aria-label="核对原项目草稿">
    <p role="status">{error||'正在核对刚才写入的原项目草稿…'}</p>
    {error&&<Button variant="ghost" onClick={onDismissReturn}>收起定位，返回项目概览</Button>}
  </section>;
  return <section hidden={!active} className="pw-project-development pw-local-workspace pw-settings-section" aria-label="使用默认模型开发项目">
    <header className="pw-development-intro"><h3>让模型接手开发</h3><p>在私人副本中工作，结果由你审阅后导回项目。</p></header>
    {error && <p className="pw-local-inline-notice" role="alert">{error}</p>}
    {buildCopyMissing&&<p className="pw-local-inline-notice" role="status">原构建所用的私人副本暂不可用，没有打开替代副本。</p>}
    {!view ? <Button variant="secondary" disabled={busy || !readable} onClick={() => void read()}> {busy ? '正在读取原项目…' : '读取项目开发条件'} </Button> : <>
      {view.draftRecoveryRequired && <p className="pw-local-inline-notice" role="alert">{view.draftRecoveryMessage} 仍可查看下方原任务、文件和工具状态。请保留应用数据，从诊断记录定位草稿存储问题后恢复，不要清空数据或重复提交目标。</p>}
      {submission && <div className="pw-local-inline-notice" role="status"><p>{submission.message || (pending ? '原项目输入仍待核对。' : submission.status === 'accepted' ? '项目任务已保存。' : '上次输入没有启动。')}</p>
        {submission.status === 'accepted' && !scopedBuildReceipt(submission) && <p>继续同一件事时，请打开原任务补充，不必重新开始。</p>}
        {submission.error && <p>{submission.error}</p>}<div className="pw-form-actions">
          {submission.taskId && submission.taskId!==versionBuild?.id && <Button variant="secondary" disabled={busy || filesOpen || !can('tasks.open')} data-project-receipt-task={submission.taskId} onClick={() => onOpenTask(submission.taskId, submission.checkoutId)}>{scopedBuildReceipt(submission)?'查看上次交办的构建':'查看原任务与结果'}</Button>}
          {pending && <Button variant="secondary" disabled={busy || filesOpen} onClick={() => void invoke('projects.development.query', { viewHandle: handle.current }, receipt => applySubmission(receipt, submission))}>查询原输入</Button>}
        </div></div>}
      {sourceConflict&&view.draft?.ownerPickedConvention&&<div className="pw-local-inline-notice"><p>原草稿现在有另一次选择，本页还留着你的输入。两份都没有被自动覆盖。</p>
        {draft.current.checkoutId===view.draft.checkoutId&&<details><summary>对照本副本的两份限制</summary><p>本页普通文字</p><p dir="auto" className="pw-reading-copy">{draft.current.constraints||'（本页为空）'}</p><p>项目当前所选约定 · 修订 {view.draft.ownerPickedConvention.revision}</p><p dir="auto" className="pw-reading-copy">{view.draft.constraints}</p></details>}
        {draft.current.checkoutId===view.draft.checkoutId?<><Button variant="secondary" size="small" disabled={busy||pending} onClick={()=>reconcileSource(true)}>在本页使用项目当前约定</Button><Button variant="ghost" size="small" disabled={busy||pending} onClick={()=>reconcileSource(false)}>用本页普通文字替换草稿约定</Button></>:<p>工作副本不同，不展示另一份正文，也不能互相覆盖。请保留本页文字后，退出并打开原项目副本。</p>}</div>}
      <TextAreaField label="这次的目标" value={goal} isDisabled={disabled} onChange={value => { if (value.length <= 12000) { setGoal(value); retain({ ...draft.current, goal: value }); } }} hint="例如：做一个能在手机预览的番茄钟，先用本地数据。不要填写密码或令牌。" />
      {currentPicked && <div ref={noticeArea} tabIndex={-1} className="pw-local-inline-notice"><p>已从本项目手写约定修订 {currentPicked.revision} 放入本次草稿，尚不是模型采用的证据。</p>
        <Button variant="ghost" size="small" disabled={disabled} onClick={removePicked}>移除本次约定</Button></div>}
      <Accordion expandedKeys={deliveryOpen?['development-delivery']:[]} onExpandedChange={keys=>setDeliveryOpen(keys.has('development-delivery'))}><AccordionItem id="development-delivery" title="交付要求" description={deliveryFields.some(key => requirements[key].trim()) ? '已补充约束、交付物或完成标准' : '选填：约束、交付物与完成标准'}>
        <TextAreaField label="限制与约束" value={requirements.constraints} isDisabled={disabled} hint="例如：保留已有数据，沿用当前组件，每项最多 4000 字。"
          onChange={value => { if (value.length <= 4000) {
            if(currentPicked && value!==requirements.constraints)setView(previous=>previous?{...previous,draft:{...previous.draft,ownerPickedConvention:null}}:previous);
            setRequirements(previous => ({ ...previous, constraints: value })); retain({ ...draft.current, constraints: value }); } }} />
        <TextAreaField label="要交付什么" value={requirements.deliverable} isDisabled={disabled} hint="例如：可预览的页面和对应项目固定包。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, deliverable: value })); retain({ ...draft.current, deliverable: value }); } }} />
        <TextAreaField label="怎样算完成" value={requirements.completionCriteria} isDisabled={disabled} hint="写清你要看到的实际结果；发送或发布仍需原来的确认。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, completionCriteria: value })); retain({ ...draft.current, completionCriteria: value }); } }} />
      </AccordionItem></Accordion>
      <SelectField label="要工作的私人副本" value={checkout} isDisabled={disabled} options={[{ id: '', label: available.length ? '选择已有私人副本' : '尚未准备私人副本' },
        ...available.map(row => ({ id: row.checkoutId, label: `${row.name || name} · ${new Date(row.createdAt || 0).toLocaleString()}` })),
        ...(checkout && !available.some(row => row.checkoutId === checkout) ? [{ id: checkout, label: '原副本暂不可用，请核对' }] : [])]}
        onChange={value => { const clear = !!currentPicked && checkout!==value; if(clear){setRequirements(last=>({...last,constraints:''}));setView(last=>last?{...last,draft:{...last.draft,ownerPickedConvention:null}}:last);}
          setCheckout(value); setBuildOrigin(null); setBuildCopyMissing(false); setSource(null); setHostOrigin(null); setHostFiles(null); sourceAttempt.current = ''; retain({ ...draft.current, checkoutId: value, ...(clear?{constraints:''}:{}) }); }} />
      {!filesOpen&&archiveRoot&&<div className="pw-local-inline-notice" role="status">
        <p>工程配置在 <bdi>{archiveRoot.descriptor}</bdi>，不在副本顶层。你仍可编辑这份副本。</p>
        <p>若要按工程根重新导入，请选择 <bdi>{archiveRoot.directory}/</bdi> 文件夹。原普通 ZIP 若只有一层外目录，也可选「去掉普通 ZIP 唯一外层目录」。重新导入另建项目，不带入本副本的修改。</p>
        {can('projects.localSource.open')&&<Button variant="secondary" size="small" disabled={disabled}
          onClick={()=>void invoke('projects.localSource.open')}>打开手机导入</Button>}
      </div>}
      {available.some(row => row.checkoutId === checkout && row.hostSource === true) && can('projects.development.hostSource') &&
        <div className="pw-form-actions"><Button variant="ghost" disabled={busy || !!review || filesOpen} onClick={() => readOrigin(false)}>核对导入来源</Button>
          {origin && <div role="status">{origin.localSourceRecorded ? <p>来自主机冻结版本 {origin.snapshotVersion} · {origin.files} 份文件 · {origin.bytes} 字节。这里只记录导入时的版本，不代表本机文件未改或主机现在在线。</p> :
            <p>保留了这份旧副本，但缺少可核对的原来源；不会按项目名猜测委托。</p>}
            {origin.localSourceRecorded && can('projects.development.compareHostSource') && <Button variant="secondary" disabled={busy || !!review || filesOpen} onClick={compareHostFiles}>对照本机已保存版本</Button>}
            {hostFiles && origin.evidenceDigest === hostFiles.evidenceDigest && view.viewHandle === hostFiles.viewHandle && <ProjectHostComparedFiles value={hostFiles} busy={busy || !!review || filesOpen}
              onRead={origin.taskVerified && can('projects.development.readHostDiff') ? readHostDiff : undefined}/>}
            {origin.taskVerified && onOpenHostOriginal && can('projects.development.resolveHostSource') && can('tasks.open') ?
              <Button variant="secondary" disabled={busy || !!review || filesOpen} onClick={() => readOrigin(true)}>打开原主机委托</Button> :
              origin.localSourceRecorded && !origin.taskVerified ? <p>原私人任务或冻结文件回执目前不能核对。</p> : null}</div>}</div>}
      {view?.viewHandle && available.some(row=>row.checkoutId===checkout && row.taskPackageSource===true) &&
        <ProjectTaskPackageOrigin key={view.viewHandle+':'+checkout} project={projectId} checkout={checkout} view={view.viewHandle}
          disabled={busy||!!review||filesOpen} invoke={invoke} can={can} open={onOpenHostOriginal}/>}
      {available.length === 0 && <div className="pw-form-actions"><Button variant="secondary" disabled={disabled || copyResult?.partial === true} onClick={() => void copy(false)}>准备这个项目的私人副本</Button></div>}
      {(view.draft?.copyRequestId || copyResult) && <div><Button variant="ghost" disabled={busy || !!review || filesOpen} onClick={() => void copy(true)}>查询原副本准备</Button>
        {copyResult?.partial && <div role="status"><p>原副本准备中断，部分文件已保留，不会覆盖。可在手动终端显式准备另一份完整副本，再返回选择；已有部分不会自动删除。</p><Button variant="secondary" disabled={busy || filesOpen} onClick={()=>onOpenTerminal()}>打开副本准备入口</Button></div>}
        {copyResult && !copyResult.found && !copyResult.partial && <p role="status">未发现这次准备的副本；显式准备仍使用同一请求，不会换一个身份重建。</p>}</div>}
      {!filesOpen&&differentBuildCopy&&<div className="pw-local-inline-notice" role="status">
        <p>这次构建使用的是 {differentBuildCopy.name||'私人副本'} · {buildCheckout.slice(0,8)}。{unfinishedInput||pending?'另一份交办仍保留，没有切换。':'可以选这份副本，再审阅下一次构建。'}</p>
        <div className="pw-form-actions">
          {!unfinishedInput&&!pending&&can('projects.development.draft')&&<Button variant="secondary" size="small" disabled={disabled} onClick={selectBuildCopy}>用这份副本继续</Button>}
          <Button variant="ghost" size="small" disabled={busy||!!review} onClick={()=>setBuildOrigin(null)}>返回当前交办</Button>
        </div>
      </div>}
      {!filesOpen && comparedBuild && <p className="pw-field-hint" role="status">
        {versionChanged ? <>当前已保存源码 <code>{source?.sourceDigest.slice(0,8)}</code> 不同于原构建 <code>{comparedBuild.sourceDigest.slice(0,8)}</code>。旧批准不会用于新版本。</>
          : '当前已保存源码仍是原构建使用的版本。'}
      </p>}
      {nodeBlocked && can('projects.development.reviewDraftBuild') && <p className="pw-field-hint" role="status">{nodeBlocked} 你仍可浏览和编辑这个副本。</p>}
      {!differentBuildCopy&&available.some(row => row.checkoutId === checkout && (row.draftWorkingCopy === true || source?.existingNodeBuild === true)) && can('projects.development.reviewDraftBuild') &&
        (versionBuild ? <Button variant="secondary" disabled={disabled || !can('tasks.open') || !can('projects.development.inspect')}
          onClick={openVersionBuild}>查看这一版构建与结果</Button> : buildLookupUnavailable ?
          <p role="status" className="pw-field-hint">这一版的构建记录暂未核对，请重新读取项目条件，不重复交办。</p> :
          <Button variant="secondary" disabled={disabled || source?.existingNodeBuild!==true && (!!goal || deliveryFields.some(k=>!!requirements[k])) || !source || view.model?.configured !== true}
            onClick={reviewNodeBuild}>{source?.existingNodeBuild===true?(versionChanged?'核对修改后的 Node 构建':'审阅这个副本的 Node 构建'):source?.editedDraftCopy===true?'审阅当前编辑版的 Node 构建':'验证这份改稿的 Node 构建'}</Button>)}
      <div className="pw-form-actions"><Button disabled={disabled || !goal.trim() || !checkout || !source || view.model?.configured !== true} onClick={() => void invoke('projects.development.review', { viewHandle: handle.current, checkoutId: checkout, goal, ...requirements }, acceptReview)}>核对并开始</Button>
        <Button variant="ghost" disabled={busy || pending || !!review || filesOpen} onClick={() => void read()}>重新读取项目条件</Button>
        {checkout && !source && <Button variant="ghost" disabled={disabled} onClick={() => { sourceAttempt.current = ''; setView(previous => previous ? { ...previous } : previous); }}>重新读取副本版本</Button>}
      </div>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !!review || filesOpen || !checkout || !can('projects.development.files.open')} onClick={() => { setHostEditor(null); setFileCheckout(checkout); }}>编辑这份私人副本的文件</Button>
        {canPrepareTerminal && <Button variant="ghost" disabled={disabled} data-workspace-entry="project-copy-terminal"
          onClick={()=>void prepareTerminal()}>准备这份副本的终端</Button>}</div>
      {view.model?.configured !== true && <p className="pw-local-inline-notice">让模型接手时，才需要连接默认模型。项目与已保存草稿保留。<Button variant="secondary" disabled={busy || pending || !!review || filesOpen} onClick={onConnectModel}>连接模型</Button></p>}
      <Accordion><AccordionItem id="development-conditions" title="运行位置与开发条件" description={androidWrapper ? '检测到 Android 配置；本机 APK 构建条件未核验' : checkoutKindSummary(source, projectId, checkout) || '可先编辑文件；运行命令前核对工具'}>
        <p>本次只绑定这台手机与所选副本，不会自动切换到远程设备。Linux 工具不是聊天引擎，不需要为本机开发开启手机操控权限。</p>
        {androidWrapper ? <AndroidBuildConditions wrapper={androidWrapper} runtime={view.runtime} /> : <p>安装依赖、构建或启动仍须核对具体方案；不会因为发现项目类型就运行脚本。</p>}
        <p>模型调用需要相应网络；已有文件和副本不会因离线而删除。退出页面不等于停止任务，重开请查看原任务。</p>
        {can('setup.runtime.read')&&can('projects.development.draft')&&<Button variant="secondary" disabled={disabled}
          data-workspace-entry="project-default-tools" onClick={prepareTools}>{androidWrapper ? '查看手机基础工具（不安装 SDK）' : '查看工具准备'}</Button>}
        {source && <dl className="pw-facts"><div><dt>原项目</dt><dd>{projectId}</dd></div><div><dt>私人副本</dt><dd>{checkout}</dd></div><div><dt>已读取版本</dt><dd>{source.sourceDigest}</dd></div><CheckoutGuide source={source} projectId={projectId} checkoutId={checkout}/></dl>}
      </AccordionItem></Accordion>
    </>}
    {active && !document.hidden && hostDiff && origin && hostDiff.viewHandle === view?.viewHandle && hostDiff.evidenceDigest === origin.evidenceDigest && <ProjectHostDiff diff={hostDiff} close={closeHostDiff} edit={can('projects.development.files.openHostChange') ? () => {
      if (visibleHostDiff.current !== hostDiff || handle.current !== hostDiff.viewHandle || document.hidden) return;
      const { path, evidenceDigest, originalSha256, phoneSha256 } = hostDiff; setHostEditor({ path, evidenceDigest, originalSha256, phoneSha256 });
      closeHostDiff(); setFileCheckout(checkout);
    } : undefined}/>}
    {active && view && fileCheckout && <ProjectCheckoutFiles key={`${view.viewHandle}|${fileCheckout}`} projectId={projectId} checkoutId={fileCheckout} viewHandle={view.viewHandle} run={run} hostFile={hostEditor || undefined} onClose={closeFiles} registerBack={registerFilesBack} />}
    <SettingsDialog isOpen={!!review} title={review?.draftBuild===true?review.dependencyState==='needs_lock_review'?'核对这版的依赖准备':review.existingCopy?'核对本机 Node 构建':review.editedCopy?'核对当前编辑版的构建':'核对这份改稿的本机构建':'开始这个项目任务？'} onClose={dismiss} closeLabel="返回修改目标">{review && <div className="pw-local-review">
      {review.draftBuild === true ? <><strong>{review.dependencyState==='needs_lock_review'?'还缺依赖，先审阅这份锁':review.manager+' run build'}</strong>
        {review.existingCopy && <p className="pw-field-hint">只核对这个私人副本已保存的源码，不含未保存的文件修改。{review.preservedProjectDraft===true && '原项目目标与交付约定留在草稿，不随这次构建提交。'}</p>}
        {versionChanged && comparedBuild && <p className="pw-field-hint">原构建源码 <code>{comparedBuild.sourceDigest.slice(0,8)}</code>。本次核对下方的新版本，需要重新批准。</p>}
        {review.editedCopy && <p className="pw-field-hint">本次只构建这个副本当前保存的源码。原稿、旧构建和已保留的成品不变；旧输出不算本次新成果。</p>}
        {review.lockedDependencies && <><p>{review.dependencyState==='current_packages_checked'?
          '已核对这份相同副本中实际存在的关键包，不需要重复准备。构建是否成功仍以本次实际结果为准。':
          '本机还缺所需包。下一步只审阅原锁与下载，不会开始构建。'}
          {' '}{review.lockedDependencies.lockPath} 共 {review.lockedDependencies.packages} 个锁条目（含其他平台）。准备时只向 npm 官方注册表请求，不读取用户包管理配置或执行安装脚本。</p><details><summary>核对锁与本机工具</summary><dl className="pw-facts"><div><dt>工具</dt><dd>{review.manager} {review.lockedDependencies.managerVersion}</dd></div><div><dt>下载来源</dt><dd>{review.lockedDependencies.registry}</dd></div><div><dt>锁文件 SHA-256</dt><dd>{review.lockedDependencies.lockSha256}</dd></div></dl></details></>}
        {review.previousVerification?.id && <p className="pw-field-hint">此副本已有一项不含锁准备的原构建。审阅这把锁会另开一项工作，旧任务和授权都保留。
          {can('tasks.open')&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>onOpenTask(review.previousVerification.id,review.checkoutId)}>查看原构建记录</Button>}</p>}
        <p>下一步仅把目标交给显示的模型，{review.dependencyState==='needs_lock_review'?'缺失的依赖与之后的构建分别确认。':'构建仍要另行确认。'} 构建脚本可以更改此副本或联网。模型可见项目版本及之后明确获取的日志和成果记录；保留输出仍须另行确认。</p>
        <details><summary>查看这一版 package.json 的构建脚本</summary><dl className="pw-facts">{['prebuild','build','postbuild'].filter(k=>typeof review.scripts?.[k]==='string').map(k=><div key={k}><dt>{k}</dt><dd><code dir="ltr" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{review.scripts[k]}</code></dd></div>)}</dl></details></> : <p>{review.goal}</p>}
      {deliveryFields.some(key => review.deliveryContract?.[key]) && <dl className="pw-facts">
        {review.deliveryContract.constraints && <div><dt>{review.ownerPickedConventionRevision?'所选手写约定 · 修订 '+review.ownerPickedConventionRevision:'限制与约束'}</dt><dd>{review.deliveryContract.constraints}</dd></div>}
        {review.deliveryContract.deliverable && <div><dt>要交付什么</dt><dd>{review.deliveryContract.deliverable}</dd></div>}
        {review.deliveryContract.completionCriteria && <div><dt>怎样算完成</dt><dd>{review.deliveryContract.completionCriteria}</dd></div>}
      </dl>}
      <dl className="pw-facts"><div><dt>项目</dt><dd>{name}</dd></div><div><dt>私人副本</dt><dd>{review.checkoutId}</dd></div><div><dt>默认模型</dt><dd>{review.model?.label} · {review.model?.provider}</dd></div><div><dt>运行位置</dt><dd>{review.runtimeId === 'managed_process_v1' ? '这台手机 · 本机托管环境' : review.runtime}</dd></div><div><dt>源码版本</dt><dd>{review.source?.sourceDigest}</dd></div><CheckoutGuide source={review.source} projectId={projectId} checkoutId={review.checkoutId}/></dl>
      {review.draftBuild!==true&&<p>将目标交给默认模型，在新的项目任务中处理。不会批准任意命令、安装依赖、读取项目记忆或自动发布。具体文件修改、命令与成果采纳仍按原任务范围审阅。</p>}
      {expired && <p role="status">本次审阅已过期，还未启动。请返回重新核对。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={dismiss}>返回修改</Button><Button disabled={busy || expired} onClick={start}>{review?.draftBuild===true?review.dependencyState==='needs_lock_review'?'继续审阅依赖':'继续审阅构建':'开始这个项目任务'}</Button></div>
    </div>}</SettingsDialog>
  </section>;
}
