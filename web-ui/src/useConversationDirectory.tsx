import {useCallback,useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';

type Conversation = {id:string;title:string;updatedAt:number};
type Page = {key:string;rows:Conversation[];cursor:string;hasMore:boolean;loading:boolean;loaded:boolean;error:string;gaps:boolean};
const empty=(key:string):Page=>({key,rows:[],cursor:'',hasMore:true,loading:false,loaded:false,error:'',gaps:false});

/** On-demand header pages, separate from the native active conversation and its draft. */
export function useConversationDirectory(active:boolean,supported:boolean,windowKey:string){
 const [page,setPage]=useState<Page>(()=>empty(windowKey));
 const generation=useRef(0),entered=useRef(false);
 const read=useCallback(async(cursor:string)=>{
  if(!active||!supported||entered.current||document.hidden)return;
  const request=++generation.current;entered.current=true;
  setPage(previous=>({...previous,loading:true,error:''}));
  try{
   const value=await bridge.command<any>('conversation.directory',{cursor});
   if(request!==generation.current)return;
   if(!Array.isArray(value.conversations)||typeof value.hasMore!=='boolean'||typeof value.nextCursor!=='string'
       ||value.bodyIncluded!==false||value.executionAuthorized!==false)throw new Error('对话目录没有完整返回，请重新读取。');
   if(value.hasMore&&(!value.nextCursor||value.nextCursor===cursor))throw new Error('对话目录没有返回下一页位置，请重新打开后再试。');
   const rows:Conversation[]=value.conversations.map((row:any)=>{
    if(typeof row.conversationId!=='string'||!row.conversationId||typeof row.title!=='string')throw new Error('对话目录条目无法读取。');
    return {id:row.conversationId,title:row.title,updatedAt:typeof row.updatedAt==='number'?row.updatedAt:0};
   });
   setPage(previous=>{
    const merged=new Map((cursor?previous.rows:[]).map(row=>[row.id,row]));
    rows.forEach(row=>merged.set(row.id,row));
    return {key:windowKey,rows:[...merged.values()],cursor:value.nextCursor,hasMore:value.hasMore,loading:false,loaded:true,error:'',
      gaps:(cursor&&previous.gaps)||Array.isArray(value.gaps)&&value.gaps.length>0};
   });
  }catch(failure){
   if(request===generation.current)setPage(previous=>({...previous,loading:false,error:failure instanceof Error?failure.message:String(failure)}));
  }finally{if(request===generation.current)entered.current=false;}
 },[active,supported,windowKey]);
 useEffect(()=>{
  generation.current++;entered.current=false;setPage(empty(windowKey));
  if(active&&supported)void read('');
  return()=>{generation.current++;entered.current=false;};
 },[active,supported,windowKey,read]);
 const visible=page.key===windowKey&&active?page:empty(windowKey);
 return {...visible,loadMore:()=>{if(visible.hasMore&&!entered.current)void read(visible.cursor)}};
}

export function ConversationDirectoryControls({directory}:{directory:ReturnType<typeof useConversationDirectory>}){
 return <div aria-label="对话目录分页">
  <span>{directory.loaded&&!directory.hasMore?'已到对话目录末尾':'搜索已加载标题，可继续加载更早对话'}</span>
  {directory.gaps&&<p role="status">部分对话资料未能读取，未将它们视为不存在。</p>}
  {directory.error&&<p className="pw-error" role="alert">{directory.error}</p>}
  {directory.hasMore&&<Button variant="ghost" size="small" disabled={directory.loading} onClick={directory.loadMore}>{directory.loading?'正在读取…':directory.error?'重试读取':directory.loaded?'加载更早对话':'读取对话目录'}</Button>}
 </div>;
}
