import {TeamOriginalFileReceipt} from './TeamOriginalFileReceipt';
import {TeamRemainingFiles} from './TeamRemainingFiles';
import { FluidValueSlider } from './forms-library/uiux/FluidValueSlider';
/**
 * PocketWorld's controlled work surface.
 * Layout/regions are adapted from the selected Affect AssistantHome, AgentRun,
 * Files/Folder and ReviewCenter sources, retained under shell-library/upstream.
 * Real source components are imported below. Native task/draft/version authorities
 * remain in the host; a bridge acceptance never becomes a fabricated success.
 */
import {Fragment,lazy,Suspense,useCallback,useEffect,useLayoutEffect,useMemo,useRef,useState,type ReactNode} from 'react';
import {motion} from 'motion/react';
import { IconAccount, IconAdd, IconArrowLeft, IconBook, IconCalendar, IconCalendarCheck, IconChat, IconCheckCircle, IconChevronDown, IconChevronRight, IconClock, IconClose, IconError, IconFile, IconFolder, IconGrid, IconHistory, IconImage, IconInfo, IconLayers, IconLink, IconMemory, IconMenu, IconMonitor, IconMore, IconNewChat, IconNotification, IconPlay, IconQuote, IconRefresh, IconSearch, IconServer, IconSettings, IconShieldCheck, IconSparkles, IconStop, IconTeam, IconTerminal, IconTool } from './icons';
import {WorkspaceNavigator,type OwnerNavigationMemory} from './WorkspaceNavigator';

import {bridge} from './bridge';
import {useConversationDraftSwitch} from './useConversationDraftSwitch';
import {usePocketSnapshot} from './usePocketSnapshot';
import {foregroundConversationDraft} from './conversation-stream';
import {replyActivityPhase,useReplyRows} from './ConversationReply';
import {ConversationLinkedReason,ConversationOpening,ConversationStarters,ConversationTurnActivity} from './ConversationSurface';
import {MotionFeedback,useCollectionMotion} from './library/journey-motion';
import {useConversationReading} from './useConversationReading';
import {ConversationReadingNav} from './ConversationReadingNav';
import {ConversationDelivery,ConversationDraftStatus,SavedConversationDelivery,savedConversationDelivery,savedLocalScreenResult,conversationPending,useConversationRegistration} from './ConversationDelivery';
import {WorkSearch,type WorkSearchItem,type WorkSearchMemory} from './WorkSearch';
import {WorkSearchOriginalReader} from './WorkSearchOriginalReader';
import {ComposerWorkOptions} from './ComposerWorkOptions';
import {ComposerAttachmentPicker,attachmentPagingTool} from './ComposerAttachmentPicker';
import {exactFileTarget,type AttachmentFrame} from './attachment-workspace';
import type {CapabilityWorkspaceMemory} from './CapabilitiesWorkspace';
import {usePocketAppearance} from './usePocketAppearance';
import {useClosingSnapshot,useMessageArrivals,useReviewFocus,useSurfaceEnter} from './library/surface-motion';
import {chatCancelIdentity,chatCancelKey,chatStopObservation,sameChatCancelIdentity,type ChatStopRecord} from './chat-cancel';
import {presentOperation,getRecentTaskProgress,taskNeedsReconciliation,observedLateProjectDependency,observedProjectDependencyHold,getLoadedFixedDraftGoalDecision} from './task-presentation';
import {TaskHistory,originalPhoneToolRecord,type OriginalPhoneToolRecord} from './TaskHistory';
import {composerHasPayload} from './composer-send-policy';
import {composerPermissionView} from './composer-permissions';
import {PermissionPolicySettings,type PermissionLeaveGuard} from './PermissionPolicySettings';
import {taskHistoryIdentity} from './task-history';
import {useWorkDirectory} from './WorkDirectory';
import {WorkFindTitle,WorkRecoveryDirectory} from './WorkRecoveryDirectory';
import {isButlerHomeWork,workCollectionPresentation,type WorkCollectionFilter} from './work-collection';
import type {CapabilityPurpose} from './capability-journey';
import {AgentAvatar,PressButton,ChatComposer,AIAnswer,MessageActions,ToolCall,ThinkingLoader,CitedAnswer,BlurCrossfade,Skeleton,ShimmerText,Toaster,toast,ProgressBar,ProgressCircle} from './library';
import {Button,IconButton,TextField,TextAreaField,SearchField,SelectField,RadioOptions,Accordion,AccordionItem,SettingsDialog} from './forms-library';
import {Drawer,PopoverTrigger,Popover,PopoverHeader,TreeView,FilesBrowser,Tabs,TabList,Tab,TabPanel,PermissionReview,type WorkNavItem} from './shell-library';
import {SecondaryRouteBoundary,SecondaryRoutePending} from './SecondaryRouteBoundary';
import type {OriginalChatReminderRuleVisit,ReminderNoticeVisit} from './ReminderWorkspace';
import {OriginalChatReminderReview} from './OriginalChatReminderReview';
import type {SettingsDirectoryOrigin} from './SettingsDirectory';
import ProfileWorkspace,{type PersonalTarget} from './ProfileWorkspace';
import {ConversationMemorySettings,type ConversationMemoryControls} from './ConversationMemorySettings';
import {NewConversationMemory} from './NewConversationMemory';
import DeveloperDiagnosticsWorkspace from './DeveloperDiagnosticsWorkspace';
import {SetupWorkspace,type SetupDestination,type SetupView} from './SetupWorkspace';
import {readSetup,type SetupCapability} from './setup-contract';
import {ownerWindowAvailable} from './background-settings';
import {backgroundConnection} from './forms-library/host-contracts';
import {AttachmentWorkspace,matchesStandaloneDocumentBook,type FileDraftTarget,type FileResultTopic,type OriginalAttachmentChoice} from './AttachmentWorkspace';
import type {SavedFileNote} from './AttachmentDocumentEditor';
import RemoteInboxWorkspace,{type NotificationViewMemory} from './RemoteInboxWorkspace';
import type {TeamAttentionLocator} from './NotificationTeamAttention';
import {ConversationOrganization,orderConversations} from './ConversationOrganization';
import {ConversationQueuedInputs} from './ConversationQueuedInputs';
import {ConversationHistoryWindow,type ConversationHistoryTaskOrigin,type HistoryResultOrigin} from './ConversationHistoryWindow';
import {MessageAttachment} from './MessageAttachment';
import {ChatNavigationMenu} from './ChatNavigationMenu';
import {ConversationDeliveredFiles,type DeliveredFileSource} from './ConversationDeliveredFiles';
import {useNavigationGlass} from './useNavigationGlass';
import {NavigationChrome} from './NavigationChrome';
import {WorkObjectMenu} from './WorkObjectMenu';
import {retainedLocalScriptOutputs,TaskCapabilityWorkspace} from './TaskCapabilityWorkspace';
import {DeviceTaskScopeWorkspace} from './DeviceTaskScopeWorkspace';
import type {SentPhoneGoalFocus} from './devices-workspace';
import {PhoneControlWorkspace} from './PhoneControlWorkspace';
import {OriginalWindowsTextOverview} from './OriginalWindowsTextRead';
import {ProjectRuntimeHealth} from './ProjectRuntimeHealth';
import {ProjectLivePreview} from './ProjectLivePreview';
import {ProjectExecutionWorkspace} from './ProjectExecutionWorkspace';
import {bareManagedNodeCheck,currentManagedNodeOperation,originalDirectViteEditorReturn,originalFirstBuildFailure,reviewedManagedNodePending,type OriginalManagedNodePending} from './project-execution';
import {failedBuildFeedback,nodeBuildContinuationText,originalNodeBuildAfterDependencies,projectFeedbackKey,type ExecutionExit} from './project-execution-exit';
import {RemoteTaskWorkspace,RemoteManagedSessionsWorkspace} from './RemoteTaskWorkspace';
import {AnswerSources,type AnswerSourceTarget} from './AnswerSources';
import {ProjectConventionProvided} from './ProjectConventionProvided';
import {ProvidedMemories} from './ProvidedMemories';
import {originalAnswerMemoryMessage} from './memory-history';
import {MessageQuote,type MessageQuoteTarget} from './MessageQuote';
import {MessageOriginReader,type MessageOriginTarget} from './MessageOriginReader';
import {PhoneReturnedReport,type PhoneReportTarget} from './PhoneReturnedReport';
import {PhoneMessageReturn} from './PhoneMessageReturn';
import {ReadingView} from './ReadingView';
import {ReadingContext,type ReadingDocument,type ReadingSnapshot} from './reading-context';
import {ComposerSourceReferences,ComposerObjectReferences} from './ComposerSourceReferences';
import {ComposerPreviewReferences} from './ComposerPreviewReferences';
import {ProjectLocalSourceIntake} from './ProjectLocalSourceIntake';
import {ProjectGitIntake,type PublicGitDraft} from './ProjectGitIntake';
import {ProjectPreviewPane,useProjectPreview} from './ProjectPreviewPane';
import {ProjectFileEditor} from './ProjectFileEditor';
import {ProjectRemovedFiles} from './ProjectRemovedFiles';
import {ProjectKeptDraftIndex} from './ProjectKeptDrafts';
import {ProjectWorkspaceOverview,type ProjectWorkCollectionView,type ProjectWorkEntry} from './ProjectWorkspaceOverview';
import {ProjectDevelopmentWorkspace,canEditImportedCheckout,originalImportedEditor,originalPreparedLocalCopy,projectBuildEditorResume,sameImportedEditor,type ProjectBuildCopy,type ProjectImportedEditor,type ProjectLocalFilesIntent,type ProjectPreparedLocalCopy,type ProjectTerminalSavedFileReturn} from './ProjectDevelopmentWorkspace';
import type {CheckoutSavedPosition} from './ProjectCheckoutFiles';
import {ProjectSavedBuildWeb} from './ProjectSavedBuildWeb';
import type {ProjectDraftReturn} from './ProjectConventionDraftAction';
import {ContinuedProjectBinding} from './ProjectWorkBinding';
import {ProjectWorkStart,emptyProjectWorkDraft,type ProjectWorkDraft} from './ProjectWorkStart';
import {ProjectWorkLauncher,type ProjectWorkPresentation} from './ProjectWorkLauncher';
import type {LocalTerminalCheckout,LocalWorkspaceDraft} from './LocalWorkspace';
import {fileWorkspaceKey,observedAdoptedFormalText,type ContinueAdoptedFormal,type FileLayer} from './project-file';
import {useProjectRevision} from './useProjectRevision';
import {packageOperationTarget,type PackagePreviewChoices,type PackagePreviewTarget} from './project-preview';
import {TaskPackageProjectContext} from './PrepareTaskPackageProject';
import {useProjectPackageHandoff} from './useProjectPackageHandoff';
import {TaskOutcome} from './TaskOutcome';
import {OwnerWorkFacts,WorkGoal} from './OwnerWorkFacts';
import {OwnerWorkTeamReportReview,linkedTeamReportChoice,sameTeamReportChoice,teamReportDecisionSaved,teamReportParent,teamReportRead,
 type TeamReportChoice,type TeamReportParent,type TeamReportRead} from './OwnerWorkTeamReportReview';
import {WorkExecutionPlace} from './WorkExecutionPlace';
import {OwnerWorkSupplement,type WorkSupplementTarget,type WorkSupplementLeave} from './OwnerWorkSupplement';
import {ProjectTeamFirstReviews} from './ProjectTeamFirstReviews';
import type {ProjectTeamReviewOrigin} from './project-team-review-origin';
import {sameTeamPackage} from './team-delivered-package';
import {ownerWorkPresentation,type OwnerWorkTeam} from './owner-work-presentation';
import type {TeamWorkTarget} from './ConversationTeamDecisions';
import {matchesWorkTeamResult,teamDecisionEntryKey,teamFileEntryKey,teamProposalEntryKey,workTeamResultEntryKey,type TeamDecisionOrigin,type TeamButlerRequest,type TeamOwnerWorkIntent,type TeamOwnerWorkLink,type TeamRunFocus,type WorkTeamResultReturn,type WorkTeamResultSource} from './team-navigation';
import {DeclinedTeamStart,originalDeclinedTeamStart,originalPendingNodeCommand,TaskApproval} from './TaskApproval';
import {continuationBlocks,continuationFailed,continuationRequest,continuationSettled,continuationUnknown,matchesFixedProjectDraft,observedFixedProjectDraft,privateProjectTask,projectContinuation,sameFixedProjectDraftIdentity,savedUnsentProfileProjectApproval,type ContinuationSubmission,type FixedProjectDraft} from './task-continuation';
import {TaskInference} from './TaskInference';
import {TokenBudgetsWorkspace} from './TokenBudgetsWorkspace';
import {QuickPanelVoiceDraft} from './QuickPanelVoiceDraft';
import {SkillDraftReview,type ConversationDraftTarget,type ReviewedSkillTarget} from './SkillDraftReview';
import {MemoryDraftReview,type SavedMemoryConversationPin} from './MemoryDraftReview';
import {RouteReference,type RouteReferenceTarget} from './RouteReference';
import {ConversationDrafts} from './ConversationDrafts';
import {ConversationWork} from './ConversationWork';
import {ButlerHome,needsOwnerOnHome,type ButlerHomeProps} from './ButlerHome';
import {homeWorkIdentity} from './butler-work-entry';
import {exactSavedHostMessage,localSavedHostMessage,type CloudGoal,type SavedHostSource} from './cloud-goal';
import {SavedHostPicker,savedHostProfiles,currentSavedHostProfile} from './SavedHostWork';
import {OriginalPrivateHostAnswer} from './OriginalPrivateHostAnswer';
import {ButlerReturnedReport,completedDirectoryReport,currentHomeReport,reportedHomeWork,sameHomeReport,verifyHomeReport,type VerifiedHomeReport} from './ButlerReturnedReport';
import type {ReportTarget} from './OwnerWorkReport';
import type {WorkEntry} from './WorkFollowUp';
import type {TaskResultFile} from './TaskResultFiles';
import {PhoneTaskStatus} from './PhoneTaskStatus';
import {PhoneInlineStopReceipt,phoneStopReceiptPresentation,useInlinePhoneStops,type ShownPhoneRun} from './PhoneInlineStopReceipts';
import {PhoneTaskOriginalRequest} from './PhoneTaskOriginalRequest';
import {loadedPhoneWorkReport,type DeliveredPhoneWorkReport,type PhoneWorkReportLocation} from './PhoneTaskConversationReturn';
import {PHONE_RECORD,PhoneOriginalActionReview} from './PhoneOriginalActionReview';
import {EarlierDraftPhoneStarts,BlankPhoneReceiptActions,type BlankFirstRun} from './BlankPhoneStarts';
import {PhoneNotificationReturn,originalPhoneNotice,type PhoneNotice} from './PhoneNotificationReturn';
import {PhoneScopeReview,type PhoneReviewDisplay} from './PhoneScopeReview';
import {currentPhonePlaceInput,phonePlaceReturn,returnToPreparedPhoneDraft,samePhonePlaceDraft,type ReviewedPlace,type PhonePlaceReturn} from './phone-review-place';
import {PhonePauseInstruction,isPrivateLocalPhone,interruptedPrivatePhone,InterruptedPhoneRecord} from './PhonePauseInstruction';
import {PhoneMainOwnerStep,mainPhoneStep} from './PhoneMainOwnerStep';
import {PhoneServiceRecovery} from './PhoneServiceRecovery';
import {privatePhoneSurface,phoneSurfaceLabel,sameDisplayedPause,type DisplayedPhonePause} from './private-phone-surface';
import {ConversationPreparationActions,conversationNext,conversationPreparation,conversationPreparationKey,type ConversationNext,type ConversationPreparation,ButlerPreparationEntries,ButlerPreparationDetail,type ButlerPreparationTarget,type OriginalProjectDraft,type PhonePreparationSetupPin,type ProjectModelPreparationPin,sameProjectModelRevision} from './ButlerPreparations';
import {ownerWorkContinuationNote,ownerWorkContinuationReply,ownerWorkPendingTeamStart,type WorkControlFeedback} from './owner-work-continuation';
import {backgroundConversationWork,conversationActivityLabel,conversationInlineAttention,conversationNavigationActivity,conversationTaskCompact,conversationWorkActionLabel,foregroundConversationTurn,messageReplyContext,messageTaskResult,ownerWorkIdentity,privateTaskConversationId,originatingRequestConversation,workIdentityKey,type ReplyContext,type TaskResult,type WorkIdentity} from './conversation-work';
import './conversation-work.css';
import {taskMessageAnchors} from './task-outcome';
import {OwnerTaskQuestion,mainPhoneAnswerSource,useOwnerQuestionDraft} from './OwnerTaskQuestion';
import {conversationModelName} from './conversation-model-selection';
import {modelSetupInput,modelSetupUse,modelSetupUseMessage,sameModelSetupInput,type ModelSetupInput} from './model-setup-return';
import {matchesChoiceDefault,type AIChoice} from './ai-choice-receipt';
import {ConversationModelPickerProvider,ConversationModelPickerControls,ConversationModelStartButton,ConversationModelPickerVisibility,type ConversationModelPickerHandle} from './ConversationModelPicker';
import {workspaceNavigationKey} from './workspace-directory';
import type {OwnerQuestionView} from './owner-task-question';
import {phoneStartReceipt,phoneStartFailure,phoneRunIdentity,messagePhoneResult,phoneConversationId,type PhoneStartReceipt,type PhoneResult} from './phone-start';
import {InlineOriginalPhoneViewer,PhoneViewer,type PhoneViewerTask} from './phone-viewer';
import './app.css';
import './nav-settings-manga.css';
import './task-surface.css';

const Secondary=lazy(()=>import('./Secondary'));

type Json=Record<string,any>;
type Task={id:string;goal:string;status:string;summary?:string;executionMode?:string;updatedAt?:number;raw?:Json;hostReceipt?:Json};
type OriginalReminderSource={conversationId:string;messageId:string;sourceRevision:number;taskId:string;runId:string;epoch:number;replyId:string};
type OriginalReminderChatVisit={source:OriginalReminderSource;documentEpoch:number;navigation:number;resume:number;returnFocus?:HTMLButtonElement|null};
type Message={id?:string;role:string;content:string;at?:number;taskId?:string;submission?:Json;attachments?:Json[];attachmentFiles?:Json[];memoryReference?:Json;projectConventionRecorded?:boolean;retainedSources?:boolean;sourceRevision?:number;hostGoalTaskId?:string;originalHostFinal?:{text?:string};firstHostRequestLocal?:boolean;quoteAvailable?:boolean;automaticLearningExcluded?:boolean;originalDefaultWritesBlocked?:boolean;phoneResult?:PhoneResult;replyContext?:ReplyContext;taskResult?:TaskResult};
type Project={id:string;name:string};
type Review={reviewHandle:string;task?:Task;ownerQuestion?:Json|null;actions?:{action:string;label:string}[];approvals?:Json[];unresolvedOperations?:Json[];projectContinuation?:Json|null;capabilityApprovalRead?:Json;approvalExecutionScope?:Json;
 projectDraftAcceptance?:{state:'missing_saved_draft';taskId:string;runId:string;epoch:number;resultReviewDigest:string};
 savedTeamDefaultModel?:WorkIdentity&{state:'unconfigured';modelSelectionRevision:number}};
type TaskReviewWindow={taskId:string;request:number;documentEpoch:number;resumeRevision:number};
type TeamReportVisit={parent:TeamReportParent;choice:TeamReportChoice;window:TaskReviewWindow;navigation:number;trigger:HTMLButtonElement};
type TeamReportMode={visit:TeamReportVisit;reading?:TeamReportRead;error?:string;submitted?:boolean;pending?:boolean;done?:boolean};
type WorkFileVisit={owner:TaskReviewWindow;work:WorkIdentity;layer:TaskLayer;navigation:number;taskRequest:number;
 file?:Pick<TaskResultFile,'id'|'sha256'>;afterAck?:number;windowId?:string};
type DraftAcceptanceClick=OriginalProjectDraft&{window:TaskReviewWindow;reviewHandle:string;reviewDigest:string;reviewedRevision:number;completed:boolean};
type PhoneReview=PhoneReviewDisplay&{reviewHandle:string;expiresInMs?:number;documentEpoch?:number;originalDraft?:{composer:string;conversation:string;ownerEpoch:number;revision:number;origin:number};requestId?:string;draftRequestId?:string;provider?:string};
type TaskLayer='overview'|'goal'|'process'|'scope'|'homeReport';
type ChatWorkReturn={document:number;conversation:string;message:string;revision:number;top:number;offset:number;trigger:HTMLButtonElement;hostTaskId?:string;replyRun?:WorkIdentity;result?:{work:TaskResult;navigation:number};phoneDelivery?:{result:PhoneResult;resume:number}};
type ChatWorkRowOrigin={work:WorkIdentity;conversation:string;draftEpoch:number;documentEpoch:number;resumeRevision:number;navigation:number;nativeTaskRequest:number;trigger:HTMLButtonElement};
type ChatWorkTaskOpen={beforeTaskRequest:number;navigation:number}&({returnTo:ChatWorkReturn;row?:never}|{row:ChatWorkRowOrigin;returnTo?:never});
type AcknowledgedWorkVisit=TaskReviewWindow&{chatRow?:ChatWorkRowOrigin;chatReturnRequest?:number};
type ChatDrawerReturn=ChatWorkReturn&{taskId:string;navigation:number;resumeRevision:number};
type DeliveredFileVisit={conversation:string;document:number;navigation:number;resume:number;trigger:HTMLButtonElement;sources:DeliveredFileSource[];more:boolean};
type PreferredChatFile={file:Pick<TaskResultFile,'id'|'sha256'>;returnFocus:HTMLButtonElement;current:()=>boolean;opened:()=>void};
type WorkRequestVisit={work:WorkIdentity;document:number;navigation:number;trigger:HTMLButtonElement;window?:number};
type PhonePreparationReturn={target:ButlerPreparationTarget;pin:PhonePreparationSetupPin;owner:string};
type TerminalSavedFileOrigin={position:CheckoutSavedPosition;navigation:number;current:(section:'local'|null)=>boolean};
type WorkspaceOrigin={route:string;label:string;teamDraft?:TeamDraftVisit;chatWork?:ChatWorkReturn;savedHostPreparation?:true;reviewedSkill?:ReviewedSkillTarget;phonePlace?:PhonePlaceReturn;phonePreparation?:PhonePreparationReturn;modelInput?:ModelSetupInput;firstHomeModel?:{navigation:number;draftRevision:number};projectModelInput?:ModelSetupInput;drawerChat?:{id:string;draftKey:string;documentEpoch:number};settingsDirectory?:SettingsDirectoryOrigin;goal?:string;projectId?:string;contextProjectId?:string;pairedDevicesProject?:true;terminalCheckout?:LocalTerminalCheckout;terminalSavedFile?:TerminalSavedFileOrigin;task?:{id:string;layer:TaskLayer;expected?:{runId:string;epoch:number};fixedArchive?:{projectId:string;checkoutId:string};teamResult?:WorkTeamResultReturn;teamModelReturn?:{documentEpoch:number;conversation:string}};focus?:HTMLElement|null;focusKey?:string;modelPicker?:boolean;parent?:WorkspaceOrigin|null};
type BackgroundPhoneDraftVisit={origin:WorkspaceOrigin;pin:PhonePlaceReturn;navigation:number;document:number;afterRevision:number;
 first:{generation:number;setupRevision:number}|null;seenAuthentication:boolean;retired:boolean};
type FileDraft={text:string;baseline:string;version:number;dirty:boolean;submitted?:string};
type LiveResultAttachment={result:TaskResult;mimeType:string;size:number;kind:string};
type SavedAttachmentMessage={id:string;revision:number;resume:number;taskRequest:number;liveResult?:LiveResultAttachment;
 historyResult?:LiveResultAttachment&{sourceGeneration:number}};
type FileAttachmentPickerOrigin={file:OriginalAttachmentChoice;draft:FileDraftTarget;requestId:string;documentEpoch:number;navigation:number;sourceMessage?:SavedAttachmentMessage};
type FileAttachmentPickerLaunch={requestId:string;cancelled:boolean;settled:boolean;reopening?:boolean};
type OrdinaryAttachmentPickerScope=Omit<FileAttachmentPickerOrigin,'file'|'requestId'|'sourceMessage'>&{resume:number;launch:FileAttachmentPickerLaunch};
type ReopenedAttachmentPicker=OrdinaryAttachmentPickerScope&{requestId:string};
type ProjectTaskReturn={projectId:string;taskId:string;checkoutId?:string;query?:string;entry:ProjectWorkEntry;focusOutcome:boolean;expected?:Pick<WorkIdentity,'runId'|'epoch'>;focusShelf?:()=>boolean;returning?:true;sourcePackageRun?:{runId:string;epoch:number}};
const asArray=<T,>(x:any):T[]=>Array.isArray(x)?x:[];
const obj=(x:any):Json=>x&&typeof x==='object'&&!Array.isArray(x)?x:{};
function ordinaryChatWorkRow(task:Task|undefined,conversation:string,detail=false){
 const work=ownerWorkIdentity(task),raw=task?.raw,source=obj(raw?.ownerWork);
 return work&&raw?.kind==='assistant'&&raw.executionMode==='capabilities'&&raw.conversationId===conversation&&
  source.format==='pocketworld.owner-work.v1'&&source.workTaskId===work.taskId&&source.conversationId===conversation&&
  raw.contextAvailable===true&&raw.contextWithdrawn!==true&&raw.sourceCurrentReadRequired!==true&&raw.taskProjection!=='source_unavailable'&&
  raw.origin==null&&raw.parentTaskId==null&&raw.teamRunId==null&&raw.teamAssignment==null&&raw.memberId==null&&raw.developmentContext==null&&
  raw.ownerPhoneControl==null&&raw.ownerDeviceWork==null&&
  (!detail||raw.taskProjection==='detail_window'&&raw.auditBodyIncluded===true&&raw.taskCoreReadLimited===false)?work:null;
}
function originalTeamDraft(live:ReturnType<typeof bridge.getState>,selectedTeam?:string){
 const owner=live.state,draft=live.composer,phone=obj(live.phoneDraft),conversation=owner.conversationId;
 if(typeof conversation!=='string'||!conversation||draft?.ready!==true||draft.conversationId!==conversation||
    draft.key!==`conversation:${conversation}`||!Number.isSafeInteger(draft.epoch)||!Number.isSafeInteger(draft.revision)||draft.revision<1||
    phone.phone!==false||phone.channelOrigin!==false||owner.conversationReader||owner.filePath||owner.queuedInputsPage||
    owner.attachmentWorkspace?.open||owner.chatSubmitting||owner.phoneSubmitting||foregroundConversationTurn(owner)||
    !live.capabilities.commands.includes('composer.object.add'))return null;
 const input={conversation,key:draft.key as string,epoch:draft.epoch as number,revision:draft.revision as number};
 const refs=asArray<Json>(draft.objectRefs),workRefs=refs.filter(row=>row?.kind==='work');
 if(!workRefs.length){
  const teams=refs.filter(row=>row?.kind==='team');
  // No Work exists for a first goal. Pin its (already native-confirmed) text
  // and draft instead; only the Team we just added may appear in readback.
  if(!Array.isArray(draft.objectRefs)||refs.length-teams.length>=4||teams.length>1||teams.some(ref=>ref.id!==selectedTeam)||
     typeof draft.text!=='string'||!draft.text.trim()||draft.voiceText||owner.listening||owner.voiceInput?.active||
     owner.voiceInput?.reviewAvailable||owner.chatAttachmentPickerOpen||!Array.isArray(draft.pending)||
     draft.pending.some(row=>row?.draftKey===draft.key))return null;
  return {...input,kind:'goal' as const,text:draft.text};
 }
 if(workRefs.length!==1)return null;
 const ref=workRefs[0],rows=asArray<Task>(owner.tasks).filter(task=>task.id===ref.id),task=rows[0];
 const work=ordinaryChatWorkRow(rows.length===1?task:undefined,conversation),generation=task?.raw?.ownerWork?.generation;
 return work?.runId&&work.epoch>0&&work.runId===ref.runId&&work.epoch===ref.epoch&&typeof generation==='string'&&generation
  ?{...input,kind:'work' as const,work,generation}:null;
}
type TeamGoalStarter=NonNullable<ReturnType<typeof emptyTeamGoalDraft>>&{documentEpoch:number;navigation:number};
function emptyTeamGoalDraft(live:ReturnType<typeof bridge.getState>){
 const {composer:draft,state}=live,view=obj(live.view),phone=obj(live.phoneDraft),conversation=state.conversationId;
 if(draft?.ready!==true||typeof draft.key!=='string'||draft.text!==''||draft.voiceText!==''||
    !Number.isSafeInteger(draft.epoch)||!Number.isSafeInteger(draft.revision)||draft.revision<1||!Number.isSafeInteger(view.resumeRevision)||
    !(conversation===null?draft.conversationId===''&&draft.key.startsWith('new:'):
      typeof conversation==='string'&&!!conversation&&draft.conversationId===conversation&&draft.key===`conversation:${conversation}`)||
    view.taskId!==null||phone.phone!==false||phone.channelOrigin!==false||state.conversationReader||state.queuedInputsPage||
    state.filePath||state.attachmentWorkspace?.open||state.chatAttachmentPickerOpen||state.chatSubmitting||state.phoneSubmitting||
    state.listening||state.voiceInput?.active||state.voiceInput?.reviewAvailable||foregroundConversationTurn(state)||
    ![draft.objectRefs,draft.sourceRefs,draft.previewRefs,state.selectedChatAttachments].every(value=>Array.isArray(value)&&!value.length)||
    !Array.isArray(draft.pending)||draft.pending.some(row=>row?.draftKey===draft.key)||
    !live.capabilities.commands.includes('composer.edit'))return null;
 return {key:draft.key,epoch:Number(draft.epoch),revision:Number(draft.revision),resume:Number(view.resumeRevision)};
}
function sameEmptyTeamGoal(left:NonNullable<ReturnType<typeof emptyTeamGoalDraft>>,right:ReturnType<typeof emptyTeamGoalDraft>){
 return right?.key===left.key&&right.epoch===left.epoch&&right.revision===left.revision&&right.resume===left.resume;
}
type TeamDraftInput=NonNullable<ReturnType<typeof originalTeamDraft>>;
type TeamDraftVisit=TeamDraftInput&{documentEpoch:number;navigation:number;resume:number};
function sameTeamDraftInput(visit:TeamDraftInput,current:TeamDraftInput|null,revision=true){
 if(!current||current.conversation!==visit.conversation||current.key!==visit.key||current.epoch!==visit.epoch||
    revision&&current.revision!==visit.revision)return false;
 return visit.kind==='goal'?current.kind==='goal'&&current.text===visit.text:
   current.kind==='work'&&current.generation===visit.generation&&workIdentityKey(current.work)===workIdentityKey(visit.work);
}
const originalTeamDraftChanged='原工作或草稿已变化，没有把编组加到其他输入。';
function savedOwnerAttachmentChoices(message:Message):OriginalAttachmentChoice[]{
 if(message.role!=='user'||!message.id||!Number.isSafeInteger(message.sourceRevision)||Number(message.sourceRevision)<1)return [];
 const refs=asArray<Json>(message.attachments),checked=asArray<Json>(message.attachmentFiles);
 if(refs.length>4||checked.length>4)return [];
 return refs.flatMap(ref=>{
  const pin=exactFileTarget(obj(ref));
  if(!pin||refs.filter(row=>row?.id===pin.fileId).length!==1)return [];
  const matches=checked.filter(row=>row?.id===pin.fileId),file=matches.length===1?matches[0]:null;
  // The original message projection includes a separate native private metadata check. It is not a read grant.
  return file?.availability==='available'&&file.role==='source'&&file.contentReadConfirmed===false&&file.sha256===pin.sha256&&
   file.mimeType===ref.mimeType&&file.size===ref.size&&typeof file.name==='string'&&file.name
   ?[{...pin,name:file.name}]:[];
 });
}
function openedRetainedDraftCopyKey({state,view}:ReturnType<typeof bridge.getState>){
 const pin=obj(state.openedRetainedDraftCopy),native=obj(view);
 const id=(value:unknown)=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);
 return !document.hidden&&native.section===null&&native.taskId===null&&!state.filePath&&
  id(pin.sourceTaskId)&&id(pin.checkoutId)&&id(pin.projectId)&&pin.projectId===state.projectId&&pin.projectId===state.requestedProjectId&&
  Number.isSafeInteger(pin.requestRevision)&&pin.requestRevision>0&&pin.requestRevision===state.requestedProjectRevision
  ?JSON.stringify([pin.sourceTaskId,pin.projectId,pin.checkoutId,pin.requestRevision]):'';
}
const sameProjectModelChat=(input:ModelSetupInput,source:ChatWorkReturn|undefined,live:ReturnType<typeof bridge.getState>)=>{
 if(document.hidden||!source||source.conversation!==input.conversation||source.document!==input.document||
    !sameModelSetupInput(input,live,bridge.getDocumentEpoch()))return false;
 const rows=asArray<Message>(live.state.messages).filter(row=>row.id===source.message);
 return rows.length===1&&rows[0].role==='user'&&rows[0].quoteAvailable===true&&rows[0].sourceRevision===source.revision;
};
const sameWorkspaceTask=(original:NonNullable<WorkspaceOrigin['task']>,tasks:unknown)=>{
 const {id,expected,fixedArchive}=original;
 if(!expected)return !fixedArchive;
 return asArray<Task>(tasks).some(row=>{
  const raw=obj(row.raw),scope=obj(raw.developmentContext);
  return row.id===id&&raw.runId===expected.runId&&raw.coordinatorEpoch===expected.epoch&&(!fixedArchive||
   privateProjectTask(row)&&raw.id===id&&raw.contextAvailable!==false&&raw.contextWithdrawn!==true&&
   raw.sourceCurrentReadRequired!==true&&raw.taskProjection!=='source_unavailable'&&scope.projectId===fixedArchive.projectId&&scope.checkoutId===fixedArchive.checkoutId);
 });
};
const completedWorkTeam=(source:WorkTeamResultSource,tasks:unknown)=>{
 const parents=asArray<Task>(tasks).filter(task=>task.id===source.parentTaskId),parent=parents[0],raw=parent?.raw;
 if(parents.length!==1||raw?.contextAvailable!==true||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||
    raw.cancelRequested===true||raw.runId!==source.parentRunId||raw.coordinatorEpoch!==source.parentEpoch||
    raw.conversationId!==source.conversationId)return false;
 const matches=ownerWorkPresentation(parent)?.teams.filter(row=>row.teamRunId===source.teamRunId&&row.taskId===source.taskId);
 const shown=matches?.length===1?matches[0]:undefined;
 return !!shown&&shown.status==='completed'&&!shown.active&&!shown.needsAttention&&!shown.cancelRequested&&!shown.deliveredPackage;
};
const originalTeamWorkCurrent=(origin:WorkspaceOrigin,source:WorkTeamResultSource,live:ReturnType<typeof bridge.getState>)=>{
 const owner=live.state;
 return !owner.filePath&&!owner.fileLoading&&!owner.fileSaving&&!owner.conversationReader&&
  (origin.route!=='chat'||owner.conversationId===source.conversationId)&&
  (origin.route!=='projects'||!!origin.projectId&&owner.projectId===origin.projectId)&&completedWorkTeam(source,owner.tasks);
};
const originalTeamWorkUnavailable='原工作暂不可核对，没有打开其他轮次。';
const resultMessageIn=(rows:unknown,id:string,expected:TaskResult,revision?:number)=>{
 const matches=asArray<Json>(rows).filter(row=>row.id===id);
 const message=matches.length===1?matches[0]:null,result=messageTaskResult(message);
 return message&&Number.isSafeInteger(message.sourceRevision)&&message.sourceRevision>0&&
  (revision===undefined||message.sourceRevision===revision)&&result&&workIdentityKey(result)===workIdentityKey(expected)&&
  result.deliveryId===expected.deliveryId&&result.status===expected.status&&!result.noticeKind?message:null;
};
const continueSavedHostLabel='继续准备（未发送）';
const originalArchiveChatUnavailable='暂不能核对原话与草稿，没有打开其他对话。';
function phonePreparationOwner(){
 const now=bridge.getState(),c=now.composer,draft=obj(now.phoneDraft),conversation=String(now.state.conversationId||'');
 if(c?.ready!==true||typeof c.key!=='string'||!c.key||!Number.isSafeInteger(c.epoch)||c.conversationId!==conversation||
    draft.channelOrigin!==false||!Number.isSafeInteger(draft.epoch)||now.state.chatSubmitting||now.state.phoneSubmitting)return '';
 // A native foreground ticket is deliberately not saved across Android Settings.
 return JSON.stringify([conversation,c.key,c.epoch,draft.epoch]);
}
const labelStatus=(s:string)=>({queued:'等待执行',preparing:'准备中',running:'执行中',pausing:'正在暂停',paused:'已暂停',stopping:'正在停止',verifying:'正在核对',needs_confirmation:'请确认结果',awaiting_user:'待你处理',waiting_user:'待你处理',waiting_approval:'等待授权',awaiting_approval:'等待授权',interrupted:'已中断',completed:'已完成',failed:'未完成',cancelled:'已停止',canceled:'已停止',stopped:'已停止',waiting_permission:'等待授权',reconciling:'等待核对动作',reconciliation_required:'等待核对动作',unknown:'等待核对动作'} as Record<string,string>)[s]||s||'待处理';
const running=(t:Task)=>['queued','preparing','running','pausing','stopping','verifying'].includes(t.status);
const attention=(t:Task)=>workCollectionPresentation(t).group==='attention';
const terminal=(t:Task)=>['completed','failed','cancelled','canceled','stopped'].includes(t.status);
const workStatusLabel=(t:Task)=>workCollectionPresentation(t).label;
function originalNodeBuildCopy(task?:Task): ProjectBuildCopy|null {
 const raw=task?.raw, scope=obj(raw?.developmentContext), pin=obj(scope.ownerDraftFirstBuild), hash=/^[a-f0-9]{64}$/;
 const key=(value:unknown):value is string=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);
 if(!task||!raw)return null;
 // A real build exit is separate from the owner's review of the overall result.
 const observedBuildFailure=task.status==='needs_confirmation'&&raw.status===task.status&&
    raw.taskProjection==='detail_window'&&raw.taskCoreReadLimited===false&&raw.hasUnresolvedOperations===false&&
    raw.cancelRequested===false&&pin.existingCheckoutBuild===true&&
    originalFirstBuildFailure(task)?.failureStage==='build'&&
    !asArray<Json>(raw.approvals).some(approval=>approval?.runId===raw.runId&&['pending','approved'].includes(approval.state));
 if((!observedBuildFailure&&!['completed','failed'].includes(task.status))||!privateProjectTask(task)||raw.id!==task.id||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.taskProjection==='source_unavailable'||
    taskNeedsReconciliation(task)||!key(task.id)||typeof raw.runId!=='string'||!raw.runId||!Number.isSafeInteger(raw.coordinatorEpoch)||raw.coordinatorEpoch<1||
    scope.format!=='pocketworld.development-context.v1'||scope.runtime!=='managed_process_v1'||scope.draftOnly===true||
    !key(pin.projectId)||!key(pin.checkoutId)||pin.projectId!==scope.projectId||pin.checkoutId!==scope.checkoutId||
    typeof pin.sourceDigest!=='string'||!hash.test(pin.sourceDigest)||pin.sourceDigest!==scope.initialSourceDigest||
    (pin.existingCheckoutBuild!==true&&(typeof pin.workIdentity!=='string'||!hash.test(pin.workIdentity))))return null;
 return {taskId:task.id,runId:raw.runId,epoch:raw.coordinatorEpoch,projectId:pin.projectId,checkoutId:pin.checkoutId,sourceDigest:pin.sourceDigest};
}
function originalViteEditorCopy(task?:Task):ProjectBuildCopy|null{
 const raw=task?.raw;
 if(!raw?.ownerDirectViteEditorResume||raw.hasUnresolvedOperations!==false||taskNeedsReconciliation(task))return null;
 const original=originalDirectViteEditorReturn(task);
 if(!original)return null;
 const {buildOperationId,...copy}=original;
 const editorResume=projectBuildEditorResume(raw.ownerDirectViteEditorResume,copy);
 return editorResume?.buildOperationId===buildOperationId?{...copy,editorResume}:null;
}
function phoneViewerTask(task?:Task):PhoneViewerTask|null{
 const raw=task?.raw,identity=phoneRunIdentity(task);
 if(!task||!raw||!identity||raw.id!==task.id||raw.kind!=='phone'||raw.actorId!=='owner'||raw.spaceId!=='private'||task.executionMode!=='background'||raw.origin&&raw.origin.kind!=='team_phone')return null;
 const evidence=obj(raw.evidence),session=[obj(evidence.phoneSession),obj(evidence.phonePreparation)].find(value=>value.taskId===identity.taskId&&value.runId===identity.runId&&value.coordinatorEpoch===identity.epoch);
 const blocked=!!session&&['awaiting_user','reconciling','failed'].includes(task.status)&&(session.state==='preparation_unconfirmed'||session.state==='not_initialized'&&evidence.code==='phone_preparation_unavailable');
 const detail=typeof task.summary==='string'&&task.summary.trim()?task.summary:typeof raw.summary==='string'&&raw.summary.trim()?raw.summary:blocked&&typeof evidence.message==='string'?evidence.message:'';
 return {...identity,title:task.goal,statusLabel:labelStatus(task.status),detail,preparation:blocked?'blocked':['queued','preparing'].includes(task.status)?'pending':undefined,status:terminal(task)||task.status==='interrupted'?(task.status==='failed'?'error':'ended'):task.status==='stopping'?'stopping':task.status==='paused'?'paused':['queued','preparing','running','verifying'].includes(task.status)?'running':'waiting'};
}
const taskBinding=(t?:Task|null)=>t?[t.id,t.updatedAt,t.raw?.runId,t.raw?.coordinatorEpoch,t.raw?.revision,t.raw?.resultReviewDigest,t.raw?.executionScope?.revision,t.raw?.executionScope?.executionPhase,t.raw?.executionScope?.planRevision,t.raw?.executionScope?.planArtifactRef,t.raw?.executionScope?.planHash].join(':'):'';
function noSavedDraftInReview(task:Task,review:Review|null){
 const fact=review?.projectDraftAcceptance,raw=task.raw;
 return fact?.state==='missing_saved_draft'&&task.status==='needs_confirmation'&&raw?.developmentContext?.draftOnly===true&&
  !Object.hasOwn(raw,'projectEditDraftId')&&fact.taskId===task.id&&typeof fact.runId==='string'&&!!fact.runId&&fact.runId===raw.runId&&
  Number.isSafeInteger(fact.epoch)&&fact.epoch>0&&fact.epoch===raw.coordinatorEpoch&&
  typeof fact.resultReviewDigest==='string'&&/^[a-f0-9]{64}$/.test(fact.resultReviewDigest)&&fact.resultReviewDigest===raw.resultReviewDigest;
}
function fixedDraftReviewTarget(task?:Task):OriginalProjectDraft|null{
 const raw=task?.raw,scope=raw?.developmentContext;
 if(!task||!raw||!privateProjectTask(task)||raw.id!==task.id||raw.status!==task.status||raw.executionMode!=='capabilities'||
    scope?.draftOnly!==true||scope.runtime!=='managed_process_v1'||raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.teamRunId!=null||raw.parentTaskId!=null||raw.memberId!=null||
    typeof raw.runId!=='string'||!raw.runId.trim()||!Number.isSafeInteger(raw.coordinatorEpoch)||raw.coordinatorEpoch<1||
    typeof raw.projectEditDraftId!=='string'||!/^[a-f0-9]{64}$/.test(raw.projectEditDraftId))return null;
 return {taskId:task.id,runId:raw.runId,epoch:raw.coordinatorEpoch,draftId:raw.projectEditDraftId};
}
function exactReviewedOriginalDraft(task?:Task,reviewed?:Task):OriginalProjectDraft|null{
 const current=fixedDraftReviewTarget(task),original=fixedDraftReviewTarget(reviewed);
 return current&&original&&current.taskId===original.taskId&&current.runId===original.runId&&
  current.epoch===original.epoch&&current.draftId===original.draftId?current:null;
}
function firstOriginalDraft(task:Task,draft:OriginalProjectDraft|null){
 const source=task.raw?.ownerWorkPreparation;
 return draft?.epoch===1&&source?.format==='pocketworld.owner-work-preparation.v1'&&source.targetTaskId===draft.taskId;
}
function draftAcceptanceClick(review:Review,window:TaskReviewWindow,completed:boolean):DraftAcceptanceClick|null{
 const original=fixedDraftReviewTarget(review.task),raw=review.task?.raw;
 const digest=raw?.resultReviewDigest,revision=raw?.resultRevision;
 return original?.taskId===window.taskId&&review.task?.status==='needs_confirmation'&&
  typeof digest==='string'&&/^[a-f0-9]{64}$/.test(digest)&&Number.isSafeInteger(revision)&&revision>0
  ? {...original,window,reviewHandle:review.reviewHandle,reviewDigest:digest,reviewedRevision:revision,completed}:null;
}
function savedDraftAcceptanceMatches(task:Task,click:DraftAcceptanceClick){
 const current=fixedDraftReviewTarget(task),receipt=task.raw?.resultConfirmation,revision=task.raw?.resultRevision;
 return task.status===(click.completed?'completed':'awaiting_user')&&current?.taskId===click.taskId&&current.runId===click.runId&&current.epoch===click.epoch&&current.draftId===click.draftId&&
  receipt?.completed===click.completed&&receipt.resultReviewDigest===click.reviewDigest&&Number.isSafeInteger(revision)&&revision>click.reviewedRevision&&receipt.confirmedResultRevision===revision;
}
const timeLabel=(value:any)=>typeof value==='number'&&value>0?new Date(value).toLocaleString('zh-CN',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}):'';
const sourceRefs=(m:Message)=>asArray<Json>(m.memoryReference?.sources||m.memoryReference?.sourceRefs||m.submission?.sourceRefs).map((s,i)=>({...s,id:String(s.id||s.sourceId||s.sourceRef||i),title:s.title||s.name||s.label||'来源'}));
function treeData(files:Json[]){
 const roots:any[]=[];const nodes=new Map<string,any>();
 files.forEach(file=>{let parent=roots;let prefix='';String(file.path).split('/').filter(Boolean).forEach((part,index,parts)=>{prefix+=(prefix?'/':'')+part;let node=nodes.get(prefix);if(!node){node={id:prefix,name:part,icon:index===parts.length-1?IconFile:IconFolder,children:index===parts.length-1?undefined:[]};nodes.set(prefix,node);parent.push(node)}if(node.children)parent=node.children;});});return roots;
}
function useNativeDraft(owner:string,text:string,revision:any){
 const [value,setValue]=useState(text);const current=useRef({owner,dirty:false,value:text});const sent=useRef<string|null>(null);
 useEffect(()=>{const c=current.current;if(c.owner!==owner){current.current={owner,dirty:false,value:text};sent.current=null;setValue(text);return;}if(text===c.value){c.dirty=false;return;}if(!c.dirty||(sent.current!==null&&c.value===sent.current&&text==='')){c.value=text;c.dirty=false;sent.current=null;setValue(text);}},[owner,text,revision]);
 return {value:current.current.owner===owner?value:text,set:(next:string)=>{sent.current=null;current.current={owner,dirty:true,value:next};setValue(next)},markSent:()=>{sent.current=current.current.value},currentValue:()=>current.current.owner===owner?current.current.value:'',owner};
}
/** Existing exceptional receipts retain their separate feedback. */
function Receipt({receipt}:{receipt:Json}){
 const status=String(receipt.status||receipt.state||'');
 if(!['failed','rejected','unconfirmed','unknown','pending','withdrawn'].includes(status))return null;
 const labels:Record<string,string>={failed:'发送未确认',rejected:'未发送',unconfirmed:'结果待确认',unknown:'结果待确认',pending:'正在确认',withdrawn:'已撤回'};
 const detail=receipt.message||receipt.reason;
 const localPending=status==='unconfirmed'&&receipt.nativeLocalRead===true;
 return <MotionFeedback identity={`${status}:${detail||''}`} className="pw-input-receipt" role="status"><span>{localPending?'原问题已保存，暂无可核对的主屏读数':labels[status]}</span>{detail&&<small>{detail}</small>}</MotionFeedback>;
}

function ChatPhonePickerGate({covered,retire,children}:{covered:boolean;retire:()=>void;children:ReactNode}){
 useLayoutEffect(()=>{if(covered)retire()},[covered,retire]);
 return covered?null:children;
}

export default function App(){
 const navigationMenuGlass=useNavigationGlass<HTMLButtonElement>();
 const navigationBackGlass=useNavigationGlass<HTMLButtonElement>();
 const chatBackGlass=useNavigationGlass<HTMLButtonElement>();
 const headerActionsGlass=useNavigationGlass<HTMLDivElement>();
 const [skillDraftTarget,setSkillDraftTarget]=useState<(ConversationDraftTarget&{chatWork?:ChatWorkReturn})|null>(null);
 const [memoryDraftTarget,setMemoryDraftTarget]=useState<ConversationDraftTarget|null>(null);
 const [personalTarget,setPersonalTarget]=useState<PersonalTarget>({view:'home'}),[personalTargetKey,setPersonalTargetKey]=useState(0);
 const [conversationMemoryOpen,setConversationMemoryOpen]=useState(false),[conversationMemoryControls,setConversationMemoryControls]=useState<ConversationMemoryControls|null>(null);
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
 useEffect(()=>setConversationMemoryOpen(false),[route,state.conversationId]);
 const conversationMemoryReadable=isChatSurface&&!!state.conversationId&&can('profile.conversation.read');
 useEffect(()=>setConversationMemoryControls(null),[conversationMemoryReadable,state.conversationId]);
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
 const backgroundPhoneDraftVisit=useRef<BackgroundPhoneDraftVisit|null>(null);
 const openingModelSetup=useRef(false);
 const [modelSetupOpening,setModelSetupOpening]=useState(false);
 const [modelSetupFeedback,setModelSetupFeedback]=useState<{input:ModelSetupInput;choice:AIChoice;matchesDefault?:boolean;modelRevision:number;draftRevision:number;preferenceRevision:unknown}|null>(null);
 const relatedOrigin=useRef<WorkspaceOrigin|null>(null);
 const terminalSavedFileArrival=useRef<ProjectTerminalSavedFileReturn|null>(null);
 const [localWorkspaceDraft,setLocalWorkspaceDraft]=useState<LocalWorkspaceDraft|undefined>();
 const [projectSections,setProjectSections]=useState<Record<string,'work'|'files'>>({});
 const [projectWorkDrafts,setProjectWorkDrafts]=useState<Record<string,ProjectWorkDraft>>({});
 const [projectDraftReturn,setProjectDraftReturn]=useState<ProjectDraftReturn|null>(null);
 const [projectBuildReturn,setProjectBuildReturn]=useState<ProjectBuildCopy|null>(null),openingBuildCopy=useRef(false);
 const handledBuildReturn=useCallback((target:ProjectBuildCopy)=>setProjectBuildReturn(value=>value===target?null:value),[]);
 useEffect(()=>{if(route!=='projects'||state.filePath||state.projectId!==projectBuildReturn?.projectId)setProjectBuildReturn(null);},[route,state.filePath,state.projectId,projectBuildReturn?.projectId]);
 useEffect(()=>{if(route!=='projects'||state.projectId!==projectDraftReturn?.projectId)setProjectDraftReturn(null);},[route,state.projectId,projectDraftReturn?.projectId]);
 const [projectWorkPresentations,setProjectWorkPresentations]=useState<Record<string,ProjectWorkPresentation>>({});
 const [projectImportedEditor,setProjectImportedEditor]=useState<ProjectImportedEditor|null>(null);
 const handledImportedEditor=useCallback((target:ProjectImportedEditor)=>setProjectImportedEditor(value=>value===target?null:value),[]);
 const [projectLocalFilesIntent,setProjectLocalFilesIntent]=useState<ProjectLocalFilesIntent|null>(null);
 const handledLocalFilesIntent=useCallback((target:ProjectLocalFilesIntent)=>setProjectLocalFilesIntent(value=>value===target?null:value),[]);
 const [projectWorkReviewOpen,setProjectWorkReviewOpen]=useState(false);
 const projectWorkBack=useRef<(()=>boolean)|null>(null);
 const registerProjectWorkBack=useCallback((handler:(()=>boolean)|null)=>{projectWorkBack.current=handler},[]);
 const [preparationTarget,setPreparationTarget]=useState<ButlerPreparationTarget|null>(null),[preparationRevision,setPreparationRevision]=useState(0);
 const [homeHasPreparations,setHomeHasPreparations]=useState(false);
 const preparationBack=useRef<(()=>boolean)|null>(null);
 const registerPreparationBack=useCallback((handler:(()=>boolean)|null)=>{preparationBack.current=handler},[]);
 const preparationBeforeLeave=useRef<(()=>Promise<boolean>)|null>(null);
 const chatWorkReturn=useRef<ChatDrawerReturn|null>(null);
 const [chatWorkReading,setChatWorkReading]=useState(false),chatOpenFlight=useRef(false);
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
 const chatHistoryReturn=useRef<{conversationId:string;document:number;navigation:number;trigger:HTMLButtonElement}|null>(null);
 const registerHistoryWindowBack=useCallback((handler:(()=>boolean)|null)=>{historyWindowBack.current=handler},[]);
 const queuedInputsBack=useRef<(()=>boolean)|null>(null);
 const registerQueuedInputsBack=useCallback((handler:(()=>boolean)|null)=>{queuedInputsBack.current=handler},[]);

 const navigationWindow=useRef(0);const [navigationRevision,setNavigationRevision]=useState(0),[secondaryPage,setSecondaryPage]=useState('');
 const [sentPhoneFocus,setSentPhoneFocus]=useState<(SentPhoneGoalFocus&{navigation:number})|null>(null);
 const [teamGoalStarter,setTeamGoalStarter]=useState<TeamGoalStarter|null>(null);
 useEffect(()=>{
  const hide=()=>{if(document.hidden)setTeamGoalStarter(null)};
  document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide);
 },[]);
 const [cloudGoal,setCloudGoal]=useState<(CloudGoal&{navigation:number})|null>(null);
 const [savedHostChoice,setSavedHostChoice]=useState<ChatWorkReturn|null>(null);
 const savedHostSession=useRef<ChatWorkReturn|null>(null),savedHostFlight=useRef<ChatWorkReturn|null>(null);
 const homeSavedHostJump=useRef<(SavedHostSource&{navigation:number;viewOnly?:true})|null>(null);
 const [savedHostBusy,setSavedHostBusy]=useState(false),[savedHostError,setSavedHostError]=useState('');
 const closeSavedHost=useCallback(()=>{savedHostSession.current=null;savedHostFlight.current=null;setSavedHostChoice(null);setSavedHostBusy(false);setSavedHostError('');},[]);
 useEffect(()=>{
  if(savedHostChoice&&(route!=='chat'||savedHostChoice.conversation!==state.conversationId||state.conversationReader||
     !exactSavedHostMessage(messages,savedHostChoice))){closeSavedHost();return;}
  if(!savedHostChoice)return;
  const background=()=>{if(document.hidden)closeSavedHost();};
  document.addEventListener('visibilitychange',background);
  window.addEventListener('pagehide',closeSavedHost);
  return()=>{document.removeEventListener('visibilitychange',background);window.removeEventListener('pagehide',closeSavedHost);};
 },[route,state.conversationId,state.conversationReader,messages,savedHostChoice,closeSavedHost]);
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
 const [deliveredFileVisit,setDeliveredFileVisit]=useState<DeliveredFileVisit|null>(null),deliveredFileRef=useRef<DeliveredFileVisit|null>(null);
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
 const [inlinePhoneVisit,setInlinePhoneVisit]=useState<string|null>(null);
 const inlinePhoneSelection=useRef<string|null>(null);
 const chooseInlinePhoneVisit=useCallback((key:string|null)=>{
  inlinePhoneSelection.current=key;setInlinePhoneVisit(key);
 },[]);
 useEffect(()=>{
  const retire=()=>{chooseInlinePhoneVisit(null)};
  const hidden=()=>{if(document.hidden)retire()};
  const off=bridge.subscribeDocumentEpoch(retire);
  document.addEventListener('visibilitychange',hidden);
  return()=>{off();document.removeEventListener('visibilitychange',hidden)};
 },[chooseInlinePhoneVisit]);
 const phoneNotification=originalPhoneNotice(state.originalPhoneNotification);
 const [phoneNoticePin,setPhoneNoticePin]=useState<PhoneNotice|null>(null),[phoneNoticePanel,setPhoneNoticePanel]=useState<string|null>(null);
 const phoneNoticeRef=useRef<PhoneNotice|null>(null),phoneNoticeSeen=useRef('');
 const phoneActivityPick=useRef<{kind:'activity'|'blank_draft';handle:string;navigation:number;document:number;resume:number;entry:'review'|'overview'}|null>(null);
 useEffect(()=>{setPhoneViewerExpanded(false)},[state.conversationId,route,taskId,taskOpen]);
 const projectScroll=useRef<HTMLElement>(null),projectListTop=useRef(new Map<string,number>());
 const projectTaskReturn=useRef<ProjectTaskReturn|null>(null);
 const projectGitToolReturn=useRef<PublicGitDraft|null>(null);
 const homeReport=useRef<VerifiedHomeReport|null>(null);
 const homeWorkReturn=useRef<{taskId:string;report?:VerifiedHomeReport;focus:()=>boolean;returning?:true}|null>(null);
 const workRequestVisit=useRef<WorkRequestVisit|null>(null),[openingWorkRequest,setOpeningWorkRequest]=useState('');
 const [workRequestError,setWorkRequestError]=useState<{work:WorkIdentity;message:string}|null>(null);
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
 useEffect(()=>{
  const returning=terminalSavedFileArrival.current;
  if(returning&&(route!=='projects'||projectSection!=='work'||state.projectId!==returning.position.projectId||state.filePath||taskOpen))
   terminalSavedFileArrival.current=null;
 },[route,projectSection,state.projectId,state.filePath,taskOpen]);
 useEffect(()=>{
  if(!projectLocalFilesIntent)return;
  const retire=()=>handledLocalFilesIntent(projectLocalFilesIntent);
  if(route!=='projects'||projectSection!=='work'||taskOpen||!projectLocalFilesIntent.current()){retire();return;}
  const hidden=()=>{if(document.hidden)retire()};
  document.addEventListener('visibilitychange',hidden);window.addEventListener('pagehide',retire);
  return()=>{document.removeEventListener('visibilitychange',hidden);window.removeEventListener('pagehide',retire)};
 },[route,projectSection,taskOpen,state.projectId,state.filePath,personalDocumentEpoch,snapshot?.view?.resumeRevision,can,projectLocalFilesIntent,handledLocalFilesIntent]);
 useEffect(()=>{
  if(!projectImportedEditor)return;
  const mode=projectWorkPresentations[projectImportedEditor.projectId];
  const retire=()=>handledImportedEditor(projectImportedEditor);
  const hidden=()=>{if(document.hidden)retire();};
  if(route!=='projects'||projectSection!=='work'||state.filePath||state.projectId!==projectImportedEditor.projectId||!mode?.open||mode.mode!=='default'||document.hidden){retire();return;}
  document.addEventListener('visibilitychange',hidden);window.addEventListener('pagehide',retire);
  return()=>{document.removeEventListener('visibilitychange',hidden);window.removeEventListener('pagehide',retire);};
 },[route,projectSection,state.filePath,state.projectId,projectWorkPresentations,projectImportedEditor,handledImportedEditor]);
 const projectPositionKey=`${String(state.projectId||'')}:${projectSection}`;
 const updateProjectView=(patch:Partial<typeof projectView>)=>setProjectViews(previous=>({...previous,[String(state.projectId||'')]:{...(previous[String(state.projectId||'')]||{query:'',view:'files',expanded:[],selected:''}),...patch}}));
 const fileQuery=projectView.query,fileView=projectView.view;const setFileQuery=(query:string)=>updateProjectView({query}),setFileView=(view:'files'|'tree'|'grid')=>updateProjectView({view});
 const taskScroll=useRef<HTMLDivElement>(null),taskReading=useRef(new Map<string,number>());
 const reviewRequest=useRef(0),openedTaskId=useRef<string|null>(null),secondaryBack=useRef<(()=>boolean)|null>(null);
 const openedTaskResumeRevision=useRef(0);
 const acknowledgedWorkVisit=useRef<AcknowledgedWorkVisit|null>(null);
 const workFileVisit=useRef<WorkFileVisit|null>(null);
 const taskHistoryOrigin=useRef<{taskId:string;origin:ConversationHistoryTaskOrigin}|null>(null);
 const taskReconciliationOrigin=useRef<{taskId:string;layer:TaskLayer;openedId:string;expected:{runId:string;epoch:number};history?:ConversationHistoryTaskOrigin}|null>(null);
 const [taskOpening,setTaskOpening]=useState(false);
 const [taskToolsPlace,setTaskToolsPlace]=useState<{taskId:string;overview:boolean}|null>(null);
 const [focusedOriginalDraft,setFocusedOriginalDraft]=useState<OriginalProjectDraft|null>(null);
 const [sourceSelection,setSourceSelection]=useState<AnswerSourceTarget|null>(null);
 const [projectConventionMessage,setProjectConventionMessage]=useState<string|null>(null);
 const [providedMemoryMessage,setProvidedMemoryMessage]=useState<string|null>(null);
 const providedMemoryBack=useRef<(()=>boolean)|null>(null);
 const registerProvidedMemoryBack=useCallback((handler:(()=>boolean)|null)=>{providedMemoryBack.current=handler},[]);
 useEffect(()=>{setProvidedMemoryMessage(null)},[route,state.conversationId]);
 const [messageQuoteTarget,setMessageQuoteTarget]=useState<MessageQuoteTarget|null>(null);
 const [messageOriginTarget,setMessageOriginTarget]=useState<MessageOriginTarget|PhoneReportTarget|null>(null);
 const [workSupplementTarget,setWorkSupplementTarget]=useState<WorkSupplementTarget|null>(null);
 const workSupplementBeforeLeave=useRef<(WorkSupplementLeave&{target:WorkSupplementTarget;panel:HTMLElement})|null>(null);
 const registerWorkSupplementBeforeLeave=useCallback((target:WorkSupplementTarget,panel:HTMLElement,guard:WorkSupplementLeave|null)=>{
  if(guard)workSupplementBeforeLeave.current={target,panel,...guard};
  else if(workSupplementBeforeLeave.current?.target===target&&workSupplementBeforeLeave.current.panel===panel)workSupplementBeforeLeave.current=null;
 },[]);
 const dismissWorkSupplement=useCallback((target:WorkSupplementTarget)=>{
  setWorkSupplementTarget(previous=>previous===target?null:previous);
 },[]);
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
 const openConversationDraft=(draft:ConversationDraftTarget&{kind:string},message?:Message)=>{
  if(draft.kind!=='skill'){setSkillDraftTarget(null);setMemoryDraftTarget(draft);return;}
  const live=bridge.getState(),node=scroll.current,conversation=String(live.state.conversationId||'');
  const article=Array.from(chatMessageList.current?.children||[]).find(row=>(row as HTMLElement).dataset.messageId===message?.id);
  const trigger=article?.querySelector<HTMLButtonElement>('button[aria-label="消息操作与来源"]');
  const revision=Number(message?.sourceRevision||0);
  const chatWork=message?.id&&message.taskId===draft.taskId&&conversation&&node&&article&&trigger&&Number.isSafeInteger(revision)&&revision>0
   ?{document:bridge.getDocumentEpoch(),conversation,message:message.id,revision,top:node.scrollTop,offset:article.getBoundingClientRect().top-node.getBoundingClientRect().top,trigger}:undefined;
  setMemoryDraftTarget(null);setSkillDraftTarget({...draft,chatWork});
 };
 const taskTransientBack=useRef<(()=>boolean)|null>(null);
 const phoneWorkReportExit=useRef<{exited:()=>boolean;retire:()=>void}|null>(null);
 useEffect(()=>()=>phoneWorkReportExit.current?.retire(),[]);
 const originalPhoneToolOpen=useRef<((pin:OriginalPhoneToolRecord)=>boolean)|null>(null);
 const capabilityBack=useRef<(()=>boolean)|null>(null);
 const taskPolicyBeforeLeave=useRef<PermissionLeaveGuard|null>(null);
 const projectDraftBeforeLeave=useRef<(()=>boolean)|null>(null);
 const registerProjectDraftBeforeLeave=useCallback((guard:()=>boolean)=>{
  projectDraftBeforeLeave.current=guard;
  return ()=>{if(projectDraftBeforeLeave.current===guard)projectDraftBeforeLeave.current=null};
 },[]);
 // A dirty project editor never replays the blocked action. Permission review retains its own guard.
 const guardTaskLeave=useCallback((next:()=>void)=>projectDraftBeforeLeave.current?.()===true||taskPolicyBeforeLeave.current?.(next)===true,[]);
 const registerTaskPolicyBeforeLeave=useCallback((guard:PermissionLeaveGuard|null)=>{taskPolicyBeforeLeave.current=guard},[]);
 const deviceScopeBack=useRef<(()=>boolean)|null>(null);
 const registerDeviceScopeBack=useCallback((handler:(()=>boolean)|null)=>{deviceScopeBack.current=handler},[]);
 const livePreviewBack=useRef<(()=>boolean)|null>(null);
 const registerLivePreviewBack=useCallback((handler:(()=>boolean)|null)=>{livePreviewBack.current=handler},[]);
 const registerCapabilityBack=useCallback((handler:(()=>boolean)|null)=>{capabilityBack.current=handler},[]);
 const registerTaskBack=useCallback((handler:(()=>boolean)|null)=>{taskTransientBack.current=handler},[]);
 const registerOriginalPhoneTool=useCallback((handler:((pin:OriginalPhoneToolRecord)=>boolean)|null)=>{originalPhoneToolOpen.current=handler},[]);
 const attachmentBack=useRef<(()=>boolean)|null>(null);
 const resultFileReturnFocus=useRef<({taskId:string;report?:VerifiedHomeReport}|{conversationId:string;resultMessageId:string;result:TaskResult;revision:number;document:number;navigation:number;resume:number;taskRequest:number;reader?:HistoryResultOrigin;historySourceGeneration?:number;chatReading?:ChatWorkReturn})&{target:HTMLButtonElement}|null>(null);
 const [fileAttachmentPicker,setFileAttachmentPicker]=useState<FileAttachmentPickerOrigin|null>(null);
 const fileAttachmentPickerLaunch=useRef<FileAttachmentPickerLaunch|null>(null);
 const [reopenedAttachmentPicker,setReopenedAttachmentPicker]=useState<ReopenedAttachmentPicker|null>(null);
 const openingChatResult=useRef('');
 const [openingChatResultId,setOpeningChatResultId]=useState('');
 const registerAttachmentBack=useCallback((handler:(()=>boolean)|null)=>{attachmentBack.current=handler},[]);
 const registerSecondaryBack=useCallback((handler:(()=>boolean)|null)=>{secondaryBack.current=handler},[]);
 const [taskErrors,setTaskErrors]=useState<Record<string,string>>({});
 const [taskMenuOpen,setTaskMenuOpen]=useState(false);
 const [localError,setLocalError]=useState(''),[localStatus,setLocalStatus]=useState(''),[review,setReview]=useState<Review|null>(null),[reviewPending,setReviewPending]=useState(false),[reviewSubmitted,setReviewSubmitted]=useState(false),[reviewNote,setReviewNote]=useState(''),[reviewFeedbackOpen,setReviewFeedbackOpen]=useState(false),[scopeTools,setScopeTools]=useState(''),[scopeModels,setScopeModels]=useState('');
 const [reviewReceipt,setReviewReceipt]=useState('');
 const [draftAcceptance,setDraftAcceptance]=useState<DraftAcceptanceClick|null>(null);
 const [projectFeedback,setProjectFeedback]=useState<Record<string,string>>({}),projectFeedbackRef=useRef<Record<string,string>>({});
 const putProjectFeedback=(key:string,text:string)=>{if(!key)return;const next={...projectFeedbackRef.current,[key]:text};projectFeedbackRef.current=next;setProjectFeedback(next)};
 const [locateProjectFeedback,setLocateProjectFeedback]=useState('');
 const [continuations,setContinuations]=useState<Record<string,ContinuationSubmission>>({}),continuationsRef=useRef<Record<string,ContinuationSubmission>>({});
 const reviewWindow=useRef<TaskReviewWindow|null>(null);
 const rememberContinuation=useCallback((value:ContinuationSubmission)=>{continuationsRef.current={...continuationsRef.current,[value.original.taskId]:value};setContinuations(continuationsRef.current)},[]);
 const invalidateContinuation=useCallback(()=>{for(const value of Object.values(continuationsRef.current)){const next=continuationUnknown(value);if(next!==value)rememberContinuation(next)}},[rememberContinuation]);
 const currentReviewWindow=useCallback((value:TaskReviewWindow|null)=>{const now=bridge.getState();return !!value&&!document.hidden&&reviewRequest.current===value.request&&openedTaskId.current===value.taskId&&bridge.getDocumentEpoch()===value.documentEpoch&&Number(obj(now.view).resumeRevision||0)===value.resumeRevision&&obj(now.view).taskId===value.taskId},[]);
 const [questionPending,setQuestionPending]=useState(false);
 const [intent,setIntent]=useState<'chat'|'background'|'main_screen'>('chat'),[phoneReview,setPhoneReview]=useState<PhoneReview|null>(null),[phoneReviewPending,setPhoneReviewPending]=useState(false);
 const [phoneReviewError,setPhoneReviewError]=useState<{handle:string;message:string}|null>(null);
 const [phoneStart,setPhoneStart]=useState<PhoneStartReceipt|null>(null);
 const phoneAction=useRef(false),phoneWindow=useRef(0),phoneReviewLive=useRef(phoneReview);phoneReviewLive.current=phoneReview;
 const phonePlacePicker=useRef<{pin:PhonePlaceReturn;navigation:number}|null>(null);
 const phonePresentation=useMemo(()=>phoneReview?{goal:phoneReview.goal,mode:phoneReview.mode,message:phoneReview.message,screenDisclosure:phoneReview.screenDisclosure,conversationId:phoneReview.conversationId,conversationTitle:phoneReview.conversationTitle,modelName:phoneReview.modelName,toolLimit:phoneReview.toolLimit,modelCallLimit:phoneReview.modelCallLimit,scopeMinutes:phoneReview.scopeMinutes,conditions:phoneReview.conditions}:null,[phoneReview]);
 const phoneExit=useClosingSnapshot(phonePresentation),phoneDisplay=phoneExit.display;
 const [scopeMinutes,setScopeMinutes]=useState('-1');const [newProjectOpen,setNewProjectOpen]=useState(false),[projectName,setProjectName]=useState(''),[newFileOpen,setNewFileOpen]=useState(false),[newPath,setNewPath]=useState(''),[fileModes,setFileModes]=useState<Record<string,FileLayer>>({}),[fileDrafts,setFileDrafts]=useState<Record<string,FileDraft>>({});
 const channelPhoneModel=intent!=='chat'&&phoneDraft.channelOrigin===true;
 const composerModelName=channelPhoneModel?String(state.modelName||''):conversationModelName(state);
 const createWindow=useRef(0),createPending=useRef(new Set<number>());const [createBusy,setCreateBusy]=useState(false),[createKind,setCreateKind]=useState<'project'|'file'>('project');
 const closeCreate=()=>{createWindow.current++;setNewProjectOpen(false);setNewFileOpen(false);setCreateBusy(false)};
 const openCreate=(kind:'project'|'file')=>{createWindow.current++;setCreateKind(kind);setCreateBusy(false);setNewProjectOpen(kind==='project');setNewFileOpen(kind==='file')};
 const modelPicker=useRef<ConversationModelPickerHandle>(null);
 const [voiceReview,setVoiceReview]=useState<any>(null),[voiceText,setVoiceText]=useState(''),[voicePending,setVoicePending]=useState(false);const [voiceOptionsOpen,setVoiceOptionsOpen]=useState(false),[workOptionsOpen,setWorkOptionsOpen]=useState(false),[messageMenu,setMessageMenu]=useState<string|null>(null),[expandedCalls,setExpandedCalls]=useState<Record<string,boolean>>({});
 const [permissionPane,setPermissionOptionsOpen]=useState<boolean|{input:ModelSetupInput;navigation:number;resume:number}>(false);
 const attachmentPermission=typeof permissionPane==='object'&&isChatSurface&&intent==='chat'&&!taskOpen&&!obj(snapshot?.view).section&&
  permissionPane.navigation===navigationRevision&&permissionPane.resume===Number(obj(snapshot?.view).resumeRevision||0)&&
  sameModelSetupInput(permissionPane.input,snapshot,personalDocumentEpoch)?permissionPane:null;
 // Wait for the native attachment picker to close before presenting the existing rule editor.
 const attachmentPermissionOpen=!!attachmentPermission&&!state.chatAttachmentPickerOpen;
 const permissionOptionsOpen=permissionPane===true||attachmentPermissionOpen;
 useEffect(()=>{if(typeof permissionPane==='object'&&!attachmentPermission)setPermissionOptionsOpen(false)},[permissionPane,attachmentPermission]);
 const [permissionPending,setPermissionPending]=useState(false);const permissionWriting=useRef(false),permissionConversation=useRef(state.conversationId);
 const permissionExit=useReviewFocus(permissionOptionsOpen);
 const permissionBeforeLeave=useRef<PermissionLeaveGuard|null>(null);
 const registerPermissionBeforeLeave=useCallback((guard:PermissionLeaveGuard|null)=>{permissionBeforeLeave.current=guard},[]);
 const permissionSaving=useCallback((pending:boolean)=>{permissionWriting.current=pending;setPermissionPending(pending)},[]);
 const closePermissionOptions=useCallback(()=>{if(permissionWriting.current)return;const close=()=>setPermissionOptionsOpen(false);if(!permissionBeforeLeave.current?.(close))close()},[]);
 useEffect(()=>{if(permissionConversation.current!==state.conversationId){setPermissionOptionsOpen(false);permissionWriting.current=false;setPermissionPending(false)}permissionConversation.current=state.conversationId},[state.conversationId]);
 const voiceReviewLive=useRef(voiceReview),voiceWriting=useRef(false);voiceReviewLive.current=voiceReview;
 const voiceReading=useRef(false),voiceReviewOwner=useRef('');
 const retainedVoiceEdit=useRef<{owner:string;inputId:string;original:string;edited:string}|null>(null);
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
 const taskCandidate=tasks.find(x=>x.id===taskId),expectedProjectRun=projectReading?.sourcePackageRun??projectReading?.expected;
 const wrongProjectRun=!!expectedProjectRun&&(taskCandidate?.raw?.runId!==expectedProjectRun.runId||taskCandidate?.raw?.coordinatorEpoch!==expectedProjectRun.epoch);
 const returningTeamWork=route!=='teams'&&relatedOrigin.current?.route===route?relatedOrigin.current.task?.teamResult?.source:undefined;
 const teamWorkNotReady=!!returningTeamWork&&returningTeamWork.parentTaskId===taskId&&
   (taskOpening||openedTaskId.current!==taskId||snapshot?.view?.taskId!==taskId||!completedWorkTeam(returningTeamWork,tasks));
 const selectedTask=noticeNotReady||reportSourceChanged||wrongProjectRun||teamWorkNotReady||projectReading&&(openedTaskId.current!==taskId||snapshot?.view?.taskId!==taskId||state.projectId!==projectReading.projectId)?undefined:taskCandidate;const currentProject=projects.find(x=>x.id===state.projectId);const currentConversation=conversations.find(x=>x.id===state.conversationId);
 useLayoutEffect(()=>{
  // Choose one parent before mounting the tools in this admitted drawer visit. Later receipts must not move a live reader.
  const task=selectedTask,raw=task?.raw,view=obj(snapshot?.view);
  if(taskToolsPlace||!taskOpen||taskOpening||!task||openedTaskId.current!==task.id||view.taskId!==task.id||view.section!=null||
     !['detail_window','source_unavailable'].includes(raw?.taskProjection)||!can('taskCapabilities.open'))return;
  const ordinary=raw?.executionRole==='owner_work'&&!raw?.developmentContext&&!raw?.ownerPhoneControl&&!raw?.ownerDeviceWork;
  const overview=ordinary&&can('taskCapabilities.artifact')&&can('taskCapabilities.export')&&!!retainedLocalScriptOutputs(task);
  setTaskToolsPlace(place=>place??{taskId:task.id,overview});
 },[taskToolsPlace,taskOpen,taskOpening,selectedTask,snapshot?.view,can]);
 const historyPage=state.conversationReader||null,workRequestPin=obj(historyPage?.workRequest),workRequestOrigin=workRequestVisit.current;
 const workRequestPage=taskOpen&&workRequestOrigin&&taskId===workRequestOrigin.work.taskId&&snapshot?.view?.taskId===taskId&&
  workRequestPin.taskId===taskId&&workRequestPin.runId===workRequestOrigin.work.runId&&workRequestPin.epoch===workRequestOrigin.work.epoch&&
  (workRequestOrigin.window===undefined||workRequestOrigin.window===historyPage?.window)&&
  workRequestOrigin.document===bridge.getDocumentEpoch()&&workRequestOrigin.navigation===navigationRevision?historyPage:null;
 useEffect(()=>setTaskMenuOpen(false),[taskId,taskOpen,taskLayer,selectedTask?.status,selectedTask?.raw?.runId,selectedTask?.raw?.coordinatorEpoch,personalDocumentEpoch,snapshot?.view?.resumeRevision]);
 useEffect(()=>{const hide=()=>{if(document.hidden)setTaskMenuOpen(false)};document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide)},[]);
 const chatOwner=`${composer.key||''}:${composer.epoch??''}`;const chatDraft=useNativeDraft(chatOwner,String(composer.text||''),composer.revision);const phoneOwner=`phone:${phoneDraft.epoch??''}:${state.conversationId||''}:${phoneDraft.channelOrigin===true?'channel':'private'}:${phoneDraft.sourceState==='elsewhere'?'away':'here'}`;const phoneText=useNativeDraft(phoneOwner,String(phoneDraft.text||''),phoneDraft.revision);
 const voiceOwner=JSON.stringify([bridge.getDocumentEpoch(),route,intent,intent==='chat'?chatOwner:phoneOwner]);
 voiceReviewOwner.current=voiceOwner;
 useEffect(()=>{
  if(retainedVoiceEdit.current?.owner!==voiceOwner)retainedVoiceEdit.current=null;
  setVoiceReview(null);setVoiceText('');
 },[voiceOwner]);
 const text=intent==='chat'?chatDraft.value:phoneText.value;
 // This is presentation of the native draft classifier, never a Web permission to read the display.
 const displayStageFlight=useRef('');
 const [displayStageOwner,setDisplayStageOwner]=useState('');
 const displayStagePending=displayStageOwner===chatOwner;
 const displayDraftSettling=intent==='chat'&&(displayStagePending||Boolean(composer.primaryDisplayGate)&&composer.text!==text);
 const displayGate=intent==='chat'&&composer.text===text?composer.primaryDisplayGate:null;
 const localDisplayReady=displayGate==='ready';
 // This is the displayed draft version, not a newer bridge snapshot the user has not seen.
 const ordinaryDraftRevision=composer.ready===true&&composer.conversationId===String(state.conversationId||'')&&
   composer.text===text&&Number.isSafeInteger(composer.revision)&&composer.revision>0?Number(composer.revision):null;
 const displayBlocked=displayGate&&!localDisplayReady?(displayGate==='busy'?'请先等原对话工作结束':'请先核对原私人窗口与本机执行开关'):'';
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
 const ownerQuestion=(review?.ownerQuestion||null) as OwnerQuestionView|null;
 const ownerQuestionInWindow=taskOpen&&!taskOpening&&!reviewStale&&!review?.capabilityApprovalRead&&!!selectedTask&&
  review?.task?.id===selectedTask.id&&currentReviewWindow(reviewWindow.current);
 const freshOwnerQuestionReview=ownerQuestionInWindow&&!reviewPending;
 const mainPhoneQuestionSource=isChatSurface&&freshOwnerQuestionReview&&review?.actions?.some(row=>row.action==='tasks.answerQuestion')?
  mainPhoneAnswerSource(review.task,ownerQuestion):undefined;
 const {text:questionAnswer,edit:setQuestionAnswer,close:closeQuestionAnswer,saved:clearSavedQuestionAnswer}=
  useOwnerQuestionDraft(ownerQuestion,mainPhoneQuestionSource,isChatSurface,freshOwnerQuestionReview,ownerQuestionInWindow&&reviewPending);
 const isListening=!!state.listening||!!state.voiceInput?.active;
 const draftKey=String(composer.key||'');const draftEpoch=Number(composer.epoch||0);
 const newMemoryScope=!state.conversationId&&draftKey.startsWith('new:')&&typeof composer.firstMessageMemory?.paused==='boolean'&&can('composer.firstMemory.save');
 const newMemoryBlocked=newMemoryScope&&(composer.firstMessageMemory.saving||composer.firstMessageMemory.confirmed!==true);
 // The original conversation's settings are not a global permission or a record of what a reply used.
 const memoryControls=conversationMemoryReadable&&conversationMemoryControls?.conversationId===state.conversationId?conversationMemoryControls:null;
 const memoryPausedHere=newMemoryScope?composer.firstMessageMemory.confirmed===true&&composer.firstMessageMemory.paused:memoryControls?.enabled===false;
 const singleLearningScope=intent==='chat'&&((newMemoryScope&&composer.conversationId==='')|| (!!state.conversationId&&composer.conversationId===state.conversationId))&&can('composer.oneLearning.save')&&typeof composer.oneMessageLearning?.excluded==='boolean';
 const singleLearningArmed=singleLearningScope&&composer.oneMessageLearning.excluded===true;
 const singleLearningBlocked=singleLearningScope&&(composer.oneMessageLearning.saving||composer.oneMessageLearning.confirmed!==true||singleLearningArmed&&composer.oneMessageLearning.pending===true);
 const canEditMemoryHere=conversationMemoryReadable||newMemoryScope;
 const memoryStatus=intent!=='chat'||!canEditMemoryHere?'':singleLearningBlocked?'核对本条记忆选择':newMemoryBlocked?'核对首条消息的记忆选择':
   memoryPausedHere?(newMemoryScope?'新对话将暂停个人记忆':'本对话已暂停个人记忆'):
   memoryControls?.excludeNewInputs===true?'本对话不自动整理新原话':singleLearningArmed?'仅本条不自动整理':'';
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
 const closeChatAttachmentPicker=useCallback(()=>{
  setPermissionOptionsOpen(current=>typeof current==='object'?false:current);
  if(reopenedAttachmentPicker&&reopenedPickerCurrent(reopenedAttachmentPicker)&&can('attachments.closePicker')){
   run('attachments.closePicker',{pickerRequestId:reopenedAttachmentPicker.requestId});return true;
  }
  const launch=fileAttachmentPickerLaunch.current;
  if(launch){
   launch.cancelled=true;
   if(launch.settled&&launch.requestId&&can('attachments.closePicker')&&bridge.getState().state.chatAttachmentPickerRequest===launch.requestId){
    run('attachments.closePicker',{pickerRequestId:launch.requestId});return true;
   }
   // A suppressed/unknown picker is not a visible Back layer. Don't guess a close target.
   return false;
  }
  if(!can('attachments.closePicker'))return false;
  run('attachments.closePicker',fileAttachmentPicker?{pickerRequestId:fileAttachmentPicker.requestId}:{});return true;
 },[can,run,fileAttachmentPicker,reopenedAttachmentPicker]);
 const openAttachmentPagingRule=()=>{
  const live=bridge.getState(),view=obj(live.view),input=modelSetupInput(live,bridge.getDocumentEpoch());
  if(!input||!isChatSurface||intent!=='chat'||document.hidden||taskOpen||view.section||
     live.state.chatAttachmentPickerOpen!==true||live.state.chatAttachmentPagingDenied!==true||live.state.attachmentImporting||attachmentPermission)return;
  const origin={input,navigation:navigationWindow.current,resume:Number(view.resumeRevision||0)};
  permissionExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);
  setPermissionOptionsOpen(origin);
  void act('attachments.closePicker').catch(()=>setPermissionOptionsOpen(current=>current===origin?false:current));
 };
 // Each click rechecks the displayed original against the newest private phone projection; the native command rechecks ownership again.
 const exactPhoneControl=(action:string,shown:{taskId:string;runId:string;epoch:number},isLocal:boolean,shownPause?:DisplayedPhonePause)=>{
  if(!isLocal)return act(action,shown);
  const native=bridge.getState().state,current=asArray<Task>(native.tasks).find(row=>row.id===shown.taskId);
  const live=privatePhoneSurface(current,native.livePrivatePhones);
  if(document.hidden||!current||terminal(current)||!live||live.runId!==shown.runId||live.epoch!==shown.epoch||live.stopRequested||
     action==='phone.pause'&&(live.paused||current.status!=='running')||
     action==='phone.resume'&&(!live.paused||current.status!=='paused'||conversationInlineAttention(current)))
    return Promise.reject(new Error('这次手机执行或控制状态已变化。请核对原任务，不会操作其他屏幕。'));
  if(action==='phone.resume'){
    if(!shownPause||!sameDisplayedPause(shownPause,live.displayedPause))return Promise.reject(new Error('原暂停或其范围已变化。请核对现在的暂停，这次点击没有继续。'));
    return act(action,{taskId:shown.taskId,runId:shown.runId,epoch:shown.epoch,...shownPause},shown.taskId).then(receipt=>{
      if(receipt?.taskId!==shown.taskId||receipt.runId!==shown.runId||receipt.epoch!==shown.epoch||!sameDisplayedPause(shownPause,receipt)||
         receipt.continuedOriginalRun!==true||receipt.budgetIncreased!==false)
        throw new Error('未取得这次暂停的完整继续回执。请查看原任务，不要重复继续。');
      return receipt;
    });
  }
  return act(action,{taskId:shown.taskId,runId:shown.runId,epoch:shown.epoch});
 };
 const triggerPhoneControl=(action:string,shown:{taskId:string;runId:string;epoch:number},isLocal:boolean,shownPause?:DisplayedPhonePause)=>{
  void exactPhoneControl(action,shown,isLocal,shownPause).catch(error=>setLocalError(error instanceof Error?error.message:String(error)));
 };
 const cancelPending=useRef(new Set<string>());
 const [cancelFeedback,setCancelFeedback]=useState<Record<string,ChatStopRecord>>({});
 const currentCancelIdentity=chatCancelIdentity(state);
 const currentCancelKey=currentCancelIdentity?chatCancelKey(currentCancelIdentity):'';
 const currentCancelFeedback=chatStopObservation(state,cancelFeedback);
 // A newer foreground run in the same conversation retires the old inline notice,
 // not its actual durable task or saved results in the work directory.
 useEffect(()=>{
  if(!currentCancelKey)return;
  setCancelFeedback(before=>{
   const kept=Object.entries(before).filter(([key,row])=>key===currentCancelKey||row.original.conversationId!==state.conversationId);
   return kept.length===Object.keys(before).length?before:Object.fromEntries(kept);
  });
 },[currentCancelKey,state.conversationId]);
 const stopOriginalChat=()=>{
  const expected=currentCancelIdentity;
  if(document.hidden||!expected||!can('chat.cancel')||!sameChatCancelIdentity(bridge.getState().state,expected))return;
  const key=chatCancelKey(expected);
  if(cancelPending.current.has(key))return;
  cancelPending.current.add(key);
  setCancelFeedback(before=>Object.fromEntries([...Object.entries(before).filter(([prior])=>prior!==key),
   [key,{original:expected,request:'sending'} as ChatStopRecord]].slice(-16)));
  const remember=(request:ChatStopRecord['request'])=>setCancelFeedback(before=>before[key]?.original===expected?
   {...before,[key]:{original:expected,request}}:before);
  void bridge.command('chat.cancel',expected).then(receipt=>remember(receipt?.cancellationRequested===true?'requested':'unknown'),
   ()=>remember('unknown')).finally(()=>{cancelPending.current.delete(key)});
 };
 const {switching:draftSwitching,pending:draftSwitchPending,open:openDraftSwitch}=useConversationDraftSwitch(setLocalError);
 const reportDraftFlight=useRef<{key:string;epoch:number}|null>(null);
 const [reportDraftSwitching,setReportDraftSwitching]=useState(false);
 const editText=useCallback((next:string)=>{
  if(intent==='chat'){
   if(reportDraftFlight.current?.key===draftKey&&reportDraftFlight.current?.epoch===draftEpoch)return;
   const live=bridge.getState(),owner=live.composer,conversation=String(state.conversationId||'');
   // Do not apply an event from the previous textarea to a departing native window.
   if(draftSwitchPending()||document.hidden||bridge.getDocumentEpoch()!==personalDocumentEpoch||live.state.conversationReader||
      owner?.ready!==true||owner.key!==draftKey||owner.epoch!==draftEpoch||owner.conversationId!==conversation||
      String(live.state.conversationId||'')!==conversation||!live.capabilities.commands.includes('composer.edit'))return;
   chatDraft.set(next);run('composer.edit',{key:draftKey,epoch:draftEpoch,text:next});
  }
  else{phoneWindow.current++;phoneText.set(next);setPhoneReview(null);if(can('phone.draft.edit'))run('phone.draft.edit',{text:next,epoch:phoneDraft.epoch,...(Number.isSafeInteger(phoneDraft.sourceEditTicket)?{sourceEditTicket:phoneDraft.sourceEditTicket,draftConversation:String(state.conversationId||'')}:{})});}
 },[intent,chatDraft,phoneText,can,run,draftKey,draftEpoch,draftSwitchPending,personalDocumentEpoch,phoneDraft.epoch,phoneDraft.sourceEditTicket,state.conversationId]);
 const rememberTaskPosition=useCallback(()=>{const node=taskScroll.current;if(taskId&&node?.isConnected)taskReading.current.set(`${taskId}:${taskLayer}`,node.scrollTop)},[taskId,taskLayer]);
 const showTaskLayer=useCallback((next:TaskLayer)=>{const commit=()=>{rememberTaskPosition();setTaskLayer(next)};if(!guardTaskLeave(commit))commit()},[rememberTaskPosition]);
 const releasePhoneNotice=useCallback(()=>{const pin=phoneNoticeRef.current;if(!pin)return;phoneNoticeRef.current=null;setPhoneNoticePin(null);setPhoneNoticePanel(null);
  if(can('phone.notice.close'))void bridge.command('phone.notice.close',{noticeHandle:pin.token}).catch(()=>{});
 },[can]);
 const closeCurrentWorkSupplement=useCallback((explicit=false)=>{
  const target=workSupplementTarget,owner=workSupplementBeforeLeave.current,body=taskScroll.current;
  if(!taskOpen||taskOpening||taskLayer!=='overview'||!target||target.taskId!==taskId||owner?.target!==target||!body)return false;
  const {panel,flush}=owner,navigation=navigationWindow.current,request=reviewRequest.current;
  const documentEpoch=bridge.getDocumentEpoch(),resume=openedTaskResumeRevision.current;
  const current=()=>{
   const registered=workSupplementBeforeLeave.current,live=bridge.getState(),view=obj(live.view);
   if(document.hidden||bridge.getDocumentEpoch()!==documentEpoch||navigationWindow.current!==navigation||reviewRequest.current!==request||
      openedTaskId.current!==target.taskId||view.section!==null||view.taskId!==target.taskId||view.resumeRevision!==resume||
      registered?.target!==target||registered.panel!==panel||taskScroll.current!==body||!body.contains(panel)||
      panel.closest('[hidden],[inert],[aria-hidden="true"]')||!panel.getClientRects().length||
      live.state.conversationReader||live.state.queuedInputsPage||live.state.attachmentWorkspace?.open===true||
      !live.capabilities.commands.includes('ownerWork.supplement.read'))return false;
   const rows=asArray<Task>(live.state.tasks).filter(row=>row.id===target.taskId);
   const work=ordinaryChatWorkRow(rows.length===1?rows[0]:undefined,target.conversationId,true);
   return !!work&&work.runId===target.runId&&work.epoch===target.epoch;
  };
  if(!current())return false;
  // Back cannot hide an unsettled receipt; X is the explicit close as before.
  const keepReceipt=()=>!explicit&&owner.keepsReceipt();
  if(!keepReceipt())void flush().then(saved=>{if(saved&&current()&&!keepReceipt())dismissWorkSupplement(target)});
  return true;
 },[taskOpen,taskOpening,taskLayer,taskId,workSupplementTarget,dismissWorkSupplement]);
 const closeTask=useCallback((restoreButlerWork=false,onClosing?:()=>void,reportClose?:{handoff:string;current:()=>boolean})=>{const commit=async()=>{
  if(reportClose){
   if(workSupplementBeforeLeave.current||!reportClose.current()||!can('tasks.close'))return false;
   const closed=await bridge.command('tasks.close',{reportHandoff:reportClose.handoff}).catch(()=>null);
   if(closed?.closed!==true||!reportClose.current())return false;
  }else if(workSupplementBeforeLeave.current&&!await workSupplementBeforeLeave.current.flush())return false;
  onClosing?.();
  const origin=homeWorkReturn.current;
  // The original overview can still be the same read-only visit from Home.
  const reportInOriginalOverview=(report:VerifiedHomeReport)=>{
   if(taskLayer!=='overview'||openedTaskId.current!==taskId)return false;
   const live=bridge.getState(),view=obj(live.view),{taskId:id,runId,epoch,conversationId}=report.target;
   if(id!==taskId||view.section!==null||view.taskId!==id)return false;
   const work=ordinaryChatWorkRow(asArray<Task>(live.state.tasks).find(row=>row.id===id),conversationId);
   return !!work&&work.runId===runId&&work.epoch===epoch&&currentHomeReport(report);
  };
  // Closing for a route handoff must not pull focus back behind the destination.
  if(restoreButlerWork&&route==='butler'&&origin?.taskId===taskId&&
    (origin.report?
      (taskLayer==='homeReport'||taskLayer==='overview')&&origin.returning&&!homeReport.current||
      origin.report===homeReport.current&&(taskLayer==='homeReport'||reportInOriginalOverview(origin.report))
      :openedTaskId.current===taskId||origin.returning))origin.returning=true;
  else homeWorkReturn.current=null;
  const project=projectTaskReturn.current;
  if(restoreButlerWork&&route==='projects'&&project?.focusShelf&&project.taskId===taskId)project.returning=true;
  releasePhoneNotice();homeReport.current=null;taskHistoryOrigin.current=null;taskReconciliationOrigin.current=null;rememberTaskPosition();invalidateContinuation();reviewWindow.current=null;reviewRequest.current++;openedTaskId.current=null;setTaskOpening(false);const closed=reportClose?Promise.resolve(true):can('tasks.close')?act('tasks.close').then(()=>true,()=>false):Promise.resolve(false);setTaskOpen(false);setFocusedOriginalDraft(null);setTaskLayer('overview');setWorkSupplementTarget(null);setReview(null);setReviewPending(false);setReviewSubmitted(false);setReviewReceipt('');closeQuestionAnswer();return closed;};if(guardTaskLeave(reportClose?()=>{}:()=>{void commit()}))return Promise.resolve(false);return commit();},[can,act,guardTaskLeave,rememberTaskPosition,invalidateContinuation,releasePhoneNotice,route,taskId,taskLayer,closeQuestionAnswer]);
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
   setSentPhoneFocus(null);
   if(next!=='teams'){setTeamAttentionFocus(undefined);setTeamRunFocus(undefined);}
   if(requested==='memory'||requested==='profile'){setPersonalTarget({view:requested==='memory'?'memories':'home'});setPersonalTargetKey(key=>key+1);}
   if(next==='profile'&&route!=='profile')profileOrigin.current={route:!['butler','chat','tasks','projects','setup','remoteInbox','developer-logs'].includes(route)&&secondaryPage?secondaryPage:route,drawerOpen:workOpen,scrollTop:navigationDrawer.current?.querySelector<HTMLElement>('.pw-drawer-body')?.scrollTop||0};
   if(!options?.keepOrigin){relatedOrigin.current=null;setLocalWorkspaceDraft(undefined);}
   setupReturnPending.current=false;rememberChatPosition();setWorkOpen(false);setCommandOpen(false);
   if(taskOpen&&!taskClosed&&!await closeTask())return false;
   if(options?.stillCurrent&&!options.stillCurrent())return false;
   terminalSavedFileArrival.current=null;
   navigationWindow.current++;setNavigationRevision(navigationWindow.current);setRoute(next);afterNavigate?.();return true;
  };
  if(guardTaskLeave(()=>{void navigate(requested,afterNavigate,options)}))return Promise.resolve(false);
  if(secondaryBeforeLeave.current?.(()=>{void commit()}))return Promise.resolve(false);
  if(route==='profile'&&next!=='profile'){
   setWorkOpen(false);setCommandOpen(false);
   if(profileBeforeLeave.current?.(()=>{void commit()}))return Promise.resolve(false);
  }
  return commit();
 },[route,secondaryPage,workOpen,taskOpen,closeTask,rememberChatPosition,can,act,leaveInlinePreparation]);
 const openPersonalMemory=(target:PersonalTarget)=>{void navigate('profile',()=>{setPersonalTarget(target);setPersonalTargetKey(key=>key+1)})};
 const returnFromProfile=()=>{const origin=profileOrigin.current;void navigate(origin.route==='profile'?'butler':origin.route==='memory'?'advanced-memory':origin.route,()=>{if(origin.drawerOpen&&!desktopNavigation){profileRestoreScroll.current=origin.scrollTop;setWorkOpen(true)}})};
 const atChatResultReturn=useCallback((origin:ChatWorkReturn)=>!document.hidden&&origin.document===bridge.getDocumentEpoch()&&
  origin.result?.navigation===navigationWindow.current&&bridge.getState().state.conversationId===origin.conversation&&
  !bridge.getState().state.conversationReader,[]);
 const currentChatWorkRow=useCallback((origin:ChatWorkRowOrigin,phase:'source'|'work'|'return')=>{
  const live=bridge.getState(),view=obj(live.view),draft=live.composer,owner=live.state,phone=obj(live.phoneDraft);
  const {trigger,conversation,work}=origin,node=scroll.current;
  const nativeTask=phase==='source'?view.taskId===null:phase==='work'?view.taskId===work.taskId:view.taskId===null||view.taskId===work.taskId;
  if(!nativeTask||view.section!==null||document.hidden||bridge.getDocumentEpoch()!==origin.documentEpoch||
     navigationWindow.current!==origin.navigation||view.resumeRevision!==origin.resumeRevision||owner.requestedTaskRevision!==origin.nativeTaskRequest||
     owner.conversationId!==conversation||owner.conversationReader||owner.queuedInputsPage||owner.attachmentWorkspace?.open||
     draft?.ready!==true||draft.key!==`conversation:${conversation}`||draft.conversationId!==conversation||draft.epoch!==origin.draftEpoch||
     phone.phone!==false||phone.channelOrigin!==false||node?.dataset.conversationId!==conversation||!node.contains(trigger)||
     !trigger.isConnected||trigger.disabled||trigger.closest('[hidden],[inert]')||trigger.getClientRects().length===0||
     trigger.closest<HTMLElement>('[data-work-task-id]')?.dataset.workTaskId!==work.taskId)return false;
  const matches=asArray<Task>(owner.tasks).filter(row=>row.id===work.taskId);
  const current=ordinaryChatWorkRow(matches.length===1?matches[0]:undefined,conversation,phase==='work');
  return !!current&&workIdentityKey(current)===workIdentityKey(work);
 },[]);
 const chatWorkLocation=useCallback((origin:ChatWorkReturn,onMissing?:()=>void,exact=false)=>{
  const live=bridge.getState(),node=scroll.current;
  const result=origin.result;
  if(result&&(result.work.taskId!==taskId||!atChatResultReturn(origin)))return;
  const missing=()=>{onMissing?.();return undefined};
  const rows=asArray<Message>(live.state.messages).filter(m=>m.id===origin.message);
  if(document.hidden||bridge.getDocumentEpoch()!==origin.document||live.state.conversationId!==origin.conversation)return;
  if(exact&&(!Number.isSafeInteger(origin.revision)||origin.revision<1||rows.length!==1||rows[0].sourceRevision!==origin.revision))return;
  if(!node||node.dataset.conversationId!==origin.conversation||origin.hostTaskId&&rows.length!==1||
     !rows.some(m=>Number(m.sourceRevision||0)===origin.revision&&(!origin.hostTaskId||m.hostGoalTaskId===origin.hostTaskId)))return missing();
  if(origin.replyRun){
   const reply=rows.length===1?messageReplyContext(rows[0]):null;
   if(!reply||workIdentityKey(reply)!==workIdentityKey(origin.replyRun))return;
  }
  if(result&&!resultMessageIn(rows,origin.message,result.work,origin.revision))return missing();
  if(origin.phoneDelivery){
   const {result:phone,resume}=origin.phoneDelivery,view=obj(live.view),owner=live.state;
   const saved=rows.length===1&&rows[0].sourceRevision===origin.revision?messagePhoneResult(rows[0]):null;
   const work=asArray<Task>(owner.tasks).filter(row=>row.id===phone.taskId),actual=work.length===1?work[0]:undefined;
   const run=phoneRunIdentity(actual);
   // The immutable old message can outlive a newer attempt of the same work.
   if(!actual||!isPrivateLocalPhone(actual)||phoneConversationId(actual)!==origin.conversation||
      run?.runId!==phone.runId||run.epoch!==phone.epoch)return;
   if(view.section!==null||view.taskId!==null&&view.taskId!==phone.taskId||view.resumeRevision!==resume||
      owner.conversationReader||owner.queuedInputsPage||owner.chatAttachmentPickerOpen||owner.attachmentWorkspace?.open||
      !live.capabilities.commands.includes('phone.result.original')||!saved||saved.deliveryId!==phone.deliveryId||
      saved.taskId!==phone.taskId||saved.runId!==phone.runId||saved.epoch!==phone.epoch||saved.status!==phone.status||
      saved.endedAt!==phone.endedAt||saved.summaryChars!==phone.summaryChars||saved.summaryTruncated!==phone.summaryTruncated)return;
  }
  const article=Array.from(chatMessageList.current?.children||[]).find(row=>(row as HTMLElement).dataset.messageId===origin.message) as HTMLElement|undefined;
  if(!article)return missing();
  return {node,article};
 },[atChatResultReturn,taskId]);
 const restoreChatWorkAt=(origin:ChatWorkReturn)=>{
  const position=chatWorkLocation(origin,origin.result?()=>setLocalStatus('当前对话还没读到这份成果所属的原消息，没有跳到其他轮次。'):undefined);
  if(!position)return false;
  const {node,article}=position;
  stopFollowing();
  node.scrollTop=origin.top;node.scrollTop+=article.getBoundingClientRect().top-node.getBoundingClientRect().top-origin.offset;
  const sameTrigger=origin.trigger.isConnected&&!origin.trigger.disabled&&(!(origin.result||origin.phoneDelivery)||article.contains(origin.trigger));
  const target=sameTrigger?origin.trigger:article;
  target.focus({preventScroll:true});
  return true;
 };
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
  if(guardTaskLeave(()=>{void openRelatedWorkspace(destination,origin,afterNavigate)}))return;
  const navigation=navigationWindow.current,closingTask=taskOpen;
  if(closingTask&&!await closeTask())return;
  if(navigationWindow.current!==navigation)return;
  if(destination==='local'&&origin.route!=='local')setLocalWorkspaceDraft(undefined);
  relatedOrigin.current=origin;
  return navigate(destination,afterNavigate,{keepOrigin:true,taskClosed:closingTask});
 };
 const openProjectTerminal=(checkout?:LocalTerminalCheckout,position?:CheckoutSavedPosition)=>{
  if(!checkout){void openRelatedWorkspace('local');return;}
  const documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const current=()=>{
   const live=bridge.getState(),view=obj(live.view);
   return !document.hidden&&route==='projects'&&!taskOpen&&navigationWindow.current===navigation&&
    bridge.getDocumentEpoch()===documentEpoch&&live.state.projectId===checkout.projectId&&!live.state.filePath&&
    !view.taskId&&!view.section;
  };
  if(!current())return;
  const start=bridge.getState(),resume=obj(start.view).resumeRevision,projectRequest=start.state.requestedProjectRevision;
  const originalOwner=(section:'local'|null)=>{
   const live=bridge.getState(),view=obj(live.view),owner=live.state;
   return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&
    view.section===section&&view.taskId===null&&view.resumeRevision===resume&&
    owner.projectId===checkout.projectId&&owner.requestedProjectRevision===projectRequest&&
    !owner.filePath&&!owner.fileLoading&&!owner.fileSaving&&!owner.conversationReader;
  };
  const terminalSavedFile=position?.projectId===checkout.projectId&&position.checkoutId===checkout.checkoutId&&
   Number.isSafeInteger(resume)&&Number.isSafeInteger(projectRequest)&&originalOwner(null)?
   {position,navigation:navigation+1,current:originalOwner}:undefined;
  const origin:WorkspaceOrigin={...captureWorkspaceOrigin(),label:'返回原私人副本',focus:null,focusKey:'project-copy-terminal',terminalCheckout:checkout,terminalSavedFile};
  void navigate('local',()=>{relatedOrigin.current=origin;setLocalWorkspaceDraft(undefined);},{keepOrigin:true,stillCurrent:current});
 };
 const terminalOrigin=setupReturnPending.current
  ? (setupOrigin.current.route==='local'?setupOrigin.current.parent:null) : relatedOrigin.current;
 const originalTerminalCheckout=route==='local'?terminalOrigin?.terminalCheckout:undefined;
 const openReviewedSkill=async(reviewedSkill:ReviewedSkillTarget,conversationId:string)=>{
  const proposal=skillDraftTarget,source=proposal?.chatWork,navigation=navigationWindow.current;
  if(!proposal||!source||source.conversation!==conversationId||route!=='chat'||taskOpen)return false;
  const stillCurrent=()=>{
   const live=bridge.getState(),rows=asArray<Message>(live.state.messages).filter(row=>row.id===source.message);
   return !document.hidden&&bridge.getDocumentEpoch()===source.document&&navigationWindow.current===navigation&&
    !obj(live.view).taskId&&!live.state.conversationReader&&live.state.conversationId===source.conversation&&
    rows.length===1&&rows[0].role==='assistant'&&rows[0].sourceRevision===source.revision&&rows[0].taskId===proposal.taskId;
  };
  const origin={...captureWorkspaceOrigin(),label:'返回原回答',focus:null,focusKey:undefined,chatWork:source,reviewedSkill,
   drawerChat:{id:source.conversation,draftKey:'',documentEpoch:source.document}};
  return navigate('skills',()=>{relatedOrigin.current=origin;setSkillDraftTarget(null);},{keepOrigin:true,stillCurrent});
 };
 const openNotifications=(afterNavigate?:()=>void)=>route==='remoteInbox'
  ?navigate('remoteInbox',afterNavigate,{keepOrigin:true})
  :openRelatedWorkspace('remoteInbox',captureNavigationOrigin(),afterNavigate);
 const restoreWorkspace=async(origin:WorkspaceOrigin,keepOrigin=false,rereadOriginal=false,afterReturn?:()=>void,
  archiveChatCurrent?:(live:ReturnType<typeof bridge.getState>,arrived:boolean)=>boolean,
  firstHomeModelCurrent?:(live:ReturnType<typeof bridge.getState>,arrived:boolean)=>boolean)=>{
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const terminalFile=route==='local'&&origin.route==='projects'&&origin.terminalSavedFile?.navigation===navigation&&
   origin.terminalSavedFile.current('local')?origin.terminalSavedFile:undefined;
  const phonePlace=origin.phonePlace?returnToPreparedPhoneDraft(origin.phonePlace):undefined;
  if(origin.phonePlace&&!phonePlace){setLocalError('原手机目标已变化或尚未恢复，没有切换到其他草稿。');return;}
  const source=origin.drawerChat,savedHost=origin.savedHostPreparation&&origin.chatWork,modelInput=origin.modelInput,projectModelInput=origin.projectModelInput,proposal=origin.phonePreparation;
  const sourceDocument=savedHost?documentEpoch:phonePlace?.documentEpoch??source?.documentEpoch;
  const teamReturn=origin.task?.teamResult,teamResult=teamReturn?.source;
  // Another Back may have queued behind the same draft guard. Only this visit can commit.
  const teamMayLeave=()=>teamReturn&&relatedOrigin.current===origin&&navigationWindow.current===navigation&&teamReturn.current();
  const guarded=!!(source||modelInput||projectModelInput||phonePlace||proposal||origin.savedHostPreparation||archiveChatCurrent||origin.pairedDevicesProject||teamResult);
  const sameProposalOwner=()=>!proposal||!document.hidden&&phonePreparationOwner()===proposal.owner;
  // Opening the work directory never changes the native composer. Do not select,
  // overwrite or resurrect a conversation if another surface has replaced that owner.
  const sameChat=(arrived=false)=>{const live=bridge.getState();
   if(teamResult){
    const view=obj(live.view);
    if(document.hidden||bridge.getDocumentEpoch()!==documentEpoch||!live.capabilities.commands.includes('tasks.open'))return false;
    if(!arrived){if(!teamMayLeave())return false;}
    else if(view.section!==null&&view.section!=='teams'||view.taskId!==null&&view.taskId!==teamResult.parentTaskId||
      !originalTeamWorkCurrent(origin,teamResult,live))return false;
   }
   if(origin.pairedDevicesProject&&(document.hidden||!origin.projectId||live.state.projectId!==origin.projectId||
     obj(live.view).taskId!==null||live.state.filePath||live.state.fileLoading||live.state.fileSaving||live.state.conversationReader))return false;
   if(archiveChatCurrent?.(live,arrived)===false)return false;
   if(projectModelInput&&!sameProjectModelChat(projectModelInput,origin.chatWork,live))return false;
   // A retired host review never returns. Only the same saved message and its
   // still-selected editor may be shown through a newly issued document token.
   if(origin.savedHostPreparation&&(!savedHost||!source?.draftKey||live.composer?.ready!==true||
      live.composer.key!==source.draftKey||live.composer.conversationId!==savedHost.conversation||
      live.state.conversationReader||!exactSavedHostMessage(asArray<Message>(live.state.messages),savedHost,false)))return false;
   if(origin.task?.fixedArchive&&source&&(live.composer?.key!==source.draftKey||origin.chatWork&&live.state.conversationReader))return false;
   if(modelInput&&!(firstHomeModelCurrent?firstHomeModelCurrent(live,arrived):sameModelSetupInput(modelInput,live,bridge.getDocumentEpoch())))return false;
   return sameProposalOwner()&&(!phonePlace||samePhonePlaceDraft(phonePlace))&&(!source||(bridge.getDocumentEpoch()===sourceDocument&&String(live.state.conversationId||'')===source.id&&(!!source.id||String(live.composer?.key||'')===source.draftKey)));};
  const current=()=>navigationWindow.current===navigation+1&&documentEpoch===bridge.getDocumentEpoch()&&!document.hidden&&sameChat(true);
  const reopenTeamResult=async()=>{
   const task=origin.task;
   if(!teamResult||!task?.expected)return;
   const {id,layer,expected}=task;
   const arrived=()=>relatedOrigin.current===origin&&navigationWindow.current===navigation+1&&
    documentEpoch===bridge.getDocumentEpoch()&&!document.hidden;
   const pending=()=>arrived()&&current();
   const finish=()=>{if(arrived()&&!keepOrigin)relatedOrigin.current=origin.parent||null;};
   const fail=()=>{if(arrived()){finish();setLocalError(originalTeamWorkUnavailable);}};
   // Wait for Secondary's existing connections.leave, not another command.
   const left=await awaitPickerProjection(pending,()=>{const view=obj(bridge.getState().view);
    return view.section===null&&view.taskId===null?true:undefined;});
   if(!left||!pending()){fail();return;}
   openTask(id,layer,false,undefined,undefined,expected,pending,undefined,fail,()=>{
    finish();
    if(tasks.some(task=>task.id===id&&attention(task)))loadTaskReview(id);
    requestAnimationFrame(()=>{
     const view=obj(bridge.getState().view);
     if(!current()||openedTaskId.current!==id||acknowledgedWorkVisit.current?.taskId!==id||view.section!==null||view.taskId!==id)return;
     const matches=Array.from(taskScroll.current?.querySelectorAll<HTMLButtonElement>('button[data-workspace-entry]:not(:disabled)')||[])
      .filter(row=>row.dataset.workspaceEntry===workTeamResultEntryKey(teamResult)&&row.getClientRects().length>0&&!row.closest('[hidden],[inert]'));
     if(matches.length===1){matches[0].scrollIntoView({block:'nearest'});matches[0].focus({preventScroll:true});}
    });
   });
  };
  const closed=await navigate(origin.route==='setup'?'butler':origin.route,()=>{
   // A Team draft guard may defer navigation. Do not use navigate's scheduled return as an entry receipt.
   if(teamResult){void reopenTeamResult();return;}
   if(!keepOrigin)relatedOrigin.current=origin.parent||null;
   if(origin.settingsDirectory)setSettingsRestore({...origin.settingsDirectory,revision:navigationWindow.current});
   // Carry a location only, on the actual ordinary Back. The usual Work read
   // decides whether this is still the saved selection; no editor is opened.
   if(terminalFile&&navigationWindow.current===navigation+1&&(terminalFile.current('local')||terminalFile.current(null))){
    const arrival:ProjectTerminalSavedFileReturn={position:terminalFile.position,take:()=>{
     // The first read may answer before the native section change is projected.
     // Leave this for the normal Work window read after connections.leave.
     if(terminalSavedFileArrival.current!==arrival||navigationWindow.current!==navigation+1||!terminalFile.current(null))return false;
     terminalSavedFileArrival.current=null;
     return true;
    }};
    terminalSavedFileArrival.current=arrival;
   }
  },{keepOrigin:true,...(guarded?{stillCurrent:sameChat}:{})});
  if(!closed&&guarded&&!sameChat()&&navigationWindow.current===navigation&&!document.hidden&&(!teamResult||relatedOrigin.current===origin)){setLocalError(teamResult?originalTeamWorkUnavailable:origin.pairedDevicesProject?'原项目暂不可核对，未打开其他项目。':archiveChatCurrent||projectModelInput?originalArchiveChatUnavailable:origin.savedHostPreparation?
   '无法核对保存的原话与输入窗口，没有打开其他准备。可从导航手动查看原对话。':'原对话或草稿已切换。未打开其他输入，请从导航重新选择原对话。');return;}
  if(teamResult||!closed||!current())return;
  if(!origin.pairedDevicesProject&&origin.projectId&&can('projects.select')&&bridge.getState().state.projectId!==origin.projectId){
   if(proposal){setLocalError('原工作页面已切换，没有打开其他项目或提案。');return;}
   try{await act('projects.select',{id:origin.projectId})}catch{return}
  }
  if(!current())return;
  const returnProposal=()=>{
   if(!proposal||!current())return;
   const target={...proposal.target,fromPhoneSetup:{...proposal.pin,owner:proposal.owner,navigation:navigation+1,taskView:origin.task?.id||''}};
   setPreparationTarget(existing=>existing||target);
  };
  if(origin.task){
   const original=origin.task,{id,layer,expected,fixedArchive,teamModelReturn}=original;
   const sameWork=()=>{
    const rows=bridge.getState().state.tasks;
    return sameWorkspaceTask(original,rows)&&(!teamModelReturn||teamModelReturn.documentEpoch===bridge.getDocumentEpoch()&&
     !!ordinaryChatWorkRow(asArray<Task>(rows).find(row=>row.id===id),teamModelReturn.conversation));
   };
   if(!sameWork()){setLocalError(fixedArchive?'原工作的执行轮次已变化或暂不可读取。固定包仍保留，未打开其他轮次。':'原工作的执行轮次已变化，没有打开新一轮。');return;}
   const failedReturn=proposal||fixedArchive||teamModelReturn?()=>{if(current())setLocalError(fixedArchive?'原运行暂不可打开，未转到其他轮次。固定包仍保留。':teamModelReturn?
    '原工作这一轮暂不可打开，没有切换到其他轮次。':'原工作未能重新打开，没有打开其他提案。');}:undefined;
   const line=fixedArchive&&origin.route==='chat'?origin.chatWork:undefined;
   const returnTo=line?.result?{...line,result:{...line.result,navigation:navigation+1}}:line;
   const exactReturn=()=>current()&&sameWork();
   if(teamModelReturn){
    // Wait for the existing Settings connection cleanup before reopening this exact Work.
    const left=await awaitPickerProjection(exactReturn,()=>{const view=obj(bridge.getState().view);return view.section===null&&view.taskId===null?true:undefined;});
    if(!left||!exactReturn()){failedReturn?.();return;}
   }
   const focusReturnedWork=()=>{
    if(!exactReturn()||openedTaskId.current!==id||acknowledgedWorkVisit.current?.taskId!==id)return;
    if(tasks.some(row=>row.id===id&&attention(row)))loadTaskReview(id);
    const request=reviewRequest.current;
    requestAnimationFrame(()=>{
     if(!exactReturn()||reviewRequest.current!==request||obj(bridge.getState().view).taskId!==id)return;
     const title=taskScroll.current?.closest('.pw-task-drawer')?.querySelector<HTMLElement>('.pw-task-header-copy h2');
     if(title){title.tabIndex=-1;title.focus({preventScroll:true});}
    });
   };
   openTask(id,layer,false,undefined,rereadOriginal?'':undefined,expected,proposal||fixedArchive||teamModelReturn?exactReturn:expected?sameWork:undefined,
    undefined,failedReturn,proposal?returnProposal:teamModelReturn?focusReturnedWork:undefined,returnTo?{returnTo,beforeTaskRequest:reviewRequest.current,navigation:navigation+1}:undefined);
  }
  else if(proposal)returnProposal();
  else if(origin.modelPicker)requestAnimationFrame(()=>{if(current())modelPicker.current?.open('models')});
  else if(!origin.settingsDirectory)requestAnimationFrame(()=>{
   if(!current())return;
   if(origin.chatWork&&restoreChatWorkAt(savedHost?{...origin.chatWork,document:documentEpoch}:origin.chatWork))return;
   if(origin.reviewedSkill)setLocalStatus('已回到同一对话，当前消息窗口未找到原回答。');
   const retained=origin.focus?.isConnected&&!origin.focus.closest('[hidden],[inert]')?origin.focus:null;
   const entry=origin.focusKey?Array.from(workspace.current?.querySelectorAll<HTMLElement>('[data-workspace-entry]')||[]).find(node=>node.dataset.workspaceEntry===origin.focusKey&&!node.closest('[hidden],[inert]')):null;
   const editor=modelInput?workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]:not(:disabled)'):null;
   const target=editor||retained||entry||(source?chatMenuTrigger.current:null)||workspace.current?.querySelector<HTMLElement>('.pw-project-overview h2,.pw-secondary-header h1,.pw-app-header h1');
   if(target){if(!target.matches('button,a,input,textarea,select,[tabindex]'))target.tabIndex=-1;target.focus({preventScroll:true})}
  });
  afterReturn?.();return true;
 };
 const openSetup=async(capability?:SetupCapability,runtimeTaskId?:string,modelMode?:'onboarding'|'center',settingsDirectory?:SettingsDirectoryOrigin,phonePlace?:PhonePlaceReturn,capturedOrigin?:WorkspaceOrigin)=>{
  if(guardTaskLeave(()=>{void openSetup(capability,runtimeTaskId,modelMode,settingsDirectory,phonePlace,capturedOrigin)}))return;
  const origin=capturedOrigin||captureWorkspaceOrigin(),navigation=navigationWindow.current,closingTask=taskOpen;
  if(closingTask&&!await closeTask())return;
  if(navigationWindow.current!==navigation)return;
  if(phonePlace&&!samePhonePlaceDraft(phonePlace))return;
  setupOrigin.current=phonePlace?{...origin,label:'返回原手机目标',focus:null,focusKey:'phone-place',phonePlace,
   drawerChat:{id:phonePlace.conversation,draftKey:phonePlace.composer,documentEpoch:phonePlace.documentEpoch}}:
   settingsDirectory?{...origin,route:settingsDirectory.page,settingsDirectory}:origin;
  setSetupMode(modelMode||'center');setSetupCapability(capability);setSetupView(modelMode?'model':capability||'overview');setSetupRuntimeTask(runtimeTaskId);
  const modelInput=origin.modelInput,proposal=origin.phonePreparation;
  await navigate('setup',undefined,{keepOrigin:true,taskClosed:closingTask,...(proposal?{stillCurrent:()=>!document.hidden&&phonePreparationOwner()===proposal.owner}:modelInput?{stillCurrent:()=>sameModelSetupInput(modelInput,bridge.getState(),bridge.getDocumentEpoch())}:{})});
 };
 const navigateFromSetup=(destination:SetupDestination)=>{const target={connections:'model-settings',background:'background',appearance:'phone:appearance',local:'local'}[destination];void navigate(target,undefined,{keepOrigin:true});setupReturnPending.current=true;};
 const closeSetup=()=>{setupReturnPending.current=false;void restoreWorkspace(setupOrigin.current,true);};
 const returnFirstHomeModelInput=async(origin:WorkspaceOrigin,choice?:AIChoice)=>{
  const input=origin.modelInput,visit=origin.firstHomeModel,documentEpoch=bridge.getDocumentEpoch();
  if(route!=='model-settings'||origin.route!=='butler'||!input||!visit||input.conversation!==''||
    taskOpen||taskOpening||setupReturnPending.current||choice?.accountOnly||choice?.target?.kind==='account'||!can('models.snapshot'))return false;
  const expected={key:input.key,epoch:input.epoch,revision:visit.draftRevision};
  const current=(live:ReturnType<typeof bridge.getState>,arrived=false)=>{
   const draft=live.composer,view=obj(live.view),phone=obj(live.phoneDraft);
   return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&openedTaskId.current===null&&
    navigationWindow.current===visit.navigation+(arrived?1:0)&&(arrived||!setupReturnPending.current&&relatedOrigin.current===origin)&&
    (view.section==='models'||arrived&&view.section===null)&&view.taskId===null&&!live.state.conversationReader&&
    !live.state.filePath&&!live.state.conversationId&&!live.state.chatSubmitting&&!live.state.phoneSubmitting&&
    phone.phone===false&&phone.channelOrigin===false&&draft?.ready===true&&draft.conversationId===''&&
    draft.key===expected.key&&draft.epoch===expected.epoch&&draft.revision===expected.revision;
  };
  if(!current(bridge.getState()))return false;
  const proof=obj(await bridge.command('models.snapshot',{originalHomeInput:expected}).catch(()=>null));
  if(proof.originalHomeInputMatched!==true||proof.key!==expected.key||proof.epoch!==expected.epoch||
     proof.revision!==expected.revision||!current(bridge.getState()))return false;
  // The fresh window only admits this navigation. The original input's epoch is never replaced.
  return await restoreWorkspace(origin,false,false,choice?()=>{
   const live=bridge.getState(),returned=modelSetupInput(live,documentEpoch);
   // The existing model choice is saved. This check did not read the current default.
   if(returned)setModelSetupFeedback({input:returned,choice,modelRevision:live.state.modelSelectionRevision,
    draftRevision:expected.revision,preferenceRevision:live.state.conversationModel?.revision});
  }:undefined,undefined,current)===true;
 };
 const returnFromSecondary=()=>{
  if(route==='profile'){returnFromProfile();return;}
  if(setupReturnPending.current){void navigate('setup',undefined,{keepOrigin:true});return;}
  const before=relatedOrigin.current,teamResult=before?.task?.teamResult;
  if(teamResult&&!teamResult.current()){
   teamResult.retire();
   if(relatedOrigin.current===before)return; // Its confirmed navigation already owns the arrival.
  }
  const origin=relatedOrigin.current;
  if(origin){
   if(returnOriginalArchiveChat(origin))return;
   if(route==='model-settings'&&origin.firstHomeModel?.navigation===navigationWindow.current){
    setLocalError('');
    void returnFirstHomeModelInput(origin).then(restored=>{
     if(!restored&&!document.hidden&&relatedOrigin.current===origin&&origin.firstHomeModel?.navigation===navigationWindow.current)
      setLocalError('原输入尚未核对，未打开另一份草稿。请在原设置页重试返回。');
    });
    return;
   }
   if(!origin.reviewedSkill&&!origin.modelInput&&!origin.projectModelInput&&!origin.savedHostPreparation&&!origin.pairedDevicesProject&&!origin.task?.teamResult)relatedOrigin.current=null;
   void restoreWorkspace(origin);return;
  }
  void navigate('butler');
 };
 // Opening the chooser from background preparation is navigation, not a mode change.
 const phoneSettingsOrigin=setupReturnPending.current?setupOrigin.current:relatedOrigin.current;
 const phoneSettingsPin=state.backgroundSetupOpen===true&&!taskOpen&&!phoneSettingsOrigin?.task&&!phoneSettingsOrigin?.phonePreparation&&can('phone.prepare')&&can('composer.intent')
  ?(phoneSettingsOrigin?.phonePlace?returnToPreparedPhoneDraft(phoneSettingsOrigin.phonePlace):currentPhonePlaceInput()):null;
 const choosePhonePlaceFromSettings=phoneSettingsPin&&samePhonePlaceDraft(phoneSettingsPin)?()=>{
  const pin=phoneSettingsPin,from=navigationWindow.current,origin=phoneSettingsOrigin;
  const stillHere=()=>from===navigationWindow.current&&samePhonePlaceDraft(pin)&&bridge.getState().state.backgroundSetupOpen===true;
  if(!stillHere()){setLocalError('原输入或准备页已变化，没有更改执行位置。');return}
  const destination=origin?.route==='chat'||origin?.route==='butler'?origin.route:pin.conversation?'chat':'butler';
  void navigate(destination,()=>{if(samePhonePlaceDraft(pin))setWorkOptionsOpen(true)},{stillCurrent:stillHere});
 }:undefined;
 // Read only the native facts already published for this unfinished setup visit.
 const nativePhoneSetupState=(visit:BackgroundPhoneDraftVisit)=>{
  const live=bridge.getState(),view=obj(live.view),owner=live.state;
  if(visit.retired||backgroundPhoneDraftVisit.current!==visit||setupOrigin.current!==visit.origin||
     !setupReturnPending.current||navigationWindow.current!==visit.navigation||document.hidden||
     bridge.getDocumentEpoch()!==visit.document||typeof live.revision!=='number'||live.revision<=visit.afterRevision||
     view.taskId!==null||view.section!==null||!ownerWindowAvailable(owner)||!returnToPreparedPhoneDraft(visit.pin))return null;
  const display=obj(obj(owner.features).display),adb=obj(display.adb),generation=adb.generation,setupRevision=display.setupRevision;
  const connection=backgroundConnection(display,owner.activeTaskId,asArray<string>(owner.moduleBusy));
  if(!connection.supported||connection.backend!=='wireless_adb'||display.taskOwned!==false||connection.busy||
     connection.displayBarrier||connection.requiresConnectionCheck||!Number.isSafeInteger(generation)||generation<0||
     !Number.isSafeInteger(setupRevision)||setupRevision<0)return null;
  return {generation,setupRevision,idleFirstSetup:adb.phase==='idle'&&display.configured===false&&display.helperConnected===false&&
   adb.identityPresent===false&&adb.helperAuthenticated===false,authenticated:adb.phase==='ready'&&connection.identityPresent&&
   adb.helperAuthenticated===true&&connection.helperConnected};
 };
 const firstPhoneHelperAuthenticated=(visit:BackgroundPhoneDraftVisit,observation:ReturnType<typeof nativePhoneSetupState>)=>
  !!visit.first&&!!observation?.authenticated&&observation.generation===visit.first.generation+2&&
  observation.setupRevision===visit.first.setupRevision+1;
 useLayoutEffect(()=>{
  const origin=setupOrigin.current,pin=origin.phonePlace,initial=bridge.getState().revision;
  if(route!=='background'||setupCapability!=='phone'||!setupReturnPending.current||pin?.mode!=='background'||
     origin.task||origin.projectId||(origin.route!=='chat'&&origin.route!=='butler')||typeof initial!=='number'||!Number.isSafeInteger(initial))return;
  const visit:BackgroundPhoneDraftVisit={origin,pin,navigation:navigationRevision,document:bridge.getDocumentEpoch(),afterRevision:initial,
   first:null,seenAuthentication:false,retired:false};
  backgroundPhoneDraftVisit.current=visit;
  const stopState=bridge.subscribe(()=>{
   const actual=nativePhoneSetupState(visit);
   // A single built-in system pair and helper authentication. Paired alone adds
   // one generation; normal connect adds one more plus the native setup revision.
   if(!visit.first&&actual?.idleFirstSetup)visit.first={generation:actual.generation,setupRevision:actual.setupRevision};
   if(firstPhoneHelperAuthenticated(visit,actual))visit.seenAuthentication=true;
  });
  const stopDocument=bridge.subscribeDocumentEpoch(()=>{
   const documentEpoch=bridge.getDocumentEpoch(),revision=bridge.getState().revision;
   if(visit.document===documentEpoch)return;
   // Never let a newer general snapshot revive a helper already reported in the previous foreground.
   if(visit.seenAuthentication)visit.retired=true;
   visit.document=documentEpoch;visit.afterRevision=revision??Number.POSITIVE_INFINITY;
  });
  return()=>{stopState();stopDocument();if(backgroundPhoneDraftVisit.current===visit)backgroundPhoneDraftVisit.current=null;};
 },[route,setupCapability,navigationRevision]);
 const connectedOriginalPhoneDraft=(visit:BackgroundPhoneDraftVisit)=>route==='background'&&!taskOpen&&!taskOpening&&
  openedTaskId.current===null&&firstPhoneHelperAuthenticated(visit,nativePhoneSetupState(visit));
 const connectedPhoneVisit=backgroundPhoneDraftVisit.current;
 const returnConnectedPhoneDraft=connectedPhoneVisit&&connectedOriginalPhoneDraft(connectedPhoneVisit)?()=>{
  if(!connectedOriginalPhoneDraft(connectedPhoneVisit)){
   setLocalError('暂不能核对原连接或目标，没有切换草稿。可返回准备中心重新核对。');return;
  }
  // This only returns and restores focus. The owner must review the goal again.
  void restoreWorkspace(connectedPhoneVisit.origin,true);
 }:undefined;
 const openModelSetup=async()=>{
  if(openingModelSetup.current||!can('setup.read'))return;
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch(),conversation=state.conversationId;
  const original=bridge.getState(),selection=original.state;
  const input=isChatSurface&&intent==='chat'&&!taskOpen?modelSetupInput(original,documentEpoch):null;
  const captured=captureWorkspaceOrigin();
  const origin:WorkspaceOrigin=input?{...captured,label:'返回原输入',modelInput:input}:captured;
  openingModelSetup.current=true;setModelSetupOpening(true);
  try{
   const current=await bridge.command('setup.read').then(readSetup,()=>null).catch(()=>null);
   const live=bridge.getState();
   if(document.hidden||navigationWindow.current!==navigation||bridge.getDocumentEpoch()!==documentEpoch||live.state.conversationId!==conversation||
      input&&!sameModelSetupInput(input,live,documentEpoch))return;
   const pending=current?.preferences.status==='new'||current?.preferences.status==='in_progress';
   // An unconfigured default is not an empty service list. The connection workspace reads both owners.
   const stableSelection=selection.modelSelectionBusy===false&&live.state.modelSelectionBusy===false&&Number.isSafeInteger(selection.modelSelectionRevision)&&
    selection.modelSelectionRevision>=0&&selection.modelSelectionRevision===live.state.modelSelectionRevision;
   if(pending&&current?.model.availability==='unconfigured'&&!current.model.configured&&!current.model.testing&&stableSelection&&
    !conversationModelName(live.state)&&live.capabilities.commands.includes('connections.enter')){
    const draft=live.composer,view=obj(live.view),phone=obj(live.phoneDraft);
    const firstHomeModel=input&&route==='butler'&&!input.conversation&&input.key.startsWith('new:')&&
     view.section===null&&view.taskId===null&&!live.state.filePath&&!live.state.chatSubmitting&&!live.state.phoneSubmitting&&
     phone.phone===false&&phone.channelOrigin===false&&draft?.ready===true&&draft.text===chatDraft.currentValue()&&
     Number.isSafeInteger(draft.revision)&&draft.revision>0?{navigation:navigation+1,draftRevision:Number(draft.revision)}:null;
    await openRelatedWorkspace('model-settings',firstHomeModel?{...origin,firstHomeModel}:origin);return;
   }
   await openSetup(undefined,undefined,pending?'onboarding':'center',undefined,undefined,origin);
  }finally{openingModelSetup.current=false;setModelSetupOpening(false);}
 };
 const firstModelOrigin=route==='model-settings'?(setupReturnPending.current?setupOrigin.current:relatedOrigin.current):null;
 const returnOriginalModelInput=firstModelOrigin?.modelInput?async(choice:AIChoice)=>{
  const origin=firstModelOrigin, input=origin.modelInput!;
  if(origin.firstHomeModel&&bridge.getDocumentEpoch()!==input.document)return returnFirstHomeModelInput(origin,choice);
  const navigation=navigationWindow.current;
  const stillOriginal=()=>!document.hidden&&navigationWindow.current===navigation&&
   sameModelSetupInput(input,bridge.getState(),bridge.getDocumentEpoch());
  if(!stillOriginal())return false;
  // Explicit return reads only saved local facts. This is neither another model
  // selection nor a provider check, and never pins a subsequent request.
  const native=choice.accountOnly||!can('models.snapshot')?null:await bridge.command('models.snapshot').catch(()=>null);
  if(!stillOriginal())return false;
  const live=bridge.getState(), revision=obj(native).modelSelectionRevision;
  const observed=Number.isSafeInteger(revision)&&revision>=0;
  return await restoreWorkspace(origin,false,false,()=>setModelSetupFeedback({input,choice,
   matchesDefault:observed?matchesChoiceDefault(choice,native):undefined,modelRevision:observed?revision:live.state.modelSelectionRevision,
   draftRevision:live.composer?.revision,preferenceRevision:live.state.conversationModel?.revision}))===true;
 }:undefined;
 const remoteInboxOpenRevision=Number(state.remoteInbox?.openRevision||0);
 const lastRemoteInboxOpen=useRef(0);
 useEffect(()=>{if(remoteInboxOpenRevision>0&&remoteInboxOpenRevision!==lastRemoteInboxOpen.current){lastRemoteInboxOpen.current=remoteInboxOpenRevision;if(route!=='remoteInbox')void openNotifications();}},[remoteInboxOpenRevision,route,openNotifications]);
 const loadTaskReview=useCallback((id:string,approvalId?:string)=>{
  if(!can('tasks.review')||openedTaskId.current!==id)return;invalidateContinuation();const request=++reviewRequest.current;setReviewPending(true);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);
  const window={taskId:id,request,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(bridge.getState().view).resumeRevision||0)};reviewWindow.current=window;
  void act('tasks.review',{id,...(approvalId?{approvalId}:{})},id).then(value=>{if(!currentReviewWindow(window))return;setReview(value);const raw=obj(value.task?.raw);setScopeTools(String(raw.toolLimit||raw.executionScope?.toolLimit||raw.scope?.toolLimit||''));setScopeModels(String(raw.modelCallLimit||raw.executionScope?.modelCallLimit||raw.scope?.modelCallLimit||''));}).catch(()=>{if(reviewRequest.current===request)setReview(null)}).finally(()=>{if(reviewRequest.current===request)setReviewPending(false)});
 },[can,act,currentReviewWindow,invalidateContinuation]);
 const teamReportModeRef=useRef<TeamReportMode|null>(null);
 const [teamReportMode,setTeamReportMode]=useState<TeamReportMode|null>(null);
 const teamReportReturnFocus=useRef<TeamReportVisit|null>(null);
 const putTeamReportMode=useCallback((mode:TeamReportMode|null)=>{teamReportModeRef.current=mode;setTeamReportMode(mode)},[]);
 const originalTeamReportWork=useCallback((visit:TeamReportVisit)=>{
  const live=bridge.getState(),view=obj(live.view),rows=asArray<Task>(live.state.tasks).filter(row=>row.id===visit.parent.taskId),row=rows.length===1?rows[0]:undefined;
  return currentReviewWindow(visit.window)&&navigationWindow.current===visit.navigation&&view.section===null&&!live.state.conversationReader&&
   row?.raw?.actorId==='owner'&&row.raw.spaceId==='private'&&row.raw.contextAvailable===true&&!row.raw.contextWithdrawn&&!row.raw.sourceCurrentReadRequired&&
   row.raw.runId===visit.parent.runId&&row.raw.coordinatorEpoch===visit.parent.epoch?row:undefined;
 },[currentReviewWindow]);
 const teamReportInOverview=taskLayer==='overview'&&taskOpen&&!taskOpening&&teamReportMode&&originalTeamReportWork(teamReportMode.visit)?teamReportMode:null;
 useEffect(()=>{
  if(teamReportMode&&teamReportModeRef.current===teamReportMode&&!teamReportInOverview)putTeamReportMode(null);
 },[teamReportMode,teamReportInOverview,putTeamReportMode]);
 const leaveTeamReport=useCallback(()=>{
  const mode=teamReportModeRef.current;
  if(!mode||taskLayer!=='overview'||!originalTeamReportWork(mode.visit))return false;
  if(mode.pending)return true;
  const {visit}=mode;
  putTeamReportMode(null);
  // A sent verdict needs its actual receipt, not a re-armed generic button after an unknown result.
  setReview(null);
  if(!mode.submitted)loadTaskReview(visit.parent.taskId);
  if(!mode.submitted)teamReportReturnFocus.current=visit;
  return true;
 },[taskLayer,originalTeamReportWork,putTeamReportMode,loadTaskReview]);
 useLayoutEffect(()=>{
  const visit=teamReportReturnFocus.current;
  if(!visit||reviewPending)return;
  teamReportReturnFocus.current=null;
  const view=obj(bridge.getState().view);
  if(document.hidden||bridge.getDocumentEpoch()!==visit.window.documentEpoch||navigationWindow.current!==visit.navigation||
     view.taskId!==visit.parent.taskId||view.resumeRevision!==visit.window.resumeRevision||taskLayer!=='overview'||
     document.activeElement!==document.body&&!taskScroll.current?.contains(document.activeElement))return;
  const trigger=visit.trigger.isConnected?visit.trigger:taskScroll.current?.querySelector<HTMLButtonElement>('[data-team-evidence-entry="'+visit.choice.teamRunId+'"]');
  trigger?.focus({preventScroll:true});trigger?.scrollIntoView({block:'nearest'});
 },[review,reviewPending,taskLayer]);
 const selectTeamReport=(task:Task,choice:TeamReportChoice,trigger:HTMLButtonElement)=>{
  const parent=teamReportParent(review?.task),live=teamReportParent(task),window=reviewWindow.current;
  if(!review||!window||!parent||!live||live.taskId!==parent.taskId||live.runId!==parent.runId||live.epoch!==parent.epoch||live.digest!==parent.digest||
     !taskOpen||taskLayer!=='overview'||taskOpening||reviewPending||reviewStale||reviewSubmitted||teamReportModeRef.current||!can('tasks.review'))return;
  const visit={parent,choice,window,trigger,navigation:navigationWindow.current};
  const actual=originalTeamReportWork(visit);
  if(!actual||!sameTeamReportChoice(linkedTeamReportChoice(actual),choice))return;
  putTeamReportMode({visit});
  const {teamRunId,generation,revision}=choice;
  void bridge.command('tasks.review',{id:parent.taskId,teamReport:{sourceReviewHandle:review.reviewHandle,teamRunId,generation,revision}}).then(value=>{
   if(teamReportModeRef.current?.visit!==visit||!originalTeamReportWork(visit))return;
   const reading=teamReportRead(value,parent,choice);
   putTeamReportMode({visit,reading});
  }).catch(error=>{
   if(teamReportModeRef.current?.visit===visit&&originalTeamReportWork(visit))
    putTeamReportMode({visit,error:error instanceof Error?error.message:'没有读取这份原汇总，没有登记决定。'});
  });
 };
 const decideTeamReport=(mode:TeamReportMode,completed:boolean)=>{
  const {visit,reading}=mode, row=originalTeamReportWork(visit),now=teamReportParent(row);
  if(!reading||mode.pending||mode.submitted||!now||now.digest!==visit.parent.digest||teamReportModeRef.current!==mode||!can('tasks.confirm')||
     reviewNote.length>8000||!sameTeamReportChoice(linkedTeamReportChoice(row),visit.choice))return;
  if(performance.now()>=reading.expires){putTeamReportMode({...mode,error:'审阅已过期，返回原工作后请明确重读这份汇总。未登记决定。'});return}
  const submitted={...mode,submitted:true,pending:true};
  putTeamReportMode(submitted);
  void bridge.command('tasks.confirm',{reviewHandle:reading.handle,usingTeamReport:true,completed,feedback:reviewNote}).then(value=>{
   if(teamReportModeRef.current!==submitted||!originalTeamReportWork(visit))return;
   if(!teamReportDecisionSaved(value,reading,completed))throw Error('未核对到原工作本次决定的保存结果。');
   putTeamReportMode({...submitted,pending:false,done:completed});
  }).catch(error=>{
   if(teamReportModeRef.current===submitted&&originalTeamReportWork(visit))
    putTeamReportMode({...submitted,pending:false,error:(error instanceof Error?error.message:'本次决定尚未确认。')+' 请先核对原工作，不要重复提交。'});
  });
 };
 const openTask=useCallback((id:string,entry:TaskLayer|'review'='overview',nativeWindowAlreadyOpen=false,historyOrigin?:ConversationHistoryTaskOrigin,searchQuery?:string,expected?:{runId:string;epoch:number;draftId?:string},stillCurrent?:()=>boolean,originalApprovalId?:string,failed?:(reason?:unknown)=>void,onOpened?:()=>void,chatWorkOpen?:ChatWorkTaskOpen):void=>{
  const originalChatVisit=()=>!chatWorkOpen?.returnTo||reviewRequest.current===chatWorkOpen.beforeTaskRequest&&navigationWindow.current===chatWorkOpen.navigation;
  if(!originalChatVisit())return;
  const noticeOrigin=phoneNoticeRef.current;
  const dropNotice=noticeOrigin&&(noticeOrigin.taskId!==id||originalPhoneNotice(bridge.getState().state?.originalPhoneNotification)?.token!==noticeOrigin.token||
    !!expected&&(expected.runId!==noticeOrigin.runId||expected.epoch!==noticeOrigin.epoch));
  if(noticeOrigin&&!dropNotice&&!expected){expected={runId:noticeOrigin.runId,epoch:noticeOrigin.epoch};nativeWindowAlreadyOpen=false}
  const projectSource=route==='projects'&&projectTaskReturn.current?.taskId===id?projectTaskReturn.current:null;
  if(projectSource&&!nativeWindowAlreadyOpen)searchQuery??=projectSource.query||'';
  if(projectSource?.sourcePackageRun)expected=projectSource.sourcePackageRun;
  else if(projectSource?.expected){
   const original=projectSource.expected;
   if(expected&&(expected.runId!==original.runId||expected.epoch!==original.epoch))return;
   expected={...expected,...original};
  }
  if(stillCurrent){if(!stillCurrent())return}else searchOpenRequest.current++;
  if(!nativeWindowAlreadyOpen&&!expected&&taskOpen&&taskId===id&&taskReconciliationOrigin.current?.openedId===id)expected=taskReconciliationOrigin.current.expected;
  if(guardTaskLeave(()=>openTask(id,entry,nativeWindowAlreadyOpen,historyOrigin,searchQuery,expected,stillCurrent,originalApprovalId,failed,onOpened,chatWorkOpen)))return;
  const commit=async()=>{
  if(preparationBeforeLeave.current&&!await leaveInlinePreparation())return;
  if(!originalChatVisit()||stillCurrent&&!stillCurrent())return;
  if(dropNotice&&phoneNoticeRef.current===noticeOrigin)releasePhoneNotice();
  // The task ID survives drawer closure. Only a confirmed current window
  // can be the origin of an in-drawer reconciliation, not the last visited row.
  const sourceView=obj(bridge.getState().view);
  if(expected&&!nativeWindowAlreadyOpen&&taskOpen&&taskId&&taskId!==id&&!document.hidden&&
    openedTaskId.current===taskId&&sourceView.taskId===taskId&&Number(sourceView.resumeRevision||0)===openedTaskResumeRevision.current)
   taskReconciliationOrigin.current={taskId,layer:taskLayer,openedId:id,expected,history:taskHistoryOrigin.current?.taskId===taskId?taskHistoryOrigin.current.origin:undefined};
  else if(!taskOpen||taskId!==id||nativeWindowAlreadyOpen||taskReconciliationOrigin.current?.openedId!==id)taskReconciliationOrigin.current=null;
  const origin=historyOrigin||(searchQuery===undefined&&!nativeWindowAlreadyOpen&&taskHistoryOrigin.current?.taskId===id?taskHistoryOrigin.current.origin:undefined);
  taskHistoryOrigin.current=origin?{taskId:id,origin}:null;
  invalidateContinuation();
  acknowledgedWorkVisit.current=null;workFileVisit.current=null;
  setFocusedOriginalDraft(expected?.draftId?{taskId:id,runId:expected.runId,epoch:expected.epoch,draftId:expected.draftId}:null);
  setTaskToolsPlace(null);
  rememberTaskPosition();const request=++reviewRequest.current;const readReview=entry!=='homeReport'&&(entry==='review'||entry==='scope'||tasks.some(task=>task.id===id&&attention(task)));openedTaskId.current=null;setReviewPending(false);setTaskOpening(true);setWorkOpen(false);setCommandOpen(false);setTaskId(id);setTaskLayer(entry==='review'?'overview':entry);setTaskOpen(true);setReview(null);setReviewNote('');setReviewFeedbackOpen(false);setReviewSubmitted(false);setReviewReceipt('');setQuestionPending(false);
  const window={taskId:id,request,documentEpoch:bridge.getDocumentEpoch(),resumeRevision:Number(obj(bridge.getState().view).resumeRevision||0)};reviewWindow.current=window;
  // Open the original host window before obtaining its single-use review handle.
  // Internal task opens do not emit another external-navigation request.
  let acknowledged=false;
  const openOriginal=async()=>{
   if(nativeWindowAlreadyOpen)return true;
   const nativeTask=String(obj(bridge.getState().view).taskId||'');
   if(origin&&nativeTask&&nativeTask!==id)await act('tasks.close',{},id);
   if(stillCurrent&&!stillCurrent()||reviewRequest.current!==request||bridge.getDocumentEpoch()!==window.documentEpoch||Number(obj(bridge.getState().view).resumeRevision||0)!==window.resumeRevision||document.hidden)return false;
   const opened=await act('tasks.open',{id,requestNavigation:false,...origin,...(searchQuery===undefined?{}:{searchQuery}),...(projectSource&&!projectSource.sourcePackageRun?{expectedProjectId:projectSource.projectId,...(projectSource.checkoutId?{expectedCheckoutId:projectSource.checkoutId}:{})}:{}),...(expected?{expectedRunId:expected.runId,expectedEpoch:expected.epoch}:{})},id);
   acknowledged=opened?.taskId===id&&opened.readOnly===true&&opened.executionAuthorized===false;return true;
  };
  const failedOpen=(reason?:unknown)=>{if(reviewRequest.current!==request)return;setTaskOpening(false);
   if(failed){if(obj(bridge.getState().view).taskId===id&&can('tasks.close'))void bridge.command('tasks.close').catch(()=>{});
     taskReconciliationOrigin.current=null;
     setTaskOpen(false);setFocusedOriginalDraft(null);failed(reason);}
  };
  void openOriginal().then(opened=>{if(reviewRequest.current!==request)return;if(!opened||stillCurrent&&!stillCurrent()){failedOpen();return}
   if(acknowledged){
    const row=chatWorkOpen?.row;
    acknowledgedWorkVisit.current=row&&chatWorkOpen?.beforeTaskRequest===request-1&&row.navigation===navigationWindow.current&&
     row.documentEpoch===window.documentEpoch&&row.resumeRevision===window.resumeRevision
     ?{...window,chatRow:row}:window;
   }
   openedTaskId.current=id;openedTaskResumeRevision.current=window.resumeRevision;setTaskOpening(false);
   if(chatWorkOpen?.returnTo&&(!chatWorkOpen.returnTo.phoneDelivery||
      acknowledged&&chatWorkLocation(chatWorkOpen.returnTo,undefined,true)))
    chatWorkReturn.current={...chatWorkOpen.returnTo,taskId:id,navigation:chatWorkOpen.navigation,resumeRevision:window.resumeRevision};
   else if(!taskOpen||searchQuery!==undefined)chatWorkReturn.current=null;
   if(onOpened)onOpened();else if(readReview)loadTaskReview(id,originalApprovalId)}).catch(failedOpen);
  };
  if(workSupplementBeforeLeave.current)void workSupplementBeforeLeave.current.flush().then(saved=>{if(saved)void commit()});else void commit();
 },[act,loadTaskReview,tasks,taskId,taskOpen,taskLayer,route,rememberTaskPosition,invalidateContinuation,leaveInlinePreparation,releasePhoneNotice,can,chatWorkLocation]);
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
 const backFromTask=useCallback(()=>{
  if(taskMenuOpen){setTaskMenuOpen(false);return}
  if(deviceScopeBack.current?.()||capabilityBack.current?.()||livePreviewBack.current?.()||taskTransientBack.current?.()||closeCurrentWorkSupplement()||leaveTeamReport())return;
  if(taskLayer==='scope'){showTaskLayer('goal');return}
  const origin=taskReconciliationOrigin.current;
  if(origin?.openedId===taskId){openTask(origin.taskId,origin.layer,false,origin.history);return}
  let returnToSource=false;
  void closeTask(true,()=>{
   const visit=acknowledgedWorkVisit.current;
   // An acknowledged Work opened from this conversation is a child of its
   // actual row, not of the search that brought the owner to this conversation.
   const returnToRow=route==='chat'&&visit?.taskId===taskId&&openedTaskId.current===taskId&&
    !!visit.chatRow&&currentChatWorkRow(visit.chatRow,'work');
   if(returnToRow)visit.chatReturnRequest=reviewRequest.current+1;
   if(!searchReturn)return;
   const source=chatWorkReturn.current,live=bridge.getState(),view=obj(live.view);
   const project=projectTaskReturn.current;
   // Search is a parent of the chat, not of a work drawer opened from its message.
   returnToSource=returnToRow||route==='projects'&&!!project?.focusShelf&&project.taskId===taskId&&project.projectId===live.state.projectId&&
    openedTaskId.current===taskId&&view.taskId===taskId||route==='chat'&&!!source&&source.taskId===taskId&&openedTaskId.current===taskId&&view.taskId===taskId&&
    source.navigation===navigationWindow.current&&source.resumeRevision===Number(view.resumeRevision||0)&&
    !live.state.conversationReader&&!!chatWorkLocation(source,undefined,true);
  }).then(closed=>{if(closed&&searchReturn&&!returnToSource)showWorkSearch()});
 },[taskMenuOpen,taskId,taskLayer,showTaskLayer,openTask,closeTask,closeCurrentWorkSupplement,leaveTeamReport,searchReturn,showWorkSearch,route,chatWorkLocation,currentChatWorkRow]);
 const selectConversation=useCallback((id:string)=>{void navigate('chat',()=>{if(can('conversation.select'))run('conversation.select',{id})});},[can,run,navigate]);
 const chooseConversation=useCallback((target:string|null)=>{
  const action=target===null?'conversation.new':'conversation.select';
  if(!can(action)||draftSwitchPending())return;
  void navigate(target===null?'butler':'chat',()=>{
   if(!can(action)||draftSwitchPending())return;
   const navigation=navigationWindow.current;
   setLocalError('');openDraftSwitch(target,()=>navigationWindow.current===navigation);
  });
 },[can,navigate,draftSwitchPending,openDraftSwitch]);
 const independentReportBusy=useRef(false);
 const startIndependentReportDraft=async(original?:{taskId:string;handle:string})=>{
  if(independentReportBusy.current||reportDraftFlight.current||intent!=='chat'||draftSwitchPending()||
     !can('ownerWork.report.followUp.resume')||original&&!can('ownerWork.report.followUp.prepare'))return false;
  const doc=bridge.getDocumentEpoch(),navigation=navigationWindow.current,request=reviewRequest.current;
  const visible=()=>!document.hidden&&bridge.getDocumentEpoch()===doc&&navigationWindow.current===navigation;
  const report=original?homeReport.current:null;
  const reportPresented=()=>visible()&&!!original&&!!report&&homeReport.current===report&&
    reviewRequest.current===request&&openedTaskId.current===original.taskId&&
    !!taskScroll.current?.closest('[data-task-layer="homeReport"]');
  const reportVisible=()=>reportPresented()&&!!report&&currentHomeReport(report)&&
    obj(bridge.getState().view).taskId===original?.taskId&&obj(bridge.getState().view).section===null;
  if(original&&!reportVisible())return false;
  independentReportBusy.current=true;let handoff="";
  try {
   if(original){
    const ack=await bridge.command('ownerWork.report.followUp.prepare',{handle:original.handle});
    if(obj(ack).prepared!==true||typeof obj(ack).handoff!=='string')return false;
    handoff=String(obj(ack).handoff);
    if(!reportVisible()||!await closeTask(false,undefined,{handoff,current:reportPresented})||!visible())return false;
   }
   if(!await navigate('butler',undefined,{taskClosed:!!original,stillCurrent:visible}))return false;
   const atDestination=()=>!document.hidden&&bridge.getDocumentEpoch()===doc&&navigationWindow.current===navigation+1;
   const arrived=await awaitPickerProjection(atDestination,()=>{
    const view=obj(bridge.getState().view);
    return view.section===null&&view.taskId===null?true:undefined;
   });
   if(!arrived||!atDestination()||reportDraftFlight.current)return false;
   const previous=bridge.getState().composer;
   if(previous?.ready!==true||typeof previous.key!=='string'||!Number.isSafeInteger(previous.epoch))return false;
   const editor={key:String(previous.key),epoch:Number(previous.epoch)};
   reportDraftFlight.current=editor;setReportDraftSwitching(true);
   try{
    const opened=await bridge.command('ownerWork.report.followUp.resume',handoff?{handoff}:{});
    if(!atDestination()||obj(opened).opened!==true)return false;
    if(obj(opened).changed===true&&!await awaitPickerProjection(atDestination,()=>{
     const active=bridge.getState().composer;
     return active?.ready===true&&active.epoch!==editor.epoch&&active.key!==editor.key?true:undefined;
    }))return false;
    if(!atDestination())return false;
    setLocalError('');
    return true;
   }finally{
    if(reportDraftFlight.current===editor){reportDraftFlight.current=null;setReportDraftSwitching(false)}
   }
  }catch(error){
   if(!document.hidden&&bridge.getDocumentEpoch()===doc&&navigationWindow.current<=navigation+1)
     setLocalError(handoff?'追问已保存，可从导航继续。没有发送。':error instanceof Error?error.message:String(error));
   return false;
  }finally{independentReportBusy.current=false}
 };
 const returnToOriginalTaskConversation=async(taskId:string,conversationId:string,work?:Pick<WorkIdentity,'runId'|'epoch'>,fromTeam=false,
  stageFile?:((inEditor:()=>boolean)=>Promise<boolean>)|{chooseAttachment:(inEditor:()=>boolean)=>Promise<void>},fileReader?:HistoryResultOrigin,stillSource?:()=>boolean)=>{
  const associate=!!work,justRead=!associate&&!stageFile;
  if(!conversationId||returningConversation||!can('conversation.select')||
     (associate||stageFile)&&bridge.getState().state.chatSubmitting)return;
  const request=++conversationReturnRequest.current,documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const keepChat=justRead&&!fromTeam&&route==='chat'&&bridge.getState().state.conversationId===conversationId;
  let destination=navigation;
  setReturningConversation(taskId);
  // Closing a task over a secondary workspace legitimately advances the host's
  // resume revision. Guard navigation by this request/document and visibility;
  // a background transition cancels the request above, rather than blocking
  // the host's normal task-close restoration.
  const current=()=>request===conversationReturnRequest.current&&documentEpoch===bridge.getDocumentEpoch()&&!document.hidden&&stillSource?.()!==false;
  const atDestination=()=>current()&&navigationWindow.current===destination;
  try{
   if(!fromTeam){
    if(taskOpen&&!await closeTask()||!current()||navigationWindow.current!==navigation)return;
    // The drawer already covers this exact reading surface. Closing it retains
    // the message focus and any parent search, without a new visit.
    if(!keepChat){
     if(!await navigate('chat',undefined,{taskClosed:true,...(stillSource?{stillCurrent:()=>current()&&navigationWindow.current===navigation}:{})}))return;
     destination=navigation+1;
    }
   }
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
   if(fileReader&&(reader?.window!==fileReader.window||reader?.conversation!==fileReader.conversationId))return;
   if(reader){
    if(!associate&&!stageFile&&reader.conversation===conversationId)return;
    if(bridge.getState().state.chatSubmitting)throw new Error('另一份历史阅读窗口已打开，未关闭它或改读其他回执。');
    if(!can('conversation.reader.close'))throw new Error('请先关闭历史阅读窗口，再回到这件事的对话。');
    await act('conversation.reader.close',{window:reader.window,conversationId:reader.conversation});
    if(!await waitForSnapshot(()=>!bridge.getState().state.conversationReader,'历史阅读窗口尚未关闭，请关闭后再回到所属对话。'))return;
   }
   if(!atDestination())return;
   const before=bridge.getState().state;
   const selecting=before.conversationId!==conversationId;
   if(fileReader&&selecting)return;
   const earlierRead=stageFile&&selecting?obj(obj(before.moduleResultInputs)['assistant.get'])._requestId:undefined;
   if(before.chatSubmitting&&(associate||stageFile))throw new Error('当前消息仍在提交，请稍后回到这件事的对话。');
   if(selecting)await act('conversation.select',{id:conversationId});
   // Native may open a read-only history for an older conversation. That is
   // already our successful destination, not a failed editor selection.
   const arrived=()=>{
    const latest=bridge.getState(),state=latest.state,reader=state.conversationReader;
    if(reader)return reader.conversation===conversationId?'history':null;
    if((fromTeam||stageFile)&&(obj(latest.view).section||obj(latest.view).taskId||obj(latest.composer).conversationId!==conversationId))return null;
    if(stageFile&&(latest.composer?.ready!==true||state.attachmentWorkspace?.open||!fileReader&&!fileDraftConversationLoaded(state,conversationId,earlierRead)))return null;
    return (justRead||!state.chatSubmitting)&&state.conversationId===conversationId?'editor':null;
   };
   if(!await waitForSnapshot(()=>arrived()!==null,'尚未确认已回到所属对话，请从对话列表重新打开。'))return;
   if(!atDestination())return;
   if(arrived()==='history'){
    if(associate) setLocalStatus('已打开原对话的历史。当前是只读历史，尚未关联到新输入。');
    if(stageFile) setLocalStatus('已打开原对话的只读历史。没有加入文件，也未发送。');
    return;
   }
   const inEditor=()=>atDestination()&&arrived()==='editor';
   if(justRead){
    if(!keepChat)requestAnimationFrame(()=>{
     const node=scroll.current;
     if(inEditor()&&node?.dataset.conversationId===conversationId)node.focus({preventScroll:true});
    });
    return;
   }
   if(inEditor()){
    if(fromTeam&&bridge.getState().state.chatBusy){
      setLocalStatus('已回到原对话。当前消息还在处理，这次成果没有关联到输入。');return;
    }
    if(typeof stageFile==='object'){
     await stageFile.chooseAttachment(inEditor);
     return;
    }
    if(!await changeIntent('chat')||!inEditor())return;
    if(stageFile){
     if(!await stageFile(inEditor)||!inEditor())return;
     setLocalStatus('原话题草稿中已关联文件位置，没有改动附件，也未发送。');
    }
    if(work){
      const draftCurrent=()=>inEditor()&&(!fromTeam||!bridge.getState().state.chatBusy);
      // The draft keeps one reference per Work. Do not retarget its unsent text to a different run.
      if(asArray<Json>(obj(bridge.getState().composer).objectRefs).some(ref=>
       ref.kind==='work'&&ref.id===taskId&&(ref.runId!==work.runId||ref.epoch!==work.epoch))){
       setLocalError(fromTeam?'这份草稿已关联这件工作的另一轮，本次没有替换。':
        '这份草稿已关联这件工作的另一轮，文字和关联都保留。要改用本轮，请先在输入框上方的「关于」中移除原关联，再从本轮追问。');return;
      }
      if(!await stageButlerObject({kind:'work',id:taskId,runId:work.runId,epoch:work.epoch},draftCurrent))return;
      if(draftCurrent())setLocalStatus(fromTeam?'已关联原工作。可以写下下一件事，尚未发送。':'已关联这次工作。追问写在原对话草稿里，尚未发送。');
    }
    requestAnimationFrame(()=>{if(current()&&navigationWindow.current===destination&&bridge.getState().state.conversationId===conversationId&&!bridge.getState().state.conversationReader)workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea')?.focus({preventScroll:true})});
   }
  }catch(error){if(atDestination())setLocalError(error instanceof Error?error.message:String(error))}
  finally{if(conversationReturnRequest.current===request)setReturningConversation('')}
 };
 const returnToTaskConversation=async(task:Task,associate=false)=>{
  const conversation=associate?privateTaskConversationId(task):originatingRequestConversation(task);
  if(!conversation)return;
  const work=associate?{runId:task.raw?.runId,epoch:task.raw?.coordinatorEpoch}:undefined;
  await returnToOriginalTaskConversation(task.id,conversation,work);
 };
 const savedMemoryWorkReturn=(pin:SavedMemoryConversationPin):{open?:()=>void}|undefined=>{
  if(route!=='tasks'||!taskOpen||taskOpening||!memoryDraftTarget||pin.taskId!==memoryDraftTarget.taskId||
     pin.runId!==memoryDraftTarget.runId||pin.epoch!==memoryDraftTarget.epoch||
     pin.operationId!==memoryDraftTarget.operationId||pin.proposalDigest!==memoryDraftTarget.proposalDigest)return;
  const shown=bridge.getState(),view=obj(shown.view),origin=shown.state.conversationId;
  if(origin===pin.conversationId||(origin!==null&&typeof origin!=='string')||view.taskId!==pin.taskId||
     openedTaskId.current!==pin.taskId||view.section!==null||!Number.isSafeInteger(view.resumeRevision))return;
  const source=(snapshot:typeof shown)=>{
   const matches=asArray<Task>(snapshot.state.tasks).filter(task=>task.id===pin.taskId);
   const task=matches.length===1?matches[0]:undefined,raw=task?.raw;
   return raw?.kind==='assistant'&&raw.conversationId===pin.conversationId&&privateTaskConversationId(task)===pin.conversationId&&
    raw.contextAvailable!==false&&raw.contextWithdrawn!==true&&raw.sourceCurrentReadRequired!==true&&raw.taskProjection!=='source_unavailable'&&
    raw.metadataLimited!==true&&raw.taskCoreReadLimited!==true&&raw.origin==null&&raw.teamRunId==null&&
    !raw.developmentContext&&!raw.ownerWorkPreparation&&!raw.ownerPhoneControl&&!raw.ownerDeviceWork&&
    typeof raw.runId==='string'&&raw.runId&&Number.isSafeInteger(raw.coordinatorEpoch)&&raw.coordinatorEpoch>0
    ? {runId:raw.runId,epoch:raw.coordinatorEpoch}:undefined;
  };
  const owner=source(shown);if(!owner)return;
  const documentEpoch=bridge.getDocumentEpoch(),request=reviewRequest.current,navigation=navigationWindow.current;
  const stillSource=()=>{
   const live=bridge.getState(),native=obj(live.view),current=source(live);
   const closing=reviewRequest.current===request+1&&openedTaskId.current===null;
   return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&native.resumeRevision===view.resumeRevision&&native.section===null&&
    current?.runId===owner.runId&&current.epoch===owner.epoch&&
    !live.state.conversationReader&&!live.state.filePath&&!live.state.attachmentWorkspace?.open&&!live.state.chatSubmitting&&
    live.capabilities.commands.includes('tasks.close')&&live.capabilities.commands.includes('conversation.select')&&
    (closing?(native.taskId===null||native.taskId===pin.taskId)&&(live.state.conversationId===origin||
      native.taskId===null&&live.state.conversationId===pin.conversationId):
      reviewRequest.current===request&&openedTaskId.current===pin.taskId&&native.taskId===pin.taskId&&live.state.conversationId===origin);
  };
  // Leave active input in place. The ordinary drawer guard still owns policy edits.
  const free=()=>!returningConversation&&intent==='chat'&&!draftSwitchPending()&&!workSupplementBeforeLeave.current&&
    !projectDraftBeforeLeave.current&&!preparationBeforeLeave.current;
  if(!stillSource()||!free())return {};
  return {open:()=>{
   if(navigationWindow.current!==navigation||reviewRequest.current!==request||!stillSource()||!free())return;
   setMemoryDraftTarget(null);
   void returnToOriginalTaskConversation(pin.taskId,pin.conversationId,undefined,false,undefined,undefined,stillSource);
  }};
 };
 const returnFromFixedDraft=(task:Task,draft:FixedProjectDraft)=>{
  const conversation=originatingRequestConversation(task),request=reviewRequest.current;
  const stillSource=()=>{
   const live=bridge.getState(),reader=live.state.conversationReader,nativeTask=obj(live.view).taskId;
   const rows=asArray<Task>(live.state.tasks).filter(item=>item.id===draft.taskId);
   return !!conversation&&(reviewRequest.current===request||reviewRequest.current===request+1)&&
    (!nativeTask||nativeTask===task.id)&&(!reader||reader.conversation===conversation)&&rows.length===1&&
    (nativeTask?matchesFixedProjectDraft(rows[0],draft):sameFixedProjectDraftIdentity(rows[0],draft))&&originatingRequestConversation(rows[0])===conversation;
  };
  const live=bridge.getState(),view=obj(live.view);
  if(!conversation||document.hidden||!can('tasks.close')||!can('conversation.select')||openedTaskId.current!==task.id||
     view.taskId!==task.id||view.section!=null||live.state.conversationReader||!stillSource()){
   setLocalError('暂不能核对原对话，没有打开其他对话。请从原工作重新核对。');return;
  }
  // Only navigation: don't stage the project, old task, or draft into the composer.
  void returnToOriginalTaskConversation(task.id,conversation,undefined,false,undefined,undefined,stillSource);
 };
 const returnToContinuationConversation=(task:Task,shown:ContinuationSubmission)=>{
  const original=shown.original,scope=task.raw?.developmentContext;
  const stillOriginal=()=>{
   const live=asArray<Task>(bridge.getState().state.tasks).find(row=>row.id===original.taskId),raw=live?.raw;
   return task.id===original.taskId&&privateProjectTask(live)&&raw?.id===task.id&&
    raw.conversationId===original.conversationId&&raw.developmentContext?.projectId===scope?.projectId&&
    raw.developmentContext?.checkoutId===scope?.checkoutId&&raw.contextAvailable!==false&&
    raw.contextWithdrawn!==true&&raw.sourceCurrentReadRequired!==true&&raw.taskProjection!=='source_unavailable';
  };
  if(openedTaskId.current!==task.id||obj(bridge.getState().view).taskId!==task.id||!stillOriginal()){
   setLocalError('原工作的对话暂不可核对，没有打开其他对话。');return;
  }
  void returnToOriginalTaskConversation(task.id,original.conversationId,undefined,false,undefined,undefined,stillOriginal);
 };
 const readOriginalWorkRequest=async(task:Task,trigger:HTMLButtonElement)=>{
  const work=ownerWorkIdentity(task);
  if(!work||work.epoch<1||!can('conversation.reader.open')||!can('conversation.reader.close')||taskOpening||openingWorkRequest||
    document.hidden||openedTaskId.current!==task.id||bridge.getState().state.conversationReader)return;
  const visit:WorkRequestVisit={work,document:bridge.getDocumentEpoch(),navigation:navigationWindow.current,trigger};
  const current=()=>workRequestVisit.current===visit&&!document.hidden&&visit.document===bridge.getDocumentEpoch()&&
    visit.navigation===navigationWindow.current&&openedTaskId.current===task.id&&obj(bridge.getState().view).taskId===task.id;
  workRequestVisit.current=visit;setOpeningWorkRequest(task.id);
  setWorkRequestError(null);
  try{
   const read=await bridge.command('conversation.reader.open',work);
   if(read?.taskId!==task.id||read.readOnly!==true||read.executionAuthorized!==false||!Number.isSafeInteger(read.window)||read.window<1)
    throw new Error('原请求尚不可核对，没有打开其他消息。');
   visit.window=read.window;
  }catch(failure){
   if(current())setWorkRequestError({work,message:failure instanceof Error?failure.message:'原请求尚不可读取。'});
   if(workRequestVisit.current===visit)workRequestVisit.current=null;
  }finally{setOpeningWorkRequest('');}
 };
 const restoreWorkRequestFocus=(windowId:number)=>{
  const origin=workRequestVisit.current;
  if(!origin||origin.window!==undefined&&origin.window!==windowId)return;
  workRequestVisit.current=null;
  requestAnimationFrame(()=>{
   const live=bridge.getState(),work=ownerWorkIdentity(asArray<Task>(live.state.tasks).find(row=>row.id===origin.work.taskId));
   if(!workRequestVisit.current&&!document.hidden&&origin.document===bridge.getDocumentEpoch()&&origin.navigation===navigationWindow.current&&
     openedTaskId.current===origin.work.taskId&&obj(live.view).taskId===origin.work.taskId&&work?.runId===origin.work.runId&&work.epoch===origin.work.epoch&&origin.trigger.isConnected&&
     !origin.trigger.disabled&&!origin.trigger.closest('[inert],[hidden]')&&document.activeElement===document.body)origin.trigger.focus({preventScroll:true});
  });
 };
 const reportHistoryOpening=useRef(false);
 const readReportInConversation=async(task:Task,original?:VerifiedHomeReport)=>{
  const target=original?.target??reportedHomeWork(task);
  if(!target||reportHistoryOpening.current||!can('ownerWork.report.history')||!can('tasks.close'))return;
  const documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const unchanged=()=>!document.hidden&&documentEpoch===bridge.getDocumentEpoch()&&navigationWindow.current===navigation;
  const atTask=()=>unchanged()&&openedTaskId.current===target.taskId&&obj(bridge.getState().view).taskId===target.taskId;
  const atOriginal=()=>!original||homeReport.current===original&&currentHomeReport(original);
  if(!atTask()||!atOriginal()||bridge.getState().state.conversationReader){
   if(original)throw Error('原工作或阅读窗口已变化，没有打开其他对话。');
   return;
  }
  reportHistoryOpening.current=true;setReturningConversation(task.id);
  let opened:{window:number;conversationId:string}|null=null,retained=false;
  try{
   if(original){
    await verifyHomeReport(original.target,original.pin);
    if(!atTask()||!atOriginal())return;
   }
   const page=await bridge.command('ownerWork.report.history',{taskId:target.taskId,runId:target.runId,epoch:target.epoch,reportMessageId:target.reportMessageId});
   if(!page||!Number.isSafeInteger(page.window)||page.window<1||page.conversationId!==target.conversationId||
      page.reportMessageId!==target.reportMessageId||!Number.isSafeInteger(page.reportRevision)||page.reportRevision<1||page.readOnly!==true)
    throw Error('没有在原对话核对到这份汇报。');
   opened={window:page.window,conversationId:page.conversationId};
   if(original&&page.reportRevision!==original.pin.expectedReportRevision)throw Error('汇报版本已变化，没有打开不同版本的消息。');
   if(!atTask()||!atOriginal()||!await closeTask()||!unchanged())return;
   retained=await navigate('chat',undefined,{taskClosed:true,stillCurrent:unchanged});
  }catch(failure){
   if(unchanged()){
    if(original)throw failure;
    setLocalError(failure instanceof Error?failure.message:'没有定位到原汇报。');
   }
  }
  finally{
   if(opened&&!retained&&documentEpoch===bridge.getDocumentEpoch()&&can('conversation.reader.close'))await bridge.command('conversation.reader.close',opened).catch(()=>{});
   reportHistoryOpening.current=false;setReturningConversation('');
  }
 };
 const openTaskProject=async(task:Task)=>{
  const projectId=task.raw?.developmentContext?.projectId;
  if(typeof projectId!=='string'||!projects.some(project=>project.id===projectId)||!can('projects.select'))return;
  const navigation=navigationWindow.current;
  if(await openRelatedWorkspace('projects')&&navigationWindow.current===navigation+1)run('projects.select',{id:projectId});
 };
 const currentViteEditorVisit=(target:ProjectBuildCopy,live:ReturnType<typeof bridge.getState>)=>{
  const desired=target.editorResume,owner=live.state,view=obj(live.view);
  const inProject=route==='projects'&&owner.projectId===target.projectId;
  const supports=(command:string)=>live.capabilities.commands.includes(command);
  if(!desired||!taskOpen||taskOpening||taskLayer!=='overview'||openedTaskId.current!==target.taskId||document.hidden||
     view.taskId!==target.taskId||view.section!==null||view.resumeRevision!==openedTaskResumeRevision.current||
     owner.filePath||owner.conversationReader||owner.attachmentWorkspace?.open||owner.localSourceIntake?.window||
     owner.projectPreview?.localWebWork===true||projectPreview.expanded||projectWorkReviewOpen||
     projectWorkDrafts[target.projectId]?.operation||!inProject&&route!=='tasks'||
     asArray<Project>(owner.projects).filter(project=>project.id===target.projectId).length!==1||
     !canEditImportedCheckout(supports)||!supports('projects.development.inspect')||!supports('tasks.close')||
     owner.projectId!==target.projectId&&!supports('projects.select'))return false;
  const rows=asArray<Task>(owner.tasks).filter(row=>row.id===target.taskId);
  const current=rows.length===1?originalViteEditorCopy(rows[0]):null;
  return !!current?.editorResume&&!!projectBuildEditorResume(desired,current)&&
    current.editorResume.path===desired.path&&current.editorResume.savedHash===desired.savedHash;
 };
 const openOriginalBuildCopy=async(target:ProjectBuildCopy)=>{
  const first=bridge.getState(),nativeTask=obj(first.view),row=asArray<Task>(first.state.tasks).find(item=>item.id===target.taskId);
  const direct=!!target.editorResume;
  const current=direct?(currentViteEditorVisit(target,first)?target:null):originalNodeBuildCopy(row);
  const inProject=route==='projects'&&first.state.projectId===target.projectId;
  const fromOtherPage=direct?route==='tasks':['tasks','chat','butler'].includes(route)&&!!row&&!!originalFirstBuildFailure(row);
  if(openingBuildCopy.current||!taskOpen||taskOpening||openedTaskId.current!==target.taskId)return;
  if(nativeTask.taskId!==target.taskId||nativeTask.section!=null||document.hidden||!inProject&&!fromOtherPage||first.state.filePath||projectPreview.expanded||projectWorkReviewOpen||
     !asArray<Project>(first.state.projects).some(project=>project.id===target.projectId)||!can('projects.development.read')||!can('projects.development.files.open')||
     first.state.projectId!==target.projectId&&!can('projects.select')||
     !current||current.runId!==target.runId||current.epoch!==target.epoch||current.projectId!==target.projectId||
     current.checkoutId!==target.checkoutId||current.sourceDigest!==target.sourceDigest){
   setTaskErrors(previous=>({...previous,[target.taskId]:'原构建或项目窗口已变化，没有打开其他副本。'}));return;
  }
  const navigation=navigationWindow.current,epoch=bridge.getDocumentEpoch(),request=reviewRequest.current;
  const origin=captureNavigationOrigin();
  if(origin.task?.id===target.taskId)origin.task={...origin.task,expected:{runId:target.runId,epoch:target.epoch}};
  let selectingOriginal=false;
  const currentWindow=()=>{
   const next=bridge.getState(),view=obj(next.view);
   return !document.hidden&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===epoch&&
    reviewRequest.current===request+1&&openedTaskId.current===null&&!next.state.filePath&&
    (!direct||view.resumeRevision===nativeTask.resumeRevision&&!next.state.conversationReader&&!next.state.attachmentWorkspace?.open&&
      !next.state.localSourceIntake?.window&&next.state.projectPreview?.localWebWork!==true&&
      (next.state.projectId===first.state.projectId||selectingOriginal&&next.state.projectId===target.projectId));
  };
  const taskClosed=()=>{
   const view=obj(bridge.getState().view);
   return direct?view.taskId===null&&view.section===null:!view.taskId&&view.section==null;
  };
  const projectSelected=()=>taskClosed()&&bridge.getState().state.projectId===target.projectId;
  const waitFor=(ready:()=>boolean)=>new Promise<boolean>(resolve=>{
   let stop=()=>{};
   const timer=window.setTimeout(()=>{stop();resolve(false)},5000);
   const check=()=>{if(!currentWindow()||ready()){window.clearTimeout(timer);stop();resolve(currentWindow());}};
   stop=bridge.subscribe(check);check();
  });
  const openPinned=()=>{
   if(!inProject||direct)setProjectBuildReturn(target);
   setProjectSections(previous=>({...previous,[target.projectId]:'work'}));
   setProjectWorkPresentations(previous=>({...previous,[target.projectId]:{open:true,mode:'default'}}));
  };
  openingBuildCopy.current=true;let opened=false;
  try{
   if(inProject&&!direct)setProjectBuildReturn(target); // Preserve the old generic copy handoff while closing.
   // Exact file return is installed only after the original Work has actually closed.
   if(!await closeTask()||!await waitFor(taskClosed)||direct&&!currentWindow())return;
   if(!projectSelected()){
    selectingOriginal=true;
    await act('projects.select',{id:target.projectId});
   }
   if(!await waitFor(projectSelected)||direct&&!currentWindow()){if(currentWindow())setLocalError('原项目尚未确认，未打开任何替代副本。');return;}
   if(inProject){openPinned();opened=true;}
   else opened=await navigate('projects',()=>{relatedOrigin.current=origin;openPinned()},{keepOrigin:true,taskClosed:true,stillCurrent:()=>currentWindow()&&projectSelected()});
  }catch(failure){if(currentWindow())setLocalError(failure instanceof Error?failure.message:'未能打开原副本。');}
  finally{openingBuildCopy.current=false;if(!opened)handledBuildReturn(target);}
 };
 const previewBlocked=!!(searchOriginal||projectWorkReviewOpen||projectConventionMessage||providedMemoryMessage||conversationMemoryOpen||permissionOptionsOpen||organizationTarget||state.attachmentWorkspace?.open||phoneViewerExpanded||workOpen||commandOpen||taskOpen||projectPickerOpen||fileDisplayOpen||newProjectOpen||newFileOpen||phoneReview||voiceReview||state.chatAttachmentPickerOpen||messageQuoteTarget||messageOriginTarget||sourceSelection||skillDraftTarget||memoryDraftTarget||routeReferenceTarget||state.quickPanelVoice);
 const packageReturn=useRef<{target:PackagePreviewTarget;origin:WorkspaceOrigin;epoch:number;navigation:number;projectFocus:ProjectTaskReturn|null;
  originalChatCurrent?:(live:ReturnType<typeof bridge.getState>)=>boolean}|null>(null);
 const returnOriginalArchiveChat=(origin:WorkspaceOrigin)=>{
  const visit=packageReturn.current,epoch=bridge.getDocumentEpoch();
  if(route!=='projects'||projectPreview.expanded||visit?.origin!==origin||visit.epoch===epoch||!visit.originalChatCurrent)return false;
  const {drawerChat,chatWork}=origin,navigation=visit.navigation,originalChat=visit.originalChatCurrent;
  const current=(live:ReturnType<typeof bridge.getState>,arrived=false)=>!document.hidden&&bridge.getDocumentEpoch()===epoch&&originalChat(live)&&
   (arrived?navigationWindow.current===navigation+1:
    navigationWindow.current===navigation&&packageReturn.current===visit&&relatedOrigin.current===origin);
  if(previewBlocked||projectPreview.exportBusy||!drawerChat||!chatWork||!current(bridge.getState())){
   if(!document.hidden)setLocalError(originalArchiveChatUnavailable);
   return true;
  }
  // A foreground token change retires commands. Only rebind this read-only
  // return to a still-present native chat row. Do not reopen the archive.
  void restoreWorkspace({...origin,drawerChat:{...drawerChat,documentEpoch:epoch},chatWork:{...chatWork,document:epoch}},false,false,()=>{
   if(packageReturn.current===visit)packageReturn.current=null;
   setLocalError('');
  },current);
  return true;
 };
 const returnFromPackage=(id:string,operationId?:string,originalChat=false)=>{
  const saved=packageReturn.current;
  if(saved&&saved.target.taskId===id&&saved.target.operationId===operationId&&saved.epoch===bridge.getDocumentEpoch()&&saved.navigation===navigationWindow.current){
   const original=saved.origin.task;
   if(!originalChat&&operationId&&(!original?.fixedArchive||!sameWorkspaceTask(original,bridge.getState().state.tasks))){
    setLocalError('原工作的执行轮次已变化或暂不可读取。固定包仍保留，未打开其他轮次。');return false;
   }
   packageReturn.current=null;
   projectTaskReturn.current=saved.projectFocus?{...saved.projectFocus,focusOutcome:false,returning:undefined,
    expected:original?.fixedArchive?original.expected:saved.projectFocus.expected}:null;
   void restoreWorkspace(saved.origin,false,true);return true;
  }
  if(originalChat||operationId){
   setLocalError(originalChat?'原话窗口已经变化，没有转到其他任务。':'原工作窗口已变化，没有打开其他执行轮次。固定包仍保留。');return false;
  }
  openTask(id,'review');return true; // Existing live preview, not a fixed package return.
 };
 const openSavedWebBuildCopy=(choices:PackagePreviewChoices)=>{
  const {projectId,checkoutId,sourceDigest,taskId,mode,originalBuild}=choices;
  const live=bridge.getState(),window=obj(live.view);
  if(!originalBuild||mode!=='web_output'||route!=='projects'||document.hidden||previewBlocked||live.state.filePath||window.taskId||window.section!=null||
     live.state.projectId!==projectId||!asArray<Project>(live.state.projects).some(row=>row.id===projectId)||projectBuildReturn||
     projectDraftReturn||projectWorkDrafts[projectId]?.operation||!can('projects.development.read')||!can('projects.development.files.open'))return false;
  const previous=packageReturn.current;
  if(previous?.target.taskId===taskId&&previous.target.operationId===choices.operationId&&previous.epoch===bridge.getDocumentEpoch()&&
     previous.navigation===navigationWindow.current)relatedOrigin.current=previous.origin;
  // This only opens the actual producing checkout's file screen. It never
  // changes the project's selected goal, copy, native review or saved package.
  setProjectBuildReturn({taskId,runId:originalBuild.runId,epoch:originalBuild.epoch,projectId,checkoutId,sourceDigest});
  setProjectSections(value=>({...value,[projectId]:'work'}));
  setProjectWorkPresentations(value=>({...value,[projectId]:{open:true,mode:'default'}}));
  return true;
 };
 const projectPreview=useProjectPreview({value:state.projectPreview,exportValue:state.projectPackageExport,projectId:String(state.projectId||''),resumeRevision:Number(obj(snapshot?.view).resumeRevision||0),active:route==='projects'&&!state.filePath,blocked:previewBlocked,can,onReturned:()=>navigate('chat'),onPackageTask:returnFromPackage,
  openOriginalBuildCopy:!projectDraftReturn&&!projectWorkDrafts[String(state.projectId||'')]?.operation&&
    can('projects.development.read')&&can('projects.development.files.open')?openSavedWebBuildCopy:undefined});
 const packageHandoff=useProjectPackageHandoff({route,navigationRevision,projectId:String(state.projectId||''),blocked:previewBlocked,
  navigation:()=>navigationWindow.current,supported:can,show:projectPreview.showPackage,error:message=>toast.error(message,{id:'project-package-error'}),
  leaveTask:stillCurrent=>navigate('projects',undefined,{keepOrigin:true,stillCurrent})});
 const packageOpening=packageHandoff.opening;
 const openTaskPackage=(target:PackagePreviewTarget)=>{
  if(!taskOpen||taskOpening||taskId!==target.taskId||openedTaskId.current!==target.taskId)return;
  const origin=captureNavigationOrigin(),projectFocus=projectTaskReturn.current,epoch=bridge.getDocumentEpoch();
  const live=bridge.getState(),view=obj(live.view),row=asArray<Task>(live.state.tasks).find(task=>task.id===target.taskId),raw=obj(row?.raw),source=obj(raw.developmentContext);
  const resume=Number(view.resumeRevision||0);
  const request=reviewRequest.current;
  const currentWork=()=>{
   if(openedTaskId.current!==target.taskId||reviewRequest.current!==request||openedTaskResumeRevision.current!==resume||
     selectedTask?.id!==target.taskId||raw.runId!==selectedTask.raw?.runId||raw.coordinatorEpoch!==selectedTask.raw?.coordinatorEpoch||
     typeof raw.runId!=='string'||!raw.runId||!Number.isSafeInteger(raw.coordinatorEpoch)||raw.coordinatorEpoch<1)return false;
   const current=asArray<Task>(bridge.getState().state.tasks).find(task=>task.id===target.taskId),value=obj(current?.raw),scope=obj(value.developmentContext);
   return privateProjectTask(current)&&value.id===target.taskId&&value.runId===raw.runId&&value.coordinatorEpoch===raw.coordinatorEpoch&&
    value.contextAvailable!==false&&value.contextWithdrawn!==true&&value.sourceCurrentReadRequired!==true&&value.taskProjection!=='source_unavailable'&&
    scope.projectId===source.projectId&&scope.checkoutId===source.checkoutId;
  };
  let archiveReturn:WorkspaceOrigin|undefined;
  if(origin.task&&view.taskId===target.taskId&&!view.section&&currentWork()){
   // Back belongs to the work window that opened this archive, not to the
   // producing run of an older archived version or a newer run.
   const line=chatWorkReturn.current;
   const verifiedLine=route==='chat'&&line?.taskId===target.taskId&&line.navigation===navigationWindow.current&&line.resumeRevision===resume&&chatWorkLocation(line,undefined,true)?line:undefined;
   const drawerChat=route==='chat'?{id:String(live.state.conversationId||''),draftKey:String(live.composer?.key||''),documentEpoch:epoch}:origin.drawerChat;
   archiveReturn={...origin,drawerChat,chatWork:verifiedLine,task:{...origin.task,expected:{runId:raw.runId,epoch:raw.coordinatorEpoch},
    fixedArchive:{projectId:source.projectId,checkoutId:source.checkoutId}}};
  }
  packageHandoff.open(target,choices=>{
   // The task's producing checkout is not necessarily the user's return project (e.g. an imported copy).
   packageReturn.current=archiveReturn&&choices.projectId===source.projectId&&choices.checkoutId===source.checkoutId
    ?{target,origin:archiveReturn,projectFocus,epoch,navigation:navigationWindow.current}:null;
  },{currentWork,sourceError:message=>setTaskErrors(previous=>({...previous,[target.taskId]:message}))});
 };
 const previewRefsUnavailable=asArray<Json>(composer.previewRefs).length>0&&composer.previewRefsAvailable!==true;
 const enterPhoneNotification=(note:PhoneNotice)=>{
  const differentRun=()=>{const rows=bridge.getState().state?.tasks;const row=Array.isArray(rows)?rows.find(t=>t?.id===note.taskId):null;
   return !!row&&(row.raw?.runId!==note.runId||row.raw?.coordinatorEpoch!==note.epoch)};
  const stillHere=()=>!document.hidden&&phoneNoticeRef.current?.token===note.token&&originalPhoneNotice(bridge.getState().state?.originalPhoneNotification)?.token===note.token&&!differentRun();
  if(document.hidden||!note.current||originalPhoneNotice(bridge.getState().state?.originalPhoneNotification)?.token!==note.token)return;
  if(differentRun()){setPhoneNoticePanel(note.token);return}
  phoneNoticeRef.current=note;setPhoneNoticePin(note);setPhoneNoticePanel(null);
  const pick=phoneActivityPick.current;
  openTask(note.taskId,note.selection&&pick?.kind===note.selection.kind&&pick.handle===note.selection.handle?pick.entry:'overview',false,undefined,undefined,{runId:note.runId,epoch:note.epoch},stillHere);
 };
 useEffect(()=>{
  const note=phoneNotification;if(!note||document.hidden||phoneNoticeSeen.current===note.token||!can('tasks.open'))return;
  phoneNoticeSeen.current=note.token;
  if(note.selection){const pick=phoneActivityPick.current;
   if(pick?.kind!==note.selection.kind||pick.handle!==note.selection.handle||pick.navigation!==navigationWindow.current||pick.document!==bridge.getDocumentEpoch()||pick.resume!==Number(obj(bridge.getState().view).resumeRevision||0))return}
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
 const reminderNoticeVisit=useRef<ReminderNoticeVisit|undefined>(undefined);
 const [originalReminderChat,setOriginalReminderChat]=useState<OriginalReminderChatVisit|null>(null);
 const [originalReminderRule,setOriginalReminderRule]=useState<OriginalChatReminderRuleVisit>();
 const originalReminderBack=useRef<(()=>boolean)|null>(null);
 const registerOriginalReminderBack=useCallback((handler:(()=>boolean)|null)=>{originalReminderBack.current=handler},[]);
 const originalReminderChatCurrent=(visit:OriginalReminderChatVisit)=>{
  const native=bridge.getState(),view=obj(native.view),owner=native.state,expected=visit.source;
  if(route!=='chat'||taskOpen||taskOpening||document.hidden||visit.documentEpoch!==bridge.getDocumentEpoch()||
     visit.navigation!==navigationWindow.current||view.section!==null||view.taskId!==null||view.resumeRevision!==visit.resume||
     owner.conversationId!==expected.conversationId||owner.conversationReader||owner.filePath||
     owner.attachmentWorkspace?.open||obj(native.phoneDraft).channelOrigin!==false)return false;
  const rows=asArray<Message>(owner.messages).filter(row=>row.id===expected.messageId&&row.sourceRevision===expected.sourceRevision);
  const match=rows.length===1&&messageReplyContext(rows[0]);
  return !!match&&match.originalReminderRecorded===true&&match.taskId===expected.taskId&&match.runId===expected.runId&&match.epoch===expected.epoch&&
    match.replyId===expected.replyId&&match.deliveryId===null&&match.replyToMessageIds.length===1;
 };
 const shownOriginalReminder=originalReminderChat&&originalReminderChatCurrent(originalReminderChat)?originalReminderChat:null;
 useEffect(()=>{if(originalReminderChat&&!shownOriginalReminder)setOriginalReminderChat(null)},[originalReminderChat,shownOriginalReminder]);
 const originalReminderPage=route==='automation'&&originalReminderRule?.navigation===navigationRevision&&
  originalReminderRule.documentEpoch===personalDocumentEpoch?originalReminderRule:undefined;
 const closeOriginalReminder=(visit:OriginalReminderChatVisit)=>{
  setOriginalReminderChat(active=>active===visit?null:active);
  requestAnimationFrame(()=>{if(originalReminderChatCurrent(visit)&&visit.returnFocus?.isConnected)visit.returnFocus.focus({preventScroll:true})});
 };
 const openSavedOriginalReminder=async(visit:OriginalReminderChatVisit,pin:{ruleId:string;ruleRevision:number})=>{
  if(!originalReminderChatCurrent(visit)||!pin.ruleId||!Number.isSafeInteger(pin.ruleRevision)||pin.ruleRevision<1)return false;
  const before=bridge.getState(),source=visit.source,scroller=scroll.current,trigger=visit.returnFocus;
  const previousList=String(before.state.moduleResultInputs?.['automation.list']?._requestId||'');
  const article=trigger?.closest<HTMLElement>('article[data-message-id]');
  const chatWork:ChatWorkReturn|undefined=scroller&&trigger&&article?.dataset.messageId===source.messageId?
    {document:visit.documentEpoch,conversation:source.conversationId,message:source.messageId,revision:source.sourceRevision,
     replyRun:source,top:scroller.scrollTop,offset:article.getBoundingClientRect().top-scroller.getBoundingClientRect().top,trigger}:undefined;
  const origin:WorkspaceOrigin={...captureWorkspaceOrigin(),label:'返回原回复',focus:null,chatWork,
    drawerChat:{id:source.conversationId,draftKey:String(before.composer?.key||''),documentEpoch:visit.documentEpoch}};
  relatedOrigin.current=origin;
  const opened=await navigate('automation',()=>{
   if(bridge.getDocumentEpoch()===visit.documentEpoch&&navigationWindow.current===visit.navigation+1)
    setOriginalReminderRule({...pin,source:visit.source,navigation:navigationWindow.current,documentEpoch:visit.documentEpoch,previousList});
  },{keepOrigin:true,stillCurrent:()=>originalReminderChatCurrent(visit)});
  if(!opened&&relatedOrigin.current===origin)relatedOrigin.current=origin.parent||null;
  return opened;
 };
 const retainedCopyVisit=useRef<{key:string;documentEpoch:number;navigation:number}|null>(null);
 useEffect(()=>{
  const revision=Number(state.requestedTaskRevision||0),id=String(state.requestedTaskId||'');
  // A notification can arrive before getTask or the document handshake finishes.
  // Consume it only when the original task can enter its native review window.
  if(revision<=previousRequestedTask.current||!id||!can('tasks.open')||!tasks.some(task=>task.id===id))return;
  previousRequestedTask.current=revision;
  openTask(id,'overview',snapshot?.view?.taskId===id);
 },[state.requestedTaskRevision,state.requestedTaskId,snapshot?.view?.taskId,tasks,can,openTask]);
 useEffect(()=>{
  const revision=Number(state.requestedProjectRevision||0),newRequest=revision>previousRequestedProject.current;
  previousRequestedProject.current=revision;
  if(!newRequest)return;
  const live=bridge.getState(),documentEpoch=bridge.getDocumentEpoch(),startingNavigation=navigationWindow.current;
  const key=live.state.requestedProjectRevision===revision?openedRetainedDraftCopyKey(live):'';
  // Mark only the visit that has arrived at this accepted native selection. This changes presentation, not the route or project.
  void navigate('projects',key?()=>{
   if(navigationWindow.current===startingNavigation+1&&bridge.getDocumentEpoch()===documentEpoch&&openedRetainedDraftCopyKey(bridge.getState())===key)
    retainedCopyVisit.current={key,documentEpoch,navigation:navigationWindow.current};
  }:undefined);
 },[state.requestedProjectRevision,navigate]);
 useEffect(()=>{
  const connection=Number(state.requestedConnectionRevision||0),section=state.requestedConnectionSection;
  if(connection<=previousRequestedConnection.current||!section){previousRequestedConnection.current=connection;return;}
  const notice=section==='automation'&&state.requestedReminderNotice?.connectionRevision===connection;
  // The tap can arrive ahead of the first document handshake. Don't lose its
  // precise receipt when attaching the already-requested reminder page.
  if(notice&&(!ready||!personalDocumentEpoch))return;
  previousRequestedConnection.current=connection;
  const startingNavigation=navigationWindow.current;
  const before=obj(bridge.getState().view);
  const warm=notice&&!taskOpen&&!['butler','chat','tasks','projects','setup','profile','remoteInbox','developer-logs'].includes(route)&&
   secondaryPage==='automation'&&before.section==='automation'&&before.taskId===null;
  void navigate(section,notice?()=>{
   const live=bridge.getState(),shown=obj(live.view),resume=shown.resumeRevision;
   if(!document.hidden&&bridge.getDocumentEpoch()===personalDocumentEpoch&&navigationWindow.current===startingNavigation+1&&
      live.state.requestedConnectionSection==='automation'&&live.state.requestedConnectionRevision===connection&&
      live.state.requestedReminderNotice?.connectionRevision===connection&&Number.isSafeInteger(resume))
    reminderNoticeVisit.current={connection,navigation:navigationWindow.current,documentEpoch:personalDocumentEpoch,resume,
     warm:warm&&shown.section==='automation'&&shown.taskId===null&&before.resumeRevision===resume};
  }:undefined);
 },[state.requestedConnectionRevision,state.requestedConnectionSection,state.requestedReminderNotice?.connectionRevision,navigate,ready,personalDocumentEpoch,route,secondaryPage,taskOpen]);
 const reminderNotice=route==='automation'&&reminderNoticeVisit.current?.navigation===navigationRevision&&
  reminderNoticeVisit.current.documentEpoch===personalDocumentEpoch?reminderNoticeVisit.current:undefined;
 useEffect(()=>{bridge.setBackHandler(()=>{if(searchOriginal){closeSearchOriginal();return true}if(!preparationBeforeLeave.current&&preparationBack.current?.())return true;if(providedMemoryBack.current?.())return true;if(conversationMemoryBack.current?.())return true;if(originalReminderBack.current?.())return true;if(queuedInputsBack.current?.())return true;
  if(deliveredFileRef.current&&!bridge.getState().state.attachmentWorkspace?.open){closeDeliveredFiles();return true;}
  if(taskLayer==='homeReport'||route==='chat'&&!taskOpen){
   const owner=bridge.getState().state;
   // The file layer covers its original reader, even while its Back handler is rebinding.
   if(owner.attachmentWorkspace?.open===true&&(taskLayer==='homeReport'?owner.attachmentWorkspace.reportResultOnly===true:!!owner.conversationReader)){
    attachmentBack.current?.();return true;
   }
  }
  if(route==='chat'&&savedHostSession.current){closeSavedHost();return true}
  if(historyWindowBack.current?.())return true;if(navigationMoreOpen){setNavigationMoreOpen(false);return true}if(chatMenuOpen){setChatMenuOpen(false);return true}if(permissionOptionsOpen){closePermissionOptions();return true}if(attachmentBack.current?.())return true;if(phoneViewerExpanded){setPhoneViewerExpanded(false);return true}if(readingDocuments.length){closeReading();return true}if(messageOriginTarget){setMessageOriginTarget(null);return true}if(messageQuoteTarget){setMessageQuoteTarget(null);return true}if(routeReferenceTarget){setRouteReferenceTarget(null);return true}if(skillDraftTarget){setSkillDraftTarget(null);return true}if(memoryDraftTarget){setMemoryDraftTarget(null);return true}if(sourceSelection){setSourceSelection(null);return true}if(messageMenu){setMessageMenu(null);return true}if(modelPicker.current?.handleBack())return true;if(state.chatAttachmentPickerOpen&&closeChatAttachmentPicker())return true;if(voiceReview){setVoiceReview(null);return true}if(phoneReview){if(!phoneReviewPending)setPhoneReview(null);return true}if(newProjectOpen||newFileOpen){closeCreate();return true}if(voiceOptionsOpen){setVoiceOptionsOpen(false);return true}if(workOptionsOpen){setWorkOptionsOpen(false);return true}if(commandOpen){setCommandOpen(false);return true}if(workOpen){setWorkOpen(false);return true}if(projectPickerOpen){setProjectPickerOpen(false);return true}if(fileDisplayOpen){setFileDisplayOpen(false);return true}if(taskOpen){backFromTask();return true}if(route==='tasks'&&taskFiltersOpen){closeWorkFind();return true}if(route==='tasks'&&restoreWorkScope())return true;if(route==='projects'&&projectWorkBack.current?.())return true;if(route==='projects'&&projectDevelopmentBack.current?.())return true;if(route==='projects'&&projectPreview.handleBack())return true;if(route==='projects'&&state.filePath){if(fileAdoption.isPending()||bridge.getState().state.fileSaving)return true;if(projectFileBack.current?.())return true;if(can('projects.close')&&!fileAdoption.pending){run('projects.close',fileIdentity);return true}return true}if(route==='chat'){if(inlinePhoneSelection.current){chooseInlinePhoneVisit(null);return true}if(preparationBeforeLeave.current&&preparationBack.current?.())return true;void navigate('butler');return true}if(route!=='butler'){if(route!=='tasks'&&route!=='projects'&&secondaryBack.current?.())return true;if(route==='setup')closeSetup();else returnFromSecondary();return true}return false;});return()=>{bridge.setBackHandler(()=>false)};},[searchOriginal,closeSearchOriginal,navigationMoreOpen,chatMenuOpen,deliveredFileVisit,permissionOptionsOpen,closePermissionOptions,phoneViewerExpanded,messageQuoteTarget,messageOriginTarget,routeReferenceTarget,skillDraftTarget,memoryDraftTarget,sourceSelection,readingDocuments,closeReading,messageMenu,voiceReview,phoneReview,phoneReviewPending,newProjectOpen,newFileOpen,voiceOptionsOpen,workOptionsOpen,commandOpen,workOpen,projectPickerOpen,fileDisplayOpen,taskFiltersOpen,closeWorkFind,restoreWorkScope,taskOpen,taskLayer,backFromTask,closeTask,route,state.filePath,state.chatAttachmentPickerOpen,fileIdentity,can,run,projectPreview,fileAdoption.pending,closeChatAttachmentPicker,chooseInlinePhoneVisit]);
 useEffect(()=>{document.documentElement.dataset.pocketUi=ready?'ready':'connecting';},[ready]);
 useEffect(()=>{if(phoneDraft.phone===true)setIntent(phoneDraft.mode==='main_screen'?'main_screen':'background');else if(phoneDraft.phone===false)setIntent('chat');},[phoneDraft.phone,phoneDraft.mode]);
 useEffect(()=>{
  if(route!=='projects'||!can('projects.refresh'))return;
  const live=bridge.getState(),view=obj(live.view),current=live.state;
  // Refresh reselects the project, which retires the native editor and its
  // outstanding save. An already open private editor needs no list reload.
  if(view.section===null&&view.taskId===null&&typeof current.projectId==='string'&&!!current.projectId&&
     typeof current.filePath==='string'&&!!current.filePath&&Number.isSafeInteger(current.fileVersion)&&current.fileVersion>=0&&
     (current.fileReady===true||current.fileLoading===true||current.fileSaving===true)&&
     asArray<Json>(current.projects).some(project=>project?.id===current.projectId))return;
  run('projects.refresh');
 },[route,can,run]);
 useLayoutEffect(()=>{if(!taskOpen)return;const node=taskScroll.current;if(node)node.scrollTop=taskReading.current.get(`${taskId}:${taskLayer}`)??0},[taskOpen,taskId,taskLayer]);
 useEffect(()=>{setFileDisplayOpen(false);setProjectPickerOpen(false)},[state.projectId]);
 useSurfaceEnter(taskScroll,`${taskId}:${taskLayer}`,{selector:'',depth:taskLayer==='overview'?0:1,active:taskOpen&&!selectedTask?.raw?.developmentContext});
 const markMessageArrival=useMessageArrivals(scroll,String(state.conversationId||''),messages.map((message,index)=>message.id||`${message.role}:${message.at}:${index}`),route==='chat',{
  owner:chatOwner,
  pendingClients:pendingInputs.filter(input=>input.draftKey===draftKey).map(input=>String(input.clientMessageId)),
  receipts:Object.entries(obj(state.chatReceipts)).map(([clientMessageId,value])=>({clientMessageId,conversationId:String(obj(value).conversationId||''),status:String(obj(value).status||'')})),
  submissions:messages.flatMap((message,index)=>message.role==='user'&&message.submission?.clientMessageId?[{id:message.id||`${message.role}:${message.at}:${index}`,clientMessageId:message.submission.clientMessageId}]:[]),
 });
 const openVoiceReview=()=>{
  if(!can('voice.review')||voiceReading.current||voiceWriting.current)return;
  const owner=voiceOwner,documentEpoch=bridge.getDocumentEpoch(),phone=intent!=='chat';
  const current=()=>!document.hidden&&voiceReviewOwner.current===owner&&bridge.getDocumentEpoch()===documentEpoch;
  voiceExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);
  voiceReading.current=true;setVoicePending(true);
  void act('voice.review',{phone}).then(value=>{
   if(!current())return;
   if(typeof value?.reviewHandle!=='string'||!value.reviewHandle||typeof value.inputId!=='string'||!value.inputId||
    typeof value.text!=='string'||value.phone!==phone)throw new Error('尚未核对到原听写，请重新打开。');
   const kept=retainedVoiceEdit.current;
   const same=kept?.owner===owner&&kept.inputId===value.inputId&&kept.original===value.text;
   if(!same)retainedVoiceEdit.current=null;
   setVoiceReview(value);setVoiceText(same?kept.edited:value.text);
  }).catch(error=>{if(current())setLocalError(error.message)}).finally(()=>{voiceReading.current=false;setVoicePending(false)});
 };
 const editVoiceReview=(edited:string)=>{
  const review=voiceReviewLive.current;
  if(!review||voiceWriting.current)return;
  retainedVoiceEdit.current={owner:voiceOwner,inputId:review.inputId,original:review.text,edited};
  setVoiceText(edited);
 };
 const submitVoiceReview=(command:'voice.accept'|'voice.discard')=>{
  const reviewed=voiceReviewLive.current;if(!reviewed||voiceWriting.current||voicePending)return;
  voiceWriting.current=true;setVoicePending(true);
  void act(command,{reviewHandle:reviewed.reviewHandle,...(command==='voice.accept'?{text:voiceText}:{})}).then(()=>{if(retainedVoiceEdit.current?.inputId===reviewed.inputId)retainedVoiceEdit.current=null;if(voiceReviewLive.current===reviewed)setVoiceReview(null)}).catch(error=>setLocalError(error.message)).finally(()=>{voiceWriting.current=false;setVoicePending(false)});
 };
 const discardVoice=()=>{if(isListening&&can('voice.stop')){run('voice.stop');return}if(!can('voice.review')||!can('voice.discard'))return;setVoicePending(true);void act('voice.review',{phone:intent!=='chat'}).then(async value=>{await act('voice.discard',{reviewHandle:value.reviewHandle});if(retainedVoiceEdit.current?.inputId===value.inputId)retainedVoiceEdit.current=null;}).catch(error=>setLocalError(error.message)).finally(()=>setVoicePending(false));};
 // Switching input mode retires the review, not the exact already registered phone request.
 // Only the native, still-owned phone draft can bring its original service denial back into view.
 const linkedPhoneDenial=(native:ReturnType<typeof bridge.getState>,visibleGoal:string)=>{
  const p=obj(native.phoneDraft),r=obj(p.startReceipt),view=obj(native.view),draft=native.composer,conversation=native.state.conversationId;
  if(route!=='chat'||intent==='chat'||taskOpen||document.hidden||draftSwitchPending()||view.section!==null||view.taskId!==null||!Number.isSafeInteger(view.resumeRevision)||
     native.state.conversationReader||typeof conversation!=='string'||!conversation||draft?.ready!==true||
     draft.conversationId!==conversation||draft.key!==`conversation:${conversation}`||r.composerKey!==draft.key||
     r.conversationId!==conversation||r.draftConversationId!==conversation||p.phone!==true||p.channelOrigin!==false||p.firstCheckOnly!==true||
     !['none','current'].includes(p.sourceState)||!Number.isSafeInteger(p.epoch)||!Number.isSafeInteger(p.revision)||
     r.draftEpoch!==p.epoch||r.draftRevision!==p.revision||typeof r.goal!=='string'||!visibleGoal.trim()||
     visibleGoal!==p.text||r.goal.trim()!==visibleGoal.trim()||!['main_screen','background'].includes(r.mode)||
     r.status!=='accepted'||r.admitted!==false||r.admissionState!=='service_denied')return;
  const receipt=phoneStartReceipt(r);
  if(!receipt.requestId||receipt.taskId!==receipt.requestId||!receipt.runId||receipt.epoch!==1||receipt.admissionState!=='service_denied')return;
  return {receipt,mode:r.mode as 'main_screen'|'background',witness:JSON.stringify([
   bridge.getDocumentEpoch(),navigationWindow.current,view.resumeRevision,draft.key,p.epoch,p.revision,p.sourceEditTicket,
   r.mode,receipt.requestId,receipt.runId,receipt.epoch])};
 };
 const heldLinkedDenial=linkedPhoneDenial(snapshot,phoneText.value);
 const presentedPhoneStart=phoneStart??heldLinkedDenial?.receipt;
 const originalDenialMode=presentedPhoneStart?.admissionState==='service_denied'&&heldLinkedDenial&&
  presentedPhoneStart.requestId===heldLinkedDenial.receipt.requestId&&presentedPhoneStart.runId===heldLinkedDenial.receipt.runId&&
  presentedPhoneStart.epoch===heldLinkedDenial.receipt.epoch?heldLinkedDenial.mode:undefined;
 const sameLinkedStart=!!presentedPhoneStart?.requestId&&presentedPhoneStart.conversationId===state.conversationId&&
  phoneDraft.channelOrigin!==true&&phoneDraft.sourceState!=='elsewhere'&&phoneDraft.startReceipt?.requestId===presentedPhoneStart.requestId;
 const sameBlankStart=!!phoneStart?.draftRequestId&&!state.conversationId&&phoneDraft.channelOrigin!==true&&phoneDraft.sourceState!=='elsewhere'&&
   phoneDraft.startReceipt?.draftRequestId===phoneStart.draftRequestId;
 const keepOriginalPhoneGoal=intent!=='chat'&&(sameLinkedStart||sameBlankStart)&&presentedPhoneStart?.status==='accepted'&&presentedPhoneStart.admitted!==true&&
  phoneDraft.firstCheckOnly===true&&phoneText.value.trim()===String(phoneDraft.text||'').trim();
 const queryOriginalPhone=(retire=false)=>{
  const original=presentedPhoneStart;if(document.hidden||phoneAction.current||!original)return;
  const blank=original.draftRequestId,action=retire?'phone.draft.retireUncreated':'phone.query';
  if(!can(action)||(!blank&&(!sameLinkedStart||!original.requestId||!original.conversationId))||(retire&&(!blank||!original.canRetire)))return;
  const args=blank?{draftRequestId:blank,...(retire?{confirmed:true}:{})}:{requestId:original.requestId,conversationId:original.conversationId};
  const window=phoneWindow.current,doc=bridge.getDocumentEpoch();
  const retained=!phoneStart?heldLinkedDenial:undefined;
  const here=()=>!document.hidden&&window===phoneWindow.current&&doc===bridge.getDocumentEpoch()&&
   (!retained||linkedPhoneDenial(bridge.getState(),phoneText.currentValue())?.witness===retained.witness);
  if(retained&&!here())return;
  phoneAction.current=true;setPhoneReviewPending(true);
  void act(action,args).then(value=>{if(!here())return;const receipt=phoneStartReceipt(value,original);setPhoneStart(receipt);setLocalStatus(receipt.message);if(receipt.status!=='unknown')setPhoneReview(null)})
    .catch(()=>{if(here()){
      if(original.admissionState&&['admitted','service_denied','component_unconfirmed'].includes(original.admissionState)){setLocalStatus('本次复核没有取得新回执，保留上次已核对的系统结果。');return}
      const receipt:PhoneStartReceipt={...original,status:'unknown',canRetire:false,message:'没有收到本次查询的回执。可以稍后再查，未重新启动任务。'};setPhoneStart(receipt);setLocalStatus(receipt.message)
    }})
    .finally(()=>{phoneAction.current=false;setPhoneReviewPending(false)});
 };
 const openBlankFirst=can('phone.draft.original')&&can('tasks.open')?(first:BlankFirstRun)=>{
  const now=bridge.getState(),key=now.composer?.key,phone=obj(now.phoneDraft);
  if(document.hidden||typeof key!=='string'||!key||now.state.conversationId||phone.phone!==true||phone.channelOrigin===true)return;
  phoneActivityPick.current={kind:'blank_draft',handle:first.draftRequestId,navigation:navigationWindow.current,document:bridge.getDocumentEpoch(),
   resume:Number(obj(now.view).resumeRevision||0),entry:'overview'};
  setPhoneReview(null);run('phone.draft.original',{composer:key,...first});
 }:undefined;
 const blankReceiptActions=(receipt:PhoneStartReceipt)=><BlankPhoneReceiptActions receipt={receipt} pending={phoneReviewPending}
  onCheck={can('phone.query')?()=>queryOriginalPhone():undefined} onCloseWait={can('phone.draft.retireUncreated')?()=>queryOriginalPhone(true):undefined}
  onTask={openBlankFirst}/>;
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
 const reviewedPlace=phoneReview?.conditions?.screen?.settings;
 const canVisitReviewedPlace=reviewedPlace==='background_setup'?can('setup.read'):reviewedPlace==='main_screen_choice'&&can('composer.intent');
 const visitReviewedPhonePlace=(handle:string,place:ReviewedPlace)=>{
  const review=phoneReviewLive.current,screen=review?.conditions?.screen;
  if(!review||review.reviewHandle!==handle||screen?.state!=='blocked'||screen.settings!==place||
    phoneAction.current||phoneReviewPending||phoneStart?.status==='unknown'||taskOpen||!isChatSurface||document.hidden)return;
  const pin=phonePlaceReturn(review);
  if(!pin){setPhoneReviewError({handle,message:'原草稿或窗口已变化。请返回核对，没有切换执行位置。'});return}
  if(place==='background_setup'&&can('setup.read')){setPhoneReview(null);void openSetup('phone',undefined,undefined,undefined,pin)}
  else if(place==='main_screen_choice'&&can('composer.intent')){phonePlacePicker.current={pin,navigation:navigationWindow.current};setPhoneReview(null)}
 };
 const finishPhoneReviewExit=(event:Event)=>{
  const pick=phonePlacePicker.current;phonePlacePicker.current=null;
  if(pick&&!phoneReviewLive.current&&pick.navigation===navigationWindow.current&&samePhonePlaceDraft(pick.pin)){
   const trigger=workspace.current?.querySelector<HTMLElement>('[data-workspace-entry="phone-place"]');
   if(trigger?.isConnected&&!trigger.closest('[inert],[hidden]')&&!document.querySelector('[role="dialog"][data-state="open"]')){
    trigger.focus({preventScroll:true});setWorkOptionsOpen(true);
   }
  }
  phoneExit.onCloseAutoFocus(event);
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
 useEffect(()=>{const receipt=phoneDraft.startReceipt;if(receipt&&['accepted','rejected','unknown'].includes(receipt.status)){const original=phoneStartReceipt(receipt);setPhoneStart(original);if(original.status!=='unknown'&&phoneReview&&phoneReview.reviewHandle===receipt.reviewHandle)setPhoneReview(receipt.recheckReview===phoneReview.reviewHandle?{...phoneReview,documentEpoch:undefined}:null)}},[phoneDraft.startReceipt?.status,phoneDraft.startReceipt?.reviewHandle,phoneDraft.startReceipt?.requestId,phoneDraft.startReceipt?.draftRequestId,phoneDraft.startReceipt?.canRetire,phoneDraft.startReceipt?.taskId,phoneDraft.startReceipt?.admitted,phoneDraft.startReceipt?.admissionState,phoneDraft.startReceipt?.runId,phoneDraft.startReceipt?.epoch,phoneOwner]);
 const send=()=>{if(displayDraftSettling||reportDraftFlight.current)return;if(keepOriginalPhoneGoal){setLocalStatus('这段目标已有原启动回执。可以核对，或编辑成一个新目标。');return}if(intent!=='chat'&&['changed','elsewhere','unverified'].includes(phoneDraft.sourceState)){setLocalStatus('请先核对所选原话。这份手机目标没有提交。');return}if(inputLimitMessage){setLocalError(inputLimitMessage);return}if(displayBlocked){setLocalError(displayBlocked);return}if(!composerModelName&&!localDisplayReady){if(can('setup.read'))void openModelSetup();else void navigate('models');return}if(intent!=='chat'){preparePhone();return}if(!composerHasPayload(text,asArray<Json>(state.selectedChatAttachments).length)||!draftKey||previewRefsUnavailable)return;
  if(!can('composer.send')||state.chatSubmitting||pendingOriginal)return;
  if(!localDisplayReady&&ordinaryDraftRevision===null){setLocalError('请等本机核对当前草稿，再发送。');return;}
  const settled=registration.begin();if(!settled)return;
  if(route==='butler'){butlerSendConversation.current=String(state.conversationId||'');void navigate('chat')}
  setLocalStatus('');goToLatest();chatDraft.markSent();markMessageArrival();
  const displayedTurn=foregroundTurn?{taskId:foregroundTurn.taskId,runId:foregroundTurn.runId,epoch:foregroundTurn.epoch}:{};
  void act('composer.send',{key:draftKey,epoch:draftEpoch,text,...(localDisplayReady?{primaryDisplayRevision:composer.revision}:
   {expectedDraftRevision:ordinaryDraftRevision,expectedForegroundTurn:displayedTurn})}).catch(error=>{
   const now=bridge.getState().composer;if(now?.key===draftKey&&now?.epoch===draftEpoch)setLocalError(error instanceof Error?error.message:String(error));
  }).finally(settled);
 };
 useLayoutEffect(()=>{if(route!=='chat'||!butlerSendConversation.current)return;const original=butlerSendConversation.current;butlerSendConversation.current=null;if(original===String(state.conversationId||''))goToLatest();},[route,state.conversationId,goToLatest]);
 const targetWork=async(task:Task,original?:WorkIdentity)=>{const identity=original||ownerWorkIdentity(task),conversationId=privateTaskConversationId(task);if(!identity||!identity.runId||identity.epoch<1||!conversationId||!can('ownerWork.supplement.read'))return;if(workSupplementBeforeLeave.current&&!await workSupplementBeforeLeave.current.flush())return;setWorkSupplementTarget({...identity,goal:task.goal,conversationId});if(taskOpen&&taskId===task.id)showTaskLayer('overview');else openTask(task.id);};
 const returnToWorkInput=(pending:Json)=>{const identity=obj(pending.originalTarget) as WorkIdentity,task=tasks.find(row=>row.id===identity.taskId);if(task&&identity.runId&&Number.isSafeInteger(identity.epoch))void targetWork(task,identity);else if(can('composer.returnPending'))run('composer.returnPending',{clientMessageId:pending.clientMessageId});};
 const workWaitsForTeamDecision=(task:Task)=>ownerWorkPendingTeamStart(task,
  review?.task&&taskBinding(review.task)===taskBinding(task)&&currentReviewWindow(reviewWindow.current)?review.approvals:undefined);
 const controlWork=(task:Task,action:'cancel'|'continue')=>{
  const identity=ownerWorkIdentity(task);
  if(!identity||!can('ownerWork.control')||task.raw?.controls?.[action==='cancel'?'canStop':'canContinue']!==true)return;
  if(action==='continue'){
   const live=bridge.getState(),original=asArray<Task>(live.state.tasks).find(row=>row.id===identity.taskId);
   if(original?.raw?.runId===identity.runId&&original.raw.coordinatorEpoch===identity.epoch&&workWaitsForTeamDecision(original))return;
  }
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
 const fileWorkLayer=useRef<{id:string;layer:TaskLayer}|null>(null);
 fileWorkLayer.current=taskOpen&&taskId&&!taskOpening&&selectedTask?.id===taskId&&taskLayer!=='homeReport'?{id:taskId,layer:taskLayer}:null;
 const readFileWorkParent=(id:string,live=bridge.getState())=>{
  const owner=acknowledgedWorkVisit.current,screen=fileWorkLayer.current,view=obj(live.view),taskRequest=live.state.requestedTaskRevision;
  if(!owner||owner.taskId!==id||openedTaskId.current!==id||screen?.id!==id||document.hidden||
   owner.documentEpoch!==bridge.getDocumentEpoch()||owner.resumeRevision!==view.resumeRevision||view.section!=null||view.taskId!==id||
   live.state.conversationReader||!Number.isSafeInteger(taskRequest))return null;
  const matching=asArray<Task>(live.state.tasks).filter(row=>row.id===id),task=matching.length===1?matching[0]:undefined,raw=task?.raw;
  const work=ownerWorkIdentity(task);
  if(!work||work.epoch<1||!work.runId||raw?.kind!=='assistant'||raw.taskProjection!=='detail_window'||
   raw.taskCoreReadLimited!==false||raw.auditBodyIncluded!==true||raw.contextAvailable!==true||raw.contextWithdrawn===true||
   raw.sourceCurrentReadRequired===true||raw.origin!=null||raw.developmentContext!=null||raw.teamRunId!=null||
   raw.memberId!=null||raw.parentTaskId!=null||raw.ownerPhoneControl||raw.ownerDeviceWork)return null;
  return {owner,work,layer:screen.layer,navigation:navigationWindow.current,taskRequest:Number(taskRequest)};
 };
 const readWorkTextVisit=(id:string)=>{
  const native=bridge.getState(),conversation=native.state.conversationId,parent=readFileWorkParent(id,native);
  if(route!=='chat'||!parent||parent.layer!=='overview'||typeof conversation!=='string'||!conversation||
     !ordinaryChatWorkRow(asArray<Task>(native.state.tasks).find(row=>row.id===id),conversation,true))return;
  return {...parent.work,visit:JSON.stringify([parent.owner.request,parent.owner.documentEpoch,parent.owner.resumeRevision,
   parent.navigation,parent.taskRequest,workIdentityKey(parent.work),conversation])};
 };
 const stillFileWorkParent=(pin:WorkFileVisit,live=bridge.getState())=>{
  const parent=readFileWorkParent(pin.work.taskId,live);
  return parent?.owner===pin.owner&&parent.layer===pin.layer&&parent.navigation===pin.navigation&&parent.taskRequest===pin.taskRequest&&
   workIdentityKey(parent.work)===workIdentityKey(pin.work);
 };
 const observeFileWorkVisit=()=>{
  const pin=workFileVisit.current;if(!pin)return;
  const live=bridge.getState(),shown=obj(live.state.attachmentWorkspace),revision=live.revision;
  if(!stillFileWorkParent(pin,live)||pin.windowId&&shown.open!==true){workFileVisit.current=null;return;}
  // A generic open ACK does not name a native file window. Only bind an actual subsequent projection.
  if(pin.afterAck===undefined||typeof revision!=='number'||!Number.isSafeInteger(revision)||revision<=pin.afterAck||
   shown.open!==true||shown.ready!==true)return;
  const legitimate=shown.taskId===pin.work.taskId&&shown.fromWorkResult===!!pin.file&&shown.resultFilesOnly===false&&
   shown.group===false&&shown.team===false&&shown.deviceOriginalOnly===false&&shown.historyResultOnly===false&&shown.reportResultOnly===false&&
   shown.restoredWorkReturn===false&&typeof shown.windowId==='string'&&!!shown.windowId;
  if(!legitimate||pin.windowId&&shown.windowId!==pin.windowId){workFileVisit.current=null;return;}
  pin.windowId=shown.windowId;
 };
 useLayoutEffect(observeFileWorkVisit,[snapshot?.revision,taskOpen,taskOpening,taskId,taskLayer,navigationRevision]);
 const openWorkFiles=(id:string,file?:TaskResultFile)=>{
  const live=bridge.getState(),prior=workFileVisit.current;
  const parent=readFileWorkParent(id,live);
  if(parent&&(live.state.attachmentWorkspace?.open===true||prior&&!prior.windowId&&stillFileWorkParent(prior,live)))return Promise.resolve(undefined);
  const pin:WorkFileVisit|null=parent?{...parent,file:file&&{id:file.id,sha256:file.sha256}}:null;
  workFileVisit.current=pin;
  return act('attachmentWorkspace.open',{taskId:id,...(file?{fileId:file.id,sha256:file.sha256,openOriginal:true}:{})},file?id:undefined).then(receipt=>{
   if(pin&&workFileVisit.current===pin){
    const at=bridge.getState();
    if(receipt?.opened===true&&receipt.reading===true&&typeof at.revision==='number'&&Number.isSafeInteger(at.revision)&&stillFileWorkParent(pin,at))pin.afterAck=at.revision;
    else workFileVisit.current=null;
   }
   return receipt;
  },error=>{if(pin&&workFileVisit.current===pin)workFileVisit.current=null;throw error;});
 };
 const continueFromWorkFiles=(shown:Json)=>{
  observeFileWorkVisit();
  const pin=workFileVisit.current,scope=obj(shown.snapshot),selected=obj(shown.file);
  return !!pin?.windowId&&shown.windowId===pin.windowId&&shown.taskId===pin.work.taskId&&scope.taskId===pin.work.taskId&&
   scope.taskRunId===pin.work.runId&&scope.coordinatorEpoch===pin.work.epoch&&
   (!pin.file||selected.id===pin.file.id&&selected.sha256===pin.file.sha256);
 };
 const openTaskResultFile=(id:string,file:TaskResultFile,target:HTMLButtonElement)=>{
  const open=()=>{
   const native=bridge.getState();
   if(document.hidden||openedTaskId.current!==id||obj(native.view).taskId!==id||native.state.attachmentWorkspace?.open||resultFileReturnFocus.current)return;
   const origin={taskId:id,target};
   resultFileReturnFocus.current=origin;
   void openWorkFiles(id,file).then(receipt=>{if(!receipt&&resultFileReturnFocus.current===origin)resultFileReturnFocus.current=null;}).catch(()=>{
    if(resultFileReturnFocus.current===origin)resultFileReturnFocus.current=null;
   });
  };
  if(!guardTaskLeave(open))open();
 };
 const fileResultReader=(native:Json,origin:HistoryResultOrigin)=>{
  const reader=obj(native.conversationReader);
  return reader.window===origin.window&&reader.conversation===origin.conversationId&&!reader.loading&&!reader.error&&!reader.limited?reader:null;
 };
 const restoreTaskFileFocus=(event:Event)=>{
  if(bridge.getState().state.attachmentWorkspace?.open!==true)workFileVisit.current=null;
  const origin=resultFileReturnFocus.current;
  resultFileReturnFocus.current=null;
  if(!origin||event.defaultPrevented||document.hidden)return;
  const native=bridge.getState().state;
  if('taskId' in origin){
   if(!origin.target.isConnected||origin.target.disabled||openedTaskId.current!==origin.taskId||origin.report&&(homeReport.current!==origin.report||taskLayer!=='homeReport'||
      obj(bridge.getState().view).taskId!==origin.taskId||!currentHomeReport(origin.report)))return;
   event.preventDefault();origin.target.focus({preventScroll:true});
   return;
  }
  if(route!=='chat'||bridge.getDocumentEpoch()!==origin.document)return;
  if(origin.reader){
   const reader=fileResultReader(native,origin.reader);
   if(!reader)return;
   event.preventDefault();origin.reader.restore();
   return;
  }
  if(native.conversationId!==origin.conversationId||native.conversationReader)return;
  event.preventDefault();
  if(!resultMessageIn(native.messages,origin.resultMessageId,origin.result,origin.revision)||
   origin.chatReading&&!restoreChatWorkAt(origin.chatReading))setLocalStatus('当前对话还没读到这份结果所属的原消息，没有跳到其他轮次。');
  if(!origin.chatReading&&origin.target.isConnected&&!origin.target.disabled)origin.target.focus({preventScroll:true});
 };
 const openHomeReportFiles=async(report:VerifiedHomeReport,target:HTMLButtonElement)=>{
  const {taskId,runId,epoch,reportMessageId,conversationId}=report.target;
  const current=()=>target.isConnected&&homeReport.current===report&&openedTaskId.current===taskId&&
   obj(bridge.getState().view).taskId===taskId&&currentHomeReport(report);
  if(!current())throw new Error('原汇报已变化，没有打开其他文件。');
  if(resultFileReturnFocus.current||bridge.getState().state.attachmentWorkspace?.open)return;
  await verifyHomeReport(report.target,report.pin);
  if(!current()||resultFileReturnFocus.current||bridge.getState().state.attachmentWorkspace?.open)return;
  const origin={taskId,report,target};resultFileReturnFocus.current=origin;
  try{
   const opened=await bridge.command('attachmentWorkspace.open',{taskId,resultMessageId:reportMessageId,conversationId,expectedRunId:runId,expectedEpoch:epoch});
   if(opened?.opened!==true)throw new Error('本轮文件暂未打开，请从原汇报重试。');
  }catch(error){
   if(resultFileReturnFocus.current===origin)resultFileReturnFocus.current=null;
   if(current())throw error;
  }
 };
 const verifyReportFileWork=async(taskId:string,windowId:string)=>{
  const origin=resultFileReturnFocus.current;
  if(!origin||!('taskId' in origin)||origin.taskId!==taskId||!origin.report)throw new Error('请在原汇报重新打开这份原件。');
  const {report}=origin;
  const current=()=>{
   const native=bridge.getState(),view=obj(native.state.attachmentWorkspace),scope=obj(view.snapshot);
   return resultFileReturnFocus.current===origin&&homeReport.current===report&&openedTaskId.current===taskId&&
    obj(native.view).taskId===taskId&&origin.target.isConnected&&currentHomeReport(report)&&view.open===true&&view.ready===true&&
    view.reportResultOnly===true&&view.canOpenReportOriginalWork===true&&view.windowId===windowId&&view.taskId===taskId&&
    scope.taskRunId===report.target.runId&&scope.coordinatorEpoch===report.target.epoch;
  };
  if(!current())throw new Error('原汇报窗口已变化，没有打开其他文件。');
  await verifyHomeReport(report.target,report.pin);
  if(!current())throw new Error('原汇报窗口已变化，没有打开其他文件。');
 };
 useLayoutEffect(()=>{
  // Only a newly re-admitted same-run work original has both native flags. The command's receipt alone is not the transition.
  const native=bridge.getState(),view=obj(native.state.attachmentWorkspace),scope=obj(view.snapshot),origin=resultFileReturnFocus.current;
  if(taskLayer==='homeReport'&&taskOpen&&origin&&'taskId' in origin&&origin.taskId===taskId&&origin.report&&homeReport.current===origin.report&&
   openedTaskId.current===taskId&&obj(native.view).taskId===taskId&&currentHomeReport(origin.report)&&view.open===true&&view.ready===true&&
   view.taskId===taskId&&view.resultFilesOnly===true&&view.fromWorkResult===true&&view.reportResultOnly===false&&view.historyResultOnly===false&&
   scope.taskRunId===origin.report.target.runId&&scope.coordinatorEpoch===origin.report.target.epoch)showTaskLayer('overview');
 },[taskId,taskLayer,taskOpen,state.attachmentWorkspace,showTaskLayer]);
 const openChatResult=async(resultMessageId:string,result:TaskResult,target:HTMLButtonElement,reader?:HistoryResultOrigin,preferred?:PreferredChatFile)=>{
  const conversationId=reader?.conversationId||String(state.conversationId||'');
  const canReadFiles=can('tasks.resultFiles')&&can('attachmentWorkspace.open');
  if(preferred&&(!canReadFiles||!preferred.current()||openingChatResult.current||resultFileReturnFocus.current))throw new Error('这段对话的原文件暂不可打开。');
  if(reader&&!canReadFiles)return;
  if(!conversationId||openingChatResult.current||resultFileReturnFocus.current)return;
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const beforeTaskRequest=reviewRequest.current,beforeSearchRequest=searchOpenRequest.current;
  const source=(native:Json,revision?:number)=>resultMessageIn(reader?fileResultReader(native,reader)?.messages:
   native.conversationId===conversationId&&!native.conversationReader?native.messages:null,resultMessageId,result,reader?.sourceRevision??revision);
  const entry=bridge.getState(),selected=source(entry.state);
  if(!target.isConnected||document.hidden)return;
  if(!selected){
   if(reader||preferred)throw new Error('当前阅读窗口没有核对到这份结果所属的原消息。');
   if(bridge.getState().state.conversationId===conversationId)setLocalStatus('当前对话还没读到这份结果所属的原消息。');
   return;
  }
  const scroller=scroll.current,article=target.closest<HTMLElement>('article[data-message-id]');
  const chatReading:ChatWorkReturn|undefined=!reader&&scroller&&article?.dataset.messageId===resultMessageId?{
   document:documentEpoch,conversation:conversationId,message:resultMessageId,revision:selected.sourceRevision,
   top:scroller.scrollTop,offset:article.getBoundingClientRect().top-scroller.getBoundingClientRect().top,trigger:target
  }:undefined;
  const currentSource=(current:ReturnType<typeof bridge.getState>)=>!document.hidden&&navigationWindow.current===navigation&&
   bridge.getDocumentEpoch()===documentEpoch&&!current.state.attachmentWorkspace?.open&&!!source(current.state,selected.sourceRevision);
  const stillCurrent=()=>{
   const current=bridge.getState();
   return (!preferred||preferred.current())&&!obj(current.view).taskId&&currentSource(current);
  };
  if(!stillCurrent())return;
  if(chatReading){stopFollowing();rememberChatPosition();}
  // A text-only result opens its work instead of the file layer. Keep the same
  // exact reply only after the original task opens; a draft guard may stop it.
  const fallback=()=>{
   if(!chatReading||reviewRequest.current!==beforeTaskRequest||searchOpenRequest.current!==beforeSearchRequest||!stillCurrent())return;
   const returnTo={...chatReading,result:{work:result,navigation}},request=++searchOpenRequest.current;
   const ownVisit=(current:ReturnType<typeof bridge.getState>)=>{
    const shown=obj(current.view).taskId;
    return searchOpenRequest.current===request&&(!shown||shown===result.taskId);
   };
   const original=()=>{const current=bridge.getState();return ownVisit(current)&&currentSource(current)};
   openTask(result.taskId,'overview',false,undefined,undefined,{runId:result.runId,epoch:result.epoch},original,undefined,reason=>{
    if(ownVisit(bridge.getState())&&atChatResultReturn(returnTo))setLocalStatus(reason instanceof Error?reason.message:'原工作或这条答复已变化，没有打开其他记录。');
   },undefined,{returnTo,beforeTaskRequest,navigation});
  };
  if(!canReadFiles){fallback();return;}
  openingChatResult.current=resultMessageId;setOpeningChatResultId(resultMessageId);setLocalError('');
  const locator={taskId:result.taskId,resultMessageId,conversationId,...(reader?{resultReaderWindow:reader.window,resultSourceRevision:reader.sourceRevision}:{})};
  const origin={conversationId,resultMessageId,result,target:preferred?.returnFocus||target,reader,historySourceGeneration:reader?entry.state.traceSourceRevision:undefined,
   document:documentEpoch,revision:selected.sourceRevision,chatReading,
   navigation,resume:obj(bridge.getState().view).resumeRevision,taskRequest:beforeTaskRequest};
  let opening=false;
  try{
   const listed=await bridge.command('tasks.resultFiles',locator);
   if(!stillCurrent())return;
   if(listed?.taskId!==result.taskId||!Array.isArray(listed.files)||!listed.files.length&&!listed.readLimited){
    if(reader||preferred)throw new Error(listed?.taskId===result.taskId&&Array.isArray(listed.files)?'这次工作没有可读取的已保存文件。':'没有核对到原回复的文件清单。');
    fallback();return;
   }
   const exact=preferred?listed.files.filter((file:TaskResultFile)=>file.id===preferred.file.id&&file.sha256===preferred.file.sha256&&file.runProvenance==='current'):null;
   if(exact&&exact.length!==1)throw new Error('这份文件的原版本已变化，没有打开其他文件。');
   const single=exact?exact[0]:listed.files.length===1&&!listed.readLimited?listed.files[0] as TaskResultFile:null;
   resultFileReturnFocus.current=origin;opening=true;
   const opened=await bridge.command('attachmentWorkspace.open',{...locator,...(single?{fileId:single.id,sha256:single.sha256,openOriginal:true}:{})});
   if(opened?.opened!==true)throw new Error('未确认打开这份原结果，请从原消息核对。');
   preferred?.opened();
  }catch(error){
   if(resultFileReturnFocus.current===origin)resultFileReturnFocus.current=null;
   if(reader||preferred)throw error;
   if(opening){
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
 const fileDraftFor=(draft:Json,conversationId:string,label:string):FileDraftTarget|undefined=>
  can('composer.object.add')&&draft.ready===true&&typeof draft.key==='string'&&draft.key&&
  Number.isSafeInteger(draft.epoch)&&Number.isSafeInteger(draft.revision)&&draft.revision>0&&draft.conversationId===conversationId
  ?{key:draft.key,epoch:draft.epoch,revision:draft.revision,conversationId,label}:undefined;
 const fileDraftTarget=fileDraftFor(composer,String(state.conversationId||''),currentConversation?.title||'当前管家输入');
 const canChooseFileForDraft=isChatSurface&&intent==='chat'&&!taskOpen&&!state.conversationReader&&!state.chatBusy&&!state.chatSubmitting&&!state.phoneSubmitting&&
  !pendingHere&&!obj(snapshot?.view).section&&!obj(snapshot?.view).taskId&&can('attachments.openPicker')&&can('attachments.closePicker')&&can('attachments.toggle');
 const filePickerDraftCurrent=(draft:FileDraftTarget,documentEpoch:number,navigation:number)=>{
  const native=bridge.getState(),input=obj(native.composer),view=obj(native.view),owner=native.state,phone=obj(native.phoneDraft);
  return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&navigationWindow.current===navigation&&!view.section&&!view.taskId&&
   phone.phone===false&&phone.channelOrigin===false&&!owner.conversationReader&&!owner.chatBusy&&!owner.chatSubmitting&&!owner.phoneSubmitting&&input.ready===true&&input.key===draft.key&&
   input.epoch===draft.epoch&&input.conversationId===draft.conversationId&&String(owner.conversationId||'')===draft.conversationId;
 };
 const liveResultMessageCurrent=(native:ReturnType<typeof bridge.getState>,conversationId:string,source:SavedAttachmentMessage)=>{
  const original=source.liveResult?.result;
  if(!original)return false;
  const work=resultAttachmentWork(native,{taskId:original.taskId,conversationId});
  const message=resultMessageIn(native.state.messages,source.id,original,source.revision);
  return work?.runId===original.runId&&work.epoch===original.epoch&&messageTaskResult(message)?.kind==='owner_work';
 };
 const savedMessageAttachmentCurrent=(draft:FileDraftTarget,source:SavedAttachmentMessage,file:OriginalAttachmentChoice)=>{
  const native=bridge.getState(),view=obj(native.view),owner=native.state;
  if(view.resumeRevision!==source.resume||reviewRequest.current!==source.taskRequest||owner.conversationId!==draft.conversationId||
     owner.attachmentWorkspace?.open||owner.queuedInputsPage||owner.filePath)return false;
  if(source.liveResult)return owner.attachmentWorkspace?.open===false&&!draftSwitchPending()&&liveResultMessageCurrent(native,draft.conversationId,source);
  if(source.historyResult)return owner.attachmentWorkspace?.open===false&&owner.conversationReader===null&&
   owner.traceSourceRevision===source.historyResult.sourceGeneration&&!draftSwitchPending();
  const rows=asArray<Message>(owner.messages).filter(row=>row.id===source.id);
  return rows.length===1&&rows[0].sourceRevision===source.revision&&savedOwnerAttachmentChoices(rows[0]).some(original=>
   original.fileId===file.fileId&&original.sha256===file.sha256);
 };
 const fileInPicker=(file:OriginalAttachmentChoice,requestId:string,fromMessage?:SavedAttachmentMessage)=>{
  const frame=bridge.getState().state,inputs=obj(obj(frame.moduleResultInputs)['attachments.list']);
  if(frame.chatAttachmentPickerRequest!==requestId||inputs._requestId!==requestId||asArray<string>(frame.moduleBusy).includes('attachments.list'))return;
  const rows=asArray<Json>(obj(obj(frame.moduleResults)['attachments.list']).attachments).filter(row=>row?.id===file.fileId&&(fromMessage||row.sha256===file.sha256));
  const boundOutput=fromMessage?.liveResult??fromMessage?.historyResult,bookSource=obj(rows[0]?.source);
  return inputs._failed!==true&&rows.length===1&&rows[0].sha256===file.sha256&&rows[0].outputSourceRetired!==true&&
   (!file.standaloneDocument||matchesStandaloneDocumentBook(rows[0],file.standaloneDocument))&&
   (!fromMessage||rows[0].principalId==='owner'&&rows[0].spaceId==='private'&&!!exactFileTarget(rows[0])&&!Object.hasOwn(bookSource,'groupSpaceId'))&&
   (!boundOutput||rows[0].mimeType===boundOutput.mimeType&&rows[0].size===boundOutput.size&&bookSource.kind===boundOutput.kind&&
    bookSource.taskId===boundOutput.result.taskId&&bookSource.runId===boundOutput.result.runId);
 };
 const awaitPickerProjection=<T,>(current:()=>boolean,observe:()=>T|undefined)=>new Promise<T|undefined>(resolve=>{
  let unsubscribe=()=>{};
  const finish=(value?:T)=>{clearTimeout(timer);unsubscribe();resolve(value)};
  const timer=window.setTimeout(()=>finish(),5000);
  const check=()=>{
   if(!current()){finish();return;}
   const value=observe();if(value!==undefined)finish(value);
  };
  unsubscribe=bridge.subscribe(check);check();
 });
 const startFileAttachmentPicker=async(file:OriginalAttachmentChoice,draft:FileDraftTarget,sourceCurrent:()=>boolean,closeOriginal?:()=>Promise<void>,sourceMessage?:SavedAttachmentMessage)=>{
  const documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current,initial=bridge.getState();
  const liveDraft=()=>filePickerDraftCurrent(draft,documentEpoch,navigation)&&bridge.getState().composer?.revision===draft.revision;
  const launch:FileAttachmentPickerLaunch={requestId:'',cancelled:false,settled:false};
  const current=()=>!launch.cancelled&&sourceCurrent()&&liveDraft();
  if(!current()||initial.state.chatAttachmentPickerOpen!==false||initial.state.chatAttachmentPickerRequest!=null||fileAttachmentPickerLaunch.current?.settled===false)
   throw new Error('原草稿或附件选择已变化，没有加入文件。');
  // Native may publish the open picker before the particular list is proven.
  // Mask any generic picker until this explicit focused request can be shown.
  fileAttachmentPickerLaunch.current=launch;
  let shown=false;
  try{
   const launched=await bridge.command('attachments.openPicker',{key:draft.key,epoch:draft.epoch,revision:draft.revision});
   if(typeof launched?.requestId!=='string'||!launched.requestId)throw new Error('未打开这份文件的附件选择，草稿保留。');
   const requestId=launch.requestId=launched.requestId;
   const matching=await awaitPickerProjection(current,()=>fileInPicker(file,requestId,sourceMessage));
   if(!matching||!current())throw new Error('尚未在普通附件清单核对到这份原件，没有修改草稿。');
   if(closeOriginal)await closeOriginal();
   if(launch.cancelled||!liveDraft()||!closeOriginal&&!sourceCurrent()||fileInPicker(file,requestId,sourceMessage)!==true)
    throw new Error('原草稿或附件选择已变化，没有加入文件。');
   // Only reveal the existing picker once the exact source, draft and list are known.
   setFileAttachmentPicker({file,draft,requestId,documentEpoch,navigation,sourceMessage});
   shown=true;
  }catch(error){
   if(!launch.requestId&&obj(error).code==='outcome_unknown')throw new Error('这次附件选择未确认，没有加入文件。可回输入框重新点「添加附件」。');
   throw error;
  }finally{
   launch.settled=true;
   if(!shown&&launch.requestId)void bridge.command('attachments.closePicker',{pickerRequestId:launch.requestId}).catch(()=>{});
  }
 };
 const existingPrivateAttachmentDraft=route==='chat'&&!!state.conversationId&&canChooseFileForDraft&&!draftSwitching&&
  !state.queuedInputsPage&&!state.filePath&&phoneDraft.phone===false&&phoneDraft.channelOrigin===false&&
  fileDraftTarget?.key===`conversation:${state.conversationId}`&&ordinaryDraftRevision===fileDraftTarget?.revision?fileDraftTarget:undefined;
 const messageAttachmentDraft=!state.attachmentWorkspace?.open?existingPrivateAttachmentDraft:undefined;
 const chooseSavedMessageAttachment=async(message:Message,file:OriginalAttachmentChoice,draft:FileDraftTarget)=>{
  const documentEpoch=personalDocumentEpoch,navigation=navigationRevision;
  const resume=obj(snapshot?.view).resumeRevision;
  const atDraft=()=>!draftSwitchPending()&&filePickerDraftCurrent(draft,documentEpoch,navigation)&&obj(bridge.getState().view).resumeRevision===resume;
  if(!message.id||!Number.isSafeInteger(message.sourceRevision)||!Number.isSafeInteger(resume)||!atDraft())return;
  const source={id:message.id,revision:Number(message.sourceRevision),resume:Number(resume),taskRequest:reviewRequest.current};
  const current=()=>atDraft()&&savedMessageAttachmentCurrent(draft,source,file);
  setMessageMenu(null);setLocalError('');
  try{
   if(!current())throw new Error('原消息或附件版本已变化，没有加入草稿。');
   stopFollowing();rememberChatPosition();
   await startFileAttachmentPicker(file,draft,current,undefined,source);
  }catch(error){
   if(atDraft()&&reviewRequest.current===source.taskRequest)
    setLocalError(error instanceof Error?error.message:'尚未核对到原附件，草稿保留。');
  }
 };
 const focusedFileDraftCurrent=(picker:FileAttachmentPickerOrigin)=>
  filePickerDraftCurrent(picker.draft,picker.documentEpoch,picker.navigation)&&
  (!picker.sourceMessage||savedMessageAttachmentCurrent(picker.draft,picker.sourceMessage,picker.file));
 const filePickerVisible=!!fileAttachmentPicker&&!fileAttachmentPickerLaunch.current?.cancelled&&state.chatAttachmentPickerRequest===fileAttachmentPicker.requestId&&
  focusedFileDraftCurrent(fileAttachmentPicker);
 const focusedInventoryReady=filePickerVisible&&obj(obj(state.moduleResultInputs)['attachments.list'])._requestId===fileAttachmentPicker?.requestId&&
  !asArray<string>(state.moduleBusy).includes('attachments.list')&&(!fileAttachmentPicker?.sourceMessage&&!fileAttachmentPicker?.file.standaloneDocument||
   fileInPicker(fileAttachmentPicker.file,fileAttachmentPicker.requestId,fileAttachmentPicker.sourceMessage)===true);
 useEffect(()=>{
  if(!fileAttachmentPicker)return;
  const {requestId}=fileAttachmentPicker;
  if(fileAttachmentPickerLaunch.current?.requestId===requestId){
   if(fileAttachmentPickerLaunch.current.cancelled){setFileAttachmentPicker(null);return;}
   fileAttachmentPickerLaunch.current=null;
  }
  if(bridge.getState().state.chatAttachmentPickerRequest!==requestId){setFileAttachmentPicker(null);return;}
  if(ready&&!filePickerVisible)void bridge.command('attachments.closePicker',{pickerRequestId:requestId}).catch(()=>{});
 },[fileAttachmentPicker,filePickerVisible,state.chatAttachmentPickerRequest,ready]);
 const ordinaryPickerScopeCurrent=(scope:OrdinaryAttachmentPickerScope)=>fileAttachmentPickerLaunch.current===scope.launch&&
  filePickerDraftCurrent(scope.draft,scope.documentEpoch,scope.navigation)&&obj(bridge.getState().view).resumeRevision===scope.resume;
 const reopenedPickerCurrent=(reopened:ReopenedAttachmentPicker)=>ordinaryPickerScopeCurrent(reopened)&&
  bridge.getState().state.chatAttachmentPickerRequest===reopened.requestId;
 const reopenedPickerVisible=!!reopenedAttachmentPicker&&reopenedPickerCurrent(reopenedAttachmentPicker);
 const reopenedInventoryCurrent=(reopened:ReopenedAttachmentPicker)=>{
  const native=bridge.getState().state,list=obj(obj(native.moduleResultInputs)['attachments.list']);
  return reopenedPickerCurrent(reopened)&&list._requestId===reopened.requestId&&list._failed!==true&&
   !asArray<string>(native.moduleBusy).includes('attachments.list');
 };
 const reopenedInventoryReady=!!reopenedAttachmentPicker&&reopenedInventoryCurrent(reopenedAttachmentPicker);
 useEffect(()=>{
  const native=bridge.getState().state;
  if(!reopenedAttachmentPicker||native.chatAttachmentPickerRequest===reopenedAttachmentPicker.requestId)return;
  if(native.chatAttachmentPickerOpen===false&&fileAttachmentPickerLaunch.current===reopenedAttachmentPicker.launch)fileAttachmentPickerLaunch.current=null;
  setReopenedAttachmentPicker(null);
 },[reopenedAttachmentPicker,state.chatAttachmentPickerOpen,state.chatAttachmentPickerRequest]);
 const toggleFocusedFileAttachment=(id:string,sha256:string,selected:boolean)=>{
  const origin=fileAttachmentPicker,native=bridge.getState(),input=obj(native.composer);
  if(!origin||fileAttachmentPickerLaunch.current?.cancelled||id!==origin.file.fileId||sha256!==origin.file.sha256||!focusedFileDraftCurrent(origin)||
     native.state.chatAttachmentPickerRequest!==origin.requestId||!Number.isSafeInteger(input.revision)||
     (origin.sourceMessage||origin.file.standaloneDocument)&&(input.revision!==composer.revision||fileInPicker(origin.file,origin.requestId,origin.sourceMessage)!==true))return;
  run('attachments.toggle',{id,sha256,selected,key:origin.draft.key,epoch:origin.draft.epoch,revision:input.revision,pickerRequestId:origin.requestId});
 };
 const fileDraftConversationLoaded=(native:Json,conversationId:string,earlierRead?:string)=>{
  // Selecting a conversation can publish a blank composer before its original history is readable.
  const read=obj(obj(native.moduleResultInputs)['assistant.get']);
  return !!read._requestId&&read._requestId!==earlierRead&&read.conversationId===conversationId&&
   obj(obj(native.moduleResults)['assistant.get']).conversationId===conversationId&&
   !asArray<string>(native.moduleBusy).includes('assistant.get')&&!obj(native.moduleErrors)['assistant.get'];
 };
 const resultTopicOf=(native:ReturnType<typeof bridge.getState>):FileResultTopic|undefined=>{
  const view=obj(native.state.attachmentWorkspace),taskId=view.taskId,owner=obj(native.view);
  if(view.open!==true||!taskId||!can('composer.object.add')||owner.section)return;
  let conversationId:string|null=null;
  if(view.resultFilesOnly===true){
   const origin=resultFileReturnFocus.current;
   if(!origin||!('conversationId' in origin)||owner.taskId||origin.result.taskId!==taskId||native.state.conversationId!==origin.conversationId)return;
   const reader=origin.reader&&fileResultReader(native.state,origin.reader);
   if(origin.reader?view.historyResultOnly!==true||!reader||native.state.chatSubmitting||!can('conversation.reader.close')||!can('conversation.select'):!!native.state.conversationReader)return;
   const matching=asArray<Json>(reader?reader.messages:native.state.messages).filter(row=>row.id===origin.resultMessageId&&(!origin.reader||row.sourceRevision===origin.reader.sourceRevision));
   const source=matching.length===1?messageTaskResult(matching[0]):null;
   if(source?.deliveryId!==origin.result.deliveryId||source.taskId!==taskId||source.runId!==origin.result.runId||source.epoch!==origin.result.epoch||source.status!=='completed')return;
   conversationId=origin.conversationId;
  }else if(owner.taskId===taskId&&openedTaskId.current===taskId){
   const work=asArray<Task>(native.state.tasks).find(task=>task.id===taskId);
   if(!work||work.raw?.contextAvailable===false||work.raw?.contextWithdrawn===true||work.raw?.taskProjection==='source_unavailable')return;
   // This existing original-request hint permits navigation only, not a draft mutation.
   conversationId=originatingRequestConversation(work);
  }
  if(conversationId)return {taskId,conversationId,label:conversations.find(row=>row.id===conversationId)?.title||'原话题'};
 };
 const fileResultTopic=snapshot&&resultTopicOf(snapshot);
 // A received-file receipt is not a draft grant. Only the normal original Work
 // reader may keep an exact file location in its already-open private draft.
 const receivedTextReader=(native:ReturnType<typeof bridge.getState>,topic:FileResultTopic)=>{
  const {state}=native,view=obj(state.attachmentWorkspace),scope=obj(view.snapshot),file=obj(view.file);
  const reader=obj(view.originalFile),range=obj(reader.information),input=obj(native.phoneDraft);
  if(route!=='chat'||intent!=='chat'||returningConversation||draftSwitchPending()||
     state.conversationId!==topic.conversationId||!fileDraftConversationLoaded(state,topic.conversationId)||
     input.phone!==false||input.channelOrigin!==false||state.chatAttachmentPickerOpen!==false||
     state.chatSubmitting||state.phoneSubmitting||phoneAction.current||phoneReviewPending||
     view.taskId!==topic.taskId||view.open!==true||view.ready!==true||view.canSaveOriginal!==true||view.busy!==false||view.exportPending!==false||
     view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||view.resultFilesOnly!==false||
     view.historyResultOnly!==false||view.reportResultOnly!==false||view.choosingSource!==false||
     view.documentDraft||view.documentOpen||view.processingOpen||view.comparison||
     typeof view.windowId!=='string'||!view.windowId)return;
  const parent=readFileWorkParent(topic.taskId,native),pin=exactFileTarget(file);
  if(!parent||parent.layer!=='overview'||!pin||scope.taskId!==topic.taskId||scope.taskStatus!=='completed'||
     scope.taskRunId!==parent.work.runId||scope.coordinatorEpoch!==parent.work.epoch||
     !ordinaryChatWorkRow(asArray<Task>(state.tasks).find(row=>row.id===topic.taskId),topic.conversationId,true)||
     typeof file.name!=='string'||!/\.txt$/i.test(file.name)||!Number.isSafeInteger(file.size)||file.size<1||
     reader.kind!=='text'||reader.sourceUnavailable!==false||reader.error||reader.sha256!==pin.sha256||
     reader.mimeType!==file.mimeType||reader.size!==file.size||typeof reader.id!=='string'||!reader.id||
     typeof reader.identity!=='string'||!/^[a-f0-9]{64}$/.test(reader.identity)||
     range.sha256!==pin.sha256||range.sourceIdentity!==reader.identity||typeof range.content!=='string'||!range.content)return;
  const operation=file.sourceOperationId;
  if(typeof operation!=='string'||!/^[a-f0-9-]{36}$/.test(operation))return;
  const received=(row:Json)=>row.id===pin.fileId&&row.sha256===pin.sha256&&row.mimeType===file.mimeType&&row.size===file.size&&
   row.role==='result'&&row.availability==='available'&&row.sourceKind==='device_received_file'&&
   row.sourceRunEvidence==='original_device_file_receive'&&row.runProvenance==='current'&&row.sourceTaskId===topic.taskId&&
   row.sourceTaskRunId===parent.work.runId&&row.sourceCoordinatorEpoch===parent.work.epoch&&row.sourceOperationId===operation;
  const rows=asArray<Json>(scope.artifacts).filter(row=>row?.id===pin.fileId);
  if(!received(file)||rows.length!==1||!received(rows[0]))return;
  return {parent,pin,identity:JSON.stringify([view.windowId,reader.id,reader.identity,operation])};
 };
 const keepResultReadingDraft=snapshot&&fileResultTopic&&fileDraftTarget?.conversationId===fileResultTopic.conversationId&&
  fileDraftTarget?.key===`conversation:${fileResultTopic.conversationId}`&&receivedTextReader(snapshot,fileResultTopic)?fileDraftTarget:undefined;
 function resultAttachmentWork(native:ReturnType<typeof bridge.getState>,topic:Pick<FileResultTopic,'taskId'|'conversationId'>,detail=false){
  const works=asArray<Task>(native.state.tasks).filter(row=>row.id===topic.taskId),work=works[0],raw=obj(work?.raw);
  if(works.length!==1||work.status!=='completed'||raw.id!==topic.taskId||raw.kind!=='assistant'||raw.actorId!=='owner'||raw.spaceId!=='private'||
     raw.origin!=null||raw.teamRunId!=null||raw.teamAssignment!=null||raw.memberId!=null||raw.parentTaskId!=null||
     raw.directWorkspace!=null||raw.directPhone!=null||raw.ownerDeviceWork!=null||
     raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.taskProjection==='source_unavailable'||
     detail&&raw.taskProjection!=='detail_window'||raw.conversationId!==topic.conversationId||originatingRequestConversation(work)!==topic.conversationId||
     typeof raw.runId!=='string'||!raw.runId||!Number.isSafeInteger(raw.coordinatorEpoch)||raw.coordinatorEpoch<1)return;
  return {runId:String(raw.runId),epoch:Number(raw.coordinatorEpoch)};
 }
 const resultAttachmentSource=(native:ReturnType<typeof bridge.getState>,topic:FileResultTopic)=>{
  const view=obj(native.state.attachmentWorkspace),shown=obj(native.view),scope=obj(view.snapshot),file=obj(view.file),input=obj(native.phoneDraft);
  if(!taskOpen||taskOpening||document.hidden||intent!=='chat'||input.phone!==false||input.channelOrigin!==false||native.state.conversationReader||
     shown.section!=null||shown.taskId!==topic.taskId||openedTaskId.current!==topic.taskId||view.taskId!==topic.taskId||
     view.open!==true||view.ready!==true||view.busy||view.exportPending||view.canSaveOriginal!==true||view.documentDraft||view.processingOpen||view.comparison||
     view.group||view.team||view.deviceOriginalOnly||view.reportResultOnly||view.historyResultOnly||
     typeof view.windowId!=='string'||!view.windowId||file.role!=='result'||file.sourceTaskId!==topic.taskId||
     typeof file.id!=='string'||!file.id||typeof file.sha256!=='string'||!(/^[a-f0-9]{64}$/).test(file.sha256)||
     file.availability!=='available'||['device_received_file','remote_result_snapshot'].includes(file.sourceKind)||
     obj(view.originalFile).sha256!==file.sha256||scope.taskId!==topic.taskId||scope.taskStatus!=='completed')return;
  const work=resultAttachmentWork(native,topic,true);
  if(!work||scope.taskRunId!==work.runId||scope.coordinatorEpoch!==work.epoch)return;
  const currentRunFile=file.runProvenance==='current'&&file.sourceTaskRunId===work.runId&&
   (file.sourceCoordinatorEpoch==null||file.sourceCoordinatorEpoch===work.epoch);
  if(!currentRunFile){
   // A native owner-saved PDF excerpt belongs to this Work, not to a model run.
   // The current run above only protects the return to this Work's original draft.
   const extract=(row:Json)=>row.sourceKind==='document_extract'&&row.runProvenance==='unrecorded'&&
    row.sourceTaskRunId==null&&row.sourceCoordinatorEpoch==null&&row.sourceOperationId==null&&row.sourceJobId==null&&
    row.role==='result'&&row.availability==='available'&&row.sourceTaskId===topic.taskId&&row.mimeType==='text/plain';
   if(!extract(file)||!Number.isSafeInteger(file.size)||file.size<1||file.size>128*1024)return;
   const original=obj(view.originalFile),parent=exactFileTarget({id:file.sourceAttachmentId,sha256:file.sourceSha256});
   if(original.mimeType!==file.mimeType||original.size!==file.size||!parent||parent.fileId===file.id)return;
   const saved=asArray<Json>(scope.artifacts).filter(row=>row?.id===file.id);
   const sources=asArray<Json>(scope.sources).filter(row=>row?.id===parent.fileId);
   if(saved.length!==1||sources.length!==1||!extract(saved[0])||saved[0].sha256!==file.sha256||saved[0].size!==file.size||
      saved[0].sourceAttachmentId!==parent.fileId||saved[0].sourceSha256!==parent.sha256||
      sources[0].role!=='source'||sources[0].availability!=='available'||sources[0].mimeType!=='application/pdf'||
      sources[0].sha256!==parent.sha256||!Number.isSafeInteger(sources[0].size)||sources[0].size<1)return;
  }
  return {...work,windowId:String(view.windowId),fileId:String(file.id),sha256:String(file.sha256)};
 };
 const resultAttachmentAvailable=!!fileResultTopic&&!!resultAttachmentSource(snapshot,fileResultTopic)&&
  ['attachments.openPicker','attachments.closePicker','attachments.toggle','conversation.select','tasks.close','composer.edit'].every(can);
 const noteDraftEmpty=(native:ReturnType<typeof bridge.getState>,draft:FileDraftTarget)=>{
  const editor=obj(native.composer),owner=native.state,input=obj(native.phoneDraft);
  return editor.ready===true&&editor.key===draft.key&&editor.epoch===draft.epoch&&editor.revision===draft.revision&&editor.saving===false&&
   editor.conversationId===draft.conversationId&&owner.conversationId===draft.conversationId&&editor.text===''&&chatDraft.currentValue()===''&&
   editor.voiceText===''&&editor.voiceInputId===''&&input.phone===false&&input.channelOrigin===false&&
   !owner.conversationReader&&!owner.queuedInputsPage&&!owner.filePath&&!owner.chatBusy&&!owner.chatSubmitting&&!owner.phoneSubmitting&&
   !owner.listening&&!obj(owner.voiceInput).active&&
   owner.chatAttachmentPickerOpen===false&&Array.isArray(editor.pending)&&editor.pending.every((row:Json)=>row?.draftKey!==draft.key)&&
   [editor.sourceRefs,editor.previewRefs,editor.objectRefs,owner.selectedChatAttachments].every(items=>Array.isArray(items)&&items.length===0);
 };
 const fileNoteLeaveFree=()=>!draftSwitchPending()&&!workSupplementBeforeLeave.current&&!projectDraftBeforeLeave.current;
 // An owner-selected input has a native Work source entry, not a producing run.
 // This only locates its separately saved note; it is not a file attachment or read grant.
 const localInputNoteSource=(native:ReturnType<typeof bridge.getState>,topic:FileResultTopic)=>{
  const state=native.state,view=obj(state.attachmentWorkspace),shown=obj(native.view),scope=obj(view.snapshot),
   file=obj(view.file),original=obj(view.originalFile),pin=exactFileTarget(file);
  const work=resultAttachmentWork(native,topic,true);
  if(!work||!ordinaryChatWorkRow(asArray<Task>(state.tasks).find(row=>row.id===topic.taskId),topic.conversationId,true)||
     !taskOpen||taskOpening||document.hidden||intent!=='chat'||shown.section!==null||shown.taskId!==topic.taskId||
     openedTaskId.current!==topic.taskId||view.taskId!==topic.taskId||view.open!==true||view.ready!==true||view.busy!==false||
     view.exportPending!==false||view.canSaveOriginal!==true||view.fromWorkResult!==true||view.originalReturn!=='entry'||view.restoredWorkReturn!==false||
     view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||view.resultFilesOnly!==false||
     view.reportResultOnly!==false||view.historyResultOnly!==false||view.choosingSource!==false||
     view.documentDraft||view.documentOpen||view.processingOpen||view.comparison||typeof view.windowId!=='string'||!view.windowId||
     scope.taskId!==topic.taskId||scope.taskStatus!=='completed'||scope.taskRunId!==work.runId||scope.coordinatorEpoch!==work.epoch||
     !pin||typeof file.mimeType!=='string'||!file.mimeType||!Number.isSafeInteger(file.size)||file.size<0||
     typeof original.id!=='string'||!original.id||typeof original.identity!=='string'||!/^[a-f0-9]{64}$/.test(original.identity)||
     original.sourceUnavailable!==false||original.busy!==false||original.error||original.sha256!==pin.sha256||original.mimeType!==file.mimeType||original.size!==file.size)return;
  const selected=(row:Json)=>row.id===pin.fileId&&row.sha256===pin.sha256&&row.role==='source'&&row.availability==='available'&&
   row.sourceKind==='content_uri'&&row.mimeType===file.mimeType&&row.size===file.size&&row.sourceTaskId===file.sourceTaskId&&
   (row.sourceTaskId==null||row.sourceTaskId===topic.taskId)&&row.sourceTaskRunId==null&&row.sourceCoordinatorEpoch==null&&row.sourceOperationId==null;
  const sources=asArray<Json>(scope.sources).filter(row=>row?.id===pin.fileId);
  if(sources.length!==1||!selected(file)||!selected(sources[0]))return;
  return {...work,windowId:view.windowId,...pin};
 };
 const fileNoteTarget=(native:ReturnType<typeof bridge.getState>,topic:FileResultTopic)=>{
  if(route!=='chat'||returningConversation||!fileNoteLeaveFree()||!can('tasks.close')||!can('composer.edit'))return;
  const source=resultAttachmentSource(native,topic)??localInputNoteSource(native,topic),parent=source&&readFileWorkParent(topic.taskId,native);
  const draft=fileDraftFor(obj(native.composer),topic.conversationId,topic.label);
  if(!source||!parent||parent.layer!=='overview'||!draft||draft.key!==`conversation:${topic.conversationId}`||
     draft.revision!==ordinaryDraftRevision||!noteDraftEmpty(native,draft))return;
  const shown=obj(native.state.attachmentWorkspace),note=obj(shown.continuationDraft);
  if(note.available!==true||note.saved!==true||note.taskId!==topic.taskId||note.fileId!==source.fileId||note.sha256!==source.sha256||
     !Number.isSafeInteger(note.revision)||note.revision<2||typeof note.text!=='string'||!note.text.trim()||note.text.length>2000)return;
  return {source,parent,draft,note,frameId:shown.frameId};
 };
 const adoptFileNote=async(topic:FileResultTopic,note:SavedFileNote,frame:AttachmentFrame,closeOriginal:()=>Promise<unknown>)=>{
  const admitted=fileNoteTarget(bridge.getState(),topic);
  const unchanged='原文件或原话题草稿已变化，备注仍保留在这份文件。';
  if(!admitted||frame.windowId!==admitted.source.windowId||frame.frameId!==admitted.frameId||
     (['taskId','fileId','sha256','revision','text'] as const).some(key=>note[key]!==admitted.note[key]))throw new Error(unchanged);
  // A mounted policy surface can be idle. Honor its existing leave guard without queuing this handoff.
  if(guardTaskLeave(()=>{}))throw new Error(unchanged);
  const {draft,parent,source}=admitted,request=reviewRequest.current;
  const view=()=>obj(bridge.getState().state.attachmentWorkspace);
  const sameChat=()=>{
   const live=bridge.getState(),owner=obj(live.view),editor=obj(live.composer);
   return !document.hidden&&bridge.getDocumentEpoch()===parent.owner.documentEpoch&&navigationWindow.current===parent.navigation&&
    owner.section===null&&owner.resumeRevision===parent.owner.resumeRevision&&live.state.conversationId===draft.conversationId&&
    !live.state.conversationReader&&editor.key===draft.key&&editor.epoch===draft.epoch;
  };
  const sameWork=()=>sameChat()&&fileNoteLeaveFree()&&reviewRequest.current===request&&stillFileWorkParent(parent)&&noteDraftEmpty(bridge.getState(),draft);
  const destination=()=>sameChat()&&!draftSwitchPending()&&reviewRequest.current===request+1&&obj(bridge.getState().view).taskId===null&&view().open===false;
  let editRequested=false;
  try{
   const closed=obj(await closeOriginal());
   if(closed.accepted!==true||obj(closed.workspace).open!==false)throw new Error(unchanged);
   if(!await awaitPickerProjection(()=>sameWork()&&(view().open!==true||view().windowId===source.windowId),()=>view().open===false?true:undefined)||
      !sameWork()||guardTaskLeave(()=>{})||!await closeTask())throw new Error(unchanged);
   const landing=()=>sameChat()&&!draftSwitchPending()&&reviewRequest.current===request+1&&
    [null,topic.taskId].includes(obj(bridge.getState().view).taskId)&&noteDraftEmpty(bridge.getState(),draft);
   if(!await awaitPickerProjection(landing,()=>destination()?true:undefined)||!destination()||!noteDraftEmpty(bridge.getState(),draft))throw new Error(unchanged);
   const original=resultAttachmentWork(bridge.getState(),topic);
   if(original?.runId!==source.runId||original.epoch!==source.epoch)throw new Error(unchanged);
   editRequested=true;
   await bridge.command('composer.edit',{key:draft.key,epoch:draft.epoch,expectedRevision:draft.revision,text:note.text});
   const observed=await awaitPickerProjection(destination,()=>{
    const editor=obj(bridge.getState().composer);
    return editor.revision>draft.revision?editor.ready===true&&editor.text===note.text:undefined;
   });
   if(!observed)throw new Error(unchanged);
   setLocalStatus('已将备注放入原话题草稿。没有附加文件，也没有发送。');
   requestAnimationFrame(()=>{if(destination())workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
  }catch(error){
   if(view().windowId===frame.windowId&&view().open===true)throw error;
   if(sameChat()&&view().open===false)setLocalError(editRequested?'尚未确认备注是否放入草稿，请核对原输入。原备注仍保留，没有发送。':unchanged);
  }
 };
 // This is the saved output under a current reply, not a message attachment or a read from history.
 const liveChatResultAttachmentSource=(native:ReturnType<typeof bridge.getState>,topic:FileResultTopic)=>{
  const origin=resultFileReturnFocus.current,view=obj(native.state.attachmentWorkspace),scope=obj(view.snapshot),shown=obj(native.view);
  if(route!=='chat'||!canChooseFileForDraft||draftSwitchPending()||!can('composer.edit')||!origin||!('conversationId' in origin)||origin.reader||
     origin.conversationId!==topic.conversationId||origin.result.taskId!==topic.taskId||origin.result.kind!=='owner_work'||
     origin.result.status!=='completed'||origin.result.noticeKind||!Number.isSafeInteger(origin.resume)||shown.resumeRevision!==origin.resume||
     reviewRequest.current!==origin.taskRequest||native.state.queuedInputsPage||native.state.filePath||
     view.open!==true||view.ready!==true||view.busy||view.exportPending||view.documentDraft||view.processingOpen||view.comparison||
     view.resultFilesOnly!==true||view.fromWorkResult!==false||view.historyResultOnly!==false||view.reportResultOnly!==false||
     view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||view.canSaveOriginal!==true||typeof view.windowId!=='string'||!view.windowId||
     view.taskId!==topic.taskId||scope.taskId!==topic.taskId||scope.taskStatus!=='completed'||
     scope.taskRunId!==origin.result.runId||scope.coordinatorEpoch!==origin.result.epoch)return;
  const draft=fileDraftFor(obj(native.composer),topic.conversationId,topic.label);
  if(!draft||draft.key!==`conversation:${topic.conversationId}`||draft.revision!==ordinaryDraftRevision||
     !filePickerDraftCurrent(draft,origin.document,origin.navigation))return;
  const file=obj(view.file),preview=obj(view.originalFile),pin=exactFileTarget(file);
  if(!pin||typeof file.sourceKind!=='string'||!file.sourceKind||['device_received_file','remote_result_snapshot'].includes(file.sourceKind)||
     typeof file.mimeType!=='string'||!file.mimeType||!Number.isSafeInteger(file.size)||file.size<0||
     preview.sha256!==pin.sha256||preview.mimeType!==file.mimeType||preview.size!==file.size)return;
  const listed=asArray<Json>(scope.artifacts).filter(row=>row?.id===pin.fileId);
  const exact=(row:Json)=>row.sha256===pin.sha256&&row.role==='result'&&row.availability==='available'&&row.sourceTaskId===topic.taskId&&
   row.runProvenance==='current'&&row.sourceTaskRunId===origin.result.runId&&(row.sourceCoordinatorEpoch==null||row.sourceCoordinatorEpoch===origin.result.epoch)&&
   row.sourceKind===file.sourceKind&&row.mimeType===file.mimeType&&row.size===file.size;
  if(listed.length!==1||!exact(file)||!exact(listed[0]))return;
  const source:SavedAttachmentMessage={id:origin.resultMessageId,revision:origin.revision,resume:origin.resume,taskRequest:origin.taskRequest,
   liveResult:{result:origin.result,mimeType:file.mimeType,size:file.size,kind:file.sourceKind}};
  if(liveResultMessageCurrent(native,topic.conversationId,source))return {source,draft,pin,windowId:view.windowId,documentEpoch:origin.document,navigation:origin.navigation};
 };
 const liveChatResultAttachmentAvailable=!!fileResultTopic&&!!liveChatResultAttachmentSource(snapshot,fileResultTopic);
 const chooseLiveChatResultAttachment=async(file:OriginalAttachmentChoice,topic:FileResultTopic,stillOpen:()=>boolean,closeOriginal:()=>Promise<unknown>)=>{
  const admitted=liveChatResultAttachmentSource(bridge.getState(),topic);
  const changed='原回复、文件或草稿已变化，没有加入附件。';
  if(!admitted||admitted.pin.fileId!==file.fileId||admitted.pin.sha256!==file.sha256||!stillOpen())throw new Error(changed);
  const {source,draft,documentEpoch,navigation,windowId}=admitted;
  const sameDraft=()=>!draftSwitchPending()&&filePickerDraftCurrent(draft,documentEpoch,navigation)&&
   bridge.getState().composer?.revision===draft.revision&&obj(bridge.getState().view).resumeRevision===source.resume;
  const result=()=>obj(bridge.getState().state.attachmentWorkspace);
  const closed=obj(await closeOriginal());
  if(closed.accepted!==true||obj(closed.workspace).open!==false)throw new Error(changed);
  // The reply stays in the same live editor. Wait for the file window to leave before showing the scoped picker.
  const didClose=await awaitPickerProjection(()=>sameDraft()&&reviewRequest.current===source.taskRequest&&
   (result().open!==true||result().windowId===windowId),()=>result().open===false?true:undefined);
  const current=()=>sameDraft()&&result().open===false&&savedMessageAttachmentCurrent(draft,source,file);
  try{
   if(!didClose||!current())throw new Error(changed);
   await startFileAttachmentPicker(file,draft,current,undefined,source);
   return true;
  }catch(error){
   if(sameDraft()&&reviewRequest.current===source.taskRequest&&result().open===false)
    setLocalError(error instanceof Error?error.message:'没有打开这份文件的附件选择，草稿保留。');
   return false;
  }
 };
 const historicalAttachmentMessage=(native:Json,history:HistoryResultOrigin,id:string,result:TaskResult)=>{
  const reader=fileResultReader(native,history);
  return reader?.originalReportMessageId===null&&!reader.workRequest&&
   messageTaskResult(resultMessageIn(reader.messages,id,result,history.sourceRevision))?.kind==='owner_work';
 };
 const historicalAttachmentDraftCurrent=(native:ReturnType<typeof bridge.getState>,draft:FileDraftTarget,
  documentEpoch:number,navigation:number,source:SavedAttachmentMessage)=>{
  const {state:owner,composer:input}=native,shown=obj(native.view),phone=obj(native.phoneDraft);
  return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&navigationWindow.current===navigation&&
   shown.section===null&&shown.taskId===null&&shown.resumeRevision===source.resume&&reviewRequest.current===source.taskRequest&&
   !!source.historyResult&&owner.traceSourceRevision===source.historyResult.sourceGeneration&&!draftSwitchPending()&&
   owner.filePath===null&&owner.queuedInputsPage===null&&owner.chatBusy===false&&owner.chatSubmitting===false&&owner.phoneSubmitting===false&&
   phone.phone===false&&phone.channelOrigin===false&&owner.conversationId===draft.conversationId&&
   input?.ready===true&&input.key===draft.key&&input.epoch===draft.epoch&&input.revision===draft.revision&&input.conversationId===draft.conversationId;
 };
 const historyChatResultAttachmentSource=(native:ReturnType<typeof bridge.getState>,topic:FileResultTopic)=>{
  const origin=resultFileReturnFocus.current,owner=native.state,shown=obj(native.view);
  const view=obj(owner.attachmentWorkspace),scope=obj(view.snapshot),file=obj(view.file),viewer=obj(view.originalFile);
  if(route!=='chat'||intent!=='chat'||taskOpen||pendingHere||!origin||!('conversationId' in origin)||!origin.reader||
     origin.conversationId!==topic.conversationId||origin.reader.conversationId!==topic.conversationId||
     origin.result.taskId!==topic.taskId||origin.result.kind!=='owner_work'||origin.result.status!=='completed'||origin.result.noticeKind||
     origin.reader.sourceRevision!==origin.revision||!Number.isSafeInteger(origin.resume)||shown.resumeRevision!==origin.resume||
     !Number.isSafeInteger(owner.traceSourceRevision)||owner.traceSourceRevision<0||
     owner.traceSourceRevision!==origin.historySourceGeneration||owner.chatAttachmentPickerOpen!==false||
     !['composer.edit','conversation.reader.close','attachments.openPicker','attachments.closePicker','attachments.toggle'].every(can)||
     !historicalAttachmentMessage(owner,origin.reader,origin.resultMessageId,origin.result)||
     view.open!==true||view.ready!==true||view.busy||view.exportPending||view.documentDraft||view.processingOpen||view.comparison||
     view.resultFilesOnly!==true||view.fromWorkResult!==false||view.historyResultOnly!==true||view.reportResultOnly!==false||
     view.group!==false||view.team!==false||view.deviceOriginalOnly!==false||view.canReferenceHistoryOriginal!==true||
     typeof view.windowId!=='string'||!view.windowId||view.taskId!==topic.taskId||scope.taskId!==topic.taskId||scope.taskStatus!=='completed'||
     scope.taskRunId!==origin.result.runId||scope.coordinatorEpoch!==origin.result.epoch)return;
  const draft=fileDraftFor(obj(native.composer),topic.conversationId,topic.label),pin=exactFileTarget(file);
  if(!draft||draft.key!==`conversation:${topic.conversationId}`||draft.revision!==ordinaryDraftRevision||!pin||
     !(file.sourceKind==='created_text'&&file.mimeType==='text/plain'||file.sourceKind==='created_document'&&file.mimeType==='application/pdf')||
     !Number.isSafeInteger(file.size)||file.size<1||viewer.sourceUnavailable!==false||viewer.busy!==false||
     viewer.sha256!==pin.sha256||viewer.mimeType!==file.mimeType||viewer.size!==file.size)return;
  const exact=(row:Json)=>row.sha256===pin.sha256&&row.role==='result'&&row.availability==='available'&&row.sourceTaskId===topic.taskId&&
   row.runProvenance==='current'&&row.sourceTaskRunId===origin.result.runId&&(row.sourceCoordinatorEpoch==null||row.sourceCoordinatorEpoch===origin.result.epoch)&&
   row.sourceKind===file.sourceKind&&row.mimeType===file.mimeType&&row.size===file.size;
  const listed=asArray<Json>(scope.artifacts).filter(row=>row?.id===pin.fileId);
  if(listed.length!==1||!exact(file)||!exact(listed[0]))return;
  const source:SavedAttachmentMessage={id:origin.resultMessageId,revision:origin.revision,resume:origin.resume,taskRequest:origin.taskRequest,
   historyResult:{result:origin.result,mimeType:file.mimeType,size:file.size,kind:file.sourceKind,sourceGeneration:owner.traceSourceRevision}};
  if(historicalAttachmentDraftCurrent(native,draft,origin.document,origin.navigation,source))
   return {source,draft,pin,history:origin.reader,windowId:view.windowId,documentEpoch:origin.document,navigation:origin.navigation,result:origin.result};
 };
 const historyChatResultAttachmentAvailable=!!fileResultTopic&&!!historyChatResultAttachmentSource(snapshot,fileResultTopic);
 const chooseHistoryChatResultAttachment=async(file:OriginalAttachmentChoice,topic:FileResultTopic,stillOpen:()=>boolean,closeOriginal:()=>Promise<unknown>)=>{
  const admitted=historyChatResultAttachmentSource(bridge.getState(),topic);
  const changed='原历史回复、文件或草稿已变化，没有加入附件。';
  if(!admitted||admitted.pin.fileId!==file.fileId||admitted.pin.sha256!==file.sha256||!stillOpen())throw new Error(changed);
  const {source,draft,history,windowId,documentEpoch,navigation,result}=admitted;
  const sameDraft=()=>historicalAttachmentDraftCurrent(bridge.getState(),draft,documentEpoch,navigation,source);
  const sameHistory=()=>historicalAttachmentMessage(bridge.getState().state,history,source.id,result);
  const fileView=()=>obj(bridge.getState().state.attachmentWorkspace);
  const fileClosed=()=>fileView().open===false;
  try{
   const closed=obj(await closeOriginal());
   if(closed.accepted!==true||obj(closed.workspace).open!==false)throw new Error(changed);
   if(!await awaitPickerProjection(()=>sameDraft()&&sameHistory()&&(fileClosed()||fileView().windowId===windowId),
      ()=>fileClosed()?true:undefined)||!fileClosed()||!sameDraft()||!sameHistory())throw new Error(changed);
   // Close only the held read-only history. The underlying composer is already
   // the destination; selecting a conversation here would replace its epoch.
   const readerClosed=()=>bridge.getState().state.conversationReader===null;
   const receipt=await bridge.command('conversation.reader.close',{window:history.window,conversationId:history.conversationId});
   if(receipt?.accepted!==true||!await awaitPickerProjection(()=>sameDraft()&&fileClosed()&&(readerClosed()||sameHistory()),
      ()=>readerClosed()?true:undefined))throw new Error(changed);
   const current=()=>sameDraft()&&fileClosed()&&readerClosed()&&savedMessageAttachmentCurrent(draft,source,file);
   if(!current())throw new Error(changed);
   await startFileAttachmentPicker(file,draft,current,undefined,source);
   return true;
  }catch(error){
   if(stillOpen())throw error;
   if(sameDraft()&&fileClosed())setLocalError(error instanceof Error?error.message:'未打开原文件的附件选择，草稿保留。');
   return false;
  }
 };
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
 const matchedDraftFile=(draft:Json,reference:Json)=>asArray<Json>(draft.objectRefs).some(ref=>
  ref.kind===reference.kind&&ref.id===reference.id&&ref.sha256===reference.sha256);
 const showFileDraft=async(reference:Json,target:FileDraftTarget,closeOriginal:()=>Promise<unknown>)=>{
  const documentEpoch=bridge.getDocumentEpoch(),originalNavigation=navigationWindow.current;
  const stillAttached=()=>{
   if(bridge.getDocumentEpoch()!==documentEpoch)return false;
   const draft=currentFileDraft(target);
   return !!draft&&Number.isSafeInteger(draft.revision)&&draft.revision>=target.revision&&matchedDraftFile(draft,reference);
  };
  if(!stillAttached())throw new Error('原草稿中已找不到这份文件，请在原件中重新关联。');
  const native=bridge.getState().state;
  if(native.chatSubmitting||native.phoneSubmitting||phoneAction.current||phoneReviewPending)throw new Error('输入正在提交，文件仍留在原话题草稿。稍后再打开草稿。');
  const destination=target.conversationId&&!native.conversationReader?'chat':'butler';
  const stillLanding=()=>stillAttached()&&(destination!=='chat'||!bridge.getState().state.conversationReader);
  const canReturn=()=>navigationWindow.current===originalNavigation&&stillLanding();
  await closeOriginal();
  if(!canReturn())return false;
  if((route!=='chat'||destination!=='chat'||taskOpen)&&!await navigate(destination,undefined,{stillCurrent:canReturn}))return false;
  const returnedNavigation=navigationWindow.current,atDraft=()=>navigationWindow.current===returnedNavigation&&stillLanding();
  if(!atDraft())return false;
  if(intent!=='chat'&&!await changeIntent('chat'))return false;
  if(!atDraft())return false;
  setLocalStatus('原草稿中的文件位置仍保留。这次未改动附件，也未发送。');
  requestAnimationFrame(()=>{if(atDraft())workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
  return true;
 };
 const useFileInOriginalTopic=async(reference:Json,topic:FileResultTopic,openOnly:boolean,stillOpen:()=>boolean,closeOriginal:()=>Promise<unknown>,keepReading?:FileDraftTarget)=>{
  const initial=bridge.getState(),documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const {taskId,conversationId,label}=topic;
  const origin=resultFileReturnFocus.current;
  const history=initial.state.attachmentWorkspace?.historyResultOnly===true&&origin&&'reader' in origin?origin.reader:undefined;
  const foreground=()=>!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&navigationWindow.current===navigation;
  const current=()=>{
   const live=resultTopicOf(bridge.getState());
   return foreground()&&stillOpen()&&live?.taskId===taskId&&live.conversationId===conversationId;
  };
  if(!current())return false;
  if(initial.state.chatSubmitting||initial.state.phoneSubmitting||phoneAction.current||phoneReviewPending||returningConversation)
   throw new Error('请先等待当前输入处理完成。没有发送这份文件。');
  const stage=async(target:FileDraftTarget,atDraft:()=>boolean)=>{
   const draft=currentFileDraft(target);
   if(!atDraft()||!draft||target.key!==`conversation:${conversationId}`||draft.revision!==target.revision)return false;
   if(matchedDraftFile(draft,reference))return true;
   if(openOnly)throw new Error('原草稿已不含这份文件，没有重新加入。');
   if(asArray<Json>(draft.objectRefs).some(ref=>ref.kind==='artifact'&&ref.id===reference.id))
    throw new Error('原草稿已关联另一个版本，没有替换。');
   return stageFileInDraft(reference,target,atDraft);
  };
  if(keepReading){
   const original=receivedTextReader(initial,topic);
   const changed='原文件、工作或原草稿已变化，文件位置尚未确认。';
   if(!original||reference.kind!=='artifact'||reference.id!==original.pin.fileId||reference.sha256!==original.pin.sha256||
      keepReading.conversationId!==conversationId||currentFileDraft(keepReading)?.revision!==keepReading.revision)throw new Error(changed);
   const sameWork=()=>foreground()&&!draftSwitchPending()&&stillFileWorkParent(original.parent)&&!!currentFileDraft(keepReading);
   const same=()=>current()&&sameWork()&&receivedTextReader(bridge.getState(),topic)?.identity===original.identity;
   if(!await stage(keepReading,same)||!same())return false;
   // A confirmed location changes only the draft. Keep this reader, range and scroll mounted.
   if(!openOnly)return true;
   return showFileDraft(reference,keepReading,async()=>{
    if(!same())throw new Error(changed);
    const closed=obj(await closeOriginal());
    if(closed.accepted!==true||obj(closed.workspace).open!==false||!sameWork())throw new Error(changed);
    return closed;
   });
  }
  if(initial.state.conversationId===conversationId&&(!initial.state.conversationReader||history)){
   const target=fileDraftFor(obj(initial.composer),conversationId,label);
   if(!target||obj(initial.state.attachmentWorkspace).resultFilesOnly!==true&&!fileDraftConversationLoaded(initial.state,conversationId))
    throw new Error('原话题尚未核对完成，文件仍留在原件。');
   if(!await stage(target,current)||!current())return false;
   if(history){
    // The attachment is already confirmed in this same live editor; closing a
    // read-only page can only reveal it, not select or write another draft.
    await closeOriginal();
    const holdsFile=()=>{const draft=currentFileDraft(target);return !!draft&&matchedDraftFile(draft,reference)};
    if(!foreground()||!holdsFile()||!fileResultReader(bridge.getState().state,history))return false;
    let shown=false;
    await returnToOriginalTaskConversation(taskId,conversationId,undefined,false,async atEditor=>{
     shown=atEditor()&&holdsFile();return shown;
    },history);
    return shown;
   }
   return showFileDraft(reference,target,closeOriginal);
  }
  if(openOnly)throw new Error('原话题草稿已变化，没有加入其他草稿。');
  if(!can('conversation.select'))throw new Error('原话题暂不可打开。没有加入其他草稿。');
  await closeOriginal();
  const after=bridge.getState();
  if(!foreground()||after.state.conversationId!==initial.state.conversationId||after.composer?.epoch!==initial.composer?.epoch||
   obj(after.view).taskId!==taskId||openedTaskId.current!==taskId)return false;
  let linked=false;
  await returnToOriginalTaskConversation(taskId,conversationId,undefined,false,async atEditor=>{
   const target=fileDraftFor(obj(bridge.getState().composer),conversationId,label);
   if(!target)throw new Error('原话题草稿还没有就绪。没有加入其他草稿。');
   linked=await stage(target,atEditor);
   return linked;
  });
  return linked;
 };
 const chooseFileInOriginalTopic=async(file:OriginalAttachmentChoice,topic:FileResultTopic,stillOpen:()=>boolean,closeOriginal:()=>Promise<unknown>)=>{
  const initial=bridge.getState(),source=resultAttachmentSource(initial,topic),documentEpoch=bridge.getDocumentEpoch();
  const navigation=navigationWindow.current,reviewTicket=reviewRequest.current,returnTicket=conversationReturnRequest.current;
  const resume=obj(initial.view).resumeRevision;
  const foreground=()=>!document.hidden&&bridge.getDocumentEpoch()===documentEpoch;
  const scopeError='原工作或草稿已变化，没有加入附件，也没有发送。';
  if(!resultAttachmentAvailable||!source||source.fileId!==file.fileId||source.sha256!==file.sha256||!stillOpen())throw new Error(scopeError);
  if(initial.state.chatSubmitting||initial.state.phoneSubmitting||phoneAction.current||phoneReviewPending||returningConversation)
   throw new Error('请先等待当前输入处理完成。没有加入附件。');
  const atSource=()=>foreground()&&navigationWindow.current===navigation&&reviewRequest.current===reviewTicket&&
   conversationReturnRequest.current===returnTicket&&openedTaskId.current===topic.taskId;
  const originalEditor=(native:ReturnType<typeof bridge.getState>)=>native.state.conversationId===initial.state.conversationId&&
   native.composer?.key===initial.composer?.key&&native.composer?.epoch===initial.composer?.epoch;
  const sameWork=(native:ReturnType<typeof bridge.getState>)=>{
   const view=obj(native.view),work=resultAttachmentWork(native,topic,!!view.taskId),input=obj(native.phoneDraft);
   const onRoute=native.state.conversationId===initial.state.conversationId?originalEditor(native):
    native.state.conversationId===topic.conversationId&&native.composer?.key===`conversation:${topic.conversationId}`;
   return foreground()&&view.section==null&&view.resumeRevision===resume&&(!view.taskId||view.taskId===topic.taskId)&&!native.state.conversationReader&&
    !native.state.chatSubmitting&&!native.state.phoneSubmitting&&onRoute&&input.phone===false&&input.channelOrigin===false&&
    work?.runId===source.runId&&work.epoch===source.epoch;
  };
  const closed=obj(await closeOriginal());
  if(closed.accepted!==true||obj(closed.workspace).open!==false)throw new Error(scopeError);
  // A close ACK can precede the next frame. Do not leave Work until the file
  // window has actually closed and the same Work still owns this handoff.
  const fileClosed=await new Promise<boolean>(resolve=>{
   let unsubscribe=()=>{};
   const finish=(ok:boolean)=>{clearTimeout(timer);unsubscribe();resolve(ok)};
   const timer=window.setTimeout(()=>finish(false),5000);
   const check=()=>{
    const after=bridge.getState(),shown=obj(after.state.attachmentWorkspace);
    if(!atSource()||!sameWork(after)||obj(after.view).taskId!==topic.taskId||
       !originalEditor(after)||after.composer?.revision!==initial.composer?.revision||
       shown.open===true&&shown.windowId!==source.windowId)finish(false);
    else if(shown.open===false)finish(true);
   };
   unsubscribe=bridge.subscribe(check);check();
  });
  if(!fileClosed||!atSource()){
   if(atSource())setLocalError(scopeError);
   return false;
  }
  const stillWork=()=>{
   const native=bridge.getState();
   return !native.state.attachmentWorkspace?.open&&sameWork(native)&&
    (reviewRequest.current===reviewTicket&&openedTaskId.current===topic.taskId||reviewRequest.current===reviewTicket+1&&!openedTaskId.current);
  };
  let opened=false;
  await returnToOriginalTaskConversation(topic.taskId,topic.conversationId,undefined,false,{chooseAttachment:async atEditor=>{
   const native=bridge.getState(),draft=fileDraftFor(obj(native.composer),topic.conversationId,topic.label);
   if(!draft||draft.key!==`conversation:${topic.conversationId}`||!atEditor()||!stillWork())throw new Error(scopeError);
   await startFileAttachmentPicker(file,draft,()=>atEditor()&&stillWork());
   opened=true;
  }},undefined,stillWork);
  return opened;
 };
 const openRegularAttachmentPicker=async()=>{
  const pending=fileAttachmentPickerLaunch.current,initial=bridge.getState();
  if(pending&&(!pending.settled||pending.reopening))return;
  const nativePickerOpen=initial.state.chatAttachmentPickerOpen;
  if(pending&&!pending.requestId&&(nativePickerOpen===true||nativePickerOpen===false)){
   const previous=initial.state.chatAttachmentPickerRequest,draft=fileDraftFor(obj(initial.composer),String(initial.state.conversationId||''),'');
   if(!draft||!isChatSurface||intent!=='chat'||draft.key!==draftKey||draft.epoch!==draftEpoch||
      (nativePickerOpen?(typeof previous!=='string'||!previous):previous!=null))return;
   const scope={launch:pending,draft,documentEpoch:bridge.getDocumentEpoch(),navigation:navigationWindow.current,resume:obj(initial.view).resumeRevision};
   const current=()=>ordinaryPickerScopeCurrent(scope)&&bridge.getState().composer?.revision===draft.revision;
   if(!current())return;
   // The user asked to open the ordinary picker here. Don't close or reveal
   // the unacknowledged request: only a separately published newly opened list counts.
   pending.reopening=true;setVoiceOptionsOpen(false);setLocalError('');
   try{
    const accepted=await bridge.command('attachments.openPicker',{key:draft.key,epoch:draft.epoch,...(nativePickerOpen?{}:{revision:draft.revision})});
    // A closed projection might predate the original command. In that case
    // require a named new native request, not whichever request appears first.
    const named=nativePickerOpen?null:accepted?.requestId;
    if(nativePickerOpen?accepted?.accepted!==true:typeof named!=='string'||!named)throw new Error('没有确认打开普通附件选择。');
    const requestId=await awaitPickerProjection(current,()=>{
     const native=bridge.getState().state,id=native.chatAttachmentPickerRequest,list=obj(obj(native.moduleResultInputs)['attachments.list']);
     if(native.chatAttachmentPickerOpen===true&&typeof id==='string'&&id&&(named!==null?id===named:id!==previous)&&list._requestId===id&&
        !asArray<string>(native.moduleBusy).includes('attachments.list'))return list._failed===true?'':id;
    });
    if(!current())return;
    if(!requestId||bridge.getState().state.chatAttachmentPickerRequest!==requestId)throw new Error('新附件清单尚未核对。没有改动草稿。');
    setReopenedAttachmentPicker({...scope,requestId});
   }catch(error){if(current())setLocalError(error instanceof Error?error.message:'普通附件选择尚未确认，草稿保留。');}
   finally{pending.reopening=false;}
   return;
  }
  // This is a separate explicit request, not a fallback from a focused miss.
  if(pending&&initial.state.chatAttachmentPickerOpen!==false)return;
  fileAttachmentPickerLaunch.current=null;
  if(reopenedAttachmentPicker)setReopenedAttachmentPicker(null);
  setVoiceOptionsOpen(false);
  run('attachments.openPicker',{key:draftKey,epoch:draftEpoch});
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
   if(request.originalWorkDraft){
     const selected=request.originalWorkDraft,origin=relatedOrigin.current,visit=origin?.teamDraft,teamId=request.teamId;
     if(route!=='teams'||!origin||!visit||!teamId||!/^[a-f0-9-]{36}$/.test(teamId))return;
     const at=(navigation:number)=>!document.hidden&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===visit.documentEpoch;
     const inSelection=()=>relatedOrigin.current===origin&&at(visit.navigation);
     const mayReturn=()=>{
       if(!inSelection())return false;
       const live=bridge.getState(),view=obj(live.view);
       if(!selected()||draftSwitchPending()||view.section!=='teams'||view.taskId!==null||view.resumeRevision!==visit.resume||!sameTeamDraftInput(visit,originalTeamDraft(live))){
         setLocalError(originalTeamDraftChanged);return false;
       }
       return true;
     };
     if(!mayReturn())return;
     setLocalError('');
     void navigate('chat',()=>{
       if(relatedOrigin.current===origin)relatedOrigin.current=origin.parent||null;
       const arrived=()=>at(visit.navigation+1);
       const here=(sameRevision=true)=>{
         const live=bridge.getState(),view=obj(live.view);
         return arrived()&&!draftSwitchPending()&&view.taskId===null&&view.resumeRevision===visit.resume&&
           sameTeamDraftInput(visit,originalTeamDraft(live,sameRevision?undefined:teamId),sameRevision);
       };
       const atChat=()=>here(false)&&obj(bridge.getState().view).section===null;
       void (async()=>{
         // Secondary already retires its native window; wait for that receipt, not a new read.
         const ready=await awaitPickerProjection(here,()=>obj(bridge.getState().view).section===null?true:undefined);
         if(!ready||!here()||!atChat()){if(arrived())setLocalError(originalTeamDraftChanged);return;}
         const added=await stageButlerObject({kind:'team',id:teamId},atChat,true);
         if(!atChat()){if(arrived())setLocalError(originalTeamDraftChanged);return;}
         if(!added)return;
         setLocalStatus('编组已关联到原对话草稿，未发送。');
         requestAnimationFrame(()=>{if(atChat())workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})});
       })().catch(error=>{if(arrived())setLocalError(atChat()&&error instanceof Error?error.message:originalTeamDraftChanged)});
     },{keepOrigin:true,stillCurrent:mayReturn});
     return;
   }
   if(state.chatSubmitting||state.phoneSubmitting||phoneAction.current||phoneReviewPending){setLocalError('请先等待当前输入处理完成，协作草稿仍保留。');return;}
   const goal=typeof request.goal==='string'?request.goal.trim():'';
   const original=!request.teamId&&!goal&&!draftSwitchPending()?emptyTeamGoalDraft(bridge.getState()):null;
   const beforeNavigation=navigationWindow.current,beforeDocument=bridge.getDocumentEpoch();
   void navigate('butler',()=>{
     const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
     const current=()=>navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden;
     const sameOriginal=original&&navigation===beforeNavigation+1&&documentEpoch===beforeDocument&&
       sameEmptyTeamGoal(original,emptyTeamGoalDraft(bridge.getState()));
     void changeIntent('chat').then(async changed=>{
       if(!changed||!current())return;
       // The existing intent command advances the native composer epoch once, even for chat → chat.
       // The starter can only follow that accepted transition, not another draft or an intervening edit.
       if(original&&sameOriginal)setTeamGoalStarter({...original,epoch:original.epoch+1,navigation,documentEpoch});
       if(request.teamId&&!await stageButlerObject({kind:'team',id:request.teamId},current))return;
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
 const openConversationTeamDecision=(locator:TeamWorkTarget,source:TeamDecisionOrigin)=>{
   const live=bridge.getState();
   if(document.hidden||route!=='chat'||taskOpen||live.state.conversationId!==source.conversationId||locator.runId!==source.teamRunId||!can('teams.readAttention'))return;
   if('openedFileDecision' in locator){
     const mark=locator.openedFileDecision;
     if(!can('teams.openWorkFileDecision')||!can('tasks.review'))return;
     openTask(mark.taskId,'review',true,undefined,undefined,{runId:mark.runId,epoch:mark.epoch},undefined,mark.approvalId);return;
   }
   const files='fileReview' in locator?{...source,fileReview:locator.fileReview}:undefined;
   const proposal='proposal' in locator?{...source,proposal:locator.proposal}:undefined;
   if(files&&!can('teams.readFrozenOutputs')||proposal&&!can('teams.readRun'))return;
   const origin={...captureWorkspaceOrigin(),focus:null,focusKey:proposal?teamProposalEntryKey(source):files?teamFileEntryKey(files):teamDecisionEntryKey(source),
     drawerChat:{id:source.conversationId,draftKey:String(live.composer?.key||''),documentEpoch:bridge.getDocumentEpoch()}};
   if(files||proposal){
     const returnToWork=(reason?:string)=>{if(document.hidden||relatedOrigin.current!==origin)return;void restoreWorkspace(origin).then(()=>{
       if(reason&&!document.hidden&&bridge.getState().state.conversationId===source.conversationId)setLocalError(reason);
     });};
     void openRelatedWorkspace('teams',origin,()=>{setTeamAttentionFocus(undefined);setTeamRunFocus({requestId:crypto.randomUUID(),runId:source.teamRunId,fromWork:proposal||files,returnToWork});});
   }else if('memberId' in locator) void openRelatedWorkspace('teams',origin,()=>{setTeamRunFocus(undefined);setTeamAttentionFocus({...locator,ownerWork:source,requestId:crypto.randomUUID()});});
 };
 const openProjectTeamReview=(source:ProjectTeamReviewOrigin)=>{
   const live=bridge.getState(),row=asArray<Task>(live.state.tasks).find(t=>t.id===source.parentTaskId),raw=row?.raw;
   if(document.hidden||!taskOpen||taskId!==source.parentTaskId||openedTaskId.current!==taskId||obj(live.view).taskId!==taskId||raw?.runId!==source.parentRunId||
      raw?.coordinatorEpoch!==source.parentEpoch||raw?.conversationId!==source.conversationId||raw?.developmentContext?.projectId!==source.projectId||
      raw?.developmentContext?.checkoutId!==source.checkoutId||!can('teams.readRun')||!can('teams.readFrozenOutputs')){setLocalError('原项目或执行轮次已经变化，没有打开替代工作。');return;}
   const origin:WorkspaceOrigin={...captureWorkspaceOrigin(),task:{id:source.parentTaskId,layer:taskLayer,expected:{runId:source.parentRunId,epoch:source.parentEpoch}}};
   const back=(reason?:string)=>{if(document.hidden||relatedOrigin.current!==origin)return;void restoreWorkspace(origin).then(()=>{if(reason&&!document.hidden)setLocalError(reason)});};
   void openRelatedWorkspace('teams',origin,()=>{setTeamAttentionFocus(undefined);setTeamRunFocus({requestId:crypto.randomUUID(),runId:source.teamRunId,fromProject:source,returnToWork:back})});
 };
 const openWorkTeam=(team:OwnerWorkTeam,openPackage=false)=>{
   if(!can('teams.readRun'))return;
   if(openPackage){
     const live=bridge.getState(),mark=team.deliveredPackage;
     const work=asArray<Task>(live.state.tasks).find(row=>row.id===team.parentTaskId),rows=ownerWorkPresentation(work)?.teams;
     const exact=rows?.find(row=>row.teamRunId===team.teamRunId&&row.taskId===team.taskId&&row.parentRunId===team.parentRunId
       &&row.conversationId===team.conversationId&&row.parentEpoch===team.parentEpoch);
     if(document.hidden||!taskOpen||taskId!==team.parentTaskId||openedTaskId.current!==taskId||obj(live.view).taskId!==taskId
       ||!can('teams.readFinalPackageFiles')||!mark||!exact||exact.cancelRequested||!sameTeamPackage(mark,exact.deliveredPackage)){
       setLocalError('原工作或签收包已变化，没有打开替代成果。');return;
     }
     const source={parentTaskId:team.parentTaskId,parentRunId:team.parentRunId,parentEpoch:team.parentEpoch,conversationId:team.conversationId,teamRunId:team.teamRunId,deliveredPackage:mark};
     const origin:WorkspaceOrigin={...captureWorkspaceOrigin(),task:{id:team.parentTaskId,layer:taskLayer,expected:{runId:team.parentRunId,epoch:team.parentEpoch}}};
     const returnToWork=(reason?:string)=>{if(document.hidden||relatedOrigin.current!==origin)return;
       void restoreWorkspace(origin).then(()=>{if(reason&&!document.hidden)setLocalError(reason)});};
     void openRelatedWorkspace('teams',origin,()=>{setTeamAttentionFocus(undefined);setTeamRunFocus({requestId:crypto.randomUUID(),runId:team.teamRunId,fromWork:source,returnToWork});});return;
   }
   const live=bridge.getState(),view=obj(live.view),origin=captureWorkspaceOrigin();
   const {parentTaskId,parentRunId,parentEpoch,conversationId,teamRunId}=team;
   const source:WorkTeamResultSource={parentTaskId,parentRunId,parentEpoch,conversationId,teamRunId,taskId:team.taskId};
   const original=origin.task?.layer==='overview'&&origin.task.id===parentTaskId&&openedTaskId.current===parentTaskId&&
     !taskOpening&&!document.hidden&&view.section===null&&view.taskId===parentTaskId&&!live.state.conversationReader&&
     ['butler','chat','tasks','projects'].includes(origin.route)&&live.capabilities.commands.includes('tasks.open')&&completedWorkTeam(source,live.state.tasks);
   const documentEpoch=bridge.getDocumentEpoch(),arrival=navigationWindow.current+1;
   const inTeam=()=>{const native=bridge.getState(),now=obj(native.view);
    return !document.hidden&&navigationWindow.current===arrival&&bridge.getDocumentEpoch()===documentEpoch&&
     now.section==='teams'&&now.taskId===null&&originalTeamWorkCurrent(origin,source,native);};
   const arm=(verifiedResult:()=>boolean):WorkTeamResultReturn|null=>{
    if(relatedOrigin.current!==origin||!inTeam())return null;
    const pin:WorkTeamResultReturn={source,rearm:arm,current:()=>relatedOrigin.current===exact&&inTeam()&&verifiedResult(),
     retire:()=>{if(navigationWindow.current===arrival&&relatedOrigin.current===exact)relatedOrigin.current=origin;}};
    const exact:WorkspaceOrigin={...origin,task:{id:parentTaskId,layer:'overview',expected:{runId:parentRunId,epoch:parentEpoch},teamResult:pin}};
    relatedOrigin.current=exact;
    return pin;
   };
   void openRelatedWorkspace('teams',original?origin:undefined,()=>{
    setTeamAttentionFocus(undefined);setTeamRunFocus({requestId:crypto.randomUUID(),runId:teamRunId,
      ...(original?{fromWorkResult:{source,arm}}:{})});
   });
 };
 const openTeamOwnerWork=(link:TeamOwnerWorkLink,intent:TeamOwnerWorkIntent)=>{
   if(intent==='returnResult'){
     const origin=relatedOrigin.current,pin=origin?.task?.teamResult;
     if(origin&&pin&&route==='teams'&&pin.current()&&matchesWorkTeamResult(pin.source,link))
       void restoreWorkspace(origin);
     return;
   }
   if(intent==='followUp'){
     if(link.readOnly!==true||link.currentRun!==true||link.stopping!==false||link.savedResultForDraft!==true||!link.taskId||!link.runId||
       !link.teamRunId||!link.conversationId||!Number.isSafeInteger(link.epoch)||link.epoch<1||!can('conversation.select')||!can('composer.object.add'))return;
     const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch(),resume=Number(obj(bridge.getState().view).resumeRevision||0);
     const fromOriginalTeam=()=>{
       const live=bridge.getState(),view=obj(live.view);
       return navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden&&
         view.section==='teams'&&!view.taskId&&Number(view.resumeRevision||0)===resume&&!live.state.chatSubmitting;
     };
     // Secondary's retained forms guard calls back after their draft writes.
     // Continue only from the actual navigation callback, not its scheduled return.
     void navigate('chat',()=>{void returnToOriginalTaskConversation(link.taskId,link.conversationId,{runId:link.runId,epoch:link.epoch},true)},
       {taskClosed:true,stillCurrent:fromOriginalTeam});
     return;
   }
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
  if(action==='tasks.confirm'&&taskLayer==='overview'){
   const original=exactReviewedOriginalDraft(selectedTask,review.task),view=obj(bridge.getState().view);
   if(original?.taskId===taskId&&openedTaskId.current===taskId&&view.taskId===taskId&&view.section==null){
    // Match the rendered editor, not a cached editing flag or a previously opened revision.
    const editor=taskScroll.current?.querySelector(`[data-owner-draft-editor="${original.draftId}"]`);
    if(editor&&!editor.closest('[hidden]'))return;
   }
  }
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
   try{submission=continuationRequest(review,taskId,projectFeedback[projectFeedbackKey(review?.task)]||'')}catch(error){setTaskErrors(previous=>({...previous,[taskId]:error instanceof Error?error.message:String(error)}));return}
   const window=reviewWindow.current,originalTask=taskId;
   const pending:ContinuationSubmission={original:submission.original,status:'pending',message:'正在确认原项目任务续接。对话草稿仍保留，尚未发送。'};
   rememberContinuation(pending);setReviewSubmitted(true);setReviewReceipt('');
   const settle=(value:unknown,error=false)=>{const original=continuationsRef.current[originalTask];if(!original||original.original.reviewHandle!==pending.original.reviewHandle)return;const current=currentReviewWindow(window);rememberContinuation(error?continuationFailed(original,value,current):continuationSettled(original,value,current));if(current&&error){setReviewSubmitted(false);setReview(null)}};
   void bridge.command('tasks.resume',submission.args).then(value=>settle(value),error=>settle(error,true));return;
  }
  const request=reviewRequest.current,originalTask=taskId;
  const planDecision=action==='tasks.plan.enter'||action==='tasks.approve'&&asArray<Json>(review.approvals).some(row=>(row.approvalId||row.id)===args.approvalId&&row.webReview?.kind==='execution_plan');
  const at=reviewWindow.current;
  const draftClick=action==='tasks.confirm'&&typeof args.completed==='boolean'&&taskLayer==='overview'&&at&&currentReviewWindow(at)?draftAcceptanceClick(review,at,args.completed):null;
  setReviewSubmitted(true);setReviewReceipt('');
  const payload={reviewHandle:review.reviewHandle,...args};
  if(planDecision)setTaskErrors(previous=>({...previous,[originalTask]:''}));
  void (planDecision?bridge.command(action,payload):act(action,payload,taskId)).then(value=>{
   if(reviewRequest.current!==request)return;
   // This ACK is only admission. The exact persisted confirmation is checked in the task view.
   if(draftClick&&obj(value).accepted===true&&currentReviewWindow(draftClick.window))setDraftAcceptance(draftClick);
   if(!planDecision)return;
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
 const openProjectWork=(id:string,entry:ProjectWorkEntry='overview',query='',checkoutId?:string,expected?:{runId:string;epoch:number},focusShelf?:()=>boolean)=>{
  const projectId=String(state.projectId||'');
  if(!projectId||bridge.getState().state.projectId!==projectId||bridge.getState().state.filePath)return;
  const origin:ProjectTaskReturn={projectId,checkoutId,query,taskId:id,entry,focusOutcome:entry==='outcomes',expected,focusShelf};
  projectTaskReturn.current=origin;
  const navigation=navigationWindow.current,epoch=bridge.getDocumentEpoch(),request=++searchOpenRequest.current;
  const current=()=>searchOpenRequest.current===request&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===epoch&&
   !document.hidden&&bridge.getState().state.projectId===projectId&&!bridge.getState().state.filePath;
  openTask(id,entry==='outcomes'?'overview':entry,false,undefined,query,expected,current,undefined,expected?()=>{
   if(!current()||projectTaskReturn.current!==origin)return;
   if(focusShelf){origin.focusOutcome=false;origin.returning=true;}else projectTaskReturn.current=null;
   setLocalError(focusShelf?'暂时打不开原工作，尚未核实这份固定网页现在能否读取。没有打开其他轮次。':'原工作或轮次暂不可读取，没有打开其他工作。');
  }:undefined);
 };
 const openProjectReceipt=(id:string,checkoutId?:string,expected?:{runId:string;epoch:number})=>openProjectWork(id,'overview','',checkoutId,expected);
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
 const pickTeamForCurrentDraft=useCallback((expected?:TeamDraftInput)=>{
  if(route!=='chat'||taskOpen||intent!=='chat'||draftSwitchPending()||document.hidden||
     expected&&navigationWindow.current!==navigationRevision)return false;
  const live=bridge.getState(),view=obj(live.view),draft=originalTeamDraft(live);
  if(!draft||expected&&!sameTeamDraftInput(expected,draft)||view.section!==null||view.taskId!==null||!Number.isSafeInteger(view.resumeRevision))return false;
  if(draft.kind==='goal'&&chatDraft.currentValue()!==draft.text){setLocalError('当前输入尚未核对，请稍后选择编组。原文字不变。');return true;}
  const visit:TeamDraftVisit={...draft,documentEpoch:bridge.getDocumentEpoch(),navigation:navigationWindow.current+1,resume:view.resumeRevision};
  const origin:WorkspaceOrigin={...captureNavigationOrigin(),drawerChat:{id:draft.conversation,draftKey:draft.key,documentEpoch:visit.documentEpoch},teamDraft:visit};
  void navigate('teams',()=>{relatedOrigin.current=origin;},{keepOrigin:true,stillCurrent:()=>{
   if(document.hidden||navigationWindow.current!==visit.navigation-1||bridge.getDocumentEpoch()!==visit.documentEpoch)return false;
   const current=bridge.getState(),window=obj(current.view);
   const exact=!draftSwitchPending()&&window.section===null&&window.taskId===null&&window.resumeRevision===visit.resume&&sameTeamDraftInput(visit,originalTeamDraft(current))&&
    (visit.kind!=='goal'||chatDraft.currentValue()===visit.text);
   if(!exact)setLocalError(originalTeamDraftChanged);
   return exact;
  }});
  return true;
 },[route,taskOpen,intent,draftSwitchPending,navigationRevision,chatDraft,captureNavigationOrigin,navigate]);
 const selectNavigation=useCallback((key:string)=>{
  setNavigationMoreOpen(false);setSearchReturn(false);
  if(key==='artifacts'){
   // Outcomes cover the current workspace; closing them must reveal that same route.
   setWorkOpen(false);setCommandOpen(false);
   if(can('attachmentWorkspace.open'))run('attachmentWorkspace.open');
  }else if(key==='teams'&&pickTeamForCurrentDraft()){
   return;
  }else if(key==='tasks'&&route==='chat'&&!desktopNavigation){
   void openRelatedWorkspace('tasks',captureNavigationOrigin());
  }else if(key==='remoteInbox'){
   void openNotifications(()=>{notificationMemory.current.attentionRequest=(notificationMemory.current.attentionRequest||0)+1});
  }else if(key==='setup')openSetup();
  else navigate(key);
 },[can,run,route,pickTeamForCurrentDraft,desktopNavigation,captureNavigationOrigin,openRelatedWorkspace,openNotifications,openSetup,navigate]);
 const homeFilesOpening=useRef(false);
 const privateHomeFiles=(native:ReturnType<typeof bridge.getState>)=>{
  const owner=native.state,view=obj(native.view),draft=native.composer,phone=obj(native.phoneDraft),available=native.capabilities.commands;
  const conversation=owner.conversationId,library=obj(obj(owner.features).attachments);
  const ownedDraft=draft?.ready===true&&typeof draft.key==='string'&&Number.isSafeInteger(draft.epoch)&&
   (conversation===null?draft.conversationId===''&&draft.key.startsWith('new:'):
    typeof conversation==='string'&&!!conversation&&draft.conversationId===conversation&&draft.key===`conversation:${conversation}`);
  return native.host?.ready===true&&ownedDraft&&view.section===null&&view.taskId===null&&Number.isSafeInteger(view.resumeRevision)&&
   owner.filePath===null&&owner.conversationReader===null&&owner.queuedInputsPage===null&&
   owner.chatAttachmentPickerOpen===false&&owner.chatSubmitting===false&&owner.phoneSubmitting===false&&
   typeof obj(owner.attachmentWorkspace).open==='boolean'&&phone.phone===false&&phone.channelOrigin===false&&
   library.scope==='owner/private'&&asArray<string>(library.commands).includes('list')&&
   available.includes('attachmentWorkspace.open')&&available.includes('attachmentWorkspace.action');
 };
 const openHomeFiles=route==='butler'&&intent==='chat'&&!taskOpen&&!workOpen&&!commandOpen&&!phoneReview&&!preparationTarget&&
  !searchReturn&&!searchOriginal&&privateHomeFiles(snapshot)?()=>{
   const current=()=>{
    const live=bridge.getState();
    return !document.hidden&&!draftSwitchPending()&&navigationWindow.current===navigationRevision&&bridge.getDocumentEpoch()===personalDocumentEpoch&&
     privateHomeFiles(live)&&obj(live.view).resumeRevision===obj(snapshot.view).resumeRevision&&
     live.composer?.key===composer.key&&live.composer?.epoch===composer.epoch&&obj(live.state.attachmentWorkspace).open===false;
   };
   if(homeFilesOpening.current||!current())return;
   homeFilesOpening.current=true;
   // The same library as the "成果" menu. No file is selected or joined to the draft.
   void bridge.command('attachmentWorkspace.open').catch(error=>{
    if(current())setLocalError(error instanceof Error?error.message:'未能打开本机文件，可以重试。');
   }).finally(()=>{homeFilesOpening.current=false});
  }:undefined;
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
    toast.dismiss('work-search-open');
    setSearchReturn(true);setWorkOpen(false);setCommandOpen(false);
    openTask(item.taskId||item.id,'overview',false,undefined,query,undefined,stillCurrent,undefined,reason=>{
     if(!stillCurrent())return;
     setSearchReturn(false);showWorkSearch();
     toast.error(reason instanceof Error?reason.message:'这项工作没能打开。可以在原搜索结果中重试。',{id:'work-search-open'});
    });
    return;
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
   const id=item.kind==='conversation'?item.conversationId||item.id:item.projectId||item.id;
   retainedForContinuation=true;
   await navigate(target,()=>{
    const atDestination=()=>ownsRequest()&&navigationWindow.current===navigation+1;
    if(!atDestination())return;
    const matches=()=>{
     const state=bridge.getState().state;
     return item.kind==='conversation'?(state.conversationReader?state.conversationReader.conversation===id:state.conversationId===id):state.projectId===id;
    };
    // An accepted select is not yet a visible selection; for conversations
    // the host can retain the active draft and open the requested reader.
    void bridge.command(action,{id}).then(()=>new Promise<boolean>((resolve,reject)=>{
     let unsubscribe=()=>{};
     const timer=window.setTimeout(()=>{
      unsubscribe();
      if(atDestination())reject(new Error(item.kind==='conversation'?'没有打开所选对话，可以在原搜索结果中核对。':'没有打开所选项目，可以在原搜索结果中核对。'));
      else resolve(false);
     },4500);
     const check=()=>{const current=atDestination();if(!current||matches()){clearTimeout(timer);unsubscribe();resolve(current)}};
     unsubscribe=bridge.subscribe(check);check();
    })).then(opened=>{if(opened&&atDestination())setSearchReturn(true);}).catch(failure=>{
     if(!atDestination())return;
     showWorkSearch();toast.error(failure instanceof Error?failure.message:'尚未打开所选记录。',{id:'work-search-open'});
    });
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
 const adoptedHead=route==='projects'&&!taskOpen&&currentFileKey&&fileConflict&&fileDraft?.submitted===undefined&&
   fileDraft&&fileDraft.version>0&&fileDraft.version<fileIdentity.version&&fileDraft.text!==fileDraft.baseline&&
   !state.fileError&&!fileAdoption.holdsDraft()&&can('projects.edit')&&can('projects.save')
   ? observedAdoptedFormalText(state,fileIdentity.path,fileIdentity.version) : undefined;
 const adoptedEditorDocument=bridge.getDocumentEpoch(),adoptedEditorResume=obj(snapshot?.view).resumeRevision;
 const adoptedEditRevision=fileEditRevisions.current.get(currentFileKey)||0;
 const continueAdopted:ContinueAdoptedFormal|undefined=adoptedHead&&fileDraft&&Number.isSafeInteger(adoptedEditorResume)?{
  scope:JSON.stringify([adoptedHead.identity,adoptedEditorDocument,adoptedEditorResume,navigationRevision,adoptedEditRevision]),
  start:()=>{
   const current=bridge.getState(), view=obj(current.view);
   if(document.hidden||bridge.getDocumentEpoch()!==adoptedEditorDocument||navigationWindow.current!==navigationRevision||
      view.resumeRevision!==adoptedEditorResume||view.section!==null||view.taskId!==null||current.host?.ready!==true||
      current.state.fileError||current.state.attachmentWorkspace?.open||current.state.conversationReader||
      !['projects.edit','projects.save'].every(a=>current.capabilities.commands.includes(a))||fileAdoption.holdsDraft()||
      (fileEditRevisions.current.get(currentFileKey)||0)!==adoptedEditRevision)return false;
   const verified=observedAdoptedFormalText(current.state,fileIdentity.path,fileIdentity.version);
   if(!verified||verified.identity!==adoptedHead.identity||verified.text!==adoptedHead.text)return false;
   // Native already holds this approved, loaded baseline. Only replace the
   // specific UI buffer that the owner has preserved; do not edit or save it.
   fileEditRevisions.current.set(currentFileKey,adoptedEditRevision+1);
   setFileDrafts(previous=>previous[currentFileKey]===fileDraft?{...previous,
     [currentFileKey]:{text:verified.text,baseline:verified.text,version:fileIdentity.version,dirty:false}}:previous);
   setFileMode('edit');
   return true;
  },
 }:undefined;
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
 const managedNodePending=(taskId:string):OriginalManagedNodePending[]=>
  review?.task?.id===taskId&&currentReviewWindow(reviewWindow.current)?asArray<Json>(review.unresolvedOperations).flatMap(row=>{
   const pin=reviewedManagedNodePending(taskId,row);return pin?[pin]:[];
  }):[];
 const renderOperation=(operation:unknown,index:number,scope:string)=>{
  const op=presentOperation(operation);const key=`${scope}:${op.runId}:${op.id||index}`;
  const packageTarget=packageOperationTarget(tasks.find(task=>task.id===scope),operation);
  const executionTask=tasks.find(task=>task.id===scope);
  const memoryProposal=obj(obj(obj(operation).receipt).memoryDraft);const memoryTarget=memoryProposal.format==='pocketworld.conversation-memory-draft.v1'&&memoryProposal.taskId===scope&&memoryProposal.operationId===obj(operation).operationId&&memoryProposal.runId===obj(operation).runId?memoryProposal:null;
  const proposal=obj(obj(obj(operation).receipt).skillDraft);const draftTarget=proposal.format==='pocketworld.conversation-skill-draft.v1'&&proposal.taskId===scope&&proposal.operationId===obj(operation).operationId&&proposal.runId===obj(operation).runId?proposal:null;
  return <section className="pw-operation-entry" key={key}><ToolCall id={op.id||String(index)} name={op.name} summary={op.stateLabel} status={op.status} input={op.arguments} result={op.result} durationMs={op.durationMs} open={expandedCalls[key]??false} onOpenChange={value=>setExpandedCalls(x=>({...x,[key]:value}))}/>{packageTarget&&can('projects.package.preview.inspect')&&<Button variant="secondary" disabled={packageOpening||taskOpening||!can('projects.package.preview.open')||!can('tasks.close')} onClick={()=>openTaskPackage(packageTarget)}>{packageTarget.savedBuildWeb?'打开这版固定网页':'查看这次任务的固定包'}</Button>}{draftTarget&&can('skillDraft.open')&&<Button variant="secondary" onClick={()=>setSkillDraftTarget(draftTarget as ConversationDraftTarget)}>审阅原技能提案</Button>}{memoryTarget&&can('memoryDraft.open')&&<Button variant="secondary" onClick={()=>setMemoryDraftTarget(memoryTarget as ConversationDraftTarget)}>审阅原记忆提案</Button>}{op.readLimited&&<p className="pw-muted">这条记录仅保留摘要，详情需要回到原记录读取。</p>}{executionTask&&obj(operation).tool==='project_execute'&&(can('local.execution.observe')||can('local.execution.open'))&&<ConversationModelPickerVisibility key={'log:'+key}>{pickerVisible=><ProjectExecutionWorkspace task={executionTask} operation={obj(operation)} reviewedPending={managedNodePending(scope)} prepareFailure={taskOpen&&openedTaskId.current===executionTask.id&&can('tasks.review')?prepareFailedBuild:undefined} active={taskOpen&&taskLayer==='process'&&!pickerVisible&&!phoneViewerObscured} run={act}/>}</ConversationModelPickerVisibility>}</section>;
 };
 const taskActionLabel=(task:Task)=>conversationWorkActionLabel(task);
 const prepareFailedBuild=(exit:ExecutionExit)=>{
  const now=bridge.getState(),original=asArray<Task>(obj(now.state).tasks).find(t=>t.id===exit.taskId);
  if(!original||document.hidden||!taskOpen||taskOpening||openedTaskId.current!==original.id||obj(now.view).taskId!==original.id||
    reviewPending||reviewSubmitted||questionPending||continuationBlocks(continuationsRef.current[original.id]))throw Error('原任务窗口或续接状态已改变，未改写草稿。');
  const key=projectFeedbackKey(original),note=failedBuildFeedback(original,exit);if(!key||!note)throw Error('原运行或副本已改变，未带到另一轮。');
  const old=projectFeedbackRef.current[key]||'';if(!old.includes(note)){
   const next=old?old+'\n\n'+note:note;if(next.length>8000)throw Error('原草稿将超过 8000 字，既有文字仍保留，请先整理。');putProjectFeedback(key,next);
  }
  setLocateProjectFeedback(key);showTaskLayer('overview');if(!review||reviewStale||projectFeedbackKey(review.task)!==key)refreshReview();
 };
 useEffect(()=>{
  if(!locateProjectFeedback||!taskOpen||document.hidden||!selectedTask||projectFeedbackKey(selectedTask)!==locateProjectFeedback||
    !review||reviewStale||projectFeedbackKey(review.task)!==locateProjectFeedback)return;
  const id=window.requestAnimationFrame(()=>{const section=document.getElementById('pw-project-continuation-note');if(section){section.scrollIntoView({block:'nearest'});section.focus({preventScroll:true});setLocateProjectFeedback('')}});
  return()=>window.cancelAnimationFrame(id);
 },[locateProjectFeedback,taskOpen,selectedTask,review,reviewStale]);
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
 const phoneWorkObscured=!!(searchOriginal||preparationTarget||projectConventionMessage||providedMemoryMessage||conversationMemoryOpen||state.queuedInputsPage||permissionOptionsOpen||organizationTarget||state.attachmentWorkspace?.open||workOpen||commandOpen||projectPickerOpen||fileDisplayOpen||newProjectOpen||newFileOpen||phoneReview||voiceReview||state.chatAttachmentPickerOpen||messageQuoteTarget||messageOriginTarget||sourceSelection||skillDraftTarget||memoryDraftTarget||routeReferenceTarget||state.quickPanelVoice||readingDocuments.length);
 const phoneViewerObscured=phoneWorkObscured||!!state.conversationReader;
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
   onPause={phoneControllable&&(!local||!live?.paused)&&task.status==='running'&&can('phone.pause')?identity=>exactPhoneControl('phone.pause',identity,local):undefined} onResume={phoneControllable&&(!local||live?.displayedPause)&&task.status==='paused'&&!conversationInlineAttention(task)&&can('phone.resume')?identity=>exactPhoneControl('phone.resume',identity,local,live?.displayedPause):undefined}
   onStop={phoneControllable&&!terminal(task)&&task.status!=='interrupted'&&task.status!=='stopping'&&can('phone.stop')?identity=>exactPhoneControl('phone.stop',identity,local):undefined}
   onOpenSetup={canCheckConnection&&can('setup.read')?()=>{setPhoneViewerExpanded(false);void openSetup('phone')}:undefined}
   onOpenTask={can('tasks.open')?identity=>{setPhoneViewerExpanded(false);if(taskOpen&&openedTaskId.current===identity.taskId){showTaskLayer(task.status==='failed'?'process':'overview');if(attention(task)&&can('tasks.review')&&!reviewPending)loadTaskReview(identity.taskId)}else openTask(identity.taskId,task.status==='failed'?'process':attention(task)&&can('tasks.review')?'review':'overview')}:undefined}/>}</ConversationModelPickerVisibility>;
 };
 const reviewedBuildAfterDependencies=(task:Task,project:ReturnType<typeof projectContinuation>,actions:string[])=>{
  if(taskLayer!=='overview'||!taskOpen||taskOpening||!review||reviewStale||review.task?.id!==task.id||
    reviewWindow.current?.taskId!==task.id||!currentReviewWindow(reviewWindow.current)||obj(snapshot?.view).section!=null||!project||project.reason||
    review.ownerQuestion||asArray(review.approvals).length||asArray(review.unresolvedOperations).length||
    !can('tasks.resume')||!actions.includes('tasks.resume'))return null;
  const current=originalNodeBuildAfterDependencies(task),shown=originalNodeBuildAfterDependencies(review.task);
  return current&&shown&&JSON.stringify(current)===JSON.stringify(shown)?current:null;
 };
 const originalTaskOverview=(task:Task)=>{
  const nativeView=obj(bridge.getState().view);
  return taskLayer==='overview'&&taskOpen&&!taskOpening&&!document.hidden&&openedTaskId.current===task.id&&nativeView.taskId===task.id&&nativeView.section==null;
 };
 // Only the original Chat already beneath this Work can receive the focus. No conversation selection, history, or reread.
 const phoneWorkReportPlace=(link:DeliveredPhoneWorkReport,live=bridge.getState())=>{
  const {result,conversationId,messageId}=link,owner=live.state,node=scroll.current,list=chatMessageList.current;
  if(owner.conversationId!==conversationId||owner.conversationReader||owner.loadingFirstConversationMessages||owner.queuedInputsPage||
     owner.filePath||owner.attachmentWorkspace?.open||owner.chatAttachmentPickerOpen||!live.capabilities.commands.includes('phone.result.original')||
     node?.dataset.conversationId!==conversationId||!list||!node.contains(list)||!loadedPhoneWorkReport(owner.messages,link))return;
  const rows=asArray<Task>(owner.tasks).filter(row=>row.id===result.taskId),work=rows.length===1?rows[0]:undefined;
  const run=phoneRunIdentity(work),raw=work?.raw;
  if(!work||!isPrivateLocalPhone(work)||phoneConversationId(work)!==conversationId||run?.runId!==result.runId||run.epoch!==result.epoch||
     raw?.contextAvailable===false||raw?.contextWithdrawn===true||raw?.sourceCurrentReadRequired===true||raw?.taskProjection==='source_unavailable')return;
  const articles=Array.from(list.children).filter((row):row is HTMLElement=>row instanceof HTMLElement&&row.tagName==='ARTICLE'&&row.dataset.messageId===messageId);
  if(articles.length===1)return {node,article:articles[0]};
 };
 const phoneWorkLocation=(task:Task):PhoneWorkReportLocation|undefined=>{
  if(route!=='chat'||!originalTaskOverview(task)||!isPrivateLocalPhone(task)||!phoneConversationId(task)||!can('tasks.close'))return;
  const available=(link:DeliveredPhoneWorkReport)=>!phoneWorkObscured&&!phoneViewerExpanded&&!phoneWorkReportExit.current&&
   !workSupplementBeforeLeave.current&&!taskPolicyBeforeLeave.current&&!projectDraftBeforeLeave.current&&!preparationBeforeLeave.current&&
   link.result.taskId===task.id&&originalTaskOverview(task)&&!!phoneWorkReportPlace(link);
  return {available,open:(link,trigger)=>{
   if(!available(link)||!trigger.isConnected||trigger.closest('[hidden],[inert],[aria-hidden="true"]'))return;
   const initial=bridge.getState(),documentEpoch=bridge.getDocumentEpoch(),nav=navigationWindow.current,request=reviewRequest.current;
   const resume=openedTaskResumeRevision.current,sourceRevision=initial.state.traceSourceRevision,nativeRequest=initial.state.requestedTaskRevision;
   const current=(closing:boolean)=>{
    const live=bridge.getState(),view=obj(live.view);
    if(document.hidden||bridge.getDocumentEpoch()!==documentEpoch||navigationWindow.current!==nav||view.section!==null||view.resumeRevision!==resume||
       live.state.traceSourceRevision!==sourceRevision||live.state.requestedTaskRevision!==nativeRequest||
       reviewRequest.current!==request+(closing?1:0)||openedTaskId.current!==(closing?null:task.id)||
       view.taskId!==task.id&&(!closing||view.taskId!==null))return;
    return phoneWorkReportPlace(link,live);
   };
   if(!current(false))return;
   let ack=false,exit=false,frame=0,timer=0,unsubscribe=()=>{};
   const hide=()=>{if(document.hidden)retire()};
   const retire=()=>{
    if(phoneWorkReportExit.current===visit)phoneWorkReportExit.current=null;
    document.removeEventListener('visibilitychange',hide);
    document.removeEventListener('pointerdown',retire,true);document.removeEventListener('keydown',retire,true);
    unsubscribe();window.clearTimeout(timer);window.cancelAnimationFrame(frame);
   };
   const focus=()=>{
    if(!ack||!exit||frame||phoneWorkReportExit.current!==visit)return;
    if(!current(true)){retire();return;}
    if(obj(bridge.getState().view).taskId!==null)return;
    frame=window.requestAnimationFrame(()=>{
     const place=phoneWorkReportExit.current===visit&&current(true),active=document.activeElement;
     if(place&&obj(bridge.getState().view).taskId===null&&!place.article.closest('[hidden],[inert],[aria-hidden="true"]')&&
        !document.querySelector('[role="dialog"][data-state="open"]')&&
        (active===document.body||active===document.documentElement||active===trigger||active===place.article)){
      stopFollowing();place.node.scrollTop+=place.article.getBoundingClientRect().top-place.node.getBoundingClientRect().top;
      place.article.focus({preventScroll:true});
     }
     retire();
    });
   };
   const visit={retire,exited:()=>{if(!current(true)){retire();return false;}exit=true;focus();return true;}};
   phoneWorkReportExit.current=visit;
   timer=window.setTimeout(retire,5000);
   document.addEventListener('visibilitychange',hide);
   document.addEventListener('pointerdown',retire,true);document.addEventListener('keydown',retire,true);
   void closeTask().then(closed=>{
    if(phoneWorkReportExit.current!==visit)return;
    if(!closed){retire();return;}
    ack=true;unsubscribe=bridge.subscribe(focus);focus();
   },retire);
  }};
 };
 const openOriginalPhoneTool=(task:Task,pin:OriginalPhoneToolRecord,trigger:HTMLButtonElement)=>{
  const documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current,request=reviewRequest.current;
  const resume=openedTaskResumeRevision.current;
  const current=()=>{
   const live=bridge.getState(),view=obj(live.view);
   if(!originalTaskOverview(task)||!trigger.isConnected||trigger.closest('[hidden],[inert],[aria-hidden="true"]')||
      documentEpoch!==bridge.getDocumentEpoch()||navigation!==navigationWindow.current||request!==reviewRequest.current||
      view.resumeRevision!==resume||live.state.conversationReader||!live.capabilities.commands.includes('history.read'))return false;
   const rows=asArray<Task>(live.state.tasks).filter(row=>row.id===pin.taskId);
   return rows.length===1&&JSON.stringify(originalPhoneToolRecord(rows[0],pin.operationId))===JSON.stringify(pin);
  };
  if(!current())return;
  // Enter the existing tools subtab first so the Trace view never activates on this gesture.
  const show=()=>{if(current()&&originalPhoneToolOpen.current?.(pin)){rememberTaskPosition();setTaskLayer('process')}};
  if(!guardTaskLeave(show))show();
 };
 const lateDependencyForOverview=(task:Task)=>{
  if(!originalTaskOverview(task))return false;
  const current=observedLateProjectDependency(task);
  return !!current&&(!review||reviewStale||
    currentReviewWindow(reviewWindow.current)&&JSON.stringify(originalNodeBuildAfterDependencies(review.task))===JSON.stringify(current));
 };
 const heldDependencyForOverview=(task:Task)=>originalTaskOverview(task)?observedProjectDependencyHold(task):'';
 const exactDependencyHoldReview=(heldBuildKey:string)=>!!heldBuildKey&&!reviewStale&&!!projectContinuation(review)?.reason&&
  currentReviewWindow(reviewWindow.current)&&!asArray<{action:string}>(review?.actions).some(action=>action.action==='tasks.resume')&&
  observedProjectDependencyHold(review?.task)===heldBuildKey;
 const prepareNextBuildNote=(task:Task,buildNext:NonNullable<ReturnType<typeof originalNodeBuildAfterDependencies>>)=>{
  if(!review||reviewStale||reviewPending||reviewSubmitted||taskOpening||openedTaskId.current!==task.id||
    !currentReviewWindow(reviewWindow.current)||continuationBlocks(continuationsRef.current[task.id]))return;
  const now=bridge.getState(),current=asArray<Task>(now.state.tasks).find(value=>value.id===task.id);
  const verified=originalNodeBuildAfterDependencies(current),key=projectFeedbackKey(current);
  if(obj(now.view).section!=null||!key||taskBinding(current)!==taskBinding(review.task)||
    !verified||JSON.stringify(verified)!==JSON.stringify(buildNext)||(projectFeedbackRef.current[key]??'')!=='')return;
  putProjectFeedback(key,nodeBuildContinuationText(verified));setLocateProjectFeedback(key);
 };
 const taskResultDecision=(task:Task,disabled:boolean,secondary=false,readback?:false|(()=>boolean))=>{
  const label=exactReviewedOriginalDraft(task,review?.task)?'确认原改稿达到目标':'确认达到目标';
  const confirm=()=>{if(readback!==false&&(!readback||readback()))reviewedAction('tasks.confirm',{completed:true,feedback:reviewNote})};
  return <><Button variant="secondary" disabled={disabled} onClick={()=>reviewedAction('tasks.confirm',{completed:false,feedback:reviewNote})}>目标尚未达到</Button>
   {readback!==false&&!noSavedDraftInReview(task,review)&&(secondary?<Button variant="secondary" disabled={disabled} onClick={confirm}>{label}</Button>:
    <PressButton label={label} disabled={disabled} onClick={confirm}/>)}</>;
 };
 const inlineDraftDecisionFor=(task:Task,actions:string[])=>{
  const view=obj(snapshot?.view),original=exactReviewedOriginalDraft(task,review?.task);
  if(!original||!firstOriginalDraft(task,original)||taskLayer!=='overview'||!taskOpen||taskOpening||openedTaskId.current!==task.id||document.hidden||view.taskId!==task.id||view.section!=null||
    !can('projectDraft.readDifference')||!can('tasks.confirm')||!review||reviewStale||!currentReviewWindow(reviewWindow.current)||
    task.status!=='needs_confirmation'||review.task?.status!=='needs_confirmation'||task.raw?.hasUnresolvedOperations!==false||taskNeedsReconciliation(task)||
    !!review.ownerQuestion||asArray<Json>(review.approvals).length||asArray<Json>(review.unresolvedOperations).length||
    !actions.includes('tasks.confirm')||actions.some(action=>!['tasks.confirm','tasks.resume','tasks.scope','tasks.plan.enter','tasks.deleteScreenshot'].includes(action))||
    actions.includes('tasks.resume')&&!projectContinuation(review))return null;
  return original;
 };
 const taskReviewBody=(task:Task)=>{
  if(teamReportInOverview?.visit.parent.taskId===task.id){
   const mode=teamReportInOverview;
   const changed=mode.done===undefined&&!mode.pending&&mode.reading&&(
    teamReportParent(task)?.digest!==mode.visit.parent.digest||!sameTeamReportChoice(linkedTeamReportChoice(task),mode.visit.choice));
   return <OwnerWorkTeamReportReview key={mode.visit.choice.teamRunId} reading={changed?undefined:mode.reading}
    error={mode.error||(changed?'所选汇总或原工作已变化。返回原工作重新核对，不会使用其他报告。':undefined)}
    done={mode.done} pending={mode.pending===true} disabled={!can('tasks.confirm')} note={reviewNote} edit={setReviewNote}
    decide={completed=>decideTeamReport(mode,completed)} leave={leaveTeamReport}/>;
  }
  const canFollowOriginal=privateTaskConversationId(task)&&task.raw?.contextAvailable!==false&&
    (task.raw?.executionRole==='owner_work'||task.raw?.nativeResultHandling===true||task.raw?.ownerWorkPreparation)&&
    typeof task.raw?.runId==='string'&&!!task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0&&
    can('conversation.select')&&can('composer.object.add')&&!state.chatSubmitting&&!returningConversation;
  const followOriginal=canFollowOriginal?()=>{if(openedTaskId.current===task.id&&!taskOpening)void returnToTaskConversation(task,true)}:undefined;
  const canReadOriginal=originatingRequestConversation(task)&&can('conversation.select')&&!returningConversation;
  const reportHistory=task.raw?.executionRole==='owner_work'&&!task.raw?.developmentContext&&!task.raw?.ownerPhoneControl&&!task.raw?.ownerDeviceWork&&
   isPrimaryRoute&&!!reportedHomeWork(task)&&can('ownerWork.report.history')&&can('tasks.close');
  if(taskLayer==='homeReport')return <ButlerReturnedReport key={homeReport.current?.target.reportMessageId} report={homeReport.current} task={task} active={taskOpen&&!taskOpening&&openedTaskId.current===task.id&&obj(snapshot?.view).taskId===task.id} onCopy={copyWithLocalFeedback}
   onOriginal={()=>{showTaskLayer('overview');if(attention(task)&&can('tasks.review')&&!reviewPending)loadTaskReview(task.id)}}
   onIndependentFollowUp={intent==='chat'&&can('ownerWork.report.followUp.prepare')&&can('ownerWork.report.followUp.resume')?handle=>startIndependentReportDraft({taskId:task.id,handle}):undefined}
   onLocateReport={reportHistory&&can('conversation.reader.close')?report=>readReportInConversation(task,report):undefined}
   onFiles={task.status==='completed'&&can('attachmentWorkspace.open')?openHomeReportFiles:undefined}
   onConversation={canReadOriginal?()=>{if(openedTaskId.current===task.id&&!taskOpening)void returnToTaskConversation(task)}:undefined}/>;
  const progress=getRecentTaskProgress(task);
  const stage=workCollectionPresentation(task).group;
  const actionNames=asArray<{action:string;label:string}>(review?.actions).map(x=>x.action),approved=asArray<Json>(review?.approvals),unresolved=asArray<Json>(review?.unresolvedOperations);
  const heldProfileApproval=task.raw?.workProfileRef&&originalTaskOverview(task)&&!taskNeedsReconciliation(task)&&savedUnsentProfileProjectApproval(task);
  const protectedDisabled=taskOpening||reviewPending||reviewStale||reviewSubmitted;
  const scope=obj(review?.approvalExecutionScope||review?.task?.raw?.executionScope),currentScope=obj(task.raw?.executionScope);
  const project=projectContinuation(review),continuation=continuations[task.id];
  const canReadProject=privateProjectTask(task)&&['paused','interrupted','awaiting_user','needs_confirmation','failed','completed'].includes(task.status);
  const taskView=obj(snapshot?.view);
  const observedDraft=observedFixedProjectDraft(task);
  const fixedDraft=project?.fixedDraft&&!reviewStale&&canReadProject&&taskOpen&&!taskOpening&&taskLayer==='overview'&&!document.hidden&&
   openedTaskId.current===task.id&&taskView.taskId===task.id&&taskView.section==null&&matchesFixedProjectDraft(task,project.fixedDraft)?project.fixedDraft:undefined;
  const fixedDraftOptions=fixedDraft&&<section className="pw-form-flow" aria-label="这份原改稿的下一步">
   <p className="pw-field-hint">原改稿保留在这里。{['projectDraft.readDifference','projectDraft.editLocal','projectDraft.keepRevision','projectDraft.closeEditor'].every(can)&&'自己编辑时可保留为新稿。'}要让管家再改，请另写一个目标。</p>
   {originatingRequestConversation(task)&&can('conversation.select')&&can('tasks.close')&&!state.conversationReader&&<>
    <Button variant="secondary" leadingIcon={IconChat} disabled={!!returningConversation} onClick={()=>returnFromFixedDraft(task,fixedDraft)}>返回原对话</Button>
    <p className="pw-field-hint">返回时不会自动加入旧稿，也不会发送消息。</p>
   </>}
  </section>;
  const projectBlocked=protectedDisabled||continuationBlocks(continuation);
  const projectNote=projectFeedback[projectFeedbackKey(task)]||'';
  const nextBuildObserved=originalNodeBuildAfterDependencies(task);
  const buildNext=reviewedBuildAfterDependencies(task,project,actionNames);
  const lateDependencyObserved=lateDependencyForOverview(task);
  const heldBuildKey=heldDependencyForOverview(task);
  const legacyBuildHold=exactDependencyHoldReview(heldBuildKey);
  const viewHeldBuild=()=>{
   const live=bridge.getState(),view=obj(live.view),current=asArray<Task>(live.state.tasks).find(row=>row.id===task.id);
   if(!legacyBuildHold||!can('history.read')||view.taskId!==task.id||view.section!=null||!currentReviewWindow(reviewWindow.current)||
      observedProjectDependencyHold(current)!==heldBuildKey)return;
   showTaskLayer('process');
  };
  const managedPending=managedNodePending(task.id);
  const waitingManaged=!reviewStale?managedPending.filter(pin=>currentManagedNodeOperation(task,pin)?.state!=='receipt'):[];
  const guideManagedCheck=!protectedDisabled&&originalTaskOverview(task)&&can('history.read')&&can('local.execution.observe');
  const managedProjectHold=waitingManaged.length>0&&!!project?.reason&&!actionNames.includes('tasks.resume');
  const projectControls=fixedDraftOptions||(managedProjectHold&&project?<p role="status">{project.reason}</p>:legacyBuildHold&&project? <>
   <p role="status">{project.reason}</p>
   {can('history.read')&&<Button variant="secondary" onClick={viewHeldBuild}>查看原执行记录</Button>}
  </>:project&&<section id="pw-project-continuation-note" tabIndex={-1} aria-label={heldProfileApproval?'原项目与范围':'原项目续接范围'}>
   {review?.task&&!reviewStale&&<ContinuedProjectBinding key={review.reviewHandle} task={review.task} projects={projects} read={can('projectRuntime.read')?args=>bridge.command('projectRuntime.read',args):undefined}/>}
   {!heldProfileApproval&&<p>{buildNext?.receiptWasLate&&lateDependencyObserved?'继续后先核对实际依赖。本机构建命令仍需你单独批准。':buildNext?'已核对同一副本的依赖准备命令退出 0，本轮还没有运行构建。继续后先核对实际依赖；本机构建命令仍需你单独批准。':'继续同一项目任务会立即开始原任务工作，不会自动发送对话草稿。'}</p>}
   <p className="pw-muted">工具调用已用 {String(scope.toolsUsed??'未读取')} / 总上限 {String(scope.toolLimit??'未读取')}；模型调用已用 {String(scope.modelCallsUsed??'未读取')} / 总上限 {String(scope.modelCallLimit??'未读取')}。{!heldProfileApproval&&'本次不增加额度，不延长截止时间。'}</p>
   <p className="pw-muted">{scope.deadlineAt===0?heldProfileApproval?'原任务未设置截止时间。':'沿用原任务未设置截止时间的范围，仍受剩余预算约束。':typeof scope.deadlineAt==='number'?`原截止时间：${new Date(scope.deadlineAt).toLocaleString('zh-CN')}。`:'原任务期限尚未读取。'}{scope.executionPhase==='planning'?(heldProfileApproval?' 原任务仍处于只读规划。':' 原任务仍处于只读规划，继续不会批准具体执行。'):''}</p>
   {!heldProfileApproval&&<>{project.reason&&<p role="status" className="pw-muted">{project.reason}</p>}
   <TextAreaField label={buildNext?'接下来核对什么':'本次修改要求'} value={projectNote} onChange={value=>putProjectFeedback(projectFeedbackKey(task),value)} placeholder={buildNext?'核对同一副本的实际依赖与构建命令':'看过原结果后，希望继续修改什么？'} hint={buildNext?(projectNote===''?'可以自己填写，或点下方按钮填入建议内容，不会继续或运行构建。':'先核对这里的内容，再明确继续。对话草稿不变。'):'必填，最多 8000 字。对话中的文字、引用和附件保持原样。'} isRequired maxLength={8000} showCount isDisabled={projectBlocked||!actionNames.includes('tasks.resume')}/>
   {!buildNext&&actionNames.includes('tasks.resume')&&actionNames.includes('tasks.confirm')&&<Button variant="secondary"
     disabled={projectBlocked||!projectNote.trim()||projectNote.trim().length>8000} onClick={()=>reviewedAction('tasks.resume')}>按本次反馈继续项目任务</Button>}</>}
  </section>);
  const resultFeedback=actionNames.includes('tasks.confirm')&&<Accordion variant="divided" className="pw-compact-disclosures"
   expandedKeys={reviewFeedbackOpen?['result-feedback']:[]} onExpandedChange={keys=>setReviewFeedbackOpen(keys.has('result-feedback'))}>
   <AccordionItem id="result-feedback" title={buildNext?'验收整项工作':'补充说明（可选）'} description={reviewNote?'已填写':undefined}>
    {buildNext&&<p className="pw-field-hint">这里只验收任务目标，不会启动构建；目前尚无本轮构建结果。</p>}
    <TextAreaField label="本次结果反馈" value={reviewNote} onChange={setReviewNote} placeholder="已完成或仍需调整的内容"/>
    {buildNext&&<div className="pw-action-row">{taskResultDecision(task,protectedDisabled,true)}</div>}
   </AccordionItem>
  </Accordion>;
  const scopeEditor=<div className="pw-scope-editor">
   <section aria-label="原任务执行阶段"><h3>执行阶段</h3><p>{scope.executionPhase==='planning'?'只读规划：可读取资料并提交计划，具体执行须先确认准确版本。':'按原任务已有范围执行。'}</p>
    {scope.executionPhase==='planning'&&<p className="pw-muted">{Number(scope.planRevision)>0?`当前计划版本 ${String(scope.planRevision)}；请在原任务授权中核对完整步骤。`:'当前还没有提交计划正文。'} 关闭页面或重新打开不会自动退出规划。</p>}
    {actionNames.includes('tasks.plan.enter')&&<><p className="pw-muted">先保存只读规划阶段，不启动任务，也不增加调用额度、延长截止时间或授权新工具。</p><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.plan.enter')}>进入只读规划</Button></>}
   </section>
   {actionNames.includes('tasks.scope')?<><h3>原任务预算与截止</h3><TextField label="工具调用总上限" inputMode="numeric" pattern="[0-9]*" hint="1–400" value={scopeTools} onChange={setScopeTools} isDisabled={protectedDisabled}/><FluidValueSlider label="拖动设置工具调用上限" value={/^\d+$/.test(scopeTools)?Number(scopeTools):NaN} onChange={value=>setScopeTools(String(value))} minValue={1} maxValue={400} disabled={protectedDisabled} unit="次"/><TextField label="模型调用总上限" inputMode="numeric" pattern="[0-9]*" hint="1–512" value={scopeModels} onChange={setScopeModels} isDisabled={protectedDisabled}/><FluidValueSlider label="拖动设置模型调用上限" value={/^\d+$/.test(scopeModels)?Number(scopeModels):NaN} onChange={value=>setScopeModels(String(value))} minValue={1} maxValue={512} disabled={protectedDisabled} unit="次"/><SelectField label="任务截止" value={scopeMinutes} onChange={setScopeMinutes} options={[{id:'-1',label:'保留原截止时间'},{id:'0',label:'不设置截止时间'},{id:'30',label:'从本次确认起30分钟'},{id:'120',label:'从本次确认起2小时'}]}/><p className="pw-muted">这是新的总上限，不是追加次数。修订范围会使旧计划确认失效。</p></>:<p>这次任务当前不能修订预算与截止时间。</p>}
  </div>;
  const readingReview=taskOpening||reviewPending;
  const refreshControl=can('tasks.review')&&<IconButton icon={IconRefresh} aria-label={review?'重新核对':'读取可用操作'} title={review?'重新核对':'读取可用操作'} disabled={readingReview||questionPending} onClick={showTaskReview}/>;
  const teamApprovalWork=review&&!reviewStale&&currentReviewWindow(reviewWindow.current)&&obj(snapshot?.view).section===null&&
    task.raw?.contextAvailable===true&&task.raw?.contextWithdrawn!==true&&task.raw?.sourceCurrentReadRequired!==true?review.task:undefined;
  const approvalRead=obj(review?.capabilityApprovalRead),approvalIds=Array.isArray(approvalRead.pendingApprovalIds)?approvalRead.pendingApprovalIds.filter((id:unknown):id is string=>typeof id==='string'):[];
  const approvalNavigation=approvalIds.length>1&&<SelectField label="待处理操作" value={String(review?.capabilityApprovalRead?.approvalId||'')} onChange={id=>loadTaskReview(task.id,id)} options={approvalIds.map((id:string,index:number)=>({id,label:`第 ${index+1} 项（共 ${approvalIds.length} 项）`}))} isDisabled={protectedDisabled} hint="逐项查看完整内容，每项操作分别确认。"/>;
  const phoneUnknown=unresolved.filter(op=>op.format===PHONE_RECORD),ordinaryUnknown=unresolved.filter(op=>op.format!==PHONE_RECORD&&op.tool!=='team_integrate_file'&&
   !managedPending.some(pin=>pin.operationId===op.operationId&&pin.runId===op.runId));
  const originalIntegrator=task.raw?.origin?.kind==='team_integration'&&!!task.raw?.runId&&Number.isSafeInteger(task.raw?.coordinatorEpoch)&&can('tasks.recoverTeamFile');
  const hasDecision=!!review?.ownerQuestion||!!project&&!managedProjectHold||actionNames.some(action=>action!=='tasks.scope'&&action!=='tasks.plan.enter'&&(action!=='tasks.resolve'||ordinaryUnknown.some(op=>!!op.operationId)));
  const phoneInterrupted=interruptedPrivatePhone(task,state.livePrivatePhones);
  const pausedSurface=task.status==='paused'?privatePhoneSurface(task,state.livePrivatePhones):null;
  const ordinaryResume=!project&&!review?.ownerQuestion&&!approved.length&&!unresolved.length&&actionNames.includes('tasks.resume')&&actionNames.every(action=>['tasks.resume','tasks.scope','tasks.plan.enter','tasks.deleteScreenshot'].includes(action));
  const resumeFeedback=<Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={reviewFeedbackOpen?['resume-feedback']:[]} onExpandedChange={keys=>setReviewFeedbackOpen(keys.has('resume-feedback'))}><AccordionItem id="resume-feedback" title={phoneInterrupted?'给新一轮的补充（可选）':'补充说明（可选）'} description={reviewNote?'已填写':undefined}><TextAreaField label="本次补充" value={reviewNote} onChange={setReviewNote} placeholder="需要调整的内容" isDisabled={protectedDisabled}/></AccordionItem></Accordion>;
  const overviewActive=taskOpen&&!taskOpening&&openedTaskId.current===task.id&&taskLayer==='overview';
  const originalPhoneTool=!phoneViewerObscured&&!phoneViewerExpanded&&originalTaskOverview(task)&&can('history.read')?originalPhoneToolRecord(task):undefined;
  const draftView=obj(snapshot?.view);
  const currentDraft=overviewActive&&!document.hidden&&draftView.taskId===task.id&&draftView.section==null?fixedDraftReviewTarget(task):null;
  const reviewedDraft=currentDraft&&fixedDraftReviewTarget(review?.task);
  const firstDraft=firstOriginalDraft(task,currentDraft);
  const draftReviewReady=!!currentDraft&&task.status==='needs_confirmation'&&task.raw?.hasUnresolvedOperations===false&&!taskNeedsReconciliation(task);
  // A genuinely saved ordinary draft is the reading surface, even while its
  // short-lived review is still loading. Dependency preparation is not a build.
  const inspectResult=stage==='ended'||draftReviewReady&&firstDraft||(actionNames.includes('tasks.confirm')&&!buildNext);
  const decisionFirst=stage==='attention'&&!inspectResult;
  const originalDraftReview=draftReviewReady&&!!reviewedDraft&&currentDraft.taskId===reviewedDraft.taskId&&currentDraft.runId===reviewedDraft.runId&&
   currentDraft.epoch===reviewedDraft.epoch&&currentDraft.draftId===reviewedDraft.draftId&&review?.task?.status==='needs_confirmation'&&
   !reviewStale&&!approved.length&&!unresolved.length&&!review?.ownerQuestion&&actionNames.includes('tasks.confirm')&&
   can('tasks.confirm')&&currentReviewWindow(reviewWindow.current);
  const durableDraftDecision=currentDraft?getLoadedFixedDraftGoalDecision(task):undefined;
  const draftAcceptanceSaved=overviewActive&&reviewSubmitted&&draftAcceptance&&review?.reviewHandle===draftAcceptance.reviewHandle&&
    currentReviewWindow(draftAcceptance.window)&&!taskErrors[task.id]&&!state.taskActionErrors?.[task.id]&&savedDraftAcceptanceMatches(task,draftAcceptance);
  const settledOriginalDraft=draftAcceptanceSaved&&firstDraft&&draftAcceptance?draftAcceptance:undefined;
  const inlineDraftDecision=!buildNext?inlineDraftDecisionFor(task,actionNames):null;
  const draftOtherOperations=originalDraftReview&&projectControls&&<Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="after-original-draft" title="更多操作">{projectControls}</AccordionItem></Accordion>;
  const ordinaryWork=task.raw?.executionRole==='owner_work'&&!task.raw?.developmentContext&&!task.raw?.ownerPhoneControl&&!task.raw?.ownerDeviceWork;
  const taskToolsInOverview=taskToolsPlace?.taskId===task.id&&taskToolsPlace.overview;
  const originalToolsWindow=overviewActive&&obj(snapshot?.view).taskId===task.id&&obj(snapshot?.view).section==null;
  const retainedScriptOutputs=taskToolsInOverview&&ordinaryWork&&originalToolsWindow&&
   can('taskCapabilities.artifact')&&can('taskCapabilities.export')?retainedLocalScriptOutputs(task):undefined;
  const taskTools=taskToolsPlace?.taskId===task.id&&originalToolsWindow&&!task.raw?.ownerPhoneControl&&
   task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&can('taskCapabilities.open')&&
   <TaskCapabilityWorkspace key={'capabilities:'+task.id} taskId={task.id} run={act} exportState={state.taskCapabilityExport} retainedOutputs={retainedScriptOutputs}
    onBackChange={registerCapabilityBack} registerBeforeLeave={registerTaskPolicyBeforeLeave} onOpenTools={next=>void openRelatedWorkspace(next||'capabilities')}/>;
  const currentResultDecision=ordinaryWork&&overviewActive&&task.status==='needs_confirmation'&&!reviewStale&&
   actionNames.includes('tasks.confirm')&&can('tasks.confirm')&&currentReviewWindow(reviewWindow.current);
  const teamReportChoice=currentResultDecision&&!protectedDisabled&&!approved.length&&!unresolved.length&&!review?.ownerQuestion&&
   can('tasks.review')&&!state.conversationReader&&!workSupplementTarget?linkedTeamReportChoice(review?.task):undefined;
  // A saved original plan has its own decision. Opening another round is
  // still possible, but it is not the action that reviews this plan.
  const planOwner=ordinaryChatWorkRow(task,String(task.raw?.conversationId||''),true);
  const planApproval=approved.length===1?approved[0]:null;
  const originalPlan=obj(planApproval?.webReview?.executionPlan);
  const currentPlanDecision=!!planOwner&&overviewActive&&originalTaskOverview(task)&&task.status==='awaiting_user'&&
   task.raw?.status===task.status&&task.raw.hasUnresolvedOperations===false&&task.raw.cancelRequested!==true&&
   task.raw.workProfileRef==null&&task.raw.workProfileIntent==null&&
   !reviewStale&&!review?.ownerQuestion&&!unresolved.length&&can('tasks.approve')&&actionNames.includes('tasks.approve')&&
   currentReviewWindow(reviewWindow.current)&&currentScope.executionPhase==='planning'&&
   planApproval?.tool==='execution_plan_review'&&planApproval.state==='pending'&&planApproval.runId===planOwner.runId&&
   Number.isSafeInteger(planApproval.expiresAt)&&planApproval.expiresAt>Date.now()&&
   planApproval.webReview?.kind==='execution_plan'&&planApproval.webReview.canApprove===true&&
   originalPlan.taskId===task.id&&originalPlan.runId===planOwner.runId&&originalPlan.epoch===planOwner.epoch&&
   originalPlan.scopeRevision===currentScope.revision&&originalPlan.planRevision===currentScope.planRevision&&
   originalPlan.planArtifactRef===currentScope.planArtifactRef&&originalPlan.planHash===currentScope.planHash;
  const requestWork=ordinaryWork?ownerWorkIdentity(task):null;
  const declinedTeam=overviewActive&&obj(snapshot?.view).taskId===task.id?originalDeclinedTeamStart(task):null;
  const teamDeclineSettlesReview=!!declinedTeam&&review?.task?.id===declinedTeam.identity.taskId&&
   review.task.raw?.runId===declinedTeam.identity.runId&&review.task.raw?.coordinatorEpoch===declinedTeam.identity.epoch&&
   approved.length===1&&(approved[0].approvalId||approved[0].id)===declinedTeam.id&&!review.ownerQuestion&&!unresolved.length&&
   actionNames.includes('tasks.approve')&&actionNames.every(action=>['tasks.approve','tasks.scope','tasks.deleteScreenshot'].includes(action));
  const failureOverview=overviewActive&&obj(snapshot?.view).taskId===task.id&&obj(snapshot?.view).section==null&&can('local.execution.observe')?originalFirstBuildFailure(task):undefined;
  const managedLogs=originalTaskOverview(task)&&can('local.execution.observe')?managedPending.flatMap(pin=>{
   const row=currentManagedNodeOperation(task,pin);return row&&pin.canObserve?[{pin,row}]:[];
  }):[];
  const pendingFailure=!!failureOverview&&managedLogs.some(({pin})=>pin.runId===failureOverview.runId&&pin.operationId===failureOverview.operationId);
  const buildTarget=overviewActive&&!state.filePath&&!projectPreview.expanded&&!projectWorkReviewOpen&&obj(snapshot?.view).taskId===task.id&&obj(snapshot?.view).section==null&&
   can('tasks.close')&&can('projects.development.read')&&can('projects.development.files.open')?originalNodeBuildCopy(task):null;
  const buildCopy=buildTarget&&projects.some(project=>project.id===buildTarget.projectId)&&
    (route==='projects'&&state.projectId===buildTarget.projectId||failureOverview&&['tasks','chat','butler'].includes(route)&&
      (state.projectId===buildTarget.projectId||can('projects.select')))?buildTarget:null;
  const viteEditor=overviewActive?originalViteEditorCopy(task):null;
  const taskFiles=obj(snapshot?.view).taskId===task.id&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&task.raw?.contextAvailable!==false&&task.raw?.taskProjection!=='source_unavailable'&&task.raw?.kind!=='inference'&&can('attachmentWorkspace.open')
   ? {taskId:task.id,supported:can('tasks.resultFiles'),onOpen:(file:TaskResultFile,target:HTMLButtonElement)=>openTaskResultFile(task.id,file,target),
      onOriginalRequest: originatingRequestConversation(task) && can('conversation.select') && !state.chatSubmitting && !returningConversation ? () => void returnToTaskConversation(task) : undefined,
      onBrowse:()=>{void openWorkFiles(task.id).catch(error=>setLocalError(error instanceof Error?error.message:String(error)));}}
   : undefined;
  const latestProgress=stage==='running'&&running(task)&&<section key="task-current-progress" className="pw-work-current-progress">
   <h3>目前进展</h3>
   <BlurCrossfade identity={`${task.id}:${progress.operationId||progress.position||progress.title}`}>
    <strong>{progress.source==='task'&&progress.status!=='unknown'?'最近确认的状态':progress.title}</strong>
    <p dir="auto">{progress.detail||(progress.source==='task'?progress.title:progress.stateLabel)}</p>
   </BlurCrossfade>
  </section>;
  const originalEvidence=<TaskOutcome key="task-evidence" registerProjectDraftBeforeLeave={registerProjectDraftBeforeLeave} projectDraftSourceRetired={task.raw?.contextAvailable===false||task.raw?.contextWithdrawn===true||task.raw?.sourceCurrentReadRequired===true||task.raw?.taskProjection==='source_unavailable'} task={review&&!reviewStale?review.task:task} messages={messages} resultFiles={taskFiles}
   active={overviewActive} focusedOriginalDraft={focusedOriginalDraft?.taskId===task.id?focusedOriginalDraft:undefined} showEmptyResult={inspectResult&&task.status==='completed'} deferExecutionDetails={!inspectResult&&task.status!=='failed'}
   draftGoalDecision={draftAcceptanceSaved?settledOriginalDraft:durableDraftDecision} lateDependencyObserved={lateDependencyObserved} reviewedDependencyHoldKey={legacyBuildHold?heldBuildKey:undefined}
   draftReview={inlineDraftDecision?{original:inlineDraftDecision,render:readback=><section className="pw-form-flow" aria-label="验收本轮原改稿">
    <div className="pw-section-heading"><h4>验收本轮原改稿</h4>{refreshControl}</div>{resultFeedback}
    <div className="pw-action-row">{taskResultDecision(task,protectedDisabled,false,readback)}</div>
   </section>}:undefined}
   canReadPhoneTaskOriginal={overviewActive&&!phoneWorkObscured&&!phoneViewerExpanded&&!obj(snapshot?.view).section&&obj(snapshot?.view).taskId===task.id&&can('phone.result.taskOriginal')}
   phoneWorkReportLocation={phoneWorkLocation(task)}
   canReadReport={can('ownerWork.report.read')} readIdentity={`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}:${navigationRevision}`}
   onContinueBuildCopy={buildCopy&&!failureOverview?()=>void openOriginalBuildCopy(buildCopy):undefined}
   onResumeViteBuildEditor={viteEditor&&currentViteEditorVisit(viteEditor,snapshot)?()=>void openOriginalBuildCopy(viteEditor):undefined}
   onCopy={copyWithLocalFeedback} onFollowUp={fixedDraft||reportHistory?undefined:followOriginal} onOpenProjectPackage={!packageOpening&&!taskOpening&&can('projects.package.preview.inspect')&&can('projects.package.preview.open')&&can('tasks.close')?openTaskPackage:undefined}
   attachmentConversation={String(state.conversationId||'')} canOpenAttachment={can('attachments.open')} canLocateAttachment={can('attachmentWorkspace.open')}/>;
  const evidence=<TaskPackageProjectContext.Provider key="task-evidence" value={taskOpen&&!taskOpening&&can('projects.localSource.openPrepared')&&can('tasks.close')?(original,pin)=>{
    if(document.hidden||original!==taskId||openedTaskId.current!==original)return;
    void navigate('projects',()=>run('projects.localSource.openPrepared',pin));
  }:null}>{failureOverview&&!pendingFailure&&<ConversationModelPickerVisibility key={`original-failure:${task.id}:${failureOverview.runId}:${failureOverview.operationId}`}>
    {pickerVisible=><ProjectExecutionWorkspace task={task} focusFailure run={act} active={!pickerVisible&&!phoneViewerObscured&&!phoneViewerExpanded}
      projectName={projects.find(project=>project.id===failureOverview.projectId)?.name} editFailedCopy={buildCopy?()=>void openOriginalBuildCopy(buildCopy):undefined}
      prepareFailure={can('tasks.review')?prepareFailedBuild:undefined}/>}</ConversationModelPickerVisibility>}{originalEvidence}</TaskPackageProjectContext.Provider>;
  const workFacts=<OwnerWorkFacts key="task-handling" task={task} onTeam={can('teams.readRun')?openWorkTeam:undefined}
   reportLabel={reportHistory?'在原对话定位汇报':undefined}
   onReport={reportHistory?overviewActive&&!returningConversation&&!state.conversationReader?()=>void readReportInConversation(task):undefined:
    can('conversation.select')?()=>void returnToTaskConversation(task):undefined}
   onReconcile={overviewActive&&can('tasks.open')?target=>openTask(target.taskId,'process',false,undefined,undefined,target):undefined}/>;
  const foldAcceptedDraftReview=(durableDraftDecision?.completed===true||draftAcceptanceSaved&&draftAcceptance?.completed===true)&&
   !continuation&&!approved.length&&!unresolved.length&&!taskNeedsReconciliation(task);
  const showProjectReview=canReadProject&&!continuationBlocks(continuation);
  const pendingNodeCommand=overviewActive&&obj(snapshot?.view).taskId===task.id&&obj(snapshot?.view).section==null&&can('tasks.review')
   ?originalPendingNodeCommand(task):null;
  const readPendingNodeCommand=()=>{
   const live=bridge.getState(),nativeView=obj(live.view),current=asArray<Task>(live.state.tasks).find(row=>row.id===task.id);
   if(!pendingNodeCommand||reviewPending||questionPending||document.hidden||openedTaskId.current!==task.id||
      nativeView.taskId!==task.id||nativeView.section!=null)return;
   if(JSON.stringify(originalPendingNodeCommand(current))!==JSON.stringify(pendingNodeCommand)){
    setTaskErrors(previous=>({...previous,[task.id]:'原命令已变化或过期，请重新读取原工作；未提交决定。'}));return;
   }
   loadTaskReview(task.id,pendingNodeCommand.approvalId);
  };
  const reviewTools=(!fixedDraft||draftReviewReady)&&!readingReview&&(!review||teamDeclineSettlesReview||reviewStale&&!review.ownerQuestion||ordinaryResume&&(reviewSubmitted||!!reviewReceipt||!!state.taskActionErrors?.[task.id]))&&
    <div className="pw-section-heading pw-task-review-tools">{pendingNodeCommand?
      <Button disabled={questionPending} onClick={readPendingNodeCommand}>{pendingNodeCommand.action==='dependencies'?'核对本次依赖准备':'核对本机构建命令'}</Button>:
      <>{showProjectReview?<Button variant="secondary" onClick={showTaskReview}>{heldProfileApproval?'核对原项目与范围':heldBuildKey?'读取原执行状态':draftReviewReady?'核对本轮改稿':nextBuildObserved?'核对下一步本机构建':observedDraft?'核对原改稿的可用操作':'核对继续原项目任务'}</Button>:<span className="pw-grow"/>}{(!showProjectReview||!heldBuildKey&&!lateDependencyObserved&&!draftReviewReady&&!foldAcceptedDraftReview)&&refreshControl}</>}</div>;
  const reviewStatus=<>
    {readingReview&&<ThinkingLoader phase="正在读取当前任务"/>}
    {heldProfileApproval&&<p className="pw-field-hint" role="status">这一步的批准已保存，原步骤未派发。当前不能在这里续接原工作配置。</p>}
    {reviewStale&&!teamDeclineSettlesReview&&!draftAcceptanceSaved&&<p className="pw-error">任务已有新进展，请重新核对后再操作。</p>}
    {continuation&&<section aria-label="原项目续接回执"><p className="pw-muted" role="status">{continuation.message}</p>{!project&&projectNote&&<Accordion variant="divided"><AccordionItem id="continuation-feedback" title="本次反馈"><p className="pw-approval-copy">{projectNote}</p></AccordionItem></Accordion>}{continuation.status==='unknown'&&can('tasks.review')&&<Button variant="secondary" disabled={readingReview} onClick={showTaskReview}>核对原任务状态</Button>}{['continued','unknown'].includes(continuation.status)&&can('conversation.select')&&<Button variant="ghost" disabled={!!state.chatSubmitting||!!returningConversation} onClick={()=>returnToContinuationConversation(task,continuation)}>返回原对话，草稿保留</Button>}</section>}
    {draftAcceptanceSaved?<p className="pw-muted" role="status" data-fixed-draft-decision-fallback={settledOriginalDraft?true:undefined}>{draftAcceptance?.completed?'验收已记录，原稿保留。':'已记录目标尚未达到，原稿保留。'}</p>:!continuation&&!teamDeclineSettlesReview&&(reviewSubmitted||reviewReceipt)&&!review?.ownerQuestion&&(reviewReceipt?<p className="pw-muted" role="status">{reviewReceipt}</p>:state.taskActionErrors?.[task.id]?<p className="pw-error" role="alert">本次提交未确认。请先重新核对原任务，再决定是否操作。</p>:<p className="pw-muted" role="status">已提交这次决定，等待任务回执。</p>)}
  </>;
  const decision=(heldProfileApproval||declinedTeam||originalIntegrator||review||reviewPending||continuation||(canReadProject||attention(task))&&can('tasks.review'))&&<div key="task-decision" className="pw-task-decision">
      {taskLayer==='overview'&&reviewStatus}
      {teamReportChoice&&<Button variant="secondary" trailingIcon={IconChevronRight} data-team-evidence-entry={teamReportChoice.teamRunId}
       onClick={event=>selectTeamReport(task,teamReportChoice,event.currentTarget)}>用本轮团队汇总核对目标</Button>}
      {taskLayer==='overview'&&!reviewStale&&actionNames.includes('tasks.confirm')&&noSavedDraftInReview(task,review)&&<p className="pw-field-hint" role="status">本轮还没有可核对的独立改稿。</p>}
      {originalIntegrator&&<TeamOriginalFileReceipt key={task.id+':'+task.raw!.runId+':'+task.raw!.coordinatorEpoch} id={task.id} run={task.raw!.runId} epoch={task.raw!.coordinatorEpoch} directory={task.raw!.originalTeamFileEffects??(!reviewStale?review?.task?.raw?.originalTeamFileEffects:undefined)} disabled={taskOpening||reviewPending||!overviewActive} recover={input=>bridge.command('tasks.recoverTeamFile',input)}/>}
      {originalIntegrator&&overviewActive&&can('tasks.teamRemainder.read')&&can('tasks.teamRemainder.continue')&&(task.raw!.mayReadOriginalFileRemainder??(!reviewStale&&review?.task?.raw?.mayReadOriginalFileRemainder))===true&&<TeamRemainingFiles key={task.id+':remaining:'+task.raw!.runId+':'+task.raw!.coordinatorEpoch} id={task.id} run={task.raw!.runId} epoch={task.raw!.coordinatorEpoch} disabled={reviewPending||taskOpening} command={(action,input)=>bridge.command(action,input)}/>}
      {reviewTools&&(foldAcceptedDraftReview?<Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="accepted-draft-options" title="更多操作">{reviewTools}</AccordionItem></Accordion>:reviewTools)}
      {!inlineDraftDecision&&(!actionNames.includes('tasks.confirm')||reviewStale)&&fixedDraftOptions}
      {waitingManaged.filter(pin=>!managedLogs.some(log=>log.pin===pin)).map(pin=><p key={pin.operationId} className="pw-field-hint" role="status">
       {pin.phase==='dependencies'?'原依赖准备':'原构建'}仍待核对。{pin.canObserve?'当前窗口不能读取这次原执行的回执。':
        guideManagedCheck&&bareManagedNodeCheck(task,pin)?'在「过程」→「工具调用」中展开「原执行记录」，点「核对这次执行」。不会重新运行。':
        '尚未核对到原进程回执，不能判断是否已经执行。'}
      </p>)}
      {managedLogs.map(({pin,row})=>{
       const actualFailure=!!failureOverview&&failureOverview.runId===pin.runId&&failureOverview.operationId===pin.operationId;
       return <ConversationModelPickerVisibility key={JSON.stringify([pin.taskId,pin.runId,pin.epoch,pin.operationId,pin.projectId,pin.checkoutId])}>{pickerVisible=>
        <ProjectExecutionWorkspace task={task} operation={row} originalPending={pin} focusFailure={actualFailure} run={act}
         active={!pickerVisible&&!phoneViewerObscured&&!phoneViewerExpanded}
         projectName={actualFailure?projects.find(project=>project.id===pin.projectId)?.name:undefined}
         editFailedCopy={actualFailure&&buildCopy?()=>void openOriginalBuildCopy(buildCopy):undefined}
         prepareFailure={actualFailure&&can('tasks.review')?prepareFailedBuild:undefined}/>
       }</ConversationModelPickerVisibility>;
      })}
      {review&&!reviewStale&&phoneUnknown.length>0&&<PhoneOriginalActionReview key={review.reviewHandle} taskId={task.id} rows={phoneUnknown} more={task.raw?.privatePhoneUnconfirmed?.hasMore===true} disabled={protectedDisabled} allowRecord={actionNames.includes('tasks.resolve')} submit={args=>reviewedAction('tasks.resolve',args)}/>}
      {review&&!teamDeclineSettlesReview&&(!reviewStale||!!review.ownerQuestion)&&(inlineDraftDecision?draftOtherOperations:fixedDraft&&!actionNames.includes('tasks.confirm')?null:ordinaryResume?resumeFeedback:hasDecision?<PermissionReview title={originalDraftReview?'验收本轮原独立改稿':legacyBuildHold?'原执行记录':project&&!managedProjectHold&&!heldProfileApproval?'继续原项目任务':actionNames.includes('tasks.confirm')?'这份结果是否达到目标？':heldProfileApproval&&project?'原项目与范围':'处理这次任务'} headerAction={legacyBuildHold?undefined:refreshControl}><div className="pw-protected-actions">{originalDraftReview?<p className="pw-field-hint">这里只验收本轮的原固定稿，不会将你另存的改稿当作本次结果。</p>:projectControls}{review.ownerQuestion&&<OwnerTaskQuestion key={review.reviewHandle} question={review.ownerQuestion as OwnerQuestionView} text={questionAnswer} onTextChange={setQuestionAnswer} disabled={protectedDisabled} canAnswer={actionNames.includes('tasks.answerQuestion')} canChoose={actionNames.includes('tasks.answerQuestionChoice')} submit={submitQuestion} onSaved={clearSavedQuestionAnswer}/>}{resultFeedback}{draftOtherOperations}{actionNames.includes('tasks.approve')&&approvalNavigation}{actionNames.includes('tasks.approve')&&approved.map(p=><TaskApproval key={`${review.reviewHandle}:${p.approvalId||p.id}`} approval={p} reviewTask={teamApprovalWork} executionScope={scope} disabled={protectedDisabled} onDecision={args=>reviewedAction('tasks.approve',args)}/>)}{actionNames.includes('tasks.resolve')&&ordinaryUnknown.filter(op=>!!op.operationId).map(op=><section className="pw-approval-item" key={op.operationId}><h4>{op.title||op.toolName||'动作结果核对'}</h4><p>{op.summary||op.message||'请依据实际观察核对这次动作是否已经发生。'}</p><div className="pw-action-row"><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.resolve',{operationId:op.operationId,outcome:'occurred'})}>确认已发生</Button><Button variant="secondary" disabled={protectedDisabled} onClick={()=>reviewedAction('tasks.resolve',{operationId:op.operationId,outcome:'not_occurred'})}>确认未发生</Button></div></section>)}{!project&&actionNames.includes('tasks.resume')&&<TextAreaField label="本次补充" value={reviewNote} onChange={setReviewNote} placeholder="继续前需要补充什么？"/>}{!review.actions?.length&&<p className="pw-muted">当前没有待作出的新决定。</p>}</div></PermissionReview>:phoneUnknown.length||task.raw?.originalTeamFileEffects?.rows?.length?null:<div className="pw-section-heading pw-task-review-tools">{declinedTeam||waitingManaged.length?<span className="pw-grow"/>:<p className="pw-muted">当前没有待作出的新决定。</p>}{refreshControl}</div>)}
      {declinedTeam&&<DeclinedTeamStart key={declinedTeam.id} decision={declinedTeam}/>}
     </div>;
  return <Tabs className="pw-work-object-tabs" selectedKey={taskLayer==='scope'?'goal':taskLayer} onSelectionChange={key=>showTaskLayer(String(key) as TaskLayer)}>
   <TabList aria-label="查看这件工作"><Tab id="overview">概览</Tab><Tab id="goal">目标</Tab><Tab id="process">过程</Tab></TabList>
   {/* Keep the result document mounted while reading deeper task details. */}
   <TabPanel id="overview" shouldForceMount hidden={taskLayer!=='overview'}>
    <section className="pw-task-overview pw-task-review" aria-label="这件工作的进展、决定与成果">
     {overviewActive&&<WorkExecutionPlace task={task} projects={projects} remoteProfiles={state.features?.remote?.profiles} canReadPhoneResources={!phoneViewerObscured&&!phoneViewerExpanded&&!obj(snapshot?.view).section&&obj(snapshot?.view).taskId===task.id&&can('history.read')}/>}
     {overviewActive&&task.raw?.ownerDeviceWork?.directWorkspace&&!phoneViewerObscured&&!phoneViewerExpanded&&<OriginalWindowsTextOverview taskId={task.id}/>}
     {originalPhoneTool&&<div className="pw-action-row"><Button variant="secondary" leadingIcon={IconTool} onClick={event=>openOriginalPhoneTool(task,originalPhoneTool,event.currentTarget)}>查看本轮手机操作</Button></div>}
     {overviewActive&&!phoneViewerObscured&&!phoneViewerExpanded&&!obj(snapshot?.view).section&&obj(snapshot?.view).taskId===task.id&&can('history.read')&&<PhoneServiceRecovery key={JSON.stringify([task.id,task.raw?.runId,task.raw?.coordinatorEpoch])} task={task} readiness={state.phoneReadiness} openSettings={can('settings.openSystem')?()=>act('settings.openSystem',{section:'accessibility'},task.id):undefined} review={can('tasks.review')&&!readingReview&&!questionPending?showTaskReview:undefined}/>}
     {taskOpen&&!taskOpening&&openedTaskId.current===task.id&&<PhoneMainOwnerStep key={'main-owner-step:'+task.id} task={task} visible={overviewActive&&!phoneViewerObscured&&!phoneViewerExpanded&&obj(snapshot?.view).taskId===task.id} run={act} can={can} onProcess={()=>showTaskLayer('process')}/>}
     {phoneInterrupted&&overviewActive&&!phoneViewerObscured&&!phoneViewerExpanded&&!obj(snapshot?.view).section&&obj(snapshot?.view).taskId===task.id&&<InterruptedPhoneRecord
       reviewed={!!review&&!reviewStale&&!readingReview} canStart={actionNames.includes('tasks.resume')} onHistory={can('history.read')?()=>showTaskLayer('process'):undefined}
       onReview={can('tasks.review')&&!readingReview&&!questionPending?showTaskReview:undefined}/>}
     {inspectResult?[evidence,taskToolsInOverview&&taskTools,decision,workFacts]:decisionFirst?[mainPhoneStep(task)&&!approved.length&&!unresolved.length&&!review?.ownerQuestion?null:decision,workFacts,evidence,taskToolsInOverview&&taskTools]:[latestProgress,workFacts,evidence,taskToolsInOverview&&taskTools,decision]}
     {taskLayer==='overview'&&ownerWorkActions(task,currentResultDecision,currentPlanDecision)}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&task.raw?.hasOwnerWorkPreparations&&<ButlerPreparationEntries key={'preparations-task:'+task.id} sourceTaskId={task.id} conversationId={privateTaskConversationId(task)||''} active={taskLayer==='overview'&&!preparationTarget} refreshKey={String(task.raw?.ownerWorkPreparationCount||0)+':'+preparationRevision} can={can} run={bridge.command} onOpen={setPreparationTarget}/>}
     {task.raw?.ownerWorkPreparation?.sourceTaskId&&task.raw?.ownerWorkPreparation?.proposalId&&task.raw?.ownerWorkPreparation?.sourceConversationId&&<Button variant="ghost" leadingIcon={IconChat} onClick={()=>setPreparationTarget({sourceTaskId:task.raw!.ownerWorkPreparation.sourceTaskId,proposalId:task.raw!.ownerWorkPreparation.proposalId,sourceConversationId:task.raw!.ownerWorkPreparation.sourceConversationId})}>查看管家的原工作准备</Button>}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&workSupplementTarget?.taskId===task.id&&can('ownerWork.supplement.read')&&<OwnerWorkSupplement key={workIdentityKey(workSupplementTarget)} target={workSupplementTarget} task={task} can={can}
       registerBeforeLeave={(guard,panel)=>registerWorkSupplementBeforeLeave(workSupplementTarget,panel,guard)}
       onClose={flush=>{if(!closeCurrentWorkSupplement(true))void flush().then(saved=>{if(saved)dismissWorkSupplement(workSupplementTarget)})}} onSelectOriginal={identity=>void targetWork(task,identity)}/>}
     {workSupplementTarget?.taskId!==task.id&&pendingInputs.some(item=>String(item.draftKey).startsWith('work:')&&item.originalTarget?.taskId===task.id)&&<div className="pw-work-pending-links" aria-label="原工作补充">{pendingInputs.filter(item=>String(item.draftKey).startsWith('work:')&&item.originalTarget?.taskId===task.id).map(item=><Button key={item.clientMessageId} variant="secondary" size="small" onClick={()=>returnToWorkInput(item)}>核对原工作中的补充</Button>)}</div>}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&task.raw?.ownerDeviceWork&&can('deviceTaskScope.open')&&<DeviceTaskScopeWorkspace key={'device:'+task.id} taskId={task.id} run={act} directed={task.raw.ownerDeviceWork.directPhone} directedWorkspace={task.raw.ownerDeviceWork.directWorkspace}
       startedPhone={overviewActive&&!phoneViewerObscured&&!phoneViewerExpanded&&!obj(snapshot?.view).section&&obj(snapshot?.view).taskId===task.id&&!!task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0}
       canReadDispatch={can('deviceTaskScope.readPhoneDispatchFact')}
       onOpenOriginal={task.raw.ownerDeviceWork.directPhone&&can('devices.readPhoneGoalArchive')?()=>{const p=task.raw!.ownerDeviceWork.directPhone;
         void openRelatedWorkspace('devices',undefined,()=>setSentPhoneFocus({taskId:task.id,deviceId:p.deviceId,instanceId:p.instanceId,certificateFingerprint:p.certificateFingerprint,navigation:navigationWindow.current}));}:undefined} onBackChange={registerDeviceScopeBack} onSaved={()=>loadTaskReview(task.id)} onOpenDevices={()=>{
         if(!taskOpen || openedTaskId.current!==task.id || document.hidden) return;
         const work=task.raw!.ownerDeviceWork, phone=Object.prototype.hasOwnProperty.call(work,'directPhone'), windows=Object.prototype.hasOwnProperty.call(work,'directWorkspace');
         if(!phone&&!windows){void openRelatedWorkspace('devices');return;}
         const p=phone?work.directPhone:work.directWorkspace;
         if(phone===windows||!/^pw1_[a-f0-9]{64}$/.test(p?.deviceId||'')||typeof p?.instanceId!=='string'||
            !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(p.instanceId)||!/^[a-f0-9]{64}$/.test(p?.certificateFingerprint||'')){
           setLocalError('原任务没有能核对的唯一执行设备，没有改为其他伙伴。');return;
         }
         const original={taskId:task.id,deviceId:p.deviceId,instanceId:p.instanceId,certificateFingerprint:p.certificateFingerprint};
         void openRelatedWorkspace('devices',undefined,()=>setSentPhoneFocus({...original,purpose:'partner_connection',navigation:navigationWindow.current}));
       }}/>}
     {taskOpen&&openedTaskId.current===task.id&&!taskOpening&&task.raw?.ownerPhoneControl?.format==='pocketworld.owner-phone-control.v1'&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&<>
      {!task.raw?.runId&&can('deviceTaskScope.open')&&<DeviceTaskScopeWorkspace key={'manual-scope:'+task.id} taskId={task.id} run={act} directedManual={task.raw.ownerPhoneControl.manualPhoneTarget}
         onBackChange={registerDeviceScopeBack} onSaved={()=>loadTaskReview(task.id)}
         onOpenDevices={()=>{const p=task.raw!.ownerPhoneControl.manualPhoneTarget;if(!p){void openRelatedWorkspace('devices');return;}
           if(typeof p.deviceId!=='string'||typeof p.instanceId!=='string'||typeof p.certificateFingerprint!=='string'||!/^pw1_[a-f0-9]{64}$/.test(p.deviceId)||!/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(p.instanceId)||!/^[a-f0-9]{64}$/.test(p.certificateFingerprint)){setLocalError('原手控设备身份无法核对，未打开其他伙伴。');return;}
           void openRelatedWorkspace('devices',undefined,()=>setSentPhoneFocus({taskId:task.id,deviceId:p.deviceId,instanceId:p.instanceId,certificateFingerprint:p.certificateFingerprint,purpose:'partner_connection',navigation:navigationWindow.current}));
         }}/>}
      {can('phoneControl.open')&&<ConversationModelPickerVisibility key={'manual:'+task.id}>{pickerVisible=><PhoneControlWorkspace taskId={task.id} hasScope={asArray(task.raw?.deviceSourceScope?.routes).length===1} started={!!task.raw?.runId} visible={taskLayer==='overview'&&!pickerVisible&&!phoneViewerObscured} run={act} onChanged={()=>loadTaskReview(task.id)}/>}</ConversationModelPickerVisibility>}
     </>}
     {taskOpen && openedTaskId.current===task.id && !taskOpening && isPrivateLocalPhone(task) && can('phone.pauseNote.read') && can('phone.pauseNote.continue') &&
       <PhonePauseInstruction key={'phone-pause-note:'+task.id} task={task} livePause={!!pausedSurface?.paused&&!pausedSurface.stopRequested&&pausedSurface.surfaceState!=='other_surface'&&pausedSurface.surfaceState!=='handoff_unconfirmed'} interrupted={phoneInterrupted} visible={taskLayer==='overview' && !phoneViewerObscured && !phoneViewerExpanded && obj(snapshot?.view).taskId===task.id} run={act}/>}
     {taskOpen&&phoneViewerFor(task)}
     {taskOpen&&task.raw?.feature==='remote'&&can('remoteTask.open')&&<RemoteTaskWorkspace key={task.id} taskId={task.id} raw={obj(task.raw)} receipt={task.hostReceipt} savedFiles={asArray<Json>(task.raw?.remoteSnapshotAttachments)} openProject={can('remoteTask.stageSavedSources')&&can('projects.localSource.openPrepared')?pin=>{void navigate('projects',()=>run('projects.localSource.openPrepared',pin));}:undefined}/>}
     {overviewActive&&task.raw?.mayReadProjectTeamFirstReviews===true&&can('tasks.projectTeamReviews')&&can('tasks.projectTeamReview.original')&&can('teams.readFrozenOutputs')&&can('teams.readRun')&&
       <ProjectTeamFirstReviews key={task.id+':'+task.raw.runId+':'+task.raw.coordinatorEpoch} id={task.id} run={task.raw.runId} epoch={task.raw.coordinatorEpoch}
         project={task.raw.developmentContext?.projectId} checkout={task.raw.developmentContext?.checkoutId} conversation={task.raw.conversationId} active={overviewActive} onOpen={openProjectTeamReview}/>}
     {taskOpen&&taskLayer==='overview'&&task.raw?.developmentContext&&can('projectLive.open')&&<ConversationModelPickerVisibility key={'live:'+task.id}>{pickerVisible=><ProjectLivePreview taskId={task.id} run={act} nativeVisible={!pickerVisible&&!phoneViewerObscured} onBackChange={registerLivePreviewBack}/>}</ConversationModelPickerVisibility>}
     {currentScope.executionPhase==='planning'&&<p className="pw-muted" role="status">原任务处于只读规划。{Number(currentScope.planRevision)>0?`当前计划版本 ${String(currentScope.planRevision)}，确认准确计划后才能在原范围内继续。`:'尚未提交计划正文，重新打开页面不会启动执行。'}</p>}

     {openedTaskId.current===task.id&&!taskOpening&&task.raw?.kind==='assistant'&&can('conversation.drafts')&&<ConversationDrafts key={'drafts:'+task.id} taskId={task.id} reviewingMemory={memoryDraftTarget?.taskId===task.id} refreshRevision={Number(state.memoryRefreshRevision||0)} open={openConversationDraft}/>}

     <Accordion variant="divided" className="pw-task-disclosures" aria-label="更多任务资料" onExpandedChange={keys=>{
      const view=obj(bridge.getState().view);
      if(keys.has(`${task.id}:details`)&&taskOpen&&!taskOpening&&openedTaskId.current===task.id&&view.taskId===task.id&&view.section==null)
       setTaskToolsPlace(place=>place??{taskId:task.id,overview:false});
     }}>
      <AccordionItem id={`${task.id}:details`} title="过程与范围">
     <div className="pw-task-entry-list">
      <Button variant="ghost" leadingIcon={IconTool} onClick={()=>showTaskLayer('process')}>查看执行轨迹<IconChevronRight size={17}/></Button>
      {can('tasks.review')&&<Button variant="ghost" leadingIcon={IconInfo} disabled={readingReview||questionPending} onClick={()=>{if(openedTaskId.current!==task.id){openTask(task.id,'scope');return}showTaskLayer('scope');if(!review||reviewStale)refreshReview()}}>{review&&!reviewStale&&actionNames.includes('tasks.scope')?'修订本次范围':'本次范围'}<IconChevronRight size={17}/></Button>}
     </div>
     {openedTaskId.current===task.id&&!taskOpening&&<>
      {taskLayer==='overview'&&!task.raw?.ownerDeviceWork&&task.raw?.actorId==='owner'&&task.raw?.spaceId==='private'&&['assistant','phone'].includes(String(task.raw?.kind))&&!task.raw?.origin&&can('deviceTaskScope.open')&&<DeviceTaskScopeWorkspace key={'device:'+task.id} taskId={task.id} run={act} onBackChange={registerDeviceScopeBack} onSaved={()=>loadTaskReview(task.id)} onOpenDevices={()=>void openRelatedWorkspace('devices')}/>}
      {!taskToolsInOverview&&taskTools}
      {taskLayer==='overview'&&task.raw?.ownerRuntimeSetup&&can('setup.runtime.read')&&<Button variant="secondary" onClick={()=>openSetup('tools',task.id)}>查看工具准备进度</Button>}
      {taskLayer==='overview'&&task.raw?.developmentContext&&can('projectRuntime.read')&&<ProjectRuntimeHealth key={'runtime:'+task.id} taskId={task.id} run={act} onPrepareTools={can('setup.runtime.read')?()=>openSetup('tools'):undefined}/>}
      {taskLayer==='overview'&&!failureOverview&&task.raw?.developmentContext&&(can('local.execution.observe')||can('local.execution.open'))&&<ConversationModelPickerVisibility key={'execution:'+task.id}>{pickerVisible=><ProjectExecutionWorkspace task={task} reviewedPending={managedPending} skipPending={managedLogs.map(log=>log.pin)} active={taskOpen&&!pickerVisible&&!phoneViewerObscured} run={act} prepareFailure={can('tasks.review')?prepareFailedBuild:undefined}/>}</ConversationModelPickerVisibility>}
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
   <TabPanel id="process" shouldForceMount className="pw-task-process-layer" hidden={taskLayer!=='process'}>{chatWorkReturn.current?.replyRun?.taskId===task.id&&
    (task.raw?.runId!==chatWorkReturn.current.replyRun.runId||task.raw?.coordinatorEpoch!==chatWorkReturn.current.replyRun.epoch)?
    <p className="pw-reading-note" role="status">这项工作已换轮或原执行暂不可核对。没有用另一轮的记录代替这条回复。</p>:<TaskHistory key={`${state.conversationId}:${taskHistoryIdentity(task)}`} task={task} canRead={can('history.read')} canReadTrace={can('trace.read')} active={taskLayer==='process' && taskOpen && !taskOpening && openedTaskId.current===task.id} registerTransientBack={registerTaskBack} registerOriginalPhoneTool={registerOriginalPhoneTool} renderOperation={renderOperation}/>}</TabPanel>
   <TabPanel id="goal" shouldForceMount hidden={taskLayer!=='goal'&&taskLayer!=='scope'}>
    <div hidden={taskLayer==='scope'} className="pw-work-goal-panel"><WorkGoal task={task}/>
     {phoneConversationId(task)&&can('phone.request.original')?<PhoneTaskOriginalRequest key={taskHistoryIdentity(task)} task={task} active={taskOpen&&!taskOpening&&taskLayer==='goal'&&openedTaskId.current===task.id&&obj(snapshot?.view).taskId===task.id}/>
      :ordinaryWork?<Button variant="ghost" leadingIcon={IconChat} aria-busy={openingWorkRequest===task.id||undefined}
        disabled={!requestWork||requestWork.epoch<1||task.raw?.contextAvailable!==true||taskOpening||!!openingWorkRequest||!!historyPage||
          !can('conversation.reader.open')||!can('conversation.reader.close')} onClick={event=>void readOriginalWorkRequest(task,event.currentTarget)}>
        {openingWorkRequest===task.id?'正在核对原请求…':'查看原请求'}</Button>
      :originatingRequestConversation(task)&&can('conversation.select')&&<Button variant="ghost" leadingIcon={IconChat} disabled={!!state.chatSubmitting||!!returningConversation} onClick={()=>void returnToTaskConversation(task)}>{phoneConversationId(task)?'打开所属对话':'查看原请求与补充'}</Button>}
     {requestWork&&workRequestError&&workIdentityKey(requestWork)===workIdentityKey(workRequestError.work)&&
       <p className="pw-field-hint" role="status">{workRequestError.message}</p>}
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
 const ownerWorkActions=(task:Task,currentResultDecision:boolean,currentPlanDecision:boolean)=>{
  const identity=ownerWorkIdentity(task);if(!identity)return null;
  const control=workControls[workIdentityKey(identity)],controls=obj(task.raw?.controls);
  const hasTeamDecision=controls.canContinue===true&&workWaitsForTeamDecision(task);
  const supplement=identity.epoch>0&&can('ownerWork.supplement.read'),continuing=!hasTeamDecision&&controls.canContinue===true&&can('ownerWork.control');
  const note=hasTeamDecision?null:controls.canContinue===true&&!can('ownerWork.control')?'这个窗口目前不能提交续接，仍可查看原工作。':ownerWorkContinuationNote(task);
  const missingDefault=review?.savedTeamDefaultModel,modelWindow=reviewWindow.current,conversation=String(task.raw?.conversationId||'');
  const defaultSetupCurrent=()=>{
   if(!missingDefault||missingDefault.state!=='unconfigured'||!modelWindow||!['butler','chat','tasks'].includes(route)||
      !taskOpen||taskOpening||reviewPending||reviewSubmitted||reviewStale||workSupplementTarget||control?.pending||control?.unknown||
      modelWindow.taskId!==task.id||reviewWindow.current!==modelWindow||!currentReviewWindow(modelWindow)||
      workIdentityKey(missingDefault)!==workIdentityKey(identity)||!ordinaryChatWorkRow(review?.task,conversation,true))return false;
   const live=bridge.getState(),view=obj(live.view),selection=live.state;
   const original=asArray<Task>(selection.tasks).find(row=>row.id===task.id);
   return view.section===null&&!selection.conversationReader&&original?.status==='awaiting_user'&&
    !!ordinaryChatWorkRow(original,conversation)&&taskBinding(original)===taskBinding(review?.task)&&
    selection.modelSelectionBusy===false&&Number.isSafeInteger(missingDefault.modelSelectionRevision)&&missingDefault.modelSelectionRevision>=0&&
    selection.modelSelectionRevision===missingDefault.modelSelectionRevision&&
    ['connections.enter','connections.leave','tasks.open','tasks.close'].every(command=>live.capabilities.commands.includes(command));
  };
  const setDefault=defaultSetupCurrent();
  const openDefaultSetup=()=>{
   if(!defaultSetupCurrent()||!modelWindow)return;
   // The app default belongs to Settings; do not alter the conversation model or continue the Work.
   const origin:WorkspaceOrigin={...captureNavigationOrigin(),label:'返回原工作',task:{id:identity.taskId,layer:'overview',
    expected:{runId:identity.runId,epoch:identity.epoch},teamModelReturn:{documentEpoch:modelWindow.documentEpoch,conversation}}};
   void openRelatedWorkspace('model-settings',origin);
  };
  if(!setDefault&&!supplement&&!continuing&&!note&&controls.resultCapacityState!=='full')return null;
  const foldNewRound=continuing&&(currentResultDecision||currentPlanDecision);
  const foldSupplement=currentPlanDecision&&supplement&&controls.canInput===false;
  const more=foldNewRound||foldSupplement;
  const explanation=!control?.pending&&!control?.unknown&&!control?.usedForContinuation?note:null;
  const newRound=continuing&&<Button variant="secondary" disabled={taskOpening||control?.pending||control?.unknown||control?.usedForContinuation} onClick={()=>controlWork(task,'continue')}>在原任务开新一轮</Button>;
  const openSupplement=supplement&&<Button variant={continuing||hasTeamDecision?'ghost':'secondary'} disabled={taskOpening||!!state.chatSubmitting} onClick={()=>{void targetWork(task)}}>{controls.canInput===true?'补充这件工作':'查看补充草稿'}</Button>;
  return <section className="pw-owner-work-actions" aria-label={more?'这件工作的其他操作':'继续这件工作'}>
   {setDefault&&<Button variant="secondary" leadingIcon={IconSettings} onClick={openDefaultSetup}>设置应用默认模型</Button>}
   {!foldNewRound&&explanation&&<p>{explanation}</p>}
   {!note&&controls.resultCapacityState==='full'&&<p role="status">这件事的结果留存已满，暂不能继续执行。已有结果未因容量限制而删除；能否查看仍取决于原任务来源是否可用。容量限制不代表正在执行的任务已经停止。</p>}
   {!foldNewRound&&newRound}
   {!foldSupplement&&openSupplement}
   {more&&<Accordion key={workIdentityKey(identity)} variant="divided" className="pw-compact-disclosures w-full"><AccordionItem title="更多操作">
    {foldNewRound&&<>{explanation&&<p className="pw-field-hint">{explanation}</p>}{newRound}</>}
    {foldSupplement&&openSupplement}
   </AccordionItem></Accordion>}
  </section>;
 };
 const taskFooter=(task:Task)=>{
  if(teamReportInOverview?.visit.parent.taskId===task.id)return null;
  if(taskLayer==='process'||taskLayer==='homeReport'||openedTaskId.current!==task.id||!review||reviewStale)return null;
  const actions=asArray<{action:string}>(review.actions).map(x=>x.action),disabled=taskOpening||reviewSubmitted||reviewPending;
  if(taskLayer==='scope'&&actions.includes('tasks.scope'))return <footer className="pw-task-footer"><PressButton label="确认本次范围修订" disabled={disabled||!/^\d+$/.test(scopeTools)||!/^\d+$/.test(scopeModels)||Number(scopeTools)<1||Number(scopeTools)>400||Number(scopeModels)<1||Number(scopeModels)>512} onClick={()=>reviewedAction('tasks.scope',{minutes:Number(scopeMinutes),tools:Number(scopeTools),models:Number(scopeModels)})}/></footer>;
  const project=projectContinuation(review),next=reviewedBuildAfterDependencies(task,project,actions);
  if(taskLayer==='overview'&&actions.includes('tasks.confirm')&&!next){
   const original=exactReviewedOriginalDraft(task,review.task);
   if(inlineDraftDecisionFor(task,actions))return null;
   return <footer className="pw-task-footer" data-fixed-draft-acceptance={original?true:undefined}>{taskResultDecision(task,disabled)}</footer>;
  }
  if(taskLayer==='overview'&&actions.includes('tasks.resume')){
   const note=projectFeedback[projectFeedbackKey(task)]||'';
   if(next&&note==='')return <footer className="pw-task-footer"><PressButton key="build-review-prefill" label="填写构建核对内容"
    disabled={disabled||continuationBlocks(continuations[task.id])} onClick={()=>prepareNextBuildNote(task,next)}/></footer>;
   const projectLabel=next&&note===nodeBuildContinuationText(next)?'继续核对本机构建':'在原范围内继续项目任务';
   return <footer className="pw-task-footer"><PressButton label={project?projectLabel:task.raw?.ownerDeviceWork&&!task.raw?.runId?'开始这项设备工作':interruptedPrivatePhone(task,state.livePrivatePhones)?'在原任务另开一轮':'继续原任务'} disabled={disabled||!!project&&(continuationBlocks(continuations[task.id])||!note.trim()||note.trim().length>8000)} onClick={()=>reviewedAction('tasks.resume',{confirmed:true,feedback:reviewNote})}/></footer>;
  }
  return null;
 };
 const selectedWorkIdentity=ownerWorkIdentity(selectedTask);
 const selectedWorkControl=selectedWorkIdentity?workControls[workIdentityKey(selectedWorkIdentity)]:undefined;
 const selectedLocalPhone=!!selectedTask&&isPrivateLocalPhone(selectedTask);
 const selectedPhoneSurface=privatePhoneSurface(selectedTask,state.livePrivatePhones);
 const selectedPhoneStop=selectedTask&&!terminal(selectedTask)&&can('phone.stop')&&(selectedLocalPhone?!!selectedPhoneSurface:selectedTask.id===activeTask?.id)?phoneRunIdentity(selectedTask):null;
 const selectedTaskStop=selectedTask&&!selectedTask.raw?.ownerDirectNodeDependencies&&!selectedTask.raw?.ownerDirectViteBuild&&(selectedPhoneStop||(selectedWorkIdentity
  ?selectedTask.raw?.controls?.canStop===true&&can('ownerWork.control')
  :!selectedLocalPhone&&selectedTask.raw?.executionRole!=='owner_work'&&!terminal(selectedTask)&&can('tasks.stop')));
 const selectedWorkPresentation=selectedTask?workCollectionPresentation(selectedTask):null;
 const selectedTaskUnavailable=taskOpening||!selectedTask||openedTaskId.current!==selectedTask.id;
 const settledDependencyInOverview=selectedTask&&selectedWorkPresentation?.reconciliation&&lateDependencyForOverview(selectedTask);
 const heldDependencyInOverview=selectedTask&&selectedWorkPresentation?.reconciliation&&exactDependencyHoldReview(heldDependencyForOverview(selectedTask));
 // The drawer owns this click, not the underlying Home/chat or independent-screen viewer.
 // Keep the ACK when this exact task settles and drops its live surface, not when the owner changes visits.
 const drawerPhoneStopScope=(native:ReturnType<typeof bridge.getState>,original:Pick<ShownPhoneRun,'taskId'|'runId'|'epoch'>)=>{
  const view=obj(native.view);
  if(!taskOpen||taskOpening||taskLayer==='homeReport'||document.hidden||openedTaskId.current!==original.taskId||
     view.section!==null||view.taskId!==original.taskId||!Number.isSafeInteger(view.resumeRevision)||
     view.resumeRevision!==openedTaskResumeRevision.current||native.state.conversationReader||taskHistoryOrigin.current)return null;
  const rows=asArray<Task>(native.state.tasks).filter(row=>row?.id===original.taskId),row=rows[0],raw=row?.raw;
  if(rows.length!==1||!raw||!isPrivateLocalPhone(row)||raw.status!==row.status||raw.runId!==original.runId||raw.coordinatorEpoch!==original.epoch||
     raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.taskProjection==='source_unavailable')return null;
  return JSON.stringify([original.taskId,original.runId,original.epoch,bridge.getDocumentEpoch(),navigationWindow.current,view.resumeRevision,reviewRequest.current]);
 };
 const drawerPhoneIdentity=selectedLocalPhone?phoneRunIdentity(selectedTask):null;
 const drawerStopScope=drawerPhoneIdentity?drawerPhoneStopScope(snapshot,drawerPhoneIdentity):null;
 const sameDrawerStop=(original:ShownPhoneRun)=>drawerStopScope!==null&&drawerPhoneStopScope(bridge.getState(),original)===drawerStopScope;
 const drawerStops=useInlinePhoneStops(drawerStopScope,sameDrawerStop);
 const drawerStop=drawerStops.rows[0];
 const drawerStopCopy=drawerStop&&phoneStopReceiptPresentation(drawerStop.phase,selectedTask?.status);
 const drawerStopPending=drawerStop?.phase==='checking'||drawerStop?.phase==='requested'||drawerStop?.phase==='local_only';
 const seeDrawerStopProcess=()=>{
  if(!drawerStop||!sameDrawerStop(drawerStop)||!can('history.read'))return;
  const show=()=>{if(sameDrawerStop(drawerStop)&&bridge.getState().capabilities.commands.includes('history.read')){rememberTaskPosition();setTaskLayer('process')}};
  if(!guardTaskLeave(show))show();
 };
 const stopSelectedTask=()=>{
  if(!selectedTask||selectedTaskUnavailable||!selectedTaskStop||selectedWorkControl?.pending||selectedWorkControl?.unknown||selectedWorkControl?.usedForContinuation||selectedTask.status==='stopping')return;
  if(selectedPhoneStop){
   const activityHandle=selectedLocalPhone?selectedPhoneSurface?.activityHandle:undefined;
   if(activityHandle&&drawerStops.request({...selectedPhoneStop,activityHandle},()=>exactPhoneControl('phone.stop',selectedPhoneStop,true)))return;
   triggerPhoneControl('phone.stop',selectedPhoneStop,selectedLocalPhone);
  }
  else if(selectedWorkIdentity)controlWork(selectedTask,'cancel');
  else run('tasks.stop',{id:selectedTask.id,runId:selectedTask.raw?.runId??'',epoch:selectedTask.raw?.coordinatorEpoch});
 };
 const composerHasChatStop=isChatSurface&&intent==='chat'&&!!state.chatBusy&&!!currentCancelIdentity&&can('chat.cancel');
 const conversationActivity=conversationActivityLabel(state);
 const openChatHistory=async(trigger:HTMLButtonElement)=>{
  const native=bridge.getState().state,conversationId=String(native.conversationId||'');
  if(document.hidden||!conversationId||conversationId!==state.conversationId||native.conversationReader||!trigger.isConnected)return;
  const origin={conversationId,document:bridge.getDocumentEpoch(),navigation:navigationWindow.current,trigger};
  chatHistoryReturn.current=origin;
  stopFollowing();rememberChatPosition();
  try{await act('conversation.reader.open',{conversationId})}
  catch{if(chatHistoryReturn.current===origin)chatHistoryReturn.current=null;}
 };
 const focusAfterHistoryClose=()=>{
  const origin=chatHistoryReturn.current;chatHistoryReturn.current=null;
  const native=bridge.getState();
  if(!origin||document.hidden||route!=='chat'||taskOpen||native.state.conversationReader||native.state.attachmentWorkspace?.open||
   origin.conversationId!==native.state.conversationId||origin.document!==bridge.getDocumentEpoch()||
   origin.navigation!==navigationWindow.current||!origin.trigger.isConnected||origin.trigger.disabled)return;
  origin.trigger.focus({preventScroll:true});
 };
 const openTurnNotes=(original:WorkIdentity,layer:'overview'|'review'|'process',saved?:Message,trigger?:HTMLButtonElement|null)=>{
  const expected={runId:original.runId,epoch:original.epoch};
  if(layer!=='process'||!saved){openTask(original.taskId,layer==='review'&&!can('tasks.review')?'overview':layer,false,undefined,undefined,expected);return}
  const source=saved.id,revision=saved.sourceRevision,scroller=scroll.current,article=trigger?.closest<HTMLElement>('article[data-message-id]');
  if(!source||typeof revision!=='number'||!Number.isSafeInteger(revision)||revision<1||!trigger||article?.dataset.messageId!==source||
     !scroller||!conversation||route!=='chat'||taskOpen||taskOpening)return;
  const documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const current=()=>{
   const state=bridge.getState().state,rows=asArray<Message>(state.messages).filter(row=>row.id===source);
   const context=rows.length===1&&rows[0].sourceRevision===revision?messageReplyContext(rows[0]):null;
   return !document.hidden&&documentEpoch===bridge.getDocumentEpoch()&&navigation===navigationWindow.current&&
    state.conversationId===conversation&&!state.conversationReader&&!!context&&workIdentityKey(context)===workIdentityKey(original);
  };
  if(!current())return;
  stopFollowing();rememberChatPosition();
  const returnTo:ChatWorkReturn={document:documentEpoch,conversation,message:source,revision,replyRun:original,top:scroller.scrollTop,
   offset:article.getBoundingClientRect().top-scroller.getBoundingClientRect().top,trigger};
  openTask(original.taskId,'process',false,undefined,undefined,expected,current,undefined,
   ()=>{if(current())setLocalError('现在无法核对这条回复的原执行。没有打开其他轮次。')},()=>{},{returnTo,beforeTaskRequest:reviewRequest.current,navigation});
 };
 const openPhoneWorkFromMessage=(entry:'overview'|'process',target:PhoneReportTarget)=>{
  const phone=target.phoneResult,trigger=target.returnFocus,node=scroll.current,view=obj(bridge.getState().view);
  const article=trigger.closest<HTMLElement>('article[data-message-id]');
  let source:ChatWorkTaskOpen|undefined;
  if(route==='chat'&&!taskOpen&&!taskOpening&&target.readerWindow===0&&!target.noticeOrigin&&target.sourceRevision===1&&
     view.section===null&&view.taskId===null&&Number.isSafeInteger(target.resume)&&
     trigger instanceof HTMLButtonElement&&trigger.isConnected&&!trigger.disabled&&!trigger.closest('[hidden],[inert]')&&
     article?.dataset.messageId===target.messageId&&node?.contains(article)){
   const returnTo:ChatWorkReturn={document:target.documentEpoch,conversation:target.conversationId,message:target.messageId,
    revision:target.sourceRevision,top:node.scrollTop,offset:article.getBoundingClientRect().top-node.getBoundingClientRect().top,trigger,
    phoneDelivery:{result:phone,resume:target.resume}};
   if(chatWorkLocation(returnTo,undefined,true)){
    stopFollowing();rememberChatPosition();
    source={returnTo,beforeTaskRequest:reviewRequest.current,navigation:navigationWindow.current};
   }
  }
  // The return location is presentation only. The existing native open still validates the original run.
  openTask(phone.taskId,entry,false,undefined,undefined,{runId:phone.runId,epoch:phone.epoch},undefined,undefined,undefined,undefined,source);
 };
 const openConversationWorkRow=(task:Task,trigger:HTMLButtonElement)=>{
  const entry=attention(task)&&can('tasks.review')?'review':'overview';
  const work=route==='chat'&&intent==='chat'&&!desktopNavigation&&!taskOpen&&conversation?ordinaryChatWorkRow(task,conversation):null;
  const resume=obj(snapshot?.view).resumeRevision,nativeTaskRequest=state.requestedTaskRevision;
  const row:ChatWorkRowOrigin|undefined=work&&Number.isSafeInteger(draftEpoch)&&Number.isSafeInteger(resume)&&Number.isSafeInteger(nativeTaskRequest)
   ?{work,conversation,draftEpoch,documentEpoch:personalDocumentEpoch,resumeRevision:resume,navigation:navigationRevision,nativeTaskRequest,trigger}:undefined;
  // The same generic read-only open still admits the task. The row pin only
  // describes where to return, and cannot add or alter a native argument.
  openTask(task.id,entry,false,undefined,undefined,undefined,undefined,undefined,undefined,undefined,
   row&&currentChatWorkRow(row,'source')?{row,beforeTaskRequest:reviewRequest.current,navigation:row.navigation}:undefined);
 };
 const editingChat=!!text||asArray(state.selectedChatAttachments).length>0||
  [composer.objectRefs,composer.sourceRefs,composer.previewRefs].some(value=>asArray(value).length>0);
 const stageExample=intent==='chat'&&!!draftKey&&!isListening&&!voiceReview&&!state.voiceInput?.reviewAvailable&&!composer.voiceText&&
  !editingChat&&!pendingHere&&!registration.waiting&&!state.chatSubmitting&&can('composer.edit')?(draft:string)=>{
   const editor=workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)');
   if(!editor||editor.value)return;
   editText(draft);
   editor.focus({preventScroll:true});
  }:undefined;
 const teamStarterIsCurrent=(source:TeamGoalStarter,live:ReturnType<typeof bridge.getState>)=>{
  const draft=emptyTeamGoalDraft(live);
  return !document.hidden&&!draftSwitchPending()&&bridge.getDocumentEpoch()===source.documentEpoch&&navigationWindow.current===source.navigation&&
    obj(live.view).section===null&&sameEmptyTeamGoal(source,draft);
 };
 const stageTeamGoal=route==='butler'&&ready&&!taskOpen&&!workOpen&&!commandOpen&&!preparationTarget&&stageExample&&teamGoalStarter&&
   teamStarterIsCurrent(teamGoalStarter,snapshot)?(value:string)=>{
    const original=teamGoalStarter,editor=workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)');
    if(!editor||editor.value!==''||chatDraft.currentValue()!==''||!teamStarterIsCurrent(original,bridge.getState()))return;
    setTeamGoalStarter(null);
    editor.focus({preventScroll:true});
    // Write only to the same still-empty native draft. Never update the local text optimistically.
    void bridge.command('composer.edit',{key:original.key,epoch:original.epoch,expectedRevision:original.revision,text:value}).catch(()=>{
      if(teamStarterIsCurrent(original,bridge.getState()))setLocalError('尚未确认填入模板。可以在原输入框核对或直接写下目标。');
    });
   }:undefined;
 const stageLocalDisplayExample=route==='butler'&&!composerModelName&&ready&&!state.conversationId&&!messages.length&&
  !foregroundTurn&&!state.chatBusy&&!taskOpen&&!workOpen&&!commandOpen&&!preparationTarget&&
  composer.canOfferPrimaryDisplayExample===true&&stageExample?()=>{
   const current=bridge.getState(), draft=current.composer;
   if(displayStageFlight.current||document.hidden||draft?.canOfferPrimaryDisplayExample!==true||draft.key!==draftKey||
      draft.epoch!==draftEpoch||!Number.isSafeInteger(draft.revision)||current.state.conversationId||chatDraft.currentValue().trim())return;
   const editor=workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)');
   if(!editor||editor.value)return;
   const doc=bridge.getDocumentEpoch(), owner=chatOwner;
   const original=()=>{const d=bridge.getState().composer;return !document.hidden&&bridge.getDocumentEpoch()===doc&&d?.key===draftKey&&d?.epoch===draftEpoch;};
   // Let the input follow the native draft instead of showing a false "connect model" state
   // while an optimistic Web edit waits to be accepted or rejected.
   displayStageFlight.current=owner;setDisplayStageOwner(owner);
   editor.focus({preventScroll:true});
   void bridge.command('composer.edit',{key:draftKey,epoch:draftEpoch,expectedRevision:draft.revision,text:'查一下这台手机主屏刷新率和分辨率'})
    .catch(()=>{if(original())toast.error('原草稿已变化，没替你填入问题。可以直接输入。');})
    .finally(()=>{if(displayStageFlight.current===owner){displayStageFlight.current='';setDisplayStageOwner('');}});
  }:undefined;
 const recentWork=orderConversations(conversations as (Json & {id:string})[]);
 const nativeNavView=obj(snapshot?.view);
 const showNavigationActivity=!desktopNavigation&&isChatSurface&&intent==='chat'&&ready&&
  !taskOpen&&!taskOpening&&nativeNavView.section===null&&nativeNavView.taskId===null&&!state.conversationReader&&
  phoneDraft.phone===false&&phoneDraft.channelOrigin===false&&can('conversation.select');
 const nativeAssistantActivity=obj(state.features).assistant;
 const navigationConversationActivity=useMemo(()=>showNavigationActivity
  ?conversationNavigationActivity(tasks,nativeAssistantActivity,ordinaryChatWorkRow):undefined,[showNavigationActivity,tasks,nativeAssistantActivity]);
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
 const [conversationPreparations,setConversationPreparations]=useState<{conversation:string;rows:ConversationPreparation[]}|null>(null);
 const receiveConversationPreparations=useCallback((conversation:string,rows:ConversationPreparation[])=>{
   if(bridge.getState().state.conversationId!==conversation)return;
   setConversationPreparations({conversation,rows});
 },[]);
 const anchoredPreparations=new Map<number,ConversationPreparation[]>();
 const placedProposalKeys=new Set<string>();
 const conversation=String(state.conversationId||'');
 if(ready&&route==='chat'&&conversationPreparations?.conversation===conversation&&can('butler.preparation.read')){
   const eligible=new Map<string,number|null>(),unidentifiedReplies=new Set<string>(),ordinaryPhonePlacement=new Map<string,number>();
   const owners=new Map<string,{index:number;revision:number}|null>();
   for(const [index,m] of messages.entries()){
     if(!m.id)continue;
     if(m.role==='user'){
       const id=m.id;
       owners.set(id,owners.has(id)||m.quoteAvailable!==true||!Number.isSafeInteger(m.sourceRevision)||Number(m.sourceRevision)<1?null:{index,revision:Number(m.sourceRevision)});
     }else if(m.role==='assistant'){
       const reply=savedReplyDetails[index]?.replyContext;
       if(reply){const key=workIdentityKey(reply);eligible.set(key,eligible.has(key)||!reply.replyId?null:index);if(reply.replyId)ordinaryPhonePlacement.set(key,index);}
       else if(m.taskId)unidentifiedReplies.add(m.taskId);
     }
   }
   for(const item of conversationPreparations.rows){
     const key=workIdentityKey({taskId:item.sourceTaskId,runId:item.sourceRunId,epoch:item.sourceEpoch}),request=item.visibleOwnerRequest;
     const owner=request&&owners.get(request.messageId), exactOwner=owner&&owner.revision===request?.revision;
     // The private native locator witnesses one original input. Multi-input
     // proposals stay with their uniquely saved assistant reply, not a guessed user row.
     const index=item.kind==='phone'?ordinaryPhonePlacement.get(key):
       exactOwner?owner.index:
       !unidentifiedReplies.has(item.sourceTaskId)?eligible.get(key):null;
     if(index==null)continue;
     const matches=anchoredPreparations.get(index)||[];
     // These facts require the exact owner request, not the model reply used as a fallback.
     anchoredPreparations.set(index,[...matches,!exactOwner&&(item.chatBuild||item.acceptedDraft||item.awaitingDraft||item.interruptedDraft)?
       {...item,chatBuild:undefined,acceptedDraft:undefined,awaitingDraft:undefined,interruptedDraft:undefined}:item]);
     placedProposalKeys.add(conversationPreparationKey(item));
   }
 }
 const representedProjectRuns=new Set<string>();
 for(const rows of anchoredPreparations.values())for(const row of rows){
  if(row.kind!=='project')continue;
  const next=conversationNext(row,can);
  if(next.action!=='prepare')representedProjectRuns.add(workIdentityKey(next.target));
 }
 const linkedProjectAction=(task:Task)=>typeof task.raw?.runId==='string'&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&
   representedProjectRuns.has(workIdentityKey({taskId:task.id,runId:task.raw.runId,epoch:task.raw.coordinatorEpoch}));
 // Only surface a decision that belongs to a task anchored in this conversation.
 const conversationAttention=intent==='chat'?tasks.reduce<Task|undefined>((latest,task)=>{const anchor=messageTaskAnchors.get(task.id);if(!conversationInlineAttention(task)||linkedProjectAction(task)||anchor===undefined)return latest;return !latest||anchor>messageTaskAnchors.get(latest.id)!?task:latest;},undefined):undefined;
 const navigationTrigger=desktopNavigation?<span className="pw-header-spacer" aria-hidden="true"/>:<Drawer.Trigger asChild><IconButton ref={isPrimaryRoute?navigationMenuGlass:undefined} icon={IconMenu} className={isChatSurface?'pw-chat-glass-trigger':undefined} aria-label="打开导航" data-testid="all_work" onClick={event=>{navigationReturnFocus.current=event.currentTarget}}/></Drawer.Trigger>;
 const openUnreadMessages=()=>{void openNotifications(()=>{notificationMemory.current.externalUnreadRequest=(notificationMemory.current.externalUnreadRequest||0)+1})};
 const navigationContent=<WorkspaceNavigator items={workNav} selected={navigationSelection} recent={recentWork}
  selectedConversation={route==='chat'?String(state.conversationId||''):undefined}
  conversationActivity={navigationConversationActivity}
  desktop={desktopNavigation} compact={effectiveCompact} onToggleCompact={()=>setNavigationCompact(value=>!value)}
  directoryOpen={navigationMoreOpen} setDirectoryOpen={setNavigationMoreOpen} searchOpen={commandOpen} openSearch={showWorkSearch}
  closeSearch={()=>setCommandOpen(false)} searchTrigger={workSearch} memory={ownerNavMemory.current} navigate={selectNavigation}
  search={<WorkSearch key="work-search" supported={can('work.search')} identity={`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}`}
   memory={workSearchMemory.current} onOpen={openSearchResult} labelStatus={labelStatus} onClose={()=>setCommandOpen(false)}/>}
  openConversation={can('conversation.select')?chooseConversation:undefined}
  organizeConversation={can('conversation.organization.read')?setOrganizationTarget:undefined}
  newConversation={can('conversation.new')?()=>chooseConversation(null):undefined} newPending={!!state.chatSubmitting||draftSwitching}
  onResumeIndependentReportFollowUp={intent==='chat'&&obj(snapshot?.composer).independentReportDraftAvailable===true&&can('ownerWork.report.followUp.resume')?()=>{void startIndependentReportDraft()}:undefined}
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
 const stopWindow=!taskOpen&&!workOpen&&(route==='butler'||route==='chat'&&!!state.conversationId)
   ?JSON.stringify([route,navigationWindow.current,bridge.getDocumentEpoch(),obj(snapshot?.view).resumeRevision??0,chatOwner,state.conversationId||'']):null;
 const stoppedSource=(shown:ShownPhoneRun,rows:Task[])=>rows.find(task=>{const id=phoneRunIdentity(task),r=task.raw;
   return task.id===shown.taskId&&id?.runId===shown.runId&&id.epoch===shown.epoch&&isPrivateLocalPhone(task)&&r&&r.status===task.status&&
     r.contextAvailable!==false&&r.taskCoreReadLimited!==true&&r.sourceCurrentReadRequired!==true&&r.taskProjection!=='source_unavailable'&&
     (route==='butler'||route==='chat'&&!!state.conversationId&&phoneConversationId(task)===state.conversationId)});
 const sameStopWindow=()=>{const n=bridge.getState(),owner=obj(n.composer);
   return !!stopWindow&&stopWindow===JSON.stringify([route,navigationWindow.current,bridge.getDocumentEpoch(),obj(n.view).resumeRevision??0,`${owner.key||''}:${owner.epoch??''}`,n.state.conversationId||''])&&
     !document.hidden};
 const sameStopSource=(shown:ShownPhoneRun)=>sameStopWindow()&&!!stoppedSource(shown,asArray<Task>(bridge.getState().state.tasks));
 const stopReceipts=useInlinePhoneStops(stopWindow,sameStopSource);
 const openOriginalActivity=(shown:ShownPhoneRun,entry:'overview'|'review')=>{
   if(document.hidden||!shown.activityHandle||!can('phone.activity.original'))return;
   const {taskId,runId,epoch,activityHandle}=shown;
   phoneActivityPick.current={kind:'activity',handle:activityHandle,navigation:navigationWindow.current,document:bridge.getDocumentEpoch(),resume:Number(obj(bridge.getState().view).resumeRevision||0),entry};
   run('phone.activity.original',{taskId,runId,epoch,activityHandle});
 };
 const inlinePhoneAllowed=route==='chat'&&!taskOpen&&!taskOpening&&!phoneViewerObscured&&!phoneViewerExpanded&&
   ['phone.viewer.open','phone.viewer.frame','phone.viewer.close'].every(can);
 const visibleInlinePhone=(native:ReturnType<typeof bridge.getState>,id:string)=>{
   const floor=obj(native.view),owner=native.state,conversation=owner.conversationId;
   if(!inlinePhoneAllowed||document.hidden||typeof conversation!=='string'||!conversation||
      floor.section!==null||floor.taskId!==null||owner.conversationReader!==null||owner.filePath!==null||
      owner.attachmentWorkspace?.open!==false||obj(native.phoneDraft).channelOrigin!==false)return;
   const matches=asArray<Task>(owner.tasks).filter(row=>row.id===id);
   const task=matches.length===1?matches[0]:undefined;
   if(!task||task.status!=='running'||!isPrivateLocalPhone(task)||phoneConversationId(task)!==conversation)return;
   const surface=privatePhoneSurface(task,owner.livePrivatePhones),target=phoneViewerTask(task);
   if(!surface?.activityHandle||surface.requestedMode!=='background'||surface.surfaceState!=='bound'||
      surface.paused||surface.stopRequested||!target)return;
   const key=JSON.stringify([bridge.getDocumentEpoch(),floor.resumeRevision,navigationWindow.current,
     conversation,surface.taskId,surface.runId,surface.epoch,surface.activityHandle]);
   return {key,task:target,activityHandle:surface.activityHandle};
 };
 const activeInlinePhoneRow=inlinePhoneVisit!==null&&phoneRows.some(({task})=>
   visibleInlinePhone(bridge.getState(),task.id)?.key===inlinePhoneVisit);
 useEffect(()=>{if(inlinePhoneVisit!==null&&!activeInlinePhoneRow)chooseInlinePhoneVisit(null)},[inlinePhoneVisit,activeInlinePhoneRow,chooseInlinePhoneVisit]);
 const homePhoneAttention:NonNullable<ButlerHomeProps['phoneAttention']>=[];
 const phoneActivityRows=phoneRows.map(({task,surface})=>{
   const held=stopReceipts.rows.find(x=>surface?.activityHandle===x.activityHandle);
   if((held?.phase==='requested'||held?.phase==='local_only')&&surface?.stopRequested)return null;
   const key=JSON.stringify([task.id,task.raw?.runId,task.raw?.coordinatorEpoch]);
   const view=visibleInlinePhone(bridge.getState(),task.id);
   const watching=!!view&&view.key===inlinePhoneVisit;
   const region='pw-original-phone-inline-'+task.id;
   const viewStillCurrent=()=>!!view&&inlinePhoneSelection.current===view.key&&visibleInlinePhone(bridge.getState(),task.id)?.key===view.key;
   const toggle=view?{open:watching,controls:region,onToggle:()=>{
     if(inlinePhoneSelection.current===view.key)chooseInlinePhoneVisit(null);
     else if(visibleInlinePhone(bridge.getState(),task.id)?.key===view.key)chooseInlinePhoneVisit(view.key);
   }}:undefined;
   const row=<PhoneTaskStatus key={key} task={task} surface={surface||undefined} can={can} inlineView={toggle}
     onOpen={()=>{if(inlinePhoneSelection.current)chooseInlinePhoneVisit(null);const entry=attention(task)&&can('tasks.review')?'review':'overview';
       if(!isPrivateLocalPhone(task)){openTask(task.id,entry);return}
       if(surface?.activityHandle)openOriginalActivity({...surface,activityHandle:surface.activityHandle},entry)}}
     onControl={(action,identity,shownPause)=>{
       if(action==='phone.stop'&&surface?.activityHandle&&isPrivateLocalPhone(task)){
         if(!sameStopWindow()){setLocalError('原手机任务页面已变化，这次没有发送停止。');return}
         if(stopReceipts.request({...identity,activityHandle:surface.activityHandle},()=>exactPhoneControl(action,identity,true)))return}
       triggerPhoneControl(action,identity,isPrivateLocalPhone(task),shownPause)}}/>;
   if(route==='butler'&&surface?.activityHandle&&!surface.stopRequested&&task.status!=='stopping'&&task.status!=='cancelling'&&
      can('tasks.open')&&can('phone.activity.original')){
     const view=workCollectionPresentation(task),exact=homeWorkIdentity(task);
     if(exact?.domain==='phone'&&view.group==='attention'&&needsOwnerOnHome(task,view,exact)){
       homePhoneAttention.push({key,row});return null;
     }
   }
   return <Fragment key={key}>{row}{watching&&view&&<ConversationModelPickerVisibility key={view.key}>{covered=>
     <ChatPhonePickerGate covered={covered} retire={()=>{if(inlinePhoneSelection.current===view.key)chooseInlinePhoneVisit(null)}}>
       <InlineOriginalPhoneViewer id={region} task={view.task} originalActivityHandle={view.activityHandle} isCurrent={viewStillCurrent}/>
     </ChatPhonePickerGate>}
   </ConversationModelPickerVisibility>}</Fragment>;
 });
 const phoneActivity=phoneActivityRows.some(Boolean)||stopReceipts.rows.length?<>{phoneActivityRows}{stopReceipts.rows.map(item=>{const row=stoppedSource(item,tasks);if(!row)return null;
   return <PhoneInlineStopReceipt key={item.ticket} item={item} goal={row.goal} recordedStatus={row.status}
     onDismiss={()=>stopReceipts.dismiss(item.ticket)} onOpen={can('phone.activity.original')?()=>{
       if(sameStopSource(item))openOriginalActivity(item,'overview')}:undefined}/>
 })}</>:null;
 const representedPhoneTaskIds=new Set(phoneRows.map(row=>row.task.id));
 const chatAttention=conversationAttention&&!representedPhoneTaskIds.has(conversationAttention.id)?conversationAttention:undefined;
 const conversationWorkRows=conversationWork.filter(task=>!representedPhoneTaskIds.has(task.id)&&!linkedProjectAction(task)&&task.id!==chatAttention?.id);
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
 const savedHostSourceCurrent=(selected:SavedHostSource)=>{
  const live=bridge.getState();
  return !document.hidden&&bridge.getDocumentEpoch()===selected.document&&live.state.conversationId===selected.conversation&&
   !live.state.conversationReader&&!!exactSavedHostMessage(asArray<Message>(live.state.messages),selected);
 };
 const hasSavedHostPicker=can('conversation.selectSavedHost')&&can('remote.reviewTask');
 const canChooseSavedHost=hasSavedHostPicker&&intent==='chat'&&!taskOpen&&!taskOpening&&!state.conversationReader&&!state.chatSubmitting&&!state.phoneSubmitting;
 const chooseHostForSource=(m:Message,trigger:HTMLButtonElement)=>{
  const node=scroll.current, article=trigger.closest<HTMLElement>('article[data-message-id]'), current=bridge.getState(), view=obj(current.view);
  if(route!=='chat'||!canChooseSavedHost||savedHostSession.current||view.section||view.taskId||current.state.chatSubmitting||current.state.phoneSubmitting||
     !node||!current.state.conversationId||node.dataset.conversationId!==current.state.conversationId||!localSavedHostMessage(m)||article?.dataset.messageId!==m.id)return;
  const selected:ChatWorkReturn={document:bridge.getDocumentEpoch(),conversation:current.state.conversationId,message:m.id,revision:1,
    top:node.scrollTop,offset:article.getBoundingClientRect().top-node.getBoundingClientRect().top,trigger};
  if(!savedHostSourceCurrent(selected))return;
  savedHostSession.current=selected;setSavedHostError('');setSavedHostChoice(selected);
 };
 const currentLocalHostMessages=route==='butler'&&canChooseSavedHost&&state.conversationId&&!foregroundTurn&&!pendingHere&&
   !commandOpen&&!workOpen&&!preparationTarget?messages.filter(localSavedHostMessage):[];
 const homeHostMessage=currentLocalHostMessages.length===1&&messages.filter(row=>row.id===currentLocalHostMessages[0].id).length===1?currentLocalHostMessages[0]:undefined;
 const continueSavedHostFromHome=()=>{
  if(!homeHostMessage||homeSavedHostJump.current)return;
  const selected:SavedHostSource={document:bridge.getDocumentEpoch(),conversation:String(state.conversationId||''),message:homeHostMessage.id,revision:1};
  const nav=navigationWindow.current;
  const visible=()=>navigationWindow.current===nav&&!document.hidden&&bridge.getDocumentEpoch()===selected.document;
  const current=()=>{const live=bridge.getState(),view=obj(live.view);return visible()&&!view.section&&!view.taskId&&
    !live.state.chatSubmitting&&!live.state.phoneSubmitting&&savedHostSourceCurrent(selected);};
  if(!current()){setLocalStatus('原话已变化，未打开其他交办。');return;}
  // The existing conversation already owns this source. No conversation selection,
  // draft staging, profile selection or host request is needed to continue preparing.
  void navigate('chat',()=>{homeSavedHostJump.current={...selected,navigation:navigationWindow.current}},{stillCurrent:current}).then(opened=>{
   if(!opened&&visible()&&!savedHostSourceCurrent(selected))setLocalStatus('原话已变化，未打开其他交办。');
  });
 };
 useLayoutEffect(()=>{
  const original=homeSavedHostJump.current;
  if(!original)return;
  homeSavedHostJump.current=null;
  if(route!=='chat'||navigationRevision!==original.navigation||document.hidden||bridge.getDocumentEpoch()!==original.document)return;
  const source=exactSavedHostMessage(messages,original,!original.viewOnly),node=scroll.current;
  const article=Array.from(chatMessageList.current?.children||[]).find(row=>(row as HTMLElement).dataset.messageId===original.message);
  if(original.viewOnly){
   if(!source||!(article instanceof HTMLElement)||node?.dataset.conversationId!==original.conversation){setLocalStatus('未找到已核对的原话，没有打开其他记录。');return;}
   stopFollowing();article.scrollIntoView({block:'nearest',behavior:'instant'});article.focus({preventScroll:true});return;
  }
  const trigger=article?.querySelector<HTMLButtonElement>('button[data-saved-host-continue]:not(:disabled)');
  if(!canChooseSavedHost||!source||!trigger||node?.dataset.conversationId!==original.conversation||!savedHostSourceCurrent(original)){
   setLocalStatus('此处未能核对原话。不会准备其他交办，也没有发送。');return;
  }
  stopFollowing();trigger.scrollIntoView({block:'nearest',behavior:'instant'});trigger.focus({preventScroll:true});
  chooseHostForSource(source,trigger);
 },[route,navigationRevision]);
 const savedHostSettingsOrigin=(selected:ChatWorkReturn):WorkspaceOrigin=>({
  ...captureNavigationOrigin(),focus:null,focusKey:undefined,chatWork:selected,savedHostPreparation:true,label:'返回原话',
  drawerChat:{id:selected.conversation,draftKey:String(bridge.getState().composer?.key||''),documentEpoch:selected.document}
 });
 const configureSavedHost=async()=>{
  const selected=savedHostSession.current,nav=navigationWindow.current;
  if(!selected||savedHostFlight.current||!can('connections.enter')||!can('remote.saveProfile'))return;
  const current=()=>{
   const view=obj(bridge.getState().view);
   return route==='chat'&&!taskOpen&&!taskOpening&&!view.section&&!view.taskId&&savedHostSession.current===selected&&
    navigationWindow.current===nav&&savedHostSourceCurrent(selected);
  };
  if(!current())return;
  const origin=savedHostSettingsOrigin(selected);
  savedHostFlight.current=selected;setSavedHostBusy(true);setSavedHostError('');
  try{
   // Configuration receives no original text or host-goal token. The owner returns to the bubble and chooses explicitly.
   await navigate('remote',()=>{relatedOrigin.current=origin;closeSavedHost();},{keepOrigin:true,stillCurrent:current});
  }catch{if(current())setSavedHostError('未打开主机连接设置。原话仍只在手机。');}
  finally{if(savedHostFlight.current===selected){savedHostFlight.current=null;setSavedHostBusy(false);}}
 };
 const selectSavedHost=async(profile:ReturnType<typeof savedHostProfiles>[number])=>{
  const selected=savedHostSession.current;
  if(!selected||savedHostFlight.current)return;
  const nav=navigationWindow.current,resumeRevision=Number(obj(bridge.getState().view).resumeRevision||0);
  const choice={profileId:profile.id,profileBinding:profile.bindingHash};
  const visible=()=>savedHostSession.current===selected&&navigationWindow.current===nav&&!document.hidden&&bridge.getDocumentEpoch()===selected.document;
  const current=()=>{const live=bridge.getState(),view=obj(live.view),p=currentSavedHostProfile(live.state.features?.remote?.profiles,choice);
   return visible()&&route==='chat'&&!view.section&&!view.taskId&&Number(view.resumeRevision||0)===resumeRevision&&p?.serviceBase===profile.serviceBase&&savedHostSourceCurrent(selected);};
  if(!current())return;
  savedHostFlight.current=selected;setSavedHostBusy(true);setSavedHostError('');
  try{
    const reply=await bridge.command<Json>('conversation.selectSavedHost',{conversationId:selected.conversation,messageId:selected.message,
      revision:selected.revision,profileId:profile.id,profileBinding:profile.bindingHash});
    if(!current()){if(visible())setSavedHostError('原话或所选主机已变化。没有改用其他位置。');return;}
    if(reply?.status!=='host_selected_local'||reply.networkUsed!==false||reply.executionAuthorized!==false||
      reply.profileId!==profile.id||reply.profileBinding!==profile.bindingHash||typeof reply.originHandle!=='string')
      throw new Error('没有核对到这条原话和所选主机，未发送。');
    const origin=savedHostSettingsOrigin(selected);
    const source:SavedHostSource={document:selected.document,conversation:selected.conversation,message:selected.message,revision:selected.revision};
    const opened=await navigate('remote',()=>{
      relatedOrigin.current=origin;
      setCloudGoal({id:crypto.randomUUID(),text:'',carriesTextOnly:false,originHandle:reply.originHandle,
        savedTarget:{...choice,source,resumeRevision},navigation:navigationWindow.current});
      closeSavedHost();
    },{keepOrigin:true,stillCurrent:current});
    if(!opened&&visible())setSavedHostError('没有打开原工作审阅；没有改用其他位置。');
  }catch(e){if(visible())setSavedHostError(e instanceof Error?e.message:'没有核对到所选主机，未发送。');}
  finally{if(savedHostFlight.current===selected){savedHostFlight.current=null;setSavedHostBusy(false);}}
 };
 const firstHostSaveFlight=useRef(false),[firstHostSaving,setFirstHostSaving]=useState(false);
 const unsettledSaveNote='原话已保存在本机，未发送。';
 const [savedLocalReturn,setSavedLocalReturn]=useState<(SavedHostSource&{handle:string;navigation:number;key:string})|null>(null);
 const [savedLocalOpening,setSavedLocalOpening]=useState(false),savedLocalFlight=useRef(false);
 const savedLocalVisible=savedLocalReturn&&savedLocalReturn.document===bridge.getDocumentEpoch()&&savedLocalReturn.navigation===navigationRevision&&
  savedLocalReturn.key===draftKey&&isChatSurface&&intent==='chat'&&localStatus===unsettledSaveNote&&!taskOpen&&!taskOpening&&!workOpen&&!commandOpen&&!preparationTarget&&can('conversation.select');
 const viewSavedLocal=async()=>{
  const pin=savedLocalReturn,live=bridge.getState(),draft=live.composer;
  if(!savedLocalVisible||!pin||savedLocalFlight.current||document.hidden)return;
  if(draft?.ready!==true||draft.key!==pin.key||!Number.isSafeInteger(draft.revision)||draft.text!==chatDraft.currentValue()){
   setLocalError('当前草稿尚未核对，请稍后再打开原话。');return;
  }
  const current=()=>!document.hidden&&bridge.getDocumentEpoch()===pin.document&&navigationWindow.current===pin.navigation;
  if(!current())return;
  const matched=()=>{const now=bridge.getState();return current()&&now.state.conversationId===pin.conversation&&
   now.composer?.conversationId===pin.conversation&&!!exactSavedHostMessage(asArray<Message>(now.state.messages),pin,false);};
  savedLocalFlight.current=true;setSavedLocalOpening(true);
  try{
   const reply=await bridge.command('conversation.select',{viewSavedHandle:pin.handle,key:draft.key,epoch:draft.epoch,revision:draft.revision});
   if(!current())return;
   if(reply?.opened!==true||reply.conversationId!==pin.conversation||reply.messageId!==pin.message||reply.revision!==pin.revision)
    throw new Error('原话打开回执未核对。');
   const arrived=await new Promise<boolean>(resolve=>{
    const check=()=>{if(!current())finish(false);else if(matched())finish(true);};
    const finish=(success:boolean)=>{clearTimeout(timer);unsubscribe();resolve(success);};
    const timer=window.setTimeout(()=>finish(false),5000),unsubscribe=bridge.subscribe(check);
    check();
   });
   if(!current())return;
   if(!arrived)throw new Error('当前未找到这条原话。');
   await navigate('chat',()=>{
    homeSavedHostJump.current={document:pin.document,conversation:pin.conversation,message:pin.message,revision:pin.revision,navigation:navigationWindow.current,viewOnly:true};
    setSavedLocalReturn(null);setLocalStatus('');
   },{stillCurrent:matched});
  }catch{if(current())setLocalError('未能核对刚才保存的原话，草稿仍保留，没有发送。');}
  finally{savedLocalFlight.current=false;setSavedLocalOpening(false);}
 };
 const prepareCloudGoal=async()=>{
  const visibleText=chatDraft.currentValue(), goal=visibleText.trim(), native=bridge.getState();
  if(intent!=='chat'||document.hidden||!goal||goal.length>8000||native.state.chatSubmitting||native.state.phoneSubmitting||firstHostSaveFlight.current)return;
  const original=native.composer, documentEpoch=bridge.getDocumentEpoch();
  if(original?.ready!==true||!draftKey||original.key!==draftKey||original.epoch!==draftEpoch||
     original.conversationId!==String(native.state.conversationId||'')||!Number.isSafeInteger(original.revision)){
    setLocalError('没有核对到当前草稿，没有保存或发送。');return;
  }
  // Keep the full editor value as the identity. Native reviews only the
  // normalized goal, but a pasted trailing newline is not a different draft.
  if(original.text!==visibleText){
    setLocalError('手机尚未确认这份草稿，等文字保存后再试。没有发送任何内容。');return;
  }
  if(!native.state.conversationId){
    if(!can('conversation.saveFirstHostRequest')||registration.waiting||native.state.chatBusy)return;
    const navigation=navigationWindow.current;
    const visible=()=>!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&navigationWindow.current===navigation;
    setSavedLocalReturn(null);
    setLocalStatus(status=>status===unsettledSaveNote?'':status);
    firstHostSaveFlight.current=true;setFirstHostSaving(true);
    try{
      const kept=await bridge.command<Json>('conversation.saveFirstHostRequest',{key:original.key,epoch:original.epoch,revision:original.revision});
      if(!visible())return;
      if(kept?.status!=='saved_local'||kept.dispatched!==false||kept.networkUsed!==false||kept.executionAuthorized!==false)
        throw new Error('本机保存尚未核对；原草稿保留。');
      if(kept.selectedOriginal===true)setRoute('chat');
      else if(kept.selectedOriginal===false&&kept.revision===1&&[kept.conversationId,kept.messageId,kept.viewSavedHandle].every(value=>typeof value==='string'&&/^[a-f0-9-]{36}$/.test(value)))
        setSavedLocalReturn({handle:kept.viewSavedHandle,document:documentEpoch,navigation,key:original.key,conversation:kept.conversationId,message:kept.messageId,revision:1});
      setLocalError('');
      setLocalStatus(kept.selectedOriginal===true?'':unsettledSaveNote);
    }catch(error){if(visible())setLocalError(error instanceof Error?error.message:'未核对到本机保存，原草稿保留。');}
    finally{firstHostSaveFlight.current=false;setFirstHostSaving(false);}
    return;
  }
  if(!can('remote.reviewTask'))return;
  const isCurrent=()=>{const current=bridge.getState(), next=current.composer;
    return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&current.state.conversationId===native.state.conversationId&&
      next?.key===original.key&&next?.epoch===original.epoch&&next?.revision===original.revision&&
      next?.text===visibleText&&chatDraft.currentValue()===visibleText;};
  const carriesTextOnly=asArray<Json>(native.state.selectedChatAttachments).length>0||['sourceRefs','objectRefs','previewRefs'].some(key=>asArray<Json>(obj(original)[key]).length>0);
  const from=navigationWindow.current, origin=captureWorkspaceOrigin();
  const source=can('conversation.captureHostGoal')?await bridge.command<Json>('conversation.captureHostGoal',{key:original.key,epoch:original.epoch,goal}).catch(()=>null):null;
  if(from!==navigationWindow.current||document.hidden||bridge.getDocumentEpoch()!==documentEpoch)return;
  if(!isCurrent()){setLocalError('草稿已改动，保留你的新文字。请从原对话重新带到主机。');return;}
  const originHandle=source?.linked===true&&source.executionAuthorized===false&&source.networkUsed===false&&typeof source.originHandle==='string'&&/^[a-f0-9-]{36}$/.test(source.originHandle)?source.originHandle:undefined;
  if(!originHandle){setLocalError('还不能固定这条原话，未联系主机。请从原私人对话再试。');return;}
  void openRelatedWorkspace('remote',origin,()=>{if(isCurrent())setCloudGoal({id:crypto.randomUUID(),text:goal,carriesTextOnly,originHandle,navigation:navigationWindow.current});});
 };
 // The first local save is a different next step from connecting a model. Only
 // promote it beside the blocked send for a typed, new draft in the butler.
 const firstSaveShown=route==='butler'&&ready&&!composerModelName&&!composer.primaryDisplayGate&&!displayStagePending&&intent==='chat'&&
  !state.conversationId&&draftKey.startsWith('new:')&&!!text.trim()&&can('conversation.saveFirstHostRequest');
 const firstSaveProblem=firstSaveShown?(text.length>4000?'仅保存支持最多 4,000 个字符的文字，原草稿不变。':
  asArray(state.selectedChatAttachments).length||[composer.sourceRefs,composer.objectRefs,composer.previewRefs].some(value=>asArray(value).length)?'这份草稿含附件或引用，不能仅保存为本机原话。草稿不变。':
  isListening||voiceReview||composer.voiceText?'请先核对听写，再保存原话。':
  pendingHere||registration.waiting||state.chatBusy||state.chatSubmitting||state.phoneSubmitting?'请先核对正在处理的输入。':
  composer.firstMessageMemory?.paused||composer.oneMessageLearning?.excluded||composer.oneMessageLearning?.protectDefaults||newMemoryBlocked||singleLearningBlocked||composer.mode&&composer.mode!=='queue'?'这份草稿有单条设置要保留，不能仅保存为本机原话。':
  composer.ready!==true||composer.text!==text?'手机尚未核对到当前文字。核对后再保存原话。':''):'';
 const firstHostSave=firstSaveShown?{hint:firstHostSaving?'正在保存原话，不调用模型，不联系主机。':firstSaveProblem||
  (can('conversation.selectSavedHost')&&can('remote.reviewTask')?'原话只留在这台手机。保存后可以另选主机，不会派发。':'原话只留在这台手机，不会发送。'),
  disabled:firstHostSaving||!!firstSaveProblem,busy:firstHostSaving,save:()=>{void prepareCloudGoal()}}:undefined;
 const setupFeedback=modelSetupFeedback&&intent==='chat'&&!state.chatSubmitting&&
  modelSetupFeedback.draftRevision===composer.revision&&modelSetupFeedback.preferenceRevision===state.conversationModel?.revision&&
  sameModelSetupInput(modelSetupFeedback.input,snapshot,bridge.getDocumentEpoch())?modelSetupFeedback:null;
 const setupUse=setupFeedback&&(state.modelSelectionRevision===setupFeedback.modelRevision
  ?modelSetupUse(setupFeedback.input,setupFeedback.choice,state,setupFeedback.matchesDefault)
  :state.modelSelectionRevision>setupFeedback.modelRevision?'changed':'unknown');
 // Keep the topic choice next to the verified editor, not alongside an input receipt.
 const homeNewTopic=route==='butler'&&ready&&intent==='chat'&&!!state.conversationId&&
  composer.ready===true&&composer.conversationId===state.conversationId&&draftKey===`conversation:${state.conversationId}`&&
  !state.conversationReader&&!state.loadingFirstConversationMessages&&!homeHostMessage&&!foregroundTurn&&!pendingHere&&
  !state.chatSubmitting&&!state.phoneSubmitting&&!draftSwitching&&!registration.waiting&&can('conversation.new')&&
  messages.some(message=>message.role==='user'&&!!message.id&&message.quoteAvailable===true&&!message.firstHostRequestLocal&&
   Number.isSafeInteger(message.sourceRevision)&&Number(message.sourceRevision)>0);
 const abandonEmptyReportDraft=async()=>{
  const native=bridge.getState().composer;
  if(reportDraftFlight.current||!draftKey||text.length||native?.key!==draftKey||native?.epoch!==draftEpoch||
     native?.revision!==composer.revision||native.independentReportDiscardEmpty!==true)return;
  const pin={key:draftKey,epoch:draftEpoch},doc=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const current=()=>bridge.getDocumentEpoch()===doc&&navigationWindow.current===navigation&&!document.hidden;
  reportDraftFlight.current=pin;setReportDraftSwitching(true);
  try{
   const ack=await act('ownerWork.report.followUp.discardEmpty',{...pin,revision:composer.revision});
   if(ack?.discarded===true)await awaitPickerProjection(current,()=>{
    const replacement=bridge.getState().composer;
    return replacement?.ready===true&&(replacement.key!==pin.key||replacement.epoch!==pin.epoch)?true:undefined;
   });
  }catch(error){if(current())setLocalError(error instanceof Error?error.message:String(error))}
  finally{if(reportDraftFlight.current===pin){reportDraftFlight.current=null;setReportDraftSwitching(false)}}
 };
 const chooseDraftTeam=route==='chat'&&intent==='chat'&&!taskOpen&&!draftSwitching&&snapshot?.view?.section===null&&snapshot.view.taskId===null&&
   can('teams.snapshot')&&can('teams.readDefinition')?originalTeamDraft(snapshot):null;
 const composerRegion=<div className="pw-composer-region">
   {route==='butler'&&ready&&(messages.length>0&&!!state.conversationId||!!foregroundTurn||pendingHere)&&<div className="pw-butler-conversation-context">
    <Button variant="ghost" size="small" leadingIcon={homeHostMessage?IconServer:IconChat} onClick={homeHostMessage?continueSavedHostFromHome:foregroundTurn&&!pendingHere?returnToForegroundReply:()=>void navigate('chat')} aria-label={homeHostMessage?`${continueSavedHostLabel}：${currentConversation?.title||'原交办'}`:pendingHere?'核对当前输入':`继续话题：${currentConversation?.title||'当前对话'}`} title={currentConversation?.title||'当前对话'}>
     <span>{homeHostMessage?continueSavedHostLabel:pendingHere?'核对当前输入':foregroundTurn?`${conversationActivity} · 查看话题`:'继续'}{currentConversation?.title?' · '+currentConversation.title:''}</span><IconChevronRight size={15} aria-hidden="true"/>
    </Button>
    {replyStop}
    {homeNewTopic&&<Button variant="ghost" size="small" leadingIcon={IconNewChat} onClick={()=>chooseConversation(null)}>聊件新事</Button>}
   </div>}
   {route==='chat'&&<ConversationReadingNav {...readingNav}/>}
   {route==='chat'&&chatAttention&&<Button variant="ghost" className="pw-chat-attention" data-task-id={chatAttention.id} trailingIcon={IconChevronRight} aria-label={`${can('tasks.review')?taskActionLabel(chatAttention):'查看任务'}：${chatAttention.goal}`} disabled={taskOpening} onClick={()=>openTask(chatAttention.id,can('tasks.review')?'review':'overview')}><span className="pw-chat-attention-copy"><small>{can('tasks.review')?taskActionLabel(chatAttention):'查看任务'}</small><b>{chatAttention.goal}</b></span></Button>}
   {route==='chat'&&ready&&messages.some(m=>!m.firstHostRequestLocal)&&!composerModelName&&!displayGate&&<div className="pw-butler-model-setup">{modelSetupAction}</div>}
   {setupFeedback&&setupUse&&<div className="pw-butler-model-setup" aria-label="原输入的模型">
    <p role="status">{modelSetupUseMessage(setupFeedback.choice,setupUse)}</p>
    {setupUse!=='inherited'&&setupUse!=='explicit'&&!channelPhoneModel&&
      ['models.conversation.api.select','models.conversation.accounts.select','models.conversation.group'].some(can)&&
      <Button variant="ghost" size="small" disabled={!!state.modelSelectionBusy} onClick={()=>modelPicker.current?.open('models')}>选择此对话的模型</Button>}
   </div>}
   {intent==='chat'&&!draftSwitching&&!state.chatSubmitting&&!registration.waiting&&<ConversationDraftStatus composer={composer} conversation={String(state.conversationId||'')} text={text} attachments={asArray(state.selectedChatAttachments).length}/>}
   {intent==='chat'&&<ComposerSourceReferences key={`${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.source.remove')}/>}
   {intent==='chat'&&!text.length&&composer.independentReportDiscardEmpty===true&&can('ownerWork.report.followUp.discardEmpty')&&
    <Button variant="ghost" size="small" disabled={draftSwitching||registration.waiting||reportDraftSwitching}
     onClick={()=>{void abandonEmptyReportDraft()}}>放弃空追问</Button>}
   {intent==='chat'&&<ComposerObjectReferences key={`objects:${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.object.remove')}/>}
   {intent==='chat'&&<ComposerPreviewReferences key={`preview:${draftKey}:${draftEpoch}`} composer={composer} disabled={!!state.chatSubmitting||!can('composer.preview.remove')}/>}
   <ChatComposer continuationMode={intent==='chat'&&(state.chatSendMode==='queue'||state.chatSendMode==='steer')?state.chatSendMode:undefined}
     contextStatus={<IconChat size={15} aria-label="当前对话"/>} controls={<ConversationModelPickerControls/>} status={intent==='chat'&&draftSwitching?'正在切换对话，等对应草稿恢复后可编辑。':inputLimitMessage||undefined} sendDisabled={keepOriginalPhoneGoal||!!inputLimitMessage||!!displayBlocked||displayDraftSettling||(!composerModelName&&!localDisplayReady)||intent==='chat'&&(previewRefsUnavailable||newMemoryBlocked||singleLearningBlocked||!localDisplayReady&&ordinaryDraftRevision===null)||!!pendingOriginal} sendLabel={keepOriginalPhoneGoal?'这段原目标已登记，核对这次启动或编辑新目标':inputLimitMessage||displayBlocked||(displayDraftSettling?'正在核对本机问题':!composerModelName&&!localDisplayReady?'请先连接模型':intent==='chat'&&(newMemoryBlocked||singleLearningBlocked)?'请先核对记忆选择':intent==='chat'&&previewRefsUnavailable?'请重新选择预览元素':pendingOriginal?'请核对这条输入的原回执':undefined)} toolbar={<div className="pw-composer-intent">
    <PopoverTrigger isOpen={voiceOptionsOpen} onOpenChange={setVoiceOptionsOpen}><IconButton icon={IconAdd} size="medium" aria-label="添加内容"/><Popover aria-label="添加内容"><PopoverHeader title="添加内容"/>{intent==='chat'&&can('attachments.openPicker')&&<Button variant="ghost" leadingIcon={IconFile} disabled={!!state.chatSubmitting||!draftKey||fileAttachmentPickerLaunch.current?.settled===false} onClick={openRegularAttachmentPicker}>添加附件</Button>}{chooseDraftTeam?.kind==='goal'&&<Button variant="ghost" leadingIcon={IconTeam} disabled={chooseDraftTeam.text!==text} onClick={()=>{
     if(pickTeamForCurrentDraft(chooseDraftTeam))setVoiceOptionsOpen(false);
     else setLocalError('原草稿已变化，未离开这条对话。');
    }}>选择协作编组</Button>}{can('voice.start')&&<Button variant="ghost" disabled={!!state.chatSubmitting||!!state.phoneSubmitting} onClick={()=>{setVoiceOptionsOpen(false);run(isListening?'voice.stop':'voice.start',{phone:intent!=='chat'})}}>{isListening?'结束听写':'语音输入'}</Button>}</Popover></PopoverTrigger>
    <ComposerWorkOptions intent={intent} onIntent={changeIntent} open={workOptionsOpen} onOpenChange={setWorkOptionsOpen} phoneAvailable={can('phone.prepare')} disabled={!!state.chatSubmitting||!!state.phoneSubmitting||phoneReviewPending} cloudGoalLabel={state.conversationId?'带目标去我的主机':'保存到本机（不发送）'} onCloudGoal={!firstHostSave&&!firstHostSaving&&intent==='chat'&&chatDraft.value.trim()&&chatDraft.value.length<=(state.conversationId?8000:4000)&&
      (state.conversationId?can('remote.reviewTask'):can('conversation.saveFirstHostRequest'))?prepareCloudGoal:undefined} onPermissions={()=>{permissionExit.captureReturnFocus(workspace.current?.querySelector<HTMLElement>('textarea[aria-label="工作输入"]')||null);setPermissionOptionsOpen(true)}} onMemory={intent==='chat'&&canEditMemoryHere?()=>setConversationMemoryOpen(true):undefined} memoryPaused={memoryPausedHere} busy={!!state.chatBusy} sendMode={state.chatSendMode||'queue'} onSendMode={can('composer.mode')?mode=>run('composer.mode',{key:draftKey,epoch:draftEpoch,mode}):undefined}/>
   </div>} composerKey={intent==='chat'?chatOwner:phoneOwner} value={text} onChange={editText} onSend={send} onStop={currentCancelIdentity&&can('chat.cancel')?stopOriginalChat:undefined} busy={intent==='chat'&&!!state.chatBusy} submitting={intent==='chat'?!!state.chatSubmitting||registration.waiting:phoneReviewPending||!!state.phoneSubmitting} disabled={!ready||(intent==='chat'?draftSwitching||reportDraftSwitching||!draftKey||!can('composer.edit'):!can('phone.prepare')||phoneDraft.sourceState==='elsewhere')} placeholder={intent==='chat'?(state.chatBusy?'补充要求，或继续写…':route==='butler'?'说说想做什么，或接着聊…':'写下想做的事…'):'想在手机上做什么？'} attachments={(intent==='chat'?asArray<Json>(state.selectedChatAttachments):[]).map(f=>({...f,id:String(f.id),name:f.name||'附件'}))} onRemoveAttachment={can('attachments.remove')?id=>run('attachments.remove',{id,key:draftKey,epoch:draftEpoch}):undefined} onVoice={can('voice.start')?()=>run(isListening?'voice.stop':'voice.start',{phone:intent!=='chat'}):undefined} listening={isListening} voiceStatus={state.voiceInput?.message} onDiscardVoice={can('voice.stop')||can('voice.discard')?discardVoice:undefined}>
     {memoryStatus&&<Button variant="ghost" size="small" className="pw-composer-memory-status" trailingIcon={IconChevronRight} aria-haspopup="dialog" onClick={()=>setConversationMemoryOpen(true)}>{memoryStatus}</Button>}
   </ChatComposer>
   {stageTeamGoal?<ConversationStarters onDraft={stageTeamGoal} purpose="team"/>:route==='butler'&&ready&&!!composerModelName&&!messages.length&&!foregroundTurn&&!butlerWorkRows.length&&!homeHasPreparations&&!phoneActivity&&!homePhoneAttention.length&&
    stageExample&&<ConversationStarters onDraft={stageExample}/>}
   {intent!=='chat'&&['elsewhere','changed','unverified'].includes(phoneDraft.sourceState)&&<p className="pw-field-hint" role="status">{phoneDraft.sourceState==='elsewhere'?'手机草稿仍留在原私人对话，这里不会显示或提交它。':phoneDraft.sourceState==='changed'?'原消息已换版或不是最新交代。草稿已保留。要改用最新交代，请在那条消息的操作里选择「保留手机草稿，关联这条原话」。':'这份保留草稿缺少可核对的原消息。原文字还在，可在最新主人消息的操作里重新指定来源，或清空后另写。'}</p>}
   {intent!=='chat'&&phoneDraft.phone===true&&!state.conversationId&&!phoneDraft.channelOrigin&&composer.key&&can('phone.draft.recentStarts')&&<EarlierDraftPhoneStarts key={chatOwner} composer={String(composer.key)} except={phoneStart?.taskId} onTask={openBlankFirst}/>}
   {phoneStart&&phoneStart.draftRequestId&&phoneStart.status!=='rejected'&&!phoneReview&&<section className="pw-input-receipt" aria-label="本次草稿的手机任务">{phoneStart.admissionState&&phoneStart.admissionState!=='admitted'?<p role="status">{phoneStart.message}</p>:<Receipt receipt={phoneStart}/>}
     {blankReceiptActions(phoneStart)}{keepOriginalPhoneGoal&&<small>保留原目标，不会再次启动。编辑成新目标后可以重新审阅。</small>}</section>}
   {presentedPhoneStart&&!presentedPhoneStart.draftRequestId&&!phoneReview&&(keepOriginalPhoneGoal||presentedPhoneStart.status==='unknown'&&(!presentedPhoneStart.requestId||sameLinkedStart))&&
    <section className="pw-input-receipt" aria-label="原手机启动回执">
     {keepOriginalPhoneGoal?<p role="status">{originalDenialMode&&<strong>原{originalDenialMode==='main_screen'?'手机主屏':'后台独立屏'}请求：</strong>}{presentedPhoneStart.message}</p>:<Receipt receipt={presentedPhoneStart}/>}
     {sameLinkedStart?(can('phone.query')&&<Button variant="ghost" size="small" disabled={phoneReviewPending} onClick={()=>queryOriginalPhone()}>核对这次启动</Button>):
       <Button variant="ghost" size="small" onClick={()=>presentedPhoneStart.taskId?openTask(presentedPhoneStart.taskId):navigate('tasks')}>核对原任务记录</Button>}
     {keepOriginalPhoneGoal&&<small>核对只读原回执，不会重新启动。可以直接编辑成新目标。</small>}
    </section>}{(state.voiceInput?.reviewAvailable||composer.voiceText)&&<div className="pw-voice-review"><p>有听写文字待核对。</p>{can('voice.review')&&<Button variant="secondary" disabled={voicePending} onClick={openVoiceReview}>核对原听写</Button>}{can('voice.discard')&&<Button variant="ghost" disabled={voicePending} onClick={discardVoice}>丢弃本次听写</Button>}</div>}<ConversationDelivery rows={pendingInputs} can={can} submitting={!!state.chatSubmitting} ordinaryComposer={intent==='chat'} readWindow={JSON.stringify([navigationRevision,bridge.getDocumentEpoch(),draftEpoch,state.traceSourceRevision])} onReadSavedFirst={async row=>{
    const navigation=navigationWindow.current,doc=bridge.getDocumentEpoch(),history=state.traceSourceRevision;
    const same=()=>{
     const live=bridge.getState(),view=obj(live.view),current=live.composer;
     return !document.hidden&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===doc&&
      !view.section&&!view.taskId&&!live.state.conversationReader&&!live.state.conversationId&&live.state.traceSourceRevision===history&&
      current?.key===draftKey&&current?.epoch===draftEpoch&&current?.revision===row.draftRevision&&
      asArray<Json>(current?.pending).some(p=>p.clientMessageId===row.clientMessageId&&p.draftKey===draftKey&&p.draftRevision===row.draftRevision&&p.canReadSavedFirstChatSource===true);
    };
    if(intent!=='chat'||!row.current||!row.sameDraft||!same())throw Error('原阅读窗口已经变化，没有打开其他内容。');
    const value=await bridge.command<Json>('composer.readSavedFirst',{clientMessageId:row.clientMessageId,key:draftKey,epoch:draftEpoch,revision:row.draftRevision});
    if(!same()||value.clientMessageId!==row.clientMessageId||value.draftRevision!==row.draftRevision||value.sourceRevision!==1||
       value.admissionState!=='unconfirmed'||value.executionAuthorized!==false||typeof value.text!=='string'||!value.text||value.text.length>12000)
     throw Error('没有核对到这条原保存记录；任务受理仍待确认。');
    return value.text;
   }} onAction={(action,row)=>{
    if(action==='return'&&row.work){returnToWorkInput(row);return}
    if(action==='release-prepared'){
     if(intent==='chat'&&row.current&&row.sameDraft&&row.canReleasePreparedFirstChat===true)run('composer.releasePreparedFirst',{clientMessageId:row.clientMessageId,key:draftKey,epoch:draftEpoch,revision:row.draftRevision});
     return;
    }
    run(action==='query'?'composer.query':action==='retry'?'composer.retry':'composer.returnPending',{clientMessageId:row.clientMessageId});
   }}/></div>;
 // Conversation identity is published before its native messages are loaded.
 // Re-read only when the visible owner source identities arrive or change.
 const visibleOwnerPreparationSources=useMemo(()=>JSON.stringify(messages.flatMap(m=>
  m.role==='user'&&m.quoteAvailable===true&&m.id&&Number.isSafeInteger(m.sourceRevision)&&Number(m.sourceRevision)>0
   ? [[m.id,m.sourceRevision]] : [])),[messages]);
 const watchesOriginalBuild=route==='chat'&&can('butler.preparation.project.followup')&&[...anchoredPreparations.values()]
  .some(rows=>rows.some(row=>row.visibleOwnerRequest&&row.originalDraft));
 const preparationRefreshKey=JSON.stringify([preparationRevision,tasks.flatMap(task=>{
  const raw=task.raw,project=raw?.developmentContext;
  if(raw?.hasOwnerWorkPreparations)return [[task.id,raw.ownerWorkPreparationCount]];
  if(raw?.ownerWorkPreparation&&project?.draftOnly===true)return [[task.id,task.status,raw.runId,raw.coordinatorEpoch,raw.projectEditDraftId]];
  if(project?.ownerDraftFirstBuild)return [[task.id,task.status,raw?.runId,raw?.coordinatorEpoch,task.updatedAt]];
  // The normal list header does not carry ownerDraftFirstBuild. Only use its
  // phase changes to re-read the original native projection, never to link a task.
  if(watchesOriginalBuild&&raw?.kind==='assistant'&&raw.actorId==='owner'&&raw.spaceId==='private'&&raw.origin==null&&
     project?.format==='pocketworld.development-context.v1'&&project.runtime==='managed_process_v1'&&project.draftOnly!==true&&
     typeof project.initialSourceDigest==='string'&&/^[a-f0-9]{64}$/.test(project.initialSourceDigest))
   return [[task.id,task.status,raw.runId,raw.coordinatorEpoch,running(task)?null:task.updatedAt]];
  return [];
 }).sort((a,b)=>String(a[0]).localeCompare(String(b[0])))]);
 const openConversationPreparation=(target:ButlerPreparationTarget,trigger:HTMLButtonElement)=>{
   if(route==='chat'){
     if(document.hidden||target.sourceConversationId!==conversation||bridge.getState().state.conversationId!==conversation||taskOpen||preparationTarget)return;
     inlinePreparationOrigin.current={conversationId:conversation,top:scroll.current?.scrollTop||0,trigger};
     stopFollowing();
   }
   setPreparationTarget(target);
 };
 const openHostWorkFromChat=async(message:Message,trigger:HTMLButtonElement)=>{
  const task=message.hostGoalTaskId,source=message.id,revision=message.sourceRevision;
  const article=trigger.closest<HTMLElement>('article[data-message-id]'),scroller=scroll.current;
  if(!task||!source||typeof revision!=='number'||!Number.isSafeInteger(revision)||revision<=0||!article||article.dataset.messageId!==source||!scroller||
    route!=='chat'||!conversation||taskOpen||taskOpening||chatOpenFlight.current||obj(bridge.getState().view).taskId)return;
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch(),beforeTaskRequest=reviewRequest.current;
  const current=()=>{
   const state=bridge.getState().state,rows=asArray<Message>(state.messages).filter(m=>m.id===source);
   return !document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&navigationWindow.current===navigation&&
    state.conversationId===conversation&&!state.conversationReader&&rows.length===1&&rows[0].sourceRevision===revision&&rows[0].hostGoalTaskId===task;
  };
  if(!current())return;
  const original:ChatWorkReturn={document:documentEpoch,conversation,message:source,revision,hostTaskId:task,top:scroller.scrollTop,
   offset:article.getBoundingClientRect().top-scroller.getBoundingClientRect().top,trigger};
  chatOpenFlight.current=true;setChatWorkReading(true);
  try{
   const receipt=await act('conversation.openHostGoal',{taskId:task,conversationId:conversation,messageId:source});
   if(!current()||receipt?.taskId!==task||receipt.readOnly!==true||receipt.executionAuthorized!==false)return;
   stopFollowing();
   openTask(task,'overview',true,undefined,undefined,undefined,current,undefined,undefined,undefined,{returnTo:original,beforeTaskRequest,navigation});
  }catch{/* The existing native command reports its own error. */}
  finally{chatOpenFlight.current=false;setChatWorkReading(false);}
 };
 const openOriginalChatWork=async(message:Message,row:ConversationPreparation,next:Exclude<ConversationNext,{action:'prepare'}>,trigger:HTMLButtonElement)=>{
  const article=trigger.closest<HTMLElement>('article[data-message-id]'), scroller=scroll.current;
  if(!article||!scroller||!message.id||!conversation||taskOpen||taskOpening||obj(bridge.getState().view).taskId||preparationTarget||chatOpenFlight.current||row.sourceConversationId!==conversation)return;
  const navigation=navigationWindow.current, documentEpoch=bridge.getDocumentEpoch(),beforeTaskRequest=reviewRequest.current;
  const original:ChatWorkReturn={document:documentEpoch,conversation,message:message.id,revision:Number(message.sourceRevision||0),top:scroller.scrollTop,
    offset:article.getBoundingClientRect().top-scroller.getBoundingClientRect().top,trigger};
  const stillCurrent=()=>{
   const state=bridge.getState().state,items=asArray<Message>(state.messages).filter(m=>m.id===original.message);
   if(document.hidden||bridge.getDocumentEpoch()!==documentEpoch||navigationWindow.current!==navigation||state.conversationId!==original.conversation||items.length!==1)return false;
   const source=items[0], context=messageReplyContext(source);
   return source.role==='user'?source.quoteAvailable===true&&source.sourceRevision===original.revision:
     source.role==='assistant'&&context?.taskId===row.sourceTaskId&&context.runId===row.sourceRunId&&context.epoch===row.sourceEpoch;
  };
  if(!stillCurrent())return;
  stopFollowing();rememberChatPosition();setLocalError('');
  const owner=row.visibleOwnerRequest;
  const originalOwner=message.role==='user'&&owner?.messageId===message.id&&owner.revision===original.revision;
  if(row.chatBuild?.taskId===next.target.taskId&&!originalOwner)return;
  const interruptedRead=next.action==='draft'&&!!row.interruptedDraft;
  if(next.action==='draft'&&(row.awaitingDraft||interruptedRead)&&(!originalOwner||!can('butler.preparation.project.followup')))return;
  if(originalOwner&&can('butler.preparation.project.followup')){
   chatOpenFlight.current=true;setChatWorkReading(true);
   try{
    const value=await bridge.command<Json>('butler.preparation.project.followup',{sourceTaskId:row.sourceTaskId,proposalId:row.proposalId,
     conversationId:conversation,messageId:owner.messageId,revision:owner.revision});
    if(!stillCurrent()||!trigger.isConnected)return;
    const fresh=conversationPreparation(value);
    if(!fresh||conversationPreparationKey(fresh)!==conversationPreparationKey(row)||fresh.sourceRunId!==row.sourceRunId||
      fresh.sourceEpoch!==row.sourceEpoch||fresh.visibleOwnerRequest?.messageId!==owner.messageId||fresh.visibleOwnerRequest.revision!==owner.revision)
      throw new Error('原话与这次工作暂不能核对，没有打开另一项工作。');
    // An explicit original-draft choice remains valid after the
    // main action advances to its exact build or fixed webpage.
    const savedDraft=interruptedRead?fresh.interruptedDraft:fresh.originalDraft??fresh.awaitingDraft;
    const readDraft=next.action==='draft'&&savedDraft&&next.draftId===savedDraft.draftId&&
      workIdentityKey(next.target)===workIdentityKey(savedDraft);
    const resolved=readDraft?next:conversationNext(fresh,can);
    if((interruptedRead&&!readDraft)||resolved.action==='prepare'||resolved.action!==next.action||workIdentityKey(resolved.target)!==workIdentityKey(next.target)||
       resolved.label!==next.label||resolved.draftId!==next.draftId||resolved.approvalId!==next.approvalId||resolved.packageOperationId!==next.packageOperationId)
      throw new Error('这次交办已有变化，请按原话下的新入口核对。');
   }catch(error){if(stillCurrent()){setPreparationRevision(value=>value+1);setLocalError(error instanceof Error?error.message:'暂不能核对这次原工作。');}return;}
   finally{chatOpenFlight.current=false;setChatWorkReading(false);}
  }
  if(!stillCurrent())return;
  if(next.action==='package'&&next.packageOperationId){
   const target={taskId:next.target.taskId,operationId:next.packageOperationId};
   const native=bridge.getState(),draftKey=String(native.composer?.key||''),draftEpoch=native.composer?.epoch;
   const resume=obj(native.view).resumeRevision;
   const origin={...captureNavigationOrigin(),label:'返回原话',focus:null,focusKey:undefined,chatWork:original,
    drawerChat:{id:conversation,draftKey,documentEpoch}};
   const sameLine=(now:ReturnType<typeof bridge.getState>)=>{
    const rows=asArray<Message>(now.state.messages).filter(m=>m.id===original.message);
    return now.state.conversationId===conversation&&now.composer?.key===draftKey&&rows.length===1&&
     rows[0].role==='user'&&rows[0].quoteAvailable===true&&rows[0].sourceRevision===original.revision;
   };
   packageHandoff.open(target,({projectId})=>{packageReturn.current={target,origin,epoch:documentEpoch,navigation:navigationWindow.current,projectFocus:null,
    originalChatCurrent:now=>{const view=obj(now.view);return now.state.projectId===projectId&&!now.state.filePath&&!now.state.conversationReader&&
     !view.taskId&&view.section==null&&Number.isSafeInteger(resume)&&view.resumeRevision===resume&&
     !!draftKey&&now.composer?.ready===true&&now.composer.conversationId===conversation&&Number.isSafeInteger(draftEpoch)&&now.composer.epoch===draftEpoch&&
     Number.isSafeInteger(original.revision)&&original.revision>0&&sameLine(now);}
   };},{originalChat:()=>sameLine(bridge.getState()),departed:()=>{relatedOrigin.current=origin;}});
   return;
  }
  // The exact interrupted-draft link reads the difference, not the separate retry review.
  openTask(next.target.taskId,next.review?'review':'overview',false,undefined,undefined,{runId:next.target.runId,epoch:next.target.epoch,draftId:next.draftId},stillCurrent,next.approvalId,()=>{
   if(stillCurrent()){setPreparationRevision(value=>value+1);setLocalError('这次原工作暂不能打开，没有转到另一轮。');}
  },interruptedRead?()=>{}:undefined,{returnTo:original,beforeTaskRequest,navigation});
 };
 const preparationEntries=(conversationId:string,homeRows?:Task[])=>{
  const representedTaskIds=new Set<string>();
  if(!homeRows&&can('tasks.open')){
   for(const task of conversationWorkRows)representedTaskIds.add(task.id);
   if(conversationId&&chatAttention)representedTaskIds.add(chatAttention.id);
   for(const id of representedPhoneTaskIds)representedTaskIds.add(id);
  }
  const representedHomeRuns=homeRows?new Set((can('tasks.open')?[...homeRows,...phoneRows.map(row=>row.task)]:[]).flatMap(task=>typeof task.raw?.runId==='string'&&task.raw.runId&&Number.isSafeInteger(task.raw.coordinatorEpoch)&&task.raw.coordinatorEpoch>0?[JSON.stringify([task.id,task.raw.runId,task.raw.coordinatorEpoch])]:[])):undefined;
  return <ButlerPreparationEntries representedHomeRuns={representedHomeRuns} onPresenceChange={conversationId?undefined:setHomeHasPreparations} key={'preparations:'+conversationId}
   conversationId={conversationId} representedTaskIds={representedTaskIds} active={ready&&!taskOpen&&!preparationTarget}
   refreshKey={conversationId?JSON.stringify([preparationRefreshKey,visibleOwnerPreparationSources]):preparationRefreshKey}
   can={can} run={bridge.command} onOpen={openConversationPreparation}
   onConversationRows={conversationId?receiveConversationPreparations:undefined} placedProposalKeys={conversationId?placedProposalKeys:undefined}/>;
 };
 const preparationInline=!!preparationTarget&&!taskOpen&&route==='chat'&&preparationTarget.sourceConversationId===state.conversationId;
 const preparationInlineAnchor=preparationInline&&preparationTarget?[...anchoredPreparations].find(([,rows])=>
   rows.some(row=>conversationPreparationKey(row)===conversationPreparationKey(preparationTarget)))?.[0]:undefined;
 const modelPreparation=preparationInlineAnchor!==undefined&&preparationTarget?anchoredPreparations.get(preparationInlineAnchor)?.find(row=>
  row.kind==='project'&&row.canPrepare&&row.visibleOwnerRequest&&conversationPreparationKey(row)===conversationPreparationKey(preparationTarget)):undefined;
 const modelPreparationTrigger=inlinePreparationOrigin.current?.trigger,modelPreparationRequest=modelPreparation?.visibleOwnerRequest;
 const projectModelInput=modelPreparationRequest?modelSetupInput(bridge.getState(),bridge.getDocumentEpoch()):null;
 const openProjectProposalModel=modelPreparation&&modelPreparationRequest&&projectModelInput&&modelPreparationTrigger instanceof HTMLButtonElement&&
  modelPreparationTrigger.closest<HTMLElement>('article[data-message-id]')?.dataset.messageId===modelPreparationRequest.messageId&&
  can('connections.enter')&&can('connections.leave')?(pin:ProjectModelPreparationPin)=>{
  const navigation=navigationWindow.current,scroller=scroll.current;
  if(!scroller||!modelPreparationTrigger.isConnected||conversationPreparationKey(pin)!==conversationPreparationKey(modelPreparation)||
     pin.sourceRunId!==modelPreparation.sourceRunId||pin.sourceEpoch!==modelPreparation.sourceEpoch)return;
  const source:ChatWorkReturn={document:projectModelInput.document,conversation:pin.sourceConversationId,message:modelPreparationRequest.messageId,revision:modelPreparationRequest.revision,
   top:scroller.scrollTop,offset:0,trigger:modelPreparationTrigger};
  const stillCurrent=()=>{const now=bridge.getState();return navigationWindow.current===navigation&&!obj(now.view).section&&
   sameProjectModelChat(projectModelInput,source,now)&&sameProjectModelRevision(pin,now.state)&&
   now.capabilities.commands.includes('connections.enter')&&now.capabilities.commands.includes('connections.leave');};
  // Settings returns to the same original message, not to a retained review or an automatic retry.
  const origin:WorkspaceOrigin={...captureWorkspaceOrigin(),label:'返回原话',focus:null,focusKey:undefined,chatWork:source,projectModelInput};
  void navigate('model-settings',()=>{relatedOrigin.current=origin},{keepOrigin:true,stillCurrent});
 }:undefined;
 useLayoutEffect(()=>{
  const origin=inlinePreparationOrigin.current;
  if(preparationTarget||!origin)return;
  inlinePreparationOrigin.current=null;
  if(route!=='chat'||taskOpen||state.conversationId!==origin.conversationId||!scroll.current)return;
  scroll.current.scrollTop=origin.top;
  if(origin.trigger?.isConnected&&!origin.trigger.matches(':disabled'))origin.trigger.focus({preventScroll:true});
 },[preparationTarget,route,taskOpen,state.conversationId]);
 const openPhoneProposalSetup=(t:ButlerPreparationTarget,pin:PhonePreparationSetupPin)=>{
  const now=bridge.getState(),v=obj(now.view),owner=phonePreparationOwner(),origin=captureWorkspaceOrigin();
  const changed=()=>setLocalError('原手机工作页面已变化，未切换到其他准备。');
  if(document.hidden)return;
  if(!owner||v.section)return changed();
  if(origin.task){
   const id=origin.task.id,rows=asArray<Task>(now.state.tasks).filter(row=>row.id===id),task=rows.length===1?rows[0]:undefined;
   const run=task?.raw?.runId,epoch=task?.raw?.coordinatorEpoch;
   if(id!==t.sourceTaskId||v.taskId!==id||openedTaskId.current!==id||typeof run!=='string'||!run||!Number.isSafeInteger(epoch)||epoch<1)return changed();
   origin.task={...origin.task,expected:{runId:run,epoch}};
  }else if(v.taskId||route!=='butler'&&(route!=='chat'||now.state.conversationId!==t.sourceConversationId))return changed();
  const target={sourceTaskId:t.sourceTaskId,proposalId:t.proposalId,sourceConversationId:t.sourceConversationId};
  void openSetup('phone',undefined,undefined,undefined,undefined,{...origin,label:'返回原手机工作',goal:undefined,focus:null,focusKey:undefined,
   phonePreparation:{target,pin,owner}});
 };
 const setupReturn=preparationTarget?.fromPhoneSetup,returnedOwner=setupReturn?phonePreparationOwner():'';
 const wrongReturn=!!setupReturn&&(setupReturn.navigation!==navigationRevision||snapshot?.composer?.ready===true&&returnedOwner!==setupReturn.owner);
 useEffect(()=>{if(wrongReturn)closePreparation()},[setupReturn,wrongReturn,closePreparation]);
 const returnedView=obj(snapshot?.view);
 const originalPreparationVisible=!setupReturn||!wrongReturn&&returnedOwner===setupReturn.owner&&!returnedView.section&&
  String(returnedView.taskId||'')===setupReturn.taskView&&(setupReturn.taskView?taskOpen&&!taskOpening&&openedTaskId.current===setupReturn.taskView:!taskOpen);
 const preparationDetail=preparationTarget&&originalPreparationVisible&&<ButlerPreparationDetail
  key={preparationTarget.sourceTaskId+':'+preparationTarget.proposalId}
  target={preparationTarget} projects={projects} can={can} run={bridge.command} onClose={closePreparation}
  inline={preparationInline} registerBack={registerPreparationBack} registerBeforeLeave={registerPreparationBeforeLeave}
  onOpenTask={(id, original)=>openTask(id,'overview',false,undefined,undefined,original)}
  onOpenPhoneRun={original=>openTask(original.taskId,'overview',false,undefined,undefined,original)}
  onOpenPhoneReport={setMessageOriginTarget}
  onPrepareModel={openProjectProposalModel}
  onConversation={!preparationInline&&can('conversation.select')&&!state.chatSubmitting?selectConversation:undefined}
  onSetup={can('setup.read')?(kind,pin)=>{
   if(kind==='phone'&&pin)openPhoneProposalSetup(preparationTarget,pin);
   else if(kind==='project')void openSetup('tools');
  }:undefined}/>;
 const deliveredFilesWindow=(visit:DeliveredFileVisit|null)=>{
  const live=bridge.getState(),view=obj(live.view),owner=live.state;
  return !!visit&&!document.hidden&&route==='chat'&&intent==='chat'&&!taskOpen&&visit.navigation===navigationWindow.current&&
   visit.document===bridge.getDocumentEpoch()&&visit.resume===view.resumeRevision&&owner.conversationId===visit.conversation&&
   !view.taskId&&!view.section&&!owner.conversationReader&&!owner.queuedInputsPage&&!owner.attachmentWorkspace?.open;
 };
 const deliveredFilesCurrent=(visit:DeliveredFileVisit|null)=>deliveredFileRef.current===visit&&deliveredFilesWindow(visit);
 const closeDeliveredFiles=(restore=true,visit=deliveredFileRef.current)=>{
  if(!visit||deliveredFileRef.current!==visit)return;
  deliveredFileRef.current=null;setDeliveredFileVisit(null);
  if(restore&&deliveredFilesWindow(visit)&&visit.trigger.isConnected)
   requestAnimationFrame(()=>{if(!deliveredFileRef.current&&deliveredFilesWindow(visit)&&visit.trigger.isConnected)visit.trigger.focus({preventScroll:true});});
 };
 const showDeliveredFiles=(trigger:HTMLButtonElement)=>{
  const live=bridge.getState(),view=obj(live.view),conversation=String(live.state.conversationId||'');
  const visit:DeliveredFileVisit={conversation,document:bridge.getDocumentEpoch(),navigation:navigationWindow.current,resume:view.resumeRevision,
   trigger,sources:[],more:false};
  if(deliveredFileRef.current||!conversation||!deliveredFilesWindow(visit))return;
  const ordered=asArray<Message>(live.state.messages).flatMap(message=>{
   const result=messageTaskResult(message);
   return message.role==='assistant'&&message.id&&Number.isSafeInteger(message.sourceRevision)&&
    result?.kind==='owner_work'&&result.status==='completed'&&!result.noticeKind&&message.taskId===result.taskId
    ?[{messageId:message.id,revision:message.sourceRevision!,result}]:[];
  }).sort((a,b)=>b.result.endedAt-a.result.endedAt);
  visit.sources=ordered.slice(0,4);visit.more=ordered.length>4;deliveredFileRef.current=visit;setDeliveredFileVisit(visit);
 };
 const deliveredFileCurrent=deliveredFilesCurrent(deliveredFileVisit);
 useEffect(()=>{if(deliveredFileVisit&&!deliveredFileCurrent)closeDeliveredFiles(false,deliveredFileVisit)},[deliveredFileVisit,deliveredFileCurrent]);
 const chatNavigation=!desktopNavigation&&<ChatNavigationMenu title={messages.length?currentTitle:'与管家对话'} navigationTrigger={<IconButton ref={chatBackGlass} icon={IconArrowLeft} className="pw-chat-glass-trigger" aria-label="返回管家" onClick={()=>navigate('butler')}/>} onHome={()=>navigate('butler')} isOpen={chatMenuOpen} onOpenChange={setChatMenuOpen} triggerRef={chatMenuTrigger} submitting={!!state.chatSubmitting} unread={can('remoteInbox.state')?Number(state.remoteInbox?.unread||0):0} onNewConversation={can('conversation.new')?()=>{void navigate('butler',()=>run('conversation.new'))}:undefined} onConversations={trigger=>{navigationReturnFocus.current=trigger;showWorkSearch()}} onNavigation={()=>{navigationReturnFocus.current=chatMenuTrigger.current;setCommandOpen(false);setWorkOpen(true)}} onOrganize={currentConversation&&can('conversation.organization.read')?()=>setOrganizationTarget(currentConversation.id):undefined} onMemory={canEditMemoryHere?()=>setConversationMemoryOpen(true):undefined} onDeliveredFiles={currentConversation&&intent==='chat'&&can('tasks.resultFiles')&&can('attachmentWorkspace.open')?showDeliveredFiles:undefined} onNotifications={can('remoteInbox.state')?()=>{void openNotifications()}:undefined} onSettings={()=>navigate('settings')}/>;
 const latestSavedMessage=messages.at(-1),latestSavedWork=savedReplyDetails.at(-1);
 const allowMessageTail=ready&&!desktopNavigation&&intent==='chat'&&!taskOpen&&!chatAttention&&!phoneActivity&&!!latestSavedMessage&&
  !latestSavedWork?.phoneResult&&!latestSavedWork?.taskResult&&!latestSavedMessage.originalHostFinal&&!latestSavedMessage.attachments?.length&&
  anchoredPreparations.size===0;
 const chatOpening=ready&&!messages.length&&!foregroundTurn;
 const readingChatOpening=chatOpening&&state.loadingFirstConversationMessages===true;
 const chatPage=<main className={`pw-chat-workspace${chatOpening&&!readingChatOpening?' pw-chat-empty':''}`}>
  <div ref={scroll} tabIndex={-1} className="pw-conversation-scroll" data-conversation-id={String(state.conversationId||'')} onScroll={saveReading} aria-label="当前对话消息"><div ref={chatContent} className="pw-conversation-content">
   {ready&&messages.length>0&&state.conversationId&&can('conversation.reader.open')&&<Button variant="ghost" size="small" leadingIcon={IconHistory} onClick={event=>void openChatHistory(event.currentTarget)}>浏览对话记录</Button>}
   {!ready||readingChatOpening?<div className="pw-start" role={readingChatOpening?'status':undefined}><AgentAvatar agentId="main" state="idle" size={40}/><ShimmerText>{readingChatOpening?'正在读取当前对话':'正在恢复当前工作'}</ShimmerText><div className="pw-loading-copy" aria-hidden="true"><Skeleton width="68%" height={13}/><Skeleton width="92%" height={13}/><Skeleton width="80%" height={13}/></div></div>:chatOpening?<ConversationOpening setup={!composerModelName&&!displayGate?modelSetupAction:undefined}
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
    const linkedFeedback=linked&&!linkedProjectAction(linked)&&!m.hostGoalTaskId&&!phoneResult&&!taskResult&&!representedPhoneTaskIds.has(linked.id)&&linked.id!==chatAttention?.id&&!conversationWorkIds.has(linked.id)&&messageTaskAnchors.get(linked.id)===index?linked:null;
    const completedFeedback=linkedFeedback&&conversationTaskCompact(linkedFeedback);
    const returnedWork=phoneResult||(taskResult?.noticeKind?null:taskResult);
    const earlierRun=returnedWork&&linked?.raw?.runId&&(linked.raw.runId!==returnedWork.runId||linked.raw.coordinatorEpoch!==returnedWork.epoch);
    const readPhone=!!m.id&&m.sourceRevision===1&&can('phone.result.original');
    const inlineResult=!earlierRun&&!phoneResult&&taskResult?.kind==='owner_work'&&taskResult.status==='completed'&&!taskResult.noticeKind&&!!m.id;
    const delivery=savedConversationDelivery(m,String(state.conversationId||''),obj(state.chatReceipts));
    const openQueuedReceipt=m.role==='user'&&m.submission?.status==='queued'&&!!m.submission?.taskId&&can('queued.open');
    const verifiedQueueLabel=openQueuedReceipt&&delivery?.status==='queued';
    const savedTrace=!phoneResult&&!taskResult&&m.id&&Number(m.sourceRevision)>0?replyContext:null;
    const reminderReply=route==='chat'&&m.role==='assistant'&&!m.originalHostFinal&&savedTrace?.originalReminderRecorded===true&&savedTrace.replyId&&savedTrace.deliveryId===null&&
      savedTrace.replyToMessageIds.length===1&&m.id&&Number.isSafeInteger(m.sourceRevision)&&can('plans.reviewSchedule')?savedTrace:null;
    const noteIdentity=!phoneResult&&!taskResult&&replyContext&&!representedProjectRuns.has(workIdentityKey(replyContext))&&messageTaskAnchors.get(replyContext.taskId)===index?replyContext:null;
    const preparations=anchoredPreparations.get(index);
    const originalProject=preparations?.length===1?preparations[0]:null;
    const olderProjectDraft=originalProject?.originalTask&&originalProject.originalDraft&&can('projectDraft.readDifference')&&can('tasks.open')&&
      conversationNext(originalProject,can).action!=='draft'?{row:originalProject,target:originalProject.originalTask,draftId:originalProject.originalDraft.draftId,
       label:originalProject.acceptedDraft?'查看已验收的原改稿':'查看原改稿'}:undefined;
    const canQuote=intent==='chat'&&m.quoteAvailable&&can('messageQuote.open')&&can('messageQuote.stage')&&can('messageQuote.close');
    const savedAttachmentChoices=messageAttachmentDraft?savedOwnerAttachmentChoices(m):[];
    const memoryShortcut=intent==='chat'&&row.lastOfRole&&!phoneResult&&!taskResult&&can('taskMemory.answer')?originalAnswerMemoryMessage(m,replyContext):null;
    return <article key={row.key} tabIndex={-1} data-message-index={index} data-message-id={id} data-reply-continuing={continued||undefined} className={`pw-message pw-message-${m.role}`} aria-label={m.role==='assistant'?(phoneResult||taskResult?'任务结果':'助手回复'):'你的消息'}>
     {m.role==='assistant'&&<ConversationTurnActivity phase={null} conversation={String(state.conversationId||'')} identity={noteIdentity} task={linked}
      receiptsInMenu={!!savedTrace&&Number.isSafeInteger(m.sourceRevision)&&!!m.content.trim()&&can('tasks.open')&&can('history.read')}
      actionElsewhere={!!linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')||!!chatAttention&&chatAttention.id===noteIdentity?.taskId}
      onRead={noteIdentity&&can('tasks.open')?(layer,trigger)=>openTurnNotes(noteIdentity,layer,savedTrace?m:undefined,trigger):undefined}/>}
     {m.originalHostFinal?<div className="pw-user-text">{m.content}</div>:m.role==='assistant'&&phoneResult&&m.id&&m.sourceRevision===1?<PhoneMessageReturn result={phoneResult} conversation={String(state.conversationId||'')} messageId={m.id} revision={Number(m.sourceRevision)} body={m.content}
       onOriginal={readPhone?setMessageOriginTarget:undefined} onRun={can('tasks.open')?openPhoneWorkFromMessage:undefined}>
       <CitedAnswer continuous text={m.content} onCopyCode={copyWithLocalFeedback} sources={refs as any} onOpenSource={()=>openSource(m)}/>
     </PhoneMessageReturn>:m.role==='assistant'?<CitedAnswer continuous text={m.content} onCopyCode={copyWithLocalFeedback} sources={refs as any} onOpenSource={()=>openSource(m)}/>:m.content?<div className="pw-user-text">{m.content}</div>:null}
     {!!preparations?.length&&<ConversationPreparationActions rows={preparations}
       can={can} disabled={!!preparationTarget||taskOpen||chatWorkReading||packageOpening} onOpen={openConversationPreparation}
       onRead={(row,next,trigger)=>void openOriginalChatWork(m,row,next,trigger)}/>}
     {preparationInlineAnchor===index&&preparationDetail}
     {asArray<Json>(m.attachments).length>0&&<div className="pw-message-files">{asArray<Json>(m.attachments).map(file=><MessageAttachment
      key={`${file.id}:${file.sha256}`} file={file} view={`chat:${navigationRevision}:${draftEpoch}`}
      origin={{conversationId:String(state.conversationId||''),messageId:m.id,sourceRevision:m.sourceRevision}}
      enabled={!taskOpen&&!state.conversationReader&&can('attachments.open')} canLocate={can('attachmentWorkspace.open')}/>)}</div>}
     {m.originalHostFinal&&m.hostGoalTaskId&&m.id&&<OriginalPrivateHostAnswer result={m.originalHostFinal}
       read={can('conversation.readPrivateHostFinal')?()=>bridge.command('conversation.readPrivateHostFinal',{conversationId:state.conversationId,messageId:m.id,taskId:m.hostGoalTaskId}):undefined}/>}
     {localSavedHostMessage(m)&&<div className="pw-message-footer">
       <span className="pw-field-hint">仅保存到本机，未派发</span>
       {hasSavedHostPicker&&
         <Button variant="ghost" size="small" leadingIcon={IconServer} data-saved-host-continue disabled={!canChooseSavedHost}
           aria-description="先选择主机，不会在这一步发送原话。" onClick={event=>chooseHostForSource(m,event.currentTarget)}>{continueSavedHostLabel}</Button>}
     </div>}
     {m.submission&&<Receipt receipt={m.submission}/>}
     <MotionFeedback className="pw-message-footer" identity={`${id}:${m.submission?.status||'saved'}:${refs.map(source=>source.id).join(',')}`} initial={continued}>
     {m.hostGoalTaskId&&m.id&&can('conversation.openHostGoal')&&<Button variant="ghost" size="small" className="pw-message-trace" leadingIcon={IconFolder}
       disabled={taskOpen||chatWorkReading} onClick={event=>void openHostWorkFromChat(m,event.currentTarget)}>打开原主机工作</Button>}
     {openQueuedReceipt&&<Button variant="ghost" className="pw-message-queue-action" leadingIcon={IconClock} onClick={()=>run('queued.open',{taskId:m.submission!.taskId,conversationId:state.conversationId})}>{verifiedQueueLabel?'已入队 · 查看或调整':'查看原输入队列'}</Button>}
     {delivery&&row.lastOfRole&&!verifiedQueueLabel&&<SavedConversationDelivery receipt={delivery}/>}
     {m.role==='user'&&linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')&&
      <ConversationLinkedReason conversation={String(state.conversationId||'')} task={linkedFeedback}/>}
     {linkedFeedback&&(attention(linkedFeedback)||linkedFeedback.status==='failed')&&<Button variant="ghost" className="pw-message-work-action" leadingIcon={completedFeedback?IconCheckCircle:attention(linkedFeedback)||linkedFeedback.status==='failed'?IconError:IconClock} aria-label={`查看任务详情：${linkedFeedback.goal}`} title={linkedFeedback.goal} disabled={!can('tasks.open')} onClick={()=>openTask(linkedFeedback.id,attention(linkedFeedback)&&can('tasks.review')?'review':'overview')}><span className="pw-message-work-next">{taskNeedsReconciliation(linkedFeedback)?'有操作待核对':attention(linkedFeedback)?taskActionLabel(linkedFeedback):linkedFeedback.status==='failed'?'查看失败原因':terminal(linkedFeedback)?'查看详情':'查看进展'}<IconChevronRight size={15} aria-hidden="true"/></span></Button>}
     {(m.content||delivery||m.role==='assistant'||canQuote||savedAttachmentChoices.length>0||(m.retainedSources&&can('sources.open')))&&<div className="pw-message-tools">
       {returnedWork&&(!phoneResult||!m.id||m.sourceRevision!==1)&&can('tasks.open')&&<Button variant="ghost" size="small" className="pw-message-trace" leadingIcon={IconFolder} disabled={inlineResult&&!!openingChatResultId}
        onClick={event=>{
          if(inlineResult&&taskResult&&m.id)void openChatResult(m.id,taskResult,event.currentTarget);
          else openTask(returnedWork.taskId,'overview',false,undefined,undefined,earlierRun?undefined:{runId:returnedWork.runId,epoch:returnedWork.epoch});
        }}>
        {openingChatResultId===m.id?'正在读取成果…':earlierRun?'查看所属工作':returnedWork.status==='completed'?'查看成果':'查看这次工作'}
       </Button>}
      {m.role==='assistant'&&!!m.content&&<MessageActions onCopy={()=>copyWithLocalFeedback(m.content)} onSpeak={can('speech.speak')&&m.id&&row.lastOfRole?playing=>run(playing?'speech.speak':'speech.stop',{messageId:m.id}):undefined} speaking={!!state.speaking}/>}
      {memoryShortcut&&<Button variant="ghost" size="small" className="pw-message-trace" leadingIcon={IconMemory} aria-label="核对这条回答提供给模型的记忆" onClick={()=>setProvidedMemoryMessage(memoryShortcut)}>本次记忆</Button>}
      <PopoverTrigger isOpen={messageMenu===id} onOpenChange={value=>setMessageMenu(value?id:null)}>
       <IconButton icon={IconMore} size="small" aria-label="消息操作与来源"/>
       <Popover className="pw-message-popover" aria-label="消息操作与来源"><div className="pw-message-menu">
        {delivery&&<SavedConversationDelivery receipt={delivery} details/>}
        {olderProjectDraft&&<Button variant="ghost" leadingIcon={IconFile} disabled={taskOpen||chatWorkReading||packageOpening}
         onClick={()=>{const original=Array.from(chatMessageList.current?.children||[]).find(node=>(node as HTMLElement).dataset.messageId===id);
          const trigger=original?.querySelector<HTMLButtonElement>('button[aria-label="消息操作与来源"]');
          setMessageMenu(null);if(trigger)void openOriginalChatWork(m,olderProjectDraft.row,{action:'draft',label:olderProjectDraft.label,
           target:olderProjectDraft.target,draftId:olderProjectDraft.draftId},trigger);}}>{olderProjectDraft.label}</Button>}
        {m.role==='user'&&m.automaticLearningExcluded===true&&<p className="pw-muted">这条原话已排除自动记忆整理，后来开启也不会补提炼。它仍是对话原文；这不代表类似内容从未另行保存。{m.originalDefaultWritesBlocked===true&&' 原任务也不能更改六项默认偏好。'}</p>}
        {m.role==='assistant'&&traceTaskId&&can('tasks.open')&&<Button variant="ghost" leadingIcon={IconTool} aria-label={savedTrace||taskResult&&!phoneResult?'查看这条回复的执行轨迹':phoneResult?'查看这份手机任务的当前进展':'查看所属工作的当前进展'} onClick={()=>{
         const article=Array.from(chatMessageList.current?.children||[]).find(node=>(node as HTMLElement).dataset.messageId===id);
         const trigger=article?.querySelector<HTMLButtonElement>('button[aria-label="消息操作与来源"]');
         setMessageMenu(null);if(savedTrace)openTurnNotes(savedTrace,'process',m,trigger);else openTask(traceTaskId,'process');
        }}>{savedTrace?'这条回复的执行轨迹':taskResult&&!phoneResult?'执行轨迹':'所属工作当前进展'}</Button>}
        {reminderReply&&<Button variant="ghost" leadingIcon={IconClock} onClick={()=>{
          const current=bridge.getState(),resume=obj(current.view).resumeRevision;
          if(!Number.isSafeInteger(resume)||!m.id||!reminderReply.replyId)return;
          const article=Array.from(chatMessageList.current?.children||[]).find(node=>(node as HTMLElement).dataset.messageId===id);
          const visit:OriginalReminderChatVisit={source:{conversationId:String(current.state.conversationId||''),messageId:m.id,
           sourceRevision:Number(m.sourceRevision),taskId:reminderReply.taskId,runId:reminderReply.runId,epoch:reminderReply.epoch,replyId:reminderReply.replyId},
           documentEpoch:bridge.getDocumentEpoch(),navigation:navigationWindow.current,resume:Number(resume),
           returnFocus:article?.querySelector<HTMLButtonElement>('button[aria-label="消息操作与来源"]')};
          if(originalReminderChatCurrent(visit)){setMessageMenu(null);setOriginalReminderChat(visit)}
        }}>查看这条回复的提醒</Button>}
        {m.role==='assistant'&&completedFeedback&&linkedFeedback&&can('tasks.open')&&<Button variant="ghost" aria-label={`查看任务详情：${linkedFeedback.goal}`} onClick={()=>{setMessageMenu(null);openTask(linkedFeedback.id)}}>查看任务详情</Button>}
        {!!m.content&&<Button variant="ghost" leadingIcon={IconBook} onClick={()=>{const source=Array.from(scroll.current?.querySelectorAll<HTMLElement>('[data-message-id]')||[]).find(row=>row.dataset.messageId===id);openReading({kind:'message',title:m.role==='assistant'?'阅读回复':'阅读消息',text:m.content,quoteSource:canQuote&&!state.chatSubmitting&&draftKey&&m.id&&Number.isSafeInteger(m.sourceRevision)&&Number(m.sourceRevision)>0?{target:{messageId:m.id,sourceRevision:Number(m.sourceRevision),key:draftKey,epoch:draftEpoch},documentEpoch:bridge.getDocumentEpoch()}:undefined,returnFocus:source?.querySelector<HTMLElement>('button[aria-label="消息操作与来源"]')});setMessageMenu(null)}}>阅读与选字</Button>}
        {m.role!=='assistant'&&!!m.content&&<MessageActions onCopy={()=>copyWithLocalFeedback(m.content)}/>}
        {m.role==='user'&&row.lastOfRole&&m.id&&m.quoteAvailable&&Number(m.sourceRevision)>0&&!!m.content.trim()&&can('phone.draft.fromMessage')&&can('phone.prepare')&&<Button variant="ghost" leadingIcon={IconMonitor} disabled={phoneReviewPending||!!state.chatSubmitting||!!state.chatBusy||!!state.phoneSubmitting||isListening||phoneStart?.status==='unknown'} onClick={()=>stageMessageForPhone(m,['changed','unverified'].includes(phoneDraft.sourceState))}>{['changed','unverified'].includes(phoneDraft.sourceState)?'保留手机草稿，关联这条原话':'准备手机任务'}</Button>}

        {m.id&&replyContext?.replyToMessageIds.length&&can('message.origin')?<Button variant="ghost" leadingIcon={IconHistory} onClick={()=>{const source=Array.from(scroll.current?.querySelectorAll<HTMLElement>('[data-message-id]')||[]).find(row=>row.dataset.messageId===id);rememberChatPosition();setMessageOriginTarget({conversationId:String(state.conversationId),messageId:m.id!,sourceMessageIds:replyContext.replyToMessageIds,returnFocus:source?.querySelector<HTMLElement>('button[aria-label="消息操作与来源"]')});setMessageMenu(null)}}>查看对应原请求</Button>:null}
        {m.role==='user'&&m.submission?.taskId&&can('queued.open')&&!savedLocalScreenResult(m,String(state.conversationId||''))&&<Button variant="ghost" leadingIcon={IconClock} onClick={()=>{setMessageMenu(null);run('queued.open',{taskId:m.submission!.taskId,conversationId:state.conversationId})}}>原任务的输入队列</Button>}
        {canQuote&&<Button variant="ghost" leadingIcon={IconQuote} disabled={!draftKey||!!state.chatSubmitting} onClick={()=>{setMessageMenu(null);openQuote(m)}}>引用到草稿</Button>}
        {messageAttachmentDraft&&savedAttachmentChoices.map((file,index)=><Button key={file.fileId} variant="ghost" leadingIcon={IconFile} title={file.name}
         disabled={fileAttachmentPickerLaunch.current?.settled===false} onClick={()=>void chooseSavedMessageAttachment(m,file,messageAttachmentDraft)}>
         选作草稿附件{savedAttachmentChoices.length>1?` ${index+1}`:''} · {file.name}</Button>)}
        {m.retainedSources&&can('sources.open')&&<Button variant="ghost" leadingIcon={IconLink} onClick={()=>{setMessageMenu(null);openSource(m)}}>回答来源</Button>}
        {m.projectConventionRecorded===true&&m.id&&can('conversation.projectConvention.read')&&<Button variant="ghost" onClick={()=>{setMessageMenu(null);setProjectConventionMessage(m.id!)}}>核对本次项目约定</Button>}
        {!memoryShortcut&&m.memoryReference&&m.id&&can('taskMemory.answer')&&<Button variant="ghost" onClick={()=>{setMessageMenu(null);setProvidedMemoryMessage(m.id!)}}>核对本次记忆</Button>}
        {m.memoryReference&&<Button variant="ghost" onClick={()=>{setMessageMenu(null);openPersonalMemory({view:'memories'})}}>管理个人记忆</Button>}
        {m.role==='assistant'&&!phoneResult&&!taskResult&&m.taskId&&can('conversation.drafts')&&messageTaskAnchors.get(m.taskId)===index&&<ConversationDrafts key={m.taskId} taskId={m.taskId} reviewingMemory={memoryDraftTarget?.taskId===m.taskId} refreshRevision={Number(state.memoryRefreshRevision||0)} open={draft=>{setMessageMenu(null);openConversationDraft(draft,m)}}/>}
       </div></Popover>
      </PopoverTrigger>
     </div>}
     </MotionFeedback>

    </article>;
   })}</div>}

   {state.conversationId&&<div hidden={preparationInline}>{preparationEntries(String(state.conversationId))}</div>}
   {preparationInline&&preparationInlineAnchor===undefined&&preparationDetail}
   <ConversationWork messages={state.conversationReader||state.loadingFirstConversationMessages?undefined:messages} allowMessageTail={allowMessageTail} pinnedTask={chatAttention&&conversationWork.find(task=>task.id===chatAttention.id)} onTeamDecision={can('teams.attention')&&can('teams.readAttention')?openConversationTeamDecision:undefined} identity={String(state.conversationId||'')} onEmptyFocus={()=>workspace.current?.querySelector<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)')?.focus({preventScroll:true})} tasks={conversationWorkRows} canOpen={can('tasks.open')} onOpen={openConversationWorkRow}/>
  </div></div>
  {composerRegion}</main>;
 const openDirectoryWork=(id:string,entry:WorkEntry,query:string,run?:{runId:string;epoch:number},report?:ReportTarget)=>{
  const failed=()=>setLocalError('这份工作未能打开，已留在工作列表。');
  if(!report){openTask(id,entry,false,undefined,query,run,undefined,undefined,failed);return;}
  const doc=bridge.getDocumentEpoch(),navigation=navigationWindow.current,request=reviewRequest.current+1;
  const original=bridge.getState(),resume=obj(original.view).resumeRevision;
  const sameWindow=()=>bridge.getDocumentEpoch()===doc&&navigationWindow.current===navigation&&obj(bridge.getState().view).resumeRevision===resume;
  const directoryCurrent=()=>{
   const live=bridge.getState(),view=obj(live.view);
   return !document.hidden&&sameWindow()&&view.section===null&&(view.taskId===null||view.taskId===id)&&
    !live.state.filePath&&!live.state.conversationReader&&
    !live.state.queuedInputsPage&&live.state.attachmentWorkspace?.open!==true;
  };
  if(route!=='tasks'||!directoryCurrent()||obj(original.view).taskId!==null||!Number.isSafeInteger(resume)||
     !run||report.taskId!==id||report.runId!==run.runId||report.epoch!==run.epoch||!can('ownerWork.report.read')){failed();return;}
  // Back belongs to this directory row, not a previously dismissed search.
  searchOpenRequest.current++;setSearchReturn(false);
  homeReport.current=null;taskReading.current.delete(`${id}:homeReport`);
  // An old directory item earns no report read until the exact native Work
  // was opened and its complete current projection still names this report.
  const present=async()=>{
   const visit=acknowledgedWorkVisit.current;
   const ownsWork=()=>sameWindow()&&reviewRequest.current===request&&openedTaskId.current===id;
   const current=()=>ownsWork()&&directoryCurrent()&&!!visit&&acknowledgedWorkVisit.current===visit&&visit.taskId===id&&visit.request===request&&
    visit.documentEpoch===doc&&visit.resumeRevision===resume;
   if(!current())return;
   const sameReport=()=>{
    const live=bridge.getState();
    // State publication can follow the read-only open acknowledgement.
    if(obj(live.view).taskId!==id)return false;
    const matches=asArray<Task>(live.state.tasks).filter(row=>row.id===id);
    return matches.length===1&&sameHomeReport(completedDirectoryReport(matches[0],true),report);
   };
   setTaskOpening(true);
   try{
    if(!await awaitPickerProjection(current,()=>sameReport()?true:undefined)||!current()||!sameReport())return;
    const verified=await verifyHomeReport(report);
    if(current()&&sameReport()&&currentHomeReport(verified))homeReport.current=verified;
   }catch{/* The existing empty report layer keeps Back to this same directory row. */}
   finally{if(ownsWork())setTaskOpening(false);}
  };
  openTask(id,'homeReport',false,undefined,query,run,directoryCurrent,undefined,failed,()=>{void present();});
 };
 const tasksPage=<WorkRecoveryDirectory directory={taskDirectory} live={tasks} canContinue={task=>can(task.raw?.executionRole==='owner_work'?'ownerWork.control':'tasks.resume')} scroll={taskList} ready={ready} canOpen={can('tasks.open')} canReview={can('tasks.review')}
  scope={filter as WorkCollectionFilter} query={taskQuery} selectScope={selectWorkScope} clearQuery={()=>setTaskQuery('')} drillEnded={drillEndedWork}
  start={()=>{void navigate('butler')}} history={()=>{void openRelatedWorkspace('history')}} open={openDirectoryWork}
  registerReturn={registerWorkReturn} extra={can('remoteSessions.read')&&<Accordion variant="divided"><AccordionItem id="remote-sessions" title="电脑端托管会话"><RemoteManagedSessionsWorkspace/></AccordionItem></Accordion>}/>;
 const showProjectSection=(section:'work'|'files')=>{
  if(section==='work'&&projectPreview.expanded&&projectPreview.fromSavedWebShelf){projectPreview.returnToSavedWebShelf();return}
  projectPreview.hide();setProjectSections(previous=>({...previous,[String(state.projectId||'')]:section}))
 };
 const dismissProjectDraftReturn=(id:string)=>{setProjectDraftReturn(null);setProjectWorkPresentations(previous=>({...previous,[id]:{open:false,mode:'default'}}));};
 const openStagedProjectDraft=(target:ProjectDraftReturn)=>{
  const ticket=bridge.getDocumentEpoch(),navigation=navigationWindow.current;
  const original=()=>{const s=bridge.getState(),v=obj(s.view);return !document.hidden&&bridge.getDocumentEpoch()===ticket&&navigationWindow.current===navigation&&
   (v.section==='memory'||v.section==='memoryReview')&&!v.taskId&&s.state.projectId===target.projectId&&!s.state.filePath&&
   projects.some(p=>p.id===target.projectId)&&can('projects.development.read')&&target.expiresAt>Date.now();};
  // This is a narrow return, not a project selector: never leave another checkout, editor, plan or pending profile operation.
  if(!original()||projectPreview.expanded||projectWorkReviewOpen||projectWorkDrafts[target.projectId]?.operation){
   setLocalError('原项目或工作窗口已变化，未切换项目或覆盖编辑。可以回原项目手动核对草稿。');return;
  }
  void navigate('projects',()=>{setProjectDraftReturn(target);setProjectSections(previous=>({...previous,[target.projectId]:'work'}));
   setProjectWorkPresentations(previous=>({...previous,[target.projectId]:{open:true,mode:'default'}}));
  },{stillCurrent:original});
 };
 const openProjectFile=(path:string)=>{if(!can('projects.open'))return;setProjectSections(previous=>({...previous,[String(state.projectId||'')]:'files'}));updateProjectView({selected:path});run('projects.open',{projectId:state.projectId,path})};
 const projectListBusy=asArray<string>(state.moduleBusy).includes('workspace.list')||packageOpening;
 const projectListInput=obj(obj(state.moduleResultInputs)['workspace.list']);
 const projectListError=projectListInput.projectId===state.projectId?String(obj(state.moduleErrors)['workspace.list']||''):'';
 const canOpenImported=canEditImportedCheckout(can);
 const importedCopy=currentProject&&route==='projects'&&!previewBlocked&&!projectPreview.expanded&&!packageOpening&&!state.localSourceIntake?.window&&
  !projectImportedEditor&&!projectDraftReturn&&!projectBuildReturn&&!projectWorkDrafts[currentProject.id]?.operation&&canOpenImported?
  originalImportedEditor(currentProject.id):null;
 const importedPresentation=importedCopy?projectWorkPresentations[importedCopy.projectId]:null;
 // The current full native registry must identify only this original import.
 // Layout never selects a copy; the existing development window still verifies it.
 const importedCheckouts=importedCopy?asArray<Json>(state.features?.local?.checkouts).filter(row=>row?.projectId===importedCopy.projectId):[];
 const importedSourceFirst=!!importedCopy&&projectSection==='work'&&importedPresentation?.open===true&&importedPresentation.mode==='default'&&
  importedCheckouts.length===1&&importedCheckouts[0].id===importedCopy.checkoutId;
 const retainedVisit=retainedCopyVisit.current;
 const retainedCopyFirst=!!(currentProject&&retainedVisit&&route==='projects'&&projectSection==='work'&&!projectPreview.expanded&&!previewBlocked&&
  !taskOpen&&!projectImportedEditor&&!projectDraftReturn&&!projectBuildReturn&&!projectWorkReviewOpen&&can('projects.development.read')&&
  !projectWorkDrafts[currentProject.id]?.operation&&!projectWorkDrafts[currentProject.id]?.expanded&&projectWorkPresentations[currentProject.id]?.mode!=='profile'&&
  navigationRevision===retainedVisit.navigation&&personalDocumentEpoch===retainedVisit.documentEpoch&&
  retainedVisit.key===openedRetainedDraftCopyKey(snapshot));
 const importedWorkTarget=importedCopy&&projectSection==='work'&&!importedPresentation?.open&&
  !projectWorkDrafts[importedCopy.projectId]?.expanded?importedCopy:null;
 const importedFileTarget=importedCopy&&projectSection==='files'&&projectListInput.projectId===importedCopy.projectId&&
  !projectListBusy&&!projectListError&&!files.length&&!fileQuery?importedCopy:null;
 const firstProjectFilesCurrent=(live:ReturnType<typeof bridge.getState>,projectId:string)=>{
  const view=obj(live.view),results=obj(live.state.moduleResults),created=obj(results['workspace.create']),listed=obj(results['workspace.list']);
  return !document.hidden&&live.state.projectId===projectId&&!live.state.filePath&&view.section===null&&view.taskId===null&&
   !live.state.localSourceIntake?.window&&!live.state.projectGitImport?.open&&created.ok===true&&created.id===projectId&&
   obj(obj(live.state.moduleResultInputs)['workspace.list']).projectId===projectId&&listed.ok===true&&
   Array.isArray(listed.files)&&!listed.files.length&&Array.isArray(live.state.files)&&!live.state.files.length&&
   !asArray<string>(live.state.moduleBusy).includes('workspace.list')&&canEditImportedCheckout(action=>live.capabilities.commands.includes(action));
 };
 const emptyCreatedProject=currentProject&&route==='projects'&&
  !previewBlocked&&!projectPreview.expanded&&!projectListBusy&&!projectListError&&!projectImportedEditor&&!projectDraftReturn&&!projectBuildReturn&&
  !projectWorkDrafts[currentProject.id]?.operation&&!projectWorkDrafts[currentProject.id]?.expanded&&
  firstProjectFilesCurrent(bridge.getState(),currentProject.id)?currentProject.id:'';
 const firstLocalFilesProject=projectSection==='files'&&!fileQuery&&!importedFileTarget?emptyCreatedProject:'';
 const openLocalProjectFiles=(from:'files'|'work',originalCopy?:ProjectPreparedLocalCopy)=>{
  if(route!=='projects'||projectSection!==from||originalCopy&&from!=='work')return;
  const projectId=originalCopy?.projectId||(from==='work'?firstLocalFilesWorkProject:firstLocalFilesProject);
  const navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const resume=obj(bridge.getState().view).resumeRevision;
  const current=()=>{
   const live=bridge.getState();
   if(!projectId||navigationWindow.current!==navigation||bridge.getDocumentEpoch()!==documentEpoch||
    !Number.isSafeInteger(resume)||obj(live.view).resumeRevision!==resume)return false;
   if(originalCopy){
    const observed=originalPreparedLocalCopy(projectId);
    return observed?.checkoutId===originalCopy.checkoutId&&observed.requestId===originalCopy.requestId;
   }
   return firstProjectFilesCurrent(live,projectId)&&(from==='files'||live.capabilities.commands.includes('projects.development.prepare'));
  };
  if(!current())return;
  setProjectLocalFilesIntent({projectId,current,originalCopy});
  setProjectSections(previous=>({...previous,[projectId]:'work'}));
  setProjectWorkPresentations(previous=>({...previous,[projectId]:{open:true,mode:'default'}}));
 };
 const fixedTaskCopy=importedCopy?.sourceKind==='task_source_package';
 const openOriginalImportedFiles=(target:ProjectImportedEditor,from:'work'|'files')=>{
  const live=bridge.getState().state,list=obj(obj(live.moduleResultInputs)['workspace.list']);
  if(route!=='projects'||projectSection!==from||live.localSourceIntake?.window||
   !sameImportedEditor(target,from==='work'?importedWorkTarget:importedFileTarget)||
   !sameImportedEditor(target,originalImportedEditor(target.projectId))||
   from==='files'&&(list.projectId!==target.projectId||list._failed===true||!Array.isArray(live.files)||live.files.length||
    asArray<string>(live.moduleBusy).includes('workspace.list'))){
    setLocalError('原项目或导入副本状态已变化，没有打开其他文件。');return;
  }
  setProjectImportedEditor(target);
  setProjectSections(previous=>({...previous,[target.projectId]:'work'}));
  setProjectWorkPresentations(previous=>({...previous,[target.projectId]:{open:true,mode:'default'}}));
 };
 const starterFiles=importedFileTarget?.sourceKind==='builtin_vite_starter';
 const starterWork=importedWorkTarget?.sourceKind==='builtin_vite_starter';
 const projectFilesEmpty=<div className="pw-empty pw-project-empty"><IconFolder size={26} aria-hidden="true"/>
  <h3>{fileQuery?'没有匹配的文件':starterFiles?'网页源码已准备':importedFileTarget?fixedTaskCopy?'源码在新工作副本':'导入源码在本机副本':firstLocalFilesProject?'还没有正式文件':'暂无列出的文件'}</h3>
  <p>{fileQuery?'换个关键词，或清空搜索返回文件列表。':starterFiles?'在这份本机副本编辑网页，改完再构建。':importedFileTarget?fixedTaskCopy?
    '这里还没有正式文件。可编辑新副本中的源码，原固定包保持不变。':'这里还没有正式文件。编辑和后续本机构建都用这份导入副本。':
    firstLocalFilesProject?'要在手机上运行，请在工作副本里添加源码。新建正式文件不会改动已有副本。':'从一个新文件开始，也可以重新读取当前项目。'}</p>
  {fileQuery?<Button variant="secondary" onClick={()=>setFileQuery('')}>清空搜索</Button>:<div className="pw-action-row">
   {importedFileTarget&&<Button variant="primary" leadingIcon={IconFolder} onClick={()=>openOriginalImportedFiles(importedFileTarget,'files')}>{starterFiles?'编辑网页':fixedTaskCopy?'编辑新副本源码':'编辑导入的本机源码'}</Button>}
   {firstLocalFilesProject&&<Button variant="primary" leadingIcon={IconFolder} onClick={()=>openLocalProjectFiles('files')}>在本机副本里编辑</Button>}
   {can('projects.newFile')&&<Button variant={importedFileTarget||firstLocalFilesProject?'ghost':'primary'} leadingIcon={IconAdd} onClick={()=>openCreate('file')}>{importedFileTarget||firstLocalFilesProject?'新建正式文件':'新建文件'}</Button>}
   {can('projects.refresh')&&<Button variant="ghost" leadingIcon={IconRefresh} onClick={()=>run('projects.refresh')}>重新读取</Button>}
  </div>}
 </div>;
 useCollectionMotion(projectScroll,JSON.stringify(['projects',state.projectId]),JSON.stringify(projects.map(project=>[project.id,project.name])),{selector:'.pw-project-library-row',active:route==='projects'&&ready&&!state.filePath&&!projectPreview.expanded&&!packageOpening});
 const projectFiles=<>{(files.length>0||!!fileQuery)&&<><div className="pw-project-tools"><SearchField value={fileQuery} onChange={setFileQuery} aria-label="搜索当前项目文件" placeholder="搜索文件"/><PopoverTrigger isOpen={fileDisplayOpen} onOpenChange={setFileDisplayOpen}><IconButton icon={fileView==='tree'?IconFolder:IconGrid} aria-label="文件显示方式" title="文件显示方式"/><Popover aria-label="文件显示方式"><PopoverHeader title="文件显示"/><RadioOptions label="显示方式" value={fileView} onChange={value=>{setFileView(value as 'files'|'tree'|'grid');setFileDisplayOpen(false)}} options={[{id:'files',label:'文件列表'},{id:'tree',label:'目录层级'},{id:'grid',label:'网格'}]}/></Popover></PopoverTrigger>{can('projects.newFile')&&<IconButton icon={IconAdd} aria-label="新文件" title="新文件" onClick={()=>openCreate('file')}/>}</div>
      <div className="pw-project-list-caption"><MotionFeedback as="span" identity={`${projectListBusy}:${fileQuery}:${shownFiles.length}`} initial={false} role="status">{projectListBusy?'正在刷新 · ':''}{fileQuery?`匹配 ${shownFiles.length} 个文件`:`${files.length} 个文件`}</MotionFeedback>{fileQuery&&<Button variant="ghost" size="small" onClick={()=>setFileQuery('')}>清空搜索</Button>}{can('projects.refresh')&&<IconButton icon={IconRefresh} size="small" aria-label="重新读取项目文件" onClick={()=>run('projects.refresh')}/>}</div>
      </>}<div className="pw-project-objects" aria-busy={projectListBusy||undefined}>{!ready||packageOpening||projectListBusy&&!files.length?<p className="pw-muted" role="status">正在读取项目文件…</p>:projectListError?<div className="pw-empty pw-project-empty" role="alert"><h3>无法读取文件</h3><p>{projectListError}</p>{can('projects.refresh')&&<Button variant="secondary" leadingIcon={IconRefresh} onClick={()=>run('projects.refresh')}>重新读取</Button>}</div>:!shownFiles.length?projectFilesEmpty:fileView==='tree'?<TreeView aria-label="项目文件" key={state.projectId} className="pw-file-tree" data={treeData(shownFiles)} selectedItemId={projectView.selected||undefined} expandedIds={projectView.expanded} onExpandedChange={expanded=>updateProjectView({expanded})} onSelectChange={file=>{if(file&&!file.children)openProjectFile(file.id)}}/>:<FilesBrowser identity={JSON.stringify([state.projectId,fileQuery.trim().toLocaleLowerCase()])} files={shownFiles as any} showToolbar={false} view={fileView==='grid'?'Grid':'List'} onViewChange={view=>setFileView(view==='Grid'?'grid':'files')} onCopyPath={path=>{void copyText(path).catch(()=>{})}} selectedPath={projectView.selected} onOpen={file=>openProjectFile(file.path)}/>}</div>
      {currentProject&&route==='projects'&&projectSection==='files'&&!projectPreview.expanded&&!taskOpen&&can('projects.removedFiles.list')&&<ProjectRemovedFiles key={currentProject.id} project={currentProject.id}/>}</>;
 const canImportLocalProject=ready&&can('projects.localSource.open');
 const canCreateLocalStarter=canImportLocalProject&&can('projects.localSource.starter');
 const localProjectSourceLabel=canCreateLocalStarter?'新建小网页或导入':'从手机导入';
 const openLocalProjectSource=()=>{
  if(!canImportLocalProject||document.hidden)return;
  if(route==='projects'){run('projects.localSource.open');return;}
  if(route!=='butler')return;
  const origin=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const sameColdHome=()=>{
   const live=bridge.getState(),view=obj(live.view);
   return navigationWindow.current===origin&&bridge.getDocumentEpoch()===documentEpoch&&!document.hidden&&
    view.section==null&&!view.taskId&&!live.state.filePath&&!live.state.projectId&&
    Array.isArray(live.state.projects)&&live.state.projects.length===0&&live.capabilities.commands.includes('projects.localSource.open');
  };
  void navigate('projects',()=>run('projects.localSource.open'),{stillCurrent:sameColdHome});
 };
 const projectStartDraft=currentProject?projectWorkDrafts[currentProject.id]:undefined;
 const projectStartPresentation:ProjectWorkPresentation=(currentProject?projectWorkPresentations[currentProject.id]:undefined)??{
  open:!!(projectStartDraft?.operation||projectStartDraft?.expanded),
  mode:projectStartDraft?.operation||projectStartDraft?.expanded||!can('projects.development.read')?'profile':'default',
 };
 // The formal list proves nothing about the checkout. The existing Work page
 // still checks the saved choice and owns explicit copy preparation.
 const quietLocalFilesWork=currentProject&&route==='projects'&&projectSection==='work'&&!projectStartPresentation.open&&
  !previewBlocked&&!projectPreview.expanded&&!packageOpening&&!taskOpening&&!importedCopy&&!retainedCopyFirst&&
  !projectLocalFilesIntent&&!projectDraftReturn&&!projectBuildReturn&&!projectImportedEditor&&!projectStartDraft?.operation&&!projectStartDraft?.expanded&&
  projectWorkView.filter==='all'&&!projectWorkView.query.trim()&&projectDirectory.loaded&&!projectDirectory.busy&&!projectDirectory.error&&
  !projectDirectory.hasMore&&!projectDirectory.hasReadGaps&&!projectDirectory.tasks.length;
 const firstLocalFilesWorkProject=quietLocalFilesWork&&can('projects.development.prepare')?emptyCreatedProject:'';
 const preparedLocalFilesWork=quietLocalFilesWork&&!firstLocalFilesWorkProject?originalPreparedLocalCopy(currentProject.id):null;
 const pairedDeviceProjectCurrent=(native:ReturnType<typeof bridge.getState>,projectId:string,returning=false)=>{
  const view=obj(native.view),owner=native.state;
  return (view.section===null||returning&&view.section==='devices')&&view.taskId===null&&Number.isSafeInteger(view.resumeRevision)&&owner.projectId===projectId&&
   !owner.filePath&&!owner.fileLoading&&!owner.fileSaving&&!owner.conversationReader&&
   ['connections.enter','connections.leave','devices.snapshot','deviceTaskScope.prepare','tasks.open'].every(action=>native.capabilities.commands.includes(action));
 };
 const projectStartOperation=projectStartDraft?.operation;
 const canUsePairedDevice=!!currentProject&&ready&&route==='projects'&&projectSection==='work'&&
  !projectStartPresentation.open&&!projectPreview.expanded&&!previewBlocked&&!packageOpening&&
  (!projectStartOperation||projectStartOperation.admitted===true||projectStartOperation.final===true||projectStartOperation.status==='not_entered')&&
  ![projectDraftReturn,projectBuildReturn,projectImportedEditor,projectLocalFilesIntent].some(target=>target?.projectId===currentProject.id)&&
  // Keep the return focus target mounted while the existing device section closes. The click requires it closed.
  pairedDeviceProjectCurrent(snapshot,currentProject.id,true);
 const visitPairedDevices=(trigger:HTMLButtonElement)=>{
  if(!currentProject||!canUsePairedDevice)return;
  const projectId=currentProject.id,documentEpoch=bridge.getDocumentEpoch(),navigation=navigationWindow.current,resume=obj(snapshot.view).resumeRevision;
  const current=()=>{
   const native=bridge.getState();
   return !document.hidden&&documentEpoch===bridge.getDocumentEpoch()&&navigation===navigationWindow.current&&
    obj(native.view).resumeRevision===resume&&pairedDeviceProjectCurrent(native,projectId)&&
    trigger.isConnected&&!trigger.disabled&&!trigger.closest('[hidden],[inert]');
  };
  if(!current())return;
  if(projectScroll.current)projectDirectory.rememberScroll(projectScroll.current.scrollTop);
  // Existing Devices owns the paired choices and all later actions. The project id
  // is kept only in this UI return location, not sent in device requests.
  const origin:WorkspaceOrigin={...captureWorkspaceOrigin(),label:'返回原项目',goal:undefined,contextProjectId:undefined,
   pairedDevicesProject:true,focus:trigger,focusKey:'project-paired-device'};
  void navigate('devices',()=>{relatedOrigin.current=origin},{keepOrigin:true,stillCurrent:current});
 };
 const projectsPage=<motion.main layoutScroll ref={projectScroll} className={`pw-page pw-projects-page ${state.filePath?'pw-project-file-open':''}`} data-preview-open={projectPreview.expanded&&!state.filePath||undefined} onScroll={event=>{if(!state.filePath&&!projectPreview.expanded){if(state.projectId&&projectSection==='work')projectDirectory.rememberScroll(event.currentTarget.scrollTop);else projectListTop.current.set(projectPositionKey,event.currentTarget.scrollTop)}}}>
  {packageOpening&&<ProgressBar label="正在读取这次任务的固定包" isIndeterminate showValueLabel={false} size="sm"/>}
  {state.filePath?<ProjectFileEditor key={currentFileKey} path={state.filePath} version={Number(state.fileVersion||0)} draft={fileDraft} state={state} conflict={fileConflict} mode={fileMode} setMode={setFileMode} can={can} edit={editFile} save={saveFile} readHistory={()=>run('projects.history',fileIdentity)} readRevision={revision=>run('projects.revision',{...fileIdentity,revision})} retry={()=>run('projects.retry',{projectId:state.projectId,path:state.filePath})} copy={()=>{if(fileDraft)void copyText(fileDraft.text).catch(()=>{})}} copyRevision={value=>{void copyText(value).catch(()=>{})}} adoption={fileAdoption} continueAdopted={continueAdopted} registerBack={registerProjectFileBack}/>:currentProject?<section className="pw-project-work" aria-label="当前项目工作区">
    <Tabs selectedKey={projectPreview.expanded?'preview':projectSection} onSelectionChange={key=>{if(key==='preview')projectPreview.open();else showProjectSection(key==='files'?'files':'work')}} className="pw-project-tabs">
      <TabList aria-label="项目工作方式"><Tab id="work" icon={IconLayers}>工作</Tab><Tab id="files" icon={IconFolder}>文件</Tab><Tab id="preview" icon={IconPlay} isDisabled={!can('projects.preview')&&!projectPreview.expanded}>预览</Tab></TabList>
      <TabPanel id="work" shouldForceMount hidden={projectSection!=='work'||projectPreview.expanded}>
       <ProjectWorkspaceOverview project={currentProject} directory={projectDirectory} view={projectWorkView} onViewChange={updateProjectWorkView} directoryAvailable={can('work.tasks.list')} canOpenTask={can('tasks.open')} canReview={can('tasks.review')} canContinue={task=>can(task.raw?.executionRole==='owner_work'?'ownerWork.control':'tasks.resume')}
        onOpenTask={openProjectWork} onOpenTerminal={()=>void openRelatedWorkspace('local')} onOpenPairedDevices={canUsePairedDevice?visitPairedDevices:undefined} onAskButler={can('composer.object.add')?()=>askButlerAbout({kind:'project',id:currentProject.id}):undefined}
        editLocalFiles={importedWorkTarget?{onOpen:()=>openOriginalImportedFiles(importedWorkTarget,'work'),label:starterWork?'编辑网页':fixedTaskCopy?'编辑新副本源码':'编辑导入源码'}:
         firstLocalFilesWorkProject?{onOpen:()=>openLocalProjectFiles('work'),label:'编辑本机文件'}:
         preparedLocalFilesWork?{onOpen:()=>openLocalProjectFiles('work',preparedLocalFilesWork),label:'打开本机文件'}:undefined}
        localCopyFirst={importedSourceFirst||retainedCopyFirst}
        savedWebContent={can('projects.savedBuildWeb.read')&&<ProjectSavedBuildWeb key={currentProject.id} project={currentProject.id}
          active={ready&&route==='projects'&&projectSection==='work'&&!projectPreview.expanded&&!previewBlocked}
          canPreview={can('projects.package.preview.inspect')&&can('projects.package.preview.open')&&!packageOpening}
          openWork={can('tasks.open')?({taskId,checkoutId,runId,epoch},restoreShelf)=>{
           if(projectScroll.current&&!projectPreview.expanded)projectDirectory.rememberScroll(projectScroll.current.scrollTop);
           openProjectWork(taskId,'outcomes','',checkoutId,{runId,epoch},restoreShelf);
          }:undefined}
          show={(choices,current,visit)=>{
           const shown=projectPreview.showPackage(choices,undefined,current,visit);
           if(shown){
            if(projectScroll.current)projectDirectory.rememberScroll(projectScroll.current.scrollTop);
            packageReturn.current=null;
           }
           return shown;
          }}/>}
        startContent={can('projects.development.read')||can('projectWork.open')&&can('projectWork.confirm')?<ProjectWorkLauncher key={currentProject.id}
          active={route==='projects'&&!taskOpen&&!state.filePath&&projectSection==='work'&&!projectPreview.expanded&&!projectPickerOpen&&!newProjectOpen&&!newFileOpen&&!workOpen&&!commandOpen}
          resumeProfile={!!projectWorkDrafts[currentProject.id]?.operation}
          retainedDraftCopy={retainedCopyFirst}
          presentation={projectStartPresentation}
          onPresentationChange={presentation=>setProjectWorkPresentations(previous=>({...previous,[currentProject.id]:presentation}))}
          defaultStart={can('projects.development.read')?(active,onBusy)=><ProjectDevelopmentWorkspace key={currentProject.id}
            retainedOriginalVisit={retainedCopyFirst?state.openedRetainedDraftCopy:undefined} returnTarget={projectDraftReturn?.projectId===currentProject.id?projectDraftReturn:undefined}
            importedEditor={projectImportedEditor?.projectId===currentProject.id?projectImportedEditor:undefined} onImportedEditorHandled={handledImportedEditor}
            localFilesIntent={projectLocalFilesIntent?.projectId===currentProject.id?projectLocalFilesIntent:undefined} onLocalFilesIntentHandled={handledLocalFilesIntent}
            terminalSavedFile={terminalSavedFileArrival.current?.position.projectId===currentProject.id?terminalSavedFileArrival.current:undefined}
            buildReturn={projectBuildReturn?.projectId===currentProject.id?projectBuildReturn:undefined} onBuildReturnHandled={handledBuildReturn} onDismissReturn={()=>dismissProjectDraftReturn(currentProject.id)} projectId={currentProject.id} name={currentProject.name} active={active} run={act} can={can} registerBack={registerProjectDevelopmentBack} onInteractionBusyChange={onBusy} onOpenTask={openProjectReceipt} onOpenHostOriginal={id=>openTask(id)}
            onOpenPackageOriginal={({taskId,runId,epoch})=>{
             const projectId=currentProject.id,navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
             const atOriginal=()=>navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&
              !document.hidden&&bridge.getState().state.projectId===projectId&&!bridge.getState().state.filePath;
             if(!atOriginal()||taskOpen||route!=='projects'||projectSection!=='work'||projectPreview.expanded||!can('tasks.open'))return;
             // The receipt lives in the new project. The verified producing run belongs to the source project.
             const origin:ProjectTaskReturn={projectId,taskId,entry:'overview',focusOutcome:false,sourcePackageRun:{runId,epoch}};
             const request=++searchOpenRequest.current,current=()=>searchOpenRequest.current===request&&projectTaskReturn.current===origin&&atOriginal();
             projectTaskReturn.current=origin;
             openTask(taskId,'overview',false,undefined,'',origin.sourcePackageRun,current,undefined,()=>{
              if(!current())return;
              projectTaskReturn.current=null;
              setLocalError('保存这版源码的原工作已换轮或暂不可读取。未打开其他轮次，源码副本仍保留。');
             });
            }} onTaskRegistered={projectDirectory.reset} onPrepareTools={()=>void openSetup('tools')} onConnectModel={()=>void openRelatedWorkspace('model-settings')} onOpenTerminal={openProjectTerminal}/>:undefined}
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
      <TabPanel id="files">{can('projects.keptDrafts.list')&&<ProjectKeptDraftIndex key={currentProject.id} project={currentProject.id}/>} {projectFiles}</TabPanel>
      <TabPanel id="preview" className="pw-project-preview-panel" style={{translate:'none',opacity:1,transition:'none'}}>{desktopNavigation?<div className="pw-project-connected-workspace" data-preview-open="true"><motion.section layoutScroll className="pw-project-files-pane" aria-label="正式项目文件"><header className="pw-project-connected-header"><h2>正式项目文件</h2></header>{projectFiles}</motion.section><section className="pw-project-preview-panel" aria-label="项目预览"><ProjectPreviewPane control={projectPreview} composer={composer}/></section></div>:<ProjectPreviewPane control={projectPreview} composer={composer}/>}</TabPanel>
    </Tabs>

  </section>:projects.length>0?<section className="pw-project-library" aria-label="项目目录"><header><h2>你的项目</h2><div className="pw-project-library-actions">{can('projects.localSource.open')&&<Button variant="ghost" onClick={()=>run('projects.localSource.open')}>{localProjectSourceLabel}</Button>}{can('projects.gitImport.open')&&<Button variant="ghost" onClick={()=>run('projects.gitImport.open')}>从 Git 导入</Button>}{can('projects.create')&&<Button variant="secondary" leadingIcon={IconAdd} onClick={()=>openCreate('project')}>{canCreateLocalStarter?'新建空白项目':'新项目'}</Button>}</div></header><div>{projects.map(project=><Button key={project.id} variant="ghost" className="pw-project-library-row" data-motion-key={project.id} leadingIcon={IconFolder} trailingIcon={IconChevronRight} disabled={!can('projects.select')} onClick={()=>run('projects.select',{id:project.id})}>{project.name}</Button>)}</div></section>:!ready?<p className="pw-muted" role="status">正在读取项目…</p>:<div className="pw-empty pw-project-empty"><IconFolder size={26} aria-hidden="true"/>
   <h3>{canImportLocalProject&&!canCreateLocalStarter?'把已有工程带到这台手机':'从一个项目开始'}</h3>
   <p>{canCreateLocalStarter?'从内置小网页开始，或导入已有的 ZIP、文件夹。创建和编辑私人副本不必先准备开发工具或连接模型。':
    canImportLocalProject?'先从 ZIP 或文件夹核对源码，再建立私人副本。导入和编辑不需要先准备开发工具或连接模型。':'把相关任务、文件和成果放在一起，随时接着做。'}</p>
   <div className="pw-project-library-actions">
    {canImportLocalProject&&<Button onClick={openLocalProjectSource}>{canCreateLocalStarter?localProjectSourceLabel:'导入 ZIP 或文件夹'}</Button>}
    {can('projects.create')&&<Button variant={canImportLocalProject?'secondary':'primary'} leadingIcon={IconAdd} onClick={()=>openCreate('project')}>{canImportLocalProject?'新建空白项目':'新建项目'}</Button>}
    {can('projects.gitImport.open')&&<Button variant="ghost" onClick={()=>run('projects.gitImport.open')}>从 Git 导入</Button>}
   </div></div>}
  <ProjectLocalSourceIntake state={state.localSourceIntake} canCreateStarter={canCreateLocalStarter}/>
  <ProjectGitIntake state={state.projectGitImport} toolReturn={projectGitToolReturn} onPrepareTools={can('setup.runtime.read')?()=>{void openSetup('tools')}:undefined}/>
 </motion.main>;
 const readStoppedChatWork=()=>{
  if(!currentCancelFeedback?.canOpen||!can('tasks.open'))return;
  const original=currentCancelFeedback.original,key=chatCancelKey(original),navigation=navigationWindow.current,documentEpoch=bridge.getDocumentEpoch();
  const stillOriginal=()=>{
   const observed=chatStopObservation(bridge.getState().state,cancelFeedback);
   return !document.hidden&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&
    !!observed?.canOpen&&chatCancelKey(observed.original)===key;
  };
  if(!stillOriginal())return;
  openTask(original.taskId,'overview',false,undefined,undefined,{runId:original.runId,epoch:original.epoch},stillOriginal,undefined,()=>{
   if(!document.hidden&&navigationWindow.current===navigation&&bridge.getDocumentEpoch()===documentEpoch&&
      bridge.getState().state.conversationId===original.conversationId)
    setLocalError('这一轮原工作暂不可读取，没有打开其他运行。');
  });
 };
 const stopFeedback=currentCancelFeedback&&(isChatSurface||currentCancelIdentity)?<div className={currentCancelFeedback.error?'pw-banner pw-banner-error':'pw-banner'}>
  <p role={currentCancelFeedback.error?'alert':'status'}>{currentCancelFeedback.message}</p>
  {currentCancelFeedback.canOpen&&can('tasks.open')&&<Button variant="ghost" size="small" disabled={taskOpening} onClick={readStoppedChatWork}>查看这轮原工作</Button>}
  <IconButton icon={IconClose} aria-label="关闭原停止提示" onClick={()=>setCancelFeedback(previous=>{
   const next={...previous};delete next[chatCancelKey(currentCancelFeedback.original)];return next;
  })}/>
 </div>:null;
 const workspaceNotices=<>{route!=='chat'&&replyActivity}{stopFeedback}{(localError||state.error)&&<div className="pw-banner pw-banner-error" role="alert"><IconInfo size={17}/><p>{localError||state.error}</p>{localError&&<IconButton icon={IconClose} aria-label="关闭这条操作提示" onClick={()=>setLocalError('')}/>}</div>}{(localStatus||state.notice)&&<div className="pw-banner" role="status"><p>{localStatus||state.notice}</p>{savedLocalVisible&&<Button variant="ghost" size="small" aria-busy={savedLocalOpening||undefined} disabled={savedLocalOpening} onClick={()=>void viewSavedLocal()}>查看刚才保存的原话</Button>}{(localStatus||can('notice.dismiss'))&&<IconButton icon={IconClose} aria-label="关闭这条提示" onClick={()=>{if(savedLocalVisible)setSavedLocalReturn(null);setLocalStatus('');if(state.notice&&can('notice.dismiss'))run('notice.dismiss')}}/>}</div>}
  {searchReturn&&!state.attachmentWorkspace?.open&&<div className="pw-search-return"><Button variant="ghost" size="small" leadingIcon={IconArrowLeft} onClick={showWorkSearch}>返回搜索结果</Button></div>}</>;
 const archiveVisit=packageReturn.current, archiveChoice=projectPreview.choices;
 // A fixed webpage opened from Work is a child of that Work, not its list's parent.
 const returnToArchiveWork=route==='projects'&&!state.filePath&&projectPreview.expanded&&archiveChoice?.mode==='web_output'&&
  !projectPreview.fromSavedWebShelf&&!projectPreview.fromOriginalChat&&!!archiveVisit?.origin.task?.fixedArchive&&
  archiveVisit.target.taskId===archiveChoice.taskId&&archiveVisit.target.operationId===archiveChoice.operationId;
 return <ReadingContext.Provider value={openReading}><ConversationModelPickerProvider ref={modelPicker} state={state} can={can} act={act} disabled={!ready} active={isChatSurface} channelPhoneModel={channelPhoneModel} onChannelModel={()=>{void navigate('models')}} onManage={()=>{const origin={...captureWorkspaceOrigin(),modelPicker:true};void openRelatedWorkspace('models',origin)}} onOpenMedia={can('media.snapshot')?()=>{const origin={...captureWorkspaceOrigin(),modelPicker:true};void openRelatedWorkspace('media',origin)}:undefined}><Drawer.Root swipeToOpen={!desktopNavigation} open={workOpen&&!desktopNavigation} onOpenChange={changeNavigation} direction="left" autoFocus shouldScaleBackground={false}>
 <div className="pw-app pw-redesign" data-navigation={desktopNavigation?'sidebar':'drawer'} data-route={route} data-file-open={!!state.filePath||undefined} data-vaul-drawer-wrapper=""><Toaster theme={state.appearance?.dark===true?'dark':'light'}/>
  {desktopNavigation&&<aside className="pw-desktop-navigation pw-navigation-drawer pw-material-navigation" data-compact={effectiveCompact?'true':'false'} aria-label="侧边工作导航" onKeyDown={event=>{if(event.key!=='Escape'||event.nativeEvent.isComposing||event.nativeEvent.keyCode===229)return;if(navigationMoreOpen||commandOpen){event.preventDefault();if(navigationMoreOpen)setNavigationMoreOpen(false);else setCommandOpen(false)}}}>{navigationContent}</aside>}
  <div className="pw-main-workspace" ref={workspace}>
  {isPrimaryRoute?<NavigationChrome>
  {(route!=='chat'||desktopNavigation)&&<header className="pw-app-header">
   {route==='projects'&&projectPreview.expanded&&projectPreview.fromSavedWebShelf?<IconButton ref={navigationBackGlass} icon={IconArrowLeft}
    aria-label="返回已保存的网页" title="返回已保存的网页" disabled={!projectPreview.canReturnToSavedWebShelf} onClick={projectPreview.returnToSavedWebShelf}
   />:returnToArchiveWork?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="返回原工作"
    disabled={!!projectPreview.busy||projectPreview.exportBusy||projectPreview.blocked||!can('tasks.open')} onClick={projectPreview.returnToPackageTask}
   />:route==='tasks'&&endedScopeOrigin?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="返回工作总览" title="返回工作总览" onClick={restoreWorkScope}/>:route==='projects'&&state.filePath?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="返回项目文件" disabled={fileAdoption.pending||!!state.fileSaving} onClick={()=>{if(!fileAdoption.isPending()&&!bridge.getState().state.fileSaving&&!projectFileBack.current?.())run('projects.close',fileIdentity)}}/>:(route==='projects'||route==='tasks')&&relatedOrigin.current?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label={relatedOrigin.current.label} title={relatedOrigin.current.label} onClick={returnFromSecondary}/>:route==='projects'&&state.projectTaskReturn?.taskId?<IconButton ref={navigationBackGlass} icon={IconArrowLeft} aria-label="回到原任务" onClick={()=>openTask(state.projectTaskReturn.taskId)}/>:navigationTrigger}
   {route==='tasks'?<WorkFindTitle editing={taskFiltersOpen} query={taskQuery} onQuery={setTaskQuery} edit={()=>setTaskFiltersOpen(true)} close={closeWorkFind}/>:route==='chat'?<div className="pw-current-work pw-chat-title"><h1 title={currentTitle} aria-label={messages.length?currentTitle:'PocketWorld'}>{messages.length>0&&currentConversation&&can('conversation.organization.read')?<Button variant="ghost" className="pw-chat-title-trigger" aria-label="整理当前对话" onClick={()=>setOrganizationTarget(currentConversation.id)}><span className="pw-chat-title-text">{currentTitle}</span><IconChevronDown size={16} aria-hidden="true"/></Button>:messages.length?currentTitle:'PocketWorld'}</h1></div>:route==='projects'&&!state.filePath?<PopoverTrigger isOpen={projectPickerOpen} onOpenChange={setProjectPickerOpen}><Button variant="ghost" className="pw-object-title" aria-label="切换项目"><h1>{currentTitle}</h1><IconChevronDown size={16}/></Button><Popover aria-label="切换项目"><PopoverHeader title="项目"/><div className="pw-project-menu">{projects.map(project=><Button key={project.id} variant="ghost" aria-current={project.id===state.projectId?'true':undefined} onClick={()=>{setProjectPickerOpen(false);run('projects.select',{id:project.id})}}><IconFolder size={17}/><span>{project.name}</span>{project.id===state.projectId&&<small>当前</small>}</Button>)}{can('projects.localSource.open')&&<Button variant="ghost" onClick={()=>{setProjectPickerOpen(false);run('projects.localSource.open')}}>{localProjectSourceLabel}</Button>}{can('projects.create')&&<Button variant="secondary" leadingIcon={IconAdd} onClick={()=>{setProjectPickerOpen(false);openCreate('project')}}>{canCreateLocalStarter?'新建空白项目':'新项目'}</Button>}</div></Popover></PopoverTrigger>:<div className="pw-current-work"><BlurCrossfade identity={currentTitle}><h1>{currentTitle}</h1></BlurCrossfade></div>}
   {isChatSurface?<div ref={headerActionsGlass} className="pw-header-actions pw-navigation-glass" role="group" aria-label="对话操作">
    {can('remoteInbox.state')&&Number(state.remoteInbox?.unread||0)>0&&<IconButton icon={IconNotification} aria-label={'外部消息 · '+state.remoteInbox.unread+' 条未读'} title="外部未读消息" onClick={openUnreadMessages}/>}
    {!homeNewTopic&&can('conversation.new')&&<IconButton icon={IconNewChat} aria-label="聊件新事" title="聊件新事" disabled={!!state.chatSubmitting||draftSwitching} onClick={()=>chooseConversation(null)}/>}
   </div>:route==='tasks'?<div ref={headerActionsGlass} className="pw-primary-tools pw-navigation-glass" role="group" aria-label="工作目录操作">
    {!desktopNavigation&&(endedScopeOrigin||relatedOrigin.current)&&navigationTrigger}
    {!taskFiltersOpen&&can('tasks.refresh')&&<IconButton icon={IconRefresh} aria-label="刷新任务" onClick={()=>{taskDirectory.reset();run('tasks.refresh')}}/>}
    {taskFiltersOpen?<IconButton icon={IconClose} aria-label="收起搜索，保留关键词" onClick={closeWorkFind}/>:taskQuery?<IconButton icon={IconClose} aria-label="清除关键词" onClick={()=>{setTaskQuery('');requestAnimationFrame(()=>workspace.current?.querySelector<HTMLElement>('[data-work-search-entry]')?.focus({preventScroll:true}))}}/>:<IconButton icon={IconSearch} data-work-search-entry aria-label="搜索工作" onClick={()=>setTaskFiltersOpen(true)}/>}
   </div>:route==='projects'&&(returnToArchiveWork||state.filePath||state.projectTaskReturn?.taskId||relatedOrigin.current)?navigationTrigger:<span className="pw-header-spacer" aria-hidden="true"/>}
  </header>}
   {workspaceNotices}
   {route==='chat'&&<>{chatNavigation}{phoneActivity}{replyActivity}</>}
  </NavigationChrome>:workspaceNotices}
  {isPrimaryRoute?<div className="pw-workspace pw-route-workspace">{route==='butler'?<ButlerHome ready={ready} modelReady={!!composerModelName} localDisplayDraft={!!composer.primaryDisplayGate} onTryLocal={stageLocalDisplayExample} onReadLocalFiles={openHomeFiles} onImportProject={canImportLocalProject&&projects.length===0&&!state.projectId?openLocalProjectSource:undefined} stagingLocal={displayStagePending} modelSetup={modelSetupAction} firstHostSave={firstHostSave} composer={composerRegion} phoneActivity={phoneActivity} phoneAttention={homePhoneAttention} active={!taskOpen&&!workOpen&&!commandOpen&&!preparationTarget} moreWork={!!state.taskListNextCursor&&can('work.tasks.list')} preparations={rows=>preparationEntries('',rows)} hasPreparations={homeHasPreparations} tasks={butlerWorkRows} canOpen={can('tasks.open')} canReadReports={can('ownerWork.report.read')} onOpen={(task,alreadyOpen=false,report,stillOriginal,returnWorkFocus)=>{
    if(report){homeReport.current=report;homeWorkReturn.current=null;taskReading.current.delete(`${task.id}:homeReport`);
     openTask(task.id,'homeReport',alreadyOpen,undefined,undefined,undefined,()=>homeReport.current===report&&currentHomeReport(report),undefined,undefined,
      returnWorkFocus?()=>{homeWorkReturn.current={taskId:task.id,report,focus:returnWorkFocus}}:undefined);return}
    homeWorkReturn.current=alreadyOpen&&returnWorkFocus?{taskId:task.id,focus:returnWorkFocus}:null;
    openTask(task.id,attention(task)&&can('tasks.review')?'review':'overview',alreadyOpen,undefined,undefined,undefined,stillOriginal)
   }} onAllWork={(scope='all')=>{void openRelatedWorkspace('tasks',undefined,()=>{setEndedScopeOrigin(null);setFilter(scope);setTaskQuery('')})}} initialScrollTop={butlerScrollTop.current} onScrollTop={top=>{butlerScrollTop.current=top}}/>:route==='chat'?chatPage:route==='tasks'?tasksPage:projectsPage}</div>:route==='remoteInbox'&&can('remoteInbox.state')?<div className="pw-workspace pw-route-workspace"><RemoteInboxWorkspace memory={notificationMemory.current} onOpenTeamAttention={locator=>{setTeamAttentionFocus({...locator,requestId:crypto.randomUUID()});void openRelatedWorkspace('teams')}} onOpenTask={(id,review)=>openTask(id,review?'review':'overview')} state={state.remoteInbox} navigationTrigger={navigationTrigger} onBack={returnFromSecondary} registerBack={registerSecondaryBack}/></div>:route==='developer-logs'?<div className="pw-workspace pw-route-workspace"><DeveloperDiagnosticsWorkspace onBack={returnFromSecondary} navigationTrigger={navigationTrigger}/></div>:route==='setup'?<div className="pw-workspace pw-route-workspace"><SetupWorkspace key={`setup:${setupMode}:${setupCapability||''}:${setupRuntimeTask||''}`} snapshot={snapshot} mode={setupMode} initialCapability={setupCapability} initialView={setupView} onViewChange={setSetupView} returnLabel={setupOrigin.current.label} originGoal={setupOrigin.current.goal} runtimeTaskId={setupRuntimeTask} onNavigate={navigateFromSetup} onClose={closeSetup} registerBackHandler={registerSecondaryBack}/></div>:route==='profile'?<div className="pw-workspace pw-route-workspace"><ProfileWorkspace initialTarget={personalTarget} targetRequestKey={String(personalTargetKey)} onOpenAdvancedMemory={()=>{void navigate('advanced-memory')}} onOpenCommitments={()=>{void navigate('commitments')}} onOpenReminders={()=>{void navigate('automation')}} state={state} presentationKey={`${personalDocumentEpoch}:${Number(snapshot?.view?.resumeRevision||0)}:${String(snapshot?.view?.taskId||'')}:${String(snapshot?.view?.section||'')}`} supported={can} run={bridge.command} onBack={returnFromProfile} onSettings={()=>{void navigate('group:data')}} registerBack={registerSecondaryBack} registerBeforeLeave={registerProfileBeforeLeave}/></div>:<SecondaryRouteBoundary navigation={route+':'+navigationRevision} onBack={returnFromSecondary}><Suspense fallback={<SecondaryRoutePending onBack={returnFromSecondary}/>}><Secondary reminderNoticeVisit={reminderNotice} originalChatReminderRuleVisit={originalReminderPage} reviewedSkill={route==='skills'?relatedOrigin.current?.reviewedSkill:undefined} onChoosePhonePlace={choosePhonePlaceFromSettings} onReturnConnectedPhoneDraft={returnConnectedPhoneDraft} cloudGoal={route==='remote'&&cloudGoal?.navigation===navigationRevision?cloudGoal:undefined} onCloudGoalUsed={consumeCloudGoal} sentPhoneFocus={sentPhoneFocus?.navigation===navigationRevision?sentPhoneFocus:undefined} teamRunFocus={teamRunFocus} onTeamRunFocusConsumed={requestId=>setTeamRunFocus(current=>current?.requestId===requestId?undefined:current)} onAskButlerForTeam={askButlerForTeam} originalWorkDraft={route==='teams'&&relatedOrigin.current?.teamDraft?.navigation===navigationRevision} onOpenTeamOwnerWork={openTeamOwnerWork} capabilityMemory={capabilityMemory.current} onStartCapability={startCapability} onTeamAttentionConsumed={requestId=>setTeamAttentionFocus(current=>current?.requestId===requestId?undefined:current)} teamAttentionFocus={teamAttentionFocus} registerBeforeLeave={registerSecondaryBeforeLeave} route={route} terminalCheckout={originalTerminalCheckout} localDraft={localWorkspaceDraft} onLocalDraftChange={setLocalWorkspaceDraft} initialProjectId={setupReturnPending.current?setupOrigin.current.contextProjectId:relatedOrigin.current?.contextProjectId} originGoal={setupReturnPending.current?setupOrigin.current.goal:relatedOrigin.current?.goal} returnLabel={setupReturnPending.current?'返回能力准备':relatedOrigin.current?.label} onOpenProjectDraft={openStagedProjectDraft} onOpenProjects={()=>{if(relatedOrigin.current?.route==='projects')returnFromSecondary();else void openRelatedWorkspace('projects')}} onBack={returnFromSecondary} snapshot={snapshot} registerBackHandler={registerSecondaryBack} navigationTrigger={navigationTrigger} navigationRevision={navigationRevision} onNavigationPageChange={setSecondaryPage} onOpenSetup={can('setup.read')?(capability,origin)=>{void openSetup(capability,undefined,undefined,origin)}:undefined} settingsRestore={settingsRestore?.revision===navigationRevision?settingsRestore:undefined} onReturnToSetup={setupReturnPending.current?returnFromSecondary:undefined} onReturnOriginalModelInput={returnOriginalModelInput}/></Suspense></SecondaryRouteBoundary> }
  </div>{allWork}
  <ConversationHistoryWindow page={workRequestPage||(route==='chat'&&!historyPage?.workRequest?historyPage:null)} covered={taskOpen&&!workRequestPage} onReturnToChat={focusAfterHistoryClose} onWorkRequestReturn={restoreWorkRequestFocus}
    activeDraft={!workRequestPage&&intent==='chat'&&composer.ready===true&&draftKey&&state.conversationId&&composer.conversationId===state.conversationId?{conversation:state.conversationId,key:draftKey,epoch:draftEpoch}:undefined} submitting={!!state.chatSubmitting} can={can} act={act} registerBack={registerHistoryWindowBack} onCopy={copyWithLocalFeedback} onOpenTask={(id,entry,origin,alreadyOpen=false)=>openTask(id,entry,alreadyOpen,origin)}
   onOpenResultFiles={can('tasks.resultFiles')&&can('attachmentWorkspace.open')?openChatResult:undefined}/>
  <ConversationQueuedInputs page={state.queuedInputsPage||null} can={can} act={act} registerBack={registerQueuedInputsBack}/>
  {deliveredFileCurrent&&deliveredFileVisit&&<ConversationDeliveredFiles conversation={deliveredFileVisit.conversation}
   sources={deliveredFileVisit.sources} more={deliveredFileVisit.more} isCurrent={()=>deliveredFilesCurrent(deliveredFileVisit)}
   isSourceCurrent={source=>!!resultMessageIn(bridge.getState().state.messages,source.messageId,source.result,source.revision)}
   onClose={closeDeliveredFiles} onOpen={({source,file},target)=>openChatResult(source.messageId,source.result,target,undefined,
    {file,returnFocus:deliveredFileVisit.trigger,current:()=>deliveredFilesCurrent(deliveredFileVisit),opened:()=>closeDeliveredFiles(false,deliveredFileVisit)})}/>}
  {shownOriginalReminder&&<OriginalChatReminderReview key={shownOriginalReminder.source.messageId+':'+shownOriginalReminder.source.sourceRevision}
    source={shownOriginalReminder.source} isCurrent={()=>originalReminderChatCurrent(shownOriginalReminder)}
    onClose={()=>closeOriginalReminder(shownOriginalReminder)} onOpenSavedRule={pin=>openSavedOriginalReminder(shownOriginalReminder,pin)}
    registerBack={registerOriginalReminderBack}/>}
  {searchOriginal&&<WorkSearchOriginalReader target={searchOriginal} identity={`${bridge.getDocumentEpoch()}:${Number(snapshot?.view?.resumeRevision||0)}`} onClose={closeSearchOriginal} onConversation={()=>{const target=searchOriginal;void openSearchResult({...target,ownerLocator:undefined}).catch(error=>setLocalError(error instanceof Error?error.message:String(error)))}} onCopy={copyWithLocalFeedback}/>}
  <AttachmentWorkspace state={state} registerBack={registerAttachmentBack} onReturnFocus={restoreTaskFileFocus} openTaskId={taskOpen?taskId||undefined:undefined} onReturnToSearch={searchReturn?showWorkSearch:undefined}
   canReturnToOpenWork={continueFromWorkFiles}
   draftTarget={fileDraftTarget} resultTopic={fileResultTopic} keepResultReadingDraft={keepResultReadingDraft} onStageFile={can('composer.object.add')?stageFileInDraft:undefined} onOpenDraft={showFileDraft} onUseResult={useFileInOriginalTopic}
   onChooseResultAttachment={resultAttachmentAvailable?chooseFileInOriginalTopic:liveChatResultAttachmentAvailable?chooseLiveChatResultAttachment:
    historyChatResultAttachmentAvailable?chooseHistoryChatResultAttachment:undefined}
   onAdoptFileNote={fileResultTopic&&fileNoteTarget(snapshot,fileResultTopic)?(note,frame,close)=>adoptFileNote(fileResultTopic,note,frame,close):undefined}
   onVerifyReportFileWork={taskLayer==='homeReport'&&can('ownerWork.report.read')?verifyReportFileWork:undefined}
   canChooseStandaloneDocument={!!existingPrivateAttachmentDraft}
   onChooseAttachment={canChooseFileForDraft?startFileAttachmentPicker:undefined} readWorkTextVisit={readWorkTextVisit}/>
  {organizationTarget&&<ConversationOrganization key={organizationTarget} conversationId={organizationTarget} onClose={()=>setOrganizationTarget(null)}/>}
  <Drawer.Root open={taskOpen} onOpenChange={open=>{if(!open){if(taskMenuOpen){setTaskMenuOpen(false);return}if(!deviceScopeBack.current?.()&&!capabilityBack.current?.()&&!livePreviewBack.current?.()&&!taskTransientBack.current?.()&&!closeCurrentWorkSupplement()&&!leaveTeamReport())closeTask(true)}else setTaskOpen(true)}} direction="right" handleOnly autoFocus shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay pw-work-object-overlay"/><Drawer.Content className="pw-drawer pw-task-drawer pw-work-object-surface pw-task-manga" data-task-layer={taskLayer} aria-describedby="pw-task-description" onCloseAutoFocus={event=>{
   if(taskOpen)return;
   if(phoneWorkReportExit.current?.exited()){chatWorkReturn.current=null;event.preventDefault();return;}
   const chatOrigin=chatWorkReturn.current;chatWorkReturn.current=null;
   if(route==='chat'&&chatOrigin?.taskId===taskId&&chatOrigin.navigation===navigationWindow.current&&restoreChatWorkAt(chatOrigin)){event.preventDefault();return;}
   const visit=acknowledgedWorkVisit.current,sourceRow=visit?.chatRow;
   if(visit&&sourceRow&&visit.taskId===taskId){
    const request=visit.chatReturnRequest;delete visit.chatReturnRequest;delete visit.chatRow;
    if(request===reviewRequest.current&&route==='chat'&&!workOpen&&!commandOpen&&
       currentChatWorkRow(sourceRow,'return')&&!sourceRow.trigger.closest('[aria-hidden="true"]')&&
       (document.activeElement===document.body||document.activeElement===sourceRow.trigger)){
     event.preventDefault();sourceRow.trigger.focus({preventScroll:true});return;
    }
   }
   const homeOrigin=homeWorkReturn.current;homeWorkReturn.current=null;
   if(route==='butler'&&!workOpen&&!commandOpen&&!preparationTarget&&homeOrigin?.returning&&homeOrigin.focus()){event.preventDefault();return}
   if(route==='tasks'&&workListReturn.current?.()){event.preventDefault();return}
   const origin=projectTaskReturn.current;projectTaskReturn.current=null;
   if(!origin||route!=='projects'||state.projectId!==origin.projectId||state.filePath||projectSection!=='work'||projectPreview.expanded)return;
   if(origin.focusShelf){
    if(!origin.returning)return;
    event.preventDefault();
    const nav=navigationWindow.current,doc=bridge.getDocumentEpoch(),request=reviewRequest.current,focused=document.activeElement;
    const current=()=>!document.hidden&&nav===navigationWindow.current&&doc===bridge.getDocumentEpoch()&&request===reviewRequest.current&&
     bridge.getState().state.projectId===origin.projectId&&!bridge.getState().state.filePath;
    let unsubscribe=()=>{};
    const timer=window.setTimeout(()=>unsubscribe(),3000);
    const finish=()=>{window.clearTimeout(timer);unsubscribe()};
    const check=()=>{
     if(!current()){finish();return;}
     const view=obj(bridge.getState().view);
     if(view.taskId===origin.taskId)return; // The drawer exit can precede the native close projection.
     finish();
     if(!view.taskId&&!view.section)requestAnimationFrame(()=>{
      const now=obj(bridge.getState().view);
      if(current()&&!now.taskId&&!now.section&&(document.activeElement===focused||document.activeElement===document.body))origin.focusShelf?.();
     });
    };
    unsubscribe=bridge.subscribe(check);check();return;
   }
   const overview=projectScroll.current?.querySelector<HTMLElement>('.pw-project-overview');
   const row=Array.from(overview?.querySelectorAll<HTMLElement>('[data-project-task-id]')||[]).find(node=>node.dataset.projectTaskId===origin.taskId);
   const buttons=Array.from(row?.querySelectorAll<HTMLElement>('button:not(:disabled)')||[]);
   const receipt=Array.from(overview?.querySelectorAll<HTMLElement>('[data-project-receipt-task]')||[]).find(node=>node.dataset.projectReceiptTask===origin.taskId&&!node.closest('[hidden],[inert]'));
   const target=buttons.find(button=>button.dataset.workEntry===origin.entry)||buttons[0]||receipt||overview?.querySelector<HTMLElement>('input:not(:disabled),h2');
   if(target){event.preventDefault();if(target.tagName==='H2')target.tabIndex=-1;target.focus({preventScroll:true})}
  }} onEscapeKeyDown={event=>{if(taskMenuOpen){event.preventDefault();setTaskMenuOpen(false)}}}><header className="pw-drawer-header pw-task-header">
   <IconButton icon={IconArrowLeft} aria-label={taskLayer==='scope'?'返回工作目标':taskReconciliationOrigin.current?.openedId===taskId?'返回原工作':
     chatWorkReturn.current?.hostTaskId===taskId?'返回原话':taskLayer==='homeReport'&&route==='butler'?'返回管家':'返回原页面'} onClick={backFromTask}/>
   <div className="pw-task-header-copy"><Drawer.Title>{selectedTask?.goal||'工作'}</Drawer.Title><Drawer.Description id="pw-task-description" className="pw-sr-only">{taskLayer==='homeReport'?'原工作交付的只读汇报。返回后保留原页面和输入。':'这件工作的目标、成果、执行过程与当前决定。返回后保留原页面和输入。'}</Drawer.Description>
    {selectedPhoneSurface&&<p className="pw-field-hint pw-phone-surface-detail">{phoneSurfaceLabel(selectedPhoneSurface)}</p>}
    {selectedLocalPhone&&!selectedPhoneSurface&&selectedTask&&!terminal(selectedTask)&&<p className="pw-field-hint">原记录计划使用{(selectedTask.executionMode||selectedTask.raw?.executionMode)==='main_screen'?'主屏':'独立屏'}，当前未核实这次执行的手机连接。</p>}
    {selectedTask&&selectedWorkPresentation&&<div className="pw-task-header-status" data-task-status={selectedWorkPresentation.completed?'completed':selectedWorkPresentation.group==='attention'?'attention':selectedWorkPresentation.group==='running'?'running':selectedTask.status}>
     {selectedWorkPresentation.busy?<ProgressCircle aria-label={selectedWorkPresentation.label} isIndeterminate size="sm"/>:<span className="pw-task-state-dot" aria-hidden="true"/>}
     <span>{heldDependencyInOverview?'原执行不可续接':settledDependencyInOverview?'需要你处理':selectedWorkPresentation.label}</span>
    </div>}
   </div>
   {taskLayer!=='homeReport'&&<div className="pw-task-header-actions">
     {selectedTaskStop&&<IconButton icon={IconStop} aria-label={selectedTask?.status==='stopping'?'正在停止工作':'停止这件工作'} title={selectedTask?.status==='stopping'?'正在停止工作':'停止这件工作'} onClick={stopSelectedTask} disabled={selectedTaskUnavailable||selectedTask?.status==='stopping'||selectedPhoneSurface?.stopRequested||drawerStopPending||selectedWorkControl?.pending||selectedWorkControl?.unknown||selectedWorkControl?.usedForContinuation}
      data-phone-stop-task-id={selectedPhoneStop?.taskId} data-phone-stop-run-id={selectedPhoneStop?.runId} data-phone-stop-epoch={selectedPhoneStop?.epoch}/>}
    {selectedTask&&<WorkObjectMenu isOpen={taskMenuOpen} onOpenChange={setTaskMenuOpen} disabled={selectedTaskUnavailable} conversationPending={!!state.chatSubmitting||!!returningConversation} scopeDisabled={reviewPending||questionPending}
     onConversation={originatingRequestConversation(selectedTask)&&can('conversation.select')?()=>{void returnToTaskConversation(selectedTask)}:undefined}
     onProject={projects.some(project=>project.id===selectedTask.raw?.developmentContext?.projectId)&&can('projects.select')?()=>{void openTaskProject(selectedTask)}:undefined}
     onFiles={can('attachmentWorkspace.open')&&selectedTask.raw?.actorId==='owner'&&selectedTask.raw?.spaceId==='private'?()=>{
      const open=()=>{void openWorkFiles(selectedTask.id).catch(error=>setLocalError(error instanceof Error?error.message:String(error)));};
      if(!guardTaskLeave(open))open();
     }:undefined}
     onScope={can('tasks.review')?()=>{showTaskLayer('scope');if(!review||reviewStale)refreshReview()}:undefined}/>}
   </div>}
  </header>{drawerStop&&drawerStopCopy&&<div className="pw-task-control-feedback">
   <p role="status"><strong>{drawerStopCopy.title}</strong>{drawerStop.phase!=='checking'&&<>。{drawerStopCopy.note}</>}</p>
   {drawerStop.phase!=='checking'&&taskLayer!=='process'&&can('history.read')&&<Button variant="ghost" size="small" onClick={seeDrawerStopProcess}>到过程核对</Button>}
  </div>}{selectedTask&&taskLayer!=='homeReport'&&selectedWorkControl?.message&&<div className="pw-task-control-feedback" role="status"><p>{selectedWorkControl.message}</p>{selectedWorkControl.unknown&&can('tasks.open')&&<Button variant="ghost" size="small" onClick={()=>openTask(selectedTask.id,taskLayer)}>核对原任务状态</Button>}</div>}{taskLayer!=='homeReport'&&selectedTask&&<>{taskErrors[selectedTask.id]&&<div className="pw-banner pw-banner-error pw-task-local-error" data-task-id={selectedTask.id} role="alert"><IconInfo size={17}/><p>{taskErrors[selectedTask.id]}</p></div>}{state.taskActionErrors?.[selectedTask.id]&&state.taskActionErrors[selectedTask.id]!==taskErrors[selectedTask.id]&&<div className="pw-banner pw-banner-error pw-task-local-error" data-task-id={selectedTask.id} role="alert"><IconInfo size={17}/><p>{state.taskActionErrors[selectedTask.id]}</p></div>}</>}<div ref={taskScroll} className="pw-drawer-body pw-task-body" onScroll={rememberTaskPosition}>{taskLayer!=='homeReport'&&selectedTask&&state.originalTaskNotice?.taskId===selectedTask.id&&<section className="pw-selected-object" aria-label="原任务通知"><h3>{state.originalTaskNotice.content?.title||"原任务提醒"}</h3><p style={{whiteSpace:"pre-wrap"}}>{state.originalTaskNotice.content?.body||"通知正文请在原任务执行过程的 post_notice 记录中核对。"}</p><p className="pw-muted">{state.originalTaskNotice.expired?"这条提醒属于先前的执行状态。":""}{state.originalTaskNotice.content?.message||"通知接口回执不代表已经显示或阅读。"}</p><Button variant="ghost" size="small" onClick={()=>run("notice.task.close")}>收起提醒</Button></section>}{selectedTask?<>{taskReviewBody(selectedTask)}</>:taskOpening?<ThinkingLoader phase="正在读取原任务"/>:<section className="pw-empty-small"><p className="pw-error" role="alert">{taskLayer==='homeReport'?(route==='tasks'?'暂未核对到这份原汇报，请返回「我的工作」重试。':'原工作或汇报已变化，请返回管家首页核对。'):taskId&&taskErrors[taskId]||(expectedProjectRun?'原运行暂不可读取，没有用其他轮次代替。':'原任务暂不可读取，请重新读取原历史窗口。')}</p>{taskLayer!=='homeReport'&&!noticeBinding&&!expectedProjectRun&&taskId&&can('tasks.open')&&<Button variant="secondary" onClick={()=>openTask(taskId,taskLayer)}>重新读取原任务</Button>}</section>}</div>{selectedTask&&taskFooter(selectedTask)}</Drawer.Content></Drawer.Portal></Drawer.Root>
  <Drawer.Root open={!!phoneReview} onOpenChange={open=>{if(!open&&!phoneReviewPending)setPhoneReview(null)}} dismissible={!phoneReviewPending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-scope-drawer" aria-describedby="pw-phone-description" inert={!phoneReview} onCloseAutoFocus={finishPhoneReviewExit}><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>核对这次手机任务</Drawer.Title><Drawer.Description id="pw-phone-description">目标、执行位置与本次范围</Drawer.Description></div><IconButton icon={IconClose} aria-label="关闭手机任务核对" disabled={phoneReviewPending} onClick={()=>setPhoneReview(null)}/></header><div className="pw-drawer-body">{phoneDisplay&&<PhoneScopeReview key={phoneExit.generation} shown={phoneDisplay} live={phoneReview} pending={phoneReviewPending} unknown={phoneStart?.status==='unknown'} onClose={()=>{if(phoneReviewLive.current)setPhoneReview(null)}} onStart={can('phone.start')?startReviewedPhone:undefined} onCheck={can('phone.prepare')?preparePhone:undefined} onSettings={can('phone.review.settings')?openPhoneReviewSettings:undefined} onPlace={canVisitReviewedPlace?visitReviewedPhonePlace:undefined} error={phoneReviewError?.handle===phoneReview?.reviewHandle?phoneReviewError?.message:undefined}/>}{phoneStart&&phoneStart.status!=='accepted'&&<><Receipt receipt={phoneStart}/>{phoneStart.status==='unknown'&&(phoneStart.draftRequestId?blankReceiptActions(phoneStart):phoneStart.requestId&&phoneStart.conversationId&&can('phone.query')?<Button variant="secondary" disabled={phoneReviewPending} onClick={()=>{if(phoneReviewLive.current)queryOriginalPhone()}}>查询原手机请求</Button>:<Button variant="secondary" onClick={()=>{if(!phoneReviewLive.current)return;setPhoneReview(null);if(phoneStart.taskId)openTask(phoneStart.taskId);else navigate('tasks')}}>核对原任务记录</Button>)}</>}</div></Drawer.Content></Drawer.Portal></Drawer.Root>
  <SettingsDialog isOpen={newProjectOpen||newFileOpen} title={createKind==='project'?'新项目':'新文件'} onClose={closeCreate} closeLabel="关闭创建" className="pw-create-dialog">
   <form className="pw-create-form" onSubmit={event=>{event.preventDefault();if(!createBusy&&(createKind==='project'?projectName:newPath).trim())createObject()}}>
    <TextField label={createKind==='project'?'项目名称':'文件路径'} isDisabled={createBusy} value={createKind==='project'?projectName:newPath} onChange={createKind==='project'?setProjectName:setNewPath} placeholder={createKind==='project'?'输入项目名称':'例如 index.html'}/>
    <footer><Button type="button" variant="ghost" onClick={closeCreate}>取消</Button><Button type="submit" disabled={createBusy||!(createKind==='project'?projectName:newPath).trim()}>{createBusy?'正在创建…':'创建'}</Button></footer>
   </form>
  </SettingsDialog>
 <Drawer.Root open={!!voiceReview} onOpenChange={open=>{if(!open)setVoiceReview(null)}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-scope-drawer" aria-describedby="pw-voice-description" inert={!voiceReview} onCloseAutoFocus={voiceExit.onCloseAutoFocus}><div className="pw-drawer-handle"/><header className="pw-drawer-header"><div><Drawer.Title>核对听写文字</Drawer.Title><Drawer.Description id="pw-voice-description">采用后放入原输入，暂不发送</Drawer.Description></div><Drawer.Close asChild><IconButton icon={IconClose} aria-label="关闭听写核对"/></Drawer.Close></header><div className="pw-drawer-body"><TextAreaField label="原听写文字" value={voiceText} isReadOnly={!voiceReview} onChange={editVoiceReview}/></div><footer className="pw-drawer-actions"><Button variant="secondary" disabled={voicePending||!voiceReview} onClick={()=>submitVoiceReview('voice.discard')}>丢弃</Button><PressButton label="采用到原输入" disabled={voicePending||!voiceReview||!voiceText.trim()} onClick={()=>submitVoiceReview('voice.accept')}/></footer></Drawer.Content></Drawer.Portal></Drawer.Root>
 <SavedHostPicker open={route==='chat'&&!!savedHostChoice&&savedHostChoice.conversation===state.conversationId}
   profiles={savedHostProfiles(state.features?.remote?.profiles)} select={p=>void selectSavedHost(p)}
   configure={can('connections.enter')&&can('remote.saveProfile')?()=>void configureSavedHost():undefined}
   close={closeSavedHost} busy={savedHostBusy} error={savedHostError}/>
 <ComposerAttachmentPicker open={!!state.chatAttachmentPickerOpen&&!state.attachmentWorkspace?.open&&(filePickerVisible||reopenedPickerVisible||!fileAttachmentPicker&&!fileAttachmentPickerLaunch.current)}
   inventory={fileAttachmentPicker?(focusedInventoryReady?obj(state.moduleResults)['attachments.list']:undefined):reopenedPickerVisible?(reopenedInventoryReady?obj(state.moduleResults)['attachments.list']:undefined):state.features?.attachments}
   original={fileAttachmentPicker?{...fileAttachmentPicker.file,draftLabel:fileAttachmentPicker.draft.label}:undefined}
   selected={state.selectedChatAttachments} inputModel={state.chatAttachmentModel} importing={!!state.attachmentImporting}
   pagingDenied={isChatSurface&&intent==='chat'&&!taskOpen&&state.chatAttachmentPagingDenied===true}
   onReviewPagingRule={!reopenedPickerVisible&&can('attachments.closePicker')&&can('permissions.snapshot')&&can('permissions.save')?openAttachmentPagingRule:undefined}
   openingPagingRule={!!attachmentPermission} onClose={closeChatAttachmentPicker}
   onImport={!fileAttachmentPicker&&draftKey&&can('attachments.pick')?()=>run('attachments.pick',{key:draftKey,epoch:draftEpoch}):undefined}
   onToggle={draftKey&&can('attachments.toggle')?(fileAttachmentPicker?toggleFocusedFileAttachment:(id,sha256)=>{
    if(reopenedAttachmentPicker&&!reopenedInventoryCurrent(reopenedAttachmentPicker))return;
    run('attachments.toggle',{id,sha256,key:draftKey,epoch:draftEpoch});
   }):undefined}/>
 <Drawer.Root open={permissionOptionsOpen} onOpenChange={open=>{if(!open)closePermissionOptions()}} dismissible={!permissionPending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-permission-drawer" aria-describedby="pw-permission-description" onCloseAutoFocus={permissionExit.onCloseAutoFocus}>
  <div className="pw-drawer-handle"/>
  <header className="pw-drawer-header"><div><Drawer.Title>{attachmentPermissionOpen?'分段读取规则':'执行权限'}</Drawer.Title><Drawer.Description id="pw-permission-description" className={attachmentPermissionOpen?undefined:'sr-only'}>{attachmentPermissionOpen?'原输入和附件仍保留。保存规则不会发送消息。':'设置工具操作的审批方式，查看本次任务范围。'}</Drawer.Description></div><IconButton icon={attachmentPermissionOpen?IconArrowLeft:IconClose} aria-label={attachmentPermissionOpen?'返回原输入':'关闭执行权限'} onClick={closePermissionOptions} disabled={permissionPending}/></header>
  <div className="pw-drawer-body pw-permission-sheet-body">{permissionOptionsOpen&&<>
   {!attachmentPermissionOpen&&permissionView?.needsReview&&<div className="pw-permission-pending-review"><span role="status">{labelStatus(permissionView.status)}</span>{can('tasks.open')&&<Button variant="ghost" disabled={permissionPending} onClick={openPermissionTask}>{permissionReviewLabel}</Button>}</div>}
   {can('permissions.snapshot')?<PermissionPolicySettings key={`${state.conversationId||''}:${attachmentPermissionOpen?'attachment':'work'}`} conversationId={String(state.conversationId||'')} initialTool={attachmentPermissionOpen?attachmentPagingTool:undefined} act={act} onPendingChange={permissionSaving} registerBeforeLeave={registerPermissionBeforeLeave}>{!attachmentPermissionOpen&&permissionTaskDetails}</PermissionPolicySettings>:<><p className="pw-muted">当前版本暂不支持修改审批设置。</p>{!attachmentPermissionOpen&&permissionTaskDetails}</>}

  </>}</div>
 </Drawer.Content></Drawer.Portal></Drawer.Root>

 {routeReferenceTarget&&<RouteReference key={`${routeReferenceTarget.taskId}:${routeReferenceTarget.runId}:${routeReferenceTarget.epoch}`} target={routeReferenceTarget} onClose={()=>setRouteReferenceTarget(null)}/>}
 {skillDraftTarget&&<SkillDraftReview key={`${skillDraftTarget.runId}:${skillDraftTarget.operationId}`} target={skillDraftTarget} onClose={()=>setSkillDraftTarget(null)}
  onOpenSaved={skillDraftTarget.chatWork&&can('connections.enter')&&can('skills.get')?openReviewedSkill:undefined}/>}
 {!preparationInline&&preparationDetail}
 {isChatSurface&&newMemoryScope&&<NewConversationMemory key={draftKey+':'+draftEpoch} composer={composer} allowSingle={singleLearningScope} isOpen={conversationMemoryOpen} onClose={()=>setConversationMemoryOpen(false)} registerBack={registerConversationMemoryBack}/>}
 {conversationMemoryReadable&&<ConversationMemorySettings key={String(state.conversationId)} conversationId={String(state.conversationId)} controls={memoryControls} composer={singleLearningScope?composer:undefined} isOpen={conversationMemoryOpen} onClose={()=>setConversationMemoryOpen(false)} onChanged={setConversationMemoryControls} onManage={()=>openPersonalMemory({view:'memories'})} registerBack={registerConversationMemoryBack}/>}
 {memoryDraftTarget&&<MemoryDraftReview key={`${memoryDraftTarget.runId}:${memoryDraftTarget.operationId}`} target={memoryDraftTarget} originalWorkReturn={savedMemoryWorkReturn} onOpenMemory={target=>{setMemoryDraftTarget(null);openPersonalMemory({view:'memory',...target})}} onClose={()=>setMemoryDraftTarget(null)}/>}
 {state.quickPanelVoice&&<QuickPanelVoiceDraft key={state.quickPanelVoice.handoffId} handoff={state.quickPanelVoice} input={obj(state.voiceInput)} run={act}/>}
 {showPhoneNotice&&phoneNotification&&<PhoneNotificationReturn key={phoneNotification.token} notice={phoneNotification} busy={phoneNotification.checking}
  chatVisible={route==='chat'&&!taskOpen&&(intent==='chat'||!!state.conversationReader)} changed={tasks.some(t=>t.id===phoneNotification.taskId&&(t.raw?.runId!==phoneNotification.runId||t.raw?.coordinatorEpoch!==phoneNotification.epoch))} onClose={returnFromPhoneNotice} onSame={can('tasks.open')?()=>enterPhoneNotification(phoneNotification):undefined}
  onRefresh={can('phone.notice.refresh')?()=>{void bridge.command('phone.notice.refresh',{noticeHandle:phoneNotification.token}).catch(()=>{})}:undefined}/>}
 {messageOriginTarget&&('phoneResult' in messageOriginTarget?<PhoneReturnedReport key={messageOriginTarget.phoneResult.deliveryId} target={messageOriginTarget} onClose={()=>setMessageOriginTarget(null)}/>:<MessageOriginReader key={`${messageOriginTarget.conversationId}:${messageOriginTarget.messageId}`} target={messageOriginTarget} onClose={()=>setMessageOriginTarget(null)}/>)}
 {messageQuoteTarget&&<MessageQuote key={`${messageQuoteTarget.key}:${messageQuoteTarget.epoch}:${messageQuoteTarget.messageId}`} target={messageQuoteTarget} onClose={()=>setMessageQuoteTarget(null)}/>}
 {projectConventionMessage&&<ProjectConventionProvided key={projectConventionMessage} messageId={projectConventionMessage} onClose={()=>setProjectConventionMessage(null)}/>}
 {providedMemoryMessage&&<ProvidedMemories key={providedMemoryMessage} messageId={providedMemoryMessage} revision={Number(state.memoryRefreshRevision||0)} onClose={()=>setProvidedMemoryMessage(null)} onManage={original=>{setProvidedMemoryMessage(null);openPersonalMemory(original?{view:'memory',id:original.id,candidate:false,expectedRevision:original.revision}:{view:'memories'})}} registerBack={registerProvidedMemoryBack}/>}
 {sourceSelection&&<AnswerSources key={sourceSelection.messageId||sourceSelection.taskId} target={sourceSelection} revision={Number(state.memoryRefreshRevision||0)} onClose={()=>setSourceSelection(null)}/>}
 <ReadingView documents={readingDocuments} onBack={closeReading}/>
 </div></Drawer.Root></ConversationModelPickerProvider></ReadingContext.Provider>;
}
