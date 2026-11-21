import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {privatePhoneSurface,type DisplayedPhonePause} from './private-phone-surface';
import {taskAuditPage,type AuditPage,type TaskHeader} from './task-history';

type Row=Record<string,any>;
type Observation={
  taskId:string; runId:string; epoch:number; eventKey:string; position:number; renderRun:string;
  observationId:string; sessionId:string; readPause:number;
};
export type PausedMainPhoneRead=Observation&{rowRevision:number;pause:DisplayedPhonePause};
export type MainPhoneReadVisit={pin:PausedMainPhoneRead;current:()=>boolean;returnToOverview:()=>boolean};
const token=(value:unknown):value is string=>typeof value==='string'&&value.length<=240&&!!value.trim();
const positive=(value:unknown):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>0;

/** An already committed observation, not the current screen, a launch target, or authority. */
export function originalMainPhoneObservation(taskId:string,raw:Row,row:Row):Observation|undefined{
  if(row.type!=='observation'||row.status!=='available'||row.eventKey!==`task:${taskId}:event-order:${row.position}`)return;
  const e=row.event,r=e?.receipt,b=r?.observationBinding;
  // Android's native observation binding carries run/epoch even when the Runner event
  // itself only carries taskId. renderRun is just the existing audit row's render key.
  if(e?.type!=='observation'||e.taskId!==taskId||e.seq!==row.position||e.tool!=='phone_observe'||e.status!=='completed'||
     e.runId!==undefined&&e.runId!==raw.runId||e.coordinatorEpoch!==undefined&&e.coordinatorEpoch!==raw.coordinatorEpoch||
     e.epoch!==undefined&&e.epoch!==raw.coordinatorEpoch||
     r?.ok!==true||r.changedDuringRead!==false||r.executionMode!=='main'||r.displayId!==0||
     b?.taskId!==taskId||b.runId!==raw.runId||b.epoch!==raw.coordinatorEpoch||b.displayId!==0||
     !token(r.observationId)||r.observationId!==b.observationId||!token(r.sessionId)||r.sessionId!==b.sessionId||
     !Number.isSafeInteger(r.pauseRevision)||r.pauseRevision<0||r.pauseRevision!==b.pauseRevision||
     r.treeSchemaVersion!==1||r.observationSources?.treeAvailable!==true||r.observationSources?.treeReason!=='accessibility_tree'||
     !Array.isArray(r.nodes)||!r.nodes.length)return;
  return {taskId,runId:b.runId,epoch:b.epoch,eventKey:row.eventKey,position:row.position,renderRun:e.runId??'',
    observationId:r.observationId,sessionId:r.sessionId,readPause:r.pauseRevision};
}

export function pausedMainPhoneRead(task:TaskHeader,livePhones:unknown,eventKey?:string):PausedMainPhoneRead|undefined{
  const raw=task.raw;
  if(!raw||!isPrivateLocalPhone(task)||task.status!=='paused'||raw.status!=='paused'||raw.cancelRequested===true||
     task.executionMode!=='main_screen'||raw.executionMode!=='main_screen'||raw.taskProjection!=='detail_window'||
     raw.taskCoreReadLimited!==false||raw.auditBodyIncluded!==true||raw.metadataLimited===true||
     raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||
     raw.teamRunId!=null||raw.teamAssignment!=null||raw.memberId!=null||
     raw.hasUnresolvedOperations!==false||raw.historyHasUnconfirmedOperations!==false||
     raw.mainPhoneOwnerStep!=null||raw.ownerPhoneQuestionHold!=null)return;
  const surface=privatePhoneSurface(task,livePhones),pause=surface?.displayedPause;
  // The native displayed handle exists only for an actual owner pause of this controller.
  if(!pause||!surface?.paused||surface.stopRequested||surface.surfaceState!=='bound'||
     raw.executionScope?.revision!==pause.scopeRevision)return;
  try{
    const page=taskAuditPage(raw.historyEventPage,task.id,'events');
    for(const row of page.entries){
      if(!positive(row.rowRevision)||eventKey!==undefined&&row.eventKey!==eventKey)continue;
      const observation=originalMainPhoneObservation(task.id,raw,row);
      if(observation&&observation.readPause<pause.pauseRevision)return {...observation,rowRevision:row.rowRevision,pause};
    }
  }catch{/* A page outside the current loaded Work is not an original observation. */}
}

export function containsMainPhoneRead(task:TaskHeader,page:AuditPage|undefined,pin:PausedMainPhoneRead):boolean{
  if(!task.raw||!page||page.taskId!==pin.taskId||page.section!=='events'||task.id!==pin.taskId)return false;
  const row=page.entries.find(value=>value.eventKey===pin.eventKey);
  if(!row||row.rowRevision!==pin.rowRevision)return false;
  const actual=originalMainPhoneObservation(task.id,task.raw,row);
  return !!actual&&actual.runId===pin.runId&&actual.epoch===pin.epoch&&actual.position===pin.position&&
    actual.renderRun===pin.renderRun&&actual.observationId===pin.observationId&&actual.sessionId===pin.sessionId&&actual.readPause===pin.readPause;
}
