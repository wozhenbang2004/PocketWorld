import {useId,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {IconFile,IconLink} from './icons';

type Target={conversationId:string;messageId:string;sourceRevision:number};
type Window={section?:unknown;taskId?:unknown;resumeRevision?:unknown};
type FileLink={id:string;sha256:string;name:string;mimeType:'application/pdf'};
type Lease={id:string;cancelled:boolean;windowId?:string;trigger:HTMLButtonElement;sourceCurrent:()=>boolean;retiring?:Promise<boolean>};
export type SavedChatPdfVisit={current:Lease|null};
const action='attachmentWorkspace.savedChatLinks';
const available=(value:unknown):FileLink[]=>Array.isArray(value)&&value.length<=4?value.filter((row):row is FileLink=>
 typeof row?.id==='string'&&typeof row.sha256==='string'&&/^[a-f0-9]{64}$/.test(row.sha256)&&
 typeof row.name==='string'&&!!row.name&&row.mimeType==='application/pdf'):[];
const errorLabel='暂不能核对这条消息中的原 PDF，没有打开其他文件。';

/** Retire the metadata list and any selected native panel before the actual Chat route exit. */
export function retireSavedChatPdfVisit(holder:SavedChatPdfVisit):Promise<boolean>{
 const lease=holder.current;if(!lease)return Promise.resolve(true);
 if(lease.retiring)return lease.retiring;
 lease.cancelled=true;
 const epoch=bridge.getDocumentEpoch(),live=()=>holder.current===lease&&bridge.getDocumentEpoch()===epoch;
 const closed=()=>bridge.getState().state.attachmentWorkspace?.savedChatLinkedOnly!==true;
 lease.retiring=(async()=>{
  try{
   const ack=await bridge.command<{retired?:boolean;visitId?:string}>(action,{retireVisit:lease.id});
   if(!live()||ack?.retired!==true||ack.visitId!==lease.id)return false;
   if(!closed()&&!await new Promise<boolean>(resolve=>{
    let off=()=>{};
    const finish=(result:boolean)=>{clearTimeout(timer);off();resolve(result)};
    const timer=window.setTimeout(()=>finish(false),5000);
    const check=()=>{if(!live()||closed())finish(live()&&closed())};
    off=bridge.subscribe(check);check();
   }))return false;
   if(!live()||!closed())return false;
   holder.current=null;return true;
  }catch{return false}
  finally{lease.retiring=undefined}
 })();
 return lease.retiring;
}

export function SavedChatFileLinks({holder,isVisitCurrent,...target}:Target&{holder:SavedChatPdfVisit;isVisitCurrent:()=>boolean}){
 const sectionId=useId(),root=useRef<HTMLDivElement>(null),flight=useRef(false);
 const [listed,setListed]=useState<{lease:Lease;files:FileLink[]}>();
 const [busy,setBusy]=useState(false),[error,setError]=useState('');
 const list=listed&&listed.lease===holder.current&&!listed.lease.cancelled?listed:undefined;
 const current=(epoch:number,resume:unknown)=>{
  const live=bridge.getState(),window=live.view as Window|undefined,control=root.current;
  return isVisitCurrent()&&!!control?.isConnected&&!control.closest('[hidden],[inert],[aria-hidden="true"]')&&!document.hidden&&bridge.getDocumentEpoch()===epoch&&
   window?.section===null&&window.taskId===null&&typeof resume==='number'&&Number.isSafeInteger(resume)&&window.resumeRevision===resume&&
   live.state.conversationId===target.conversationId&&
   Array.isArray(live.state.messages)&&live.state.messages.some(row=>row?.id===target.messageId&&row.sourceRevision===target.sourceRevision&&row.savedChatLinkOffered===true);
 };
 const open=async(trigger:HTMLButtonElement,file?:FileLink)=>{
  const epoch=bridge.getDocumentEpoch(),before=bridge.getState(),resume=(before.view as Window|undefined)?.resumeRevision;
  const sourceCurrent=()=>current(epoch,resume);
  const atOriginal=()=>sourceCurrent()&&bridge.getState().state.attachmentWorkspace?.open===false;
  if(flight.current||!atOriginal()||!trigger.isConnected)return;
  flight.current=true;setBusy(true);setError('');
  let visit:Lease|undefined;
  try{
   if(file){
    visit=list?.lease;
    if(!visit||holder.current!==visit||visit.cancelled||!visit.sourceCurrent()||!list?.files.includes(file))return;
    visit.trigger=trigger;visit.windowId=undefined;
   }else{
    if(holder.current&&!await retireSavedChatPdfVisit(holder))throw new Error(errorLabel);
    if(!atOriginal()||holder.current)return;
    // Keep this nonce even while the first list is still pending, so route exit can retire it.
    visit={id:crypto.randomUUID(),cancelled:false,trigger,sourceCurrent};holder.current=visit;
   }
   const receipt=await bridge.command<{opened?:boolean;visitId?:string;windowId?:string;files?:unknown}>(action,
    {...target,visitId:visit.id,...(file?{fileId:file.id,sha256:file.sha256}:{})});
   if(holder.current!==visit||visit.cancelled)return;
   if(receipt?.visitId!==visit.id)throw new Error(errorLabel);
   if(file){
    if(receipt.opened!==true||typeof receipt.windowId!=='string'||!receipt.windowId)throw new Error(errorLabel);
    visit.windowId=receipt.windowId;
   }else if(atOriginal())setListed({lease:visit,files:available(receipt.files)});
  }catch{
   if(visit&&holder.current===visit&&!visit.cancelled)await retireSavedChatPdfVisit(holder);
   if(atOriginal())setError(errorLabel);
  }finally{
   flight.current=false;
   if(root.current?.isConnected)setBusy(false);
  }
 };
 const collapse=async()=>{
  if(!list||flight.current)return;
  setListed(undefined);setError('');
  flight.current=true;setBusy(true);
  try{
   if(holder.current===list.lease&&!await retireSavedChatPdfVisit(holder)&&list.lease.sourceCurrent())setError(errorLabel);
  }finally{
   flight.current=false;
   if(root.current?.isConnected)setBusy(false);
  }
 };
 return <div className="pw-message-file" ref={root} aria-busy={busy||undefined}>
  <Button variant="ghost" size="small" leadingIcon={IconLink} aria-expanded={!!list} aria-controls={list?sectionId:undefined} disabled={busy}
   onClick={event=>list?void collapse():void open(event.currentTarget)}>关联的 PDF</Button>
  {list&&<div id={sectionId}>
   {list.files.length?<div className="pw-message-files" role="group" aria-label="原消息中关联的文件位置">{list.files.map(file=><div key={file.id}>
    <Button variant="secondary" size="small" leadingIcon={IconFile} disabled={busy} onClick={event=>void open(event.currentTarget,file)}>
     <bdi>{file.name}</bdi></Button>
   </div>)}</div>:<p className="pw-field-hint" role="status">未核对到可查看的关联 PDF。</p>}
  </div>}
  {error&&<p className="pw-field-hint" role="status">{error}</p>}
 </div>;
}
