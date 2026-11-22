import type {ProjectTeamReviewOrigin} from './project-team-review-origin';
import type { TeamPackageMark } from './team-delivered-package';
import type { OwnerWorkTeam } from './owner-work-presentation';

/** Native-verified links keep collaboration in the work that requested it. */
export type TeamOwnerWorkLink = {
  taskId: string;
  runId: string;
  epoch: number;
  conversationId: string;
  goal: string;
  currentRun: boolean;
  readOnly: true;
  teamRunId?: string;
  teamTaskId?: string;
  stopping?: boolean;
  savedResultForDraft?: true;
};
export type TeamOwnerWorkIntent = 'overview' | 'supplement' | 'followUp' | 'returnResult';

export type DraftTeamSelection = { id:string; label:string };
export const isDraftTeamSelectionId=(id:unknown):id is string=>typeof id==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id);
export type TeamButlerRequest = {
  teamId?: string;
  goal?: string;
  originalWorkDraft?: () => boolean;
};

export type TeamFileReviewMark = { planHash:string; reviewerTaskId:string; reviewerRunId:string; projectId:string; fileCount:number };
export type WorkFileSource = TeamDecisionOrigin & {fileReview:TeamFileReviewMark};
export type TeamProposalMark = {operationId:string;workItemId:string;assignmentId:string;planHash:string};
export type WorkProposalSource = TeamDecisionOrigin & {proposal:TeamProposalMark};
export function teamProposalMark(input:unknown):TeamProposalMark|null{
  if(!input||typeof input!=='object')return null;
  const x=input as Record<string,unknown>,id=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9-]{36}$/.test(v);
  return typeof x.operationId==='string'&&x.operationId.length>0&&x.operationId.length<=240&&id(x.workItemId)&&id(x.assignmentId)&&typeof x.planHash==='string'&&/^[a-f0-9]{64}$/.test(x.planHash)
    ?{operationId:x.operationId,workItemId:x.workItemId,assignmentId:x.assignmentId,planHash:x.planHash}:null;
}
export function sameTeamProposal(left:TeamProposalMark,right:unknown):boolean{
  const next=teamProposalMark(right);return !!next&&next.operationId===left.operationId&&next.workItemId===left.workItemId&&next.assignmentId===left.assignmentId&&next.planHash===left.planHash;
}
export const teamProposalEntryKey=(source:TeamDecisionOrigin)=>JSON.stringify(['team-revisions',source.conversationId,source.parentTaskId,source.parentRunId,source.parentEpoch,source.teamRunId]);
export type WorkPackageSource = TeamDecisionOrigin & {deliveredPackage:TeamPackageMark};
export type WorkTeamResultSource = TeamDecisionOrigin & {taskId:string};
export type AcceptedWorkTeamReport = {
  resultRevision:number; resultReviewDigest:string; generation:number; revision:number; messageId:string; sha256:string; bytes:number;
};
/** Only the current original result's native owner decision, never the latest Team directory row. */
export function acceptedWorkTeamReport(task:unknown,source:WorkTeamResultSource):AcceptedWorkTeamReport|undefined{
  const row=task as {id?:string;status?:string;raw?:Record<string,any>}|undefined,raw=row?.raw;
  const receipt=raw?.resultConfirmation,evidence=receipt?.teamReportEvidence;
  const id=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
  const sha=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
  if(!row||!raw||row.id!==source.parentTaskId||raw.id!==row.id||row.status!=='completed'||raw.status!==row.status||
     raw.actorId!=='owner'||raw.spaceId!=='private'||raw.executionRole!=='owner_work'||raw.origin!=null||
     raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||raw.contextAvailable!==true||
     raw.contextWithdrawn||raw.sourceCurrentReadRequired||raw.cancelRequested!==false||raw.hasUnresolvedOperations!==false||
     raw.runId!==source.parentRunId||raw.coordinatorEpoch!==source.parentEpoch||raw.conversationId!==source.conversationId||
     receipt?.completed!==true||!Number.isSafeInteger(raw.resultRevision)||raw.resultRevision<1||receipt.confirmedResultRevision!==raw.resultRevision||
     evidence?.format!=='pocketworld.owner-work-team-report.v1'||evidence.taskId!==source.parentTaskId||
     evidence.runId!==source.parentRunId||evidence.epoch!==source.parentEpoch||evidence.teamRunId!==source.teamRunId||
     !sha(evidence.resultReviewDigest)||receipt.resultReviewDigest!==evidence.resultReviewDigest||
     !Number.isSafeInteger(evidence.generation)||evidence.generation<0||!Number.isSafeInteger(evidence.revision)||evidence.revision<1||
     !id(evidence.messageId)||!sha(evidence.sha256)||!Number.isSafeInteger(evidence.bytes)||evidence.bytes<1||evidence.bytes>65_536)return;
  return {resultRevision:raw.resultRevision,resultReviewDigest:evidence.resultReviewDigest,generation:evidence.generation,
    revision:evidence.revision,messageId:evidence.messageId,sha256:evidence.sha256,bytes:evidence.bytes};
}
export function sameAcceptedWorkTeamReport(report:AcceptedWorkTeamReport,actual:AcceptedWorkTeamReport|undefined):boolean{
  return !!actual&&Object.entries(report).every(([field,value])=>actual[field as keyof AcceptedWorkTeamReport]===value);
}
export type WorkTeamResultReturn = {
  source:WorkTeamResultSource; current:()=>boolean; retire:()=>void;
  rearm:(current:()=>boolean)=>WorkTeamResultReturn|null;
};
export const matchesWorkTeamResult=(source:WorkTeamResultSource,link:TeamOwnerWorkLink)=>link.readOnly===true&&link.currentRun===true&&link.stopping===false&&
  link.taskId===source.parentTaskId&&link.runId===source.parentRunId&&link.epoch===source.parentEpoch&&link.conversationId===source.conversationId&&
  link.teamTaskId===source.taskId&&link.teamRunId===source.teamRunId;
export const workTeamResultEntryKey=(source:WorkTeamResultSource)=>JSON.stringify(['team-result',source.parentTaskId,source.parentRunId,source.parentEpoch,source.conversationId,source.taskId,source.teamRunId]);
export type WorkTeamMemberStage = { memberId:string; name:string; generation:number; memberGeneration:number; turnId:string; round:1 };
const memberRun=(value:unknown,runId:string)=>{
  const run=value as (Record<string,any>&{members:Record<string,any>[]})|undefined;
  if(!run||run.id!==runId||run.operationId!==runId||run.mode!=='collaborate'||run.rounds!==1||
     run.definitionKind!=null||run.archived===true||run.workPlan||run.productionScope||run.phoneScope||
     run.researchExecutionFormat!=='pocketworld.team-research-agent.v1'||run.discussion||
     !Array.isArray(run.members)||run.members.length!==2||!Number.isSafeInteger(run.generation)||
     !run.members.every(member=>member&&typeof member.id==='string'&&member.id&&member.runner==='model'&&member.device==='phone'&&
       member.exited!==true&&Array.isArray(member.roundStates)&&member.roundStates.length===1&&member.roundStates[0]?.round===1)||
     run.members[0].id===run.members[1].id)return;
  return run;
};

/** A display hint from the already pushed native run and the same Work receipt. No child read or control. */
export function activeWorkTeamMember(team:OwnerWorkTeam,value:unknown):WorkTeamMemberStage|undefined{
  if(!team.active||team.needsAttention||team.cancelRequested||team.status!=='running'||team.memberCount!==2||team.completedMemberCount!==1)return;
  const run=memberRun(value,team.teamRunId);
  if(!run||run.status!=='running'||run.phase!=='collaborate'||run.updatedAt!==team.updatedAt)return;
  const [first,second]=run.members;
  const member=first.status==='running'?first:second, other=member===first?second:first;
  const stage=member.roundStates[0],done=other.roundStates[0];
  if(member.status!=='running'||member.paused!==false||stage.status!=='running'||stage.generation!==member.generation||
     !Number.isSafeInteger(member.generation)||typeof stage.turnId!=='string'||!stage.turnId||
     typeof member.name!=='string'||!member.name.trim()||member.name===other.name||
     other.status!=='completed'||done.status!=='completed'||!done.researchTaskId||
     done.researchTask?.status!=='completed'||done.researchTask.taskId!==done.researchTaskId)return;
  return {memberId:member.id,name:member.name,generation:run.generation,memberGeneration:member.generation,turnId:stage.turnId,round:1};
}

export function sameWorkTeamMember(left:WorkTeamMemberStage,right:WorkTeamMemberStage|undefined):boolean{
  return !!right&&left.memberId===right.memberId&&left.name===right.name&&left.generation===right.generation&&
    left.memberGeneration===right.memberGeneration&&left.round===right.round&&left.turnId===right.turnId;
}

/** It may now be finished, but this must still be the stage selected by the owner. */
export function matchesWorkTeamMember(source:WorkTeamResultSource,selected:WorkTeamMemberStage,link:TeamOwnerWorkLink,runValue:unknown):boolean{
  if(!link||!matchesWorkTeamResult(source,link))return false;
  const run=memberRun(runValue,source.teamRunId);
  const member=run?.members.find(row=>row.id===selected.memberId),stage=member?.roundStates[0];
  return !!run&&!!member&&run.generation===selected.generation&&member.name===selected.name&&
    member.generation===selected.memberGeneration&&stage?.round===selected.round&&
    stage.turnId===selected.turnId&&stage.generation===selected.memberGeneration;
}

export type TeamRunFocus = { requestId: string; runId: string; fromProject?:ProjectTeamReviewOrigin; fromWork?: WorkFileSource|WorkProposalSource|WorkPackageSource;
  fromWorkMember?: {source:WorkTeamResultSource; selected:WorkTeamMemberStage};
  fromWorkResult?: {source:WorkTeamResultSource; arm:(current:()=>boolean)=>WorkTeamResultReturn|null;
    accepted?:{report:AcceptedWorkTeamReport;current:()=>boolean}}; returnToWork?: (reason?:string)=>void };
export function teamFileMark(input:unknown): TeamFileReviewMark | null {
  if(!input||typeof input!=='object')return null;
  const m=input as Record<string,unknown>,id=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9-]{36}$/.test(v);
  return typeof m.planHash==='string'&&/^[a-f0-9]{64}$/.test(m.planHash)&&id(m.reviewerTaskId)&&id(m.reviewerRunId)&&id(m.projectId)
    &&Number.isSafeInteger(m.fileCount)&&Number(m.fileCount)>0&&Number(m.fileCount)<=8
    ?{planHash:m.planHash,reviewerTaskId:m.reviewerTaskId,reviewerRunId:m.reviewerRunId,projectId:m.projectId,fileCount:Number(m.fileCount)}:null;
}
export function sameTeamFiles(left:TeamFileReviewMark,right:unknown):boolean {
  const next=teamFileMark(right);return !!next&&next.planHash===left.planHash&&next.reviewerTaskId===left.reviewerTaskId
    &&next.reviewerRunId===left.reviewerRunId&&next.projectId===left.projectId&&next.fileCount===left.fileCount;
}
export const teamFileEntryKey=(source:WorkFileSource)=>JSON.stringify(['team-files',source.conversationId,source.parentTaskId,source.parentRunId,source.parentEpoch,source.teamRunId,source.fileReview.planHash,source.fileReview.reviewerRunId]);

export type TeamDecisionOrigin = { parentTaskId: string; parentRunId: string; parentEpoch: number; conversationId: string; teamRunId: string };
export const teamDecisionEntryKey = (source: TeamDecisionOrigin) => JSON.stringify(['team-decisions',source.conversationId,source.parentTaskId,source.parentRunId,source.parentEpoch,source.teamRunId]);
