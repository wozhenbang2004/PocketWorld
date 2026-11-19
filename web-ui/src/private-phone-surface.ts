import { phoneRunIdentity } from './phone-start';
import { isPrivateLocalPhone } from './PhonePauseInstruction';

type Task = Record<string, any>;
const reasons={
  structure:['partial','image_unavailable'],
  local_text:['limited_structure'],
  frame:['same_frame_after_ocr','limited_structure'],
  local_geometry:[],
} as const;
type Method=keyof typeof reasons;
type Basis={method:Method;reason?:string};
export type DisplayedPhonePause = {pauseRevision:number;scopeRevision:number;displayHandle:string};
function retainedPause(value:any):DisplayedPhonePause|undefined {
  if(!value||!Number.isSafeInteger(value.pauseRevision)||value.pauseRevision<1||!Number.isSafeInteger(value.scopeRevision)||value.scopeRevision<1||
     typeof value.displayHandle!=='string'||!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value.displayHandle))return;
  return {pauseRevision:value.pauseRevision,scopeRevision:value.scopeRevision,displayHandle:value.displayHandle};
}
export function sameDisplayedPause(a?:DisplayedPhonePause,b?:DisplayedPhonePause){
  return !!a&&!!b&&a.pauseRevision===b.pauseRevision&&a.scopeRevision===b.scopeRevision&&a.displayHandle===b.displayHandle;
}
export type PrivatePhoneSurface = {
  taskId:string; runId:string; epoch:number; requestedMode:'main_screen'|'background';
  surfaceState:'bound'|'unconfirmed'|'handoff_unconfirmed'|'other_surface';
  paused:boolean; stopRequested:boolean; displayedPause?:DisplayedPhonePause; lastObservation?:{displayId:number;packageName?:string;emptyDisplayVerified?:true;basis?:Basis;changedDuringRead?:true};
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
  if(task.status==='paused'&&out.paused&&!out.stopRequested&&out.surfaceState!=='other_surface'&&out.surfaceState!=='handoff_unconfirmed')
    out.displayedPause=retainedPause(row.displayedPause);
  const obs=row.lastObservation;
  if(row.surfaceState==='bound'&&obs&&Number.isSafeInteger(obs.displayId)&&(mode==='background'?obs.displayId>0:obs.displayId===0)){
    const reading=obs.basis,method=reading?.method;
    const basis:Basis|undefined=typeof method==='string'&&Object.hasOwn(reasons,method)?{method:method as Method}:undefined;
    if(basis&&(reasons[basis.method] as readonly string[]).includes(reading.reason))basis.reason=reading.reason;
    if(obs.changedDuringRead===true){
      if(basis)out.lastObservation={displayId:obs.displayId,basis,changedDuringRead:true};
      return out; // Never carry an app identity or touch target forward from a changing frame.
    }
    out.lastObservation={displayId:obs.displayId,...(basis?{basis}:{})};
    if(obs.displayId>0&&obs.emptyDisplayVerified===true&&!obs.packageName)out.lastObservation.emptyDisplayVerified=true;
    else if(obs.emptyDisplayVerified!==true&&typeof obs.packageName==='string'&&obs.packageName.length<=255&&/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(obs.packageName))
      out.lastObservation.packageName=obs.packageName;
  }
  return out;
}
function observationLine(basis:Basis,compact:boolean):string {
  switch(basis.method){
    case 'structure':return basis.reason==='partial'?'只读到部分界面结构':
      basis.reason==='image_unavailable'?(compact?'保留可读结构':'没有取得截图，保留了仍可读的界面结构'):'已读到界面结构';
    case 'local_text':return !compact&&basis.reason==='limited_structure'?'可读结构不全，已在本机识别画面文字':'本机已识别画面文字';
    case 'frame':return basis.reason==='same_frame_after_ocr'?(compact?'改用同一张截图':'文字识别没有可用结果，回执沿用同一张截图，没有另行抓屏'):
      !compact&&basis.reason==='limited_structure'?'可读结构不全，这次读取回执包含截图':'读取回执包含截图';
    case 'local_geometry':return '已在本机核对画面位置';
  }
}
export function phoneSurfaceLabel(row:PrivatePhoneSurface,compact=false):string {
  const screen=row.requestedMode==='background'?'独立屏':'主屏';
  if(row.surfaceState==='other_surface')return '本轮已离开手机操作界面';
  if(row.surfaceState==='handoff_unconfirmed')return '原应用已移屏或位置待核对';
  if(row.surfaceState!=='bound')return '计划使用'+screen+' · 屏幕尚未核实';
  const last=row.lastObservation;
  if(!last)return '已绑定'+screen+' · 前台应用未核实';
  const actual=last.displayId>0?'独立屏 '+last.displayId:'主屏';
  const where=last.emptyDisplayVerified?'屏内没有应用':last.packageName||'应用未核实';
  if(!last.basis||last.emptyDisplayVerified)return '上次观察：'+actual+' · '+where;
  const line=observationLine(last.basis,compact);
  if(last.changedDuringRead)return compact?'上次观察：'+actual+' · 读取中画面变化':'上次观察：'+actual+' · '+line+'；读取时画面在变化，没有确认可点击的位置。';
  return compact?'上次观察：'+actual+' · '+line+' · '+where:'上次观察：'+actual+' · '+where+'。'+line+'。';
}
