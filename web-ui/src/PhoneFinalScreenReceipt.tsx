import {useSyncExternalStore} from 'react';
import {bridge} from './bridge';
import {Accordion,AccordionItem} from './forms-library';
import {isPrivateLocalPhone} from './PhonePauseInstruction';
import {taskAuditPage} from './task-history';
import {originalPhoneActionFact} from './phone-stop-receipt';
import {stoppedAppWindows} from './stopped-phone-window';

type Row=Record<string,any>;
type Original={taskId:string;runId:string;epoch:number};
const methods={structure:'页面结构',local_text:'本机识别文字',local_geometry:'本机画面几何信息',frame:'画面'} as const;
const labels={
  not_allowed:'收尾阶段没有读屏。',not_entered:'本轮没有启用手机观察。',other_surface:'收尾时已切出手机工作面，没有重读最后一屏。',
  read_failed:'原执行尝试收尾读取，但没读到结果页面。',unconfirmed:'返回记录不足以核对原终屏。',
  changed:'读取时页面仍在变化，没有确认稳定结果。',empty:'原副屏当时没有前台应用。',
} as const;
/** Historical facts only: never use this as current-screen state or permission to refresh a device. */
export function finalScreenLine(row:Row|null|undefined,id:Original):string|null{
  if(!row||!id.taskId||!id.runId||!Number.isSafeInteger(id.epoch)||id.epoch<1||row.format!=='pocketworld.phone-final-page.v1'||row.taskId!==id.taskId||
     row.runId!==id.runId||row.epoch!==id.epoch||row.businessResultVerified!==false||row.containsScreenContent!==false)return null;
  if(row.state==='observed'&&typeof row.method==='string'&&Object.hasOwn(methods,row.method))
    return '当时收到了原屏幕的'+methods[row.method as keyof typeof methods]+'；是否达到目标仍需你核对。';
  return typeof row.state==='string'&&Object.hasOwn(labels,row.state)?labels[row.state as keyof typeof labels]:null;
}
const token=(v:unknown)=>typeof v==='string'&&v.trim().length>0&&v.length<=240;
const whole=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0;
function finalPlace(raw:Row,id:Original,mode:string){
  const e=raw.evidence,b=e?.observationBinding,display=e?.displayId;
  if(e?.ok!==true||e.changedDuringRead!==false||!b||b.taskId!==id.taskId||b.runId!==id.runId||b.epoch!==id.epoch||
     !token(e.observationId)||e.observationId!==b.observationId||!token(e.sessionId)||e.sessionId!==b.sessionId||
     !whole(display)||display>2147483647||display!==b.displayId||!whole(e.pauseRevision)||e.pauseRevision!==b.pauseRevision||
     raw.executionMode!==mode||!(mode==='main_screen'?display===0:mode==='background'&&display>0))return null;
  const screen=display===0?'主屏':'独立屏 '+display;
  return screen+' · '+stoppedAppWindows(e);
}
function subscribe(listener:()=>void){
  const off=bridge.subscribe(listener);document.addEventListener('visibilitychange',listener);
  return ()=>{off();document.removeEventListener('visibilitychange',listener)};
}
function originalDetail(task:Row){
  const host=bridge.getState(),v=host.view as {section?:string;taskId?:string}|undefined;
  if(document.hidden||v?.section||!v||v.taskId!==task.id||!host.capabilities.commands.includes('history.read'))return false;
  const matches=Array.isArray(host.state.tasks)?host.state.tasks.filter(r=>r?.id===task.id):[];
  const live=matches.length===1?matches[0]:null,raw=task.raw;
  return !!live&&isPrivateLocalPhone(live)&&live.status===task.status&&live.updatedAt===task.updatedAt&&raw?.id===task.id&&
    live.raw?.runId===raw.runId&&live.raw.coordinatorEpoch===raw.coordinatorEpoch&&live.raw.executionMode===raw.executionMode&&
    live.raw.contextAvailable!==false&&live.raw.sourceCurrentReadRequired!==true&&live.raw.taskProjection!=='source_unavailable'&&
    raw.status===task.status&&raw.contextAvailable!==false&&raw.sourceCurrentReadRequired!==true&&
    raw.taskProjection==='detail_window'&&raw.auditBodyIncluded===true&&raw.taskCoreReadLimited===false;
}
function actionWindow(raw:Row,id:Original){
  try {
    const page=taskAuditPage(raw.historyOperationPage,id.taskId,'operations');
    // The native page is newest first. Read every matching header in this bounded window, not just the final call.
    const rows=page.entries.flatMap(op=>originalPhoneActionFact(op,id)??[]);
    return {rows,partial:page.completeHistory!==true||page.readLimited!==false||page.hasMore};
  }catch{return null}
}
export function TaskPhoneFinalScreen({task}:{task:Row}){
  const visible=useSyncExternalStore(subscribe,()=>originalDetail(task),()=>false);
  if(!isPrivateLocalPhone(task)||!['needs_confirmation','completed'].includes(task.status))return null;
  const raw=task.raw,turn=raw?.evidence?.nativeFinalAnswer,id={taskId:task.id,runId:raw.runId,epoch:raw.coordinatorEpoch};
  const sealed=turn?.format==='pocketworld.runner-final-answer.v1'&&turn.taskId===id.taskId&&turn.runId===id.runId&&turn.coordinatorEpoch===id.epoch&&
    turn.inputBoundaryEmpty===true&&Number.isSafeInteger(turn.messageIndex)&&turn.messageIndex>=0&&Number.isSafeInteger(turn.historyLength)&&turn.historyLength>turn.messageIndex;
  const text=sealed?finalScreenLine(turn.phoneFinalPage,id):null;
  const place=visible&&text&&turn.phoneFinalPage.state==='observed'?(finalPlace(raw,id,task.executionMode)??'应用未核实'):null;
  const audit=visible?actionWindow(raw,id):null,latest=audit?.rows[0];
  if(!text&&!latest)return null;
  return <>
    {text&&<p className="pw-field-hint"><strong>收尾时的屏幕：</strong>{place&&<><bdi style={{overflowWrap:'anywhere'}}>{place}</bdi>。 </>}{text}</p>}
    {latest&&audit&&<Accordion key={JSON.stringify([id.taskId,id.runId,id.epoch])} variant="divided" className="pw-reading-disclosures" aria-label="本轮手机操作依据">
      <AccordionItem id="original-phone-actions" title="本轮操作回执" description={'最近一笔：'+latest.name+' · '+latest.result}>
        <p className="pw-reading-note">本页读到 {audit.rows.length} 笔同轮手机操作，按先后排列。收尾时的应用不代表每步都在其中完成；系统接纳请求也不是业务完成的凭据。</p>
        <ol className="list-decimal ps-5">{[...audit.rows].reverse().map(row=><li key={row.id}><strong>{row.name}：</strong>{row.result}</li>)}</ol>
        {audit.partial&&<p className="pw-reading-note">部分记录可能不在这一页，或未读到正文。可到“过程”核对原记录。</p>}
      </AccordionItem>
    </Accordion>}
    {visible&&text&&!latest&&<p className="pw-reading-note">已读窗口中没有可核对的本轮手机操作回执，不代表没有操作。</p>}
  </>;
}
