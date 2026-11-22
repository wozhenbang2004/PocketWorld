import {useId,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {IconChevronRight,IconExternalLink,IconFile} from './icons';
import './message-attachment.css';

type Origin={conversationId:string;messageId?:string|null;sourceRevision?:number;window?:number;taskId?:string};
type Props={origin:Origin;file:Record<string,unknown>;view:string;enabled:boolean;canLocate:boolean;
 history?:boolean;workCopy?:ReactNode;beforeOpen?:()=>void;
 onLocalPdf?:(source:MessageAttachmentSource,trigger:HTMLButtonElement)=>Promise<void>};
const messages={
 checking:'正在核对这份原附件…',
 locating:'正在前往文件库核对原件…',
 viewer_requested:'已请求手机打开。是否读到内容请在查看应用里核对。',
 no_reader:'手机没有找到能打开这份附件的应用。原件未改动。',
 not_prepared:'无法核对或准备这份原附件，本次没有请求打开。',
 launch_unknown:'未能确认查看应用是否已启动。请先查看最近使用的应用。',
 scope_changed:'原附件或窗口已变化。请在原消息重新核对。',
 unconfirmed:'没有收到这次打开的结果。请先检查查看应用是否已打开。',
 library_unavailable:'手机没有找到查看应用，文件库也未能打开这份原件。',
 pdf_unavailable:'本机预览尚未确认。可从原消息重试；没有请求其他应用打开。',
};
type Phase=keyof typeof messages;
export type MessageAttachmentSource=Origin&{messageId:string;sourceRevision:number;id:string;sha256:string};
type Source=MessageAttachmentSource;
type Result=Partial<Source>&{status?:string};
const nativePhases=new Set<Phase>(['viewer_requested','no_reader','not_prepared','launch_unknown','scope_changed']);

function isVisibleSource(source:Source) {
 const snapshot=bridge.getState(),state=snapshot.state;
 const view=snapshot.view as {section?:string|null;taskId?:string|null}|undefined;
 if(!view||view.section!=null||(view.taskId??undefined)!==source.taskId||state.attachmentWorkspace?.open)return false;
 const reader=state.conversationReader;
 const sameReader=reader?.window===source.window&&reader?.conversation===source.conversationId;
 const sameComposer=state.conversationId===source.conversationId&&(!reader||source.taskId);
 const rows=source.window===undefined?sameComposer&&state.messages:sameReader&&reader.messages;
 return Array.isArray(rows)&&rows.some(row=>row?.id===source.messageId&&row.sourceRevision===source.sourceRevision&&
  Array.isArray(row.attachments)&&row.attachments.some((file:Record<string,unknown>)=>file.id===source.id&&file.sha256===source.sha256));
}

/** This window displays only the native response to this explicit click, not a file-level history. */
export function MessageAttachment({origin,file,view,enabled,canLocate,history,workCopy,beforeOpen,onLocalPdf}:Props){
 const expected=origin.messageId&&origin.conversationId&&typeof origin.sourceRevision==='number'&&Number.isSafeInteger(origin.sourceRevision)&&origin.sourceRevision>=0&&
  typeof file.id==='string'&&file.id&&typeof file.sha256==='string'&&/^[a-f0-9]{64}$/.test(file.sha256)
  ? {...origin,messageId:origin.messageId,sourceRevision:origin.sourceRevision,id:file.id,sha256:file.sha256}:null;
 const epoch=bridge.getDocumentEpoch(),identity=JSON.stringify([epoch,view,expected]);
 const [feedback,setFeedback]=useState<{identity:string;phase:Phase}|null>(null);
 const trigger=useRef<HTMLButtonElement>(null),running=useRef<{identity:string}|null>(null);
 const statusId=useId(),name=typeof file.name==='string'&&file.name?file.name:'原附件';
 useLayoutEffect(()=>()=>{if(running.current?.identity===identity)running.current=null},[identity]);
 const phase=enabled&&feedback?.identity===identity?feedback.phase:null;
 const busy=phase==='checking'||phase==='locating';
 const stillPresented=()=>enabled&&!document.hidden&&epoch===bridge.getDocumentEpoch()&&
  !!trigger.current?.isConnected&&!trigger.current.closest('[hidden],[inert],[aria-hidden="true"]');
 const open=async(place:'local'|'external'|'library'='external')=>{
  const source=expected,library=place==='library',local=place==='local';
  if(!source||!stillPresented()||running.current?.identity===identity)return;
  const update=(phase:Phase)=>setFeedback({identity,phase});
  if(!isVisibleSource(source)){update(library?'library_unavailable':'scope_changed');return}
  const request={identity};
  running.current=request;
  const current=()=>running.current===request&&stillPresented()&&isVisibleSource(source);
  update(library?'locating':'checking');
  try{
   beforeOpen?.();
   if(local){
    if(onLocalPdf&&trigger.current)await onLocalPdf(source,trigger.current);
   }else if(library){
    const reply=await bridge.command<{opened?:boolean}>('attachmentWorkspace.open',{messageAttachment:source});
    if(current())update(reply?.opened===true?'no_reader':'library_unavailable');
   }else{
    const reply=await bridge.command<Result>(source.window===undefined?'attachments.open':'conversation.reader.attachment',source);
    if(current())update(reply&&reply.conversationId===source.conversationId&&reply.messageId===source.messageId&&
     reply.sourceRevision===source.sourceRevision&&reply.id===source.id&&reply.sha256===source.sha256&&
     reply.window===source.window&&reply.taskId===source.taskId&&nativePhases.has(reply.status as Phase)
     ? reply.status as Phase : 'unconfirmed');
   }
  }catch(error){
   if(current())update(local?'pdf_unavailable':library?'library_unavailable':
    error instanceof Error&&'code' in error&&['invalid_request','not_accepted','not_sent'].includes(String(error.code))?'scope_changed':'unconfirmed');
  }finally{
   if(running.current===request){
    running.current=null;
    setFeedback(value=>value?.identity===identity&&(value.phase==='checking'||value.phase==='locating')?null:value);
   }
  }
 };
 const primary=<Button ref={trigger} variant={history?'ghost':'secondary'} size={!history&&!workCopy?'small':'medium'} leadingIcon={IconFile}
  trailingIcon={workCopy?IconChevronRight:undefined} className={workCopy?'pw-outcome-file':undefined}
  disabled={!enabled||!expected||busy} aria-busy={busy||undefined} aria-describedby={phase?statusId:undefined}
  aria-label={onLocalPdf?`在本机查看 ${name}`:undefined}
  onClick={()=>void open(onLocalPdf?'local':'external')}>{workCopy??name}</Button>;
 return <div className="pw-message-file" data-motion-key={workCopy?`file:${origin.messageId}:${file.id}`:undefined}>
  {onLocalPdf?<div className="pw-message-pdf-actions">{primary}<IconButton icon={IconExternalLink} size="small"
   aria-label={`用其他应用打开 ${name}`} title="用其他应用打开" disabled={!enabled||!expected||busy} onClick={()=>void open('external')}/></div>:primary}
  {phase&&<p id={statusId} className="pw-field-hint pw-message-file-feedback" role="status">{messages[phase]}</p>}
  {(phase==='no_reader'||phase==='library_unavailable')&&canLocate&&!onLocalPdf&&
   <Button variant="ghost" size="small" className="pw-message-file-next" onClick={()=>void open('library')}>在文件库核对原件</Button>}
 </div>;
}
