import { phoneRunIdentity } from './phone-start';
import { isPrivateLocalPhone } from './PhonePauseInstruction';

type Task = Record<string, any>;
export type PrivatePhoneSurface = {
  taskId:string; runId:string; epoch:number; requestedMode:'main_screen'|'background';
  surfaceState:'bound'|'unconfirmed'|'handoff_unconfirmed'|'other_surface';
  paused:boolean; stopRequested:boolean; lastObservation?:{displayId:number;packageName?:string;emptyDisplayVerified?:true};
};
/** Presentation only. Never replace the identity with the global active id or use a task's requested App as an observation. */
export function privatePhoneSurface(task:Task|undefined, collection:any):PrivatePhoneSurface|null {
  if(!task||!isPrivateLocalPhone(task)||!Array.isArray(collection))return null;
  const id=phoneRunIdentity(task);if(!id)return null;
  const matches=collection.filter(row=>row?.taskId===id.taskId && row.runId===id.runId && row.epoch===id.epoch);
  if(matches.length!==1)return null;
  const row=matches[0],mode=task.executionMode||task.raw?.executionMode;
  if(row.requestedMode!==mode||!['main_screen','background'].includes(mode)||!['bound','unconfirmed','handoff_unconfirmed','other_surface'].includes(row.surfaceState)||typeof row.paused!=='boolean'||typeof row.stopRequested!=='boolean')return null;
  const out:PrivatePhoneSurface={...id,requestedMode:mode,surfaceState:row.surfaceState,paused:row.paused,stopRequested:row.stopRequested};
  const obs=row.lastObservation;
  if(row.surfaceState==='bound'&&obs&&Number.isSafeInteger(obs.displayId)&&(mode==='background'?obs.displayId>0:obs.displayId===0)){
    out.lastObservation={displayId:obs.displayId};
    if(obs.displayId>0&&obs.emptyDisplayVerified===true&&!obs.packageName)out.lastObservation.emptyDisplayVerified=true;
    else if(obs.emptyDisplayVerified!==true&&typeof obs.packageName==='string'&&obs.packageName.length<=255&&/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(obs.packageName))
      out.lastObservation.packageName=obs.packageName;
  }
  return out;
}
export function phoneSurfaceLabel(row:PrivatePhoneSurface):string {
  const screen=row.requestedMode==='background'?'独立屏':'主屏';
  if(row.surfaceState==='other_surface')return '本轮已离开手机操作界面';
  if(row.surfaceState==='handoff_unconfirmed')return '原应用已移屏或位置待核对';
  if(row.surfaceState!=='bound')return '计划使用'+screen+' · 屏幕尚未核实';
  const last=row.lastObservation;
  if(!last)return '已绑定'+screen+' · 前台应用未核实';
  const actual=last.displayId>0?'独立屏 '+last.displayId:'主屏';
  return '上次观察：'+actual+(last.emptyDisplayVerified?' · 屏内没有应用':last.packageName?' · '+last.packageName:' · 应用未核实');
}
