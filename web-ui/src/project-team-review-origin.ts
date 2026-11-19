/** A navigation receipt only, never an owner approval, file body or integration grant. */
const ids=['parentTaskId','parentRunId','conversationId','teamTaskId','teamRunId','projectId','checkoutId','reviewerTaskId','reviewerRunId'] as const;
const hashes=['planHash','selectionHash','snapshotIdentity','parentSourceDigest','sourceDigest'] as const;
export type ProjectTeamReviewOrigin=Record<typeof ids[number]|typeof hashes[number],string>&{parentEpoch:number;fileCount:number};
const keys=[...ids,...hashes,'parentEpoch','fileCount'] as const;
export function projectTeamReviewOrigin(value:unknown):ProjectTeamReviewOrigin|null{
  if(!value||typeof value!=='object')return null;const row=value as Record<string,unknown>;
  if(Object.keys(row).length!==keys.length||!ids.every(key=>typeof row[key]==='string'&&String(row[key]).length>0&&String(row[key]).length<=240)||
    !hashes.every(key=>typeof row[key]==='string'&&/^[a-f0-9]{64}$/.test(String(row[key])))||!Number.isSafeInteger(row.parentEpoch)||Number(row.parentEpoch)<1||
    !Number.isSafeInteger(row.fileCount)||Number(row.fileCount)<1||Number(row.fileCount)>8)return null;
  return row as ProjectTeamReviewOrigin;
}
export function sameProjectTeamReview(original:ProjectTeamReviewOrigin,other:unknown){
  const next=projectTeamReviewOrigin(other);return !!next&&keys.every(k=>original[k]===next[k]);
}
