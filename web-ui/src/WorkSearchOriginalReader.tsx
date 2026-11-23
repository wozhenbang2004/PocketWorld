import {useCallback,useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {Drawer} from './shell-library';
import {MessageActions} from './library';
import {IconArrowLeft} from './icons';
import {matchesWorkSearchConversation,type WorkSearchConversation,type WorkSearchItem} from './WorkSearch';

type Json=Record<string,any>;
const locatorFields=['kind','generation','conversationId','recordId','recordVersion','fingerprint'];
/** The existing source-reader and history-window layout reads the exact owner
 * locator. Viewing a search hit never changes the active composer or its scope. */
export function WorkSearchOriginalReader({target,identity,covered=false,conversation,onClose,onConversation,onContext,onCopy,onOriginalAccepted}:{
 target:WorkSearchItem;identity:string;covered?:boolean;conversation?:WorkSearchConversation;onClose:()=>void;onConversation?:()=>void;
 onContext?:(locator:Record<string,unknown>)=>Promise<void>;onCopy:(text:string)=>Promise<unknown>;
 onOriginalAccepted?:(target:WorkSearchItem)=>void;
}){
 const initialOffset=Number.isSafeInteger(target.readRange?.offset)&&Number(target.readRange?.offset)>=0?Number(target.readRange?.offset):0;
 const [page,setPage]=useState<Json|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[offsets,setOffsets]=useState<number[]>([]);
 const [locating,setLocating]=useState(false);
 const alive=useRef(false),generation=useRef(0),pending=useRef(false),requestedOffset=useRef(initialOffset),scroller=useRef<HTMLDivElement>(null);
 const confirmed=useRef<{target:WorkSearchItem;identity:string;request:number;epoch:number}|null>(null);
 const contextButton=useRef<HTMLButtonElement>(null),contextPosition=useRef<number|null>(null),contextOpening=useRef(false);
 const read=useCallback(async(offset:number)=>{
  if(pending.current)return;pending.current=true;
  const request=++generation.current,epoch=bridge.getDocumentEpoch();requestedOffset.current=offset;setBusy(true);setError('');
  const accepts=()=>alive.current&&request===generation.current&&epoch===bridge.getDocumentEpoch()&&!document.hidden;
  try{
   const result=await bridge.command('work.search.read',{ownerLocator:target.ownerLocator,offset,maxChars:6000,
    ...(conversation?{conversationScope:conversation}:{})});
   if(!accepts())return;
   if(conversation&&!matchesWorkSearchConversation(result?.conversationScope,conversation))
    throw new Error('原对话已变化，没有打开其他记录。');
   if(typeof result?.status!=='string')throw new Error('原文读取结果暂不可核对，请重新搜索。');
   if(result.status==='current'){
    const range=result.readRange;
    if(locatorFields.some(key=>result.ownerLocator?.[key]!==target.ownerLocator?.[key])||typeof result.body!=='string'||range?.offset!==offset||range?.chars!==result.body.length||!Number.isSafeInteger(range?.totalChars)||range.totalChars<offset+result.body.length||!Number.isSafeInteger(result.nextOffset)||(result.nextOffset!==-1&&result.nextOffset!==offset+result.body.length))throw new Error('原文身份或读取范围已变化，请重新搜索这条记录。');
     confirmed.current={target,identity,request,epoch};
   }
   setPage(result);setOffsets(previous=>{const index=previous.indexOf(offset);return index<0?[...previous,offset]:previous.slice(0,index+1)});
   if(scroller.current)scroller.current.scrollTop=0;
  }catch(failure){if(accepts()){setPage(null);setError(failure instanceof Error?failure.message:'原文暂不可读取，请重试。')}}
  finally{if(request===generation.current){pending.current=false;if(alive.current)setBusy(false)}}
 },[target,conversation,identity]);
 useEffect(()=>{
  alive.current=true;pending.current=false;setPage(null);void read(requestedOffset.current);
  const resume=()=>{generation.current++;pending.current=false;if(document.hidden)setBusy(false);else if(!conversation){setPage(null);void read(requestedOffset.current)}};
  document.addEventListener('visibilitychange',resume);
  return()=>{alive.current=false;generation.current++;pending.current=false;document.removeEventListener('visibilitychange',resume)};
 },[read,identity,conversation]);
 const copy=async(text:string)=>{try{await onCopy(text)}catch(failure){if(alive.current)setError(failure instanceof Error?failure.message:'复制未完成，请选中文字后复制。');throw failure}};
 const current=page?.status==='current',range=page?.readRange;
 const original=page?.ownerLocator;
 useLayoutEffect(()=>{
  const proof=confirmed.current;
  if(!onOriginalAccepted||!current||covered||document.hidden||!alive.current||!proof||proof.target!==target||
     proof.identity!==identity||proof.request!==generation.current||proof.epoch!==bridge.getDocumentEpoch())return;
  // Only acknowledge the exact verified passage once its reader commit has taken place.
  confirmed.current=null;
  onOriginalAccepted(target);
 },[page,current,target,identity,covered,onOriginalAccepted]);
 const contextReady=current&&original?.kind==='owner_native_original'&&page.importAssignedIdentity===false&&
  page.derivedUseStatus==='not_authorized_by_this_owner_read'&&locatorFields.every(key=>original[key]===target.ownerLocator?.[key]);
 const locate=async()=>{
  if(!onContext||!contextReady||pending.current||contextOpening.current||covered)return;
  const ticket=generation.current;
  contextOpening.current=true;setLocating(true);setError('');
  contextPosition.current=scroller.current?.scrollTop??0;
  try{await onContext(original)}
  catch(failure){
   contextPosition.current=null;
   if(alive.current&&generation.current===ticket)setError(failure instanceof Error?failure.message:'未定位到原消息，已核对的原文仍在这里。');
  }
  finally{contextOpening.current=false;if(alive.current)setLocating(false)}
 };
 const restoreContext=(event:Event)=>{
  const top=contextPosition.current;
  const node=scroller.current,button=contextButton.current;
  if(top===null||covered||contextOpening.current||document.hidden||!node?.isConnected||!button?.isConnected)return;
  contextPosition.current=null;event.preventDefault();
  node.scrollTop=top;button.focus({preventScroll:true});
 };
 return <Drawer.Root open={!covered} onOpenChange={open=>{if(!open&&!covered)onClose()}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content inert={covered} className="pw-drawer pw-conversation-history-window pw-search-original-reader" aria-describedby="pw-search-original-description" onOpenAutoFocus={restoreContext} onCloseAutoFocus={event=>event.preventDefault()} onEscapeKeyDown={event=>{event.preventDefault();if(!covered)onClose()}}>
  <header className="pw-drawer-header"><IconButton icon={IconArrowLeft} aria-label="返回搜索结果" onClick={onClose}/><div><Drawer.Title>对话中的原文</Drawer.Title><Drawer.Description id="pw-search-original-description">查看搜索命中的原记录，返回后继续查找。</Drawer.Description></div></header>
  <div className="pw-drawer-body pw-search-original-body" ref={scroller} aria-busy={busy||undefined}>
   {(busy||locating)&&<p className="pw-muted" role="status">{locating?'正在定位同一条原消息…':'正在核对并读取原文…'}</p>}{error&&<p className="pw-error" role="alert">{error}</p>}
   {current?<><div className="pw-search-original-meta"><span>{[page.role==='assistant'?'管家的答复':page.role==='user'?'你交代的内容':'原记录',page.timeKnown&&typeof page.at==='number'?new Date(page.at).toLocaleString('zh-CN'):'',page.historicalRevisionKnown?`原修订 ${page.historicalRevision}`:''].filter(Boolean).join(' · ')}</span><MessageActions onCopy={()=>copy(page.body)}/></div><p className="pw-reading-copy">{page.body||'这条原记录没有文字。'}</p><p className="pw-muted">已读范围 {range.offset}–{range.offset+range.chars} / {range.totalChars}</p></>:page?<p role="status">{page.reason||'这份原记录已不可读取，请返回重新搜索。'}</p>:null}
   <div className="pw-action-row">{current&&range.offset>0&&offsets.length===1&&<Button variant="ghost" disabled={busy||locating} onClick={()=>void read(0)}>从原文开头读</Button>}{offsets.length>1&&<Button variant="ghost" disabled={busy||locating} onClick={()=>void read(offsets[offsets.length-2])}>上一段原文</Button>}{current&&page.nextOffset>=0&&<Button variant="secondary" disabled={busy||locating} onClick={()=>void read(page.nextOffset)}>继续读取原文</Button>}{error&&!current&&<Button variant="secondary" disabled={busy||locating} onClick={()=>void read(requestedOffset.current)}>重新读取这一段</Button>}</div>
  </div>
  <footer className="pw-drawer-actions"><Button variant="ghost" onClick={onClose}>返回搜索结果</Button>
   {onContext&&contextReady?<Button ref={contextButton} variant="secondary" disabled={busy||locating} onClick={()=>void locate()}>在原对话中定位</Button>:
    !onContext&&onConversation&&<Button variant="secondary" disabled={busy||locating} onClick={onConversation}>打开所属对话</Button>}</footer>
 </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
