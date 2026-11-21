import type {ProjectDraftReturn} from './ProjectConventionDraftAction';
import type {LocalTerminalCheckout} from './LocalWorkspace';
import { checkedHostFiles, checkedHostText, ProjectHostComparedFiles, ProjectHostDiff, type HostComparedFile, type HostComparison, type HostDiff, type HostEditorFile } from './ProjectHostFileComparison';
import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, TextAreaField } from './forms-library';
import {ProjectTaskPackageOrigin,type TaskPackageOriginalWork} from './ProjectTaskPackageOrigin';
import { bridge } from './bridge';
import { ProjectCheckoutFiles, checkoutWebEntry, type CheckoutSavedPosition, type CheckoutSaveForBuild, type CheckoutWebEntry } from './ProjectCheckoutFiles';
import { ProjectSavedWebLink } from './ProjectSavedBuildWeb';
import { CheckoutGuide, checkoutFirstWebTools, checkoutKindSummary, checkoutNodeToolObservation } from './ProjectWorkBinding';
import { ProjectDraftChanges } from './ProjectDraftChanges';
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
type ImportedEditorSource = 'external_zip'|'saf_tree'|'task_source_package';
export type ProjectImportedEditor = {projectId:string; checkoutId:string; importIdentity:string; sourceKind:ImportedEditorSource; window:string};
export type ProjectLocalFilesIntent = {projectId:string; current:()=>boolean};
type Props = { returnTarget?:ProjectDraftReturn; onDismissReturn?:()=>void;
  buildReturn?:ProjectBuildCopy; onBuildReturnHandled?:(target:ProjectBuildCopy)=>void;
  importedEditor?:ProjectImportedEditor; onImportedEditorHandled?:(target:ProjectImportedEditor)=>void;
  localFilesIntent?:ProjectLocalFilesIntent; onLocalFilesIntentHandled?:(target:ProjectLocalFilesIntent)=>void;
  retainedOriginalVisit?:Data;
  projectId: string; name: string; active: boolean; run: (action: string, args?: Data) => Promise<any>; can: (action: string) => boolean;
  onOpenTask: (id: string, checkoutId?: string) => void; onOpenHostOriginal?: (id: string) => void; onOpenPackageOriginal?: (work: TaskPackageOriginalWork) => void; onTaskRegistered: () => void; onPrepareTools: () => void; onConnectModel: () => void; onOpenTerminal: (checkout?: LocalTerminalCheckout) => void;
  registerBack: (handler: (() => boolean) | null) => void; onInteractionBusyChange?: (busy: boolean) => void };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const unresolved = (value: Data | null) => !!value && ['pending', 'unknown', 'unconfirmed'].includes(value.status);
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const scopedBuildReceipt = (receipt: Data) => receipt.independentNodeBuild === true || !!(receipt.versionedBuildSourceDigest || receipt.editedBuildSourceDigest);
const copyIdentity = (value: unknown): value is string =>
  typeof value==='string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const sourceHash = (value: unknown): value is string => typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
type BuildPreview = {value:Data; viewHandle:string; window:string};
type RetainedCopyScope = {projectId:string; checkoutId:string};
type RetainedOriginalWarmVisit = RetainedCopyScope & {sourceTaskId:string; requestRevision:number};
type RetainedOriginalVisit = RetainedOriginalWarmVisit | (RetainedCopyScope & {viewHandle:string});
type RetainedOriginalDifference = {
  draftId:string; runId:string; epoch:number; path:string; before:string; after:string;
  beforeSha256:string; afterSha256:string; beforeBytes:number; afterBytes:number; beforeFromOwnerRevision:boolean;
};
type RetainedOriginalRead = {value:RetainedOriginalDifference; visit:RetainedOriginalVisit; viewHandle:string; window:string};
const sourceTaskKey=(value:unknown):value is string=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);
const sameRetainedVisit = (a:RetainedOriginalVisit,b:RetainedOriginalVisit) =>
  a.projectId===b.projectId&&a.checkoutId===b.checkoutId&&('viewHandle' in a
    ? 'viewHandle' in b&&a.viewHandle===b.viewHandle
    : !('viewHandle' in b)&&a.sourceTaskId===b.sourceTaskId&&a.requestRevision===b.requestRevision);

function keepOnlySelectedRetainedLink(page:Data|null,checkoutId:string):Data|null {
  const checkouts=rows(page?.checkouts);
  return page&&checkouts.some(row=>row?.retainedOriginalLinked===true&&row.checkoutId!==checkoutId)
    ? {...page,checkouts:checkouts.map(row=>row?.retainedOriginalLinked===true&&row.checkoutId!==checkoutId
        ? {...row,retainedOriginalLinked:false}:row)}:page;
}

function currentRetainedVisit(shown:Data|undefined,page:Data|null,projectId:string,checkoutId:string):RetainedOriginalVisit|null {
  const {state,view}=bridge.getState(),native=state.openedRetainedDraftCopy,window=(view||{}) as Data;
  if(document.hidden||window.section!==null||window.taskId!==null||state.filePath||state.projectId!==projectId)return null;
  // The ordinary project directory does not replay a retained-copy open. Only
  // this native page's positively linked, currently selected checkout can read it.
  let selected:RetainedOriginalVisit|null=null;
  if(page?.projectId===projectId&&page.executionAuthorized===false&&copyIdentity(page.viewHandle)&&copyIdentity(checkoutId)){
    const match=rows(page.checkouts).filter(row=>row?.projectId===projectId&&row.checkoutId===checkoutId);
    if(match.length===1&&match[0].draftWorkingCopy===true&&match[0].retainedOriginalLinked===true)
      selected={projectId,checkoutId,viewHandle:page.viewHandle};
  }
  if(state.requestedProjectId!==projectId||native?.canReadOriginalDraft!==true||
    native.projectId!==projectId||native.checkoutId!==checkoutId||!sourceTaskKey(native.sourceTaskId)||
    !Number.isSafeInteger(native.requestRevision)||native.requestRevision<1||
    native.requestRevision!==state.requestedProjectRevision)return selected;
  // If the already-open native visit remains live, the reader returns its old
  // receipt shape. It can expose the action only with the prior App visit or a separate Page link.
  if(!selected&&(shown?.canReadOriginalDraft!==true||!sameRetainedVisit(native,shown as RetainedOriginalWarmVisit)))return null;
  return {projectId,checkoutId,sourceTaskId:native.sourceTaskId,requestRevision:native.requestRevision};
}

function checkedRetainedOriginal(value:Data,visit:RetainedOriginalVisit):RetainedOriginalDifference {
  const scope='viewHandle' in visit?['viewHandle']:['sourceTaskId','requestRevision'];
  const fields=['format','readOnly','executionAuthorized','projectId','checkoutId','draftId','runId','epoch',
    'path','before','after','beforeSha256','afterSha256','beforeBytes','afterBytes','beforeFromOwnerRevision',...scope];
  if(value?.format!=='pocketworld.retained-copy-original-draft.v1'||value.readOnly!==true||value.executionAuthorized!==false||
    Object.keys(value).some(key=>!fields.includes(key))||!sameRetainedVisit(value as RetainedOriginalVisit,visit)||
    !sourceHash(value.draftId)||!sourceTaskKey(value.runId)||!Number.isSafeInteger(value.epoch)||value.epoch<1||
    typeof value.path!=='string'||!value.path.trim()||typeof value.beforeFromOwnerRevision!=='boolean'||
    !sourceHash(value.beforeSha256)||!sourceHash(value.afterSha256)||value.beforeSha256===value.afterSha256||
    typeof value.before!=='string'||value.before.length>65536||typeof value.after!=='string'||value.after.length>24576||
    value.before===value.after||!Number.isSafeInteger(value.beforeBytes)||value.beforeBytes<0||value.beforeBytes>65536||
    !Number.isSafeInteger(value.afterBytes)||value.afterBytes<0||value.afterBytes>24576||
    new TextEncoder().encode(value.before).byteLength!==value.beforeBytes||new TextEncoder().encode(value.after).byteLength!==value.afterBytes)
    throw Error('未能核对这份工作副本的本轮原改稿，没有显示其他文件。');
  return value as RetainedOriginalDifference;
}
const nodeScripts=['prebuild','build','postbuild'] as const;

function checkedBuildPreview(value:Data,projectId:string,checkoutId:string,sourceDigest:string) {
  const allowed=['format','projectId','checkoutId','sourceDigest','runtimeId','manager','scripts','editedCopy','existingCopy',
    'preservedProjectDraft','commandsAuthorized','executionAuthorized','readOnly','lockedDependencies','dependencyState'];
  const scripts=value?.scripts,lock=value?.lockedDependencies;
  if(value?.format!=='pocketworld.node-build-plan-preview.v1'||Object.keys(value).some(key=>!allowed.includes(key))||
    value.readOnly!==true||value.commandsAuthorized!==false||value.executionAuthorized!==false||
    value.projectId!==projectId||value.checkoutId!==checkoutId||value.sourceDigest!==sourceDigest||
    value.runtimeId!=='managed_process_v1'||value.existingCopy!==true||value.editedCopy!==false||
    typeof value.preservedProjectDraft!=='boolean'||!['npm','pnpm'].includes(value.manager)||
    !scripts||typeof scripts.build!=='string'||!scripts.build.trim()||
    Object.entries(scripts).some(([key,text])=>!nodeScripts.some(name=>name===key)||typeof text!=='string'||text.length>4096)||
    (Object.hasOwn(value,'lockedDependencies')?(!lock||lock.manager!==value.manager||lock.registry!=='https://registry.npmjs.org/'||
      lock.lockPath!==(value.manager==='npm'?'package-lock.json':'pnpm-lock.yaml')||!sourceHash(lock.lockSha256)||
      typeof lock.managerVersion!=='string'||!lock.managerVersion||!Number.isSafeInteger(lock.packages)||lock.packages<1||lock.packages>8192||
      !['current_packages_checked','needs_lock_review'].includes(value.dependencyState)||
      Object.keys(lock).some(key=>!['manager','managerVersion','registry','lockPath','lockSha256','packages'].includes(key))):
      Object.hasOwn(value,'dependencyState')))
    throw Error('没有取得这个副本的只读构建方案，未开始工作。');
  return value;
}

function NodeBuildLock({plan,preview=false}:{plan:Data;preview?:boolean}) {
  const lock=plan.lockedDependencies;
  if(!lock)return preview?<p>这份方案没有单独的锁定依赖准备步骤。</p>:null;
  return <><p>{plan.dependencyState==='current_packages_checked'?
    '已核对这份相同副本中实际存在的关键包，不需要重复准备。构建是否成功仍以本次实际结果为准。':
    preview?'本机还缺所需包，后续要先单独核对依赖准备。':'本机还缺所需包。下一步只审阅原锁与下载，不会开始构建。'}
    {' '}{lock.lockPath} 共 {lock.packages} 个锁条目（含其他平台）。准备时只向 npm 官方注册表请求，不读取用户包管理配置或执行安装脚本。</p>
    <details><summary>核对锁与本机工具</summary><dl className="pw-facts"><div><dt>工具</dt><dd>{plan.manager} {lock.managerVersion}</dd></div><div><dt>下载来源</dt><dd>{lock.registry}</dd></div><div><dt>锁文件 SHA-256</dt><dd>{lock.lockSha256}</dd></div></dl></details></>;
}

function NodeBuildScripts({plan}:{plan:Data}) {
  return <details><summary>查看这一版 package.json 的构建脚本</summary><dl className="pw-facts">{nodeScripts.filter(k=>typeof plan.scripts?.[k]==='string').map(k=><div key={k}><dt>{k}</dt><dd><code dir="ltr" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{plan.scripts[k]}</code></dd></div>)}</dl></details>;
}

/** The existing native catalog and checkout registration must both identify the import. */
export function originalImportedEditor(projectId:string):ProjectImportedEditor|null {
  const {state,view}=bridge.getState(), nativeView=view as Data|undefined;
  if(document.hidden||state.projectId!==projectId||state.filePath||!nativeView||nativeView.taskId||nativeView.section!=null)return null;
  const projects=rows(state.features?.workspace?.projects).filter(row=>row?.id===projectId);
  const origin=projects.length===1?projects[0].sourceImport:null;
  if(!copyIdentity(projectId)||!copyIdentity(origin?.clientId)||!sourceHash(origin?.packageSha256)||origin.executionAuthorityRestored!==false)return null;
  const matches=rows(state.features?.local?.checkouts).filter(row=>row?.projectId===projectId&&
    row.sourceImport?.clientId===origin.clientId&&row.sourceImport?.packageSha256===origin.packageSha256);
  if(matches.length!==1)return null;
  const copy=matches[0], source=copy.sourceImport;
  const sourceKind:unknown=source.sourceKind;
  // A collected Termux checkout can inherit sourceImport but is not the published fork.
  const collectedTaskCopy=sourceKind==='task_source_package'&&('collectedFromCheckoutId' in copy||'collectionOperationId' in copy);
  if(!copyIdentity(copy.id)||source.mode!=='new_project'||source.executionAuthorityRestored!==false||
    (sourceKind!=='external_zip'&&sourceKind!=='saf_tree'&&sourceKind!=='task_source_package')||
    !sourceHash(source.manifestIdentity)||!sourceHash(source.sourceEvidenceSha256)||
    collectedTaskCopy||['teamProjectCopy','skillExecutionCopy','isolatedGit','nativeGitImportPending'].some(key=>key in copy))return null;
  return {projectId,checkoutId:copy.id,importIdentity:JSON.stringify([origin.clientId,origin.packageSha256,source.manifestIdentity,source.sourceEvidenceSha256]),
    sourceKind,window:ownerWindow()};
}
export const sameImportedEditor = (target:ProjectImportedEditor, observed:ProjectImportedEditor|null) =>
  !!observed&&target.projectId===observed.projectId&&target.checkoutId===observed.checkoutId&&
  target.importIdentity===observed.importIdentity&&target.sourceKind===observed.sourceKind&&target.window===observed.window;
export const canEditImportedCheckout = (can:(action:string)=>boolean) =>
  ['projects.development.read','projects.development.files.open','projects.development.files.read',
    'projects.development.files.edit','projects.development.files.save'].every(can);
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
export function ProjectDevelopmentWorkspace({ returnTarget, onDismissReturn, buildReturn, onBuildReturnHandled, importedEditor, onImportedEditorHandled, localFilesIntent, onLocalFilesIntentHandled, retainedOriginalVisit, projectId, name, active, run, can, onOpenTask, onOpenHostOriginal, onOpenPackageOriginal, onTaskRegistered, onPrepareTools, onConnectModel, onOpenTerminal, registerBack, onInteractionBusyChange }: Props) {
  const savedWebLink = useContext(ProjectSavedWebLink);
  const [view, setView] = useState<Data | null>(null), [goal, setGoal] = useState(''), [checkout, setCheckout] = useState('');
  const returned=useRef(''),focusReturned=useRef(false),deliverySummary=useRef<HTMLSpanElement>(null);
  const [deliveryOpen,setDeliveryOpen]=useState(false);
  const [hostOrigin, setHostOrigin] = useState<Data | null>(null);
  const [hostFiles, setHostFiles] = useState<HostComparison | null>(null), [hostDiff, setHostDiff] = useState<HostDiff | null>(null);
  const [hostEditor, setHostEditor] = useState<HostEditorFile | null>(null);
  const visibleHostDiff = useRef<HostDiff | null>(null); visibleHostDiff.current = hostDiff;
  const closeHostDiff = () => { visibleHostDiff.current = null; setHostDiff(null); };
  const [requirements, setRequirements] = useState<DeliveryRequirements>(() => deliveryRequirements());
  const [source, setSource] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [submission, setSubmission] = useState<Data | null>(null);
  const [buildPreview,setBuildPreview]=useState<BuildPreview|null>(null), previewOpen=useRef(false);
  const [retainedRead,setRetainedRead]=useState<RetainedOriginalRead|null>(null), retainedOriginalOpen=useRef(false);
  const activeRetainedVisit=useRef<RetainedOriginalVisit|null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [expired, setExpired] = useState(false), [copyResult, setCopyResult] = useState<Data | null>(null);
  const [fileWindow,setFileWindow]=useState<{checkoutId:string;resumeSaved?:CheckoutSavedPosition;firstEntry?:CheckoutWebEntry;unselectedImport?:ProjectImportedEditor}|null>(null);
  const [lastSavedFile,setLastSavedFile]=useState<CheckoutSavedPosition|null>(null);
  const [importEditChoice,setImportEditChoice]=useState<{original:ProjectImportedEditor;viewHandle:string}|null>(null);
  const [importEditorNotice,setImportEditorNotice]=useState('');
  const buildReturnAnchor=useRef<HTMLElement>(null), buildStepSection=useRef<HTMLElement>(null), buildNextAction=useRef<HTMLButtonElement>(null), focusingBeforeDeparture=useRef(false);
  const buildAfterSave=useRef<{saved:CheckoutSavedPosition;viewHandle:string;window:string}|null>(null);
  const sourceRecoveryReturn=useRef<{window:string;viewHandle:string;seen:boolean}|null>(null);
  const fileCheckout=fileWindow?.checkoutId||'';
  const [buildOrigin,setBuildOrigin]=useState<ProjectBuildCopy|null>(null);
  const buildCheckout=buildOrigin?.checkoutId||'';
  const [buildCopyMissing,setBuildCopyMissing]=useState(false);
  const filesOpen=!!fileCheckout, filesBack = useRef<(() => boolean) | null>(null);
  const registerFilesBack = useCallback((handler: (() => boolean) | null) => { filesBack.current = handler; }, []);
  const alive = useRef(false), generation = useRef(0), entered = useRef<object | null>(null), handle = useRef(''), activeReview = useRef<Data | null>(null);
  const keptNativePick=useRef(false);const [sourceConflict,setSourceConflict]=useState(false);
  const sourceAttempt = useRef(''), readAttempt = useRef(''), touched = useRef(false), draft = useRef<DevelopmentDraft>({ goal: '', checkoutId: '', ...deliveryRequirements() });
  const latest = useRef({ run, can, active, localFilesIntent, onLocalFilesIntentHandled });
  latest.current = { run, can, active, localFilesIntent, onLocalFilesIntentHandled }; activeReview.current = review;
  const attemptedLocalFiles = useRef<ProjectLocalFilesIntent|null>(null);
  const preview=active&&buildPreview&&view?.viewHandle===buildPreview.viewHandle&&handle.current===buildPreview.viewHandle&&
    buildPreview.window===ownerWindow()&&buildPreview.value.projectId===projectId&&buildPreview.value.checkoutId===checkout&&
    source?.projectId===projectId&&source.checkoutId===checkout&&source.sourceDigest===buildPreview.value.sourceDigest&&
    bridge.getState().state.projectId===projectId&&!document.hidden?buildPreview.value:null;
  previewOpen.current=!!preview;
  const closeBuildPreview=()=>{previewOpen.current=false;setBuildPreview(null);};
  const retainedVisit=active&&view?.projectId===projectId&&view.executionAuthorized===false&&view.viewHandle===handle.current&&
    !view.draftRecoveryRequired&&!filesOpen&&!hostDiff&&!review&&!preview&&!unresolved(submission)&&!sourceConflict&&!buildCopyMissing&&
    can('projects.development.inspect')?currentRetainedVisit(retainedOriginalVisit,view,projectId,checkout):null;
  activeRetainedVisit.current=retainedVisit;
  const originalDifference=retainedRead&&retainedVisit&&retainedRead.viewHandle===handle.current&&
    retainedRead.window===ownerWindow()&&sameRetainedVisit(retainedRead.visit,retainedVisit)?retainedRead.value:null;
  retainedOriginalOpen.current=!!originalDifference;
  const closeRetainedOriginal=()=>{retainedOriginalOpen.current=false;setRetainedRead(null);};
  useEffect(()=>{if(retainedRead&&!originalDifference)setRetainedRead(null);},[retainedRead,originalDifference]);
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
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void, reject = (failure: unknown) => setError(failureText(failure))) => {
    const ownsProject = () => bridge.getState().state.projectId === projectId && !bridge.getState().state.filePath;
    if (!latest.current.active || entered.current || document.hidden || !ownsProject() || !latest.current.can(action)) return;
    const token = {}, window = ownerWindow(), sequence = generation.current;
    entered.current = token; setBusy(true); setError('');
    const current = () => alive.current && latest.current.active && entered.current === token && generation.current === sequence && ownerWindow() === window && ownsProject() && !document.hidden;
    try {
      // A retired comparison must not report its private read failure on the next page.
      const runHere=action==='projects.development.inspect'&&args.readOriginalDraft===true?bridge.command:latest.current.run;
      const value = await runHere(action, args);
      if (current()) { accept?.(value); return value as Data; }
      if (action === 'projects.development.read' && value?.viewHandle) void latest.current.run('projects.development.close', { viewHandle: value.viewHandle }).catch(() => {});
    }
    catch (failure) { if (current()) reject(failure); }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const read = () => {
    const readableNow = latest.current.active && !entered.current && !document.hidden && latest.current.can('projects.development.read');
    if (readableNow) readAttempt.current = ownerWindow();
    const request=latest.current.localFilesIntent;
    const openLocalFiles=readableNow&&request&&request!==attemptedLocalFiles.current&&request.projectId===projectId&&request.current()?request:null;
    if(openLocalFiles)attemptedLocalFiles.current=openLocalFiles;
    const pinned=returnTarget&&returned.current!==returnTarget.handle?returnTarget:null;
    const reading=invoke('projects.development.read', {projectId,...(pinned?{draftReturnHandle:pinned.handle}:{})}, value => {
    if (value.projectId !== projectId || typeof value.viewHandle !== 'string') throw new Error('没有取得这个原项目的页面，请重新读取。');
    if(pinned){
      if(value.openedDraftReturn!==pinned.handle||value.draft?.projectId!==pinned.projectId||value.draft?.checkoutId!==pinned.checkoutId||
          value.draft?.ownerPickedConvention?.checkoutId!==pinned.checkoutId||!Number.isSafeInteger(value.draft?.ownerPickedConvention?.revision)){
        void latest.current.run('projects.development.close',{viewHandle:value.viewHandle}).catch(()=>{});throw new Error('原项目草稿的定位没有得到确认，没有显示其他草稿。');
      }
      returned.current=pinned.handle;focusReturned.current=true;setDeliveryOpen(true);
    }
    handle.current = value.viewHandle; setView(value); sourceAttempt.current = ''; setSource(null); closeBuildPreview(); closeRetainedOriginal();
    const incoming = value.draft || {}, here = draft.current;
    if(value.draftRecoveryRequired!==true)setCopyResult(previous =>
      incoming.copyRequestId && (previous?.unconfirmed===true || previous?.requestId===incoming.copyRequestId) ? previous : null);
    const selectedCopy = incoming.ownerPickedConvention && (!here.constraints || keptNativePick.current) && (!here.checkoutId || here.checkoutId===incoming.checkoutId) &&
      here.goal === (incoming.goal||'') && here.deliverable === (incoming.deliverable||'') && here.completionCriteria === (incoming.completionCriteria||'');
    setSourceConflict(false);
    if (!touched.current || selectedCopy) {
      const retained = incoming, available = rows(value.checkouts);
      const selected = retained.checkoutId || (!openLocalFiles && available.length === 1 ? available[0].checkoutId : '');
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
    // The Files tap can only open the already-saved choice from this normal
    // Work read. An implicit/ambiguous choice stays in the existing chooser.
    if(openLocalFiles&&latest.current.localFilesIntent===openLocalFiles&&openLocalFiles.current()&&
      !pinned&&!buildReturn&&!importedEditor&&!fileCheckout&&value.executionAuthorized===false&&value.commandsAuthorized===false&&
      value.draftRecoveryRequired===false&&incoming.projectId===projectId&&!incoming.ownerPickedConvention&&!unresolved(incoming.submission)&&
      incoming.copyStatus==='ready'&&copyIdentity(incoming.copyRequestId)&&copyIdentity(incoming.checkoutId)&&
      draft.current.checkoutId===incoming.checkoutId&&canEditImportedCheckout(latest.current.can)){
      const checkouts=rows(value.checkouts), copy=checkouts[0];
      if(checkouts.length===1&&copy?.projectId===projectId&&copy.checkoutId===incoming.checkoutId&&
        copy.draftWorkingCopy===false&&copy.taskPackageSource===false&&copy.hostSource===false){
        setFileWindow({checkoutId:incoming.checkoutId});
      }
    }
    });
    return openLocalFiles?reading.finally(()=>latest.current.onLocalFilesIntentHandled?.(openLocalFiles)):reading;
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
    registerBack(() => { if (!latest.current.active) return false; if (visibleHostDiff.current) { closeHostDiff(); return true; } if (filesBack.current?.()) return true; if (entered.current) return true; if (activeReview.current) { dismiss(); return true; } if(previewOpen.current){closeBuildPreview();return true;} if(retainedOriginalOpen.current){closeRetainedOriginal();return true;} return false; });
    return () => registerBack(null);
  }, [registerBack]);
  useEffect(() => {
    alive.current = true; let owner = ownerWindow();
    const retire = () => {
      generation.current++; entered.current = null; buildAfterSave.current=null; sourceRecoveryReturn.current=null;
      const original = handle.current; handle.current = ''; sourceAttempt.current = ''; readAttempt.current = ''; activeReview.current = null; previewOpen.current=false; retainedOriginalOpen.current=false; activeRetainedVisit.current=null;
      if (alive.current) { closeHostDiff(); setHostEditor(null); setHostFiles(null); setHostOrigin(null); setView(null); setReview(null); setBuildPreview(null); setRetainedRead(null); setSource(null); setBusy(false); setFileWindow(null); setImportEditorNotice(''); }
      if (original) void latest.current.run('projects.development.close', { viewHandle: original }).catch(() => {});
    };
    // Switching the start method retires its window, never its plain draft or
    // the identity of a submitted operation. Returning performs a fresh read.
    if (!active) { retire(); return () => { alive.current = false; }; }
    const inspect = () => {
      const next = ownerWindow();
      if (next !== owner || document.hidden) { owner = next; retire(); }
      const recovery = sourceRecoveryReturn.current;
      if (recovery && recovery.viewHandle === handle.current) {
        const intake = bridge.getState().state.localSourceIntake;
        if (intake?.recoveryOnly && intake.window === recovery.window) recovery.seen = true;
        else if (recovery.seen && !intake?.window && !entered.current) {
          // Opening the original can return to this same project ID. Do not keep its pre-recovery empty view.
          sourceRecoveryReturn.current = null;
          void readRef.current();
        }
      }
      if (latest.current.active && !document.hidden && !handle.current && !entered.current && readAttempt.current !== next && latest.current.can('projects.development.read')) void readRef.current();
    };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    void readRef.current();
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, [projectId, active]);
  const picked = view?.draft?.ownerPickedConvention;
  const currentPicked = !sourceConflict && keptNativePick.current && picked && Number.isSafeInteger(picked.revision) && picked.revision>0 && picked.checkoutId===checkout &&
    requirements.constraints && view?.draft?.constraints===requirements.constraints ? picked : null;
  useEffect(()=>{
    const trigger=deliverySummary.current?.closest('button');
    if(!active||!view||!deliveryOpen||!focusReturned.current||!currentPicked||document.hidden||
      !trigger?.isConnected||trigger.closest('[hidden],[inert]'))return;
    // Restore to the original disclosure trigger, not a field inside its entering panel.
    focusReturned.current=false;trigger.focus({preventScroll:true});trigger.scrollIntoView({block:'nearest',behavior:'auto'});
  },[active,view,currentPicked,deliveryOpen]);
  const removePicked = () => { setRequirements(value=>({...value,constraints:''})); retain({...draft.current,constraints:''});
    setView(value=>value ? {...value,draft:{...value.draft,ownerPickedConvention:null}} : value); };
  const readable = can('projects.development.read');
  const available = rows(view?.checkouts);
  const recovery = view?.phoneSourceRecovery;
  const sourceRecovery = !available.length && recovery?.projectId===projectId && copyIdentity(recovery.actionId) &&
    sourceHash(recovery.planDigest) ? recovery : null;
  const openSourceRecovery = () => {
    const viewHandle = handle.current;
    if (!sourceRecovery || !viewHandle || viewHandle !== view?.viewHandle) return;
    void invoke('projects.localSource.open',{recovery:sourceRecovery},value=>{
      if (value.opened!==true || !copyIdentity(value.recoveryWindow)) throw Error('没有打开原导入核对窗口。');
      const intake = bridge.getState().state.localSourceIntake;
      sourceRecoveryReturn.current = {window:value.recoveryWindow,viewHandle,seen:intake?.recoveryOnly===true&&intake.window===value.recoveryWindow};
    });
  };
  const selectedCopy = available.find(row => row.projectId === projectId && row.checkoutId === checkout);
  const savedFileHere = selectedCopy && lastSavedFile?.projectId===projectId && lastSavedFile.checkoutId===checkout ? lastSavedFile : null;
  const differentBuildCopy=buildCheckout&&checkout!==buildCheckout&&available.find(row=>row.projectId===projectId&&row.checkoutId===buildCheckout);
  useEffect(() => { if (active && readable && !handle.current) void readRef.current(); }, [active, readable]);
  useEffect(() => {
    if (!active || !view || !checkout || busy || review || filesOpen || buildReturn || importedEditor || differentBuildCopy || unresolved(submission)) return;
    const viewHandle=view.viewHandle, key = `${viewHandle}|${projectId}|${checkout}`;
    if (sourceAttempt.current === key) return;
    sourceAttempt.current = key; setSource(null);
    void invoke('projects.development.inspect', { viewHandle, checkoutId: checkout }, value => {
      if (draft.current.checkoutId !== checkout) return;
      if (value.projectId !== projectId || value.checkoutId !== checkout) throw new Error('源码不属于这个所选副本，请重新读取。');
      setSource(value);
      // This existing inspection also sees an explicit choice made after a
      // multi-checkout page opened. Keep only its still-selected native link.
      if(value.retainedOriginalLinked===true&&value.retainedOriginalViewHandle===viewHandle)setView(current=>{
        if(!current||current.viewHandle!==viewHandle||handle.current!==viewHandle||draft.current.checkoutId!==checkout)return current;
        const checkouts=rows(current.checkouts), match=checkouts.filter(row=>row?.projectId===projectId&&row.checkoutId===checkout);
        if(match.length!==1||match[0].draftWorkingCopy!==true||match[0].retainedOriginalLinked===true)return current;
        return {...current,checkouts:checkouts.map(row=>row===match[0]?{...row,retainedOriginalLinked:true}:row)};
      });
    });
  }, [active, view, checkout, busy, review, filesOpen, buildReturn, importedEditor, differentBuildCopy, submission, projectId]);
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
  const preserveSelectionBeforeDeparture = async (displayed:DevelopmentDraft,open:()=>void) => {
    // An explicit departure from the displayed sole copy retains that exact
    // selection. Returning must not infer a copy from an updated list.
    if(displayed.checkoutId&&!displayedSavedDraft(displayed).checkoutId){
      const saved=await invoke('projects.development.draft',{viewHandle:handle.current,...displayed},value=>{
        if(value.saved!==true||value.projectId!==projectId||!sameDraft(draft.current,displayed))
          throw new Error('没有核对到原副本的选择，未离开项目。');
        touched.current=true;
      });
      if(!saved)return;
    }
    await verifyDeparture(displayed,displayed,open);
  };
  const openFromBuild = (open:()=>void) => {
    // App captures this stable owner-workspace focus key. The preparation
    // button itself can disappear once the tools/model are available.
    focusingBeforeDeparture.current=true;
    buildReturnAnchor.current?.focus({preventScroll:true});
    focusingBeforeDeparture.current=false;
    open();
  };
  const canViewToolSetup = can('setup.read') && can('setup.runtime.read') && can('projects.development.draft');
  const prepareTools = () => {
    if(disabled||entered.current||!canViewToolSetup||draft.current.checkoutId!==checkout||checkout&&!selectedCopy)return;
    void preserveSelectionBeforeDeparture({...draft.current},()=>openFromBuild(onPrepareTools));
  };
  const connectBuildModel = () => {
    if(disabled||entered.current||!selectedCopy||!can('projects.development.draft')||draft.current.checkoutId!==checkout)return;
    void preserveSelectionBeforeDeparture({...draft.current},()=>openFromBuild(onConnectModel));
  };
  const canPrepareTerminal = can('projects.development.draft') && can('connections.enter') &&
    can('local.describeCheckout') && can('local.terminal.review') && !!selectedCopy;
  const prepareTerminal = () => {
    if(disabled||entered.current||!canPrepareTerminal||draft.current.checkoutId!==checkout)return;
    const displayed={...draft.current};
    void preserveSelectionBeforeDeparture(displayed,()=>onOpenTerminal({projectId,checkoutId:displayed.checkoutId}));
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
  const copyUnconfirmed = copyResult?.unconfirmed === true ||
    !copyResult && !!view?.draft?.copyRequestId && view?.draft?.copyStatus !== 'ready';
  const interactionBusy = active && (busy || pending || !!review || !!preview || !!originalDifference || filesOpen || copyUnconfirmed && can('projects.development.queryCopy'));
  useEffect(() => { onInteractionBusyChange?.(interactionBusy); return () => onInteractionBusyChange?.(false); }, [interactionBusy, onInteractionBusyChange]);
  const closeFiles = (saved?:CheckoutSavedPosition) => {
    const original=fileWindow?.unselectedImport;
    const sameSave=saved?.projectId===projectId&&saved.checkoutId===fileCheckout&&active&&!document.hidden&&bridge.getState().state.projectId===projectId;
    buildAfterSave.current=null;
    if(sameSave)setLastSavedFile(saved);
    // The saved file can identify a suggested selection, never make one on return.
    setImportEditChoice(sameSave&&original?.checkoutId===saved.checkoutId&&view?.viewHandle===handle.current&&original.window===ownerWindow()?
      {original,viewHandle:handle.current}:null);
    setFileWindow(null); setHostEditor(null); setHostFiles(null); sourceAttempt.current = ''; setSource(null);
  };
  useEffect(()=>{
    if(!importedEditor||!active||!view||busy||entered.current||review||fileCheckout||buildReturn||handle.current!==view.viewHandle)return;
    onImportedEditorHandled?.(importedEditor);
    if(ownerWindow()!==importedEditor.window||document.hidden)return;
    const checkoutId=importedEditor.checkoutId;
    const sequence=generation.current;
    const originalIsCurrent=()=>alive.current&&latest.current.active&&generation.current===sequence&&handle.current===view.viewHandle&&
      sameImportedEditor(importedEditor,originalImportedEditor(projectId))&&canEditImportedCheckout(latest.current.can);
    if(!originalIsCurrent()||view.projectId!==projectId||view.executionAuthorized!==false||
       !rows(view.checkouts).some(copy=>copy.projectId===projectId&&copy.checkoutId===checkoutId&&
         (importedEditor.sourceKind!=='task_source_package'||copy.taskPackageSource===true))){
      setImportEditorNotice('原导入副本已不能核对，没有打开其他文件。');return;
    }
    const hasDifferentCopy=()=>[draft.current.checkoutId,view.draft?.checkoutId].some(id=>id&&id!==checkoutId);
    if(view.draftRecoveryRequired===true||unresolved(submission)||sourceConflict||hasDifferentCopy()){
      setImportEditorNotice('当前项目保留了其他副本选择或待核对的工作。请先核对，再明确选择原导入副本 '+checkoutId.slice(0,8)+'。');return;
    }
    const openCopy=(inspection?:Data)=>{
      if(!originalIsCurrent()||hasDifferentCopy())return;
      setImportEditorNotice('');setHostEditor(null);
      const firstEntry=checkoutWebEntry(inspection??null,projectId,checkoutId);
      if(inspection?.projectId===projectId&&inspection.checkoutId===draft.current.checkoutId&&inspection.checkoutId===checkoutId&&sourceHash(inspection.sourceDigest))
        setSource(inspection); // Existing observation can name a destination, not authorize a build after an edit.
      setFileWindow({checkoutId,firstEntry:firstEntry??undefined,
        unselectedImport:!draft.current.checkoutId&&!view.draft?.checkoutId?importedEditor:undefined});
    };
    // Only the existing read's exact entry can skip the same copy's file
    // list. Neither opening path changes the selected work or grants a build.
    if(latest.current.can('projects.development.inspect'))
      void invoke('projects.development.inspect',{viewHandle:view.viewHandle,checkoutId}).then(openCopy);
    else openCopy();
  },[importedEditor,onImportedEditorHandled,active,view,busy,review,fileCheckout,buildReturn,projectId,submission,sourceConflict]);
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
    sourceAttempt.current='';setSource(null);setHostEditor(null);setBuildOrigin(buildReturn);setFileWindow({checkoutId:buildReturn.checkoutId});
  },[buildReturn,onBuildReturnHandled,active,view,busy,review,fileCheckout,projectId]);
  const unfinishedInput=!!(goal||view?.draft?.goal||view?.draft?.ownerPickedConvention||deliveryFields.some(k=>requirements[k]||view?.draft?.[k]));
  const selectBuildCopy=()=>{
    if(!differentBuildCopy||unfinishedInput||disabled||!can('projects.development.draft'))return;
    const next={...draft.current,checkoutId:buildCheckout};
    void invoke('projects.development.draft',{viewHandle:handle.current,...next},value=>{
      if(value.saved!==true||value.projectId!==projectId)throw Error('原副本选择未确认，未开始工作。');
      touched.current=true;draft.current=next;setCheckout(next.checkoutId);
      setView(page=>keepOnlySelectedRetainedLink(page,next.checkoutId));
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
  const chooseCheckout = (value:string) => {
    setImportEditChoice(null);setImportEditorNotice('');
    const clear = !!currentPicked && checkout!==value;
    if(clear)setRequirements(last=>({...last,constraints:''}));
    if(checkout!==value)setView(last=>{
      const page=keepOnlySelectedRetainedLink(last,value);
      return clear&&page?{...page,draft:{...page.draft,ownerPickedConvention:null}}:page;
    });
    setCheckout(value); setBuildOrigin(null); setBuildCopyMissing(false); setSource(null); setHostOrigin(null); setHostFiles(null);
    sourceAttempt.current = ''; retain({ ...draft.current, checkoutId: value, ...(clear?{constraints:''}:{}) });
  };
  const editedImportCopy = () => {
    if(!importEditChoice||!latest.current.active||checkout||draft.current.checkoutId||view?.draft?.checkoutId||available.length<2||
      handle.current!==importEditChoice.viewHandle||view?.viewHandle!==importEditChoice.viewHandle||
      !sameImportedEditor(importEditChoice.original,originalImportedEditor(projectId)))return;
    return available.find(row=>row.projectId===projectId&&row.checkoutId===importEditChoice.original.checkoutId);
  };
  const editedCopy=editedImportCopy();
  const copy = (query: boolean) => {
    const action=query?'projects.development.queryCopy':'projects.development.prepare', expected=view?.draft?.copyRequestId;
    const state=bridge.getState().state;
    if(!active||entered.current||!view||!can(action)||document.hidden||state.projectId!==projectId||state.filePath||
      !query&&(disabled||copyUnconfirmed||copyResult?.partial===true))return;
    if(!query)setCopyResult({unconfirmed:true});
    return invoke(action,{viewHandle:handle.current},value=>{
      if(!value||value.projectId!==projectId||!copyIdentity(value.requestId)||!copyIdentity(value.checkoutId)||
        expected&&value.requestId!==expected||typeof value.found!=='boolean'||typeof value.partial!=='boolean'||value.replayAllowed!==false)
        throw Error('没有核对到原副本准备回执，未更换所选副本。');
      const raw=value.checkout;
      if(value.found&&(value.partial||!raw||raw.id!==value.checkoutId||raw.projectId!==projectId||raw.ownerDevelopmentRequestId!==value.requestId||
        typeof raw.name!=='string'||!Number.isSafeInteger(raw.createdAt)||raw.createdAt<1||raw.createdAt>8.64e15))
        throw Error('副本登记不属于原准备请求，未更换所选副本。');
      const prepared=value.found?{checkoutId:value.checkoutId,projectId,name:raw.name,createdAt:raw.createdAt}:null;
      setCopyResult({requestId:value.requestId,found:value.found,partial:value.partial,checkout:prepared});
      setView(previous=>previous?{...previous,draft:{...previous.draft,copyRequestId:value.requestId,copyStatus:prepared?'ready':'unknown'},
        checkouts:prepared&&!rows(previous.checkouts).some(row=>row.projectId===projectId&&row.checkoutId===prepared.checkoutId)?
          [...rows(previous.checkouts),prepared]:previous.checkouts}:previous);
      // Checking an older request is just observation. Only an explicit initial
      // prepare in an unselected project can present its new copy by default.
      if(prepared&&!query&&!draft.current.checkoutId&&!currentPicked)chooseCheckout(prepared.checkoutId);
    });
  };
  const start = () => {
    const selected = activeReview.current;
    if (!active || previewOpen.current || !selected || busy || expired || Number(selected.expiresAt) <= Date.now()) return;
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
  const importedEntry = selectedCopy && !differentBuildCopy ? checkoutWebEntry(source,projectId,checkout) : null;
  const versionBuild = versionBuildFor(source, projectId, checkout);
  const webTools = selectedCopy && !differentBuildCopy ? checkoutFirstWebTools(source,projectId,checkout) : null;
  const nodeTools = webTools?.kind==='node' ? webTools : null;
  const draftNodeTools = selectedCopy?.draftWorkingCopy===true && !differentBuildCopy
    ? checkoutNodeToolObservation(source,projectId,checkout) : null;
  const buildToolsBlocked = !!nodeTools && (!nodeTools.runtimeReady || nodeTools.missing.length>0);
  const missingPnpm = !!nodeTools?.missing.includes('pnpm');
  const builtInSetupUnavailable = view?.runtime?.runtimeId==='managed_process_v1' && view.runtime.preparationAvailable===false;
  const canReviewToolSetup = canViewToolSetup && can('setup.runtime.review') && can('setup.runtime.start');
  const nodeBlocked = source?.projectId===projectId && source?.checkoutId===checkout ? existingBuildBlocked(source.existingNodeBuildBlocked) : '';
  const buildLookupUnavailable = source?.existingNodeBuild === true ?
    source.existingNodeBuildUnavailable === true || source.existingNodeBuildTask && !versionBuild :
    source?.editedDraftCopy === true && (source.editedDraftBuildUnavailable === true || source.editedDraftBuild && !versionBuild);
  const buildStepVisible = !filesOpen && !pending && !sourceConflict && !buildCopyMissing && !view?.draftRecoveryRequired;
  const observedLockPath = source?.existingNodeBuild===true ? source.existingNodeBuildMissingLock :
    selectedCopy?.draftWorkingCopy===true && !versionBuild && !buildLookupUnavailable ? source?.draftCopyNodeBuildMissingLock : undefined;
  const missingRequiredLock = !!selectedCopy && !differentBuildCopy && source?.projectId===projectId && source.checkoutId===checkout &&
    sourceHash(source.sourceDigest) &&
    (observedLockPath==='package-lock.json'||observedLockPath==='pnpm-lock.yaml') ? observedLockPath : '';
  const firstBuild = !!(nodeTools || missingRequiredLock) && !versionBuild && !buildLookupUnavailable && can('projects.development.reviewDraftBuild');
  const firstBuildVisible = firstBuild && buildStepVisible;
  const showToolSetupByBuild = firstBuildVisible && !missingRequiredLock && buildToolsBlocked && !missingPnpm &&
    view?.runtime?.preparationAvailable===true && canReviewToolSetup;
  const modelStillNeeded = firstBuild && !buildToolsBlocked && view?.model?.configured!==true;
  const canPreviewBuild=view?.model?.configured===false&&source?.existingNodeBuild===true&&sourceHash(source.sourceDigest);
  const firstBuildAction = !firstBuildVisible || missingRequiredLock ? '' : showToolSetupByBuild ? 'tools' : buildToolsBlocked ? 'refresh' : modelStillNeeded ? canPreviewBuild?'preview':'model' : 'review';
  // The pre-editor missing-lock observation may be repaired by saving that exact root path.
  // Recheck the saved source before deciding what comes next; saving a lock is not dependency readiness.
  const canPrepareSavedBuild=!!((nodeTools||missingRequiredLock)&&checkout===fileCheckout&&!hostEditor&&!pending&&!sourceConflict&&!buildCopyMissing&&
    !view?.draftRecoveryRequired&&can('projects.development.inspect')&&can('projects.development.reviewDraftBuild'));
  const savedSource = source?.savedEditInSource;
  const checkedSave = savedFileHere && savedSource?.path===savedFileHere.path && savedSource.savedHash===savedFileHere.savedHash &&
    savedSource.sourceDigest===source?.sourceDigest && sourceHash(source?.sourceDigest) && typeof savedSource.matches==='boolean' ? savedSource : null;
  const sourceVersion = source?.projectId===projectId && source.checkoutId===checkout && sourceHash(source.sourceDigest) ? source.sourceDigest : '';
  const acceptedBuild = selectedCopy && !differentBuildCopy && submission?.status==='accepted' && submission.independentNodeBuild===true && submission.projectId===projectId &&
    submission.checkoutId===checkout && sourceHash(submission.versionedBuildSourceDigest) &&
    typeof submission.taskId==='string' && /^[A-Za-z0-9_-]{1,80}$/.test(submission.taskId) &&
    (!source || submission.versionedBuildSourceDigest===sourceVersion) ? submission : null;
  const versionWorkAtTop = buildStepVisible && !!versionBuild && source?.existingNodeBuild===true && can('tasks.open');
  const acceptedWorkAtTop = buildStepVisible && !versionBuild && !!acceptedBuild && can('tasks.open');
  const showMissingLock = buildStepVisible && !!missingRequiredLock && !versionWorkAtTop && !acceptedWorkAtTop;
  const exactBuildNext = !!(firstBuildAction || versionWorkAtTop || acceptedWorkAtTop || showMissingLock);
  const openSelectedCopyFiles = () => {
    if (!selectedCopy || draft.current.checkoutId!==selectedCopy.checkoutId || !can('projects.development.files.open')) return;
    setHostEditor(null); setFileWindow({checkoutId:selectedCopy.checkoutId});
  };
  const prepareSavedBuild = async ({position:saved,filesHandle,id}:CheckoutSaveForBuild) => {
    const viewHandle=handle.current, window=ownerWindow();
    if(!active||!canPrepareSavedBuild||!viewHandle||view?.viewHandle!==viewHandle||saved.projectId!==projectId||
      saved.checkoutId!==fileCheckout||draft.current.checkoutId!==saved.checkoutId||
      (missingRequiredLock&&saved.path!==missingRequiredLock)||document.hidden)
      throw Error('原副本或编辑窗口已变化，请先核对原文件。');
    const fresh=await invoke('projects.development.inspect',{viewHandle,checkoutId:saved.checkoutId,savedEdit:{filesHandle,id}},value=>{
      const observation=value.savedEditInSource;
      if(handle.current!==viewHandle||draft.current.checkoutId!==saved.checkoutId||value.projectId!==projectId||
        value.checkoutId!==saved.checkoutId||!sourceHash(value.sourceDigest)||observation?.sourceDigest!==value.sourceDigest||
        observation.path!==saved.path||observation.savedHash!==saved.savedHash||typeof observation.matches!=='boolean')
        throw Error('没有核对到这次原文件保存与当前源码，文件仍留在原编辑器。');
      buildAfterSave.current={saved,viewHandle,window};
      setLastSavedFile(saved); setImportEditChoice(null); setFileWindow(null); setHostEditor(null); setHostFiles(null);
      // The normal inspection already ran with exactly this page and save.
      // Invalidate as usual on changing copies, reopening files or returning from setup.
      sourceAttempt.current=`${viewHandle}|${projectId}|${saved.checkoutId}`;setSource(value);
    },failure=>{throw failure;});
    if(!fresh)throw Error('没有取得这次原保存的核对回执。');
  };
  useEffect(()=>{
    const next=buildAfterSave.current;
    if(!next)return;
    const current=()=>alive.current&&latest.current.active&&!document.hidden&&handle.current===next.viewHandle&&
      ownerWindow()===next.window&&draft.current.checkoutId===next.saved.checkoutId&&bridge.getState().state.projectId===next.saved.projectId;
    if(!current()){buildAfterSave.current=null;return;}
    if(filesOpen||busy||entered.current||!source&&!error)return;
    // The checked source came from the still-open exact editor; the ordinary
    // next-step controls decide what to offer once the editor is closed.
    const fresh=source?.projectId===projectId&&source?.checkoutId===checkout&&sourceHash(source?.sourceDigest);
    const frame=requestAnimationFrame(()=>{
      if(buildAfterSave.current!==next||!current()||savedFileHere!==next.saved)return;
      buildAfterSave.current=null;
      const action=fresh&&buildNextAction.current;
      if(action&&!action.disabled){
        action.focus({preventScroll:true});(buildStepSection.current||action).scrollIntoView({block:'start',behavior:'auto'});
      }else buildReturnAnchor.current?.focus({preventScroll:true});
    });
    return ()=>cancelAnimationFrame(frame);
  },[active,filesOpen,busy,error,source,projectId,checkout,savedFileHere]);
  const comparedBuild = buildOrigin && buildOrigin.projectId===projectId && buildOrigin.checkoutId===checkout &&
    source?.projectId===projectId && source.checkoutId===checkout && typeof source.sourceDigest==='string' &&
    /^[a-f0-9]{64}$/.test(source.sourceDigest) ? buildOrigin : null;
  const versionChanged = comparedBuild && comparedBuild.sourceDigest!==source?.sourceDigest;
  const previewNodeBuild=()=>{
    const viewHandle=handle.current,window=ownerWindow(),expectedSourceDigest=sourceVersion,shown={...draft.current};
    if(firstBuildAction!=='preview'||disabled||previewOpen.current||!viewHandle||view?.viewHandle!==viewHandle||
      !expectedSourceDigest||shown.checkoutId!==checkout)return;
    void invoke('projects.development.reviewDraftBuild',{viewHandle,checkoutId:checkout,retainedDraft:displayedSavedDraft(shown),
      previewOnly:true,expectedSourceDigest}, value=>{
      if(handle.current!==viewHandle||!sameDraft(draft.current,shown))throw Error('原副本或草稿已变化，未展示构建方案。');
      setBuildPreview({value:checkedBuildPreview(value,projectId,checkout,expectedSourceDigest),viewHandle,window});
    });
  };
  const readRetainedOriginal=()=>{
    const viewHandle=handle.current,window=ownerWindow(),seen=currentRetainedVisit(retainedOriginalVisit,view,projectId,checkout);
    if(disabled||retainedOriginalOpen.current||!retainedVisit||!seen||!sameRetainedVisit(retainedVisit,seen)||
      !viewHandle||view?.viewHandle!==viewHandle||draft.current.checkoutId!==checkout)return;
    const stillHere=()=>{
      const current=currentRetainedVisit(retainedOriginalVisit,view,projectId,checkout);
      const shown=activeRetainedVisit.current;
      return current&&shown&&sameRetainedVisit(seen,current)&&sameRetainedVisit(seen,shown)&&handle.current===viewHandle&&
        ownerWindow()===window&&draft.current.checkoutId===checkout;
    };
    void invoke('projects.development.inspect',{viewHandle,checkoutId:checkout,readOriginalDraft:true},value=>{
      if(stillHere())setRetainedRead({value:checkedRetainedOriginal(value,seen),visit:seen,viewHandle,window});
    },failure=>{if(stillHere())setError(failureText(failure));});
  };
  const reviewNodeBuild = () => {
    const viewHandle=handle.current;
    void invoke('projects.development.reviewDraftBuild',{
      viewHandle,checkoutId:checkout,retainedDraft:displayedSavedDraft({...draft.current}),
    },value=>{
      try { acceptReview(value,true); }
      catch (failure) {
        // Native has prepared this review even when it no longer matches the
        // displayed source. Release only that review so the original file can reopen.
        if(value.projectId===projectId&&value.checkoutId===checkout&&typeof value.reviewHandle==='string')
          void latest.current.run('projects.development.dismiss',{viewHandle,reviewHandle:value.reviewHandle}).catch(()=>{});
        throw failure;
      }
    });
  };
  const openVersionBuild = (destination:'work'|'savedWeb') => void invoke('projects.development.inspect', {viewHandle:handle.current, checkoutId:checkout}, fresh => {
    const current = versionBuildFor(fresh, projectId, checkout);
    if (fresh.projectId === projectId && fresh.checkoutId === checkout) setSource(fresh);
    if (!versionBuild || !current || fresh.sourceDigest !== source?.sourceDigest || fresh.existingNodeBuild !== source?.existingNodeBuild ||
        current.id !== versionBuild.id || current.runId !== versionBuild.runId || current.epoch !== versionBuild.epoch)
      throw new Error('副本版本或原任务已变化。没有打开另一版，请按更新后的记录选择。');
    if(destination==='work')onOpenTask(current.id, checkout);
    else if(!savedWebLink?.current?.({kind:'build',projectId,checkoutId:checkout,taskId:current.id,sourceDigest:fresh.sourceDigest,runId:current.runId,epoch:current.epoch}))
      throw new Error('网页版本区暂不可用，请回到原项目工作页再核对。没有打开其他网页。');
  });
  const canReviewSelectedBuild=!differentBuildCopy&&!!selectedCopy&&
    (selectedCopy.draftWorkingCopy===true||source?.existingNodeBuild===true)&&can('projects.development.reviewDraftBuild');
  const showBuildReviewFallback=canReviewSelectedBuild&&!versionBuild&&!firstBuild&&!acceptedWorkAtTop&&!buildLookupUnavailable;
  const originalDraftToolBlock=showBuildReviewFallback&&draftNodeTools&&(!draftNodeTools.runtimeReady||draftNodeTools.missing.length>0)?draftNodeTools:null;
  const prepareOriginalDraftTools=originalDraftToolBlock&&!originalDraftToolBlock.missing.includes('pnpm')&&canReviewToolSetup&&
    view?.runtime?.runtimeId==='managed_process_v1'&&view.runtime.preparationAvailable===true;
  const findSameSourceWeb=!!versionBuild&&canReviewSelectedBuild&&can('projects.development.inspect');
  const findSavedWeb=()=>{
    if(disabled||entered.current||!alive.current||!latest.current.active||!selectedCopy||
      selectedCopy.checkoutId!==draft.current.checkoutId||view?.projectId!==projectId||
      view?.executionAuthorized!==false||!handle.current||view?.viewHandle!==handle.current)return;
    if(findSameSourceWeb){openVersionBuild('savedWeb');return;}
    // The existing shelf checks the exact checkout again. Do not inspect the
    // current source or require it to have a build before looking for older saves.
    if(!savedWebLink?.current?.({kind:'checkout',projectId,checkoutId:selectedCopy.checkoutId}))
      setError('原副本的网页版本区暂不可用，没有读取其他副本。');
  };
  if(returnTarget&&returned.current!==returnTarget.handle)return <section hidden={!active} className="pw-project-development pw-settings-section" aria-label="核对原项目草稿">
    <p role="status">{error||'正在核对刚才写入的原项目草稿…'}</p>
    {error&&<Button variant="ghost" onClick={onDismissReturn}>收起定位，返回项目概览</Button>}
  </section>;
  return <section hidden={!active} className="pw-project-development pw-local-workspace pw-settings-section" aria-label="本机副本与默认模型">
    <header ref={buildReturnAnchor} tabIndex={-1} data-workspace-entry="project-first-build" className="pw-development-intro"
      onFocus={event=>{if(event.target===event.currentTarget&&active&&!focusingBeforeDeparture.current&&!document.hidden)event.currentTarget.scrollIntoView({block:'start',behavior:'auto'});}}>
      <h3>这台手机上的工作副本</h3><p>直接改文件不需要模型。构建或交办目标时，另行核对。</p>
    </header>
    {error && <p className="pw-local-inline-notice" role="alert">{error}</p>}
    {importEditorNotice && <p className="pw-local-inline-notice" role="status">{importEditorNotice}</p>}
    {buildCopyMissing&&<p className="pw-local-inline-notice" role="status">原构建所用的私人副本暂不可用，没有打开替代副本。</p>}
    {(exactBuildNext||checkedSave&&buildStepVisible)&&<section ref={buildStepSection} aria-label="这份私人副本的构建下一步">
      <p className="pw-field-hint">本机副本 <bdi>{selectedCopy?.name||name}</bdi> · <code>{checkout.slice(0,8)}</code>。
        {sourceVersion?<> 已保存源码版本 <code>{sourceVersion.slice(0,10)}</code>。</>:
          acceptedWorkAtTop?<> 已交办版本 <code>{acceptedBuild!.versionedBuildSourceDigest.slice(0,10)}</code>。</>:null}</p>
      {checkedSave&&<p className="pw-field-hint" role="status">{checkedSave.matches?
        <>这版包含刚确认保存的 <bdi>{checkedSave.path}</bdi>。</>:
        <>上次保存的 <bdi>{checkedSave.path}</bdi> 与当前版本不一致。若继续，只审阅上面显示的当前版本。</>}</p>}
      {(versionWorkAtTop||acceptedWorkAtTop)?<>
        <p className="pw-field-hint" role="status">{acceptedWorkAtTop?'构建交办已受理，尚不代表命令获准或运行。可在原工作中继续核对。':
          '这一版已有原构建工作。打开可查看实际进展、待确认命令和已保存成果。'}</p>
        {acceptedBuild?.error&&<p className="pw-field-hint">{acceptedBuild.error}</p>}
        <div className="pw-form-actions"><Button ref={buildNextAction} disabled={disabled||versionWorkAtTop&&!can('projects.development.inspect')}
          onClick={()=>versionWorkAtTop?openVersionBuild('work'):acceptedBuild&&onOpenTask(acceptedBuild.taskId,acceptedBuild.checkoutId)}>打开原构建工作</Button></div>
      </>:showMissingLock?<>
        <p className="pw-field-hint" role="status">这份源码声明了要用的依赖，但副本根目录没有 <bdi>{missingRequiredLock}</bdi>。先补齐同版本的锁文件，再核对构建；不会自动生成锁或安装依赖。</p>
        {can('projects.development.files.open')&&<div className="pw-form-actions"><Button ref={buildNextAction} disabled={disabled}
          onClick={openSelectedCopyFiles}>打开这份副本的文件</Button></div>}
      </>:nodeTools&&firstBuildAction&&<>
        <p className="pw-field-hint" role="status">{buildToolsBlocked?
          <>{nodeTools.missing.length?`尚未核对到 ${nodeTools.missing.map(tool=>tool==='node'?'Node.js':tool).join('、')}。`:'开发环境尚未核对为可运行。'}
            {missingPnpm?' 内置准备提供 Node.js 和 npm，不会用 npm 替代 pnpm。':
              builtInSetupUnavailable?' 当前安装包不能准备本机运行环境；文件仍可编辑。':
              showToolSetupByBuild?' 请先审阅手机工具的准备内容。返回后重新核对，不会自动构建。':' 这个窗口不能开始内置准备；仍可编辑文件。'}</>:
          firstBuildAction==='preview'?`已核对 Node.js 和 ${nodeTools.manager}。可以先看这版要运行的命令和依赖，不需要默认模型。`:
          firstBuildAction==='model'?`已核对 Node.js 和 ${nodeTools.manager}。构建任务还需要连接默认模型；连接不会开始构建。`:
          `已核对 Node.js 和 ${nodeTools.manager}，已有默认模型配置。下一步只看构建方案，依赖和运行仍要分别确认。`}</p>
        <div className="pw-form-actions">
          {firstBuildAction==='tools'&&<Button ref={buildNextAction} disabled={disabled} onClick={prepareTools}>{view?.runtime?.installed===true?'检查与修复手机工具':'打开手机工具准备'}</Button>}
          {firstBuildAction==='refresh'&&<Button ref={buildNextAction} variant="secondary" disabled={disabled} onClick={()=>void read()}>重新核对手机工具</Button>}
          {firstBuildAction==='preview'&&<Button ref={buildNextAction} disabled={disabled} onClick={previewNodeBuild}>查看本机构建方案</Button>}
          {firstBuildAction==='model'&&<Button ref={buildNextAction} disabled={disabled||!can('projects.development.draft')} onClick={connectBuildModel}>连接默认模型</Button>}
          {firstBuildAction==='review'&&<Button ref={buildNextAction} disabled={disabled} onClick={reviewNodeBuild}>{checkedSave?.matches===false?'核对当前版本的构建方案':'核对这个副本的构建方案'}</Button>}
        </div>
      </>}
    </section>}
    {!view ? <Button variant="secondary" disabled={busy || !readable} onClick={() => void read()}> {busy ? '正在读取原项目…' : '读取项目开发条件'} </Button> : <>
      {view.draftRecoveryRequired && <p className="pw-local-inline-notice" role="alert">{view.draftRecoveryMessage} 仍可查看下方原任务、文件和工具状态。请保留应用数据，从诊断记录定位草稿存储问题后恢复，不要清空数据或重复提交目标。</p>}
      {submission && !acceptedWorkAtTop && !(versionWorkAtTop && acceptedBuild?.taskId===versionBuild?.id) && <div className="pw-local-inline-notice" role="status"><p>{submission.message || (pending ? '原项目输入仍待核对。' : submission.status === 'accepted' ? '项目任务已保存。' : '上次输入没有启动。')}</p>
        {submission.status === 'accepted' && !scopedBuildReceipt(submission) && <p>继续同一件事时，请打开原任务补充，不必重新开始。</p>}
        {submission.error && <p>{submission.error}</p>}<div className="pw-form-actions">
          {submission.taskId && submission.taskId!==versionBuild?.id && <Button variant="secondary" disabled={busy || filesOpen || !can('tasks.open')} data-project-receipt-task={submission.taskId} onClick={() => onOpenTask(submission.taskId, submission.checkoutId)}>{scopedBuildReceipt(submission)?'查看上次交办的构建':'查看原任务与结果'}</Button>}
          {pending && <Button variant="secondary" disabled={busy || filesOpen} onClick={() => void invoke('projects.development.query', { viewHandle: handle.current }, receipt => applySubmission(receipt, submission))}>查询原输入</Button>}
        </div></div>}
      {sourceConflict&&view.draft?.ownerPickedConvention&&<div className="pw-local-inline-notice"><p>原草稿现在有另一次选择，本页还留着你的输入。两份都没有被自动覆盖。</p>
        {draft.current.checkoutId===view.draft.checkoutId&&<details><summary>对照本副本的两份限制</summary><p>本页普通文字</p><p dir="auto" className="pw-reading-copy">{draft.current.constraints||'（本页为空）'}</p><p>项目当前所选约定 · 修订 {view.draft.ownerPickedConvention.revision}</p><p dir="auto" className="pw-reading-copy">{view.draft.constraints}</p></details>}
        {draft.current.checkoutId===view.draft.checkoutId?<><Button variant="secondary" size="small" disabled={busy||pending} onClick={()=>reconcileSource(true)}>在本页使用项目当前约定</Button><Button variant="ghost" size="small" disabled={busy||pending} onClick={()=>reconcileSource(false)}>用本页普通文字替换草稿约定</Button></>:<p>工作副本不同，不展示另一份正文，也不能互相覆盖。请保留本页文字后，退出并打开原项目副本。</p>}</div>}
      <SelectField label="选择私人副本" value={checkout} isDisabled={disabled} options={[{ id: '', label: available.length ? '选择已有私人副本' : '尚未准备私人副本' },
        ...available.map(row => ({ id: row.checkoutId, label: `${row.name || name} · ${row.checkoutId.slice(0,8)} · ${new Date(row.createdAt || 0).toLocaleString()}` })),
        ...(checkout && !available.some(row => row.checkoutId === checkout) ? [{ id: checkout, label: '原副本暂不可用，请核对' }] : [])]}
        onChange={chooseCheckout} />
      {editedCopy&&can('projects.development.draft')&&<div className="pw-form-actions"><Button variant="ghost" size="small" disabled={disabled} onClick={()=>{
        if(!disabled&&!entered.current&&latest.current.can('projects.development.draft')&&editedImportCopy()?.checkoutId===editedCopy.checkoutId)chooseCheckout(editedCopy.checkoutId);
      }}>选我刚编辑的副本 · <bdi>{editedCopy.name||name}</bdi> · <code>{editedCopy.checkoutId.slice(0,8)}</code></Button></div>}
      {sourceRecovery ? <>
        <p className="pw-field-hint" role="status">原项目已登记，还没有核对到这次导入的工作副本。请先核对原导入，不另建空副本。</p>
        {can('projects.localSource.open') && <div className="pw-form-actions"><Button variant="secondary" disabled={!active || busy || !!review || filesOpen}
          onClick={openSourceRecovery}>核对原导入</Button></div>}
      </> : available.length === 0 && can('projects.development.prepare') && <div className="pw-form-actions"><Button variant="secondary" disabled={disabled || copyUnconfirmed || copyResult?.partial === true} onClick={() => void copy(false)}>准备这个项目的私人副本</Button></div>}
      {(view.draft?.copyRequestId || copyResult) && <div>
        {copyUnconfirmed&&<p className="pw-field-hint" role="status">{can('projects.development.queryCopy')?
          '副本准备结果还未核对。先查原请求，不会重复创建副本。':'副本准备结果未知，这个窗口暂时不能查询；不会重新创建副本。'}</p>}
        {can('projects.development.queryCopy')&&<Button variant="ghost" disabled={busy || !!review || filesOpen} onClick={() => void copy(true)}>查询原副本准备</Button>}
        {copyResult?.found===true&&copyResult.checkout&&<div role="status" className="pw-field-hint pw-checkout-identity">
          <p>已核对本机副本登记：<bdi>{copyResult.checkout.name||name}</bdi> · <code>{copyResult.checkout.checkoutId.slice(0,8)}</code>。
            {checkout===copyResult.checkout.checkoutId?' 当前选中这一份。':' 未切换当前副本或交办。'}</p>
          {checkout!==copyResult.checkout.checkoutId&&!currentPicked&&(!checkout||!unfinishedInput)&&<Button variant="secondary" size="small" disabled={disabled}
            onClick={()=>chooseCheckout(copyResult.checkout.checkoutId)}>选择这份副本</Button>}
        </div>}
        {copyResult?.partial && <div role="status"><p>原副本准备中断，部分文件已保留，不会覆盖。可在手动终端显式准备另一份完整副本，再返回选择；已有部分不会自动删除。</p><Button variant="secondary" disabled={busy || filesOpen} onClick={()=>onOpenTerminal()}>打开副本准备入口</Button></div>}
        {copyResult?.found===false && !copyResult.partial && <p role="status">未找到这次准备的副本。没有自动重试或切换工作位置。</p>}</div>}
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
      {!filesOpen && savedFileHere && <p className="pw-field-hint pw-checkout-identity">
        上次确认保存：<bdi>{savedFileHere.path}</bdi> · <code>{savedFileHere.savedHash.slice(0,10)}</code>。重开时会核对原文件，没有另存一份历史副本。
      </p>}
      {!filesOpen&&importedEntry&&<p className="pw-field-hint pw-checkout-identity">
        {importedEntry.root?<>副本顶层保留了 <bdi>{importedEntry.root}/</bdi>。入口源码：</>:<>副本顶层的入口源码：</>}
        <bdi>{importedEntry.path}</bdi>。{webTools?.kind==='static'?' 可直接编辑，不用准备 Node.js。':''}
        {' 预览请查看原任务已登记的页面，或打开「已保存的构建网页」。导入的源码不会直接运行。'}
      </p>}
      {!filesOpen&&!importedEntry&&webTools?.kind==='static'&&<p className="pw-field-hint">根目录有 <code>index.html</code>，直接编辑不用准备 Node.js。这个入口不是已登记的预览或构建成果。</p>}
      <div className="pw-form-actions">
        {importedEntry&&can('projects.development.files.open')&&<Button variant="secondary" disabled={busy||!!review||filesOpen}
          aria-label={`打开 ${importedEntry.path} 源码`} onClick={()=>{setHostEditor(null);setFileWindow({checkoutId:importedEntry.checkoutId,firstEntry:importedEntry});}}>
          打开入口源码
        </Button>}
        {selectedCopy && !showMissingLock && can('projects.development.files.open') && <Button variant={importedEntry?'ghost':'secondary'} disabled={busy || !!review || filesOpen}
          onClick={openSelectedCopyFiles}>{importedEntry?'编辑其他源文件':'编辑这份副本的文件'}</Button>}
        {savedFileHere && can('projects.development.files.open') && <Button variant="ghost" disabled={busy || !!review || filesOpen}
          onClick={() => { setHostEditor(null); setFileWindow({checkoutId:savedFileHere.checkoutId,resumeSaved:savedFileHere}); }}>回到上次保存的文件</Button>}
        {retainedVisit&&selectedCopy?.draftWorkingCopy===true&&<Button variant="ghost" disabled={disabled} onClick={readRetainedOriginal}>查看本轮原改稿</Button>}
        {canReviewSelectedBuild &&
          (versionBuild ? !versionWorkAtTop && can('tasks.open') && <Button ref={buildNextAction} variant="secondary" disabled={disabled || !can('projects.development.inspect')}
            onClick={()=>openVersionBuild('work')}>查看原构建任务</Button> :
          showBuildReviewFallback && (originalDraftToolBlock ? prepareOriginalDraftTools &&
            <Button ref={buildNextAction} variant="secondary" disabled={disabled} onClick={prepareTools}>
              {view.runtime?.installed===true?'检查与修复手机工具':'打开手机工具准备'}
            </Button> : <Button ref={buildNextAction} variant="secondary"
              disabled={disabled || buildToolsBlocked || source?.existingNodeBuild!==true && (!!goal || deliveryFields.some(k=>!!requirements[k])) || !source || view.model?.configured !== true}
              onClick={reviewNodeBuild}>{source?.existingNodeBuild===true?(versionChanged?'核对修改后的 Node 构建':'审阅这个副本的 Node 构建'):
                !draftNodeTools?'核对这份改稿的构建条件':source?.editedDraftCopy===true?'审阅当前编辑版的 Node 构建':'审阅这份改稿的 Node 构建'}</Button>))}
        {!differentBuildCopy&&selectedCopy&&savedWebLink&&<Button variant="ghost" disabled={disabled} onClick={findSavedWeb}>
          {findSameSourceWeb?'查找同一源码的已存网页':'查找这份副本的已存网页'}</Button>}
      </div>
      {originalDraftToolBlock && !filesOpen && <p role="status" className="pw-field-hint">
        {originalDraftToolBlock.missing.length?<>本机尚未核对到 {originalDraftToolBlock.missing.map(tool=>tool==='node'?'Node.js':tool).join('、')}。</>:
          '本机开发环境尚未核对为可运行。'}
        {originalDraftToolBlock.missing.includes('pnpm')&&' 内置工具准备不会用 npm 替代 pnpm。'} 仍可编辑这份改稿副本。
      </p>}
      {buildLookupUnavailable && <p role="status" className="pw-field-hint">这一版的构建记录暂未核对，请重新读取项目条件，不重复交办。</p>}
      {nodeBlocked && can('projects.development.reviewDraftBuild') && <p className="pw-field-hint" role="status">{nodeBlocked} 你仍可浏览和编辑这个副本。</p>}
      <TextAreaField label="这次的目标" value={goal} isDisabled={disabled} onChange={value => { if (value.length <= 12000) { setGoal(value); retain({ ...draft.current, goal: value }); } }} hint="例如：做一个能在手机预览的番茄钟，先用本地数据。不要填写密码或令牌。" />
      <Accordion expandedKeys={deliveryOpen?['development-delivery']:[]} onExpandedChange={keys=>setDeliveryOpen(keys.has('development-delivery'))}><AccordionItem id="development-delivery" title="交付要求" description={currentPicked
        ? <span ref={deliverySummary}>本项目手写约定已放入草稿 · 修订 {currentPicked.revision}</span>
        : deliveryFields.some(key => requirements[key].trim()) ? '已补充约束、交付物或完成标准' : '选填：约束、交付物与完成标准'}>
        {currentPicked && <div className="pw-local-inline-notice"><p>移除只影响这份草稿，不会改原约定或已交办的任务。</p>
          <Button variant="ghost" size="small" disabled={disabled} onClick={removePicked}>从本次草稿移除</Button></div>}
        <TextAreaField label="限制与约束" value={requirements.constraints} isDisabled={disabled} hint="例如：保留已有数据，沿用当前组件，每项最多 4000 字。"
          onChange={value => { if (value.length <= 4000) {
            if(currentPicked && value!==requirements.constraints)setView(previous=>previous?{...previous,draft:{...previous.draft,ownerPickedConvention:null}}:previous);
            setRequirements(previous => ({ ...previous, constraints: value })); retain({ ...draft.current, constraints: value }); } }} />
        <TextAreaField label="要交付什么" value={requirements.deliverable} isDisabled={disabled} hint="例如：可预览的页面和对应项目固定包。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, deliverable: value })); retain({ ...draft.current, deliverable: value }); } }} />
        <TextAreaField label="怎样算完成" value={requirements.completionCriteria} isDisabled={disabled} hint="写清你要看到的实际结果；发送或发布仍需原来的确认。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, completionCriteria: value })); retain({ ...draft.current, completionCriteria: value }); } }} />
      </AccordionItem></Accordion>
      <div className="pw-form-actions"><Button disabled={disabled || !goal.trim() || !checkout || !source || view.model?.configured !== true} onClick={() => void invoke('projects.development.review', { viewHandle: handle.current, checkoutId: checkout, goal, ...requirements }, acceptReview)}>核对并开始</Button>
        <Button variant="ghost" disabled={busy || pending || !!review || filesOpen} onClick={() => void read()}>重新读取项目条件</Button>
        {checkout && !source && <Button variant="ghost" disabled={disabled} onClick={() => { sourceAttempt.current = ''; setView(previous => previous ? { ...previous } : previous); }}>重新读取副本版本</Button>}
      </div>
      {!filesOpen&&archiveRoot&&<div className="pw-local-inline-notice" role="status">
        <p>工程入口或配置在 <bdi>{archiveRoot.descriptor}</bdi>，不在副本顶层。不会自动把里面的目录用作构建根。</p>
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
          disabled={busy||!!review||filesOpen} invoke={invoke} can={can} open={onOpenPackageOriginal}/>}
      {canPrepareTerminal && <div className="pw-form-actions"><Button variant="ghost" disabled={disabled} data-workspace-entry="project-copy-terminal"
        onClick={()=>void prepareTerminal()}>准备这份副本的终端</Button></div>}
      {view.model?.configured !== true && (!exactBuildNext || !['model','preview'].includes(firstBuildAction)&&!!goal.trim()) &&
        <p className="pw-local-inline-notice">交办目标需要默认模型；直接编辑文件、找回已保存的网页不需要。<Button variant={exactBuildNext?'ghost':'secondary'}
          disabled={busy || pending || !!review || filesOpen || exactBuildNext&&(disabled||!can('projects.development.draft'))}
          onClick={exactBuildNext?connectBuildModel:onConnectModel}>连接模型</Button></p>}
      <Accordion><AccordionItem id="development-conditions" title="运行位置与开发条件" description={androidWrapper ? '检测到 Android 配置；本机 APK 构建条件未核验' : checkoutKindSummary(source, projectId, checkout) || '可先编辑文件；运行命令前核对工具'}>
        <p>本次只绑定这台手机与所选副本，不会自动切换到远程设备。Linux 工具不是聊天引擎，不需要为本机开发开启手机操控权限。</p>
        {androidWrapper ? <AndroidBuildConditions wrapper={androidWrapper} runtime={view.runtime} /> : <p>安装依赖、构建或启动仍须核对具体方案；不会因为发现项目类型就运行脚本。</p>}
        <p>模型调用需要相应网络；已有文件和副本不会因离线而删除。退出页面不等于停止任务，重开请查看原任务。</p>
        {canViewToolSetup&&!showToolSetupByBuild&&!showMissingLock&&<Button variant="secondary" disabled={disabled||!!checkout&&!selectedCopy}
          data-workspace-entry="project-default-tools" onClick={prepareTools}>{androidWrapper ? '查看手机基础工具（不安装 SDK）' : '查看工具准备'}</Button>}
        {source && <dl className="pw-facts"><div><dt>原项目</dt><dd>{projectId}</dd></div><div><dt>私人副本</dt><dd>{checkout}</dd></div><div><dt>已读取版本</dt><dd>{source.sourceDigest}</dd></div><CheckoutGuide source={source} projectId={projectId} checkoutId={checkout}/></dl>}
      </AccordionItem></Accordion>
    </>}
    {active && !document.hidden && hostDiff && origin && hostDiff.viewHandle === view?.viewHandle && hostDiff.evidenceDigest === origin.evidenceDigest && <ProjectHostDiff diff={hostDiff} close={closeHostDiff} edit={can('projects.development.files.openHostChange') ? () => {
      if (visibleHostDiff.current !== hostDiff || handle.current !== hostDiff.viewHandle || document.hidden) return;
      const { path, evidenceDigest, originalSha256, phoneSha256 } = hostDiff; setHostEditor({ path, evidenceDigest, originalSha256, phoneSha256 });
      closeHostDiff(); setFileWindow({checkoutId:checkout});
    } : undefined}/>}
    {active && view && fileWindow && <ProjectCheckoutFiles key={`${view.viewHandle}|${fileCheckout}`} projectId={projectId} checkoutId={fileCheckout} viewHandle={view.viewHandle} run={run} hostFile={hostEditor || undefined}
      resumeSaved={fileWindow.resumeSaved} firstEntry={fileWindow.firstEntry} onClose={closeFiles}
      onPrepareBuild={canPrepareSavedBuild?prepareSavedBuild:undefined} prepareBuildAfterPath={missingRequiredLock||undefined} registerBack={registerFilesBack} />}
    {originalDifference&&<SettingsDialog isOpen title="本轮原改稿" closeLabel="返回当前工作副本" onClose={closeRetainedOriginal}>
      <div className="pw-project-draft">
        <strong className="pw-project-draft-path">{originalDifference.path}</strong>
        <p className="pw-field-hint">固定原改稿 <code>{originalDifference.afterSha256.slice(0,8)}</code>。只读，不包含当前工作副本的后续编辑。关闭仍留在当前副本。</p>
        <ProjectDraftChanges key={originalDifference.draftId} before={originalDifference.before} after={originalDifference.after}
          version={originalDifference.afterSha256.slice(0,8)} beforeLabel={originalDifference.beforeFromOwnerRevision?'本轮选中的已存稿':'本轮起点'}/>
      </div>
    </SettingsDialog>}
    <SettingsDialog isOpen={!!review||!!preview} title={preview?'本机构建方案':review?.draftBuild===true?review.dependencyState==='needs_lock_review'?'核对这版的依赖准备':review.existingCopy?'核对本机 Node 构建':review.editedCopy?'核对当前编辑版的构建':'核对这份改稿的本机构建':'开始这个项目任务？'} onClose={preview?closeBuildPreview:dismiss} closeLabel={preview||review?.draftBuild===true?'返回项目副本':'返回修改目标'}>{preview?<div className="pw-local-review">
      <p className="pw-field-hint">{name} · 副本 <code>{preview.checkoutId.slice(0,8)}</code> · 已保存版本 <code>{preview.sourceDigest.slice(0,10)}</code></p>
      <strong>{preview.manager} run build</strong>
      <NodeBuildLock plan={preview} preview/>
      <NodeBuildScripts plan={preview}/>
      <p>只查看这份副本，没有发送、下载或运行。{preview.preservedProjectDraft&&'你已写的目标留在草稿。'}提交构建交办才需要默认模型；实际命令仍须另行批准。</p>
      <div className="pw-form-actions">
        <Button variant="secondary" onClick={closeBuildPreview}>返回副本</Button>
        {view?.model?.configured===false&&can('projects.development.draft')&&<Button disabled={disabled} onClick={()=>{
          if(!previewOpen.current||!buildPreview||disabled||entered.current||document.hidden||handle.current!==buildPreview.viewHandle||
            ownerWindow()!==buildPreview.window||bridge.getState().state.projectId!==projectId||draft.current.checkoutId!==checkout)return;
          closeBuildPreview();connectBuildModel();
        }}>连接默认模型</Button>}
      </div>
    </div>:review && <div className="pw-local-review">
      {review.draftBuild === true ? <><strong>{review.dependencyState==='needs_lock_review'?'还缺依赖，先审阅这份锁':review.manager+' run build'}</strong>
        {review.existingCopy && <p className="pw-field-hint">只核对这个私人副本已保存的源码，不含未保存的文件修改。{review.preservedProjectDraft===true && '原项目目标与交付约定留在草稿，不随这次构建提交。'}</p>}
        {versionChanged && comparedBuild && <p className="pw-field-hint">原构建源码 <code>{comparedBuild.sourceDigest.slice(0,8)}</code>。本次核对下方的新版本，需要重新批准。</p>}
        {review.editedCopy && <p className="pw-field-hint">本次只构建这个副本当前保存的源码。原稿、旧构建和已保留的成品不变；旧输出不算本次新成果。</p>}
        <NodeBuildLock plan={review}/>
        {review.previousVerification?.id && <p className="pw-field-hint">此副本已有一项不含锁准备的原构建。审阅这把锁会另开一项工作，旧任务和授权都保留。
          {can('tasks.open')&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>onOpenTask(review.previousVerification.id,review.checkoutId)}>查看原构建记录</Button>}</p>}
        <p>提交会把构建目标交给下方的默认模型，可能产生用量。{review.dependencyState==='needs_lock_review'?'依赖准备和之后的构建命令分别等待你确认。':'构建命令仍要另行确认。'} 构建脚本可以更改此副本或联网。模型可见项目版本及之后明确获取的日志和成果记录；保留输出仍须另行确认。</p>
        <NodeBuildScripts plan={review}/></> : <p>{review.goal}</p>}
      {deliveryFields.some(key => review.deliveryContract?.[key]) && <dl className="pw-facts">
        {review.deliveryContract.constraints && <div><dt>{review.ownerPickedConventionRevision?'所选手写约定 · 修订 '+review.ownerPickedConventionRevision:'限制与约束'}</dt><dd>{review.deliveryContract.constraints}</dd></div>}
        {review.deliveryContract.deliverable && <div><dt>要交付什么</dt><dd>{review.deliveryContract.deliverable}</dd></div>}
        {review.deliveryContract.completionCriteria && <div><dt>怎样算完成</dt><dd>{review.deliveryContract.completionCriteria}</dd></div>}
      </dl>}
      <dl className="pw-facts"><div><dt>项目</dt><dd>{name}</dd></div><div><dt>私人副本</dt><dd>{review.checkoutId}</dd></div><div><dt>默认模型</dt><dd>{review.model?.label} · {review.model?.provider}</dd></div><div><dt>运行位置</dt><dd>{review.runtimeId === 'managed_process_v1' ? '这台手机 · 本机托管环境' : review.runtime}</dd></div><div><dt>源码版本</dt><dd>{review.source?.sourceDigest}</dd></div><CheckoutGuide source={review.source} projectId={projectId} checkoutId={review.checkoutId}/></dl>
      {review.draftBuild!==true&&<p>将目标交给默认模型，在新的项目任务中处理。不会批准任意命令、安装依赖、读取项目记忆或自动发布。具体文件修改、命令与成果采纳仍按原任务范围审阅。</p>}
      {expired && <p role="status">本次审阅已过期，还未启动。请返回重新核对。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={dismiss}>{review.draftBuild===true?'返回副本':'返回修改'}</Button><Button disabled={busy || expired} onClick={start}>{review.draftBuild===true?'提交构建交办':'开始这个项目任务'}</Button></div>
    </div>}</SettingsDialog>
  </section>;
}
