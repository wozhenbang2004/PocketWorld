import { FluidValueSlider } from './forms-library/uiux/FluidValueSlider';
/**
 * PocketWorld's controlled work surface.
 * Layout/regions are adapted from the selected Affect AssistantHome, AgentRun,
 * Files/Folder and ReviewCenter sources, retained under shell-library/upstream.
 * Real source components are imported below. Native task/draft/version authorities
 * remain in the host; a bridge acceptance never becomes a fabricated success.
 */
import {useCallback,useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {Brain,CalendarDays,Layers,MessageSquare,Monitor,MoreHorizontal,PanelLeftClose,PanelLeftOpen,Search,Settings,Sparkles,SquarePen,UserRound} from 'lucide-react';
import {UNSAFE_PortalProvider} from 'react-aria/PortalProvider';
import {RiArrowLeftLine,RiArrowRightSLine,RiArrowDownSLine,RiChatAiLine,RiCloseLine,RiCodeSSlashLine,RiFileLine,RiFolderLine,RiHistoryLine,RiLayoutGridLine,RiMenuLine,RiMoreLine,RiPlayLine,RiRefreshLine,RiSearchLine,RiSettings4Line,RiTeamLine,RiTerminalBoxLine,RiToolsLine,RiAddLine,RiInformationLine,RiCheckboxCircleLine,RiPauseCircleLine,RiErrorWarningLine,RiTimeLine,RiDoubleQuotesL,RiLinksLine,RiBookOpenLine,RiCalendarCheckLine,RiComputerLine,RiImage2Line,RiChatNewLine,RiNotification3Line,RiShieldCheckLine,RiBox3Line} from '@remixicon/react';
import {bridge} from './bridge';
import {usePocketSnapshot} from './usePocketSnapshot';
import {foregroundConversationStream} from './conversation-stream';
import {useConversationReading} from './useConversationReading';
import {usePocketAppearance} from './usePocketAppearance';
import {useClosingSnapshot,useMessageArrivals,useReviewFocus,useSurfaceEnter} from './library/surface-motion';
import {chatCancelFeedback,chatCancelIdentity,chatCancelKey} from './chat-cancel';
import {presentOperation,getRecentTaskProgress,taskNeedsAttention} from './task-presentation';
import {TaskHistory} from './TaskHistory';
import {composerHasPayload} from './composer-send-policy';
import {composerPermissionView} from './composer-permissions';
import {PermissionPolicySettings} from './PermissionPolicySettings';
import {taskHistoryIdentity} from './task-history';
import {TaskListHistoryControls,useTaskListHistory} from './TaskListHistory';
import {AgentAvatar,PressButton,ChatComposer,AIAnswer,MessageActions,ToolCall,ThinkingLoader,CitedAnswer,BlurCrossfade,Skeleton,ShimmerText,Toaster,toast,ProgressBar,ProgressCircle} from './library';
import {Button,IconButton,TextField,TextAreaField,SearchField,SelectField,RadioOptions,CheckboxField,Accordion,AccordionItem,SettingsDialog} from './forms-library';
import {Drawer,PopoverTrigger,Popover,PopoverHeader,TreeView,WorkSidebar,CommandMenu,FilesBrowser,Tabs,TabList,Tab,TabPanel,DecisionInbox,PermissionReview,type WorkCommand,type WorkNavItem} from './shell-library';
import Secondary from './Secondary';
import ProfileWorkspace from './ProfileWorkspace';
import DeveloperDiagnosticsWorkspace from './DeveloperDiagnosticsWorkspace';
import {SetupWorkspace,type SetupDestination} from './SetupWorkspace';
import {readSetup,type SetupCapability} from './setup-contract';
import {AttachmentWorkspace} from './AttachmentWorkspace';
import RemoteInboxWorkspace from './RemoteInboxWorkspace';
import {ConversationOrganization,orderConversations} from './ConversationOrganization';
import {ConversationQueuedInputs} from './ConversationQueuedInputs';
import {ConversationHistoryWindow} from './ConversationHistoryWindow';
import {ChatNavigationMenu} from './ChatNavigationMenu';
import {TaskCapabilityWorkspace} from './TaskCapabilityWorkspace';
import {ProjectRuntimeHealth} from './ProjectRuntimeHealth';
import {ProjectLivePreview} from './ProjectLivePreview';
import {ProjectExecutionWorkspace} from './ProjectExecutionWorkspace';
import {RemoteTaskWorkspace,RemoteManagedSessionsWorkspace} from './RemoteTaskWorkspace';
import {AnswerSources,type AnswerSourceTarget} from './AnswerSources';
import {MessageQuote,type MessageQuoteTarget} from './MessageQuote';
import {ReadingView} from './ReadingView';
import {ReadingContext,type ReadingDocument,type ReadingSnapshot} from './reading-context';
import {ComposerSourceReferences} from './ComposerSourceReferences';
import {ComposerPreviewReferences} from './ComposerPreviewReferences';
import {ProjectPreviewPane,useProjectPreview} from './ProjectPreviewPane';
import {ProjectFileEditor} from './ProjectFileEditor';
import {fileWorkspaceKey,type FileLayer} from './project-file';
import {useProjectRevision} from './useProjectRevision';
import {packageOperationTarget,packagePreviewChoices,type PackagePreviewChoices,type PackagePreviewTarget} from './project-preview';
import {TaskOutcome} from './TaskOutcome';
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
import {backgroundConversationWork,conversationActivityLabel,conversationInlineAttention,conversationTaskCompact,conversationWorkActionLabel,foregroundConversationTurn,messageTaskResult,ownerWorkIdentity,workIdentityKey,type ReplyContext,type TaskResult,type WorkIdentity} from './conversation-work';
import './conversation-work.css';
import {taskMessageAnchors} from './task-outcome';
import {OwnerTaskQuestion,useOwnerQuestionDraft} from './OwnerTaskQuestion';
import {AIModelPicker, type AIModelTarget, type AIModelPickerPresentation} from './AIModelPicker';
import {conversationModelName, conversationSelectionRequest, conversationSelectionReceipt} from './conversation-model-selection';
import {ConversationModelControls} from './ConversationModelControls';
import {ScopedConversationReasoningPanel} from './ScopedConversationModelControls';
import {workspaceNavigationKey} from './workspace-directory';
import type {OwnerQuestionView} from './owner-task-question';
import {phoneStartReceipt,phoneStartFailure,phoneRunIdentity,messagePhoneResult,phoneConversationId,type PhoneStartReceipt,type PhoneResult} from './phone-start';
import {PhoneViewer,type PhoneViewerTask} from './phone-viewer';
import './app.css';

type Json=Record<string,any>;
type Task={id:string;goal:string;status:string;summary?:string;executionMode?:string;updatedAt?:number;raw?:Json};
type Message={id?:string;role:string;content:string;at?:number;taskId?:string;submission?:Json;attachments?:Json[];memoryReference?:Json;retainedSources?:boolean;sourceRevision?:number;quoteAvailable?:boolean;phoneResult?:PhoneResult;replyContext?:ReplyContext;taskResult?:TaskResult};
type Project={id:string;name:string};
type Review={reviewHandle:string;task?:Task;ownerQuestion?:Json|null;actions?:{action:string;label:string}[];approvals?:Json[];unresolvedOperations?:Json[];projectContinuation?:Json|null};
type TaskReviewWindow={taskId:string;request:number;documentEpoch:number;resumeRevision:number};
type PhoneReview={reviewHandle:string;goal:string;mode:string;toolLimit:number;modelCallLimit:number;scopeMinutes:number;message?:string;requestId?:string;conversationId?:string;conversationTitle?:string;modelName?:string;provider?:string;screenDisclosure?:string};
type TaskLayer='overview'|'process'|'scope';
type FileDraft={text:string;baseline:string;version:number;dirty:boolean;submitted?:string};
type PackageNavigation={id:number;target:PackagePreviewTarget;documentEpoch:number;resumeRevision:number;navigationRevision:number;originalProjectId:string;destinationProjectId?:string;closed:Promise<boolean>};
const asArray=<T,>(x:any):T[]=>Array.isArray(x)?x:[];
const obj=(x:any):Json=>x&&typeof x==='object'&&!Array.isArray(x)?x:{};
const labelStatus=(s:string)=>({queued:'等待执行',preparing:'准备中',running:'执行中',pausing:'正在暂停',paused:'已暂停',stopping:'正在停止',verifying:'正在核对',needs_confirmation:'请确认结果',awaiting_user:'待你处理',waiting_user:'待你处理',waiting_approval:'等待授权',awaiting_approval:'等待授权',interrupted:'已中断',completed:'已完成',failed:'未完成',cancelled:'已停止',canceled:'已停止',stopped:'已停止',waiting_permission:'等待授权',reconciling:'等待核对动作',reconciliation_required:'等待核对动作',unknown:'等待核对动作'} as Record<string,string>)[s]||s||'待处理';
const running=(t:Task)=>['queued','preparing','running','pausing','stopping','verifying'].includes(t.status);
const attention=(t:Task)=>taskNeedsAttention(t);
const terminal=(t:Task)=>['completed','failed','cancelled','canceled','stopped'].includes(t.status);
function phoneViewerTask(task?:Task):PhoneViewerTask|null{
 const raw=task?.raw,identity=phoneRunIdentity(task);
 if(!task||!raw||!identity||raw.id!==task.id||raw.kind!=='phone'||raw.actorId!=='owner'||raw.spaceId!=='private'||task.executionMode!=='background'||raw.origin&&raw.origin.kind!=='team_phone')return null;
 return {...identity,title:task.goal,status:terminal(task)||task.status==='interrupted'?'ended':running(task)?'running':'paused'};
}
const taskBinding=(t?:Task|null)=>t?[t.id,t.updatedAt,t.raw?.runId,t.raw?.coordinatorEpoch,t.raw?.revision,t.raw?.resultReviewDigest,t.raw?.executionScope?.revision,t.raw?.executionScope?.executionPhase,t.raw?.executionScope?.planRevision,t.raw?.executionScope?.planArtifactRef,t.raw?.executionScope?.planHash].join(':'):'';
const timeLabel=(value:any)=>typeof value==='number'&&value>0?new Date(value).toLocaleString('zh-CN',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}):'';
const sourceRefs=(m:Message)=>asArray<Json>(m.memoryReference?.sources||m.memoryReference?.sourceRefs||m.submission?.sourceRefs).map((s,i)=>({...s,id:String(s.id||s.sourceId||s.sourceRef||i),title:s.title||s.name||s.label||'来源'}));
function treeData(files:Json[]){
 const roots:any[]=[];const nodes=new Map<string,any>();
 files.forEach(file=>{let parent=roots;let prefix='';String(file.path).split('/').filter(Boolean).forEach((part,index,parts)=>{prefix+=(prefix?'/':'')+part;let node=nodes.get(prefix);if(!node){node={id:prefix,name:part,icon:index===parts.length-1?RiFileLine:RiFolderLine,children:index===parts.length-1?undefined:[]};nodes.set(prefix,node);parent.push(node)}if(node.children)parent=node.children;});});return roots;
}
function useNativeDraft(owner:string,text:string,revision:any){
 const [value,setValue]=useState(text);const current=useRef({owner,dirty:false,value:text});const sent=useRef<string|null>(null);
 useEffect(()=>{const c=current.current;if(c.owner!==owner){current.current={owner,dirty:false,value:text};sent.current=null;setValue(text);return;}if(text===c.value){c.dirty=false;return;}if(!c.dirty||(sent.current!==null&&c.value===sent.current&&text==='')){c.value=text;c.dirty=false;sent.current=null;setValue(text);}},[owner,text,revision]);
 return {value,set:(next:string)=>{sent.current=null;current.current={owner,dirty:true,value:next};setValue(next)},markSent:()=>{sent.current=current.current.value},owner};
}
/** Only exceptional delivery states belong in the reading stream; successful receipts stay in native history. */
function Receipt({receipt}:{receipt:Json}){
 const status=String(receipt.status||receipt.state||'');
 if(!['failed','rejected','unconfirmed','unknown','pending','withdrawn'].includes(status))return null;
 const labels:Record<string,string>={failed:'发送未确认',rejected:'未发送',unconfirmed:'结果待确认',unknown:'结果待确认',pending:'正在确认',withdrawn:'已撤回'};
 const detail=receipt.message||receipt.reason;
 return <div className="pw-input-receipt" role="status"><span>{labels[status]}</span>{detail&&<small>{detail}</small>}</div>;
}

export default function App(){
 const [skillDraftTarget,setSkillDraftTarget]=useState<ConversationDraftTarget|null>(null);
 const [memoryDraftTarget,setMemoryDraftTarget]=useState<ConversationDraftTarget|null>(null);
 const [routeReferenceTarget,setRouteReferenceTarget]=useState<RouteReferenceTarget|null>(null);
 const snapshot=usePocketSnapshot() as any;const nativeState:Json=obj(snapshot?.state);const foregroundTurn=foregroundConversationTurn(nativeState);const state:Json={...nativeState,foregroundTurn,chatActiveTurn:foregroundTurn,chatBusy:!!foregroundTurn};const composer:Json=obj(snapshot?.composer);const phoneDraft:Json=obj(snapshot?.phoneDraft);
 usePocketAppearance(state.appearance);
 const commands=asArray<string>(snapshot?.capabilities?.commands);const commandKey=commands.join('\u0000');const commandSet=useMemo(()=>new Set(commands),[commandKey]);const can=useCallback((action:string)=>commandSet.has(action),[commandSet]);
 const messages=asArray<Message>(state.messages),tasks=asArray<Task>(state.tasks),projects=asArray<Project>(state.projects),files=asArray<Json>(state.files),conversations=asArray<Json>(state.conversations);
 const [route,setRoute]=useState('chat'),[workOpen,setWorkOpen]=useState(false),[commandOpen,setCommandOpen]=useState(false),[taskId,setTaskId]=useState<string|null>(null),[taskOpen,setTaskOpen]=useState(false),[filter,setFilter]=useState('all'),[taskQuery,setTaskQuery]=useState('');
 const profileBeforeLeave=useRef<((next:()=>void)=>boolean)|null>(null);
 const registerProfileBeforeLeave=useCallback((guard:((next:()=>void)=>boolean)|null)=>{profileBeforeLeave.current=guard},[]);
 const profileOrigin=useRef({route:'chat',drawerOpen:false,scrollTop:0});
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
 const setupOrigin=useRef<{route:string;task?:{id:string;layer:TaskLayer}}>({route:'chat'}),setupReturnPending=useRef(false),setupAutoChecked=useRef(false);
 const historyWindowBack=useRef<(()=>boolean)|null>(null);
 const registerHistoryWindowBack=useCallback((handler:(()=>boolean)|null)=>{historyWindowBack.current=handler},[]);
 const queuedInputsBack=useRef<(()=>boolean)|null>(null);
 const registerQueuedInputsBack=useCallback((handler:(()=>boolean)|null)=>{queuedInputsBack.current=handler},[]);
 const conversationControlsBack=useRef<(()=>boolean)|null>(null);
 const registerConversationControlsBack=useCallback((handler:(()=>boolean)|null)=>{conversationControlsBack.current=handler},[]);
 const navigationWindow=useRef(0);const [navigationRevision,setNavigationRevision]=useState(0),[secondaryPage,setSecondaryPage]=useState('');
 const [organizationTarget,setOrganizationTarget]=useState<string|null>(null);
 const [commandScope,setCommandScope]=useState<'all'|'conversations'>('all');
 const [desktopNavigation,setDesktopNavigation]=useState(()=>window.matchMedia('(min-width: 1024px)').matches);
 const [chatMenuOpen,setChatMenuOpen]=useState(false);const chatMenuTrigger=useRef<HTMLButtonElement>(null);
 const navigationReturnFocus=useRef<HTMLElement|null>(null);
 const [navigationMoreOpen,setNavigationMoreOpen]=useState(false);
 const navigationDrawer=useRef<HTMLDivElement>(null);
 useLayoutEffect(()=>{
  if(!workOpen||profileRestoreScroll.current===null)return;
  const frame=requestAnimationFrame(()=>{const body=navigationDrawer.current?.querySelector<HTMLElement>('.pw-drawer-body');if(body){body.scrollTop=profileRestoreScroll.current||0;profileRestoreScroll.current=null;}});
  return()=>cancelAnimationFrame(frame);
 },[workOpen]);
 useEffect(()=>{setChatMenuOpen(false)},[route,state.conversationId,desktopNavigation]);
 useEffect(()=>{setNavigationMoreOpen(false)},[route,state.conversationId,desktopNavigation,workOpen,commandOpen]);
 const [navigationCompact,setNavigationCompact]=useState(false);
 const effectiveCompact=desktopNavigation&&navigationCompact&&!commandOpen;
 useEffect(()=>{const media=window.matchMedia('(min-width: 1024px)');const update=()=>setDesktopNavigation(media.matches);update();media.addEventListener('change',update);return()=>media.removeEventListener('change',update)},[]);
 const previousDesktopNavigation=useRef(desktopNavigation);
 useEffect(()=>{if(previousDesktopNavigation.current===desktopNavigation)return;previousDesktopNavigation.current=desktopNavigation;setWorkOpen(!desktopNavigation&&commandOpen)},[desktopNavigation,commandOpen]);
 const workSearch=useRef<HTMLButtonElement>(null),conversationSearch=useRef<HTMLButtonElement>(null),previousCommandOpen=useRef(false);
 useEffect(()=>{if((workOpen||desktopNavigation)&&previousCommandOpen.current&&!commandOpen)(commandScope==='conversations'?conversationSearch:workSearch).current?.focus();previousCommandOpen.current=commandOpen;},[workOpen,desktopNavigation,commandOpen,commandScope]);
 const [taskLayer,setTaskLayer]=useState<TaskLayer>('overview'),[projectPickerOpen,setProjectPickerOpen]=useState(false),[fileDisplayOpen,setFileDisplayOpen]=useState(false);
 const [taskFiltersOpen,setTaskFiltersOpen]=useState(false),[endedExpanded,setEndedExpanded]=useState(false),[taskContextExpanded,setTaskContextExpanded]=useState(false);
 const [phoneViewerExpanded,setPhoneViewerExpanded]=useState(false),[phoneViewedTaskId,setPhoneViewedTaskId]=useState<string|null>(null);
 useEffect(()=>{setPhoneViewerExpanded(false)},[state.conversationId,route,taskId,taskOpen]);
 const projectScroll=useRef<HTMLElement>(null),projectListTop=useRef(new Map<string,number>());
 const projectFileBack=useRef<(()=>boolean)|null>(null);const registerProjectFileBack=useCallback((handler:(()=>boolean)|null)=>{projectFileBack.current=handler},[]);
 const [projectViews,setProjectViews]=useState<Record<string,{query:string;view:'files'|'tree'|'grid';expanded:string[];selected:string}>>({});
 const projectView=projectViews[String(state.projectId||'')]||{query:'',view:'files' as const,expanded:[],selected:''};
 const updateProjectView=(patch:Partial<typeof projectView>)=>setProjectViews(previous=>({...previous,[String(state.projectId||'')]:{...(previous[String(state.projectId||'')]||{query:'',view:'files',expanded:[],selected:''}),...patch}}));
 const fileQuery=projectView.query,fileView=projectView.view;const setFileQuery=(query:string)=>updateProjectView({query}),setFileView=(view:'files'|'tree'|'grid')=>updateProjectView({view});
 const taskScroll=useRef<HTMLDivElement>(null),taskReading=useRef(new Map<string,number>());
 const reviewRequest=useRef(0),openedTaskId=useRef<string|null>(null),secondaryBack=useRef<(()=>boolean)|null>(null);
 const [taskOpening,setTaskOpening]=useState(false);
 const [sourceSelection,setSourceSelection]=useState<AnswerSourceTarget|null>(null);
 const [messageQuoteTarget,setMessageQuoteTarget]=useState<MessageQuoteTarget|null>(null);
 const [workTarget,setWorkTarget]=useState<(WorkIdentity&{goal:string;conversationId:string})|null>(null);
 const [workControls,setWorkControls]=useState<Record<string,{pending:boolean;unknown:boolean;message:string}>>({});
 const workControlEntered=useRef(new Set<string>());
 const [readingDocuments,setReadingDocuments]=useState<ReadingDocument[]>([]);const readingSerial=useRef(0);
 const closeReading=useCallback(()=>{setReadingDocuments(previous=>previous.slice(0,-1))},[]);
 useEffect(()=>{setReadingDocuments([])},[state.conversationId,route,taskId,taskOpen]);
 const messageTaskAnchors=useMemo(()=>taskMessageAnchors(messages),[messages]);
 const conversationWork=backgroundConversationWork(tasks,asArray<Task>(state.ownerWork),state.conversationId);
 const targetTask=workTarget?tasks.find(task=>task.id===workTarget.taskId):null;
 const currentTargetIdentity=ownerWorkIdentity(targetTask);
 const workTargetCurrent=!!workTarget&&workTarget.conversationId===state.conversationId&&!!currentTargetIdentity&&workIdentityKey(workTarget)===workIdentityKey(currentTargetIdentity)&&targetTask?.raw?.controls?.canInput===true;
 const conversationViewerTasks=tasks.filter(task=>phoneConversationId(task)===state.conversationId&&phoneViewerTask(task)!==null);
 const chatPhoneTask=conversationViewerTasks.find(task=>task.id===phoneViewedTaskId)||conversationViewerTasks.find(task=>!terminal(task)&&task.status!=='interrupted')||conversationViewerTasks[0];
 useEffect(()=>{if(chatPhoneTask?.id!==phoneViewedTaskId)setPhoneViewedTaskId(chatPhoneTask?.id||null)},[chatPhoneTask?.id,phoneViewedTaskId]);
 useEffect(()=>{setSkillDraftTarget(null);setMemoryDraftTarget(null);setRouteReferenceTarget(null);setMessageQuoteTarget(null)},[state.conversationId,route]);
 useEffect(()=>{setRouteReferenceTarget(null)},[taskId,taskOpen]);
 const openConversationDraft=(draft:ConversationDraftTarget&{kind:string})=>{if(draft.kind==='skill'){setMemoryDraftTarget(null);setSkillDraftTarget(draft)}else{setSkillDraftTarget(null);setMemoryDraftTarget(draft)}};
 const taskTransientBack=useRef<(()=>boolean)|null>(null);
 const capabilityBack=useRef<(()=>boolean)|null>(null);
 const livePreviewBack=useRef<(()=>boolean)|null>(null);
 const registerLivePreviewBack=useCallback((handler:(()=>boolean)|null)=>{livePreviewBack.current=handler},[]);
 const registerCapabilityBack=useCallback((handler:(()=>boolean)|null)=>{capabilityBack.current=handler},[]);
 const registerTaskBack=useCallback((handler:(()=>boolean)|null)=>{taskTransientBack.current=handler},[]);
 const attachmentBack=useRef<(()=>boolean)|null>(null);
 const registerAttachmentBack=useCallback((handler:(()=>boolean)|null)=>{attachmentBack.current=handler},[]);
 const registerSecondaryBack=useCallback((handler:(()=>boolean)|null)=>{secondaryBack.current=handler},[]);
 const [taskErrors,setTaskErrors]=useState<Record<string,string>>({});
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
 const [phoneStart,setPhoneStart]=useState<PhoneStartReceipt|null>(null);
 const phoneAction=useRef(false),phoneWindow=useRef(0),phoneReviewLive=useRef(phoneReview);phoneReviewLive.current=phoneReview;
 const phonePresentation=useMemo(()=>phoneReview?{goal:phoneReview.goal,mode:phoneReview.mode,message:phoneReview.message,screenDisclosure:phoneReview.screenDisclosure,conversationId:phoneReview.conversationId,conversationTitle:phoneReview.conversationTitle,modelName:phoneReview.modelName,toolLimit:phoneReview.toolLimit,modelCallLimit:phoneReview.modelCallLimit,scopeMinutes:phoneReview.scopeMinutes}:null,[phoneReview]);
 const phoneExit=useClosingSnapshot(phonePresentation),phoneDisplay=phoneExit.display;
 const [scopeMinutes,setScopeMinutes]=useState('-1');const [newProjectOpen,setNewProjectOpen]=useState(false),[projectName,setProjectName]=useState(''),[newFileOpen,setNewFileOpen]=useState(false),[newPath,setNewPath]=useState(''),[fileModes,setFileModes]=useState<Record<string,FileLayer>>({}),[fileDrafts,setFileDrafts]=useState<Record<string,FileDraft>>({});
 const channelPhoneModel=intent!=='chat'&&phoneDraft.channelOrigin===true;
 const composerModelName=channelPhoneModel?String(state.modelName||''):conversationModelName(state);
 const createWindow=useRef(0),createPending=useRef(new Set<number>());const [createBusy,setCreateBusy]=useState(false),[createKind,setCreateKind]=useState<'project'|'file'>('project');
 const closeCreate=()=>{createWindow.current++;setNewProjectOpen(false);setNewFileOpen(false);setCreateBusy(false)};
 const openCreate=(kind:'project'|'file')=>{createWindow.current++;setCreateKind(kind);setCreateBusy(false);setNewProjectOpen(kind==='project');setNewFileOpen(kind==='file')};
 const [modelPickerOpen,setModelPickerOpen]=useState(false),[modelSelectPending,setModelSelectPending]=useState(false),[modelPickerConfirmed,setModelPickerConfirmed]=useState(false);
 const modelWindow=useRef(0),modelWriting=useRef(false),modelPickerAnchor=useRef<HTMLButtonElement>(null),modelPickerScroll=useRef<HTMLDivElement>(null);const pickerToken=modelWindow.current;
 const [modelPickerSection,setModelPickerSection]=useState<'models'|'reasoning'>('models'),[modelPickerPresentation,setModelPickerPresentation]=useState<AIModelPickerPresentation>({query:'',filter:'all'});const modelPickerScope=useRef(state.conversationModel?.scopeKey);
 const finishModelPicker=()=>{modelWindow.current++;modelWriting.current=false;setModelPickerOpen(false);setModelSelectPending(false);setModelPickerConfirmed(false)};
 useEffect(()=>{if(modelPickerScope.current!==state.conversationModel?.scopeKey){finishModelPicker();setModelPickerPresentation({query:'',filter:'all'})}modelPickerScope.current=state.conversationModel?.scopeKey},[state.conversationModel?.scopeKey]);
 useEffect(()=>{if(modelPickerOpen&&modelPickerConfirmed&&!modelSelectPending&&!modelWriting.current&&!state.modelSelectionBusy)finishModelPicker()},[modelPickerOpen,modelPickerConfirmed,modelSelectPending,state.modelSelectionBusy]);
 const [voiceReview,setVoiceReview]=useState<any>(null),[voiceText,setVoiceText]=useState(''),[voicePending,setVoicePending]=useState(false);const [voiceOptionsOpen,setVoiceOptionsOpen]=useState(false),[permissionOptionsOpen,setPermissionOptionsOpen]=useState(false),[messageMenu,setMessageMenu]=useState<string|null>(null),[expandedCalls,setExpandedCalls]=useState<Record<string,boolean>>({});
 const [permissionPending,setPermissionPending]=useState(false);const permissionWriting=useRef(false),permissionConversation=useRef(state.conversationId);
 const permissionExit=useReviewFocus(permissionOptionsOpen);
 const permissionSaving=useCallback((pending:boolean)=>{permissionWriting.current=pending;setPermissionPending(pending)},[]);
 const closePermissionOptions=useCallback(()=>{if(!permissionWriting.current)setPermissionOptionsOpen(false)},[]);
 useEffect(()=>{if(permissionConversation.current!==state.conversationId){setPermissionOptionsOpen(false);permissionWriting.current=false;setPermissionPending(false)}permissionConversation.current=state.conversationId},[state.conversationId]);
 const voiceReviewLive=useRef(voiceReview),voiceWriting=useRef(false);voiceReviewLive.current=voiceReview;
 const voiceExit=useReviewFocus(!!voiceReview);
 const workspace=useRef<HTMLDivElement>(null);
 const scroll=useRef<HTMLDivElement>(null);
 const conversationStream=foregroundConversationStream(state);
 const streamMarker=conversationStream?`${conversationStream.requestId}:${conversationStream.generation}:${conversationStream.sequence}`:'';
 const {content:chatContent,hasNewReplies,saveReading,rememberChatPosition,goToLatest,stopFollowing}=useConversationReading({scroll,active:route==='chat',conversationId:String(state.conversationId||''),composer,messages,streamMarker,paused:readingDocuments.length>0||!!state.conversationReader||!!state.queuedInputsPage});
 const openReading=useCallback((document:ReadingSnapshot)=>{rememberChatPosition();const entry={...document,key:++readingSerial.current};setReadingDocuments(previous=>[...previous,entry])},[rememberChatPosition]);
 const activeTask=tasks.find(x=>x.id===state.activeTaskId);const activePhoneIdentity=phoneRunIdentity(activeTask);const selectedTask=tasks.find(x=>x.id===taskId);const currentProject=projects.find(x=>x.id===state.projectId);const currentConversation=conversations.find(x=>x.id===state.conversationId);
 const chatOwner=`${composer.key||''}:${composer.epoch??''}`;const chatDraft=useNativeDraft(chatOwner,String(composer.text||''),composer.revision);const phoneOwner=`phone:${phoneDraft.epoch??''}`;const phoneText=useNativeDraft(phoneOwner,String(phoneDraft.text||''),phoneDraft.revision);
 const text=intent==='chat'?chatDraft.value:phoneText.value;
 useEffect(()=>{phoneWindow.current++;setPhoneReview(null)},[state.conversationId,chatOwner,phoneOwner]);
 const shownFiles=useMemo(()=>files.filter(f=>String(f.path).toLocaleLowerCase().includes(fileQuery.toLocaleLowerCase())),[files,fileQuery]);
 const currentFileKey=fileWorkspaceKey(state.projectId,state.filePath);const fileDraft=currentFileKey?fileDrafts[currentFileKey]:undefined;
 const fileMode=fileModes[currentFileKey]||'edit';const setFileMode=(mode:FileLayer)=>setFileModes(previous=>({...previous,[currentFileKey]:mode}));
 const fileEditRevisions=useRef(new Map<string,number>());
 const fileAdoption=useProjectRevision({key:currentFileKey,active:route==='projects'&&!!state.filePath,allowed:can('projects.useRevision')&&!!state.fileReady&&!state.fileLoading&&!state.revisionLoading&&!!fileDraft&&fileDraft.version===Number(state.fileVersion||0),saving:!!state.fileSaving,localCurrent:()=>({editRevision:fileEditRevisions.current.get(currentFileKey)||0,navigationRevision:navigationWindow.current}),current:fileDraft&&typeof state.revisionContent==='string'&&Number.isInteger(state.revisionNumber)?{projectId:String(state.projectId),path:String(state.filePath),version:fileDraft.version,revision:state.revisionNumber,content:state.revisionContent,draft:fileDraft.text,editRevision:fileEditRevisions.current.get(currentFileKey)||0,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),navigationRevision}:null,apply:original=>{setFileDrafts(previous=>{const prior=previous[currentFileKey];if(!prior||prior.version!==original.version||prior.text!==original.draft)return previous;return {...previous,[currentFileKey]:{...prior,text:original.content,dirty:original.content!==prior.baseline,submitted:undefined}}});fileEditRevisions.current.set(currentFileKey,(fileEditRevisions.current.get(currentFileKey)||0)+1);setFileMode('edit')}});
 const fileIdentity=useMemo(()=>({projectId:state.projectId,path:state.filePath,version:fileDraft?.version??Number(state.fileVersion||0)}),[state.projectId,state.filePath,fileDraft?.version,state.fileVersion]);
 const isPrimaryRoute=route==='chat'||route==='tasks'||route==='projects';
 const ready=commands.length>0;const currentTitle=route==='tasks'?'任务':route==='projects'?(state.filePath?String(state.filePath).split('/').pop()||'文件':currentProject?.name||'项目'):route==='chat'?(currentConversation?.title||'新对话'):'设置与连接';
 const selectedBinding=taskBinding(selectedTask);const reviewedBinding=taskBinding(review?.task);const reviewStale=!!review&&!!selectedTask&&reviewedBinding!==selectedBinding;
 const isListening=!!state.listening||!!state.voiceInput?.active;
 const draftKey=String(composer.key||'');const draftEpoch=Number(composer.epoch||0);
 const pendingInputs:(Json&{receipt:Json;message?:Message})[]=asArray<Json>(composer.pending).filter(p=>typeof p.clientMessageId==='string'&&p.clientMessageId).map(p=>({...p,receipt:obj(p.receipt||state.chatReceipts?.[p.clientMessageId]),message:messages.find(m=>m.role==='user'&&m.submission?.clientMessageId===p.clientMessageId)}));
 const act=useCallback(async(action:string,args:Json={},errorTaskId?:string)=>{
  const errorOwner=errorTaskId||(action.startsWith('tasks.')?String(args.id||''):'');
  const rememberError=(message:string)=>{if(errorOwner)setTaskErrors(previous=>({...previous,[errorOwner]:message}));};
  if(!can(action)){const message='这个操作当前尚不可用。';if(!errorOwner)setLocalError(message);rememberError(message);throw new Error(message)}
  setLocalError('');if(errorOwner)setTaskErrors(previous=>previous[errorOwner]?{...previous,[errorOwner]:''}:previous);
  return bridge.command(action,args).then(value=>{if(errorOwner)toast.dismiss(`command-error:${action}`);return value}).catch(error=>{const message=error instanceof Error?error.message:String(error);rememberError(message);if(!errorOwner)toast.error(message,{id:`command-error:${action}`});throw error;});
 },[can]);
 const run=useCallback((action:string,args:Json={},onAccepted?:(value:any)=>void)=>{void act(action,args).then(value=>{onAccepted?.(value)}).catch(error=>{if(!action.startsWith('tasks.')||!args.id)setLocalError(error instanceof Error?error.message:String(error))});},[act]);
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
  else{phoneWindow.current++;phoneText.set(next);setPhoneReview(null);if(can('phone.draft.edit'))run('phone.draft.edit',{text:next,epoch:phoneDraft.epoch});}
 },[intent,chatDraft,phoneText,can,run,draftKey,draftEpoch,phoneDraft.epoch]);
 const rememberTaskPosition=useCallback(()=>{const node=taskScroll.current;if(taskId&&node?.isConnected)taskReading.current.set(`${taskId}:${taskLayer}`,node.scrollTop)},[taskId,taskLayer]);
 const showTaskLayer=useCallback((next:TaskLayer)=>{rememberTaskPosition();setTaskLayer(next)},[rememberTaskPosition]);
 const closeTask=useCallback(()=>{rememberTaskPosition();invalidateContinuation();reviewWindow.current=null;reviewRequest.current++;openedTaskId.current=null;setTaskOpening(false);const closed=can('tasks.close')?act('tasks.close').then(()=>true,()=>false):Promise.resolve(false);setTaskOpen(false);setTaskLayer('overview');setReview(null);setReviewPending(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionAnswer('');return closed;},[can,act,rememberTaskPosition,invalidateContinuation]);
 const changeNavigation=useCallback((open:boolean)=>{setWorkOpen(open);if(!open)setCommandOpen(false)},[]);
 // Open mobile navigation across the screen without taking over scrolling or text selection.
 useEffect(()=>{
  if(desktopNavigation||workOpen)return;
  let gesture:{id:number;x:number;y:number;horizontal:boolean}|null=null;
  const reset=()=>{gesture=null};
  const cancelSelection=()=>{if(window.getSelection()?.toString())reset()};
  const start=(event:TouchEvent)=>{
   reset();
   if(event.touches.length!==1||document.hidden)return;
   const touch=event.touches[0],target=event.target;
   if(!(target instanceof Element)||!target.closest('.pw-app'))return;
   if(target.closest('input,textarea,select,button,a,[role="slider"],[contenteditable="true"],[data-vaul-no-drag]')||document.querySelector('[role="dialog"][data-state="open"],[role="alertdialog"]')||window.getSelection()?.toString())return;
   for(let node:Element|null=target;node&&!node.classList.contains('pw-app');node=node.parentElement){
    if(node.scrollWidth>node.clientWidth+1&&/auto|scroll/.test(getComputedStyle(node).overflowX))return;
   }
   gesture={id:touch.identifier,x:touch.clientX,y:touch.clientY,horizontal:false};
  };
  const move=(event:TouchEvent)=>{
   if(!gesture)return;
   if(event.touches.length!==1||window.getSelection()?.toString()){reset();return}
   const touch=Array.from(event.touches).find(item=>item.identifier===gesture!.id);
   if(!touch){reset();return}
   const dx=touch.clientX-gesture.x,dy=Math.abs(touch.clientY-gesture.y);
   if(!gesture.horizontal){
    if(dy>12&&dy>=Math.abs(dx)||dx< -12){reset();return}
    if(dx<12||dx<dy*1.5)return;
    gesture.horizontal=true;
   }
   if(event.cancelable)event.preventDefault();
  };
  const end=(event:TouchEvent)=>{
   const current=gesture;reset();
   if(!current||!current.horizontal||window.getSelection()?.toString())return;
   const touch=Array.from(event.changedTouches).find(item=>item.identifier===current.id);
   if(!touch||touch.clientX-current.x<48||Math.abs(touch.clientY-current.y)>Math.max(32,(touch.clientX-current.x)/1.5))return;
   if(event.cancelable)event.preventDefault();
   changeNavigation(true);
  };
  document.addEventListener('touchstart',start,{passive:true});
  document.addEventListener('touchmove',move,{passive:false});
  document.addEventListener('touchend',end,{passive:false});
  document.addEventListener('touchcancel',reset);
  document.addEventListener('visibilitychange',reset);
  document.addEventListener('contextmenu',reset);
  document.addEventListener('selectionchange',cancelSelection);
  return()=>{
   document.removeEventListener('touchstart',start);document.removeEventListener('touchmove',move);
   document.removeEventListener('touchend',end);document.removeEventListener('touchcancel',reset);
   document.removeEventListener('visibilitychange',reset);
   document.removeEventListener('contextmenu',reset);document.removeEventListener('selectionchange',cancelSelection);
  };
 },[desktopNavigation,workOpen,changeNavigation]);
 const navigate=useCallback((next:string,afterNavigate?:()=>void)=>{
  const commit=()=>{
   if(next==='profile'&&route!=='profile')profileOrigin.current={route:!['chat','tasks','projects','setup','remoteInbox','developer-logs'].includes(route)&&secondaryPage?secondaryPage:route,drawerOpen:workOpen,scrollTop:navigationDrawer.current?.querySelector<HTMLElement>('.pw-drawer-body')?.scrollTop||0};
   setupReturnPending.current=false;rememberChatPosition();setWorkOpen(false);setCommandOpen(false);
   const closed=taskOpen?closeTask():Promise.resolve(true);navigationWindow.current++;setNavigationRevision(navigationWindow.current);setRoute(next);afterNavigate?.();return closed;
  };
  if(route==='profile'&&next!=='profile'){
   setWorkOpen(false);setCommandOpen(false);
   if(profileBeforeLeave.current?.(()=>{void commit()}))return Promise.resolve(false);
  }
  return commit();
 },[route,secondaryPage,workOpen,taskOpen,closeTask,rememberChatPosition]);
 const returnFromProfile=()=>{const origin=profileOrigin.current;void navigate(origin.route==='profile'?'chat':origin.route,()=>{if(origin.drawerOpen&&!desktopNavigation){profileRestoreScroll.current=origin.scrollTop;setWorkOpen(true)}})};
 const openSetup=useCallback((capability?:SetupCapability,runtimeTaskId?:string)=>{
  setupOrigin.current={route:!isPrimaryRoute&&secondaryPage?secondaryPage:route,...(taskOpen&&taskId?{task:{id:taskId,layer:taskLayer}}:{})};
  setSetupMode('center');setSetupCapability(capability);setSetupRuntimeTask(runtimeTaskId);void navigate('setup');
 },[isPrimaryRoute,secondaryPage,route,taskOpen,taskId,taskLayer,navigate]);
 const navigateFromSetup=(destination:SetupDestination)=>{const target={connections:'model-settings',background:'background',appearance:'phone:appearance',local:'local'}[destination];void navigate(target);setupReturnPending.current=true;};
 const closeSetup=()=>{const origin=setupOrigin.current;setupReturnPending.current=false;void navigate(origin.route==='setup'?'chat':origin.route).then(closed=>{if(closed&&origin.task)void openTask(origin.task.id,origin.task.layer==='scope'?'scope':'review')});};
 const returnFromSecondary=()=>{if(route==='profile'){returnFromProfile();return;}if(setupReturnPending.current){void navigate('setup');return;}void navigate('chat');};
 useEffect(()=>{
  if(!ready||!can('setup.read')||setupAutoChecked.current||route!=='chat'||taskOpen||foregroundTurn||state.activeTaskId)return;
  let active=true;const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  void bridge.command('setup.read').then(raw=>{
   if(!active||navigationWindow.current!==navigation||documentEpoch!==bridge.getDocumentEpoch())return;
   const actual=readSetup(raw);setupAutoChecked.current=true;
   if(actual.preferences.status==='new'||actual.preferences.status==='in_progress'){
    setupOrigin.current={route:'chat'};setSetupMode('onboarding');setSetupCapability(undefined);setSetupRuntimeTask(undefined);void navigate('setup');
   }
  }).catch(()=>{});
  return()=>{active=false};
 },[ready,can,route,taskOpen,foregroundTurn?.taskId,state.activeTaskId,state.conversationId,snapshot?.view?.resumeRevision,navigate]);
 const remoteInboxOpenRevision=Number(state.remoteInbox?.openRevision||0);
 const lastRemoteInboxOpen=useRef(0);
 useEffect(()=>{if(remoteInboxOpenRevision>0&&remoteInboxOpenRevision!==lastRemoteInboxOpen.current){lastRemoteInboxOpen.current=remoteInboxOpenRevision;navigate('remoteInbox');}},[remoteInboxOpenRevision,navigate]);
 const loadTaskReview=useCallback((id:string)=>{
  if(!can('tasks.review')||openedTaskId.current!==id)return;invalidateContinuation();const request=++reviewRequest.current;setReviewPending(true);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);
  const window={taskId:id,request,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(bridge.getState().view).resumeRevision||0)};reviewWindow.current=window;
  void act('tasks.review',{id},id).then(value=>{if(!currentReviewWindow(window))return;setReview(value);const raw=obj(value.task?.raw);setScopeTools(String(raw.toolLimit||raw.executionScope?.toolLimit||raw.scope?.toolLimit||''));setScopeModels(String(raw.modelCallLimit||raw.executionScope?.modelCallLimit||raw.scope?.modelCallLimit||''));}).catch(()=>{if(reviewRequest.current===request)setReview(null)}).finally(()=>{if(reviewRequest.current===request)setReviewPending(false)});
 },[can,act,currentReviewWindow,invalidateContinuation]);
 const openTask=useCallback((id:string,entry:TaskLayer|'review'='overview',nativeWindowAlreadyOpen=false)=>{
  invalidateContinuation();
  rememberTaskPosition();setTaskContextExpanded(false);const request=++reviewRequest.current;const readReview=entry==='review'||entry==='scope'||tasks.some(task=>task.id===id&&attention(task));openedTaskId.current=null;setReviewPending(false);setTaskOpening(true);setWorkOpen(false);setCommandOpen(false);setTaskId(id);setTaskLayer(entry==='review'?'overview':entry);setTaskOpen(true);setReview(null);setReviewNote('');setReviewFeedbackOpen(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);
  reviewWindow.current={taskId:id,request,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(bridge.getState().view).resumeRevision||0)};
  // Open the original host window before obtaining its single-use review handle.
  // Internal task opens do not emit another external-navigation request.
  void (nativeWindowAlreadyOpen?Promise.resolve():act('tasks.open',{id,requestNavigation:false},id)).then(()=>{if(reviewRequest.current!==request)return;openedTaskId.current=id;setTaskOpening(false);if(readReview)loadTaskReview(id)}).catch(()=>{if(reviewRequest.current===request)setTaskOpening(false)});
 },[act,loadTaskReview,tasks,rememberTaskPosition,invalidateContinuation]);
 useEffect(()=>{
  const invalidate=()=>{const original=reviewWindow.current;if(!original||!document.hidden&&bridge.getDocumentEpoch()===original.documentEpoch&&Number(obj(bridge.getState().view).resumeRevision||0)===original.resumeRevision)return;invalidateContinuation();reviewWindow.current=null;reviewRequest.current++;openedTaskId.current=null;setReview(null);setReviewPending(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);setTaskOpening(false)};
  const unsubscribe=bridge.subscribe(invalidate);document.addEventListener('visibilitychange',invalidate);
  return()=>{unsubscribe();document.removeEventListener('visibilitychange',invalidate)};
 },[invalidateContinuation]);
 const backFromTask=useCallback(()=>{if(capabilityBack.current?.()||livePreviewBack.current?.()||taskTransientBack.current?.())return;if(taskLayer!=='overview'){showTaskLayer('overview');return}closeTask()},[taskLayer,showTaskLayer,closeTask]);
 const selectConversation=useCallback((id:string)=>{void navigate('chat',()=>{if(can('conversation.select'))run('conversation.select',{id})});},[can,run,navigate]);
 const previewBlocked=!!(permissionOptionsOpen||organizationTarget||state.attachmentWorkspace?.open||phoneViewerExpanded||workOpen||commandOpen||taskOpen||projectPickerOpen||fileDisplayOpen||newProjectOpen||newFileOpen||modelPickerOpen||phoneReview||voiceReview||state.chatAttachmentPickerOpen||messageQuoteTarget||sourceSelection||skillDraftTarget||memoryDraftTarget||routeReferenceTarget||state.quickPanelVoice);
 const projectPreview=useProjectPreview({value:state.projectPreview,projectId:String(state.projectId||''),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),active:route==='projects'&&!state.filePath,blocked:previewBlocked,can,onReturned:()=>navigate('chat'),onPackageTask:(id,entry='process')=>openTask(id,entry)});
 const packageSequence=useRef(0),packageNavigation=useRef<PackageNavigation|null>(null);
 const [packageRequest,setPackageRequest]=useState(0),[packageOpening,setPackageOpening]=useState(false),[packageReady,setPackageReady]=useState<{request:PackageNavigation;choices:PackagePreviewChoices}|null>(null);
 const packageLocation=useRef({route,navigationRevision,projectId:String(state.projectId||''),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),blocked:previewBlocked});
 packageLocation.current={route,navigationRevision,projectId:String(state.projectId||''),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),blocked:previewBlocked};
 const packageCurrent=(request:PackageNavigation)=>{const now=packageLocation.current;return packageNavigation.current===request&&!document.hidden&&bridge.getDocumentEpoch()===request.documentEpoch&&now.resumeRevision===request.resumeRevision&&now.route==='projects'&&now.navigationRevision===request.navigationRevision&&!now.blocked&&(now.projectId===request.originalProjectId||!!request.destinationProjectId&&now.projectId===request.destinationProjectId)};
 const cancelPackageNavigation=()=>{packageNavigation.current=null;setPackageOpening(false);setPackageReady(null)};
 const openTaskPackage=(target:PackagePreviewTarget)=>{
  if(packageNavigation.current||taskOpening||!can('projects.package.preview.inspect')||!can('projects.package.preview.open')||!can('tasks.close'))return;
  const id=++packageSequence.current,closed=navigate('projects');
  packageNavigation.current={id,target,closed,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),navigationRevision:navigationRevision+1,originalProjectId:String(state.projectId||'')};
  setPackageReady(null);setPackageOpening(true);setPackageRequest(id);
 };
 useEffect(()=>{
  const request=packageNavigation.current;if(!request||request.id!==packageRequest)return;
  let mounted=true;
  void(async()=>{
   if(!await request.closed)throw new Error('原任务窗口尚未关闭，请回原任务核对后再打开固定包。');
   if(!mounted||!packageCurrent(request))return;
   const result=await bridge.command('projects.package.preview.inspect',request.target);
   if(!mounted||!packageCurrent(request))return;
   const choices=packagePreviewChoices(result,request.target);
   if(!choices)throw new Error('固定包描述与原任务操作不符，未切换到另一份成果。');
   request.destinationProjectId=choices.projectId;
   const observed=bridge.getState().state;
   if(observed.projectId!==choices.projectId||observed.filePath){
    if(!can('projects.select'))throw new Error('原项目导航暂不可用，草稿仍保留。');
    await bridge.command('projects.select',{id:choices.projectId});
   }
   if(!mounted||!packageCurrent(request))return;
   await new Promise<void>((resolve,reject)=>{
    let unsubscribe=()=>{};const timer=window.setTimeout(()=>{unsubscribe();reject(new Error('尚未读取到原项目页面，未再次打开或开始工作。'))},5000);
    const check=()=>{if(!mounted||!packageCurrent(request)){clearTimeout(timer);unsubscribe();resolve();return}const latest=bridge.getState().state;if(latest.projectId===choices.projectId&&!latest.filePath&&asArray<Json>(latest.projects).some(project=>project.id===choices.projectId)){clearTimeout(timer);unsubscribe();resolve()}};
    unsubscribe=bridge.subscribe(check);check();
   });
   if(mounted&&packageCurrent(request))setPackageReady({request,choices});
  })().catch(error=>{if(mounted&&packageCurrent(request)){cancelPackageNavigation();toast.error(error instanceof Error?error.message:String(error),{id:'project-package-error'})}});
  return()=>{mounted=false};
 },[packageRequest]);
 useEffect(()=>{
  const request=packageNavigation.current;if(request&&!packageCurrent(request))cancelPackageNavigation();
 },[route,navigationRevision,state.projectId,snapshot?.view?.resumeRevision,previewBlocked]);
 useEffect(()=>{
  // Native can acknowledge tasks.close before React commits our own route change.
  // Only native lifecycle facts are synchronous; local navigation is checked above after render.
  const invalidate=()=>{const request=packageNavigation.current;if(request&&(document.hidden||bridge.getDocumentEpoch()!==request.documentEpoch||Number(obj(bridge.getState().view).resumeRevision||0)!==request.resumeRevision))cancelPackageNavigation()};
  const unsubscribe=bridge.subscribe(invalidate);document.addEventListener('visibilitychange',invalidate);
  return()=>{unsubscribe();document.removeEventListener('visibilitychange',invalidate);packageNavigation.current=null};
 },[]);
 useEffect(()=>{
  if(!packageReady||!packageCurrent(packageReady.request)||state.projectId!==packageReady.choices.projectId||state.filePath||!projects.some(project=>project.id===packageReady.choices.projectId))return;
  if(projectPreview.showPackage(packageReady.choices))cancelPackageNavigation();
 },[packageReady,state.projectId,state.filePath,projects,route,previewBlocked]);
 const previewRefsUnavailable=asArray<Json>(composer.previewRefs).length>0&&composer.previewRefsAvailable!==true;
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
 useEffect(()=>{bridge.setBackHandler(()=>{if(queuedInputsBack.current?.())return true;if(historyWindowBack.current?.())return true;if(navigationMoreOpen){setNavigationMoreOpen(false);return true}if(chatMenuOpen){setChatMenuOpen(false);return true}if(permissionOptionsOpen){closePermissionOptions();return true}if(attachmentBack.current?.())return true;if(phoneViewerExpanded){setPhoneViewerExpanded(false);return true}if(readingDocuments.length){closeReading();return true}if(messageQuoteTarget){setMessageQuoteTarget(null);return true}if(routeReferenceTarget){setRouteReferenceTarget(null);return true}if(skillDraftTarget){setSkillDraftTarget(null);return true}if(memoryDraftTarget){setMemoryDraftTarget(null);return true}if(sourceSelection){setSourceSelection(null);return true}if(messageMenu){setMessageMenu(null);return true}if(conversationControlsBack.current?.())return true;if(modelPickerOpen){if(modelWriting.current||state.modelSelectionBusy)return true;finishModelPicker();return true}if(state.chatAttachmentPickerOpen&&can('attachments.closePicker')){run('attachments.closePicker');return true}if(voiceReview){setVoiceReview(null);return true}if(phoneReview){if(!phoneReviewPending)setPhoneReview(null);return true}if(newProjectOpen||newFileOpen){closeCreate();return true}if(voiceOptionsOpen){setVoiceOptionsOpen(false);return true}if(commandOpen){setCommandOpen(false);return true}if(workOpen){setWorkOpen(false);return true}if(projectPickerOpen){setProjectPickerOpen(false);return true}if(fileDisplayOpen){setFileDisplayOpen(false);return true}if(taskOpen){backFromTask();return true}if(route==='tasks'&&taskFiltersOpen){setTaskFiltersOpen(false);return true}if(route==='projects'&&projectPreview.handleBack())return true;if(route==='projects'&&state.filePath){if(fileAdoption.isPending()||bridge.getState().state.fileSaving)return true;if(projectFileBack.current?.())return true;if(can('projects.close')&&!fileAdoption.pending){run('projects.close',fileIdentity);return true}return true}if(route!=='chat'){if(route!=='tasks'&&route!=='projects'&&secondaryBack.current?.())return true;if(route==='setup')closeSetup();else returnFromSecondary();return true}return false;});return()=>{bridge.setBackHandler(()=>false)};},[navigationMoreOpen,chatMenuOpen,permissionOptionsOpen,closePermissionOptions,phoneViewerExpanded,messageQuoteTarget,routeReferenceTarget,skillDraftTarget,memoryDraftTarget,sourceSelection,readingDocuments,closeReading,messageMenu,modelPickerOpen,modelSelectPending,state.modelSelectionBusy,voiceReview,phoneReview,phoneReviewPending,newProjectOpen,newFileOpen,voiceOptionsOpen,commandOpen,workOpen,projectPickerOpen,fileDisplayOpen,taskFiltersOpen,taskOpen,taskLayer,backFromTask,closeTask,route,state.filePath,state.chatAttachmentPickerOpen,fileIdentity,can,run,projectPreview,fileAdoption.pending]);
 useEffect(()=>{document.documentElement.dataset.pocketUi=ready?'ready':'connecting';},[ready]);
 useEffect(()=>{if(phoneDraft.phone===true)setIntent(phoneDraft.mode==='main_screen'?'main_screen':'background');else if(phoneDraft.phone===false)setIntent('chat');},[phoneDraft.phone,phoneDraft.mode]);
 useEffect(()=>{if(route==='projects'&&can('projects.refresh'))run('projects.refresh');else if(route==='tasks'&&can('tasks.refresh'))run('tasks.refresh');},[route,can,run]);
 useLayoutEffect(()=>{if(!taskOpen)return;const node=taskScroll.current;if(node)node.scrollTop=taskReading.current.get(`${taskId}:${taskLayer}`)??0},[taskOpen,taskId,taskLayer]);
 useEffect(()=>{setFileQuery('');setFileDisplayOpen(false);setProjectPickerOpen(false)},[state.projectId]);
 useSurfaceEnter(workspace,route);
 useMessageArrivals(scroll,String(state.conversationId||''),messages.map((message,index)=>message.id||`${message.role}:${message.at}:${index}`),route==='chat');
 const openVoiceReview=()=>{if(!can('voice.review'))return;voiceExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);setVoicePending(true);void act('voice.review',{phone:intent!=='chat'}).then(value=>{setVoiceReview(value);setVoiceText(value.text||'')}).catch(error=>setLocalError(error.message)).finally(()=>setVoicePending(false));};
 const submitVoiceReview=(command:'voice.accept'|'voice.discard')=>{
  const reviewed=voiceReviewLive.current;if(!reviewed||voiceWriting.current||voicePending)return;
  voiceWriting.current=true;setVoicePending(true);
  void act(command,{reviewHandle:reviewed.reviewHandle,...(command==='voice.accept'?{text:voiceText}:{})}).then(()=>{if(voiceReviewLive.current===reviewed)setVoiceReview(null)}).catch(error=>setLocalError(error.message)).finally(()=>{voiceWriting.current=false;setVoicePending(false)});
 };
 const discardVoice=()=>{if(isListening&&can('voice.stop')){run('voice.stop');return}if(!can('voice.review')||!can('voice.discard'))return;setVoicePending(true);void act('voice.review',{phone:intent!=='chat'}).then(value=>act('voice.discard',{reviewHandle:value.reviewHandle})).catch(error=>setLocalError(error.message)).finally(()=>setVoicePending(false));};
 const queryOriginalPhone=()=>{
  if(phoneAction.current||!phoneStart?.requestId||!phoneStart.conversationId||!can('phone.query'))return;
  const original=phoneStart;phoneAction.current=true;setPhoneReviewPending(true);
  void act('phone.query',{requestId:original.requestId,conversationId:original.conversationId}).then(value=>{const receipt=phoneStartReceipt(value,original);setPhoneStart(receipt);setLocalStatus(receipt.message);if(receipt.status==='accepted')setPhoneReview(null)}).catch(error=>{const receipt={...original,...phoneStartFailure(error,original)};setPhoneStart(receipt);setLocalStatus(receipt.message)}).finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 const preparePhone=()=>{if(phoneAction.current||!phoneText.value.trim()||!can('phone.prepare'))return;if(phoneStart?.status==='unknown'){setLocalStatus('请先查询原手机请求，草稿仍保留。');return}phoneExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);const window=phoneWindow.current;phoneAction.current=true;setPhoneReviewPending(true);void act('phone.prepare',{goal:phoneText.value,mode:intent,epoch:phoneDraft.epoch}).then(value=>{if(phoneWindow.current!==window)return;if(value?.reviewHandle){setPhoneStart(null);setPhoneReview(value)}else setLocalError('未取得这次目标的范围审阅，请重新核对。')}).catch(error=>{if(phoneWindow.current===window)setLocalError(error instanceof Error?error.message:String(error))}).finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});};
 const startReviewedPhone=()=>{const reviewed=phoneReviewLive.current;if(phoneAction.current||!reviewed||phoneReviewPending||phoneStart?.status==='unknown')return;phoneAction.current=true;phoneText.markSent();setPhoneReviewPending(true);void act('phone.start',{reviewHandle:reviewed.reviewHandle}).then(value=>{const receipt=phoneStartReceipt(value,reviewed);setPhoneStart(receipt);if(receipt.status!=='unknown')setPhoneReview(null);setLocalStatus(receipt.message)}).catch(error=>{const receipt=phoneStartFailure(error,reviewed);setPhoneStart(receipt);setLocalStatus(receipt.message)}).finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});};
 useEffect(()=>{const receipt=phoneDraft.startReceipt;if(receipt&&['accepted','rejected','unknown'].includes(receipt.status)){const original=phoneStartReceipt(receipt);setPhoneStart(original);if(original.status!=='unknown'&&phoneReview&&phoneReview.reviewHandle===receipt.reviewHandle)setPhoneReview(null)}},[phoneDraft.startReceipt?.status,phoneDraft.startReceipt?.reviewHandle,phoneDraft.startReceipt?.requestId,phoneDraft.startReceipt?.taskId]);
 const send=()=>{if(!composerModelName){navigate('models');return}if(intent!=='chat'){preparePhone();return}if(!composerHasPayload(text,asArray<Json>(state.selectedChatAttachments).length)||!draftKey||previewRefsUnavailable)return;
  if(workTarget){if(!workTargetCurrent||!can('ownerWork.input'))return;setLocalStatus('');goToLatest();chatDraft.markSent();void act('ownerWork.input',{taskId:workTarget.taskId,runId:workTarget.runId,epoch:workTarget.epoch,clientMessageId:crypto.randomUUID(),message:text,mode:state.chatSendMode||'queue'}).then(receipt=>{if(['accepted','queued','consumed'].includes(receipt?.status))setWorkTarget(null)}).catch(()=>{});return;}
  if(!can('composer.send'))return;setLocalStatus('');goToLatest();chatDraft.markSent();run('composer.send',{key:draftKey,epoch:draftEpoch,text});};
 const targetWork=async(task:Task)=>{const identity=ownerWorkIdentity(task);if(!identity||task.raw?.controls?.canInput!==true||task.raw?.conversationId!==state.conversationId)return;const closed=taskOpen?await closeTask():true;if(!closed)return;void navigate('chat',()=>{setWorkTarget({...identity,goal:task.goal,conversationId:String(state.conversationId)});changeIntent('chat')});};
 const controlWork=(task:Task,action:'cancel'|'continue')=>{const identity=ownerWorkIdentity(task);if(!identity||!can('ownerWork.control')||task.raw?.conversationId!==state.conversationId||task.raw?.controls?.[action==='cancel'?'canStop':'canContinue']!==true)return;const key=workIdentityKey(identity);if(workControlEntered.current.has(key)||workControls[key]?.unknown)return;workControlEntered.current.add(key);setWorkControls(old=>({...old,[key]:{pending:true,unknown:false,message:''}}));void act('ownerWork.control',{...identity,action},task.id).then(receipt=>{setWorkControls(old=>({...old,[key]:{pending:false,unknown:receipt?.status==='unknown',message:receipt?.message||(action==='cancel'?'已请求停止这件事，请以原任务状态为准。':'已提交继续原任务，请核对执行状态。')}}))}).catch(error=>{setWorkControls(old=>({...old,[key]:{pending:false,unknown:true,message:error instanceof Error?error.message:'原任务控制回执尚未确认，请先核对原任务。'}}))}).finally(()=>workControlEntered.current.delete(key));};
 useEffect(()=>{setWorkTarget(null)},[state.conversationId]);
 const changeIntent=(next:'chat'|'background'|'main_screen')=>{
   const window=++phoneWindow.current;
   setLocalError('');setLocalStatus('');
   setPhoneStart(previous=>previous?.status==='unknown'?previous:null);
   if(isListening&&can('voice.stop'))run('voice.stop');
   setPhoneReview(null);setVoiceReview(null);
   if(!can('composer.intent')){setLocalError('执行模式暂不可切换，请刷新当前状态。');return}
   void act('composer.intent',{phone:next!=='chat',...(next!=='chat'?{mode:next}:{})}).then(()=>{
     if(phoneWindow.current===window)setIntent(next);
   }).catch(error=>{
     if(phoneWindow.current===window)setLocalError(error instanceof Error?error.message:String(error));
   });
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
 const taskHistory=useTaskListHistory(tasks,String(state.taskListNextCursor||''),String(state.conversationId||''),route==='tasks'&&can('history.read'));
 const filteredTasks=taskHistory.tasks.filter(t=>(filter==='all'||filter==='attention'&&attention(t)||filter==='running'&&running(t)||filter==='ended'&&terminal(t))&&[t.goal,t.summary||'',t.id].join(' ').toLocaleLowerCase().includes(taskQuery.toLocaleLowerCase()));
 const attentionTasks=tasks.filter(attention);
 const navigationSelection=state.attachmentWorkspace?.open?'artifacts':['developer-logs','setup','remoteInbox','profile'].includes(route)?route:isPrimaryRoute?route:workspaceNavigationKey(secondaryPage||route);
 const selectNavigation=useCallback((key:string)=>{
  setNavigationMoreOpen(false);
  if(key==='artifacts'){
   // Outcomes cover the current workspace; closing them must reveal that same route.
   setWorkOpen(false);setCommandOpen(false);
   if(can('attachmentWorkspace.open'))run('attachmentWorkspace.open');
  }else if(key==='setup')openSetup();
  else navigate(key);
 },[can,run,openSetup,navigate]);
 const workNav:WorkNavItem[]=useMemo(()=>[
  {key:'chat',label:'对话',icon:MessageSquare,group:'常用'},
  {key:'tasks',label:'任务',icon:CalendarDays,group:'常用',status:attentionTasks.length?`${attentionTasks.length} 项需要处理`:undefined},
  {key:'devices',label:'设备',icon:Monitor,group:'常用'},
  {key:'artifacts',label:'成果',icon:Layers,group:'常用',disabled:!can('attachmentWorkspace.open')},
  {key:'memory',label:'记忆',icon:Brain,group:'常用'},
  {key:'skills',label:'能力',icon:Sparkles,group:'常用'},
  {key:'remoteInbox',label:'通知',icon:RiNotification3Line,group:'日常',count:Number(state.remoteInbox?.unread||0)||undefined},
  {key:'projects',label:'项目',icon:RiFolderLine,group:'日常'},
  {key:'automation',label:'自动化',icon:RiCalendarCheckLine,group:'工作区'},
  {key:'teams',label:'团队',icon:RiTeamLine,group:'工作区'},
  {key:'media',label:'媒体',icon:RiImage2Line,group:'工作区'},
  {key:'model-settings',label:'账号与模型',icon:UserRound,group:'偏好'},
  ...(can('setup.read')?[{key:'setup',label:'准备中心',icon:RiCheckboxCircleLine,group:'偏好'}]:[]),
  {key:'settings',label:'设置',icon:RiSettings4Line,group:'偏好'},
   {key:'developer-logs',label:'开发诊断',icon:RiTerminalBoxLine,group:'偏好'}
 ],[attentionTasks.length,state.remoteInbox?.unread,can]);
 const primaryWorkNav=workNav.filter(item=>item.group==='常用');
 const additionalWorkNav=workNav.filter(item=>item.group!=='常用'&&!['model-settings','settings'].includes(item.key));
 const workCommands:WorkCommand[]=useMemo(()=>[
  ...workNav.filter(item=>!item.disabled).map(item=>({id:item.key,group:item.group||'功能',title:item.label,description:item.key==='skills'?'技能与工具':item.key==='model-settings'?'AI 接入':undefined,icon:item.icon,onSelect:()=>selectNavigation(item.key)})),
  {id:'history',group:'日常',title:'任务历史',icon:RiHistoryLine,onSelect:()=>navigate('history')},
  ...conversations.map(c=>({id:`conversation:${c.id}`,group:'已加载的对话',title:c.title||'未命名对话',description:timeLabel(c.updatedAt),icon:RiChatAiLine,onSelect:()=>selectConversation(c.id)})),
  ...projects.map(p=>({id:`project:${p.id}`,group:'已加载的项目',title:p.name,icon:RiFolderLine,onSelect:()=>{void navigate('projects',()=>{if(can('projects.select'))run('projects.select',{id:p.id})})}}))
 ],[workNav,conversations,projects,navigate,selectNavigation,selectConversation,can,run]);
 useEffect(()=>{if(!currentFileKey||!state.fileReady||fileAdoption.holdsDraft())return;setFileDrafts(previous=>{const old=previous[currentFileKey];const version=Number(state.fileVersion||0),content=String(state.fileContent||''),baseline=String(state.fileBaseline||'');if(!old)return {...previous,[currentFileKey]:{text:content,baseline,version,dirty:content!==baseline}};if(old.submitted!==undefined&&content===old.submitted&&version!==old.version){
   // The native saving snapshot arrives asynchronously. A newer local edit can
   // already exist when this earlier save is acknowledged; only its baseline moved.
   return {...previous,[currentFileKey]:{text:old.text,baseline:content,version,dirty:old.text!==content}};
  }
  // Even a return to the pre-save baseline is a newer draft while the submitted
  // value is unresolved. A native saving snapshot must not replace that choice.
  if(old.submitted!==undefined&&old.text!==old.submitted)return previous;
  if(!old.dirty&&(old.text!==content||old.version!==version))return {...previous,[currentFileKey]:{text:content,baseline,version,dirty:content!==baseline}};return previous;});},[currentFileKey,state.fileContent,state.fileBaseline,state.fileVersion,state.fileReady,fileAdoption.pending,fileAdoption.unknown]);
 useEffect(()=>{if(route==='projects'&&!state.filePath&&!projectPreview.expanded){const frame=requestAnimationFrame(()=>{const node=projectScroll.current;if(!node)return;node.scrollTop=projectListTop.current.get(String(state.projectId||''))||0;const target=Array.from(node.querySelectorAll<HTMLElement>('[data-file-path], [data-tree-item]')).find(element=>element.dataset.filePath===projectView.selected||element.dataset.treeItem===projectView.selected);target?.focus({preventScroll:true})});return()=>cancelAnimationFrame(frame)}},[route,state.projectId,state.filePath,projectPreview.expanded]);
 const fileConflict=!!fileDraft?.dirty&&fileDraft.version!==Number(state.fileVersion||0);
 const editFile=(value:string)=>{if(!currentFileKey||!fileDraft||fileAdoption.isPending())return;fileEditRevisions.current.set(currentFileKey,(fileEditRevisions.current.get(currentFileKey)||0)+1);setFileDrafts(prev=>({...prev,[currentFileKey]:{...prev[currentFileKey],text:value,dirty:value!==prev[currentFileKey].baseline}}));if(can('projects.edit')&&!fileConflict)run('projects.edit',{projectId:state.projectId,path:state.filePath,version:fileDraft.version,content:value});};
 const saveFile=()=>{if(!fileDraft||fileConflict||fileAdoption.isPending()||!can('projects.save'))return;setFileDrafts(prev=>({...prev,[currentFileKey]:{...prev[currentFileKey],submitted:fileDraft.text}}));run('projects.save',{projectId:state.projectId,path:state.filePath,version:fileDraft.version,content:fileDraft.text});};
 const copyText=async(textToCopy:string)=>{try{if(!navigator.clipboard?.writeText)throw new Error('当前无法直接复制，请选择文字后复制。');await navigator.clipboard.writeText(textToCopy);toast('已复制');}catch(error){const message=error instanceof Error?error.message:'复制未完成，请选择文字后复制。';setLocalError(message);toast.error(message);throw error;}};
 const closeModelPicker=()=>{if(!modelWriting.current&&!state.modelSelectionBusy)finishModelPicker()};
 const modelSelectionLocked=!!state.chatSubmitting||!!state.phoneSubmitting||!!state.modelSaving||!!state.modelTesting||!!state.modelSelectionBusy||modelSelectPending;
 useEffect(()=>{if(channelPhoneModel&&modelPickerOpen&&!modelSelectionLocked&&!modelWriting.current)finishModelPicker()},[channelPhoneModel,modelPickerOpen,modelSelectionLocked]);
 const openModelPicker=(section:'models'|'reasoning'='models',anchor?:HTMLButtonElement)=>{
  if(channelPhoneModel){navigate('models');return}
  if(modelPickerOpen||modelWriting.current||modelSelectionLocked)return;
  const trigger=anchor||workspace.current?.querySelector<HTMLButtonElement>(section==='models'?'.pw-conversation-model-chip':'.pw-conversation-reasoning-chip');
  if(!trigger?.isConnected)return;
  modelPickerAnchor.current=trigger;trigger.focus({preventScroll:true});
  modelWindow.current++;setModelPickerConfirmed(false);setModelPickerSection(section);setModelPickerOpen(true);
 };
 const selectConversationModel=async(target:AIModelTarget)=>{
  const request=conversationSelectionRequest(target,state);
  if(!can(request.action))throw new Error('当前应用尚未接通此对话的模型选择，请升级后再试。');
  const receipt=await act(request.action,request.args);
  conversationSelectionReceipt(receipt,request.scopeKey,request.previousRevision);
  return receipt;
 };
 const modelPickerPending=(pending:boolean)=>{if(modelWindow.current===pickerToken){modelWriting.current=pending;setModelSelectPending(pending)}};
 const modelPickerSelected=()=>{if(modelWindow.current===pickerToken)setModelPickerConfirmed(true)};
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
  return <section className="pw-operation-entry" key={key}><ToolCall id={op.id||String(index)} name={op.name} summary={op.stateLabel} status={op.status} input={op.arguments} result={op.result} durationMs={op.durationMs} open={expandedCalls[key]??false} onOpenChange={value=>setExpandedCalls(x=>({...x,[key]:value}))}/>{packageTarget&&can('projects.package.preview.inspect')&&<Button variant="secondary" disabled={packageOpening||taskOpening||!can('projects.package.preview.open')||!can('tasks.close')} onClick={()=>openTaskPackage(packageTarget)}>查看这次任务的固定包</Button>}{draftTarget&&can('skillDraft.open')&&<Button variant="secondary" onClick={()=>setSkillDraftTarget(draftTarget as ConversationDraftTarget)}>审阅原技能提案</Button>}{memoryTarget&&can('memoryDraft.open')&&<Button variant="secondary" onClick={()=>setMemoryDraftTarget(memoryTarget as ConversationDraftTarget)}>审阅原记忆提案</Button>}{op.readLimited&&<p className="pw-muted">这条记录仅保留摘要，详情需要回到原记录读取。</p>}{executionTask&&obj(operation).tool==='project_execute'&&can('local.execution.open')&&<ProjectExecutionWorkspace key={'log:'+key} task={executionTask} operation={obj(operation)} active={taskOpen&&taskLayer==='process'&&!phoneViewerObscured} run={act}/>}</section>;
 };
 const taskActionLabel=(task:Task)=>conversationWorkActionLabel(task);
 const showTaskReview=()=>refreshReview();
 // Match the existing full-window layer breakpoints, rather than coloring native
 // safe areas from a route hidden behind a file or reading workspace.
 const [canvasLayers,setCanvasLayers]=useState(()=>({
  files:window.matchMedia('(max-width: 640px)').matches,
  reading:!window.matchMedia('(min-width: 760px) and (min-height: 600px)').matches,
 }));
 useEffect(()=>{
  const files=window.matchMedia('(max-width: 640px)'),reading=window.matchMedia('(min-width: 760px) and (min-height: 600px)');
  const update=()=>setCanvasLayers(previous=>{
   const next={files:files.matches,reading:!reading.matches};
   return previous.files===next.files&&previous.reading===next.reading?previous:next;
  });
  update();[files,reading].forEach(query=>query.addEventListener('change',update));
  return()=>[files,reading].forEach(query=>query.removeEventListener('change',update));
 },[]);
 const canvasRole=readingDocuments.length>0&&canvasLayers.reading?'chat':
  desktopNavigation||(state.attachmentWorkspace?.open&&canvasLayers.files)?'workspace':route==='chat'?'chat':'workspace';
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
 const phoneViewerObscured=!!(state.conversationReader||state.queuedInputsPage||permissionOptionsOpen||organizationTarget||state.attachmentWorkspace?.open||workOpen||commandOpen||projectPickerOpen||fileDisplayOpen||newProjectOpen||newFileOpen||modelPickerOpen||phoneReview||voiceReview||state.chatAttachmentPickerOpen||messageQuoteTarget||sourceSelection||skillDraftTarget||memoryDraftTarget||routeReferenceTarget||state.quickPanelVoice||readingDocuments.length);
 const phoneViewerFor=(task:Task,conversation=false)=>{
  const target=phoneViewerTask(task);if(!target||!can('phone.viewer.open')||!can('phone.viewer.frame')||!can('phone.viewer.close'))return null;
  return <PhoneViewer key={`${task.id}:${target.runId}:${target.epoch}`} task={target} expanded={phoneViewerExpanded} onExpandedChange={setPhoneViewerExpanded} nativeVisible={!phoneViewerObscured&&(conversation?!taskOpen:taskOpen&&taskLayer==='overview')}
   tasks={conversation?conversationViewerTasks.map(item=>phoneViewerTask(item)!):undefined} onSelectTask={conversation?(selected)=>{setPhoneViewedTaskId(selected.taskId)}:undefined}
   onPause={running(task)&&can('phone.pause')?identity=>act('phone.pause',identity):undefined} onResume={task.status==='paused'&&can('phone.resume')?identity=>act('phone.resume',identity):undefined}/>;
 };
 const taskReviewBody=(task:Task)=>{
  const progress=getRecentTaskProgress(task);
  const actionNames=asArray<{action:string;label:string}>(review?.actions).map(x=>x.action),approved=asArray<Json>(review?.approvals),unresolved=asArray<Json>(review?.unresolvedOperations);
  const protectedDisabled=taskOpening||reviewPending||reviewStale||reviewSubmitted;
  const scope=obj(review?.task?.raw?.executionScope),currentScope=obj(task.raw?.executionScope);
  const project=projectContinuation(review),continuation=continuations[task.id];
  const canReadProject=privateProjectTask(task)&&['paused','interrupted','awaiting_user','needs_confirmation','failed','completed'].includes(task.status);
  const projectBlocked=protectedDisabled||continuationBlocks(continuation);
  const projectNote=projectFeedback[task.id]||'';
  const projectControls=project&&<section aria-label="原项目续接范围">
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
  const hasDecision=!!review?.ownerQuestion||!!project||actionNames.some(action=>action!=='tasks.scope'&&action!=='tasks.plan.enter');
  const decisionFirst=(!!review?.ownerQuestion||actionNames.includes('tasks.approve')&&approved.length>0)&&!actionNames.includes('tasks.confirm')&&!actionNames.includes('tasks.resolve');
  const evidence=<div key="task-evidence" className="pw-task-evidence">{decisionFirst&&<Button variant="ghost" className="pw-task-context-toggle" aria-expanded={taskContextExpanded} aria-controls={`pw-task-context-${task.id}`} onClick={()=>setTaskContextExpanded(value=>!value)}>查看任务上下文<RiArrowDownSLine size={16}/></Button>}<div id={`pw-task-context-${task.id}`} hidden={decisionFirst&&!taskContextExpanded}><TaskOutcome task={review&&!reviewStale?review.task:task} messages={messages} onCopy={copyText} onOpenAttachment={can('attachments.open')?(messageId,id,sha256)=>run('attachments.open',{messageId,id,sha256}):undefined}/></div></div>;
  const refreshControl=can('tasks.review')&&<IconButton icon={RiRefreshLine} aria-label={review?'重新核对':'读取可用操作'} title={review?'重新核对':'读取可用操作'} disabled={readingReview||questionPending} onClick={showTaskReview}/>;
  const reviewStatus=<>{readingReview&&<ThinkingLoader phase="正在读取当前任务"/>}{reviewStale&&<p className="pw-error">任务已有新进展，请重新核对后再操作。</p>}{continuation&&<section aria-label="原项目续接回执"><p className="pw-muted" role="status">{continuation.message}</p>{!project&&projectNote&&<Accordion variant="divided"><AccordionItem id="continuation-feedback" title="本次反馈"><p className="pw-approval-copy">{projectNote}</p></AccordionItem></Accordion>}{continuation.status==='unknown'&&can('tasks.review')&&<Button variant="secondary" disabled={readingReview} onClick={showTaskReview}>核对原任务状态</Button>}{['continued','unknown'].includes(continuation.status)&&can('conversation.select')&&<Button variant="ghost" disabled={!!state.chatSubmitting} onClick={()=>selectConversation(continuation.original.conversationId)}>返回原对话，草稿保留</Button>}</section>}{!continuation&&(reviewSubmitted||reviewReceipt)&&!review?.ownerQuestion&&(reviewReceipt?<p className="pw-muted" role="status">{reviewReceipt}</p>:state.taskActionErrors?.[task.id]?<p className="pw-error" role="alert">本次提交未确认。请先重新核对原任务，再决定是否操作。</p>:<p className="pw-muted" role="status">已提交这次决定，等待任务回执。</p>)}</>;
  const decision=(review||reviewPending||continuation||(canReadProject||attention(task))&&can('tasks.review'))&&<div key="task-decision" className="pw-task-decision">
      {taskLayer==='overview'&&reviewStatus}
      {!readingReview&&(!review||reviewStale&&!review.ownerQuestion)&&<div className="pw-section-heading pw-task-review-tools">{canReadProject&&!continuationBlocks(continuation)?<Button variant="secondary" onClick={showTaskReview}>核对继续原项目任务</Button>:<span className="pw-grow"/>}{refreshControl}</div>}
      {review&&(!reviewStale||!!review.ownerQuestion)&&(hasDecision?<PermissionReview title={project?'继续原项目任务':actionNames.includes('tasks.confirm')?'这份结果是否达到目标？':'处理这次任务'} headerAction={refreshControl}><div className="pw-protected-actions">{projectControls}{review.ownerQuestion&&<OwnerTaskQuestion key={review.reviewHandle} question={review.ownerQuestion as OwnerQuestionView} text={questionAnswer} onTextChange={setQuestionAnswer} disabled={protectedDisabled} canAnswer={actionNames.includes('tasks.answerQuestion')} canChoose={actionNames.includes('tasks.answerQuestionChoice')} submit={submitQuestion}/>}{actionNames.includes('tasks.confirm')&&<Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={reviewFeedbackOpen?['result-feedback']:[]} onExpandedChange={keys=>setReviewFeedbackOpen(keys.has('result-feedback'))}><AccordionItem id="result-feedback" title="补充说明（可选）" description={reviewNote?'已填写':undefined}><TextAreaField label="本次结果反馈" value={reviewNote} onChange={setReviewNote} placeholder="已完成或仍需调整的内容"/></AccordionItem></Accordion>}{actionNames.includes('tasks.approve')&&approved.map(p=><TaskApproval key={`${review.reviewHandle}:${p.approvalId||p.id}`} approval={p} executionScope={scope} disabled={protectedDisabled} onDecision={args=>reviewedAction('tasks.approve',args)}/>)}{actionNames.includes('tasks.resolve')&&unresolved.map(op=><section className="pw-approval-item" key={op.operationId}><h4>{op.title||op.toolName||'动作结果核对'}</h4><p>{op.summary||op.message||'请依据实际观察核对这次动作是否已经发生。'}</p><div className="pw-action-row"><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.resolve',{operationId:op.operationId,outcome:'occurred'})}>确认已发生</Button><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.resolve',{operationId:op.operationId,outcome:'not_occurred'})}>确认未发生</Button></div></section>)}{!project&&actionNames.includes('tasks.resume')&&<TextAreaField label="本次补充" value={reviewNote} onChange={setReviewNote} placeholder="继续前需要补充什么？"/>}{!review.actions?.length&&<p className="pw-muted">当前没有待作出的新决定。</p>}</div></PermissionReview>:<div className="pw-section-heading pw-task-review-tools"><p className="pw-muted">当前没有待作出的新决定。</p>{refreshControl}</div>)}
     </div>;
  return <>
   {/* Keep the result document mounted while reading deeper task details. */}
   <div key={task.id} hidden={taskLayer!=='overview'}>
    <section className="pw-task-overview pw-task-review" aria-label="任务结果与处理">
     <header className="pw-task-overview-top"><div className="pw-task-identity">{running(task)?<ProgressCircle aria-label="任务正在执行" isIndeterminate size="sm"/>:<span className="pw-task-state-dot" data-state={task.status} aria-hidden="true"/>}<span data-task-status={task.status}>{labelStatus(task.status)}</span></div>{taskLayer==='overview'&&ownerWorkActions(task)}</header>
     {taskOpen&&phoneViewerFor(task)}
     {taskOpen&&task.raw?.feature==='remote'&&can('remoteTask.open')&&<RemoteTaskWorkspace key={task.id} taskId={task.id}/>}
     {taskOpen&&taskLayer==='overview'&&task.raw?.developmentContext&&can('projectLive.open')&&<ProjectLivePreview key={'live:'+task.id} taskId={task.id} run={act} nativeVisible={!phoneViewerObscured} onBackChange={registerLivePreviewBack}/>}
     {can('attachmentWorkspace.open')&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&<Button variant="ghost" leadingIcon={RiFileLine} onClick={()=>run('attachmentWorkspace.open',{taskId:task.id})}>原任务的文件与成果</Button>}
     {currentScope.executionPhase==='planning'&&<p className="pw-muted" role="status">原任务处于只读规划。{Number(currentScope.planRevision)>0?`当前计划版本 ${String(currentScope.planRevision)}，确认准确计划后才能在原范围内继续。`:'尚未提交计划正文，重新打开页面不会启动执行。'}</p>}
     {decisionFirst?[decision,evidence]:[evidence,decision]}
     {running(task)&&(progress.source!=='task'||progress.status==='unknown')&&<section className="pw-task-progress" aria-label="最近进展"><ProgressCircle aria-label="任务正在执行" isIndeterminate size="sm"/><div><BlurCrossfade identity={`${task.id}:${progress.operationId||progress.position||progress.title}`}><strong>{progress.title}</strong></BlurCrossfade>{progress.detail&&progress.detail!==task.summary&&<p>{progress.detail}</p>}</div></section>}
     {openedTaskId.current===task.id&&!taskOpening&&task.raw?.kind==='assistant'&&can('conversation.drafts')&&<ConversationDrafts key={'drafts:'+task.id} taskId={task.id} refreshRevision={Number(state.memoryRefreshRevision||0)} open={openConversationDraft}/>}

     <Accordion variant="divided" className="pw-task-disclosures" aria-label="更多任务资料">
      <AccordionItem id={`${task.id}:details`} title="过程与范围">
     <div className="pw-task-entry-list">
      <Button variant="ghost" leadingIcon={RiToolsLine} onClick={()=>showTaskLayer('process')}>查看执行轨迹<RiArrowRightSLine size={17}/></Button>
      {can('tasks.review')&&<Button variant="ghost" leadingIcon={RiInformationLine} disabled={readingReview||questionPending} onClick={()=>{if(openedTaskId.current!==task.id){openTask(task.id,'scope');return}showTaskLayer('scope');if(!review||reviewStale)refreshReview()}}>{review&&!reviewStale&&actionNames.includes('tasks.scope')?'修订本次范围':'本次范围'}<RiArrowRightSLine size={17}/></Button>}
     </div>
     {openedTaskId.current===task.id&&!taskOpening&&<>
      {taskLayer==='overview'&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&can('taskCapabilities.open')&&<TaskCapabilityWorkspace key={'capabilities:'+task.id} taskId={task.id} run={act} exportState={state.taskCapabilityExport} onBackChange={registerCapabilityBack}/>}
      {taskLayer==='overview'&&task.raw?.ownerRuntimeSetup&&can('setup.runtime.read')&&<Button variant="secondary" onClick={()=>openSetup('tools',task.id)}>查看工具准备进度</Button>}
      {taskLayer==='overview'&&task.raw?.developmentContext&&can('projectRuntime.read')&&<ProjectRuntimeHealth key={'runtime:'+task.id} taskId={task.id} run={act} onPrepareTools={can('setup.runtime.read')?()=>openSetup('tools'):undefined}/>}
      {taskLayer==='overview'&&task.raw?.developmentContext&&can('local.execution.open')&&<ProjectExecutionWorkspace key={'execution:'+task.id} task={task} active={taskOpen&&!phoneViewerObscured} run={act}/>}
      {can('usage.task')&&<TaskInference key={task.id} taskId={task.id}/>}{can('tokenBudgets.open')&&<TokenBudgetsWorkspace key={'budget:'+task.id} taskId={task.id} supported={can} registerTransientBack={registerTaskBack}/>}
      {task.raw?.kind==='phone'&&!task.raw?.origin&&typeof task.raw.runId==='string'&&task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0&&can('routeReference.open')&&<Button variant="ghost" onClick={()=>setRouteReferenceTarget({taskId:task.id,runId:task.raw!.runId,epoch:task.raw!.coordinatorEpoch,namespace:'owner/private'})}>查看路线参考与分享</Button>}
      {task.raw?.evidence?.nativeFinalAnswer&&can('sources.open')&&<Button variant="ghost" onClick={()=>setSourceSelection({taskId:task.id})}>核对原回答来源</Button>}
     </>}
      </AccordionItem>
      {review&&(!reviewStale||!!review.ownerQuestion)&&actionNames.includes('tasks.deleteScreenshot')&&<AccordionItem id={`${task.id}:screenshot`} title="截图管理"><section className="pw-approval-item"><h4>删除这份任务截图</h4><p>只删除本次核对的结果截图；已导出的文件和项目成果仍保留。删除后不能恢复。</p><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.deleteScreenshot')}>确认删除这份截图</Button></section></AccordionItem>}
     </Accordion>
    </section>
   </div>
   {/* Keep settled audit pages and expanded records while returning to the result.
       The original task/run/epoch key still retires a different history window. */}
   <div className="pw-task-process-layer" hidden={taskLayer!=='process'}><TaskHistory key={`${state.conversationId}:${taskHistoryIdentity(task)}`} task={task} canRead={can('history.read')} canReadTrace={can('trace.read')} active={taskLayer==='process' && taskOpen && !taskOpening && openedTaskId.current===task.id} registerTransientBack={registerTaskBack} renderOperation={renderOperation}/></div>
   {taskLayer==='scope'&&<section className="pw-task-review" aria-label="本次任务范围">
    {reviewStatus}
    {!readingReview&&<><div className="pw-section-heading pw-task-review-tools"><span className="pw-grow"/>{refreshControl}</div>{review&&!reviewStale&&scopeEditor}</>}
   </section>}
  </>;
 };
 const ownerWorkActions=(task:Task)=>{const identity=ownerWorkIdentity(task);if(!identity||task.raw?.conversationId!==state.conversationId)return null;const control=workControls[workIdentityKey(identity)],controls=obj(task.raw?.controls);return <section className="pw-owner-work-actions" aria-label="这件事的操作">{controls.resultCapacityState==='full'&&<p role="status">这件事的结果留存已满，暂不能继续执行。已有结果未因容量限制而删除；能否查看仍取决于原任务来源是否可用。容量限制不代表正在执行的任务已经停止。</p>}{controls.canInput===true&&can('ownerWork.input')&&<Button variant="secondary" disabled={!!state.chatSubmitting} onClick={()=>{void targetWork(task)}}>补充这个任务</Button>}{controls.canStop===true&&can('ownerWork.control')&&<Button variant="ghost" disabled={control?.pending||control?.unknown} onClick={()=>controlWork(task,'cancel')}>停止这件事</Button>}{controls.canContinue===true&&can('ownerWork.control')&&<Button variant="secondary" disabled={control?.pending||control?.unknown} onClick={()=>controlWork(task,'continue')}>继续原任务</Button>}{control?.message&&<p role="status">{control.message}</p>}{control?.unknown&&<Button variant="ghost" onClick={()=>openTask(task.id)}>核对原任务状态</Button>}</section>;};
 const taskFooter=(task:Task)=>{
  if(taskLayer==='process'||openedTaskId.current!==task.id||!review||reviewStale)return null;
  const actions=asArray<{action:string}>(review.actions).map(x=>x.action),disabled=taskOpening||reviewSubmitted||reviewPending;
  if(taskLayer==='scope'&&actions.includes('tasks.scope'))return <footer className="pw-task-footer"><PressButton label="确认本次范围修订" disabled={disabled||!/^\d+$/.test(scopeTools)||!/^\d+$/.test(scopeModels)||Number(scopeTools)<1||Number(scopeTools)>400||Number(scopeModels)<1||Number(scopeModels)>512} onClick={()=>reviewedAction('tasks.scope',{minutes:Number(scopeMinutes),tools:Number(scopeTools),models:Number(scopeModels)})}/></footer>;
  if(taskLayer==='overview'&&actions.includes('tasks.confirm'))return <footer className="pw-task-footer"><Button variant="secondary" disabled={disabled} onClick={()=>reviewedAction('tasks.confirm',{completed:false,feedback:reviewNote})}>目标尚未达到</Button><PressButton label="确认达到目标" disabled={disabled} onClick={()=>reviewedAction('tasks.confirm',{completed:true,feedback:reviewNote})}/></footer>;
   if(taskLayer==='overview'&&actions.includes('tasks.resume')){const project=projectContinuation(review),note=projectFeedback[task.id]||'';return <footer className="pw-task-footer"><PressButton label={project?'在原范围内继续项目任务':'在原范围内继续'} disabled={disabled||!!project&&(continuationBlocks(continuations[task.id])||!note.trim()||note.trim().length>8000)} onClick={()=>reviewedAction('tasks.resume',{confirmed:true,feedback:reviewNote})}/></footer>;}
  return null;
 };
 const selectedTaskStop=selectedTask&&!terminal(selectedTask)&&can('tasks.stop');
 const composerHasChatStop=route==='chat'&&intent==='chat'&&!!state.chatBusy&&!!currentCancelIdentity&&can('chat.cancel');
 const conversationActivity=conversationActivityLabel(state);
 const recentWork=orderConversations(conversations as (Json & {id:string})[]).slice(0,Math.max(5,conversations.filter(c=>Number(c.pinOrder)>0).length));
 // Bound read-only permission summary; grants and approvals remain native task operations.
 const permissionView=composerPermissionView(state);
 const permissionReviewLabel=permissionView&&['waiting_approval','awaiting_approval','waiting_permission'].includes(permissionView.status)?'审阅本次授权':'查看待处理事项';
 const openPermissionTask=()=>{if(permissionWriting.current||!permissionView||!can('tasks.open'))return;setPermissionOptionsOpen(false);openTask(permissionView.taskId,permissionView.needsReview&&can('tasks.review')?'review':'scope')};
 const permissionTaskDetails=<Accordion className="pw-composer-permission-detail"><AccordionItem title="本次任务范围">
    {permissionView?<><strong className="pw-composer-permission-goal">{permissionView.goal}</strong><p>{labelStatus(permissionView.status)}{permissionView.phase==='planning'?' · 只读规划':''}</p>
     {permissionView.scopeVerified?<dl><div><dt>工具次数</dt><dd>{permissionView.toolsUsed??'未核对'} / {permissionView.toolLimit??'未核对'}</dd></div><div><dt>模型调用</dt><dd>{permissionView.modelCallsUsed??'未核对'} / {permissionView.modelCallLimit??'未核对'}</dd></div><div><dt>截止时间</dt><dd>{permissionView.deadlineAt===0?'未设置':permissionView.deadlineAt===null?'未核对':new Date(permissionView.deadlineAt).toLocaleString('zh-CN')}</dd></div></dl>:<p>尚无可核对的任务执行范围。</p>}
     <p className="pw-composer-permission-note">审批设置不会扩大此任务的范围、资源许可或预算。</p>
     {!permissionView.needsReview&&can('tasks.open')&&<Button variant="ghost" disabled={permissionPending} onClick={openPermissionTask}>查看原任务权限</Button>}
    </>:<p>当前对话还没有任务。任务开始后可在这里查看执行范围。</p>}
   </AccordionItem></Accordion>;
 // Only surface a decision that belongs to a task anchored in this conversation.
 const conversationAttention=intent==='chat'?tasks.reduce<Task|undefined>((latest,task)=>{const anchor=messageTaskAnchors.get(task.id);if(!conversationInlineAttention(task)||anchor===undefined)return latest;return !latest||anchor>messageTaskAnchors.get(latest.id)!?task:latest;},undefined):undefined;
 const navigationTrigger=desktopNavigation?<span className="pw-header-spacer" aria-hidden="true"/>:<Drawer.Trigger asChild><IconButton icon={RiMenuLine} className={route==='chat'?'pw-chat-glass-trigger':undefined} aria-label="打开导航" data-testid="all_work" onClick={event=>{navigationReturnFocus.current=event.currentTarget}}/></Drawer.Trigger>;
 const navigationTitle=commandOpen?(commandScope==='conversations'?'查找对话':'搜索工作'):'PocketWorld';
 const navigationContent=<>
   <header className={`pw-drawer-header${commandOpen?' pw-search-header':''}`}>
    {commandOpen&&<IconButton icon={RiArrowLeftLine} aria-label="返回导航" onClick={()=>setCommandOpen(false)}/>}
    {desktopNavigation?<h2>{navigationTitle}</h2>:<Drawer.Title>{navigationTitle}</Drawer.Title>}
    {desktopNavigation&&!commandOpen&&<IconButton icon={effectiveCompact?PanelLeftOpen:PanelLeftClose} aria-label={effectiveCompact?'展开导航':'收起导航'} aria-expanded={!effectiveCompact} title={effectiveCompact?'展开导航':'收起导航'} onClick={()=>setNavigationCompact(value=>!value)}/>}
    {!desktopNavigation&&<><Drawer.Description id="pw-work-description" className="pw-sr-only">搜索或继续最近对话，进入任务、设备、成果、记忆、能力及更多工作区。</Drawer.Description><Drawer.Close asChild><IconButton icon={RiCloseLine} aria-label="关闭导航"/></Drawer.Close></>}
   </header>
   {!commandOpen&&<div className="pw-navigation-actions">
    <Button ref={conversationSearch} variant="ghost" className="pw-navigation-search-trigger" leadingIcon={Search} aria-label="搜索对话" title="搜索对话" onClick={()=>{setCommandScope('conversations');setCommandOpen(true)}}>搜索对话</Button>
    {can('conversation.new')&&<Button variant="ghost" className="pw-navigation-new" leadingIcon={SquarePen} aria-label="新对话" title="新对话" disabled={!!state.chatSubmitting} onClick={()=>{void navigate('chat',()=>run('conversation.new'))}}>新对话</Button>}
   </div>}
   <div className={`pw-drawer-body ${commandOpen?'pw-navigation-search':''}`}>
    {commandOpen?<CommandMenu key={commandScope} commands={commandScope==='conversations'?workCommands.filter(item=>item.id.startsWith('conversation:')):workCommands} conversationOnly={commandScope==='conversations'} onClose={()=>setCommandOpen(false)}/>:<>
     <WorkSidebar items={primaryWorkNav} label="常用工作区" compact={effectiveCompact} hideGroupLabels selected={navigationSelection} onNavigate={selectNavigation}/>
     <UNSAFE_PortalProvider getContainer={()=>desktopNavigation?document.body:navigationDrawer.current??document.body}>
     <PopoverTrigger isOpen={navigationMoreOpen} onOpenChange={setNavigationMoreOpen}>
      <Button ref={workSearch} variant="ghost" className="pw-navigation-more" leadingIcon={MoreHorizontal} aria-label={Number(state.remoteInbox?.unread||0)>0?'更多工作区，有未读通知':'更多工作区'} title="更多工作区" data-has-unread={Number(state.remoteInbox?.unread||0)>0||undefined} data-selected={additionalWorkNav.some(item=>item.key===navigationSelection)||undefined}>更多工作区</Button>
      <Popover className="pw-navigation-more-popover" dialogClassName="pw-navigation-more-dialog" aria-label="更多工作区" placement="bottom start" boundaryElement={desktopNavigation?undefined:navigationDrawer.current??undefined}>
       <Button variant="ghost" className="pw-navigation-global-search" leadingIcon={Search} data-testid="work_search" onClick={()=>{setNavigationMoreOpen(false);setCommandScope('all');setCommandOpen(true)}}>搜索全部工作</Button>
       <WorkSidebar items={additionalWorkNav} label="更多工作区" hideGroupLabels selected={navigationSelection} onNavigate={selectNavigation}/>
      </Popover>
     </PopoverTrigger>
     </UNSAFE_PortalProvider>
     <section className="pw-work-recent" aria-label="最近对话">
      {[{label:'置顶',items:recentWork.filter(c=>Number(c.pinOrder)>0)},{label:'最近',items:recentWork.filter(c=>!(Number(c.pinOrder)>0))}].filter(group=>group.items.length>0).map(group=><div className="pw-conversation-group" key={group.label}>
       <div className="pw-section-heading"><h3>{group.label}</h3></div>
       <div className="pw-recent-work">{group.items.map(c=><div className="pw-conversation-row" key={c.id}><Button variant="ghost" leadingIcon={MessageSquare} title={c.title||'未命名对话'} aria-current={c.id===state.conversationId&&route==='chat'?'page':undefined} className={c.id===state.conversationId&&route==='chat'?'is-current':''} onClick={()=>selectConversation(c.id)}><span>{c.title||'未命名对话'}</span></Button>{can('conversation.organization.read')&&<IconButton icon={MoreHorizontal} aria-label={`整理对话：${c.title||'未命名对话'}`} onClick={()=>setOrganizationTarget(c.id)}/>}</div>)}</div>
      </div>)}
     </section>
    </>}
   </div>
   {!commandOpen&&<footer className="pw-navigation-footer">
    <div className="pw-navigation-account">
     <Button variant="ghost" className="pw-navigation-profile" aria-label={`${personalName}，个人资料与偏好`} title="个人资料与偏好" aria-current={route==='profile'?'page':undefined} disabled={!can('profile.snapshot')} onClick={()=>selectNavigation('profile')}><span className="pw-navigation-profile-content"><span className="pw-sidebar-avatar" aria-hidden="true">{personalAvatar?<img src={personalAvatar} alt=""/>:personalName==='我的'?<UserRound size={21}/>:Array.from(personalName)[0]}</span><span className="pw-navigation-profile-copy"><strong>{personalName}</strong><small>资料与偏好</small></span></span></Button>
     <IconButton icon={Settings} aria-label="设置" title="设置" aria-current={navigationSelection==='settings'?'page':undefined} onClick={()=>selectNavigation('settings')}/>
    </div>
   </footer>}
 </>;
 const allWork=!desktopNavigation&&<Drawer.Portal>
  <Drawer.Overlay className="pw-overlay pw-navigation-overlay"/>
  <Drawer.Content ref={navigationDrawer} className="pw-drawer pw-work-drawer pw-navigation-drawer" aria-describedby="pw-work-description" onEscapeKeyDown={event=>{if(navigationMoreOpen){event.preventDefault();setNavigationMoreOpen(false)}else if(commandOpen){event.preventDefault();if(!event.isComposing&&event.keyCode!==229)setCommandOpen(false)}}} onCloseAutoFocus={event=>{const target=navigationReturnFocus.current?.isConnected?navigationReturnFocus.current:chatMenuTrigger.current;if(taskOpen)event.preventDefault();else if(route==='chat'&&target){event.preventDefault();target.focus()}navigationReturnFocus.current=null}}>
   {navigationContent}
  </Drawer.Content>
 </Drawer.Portal>;
 const renderTaskRow=(t:Task)=><Button variant="ghost" key={t.id} className="pw-task-row" data-task-id={t.id} data-task-status={t.status} onClick={()=>openTask(t.id)}><span className="pw-task-row-status">{running(t)?<ProgressCircle aria-label={labelStatus(t.status)} isIndeterminate size="sm"/>:t.status==='completed'?<RiCheckboxCircleLine size={20} aria-hidden="true"/>:t.status==='failed'?<RiErrorWarningLine size={20} aria-hidden="true"/>:['paused','interrupted'].includes(t.status)?<RiPauseCircleLine size={20} aria-hidden="true"/>:<RiTimeLine size={20} aria-hidden="true"/>}</span><span className="pw-task-row-copy"><b title={t.goal}>{t.goal}</b>{t.summary&&t.summary!==t.goal&&<span className="pw-task-row-summary">{t.summary}</span>}<span className="pw-task-row-meta"><span>{labelStatus(t.status)}</span>{!!t.updatedAt&&<time>{timeLabel(t.updatedAt)}</time>}</span></span><RiArrowRightSLine size={18} aria-hidden="true"/></Button>;
 const chatPage=<main className={`pw-chat-workspace${ready&&!messages.length?' pw-chat-empty':''}`}>
  {!desktopNavigation&&<ChatNavigationMenu title={messages.length?currentTitle:'PocketWorld'} navigationTrigger={navigationTrigger} isOpen={chatMenuOpen} onOpenChange={setChatMenuOpen} triggerRef={chatMenuTrigger} submitting={!!state.chatSubmitting} unread={can('remoteInbox.state')?Number(state.remoteInbox?.unread||0):0} onNewConversation={can('conversation.new')?()=>run('conversation.new'):undefined} onConversations={()=>{navigationReturnFocus.current=chatMenuTrigger.current;setCommandScope('conversations');setCommandOpen(true);setWorkOpen(true)}} onNavigation={()=>{navigationReturnFocus.current=chatMenuTrigger.current;setCommandOpen(false);setWorkOpen(true)}} onOrganize={currentConversation&&can('conversation.organization.read')?()=>setOrganizationTarget(currentConversation.id):undefined} onNotifications={can('remoteInbox.state')?()=>navigate('remoteInbox'):undefined} onSettings={()=>navigate('settings')}/>}
  <div ref={scroll} className="pw-conversation-scroll" data-conversation-id={String(state.conversationId||'')} onScroll={saveReading} aria-label="当前对话消息"><div ref={chatContent} className="pw-conversation-content">
   {!ready?<div className="pw-start"><AgentAvatar agentId="main" state="idle" size={40}/><ShimmerText>正在恢复当前工作</ShimmerText><div className="pw-loading-copy" aria-hidden="true"><Skeleton width="68%" height={13}/><Skeleton width="92%" height={13}/><Skeleton width="80%" height={13}/></div></div>:!messages.length?<div className="pw-start pw-chat-start">
    <div className="pw-start-heading"><h2>想做点什么？</h2><p>{composerModelName?'聊聊想法，或交给我一件事。':'先连接模型，再开始对话。'}</p></div>
    {!composerModelName?<Button variant="primary" className="pw-start-model" leadingIcon={RiBox3Line} trailingIcon={RiArrowRightSLine} disabled={!ready||modelSelectionLocked} aria-haspopup="dialog" aria-expanded={modelPickerOpen&&modelPickerSection==="models"} onClick={()=>openModelPicker("models")}>{channelPhoneModel?'连接渠道默认模型':'选择模型'}</Button>:<div className="pw-start-actions" aria-label="开始一件事">
     {can('attachments.openPicker')&&<Button variant="secondary" leadingIcon={RiFileLine} disabled={!!state.chatSubmitting} onClick={()=>{changeIntent('chat');run('attachments.openPicker')}}>处理文件</Button>}
     {can('phone.prepare')&&<Button variant="secondary" leadingIcon={RiComputerLine} onClick={()=>{changeIntent('background');workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea')?.focus()}}>手机操作</Button>}
     <Button variant="secondary" leadingIcon={RiToolsLine} onClick={()=>navigate('skills')}>使用技能</Button>
    </div>}
   </div>:<div className="pw-message-stream">{messages.map((m,index)=>{
    const id=m.id||`${m.role}:${m.at}:${index}`,refs=sourceRefs(m);
    const phoneResult=messagePhoneResult(m),taskResult=messageTaskResult(m);
    const linked=tasks.find(t=>t.id===(m.taskId||m.submission?.taskId));
    const traceTaskId=phoneResult?.taskId||taskResult?.taskId||m.taskId;
    const linkedFeedback=linked&&!phoneResult&&!taskResult&&linked.id!==conversationAttention?.id&&messageTaskAnchors.get(linked.id)===index?linked:null;
    const completedFeedback=linkedFeedback&&conversationTaskCompact(linkedFeedback);
    const traceInMenu=!!(phoneResult||taskResult||linkedFeedback&&!completedFeedback);
    const canQuote=intent==='chat'&&m.quoteAvailable&&can('messageQuote.open')&&can('messageQuote.stage')&&can('messageQuote.close');
    return <article key={id} tabIndex={-1} data-message-index={index} data-message-id={id} className={`pw-message pw-message-${m.role}`} aria-label={m.role==='assistant'?(phoneResult||taskResult?'任务结果':'助手回复'):'你的消息'}>
     {m.role==='assistant'?(refs.length?<CitedAnswer text={m.content} onCopyCode={copyText} sources={refs as any} onOpenSource={()=>openSource(m)}/>:<AIAnswer text={m.content} onCopyCode={copyText} busy={false} showActions={false}/>):m.content?<div className="pw-user-text">{m.content}</div>:null}
     {asArray<Json>(m.attachments).length>0&&<div className="pw-message-files">{asArray<Json>(m.attachments).map(file=><Button key={file.id||file.name} variant="secondary" size="small" leadingIcon={RiFileLine} disabled={!can('attachments.open')||!file.sha256||!!state.attachmentOpens?.[`${file.id}:${file.sha256}`]?.checking} onClick={()=>run('attachments.open',{messageId:m.id,id:file.id,sha256:file.sha256})}>{file.name||'原附件'}</Button>)}</div>}
     {m.submission&&<Receipt receipt={m.submission}/>}
     <div className="pw-message-footer">
     {m.role==='user'&&m.submission?.status==='queued'&&m.submission?.taskId&&can('queued.open')&&<Button variant="ghost" className="pw-message-queue-action" leadingIcon={RiTimeLine} onClick={()=>run('queued.open',{taskId:m.submission!.taskId,conversationId:state.conversationId})}>已入队 · 查看或调整</Button>}
     {linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')&&<Button variant="ghost" className="pw-message-work-action" leadingIcon={completedFeedback?RiCheckboxCircleLine:attention(linkedFeedback)||linkedFeedback.status==='failed'?RiErrorWarningLine:RiTimeLine} aria-label={`查看任务详情：${linkedFeedback.goal}`} title={linkedFeedback.goal} disabled={!can('tasks.open')} onClick={()=>openTask(linkedFeedback.id,attention(linkedFeedback)&&can('tasks.review')?'review':'overview')}><span className="pw-message-work-status">{labelStatus(linkedFeedback.status)}</span><span className="pw-message-work-next">{attention(linkedFeedback)?taskActionLabel(linkedFeedback):linkedFeedback.status==='failed'?'查看失败原因':terminal(linkedFeedback)?'查看详情':'查看进展'}<RiArrowRightSLine size={15} aria-hidden="true"/></span></Button>}
     {(m.content||m.role==='assistant'||canQuote||(m.retainedSources&&can('sources.open')))&&<div className="pw-message-tools">
      {m.role==='assistant'&&!!m.content&&<MessageActions onCopy={()=>copyText(m.content)} onSpeak={can('speech.speak')&&m.id&&m===messages.filter(x=>x.role==='assistant').at(-1)?playing=>run(playing?'speech.speak':'speech.stop',{messageId:m.id}):undefined} speaking={!!state.speaking}/>}
      <PopoverTrigger isOpen={messageMenu===id} onOpenChange={value=>setMessageMenu(value?id:null)}>
       <IconButton icon={RiMoreLine} size="small" aria-label="消息操作与来源"/>
       <Popover className="pw-message-popover" aria-label="消息操作与来源"><div className="pw-message-menu">
        {m.role==='assistant'&&traceTaskId&&can('tasks.open')&&<Button variant="ghost" leadingIcon={RiToolsLine} aria-label="查看这条回复的执行轨迹" onClick={()=>{setMessageMenu(null);openTask(traceTaskId,'process')}}>执行轨迹</Button>}
        {m.role==='assistant'&&completedFeedback&&linkedFeedback&&can('tasks.open')&&<Button variant="ghost" aria-label={`查看任务详情：${linkedFeedback.goal}`} onClick={()=>{setMessageMenu(null);openTask(linkedFeedback.id)}}>查看任务详情</Button>}
        {!!m.content&&<Button variant="ghost" leadingIcon={RiBookOpenLine} onClick={()=>{const source=Array.from(scroll.current?.querySelectorAll<HTMLElement>('[data-message-id]')||[]).find(row=>row.dataset.messageId===id);openReading({kind:'message',title:m.role==='assistant'?'阅读回复':'阅读消息',text:m.content,returnFocus:source?.querySelector<HTMLElement>('button[aria-label="消息操作与来源"]')});setMessageMenu(null)}}>阅读与选字</Button>}
        {m.role!=='assistant'&&!!m.content&&<MessageActions onCopy={()=>copyText(m.content)}/>}

        {m.role==='user'&&m.submission?.taskId&&can('queued.open')&&<Button variant="ghost" leadingIcon={RiTimeLine} onClick={()=>{setMessageMenu(null);run('queued.open',{taskId:m.submission!.taskId,conversationId:state.conversationId})}}>原任务的输入队列</Button>}
        {canQuote&&<Button variant="ghost" leadingIcon={RiDoubleQuotesL} disabled={!draftKey||!!state.chatSubmitting} onClick={()=>{setMessageMenu(null);openQuote(m)}}>引用到草稿</Button>}
        {m.retainedSources&&can('sources.open')&&<Button variant="ghost" leadingIcon={RiLinksLine} onClick={()=>{setMessageMenu(null);openSource(m)}}>回答来源</Button>}
        {m.memoryReference&&<Button variant="ghost" onClick={()=>{setMessageMenu(null);navigate('memory')}}>打开记忆管理</Button>}
        {m.role==='assistant'&&!phoneResult&&!taskResult&&m.taskId&&can('conversation.drafts')&&messageTaskAnchors.get(m.taskId)===index&&<ConversationDrafts key={m.taskId} taskId={m.taskId} refreshRevision={Number(state.memoryRefreshRevision||0)} open={draft=>{setMessageMenu(null);openConversationDraft(draft)}}/>}
       </div></Popover>
      </PopoverTrigger>
     </div>}
     </div>

    </article>;
   })}</div>}
   {conversationStream&&<div className="pw-message-stream"><article className="pw-message pw-message-assistant" aria-label="当前回复草稿" aria-busy={!conversationStream.turnEnded}><header className="pw-stream-status"><span className="pw-stream-activity" role="status">{!conversationStream.turnEnded&&!conversationStream.failed&&<ProgressCircle isIndeterminate size="sm" aria-label="正在回复"/>}<span>{conversationStream.failed?'生成中断 · 草稿已保留':conversationStream.turnEnded?'回复草稿':'正在回复'}</span></span>{state.chatBusy&&!conversationStream.turnEnded&&!conversationStream.failed&&can('tasks.open')&&<Button variant="ghost" size="small" aria-label="查看当前回复的执行过程" onClick={()=>openTask(state.chatActiveTurn.taskId,'process')}>查看过程<RiArrowRightSLine size={15} aria-hidden="true"/></Button>}</header><AIAnswer text={conversationStream.text} busy={!conversationStream.turnEnded} showActions={false}/>{conversationStream.truncated&&<p className="pw-muted">仅预览前 32,768 个字符，完整结果以正式回复为准。</p>}</article></div>}
   {chatPhoneTask&&!taskOpen&&<div className="pw-conversation-phone-viewer">{phoneViewerFor(chatPhoneTask,true)}</div>}
   <ConversationWork tasks={conversationWork} labelStatus={labelStatus} canOpen={can('tasks.open')} onOpen={task=>openTask(task.id,attention(task)&&can('tasks.review')?'review':'overview')}/>
   {state.chatBusy&&(!conversationStream||conversationStream.turnEnded||conversationStream.failed)&&<div className="pw-current-thinking"><div className="pw-current-activity"><ThinkingLoader phase={conversationActivity}/></div>{can('tasks.open')&&<Button variant="ghost" size="small" aria-label="查看当前回复的执行过程" onClick={()=>openTask(state.chatActiveTurn.taskId,'process')}>查看过程<RiArrowRightSLine size={15} aria-hidden="true"/></Button>}</div>}
  </div></div>
  <div className="pw-composer-region">
   {hasNewReplies&&<Button variant="secondary" className="pw-new-replies" onClick={goToLatest}>有新回复 · 回到最新</Button>}
   {conversationAttention&&<Button variant="ghost" className="pw-chat-attention" data-task-id={conversationAttention.id} trailingIcon={RiArrowRightSLine} aria-label={`${can('tasks.review')?taskActionLabel(conversationAttention):'查看任务'}：${conversationAttention.goal}`} disabled={taskOpening} onClick={()=>openTask(conversationAttention.id,can('tasks.review')?'review':'overview')}><span className="pw-chat-attention-copy"><small>{can('tasks.review')?taskActionLabel(conversationAttention):'查看任务'}</small><b>{conversationAttention.goal}</b></span></Button>}
   {intent==='chat'&&workTarget&&<div className="pw-owner-work-target" role="status"><span>补充给：<strong>{workTarget.goal}</strong>{!workTargetCurrent&&' · 原任务状态已变化，请重新核对'}</span><Button variant="ghost" size="small" onClick={()=>setWorkTarget(null)}>回到对话</Button></div>}
   {intent==='chat'&&<ComposerSourceReferences key={`${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.source.remove')}/>}
   {intent==='chat'&&<ComposerPreviewReferences key={`preview:${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.preview.remove')}/>}
   {intent!=='chat'&&<div className="pw-input-mode"><RiToolsLine size={16}/><span>{intent==='background'?'手机后台独立屏':'手机主屏操作'}</span><Button variant="ghost" size="small" onClick={()=>changeIntent('chat')}>切回对话</Button></div>}
   <ChatComposer contextStatus={<><RiChatAiLine size={15} aria-label="当前对话"/>{intent==='chat'&&currentProject&&<RiFolderLine size={15} aria-label="关联项目"/>}{intent==='chat'&&workTarget&&<RiToolsLine size={15} aria-label="任务上下文"/>}</>} controls={channelPhoneModel?<div className="pw-conversation-model-controls" aria-label="渠道手机任务模型"><Button variant="ghost" className="pw-conversation-model-chip" disabled aria-label={`渠道默认模型：${composerModelName||'未选择'}`} title={`渠道默认模型：${composerModelName||'未选择'}`}><RiBox3Line size={19} aria-hidden="true"/><span className="pw-conversation-model-chip-name">渠道默认 · {composerModelName||'未选择模型'}</span></Button></div>:<ConversationModelControls state={state} can={can} act={act} onOpenModel={openModelPicker} pickerSection={modelPickerOpen?modelPickerSection:null} disabled={!ready||modelSelectionLocked} registerBackHandler={registerConversationControlsBack}/>} sendDisabled={!composerModelName||intent==='chat'&&(previewRefsUnavailable||!!workTarget&&!workTargetCurrent)} sendLabel={!composerModelName?'请先连接模型':intent==='chat'&&previewRefsUnavailable?'请重新选择预览元素':undefined} toolbar={<div className="pw-composer-intent"><PopoverTrigger isOpen={voiceOptionsOpen} onOpenChange={setVoiceOptionsOpen}><IconButton icon={intent==='chat'?RiAddLine:RiToolsLine} size="medium" aria-label="输入方式与选项"/><Popover aria-label="输入方式"><PopoverHeader title="输入选项"/>{can('voice.start')&&(!!text.trim()||!!state.chatBusy)&&<Button variant="ghost" disabled={!!state.chatSubmitting||!!state.phoneSubmitting} onClick={()=>{setVoiceOptionsOpen(false);run(isListening?'voice.stop':'voice.start',{phone:intent!=='chat'})}}>{isListening?'结束听写':'语音输入'}</Button>}{intent==='chat'&&can('attachments.openPicker')&&<Button variant="ghost" leadingIcon={RiFileLine} disabled={!!state.chatSubmitting} onClick={()=>{setVoiceOptionsOpen(false);run('attachments.openPicker')}}>添加附件</Button>}<RadioOptions label="输入方式" value={intent} options={[{id:'chat',label:'与主 Agent 对话'},{id:'background',label:'手机后台独立屏',disabled:!can('phone.prepare')},{id:'main_screen',label:'手机主屏操作',disabled:!can('phone.prepare')}]} onChange={value=>{changeIntent(value as any);setVoiceOptionsOpen(false)}}/>{intent==='chat'&&(state.chatBusy||!!workTarget)&&can('composer.mode')&&<section className="pw-input-delivery"><RadioOptions label="补充采用方式" value={state.chatSendMode||'queue'} options={[{id:'queue',label:'本轮结束后处理'},{id:'steer',label:'下个安全步骤加入'}]} onChange={mode=>run('composer.mode',{key:draftKey,epoch:draftEpoch,mode})}/><p className="pw-muted">不撤销已派发动作，也不扩大原预算与授权。</p></section>}</Popover></PopoverTrigger><IconButton icon={RiShieldCheckLine} size="medium" aria-label="执行权限" title="执行权限" aria-haspopup="dialog" aria-expanded={permissionOptionsOpen} onClick={()=>{permissionExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);setPermissionOptionsOpen(true)}}/></div>} composerKey={intent==='chat'?chatOwner:phoneOwner} value={text} onChange={editText} onSend={send} onStop={currentCancelIdentity&&can('chat.cancel')?stopOriginalChat:undefined} busy={intent==='chat'&&!!state.chatBusy} submitting={intent==='chat'?!!state.chatSubmitting:phoneReviewPending||!!state.phoneSubmitting} disabled={!ready||(intent==='chat'?!draftKey:!can('phone.prepare'))} placeholder={intent==='chat'?(workTarget?'继续这件事…':'问点什么…'):'想在手机上做什么？'} attachments={(intent==='chat'?asArray<Json>(state.selectedChatAttachments):[]).map(f=>({...f,id:String(f.id),name:f.name||'附件'}))} onRemoveAttachment={can('attachments.remove')?id=>run('attachments.remove',{id,key:draftKey,epoch:draftEpoch}):undefined} onVoice={can('voice.start')?()=>run(isListening?'voice.stop':'voice.start',{phone:intent!=='chat'}):undefined} listening={isListening} voiceStatus={state.voiceInput?.message} onDiscardVoice={can('voice.stop')||can('voice.discard')?discardVoice:undefined}/>{phoneStart?.status==='unknown'&&!phoneReview&&<section className="pw-input-receipt" aria-label="原手机启动回执"><Receipt receipt={phoneStart}/>{phoneStart.requestId&&phoneStart.conversationId&&can('phone.query')?<Button variant="ghost" size="small" disabled={phoneReviewPending} onClick={queryOriginalPhone}>查询原手机请求</Button>:<Button variant="ghost" size="small" onClick={()=>phoneStart.taskId?openTask(phoneStart.taskId):navigate('tasks')}>核对原任务记录</Button>}</section>}{(state.voiceInput?.reviewAvailable||composer.voiceText)&&<div className="pw-voice-review"><p>有听写文字待核对。</p>{can('voice.review')&&<Button variant="secondary" disabled={voicePending} onClick={openVoiceReview}>核对原听写</Button>}{can('voice.discard')&&<Button variant="ghost" disabled={voicePending} onClick={discardVoice}>丢弃本次听写</Button>}</div>}{pendingInputs.length>0&&<section className="pw-pending-inputs" aria-label="待确认的消息">{pendingInputs.map(p=><div className="pw-pending-input" key={p.clientMessageId}><Receipt receipt={{...p.receipt,status:p.receipt.status||p.status}}/><small>{p.draftKey===draftKey?'当前输入':'另一份工作中的输入'}</small><div className="pw-action-row">{can('composer.query')&&<Button variant="ghost" size="small" onClick={()=>run('composer.query',{clientMessageId:p.clientMessageId})}>确认发送结果</Button>}{p.draftKey!==draftKey&&can('composer.returnPending')&&<Button variant="ghost" size="small" disabled={!!state.chatSubmitting} onClick={()=>run('composer.returnPending',{clientMessageId:p.clientMessageId})}>返回这条消息</Button>}{p.canRetryLocallyUnsent===true&&can('composer.retry')&&<Button variant="secondary" size="small" onClick={()=>run('composer.retry',{clientMessageId:p.clientMessageId})}>重新发送</Button>}</div></div>)}</section>}</div></main>;
 const endedTasks=filteredTasks.filter(t=>terminal(t)&&!attention(t));
 const visibleEnded=filter==='ended'||endedExpanded?endedTasks:endedTasks.slice(0,3);
 const activeTaskStatus=({attention:'待处理',running:'进行中',ended:'已结束'} as Record<string,string>)[filter];
 const hasTaskFilters=filter!=='all'||!!taskQuery;
 const showTaskTools=taskHistory.tasks.length>0||hasTaskFilters||taskFiltersOpen||taskHistory.hasMore||taskHistory.earlier;
 const tasksPage=<main className="pw-page pw-tasks-page pw-depth-tasks" data-tools={showTaskTools}>
    {showTaskTools&&<div className="pw-task-collection-tools">
     <div className="pw-task-status-filters" role="group" aria-label="任务筛选">{[{id:'all',label:'全部'},{id:'attention',label:'待处理'},{id:'running',label:'进行中'},{id:'ended',label:'已结束'}].map(option=><Button key={option.id} variant="ghost" aria-pressed={filter===option.id} onClick={()=>setFilter(option.id)}>{option.label}</Button>)}</div>
     <div className="pw-task-filters-reveal" data-open={taskFiltersOpen} inert={!taskFiltersOpen} aria-hidden={!taskFiltersOpen}><div className="pw-disclosure-clip"><section id="pw-task-filters" className="pw-task-filters" aria-label="搜索与筛选任务">
       <div className="pw-task-list-tools"><SearchField value={taskQuery} onChange={setTaskQuery} aria-label="搜索已加载任务" placeholder="搜索已加载任务"/><IconButton icon={RiHistoryLine} aria-label="查看历史记录" title="历史记录" onClick={()=>navigate('history')}/></div>
     </section></div></div>
     {!taskFiltersOpen&&taskQuery&&<div className="pw-active-filter"><Button variant="ghost" aria-label={`修改任务筛选：${[activeTaskStatus,`关键词：${taskQuery}`].filter(Boolean).join('，')}`} onClick={()=>setTaskFiltersOpen(true)}><span className="pw-filter-summary"><RiSearchLine size={16} aria-hidden="true"/><span className="pw-filter-query">{taskQuery}</span></span></Button><IconButton icon={RiCloseLine} aria-label="清除任务筛选" onClick={()=>{setFilter('all');setTaskQuery('')}}/></div>}
    </div>}
    <section className="pw-task-results" aria-label="任务列表" data-has-active={filteredTasks.some(t=>!attention(t)&&!terminal(t))||undefined}>
      {filteredTasks.some(attention)&&<DecisionInbox compact heading="待你处理" className="pw-priority-decisions" decisions={filteredTasks.filter(attention).map(t=>({id:t.id,title:t.goal,detail:labelStatus(t.status),primary:taskActionLabel(t),secondary:'查看任务'}))} onDecide={async(d,answer)=>{openTask(d.id,answer==='primary'&&can('tasks.review')?'review':'overview')}}/>}
      {filteredTasks.some(t=>!attention(t)&&!terminal(t))&&<section className="pw-task-collection" aria-label="正在进行"><h3>正在进行<span>{filteredTasks.filter(t=>!attention(t)&&!terminal(t)).length}</span></h3><div className="pw-task-list">{filteredTasks.filter(t=>!attention(t)&&!terminal(t)).map(renderTaskRow)}</div></section>}
      {endedTasks.length>0&&<section className="pw-task-collection pw-ended-tasks" aria-label="最近结束"><h3>最近结束<span>{endedTasks.length}</span></h3><div className="pw-task-list">{visibleEnded.map(renderTaskRow)}</div>{filter!=='ended'&&endedTasks.length>3&&<Button variant="ghost" className="pw-show-all-tasks" onClick={()=>setEndedExpanded(value=>!value)}>{endedExpanded?'收起':'查看全部 '+endedTasks.length+' 项'}<RiArrowDownSLine size={16}/></Button>}</section>}
      {!ready&&<p className="pw-muted" role="status">正在读取任务…</p>}
      {ready&&!filteredTasks.length&&!taskHistory.busy&&!taskHistory.error&&<div className="pw-empty pw-task-empty"><RiLayoutGridLine size={24} aria-hidden="true"/><h3>{hasTaskFilters?'没有匹配的任务':taskHistory.earlier||taskHistory.hasMore?'本页没有任务':'还没有任务'}</h3><p>{hasTaskFilters?'换个关键词，或清除筛选查看当前页。':taskHistory.earlier||taskHistory.hasMore?'可以翻页查看其他时间的任务。':'在对话中交办的事情，会在这里显示进展。'}</p>{hasTaskFilters?<Button variant="secondary" onClick={()=>{setFilter('all');setTaskQuery('')}}>清除筛选</Button>:!taskHistory.earlier&&!taskHistory.hasMore&&<Button variant="primary" onClick={()=>navigate('chat')}>开始对话</Button>}</div>}
    </section>
    <TaskListHistoryControls history={taskHistory}/>
    {can('remoteSessions.read')&&<Accordion variant="divided"><AccordionItem id="remote-sessions" title="电脑端托管会话"><RemoteManagedSessionsWorkspace/></AccordionItem></Accordion>}
  </main>;
 const openProjectFile=(path:string)=>{if(!can('projects.open'))return;updateProjectView({selected:path});run('projects.open',{projectId:state.projectId,path})};
 const projectListBusy=asArray<string>(state.moduleBusy).includes('workspace.list')||packageOpening;
 const projectListInput=obj(obj(state.moduleResultInputs)['workspace.list']);
 const projectListError=projectListInput.projectId===state.projectId?String(obj(state.moduleErrors)['workspace.list']||''):'';
 const projectFiles=<>{(files.length>0||!!fileQuery)&&<><div className="pw-project-tools"><SearchField value={fileQuery} onChange={setFileQuery} aria-label="搜索当前项目文件" placeholder="搜索文件"/><PopoverTrigger isOpen={fileDisplayOpen} onOpenChange={setFileDisplayOpen}><IconButton icon={fileView==='tree'?RiFolderLine:RiLayoutGridLine} aria-label="文件显示方式" title="文件显示方式"/><Popover aria-label="文件显示方式"><PopoverHeader title="文件显示"/><RadioOptions label="显示方式" value={fileView} onChange={value=>{setFileView(value as 'files'|'tree'|'grid');setFileDisplayOpen(false)}} options={[{id:'files',label:'文件列表'},{id:'tree',label:'目录层级'},{id:'grid',label:'网格'}]}/><Button variant="ghost" leadingIcon={RiTerminalBoxLine} onClick={()=>{setFileDisplayOpen(false);navigate('local')}}>运行与工具</Button></Popover></PopoverTrigger>{can('projects.newFile')&&<IconButton icon={RiAddLine} aria-label="新文件" title="新文件" onClick={()=>openCreate('file')}/>}</div>
      <div className="pw-project-list-caption"><span>{fileQuery?`匹配 ${shownFiles.length} 个文件`:`${files.length} 个文件`}</span>{fileQuery&&<Button variant="ghost" size="small" onClick={()=>setFileQuery('')}>清空搜索</Button>}{can('projects.refresh')&&<IconButton icon={RiRefreshLine} size="small" aria-label="重新读取项目文件" onClick={()=>run('projects.refresh')}/>}</div>
      </>}<div className="pw-project-objects">{!ready||projectListBusy?<p className="pw-muted" role="status">正在读取项目文件…</p>:projectListError?<div className="pw-empty pw-project-empty" role="alert"><h3>无法读取文件</h3><p>{projectListError}</p>{can('projects.refresh')&&<Button variant="secondary" leadingIcon={RiRefreshLine} onClick={()=>run('projects.refresh')}>重新读取</Button>}</div>:!shownFiles.length?<div className="pw-empty pw-project-empty"><RiFolderLine size={26} aria-hidden="true"/><h3>{fileQuery?'没有匹配的文件':'暂无列出的文件'}</h3><p>{fileQuery?'换个关键词，或清空搜索返回文件列表。':'从一个新文件开始，也可以重新读取当前项目。'}</p>{fileQuery?<Button variant="secondary" onClick={()=>setFileQuery('')}>清空搜索</Button>:<><div className="pw-action-row">{can('projects.newFile')&&<Button variant="primary" leadingIcon={RiAddLine} onClick={()=>openCreate('file')}>新建文件</Button>}{can('projects.refresh')&&<Button variant="ghost" leadingIcon={RiRefreshLine} onClick={()=>run('projects.refresh')}>重新读取</Button>}</div><Button variant="ghost" leadingIcon={RiTerminalBoxLine} onClick={()=>navigate('local')}>运行与工具</Button></>}</div>:fileView==='tree'?<TreeView aria-label="项目文件" key={state.projectId} className="pw-file-tree" data={treeData(shownFiles)} selectedItemId={projectView.selected||undefined} expandedIds={projectView.expanded} onExpandedChange={expanded=>updateProjectView({expanded})} onSelectChange={file=>{if(file&&!file.children)openProjectFile(file.id)}}/>:<FilesBrowser files={shownFiles as any} showToolbar={false} view={fileView==='grid'?'Grid':'List'} onViewChange={view=>setFileView(view==='Grid'?'grid':'files')} onCopyPath={path=>{void copyText(path).catch(()=>{})}} selectedPath={projectView.selected} onOpen={file=>openProjectFile(file.path)}/>}</div></>;
 const projectsPage=<main ref={projectScroll} className={`pw-page pw-projects-page ${state.filePath?'pw-project-file-open':''}`} data-preview-open={projectPreview.expanded&&!state.filePath||undefined} onScroll={event=>{if(!state.filePath&&!projectPreview.expanded)projectListTop.current.set(String(state.projectId||''),event.currentTarget.scrollTop)}}>
  {packageOpening&&<ProgressBar label="正在读取这次任务的固定包" isIndeterminate showValueLabel={false} size="sm"/>}
  {state.filePath?<ProjectFileEditor key={currentFileKey} path={state.filePath} version={Number(state.fileVersion||0)} draft={fileDraft} state={state} conflict={fileConflict} mode={fileMode} setMode={setFileMode} can={can} edit={editFile} save={saveFile} readHistory={()=>run('projects.history',fileIdentity)} readRevision={revision=>run('projects.revision',{...fileIdentity,revision})} retry={()=>run('projects.retry',{projectId:state.projectId,path:state.filePath})} copy={()=>{if(fileDraft)void copyText(fileDraft.text).catch(()=>{})}} adoption={fileAdoption} registerBack={registerProjectFileBack}/>:currentProject?<section className="pw-project-work" aria-label="当前项目工作区">
    {desktopNavigation?<div className="pw-project-connected-workspace" data-preview-open={projectPreview.expanded||undefined}>
      <section className="pw-project-files-pane" aria-label="项目文件"><header className="pw-project-connected-header"><h2>项目文件</h2>{!projectPreview.expanded&&<Button variant="secondary" leadingIcon={RiPlayLine} disabled={!can('projects.preview')} onClick={projectPreview.open}>打开预览</Button>}</header>{projectFiles}</section>
      {projectPreview.expanded&&<section className="pw-project-preview-panel" aria-label="项目预览"><ProjectPreviewPane control={projectPreview} composer={composer}/></section>}
    </div>:(<Tabs selectedKey={projectPreview.expanded?'preview':'files'} onSelectionChange={key=>{if(key==='preview')projectPreview.open();else projectPreview.hide()}} className="pw-project-tabs">
      <TabList aria-label="项目工作方式"><Tab id="files" icon={RiFolderLine}>文件</Tab><Tab id="preview" icon={RiPlayLine} isDisabled={!can('projects.preview')&&!projectPreview.expanded}>预览</Tab></TabList>
      <TabPanel id="files">{projectFiles}</TabPanel>
      <TabPanel id="preview" className="pw-project-preview-panel"><ProjectPreviewPane control={projectPreview} composer={composer}/></TabPanel>
    </Tabs>)}

  </section>:projects.length>0?<section className="pw-project-library" aria-label="项目目录"><header><h2>你的项目</h2>{can('projects.create')&&<Button variant="secondary" leadingIcon={RiAddLine} onClick={()=>openCreate('project')}>新项目</Button>}</header><div>{projects.map(project=><Button key={project.id} variant="ghost" className="pw-project-library-row" leadingIcon={RiFolderLine} trailingIcon={RiArrowRightSLine} disabled={!can('projects.select')} onClick={()=>run('projects.select',{id:project.id})}>{project.name}</Button>)}</div></section>:!ready?<p className="pw-muted" role="status">正在读取项目…</p>:<div className="pw-empty pw-project-empty"><RiFolderLine size={26} aria-hidden="true"/><h3>从一个项目开始</h3><p>把文件、编辑和预览放在一起，随时接着做。</p>{can('projects.create')&&<Button variant="primary" leadingIcon={RiAddLine} onClick={()=>openCreate('project')}>新建项目</Button>}</div>}
 </main>;
 return <ReadingContext.Provider value={openReading}><Drawer.Root open={workOpen&&!desktopNavigation} onOpenChange={changeNavigation} direction="left" autoFocus shouldScaleBackground={false}>
 <div className="pw-app pw-redesign" data-navigation={desktopNavigation?'sidebar':'drawer'} data-route={route} data-file-open={!!state.filePath||undefined} data-vaul-drawer-wrapper=""><Toaster theme={state.appearance?.dark===true?'dark':'light'}/>
  {desktopNavigation&&<aside className="pw-desktop-navigation pw-navigation-drawer" data-compact={effectiveCompact?'true':'false'} aria-label="侧边工作导航" onKeyDown={event=>{if(commandOpen&&event.key==='Escape'&&!event.nativeEvent.isComposing&&event.nativeEvent.keyCode!==229){event.preventDefault();setCommandOpen(false)}}}>{navigationContent}</aside>}
  <div className="pw-main-workspace" ref={workspace}>
  {isPrimaryRoute&&(route!=='chat'||desktopNavigation)&&<header className="pw-app-header">
   {route==='projects'&&state.filePath?<IconButton icon={RiArrowLeftLine} aria-label="返回项目文件" disabled={fileAdoption.pending||!!state.fileSaving} onClick={()=>{if(!fileAdoption.isPending()&&!bridge.getState().state.fileSaving&&!projectFileBack.current?.())run('projects.close',fileIdentity)}}/>:route==='projects'&&state.projectTaskReturn?.taskId?<IconButton icon={RiArrowLeftLine} aria-label="回到原任务" onClick={()=>openTask(state.projectTaskReturn.taskId)}/>:navigationTrigger}
   {route==='chat'?<div className="pw-current-work pw-chat-title"><h1 title={currentTitle} aria-label={messages.length?currentTitle:'PocketWorld'}>{messages.length>0&&currentConversation&&can('conversation.organization.read')?<Button variant="ghost" className="pw-chat-title-trigger" aria-label="整理当前对话" onClick={()=>setOrganizationTarget(currentConversation.id)}><span className="pw-chat-title-text">{currentTitle}</span><RiArrowDownSLine size={16} aria-hidden="true"/></Button>:messages.length?currentTitle:'PocketWorld'}</h1></div>:route==='projects'&&!state.filePath?<PopoverTrigger isOpen={projectPickerOpen} onOpenChange={setProjectPickerOpen}><Button variant="ghost" className="pw-object-title" aria-label="切换项目"><h1>{currentTitle}</h1><RiArrowDownSLine size={16}/></Button><Popover aria-label="切换项目"><PopoverHeader title="项目"/><div className="pw-project-menu">{projects.map(project=><Button key={project.id} variant="ghost" aria-current={project.id===state.projectId?'true':undefined} onClick={()=>{setProjectPickerOpen(false);run('projects.select',{id:project.id})}}><RiFolderLine size={17}/><span>{project.name}</span>{project.id===state.projectId&&<small>当前</small>}</Button>)}{can('projects.create')&&<Button variant="secondary" leadingIcon={RiAddLine} onClick={()=>{setProjectPickerOpen(false);openCreate('project')}}>新项目</Button>}</div></Popover></PopoverTrigger>:<div className="pw-current-work"><BlurCrossfade identity={currentTitle}><h1>{currentTitle}</h1></BlurCrossfade></div>}
   {route==='chat'&&can('remoteInbox.state')&&Number(state.remoteInbox?.unread||0)>0&&<IconButton icon={RiNotification3Line} aria-label={Number(state.remoteInbox?.unread||0)>0?'通知 · '+state.remoteInbox.unread+' 条未读':'查看通知'} title="通知" onClick={()=>navigate('remoteInbox')}/>}
   {route==='chat'&&can('conversation.new')?<IconButton icon={RiChatNewLine} aria-label="新建对话" title="新对话" disabled={!!state.chatSubmitting} onClick={()=>run('conversation.new')}/>:route==='tasks'?<div className="pw-primary-tools">{can('tasks.refresh')&&<IconButton icon={RiRefreshLine} aria-label="刷新任务" onClick={()=>{taskHistory.reset();run('tasks.refresh')}}/>}{showTaskTools&&<IconButton icon={RiSearchLine} aria-label="搜索与筛选任务" aria-expanded={taskFiltersOpen} aria-controls="pw-task-filters" onClick={()=>setTaskFiltersOpen(value=>!value)}/>}</div>:route==='projects'&&(state.filePath||state.projectTaskReturn?.taskId)?navigationTrigger:<span className="pw-header-spacer" aria-hidden="true"/>}
  </header>}
  {(state.chatBusy&&!composerHasChatStop||activeTask&&!terminal(activeTask))&&<div className="pw-active-bar">{state.chatBusy&&!composerHasChatStop&&<span className="pw-inline-status"><ProgressCircle aria-label="当前对话执行状态" isIndeterminate size="sm"/>{state.chatActiveTurn?.resourceState==='queued'?'等待执行位置':state.chatActiveTurn?.resourceState==='retained'?'原任务等待补充':state.chatActiveTurn?.resourceState==='stopping'?'正在停止':'正在回复'}</span>}{activeTask&&!terminal(activeTask)&&<button type="button" onClick={()=>openTask(activeTask.id)}>{labelStatus(activeTask.status)} · {activeTask.goal}<RiArrowRightSLine size={14}/></button>}<span className="pw-grow"/>{currentCancelIdentity&&can('chat.cancel')&&!composerHasChatStop&&<Button variant="ghost" size="small" onClick={stopOriginalChat}>停止回复</Button>}{activeTask&&activePhoneIdentity&&activeTask.status==='running'&&can('phone.pause')&&<Button variant="ghost" size="small" onClick={()=>run('phone.pause',activePhoneIdentity)}>暂停手机任务</Button>}{activeTask&&activePhoneIdentity&&activeTask.status==='paused'&&can('phone.resume')&&<Button variant="ghost" size="small" onClick={()=>run('phone.resume',activePhoneIdentity)}>继续原任务</Button>}{activeTask&&!terminal(activeTask)&&can('phone.stop')&&<Button variant="ghost" size="small" onClick={()=>{if(activePhoneIdentity)run('phone.stop',activePhoneIdentity)}} disabled={!activePhoneIdentity||activeTask.status==='stopping'}>{activeTask.status==='stopping'?'正在停止':'停止手机任务'}</Button>}</div>}{currentCancelFeedback&&<div className={currentCancelFeedback.error?'pw-banner pw-banner-error':'pw-banner'} role={currentCancelFeedback.error?'alert':'status'}><p>{currentCancelFeedback.message}</p></div>}{(localError||state.error)&&<div className="pw-banner pw-banner-error" role="alert"><RiInformationLine size={17}/><p>{localError||state.error}</p>{localError&&<IconButton icon={RiCloseLine} aria-label="关闭这条操作提示" onClick={()=>setLocalError('')}/>}</div>}{Object.entries(obj(state.attachmentOpens)).filter(([,value])=>value.error).map(([key,value])=><div className="pw-banner pw-banner-error" role="alert" key={key}><p>{value.error}</p></div>)}{(localStatus||state.notice)&&<div className="pw-banner" role="status"><p>{localStatus||state.notice}</p>{localStatus&&<IconButton icon={RiCloseLine} aria-label="关闭这条提示" onClick={()=>setLocalStatus('')}/>}</div>}
  {isPrimaryRoute?<div className="pw-workspace pw-route-workspace">{route==='chat'?chatPage:route==='tasks'?tasksPage:projectsPage}</div>:route==='remoteInbox'&&can('remoteInbox.state')?<div className="pw-workspace pw-route-workspace"><RemoteInboxWorkspace state={state.remoteInbox} navigationTrigger={navigationTrigger} onBack={()=>navigate('chat')}/></div>:route==='developer-logs'?<div className="pw-workspace pw-route-workspace"><DeveloperDiagnosticsWorkspace onBack={()=>navigate('chat')} navigationTrigger={navigationTrigger}/></div>:route==='setup'?<div className="pw-workspace pw-route-workspace"><SetupWorkspace key={`setup:${setupMode}:${setupCapability||''}:${setupRuntimeTask||''}`} snapshot={snapshot} mode={setupMode} initialCapability={setupCapability} runtimeTaskId={setupRuntimeTask} onNavigate={navigateFromSetup} onClose={closeSetup} registerBackHandler={registerSecondaryBack}/></div>:route==='profile'?<div className="pw-workspace pw-route-workspace"><ProfileWorkspace state={state} presentationKey={`${personalDocumentEpoch}:${Number(snapshot?.view?.resumeRevision||0)}:${String(snapshot?.view?.taskId||'')}:${String(snapshot?.view?.section||'')}`} supported={can} run={bridge.command} onBack={returnFromProfile} onSettings={()=>{void navigate('group:data')}} registerBack={registerSecondaryBack} registerBeforeLeave={registerProfileBeforeLeave}/></div>:<Secondary route={route} onBack={returnFromSecondary} snapshot={snapshot} registerBackHandler={registerSecondaryBack} navigationTrigger={navigationTrigger} navigationRevision={navigationRevision} onNavigationPageChange={setSecondaryPage} onOpenSetup={can('setup.read')?openSetup:undefined} onReturnToSetup={setupReturnPending.current?returnFromSecondary:undefined}/> }
  </div>{allWork}
  <ConversationHistoryWindow page={state.conversationReader||null} submitting={!!state.chatSubmitting} can={can} act={act} registerBack={registerHistoryWindowBack}/>
  <ConversationQueuedInputs page={state.queuedInputsPage||null} can={can} act={act} registerBack={registerQueuedInputsBack}/>
  <AttachmentWorkspace state={state} registerBack={registerAttachmentBack}/>
  {organizationTarget&&<ConversationOrganization key={organizationTarget} conversationId={organizationTarget} onClose={()=>setOrganizationTarget(null)}/>}
  <Drawer.Root open={taskOpen} onOpenChange={open=>{if(!open){if(!capabilityBack.current?.()&&!livePreviewBack.current?.()&&!taskTransientBack.current?.())closeTask()}else setTaskOpen(true)}} shouldScaleBackground><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-task-drawer" data-task-layer={taskLayer} aria-describedby="pw-task-description"><header className="pw-drawer-header pw-task-header"><IconButton icon={RiArrowLeftLine} aria-label={taskLayer==='overview'?'返回原工作':'返回任务结果'} onClick={backFromTask}/><div><Drawer.Title>{taskLayer==='overview'?selectedTask?.goal||'任务':taskLayer==='process'?'执行过程':'本次范围'}</Drawer.Title><Drawer.Description id="pw-task-description" className={taskLayer==='overview'?'pw-sr-only':'pw-task-context'}>{taskLayer==='overview'?'这份任务的状态、结果与当前决定':selectedTask?.goal||'当前任务详情'}</Drawer.Description></div>{taskLayer==='overview'&&selectedTaskStop&&selectedTask?.raw?.executionRole!=='owner_work'&&<Button variant="ghost" size="small" onClick={()=>run('tasks.stop',{id:selectedTask!.id,runId:selectedTask!.raw?.runId??'',epoch:selectedTask!.raw?.coordinatorEpoch})} disabled={selectedTask?.status==='stopping'}>{selectedTask?.status==='stopping'?'正在停止':'停止'}</Button>}</header>{selectedTask&&<>{taskErrors[selectedTask.id]&&<div className="pw-banner pw-banner-error pw-task-local-error" data-task-id={selectedTask.id} role="alert"><RiInformationLine size={17}/><p>{taskErrors[selectedTask.id]}</p></div>}{state.taskActionErrors?.[selectedTask.id]&&state.taskActionErrors[selectedTask.id]!==taskErrors[selectedTask.id]&&<div className="pw-banner pw-banner-error pw-task-local-error" data-task-id={selectedTask.id} role="alert"><RiInformationLine size={17}/><p>{state.taskActionErrors[selectedTask.id]}</p></div>}</>}<div ref={taskScroll} className="pw-drawer-body pw-task-body" onScroll={rememberTaskPosition}>{selectedTask&&state.originalTaskNotice?.taskId===selectedTask.id&&<section className="pw-selected-object" aria-label="原任务通知"><h3>{state.originalTaskNotice.content?.title||"原任务提醒"}</h3><p style={{whiteSpace:"pre-wrap"}}>{state.originalTaskNotice.content?.body||"通知正文请在原任务执行过程的 post_notice 记录中核对。"}</p><p className="pw-muted">{state.originalTaskNotice.expired?"这条提醒属于先前的执行状态。":""}{state.originalTaskNotice.content?.message||"通知接口回执不代表已经显示或阅读。"}</p><Button variant="ghost" size="small" onClick={()=>run("notice.task.close")}>收起提醒</Button></section>}{selectedTask?<>{taskReviewBody(selectedTask)}</>:taskOpening?<ThinkingLoader phase="正在读取原任务"/>:<section className="pw-empty-small"><p className="pw-error" role="alert">{taskId&&taskErrors[taskId]||'原任务暂不可读取，请重新读取原历史窗口。'}</p>{taskId&&can('tasks.open')&&<Button variant="secondary" onClick={()=>openTask(taskId,taskLayer)}>重新读取原任务</Button>}</section>}</div>{selectedTask&&taskFooter(selectedTask)}</Drawer.Content></Drawer.Portal></Drawer.Root>
  <Drawer.Root open={!!phoneReview} onOpenChange={open=>{if(!open&&!phoneReviewPending)setPhoneReview(null)}} dismissible={!phoneReviewPending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-scope-drawer" aria-describedby="pw-phone-description" inert={!phoneReview} onCloseAutoFocus={phoneExit.onCloseAutoFocus}><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>核对这次手机任务</Drawer.Title><Drawer.Description id="pw-phone-description">目标、执行位置与本次范围</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="关闭手机任务核对" disabled={phoneReviewPending} onClick={()=>setPhoneReview(null)}/></header><div className="pw-drawer-body">{phoneDisplay&&<PermissionReview key={phoneExit.generation} title={phoneDisplay.goal} context={phoneDisplay.mode==='background'?'在后台独立屏执行':'在手机主屏执行'} reason={[phoneDisplay.message,phoneDisplay.screenDisclosure].filter(Boolean).join(' ')} fields={[...(phoneDisplay.conversationId?[{label:'结果返回',value:phoneDisplay.conversationTitle||'原私人对话'}]:[]),...(phoneDisplay.modelName?[{label:'本次模型',value:phoneDisplay.modelName}]:[]),{label:'工具调用上限',value:phoneDisplay.toolLimit},{label:'模型调用上限',value:phoneDisplay.modelCallLimit},{label:'本次期限',value:phoneDisplay.scopeMinutes<0?'使用原授权期限':phoneDisplay.scopeMinutes===0?'不设截止时间':`${phoneDisplay.scopeMinutes} 分钟`}]} onReject={phoneReviewPending?undefined:()=>{if(phoneReviewLive.current)setPhoneReview(null)}} onApprove={can('phone.start')&&phoneStart?.status!=='unknown'?startReviewedPhone:undefined} pending={phoneReviewPending} approveLabel="开始这次任务"/>}{phoneStart&&phoneStart.status!=='accepted'&&<><Receipt receipt={phoneStart}/>{phoneStart.status==='unknown'&&(phoneStart.requestId&&phoneStart.conversationId&&can('phone.query')?<Button variant="secondary" disabled={phoneReviewPending} onClick={()=>{if(phoneReviewLive.current)queryOriginalPhone()}}>查询原手机请求</Button>:<Button variant="secondary" onClick={()=>{if(!phoneReviewLive.current)return;setPhoneReview(null);if(phoneStart.taskId)openTask(phoneStart.taskId);else navigate('tasks')}}>核对原任务记录</Button>)}</>}</div></Drawer.Content></Drawer.Portal></Drawer.Root>
  <SettingsDialog isOpen={newProjectOpen||newFileOpen} title={createKind==='project'?'新项目':'新文件'} onClose={closeCreate} closeLabel="关闭创建" className="pw-create-dialog">
   <form className="pw-create-form" onSubmit={event=>{event.preventDefault();if(!createBusy&&(createKind==='project'?projectName:newPath).trim())createObject()}}>
    <TextField label={createKind==='project'?'项目名称':'文件路径'} isDisabled={createBusy} value={createKind==='project'?projectName:newPath} onChange={createKind==='project'?setProjectName:setNewPath} placeholder={createKind==='project'?'输入项目名称':'例如 index.html'}/>
    <footer><Button type="button" variant="ghost" onClick={closeCreate}>取消</Button><Button type="submit" disabled={createBusy||!(createKind==='project'?projectName:newPath).trim()}>{createBusy?'正在创建…':'创建'}</Button></footer>
   </form>
  </SettingsDialog>
 <Drawer.Root open={!!voiceReview} onOpenChange={open=>{if(!open)setVoiceReview(null)}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-scope-drawer" aria-describedby="pw-voice-description" inert={!voiceReview} onCloseAutoFocus={voiceExit.onCloseAutoFocus}><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>核对听写文字</Drawer.Title><Drawer.Description id="pw-voice-description">采用后放入原输入，暂不发送</Drawer.Description></div><Drawer.Close asChild><IconButton icon={RiCloseLine} aria-label="关闭听写核对"/></Drawer.Close></header><div className="pw-drawer-body"><TextAreaField label="原听写文字" value={voiceText} isReadOnly={!voiceReview} onChange={value=>{if(voiceReviewLive.current)setVoiceText(value)}}/></div><footer className="pw-drawer-actions"><Button variant="secondary" disabled={voicePending||!voiceReview} onClick={()=>submitVoiceReview('voice.discard')}>丢弃</Button><PressButton label="采用到原输入" disabled={voicePending||!voiceReview||!voiceText.trim()} onClick={()=>submitVoiceReview('voice.accept')}/></footer></Drawer.Content></Drawer.Portal></Drawer.Root>
 <Drawer.Root open={!!state.chatAttachmentPickerOpen} onOpenChange={open=>{if(!open&&can('attachments.closePicker'))run('attachments.closePicker')}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-attachment-drawer" aria-describedby="pw-attachment-description"><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>选择附件</Drawer.Title><Drawer.Description id="pw-attachment-description">选择后随消息发送，最多 4 个</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="关闭附件选择" onClick={()=>run('attachments.closePicker')}/></header><div className="pw-drawer-body">{!state.chatAttachmentModel?.supportsImageInput&&<p className="pw-muted">当前模型尚未配置图像输入，可选择受支持文档。</p>}{state.features?.attachments?.indexDiagnostics?.incomplete===true&&<p className="pw-error" role="alert">部分附件尚未核对完整，可读文件仍可选择。未确认的原件已保留，请核对后再备份。</p>}<div className="pw-attachment-objects">{state.attachmentImporting&&<ProgressBar label="正在导入附件库" isIndeterminate showValueLabel={false} size="sm"/>}{asArray<Json>(state.features?.attachments?.attachments).map(file=>{const selected=asArray<Json>(state.selectedChatAttachments).some(f=>f.id===file.id);const image=String(file.mimeType||'').startsWith('image/');return <div className="pw-attachment-object" key={`${file.id}:${file.sha256}`}><CheckboxField isSelected={selected} isDisabled={!can('attachments.toggle')||(!selected&&image&&!state.chatAttachmentModel?.supportsImageInput)} onChange={()=>run('attachments.toggle',{id:file.id,sha256:file.sha256})}><span className="pw-attachment-copy"><b>{file.name||'未命名附件'}</b><small>{file.mimeType||'文件'} · {Number(file.size||0).toLocaleString()} bytes</small></span></CheckboxField></div>})}</div>{!asArray<Json>(state.features?.attachments?.attachments).length&&<div className="pw-empty-small">{state.features?.attachments?.indexDiagnostics?.incomplete===true?'暂未读到可选择的附件，请核对未确认的原件。':'还没有附件，可以先导入。'}</div>}<p className="pw-muted">图片 ≤ 4 MiB，文档 ≤ 20 MiB。</p></div><footer className="pw-drawer-actions">{can('attachments.pick')&&<Button variant="secondary" leadingIcon={RiAddLine} disabled={!!state.attachmentImporting} onClick={()=>run('attachments.pick')}>{state.attachmentImporting?'正在导入…':'导入新附件'}</Button>}<span className="pw-grow"/><PressButton label="保留选择" onClick={()=>run('attachments.closePicker')}/></footer></Drawer.Content></Drawer.Portal></Drawer.Root>
 <Drawer.Root open={permissionOptionsOpen} onOpenChange={open=>{if(!open)closePermissionOptions()}} dismissible={!permissionPending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-permission-drawer" aria-describedby="pw-permission-description" onCloseAutoFocus={permissionExit.onCloseAutoFocus}>
  <div className="pw-drawer-handle"/>
  <header className="pw-drawer-header"><div><Drawer.Title>执行权限</Drawer.Title><Drawer.Description id="pw-permission-description" className="sr-only">设置工具操作的审批方式，查看本次任务范围。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="关闭执行权限" onClick={closePermissionOptions} disabled={permissionPending}/></header>
  <div className="pw-drawer-body pw-permission-sheet-body">{permissionOptionsOpen&&<>
   {permissionView?.needsReview&&<div className="pw-permission-pending-review"><span role="status">{labelStatus(permissionView.status)}</span>{can('tasks.open')&&<Button variant="ghost" disabled={permissionPending} onClick={openPermissionTask}>{permissionReviewLabel}</Button>}</div>}
   {can('permissions.snapshot')?<PermissionPolicySettings key={String(state.conversationId||'')} conversationId={String(state.conversationId||'')} act={act} onPendingChange={permissionSaving}>{permissionTaskDetails}</PermissionPolicySettings>:<><p className="pw-muted">当前版本暂不支持修改审批设置。</p>{permissionTaskDetails}</>}

  </>}</div>
 </Drawer.Content></Drawer.Portal></Drawer.Root>
 <Popover triggerRef={modelPickerAnchor} isOpen={modelPickerOpen} onOpenChange={open=>{if(!open)closeModelPicker()}} placement="top start" offset={8} shouldFlip containerPadding={16} maxHeight={modelPickerSection==='models'?560:480} scrollRef={modelPickerScroll} isKeyboardDismissDisabled={modelSelectPending||!!state.modelSelectionBusy} aria-label={modelPickerSection==='models'?'选择此对话的模型':'选择此对话的推理强度'} className={`pw-conversation-picker-popover ${modelPickerSection==='models'?'pw-conversation-model-popover':'pw-conversation-reasoning-popover'}`} dialogClassName="pw-conversation-picker-dialog">
  <header className="pw-conversation-picker-header"><h2>{modelPickerSection==='models'?'选择模型':'推理强度'}</h2><IconButton icon={RiCloseLine} aria-label={modelPickerSection==='models'?'关闭模型选择':'关闭推理设置'} onClick={closeModelPicker} disabled={modelSelectPending||!!state.modelSelectionBusy}/></header>
  {modelPickerOpen&&<div ref={modelPickerScroll} className="pw-conversation-picker-body">{modelPickerSection==='models'?<AIModelPicker key={`models:${pickerToken}`} state={state} can={can} act={act} scope="conversation" onSelectTarget={selectConversationModel} onSelectionPending={modelPickerPending} onSelected={modelPickerSelected} presentationState={modelPickerPresentation} onPresentationChange={setModelPickerPresentation} onManage={()=>{if(modelWriting.current||state.modelSelectionBusy)return;finishModelPicker();navigate('models')}}/>:can('models.conversation.review')&&<ScopedConversationReasoningPanel key={`reasoning:${pickerToken}`} state={state} can={can} act={act} onSelectionPending={modelPickerPending} onSelected={modelPickerSelected}/>}</div>}
  {state.chatBusy&&<p className="pw-model-selection-running">当前任务继续使用启动时的模型。</p>}
 </Popover>
 {routeReferenceTarget&&<RouteReference key={`${routeReferenceTarget.taskId}:${routeReferenceTarget.runId}:${routeReferenceTarget.epoch}`} target={routeReferenceTarget} onClose={()=>setRouteReferenceTarget(null)}/>}
 {skillDraftTarget&&<SkillDraftReview key={`${skillDraftTarget.runId}:${skillDraftTarget.operationId}`} target={skillDraftTarget} onClose={()=>setSkillDraftTarget(null)}/>}
 {memoryDraftTarget&&<MemoryDraftReview key={`${memoryDraftTarget.runId}:${memoryDraftTarget.operationId}`} target={memoryDraftTarget} onClose={()=>setMemoryDraftTarget(null)}/>}
 {state.quickPanelVoice&&<QuickPanelVoiceDraft key={state.quickPanelVoice.handoffId} handoff={state.quickPanelVoice} input={obj(state.voiceInput)} run={act}/>}
 {messageQuoteTarget&&<MessageQuote key={`${messageQuoteTarget.key}:${messageQuoteTarget.epoch}:${messageQuoteTarget.messageId}`} target={messageQuoteTarget} onClose={()=>setMessageQuoteTarget(null)}/>}
 {sourceSelection&&<AnswerSources key={sourceSelection.messageId||sourceSelection.taskId} target={sourceSelection} revision={Number(state.memoryRefreshRevision||0)} onClose={()=>setSourceSelection(null)}/>}
 <ReadingView documents={readingDocuments} onBack={closeReading}/>
 </div></Drawer.Root></ReadingContext.Provider>;
}

