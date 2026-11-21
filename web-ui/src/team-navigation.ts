import type {ProjectTeamReviewOrigin} from './project-team-review-origin';
import type { TeamPackageMark } from './team-delivered-package';

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
export type WorkTeamResultReturn = {
  source:WorkTeamResultSource; current:()=>boolean; retire:()=>void;
  rearm:(current:()=>boolean)=>WorkTeamResultReturn|null;
};
export const matchesWorkTeamResult=(source:WorkTeamResultSource,link:TeamOwnerWorkLink)=>link.readOnly===true&&link.currentRun===true&&link.stopping===false&&
  link.taskId===source.parentTaskId&&link.runId===source.parentRunId&&link.epoch===source.parentEpoch&&link.conversationId===source.conversationId&&
  link.teamTaskId===source.taskId&&link.teamRunId===source.teamRunId;
export const workTeamResultEntryKey=(source:WorkTeamResultSource)=>JSON.stringify(['team-result',source.parentTaskId,source.parentRunId,source.parentEpoch,source.conversationId,source.taskId,source.teamRunId]);
export type TeamRunFocus = { requestId: string; runId: string; fromProject?:ProjectTeamReviewOrigin; fromWork?: WorkFileSource|WorkProposalSource|WorkPackageSource;
  fromWorkResult?: {source:WorkTeamResultSource; arm:(current:()=>boolean)=>WorkTeamResultReturn|null}; returnToWork?: (reason?:string)=>void };
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
