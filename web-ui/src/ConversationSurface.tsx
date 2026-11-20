import {useId,useMemo,useState,type ReactNode} from 'react';
import {Button} from './forms-library';
import {IconArrowUpRight,IconChevronDown,IconChevronRight} from './icons';
import {MotionDisclosure} from './library/journey-motion';
import {ReplyActivity,type ReplyPhase} from './ConversationReply';
import type {WorkIdentity} from './conversation-work';
import {conversationTurnNotes} from './conversation-turn-notes';
import './conversation-surface.css';

const starters=[
 {label:'整理一段材料',draft:'帮我把下面的材料整理成重点和下一步：\n'},
 {label:'比较两个方案',draft:'帮我比较这两个方案，说明更适合我的选择：\n'},
];
/** Examples only enter the existing editor, never send or choose an executor. */
export function ConversationOpening({setup,editing,pending,onDraft}:{
 setup?:ReactNode;editing:boolean;pending:boolean;onDraft?:(value:string)=>void;
}){
 if(pending)return null; // The original receipt surface is the single actionable explanation.
 return <section className="pw-start pw-chat-start pw-paper-opening" aria-label="开始对话">
  <div className="pw-start-heading"><h2>想先办哪件事？</h2>
   <p>{setup?'可以先写下目标，发送前再接入模型。':editing?'写好后发送，之后还可以接着补充。' :onDraft?'直接告诉我目标，或者从一个例子开始。':'直接告诉我目标，细节可以边做边补。'}</p></div>
  {setup||!editing&&onDraft&&<ConversationStarters onDraft={onDraft}/>}
 </section>;
}

/** The first-use home can share just the two actions without adding another heading or editor. */
export function ConversationStarters({onDraft}:{onDraft:(value:string)=>void}){
 return <div className="pw-start-examples" role="group" aria-label="选择一个例子写入草稿">
  {starters.map(s=><Button key={s.label} size="small" variant="ghost" trailingIcon={IconArrowUpRight} onClick={()=>onDraft(s.draft)} aria-label={s.label+'，写入草稿'}>{s.label}</Button>)}
 </div>;
}

/** A saved input can be the last row if a job failed before it wrote any answer.
 * In that case show only the current original task's published reason, not an
 * event attributed to that unbound input. Its existing footer owns the action. */
export function ConversationLinkedReason({conversation,task}:{conversation:string;task:{id:string;goal:string;status:string;summary?:string;raw?:Record<string,any>}}){
 const raw=task.raw;
 if(!raw||!conversation||raw.id!==task.id||raw.actorId!=='owner'||raw.spaceId!=='private'||raw.conversationId!==conversation||
    raw.executionRole==='owner_work'||raw.contextAvailable===false||!['failed','interrupted','paused'].includes(task.status))return null;
 const original=task.summary||raw.summary;
 const reason=typeof original==='string'&&original!==task.goal?Array.from(original.trim()):[];
 const label=task.status==='failed'?'这项工作未完成':task.status==='paused'?'这项工作已暂停':'这项工作已中断';
 return <p className="pw-linked-reason"><strong>{label}</strong>{reason.length>0&&original!==label&&<span>{reason.slice(0,180).join('')}{reason.length>180?'…':''}</span>}</p>;
}
type Props={conversation:string;identity?:WorkIdentity|null;task?:{id:string;status:string;raw?:Record<string,any>};phase:ReplyPhase|null;
 actionElsewhere?:boolean;onRead?:(layer:'process'|'overview'|'review',trigger:HTMLButtonElement)=>void};
/** Lives in the existing keyed reply article, next to (never inside) its answer.
 * The disclosure owns only its local open/close state. All reads and decisions
 * still go through the original task window with its expected run and epoch. */
export function ConversationTurnActivity(props:Props){
 if(!props.identity&&!props.phase)return <ReplyActivity phase={null}/>;
 return <BoundTurnActivity {...props}/>;
}
function BoundTurnActivity({conversation,identity,task,phase,onRead,actionElsewhere}:Props){
 const detailId=useId(),[expanded,setExpanded]=useState(false);
 const notes=useMemo(()=>conversationTurnNotes(conversation,identity,task),[conversation,identity?.taskId,identity?.runId,identity?.epoch,task]);
 const need=notes?.need,groups=notes?.groups||[],recent=notes?.recent;
 const routine=groups.find(g=>g.kind==='returned'),important=groups.filter(g=>g.kind!=='returned');
 const visible=!!phase||!!need||groups.length>0;
 const shown=need||(phase?.state==='thinking'&&notes?.lastTool?{...phase,label:'最近调用：'+notes.lastTool}:phase);
 return <div className="pw-turn-margin" data-has-notes={visible||undefined}>
  <ReplyActivity phase={shown}/>
  {visible&&<>
   {need&&phase?.state==='error'&&phase.label!==need.label&&<p className="pw-turn-dual-status">{phase.label}</p>}
   {!!important.length&&<ul className="pw-turn-receipts" aria-label="已读取的操作回执">{important.map(g=><li key={g.kind} data-kind={g.kind}><strong>{g.title}</strong><span>{g.names}</span></li>)}</ul>}
   {need&&onRead&&!actionElsewhere&&<Button variant="secondary" size="small" className="pw-turn-next" onClick={event=>onRead(need.layer,event.currentTarget)}>{need.action}<IconChevronRight size={14} aria-hidden="true"/></Button>}
   {(!!recent||!!onRead||!!routine)&&<div className="pw-turn-progress">
    <Button size="small" variant="ghost" className="pw-turn-fold" aria-expanded={expanded} aria-controls={detailId} onClick={()=>setExpanded(v=>!v)}>
     {expanded?'收起进展':'查看进展'}{routine&&<span className="pw-turn-count">{routine.count} 条工具回执</span>}<IconChevronDown size={14} aria-hidden="true"/>
    </Button>
    <MotionDisclosure id={detailId} open={expanded}><div className="pw-turn-details">
     {routine&&<p><strong>{routine.title}</strong>{routine.names}</p>}
     {recent&&<p><strong>最近记录</strong>{recent}</p>}
     {notes?.limited&&<p>当前只能读取部分记录。</p>}
     {onRead&&<Button size="small" variant="ghost" trailingIcon={IconChevronRight} onClick={event=>onRead('process',event.currentTarget)}>查看这次工作的过程</Button>}
    </div></MotionDisclosure>
   </div>}
  </>}
 </div>;
}
