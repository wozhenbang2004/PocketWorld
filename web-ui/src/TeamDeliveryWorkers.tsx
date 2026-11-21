import {Button} from './forms-library';
type Data=Record<string,any>;
const name=(role:string)=>role==='reviewer'?'独立审阅者':'唯一整合者';
const phase=(row:Data)=>{
  switch(row.state){
    case 'needs_reconciliation':return row.role==='integrator'?'写回效果待核对':'原操作效果待核对';
    case 'stopping':return '原执行正在停止';case 'needs_status_check':return '原记录仍未结算，当前执行有待核对';
    case 'running':return '原执行尚在进行';
    case 'releasing':return '原执行正在收尾';
    case 'paused':return '已暂停';case 'cancelled':return '已取消，不代表成果已撤回';
    case 'interrupted':return '执行中断';case 'failed':return '本版未完成';
    case 'needs_confirmation':case 'awaiting_user':return '停在原任务，需核对下一步';
    default:return '原状态待核对';
  }
};
export function deliveryReading(value:Data|undefined,team:string|undefined):Data|null{
  return value?.readOnly===true&&value.teamRunId===team&&Array.isArray(value.workers)&&Array.isArray(value.unboundRoles)&&typeof value.complete==='boolean'&&
    typeof value.resourcesReleased==='boolean'&&value.workers.every((r:Data)=>r?.teamRunId===team&&['reviewer','integrator'].includes(r.role)&&!!r.taskId&&!!r.planHash)&&
    value.unboundRoles.every((role:string)=>['reviewer','integrator'].includes(role))?value:null;
}
export function TeamDeliveryWorkers({reading,stopped,disabled,open}:{reading:Data|null;stopped:boolean;disabled:boolean;open:(row:Data)=>void}){
  const original=reading?.settledOriginal;
  const settled=original?.teamRunId===reading?.teamRunId&&original?.role==='integrator'&&original?.canOpen===true&&
    original.taskStatus==='completed'&&typeof original.taskId==='string'&&typeof original.runId==='string'&&Number.isSafeInteger(original.epoch)&&original.epoch>0?original:null;
  if(!reading||!settled&&!reading.workers.length&&!reading.unboundRoles.length&&reading.complete)return null;
  return <section className="pw-team-overview" aria-label="原文件的交付进展"><h3 className="pw-team-section-title">文件交付</h3>
    {stopped&&!reading.resourcesReleased&&<p className="pw-field-hint">团队已停止后续派发，以下原执行尚未核对完。</p>}
    {reading.workers.map((r:Data)=><div className="pw-team-expanded-copy" key={r.taskId}>
      <p><strong>{name(r.role)}</strong> · {phase(r)}</p>
      <p className="pw-field-hint">文件计划 <code>{r.planHash.slice(0,12)}</code>{r.hasUnresolvedEffects?' · 没有重试或确认成功':r.sameFrozenOnly?' · 冻结文件相同，未把当时的项目现场当作当前版本':''}</p>
      {r.canOpen?<Button variant="ghost" size="small" disabled={disabled} onClick={()=>open(r)}>查看这次{r.role==='reviewer'?'独审':'整合'}任务</Button>:<p className="pw-field-hint">原执行身份待核对，不跳转其他轮次。</p>}
    </div>)}
    {settled&&<div className="pw-team-expanded-copy">
      <p className="pw-field-hint">当时的写回已核对，不代表当前项目版本或业务验收。</p>
      <Button variant="ghost" size="small" disabled={disabled} onClick={()=>open(settled)}>查看原写回任务</Button>
    </div>}
    {reading.unboundRoles.map((r:string)=><p className="pw-field-hint" key={r}>{name(r)}的本机执行仍占用资源，尚未能对应到可打开的那次任务。</p>)}
    {!reading.complete&&<p className="pw-field-hint">有原记录未能核对，不能据此判断交付已结束。可刷新当前记录。</p>}
  </section>;
}
