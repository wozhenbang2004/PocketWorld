/** Native-verified links keep collaboration in the work that requested it. */
export type TeamOwnerWorkLink = {
  taskId: string;
  runId: string;
  epoch: number;
  conversationId: string;
  goal: string;
  currentRun: boolean;
  readOnly: true;
};

export type TeamButlerRequest = {
  teamId?: string;
  goal?: string;
};

export type TeamFileReviewMark = { planHash:string; reviewerTaskId:string; reviewerRunId:string; projectId:string; fileCount:number };
export type WorkFileSource = TeamDecisionOrigin & {fileReview:TeamFileReviewMark};
export type TeamRunFocus = { requestId: string; runId: string; fromWork?: WorkFileSource; returnToWork?: (reason?:string)=>void };
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
