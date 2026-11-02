// Product content adapter: react-markdown 10.1.0 + remark-gfm 4.0.1 (MIT).
// Parsing is delegated to those established libraries. The small remark plugin only
// links citation markers in already-parsed text nodes to the provided source IDs.
import {useMemo,useState,type ReactNode} from 'react';
import ReactMarkdown,{defaultUrlTransform,type Components} from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {SourceReference} from './types';
import {Icon} from './core';
export function safeMarkdownUrl(raw:string):string {
 const safe=defaultUrlTransform(raw);if(!safe)return '';
 if(safe.startsWith('#'))return safe;
 try{const parsed=new URL(safe);return parsed.protocol==='https:'||parsed.protocol==='http:'?safe:''}catch{return ''}
}
function citationPlugin(sources:SourceReference[]){return ()=>{return(tree:any)=>{const visit=(node:any)=>{if(!Array.isArray(node.children)||['link','linkReference','code','inlineCode','html'].includes(node.type))return;const children:any[]=[];for(const child of node.children){if(child.type!=='text'){visit(child);children.push(child);continue}const value=String(child.value);const pattern=/\[(\d+)\]/g;let previous=0;let match:RegExpExecArray|null;while((match=pattern.exec(value))){const source=sources[Number(match[1])-1];if(!source)continue;if(match.index>previous)children.push({type:'text',value:value.slice(previous,match.index)});children.push({type:'link',url:`#pw-source-${encodeURIComponent(source.id)}`,children:[{type:'text',value:match[0]}]});previous=pattern.lastIndex}if(previous<value.length)children.push({type:'text',value:value.slice(previous)});else if(!previous)children.push(child)}node.children=children};visit(tree)}}}
function nodeText(node:any):string {if(typeof node?.value==='string')return node.value;return Array.isArray(node?.children)?node.children.map(nodeText).join(''):''}
function CodeBlock({children,text,onCopy}:{children:ReactNode;text:string;onCopy?:(text:string)=>void|Promise<unknown>}){
 const[status,setStatus]=useState('');const copy=async()=>{try{if(onCopy)await onCopy(text);else if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(text);else throw Error();setStatus('已复制')}catch{setStatus('复制未完成')}};
 return <div className="pw-markdown-code"><div className="pw-markdown-codebar"><span>代码</span><button type="button" onClick={()=>{void copy()}} aria-label={status||'复制代码'}><Icon name="copy" size={13}/>{status||'复制'}</button></div><pre tabIndex={0}>{children}</pre></div>
}
export type MarkdownBodyProps={text:string;className?:string;sources?:SourceReference[];onCitation?:(source:SourceReference)=>void;onOpenLink?:(url:string)=>void|Promise<unknown>;onCopyCode?:(text:string)=>void|Promise<unknown>;resolveImage?:(url:string)=>string|undefined};
export function MarkdownBody({text,className='',sources=[],onCitation,onOpenLink,onCopyCode,resolveImage}:MarkdownBodyProps){
 const signature=sources.map(x=>x.id).join('|');const citations=useMemo(()=>citationPlugin(sources),[signature]);
 const components:Components={
  a:({href,children})=>{if(!href)return <span>{children}</span>;if(href.startsWith('#pw-source-')){let id='';try{id=decodeURIComponent(href.slice('#pw-source-'.length))}catch{}const source=sources.find(x=>x.id===id);return source?<button type="button" className="cb-cite pw-markdown-cite" aria-label={`查看来源：${source.title||source.name||source.id}`} onClick={()=>onCitation?.(source)}>{children}</button>:<span>{children}</span>}const safe=safeMarkdownUrl(href);if(!safe)return <span>{children}</span>;return <a href={safe} rel="noopener noreferrer" target={safe.startsWith('#')?undefined:'_blank'} onClick={onOpenLink&&!safe.startsWith('#')?e=>{e.preventDefault();void onOpenLink(safe)}:undefined}>{children}</a>},
  pre:({children,node})=><CodeBlock text={nodeText(node)} onCopy={onCopyCode}>{children}</CodeBlock>,
  table:({children})=><div className="pw-markdown-table" tabIndex={0} role="region" aria-label="表格，可横向滚动"><table>{children}</table></div>,
  input:({checked})=><span className="pw-markdown-checkbox" role="img" aria-label={checked?'文档内已勾选':'文档内未勾选'}>{checked&&<Icon name="check" size={12}/>}</span>,
  img:({src,alt})=>{const raw=typeof src==='string'?src:'';const resolved=resolveImage?.(raw);if(resolved&&(resolved.startsWith('blob:')||resolved.startsWith('data:image/')||resolved.startsWith('https://pocketengine.local/')))return <img src={resolved} alt={alt||''} loading="lazy"/>;const safe=safeMarkdownUrl(raw);return <span className="pw-markdown-image">图片：{alt||'未命名'}{safe&&onOpenLink&&<button type="button" onClick={()=>{void onOpenLink(safe)}}>打开图片来源</button>}</span>}
 };
 return <div className={`pw-markdown ${className}`}><ReactMarkdown remarkPlugins={[remarkGfm,citations]} skipHtml urlTransform={safeMarkdownUrl} components={components}>{text}</ReactMarkdown></div>
}
