import {useLayoutEffect,useRef} from 'react';
import {IconChevronRight} from './icons';
import {Button} from './forms-library';
import {ReplyPhaseStatus,type ReplyActivityState} from './library/adapted/reply-activity';
import {useDocumentVisible} from './library/core';
import {MotionDisclosure} from './library/journey-motion';
import {messageReplyContext,type WorkIdentity} from './conversation-work';
import type {ConversationStream} from './conversation-stream';

type SavedReply={id?:string;role:string;at?:number;taskId?:string;memoryReference?:Record<string,any>};
type ReplyRow<M>={kind:'message';key:string;message:M;index:number;continued:boolean}|{kind:'live';key:string;draft:ConversationStream|null};
const requestKey=(conversation:string,request:WorkIdentity&{requestId:string})=>JSON.stringify([conversation,request.taskId,request.runId,request.epoch,request.requestId]);

/** A saved reply must name the exact native request AND answer receipt. Similar
 * text, a shared task ID or a completion timestamp cannot merge two answers. */
function savedRequestKey(conversation:string,message:SavedReply){
 const context=messageReplyContext(message),receipt=message.memoryReference;
 if(!context?.replyId||!receipt||receipt.taskId!==context.taskId||receipt.runId!==context.runId||receipt.coordinatorEpoch!==context.epoch||receipt.answerId!==context.replyId||
  typeof receipt.requestId!=='string'||!/^[A-Za-z0-9_-]{1,160}$/.test(receipt.requestId))return null;
 return requestKey(conversation,{...context,requestId:receipt.requestId});
}

/** A render projection only. Saved history and the native transient draft stay
 * separate data; a common keyed sibling keeps the visible answer body alive. */
export function useReplyRows<M extends SavedReply>(conversation:string,messages:readonly M[],draft:ConversationStream|null,turn:WorkIdentity|null,active:boolean):ReplyRow<M>[] {
 const visible=useDocumentVisible(),observed=useRef({conversation,requests:new Set<string>()});
 const saved=messages.map(message=>savedRequestKey(conversation,message)),counts=new Map<string,number>();
 saved.forEach(key=>{if(key)counts.set(key,(counts.get(key)||0)+1)});
 const live=draft?requestKey(conversation,draft):null;
 const rows:ReplyRow<M>[]=messages.map((message,index)=>{
  const key=saved[index],exact=key&&counts.get(key)===1?key:null;
  return {kind:'message',key:exact?`reply:${exact}`:`message:${message.id||`${message.role}:${message.at}:${index}`}`,message,index,
   continued:!!exact&&observed.current.conversation===conversation&&observed.current.requests.has(exact)};
 });
 if(turn&&(!live||!counts.has(live)))rows.push({kind:'live',key:live?`reply:${live}`:`waiting:${JSON.stringify([conversation,turn.taskId,turn.runId,turn.epoch])}`,draft});
 useLayoutEffect(()=>{
  if(observed.current.conversation!==conversation)observed.current={conversation,requests:new Set()};
  if(active&&visible&&live&&!counts.has(live)){
   observed.current.requests.add(live);
   while(observed.current.requests.size>128)observed.current.requests.delete(observed.current.requests.values().next().value!);
  }
 },[conversation,active,visible,live,messages]);
 return rows;
}

type ReplyPhase={label:string;state:ReplyActivityState};

/** Stream completion is a provisional model result, never a saved answer or a
 * task success. Failed attempts may arrive before the first text delta. */
export function replyActivityPhase(draft:ConversationStream|null,resourceState:string|undefined,activity:string):ReplyPhase{
 const hasText=!!draft?.text.trim();
 if(draft?.failed)return {state:'error',label:hasText?'回复中断，已保留内容':'回复中断'};
 if(resourceState==='queued')return {state:'waiting',label:'排队中'};
 if(resourceState==='retained')return {state:'waiting',label:'开发会话已保留'};
 if(resourceState==='stopping')return {state:'stopping',label:'正在停止'};
 if(draft?.turnEnded)return {state:'settling',label:activity||'正在整理结果'};
 return hasText?{state:'replying',label:'正在回复'}:{state:'thinking',label:activity||'正在思考'};
}
/** Only the closing label is retained; actions and activity stop immediately. */
export function ReplyActivity({phase,onProcess}:{phase:ReplyPhase|null;onProcess?:()=>void}){
 const previous=useRef<ReplyPhase|null>(null);if(phase)previous.current=phase;
 const shown=phase||previous.current;
 if(!shown)return null;
 return <MotionDisclosure open={!!phase} className="pw-reply-phase">
  <header className="pw-stream-status" data-reply-state={shown.state}>
   <span className="pw-stream-activity" role="status" aria-live="polite" aria-atomic="true">
    <ReplyPhaseStatus label={shown.label} state={shown.state} active={!!phase}/>
   </span>
   {onProcess&&<Button variant="ghost" size="small" aria-label="查看当前回复的执行过程" disabled={!phase} onClick={phase?onProcess:undefined}>执行过程<IconChevronRight size={15} aria-hidden="true"/></Button>}
  </header>
 </MotionDisclosure>;
}
