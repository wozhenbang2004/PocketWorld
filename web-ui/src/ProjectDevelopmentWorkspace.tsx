import type {ProjectDraftReturn} from './ProjectConventionDraftAction';
import type {LocalTerminalCheckout} from './LocalWorkspace';
import { checkedHostFiles, checkedHostText, ProjectHostComparedFiles, ProjectHostDiff, type HostComparedFile, type HostComparison, type HostDiff, type HostEditorFile } from './ProjectHostFileComparison';
import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, TextAreaField } from './forms-library';
import {ProjectTaskPackageOrigin,type TaskPackageOriginalWork} from './ProjectTaskPackageOrigin';
import { bridge } from './bridge';
import { savedPythonPath, type OriginalPythonRun } from './owner-python-run';
import type {DirectNodeDependencyPin} from './project-execution';
import { ProjectCheckoutFiles, checkoutWebEntry, type CheckoutBuildEditorResume, type CheckoutSavedPosition, type CheckoutPythonToolsDeparture, type CheckoutSaveForBuild, type CheckoutWebEntry } from './ProjectCheckoutFiles';
import { ProjectSavedWebLink } from './ProjectSavedBuildWeb';
import type { ProjectViteResultTarget } from './FirstNodeWebOutput';
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
export type ProjectBuildEditorResume = CheckoutBuildEditorResume & { taskId:string; runId:string; epoch:number; buildOperationId:string };
export type ProjectBuildCopy = { taskId:string; runId:string; epoch:number; projectId:string; checkoutId:string; sourceDigest:string; editorResume?:ProjectBuildEditorResume; pythonEditor?:OriginalPythonRun };
const builtinViteStarter='builtin_vite_starter';
type ImportedEditorSource = 'external_zip'|'saf_tree'|'task_source_package'|'git_https'|typeof builtinViteStarter;
export type ProjectImportedEditor = {projectId:string; checkoutId:string; importIdentity:string; sourceKind:ImportedEditorSource; window:string; exactPhoneSource?:true; exactStarterSource?:true; openDirectory?:true};
export type ProjectPreparedLocalCopy = {projectId:string;checkoutId:string;requestId:string};
export type ProjectLocalFilesIntent = {projectId:string; current:()=>boolean; originalCopy?:ProjectPreparedLocalCopy};
type FirstLocalFilesPreparation = {intent:ProjectLocalFilesIntent;viewHandle:string};
export type ProjectTerminalSavedFileReturn = {position:CheckoutSavedPosition; take:()=>boolean};
export type CheckoutPythonSetupReturn = {position:CheckoutSavedPosition; take:()=>boolean};
export type ProjectDependencyBuildReturn = {original:DirectNodeDependencyPin; comparisonHandle?:string; current:()=>boolean; take:()=>boolean};
export type ProjectViteToolsPosition = {projectId:string;checkoutId:string;sourceDigest:string};
export type ProjectViteToolsReturn = {position:ProjectViteToolsPosition;runtimeIdentity:string; current:()=>boolean; take:()=>boolean};
export type ProjectViteResultLookup = {
  projectId:string;current:()=>boolean;finish:(result?:ProjectViteResultTarget)=>void;
};
type Props = { returnTarget?:ProjectDraftReturn; onDismissReturn?:()=>void;
  buildReturn?:ProjectBuildCopy; onBuildReturnHandled?:(target:ProjectBuildCopy)=>void;
  importedEditor?:ProjectImportedEditor; onImportedEditorHandled?:(target:ProjectImportedEditor)=>void;
  localFilesIntent?:ProjectLocalFilesIntent; onLocalFilesIntentHandled?:(target:ProjectLocalFilesIntent)=>void;
  terminalSavedFile?:ProjectTerminalSavedFileReturn;
  pythonSetupReturn?:CheckoutPythonSetupReturn;
  dependencyBuildReturn?:ProjectDependencyBuildReturn;
  viteToolsReturn?:ProjectViteToolsReturn;
  viteResultLookup?:ProjectViteResultLookup;
  onPreparePythonTools?: (position: CheckoutSavedPosition) => void;
  retainedOriginalVisit?:Data;
  projectId: string; name: string; active: boolean; run: (action: string, args?: Data) => Promise<any>; can: (action: string) => boolean;
  onOpenTask: (id: string, checkoutId?: string, expected?: {runId:string;epoch:number}) => void; onOpenHostOriginal?: (id: string) => void; onOpenPackageOriginal?: (work: TaskPackageOriginalWork) => void; onTaskRegistered: () => void; onPrepareTools: (project?:ProjectViteToolsPosition) => void; onConnectModel: () => void; onOpenTerminal: (checkout?: LocalTerminalCheckout, position?:CheckoutSavedPosition) => void;
  registerBack: (handler: (() => boolean) | null) => void; onInteractionBusyChange?: (busy: boolean) => void };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const unresolved = (value: Data | null) => !!value && ['pending', 'unknown', 'unconfirmed'].includes(value.status);
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const scopedBuildReceipt = (receipt: Data) => receipt.independentNodeBuild === true || !!(receipt.versionedBuildSourceDigest || receipt.editedBuildSourceDigest);
const copyIdentity = (value: unknown): value is string =>
  typeof value==='string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const sourceHash = (value: unknown): value is string => typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
export function projectBuildEditorResume(value:unknown, copy:ProjectBuildCopy):ProjectBuildEditorResume|undefined {
  if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==9)return;
  const pin=value as Partial<ProjectBuildEditorResume>, {taskId,runId,epoch,projectId,checkoutId,sourceDigest}=copy;
  const {path,savedHash}=pin;
  if(!copyIdentity(taskId)||runId!==taskId||epoch!==1||pin.taskId!==taskId||pin.runId!==runId||pin.epoch!==epoch||
    pin.buildOperationId!==taskId||!copyIdentity(projectId)||!copyIdentity(checkoutId)||pin.projectId!==projectId||
    pin.checkoutId!==checkoutId||!sourceHash(sourceDigest)||pin.sourceDigest!==sourceDigest||!sourceHash(savedHash)||
    typeof path!=='string'||!path.trim()||path.length>240||/[\\:\u0000-\u001f]/.test(path)||
    path.split('/').some(part=>!part||part==='.'||part==='..'))return;
  return {taskId,runId,epoch,buildOperationId:taskId,projectId,checkoutId,sourceDigest,path,savedHash};
}
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
const reviewDirectBuild='projects.development.localBuild.review', confirmDirectBuild='projects.development.localBuild.confirm';
const pickSuppliedLock='projects.development.suppliedViteLock.pick', reviewSuppliedLock='projects.development.suppliedViteLock.review',
  confirmSuppliedLock='projects.development.suppliedViteLock.confirm', dismissSuppliedLock='projects.development.suppliedViteLock.dismiss';
const suppliedLockActions=[pickSuppliedLock,reviewSuppliedLock,confirmSuppliedLock,dismissSuppliedLock];
const confirmDirectDependencies='projects.development.localDependencies.confirm';
const confirmDependencyCopy='projects.development.localDependencies.confirmCopy';
const officialNpm='https://registry.npmjs.org/';
const dependencyFields=['format','requestId','runId','epoch','dependencyOperationId','projectId','checkoutId','sourceDigest',
  'planDigest','runtimeId','runtimeIdentity','manager','managerVersion','lockPath','lockSha256','registry','packages',
  'modelDispatchAllowed','replayAllowed'];
// Display only the fixed native npm plan. Confirmation sends no command or source arguments.
const lockedNpmArgs=['ci','--ignore-scripts','--no-audit','--no-fund','--workspaces=false','--include=dev','--include=optional',
  '--registry='+officialNpm,'--strict-ssl=true','--userconfig=./.pw-locked-npm-no-user-config',
  '--globalconfig=./.pw-locked-npm-no-global-config','--fetch-retries=0','--fetch-timeout=45000'];
type DirectBuildKind='compiler'|'vite';
const directOffer=(plan:Data|null)=>plan?.directViteBuild||plan?.directBuild;
const directOriginal=(plan:Data|null)=>plan?.directViteBuildWork||plan?.directBuildWork;
function directNodeWork(value:Data|undefined,projectId:string,checkoutId:string,sourceDigest:string,kind:DirectBuildKind='compiler') {
  const vite=kind==='vite';
  return value?.format===(vite?'pocketworld.owner-direct-vite-build.v1':'pocketworld.owner-direct-node-build.v1')&&copyIdentity(value.requestId)&&
    value.runId===value.requestId&&value.buildOperationId===value.requestId&&value.epoch===1&&
    value.projectId===projectId&&value.checkoutId===checkoutId&&value.sourceDigest===sourceDigest&&sourceHash(sourceDigest)&&
    value.runtimeId==='managed_process_v1'&&sourceHash(value.runtimeIdentity)&&sourceHash(value.planDigest)&&sourceHash(value.lockSha256)&&
    (vite?value.outputPath==='dist/index.html':typeof value.outputPath==='string'&&/^dist\/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\.js$/.test(value.outputPath))&&
    value.modelDispatchAllowed===false&&value.replayAllowed===false?value:null;
}
const directBuildRegistered=(value:Data|null)=>!!value&&['running','completed','failed','reconciling','paused','stopping','interrupted','awaiting_user','cancelled'].includes(value.status);
const sameDirectBuild=(a:Data,b:Data)=>a.requestId===b.requestId&&a.planDigest===b.planDigest&&
  a.runtimeIdentity===b.runtimeIdentity&&a.lockSha256===b.lockSha256&&a.outputPath===b.outputPath;

function directDependency(value:Data|undefined,projectId:string,checkoutId:string,sourceDigest:string,review=false){
  const extras=review?['reviewHandle','expiresAt','executionAuthorized','argv']:['status'];
  if(value?.format!=='pocketworld.owner-direct-node-dependencies.v1'||!copyIdentity(value.requestId)||
     value.runId!==value.requestId||value.dependencyOperationId!==value.requestId||value.epoch!==1||
     value.projectId!==projectId||value.checkoutId!==checkoutId||value.sourceDigest!==sourceDigest||!sourceHash(sourceDigest)||
     value.runtimeId!=='managed_process_v1'||!sourceHash(value.runtimeIdentity)||!sourceHash(value.planDigest)||
     value.manager!=='npm'||typeof value.managerVersion!=='string'||!/^11\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(value.managerVersion)||
     value.registry!==officialNpm||value.lockPath!=='package-lock.json'||!sourceHash(value.lockSha256)||
     !Number.isSafeInteger(value.packages)||value.packages<1||value.packages>8192||
     value.modelDispatchAllowed!==false||value.replayAllowed!==false||
     Object.keys(value).some(key=>!dependencyFields.includes(key)&&!extras.includes(key)))return null;
  if(!review)return directBuildRegistered(value)?value:null;
  return value.executionAuthorized===false&&copyIdentity(value.reviewHandle)&&Number.isSafeInteger(value.expiresAt)&&
    value.expiresAt>Date.now()&&Array.isArray(value.argv)&&value.argv.length===lockedNpmArgs.length&&
    lockedNpmArgs.every((arg,index)=>value.argv[index]===arg)?value:null;
}
const sameDependency=(a:Data,b:Data)=>dependencyFields.every(key=>a[key]===b[key]);

const dependencyCopyPin=['projectId','checkoutId','sourceDigest','lockSha256','newCheckoutId','name','fileCount','bytes'];
function checkedDependencyCopy(value:Data|undefined,projectId:string,checkoutId:string,sourceDigest:string,lockSha:unknown){
  if(value?.format!=='pocketworld.owner-node-dependency-checkout.v1'||value.projectId!==projectId||value.checkoutId!==checkoutId||
      value.sourceDigest!==sourceDigest||!sourceHash(sourceDigest)||value.lockSha256!==lockSha||!sourceHash(value.lockSha256)||
      !copyIdentity(value.newCheckoutId)||value.newCheckoutId===checkoutId||value.name!==`依赖副本 · ${sourceDigest.slice(0,8)}`||
      !Number.isSafeInteger(value.fileCount)||value.fileCount<1||value.fileCount>2000||
      !Number.isSafeInteger(value.bytes)||value.bytes<1||value.bytes>16*1024*1024||
      value.executionAuthorized!==false||value.switchedCheckout!==false||value.originalCheckoutUnchanged!==true||value.replayAllowed!==false)
    return null;
  const extra=value.state==='review'?['reviewHandle','expiresAt']:value.state==='published'?['createdAt']:[];
  if(Object.hasOwn(value,'reason')||Object.hasOwn(value,'previousLockSha256')){
    if(value.reason!=='current_lock_unverified'||!sourceHash(value.previousLockSha256)||value.previousLockSha256===value.lockSha256)return null;
    extra.push('reason','previousLockSha256');
  }
  if(Object.keys(value).some(key=>!dependencyCopyPin.includes(key)&&!extra.includes(key)&&
      !['format','state','executionAuthorized','switchedCheckout','originalCheckoutUnchanged','replayAllowed'].includes(key)))return null;
  if(value.state==='review')return copyIdentity(value.reviewHandle)&&Number.isSafeInteger(value.expiresAt)&&value.expiresAt>Date.now()?value:null;
  if(value.state==='published')return Number.isSafeInteger(value.createdAt)&&value.createdAt>0?value:null;
  return value.state==='partial'?value:null;
}

function checkedBuildPreview(value:Data,projectId:string,checkoutId:string,sourceDigest:string) {
  const allowed=['format','projectId','checkoutId','sourceDigest','runtimeId','manager','scripts','editedCopy','existingCopy',
    'preservedProjectDraft','commandsAuthorized','executionAuthorized','readOnly','lockedDependencies','dependencyState',
    'directBuild','directBuildWork','directBuildKind','directViteBuild','directViteBuildWork','directDependencies','directDependencyWork','dependencyCopy'];
  const scripts=value?.scripts,lock=value?.lockedDependencies;
  if(value?.format!=='pocketworld.node-build-plan-preview.v1'||Object.keys(value).some(key=>!allowed.includes(key))||
    value.readOnly!==true||value.commandsAuthorized!==false||value.executionAuthorized!==false||
    value.projectId!==projectId||value.checkoutId!==checkoutId||value.sourceDigest!==sourceDigest||
    value.runtimeId!=='managed_process_v1'||value.existingCopy!==true||value.editedCopy!==false||
    typeof value.preservedProjectDraft!=='boolean'||!['npm','pnpm'].includes(value.manager)||
    !scripts||typeof scripts.build!=='string'||!scripts.build.trim()||
    Object.entries(scripts).some(([key,text])=>!nodeScripts.some(name=>name===key)||typeof text!=='string'||text.length>4096)||
    (Object.hasOwn(value,'lockedDependencies')?(!lock||lock.manager!==value.manager||lock.registry!==officialNpm||
      lock.lockPath!==(value.manager==='npm'?'package-lock.json':'pnpm-lock.yaml')||!sourceHash(lock.lockSha256)||
      typeof lock.managerVersion!=='string'||!lock.managerVersion||!Number.isSafeInteger(lock.packages)||lock.packages<1||lock.packages>8192||
      !['current_packages_checked','needs_lock_review'].includes(value.dependencyState)||
      Object.keys(lock).some(key=>!['manager','managerVersion','registry','lockPath','lockSha256','packages'].includes(key))):
      Object.hasOwn(value,'dependencyState')))
    throw Error('没有取得这个副本的只读构建方案，未开始工作。');
  const vite=value.directBuildKind==='vite';
  const noHooks=!Object.hasOwn(scripts,'prebuild')&&!Object.hasOwn(scripts,'postbuild');
  // Vite compatibility is a positive native fact, not detected from script text.
  const runnableScript=value.manager==='npm'&&noHooks&&(vite||scripts.build==='tsc');
  if(Object.hasOwn(value,'directBuildKind')&&!vite||vite&&(!runnableScript||!lock||Object.hasOwn(value,'directBuild')||Object.hasOwn(value,'directBuildWork'))||
     !vite&&(Object.hasOwn(value,'directViteBuild')||Object.hasOwn(value,'directViteBuildWork')))
    throw Error('未核对到这份副本的原本机构建类型。');
  const buildKey=vite?'directViteBuild':'directBuild', workKey=vite?'directViteBuildWork':'directBuildWork', kind=vite?'vite':'compiler';
  const offer=directNodeWork(value[buildKey],projectId,checkoutId,sourceDigest,kind), prior=directNodeWork(value[workKey],projectId,checkoutId,sourceDigest,kind);
  if(Object.hasOwn(value,buildKey)&&(!offer||Object.hasOwn(value,workKey)||!runnableScript||value.dependencyState!=='current_packages_checked'||
      offer.lockSha256!==lock?.lockSha256||offer.executionAuthorized!==false||!copyIdentity(offer.reviewHandle)||
      !Number.isSafeInteger(offer.expiresAt)||offer.expiresAt<=Date.now())||
     Object.hasOwn(value,workKey)&&(!directBuildRegistered(prior)||vite&&prior?.lockSha256!==lock?.lockSha256))
    throw Error('没有核对到这份源码的本机构建审阅，没有开始。');
  const matchesLock=(dependency:Data|null)=>!!dependency&&runnableScript&&
    ['manager','managerVersion','registry','lockPath','lockSha256','packages'].every(key=>dependency[key]===lock?.[key]);
  if(Object.hasOwn(value,'directDependencies')&&(!matchesLock(directDependency(value.directDependencies,projectId,checkoutId,sourceDigest,true))||
      value.dependencyState!=='needs_lock_review'||Object.hasOwn(value,'directDependencyWork')||offer||prior))
    throw Error('没有核对到这份副本的锁定依赖审阅，没有开始。');
  if(Object.hasOwn(value,'directDependencyWork')){
    const work=directDependency(value.directDependencyWork,projectId,checkoutId,sourceDigest);
    if(!work||!matchesLock(work)||(offer||prior)&&(work.status!=='completed'||
        work.requestId===(offer||prior)?.requestId||work.planDigest===(offer||prior)?.planDigest))
      throw Error('没有核对到这份副本的原依赖准备记录。');
  }
  if(Object.hasOwn(value,'dependencyCopy')){
    const copy=checkedDependencyCopy(value.dependencyCopy,projectId,checkoutId,sourceDigest,lock?.lockSha256);
    if(!vite||!copy||value.directDependencies||value.directDependencyWork||
        (copy.reason==='current_lock_unverified'?(!offer&&prior?.status!=='completed')||value.dependencyState!=='current_packages_checked':
          !!offer||!!prior||value.dependencyState!=='needs_lock_review'))
      throw Error('没有核对到这版锁和原副本的新依赖副本位置。');
  }
  return value;
}

const noDirectDependencyStep=(plan:Data)=>plan.dependencyState==='needs_lock_review'&&
  !plan.directDependencies&&!plan.directDependencyWork&&!plan.dependencyCopy&&!directOffer(plan)&&!directOriginal(plan);

function NodeBuildLock({plan,preview=false}:{plan:Data;preview?:boolean}) {
  const lock=plan.lockedDependencies;
  if(!lock)return preview?<p>这份方案没有单独的锁定依赖准备步骤。</p>:null;
  const offer=preview&&plan.directDependencies, prior=preview&&plan.directDependencyWork&&!directOffer(plan)&&!directOriginal(plan);
  const historical=preview&&plan.dependencyCopy?.reason==='current_lock_unverified';
  const lockCount=<>{lock.lockPath} 共 {lock.packages} 个锁条目（含其他平台）。</>;
  return <>{historical&&<p>所选准备记录核对的是旧版锁，不是当前已保存的锁。原副本仍保留；也可以另建副本，按这版锁准备。</p>}
    {!prior&&!(preview&&plan.dependencyCopy)&&<p>{offer?'本机还缺所需包。确认后按锁从 npm 官方注册表下载，不读取用户包管理配置或执行安装脚本。':
    preview&&noDirectDependencyStep(plan)?lockCount:<>
    {plan.dependencyState==='current_packages_checked'?'已核对这份相同副本中实际存在的关键包，不需要重复准备。构建是否成功仍以本次实际结果为准。':
    preview?'本机还缺所需包，后续要先单独核对依赖准备。':'本机还缺所需包。下一步只审阅原锁与下载，不会开始构建。'}
    {' '}{lockCount}准备时只向 npm 官方注册表请求，不读取用户包管理配置或执行安装脚本。</>}</p>}
    <details><summary>核对锁与本机工具</summary><dl className="pw-facts"><div><dt>工具</dt><dd>{plan.manager} {lock.managerVersion}</dd></div><div><dt>下载来源</dt><dd>{lock.registry}</dd></div>
      {(offer||prior)&&<div><dt>原锁文件</dt><dd>{lockCount}</dd></div>}
      <div><dt>锁文件 SHA-256</dt><dd>{lock.lockSha256}</dd></div>
      {offer&&<div><dt>命令</dt><dd><code dir="ltr" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>npm {offer.argv.join(' ')}</code></dd></div>}</dl></details></>;
}

function NodeBuildScripts({plan}:{plan:Data}) {
  return <details><summary>查看这一版 package.json 的构建脚本</summary><dl className="pw-facts">{nodeScripts.filter(k=>typeof plan.scripts?.[k]==='string').map(k=><div key={k}><dt>{k}</dt><dd><code dir="ltr" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{plan.scripts[k]}</code></dd></div>)}</dl></details>;
}

/** The existing native catalog and checkout registration must both identify the import. */
export function originalImportedEditor(projectId:string):ProjectImportedEditor|null {
  const {state,view,capabilities}=bridge.getState(), nativeView=view as Data|undefined;
  if(document.hidden||state.projectId!==projectId||state.filePath||!nativeView||nativeView.taskId||nativeView.section!=null)return null;
  const projects=rows(state.features?.workspace?.projects).filter(row=>row?.id===projectId);
  const origin=projects.length===1?projects[0].sourceImport:null;
  if(!copyIdentity(projectId)||!copyIdentity(origin?.clientId)||!sourceHash(origin?.packageSha256)||origin.executionAuthorityRestored!==false)return null;
  const matches=rows(state.features?.local?.checkouts).filter(row=>row?.projectId===projectId&&
    row.sourceImport?.clientId===origin.clientId&&row.sourceImport?.packageSha256===origin.packageSha256);
  if(matches.length!==1)return null;
  const copy=matches[0], source=copy.sourceImport;
  const sourceKind:unknown=source.sourceKind;
  // A collected Termux checkout can inherit sourceImport, including native Git provenance.
  const starter=sourceKind===builtinViteStarter;
  const collectedCopy=(sourceKind==='task_source_package'||sourceKind==='git_https'||starter)&&('collectedFromCheckoutId' in copy||'collectionOperationId' in copy);
  if(!copyIdentity(copy.id)||source.mode!=='new_project'||source.executionAuthorityRestored!==false||
    (sourceKind!=='external_zip'&&sourceKind!=='saf_tree'&&sourceKind!=='task_source_package'&&sourceKind!=='git_https'&&sourceKind!==builtinViteStarter)||
    !sourceHash(source.manifestIdentity)||!sourceHash(source.sourceEvidenceSha256)||
    starter&&('ownerDraftWorkCopy' in copy||!capabilities.commands.includes('projects.development.inspect'))||
    collectedCopy||['teamProjectCopy','skillExecutionCopy','isolatedGit','nativeGitImportPending'].some(key=>key in copy))return null;
  const identity=[origin.clientId,origin.packageSha256,source.manifestIdentity,source.sourceEvidenceSha256];
  if(sourceKind==='git_https'){
    const git=source.gitOriginal;
    if(state.projectGitImport?.open||source.gitHistoryCopied!==true||source.credentialsRestored!==false||
      !copyIdentity(git?.actionId)||typeof git.commitId!=='string'||!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(git.commitId)||
      git.sourceManifestIdentity!==source.manifestIdentity||git.executionAuthorityRestored!==false||
      !sourceHash(git.manifestSha256)||!sourceHash(git.gitMetadataDigest))return null;
    identity.push(git.actionId,git.commitId,git.manifestSha256,git.gitMetadataDigest);
  }
  // The native registered phone publication has exactly these seven source fields.
  // A host/device source or a derived copy may still be browsable but is not this original.
  const exactLocalPublication=Object.keys(source).length===7&&
    !['ownerDevelopmentRequestId','ownerDraftWorkCopy','collectedFromCheckoutId','collectionOperationId'].some(key=>key in copy);
  return {projectId,checkoutId:copy.id,importIdentity:JSON.stringify(identity),sourceKind,window:ownerWindow(),
    ...(exactLocalPublication&&(sourceKind==='external_zip'||sourceKind==='saf_tree')?{exactPhoneSource:true as const}:{}),
    ...(exactLocalPublication&&starter?{exactStarterSource:true as const}:{})};
}
export const sameImportedEditor = (target:ProjectImportedEditor, observed:ProjectImportedEditor|null) =>
  !!observed&&target.projectId===observed.projectId&&target.checkoutId===observed.checkoutId&&
  target.importIdentity===observed.importIdentity&&target.sourceKind===observed.sourceKind&&
  target.exactPhoneSource===observed.exactPhoneSource&&target.exactStarterSource===observed.exactStarterSource&&target.window===observed.window;
export const canEditImportedCheckout = (can:(action:string)=>boolean) =>
  ['projects.development.read','projects.development.files.open','projects.development.files.read',
    'projects.development.files.edit','projects.development.files.save'].every(can);

/** A persisted checkout registration is a locator, not the saved owner choice.
 * After the tap the normal Work read must still prove the original selection. */
export function originalPreparedLocalCopy(projectId:string):ProjectPreparedLocalCopy|null {
  const {state,view,capabilities}=bridge.getState(), window=view as Data|undefined;
  if(document.hidden||!copyIdentity(projectId)||state.projectId!==projectId||state.filePath||state.fileLoading||state.fileSaving||
    state.conversationReader||state.localSourceIntake?.window||state.projectGitImport?.open||
    window?.section!==null||window.taskId!==null||rows(state.projects).filter(row=>row?.id===projectId).length!==1||
    !canEditImportedCheckout(action=>capabilities.commands.includes(action)))return null;
  const checkouts=rows(state.features?.local?.checkouts).filter(row=>row?.projectId===projectId),copy=checkouts[0];
  if(checkouts.length!==1||!copyIdentity(copy.id)||!copyIdentity(copy.ownerDevelopmentRequestId)||!Array.isArray(copy.files)||
    ['ownerDraftWorkCopy','sourceImport','teamProjectCopy','skillExecutionCopy','isolatedGit','nativeGitImportPending',
      'collectedFromCheckoutId','collectionOperationId'].some(key=>key in copy))return null;
  return {projectId,checkoutId:copy.id,requestId:copy.ownerDevelopmentRequestId};
}

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
export function ProjectDevelopmentWorkspace({ returnTarget, onDismissReturn, buildReturn, onBuildReturnHandled, importedEditor, onImportedEditorHandled, localFilesIntent, onLocalFilesIntentHandled, terminalSavedFile, pythonSetupReturn, dependencyBuildReturn, viteToolsReturn, viteResultLookup, onPreparePythonTools, retainedOriginalVisit, projectId, name, active, run, can, onOpenTask, onOpenHostOriginal, onOpenPackageOriginal, onTaskRegistered, onPrepareTools, onConnectModel, onOpenTerminal, registerBack, onInteractionBusyChange }: Props) {
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
  const [buildPreview,setBuildPreview]=useState<BuildPreview|null>(null), previewOpen=useRef(false), heldPreview=useRef<BuildPreview|null>(null);
  const [retainedRead,setRetainedRead]=useState<RetainedOriginalRead|null>(null), retainedOriginalOpen=useRef(false);
  const activeRetainedVisit=useRef<RetainedOriginalVisit|null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [expired, setExpired] = useState(false), [copyResult, setCopyResult] = useState<Data | null>(null);
  const [fileWindow,setFileWindow]=useState<{checkoutId:string;resumeSaved?:CheckoutSavedPosition;firstEntry?:CheckoutWebEntry;buildResume?:CheckoutBuildEditorResume;pythonResume?:OriginalPythonRun;
    pythonSetupResume?:CheckoutSavedPosition;originalImport?:ProjectImportedEditor;unselectedImport?:ProjectImportedEditor;reopenedFiles?:true}|null>(null);
  const [lastSavedFile,setLastSavedFile]=useState<CheckoutSavedPosition|null>(null);
  // A UI correlation so a new mount cannot adopt a system picker choice. This never grants a file read or write.
  const [lockVisit,setLockVisit]=useState<string|null>(null);
  useEffect(()=>{if(!active)setLockVisit(null);},[active,projectId]);
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
  const attemptedViteResult=useRef<ProjectViteResultLookup|null>(null);
  const sourceAttempt = useRef(''), readAttempt = useRef(''), touched = useRef(false), draft = useRef<DevelopmentDraft>({ goal: '', checkoutId: '', ...deliveryRequirements() });
  const latest = useRef({ run, can, active, localFilesIntent, importedEditor, onLocalFilesIntentHandled, terminalSavedFile, onPreparePythonTools });
  latest.current = { run, can, active, localFilesIntent, importedEditor, onLocalFilesIntentHandled, terminalSavedFile, onPreparePythonTools }; activeReview.current = review;
  const runReopenedFiles=useCallback((action:string,args:Data={})=>
    action==='projects.development.files.open'?bridge.command(action,args):run(action,args),[run]);
  const attemptedLocalFiles = useRef<ProjectLocalFilesIntent|null>(null);
  const firstFilesPreparation = useRef<FirstLocalFilesPreparation|null>(null);
  const preview=active&&buildPreview&&view?.viewHandle===buildPreview.viewHandle&&handle.current===buildPreview.viewHandle&&
    buildPreview.window===ownerWindow()&&buildPreview.value.projectId===projectId&&buildPreview.value.checkoutId===checkout&&
    source?.projectId===projectId&&source.checkoutId===checkout&&source.sourceDigest===buildPreview.value.sourceDigest&&
    bridge.getState().state.projectId===projectId&&!document.hidden?buildPreview.value:null;
  previewOpen.current=!!preview;heldPreview.current=buildPreview;
  const closeBuildPreview=()=>{
    const original=heldPreview.current;heldPreview.current=null;
    previewOpen.current=false;setBuildPreview(null);
    const prepared=original&&(original.value.directDependencies||directOffer(original.value)||original.value.dependencyCopy);
    if(prepared?.reviewHandle&&original&&handle.current===original.viewHandle&&ownerWindow()===original.window)
      void latest.current.run('projects.development.dismiss',{viewHandle:original.viewHandle,reviewHandle:prepared.reviewHandle}).catch(()=>{});
  };
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
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void,
    reject = (failure: unknown) => setError(failureText(failure)), scopedOwner?:()=>boolean) => {
    const ownsProject = () => bridge.getState().state.projectId === projectId && !bridge.getState().state.filePath;
    if (!latest.current.active || entered.current || document.hidden || !ownsProject() || !latest.current.can(action) || scopedOwner&&!scopedOwner()) return;
    const token = {}, window = ownerWindow(), sequence = generation.current;
    entered.current = token; setBusy(true); setError('');
    const current = () => alive.current && latest.current.active && entered.current === token && generation.current === sequence &&
      ownerWindow() === window && ownsProject() && !document.hidden && (!scopedOwner || scopedOwner());
    try {
      // Keep scoped native feedback on its original page; a late failure cannot speak for another visit.
      const scoped=scopedOwner||action===confirmDirectDependencies||action===confirmDependencyCopy||suppliedLockActions.includes(action)||
        action==='projects.development.inspect'&&args.readOriginalDraft===true;
      const runHere=scoped?bridge.command:latest.current.run;
      const value = await runHere(action, args);
      if (current()) { accept?.(value); return value as Data; }
      if (action === 'projects.development.read' && value?.viewHandle) {
        const closeHere=scopedOwner?bridge.command:latest.current.run;
        void closeHere('projects.development.close', { viewHandle: value.viewHandle }).catch(() => {});
      }
    }
    catch (failure) { if (current()) reject(failure); }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const read = () => {
    const readableNow = latest.current.active && !entered.current && !document.hidden && latest.current.can('projects.development.read');
    if (readableNow) { readAttempt.current = ownerWindow(); firstFilesPreparation.current = null; }
    const request=latest.current.localFilesIntent;
    const openLocalFiles=readableNow&&request&&request!==attemptedLocalFiles.current&&request.projectId===projectId&&request.current()?request:null;
    if(openLocalFiles)attemptedLocalFiles.current=openLocalFiles;
    const pinned=returnTarget&&returned.current!==returnTarget.handle?returnTarget:null;
    const starter=importedEditor?.openDirectory&&importedEditor.exactStarterSource===true?importedEditor:null;
    const scopedOwner=viteResultLookup?.projectId===projectId&&viteResultLookup.current()?viteResultLookup.current:
      openLocalFiles?.originalCopy?()=>latest.current.localFilesIntent===openLocalFiles&&openLocalFiles.current():
      starter?()=>latest.current.importedEditor===starter&&sameImportedEditor(starter,originalImportedEditor(projectId)):undefined;
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
    const returnedFile=latest.current.terminalSavedFile;
    if(returnedFile?.position.projectId===projectId&&returnedFile.take()){
      const position=returnedFile.position;
      const copies=rows(value.checkouts).filter(copy=>copy?.projectId===projectId&&copy.checkoutId===position.checkoutId);
      // Only this fresh page's explicit saved choice can restore the existing
      // bookmark. The bytes/caret are still checked on the owner's later click.
      if(!pinned&&!buildReturn&&!importedEditor&&!request&&!fileCheckout&&value.executionAuthorized===false&&
        value.commandsAuthorized===false&&value.draftRecoveryRequired===false&&incoming.projectId===projectId&&
        incoming.checkoutId===position.checkoutId&&draft.current.checkoutId===position.checkoutId&&!unresolved(incoming.submission)&&
        (!incoming.copyRequestId||incoming.copyStatus==='ready')&&copies.length===1&&copies[0].draftWorkingCopy===false)
        setLastSavedFile(previous=>previous??position);
    }
    const sameFilesVisit=openLocalFiles&&latest.current.localFilesIntent===openLocalFiles&&openLocalFiles.current()&&
      !pinned&&!buildReturn&&!importedEditor&&!fileCheckout&&value.executionAuthorized===false&&value.commandsAuthorized===false&&
      value.draftRecoveryRequired===false&&incoming.projectId===projectId&&!incoming.ownerPickedConvention&&!unresolved(incoming.submission)&&
      canEditImportedCheckout(latest.current.can);
    // An existing copy still requires the positively saved exact choice.
    let filesOpened=false;
    if(sameFilesVisit&&openLocalFiles&&
      incoming.copyStatus==='ready'&&copyIdentity(incoming.copyRequestId)&&copyIdentity(incoming.checkoutId)&&
      draft.current.checkoutId===incoming.checkoutId){
      const checkouts=rows(value.checkouts), copy=checkouts[0];
      const original=openLocalFiles.originalCopy;
      if((!original||original.projectId===projectId&&original.checkoutId===incoming.checkoutId&&original.requestId===incoming.copyRequestId)&&
        checkouts.length===1&&copy?.projectId===projectId&&copy.checkoutId===incoming.checkoutId&&
        copy.draftWorkingCopy===false&&copy.taskPackageSource===false&&copy.hostSource===false){
        filesOpened=true;setImportEditorNotice('');setFileWindow({checkoutId:incoming.checkoutId,...(original?{reopenedFiles:true as const}:{})});
      }
    }
    if(!filesOpened&&openLocalFiles?.originalCopy&&openLocalFiles.current())
      setImportEditorNotice('未核对到原先所选的本机副本。请先确认副本选择，没有打开其他文件。');
    // An explicit edit tap in a genuinely empty new project can instead offer
    // one user-confirmed preparation that opens just the newly prepared list.
    if(sameFilesVisit&&openLocalFiles&&!openLocalFiles.originalCopy&&!touched.current&&
       Array.isArray(value.checkouts)&&value.checkouts.length===0&&!value.phoneSourceRecovery&&
       incoming.checkoutId===''&&incoming.goal===''&&deliveryFields.every(key=>incoming[key]==='')&&
       !incoming.submission&&!incoming.copyRequestId&&!incoming.copyStatus&&latest.current.can('projects.development.prepare'))
      firstFilesPreparation.current={intent:openLocalFiles,viewHandle:value.viewHandle};
    },undefined,scopedOwner);
    return openLocalFiles?reading.finally(()=>latest.current.onLocalFilesIntentHandled?.(openLocalFiles)):reading;
  };
  const readRef = useRef(read); readRef.current = read;
  const retain = (next: DevelopmentDraft) => {
    firstFilesPreparation.current=null;
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
      generation.current++; entered.current = null; buildAfterSave.current=null; sourceRecoveryReturn.current=null; firstFilesPreparation.current=null;
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
    if (!active || !view || !checkout || busy || review || filesOpen || buildReturn || importedEditor || differentBuildCopy || unresolved(submission) || viteResultLookup) return;
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
  }, [active, view, checkout, busy, review, filesOpen, buildReturn, importedEditor, differentBuildCopy, submission, projectId, viteResultLookup]);
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
    const vite = localViteReview&&firstBuildAction==='tools'&&nodeTools?.manager==='npm'&&sourceVersion&&
      !source?.directViteBuildWorkUnavailable?{projectId,checkoutId:checkout,sourceDigest:sourceVersion}:undefined;
    void preserveSelectionBeforeDeparture({...draft.current},()=>openFromBuild(()=>onPrepareTools(vite)));
  };
  const connectBuildModel = () => {
    if(disabled||entered.current||!selectedCopy||!can('projects.development.draft')||draft.current.checkoutId!==checkout)return;
    void preserveSelectionBeforeDeparture({...draft.current},()=>openFromBuild(onConnectModel));
  };
  const canPrepareTerminal = can('projects.development.draft') && can('connections.enter') &&
    can('local.describeCheckout') && can('local.terminal.review') && !!selectedCopy;
  const prepareTerminal = (plannedSourceDigest?:string) => {
    if(disabled||entered.current||!canPrepareTerminal||draft.current.checkoutId!==checkout)return;
    const displayed={...draft.current};
    const position=!copyUnconfirmed&&selectedCopy?.draftWorkingCopy!==true&&savedFileHere&&sourceHash(savedFileHere.savedHash)?savedFileHere:undefined;
    const destination={projectId,checkoutId:displayed.checkoutId,...(plannedSourceDigest?{plannedSourceDigest}:{})};
    void preserveSelectionBeforeDeparture(displayed,()=>onOpenTerminal(destination,position));
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
  useEffect(()=>{
    const original=pythonSetupReturn;
    if(!original||!active||document.hidden||!view||view.viewHandle!==handle.current||view.projectId!==projectId||
      original.position.projectId!==projectId||busy||entered.current)return;
    const {position}=original,incoming=view.draft||{};
    const copies=rows(view.checkouts).filter(copy=>copy?.projectId===projectId&&copy.checkoutId===position.checkoutId);
    if(fileCheckout||returnTarget||buildReturn||importedEditor||localFilesIntent||activeReview.current||previewOpen.current||
      sourceConflict||!canEditImportedCheckout(latest.current.can)||view.executionAuthorized!==false||view.commandsAuthorized!==false||
      view.draftRecoveryRequired!==false||incoming.projectId!==projectId||unresolved(incoming.submission)||
      incoming.copyRequestId&&incoming.copyStatus!=='ready'||incoming.checkoutId!==position.checkoutId||
      draft.current.checkoutId!==position.checkoutId||copies.length!==1||copies[0].draftWorkingCopy!==false||
      !savedPythonPath(position.path)||!sourceHash(position.savedHash)||!original.take())return;
    // Wait for the existing Work inspection to release the page before taking the one-use return.
    // The ordinary list opens first. Returning to the file still requires a fresh native byte match.
    setFileWindow({checkoutId:position.checkoutId,pythonSetupResume:position});
  },[active,pythonSetupReturn,view,projectId,busy,fileCheckout,returnTarget,buildReturn,importedEditor,localFilesIntent,sourceConflict]);

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
    const nativeCopies=rows(view.checkouts).filter(copy=>copy?.projectId===projectId&&copy.checkoutId===checkoutId);
    const gitImport=importedEditor.sourceKind==='git_https', starter=importedEditor.sourceKind===builtinViteStarter;
    const directory=importedEditor.openDirectory===true;
    const admitted=gitImport||starter||directory?nativeCopies.length===1&&nativeCopies[0].draftWorkingCopy===false&&view.commandsAuthorized===false:
      nativeCopies.some(copy=>importedEditor.sourceKind!=='task_source_package'||copy.taskPackageSource===true);
    if(!originalIsCurrent()||view.projectId!==projectId||view.executionAuthorized!==false||!admitted){
      setImportEditorNotice('原导入副本已不能核对，没有打开其他文件。');return;
    }
    const hasDifferentCopy=()=>[draft.current.checkoutId,view.draft?.checkoutId].some(id=>id&&id!==checkoutId);
    if(view.draftRecoveryRequired===true||unresolved(submission)||sourceConflict||hasDifferentCopy()||
       ((starter||directory)&&draft.current.checkoutId!==checkoutId)||
       (directory&&(view.draftRecoveryRequired!==false||importedEditor.exactPhoneSource!==true&&importedEditor.exactStarterSource!==true))){
      setImportEditorNotice(starter?'请先核对当前选择，再选中原网页副本 '+checkoutId.slice(0,8)+'。未打开其他文件。':
        '当前项目保留了其他副本选择或待核对的工作。请先核对，再明确选择原导入副本 '+checkoutId.slice(0,8)+'。');return;
    }
    const openCopy=(inspection?:Data)=>{
      if(!originalIsCurrent()||hasDifferentCopy()||starter&&draft.current.checkoutId!==checkoutId)return;
      const firstEntry=checkoutWebEntry(inspection??null,projectId,checkoutId);
      if(starter&&!directory&&(inspection?.firstImportedWebFile?.sourceKind!==builtinViteStarter||firstEntry?.path!=='index.html'||firstEntry.root!=='')){
        setImportEditorNotice('未核对到这份副本的网页源文件。没有打开或创建其他文件。');return;
      }
      setImportEditorNotice('');setHostEditor(null);
      if(inspection?.projectId===projectId&&inspection.checkoutId===draft.current.checkoutId&&inspection.checkoutId===checkoutId&&sourceHash(inspection.sourceDigest))
        setSource(inspection); // Existing observation can name a destination, not authorize a build after an edit.
      setFileWindow({checkoutId,firstEntry:firstEntry??undefined,originalImport:importedEditor,...(starter&&directory?{reopenedFiles:true as const}:{}),
        unselectedImport:!draft.current.checkoutId&&!view.draft?.checkoutId?importedEditor:undefined});
    };
    // These original-source actions show the directory before choosing a file.
    // Other paths keep their existing exact entry observation.
    if(!gitImport&&!directory&&latest.current.can('projects.development.inspect'))
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
    const copies=rows(view.checkouts).filter(copy=>copy.projectId===projectId&&copy.checkoutId===buildReturn.checkoutId);
    if(buildReturn.projectId!==projectId||view.projectId!==projectId||view.executionAuthorized!==false||!copies.length){
      setBuildCopyMissing(true);return;
    }
    // The clicked task chooses only the editor. The current submission draft
    // and any other checkout selection are intentionally left unchanged.
    const buildResume=copies.length===1&&copies[0].draftWorkingCopy===false&&view.commandsAuthorized===false&&
      nativeView?.taskId===null&&nativeView.section===null&&latest.current.can('projects.development.files.read')?
      projectBuildEditorResume(buildReturn.editorResume,buildReturn):undefined;
    const pythonResume=buildReturn.pythonEditor;
    if (pythonResume && (copies.length!==1 || copies[0].draftWorkingCopy!==false || view.commandsAuthorized!==false ||
        !latest.current.can('projects.development.files.read')||pythonResume.projectId!==projectId||
        pythonResume.checkoutId!==buildReturn.checkoutId||pythonResume.sha256!==buildReturn.sourceDigest)) {
      setBuildCopyMissing(true);return;
    }
    sourceAttempt.current='';setSource(null);setHostEditor(null);setBuildOrigin(pythonResume?null:buildReturn);
    setFileWindow({checkoutId:buildReturn.checkoutId,buildResume,pythonResume});
  },[buildReturn,onBuildReturnHandled,active,view,busy,review,fileCheckout,projectId]);
  const unfinishedInput=!!(goal||view?.draft?.goal||view?.draft?.ownerPickedConvention||deliveryFields.some(k=>requirements[k]||view?.draft?.[k]));
  const protectModelDraft=unfinishedInput||touched.current;
  const connectProjectModel=()=>{
    if(!protectModelDraft&&!touched.current){onConnectModel();return;}
    if(disabled||entered.current||!can('projects.development.draft'))return;
    const displayed={...draft.current};
    // Commit only the matching native draft. Opening settings cannot save a
    // different editor value or turn a displayed sole copy into a selection.
    void verifyDeparture(displayed,displayedSavedDraft(displayed),onConnectModel);
  };
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
  const prepareFilesHere=()=>{
    const target=firstFilesPreparation.current;
    return target&&target.viewHandle===view?.viewHandle&&handle.current===target.viewHandle&&target.intent.current()&&
      !touched.current&&!checkout&&!draft.current.checkoutId&&!available.length&&!sourceRecovery&&!view.draft?.copyRequestId&&
      !importedEditor&&!buildReturn&&!returnTarget&&canEditImportedCheckout(latest.current.can)?target:null;
  };
  const prepareForFiles=prepareFilesHere();
  const copy = (query: boolean, openFilesAfter:FirstLocalFilesPreparation|null = null) => {
    const action=query?'projects.development.queryCopy':'projects.development.prepare', expected=view?.draft?.copyRequestId;
    const state=bridge.getState().state;
    if(!active||entered.current||!view||!can(action)||document.hidden||state.projectId!==projectId||state.filePath||
      !query&&(disabled||copyUnconfirmed||copyResult?.partial===true)||
      openFilesAfter&&(query||prepareFilesHere()!==openFilesAfter))return;
    if(!query){firstFilesPreparation.current=null;setCopyResult({unconfirmed:true});}
    return invoke(action,{viewHandle:handle.current},value=>{
      if(!value||value.projectId!==projectId||!copyIdentity(value.requestId)||!copyIdentity(value.checkoutId)||
        expected&&value.requestId!==expected||typeof value.found!=='boolean'||typeof value.partial!=='boolean'||value.replayAllowed!==false)
        throw Error('没有核对到原副本准备回执，未更换所选副本。');
      const raw=value.checkout;
      if(value.found&&(value.partial||!raw||raw.id!==value.checkoutId||raw.projectId!==projectId||raw.ownerDevelopmentRequestId!==value.requestId||
        typeof raw.name!=='string'||!Number.isSafeInteger(raw.createdAt)||raw.createdAt<1||raw.createdAt>8.64e15))
        throw Error('副本登记不属于原准备请求，未更换所选副本。');
      const openInitialFiles=value.found&&!query&&!draft.current.checkoutId&&!currentPicked&&
        openFilesAfter&&value.freshPrepareViewHandle===openFilesAfter.viewHandle&&
        handle.current===openFilesAfter.viewHandle&&openFilesAfter.intent.current()&&!touched.current&&
        canEditImportedCheckout(latest.current.can)&&Array.isArray(raw.files)&&
        !['ownerDraftWorkCopy','sourceImport','teamProjectCopy','skillExecutionCopy','isolatedGit','nativeGitImportPending',
          'collectedFromCheckoutId','collectionOperationId'].some(key=>key in raw);
      // Preserve the same positive ordinary-copy classification a full native
      // Work read supplies, so the first saved file can find the next local action.
      const prepared=value.found?{checkoutId:value.checkoutId,projectId,name:raw.name,createdAt:raw.createdAt,
        ...(openInitialFiles?{draftWorkingCopy:false}:{})}:null;
      setCopyResult({requestId:value.requestId,found:value.found,partial:value.partial,checkout:prepared});
      setView(previous=>previous?{...previous,draft:{...previous.draft,copyRequestId:value.requestId,copyStatus:prepared?'ready':'unknown'},
        checkouts:prepared&&!rows(previous.checkouts).some(row=>row.projectId===projectId&&row.checkoutId===prepared.checkoutId)?
          [...rows(previous.checkouts),prepared]:previous.checkouts}:previous);
      // Checking an older request is just observation. Only an explicit initial
      // prepare in an unselected project can present its new copy by default.
      if(prepared&&!query&&!draft.current.checkoutId&&!currentPicked){
        chooseCheckout(prepared.checkoutId);
        if(openInitialFiles){setHostEditor(null);setFileWindow({checkoutId:prepared.checkoutId});}
      }
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
  const starterEntry=importedEntry?.path==='index.html'&&importedEntry.root===''&&source?.firstImportedWebFile?.sourceKind===builtinViteStarter;
  const versionBuild = versionBuildFor(source, projectId, checkout);
  const ordinaryBuildSource=selectedCopy?.draftWorkingCopy===false&&!differentBuildCopy&&
    source?.projectId===projectId&&source.checkoutId===checkout&&sourceHash(source.sourceDigest);
  const sourceBuildKind:DirectBuildKind=source?.directViteBuildWork?'vite':'compiler';
  const directWork = ordinaryBuildSource&&source&&(sourceBuildKind==='vite'||view?.model?.configured===false)&&
    !(source.directNodeBuildWork&&source.directViteBuildWork)?
    directNodeWork(sourceBuildKind==='vite'?source.directViteBuildWork:source.directNodeBuildWork,projectId,checkout,source.sourceDigest,sourceBuildKind):null;
  const nativeDirectBuild = can(reviewDirectBuild)&&can(confirmDirectBuild);
  // Only a positive source-shape hint. The explicit native review still checks the lock, tools and runnable plan.
  const localViteReview=ordinaryBuildSource&&source?.existingNodeBuild===true&&source.directBuildKind==='vite'&&nativeDirectBuild;
  const nativeDirectDependencies = can(reviewDirectBuild)&&can(confirmDirectDependencies);
  const nativeNodeReview = nativeDirectBuild||nativeDirectDependencies;
  const previewVite=preview?.directBuildKind==='vite', buildOffer=directOffer(preview), buildWork=directOriginal(preview);
  const dependencyOffer=preview?.directDependencies, dependencyWork=preview?.directDependencyWork;
  const dependencyCopy=preview?.dependencyCopy;
  const historicalLockCopy=dependencyCopy?.reason==='current_lock_unverified';
  const dependencyNext=!!(dependencyCopy&&!historicalLockCopy||dependencyOffer||dependencyWork&&!buildOffer&&!buildWork);
  const buildModelReview=localViteReview&&view?.model?.configured===true&&!dependencyNext&&!buildOffer&&!buildWork&&can('projects.development.reviewDraftBuild');
  const buildModelConnect=view?.model?.configured===false&&!dependencyNext&&!buildWork&&can('projects.development.draft');
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
  useEffect(()=>{
    const asked=viteResultLookup;
    if(!asked||attemptedViteResult.current===asked||asked.projectId!==projectId||!asked.current()||
       !active||busy||entered.current)return;
    if(!view){if(error)asked.finish();return;}
    const done=(message:string)=>{if(asked.current()){setError(message);asked.finish();}};
    attemptedViteResult.current=asked;
    const viewHandle=handle.current,window=ownerWindow(),sequence=generation.current;
    const selected=view.draft?.checkoutId||((available.length===1&&available[0]?.checkoutId)||'');
    if(!viewHandle||view.viewHandle!==viewHandle||view.projectId!==projectId||view.draft?.projectId!==projectId||
       view.draftRecoveryRequired!==false||view.commandsAuthorized!==false||view.executionAuthorized!==false||
       filesOpen||buildReturn||importedEditor||
       differentBuildCopy||pending||sourceConflict||review||preview||!can('projects.development.inspect')){
      done('请先完成原项目的选择或审阅。没有打开其他网页。');return;
    }
    if(!copyIdentity(selected)||selected!==checkout||draft.current.checkoutId!==checkout||selectedCopy?.draftWorkingCopy!==false||
       available.filter(row=>row.projectId===projectId&&row.checkoutId===selected).length!==1){
      done('请先选择要核对的私人副本。没有打开其他副本的网页。');return;
    }
    const current=()=>asked.current()&&generation.current===sequence&&handle.current===viewHandle&&ownerWindow()===window&&
      draft.current.checkoutId===selected;
    // A failed explicit check must not start an ordinary background retry or reuse its old projection.
    sourceAttempt.current=`${viewHandle}|${projectId}|${selected}`;setSource(null);
    void invoke('projects.development.inspect',{viewHandle,checkoutId:selected},fresh=>{
      if(!current())return;
      if(fresh.projectId!==projectId||fresh.checkoutId!==selected||!sourceHash(fresh.sourceDigest)){
        done('未核对到原副本的当前源码，没有采用其他网页。');return;
      }
      setSource(fresh);
      const original=directNodeWork(fresh.directViteBuildWork,projectId,selected,fresh.sourceDigest,'vite');
      if(fresh.directBuildKind!=='vite'||original?.status!=='completed'){
        done(original?.status&&original.status!=='completed'?
          '当前源码的本机网页构建没有确认完成。可以查看原构建；没有打开旧网页。':
          '当前已保存源码没有可核对的本机网页成果。可在这里查看构建条件；没有打开旧网页。');return;
      }
      asked.finish({taskId:original.requestId,runId:original.runId,epoch:original.epoch,buildOperationId:original.buildOperationId,
        projectId,checkoutId:selected,sourceDigest:fresh.sourceDigest});
    },()=>{if(current())done('未能核对这份副本的源码和原构建，没有打开其他网页。');},current);
  },[viteResultLookup,projectId,active,busy,view,error,checkout,selectedCopy,available,filesOpen,buildReturn,importedEditor,
    differentBuildCopy,pending,sourceConflict,review,preview,can]);
  const buildStepVisible = !filesOpen && !pending && !sourceConflict && !buildCopyMissing && !view?.draftRecoveryRequired;
  const observedLockPath = source?.existingNodeBuild===true ? source.existingNodeBuildMissingLock :
    selectedCopy?.draftWorkingCopy===true && !versionBuild && !buildLookupUnavailable ? source?.draftCopyNodeBuildMissingLock : undefined;
  const missingRequiredLock = !!selectedCopy && !differentBuildCopy && source?.projectId===projectId && source.checkoutId===checkout &&
    sourceHash(source.sourceDigest) &&
    (observedLockPath==='package-lock.json'||observedLockPath==='pnpm-lock.yaml') ? observedLockPath : '';
  const directWorkAtTop=buildStepVisible&&directBuildRegistered(directWork)&&can('tasks.open');
  const firstBuild = !!(nodeTools || missingRequiredLock) && !directWorkAtTop && !versionBuild && !buildLookupUnavailable &&
    (localViteReview||can('projects.development.reviewDraftBuild'));
  const firstBuildVisible = firstBuild && buildStepVisible;
  const showToolSetupByBuild = firstBuildVisible && !missingRequiredLock && buildToolsBlocked && !missingPnpm &&
    view?.runtime?.preparationAvailable===true && canReviewToolSetup;
  const modelStillNeeded = firstBuild && !buildToolsBlocked && view?.model?.configured!==true;
  const canPreviewBuild=view?.model?.configured===false&&source?.existingNodeBuild===true&&sourceHash(source.sourceDigest);
  const firstBuildAction = !firstBuildVisible || missingRequiredLock ? '' : showToolSetupByBuild ? 'tools' : buildToolsBlocked ? 'refresh' :
    localViteReview?'preview':modelStillNeeded?canPreviewBuild?'preview':'model':'review';
  // The pre-editor missing-lock observation may be repaired by saving that exact root path.
  // Recheck the saved source before deciding what comes next; saving a lock is not dependency readiness.
  const originalImportForSave=fileWindow?.originalImport;
  const directImportedSave=!source&&!!originalImportForSave&&originalImportForSave.sourceKind!=='task_source_package'&&
    originalImportForSave.checkoutId===checkout&&selectedCopy?.draftWorkingCopy===false&&view?.executionAuthorized===false&&view.commandsAuthorized===false&&
    sameImportedEditor(originalImportForSave,originalImportedEditor(projectId));
  const visibleStarter=fileWindow&&(originalImportForSave||fileWindow.buildResume&&originalImportedEditor(projectId));
  const starterForSave=visibleStarter?.sourceKind===builtinViteStarter&&visibleStarter.checkoutId===checkout&&
    selectedCopy?.draftWorkingCopy===false&&view?.executionAuthorized===false&&view.commandsAuthorized===false&&nativeDirectBuild&&
    sameImportedEditor(visibleStarter,originalImportedEditor(projectId))?visibleStarter:null;
  const importedViteEntry=!!fileWindow?.buildResume||(ordinaryBuildSource&&source?.directBuildKind==='vite');
  const candidateImport=fileWindow&&(directImportedSave||importedViteEntry)?originalImportForSave||originalImportedEditor(projectId):null;
  const importedViteForSave=candidateImport&&(candidateImport.sourceKind==='git_https'||candidateImport.exactPhoneSource===true)&&
    candidateImport.checkoutId===checkout&&selectedCopy?.draftWorkingCopy===false&&view?.executionAuthorized===false&&
    view.commandsAuthorized===false&&nativeDirectBuild&&
    sameImportedEditor(candidateImport,originalImportedEditor(projectId))?candidateImport:null;
  const viteForSave=starterForSave||importedViteForSave;
  const dependencyChoices=selectedCopy?.ownerDependencyCopyEditor===true?available.filter(row=>row.checkoutId===checkout):[];
  const selectedDependencyEditor=dependencyChoices.length===1&&selectedCopy?.draftWorkingCopy===false&&
    view?.projectId===projectId&&view.viewHandle===handle.current&&view.executionAuthorized===false&&
    view.commandsAuthorized===false&&view.draftRecoveryRequired===false?selectedCopy:null;
  const shownDependencyEditor=useRef<Data|null>(null);shownDependencyEditor.current=selectedDependencyEditor;
  const dependencyForSave=selectedDependencyEditor&&fileCheckout===checkout&&nativeDirectBuild?selectedDependencyEditor:null;
  const showViteSaveLabel=!!starterForSave||!!(importedViteForSave||dependencyForSave)&&importedViteEntry;
  const pythonToolSetup = !!onPreparePythonTools && !!fileWindow && !fileWindow.originalImport && !fileWindow.unselectedImport &&
    checkout===fileCheckout&&!hostEditor&&!pending&&!sourceConflict&&!buildCopyMissing&&!view?.draftRecoveryRequired&&
    selectedCopy?.draftWorkingCopy===false&&view?.runtime?.runtimeId==='managed_process_v1'&&
    view.runtime.status==='preparation_required'&&view.runtime.canExecute===false&&view.runtime.preparationAvailable===true&&canViewToolSetup;
  const prepareSavedPythonTools = async ({position,savedHere,closeEditor}:CheckoutPythonToolsDeparture) => {
    const documentWindow=ownerWindow(),viewHandle=handle.current,sequence=generation.current,displayed={...draft.current};
    const stillHere=()=>alive.current&&latest.current.active&&!document.hidden&&generation.current===sequence&&
      handle.current===viewHandle&&ownerWindow()===documentWindow&&!activeReview.current&&!previewOpen.current&&
      bridge.getState().state.projectId===projectId&&!bridge.getState().state.filePath&&sameDraft(draft.current,displayed);
    if(!pythonToolSetup||!stillHere()||position.projectId!==projectId||position.checkoutId!==fileCheckout||
      displayed.checkoutId!==position.checkoutId||!savedPythonPath(position.path)||!sourceHash(position.savedHash))return;
    let verified=false;
    await preserveSelectionBeforeDeparture(displayed,()=>{verified=true});
    if(!verified||!stillHere())throw Error('原副本的选择尚未确认，没有打开工具准备。');
    if(!await closeEditor())throw Error('未确认原编辑器已关闭，没有打开工具准备。');
    if(!stillHere())return;
    const exactPosition = {projectId,checkoutId:position.checkoutId,path:position.path,savedHash:position.savedHash};
    closeFiles(savedHere?exactPosition:undefined);
    latest.current.onPreparePythonTools?.(exactPosition);
  };
  const canPrepareSavedBuild=!!((nodeTools||missingRequiredLock||directImportedSave||viteForSave||dependencyForSave)&&checkout===fileCheckout&&!hostEditor&&!pending&&!sourceConflict&&!buildCopyMissing&&
    !view?.draftRecoveryRequired&&can('projects.development.inspect')&&(!!viteForSave||!!dependencyForSave||can('projects.development.reviewDraftBuild')));
  const savedSource = source?.savedEditInSource;
  const checkedSave = savedFileHere && savedSource?.path===savedFileHere.path && savedSource.savedHash===savedFileHere.savedHash &&
    savedSource.sourceDigest===source?.sourceDigest && sourceHash(source?.sourceDigest) && typeof savedSource.matches==='boolean' ? savedSource : null;
  const sourceVersion = source?.projectId===projectId && source.checkoutId===checkout && sourceHash(source.sourceDigest) ? source.sourceDigest : '';
  const suppliedLock=bridge.getState().state.suppliedViteLock;
  const heldLock=active&&lockVisit&&suppliedLock?.visit===lockVisit&&suppliedLock.projectId===projectId&&
    suppliedLock.checkoutId===checkout&&sourceHash(suppliedLock.sourceDigest)?suppliedLock:null;
  const checkedLock=heldLock?.state==='review'&&heldLock.saved===false&&heldLock.executionAuthorized===false&&
    heldLock.path==='package-lock.json'&&sourceHash(heldLock.sha256)&&sourceHash(heldLock.manifestSha256)&&
    sourceHash(heldLock.nextSourceDigest)&&heldLock.nextSourceDigest!==heldLock.sourceDigest&&
    Number.isSafeInteger(heldLock.bytes)&&heldLock.bytes>0&&heldLock.bytes<=524288?heldLock:null;
  const canSupplyLock=source?.canChooseSuppliedViteLock===true&&missingRequiredLock==='package-lock.json'&&
    !!sourceVersion&&selectedCopy?.draftWorkingCopy===false&&view?.draftRecoveryRequired===false&&suppliedLockActions.every(can);
  const staleLock=!!sourceVersion&&!!heldLock&&heldLock.sourceDigest!==sourceVersion&&['selected','review'].includes(heldLock.state);
  const supplyLock=(action:string)=>{
    if(entered.current||!handle.current||handle.current!==view?.viewHandle||document.hidden||
       !checkout||checkout!==draft.current.checkoutId||!can(action))return;
    if(action===dismissSuppliedLock){if(!heldLock||!['selected','review'].includes(heldLock.state))return;}
    else if(!canSupplyLock||action!==pickSuppliedLock&&heldLock?.sourceDigest!==sourceVersion||
       action===reviewSuppliedLock&&heldLock?.state!=='selected'||action===confirmSuppliedLock&&!checkedLock)return;
    const visit=action===pickSuppliedLock?crypto.randomUUID():lockVisit;
    if(!visit)return;
    if(action===pickSuppliedLock)setLockVisit(visit);
    void invoke(action,{viewHandle:handle.current,checkoutId:checkout,visit,...(action===pickSuppliedLock?{sourceDigest:sourceVersion}:{})},value=>{
      if(action===dismissSuppliedLock&&value.dismissed===true){setLockVisit(null);return;}
      if(action!==confirmSuppliedLock)return;
      if(value.projectId!==projectId||value.checkoutId!==checkout||value.sourceDigest!==sourceVersion||
         value.visit!==visit||value.path!=='package-lock.json'||value.executionAuthorized!==false||
         value.sha256!==checkedLock?.sha256||value.bytes!==checkedLock?.bytes||value.nextSourceDigest!==checkedLock?.nextSourceDigest)
        throw Error('未核对到这一份原锁文件的写入回执，请先重读原副本，不要重复写入。');
      if(value.state==='saved'&&value.saved===true){
        // Reuse the ordinary selected-source read. No dependency action is invoked.
        sourceAttempt.current='';setSource(null);setView(previous=>previous?{...previous}:previous);
      }
    });
  };
  const acceptedBuild = selectedCopy && !differentBuildCopy && submission?.status==='accepted' && submission.independentNodeBuild===true && submission.projectId===projectId &&
    submission.checkoutId===checkout && sourceHash(submission.versionedBuildSourceDigest) &&
    typeof submission.taskId==='string' && /^[A-Za-z0-9_-]{1,80}$/.test(submission.taskId) &&
    (!source || submission.versionedBuildSourceDigest===sourceVersion) ? submission : null;
  const versionWorkAtTop = buildStepVisible && !directWorkAtTop && !!versionBuild && source?.existingNodeBuild===true && can('tasks.open');
  const acceptedWorkAtTop = buildStepVisible && !directWorkAtTop && !versionBuild && !!acceptedBuild && can('tasks.open');
  const showMissingLock = buildStepVisible && !directWorkAtTop && !!missingRequiredLock && !versionWorkAtTop && !acceptedWorkAtTop;
  const reactLock=ordinaryBuildSource&&source?.existingNodeBuild===true&&!source.directBuildKind?
    source.directReactTypeScriptLockNotice:null;
  const showReactLock=buildStepVisible&&nativeDirectBuild&&!directWorkAtTop&&!versionWorkAtTop&&!acceptedWorkAtTop&&!showMissingLock&&
    reactLock?.verifiedVersion==='6.0.3'&&typeof reactLock.lockedVersion==='string'&&/^7[.](?:0|[1-9][0-9]{0,3})[.](?:0|[1-9][0-9]{0,3})$/.test(reactLock.lockedVersion);
  const exactBuildNext = !!(firstBuildAction || directWorkAtTop || versionWorkAtTop || acceptedWorkAtTop || showMissingLock || showReactLock);
  // Recognizing Python and its tools only brings the existing scope review closer.
  // The terminal's native review still reads the current copy and execution conditions.
  const savedScriptTooling=source?.checkoutTooling, terminalRuntime=view?.runtime;
  const terminalBySave=canPrepareTerminal&&buildStepVisible&&!differentBuildCopy&&!exactBuildNext&&selectedCopy?.draftWorkingCopy===false&&
    view?.model?.configured===false&&!!savedFileHere&&/\.py$/.test(savedFileHere.path)&&sourceHash(savedFileHere.savedHash)&&!!sourceVersion&&
    savedScriptTooling?.readOnly===true&&savedScriptTooling.projectId===projectId&&savedScriptTooling.checkoutId===checkout&&
    savedScriptTooling.sourceDigest===sourceVersion&&savedScriptTooling.runtimeId==='managed_process_v1'&&savedScriptTooling.runtimeReady===true&&
    Array.isArray(savedScriptTooling.kinds)&&savedScriptTooling.kinds.length===1&&savedScriptTooling.kinds[0]==='python'&&
    Array.isArray(savedScriptTooling.observedTools)&&['python3','sh'].every(tool=>savedScriptTooling.observedTools.includes(tool))&&
    terminalRuntime?.runtimeId==='managed_process_v1'&&terminalRuntime.canExecute===true&&terminalRuntime.ptyAvailable===true;
  const terminalAction=canPrepareTerminal&&<div className="pw-form-actions"><Button variant={terminalBySave?'secondary':'ghost'} disabled={disabled}
    data-workspace-entry="project-copy-terminal" onClick={()=>prepareTerminal()}>{terminalBySave?'核对本机终端':'准备这份副本的终端'}</Button></div>;
  // The preview itself is the native proof this is an ordinary existing copy.
  const terminalFromPreview=!!preview&&canPrepareTerminal&&!differentBuildCopy&&!buildCopyMissing&&
    view?.model?.configured===false&&nodeTools?.runtimeReady===true&&nodeTools.missing.length===0&&
    Array.isArray(savedScriptTooling?.observedTools)&&savedScriptTooling.observedTools.includes('sh')&&
    terminalRuntime?.runtimeId==='managed_process_v1'&&terminalRuntime.canExecute===true&&terminalRuntime.ptyAvailable===true;
  const leaveBuildPreview=(next:()=>void)=>{
    const state=bridge.getState().state;
    if(!preview||!previewOpen.current||!buildPreview||disabled||entered.current||document.hidden||
      handle.current!==buildPreview.viewHandle||ownerWindow()!==buildPreview.window||state.projectId!==projectId||state.filePath||
      draft.current.checkoutId!==preview.checkoutId||sourceVersion!==preview.sourceDigest)return;
    closeBuildPreview();next();
  };
  const openSelectedCopyFiles = () => {
    if (!selectedCopy || draft.current.checkoutId!==selectedCopy.checkoutId || !can('projects.development.files.open')) return;
    setHostEditor(null); setFileWindow({checkoutId:selectedCopy.checkoutId});
  };
  const prepareSavedBuild = async ({position:saved,filesHandle,id,closeEditor}:CheckoutSaveForBuild) => {
    const viewHandle=handle.current, window=ownerWindow(), sequence=generation.current;
    const original=directImportedSave?originalImportForSave:undefined;
    const vite=viteForSave, dependency=dependencyForSave, shown={...draft.current};
    if(!active||!canPrepareSavedBuild||!viewHandle||view?.viewHandle!==viewHandle||saved.projectId!==projectId||
      saved.checkoutId!==fileCheckout||draft.current.checkoutId!==saved.checkoutId||
      (missingRequiredLock&&saved.path!==missingRequiredLock)||document.hidden)
      throw Error('原副本或编辑窗口已变化，请先核对原文件。');
    const showSavedSource=(value:Data,focus:boolean)=>{
      buildAfterSave.current=focus?{saved,viewHandle,window}:null;
      setLastSavedFile(saved); setImportEditChoice(null); setFileWindow(null); setHostEditor(null); setHostFiles(null);
      // The normal inspection already ran with exactly this page and save.
      sourceAttempt.current=`${viewHandle}|${projectId}|${saved.checkoutId}`;setSource(value);
    };
    let goToReview=false;
    const fresh=await invoke('projects.development.inspect',{viewHandle,checkoutId:saved.checkoutId,savedEdit:{filesHandle,id}},value=>{
      const observation=value.savedEditInSource;
      if(handle.current!==viewHandle||draft.current.checkoutId!==saved.checkoutId||value.projectId!==projectId||
        value.checkoutId!==saved.checkoutId||!sourceHash(value.sourceDigest)||observation?.sourceDigest!==value.sourceDigest||
        observation.path!==saved.path||observation.savedHash!==saved.savedHash||typeof observation.matches!=='boolean')
        throw Error('没有核对到这次原文件保存与当前源码，文件仍留在原编辑器。');
      const exactOrigin=vite||original;
      if(exactOrigin&&(!sameImportedEditor(exactOrigin,originalImportedEditor(projectId))||observation.matches!==true))
        throw Error('原导入副本或刚保存的字节已变化，没有进入其他版本的构建核对。');
      if(dependency&&(shownDependencyEditor.current!==dependency||value.ownerDependencyCopyEditor!==true||observation.matches!==true))
        throw Error('未核对到这份依赖副本刚保存的文件。仍留在原编辑器，没有切换副本。');
      const tools=checkoutFirstWebTools(value,projectId,saved.checkoutId);
      const knownViteOrigin=vite?.sourceKind===builtinViteStarter?value.firstImportedWebFile?.sourceKind===builtinViteStarter:
        !!(vite||dependency)&&value.existingNodeBuild===true;
      goToReview=knownViteOrigin&&
        value.directBuildKind==='vite'&&
        tools?.kind==='node'&&tools.manager==='npm'&&tools.runtimeReady&&tools.missing.length===0&&
        !value.existingNodeBuildTask&&!value.existingNodeBuildUnavailable&&!value.directViteBuildWork&&!value.directViteBuildWorkUnavailable;
      if(!goToReview)showSavedSource(value,true);
    },failure=>{throw failure;});
    if(!fresh)throw Error('没有取得这次原保存的核对回执。');
    if(!goToReview||!(vite||dependency))return;
    const sameVisit=()=>alive.current&&latest.current.active&&!document.hidden&&generation.current===sequence&&ownerWindow()===window&&
      handle.current===viewHandle&&bridge.getState().state.projectId===projectId&&!bridge.getState().state.filePath;
    const sameOrigin=()=>sameVisit()&&!activeReview.current&&!previewOpen.current&&sameDraft(draft.current,shown)&&
      (vite?sameImportedEditor(vite,originalImportedEditor(projectId)):shownDependencyEditor.current===dependency);
    if(!sameOrigin())throw Error('原副本或项目草稿已变化，未打开构建审阅。');
    const closed=await closeEditor();
    if(!sameVisit())return;
    if(!sameOrigin()){closeFiles(saved);setError('这次写入已保存，原项目或草稿已变化，没有进入构建审阅。');return;}
    showSavedSource(fresh,!closed);
    if(!closed){setError('文件已保存，但未确认原编辑器已关闭，没有打开构建审阅。可在同一副本核对。');return;}
    await readBuildPreview(viewHandle,window,shown,fresh.sourceDigest,true,'exact',sameOrigin);
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
  const readBuildPreview=(viewHandle:string,window:string,shown:DevelopmentDraft,expectedSourceDigest:string,direct:boolean,
    requireVite:'exact'|'offer'|false,stillCurrent?:()=>boolean,verify?: (value:Data)=>void,dependencyComparisonHandle?:string)=>
    invoke(direct?reviewDirectBuild:'projects.development.reviewDraftBuild',
      {viewHandle,checkoutId:shown.checkoutId,retainedDraft:displayedSavedDraft(shown),expectedSourceDigest,
        ...(direct?(dependencyComparisonHandle?{dependencyComparisonHandle}:{}):{previewOnly:true})}, value=>{
      if(handle.current!==viewHandle||!sameDraft(draft.current,shown)||stillCurrent?.()===false)throw Error('原副本或草稿已变化，未展示构建方案。');
      const checked=checkedBuildPreview(value,projectId,shown.checkoutId,expectedSourceDigest);
      if(requireVite&&checked.directBuildKind!=='vite'&&(requireVite==='exact'||directOffer(checked)||directOriginal(checked)||checked.directDependencies||checked.directDependencyWork))
        throw Error('原网页方案没有得到核对，没有换成其他构建。');
      verify?.(checked);
      setBuildPreview({value:checked,viewHandle,window});
    });
  useEffect(()=>{
    const arrival=dependencyBuildReturn||viteToolsReturn;
    const dependency=dependencyBuildReturn?.original, vite=dependency?undefined:viteToolsReturn;
    const comparison=dependencyBuildReturn?.comparisonHandle, original=dependency||vite?.position;
    if(!arrival||!original||!active||!arrival.current()||busy||entered.current)return;
    const focus=()=>requestAnimationFrame(()=>{
      const anchor=buildReturnAnchor.current;
      if(arrival.current()&&latest.current.active&&anchor?.isConnected&&!anchor.closest('[hidden],[inert]'))
        anchor.focus({preventScroll:true});
    });
    const reject=(message:string)=>{if(arrival.take()){setError(message);focus();}};
    if(!view){if(error)reject(error);return;}
    const ordinary=rows(view.checkouts).filter(copy=>copy.projectId===projectId&&copy.checkoutId===original.checkoutId);
    if(original.projectId!==projectId||view.projectId!==projectId||view.viewHandle!==handle.current||
       view.commandsAuthorized!==false||view.executionAuthorized!==false||view.draftRecoveryRequired!==false||
       checkout!==original.checkoutId||draft.current.checkoutId!==original.checkoutId||ordinary.length!==1||ordinary[0].draftWorkingCopy!==false||
       returnTarget||buildReturn||importedEditor||localFilesIntent||pythonSetupReturn||filesOpen||review||preview||sourceConflict||pending||copyUnconfirmed){
      reject('原副本或项目工作已经变化，没有替换选择或打开构建。');return;
    }
    // Both returns wait for the existing fresh Work inspection, not a second one.
    if(!source){if(error)reject(error);return;}
    if(source.projectId!==projectId||source.checkoutId!==original.checkoutId||!sourceHash(source.sourceDigest)||
       !comparison&&source.sourceDigest!==original.sourceDigest||source.existingNodeBuild!==true||!nativeDirectBuild){
      reject(vite?'原网页源码已变化或暂不可核对，没有打开其他版本的构建。':
        '原依赖准备对应的源码已变化或暂不可核对，没有打开其他版本。');return;
    }
    if(comparison&&(!dependency||source.sourceDigest===dependency.sourceDigest||!localViteReview||view.runtime?.identity!==dependency.runtimeIdentity)){
      reject('这份准备记录与当前副本的锁暂不能对照。原副本未切换、未运行。');return;
    }
    if(vite&&(!localViteReview||view.runtime?.identity!==vite.runtimeIdentity||view.runtime?.canExecute!==true||
       nodeTools?.manager!=='npm'||nodeTools.runtimeReady!==true||nodeTools.missing.length)){
      reject('原网页副本或手机工具已变化，请在原项目重新核对。没有开始准备或构建。');return;
    }
    const shown={...draft.current},viewHandle=handle.current,window=ownerWindow(),sequence=generation.current;
    if(!arrival.take())return;
    void readBuildPreview(viewHandle,window,shown,source.sourceDigest,true,vite||comparison?'exact':false,
      ()=>arrival.current()&&alive.current&&latest.current.active&&generation.current===sequence&&ownerWindow()===window&&
        !activeReview.current&&!previewOpen.current,checked=>{
        const prepared=checked.directDependencyWork,build=directOffer(checked)||directOriginal(checked);
        if(comparison){
          if(!dependency||checked.dependencyCopy?.reason!=='current_lock_unverified'||
             checked.dependencyCopy.previousLockSha256!==dependency.lockSha256||build?.runtimeIdentity!==dependency.runtimeIdentity)
            throw Error('没有核对到所选的旧准备记录与这版锁的对照。没有另建副本或运行。');
          return;
        }
        if(vite){
          const next=build||checked.directDependencies||prepared;
          if(next?.runtimeIdentity!==vite.runtimeIdentity)
            throw Error('没有核对到这版网页对应的依赖或构建动作，请在原项目查看条件。没有开始准备或构建。');
          return;
        }
        if(!dependency||checked.dependencyState!=='current_packages_checked'||prepared?.status!=='completed'||
           prepared.requestId!==dependency.taskId||prepared.runId!==dependency.runId||prepared.epoch!==dependency.epoch||
           prepared.dependencyOperationId!==dependency.dependencyOperationId||prepared.planDigest!==dependency.planDigest||
           prepared.runtimeIdentity!==dependency.runtimeIdentity||prepared.lockSha256!==dependency.lockSha256||
           prepared.managerVersion!==dependency.managerVersion||prepared.packages!==dependency.packages||
           build?.runtimeIdentity!==dependency.runtimeIdentity||build.lockSha256!==dependency.lockSha256)
          throw Error('尚未核对到这次准备对应的工具、锁定依赖与构建。没有重新下载或运行。');
      },comparison).then(value=>{if(!value)focus();});
  },[dependencyBuildReturn,viteToolsReturn,active,busy,view,error,projectId,checkout,returnTarget,buildReturn,importedEditor,
    localFilesIntent,pythonSetupReturn,filesOpen,review,preview,sourceConflict,pending,copyUnconfirmed,source,nativeDirectBuild,localViteReview]);
  const previewNodeBuild=()=>{
    const viewHandle=handle.current,window=ownerWindow(),expectedSourceDigest=sourceVersion,shown={...draft.current};
    if(firstBuildAction!=='preview'||disabled||previewOpen.current||!viewHandle||view?.viewHandle!==viewHandle||
      !expectedSourceDigest||shown.checkoutId!==checkout)return;
    void readBuildPreview(viewHandle,window,shown,expectedSourceDigest,nativeNodeReview,localViteReview?'offer':false);
  };
  const canActOnDirectPreview=()=>{
    const state=bridge.getState().state;
    return !!preview&&!!buildPreview&&previewOpen.current&&!entered.current&&!disabled&&!!handle.current&&!document.hidden&&
      handle.current===buildPreview.viewHandle&&ownerWindow()===buildPreview.window&&state.projectId===projectId&&!state.filePath&&
      draft.current.checkoutId===preview.checkoutId&&sourceVersion===preview.sourceDigest;
  };
  const confirmOwnerNodeBuild=()=>{
    const offer=buildOffer;
    if(!offer||!canActOnDirectPreview()||!nativeDirectBuild||!can('tasks.open'))return;
    if(offer.expiresAt<=Date.now()){
      closeBuildPreview();setError('这次本机构建审阅已过期。没有提交，请重新核对同一副本。');return;
    }
    const viewHandle=handle.current, kind=previewVite?'vite':'compiler';
    // Once confirmed, never reuse that review token, even if the reply is lost.
    heldPreview.current=null;previewOpen.current=false;setBuildPreview(null);
    void invoke(confirmDirectBuild,{viewHandle,reviewHandle:offer.reviewHandle},value=>{
      const original=directNodeWork(value,projectId,checkout,sourceVersion,kind);
      if(!original||!directBuildRegistered(original)||!sameDirectBuild(original,offer)||
         handle.current!==viewHandle||draft.current.checkoutId!==checkout)
        throw Error('未取得同一笔本机构建回执。请回原项目核对，没有打开其他任务。');
      onTaskRegistered();onOpenTask(original.requestId,checkout,kind==='vite'?{runId:original.runId,epoch:original.epoch}:undefined);
    },failure=>{
      setError(failureText(failure)+' 返回核对同一副本时，只查已登记的原构建，不会重复运行。');
      sourceAttempt.current='';setSource(null);
    });
  };
  const openDependencyWork=()=>{
    const original=directDependency(dependencyWork,projectId,checkout,sourceVersion);
    if(!original||!canActOnDirectPreview()||!can('tasks.open'))return;
    closeBuildPreview();onOpenTask(original.requestId,checkout,{runId:original.runId,epoch:original.epoch});
  };
  const confirmOwnerDependencies=()=>{
    if(!dependencyOffer||!canActOnDirectPreview()||!nativeDirectDependencies||!can('tasks.open'))return;
    const shown=directDependency(dependencyOffer,projectId,checkout,sourceVersion,true);
    if(!shown){closeBuildPreview();setError('这份依赖审阅已过期。没有提交，请重新核对同一副本。');return;}
    const viewHandle=handle.current;
    // Confirmation is one use even if the native result does not reach this page.
    heldPreview.current=null;previewOpen.current=false;setBuildPreview(null);
    void invoke(confirmDirectDependencies,{viewHandle,reviewHandle:shown.reviewHandle},value=>{
      const original=directDependency(value,projectId,checkout,sourceVersion);
      if(!original||!sameDependency(original,shown)||handle.current!==viewHandle||draft.current.checkoutId!==checkout)
        throw Error('没有核对到这笔原依赖准备的工作回执。');
      onTaskRegistered();onOpenTask(original.requestId,checkout,{runId:original.runId,epoch:original.epoch});
    },failure=>setError(failureText(failure)+' 请重新查看同一副本的方案，先核对原准备结果。'));
  };
  const showPublishedDependencyCopy=(actual:Data)=>{
    const existing=available.filter(row=>row.checkoutId===actual.newCheckoutId);
    if(existing.length>1||existing.some(row=>row.projectId!==projectId||row.name!==actual.name||row.createdAt!==actual.createdAt||row.draftWorkingCopy!==false))
      throw Error('新副本的原登记已变化，未选择其他副本。');
    const viewHandle=handle.current;
    if(!existing.length)setView(last=>last&&last.viewHandle===viewHandle&&!rows(last.checkouts).some(row=>row.checkoutId===actual.newCheckoutId)?{
      ...last,checkouts:[...rows(last.checkouts),{checkoutId:actual.newCheckoutId,projectId,name:actual.name,createdAt:actual.createdAt,
        draftWorkingCopy:false,ownerDependencyCopyEditor:true}],
    }:last);
  };
  const createDependencyCopy=()=>{
    if(!buildPreview||!canActOnDirectPreview()||!can(confirmDependencyCopy))return;
    const shown=checkedDependencyCopy(dependencyCopy,projectId,checkout,sourceVersion,preview?.lockedDependencies?.lockSha256);
    if(shown?.state!=='review'){closeBuildPreview();setError('这次副本审阅已过期，没有另建副本。');return;}
    const original=buildPreview;
    const update=(copy:Data)=>{
      const next={...original,value:{...original.value,dependencyCopy:copy}};
      heldPreview.current=next;setBuildPreview(next);return next;
    };
    const {reviewHandle,expiresAt:_,...identity}=shown;
    const waiting=update({...identity,state:'unconfirmed'}); // Retire the one-use confirmation even when the ACK is lost.
    const stillShowing=()=>heldPreview.current===waiting&&previewOpen.current;
    void invoke(confirmDependencyCopy,{viewHandle:original.viewHandle,reviewHandle},value=>{
      if(!stillShowing())return; // Explicitly returning to the checkout never reopens the modal on a late ACK.
      const actual=checkedDependencyCopy(value,projectId,checkout,sourceVersion,shown.lockSha256);
      if(!actual||actual.state==='review'||!dependencyCopyPin.every(key=>actual[key]===shown[key])||
          actual.reason!==shown.reason||actual.previousLockSha256!==shown.previousLockSha256||
          handle.current!==original.viewHandle||draft.current.checkoutId!==checkout)
        throw Error('未核对到这次的新副本登记。');
      if(actual.state==='published')showPublishedDependencyCopy(actual);
      update(actual);
    },failure=>{if(stillShowing())setError(failureText(failure));});
  };
  const selectDependencyCopy=()=>{
    if(!canActOnDirectPreview()||unfinishedInput||!can('projects.development.draft'))return;
    const actual=checkedDependencyCopy(dependencyCopy,projectId,checkout,sourceVersion,preview?.lockedDependencies?.lockSha256);
    if(actual?.state!=='published')return;
    try{showPublishedDependencyCopy(actual);}catch(failure){setError(failureText(failure));return;}
    closeBuildPreview();chooseCheckout(actual.newCheckoutId);
  };
  const openDirectWork=(shown:Data,kind:DirectBuildKind='compiler')=>{
    const viewHandle=handle.current;
    if(!viewHandle||disabled||entered.current||draft.current.checkoutId!==checkout||!can('tasks.open'))return;
    void invoke('projects.development.inspect',{viewHandle,checkoutId:checkout},fresh=>{
      if(fresh.projectId!==projectId||fresh.checkoutId!==checkout||fresh.sourceDigest!==shown.sourceDigest||handle.current!==viewHandle||draft.current.checkoutId!==checkout)
        throw Error('副本版本已变化，没有打开其他构建。');
      setSource(fresh);
      const original=directNodeWork(kind==='vite'?fresh.directViteBuildWork:fresh.directNodeBuildWork,projectId,checkout,shown.sourceDigest,kind);
      if(!original||!directBuildRegistered(original)||!sameDirectBuild(original,shown))
        throw Error('未核对到同一笔原构建，没有打开其他任务。');
      closeBuildPreview();onOpenTask(original.requestId,checkout,kind==='vite'?{runId:original.runId,epoch:original.epoch}:undefined);
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
  const showBuildReviewFallback=canReviewSelectedBuild&&!directWorkAtTop&&!versionBuild&&!firstBuild&&!acceptedWorkAtTop&&!buildLookupUnavailable;
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
    {heldLock?.state==='saved'&&heldLock.saved===true&&<p className="pw-field-hint" role="status">已补入并回读这份锁文件。准备依赖和构建仍要分别确认。</p>}
    {heldLock&&['unknown','not_written'].includes(heldLock.state)&&<div className="pw-root-lock-intake">
      <p className="pw-field-hint" role="status">{heldLock.state==='unknown'?'未确认这次锁文件写入，不会重复提交。':'原位置已存在文件，没有覆盖。'}</p>
      <Button variant="secondary" disabled={disabled} onClick={()=>void read()}>重新读取这份副本</Button>
    </div>}
    {staleLock&&<div className="pw-root-lock-intake">
      <p className="pw-field-hint" role="status">原源码版本已变化，未补入所选锁文件。</p>
      <Button variant="secondary" disabled={disabled} onClick={()=>supplyLock(dismissSuppliedLock)}>放弃这次选择</Button>
    </div>}
    {(exactBuildNext||checkedSave&&buildStepVisible)&&<section ref={buildStepSection} aria-label="这份私人副本的构建下一步">
      <p className="pw-field-hint">本机副本 <bdi>{selectedCopy?.name||name}</bdi> · <code>{checkout.slice(0,8)}</code>。
        {sourceVersion?<> 已保存源码版本 <code>{sourceVersion.slice(0,10)}</code>。</>:
          acceptedWorkAtTop?<> 已交办版本 <code>{acceptedBuild!.versionedBuildSourceDigest.slice(0,10)}</code>。</>:null}</p>
      {checkedSave&&<p className="pw-field-hint" role="status">{checkedSave.matches?
        <>这版包含刚确认保存的 <bdi>{checkedSave.path}</bdi>。</>:
        <>上次保存的 <bdi>{checkedSave.path}</bdi> 与当前版本不一致。若继续，只审阅上面显示的当前版本。</>}</p>}
      {directWorkAtTop&&directWork?<div className="pw-form-actions"><Button ref={buildNextAction} disabled={disabled||!can('projects.development.inspect')}
        onClick={()=>openDirectWork(directWork,sourceBuildKind)}>{sourceBuildKind==='vite'?'打开原网页构建':'打开原本机构建'}</Button></div>:(versionWorkAtTop||acceptedWorkAtTop)?<>
        <p className="pw-field-hint" role="status">{acceptedWorkAtTop?'构建交办已受理，尚不代表命令获准或运行。可在原工作中继续核对。':
          '这一版已有原构建工作。打开可查看实际进展、待确认命令和已保存成果。'}</p>
        {acceptedBuild?.error&&<p className="pw-field-hint">{acceptedBuild.error}</p>}
        <div className="pw-form-actions"><Button ref={buildNextAction} disabled={disabled||versionWorkAtTop&&!can('projects.development.inspect')}
          onClick={()=>versionWorkAtTop?openVersionBuild('work'):acceptedBuild&&onOpenTask(acceptedBuild.taskId,acceptedBuild.checkoutId)}>打开原构建工作</Button></div>
      </>:showMissingLock?<>
        <p className="pw-field-hint" role="status">这份源码声明了要用的依赖，但副本根目录没有 <bdi>{missingRequiredLock}</bdi>。先补齐同版本的锁文件，再核对构建；不会自动生成锁或安装依赖。</p>
        {canSupplyLock&&heldLock?.sourceDigest===sourceVersion&&<div className="pw-root-lock-intake">
          {heldLock.state==='picking'||heldLock.state==='reading'||heldLock.state==='writing'?
            <p role="status" className="pw-field-hint">{heldLock.state==='picking'?'等待系统文件选择…':heldLock.state==='reading'?'正在核对这份锁文件…':'正在写入并回读原锁文件…'}</p>:
            heldLock.state==='cancelled'?<p role="status" className="pw-field-hint">已取消，未读取或写入文件。</p>:
            heldLock.state==='unavailable'?<p role="status" className="pw-field-hint">没有核对到匹配的锁，未写入文件。可重新选择原件。</p>:null}
          {checkedLock&&<p className="pw-field-hint"><bdi>{checkedLock.path}</bdi> · {checkedLock.bytes} 字节 · <code>{checkedLock.sha256.slice(0,12)}</code>。已核对与当前 <bdi>package.json</bdi> 匹配，只写入缺少的锁，不安装依赖。</p>}
        </div>}
        <div className="pw-form-actions">
          {canSupplyLock&&(!heldLock||['cancelled','unavailable'].includes(heldLock.state))&&<Button ref={buildNextAction} disabled={disabled}
            onClick={()=>supplyLock(pickSuppliedLock)}>选择已有的 npm 锁文件</Button>}
          {canSupplyLock&&heldLock?.sourceDigest===sourceVersion&&heldLock.state==='selected'&&<Button ref={buildNextAction} disabled={disabled}
            onClick={()=>supplyLock(reviewSuppliedLock)}>核对所选锁文件</Button>}
          {canSupplyLock&&checkedLock?.sourceDigest===sourceVersion&&<Button ref={buildNextAction} disabled={disabled}
            onClick={()=>supplyLock(confirmSuppliedLock)}>确认补入这份锁</Button>}
          {canSupplyLock&&heldLock?.sourceDigest===sourceVersion&&['selected','review'].includes(heldLock.state)&&
            <Button variant="ghost" disabled={disabled} onClick={()=>supplyLock(dismissSuppliedLock)}>取消选择</Button>}
          {can('projects.development.files.open')&&<Button ref={canSupplyLock?undefined:buildNextAction} variant={canSupplyLock?'secondary':'primary'} disabled={disabled}
            onClick={openSelectedCopyFiles}>打开这份副本的文件</Button>}
        </div>
      </>:showReactLock?<p className="pw-field-hint" role="status">
        这份锁使用 TypeScript <bdi>{reactLock.lockedVersion}</bdi>。此处的本机网页构建目前只支持锁定到 TypeScript <bdi>{reactLock.verifiedVersion}</bdi> 的标准 React TypeScript 项目。
        你仍可编辑这份副本。没有改锁、准备依赖或构建。
      </p>:nodeTools&&firstBuildAction&&<>
        <p className="pw-field-hint" role="status">{buildToolsBlocked?
          <>{nodeTools.missing.length?`尚未核对到 ${nodeTools.missing.map(tool=>tool==='node'?'Node.js':tool).join('、')}。`:'开发环境尚未核对为可运行。'}
            {missingPnpm?' 内置准备提供 Node.js 和 npm，不会用 npm 替代 pnpm。':
              builtInSetupUnavailable?' 当前安装包不能准备本机运行环境；文件仍可编辑。':
              showToolSetupByBuild?' 请先审阅手机工具的准备内容。返回后重新核对，不会自动构建。':' 这个窗口不能开始内置准备；仍可编辑文件。'}</>:
          firstBuildAction==='preview'?localViteReview?'先核对这版的本机网页构建与依赖，不调用模型。':`已核对 Node.js 和 ${nodeTools.manager}。先查看这版的命令与依赖；不用先连接模型。`:
          firstBuildAction==='model'?`已核对 Node.js 和 ${nodeTools.manager}。构建任务还需要连接默认模型；连接不会开始构建。`:
          `已核对 Node.js 和 ${nodeTools.manager}，已有默认模型配置。下一步只看构建方案，依赖和运行仍要分别确认。`}</p>
        <div className="pw-form-actions">
          {firstBuildAction==='tools'&&<Button ref={buildNextAction} disabled={disabled} onClick={prepareTools}>{view?.runtime?.installed===true?'检查与修复手机工具':'打开手机工具准备'}</Button>}
          {firstBuildAction==='refresh'&&<Button ref={buildNextAction} variant="secondary" disabled={disabled} onClick={()=>void read()}>重新核对手机工具</Button>}
          {firstBuildAction==='preview'&&<Button ref={buildNextAction} disabled={disabled} onClick={previewNodeBuild}>{localViteReview?'核对网页构建与依赖':nativeDirectDependencies?'核对本机构建与依赖':'查看本机构建方案'}</Button>}
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
      </> : available.length === 0 && can('projects.development.prepare') && <div className="pw-form-actions"><Button variant={prepareForFiles?'primary':'secondary'} disabled={disabled || copyUnconfirmed || copyResult?.partial === true}
        onClick={() => void copy(false,prepareForFiles)}>{prepareForFiles?'准备副本并打开文件':'准备这个项目的私人副本'}</Button></div>}
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
      {!filesOpen && savedFileHere && <>
        <p className="pw-field-hint pw-checkout-identity">
          上次确认保存：<bdi>{savedFileHere.path}</bdi> · <code>{savedFileHere.savedHash.slice(0,10)}</code>。重开时会核对原文件，没有另存一份历史副本。
        </p>
        {terminalBySave&&terminalAction}
      </>}
      {!filesOpen&&importedEntry&&<p className="pw-field-hint pw-checkout-identity">
        {starterEntry?'起步网页源码：':importedEntry.root?<>副本顶层保留了 <bdi>{importedEntry.root}/</bdi>。入口源码：</>:<>副本顶层的入口源码：</>}
        <bdi>{importedEntry.path}</bdi>。{starterEntry?' 可先修改并保存，再另行核对构建。':<>
          {webTools?.kind==='static'?' 可直接编辑，不用准备 Node.js。':''}
          {' 预览请查看原任务已登记的页面，或打开「已保存的构建网页」。导入的源码不会直接运行。'}
        </>}
      </p>}
      {!filesOpen&&!importedEntry&&webTools?.kind==='static'&&<p className="pw-field-hint">根目录有 <code>index.html</code>，直接编辑不用准备 Node.js。这个入口不是已登记的预览或构建成果。</p>}
      <div className="pw-form-actions">
        {importedEntry&&can('projects.development.files.open')&&<Button variant="secondary" disabled={busy||!!review||filesOpen||starterEntry&&disabled}
          aria-label={starterEntry?`编辑网页：${importedEntry.path}`:`打开 ${importedEntry.path} 源码`} onClick={()=>{
            if(starterEntry){
              const original=originalImportedEditor(projectId);
              if(disabled||entered.current||handle.current!==view.viewHandle||draft.current.checkoutId!==importedEntry.checkoutId||
                original?.sourceKind!==builtinViteStarter||original.checkoutId!==importedEntry.checkoutId){
                setImportEditorNotice('原网页副本已变化，请先重新核对。');return;
              }
              setImportEditorNotice('');
            }
            setHostEditor(null);setFileWindow({checkoutId:importedEntry.checkoutId,firstEntry:importedEntry});
          }}>
          {starterEntry?'编辑网页':'打开入口源码'}
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
      {!terminalBySave&&terminalAction}
      {view.model?.configured !== true && (!exactBuildNext || !['model','preview'].includes(firstBuildAction)&&!!goal.trim()) &&
        <p className="pw-local-inline-notice">交办目标需要默认模型；直接编辑文件、找回已保存的网页不需要。<Button variant={exactBuildNext?'ghost':'secondary'}
          disabled={exactBuildNext||protectModelDraft?disabled||!can('projects.development.draft'):busy||pending||!!review||filesOpen}
          onClick={exactBuildNext?connectBuildModel:connectProjectModel}>连接模型</Button></p>}
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
    {active && view && fileWindow && <ProjectCheckoutFiles key={`${view.viewHandle}|${fileCheckout}`} projectId={projectId} checkoutId={fileCheckout} viewHandle={view.viewHandle} run={fileWindow.reopenedFiles?runReopenedFiles:run} hostFile={hostEditor || undefined}
      resumeSaved={fileWindow.resumeSaved} firstEntry={fileWindow.firstEntry} buildResume={fileWindow.buildResume} pythonResume={fileWindow.pythonResume}
      pythonSetupResume={fileWindow.pythonSetupResume} onClose={closeFiles}
      onPreparePythonTools={pythonToolSetup?prepareSavedPythonTools:undefined}
      onOpenPythonWork={!hostEditor&&can('tasks.open')?work=>{
        if (work.projectId!==projectId || work.checkoutId!==fileCheckout || !active) return;
        onTaskRegistered();onOpenTask(work.taskId,work.checkoutId,{runId:work.runId,epoch:work.epoch});
      }:undefined}
      onPrepareBuild={canPrepareSavedBuild?prepareSavedBuild:undefined} prepareBuildAfterPath={missingRequiredLock||undefined}
      prepareBuildLabel={showViteSaveLabel?'从这次保存核对网页构建':directImportedSave||dependencyForSave?'核对已保存源码':undefined} registerBack={registerFilesBack} />}
    {originalDifference&&<SettingsDialog isOpen title="本轮原改稿" closeLabel="返回当前工作副本" onClose={closeRetainedOriginal}>
      <div className="pw-project-draft">
        <strong className="pw-project-draft-path">{originalDifference.path}</strong>
        <p className="pw-field-hint">固定原改稿 <code>{originalDifference.afterSha256.slice(0,8)}</code>。只读，不包含当前工作副本的后续编辑。关闭仍留在当前副本。</p>
        <ProjectDraftChanges key={originalDifference.draftId} before={originalDifference.before} after={originalDifference.after}
          version={originalDifference.afterSha256.slice(0,8)} beforeLabel={originalDifference.beforeFromOwnerRevision?'本轮选中的已存稿':'本轮起点'}/>
      </div>
    </SettingsDialog>}
    <SettingsDialog isOpen={!!review||!!preview} title={preview?dependencyCopy&&!historicalLockCopy?'另建依赖副本':dependencyNext?'核对这版的锁定依赖':previewVite?'构建与预览网页':'本机构建方案':review?.draftBuild===true?review.dependencyState==='needs_lock_review'?'核对这版的依赖准备':review.existingCopy?'核对本机 Node 构建':review.editedCopy?'核对当前编辑版的构建':'核对这份改稿的本机构建':'开始这个项目任务？'} onClose={preview?closeBuildPreview:dismiss} closeLabel={preview||review?.draftBuild===true?'返回项目副本':'返回修改目标'}>{preview?<div className="pw-local-review">
      <p className="pw-field-hint">{name} · 副本 <code>{preview.checkoutId.slice(0,8)}</code> · 已保存版本 <code>{preview.sourceDigest.slice(0,10)}</code></p>
      {!dependencyNext&&<strong>{preview.manager} run build</strong>}
      <NodeBuildLock plan={preview} preview/>
      {!dependencyNext&&<NodeBuildScripts plan={preview}/>}
      <p>这次只核对已保存源码，没有下载或运行。{preview.preservedProjectDraft&&'你已写的目标留在草稿。'}</p>
      {(!dependencyCopy||historicalLockCopy)&&(dependencyOffer?<p>只为这个缺少依赖目录的副本准备锁定依赖。不调用模型、不开始构建。</p>:
        buildOffer?previewVite?<p>只在这部手机构建这版网页，不调用模型或安装依赖。构建可能联网并写入副本，只运行可信项目。确认后进入原工作的「概览」。本次网页可预览时，点「预览本次构建」即可查看，不用先保存。想保留时再保存网页包；网页不会自动打开或发布。</p>:
          <p>将只在这部手机构建这份已准备依赖的 TypeScript 副本，不调用模型或安装依赖。构建可写入副本，只运行可信项目。在原工作的「过程 → 工具调用」核对 <bdi>{buildOffer.outputPath}</bdi>，有实际输出才能另行保存。</p>:
        buildWork?<p>{previewVite?'这版已有一笔网页构建。':'这版已有一笔本机构建。'}打开原工作核对，不会再运行。</p>:
        dependencyWork?<p>这版已有依赖准备记录。请打开原工作核对结果，不会重新下载或开始构建。</p>:
        noDirectDependencyStep(preview)?<p>还没核对到这份副本所需的包，当前不能在这里确认准备依赖或构建。
          {buildModelReview?' 如需使用模型，可先审阅交办内容，再另行确认。':
            !terminalFromPreview&&buildModelConnect&&can('projects.development.reviewDraftBuild')?' 如需模型协助，可先连接默认模型；连接不会开始构建。':''}</p>:
        <p>{previewVite?'这版还没有可确认的本机网页构建。没有开始构建或预览。':'这版还没有可直接确认的本机构建。交办其他构建才需要模型，命令仍须另行批准。'}</p>)}
      {dependencyCopy&&<>
        <strong>{dependencyCopy.name} · <code>{dependencyCopy.newCheckoutId.slice(0,8)}</code></strong>
        {dependencyCopy.state==='review'?<p>{!historicalLockCopy&&'原副本里的依赖不匹配这版锁。'}可另建同一项目内的新副本，只复制这版已保存的 {dependencyCopy.fileCount} 个源文件，不带入旧依赖、Git 历史或构建输出。原副本保留；这里只建副本，不下载依赖或开始构建。</p>:
          dependencyCopy.state==='published'?<p role="status">已核对新副本，仍选中原副本。选择新副本后，再核对它当前的源码和依赖。{unfinishedInput&&'当前交办原样保留，没有切换。'}</p>:
          <p role="status">{busy?'正在核对新副本的登记。':'新副本登记尚未确认，没有切换或重试，不会覆盖可能已写入的文件。请返回后重新核对这一版的原方案。'}</p>}
      </>}
      {terminalFromPreview&&!dependencyNext&&!buildOffer&&!buildWork&&<p>你也可以核对同一副本的手动终端。打开终端不会代你准备依赖或执行构建。</p>}
      <div className="pw-form-actions">
        <Button variant="secondary" onClick={closeBuildPreview}>返回副本</Button>
        {dependencyOffer&&nativeDirectDependencies&&<Button disabled={disabled||!can('tasks.open')} onClick={confirmOwnerDependencies}>确认准备锁定依赖</Button>}
        {buildOffer&&nativeDirectBuild&&<Button disabled={disabled||!can('tasks.open')} onClick={confirmOwnerNodeBuild}>{previewVite?'确认构建网页':'确认在这部手机构建'}</Button>}
        {dependencyCopy?.state==='review'&&can(confirmDependencyCopy)&&<Button variant={historicalLockCopy?'secondary':undefined} disabled={disabled} onClick={createDependencyCopy}>确认另建副本</Button>}
        {dependencyCopy?.state==='published'&&!unfinishedInput&&can('projects.development.draft')&&<Button variant={historicalLockCopy?'secondary':undefined} disabled={disabled} onClick={selectDependencyCopy}>选择新副本</Button>}
        {buildWork&&<Button disabled={disabled||!can('tasks.open')||!can('projects.development.inspect')}
          onClick={()=>{if(canActOnDirectPreview())openDirectWork(buildWork,previewVite?'vite':'compiler');}}>{previewVite?'打开原网页构建':'打开原本机构建'}</Button>}
        {dependencyWork&&can('tasks.open')&&<Button variant={dependencyNext?undefined:'ghost'} disabled={disabled} onClick={openDependencyWork}>打开原依赖准备</Button>}
        {terminalFromPreview&&!dependencyNext&&!buildOffer&&!buildWork&&<Button disabled={disabled} onClick={()=>leaveBuildPreview(()=>prepareTerminal(preview.sourceDigest))}>核对同一副本的手动终端</Button>}
        {buildModelReview&&<Button variant="ghost" disabled={disabled} onClick={()=>leaveBuildPreview(reviewNodeBuild)}>改为核对模型交办</Button>}
        {buildModelConnect&&<Button variant={terminalFromPreview||buildOffer?'ghost':undefined}
          disabled={disabled} onClick={()=>leaveBuildPreview(connectBuildModel)}>{buildOffer?'改用模型交办':'连接默认模型'}</Button>}
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
