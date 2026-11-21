import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';
import {IconChevronRight,IconFile,IconHistory} from './icons';
import {attachmentFileFormat,fileBytes} from './attachment-workspace';
import type {TaskResult} from './conversation-work';
import {usableResultFile,type TaskResultFile} from './TaskResultFiles';

export type DeliveredFileSource={messageId:string;revision:number;result:TaskResult};
type Item={source:DeliveredFileSource;file:TaskResultFile};
type Batch={index:number};
const batchSize=4;
const itemKey=({source,file}:Item)=>JSON.stringify([source.messageId,source.revision,file.id,file.sha256]);
const time=(value:number)=>new Intl.DateTimeFormat(undefined,{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}).format(value);

/** Only after an explicit visit/check: batch the already-visible original replies.
 * No history body, global inventory or guessed output name is requested here. */
export function ConversationDeliveredFiles({conversation,sources,covered,isCurrent,isSourceCurrent,onClose,onOpen,onHistory}:{
 conversation:string;sources:readonly DeliveredFileSource[];covered:boolean;isCurrent:()=>boolean;
 isSourceCurrent:(source:DeliveredFileSource)=>boolean;onClose:()=>void;onOpen:(item:Item,target:HTMLButtonElement)=>Promise<void>;
 onHistory?:()=>void;
}){
 const [batch,setBatch]=useState<Batch>({index:0});
 const [list,setList]=useState<{batch:Batch;items:Item[];partial:boolean;failed:boolean}|null>(null);
 const [opening,setOpening]=useState(''),[error,setError]=useState('');
 const openingFile=useRef(''),rows=useRef(new Map<string,HTMLButtonElement>());
 const returning=useRef<{key:string;top:number}|null>(null),wasCovered=useRef(false);
 const scope=useRef({isCurrent,isSourceCurrent});scope.current={isCurrent,isSourceCurrent};
 const page=list?.batch===batch?list:null;
 const more=(batch.index+1)*batchSize<sources.length;
 useEffect(()=>{
  let active=true;
  const current=()=>active&&scope.current.isCurrent();
  void (async()=>{
   const start=batch.index*batchSize,items:Item[]=[],seen=new Set<string>();
   let partial=false,failed=false;
   for(const source of sources.slice(start,start+batchSize)){
    if(!current())return;
    if(!scope.current.isSourceCurrent(source)){partial=true;continue}
    try{
     const answer=await bridge.command('tasks.resultFiles',{conversationId:conversation,resultMessageId:source.messageId,taskId:source.result.taskId});
     if(!current())return;
     if(!scope.current.isSourceCurrent(source)){partial=true;continue}
     if(answer?.taskId!==source.result.taskId||!Array.isArray(answer.files)||answer.files.length>8||typeof answer.readLimited!=='boolean'){partial=true;failed=true;continue}
     partial=partial||answer.readLimited;
     for(const file of answer.files){
      if(!usableResultFile(file)||file.runProvenance!=='current'){partial=true;continue}
      const key=file.id+':'+file.sha256;
      if(!seen.has(key)){seen.add(key);items.push({source,file});}
     }
    }catch{partial=true;failed=true}
   }
   if(current())setList({batch,items,partial,failed});
  })();
  return ()=>{active=false};
 },[conversation,sources,batch]);
 useLayoutEffect(()=>{
  if(covered){wasCovered.current=true;return;}
  if(!wasCovered.current)return;
  wasCovered.current=false;
  const position=returning.current;returning.current=null;
  if(!position)return;
  const frame=requestAnimationFrame(()=>{
   const row=rows.current.get(position.key);
   if(!scope.current.isCurrent()||!row?.isConnected||row.disabled||row.closest('[inert]'))return;
   const panel=row.closest<HTMLElement>('.pw-settings-dialog-content');
   if(panel)panel.scrollTop=position.top;
   row.focus({preventScroll:true});
  });
  return ()=>cancelAnimationFrame(frame);
 },[covered]);
 const change=(index:number)=>{
  if(openingFile.current||!page||!isCurrent())return;
  returning.current=null;setError('');setBatch({index});
 };
 const open=async(item:Item,target:HTMLButtonElement)=>{
  if(openingFile.current||!page||!isCurrent())return;
  if(!isSourceCurrent(item.source)){setError('原回复已不在当前对话里。没有打开其他文件。');return;}
  const key=itemKey(item);
  returning.current={key,top:target.closest<HTMLElement>('.pw-settings-dialog-content')?.scrollTop||0};
  openingFile.current=key;setOpening(key);setError('');
  try{await onOpen(item,target)}
  catch(failure){if(isCurrent())setError(failure instanceof Error?failure.message:'没有打开这份文件。')}
  finally{openingFile.current='';setOpening('')}
 };
 const items=page?.items.filter(item=>isSourceCurrent(item.source))??[];
 const partial=!!page&&(page.partial||items.length!==page.items.length);
 return <SettingsDialog isOpen={!covered} title={batch.index===0?'近期文件成果':'较早的文件成果'} closeLabel="关闭文件成果" onClose={onClose}>
  {!page&&<p role="status">正在核对这批原成果…</p>}
  {page&&!items.length&&<p role="status">{partial?'这批没有核对到可打开的原文件。':'这批没有已保存的文件成果。'}</p>}
  {!!items.length&&<div className="pw-outcome-files">{items.map(item=>{
   const {file,source}=item,key=itemKey(item),meta=[attachmentFileFormat(file.mimeType,file.name).label,fileBytes(file.size),time(source.result.endedAt)].join(' · ');
   return <Button key={key} ref={element=>{if(element)rows.current.set(key,element);else rows.current.delete(key)}} variant="ghost" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight}
    disabled={!!opening} onClick={event=>void open(item,event.currentTarget)}>
    <span className="pw-outcome-file-copy"><span dir="auto">{file.name}</span><small>{opening===key?'正在打开…':meta}</small></span>
   </Button>;
  })}</div>}
  {partial&&<p className="pw-muted">这批只显示已核对的文件。</p>}
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {page&&<div className="pw-form-actions">
   {more&&<Button variant="secondary" disabled={!!opening} onClick={()=>change(batch.index+1)}>查找较早的文件成果</Button>}
   {batch.index>0&&<Button variant="ghost" disabled={!!opening} onClick={()=>change(batch.index-1)}>返回上一批</Button>}
   {(error||page.failed)&&<Button variant="ghost" disabled={!!opening} onClick={()=>change(batch.index)}>重新核对这批</Button>}
   {onHistory&&!more&&<Button variant="ghost" leadingIcon={IconHistory} disabled={!!opening} onClick={onHistory}>浏览对话记录</Button>}
  </div>}
 </SettingsDialog>;
}
