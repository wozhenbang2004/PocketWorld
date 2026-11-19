import { FluidValueSlider } from './forms-library/uiux/FluidValueSlider';
/**
 * PocketWorld's controlled work surface.
 * Layout/regions are adapted from the selected Affect AssistantHome, AgentRun,
 * Files/Folder and ReviewCenter sources, retained under shell-library/upstream.
 * Real source components are imported below. Native task/draft/version authorities
 * remain in the host; a bridge acceptance never becomes a fabricated success.
 */
import {useCallback,useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {motion} from 'motion/react';
import { IconAccount, IconAdd, IconArrowLeft, IconBook, IconCalendar, IconCalendarCheck, IconChat, IconCheckCircle, IconChevronDown, IconChevronRight, IconClock, IconClose, IconError, IconFile, IconFolder, IconGrid, IconHistory, IconImage, IconInfo, IconLayers, IconLink, IconMemory, IconMenu, IconMonitor, IconMore, IconNewChat, IconNotification, IconPlay, IconQuote, IconRefresh, IconSearch, IconSettings, IconShieldCheck, IconSparkles, IconStop, IconTeam, IconTerminal, IconTool } from './icons';
import {WorkspaceNavigator,type OwnerNavigationMemory} from './WorkspaceNavigator';

import {bridge} from './bridge';
import {usePocketSnapshot} from './usePocketSnapshot';
import {foregroundConversationDraft} from './conversation-stream';
import {replyActivityPhase,useReplyRows} from './ConversationReply';
import {ConversationLinkedReason,ConversationOpening,ConversationStarters,ConversationTurnActivity} from './ConversationSurface';
import {MotionFeedback,useCollectionMotion} from './library/journey-motion';
import {useConversationReading} from './useConversationReading';
import {ConversationReadingNav} from './ConversationReadingNav';
import {ConversationDelivery,conversationPending,useConversationRegistration} from './ConversationDelivery';
import {WorkSearch,type WorkSearchItem,type WorkSearchMemory} from './WorkSearch';
import {WorkSearchOriginalReader} from './WorkSearchOriginalReader';
import {ComposerWorkOptions} from './ComposerWorkOptions';
import type {CapabilityWorkspaceMemory} from './CapabilitiesWorkspace';
import {usePocketAppearance} from './usePocketAppearance';
import {useClosingSnapshot,useMessageArrivals,useReviewFocus,useSurfaceEnter} from './library/surface-motion';
import {chatCancelFeedback,chatCancelIdentity,chatCancelKey} from './chat-cancel';
import {presentOperation,getRecentTaskProgress,taskNeedsReconciliation} from './task-presentation';
import {TaskHistory} from './TaskHistory';
import {composerHasPayload} from './composer-send-policy';
import {composerPermissionView} from './composer-permissions';
import {PermissionPolicySettings,type PermissionLeaveGuard} from './PermissionPolicySettings';
import {taskHistoryIdentity} from './task-history';
import {useWorkDirectory} from './WorkDirectory';
import {WorkFindTitle,WorkRecoveryDirectory} from './WorkRecoveryDirectory';
import {isButlerHomeWork,workCollectionPresentation,type WorkCollectionFilter} from './work-collection';
import type {CapabilityPurpose} from './capability-journey';
import {AgentAvatar,PressButton,ChatComposer,AIAnswer,MessageActions,ToolCall,ThinkingLoader,CitedAnswer,BlurCrossfade,Skeleton,ShimmerText,Toaster,toast,ProgressBar,ProgressCircle} from './library';
import {Button,IconButton,TextField,TextAreaField,SearchField,SelectField,RadioOptions,CheckboxField,Accordion,AccordionItem,SettingsDialog} from './forms-library';
import {Drawer,PopoverTrigger,Popover,PopoverHeader,TreeView,FilesBrowser,Tabs,TabList,Tab,TabPanel,PermissionReview,type WorkNavItem} from './shell-library';
import Secondary from './Secondary';
import type {SettingsDirectoryOrigin} from './SettingsDirectory';
import ProfileWorkspace,{type PersonalTarget} from './ProfileWorkspace';
import {ConversationMemorySettings} from './ConversationMemorySettings';
import {NewConversationMemory} from './NewConversationMemory';
import DeveloperDiagnosticsWorkspace from './DeveloperDiagnosticsWorkspace';
import {SetupWorkspace,type SetupDestination,type SetupView} from './SetupWorkspace';
import {readSetup,type SetupCapability} from './setup-contract';
import {AttachmentWorkspace,type FileDraftTarget} from './AttachmentWorkspace';
import RemoteInboxWorkspace,{type NotificationViewMemory} from './RemoteInboxWorkspace';
import type {TeamAttentionLocator} from './NotificationTeamAttention';
import {ConversationOrganization,orderConversations} from './ConversationOrganization';
import {ConversationQueuedInputs} from './ConversationQueuedInputs';
import {ConversationHistoryWindow,type ConversationHistoryTaskOrigin} from './ConversationHistoryWindow';
import {ChatNavigationMenu} from './ChatNavigationMenu';
import {useNavigationGlass} from './useNavigationGlass';
import {NavigationChrome} from './NavigationChrome';
import {WorkObjectMenu} from './WorkObjectMenu';
import {TaskCapabilityWorkspace} from './TaskCapabilityWorkspace';
import {DeviceTaskScopeWorkspace} from './DeviceTaskScopeWorkspace';
import {PhoneControlWorkspace} from './PhoneControlWorkspace';
import {ProjectRuntimeHealth} from './ProjectRuntimeHealth';
import {ProjectLivePreview} from './ProjectLivePreview';
import {ProjectExecutionWorkspace} from './ProjectExecutionWorkspace';
import {RemoteTaskWorkspace,RemoteManagedSessionsWorkspace} from './RemoteTaskWorkspace';
import {AnswerSources,type AnswerSourceTarget} from './AnswerSources';
import {ProvidedMemories} from './ProvidedMemories';
import {MessageQuote,type MessageQuoteTarget} from './MessageQuote';
import {MessageOriginReader,type MessageOriginTarget} from './MessageOriginReader';
import {PhoneReturnedReport,phoneReportTarget,type PhoneReportTarget} from './PhoneReturnedReport';
import {ReadingView} from './ReadingView';
import {ReadingContext,type ReadingDocument,type ReadingSnapshot} from './reading-context';
import {ComposerSourceReferences,ComposerObjectReferences} from './ComposerSourceReferences';
import {ComposerPreviewReferences} from './ComposerPreviewReferences';
import {ProjectGitIntake,type PublicGitDraft} from './ProjectGitIntake';
import {ProjectPreviewPane,useProjectPreview} from './ProjectPreviewPane';
import {ProjectFileEditor} from './ProjectFileEditor';
import {ProjectWorkspaceOverview,type ProjectWorkCollectionView,type ProjectWorkEntry} from './ProjectWorkspaceOverview';
import {ProjectDevelopmentWorkspace} from './ProjectDevelopmentWorkspace';
import {ContinuedProjectBinding} from './ProjectWorkBinding';
import {ProjectWorkStart,emptyProjectWorkDraft,type ProjectWorkDraft} from './ProjectWorkStart';
import {ProjectWorkLauncher,type ProjectWorkPresentation} from './ProjectWorkLauncher';
import type {LocalWorkspaceDraft} from './LocalWorkspace';
import {fileWorkspaceKey,type FileLayer} from './project-file';
import {useProjectRevision} from './useProjectRevision';
import {packageOperationTarget,type PackagePreviewTarget} from './project-preview';
import {useProjectPackageHandoff} from './useProjectPackageHandoff';
import {TaskOutcome} from './TaskOutcome';
import {OwnerWorkFacts,WorkGoal} from './OwnerWorkFacts';
import {WorkExecutionPlace} from './WorkExecutionPlace';
import {OwnerWorkSupplement,type WorkSupplementTarget,type WorkSupplementLeave} from './OwnerWorkSupplement';
import {ownerWorkPresentation,type OwnerWorkTeam} from './owner-work-presentation';
import {teamDecisionEntryKey,type TeamDecisionOrigin,type TeamButlerRequest,type TeamOwnerWorkLink,type TeamRunFocus} from './team-navigation';
import {TaskApproval} from './TaskApproval';
import {continuationBlocks,continuationFailed,continuationRequest,continuationSettled,continuationUnknown,privateProjectTask,projectContinuation,type ContinuationSubmission} from './task-continuation';
import {TaskInference} from './TaskInference';
import {TokenBudgetsWorkspace} from './TokenBudgetsWorkspace';
import {QuickPanelVoiceDraft} from './QuickPanelVoiceDraft';
import {SkillDraftReview,type ConversationDraftTarget} from './SkillDraftReview';
import {MemoryDraftReview} from './MemoryDraftReview';
import {RouteReference,type RouteReferenceTarget} from './RouteReference';
import {ConversationDrafts} from './ConversationDrafts';
import {ConversationWork} from './ConversationWork';
import {ButlerHome} from './ButlerHome';
import {homeWorkIdentity} from './butler-work-entry';
import type {CloudGoal} from './cloud-goal';
import {ButlerReturnedReport,currentHomeReport,type VerifiedHomeReport} from './ButlerReturnedReport';
import type {TaskResultFile} from './TaskResultFiles';
import {PhoneTaskStatus} from './PhoneTaskStatus';
import {PhoneTaskOriginalRequest} from './PhoneTaskOriginalRequest';
import {PHONE_RECORD,PhoneOriginalActionReview} from './PhoneOriginalActionReview';
import {EarlierDraftPhoneStarts,BlankPhoneReceiptActions} from './BlankPhoneStarts';
import {PhoneNotificationReturn,originalPhoneNotice,type PhoneNotice} from './PhoneNotificationReturn';
import {PhoneScopeReview,type PhoneReviewDisplay} from './PhoneScopeReview';
import {PhonePauseInstruction,isPrivateLocalPhone} from './PhonePauseInstruction';
import {PhoneMainOwnerStep,mainPhoneStep} from './PhoneMainOwnerStep';
import {privatePhoneSurface,phoneSurfaceLabel} from './private-phone-surface';
import {ButlerPreparationEntries,ButlerPreparationDetail,type ButlerPreparationTarget} from './ButlerPreparations';
import {ownerWorkContinuationNote,ownerWorkContinuationReply,type WorkControlFeedback} from './owner-work-continuation';
import {backgroundConversationWork,conversationActivityLabel,conversationInlineAttention,conversationTaskCompact,conversationWorkActionLabel,foregroundConversationTurn,messageReplyContext,messageTaskResult,ownerWorkIdentity,privateTaskConversationId,workIdentityKey,type ReplyContext,type TaskResult,type WorkIdentity} from './conversation-work';
import './conversation-work.css';
import {taskMessageAnchors} from './task-outcome';
import {OwnerTaskQuestion,useOwnerQuestionDraft} from './OwnerTaskQuestion';
import {conversationModelName} from './conversation-model-selection';
import {ConversationModelPickerProvider,ConversationModelPickerControls,ConversationModelStartButton,ConversationModelPickerVisibility,type ConversationModelPickerHandle} from './ConversationModelPicker';
import {workspaceNavigationKey} from './workspace-directory';
import type {OwnerQuestionView} from './owner-task-question';
import {phoneStartReceipt,phoneStartFailure,phoneRunIdentity,messagePhoneResult,phoneConversationId,type PhoneStartReceipt,type PhoneResult} from './phone-start';
import {PhoneViewer,type PhoneViewerTask} from './phone-viewer';
import './app.css';
import './nav-settings-manga.css';
import './task-surface.css';

type Json=Record<string,any>;
type Task={id:string;goal:string;status:string;summary?:string;executionMode?:string;updatedAt?:number;raw?:Json;hostReceipt?:Json};
type Message={id?:string;role:string;content:string;at?:number;taskId?:string;submission?:Json;attachments?:Json[];memoryReference?:Json;retainedSources?:boolean;sourceRevision?:number;hostGoalTaskId?:string;quoteAvailable?:boolean;automaticLearningExcluded?:boolean;phoneResult?:PhoneResult;replyContext?:ReplyContext;taskResult?:TaskResult};
type Project={id:string;name:string};
type Review={reviewHandle:string;task?:Task;ownerQuestion?:Json|null;actions?:{action:string;label:string}[];approvals?:Json[];unresolvedOperations?:Json[];projectContinuation?:Json|null;capabilityApprovalRead?:Json;approvalExecutionScope?:Json};
type TaskReviewWindow={taskId:string;request:number;documentEpoch:number;resumeRevision:number};
type PhoneReview=PhoneReviewDisplay&{reviewHandle:string;expiresInMs?:number;documentEpoch?:number;originalDraft?:{composer:string;conversation:string;ownerEpoch:number;revision:number;origin:number};requestId?:string;draftRequestId?:string;provider?:string};
type TaskLayer='overview'|'goal'|'process'|'scope'|'homeReport';
type WorkspaceOrigin={route:string;label:string;drawerChat?:{id:string;draftKey:string;documentEpoch:number};settingsDirectory?:SettingsDirectoryOrigin;goal?:string;projectId?:string;contextProjectId?:string;task?:{id:string;layer:TaskLayer};focus?:HTMLElement|null;focusKey?:string;modelPicker?:boolean;parent?:WorkspaceOrigin|null};
type FileDraft={text:string;baseline:string;version:number;dirty:boolean;submitted?:string};
type ProjectTaskReturn={projectId:string;taskId:string;checkoutId?:string;query?:string;entry:ProjectWorkEntry;focusOutcome:boolean};
const asArray=<T,>(x:any):T[]=>Array.isArray(x)?x:[];
const obj=(x:any):Json=>x&&typeof x==='object'&&!Array.isArray(x)?x:{};
const labelStatus=(s:string)=>({queued:'等待执行',preparing:'准备中',running:'执行中',pausing:'正在暂停',paused:'已暂停',stopping:'正在停止',verifying:'正在核对',needs_confirmation:'请确认结果',awaiting_user:'待你处理',waiting_user:'待你处理',waiting_approval:'等待授权',awaiting_approval:'等待授权',interrupted:'已中断',completed:'已完成',failed:'未完成',cancelled:'已停止',canceled:'已停止',stopped:'已停止',waiting_permission:'等待授权',reconciling:'等待核对动作',reconciliation_required:'等待核对动作',unknown:'等待核对动作'} as Record<string,string>)[s]||s||'待处理';
const running=(t:Task)=>['queued','preparing','running','pausing','stopping','verifying'].includes(t.status);
const attention=(t:Task)=>workCollectionPresentation(t).group==='attention';
const terminal=(t:Task)=>['completed','failed','cancelled','canceled','stopped'].includes(t.status);
const workStatusLabel=(t:Task)=>workCollectionPresentation(t).label;
function phoneViewerTask(task?:Task):PhoneViewerTask|null{
 const raw=task?.raw,identity=phoneRunIdentity(task);
 if(!task||!raw||!identity||raw.id!==task.id||raw.kind!=='phone'||raw.actorId!=='owner'||raw.spaceId!=='private'||task.executionMode!=='background'||raw.origin&&raw.origin.kind!=='team_phone')return null;
 const evidence=obj(raw.evidence),session=[obj(evidence.phoneSession),obj(evidence.phonePreparation)].find(value=>value.taskId===identity.taskId&&value.runId===identity.runId&&value.coordinatorEpoch===identity.epoch);
 const blocked=!!session&&['awaiting_user','reconciling','failed'].includes(task.status)&&(session.state==='preparation_unconfirmed'||session.state==='not_initialized'&&evidence.code==='phone_preparation_unavailable');
 const detail=typeof task.summary==='string'&&task.summary.trim()?task.summary:typeof raw.summary==='string'&&raw.summary.trim()?raw.summary:blocked&&typeof evidence.message==='string'?evidence.message:'';
 return {...identity,title:task.goal,statusLabel:labelStatus(task.status),detail,preparation:blocked?'blocked':['queued','preparing'].includes(task.status)?'pending':undefined,status:terminal(task)||task.status==='interrupted'?(task.status==='failed'?'error':'ended'):task.status==='stopping'?'stopping':task.status==='paused'?'paused':['queued','preparing','running','verifying'].includes(task.status)?'running':'waiting'};
}
const taskBinding=(t?:Task|null)=>t?[t.id,t.updatedAt,t.raw?.runId,t.raw?.coordinatorEpoch,t.raw?.revision,t.raw?.resultReviewDigest,t.raw?.executionScope?.revision,t.raw?.executionScope?.executionPhase,t.raw?.executionScope?.planRevision,t.raw?.executionScope?.planArtifactRef,t.raw?.executionScope?.planHash].join(':'):'';
const timeLabel=(value:any)=>typeof value==='number'&&value>0?new Date(value).toLocaleString('zh-CN',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}):'';
const sourceRefs=(m:Message)=>asArray<Json>(m.memoryReference?.sources||m.memoryReference?.sourceRefs||m.submission?.sourceRefs).map((s,i)=>({...s,id:String(s.id||s.sourceId||s.sourceRef||i),title:s.title||s.name||s.label||'来源'}));
function treeData(files:Json[]){
 const roots:any[]=[];const nodes=new Map<string,any>();
 files.forEach(file=>{let parent=roots;let prefix='';String(file.path).split('/').filter(Boolean).forEach((part,index,parts)=>{prefix+=(prefix?'/':'')+part;let node=nodes.get(prefix);if(!node){node={id:prefix,name:part,icon:index===parts.length-1?IconFile:IconFolder,children:index===parts.length-1?undefined:[]};nodes.set(prefix,node);parent.push(node)}if(node.children)parent=node.children;});});return roots;
}
function useNativeDraft(owner:string,text:string,revision:any){
 const [value,setValue]=useState(text);const current=useRef({owner,dirty:false,value:text});const sent=useRef<string|null>(null);
 useEffect(()=>{const c=current.current;if(c.owner!==owner){current.current={owner,dirty:false,value:text};sent.current=null;setValue(text);return;}if(text===c.value){c.dirty=false;return;}if(!c.dirty||(sent.current!==null&&c.value===sent.current&&text==='')){c.value=text;c.dirty=false;sent.current=null;setValue(text);}},[owner,text,revision]);
 return {value:current.current.owner===owner?value:text,set:(next:string)=>{sent.current=null;current.current={owner,dirty:true,value:next};setValue(next)},markSent:()=>{sent.current=current.current.value},currentValue:()=>current.current.owner===owner?current.current.value.trim():'',owner};
}
/** Only exceptional delivery states belong in the reading stream; successful receipts stay in native history. */
function Receipt({receipt}:{receipt:Json}){
 const status=String(receipt.status||receipt.state||'');
 if(!['failed','rejected','unconfirmed','unknown','pending','withdrawn'].includes(status))return null;
 const labels:Record<string,string>={failed:'发送未确认',rejected:'未发送',unconfirmed:'结果待确认',unknown:'结果待确认',pending:'正在确认',withdrawn:'已撤回'};
 const detail=receipt.message||receipt.reason;
 return <MotionFeedback identity={`${status}:${detail||''}`} className="pw-input-receipt" role="status"><span>{labels[status]}</span>{detail&&<small>{detail}</small>}</MotionFeedback>;
}

export default function App(){
 const navigationMenuGlass=useNavigationGlass<HTMLButtonElement>();
 const navigationBackGlass=useNavigationGlass<HTMLButtonElement>();
 const chatBackGlass=useNavigationGlass<HTMLButtonElement>();
 const headerActionsGlass=useNavigationGlass<HTMLDivElement>();
 const [skillDraftTarget,setSkillDraftTarget]=useState<ConversationDraftTarget|null>(null);
 const [memoryDraftTarget,setMemoryDraftTarget]=useState<ConversationDraftTarget|null>(null);
 const [personalTarget,setPersonalTarget]=useState<PersonalTarget>({view:'home'}),[personalTargetKey,setPersonalTargetKey]=useState(0);
 const [conversationMemoryOpen,setConversationMemoryOpen]=useState(false),[conversationMemoryMode,setConversationMemoryMode]=useState<{conversationId:string;enabled:boolean}|null>(null);
 const conversationMemoryBack=useRef<(()=>boolean)|null>(null);
 const registerConversationMemoryBack=useCallback((handler:(()=>boolean)|null)=>{conversationMemoryBack.current=handler},[]);
 const [routeReferenceTarget,setRouteReferenceTarget]=useState<RouteReferenceTarget|null>(null);
 const snapshot=usePocketSnapshot() as any;const nativeState:Json=obj(snapshot?.state);const foregroundTurn=foregroundConversationTurn(nativeState);const state:Json={...nativeState,foregroundTurn,chatActiveTurn:foregroundTurn,chatBusy:!!foregroundTurn};const composer:Json=obj(snapshot?.composer);const phoneDraft:Json=obj(snapshot?.phoneDraft);
 usePocketAppearance(state.appearance);
 const commands=asArray<string>(snapshot?.capabilities?.commands);const commandKey=commands.join('\u0000');const commandSet=useMemo(()=>new Set(commands),[commandKey]);const can=useCallback((action:string)=>commandSet.has(action),[commandSet]);
 const messages=asArray<Message>(state.messages),tasks=asArray<Task>(state.tasks),projects=asArray<Project>(state.projects),files=asArray<Json>(state.files),conversations=asArray<Json>(state.conversations);
 const [route,setRoute]=useState('butler'),[workOpen,setWorkOpen]=useState(false),[commandOpen,setCommandOpen]=useState(false),[taskId,setTaskId]=useState<string|null>(null),[taskOpen,setTaskOpen]=useState(false),[filter,setFilter]=useState('all'),[taskQuery,setTaskQuery]=useState('');
 const isChatSurface=route==='chat'||route==='butler';
 const butlerScrollTop=useRef(0),butlerSendConversation=useRef<string|null>(null);
 useEffect(()=>{setConversationMemoryOpen(false);setConversationMemoryMode(null)},[route,state.conversationId]);
 const secondaryBeforeLeave=useRef<PermissionLeaveGuard|null>(null);
 const registerSecondaryBeforeLeave=useCallback((guard:PermissionLeaveGuard|null)=>{secondaryBeforeLeave.current=guard},[]);
 const profileBeforeLeave=useRef<((next:()=>void)=>boolean)|null>(null);
 const registerProfileBeforeLeave=useCallback((guard:((next:()=>void)=>boolean)|null)=>{profileBeforeLeave.current=guard},[]);
 const notificationMemory=useRef<NotificationViewMemory>({});
 const [teamAttentionFocus,setTeamAttentionFocus]=useState<(TeamAttentionLocator&{requestId:string;ownerWork?:TeamDecisionOrigin})|undefined>();
 const [teamRunFocus,setTeamRunFocus]=useState<TeamRunFocus|undefined>();
 const profileOrigin=useRef({route:'butler',drawerOpen:false,scrollTop:0});
 const profileRestoreScroll=useRef<number|null>(null);
 const personalMetadata=obj(state.personalProfile);
 const personalRevision=Number(personalMetadata.revision||0),personalDocumentEpoch=bridge.getDocumentEpoch();
 const [personalIdentity,setPersonalIdentity]=useState<Json|null>(null);
 useEffect(()=>{
  let active=true;
  if(!can('profile.identity')){setPersonalIdentity(null);return()=>{active=false}};
  void bridge.command('profile.identity').then(value=>{const profile=obj(obj(value).profile);if(active&&Number(profile.revision)>=personalRevision)setPersonalIdentity(profile)}).catch(()=>{});
  return()=>{active=false};
 },[can,personalRevision,personalDocumentEpoch]);
 const personalProfile=personalIdentity&&Number(personalIdentity.revision)>=personalRevision?personalIdentity:personalMetadata;
 const personalName=String(personalProfile.nickname||personalProfile.addressAs||'我的').trim()||'我的';
 const personalAvatar=typeof personalProfile.avatarDataUrl==='string'&&/^data:image\/(?:jpeg|png|webp);base64,/.test(personalProfile.avatarDataUrl)&&personalProfile.avatarDataUrl.length<=300000?personalProfile.avatarDataUrl:'';
 const [setupMode,setSetupMode]=useState<'onboarding'|'center'>('center'),[setupCapability,setSetupCapability]=useState<SetupCapability|undefined>(),[setupRuntimeTask,setSetupRuntimeTask]=useState<string|undefined>();
 const [setupView,setSetupView]=useState<SetupView>('overview');
 const setupOrigin=useRef<WorkspaceOrigin>({route:'butler',label:'返回管家'}),setupReturnPending=useRef(false);
 const openingModelSetup=useRef(false);
 const [modelSetupOpening,setModelSetupOpening]=useState(false);
 const relatedOrigin=useRef<WorkspaceOrigin|null>(null);
 const [localWorkspaceDraft,setLocalWorkspaceDraft]=useState<LocalWorkspaceDraft|undefined>();
 const [projectSections,setProjectSections]=useState<Record<string,'work'|'files'>>({});
 const [projectWorkDrafts,setProjectWorkDrafts]=useState<Record<string,ProjectWorkDraft>>({});
 const [projectWorkPresentations,setProjectWorkPresentations]=useState<Record<string,ProjectWorkPresentation>>({});
 const [projectWorkReviewOpen,setProjectWorkReviewOpen]=useState(false);
 const projectWorkBack=useRef<(()=>boolean)|null>(null);
 const registerProjectWorkBack=useCallback((handler:(()=>boolean)|null)=>{projectWorkBack.current=handler},[]);
 const [preparationTarget,setPreparationTarget]=useState<ButlerPreparationTarget|null>(null),[preparationRevision,setPreparationRevision]=useState(0);
 const [homeHasPreparations,setHomeHasPreparations]=useState(false);
 const preparationBack=useRef<(()=>boolean)|null>(null);
 const registerPreparationBack=useCallback((handler:(()=>boolean)|null)=>{preparationBack.current=handler},[]);
 const preparationBeforeLeave=useRef<(()=>Promise<boolean>)|null>(null);
 const inlinePreparationOrigin=useRef<{conversationId:string;top:number;trigger:HTMLElement|null}|null>(null);
 const registerPreparationBeforeLeave=useCallback((handler:(()=>Promise<boolean>)|null)=>{preparationBeforeLeave.current=handler},[]);
 const closePreparation=useCallback(()=>{
  preparationBeforeLeave.current=null;setPreparationTarget(null);setPreparationRevision(value=>value+1);
 },[]);
 const leaveInlinePreparation=useCallback(async()=>{
  const beforeLeave=preparationBeforeLeave.current,navigation=navigationWindow.current;
  if(!beforeLeave)return true;
  if(!await beforeLeave()||preparationBeforeLeave.current!==beforeLeave||navigationWindow.current!==navigation)return false;
  closePreparation();
  return true;
 },[closePreparation]);
 const [returningConversation,setReturningConversation]=useState('');
 const conversationReturnRequest=useRef(0);
 useEffect(()=>{const cancel=()=>{if(document.hidden){conversationReturnRequest.current++;setReturningConversation('')}};document.addEventListener('visibilitychange',cancel);return()=>document.removeEventListener('visibilitychange',cancel)},[]);
 const historyWindowBack=useRef<(()=>boolean)|null>(null);
 const registerHistoryWindowBack=useCallback((handler:(()=>boolean)|null)=>{historyWindowBack.current=handler},[]);
 const queuedInputsBack=useRef<(()=>boolean)|null>(null);
 const registerQueuedInputsBack=useCallback((handler:(()=>boolean)|null)=>{queuedInputsBack.current=handler},[]);

 const navigationWindow=useRef(0);const [navigationRevision,setNavigationRevision]=useState(0),[secondaryPage,setSecondaryPage]=useState('');
 const [cloudGoal,setCloudGoal]=useState<(CloudGoal&{navigation:number})|null>(null);
 useEffect(()=>{if(route!=='remote')setCloudGoal(null)},[route]);
 const consumeCloudGoal=useCallback((id:string)=>setCloudGoal(old=>old?.id===id?null:old),[]);
 const [settingsRestore,setSettingsRestore]=useState<(SettingsDirectoryOrigin&{revision:number})>();
 const [organizationTarget,setOrganizationTarget]=useState<string|null>(null);
 const workSearchMemory=useRef<WorkSearchMemory>({}),capabilityMemory=useRef<CapabilityWorkspaceMemory>({});
 const searchOpenRequest=useRef(0);
 const [searchReturn,setSearchReturn]=useState(false),[searchOriginal,setSearchOriginal]=useState<WorkSearchItem|null>(null);
 const [desktopNavigation,setDesktopNavigation]=useState(()=>window.matchMedia('(min-width: 1024px)').matches);
 const showWorkSearch=useCallback(()=>{searchOpenRequest.current++;setCommandOpen(true);setWorkOpen(!desktopNavigation)},[desktopNavigation]);
 const closeSearchOriginal=useCallback(()=>{setSearchOriginal(null);showWorkSearch()},[showWorkSearch]);
 useEffect(()=>{const invalidate=()=>{if(document.hidden)searchOpenRequest.current++};document.addEventListener('visibilitychange',invalidate);return()=>document.removeEventListener('visibilitychange',invalidate)},[]);
 const [chatMenuOpen,setChatMenuOpen]=useState(false);const chatMenuTrigger=useRef<HTMLButtonElement>(null);
 const navigationReturnFocus=useRef<HTMLElement|null>(null);
 const [navigationMoreOpen,setNavigationMoreOpen]=useState(false);
 const ownerNavMemory=useRef<OwnerNavigationMemory>({});
 const navigationDrawer=useRef<HTMLDivElement>(null);
 const attachNavigationDrawer=useCallback((node:HTMLDivElement|null)=>{
  navigationDrawer.current=node;
  const position=profileRestoreScroll.current,body=node?.querySelector<HTMLElement>('.pw-drawer-body');
  if(position!==null&&body){body.scrollTop=position;profileRestoreScroll.current=null}
 },[]);
 useLayoutEffect(()=>{
  if(workOpen)attachNavigationDrawer(navigationDrawer.current);
 },[workOpen,attachNavigationDrawer]);
 useEffect(()=>{setChatMenuOpen(false)},[route,state.conversationId,desktopNavigation]);
 useEffect(()=>{setNavigationMoreOpen(false)},[route,state.conversationId,desktopNavigation,workOpen,commandOpen]);
 const [navigationCompact,setNavigationCompact]=useState(false);
 const effectiveCompact=desktopNavigation&&navigationCompact&&!commandOpen&&!navigationMoreOpen;
 useEffect(()=>{const media=window.matchMedia('(min-width: 1024px)');const update=()=>setDesktopNavigation(media.matches);update();media.addEventListener('change',update);return()=>media.removeEventListener('change',update)},[]);
 const previousDesktopNavigation=useRef(desktopNavigation);
 useEffect(()=>{if(previousDesktopNavigation.current===desktopNavigation)return;previousDesktopNavigation.current=desktopNavigation;setWorkOpen(!desktopNavigation&&commandOpen)},[desktopNavigation,commandOpen]);
 const workSearch=useRef<HTMLButtonElement>(null),previousCommandOpen=useRef(false);
 useEffect(()=>{if((workOpen||desktopNavigation)&&previousCommandOpen.current&&!commandOpen)workSearch.current?.focus({preventScroll:true});previousCommandOpen.current=commandOpen;},[workOpen,desktopNavigation,commandOpen]);
 const [taskLayer,setTaskLayer]=useState<TaskLayer>('overview'),[projectPickerOpen,setProjectPickerOpen]=useState(false),[fileDisplayOpen,setFileDisplayOpen]=useState(false);
 const [taskFiltersOpen,setTaskFiltersOpen]=useState(false),[endedScopeOrigin,setEndedScopeOrigin]=useState<{filter:WorkCollectionFilter;query:string}|null>(null);
 const [phoneViewerExpanded,setPhoneViewerExpanded]=useState(false);
 const phoneNotification=originalPhoneNotice(state.originalPhoneNotification);
 const [phoneNoticePin,setPhoneNoticePin]=useState<PhoneNotice|null>(null),[phoneNoticePanel,setPhoneNoticePanel]=useState<string|null>(null);
 const phoneNoticeRef=useRef<PhoneNotice|null>(null),phoneNoticeSeen=useRef('');
 useEffect(()=>{setPhoneViewerExpanded(false)},[state.conversationId,route,taskId,taskOpen]);
 const projectScroll=useRef<HTMLElement>(null),projectListTop=useRef(new Map<string,number>());
 const projectTaskReturn=useRef<ProjectTaskReturn|null>(null);
 const projectGitToolReturn=useRef<PublicGitDraft|null>(null);
 const homeReport=useRef<VerifiedHomeReport|null>(null);
 const taskList=useRef<HTMLElement>(null),workListReturn=useRef<(()=>boolean)|null>(null),workDrillFocus=useRef<'origin'|'ended'|null>(null);
 const registerWorkReturn=useCallback((handler:(()=>boolean)|null)=>{workListReturn.current=handler},[]);
 const closeWorkFind=useCallback(()=>{setTaskFiltersOpen(false);requestAnimationFrame(()=>workspace.current?.querySelector<HTMLElement>('[data-work-search-entry]')?.focus({preventScroll:true}))},[]);
 const selectWorkScope=(value:WorkCollectionFilter)=>{if(value===filter)return;setEndedScopeOrigin(null);workDrillFocus.current=null;setFilter(value)};
 const restoreWorkScope=useCallback(()=>{
  if(!endedScopeOrigin)return false;
  setTaskFiltersOpen(false);setEndedScopeOrigin(null);workDrillFocus.current='origin';setTaskQuery(endedScopeOrigin.query);setFilter(endedScopeOrigin.filter);
  return true;
 },[endedScopeOrigin]);
 useEffect(()=>{if(route!=='tasks'){setTaskFiltersOpen(false);workDrillFocus.current=null}},[route]);
 const projectDevelopmentBack=useRef<(()=>boolean)|null>(null);const registerProjectDevelopmentBack=useCallback((handler:(()=>boolean)|null)=>{projectDevelopmentBack.current=handler},[]);
 const projectFileBack=useRef<(()=>boolean)|null>(null);const registerProjectFileBack=useCallback((handler:(()=>boolean)|null)=>{projectFileBack.current=handler},[]);
 const [projectViews,setProjectViews]=useState<Record<string,{query:string;view:'files'|'tree'|'grid';expanded:string[];selected:string}>>({});
 const projectView=projectViews[String(state.projectId||'')]||{query:'',view:'files' as const,expanded:[],selected:''};
 const [projectWorkViews,setProjectWorkViews]=useState<Record<string,ProjectWorkCollectionView>>({});
 const projectWorkView=projectWorkViews[String(state.projectId||'')]||{query:'',filter:'all' as const,expanded:false,selected:''};
 const updateProjectWorkView=(patch:Partial<ProjectWorkCollectionView>)=>setProjectWorkViews(previous=>({...previous,[String(state.projectId||'')]:{...(previous[String(state.projectId||'')]||{query:'',filter:'all',expanded:false,selected:''}),...patch}}));
 const projectSection=projectSections[String(state.projectId||'')]||'work';
 const projectPositionKey=`${String(state.projectId||'')}:${projectSection}`;
 const updateProjectView=(patch:Partial<typeof projectView>)=>setProjectViews(previous=>({...previous,[String(state.projectId||'')]:{...(previous[String(state.projectId||'')]||{query:'',view:'files',expanded:[],selected:''}),...patch}}));
 const fileQuery=projectView.query,fileView=projectView.view;const setFileQuery=(query:string)=>updateProjectView({query}),setFileView=(view:'files'|'tree'|'grid')=>updateProjectView({view});
 const taskScroll=useRef<HTMLDivElement>(null),taskReading=useRef(new Map<string,number>());
 const reviewRequest=useRef(0),openedTaskId=useRef<string|null>(null),secondaryBack=useRef<(()=>boolean)|null>(null);
 const openedTaskResumeRevision=useRef(0);
 const taskHistoryOrigin=useRef<{taskId:string;origin:ConversationHistoryTaskOrigin}|null>(null);
 const taskReconciliationOrigin=useRef<{taskId:string;layer:TaskLayer;openedId:string;expected:{runId:string;epoch:number};history?:ConversationHistoryTaskOrigin}|null>(null);
 const [taskOpening,setTaskOpening]=useState(false);
 const [sourceSelection,setSourceSelection]=useState<AnswerSourceTarget|null>(null);
 const [providedMemoryMessage,setProvidedMemoryMessage]=useState<string|null>(null);
 const providedMemoryBack=useRef<(()=>boolean)|null>(null);
 const registerProvidedMemoryBack=useCallback((handler:(()=>boolean)|null)=>{providedMemoryBack.current=handler},[]);
 useEffect(()=>{setProvidedMemoryMessage(null)},[route,state.conversationId]);
 const [messageQuoteTarget,setMessageQuoteTarget]=useState<MessageQuoteTarget|null>(null);
 const [messageOriginTarget,setMessageOriginTarget]=useState<MessageOriginTarget|PhoneReportTarget|null>(null);
 const [workSupplementTarget,setWorkSupplementTarget]=useState<WorkSupplementTarget|null>(null);
 const workSupplementBeforeLeave=useRef<WorkSupplementLeave|null>(null);
 const registerWorkSupplementBeforeLeave=useCallback((guard:WorkSupplementLeave|null)=>{workSupplementBeforeLeave.current=guard},[]);
 const [workControls,setWorkControls]=useState<Record<string,WorkControlFeedback>>({});
 const workControlEntered=useRef(new Set<string>());
 const [readingDocuments,setReadingDocuments]=useState<ReadingDocument[]>([]);const readingSerial=useRef(0);
 const closeReading=useCallback(()=>{setReadingDocuments(previous=>previous.slice(0,-1))},[]);
 useEffect(()=>{setReadingDocuments([])},[state.conversationId,route,taskId,taskOpen]);
 const messageTaskAnchors=useMemo(()=>taskMessageAnchors(messages),[messages]);
 const savedReplyDetails=useMemo(()=>messages.map(message=>({
  refs:sourceRefs(message),phoneResult:messagePhoneResult(message),taskResult:messageTaskResult(message),replyContext:messageReplyContext(message)
 })),[messages]);
 const replyTasksById=useMemo(()=>{
  const byId=new Map<string,Task>();
  for(const task of tasks)if(!byId.has(task.id))byId.set(task.id,task);
  return byId;
 },[tasks]);
 const conversationWork=backgroundConversationWork(tasks,asArray<Task>(state.ownerWork),state.conversationId);

 useEffect(()=>{setSkillDraftTarget(null);setMemoryDraftTarget(null);setRouteReferenceTarget(null);setMessageQuoteTarget(null);setMessageOriginTarget(null)},[state.conversationId,route]);
 useEffect(()=>{setRouteReferenceTarget(null)},[taskId,taskOpen]);
 const openConversationDraft=(draft:ConversationDraftTarget&{kind:string})=>{if(draft.kind==='skill'){setMemoryDraftTarget(null);setSkillDraftTarget(draft)}else{setSkillDraftTarget(null);setMemoryDraftTarget(draft)}};
 const taskTransientBack=useRef<(()=>boolean)|null>(null);
 const capabilityBack=useRef<(()=>boolean)|null>(null);
 const taskPolicyBeforeLeave=useRef<PermissionLeaveGuard|null>(null);
 const registerTaskPolicyBeforeLeave=useCallback((guard:PermissionLeaveGuard|null)=>{taskPolicyBeforeLeave.current=guard},[]);
 const deviceScopeBack=useRef<(()=>boolean)|null>(null);
 const registerDeviceScopeBack=useCallback((handler:(()=>boolean)|null)=>{deviceScopeBack.current=handler},[]);
 const livePreviewBack=useRef<(()=>boolean)|null>(null);
 const registerLivePreviewBack=useCallback((handler:(()=>boolean)|null)=>{livePreviewBack.current=handler},[]);
 const registerCapabilityBack=useCallback((handler:(()=>boolean)|null)=>{capabilityBack.current=handler},[]);
 const registerTaskBack=useCallback((handler:(()=>boolean)|null)=>{taskTransientBack.current=handler},[]);
 const attachmentBack=useRef<(()=>boolean)|null>(null);
 const resultFileReturnFocus=useRef<({taskId:string}|{conversationId:string})&{target:HTMLButtonElement}|null>(null);
 const openingChatResult=useRef('');
 const [openingChatResultId,setOpeningChatResultId]=useState('');
 const registerAttachmentBack=useCallback((handler:(()=>boolean)|null)=>{attachmentBack.current=handler},[]);
 const registerSecondaryBack=useCallback((handler:(()=>boolean)|null)=>{secondaryBack.current=handler},[]);
 const [taskErrors,setTaskErrors]=useState<Record<string,string>>({});
 const [taskMenuOpen,setTaskMenuOpen]=useState(false);
 const [localError,setLocalError]=useState(''),[localStatus,setLocalStatus]=useState(''),[review,setReview]=useState<Review|null>(null),[reviewPending,setReviewPending]=useState(false),[reviewSubmitted,setReviewSubmitted]=useState(false),[reviewNote,setReviewNote]=useState(''),[reviewFeedbackOpen,setReviewFeedbackOpen]=useState(false),[scopeTools,setScopeTools]=useState(''),[scopeModels,setScopeModels]=useState('');
 const [reviewReceipt,setReviewReceipt]=useState('');
 const [projectFeedback,setProjectFeedback]=useState<Record<string,string>>({});
 const [continuations,setContinuations]=useState<Record<string,ContinuationSubmission>>({}),continuationsRef=useRef<Record<string,ContinuationSubmission>>({});
 const reviewWindow=useRef<TaskReviewWindow|null>(null);
 const rememberContinuation=useCallback((value:ContinuationSubmission)=>{continuationsRef.current={...continuationsRef.current,[value.original.taskId]:value};setContinuations(continuationsRef.current)},[]);
 const invalidateContinuation=useCallback(()=>{for(const value of Object.values(continuationsRef.current)){const next=continuationUnknown(value);if(next!==value)rememberContinuation(next)}},[rememberContinuation]);
 const currentReviewWindow=useCallback((value:TaskReviewWindow|null)=>{const now=bridge.getState();return !!value&&!document.hidden&&reviewRequest.current===value.request&&openedTaskId.current===value.taskId&&bridge.getDocumentEpoch()===value.documentEpoch&&Number(obj(now.view).resumeRevision||0)===value.resumeRevision&&obj(now.view).taskId===value.taskId},[]);
 const [questionAnswer,setQuestionAnswer]=useOwnerQuestionDraft(review?.ownerQuestion?.questionKey);
 const [questionPending,setQuestionPending]=useState(false);
 const [intent,setIntent]=useState<'chat'|'background'|'main_screen'>('chat'),[phoneReview,setPhoneReview]=useState<PhoneReview|null>(null),[phoneReviewPending,setPhoneReviewPending]=useState(false);
 const [phoneReviewError,setPhoneReviewError]=useState<{handle:string;message:string}|null>(null);
 const [phoneStart,setPhoneStart]=useState<PhoneStartReceipt|null>(null);
 const phoneAction=useRef(false),phoneWindow=useRef(0),phoneReviewLive=useRef(phoneReview);phoneReviewLive.current=phoneReview;
 const phonePresentation=useMemo(()=>phoneReview?{goal:phoneReview.goal,mode:phoneReview.mode,message:phoneReview.message,screenDisclosure:phoneReview.screenDisclosure,conversationId:phoneReview.conversationId,conversationTitle:phoneReview.conversationTitle,modelName:phoneReview.modelName,toolLimit:phoneReview.toolLimit,modelCallLimit:phoneReview.modelCallLimit,scopeMinutes:phoneReview.scopeMinutes,conditions:phoneReview.conditions}:null,[phoneReview]);
 const phoneExit=useClosingSnapshot(phonePresentation),phoneDisplay=phoneExit.display;
 const [scopeMinutes,setScopeMinutes]=useState('-1');const [newProjectOpen,setNewProjectOpen]=useState(false),[projectName,setProjectName]=useState(''),[newFileOpen,setNewFileOpen]=useState(false),[newPath,setNewPath]=useState(''),[fileModes,setFileModes]=useState<Record<string,FileLayer>>({}),[fileDrafts,setFileDrafts]=useState<Record<string,FileDraft>>({});
 const channelPhoneModel=intent!=='chat'&&phoneDraft.channelOrigin===true;
 const composerModelName=channelPhoneModel?String(state.modelName||''):conversationModelName(state);
 const createWindow=useRef(0),createPending=useRef(new Set<number>());const [createBusy,setCreateBusy]=useState(false),[createKind,setCreateKind]=useState<'project'|'file'>('project');
 const closeCreate=()=>{createWindow.current++;setNewProjectOpen(false);setNewFileOpen(false);setCreateBusy(false)};
 const openCreate=(kind:'project'|'file')=>{createWindow.current++;setCreateKind(kind);setCreateBusy(false);setNewProjectOpen(kind==='project');setNewFileOpen(kind==='file')};
 const modelPicker=useRef<ConversationModelPickerHandle>(null);
 const [voiceReview,setVoiceReview]=useState<any>(null),[voiceText,setVoiceText]=useState(''),[voicePending,setVoicePending]=useState(false);const [voiceOptionsOpen,setVoiceOptionsOpen]=useState(false),[workOptionsOpen,setWorkOptionsOpen]=useState(false),[permissionOptionsOpen,setPermissionOptionsOpen]=useState(false),[messageMenu,setMessageMenu]=useState<string|null>(null),[expandedCalls,setExpandedCalls]=useState<Record<string,boolean>>({});
 const [permissionPending,setPermissionPending]=useState(false);const permissionWriting=useRef(false),permissionConversation=useRef(state.conversationId);
 const permissionExit=useReviewFocus(permissionOptionsOpen);
 const permissionBeforeLeave=useRef<PermissionLeaveGuard|null>(null);
 const registerPermissionBeforeLeave=useCallback((guard:PermissionLeaveGuard|null)=>{permissionBeforeLeave.current=guard},[]);
 const permissionSaving=useCallback((pending:boolean)=>{permissionWriting.current=pending;setPermissionPending(pending)},[]);
 const closePermissionOptions=useCallback(()=>{if(permissionWriting.current)return;const close=()=>setPermissionOptionsOpen(false);if(!permissionBeforeLeave.current?.(close))close()},[]);
 useEffect(()=>{if(permissionConversation.current!==state.conversationId){setPermissionOptionsOpen(false);permissionWriting.current=false;setPermissionPending(false)}permissionConversation.current=state.conversationId},[state.conversationId]);
 const voiceReviewLive=useRef(voiceReview),voiceWriting=useRef(false);voiceReviewLive.current=voiceReview;
 const voiceExit=useReviewFocus(!!voiceReview);
 const workspace=useRef<HTMLDivElement>(null);
 const scroll=useRef<HTMLDivElement>(null);
 const conversationDraft=foregroundConversationDraft(state);
 const conversationStream=conversationDraft?.text.trim()?conversationDraft:null;
 const {rows:replyRows,continued:wasReplyVisible}=useReplyRows(String(state.conversationId||''),messages,conversationDraft,foregroundTurn as WorkIdentity|null,route==='chat'&&commands.length>0);
 const streamMarker=conversationStream?`${conversationStream.requestId}:${conversationStream.generation}:${conversationStream.sequence}`:'';
 const {content:chatContent,messageList:chatMessageList,readingNav,saveReading,rememberChatPosition,goToLatest,stopFollowing}=useConversationReading({scroll,active:route==='chat',conversationId:String(state.conversationId||''),composer,messages,streamMarker,paused:readingDocuments.length>0||!!messageOriginTarget||!!state.conversationReader||!!state.queuedInputsPage});
 const openReading=useCallback((document:ReadingSnapshot)=>{rememberChatPosition();const entry={...document,key:++readingSerial.current};setReadingDocuments(previous=>[...previous,entry])},[rememberChatPosition]);
 const activeTask=tasks.find(x=>x.id===state.activeTaskId);const projectReading=route==='projects'&&projectTaskReturn.current?.taskId===taskId?projectTaskReturn.current:null;
 const reportSourceChanged=taskLayer==='homeReport'&&(!homeReport.current||!currentHomeReport(homeReport.current));
 const noticeBinding=phoneNoticePin?.taskId===taskId?phoneNoticePin:null;
 const noticeCandidate=noticeBinding?tasks.find(t=>t.id===noticeBinding.taskId):null;
 const wrongNoticeRun=!!noticeBinding&&(!noticeCandidate||noticeCandidate.raw?.runId!==noticeBinding.runId||noticeCandidate.raw?.coordinatorEpoch!==noticeBinding.epoch);
 const noticeNotReady=!!noticeBinding&&(wrongNoticeRun||phoneNotification?.token!==noticeBinding.token||!phoneNotification?.current||openedTaskId.current!==taskId||snapshot?.view?.taskId!==taskId);
 const selectedTask=noticeNotReady||reportSourceChanged||projectReading&&(openedTaskId.current!==taskId||snapshot?.view?.taskId!==taskId||state.projectId!==projectReading.projectId)?undefined:tasks.find(x=>x.id===taskId);const currentProject=projects.find(x=>x.id===state.projectId);const currentConversation=conversations.find(x=>x.id===state.conversationId);
 useEffect(()=>setTaskMenuOpen(false),[taskId,taskOpen,taskLayer,selectedTask?.status,selectedTask?.raw?.runId,selectedTask?.raw?.coordinatorEpoch,personalDocumentEpoch,snapshot?.view?.resumeRevision]);
 useEffect(()=>{const hide=()=>{if(document.hidden)setTaskMenuOpen(false)};document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide)},[]);
 const chatOwner=`${composer.key||''}:${composer.epoch??''}`;const chatDraft=useNativeDraft(chatOwner,String(composer.text||''),composer.revision);const phoneOwner=`phone:${phoneDraft.epoch??''}:${state.conversationId||''}:${phoneDraft.channelOrigin===true?'channel':'private'}:${phoneDraft.sourceState==='elsewhere'?'away':'here'}`;const phoneText=useNativeDraft(phoneOwner,String(phoneDraft.text||''),phoneDraft.revision);
 const text=intent==='chat'?chatDraft.value:phoneText.value;
 // Match the native send/phone-prepare limits without truncating the retained draft.
 const inputLimit=intent==='chat'?12000:4000,inputLength=text.trim().length;
 const inputLimitMessage=inputLength>inputLimit?`本次${intent==='chat'?'消息':'手机任务目标'}最多 ${inputLimit} 个字符，当前 ${inputLength} 个，超出 ${inputLength-inputLimit} 个。请缩短后发送。`:'';
 useEffect(()=>{phoneWindow.current++;setPhoneReview(null);setPhoneStart(null)},[state.conversationId,chatOwner,phoneOwner]);
 const shownFiles=useMemo(()=>files.filter(f=>String(f.path).toLocaleLowerCase().includes(fileQuery.toLocaleLowerCase())),[files,fileQuery]);
 const currentFileKey=fileWorkspaceKey(state.projectId,state.filePath);const fileDraft=currentFileKey?fileDrafts[currentFileKey]:undefined;
 const fileMode=fileModes[currentFileKey]||'edit';const setFileMode=(mode:FileLayer)=>setFileModes(previous=>({...previous,[currentFileKey]:mode}));
 const fileEditRevisions=useRef(new Map<string,number>());
 const fileAdoption=useProjectRevision({key:currentFileKey,active:route==='projects'&&!!state.filePath,allowed:can('projects.useRevision')&&!!state.fileReady&&!state.fileLoading&&!state.revisionLoading&&!!fileDraft&&fileDraft.version===Number(state.fileVersion||0),saving:!!state.fileSaving,localCurrent:()=>({editRevision:fileEditRevisions.current.get(currentFileKey)||0,navigationRevision:navigationWindow.current}),current:fileDraft&&typeof state.revisionContent==='string'&&Number.isInteger(state.revisionNumber)?{projectId:String(state.projectId),path:String(state.filePath),version:fileDraft.version,revision:state.revisionNumber,content:state.revisionContent,draft:fileDraft.text,editRevision:fileEditRevisions.current.get(currentFileKey)||0,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),navigationRevision}:null,apply:original=>{setFileDrafts(previous=>{const prior=previous[currentFileKey];if(!prior||prior.version!==original.version||prior.text!==original.draft)return previous;return {...previous,[currentFileKey]:{...prior,text:original.content,dirty:original.content!==prior.baseline,submitted:undefined}}});fileEditRevisions.current.set(currentFileKey,(fileEditRevisions.current.get(currentFileKey)||0)+1);setFileMode('edit')}});
 // Reading history and leaving the editor target the current native window,
 // while edits, saves and revision adoption keep the retained draft's version.
 const fileIdentity=useMemo(()=>({projectId:state.projectId,path:state.filePath,version:Number(state.fileVersion||0)}),[state.projectId,state.filePath,state.fileVersion]);
 const isPrimaryRoute=isChatSurface||route==='tasks'||route==='projects';
 const ready=commands.length>0;const currentTitle=route==='butler'?'管家':route==='tasks'?'我的工作':route==='projects'?(state.filePath?String(state.filePath).split('/').pop()||'文件':currentProject?.name||'项目'):route==='chat'?(currentConversation?.title||'新对话'):'设置与连接';
 const selectedBinding=taskBinding(selectedTask);const reviewedBinding=taskBinding(review?.task);const reviewStale=!!review&&!!selectedTask&&reviewedBinding!==selectedBinding;
 const isListening=!!state.listening||!!state.voiceInput?.active;
 const draftKey=String(composer.key||'');const draftEpoch=Number(composer.epoch||0);
 const newMemoryScope=!state.conversationId&&draftKey.startsWith('new:')&&typeof composer.firstMessageMemory?.paused==='boolean'&&can('composer.firstMemory.save');
 const newMemoryBlocked=newMemoryScope&&(composer.firstMessageMemory.saving||composer.firstMessageMemory.confirmed!==true);
 const memoryPausedHere=newMemoryScope?composer.firstMessageMemory.confirmed===true&&composer.firstMessageMemory.paused:!!conversationMemoryMode&&conversationMemoryMode.conversationId===state.conversationId&&!conversationMemoryMode.enabled;
 const singleLearningScope=intent==='chat'&&!!state.conversationId&&composer.conversationId===state.conversationId&&can('composer.oneLearning.save')&&typeof composer.oneMessageLearning?.excluded==='boolean';
 const singleLearningArmed=singleLearningScope&&composer.oneMessageLearning.excluded===true;
 const singleLearningBlocked=singleLearningScope&&(composer.oneMessageLearning.saving||composer.oneMessageLearning.confirmed!==true||singleLearningArmed&&composer.oneMessageLearning.pending===true);
 const canEditMemoryHere=!!(state.conversationId&&can('profile.conversation.read'))||newMemoryScope;
 const pendingInputs=conversationPending(composer,obj(state.chatReceipts),messages,String(state.conversationId||''),conversations,text);
 const pendingOriginal=intent==='chat'&&pendingInputs.find(row=>row.sameDraft&&(can('composer.query')||row.canRetryLocallyUnsent===true&&can('composer.retry')));
 const pendingHere=pendingInputs.some(row=>row.current);
 const registration=useConversationRegistration(chatOwner,composer.revision,text);
 const act=useCallback(async(action:string,args:Json={},errorTaskId?:string)=>{
  const errorOwner=errorTaskId||(action.startsWith('tasks.')?String(args.id||''):'');
  const rememberError=(message:string)=>{if(errorOwner)setTaskErrors(previous=>({...previous,[errorOwner]:message}));};
  if(!can(action)){const message='这个操作当前尚不可用。';if(!errorOwner)setLocalError(message);rememberError(message);throw new Error(message)}
  setLocalError('');if(errorOwner)setTaskErrors(previous=>previous[errorOwner]?{...previous,[errorOwner]:''}:previous);
  return bridge.command(action,args).then(value=>{if(errorOwner)toast.dismiss(`command-error:${action}`);return value}).catch(error=>{const message=error instanceof Error?error.message:String(error);rememberError(message);if(!errorOwner)toast.error(message,{id:`command-error:${action}`});throw error;});
 },[can]);
 const run=useCallback((action:string,args:Json={},onAccepted?:(value:any)=>void)=>{void act(action,args).then(value=>{onAccepted?.(value)}).catch(error=>{if(!action.startsWith('tasks.')||!args.id)setLocalError(error instanceof Error?error.message:String(error))});},[act]);
 // Each click rechecks the displayed original against the newest private phone projection; the native command rechecks ownership again.
 const exactPhoneControl=(action:string,shown:{taskId:string;runId:string;epoch:number},isLocal:boolean)=>{
  if(!isLocal)return act(action,shown);
  const native=bridge.getState().state,current=asArray<Task>(native.tasks).find(row=>row.id===shown.taskId);
  const live=privatePhoneSurface(current,native.livePrivatePhones);
  if(document.hidden||!current||terminal(current)||!live||live.runId!==shown.runId||live.epoch!==shown.epoch||live.stopRequested||
     action==='phone.pause'&&(live.paused||current.status!=='running')||
     action==='phone.resume'&&(!live.paused||current.status!=='paused'||conversationInlineAttention(current)))
    return Promise.reject(new Error('这次手机执行或控制状态已变化。请核对原任务，不会操作其他屏幕。'));
  return act(action,{taskId:shown.taskId,runId:shown.runId,epoch:shown.epoch});
 };
 const triggerPhoneControl=(action:string,shown:{taskId:string;runId:string;epoch:number},isLocal:boolean)=>{
  void exactPhoneControl(action,shown,isLocal).catch(error=>setLocalError(error instanceof Error?error.message:String(error)));
 };
 const cancelPending=useRef(new Set<string>());
 const [cancelFeedback,setCancelFeedback]=useState<Record<string,{error:boolean;message:string}>>({});
 const currentCancelIdentity=chatCancelIdentity(state);
 const currentCancelFeedback=chatCancelFeedback(state,cancelFeedback);
 const stopOriginalChat=()=>{
  const expected=chatCancelIdentity(state);
  if(!expected||!can('chat.cancel'))return;
  const key=chatCancelKey(expected);
  if(cancelPending.current.has(key))return;
  cancelPending.current.add(key);
  setCancelFeedback(previous=>{const next={...previous};delete next[key];return next});
  const remember=(error:boolean,message:string)=>setCancelFeedback(previous=>
   Object.fromEntries([...Object.entries(previous).filter(([prior])=>prior!==key),[key,{error,message}]].slice(-16)));
  void bridge.command('chat.cancel',expected).then(()=>{
   remember(false,'已请求停止所选原回复，结果请以原任务状态为准。');
  }).catch(error=>{
   remember(true,error instanceof Error?error.message:'未确认原回复收到停止请求，请核对原任务。');
  }).finally(()=>{cancelPending.current.delete(key)});
 };
 const editText=useCallback((next:string)=>{
  if(intent==='chat'){chatDraft.set(next);if(can('composer.edit'))run('composer.edit',{key:draftKey,epoch:draftEpoch,text:next});}
  else{phoneWindow.current++;phoneText.set(next);setPhoneReview(null);if(can('phone.draft.edit'))run('phone.draft.edit',{text:next,epoch:phoneDraft.epoch,...(Number.isSafeInteger(phoneDraft.sourceEditTicket)?{sourceEditTicket:phoneDraft.sourceEditTicket,draftConversation:String(state.conversationId||'')}:{})});}
 },[intent,chatDraft,phoneText,can,run,draftKey,draftEpoch,phoneDraft.epoch,phoneDraft.sourceEditTicket,state.conversationId]);
 const rememberTaskPosition=useCallback(()=>{const node=taskScroll.current;if(taskId&&node?.isConnected)taskReading.current.set(`${taskId}:${taskLayer}`,node.scrollTop)},[taskId,taskLayer]);
 const showTaskLayer=useCallback((next:TaskLayer)=>{const commit=()=>{rememberTaskPosition();setTaskLayer(next)};if(!taskPolicyBeforeLeave.current?.(commit))commit()},[rememberTaskPosition]);
 const releasePhoneNotice=useCallback(()=>{const pin=phoneNoticeRef.current;if(!pin)return;phoneNoticeRef.current=null;setPhoneNoticePin(null);setPhoneNoticePanel(null);
  if(can('phone.notice.close'))void bridge.command('phone.notice.close',{noticeHandle:pin.token}).catch(()=>{});
 },[can]);
 const closeTask=useCallback(()=>{const commit=async()=>{if(workSupplementBeforeLeave.current&&!await workSupplementBeforeLeave.current())return false;releasePhoneNotice();homeReport.current=null;taskHistoryOrigin.current=null;taskReconciliationOrigin.current=null;rememberTaskPosition();invalidateContinuation();reviewWindow.current=null;reviewRequest.current++;openedTaskId.current=null;setTaskOpening(false);const closed=can('tasks.close')?act('tasks.close').then(()=>true,()=>false):Promise.resolve(false);setTaskOpen(false);setTaskLayer('overview');setWorkSupplementTarget(null);setReview(null);setReviewPending(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionAnswer('');return closed;};if(taskPolicyBeforeLeave.current?.(()=>{void commit()}))return Promise.resolve(false);return commit();},[can,act,rememberTaskPosition,invalidateContinuation,releasePhoneNotice]);
 const changeNavigation=useCallback((open:boolean)=>{setWorkOpen(open);if(!open)setCommandOpen(false)},[]);
 const navigate=useCallback((requested:string,afterNavigate?:()=>void,options?:{keepOrigin?:boolean;taskClosed?:boolean;stillCurrent?:()=>boolean}):Promise<boolean>=>{
  if(options?.stillCurrent){if(!options.stillCurrent())return Promise.resolve(false)}else searchOpenRequest.current++;
  const next=requested==='memory'?'profile':requested==='advanced-memory'?'memory':requested;
  const commit=async()=>{
   if(options?.stillCurrent&&!options.stillCurrent())return false;
   if(preparationBeforeLeave.current&&!await leaveInlinePreparation())return false;
   let taskClosed=!!options?.taskClosed;
   if(next==='butler'){
    const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
    const current=()=>navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden;
    setWorkOpen(false);setCommandOpen(false);
    if(taskOpen&&!taskClosed){if(!await closeTask()||!current())return false;taskClosed=true;}
    const reader=bridge.getState().state.conversationReader;
    if(reader){
     if(!can('conversation.reader.close')){setLocalError('请先关闭对话记录，再回到管家。');return false;}
     try{
      await act('conversation.reader.close',{window:reader.window,conversationId:reader.conversation});
      const closed=await new Promise<boolean>(resolve=>{
       let unsubscribe=()=>{};
       const timer=window.setTimeout(()=>{unsubscribe();if(current())setLocalError('对话记录尚未确认关闭，请稍后返回管家。');resolve(false)},5000);
       const check=()=>{if(!current()||!bridge.getState().state.conversationReader){clearTimeout(timer);unsubscribe();resolve(current())}};
       unsubscribe=bridge.subscribe(check);check();
      });
      if(!closed||!current())return false;
     }catch{return false;}
    }
    if(!current())return false;
   }
   setSearchReturn(false);setWorkOptionsOpen(false);setSearchOriginal(null);
   if(next!=='teams'){setTeamAttentionFocus(undefined);setTeamRunFocus(undefined);}
   if(requested==='memory'||requested==='profile'){setPersonalTarget({view:requested==='memory'?'memories':'home'});setPersonalTargetKey(key=>key+1);}
   if(next==='profile'&&route!=='profile')profileOrigin.current={route:!['butler','chat','tasks','projects','setup','remoteInbox','developer-logs'].includes(route)&&secondaryPage?secondaryPage:route,drawerOpen:workOpen,scrollTop:navigationDrawer.current?.querySelector<HTMLElement>('.pw-drawer-body')?.scrollTop||0};
   if(!options?.keepOrigin){relatedOrigin.current=null;setLocalWorkspaceDraft(undefined);}
   setupReturnPending.current=false;rememberChatPosition();setWorkOpen(false);setCommandOpen(false);
   if(taskOpen&&!taskClosed&&!await closeTask())return false;
   if(options?.stillCurrent&&!options.stillCurrent())return false;
   navigationWindow.current++;setNavigationRevision(navigationWindow.current);setRoute(next);afterNavigate?.();return true;
  };
  if(taskPolicyBeforeLeave.current?.(()=>{void navigate(requested,afterNavigate,options)}))return Promise.resolve(false);
  if(secondaryBeforeLeave.current?.(()=>{void commit()}))return Promise.resolve(false);
  if(route==='profile'&&next!=='profile'){
   setWorkOpen(false);setCommandOpen(false);
   if(profileBeforeLeave.current?.(()=>{void commit()}))return Promise.resolve(false);
  }
  return commit();
 },[route,secondaryPage,workOpen,taskOpen,closeTask,rememberChatPosition,can,act,leaveInlinePreparation]);
 const openPersonalMemory=(target:PersonalTarget)=>{void navigate('profile',()=>{setPersonalTarget(target);setPersonalTargetKey(key=>key+1)})};
 const returnFromProfile=()=>{const origin=profileOrigin.current;void navigate(origin.route==='profile'?'butler':origin.route==='memory'?'advanced-memory':origin.route,()=>{if(origin.drawerOpen&&!desktopNavigation){profileRestoreScroll.current=origin.scrollTop;setWorkOpen(true)}})};
 const captureWorkspaceOrigin=():WorkspaceOrigin=>({
  route:!['butler','chat','tasks','projects','setup','profile','remoteInbox','developer-logs'].includes(route)&&secondaryPage?secondaryPage:route,
  label:taskOpen?'返回这次任务':route==='projects'?'返回项目':route==='butler'?'返回管家':route==='chat'?'返回对话':route==='tasks'?'返回任务列表':'返回原工作',
  goal:taskOpen?selectedTask?.goal:route==='projects'?currentProject?.name:relatedOrigin.current?.goal,
  contextProjectId:taskOpen?selectedTask?.raw?.developmentContext?.projectId:route==='projects'?currentProject?.id:relatedOrigin.current?.contextProjectId,
  ...(route==='projects'&&currentProject?{projectId:currentProject.id}:{}),
  ...(taskOpen&&taskId?{task:{id:taskId,layer:taskLayer}}:{}),
  focus:document.activeElement instanceof HTMLElement?document.activeElement:null,
  focusKey:document.activeElement instanceof HTMLElement?document.activeElement.closest<HTMLElement>('[data-workspace-entry]')?.dataset.workspaceEntry:undefined,
  parent:relatedOrigin.current,
 });
 const captureNavigationOrigin=():WorkspaceOrigin=>{
  const origin=captureWorkspaceOrigin();
  if(route!=='chat'||desktopNavigation)return origin;
  const live=bridge.getState();
  return {...origin,focus:null,focusKey:undefined,drawerChat:{id:String(live.state.conversationId||''),draftKey:String(live.composer?.key||''),documentEpoch:bridge.getDocumentEpoch()}};
 };
 const openRelatedWorkspace=async(destination:string,origin=captureWorkspaceOrigin(),afterNavigate?:()=>void):Promise<boolean|undefined>=>{
  if(taskPolicyBeforeLeave.current?.(()=>{void openRelatedWorkspace(destination,origin,afterNavigate)}))return;
  const navigation=navigationWindow.current,closingTask=taskOpen;
  if(closingTask&&!await closeTask())return;
  if(navigationWindow.current!==navigation)return;
  if(destination==='local'&&origin.route!=='local')setLocalWorkspaceDraft(undefined);
  relatedOrigin.current=origin;
  return navigate(destination,afterNavigate,{keepOrigin:true,taskClosed:closingTask});
 };
 const openNotifications=(afterNavigate?:()=>void)=>route==='remoteInbox'
  ?navigate('remoteInbox',afterNavigate,{keepOrigin:true})
  :openRelatedWorkspace('remoteInbox',captureNavigationOrigin(),afterNavigate);
 const restoreWorkspace=async(origin:WorkspaceOrigin,keepOrigin=false,rereadOriginal=false)=>{
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const source=origin.drawerChat;
  // Opening the work directory never changes the native composer. Do not select,
  // overwrite or resurrect a conversation if another surface has replaced that owner.
  const sameChat=()=>{const live=bridge.getState();return !source||(bridge.getDocumentEpoch()===source.documentEpoch&&String(live.state.conversationId||'')===source.id&&(!!source.id||String(live.composer?.key||'')===source.draftKey));};
  const closed=await navigate(origin.route==='setup'?'butler':origin.route,()=>{
   if(!keepOrigin)relatedOrigin.current=origin.parent||null;
   if(origin.settingsDirectory)setSettingsRestore({...origin.settingsDirectory,revision:navigationWindow.current});
  },{keepOrigin:true,...(source?{stillCurrent:sameChat}:{})});
  if(!closed&&source&&!sameChat()&&navigationWindow.current===navigation&&!document.hidden){setLocalError('原对话或草稿已切换。未打开其他输入，请从导航重新选择原对话。');return;}
  const current=()=>navigationWindow.current===navigation+1&&documentEpoch===bridge.getDocumentEpoch()&&!document.hidden&&sameChat();
  if(!closed||!current())return;
  if(origin.projectId&&can('projects.select')&&bridge.getState().state.projectId!==origin.projectId){
   try{await act('projects.select',{id:origin.projectId})}catch{return}
  }
  if(!current())return;
  if(origin.task)openTask(origin.task.id,origin.task.layer,false,undefined,rereadOriginal?'':undefined);
  else if(origin.modelPicker)requestAnimationFrame(()=>{if(current())modelPicker.current?.open('models')});
  else if(!origin.settingsDirectory)requestAnimationFrame(()=>{
   if(!current())return;
   const retained=origin.focus?.isConnected&&!origin.focus.closest('[hidden],[inert]')?origin.focus:null;
   const entry=origin.focusKey?Array.from(workspace.current?.querySelectorAll<HTMLElement>('[data-workspace-entry]')||[]).find(node=>node.dataset.workspaceEntry===origin.focusKey&&!node.closest('[hidden],[inert]')):null;
   const target=retained||entry||(source?chatMenuTrigger.current:null)||workspace.current?.querySelector<HTMLElement>('.pw-project-overview h2,.pw-secondary-header h1,.pw-app-header h1');
   if(target){if(!target.matches('button,a,input,textarea,select,[tabindex]'))target.tabIndex=-1;target.focus({preventScroll:true})}
  });
 };
 const openSetup=async(capability?:SetupCapability,runtimeTaskId?:string,modelMode?:'onboarding'|'center',settingsDirectory?:SettingsDirectoryOrigin)=>{
  if(taskPolicyBeforeLeave.current?.(()=>{void openSetup(capability,runtimeTaskId,modelMode,settingsDirectory)}))return;
  const origin=captureWorkspaceOrigin(),navigation=navigationWindow.current,closingTask=taskOpen;
  if(closingTask&&!await closeTask())return;
  if(navigationWindow.current!==navigation)return;
  setupOrigin.current=settingsDirectory?{...origin,route:settingsDirectory.page,settingsDirectory}:origin;
  setSetupMode(modelMode||'center');setSetupCapability(capability);setSetupView(modelMode?'model':capability||'overview');setSetupRuntimeTask(runtimeTaskId);
  await navigate('setup',undefined,{keepOrigin:true,taskClosed:closingTask});
 };
 const navigateFromSetup=(destination:SetupDestination)=>{const target={connections:'model-settings',background:'background',appearance:'phone:appearance',local:'local'}[destination];void navigate(target,undefined,{keepOrigin:true});setupReturnPending.current=true;};
 const closeSetup=()=>{setupReturnPending.current=false;void restoreWorkspace(setupOrigin.current,true);};
 const returnFromSecondary=()=>{if(route==='profile'){returnFromProfile();return;}if(setupReturnPending.current){void navigate('setup',undefined,{keepOrigin:true});return;}const origin=relatedOrigin.current;if(origin){relatedOrigin.current=null;void restoreWorkspace(origin);return;}void navigate('butler');};
 const openModelSetup=async()=>{
  if(openingModelSetup.current||!can('setup.read'))return;
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch(),conversation=state.conversationId;
  const origin=captureWorkspaceOrigin(),selection=bridge.getState().state;
  openingModelSetup.current=true;setModelSetupOpening(true);
  try{
   const current=await bridge.command('setup.read').then(readSetup,()=>null).catch(()=>null);
   const live=bridge.getState();
   if(document.hidden||navigationWindow.current!==navigation||bridge.getDocumentEpoch()!==documentEpoch||live.state.conversationId!==conversation)return;
   const pending=current?.preferences.status==='new'||current?.preferences.status==='in_progress';
   // An unconfigured default is not an empty service list. The connection workspace reads both owners.
   const stableSelection=selection.modelSelectionBusy===false&&live.state.modelSelectionBusy===false&&Number.isSafeInteger(selection.modelSelectionRevision)&&
    selection.modelSelectionRevision>=0&&selection.modelSelectionRevision===live.state.modelSelectionRevision;
   if(pending&&current?.model.availability==='unconfigured'&&!current.model.configured&&!current.model.testing&&stableSelection&&
    !conversationModelName(live.state)&&live.capabilities.commands.includes('connections.enter')){
    await openRelatedWorkspace('model-settings',origin);return;
   }
   await openSetup(undefined,undefined,pending?'onboarding':'center');
  }finally{openingModelSetup.current=false;setModelSetupOpening(false);}
 };
 const remoteInboxOpenRevision=Number(state.remoteInbox?.openRevision||0);
 const lastRemoteInboxOpen=useRef(0);
 useEffect(()=>{if(remoteInboxOpenRevision>0&&remoteInboxOpenRevision!==lastRemoteInboxOpen.current){lastRemoteInboxOpen.current=remoteInboxOpenRevision;if(route!=='remoteInbox')void openNotifications();}},[remoteInboxOpenRevision,route,openNotifications]);
 const loadTaskReview=useCallback((id:string,approvalId?:string)=>{
  if(!can('tasks.review')||openedTaskId.current!==id)return;invalidateContinuation();const request=++reviewRequest.current;setReviewPending(true);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);
  const window={taskId:id,request,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(bridge.getState().view).resumeRevision||0)};reviewWindow.current=window;
  void act('tasks.review',{id,...(approvalId?{approvalId}:{})},id).then(value=>{if(!currentReviewWindow(window))return;setReview(value);const raw=obj(value.task?.raw);setScopeTools(String(raw.toolLimit||raw.executionScope?.toolLimit||raw.scope?.toolLimit||''));setScopeModels(String(raw.modelCallLimit||raw.executionScope?.modelCallLimit||raw.scope?.modelCallLimit||''));}).catch(()=>{if(reviewRequest.current===request)setReview(null)}).finally(()=>{if(reviewRequest.current===request)setReviewPending(false)});
 },[can,act,currentReviewWindow,invalidateContinuation]);
 const openTask=useCallback((id:string,entry:TaskLayer|'review'='overview',nativeWindowAlreadyOpen=false,historyOrigin?:ConversationHistoryTaskOrigin,searchQuery?:string,expected?:{runId:string;epoch:number},stillCurrent?:()=>boolean):void=>{
  const noticeOrigin=phoneNoticeRef.current;
  const dropNotice=noticeOrigin&&(noticeOrigin.taskId!==id||originalPhoneNotice(bridge.getState().state?.originalPhoneNotification)?.token!==noticeOrigin.token||
    !!expected&&(expected.runId!==noticeOrigin.runId||expected.epoch!==noticeOrigin.epoch));
  if(noticeOrigin&&!dropNotice&&!expected){expected={runId:noticeOrigin.runId,epoch:noticeOrigin.epoch};nativeWindowAlreadyOpen=false}
  const projectSource=route==='projects'&&projectTaskReturn.current?.taskId===id?projectTaskReturn.current:null;
  if(projectSource&&!nativeWindowAlreadyOpen)searchQuery??=projectSource.query||'';
  if(stillCurrent){if(!stillCurrent())return}else searchOpenRequest.current++;
  if(!nativeWindowAlreadyOpen&&!expected&&taskReconciliationOrigin.current?.openedId===id)expected=taskReconciliationOrigin.current.expected;
  if(taskPolicyBeforeLeave.current?.(()=>openTask(id,entry,nativeWindowAlreadyOpen,historyOrigin,searchQuery,expected,stillCurrent)))return;
  const commit=async()=>{
  if(preparationBeforeLeave.current&&!await leaveInlinePreparation())return;
  if(stillCurrent&&!stillCurrent())return;
  if(dropNotice&&phoneNoticeRef.current===noticeOrigin)releasePhoneNotice();
  if(expected&&taskId&&taskId!==id)taskReconciliationOrigin.current={taskId,layer:taskLayer,openedId:id,expected,history:taskHistoryOrigin.current?.taskId===taskId?taskHistoryOrigin.current.origin:undefined};
  else if(nativeWindowAlreadyOpen||taskReconciliationOrigin.current?.openedId!==id)taskReconciliationOrigin.current=null;
  const origin=historyOrigin||(searchQuery===undefined&&!nativeWindowAlreadyOpen&&taskHistoryOrigin.current?.taskId===id?taskHistoryOrigin.current.origin:undefined);
  taskHistoryOrigin.current=origin?{taskId:id,origin}:null;
  invalidateContinuation();
  rememberTaskPosition();const request=++reviewRequest.current;const readReview=entry!=='homeReport'&&(entry==='review'||entry==='scope'||tasks.some(task=>task.id===id&&attention(task)));openedTaskId.current=null;setReviewPending(false);setTaskOpening(true);setWorkOpen(false);setCommandOpen(false);setTaskId(id);setTaskLayer(entry==='review'?'overview':entry);setTaskOpen(true);setReview(null);setReviewNote('');setReviewFeedbackOpen(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);
  const window={taskId:id,request,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(bridge.getState().view).resumeRevision||0)};reviewWindow.current=window;
  // Open the original host window before obtaining its single-use review handle.
  // Internal task opens do not emit another external-navigation request.
  const openOriginal=async()=>{
   if(nativeWindowAlreadyOpen)return true;
   const nativeTask=String(obj(bridge.getState().view).taskId||'');
   if(origin&&nativeTask&&nativeTask!==id)await act('tasks.close',{},id);
   if(stillCurrent&&!stillCurrent()||reviewRequest.current!==request||bridge.getDocumentEpoch()!==window.documentEpoch||Number(obj(bridge.getState().view).resumeRevision||0)!==window.resumeRevision||document.hidden)return false;
   await act('tasks.open',{id,requestNavigation:false,...origin,...(searchQuery===undefined?{}:{searchQuery}),...(projectSource?{expectedProjectId:projectSource.projectId,...(projectSource.checkoutId?{expectedCheckoutId:projectSource.checkoutId}:{})}:{}),...(expected?{expectedRunId:expected.runId,expectedEpoch:expected.epoch}:{})},id);return true;
  };
  void openOriginal().then(opened=>{if(reviewRequest.current!==request)return;if(!opened||stillCurrent&&!stillCurrent()){setTaskOpening(false);return}openedTaskId.current=id;openedTaskResumeRevision.current=window.resumeRevision;setTaskOpening(false);if(readReview)loadTaskReview(id)}).catch(()=>{if(reviewRequest.current===request)setTaskOpening(false)});
  };
  if(workSupplementBeforeLeave.current)void workSupplementBeforeLeave.current().then(saved=>{if(saved)void commit()});else void commit();
 },[act,loadTaskReview,tasks,taskId,taskLayer,route,rememberTaskPosition,invalidateContinuation,leaveInlinePreparation,releasePhoneNotice]);
 useEffect(()=>{
  const invalidate=()=>{
   const original=reviewWindow.current,view=obj(bridge.getState().view);
   const documentEpoch=bridge.getDocumentEpoch(),resumeRevision=Number(view.resumeRevision||0);
   // Foregrounding rotates the command token in this same WebView. Only a
   // different native view retires the read-only acknowledgement.
   if(openedTaskId.current&&(resumeRevision!==openedTaskResumeRevision.current||document.hidden&&view.taskId!==openedTaskId.current)){
    openedTaskId.current=null;
   }
   if(!original||!document.hidden&&documentEpoch===original.documentEpoch&&resumeRevision===original.resumeRevision)return;
   // Retain the acknowledged read-only work window when Android backgrounds it.
   // Its short-lived review must still be reread before a decision.
   invalidateContinuation();reviewWindow.current=null;reviewRequest.current++;
   setReview(null);setReviewPending(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);setTaskOpening(false);
  };
  const unsubscribe=bridge.subscribe(invalidate);document.addEventListener('visibilitychange',invalidate);
  return()=>{unsubscribe();document.removeEventListener('visibilitychange',invalidate)};
 },[invalidateContinuation]);
 const backFromTask=useCallback(()=>{if(taskMenuOpen){setTaskMenuOpen(false);return}if(deviceScopeBack.current?.()||capabilityBack.current?.()||livePreviewBack.current?.()||taskTransientBack.current?.())return;if(taskLayer==='scope'){showTaskLayer('goal');return}const origin=taskReconciliationOrigin.current;if(origin?.openedId===taskId){openTask(origin.taskId,origin.layer,false,origin.history);return}void closeTask().then(closed=>{if(closed&&searchReturn)showWorkSearch()})},[taskMenuOpen,taskId,taskLayer,showTaskLayer,openTask,closeTask,searchReturn,showWorkSearch]);
 const selectConversation=useCallback((id:string)=>{void navigate('chat',()=>{if(can('conversation.select'))run('conversation.select',{id})});},[can,run,navigate]);
 const returnToTaskConversation=async(task:Task,associate=false)=>{
  const conversationId=privateTaskConversationId(task);
  if(!conversationId||returningConversation||bridge.getState().state.chatSubmitting||!can('conversation.select'))return;
  const request=++conversationReturnRequest.current,documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  setReturningConversation(task.id);
  // Closing a task over a secondary workspace legitimately advances the host's
  // resume revision. Guard navigation by this request/document and visibility;
  // a background transition cancels the request above, rather than blocking
  // the host's normal task-close restoration.
  const current=()=>request===conversationReturnRequest.current&&documentEpoch===bridge.getDocumentEpoch()&&!document.hidden;
  try{
   if(taskOpen&&!await closeTask()||!current()||navigationWindow.current!==navigation)return;
   await navigate('chat',undefined,{taskClosed:true});
   const destination=navigationWindow.current;
   const atDestination=()=>current()&&navigationWindow.current===destination;
   const waitForSnapshot=(matches:()=>boolean,failure:string)=>new Promise<boolean>((resolve,reject)=>{
    let unsubscribe=()=>{};
    const timer=window.setTimeout(()=>{unsubscribe();if(atDestination())reject(new Error(failure));else resolve(false)},5000);
    const check=()=>{
     if(!atDestination()){clearTimeout(timer);unsubscribe();resolve(false);return}
     if(matches()){clearTimeout(timer);unsubscribe();resolve(true)}
    };
    unsubscribe=bridge.subscribe(check);check();
   });
   if(!atDestination())return;
   const reader=bridge.getState().state.conversationReader;
   if(reader){
    if(!can('conversation.reader.close'))throw new Error('请先关闭历史阅读窗口，再回到这件事的对话。');
    await act('conversation.reader.close',{window:reader.window,conversationId:reader.conversation});
    if(!await waitForSnapshot(()=>!bridge.getState().state.conversationReader,'历史阅读窗口尚未关闭，请关闭后再回到所属对话。'))return;
   }
   if(!atDestination())return;
   if(bridge.getState().state.chatSubmitting)throw new Error('当前消息仍在提交，请稍后回到这件事的对话。');
   if(bridge.getState().state.conversationId!==conversationId)await act('conversation.select',{id:conversationId});
   // Selection can acknowledge before its snapshot, or open a history reader
   // instead. Confirm both the identity and its editable conversation surface.
   if(!await waitForSnapshot(()=>bridge.getState().state.conversationId===conversationId&&!bridge.getState().state.conversationReader,'尚未确认已回到所属对话，请从对话列表重新打开。'))return;
   if(current()&&navigationWindow.current===destination&&bridge.getState().state.conversationId===conversationId){
    if(!await changeIntent('chat')||!atDestination())return;
    if(associate){
      if(!await stageButlerObject({kind:'work',id:task.id,runId:task.raw?.runId,epoch:task.raw?.coordinatorEpoch},atDestination))return;
      if(atDestination())setLocalStatus('已关联这次工作。追问写在原对话草稿里，尚未发送。');
    }
    requestAnimationFrame(()=>{if(current()&&navigationWindow.current===destination&&bridge.getState().state.conversationId===conversationId&&!bridge.getState().state.conversationReader)workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea')?.focus({preventScroll:true})});
   }
  }catch(error){if(current()&&navigationWindow.current===navigation+1)setLocalError(error instanceof Error?error.message:String(error))}
  finally{if(conversationReturnRequest.current===request)setReturningConversation('')}
 };
 const openTaskProject=async(task:Task)=>{
  const projectId=task.raw?.developmentContext?.projectId;
  if(typeof projectId!=='string'||!projects.some(project=>project.id===projectId)||!can('projects.select'))return;
  const navigation=navigationWindow.current;
  if(await openRelatedWorkspace('projects')&&navigationWindow.current===navigation+1)run('projects.select',{id:projectId});
 };
 const previewBlocked=!!(searchOriginal||projectWorkReviewOpen||providedMemoryMessage||conversationMemoryOpen||permissionOptionsOpen||organizationTarget||state.attachmentWorkspace?.open||phoneViewerExpanded||workOpen||commandOpen||taskOpen||projectPickerOpen||fileDisplayOpen||newProjectOpen||newFileOpen||phoneReview||voiceReview||state.chatAttachmentPickerOpen||messageQuoteTarget||messageOriginTarget||sourceSelection||skillDraftTarget||memoryDraftTarget||routeReferenceTarget||state.quickPanelVoice);
 const packageReturn=useRef<{target:PackagePreviewTarget;origin:WorkspaceOrigin;epoch:number;navigation:number;projectFocus:ProjectTaskReturn|null}|null>(null);
 const returnFromPackage=(id:string,operationId?:string)=>{
  const saved=packageReturn.current;
  if(saved&&saved.target.taskId===id&&saved.target.operationId===operationId&&saved.epoch===bridge.getDocumentEpoch()&&saved.navigation===navigationWindow.current){
   packageReturn.current=null;projectTaskReturn.current=saved.projectFocus?{...saved.projectFocus,focusOutcome:false}:null;
   void restoreWorkspace(saved.origin,false,true);
  }else openTask(id,operationId?'overview':'review',false,undefined,operationId?'':undefined);
 };
 const projectPreview=useProjectPreview({value:state.projectPreview,exportValue:state.projectPackageExport,projectId:String(state.projectId||''),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),active:route==='projects'&&!state.filePath,blocked:previewBlocked,can,onReturned:()=>navigate('chat'),onPackageTask:returnFromPackage});
 const packageHandoff=useProjectPackageHandoff({route,navigationRevision,projectId:String(state.projectId||''),blocked:previewBlocked,
  navigation:()=>navigationWindow.current,supported:can,show:projectPreview.showPackage,error:message=>toast.error(message,{id:'project-package-error'}),
  leaveTask:stillCurrent=>navigate('projects',undefined,{keepOrigin:true,stillCurrent})});
 const packageOpening=packageHandoff.opening;
 const openTaskPackage=(target:PackagePreviewTarget)=>{
  if(!taskOpen||taskOpening||taskId!==target.taskId||openedTaskId.current!==target.taskId)return;
  const origin=captureNavigationOrigin(),projectFocus=projectTaskReturn.current,epoch=bridge.getDocumentEpoch();
  packageHandoff.open(target,()=>{packageReturn.current={target,origin,projectFocus,epoch,navigation:navigationWindow.current}});
 };
 const previewRefsUnavailable=asArray<Json>(composer.previewRefs).length>0&&composer.previewRefsAvailable!==true;
 const enterPhoneNotification=(note:PhoneNotice)=>{
  const differentRun=()=>{const rows=bridge.getState().state?.tasks;const row=Array.isArray(rows)?rows.find(t=>t?.id===note.taskId):null;
   return !!row&&(row.raw?.runId!==note.runId||row.raw?.coordinatorEpoch!==note.epoch)};
  const stillHere=()=>!document.hidden&&phoneNoticeRef.current?.token===note.token&&originalPhoneNotice(bridge.getState().state?.originalPhoneNotification)?.token===note.token&&!differentRun();
  if(document.hidden||!note.current||originalPhoneNotice(bridge.getState().state?.originalPhoneNotification)?.token!==note.token)return;
  if(differentRun()){setPhoneNoticePanel(note.token);return}
  phoneNoticeRef.current=note;setPhoneNoticePin(note);setPhoneNoticePanel(null);
  openTask(note.taskId,'overview',false,undefined,undefined,{runId:note.runId,epoch:note.epoch},stillHere);
 };
 useEffect(()=>{
  const note=phoneNotification;if(!note||document.hidden||phoneNoticeSeen.current===note.token||!can('tasks.open'))return;
  phoneNoticeSeen.current=note.token;
  if(note.current)enterPhoneNotification(note);
  else setPhoneNoticePanel(note.token)
 },[phoneNotification?.token,can,openTask]);
 const returnFromPhoneNotice=()=>{const note=phoneNotification;if(!note)return;
  const release=()=>{setPhoneNoticePanel(null);if(can('phone.notice.close'))void bridge.command('phone.notice.close',{noticeHandle:note.token}).catch(()=>{})};
  if(noticeBinding&&taskOpen){void closeTask().then(closed=>{if(closed)release()});return}
  release();
 };
 const showPhoneNotice=!!phoneNotification&&(phoneNoticePanel===phoneNotification.token||noticeBinding?.token===phoneNotification.token&&taskOpen&&!taskOpening&&noticeNotReady);
 const previousRequestedTask=useRef(0),previousRequestedProject=useRef(0),previousRequestedConnection=useRef(0);
 useEffect(()=>{
  const revision=Number(state.requestedTaskRevision||0),id=String(state.requestedTaskId||'');
  // A notification can arrive before getTask or the document handshake finishes.
  // Consume it only when the original task can enter its native review window.
  if(revision<=previousRequestedTask.current||!id||!can('tasks.open')||!tasks.some(task=>task.id===id))return;
  previousRequestedTask.current=revision;
  openTask(id,'overview',snapshot?.view?.taskId===id);
 },[state.requestedTaskRevision,state.requestedTaskId,snapshot?.view?.taskId,tasks,can,openTask]);
 useEffect(()=>{const revision=Number(state.requestedProjectRevision||0);if(revision>previousRequestedProject.current)void navigate('projects');previousRequestedProject.current=revision;},[state.requestedProjectRevision,navigate]);
 useEffect(()=>{const revision=Number(state.requestedConnectionRevision||0);if(revision>previousRequestedConnection.current&&state.requestedConnectionSection)void navigate(state.requestedConnectionSection);previousRequestedConnection.current=revision;},[state.requestedConnectionRevision,state.requestedConnectionSection,navigate]);
 useEffect(()=>{bridge.setBackHandler(()=>{if(searchOriginal){closeSearchOriginal();return true}if(!preparationBeforeLeave.current&&preparationBack.current?.())return true;if(providedMemoryBack.current?.())return true;if(conversationMemoryBack.current?.())return true;if(queuedInputsBack.current?.())return true;if(historyWindowBack.current?.())return true;if(navigationMoreOpen){setNavigationMoreOpen(false);return true}if(chatMenuOpen){setChatMenuOpen(false);return true}if(permissionOptionsOpen){closePermissionOptions();return true}if(attachmentBack.current?.())return true;if(phoneViewerExpanded){setPhoneViewerExpanded(false);return true}if(readingDocuments.length){closeReading();return true}if(messageOriginTarget){setMessageOriginTarget(null);return true}if(messageQuoteTarget){setMessageQuoteTarget(null);return true}if(routeReferenceTarget){setRouteReferenceTarget(null);return true}if(skillDraftTarget){setSkillDraftTarget(null);return true}if(memoryDraftTarget){setMemoryDraftTarget(null);return true}if(sourceSelection){setSourceSelection(null);return true}if(messageMenu){setMessageMenu(null);return true}if(modelPicker.current?.handleBack())return true;if(state.chatAttachmentPickerOpen&&can('attachments.closePicker')){run('attachments.closePicker');return true}if(voiceReview){setVoiceReview(null);return true}if(phoneReview){if(!phoneReviewPending)setPhoneReview(null);return true}if(newProjectOpen||newFileOpen){closeCreate();return true}if(voiceOptionsOpen){setVoiceOptionsOpen(false);return true}if(workOptionsOpen){setWorkOptionsOpen(false);return true}if(commandOpen){setCommandOpen(false);return true}if(workOpen){setWorkOpen(false);return true}if(projectPickerOpen){setProjectPickerOpen(false);return true}if(fileDisplayOpen){setFileDisplayOpen(false);return true}if(taskOpen){backFromTask();return true}if(route==='tasks'&&taskFiltersOpen){closeWorkFind();return true}if(route==='tasks'&&restoreWorkScope())return true;if(route==='projects'&&projectWorkBack.current?.())return true;if(route==='projects'&&projectDevelopmentBack.current?.())return true;if(route==='projects'&&projectPreview.handleBack())return true;if(route==='projects'&&state.filePath){if(fileAdoption.isPending()||bridge.getState().state.fileSaving)return true;if(projectFileBack.current?.())return true;if(can('projects.close')&&!fileAdoption.pending){run('projects.close',fileIdentity);return true}return true}if(route==='chat'){if(preparationBeforeLeave.current&&preparationBack.current?.())return true;void navigate('butler');return true}if(route!=='butler'){if(route!=='tasks'&&route!=='projects'&&secondaryBack.current?.())return true;if(route==='setup')closeSetup();else returnFromSecondary();return true}return false;});return()=>{bridge.setBackHandler(()=>false)};},[searchOriginal,closeSearchOriginal,navigationMoreOpen,chatMenuOpen,permissionOptionsOpen,closePermissionOptions,phoneViewerExpanded,messageQuoteTarget,messageOriginTarget,routeReferenceTarget,skillDraftTarget,memoryDraftTarget,sourceSelection,readingDocuments,closeReading,messageMenu,voiceReview,phoneReview,phoneReviewPending,newProjectOpen,newFileOpen,voiceOptionsOpen,workOptionsOpen,commandOpen,workOpen,projectPickerOpen,fileDisplayOpen,taskFiltersOpen,closeWorkFind,restoreWorkScope,taskOpen,taskLayer,backFromTask,closeTask,route,state.filePath,state.chatAttachmentPickerOpen,fileIdentity,can,run,projectPreview,fileAdoption.pending]);
 useEffect(()=>{document.documentElement.dataset.pocketUi=ready?'ready':'connecting';},[ready]);
 useEffect(()=>{if(phoneDraft.phone===true)setIntent(phoneDraft.mode==='main_screen'?'main_screen':'background');else if(phoneDraft.phone===false)setIntent('chat');},[phoneDraft.phone,phoneDraft.mode]);
 useEffect(()=>{if(route==='projects'&&can('projects.refresh'))run('projects.refresh');else if((route==='tasks'||route==='butler')&&can('tasks.refresh'))run('tasks.refresh');},[route,can,run]);
 useLayoutEffect(()=>{if(!taskOpen)return;const node=taskScroll.current;if(node)node.scrollTop=taskReading.current.get(`${taskId}:${taskLayer}`)??0},[taskOpen,taskId,taskLayer]);
 useEffect(()=>{setFileDisplayOpen(false);setProjectPickerOpen(false)},[state.projectId]);
 useSurfaceEnter(taskScroll,`${taskId}:${taskLayer}`,{selector:'',depth:taskLayer==='overview'?0:1,active:taskOpen&&!selectedTask?.raw?.developmentContext});
 const markMessageArrival=useMessageArrivals(scroll,String(state.conversationId||''),messages.map((message,index)=>message.id||`${message.role}:${message.at}:${index}`),route==='chat',{
  owner:chatOwner,
  pendingClients:pendingInputs.filter(input=>input.draftKey===draftKey).map(input=>String(input.clientMessageId)),
  receipts:Object.entries(obj(state.chatReceipts)).map(([clientMessageId,value])=>({clientMessageId,conversationId:String(obj(value).conversationId||''),status:String(obj(value).status||'')})),
  submissions:messages.flatMap((message,index)=>message.role==='user'&&message.submission?.clientMessageId?[{id:message.id||`${message.role}:${message.at}:${index}`,clientMessageId:message.submission.clientMessageId}]:[]),
 });
 const openVoiceReview=()=>{if(!can('voice.review'))return;voiceExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);setVoicePending(true);void act('voice.review',{phone:intent!=='chat'}).then(value=>{setVoiceReview(value);setVoiceText(value.text||'')}).catch(error=>setLocalError(error.message)).finally(()=>setVoicePending(false));};
 const submitVoiceReview=(command:'voice.accept'|'voice.discard')=>{
  const reviewed=voiceReviewLive.current;if(!reviewed||voiceWriting.current||voicePending)return;
  voiceWriting.current=true;setVoicePending(true);
  void act(command,{reviewHandle:reviewed.reviewHandle,...(command==='voice.accept'?{text:voiceText}:{})}).then(()=>{if(voiceReviewLive.current===reviewed)setVoiceReview(null)}).catch(error=>setLocalError(error.message)).finally(()=>{voiceWriting.current=false;setVoicePending(false)});
 };
 const discardVoice=()=>{if(isListening&&can('voice.stop')){run('voice.stop');return}if(!can('voice.review')||!can('voice.discard'))return;setVoicePending(true);void act('voice.review',{phone:intent!=='chat'}).then(value=>act('voice.discard',{reviewHandle:value.reviewHandle})).catch(error=>setLocalError(error.message)).finally(()=>setVoicePending(false));};
 const queryOriginalPhone=(retire=false)=>{
  const original=phoneStart;if(document.hidden||phoneAction.current||!original)return;
  const blank=original.draftRequestId,action=retire?'phone.draft.retireUncreated':'phone.query';
  if(!can(action)||(!blank&&(!original.requestId||!original.conversationId))||(retire&&(!blank||!original.canRetire)))return;
  const args=blank?{draftRequestId:blank,...(retire?{confirmed:true}:{})}:{requestId:original.requestId,conversationId:original.conversationId};
  const window=phoneWindow.current,doc=bridge.getDocumentEpoch();
  const here=()=>!document.hidden&&window===phoneWindow.current&&doc===bridge.getDocumentEpoch();
  phoneAction.current=true;setPhoneReviewPending(true);
  void act(action,args).then(value=>{if(!here())return;const receipt=phoneStartReceipt(value,original);setPhoneStart(receipt);setLocalStatus(receipt.message);if(receipt.status!=='unknown')setPhoneReview(null)})
    .catch(()=>{if(here()){const receipt:PhoneStartReceipt={...original,status:'unknown',canRetire:false,message:'没有收到本次查询的回执。可以稍后再查，未重新启动任务。'};setPhoneStart(receipt);setLocalStatus(receipt.message)}})
    .finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 const blankReceiptActions=(receipt:PhoneStartReceipt)=><BlankPhoneReceiptActions receipt={receipt} pending={phoneReviewPending}
  onCheck={can('phone.query')?()=>queryOriginalPhone():undefined} onCloseWait={can('phone.draft.retireUncreated')?()=>queryOriginalPhone(true):undefined}
  onTask={id=>{if(!document.hidden){setPhoneReview(null);openTask(id)}}}/>;
 const preparePhone=(original?:string)=>{
  if(document.hidden||phoneAction.current||['changed','elsewhere','unverified'].includes(phoneDraft.sourceState)||!phoneText.value.trim()||!can('phone.prepare'))return;
  const previous=original?phoneReviewLive.current:null;
  if(original&&(!previous?.originalDraft||previous.reviewHandle!==original))return;
  if(previous&&(previous.mode!==intent||previous.goal.trim()!==phoneText.value.trim())){
    setPhoneReviewError({handle:previous.reviewHandle,message:'原目标或执行位置已变化。请返回核对，未准备其他任务。'});return;
  }
  if(phoneStart?.status==='unknown'){setLocalStatus('请先查询原手机请求，草稿仍保留。');return}
  if(!original)phoneExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);
  const window=phoneWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const current=()=>phoneWindow.current===window&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden&&(!original||phoneReviewLive.current?.reviewHandle===original);
  phoneAction.current=true;setPhoneReviewPending(true);setPhoneReviewError(null);
  void act('phone.prepare',{goal:phoneText.value,mode:intent,epoch:phoneDraft.epoch,...(previous?{originalDraft:previous.originalDraft}:{})}).then(value=>{
    if(!current())return;
    if(!value?.reviewHandle)throw Error('未取得这次目标的范围审阅，请重新核对。');
    setPhoneStart(null);setPhoneReview({...value,documentEpoch});
  }).catch(error=>{
    if(!current())return;const message=error instanceof Error?error.message:String(error);
    if(original)setPhoneReviewError({handle:original,message});else setLocalError(message);
  }).finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 const openPhoneReviewSettings=(handle:string,target:'notifications'|'accessibility')=>{
  if(document.hidden||phoneAction.current||phoneReviewLive.current?.reviewHandle!==handle||phoneStart?.status==='unknown')return;
  phoneAction.current=true;setPhoneReviewPending(true);setPhoneReviewError(null);
  void act('phone.review.settings',{reviewHandle:handle,target}).catch(()=>{
    if(phoneReviewLive.current?.reviewHandle===handle)setPhoneReviewError({handle,message:'未能确认设置页已打开。可在系统设置中查看后返回重新核对；没有提交任务。'});
  }).finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 const startReviewedPhone=()=>{
  const reviewed=phoneReviewLive.current;
  if(document.hidden||phoneAction.current||!reviewed||phoneReviewPending||phoneStart?.status==='unknown')return;
  const startWindow=phoneWindow.current,startDoc=bridge.getDocumentEpoch();const here=()=>!document.hidden&&phoneWindow.current===startWindow&&bridge.getDocumentEpoch()===startDoc;
  phoneAction.current=true;phoneText.markSent();setPhoneReviewPending(true);
  void act('phone.start',{reviewHandle:reviewed.reviewHandle}).then(value=>{
    if(!here())return;const receipt=phoneStartReceipt(value,reviewed);setPhoneStart(receipt);
    if(reviewed.conditions&&receipt.status==='rejected'&&value?.recheckReview===reviewed.reviewHandle){
      setPhoneReview(current=>current?.reviewHandle===reviewed.reviewHandle?{...current,documentEpoch:undefined}:current);
    }else if(receipt.status!=='unknown')setPhoneReview(current=>current?.reviewHandle===reviewed.reviewHandle?null:current);
    setLocalStatus(receipt.message);
  }).catch(error=>{if(!here())return;const receipt=phoneStartFailure(error,reviewed);setPhoneStart(receipt);setLocalStatus(receipt.message)})
    .finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 useEffect(()=>{const receipt=phoneDraft.startReceipt;if(receipt&&['accepted','rejected','unknown'].includes(receipt.status)){const original=phoneStartReceipt(receipt);setPhoneStart(original);if(original.status!=='unknown'&&phoneReview&&phoneReview.reviewHandle===receipt.reviewHandle)setPhoneReview(null)}},[phoneDraft.startReceipt?.status,phoneDraft.startReceipt?.reviewHandle,phoneDraft.startReceipt?.requestId,phoneDraft.startReceipt?.draftRequestId,phoneDraft.startReceipt?.canRetire,phoneDraft.startReceipt?.taskId,phoneOwner]);
 const send=()=>{if(intent!=='chat'&&['changed','elsewhere','unverified'].includes(phoneDraft.sourceState)){setLocalStatus('请先核对所选原话。这份手机目标没有提交。');return}if(inputLimitMessage){setLocalError(inputLimitMessage);return}if(!composerModelName){if(can('setup.read'))void openModelSetup();else void navigate('models');return}if(intent!=='chat'){preparePhone();return}if(!composerHasPayload(text,asArray<Json>(state.selectedChatAttachments).length)||!draftKey||previewRefsUnavailable)return;
  if(!can('composer.send')||state.chatSubmitting||pendingOriginal)return;
  const settled=registration.begin();if(!settled)return;
  if(route==='butler'){butlerSendConversation.current=String(state.conversationId||'');void navigate('chat')}
  setLocalStatus('');goToLatest();chatDraft.markSent();markMessageArrival();
  void act('composer.send',{key:draftKey,epoch:draftEpoch,text}).catch(error=>{
   const now=bridge.getState().composer;if(now?.key===draftKey&&now?.epoch===draftEpoch)setLocalError(error instanceof Error?error.message:String(error));
  }).finally(settled);
 };
 useLayoutEffect(()=>{if(route!=='chat'||!butlerSendConversation.current)return;const original=butlerSendConversation.current;butlerSendConversation.current=null;if(original===String(state.conversationId||''))goToLatest();},[route,state.conversationId,goToLatest]);
 const targetWork=async(task:Task,original?:WorkIdentity)=>{const identity=original||ownerWorkIdentity(task),conversationId=privateTaskConversationId(task);if(!identity||!identity.runId||identity.epoch<1||!conversationId||!can('ownerWork.supplement.read'))return;if(workSupplementBeforeLeave.current&&!await workSupplementBeforeLeave.current())return;setWorkSupplementTarget({...identity,goal:task.goal,conversationId});if(taskOpen&&taskId===task.id)showTaskLayer('overview');else openTask(task.id);};
 const returnToWorkInput=(pending:Json)=>{const identity=obj(pending.originalTarget) as WorkIdentity,task=tasks.find(row=>row.id===identity.taskId);if(task&&identity.runId&&Number.isSafeInteger(identity.epoch))void targetWork(task,identity);else if(can('composer.returnPending'))run('composer.returnPending',{clientMessageId:pending.clientMessageId});};
 const controlWork=(task:Task,action:'cancel'|'continue')=>{
  const identity=ownerWorkIdentity(task);
  if(!identity||!can('ownerWork.control')||task.raw?.controls?.[action==='cancel'?'canStop':'canContinue']!==true)return;
  const key=workIdentityKey(identity);
  if(workControlEntered.current.has(key)||workControls[key]?.unknown||workControls[key]?.usedForContinuation)return;
  workControlEntered.current.add(key);
  let keepOriginal=false;
  setWorkControls(old=>({...old,[key]:{pending:true,unknown:false,message:action==='continue'?'正在提交新一轮请求，尚未确认受理。':''}}));
  void bridge.command('ownerWork.control',{...identity,action}).then(receipt=>{
   if(action==='continue'){
    const {feedback,next}=ownerWorkContinuationReply(receipt,identity),nextKey=next&&workIdentityKey(next);
    keepOriginal=feedback.unknown||!!next;
    setWorkControls(old=>({...old,[key]:{...feedback,usedForContinuation:!!next},
      ...(nextKey&&!old[nextKey]?{[nextKey]:feedback}:{})}));
    return;
   }
   const message=receipt?.status==='rejected' ? receipt.message||'这次操作未开始，请处理后再试。' : receipt?.message||'已请求停止这件事，等待状态更新。';
   setWorkControls(old=>({...old,[key]:{pending:false,unknown:receipt?.status==='unknown',message}}));
  }).catch(error=>{
   keepOriginal=action==='continue';
   setWorkControls(old=>({...old,[key]:{pending:false,unknown:true,message:action==='continue'?ownerWorkContinuationReply(null,identity).feedback.message:error instanceof Error?error.message:'原工作控制回执尚未确认，请先核对原工作。'}}));
  }).finally(()=>{if(!keepOriginal)workControlEntered.current.delete(key)});
 };
 const openTaskResultFile=(id:string,file:TaskResultFile,target:HTMLButtonElement)=>{
  const open=()=>{
   const native=bridge.getState();
   if(document.hidden||openedTaskId.current!==id||obj(native.view).taskId!==id||native.state.attachmentWorkspace?.open||resultFileReturnFocus.current)return;
   const origin={taskId:id,target};
   resultFileReturnFocus.current=origin;
   void act('attachmentWorkspace.open',{taskId:id,fileId:file.id,sha256:file.sha256,openOriginal:true},id).catch(()=>{
    if(resultFileReturnFocus.current===origin)resultFileReturnFocus.current=null;
   });
  };
  if(!taskPolicyBeforeLeave.current?.(open))open();
 };
 const restoreTaskFileFocus=(event:Event)=>{
  const origin=resultFileReturnFocus.current;
  resultFileReturnFocus.current=null;
  if(!origin||event.defaultPrevented||document.hidden||!origin.target.isConnected||origin.target.disabled)return;
  if('taskId' in origin?openedTaskId.current!==origin.taskId:route!=='chat'||bridge.getState().state.conversationId!==origin.conversationId)return;
  event.preventDefault();
  origin.target.focus({preventScroll:true});
 };
 const openChatResult=async(resultMessageId:string,result:TaskResult,target:HTMLButtonElement)=>{
  const conversationId=String(state.conversationId||'');
  const fallback=()=>openTask(result.taskId,'overview',false,undefined,undefined,{runId:result.runId,epoch:result.epoch});
  if(!can('tasks.resultFiles')||!can('attachmentWorkspace.open')){fallback();return;}
  if(!conversationId||openingChatResult.current||resultFileReturnFocus.current)return;
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const stillCurrent=()=>{
   const current=bridge.getState();
   return !document.hidden&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&current.state.conversationId===conversationId&&!obj(current.view).taskId;
  };
  if(!stillCurrent())return;
  openingChatResult.current=resultMessageId;setOpeningChatResultId(resultMessageId);setLocalError('');
  const locator={taskId:result.taskId,resultMessageId,conversationId};
  let opening=false;
  try{
   const listed=await bridge.command('tasks.resultFiles',locator);
   if(!stillCurrent())return;
   if(listed?.taskId!==result.taskId||!Array.isArray(listed.files)||!listed.files.length&&!listed.readLimited){fallback();return;}
   const single=listed.files.length===1&&!listed.readLimited?listed.files[0] as TaskResultFile:null;
   resultFileReturnFocus.current={conversationId,target};opening=true;
   await bridge.command('attachmentWorkspace.open',{...locator,...(single?{fileId:single.id,sha256:single.sha256,openOriginal:true}:{})});
  }catch(error){
   if(opening){
    resultFileReturnFocus.current=null;
    if(stillCurrent())setLocalError(error instanceof Error?error.message:'暂时无法打开工作文件。');
   }else if(stillCurrent())fallback();
  }finally{
   openingChatResult.current='';setOpeningChatResultId('');
  }
 };
 const changeIntent=(next:'chat'|'background'|'main_screen'):Promise<boolean>=>{
   if(phoneAction.current||phoneReviewPending||state.chatSubmitting||state.phoneSubmitting){setLocalError('请先等当前输入处理完成，原草稿仍保留。');return Promise.resolve(false)}
   const window=++phoneWindow.current;
   const documentEpoch=bridge.getDocumentEpoch();
   setLocalError('');setLocalStatus('');
   setPhoneStart(previous=>previous?.status==='unknown'?previous:null);
   if(isListening&&can('voice.stop'))run('voice.stop');
   setPhoneReview(null);setVoiceReview(null);
   if(!can('composer.intent')){setLocalError('执行模式暂不可切换，请刷新当前状态。');return Promise.resolve(false)}
   return act('composer.intent',{phone:next!=='chat',...(next!=='chat'?{mode:next}:{})}).then(()=>{
     if(phoneWindow.current!==window||bridge.getDocumentEpoch()!==documentEpoch||document.hidden)return false;
     setIntent(next);return true;
   }).catch(error=>{
     if(phoneWindow.current===window)setLocalError(error instanceof Error?error.message:String(error));
     return false;
   });
 };
 const stageButlerObject=async(reference:Json,current:()=>boolean,reportInSource=false)=>{
   if(!current())return false;
   const draft=obj(bridge.getState().composer);
   if(draft.ready!==true||!draft.key||!Number.isSafeInteger(draft.epoch)||!Number.isSafeInteger(draft.revision)||!can('composer.object.add'))throw new Error('管家输入尚未就绪，请稍后关联当前对象。');
   const args={key:draft.key,epoch:draft.epoch,revision:draft.revision,reference};
   const receipt=await (reportInSource?bridge.command('composer.object.add',args):act('composer.object.add',args));
   if(!current())return false;
   if(receipt?.added!==true)throw new Error('对象关联结果尚待核对，请查看原草稿。');
   return new Promise<boolean>((resolve,reject)=>{
     let unsubscribe=()=>{};
     const timer=window.setTimeout(()=>{unsubscribe();if(current())reject(new Error('尚未确认关联后的草稿，请查看管家输入。'));else resolve(false)},5000);
     const check=()=>{
       if(!current()){clearTimeout(timer);unsubscribe();resolve(false);return}
       const next=obj(bridge.getState().composer);
       if(next.key!==draft.key||next.epoch!==draft.epoch){clearTimeout(timer);unsubscribe();resolve(false);return}
       if(next.revision>=draft.revision&&(Array.isArray(next.objectRefs)?next.objectRefs:[]).some((ref:Json)=>Object.entries(reference).every(([key,value])=>ref[key]===value))){clearTimeout(timer);unsubscribe();resolve(true)}
     };
     unsubscribe=bridge.subscribe(check);check();
   });
 };
 const conversationDraftId=String(state.conversationId||'');
 const fileDraftTarget:FileDraftTarget|undefined=can('composer.object.add')&&composer.ready===true&&typeof composer.key==='string'&&composer.key&&
  Number.isSafeInteger(composer.epoch)&&Number.isSafeInteger(composer.revision)&&composer.revision>0&&composer.conversationId===conversationDraftId
  ?{key:composer.key,epoch:composer.epoch,revision:composer.revision,conversationId:conversationDraftId,label:currentConversation?.title||'当前管家输入'}:undefined;
 const currentFileDraft=(target:FileDraftTarget)=>{
  const native=bridge.getState(),draft=obj(native.composer);
  if(document.hidden||draft.ready!==true||draft.key!==target.key||draft.epoch!==target.epoch||
   draft.conversationId!==target.conversationId||String(native.state.conversationId||'')!==target.conversationId)return null;
  return draft;
 };
 const stageFileInDraft=async(reference:Json,target:FileDraftTarget,stillOpen:()=>boolean)=>{
  if(!stillOpen()||currentFileDraft(target)?.revision!==target.revision)throw new Error('目标草稿已经变化，请在这份原件重新选择当前草稿。');
  const native=bridge.getState().state;
  if(native.chatSubmitting||native.phoneSubmitting||phoneAction.current||phoneReviewPending)throw new Error('输入正在提交，请稍后从这份原件再试。');
  return stageButlerObject(reference,()=>stillOpen()&&!!currentFileDraft(target),true);
 };
 const showFileDraft=async(reference:Json,target:FileDraftTarget,closeOriginal:()=>Promise<unknown>)=>{
  const documentEpoch=bridge.getDocumentEpoch();
  const stillAttached=()=>{
   if(bridge.getDocumentEpoch()!==documentEpoch)return false;
   const draft=currentFileDraft(target);
   return asArray<Json>(draft?.objectRefs).some(ref=>ref.kind===reference.kind&&ref.id===reference.id&&ref.sha256===reference.sha256);
  };
  if(!stillAttached())throw new Error('原草稿中已找不到这份文件，请在原件中重新关联。');
  const native=bridge.getState().state;
  if(native.chatSubmitting||native.phoneSubmitting||phoneAction.current||phoneReviewPending)throw new Error('输入正在提交，文件仍留在原话题草稿。稍后再打开草稿。');
  await closeOriginal();
  if(!stillAttached()||!await navigate('butler',undefined,{stillCurrent:stillAttached})||!stillAttached())return false;
  if(intent!=='chat'&&!await changeIntent('chat'))return false;
  if(!stillAttached())return false;
  setLocalStatus('已关联到管家输入，可以继续提问。');
  requestAnimationFrame(()=>{if(stillAttached())workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
  return true;
 };
 const askButlerAbout=(reference:Json)=>{
   if(state.chatSubmitting||state.phoneSubmitting||phoneAction.current||phoneReviewPending){setLocalError('请先等待当前输入处理完成。');return;}
   void navigate('butler',()=>{
     const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
     const current=()=>navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden;
     void changeIntent('chat').then(async changed=>{
       if(!changed||!current()||!await stageButlerObject(reference,current))return;
       setLocalStatus('已关联到管家输入，可以继续提问。');
       requestAnimationFrame(()=>{if(current())workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
     }).catch(error=>{if(current())setLocalError(error instanceof Error?error.message:'对象尚未关联，请核对原草稿。')});
   });
 };
 const askButlerForTeam=(request:TeamButlerRequest={})=>{
   if(state.chatSubmitting||state.phoneSubmitting||phoneAction.current||phoneReviewPending){setLocalError('请先等待当前输入处理完成，协作草稿仍保留。');return;}
   void navigate('butler',()=>{
     const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
     const current=()=>navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden;
     void changeIntent('chat').then(async changed=>{
       if(!changed||!current())return;
       if(request.teamId&&!await stageButlerObject({kind:'team',id:request.teamId},current))return;
       const goal=typeof request.goal==='string'?request.goal.trim():'';
       if(goal){
         const draft=obj(bridge.getState().composer);
         if(draft.ready!==true||!draft.key||!Number.isSafeInteger(draft.epoch)||!Number.isSafeInteger(draft.revision)||!can('composer.edit')){setLocalError('管家输入尚未就绪，原协作目标仍保留。');return;}
         const original=String(draft.text||''),instruction=`请安排团队协作完成以下目标：\n${goal}`;
         const next=original.trim()===goal||original.includes(instruction)?original:original.trim()?`${original}\n\n${instruction}`:instruction;
         try{
           await act('composer.edit',{key:draft.key,epoch:draft.epoch,text:next,expectedRevision:draft.revision});
           if(!current())return;
           setLocalStatus('协作目标已放入管家输入，核对后发送即可。');
         }catch(error){if(current())setLocalError(error instanceof Error?error.message:'原输入已变化，协作目标没有覆盖当前草稿。');return;}
       }
       requestAnimationFrame(()=>{if(current())workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
     }).catch(error=>{if(current())setLocalError(error instanceof Error?error.message:'团队尚未关联，请核对原草稿。')});
   });
 };
 const openConversationTeamDecision=(locator:TeamAttentionLocator,source:TeamDecisionOrigin)=>{
   const live=bridge.getState();
   if(document.hidden||route!=='chat'||taskOpen||live.state.conversationId!==source.conversationId||locator.runId!==source.teamRunId||!can('teams.readAttention'))return;
   const origin={...captureWorkspaceOrigin(),focus:null,focusKey:teamDecisionEntryKey(source),
     drawerChat:{id:source.conversationId,draftKey:String(live.composer?.key||''),documentEpoch:bridge.getDocumentEpoch()}};
   void openRelatedWorkspace('teams',origin,()=>{setTeamRunFocus(undefined);setTeamAttentionFocus({...locator,ownerWork:source,requestId:crypto.randomUUID()});});
 };
 const openWorkTeam=(team:OwnerWorkTeam)=>{
   if(!can('teams.readRun'))return;
   void openRelatedWorkspace('teams',undefined,()=>{setTeamAttentionFocus(undefined);setTeamRunFocus({requestId:crypto.randomUUID(),runId:team.teamRunId});});
 };
 const openTeamOwnerWork=(link:TeamOwnerWorkLink,intent:'overview'|'supplement')=>{
   if(!can('tasks.open')||link.readOnly!==true||!link.taskId||!link.runId||!Number.isSafeInteger(link.epoch)||link.epoch<1)return;
   if(intent==='supplement'&&(!link.currentRun||!can('ownerWork.supplement.read'))){setLocalError('这份协作属于较早的执行，请先查看原工作当前状态。');return;}
   const open=()=>{
     if(intent==='supplement')setWorkSupplementTarget({taskId:link.taskId,runId:link.runId,epoch:link.epoch,conversationId:link.conversationId,goal:link.goal||'这件工作'});
     if(!link.currentRun)setLocalStatus('这份协作来自较早一轮，已打开所属工作供你核对。');
     openTask(link.taskId,'overview',false,undefined,undefined,link.currentRun?{runId:link.runId,epoch:link.epoch}:undefined);
   };
   if(!secondaryBeforeLeave.current?.(open))open();
 };
 const startCapability=(purpose:CapabilityPurpose)=>{
   if(purpose==='team'){askButlerForTeam();return;}
   const destination=({project:'projects',remote:'devices',schedule:'automation'} as Partial<Record<CapabilityPurpose,string>>)[purpose];
   if(destination){void openRelatedWorkspace(destination);return}
   const mode=purpose==='phone.main_screen'?'main_screen':purpose==='phone.background'?'background':'chat';
   void navigate('butler',()=>{
     const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
     void changeIntent(mode).then(changed=>{
       if(!changed)return;
       requestAnimationFrame(()=>{if(navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden)workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
     });
   });
 };
 const stageMessageForPhone=(message:Message,keepDraft=false)=>{
  if(phoneAction.current||!can('phone.draft.fromMessage')||!message.id||message.role!=='user')return;
  if(!keepDraft&&phoneText.value.trim()&&phoneText.value.trim()!==message.content.trim()){setLocalError('已有另一份手机草稿，请先保留或处理；没有覆盖原文字。');return}
  const window=++phoneWindow.current,conversationId=String(state.conversationId||''),documentEpoch=bridge.getDocumentEpoch();
  phoneAction.current=true;setPhoneReviewPending(true);setMessageMenu(null);setPhoneReview(null);setLocalStatus('');
  void act('phone.draft.fromMessage',{conversationId,messageId:message.id,sourceRevision:message.sourceRevision,epoch:phoneDraft.epoch,revision:phoneDraft.revision,...(keepDraft?{keepDraft:true}:{})}).then(value=>{
   if(phoneWindow.current!==window||bridge.getDocumentEpoch()!==documentEpoch||bridge.getState().state.conversationId!==conversationId)return;
   if(value?.staged!==true||value.taskCreated!==false||value.executionStarted!==false||typeof value.text!=='string')throw new Error('尚未确认原请求已放入手机草稿，请核对输入框；没有请求启动。');
   phoneText.set(value.text);setIntent(value.mode==='main_screen'?'main_screen':'background');setPhoneStart(null);
   setLocalStatus(value.message);workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea')?.focus();
  }).catch(error=>{if(phoneWindow.current===window)setLocalError(error instanceof Error?error.message:String(error))})
   .finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 const refreshReview=()=>{if(!taskId||taskOpening||reviewPending||questionPending)return;if(openedTaskId.current===taskId)loadTaskReview(taskId);else openTask(taskId,taskLayer==='scope'?'scope':'review')};
 const submitQuestion=async(action:string,args:Record<string,unknown>)=>{
  if(!taskId||!review||reviewStale||reviewSubmitted||!review.ownerQuestion||!can(action))throw new Error('请先重新核对这个原问题。');
  const request=reviewRequest.current;const handle=review.reviewHandle;setQuestionPending(true);setReviewSubmitted(true);
  try{return await act(action,{reviewHandle:handle,...args},taskId)}
  finally{if(reviewRequest.current===request)setQuestionPending(false)}
 };
 const reviewedAction=(action:string,args:Json={})=>{
  if(!taskId||!review||reviewStale||reviewSubmitted||!can(action))return;
  if(action==='tasks.resolve'&&args.checkedOriginal===true){
   const originalTask=taskId,doc=bridge.getDocumentEpoch(),v=bridge.getState().view as {resumeRevision?:number}|undefined,resume=v?.resumeRevision;
   const fact=asArray<Json>(review.unresolvedOperations).find(r=>r.format===PHONE_RECORD&&r.taskId===originalTask&&r.operationId===args.operationId&&r.runId===args.operationRun&&r.epoch===args.operationEpoch&&r.tool===args.operationTool&&r.state==='unknown'&&r.canRecord===true);
   if(!fact||!['occurred','not_occurred'].includes(args.outcome))return;
   const current=()=>{const view=bridge.getState().view as {taskId?:string;resumeRevision?:number}|undefined;return !document.hidden&&bridge.getDocumentEpoch()===doc&&view?.taskId===originalTask&&view.resumeRevision===resume&&openedTaskId.current===originalTask;};
   setReviewSubmitted(true);setReviewReceipt('正在核对这笔原记录…');
   void bridge.command(action,{reviewHandle:review.reviewHandle,...args}).then(receipt=>{
    if(!current())return;
    if(receipt?.format!=='pocketworld.owner-phone-reconciliation-receipt.v1'||receipt.taskId!==originalTask||receipt.runId!==fact.runId||receipt.epoch!==fact.epoch||receipt.operationId!==fact.operationId||
       receipt.outcome!==args.outcome||receipt.ownerRecorded!==true||receipt.automaticallyVerified!==false||receipt.automaticReplay!==false||!Number.isSafeInteger(receipt.recordedAt)||receipt.recordedAt<=0)
      throw new Error('尚未核对到这笔原登记，请查看原记录，不要重复提交。');
    setReviewReceipt('已按你的核对记为'+(args.outcome==='occurred'?'已发生':'未发生')+'。没有重放动作，也没有确认整个任务完成。');setReviewSubmitted(false);setReview(null);
   }).catch(e=>{if(current()){setReviewSubmitted(false);setReview(null);setReviewReceipt(e instanceof Error?e.message:'登记回执未确认，请重新查看原动作，不要重复提交。');}});
   return;
  }
  if(action==='tasks.resume'&&projectContinuation(review)){
   if(continuationBlocks(continuationsRef.current[taskId])||!currentReviewWindow(reviewWindow.current))return;
   let submission:ReturnType<typeof continuationRequest>;
   try{submission=continuationRequest(review,taskId,projectFeedback[taskId]||'')}catch(error){setTaskErrors(previous=>({...previous,[taskId]:error instanceof Error?error.message:String(error)}));return}
   const window=reviewWindow.current,originalTask=taskId;
   const pending:ContinuationSubmission={original:submission.original,status:'pending',message:'正在确认原项目任务续接。对话草稿仍保留，尚未发送。'};
   rememberContinuation(pending);setReviewSubmitted(true);setReviewReceipt('');
   const settle=(value:unknown,error=false)=>{const original=continuationsRef.current[originalTask];if(!original||original.original.reviewHandle!==pending.original.reviewHandle)return;const current=currentReviewWindow(window);rememberContinuation(error?continuationFailed(original,value,current):continuationSettled(original,value,current));if(current&&error){setReviewSubmitted(false);setReview(null)}};
   void bridge.command('tasks.resume',submission.args).then(value=>settle(value),error=>settle(error,true));return;
  }
  const request=reviewRequest.current,originalTask=taskId;
  const planDecision=action==='tasks.plan.enter'||action==='tasks.approve'&&asArray<Json>(review.approvals).some(row=>(row.approvalId||row.id)===args.approvalId&&row.webReview?.kind==='execution_plan');
  setReviewSubmitted(true);setReviewReceipt('');
  const payload={reviewHandle:review.reviewHandle,...args};
  if(planDecision)setTaskErrors(previous=>({...previous,[originalTask]:''}));
  void (planDecision?bridge.command(action,payload):act(action,payload,taskId)).then(value=>{
   if(!planDecision||reviewRequest.current!==request)return;
   const receipt=obj(value),original=obj(receipt.task||receipt);
   if((original.id||original.taskId)!==originalTask)throw new Error('未确认这次决定属于原任务，请重新读取原记录；不要重复提交。');
   if(receipt.presentationCurrent===false){setReviewReceipt('原决定已有保存回执，但审阅窗口已关闭。请核对原任务中的继续状态；没有重新提交。');return;}
   if(action==='tasks.plan.enter'){
    if(original.executionScope?.executionPhase!=='planning'||receipt.executionStarted===true)throw new Error('尚未确认只读规划已保存，请重新核对原任务；没有重复提交。');
    setReviewReceipt('只读规划已保存在原任务中，尚未启动执行。原预算与截止时间保持不变。');
   }else{
    const decision=asArray<Json>(original.approvals).find(row=>(row.approvalId||row.id)===args.approvalId);
    if(args.approved===false){
     if(decision?.state!=='denied')throw new Error('尚未确认这次拒绝已保存，请核对原任务；没有重复提交。');
     setReviewReceipt('已拒绝这次计划申请，没有批准执行。');
    }else{
     if(receipt.approvalDecisionSaved!==true&&!['approved','consumed'].includes(String(decision?.state||'')))throw new Error('尚未确认这版计划的决定已保存，请核对原任务；没有重复提交。');
     setReviewReceipt(receipt.continuedOriginalRun===true?'这版计划已确认，宿主已确认续接原任务；实际执行结果请看原任务记录。':receipt.continuationScheduled===true?'这版计划的决定已保存，续接已排队；尚未确认开始执行。':'这版计划的决定已保存；尚未确认启动执行，请核对原任务记录。');
    }
   }
  }).catch(error=>{if(reviewRequest.current!==request)return;setReviewSubmitted(false);setReview(null);if(planDecision){const message=error instanceof Error?error.message:'未确认原计划决定，请核对原任务；没有重复提交。';setReviewReceipt(message);setTaskErrors(previous=>({...previous,[originalTask]:message}))}});
 };
 const taskDirectory=useWorkDirectory({tasks,query:taskQuery,filter:filter as WorkCollectionFilter,enabled:ready&&route==='tasks'&&can('work.tasks.list'),identity:`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}`,scrollRef:taskList});
 const projectDirectory=useWorkDirectory({tasks,projectId:String(state.projectId||''),query:projectWorkView.query,filter:projectWorkView.filter,enabled:ready&&route==='projects'&&!!state.projectId&&!state.filePath&&projectSection==='work'&&!projectPreview.expanded&&can('work.tasks.list'),identity:`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}`,scrollRef:projectScroll});
 useLayoutEffect(()=>{
  const origin=projectTaskReturn.current;
  if(!origin?.focusOutcome||origin.taskId!==taskId||!taskOpen||taskOpening||taskLayer!=='overview'||openedTaskId.current!==taskId||snapshot?.view?.taskId!==taskId)return;
  const outcome=taskScroll.current?.querySelector<HTMLElement>('.pw-task-outcome');
  if(outcome){origin.focusOutcome=false;outcome.tabIndex=-1;outcome.focus({preventScroll:true});outcome.scrollIntoView({block:'start',behavior:'instant'})}
 },[taskOpen,taskOpening,taskId,taskLayer,selectedTask?.id,snapshot?.view?.taskId]);
 const openProjectWork=(id:string,entry:ProjectWorkEntry='overview',query='',checkoutId?:string)=>{
  const projectId=String(state.projectId||'');
  if(!projectId||bridge.getState().state.projectId!==projectId||bridge.getState().state.filePath)return;
  projectTaskReturn.current={projectId,checkoutId,query,taskId:id,entry,focusOutcome:entry==='outcomes'};
  const navigation=navigationWindow.current,epoch=bridge.getDocumentEpoch(),request=++searchOpenRequest.current;
  const current=()=>searchOpenRequest.current===request&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===epoch&&
   !document.hidden&&bridge.getState().state.projectId===projectId&&!bridge.getState().state.filePath;
  openTask(id,entry==='outcomes'?'overview':entry,false,undefined,query,undefined,current);
 };
 const openProjectReceipt=(id:string,checkoutId?:string)=>openProjectWork(id,'overview','',checkoutId);
 const drillEndedWork=()=>{
  if(taskList.current)taskDirectory.rememberScroll(taskList.current.scrollTop);
  setEndedScopeOrigin({filter:filter as WorkCollectionFilter,query:taskQuery});setTaskFiltersOpen(false);
  workDrillFocus.current='ended';setFilter('ended');
 };
 useLayoutEffect(()=>{
  const target=workDrillFocus.current;if(!target||route!=='tasks'||!taskDirectory.loaded||taskDirectory.busy||taskDirectory.error||!taskList.current)return;
  const list=taskList.current,control=target==='origin'?list.querySelector<HTMLElement>('[data-work-ended-link]')||list.querySelector<HTMLElement>('[data-work-scope="all"]'):list.querySelector<HTMLElement>('[data-work-scope="ended"]');
  control?.focus({preventScroll:true});workDrillFocus.current=null;
 },[route,filter,taskQuery,taskDirectory.loaded,taskDirectory.busy,taskDirectory.error]);
 const attentionTasks=tasks.filter(attention);
 const navigationSelection=state.attachmentWorkspace?.open?'artifacts':route==='chat'?'butler':['developer-logs','setup','remoteInbox','profile'].includes(route)?route:isPrimaryRoute?route:workspaceNavigationKey(secondaryPage||route);
 const selectNavigation=useCallback((key:string)=>{
  setNavigationMoreOpen(false);setSearchReturn(false);
  if(key==='artifacts'){
   // Outcomes cover the current workspace; closing them must reveal that same route.
   setWorkOpen(false);setCommandOpen(false);
   if(can('attachmentWorkspace.open'))run('attachmentWorkspace.open');
  }else if(key==='tasks'&&route==='chat'&&!desktopNavigation){
   void openRelatedWorkspace('tasks',captureNavigationOrigin());
  }else if(key==='remoteInbox'){
   void openNotifications(()=>{notificationMemory.current.attentionRequest=(notificationMemory.current.attentionRequest||0)+1});
  }else if(key==='setup')openSetup();
  else navigate(key);
 },[can,run,route,desktopNavigation,captureNavigationOrigin,openRelatedWorkspace,openNotifications,openSetup,navigate]);
 const workNav:WorkNavItem[]=useMemo(()=>[
  {key:'butler',label:'管家',icon:IconSparkles,group:'主入口'},
  {key:'tasks',label:'我的工作',icon:IconCalendar,group:'工作',status:attentionTasks.length?'有工作需要处理':undefined},
  {key:'projects',label:'项目',icon:IconFolder,group:'工作'},
  {key:'artifacts',label:'成果',icon:IconLayers,group:'工作',disabled:!can('attachmentWorkspace.open')},
  {key:'memory',label:'我与管家',icon:IconMemory,group:'管理'},
  {key:'capabilities',label:'能力与工具',icon:IconSparkles,group:'管理'},
  {key:'devices',label:'设备与执行',icon:IconMonitor,group:'管理'},
  {key:'remoteInbox',label:'通知',icon:IconNotification,group:'日常',count:Number(state.remoteInbox?.unread||0)||undefined},
  {key:'automation',label:'自动化',icon:IconCalendarCheck,group:'工作区'},
  {key:'teams',label:'协作记录',icon:IconTeam,group:'工作区'},
  {key:'media',label:'媒体',icon:IconImage,group:'工作区'},
  {key:'model-settings',label:'账号与模型',icon:IconAccount,group:'偏好'},
  ...(can('setup.read')?[{key:'setup',label:'准备中心',icon:IconCheckCircle,group:'偏好'}]:[]),
  {key:'settings',label:'设置',icon:IconSettings,group:'偏好'},
   {key:'developer-logs',label:'开发诊断',icon:IconTerminal,group:'偏好'}
 ],[attentionTasks.length,state.remoteInbox?.unread,can]);
 const openSearchResult=async(item:WorkSearchItem,searchCurrent:()=>boolean=()=>true)=>{
  if(!searchCurrent())return;
  const request=++searchOpenRequest.current,navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const resumeRevision=Number(obj(bridge.getState().view).resumeRevision||0),query=workSearchMemory.current.query||'';
  let retainedForContinuation=false;
  const ownsRequest=()=>searchOpenRequest.current===request&&bridge.getDocumentEpoch()===documentEpoch
    &&Number(obj(bridge.getState().view).resumeRevision||0)===resumeRevision&&!document.hidden;
  const stillCurrent=()=>ownsRequest()&&navigationWindow.current===navigation&&(retainedForContinuation||searchCurrent());
  const commit=async()=>{
   if(!stillCurrent())return;
   if(item.kind==='conversation'&&item.ownerLocator){
    if(!can('work.search.read'))throw new Error('当前版本暂不能读取搜索命中的原文。');
    setSearchOriginal(item);setSearchReturn(true);setWorkOpen(false);setCommandOpen(false);return;
   }
   if(item.kind==='work'){
    if(!can('tasks.open'))throw new Error('当前版本暂不能打开工作。');
    retainedForContinuation=true;
    setSearchReturn(true);setWorkOpen(false);setCommandOpen(false);openTask(item.taskId||item.id,'overview',false,undefined,query,undefined,stillCurrent);return;
   }
   if(item.kind==='artifact'){
    if(!can('attachmentWorkspace.open')||!item.sha256)throw new Error('这份成果的准确版本暂不可打开，请重新搜索。');
    await bridge.command('attachmentWorkspace.open',{fileId:item.artifactId||item.id,sha256:item.sha256,...(item.taskId?{taskId:item.taskId}:{})});
    if(!stillCurrent())return;
    setSearchReturn(true);setWorkOpen(false);setCommandOpen(false);return;
   }
   const target=item.kind==='conversation'?'chat':'projects';
   const action=item.kind==='conversation'?'conversation.select':'projects.select';
   if(!can(action))throw new Error('当前版本暂不能打开这条记录。');
   retainedForContinuation=true;
   await navigate(target,()=>{
    if(!ownsRequest()||navigationWindow.current!==navigation+1)return;
    setSearchReturn(true);
    void bridge.command(action,{id:item.kind==='conversation'?item.conversationId||item.id:item.projectId||item.id})
      .catch(failure=>{if(ownsRequest()&&navigationWindow.current===navigation+1)setLocalError(failure instanceof Error?failure.message:String(failure))});
   },{stillCurrent});
  };
  if(item.kind==='work'||item.kind==='artifact'){
   // A draft guard owns the explicit continuation after it closes the search.
   // New searches, navigation and document changes still retire that request.
   const resume=()=>{retainedForContinuation=true;void commit().catch(failure=>{if(stillCurrent())setLocalError(failure instanceof Error?failure.message:String(failure))})};
   if(secondaryBeforeLeave.current?.(resume)||route==='profile'&&profileBeforeLeave.current?.(resume)){retainedForContinuation=true;setWorkOpen(false);setCommandOpen(false);return;}
  }
  await commit();
 };
 useEffect(()=>{if(!currentFileKey||!state.fileReady||fileAdoption.holdsDraft())return;setFileDrafts(previous=>{const old=previous[currentFileKey];const version=Number(state.fileVersion||0),content=String(state.fileContent||''),baseline=String(state.fileBaseline||'');if(!old)return {...previous,[currentFileKey]:{text:content,baseline,version,dirty:content!==baseline}};if(old.submitted!==undefined&&content===old.submitted&&version!==old.version){
   // The native saving snapshot arrives asynchronously. A newer local edit can
   // already exist when this earlier save is acknowledged; only its baseline moved.
   return {...previous,[currentFileKey]:{text:old.text,baseline:content,version,dirty:old.text!==content}};
  }
  // Even a return to the pre-save baseline is a newer draft while the submitted
  // value is unresolved. A native saving snapshot must not replace that choice.
  if(old.submitted!==undefined&&old.text!==old.submitted)return previous;
  if(!old.dirty&&(old.text!==content||old.version!==version))return {...previous,[currentFileKey]:{text:content,baseline,version,dirty:content!==baseline}};return previous;});},[currentFileKey,state.fileContent,state.fileBaseline,state.fileVersion,state.fileReady,fileAdoption.pending,fileAdoption.unknown]);
 useLayoutEffect(()=>{if(route==='projects'&&!state.filePath&&!projectPreview.expanded&&(!state.projectId||projectSection==='files')){const node=projectScroll.current;if(!node)return;node.scrollTop=projectListTop.current.get(projectPositionKey)||0;if(projectSection==='files'){const target=Array.from(node.querySelectorAll<HTMLElement>('[data-file-path], [data-tree-item]')).find(element=>element.dataset.filePath===projectView.selected||element.dataset.treeItem===projectView.selected);target?.focus({preventScroll:true})}}},[route,projectPositionKey,projectSection,state.filePath,projectPreview.expanded]);
 const fileConflict=!!fileDraft?.dirty&&fileDraft.version!==Number(state.fileVersion||0);
 const editFile=(value:string)=>{if(!currentFileKey||!fileDraft||fileAdoption.isPending())return;fileEditRevisions.current.set(currentFileKey,(fileEditRevisions.current.get(currentFileKey)||0)+1);setFileDrafts(prev=>({...prev,[currentFileKey]:{...prev[currentFileKey],text:value,dirty:value!==prev[currentFileKey].baseline}}));if(can('projects.edit')&&!fileConflict)run('projects.edit',{projectId:state.projectId,path:state.filePath,version:fileDraft.version,content:value});};
 const saveFile=()=>{if(!fileDraft||fileConflict||fileAdoption.isPending()||!can('projects.save'))return;setFileDrafts(prev=>({...prev,[currentFileKey]:{...prev[currentFileKey],submitted:fileDraft.text}}));run('projects.save',{projectId:state.projectId,path:state.filePath,version:fileDraft.version,content:fileDraft.text});};
 const copyText=async(textToCopy:string,feedback:'toast'|'local'='toast')=>{try{if(!navigator.clipboard?.writeText)throw new Error('当前无法直接复制，请选择文字后复制。');await navigator.clipboard.writeText(textToCopy);if(feedback==='toast')toast('已复制');}catch(error){const message=error instanceof Error?error.message:'复制未完成，请选择文字后复制。';setLocalError(message);toast.error(message);throw error;}};
 const copyWithLocalFeedback=(textToCopy:string)=>copyText(textToCopy,'local');

 const createObject=()=>{
  const original=createWindow.current;if(createPending.current.has(original))return;
  const project=newProjectOpen;const args=project?{name:projectName}:{projectId:state.projectId,path:newPath};
  createPending.current.add(original);setCreateBusy(true);
  void act(project?'projects.create':'projects.newFile',args).then(()=>{
   if(createWindow.current!==original)return;
   closeCreate();if(project)setProjectName('');else setNewPath('');
  }).catch(()=>{}).finally(()=>{createPending.current.delete(original);if(createWindow.current===original)setCreateBusy(false)});
 };

 const openSource=(message:Message)=>{if(can('sources.open')&&message.id&&message.retainedSources)setSourceSelection({messageId:message.id});else setLocalStatus('这条回答没有可核对的原来源身份。');};
 useEffect(()=>{setMessageQuoteTarget(null)},[draftKey,draftEpoch,intent]);
 const openQuote=(message:Message)=>{if(intent==='chat'&&!state.chatSubmitting&&draftKey&&can('messageQuote.open')&&message.quoteAvailable&&message.id&&Number.isSafeInteger(message.sourceRevision)&&Number(message.sourceRevision)>0)setMessageQuoteTarget({messageId:message.id,sourceRevision:Number(message.sourceRevision),key:draftKey,epoch:draftEpoch});else setLocalStatus('原消息或草稿已变化，请重新核对后引用。');};
 const renderOperation=(operation:unknown,index:number,scope:string)=>{
  const op=presentOperation(operation);const key=`${scope}:${op.runId}:${op.id||index}`;
  const packageTarget=packageOperationTarget(tasks.find(task=>task.id===scope),operation);
  const executionTask=tasks.find(task=>task.id===scope);
  const memoryProposal=obj(obj(obj(operation).receipt).memoryDraft);const memoryTarget=memoryProposal.format==='pocketworld.conversation-memory-draft.v1'&&memoryProposal.taskId===scope&&memoryProposal.operationId===obj(operation).operationId&&memoryProposal.runId===obj(operation).runId?memoryProposal:null;
  const proposal=obj(obj(obj(operation).receipt).skillDraft);const draftTarget=proposal.format==='pocketworld.conversation-skill-draft.v1'&&proposal.taskId===scope&&proposal.operationId===obj(operation).operationId&&proposal.runId===obj(operation).runId?proposal:null;
  return <section className="pw-operation-entry" key={key}><ToolCall id={op.id||String(index)} name={op.name} summary={op.stateLabel} status={op.status} input={op.arguments} result={op.result} durationMs={op.durationMs} open={expandedCalls[key]??false} onOpenChange={value=>setExpandedCalls(x=>({...x,[key]:value}))}/>{packageTarget&&can('projects.package.preview.inspect')&&<Button variant="secondary" disabled={packageOpening||taskOpening||!can('projects.package.preview.open')||!can('tasks.close')} onClick={()=>openTaskPackage(packageTarget)}>查看这次任务的固定包</Button>}{draftTarget&&can('skillDraft.open')&&<Button variant="secondary" onClick={()=>setSkillDraftTarget(draftTarget as ConversationDraftTarget)}>审阅原技能提案</Button>}{memoryTarget&&can('memoryDraft.open')&&<Button variant="secondary" onClick={()=>setMemoryDraftTarget(memoryTarget as ConversationDraftTarget)}>审阅原记忆提案</Button>}{op.readLimited&&<p className="pw-muted">这条记录仅保留摘要，详情需要回到原记录读取。</p>}{executionTask&&obj(operation).tool==='project_execute'&&can('local.execution.open')&&<ConversationModelPickerVisibility key={'log:'+key}>{pickerVisible=><ProjectExecutionWorkspace task={executionTask} operation={obj(operation)} active={taskOpen&&taskLayer==='process'&&!pickerVisible&&!phoneViewerObscured} run={act}/>}</ConversationModelPickerVisibility>}</section>;
 };
 const taskActionLabel=(task:Task)=>conversationWorkActionLabel(task);
 const showTaskReview=()=>refreshReview();
 // Match the existing full-window layer breakpoints, rather than coloring native
 // safe areas from a route hidden behind a file or reading workspace.
 const [readingFullWindow,setReadingFullWindow]=useState(()=>!window.matchMedia('(min-width: 760px) and (min-height: 600px)').matches);
 useEffect(()=>{
  const reading=window.matchMedia('(min-width: 760px) and (min-height: 600px)');
  const update=()=>setReadingFullWindow(!reading.matches);
  update();reading.addEventListener('change',update);
  return()=>reading.removeEventListener('change',update);
 },[]);
 const canvasRole=readingDocuments.length>0&&readingFullWindow?'chat':
  desktopNavigation||state.attachmentWorkspace?.open?'workspace':isChatSurface?'chat':'workspace';
 const canvasSupported=can('settings.canvas'),canvasResume=Number(obj(snapshot?.view).resumeRevision||0);
 useEffect(()=>{
  let active=true;
  const sync=()=>{
   if(!active||document.hidden||!canvasSupported)return;
   // A newer route/foreground render cancels a pending presentation-only send.
   void Promise.resolve().then(()=>{
    if(!active||document.hidden||bridge.getDocumentEpoch()!==personalDocumentEpoch)return;
    return bridge.command('settings.canvas',{role:canvasRole});
   }).catch(()=>{
    // Native resume/document rebinding or the next visible layer resynchronizes.
    // Keep rejected display requests out of business errors and retry loops.
   });
  };
  sync();document.addEventListener('visibilitychange',sync);
  return()=>{active=false;document.removeEventListener('visibilitychange',sync)};
 },[canvasRole,canvasSupported,personalDocumentEpoch,canvasResume]);
 const phoneViewerObscured=!!(searchOriginal||preparationTarget||providedMemoryMessage||conversationMemoryOpen||state.conversationReader||state.queuedInputsPage||permissionOptionsOpen||organizationTarget||state.attachmentWorkspace?.open||workOpen||commandOpen||projectPickerOpen||fileDisplayOpen||newProjectOpen||newFileOpen||phoneReview||voiceReview||state.chatAttachmentPickerOpen||messageQuoteTarget||messageOriginTarget||sourceSelection||skillDraftTarget||memoryDraftTarget||routeReferenceTarget||state.quickPanelVoice||readingDocuments.length);
 const phoneViewerFor=(task:Task)=>{
  const target=phoneViewerTask(task);if(!target||!can('phone.viewer.open')||!can('phone.viewer.frame')||!can('phone.viewer.close'))return null;
  const local=isPrivateLocalPhone(task),live=privatePhoneSurface(task,state.livePrivatePhones);
  const phoneControllable=!local||(!!live&&!live.stopRequested);
  const conversationId=phoneConversationId(task);
  const relatedViewers=conversationId?tasks.filter(item=>phoneConversationId(item)===conversationId)
   .map(phoneViewerTask).filter((item):item is PhoneViewerTask=>item!==null):[];
  const preparationSession=obj(task.raw?.evidence?.phoneSession);
  const canCheckConnection=target.preparation==='blocked'&&preparationSession.taskId===target.taskId&&preparationSession.runId===target.runId&&preparationSession.coordinatorEpoch===target.epoch&&preparationSession.state==='not_initialized'&&preparationSession.physicalDisplayHeld===false&&task.raw?.evidence?.message===target.detail;
  return <ConversationModelPickerVisibility>{pickerVisible=><PhoneViewer key={`${task.id}:${target.runId}:${target.epoch}`} task={target} expanded={phoneViewerExpanded} onExpandedChange={setPhoneViewerExpanded} nativeVisible={!pickerVisible&&!phoneViewerObscured&&taskOpen&&taskLayer==='overview'}
   tasks={relatedViewers} onSelectTask={can('tasks.open')?selected=>openTask(selected.taskId,'overview',false,undefined,undefined,selected):undefined}
   onPause={phoneControllable&&(!local||!live?.paused)&&task.status==='running'&&can('phone.pause')?identity=>exactPhoneControl('phone.pause',identity,local):undefined} onResume={phoneControllable&&(!local||live?.paused)&&task.status==='paused'&&!conversationInlineAttention(task)&&can('phone.resume')?identity=>exactPhoneControl('phone.resume',identity,local):undefined}
   onStop={phoneControllable&&!terminal(task)&&task.status!=='interrupted'&&task.status!=='stopping'&&can('phone.stop')?identity=>exactPhoneControl('phone.stop',identity,local):undefined}
   onOpenSetup={canCheckConnection&&can('setup.read')?()=>{setPhoneViewerExpanded(false);void openSetup('phone')}:undefined}
   onOpenTask={can('tasks.open')?identity=>{setPhoneViewerExpanded(false);if(taskOpen&&openedTaskId.current===identity.taskId){showTaskLayer(task.status==='failed'?'process':'overview');if(attention(task)&&can('tasks.review')&&!reviewPending)loadTaskReview(identity.taskId)}else openTask(identity.taskId,task.status==='failed'?'process':attention(task)&&can('tasks.review')?'review':'overview')}:undefined}/>}</ConversationModelPickerVisibility>;
 };
 const taskReviewBody=(task:Task)=>{
  const canFollowOriginal=privateTaskConversationId(task)&&task.raw?.contextAvailable!==false&&
    (task.raw?.executionRole==='owner_work'||task.raw?.nativeResultHandling===true||task.raw?.ownerWorkPreparation)&&
    typeof task.raw?.runId==='string'&&!!task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0&&
    can('conversation.select')&&can('composer.object.add')&&!state.chatSubmitting&&!returningConversation;
  const followOriginal=canFollowOriginal?()=>{if(openedTaskId.current===task.id&&!taskOpening)void returnToTaskConversation(task,true)}:undefined;
  if(taskLayer==='homeReport')return <ButlerReturnedReport report={homeReport.current} task={task} active={taskOpen&&!taskOpening&&openedTaskId.current===task.id&&obj(snapshot?.view).taskId===task.id} onCopy={copyWithLocalFeedback} onOriginal={()=>showTaskLayer('overview')} onFollowUp={followOriginal}/>;
  const progress=getRecentTaskProgress(task);
  const stage=workCollectionPresentation(task).group;
  const actionNames=asArray<{action:string;label:string}>(review?.actions).map(x=>x.action),approved=asArray<Json>(review?.approvals),unresolved=asArray<Json>(review?.unresolvedOperations);
  const protectedDisabled=taskOpening||reviewPending||reviewStale||reviewSubmitted;
  const scope=obj(review?.approvalExecutionScope||review?.task?.raw?.executionScope),currentScope=obj(task.raw?.executionScope);
  const project=projectContinuation(review),continuation=continuations[task.id];
  const canReadProject=privateProjectTask(task)&&['paused','interrupted','awaiting_user','needs_confirmation','failed','completed'].includes(task.status);
  const projectBlocked=protectedDisabled||continuationBlocks(continuation);
  const projectNote=projectFeedback[task.id]||'';
  const projectControls=project&&<section aria-label="原项目续接范围">
   {review?.task&&!reviewStale&&<ContinuedProjectBinding key={review.reviewHandle} task={review.task} projects={projects} read={can('projectRuntime.read')?args=>bridge.command('projectRuntime.read',args):undefined}/>}
   <p>继续同一项目任务会立即开始原任务工作，不会自动发送对话草稿。</p>
   <p className="pw-muted">工具调用已用 {String(scope.toolsUsed??'未读取')} / 总上限 {String(scope.toolLimit??'未读取')}；模型调用已用 {String(scope.modelCallsUsed??'未读取')} / 总上限 {String(scope.modelCallLimit??'未读取')}。本次不增加额度，不延长截止时间。</p>
   <p className="pw-muted">{scope.deadlineAt===0?'沿用原任务未设置截止时间的范围，仍受剩余预算约束。':typeof scope.deadlineAt==='number'?`原截止时间：${new Date(scope.deadlineAt).toLocaleString('zh-CN')}。`:'原任务期限尚未读取。'}{scope.executionPhase==='planning'?' 原任务仍处于只读规划，继续不会批准具体执行。':''}</p>
   {project.reason&&<p role="status" className="pw-muted">{project.reason}</p>}
   <TextAreaField label="本次修改要求" value={projectNote} onChange={value=>setProjectFeedback(previous=>({...previous,[task.id]:value}))} placeholder="看过原结果后，希望继续修改什么？" hint="必填，最多 8000 字。对话中的文字、引用和附件保持原样。" isRequired maxLength={8000} showCount isDisabled={projectBlocked||!actionNames.includes('tasks.resume')}/>
   {actionNames.includes('tasks.resume')&&actionNames.includes('tasks.confirm')&&<Button variant="secondary" disabled={projectBlocked||!projectNote.trim()||projectNote.trim().length>8000} onClick={()=>reviewedAction('tasks.resume')}>按本次反馈继续项目任务</Button>}
  </section>;
  const scopeEditor=<div className="pw-scope-editor">
   <section aria-label="原任务执行阶段"><h3>执行阶段</h3><p>{scope.executionPhase==='planning'?'只读规划：可读取资料并提交计划，具体执行须先确认准确版本。':'按原任务已有范围执行。'}</p>
    {scope.executionPhase==='planning'&&<p className="pw-muted">{Number(scope.planRevision)>0?`当前计划版本 ${String(scope.planRevision)}；请在原任务授权中核对完整步骤。`:'当前还没有提交计划正文。'} 关闭页面或重新打开不会自动退出规划。</p>}
    {actionNames.includes('tasks.plan.enter')&&<><p className="pw-muted">先保存只读规划阶段，不启动任务，也不增加调用额度、延长截止时间或授权新工具。</p><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.plan.enter')}>进入只读规划</Button></>}
   </section>
   {actionNames.includes('tasks.scope')?<><h3>原任务预算与截止</h3><TextField label="工具调用总上限" inputMode="numeric" pattern="[0-9]*" hint="1–400" value={scopeTools} onChange={setScopeTools} isDisabled={protectedDisabled}/><FluidValueSlider label="拖动设置工具调用上限" value={/^\d+$/.test(scopeTools)?Number(scopeTools):NaN} onChange={value=>setScopeTools(String(value))} minValue={1} maxValue={400} disabled={protectedDisabled} unit="次"/><TextField label="模型调用总上限" inputMode="numeric" pattern="[0-9]*" hint="1–512" value={scopeModels} onChange={setScopeModels} isDisabled={protectedDisabled}/><FluidValueSlider label="拖动设置模型调用上限" value={/^\d+$/.test(scopeModels)?Number(scopeModels):NaN} onChange={value=>setScopeModels(String(value))} minValue={1} maxValue={512} disabled={protectedDisabled} unit="次"/><SelectField label="任务截止" value={scopeMinutes} onChange={setScopeMinutes} options={[{id:'-1',label:'保留原截止时间'},{id:'0',label:'不设置截止时间'},{id:'30',label:'从本次确认起30分钟'},{id:'120',label:'从本次确认起2小时'}]}/><p className="pw-muted">这是新的总上限，不是追加次数。修订范围会使旧计划确认失效。</p></>:<p>这次任务当前不能修订预算与截止时间。</p>}
  </div>;
  const readingReview=taskOpening||reviewPending;
  const approvalRead=obj(review?.capabilityApprovalRead),approvalIds=Array.isArray(approvalRead.pendingApprovalIds)?approvalRead.pendingApprovalIds.filter((id:unknown):id is string=>typeof id==='string'):[];
  const approvalNavigation=approvalIds.length>1&&<SelectField label="待处理操作" value={String(review?.capabilityApprovalRead?.approvalId||'')} onChange={id=>loadTaskReview(task.id,id)} options={approvalIds.map((id:string,index:number)=>({id,label:`第 ${index+1} 项（共 ${approvalIds.length} 项）`}))} isDisabled={protectedDisabled} hint="逐项查看完整内容，每项操作分别确认。"/>;
  const phoneUnknown=unresolved.filter(op=>op.format===PHONE_RECORD),ordinaryUnknown=unresolved.filter(op=>op.format!==PHONE_RECORD);
  const hasDecision=!!review?.ownerQuestion||!!project||actionNames.some(action=>action!=='tasks.scope'&&action!=='tasks.plan.enter'&&(action!=='tasks.resolve'||ordinaryUnknown.some(op=>!!op.operationId)));
  const ordinaryResume=!project&&!review?.ownerQuestion&&!approved.length&&!unresolved.length&&actionNames.includes('tasks.resume')&&actionNames.every(action=>['tasks.resume','tasks.scope','tasks.plan.enter','tasks.deleteScreenshot'].includes(action));
  const resumeFeedback=<Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={reviewFeedbackOpen?['resume-feedback']:[]} onExpandedChange={keys=>setReviewFeedbackOpen(keys.has('resume-feedback'))}><AccordionItem id="resume-feedback" title="补充说明（可选）" description={reviewNote?'已填写':undefined}><TextAreaField label="本次补充" value={reviewNote} onChange={setReviewNote} placeholder="需要调整的内容" isDisabled={protectedDisabled}/></AccordionItem></Accordion>;
  const inspectResult=stage==='ended'||actionNames.includes('tasks.confirm');
  const decisionFirst=stage==='attention'&&!inspectResult;
  const overviewActive=taskOpen&&!taskOpening&&openedTaskId.current===task.id&&taskLayer==='overview';
  const taskFiles=obj(snapshot?.view).taskId===task.id&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&task.raw?.contextAvailable!==false&&task.raw?.taskProjection!=='source_unavailable'&&task.raw?.kind!=='inference'&&can('attachmentWorkspace.open')
   ? {taskId:task.id,supported:can('tasks.resultFiles'),onOpen:(file:TaskResultFile,target:HTMLButtonElement)=>openTaskResultFile(task.id,file,target),
      onOriginalRequest: privateTaskConversationId(task) && can('conversation.select') && !state.chatSubmitting && !returningConversation ? () => void returnToTaskConversation(task) : undefined,
      onBrowse:()=>run('attachmentWorkspace.open',{taskId:task.id})}
   : undefined;
  const latestProgress=stage==='running'&&running(task)&&<section key="task-current-progress" className="pw-work-current-progress">
   <h3>目前进展</h3>
   <BlurCrossfade identity={`${task.id}:${progress.operationId||progress.position||progress.title}`}>
    <strong>{progress.source==='task'&&progress.status!=='unknown'?'最近确认的状态':progress.title}</strong>
    <p dir="auto">{progress.detail||(progress.source==='task'?progress.title:progress.stateLabel)}</p>
   </BlurCrossfade>
  </section>;
  const evidence=<TaskOutcome key="task-evidence" task={review&&!reviewStale?review.task:task} messages={messages} resultFiles={taskFiles}
   active={overviewActive} showEmptyResult={inspectResult&&task.status==='completed'} deferExecutionDetails={!inspectResult&&task.status!=='failed'}
   canReadReport={can('ownerWork.report.read')} readIdentity={`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}:${navigationRevision}`}
   onCopy={copyWithLocalFeedback} onFollowUp={followOriginal} onOpenProjectPackage={!packageOpening&&!taskOpening&&can('projects.package.preview.inspect')&&can('projects.package.preview.open')&&can('tasks.close')?openTaskPackage:undefined}
   onOpenAttachment={can('attachments.open')?(messageId,id,sha256)=>run('attachments.open',{messageId,id,sha256}):undefined}/>;
  const workFacts=<OwnerWorkFacts key="task-handling" task={task} onTeam={can('teams.readRun')?openWorkTeam:undefined}
   onReport={can('conversation.select')?()=>void returnToTaskConversation(task):undefined}
   onReconcile={overviewActive&&can('tasks.open')?target=>openTask(target.taskId,'process',false,undefined,undefined,target):undefined}/>;
  const refreshControl=can('tasks.review')&&<IconButton icon={IconRefresh} aria-label={review?'重新核对':'读取可用操作'} title={review?'重新核对':'读取可用操作'} disabled={readingReview||questionPending} onClick={showTaskReview}/>;
  const reviewStatus=<>{readingReview&&<ThinkingLoader phase="正在读取当前任务"/>}{reviewStale&&<p className="pw-error">任务已有新进展，请重新核对后再操作。</p>}{continuation&&<section aria-label="原项目续接回执"><p className="pw-muted" role="status">{continuation.message}</p>{!project&&projectNote&&<Accordion variant="divided"><AccordionItem id="continuation-feedback" title="本次反馈"><p className="pw-approval-copy">{projectNote}</p></AccordionItem></Accordion>}{continuation.status==='unknown'&&can('tasks.review')&&<Button variant="secondary" disabled={readingReview} onClick={showTaskReview}>核对原任务状态</Button>}{['continued','unknown'].includes(continuation.status)&&can('conversation.select')&&<Button variant="ghost" disabled={!!state.chatSubmitting} onClick={()=>selectConversation(continuation.original.conversationId)}>返回原对话，草稿保留</Button>}</section>}{!continuation&&(reviewSubmitted||reviewReceipt)&&!review?.ownerQuestion&&(reviewReceipt?<p className="pw-muted" role="status">{reviewReceipt}</p>:state.taskActionErrors?.[task.id]?<p className="pw-error" role="alert">本次提交未确认。请先重新核对原任务，再决定是否操作。</p>:<p className="pw-muted" role="status">已提交这次决定，等待任务回执。</p>)}</>;
  const decision=(review||reviewPending||continuation||(canReadProject||attention(task))&&can('tasks.review'))&&<div key="task-decision" className="pw-task-decision">
      {taskLayer==='overview'&&reviewStatus}
      {!readingReview&&(!review||reviewStale&&!review.ownerQuestion||ordinaryResume&&(reviewSubmitted||!!reviewReceipt||!!state.taskActionErrors?.[task.id]))&&<div className="pw-section-heading pw-task-review-tools">{canReadProject&&!continuationBlocks(continuation)?<Button variant="secondary" onClick={showTaskReview}>核对继续原项目任务</Button>:<span className="pw-grow"/>}{refreshControl}</div>}
      {review&&!reviewStale&&phoneUnknown.length>0&&<PhoneOriginalActionReview key={review.reviewHandle} taskId={task.id} rows={phoneUnknown} more={task.raw?.privatePhoneUnconfirmed?.hasMore===true} disabled={protectedDisabled} allowRecord={actionNames.includes('tasks.resolve')} submit={args=>reviewedAction('tasks.resolve',args)}/>}
      {review&&(!reviewStale||!!review.ownerQuestion)&&(ordinaryResume?resumeFeedback:hasDecision?<PermissionReview title={project?'继续原项目任务':actionNames.includes('tasks.confirm')?'这份结果是否达到目标？':'处理这次任务'} headerAction={refreshControl}><div className="pw-protected-actions">{projectControls}{review.ownerQuestion&&<OwnerTaskQuestion key={review.reviewHandle} question={review.ownerQuestion as OwnerQuestionView} text={questionAnswer} onTextChange={setQuestionAnswer} disabled={protectedDisabled} canAnswer={actionNames.includes('tasks.answerQuestion')} canChoose={actionNames.includes('tasks.answerQuestionChoice')} submit={submitQuestion}/>}{actionNames.includes('tasks.confirm')&&<Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={reviewFeedbackOpen?['result-feedback']:[]} onExpandedChange={keys=>setReviewFeedbackOpen(keys.has('result-feedback'))}><AccordionItem id="result-feedback" title="补充说明（可选）" description={reviewNote?'已填写':undefined}><TextAreaField label="本次结果反馈" value={reviewNote} onChange={setReviewNote} placeholder="已完成或仍需调整的内容"/></AccordionItem></Accordion>}{actionNames.includes('tasks.approve')&&approvalNavigation}{actionNames.includes('tasks.approve')&&approved.map(p=><TaskApproval key={`${review.reviewHandle}:${p.approvalId||p.id}`} approval={p} executionScope={scope} disabled={protectedDisabled} onDecision={args=>reviewedAction('tasks.approve',args)}/>)}{actionNames.includes('tasks.resolve')&&ordinaryUnknown.filter(op=>!!op.operationId).map(op=><section className="pw-approval-item" key={op.operationId}><h4>{op.title||op.toolName||'动作结果核对'}</h4><p>{op.summary||op.message||'请依据实际观察核对这次动作是否已经发生。'}</p><div className="pw-action-row"><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.resolve',{operationId:op.operationId,outcome:'occurred'})}>确认已发生</Button><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.resolve',{operationId:op.operationId,outcome:'not_occurred'})}>确认未发生</Button></div></section>)}{!project&&actionNames.includes('tasks.resume')&&<TextAreaField label="本次补充" value={reviewNote} onChange={setReviewNote} placeholder="继续前需要补充什么？"/>}{!review.actions?.length&&<p className="pw-muted">当前没有待作出的新决定。</p>}</div></PermissionReview>:phoneUnknown.length?null:<div className="pw-section-heading pw-task-review-tools"><p className="pw-muted">当前没有待作出的新决定。</p>{refreshControl}</div>)}
     </div>;
  return <Tabs className="pw-work-object-tabs" selectedKey={taskLayer==='scope'?'goal':taskLayer} onSelectionChange={key=>showTaskLayer(String(key) as TaskLayer)}>
   <TabList aria-label="查看这件工作"><Tab id="overview">概览</Tab><Tab id="goal">目标</Tab><Tab id="process">过程</Tab></TabList>
   {/* Keep the result document mounted while reading deeper task details. */}
   <TabPanel id="overview" shouldForceMount hidden={taskLayer!=='overview'}>
    <section className="pw-task-overview pw-task-review" aria-label="这件工作的进展、决定与成果">
     {overviewActive&&<WorkExecutionPlace task={task} projects={projects}/>}
     {taskOpen&&!taskOpening&&openedTaskId.current===task.id&&<PhoneMainOwnerStep key={'main-owner-step:'+task.id} task={task} visible={overviewActive&&!phoneViewerObscured&&!phoneViewerExpanded&&obj(snapshot?.view).taskId===task.id} run={act} can={can} onProcess={()=>showTaskLayer('process')}/>}
     {inspectResult?[evidence,decision,workFacts]:decisionFirst?[mainPhoneStep(task)&&!approved.length&&!unresolved.length&&!review?.ownerQuestion?null:decision,workFacts,evidence]:[latestProgress,workFacts,evidence,decision]}
     {taskLayer==='overview'&&ownerWorkActions(task)}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&task.raw?.hasOwnerWorkPreparations&&<ButlerPreparationEntries key={'preparations-task:'+task.id} sourceTaskId={task.id} conversationId={privateTaskConversationId(task)||''} active={taskLayer==='overview'&&!preparationTarget} refreshKey={String(task.raw?.ownerWorkPreparationCount||0)+':'+preparationRevision} can={can} run={bridge.command} onOpen={setPreparationTarget}/>}
     {task.raw?.ownerWorkPreparation?.sourceTaskId&&task.raw?.ownerWorkPreparation?.proposalId&&task.raw?.ownerWorkPreparation?.sourceConversationId&&<Button variant="ghost" leadingIcon={IconChat} onClick={()=>setPreparationTarget({sourceTaskId:task.raw!.ownerWorkPreparation.sourceTaskId,proposalId:task.raw!.ownerWorkPreparation.proposalId,sourceConversationId:task.raw!.ownerWorkPreparation.sourceConversationId})}>查看管家的原工作准备</Button>}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&workSupplementTarget?.taskId===task.id&&can('ownerWork.supplement.read')&&<OwnerWorkSupplement key={workIdentityKey(workSupplementTarget)} target={workSupplementTarget} task={task} can={can} registerBeforeLeave={registerWorkSupplementBeforeLeave} onClose={()=>setWorkSupplementTarget(null)} onSelectOriginal={identity=>void targetWork(task,identity)}/>}
     {workSupplementTarget?.taskId!==task.id&&pendingInputs.some(item=>String(item.draftKey).startsWith('work:')&&item.originalTarget?.taskId===task.id)&&<div className="pw-work-pending-links" aria-label="原工作补充">{pendingInputs.filter(item=>String(item.draftKey).startsWith('work:')&&item.originalTarget?.taskId===task.id).map(item=><Button key={item.clientMessageId} variant="secondary" size="small" onClick={()=>returnToWorkInput(item)}>核对原工作中的补充</Button>)}</div>}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&task.raw?.ownerDeviceWork&&can('deviceTaskScope.open')&&<DeviceTaskScopeWorkspace key={'device:'+task.id} taskId={task.id} run={act} directed={task.raw.ownerDeviceWork.directPhone} autoOpen={!task.raw.ownerDeviceWork.directPhone&&!task.raw?.runId&&!asArray(task.raw?.deviceSourceScope?.routes).length} onBackChange={registerDeviceScopeBack} onSaved={()=>loadTaskReview(task.id)} onOpenDevices={()=>void openRelatedWorkspace('devices')}/>}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&task.raw?.ownerPhoneControl?.format==='pocketworld.owner-phone-control.v1'&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&<>
      {!task.raw?.runId&&can('deviceTaskScope.open')&&<DeviceTaskScopeWorkspace key={'manual-scope:'+task.id} taskId={task.id} run={act} autoOpen={!asArray(task.raw?.deviceSourceScope?.routes).length} onBackChange={registerDeviceScopeBack} onSaved={()=>loadTaskReview(task.id)} onOpenDevices={()=>void openRelatedWorkspace('devices')}/>}
      {can('phoneControl.open')&&<ConversationModelPickerVisibility key={'manual:'+task.id}>{pickerVisible=><PhoneControlWorkspace taskId={task.id} hasScope={asArray(task.raw?.deviceSourceScope?.routes).length===1} started={!!task.raw?.runId} visible={taskLayer==='overview'&&!pickerVisible&&!phoneViewerObscured} run={act} onChanged={()=>loadTaskReview(task.id)}/>}</ConversationModelPickerVisibility>}
     </>}
     {taskOpen && openedTaskId.current===task.id && !taskOpening && isPrivateLocalPhone(task) && can('phone.pauseNote.read') && can('phone.pauseNote.continue') &&
       <PhonePauseInstruction key={'phone-pause-note:'+task.id} task={task} visible={taskLayer==='overview' && !phoneViewerObscured && !phoneViewerExpanded && obj(snapshot?.view).taskId===task.id} run={act}/>}
     {taskOpen&&phoneViewerFor(task)}
     {taskOpen&&task.raw?.feature==='remote'&&can('remoteTask.open')&&<RemoteTaskWorkspace key={task.id} taskId={task.id} raw={obj(task.raw)} receipt={task.hostReceipt} savedFiles={asArray<Json>(task.raw?.remoteSnapshotAttachments)}/>}
     {taskOpen&&taskLayer==='overview'&&task.raw?.developmentContext&&can('projectLive.open')&&<ConversationModelPickerVisibility key={'live:'+task.id}>{pickerVisible=><ProjectLivePreview taskId={task.id} run={act} nativeVisible={!pickerVisible&&!phoneViewerObscured} onBackChange={registerLivePreviewBack}/>}</ConversationModelPickerVisibility>}
     {currentScope.executionPhase==='planning'&&<p className="pw-muted" role="status">原任务处于只读规划。{Number(currentScope.planRevision)>0?`当前计划版本 ${String(currentScope.planRevision)}，确认准确计划后才能在原范围内继续。`:'尚未提交计划正文，重新打开页面不会启动执行。'}</p>}

     {openedTaskId.current===task.id&&!taskOpening&&task.raw?.kind==='assistant'&&can('conversation.drafts')&&<ConversationDrafts key={'drafts:'+task.id} taskId={task.id} reviewingMemory={memoryDraftTarget?.taskId===task.id} refreshRevision={Number(state.memoryRefreshRevision||0)} open={openConversationDraft}/>}

     <Accordion variant="divided" className="pw-task-disclosures" aria-label="更多任务资料">
      <AccordionItem id={`${task.id}:details`} title="过程与范围">
     <div className="pw-task-entry-list">
      <Button variant="ghost" leadingIcon={IconTool} onClick={()=>showTaskLayer('process')}>查看执行轨迹<IconChevronRight size={17}/></Button>
      {can('tasks.review')&&<Button variant="ghost" leadingIcon={IconInfo} disabled={readingReview||questionPending} onClick={()=>{if(openedTaskId.current!==task.id){openTask(task.id,'scope');return}showTaskLayer('scope');if(!review||reviewStale)refreshReview()}}>{review&&!reviewStale&&actionNames.includes('tasks.scope')?'修订本次范围':'本次范围'}<IconChevronRight size={17}/></Button>}
     </div>
     {openedTaskId.current===task.id&&!taskOpening&&<>
      {taskLayer==='overview'&&!task.raw?.ownerDeviceWork&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&['assistant','phone'].includes(String(task.raw?.kind))&&!task.raw?.origin&&can('deviceTaskScope.open')&&<DeviceTaskScopeWorkspace key={'device:'+task.id} taskId={task.id} run={act} onBackChange={registerDeviceScopeBack} onSaved={()=>loadTaskReview(task.id)} onOpenDevices={()=>void openRelatedWorkspace('devices')}/>}
      {taskLayer==='overview'&&!task.raw?.ownerPhoneControl&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&can('taskCapabilities.open')&&<TaskCapabilityWorkspace key={'capabilities:'+task.id} taskId={task.id} run={act} exportState={state.taskCapabilityExport} onBackChange={registerCapabilityBack} registerBeforeLeave={registerTaskPolicyBeforeLeave} onOpenTools={next=>void openRelatedWorkspace(next||'capabilities')}/>}
      {taskLayer==='overview'&&task.raw?.ownerRuntimeSetup&&can('setup.runtime.read')&&<Button variant="secondary" onClick={()=>openSetup('tools',task.id)}>查看工具准备进度</Button>}
      {taskLayer==='overview'&&task.raw?.developmentContext&&can('projectRuntime.read')&&<ProjectRuntimeHealth key={'runtime:'+task.id} taskId={task.id} run={act} onPrepareTools={can('setup.runtime.read')?()=>openSetup('tools'):undefined}/>}
      {taskLayer==='overview'&&task.raw?.developmentContext&&can('local.execution.open')&&<ConversationModelPickerVisibility key={'execution:'+task.id}>{pickerVisible=><ProjectExecutionWorkspace task={task} active={taskOpen&&!pickerVisible&&!phoneViewerObscured} run={act}/>}</ConversationModelPickerVisibility>}
      {can('usage.task')&&<TaskInference key={task.id} taskId={task.id}/>}{can('tokenBudgets.open')&&<TokenBudgetsWorkspace key={'budget:'+task.id} taskId={task.id} supported={can} registerTransientBack={registerTaskBack}/>}
      {task.raw?.kind==='phone'&&!task.raw?.origin&&typeof task.raw.runId==='string'&&task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0&&can('routeReference.open')&&<Button variant="ghost" onClick={()=>setRouteReferenceTarget({taskId:task.id,runId:task.raw!.runId,epoch:task.raw!.coordinatorEpoch,namespace:'owner/private'})}>查看路线参考与分享</Button>}
      {task.raw?.evidence?.nativeFinalAnswer&&can('sources.open')&&<Button variant="ghost" onClick={()=>setSourceSelection({taskId:task.id})}>核对原回答来源</Button>}
     </>}
      </AccordionItem>
      {review&&(!reviewStale||!!review.ownerQuestion)&&actionNames.includes('tasks.deleteScreenshot')&&<AccordionItem id={`${task.id}:screenshot`} title="截图管理"><section className="pw-approval-item"><h4>删除这份任务截图</h4><p>只删除本次核对的结果截图；已导出的文件和项目成果仍保留。删除后不能恢复。</p><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.deleteScreenshot')}>确认删除这份截图</Button></section></AccordionItem>}
     </Accordion>
    </section>
   </TabPanel>
   {/* Keep settled audit pages and expanded records while returning to the result.
       The original task/run/epoch key still retires a different history window. */}
   <TabPanel id="process" shouldForceMount className="pw-task-process-layer" hidden={taskLayer!=='process'}><TaskHistory key={`${state.conversationId}:${taskHistoryIdentity(task)}`} task={task} canRead={can('history.read')} canReadTrace={can('trace.read')} active={taskLayer==='process' && taskOpen && !taskOpening && openedTaskId.current===task.id} registerTransientBack={registerTaskBack} renderOperation={renderOperation}/></TabPanel>
   <TabPanel id="goal" shouldForceMount hidden={taskLayer!=='goal'&&taskLayer!=='scope'}>
    <div hidden={taskLayer==='scope'} className="pw-work-goal-panel"><WorkGoal task={task}/>
     {phoneConversationId(task)&&can('phone.request.original')?<PhoneTaskOriginalRequest key={taskHistoryIdentity(task)} task={task} active={taskOpen&&!taskOpening&&taskLayer==='goal'&&openedTaskId.current===task.id&&obj(snapshot?.view).taskId===task.id}/>
      :privateTaskConversationId(task)&&can('conversation.select')&&<Button variant="ghost" leadingIcon={IconChat} disabled={!!state.chatSubmitting||!!returningConversation} onClick={()=>void returnToTaskConversation(task)}>{phoneConversationId(task)?'打开所属对话':'查看原请求与补充'}</Button>}
     {privateTaskConversationId(task)&&(task.raw?.executionRole==='owner_work'||task.raw?.nativeResultHandling===true||task.raw?.ownerWorkPreparation)&&task.raw?.runId&&Number.isSafeInteger(task.raw?.coordinatorEpoch)&&task.raw.coordinatorEpoch>0&&can('conversation.select')&&can('composer.object.add')&&<Button variant="ghost" disabled={!!state.chatSubmitting||!!returningConversation} onClick={()=>void returnToTaskConversation(task,true)}>问管家这件事</Button>}
     {can('tasks.review')&&<Button variant="secondary" leadingIcon={IconShieldCheck} disabled={readingReview||questionPending} onClick={()=>{showTaskLayer('scope');if(!review||reviewStale)refreshReview()}}>查看执行范围与预算</Button>}
    </div>
    {taskLayer==='scope'&&<section className="pw-task-review" aria-label="本次任务范围">
    {reviewStatus}
    {!readingReview&&<><div className="pw-section-heading pw-task-review-tools"><span className="pw-grow"/>{refreshControl}</div>{review&&!reviewStale&&scopeEditor}</>}
    </section>}
   </TabPanel>
  </Tabs>;
 };
 const ownerWorkActions=(task:Task)=>{
  const identity=ownerWorkIdentity(task);if(!identity)return null;
  const control=workControls[workIdentityKey(identity)],controls=obj(task.raw?.controls);
  const supplement=identity.epoch>0&&can('ownerWork.supplement.read'),continuing=controls.canContinue===true&&can('ownerWork.control');
  const note=controls.canContinue===true&&!can('ownerWork.control')?'这个窗口目前不能提交续接，仍可查看原工作。':ownerWorkContinuationNote(task);
  if(!supplement&&!continuing&&!note&&controls.resultCapacityState!=='full')return null;
  return <section className="pw-owner-work-actions" aria-label="继续这件工作">
   {note&&!control?.pending&&!control?.unknown&&!control?.usedForContinuation&&<p>{note}</p>}
   {!note&&controls.resultCapacityState==='full'&&<p role="status">这件事的结果留存已满，暂不能继续执行。已有结果未因容量限制而删除；能否查看仍取决于原任务来源是否可用。容量限制不代表正在执行的任务已经停止。</p>}
   {continuing&&<Button variant="secondary" disabled={taskOpening||control?.pending||control?.unknown||control?.usedForContinuation} onClick={()=>controlWork(task,'continue')}>在原任务开新一轮</Button>}
   {supplement&&<Button variant={continuing?'ghost':'secondary'} disabled={taskOpening||!!state.chatSubmitting} onClick={()=>{void targetWork(task)}}>{controls.canInput===true?'补充这件工作':'查看补充草稿'}</Button>}
  </section>;
 };
 const taskFooter=(task:Task)=>{
  if(taskLayer==='process'||taskLayer==='homeReport'||openedTaskId.current!==task.id||!review||reviewStale)return null;
  const actions=asArray<{action:string}>(review.actions).map(x=>x.action),disabled=taskOpening||reviewSubmitted||reviewPending;
  if(taskLayer==='scope'&&actions.includes('tasks.scope'))return <footer className="pw-task-footer"><PressButton label="确认本次范围修订" disabled={disabled||!/^\d+$/.test(scopeTools)||!/^\d+$/.test(scopeModels)||Number(scopeTools)<1||Number(scopeTools)>400||Number(scopeModels)<1||Number(scopeModels)>512} onClick={()=>reviewedAction('tasks.scope',{minutes:Number(scopeMinutes),tools:Number(scopeTools),models:Number(scopeModels)})}/></footer>;
  if(taskLayer==='overview'&&actions.includes('tasks.confirm'))return <footer className="pw-task-footer"><Button variant="secondary" disabled={disabled} onClick={()=>reviewedAction('tasks.confirm',{completed:false,feedback:reviewNote})}>目标尚未达到</Button><PressButton label="确认达到目标" disabled={disabled} onClick={()=>reviewedAction('tasks.confirm',{completed:true,feedback:reviewNote})}/></footer>;
   if(taskLayer==='overview'&&actions.includes('tasks.resume')){const project=projectContinuation(review),note=projectFeedback[task.id]||'';return <footer className="pw-task-footer"><PressButton label={project?'在原范围内继续项目任务':task.raw?.ownerDeviceWork&&!task.raw?.runId?'开始这项设备工作':'继续原任务'} disabled={disabled||!!project&&(continuationBlocks(continuations[task.id])||!note.trim()||note.trim().length>8000)} onClick={()=>reviewedAction('tasks.resume',{confirmed:true,feedback:reviewNote})}/></footer>;}
  return null;
 };
 const selectedWorkIdentity=ownerWorkIdentity(selectedTask);
 const selectedWorkControl=selectedWorkIdentity?workControls[workIdentityKey(selectedWorkIdentity)]:undefined;
 const selectedLocalPhone=!!selectedTask&&isPrivateLocalPhone(selectedTask);
 const selectedPhoneSurface=privatePhoneSurface(selectedTask,state.livePrivatePhones);
 const selectedPhoneStop=selectedTask&&!terminal(selectedTask)&&can('phone.stop')&&(selectedLocalPhone?!!selectedPhoneSurface:selectedTask.id===activeTask?.id)?phoneRunIdentity(selectedTask):null;
 const selectedTaskStop=selectedTask&&(selectedPhoneStop||(selectedWorkIdentity
  ?selectedTask.raw?.controls?.canStop===true&&can('ownerWork.control')
  :!selectedLocalPhone&&selectedTask.raw?.executionRole!=='owner_work'&&!terminal(selectedTask)&&can('tasks.stop')));
 const selectedWorkPresentation=selectedTask?workCollectionPresentation(selectedTask):null;
 const selectedTaskUnavailable=taskOpening||!selectedTask||openedTaskId.current!==selectedTask.id;
 const stopSelectedTask=()=>{
  if(!selectedTask||selectedTaskUnavailable||!selectedTaskStop||selectedWorkControl?.pending||selectedWorkControl?.unknown||selectedWorkControl?.usedForContinuation||selectedTask.status==='stopping')return;
  if(selectedPhoneStop)triggerPhoneControl('phone.stop',selectedPhoneStop,selectedLocalPhone);
  else if(selectedWorkIdentity)controlWork(selectedTask,'cancel');
  else run('tasks.stop',{id:selectedTask.id,runId:selectedTask.raw?.runId??'',epoch:selectedTask.raw?.coordinatorEpoch});
 };
 const composerHasChatStop=isChatSurface&&intent==='chat'&&!!state.chatBusy&&!!currentCancelIdentity&&can('chat.cancel');
 const conversationActivity=conversationActivityLabel(state);
 const openTurnNotes=(original:WorkIdentity,layer:'overview'|'review'|'process')=>
  openTask(original.taskId,layer==='review'&&!can('tasks.review')?'overview':layer,false,undefined,undefined,{runId:original.runId,epoch:original.epoch});
 const editingChat=!!text||asArray(state.selectedChatAttachments).length>0||
  [composer.objectRefs,composer.sourceRefs,composer.previewRefs].some(value=>asArray(value).length>0);
 const stageExample=intent==='chat'&&!!draftKey&&!isListening&&!voiceReview&&!state.voiceInput?.reviewAvailable&&!composer.voiceText&&
  !editingChat&&!pendingHere&&!registration.waiting&&!state.chatSubmitting&&can('composer.edit')?(draft:string)=>{
   const editor=workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)');
   if(!editor||editor.value)return;
   editText(draft);
   editor.focus({preventScroll:true});
  }:undefined;
 const recentWork=orderConversations(conversations as (Json & {id:string})[]);
 // Bound read-only permission summary; grants and approvals remain native task operations.
 const permissionView=composerPermissionView(state);
 const permissionReviewLabel=permissionView&&['waiting_approval','awaiting_approval','waiting_permission'].includes(permissionView.status)?'审阅本次授权':'查看待处理事项';
 const openPermissionTask=()=>{if(permissionWriting.current||!permissionView||!can('tasks.open'))return;const selected=permissionView;const open=()=>{setPermissionOptionsOpen(false);openTask(selected.taskId,selected.needsReview&&can('tasks.review')?'review':'scope')};if(!permissionBeforeLeave.current?.(open))open()};
 const permissionTaskDetails=<Accordion className="pw-composer-permission-detail"><AccordionItem title="本次任务范围">
    {permissionView?<><strong className="pw-composer-permission-goal">{permissionView.goal}</strong><p>{labelStatus(permissionView.status)}{permissionView.phase==='planning'?' · 只读规划':''}</p>
     {permissionView.scopeVerified?<dl><div><dt>工具次数</dt><dd>{permissionView.toolsUsed??'未核对'} / {permissionView.toolLimit??'未核对'}</dd></div><div><dt>模型调用</dt><dd>{permissionView.modelCallsUsed??'未核对'} / {permissionView.modelCallLimit??'未核对'}</dd></div><div><dt>截止时间</dt><dd>{permissionView.deadlineAt===0?'未设置':permissionView.deadlineAt===null?'未核对':new Date(permissionView.deadlineAt).toLocaleString('zh-CN')}</dd></div></dl>:<p>尚无可核对的任务执行范围。</p>}
     <p className="pw-composer-permission-note">审批设置不会扩大此任务的范围、资源许可或预算。</p>
     {!permissionView.needsReview&&can('tasks.open')&&<Button variant="ghost" disabled={permissionPending} onClick={openPermissionTask}>查看原任务权限</Button>}
    </>:<p>当前对话还没有任务。任务开始后可在这里查看执行范围。</p>}
   </AccordionItem></Accordion>;
 // Only surface a decision that belongs to a task anchored in this conversation.
 const conversationAttention=intent==='chat'?tasks.reduce<Task|undefined>((latest,task)=>{const anchor=messageTaskAnchors.get(task.id);if(!conversationInlineAttention(task)||anchor===undefined)return latest;return !latest||anchor>messageTaskAnchors.get(latest.id)!?task:latest;},undefined):undefined;
 const navigationTrigger=desktopNavigation?<span className="pw-header-spacer" aria-hidden="true"/>:<Drawer.Trigger asChild><IconButton ref={isPrimaryRoute?navigationMenuGlass:undefined} icon={IconMenu} className={isChatSurface?'pw-chat-glass-trigger':undefined} aria-label="打开导航" data-testid="all_work" onClick={event=>{navigationReturnFocus.current=event.currentTarget}}/></Drawer.Trigger>;
 const openUnreadMessages=()=>{void openNotifications(()=>{notificationMemory.current.externalUnreadRequest=(notificationMemory.current.externalUnreadRequest||0)+1})};
 const navigationContent=<WorkspaceNavigator items={workNav} selected={navigationSelection} recent={recentWork}
  selectedConversation={route==='chat'?String(state.conversationId||''):undefined}
  desktop={desktopNavigation} compact={effectiveCompact} onToggleCompact={()=>setNavigationCompact(value=>!value)}
  directoryOpen={navigationMoreOpen} setDirectoryOpen={setNavigationMoreOpen} searchOpen={commandOpen} openSearch={showWorkSearch}
  closeSearch={()=>setCommandOpen(false)} searchTrigger={workSearch} memory={ownerNavMemory.current} navigate={selectNavigation}
  search={<WorkSearch key="work-search" supported={can('work.search')} identity={`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}`}
   memory={workSearchMemory.current} onOpen={openSearchResult} labelStatus={labelStatus} onClose={()=>setCommandOpen(false)}/>}
  openConversation={can('conversation.select')?selectConversation:undefined}
  organizeConversation={can('conversation.organization.read')?setOrganizationTarget:undefined}
  newConversation={can('conversation.new')?()=>{void navigate('butler',()=>run('conversation.new'))}:undefined} newPending={!!state.chatSubmitting}
  profileName={personalName} avatar={personalAvatar||undefined} canProfile={can('profile.snapshot')} openUnread={openUnreadMessages}/>;
 const allWork=!desktopNavigation&&<Drawer.Portal>
  <Drawer.Overlay className="pw-overlay pw-navigation-overlay"/>
  <Drawer.Content ref={attachNavigationDrawer} className="pw-drawer pw-work-drawer pw-navigation-drawer pw-material-navigation" aria-describedby="pw-work-description" onEscapeKeyDown={event=>{if(navigationMoreOpen||commandOpen){event.preventDefault();if(event.isComposing||event.keyCode===229)return;if(navigationMoreOpen)setNavigationMoreOpen(false);else setCommandOpen(false)}}} onCloseAutoFocus={event=>{const target=navigationReturnFocus.current?.isConnected?navigationReturnFocus.current:chatMenuTrigger.current;if(taskOpen)event.preventDefault();else if(route==='chat'&&target){event.preventDefault();target.focus()}navigationReturnFocus.current=null}}>
   {navigationContent}
  </Drawer.Content>
 </Drawer.Portal>;
 // A private conversation shows only its own retained local phone runs. Home can hold up to the native four independent screens.
 const phoneRows=tasks.filter(task=>!terminal(task)&&(route==='butler'||route==='chat'&&!!state.conversationId&&phoneConversationId(task)===state.conversationId))
   .map(task=>({task,surface:privatePhoneSurface(task,state.livePrivatePhones)}))
   .filter(row=>row.surface!==null||!isPrivateLocalPhone(row.task)&&row.task.id===activeTask?.id);
 const phoneActivity=phoneRows.length?<>{phoneRows.map(({task,surface})=><PhoneTaskStatus key={JSON.stringify([task.id,task.raw?.runId,task.raw?.coordinatorEpoch])} task={task} surface={surface||undefined} can={can}
    onOpen={()=>openTask(task.id,attention(task)&&can('tasks.review')?'review':'overview')}
    onControl={(action,identity)=>triggerPhoneControl(action,identity,!!surface)}/>)}</>:null;
 const representedPhoneTaskIds=new Set(phoneRows.map(row=>row.task.id));
 const chatAttention=conversationAttention&&!representedPhoneTaskIds.has(conversationAttention.id)?conversationAttention:undefined;
 const conversationWorkRows=conversationWork.filter(task=>!representedPhoneTaskIds.has(task.id)&&task.id!==chatAttention?.id);
 const ownerHomeRows=asArray<Task>(state.ownerWork).filter(task=>isButlerHomeWork(task));
 const ownerHomeIds=new Set(ownerHomeRows.map(task=>task.id));
 const butlerWorkRows=[...ownerHomeRows,...tasks.filter(task=>!ownerHomeIds.has(task.id)&&homeWorkIdentity(task))]
  .filter(task=>!representedPhoneTaskIds.has(task.id));
 const conversationWorkIds=new Set(conversationWorkRows.map(task=>task.id));
 const awayFromConversation=route!=='chat'&&route!=='butler';
 const returnToForegroundReply=()=>{
  const conversationId=foregroundTurn?.conversationId;
  if(typeof conversationId!=='string'||!conversationId)return;
  void navigate('chat',undefined,{stillCurrent:()=>!document.hidden&&bridge.getState().state.conversationId===conversationId});
 };
 const replyStop=!composerHasChatStop&&currentCancelIdentity&&can('chat.cancel')&&
  <Button variant="ghost" size="small" onClick={stopOriginalChat}>停止回复</Button>;
 const replyActivity=foregroundTurn&&(awayFromConversation||route==='chat'&&!composerHasChatStop)?
  <div className={`pw-reply-activity-bar${awayFromConversation?' pw-reply-handoff':''}`}>
   <span className="pw-reply-handoff-copy">
    {awayFromConversation&&<strong dir="auto">{currentConversation?.title||'当前对话'}</strong>}
    <span role="status" dir="auto">{conversationActivity}</span>
   </span>
   <div className="pw-reply-handoff-actions">
    {awayFromConversation&&<Button variant="ghost" size="small" onClick={returnToForegroundReply}>回到原对话</Button>}
    {replyStop}
   </div>
  </div>:null;
 const modelSetupAction=can('setup.read')
  ? <Button variant="secondary" size="small" data-workspace-entry="connect-model" disabled={modelSetupOpening} onClick={()=>void openModelSetup()}>{modelSetupOpening?'正在读取接入状态…':'连接模型'}</Button>
  : <ConversationModelStartButton/>;
 const prepareCloudGoal=async()=>{
  const goal=chatDraft.value.trim(), native=bridge.getState();
  if(intent!=='chat'||document.hidden||!goal||goal.length>8000||!can('remote.reviewTask')||native.state.chatSubmitting||native.state.phoneSubmitting)return;
  const original=native.composer, documentEpoch=bridge.getDocumentEpoch();
  if(original?.ready!==true||typeof original.key!=='string'||!original.key)return;
  const isCurrent=()=>{const next=bridge.getState().composer;return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&next?.key===original.key&&next?.epoch===original.epoch&&chatDraft.currentValue()===goal;};
  const carriesTextOnly=asArray<Json>(native.state.selectedChatAttachments).length>0||['sourceRefs','objectRefs','previewRefs'].some(key=>asArray<Json>(obj(original)[key]).length>0);
  const from=navigationWindow.current, origin=captureWorkspaceOrigin();
  const source=can('conversation.captureHostGoal')?await bridge.command<Json>('conversation.captureHostGoal',{key:original.key,epoch:original.epoch,goal}).catch(()=>null):null;
  if(from!==navigationWindow.current||!isCurrent())return;
  const originHandle=source?.linked===true&&source.executionAuthorized===false&&source.networkUsed===false&&typeof source.originHandle==='string'&&/^[a-f0-9-]{36}$/.test(source.originHandle)?source.originHandle:undefined;
  void openRelatedWorkspace('remote',origin,()=>{if(isCurrent())setCloudGoal({id:crypto.randomUUID(),text:goal,carriesTextOnly,originHandle,navigation:navigationWindow.current});});
 };
 const composerRegion=<div className="pw-composer-region">
   {route==='butler'&&ready&&(messages.length>0&&!!state.conversationId||!!foregroundTurn||pendingHere)&&<div className="pw-butler-conversation-context">
    <Button variant="ghost" size="small" leadingIcon={IconChat} onClick={foregroundTurn&&!pendingHere?returnToForegroundReply:()=>void navigate('chat')} aria-label={pendingHere?'核对当前输入':`继续话题：${currentConversation?.title||'当前对话'}`} title={currentConversation?.title||'当前对话'}>
     <span>{pendingHere?'核对当前输入':foregroundTurn?`${conversationActivity} · 查看话题`:'继续'}{currentConversation?.title?' · '+currentConversation.title:''}</span><IconChevronRight size={15} aria-hidden="true"/>
    </Button>
    {replyStop}
   </div>}
   {route==='chat'&&<ConversationReadingNav {...readingNav}/>}
   {route==='chat'&&chatAttention&&<Button variant="ghost" className="pw-chat-attention" data-task-id={chatAttention.id} trailingIcon={IconChevronRight} aria-label={`${can('tasks.review')?taskActionLabel(chatAttention):'查看任务'}：${chatAttention.goal}`} disabled={taskOpening} onClick={()=>openTask(chatAttention.id,can('tasks.review')?'review':'overview')}><span className="pw-chat-attention-copy"><small>{can('tasks.review')?taskActionLabel(chatAttention):'查看任务'}</small><b>{chatAttention.goal}</b></span></Button>}
   {route==='chat'&&ready&&messages.length>0&&!composerModelName&&<div className="pw-butler-model-setup">{modelSetupAction}</div>}
   {intent==='chat'&&<ComposerSourceReferences key={`${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.source.remove')}/>}
   {intent==='chat'&&<ComposerObjectReferences key={`objects:${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.object.remove')}/>}
   {intent==='chat'&&<ComposerPreviewReferences key={`preview:${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.preview.remove')}/>}
   <ChatComposer continuationMode={intent==='chat'&&(state.chatSendMode==='queue'||state.chatSendMode==='steer')?state.chatSendMode:undefined}
     contextStatus={<>{(memoryPausedHere||newMemoryBlocked||singleLearningArmed||singleLearningBlocked)&&<Button variant="ghost" size="small" onClick={()=>setConversationMemoryOpen(true)}>{singleLearningBlocked?'核对本条记忆选择':newMemoryBlocked?'核对首条消息的记忆选择':memoryPausedHere?'个人记忆已暂停':'仅本条不自动整理'}</Button>}<IconChat size={15} aria-label="当前对话"/></>} controls={<ConversationModelPickerControls/>} status={inputLimitMessage||undefined} sendDisabled={!!inputLimitMessage||!composerModelName||intent==='chat'&&(previewRefsUnavailable||newMemoryBlocked||singleLearningBlocked)||!!pendingOriginal} sendLabel={inputLimitMessage||(!composerModelName?'请先连接模型':intent==='chat'&&(newMemoryBlocked||singleLearningBlocked)?'请先核对记忆选择':intent==='chat'&&previewRefsUnavailable?'请重新选择预览元素':pendingOriginal?'请核对这条输入的原回执':undefined)} toolbar={<div className="pw-composer-intent">
    <PopoverTrigger isOpen={voiceOptionsOpen} onOpenChange={setVoiceOptionsOpen}><IconButton icon={IconAdd} size="medium" aria-label="添加内容"/><Popover aria-label="添加内容"><PopoverHeader title="添加内容"/>{intent==='chat'&&can('attachments.openPicker')&&<Button variant="ghost" leadingIcon={IconFile} disabled={!!state.chatSubmitting} onClick={()=>{setVoiceOptionsOpen(false);run('attachments.openPicker')}}>添加附件</Button>}{can('voice.start')&&<Button variant="ghost" disabled={!!state.chatSubmitting||!!state.phoneSubmitting} onClick={()=>{setVoiceOptionsOpen(false);run(isListening?'voice.stop':'voice.start',{phone:intent!=='chat'})}}>{isListening?'结束听写':'语音输入'}</Button>}</Popover></PopoverTrigger>
    <ComposerWorkOptions intent={intent} onIntent={changeIntent} open={workOptionsOpen} onOpenChange={setWorkOptionsOpen} phoneAvailable={can('phone.prepare')} disabled={!!state.chatSubmitting||!!state.phoneSubmitting||phoneReviewPending} onCloudGoal={intent==='chat'&&chatDraft.value.trim()&&chatDraft.value.trim().length<=8000&&can('remote.reviewTask')?prepareCloudGoal:undefined} onPermissions={()=>{permissionExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);setPermissionOptionsOpen(true)}} onMemory={intent==='chat'&&canEditMemoryHere?()=>setConversationMemoryOpen(true):undefined} memoryPaused={memoryPausedHere} busy={!!state.chatBusy} sendMode={state.chatSendMode||'queue'} onSendMode={can('composer.mode')?mode=>run('composer.mode',{key:draftKey,epoch:draftEpoch,mode}):undefined}/>
   </div>} composerKey={intent==='chat'?chatOwner:phoneOwner} value={text} onChange={editText} onSend={send} onStop={currentCancelIdentity&&can('chat.cancel')?stopOriginalChat:undefined} busy={intent==='chat'&&!!state.chatBusy} submitting={intent==='chat'?!!state.chatSubmitting||registration.waiting:phoneReviewPending||!!state.phoneSubmitting} disabled={!ready||(intent==='chat'?!draftKey:!can('phone.prepare')||phoneDraft.sourceState==='elsewhere')} placeholder={intent==='chat'?(state.chatBusy?'补充要求，或继续写…':route==='butler'?'说说想做什么，或接着聊…':'写下想做的事…'):'想在手机上做什么？'} attachments={(intent==='chat'?asArray<Json>(state.selectedChatAttachments):[]).map(f=>({...f,id:String(f.id),name:f.name||'附件'}))} onRemoveAttachment={can('attachments.remove')?id=>run('attachments.remove',{id,key:draftKey,epoch:draftEpoch}):undefined} onVoice={can('voice.start')?()=>run(isListening?'voice.stop':'voice.start',{phone:intent!=='chat'}):undefined} listening={isListening} voiceStatus={state.voiceInput?.message} onDiscardVoice={can('voice.stop')||can('voice.discard')?discardVoice:undefined}/>
   {route==='butler'&&ready&&!!composerModelName&&!messages.length&&!foregroundTurn&&!butlerWorkRows.length&&!homeHasPreparations&&!phoneActivity&&
    stageExample&&<ConversationStarters onDraft={stageExample}/>}
   {intent!=='chat'&&['elsewhere','changed','unverified'].includes(phoneDraft.sourceState)&&<p className="pw-field-hint" role="status">{phoneDraft.sourceState==='elsewhere'?'手机草稿仍留在原私人对话，这里不会显示或提交它。':phoneDraft.sourceState==='changed'?'原消息已换版或不是最新交代。草稿已保留。要改用最新交代，请在那条消息的操作里选择「保留手机草稿，关联这条原话」。':'这份保留草稿缺少可核对的原消息。原文字还在，可在最新主人消息的操作里重新指定来源，或清空后另写。'}</p>}
   {intent!=='chat'&&phoneDraft.phone===true&&!state.conversationId&&!phoneDraft.channelOrigin&&composer.key&&can('phone.draft.recentStarts')&&<EarlierDraftPhoneStarts key={chatOwner} composer={String(composer.key)} except={phoneStart?.taskId} onTask={openTask}/>}
   {phoneStart&&phoneStart.draftRequestId&&phoneStart.status!=='rejected'&&!phoneReview&&<section className="pw-input-receipt" aria-label="本次草稿的手机任务"><Receipt receipt={phoneStart}/>{blankReceiptActions(phoneStart)}</section>}
   {phoneStart?.status==='unknown'&&!phoneStart.draftRequestId&&!phoneReview&&<section className="pw-input-receipt" aria-label="原手机启动回执"><Receipt receipt={phoneStart}/>{phoneStart.requestId&&phoneStart.conversationId&&can('phone.query')?<Button variant="ghost" size="small" disabled={phoneReviewPending} onClick={()=>queryOriginalPhone()}>查询原手机请求</Button>:<Button variant="ghost" size="small" onClick={()=>phoneStart.taskId?openTask(phoneStart.taskId):navigate('tasks')}>核对原任务记录</Button>}</section>}{(state.voiceInput?.reviewAvailable||composer.voiceText)&&<div className="pw-voice-review"><p>有听写文字待核对。</p>{can('voice.review')&&<Button variant="secondary" disabled={voicePending} onClick={openVoiceReview}>核对原听写</Button>}{can('voice.discard')&&<Button variant="ghost" disabled={voicePending} onClick={discardVoice}>丢弃本次听写</Button>}</div>}<ConversationDelivery rows={pendingInputs} can={can} submitting={!!state.chatSubmitting} onAction={(action,row)=>{
    if(action==='return'&&row.work){returnToWorkInput(row);return}
    run(action==='query'?'composer.query':action==='retry'?'composer.retry':'composer.returnPending',{clientMessageId:row.clientMessageId});
   }}/></div>;
 const preparationRefreshKey=JSON.stringify([preparationRevision,tasks.filter(task=>task.raw?.hasOwnerWorkPreparations).map(task=>[task.id,task.raw?.ownerWorkPreparationCount])]);
 const preparationEntries=(conversationId:string,homeRows?:Task[])=>{
  const representedTaskIds=new Set<string>();
  if(!homeRows&&can('tasks.open')){
   for(const task of conversationWorkRows)representedTaskIds.add(task.id);
   if(conversationId&&chatAttention)representedTaskIds.add(chatAttention.id);
   for(const id of representedPhoneTaskIds)representedTaskIds.add(id);
  }
  const representedHomeRuns=homeRows?new Set((can('tasks.open')?[...homeRows,...phoneRows.map(row=>row.task)]:[]).flatMap(task=>typeof task.raw?.runId==='string'&&task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0?[JSON.stringify([task.id,task.raw.runId,task.raw.coordinatorEpoch])]:[])):undefined;
  return <ButlerPreparationEntries representedHomeRuns={representedHomeRuns} onPresenceChange={conversationId?undefined:setHomeHasPreparations} key={'preparations:'+conversationId} conversationId={conversationId} representedTaskIds={representedTaskIds} active={ready&&!taskOpen&&!preparationTarget} refreshKey={preparationRefreshKey} can={can} run={bridge.command} onOpen={(target,trigger)=>{
   if(route==='chat'&&target.sourceConversationId===state.conversationId){
    inlinePreparationOrigin.current={conversationId:target.sourceConversationId,top:scroll.current?.scrollTop||0,trigger};
    stopFollowing();
   }
   setPreparationTarget(target);
  }}/>;
 };
 const preparationInline=!!preparationTarget&&!taskOpen&&route==='chat'&&preparationTarget.sourceConversationId===state.conversationId;
 useLayoutEffect(()=>{
  const origin=inlinePreparationOrigin.current;
  if(preparationTarget||!origin)return;
  inlinePreparationOrigin.current=null;
  if(route!=='chat'||taskOpen||state.conversationId!==origin.conversationId||!scroll.current)return;
  scroll.current.scrollTop=origin.top;
  if(origin.trigger?.isConnected&&!origin.trigger.matches(':disabled'))origin.trigger.focus({preventScroll:true});
 },[preparationTarget,route,taskOpen,state.conversationId]);
 const preparationDetail=preparationTarget&&<ButlerPreparationDetail
  key={preparationTarget.sourceTaskId+':'+preparationTarget.proposalId}
  target={preparationTarget} projects={projects} can={can} run={bridge.command} onClose={closePreparation}
  inline={preparationInline} registerBack={registerPreparationBack} registerBeforeLeave={registerPreparationBeforeLeave}
  onOpenTask={id=>openTask(id)} onConversation={!preparationInline&&can('conversation.select')&&!state.chatSubmitting?selectConversation:undefined}
  onSetup={can('setup.read')?kind=>{void openSetup(kind==='phone'?'phone':'tools')}:undefined}/>;
 const chatNavigation=!desktopNavigation&&<ChatNavigationMenu title={messages.length?currentTitle:'与管家对话'} navigationTrigger={<IconButton ref={chatBackGlass} icon={IconArrowLeft} className="pw-chat-glass-trigger" aria-label="返回管家" onClick={()=>navigate('butler')}/>} onHome={()=>navigate('butler')} isOpen={chatMenuOpen} onOpenChange={setChatMenuOpen} triggerRef={chatMenuTrigger} submitting={!!state.chatSubmitting} unread={can('remoteInbox.state')?Number(state.remoteInbox?.unread||0):0} onNewConversation={can('conversation.new')?()=>{void navigate('butler',()=>run('conversation.new'))}:undefined} onConversations={trigger=>{navigationReturnFocus.current=trigger;showWorkSearch()}} onNavigation={()=>{navigationReturnFocus.current=chatMenuTrigger.current;setCommandOpen(false);setWorkOpen(true)}} onOrganize={currentConversation&&can('conversation.organization.read')?()=>setOrganizationTarget(currentConversation.id):undefined} onMemory={canEditMemoryHere?()=>setConversationMemoryOpen(true):undefined} onNotifications={can('remoteInbox.state')?()=>{void openNotifications()}:undefined} onSettings={()=>navigate('settings')}/>;
 const chatPage=<main className={`pw-chat-workspace${ready&&!messages.length&&!foregroundTurn?' pw-chat-empty':''}`}>
  <div ref={scroll} tabIndex={-1} className="pw-conversation-scroll" data-conversation-id={String(state.conversationId||'')} onScroll={saveReading} aria-label="当前对话消息"><div ref={chatContent} className="pw-conversation-content">
   {ready&&messages.length>0&&state.conversationId&&can('conversation.reader.open')&&<Button variant="ghost" size="small" leadingIcon={IconHistory} onClick={()=>run('conversation.reader.open',{conversationId:state.conversationId})}>浏览对话记录</Button>}
   {!ready?<div className="pw-start"><AgentAvatar agentId="main" state="idle" size={40}/><ShimmerText>正在恢复当前工作</ShimmerText><div className="pw-loading-copy" aria-hidden="true"><Skeleton width="68%" height={13}/><Skeleton width="92%" height={13}/><Skeleton width="80%" height={13}/></div></div>:!messages.length&&!foregroundTurn?<ConversationOpening setup={!composerModelName?modelSetupAction:undefined}
    editing={editingChat} pending={pendingHere} onDraft={stageExample}/>:null}
   {ready&&replyRows.length>0&&<div ref={chatMessageList} className="pw-message-stream">{replyRows.map(row=>{
    if(row.kind==='live'){
     const draft=row.draft,hasText=!!draft?.text.trim(),phase=replyActivityPhase(draft,foregroundTurn?.resourceState,conversationActivity);
     return <article key={row.key} className="pw-message pw-message-assistant" aria-label={hasText?'当前回复草稿':'当前回复状态'} aria-busy={['thinking','replying','stopping'].includes(phase.state)}>
      <ConversationTurnActivity phase={phase} conversation={String(state.conversationId||'')} identity={foregroundTurn as WorkIdentity|null}
       task={foregroundTurn?replyTasksById.get(foregroundTurn.taskId):undefined} actionElsewhere={!!chatAttention&&chatAttention.id===foregroundTurn?.taskId}
       onRead={can('tasks.open')&&foregroundTurn?layer=>openTurnNotes(foregroundTurn as WorkIdentity,layer):undefined}/>
      <CitedAnswer continuous text={draft?.text||''} busy={phase.state==='replying'} onCopyCode={copyWithLocalFeedback}/>
      {draft?.truncated&&<p className="pw-muted">仅预览前 32,768 个字符，完整结果以正式回复为准。</p>}
     </article>;
    }
    const {message:m,index}=row,continued=wasReplyVisible(row);
    const id=m.id||`${m.role}:${m.at}:${index}`,{refs,phoneResult,taskResult,replyContext}=savedReplyDetails[index];
    const linked=replyTasksById.get(m.taskId||m.submission?.taskId);
    const traceTaskId=phoneResult?.taskId||taskResult?.taskId||m.taskId;
    const linkedFeedback=linked&&!m.hostGoalTaskId&&!phoneResult&&!taskResult&&!representedPhoneTaskIds.has(linked.id)&&linked.id!==chatAttention?.id&&!conversationWorkIds.has(linked.id)&&messageTaskAnchors.get(linked.id)===index?linked:null;
    const completedFeedback=linkedFeedback&&conversationTaskCompact(linkedFeedback);
    const returnedWork=phoneResult||(taskResult?.noticeKind?null:taskResult);
    const earlierRun=returnedWork&&linked?.raw?.runId&&(linked.raw.runId!==returnedWork.runId||linked.raw.coordinatorEpoch!==returnedWork.epoch);
    const originalPhone=phoneResult&&!!m.id&&m.sourceRevision===1&&can('phone.result.original')&&(phoneResult.summaryTruncated||earlierRun||!linked?.raw?.runId);
    const inlineResult=!earlierRun&&!phoneResult&&taskResult?.kind==='owner_work'&&taskResult.status==='completed'&&!taskResult.noticeKind&&!!m.id;
    const noteIdentity=!phoneResult&&!taskResult&&replyContext&&messageTaskAnchors.get(replyContext.taskId)===index?replyContext:null;
    const canQuote=intent==='chat'&&m.quoteAvailable&&can('messageQuote.open')&&can('messageQuote.stage')&&can('messageQuote.close');
    return <article key={row.key} tabIndex={-1} data-message-index={index} data-message-id={id} data-reply-continuing={continued||undefined} className={`pw-message pw-message-${m.role}`} aria-label={m.role==='assistant'?(phoneResult||taskResult?'任务结果':'助手回复'):'你的消息'}>
     {m.role==='assistant'&&<ConversationTurnActivity phase={null} conversation={String(state.conversationId||'')} identity={noteIdentity} task={linked}
      actionElsewhere={!!linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')||!!chatAttention&&chatAttention.id===noteIdentity?.taskId}
      onRead={noteIdentity&&can('tasks.open')?layer=>openTurnNotes(noteIdentity,layer):undefined}/>}
     {m.role==='assistant'?<CitedAnswer continuous text={m.content} onCopyCode={copyWithLocalFeedback} sources={refs as any} onOpenSource={()=>openSource(m)}/>:m.content?<div className="pw-user-text">{m.content}</div>:null}
     {asArray<Json>(m.attachments).length>0&&<div className="pw-message-files">{asArray<Json>(m.attachments).map(file=><Button key={file.id||file.name} variant="secondary" size="small" leadingIcon={IconFile} disabled={!can('attachments.open')||!file.sha256||!!state.attachmentOpens?.[`${file.id}:${file.sha256}`]?.checking} onClick={()=>run('attachments.open',{messageId:m.id,id:file.id,sha256:file.sha256})}>{file.name||'原附件'}</Button>)}</div>}
     {m.submission&&<Receipt receipt={m.submission}/>}
     <MotionFeedback className="pw-message-footer" identity={`${id}:${m.submission?.status||'saved'}:${refs.map(source=>source.id).join(',')}`} initial={continued}>
     {m.hostGoalTaskId&&m.id&&can('conversation.openHostGoal')&&<Button variant="ghost" size="small" className="pw-message-trace" leadingIcon={IconFolder} onClick={()=>{const task=m.hostGoalTaskId!,conversation=String(state.conversationId||''),documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
       void act('conversation.openHostGoal',{taskId:task,conversationId:conversation,messageId:m.id}).then(receipt=>{if(receipt?.taskId===task&&receipt.readOnly===true&&receipt.executionAuthorized===false&&!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&navigationWindow.current===navigation&&bridge.getState().state.conversationId===conversation)openTask(task,'overview',true);}).catch(()=>{});}}>打开原主机工作</Button>}
     {m.role==='user'&&m.submission?.status==='queued'&&m.submission?.taskId&&can('queued.open')&&<Button variant="ghost" className="pw-message-queue-action" leadingIcon={IconClock} onClick={()=>run('queued.open',{taskId:m.submission!.taskId,conversationId:state.conversationId})}>已入队 · 查看或调整</Button>}
     {m.role==='user'&&linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')&&
      <ConversationLinkedReason conversation={String(state.conversationId||'')} task={linkedFeedback}/>}
     {linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')&&<Button variant="ghost" className="pw-message-work-action" leadingIcon={completedFeedback?IconCheckCircle:attention(linkedFeedback)||linkedFeedback.status==='failed'?IconError:IconClock} aria-label={`查看任务详情：${linkedFeedback.goal}`} title={linkedFeedback.goal} disabled={!can('tasks.open')} onClick={()=>openTask(linkedFeedback.id,attention(linkedFeedback)&&can('tasks.review')?'review':'overview')}><span className="pw-message-work-next">{taskNeedsReconciliation(linkedFeedback)?'有操作待核对':attention(linkedFeedback)?taskActionLabel(linkedFeedback):linkedFeedback.status==='failed'?'查看失败原因':terminal(linkedFeedback)?'查看详情':'查看进展'}<IconChevronRight size={15} aria-hidden="true"/></span></Button>}
     {(m.content||m.role==='assistant'||canQuote||(m.retainedSources&&can('sources.open')))&&<div className="pw-message-tools">
       {returnedWork&&(originalPhone||can('tasks.open'))&&<Button variant="ghost" size="small" className="pw-message-trace" leadingIcon={IconFolder} disabled={inlineResult&&!!openingChatResultId}
        onClick={event=>{
          if(originalPhone&&phoneResult&&m.id)setMessageOriginTarget(phoneReportTarget(String(state.conversationId||''),m.id,Number(m.sourceRevision),phoneResult,event.currentTarget));
          else if(inlineResult&&taskResult&&m.id)void openChatResult(m.id,taskResult,event.currentTarget);
          else openTask(returnedWork.taskId,'overview',false,undefined,undefined,earlierRun?undefined:{runId:returnedWork.runId,epoch:returnedWork.epoch});
        }}>
        {openingChatResultId===m.id?'正在读取成果…':originalPhone?'查看这轮原文':earlierRun?'查看所属工作':returnedWork.status==='completed'?'查看成果':'查看这次工作'}
       </Button>}
      {m.role==='assistant'&&!!m.content&&<MessageActions onCopy={()=>copyWithLocalFeedback(m.content)} onSpeak={can('speech.speak')&&m.id&&row.lastOfRole?playing=>run(playing?'speech.speak':'speech.stop',{messageId:m.id}):undefined} speaking={!!state.speaking}/>}
      <PopoverTrigger isOpen={messageMenu===id} onOpenChange={value=>setMessageMenu(value?id:null)}>
       <IconButton icon={IconMore} size="small" aria-label="消息操作与来源"/>
       <Popover className="pw-message-popover" aria-label="消息操作与来源"><div className="pw-message-menu">
        {m.role==='user'&&m.automaticLearningExcluded===true&&<p className="pw-muted">这条原话已排除自动记忆整理，后来开启也不会补提炼。它仍是对话原文；这不代表类似内容从未另行保存。</p>}
        {m.role==='assistant'&&traceTaskId&&can('tasks.open')&&<Button variant="ghost" leadingIcon={IconTool} aria-label={phoneResult?'查看这份手机任务的当前进展':'查看这条回复的执行轨迹'} onClick={()=>{setMessageMenu(null);openTask(traceTaskId,'process')}}>{phoneResult?'所属工作当前进展':'执行轨迹'}</Button>}
        {m.role==='assistant'&&completedFeedback&&linkedFeedback&&can('tasks.open')&&<Button variant="ghost" aria-label={`查看任务详情：${linkedFeedback.goal}`} onClick={()=>{setMessageMenu(null);openTask(linkedFeedback.id)}}>查看任务详情</Button>}
        {!!m.content&&<Button variant="ghost" leadingIcon={IconBook} onClick={()=>{const source=Array.from(scroll.current?.querySelectorAll<HTMLElement>('[data-message-id]')||[]).find(row=>row.dataset.messageId===id);openReading({kind:'message',title:m.role==='assistant'?'阅读回复':'阅读消息',text:m.content,quoteSource:canQuote&&!state.chatSubmitting&&draftKey&&m.id&&Number.isSafeInteger(m.sourceRevision)&&Number(m.sourceRevision)>0?{target:{messageId:m.id,sourceRevision:Number(m.sourceRevision),key:draftKey,epoch:draftEpoch},documentEpoch:bridge.getDocumentEpoch()}:undefined,returnFocus:source?.querySelector<HTMLElement>('button[aria-label="消息操作与来源"]')});setMessageMenu(null)}}>阅读与选字</Button>}
        {m.role!=='assistant'&&!!m.content&&<MessageActions onCopy={()=>copyWithLocalFeedback(m.content)}/>}
        {m.role==='user'&&row.lastOfRole&&m.id&&m.quoteAvailable&&Number(m.sourceRevision)>0&&!!m.content.trim()&&can('phone.draft.fromMessage')&&can('phone.prepare')&&<Button variant="ghost" leadingIcon={IconMonitor} disabled={phoneReviewPending||!!state.chatSubmitting||!!state.chatBusy||!!state.phoneSubmitting||!!state.activeTaskId||isListening||phoneStart?.status==='unknown'} onClick={()=>stageMessageForPhone(m,['changed','unverified'].includes(phoneDraft.sourceState))}>{['changed','unverified'].includes(phoneDraft.sourceState)?'保留手机草稿，关联这条原话':'准备手机任务'}</Button>}

        {m.id&&replyContext?.replyToMessageIds.length&&can('message.origin')?<Button variant="ghost" leadingIcon={IconHistory} onClick={()=>{const source=Array.from(scroll.current?.querySelectorAll<HTMLElement>('[data-message-id]')||[]).find(row=>row.dataset.messageId===id);rememberChatPosition();setMessageOriginTarget({conversationId:String(state.conversationId),messageId:m.id!,sourceMessageIds:replyContext.replyToMessageIds,returnFocus:source?.querySelector<HTMLElement>('button[aria-label="消息操作与来源"]')});setMessageMenu(null)}}>查看对应原请求</Button>:null}
        {m.role==='user'&&m.submission?.taskId&&can('queued.open')&&<Button variant="ghost" leadingIcon={IconClock} onClick={()=>{setMessageMenu(null);run('queued.open',{taskId:m.submission!.taskId,conversationId:state.conversationId})}}>原任务的输入队列</Button>}
        {canQuote&&<Button variant="ghost" leadingIcon={IconQuote} disabled={!draftKey||!!state.chatSubmitting} onClick={()=>{setMessageMenu(null);openQuote(m)}}>引用到草稿</Button>}
        {m.retainedSources&&can('sources.open')&&<Button variant="ghost" leadingIcon={IconLink} onClick={()=>{setMessageMenu(null);openSource(m)}}>回答来源</Button>}
        {m.memoryReference&&m.id&&can('taskMemory.answer')&&<Button variant="ghost" onClick={()=>{setMessageMenu(null);setProvidedMemoryMessage(m.id!)}}>本次提供的记忆</Button>}
        {m.memoryReference&&<Button variant="ghost" onClick={()=>{setMessageMenu(null);openPersonalMemory({view:'memories'})}}>管理个人记忆</Button>}
        {m.role==='assistant'&&!phoneResult&&!taskResult&&m.taskId&&can('conversation.drafts')&&messageTaskAnchors.get(m.taskId)===index&&<ConversationDrafts key={m.taskId} taskId={m.taskId} reviewingMemory={memoryDraftTarget?.taskId===m.taskId} refreshRevision={Number(state.memoryRefreshRevision||0)} open={draft=>{setMessageMenu(null);openConversationDraft(draft)}}/>}
       </div></Popover>
      </PopoverTrigger>
     </div>}
     </MotionFeedback>

    </article>;
   })}</div>}

   {state.conversationId&&<div hidden={preparationInline}>{preparationEntries(String(state.conversationId))}</div>}
   {preparationInline&&preparationDetail}
   <ConversationWork pinnedTask={chatAttention&&conversationWork.find(task=>task.id===chatAttention.id)} onTeamDecision={can('teams.attention')&&can('teams.readAttention')?openConversationTeamDecision:undefined} identity={String(state.conversationId||'')} onEmptyFocus={()=>workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})} tasks={conversationWorkRows} canOpen={can('tasks.open')} onOpen={task=>openTask(task.id,attention(task)&&can('tasks.review')?'review':'overview')}/>
  </div></div>
  {composerRegion}</main>;
 const tasksPage=<WorkRecoveryDirectory directory={taskDirectory} live={tasks} canContinue={task=>can(task.raw?.executionRole==='owner_work'?'ownerWork.control':'tasks.resume')} scroll={taskList} ready={ready} canOpen={can('tasks.open')} canReview={can('tasks.review')}
  scope={filter as WorkCollectionFilter} query={taskQuery} selectScope={selectWorkScope} clearQuery={()=>setTaskQuery('')} drillEnded={drillEndedWork}
  start={()=>{void navigate('butler')}} history={()=>{void openRelatedWorkspace('history')}} open={(id,entry,query)=>openTask(id,entry,false,undefined,query)}
  registerReturn={registerWorkReturn} extra={can('remoteSessions.read')&&<Accordion variant="divided"><AccordionItem id="remote-sessions" title="电脑端托管会话"><RemoteManagedSessionsWorkspace/></AccordionItem></Accordion>}/>;
 const showProjectSection=(section:'work'|'files')=>{projectPreview.hide();setProjectSections(previous=>({...previous,[String(state.projectId||'')]:section}))};
 const openProjectFile=(path:string)=>{if(!can('projects.open'))return;setProjectSections(previous=>({...previous,[String(state.projectId||'')]:'files'}));updateProjectView({selected:path});run('projects.open',{projectId:state.projectId,path})};
 const projectListBusy=asArray<string>(state.moduleBusy).includes('workspace.list')||packageOpening;
 const projectListInput=obj(obj(state.moduleResultInputs)['workspace.list']);
 const projectListError=projectListInput.projectId===state.projectId?String(obj(state.moduleErrors)['workspace.list']||''):'';
 useCollectionMotion(projectScroll,JSON.stringify(['projects',state.projectId]),JSON.stringify(projects.map(project=>[project.id,project.name])),{selector:'.pw-project-library-row',active:route==='projects'&&ready&&!state.filePath&&!projectPreview.expanded&&!packageOpening});
 const projectFiles=<>{(files.length>0||!!fileQuery)&&<><div className="pw-project-tools"><SearchField value={fileQuery} onChange={setFileQuery} aria-label="搜索当前项目文件" placeholder="搜索文件"/><PopoverTrigger isOpen={fileDisplayOpen} onOpenChange={setFileDisplayOpen}><IconButton icon={fileView==='tree'?IconFolder:IconGrid} aria-label="文件显示方式" title="文件显示方式"/><Popover aria-label="文件显示方式"><PopoverHeader title="文件显示"/><RadioOptions label="显示方式" value={fileView} onChange={value=>{setFileView(value as 'files'|'tree'|'grid');setFileDisplayOpen(false)}} options={[{id:'files',label:'文件列表'},{id:'tree',label:'目录层级'},{id:'grid',label:'网格'}]}/></Popover></PopoverTrigger>{can('projects.newFile')&&<IconButton icon={IconAdd} aria-label="新文件" title="新文件" onClick={()=>openCreate('file')}/>}</div>
      <div className="pw-project-list-caption"><MotionFeedback as="span" identity={`${projectListBusy}:${fileQuery}:${shownFiles.length}`} initial={false} role="status">{projectListBusy?'正在刷新 · ':''}{fileQuery?`匹配 ${shownFiles.length} 个文件`:`${files.length} 个文件`}</MotionFeedback>{fileQuery&&<Button variant="ghost" size="small" onClick={()=>setFileQuery('')}>清空搜索</Button>}{can('projects.refresh')&&<IconButton icon={IconRefresh} size="small" aria-label="重新读取项目文件" onClick={()=>run('projects.refresh')}/>}</div>
      </>}<div className="pw-project-objects" aria-busy={projectListBusy||undefined}>{!ready||packageOpening||projectListBusy&&!files.length?<p className="pw-muted" role="status">正在读取项目文件…</p>:projectListError?<div className="pw-empty pw-project-empty" role="alert"><h3>无法读取文件</h3><p>{projectListError}</p>{can('projects.refresh')&&<Button variant="secondary" leadingIcon={IconRefresh} onClick={()=>run('projects.refresh')}>重新读取</Button>}</div>:!shownFiles.length?<div className="pw-empty pw-project-empty"><IconFolder size={26} aria-hidden="true"/><h3>{fileQuery?'没有匹配的文件':'暂无列出的文件'}</h3><p>{fileQuery?'换个关键词，或清空搜索返回文件列表。':'从一个新文件开始，也可以重新读取当前项目。'}</p>{fileQuery?<Button variant="secondary" onClick={()=>setFileQuery('')}>清空搜索</Button>:<><div className="pw-action-row">{can('projects.newFile')&&<Button variant="primary" leadingIcon={IconAdd} onClick={()=>openCreate('file')}>新建文件</Button>}{can('projects.refresh')&&<Button variant="ghost" leadingIcon={IconRefresh} onClick={()=>run('projects.refresh')}>重新读取</Button>}</div></>}</div>:fileView==='tree'?<TreeView aria-label="项目文件" key={state.projectId} className="pw-file-tree" data={treeData(shownFiles)} selectedItemId={projectView.selected||undefined} expandedIds={projectView.expanded} onExpandedChange={expanded=>updateProjectView({expanded})} onSelectChange={file=>{if(file&&!file.children)openProjectFile(file.id)}}/>:<FilesBrowser identity={JSON.stringify([state.projectId,fileQuery.trim().toLocaleLowerCase()])} files={shownFiles as any} showToolbar={false} view={fileView==='grid'?'Grid':'List'} onViewChange={view=>setFileView(view==='Grid'?'grid':'files')} onCopyPath={path=>{void copyText(path).catch(()=>{})}} selectedPath={projectView.selected} onOpen={file=>openProjectFile(file.path)}/>}</div></>;
 const projectsPage=<motion.main layoutScroll ref={projectScroll} className={`pw-page pw-projects-page ${state.filePath?'pw-project-file-open':''}`} data-preview-open={projectPreview.expanded&&!state.filePath||undefined} onScroll={event=>{if(!state.filePath&&!projectPreview.expanded){if(state.projectId&&projectSection==='work')projectDirectory.rememberScroll(event.currentTarget.scrollTop);else projectListTop.current.set(projectPositionKey,event.currentTarget.scrollTop)}}}>
  {packageOpening&&<ProgressBar label="正在读取这次任务的固定包" isIndeterminate showValueLabel={false} size="sm"/>}
  {state.filePath?<ProjectFileEditor key={currentFileKey} path={state.filePath} version={Number(state.fileVersion||0)} draft={fileDraft} state={state} conflict={fileConflict} mode={fileMode} setMode={setFileMode} can={can} edit={editFile} save={saveFile} readHistory={()=>run('projects.history',fileIdentity)} readRevision={revision=>run('projects.revision',{...fileIdentity,revision})} retry={()=>run('projects.retry',{projectId:state.projectId,path:state.filePath})} copy={()=>{if(fileDraft)void copyText(fileDraft.text).catch(()=>{})}} adoption={fileAdoption} registerBack={registerProjectFileBack}/>:currentProject?<section className="pw-project-work" aria-label="当前项目工作区">
    <Tabs selectedKey={projectPreview.expanded?'preview':projectSection} onSelectionChange={key=>{if(key==='preview')projectPreview.open();else showProjectSection(key==='files'?'files':'work')}} className="pw-project-tabs">
      <TabList aria-label="项目工作方式"><Tab id="work" icon={IconLayers}>工作</Tab><Tab id="files" icon={IconFolder}>文件</Tab><Tab id="preview" icon={IconPlay} isDisabled={!can('projects.preview')&&!projectPreview.expanded}>预览</Tab></TabList>
      <TabPanel id="work" shouldForceMount hidden={projectSection!=='work'||projectPreview.expanded}>
       <ProjectWorkspaceOverview project={currentProject} directory={projectDirectory} view={projectWorkView} onViewChange={updateProjectWorkView} directoryAvailable={can('work.tasks.list')} canOpenTask={can('tasks.open')} canReview={can('tasks.review')} canContinue={task=>can(task.raw?.executionRole==='owner_work'?'ownerWork.control':'tasks.resume')}
        onOpenTask={openProjectWork} onOpenTerminal={()=>void openRelatedWorkspace('local')} onAskButler={can('composer.object.add')?()=>askButlerAbout({kind:'project',id:currentProject.id}):undefined}
        startContent={can('projects.development.read')||can('projectWork.open')&&can('projectWork.confirm')?<ProjectWorkLauncher key={currentProject.id}
          active={route==='projects'&&!taskOpen&&!state.filePath&&projectSection==='work'&&!projectPreview.expanded&&!projectPickerOpen&&!newProjectOpen&&!newFileOpen&&!workOpen&&!commandOpen}
          resumeProfile={!!projectWorkDrafts[currentProject.id]?.operation}
          presentation={projectWorkPresentations[currentProject.id]??{open:!!(projectWorkDrafts[currentProject.id]?.operation||projectWorkDrafts[currentProject.id]?.expanded),mode:projectWorkDrafts[currentProject.id]?.operation||projectWorkDrafts[currentProject.id]?.expanded||!can('projects.development.read')?'profile':'default'}}
          onPresentationChange={presentation=>setProjectWorkPresentations(previous=>({...previous,[currentProject.id]:presentation}))}
          defaultStart={can('projects.development.read')?(active,onBusy)=><ProjectDevelopmentWorkspace key={currentProject.id} projectId={currentProject.id} name={currentProject.name} active={active} run={act} can={can} registerBack={registerProjectDevelopmentBack} onInteractionBusyChange={onBusy} onOpenTask={openProjectReceipt} onTaskRegistered={projectDirectory.reset} onPrepareTools={()=>void openSetup('tools')} onConnectModel={()=>void openRelatedWorkspace('model-settings')} onOpenTerminal={()=>void openRelatedWorkspace('local')}/>:undefined}
          profileStart={can('projectWork.open')&&can('projectWork.confirm')?(active,onBusy)=><ProjectWorkStart
            key={currentProject.id} project={currentProject} embedded
            draft={projectWorkDrafts[currentProject.id]||emptyProjectWorkDraft()}
            onChange={draft=>setProjectWorkDrafts(previous=>({...previous,[currentProject.id]:draft}))}
            initiallyOpen active={active} onInteractionBusyChange={onBusy}
            ownerRevision={`${bridge.getDocumentEpoch()}:${snapshot?.view?.section||''}:${Number(snapshot?.view?.resumeRevision||0)}`}
            supported={can} run={bridge.command} onOpenTask={openProjectReceipt} onTaskRegistered={projectDirectory.reset}
            onConfigure={()=>void openRelatedWorkspace('workProfiles')}
            onPrepareModel={can('setup.read')?()=>void openRelatedWorkspace('model-settings'):undefined}
            onPrepareTools={can('setup.read')?()=>void openSetup('tools'):undefined}
            registerBack={registerProjectWorkBack} onReviewChange={setProjectWorkReviewOpen}
          />:undefined}
        />:undefined}/>
      </TabPanel>
      <TabPanel id="files">{projectFiles}</TabPanel>
      <TabPanel id="preview" className="pw-project-preview-panel" style={{translate:'none',opacity:1,transition:'none'}}>{desktopNavigation?<div className="pw-project-connected-workspace" data-preview-open="true"><motion.section layoutScroll className="pw-project-files-pane" aria-label="项目文件"><header className="pw-project-connected-header"><h2>项目文件</h2></header>{projectFiles}</motion.section><section className="pw-project-preview-panel" aria-label="项目预览"><ProjectPreviewPane control={projectPreview} composer={composer}/></section></div>:<ProjectPreviewPane control={projectPreview} composer={composer}/>}</TabPanel>
    </Tabs>

  </section>:projects.length>0?<section className="pw-project-library" aria-label="项目目录"><header><h2>你的项目</h2><div className="pw-project-library-actions">{can('projects.gitImport.open')&&<Button variant="ghost" onClick={()=>run('projects.gitImport.open')}>从 Git 导入</Button>}{can('projects.create')&&<Button variant="secondary" leadingIcon={IconAdd} onClick={()=>openCreate('project')}>新项目</Button>}</div></header><div>{projects.map(project=><Button key={project.id} variant="ghost" className="pw-project-library-row" data-motion-key={project.id} leadingIcon={IconFolder} trailingIcon={IconChevronRight} disabled={!can('projects.select')} onClick={()=>run('projects.select',{id:project.id})}>{project.name}</Button>)}</div></section>:!ready?<p className="pw-muted" role="status">正在读取项目…</p>:<div className="pw-empty pw-project-empty"><IconFolder size={26} aria-hidden="true"/><h3>从一个项目开始</h3><p>把相关任务、文件和成果放在一起，随时接着做。</p><div className="pw-project-library-actions">{can('projects.create')&&<Button variant="primary" leadingIcon={IconAdd} onClick={()=>openCreate('project')}>新建项目</Button>}{can('projects.gitImport.open')&&<Button variant="ghost" onClick={()=>run('projects.gitImport.open')}>从 Git 导入</Button>}</div></div>}
  <ProjectGitIntake state={state.projectGitImport} toolReturn={projectGitToolReturn} onPrepareTools={can('setup.runtime.read')?()=>{void openSetup('tools')}:undefined}/>
 </motion.main>;
 const workspaceNotices=<>{route!=='chat'&&replyActivity}{currentCancelFeedback&&<div className={currentCancelFeedback.error?'pw-banner pw-banner-error':'pw-banner'} role={currentCancelFeedback.error?'alert':'status'}><p>{currentCancelFeedback.message}</p></div>}{(localError||state.error)&&<div className="pw-banner pw-banner-error" role="alert"><IconInfo size={17}/><p>{localError||state.error}</p>{localError&&<IconButton icon={IconClose} aria-label="关闭这条操作提示" onClick={()=>setLocalError('')}/>}</div>}{Object.entries(obj(state.attachmentOpens)).filter(([,value])=>value.error).map(([key,value])=><div className="pw-banner pw-banner-error" role="alert" key={key}><p>{value.error}</p></div>)}{(localStatus||state.notice)&&<div className="pw-banner" role="status"><p>{localStatus||state.notice}</p>{(localStatus||can('notice.dismiss'))&&<IconButton icon={IconClose} aria-label="关闭这条提示" onClick={()=>{setLocalStatus('');if(state.notice&&can('notice.dismiss'))run('notice.dismiss')}}/>}</div>}
  {searchReturn&&!state.attachmentWorkspace?.open&&<div className="pw-search-return"><Button variant="ghost" size="small" leadingIcon={IconArrowLeft} onClick={showWorkSearch}>返回搜索结果</Button></div>}</>;
 return <ReadingContext.Provider value={openReading}><ConversationModelPickerProvider ref={modelPicker} state={state} can={can} act={act} disabled={!ready} active={isChatSurface} channelPhoneModel={channelPhoneModel} onChannelModel={()=>{void navigate('models')}} onManage={()=>{const origin={...captureWorkspaceOrigin(),modelPicker:true};void openRelatedWorkspace('models',origin)}} onOpenMedia={can('media.snapshot')?()=>{const origin={...captureWorkspaceOrigin(),modelPicker:true};void openRelatedWorkspace('media',origin)}:undefined}><Drawer.Root swipeToOpen={!desktopNavigation} open={workOpen&&!desktopNavigation} onOpenChange={changeNavigation} direction="left" autoFocus shouldScaleBackground={false}>
 <div className="pw-app pw-redesign" data-navigation={desktopNavigation?'sidebar':'drawer'} data-route={route} data-file-open={!!state.filePath||undefined} data-vaul-drawer-wrapper=""><Toaster theme={state.appearance?.dark===true?'dark':'light'}/>
  {desktopNavigation&&<aside className="pw-desktop-navigation pw-navigation-drawer pw-material-navigation" data-compact={effectiveCompact?'true':'false'} aria-label="侧边工作导航" onKeyDown={event=>{if(event.key!=='Escape'||event.nativeEvent.isComposing||event.nativeEvent.keyCode===229)return;if(navigationMoreOpen||commandOpen){event.preventDefault();if(navigationMoreOpen)setNavigationMoreOpen(false);else setCommandOpen(false)}}}>{navigationContent}</aside>}
  <div className="pw-main-workspace" ref={workspace}>
  {isPrimaryRoute?<NavigationChrome>
  {(route!=='chat'||desktopNavigation)&&<header className="pw-app-header">
   {route==='tasks'&&endedScopeOrigin?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="返回工作总览" title="返回工作总览" onClick={restoreWorkScope}/>:route==='projects'&&state.filePath?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="返回项目文件" disabled={fileAdoption.pending||!!state.fileSaving} onClick={()=>{if(!fileAdoption.isPending()&&!bridge.getState().state.fileSaving&&!projectFileBack.current?.())run('projects.close',fileIdentity)}}/>:(route==='projects'||route==='tasks')&&relatedOrigin.current?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label={relatedOrigin.current.label} title={relatedOrigin.current.label} onClick={returnFromSecondary}/>:route==='projects'&&state.projectTaskReturn?.taskId?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="回到原任务" onClick={()=>openTask(state.projectTaskReturn.taskId)}/>:navigationTrigger}
   {route==='tasks'?<WorkFindTitle editing={taskFiltersOpen} query={taskQuery} onQuery={setTaskQuery} edit={()=>setTaskFiltersOpen(true)} close={closeWorkFind}/>:route==='chat'?<div className="pw-current-work pw-chat-title"><h1 title={currentTitle} aria-label={messages.length?currentTitle:'PocketWorld'}>{messages.length>0&&currentConversation&&can('conversation.organization.read')?<Button variant="ghost" className="pw-chat-title-trigger" aria-label="整理当前对话" onClick={()=>setOrganizationTarget(currentConversation.id)}><span className="pw-chat-title-text">{currentTitle}</span><IconChevronDown size={16} aria-hidden="true"/></Button>:messages.length?currentTitle:'PocketWorld'}</h1></div>:route==='projects'&&!state.filePath?<PopoverTrigger isOpen={projectPickerOpen} onOpenChange={setProjectPickerOpen}><Button variant="ghost" className="pw-object-title" aria-label="切换项目"><h1>{currentTitle}</h1><IconChevronDown size={16}/></Button><Popover aria-label="切换项目"><PopoverHeader title="项目"/><div className="pw-project-menu">{projects.map(project=><Button key={project.id} variant="ghost" aria-current={project.id===state.projectId?'true':undefined} onClick={()=>{setProjectPickerOpen(false);run('projects.select',{id:project.id})}}><IconFolder size={17}/><span>{project.name}</span>{project.id===state.projectId&&<small>当前</small>}</Button>)}{can('projects.create')&&<Button variant="secondary" leadingIcon={IconAdd} onClick={()=>{setProjectPickerOpen(false);openCreate('project')}}>新项目</Button>}</div></Popover></PopoverTrigger>:<div className="pw-current-work"><BlurCrossfade identity={currentTitle}><h1>{currentTitle}</h1></BlurCrossfade></div>}
   {isChatSurface?<div ref={headerActionsGlass} className="pw-header-actions pw-navigation-glass" role="group" aria-label="对话操作">
    {can('remoteInbox.state')&&Number(state.remoteInbox?.unread||0)>0&&<IconButton icon={IconNotification} aria-label={'外部消息 · '+state.remoteInbox.unread+' 条未读'} title="外部未读消息" onClick={openUnreadMessages}/>}
    {can('conversation.new')&&<IconButton icon={IconNewChat} aria-label="聊件新事" title="聊件新事" disabled={!!state.chatSubmitting} onClick={()=>{void navigate('butler',()=>run('conversation.new'))}}/>}
   </div>:route==='tasks'?<div ref={headerActionsGlass} className="pw-primary-tools pw-navigation-glass" role="group" aria-label="工作目录操作">
    {!desktopNavigation&&(endedScopeOrigin||relatedOrigin.current)&&navigationTrigger}
    {!taskFiltersOpen&&can('tasks.refresh')&&<IconButton icon={IconRefresh} aria-label="刷新任务" onClick={()=>{taskDirectory.reset();run('tasks.refresh')}}/>}
    {taskFiltersOpen?<IconButton icon={IconClose} aria-label="收起搜索，保留关键词" onClick={closeWorkFind}/>:taskQuery?<IconButton icon={IconClose} aria-label="清除关键词" onClick={()=>{setTaskQuery('');requestAnimationFrame(()=>workspace.current?.querySelector<HTMLElement>('[data-work-search-entry]')?.focus({preventScroll:true}))}}/>:<IconButton icon={IconSearch} data-work-search-entry aria-label="搜索工作" onClick={()=>setTaskFiltersOpen(true)}/>}
   </div>:route==='projects'&&(state.filePath||state.projectTaskReturn?.taskId||relatedOrigin.current)?navigationTrigger:<span className="pw-header-spacer" aria-hidden="true"/>}
  </header>}
   {workspaceNotices}
   {route==='chat'&&<>{chatNavigation}{phoneActivity}{replyActivity}</>}
  </NavigationChrome>:workspaceNotices}
  {isPrimaryRoute?<div className="pw-workspace pw-route-workspace">{route==='butler'?<ButlerHome ready={ready} modelReady={!!composerModelName} modelSetup={modelSetupAction} composer={composerRegion} phoneActivity={phoneActivity} active={!taskOpen&&!workOpen&&!commandOpen&&!preparationTarget} moreWork={!!state.taskListNextCursor&&can('work.tasks.list')} preparations={rows=>preparationEntries('',rows)} hasPreparations={homeHasPreparations} tasks={butlerWorkRows} canOpen={can('tasks.open')} canReadReports={can('ownerWork.report.read')} onOpen={(task,alreadyOpen=false,report,stillOriginal)=>{
    if(report){homeReport.current=report;taskReading.current.delete(`${task.id}:homeReport`);openTask(task.id,'homeReport',alreadyOpen,undefined,undefined,undefined,()=>homeReport.current===report&&currentHomeReport(report));return}
    openTask(task.id,attention(task)&&can('tasks.review')?'review':'overview',alreadyOpen,undefined,undefined,undefined,stillOriginal)
   }} onAllWork={(scope='all')=>{void openRelatedWorkspace('tasks',undefined,()=>{setEndedScopeOrigin(null);setFilter(scope);setTaskQuery('')})}} initialScrollTop={butlerScrollTop.current} onScrollTop={top=>{butlerScrollTop.current=top}}/>:route==='chat'?chatPage:route==='tasks'?tasksPage:projectsPage}</div>:route==='remoteInbox'&&can('remoteInbox.state')?<div className="pw-workspace pw-route-workspace"><RemoteInboxWorkspace memory={notificationMemory.current} onOpenTeamAttention={locator=>{setTeamAttentionFocus({...locator,requestId:crypto.randomUUID()});void openRelatedWorkspace('teams')}} onOpenTask={(id,review)=>openTask(id,review?'review':'overview')} state={state.remoteInbox} navigationTrigger={navigationTrigger} onBack={returnFromSecondary} registerBack={registerSecondaryBack}/></div>:route==='developer-logs'?<div className="pw-workspace pw-route-workspace"><DeveloperDiagnosticsWorkspace onBack={returnFromSecondary} navigationTrigger={navigationTrigger}/></div>:route==='setup'?<div className="pw-workspace pw-route-workspace"><SetupWorkspace key={`setup:${setupMode}:${setupCapability||''}:${setupRuntimeTask||''}`} snapshot={snapshot} mode={setupMode} initialCapability={setupCapability} initialView={setupView} onViewChange={setSetupView} returnLabel={setupOrigin.current.label} originGoal={setupOrigin.current.goal} runtimeTaskId={setupRuntimeTask} onNavigate={navigateFromSetup} onClose={closeSetup} registerBackHandler={registerSecondaryBack}/></div>:route==='profile'?<div className="pw-workspace pw-route-workspace"><ProfileWorkspace initialTarget={personalTarget} targetRequestKey={String(personalTargetKey)} onOpenAdvancedMemory={()=>{void navigate('advanced-memory')}} onOpenCommitments={()=>{void navigate('commitments')}} onOpenReminders={()=>{void navigate('automation')}} state={state} presentationKey={`${personalDocumentEpoch}:${Number(snapshot?.view?.resumeRevision||0)}:${String(snapshot?.view?.taskId||'')}:${String(snapshot?.view?.section||'')}`} supported={can} run={bridge.command} onBack={returnFromProfile} onSettings={()=>{void navigate('group:data')}} registerBack={registerSecondaryBack} registerBeforeLeave={registerProfileBeforeLeave}/></div>:<Secondary cloudGoal={route==='remote'&&cloudGoal?.navigation===navigationRevision?cloudGoal:undefined} onCloudGoalUsed={consumeCloudGoal} teamRunFocus={teamRunFocus} onTeamRunFocusConsumed={requestId=>setTeamRunFocus(current=>current?.requestId===requestId?undefined:current)} onAskButlerForTeam={askButlerForTeam} onOpenTeamOwnerWork={openTeamOwnerWork} capabilityMemory={capabilityMemory.current} onStartCapability={startCapability} onTeamAttentionConsumed={requestId=>setTeamAttentionFocus(current=>current?.requestId===requestId?undefined:current)} teamAttentionFocus={teamAttentionFocus} registerBeforeLeave={registerSecondaryBeforeLeave} route={route} localDraft={localWorkspaceDraft} onLocalDraftChange={setLocalWorkspaceDraft} initialProjectId={setupReturnPending.current?setupOrigin.current.contextProjectId:relatedOrigin.current?.contextProjectId} originGoal={setupReturnPending.current?setupOrigin.current.goal:relatedOrigin.current?.goal} returnLabel={setupReturnPending.current?'返回能力准备':relatedOrigin.current?.label} onOpenProjects={()=>{if(relatedOrigin.current?.route==='projects')returnFromSecondary();else void openRelatedWorkspace('projects')}} onBack={returnFromSecondary} snapshot={snapshot} registerBackHandler={registerSecondaryBack} navigationTrigger={navigationTrigger} navigationRevision={navigationRevision} onNavigationPageChange={setSecondaryPage} onOpenSetup={can('setup.read')?(capability,origin)=>{void openSetup(capability,undefined,undefined,origin)}:undefined} settingsRestore={settingsRestore?.revision===navigationRevision?settingsRestore:undefined} onReturnToSetup={setupReturnPending.current?returnFromSecondary:undefined}/> }
  </div>{allWork}
  <ConversationHistoryWindow page={route==='chat'&&!taskOpen?state.conversationReader||null:null} activeDraft={intent==='chat'&&composer.ready===true&&draftKey&&state.conversationId&&composer.conversationId===state.conversationId?{conversation:state.conversationId,key:draftKey,epoch:draftEpoch}:undefined} submitting={!!state.chatSubmitting} can={can} act={act} registerBack={registerHistoryWindowBack} onCopy={copyWithLocalFeedback} onOpenTask={(id,entry,origin,alreadyOpen=false)=>openTask(id,entry,alreadyOpen,origin)}/>
  <ConversationQueuedInputs page={state.queuedInputsPage||null} can={can} act={act} registerBack={registerQueuedInputsBack}/>
  {searchOriginal&&<WorkSearchOriginalReader target={searchOriginal} identity={`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}`} onClose={closeSearchOriginal} onConversation={()=>{const target=searchOriginal;void openSearchResult({...target,ownerLocator:undefined}).catch(error=>setLocalError(error instanceof Error?error.message:String(error)))}} onCopy={copyWithLocalFeedback}/>}
  <AttachmentWorkspace state={state} registerBack={registerAttachmentBack} onReturnFocus={restoreTaskFileFocus} openTaskId={taskOpen?taskId||undefined:undefined} onReturnToSearch={searchReturn?showWorkSearch:undefined}
   draftTarget={fileDraftTarget} onStageFile={can('composer.object.add')?stageFileInDraft:undefined} onOpenDraft={showFileDraft}/>
  {organizationTarget&&<ConversationOrganization key={organizationTarget} conversationId={organizationTarget} onClose={()=>setOrganizationTarget(null)}/>}
  <Drawer.Root open={taskOpen} onOpenChange={open=>{if(!open){if(taskMenuOpen){setTaskMenuOpen(false);return}if(!deviceScopeBack.current?.()&&!capabilityBack.current?.()&&!livePreviewBack.current?.()&&!taskTransientBack.current?.())closeTask()}else setTaskOpen(true)}} direction="right" handleOnly shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay pw-work-object-overlay"/><Drawer.Content className="pw-drawer pw-task-drawer pw-work-object-surface pw-task-manga" data-task-layer={taskLayer} aria-describedby="pw-task-description" onCloseAutoFocus={event=>{
   if(taskOpen)return;
   if(route==='tasks'&&workListReturn.current?.()){event.preventDefault();return}
   const origin=projectTaskReturn.current;projectTaskReturn.current=null;
   if(!origin||route!=='projects'||state.projectId!==origin.projectId||state.filePath||projectSection!=='work'||projectPreview.expanded)return;
   const overview=projectScroll.current?.querySelector<HTMLElement>('.pw-project-overview');
   const row=Array.from(overview?.querySelectorAll<HTMLElement>('[data-project-task-id]')||[]).find(node=>node.dataset.projectTaskId===origin.taskId);
   const buttons=Array.from(row?.querySelectorAll<HTMLElement>('button:not(:disabled)')||[]);
   const receipt=Array.from(overview?.querySelectorAll<HTMLElement>('[data-project-receipt-task]')||[]).find(node=>node.dataset.projectReceiptTask===origin.taskId&&!node.closest('[hidden],[inert]'));
   const target=buttons.find(button=>button.dataset.workEntry===origin.entry)||buttons[0]||receipt||overview?.querySelector<HTMLElement>('input:not(:disabled),h2');
   if(target){event.preventDefault();if(target.tagName==='H2')target.tabIndex=-1;target.focus({preventScroll:true})}
  }} onEscapeKeyDown={event=>{if(taskMenuOpen){event.preventDefault();setTaskMenuOpen(false)}}}><header className="pw-drawer-header pw-task-header">
   <IconButton icon={IconArrowLeft} aria-label={taskLayer==='scope'?'返回工作目标':taskReconciliationOrigin.current?.openedId===taskId?'返回原工作':'返回原页面'} onClick={backFromTask}/>
   <div className="pw-task-header-copy"><Drawer.Title>{selectedTask?.goal||'工作'}</Drawer.Title><Drawer.Description id="pw-task-description" className="pw-sr-only">{taskLayer==='homeReport'?'原工作交付的只读汇报。返回管家后保留原页面和输入。':'这件工作的目标、成果、执行过程与当前决定。返回后保留原页面和输入。'}</Drawer.Description>
    {selectedPhoneSurface&&<p className="pw-field-hint pw-phone-surface-detail">{phoneSurfaceLabel(selectedPhoneSurface)}</p>}
    {selectedLocalPhone&&!selectedPhoneSurface&&selectedTask&&!terminal(selectedTask)&&<p className="pw-field-hint">原记录计划使用{(selectedTask.executionMode||selectedTask.raw?.executionMode)==='main_screen'?'主屏':'独立屏'}，当前未核实这次执行的手机连接。</p>}
    {selectedTask&&selectedWorkPresentation&&<div className="pw-task-header-status" data-task-status={selectedWorkPresentation.completed?'completed':selectedWorkPresentation.group==='attention'?'attention':selectedWorkPresentation.group==='running'?'running':selectedTask.status}>
     {selectedWorkPresentation.busy?<ProgressCircle aria-label={selectedWorkPresentation.label} isIndeterminate size="sm"/>:<span className="pw-task-state-dot" aria-hidden="true"/>}
     <span>{selectedWorkPresentation.label}</span>
    </div>}
   </div>
   {taskLayer!=='homeReport'&&<div className="pw-task-header-actions">
     {selectedTaskStop&&<IconButton icon={IconStop} aria-label={selectedTask?.status==='stopping'?'正在停止工作':'停止这件工作'} title={selectedTask?.status==='stopping'?'正在停止工作':'停止这件工作'} onClick={stopSelectedTask} disabled={selectedTaskUnavailable||selectedTask?.status==='stopping'||selectedPhoneSurface?.stopRequested||selectedWorkControl?.pending||selectedWorkControl?.unknown||selectedWorkControl?.usedForContinuation}
      data-phone-stop-task-id={selectedPhoneStop?.taskId} data-phone-stop-run-id={selectedPhoneStop?.runId} data-phone-stop-epoch={selectedPhoneStop?.epoch}/>}
    {selectedTask&&<WorkObjectMenu isOpen={taskMenuOpen} onOpenChange={setTaskMenuOpen} disabled={selectedTaskUnavailable} conversationPending={!!state.chatSubmitting||!!returningConversation} scopeDisabled={reviewPending||questionPending}
     onConversation={privateTaskConversationId(selectedTask)&&can('conversation.select')?()=>{void returnToTaskConversation(selectedTask)}:undefined}
     onProject={projects.some(project=>project.id===selectedTask.raw?.developmentContext?.projectId)&&can('projects.select')?()=>{void openTaskProject(selectedTask)}:undefined}
     onFiles={can('attachmentWorkspace.open')&&selectedTask.raw?.actorId==='owner'&&selectedTask.raw?.spaceId==='private'?()=>{const open=()=>run('attachmentWorkspace.open',{taskId:selectedTask.id});if(!taskPolicyBeforeLeave.current?.(open))open()}:undefined}
     onScope={can('tasks.review')?()=>{showTaskLayer('scope');if(!review||reviewStale)refreshReview()}:undefined}/>}
   </div>}
  </header>{selectedTask&&taskLayer!=='homeReport'&&selectedWorkControl?.message&&<div className="pw-task-control-feedback" role="status"><p>{selectedWorkControl.message}</p>{selectedWorkControl.unknown&&can('tasks.open')&&<Button variant="ghost" size="small" onClick={()=>openTask(selectedTask.id,taskLayer)}>核对原任务状态</Button>}</div>}{taskLayer!=='homeReport'&&selectedTask&&<>{taskErrors[selectedTask.id]&&<div className="pw-banner pw-banner-error pw-task-local-error" data-task-id={selectedTask.id} role="alert"><IconInfo size={17}/><p>{taskErrors[selectedTask.id]}</p></div>}{state.taskActionErrors?.[selectedTask.id]&&state.taskActionErrors[selectedTask.id]!==taskErrors[selectedTask.id]&&<div className="pw-banner pw-banner-error pw-task-local-error" data-task-id={selectedTask.id} role="alert"><IconInfo size={17}/><p>{state.taskActionErrors[selectedTask.id]}</p></div>}</>}<div ref={taskScroll} className="pw-drawer-body pw-task-body" onScroll={rememberTaskPosition}>{taskLayer!=='homeReport'&&selectedTask&&state.originalTaskNotice?.taskId===selectedTask.id&&<section className="pw-selected-object" aria-label="原任务通知"><h3>{state.originalTaskNotice.content?.title||"原任务提醒"}</h3><p style={{whiteSpace:"pre-wrap"}}>{state.originalTaskNotice.content?.body||"通知正文请在原任务执行过程的 post_notice 记录中核对。"}</p><p className="pw-muted">{state.originalTaskNotice.expired?"这条提醒属于先前的执行状态。":""}{state.originalTaskNotice.content?.message||"通知接口回执不代表已经显示或阅读。"}</p><Button variant="ghost" size="small" onClick={()=>run("notice.task.close")}>收起提醒</Button></section>}{selectedTask?<>{taskReviewBody(selectedTask)}</>:taskOpening?<ThinkingLoader phase="正在读取原任务"/>:<section className="pw-empty-small"><p className="pw-error" role="alert">{taskLayer==='homeReport'?'原工作或汇报已变化，请返回管家首页核对。':taskId&&taskErrors[taskId]||'原任务暂不可读取，请重新读取原历史窗口。'}</p>{taskLayer!=='homeReport'&&!noticeBinding&&taskId&&can('tasks.open')&&<Button variant="secondary" onClick={()=>openTask(taskId,taskLayer)}>重新读取原任务</Button>}</section>}</div>{selectedTask&&taskFooter(selectedTask)}</Drawer.Content></Drawer.Portal></Drawer.Root>
  <Drawer.Root open={!!phoneReview} onOpenChange={open=>{if(!open&&!phoneReviewPending)setPhoneReview(null)}} dismissible={!phoneReviewPending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-scope-drawer" aria-describedby="pw-phone-description" inert={!phoneReview} onCloseAutoFocus={phoneExit.onCloseAutoFocus}><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>核对这次手机任务</Drawer.Title><Drawer.Description id="pw-phone-description">目标、执行位置与本次范围</Drawer.Description></div><IconButton icon={IconClose} aria-label="关闭手机任务核对" disabled={phoneReviewPending} onClick={()=>setPhoneReview(null)}/></header><div className="pw-drawer-body">{phoneDisplay&&<PhoneScopeReview key={phoneExit.generation} shown={phoneDisplay} live={phoneReview} pending={phoneReviewPending} unknown={phoneStart?.status==='unknown'} onClose={()=>{if(phoneReviewLive.current)setPhoneReview(null)}} onStart={can('phone.start')?startReviewedPhone:undefined} onCheck={can('phone.prepare')?preparePhone:undefined} onSettings={can('phone.review.settings')?openPhoneReviewSettings:undefined} error={phoneReviewError?.handle===phoneReview?.reviewHandle?phoneReviewError?.message:undefined}/>}{phoneStart&&phoneStart.status!=='accepted'&&<><Receipt receipt={phoneStart}/>{phoneStart.status==='unknown'&&(phoneStart.draftRequestId?blankReceiptActions(phoneStart):phoneStart.requestId&&phoneStart.conversationId&&can('phone.query')?<Button variant="secondary" disabled={phoneReviewPending} onClick={()=>{if(phoneReviewLive.current)queryOriginalPhone()}}>查询原手机请求</Button>:<Button variant="secondary" onClick={()=>{if(!phoneReviewLive.current)return;setPhoneReview(null);if(phoneStart.taskId)openTask(phoneStart.taskId);else navigate('tasks')}}>核对原任务记录</Button>)}</>}</div></Drawer.Content></Drawer.Portal></Drawer.Root>
  <SettingsDialog isOpen={newProjectOpen||newFileOpen} title={createKind==='project'?'新项目':'新文件'} onClose={closeCreate} closeLabel="关闭创建" className="pw-create-dialog">
   <form className="pw-create-form" onSubmit={event=>{event.preventDefault();if(!createBusy&&(createKind==='project'?projectName:newPath).trim())createObject()}}>
    <TextField label={createKind==='project'?'项目名称':'文件路径'} isDisabled={createBusy} value={createKind==='project'?projectName:newPath} onChange={createKind==='project'?setProjectName:setNewPath} placeholder={createKind==='project'?'输入项目名称':'例如 index.html'}/>
    <footer><Button type="button" variant="ghost" onClick={closeCreate}>取消</Button><Button type="submit" disabled={createBusy||!(createKind==='project'?projectName:newPath).trim()}>{createBusy?'正在创建…':'创建'}</Button></footer>
   </form>
  </SettingsDialog>
 <Drawer.Root open={!!voiceReview} onOpenChange={open=>{if(!open)setVoiceReview(null)}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-scope-drawer" aria-describedby="pw-voice-description" inert={!voiceReview} onCloseAutoFocus={voiceExit.onCloseAutoFocus}><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>核对听写文字</Drawer.Title><Drawer.Description id="pw-voice-description">采用后放入原输入，暂不发送</Drawer.Description></div><Drawer.Close asChild><IconButton icon={IconClose} aria-label="关闭听写核对"/></Drawer.Close></header><div className="pw-drawer-body"><TextAreaField label="原听写文字" value={voiceText} isReadOnly={!voiceReview} onChange={value=>{if(voiceReviewLive.current)setVoiceText(value)}}/></div><footer className="pw-drawer-actions"><Button variant="secondary" disabled={voicePending||!voiceReview} onClick={()=>submitVoiceReview('voice.discard')}>丢弃</Button><PressButton label="采用到原输入" disabled={voicePending||!voiceReview||!voiceText.trim()} onClick={()=>submitVoiceReview('voice.accept')}/></footer></Drawer.Content></Drawer.Portal></Drawer.Root>
 <Drawer.Root open={!!state.chatAttachmentPickerOpen} onOpenChange={open=>{if(!open&&can('attachments.closePicker'))run('attachments.closePicker')}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-attachment-drawer" aria-describedby="pw-attachment-description"><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>选择附件</Drawer.Title><Drawer.Description id="pw-attachment-description">选择后随消息发送，最多 4 个</Drawer.Description></div><IconButton icon={IconClose} aria-label="关闭附件选择" onClick={()=>run('attachments.closePicker')}/></header><div className="pw-drawer-body">{!state.chatAttachmentModel?.supportsImageInput&&<p className="pw-muted">当前模型尚未配置图像输入，可选择受支持文档。</p>}{state.features?.attachments?.indexDiagnostics?.incomplete===true&&<p className="pw-error" role="alert">部分附件尚未核对完整，可读文件仍可选择。未确认的原件已保留，请核对后再备份。</p>}<div className="pw-attachment-objects">{state.attachmentImporting&&<ProgressBar label="正在导入附件库" isIndeterminate showValueLabel={false} size="sm"/>}{asArray<Json>(state.features?.attachments?.attachments).map(file=>{const selected=asArray<Json>(state.selectedChatAttachments).some(f=>f.id===file.id);const image=String(file.mimeType||'').startsWith('image/');return <div className="pw-attachment-object" key={`${file.id}:${file.sha256}`}><CheckboxField isSelected={selected} isDisabled={!can('attachments.toggle')||(!selected&&image&&!state.chatAttachmentModel?.supportsImageInput)} onChange={()=>run('attachments.toggle',{id:file.id,sha256:file.sha256})}><span className="pw-attachment-copy"><b>{file.name||'未命名附件'}</b><small>{file.mimeType||'文件'} · {Number(file.size||0).toLocaleString()} bytes</small></span></CheckboxField></div>})}</div>{!asArray<Json>(state.features?.attachments?.attachments).length&&<div className="pw-empty-small">{state.features?.attachments?.indexDiagnostics?.incomplete===true?'暂未读到可选择的附件，请核对未确认的原件。':'还没有附件，可以先导入。'}</div>}<p className="pw-muted">图片 ≤ 4 MiB，文档 ≤ 20 MiB。</p></div><footer className="pw-drawer-actions">{can('attachments.pick')&&<Button variant="secondary" leadingIcon={IconAdd} disabled={!!state.attachmentImporting} onClick={()=>run('attachments.pick')}>{state.attachmentImporting?'正在导入…':'导入新附件'}</Button>}<span className="pw-grow"/><PressButton label="保留选择" onClick={()=>run('attachments.closePicker')}/></footer></Drawer.Content></Drawer.Portal></Drawer.Root>
 <Drawer.Root open={permissionOptionsOpen} onOpenChange={open=>{if(!open)closePermissionOptions()}} dismissible={!permissionPending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-permission-drawer" aria-describedby="pw-permission-description" onCloseAutoFocus={permissionExit.onCloseAutoFocus}>
  <div className="pw-drawer-handle"/>
  <header className="pw-drawer-header"><div><Drawer.Title>执行权限</Drawer.Title><Drawer.Description id="pw-permission-description" className="sr-only">设置工具操作的审批方式，查看本次任务范围。</Drawer.Description></div><IconButton icon={IconClose} aria-label="关闭执行权限" onClick={closePermissionOptions} disabled={permissionPending}/></header>
  <div className="pw-drawer-body pw-permission-sheet-body">{permissionOptionsOpen&&<>
   {permissionView?.needsReview&&<div className="pw-permission-pending-review"><span role="status">{labelStatus(permissionView.status)}</span>{can('tasks.open')&&<Button variant="ghost" disabled={permissionPending} onClick={openPermissionTask}>{permissionReviewLabel}</Button>}</div>}
   {can('permissions.snapshot')?<PermissionPolicySettings key={String(state.conversationId||'')} conversationId={String(state.conversationId||'')} act={act} onPendingChange={permissionSaving} registerBeforeLeave={registerPermissionBeforeLeave}>{permissionTaskDetails}</PermissionPolicySettings>:<><p className="pw-muted">当前版本暂不支持修改审批设置。</p>{permissionTaskDetails}</>}

  </>}</div>
 </Drawer.Content></Drawer.Portal></Drawer.Root>

 {routeReferenceTarget&&<RouteReference key={`${routeReferenceTarget.taskId}:${routeReferenceTarget.runId}:${routeReferenceTarget.epoch}`} target={routeReferenceTarget} onClose={()=>setRouteReferenceTarget(null)}/>}
 {skillDraftTarget&&<SkillDraftReview key={`${skillDraftTarget.runId}:${skillDraftTarget.operationId}`} target={skillDraftTarget} onClose={()=>setSkillDraftTarget(null)}/>}
 {!preparationInline&&preparationDetail}
 {isChatSurface&&newMemoryScope&&<NewConversationMemory key={draftKey+':'+draftEpoch} composer={composer} isOpen={conversationMemoryOpen} onClose={()=>setConversationMemoryOpen(false)} registerBack={registerConversationMemoryBack}/>}
 {isChatSurface&&state.conversationId&&can('profile.conversation.read')&&<ConversationMemorySettings key={String(state.conversationId)} conversationId={String(state.conversationId)} composer={singleLearningScope?composer:undefined} isOpen={conversationMemoryOpen} onClose={()=>setConversationMemoryOpen(false)} onChanged={setConversationMemoryMode} onManage={()=>openPersonalMemory({view:'memories'})} registerBack={registerConversationMemoryBack}/>}
 {memoryDraftTarget&&<MemoryDraftReview key={`${memoryDraftTarget.runId}:${memoryDraftTarget.operationId}`} target={memoryDraftTarget} onOpenMemory={target=>{setMemoryDraftTarget(null);openPersonalMemory({view:'memory',...target})}} onClose={()=>setMemoryDraftTarget(null)}/>}
 {state.quickPanelVoice&&<QuickPanelVoiceDraft key={state.quickPanelVoice.handoffId} handoff={state.quickPanelVoice} input={obj(state.voiceInput)} run={act}/>}
 {showPhoneNotice&&phoneNotification&&<PhoneNotificationReturn key={phoneNotification.token} notice={phoneNotification} busy={phoneNotification.checking}
  changed={tasks.some(t=>t.id===phoneNotification.taskId&&(t.raw?.runId!==phoneNotification.runId||t.raw?.coordinatorEpoch!==phoneNotification.epoch))} onClose={returnFromPhoneNotice} onSame={can('tasks.open')?()=>enterPhoneNotification(phoneNotification):undefined}
  onRefresh={can('phone.notice.refresh')?()=>{void bridge.command('phone.notice.refresh',{noticeHandle:phoneNotification.token}).catch(()=>{})}:undefined}/>}
 {messageOriginTarget&&('phoneResult' in messageOriginTarget?<PhoneReturnedReport key={messageOriginTarget.phoneResult.deliveryId} target={messageOriginTarget} onClose={()=>setMessageOriginTarget(null)}/>:<MessageOriginReader key={`${messageOriginTarget.conversationId}:${messageOriginTarget.messageId}`} target={messageOriginTarget} onClose={()=>setMessageOriginTarget(null)}/>)}
 {messageQuoteTarget&&<MessageQuote key={`${messageQuoteTarget.key}:${messageQuoteTarget.epoch}:${messageQuoteTarget.messageId}`} target={messageQuoteTarget} onClose={()=>setMessageQuoteTarget(null)}/>}
 {providedMemoryMessage&&<ProvidedMemories key={providedMemoryMessage} messageId={providedMemoryMessage} revision={Number(state.memoryRefreshRevision||0)} onClose={()=>setProvidedMemoryMessage(null)} onManage={()=>{setProvidedMemoryMessage(null);void openPersonalMemory({view:'memories'})}} registerBack={registerProvidedMemoryBack}/>}
 {sourceSelection&&<AnswerSources key={sourceSelection.messageId||sourceSelection.taskId} target={sourceSelection} revision={Number(state.memoryRefreshRevision||0)} onClose={()=>setSourceSelection(null)}/>}
 <ReadingView documents={readingDocuments} onBack={closeReading}/>
 </div></Drawer.Root></ConversationModelPickerProvider></ReadingContext.Provider>;
}
