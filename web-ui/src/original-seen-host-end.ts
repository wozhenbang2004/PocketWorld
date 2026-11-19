type Row=Record<string,unknown>;
const labels:Record<string,string>={completed:'已结束',failed:'失败',cancelled:'已取消'};
/** A phone-held original report. Deliberately no service URL, host log, admission action or business outcome. */
export function originalSeenHostEnd(row:Row|undefined):string{
  const value=row?.previouslySeenEnd;if(!row||row.stage!=='host_acknowledged'||!value||typeof value!=='object'||Array.isArray(value))return '';
  const e=value as Row;
  if(e.format!=='pocketworld.seen-host-job-end.v1'||typeof e.status!=='string'||!Object.hasOwn(labels,e.status)||row.executorStatus!==e.status||
    !['taskId','runId','jobId'].every(k=>typeof row[k]==='string'&&row[k]!==''&&e[k]===row[k])||!Number.isSafeInteger(row.epoch)||e.epoch!==row.epoch)return '';
  if(e.exitCode!==undefined&&(!Number.isInteger(e.exitCode)||typeof e.exitCode!=='number'||e.exitCode < -2147483648||e.exitCode > 2147483647))return '';
  return '手机曾收到：'+labels[e.status]+(e.exitCode===undefined?'（无已核对退出码）':' · 执行端退出码 '+e.exitCode)+'。';
}
