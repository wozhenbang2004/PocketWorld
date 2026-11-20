type Row=Record<string,unknown>;
const labels:Record<string,string>={completed:'已结束',failed:'失败',cancelled:'已取消'};
const id=(value:unknown):value is string=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(value);
const digest=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const row=(value:unknown):Row|undefined=>value&&typeof value==='object'&&!Array.isArray(value)?value as Row:undefined;

/** CloudWorkspaceJobs.receiptForTask is the native issuer. Scope the already loaded projection to this exact view. */
export function originalHostReceipt(taskId:string,task:Row|undefined,value:Row|undefined){
  if(!task||!value||task.id!==taskId||task.actorId!=='owner'||task.spaceId!=='private'||task.feature!=='remote'||task.command!=='submitJob'||
    task.origin!=null||task.parentTaskId!=null||task.memberId!=null||task.teamRunId!=null||task.metadataLimited===true||
    task.contextAvailable===false||task.contextWithdrawn===true||task.sourceCurrentReadRequired===true||task.taskProjection==='source_unavailable'||
    value.taskId!==taskId||!id(task.runId)||task.runId!==task.operationId||value.runId!==task.runId||value.epoch!==task.coordinatorEpoch||
    typeof value.epoch!=='number'||!Number.isSafeInteger(value.epoch)||value.epoch<0)return undefined;
  if(value.stage==='phone_registered'||value.stage==='host_acknowledged'&&id(value.jobId)&&
    value.jobId===task.externalId&&task.externalOperationId===task.runId)return value;
  return undefined;
}

/** A name is only a label for a uniquely matching cached owner connection, not a current reachability or login check. */
export function originalHostLocation(taskId:string,task:Row|undefined,receipt:Row|undefined,profiles:unknown){
  if(!originalHostReceipt(taskId,task,receipt))return undefined;
  const refs=row(task?.externalRefs),host=row(refs?.hostWorkspace);
  if(!host||!id(host.profileId)||!digest(host.profileBinding)||!id(host.environmentId)||!digest(host.workspaceAnchor)||
    refs?.profileId!==host.profileId||refs.environmentId!==host.environmentId||refs.worker!==host.worker||
    typeof host.serviceBase!=='string'||host.serviceBase.length>2048)return undefined;
  let endpoint:URL;
  try{endpoint=new URL(host.serviceBase);}catch{return undefined;}
  if(endpoint.protocol!=='https:'||!endpoint.hostname||endpoint.username||endpoint.password||endpoint.search||endpoint.hash)return undefined;
  const sameId=Array.isArray(profiles)?profiles.map(row).filter(p=>p?.id===host.profileId):[];
  const current=sameId.length===1&&sameId[0]?.bindingHash===host.profileBinding&&sameId[0]?.serviceBase===host.serviceBase&&sameId[0]?.hasToken===true;
  const savedName=current&&typeof sameId[0]?.name==='string'?sameId[0].name.trim():'',name=Array.from(savedName).slice(0,80).join('');
  return {hostname:endpoint.host,workspace:host.environmentId,name,current};
}

/** A phone-held original report. Deliberately no service URL, host log, admission action or business outcome. */
export function originalSeenHostEnd(row:Row|undefined):string{
  const value=row?.previouslySeenEnd;if(!row||row.stage!=='host_acknowledged'||!value||typeof value!=='object'||Array.isArray(value))return '';
  const e=value as Row;
  if(e.format!=='pocketworld.seen-host-job-end.v1'||typeof e.status!=='string'||!Object.hasOwn(labels,e.status)||row.executorStatus!==e.status||
    !['taskId','runId','jobId'].every(k=>typeof row[k]==='string'&&row[k]!==''&&e[k]===row[k])||!Number.isSafeInteger(row.epoch)||e.epoch!==row.epoch)return '';
  if(e.exitCode!==undefined&&(!Number.isInteger(e.exitCode)||typeof e.exitCode!=='number'||e.exitCode < -2147483648||e.exitCode > 2147483647))return '';
  return '手机曾收到：'+labels[e.status]+(e.exitCode===undefined?'（无已核对退出码）':' · 执行端退出码 '+e.exitCode)+'。';
}
