import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';
import {IconChevronRight,IconFile} from './icons';
import {attachmentFileFormat,fileBytes} from './attachment-workspace';
import type {TaskResult} from './conversation-work';
import {usableResultFile,type TaskResultFile} from './TaskResultFiles';

export type DeliveredFileSource={messageId:string;revision:number;result:TaskResult};
type Item={source:DeliveredFileSource;file:TaskResultFile};
const time=(value:number)=>new Intl.DateTimeFormat(undefined,{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}).format(value);

/** Only opens when chosen; reuse the original Chat result reader, not a global file search. */
export function ConversationDeliveredFiles({conversation,sources,more,isCurrent,isSourceCurrent,onClose,onOpen}:{
 conversation:string;sources:readonly DeliveredFileSource[];more:boolean;isCurrent:()=>boolean;
 isSourceCurrent:(source:DeliveredFileSource)=>boolean;onClose:()=>void;onOpen:(item:Item,target:HTMLButtonElement)=>Promise<void>;
}){
 const [list,setList]=useState<{items:Item[];partial:boolean;failed:boolean}|null>(null);
 const [retry,setRetry]=useState(0),[opening,setOpening]=useState(''),[error,setError]=useState('');
 const scope=useRef({isCurrent,isSourceCurrent});scope.current={isCurrent,isSourceCurrent};
 useEffect(()=>{
  let active=true;
  const current=()=>active&&scope.current.isCurrent();
  setList(null);
  void (async()=>{
   const items:Item[]=[];let partial=more,failed=false;const seen=new Set<string>();
   for(const source of sources){
    if(!current())return;
    if(!scope.current.isSourceCurrent(source)){partial=true;continue}
    try{
     const page=await bridge.command('tasks.resultFiles',{conversationId:conversation,resultMessageId:source.messageId,taskId:source.result.taskId});
     if(!current())return;
     if(!scope.current.isSourceCurrent(source)) {partial=true;continue}
     if(page?.taskId!==source.result.taskId||!Array.isArray(page.files)||page.files.length>8||typeof page.readLimited!=='boolean') {partial=true;failed=true;continue}
     partial=partial||page.readLimited;
     for(const file of page.files){
      if(!usableResultFile(file)||file.runProvenance!=='current'){partial=true;continue}
      const key=file.id+':'+file.sha256;
      if(!seen.has(key)){
       seen.add(key);
       if(items.length<8)items.push({source,file});else partial=true;
      }
     }
    }catch{partial=true;failed=true}
   }
   if(current())setList({items,partial,failed});
  })();
  return ()=>{active=false};
 },[conversation,sources,more,retry]);
 const open=async(item:Item,target:HTMLButtonElement)=>{
  if(opening||!isCurrent())return;
  if(!isSourceCurrent(item.source)){setError('原回复已不在当前对话里。没有打开其他文件。');return;}
  setOpening(item.file.id);setError('');
  try{await onOpen(item,target)}
  catch(failure){if(isCurrent())setError(failure instanceof Error?failure.message:'没有打开这份文件。')}
  finally{if(isCurrent())setOpening('')}
 };
 const items=list?.items.filter(item=>isSourceCurrent(item.source))??[];
 const partial=!!list&&(list.partial||items.length!==list.items.length);
 return <SettingsDialog isOpen title="近期文件成果" closeLabel="关闭近期文件成果" onClose={onClose}>
  {!list&&<p role="status">正在读取近期成果…</p>}
  {list&&!items.length&&<p role="status">{partial?'暂时没有读到可打开的近期文件。':'这段对话的近期工作没有交付文件。'}</p>}
  {!!items.length&&<div className="pw-outcome-files">{items.map(item=>{
   const {file,source}=item,meta=[attachmentFileFormat(file.mimeType,file.name).label,fileBytes(file.size),time(source.result.endedAt)].join(' · ');
   return <Button key={file.id+':'+file.sha256} variant="ghost" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight}
    disabled={!!opening} onClick={event=>void open(item,event.currentTarget)}>
    <span className="pw-outcome-file-copy"><span dir="auto">{file.name}</span><small>{opening===file.id?'正在打开…':meta}</small></span>
   </Button>;
  })}</div>}
  {partial&&<p className="pw-muted">这里只显示已核对的近期文件。更早的成果仍在原对话记录。</p>}
  {error&&<p className="pw-error" role="alert">{error}</p>}
  {(error||list?.failed)&&<Button variant="secondary" disabled={!!opening} onClick={()=>{setError('');setRetry(value=>value+1)}}>重新读取</Button>}
 </SettingsDialog>;
}
