import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconChevronRight} from './icons';
import {privatePhoneSurface} from './private-phone-surface';

type State=Record<string,any>;
type Choice={id:string;run:string;epoch:number;handle:string;goal:string;created:number};
const positive=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0;
const same=(a:Choice,b:Choice)=>a.id===b.id&&a.run===b.run&&a.epoch===b.epoch&&a.handle===b.handle;

// taskOwned says that something is pinned. Only individual native display sessions identify
// who; the global active task may be a main-screen task or an unrelated parallel run.
function choices(state:State):Choice[] {
  const display=state.features?.display;
  if(state.backgroundSetupOpen!==true||display?.taskOwned!==true||!Array.isArray(display.sessions)||
     !Array.isArray(state.tasks)||!Array.isArray(state.livePrivatePhones))return [];
  const held=display.sessions.filter((s:any)=>s?.taskOwned===true),out:Choice[]=[];
  for(const s of held){
    if(typeof s.ownerTaskId!=='string'||!s.ownerTaskId||typeof s.ownerRunId!=='string'||!s.ownerRunId||!positive(s.ownerEpoch)||
       held.filter((other:any)=>other.ownerTaskId===s.ownerTaskId).length!==1)continue;
    const tasks=state.tasks.filter((t:any)=>t?.id===s.ownerTaskId);
    const task=tasks.length===1?tasks[0]:undefined,raw=task?.raw;
    if(!task||raw?.executionMode!=='background'||raw.status!==task.status||raw.contextAvailable===false||raw.metadataLimited===true||
       raw.sourceCurrentReadRequired===true||raw.taskProjection==='source_unavailable'||typeof task.goal!=='string'||!task.goal.trim())continue;
    const live=privatePhoneSurface(task,state.livePrivatePhones);
    if(live?.requestedMode!=='background'||!live.activityHandle||live.runId!==s.ownerRunId||live.epoch!==s.ownerEpoch)continue;
    out.push({id:live.taskId,run:live.runId,epoch:live.epoch,handle:live.activityHandle,goal:task.goal.trim(),created:positive(raw.createdAt)?raw.createdAt:0});
  }
  return out.sort((a,b)=>a.created-b.created||(a.id<b.id?-1:a.id>b.id?1:0));
}

export function BackgroundHeldWork({state,canOpen,open}:{state:State;canOpen:boolean;open:(args:{id:string;expectedRunId:string;expectedEpoch:number})=>Promise<unknown>}) {
  const [busy,setBusy]=useState(''),[message,setMessage]=useState(''),flight=useRef(false),alive=useRef(true);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  const owners=canOpen?choices(state):[];
  const select=async(target:Choice)=>{
    if(flight.current||document.hidden)return;
    const now=bridge.getState();
    if(!now.capabilities.commands.includes('tasks.open')||!choices(now.state).some(choice=>same(choice,target))){
      setMessage('这份后台资源的归属已变化，没有打开其他工作。');return;
    }
    flight.current=true;setBusy(target.id);setMessage('');
    const epoch=bridge.getDocumentEpoch();
    try{await open({id:target.id,expectedRunId:target.run,expectedEpoch:target.epoch});}
    catch{if(alive.current&&epoch===bridge.getDocumentEpoch())setMessage('没有打开这份原任务。请按原目标从工作列表核对。');}
    finally{flight.current=false;if(alive.current)setBusy('');}
  };
  if(state.backgroundSetupOpen!==true)return null;
  if(!owners.length)return <p className="pw-field-hint" role="status">{message||'此页没有可核对的私人原任务。可从工作列表按原目标查找。'}</p>;
  return <div className="pw-background-owned-work" role="group" aria-label="查看持有后台资源的原任务">
    <span className="pw-field-hint">查看使用后台资源的原任务</span>
    {owners.map((owner,index)=>{
      const sameGoal=owners.some((other,i)=>i!==index&&other.goal===owner.goal),date=new Date(owner.created);
      const hint=sameGoal?'原记录 '+(index+1)+(owner.created&&Number.isFinite(date.getTime())?' · '+date.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false})+' 创建':''):'';
      return <Button variant="ghost" key={owner.handle} className="pw-domain-link h-auto whitespace-normal" trailingIcon={IconChevronRight}
        aria-label={'查看原任务：'+owner.goal+(hint?'，'+hint:'')} disabled={!!busy} onClick={()=>void select(owner)}><span className="pw-background-owned-label" dir="auto">
        {hint&&<small>{hint} · </small>}{owner.goal}</span></Button>;
    })}
    {(message||busy)&&<p className="pw-field-hint" role="status">{message||'正在打开所选原任务…'}</p>}
  </div>;
}
