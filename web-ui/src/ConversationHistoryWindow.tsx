import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {RiCloseLine,RiFileLine,RiRefreshLine} from '@remixicon/react';
import {Button,IconButton} from './forms-library';
import {Drawer} from './shell-library';
import {AIAnswer} from './library';
import {useClosingSnapshot} from './library/surface-motion';
import './conversation-history-window.css';

type Json=Record<string,any>;
type Position={index:number;offset:number;anchor:string};
type Props={page:Json|null;submitting:boolean;can:(action:string)=>boolean;act:(action:string,args?:Json)=>Promise<any>;registerBack:(handler:(()=>boolean)|null)=>void};
/** A second, read-only history window. The active composer stays with its original
 * submission until the owner explicitly switches after its receipt has settled. */
export function ConversationHistoryWindow({page,submitting,can,act,registerBack}:Props){
 const exit=useClosingSnapshot(page),shown:Json=exit.display??{};
 const scroll=useRef<HTMLDivElement>(null),live=useRef(page);live.current=page;
 const restored=useRef<number|null>(null),position=useRef<Position>({index:0,offset:0,anchor:''});
 const timer=useRef<ReturnType<typeof setTimeout>|null>(null),sending=useRef(false);
 const [pending,setPending]=useState(false),[error,setError]=useState('');
 const rows:Json[]=Array.isArray(shown.messages)?shown.messages:[];
 const marker=rows.map(row=>`${row.id}:${row.sourceRevision||0}`).join('|');
 const args=()=>({window:live.current?.window,conversationId:live.current?.conversation});
 const remember=()=>{
  const node=scroll.current;if(!node||!live.current||restored.current!==live.current.window)return;
  const items=Array.from(node.querySelectorAll<HTMLElement>('[data-reader-index]'));
  const row=items.find(item=>item.getBoundingClientRect().bottom>node.getBoundingClientRect().top)||items.at(-1);if(!row)return;
  position.current={index:Number(row.dataset.readerIndex),offset:Math.max(0,Math.round(node.getBoundingClientRect().top-row.getBoundingClientRect().top)),anchor:row.dataset.readerId||''};
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
 const back=()=>{if(!live.current)return false;if(!sending.current)void invoke('conversation.reader.close');return true};
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
  restored.current=page.window;remember();
 },[page?.window,marker]);
 useEffect(()=>{setError('')},[page?.window]);
 return <Drawer.Root open={!!page} onOpenChange={open=>{if(!open)back()}} dismissible={!pending} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-conversation-history-window" aria-describedby="pw-history-window-description" inert={!page} onCloseAutoFocus={exit.onCloseAutoFocus}>
  <header className="pw-drawer-header"><div><Drawer.Title>{shown.title||'阅读对话'}</Drawer.Title><Drawer.Description id="pw-history-window-description">只读查看，原输入和发送回执仍留在原对话。</Drawer.Description></div><IconButton icon={RiRefreshLine} aria-label="刷新这份对话历史" disabled={!page||pending||!!shown.loading||!can('conversation.reader.refresh')} onClick={()=>void invoke('conversation.reader.refresh')}/><IconButton icon={RiCloseLine} aria-label="返回原输入" disabled={!page||pending} onClick={back}/></header>
  <div className="pw-drawer-body pw-history-window-messages" ref={scroll} onScroll={saveReading}>
   {(error||shown.error)&&<p className="pw-error" role="alert">{error||shown.error}</p>}
   {shown.notice&&<p className="pw-muted">{shown.notice}</p>}
   {shown.hasMore&&!shown.limited&&<Button variant="ghost" disabled={!page||pending||shown.loading||!can('conversation.reader.earlier')} onClick={()=>void invoke('conversation.reader.earlier')}>查看更早消息</Button>}
   {shown.loading&&<p className="pw-muted" role="status">正在读取原消息…</p>}
   {rows.map((message,index)=><article key={message.id} className={`pw-history-window-message pw-message-${message.role}`} data-reader-index={index} data-reader-id={message.id} aria-label={message.role==='assistant'?'助手回复':'你的消息'}>
    {message.role==='assistant'?<AIAnswer text={message.content} showActions={false}/>:<p className="pw-user-text">{message.content}</p>}
    {Array.isArray(message.attachments)&&message.attachments.length>0&&<div className="pw-message-files">{message.attachments.map((file:Json)=><Button key={`${file.id}:${file.sha256}`} variant="ghost" leadingIcon={RiFileLine} disabled={!page||pending||!can('conversation.reader.attachment')} onClick={()=>void invoke('conversation.reader.attachment',{messageId:message.id,id:file.id,sha256:file.sha256})}>{file.name||'原附件'}</Button>)}</div>}
   </article>)}
   {!shown.loading&&!shown.error&&!rows.length&&<p className="pw-muted">这份对话没有可读取的消息。</p>}
  </div>
  <footer className="pw-drawer-actions"><Button variant="ghost" disabled={pending} onClick={back}>返回原输入</Button><Button variant="secondary" disabled={!page||submitting||pending||!can('conversation.reader.select')} onClick={()=>void invoke('conversation.reader.select')}>{submitting?'原输入正在登记':'在此对话继续'}</Button></footer>
 </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
