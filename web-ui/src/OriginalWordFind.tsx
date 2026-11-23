import {useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {Accordion,AccordionItem,Button,TextField} from './forms-library';
import {bridge} from './bridge';
import {fileObject as obj,fileRows as rows,type FileJson as Json} from './attachment-workspace';

export type OriginalWordHit={unit:number;chunk:number;offset:number;kind:'text'|'table_cell';
 before:string;after:string;table?:string;row?:string;column?:string};
type Scope={windowId:string;viewerId:string;identity:string;sha256:string;epoch:number};
type Input={query:string};
type Found={scope:Scope;input:Input;hits:OriginalWordHit[];more:boolean};
type Reveal={found:Found;hit:OriginalWordHit;pane:HTMLElement|null;interaction:number;focus:Element|null;returnRevision:number};

const coordinate=(value:unknown):value is string=>typeof value==='string'&&/^[1-9]\d{0,6}$/.test(value);
const ordinal=(value:unknown):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0&&value<=1_000_000;
const digest=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const wholeNumber=(value:unknown)=>typeof value==='string'&&/^\d{1,8}$/.test(value)?Number(value):-1;
function isWord(value:Json,scope:Scope){
 return value.format==='docx'&&value.status==='partial'&&value.coverage==='body_paragraphs_and_tables'&&
  value.sourceIdentity===scope.identity&&value.sha256===scope.sha256&&ordinal(value.totalUnits);
}
function liveWord(scope:Scope):Json|undefined{
 const view=obj(bridge.getState().state.attachmentWorkspace),file=obj(view.originalFile),info=obj(file.information);
 if(!document.hidden&&bridge.getDocumentEpoch()===scope.epoch&&view.open===true&&view.ready===true&&view.windowId===scope.windowId&&
  file.kind==='office'&&file.sourceUnavailable===false&&file.id===scope.viewerId&&file.identity===scope.identity&&file.sha256===scope.sha256&&
  isWord(info,scope))return info;
}
function originalWordScope(source:Json,windowId:string):Scope|undefined{
 const {id,identity,sha256}=source;
 if(typeof id!=='string'||!/^[a-f0-9-]{36}$/.test(id)||!digest(identity)||!digest(sha256))return;
 const scope={windowId,viewerId:id,identity,sha256,epoch:bridge.getDocumentEpoch()};
 return liveWord(scope)?scope:undefined;
}
function parsedMatches(value:Json,scope:Scope,input:Input,total:number):Found|undefined{
 if(value.format!=='pocketworld.original-word-find.v1'||value.coverage!=='body_paragraphs_and_tables'||value.caseSensitive!==true||
  value.sourceIdentity!==scope.identity||value.sha256!==scope.sha256||value.query!==input.query||value.totalUnits!==total||
  typeof value.hasMoreMatches!=='boolean'||!Array.isArray(value.matches)||value.matches.length>40||
  value.hasMoreMatches&&value.matches.length!==40)return;
 const hits:OriginalWordHit[]=[];
 for(const raw of value.matches){
  const {unit,chunk,offset,kind,before,after,table,row,column}=obj(raw),last=hits.at(-1);
  if(!ordinal(unit)||unit<1||unit>total||!ordinal(chunk)||typeof offset!=='number'||!Number.isSafeInteger(offset)||
     offset<0||offset>8*1024*1024||last&&(unit<last.unit||unit===last.unit&&offset<last.offset+input.query.length)||
     typeof before!=='string'||before.length>32||before.length>offset||typeof after!=='string'||after.length>32||
     kind!=='text'&&kind!=='table_cell')return;
  if(kind==='table_cell'){
   if(!coordinate(table)||!coordinate(row)||!coordinate(column))return;
   hits.push({unit,chunk,offset,kind,before,after,table,row,column});
  }else hits.push({unit,chunk,offset,kind,before,after});
 }
 return {scope,input,hits,more:value.hasMoreMatches};
}
export function originalWordHitBlock(block:Json,hit:OriginalWordHit){
 const locator=obj(block.locator);
 return block.unit===hit.unit&&block.chunk===hit.chunk&&block.kind===hit.kind&&locator.paragraph===String(hit.unit)&&
  (hit.kind==='text'||locator.table===hit.table&&locator.row===hit.row&&locator.column===hit.column);
}
function confirmedRange(value:Json,found:Found,hit:OriginalWordHit){
 if(!isWord(value,found.scope)||value.unit!==hit.unit||value.chunk!==hit.chunk)return false;
 const [first,next]=rows(value.chunks);
 if(!first||!originalWordHitBlock(first,hit)||typeof first.text!=='string')return false;
 const a=obj(first.locator),start=wholeNumber(a.charStart),end=wholeNumber(a.charEnd),{query}=found.input;
 if(start<0||end-start!==first.text.length||hit.offset<start||hit.offset>=end)return false;
 let text=first.text.slice(hit.offset-start,hit.offset-start+query.length);
 if(text.length<query.length){
  const b=obj(next?.locator),tail=wholeNumber(b.charEnd);
  if(!next||!originalWordHitBlock(next,{...hit,chunk:hit.chunk+1})||a.part!==b.part||
   wholeNumber(b.charStart)!==end||typeof next.text!=='string'||tail-end!==next.text.length)return false;
  text+=next.text.slice(0,query.length-text.length);
 }
 return text===query;
}

// Copy only the original match's single painted paragraph chunk, never search snippets or unseen continuation.
function workPassage(value:Json,native:Json,selected:Reveal):string|undefined{
 const {found,hit}=selected;
 if(hit.kind!=='text'||!confirmedRange(value,found,hit)||!confirmedRange(native,found,hit))return;
 const first=obj(rows(value.chunks)[0]),proof=obj(rows(native.chunks)[0]),at=obj(first.locator),source=obj(proof.locator);
 return typeof first.text==='string'&&first.text.trim()&&first.text.length<=8000&&first.text===proof.text&&
  at.part===source.part&&at.charStart===source.charStart&&at.charEnd===source.charEnd&&
  hit.offset+found.input.query.length<=wholeNumber(at.charEnd)?first.text:undefined;
}

export function OriginalWordFind({source,windowId,value,busy,find,read,onExcerpt,onSavedExcerpt,mayCopyWorkPassage,beforeFindJump,returnRevision=0,children}:{
 source:Json;windowId:string;value:Json;busy:boolean;
 find:(query:string)=>Promise<Json|null>;read:(unit:number,chunk:number)=>Promise<Json|null>;
 onExcerpt?:(viewerId:string,unit:number,chunk:number)=>void;onSavedExcerpt?:()=>void;mayCopyWorkPassage?:()=>boolean;
 beforeFindJump?:(unit:number,chunk:number)=>(()=>void)|undefined;returnRevision?:number;
 children:(match?:OriginalWordHit,action?:ReactNode)=>ReactNode;
}){
 const input=useRef<Input>({query:''}),entered=useRef(false),mounted=useRef(false),interaction=useRef(0);
 const body=useRef<HTMLDivElement>(null),focusNext=useRef<Reveal|undefined>(undefined),paintedCopy=useRef<Reveal|undefined>(undefined),copying=useRef(false);
 const [query,setQuery]=useState(''),[found,setFound]=useState<Found>(),[reveal,setReveal]=useState<Reveal>();
 const [pending,setPending]=useState(false),[error,setError]=useState('');
 const [copyFeedback,setCopyFeedback]=useState<{selection:Reveal;message:string}>();
 const captured=originalWordScope(source,windowId),visible=captured&&isWord(value,captured);
 const current=(result:Found)=>mounted.current&&input.current===result.input&&!!liveWord(result.scope);
 const shown=found&&current(found)?found:undefined;
 const selected=reveal&&reveal.returnRevision===returnRevision&&current(reveal.found)&&
  confirmedRange(value,reveal.found,reveal.hit)?reveal:undefined;
 const observed=selected&&mayCopyWorkPassage?.()?liveWord(selected.found.scope):undefined;
 const copyText=selected&&observed?workPassage(value,observed,selected):undefined;
 useLayoutEffect(()=>{
  paintedCopy.current=copyText?selected:undefined;
  if(!copyText)setCopyFeedback(undefined);
 },[selected,copyText]);
 const copyWorkPassage=async(target:HTMLButtonElement)=>{
  if(!selected||!copyText||busy||pending||copying.current)return;
  const currentText=()=>{
   if(paintedCopy.current!==selected||!current(selected.found)||!mayCopyWorkPassage?.()||
      !target.isConnected||target.closest('[hidden],[inert],[aria-hidden="true"]'))return;
   const anchors=body.current?.querySelectorAll<HTMLElement>('[data-original-word-find="true"]');
   const match=anchors?.length===1?anchors[0]:undefined;
   const prose=match?.matches('.pw-file-text')?match:match?.querySelector<HTMLElement>(':scope > .pw-file-text');
   if(!match?.contains(target)||!prose||!prose.getClientRects().length||prose.textContent!==copyText)return;
   const native=liveWord(selected.found.scope);
   return native&&workPassage(value,native,selected)===copyText?copyText:undefined;
  };
  const text=currentText();if(!text)return;
  copying.current=true;setCopyFeedback(undefined);
  try{
   if(!navigator.clipboard?.writeText)throw new Error('clipboard unavailable');
   await navigator.clipboard.writeText(text);
   if(currentText())setCopyFeedback({selection:selected,message:'已复制当前这段 Word 文字。'});
  }catch{if(currentText())setCopyFeedback({selection:selected,message:'没有复制成功，原件未改动。'});}
  finally{copying.current=false;}
 };
 useLayoutEffect(()=>{
  mounted.current=true;
  const pane=body.current?.closest<HTMLElement>('.pw-file-page-scroll'),note=()=>{interaction.current++};
  const events=['pointerdown','wheel','touchmove','keydown'];
  for(const event of events)pane?.addEventListener(event,note,{capture:true,passive:true});
  const hidden=()=>{if(document.hidden){input.current={query:input.current.query};focusNext.current=undefined;setFound(undefined);setReveal(undefined)}};
  document.addEventListener('visibilitychange',hidden);
  return()=>{mounted.current=false;document.removeEventListener('visibilitychange',hidden);for(const event of events)pane?.removeEventListener(event,note,true)};
 },[]);
 useLayoutEffect(()=>{
  const action=focusNext.current;
  if(!action||!selected||action!==selected)return;
  const {pane}=action,info=liveWord(action.found.scope),active=document.activeElement;
  if(!info||!confirmedRange(info,action.found,action.hit))return;
  focusNext.current=undefined;
  const anchor=body.current?.querySelector<HTMLElement>('[data-original-word-find="true"]');
  if(!pane?.isConnected||!anchor?.isConnected||anchor.closest('.pw-file-page-scroll')!==pane||
   anchor.closest('[hidden],[inert],[aria-hidden="true"]')||interaction.current!==action.interaction||
   (active!==action.focus&&active!==document.body))return;
  pane.scrollTop+=anchor.getBoundingClientRect().top-pane.getBoundingClientRect().top;
  const table=anchor.closest<HTMLElement>('.pw-file-table');
  if(table){const cell=anchor.getBoundingClientRect(),viewport=table.getBoundingClientRect();
   if(cell.left<viewport.left||cell.right>viewport.right)table.scrollLeft+=cell.left-viewport.left;}
  anchor.focus({preventScroll:true});
 },[selected,source.information]);
 const start=async()=>{
  const scope=originalWordScope(source,windowId),pressed=input.current;
  if(!scope||!isWord(value,scope)||!pressed.query||busy||entered.current)return;
  entered.current=true;setPending(true);setError('');setFound(undefined);setReveal(undefined);focusNext.current=undefined;
  try{
   const result=await find(pressed.query);
   if(!mounted.current||input.current!==pressed||!liveWord(scope))return;
   const checked=result&&parsedMatches(result,scope,pressed,value.totalUnits);
   if(checked)setFound(checked);
   else setError('未核对到这次 Word 查找结果。原文未变化。');
  }finally{entered.current=false;if(mounted.current)setPending(false)}
 };
 const open=async(hit:OriginalWordHit)=>{
  if(!shown||!current(shown)||!shown.hits.includes(hit)||busy||entered.current)return;
  const action={found:shown,hit,pane:body.current?.closest<HTMLElement>('.pw-file-page-scroll')??null,
   interaction:interaction.current,focus:document.activeElement,returnRevision};
  const onLanded=beforeFindJump?.(hit.unit,hit.chunk);
  entered.current=true;setPending(true);setError('');setReveal(undefined);focusNext.current=undefined;
  try{
   const result=await read(hit.unit,hit.chunk);
   if(!current(shown))return;
   if(!result||!confirmedRange(result,shown,hit)){setError('未核对到这条匹配的原段落，没有跳到其他位置。');return}
   onLanded?.();focusNext.current=action;setReveal(action);
  }finally{entered.current=false;if(mounted.current)setPending(false)}
 };
 return <>
  {visible&&<Accordion className="pw-original-find"><AccordionItem id="original-word-find" title="查找 Word 正文"
   description={shown?`当前查找：${shown.input.query}`:undefined}>
   <p className="pw-muted">只查找内置阅读可读取的正文和表格文字，不含页眉、脚注或图片。</p>
   <div className="pw-file-search"><TextField label="查找文字（区分大小写）" value={query} onChange={text=>{
    const next=text.slice(0,256);input.current={query:next};setQuery(next);setFound(undefined);setReveal(undefined);focusNext.current=undefined;setError('');
   }}/>
   <Button variant="secondary" disabled={!query||busy||pending} onClick={()=>void start()}>查找</Button></div>
   {error&&<p role="status">{error}</p>}
   {shown&&(!shown.hits.length?<p role="status">内置可读的正文和表格中未找到匹配。</p>:<>
    <div className="pw-outcome-files" aria-label="当前 Word 匹配">{shown.hits.map(hit=>
     <Button key={`${hit.unit}:${hit.offset}`} variant="ghost" disabled={busy||pending} onClick={()=>void open(hit)}>
      <span><bdi>{hit.before}<strong>{shown.input.query}</strong>{hit.after}</bdi>
       <small>{hit.kind==='table_cell'?`表格 ${hit.table} · 第 ${hit.row} 行第 ${hit.column} 列`:`段落 ${hit.unit}`} · 位置 {hit.offset+1}</small></span>
     </Button>)}</div>
    {shown.more&&<p className="pw-muted">只显示前 40 处匹配。可用更具体的文字重新查找。</p>}
   </>)}
  </AccordionItem></Accordion>}
  <div ref={body} data-word-reading-body tabIndex={-1}>{children(selected?.hit,selected&&(copyText?
   <><Button variant="ghost" disabled={busy||pending} onClick={event=>void copyWorkPassage(event.currentTarget)}>复制这段 Word 文字</Button>
    {copyFeedback&&copyFeedback.selection===selected&&<p role="status">{copyFeedback.message}</p>}</>:onSavedExcerpt?
   <Button variant="ghost" disabled={busy||pending} onClick={()=>{if(current(selected.found))onSavedExcerpt()}}>打开已保存的摘录</Button>:
   onExcerpt&&<Button variant="ghost" disabled={busy||pending} onClick={()=>{
    const page=liveWord(selected.found.scope);
    if(page&&current(selected.found)&&confirmedRange(page,selected.found,selected.hit))
     onExcerpt(selected.found.scope.viewerId,selected.hit.unit,selected.hit.chunk);
   }}>核对这段摘录</Button>))}</div>
 </>;
}
