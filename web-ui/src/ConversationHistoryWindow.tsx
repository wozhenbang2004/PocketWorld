import {OriginalPrivateHostAnswer} from './OriginalPrivateHostAnswer';
import {useCallback,useEffect,useLayoutEffect,useRef,useState} from 'react';
import { IconClose, IconFolder, IconLink, IconMemory, IconQuote, IconRefresh, IconTool } from './icons';
import {Accordion,AccordionItem,Button,IconButton} from './forms-library';
import {Drawer} from './shell-library';
import {bridge} from './bridge';
import {AIAnswer,MessageActions} from './library';
import {AnswerSources,type HistoricalAnswerSource} from './AnswerSources';
import {ProvidedMemories} from './ProvidedMemories';
import {originalAnswerMemoryMessage} from './memory-history';
import {useClosingSnapshot} from './library/surface-motion';
import {PhoneReturnedReport,type PhoneReportTarget} from './PhoneReturnedReport';
import {PhoneMessageReturn} from './PhoneMessageReturn';
import {messagePhoneResult} from './phone-start';
import {MessageQuote,type MessageQuoteTarget} from './MessageQuote';
import {MessageAttachment} from './MessageAttachment';
import {RegisteredQuoteTrail,sameQuoteVisit,type HistoryQuoteVisit} from './RegisteredQuoteTrail';
import {messageReplyContext,messageTaskResult,type TaskResult} from './conversation-work';
import './conversation-history-window.css';

type Json=Record<string,any>;
type Position={index:number;offset:number;anchor:string;revision?:number};
const readerScope=(page:Json|null|undefined)=>page?JSON.stringify([bridge.getDocumentEpoch(),page.window,page.conversation]):'';
const exactRow=(page:Json|null|undefined,id:string,revision?:number):Json|null=>{
 const matches=Array.isArray(page?.messages)?page.messages.filter((row:Json)=>row.id===id):[];
 return matches.length===1&&(revision===undefined||matches[0].sourceRevision===revision)?matches[0]:null;
};
const readyPage=(page:Json|null|undefined):page is Json=>!!page&&!page.loading&&!page.error&&!page.limited;
type HistorySource={target:HistoricalAnswerSource;trigger:HTMLButtonElement;top:number;generation:number};
const sourceGeneration=()=>Number(bridge.getState().state.traceSourceRevision||0);
const hasPublicSources=(row:Json)=>row.role==='assistant'&&typeof row.id==='string'&&!!row.id&&row.retainedSources===true&&
 row.memoryReference==null&&Number.isSafeInteger(row.sourceRevision)&&row.sourceRevision>0;
const hasProvidedMemory=(row:Json)=>!!originalAnswerMemoryMessage(row,messageReplyContext(row));
function sameHistoricalAnswer(page:Json|null,target:HistoricalAnswerSource,eligible=hasPublicSources){
 return page?.window===target.readerWindow&&page.conversation===target.readerConversationId&&!page.loading&&!page.error&&!page.limited&&
  Array.isArray(page.messages)&&page.messages.filter((row:Json)=>row.id===target.messageId&&row.sourceRevision===target.readerSourceRevision&&eligible(row)).length===1;
}
export type ConversationHistoryTaskOrigin={readerWindow:number;readerConversationId:string;readerMessageId:string};
export type HistoryResultOrigin={window:number;conversationId:string;sourceRevision:number;restore:()=>boolean};
type Props={page:Json|null;covered?:boolean;onReturnToChat?:()=>void;activeDraft?:{conversation:string;key:string;epoch:number};submitting:boolean;can:(action:string)=>boolean;act:(action:string,args?:Json)=>Promise<any>;registerBack:(handler:(()=>boolean)|null)=>void;onCopy:(text:string)=>Promise<unknown>;onOpenTask:(taskId:string,entry:'overview'|'process',origin:ConversationHistoryTaskOrigin,nativeAlreadyOpen?:boolean)=>void;
 onOpenResultFiles?:(messageId:string,result:TaskResult,trigger:HTMLButtonElement,origin:HistoryResultOrigin)=>Promise<void>;onWorkRequestReturn?:(windowId:number)=>void;
 onSearchReturn?:(windowId:number)=>void};
/** A second, read-only history window. The active composer stays with its original
 * submission until the owner explicitly switches after its receipt has settled. */
export function ConversationHistoryWindow({page:nativePage,covered=false,onReturnToChat,activeDraft,submitting,can:available,act,registerBack,onCopy,onOpenTask,onOpenResultFiles,onWorkRequestReturn,onSearchReturn}:Props){
 const page=covered?null:nativePage,scope=readerScope(nativePage);
 const exit=useClosingSnapshot(page),shown:Json=exit.display??{};
 const requestOnly=!!shown.workRequest,searchOnly=!!shown.searchAnchor,backLabel=requestOnly?'返回原工作':searchOnly?'返回命中的原文':'返回原输入';
 const can=(action:string)=>available(action)&&(!requestOnly||action==='conversation.reader.close')&&
  (!searchOnly||action==='conversation.reader.close'||action==='conversation.reader.earlier');
 const scroll=useRef<HTMLDivElement>(null),live=useRef(page);live.current=page;
 const restored=useRef(''),position=useRef<Position>({index:0,offset:0,anchor:''});
 const closingFromReader=useRef(''),unresolved=useRef(''),programScroll=useRef<{scope:string;top:number}|null>(null);
 const [positionNote,setPositionNote]=useState<{scope:string;text:string}|null>(null);
 const [quoteNote,setQuoteNote]=useState('');
 const setMissingPlace=(missing:boolean,text='')=>{
  unresolved.current=missing?scope:'';
  setPositionNote(missing?{scope,text}:null);
 };
 const timer=useRef<ReturnType<typeof setTimeout>|null>(null),sending=useRef(false);
 const [pending,setPending]=useState(false),[error,setError]=useState('');
 const [historySource,setHistorySource]=useState<HistorySource|null>(null);
 const shownSource=historySource&&historySource.generation===sourceGeneration()&&sameHistoricalAnswer(page,historySource.target)?historySource:null;
 useEffect(()=>{if(historySource&&!shownSource)setHistorySource(null)},[historySource,shownSource]);
 const [historyMemory,setHistoryMemory]=useState<HistorySource|null>(null);
 const shownMemory=historyMemory&&historyMemory.generation===sourceGeneration()&&sameHistoricalAnswer(page,historyMemory.target,hasProvidedMemory)?historyMemory:null;
 useEffect(()=>{if(historyMemory&&!shownMemory)setHistoryMemory(null)},[historyMemory,shownMemory]);
 const memoryBack=useRef<(()=>boolean)|null>(null);
 const registerMemoryBack=useCallback((handler:(()=>boolean)|null)=>{memoryBack.current=handler},[]);
 const canReadMemory=can('memoryHistory.open')&&can('taskMemory.answer')&&can('taskMemory.receipt');
 const [phoneOriginal,setPhoneOriginal]=useState<PhoneReportTarget|null>(null);
 const [quoteVisit,setQuoteVisit]=useState<(HistoryQuoteVisit&{scope:string})|null>(null);
 const visibleVisit=quoteVisit&&quoteVisit.scope===scope&&sameQuoteVisit(page,quoteVisit)?quoteVisit:null;
 const [reuse,setReuse]=useState<MessageQuoteTarget|null>(null);
 const canReuse=!!visibleVisit&&activeDraft?.conversation===visibleVisit.conversation&&!submitting&&can('messageQuote.open')&&can('messageQuote.stage')&&can('conversation.reader.close');
 const priorReview=canReuse&&reuse?.prior&&reuse.key===activeDraft?.key&&reuse.epoch===activeDraft?.epoch&&reuse.prior.reader===visibleVisit?.window&&reuse.prior.request===visibleVisit.request&&reuse.prior.revision===visibleVisit.requestRevision&&reuse.messageId===visibleVisit.target&&reuse.sourceRevision===visibleVisit.revision&&reuse.prior.offset===visibleVisit.offset&&reuse.prior.chars===visibleVisit.chars?reuse:null;
 const [firstQuote,setFirstQuote]=useState<{scope:string;target:MessageQuoteTarget}|null>(null);
 const [quoteProblem,setQuoteProblem]=useState<{scope:string;messageId:string}|null>(null);
 const canQuoteNew=!!activeDraft&&readyPage(page)&&activeDraft.conversation===page.conversation&&!submitting&&
  can('messageQuote.open')&&can('messageQuote.stage')&&can('messageQuote.close')&&can('conversation.reader.close');
 const newQuoteTarget=(message:Json):MessageQuoteTarget|null=>{
  const native=bridge.getState(),draft=native.composer,reader=native.state.conversationReader;
  const view=native.view as {section?:unknown;taskId?:unknown}|undefined;
  if(!canQuoteNew||!activeDraft||document.hidden||native.state.chatSubmitting||view?.section||view?.taskId||
   !readyPage(reader)||readerScope(reader)!==scope||native.state.conversationId!==activeDraft.conversation||
   draft?.ready!==true||draft.key!==activeDraft.key||draft.epoch!==activeDraft.epoch||draft.conversationId!==activeDraft.conversation||
   typeof message.id!=='string'||!message.id||typeof message.content!=='string'||!message.content||
   !Number.isSafeInteger(message.sourceRevision)||message.sourceRevision<1)return null;
  const source=exactRow(native.state,message.id,message.sourceRevision),shown=exactRow(reader,message.id,message.sourceRevision);
  // The history projection deliberately has no original-locator authority. Only
  // an identical, separately quotable copy in the active private window can open.
  return source?.quoteAvailable===true&&shown&&source.role===message.role&&shown.role===message.role&&
   source.content===message.content&&shown.content===message.content?
   {messageId:message.id,sourceRevision:message.sourceRevision,key:activeDraft.key,epoch:activeDraft.epoch}:null;
 };
 const firstMessage=firstQuote?.scope===scope&&exactRow(page,firstQuote.target.messageId,firstQuote.target.sourceRevision);
 const reviewing=priorReview||(firstMessage&&activeDraft?.key===firstQuote?.target.key&&activeDraft?.epoch===firstQuote?.target.epoch&&
  newQuoteTarget(firstMessage)?firstQuote?.target:null);
 useEffect(()=>{if(reuse&&!priorReview)setReuse(null);if(firstQuote&&reviewing!==firstQuote.target)setFirstQuote(null)},[reuse,priorReview,firstQuote,reviewing]);
 const moveToQuoteRow=(visit:HistoryQuoteVisit,returning:boolean)=>{
  const native=bridge.getState().state.conversationReader;
  if(document.hidden||sending.current||!scroll.current||readerScope(native)!==scope||!sameQuoteVisit(native,visit)||!sameQuoteVisit(live.current,visit))return false;
  const box=scroll.current;
  const id=returning?visit.request:visit.target;
  const row=Array.from(box.querySelectorAll<HTMLElement>('[data-reader-id]')).find(item=>item.dataset.readerId===id);
  if(!row)return false;
  const origin=returning?Array.from(row.querySelectorAll<HTMLButtonElement>('[data-quote-target]')).find(item=>item.dataset.quoteTarget===visit.target&&Number(item.dataset.quoteStart)===visit.offset&&Number(item.dataset.quoteSize)===visit.chars):null;
  const anchor=origin&&!origin.disabled?origin:row;anchor.focus({preventScroll:true});
  box.scrollTop+=anchor.getBoundingClientRect().top-box.getBoundingClientRect().top-(returning&&origin?visit.returnInset:0);return true;
 };
 const returnFromQuote=()=>{const v=visibleVisit;if(!v)return false;setQuoteVisit(null);if(!moveToQuoteRow(v,true))setQuoteNote('当前窗口还没有读到引用它的原请求，没有跳到其他消息。');return true};
 useEffect(()=>{
  // The same native history may be covered by an opened work. A temporary
  // loading or covered state hides the visit without replacing its two anchors.
  if(!quoteVisit)return;
  if(quoteVisit.scope!==scope){setQuoteVisit(null);return}
  if(!nativePage?.loading&&!sameQuoteVisit(nativePage,quoteVisit)){
   setQuoteVisit(null);setQuoteNote('当前窗口未能同时核对原请求和引用的原消息，没有跳到其他消息。');
  }
 },[quoteVisit,nativePage,scope]);
 useEffect(()=>{setQuoteNote('')},[scope]);
 useEffect(()=>{const retire=()=>{if(document.hidden){setQuoteVisit(null);setQuoteNote('');setFirstQuote(null);setHistorySource(null);setHistoryMemory(null)}};document.addEventListener('visibilitychange',retire);return()=>document.removeEventListener('visibilitychange',retire)},[]);

 const [inputNotice,setInputNotice]=useState<{window:number;messageId:string;error?:string}|null>(null);
 const [resultNotice,setResultNotice]=useState<{window:number;messageId:string;revision:number;error?:string}|null>(null);
 const returnToInput=useRef<(ConversationHistoryTaskOrigin&{taskId:string;revision:number})|null>(null);
 const rows:Json[]=Array.isArray(shown.messages)?requestOnly?(page&&page.window===shown.window&&!page.error&&shown.messages.length===1&&
  shown.messages[0]?.role==='user'&&shown.messages[0].id===shown.workRequest.messageId&&shown.messages[0].sourceRevision===shown.workRequest.revision?shown.messages:[]):shown.messages:[];
 const marker=rows.map(row=>`${row.id}:${row.sourceRevision||0}`).join('|');
 const loadingInput=(id:string)=>pending&&inputNotice?.window===shown.window&&inputNotice?.messageId===id&&!inputNotice?.error;
 const shownRow=(messageId:string,revision?:number)=>{
  if(!exactRow(live.current,messageId,revision))return;
  return Array.from(scroll.current?.querySelectorAll<HTMLElement>('[data-reader-id]')||[]).find(row=>row.dataset.readerId===messageId&&
   (revision===undefined||Number(row.dataset.readerRevision)===revision));
 };
 const remember=()=>{
  const node=scroll.current,h=live.current;
  if(!node||!readyPage(h)||restored.current!==scope||unresolved.current===scope)return;
  const items=Array.from(node.querySelectorAll<HTMLElement>('[data-reader-index]'));
  const row=items.find(item=>item.getBoundingClientRect().bottom>node.getBoundingClientRect().top)||items.at(-1);
  const anchor=row?.dataset.readerId,revision=Number(row?.dataset.readerRevision);
  if(!row||!anchor||!Number.isSafeInteger(revision)||!exactRow(h,anchor,revision))return;
  position.current={index:Number(row.dataset.readerIndex),offset:Math.max(0,Math.round(node.getBoundingClientRect().top-row.getBoundingClientRect().top)),anchor,revision};
 };
 const restoreReaderRow=(original:Json,message:Json,trigger:HTMLButtonElement,box:HTMLElement)=>{
  const ticket=readerScope(original),id=String(message.id),revision=Number(message.sourceRevision);
  const base=box.getBoundingClientRect().top,inset=trigger.getBoundingClientRect().top-base;
  const rowInset=(trigger.closest<HTMLElement>('[data-reader-id]')?.getBoundingClientRect().top??base)-base;
  return ()=>{
   const h=live.current,node=scroll.current;
   const native=bridge.getState().state.conversationReader;
   if(document.hidden||!node||readerScope(h)!==ticket||readerScope(native)!==ticket||!readyPage(h)||!readyPage(native))return false;
   const row=exactRow(native,id,revision)&&shownRow(id,revision);
   if(!row){
    setQuoteNote('当前窗口还没有读到刚才那条原消息，未跳到其他位置。');
    return false;
   }
   const focus=row.contains(trigger)&&!trigger.disabled?trigger:row;
   node.scrollTop+=focus.getBoundingClientRect().top-node.getBoundingClientRect().top-(focus===trigger?inset:rowInset);
   programScroll.current={scope:ticket,top:node.scrollTop};focus.focus({preventScroll:true});
   return true;
  };
 };
 const openHistorySource=(message:Json,trigger:HTMLButtonElement)=>{
  const h=live.current;
  if(document.hidden||!h||!scroll.current||!scroll.current.contains(trigger)||!can('sources.open'))return;
  const target={readerWindow:h.window,readerConversationId:h.conversation,messageId:message.id,readerSourceRevision:message.sourceRevision};
  if(!sameHistoricalAnswer(h,target))return;
  remember();setHistorySource({target,trigger,top:scroll.current.scrollTop,generation:sourceGeneration()});
 };
 const openHistoryMemory=(message:Json,trigger:HTMLButtonElement)=>{
  const h=live.current;
  if(document.hidden||!h||!scroll.current?.contains(trigger)||sending.current||!canReadMemory)return;
  const target={readerWindow:h.window,readerConversationId:h.conversation,messageId:message.id,readerSourceRevision:message.sourceRevision};
  if(!sameHistoricalAnswer(h,target,hasProvidedMemory))return;
  remember();setHistoryMemory({target,trigger,top:scroll.current.scrollTop,generation:sourceGeneration()});
 };
 const restoreAnswerFocus=(event:Event,shown:HistorySource,eligible:(row:Json)=>boolean)=>{
  event.preventDefault();
  if(!document.hidden&&sameHistoricalAnswer(live.current,shown.target,eligible)&&scroll.current?.contains(shown.trigger)&&!shown.trigger.disabled){
   scroll.current.scrollTop=shown.top;shown.trigger.focus({preventScroll:true});
  }
 };
 const persist=async()=>{
  if(timer.current){clearTimeout(timer.current);timer.current=null}
  const actual=bridge.getState().state.conversationReader,place=position.current;
  if(!live.current||!readyPage(actual)||readerScope(actual)!==scope||readerScope(live.current)!==scope||
     restored.current!==scope||unresolved.current===scope||!place.anchor||!can('conversation.reader.position'))return;
  const saved=exactRow(actual,place.anchor,place.revision);
  if(!saved)return;
  const index=actual.messages.indexOf(saved);
  try{await act('conversation.reader.position',{window:actual.window,conversationId:actual.conversation,index,offset:place.offset})}
  catch{/* The original place can still be used locally, never substitute a different row. */}
 };
 const saveReading=()=>{
  const prior=programScroll.current;programScroll.current=null;
  if(sending.current||prior?.scope===scope&&Math.abs((scroll.current?.scrollTop??0)-prior.top)<1)return;
  remember();if(timer.current)clearTimeout(timer.current);timer.current=setTimeout(()=>void persist(),180);
 };
 const invoke=async(action:string,extra:Json={})=>{
  const original=live.current;if(!original||sending.current||document.hidden||original.workRequest&&action!=='conversation.reader.close')return;
  if(action!=='conversation.reader.close')closingFromReader.current='';
  sending.current=true;setPending(true);setError('');remember();
  try{
   const saved=persist();
   if(action==='conversation.reader.earlier'||action==='conversation.reader.refresh')await saved;
   if(!document.hidden&&readerScope(live.current)===scope&&readerScope(bridge.getState().state.conversationReader)===scope)
    await act(action,{window:original.window,conversationId:original.conversation,...extra});
  }catch(failure){
   if(closingFromReader.current===scope)closingFromReader.current='';
   if(readerScope(live.current)===scope)setError(failure instanceof Error?failure.message:'原历史暂不可读取，请重新打开。');
  }
  finally{sending.current=false;setPending(false)}
 };
 const readHostFinal=async(message:Json)=>{
  const h=live.current;
  if(document.hidden||!h||h.window!==shown.window||h.conversation!==shown.conversation||h.loading||h.error||h.limited||sending.current||
   !h.messages?.some((row:Json)=>row.id===message.id&&row.sourceRevision===message.sourceRevision&&row.hostGoalTaskId===message.hostGoalTaskId&&row.originalHostFinal))
    throw new Error('原历史消息已变化，请在同一条原话取回。');
  return bridge.command('conversation.readPrivateHostFinal',{readerWindow:h.window,conversationId:h.conversation,
   messageId:message.id,sourceRevision:message.sourceRevision,taskId:message.hostGoalTaskId});
 };
 const copy=async(text:string)=>{
  const original=live.current;if(!original)throw new Error('原阅读窗口已关闭。');
  try{await onCopy(text)}catch(failure){if(live.current?.window===original.window)setError(failure instanceof Error?failure.message:'复制未完成，请选择文字后复制。');throw failure}
 };
 const openLinkedTask=async(message:Json,entry:'overview'|'process')=>{
  const original=live.current,input=message.role==='user';
  const hostTask=message.hostGoalTaskId;
  const taskId=hostTask||(input?message.readerInputTaskId:message.phoneResult?.taskId||message.taskResult?.taskId||message.taskId);
  if(!original||sending.current||!taskId||!can('tasks.open'))return;
  const origin={readerWindow:original.window,readerConversationId:original.conversation,readerMessageId:message.id};
  const sameWindow=()=>live.current?.window===origin.readerWindow&&live.current?.conversation===origin.readerConversationId;
  const checkInput=()=>{
   if(document.hidden||!sameWindow())return false;
   if(!live.current?.messages?.some((row:Json)=>row.id===message.id&&row.role==='user'&&row.sourceRevision===message.sourceRevision&&(hostTask?row.hostGoalTaskId:row.readerInputTaskId)===taskId))throw new Error('原消息已变化，请刷新这份历史后再查看。');
   return true;
  };
  sending.current=true;setPending(true);setError('');setInputNotice(input?{window:origin.readerWindow,messageId:message.id}:null);remember();
  if(timer.current){clearTimeout(timer.current);timer.current=null}
  try{
   // Preserve the reader and its underlying draft. Never select the conversation.
   await persist();
   if(!sameWindow())return;
   if(!input){onOpenTask(String(taskId),entry,origin);return}
   if(!checkInput())return;
   // Only promote the sheet after native binds this exact persisted input to its
   // private task and still-current run. A failed read never opens a substitute.
   const ack=await act('tasks.open',{id:taskId,requestNavigation:false,...origin});
   if(!checkInput())return;
   if(ack?.taskId!==taskId||ack.readOnly!==true||ack.executionAuthorized!==false)throw new Error('未能核对这条输入的原工作。');
   returnToInput.current={...origin,taskId,revision:message.sourceRevision};
   onOpenTask(taskId,'overview',origin,true);
  }catch(failure){
   if(sameWindow()){
    const text=failure instanceof Error?failure.message:'原工作暂不可读取，请重试。';
    if(input)setInputNotice({window:origin.readerWindow,messageId:message.id,error:text});else setError(text)
   }
  }finally{sending.current=false;setPending(false);setInputNotice(notice=>notice?.error?notice:null)}
 };
 const focusOriginalInput=()=>{
  const target=returnToInput.current;if(!target||!scroll.current)return false;
  returnToInput.current=null;
  if(live.current?.window!==target.readerWindow||live.current?.conversation!==target.readerConversationId)return false;
  const button=Array.from(scroll.current?.querySelectorAll<HTMLButtonElement>('[data-input-work]')||[]).find(item=>item.closest<HTMLElement>('[data-reader-id]')?.dataset.readerId===target.readerMessageId&&item.dataset.inputWork===target.taskId&&Number(item.dataset.inputRevision)===target.revision);
  if(!button||button.disabled)return false;
  button.focus({preventScroll:true});return true;
 };
 const focusOpenedRow=()=>{
  if(focusOriginalInput())return true;
  const shownPage=live.current,search=shownPage?.searchAnchor;
  if(search){
   const node=scroll.current,native=bridge.getState().state.conversationReader;
   if(document.hidden||!node?.isConnected||!readyPage(shownPage)||!readyPage(native)||readerScope(native)!==scope||
      native.searchAnchor?.requestId!==search.requestId||native.searchAnchor.messageId!==search.messageId||
      native.searchAnchor.revision!==search.revision||!exactRow(native,search.messageId,search.revision))return false;
   const row=shownRow(search.messageId,search.revision);
   if(!row?.isConnected||!row.getClientRects().length)return false;
   // Vaul's portal can mount after the parent layout effect. Its real open
   // autofocus must align the connected exact row as well, not just focus it.
   if(restored.current!==scope){
    node.scrollTop+=row.getBoundingClientRect().top-node.getBoundingClientRect().top;
    programScroll.current={scope,top:node.scrollTop};restored.current=scope;
    position.current={index:Number(row.dataset.readerIndex),offset:0,anchor:search.messageId,revision:search.revision};
   }
   row.focus({preventScroll:true});return true;
  }
  const message=live.current?.workRequest?.messageId||live.current?.originalReportMessageId;
  if(!message||!scroll.current)return false;
  const report=Array.from(scroll.current.querySelectorAll<HTMLElement>('[data-reader-id]')).find(row=>row.dataset.readerId===message);
  report?.focus({preventScroll:true});return !!report;
 };
 const openHistoryResultFiles=async(message:Json,result:TaskResult,trigger:HTMLButtonElement)=>{
  const original=live.current;
  if(!original||sending.current||!onOpenResultFiles)return;
  const current=()=>{
   const next=live.current;
   return !document.hidden&&readyPage(next)&&readerScope(next)===scope&&!!exactRow(next,message.id,message.sourceRevision);
  };
  if(!current())return;
  const node=scroll.current;
  if(!node?.contains(trigger))return;
  const restore=restoreReaderRow(original,message,trigger,node);
  const notice={window:original.window,messageId:message.id,revision:message.sourceRevision};
  sending.current=true;setPending(true);setResultNotice(notice);remember();void persist();
  try{await onOpenResultFiles(message.id,result,trigger,{window:original.window,conversationId:original.conversation,sourceRevision:message.sourceRevision,restore})}
  catch(failure){if(current())setResultNotice({...notice,error:failure instanceof Error?failure.message:'本次原文件暂不可读取。'})}
  finally{sending.current=false;setPending(false);setResultNotice(value=>value===notice?null:value)}
 };
 const back=()=>{if(!live.current)return false;if(shownMemory){if(!memoryBack.current?.())setHistoryMemory(null);return true}if(shownSource){setHistorySource(null);return true}if(reuse||firstQuote){setReuse(null);setFirstQuote(null);return true}if(phoneOriginal){setPhoneOriginal(null);return true}if(returnFromQuote())return true;if(!sending.current){closingFromReader.current=scope;void invoke('conversation.reader.close')}return true};
 useEffect(()=>{registerBack(back);return()=>registerBack(null)});
 useEffect(()=>()=>{if(timer.current)clearTimeout(timer.current)},[]);
 useLayoutEffect(()=>{
  if(!nativePage){restored.current='';unresolved.current='';return}
  const node=scroll.current;
  if(!readyPage(page)||!node||!page)return;
  const first=restored.current!==scope;
  if(first&&page.searchAnchor){focusOpenedRow();return;}
  const saved:Position=first?{index:Number(page.index||0),offset:Number(page.offset||0),anchor:String(page.anchor||'')}:position.current;
  const items=Array.from(node.querySelectorAll<HTMLElement>('[data-reader-index]'));
  // A native bookmark identifies a saved message. Its former index is no
  // substitute if this bounded history has not loaded that original row.
  const row=saved.anchor?shownRow(saved.anchor,saved.revision):items[Math.min(Math.max(0,saved.index),items.length-1)];
  restored.current=scope;position.current=saved;
  if(saved.anchor&&!row){
   setMissingPlace(true,page.hasMore?'还没读到保存位置的原消息，可查看更早消息。未跳到其他位置。':'当前阅读窗口未核对到保存位置的原消息，未跳到其他位置。');
   return;
  }
  setMissingPlace(false);
  if(row){
   node.scrollTop+=row.getBoundingClientRect().top-node.getBoundingClientRect().top+saved.offset;
   programScroll.current={scope,top:node.scrollTop};
  }
  if(!saved.anchor)remember();
  if(first)focusOpenedRow();
 },[scope,page?.window,page?.loading,page?.error,page?.limited,marker,positionNote?.scope]);
 useEffect(()=>{setError('');setPhoneOriginal(null)},[page?.window]);
 return <Drawer.Root open={!!page} onOpenChange={open=>{if(!open)back()}} dismissible={!pending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay" data-work-request={requestOnly||undefined}/><Drawer.Content className="pw-drawer pw-conversation-history-window" data-work-request={requestOnly||undefined} aria-describedby="pw-history-window-description" inert={!page} onOpenAutoFocus={event=>{if(focusOpenedRow())event.preventDefault()}} onCloseAutoFocus={event=>{
  exit.onCloseAutoFocus(event);
  const origin=closingFromReader.current;closingFromReader.current='';
  if(requestOnly&&!live.current&&!document.hidden)onWorkRequestReturn?.(shown.window);
  else if(searchOnly&&!live.current&&!bridge.getState().state.conversationReader)onSearchReturn?.(shown.window);
  else if(origin&&!document.hidden&&!bridge.getState().state.conversationReader)onReturnToChat?.();
 }}>
  <header className="pw-drawer-header"><div><Drawer.Title>{shown.title||'阅读对话'}</Drawer.Title><Drawer.Description id="pw-history-window-description">{requestOnly?'只读查看这件工作的原话，不会切换原输入。':searchOnly?'从命中的原消息开始，查看它在对话中的前后文。原输入没有切换。':'只读查看，原输入和发送回执仍留在原对话。'}{!requestOnly&&canQuoteNew&&' 仍可在原对话核对的消息，可以选取原话加入草稿。'}</Drawer.Description></div>{!requestOnly&&!searchOnly&&<IconButton icon={IconRefresh} aria-label={shown.originalReportMessageId?'重新核对这份原汇报':'刷新这份对话历史'} disabled={!page||pending||!!shown.loading||!can('conversation.reader.refresh')} onClick={()=>void invoke('conversation.reader.refresh')}/>}<IconButton icon={IconClose} aria-label={visibleVisit?'返回引用它的请求':backLabel} disabled={!page||pending} onClick={back}/></header>
  <div className="pw-drawer-body pw-history-window-messages" ref={scroll} onScroll={saveReading}>
   {(error||shown.error)&&<p className="pw-error" role="alert">{error||shown.error}</p>}
   {shown.notice&&shown.notice!=='上次阅读位置不在当前窗口，可继续查看较早消息；当前任务未切换。'&&<p className="pw-muted">{shown.notice}</p>}
   {page&&positionNote?.scope===scope&&<div className="pw-action-row"><p className="pw-muted" role="status">{positionNote.text}</p>
    {!!rows.length&&<Button variant="ghost" size="small" disabled={pending||shown.loading} onClick={()=>{setMissingPlace(false);remember();void persist()}}>从这里继续阅读</Button>}</div>}
   {page&&quoteNote&&<p className="pw-muted" role="status">{quoteNote}</p>}
   {shown.hasMore&&!shown.limited&&<Button variant="ghost" disabled={!page||pending||shown.loading||!can('conversation.reader.earlier')} onClick={()=>void invoke('conversation.reader.earlier')}>查看更早消息</Button>}
   {shown.loading&&<p className="pw-muted" role="status">正在读取原消息…</p>}
   {rows.map((message,index)=>{const phone=message.id&&message.sourceRevision===1?messagePhoneResult(message):null,readPhone=!!phone&&can('phone.result.original');
    const result=messageTaskResult(message),fileResult=!searchOnly&&onOpenResultFiles&&message.sourceRevision>0&&result?.kind==='owner_work'&&result.status==='completed'&&!result.noticeKind?result:null;
    const canSelectQuote=canQuoteNew&&!pending&&!!newQuoteTarget(message);
    const readingFiles=resultNotice?.window===shown.window&&resultNotice?.messageId===message.id&&resultNotice?.revision===message.sourceRevision?resultNotice:null;
    return <article key={message.id} className={`pw-history-window-message pw-message-${message.role}`} tabIndex={-1} data-reader-index={index} data-reader-id={message.id} data-reader-revision={message.sourceRevision} aria-label={message.role==='assistant'?'助手回复':'你的消息'}>
    {shown.originalReportMessageId===message.id&&<p className="pw-field-hint">这件工作的原汇报</p>}
    {phone&&message.id?<PhoneMessageReturn result={phone} conversation={shown.conversation} reader={shown.window} messageId={message.id} revision={message.sourceRevision}
      body={message.content} locked={!page||page.window!==shown.window||page.loading||!!page.error||page.limited||pending}
      onOriginal={readPhone?t=>{remember();setPhoneOriginal(t)}:undefined}>
      <AIAnswer text={message.content} onCopyCode={copy} showActions={false}/>
     </PhoneMessageReturn>:message.originalHostFinal?<p className="pw-user-text">{message.content}</p>:message.role==='assistant'?<AIAnswer text={message.content} onCopyCode={copy} showActions={false}/>:<p className="pw-user-text">{message.content}</p>}
    {!requestOnly&&!searchOnly&&Array.isArray(message.attachments)&&message.attachments.length>0&&<div className="pw-message-files">{message.attachments.map((file:Json)=><MessageAttachment
     key={`${file.id}:${file.sha256}`} file={file} history view={`history:${shown.window}`}
     origin={{conversationId:shown.conversation,messageId:message.id,sourceRevision:message.sourceRevision,window:shown.window}}
     enabled={!!page&&page.window===shown.window&&!pending&&can('conversation.reader.attachment')} canLocate={can('attachmentWorkspace.open')}
     beforeOpen={()=>{remember();persist()}}/>)}</div>}
    {!requestOnly&&page?.window===shown.window&&message.role==='user'&&message.automaticLearningExcluded===true&&<Accordion className="pw-compact-disclosures"><AccordionItem id={'memory-'+message.id} title="这条原话已排除自动记忆整理"><p className="pw-muted">后来开启也不会补提炼。它仍是对话原文；这不代表类似内容从未另行保存。{message.originalDefaultWritesBlocked===true&&' 原任务也不能更改六项默认偏好。'}</p></AccordionItem></Accordion>}
    {!requestOnly&&!searchOnly&&page?.window===shown.window&&!page?.loading&&!page?.error&&!page?.limited&&message.role==='user'&&<RegisteredQuoteTrail message={message} hasEarlier={shown.hasMore===true} locked={pending||!!visibleVisit&&visibleVisit.request!==message.id} locate={(range,target,from)=>{
     const h=live.current;if(!h||!scroll.current?.contains(from)||h.window!==shown.window||h.conversation!==shown.conversation)return;
     const visit={...range,scope,returnInset:from.getBoundingClientRect().top-scroll.current.getBoundingClientRect().top,window:h.window,conversation:h.conversation,request:message.id,requestRevision:message.sourceRevision,target};
     if(moveToQuoteRow(visit,false)){setQuoteNote('');setQuoteVisit(visit)}else setQuoteNote('当前窗口还没有核对到引用的原消息，没有跳到其他位置。');
    }}/>}
    {!requestOnly&&visibleVisit?.target===message.id&&<div className="pw-action-row">
     {canReuse&&activeDraft&&<Button variant="secondary" size="small" disabled={pending} onClick={()=>setReuse({messageId:visibleVisit.target,sourceRevision:visibleVisit.revision,key:activeDraft.key,epoch:activeDraft.epoch,prior:{reader:visibleVisit.window,request:visibleVisit.request,revision:visibleVisit.requestRevision,offset:visibleVisit.offset,chars:visibleVisit.chars}})}>本次再引用这段…</Button>}
     <Button variant="ghost" size="small" disabled={pending} onClick={returnFromQuote}>返回引用它的请求</Button>
    </div>}
    {!requestOnly&&<div className="pw-action-row">
     {!!message.content&&<MessageActions onCopy={()=>copy(message.content)}/>}
     {canSelectQuote&&<Button variant="ghost" size="small" leadingIcon={IconQuote} onClick={()=>{
      const target=newQuoteTarget(message);
      if(!target||sending.current){setQuoteProblem({scope,messageId:message.id});return}
      setQuoteProblem(null);setReuse(null);setFirstQuote({scope,target});
     }}>引用到草稿</Button>}
     {message.firstHostRequestLocal&&!message.hostGoalTaskId&&<p className="pw-field-hint">仅保存到本机，未派发</p>}
     {message.originalHostFinal&&<OriginalPrivateHostAnswer result={message.originalHostFinal}
       read={page&&page.window===shown.window&&!page.loading&&!page.error&&!page.limited&&!pending&&message.id&&message.sourceRevision===1&&
         message.hostGoalTaskId&&can('conversation.readPrivateHostFinal')?()=>readHostFinal(message):undefined}/>}
     {hasPublicSources(message)&&can('sources.open')&&<Button variant="ghost" size="small" leadingIcon={IconLink}
      disabled={!page||page.window!==shown.window||pending||page.loading||!!page.error||page.limited}
      onClick={event=>openHistorySource(message,event.currentTarget)}>查看出处</Button>}
     {hasProvidedMemory(message)&&canReadMemory&&<Button variant="ghost" size="small" leadingIcon={IconMemory}
      aria-label="核对这条回答提供给模型的记忆" disabled={!page||page.window!==shown.window||pending||page.loading||!!page.error||page.limited}
      onClick={event=>openHistoryMemory(message,event.currentTarget)}>本次记忆</Button>}
     {message.hostGoalTaskId&&can('tasks.open')&&<Button variant="ghost" size="small" leadingIcon={IconTool} disabled={pending} onClick={()=>void openLinkedTask(message,'overview')}>打开原主机工作</Button>}
     {message.role==='user'&&typeof message.readerInputTaskId==='string'&&message.readerInputTaskId&&can('tasks.open')&&<Button variant="ghost" size="small" className="pw-input-work" leadingIcon={IconTool} data-input-work={message.readerInputTaskId} data-input-revision={message.sourceRevision} aria-busy={loadingInput(message.id)} disabled={!page||pending} onClick={()=>void openLinkedTask(message,'overview')}>{loadingInput(message.id)?'正在读取…':'查看原工作'}</Button>}
     {fileResult&&<Button variant="ghost" size="small" leadingIcon={IconFolder} disabled={!page||pending||page.loading||page.error||page.limited}
      aria-busy={!!readingFiles&&!readingFiles.error||undefined} onClick={event=>void openHistoryResultFiles(message,fileResult,event.currentTarget)}>{readingFiles&&!readingFiles.error?'正在读取本次文件…':'查看本次文件'}</Button>}
     {phone&&can('tasks.open')&&<Button variant="ghost" size="small" disabled={!page||pending} onClick={()=>void openLinkedTask(message,'process')}>所属工作的当前进展</Button>}
     {message.role==='assistant'&&!phone&&!message.hostGoalTaskId&&(Number(message.sourceRevision)>0||message.phoneResult||message.taskResult)&&(message.phoneResult?.taskId||message.taskResult?.taskId||message.taskId)&&can('tasks.open')&&<>
      <Button variant="ghost" size="small" disabled={!page||pending} onClick={()=>void openLinkedTask(message,'overview')}>{message.phoneResult?'所属工作':'任务详情'}</Button>
      <Button variant="ghost" size="small" leadingIcon={IconTool} disabled={!page||pending} onClick={()=>void openLinkedTask(message,'process')}>{message.phoneResult?'当前进展':'执行轨迹'}</Button>
     </>}
    </div>}
    {inputNotice?.error&&inputNotice.window===shown.window&&inputNotice.messageId===message.id&&<p className="pw-input-work-error" role="status">{inputNotice.error}</p>}
    {quoteProblem?.scope===scope&&quoteProblem.messageId===message.id&&<p className="pw-input-work-error" role="status">原消息或草稿已变化，这次没有关联。</p>}
    {readingFiles?.error&&<p className="pw-input-work-error" role="status">{readingFiles.error}</p>}
   </article>})}
   {!shown.loading&&!shown.error&&!rows.length&&<p className="pw-muted">当前窗口未读到可显示的消息。</p>}
  </div>
  <footer className="pw-drawer-actions"><Button variant="ghost" disabled={pending} onClick={back}>{visibleVisit?'返回引用它的请求':backLabel}</Button>{!requestOnly&&!searchOnly&&<Button variant="secondary" disabled={!page||submitting||pending||!can('conversation.reader.select')} onClick={()=>void invoke('conversation.reader.select')}>{submitting?'原输入正在登记':'在此对话继续'}</Button>}</footer>
  {!requestOnly&&reviewing&&<MessageQuote key={`${scope}:${reviewing.messageId}:${reviewing.prior?.request||''}`} target={reviewing}
   onClose={()=>{setReuse(null);setFirstQuote(null)}} onStaged={()=>{if(readerScope(live.current)===scope)void invoke('conversation.reader.close')}}/>}
  {phoneOriginal&&page?.window===phoneOriginal.readerWindow&&<PhoneReturnedReport target={phoneOriginal} onClose={()=>setPhoneOriginal(null)}/>}
  {shownSource&&<AnswerSources key={`${shownSource.target.readerWindow}:${shownSource.target.messageId}:${shownSource.target.readerSourceRevision}`}
    target={shownSource.target} revision={shownSource.target.readerSourceRevision} onClose={()=>setHistorySource(null)}
    onCloseAutoFocus={event=>restoreAnswerFocus(event,shownSource,hasPublicSources)}/>}
  {shownMemory&&<ProvidedMemories key={`${shownMemory.target.readerWindow}:${shownMemory.target.messageId}:${shownMemory.target.readerSourceRevision}`}
    messageId={shownMemory.target.messageId} history={shownMemory.target} revision={Number(bridge.getState().state.memoryRefreshRevision||0)}
    onClose={()=>setHistoryMemory(null)} onCloseAutoFocus={event=>restoreAnswerFocus(event,shownMemory,hasProvidedMemory)} registerBack={registerMemoryBack}/>}
 </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
