/* PW-21: adapted from library v3 Affect CommandMenu (not official Pro source).
 * Original inputs.tsx is retained. The real command list replaces fixture commands;
 * picking calls the supplied action, not a local demonstration success message.
 */
import {useEffect,useId,useRef,useState} from 'react';
import {RiSearchLine,RiCommandLine} from '@remixicon/react';
export type WorkCommand={id:string;group:string;title:string;description?:string;onSelect:()=>void};
export function CommandMenu({commands,onClose,conversationOnly=false}:{commands:WorkCommand[];onClose?:()=>void;conversationOnly?:boolean}){
 const commandId=useId();const[query,setQuery]=useState(''),[active,setActive]=useState(0);const input=useRef<HTMLInputElement>(null);
 const list=commands.filter(x=>[x.group,x.title,x.description||''].join(' ').toLocaleLowerCase().includes(query.toLocaleLowerCase()));
 const choose=(item:WorkCommand)=>{item.onSelect();onClose?.()};
 useEffect(()=>{document.getElementById(`${commandId}-${active}`)?.scrollIntoView({block:'nearest'});},[active,query,commandId]);
 return <section className="afa-command afa-card pw-command" onKeyDown={e=>{
  if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();input.current?.focus()}
  if(e.key==='Escape'){e.stopPropagation();onClose?.()}
  if(e.key==='Tab'&&list.length){e.preventDefault();const groups=Array.from(new Set(list.map(x=>x.group)));const group=list[active]?.group||groups[0];const at=(groups.indexOf(group)+(e.shiftKey?-1:1)+groups.length)%groups.length;setActive(list.findIndex(x=>x.group===groups[at]));}
  if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();setActive(x=>(x+(e.key==='ArrowDown'?1:-1)+Math.max(1,list.length))%Math.max(1,list.length));}
  if(e.key==='Enter'&&list[active]){e.preventDefault();choose(list[active])}
 }}><div className="afa-commandinput"><RiSearchLine size={18}/><input ref={input} autoFocus role="combobox" aria-label={conversationOnly?"搜索已加载对话":"搜索已加载工作与功能"} aria-expanded="true" aria-controls={`${commandId}-commands`} aria-activedescendant={list.length?`${commandId}-${active}`:undefined} placeholder={conversationOnly?"搜索对话标题…":"搜索已加载的工作与功能…"} value={query} onChange={e=>{setQuery(e.target.value);setActive(0)}}/><kbd>↵</kbd></div><div className="afa-commandlist" id={`${commandId}-commands`} role="listbox" aria-label={conversationOnly?"对话":"工作与功能"}>{list.map((x,i)=><div key={x.id}>{i===0||list[i-1].group!==x.group?<div className="afa-label">{x.group}</div>:null}<button type="button" id={`${commandId}-${i}`} role="option" aria-selected={i===active} className={i===active?'afa-hover':''} onMouseEnter={()=>setActive(i)} onClick={()=>choose(x)}><RiCommandLine size={17}/><span>{x.title}{x.description&&<small>{x.description}</small>}</span><span className="afa-push"/></button></div>)}{!list.length&&<p className="afa-empty">{conversationOnly?"没有匹配的已加载对话。":"没有匹配的已加载工作或功能。"}</p>}</div><footer>{conversationOnly?"仅搜索当前已加载的对话":"搜索当前已加载范围"}</footer></section>
}
