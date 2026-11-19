export type TeamFileDecisionMark={taskId:string;runId:string;epoch:number;approvalId:string;operationId:string;artifactId:string;sourceUpdatedAt:number;planHash:string;capabilityHash:string;reviewId:string;expiresAt:number};
export function teamFileDecisionMark(value:unknown):TeamFileDecisionMark|null{
  if(!value||typeof value!=='object')return null;
  const m=value as Record<string,unknown>,id=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
  if(typeof m.capabilityHash!=='string'||!/^[a-f0-9]{64}$/.test(m.capabilityHash)||!id(m.taskId)||!id(m.runId)||!id(m.approvalId)||!id(m.operationId)||!id(m.artifactId)||!id(m.reviewId)||
      typeof m.planHash!=='string'||!/^[a-f0-9]{64}$/.test(m.planHash)||typeof m.sourceUpdatedAt!=='number'||!Number.isSafeInteger(m.sourceUpdatedAt)||m.sourceUpdatedAt<1||
      typeof m.epoch!=='number'||!Number.isSafeInteger(m.epoch)||m.epoch<1||typeof m.expiresAt!=='number'||!Number.isSafeInteger(m.expiresAt)||m.expiresAt<=0)return null;
  return {taskId:m.taskId,runId:m.runId,epoch:m.epoch,approvalId:m.approvalId,operationId:m.operationId,artifactId:m.artifactId,sourceUpdatedAt:m.sourceUpdatedAt,planHash:m.planHash,capabilityHash:m.capabilityHash,reviewId:m.reviewId,expiresAt:m.expiresAt};
}
