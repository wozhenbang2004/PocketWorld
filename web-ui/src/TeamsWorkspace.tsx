import { useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { dequal } from 'dequal/lite';
import { bridge } from './bridge';
import { IconAdd, IconArrowLeft, IconChevronRight, IconEdit, IconExternalLink, IconPause, IconPlay, IconRefresh, IconMore, IconStop } from './icons';
import { Tabs, TabList, Tab, TabPanel, ContextMenuTrigger, ContextMenu, ContextMenuItem } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { AIAnswer, PressButton, ProgressBar } from './library';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { teamAnswerOutcome, teamDirectedSupplement, teamMessageConfirmed, teamMessageDelivery, teamMessageRecorded, teamSupplementReceipt, type AnswerDelivery, type TeamDirectedSupplement } from './team-message';
import { teamResearchRecords, teamResearchReplyExecution, type TeamResearchRecord } from './team-research';
import { teamPhoneChoice, teamPhoneDevice, teamPhoneScopeLabel, teamPhoneSelections, teamPhoneTargetKey, teamPhoneTargetLabel, type TeamPhoneChoice } from './team-phone';
import { TeamHarnessFields } from './TeamHarnessFields';
import { TeamProductionFields, TeamProductionReview } from './TeamProductionFields';
import { TeamEvidenceWorkspace } from './TeamEvidenceWorkspace';
import { TeamQuickStartForm } from './TeamQuickStartForm';
import { TeamStartReview } from './TeamStartReview';
import { teamPlannedModel } from './team-preparation';
import { canInspectFileContinuation, TeamFileContinuationReview } from './TeamFileContinuation';
import { TeamFailedWorkHandoff, FailedHandoffReview } from './TeamFailedWorkHandoff';
import { TeamParentRevisions,TeamParentRevisionReview } from './TeamParentRevisions';
import type {ProjectTeamReviewOrigin} from './project-team-review-origin';
import { TeamFrozenFiles, type FrozenMemberFile } from './TeamFrozenFiles';
import { resetTeamProductionAcknowledgments, teamDefinitionPayload, teamProductionDraft, teamProductionEditorMember } from './team-production';
import { TeamMemberAvatar, TeamMemberIdentity } from './TeamMemberIdentity';
import { teamAvatarShape, teamAvatarShapes, teamMemberLabel, teamMessageIdentity, teamMessageAuthor } from './team-identity';
import { classicAnswerOwner, exactClassicAnswer, teamResearchDeliveries, TeamResearchDeliveries, type TeamResearchDelivery } from './TeamResearchDeliveries';
import { TeamFollowUps, TeamResultHandoff } from './TeamResultHandoff';
import { finalPackage, TeamFinalPackageReading } from './TeamFinalPackageReading';
import {deliveryReading,TeamDeliveryWorkers} from './TeamDeliveryWorkers';
import { ownerPublishedOrdinaryTeamResult, passiveTeamDependency, stoppedTeamMemberCandidate, settledTeamResultEntry, teamFollowUpFocus, teamFollowUps, teamMemberProgress, teamMemberStopRequested, teamPartialReading, teamRemainingMember, teamResultReading, teamRunEntrySection, type AttentionFocus, type StoppedTeamMemberCandidate, type TeamFollowUp } from './team-result-reading';
import { ownerWorkPresentation } from './owner-work-presentation';
import { ReadingContext } from './reading-context';
import { PHONE_OUTPUT_NOT_RETURNED, teamCanPause, teamCanResume, teamGoalTitle, teamHasEnded, teamNeedsAttention, teamPhaseLabel, teamProgressLabel, teamRoleLabel, teamStatusLabel as status, teamTimeLabel } from './team-presentation';
import type { TeamAttentionLocator } from './NotificationTeamAttention';
import {sameTeamPackage} from './team-delivered-package';
import {isDraftTeamSelectionId,matchesWorkTeamMember,matchesWorkTeamResult,pendingTeamReportReview,sameTeamFiles,sameTeamProposal,type AcceptedWorkTeamReport,type DraftTeamSelection,type WorkProposalSource,type TeamButlerRequest,type TeamOwnerWorkIntent,type TeamOwnerWorkLink,type TeamRunFocus,type WorkFileSource,type WorkTeamResultReturn,type WorkTeamResultSource} from './team-navigation';
import { HarnessEvidence } from './HarnessEvidence';
import { harnessWorkerLabel, isTeamHarness } from './harness-capabilities';
import './team-continuity.css';
import './collaboration-depth.css';
import './team-navigation.css';
import './team-workbench.css';
import { FormDraftNotice, useFormDrafts, type RegisterBeforeLeave } from './form-drafts';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type PendingMessage = { id: string; memberId: string; runId: string; key: string; text: string; questionId: string; assignmentId: string };
type AnswerReceipt = Pick<PendingMessage, 'id' | 'runId' | 'memberId' | 'assignmentId' | 'questionId'> & { detail: string };
type TeamTab = 'runs' | 'teams';
type RunSection = 'progress' | 'result' | 'details';
type EditorPurpose = 'start' | 'definition';
type MessageInput = { recipient: string; replyId: string; mode: string };
type PendingStart = { teamId: string; since: number; goal: string };
type ResultMember = { id: string; generation: number; deliveryKey: string; round: number };
type ResultOrigin = { runId: string; key: string; scroll: number; member?: ResultMember };
type WorkMemberAudit = Pick<WorkTeamResultReturn,'source'|'rearm'> & {summary:string;resume:number;request:number;seen?:true;receipt?:true;returnHandle?:string};
type MemberDeliveryReturn = {team:string;key:string;section:'progress'|'result';taskId:string;sourceHandle:string;scroll:number;
  entered:boolean;ordinary:boolean;documentEpoch:number;member?:ResultMember;work?:WorkMemberAudit;record?:true};
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const label = (value: unknown): string => typeof value === 'string' ? value : '';
function memberAuditWindow(original:MemberDeliveryReturn,live:ReturnType<typeof bridge.getState>){
  const audit=original.work,view=object(live.view),owner=live.state;
  if(!audit||document.hidden||bridge.getDocumentEpoch()!==original.documentEpoch)return null;
  if(!audit.seen&&view.section==='teams'&&view.taskId===null&&view.resumeRevision===audit.resume&&owner.requestedTaskRevision===audit.request)return 'team';
  if(owner.requestedTaskRevision!==audit.request+1||owner.requestedTaskId!==original.taskId)return null;
  if(view.section===null&&view.taskId===original.taskId&&view.resumeRevision===audit.resume)return 'child';
  return audit.seen&&view.section==='teams'&&view.taskId===null&&view.resumeRevision===audit.resume+1?'returned':null;
}
type PlanWorkLocator = {teamRunId:string;generation:number;planId:string;workItemId:string;assignmentId:string;runId:string;epoch:number;
  memberId:string;itemRevision:number;ownerGeneration:number;targetProjectId:string;parentTaskId:string;parentRunId:string;parentEpoch:number};
type PlanWorkReturn = {source:PlanWorkLocator;sourceHandle:string;document:number;resume:number;request:number;seen?:true;receipt?:true};
function planWorkTask(live:ReturnType<typeof bridge.getState>, id:string) {
  const matches=rows(live.state.tasks).filter(row=>row.id===id);
  return matches.length===1?object(matches[0].raw):{};
}
function planWorkSource(run:Data,item:Data):Omit<PlanWorkLocator,'epoch'>|null {
  const plan=object(run.workPlan),scope=object(run.productionScope),shown=object(item.assignmentTask);
  const source={teamRunId:label(run.id),generation:run.generation,planId:label(plan.planId),workItemId:label(item.workItemId),
    assignmentId:label(item.assignmentId),runId:label(shown.runId),memberId:label(item.memberId),itemRevision:item.itemRevision,
    ownerGeneration:item.ownerGeneration,targetProjectId:label(plan.targetProjectId),parentTaskId:label(plan.parentTaskId),
    parentRunId:label(plan.parentRunId),parentEpoch:plan.parentEpoch};
  const members=rows(run.members).filter(row=>row.id===source.memberId),member=members[0];
  if(run.mode!=='produce'||run.archived===true||plan.format!=='pocketworld.team.work-items.v1'||plan.state!=='active'||plan.teamRunId!==source.teamRunId||
     plan.parentPlanRef!==source.planId||scope.parentPlanRef!==source.planId||scope.projectSelection!=null||
     scope.targetProjectId!==source.targetProjectId||scope.parentTaskId!==source.parentTaskId||scope.parentRunId!==source.parentRunId||
     members.length!==1||(member.runner??'model')!=='model'||member.device!=='phone'||member.exited===true||
     shown.taskId!==source.assignmentId||shown.assignmentId!==source.assignmentId||shown.memberId!==source.memberId||
     [source.teamRunId,source.planId,source.workItemId,source.assignmentId,source.runId,source.memberId,source.targetProjectId,
       source.parentTaskId,source.parentRunId].some(id=>!id)||
     !Number.isSafeInteger(source.generation)||source.generation<0||
     !Number.isSafeInteger(source.itemRevision)||source.itemRevision<1||!Number.isSafeInteger(source.ownerGeneration)||source.ownerGeneration<0||
     !Number.isSafeInteger(source.parentEpoch)||source.parentEpoch<1)return null;
  return source;
}
function planWorkLocator(shown:ReturnType<typeof planWorkSource>,live:ReturnType<typeof bridge.getState>):PlanWorkLocator|null {
  if(!shown)return null;
  const raw=planWorkTask(live,shown.assignmentId),origin=object(raw.origin),assignment=object(raw.teamAssignment);
  const source={...shown,epoch:raw.coordinatorEpoch};
  if(!Number.isSafeInteger(source.epoch)||source.epoch<1||
     origin.kind!=='team_assignment'||origin.teamRunId!==source.teamRunId||origin.assignmentId!==source.assignmentId||origin.memberId!==source.memberId||
     assignment.teamRunId!==source.teamRunId||assignment.assignmentId!==source.assignmentId||assignment.memberId!==source.memberId||
     assignment.targetProjectId!==source.targetProjectId||assignment.workItemId!==source.workItemId||assignment.itemRevision!==source.itemRevision||
     assignment.ownerGeneration!==source.ownerGeneration||assignment.remoteSelection!=null||assignment.projectSelection!=null||
     raw.id!==source.assignmentId||raw.runId!==source.runId||raw.parentTaskId!==source.teamRunId||raw.actorId!=='owner'||
     raw.spaceId!=='private'||raw.kind!=='assistant'||raw.executionMode!=='capabilities'||raw.teamProject!=null)return null;
  return source;
}
function planWorkWindow(place:PlanWorkReturn,live:ReturnType<typeof bridge.getState>) {
  const view=object(live.view), owner=live.state,source=place.source,task=planWorkTask(live,source.assignmentId);
  if(document.hidden||bridge.getDocumentEpoch()!==place.document||owner.conversationReader||
     task.runId!==source.runId||task.coordinatorEpoch!==source.epoch)return null;
  if(!place.seen&&view.section==='teams'&&view.taskId===null&&view.resumeRevision===place.resume&&owner.requestedTaskRevision===place.request)return 'team';
  if(owner.requestedTaskRevision!==place.request+1||owner.requestedTaskId!==source.assignmentId)return null;
  if(view.section===null&&view.taskId===source.assignmentId&&view.resumeRevision===place.resume)return 'child';
  return place.seen&&view.section==='teams'&&view.taskId===null&&view.resumeRevision===place.resume+1?'returned':null;
}
const questionKey = (value: Data): string => ['memberId', 'workItemId', 'assignmentId', 'questionId'].map(key => label(value[key])).join(':');

/** Use only the current original work item and its single retained native TXT contribution. */
function completedMemberFile(run:Data,member:Data,workItemId:string):FrozenMemberFile|undefined {
  const plan=object(run.workPlan),scope=object(run.productionScope);
  if(run.mode!=='produce'||run.status!=='awaiting_review'||run.phase!=='review'||run.archived===true||
     plan.format!=='pocketworld.team.work-items.v1'||plan.state!=='active'||plan.teamRunId!==run.id||
     !label(plan.planId)||plan.parentPlanRef!==plan.planId||scope.parentPlanRef!==plan.planId||scope.projectSelection!=null||
     !label(plan.targetProjectId)||scope.targetProjectId!==plan.targetProjectId||
     member.runner!=='model'||member.device!=='phone'||member.exited===true)return;
  const items=rows(plan.items).filter(row=>row.workItemId===workItemId);
  const item=items[0],child=object(item?.assignmentTask),refs=rows(item?.artifacts),ref=refs[0];
  if(items.length!==1||item.memberId!==member.id||item.status!=='completed'||!label(item.assignmentId)||
     child.taskId!==item.assignmentId||child.assignmentId!==item.assignmentId||child.memberId!==member.id||
     child.status!=='completed'||!label(child.runId)||refs.length!==1||ref.teamRunId!==run.id||
     ref.taskId!==child.taskId||ref.assignmentId!==item.assignmentId||ref.memberId!==member.id||
     ref.runId!==child.runId||ref.targetProjectId!==plan.targetProjectId||ref.artifactKind!=='text_file'||
     ref.mimeType!=='text/plain'||!/\.txt$/i.test(label(ref.path))||!label(ref.artifactId)||!/^[a-f0-9]{64}$/.test(label(ref.sha256)))return;
  return {artifactId:ref.artifactId,assignmentId:ref.assignmentId,memberId:ref.memberId,path:ref.path,sha256:ref.sha256,projectId:ref.targetProjectId};
}

function originalResearchQuestion(run: Data, item: TeamFollowUp): string {
  const at = item.locator;
  if (item.status !== 'awaiting_user' || at.childKind !== 'research' || !at.taskId || at.questionId || at.assignmentId ||
      at.workItemId || at.runId !== run.id || at.generation !== run.generation) return '';
  const members = rows(run.members).filter(row => row?.id === at.memberId), member = members[0];
  if (members.length !== 1 || member.exited || member.paused || member.status !== 'awaiting_user' || member.round !== at.round ||
      member.generation !== at.memberGeneration || (member.identityRevision ?? 1) !== at.identityRevision) return '';
  const stages = rows(member.roundStates).filter(row => row?.round === at.round), stage = stages[0];
  if (stages.length !== 1 || stage.status !== 'awaiting_user' || stage.researchTaskId !== at.taskId ||
      stage.generation !== at.memberGeneration || !label(stage.turnId)) return '';
  const child = object(stage.researchTask), message = label(child.message);
  return child.originalOwnerQuestionPending === true && child.status === 'awaiting_user' && child.inspectOnly === false &&
    child.executionAuthorized === false && child.taskId === at.taskId && child.teamRunId === at.runId &&
    child.memberId === at.memberId && child.round === at.round && child.executionTurnId === stage.turnId &&
    child.runGeneration === at.generation && child.memberGeneration === at.memberGeneration && child.identityRevision === at.identityRevision &&
    !!label(child.runId).trim() && Number.isSafeInteger(child.coordinatorEpoch) && child.coordinatorEpoch > 0 &&
    message.trim() && message.length <= 4000 ? message : '';
}

function currentOriginalTeamParent(reading: Data | null): TeamOwnerWorkLink | null {
  const link = reading?.ownerWorkLink;
  if (!link || link.readOnly !== true || link.currentRun !== true || link.stopping !== false ||
      !label(link.teamTaskId) || link.teamTaskId !== reading?.taskId || link.teamRunId !== reading?.run?.id ||
      !label(link.taskId) || !label(link.runId) || !label(link.conversationId) || !Number.isSafeInteger(link.epoch) || link.epoch < 1) return null;
  return link;
}

function acceptingOriginalParent(reading: Data | null, tasks: unknown): boolean {
  const link = currentOriginalTeamParent(reading);
  if (!link) return false;
  const parents = rows(tasks).filter(row => row?.id === link.taskId), parent = object(parents[0]?.raw);
  return parents.length === 1 && parent.id === link.taskId && parent.actorId === 'owner' && parent.spaceId === 'private' &&
    parent.executionRole === 'owner_work' && parent.cancelRequested !== true && parent.contextAvailable === true &&
    parent.runId === link.runId && parent.coordinatorEpoch === link.epoch && parent.conversationId === link.conversationId &&
    parent.controls?.canInput === true;
}
const newerTeamReading = (run: Data, live: Data) => live.id === run.id && (live.generation > run.generation ||
  live.generation === run.generation && live.revision > run.revision);
const originalHotCompletedTeam = (catalog:Data, id:string) => {
  // An archived read returns its original body without the catalogue's archived flag.
  if(!Array.isArray(catalog.history)||rows(catalog.history).some(row=>row.id===id))return false;
  const matches=rows(catalog.runs).filter(row=>row.id===id), run=matches[0], roster=rows(run?.members);
  return matches.length===1&&run.archived!==true&&run.status==='completed'&&run.mode==='collaborate'&&!!label(run.teamId)&&
    run.definitionKind==null&&(run.discussionFormat??'classic')==='classic'&&!run.discussion&&!run.workPlan&&!run.productionScope&&
    !run.nativeWorkProfileModels&&run.researchExecutionFormat==='pocketworld.team-research-agent.v1'&&roster.length>0&&roster.length<=4&&
    roster.every(slot=>(slot.runner||'model')==='model'&&(!slot.device||slot.device==='phone'));
};
const originalWorkTeamResult = (reading:Data|null, source:WorkTeamResultSource) => {
  if(!classicAnswerOwner(reading)||reading?.run.status!=='completed'||!Array.isArray(reading.attention)||reading.attention.length)return;
  if(!matchesWorkTeamResult(source,reading.ownerWorkLink))return;
  const live=object(object(bridge.getState().state.teamRuns)[source.teamRunId]);
  if(newerTeamReading(reading.run,live))return;
  const result=teamResultReading(reading.run,reading.messages);return result.original?result.messageId:undefined;
};
// A one-off roster has no saved definition. Only the current native answer
// reading may anchor this Result visit; the saved-roster child audit stays separate.
const reportedTemporaryWorkIdentity = (reading:Data|null,source:WorkTeamResultSource) => {
  if(reading?.currentCompletedTemporaryAnswersSource!==true||!classicAnswerOwner(reading,true)||
     !matchesWorkTeamResult(source,reading.ownerWorkLink)||!Array.isArray(reading.attention)||reading.attention.length)return;
  const run=reading.run,roster=rows(run.members);
  if(run.definitionKind!=='one_time'||run.operationId!==source.teamRunId||run.summaryStale!==false||
     !Number.isSafeInteger(run.generation)||run.generation<0||!Number.isSafeInteger(run.revision)||run.revision<1||
     roster.length!==2||roster[0]?.id===roster[1]?.id||
     !roster.every(slot=>label(slot?.id)&&slot.runner==='model'&&slot.device==='phone'&&
       slot.status==='completed'&&slot.exited!==true&&slot.paused!==true&&Number.isSafeInteger(slot.generation)&&slot.generation>=0))return;
  const live=object(object(bridge.getState().state.teamRuns)[source.teamRunId]);
  if(newerTeamReading(run,live))return;
  const result=teamResultReading(run,reading.messages);
  if(result.original&&result.messageId)
    return JSON.stringify([run.generation,run.revision,result.messageId,roster.map(slot=>[slot.id,slot.generation,slot.identityRevision??1])]);
};
const acceptedSummaryUnavailable='未能核对验收时保存的这份原汇总。没有打开其他版本。';
async function verifiedAcceptedSummary(reading:Data|undefined,source:WorkTeamResultSource,report:AcceptedWorkTeamReport):Promise<boolean>{
  if(!reading?.ownerWorkLink||!globalThis.crypto?.subtle||!matchesWorkTeamResult(source,reading.ownerWorkLink)||
     reading.kind!=='run'||!reading.viewHandle||reading.taskId!==source.taskId||reading.run?.id!==source.teamRunId||
     reading.run.status!=='completed'||reading.run.phase!=='completed'||reading.run.summaryStale!==false||
     reading.run.generation!==report.generation||reading.run.revision!==report.revision)return false;
  const original=teamResultReading(reading.run,reading.messages);
  if(!original.original||original.messageId!==report.messageId||original.body.length>report.bytes)return false;
  const body=new TextEncoder().encode(original.body);
  if(body.byteLength!==report.bytes)return false;
  const digest=await crypto.subtle.digest('SHA-256',body);
  return Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join('')===report.sha256;
}
const blankMember = (id: string, index: number) => teamProductionEditorMember({ id, name: `成员 ${index + 1}`, role: '', model: '', runner: 'model' });
const starterMembers = (template: string): Data[] => [
  { ...blankMember('member-0', 0), name: template === 'options' ? '方案设计' : '资料研究', role: template === 'options' ? '围绕共同目标提出不同方案，说明依据与限制。' : '围绕共同目标搜集和整理信息，标明来源与不确定性。' },
  { ...blankMember('member-1', 1), name: template === 'options' ? '评估与建议' : '核对与整合', role: template === 'options' ? '比较方案的成本、收益与风险，整理可执行建议。' : '核对信息与推理，指出矛盾和缺口，整合结论与下一步。' },
];
const safeTeamDraft = (editor: Data): Data => ({ name: editor.name, ...(typeof editor.nameEdited === 'boolean' ? { nameEdited: editor.nameEdited } : {}), mode: editor.mode, leaderId: editor.leaderId, goal: editor.goal || '', rounds: editor.rounds || '1', ...(editor.pendingStartTeamId ? { pendingStartTeamId: editor.pendingStartTeamId, pendingStartSince: editor.pendingStartSince } : {}), members: rows(editor.members).map(member => ({ ...Object.fromEntries(['id', 'name', 'role', 'model', 'runner', 'modeId', 'avatarShape', 'assignment', 'dependsOn', 'device', 'writeFiles'].filter(key => member[key] !== undefined).map(key => [key, member[key]])), production: Object.fromEntries(['sourceMode', 'sourceTaskId', 'snapshotId', 'packageId', 'packageSha', 'packageManifest', 'wallSeconds', 'outputBytes', 'contributionBytes'].filter(key => member.production?.[key] !== undefined).map(key => [key, member.production[key]])) })) });
const pendingStart = (draft: Data): PendingStart | null => typeof draft.pendingStartTeamId === 'string' && typeof draft.pendingStartSince === 'number'
  ? { teamId: draft.pendingStartTeamId, since: draft.pendingStartSince, goal: label(draft.goal) } : null;
const matchesPendingStart = (run: Data, pending: PendingStart): boolean => run.teamId === pending.teamId
  && typeof run.createdAt === 'number' && run.createdAt >= pending.since;
function modelTeamKind(definition: Data | null) {
  if (!definition || !['collaborate', 'debate'].includes(definition.mode)) return null;
  const members = rows(definition.members);
  if (!members.length || !members.every(member => (!member.runner || member.runner === 'model') && (!member.device || member.device === 'phone'))) return null;
  return definition.mode === 'collaborate' && definition.definitionKind == null && definition.archived !== true &&
    (definition.discussionFormat == null || definition.discussionFormat === 'classic') ? 'ordinary' : 'other';
}
const readingKey = (value: Data | null, tab: TeamTab) => value?.kind === 'run' ? `run:${value.run.id}`
  : value?.kind === 'definition' ? `definition:${value.definition.id}` : `list:${tab}`;
const memberModelLabel = (member: Data): string => {
  const selection = object(member.memberModelSelection), route = object(selection.memberRoute);
  if (label(route.requestedAlias)) return route.requestedAlias;
  const target = object(selection.memberTarget || member.modelTarget);
  if (target.kind === 'exact') return label(target.modelId);
  if (target.kind === 'group') return '模型组';
  return selection.memberTarget || member.modelTarget ? '' : label(member.model);
};

/** Uses the adopted BoardUI fields/dialogs and Affect answer presentation on the original team worksite. */
export function TeamsWorkspace({ state, run, registerTransientBack, registerBeforeLeave, alreadyShownError = '', attentionFocus, onAttentionConsumed, runFocus, onRunFocusConsumed, onAskButler, originalWorkDraft, onOpenWork, primaryTeamsRoute = false }: {
  state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void;
  registerBeforeLeave?: RegisterBeforeLeave; alreadyShownError?: string; attentionFocus?: Data | null;
  onAttentionConsumed?: (requestId: string) => void;
  runFocus?: TeamRunFocus | null; onRunFocusConsumed?: (requestId: string) => void;
  onAskButler?: (request: TeamButlerRequest) => void;
  originalWorkDraft?: boolean | DraftTeamSelection;
  onOpenWork?: (link: TeamOwnerWorkLink, intent: TeamOwnerWorkIntent, savedTeamId?: string) => void;
  primaryTeamsRoute?: boolean;
}) {
  const replacingDraftTeam=typeof originalWorkDraft==='object'?originalWorkDraft:undefined;
  const openReading = useContext(ReadingContext);
  const formDraft = useFormDrafts(run, 'teams');
  const startDraft = useFormDrafts(run, 'teams');
  const messageDraft = useFormDrafts(run, 'teams');
  const memberFormDraft = useFormDrafts(run, 'teams');
  const messageDraftId = useRef('');
  const submittingAnswer = useRef(false); // Reserve both choices before the asynchronous draft write.
  const unverifiedSavedContinuation = useRef(new Set<string>());
  const [answerReceipt, setAnswerReceipt] = useState<AnswerReceipt | null>(null);
  const [draftCatalogChecked, setDraftCatalogChecked] = useState(false), [messageChecked, setMessageChecked] = useState(false);
  const startDraftId = useRef('');
  const [catalog, setCatalog] = useState<Data>({}), [tab, setTab] = useState<TeamTab>(originalWorkDraft ? 'teams' : 'runs');
  const [catalogReady, setCatalogReady] = useState(false), [editorExpanded, setEditorExpanded] = useState<string[]>([]);
  const [queries, setQueries] = useState<Record<TeamTab, string>>({ runs: '', teams: '' });
  const query = queries[tab], setQuery = (value: string) => setQueries(old => ({ ...old, [tab]: value }));
  const [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [editor, setEditor] = useState<Data | null>(null);
  const [editorPurpose, setEditorPurpose] = useState<EditorPurpose>('start');
  const memberEditorFocus = useRef<{ id: string; read: Data; documentEpoch: number } | null>(null);
  const [quickStartPending, setQuickStartPending] = useState<PendingStart | null>(null), [savedStartPending, setSavedStartPending] = useState<PendingStart | null>(null);
  const [memberDraft, setMemberDraft] = useState<Data | null>(null);
  const pendingMemberChange = useRef<string | null>(null);
  const [phoneSelection, setPhoneSelection] = useState<Data | null>(null), [phoneChoices, setPhoneChoices] = useState<Record<string, TeamPhoneChoice>>({});
  const [externalReady, setExternalReady] = useState<Record<string, boolean>>({}), [memberProbes, setMemberProbes] = useState<Record<string, Data>>({});
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [acknowledged, setAcknowledged] = useState(false);
  const [goal, setGoal] = useState(''), [rounds, setRounds] = useState('1'), [recipient, setRecipient] = useState(''), [replyId, setReplyId] = useState(''), [mode, setMode] = useState('discussion');
  const [composeOpen, setComposeOpen] = useState(false);
  const [runSections, setRunSections] = useState<Record<string, RunSection>>({});
  const [detailSections, setDetailSections] = useState<Record<string, string[]>>({});
  const [memberFilters, setMemberFilters] = useState<Record<string, string>>({});
  const [drafts, setDrafts] = useState<Record<string, string>>({}), [uncertainByRun, setUncertainByRun] = useState<Record<string, PendingMessage>>({});
  const [expandedMembers, setExpandedMembers] = useState<Record<string, string[]>>({}), [expandedItems, setExpandedItems] = useState<Record<string, string[]>>({});
  const uncertain = uncertainByRun[String(reading?.run?.id || '')] || null;
  const rememberUncertain = (id: string, value: PendingMessage | null) => setUncertainByRun(old => {
    const next = { ...old }; if (value) next[id] = value; else delete next[id]; return next;
  });
  const alive = useRef(true), requestGeneration = useRef(0), entered = useRef(false), currentReading = useRef<Data | null>(null);
  const acceptedSummary = useRef<{handle:string;current:()=>boolean}|null>(null);
  const backgrounded = useRef(document.visibilityState !== 'visible'), currentReview = useRef<Data | null>(null);
  const currentPhoneSelection = useRef<Data | null>(null);
  const frozenFilesBack = useRef<(() => boolean) | null>(null);
  const [readingFiles,setReadingFiles] = useState(false);
  const [memberFileEntry,setMemberFileEntry]=useState<{requestId:string;viewHandle:string;documentEpoch:number;file:FrozenMemberFile}|null>(null);
  const materialBack = useRef<(() => boolean) | null>(null);
  const materialBeforeLeave = useRef<((leave: () => void) => void) | null>(null);
  useEffect(() => { registerBeforeLeave?.(leave => formDraft.guard(() => startDraft.guard(() => messageDraft.guard(() => memberFormDraft.guard(() => { if (materialBeforeLeave.current) materialBeforeLeave.current(leave); else leave(); }))))); return () => registerBeforeLeave?.(null); }, [registerBeforeLeave, formDraft.guard, startDraft.guard, messageDraft.guard, memberFormDraft.guard]);
  const [materialBusy, setMaterialBusy] = useState(false);
  const [resultSource, setResultSource] = useState<ResultOrigin | null>(null);
  const [shownMemberSources,setShownMemberSources]=useState<Record<string,boolean>>({});
  const [shownArchives,setShownArchives]=useState<Record<string,boolean>>({});
  const [openingMemberResult, setOpeningMemberResult] = useState(false);
  const classicAnswerFlight=useRef(false);
  const deliveryReturn=useRef<MemberDeliveryReturn|null>(null);
  const planWorkReturn=useRef<PlanWorkReturn|null>(null);
  const [allResultActions, setAllResultActions] = useState<Record<string, boolean>>({});
  const resultBack = useRef<() => boolean>(() => false);
  const handledAttention = useRef('');
  const handledRunFocus = useRef('');
  const workResultReturn = useRef<WorkTeamResultReturn|null>(null);
  const retireWorkResult = () => { workResultReturn.current?.retire(); workResultReturn.current=null; };
  const [fileEntry,setFileEntry]=useState<{view:string;requestId:string;source:WorkFileSource|ProjectTeamReviewOrigin;returnToWork?:(reason?:string)=>void}|null>(null);
  const [proposalEntry,setProposalEntry]=useState<{source:WorkProposalSource;returnToWork?:(reason?:string)=>void}|null>(null);
  const [workPackage,setWorkPackage]=useState<{view:string;returnToWork:(reason?:string)=>void}|null>(null);
  const focusedPackage=workPackage&&workPackage.view===reading?.viewHandle?workPackage:null;
  const focusedProposal=proposalEntry&&reading?.run?.id===proposalEntry.source.teamRunId?proposalEntry:null;
  const proposalRead=(runId:string)=>({runId,...(focusedProposal?.source.teamRunId===runId?{proposalOrigin:focusedProposal.source}:{})});
  const revisionRows=rows(reading?.revisionOffers).filter(row=>!focusedProposal||row.canReview===true&&sameTeamProposal(focusedProposal.source.proposal,row));
  const workspaceActive = state.webWorkspaceSection === 'teams' && !state.webWorkspaceTaskOpen;
  const workspaceWindow = useRef({ active: false, revision: Number(state.webWorkspaceResumeRevision || 0) });
  const ownsNativeWindow = () => {
    const view = object(bridge.getState().view);
    return view.section === 'teams' && !view.taskId;
  };
  const root = useRef<HTMLElement>(null), positions = useRef(new Map<string, number>());
  const listSelection = useRef<Partial<Record<TeamTab, string>>>({});
  const messageInputs = useRef(new Map<string, MessageInput>()), startInputs = useRef(new Map<string, { goal: string; rounds: string }>());
  const currentInput = useRef({ recipient, replyId, mode, goal, rounds });
  const viewKey = readingKey(reading, tab), shownView = useRef(viewKey);
  useSurfaceEnter(root, viewKey, { selector: ':scope > .pw-team-reading, :scope > .pw-team-tabs, :scope > .pw-collaboration-directory, :scope > .pw-team-empty', depth: reading?.kind === 'run' ? 2 : reading ? 1 : 0, active: workspaceActive });
  currentReading.current = reading; currentReview.current = review; currentPhoneSelection.current = phoneSelection;
  currentInput.current = { recipient, replyId, mode, goal, rounds };
  const rememberView = () => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) positions.current.set(shownView.current, scroll.scrollTop);
    const previous = currentReading.current;
    const input = currentInput.current;
    if (previous?.kind === 'run') messageInputs.current.set(previous.run.id, { recipient: input.recipient, replyId: input.replyId, mode: input.mode });
    else if (previous?.kind === 'definition') startInputs.current.set(previous.definition.id, { goal: input.goal, rounds: input.rounds });
  };
  useLayoutEffect(() => {
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (!scroll) return;
    const previous = shownView.current, position = positions.current.get(viewKey) || 0;
    shownView.current = viewKey;
    const restore = () => { scroll.scrollTop = position; };
    restore();
    if (previous !== viewKey) {
      const target = reading ? root.current?.querySelector<HTMLElement>('.pw-team-reading h2')
        : !previous.startsWith('list:') ? Array.from(root.current?.querySelectorAll<HTMLButtonElement>('[data-team-list-id]') || [])
          .find(node => node.dataset.teamListId === listSelection.current[tab]) : undefined;
      target?.focus({ preventScroll: true });
    }
    const frame = requestAnimationFrame(restore);
    const remember = () => positions.current.set(viewKey, scroll.scrollTop);
    scroll.addEventListener('scroll', remember, { passive: true });
    return () => { cancelAnimationFrame(frame); scroll.removeEventListener('scroll', remember); };
  }, [viewKey]);
  type FocusStage=NonNullable<TeamRunFocus['fromWorkMember']>;
  const checkStageFocus=(reading:Data,focus:FocusStage,live=bridge.getState())=>{
    const {source,selected}=focus;
    const parents=rows(live.state.tasks).filter(row=>row.id===source.parentTaskId);
    const raw=parents.length===1?parents[0].raw:undefined;
    if(!raw||raw.runId!==source.parentRunId||raw.coordinatorEpoch!==source.parentEpoch||
       raw.conversationId!==source.conversationId||raw.contextAvailable===false||raw.contextWithdrawn===true||
       raw.sourceCurrentReadRequired===true||raw.cancelRequested===true)return false;
    const latest=object(object(live.state.teamRuns)[source.teamRunId]);
    // A routine progress update can advance the revision while the same member
    // is still making the same turn. Never substitute a different member or turn.
    return matchesWorkTeamMember(source,selected,reading.ownerWorkLink,reading.run)&&
      (!newerTeamReading(reading.run,latest)||matchesWorkTeamMember(source,selected,reading.ownerWorkLink,latest));
  };
  const decisionTarget=useRef<{handle:string;focus:AttentionFocus|{stage:FocusStage}}|null>(null);
  const showDecisionTarget=(id:string,handle:string,focus:AttentionFocus)=>{
    decisionTarget.current={handle,focus};
    setRunSections(old=>({...old,[id]:focus.kind==='question'||focus.kind==='workItem'?'details':'progress'}));
    if(focus.kind==='changed'){setNotice(focus.message||'原待办已变化，请查看原协作。');return}
    setMemberFilters(old=>({...old,[id]:''}));
    if(focus.kind==='question')setDetailSections(old=>({...old,[id]:[...new Set([...(old[id]||[]),'conversation'])]}));
    if(focus.kind==='workItem')setExpandedItems(old=>({...old,[id]:[...new Set([...(old[id]||[]),focus.workItemId!])]}));
    if(focus.kind==='member')setExpandedMembers(old=>({...old,[id]:[...new Set([...(old[id]||[]),focus.memberId!])]}));
  };
  // Resolve after the exact reading and its disclosure have committed. A first run
  // visit also schedules ordinary scroll restoration above; it must not override this target.
  useLayoutEffect(()=>{
    const pending=decisionTarget.current;
    if(!pending||pending.handle!==reading?.viewHandle||!workspaceActive)return;
    const frame=requestAnimationFrame(()=>{
      if(decisionTarget.current!==pending||currentReading.current?.viewHandle!==pending.handle||!ownsNativeWindow()||document.hidden)return;
      decisionTarget.current=null;
      const focus=pending.focus;
      if('stage' in focus){
        const {selected}=focus.stage,shown=currentReading.current;
        if(shownRunSection.current!=='progress'||!shown||!checkStageFocus(shown,focus.stage))return;
        const nodes=root.current?.querySelectorAll<HTMLElement>(
          `[data-team-member-id="${CSS.escape(selected.memberId)}"] [data-team-progress-round="${selected.round}"]`);
        const row=nodes?.length===1?nodes[0]:undefined;
        if(row&&!row.closest('[hidden],[inert],[aria-hidden="true"]')){
          row.focus({preventScroll:true});row.scrollIntoView({block:'center'});
        }
        return;
      }
      const selector=focus.kind==='question'?`[data-message-id="${CSS.escape(focus.messageId!)}"]`
        :focus.kind==='workItem'?`[data-work-item-id="${CSS.escape(focus.workItemId!)}"]`
        :focus.kind==='member'?`[data-team-member-id="${CSS.escape(focus.memberId!)}"]`:'.pw-team-run h2';
      const target=root.current?.querySelector<HTMLElement>(selector), child=focus.taskId?target?.querySelector<HTMLElement>(`[data-team-source-task="${CSS.escape(focus.taskId)}"]`):null;
      if(!target)setNotice('原事项在当前阅读范围尚未显示，没有定位其他分工。');
      else if(focus.taskId&&!child)setNotice('已定位原分工，这份子任务当前没有独立入口。没有转到其他轮次。');
      const control=child||(focus.kind==='member'?target?.querySelector<HTMLElement>('[aria-controls^="team-member-detail-"]'):focus.kind==='workItem'?target?.querySelector<HTMLElement>('button[aria-expanded]'):target);
      control?.focus({preventScroll:true});(control||target)?.scrollIntoView({block:'center'});
    });
    return ()=>cancelAnimationFrame(frame);
  },[reading?.viewHandle,workspaceActive]);
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void, rejected?: (failure: unknown) => void, beforeAccept?: (result:Data)=>Promise<boolean>) => {
    if (entered.current || backgrounded.current || !ownsNativeWindow()) return; entered.current = true; const generation = ++requestGeneration.current; setBusy(true); setError(''); setNotice('');
    try {
      const result = object(await run(action, args));
      const current=()=>alive.current&&generation===requestGeneration.current;
      if((beforeAccept?await beforeAccept(result):current())&&current())accept?.(result);
      return result;
    }
    catch (failure) { if (alive.current && generation === requestGeneration.current) { setError(failure instanceof Error ? failure.message : String(failure)); rejected?.(failure); } }
    finally { if (generation === requestGeneration.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const release = (value: Data | null, kind = 'viewHandle') => {
    // Native leaveConnection already retires these handles. Cleanup is not a
    // user operation and must not overwrite a successful read or an action error.
    if (value?.[kind] && ownsNativeWindow()) void bridge.command('teams.closeReading', { [kind]: value[kind] }).catch(() => {});
  };
  const acceptReading = (next: Data) => {
    if(acceptedSummary.current?.handle!==next.viewHandle)acceptedSummary.current=null;
    rememberView();
    if (next.kind !== 'run' || next.run?.id !== currentReading.current?.run?.id) { setMemberDraft(null); setResultSource(null); }
    release(currentReading.current); setReading(next);
    if (next.kind === 'run' && pendingMemberChange.current === next.run?.id) {
      pendingMemberChange.current = null; setMemberDraft(null);
      setNotice('已重新读取当前成员状态，请核对刚才的改动；没有重发旧操作。');
    }
    if (next.kind === 'run' && next.run?.id) listSelection.current.runs = next.run.id;
    if (next.kind === 'definition' && next.definition?.id) listSelection.current.teams = next.definition.id;
    if (next.kind === 'run' && next.run?.id) setCatalog(old => ({ ...old, runs: [next.run, ...rows(old.runs).filter(value => value.id !== next.run.id)] }));
    if (next.kind === 'definition' && next.definition?.id) setCatalog(old => ({ ...old, teams: [next.definition, ...rows(old.teams).filter(value => value.id !== next.definition.id)] }));
    if (next.kind === 'run' && next.run?.id !== currentReading.current?.run?.id) {
      const saved = messageInputs.current.get(next.run.id);
      setComposeOpen(!!saved?.replyId); setRecipient(saved?.recipient ?? next.run.leaderId ?? ''); setReplyId(saved?.replyId || ''); setMode(saved?.mode || 'discussion');
    } else if (!replacingDraftTeam && next.kind === 'definition' && next.definition?.id !== currentReading.current?.definition?.id) {
      const saved = startInputs.current.get(next.definition.id);
      startDraftId.current = ''; setSavedStartPending(null);
      void startDraft.open('owner', 'start:' + next.definition.id, String(next.definition.revision || ''), { goal: saved?.goal || '', rounds: saved?.rounds || '1' }, !!saved?.goal).then(value => { if (value && alive.current && currentReading.current?.definition?.id === next.definition.id) { startDraftId.current = next.definition.id; setGoal(value.goal); setRounds(value.rounds); setSavedStartPending(pendingStart(value)); } });
      setGoal(saved?.goal || ''); setRounds(saved?.rounds || '1');
    }
    const pending = uncertainByRun[String(next.run?.id || '')];
    if (pending && teamMessageRecorded(next, pending)) {
      setDrafts(old => old[pending.key] === pending.text ? { ...old, [pending.key]: '' } : old); rememberUncertain(pending.runId, null); setNotice('已从原运行记录核对消息已保存，消费状态见原记录。'); if (messageDraftId.current === pending.key) messageDraft.discard(() => { void messageDraft.open('owner', 'message:' + pending.key, pending.key, { text: '' }); });
    }
  };
  useEffect(() => {
    if (!composeOpen) return;
    const frame = requestAnimationFrame(() => root.current?.querySelector<HTMLTextAreaElement>('.pw-team-compose textarea:not(:read-only):not(:disabled)')?.focus());
    return () => cancelAnimationFrame(frame);
  }, [composeOpen, replyId]);
  const acceptCatalog = (value: Data) => {
    setCatalog(value); setCatalogReady(true);
  };
  const load = () => { void invoke('teams.snapshot', {}, value => { acceptCatalog(value); if (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) setDraftCatalogChecked(true); }); };
  useEffect(() => { if (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) setDraftCatalogChecked(false); }, [formDraft.requiresRecheck, startDraft.requiresRecheck, memberFormDraft.requiresRecheck]);
  const readRun = (id: string, fromDirectory = false) => { void invoke('teams.readRun', proposalRead(id), result => {
    const next = result.reading;
    if (next?.kind !== 'run' || !next.viewHandle || next.run?.id !== id) { release(next); throw new Error('原团队运行身份不匹配'); }
    if (fromDirectory && teamRunEntrySection(next) === 'result') setRunSections(old => old[id] ? old : { ...old, [id]: 'result' });
    acceptReading(next);
  }); };
  const readDefinition = (id: string) => { void invoke('teams.readDefinition', { id }, value => { if (value.definition?.id !== id) throw new Error('团队配置身份不匹配，请刷新目录'); acceptReading(value); }); };
  useEffect(() => {
    const requestId = runFocus?.requestId, runId = runFocus?.runId;
    if (!requestId || !runId || requestId === handledRunFocus.current || !workspaceActive || !catalogReady || busy || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    handledRunFocus.current = requestId;
    const project=runFocus?.fromProject,from=runFocus?.fromWork,proposal=from&&'proposal' in from?from:undefined,files=from&&'fileReview' in from?from:undefined,pack=from&&'deliveredPackage' in from?from:undefined;
    const originalResult=runFocus?.fromWorkResult,accepted=originalResult?.accepted,member=runFocus?.fromWorkMember;
    retireWorkResult();
    const originalAnchor=originalResult&&onOpenWork&&originalResult.source.teamRunId===runId&&originalHotCompletedTeam(catalog,runId)?originalResult:null;
    const documentEpoch=bridge.getDocumentEpoch(),resume=object(bridge.getState().view).resumeRevision;
    const inSameVisit=()=>alive.current&&!document.hidden&&!backgrounded.current&&ownsNativeWindow()&&Number.isSafeInteger(resume)&&
      bridge.getDocumentEpoch()===documentEpoch&&object(bridge.getState().view).resumeRevision===resume;
    if(accepted){
      // The normal foreground restore may re-locate a pending run focus.
      // An acceptance source is read only for this explicit click.
      onRunFocusConsumed?.(requestId);
      if(!inSameVisit()||!accepted.current()){setError(acceptedSummaryUnavailable);return;}
    }
    const showRun=(result:Data)=>{
      const next=result.reading;
      if (next?.kind!=='run'||next.run?.id !== runId||!next.viewHandle||project&&(from||project.teamRunId!==runId||!next.run.productionScope||!next.run.workPlan)) {release(next);throw new Error('原协作记录身份不匹配，请从原工作重新打开。');}
      if(from){
        const link=next.ownerWorkLink;
        if(!link||link.currentRun!==true||link.readOnly!==true||link.stopping!==false||link.taskId!==from.parentTaskId||link.runId!==from.parentRunId
          ||link.epoch!==from.parentEpoch||link.conversationId!==from.conversationId||link.teamRunId!==from.teamRunId||runId!==from.teamRunId||files&&!sameTeamFiles(files.fileReview,link.fileReview)
          ||pack&&(!sameTeamPackage(pack.deliveredPackage,link.deliveredPackage)||!sameTeamPackage(pack.deliveredPackage,finalPackage(next.finalProjectPackage,runId)))
          ||proposal&&rows(next.revisionOffers).filter(row=>row.canReview===true&&sameTeamProposal(proposal.proposal,row)).length!==1){
          release(next);throw new Error(pack?'原团队签收或包版本已变化，没有打开替代成果。':'原文件或提案已变化，没有打开另一轮。');
        }
      }
      setEditor(null); setMemberDraft(null); setResultSource(null); closeReview(); closePhoneSelection();
      if(accepted)acceptedSummary.current={handle:next.viewHandle,current:accepted.current};
      acceptReading(next);setProposalEntry(proposal?{source:proposal,returnToWork:runFocus?.returnToWork}:null);
      setWorkPackage(pack&&runFocus?.returnToWork?{view:next.viewHandle,returnToWork:runFocus.returnToWork}:null);
      const returned=project||files;
      setFileEntry(returned?{view:next.viewHandle,source:returned,requestId,returnToWork:runFocus?.returnToWork}:null);
      const link = next.ownerWorkLink;
      const originalWork = !from && !project && link?.readOnly === true && link.currentRun === true && link.stopping === false && link.teamRunId === runId;
      const originalTeam = originalWork && ownerWorkPresentation(rows(bridge.getState().state.tasks).find(row => row.id === link.taskId))?.teams.find(row =>
        row.teamRunId === runId && row.taskId === link.teamTaskId && row.parentRunId === link.runId &&
        row.parentEpoch === link.epoch && row.conversationId === link.conversationId);
      const settled = originalTeam ? settledTeamResultEntry(originalTeam) : originalWork && ownerPublishedOrdinaryTeamResult(next);
      const originalMessage=originalAnchor&&inSameVisit()&&originalWorkTeamResult(next,originalAnchor.source);
      if(originalAnchor&&originalMessage){
        const generation=next.run.generation;
        workResultReturn.current=originalAnchor.arm(()=>{
          const shown=currentReading.current;
          return inSameVisit()&&shown?.run?.generation===generation&&originalWorkTeamResult(shown,originalAnchor.source)===originalMessage;
        });
      }else if(originalResult&&onOpenWork&&!accepted&&!from&&!project&&!member&&inSameVisit()){
        const identity=reportedTemporaryWorkIdentity(next,originalResult.source);
        if(identity)workResultReturn.current=originalResult.arm(()=>inSameVisit()&&shownRunSection.current==='result'&&
          reportedTemporaryWorkIdentity(currentReading.current,originalResult.source)===identity);
      }
      const stage=member&&inSameVisit()&&checkStageFocus(next,member)?member:undefined;
      if(stage)decisionTarget.current={handle:next.viewHandle,focus:{stage}};
      const entry = stage ? 'progress' : from || project || workResultReturn.current || accepted ? 'result' : settled ? teamRunEntrySection(next) : 'progress';
      setRunSections(old => ({ ...old, [runId]: entry }));
      setComposeOpen(false);
      onRunFocusConsumed?.(requestId);
    };
    const selection=requestGeneration.current+1;
    void invoke('teams.readRun', { runId,...(proposal?{proposalOrigin:proposal}:{}) }, showRun, failure => {
      onRunFocusConsumed?.(requestId);
      if(accepted&&inSameVisit())setError(acceptedSummaryUnavailable);
      else if(runFocus?.fromWork||runFocus?.fromProject)runFocus.returnToWork?.(failure instanceof Error?failure.message:'原文件或提案目录已变化。');
    }, accepted&&originalResult?async result=>{
      const next=result.reading, current=()=>inSameVisit()&&requestGeneration.current===selection;
      const matched=current()&&await verifiedAcceptedSummary(next,originalResult.source,accepted.report).catch(()=>false);
      if(!current()){release(next);onRunFocusConsumed?.(requestId);return false;}
      if(!matched||!accepted.current()||newerTeamReading(next.run,object(object(bridge.getState().state.teamRuns)[runId]))){
        release(next);throw Error(acceptedSummaryUnavailable);
      }
      return true;
    }:undefined);
  }, [runFocus?.requestId, workspaceActive, catalogReady, busy]);
  useEffect(() => {
    const requestId = label(attentionFocus?.requestId);
    if (!requestId || requestId === handledAttention.current || !workspaceActive || !catalogReady || busy || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    handledAttention.current = requestId;
    const locator = Object.fromEntries(['runId', 'memberId', 'generation', 'memberGeneration', 'identityRevision', 'workItemId', 'assignmentId', 'questionId', 'childKind', 'round', 'taskId']
      .filter(key => attentionFocus?.[key] !== undefined).map(key => [key, attentionFocus![key]])) as TeamAttentionLocator;
    void invoke('teams.readAttention', locator, result => {
      const next=result.reading, source=attentionFocus?.ownerWork;
      if (next?.kind!=='run'||!next.viewHandle||next.run?.id!==locator.runId) { release(next); throw new Error('没有读到这项待办的原协作。'); }
      if(source) {
        const link=next.ownerWorkLink;
        if(!link||link.currentRun!==true||link.readOnly!==true||link.stopping!==false||link.taskId!==source.parentTaskId
            ||link.runId!==source.parentRunId||link.epoch!==source.parentEpoch||link.conversationId!==source.conversationId||link.teamRunId!==source.teamRunId){
          release(next);throw new Error('发起这项待办的原工作已经变化，没有打开其他分工。');
        }
      }
      const focus=teamFollowUpFocus(next,object(result.focus),locator),id=next.run.id;
      setResultSource(null); acceptReading(next);
      showDecisionTarget(id,next.viewHandle,focus);
      onAttentionConsumed?.(requestId);
    }, () => onAttentionConsumed?.(requestId));
  }, [attentionFocus?.requestId, workspaceActive, catalogReady, busy]);
  const back = () => { const leave = () => { rememberView(); release(reading); acceptedSummary.current=null; setTab(reading?.kind === 'run' ? 'runs' : 'teams'); setReading(null); setComposeOpen(false); setReplyId(''); setEditor(null); setMemberDraft(null); setError(''); setNotice(''); }; formDraft.guard(() => startDraft.guard(() => messageDraft.guard(() => memberFormDraft.guard(() => { if (materialBeforeLeave.current) materialBeforeLeave.current(leave); else leave(); })))); };
  const openRelatedTask = (id: string,item?:Data) => {
    const origin=currentReading.current,live=bridge.getState(),view=object(live.view);
    const board=item&&origin?.kind==='run'&&['awaiting_input','awaiting_user','needs_confirmation','blocked_unknown','paused','failed','interrupted'].includes(item.status)
      ?planWorkSource(origin.run,item):null;
    if(board&&(!ownsNativeWindow()||document.hidden||backgrounded.current||busy||entered.current))return;
    const source=planWorkLocator(board,live);
    const place:PlanWorkReturn|null=source&&origin&&label(origin.viewHandle)&&source.assignmentId===id&&Number.isSafeInteger(view.resumeRevision)&&
      Number.isSafeInteger(live.state.requestedTaskRevision)?{source,sourceHandle:origin.viewHandle,document:bridge.getDocumentEpoch(),
        resume:view.resumeRevision,request:live.state.requestedTaskRevision}:null;
    if(board&&!place){setError('未核对到此分工的原工作回合。请重读本轮团队记录，没有打开其他工作。');return;}
    const open = () => {
      if(place) {
        if(planWorkReturn.current)return;
        if(currentReading.current?.viewHandle!==place.sourceHandle||planWorkWindow(place,bridge.getState())!=='team'){
          setError('原分工已变化，未打开其他工作。');return;
        }
        planWorkReturn.current=place;
      }
      void run('tasks.open',place?{id,teamPlanView:place.sourceHandle,expectedRunId:place.source.runId,expectedEpoch:place.source.epoch}:{id})
        .then(value=>{
          if(place&&planWorkReturn.current===place&&value?.taskId===id&&value.readOnly===true&&value.executionAuthorized===false)
            place.receipt=true;
        }).catch(failure=>{
          if(place) {
            if(planWorkReturn.current!==place||planWorkWindow(place,bridge.getState())!=='team')return;
            planWorkReturn.current=null;
          }
          setError(failure instanceof Error?failure.message:String(failure));
        });
    };
    if (materialBeforeLeave.current) materialBeforeLeave.current(open); else open();
  };
  const leaveWithDrafts = (leave: () => void) => formDraft.guard(() => startDraft.guard(() => messageDraft.guard(() => memberFormDraft.guard(() => {
    if (materialBeforeLeave.current) materialBeforeLeave.current(leave); else leave();
  }))));
  const askButler = (request: TeamButlerRequest = {}) => {
    if (!onAskButler || originalWorkDraft || busy || materialBusy) return;
    const teamId = reading?.kind === 'definition' ? label(reading.definition?.id) : reading?.kind === 'run' ? label(reading.run?.teamId) : '';
    leaveWithDrafts(() => onAskButler({ ...request, ...(teamId ? { teamId } : {}) }));
  };
  const openResearchTask = (memberId: string, record: TeamResearchRecord) => {
    const original = currentReading.current;
    if (busy || materialBusy || backgrounded.current || !ownsNativeWindow() || !original?.viewHandle || original.kind !== 'run') return;
    if (deliveryReturn.current?.record) return; // This exact record open is already in flight.
    // The stage-record button already has a held native read. When it is a
    // proven original Work visit, use the same exact child/return as a Result.
    if (!record.historical && record.recordedStatus === 'completed' && original === reading &&
      canRead && canOpenMemberResult && !entered.current && !deliveryReturn.current && !classicAnswerFlight.current &&
      !document.hidden && shownRunSection.current === 'progress') {
      const origin = readMemberWorkAudit(original, original);
      if (origin) {
        const sources = teamResearchDeliveries(original.run, original.messages).filter(row =>
          row.memberId === memberId && row.round === record.round && row.taskId === record.taskId);
        if (sources.length === 1) { openMemberDelivery(sources[0], 'progress', origin); return; }
      }
    }
    const open = () => { void invoke('teams.openResearchTask', { viewHandle: original.viewHandle, memberId, round: record.round, taskId: record.taskId }); };
    if (materialBeforeLeave.current) materialBeforeLeave.current(open); else open();
  };
  const switchTab = (value: TeamTab) => { if (busy) return; rememberView(); setTab(value); setError(''); setNotice(''); };
  const closeReview = () => { const original = currentReview.current; currentReview.current = null; release(original, 'reviewHandle'); setReview(null); setAcknowledged(false); };
  const closePhoneSelection = () => { const original = currentPhoneSelection.current; currentPhoneSelection.current = null; release(original, 'phoneHandle'); setPhoneSelection(null); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; requestGeneration.current++; retireWorkResult(); release(currentReading.current); release(currentReview.current, 'reviewHandle'); release(currentPhoneSelection.current, 'phoneHandle'); }; }, []);
  const restoreWorkspace = () => {
    if (backgrounded.current || !ownsNativeWindow()) return;
    // A retired read did not consume its notification; a fresh owner window may locate it again.
    handledAttention.current = '';
    handledRunFocus.current = '';
    // Resume the directory, not a previously selected acceptance source.
    const expiredAccepted = acceptedSummary.current;
    if(expiredAccepted){acceptedSummary.current=null;release(currentReading.current);}
    const original = expiredAccepted ? null : currentReading.current, generation = ++requestGeneration.current;
    closeReview(); closePhoneSelection();
    entered.current = true; setBusy(true); setReview(null); setAcknowledged(false); setError(expiredAccepted?acceptedSummaryUnavailable:'');
    setReading(original ? { ...original, viewHandle: '' } : null);
    void (async () => {
      if (original?.kind === 'definition') {
        const id = label(original.definition?.id);
        if (!id) throw new Error('原团队配置已变化');
        const value = object(await run('teams.snapshot', { restoreDefinitionId: id }));
        const next = value.reading, shown = currentReading.current;
        if (!alive.current || generation !== requestGeneration.current || backgrounded.current || !ownsNativeWindow() ||
            shown?.kind !== 'definition' || shown.definition?.id !== id) { release(next); return; }
        const fresh = object(value.catalog);
        if (!Array.isArray(fresh.teams) || !Array.isArray(fresh.runs)) { release(next); throw new Error('原团队目录尚未读取'); }
        acceptCatalog(fresh);
        if (next?.kind !== 'definition' || typeof next.viewHandle !== 'string' || !next.viewHandle || next.definition?.id !== id) {
          release(next); throw new Error('原团队配置已变化');
        }
        acceptReading(next);
        return;
      }
      const returning = deliveryReturn.current;
      const restoreRun = original?.kind === 'run' && returning?.ordinary && returning.entered &&
        returning.team === original.run.id && returning.documentEpoch === bridge.getDocumentEpoch() ? returning.team : '';
      const memberReturnCurrent = () => !backgrounded.current && !document.hidden && ownsNativeWindow() &&
        deliveryReturn.current === returning && returning?.documentEpoch === bridge.getDocumentEpoch() &&
        currentReading.current?.kind === 'run' && currentReading.current.run?.id === restoreRun;
      const planReturn=planWorkReturn.current;
      const restorePlan=!restoreRun&&original?.kind==='run'&&planReturn?.receipt&&planReturn.source.teamRunId===original.run.id&&
        planWorkWindow(planReturn,bridge.getState())==='returned'?planReturn:null;
      const samePlanVisit=()=>!restorePlan||(!backgrounded.current&&!document.hidden&&ownsNativeWindow()&&
        bridge.getDocumentEpoch()===restorePlan.document&&object(bridge.getState().view).resumeRevision===restorePlan.resume+1&&
        currentReading.current?.kind==='run'&&currentReading.current.run?.id===restorePlan.source.teamRunId);
      const wrapped=!!restoreRun||!!restorePlan;
      const value=object(await run('teams.snapshot',restorePlan?{restorePlanWork:restorePlan.source}:restoreRun?{restoreMemberResultRunId:restoreRun}:undefined));
      const next=wrapped?value.reading:null;
      if (!alive.current || generation !== requestGeneration.current || restoreRun && !memberReturnCurrent() || !samePlanVisit()) { release(next); return; }
      const fresh=wrapped?object(value.catalog):value;
      if (wrapped && (!Array.isArray(fresh.teams) || !Array.isArray(fresh.runs))) { release(next); throw new Error('原团队目录尚未读取'); }
      acceptCatalog(fresh);
      if(restorePlan) {
        const live=bridge.getState(),source=restorePlan.source;
        const returned=planWorkReturn.current===restorePlan&&planWorkWindow(restorePlan,live)==='returned';
        if(planWorkReturn.current===restorePlan)planWorkReturn.current=null;
        const selected=rows(next?.run?.workPlan?.items).filter(item=>item.workItemId===source.workItemId);
        if(returned&&next?.kind==='run'&&label(next.viewHandle)&&next.viewHandle!==restorePlan.sourceHandle&&
           selected.length===1&&dequal(planWorkLocator(planWorkSource(next.run,selected[0]),live),source)&&
           !newerTeamReading(next.run,object(object(live.state.teamRuns)[source.teamRunId]))) {
          acceptReading(next);
          showDecisionTarget(source.teamRunId,next.viewHandle,{kind:'workItem',workItemId:source.workItemId,taskId:source.assignmentId});
          return;
        }
      }
      if (restoreRun && next?.kind === 'run' && label(next.viewHandle) && next.viewHandle !== returning?.sourceHandle &&
          next.run?.id === restoreRun) {
        const audit=returning?.work;
        // A cold or fallback read can still return to the member, but cannot carry the original Work Back.
        if(returning&&audit?.receipt&&memberAuditWindow(returning,bridge.getState())==='returned'&&
          next.run.generation===returning.member?.generation&&originalWorkTeamResult(next,audit.source)===audit.summary&&
          teamResearchDeliveries(next.run,next.messages).filter(row=>row.key===returning.key).length===1)
          audit.returnHandle=next.viewHandle;
        acceptReading(next);return;
      }
      release(next);
      if (original?.kind === 'run') {
        const value = object(await run('teams.readRun', proposalRead(original.run.id)));
        if (alive.current && generation === requestGeneration.current && value.reading?.run?.id === original.run.id &&
            (!restoreRun || memberReturnCurrent()) && samePlanVisit()) acceptReading(value.reading);
        else if (wrapped) release(value.reading);
      }
    })().catch(failure => { if (alive.current && generation === requestGeneration.current) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (generation === requestGeneration.current) { entered.current = false; if (alive.current) setBusy(false); } });
  };
  useEffect(() => {
    const revision = Number(state.webWorkspaceResumeRevision || 0);
    const previous = workspaceWindow.current;
    workspaceWindow.current = { active: workspaceActive, revision };
    if (!workspaceActive) {
      if (previous.active) {
        requestGeneration.current++; entered.current = false; setBusy(false);
        closeReview(); closePhoneSelection(); setMemberProbes({}); setExternalReady({});
        const retain = !acceptedSummary.current;
        setReading(value => retain && value ? { ...value, viewHandle: '' } : null);
      }
      return;
    }
    if (!previous.active || revision !== previous.revision) restoreWorkspace();
  }, [workspaceActive, state.webWorkspaceResumeRevision]);
  // Retire only the optional original return, not the existing member reading.
  useEffect(()=>{
    const check=()=>{
      const live=bridge.getState(),opened=deliveryReturn.current,place=planWorkReturn.current;
      if(place) {
        const phase=planWorkWindow(place,live);
        if(phase==='child')place.seen=true;
        else if(!phase)planWorkReturn.current=null;
      }
      if(opened?.work) {
        const phase=memberAuditWindow(opened,live);
        if(phase==='child')opened.work.seen=true;
        else if(!phase)opened.work=undefined;
      }
    };
    const stopState=bridge.subscribe(check),stopDocument=bridge.subscribeDocumentEpoch(check);
    return ()=>{stopState();stopDocument();};
  },[]);
  useEffect(() => {
    const hide = () => {
      if(deliveryReturn.current)deliveryReturn.current.work=undefined;
      planWorkReturn.current=null;
      backgrounded.current = true; requestGeneration.current++; entered.current = false;
      setBusy(false); closeReview(); closePhoneSelection(); setMemberProbes({}); setExternalReady({});
      const retain = !acceptedSummary.current;
      setReading(value => retain && value ? { ...value, viewHandle: '' } : null);
    };
    const visibility = () => {
      if (document.visibilityState !== 'visible') hide();
      else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); }
    };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  const verifiedWorkResultReturn = () => onOpenWork&&workResultReturn.current?.current()===true;
  const readMemberWorkAudit = (original:Data,next:Data):WorkMemberAudit|undefined => {
    const work=workResultReturn.current, live=bridge.getState();
    const resume=object(live.view).resumeRevision, request=live.state.requestedTaskRevision;
    // The original result predicate includes the ordinary saved team, current
    // private parent Work and exact completed summary; a catalogue row is not enough.
    const summary=work?.current()?originalWorkTeamResult(original,work.source):undefined;
    if(!work||!summary||next.run.generation!==original.run.generation||
      next!==original&&originalWorkTeamResult(next,work.source)!==summary||!Number.isSafeInteger(resume)||resume<0||
      !Number.isSafeInteger(request)||request<0)return;
    return {source:work.source,rearm:work.rearm,summary,resume,request};
  };
  useLayoutEffect(() => { if(workResultReturn.current&&!verifiedWorkResultReturn())retireWorkResult(); });
  const backFromRun = () => {
    if(!verifiedWorkResultReturn()){retireWorkResult();back();return;}
    // App uses the same registered Team draft guard as the normal outer Back.
    onOpenWork?.(currentReading.current!.ownerWorkLink,'returnResult');
  };
  useEffect(() => { registerTransientBack(() => { if (frozenFilesBack.current?.()) return true; if (materialBack.current?.()) return true; if (busy) return true; if (review) { closeReview(); return true; } if (phoneSelection) { closePhoneSelection(); return true; } if (memberDraft) { memberFormDraft.guard(() => setMemberDraft(null)); return true; } if (editor) { formDraft.guard(() => setEditor(null)); return true; } if (resultBack.current()) return true; if(focusedPackage){leaveWithDrafts(focusedPackage.returnToWork);return true;} if (focusedProposal?.returnToWork) { leaveWithDrafts(focusedProposal.returnToWork); return true; } if (reading) { backFromRun(); return true; } return false; }); return () => registerTransientBack(null); }, [reading, review, phoneSelection, memberDraft, editor, busy, focusedPackage, focusedProposal, onOpenWork, registerTransientBack]);
  const definition = reading?.kind === 'definition' ? object(reading.definition) : null;
  const definitionReady = workspaceActive && (!definition || !!reading?.viewHandle);
  const canEditSavedMember = ['teams.reviewDefinition', 'teams.confirm'].every(command => bridge.getState().capabilities.commands.includes(command));
  const definitionMembers = rows(definition?.members);
  const modelKind = modelTeamKind(definition), modelTemplate = modelKind !== null;
  const templateDraftBlocked = !definitionReady || !startDraft.hasSession || startDraft.loading || !!startDraft.error ||
    startDraft.stale || startDraft.requiresRecheck || !!startDraft.conflict;
  const ordinaryTemplate = modelTemplate && definition?.mode === 'collaborate';
  const carryToOriginalDraft = originalWorkDraft && modelKind === 'ordinary';
  const replacementDefinition = replacingDraftTeam && carryToOriginalDraft &&
    isDraftTeamSelectionId(definition?.id) &&
    !Object.hasOwn(definition!, 'nativeWorkProfileModels') && definitionMembers.length<=4 &&
    definitionMembers.every(member=>!Object.hasOwn(member,'writeFiles')&&!Object.hasOwn(member,'remoteProduction'));
  const templatePreparation = !ordinaryTemplate ? '' : startDraft.requiresRecheck
    ? '上次启动的结果尚待核对；先查原协作，不新发起。'
    : startDraft.error ? '原目标草稿暂未核对，请先恢复读取或保存。'
    : startDraft.conflict || startDraft.stale ? '草稿与原配置待核对，暂不能带回。'
    : !definitionReady || startDraft.loading || !startDraft.hasSession || startDraftId.current !== definition?.id
      ? '正在读取这份编组的原目标草稿。'
      : goal.length > 8000 ? '目标超过 8,000 字，请缩短再带回。'
        : goal.trim() ? carryToOriginalDraft ? '这份目标仍留在编组，本次只关联编组。' : '这份目标草稿已有内容，可与已保存的编组一起带回。'
          : '成员与职责已保存；这份草稿还没写目标。';
  const carryModelTemplate = () => {
    const original = currentReading.current, exactGoal = currentInput.current.goal;
    if (!onAskButler || !modelTemplate || (originalWorkDraft && !carryToOriginalDraft) || busy || materialBusy || original?.kind !== 'definition' ||
        original.definition?.id !== definition?.id || !original.viewHandle ||
        (replacingDraftTeam ? !replacementDefinition || !definitionReady || original.definition.id===replacingDraftTeam.id :
         templateDraftBlocked || exactGoal.length > 8000 || startDraftId.current !== original.definition.id)) return;
    const documentEpoch = bridge.getDocumentEpoch();
    const stillOpen = () => !document.hidden && bridge.getDocumentEpoch() === documentEpoch && ownsNativeWindow() &&
      currentReading.current?.viewHandle === original.viewHandle;
    leaveWithDrafts(() => {
      if (!stillOpen() || !replacingDraftTeam && currentInput.current.goal !== exactGoal) return;
      void invoke('teams.readDefinition', { id: original.definition.id }, fresh => {
        if (fresh.kind !== 'definition' || !fresh.viewHandle || fresh.definition?.id !== original.definition.id) {
          release(fresh); throw new Error('没有核对到这份原编组，目标仍留在本机。');
        }
        if (!stillOpen()) { release(fresh); return; }
        if (!dequal(original.definition, fresh.definition)) {
          acceptReading(fresh); setNotice('编组已有变化。目标仍保留，核对当前分工后再带回管家。'); return;
        }
        release(fresh);
        if (!replacingDraftTeam && currentInput.current.goal !== exactGoal) { setNotice('目标已改动，未带走旧文字。'); return; }
        onAskButler({ teamId: original.definition.id, ...(carryToOriginalDraft ? { originalWorkDraft: stillOpen } : exactGoal.trim() ? { goal: exactGoal } : {}) });
      });
    });
  };
  const current = reading?.kind === 'run' ? object(reading.run) : null;
  // This relationship comes only from readRun's native parent-work check.
  const ownerWorkLink = reading?.kind === 'run' && reading.ownerWorkLink ? reading.ownerWorkLink as TeamOwnerWorkLink : null;
  const openOwnerWork = (intent: 'overview' | 'supplement' | 'reviewReport', stillAccepting?: () => boolean) => {
    if (!onOpenWork || !ownerWorkLink || busy || materialBusy || intent === 'supplement' && (ownerWorkLink.currentRun !== true || changed || !reading?.viewHandle)) return;
    leaveWithDrafts(() => { if (!stillAccepting || stillAccepting()) onOpenWork(ownerWorkLink, intent); });
  };
  const latest = current ? object(object(state.teamRuns)[current.id]) : {};
  const changed = !!current && newerTeamReading(current, latest);
  const pinnedSummary=acceptedSummary.current;
  const acceptedSummaryRetired=!!reading&&!!pinnedSummary&&
    (pinnedSummary.handle!==reading.viewHandle||changed||!pinnedSummary.current());
  useLayoutEffect(()=>{
    if(!acceptedSummaryRetired||acceptedSummary.current!==pinnedSummary)return;
    acceptedSummary.current=null;release(currentReading.current);setReading(null);setError(acceptedSummaryUnavailable);
  });
  useEffect(() => {
    if (pinnedSummary || !changed || !workspaceActive || busy || materialBusy || openingMemberResult || readingFiles || editor || memberDraft || review || phoneSelection || backgrounded.current) return;
    const runId = current!.id, originalHandle = reading?.viewHandle, generation = requestGeneration.current;
    let retired = false;
    const stillCurrent = () => !retired && !acceptedSummary.current && alive.current && !backgrounded.current && ownsNativeWindow()
      && generation === requestGeneration.current && currentReading.current?.run?.id === runId
      && currentReading.current?.viewHandle === originalHandle;
    // Coalesce progress events into one read without locking the user's draft.
    // A foreground action/navigation retires this read, never the action itself.
    const timer = setTimeout(() => {
      if (!stillCurrent()) return;
      void bridge.command('teams.readRun', proposalRead(runId)).then(result => {
        const next = object(object(result).reading);
        if (!stillCurrent()) { release(next); return; }
        if (next.run?.id !== runId || !next.viewHandle) {
          release(next); throw new Error('原团队运行身份不匹配，请刷新记录');
        }
        acceptReading(next);
      }).catch(failure => {
        if (stillCurrent()) setError(failure instanceof Error ? failure.message : String(failure));
      });
    }, 250);
    return () => { retired = true; clearTimeout(timer); };
  }, [changed, current?.id, reading?.viewHandle, workspaceActive, busy, materialBusy, openingMemberResult, readingFiles, editor, memberDraft, review, phoneSelection]);
  const members = rows(current?.members), messages = rows(reading?.messages);
  const activeQuestionKeys = new Set(rows(reading?.attention).filter(item => item.kind === 'question').map(questionKey));
  const isCurrentQuestion = (message: Data) => message.kind === 'assignment_question' && message.state === 'pending' && activeQuestionKeys.has(questionKey(message));
  const workItems = rows(object(current?.workPlan).items);
  const deliveryWorkers=deliveryReading(reading?.deliveryWorkers,current?.id);
  const teamPackage=current&&reading?.viewHandle?finalPackage(reading.finalProjectPackage,current.id):null;
  const pendingFinal=deliveryWorkers?.resourcesReleased===false;
  const revisionRequest=object(current?.workPlan).lastRevisionRequestId;
  const hasFrozenRevision=!!revisionRequest&&rows(current?.parentRequests).some(p=>p.kind==='revision_proposal'&&p.state==='applied_to_work_revision'&&p.revisionRequestId===revisionRequest);
  const runSection = runSections[current?.id || ''] || 'progress';
  const shownRunSection = useRef(runSection);
  shownRunSection.current = runSection;
  const expandedDetails = detailSections[current?.id || ''] || [];
  const materialsActive = runSection === 'details' && expandedDetails.includes('materials');
  const revealDetail = (section: string) => { if (current) setDetailSections(old => ({ ...old, [current.id]: [...new Set([...(old[current.id] || []), section])] })); };
  const chooseDetailSections = (sections: string[]) => {
    if (!current) return;
    const select = () => setDetailSections(old => ({ ...old, [current.id]: sections }));
    if (materialsActive && !sections.includes('materials') && materialBeforeLeave.current) materialBeforeLeave.current(select); else select();
  };
  const memberFilter = memberFilters[current?.id || ''] || '';
  const visibleMessages = memberFilter ? messages.filter(message => [message.memberId, message.recipientMemberId, message.targetMemberId].includes(memberFilter)) : messages;
  const chooseMemberFilter = (id: string) => { if (current) setMemberFilters(old => ({ ...old, [current.id]: id })); };
  const chooseRunSection = (section: RunSection, onShown?: () => void) => {
    if (!current) return;
    const select = () => {
      const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
      if (scroll && section !== runSection) positions.current.set(`run:${current.id}:${runSection}`, scroll.scrollTop);
      setRunSections(old => ({ ...old, [current.id]: section }));
      requestAnimationFrame(() => { if (scroll && section !== runSection) scroll.scrollTop = positions.current.get(`run:${current.id}:${section}`) || 0; onShown?.(); });
    };
    if (materialsActive && section !== runSection && materialBeforeLeave.current) materialBeforeLeave.current(select);
    else select();
  };
  const focusMessage = (id: string, recipientId = '') => {
    const runId = current?.id;
    chooseRunSection('details', () => {
      if (document.hidden || !ownsNativeWindow() || currentReading.current?.run?.id !== runId) return;
      revealDetail('conversation');
      chooseMemberFilter(recipientId);
      requestAnimationFrame(() => {
        if (document.hidden || !ownsNativeWindow() || currentReading.current?.run?.id !== runId) return;
        const targets = root.current?.querySelectorAll<HTMLElement>(`[data-message-id="${CSS.escape(id)}"]`);
        const target = targets?.length === 1 ? targets[0] : undefined;
        if (!target || target.closest('[hidden],[inert]')) {
          if (recipientId) setNotice('这条原消息当前不可定位，没有转到另一条。');
          return;
        }
        target.focus({ preventScroll: true }); target.scrollIntoView({ block: 'center' });
      });
    });
  };
  const hasWorkPlan = !!current?.workPlan;
  const reply = messages.find(message => message.messageId === replyId);
  const replyCanReceive = !reply || reply.kind !== 'assignment_question' || isCurrentQuestion(reply);
  const draftKey = `${current?.id || ''}:${recipient}:${replyId}:${mode}`, text = drafts[draftKey] || '';
  const currentDraft = messageDraftId.current === draftKey && !messageDraft.loading;
  const draftNeedsRecheck = (currentDraft && messageDraft.requiresRecheck) || uncertain?.key === draftKey;
  const draftOnly = currentDraft && !!text.trim() && !draftNeedsRecheck && !submittingAnswer.current;
  const latestAnswer = reply?.kind === 'assignment_question' && answerReceipt?.runId === current?.id &&
    answerReceipt?.questionId === reply.questionId && answerReceipt?.memberId === reply.memberId &&
    answerReceipt?.assignmentId === reply.assignmentId ? answerReceipt : null;
  const latestAnswerMessageId = latestAnswer && reply && reply.answerId === latestAnswer.id && reply.answer && !isCurrentQuestion(reply)
    ? label(reply.messageId) : '';
  useEffect(() => {
    if (!current?.id || !recipient || !composeOpen) return;
    messageDraftId.current = '';
    void messageDraft.open('owner', 'message:' + draftKey, draftKey, { text: drafts[draftKey] || '' }).then(saved => { if (saved && alive.current) { messageDraftId.current = draftKey; setDrafts(old => ({ ...old, [draftKey]: saved.text })); } });
  }, [draftKey, composeOpen]);
  useEffect(() => { if (messageDraftId.current === draftKey) messageDraft.update({ text }); }, [draftKey, text, messageDraft.update]);
  useEffect(() => { if (messageDraft.requiresRecheck) setMessageChecked(false); }, [messageDraft.requiresRecheck]);
  const canRead = workspaceActive && !!current && !!reading?.viewHandle && !busy && !openingMemberResult && !changed;
  const canControl = canRead && !materialBusy && current?.status !== 'cancelled';
  const canMutate = canControl && !uncertain;
  const changeMessageMode = (nextMode: string) => messageDraft.guard(() => setMode(nextMode));
  const name = (id: string) => { const member = members.find(value => value.id === id); return member ? teamMemberLabel(member, members) : id; };
  const simpleRoster = current?.mode === 'collaborate' && !hasWorkPlan && (!current.researchExecutionFormat || current.researchExecutionFormat === catalog.continuousResearchFormat) && !current.nativeWorkProfileModels
    && members.every(member => (member.runner || 'model') === 'model' && (!member.device || member.device === 'phone'));
  const rosterPaused = current?.status === 'paused' || current?.phase === 'awaiting_member';
  const currentMembers = members.filter(member => !member.exited);
  const pairedResultsMissing = current?.mode === 'collaborate' && current.rounds === 1 && !hasWorkPlan
    ? currentMembers.filter(member => member.runner === 'phone_agent' && member.device?.kind === 'paired_android' &&
        member.status === PHONE_OUTPUT_NOT_RETURNED)
    : [];
  const noWorkRetryForMissingText = current?.status === 'partial' && pairedResultsMissing.length > 0 &&
    !currentMembers.some(member => ['failed','interrupted','paused'].includes(member.status));
  const beginMemberChange = (action: 'add' | 'rename' | 'exit', member?: Data) => {
    if (!canMutate || !current) return;
    setError('');
    const initial = { action, runId: current.id, memberId: member?.id || `member-${crypto.randomUUID().slice(0, 12)}`, name: member?.name || '', avatarShape: teamAvatarShape(member || { id: `new-${members.length}` }), role: '', assignment: '', model: '', newLeaderId: '' };
    if (action === 'exit') { setMemberDraft(initial); return; }
    void memberFormDraft.open('owner', 'member:' + current.id + ':' + action + ':' + (member?.id || 'new'), String(current.revision || ''), { members: [{ id: initial.memberId, name: initial.name, avatarShape: initial.avatarShape, role: initial.role, assignment: initial.assignment, model: initial.model }] }).then(saved => { if (saved && alive.current) { const value = saved.members[0]; setMemberDraft({ ...initial, ...value, memberId: value.id }); } });
  };
  useEffect(() => { if (memberDraft && memberDraft.action !== 'exit') memberFormDraft.update({ members: [{ id: memberDraft.memberId, name: memberDraft.name, avatarShape: memberDraft.avatarShape, role: memberDraft.role, assignment: memberDraft.assignment, model: memberDraft.model }] }); }, [memberDraft, memberFormDraft.update]);
  const reviewMemberChange = () => {
    if (!memberDraft || memberDraft.action !== 'exit' && (memberFormDraft.stale || !!memberFormDraft.conflict || memberFormDraft.requiresRecheck) || !canMutate || memberDraft.runId !== current?.id) return;
    const { action, memberId, name, avatarShape, role, assignment, model, newLeaderId } = memberDraft;
    void invoke('teams.reviewMember', { viewHandle: reading!.viewHandle, action, memberId,
      ...(action === 'exit' ? { newLeaderId } : { name, avatarShape }), ...(action === 'add' ? { role, assignment, model } : {}) }, reviewResult);
  };
  const pendingCount = (member: Data) => Number(member.unreadOwnerMessages || 0) + Number(member.unreadPeerMessages || 0);
  const pendingMessageBusy = (member: Data) => ['running', 'awaiting_approval'].includes(member.status);
  const recipientMember = members.find(member => member.id === recipient);
  const recipientPending = recipientMember && !hasWorkPlan && !recipientMember.exited ? pendingCount(recipientMember) : 0;
  const reviewResult = (value: Data) => {
    if (value.operation === 'phone_scope') { setPhoneSelection(value); setPhoneChoices(Object.fromEntries(rows(value.view?.members).map(member => [member.memberId, teamPhoneChoice()]))); }
    else { setReview(value); setAcknowledged(false); }
  };
  const control = (command: string, memberId?: string, resumed?: NonNullable<ReturnType<typeof teamRemainingMember>>) => {
    if (!reading || !canControl || entered.current || backgrounded.current || !ownsNativeWindow() || uncertain && !['pause', 'cancel'].includes(command)) return;
    const viewHandle = reading.viewHandle;
    // Native control consumes this reading before it checks the current run.
    // A rejected action must not leave enabled controls with a spent handle.
    setReading(value => value ? { ...value, viewHandle: '' } : null);
    void invoke('teams.control', { viewHandle, command, ...(memberId ? { memberId } : {}), ...(resumed?.deferredOwnRound ? { originalMemberOnly: true } : {}) }, result => {
      if (result.confirmed !== true) throw new Error('原操作尚未确认');
      if (resumed) {
        const next = result.reading?.run, locator = resumed.item.locator;
        const slot = rows(next?.members).find(member => member.id === locator.memberId);
        const sameWait = !resumed.deferredOwnRound || next?.generation === locator.generation &&
          rows(slot?.roundStates).some(stage => stage.round === resumed.rounds[0] && stage.status === 'queued') &&
          rows(slot?.roundStates).some(stage => stage.round === resumed.deferredOwnRound && ['waiting_dependency','blocked_dependency'].includes(stage.status));
        if (result.command !== 'resume' || result.reading?.kind !== 'run' || !result.reading.viewHandle ||
            next?.id !== locator.runId || !(next.revision > resumed.revision) ||
            slot?.generation !== locator.memberGeneration + 1 || slot.status !== 'queued' || !sameWait) {
          release(result.reading); throw new Error('原分工是否已排队待核对，请刷新这次协作，不要重复提交。');
        }
      }
      if (result.reading) acceptReading(result.reading);
      setNotice(resumed?.deferredOwnRound ? `这名成员的第 ${resumed.rounds[0]} 阶段已排队。第 ${resumed.deferredOwnRound} 阶段仍在等原结果；其他成员的原件保留。` :
        resumed ? '这名成员的未完成分工已排队。其他成员已交的原件保留，尚无新成果。' : '已收到原运行的操作回执。');
    });
  };
  const mayAnswer = !!reading && !!current && canMutate && reply?.kind === 'assignment_question' && replyCanReceive
    && recipient === reply.memberId && mode === 'discussion' && !!reply.assignmentId && !!reply.questionId && members.some(member => member.id === recipient && !member.exited)
    && !messageDraft.loading && !messageDraft.conflict && !messageDraft.requiresRecheck && messageDraftId.current === draftKey && !!text.trim() && text.length <= 4000;
  const acceptAnswer = (result: Data, original: Omit<AnswerReceipt, 'detail'>, delivery: AnswerDelivery) => {
    if (!teamMessageConfirmed(result, original)) throw new Error('原回答回执尚待核对，请刷新原记录。');
    if (result.reading?.run?.id === original.runId) acceptReading(result.reading);
    const detail = teamAnswerOutcome(result.result, delivery) + (result.reading?.run?.id === original.runId ? '' : ' 请刷新原记录。');
    setAnswerReceipt({ ...original, detail });
    setNotice(detail);
  };
  const send = async (delivery: 'save' | 'continue') => {
    if (!mayAnswer || !reading || !reply || submittingAnswer.current || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    const action = 'teams.answerAssignment';
    if (!bridge.getState().capabilities.commands.includes(action)) { setError('当前团队页面尚未接通回答能力，草稿保留，请重新进入。'); return; }
    submittingAnswer.current = true; setBusy(true);
    const { viewHandle } = reading;
    const original: PendingMessage = { id: crypto.randomUUID(), memberId: recipient, runId: current!.id, key: draftKey, text, questionId: reply.questionId, assignmentId: reply.assignmentId };
    try {
      await messageDraft.markSubmitted();
      if (!alive.current || entered.current || backgrounded.current || !ownsNativeWindow() || currentReading.current?.viewHandle !== viewHandle
        || currentInput.current.replyId !== replyId || currentInput.current.recipient !== recipient || messageDraftId.current !== original.key) {
        if (alive.current && messageDraftId.current === original.key) { messageDraft.clearSubmitted(); setNotice('原窗口已变化，回答没有提交。'); }
        return;
      }
      rememberUncertain(original.runId, original);
      setReading(value => value?.viewHandle === viewHandle ? { ...value, viewHandle: '' } : value);
      await invoke(action, { viewHandle, memberId: recipient, messageId: original.id, text: text.trim(), delivery, quoteMessageId: replyId }, result => {
        acceptAnswer(result, original, delivery);
        setDrafts(old => old[original.key] === original.text ? { ...old, [original.key]: '' } : old); rememberUncertain(original.runId, null);
        if (messageDraftId.current === original.key) messageDraft.discard(() => { if (alive.current && messageDraftId.current === original.key) void messageDraft.open('owner', 'message:' + original.key, original.key, { text: '' }); });
      }, failure => { if (['not_sent', 'not_accepted', 'invalid_request'].includes(object(failure).code)) {
        rememberUncertain(original.runId, null); if (messageDraftId.current === original.key) messageDraft.clearSubmitted();
        setNotice('这次回答未提交，草稿保留；请刷新原运行后再操作。');
      } });
    } catch (failure) { if (alive.current) setError((failure as Error).message); }
    finally { submittingAnswer.current = false; if (alive.current && !entered.current) setBusy(false); }
  };
  const savedContinuationKey = (row: Data) => JSON.stringify([current?.id, row.memberId, row.assignmentId, row.questionId, row.answerId]);
  const continueSavedAnswer = (row: Data) => {
    if (!canMutate || !reading || row.canRequestSavedContinuation !== true || submittingAnswer.current || entered.current || backgrounded.current || !ownsNativeWindow()) return;
    const key = savedContinuationKey(row); if (unverifiedSavedContinuation.current.has(key)) return;
    const original = { id: row.answerId, runId: current!.id, memberId: row.memberId, assignmentId: row.assignmentId, questionId: row.questionId };
    const { viewHandle } = reading;
    unverifiedSavedContinuation.current.add(key);
    setReading(value => value?.viewHandle === viewHandle ? { ...value, viewHandle: '' } : value);
    void invoke('teams.answerAssignment', { viewHandle, memberId: row.memberId, messageId: row.answerId, quoteMessageId: row.messageId, delivery: 'continue_saved' }, result => {
      acceptAnswer(result, original, 'continue_saved'); unverifiedSavedContinuation.current.delete(key);
    }, failure => {
      if (['not_sent', 'not_accepted', 'invalid_request'].includes(object(failure).code)) unverifiedSavedContinuation.current.delete(key);
      else setNotice('继续请求的结果待核对，请查看原分工进展；回答没有重发。');
    });
  };
  const beginEditor = (team: Data | null, purpose: EditorPurpose = team ? 'definition' : 'start', fromDraft?: Data, memberId?: string) => {
    if (formDraft.loading) return;
    const held = memberId ? currentReading.current : null, documentEpoch = memberId ? bridge.getDocumentEpoch() : -1;
    const sameDefinitionVisit = () => held?.kind === 'definition' && held.definition === team && !!held.viewHandle &&
      currentReading.current === held && !document.hidden && ownsNativeWindow() && bridge.getDocumentEpoch() === documentEpoch;
    if (memberId && !sameDefinitionVisit()) return;
    memberEditorFocus.current = null;
    const members = team ? rows(team.members).map(teamProductionEditorMember) : fromDraft ? rows(fromDraft.members) : purpose === 'start' ? [] : starterMembers('research');
    setError(''); setNotice(''); setExternalReady({}); setEditorExpanded(memberId ? [memberId] : team ? members.slice(0, 1).map(member => member.id) : []);
    const initial = { name: team?.name || fromDraft?.name || (purpose === 'start' ? '' : '共同研究'), nameEdited: team ? true : fromDraft?.nameEdited, mode: team?.mode || fromDraft?.mode || 'collaborate', leaderId: team?.leaderId || fromDraft?.leaderId || members.at(-1)?.id || '', members, goal: team ? goal : fromDraft?.goal || '', rounds: fromDraft?.rounds || '1' };
    void formDraft.open('owner', team ? 'definition:' + team.id : purpose === 'start' ? 'collaboration:new' : 'definition:new', String(team?.revision || 'new'), safeTeamDraft(initial)).then(saved => {
      if (!saved || !alive.current || memberId && !sameDefinitionVisit()) return;
      if (memberId && rows(saved.members).filter(member => member.id === memberId).length !== 1) {
        setNotice('原成员不在保留的编辑草稿里。可打开「编辑编组」核对，没有跳到其他成员。'); return;
      }
      setEditorPurpose(purpose);
      if (purpose === 'start') setQuickStartPending(pendingStart(saved));
      if (memberId && held) memberEditorFocus.current = { id: memberId, read: held, documentEpoch };
      setEditor({ ...initial, ...saved, members: rows(saved.members).map(member => ({ ...member, externalExecutionRiskAcknowledged: false, production: resetTeamProductionAcknowledgments({ ...teamProductionDraft(), ...member.production }) })) });
    });
  };
  const changeTeamGoal = (goal: string) => setEditor(previous => {
    if (!previous) return previous;
    // Older drafts have no flag. Keep their non-default names rather than infer a rename.
    const nameEdited = typeof previous.nameEdited === 'boolean' ? previous.nameEdited : !!previous.name && previous.name !== '共同研究';
    return { ...previous, goal, nameEdited, name: nameEdited ? previous.name : goal.trim().split('\n')[0].slice(0, 40) || '共同研究' };
  });
  useEffect(() => { if (editor) formDraft.update(safeTeamDraft(editor)); }, [editor, formDraft.update]);
  useEffect(() => { if (reading?.kind === 'definition' && startDraftId.current === reading.definition.id) startDraft.update({ goal, rounds, ...(savedStartPending ? { pendingStartTeamId: savedStartPending.teamId, pendingStartSince: savedStartPending.since } : {}) }); }, [goal, rounds, savedStartPending, reading?.definition?.id, startDraft.update]);
  const addMember = () => {
    if (!editor || busy || editor.members.length >= 4) return;
    const member = blankMember(`member-${crypto.randomUUID().slice(0, 8)}`, editor.members.length);
    setEditor({ ...editor, members: [...editor.members, member] }); setEditorExpanded(old => [...old, member.id]);
    requestAnimationFrame(() => document.getElementById(`team-editor-member-${member.id}`)?.scrollIntoView({ block: 'nearest' }));
  };
  const revealMember = (id: string) => {
    setEditorExpanded(old => old.includes(id) ? old : [...old, id]);
    requestAnimationFrame(() => {
      const section = document.getElementById(`team-editor-member-${id}`);
      section?.scrollIntoView({ block: 'nearest' });
      section?.querySelector<HTMLElement>('input:not(:disabled),textarea:not(:disabled),button:not(:disabled)')?.focus({ preventScroll: true });
    });
  };
  useLayoutEffect(() => {
    const focus = memberEditorFocus.current;
    if (!editor || review || !focus) return;
    memberEditorFocus.current = null;
    if (currentReading.current !== focus.read || !ownsNativeWindow() || document.hidden || bridge.getDocumentEpoch() !== focus.documentEpoch) return;
    const target = document.getElementById(`team-editor-member-${focus.id}`);
    if (!target?.closest('[role="dialog"][data-state="open"]')) return;
    // Focus the selected section without opening the phone keyboard just to browse.
    target.focus({ preventScroll: true });
    target.scrollIntoView({ block: 'nearest' });
  }, [editor, review]);
  const updateMember = (id: string, changes: Data) => {
    if (['runner', 'device', 'model', 'modeId'].some(key => Object.hasOwn(changes, key))) setExternalReady(old => ({ ...old, [id]: false }));
    setEditor(value => value ? { ...value, members: rows(value.members).map(member => member.id === id ? { ...member, ...changes,
      ...(['runner', 'device'].some(key => Object.hasOwn(changes, key)) ? { production: resetTeamProductionAcknowledgments(member.production || teamProductionDraft()) } : {}) } : member) } : null);
  };
  const phoneTargets = rows(catalog.phoneTargets);
  const computerProfiles = rows({ ...object(object(state.features).remote), ...object(object(state.ownerSnapshots).remote) }.profiles);
  const computerLabel = (member: Data) => `${computerProfiles.find(profile => profile.id === member.device?.profileId)?.name || member.device?.profileId || '原电脑'} · ${member.device?.environmentId || '环境待选择'}`;
  const phoneMembers = rows(phoneSelection?.view?.members);
  const hasPhoneMembers = definitionMembers.some(member => member.runner === 'phone_agent');
  const choosePhoneScope = (member: Data, id: string) => setPhoneChoices(value => ({ ...value, [member.memberId]: teamPhoneChoice(rows(member.options).find(option => option.optionId === id)) }));
  const changePhoneBudget = (memberId: string, field: keyof TeamPhoneChoice, value: string) => setPhoneChoices(old => ({ ...old, [memberId]: { ...old[memberId], [field]: value } }));
  const reviewPhoneStart = () => {
    if (!phoneSelection || busy) return;
    try {
      const members = teamPhoneSelections(object(phoneSelection.view), phoneChoices);
      const phoneHandle = phoneSelection.phoneHandle; currentPhoneSelection.current = null; setPhoneSelection(null);
      void invoke('teams.reviewPhoneStart', { phoneHandle, members }, reviewResult);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const catalogTeams = rows(catalog.teams), catalogRuns = rows(catalog.runs);
  const catalogEntries = tab === 'runs' ? catalogRuns : catalogTeams, hasQuery = !!query.trim();
  const showDirectoryTools = catalogReady || catalogTeams.length > 0 || catalogRuns.length > 0;
  const showCollectionTools = catalogEntries.length > 0 || hasQuery;
  const directoryState = !workspaceActive || busy ? 'loading' : error ? 'unconfirmed' : !catalogReady ? 'unread' : hasQuery ? 'filtered' : 'empty';
  const entries = catalogEntries.map(row => {
    const live = object(object(state.teamRuns)[row.id]);
    return tab === 'runs' && live.id === row.id && (live.generation > row.generation
      || live.generation === row.generation && live.revision > row.revision) ? { ...row, ...live } : row;
  }).filter(row => `${row.goal || ''} ${row.name || ''} ${row.teamName || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const showDirectoryActions = catalogReady && !error;
  const choosingOriginalTeam = originalWorkDraft && tab === 'teams';
  const offerFirstOriginalTeam = choosingOriginalTeam && workspaceActive && showDirectoryActions && Array.isArray(catalog.teams) &&
    !catalogTeams.some(team => /^[a-f0-9-]{36}$/.test(label(team.id)) && modelTeamKind(team) === 'ordinary') &&
    bridge.getState().capabilities.commands.includes('teams.reviewDefinition');
  const directoryGroups = tab === 'teams' ? [{ id: 'definitions', title: '可复用的成员与分工', entries }] : [
    { id: 'attention', title: '需要处理', entries: entries.filter(row => teamNeedsAttention(row.status)) },
    { id: 'active', title: '进行中与已暂停', entries: entries.filter(row => !teamNeedsAttention(row.status) && !teamHasEnded(row.status)) },
    { id: 'history', title: '已结束', entries: entries.filter(row => teamHasEnded(row.status)) },
  ];
  useCollectionMotion(root, JSON.stringify([viewKey, runSection, memberFilter, query.trim(), state.webWorkspaceResumeRevision]), JSON.stringify([
    entries.map(row => [row.id, row.status, row.revision]),
    messages.map(message => [message.messageId, message.state, !!message.answer]),
    members.map(member => [member.id, member.status, member.exited]),
    workItems.map(item => [item.workItemId, item.status]), !!current?.summary,
    expandedMembers[String(current?.id || '')] || [], expandedItems[String(current?.id || '')] || [],
  ]), { active: workspaceActive && catalogReady && (!reading || !!reading.viewHandle) && !editor && !memberDraft && !review && !phoneSelection });
  const teamResult = teamResultReading(current, messages, deliveryWorkers?.originalFileObservation);
  const followUps = teamFollowUps(current, reading?.attention);
  const frozenReviewNext = runSection==='progress' && canRead && !materialBusy && !readingFiles && !review && !resultSource &&
    !teamResult.body && !followUps.length && reading?.currentFirstLocalFrozenReviewSource===true &&
    ownerWorkLink?.currentRun!==false && ownerWorkLink?.stopping!==true &&
    deliveryWorkers?.complete===true && deliveryWorkers.resourcesReleased===true && !deliveryWorkers.workers.length &&
    !deliveryWorkers.unboundRoles.length && !deliveryWorkers.settledOriginal && !deliveryWorkers.originalFileObservation &&
    bridge.getState().capabilities.commands.includes('teams.readFrozenOutputs');
  const canOpenMemberResult = bridge.getState().capabilities.commands.includes('teams.openResearchTask');
  const classicOwner = canOpenMemberResult && openReading && globalThis.crypto?.subtle ? classicAnswerOwner(reading,true) : '';
  const deliveries = teamResearchDeliveries(current, messages, 'current', !!classicOwner);
  const awaitingMemberAnswerReview = !!classicOwner && reading?.ownerWorkLink?.temporaryAnswersForReview===true &&
    deliveries.some(row=>row.answer?.plainText===true);
  const reportReview = primaryTeamsRoute && runSection==='result' && awaitingMemberAnswerReview &&
    ['tasks.open','tasks.review','tasks.confirm','tasks.close'].every(command=>bridge.getState().capabilities.commands.includes(command))
    ? pendingTeamReportReview(ownerWorkLink) : undefined;
  const exactReportReview = reportReview && reportReview.generation===current?.generation && reportReview.revision===current?.revision && ownerWorkLink?.teamRunId===current?.id
    ? reportReview : undefined;
  const reviewOriginalConclusion = () => {
    if(!exactReportReview||!current||!reading||!canRead||materialBusy)return;
    const documentEpoch=bridge.getDocumentEpoch(),resume=object(bridge.getState().view).resumeRevision;
    const stillCurrent=()=>alive.current&&!document.hidden&&!backgrounded.current&&ownsNativeWindow()&&
      currentReading.current===reading&&shownRunSection.current==='result'&&bridge.getDocumentEpoch()===documentEpoch&&
      object(bridge.getState().view).resumeRevision===resume&&
      !newerTeamReading(current,object(object(bridge.getState().state.teamRuns)[current.id]));
    if(stillCurrent())openOwnerWork('reviewReport',stillCurrent);
  };
  const archivedDeliveries = teamResearchDeliveries(current, messages, 'archived');
  const ordinaryAnswers = current?.mode === 'collaborate' && !hasWorkPlan && !current.productionScope &&
    current.researchExecutionFormat === 'pocketworld.team-research-agent.v1';
  const resultPhaseLink = ordinaryAnswers && resultSource?.member && resultSource.runId === current.id &&
    resultSource.member.generation === current.generation ? resultSource.member.deliveryKey : '';
  const readableDeliveries = deliveries.filter(source => canOpenMemberResult || source.answer && openReading);
  const remainingMember = teamRemainingMember(reading, readableDeliveries, state.tasks);
  const canReviewStoppedMember = ['teams.reviewMember','teams.confirm'].every(command => bridge.getState().capabilities.commands.includes(command));
  const stoppedCandidate = stoppedTeamMemberCandidate(reading, readableDeliveries);
  const stoppedMember = canReviewStoppedMember || stoppedCandidate?.failureKey ? stoppedCandidate : null;
  const stoppedMessageRecipient = stoppedMember?.memberId === recipient;
  // The temporary roster is not a saved team. Only the already held original
  // Work link gives this current research question the same read-only placement.
  const originalTemporaryQuestionRun = current?.definitionKind === 'one_time' && (current.discussionFormat ?? 'classic') === 'classic' &&
    !current.discussion && !current.phoneScope && members.length >= 2 && members.length <= 4 &&
    !!currentOriginalTeamParent(reading) && members.every(member => member.runner === 'model' && member.device === 'phone' && !member.exited);
  const showOriginalQuestions = ordinaryAnswers && workspaceActive && !!reading?.viewHandle && !changed &&
    !!label(current.teamId).trim() && (current.definitionKind == null || originalTemporaryQuestionRun) && !current.nativeWorkProfileModels && current.archived !== true &&
    ['running', 'paused'].includes(current.status) && members.length > 0 &&
    members.every(member => (member.runner || 'model') === 'model' && (!member.device || member.device === 'phone'));
  const progressByMember = new Map(current ? members.map(member => [String(member.id), teamMemberProgress(current, member, followUps, true)]) : []);
  const originalSavedWorkRoster = ordinaryAnswers && workspaceActive && !!reading?.viewHandle && !changed &&
    !!label(current.teamId).trim() && current.definitionKind == null && (current.discussionFormat ?? 'classic') === 'classic' &&
    !current.discussion && !current.nativeWorkProfileModels && ownerWorkLink?.readOnly === true && ownerWorkLink.currentRun === true &&
    ownerWorkLink.teamRunId === current.id && !!label(ownerWorkLink.teamTaskId) && ownerWorkLink.teamTaskId === reading.taskId &&
    members.length > 0 && members.length <= 4 && new Set(members.map(member => member.id)).size === members.length &&
    members.every(member => (member.runner || 'model') === 'model' && (!member.device || member.device === 'phone'));
  // Whole-run cancellation marks unfinished stages before the original children settle.
  const stoppedSavedWorkTeam = originalSavedWorkRoster && ownerWorkLink?.stopping === true &&
    current?.status === 'cancelled' && current.phase === 'cancelled';
  const showPrerequisites = originalSavedWorkRoster && ownerWorkLink?.stopping === false && current?.archived !== true &&
    Array.isArray(catalog.history) && !rows(catalog.history).some(row => row.id === current?.id);
  const prerequisiteDocument = bridge.getDocumentEpoch(), prerequisiteResume = object(bridge.getState().view).resumeRevision;
  const actionableFollowUps = showPrerequisites ? followUps.filter(item => {
    const member = members.find(row => row.id === item.locator.memberId);
    return !member || !passiveTeamDependency(item, member, progressByMember);
  }) : followUps;
  const scopedFollowUps = actionableFollowUps.map(item => {
    const question = showOriginalQuestions && current ? originalResearchQuestion(current, item) : '';
    if (question) return { ...item, title: question, detail: name(item.locator.memberId) + ' · 第 ' + item.locator.round + ' 阶段提问', originalQuestion: true };
    if (item.key === stoppedMember?.failureKey && canReviewStoppedMember && canMutate && !uncertain)
      return { ...item, title: '核对并重试' + name(item.locator.memberId) + '的分工', originalQuestion: false };
    return item.key !== stoppedMember?.failureKey && remainingMember?.canReview && !uncertain && item.key === remainingMember.item.key
      ? { ...item, title: '核对' + name(item.locator.memberId) + '的未完成分工', originalQuestion: false }
      : { ...item, originalQuestion: false };
  }).sort((a, b) => Number(b.originalQuestion) - Number(a.originalQuestion));
  const canSupplementParent = ownerWorkLink?.currentRun && ownerWorkLink.stopping !== true &&
    (!teamHasEnded(current?.status) || current?.status === 'completed' && rows(state.tasks).some(task =>
      task.id === ownerWorkLink.taskId && task.raw?.runId === ownerWorkLink.runId && task.raw?.coordinatorEpoch === ownerWorkLink.epoch &&
      task.raw?.conversationId === ownerWorkLink.conversationId && task.raw?.controls?.canInput === true));
  const supplementByQuestion = canSupplementParent && onOpenWork && runSection !== 'details' && !messageDraft.requiresRecheck && !uncertain &&
    scopedFollowUps.some(item => item.originalQuestion) && acceptingOriginalParent(reading, state.tasks) &&
    ['tasks.open', 'ownerWork.supplement.read'].every(command => bridge.getState().capabilities.commands.includes(command)) ? {
      disabled: !canRead || materialBusy,
      open: () => {
        if (!reading?.viewHandle || !current) return;
        const documentEpoch = bridge.getDocumentEpoch(), resume = object(bridge.getState().view).resumeRevision;
        const stillAccepting = () => {
          const live = bridge.getState();
          return alive.current && !entered.current && !document.hidden && !backgrounded.current && ownsNativeWindow() && currentReading.current === reading &&
            documentEpoch === bridge.getDocumentEpoch() && resume === object(live.view).resumeRevision &&
            ['tasks.open', 'ownerWork.supplement.read'].every(command => live.capabilities.commands.includes(command)) &&
            !newerTeamReading(current, object(object(live.state.teamRuns)[current.id])) && acceptingOriginalParent(reading, live.state.tasks);
        };
        if (stillAccepting()) openOwnerWork('supplement', stillAccepting);
      },
    } : undefined;
  const canPrepareFollowUp = !!onOpenWork && ownerWorkLink?.savedResultForDraft === true && ownerWorkLink.currentRun === true &&
    ownerWorkLink.stopping === false && ownerWorkLink.teamRunId === current?.id && current?.status === 'completed' &&
    teamResult.original && Array.isArray(reading?.attention) && reading.attention.length === 0 &&
    ['conversation.select', 'composer.object.add'].every(command => bridge.getState().capabilities.commands.includes(command));
  // A saved original report can lead the result; unfinished/unverified work still leads with member evidence.
  const publishedOrdinaryResult = ordinaryAnswers && canPrepareFollowUp && !changed && !!reading &&
    ownerPublishedOrdinaryTeamResult(reading);
  const prepareFollowUp = () => {
    const original = currentReading.current, link = ownerWorkLink;
    if (!canPrepareFollowUp || !canRead || materialBusy || !link || !original?.viewHandle || !onOpenWork) return;
    leaveWithDrafts(() => {
      if (currentReading.current?.viewHandle !== original.viewHandle || !ownsNativeWindow() || document.hidden) return;
      void invoke('teams.readRun', { runId: link.teamRunId }, value => {
        const fresh = value.reading, next = fresh?.ownerWorkLink;
        const result = teamResultReading(fresh?.run, fresh?.messages);
        const same = fresh?.kind === 'run' && fresh.viewHandle && fresh.run?.id === link.teamRunId &&
          fresh.run.generation === original.run.generation && fresh.run.status === 'completed' && result.original &&
          result.messageId === teamResult.messageId && result.body === teamResult.body && Array.isArray(fresh.attention) && fresh.attention.length === 0 &&
          next?.savedResultForDraft === true && next.readOnly === true && next.currentRun === true && next.stopping === false &&
          next.teamRunId === link.teamRunId && next.taskId === link.taskId && next.runId === link.runId &&
          next.epoch === link.epoch && next.conversationId === link.conversationId;
        if (fresh?.kind === 'run' && fresh.viewHandle && fresh.run?.id === link.teamRunId) acceptReading(fresh);
        else release(fresh);
        if (!same) throw new Error('这次协作的原成果已变化，没有关联到草稿。');
        const teamId = label(fresh.run.teamId), originalRoster = classicAnswerOwner(original);
        const saved = catalogTeams.filter(team => team.id === teamId);
        // The returned Work is the result source. A still-saved named definition
        // is a separate optional choice, never a reason to reject the Work carry.
        const savedTeamId = catalogReady && originalRoster && originalRoster === classicAnswerOwner(fresh) &&
          Array.isArray(catalog.history) && !rows(catalog.history).some(row => row.id === link.teamRunId) &&
          saved.length === 1 && modelTeamKind(saved[0]) === 'ordinary' ? teamId : undefined;
        onOpenWork(next, 'followUp', savedTeamId);
      });
    });
  };
  const resultOrigin = (key = '', member?: ResultOrigin['member']): ResultOrigin | null => current
    ? { runId: String(current.id), key, member, scroll: root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0 } : null;
  const memberStage = (member: Pick<ResultMember, 'id' | 'round'>) => {
    const candidates = root.current?.querySelectorAll<HTMLElement>(
      `[data-team-member-id="${CSS.escape(member.id)}"] [data-team-progress-round="${member.round}"]`);
    const node = candidates?.length === 1 ? candidates[0] : undefined;
    return node?.closest('[hidden],[inert]') ? undefined : node;
  };
  const locatePrerequisite = (id: string, round: number) => {
    // The target is in the same held reading. Do not open a task, read another run or use a newer member with the same name.
    const shown = currentReading.current, live = bridge.getState();
    if (!showPrerequisites || !canRead || materialBusy || backgrounded.current || document.hidden || !ownsNativeWindow() ||
        !shown || shown !== reading || shownRunSection.current !== 'progress' ||
        bridge.getDocumentEpoch() !== prerequisiteDocument || object(live.view).resumeRevision !== prerequisiteResume ||
        newerTeamReading(shown.run, object(object(live.state.teamRuns)[shown.run.id]))) return;
    const target = memberStage({ id, round });
    if (!target) { setNotice('当前无法定位这项原前置分工，没有转到其他阶段。'); return; }
    target.focus({ preventScroll: true }); target.scrollIntoView({ block: 'center' });
  };
  resultBack.current = () => {
    if (!resultSource || runSection === 'result' || resultSource.runId !== current?.id || !workspaceActive || !ownsNativeWindow()) return false;
    const original = resultSource;
    chooseRunSection('result', () => {
      if (currentReading.current?.run?.id !== original.runId || !ownsNativeWindow()) return;
      const result = root.current?.querySelector<HTMLElement>('.pw-team-result');
      const row = Array.from(result?.querySelectorAll<HTMLElement>('[data-team-follow-up]') || [])
        .find(v => v.dataset.teamFollowUp === original.key && !v.closest('[hidden],[inert]'));
      if (!original.member || !row) setResultSource(saved => saved === original ? null : saved);
      if (original.member && !row) setNotice('已返回协作成果，刚才的答复不在当前清单。');
      const target = row || result?.querySelector<HTMLElement>('[data-team-result-heading]');
      const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
      if (scroll) scroll.scrollTop = original.scroll;
      if (original.member) row?.scrollIntoView({ block: 'nearest' });
      if (!original.member || !document.activeElement?.closest('[role="tablist"]')) target?.focus({ preventScroll: true });
    }); return true;
  };
  const openFollowUp = (item: TeamFollowUp) => {
    const original = currentReading.current;
    if (!canRead || materialBusy || !ownsNativeWindow() || original?.run?.id !== item.locator.runId || !original.viewHandle) return;
    const failed = stoppedMember?.failureKey === item.key ? stoppedMember : null;
    if (failed && canReviewStoppedMember && canMutate && !uncertain) {
      reviewStoppedMember(failed); return;
    }
    const origin = runSection === 'result' ? resultOrigin(item.key) : null;
    const resume = !failed && canMutate && remainingMember?.canReview && remainingMember.item.key === item.key ? remainingMember : null;
    void invoke('teams.readAttention', item.locator, reply => {
      const next = reply.reading;
      if (currentReading.current?.viewHandle !== original.viewHandle || !ownsNativeWindow()) { release(next); return; }
      if (next?.kind !== 'run' || !next.viewHandle || next.run?.id !== item.locator.runId) { release(next); throw new Error('没有核对到原协作，未转到其他工作。'); }
      const focus = teamFollowUpFocus(next, object(reply.focus), item.locator);
      acceptReading(next);
      if (focus.kind === 'changed') { setNotice(focus.message || '这项原事项已变化。'); return; }
      if (resume) {
        const originalDeliveries = teamResearchDeliveries(next.run, next.messages).filter(source => canOpenMemberResult || source.answer && openReading);
        const now = teamRemainingMember(next, originalDeliveries, state.tasks);
        if (focus.kind !== 'member' || focus.memberId !== item.locator.memberId || !now?.canReview || now.key !== resume.key) {
          setNotice('这项分工或原额度已变化。没有继续，请核对现在的进展。'); return;
        }
        reviewResult({ operation: 'resumeRemainingMember', viewHandle: next.viewHandle, target: now });
        return;
      }
      setResultSource(origin);
      showDecisionTarget(item.locator.runId,next.viewHandle,focus);
    });
  };
  const resumeReview: NonNullable<ReturnType<typeof teamRemainingMember>> | null =
    review?.operation === 'resumeRemainingMember' ? review.target : null;
  const resumeReviewCurrent = !!resumeReview && !!remainingMember?.canReview && canMutate &&
    review?.viewHandle === reading?.viewHandle && resumeReview.key === remainingMember.key;
  const confirmRemainingMember = () => {
    if (!resumeReviewCurrent || !resumeReview || entered.current || backgrounded.current || document.hidden ||
        currentReview.current !== review || currentReading.current?.viewHandle !== review?.viewHandle || !ownsNativeWindow()) return;
    closeReview();
    control('resume', resumeReview.item.locator.memberId, resumeReview);
  };
  const openedStopped=review?.operation==='resumePausedMember'||review?.operation==='resumeFailedMember'?review:null;
  const stoppedView=openedStopped?.view;
  const currentStoppedReading=(original:Data,documentEpoch:number,resumeRevision:unknown,section:unknown)=>{
    const live=bridge.getState();
    return !document.hidden&&!backgrounded.current&&ownsNativeWindow()&&currentReading.current===original&&shownRunSection.current===section&&
      bridge.getDocumentEpoch()===documentEpoch&&object(live.view).resumeRevision===resumeRevision&&
      !newerTeamReading(original.run,object(object(live.state.teamRuns)[original.run.id]));
  };
  const reviewStoppedMember=(target:StoppedTeamMemberCandidate)=>{
    const original=currentReading.current,doc=bridge.getDocumentEpoch(),resume=object(bridge.getState().view).resumeRevision,section=shownRunSection.current;
    const failed=!!target.failureKey,operation=failed?'resumeFailedMember':'resumePausedMember';
    if(!canMutate||!canReviewStoppedMember||!original?.viewHandle||original!==reading||currentReview.current||
       !(section==='progress'||failed&&section==='result')||!currentStoppedReading(original,doc,resume,section))return;
    void invoke('teams.reviewMember',{viewHandle:original.viewHandle,action:failed?'resumeFailed':'resumePaused',memberId:target.memberId},value=>{
      const shown=object(value.view);
      if(!currentStoppedReading(original,doc,resume,section)||value.operation!==operation||!label(value.reviewHandle)||value.viewHandle!==original.viewHandle||
         shown.executionAuthorized!==false||shown.runId!==target.runId||shown.generation!==target.generation||shown.revision!==target.revision||
         shown.memberId!==target.memberId||shown.memberGeneration!==target.memberGeneration||shown.retainedMemberId!==target.retainedMemberId||
         !label(shown.assignment)||!label(shown.memberReceiver)||!label(shown.memberSummaryReceiver)||!label(shown.synthesisReceiver)){
        release(value,'reviewHandle');throw new Error('原分工或窗口已变化，没有继续。');
      }
      reviewResult({...value,original,doc,resume,section,target});
    });
  };
  const stoppedReviewCurrent=!!openedStopped&&canMutate&&currentStoppedReading(openedStopped.original,openedStopped.doc,openedStopped.resume,openedStopped.section);
  const confirmStoppedMember=()=>{
    const original=openedStopped;
    if(!original||!stoppedReviewCurrent||entered.current||currentReview.current!==original||
       !currentStoppedReading(original.original,original.doc,original.resume,original.section))return;
    const target:StoppedTeamMemberCandidate=original.target;
    currentReview.current=null;setReview(null);setAcknowledged(false);
    void invoke('teams.confirm',{reviewHandle:original.reviewHandle},result=>{
      if(currentReading.current!==original.original||document.hidden||!ownsNativeWindow()||
         bridge.getDocumentEpoch()!==original.doc||object(bridge.getState().view).resumeRevision!==original.resume){release(result.reading);return;}
      const next=result.reading?.run,slot=rows(next?.members).find(row=>row.id===target.memberId);
      if(result.confirmed!==true||result.command!==original.operation||result.memberId!==target.memberId||!result.reading?.viewHandle||
         next?.id!==target.runId||next.generation!==target.generation||!(next.revision>target.revision)||
         slot?.generation!==target.memberGeneration+1||slot.status!=='queued'||
         !rows(slot.roundStates).some(row=>row.round===1&&row.status==='queued'&&row.researchTaskId===target.childTaskId)||
         !teamResearchDeliveries(next,result.reading.messages).some(row=>row.key===target.retainedDelivery)){
        release(result.reading);throw new Error('原分工是否已排队待核对，请刷新原协作，不要重复提交。');
      }
      acceptReading(result.reading);
      setNotice('只将这项分工排队。另一名成员的原答复保留，尚无新结果。');
    },()=>{
      // The old review is spent even when the result is unavailable. Do not turn an uncertain acknowledgement into another send.
      if(currentReading.current===original.original){release(original.original);setReading({...original.original,viewHandle:''});}
    });
  };
  const partialResult = runSection === 'result' && reading?.viewHandle && ownerWorkLink?.currentRun !== false &&
    deliveries.some(source => canOpenMemberResult || source.answer && openReading) ? teamPartialReading(current, followUps) : null;
  const rememberSourcesOpened = (id: string) => setShownMemberSources(shown => shown[id] ? shown : { ...shown, [id]: true });
  const openMemberSupplement = (item: TeamDirectedSupplement) => {
    const original = currentReading.current, link = original?.ownerWorkLink;
    if (!canRead || materialBusy || document.hidden || !ownsNativeWindow() || original?.kind !== 'run' ||
        original.viewHandle !== reading?.viewHandle) return;
    const rowsForId = rows(original.messages).filter(row => row?.messageId === item.messageId);
    const row = rowsForId.length === 1 ? rowsForId[0] : null;
    const same = row && (row.role !== 'parent_model' || link?.readOnly === true && link?.teamRunId === original.run.id &&
      link?.taskId === row.senderParentTaskId) ? teamSupplementReceipt(original.run, row) : null;
    if (!same || same.key !== item.key || same.memberId !== item.memberId) {
      setNotice('这条补充已变化，没有打开另一条。'); return;
    }
    focusMessage(same.messageId, same.memberId);
  };
  const openMemberAnswer=(item:TeamResearchDelivery,trigger:HTMLButtonElement)=>{
    const original=currentReading.current;
    if(!openReading||!item.answer||!canRead||materialBusy||document.hidden||!ownsNativeWindow()||
      original?.kind!=='run'||original.run?.id!==item.team||original.viewHandle!==reading?.viewHandle)return;
    const show=(same:TeamResearchDelivery, generation:number, awaitingReview=false)=>{
      if(!same.answer)return;
      if (runSection === 'result') {
        rememberSourcesOpened(item.team);
        setResultSource(Number.isSafeInteger(generation)?resultOrigin(same.answer.key, {
          id:same.memberId,generation,deliveryKey:same.key,round:same.round,
        }):null);
      }
      else setResultSource(saved=>saved?.runId===item.team&&saved.member?.deliveryKey===same.key?saved:null);
      openReading({kind:'message',title:same.name+' · '+same.answer.label+(awaitingReview?'（原工作尚未确认）':''),text:same.answer.text,returnFocus:trigger,
        ...(same.answer.plainText?{plainText:true as const}:{})});
    };
    const originalAnswer=item.answer;
    const find=(value:Data, classic=false)=>teamResearchDeliveries(value.run,value.messages,'current',classic)
      .find(row=>row.key===item.key&&row.answer?.key===originalAnswer.key&&row.answer.text===originalAnswer.text);
    if(originalAnswer.plainText!==true){
      const same=find(original);
      if(!same){setNotice('这份成员答复已变化，没有打开其他阶段。');return;}
      show(same,original.run.generation);return;
    }
    if(!classicOwner||classicAnswerOwner(original,true)!==classicOwner||classicAnswerFlight.current||entered.current)return;
    const documentEpoch=bridge.getDocumentEpoch(), resume=object(bridge.getState().view).resumeRevision,
      request=requestGeneration.current+1;
    const stillCurrent=()=>alive.current&&!backgrounded.current&&!document.hidden&&ownsNativeWindow()&&
      documentEpoch===bridge.getDocumentEpoch()&&resume===object(bridge.getState().view).resumeRevision&&
      shownRunSection.current===runSection&&currentReading.current===original&&requestGeneration.current===request;
    classicAnswerFlight.current=true;
    setOpeningMemberResult(true);
    void (async()=>{
      try{
        // The same fresh native owner read as the original task action. The
        // cached transcript supplies the identity to compare, not the answer to show.
        const reply=await invoke('teams.readRun',{runId:item.team}), next=reply?.reading;
        if(!stillCurrent()){release(next);return;}
        if(next?.kind!=='run'||!next.viewHandle||next.viewHandle===original.viewHandle||next.run?.id!==item.team){
          release(next);if(reply)setNotice('没有核对到原协作，未打开其他答复。');return;
        }
        const same=next.run.generation===original.run.generation&&classicAnswerOwner(next,true)===classicOwner?find(next,true):undefined;
        const checked=same&&await exactClassicAnswer(same).catch(()=>false);
        if(!stillCurrent()||newerTeamReading(next.run,object(object(bridge.getState().state.teamRuns)[item.team]))){
          release(next);return;
        }
        acceptReading(next);
        if(!checked||!same){setNotice('这份完整答复未能核对。可从原研究任务查看记录。');return;}
        show(same,next.run.generation,next.ownerWorkLink?.temporaryAnswersForReview===true);
      }catch(failure){
        if(stillCurrent())setError(failure instanceof Error?failure.message:'原答复暂时无法核对。');
      }finally{
        classicAnswerFlight.current=false;
        if(alive.current)setOpeningMemberResult(false);
      }
    })();
  };
  const selectRunTab = (section: RunSection) => {
    const source = resultSource, member = source?.member;
    if (section === 'result' && member && resultBack.current()) return;
    // A user who just read this member's actual answer can keep that member
    // selected when opening the existing progress tab. No task is opened.
    if (section === 'progress' && runSection === 'result' && source && member && canRead && !materialBusy &&
      source.runId === current?.id && member.generation === current.generation &&
      [...deliveries,...archivedDeliveries].some(row => row.memberId === member.id && row.key === member.deliveryKey && (row.key === source.key || row.answer?.key === source.key)) &&
      members.filter(row => row.id === member.id).length === 1) {
      chooseRunSection('progress', () => {
        if (!ownsNativeWindow() || document.hidden || currentReading.current?.run?.id !== source.runId ||
          currentReading.current.run.generation !== member.generation) return;
        const stage = memberStage(member);
        if (!stage) { setNotice('进展暂无法定位原阶段，没有选中另一项分工。'); return; }
        stage.scrollIntoView({ block: 'center' });
        if (!document.activeElement?.closest('[role="tablist"]')) stage.focus({ preventScroll: true });
      });
      return;
    }
    chooseRunSection(section, () => setResultSource(saved => saved === source ? null : saved));
  };
  const openMemberDelivery=(item:TeamResearchDelivery, section: 'progress' | 'result' = 'result', record?: WorkMemberAudit)=>{
    const original=currentReading.current;
    if(deliveryReturn.current||!canOpenMemberResult||!canRead||materialBusy||!ownsNativeWindow()||original?.run?.id!==item.team)return;
    const documentEpoch=bridge.getDocumentEpoch(), sameSection = () => shownRunSection.current === section && !document.hidden;
    const expiredRecord = () => {
      if(record&&alive.current&&sameSection()&&ownsNativeWindow()&&bridge.getDocumentEpoch()===documentEpoch&&currentReading.current?.run?.id===item.team)
        setNotice('原工作或这份答复已变化，没有打开其他分工。');
    };
    const open = () => {
      if(!sameSection()||!ownsNativeWindow()||currentReading.current?.viewHandle!==original.viewHandle||
        record&&(backgrounded.current||entered.current||!!deliveryReturn.current||classicAnswerFlight.current||
        bridge.getDocumentEpoch()!==documentEpoch||!bridge.getState().capabilities.commands.includes('teams.openResearchTask'))){
        expiredRecord();return;
      }
      const inspect = (next:Data|null, refreshed:boolean) => {
        if(!sameSection()||!ownsNativeWindow()||currentReading.current?.viewHandle!==original.viewHandle){if(refreshed)release(next);return}
        if(next?.kind!=='run'||!next.viewHandle||next.run?.id!==item.team){if(refreshed)release(next);throw new Error('未读取到原协作，没有打开其他分工。')}
        const deliveries=teamResearchDeliveries(next.run,next.messages,item.archivedVersion?'archived':'current');
        const same=deliveries.find(row=>row.key===item.key);
        if(!same){if(refreshed)acceptReading(next);setNotice('这份成员原件已变化，请重新核对。没有打开另一阶段。');return}
        const member=Number.isSafeInteger(next.run.generation)?{id:same.memberId,generation:next.run.generation,deliveryKey:same.key,round:same.round}:undefined;
        const ordinary = !same.archivedVersion && next.run.mode === 'collaborate' && next.run.definitionKind !== 'one_time' &&
          !next.run.nativeWorkProfileModels && !next.run.workPlan && !next.run.productionScope &&
          next.run.researchExecutionFormat === 'pocketworld.team-research-agent.v1' &&
          rows(next.run.members).every(slot => (slot.runner || 'model') === 'model' && (!slot.device || slot.device === 'phone'));
        const audit=ordinary&&(section==='result'||record)&&deliveries.filter(row=>row.key===same.key).length===1
          ? readMemberWorkAudit(original,next):undefined;
        if(refreshed)acceptReading(next);
        if(record&&(!audit||audit.source!==record.source||audit.rearm!==record.rearm||audit.summary!==record.summary||
          audit.resume!==record.resume||audit.request!==record.request)){expiredRecord();return}
        const origin:MemberDeliveryReturn={team:item.team,key:item.key,section,taskId:item.taskId,sourceHandle:next.viewHandle,
          scroll:root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop||0,entered:false,ordinary,documentEpoch:bridge.getDocumentEpoch(),
          member,work:audit,...(record?{record:true as const}:{})};
        if (section === 'progress') setResultSource(saved=>saved?.runId===item.team&&saved.member?.deliveryKey===same.key?saved:null);
        else if(same.archivedVersion)setShownArchives(old=>({...old,[item.team]:true}));
        else rememberSourcesOpened(item.team);
        deliveryReturn.current=origin;
        if(record){setError('');setNotice('');}
        setOpeningMemberResult(true);
        void run('teams.openResearchTask',{viewHandle:next.viewHandle,memberId:same.memberId,round:same.round,taskId:same.taskId,outputFingerprint:same.hash,
          ...(same.archivedVersion?{archivedVersion:same.archivedVersion}:{})}).then(value=>{
          // A very quick close can occur before React observes the native task view.
          if(deliveryReturn.current!==origin)return;
          const opened=object(value);
          if(opened.taskId===origin.taskId&&opened.readOnly===true&&opened.executionAuthorized===false){
            origin.entered=true;
            if(origin.work)origin.work.receipt=true;
          }
          else if(!origin.entered&&object(bridge.getState().view).taskId!==origin.taskId)throw new Error('没有核对到原任务的阅读回执。');
        }).catch(failure=>{
          if(deliveryReturn.current===origin)deliveryReturn.current=null;
          if(alive.current&&!backgrounded.current&&ownsNativeWindow()&&currentReading.current?.run?.id===item.team)
            setError(failure instanceof Error?failure.message:'原答复暂时无法打开。');
        }).finally(() => { if (alive.current) setOpeningMemberResult(false); });
      };
      // Native open will still verify the exact child run/epoch/fingerprint.
      // The stage record doesn't need an extra Team read on the way in.
      if(record)inspect(original,false);
      else void invoke('teams.readRun',{runId:item.team},reply=>inspect(reply.reading,true));
    };
    if(!record)leaveWithDrafts(open);
    else if(materialBeforeLeave.current)materialBeforeLeave.current(open);
    else open();
  };
  useLayoutEffect(()=>{
    const origin=deliveryReturn.current;if(!origin)return;
    const view=object(bridge.getState().view);
    if(view.taskId===origin.taskId){origin.entered=true;return}
    if(view.taskId||view.section&&view.section!=='teams'){deliveryReturn.current=null;return}
    // The native task retires the source handle. Restore only after a fresh
    // reading has committed, not while the last team's DOM is still on screen.
    if(!origin.entered||!workspaceActive||!ownsNativeWindow()||busy||openingMemberResult||changed||!reading?.viewHandle||
      reading.viewHandle===origin.sourceHandle||document.hidden)return;
    deliveryReturn.current=null;
    if(current?.id!==origin.team||runSection!==origin.section)return;
    const fromProgress = origin.section === 'progress';
    const area=root.current?.querySelector<HTMLElement>(fromProgress ? '[data-team-section="members"]' : '.pw-team-result');
    const selector = fromProgress ? '[data-team-progress-delivery]' : '[data-team-delivery]';
    const sameGeneration = !!origin.member && current.generation === origin.member.generation;
    const visible = (row:HTMLButtonElement) => !row.disabled&&!row.closest('[hidden],[inert]');
    let exact:HTMLButtonElement|undefined;
    if(origin.record){
      const member=origin.member;
      if(member&&sameGeneration&&teamResearchDeliveries(current,reading.messages).filter(row=>row.key===origin.key).length===1){
        const records=Array.from(area?.querySelectorAll<HTMLButtonElement>(
          `[data-team-member-id="${CSS.escape(member.id)}"] [data-team-source-task="${CSS.escape(origin.taskId)}"][data-team-record-round="${member.round}"]`)||[]).filter(visible);
        if(records.length===1)exact=records[0];
      }
    }else exact=Array.from(area?.querySelectorAll<HTMLButtonElement>(selector)||[])
      .find(row=>(fromProgress?row.dataset.teamProgressDelivery:row.dataset.teamDelivery)===origin.key&&visible(row));
    const stage = fromProgress && origin.member && sameGeneration ? memberStage(origin.member) : undefined;
    const audit=origin.work,returnHandle=audit?.returnHandle;
    if((!fromProgress||origin.record)&&exact&&sameGeneration&&audit&&returnHandle&&returnHandle===reading.viewHandle&&!workResultReturn.current){
      const verified=()=>{
        const shown=currentReading.current;
        return alive.current&&!backgrounded.current&&shownRunSection.current===origin.section&&shown?.viewHandle===returnHandle&&
          memberAuditWindow(origin,bridge.getState())==='returned'&&shown?.run?.generation===origin.member?.generation&&
          originalWorkTeamResult(shown,audit.source)===audit.summary;
      };
      if(verified()){
        workResultReturn.current=audit.rearm(verified);
        if(origin.record&&workResultReturn.current)setNotice('已返回原成员阶段，可返回原工作。');
      }
    }
    if (!fromProgress) setResultSource(exact&&origin.member&&Number.isSafeInteger(current.generation)
      ? {runId:origin.team,key:origin.key,scroll:origin.scroll,member:{...origin.member,generation:current.generation}} : null);
    const scroll=root.current?.closest<HTMLElement>('.pw-secondary-scroll');if(scroll)scroll.scrollTop=origin.scroll;
    // A summary or a newer receipt may have arrived while its task was open.
    // Losing the old button is not permission to select the new receipt.
    const target = exact || stage;
    if(!exact)setNotice(stage?'已返回原成员阶段；刚才的答复不在这个入口，没有打开另一份。':'刚才的答复已不在当前列表，没有转到另一份。');
    target?.scrollIntoView({block:'nearest'});
    (target||(fromProgress?area:area?.querySelector<HTMLElement>('[data-team-result-heading]')))?.focus({preventScroll:true});
  },[workspaceActive,busy,openingMemberResult,changed,reading?.viewHandle,current?.id,runSection,state.webWorkspaceResumeRevision]);
  const openAwaitingReview = () => {
    const original = currentReading.current, owner = original?.taskId, id = current?.id;
    if (!canRead || materialBusy || !id || !owner || original?.run?.id !== id || current.status !== 'awaiting_review') return;
    leaveWithDrafts(() => {
      if (!ownsNativeWindow() || currentReading.current?.viewHandle !== original.viewHandle) return;
      void invoke('teams.readRun', { runId: id }, reply => {
        const next = reply.reading;
        if (!ownsNativeWindow() || currentReading.current?.viewHandle !== original.viewHandle) { release(next); return; }
        if (next?.kind !== 'run' || !next.viewHandle || next.run?.id !== id) { release(next); throw new Error('未能核对原协作。'); }
        acceptReading(next);
        if (next.run.status !== 'awaiting_review' || next.taskId !== owner) { setNotice('原协作的审阅状态或任务关联已变化，没有打开其他任务。'); return; }
        // This is the already-authorized private owner task, even outside the
        // recent task list. Opening its record neither approves nor resumes it.
        void run('tasks.open', { id: owner, searchQuery: '' }).catch(failure => {
          if (alive.current && !backgrounded.current && ownsNativeWindow() && currentReading.current?.run?.id === id)
            setError(failure instanceof Error ? failure.message : '原协作任务暂不可读取。');
        });
      });
    });
  };
  const jumpToRunSection = (section: 'messages' | 'members' | 'materials' | 'compose') => {
    chooseRunSection(section === 'members' ? 'progress' : 'details', () => {
      if (section !== 'members') revealDetail(section === 'materials' ? 'materials' : 'conversation');
      if (section === 'compose') setComposeOpen(true);
      requestAnimationFrame(() => {
        const target = root.current?.querySelector<HTMLElement>(`[data-team-section="${section}"]`);
        target?.scrollIntoView({ block: 'start' });
        if (section === 'compose') target?.querySelector<HTMLTextAreaElement>('textarea')?.focus({ preventScroll: true });
        else target?.focus({ preventScroll: true });
      });
    });
  };
  const editorMemberIssue = (member: Data): string => {
    if (!label(member.name).trim()) return '填写成员名称';
    if (member.name.length > 48) return '成员名称最多 48 字';
    if (!label(member.role).trim()) return '填写成员职责';
    if (member.role.length > 1200) return '成员职责最多 1200 字';
    if (label(member.model).length > 200) return '模型 ID 最多 200 字';
    if (label(member.assignment).length > 2000) return '具体分工最多 2000 字';
    if (member.runner === 'phone_agent') return !label(member.assignment).trim() ? '填写手机具体分工'
      : member.assignment.length > 2000 ? '手机分工最多 2000 字' : !teamPhoneTargetKey(member.device) ? '选择工作手机' : '';
    if (member.runner === 'model') return '';
    if (!member.device?.profileId || !member.device?.environmentId) return '选择电脑与工作环境';
    if (!isTeamHarness(member.runner)) return '选择成员执行器';
    if (member.runner !== 'codex_app_server' && member.externalExecutionRiskAcknowledged !== true) return '确认电脑执行权限与费用边界';
    return externalReady[member.id] ? '' : '核对这名成员的执行器';
  };
  const incompleteMembers = rows(editor?.members).filter(member => !!editorMemberIssue(member));
  const evidenceDebate = editor?.mode === 'debate' && !(definition?.mode === 'debate' && definition.discussionFormat === 'classic');
  const discussionError = evidenceDebate && (rows(editor?.members).length < 2 || rows(editor?.members).some(member => member.runner !== 'model' || rows(member.dependsOn).length > 0))
    ? '取证与讨论需要至少两名独立的分析成员。电脑、手机或有前置依赖的分工请使用“共同工作”。' : '';
  let editorPayload: Data | null = null, productionError = '';
  if (editor) try { const { goal: _goal, rounds: _rounds, nameEdited: _nameEdited, pendingStartTeamId: _pendingStartTeamId, pendingStartSince: _pendingStartSince, ...definitionDraft } = editor; editorPayload = teamDefinitionPayload(definitionDraft, catalog); }
  catch (failure) { productionError = failure instanceof Error ? failure.message : String(failure); }
  const ordinaryDefinitionEditor = editorPurpose === 'definition' && editor?.mode === 'collaborate' &&
    rows(editor.members).every(member => member.runner === 'model' && (!member.device || member.device === 'phone'));
  const reviewDefinitionBlocked = !editor || busy || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck ||
    !definitionReady || !editorPayload || !editor.name.trim() || editor.name.length > 80 || incompleteMembers.length > 0 || !!discussionError;
  const refreshReading = <IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="刷新当前记录" title="刷新当前记录" disabled={busy || materialBusy} onClick={() => current ? readRun(current.id) : definition ? void invoke('teams.readDefinition', { id: definition.id }, acceptReading) : load()} />;
  const startRecoveries = [
    ...(formDraft.requiresRecheck && quickStartPending ? [{ kind: 'quick' as const, pending: quickStartPending }] : []),
    ...(startDraft.requiresRecheck && savedStartPending ? [{ kind: 'saved' as const, pending: savedStartPending }] : []),
  ];
  const draftRecovery = (formDraft.requiresRecheck || startDraft.requiresRecheck || memberFormDraft.requiresRecheck) && <section className="pw-team-draft-recovery" aria-label="核对上次操作">
    <Button variant="secondary" disabled={busy} onClick={load}>读取协作与编组核对结果</Button>
    {startRecoveries.map(({ kind, pending }) => {
      const candidates = catalogRuns.filter(row => matchesPendingStart(row, pending));
      const exactReading = !!current && !!reading?.viewHandle && matchesPendingStart(current, pending) && candidates.length === 1;
      return <div className="pw-team-start-recovery" key={kind}><p className="pw-team-recovery-goal">原目标：{pending.goal}</p><p>刚才的启动结果待核对，目标与分工已保留。{draftCatalogChecked ? candidates.length === 0 ? '暂未找到对应协作，请重新读取；不会再次启动。' : candidates.length > 1 ? '找到多条可能相关的协作，需要继续核对，暂不解除待确认状态。' : '找到一条相关协作，请打开核对目标与分工。' : ''}</p>
        {candidates.map(row => <Button key={row.id} variant="ghost" trailingIcon={IconChevronRight} disabled={busy} onClick={() => (kind === 'quick' ? formDraft : startDraft).guard(() => { setEditor(null); readRun(row.id); })}>查看：{teamGoalTitle(row.goal, row.teamName)} · {teamTimeLabel(row.createdAt)}</Button>)}
        {exactReading && <Button variant="secondary" disabled={busy || changed} onClick={() => { (kind === 'quick' ? formDraft : startDraft).discard(() => { if (kind === 'quick') { setQuickStartPending(null); setEditor(null); } else { setSavedStartPending(null); startInputs.current.delete(pending.teamId); } setNotice('已核对并打开这次协作，未重新启动。'); }); }}>已核对，这是刚才开始的协作</Button>}
      </div>;
    })}
    {(formDraft.requiresRecheck && editorPurpose === 'definition' || memberFormDraft.requiresRecheck) && <Button variant="secondary" disabled={busy || !draftCatalogChecked} onClick={() => { if (editorPurpose === 'definition') formDraft.clearSubmitted(); memberFormDraft.clearSubmitted(); setDraftCatalogChecked(false); }}>已核对编组或成员改动，继续编辑</Button>}
    {(startDraft.requiresRecheck && !savedStartPending || formDraft.requiresRecheck && editorPurpose === 'start' && !quickStartPending) && <p className="pw-field-hint">这份旧草稿没有保存启动标识，请先查看协作记录；启动结果尚未确定。</p>}
  </section>;
  const directoryContent = <>
    {showCollectionTools && <SearchField fieldClassName="pw-collaboration-search" shape="rounded" className="pw-secondary-search" data-input-size="medium" aria-label={tab === 'runs' ? '搜索协作' : '搜索编组'} placeholder={tab === 'runs' ? '查找目标或编组' : '查找编组'} value={query} onChange={setQuery} />}
    {tab === 'teams' && entries.length > 0 && <p className="pw-team-directory-caption">常用团队，随时交办。</p>}
    {entries.length > 0 ? <div className="pw-collaboration-directory">{directoryGroups.filter(group => group.entries.length > 0).map(group => <section className="pw-collaboration-group" key={group.id} aria-label={group.title}>
      {tab === 'runs' && <header className="pw-collaboration-group-heading"><h3>{group.title}</h3><span>{group.entries.length} 项已读取</span></header>}
      <div className="pw-object-list" data-team-directory-kind={tab}>{group.entries.map(row => <Button key={row.id} data-team-list-id={row.id} data-motion-key={`directory:${row.id}`} variant="ghost" trailingIcon={IconChevronRight} className="pw-object-row pw-team-directory-row h-auto whitespace-normal" disabled={busy} onClick={() => { listSelection.current[tab] = row.id; if (tab === 'runs') readRun(row.id, true); else readDefinition(row.id); }}>
        <span className="pw-object-row-copy"><span className="pw-object-row-title">{tab === 'runs' ? teamGoalTitle(row.goal, row.teamName || '这次协作') : row.name || '未命名编组'}</span>
          <small className="pw-team-directory-meta">{tab === 'runs' ? <><span className="pw-team-state" data-state={row.status}>{status(row.status)}</span>{row.teamName && <span>{row.teamName}</span>}{teamTimeLabel(row.updatedAt || row.createdAt) && <time>{teamTimeLabel(row.updatedAt || row.createdAt)}</time>}</> : `${rows(row.members).length} 名成员 · ${row.mode === 'produce' ? '项目文件协作' : row.mode === 'debate' ? '取证与讨论' : '共同工作'}`}</small>
          {tab === 'teams' && <span className="pw-team-roster-preview">{rows(row.members).slice(0, 4).map(member => <span key={member.id}><TeamMemberAvatar member={member} size={28} />{teamMemberLabel(member, rows(row.members))}</span>)}{rows(row.members).length > 4 && <span className="pw-team-roster-more">+{rows(row.members).length - 4} 人</span>}</span>}
        </span>
      </Button>)}</div>
    </section>)}</div> : <section className="pw-team-empty" aria-label="协作目录提示" role={directoryState === 'loading' ? 'status' : undefined}>
      <h2>{directoryState === 'loading' ? '正在读取协作' : directoryState === 'unconfirmed' ? '暂时未能读取协作' : directoryState === 'unread' ? '协作尚未读取' : directoryState === 'filtered' ? '没有匹配的记录' : tab === 'teams' ? '还没有保存编组' : '还没有协作记录'}</h2>
      <p>{directoryState === 'loading' || directoryState === 'unread' ? '已保存的目标、分工和成果会显示在这里。' : directoryState === 'unconfirmed' ? '重新读取后继续，已有工作与草稿保留。' : directoryState === 'filtered' ? '换一个关键词，或清除搜索查看已读取的记录。' : tab === 'teams' ? '常用分工可以保存在这里。直接把目标交给管家，也能按这次工作安排成员。' : '把想完成的事告诉管家。需要多人分工时，协作会留在那件工作里，记录也可以在这里查看。'}
      </p>
      {!showDirectoryTools && (directoryState === 'unread' || directoryState === 'unconfirmed') && <Button variant="secondary" disabled={busy} onClick={load}>重新读取</Button>}
      {directoryState === 'filtered' && <Button variant="secondary" onClick={() => setQuery('')}>清除搜索</Button>}
    </section>}
  </>;
  return <section ref={root} className="pw-team-workspace pw-depth-collaboration" data-team-layer={reading ? 'detail' : 'list'} data-team-view={viewKey} inert={!workspaceActive} aria-busy={!workspaceActive || busy}>
    <div className="pw-team-loading">{(!workspaceActive || busy) && <ProgressBar aria-label={workspaceActive ? '正在读取原团队回执' : '正在进入团队工作区'} isIndeterminate showValueLabel={false} size="sm" />}</div>
    {error && error !== alreadyShownError && !editor && !memberDraft && !review && !phoneSelection && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}{notice && <MotionFeedback identity={notice} className="pw-field-hint" role="status">{notice}</MotionFeedback>}
    {reading && <nav className="pw-team-location" aria-label="协作记录导航"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={backFromRun}>{verifiedWorkResultReturn() ? '返回原工作' : current ? '协作记录' : '全部编组'}</Button></nav>}
    {!reading && <div className="pw-team-directory-intro"><div><h2>{choosingOriginalTeam ? replacingDraftTeam ? '更换协作编组' : '为原对话选编组' : '一起完成'}</h2><p>{choosingOriginalTeam
      ? replacingDraftTeam ? <>当前是 <bdi>{replacingDraftTeam.label}</bdi>。只有确认更换才会改动原草稿。</> : '只关联到原对话草稿，不发送，也不启动协作。'
      : onAskButler && !originalWorkDraft && !entries.length && directoryState === 'empty' ? '先写目标，不用先建编组。管家提出分工后，在原工作里核对。' : '分工、进展与成果，都在这里。'}</p></div>
      {offerFirstOriginalTeam ? <Button variant="secondary" leadingIcon={IconAdd} disabled={busy || formDraft.loading || !!editor} onClick={() => beginEditor(null, 'definition')}>新建编组</Button>
        : onAskButler && !originalWorkDraft && <Button variant="secondary" trailingIcon={IconChevronRight} disabled={busy} onClick={() => askButler()}>{!entries.length && directoryState === 'empty' ? '到管家写目标' : '交给管家'}</Button>}</div>}
    {!reading && (showDirectoryTools ? <Tabs selectedKey={tab} onSelectionChange={value => { if (value === 'runs' || value === 'teams') switchTab(value); }} className="pw-team-tabs">
      <div className="pw-object-toolbar pw-material-navigation"><TabList aria-label="协作记录与配置"><Tab id="runs">协作记录</Tab><Tab id="teams">我的团队</Tab></TabList>
        <div className="pw-team-directory-actions">{showDirectoryActions && tab === 'teams' && !offerFirstOriginalTeam && <IconButton className="pw-utility-icon" icon={IconAdd} aria-label="新建编组" title="新建编组" disabled={busy || !catalogReady} onClick={() => beginEditor(null, 'definition')} />}<IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="刷新协作记录与编组" title="刷新协作记录与编组" disabled={busy} onClick={load} /></div>
      </div>
      <TabPanel id={tab}>{directoryContent}</TabPanel>
    </Tabs> : directoryContent)}
    {!reading && !editor && <Accordion variant="divided" className="pw-team-recovery-access"><AccordionItem id="old-drafts" title="旧草稿与启动核对"><p className="pw-field-hint">以前未发出的目标与分工仍可查看。启动结果未确认的协作，需要先核对原记录。</p><Button variant="ghost" trailingIcon={IconChevronRight} disabled={busy || formDraft.loading} onClick={() => beginEditor(null, 'start')}>查看旧协作草稿</Button></AccordionItem></Accordion>}
    {!editor && !memberDraft && draftRecovery}
    {!editor && !definition && <FormDraftNotice draft={formDraft} />}
    {definition && <article className="pw-team-reading pw-team-definition"><header className="pw-object-toolbar pw-team-definition-heading"><div><h2 tabIndex={-1}>{definition.name}</h2><p>{definition.mode === 'produce' ? '原项目文件协作' : definition.mode === 'debate' ? '取证与讨论' : hasPhoneMembers ? '手机分工与共同工作' : '共同分析'} · {definitionMembers.length} 名成员</p></div>{refreshReading}</header>
      {originalWorkDraft && !(replacingDraftTeam ? replacementDefinition : carryToOriginalDraft) &&
        <p className="pw-field-hint">这里可以查看原编组。此入口只关联本机模型协作的普通编组。</p>}
      {!modelTemplate && <section className="pw-team-handoff" aria-label="使用编组安排工作">
        {!originalWorkDraft && <p>这份编组保存常用成员与职责。把目标交给管家后，再按实际工作安排分工与范围。</p>}
        {onAskButler && !originalWorkDraft && <Button trailingIcon={IconChevronRight} disabled={busy || materialBusy || startDraft.loading || startDraft.requiresRecheck || !!startDraft.conflict || goal.length > 8000} onClick={() => askButler(goal.trim() ? { goal } : {})}>回管家安排协作</Button>}
        {(goal.trim() || startDraft.savedAt || startDraft.loading || startDraft.error || startDraft.conflict || startDraft.requiresRecheck) && <Accordion variant="divided" className="pw-team-saved-goal"><AccordionItem id="saved-goal" title="这份编组的旧目标草稿" description={goal.trim() ? teamGoalTitle(goal) : undefined}>
          <FormDraftNotice draft={startDraft} />
          <TextAreaField label="保留的目标" value={goal} onChange={setGoal} maxLength={8000} rows={3} autoResize maxRows={8} resize="none" isDisabled={busy || startDraft.requiresRecheck} />
          <p className="pw-field-hint">{originalWorkDraft ? '这份旧目标仍保留在编组，不带到原草稿。' : '目标会带回管家输入区，由你继续交办；这份草稿与分工保留。'}</p>
        </AccordionItem></Accordion>}
      </section>}
      <section className="pw-team-members"><header className="pw-team-members-heading"><h3>{modelKind === 'ordinary' ? '编组默认分工' : ordinaryTemplate ? '已保存的成员与职责' : '成员与职责'}</h3><Button variant="ghost" leadingIcon={IconEdit} disabled={busy || !definitionReady} onClick={() => beginEditor(definition)}>编辑编组</Button></header>
        <Accordion variant="divided" className="pw-team-definition-member-list">{definitionMembers.map(member => {
          const ordinary = modelKind === 'ordinary', savedAssignment = ordinary ? label(member.assignment).trim() : '';
          const savedRole = ordinary ? label(member.role).trim() : '', memberId = label(member.id);
          const predecessors: Data[][] = ordinary && Array.isArray(member.dependsOn) ? member.dependsOn.map((id: unknown) =>
            definitionMembers.filter(row => row.id === id && row.id !== member.id && !!label(row.name).trim())) : [];
          const prerequisites = predecessors.length && predecessors.every(matches => matches.length === 1) ?
            predecessors.map(([row]) => teamMemberLabel(row, definitionMembers)).join('、') : '';
          return <AccordionItem key={member.id} id={member.id} data-motion-key={`definition-member:${member.id}`}
            title={<TeamMemberIdentity member={member} name={teamMemberLabel(member, definitionMembers)} detail={member.id === definition.leaderId ? teamRoleLabel(definition) : undefined} size={32} />}
            description={<><span className="pw-team-definition-duty" dir="auto">{ordinary ? savedAssignment || '默认分工未列出' : ordinaryTemplate ? member.role || '职责待核对' : modelTemplate ? member.assignment || member.role : member.role}</span>
              {prerequisites && <small className="pw-team-definition-duty">前置成员：<bdi>{prerequisites}</bdi></small>}</>}>
            <div className="pw-team-detail-body">
              {ordinary ? <>{savedAssignment && <p className="pw-team-goal-copy" dir="auto">{savedAssignment}</p>}
                {savedRole && savedRole !== savedAssignment && <small dir="auto">职责：{savedRole}</small>}</>
                : <><p>{member.role}</p>{modelTemplate && member.assignment && member.assignment !== member.role && <p className="pw-team-goal-copy" dir="auto">具体分工：{member.assignment}</p>}</>}
              {member.runner === 'phone_agent' ? <><small>{teamPhoneTargetLabel(member.device, phoneTargets)}</small><p>{member.assignment}</p></>
                : member.runner === 'model' || !member.runner ? <small>{ordinaryTemplate ? '编组模型：' : '模型：'}<bdi>{ordinaryTemplate ?
                  teamPlannedModel(member,'未指定型号，以本次工作审阅为准') : (memberModelLabel(member) || '沿用实际启动时的主模型')}</bdi>{!ordinaryTemplate && ' · 本机模型连接'}</small>
                : <><small>执行器：{harnessWorkerLabel(undefined, member.runner)} · 模型：{member.model || '执行器默认'}</small><p className="pw-field-hint">电脑：{computerLabel(member)}</p>
                  <p className="pw-field-hint">开始协作前，核对这份已保存配置的实际执行器。</p><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.probeMember', { viewHandle: reading!.viewHandle, memberId: member.id }, value => setMemberProbes(old => ({ ...old, [`${definition.id}:${member.id}`]: { definition: JSON.stringify(member), value: object(value.result || value) } })))}>核对原执行器</Button>
                  {memberProbes[`${definition.id}:${member.id}`]?.definition === JSON.stringify(member) && <HarnessEvidence probe={memberProbes[`${definition.id}:${member.id}`].value} id={`saved-member-evidence-${member.id}`} />}
                </>}
              {ordinary && canEditSavedMember && memberId && definitionMembers.filter(row => row.id === memberId).length === 1 &&
                <Button variant="ghost" leadingIcon={IconEdit} disabled={busy || materialBusy || !definitionReady || formDraft.loading}
                  onClick={event => { event.currentTarget.focus({ preventScroll: true }); beginEditor(definition, 'definition', undefined, memberId); }}>编辑这名成员</Button>}
            </div>
          </AccordionItem>;
        })}</Accordion>
      </section>
      {replacingDraftTeam && replacementDefinition && onAskButler && <section className="pw-team-handoff" aria-label="更换协作编组">
        {definition.id===replacingDraftTeam.id ? <p>这是原草稿当前选择的编组。</p> : <>
          <Button trailingIcon={IconChevronRight} disabled={busy || materialBusy || !definitionReady} onClick={carryModelTemplate}>换成这个编组</Button>
          <p>只更换原草稿的编组，保留文字和附件，不发送。开始协作仍需另行审阅。</p>
        </>}
      </section>}
      {!replacingDraftTeam && modelTemplate && onAskButler && (!originalWorkDraft || carryToOriginalDraft) && <section className="pw-team-handoff" aria-label="使用编组安排工作">
        {ordinaryTemplate && <div className="pw-team-template-preparation"><h3>准备本次协作</h3><p role="status">{templatePreparation}</p></div>}
        <TextAreaField className="pw-team-saved-goal" label={carryToOriginalDraft ? '编组保留的目标' : '这次想完成什么'} value={goal} onChange={setGoal} maxLength={8000} rows={2} autoResize maxRows={8} resize="none"
          isDisabled={busy || materialBusy || templateDraftBlocked} hint={carryToOriginalDraft ? '这份目标只留在编组，不合并到原对话。' : '目标先留在这份编组的草稿里。留空只带编组，不改管家的已有文字。'} />
        {(startDraft.loading || startDraft.saving || startDraft.error || startDraft.stale || startDraft.conflict || startDraft.requiresRecheck || startDraft.savedAt > 0) && <FormDraftNotice draft={startDraft} />}
        <Button trailingIcon={IconChevronRight} disabled={busy || materialBusy || templateDraftBlocked || goal.length > 8000} onClick={carryModelTemplate}>
          {carryToOriginalDraft ? '在原对话关联这个编组' : goal.trim() ? '带目标和编组回管家' : '带编组回管家'}</Button>
        <p>{carryToOriginalDraft ? '只关联编组，原输入与工作引用不变，不发送。' : ordinaryTemplate ? '带回只预填管家草稿，不发送。你提交目标后，再到同一件工作里审阅具体分工。' : '回到管家可继续编辑，确认发送后才交办。实际分工仍由你审阅。'}</p>
      </section>}
      <div className="pw-team-definition-settings"><Accordion variant="divided"><AccordionItem id="definition-management" title="管理编组"><Button variant="ghost" disabled={busy || !definitionReady} onClick={() => void invoke('teams.reviewRemove', { viewHandle: reading!.viewHandle }, reviewResult)}>移除编组</Button></AccordionItem></Accordion></div>

    </article>}
    {current && !acceptedSummaryRetired && <article className="pw-team-reading pw-team-run">
      <header className="pw-object-toolbar pw-team-run-heading"><div><p className="pw-team-run-origin">{pinnedSummary ? '验收时选用的原汇总' : ownerWorkLink ? '管家工作中的协作' : '历史协作'}{current.teamName ? ` · ${current.teamName}` : ''}</p><h2 tabIndex={-1}>{teamGoalTitle(current.goal, current.teamName)}</h2><div className="pw-team-run-meta"><span className="pw-team-state" data-state={current.status}>{current.status==='cancelled'?(pendingFinal?'已停派，原执行待核对':'已停止后续派发'):current.status==='completed'&&(pendingFinal||deliveryWorkers?.stage==='stopped_or_waiting')?'协调已结束，交付待核对':current.status==='awaiting_review'&&deliveryWorkers?.stage==='in_progress'?'文件交付处理中':status(current.status)}</span>{teamPhaseLabel(current.phase) && <span>{teamPhaseLabel(current.phase)}</span>}{teamProgressLabel(current) && <span>{teamProgressLabel(current)}</span>}</div></div>{!pinnedSummary&&refreshReading}</header>
      {members.find(member => member.id === current.leaderId) && <div className="pw-team-owner"><span>{teamRoleLabel(current)}</span><TeamMemberIdentity member={members.find(member => member.id === current.leaderId)!} name={name(current.leaderId)} size={28} /></div>}
      {changed && <p className="pw-team-reading-notice" role="status">正在更新进展，阅读位置与未发送内容保留。</p>}
      {workspaceActive && !busy && !reading?.viewHandle && <p className="pw-team-reading-notice" role="status">请刷新记录后继续操作，未发送内容保留。</p>}
      <div className="pw-team-run-controls">
        <div className="pw-team-work-actions">{ownerWorkLink && onOpenWork ? <><Button variant="ghost" trailingIcon={IconChevronRight}
          disabled={busy || materialBusy || !!exactReportReview&&!canRead} onClick={exactReportReview?reviewOriginalConclusion:()=>openOwnerWork('overview')}>
          {exactReportReview?'核对本轮结论':'查看原工作'}</Button>{canSupplementParent && !supplementByQuestion &&
          <Button variant="secondary" disabled={!canRead || materialBusy} onClick={() => openOwnerWork('supplement')}>补充要求</Button>}</> : <span className="pw-team-history-label">保留原目标与执行记录</span>}</div>
        <div className="pw-team-current-actions">
          {!hasWorkPlan && !remainingMember && !stoppedMember && !noWorkRetryForMissingText && (teamCanResume(current.status) || current.phase === 'intervention' || current.phase === 'awaiting_member' && currentMembers.length > 0) && <Button leadingIcon={IconPlay} disabled={!canMutate} onClick={() => control('resume')}>继续协作</Button>}
          {current.status !== 'cancelled' && <ContextMenuTrigger key={current.id}>{({ open }) => <><IconButton icon={IconMore} aria-label="暂停或停止协作" disabled={!canControl} onClick={event => open(event.currentTarget)} /><ContextMenu aria-label="本次协作操作" onAction={key => { if (key === 'pause' && canControl) control('pause'); if (key === 'stop' && canControl) reviewResult({ operation: 'cancel', viewHandle: reading!.viewHandle, runId: current.id }); }}>{teamCanPause(current.status) && <ContextMenuItem id="pause" icon={IconPause}>暂停协作</ContextMenuItem>}<ContextMenuItem id="stop" icon={IconStop}>停止这次协作</ContextMenuItem></ContextMenu></>}</ContextMenuTrigger>}
        </div>
      </div>
      {ownerWorkLink && !ownerWorkLink.currentRun && <p className="pw-team-context-note">这是原工作较早一次执行的协作记录，可查看当时进展与成果。</p>}
      <TeamDeliveryWorkers reading={deliveryWorkers} stopped={current.status==='cancelled'} disabled={!canRead||materialBusy||!bridge.getState().capabilities.commands.includes('teams.openDeliveryWorker')} open={row=>{
        const handle=reading?.viewHandle;if(!handle||!canRead||!ownsNativeWindow())return;
        leaveWithDrafts(()=>{if(currentReading.current?.viewHandle!==handle)return;void run('teams.openDeliveryWorker',{viewHandle:handle,taskId:row.taskId,runId:row.runId,epoch:row.epoch}).catch(e=>setError(e instanceof Error?e.message:String(e)));});
      }}/>
      {reading?.taskLookupNotice && <p className="pw-field-hint" role="status">{reading.taskLookupNotice}</p>}
      <Tabs selectedKey={runSection} onSelectionChange={value => { if (value === 'progress' || value === 'result' || value === 'details') selectRunTab(value); }} className="pw-team-workbench">
        <div className="pw-team-workbench-bar pw-material-navigation"><TabList aria-label="这次协作"><Tab id="progress" isDisabled={materialBusy || openingMemberResult}>进展</Tab><Tab id="result" isDisabled={materialBusy || openingMemberResult}>成果</Tab><Tab id="details" isDisabled={openingMemberResult}>详情</Tab></TabList></div>
        {resultSource && !resultSource.member && resultSource.runId === current.id && runSection !== 'result' && <Button variant="ghost" size="small" leadingIcon={IconArrowLeft} disabled={busy || materialBusy}
          onClick={() => resultBack.current()}>返回协作成果</Button>}
        <TabPanel id="progress">
          <TeamFollowUps items={scopedFollowUps} read={openFollowUp} disabled={!canRead || materialBusy} parentSupplement={supplementByQuestion}/>
          {frozenReviewNext&&<Button variant="secondary" size="small" trailingIcon={IconChevronRight} onClick={()=>{
            const shown=currentReading.current,live=bridge.getState();
            if(!shown||shown!==reading||document.hidden||!ownsNativeWindow()||shownRunSection.current!=='progress'||
               newerTeamReading(shown.run,object(object(live.state.teamRuns)[shown.run.id])))return;
            chooseRunSection('result',()=>{
              if(currentReading.current===shown&&ownsNativeWindow()&&shownRunSection.current==='result')
                root.current?.querySelector<HTMLElement>('[data-team-result-heading]')?.focus({preventScroll:true});
            });
          }}>去成果核对冻结文件</Button>}
          {current.phase === 'intervention' && !hasWorkPlan && <div className="pw-collaboration-attention" role="status"><div><strong>新要求已保存</strong><span>受影响成员已暂停。继续协作后处理，沿用剩余额度。</span></div></div>}
          {!!teamResult.body && <section className="pw-team-result-overview" aria-label={teamResult.previous ? '上次汇总摘要' : teamResult.partial ? '部分汇总摘要' : '本轮汇总摘要'}>
            <h3>{teamResult.previous ? '上次保存的共同汇总' : teamResult.partial ? '本次的部分汇总' : '已有共同汇总'}</h3>
            <p>{teamResult.body.slice(0, 360)}{teamResult.body.length > 360 ? '…' : ''}</p>
            <Button variant="ghost" className="pw-team-result-link" trailingIcon={IconChevronRight}
              onClick={() => chooseRunSection('result')}>{teamResult.partial ? '阅读部分汇总' : teamResult.excerpt ? '阅读汇总节选' : '阅读完整汇总'}</Button></section>}

      <section className="pw-team-overview" data-team-section="members" tabIndex={-1} aria-label="成员与当前工作">
        <header className="pw-team-members-heading"><h3 className="pw-team-section-title">成员 <span>{currentMembers.length} 名在队{members.length > currentMembers.length ? ` · ${members.length - currentMembers.length} 名已退出` : ''}</span></h3></header>
        {ordinaryAnswers && !teamResult.original && reading?.viewHandle && canOpenMemberResult && deliveries.length > 0 && <p className="pw-team-panel-caption">
          已交回的答复可单独打开，不用等其他成员。在原任务核对后可手动复制；用于新问题或项目时，先确认资料仍可使用。</p>}
        <div className="pw-team-member-board">
          {members.map(member => {
            const model = memberModelLabel(member), progress = progressByMember.get(member.id) ?? [];
            const researchMember = !hasWorkPlan && current.researchExecutionFormat === 'pocketworld.team-research-agent.v1' && (member.runner || 'model') === 'model';
            const observed = researchMember && reading?.viewHandle ? teamResearchReplyExecution(current.id, member, reading.researchAnswerRoutes) : null;
            const supplement = !hasWorkPlan && reading?.viewHandle ? teamDirectedSupplement(current, messages, member.id,
              ownerWorkLink?.readOnly === true && ownerWorkLink.teamRunId === current.id ? ownerWorkLink.taskId : undefined) : null;
            const memberRound = hasWorkPlan ? undefined : rows(member.roundStates).find(round => round.round === member.round && round.generation === member.generation);
            const missingPairedText = pairedResultsMissing.includes(member);
            const waitsForPairedText = memberRound?.status === 'blocked_dependency' && Array.isArray(memberRound.blockedBy) &&
              pairedResultsMissing.some(source => memberRound.blockedBy.includes(source.id));
            const memberTaskId = member.phoneTask?.taskId || (!hasWorkPlan ? member.assignmentTask?.taskId : undefined);
            const outputMessage = [...messages].reverse().find(message => message.memberId === member.id && ['answer', 'work_item_completed'].includes(message.kind) && message.content === member.text);
            const outputIdentity = outputMessage ? teamMessageIdentity(outputMessage, members) : null;
            // A revision queues the stage but keeps the slot's last text. Do not
            // relabel that retained paragraph as the new stage's deliverable.
            const revisedCopy = researchMember && rows(member.roundStates).some(stage => Array.isArray(stage.researchHistory) && stage.researchHistory.length > 0);
            const unversionedCopy = revisedCopy && !(outputMessage?.kind === 'answer' && rows(member.roundStates).some(stage =>
              stage.status === 'completed' && stage.turnId === outputMessage.turnId && stage.round === outputMessage.round &&
              stage.generation === outputMessage.memberGeneration && deliveries.some(row => row.memberId === member.id && row.taskId === stage.researchTaskId && row.round === stage.round)));
            const researchRecords = current.researchExecutionFormat ? teamResearchRecords(current.id, member) : [];
            const assignment = !hasWorkPlan || !progress.length ? label(member.assignment || member.role) : '';
            const memberState = member.exited ? 'exited' : member.status;
            const stoppedLeg = stoppedMember?.memberId === member.id ? stoppedMember : null;
            const coordinatorStopped = stoppedSavedWorkTeam && !member.exited;
            const stopRequested = ordinaryAnswers && !member.exited &&
              (coordinatorStopped && memberState === 'cancelled' || teamMemberStopRequested(member));
            const showMemberState = !progress.length || member.exited || memberState === 'paused' ||
              ordinaryAnswers && ['stopping','cancelled'].includes(memberState) ||
              teamNeedsAttention(memberState) && !progress.some(fact => fact.state === memberState);
            const expanded = (expandedMembers[current.id] || []).includes(member.id);
            return <article key={member.id} className="pw-team-member pw-team-member-card" data-team-member-id={member.id} data-motion-key={`member:${member.id}`}>
              <header className="pw-team-progress-member-heading"><TeamMemberIdentity member={member} name={name(member.id)} detail={member.id === current.leaderId ? teamRoleLabel(current) : undefined} size={40} />{showMemberState && <span className="pw-team-state" data-state={stopRequested ? 'stopping' : memberState}>{stopRequested ? '已请求停止' : status(memberState)}</span>}</header>
              {assignment && <p className="pw-team-current-work">{assignment}</p>}
              {progress.length > 0 && <ul className="pw-team-progress-facts" role="list" aria-label={hasWorkPlan ? '本次分工' : '本次阶段'}>
                {progress.map(fact => {
                  const source = !hasWorkPlan && fact.state === 'completed' ? deliveries.find(item => item.memberId === member.id && String(item.round) === fact.key) : undefined;
                  const prior = !source && !hasWorkPlan && canOpenMemberResult ? archivedDeliveries.find(item => item.memberId === member.id && String(item.round) === fact.key) : undefined;
                  const inPlace = source?.answer && !!openReading;
                  const file=hasWorkPlan&&fact.state==='completed'&&!hasFrozenRevision&&fileEntry?.view!==reading?.viewHandle&&
                    bridge.getState().capabilities.commands.includes('teams.readFrozenOutputs')&&
                    bridge.getState().capabilities.commands.includes('teams.readFrozenDiff')?completedMemberFile(current,member,fact.key):undefined;
                  const execution = observed && fact.state === 'completed' && String(observed.round) === fact.key ? observed : null;
                  const checkReceipt = fact.state === 'completed' && ordinaryAnswers && researchMember;
                  const statusCopy = coordinatorStopped && fact.state === 'cancelled' ? '不再派发'
                    : checkReceipt ? (source ? '答复已保存' : '完成 · 答复待核对')
                    : fact.state === 'completed' ? '已完成' : fact.state === 'preparing' ? '正在准备'
                      : fact.state === 'queued' && (member.paused || current.status === 'paused' || ordinaryAnswers && memberState === 'cancelled') ? '未开始'
                        : stopRequested && fact.state === 'paused' ? '停止请求前已暂停' : status(fact.state);
                  return <li key={fact.key} data-team-progress-round={hasWorkPlan ? undefined : fact.key} tabIndex={hasWorkPlan ? undefined : -1}>
                    <span dir="auto">{fact.title}</span><span className="pw-team-state" data-state={checkReceipt && !source ? 'blocked_unknown' : fact.state}>{statusCopy}</span>
                    {source && (inPlace || canOpenMemberResult) && <Button className="pw-team-progress-read" size="small" variant="ghost"
                      trailingIcon={IconChevronRight} aria-label={'查看' + source.name + '的' + fact.title + '答复'} data-team-progress-delivery={source.key}
                      disabled={!canRead || materialBusy} onClick={event => inPlace ? openMemberAnswer(source, event.currentTarget) : openMemberDelivery(source, 'progress')}>查看答复</Button>}
                    {stoppedLeg&&!stoppedLeg.failureKey&&fact.key==='1'&&fact.state==='paused'&&<Button className="pw-team-progress-read" size="small" variant="ghost"
                      trailingIcon={IconChevronRight} disabled={!canMutate} onClick={()=>reviewStoppedMember(stoppedLeg)}>核对并继续这项分工</Button>}
                    {file&&<Button className="pw-team-progress-read" size="small" variant="ghost" trailingIcon={IconChevronRight}
                      aria-label={`查看${name(member.id)}交回的 ${file.path} 的原文件差异`} disabled={!canRead||materialBusy||readingFiles}
                      onClick={()=>{
                        const handle=reading?.viewHandle;
                        if(!handle||!canRead||materialBusy||readingFiles||document.hidden||!ownsNativeWindow()||
                           currentReading.current?.viewHandle!==handle)return;
                        setMemberFileEntry({requestId:crypto.randomUUID(),viewHandle:handle,documentEpoch:bridge.getDocumentEpoch(),file});
                        chooseRunSection('result');
                      }}>查看原文件差异</Button>}
                    {prior && <Button className="pw-team-progress-read" size="small" variant="ghost" trailingIcon={IconChevronRight}
                      aria-label={`查看${prior.name}第 ${prior.round} 阶段第 ${prior.archivedVersion} 版答复的原任务`} data-team-progress-delivery={prior.key}
                      disabled={!canRead || materialBusy} onClick={() => openMemberDelivery(prior, 'progress')}>第 {prior.archivedVersion} 版原答复</Button>}
                    {resultPhaseLink && resultSource?.member?.id === member.id && String(resultSource?.member?.round) === fact.key &&
                      <Button className="pw-team-progress-read" size="small" variant="ghost" leadingIcon={IconArrowLeft}
                        disabled={!canRead || materialBusy} onClick={() => resultBack.current()}>返回该份成果</Button>}
                    {fact.blockers && <small className="pw-team-progress-source">前置阶段：{fact.blockers.map((blocker, index) => {
                      const stage = <>{blocker.sameMember ? '本成员' : <bdi>{name(blocker.memberId)}</bdi>}第 {blocker.round} 阶段（{blocker.detail}）</>;
                      const target = progressByMember.get(blocker.memberId)?.some(row => row.key === String(blocker.round));
                      return <span key={blocker.memberId}>{index > 0 ? '；' : ''}{showPrerequisites && blocker.current && target
                        ? <Button size="small" variant="ghost" className="pw-team-result-link" trailingIcon={IconChevronRight}
                            aria-label={`定位${name(blocker.memberId)}第 ${blocker.round} 阶段的原前置分工`}
                            disabled={!canRead || materialBusy} onClick={() => locatePrerequisite(blocker.memberId, blocker.round)}>{stage}</Button>
                        : stage}</span>;
                    })}</small>}
                    {execution && <small className="pw-team-progress-source">{execution.reportedModel ? <>答复模型：<bdi>{execution.reportedModel}</bdi></> :
                      execution.requestedModel ? <>请求模型：<bdi>{execution.requestedModel}</bdi>（答复型号未确认）</> : '答复型号未确认'}。{execution.location}</small>}
                  </li>;
                })}
              </ul>}
              {supplement && <div className="pw-team-supplement-receipt">
                <div><strong>{supplement.title}{supplement.round ? ` · 第 ${supplement.round} 阶段` : ''}</strong><span>{supplement.status}</span></div>
                <Button variant="ghost" size="small" trailingIcon={IconChevronRight} disabled={!canRead || materialBusy}
                  aria-label={`查看给${name(member.id)}的这条${supplement.title === '你的讨论' ? '讨论' : '补充'}`}
                  onClick={() => openMemberSupplement(supplement)}>查看原消息</Button>
              </div>}
              {missingPairedText && <p className="pw-team-member-receipt">请在执行这项分工的原手机，从「我的设备」里的「交给这台手机的工作」核对原工作。</p>}
              {memberRound?.message && <p className="pw-team-member-receipt">{memberRound.message}</p>}
              {memberRound?.nativeErrorMessage && <p className="pw-team-member-error">{memberRound.nativeErrorMessage}</p>}
              {!progress.length && !unversionedCopy && member.text && <p className="pw-team-output-preview">{String(member.text).slice(0, 240)}{String(member.text).length > 240 ? '…' : ''}</p>}
              {!hasWorkPlan && !member.exited && pendingCount(member) > 0 && <p className="pw-team-member-pending">{pendingCount(member)} 条消息待处理</p>}
              <div className="pw-team-member-primary-actions"><Button variant="ghost" onClick={() => { chooseMemberFilter(member.id); jumpToRunSection('messages'); }}>沟通</Button>{memberTaskId && <Button variant="ghost" trailingIcon={IconExternalLink} data-team-source-task={memberTaskId} onClick={() => openRelatedTask(memberTaskId)}>{missingPairedText ? '查看本机派发记录' : '打开任务'}</Button>}<Button variant="secondary" aria-expanded={expanded} aria-controls={`team-member-detail-${member.id}`} onClick={() => setExpandedMembers(old => ({ ...old, [current.id]: expanded ? (old[current.id] || []).filter(id => id !== member.id) : [...(old[current.id] || []), member.id] }))}>{expanded ? '收起详情' : '产出与管理'}</Button></div>
              <div id={`team-member-detail-${member.id}`} hidden={!expanded}>
              {expanded && <div className="pw-team-expanded-copy">
                {member.id === current.leaderId && <p className="pw-field-hint">{teamRoleLabel(current)}</p>}<p>{member.assignment || member.role}</p>
                {!!rows(member.dependsOn).length && <p className="pw-field-hint">{ordinaryAnswers ? '设定的前置成员' : '等待前置分工'}：{member.dependsOn.map(name).join('、')}</p>}
                <Accordion variant="divided" className="pw-team-execution-details"><AccordionItem id={`execution:${member.id}`} title="模型与执行信息">
                {member.runner === 'phone_agent' && <p className="pw-muted">计划使用：{teamPhoneTargetLabel(member.device, phoneTargets)}</p>}
                {isTeamHarness(member.runner || '') && <><p className="pw-muted">计划执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p>{member.externalSession?.sessionId && <p className="pw-muted">原会话：{member.externalSession.sessionId}</p>}</>}
                {(model || researchMember) && <p className="pw-muted">计划模型：{model || '沿用原团队主模型'}</p>}
                {researchMember && !observed && <p className="pw-muted">没有可核对的答复回执，实际模型与执行位置未确认。</p>}</AccordionItem></Accordion>
                {researchRecords.length > 0 && <section aria-label="原研究记录"><h4>原研究记录</h4><p className="pw-field-hint">查看完整产出、待处理事项和执行过程；打开不会继续工作，最新状态以原任务为准。</p>
                  <div className="pw-form-actions">{researchRecords.filter(record => !record.historical).map(record => <Button key={`${record.round}:${record.taskId}`} data-team-source-task={record.taskId} data-team-record-round={record.round} variant="ghost" trailingIcon={IconExternalLink} disabled={!workspaceActive || !reading?.viewHandle || busy || materialBusy || changed} onClick={() => openResearchTask(member.id, record)}>第 {record.round} 阶段记录{record.recordedStatus ? ` · 记录时${status(record.recordedStatus)}` : ''}</Button>)}</div>
                  {researchRecords.some(record => record.historical) && <Accordion variant="divided"><AccordionItem id="research-history" title="历史研究版本"><p className="pw-field-hint">这些版本已被后续输入替代，不代表当前成果，也不会转移给新成员。</p><div className="pw-form-actions">{researchRecords.filter(record => record.historical).map(record => <Button key={`${record.round}:${record.taskId}:${record.version}`} variant="ghost" trailingIcon={IconExternalLink} disabled={!workspaceActive || !reading?.viewHandle || busy || materialBusy || changed} onClick={() => openResearchTask(member.id, record)}>第 {record.round} 阶段 · 旧版本 {record.version}{record.recordedStatus ? ` · 记录时${status(record.recordedStatus)}` : ''}</Button>)}</div></AccordionItem></Accordion>}
                </section>}
                {member.text && <section className="pw-team-member-output" aria-labelledby={`team-output-${member.id}`}><h4 id={`team-output-${member.id}`}>{unversionedCopy ? '留存文字（未核对为本版交付）' : '已保存的成员产出'}</h4>{outputIdentity && <TeamMemberIdentity member={outputIdentity} name={teamMessageAuthor(outputMessage!, members)} detail={outputIdentity.name !== member.name ? `原名称 · ${member.id}` : undefined} size={24} />}<AIAnswer text={String(member.text)} /></section>}
                {member.acceptance && <p>验收：{member.acceptance}</p>}
                {!member.exited && <div className="pw-form-actions"><Button variant="ghost" disabled={!canMutate} onClick={() => beginMemberChange('rename', member)}>名称与头像</Button>{simpleRoster && <Button variant="ghost" disabled={!canMutate || !rosterPaused} onClick={() => beginMemberChange('exit', member)}>退出本次协作</Button>}</div>}
                <div className="pw-form-actions">{!member.exited && teamCanPause(member.status) && <Button variant="ghost" disabled={!canControl} onClick={() => control('pause', member.id)}>暂停成员</Button>}{!member.exited && !hasWorkPlan && !stoppedLeg && !waitsForPairedText && teamCanResume(member.status) && (remainingMember && remainingMember.item.locator.memberId === member.id
                  ? remainingMember.canReview && <Button variant="ghost" disabled={!canMutate} onClick={() => openFollowUp(remainingMember.item)}>核对未完成分工</Button>
                  : <Button variant="ghost" disabled={!canMutate} onClick={() => control('resume', member.id)}>继续成员</Button>)}{!hasWorkPlan && !member.exited && !stoppedLeg && !missingPairedText && !waitsForPairedText && pendingCount(member) > 0 && <Button variant="ghost" disabled={!canMutate || pendingMessageBusy(member)} onClick={() => control('continueMessages', member.id)}>使用 {pendingCount(member)} 条待处理消息继续</Button>}</div>
              </div>}
              </div>
            </article>;
          })}
        </div>
      </section>
        </TabPanel>
        <TabPanel id="result">
          {pairedResultsMissing.length > 0 && <p className="pw-field-hint">本设备没有取得 <bdi>{pairedResultsMissing.map(member => name(member.id)).join('、')}</bdi> 的手机执行结果正文。请在执行分工的原手机打开原工作核对。</p>}
          <TeamResultHandoff result={teamResult} partial={partialResult} memberName={name}
            showSourcesFirst={ordinaryAnswers&&!pinnedSummary}
            prioritizePublishedReport={publishedOrdinaryResult} revisionOffers={revisionRows.length||focusedProposal ? <TeamParentRevisions rows={revisionRows} returnToWork={focusedProposal?.returnToWork?()=>leaveWithDrafts(focusedProposal.returnToWork!):undefined} disabled={!canMutate||materialBusy||!bridge.getState().capabilities.commands.includes('teams.reviewParentRevision')} inspect={row=>{
            const original=reading?.viewHandle;if(!original||!canMutate||materialBusy)return;
            void invoke('teams.reviewParentRevision',{viewHandle:original,operationId:row.operationId},value=>{
              if(currentReading.current?.viewHandle!==original||value.operation!=='reviseFromParent'||value.input?.runId!==current.id||value.input?.operationId!==row.operationId||
                  value.input?.workItemId!==row.workItemId||value.input?.assignmentId!==row.assignmentId||value.input?.text!==row.text||!Array.isArray(value.input?.affected)||!value.input.affected.length){
                release(value,'reviewHandle');throw new Error('原提案或工作项已变化，没有开始修订。');
              }reviewResult(value);
            });}}/>:undefined} frozenFiles={current.productionScope && (current.mode === 'produce' || current.workPlan) && (fileEntry&&fileEntry.view===reading?.viewHandle || current.status === 'awaiting_review' || hasFrozenRevision || workItems.some(item => rows(item.artifacts).length > 0) || members.some(member => rows(member.roundStates).some(phase => rows(phase.artifacts).length > 0))) && bridge.getState().capabilities.commands.includes('teams.readFrozenOutputs')
            ? <TeamFrozenFiles key={reading?.viewHandle || current.id} team={current.id} hasRevision={hasFrozenRevision} view={reading?.viewHandle || ''} entry={fileEntry&&fileEntry.view===reading?.viewHandle?fileEntry:undefined}
                memberEntry={memberFileEntry&&memberFileEntry.viewHandle===reading?.viewHandle?{...memberFileEntry,
                  consume:id=>setMemberFileEntry(value=>value?.requestId===id?null:value)}:undefined}
                run={run} active={workspaceActive && runSection === 'result'} disabled={!canRead || materialBusy} onOpen={setReadingFiles} back={frozenFilesBack} memberName={name}/> : undefined} sources={deliveries.length>0 ? <TeamResearchDeliveries records={deliveries} disabled={!canRead||materialBusy} rawAvailable={canOpenMemberResult}
            open={openMemberDelivery} openAnswer={openReading?openMemberAnswer:undefined}
            awaitingWorkReview={awaitingMemberAnswerReview}
            groupByMember={ordinaryAnswers ? name : undefined}
            locate={resultPhaseLink ? {deliveryKey:resultPhaseLink,open:()=>selectRunTab('progress')} : undefined}
            expanded={shownMemberSources[current.id] ?? (!!partialResult || ordinaryAnswers && !publishedOrdinaryResult && deliveries.length <= 3)}
            onExpanded={value=>setShownMemberSources(old=>({...old,[current.id]:value}))}/> : undefined}
            archivedSources={archivedDeliveries.length>0 && canOpenMemberResult ? <TeamResearchDeliveries records={archivedDeliveries} archived disabled={!canRead||materialBusy} rawAvailable={canOpenMemberResult} open={openMemberDelivery}
              locate={resultPhaseLink ? {deliveryKey:resultPhaseLink,open:()=>selectRunTab('progress')} : undefined}
              expanded={shownArchives[current.id] ?? !deliveries.length} onExpanded={value=>setShownArchives(old=>({...old,[current.id]:value}))}/> : undefined} stopped={current.status === 'cancelled'}
            actions={{ items: scopedFollowUps, read: openFollowUp, disabled: !canRead || materialBusy, expanded: !!allResultActions[current.id], parentSupplement: supplementByQuestion,
              toggle: () => setAllResultActions(old => ({ ...old, [current.id]: !old[current.id] })) }}
            finalPackageReader={teamPackage&&reading&&workspaceActive&&runSection==='result'&&bridge.getState().capabilities.commands.includes('teams.readFinalPackageFiles')
              ? <TeamFinalPackageReading key={reading.viewHandle+':'+teamPackage.operationId} item={teamPackage} handle={reading.viewHandle} fromWork={focusedPackage?()=>leaveWithDrafts(focusedPackage.returnToWork):undefined} disabled={!canRead||materialBusy} exportState={state.teamFinalPackageExport===undefined?reading.finalPackageExport:state.teamFinalPackageExport}
                  live={()=>ownsNativeWindow()&&currentReading.current?.viewHandle===reading.viewHandle&&currentReading.current?.run?.id===teamPackage.teamRunId}/> : undefined}
            openOriginalReview={current.status === 'awaiting_review' && (!focusedPackage||followUps.length>0||rows(reading?.revisionOffers).length>0) && reading?.taskId ? openAwaitingReview : undefined}
            nextRequest={canPrepareFollowUp ? { open: prepareFollowUp, disabled: !!state.chatSubmitting } : undefined}
            openMembers={() => { setResultSource(resultOrigin('members')); jumpToRunSection('members'); }}
            openMessage={id => { setResultSource(resultOrigin('summary-original')); focusMessage(id); }}/>
        </TabPanel>
        <TabPanel id="details" shouldForceMount data-team-details-active={runSection === 'details'}>
          {hasWorkPlan && <p className="pw-team-panel-caption">分工按计划推进，展开可查看原任务、审批与已保存产出。</p>}
      {!!workItems.length && <section className="pw-team-overview" aria-label="这次协作的工作项">
        <h3 className="pw-team-section-title">工作项 <span>{workItems.length}</span></h3>
        <Accordion key={`${current.id}:items`} variant="divided" className="pw-team-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedItems[current.id] || [])}
          onExpandedChange={keys => setExpandedItems(old => ({ ...old, [current.id]: Array.from(keys, String) }))}>
          {workItems.map(item => <AccordionItem key={item.workItemId} id={item.workItemId} className="pw-team-work-item" data-work-item-id={item.workItemId} data-motion-key={`work-item:${item.workItemId}`}
            title={<span className="pw-team-item-heading"><span>{item.title}</span><small className="pw-team-state" data-state={item.status}>{status(item.status)}</small></span>}
            description={<TeamMemberIdentity member={members.find(member => member.id === item.memberId) || { id: item.memberId }} name={name(item.memberId)} size={24} />}>
            <div className="pw-team-expanded-copy"><p>{item.instruction}</p>{item.message && <p>{item.message}</p>}{item.nativeErrorMessage && <p className="pw-field-hint">{item.nativeErrorMessage}</p>}{item.assignmentTask?.taskId && <Button variant="secondary" trailingIcon={IconExternalLink} data-team-source-task={item.assignmentTask.taskId} onClick={() => openRelatedTask(item.assignmentTask.taskId,item)}>查看此分工与审批</Button>}
              {canInspectFileContinuation(current, item, members.find(member => member.id === item.memberId)) && <Button variant="ghost" disabled={!canMutate} onClick={() => {
                const original = reading?.viewHandle;
                if (!original) return;
                void invoke('teams.reviewWorkItemContinuation', { viewHandle: original, workItemId: item.workItemId }, value => {
                  if (currentReading.current?.viewHandle !== original || value.operation !== 'continueWorkItem' || value.input?.runId !== current.id
                      || value.input?.workItemId !== item.workItemId || value.input?.assignmentId !== item.assignmentId) {
                    release(value, 'reviewHandle'); throw new Error('原分工已变化，未开始工作。');
                  }
                  reviewResult(value);
                });
              }}>核对能否继续此分工</Button>}
              {bridge.getState().capabilities.commands.includes('teams.reviewSoleFailedHandoff') && <TeamFailedWorkHandoff team={current} item={item} members={members} disabled={!canMutate} review={next=>{
                const origin=reading?.viewHandle;if(!origin)return;
                void invoke('teams.reviewSoleFailedHandoff',{viewHandle:origin,workItemId:item.workItemId,nextMemberId:next},value=>{
                  if(currentReading.current?.viewHandle!==origin||value.operation!=='handoffFailedItem'||value.input?.runId!==current.id||
                    value.input?.workItemId!==item.workItemId||value.input?.assignmentId!==item.assignmentId||value.input?.nextMemberId!==next){
                    release(value,'reviewHandle');throw new Error('不是此次选定的失败分工和接手成员，没有排队。');
                  }reviewResult(value);
                });
              }}/>}
              {item.acceptance && <p>验收：{item.acceptance}</p>}
              {!!rows(item.dependsOn).length && <p>前置：{item.dependsOn.map((id: string) => workItems.find(prior => prior.workItemId === id)?.title || id).join('、')}</p>}
            </div>
          </AccordionItem>)}
        </Accordion>
      </section>}
          <Accordion variant="divided" className="pw-team-detail-disclosures" allowsMultipleExpanded expandedKeys={new Set(expandedDetails)} onExpandedChange={keys => chooseDetailSections(Array.from(keys, String))}>
            <AccordionItem id="background" title="工作背景" description="完整目标与所属任务">
              <div className="pw-team-detail-body"><p className="pw-team-goal-copy">{current.goal}</p>
                {teamTimeLabel(current.createdAt) && <p className="pw-field-hint">开始于 {teamTimeLabel(current.createdAt)}</p>}
                {reading?.taskId && <Button variant="ghost" trailingIcon={IconExternalLink} disabled={busy || materialBusy} onClick={() => openRelatedTask(reading.taskId)}>查看协作任务与审批</Button>}
              </div>
            </AccordionItem>
            <AccordionItem id="materials" title="资料与授权" description="成员可使用的资料与范围">
              <div data-team-section="materials" tabIndex={-1}><TeamEvidenceWorkspace key={current.id} runId={current.id} members={members} run={run} disabled={!canRead} stopped={current.status === 'cancelled'} active={workspaceActive && materialsActive} windowRevision={Number(state.webWorkspaceResumeRevision || 0)} onBackChange={handler => { materialBack.current = handler; }} onBeforeLeaveChange={handler => { materialBeforeLeave.current = handler; }} onBusyChange={setMaterialBusy} /></div>
            </AccordionItem>
            <AccordionItem id="conversation" title="沟通记录" description={`${messages.length} 条已读取记录`}>
              <div className="pw-team-conversation-body">

      <Accordion variant="divided" className="pw-team-compose-disclosure" expandedKeys={composeOpen || !!uncertain ? ['team-compose'] : []} onExpandedChange={keys => setComposeOpen(keys.has('team-compose'))}><AccordionItem id="team-compose" title={reply?.kind === 'assignment_question' ? '回答原问题' : '本机消息草稿'} description={draftNeedsRecheck ? '提交结果待核对' : reply ? '保留原问题与收件成员' : draftOnly ? '仅在草稿里，未发送' : '查看以往的草稿'}>
      <section className="pw-team-compose" data-team-section="compose" aria-label="原问题与消息草稿">
      {reply && <div className="pw-team-reply-context"><header><span>{reply.kind === 'assignment_question' ? '回答原问题' : '引用原消息'} · {teamMessageIdentity(reply, members) ? <TeamMemberIdentity member={teamMessageIdentity(reply, members)!} name={teamMessageAuthor(reply, members)} size={24} /> : teamMessageAuthor(reply, members)}</span><Button variant="ghost" disabled={busy || !!uncertain} onClick={() => messageDraft.guard(() => setReplyId(''))}>查看其他草稿</Button></header><p className="pw-team-quote-excerpt">{String(reply.content || '').slice(0, 360)}{String(reply.content || '').length > 360 ? '…' : ''}</p><Button variant="ghost" onClick={() => focusMessage(reply.messageId)}>查看完整原消息</Button></div>}
      <SelectField label="收件成员" value={recipient} options={[{ id: '*', label: '全队的旧草稿' }, ...members.map(member => ({ id: member.id, label: name(member.id) + (member.exited ? ' · 已退出' : member.id === current.leaderId ? ' · ' + teamRoleLabel(current) : '') }))]} onChange={value => messageDraft.guard(() => setRecipient(value))} isDisabled={busy || !!uncertain || reply?.kind === 'assignment_question'} />
      {reply?.kind !== 'assignment_question' && <>
        <SelectField label="原消息用途" value={mode} options={[{ id: 'discussion', label: '讨论补充' }, { id: 'queue', label: '补充下一工作点' }, { id: 'feedback', label: '调整要求' }]} onChange={changeMessageMode} isDisabled={busy || !!uncertain} />
        <SelectField label="草稿引用的原消息" value={replyId} options={[{ id: '', label: '未引用消息' }, ...messages.map(message => ({ id: message.messageId, label: `${teamMessageAuthor(message, members)} · ${teamGoalTitle(message.content, '原消息')}` }))]} onChange={value => messageDraft.guard(() => { const original = messages.find(message => message.messageId === value); setReplyId(value); if (original?.kind === 'assignment_question') { setRecipient(original.memberId); setMode('discussion'); } })} isDisabled={busy || !!uncertain} />
        <p className="pw-field-hint">旧草稿保留供核对。新的要求在原工作里交给管家，原问题仍在这里逐条回答。</p>
        {!ordinaryAnswers && canSupplementParent && onOpenWork && <Button variant="secondary" disabled={busy || materialBusy || messageDraft.requiresRecheck || !!uncertain} onClick={() => openOwnerWork('supplement')}>回原工作补充</Button>}
      </>}
      {!replyCanReceive && !latestAnswer && <p className="pw-team-reading-notice" role="status">这条原问题已不再等待回答，未发送草稿仍可核对。</p>}
      {current.status === 'cancelled' && <p className="pw-team-reading-notice" role="status">协作已停止后续派发，消息与未发送草稿可继续查看。</p>}
      <FormDraftNotice draft={messageDraft} />{messageDraft.requiresRecheck && <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void invoke('teams.readRun', { runId: current.id }, value => { if (value.reading?.run?.id !== current.id) throw new Error('原协作身份不匹配'); acceptReading(value.reading); setMessageChecked(true); }); }}>读取原消息核对结果</Button><Button variant="secondary" disabled={busy || !messageChecked || !!uncertain} onClick={() => { messageDraft.clearSubmitted(); setMessageChecked(false); }}>已核对原记录</Button></div>}
      <TextAreaField label={reply?.kind === 'assignment_question' ? '回答这条原问题' : '本机草稿内容'} value={text} onChange={value => setDrafts(old => ({ ...old, [draftKey]: value }))} maxLength={4000} showCount isReadOnly={reply?.kind !== 'assignment_question'} isDisabled={reply?.kind === 'assignment_question' && (!canMutate || !replyCanReceive) || messageDraft.loading} hint={reply?.kind === 'assignment_question' ? '只保存不会启动成员；选择继续，只会请求这名成员沿用原分工和剩余额度。' : '查看草稿不会发送消息或启动工作。'} />
      {draftOnly && <p className="pw-field-hint">这份草稿尚未提交。</p>}
      {latestAnswer && <div className="pw-team-answer-receipt"><p className="pw-field-hint">刚才的操作：{latestAnswer.detail}</p>
        {latestAnswerMessageId && <Button variant="ghost" size="small" trailingIcon={IconChevronRight} disabled={!canRead || materialBusy}
          onClick={() => focusMessage(latestAnswerMessageId, latestAnswer.memberId)}>查看原问题和已保存回答</Button>}</div>}
      {reply?.kind === 'assignment_question' && replyCanReceive && <div className="pw-form-actions"><PressButton label="回答并继续原分工" disabled={!mayAnswer} onClick={() => { void send('continue'); }} /><Button variant="secondary" disabled={!mayAnswer} onClick={() => { void send('save'); }}>只保存回答</Button></div>}
      {mode !== 'feedback' && !replyId && recipientPending > 0 && <div className="pw-form-actions"><p className="pw-field-hint">{name(recipient)}有 {recipientPending} 条已排队消息尚未确认消费。{stoppedMessageRecipient ? '消息留在原队列；可在进展核对原分工后选择是否继续。' : recipientMember && !pendingMessageBusy(recipientMember) ? '继续只使用已保存的消息，不提交当前草稿或追加额度。' : '成员正在工作或等待授权，消息保留在原队列。'}</p>{recipientMember && !stoppedMessageRecipient && !pendingMessageBusy(recipientMember) && <Button variant="secondary" disabled={!canMutate} onClick={() => control('continueMessages', recipient)}>使用已排队消息继续</Button>}</div>}
      {uncertain && <p role="status">这条原消息仍待核对，草稿保留，不会重复发送。刷新原记录后仍可暂停或停止团队。</p>}
      </section>
      </AccordionItem></Accordion>

          <div className="pw-team-member-filter" aria-label="按成员查看沟通"><Button variant="ghost" aria-pressed={!memberFilter} onClick={() => chooseMemberFilter('')}>全队</Button>{members.map(member => <Button key={member.id} variant="ghost" aria-pressed={memberFilter === member.id} onClick={() => chooseMemberFilter(member.id)}><TeamMemberIdentity member={member} name={name(member.id)} size={28} /></Button>)}</div>
          <p className="pw-team-conversation-hint">{memberFilter ? `只看与 ${name(memberFilter)} 有关的共享记录。` : '可按成员查看沟通、问题与保存回执。'}</p>
      <section className="pw-team-messages" data-team-section="messages" tabIndex={-1} aria-label="原团队消息"><header className="pw-collaboration-group-heading"><h3>团队沟通</h3><span>{visibleMessages.length} 条消息</span></header>
      {ordinaryAnswers && canSupplementParent && onOpenWork && <div className="pw-team-message-entry">
        <Button variant="secondary" size="small" trailingIcon={IconChevronRight} disabled={!canRead || materialBusy || messageDraft.requiresRecheck || !!uncertain}
          onClick={() => openOwnerWork('supplement')}>回原工作写补充</Button>
        <p className="pw-field-hint">{memberFilter ? <>可在原工作写明要给 <bdi>{name(memberFilter)}</bdi> 的要求。当前筛选不决定收件人。</> : '在原工作写明收件成员和要求。打开输入框与保存草稿都不会发送。'}</p>
      </div>}
      {visibleMessages.map(message => {
        const identity = teamMessageIdentity(message, members), sender = teamMessageAuthor(message, members);
        const quoted = message.quoteMessageId ? object(message.quotedMessage || messages.find(row => row.messageId === message.quoteMessageId)) : null;
        const quotedText = String(quoted?.content || ''), quoteIsExcerpt = quoted?.truncated || quotedText.length > 360;
        const eventRecord = message.role === 'tool' || message.role === 'system';
        const deliveryCopy = message.kind === 'assignment_question' && message.state === 'pending' && !isCurrentQuestion(message)
          ? '保留原问题记录，当前不再等待这条回答。' : teamMessageDelivery(current, message);
        const to = message.recipientMemberId || message.targetMemberId;
        const originalRecipient = ordinaryAnswers && to && message.kind === 'conversation' && ['user','parent_model'].includes(message.role) &&
          message.memberSnapshot?.id === to ? message.memberSnapshot : null;
        const toName = originalRecipient ? teamMemberLabel(originalRecipient, members.filter(member => member.id !== to).concat(originalRecipient)) : to ? name(to) : '';
        const messageContent = <AIAnswer text={String(message.content || '')} actions={isCurrentQuestion(message) && <Button variant="ghost" disabled={!canMutate || !members.some(member => member.id === message.memberId && !member.exited)} aria-pressed={replyId === message.messageId} onClick={() => { messageDraft.guard(() => { setReplyId(message.messageId); setMode('discussion'); setRecipient(message.memberId); jumpToRunSection('compose'); }); }}>回答原问题</Button>} />;
        return <article className="pw-source-record pw-team-message" data-message-kind={eventRecord ? 'event' : message.kind} key={message.messageId} data-message-id={message.messageId} data-motion-key={`message:${message.messageId}`} data-reply-selected={replyId === message.messageId || undefined} tabIndex={-1}>
        <header className="pw-team-message-heading"><h3>{identity ? <TeamMemberIdentity member={identity} name={sender} detail={members.some(member => member.id === identity.id && member.name !== identity.name) ? `原名称 · ${identity.id}` : members.filter(member => member.name === identity.name).length > 1 ? identity.id : undefined} /> : sender}{to && <small>→ <bdi>{toName}</bdi>{originalRecipient && toName !== name(to) ? '（发送时的名称）' : ''}</small>}</h3>{message.kind === 'assignment_question' ? <span>{isCurrentQuestion(message) ? '等待你的回答' : message.state === 'pending' ? '历史问题' : '问题记录'}</span> : message.role === 'tool' ? <span>资料回执</span> : message.role === 'system' ? <span>执行记录</span> : null}</header>
        {quoted && quoted.messageId && <div className="pw-team-reply-context"><header><span>{quoteIsExcerpt ? '引用节选' : '引用原消息'} · {teamMessageIdentity(quoted, members) ? <TeamMemberIdentity member={teamMessageIdentity(quoted, members)!} name={teamMessageAuthor(quoted, members)} size={24} /> : teamMessageAuthor(quoted, members)}</span><Button variant="ghost" onClick={() => focusMessage(String(message.quoteMessageId))}>查看原消息</Button></header><p className="pw-team-quote-excerpt">{quotedText.slice(0, 360)}{quoteIsExcerpt ? '…' : ''}</p></div>}
        {eventRecord ? <Accordion variant="divided" className="pw-team-event-record"><AccordionItem id={`event:${message.messageId}`} title={teamGoalTitle(message.content, '查看执行记录')}>{messageContent}</AccordionItem></Accordion> : messageContent}
        {deliveryCopy && <MotionFeedback initial={false} identity={deliveryCopy} className="pw-muted">{deliveryCopy}</MotionFeedback>}{message.answer && <div className="pw-team-saved-answer"><span>已保存的回答</span><p>{message.answer}</p>{message.canRequestSavedContinuation === true && (unverifiedSavedContinuation.current.has(savedContinuationKey(message)) ? <span>续接结果待核对，请查看原分工进展。</span> : <Button variant="secondary" disabled={!canMutate} onClick={() => continueSavedAnswer(message)}>用这条回答继续原分工</Button>)}</div>}
      </article>; })}{!visibleMessages.length && <p className="pw-secondary-empty">{memberFilter ? '这名成员还没有可显示的沟通记录。' : '这次协作还没有消息。'}</p>}</section>


              </div>
            </AccordionItem>
            {simpleRoster && <AccordionItem id="roster-management" title="调整参与成员" description={`${currentMembers.length} 名在队${members.length > currentMembers.length ? ` · ${members.length - currentMembers.length} 名已退出` : ''}`}>
              <div className="pw-team-detail-body"><p className="pw-field-hint">暂停协作后可加入新成员。已有成员的名称、头像与退出操作在各自进展详情中。</p><Button variant="ghost" leadingIcon={IconAdd} disabled={!canMutate || !rosterPaused || currentMembers.length >= 4 || members.length >= 16} onClick={() => beginMemberChange('add')}>加入成员</Button></div>
            </AccordionItem>}
          </Accordion>
          {(typeof current.callsUsed === 'number' || typeof current.toolCallsUsed === 'number') && <Accordion variant="divided" className="pw-team-run-facts"><AccordionItem id="run-usage" title="本次额度与执行范围"><dl className="pw-team-facts">{typeof current.callsUsed === 'number' && <div><dt>模型调用</dt><dd>{current.callsUsed}{typeof current.callLimit === 'number' ? ` / ${current.callLimit}` : ''} 次</dd></div>}{typeof current.toolCallsUsed === 'number' && <div><dt>工具操作</dt><dd>{current.toolCallsUsed}{typeof current.toolCallLimit === 'number' ? ` / ${current.toolCallLimit}` : ''} 次</dd></div>}{typeof current.externalTurnsUsed === 'number' && current.externalTurnLimit > 0 && <div><dt>电脑执行</dt><dd>{current.externalTurnsUsed} / {current.externalTurnLimit} 轮</dd></div>}</dl><p className="pw-field-hint">继续沿用本次剩余额度。额外范围与预算在所属任务中处理。</p></AccordionItem></Accordion>}
        </TabPanel>
      </Tabs>
    </article>}
    <SettingsDialog className="pw-team-modal" isOpen={!!memberDraft && !review} title={memberDraft?.action === 'add' ? '加入本次协作' : memberDraft?.action === 'exit' ? '退出本次协作' : '本次协作中的名称与头像'} onClose={() => { if (!busy) memberFormDraft.guard(() => setMemberDraft(null)); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">{memberDraft && <>
      {error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}
      <FormDraftNotice draft={memberFormDraft} />{draftRecovery}
      <TeamMemberIdentity member={{ ...memberDraft, id: memberDraft.memberId }} size={40} />
      {memberDraft.action === 'exit' ? <>
        <p>保留这名成员的历史消息和产出，不把旧消息、资料或分工转给同名新成员。退出不会撤销已经发生的效果。</p>
        {current?.researchExecutionFormat && <p>退出研究后，已完成成果仍以原作者与原资料授权核验，范围撤回、换版或到期后不能继续用于新回答。退出成员的“资料”页只保留核对与撤回，不自动续权；未回答问题、待批或未知操作会阻止退出。</p>}
        {current?.leaderId === memberDraft.memberId && currentMembers.length > 1 && <SelectField label="由谁接任汇总" value={memberDraft.newLeaderId} options={[{ id: '', label: '选择接任成员' }, ...currentMembers.filter(member => member.id !== memberDraft.memberId).map(member => ({ id: member.id, label: name(member.id) }))]} onChange={value => setMemberDraft({ ...memberDraft, newLeaderId: value })} isDisabled={busy} />}
        {currentMembers.length === 1 && <p className="pw-field-hint">最后一名成员退出后，协作将等待你加入新成员，不会自行恢复。</p>}
        {members.some(member => !member.exited && rows(member.dependsOn).includes(memberDraft.memberId)) && <p className="pw-field-hint">仍有成员依赖这份产出。核对时会阻止遗留未完成前置分工，不会静默重派。</p>}
      </> : <>
        <TextField label="成员名称" value={memberDraft.name} onChange={value => setMemberDraft({ ...memberDraft, name: value })} maxLength={48} isRequired isDisabled={busy} />
        <div className="pw-team-avatar-picker" role="group" aria-label="成员头像"><span className="pw-field-hint">头像形态</span><div>{teamAvatarShapes.map((shape, index) => <Button key={shape} variant="ghost" aria-label={`选择${['柔团', '卵石', '圆方', '蛋形'][index]}头像`} aria-pressed={memberDraft.avatarShape === shape} disabled={busy} onClick={() => setMemberDraft({ ...memberDraft, avatarShape: shape })}><TeamMemberAvatar member={{ id: memberDraft.memberId, avatarShape: shape }} size={40} /><span>{['柔团', '卵石', '圆方', '蛋形'][index]}</span></Button>)}</div></div>
        {memberDraft.action === 'add' ? <>
          <TextAreaField label="职责" value={memberDraft.role} onChange={value => setMemberDraft({ ...memberDraft, role: value })} maxLength={1200} showCount isRequired isDisabled={busy} />
          <TextAreaField label="这次具体分工" value={memberDraft.assignment} onChange={value => setMemberDraft({ ...memberDraft, assignment: value })} maxLength={2000} showCount isRequired isDisabled={busy} />
          <TextField label="模型 ID，留空使用确认加入时的主模型" value={memberDraft.model} onChange={value => setMemberDraft({ ...memberDraft, model: value })} maxLength={200} isDisabled={busy} />
          <p className="pw-field-hint">新增独立的本机分析成员，以暂停状态加入；不会继承退出成员的独立资料范围、定向消息或审批，也不增加预算。核对后从成员详情明确继续。</p>
        </> : <p className="pw-field-hint">只改这次协作的显示身份，不改变职责、模型、权限或暂停状态；历史消息与正在执行的原轮次保留原名字和头像。下次工作的团队配置不变。</p>}
      </>}
      {(!reading?.viewHandle || changed) && <div className="pw-form-actions"><p className="pw-field-hint" role="status">当前记录需重新核对，编辑不会自动提交。若上次改动回执未知，会先关闭旧编辑并显示实际成员状态。</p><Button variant="secondary" disabled={busy || !current} onClick={() => { if (current) readRun(current.id); }}>重读原记录</Button></div>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => memberFormDraft.guard(() => setMemberDraft(null))}>返回并保留草稿</Button>{memberDraft.action !== 'exit' && <Button variant="ghost" disabled={busy || memberFormDraft.requiresRecheck} onClick={() => memberFormDraft.discard(() => setMemberDraft(null))}>放弃草稿</Button>}<Button disabled={!canMutate || memberDraft.action !== 'exit' && !label(memberDraft.name).trim() || memberDraft.action === 'add' && (!label(memberDraft.role).trim() || !label(memberDraft.assignment).trim()) || memberDraft.action === 'exit' && current?.leaderId === memberDraft.memberId && currentMembers.length > 1 && !memberDraft.newLeaderId} onClick={reviewMemberChange}>核对这次改动</Button></div>
    </>}</div></SettingsDialog>
    <SettingsDialog className="pw-team-modal" isOpen={!!phoneSelection} title="这次手机分工的范围" onClose={() => { if (!busy) closePhoneSelection(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">{phoneSelection && <>
      {error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}
      <p>{phoneSelection.view?.goal}</p>
      <p className="pw-field-hint">每名成员选择自己的手机与应用范围；同一应用、同一 Android 用户仍会协调占用。</p>
      {phoneMembers.map(member => {
        const choice = phoneChoices[member.memberId] || teamPhoneChoice(), selected = rows(member.options).find(option => option.optionId === choice.optionId);
        return <section className="pw-source-record" key={member.memberId}>
          <h3><TeamMemberIdentity member={rows(definition?.members).find(row => row.id === member.memberId) || { ...member, id: member.memberId }} name={teamMemberLabel({ ...member, id: member.memberId }, phoneMembers)} /></h3><p className="pw-muted">{teamPhoneTargetLabel(member.device, phoneTargets)}</p>
          <SelectField label="应用范围" value={choice.optionId} options={[{ id: '', label: '选择这名成员可使用的范围' }, ...rows(member.options).filter(option => option.available === true).map(option => ({ id: option.optionId, label: option.label }))]} onChange={id => choosePhoneScope(member, id)} isDisabled={busy} />
          {!rows(member.options).some(option => option.available === true) && <p role="status">{rows(member.options).find(option => option.reason)?.reason || '当前手机没有可用范围，请在设备页核对连接与应用权限后重新读取。'}</p>}
          {selected && <><p className="pw-field-hint">{teamPhoneScopeLabel(selected.resourceBinding)}</p>
            <TextField label="最多操作次数" value={choice.toolBudget} onChange={value => changePhoneBudget(member.memberId, 'toolBudget', value)} isDisabled={busy} hint={`原范围最多 ${selected.toolBudgetMax} 次`} />
            <TextField label="最多模型调用次数" value={choice.modelBudget} onChange={value => changePhoneBudget(member.memberId, 'modelBudget', value)} isDisabled={busy} hint={`原范围最多 ${selected.modelBudgetMax} 次；${member.device?.kind === 'paired_android' ? '由目标手机确认执行模型' : '使用本机当前已选的 API 模型'}`} />
            <TextField label="本次有效分钟数" value={choice.durationMinutes} onChange={value => changePhoneBudget(member.memberId, 'durationMinutes', value)} isDisabled={busy} hint={`不能晚于 ${new Date(selected.expiresAtMax).toLocaleString('zh-CN')}`} />
          </>}
        </section>;
      })}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closePhoneSelection}>返回</Button><Button disabled={busy || !phoneMembers.length || phoneMembers.some(member => !phoneChoices[member.memberId]?.optionId)} onClick={reviewPhoneStart}>核对本次范围</Button></div>
    </>}</div></SettingsDialog>
    <SettingsDialog className="pw-team-modal" isOpen={!!editor && !review} title={editorPurpose === 'start' ? '旧协作草稿' : definition ? '编辑编组' : '新建编组'} onClose={() => { if (!busy) formDraft.guard(() => setEditor(null)); }}><div className="pw-collaboration-dialog pw-team-editor-dialog" data-collaboration-kind="TeamsWorkspace">{editor && <><div className="pw-team-editor-scroll">
      <FormDraftNotice draft={formDraft} />{draftRecovery}
      {editorPurpose === 'start' ? <TeamQuickStartForm editor={editor} held={formDraft.requiresRecheck} disabled={busy || formDraft.loading || formDraft.requiresRecheck} onGoalChange={changeTeamGoal} onAdvanced={() => formDraft.guard(() => beginEditor(null, 'definition', editor))} /> : <>
      <Accordion defaultExpandedKeys={['team-details']}><AccordionItem id="team-details" title="成员与分工" description={ordinaryDefinitionEditor ?
        `${editor.members.length - incompleteMembers.length}/${editor.members.length} 名成员的配置已填写` : `${editor.name} · ${editor.members.length} 名成员`}>
      <TextField label="编组名称" value={editor.name} onChange={value => setEditor({ ...editor, name: value, nameEdited: true })} maxLength={80} isRequired isDisabled={busy} />
      <SelectField label="协作方式" value={editor.mode} options={[{ id: 'collaborate', label: '共同工作' }, ...(!rows(editor.members).some(member => member.runner === 'phone_agent') ? [{ id: 'debate', label: '取证与讨论' }, ...(catalog.writeAssignmentsConfigured === true || editor.mode === 'produce' ? [{ id: 'produce', label: '项目文件协作' }] : [])] : [])]} onChange={value => setEditor({ ...editor, mode: value })} isDisabled={busy} />
      {editor.mode === 'produce' && <p className="pw-field-hint">每名成员只修改各自负责的文件。从原项目任务交给这个团队时，仍需核对文件范围、独立远端任务与本机整合检查；保存配置不会启动工作。</p>}
      {discussionError && <p className="pw-field-error" role="status">{discussionError}</p>}
      <Accordion variant="divided" className="pw-team-editor-members" allowsMultipleExpanded expandedKeys={new Set(editorExpanded)} onExpandedChange={keys => setEditorExpanded(Array.from(keys, String))}>{rows(editor.members).map((member, index) => {
        const original = rows(definition?.members).find(row => row.id === member.id), external = !['model', 'phone_agent'].includes(member.runner || 'model');
        const targetKey = teamPhoneTargetKey(member.device), targetOptions = phoneTargets.map(target => ({ id: teamPhoneTargetKey(target), label: `${target.label}${target.available === false ? ' · 暂不可用' : ''}` }));
        if (targetKey && !targetOptions.some(target => target.id === targetKey)) targetOptions.push({ id: targetKey, label: '原手机目标 · 当前不可用' });
        return <AccordionItem key={member.id} id={member.id} title={<TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(editor.members))} />} description={editorMemberIssue(member) || member.role || undefined}><section id={`team-editor-member-${member.id}`} className="pw-source-record pw-team-editor-member" tabIndex={-1} aria-label={teamMemberLabel(member, rows(editor.members)) + '的配置'}>
          <TextField label={`成员 ${index + 1} 名称`} value={member.name} onChange={value => updateMember(member.id, { name: value })} maxLength={48} isRequired isDisabled={busy} />
          <div className="pw-team-avatar-picker" role="group" aria-label="成员头像"><span className="pw-field-hint">头像形态</span><div>{teamAvatarShapes.map((shape, shapeIndex) => <Button key={shape} variant="ghost" aria-label={`选择${['柔团', '卵石', '圆方', '蛋形'][shapeIndex]}头像`} aria-pressed={teamAvatarShape(member) === shape} disabled={busy} onClick={() => updateMember(member.id, { avatarShape: shape })}><TeamMemberAvatar member={{ ...member, avatarShape: shape }} size={40} /><span>{['柔团', '卵石', '圆方', '蛋形'][shapeIndex]}</span></Button>)}</div></div>
          <TextAreaField label="职责" value={member.role} onChange={value => updateMember(member.id, { role: value })} maxLength={1200} showCount isRequired isDisabled={busy} />
          <SelectField label="成员做什么" value={external ? 'computer' : member.runner || 'model'} options={[{ id: 'model', label: '分析与讨论' }, { id: 'computer', label: '在电脑上使用执行器工作' }, ...(editor.mode === 'collaborate' && !original?.writeFiles ? [{ id: 'phone_agent', label: '操作手机完成分工' }] : [])]}
            isDisabled={busy || !!original?.modelTarget} onChange={runner => updateMember(member.id, runner === 'phone_agent'
              ? { runner, model: '', modeId: undefined, externalExecutionRiskAcknowledged: undefined, device: { kind: 'local_phone', screenMode: 'background' }, assignment: member.assignment || member.role, dependsOn: member.dependsOn || [] }
              : runner === 'computer' ? { runner, model: '', modeId: '', device: { profileId: '', environmentId: '' }, externalExecutionRiskAcknowledged: false }
              : { runner, device: undefined, modeId: undefined, externalExecutionRiskAcknowledged: undefined })} />
          {member.runner === 'phone_agent' ? <>
            <SelectField label="在哪台手机上工作" value={targetKey} options={targetOptions} isDisabled={busy} onChange={key => { const target = phoneTargets.find(value => teamPhoneTargetKey(value) === key); if (target) updateMember(member.id, { device: teamPhoneDevice(target) }); }} hint="应用范围与预算在每次启动前核对。" />
          </> : external ? <TeamHarnessFields member={member} profiles={computerProfiles} run={run} disabled={busy} productionMode={editor.mode === 'produce' ? member.production?.sourceMode || 'snapshot' : undefined} onChange={changes => updateMember(member.id, changes)} onReady={ready => setExternalReady(old => old[member.id] === ready ? old : { ...old, [member.id]: ready })} />
            : <TextField label="模型 ID，留空沿用主模型" value={member.model} onChange={value => updateMember(member.id, { model: value })} maxLength={200} isDisabled={busy || !!original?.modelTarget} />}
          <TextAreaField label="具体分工" value={member.assignment || ''} onChange={value => updateMember(member.id, { assignment: value })} maxLength={2000} showCount isRequired={member.runner === 'phone_agent'} isDisabled={busy} hint={member.runner === 'phone_agent' ? '手机成员按这份具体分工执行。' : '留空沿用上面的职责；填写后按这份具体分工执行。'} />
          <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id={`member-dependencies-${member.id}`} title="先等待哪些成员完成">{rows(editor.members).filter(row => row.id !== member.id).map(row => <CheckboxField key={row.id} isSelected={(member.dependsOn || []).includes(row.id)} isDisabled={busy || evidenceDebate && !(member.dependsOn || []).includes(row.id)} onChange={selected => updateMember(member.id, { dependsOn: selected ? [...(member.dependsOn || []), row.id] : (member.dependsOn || []).filter((id: string) => id !== row.id) })}>{row.name || row.id}</CheckboxField>)}<p className="pw-field-hint">{evidenceDebate ? '取证与讨论首轮必须独立进行，请清除已有前置成员。' : '不选择时可同时开始；前置关系不能互相循环。'}</p></AccordionItem></Accordion>
          {editor.mode === 'produce' && <TeamProductionFields member={member} sources={object(catalog.productionSources)} disabled={busy} onChange={changes => updateMember(member.id, changes)} />}
          <Button variant="ghost" disabled={busy || editor.members.length <= 1} onClick={() => { const next = rows(editor.members).filter(row => row.id !== member.id).map(row => Array.isArray(row.dependsOn) ? { ...row, dependsOn: row.dependsOn.filter((id: string) => id !== member.id) } : row); setEditorExpanded(old => old.filter(id => id !== member.id)); setEditor({ ...editor, members: next, leaderId: editor.leaderId === member.id ? next[0].id : editor.leaderId }); }}>移除此成员</Button>
        </section></AccordionItem>;
      })}</Accordion>
      <Button variant="ghost" leadingIcon={IconAdd} disabled={busy || editor.members.length >= 4} onClick={addMember}>添加成员 · {editor.members.length}/4</Button>
      <SelectField label={editor.mode === 'produce' ? '默认收件人' : '负责汇总'} value={editor.leaderId} options={rows(editor.members).map(member => ({ id: member.id, label: member.name || member.id }))} onChange={value => setEditor({ ...editor, leaderId: value })} isDisabled={busy} />
      <p className="pw-field-hint">保存编组供下次选用，不会启动协作或改变已有工作。</p></AccordionItem></Accordion>
      </>}
      {editorPurpose === 'start' && discussionError && <p className="pw-field-error" role="status">{discussionError}</p>}
      {editorPurpose === 'definition' && productionError && <p className="pw-field-hint" role="status">{productionError}</p>}
      </div><footer className="pw-team-editor-footer">
        {error && <MotionFeedback identity={error} className="pw-secondary-error" role="alert">{error}</MotionFeedback>}
        {editorPurpose === 'definition' && incompleteMembers.length > 0 && <div className="pw-team-editor-missing" role="status"><span>还有 {incompleteMembers.length} 名成员需要补充</span><Button variant="ghost" disabled={busy} onClick={() => revealMember(incompleteMembers[0].id)}>{incompleteMembers[0].name || '未命名成员'}：{editorMemberIssue(incompleteMembers[0])}</Button></div>}
        {editorPurpose === 'definition' && !editor.name.trim() && <p className="pw-field-hint">填写名称后继续。</p>}
        {ordinaryDefinitionEditor && !reviewDefinitionBlocked && <p className="pw-field-hint">可打开这份编组的审阅，不会保存或开始协作。</p>}
        {busy && <p className="pw-field-hint" role="status">正在核对这次配置，请等待回执。</p>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => formDraft.guard(() => setEditor(null))}>返回并保留草稿</Button><Button variant="ghost" disabled={busy || formDraft.requiresRecheck} onClick={() => formDraft.discard(() => setEditor(null))}>放弃草稿</Button>
          {editorPurpose === 'start' ? onAskButler && !originalWorkDraft && <Button disabled={busy || formDraft.loading || !!formDraft.conflict || formDraft.requiresRecheck || !label(editor.goal).trim() || editor.goal.length > 8000} onClick={() => askButler({ goal: editor.goal })}>带目标回管家</Button> : <Button disabled={reviewDefinitionBlocked} onClick={() => { if (editorPayload) void invoke('teams.reviewDefinition', { ...editorPayload, ...(definition ? { viewHandle: reading!.viewHandle } : {}) }, reviewResult); }}>{ordinaryDefinitionEditor ? '核对这份编组' : '核对并保存编组'}</Button>}
        </div>
      </footer>
    </>}</div></SettingsDialog>
    <SettingsDialog className="pw-team-modal" isOpen={!!resumeReview} title="核对未完成的分工" closeLabel="返回原协作" onClose={closeReview}>
      {resumeReview && <div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">
        <h3><bdi>{name(resumeReview.item.locator.memberId)}</bdi></h3>
        <p dir="auto">{resumeReview.assignment}</p>
        <p>{resumeReview.deferredOwnRound ? `先继续第 ${resumeReview.rounds[0]} 阶段，沿用原范围和剩余额度。第 ${resumeReview.deferredOwnRound} 阶段仍要等前一阶段交回实际结果。` :
          `这名成员还有${resumeReview.rounds.map(round => '第 ' + round + ' 阶段').join('、')}未完成。只继续这些阶段，沿用原范围和剩余额度。`}</p>
        <p>其他成员已交回的内容保留，不重新分派。共同汇总仍按原流程处理。</p>
        {!resumeReviewCurrent && <p role="status">这项分工或原记录已变化。返回后请核对目前的进展。</p>}
        <div className="pw-form-actions"><Button variant="secondary" onClick={closeReview}>返回</Button>
          <Button disabled={!resumeReviewCurrent} onClick={confirmRemainingMember}>只继续这名成员</Button></div>
      </div>}
    </SettingsDialog>
    <SettingsDialog className="pw-team-modal" isOpen={!!openedStopped} title={openedStopped?.operation==='resumeFailedMember'?'只重试未完成的分工':'只继续已暂停的分工'} closeLabel="返回原协作" onClose={closeReview}>
      {stoppedView&&<div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">
        <h3><bdi>{name(stoppedView.memberId)}</bdi></h3>
        <p dir="auto">{stoppedView.assignment}</p>
        <p>沿用此分工原模型：<bdi>{stoppedView.memberReceiver}</bdi>。
          {stoppedView.memberSummaryReceiver!==stoppedView.memberReceiver&&<>成员整理上下文时仍用 <bdi>{stoppedView.memberSummaryReceiver}</bdi>。</>}</p>
        {openedStopped?.operation==='resumeFailedMember' ? <>
          <p>重试会把原目标、分工和验收要求发给这名成员的原模型。原范围内待处理的补充、队内消息和已获准引用的前置成果也可能一并发送。</p>
          <p><bdi>{name(stoppedView.retainedMemberId)}</bdi> 已交回的原答复保留，不重新分派。若进行共同汇总，已有答复和重试取得的新答复会发给原汇总模型 <bdi>{stoppedView.synthesisReceiver}</bdi>。只用原剩余额度。</p>
        </> : <p>保留 <bdi>{name(stoppedView.retainedMemberId)}</bdi> 已交回的原答复，不重新分派。共同汇总仍发给原模型 <bdi>{stoppedView.synthesisReceiver}</bdi>。只用原剩余额度。</p>}
        {!stoppedReviewCurrent&&<p role="status">原协作已变化，请返回核对当前进展。</p>}
        <div className="pw-form-actions"><Button variant="secondary" onClick={closeReview}>返回</Button>
          <Button disabled={!stoppedReviewCurrent} onClick={confirmStoppedMember}>{openedStopped?.operation==='resumeFailedMember'?'只重试这名成员':'只继续这名成员'}</Button></div>
      </div>}
    </SettingsDialog>
    <SettingsDialog className="pw-team-modal" isOpen={!!review && !resumeReview && !openedStopped} title={review?.operation === 'handoffFailedItem' ? '核对这一次分工交接' : review?.operation === 'reviseFromParent' ? '核对原团队修订' : review?.operation === 'continueWorkItem' ? '核对原文件分工' : review?.operation === 'configureMember' ? '核对本次成员改动' : review?.operation === 'start' ? '开始这次协作' : review?.operation === 'cancel' ? '停止这次协作' : review?.operation === 'feedback' ? '修改要求并暂停' : review?.operation === 'remove' ? '移除编组' : '保存编组'} onClose={() => { if (!busy) closeReview(); }}><div className="pw-collaboration-dialog" data-collaboration-kind="TeamsWorkspace">
      {review?.operation === 'handoffFailedItem' ? <FailedHandoffReview facts={review.input}/> : review?.operation === 'reviseFromParent' ? <TeamParentRevisionReview facts={review.input}/> : review?.operation === 'continueWorkItem' ? <TeamFileContinuationReview facts={review.input} /> : review?.operation === 'configureMember' ? <>
        <TeamMemberIdentity member={review.member || {}} name={teamMemberLabel(review.member || {}, rows(review.roster))} size={40} />
        {review.input?.action === 'rename' ? <><p>改为：</p><TeamMemberIdentity member={{ ...review.member, name: review.input.name, avatarShape: review.input.avatarShape }} size={40} /><p>历史消息和已开始的轮次保留原身份；不改职责、权限、额度或执行状态。</p></>
          : review.input?.action === 'add' ? <><p>职责：{review.member?.role}</p><p>具体分工：{review.member?.assignment}</p><p>本机模型：{review.member?.model || '确认加入时的主模型'}</p><p>以暂停状态加入当前运行，保留独立新身份；未开始工作、未继承旧成员的独立资料范围或定向消息、未增加额度。同队共享目标和公开协作记录仍可用于这次分工。回到分工页后明确继续。</p></>
          : <><p>退出后保留已有产出和历史消息，未消费的补充不再唤醒这名成员；不会把任务交给其他成员。</p>{review.researchExit && <p>仅停止这名成员后续执行。旧成果按冻结作者与原资料范围核验，接任者不继承其资料读取权；到期、换版或撤回后不可继续用于新回答，可在“资料”中核对与撤回。</p>}{review.input?.newLeaderId && <p>接任汇总：{teamMemberLabel(rows(review.roster).find(member => member.id === review.input.newLeaderId) || { id: review.input.newLeaderId }, rows(review.roster))}</p>}<p>尚未完成的前置依赖会阻止退出。暂停和退出不承诺撤销已发生的效果。</p></>}
      </> : review?.operation === 'start' ? <TeamStartReview review={review} phoneTargets={phoneTargets} /> : review?.operation === 'feedback' ? <><p>发给{review.recipientName}的新要求：</p><AIAnswer text={review.text} showActions={false} /><p>相关成员会暂停；已有成果保留，受影响的总结待更新。你选择继续后才会处理新要求，不自动扩大权限或预算。</p></> : review?.operation === 'cancel' ? <p>停止这个原运行后续工作，保留消息、证据和已有成果。停止请求不代表未知外部操作已完成清理。</p> : review?.operation === 'remove' ? <p>移除“{review.definition?.name}”的后续启动配置，已有运行和成果保留。</p> : <><p>{review?.input?.name}</p>{rows(review?.input?.members).map(member => <section className="pw-source-record" key={member.id}><h3><TeamMemberIdentity member={member} name={teamMemberLabel(member, rows(review?.input?.members))} size={40} /></h3><p>{member.role}</p>{member.assignment && member.assignment !== member.role && member.runner !== 'phone_agent' && <p>具体分工：{member.assignment}</p>}{rows(member.dependsOn).length > 0 && <p>前置：{member.dependsOn.map((id: string) => rows(review?.input?.members).find(row => row.id === id)?.name || id).join('、')}</p>}{member.runner === 'phone_agent' ? <p>{teamPhoneTargetLabel(member.device, phoneTargets)} · {member.assignment}</p> : isTeamHarness(member.runner) ? <><p>执行器：{harnessWorkerLabel(undefined, member.runner)} · 电脑：{computerLabel(member)}</p><p>模型：{member.model || '执行器默认'}{member.modeId ? ` · 模式：${member.modeId}` : ''}</p></> : <p>模型：{member.model || '实际启动时的主模型'} · 本机模型连接</p>}{review?.input?.mode === 'produce' && <TeamProductionReview member={member} sources={object(catalog.productionSources)} />}</section>)}</>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>{review?.operation==='reviseFromParent'?'我同意以上修订原文、受影响的分工与文件，以及列出的团队模型和工具额度':'已核对这次操作与原目标'}</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={async () => {
        const original = review; if (!original || entered.current || backgrounded.current || !ownsNativeWindow()) return; setReview(null); setAcknowledged(false);
        setBusy(true);
        try { if (original.operation === 'save') await formDraft.markSubmitted(); else if (original.operation === 'start') { const pending = { pendingStartTeamId: original.input.teamId, pendingStartSince: Date.now() }; if (original.draft) { const next = { ...editor, ...pending }; setEditor(next); setQuickStartPending(pendingStart(next)); formDraft.update(safeTeamDraft(next)); await formDraft.markSubmitted(); } else { const next = { goal, rounds, ...pending }; setSavedStartPending(pendingStart(next)); startDraft.update(next); await startDraft.markSubmitted(); } } else if (original.operation === 'configureMember' && memberDraft?.action !== 'exit') await memberFormDraft.markSubmitted(); else if (original.operation === 'feedback') await messageDraft.markSubmitted(); } catch (failure) { setBusy(false); setError((failure as Error).message); return; }
        if (!alive.current || !ownsNativeWindow()) return;
        const directControl = original.operation === 'cancel' || original.operation === 'feedback';
        if (original.operation === 'configureMember') { pendingMemberChange.current = original.input.runId; setReading(value => value ? { ...value, viewHandle: '' } : null); }
        if (directControl) setReading(value => value ? { ...value, viewHandle: '' } : null);
        void invoke(directControl ? 'teams.control' : 'teams.confirm', directControl ? { command: original.operation, viewHandle: original.viewHandle,
          ...(original.operation === 'feedback' ? { memberId: original.memberId, text: original.text.trim() } : {}) } : { reviewHandle: original.reviewHandle }, result => {
          if (result.confirmed !== true) throw new Error('原团队操作尚未确认，请刷新核对。');
          if (original.operation === 'start' && (result.command !== 'start' || !result.reading?.run?.id || result.reading.run.teamId !== original.input.teamId)) throw new Error('启动回执身份待核对，请读取协作记录；不会再次启动。');
          if(original.operation==='reviseFromParent'){
            if(result.command!=='reviseFromParent'||result.reading?.run?.id!==original.input.runId||result.operationId!==original.input.operationId||result.workItemId!==original.input.workItemId||result.assignmentId!==original.input.assignmentId)
              throw new Error('原修订是否已排队待核对，请刷新原协作，不要重发。');
            acceptReading(result.reading);setNotice('已提交给这次列明的原分工。新成果尚未交回，不会自动写回项目。');return;
          }
          if (original.operation === 'handoffFailedItem') {
            const row=rows(result.reading?.run?.workPlan?.items).find(i=>i.workItemId===original.input.workItemId);
            if(result.command!=='handoffFailedItem'||result.reading?.run?.id!==original.input.runId||result.oldAssignmentId!==original.input.assignmentId||
              result.nextMemberId!==original.input.nextMemberId||row?.assignmentId!==result.newAssignmentId||row?.memberId!==original.input.nextMemberId||
              !result.newAssignmentId||result.newAssignmentId===original.input.assignmentId)throw new Error('原交接是否已排队待核对，请刷新这个协作，不要重发。');
            acceptReading(result.reading);setNotice('接手已登记。只有这项分工已排队，还没有新的完成回执。');return;
          }
          if (original.operation === 'continueWorkItem') {
            if (result.command !== 'continueWorkItem' || result.reading?.run?.id !== original.input.runId
                || result.assignmentId !== original.input.assignmentId || result.workItemId !== original.input.workItemId)
              throw new Error('原分工是否已继续待核对，请只刷新原件，不重复提交。');
            acceptReading(result.reading); setNotice('原文件分工已排队继续，尚无新的完成回执。'); return;
          }
          if (original.operation === 'configureMember') {
            if (result.command !== 'configureMember' || result.reading?.run?.id !== original.input.runId) throw new Error('成员改动回执待核对，请刷新原协作，没有自动重发。');
            pendingMemberChange.current = null; if (original.input.action !== 'exit') memberFormDraft.discard(() => setMemberDraft(null)); else setMemberDraft(null); acceptReading(result.reading);
            if (original.input.action === 'add') setExpandedMembers(old => ({ ...old, [original.input.runId]: [...new Set([...(old[original.input.runId] || []), original.member.id])] }));
            if (original.input.action === 'exit' && recipient === original.input.memberId) { setRecipient(result.reading.run.leaderId || ''); setReplyId(''); }
            setNotice(original.input.action === 'add' ? '新成员已加入并暂停。核对分工后，明确继续该成员；没有追加额度。' : original.input.action === 'exit' ? '成员已退出本次协作，历史与成果保留，没有自动重派。' : '本次协作的名称与头像已更新，历史身份和团队配置保留。');
            return;
          }
          if (result.command === 'save') { const nextId = result.reading?.definition?.id || result.result?.id; if (nextId && editor?.goal) startInputs.current.set(nextId, { goal: editor.goal, rounds: editor.rounds || '1' }); formDraft.discard(() => setEditor(null)); } else setEditor(null); setNotice(result.command === 'save' ? '编组已保存，管家可按工作目标安排合适的分工。' : '已收到操作回执。');
          if (original.operation === 'feedback') {
            if (result.command !== 'feedback' || result.reading?.run?.id !== original.runId) throw new Error('补充结果待核对，请刷新原协作记录；不会自动重发。');
            setDrafts(old => old[original.draftKey] === original.text ? { ...old, [original.draftKey]: '' } : old);
            messageDraft.discard(() => { void messageDraft.open('owner', 'message:' + original.draftKey, original.draftKey, { text: '' }); });
            setNotice('新要求已保存，受影响成员已暂停。选择继续后处理，未自动重跑。');
          }
          if (result.reading) { acceptReading(result.reading); if (original.operation === 'start') { (original.draft ? formDraft : startDraft).discard(); if (original.draft) setQuickStartPending(null); else setSavedStartPending(null); if (!original.draft) { startInputs.current.delete(original.definition?.id || original.input?.teamId); setGoal(''); } } }
          else { rememberView(); release(reading); setReading(null); setTab('teams'); setCatalog(old => ({ ...old, teams: result.command === 'save' ? [...rows(old.teams).filter(team => team.id !== result.result.id), result.result] : rows(old.teams).filter(team => team.id !== original.input?.id) })); }
        }, failure => {
          // Only the bridge's explicit pre-admission refusal proves that no
          // start reached FeatureHost. Unknown receipts retain their draft lock.
          if (original.operation !== 'start' || !(failure instanceof Error) || object(failure).code !== 'not_accepted') return;
          if (original.draft && editor) {
            const { pendingStartTeamId: _team, pendingStartSince: _since, ...restored } = editor;
            setEditor(restored); setQuickStartPending(null); formDraft.update(safeTeamDraft(restored)); formDraft.clearSubmitted();
          } else if (!original.draft) {
            setSavedStartPending(null); startDraft.update({ goal, rounds }); startDraft.clearSubmitted();
          }
          setNotice('这次启动未被受理，目标与分工已保留。请重新核对后再确认开始。');
        });
      }}>{review?.operation === 'handoffFailedItem' ? '确认只交接这项分工' : review?.operation === 'reviseFromParent' ? '按此范围开始修订' : review?.operation === 'continueWorkItem' ? '继续这个原分工' : review?.operation === 'start' ? '开始协作' : review?.operation === 'save' ? '保存编组' : '确认这一次操作'}</Button></div>
    </div></SettingsDialog>
  </section>;
}



