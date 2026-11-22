import {useLayoutEffect,useMemo,useRef,useState} from 'react';
import {bridge,type PocketSnapshot} from './bridge';
import {chatCancelKey,sameChatCancelIdentity,type ChatCancelIdentity,type ChatStopRecord} from './chat-cancel';
import {IconChevronRight} from './icons';
import {Button} from './forms-library';
import {ReplyPhaseStatus,type ReplyActivityState} from './library/adapted/reply-activity';
import {useDocumentVisible} from './library/core';
import {MotionDisclosure} from './library/journey-motion';
import {foregroundConversationTurn,messageReplyContext,type WorkIdentity} from './conversation-work';
import {foregroundConversationDraft,type ConversationStream} from './conversation-stream';

type SavedReply={id?:string;role:string;at?:number;taskId?:string;memoryReference?:Record<string,any>};
type SavedRow<M>={kind:'message';key:string;message:M;index:number;request:string|null;lastOfRole:boolean};
type ReplyRow<M>=SavedRow<M>|{kind:'live';key:string;draft:ConversationStream|null}|{kind:'stopped';key:string;text:string;truncated:boolean};
const requestKey=(conversation:string,request:WorkIdentity&{requestId:string})=>JSON.stringify([conversation,request.taskId,request.runId,request.epoch,request.requestId]);
const paintedReplyKey=(conversation:string,draft:ConversationStream)=>JSON.stringify([requestKey(conversation,draft),draft.generation,draft.sequence]);
type StopWindow={snapshot:PocketSnapshot;enabled:boolean;documentEpoch:number;navigation:number;navigationWindow:{current:number};feedback?:ChatStopRecord};
type StoppedReply={original:ChatCancelIdentity;documentEpoch:number;navigation:number;resume:number;draftEpoch:number;
 inputId:string;request:string;requestId:string;generation:number;text:string;truncated:boolean};

/** A saved reply must name the exact native request AND answer receipt. Similar
 * text, a shared task ID or a completion timestamp cannot merge two answers. */
export function savedRequestKey(conversation:string,message:SavedReply){
 const context=messageReplyContext(message),receipt=message.memoryReference;
 if(!context?.replyId||!receipt||receipt.taskId!==context.taskId||receipt.runId!==context.runId||receipt.coordinatorEpoch!==context.epoch||receipt.answerId!==context.replyId||
  typeof receipt.requestId!=='string'||!/^[A-Za-z0-9_-]{1,160}$/.test(receipt.requestId))return null;
 return requestKey(conversation,{...context,requestId:receipt.requestId});
}

function stopReplyWindow(live:PocketSnapshot,original:ChatCancelIdentity){
 const {state,composer}=live,{conversationId,taskId,runId,epoch}=original;
 const view=live.view as Record<string,unknown>|undefined,phone=live.phoneDraft as Record<string,unknown>|undefined;
 if(view?.section!==null||view.taskId!==null||typeof view.resumeRevision!=='number'||!Number.isSafeInteger(view.resumeRevision)||
    phone?.phone!==false||phone.channelOrigin!==false||
    state.conversationId!==conversationId||state.chatSubmitting||state.conversationReader||state.loadingFirstConversationMessages||
    state.filePath||state.queuedInputsPage||state.attachmentWorkspace?.open||state.chatAttachmentPickerOpen||state.quickPanelVoice||
    composer?.ready!==true||composer.key!==`conversation:${conversationId}`||composer.conversationId!==conversationId||
    typeof composer.epoch!=='number'||!Number.isSafeInteger(composer.epoch))return null;
 const tasks=Array.isArray(state.tasks)?state.tasks.filter(row=>row?.id===taskId):[],raw=tasks.length===1?tasks[0].raw:null;
 if(raw?.id!==taskId||raw.kind!=='assistant'||raw.executionMode!=='capabilities'||raw.executionRole==='owner_work'||
    raw.conversationId!==conversationId||raw.runId!==runId||raw.coordinatorEpoch!==epoch||raw.actorId!=='owner'||raw.spaceId!=='private'||
    raw.contextAvailable===false||raw.contextWithdrawn===true||raw.sourceCurrentReadRequired===true||raw.taskProjection==='source_unavailable'||
    raw.origin!=null||raw.parentTaskId!=null||raw.teamRunId!=null||raw.developmentContext!=null||
    raw.status==='completed'&&tasks[0].status==='completed')return null;
 const messages=Array.isArray(state.messages)?state.messages:[];
 for(let i=messages.length-1;i>=0;i--)if(messages[i]?.role==='user'){
  const inputId=messages[i].id;
  return typeof inputId==='string'&&inputId?{resume:view.resumeRevision,draftEpoch:composer.epoch,inputId}:null;
 }
 return null;
}
function sameStoppedWindow(clip:StoppedReply,snapshot:PocketSnapshot,navigation:number){
 if(document.hidden||bridge.getDocumentEpoch()!==clip.documentEpoch||navigation!==clip.navigation)return false;
 const window=stopReplyWindow(snapshot,clip.original);
 if(!window||window.resume!==clip.resume||window.draftEpoch!==clip.draftEpoch||window.inputId!==clip.inputId)return false;
 const turn=foregroundConversationTurn(snapshot.state),next=foregroundConversationDraft(snapshot.state);
 return (!turn||sameChatCancelIdentity(snapshot.state,clip.original))&&
  (!next||next.requestId===clip.requestId&&next.generation===clip.generation);
}

// Extract words from the committed answer body, not from the next native delta.
// Markdown code/table headers and their controls are not part of the reply.
const replyChrome='header,.pw-markdown-codebar,button,[hidden],[inert],[aria-hidden="true"]';
function printedReplyText(node:HTMLElement):string{
 if(node.matches(replyChrome))return '';
 if(node.tagName==='BR')return '\n';
 if(!node.querySelector(replyChrome))return node.innerText;
 return Array.from(node.childNodes,child=>child instanceof HTMLElement?printedReplyText(child):
  child.nodeType===Node.TEXT_NODE?child.nodeValue||'':'').join('');
}

/** A render projection only. Saved history and the native transient draft stay
 * separate data; a common keyed sibling keeps the visible answer body alive. */
export function useReplyRows<M extends SavedReply>(conversation:string,messages:readonly M[],draft:ConversationStream|null,turn:WorkIdentity|null,active:boolean,stop:StopWindow) {
 const visible=useDocumentVisible(),observed=useRef({conversation,requests:new Set<string>()});
 const liveReply=useRef<HTMLElement>(null),[stopped,setStopped]=useState<StoppedReply|null>(null);
 const {savedRows,counts}=useMemo(()=>{
  const counts=new Map<string,number>();
  let lastAssistant=-1,lastUser=-1;
  const keys=messages.map((message,index)=>{
   if(message.role==='assistant')lastAssistant=index;
   else if(message.role==='user')lastUser=index;
   const key=savedRequestKey(conversation,message);
   if(key)counts.set(key,(counts.get(key)||0)+1);
   return key;
  });
  const savedRows=messages.map((message,index):SavedRow<M>=>{
   const key=keys[index],exact=key&&counts.get(key)===1?key:null;
   return {kind:'message',key:exact?`reply:${exact}`:`message:${message.id||`${message.role}:${message.at}:${index}`}`,message,index,
    request:exact,lastOfRole:index===lastAssistant||index===lastUser};
  });
  return {savedRows,counts};
 },[conversation,messages]);
 const live=draft?requestKey(conversation,draft):null;
 const paintedKey=draft?paintedReplyKey(conversation,draft):undefined;
 const current=stopped&&active&&stop.enabled&&visible&&stop.navigation===stopped.navigation&&stop.feedback&&
  chatCancelKey(stop.feedback.original)===chatCancelKey(stopped.original)&&!counts.has(stopped.request)&&
  sameStoppedWindow(stopped,stop.snapshot,stop.navigationWindow.current)?stopped:null;
 // If the stop request was not confirmed and the original stream is still
 // visible, it stays live. The earlier clip is only a presentation fallback.
 const shown=stop.feedback?.request==='unknown'&&draft?.text.trim()?null:current;
 useLayoutEffect(()=>{
  if(!stopped)return;
  const retire=()=>setStopped(previous=>previous===stopped?null:previous);
  if(!current){retire();return;}
  const verify=()=>{if(!sameStoppedWindow(stopped,bridge.getState(),stop.navigationWindow.current))retire();};
  const unsubscribe=bridge.subscribe(verify),unbind=bridge.subscribeDocumentEpoch(verify);
  document.addEventListener('visibilitychange',verify);window.addEventListener('pagehide',retire);
  verify();
  return()=>{unsubscribe();unbind();document.removeEventListener('visibilitychange',verify);window.removeEventListener('pagehide',retire);};
 },[stopped,current,stop.navigationWindow]);
 const retainVisibleReply=(original:ChatCancelIdentity,snapshot:PocketSnapshot)=>{
  if(!active||!stop.enabled||!visible||document.hidden||bridge.getDocumentEpoch()!==stop.documentEpoch||stop.navigationWindow.current!==stop.navigation||!draft||draft.failed||draft.turnEnded||
     !draft.text.trim()||conversation!==original.conversationId||!sameChatCancelIdentity(snapshot.state,original))return;
  const window=stopReplyWindow(snapshot,original),committed=stopReplyWindow(stop.snapshot,original);
  const painted=liveReply.current,next=foregroundConversationDraft(snapshot.state);
  if(!window||!committed||window.resume!==committed.resume||window.draftEpoch!==committed.draftEpoch||window.inputId!==committed.inputId||
     !next||next.requestId!==draft.requestId||next.generation!==draft.generation||next.sequence<draft.sequence||
     draft.taskId!==original.taskId||draft.runId!==original.runId||draft.epoch!==original.epoch||
     !painted?.isConnected||painted.dataset.paintedReply!==paintedKey||painted.closest('[hidden],[inert],[aria-hidden="true"]')||!painted.getClientRects().length)return;
  const body=painted.querySelector<HTMLElement>('.pw-answer-text > .pw-markdown');
  const scroller=painted.closest<HTMLElement>('.pw-conversation-scroll');
  if(!body?.getClientRects().length||scroller?.dataset.conversationId!==conversation||getComputedStyle(body).visibility!=='visible')return;
  const content=body.getBoundingClientRect(),viewport=scroller.getBoundingClientRect();
  if(content.bottom<=viewport.top||content.top>=viewport.bottom)return;
  // A preview failure must never block the existing Stop command.
  try{
   const printed=printedReplyText(body).trim();if(!printed)return;
   const text=printed.slice(0,32768).replace(/[\uD800-\uDBFF]$/,'');
   setStopped({original,documentEpoch:stop.documentEpoch,navigation:stop.navigation,...window,
    request:requestKey(conversation,draft),requestId:draft.requestId,generation:draft.generation,text,truncated:draft.truncated||printed.length>text.length});
  }catch{/* nothing visibly safe to retain */}
 };
 const rows:ReplyRow<M>[]=shown?[...savedRows,{kind:'stopped',key:`reply:${shown.request}`,text:shown.text,truncated:shown.truncated}]:turn&&(!live||!counts.has(live))
  ? [...savedRows,{kind:'live',key:live?`reply:${live}`:`waiting:${JSON.stringify([conversation,turn.taskId,turn.runId,turn.epoch])}`,draft}]
  : savedRows;
 const seen=observed.current.conversation===conversation?observed.current.requests:null;
 useLayoutEffect(()=>{
  if(observed.current.conversation!==conversation)observed.current={conversation,requests:new Set()};
  if(active&&visible&&live&&!counts.has(live)){
   observed.current.requests.add(live);
   while(observed.current.requests.size>128)observed.current.requests.delete(observed.current.requests.values().next().value!);
  }
 },[conversation,active,visible,live,counts]);
 return {rows,continued:(row:SavedRow<M>)=>!!row.request&&!!seen?.has(row.request),paintedKey,liveReply,retainVisibleReply,clearStoppedReply:()=>setStopped(null)};
}

export type ReplyPhase={label:string;state:ReplyActivityState};

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
   <span className="pw-stream-activity">
    <ReplyPhaseStatus label={shown.label} state={shown.state} active={!!phase}/>
   </span>
   {onProcess&&<Button variant="ghost" size="small" aria-label="查看当前回复的执行过程" disabled={!phase} onClick={phase?onProcess:undefined}>执行过程<IconChevronRight size={15} aria-hidden="true"/></Button>}
  </header>
 </MotionDisclosure>;
}
