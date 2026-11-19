import type {AuditPage,TaskHeader} from './task-history';
import type {ProcessRecord} from './task-process-checkpoint';
import {phoneRunIdentity} from './phone-start';
import {isPrivateLocalPhone} from './PhonePauseInstruction';

type Row=Record<string,any>;
type Original={taskId:string;runId:string;epoch:number};
type Readback={stored?:string;event?:ProcessRecord;records:Map<string,ProcessRecord>};
const row=(v:unknown):Row|undefined=>v!==null&&typeof v==='object'&&!Array.isArray(v)?v as Row:undefined;
const positive=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0;
const statuses=new Set(['paused','awaiting_user','reconciling','failed','interrupted','cancelled','canceled','stopped','needs_confirmation','completed']);

function audioResult(value:unknown):string {
  const a=row(value);
  if(a?.status==='audio_not_configured')return 'not_configured';
  if(a?.pending===true||a?.status==='audio_recovery_pending'||positive(a?.pending))return 'pending';
  return a?.pending===0?'restored_or_preserved':'unverified';
}
function audioLine(mark:unknown,actual:unknown){
  const known=audioResult(actual);
  if(mark!==known)return '声音设置是否复原未核实。';
  if(known==='pending')return '当时的声音设置仍待复原。';
  if(known==='restored_or_preserved')return '当时已保留或恢复原声音设置。';
  if(known==='not_configured')return '这次没有配置任务声音。';
  return '声音设置是否复原未核实。';
}
function describe(source:Row|undefined,original:Original,event:boolean){
  const r=row(source?.phoneResources);
  if(!r||r.schemaVersion!==1||r.taskId!==original.taskId||r.runId!==original.runId||r.coordinatorEpoch!==original.epoch||
    r.policy!=='release_owned_display_on_exit'||r.scope!=='original_owned_display_and_activity'||!positive(r.observedAt)||
    r.leaseExpiryIsReleaseProof!==false||r.thirdPartyProcessesForceStopped!==false)return;
  if(!event&&r.state==='held_for_owner'&&['awaiting_owner','manual_takeover','reconciliation'].includes(r.reason)&&r.manualRelease==='stop_original_run')
    return '当时为你保留了独立屏，不代表现在仍占用。'+audioLine(r.audioRecovery,source?.audioRestore);
  if(!event&&r.state==='release_pending'&&r.reason==='original_cleanup_queued'&&r.releaseConfirmed===false)
    return '当时已登记清理，这笔记录没有确认独立屏已释放。';
  const release=row(source?.displayRelease);
  if(r.state==='release_unknown'&&r.reason==='native_release_unconfirmed'&&r.releaseConfirmed===false&&release?.releaseConfirmed===false)
    return '这次独立屏释放未获确认。'+audioLine(r.audioRecovery,release.audioRestore);
  if(r.state==='release_confirmed'&&r.reason==='native_release_receipt'&&r.releaseConfirmed===true&&release?.releaseConfirmed===true&&release.ok!==false){
    if(release.notInitialized===true||release.noDisplayIdentityReturned===true)return '该次准备未核实实际独立屏，不能据此说已释放一块屏。';
    return '已确认释放这次独立屏，不代表关闭了应用。'+audioLine(r.audioRecovery,release.audioRestore);
  }
}

/** Original records, not a live occupancy check. The result and an async release event may
 * describe different display sessions in the same run; never combine them into a final state. */
export function phoneResourceHistory(task:TaskHeader,page?:AuditPage):Readback|undefined {
  const raw=task.raw,original=phoneRunIdentity(task);
  if(!original||!raw||!isPrivateLocalPhone(task)||raw.executionMode!=='background'||task.executionMode&&task.executionMode!=='background'||
    raw.status!==task.status||!statuses.has(task.status)||raw.taskProjection!=='detail_window'||raw.auditBodyIncluded!==true||
    raw.contextAvailable===false||raw.sourceCurrentReadRequired===true)return;
  const out:Readback={records:new Map()};
  if(raw.taskCoreReadLimited===false)out.stored=describe(row(raw.evidence),original,false);
  if(page?.format==='pocketworld.task-audit-page.v1'&&page.taskId===original.taskId&&page.section==='events'&&
    page.executionAuthorized===false&&Array.isArray(page.entries)&&page.entries.length<=40){
    let blocked=false;
    for(const item of page.entries){
      if(item.type!=='phone_resource_release')continue;
      const e=row(item.event);
      if(item.status!=='available'||!positive(item.position)||item.eventKey!=='task:'+original.taskId+':event-order:'+item.position||
        !e||e.type!==item.type||e.taskId!==undefined&&e.taskId!==original.taskId||typeof e.runId!=='string'||!e.runId||!positive(e.coordinatorEpoch)||
        e.epoch!==undefined&&e.epoch!==e.coordinatorEpoch){blocked=true;continue;}
      const f=row(e.phoneResources);
      if(!f||f.taskId!==original.taskId||f.runId!==e.runId||f.coordinatorEpoch!==e.coordinatorEpoch){blocked=true;continue;}
      if(e.runId!==original.runId||e.coordinatorEpoch!==original.epoch)continue;
      const body=describe(e,original,true);
      if(!body){blocked=true;continue;}
      const record:ProcessRecord={section:'events',id:item.eventKey,runId:original.runId,locatable:true,label:'同轮独立屏清理回执',body};
      out.records.set(record.id,record);
      if(!blocked&&!out.event)out.event=record;
    }
  }
  return out.stored||out.records.size?out:undefined;
}
