/** Only a click target. An owner-work row never carries the archive itself or a team-reading grant. */
export type TeamPackageMark={taskId:string;operationId:string;artifactId:string;archiveSha256:string;sourceDigest:string;manifestIdentity:string;archiveBytes:number;fileCount:number};
const atom=(v:unknown):v is string=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(v);
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(v);
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
export function teamPackageMark(value:unknown):TeamPackageMark|null{
  if(!value||typeof value!=='object')return null;
  const p=value as Partial<TeamPackageMark>;
  if(!atom(p.taskId)||!atom(p.operationId)||!uuid(p.artifactId)||!hash(p.archiveSha256)||!hash(p.sourceDigest)||!hash(p.manifestIdentity)
    ||typeof p.archiveBytes!=='number'||!Number.isSafeInteger(p.archiveBytes)||p.archiveBytes<=0||p.archiveBytes>20*1024*1024
    ||typeof p.fileCount!=='number'||!Number.isSafeInteger(p.fileCount)||p.fileCount<0||p.fileCount>2000)return null;
  return {taskId:p.taskId,operationId:p.operationId,artifactId:p.artifactId,archiveSha256:p.archiveSha256,sourceDigest:p.sourceDigest,
    manifestIdentity:p.manifestIdentity,archiveBytes:p.archiveBytes,fileCount:p.fileCount};
}
export function sameTeamPackage(mark:TeamPackageMark,other:unknown):boolean{
  const next=teamPackageMark(other);
  return !!next&&(Object.keys(mark) as (keyof TeamPackageMark)[]).every(key=>mark[key]===next[key]);
}
