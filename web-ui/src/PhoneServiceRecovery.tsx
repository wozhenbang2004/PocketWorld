import {useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {phoneRunIdentity} from './phone-start';
import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {taskAuditPage,type TaskHeader} from './task-history';

type Row=Record<string,any>;
type Source='disabled'|'not_connected'|'read_disconnected'|'port_unavailable';
const row=(v:unknown):Row|undefined=>v!==null&&typeof v==='object'&&!Array.isArray(v)?v as Row:undefined;
const positive=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0;

function stoppedService(task:TaskHeader):Source|undefined {
  const t=task.raw,id=phoneRunIdentity(task),e=row(t?.evidence);
  if(!id||!isPrivateLocalPhone(task)||!t||task.status!=='awaiting_user'||t.status!==task.status||
     t.taskProjection!=='detail_window'||t.taskCoreReadLimited!==false||t.auditBodyIncluded!==true||t.metadataLimited===true||
     t.contextAvailable===false||t.sourceCurrentReadRequired===true||t.hasUnresolvedOperations===true||t.historyHasUnconfirmedOperations===true||
     !e||e.verification!=='user_required'||e.automaticallyVerified!==false||e.requiresReconciliation===true||e.nativeSensitiveStep)return;
  const mode=t.executionMode;if(!['main_screen','background'].includes(mode)||task.executionMode&&task.executionMode!==mode)return;
  const original=(r?:Row)=>r?.taskId===id.taskId&&r.runId===id.runId&&r.epoch===id.epoch;
  const prep=row(e.originalPhoneServiceWait);
  const scoped=prep?.format==='pocketworld.phone-service-wait.v1'&&original(prep)&&prep.mode===mode;
  if(scoped&&prep.condition==='bound_port_unavailable')return 'port_unavailable';
  if(e.ok!==false||e.accepted!==false||e.requiresPause!==true||e.automaticReplayAllowed!==false)return;
  if(scoped&&e.code==='phone_preparation_unavailable'&&e.nativeDispatched===false&&e.effectStatus==='not_dispatched'){
    if(prep.condition==='accessibility_disabled')return 'disabled';
    if(prep.condition==='accessibility_not_connected')return 'not_connected';
  }
  // Existing native read failures carry their original, already-bound display. Neither a string in a
  // summary nor a current Android setting can identify why an earlier screen read stopped.
  const failure=(r?:Row)=>{
    const s=row(r?.observationScope);return !!s&&original(s)&&typeof s.sessionId==='string'&&!!s.sessionId&&
      (mode==='background'?positive(s.displayId):s.displayId===0)&&r?.error==='service_disconnected'&&
      (r.code===undefined||r.code===r.error)&&r.ok===false&&r.accepted===false&&r.verified===false&&
      r.requiresPause===true&&r.nextObservationAction==='pause'&&r.automaticReplayAllowed===false;
  };
  if(!failure(e))return;
  try{
    const page=taskAuditPage(t.historyEventPage,id.taskId,'events');
    for(const item of page.entries){
      if(item.type!=='observation')continue;
      const ev=row(item.event);
      if(item.status!=='available'||item.eventKey!=='task:'+id.taskId+':event-order:'+item.position||!ev||
         ev.taskId!==id.taskId||ev.type!==item.type||typeof ev.runId!=='string'||!ev.runId||!positive(ev.coordinatorEpoch)||
         ev.epoch!==undefined&&ev.epoch!==ev.coordinatorEpoch)return;
      if(ev.runId!==id.runId||ev.coordinatorEpoch!==id.epoch)continue;
      const r=row(ev.receipt);
      if(ev.tool==='phone_observe'&&ev.status==='failed'&&failure(r)&&r!.observationScope.sessionId===e.observationScope.sessionId&&
         r!.observationScope.displayId===e.observationScope.displayId)return 'read_disconnected';
      return; // Never reach past a newer original observation.
    }
  }catch{/* A limited or replaced window is not evidence of a disconnect. */}
}
function currentService(value:unknown){
  const r=row(value),sample=positive(r?.checkedAtUptimeMs)?r.checkedAtUptimeMs:0;
  const groups=Array.isArray(r?.groups)?r.groups.filter((g:any)=>g?.id==='mainObservation'):[];
  const checks=groups.length===1&&Array.isArray(groups[0].checks)?groups[0].checks.filter((c:any)=>c?.id==='serviceConnected'):[];
  const c=checks.length===1?checks[0]:undefined;
  const condition=sample&&c?.state==='ready'&&c.code===''?'ready':
    sample&&c?.state==='blocked'&&c.action==='accessibility'&&c.code==='accessibility_disabled'?'disabled':
    sample&&c?.state==='blocked'&&c.action==='accessibility'&&c.code==='accessibility_not_connected'?'not_connected':'unknown';
  return {sample,condition};
}

export function PhoneServiceRecovery({task,readiness,openSettings,review}:{task:TaskHeader;readiness:unknown;openSettings?:()=>Promise<unknown>;review?:()=>void}){
  const past=stoppedService(task),original=phoneRunIdentity(task);
  const [leftAt,setLeftAt]=useState<number|null>(null),[feedback,setFeedback]=useState(''),flight=useRef(false);
  if(!past||!original)return null;
  const service=currentService(readiness),afterReturn=leftAt===null||service.sample>leftAt,latest=afterReturn?service.condition:'unknown';
  const current=(action:string)=>{
    const now=bridge.getState(),view=row(now.view),tasks=Array.isArray(now.state.tasks)?now.state.tasks.filter((v:Row)=>v?.id===original.taskId):[];
    const live=tasks.length===1?tasks[0]:undefined;
    return !document.hidden&&!view?.section&&view?.taskId===original.taskId&&now.capabilities.commands.includes('history.read')&&now.capabilities.commands.includes(action)&&
      !!live&&live.raw?.runId===original.runId&&live.raw?.coordinatorEpoch===original.epoch&&stoppedService(live)===past;
  };
  const settings=async()=>{
    if(flight.current||!openSettings||!current('settings.openSystem'))return;
    const prior=currentService(bridge.getState().state.phoneReadiness);
    if(prior.condition==='ready'||past==='port_unavailable'&&prior.condition!=='disabled'&&prior.condition!=='not_connected')return;
    flight.current=true;setFeedback('');setLeftAt(prior.sample);
    try{await openSettings();}
    catch{if(current('settings.openSystem')){setLeftAt(null);setFeedback('系统设置是否打开未核实，原任务没有自动继续。');}}
    finally{flight.current=false;}
  };
  return <section className="pw-work-current-progress" aria-label="原任务的无障碍连接">
    <h3>原任务停在手机连接</h3><p>{past==='disabled'?'准备使用手机时，系统显示无障碍服务已关闭；这次手机调用没有派发。':
      past==='not_connected'?'准备使用手机时，系统开关已开启，但服务没有连上；这次手机调用没有派发。':
      past==='read_disconnected'?'这次读取屏幕时，无障碍服务断开了。这不代表此前的操作没有发生。':
      '原任务当时无法使用原手机执行环境。这条记录没有确认是什么原因，也不代表此前的操作没有发生。'}</p>
    <p className="pw-field-hint" role="status">{feedback||(!afterReturn?'尚未收到返回后的连接状态。':
      latest==='ready'?'最近核对：服务已连接。原任务没有自动继续。':latest==='disabled'?'最近核对：系统开关仍关闭。':
      latest==='not_connected'?'最近核对：开关已开启，服务尚未连接。':'目前没有可核对的新连接状态。')}</p>
    {latest!=='ready'&&(past!=='port_unavailable'||latest==='disabled'||latest==='not_connected')&&openSettings&&<Button variant="secondary" disabled={!afterReturn} onClick={()=>void settings()}>查看 Android 无障碍设置</Button>}
    {(latest==='ready'||past==='port_unavailable'&&latest==='unknown'&&afterReturn)&&review&&<Button variant="secondary" onClick={()=>{const c=currentService(bridge.getState().state.phoneReadiness).condition;if(current('tasks.review')&&(c==='ready'||past==='port_unavailable'&&c==='unknown'))review();}}>核对这项任务的可用操作</Button>}
  </section>;
}
