// Read only the archived Android forest headers. A native primary package is a ranking,
// not a unique foreground app. Match OwnerObservedWindows without querying a live device.
type Row=Record<string,any>;
type Original={taskId:string;runId:string;epoch:number};
const pkg=/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const integer=(n:unknown):n is number=>typeof n==='number'&&Number.isSafeInteger(n)&&n>=0&&n<=2147483647;
const object=(v:unknown):v is Row=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const unknown='应用未核实';

export function stoppedAppWindows(seen:Row):string {
  const windows=seen.windows,c=seen.treeCoverage;
  if(seen.treeSchemaVersion!==1||!Array.isArray(windows)||!windows.length||windows.length>32||!object(c)||
    c.windowCount!==windows.length||!integer(c.omittedWindows))return unknown;
  const ids=new Set<number>(),apps=new Set<string>();let uncertain=false,activeApp=false,activeOther=false;
  for(const w of windows){
    if(!object(w)||w.displayId!==seen.displayId||!integer(w.windowId)||ids.has(w.windowId)||!integer(w.type)||w.type<1||
      typeof w.active!=='boolean'||typeof w.focused!=='boolean')return unknown;
    ids.add(w.windowId);
    if(w.type!==1){activeOther||=w.active||w.focused;continue;} // Android TYPE_APPLICATION
    const name=w.packageName;
    if(typeof name!=='string'||name.length>255||(name!=='android'&&!pkg.test(name))){uncertain=true;continue;}
    apps.add(name);activeApp||=w.active||w.focused;
  }
  if(apps.size>1)return '读到不同应用的窗口，未认定单一应用';
  if(activeOther)return '非应用窗口处于活动或焦点状态，应用未核实';
  const name=seen.packageName,installed=seen.installedApp;
  return apps.size===1&&apps.has(name)&&activeApp&&!uncertain&&c.omittedWindows===0&&typeof name==='string'&&pkg.test(name)&&
    object(installed)&&installed.source==='android_package_manager'&&installed.packageName===name?'活动应用窗口：'+name:unknown;
}

/** Rows come from the validated newest-first native page. Do not skip newer unreadable
 * observation headers: the missing body could belong to this attempt. */
export function originalStopObservation(rows:Row[],o:Original):{entry?:Row;gap?:true} {
  for(const item of rows){
    if(item.type!=='observation')continue;
    const e=item.event;
    if(item.status!=='available'||!Number.isSafeInteger(item.position)||item.position<1||item.eventKey!=='task:'+o.taskId+':event-order:'+item.position||
      !object(e)||e.type!==item.type||e.taskId!==o.taskId||typeof e.runId!=='string'||!e.runId||!integer(e.coordinatorEpoch)||e.coordinatorEpoch<1||
      e.epoch!==undefined&&e.epoch!==e.coordinatorEpoch)return {gap:true};
    if(e.runId!==o.runId&&e.coordinatorEpoch!==o.epoch)continue;
    if(e.runId!==o.runId||e.coordinatorEpoch!==o.epoch||typeof e.tool!=='string'||!e.tool)return {gap:true};
    return e.tool==='phone_observe'?{entry:item}:{gap:true};
  }
  return {};
}
