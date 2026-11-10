import {useEffect,useRef,useState} from 'react';
import {RiArrowLeftLine,RiCloseLine,RiRefreshLine} from '@remixicon/react';
import {Button,IconButton,RadioOptions,TextAreaField} from './forms-library';
import {Drawer} from './shell-library';
import {useClosingSnapshot} from './library/surface-motion';
import './conversation-queued-inputs.css';

type Json=Record<string,any>;
type Props={page:Json|null;can:(action:string)=>boolean;act:(action:string,args?:Json)=>Promise<any>;registerBack:(handler:(()=>boolean)|null)=>void};
const queueStatus=(row:Json)=>(({pending:row.mode==='steer'?'等待下个安全步骤':'等待本轮结束',claimed:'任务已接手',materialized:'已纳入任务上下文',withdrawn:'已撤回',admitted:'初始目标已接收'} as Record<string,string>)[String(row.queueState)]||'接手状态待核对');
const binding=(page:Json)=>({window:page.window,taskId:page.task,conversationId:page.conversation});
const editBinding=(page:Json)=>({...binding(page),messageId:page.editor.message,queueRevision:page.editor.queueRevision,messageRevision:page.editor.messageRevision});

/** Native owner-input queue projection. Reading/edits never create a new input or
 * grant a new execution scope. The native queue/message revisions remain CAS. */
export function ConversationQueuedInputs({page,can,act,registerBack}:Props){
 const exit=useClosingSnapshot(page),shown:Json=exit.display??{};
 const [pending,setPending]=useState(false),[error,setError]=useState('');
 const live=useRef(page),sending=useRef(false);live.current=page;
 const invoke=async(action:string,args:Json)=>{
  if(sending.current)return;
  const original=live.current?.window;sending.current=true;setPending(true);setError('');
  try{await act(action,args)}catch(failure){if(live.current?.window===original)setError(failure instanceof Error?failure.message:'这次操作未确认，请刷新原队列核对。')}
  finally{sending.current=false;setPending(false)}
 };
 const back=()=>{const current=live.current;if(!current)return false;if(sending.current||current.saving)return true;void invoke(current.editor?'queued.editor.close':'queued.close',current.editor?editBinding(current):binding(current));return true};
 useEffect(()=>{registerBack(back);return()=>registerBack(null)});
 useEffect(()=>{setError('')},[page?.window,page?.editor?.message,page?.editor?.queueRevision]);
 const close=()=>{const current=live.current;if(current&&!sending.current&&!current.saving)void invoke('queued.close',binding(current))};
 return <Drawer.Root open={!!page} onOpenChange={open=>{if(!open)close()}} dismissible={!pending&&!page?.saving} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-queued-inputs-drawer" aria-describedby="pw-queued-description" inert={!page} onCloseAutoFocus={exit.onCloseAutoFocus} onEscapeKeyDown={event=>{if(page?.editor){event.preventDefault();back()}}}>
  <div className="pw-drawer-handle"/>
  <header className="pw-drawer-header">{shown?.editor&&<IconButton icon={RiArrowLeftLine} aria-label="返回排队消息" disabled={pending||!!page?.saving} onClick={back}/>}<div><Drawer.Title>{shown?.editor?(shown.editor.withdrawn?'撤回这条消息':'调整排队消息'):'排队消息'}</Drawer.Title><Drawer.Description id="pw-queued-description">只调整尚未被任务接手的输入，不会重发消息或扩大任务范围。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="关闭排队消息" disabled={pending||!!page?.saving} onClick={close}/></header>
  <div className="pw-drawer-body pw-queued-inputs-body">
   {(error||shown?.error)&&<p className="pw-error" role="alert">{error||shown?.error}</p>}
   {shown?.notice&&<p className="pw-muted" role="status">{shown.notice}</p>}
   {shown?.current===false&&<p className="pw-error" role="alert">原对话窗口已变化，请关闭后重新打开排队消息。</p>}
   {shown?.editor?<QueueEditor key={`${shown.window}:${shown.editor.message}:${shown.editor.queueRevision}`} page={shown} disabled={!page||shown.current!==true||pending||shown.saving||!!shown.error} can={can} act={act} onPending={setPending} sending={sending} onError={setError}/>:<>
    <div className="pw-queued-inputs-tools"><span role="status">{shown?.reading?'正在读取原队列':shown?.readingMessage?'正在读取原消息':'原任务的输入记录'}</span><IconButton icon={RiRefreshLine} aria-label="刷新排队消息" disabled={!page||shown?.current!==true||pending||!!shown?.reading||!!shown?.readingMessage||!can('queued.refresh')} onClick={()=>{if(page)void invoke('queued.refresh',binding(page))}}/></div>
    <ol className="pw-queued-inputs-list">{Array.isArray(shown?.items)&&shown.items.map((row:Json)=><li key={row.messageId}>
     <small>{queueStatus(row)}</small><p>{row.content||'仅附件消息'}{row.contentTruncated&&'…'}</p>
     {(row.attachmentCount>0||row.sourceReferenceCount>0||row.previewReferenceCount>0)&&<small>{[row.attachmentCount>0?`${row.attachmentCount} 个附件`:'',row.sourceReferenceCount>0?`${row.sourceReferenceCount} 处原文引用`:'',row.previewReferenceCount>0?`${row.previewReferenceCount} 处预览引用`:''].filter(Boolean).join(' · ')}</small>}
     {row.editable===true&&<div className="pw-action-row"><Button variant="ghost" disabled={!page||shown.current!==true||pending||shown.reading||!!shown.readingMessage||!can('queued.edit')} onClick={()=>{if(page)void invoke('queued.edit',{...binding(page),messageId:row.messageId,withdrawn:false})}}>调整内容与时机</Button><Button variant="ghost" disabled={!page||shown.current!==true||pending||shown.reading||!!shown.readingMessage||!can('queued.edit')} onClick={()=>{if(page)void invoke('queued.edit',{...binding(page),messageId:row.messageId,withdrawn:true})}}>撤回</Button></div>}
    </li>)}</ol>
    {!shown?.reading&&!shown?.error&&!shown?.items?.length&&<p className="pw-muted">这项任务没有可显示的输入记录。</p>}
   </>}
  </div>
 </Drawer.Content></Drawer.Portal></Drawer.Root>;
}

function QueueEditor({page,disabled,can,act,onPending,sending,onError}:{page:Json;disabled:boolean;can:Props['can'];act:Props['act'];onPending:(value:boolean)=>void;sending:{current:boolean};onError:(value:string)=>void}){
 const edit=page.editor,[text,setText]=useState(String(edit.text||'')),[mode,setMode]=useState(String(edit.mode||'queue'));
 const mounted=useRef(true);useEffect(()=>()=>{mounted.current=false},[]);
 const submit=async()=>{
  if(disabled||sending.current)return;
  sending.current=true;onPending(true);onError('');const original=editBinding(page);
  try{if(!edit.withdrawn)await act('queued.change',{...original,text,mode});await act('queued.save',original)}
  catch(failure){if(mounted.current)onError(failure instanceof Error?failure.message:'保存尚未确认，请返回队列刷新核对。')}
  finally{sending.current=false;onPending(false)}
 };
 return <section className="pw-queued-input-editor">
  {edit.withdrawn?<><p className="pw-queued-original">{edit.baseline||'仅附件消息'}</p><p className="pw-muted">确认后这条尚未接手的消息不再参与任务。已经执行的动作不会撤销。</p></>:<>
   <TextAreaField label="消息内容" value={text} onChange={setText} isDisabled={disabled} rows={4} maxLength={12000} showCount/>
   <RadioOptions label="采用时机" value={mode} onChange={setMode} isDisabled={disabled} options={[{id:'queue',label:'本轮结束后处理'},{id:'steer',label:'下个安全步骤加入'}]}/>
   <p className="pw-muted">保留原附件和引用。任务已接手时不能再修改，请另发补充。</p>
  </>}
  <Button variant={edit.withdrawn?'danger':'primary'} disabled={disabled||!can('queued.save')||!edit.withdrawn&&(!can('queued.change')||!text.trim()&&!edit.attachmentCount||text.length>12000)} onClick={submit}>{page.saving?'正在保存…':edit.withdrawn?'确认撤回':'保存修改'}</Button>
 </section>;
}
