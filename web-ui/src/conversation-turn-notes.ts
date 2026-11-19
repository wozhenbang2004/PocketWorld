// A reading projection, never a journal, approval, replay or task dispatcher.
// Only the native private conversation, task, run and epoch may lend their
// records to this particular reply. Message text is not evidence of tool use.
import type {WorkIdentity} from './conversation-work';
import {getRecentTaskProgress,getTaskOperations,presentOperation} from './task-presentation';
import {toolTitle} from './tool-catalog';

type Task={id:string;status:string;raw?:Record<string,any>};
type Group='attention'|'failed'|'occurred'|'notOccurred'|'checked'|'verified'|'returned';
export type TurnNeed={state:'error'|'waiting'|'stopping';label:string;action:string;layer:'overview'|'review'};
export type TurnNotes={need?:TurnNeed;groups:{kind:Group;title:string;count:number;names:string}[];lastTool:string;recent:string;limited:boolean};
const titles:Record<Group,string>={attention:'需要核对',failed:'调用未完成',occurred:'你已核对发生',notOccurred:'你已核对未发生',checked:'你已记录核对结果',verified:'有成功回执',returned:'收到工具回执'};
const states:Record<string,TurnNeed>={
 failed:{state:'error',label:'这次工作未完成',action:'查看失败原因',layer:'overview'},
 interrupted:{state:'error',label:'这次工作已中断',action:'查看原工作',layer:'overview'},
 paused:{state:'waiting',label:'这次工作已暂停',action:'查看原工作',layer:'overview'},
 pausing:{state:'stopping',label:'正在暂停，等待回执',action:'查看原工作',layer:'overview'},
 stopping:{state:'stopping',label:'正在停止，等待回执',action:'查看原工作',layer:'overview'},
 needs_confirmation:{state:'waiting',label:'等待你确认结果',action:'查看待确认的结果',layer:'review'},
};
const permission:TurnNeed={state:'waiting',label:'有操作需要你的授权',action:'查看授权请求',layer:'review'};
const question:TurnNeed={state:'waiting',label:'需要你补充信息',action:'查看问题',layer:'review'};
const unknown:TurnNeed={state:'waiting',label:'有操作结果待核对',action:'核对原工作',layer:'review'};
const nameOf=(name:string)=>toolTitle({name,description:'',source:'original_task',category:'task_record',configurable:false});

export function conversationTurnNotes(conversation:string,identity:WorkIdentity|null|undefined,task:Task|undefined):TurnNotes|null{
 const raw=task?.raw;
 if(!conversation||!identity||!task||!raw||!identity.runId||!Number.isSafeInteger(identity.epoch)||identity.epoch<1||
    raw.id!==task.id||task.id!==identity.taskId||raw.conversationId!==conversation||raw.actorId!=='owner'||raw.spaceId!=='private'||
    raw.contextAvailable===false||raw.executionRole==='owner_work'||raw.runId!==identity.runId||raw.coordinatorEpoch!==identity.epoch)return null;
 const recent=getRecentTaskProgress(task);
 const settled=['completed','failed','cancelled','canceled','stopped'].includes(task.status);
 let need:TurnNeed|undefined;
 // A task-wide native warning may refer to an operation outside this bounded
 // window. Do not attach its effect to the most recent tool or claim success.
 if(recent.source==='task'&&recent.status==='unknown'&&recent.title==='有操作结果待核对')need=unknown;
 else if(Object.hasOwn(states,task.status))need=states[task.status];
 else if(!settled&&['waiting_approval','awaiting_approval','waiting_permission','awaiting_permission'].includes(task.status))need=permission;
 else if(!settled&&['awaiting_user','waiting_user'].includes(task.status))need=question;
 else if(!settled&&Array.isArray(raw.approvals)&&raw.approvals.some((a:{state?:string;status?:string})=>a&&['pending','waiting'].includes(a.state||a.status||'')))need=permission;
 const groups=new Map<Group,Map<string,number>>();
 const add=(kind:Group,name:string)=>{const entries=groups.get(kind)||new Map<string,number>();entries.set(name,(entries.get(name)||0)+1);groups.set(kind,entries)};
 for(const op of getTaskOperations(task)){
  if(op.runId!==identity.runId||!op.bodyAvailable)continue;
  const row=presentOperation(op),label=nameOf(row.name);
  const receipt=op.receipt as {source?:string;outcome?:string}|undefined;
  if(row.status==='returned'&&op.state==='resolved'&&receipt?.source==='owner_reconciliation')
   add(receipt.outcome==='occurred'?'occurred':receipt.outcome==='not_occurred'?'notOccurred':'checked',label);
  else if(row.status==='returned'){
   const outcome=op.toolOutcome as {format?:string;kind?:string;nextAction?:string;automaticReplayAllowed?:boolean}|undefined;
   // An ordinary returned value is not an effect or a goal's completion.
   const verified=outcome?.format==='pocketworld.tool-outcome.v1'&&outcome.kind==='success'&&outcome.nextAction==='continue'&&outcome.automaticReplayAllowed===false;
   add(verified?'verified':'returned',label);
  }
  else if(row.status==='error')add('failed',label);
  else if(row.status==='needs_approval'||row.status==='unknown'){
   add('attention',label);if(!settled&&!need)need=row.status==='needs_approval'?permission:unknown;
  }
 }
 const recentLabel=recent.source==='event'?recent.detail:recent.source==='operation'?nameOf(recent.title)+' · '+recent.stateLabel:'';
 // A recorded failure without a readable tool receipt is still visible. A prior
 // failed call doesn't prove the whole task failed or prevent more answer text.
 if(recent.source==='event'&&recent.status==='error')add('failed',recent.detail||recent.title);
 if(recent.source==='event'&&recent.status==='unknown'&&recent.stateLabel==='结果未核实')add('attention',recent.detail||recent.title);
 const ordered=(Object.keys(titles) as Group[]).filter(kind=>groups.has(kind)).map(kind=>({kind,title:titles[kind],count:[...groups.get(kind)!.values()].reduce((a,b)=>a+b,0),
  names:[...groups.get(kind)!].map(([name,n])=>n>1?name+'（'+n+' 次）':name).join('、')}));
 return {need,groups:ordered,lastTool:recent.source==='operation'&&recent.status!=='error'&&recent.status!=='unknown'?nameOf(recent.title):'',recent:recentLabel,limited:recent.partial};
}
