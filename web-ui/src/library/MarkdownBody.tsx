// Product content adapter: react-markdown 10.1.0 + remark-gfm 4.0.1 (MIT).
// Parsing is delegated to those established libraries. The small remark plugin only
// links citation markers in already-parsed text nodes to the provided source IDs.
import {createContext,memo,useContext,useEffect,useMemo,useState,type ContextType,type ReactNode} from 'react';
import { IconExpand } from '../icons';
import {ReadingContext} from '../reading-context';
import ReactMarkdown,{defaultUrlTransform,type Components} from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {SourceReference} from './types';
import {Icon} from './core';
export function safeMarkdownUrl(raw:string):string {
 const safe=defaultUrlTransform(raw);if(!safe)return '';
 if(safe.startsWith('#'))return safe;
 try{const parsed=new URL(safe);return parsed.protocol==='https:'||parsed.protocol==='http:'?safe:''}catch{return ''}
}
function citationPlugin(sourceIds:string[]){return ()=>{return(tree:any)=>{const visit=(node:any)=>{if(!Array.isArray(node.children)||['link','linkReference','code','inlineCode','html'].includes(node.type))return;const children:any[]=[];for(const child of node.children){if(child.type!=='text'){visit(child);children.push(child);continue}const value=String(child.value);const pattern=/\[(\d+)\]/g;let previous=0;let match:RegExpExecArray|null;while((match=pattern.exec(value))){const id=sourceIds[Number(match[1])-1];if(id===undefined)continue;if(match.index>previous)children.push({type:'text',value:value.slice(previous,match.index)});children.push({type:'link',url:`#pw-source-${encodeURIComponent(id)}`,children:[{type:'text',value:match[0]}]});previous=pattern.lastIndex}if(previous<value.length)children.push({type:'text',value:value.slice(previous)});else if(!previous)children.push(child)}node.children=children};visit(tree)}}}
function nodeText(node:any):string {if(typeof node?.value==='string')return node.value;return Array.isArray(node?.children)?node.children.map(nodeText).join(''):''}
function CodeBlock({children,text,onCopy}:{children:ReactNode;text:string;onCopy?:(text:string)=>void|Promise<unknown>}){
 const[status,setStatus]=useState('');useEffect(()=>setStatus(''),[text]);const copy=async()=>{try{if(onCopy)await onCopy(text);else if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(text);else throw Error();setStatus('已复制')}catch{setStatus('复制未完成')}};
 return <div className="pw-markdown-code"><div className="pw-markdown-codebar"><span>代码</span><button type="button" onClick={()=>{void copy()}} aria-label={status||'复制代码'}><Icon name="copy" size={13}/>{status||'复制'}</button></div><pre tabIndex={0}>{children}</pre></div>
}
function tableText(node:any):string {const rows:string[]=[];const visit=(entry:any)=>{if(entry?.tagName==='tr'){rows.push((entry.children||[]).filter((cell:any)=>cell.tagName==='th'||cell.tagName==='td').map(nodeText).join('\t'));return}(entry?.children||[]).forEach(visit)};visit(node);return rows.join('\n')}
function tableSelectionPlugin(offset:number|undefined){return ()=>{return(tree:any)=>{if(offset===undefined)return;let table:any;const visit=(node:any)=>{if(node?.tagName==='table'&&node.position?.start.offset===offset)table=node;else (node?.children||[]).forEach(visit)};visit(tree);tree.children=table?[table]:[]}}}
export type MarkdownBodyProps={text:string;className?:string;onlyTableAt?:number;sources?:SourceReference[];onCitation?:(source:SourceReference)=>void;onOpenLink?:(url:string)=>void|Promise<unknown>;onCopyCode?:(text:string)=>void|Promise<unknown>;resolveImage?:(url:string)=>string|undefined};
type MarkdownPresentation=Pick<MarkdownBodyProps,'text'|'onCitation'|'onOpenLink'|'onCopyCode'|'resolveImage'>&{sources:SourceReference[];openReading:ContextType<typeof ReadingContext>};
const MarkdownPresentationContext=createContext<MarkdownPresentation>({text:'',sources:[],openReading:null});
// Only document inputs cross this memo boundary. Fresh native snapshots and
// callback identities must not reparse every unchanged historical answer.
const ParsedMarkdown=memo(ReactMarkdown);
// Context consumers still receive current source metadata, callbacks and image
// resolvers even when ParsedMarkdown reuses its document tree. No comparator
// ignores an interaction prop, and the stable component types keep local state.
const components:Components={
  a:function MarkdownLink({href,children}){const {sources,onCitation,onOpenLink}=useContext(MarkdownPresentationContext);if(!href)return <span>{children}</span>;if(href.startsWith('#pw-source-')){let id='';try{id=decodeURIComponent(href.slice('#pw-source-'.length))}catch{}const source=sources.find(x=>x.id===id);return source?<button type="button" className="cb-cite pw-markdown-cite" aria-label={`查看来源：${source.title||source.name||source.id}`} onClick={()=>onCitation?.(source)}>{children}</button>:<span>{children}</span>}const safe=safeMarkdownUrl(href);if(!safe)return <span>{children}</span>;return <a href={safe} rel="noopener noreferrer" target={safe.startsWith('#')?undefined:'_blank'} onClick={onOpenLink&&!safe.startsWith('#')?e=>{e.preventDefault();void onOpenLink(safe)}:undefined}>{children}</a>},
  pre:function MarkdownCode({children,node}){const {onCopyCode}=useContext(MarkdownPresentationContext);const text=useMemo(()=>nodeText(node),[node]);return <CodeBlock text={text} onCopy={onCopyCode}>{children}</CodeBlock>},
  table:function MarkdownTable({children,node}){
   const {text,openReading}=useContext(MarkdownPresentationContext);const start=node?.position?.start.offset,end=node?.position?.end.offset;
   const tableSource=typeof start==='number'&&typeof end==='number'?text.slice(start>0?text.lastIndexOf('\n',start-1)+1:0,end):'';
   const content=<div className="pw-markdown-table" tabIndex={0} role="region" aria-label="表格，可横向滚动"><table>{children}</table></div>;
   return openReading&&tableSource?<section className="pw-markdown-table-section"><header><span>表格</span><button type="button" className="pw-markdown-table-open" aria-label="展开表格" onClick={event=>openReading({kind:'table',title:'表格',text:tableSource,copyText:tableText(node),tableDocument:{markdown:text,offset:start!},returnFocus:event.currentTarget})}><IconExpand size={16} aria-hidden="true"/>展开</button></header>{content}</section>:content;
  },
  input:({checked})=><span className="pw-markdown-checkbox" role="img" aria-label={checked?'文档内已勾选':'文档内未勾选'}>{checked&&<Icon name="check" size={12}/>}</span>,
  img:function MarkdownImage({src,alt}){const {resolveImage,onOpenLink}=useContext(MarkdownPresentationContext);const raw=typeof src==='string'?src:'';const resolved=resolveImage?.(raw);if(resolved&&(resolved.startsWith('blob:')||resolved.startsWith('data:image/')||resolved.startsWith('https://pocketengine.local/')))return <img src={resolved} alt={alt||''} loading="lazy"/>;const safe=safeMarkdownUrl(raw);return <span className="pw-markdown-image">图片：{alt||'未命名'}{safe&&onOpenLink&&<button type="button" onClick={()=>{void onOpenLink(safe)}}>打开图片来源</button>}</span>}
};
export function MarkdownBody({text,className='',onlyTableAt,sources=[],onCitation,onOpenLink,onCopyCode,resolveImage}:MarkdownBodyProps){
 const signature=JSON.stringify(sources.map(source=>source.id));
 const citations=useMemo(()=>citationPlugin(JSON.parse(signature) as string[]),[signature]);
 const remarkPlugins=useMemo(()=>[remarkGfm,citations],[citations]);
 const rehypePlugins=useMemo(()=>onlyTableAt===undefined?[]:[tableSelectionPlugin(onlyTableAt)],[onlyTableAt]);
 const openReading=useContext(ReadingContext);
 const presentation:MarkdownPresentation={text,sources,onCitation,onOpenLink,onCopyCode,resolveImage,openReading};
 return <div className={`pw-markdown ${className}`}><MarkdownPresentationContext.Provider value={presentation}><ParsedMarkdown remarkPlugins={remarkPlugins} rehypePlugins={rehypePlugins} skipHtml urlTransform={safeMarkdownUrl} components={components}>{text}</ParsedMarkdown></MarkdownPresentationContext.Provider></div>
}

