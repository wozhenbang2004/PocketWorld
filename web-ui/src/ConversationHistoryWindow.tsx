import {OriginalPrivateHostAnswer} from './OriginalPrivateHostAnswer';
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import { IconClose, IconFolder, IconLink, IconRefresh, IconTool } from './icons';
import {Accordion,AccordionItem,Button,IconButton} from './forms-library';
import {Drawer} from './shell-library';
import {bridge} from './bridge';
import {AIAnswer,MessageActions} from './library';
import {AnswerSources,type HistoricalAnswerSource} from './AnswerSources';
import {useClosingSnapshot} from './library/surface-motion';
import {PhoneReturnedReport,type PhoneReportTarget} from './PhoneReturnedReport';
import {PhoneMessageReturn} from './PhoneMessageReturn';
import {messagePhoneResult} from './phone-start';
import {MessageQuote,type MessageQuoteTarget} from './MessageQuote';
import {MessageAttachment} from './MessageAttachment';
import {RegisteredQuoteTrail,sameQuoteVisit,type HistoryQuoteVisit} from './RegisteredQuoteTrail';
import {messageTaskResult,type TaskResult} from './conversation-work';
import './conversation-history-window.css';

type Json=Record<string,any>;
type Position={index:number;offset:number;anchor:string};
type HistorySource={target:HistoricalAnswerSource;trigger:HTMLButtonElement;top:number;generation:number};
const sourceGeneration=()=>Number(bridge.getState().state.traceSourceRevision||0);
const hasPublicSources=(row:Json)=>row.role==='assistant'&&typeof row.id==='string'&&!!row.id&&row.retainedSources===true&&
 row.memoryReference==null&&Number.isSafeInteger(row.sourceRevision)&&row.sourceRevision>0;
function sameHistoricalAnswer(page:Json|null,target:HistoricalAnswerSource){
 return page?.window===target.readerWindow&&page.conversation===target.readerConversationId&&!page.loading&&!page.error&&!page.limited&&
  Array.isArray(page.messages)&&page.messages.filter((row:Json)=>row.id===target.messageId&&row.sourceRevision===target.readerSourceRevision&&hasPublicSources(row)).length===1;
}
export type ConversationHistoryTaskOrigin={readerWindow:number;readerConversationId:string;readerMessageId:string};
export type HistoryResultOrigin={window:number;conversationId:string;sourceRevision:number};
type Props={page:Json|null;activeDraft?:{conversation:string;key:string;epoch:number};submitting:boolean;can:(action:string)=>boolean;act:(action:string,args?:Json)=>Promise<any>;registerBack:(handler:(()=>boolean)|null)=>void;onCopy:(text:string)=>Promise<unknown>;onOpenTask:(taskId:string,entry:'overview'|'process',origin:ConversationHistoryTaskOrigin,nativeAlreadyOpen?:boolean)=>void;
 onOpenResultFiles?:(messageId:string,result:TaskResult,trigger:HTMLButtonElement,origin:HistoryResultOrigin)=>Promise<void>};
/** A second, read-only history window. The active composer stays with its original
 * submission until the owner explicitly switches after its receipt has settled. */
export function ConversationHistoryWindow({page,activeDraft,submitting,can,act,registerBack,onCopy,onOpenTask,onOpenResultFiles}:Props){
 const exit=useClosingSnapshot(page),shown:Json=exit.display??{};
 const scroll=useRef<HTMLDivElement>(null),live=useRef(page);live.current=page;
 const restored=useRef<number|null>(null),position=useRef<Position>({index:0,offset:0,anchor:''});
 const timer=useRef<ReturnType<typeof setTimeout>|null>(null),sending=useRef(false);
 const [pending,setPending]=useState(false),[error,setError]=useState('');
 const [historySource,setHistorySource]=useState<HistorySource|null>(null);
 const shownSource=historySource&&historySource.generation===sourceGeneration()&&sameHistoricalAnswer(page,historySource.target)?historySource:null;
 useEffect(()=>{if(historySource&&!shownSource)setHistorySource(null)},[historySource,shownSource]);
 const [phoneOriginal,setPhoneOriginal]=useState<PhoneReportTarget|null>(null);
 const [quoteVisit,setQuoteVisit]=useState<HistoryQuoteVisit|null>(null);
 const visibleVisit=quoteVisit&&sameQuoteVisit(page,quoteVisit)?quoteVisit:null;
 const [reuse,setReuse]=useState<MessageQuoteTarget|null>(null);
 const canReuse=!!visibleVisit&&activeDraft?.conversation===visibleVisit.conversation&&!submitting&&can('messageQuote.open')&&can('messageQuote.stage')&&can('conversation.reader.close');
 const reviewing=canReuse&&reuse?.prior&&reuse.key===activeDraft?.key&&reuse.epoch===activeDraft?.epoch&&reuse.prior.reader===visibleVisit?.window&&reuse.prior.request===visibleVisit.request&&reuse.prior.revision===visibleVisit.requestRevision&&reuse.messageId===visibleVisit.target&&reuse.sourceRevision===visibleVisit.revision&&reuse.prior.offset===visibleVisit.offset&&reuse.prior.chars===visibleVisit.chars?reuse:null;
 useEffect(()=>{if(reuse&&!reviewing)setReuse(null)},[reuse,reviewing]);
 const moveToQuoteRow=(visit:HistoryQuoteVisit,returning:boolean)=>{
  if(document.hidden||sending.current||!scroll.current||!sameQuoteVisit(live.current,visit))return false;
  const box=scroll.current;
  const id=returning?visit.request:visit.target;
  const row=Array.from(box.querySelectorAll<HTMLElement>('[data-reader-id]')).find(item=>item.dataset.readerId===id);
  if(!row)return false;
  const origin=returning?Array.from(row.querySelectorAll<HTMLButtonElement>('[data-quote-target]')).find(item=>item.dataset.quoteTarget===visit.target&&Number(item.dataset.quoteStart)===visit.offset&&Number(item.dataset.quoteSize)===visit.chars):null;
  const anchor=origin&&!origin.disabled?origin:row;anchor.focus({preventScroll:true});
  box.scrollTop+=anchor.getBoundingClientRect().top-box.getBoundingClientRect().top-(returning&&origin?visit.returnInset:0);return true;
 };
 const returnFromQuote=()=>{const v=visibleVisit;if(!v)return false;setQuoteVisit(null);moveToQuoteRow(v,true);return true};
 useEffect(()=>{if(quoteVisit&&!visibleVisit)setQuoteVisit(null)},[quoteVisit,visibleVisit]);
 useEffect(()=>{const retire=()=>{if(document.hidden){setQuoteVisit(null);setHistorySource(null)}};document.addEventListener('visibilitychange',retire);return()=>document.removeEventListener('visibilitychange',retire)},[]);

 const [inputNotice,setInputNotice]=useState<{window:number;messageId:string;error?:string}|null>(null);
 const [resultNotice,setResultNotice]=useState<{window:number;messageId:string;revision:number;error?:string}|null>(null);
 const returnToInput=useRef<(ConversationHistoryTaskOrigin&{taskId:string;revision:number})|null>(null);
 const rows:Json[]=Array.isArray(shown.messages)?shown.messages:[];
 const marker=rows.map(row=>`${row.id}:${row.sourceRevision||0}`).join('|');
 const loadingInput=(id:string)=>pending&&inputNotice?.window===shown.window&&inputNotice?.messageId===id&&!inputNotice?.error;
 const args=()=>({window:live.current?.window,conversationId:live.current?.conversation});
 const remember=()=>{
  const node=scroll.current;if(!node||!live.current||restored.current!==live.current.window)return;
  const items=Array.from(node.querySelectorAll<HTMLElement>('[data-reader-index]'));
  const row=items.find(item=>item.getBoundingClientRect().bottom>node.getBoundingClientRect().top)||items.at(-1);if(!row)return;
  position.current={index:Number(row.dataset.readerIndex),offset:Math.max(0,Math.round(node.getBoundingClientRect().top-row.getBoundingClientRect().top)),anchor:row.dataset.readerId||''};
 };
 const openHistorySource=(message:Json,trigger:HTMLButtonElement)=>{
  const h=live.current;
  if(document.hidden||!h||!scroll.current||!scroll.current.contains(trigger)||!can('sources.open'))return;
  const target={readerWindow:h.window,readerConversationId:h.conversation,messageId:message.id,readerSourceRevision:message.sourceRevision};
  if(!sameHistoricalAnswer(h,target))return;
  remember();setHistorySource({target,trigger,top:scroll.current.scrollTop,generation:sourceGeneration()});
 };
 const persist=()=>{
  if(timer.current){clearTimeout(timer.current);timer.current=null}
  if(!live.current||restored.current!==live.current.window||!can('conversation.reader.position'))return;
  void act('conversation.reader.position',{...args(),index:position.current.index,offset:position.current.offset}).catch(()=>{});
 };
 const saveReading=()=>{remember();if(timer.current)clearTimeout(timer.current);timer.current=setTimeout(persist,180)};
 const invoke=async(action:string,extra:Json={})=>{
  const original=live.current;if(!original||sending.current)return;
  sending.current=true;setPending(true);setError('');remember();persist();
  try{await act(action,{window:original.window,conversationId:original.conversation,...extra})}
  catch(failure){if(live.current?.window===original.window)setError(failure instanceof Error?failure.message:'原历史暂不可读取，请重新打开。')}
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
   if(can('conversation.reader.position'))await act('conversation.reader.position',{window:original.window,conversationId:original.conversation,index:position.current.index,offset:position.current.offset});
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
 const openHistoryResultFiles=async(message:Json,result:TaskResult,trigger:HTMLButtonElement)=>{
  const original=live.current;
  if(!original||sending.current||!onOpenResultFiles)return;
  const current=()=>{
   const next=live.current;
   return !document.hidden&&next?.window===original.window&&next?.conversation===original.conversation&&!next?.loading&&!next?.error&&!next?.limited&&
    next?.messages?.some((row:Json)=>row.id===message.id&&row.sourceRevision===message.sourceRevision);
  };
  if(!current())return;
  const notice={window:original.window,messageId:message.id,revision:message.sourceRevision};
  sending.current=true;setPending(true);setResultNotice(notice);remember();persist();
  try{await onOpenResultFiles(message.id,result,trigger,{window:original.window,conversationId:original.conversation,sourceRevision:message.sourceRevision})}
  catch(failure){if(current())setResultNotice({...notice,error:failure instanceof Error?failure.message:'本次原文件暂不可读取。'})}
  finally{sending.current=false;setPending(false);setResultNotice(value=>value===notice?null:value)}
 };
 const back=()=>{if(shownSource){setHistorySource(null);return true}if(reuse){setReuse(null);return true}if(phoneOriginal){setPhoneOriginal(null);return true}if(returnFromQuote())return true;if(!live.current)return false;if(!sending.current)void invoke('conversation.reader.close');return true};
 useEffect(()=>{registerBack(back);return()=>registerBack(null)});
 useEffect(()=>()=>{if(timer.current)clearTimeout(timer.current)},[]);
 useLayoutEffect(()=>{
  if(!page){restored.current=null;return}
  const node=scroll.current;if(!node||!rows.length)return;
  const first=restored.current!==page.window;
  const saved=first?{index:Number(page.index||0),offset:Number(page.offset||0),anchor:String(page.anchor||'')}:position.current;
  const items=Array.from(node.querySelectorAll<HTMLElement>('[data-reader-index]'));
  const row=items.find(item=>item.dataset.readerId===saved.anchor)||items[Math.min(Math.max(0,saved.index),items.length-1)];
  if(row)node.scrollTop+=row.getBoundingClientRect().top-node.getBoundingClientRect().top+saved.offset;
  restored.current=page.window;remember();if(first)focusOriginalInput();
 },[page?.window,marker]);
 useEffect(()=>{setError('');setPhoneOriginal(null)},[page?.window]);
 return <Drawer.Root open={!!page} onOpenChange={open=>{if(!open)back()}} dismissible={!pending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-conversation-history-window" aria-describedby="pw-history-window-description" inert={!page} onOpenAutoFocus={event=>{if(focusOriginalInput())event.preventDefault()}} onCloseAutoFocus={exit.onCloseAutoFocus}>
  <header className="pw-drawer-header"><div><Drawer.Title>{shown.title||'阅读对话'}</Drawer.Title><Drawer.Description id="pw-history-window-description">只读查看，原输入和发送回执仍留在原对话。</Drawer.Description></div><IconButton icon={IconRefresh} aria-label="刷新这份对话历史" disabled={!page||pending||!!shown.loading||!can('conversation.reader.refresh')} onClick={()=>void invoke('conversation.reader.refresh')}/><IconButton icon={IconClose} aria-label={visibleVisit?'返回引用它的请求':'返回原输入'} disabled={!page||pending} onClick={back}/></header>
  <div className="pw-drawer-body pw-history-window-messages" ref={scroll} onScroll={saveReading}>
   {(error||shown.error)&&<p className="pw-error" role="alert">{error||shown.error}</p>}
   {shown.notice&&<p className="pw-muted">{shown.notice}</p>}
   {shown.hasMore&&!shown.limited&&<Button variant="ghost" disabled={!page||pending||shown.loading||!can('conversation.reader.earlier')} onClick={()=>void invoke('conversation.reader.earlier')}>查看更早消息</Button>}
   {shown.loading&&<p className="pw-muted" role="status">正在读取原消息…</p>}
   {rows.map((message,index)=>{const phone=message.id&&message.sourceRevision===1?messagePhoneResult(message):null,readPhone=!!phone&&can('phone.result.original');
    const result=messageTaskResult(message),fileResult=onOpenResultFiles&&message.sourceRevision>0&&result?.kind==='owner_work'&&result.status==='completed'&&!result.noticeKind?result:null;
    const readingFiles=resultNotice?.window===shown.window&&resultNotice?.messageId===message.id&&resultNotice?.revision===message.sourceRevision?resultNotice:null;
    return <article key={message.id} className={`pw-history-window-message pw-message-${message.role}`} tabIndex={-1} data-reader-index={index} data-reader-id={message.id} aria-label={message.role==='assistant'?'助手回复':'你的消息'}>
    {phone&&message.id?<PhoneMessageReturn result={phone} conversation={shown.conversation} reader={shown.window} messageId={message.id} revision={message.sourceRevision}
      body={message.content} locked={!page||page.window!==shown.window||page.loading||!!page.error||page.limited||pending}
      onOriginal={readPhone?t=>{remember();setPhoneOriginal(t)}:undefined}>
      <AIAnswer text={message.content} onCopyCode={copy} showActions={false}/>
     </PhoneMessageReturn>:message.originalHostFinal?<p className="pw-user-text">{message.content}</p>:message.role==='assistant'?<AIAnswer text={message.content} onCopyCode={copy} showActions={false}/>:<p className="pw-user-text">{message.content}</p>}
    {Array.isArray(message.attachments)&&message.attachments.length>0&&<div className="pw-message-files">{message.attachments.map((file:Json)=><MessageAttachment
     key={`${file.id}:${file.sha256}`} file={file} history view={`history:${shown.window}`}
     origin={{conversationId:shown.conversation,messageId:message.id,sourceRevision:message.sourceRevision,window:shown.window}}
     enabled={!!page&&page.window===shown.window&&!pending&&can('conversation.reader.attachment')} canLocate={can('attachmentWorkspace.open')}
     beforeOpen={()=>{remember();persist()}}/>)}</div>}
    {page?.window===shown.window&&message.role==='user'&&message.automaticLearningExcluded===true&&<Accordion className="pw-compact-disclosures"><AccordionItem id={'memory-'+message.id} title="这条原话已排除自动记忆整理"><p className="pw-muted">后来开启也不会补提炼。它仍是对话原文；这不代表类似内容从未另行保存。{message.originalDefaultWritesBlocked===true&&' 原任务也不能更改六项默认偏好。'}</p></AccordionItem></Accordion>}
    {page?.window===shown.window&&!page?.loading&&!page?.error&&!page?.limited&&message.role==='user'&&<RegisteredQuoteTrail message={message} locked={pending||!!visibleVisit&&visibleVisit.request!==message.id} locate={(range,target,from)=>{
     const h=live.current;if(!h||!scroll.current?.contains(from)||h.window!==shown.window||h.conversation!==shown.conversation)return;
     const visit={...range,returnInset:from.getBoundingClientRect().top-scroll.current.getBoundingClientRect().top,window:h.window,conversation:h.conversation,request:message.id,requestRevision:message.sourceRevision,target};
     if(moveToQuoteRow(visit,false))setQuoteVisit(visit);
    }}/>}
    {visibleVisit?.target===message.id&&<div className="pw-action-row">
     {canReuse&&activeDraft&&<Button variant="secondary" size="small" disabled={pending} onClick={()=>setReuse({messageId:visibleVisit.target,sourceRevision:visibleVisit.revision,key:activeDraft.key,epoch:activeDraft.epoch,prior:{reader:visibleVisit.window,request:visibleVisit.request,revision:visibleVisit.requestRevision,offset:visibleVisit.offset,chars:visibleVisit.chars}})}>本次再引用这段…</Button>}
     <Button variant="ghost" size="small" disabled={pending} onClick={returnFromQuote}>返回引用它的请求</Button>
    </div>}
    <div className="pw-action-row">
     {!!message.content&&<MessageActions onCopy={()=>copy(message.content)}/>}
     {message.firstHostRequestLocal&&!message.hostGoalTaskId&&<p className="pw-field-hint">仅保存到本机，未派发</p>}
     {message.originalHostFinal&&<OriginalPrivateHostAnswer result={message.originalHostFinal}
       read={page&&page.window===shown.window&&!page.loading&&!page.error&&!page.limited&&!pending&&message.id&&message.sourceRevision===1&&
         message.hostGoalTaskId&&can('conversation.readPrivateHostFinal')?()=>readHostFinal(message):undefined}/>}
     {hasPublicSources(message)&&can('sources.open')&&<Button variant="ghost" size="small" leadingIcon={IconLink}
      disabled={!page||page.window!==shown.window||pending||page.loading||!!page.error||page.limited}
      onClick={event=>openHistorySource(message,event.currentTarget)}>查看出处</Button>}
     {message.hostGoalTaskId&&can('tasks.open')&&<Button variant="ghost" size="small" leadingIcon={IconTool} disabled={pending} onClick={()=>void openLinkedTask(message,'overview')}>打开原主机工作</Button>}
     {message.role==='user'&&typeof message.readerInputTaskId==='string'&&message.readerInputTaskId&&can('tasks.open')&&<Button variant="ghost" size="small" className="pw-input-work" leadingIcon={IconTool} data-input-work={message.readerInputTaskId} data-input-revision={message.sourceRevision} aria-busy={loadingInput(message.id)} disabled={!page||pending} onClick={()=>void openLinkedTask(message,'overview')}>{loadingInput(message.id)?'正在读取…':'查看原工作'}</Button>}
     {fileResult&&<Button variant="ghost" size="small" leadingIcon={IconFolder} disabled={!page||pending||page.loading||page.error||page.limited}
      aria-busy={!!readingFiles&&!readingFiles.error||undefined} onClick={event=>void openHistoryResultFiles(message,fileResult,event.currentTarget)}>{readingFiles&&!readingFiles.error?'正在读取本次文件…':'查看本次文件'}</Button>}
     {phone&&can('tasks.open')&&<Button variant="ghost" size="small" disabled={!page||pending} onClick={()=>void openLinkedTask(message,'process')}>所属工作的当前进展</Button>}
     {message.role==='assistant'&&!phone&&!message.hostGoalTaskId&&(Number(message.sourceRevision)>0||message.phoneResult||message.taskResult)&&(message.phoneResult?.taskId||message.taskResult?.taskId||message.taskId)&&can('tasks.open')&&<>
      <Button variant="ghost" size="small" disabled={!page||pending} onClick={()=>void openLinkedTask(message,'overview')}>{message.phoneResult?'所属工作':'任务详情'}</Button>
      <Button variant="ghost" size="small" leadingIcon={IconTool} disabled={!page||pending} onClick={()=>void openLinkedTask(message,'process')}>{message.phoneResult?'当前进展':'执行轨迹'}</Button>
     </>}
    </div>
    {inputNotice?.error&&inputNotice.window===shown.window&&inputNotice.messageId===message.id&&<p className="pw-input-work-error" role="status">{inputNotice.error}</p>}
    {readingFiles?.error&&<p className="pw-input-work-error" role="status">{readingFiles.error}</p>}
   </article>})}
   {!shown.loading&&!shown.error&&!rows.length&&<p className="pw-muted">这份对话没有可读取的消息。</p>}
  </div>
  <footer className="pw-drawer-actions"><Button variant="ghost" disabled={pending} onClick={back}>{visibleVisit?'返回引用它的请求':'返回原输入'}</Button><Button variant="secondary" disabled={!page||submitting||pending||!can('conversation.reader.select')} onClick={()=>void invoke('conversation.reader.select')}>{submitting?'原输入正在登记':'在此对话继续'}</Button></footer>
  {reviewing&&<MessageQuote target={reviewing} onClose={()=>setReuse(null)} onStaged={()=>{if(live.current?.window===reviewing.prior?.reader)void invoke('conversation.reader.close')}}/>}
  {phoneOriginal&&page?.window===phoneOriginal.readerWindow&&<PhoneReturnedReport target={phoneOriginal} onClose={()=>setPhoneOriginal(null)}/>}
  {shownSource&&<AnswerSources key={`${shownSource.target.readerWindow}:${shownSource.target.messageId}:${shownSource.target.readerSourceRevision}`}
    target={shownSource.target} revision={shownSource.target.readerSourceRevision} onClose={()=>setHistorySource(null)}
    onCloseAutoFocus={event=>{
     event.preventDefault();
     if(!document.hidden&&sameHistoricalAnswer(live.current,shownSource.target)&&scroll.current?.contains(shownSource.trigger)&&!shownSource.trigger.disabled){
      scroll.current.scrollTop=shownSource.top;shownSource.trigger.focus({preventScroll:true});
     }
    }}/>}
 </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
