import {useCallback,useEffect,useMemo,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {CommandMenu,type WorkCommand} from './shell-library/CommandMenu';
import {IconChat,IconFile,IconFolder,IconLayers} from './icons';
import './work-search.css';

export type WorkSearchItem={kind:'conversation'|'work'|'artifact'|'project';id:string;title:string;excerpt?:string;updatedAt?:number;status?:string;conversationId?:string;projectId?:string;taskId?:string;artifactId?:string;sha256?:string;recordId?:string;ownerLocator?:Record<string,unknown>;readRange?:{offset?:number;chars?:number}};
export type WorkSearchMemory={query?:string;selectedId?:string;scrollTop?:number;pages?:number};
type SearchReply={items:WorkSearchItem[];nextCursor?:string;notices?:string[]};
const kinds={conversation:'对话',work:'工作',artifact:'成果',project:'项目'};
const icons={conversation:IconChat,work:IconLayers,artifact:IconFile,project:IconFolder};
const itemKey=(item:WorkSearchItem)=>`${item.kind}:${item.id}:${item.sha256||''}`;
const record=(value:unknown):Record<string,any>=>value&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,any>:{};
function readReply(value:unknown):SearchReply{
 const data=record(value);
 if(!Array.isArray(data.items)||data.items.some((item:any)=>!item||!Object.hasOwn(kinds,item.kind)||typeof item.id!=='string'||!item.id||typeof item.title!=='string'))throw new Error('搜索结果暂不可读取，请重新搜索。');
 return {items:data.items,nextCursor:typeof data.nextCursor==='string'?data.nextCursor:undefined,notices:Array.isArray(data.notices)?data.notices.filter((notice:unknown)=>typeof notice==='string'):[]};
}
/** The retained Affect/BeUI search surface reads the native persistent stores.
 * Query and return position are presentation memory; result rows are always reread. */
export function WorkSearch({supported,identity,memory,onOpen,onClose,labelStatus}:{supported:boolean;identity:string;memory:WorkSearchMemory;onOpen:(item:WorkSearchItem)=>void|Promise<void>;onClose:()=>void;labelStatus:(status:string)=>string}){
 const [query,setQuery]=useState(memory.query||''),[reply,setReply]=useState<SearchReply|null>(null);
 const [loading,setLoading]=useState(false),[opening,setOpening]=useState(false),[error,setError]=useState(''),[attempt,setAttempt]=useState(0);
 const root=useRef<HTMLDivElement>(null),request=useRef(0),alive=useRef(false),restored=useRef(false),pending=useRef(false),pages=useRef(0),returnPosition=useRef(memory.scrollTop||0);
 const current=useRef({query,identity});current.current={query,identity};
 const openingRef=useRef(false);
 const read=useCallback(async(cursor?:string)=>{
  if(!supported||pending.current)return;
  const owner=++request.current,expected={...current.current},epoch=bridge.getDocumentEpoch();
  pending.current=true;setLoading(true);setError('');
  const accepts=()=>alive.current&&owner===request.current&&expected.query===current.current.query&&expected.identity===current.current.identity&&epoch===bridge.getDocumentEpoch()&&!document.hidden;
  try{
   const count=cursor?1:Math.max(1,memory.pages||1);let nextCursor=cursor;
   for(let page=0;page<count;page++){
    const append=!!nextCursor;
    const next=readReply(await bridge.command('work.search',{query:expected.query.trim(),limit:30,...(nextCursor?{cursor:nextCursor}:{})}));
    if(!accepts())return;
    pages.current=append?pages.current+1:1;memory.pages=pages.current;
    setReply(previous=>append&&previous?{...next,items:[...new Map([...previous.items,...next.items].map(item=>[itemKey(item),item])).values()]}:next);
    nextCursor=next.nextCursor;if(!nextCursor)break;
   }
  }catch(failure){if(accepts())setError(failure instanceof Error?failure.message:'搜索暂不可用，请重试。')}
  finally{if(owner===request.current){pending.current=false;if(alive.current)setLoading(false)}}
 },[supported,memory]);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++;pending.current=false}},[]);
 useEffect(()=>{
  request.current++;pending.current=false;pages.current=0;restored.current=false;returnPosition.current=memory.scrollTop||0;setReply(null);setError('');setLoading(supported);
  const timer=window.setTimeout(()=>{void read()},query.trim()?220:0);
  return()=>window.clearTimeout(timer);
 },[query,identity,attempt,supported,read]);
 useEffect(()=>{
  const visibility=()=>{if(document.hidden){request.current++;pending.current=false;setLoading(false)}else setAttempt(value=>value+1)};
  document.addEventListener('visibilitychange',visibility);return()=>document.removeEventListener('visibilitychange',visibility);
 },[]);
 useEffect(()=>{
  if(!reply||loading||restored.current)return;
  const frame=requestAnimationFrame(()=>{const scroller=root.current?.querySelector('.afa-commandlist');if(scroller)scroller.scrollTop=returnPosition.current;restored.current=true;});
  return()=>cancelAnimationFrame(frame);
 },[reply,loading]);
 useEffect(()=>{
  const scroller=root.current?.querySelector('.afa-commandlist');const remember=()=>{if(scroller&&restored.current)memory.scrollTop=scroller.scrollTop};
  scroller?.addEventListener('scroll',remember,{passive:true});return()=>{remember();scroller?.removeEventListener('scroll',remember)};
 },[memory]);
 const changeQuery=(value:string)=>{memory.query=value;memory.selectedId=undefined;memory.scrollTop=0;memory.pages=1;setQuery(value)};
 const commands=useMemo<WorkCommand[]>(()=>(reply?.items||[]).map(item=>({id:itemKey(item),group:'',title:item.title||'未命名',icon:icons[item.kind],meta:[kinds[item.kind],item.status?labelStatus(item.status):'',item.updatedAt?new Date(item.updatedAt).toLocaleDateString('zh-CN',{month:'short',day:'numeric'}):''].filter(Boolean).join(' · '),description:item.excerpt,onSelect:()=>{if(openingRef.current)return;openingRef.current=true;setOpening(true);memory.selectedId=itemKey(item);void Promise.resolve().then(()=>onOpen(item)).catch(failure=>{if(alive.current)setError(failure instanceof Error?failure.message:'尚未打开这条结果，请重试。')}).finally(()=>{openingRef.current=false;if(alive.current)setOpening(false)})}})),[reply,onOpen,labelStatus,memory]);
 return <div className="pw-work-search" ref={root}>
  <CommandMenu commands={commands} query={query} onQueryChange={changeQuery} remote loading={loading||opening} closeOnSelect={false} onClose={onClose} selectedId={memory.selectedId}
   emptyMessage={reply?.nextCursor?'当前已读取的记录中还没有命中，可以继续查找更早的记录。':reply?.notices?.length?'已读取的记录中没有命中；部分记录尚未读全，请查看下方说明。':query.trim()?'没有找到匹配的工作、对话、成果或项目。试试目标中的关键词或文件名。':'还没有可显示的工作记录。'}
   status={!supported?<p className="pw-search-status" role="status">当前版本尚未提供工作搜索。</p>:opening?<p className="pw-search-status" role="status">正在打开原记录…</p>:error?<div className="pw-search-status" role="alert"><p>{error}</p><Button variant="secondary" onClick={()=>{if(reply?.nextCursor)void read(reply.nextCursor);else setAttempt(value=>value+1)}}>重新搜索</Button></div>:loading?<p className="pw-search-status" role="status">{reply?'正在读取更多结果…':'正在搜索保存的工作…'}</p>:undefined}
   footer={<><p>{query.trim()?'搜索保存的工作、对话内容、成果和项目':'最近保存的工作与成果'}</p>{reply?.nextCursor&&<Button variant="ghost" disabled={loading||opening} onClick={()=>void read(reply.nextCursor)}>{reply.items.length?'查看更多结果':'继续查找'}</Button>}{reply?.notices?.map((notice,index)=><p className="pw-search-notice" key={index}>{notice}</p>)}</>}/>
 </div>;
}
