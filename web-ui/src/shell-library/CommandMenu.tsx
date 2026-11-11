/* Affect command behavior + BeUI a866cb2 morphing-search geometry and active-result
 * layout. The host owns its existing drawer: no second portal or focus trap. */
import {useEffect,useId,useRef,useState,type ComponentType,type ReactNode} from 'react';
import {LayoutGroup,motion,useReducedMotion} from 'motion/react';
import { IconCommand, IconSearch } from '../icons';
import {EASE_OUT,SPRING_LAYOUT} from './beui-adapted/ease';
import './beui-adapted/interactions.css';
export type WorkCommand={id:string;group:string;title:string;description?:string;meta?:string;icon?:ComponentType<{className?:string;'aria-hidden'?:boolean|'true'|'false'}>;onSelect:()=>void};
type CommandMenuProps={commands:WorkCommand[];onClose?:()=>void;conversationOnly?:boolean;footer?:ReactNode;controls?:ReactNode;scopeKey?:string;query?:string;queryReadOnly?:boolean;onQueryChange?:(query:string)=>void;remote?:boolean;loading?:boolean;status?:ReactNode;emptyMessage?:string;closeOnSelect?:boolean;selectedId?:string;onSelectedId?:(id:string)=>void};
export function CommandMenu({commands,onClose,conversationOnly=false,footer,controls,scopeKey,query:controlledQuery,queryReadOnly=false,onQueryChange,remote=false,loading=false,status,emptyMessage,closeOnSelect=true,selectedId,onSelectedId}:CommandMenuProps){
 const commandId=useId();const[localQuery,setLocalQuery]=useState(''),[active,setActive]=useState(0);const reduce=useReducedMotion();
 const query=controlledQuery??localQuery;
 const input=useRef<HTMLInputElement>(null),restoredSelection=useRef<string|undefined>(undefined);
 const list=remote?commands:commands.filter(x=>[x.group,x.title,x.description||''].join(' ').toLocaleLowerCase().includes(query.toLocaleLowerCase()));
 const activeIndex=Math.min(active,Math.max(0,list.length-1));
 const activeId=list[activeIndex]?.id;
 const choose=(item:WorkCommand)=>{if(loading)return;onSelectedId?.(item.id);item.onSelect();if(closeOnSelect)onClose?.()};
 useEffect(()=>{setActive(0);restoredSelection.current=undefined},[scopeKey]);
 useEffect(()=>{if(!selectedId){restoredSelection.current=undefined;return}if(restoredSelection.current===selectedId)return;const next=list.findIndex(item=>item.id===selectedId);if(next>=0){restoredSelection.current=selectedId;setActive(next)}},[selectedId,commands]);
 // Appending a page must not move a manually scrolled list back to its active row.
 useEffect(()=>{document.getElementById(`${commandId}-${activeIndex}`)?.scrollIntoView({block:'nearest'});},[activeIndex,activeId,query,commandId]);
 return <LayoutGroup id={commandId}><motion.section className="afa-command afa-card pw-command pw-morph-search" layout={!reduce}
 initial={reduce?false:{clipPath:'inset(0 0 calc(100% - 64px) 0 round 20px)',opacity:0}}
 animate={{clipPath:'inset(0 0 0 0 round 20px)',opacity:1}} transition={reduce?{duration:0}:{layout:{type:'spring',duration:.58,bounce:.22},clipPath:{duration:.32,ease:EASE_OUT},opacity:{duration:.16}}}
 onKeyDown={e=>{
 if(e.nativeEvent.isComposing||e.nativeEvent.keyCode===229)return;
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();input.current?.focus()}
 if(e.key==='Escape'){e.stopPropagation();onClose?.()}
 if(e.target!==input.current)return;
 if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();setActive((activeIndex+(e.key==='ArrowDown'?1:-1)+Math.max(1,list.length))%Math.max(1,list.length));}
 if(e.key==='Enter'&&!loading&&list[activeIndex]){e.preventDefault();choose(list[activeIndex])}
 }}>
 <motion.div className="afa-commandinput" layout={!reduce}><IconSearch size={18}/><input ref={input} autoFocus readOnly={queryReadOnly} maxLength={remote?500:undefined} role="combobox" aria-label={remote?'搜索工作、对话、成果与项目':conversationOnly?'搜索已加载对话':'搜索已加载工作与功能'} aria-expanded="true" aria-controls={`${commandId}-commands`} aria-activedescendant={list.length?`${commandId}-${activeIndex}`:undefined} placeholder={remote?'搜索目标、内容或文件名…':conversationOnly?'搜索对话标题…':'搜索已加载的工作与功能…'} value={query} onChange={e=>{if(queryReadOnly)return;setLocalQuery(e.target.value);onQueryChange?.(e.target.value);setActive(0)}}/><kbd>↵</kbd></motion.div>
 {controls}
 {status}
 <motion.div className="afa-commandlist" id={`${commandId}-commands`} role="listbox" aria-label={conversationOnly?'对话':'工作与成果'} aria-busy={loading||undefined} layout={!reduce} initial={reduce?false:{opacity:0,y:6}} animate={{opacity:1,y:0}} transition={reduce?{duration:0}:{duration:.16,ease:EASE_OUT}}>
 {list.map((x,i)=>{const CommandIcon=x.icon||IconCommand;return <div key={x.id}>
 {x.group&&(i===0||list[i-1].group!==x.group)?<div className="afa-label">{x.group}</div>:null}
 <motion.button layout={reduce?false:'position'} transition={reduce?{duration:0}:SPRING_LAYOUT} type="button" id={`${commandId}-${i}`} role="option" tabIndex={-1} aria-selected={i===activeIndex} className={i===activeIndex?'afa-hover':''} onMouseEnter={()=>setActive(i)} onClick={()=>choose(x)}>
 {i===activeIndex&&<motion.span className="pw-morph-active" layoutId="active-result" transition={reduce?{duration:0}:SPRING_LAYOUT} aria-hidden/>}
 <CommandIcon className="pw-command-icon" aria-hidden/><span className="pw-command-copy"><span className="pw-command-title">{x.title}</span>{x.meta&&<span className="pw-command-meta">{x.meta}</span>}{x.description&&<span className="pw-command-description">{x.description}</span>}</span><span className="afa-push"/>
 </motion.button></div>})}
 {!list.length&&!loading&&!status&&<p className="afa-empty">{emptyMessage||(conversationOnly?'没有匹配的已加载对话。':'没有匹配的已加载工作或功能。')}</p>}
 </motion.div><footer onKeyDown={event=>event.stopPropagation()}>{footer??(conversationOnly?'仅搜索当前已加载的对话':'搜索当前已加载范围')}</footer>
 </motion.section></LayoutGroup>
}
