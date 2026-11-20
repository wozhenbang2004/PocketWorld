import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, RadioOptions, SelectField } from './forms-library';
import { Drawer, PermissionReview } from './shell-library';
import { IconArrowLeft, IconChat, IconChevronRight, IconClose, IconFolder, IconMonitor, IconRefresh } from './icons';
import { WorkContract } from './OwnerWorkFacts';
import {PhoneScopeReview} from './PhoneScopeReview';
import {ButlerPhoneReceipt, reviewedPhone, type ProposalPhoneRun} from './ButlerPhoneJourney';
import {phoneReportInSource, type PhoneReportTarget} from './PhoneReturnedReport';
import {pinKey,revisionChoice,type RevisionChoice} from './OwnerRevisionChoice';
import './butler-preparations.css';

type Data = Record<string, any>;
type PhoneMode = 'background' | 'main_screen';
export type PhonePreparationSetupPin = { digest: string; mode: PhoneMode };
export type ButlerPreparationTarget = { sourceTaskId: string; proposalId: string; sourceConversationId: string;
  fromPhoneSetup?: PhonePreparationSetupPin & { taskView: string; navigation: number; owner: string } };
export type ProjectModelPreparationPin = Pick<ButlerPreparationTarget,'sourceTaskId'|'sourceConversationId'|'proposalId'> & {
  sourceRunId:string; sourceEpoch:number; modelSelectionRevision:number;
};
export type OriginalProjectDraft = { taskId:string; runId:string; epoch:number; draftId:string };
type OriginalTask = { taskId:string; runId:string; epoch:number; status:string };
type ChatBuild = OriginalTask & {ordinal:number; settledBuild?:true; decision?:'dependencies'|'build'|'collect'; approvalId?:string; packageOperationId?:string};
export type ConversationPreparation = ButlerPreparationTarget & {
  sourceRunId:string; sourceEpoch:number; kind:'phone'|'project'; canPrepare:boolean; targetTaskId:string; goal:string;
  originalTask?:OriginalTask; originalDraft?:OriginalProjectDraft; awaitingDraft?:OriginalProjectDraft; interruptedDraft?:OriginalProjectDraft; chatBuild?:ChatBuild; visibleOwnerRequest?:{messageId:string;revision:number}
};
const identifier = (value:unknown): value is string => typeof value==='string' && !!value && value.length<=240;
const positive = (value:unknown): value is number => Number.isSafeInteger(value) && Number(value)>0;
const originalTask = (t:Data, original=true):OriginalTask|undefined=>
  t && identifier(t.taskId) && identifier(t.runId) && positive(t.epoch) && typeof t.status==='string' &&
  (!original || t.canOpenOriginalRun===true && t.originalRunId===t.runId && t.originalEpoch===t.epoch)
    ? {taskId:t.taskId,runId:t.runId,epoch:t.epoch,status:t.status} : undefined;
function originalProjectDraft(row:Data, task?:OriginalTask):OriginalProjectDraft|undefined{
  const draftId=row.target?.originalProjectDraftId;
  return row.kind==='project'&&task?.status==='completed'&&typeof draftId==='string'&&/^[a-f0-9]{64}$/.test(draftId)
    ? {taskId:task.taskId,runId:task.runId,epoch:task.epoch,draftId} : undefined;
}
export const conversationPreparationKey = (row: ButlerPreparationTarget) => JSON.stringify([row.sourceConversationId,row.sourceTaskId,row.proposalId]);
export function conversationPreparation(row: Data): ConversationPreparation | null {
  if (!preparation(row) || !identifier(row.sourceConversationId) || !identifier(row.sourceTaskId) || !identifier(row.proposalId) ||
      !identifier(row.sourceRunId) || !positive(row.sourceEpoch) || row.available !== true) return null;
  const targetTaskId = identifier(row.target?.taskId) ? row.target.taskId : '';
  const canPrepare = row.state === 'proposed' && row.canPrepare === true && !targetTaskId;
  if (!canPrepare && !targetTaskId) return null;
  const task=originalTask(row.target), draft=originalProjectDraft(row,task), build=row.chatOriginalBuild, child=originalTask(build,false);
  const source=row.kind==='project'&&identifier(row.visibleOwnerRequest?.messageId)&&positive(row.visibleOwnerRequest.revision)
    ? {messageId:row.visibleOwnerRequest.messageId,revision:row.visibleOwnerRequest.revision} : undefined;
  const waitingId=row.target?.originalProjectDraftAwaitingAcceptanceId;
  const awaitingDraft:OriginalProjectDraft|undefined=source&&task?.status==='needs_confirmation'&&typeof waitingId==='string'&&/^[a-f0-9]{64}$/.test(waitingId)
    ? {taskId:task.taskId,runId:task.runId,epoch:task.epoch,draftId:waitingId} : undefined;
  const interruptedId=row.target?.originalProjectDraftInterruptedId;
  const interruptedDraft:OriginalProjectDraft|undefined=source&&task?.status==='interrupted'&&task.epoch===1&&
    row.target?.hasUnresolvedOperations===false&&typeof interruptedId==='string'&&/^[a-f0-9]{64}$/.test(interruptedId)
    ? {taskId:task.taskId,runId:task.runId,epoch:task.epoch,draftId:interruptedId} : undefined;
  const chatBuild:ChatBuild|undefined=draft&&child&&child.taskId!==draft.taskId&&build.draftTaskId===draft.taskId&&build.draftId===draft.draftId&&
      build.draftRunId===draft.runId&&build.draftEpoch===draft.epoch&&build.executionAuthorized===false&&
      Number.isInteger(build.ordinal)&&build.ordinal>=0&&build.ordinal<=8
    ? {...child,ordinal:build.ordinal,...(build.settledBuild===true&&child.status==='completed'?{settledBuild:true}:{}),...(['dependencies','build','collect'].includes(build.decision)&&identifier(build.approvalId)?{decision:build.decision,approvalId:build.approvalId}:{}),
      ...(identifier(build.packageOperationId)?{packageOperationId:build.packageOperationId}:{})}:undefined;
  return { sourceTaskId: row.sourceTaskId, proposalId: row.proposalId, sourceConversationId: row.sourceConversationId,
    sourceRunId: row.sourceRunId, sourceEpoch: row.sourceEpoch, kind: row.kind, canPrepare, targetTaskId, goal:text(row.goal),
    originalTask:task, originalDraft:draft, awaitingDraft, interruptedDraft, chatBuild, visibleOwnerRequest:source };
}
export type ConversationNext = {action:'prepare';label:string; hint?:string} |
  {action:'task'|'draft'|'package';label:string; hint?:string; target:OriginalTask; draftId?:string; packageOperationId?:string; review?:boolean; approvalId?:string};
const approvalStates=['waiting_approval','awaiting_approval','waiting_permission'];
const questionStates=['awaiting_user','waiting_user'];
const checkStates=['reconciling','reconciliation_required','unknown'];
const activeBuildStatus=new Map([
  ['queued','原工作已登记，等待执行'],
  ['preparing','正在准备本机执行'],
  ['running','原工作正在执行'],
  ['verifying','正在核对本机结果'],
]);
function taskNext(target:OriginalTask, canReview:boolean, build?:ChatBuild):ConversationNext {
  if(canReview&&approvalStates.includes(target.status)){
    const label=build?.decision==='dependencies'?'审阅依赖安装':build?.decision==='build'?'审阅本机网页构建':
      build?.decision==='collect'?'审阅保存文件':'核对待确认的操作';
    return {action:'task',target,label,review:true,approvalId:build?.approvalId};
  }
  if(canReview&&questionStates.includes(target.status))return {action:'task',target,label:build?'查看原构建需要什么':'查看这次工作需要什么',review:true};
  if(canReview&&target.status==='needs_confirmation')return {action:'task',target,label:'核对这次结果',review:true};
  if(checkStates.includes(target.status))return {action:'task',target,label:'核对原操作状态',review:canReview};
  if(target.status==='failed')return {action:'task',target,label:build?'查看这次构建为何未完成':'查看这次工作为何未完成'};
  return {action:'task',target,label:build?'查看这次构建':'查看这次工作'};
}
export function conversationNext(row:ConversationPreparation, can:(command:string)=>boolean):ConversationNext {
  const task=row.originalTask, build=row.chatBuild;
  if(row.kind==='project'&&task&&can('tasks.open')){
    if(build&&can('butler.preparation.project.followup')){
      // A current decision displaces an already usable result only when the
      // exact next action is known. This is not a live progress dashboard.
      if(build.decision||checkStates.includes(build.status))return taskNext(build,can('tasks.review'),build);
      if(build.packageOperationId&&['projects.package.preview.inspect','projects.package.preview.open'].every(can))
        return {action:'package',target:build,packageOperationId:build.packageOperationId,label:'打开固定网页',hint:build.ordinal?'第 '+build.ordinal+' 稿':undefined};
      if(build.settledBuild||[...approvalStates,...questionStates,'needs_confirmation','failed'].includes(build.status))
        return taskNext(build,can('tasks.review'),build);
      const hint=activeBuildStatus.get(build.status);
      // This is the exact recorded descendant, not the reply or the draft.
      // Keep the read action stable as it moves between these live phases.
      if(hint)return {action:'task',target:build,label:'查看本机构建进展',hint};
    }
    if(row.originalDraft&&can('projectDraft.readDifference'))
      return {action:'draft',target:task,draftId:row.originalDraft.draftId,label:'查看这次改稿'};
    if(row.awaitingDraft&&can('projectDraft.readDifference')&&can('butler.preparation.project.followup'))
      return {action:'draft',target:task,draftId:row.awaitingDraft.draftId,label:'查看这次改稿',hint:'改稿已保存，目标还待验收'};
    if(row.interruptedDraft&&can('projectDraft.readDifference')&&can('butler.preparation.project.followup'))
      return {action:'draft',target:task,draftId:row.interruptedDraft.draftId,label:'查看这次改稿',hint:'执行已中断，原改稿已保存'};
    return taskNext(task,can('tasks.review'));
  }
  return {action:'prepare',label:row.targetTaskId?'核对这次工作':row.kind==='project'?'审阅项目交办':'核对手机工作',
    ...(row.canPrepare?{hint:row.kind==='project'?'选好项目或已存稿后再开始':'管家已拟好，尚未开始'}:{})};
}
export function ConversationPreparationActions({rows,disabled,can,onOpen,onRead}:{
  rows:ConversationPreparation[];disabled:boolean;can:(command:string)=>boolean;
  onOpen:(target:ButlerPreparationTarget,trigger:HTMLButtonElement)=>void;
  onRead:(row:ConversationPreparation,next:Exclude<ConversationNext,{action:'prepare'}>,trigger:HTMLButtonElement)=>void;
}){
  const action=(row:ConversationPreparation, named=false)=>{
    const next=conversationNext(row,can);
    return <Button key={conversationPreparationKey(row)} className="pw-chat-preparation-action" variant="secondary" size="small"
      leadingIcon={row.kind==='phone'?IconMonitor:IconFolder} trailingIcon={IconChevronRight} disabled={disabled}
      onClick={event=>next.action==='prepare'?onOpen(row,event.currentTarget):onRead(row,next,event.currentTarget)}>
      <span><strong>{next.label}</strong>{named&&row.goal?<small><bdi>{row.goal}</bdi>{next.hint&&` · ${next.hint}`}</small>:next.hint?<small>{next.hint}</small>:null}</span>
    </Button>;
  };
  return rows.length===1?<div className="pw-chat-preparation-actions">{action(rows[0])}</div>:
    <Accordion className="pw-chat-preparation-choices" isDisabled={disabled}><AccordionItem title="查看这条消息的交办">
      <div className="pw-chat-preparation-actions">{rows.map(row=>action(row,true))}</div>
    </AccordionItem></Accordion>;
}
type Shared = { can: (action: string) => boolean; run: (action: string, args?: Data) => Promise<any> };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const text = (value: unknown) => typeof value === 'string' ? value : '';
const failureText = (value: unknown) => value instanceof Error ? value.message : String(value);
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const keyOf = (row: Data) => `${row.sourceTaskId}:${row.proposalId}`;
const preparation = (value: Data, target?: ButlerPreparationTarget) => value && typeof value.sourceTaskId === 'string' && typeof value.proposalId === 'string' && typeof value.sourceConversationId === 'string' && ['phone', 'project'].includes(value.kind) && value.executionAuthorized === false && (!target || keyOf(value) === keyOf(target) && value.sourceConversationId === target.sourceConversationId);
export const sameProjectModelRevision = (pin:ProjectModelPreparationPin, state:Data) =>
  state.modelSelectionBusy === false && state.modelSelectionRevision === pin.modelSelectionRevision;
const stateLabels: Record<string, string> = { proposed: '待你核对', prepared: '已建立原任务', started: '已关联执行', dismissed: '已取消提案', unavailable: '原来源不可用' };
const targetStateLabels: Record<string, string> = { queued: '等待执行', preparing: '准备执行', running: '正在执行', verifying: '核对结果中', needs_confirmation: '结果等你核对', awaiting_user: '等你补充', waiting_approval: '等待授权', paused: '已暂停', interrupted: '已中断', completed: '执行已完成', failed: '本次未完成', cancelled: '已停止', stopping: '正在停止' };
const contractOf = (value: Data | null) => value?.contract && typeof value.contract.goal === 'string' && ['constraints', 'deliverable', 'completionCriteria'].every(key => value.contract[key] === undefined || typeof value.contract[key] === 'string') ? value.contract as { goal: string; constraints?: string; deliverable?: string; completionCriteria?: string } : undefined;

/** The retained ConversationWork rows, backed by the durable proposal index. */
export function ButlerPreparationEntries({ conversationId = '', sourceTaskId, representedTaskIds, representedHomeRuns, active, refreshKey, can, run, onOpen, onPresenceChange, onConversationRows, placedProposalKeys }: Shared & {
  conversationId?: string; sourceTaskId?: string; active: boolean; refreshKey: string; onOpen: (target: ButlerPreparationTarget, trigger: HTMLButtonElement) => void;
  representedTaskIds?: ReadonlySet<string>;
  representedHomeRuns?: ReadonlySet<string>;
  onPresenceChange?: (present: boolean) => void;
  onConversationRows?: (conversation: string, rows: ConversationPreparation[]) => void;
  placedProposalKeys?: ReadonlySet<string>;
}) {
  const [entries, setEntries] = useState<Data[]>([]), [cursor, setCursor] = useState(''), [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [expanded, setExpanded] = useState(false);
  const alive = useRef(true), flight = useRef(false), sequence = useRef(0), attempted = useRef(''), latest = useRef({ active, can, run }); latest.current = { active, can, run };
  const load = async (append = false) => {
    if (!latest.current.active || flight.current || !latest.current.can('butler.preparations.list')) return;
    const original = ++sequence.current, window = ownerWindow(); flight.current = true; setBusy(true); setError('');
    try {
      const value = await latest.current.run('butler.preparations.list', { conversationId, cursor: append ? cursor : '', limit: 12 });
      if (!alive.current || original !== sequence.current || ownerWindow() !== window) return;
      if (!Array.isArray(value.entries) || !value.entries.every((row: Data) => preparation(row) && (!conversationId || row.sourceConversationId === conversationId)) || typeof value.hasMore !== 'boolean' || typeof value.nextCursor !== 'string' || value.hasMore && !value.nextCursor) throw new Error('原工作准备列表尚未完整读取。');
      const next = value.entries as Data[];
      setEntries(previous => append ? [...previous, ...next.filter(row => !previous.some(old => keyOf(old) === keyOf(row)))] : next);
      setCursor(value.nextCursor); setMore(value.hasMore);
    } catch (failure) { if (alive.current && original === sequence.current) setError(failureText(failure)); }
    finally { flight.current = false; if (alive.current && original === sequence.current) setBusy(false); }
  };
  const loadRef = useRef(load); loadRef.current = load;
  useEffect(() => { alive.current = true; return () => { alive.current = false; sequence.current++; }; }, []);
  const readKey = JSON.stringify([conversationId, sourceTaskId, refreshKey, ownerWindow()]);
  const readable = can('butler.preparations.list');
  useEffect(() => { if (!active || !readable) { attempted.current = ''; return; } if (!busy && !flight.current && attempted.current !== readKey) { attempted.current = readKey; void loadRef.current(); } }, [active, busy, readKey, readable]);
  // Keep this entry while the proposal is the only way to reach its next action.
  const relevant = entries.filter(row => {
    if (sourceTaskId) return row.sourceTaskId === sourceTaskId;
    const target = row.target;
    if (!target?.taskId) return !['dismissed', 'unavailable'].includes(row.state);
    // On Home a collapsed row or another execution of the same task is not a
    // route to this preparation. Suppress only an actually represented run.
    if (target.runId && (representedHomeRuns ? representedHomeRuns.has(JSON.stringify([target.taskId,target.runId,target.epoch])) : representedTaskIds?.has(target.taskId))) return false;
    return !target.runId || target.hasUnresolvedOperations !== false || !['completed', 'cancelled', 'canceled', 'stopped'].includes(target.status);
  });
  useEffect(() => {
    if (!conversationId || !onConversationRows) return;
    // The bottom work queue can omit settled work. The original reply cannot:
    // its real, linked deliverable must still be reachable after reopening.
    onConversationRows(conversationId, readable ? entries.flatMap(row => {
      const entry = conversationPreparation(row); return entry ? [entry] : [];
    }) : []);
  },[entries, conversationId, onConversationRows, readable]);
  const fallback = placedProposalKeys?.size ? relevant.filter(row => !placedProposalKeys.has(conversationPreparationKey({sourceConversationId:text(row.sourceConversationId),sourceTaskId:text(row.sourceTaskId),proposalId:text(row.proposalId)}))) : relevant;
  const visible = expanded ? fallback : fallback.slice(0, 3);
  const present = readable && (fallback.length > 0 || !!error || more);
  useEffect(() => { onPresenceChange?.(present); return () => onPresenceChange?.(false); }, [present, onPresenceChange]);
  if (!present) return null;
  return <section className="pw-butler-preparations" aria-label={sourceTaskId ? '关联的准备记录' : '待处理与进行中的工作'} aria-busy={busy}>
    {fallback.length > 0 && <header><h2>{sourceTaskId ? '关联的准备' : '还需准备'}</h2></header>}
    {error && <p className="pw-error" role="alert">{error}</p>}
    {error && <Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={!active || busy} onClick={() => void load()}>重新读取工作记录</Button>}
    <ol>{visible.map(row => <li key={keyOf(row)}><Button variant="ghost" className="pw-preparation-row" leadingIcon={row.kind === 'phone' ? IconMonitor : IconFolder} trailingIcon={IconChevronRight} disabled={!active || busy} onClick={event => onOpen({ sourceTaskId: row.sourceTaskId, proposalId: row.proposalId, sourceConversationId: row.sourceConversationId }, event.currentTarget)}>
      <span><strong dir="auto">{text(row.goal) || (row.kind === 'phone' ? '原手机工作提案' : '原项目工作提案')}</strong><small>{row.target?.taskId ? targetStateLabels[row.target.status] || '原任务状态待核对' : stateLabels[row.state] || '状态待核对'} · {row.kind === 'phone' ? '手机操作' : '项目开发'}</small></span>
    </Button></li>)}</ol>
    {fallback.length > 3 && <Button variant="ghost" size="small" disabled={!active || busy} onClick={() => setExpanded(value => !value)}>{expanded ? '收起' : `查看另 ${fallback.length - 3} 项`}</Button>}
    {more && <Button variant="ghost" size="small" disabled={!active || busy} onClick={() => { setExpanded(true); void load(true); }}>{busy ? '正在读取…' : '查看更早的工作'}</Button>}
  </section>;
}

type Choices = { mode: string; projectId: string; checkoutId: string };
const choicesOf = (value: Data): Choices => ({ mode: ['main_screen', 'background'].includes(value.mode) ? value.mode : 'background', projectId: text(value.projectId), checkoutId: text(value.checkoutId) });
const encodeChoices = (value: Choices, kind: string) => kind === 'phone' ? JSON.stringify([value.mode]) : JSON.stringify([value.projectId, value.checkoutId]);
const projectMethods = {
  draft: {id:'draft',label:'只出改稿和差异',description:'不写回原件，不运行命令。'},
  saved: {id:'saved',label:'接着改我另存的一稿',description:'只把你选的版本交给模型，不读工程或其他稿。'},
  project: {id:'project',label:'按现有项目流程工作',description:'仍需核对这次使用的源码、模型与权限。'},
};

/** Reuses the existing work goal, project selectors, PermissionReview and Vaul
 * object page. Only the native typed proposal can enter the original admission. */
export function ButlerPreparationDetail({ target, projects, can, run, onClose, onOpenTask, onOpenPhoneRun, onOpenPhoneReport, onConversation, onSetup, onPrepareModel, registerBack, registerBeforeLeave, inline = false }: Shared & {
  target: ButlerPreparationTarget; projects: { id: string; name: string }[]; onClose: () => void; onOpenTask: (id: string, original?: {runId: string; epoch: number}) => void;
  onConversation?: (id: string) => void; onSetup?: (kind: 'phone' | 'project', phone?: PhonePreparationSetupPin) => void; registerBack: (handler: (() => boolean) | null) => void;
  onPrepareModel?: (pin:ProjectModelPreparationPin) => void;
  onOpenPhoneRun?: (origin:ProposalPhoneRun)=>void; onOpenPhoneReport?: (target:PhoneReportTarget)=>void;
  registerBeforeLeave?: (handler: (() => Promise<boolean>) | null) => void;
  inline?: boolean;
}) {
  // Proposed mode is the non-mutating route. The native confirmation is still
  // required; reopening a proposal never restores permission for the wider route.
  const [draftOnly, setDraftOnly] = useState(true);
  const [usingRevision,useRevision]=useState(false),[versions,setVersions]=useState<RevisionChoice[]>([]),[picked,pick]=useState<RevisionChoice|null>(null);
  const [versionsCursor,pageVersions]=useState(''),[versionsRead,didReadVersions]=useState(false);
  const [view, setView] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [capabilities, setCapabilities] = useState<Data[]>([]);
  const [modelBlock,setModelBlock] = useState<(ProjectModelPreparationPin & {projectId:string;checkoutId:string;window:string}) | null>(null);
  const [choices, setChoices] = useState<Choices>(() => choicesOf({})), [inspection, setInspection] = useState<Data | null>(null), [copy, setCopy] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [working, setWorking] = useState(''), [saving, setSaving] = useState(false), [error, setError] = useState(''), [draftError, setDraftError] = useState(''), [stale, setStale] = useState(false), [saved, setSaved] = useState(true);
  const busy=!!working;
  const alive = useRef(true), entered = useRef(false), generation = useRef(0), handle = useRef(''), viewRef = useRef(view), reviewRef = useRef(review), receiptRef = useRef(receipt);
  const choicesRef = useRef(choices), baseline = useRef(''), touched = useRef(false), needsSave = useRef(false), flight = useRef<Promise<void> | null>(null), paused = useRef(false);
  const scroller = useRef<HTMLDivElement>(null), heading = useRef<HTMLHeadingElement>(null), reviewPosition = useRef(0), wasReview = useRef(false);
  const titleId = useId(), descriptionId = useId();
  const verifyPhoneReturn = useRef(target.fromPhoneSetup);
  const latest = useRef({ run, can, onClose }); latest.current = { run, can, onClose }; viewRef.current = view; reviewRef.current = review; receiptRef.current = receipt;
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void, raw = false) => {
    if (entered.current || document.hidden || !latest.current.can(action)) return;
    const original = handle.current, window = ownerWindow(), ticket = generation.current;
    if (!raw && !original) return;
    entered.current = true; setWorking(action); setError('');
    try {
      const value = await latest.current.run(action, raw ? args : { viewHandle: original, ...args });
      if (!alive.current || ticket !== generation.current || window !== ownerWindow() || document.hidden) return;
      accept?.(value); return value as Data;
    } catch (failure) { if (alive.current && ticket === generation.current) setError(failureText(failure)); }
    finally { entered.current = false; if (alive.current) setWorking(''); }
  };
  const read = () => invoke('butler.preparation.read', { sourceTaskId: target.sourceTaskId, proposalId: target.proposalId }, value => {
    if (!preparation(value, target) || !text(value.viewHandle)) throw new Error('没有取得这份原提案的页面，请重新读取。');
    const nativeChoices = choicesOf(value.preparationDraft || {});
    const returned = verifyPhoneReturn.current;
    if (returned && (value.kind !== 'phone' || value.reviewDigest !== returned.digest ||
        value.canPrepare === true && (value.preparationDraft?.saved !== true || nativeChoices.mode !== returned.mode))) {
      closeNative(value.viewHandle);
      throw new Error('原提案或原执行位置已变化，没有用另一份准备替代。');
    }
    verifyPhoneReturn.current = undefined;
    setVersions([]);pick(null);pageVersions('');didReadVersions(false);
    handle.current = value.viewHandle; setView(value); setStale(false); setReview(null); setModelBlock(null); setInspection(null); setCapabilities([]);
    baseline.current = encodeChoices(nativeChoices, value.kind);
    needsSave.current = value.preparationDraft?.saved === false;
    if (!touched.current) { choicesRef.current = nativeChoices; setChoices(nativeChoices); }
    setSaved(!needsSave.current && baseline.current === encodeChoices(choicesRef.current, value.kind));
    if (needsSave.current) setDraftError('原选择尚未确认写入本机，请保留本页并重新保存。');
    setCopy(value.preparationDraft?.copyRequestId ? { projectId: nativeChoices.projectId, requestId: value.preparationDraft.copyRequestId, queryRequired: true } : null);
    setReceipt(value.originalStart || null); paused.current = false;
  }, true);
  const readRef = useRef(read); readRef.current = read;
  const closeNative = (original: string) => { if (original) void latest.current.run('butler.preparation.close', { viewHandle: original }).catch(() => {}); };
  useEffect(() => {
    alive.current = true; let window = ownerWindow();
    const retire = () => { generation.current++; const original = handle.current; handle.current = ''; closeNative(original); if (alive.current) { setStale(true); setReview(null); setModelBlock(null); setInspection(null); setCapabilities([]); setVersions([]);pick(null);pageVersions('');didReadVersions(false); } };
    const inspect = () => { const next = ownerWindow(); if (next !== window || document.hidden) { window = next; retire(); } };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect);
    void readRef.current();
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); };
  }, [target.sourceTaskId, target.proposalId, target.sourceConversationId]);
  async function flush(explicit = false): Promise<void> {
    if (flight.current) return flight.current;
    const currentView = viewRef.current;
    if (!currentView || !needsSave.current && baseline.current === encodeChoices(choicesRef.current, currentView.kind)) return;
    if (paused.current && !explicit || !handle.current || document.hidden || entered.current) throw new Error('选择还没有保存，请保留本页并重新保存。');
    const original = handle.current, ticket = generation.current; paused.current = false;
    const operation = (async () => { setSaving(true); try {
      while (alive.current && (needsSave.current || baseline.current !== encodeChoices(choicesRef.current, currentView.kind))) {
        const next = { ...choicesRef.current }, encoded = encodeChoices(next, currentView.kind);
        const value = await latest.current.run('butler.preparation.draft', { viewHandle: original, ...(currentView.kind === 'phone' ? { mode: next.mode } : { projectId: next.projectId, checkoutId: next.checkoutId }) });
        if (!alive.current || ticket !== generation.current || original !== handle.current) throw new Error('原准备页面已变化，选择仍保留，请重新读取后保存。');
        if (value.saved !== true || encodeChoices(choicesOf(value.preparationDraft || {}), currentView.kind) !== encoded) throw new Error('原准备选择的保存回执尚未核对。');
        baseline.current = encoded; needsSave.current = false; setDraftError(''); setSaved(encoded === encodeChoices(choicesRef.current, currentView.kind));
        if (currentView.kind === 'project' && choicesRef.current.projectId === next.projectId) setCopy(previous => value.preparationDraft?.copyRequestId ? previous?.projectId === next.projectId && previous?.requestId === value.preparationDraft.copyRequestId ? previous : { projectId: next.projectId, requestId: value.preparationDraft.copyRequestId, queryRequired: true } : null);
      }
    } catch (failure) { paused.current = true; if (alive.current) { setDraftError(failureText(failure)); setSaved(false); } throw failure; }
    finally { flight.current = null; if (alive.current) setSaving(false); } })();
    flight.current = operation; return operation;
  }
  const flushRef = useRef(flush);
  flushRef.current = flush;
  useLayoutEffect(() => {
    if (!inline || !registerBeforeLeave) return;
    registerBeforeLeave(async () => {
      if (entered.current) return false;
      try {
        await flushRef.current();
        return !entered.current;
      } catch (failure) {
        if (alive.current) setDraftError(failureText(failure));
        return false;
      }
    });
    return () => registerBeforeLeave(null);
  }, [inline, registerBeforeLeave]);
  const edit = (next: Choices) => { if (entered.current || reviewRef.current) return; touched.current = true; if (choicesRef.current.projectId !== next.projectId) setInspection(null); choicesRef.current = next; setChoices(next); setSaved(false); setReview(null); setModelBlock(null); setError(''); if (!paused.current) void flush().catch(() => {}); };
  const close = async (after?: () => void, stillCurrent?: () => boolean) => {
    if (entered.current) return;
    try { await flush(); if (stillCurrent && !stillCurrent()) return;
      const original = handle.current; handle.current = ''; closeNative(original); latest.current.onClose(); after?.(); }
    catch (failure) { if (alive.current) setDraftError(failureText(failure)); }
  };
  const openPhoneSetup = () => {
    const original = viewRef.current, mode = choicesRef.current.mode, digest = original?.reviewDigest;
    if (!onSetup || !original || !preparation(original,target) || original.kind !== 'phone' || original.canPrepare !== true ||
        typeof digest !== 'string' || !/^[a-f0-9]{64}$/.test(digest) || (mode !== 'background' && mode !== 'main_screen')) return;
    const window = ownerWindow(), version = generation.current, originalHandle = handle.current;
    // The navigation keeps only the immutable proposal, not the private review handle or approval.
    void close(() => onSetup('phone',{digest,mode}), () => alive.current && !document.hidden && !!originalHandle &&
      originalHandle === handle.current && window === ownerWindow() && version === generation.current &&
      viewRef.current === original && choicesRef.current.mode === mode && !needsSave.current);
  };
  const back = useRef<() => boolean>(() => true);
  back.current = () => { if (!entered.current) { if (reviewRef.current) setReview(null); else void close(); } return true; };
  useEffect(() => { registerBack(() => back.current()); return () => registerBack(null); }, [registerBack]);
  const reviewing = !!review;
  useLayoutEffect(() => {
    if (inline) {
      heading.current?.scrollIntoView({ block: 'nearest' });
      heading.current?.focus({ preventScroll: true });
      return;
    }
    if (!scroller.current || wasReview.current === reviewing) return;
    if (reviewing) { reviewPosition.current = scroller.current.scrollTop; scroller.current.scrollTop = 0; heading.current?.focus({ preventScroll: true }); }
    else scroller.current.scrollTop = reviewPosition.current;
    wasReview.current = reviewing;
  }, [inline, reviewing]);
  const afterSave = async (operation: () => void | Promise<unknown>) => { try { await flush(true); if (alive.current) await operation(); } catch (failure) { if (alive.current) setDraftError(failureText(failure)); } };
  const inspectProject = () => void afterSave(() => invoke('butler.preparation.project.choices', { projectId: choicesRef.current.projectId }, value => {
    if (value.projectId !== choicesRef.current.projectId || !Array.isArray(value.checkouts)) throw new Error('私人副本信息不属于当前所选项目。'); setInspection(value);
  }));
  const copyProject = (query: boolean) => void afterSave(async () => {
    const projectId = choicesRef.current.projectId; if (!query) setCopy({ projectId, queryRequired: true });
    await invoke(query ? 'butler.preparation.project.queryCopy' : 'butler.preparation.project.prepare', { projectId }, value => {
      if (value.projectId !== projectId || !text(value.requestId) || value.replayAllowed !== false) throw new Error('原副本准备回执尚未核对。');
      setCopy(value); if (value.found === true) setInspection(null);
    });
  });
  const listVersions=(more=false)=>void afterSave(()=>invoke('butler.preparation.project.savedRevisions',more?{cursor:versionsCursor}:{},v=>{
    if(v.sourceTaskId!==target.sourceTaskId||v.proposalId!==target.proposalId||v.sourceConversationId!==target.sourceConversationId||typeof v.nextCursor!=='string'||!Array.isArray(v.entries)||!v.entries.every(revisionChoice))throw new Error('原对话与这批已存稿不能核对。');
    setVersions(prior=>{const unique=new Map<string,RevisionChoice>();for(const row of [...(more?prior:[]),...v.entries])unique.set(pinKey(row.ownerRevision),row);return [...unique.values()]});pageVersions(v.nextCursor);didReadVersions(true);
  }));
  const switchProjectSource=(value:string)=>{
    if(entered.current||reviewRef.current)return;setReview(null);setModelBlock(null);setError('');setDraftOnly(value!=='project');useRevision(value==='saved');pick(null);setVersions([]);pageVersions('');didReadVersions(false);
    if(value==='saved')listVersions();
  };
  const prepareReview = () => void afterSave(() => {
    setModelBlock(null);
    return invoke(view?.kind === 'phone' ? 'butler.preparation.phone.review' : 'butler.preparation.project.review', view?.kind === 'phone' ? { mode: choicesRef.current.mode } : usingRevision ? {draftOnly:true,sourceKind:'owner_saved_revision',ownerRevision:picked?.ownerRevision} : { projectId: choicesRef.current.projectId, checkoutId: choicesRef.current.checkoutId, draftOnly }, value => {
    if(value?.status==='blocked'&&value.prerequisite==='default_model_unconfigured'){
      const original=viewRef.current,{projectId,checkoutId}=choicesRef.current;
      if(!inline)throw new Error('模型尚未就绪，提案和项目选择仍可保留');
      if(!draftOnly||usingRevision||original?.kind!=='project'||original.state!=='proposed'||original.available!==true||original.canPrepare!==true||
         value.kind!=='project'||value.draftOnly!==true||value.executionAuthorized!==false||value.reviewHandle!==undefined||
         value.sourceTaskId!==target.sourceTaskId||value.proposalId!==target.proposalId||value.sourceConversationId!==target.sourceConversationId||
         value.sourceRunId!==original.sourceRunId||!identifier(value.sourceRunId)||value.sourceEpoch!==original.sourceEpoch||!positive(value.sourceEpoch)||
         value.reviewDigest!==original.reviewDigest||typeof value.reviewDigest!=='string'||!/^[a-f0-9]{64}$/.test(value.reviewDigest)||
         value.projectId!==projectId||value.checkoutId!==checkoutId||!identifier(projectId)||!identifier(checkoutId)||
         !Number.isSafeInteger(value.modelSelectionRevision)||value.modelSelectionRevision<0)
        throw new Error('未能核对这次改稿的模型条件，没有开始工作。');
      setModelBlock({sourceTaskId:target.sourceTaskId,proposalId:target.proposalId,sourceConversationId:target.sourceConversationId,
        sourceRunId:value.sourceRunId,sourceEpoch:value.sourceEpoch,projectId,checkoutId,modelSelectionRevision:value.modelSelectionRevision,window:ownerWindow()});
      return;
    }
    if (!text(value.reviewHandle) || value.goal !== contractOf(viewRef.current)?.goal || !contractOf(value)) throw new Error('审阅没有完整对应原提案目标，请重新核对。');
    if (viewRef.current?.kind === 'phone' && !reviewedPhone(value,viewRef.current,target,choicesRef.current.mode)) throw new Error('原目标、手机条件和执行范围没有一起核实。没有提交，请重新核对。');
    if(viewRef.current?.kind==='project'){
      if(value.projectId!==(usingRevision?picked?.projectId:choicesRef.current.projectId)||value.checkoutId!==(usingRevision?picked?.checkoutId:choicesRef.current.checkoutId)||value.draftOnly!==draftOnly||!text(value.source?.sourceDigest)||!text(value.model?.label))throw new Error('项目来源与模型审阅尚未完整返回。');
      if(usingRevision&&(!picked||!text(value.model?.destination)||value.sourceTaskId!==target.sourceTaskId||value.proposalId!==target.proposalId||value.sourceConversationId!==target.sourceConversationId||!revisionChoice(value.source)||pinKey(value.source.ownerRevision)!==pinKey(picked.ownerRevision)||value.source.ordinal!==picked.ordinal||value.source.path!==picked.path||value.source.bytes!==picked.bytes||typeof value.source.selectedText!=='string'||new TextEncoder().encode(value.source.selectedText).length!==picked.bytes))throw new Error('新目标与选定的同一份已存稿不能核对，没有启动。');
      if(!usingRevision&&value.source?.ownerRevision)throw new Error('收到与本次选择不同的起始件。');
    }
    setReview(viewRef.current?.kind==='phone'?{...value,documentEpoch:bridge.getDocumentEpoch(),deadline:performance.now()+value.expiresInMs}:value);
    });
  });
  const acceptReceipt = (value: Data, original?: Data | null) => {
    if (preparation(value, target)) { setView(previous => ({ ...previous, ...value })); setReceipt(null); return; }
    const expected = original || receiptRef.current;
    if(expected?.ownerRevision && pinKey(value.ownerRevision)!==pinKey(expected.ownerRevision))throw new Error('原请求回执不属于刚确认的那份已存稿，请查询原请求。');
    if (!['accepted', 'rejected', 'unknown'].includes(value.status) || expected?.kind === 'phone' && (value.kind !== 'phone' || value.requestId !== expected.requestId || value.requestId !== viewRef.current?.targetRequestId || value.conversationId !== target.sourceConversationId || value.status === 'accepted' && value.taskId !== value.requestId) || expected?.kind === 'project' && value.clientMessageId !== expected.clientMessageId || value.status === 'accepted' && !text(value.taskId)) throw new Error('原启动回执身份尚未完整核对，请查询原请求。');
    setReceipt(value); setReview(null);
  };
  const start = () => {
    const original = reviewRef.current, currentView = viewRef.current; if (!original || !currentView || entered.current) return;
    if(currentView.kind==='phone'){
      const shown=reviewedPhone(original,currentView,target,choicesRef.current.mode);
      if(!shown||document.hidden||original.documentEpoch!==bridge.getDocumentEpoch()||performance.now()>=original.deadline||
        shown.conditions?.notification.state!=='ready'||shown.conditions.capacity?.state!=='ready'){setError('这次手机条件需要重新核对，没有提交。');return}
    }
    const retained = { ...original, source:undefined, ownerRevision:original.source?.ownerRevision, kind: currentView.kind, clientMessageId: currentView.clientMessageId, status: 'unknown', message: '原启动请求待核对，请查询原请求。' };
    setReceipt(retained); setReview(null);
    void invoke(currentView.kind === 'phone' ? 'butler.preparation.phone.start' : 'butler.preparation.project.start', { reviewHandle: original.reviewHandle }, value => acceptReceipt(value, retained));
  };
  const blocked = busy || saving || stale || !view || !handle.current, unresolved = !!receipt && ['pending', 'unknown', 'accepted'].includes(receipt.status);
  const canPrepare = !blocked && view?.canPrepare === true && !unresolved && !review && can('butler.preparation.draft');
  const missingDefaultModel = canPrepare && saved && draftOnly && !usingRevision && modelBlock?.projectId===choices.projectId &&
    modelBlock.checkoutId===choices.checkoutId && modelBlock.window===ownerWindow() && sameProjectModelRevision(modelBlock,bridge.getState().state) ? modelBlock : null;
  const openDefaultModel = () => {
    const pin=missingDefaultModel;
    if(pin&&!entered.current&&!document.hidden&&pin.window===ownerWindow()&&choicesRef.current.projectId===pin.projectId&&
       choicesRef.current.checkoutId===pin.checkoutId&&sameProjectModelRevision(pin,bridge.getState().state))onPrepareModel?.(pin);
  };
  const checkouts = rows(inspection?.checkouts), contract = contractOf(view), targetTaskId = text(receipt?.taskId) || text(view?.target?.taskId);
  const phoneShown=review&&view?.kind==='phone'?reviewedPhone(review,view,target,choices.mode):null;
  const phoneHistory=view?.kind==='phone'&&(!!receipt||!!view.target?.taskId);
  const projectView = view?.kind === 'project' && preparation(view, target) ? view : null;
  const projectTarget = projectView && identifier(targetTaskId) && projectView.target?.taskId === targetTaskId && projectView.targetTaskId === targetTaskId
    ? projectView.target : null;
  const acceptedProjectReceipt = projectView && receipt?.kind === 'project' && receipt.status === 'accepted' && receipt.queryOnly === true &&
    identifier(projectView.clientMessageId) && receipt.clientMessageId === projectView.clientMessageId && identifier(receipt.taskId) &&
    (projectView.target == null || !!projectTarget);
  const projectRegistered = projectView?.available === true && (!!projectTarget || !!acceptedProjectReceipt);
  const pendingProjectStart = !projectRegistered && projectView?.available === true && projectView.state === 'proposed' &&
    receipt?.kind === 'project' && identifier(projectView.clientMessageId) && receipt.clientMessageId === projectView.clientMessageId &&
    ['pending','unknown'].includes(receipt.status);
  const submittingProject = pendingProjectStart && !stale && working === 'butler.preparation.project.start';
  const projectStartReceipt = projectView && receipt && !projectRegistered && <section className="pw-preparation-receipt" aria-label="原启动记录">
    <p role="status">{submittingProject ? '正在提交这次项目工作，尚未收到接单回执。' : pendingProjectStart && stale ? '原启动尚无确认回执。重新读取原记录，不会再次提交。' : text(receipt.message) || (receipt.status === 'accepted' ? '原任务已保存。' : '原启动回执待核对。')}</p>
    {receipt.error && <p className="pw-error">{text(receipt.error)}</p>}
    {!submittingProject && !(pendingProjectStart && stale) && ['pending','unknown'].includes(receipt.status) && <Button variant="secondary" disabled={blocked} onClick={() => void invoke('butler.preparation.query', {}, acceptReceipt)}>查询原启动请求</Button>}
  </section>;
  // A receipt proves registration, but only the original preparation read binds an execution.
  const boundProjectRun = projectTarget?.canOpenOriginalRun === true && identifier(projectTarget.originalRunId) && positive(projectTarget.originalEpoch) &&
    projectTarget.originalRunId === projectTarget.runId && projectTarget.originalEpoch === projectTarget.epoch
    ? {runId: projectTarget.originalRunId, epoch: projectTarget.originalEpoch} : null;
  const projectName = projects.find(project => project.id === choices.projectId)?.name || '原项目';
  const projectMethod = projectMethods[usingRevision ? 'saved' : draftOnly ? 'draft' : 'project'];
  const suggestedProject = projects.find(project => project.id === view?.projectId);
  const chooseProject = (projectId: string) => {
    if (!canPrepare || projectId === choicesRef.current.projectId) return;
    setCopy(null); edit({ ...choicesRef.current, projectId, checkoutId: '' });
    if (projectId) inspectProject();
  };
  const title = reviewing ? '核对本次执行' : projectRegistered || pendingProjectStart ? '原项目工作' : inline ? '准备这次工作' : '管家为你准备的工作';
  const description = view?.kind === 'phone' ? '原请求 · 手机操作' : view?.kind === 'project' ? '原请求 · 项目开发' : '读取原提案与准备状态';
  const body = <div ref={scroller} className={inline ? 'pw-preparation-body' : 'pw-drawer-body pw-task-body pw-preparation-body'}>
        {error && <p className="pw-error" role="alert">{error}</p>}{draftError && <p className="pw-error" role="alert">{draftError}</p>}
        {draftError && !saved && <Button variant="ghost" disabled={busy || saving} onClick={() => { const original = handle.current; handle.current = ''; closeNative(original); onClose(); }}>放弃本页未保存的选择并返回</Button>}
        {pendingProjectStart&&projectStartReceipt}
        {(!view || stale) && <section>{!pendingProjectStart&&<p className="pw-muted" role={projectRegistered ? 'status' : undefined}>{projectRegistered ? '原任务已登记，执行进展待重新核对。' : stale ? '原准备窗口已变化，保留选择后重新核对即可继续。' : busy ? '正在读取原提案…' : '原提案尚未完整读取。'}</p>}<Button variant="secondary" disabled={busy || saving || (projectRegistered || pendingProjectStart) && !can('butler.preparation.read')} onClick={() => void read()}>{projectRegistered ? '核对执行进展' : pendingProjectStart ? '重新读取原启动记录' : '重新读取原提案'}</Button></section>}
        {view && <>
          {!projectRegistered&&!pendingProjectStart&&!(view.kind==='phone'&&(review||phoneHistory))&&<div className="pw-owner-work-handling"><div><strong>{stateLabels[view.state] || '状态待核对'}</strong><p>{text(view.reason) || (targetTaskId ? '这份准备已关联原任务，按实际执行进展查看结果。' : '核对目标、所需能力与本次范围后，再决定开始。')}</p></div></div>}
          {contract && !review && !phoneHistory && <WorkContract goal={contract.goal} contract={contract} />}
          {view.kind==='phone'&&!review&&<ButlerPhoneReceipt view={view} raw={receipt} target={target} inline={inline} disabled={blocked}
            onCheck={can('butler.preparation.query')?()=>void invoke('butler.preparation.query',{},acceptReceipt):undefined}
            onRun={onOpenPhoneRun&&can('tasks.open')?t=>void close(()=>onOpenPhoneRun(t)):undefined}
            onReport={onOpenPhoneReport&&can('phone.result.original')?t=>void close(()=>{if(phoneReportInSource(t))onOpenPhoneReport(t)}):undefined}/>}
          {phoneHistory&&contract&&!review&&<Accordion><AccordionItem id="phone-original-terms" title="查看原委托与验收方式"><WorkContract goal={contract.goal} contract={contract}/></AccordionItem></Accordion>}
          {!pendingProjectStart&&projectStartReceipt}
          {view.kind === 'project' && targetTaskId && (!stale || !projectRegistered) && (boundProjectRun
            ? <Button variant="secondary" trailingIcon={IconChevronRight} disabled={busy || saving || !can('tasks.open')}
                onClick={() => void close(() => onOpenTask(targetTaskId,boundProjectRun))}>查看原工作与结果</Button>
            : <section className="pw-preparation-receipt"><p role="status">{projectTarget?.originalRunId
                ? '初次执行已不是当前轮。这份提案不会打开后续轮次来代替原结果。'
                : projectRegistered ? '原任务已登记，执行进展尚未核对。' : '已经有任务记录，但这次实际执行尚未核对。'}</p>
                <Button variant={projectRegistered ? 'secondary' : 'ghost'} disabled={busy || saving || projectRegistered && !can('butler.preparation.read')} onClick={() => void read()}>{projectRegistered ? '核对执行进展' : '核对原执行'}</Button></section>)}
          {view.canPrepare === true && !unresolved && !review && <section className="pw-preparation-choices" aria-label="本次执行准备">
            {view.kind === 'phone' ? <RadioOptions label="在哪个屏幕执行" value={choices.mode} options={[{ id: 'background', label: '后台独立屏' }, { id: 'main_screen', label: '手机主屏' }]} onChange={mode => edit({ ...choicesRef.current, mode })} isDisabled={!canPrepare} /> : <>
              <p className="pw-muted"><strong>{projectMethod.label}</strong> · {projectMethod.description}</p>
              {usingRevision?<><SelectField label="用我保留的哪一稿" value={picked?pinKey(picked.ownerRevision):''} options={[{id:'',label:'请选择一份，不默认使用最新稿'},...versions.map(v=>({id:pinKey(v.ownerRevision),label:v.path+' · 我的第 '+v.ordinal+' 稿 · '+(v.goal||v.ownerRevision.taskId)+' · '+v.ownerRevision.sha256.slice(0,8)}))]} isDisabled={!canPrepare||!versions.length} onChange={id=>pick(versions.find(v=>pinKey(v.ownerRevision)===id)||null)}/>
                {versionsRead&&!versions.length&&<p className="pw-muted">{versionsCursor?'这一批工作没有主人另存的稿。':'在这段对话可核对的先前项目工作中，还没有你另存的稿。'}</p>}
                {(!versionsRead||versionsCursor)&&<Button variant="ghost" disabled={!canPrepare} onClick={()=>listVersions(!!versionsCursor)}>{versionsCursor?'查看更早的工作':'读取本对话的已存稿'}</Button>}</>:<>
              <SelectField label="选择真实项目" value={choices.projectId} options={[{ id: '', label: '选择要工作的项目' }, ...projects.map(project => ({ id: project.id, label: project.name })), ...(choices.projectId && !projects.some(project => project.id === choices.projectId) ? [{ id: choices.projectId, label: '原项目暂不可用，请重新核对' }] : [])]} isDisabled={!canPrepare} onChange={chooseProject} />
              {suggestedProject && !choices.projectId && <Button variant="secondary" disabled={!canPrepare} onClick={() => chooseProject(suggestedProject.id)}>用提案中的项目：{suggestedProject.name}</Button>}
              {choices.projectId && (!inspection || inspection.projectId !== choices.projectId) && <Button variant="ghost" disabled={!canPrepare} onClick={inspectProject}>重新读取私人副本</Button>}
              {inspection && inspection.projectId === choices.projectId && <SelectField label="本次工作的私人副本" value={choices.checkoutId} options={[{ id: '', label: checkouts.length ? '选择已有私人副本' : '尚未准备私人副本' }, ...checkouts.map(row => ({ id: row.checkoutId, label: text(row.name) || projectName })), ...(choices.checkoutId && !checkouts.some(row => row.checkoutId === choices.checkoutId) ? [{ id: choices.checkoutId, label: '原副本暂不可用，请核对' }] : [])]} isDisabled={!canPrepare} onChange={checkoutId => edit({ ...choicesRef.current, checkoutId })} />}
              {choices.projectId && (!copy || copy.registered === false) && inspection && !checkouts.length && <Button variant="secondary" disabled={!canPrepare} onClick={() => copyProject(false)}>准备原项目的私人副本</Button>}
              {choices.projectId && <Button variant="ghost" disabled={!canPrepare} onClick={() => copyProject(true)}>查询原副本准备</Button>}
              {copy?.projectId === choices.projectId && <p className="pw-muted" role="status">{copy.found === true ? '原私人副本已准备，请重新读取副本列表后选择。' : copy.partial === true ? '原副本准备中断，已有文件保留。请在原项目入口核对。' : copy.queryRequired ? '原副本准备已有记录，请先查询原请求。' : '这次查询没有读到完整副本，未重复创建。'}</p>}</>}
            </>}
            {!saved && <Button variant="secondary" disabled={blocked} onClick={() => void flush(true).catch(() => {})}>保存保留的选择</Button>}
            <div className="pw-preparation-start">{missingDefaultModel && onPrepareModel ?
              <Button variant="primary" onClick={openDefaultModel}>设置默认模型</Button> :
              <Button variant="primary" disabled={!canPrepare || view.kind === 'project' && (usingRevision?!picked:!choices.projectId || !choices.checkoutId)} onClick={prepareReview}>核对{view.kind === 'phone' ? '手机范围' : draftOnly ? '改稿范围' : '源码与模型'}</Button>}
              <span role="status">{missingDefaultModel ? '这次改稿需要默认模型，尚未开始。' : usingRevision?'本次选稿不会自动发给模型':saving ? '正在保存选择…' : saved ? '准备选择已保留' : '本页选择尚未确认保存'}</span></div>
            <Accordion variant="divided">{view.kind==='project'&&<AccordionItem id="project-method" title="其他做法">
              <RadioOptions label="本次怎么做" value={projectMethod.id} isDisabled={!canPrepare}
                options={[projectMethods.draft,...(can('butler.preparation.project.savedRevisions')?[projectMethods.saved]:[]),projectMethods.project]}
                onChange={switchProjectSource} />
            </AccordionItem>}<AccordionItem id="capabilities" title="核对所需能力" description="设备与运行环境的当前情况">
              <Button variant="ghost" leadingIcon={IconRefresh} disabled={blocked} onClick={() => void invoke('butler.capabilities', { purpose: view.kind }, value => { if (value.observationOnly !== true || !Array.isArray(value.capabilities)) throw new Error('能力状态尚未完整读取。'); setCapabilities(value.capabilities); }, true)}>读取当前能力状态</Button>
              {capabilities.map(row => <section key={row.id}><h3>{text(row.label)}</h3><p>{text(row.detail)}</p>{row.state && <p className="pw-muted">{({ busy: '当前资源占用中', preparation_required: '需要完成准备', review_required: '可以进入本次审阅', selection_required: '需要选择真实执行对象', unavailable: '当前不可用' } as Record<string, string>)[row.state] || '请按实际范围核对'}</p>}</section>)}
              {onSetup && <Button variant="secondary" disabled={busy || saving} onClick={view.kind==='phone'?openPhoneSetup:()=>void close(()=>onSetup('project'))}>打开{view.kind === 'phone' ? '手机' : '本机工具'}准备</Button>}
            </AccordionItem></Accordion>
          </section>}
          {review&&view.kind==='phone'&&phoneShown&&<PhoneScopeReview shown={phoneShown} live={{reviewHandle:review.reviewHandle,expiresInMs:review.expiresInMs,documentEpoch:review.documentEpoch,conversationId:review.conversationId}}
            pending={busy||saving} unknown={false} terms={contractOf(review)} onClose={()=>setReview(null)} onStart={!blocked&&can('butler.preparation.phone.start')?start:undefined}
            onCheck={can('butler.preparation.phone.review')?h=>{if(reviewRef.current?.reviewHandle===h)prepareReview()}:undefined}
            onSettings={can('butler.preparation.phone.settings')?(h,place)=>{if(reviewRef.current?.reviewHandle===h)void invoke('butler.preparation.phone.settings',{reviewHandle:h,target:place},v=>{if(v.status!=='settings_opened')throw new Error('系统设置页是否已打开尚未核对。');setReview(null)})}:undefined}
            onPlace={phoneShown.conditions?.screen.settings!=='background_setup'||onSetup?(h,place)=>{if(reviewRef.current?.reviewHandle!==h||entered.current)return;if(place==='main_screen_choice')setReview(null);else openPhoneSetup()}:undefined}/>}
          {review&&view.kind==='project'&&<PermissionReview title={text(review.goal)} context={review.source?.ownerRevision?'这次新目标 · 我已另存的一稿':`${projectName} · 原私人副本`} reason={review.source?.ownerRevision?'确认后，新目标和所选稿全文可发给下面这个模型；在线模型会收到这些内容。不会附带其他版本或工程文件，不写回、不运行命令。':review.draftOnly === true ? '只准备独立改稿和差异。模型可按需读这份固定副本；如果选择的是在线模型，被读取的源码会交给该模型。不写回原件或副本，不运行命令。' : '按已选择的源码与模型建立原项目任务，命令执行与项目导回继续使用各自的原审阅入口。'} fields={[{ label: '本次模型', value: text(review.model?.label) }, { label: '运行位置', value: text(review.runtime) }, ...(review.source?.ownerRevision?[{label:'接收方',value:text(review.model.destination)},{label:'所选文件',value:text(review.source.path)},{label:'你的保存版本',value:'第 '+review.source.ordinal+' 稿 · '+review.source.bytes+' 字节'},{label:'此稿 SHA-256',value:text(review.source.ownerRevision.sha256)}]:[{ label: '固定副本', value: choices.checkoutId }, { label: '源码版本', value: text(review.source?.sourceDigest) }])]} pending={busy} onReject={() => setReview(null)} onApprove={!blocked ? start : undefined} approveLabel={review.source?.ownerRevision?'确认，把这一稿交给管家':review.draftOnly === true ? '确认，开始独立改稿' : '确认并开始这次工作'}><WorkContract goal={text(review.goal)} contract={contractOf(review)} />{review.source?.ownerRevision&&<details><summary>核对会发给模型的完整稿</summary><pre className="pw-code-editor">{review.source.selectedText||'（这份已存稿是空文件）'}</pre></details>}</PermissionReview>}
          <nav className="pw-task-source-links" aria-label="原委托与准备"><Button variant="ghost" disabled={busy || saving || !can('tasks.open')} onClick={() => void close(() => onOpenTask(target.sourceTaskId))}>查看原委托记录</Button>{!inline && onConversation && <Button variant="ghost" leadingIcon={IconChat} disabled={busy || saving} onClick={() => void close(() => onConversation(target.sourceConversationId))}>回到原委托话题</Button>}{view.canPrepare === true && !unresolved && !review && <Button variant="ghost" disabled={blocked} onClick={() => void afterSave(() => invoke('butler.preparation.dismiss', {}, value => { if (!preparation(value, target)) throw new Error('原提案取消回执尚未核对。'); setView(value); }))}>取消这份提案</Button>}</nav>
        </>}
      </div>;
  if (inline) return <section className="pw-preparation-detail pw-preparation-inline" aria-labelledby={titleId} aria-describedby={descriptionId} aria-busy={busy || saving}>
    <header className="pw-preparation-inline-header">
      <div><h2 id={titleId} ref={heading} tabIndex={-1}>{title}</h2><p id={descriptionId}>{description}</p></div>
      <IconButton icon={reviewing ? IconArrowLeft : IconClose} aria-label={reviewing ? '返回准备选择' : '收起工作准备'} disabled={busy || saving} onClick={() => back.current()} />
    </header>
    {body}
  </section>;
  return <Drawer.Root open onOpenChange={open => { if (!open) void close(); }} dismissible={!busy && !saving} direction="right" handleOnly shouldScaleBackground={false}>
    <Drawer.Portal><Drawer.Overlay className="pw-overlay pw-work-object-overlay" /><Drawer.Content className="pw-drawer pw-task-drawer pw-work-object-surface pw-preparation-detail" aria-describedby={descriptionId}>
      <header className="pw-drawer-header pw-task-header"><IconButton icon={IconArrowLeft} aria-label={reviewing ? '返回准备选择' : '返回原页面'} disabled={busy || saving} onClick={() => back.current()} /><div><Drawer.Title ref={heading} tabIndex={-1}>{title}</Drawer.Title><Drawer.Description id={descriptionId}>{description}</Drawer.Description></div></header>
      {body}
    </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
