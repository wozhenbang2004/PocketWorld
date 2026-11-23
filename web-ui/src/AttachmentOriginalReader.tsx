import {useEffect,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {Accordion,AccordionItem,Button,IconButton,RadioOptions,SelectField,TextField} from './forms-library';
import {IconChevronLeft,IconChevronRight,IconExternalLink} from './icons';
import {MarkdownBody} from './library';
import {bridge} from './bridge';
import {documentTable,fileObject as obj,fileRows as rows,type FileJson as Json} from './attachment-workspace';
import {OriginalMediaPlayer} from './workspace-library/OriginalMediaPlayer';
import {OriginalWordFind,originalWordHitBlock,type OriginalWordHit} from './OriginalWordFind';
import {useOriginalFindDetour} from './OriginalFindDetour';

export function FileDetails({value,title='来源详情'}:{value:unknown;title?:string}) {
 return value == null ? null : <details className="pw-file-details"><summary>{title}</summary><pre>{typeof value==='string'?value:JSON.stringify(value,null,2)}</pre></details>;
}
type WordFragment={kind:'paragraph';block:Json}|{kind:'table';tableId:string;blocks:Json[]};
type OriginalWordHeading={unit:number;level:number;text:string};
function confirmedWordHeadings(value:Json):OriginalWordHeading[]{
 const headings=rows(value.wordHeadings),total=value.totalUnits;
 if(value.format!=='docx'||!['ok','partial'].includes(value.status)||!Number.isSafeInteger(total)||total<1||
    !Number.isSafeInteger(value.unit)||value.unit<1||value.unit>total||!Number.isSafeInteger(value.chunk)||value.chunk<0||
    !headings.length||headings.length>64||headings.length>total||
    headings.some(({unit,level,text},index)=>!Number.isSafeInteger(unit)||unit<1||unit>Math.min(total,1000000)||
     index>0&&unit<=headings[index-1].unit||!Number.isSafeInteger(level)||level<1||level>9||
     typeof text!=='string'||!text.trim()||text.length>128||/[\u0000-\u001f\u007f-\u009f\u2028-\u202e\u2066-\u2069]/u.test(text)))return [];
 return headings.map(({unit,level,text})=>({unit,level,text}));
}
function wordFragments(chunks:Json[]):WordFragment[]{
 const fragments:WordFragment[]=[];
 const coordinate=(value:unknown)=>typeof value==='string'&&/^[1-9]\d{0,6}$/.test(value);
 for(const block of chunks){
  const at=obj(block.locator);
  if(block.kind!=='table_cell'||typeof at.part!=='string'||!at.part||!coordinate(at.table)||!coordinate(at.row)||!coordinate(at.column)){
   fragments.push({kind:'paragraph',block});continue;
  }
  // Word units are paragraph ordinals, not table IDs. A nested table or prose ends this fragment.
  const tableId=JSON.stringify([at.part,at.table]),previous=fragments.at(-1);
  if(previous?.kind==='table'&&previous.tableId===tableId)previous.blocks.push(block);
  else fragments.push({kind:'table',tableId,blocks:[block]});
 }
 return fragments;
}
export function DocumentProjection({value,orderedOriginalWord=false,pdfPage,wordMatch,wordExcerptAction}:{
 value:Json;orderedOriginalWord?:boolean;pdfPage?:number;wordMatch?:OriginalWordHit;wordExcerptAction?:ReactNode;
}) {
 const chunks=rows(value.chunks),tables=new Map<string,Json[]>(),paragraphs:Json[]=[];
 const orderedWord=orderedOriginalWord&&value.format==='docx'&&['ok','partial'].includes(value.status);
 const wordHeadings=new Map((orderedWord?confirmedWordHeadings(value):[]).map(heading=>[heading.unit,heading] as const));
 if(!orderedWord)for(const block of chunks){const at=obj(block.locator);if(at.cell||at.table){const key=`${at.sheet||''}:${at.table||''}:${block.unit}`;tables.set(key,[...(tables.get(key)||[]),block])}else paragraphs.push(block)}
 const paragraph=(block:Json,key:string)=>{
  const heading=wordHeadings.get(block.unit);
  const selected=orderedWord&&wordMatch&&originalWordHitBlock(block,wordMatch)?{'data-original-word-find':'true',tabIndex:-1}:undefined;
  if(heading&&block.kind==='text'&&block.chunk===0&&block.text===heading.text){
   const title=<h4 className="pw-file-text"><bdi>{block.text}</bdi></h4>;
   return selected&&wordExcerptAction?<div key={key} {...selected}>{title}{wordExcerptAction}</div>:
    <h4 key={key} className="pw-file-text" {...selected}><bdi>{block.text}</bdi></h4>;
  }
  return <div key={key} {...selected}>{!pdfPage&&<small className="pw-muted">{orderedWord?'段落':value.format==='pptx'?'幻灯片':'单元'} {block.unit}</small>}<p className="pw-file-text">{block.text}</p>
   {selected&&wordExcerptAction}</div>;
 };
 const tableFragment=(blocks:Json[],key:string)=>{
  const table=documentTable(blocks),caption=orderedWord?'原表格片段 · 当前读取范围':obj(blocks[0]?.locator).sheet||'原文档表格 · 当前范围';
  const selected=orderedWord&&wordMatch&&blocks.some(block=>originalWordHitBlock(block,wordMatch))?wordMatch:undefined;
  return <div className="pw-file-table" key={key}><table><caption>{caption}</caption><thead><tr><th>行</th>{table.columns.map(col=><th key={col}>{col}</th>)}</tr></thead><tbody>{table.rows.map(row=><tr key={row}><th>{row}</th>{table.columns.map(col=>{
   const isMatch=!!selected&&selected.row===row&&selected.column===col;
   return <td key={col} data-original-word-find={isMatch?'true':undefined} tabIndex={isMatch?-1:undefined}>{table.text(row,col)}
    {isMatch&&wordExcerptAction}</td>;
  })}</tr>)}</tbody></table></div>;
 };
 return <section className="pw-file-projection" aria-label="当前读取范围"><p className="pw-muted">{pdfPage?
  value.truncated===true?'只显示本页已读到的文字。':'仅显示该页的本机文本层，未做图片识别。':
  <>{value.coverage||'这里只显示当前已读取的范围。'} {value.status&&`读取状态：${value.status}`}</>}</p>
 {!chunks.length&&<p>{pdfPage?'未读到本页文字；页面仍可在原 PDF 中查看。':'当前范围没有可显示的文字，不代表原文件为空。'}</p>}
 {orderedWord?wordFragments(chunks).map((fragment,i)=>fragment.kind==='table'?
  tableFragment(fragment.blocks,`word:table:${fragment.tableId}:${i}`):
  paragraph(fragment.block,`word:paragraph:${fragment.block.unit}:${fragment.block.chunk}:${i}`)):<>
  {paragraphs.map((block,i)=>paragraph(block,`${block.unit}:${block.chunk}:${i}`))}
  {[...tables].map(([key,blocks])=>tableFragment(blocks,key))}
 </>}
 {value.format==='xlsx'&&<p className="pw-muted">公式显示原文件保存的值或公式文字，本次没有重新计算。</p>}
 {Array.isArray(value.warnings)&&value.warnings.length>0&&<details><summary>格式与读取说明</summary>{value.warnings.map((text:string,i:number)=><p key={i}>{text}</p>)}</details>}
 </section>;
}

const textEncodings=['UTF-8','UTF-16LE','UTF-16BE','GB18030','ISO-8859-1'].map(id=>({id,label:id}));
export type DisplayedOriginalText={windowId:string;viewerId:string;identity:string;sha256:string;encoding:string;offset:number};
type OriginalReaderProps={source:Json;windowId:string;showTitle?:boolean;findBeforeLongText?:boolean;onTextDisplayed?:(range:DisplayedOriginalText)=>void;
 onReadPdfPageText?:(viewerId:string,rasterId:string)=>void;showingPdfPageText?:boolean;
 onReadWordExcerpt?:(viewerId:string,unit:number,chunk:number)=>void;onOpenSavedWordExcerpt?:()=>void;
 showingWordExcerpt?:boolean;
 recovery?:{canSave:boolean;busy:boolean;onOpenInReader?:()=>void}};
type PdfRaster={pdfRasterId:string;page:number;pages:number;searchAvailable:boolean};
type PdfFindPin={windowId:string;viewerId:string;identity:string;sha256:string;url:string};
type PdfFindReceipt={value:Json;epoch:number;pin?:PdfFindPin};
type PdfFindInput={text:string;receipt?:PdfFindReceipt};
type TextFindScope=Omit<DisplayedOriginalText,'offset'>;
type TextFindHit={offset:number;before:string;after:string};
type TextFindReceipt={value:Json;epoch:number;scope:TextFindScope;matches:TextFindHit[]};
type TextFindInput={text:string;receipt?:TextFindReceipt};
type PdfMatchTarget={page:number;input:PdfFindInput;receipt:PdfFindReceipt;interaction:number;focus:Element|null;onLanded?:()=>void};
type PdfPageStart={kind:'pageStart';page:number;pin:PdfFindPin;epoch:number;interaction:number;focus:Element|null;body:HTMLDivElement;top:number;left:number};
type PdfReveal=PdfPageStart|PdfMatchTarget;
type PdfHitBox=readonly [number,number,number,number];
type PdfMark={raster:PdfRaster;receipt:PdfFindReceipt;hits:PdfHitBox[][];index:number};
type TextAdvance={range:DisplayedOriginalText;epoch:number;interaction:number;focus:Element|null;pane:HTMLElement;findCurrent?:()=>boolean};
type WordPoint={unit:number;chunk:number};
type WordAdvance={windowId:string;viewerId:string;identity:string;sha256:string;epoch:number;before:WordPoint;next:WordPoint;
 pane:HTMLElement;start:HTMLElement;focus:Element|null;top:number;left:number;anchor:string;anchorPriority:string;request:number;confirmed:boolean};
const wordPoint=(unit:number,chunk:number):WordPoint|undefined=>Number.isSafeInteger(unit)&&unit>0&&
 Number.isSafeInteger(chunk)&&chunk>=0?{unit,chunk}:undefined;
const wordRangeIs=(value:Json,owner:WordAdvance,point:WordPoint)=>value.format==='docx'&&['ok','partial'].includes(value.status)&&
 value.sourceIdentity===owner.identity&&value.sha256===owner.sha256&&value.unit===point.unit&&value.chunk===point.chunk&&
 Number.isSafeInteger(value.totalUnits)&&point.unit<=value.totalUnits&&Array.isArray(value.chunks)&&value.chunks.length>0;
function currentWordReader(owner:WordAdvance):Json|undefined {
 const drawer=obj(bridge.getState().state.attachmentWorkspace),source=obj(drawer.originalFile);
 if(!document.hidden&&bridge.getDocumentEpoch()===owner.epoch&&drawer.open===true&&drawer.ready===true&&drawer.windowId===owner.windowId&&
    source.kind==='office'&&source.sourceUnavailable===false&&source.id===owner.viewerId&&source.identity===owner.identity&&source.sha256===owner.sha256)return source;
}
function wordViewportUnchanged(owner:WordAdvance){
 // A shorter successful range can clamp the old scroll offset. It isn't a new user scroll.
 const pane=owner.pane,top=Math.min(owner.top,Math.max(0,pane.scrollHeight-pane.clientHeight));
 return Math.abs(pane.scrollTop-top)<=1&&Math.abs(pane.scrollLeft-owner.left)<=1;
}
function textRangeMatches(value:Json,range:DisplayedOriginalText){
 return value.sourceIdentity===range.identity&&value.sha256===range.sha256&&value.encoding===range.encoding&&
  value.offset===range.offset&&typeof value.content==='string'&&!!value.content;
}
function currentTextRange(range:DisplayedOriginalText):Json {
 const {state}=bridge.getState(),view=obj(state.attachmentWorkspace),original=obj(view.originalFile);
 return state.chatAttachmentPickerOpen===false&&view.open===true&&view.ready===true&&view.windowId===range.windowId&&
  view.group===false&&view.team===false&&view.choosingSource===false&&view.deviceOriginalOnly===false&&
  view.historyResultOnly===false&&view.reportResultOnly===false&&
  typeof range.identity==='string'&&/^[a-f0-9]{64}$/.test(range.identity)&&typeof range.sha256==='string'&&/^[a-f0-9]{64}$/.test(range.sha256)&&
  view.canSaveOriginal===true&&view.file?.sha256===range.sha256&&original.kind==='text'&&original.id===range.viewerId&&
  original.identity===range.identity&&original.sha256===range.sha256&&original.sourceUnavailable===false&&original.busy===false?
  obj(original.information):{};
}
function currentTextFindSource(scope:TextFindScope){
 const view=obj(bridge.getState().state.attachmentWorkspace),file=obj(view.originalFile),info=obj(file.information);
 return view.open===true&&view.ready===true&&view.windowId===scope.windowId&&file.kind==='text'&&
  file.id===scope.viewerId&&file.identity===scope.identity&&file.sha256===scope.sha256&&file.sourceUnavailable===false&&
  info.sourceIdentity===scope.identity&&info.sha256===scope.sha256&&info.encoding===scope.encoding;
}
function captureTextFindScope(windowId:string,source:Json,encoding:unknown):TextFindScope|undefined{
 const {id,identity,sha256}=source;
 if(typeof id!=='string'||!/^[a-f0-9-]{36}$/.test(id)||typeof identity!=='string'||!/^[a-f0-9]{64}$/.test(identity)||
    typeof sha256!=='string'||!/^[a-f0-9]{64}$/.test(sha256)||typeof encoding!=='string'||!textEncodings.some(row=>row.id===encoding))return;
 const scope={windowId,viewerId:id,identity,sha256,encoding};
 if(currentTextFindSource(scope))return scope;
}
function verifiedTextFind(value:Json,scope:TextFindScope,query:string,start:number):TextFindHit[]|undefined{
 const {scannedUntil:through,nextOffset:next,atEnd}=value;
 if(value.sourceIdentity!==scope.identity||value.sha256!==scope.sha256||value.encoding!==scope.encoding||
    value.query!==query||value.startOffset!==start||!Number.isSafeInteger(through)||through<start||through>20*1024*1024||
    typeof atEnd!=='boolean'||(atEnd?next!==null:!Number.isSafeInteger(next)||next<start||next>through)||
    value.caseSensitive!==true||value.overlapping!==false||!Array.isArray(value.matches)||value.matches.length>40)return;
 const matches:TextFindHit[]=[];
 const context=(text:unknown,available:number)=>typeof text==='string'&&text.length<=32&&text.length<=available?text:'';
 let first=start;
 for(const raw of value.matches){
  const {offset,length,before,after}=obj(raw);
  if(!Number.isSafeInteger(offset)||offset<first||length!==query.length||offset+length>through)return;
  first=offset+length;
  matches.push({offset,before:context(before,offset-start),after:context(after,through-first)});
 }
 return matches;
}
function verifiedPdfFind(value:Json,sha:string,query:string,first:number,pages:number){
 const through=value.scannedThrough;
 return value.textLayerOnly===true&&value.sha256===sha&&value.query===query&&value.firstPage===first&&
  Number.isSafeInteger(through)&&through>=first-1&&through<=Math.min(pages,first+19)&&
  value.nextPage===(through===pages?null:through+1)&&Array.isArray(value.matches)&&value.matches.length<=100&&
  value.matches.every(hit=>Number.isSafeInteger(hit?.page)&&hit.page>=first&&hit.page<=through);
}
function matchesPdfFindPin(pin:PdfFindPin,windowId:string,source:Json){
 return windowId===pin.windowId&&source.kind==='pdf'&&source.sourceUnavailable===false&&source.id===pin.viewerId&&
  source.identity===pin.identity&&source.sha256===pin.sha256&&source.url===pin.url;
}
function capturePdfFindPin(windowId:string,source:Json):PdfFindPin|undefined{
 const {id,identity,sha256,url}=source,view=obj(bridge.getState().state.attachmentWorkspace);
 if(typeof id!=='string'||!/^[a-f0-9-]{36}$/.test(id)||typeof identity!=='string'||!/^[a-f0-9]{64}$/.test(identity)||
    typeof sha256!=='string'||!/^[a-f0-9]{64}$/.test(sha256)||url!==`https://pocketengine.local/pocket-files/${id}/content`||
    view.open!==true||view.ready!==true)return;
 const pin={windowId,viewerId:id,identity,sha256,url};
 if(matchesPdfFindPin(pin,windowId,source)&&matchesPdfFindPin(pin,view.windowId,obj(view.originalFile)))return pin;
}
function isPdfHitBox(box:unknown):box is PdfHitBox {
 return Array.isArray(box)&&box.length===4&&box.every(value=>Number.isFinite(value)&&value>=0&&value<=1)&&
  box[0]<box[2]&&box[1]<box[3];
}
function pdfHitPositions(value:Json,page:number):PdfHitBox[][]{
 // This is a local count of distinguishable positions, not a complete match count.
 const hits:PdfHitBox[][]=[],starts=new Set<number>(),anchors=new Set<string>();
 for(const hit of rows(value.matches)){
  if(hit.page!==page||!Number.isSafeInteger(hit.textStart)||hit.textStart<0||starts.has(hit.textStart)||
     !Array.isArray(hit.boxes)||!hit.boxes.length||hit.boxes.length>16||!hit.boxes.every(isPdfHitBox))continue;
  const anchor=JSON.stringify(hit.boxes[0]);
  if(anchors.has(anchor))continue;
  starts.add(hit.textStart);anchors.add(anchor);hits.push(hit.boxes);
 }
 return hits;
}
function displayedPdfImage(body:HTMLElement,raster:PdfRaster,pin:PdfFindPin){
 const layer=body.querySelector<HTMLElement>(`[data-pdf-raster="${raster.pdfRasterId}"]`),image=layer?.firstElementChild;
 if(!layer||!(image instanceof HTMLImageElement)||!image.complete||!image.naturalWidth||!image.naturalHeight||
    image.currentSrc!==`${pin.url}?page=${raster.page}&raster=${raster.pdfRasterId}`)return;
 const imageStyle=getComputedStyle(image),layerStyle=getComputedStyle(layer);
 const unturned=(style:CSSStyleDeclaration)=>style.transform==='none'&&(!style.rotate||style.rotate==='none');
 if(!unturned(imageStyle)||!unturned(layerStyle)||imageStyle.objectFit!=='fill')return;
 const rect=image.getBoundingClientRect(),plane=layer.getBoundingClientRect();
 // The full native page and its search boxes have the same axes. Never map onto a cropped,
 // stretched or transformed image, or the padded region that contains it.
 if(rect.width>0&&rect.height>0&&rect.left===plane.left&&rect.top===plane.top&&rect.width===plane.width&&rect.height===plane.height&&
    Math.abs(rect.height-rect.width*image.naturalHeight/image.naturalWidth)<=1&&body.clientWidth>0&&body.clientHeight>0)return rect;
}
function pdfRaster(value:Json):PdfRaster|null {
 const {pdfRasterId,page,pages,searchAvailable}=value;
 return typeof pdfRasterId==='string'&&/^[a-f0-9-]{36}$/.test(pdfRasterId)&&
  Number.isSafeInteger(page)&&Number.isSafeInteger(pages)&&page>=1&&page<=pages&&typeof searchAvailable==='boolean'
  ?{pdfRasterId,page,pages,searchAvailable}:null;
}
function isDisplayedPdfRaster(value:Json,raster:PdfRaster){
 return value.pdfRasterId===raster.pdfRasterId&&value.page===raster.page&&value.pages===raster.pages&&value.searchAvailable===true;
}
const officePreviewIssue:Record<string,string>={
 unsupported:'这个文档格式没有内置预览。', encrypted:'这份文档受加密保护，无法在这里预览。',
 invalid:'未能读出这份文档的结构。', limit_exceeded:'这份文档超出了内置预览的读取范围。', unavailable:'这份文档暂时不能在这里预览。',
};
function previousWorksheetRange(value:Json){
 const previous=obj(value.previousWorksheetRange),first=rows(value.chunks)[0];
 if(value.format!=='xlsx'||!['ok','partial'].includes(value.status)||!Number.isSafeInteger(value.totalUnits)||
    !Number.isSafeInteger(value.unit)||value.unit<1||value.unit>value.totalUnits||
    !Number.isSafeInteger(value.chunk)||first?.unit!==value.unit||first.chunk!==value.chunk||previous.unit!==value.unit||
    !Number.isSafeInteger(previous.chunk)||previous.chunk<0||previous.chunk>=value.chunk)return;
 return {unit:previous.unit,chunk:previous.chunk};
}
function worksheetChoices(value:Json):{id:string;unit:number;label:string;description?:string}[]{
 const total=value.totalUnits,sheets=rows(value.worksheets);
 if(value.format!=='xlsx'||!['ok','partial'].includes(value.status)||!Number.isSafeInteger(total)||total<2||total>64||
    !Number.isSafeInteger(value.unit)||value.unit<1||value.unit>total||!Number.isSafeInteger(value.chunk)||value.chunk<0||
    sheets.length<2||sheets.length>total||
    sheets.some(({unit,name},index)=>!Number.isSafeInteger(unit)||unit<1||unit>total||index>0&&unit<=sheets[index-1].unit||
     typeof name!=='string'||!name.trim()||name.length>128||/[\u0000-\u001f\u007f-\u009f\u2028-\u202e\u2066-\u2069]/u.test(name)))return [];
 const counts=new Map<string,number>();
 for(const {name} of sheets){const key=name.trim().toLowerCase();counts.set(key,(counts.get(key)||0)+1)}
 return sheets.map(({unit,name})=>(counts.get(name.trim().toLowerCase())||0)>1
  ?{id:String(unit),unit,label:`${name} · 第 ${unit} 张`}
  :{id:String(unit),unit,label:name,description:`第 ${unit} 张`});
}

export function AttachmentOriginalReader(props:OriginalReaderProps) {
 return <OriginalReader key={JSON.stringify([props.windowId,props.source.id])} {...props}/>;
}

function OriginalReader({source,windowId,showTitle=true,recovery,findBeforeLongText,onTextDisplayed,onReadPdfPageText,showingPdfPageText,
 onReadWordExcerpt,onOpenSavedWordExcerpt,showingWordExcerpt}:OriginalReaderProps) {
 const readerSurface=useRef<HTMLElement>(null),textAdvance=useRef<TextAdvance|null>(null);
 const wordAdvance=useRef<WordAdvance|null>(null);
 const [wordAdvanceRevision,setWordAdvanceRevision]=useState(0);
 const textBody=useRef<HTMLDivElement>(null),pdfBody=useRef<HTMLDivElement>(null),pdfView=useRef<HTMLDivElement>(null);
 const [toolsVisible,setToolsVisible]=useState(false),[wordReturnRevision,setWordReturnRevision]=useState(0);
 const [value,setValue]=useState<Json>(obj(source.information)),[error,setError]=useState(''),[busy,setBusy]=useState(false),[query,setQuery]=useState(''),[found,setFound]=useState<Json|null>(null),[zoom,setZoom]=useState(1),[turn,setTurn]=useState(0),[position,setPosition]=useState('1'),[markdown,setMarkdown]=useState(String(source.name||'').toLowerCase().endsWith('.md'));
 const [recoveryEncoding,setRecoveryEncoding]=useState('');
 const [failedRead,setFailedRead]=useState<{command:string;args:Json;pdfFind?:PdfFindInput;textFind?:TextFindInput}|null>(null);
 const pdfInput=useRef<PdfFindInput>({text:''});
 const textFindInput=useRef<TextFindInput>({text:''});
 const [pdfShown,setPdfShown]=useState<PdfRaster|null>(null),[pdfAttempt,setPdfAttempt]=useState<PdfRaster|null>(null);
 const [pdfMark,setPdfMark]=useState<PdfMark|null>(null);
 const pdfPending=useRef<{raster:PdfRaster;acknowledging:boolean;target?:PdfReveal}|null>(null);
 const pdfReveal=useRef<{raster:PdfRaster;target:PdfReveal}|null>(null),readerInteraction=useRef(0);
 const pdfTextReturn=useRef<{pin:PdfFindPin;raster:PdfRaster;epoch:number;pane:HTMLDivElement;top:number;left:number;opened:boolean}|null>(null);
 const retired=source.sourceUnavailable===true;
 const live=useRef({id:source.id,windowId,retired}),sequence=useRef(0),pending=useRef(false);live.current={id:source.id,windowId,retired};
 const type=String(source.kind),details=obj(source.information);
 const trackTextAdvance=type==='text'&&!!onTextDisplayed;
 const dropWordAdvance=()=>{
  const pending=wordAdvance.current;if(!pending)return;
  wordAdvance.current=null;
  if(pending.pane.style.getPropertyValue('overflow-anchor')==='none'&&!pending.pane.style.getPropertyPriority('overflow-anchor')){
   if(pending.anchor)pending.pane.style.setProperty('overflow-anchor',pending.anchor,pending.anchorPriority);
   else pending.pane.style.removeProperty('overflow-anchor');
  }
 };
 const retirePdfMatch=()=>{
  if(pdfPending.current)pdfPending.current.target=undefined;
  pdfReveal.current=null;setPdfMark(null);
 };
 const retirePdfInput=(text:string)=>{
  retirePdfMatch();
  pdfInput.current.receipt=undefined;
  pdfInput.current={text}; // Editing away and back is still a new, unsubmitted search.
 };
 const retireTextFind=(text:string)=>{
  textFindInput.current.receipt=undefined;
  textFindInput.current={text};
 };
 const changeQuery=(text:string)=>{
  const next=text.slice(0,256);
  if(type==='pdf')retirePdfInput(next);
  if(type==='text')retireTextFind(next);
  setQuery(next);setFound(null);
 };
 const clearPdfAttempt=()=>{pdfPending.current=null;setPdfAttempt(null)};
 const currentRaster=(raster:PdfRaster)=>!live.current.retired&&document.visibilityState!=='hidden'&&pdfPending.current?.raster===raster;
 const ownsPdfPageStart=(target:PdfPageStart)=>{
  const pin=capturePdfFindPin(windowId,source),active=document.activeElement;
  return !live.current.retired&&!document.hidden&&bridge.getDocumentEpoch()===target.epoch&&readerInteraction.current===target.interaction&&
   (active===target.focus||active===document.body)&&target.body===pdfBody.current&&target.body.isConnected&&!target.body.hidden&&
   pin&&matchesPdfFindPin(target.pin,pin.windowId,source);
 };
 const failRaster=(raster:PdfRaster)=>{
  if(!currentRaster(raster))return;
  clearPdfAttempt();setFailedRead({command:'read',args:{page:raster.page}});
  setError(`原 PDF 第 ${raster.page} 页未能显示。`);
 };
 const confirmRaster=async(raster:PdfRaster)=>{
  const attempt=pdfPending.current;
  if(!currentRaster(raster)||!attempt||attempt.acknowledging)return;attempt.acknowledging=true;
  try {
   const shown=pdfRaster(obj(await bridge.command<Json>('attachmentWorkspace.viewer',{windowId,viewerId:source.id,command:'check',displayedPdfRaster:raster.pdfRasterId})));
   if(!currentRaster(raster))return;
   if(shown?.pdfRasterId!==raster.pdfRasterId||shown.page!==raster.page){failRaster(raster);return}
   const target=attempt.target;
   // Check the old viewport before replacing the raster; the new image's height can clamp its offset.
   pdfReveal.current=target&&(!('kind' in target)||ownsPdfPageStart(target)&&
    target.body.scrollTop===target.top&&target.body.scrollLeft===target.left)?{raster,target}:null;
   setPdfShown(raster);clearPdfAttempt();
  } catch {failRaster(raster)}
 };
 const invoke=async(command:string,args:Json={},target?:PdfReveal,advance?:TextAdvance)=>{
  if(pending.current||pdfPending.current||live.current.retired)return null;
  const pdfFind=command==='find'&&type==='pdf'?pdfInput.current:undefined;
  const textFind=command==='find'&&type==='text'?textFindInput.current:undefined;
  const start=args.cursor,pdfPages=pdfShown?.pages??0,sha=source.sha256,epoch=bridge.getDocumentEpoch();
  if(pdfFind&&(!pdfShown?.searchAvailable||!pdfFind.text||args.query!==pdfFind.text||
      !Number.isSafeInteger(start)||start<1||start>pdfPages||typeof sha!=='string'||!/^[a-f0-9]{64}$/.test(sha)||
      (start!==1&&(pdfFind.receipt?.epoch!==epoch||pdfFind.receipt.value.nextPage!==start))))return null;
  const textScope=textFind?captureTextFindScope(windowId,source,args.encoding):undefined;
  if(textFind&&(!textScope||!textFind.text||args.query!==textFind.text||!Number.isSafeInteger(start)||start<0||
     (start!==0&&(textFind.receipt?.epoch!==epoch||textFind.receipt.value.nextOffset!==start||
      textFind.receipt.scope.encoding!==textScope.encoding))))return null;
  const pdfFindPin=pdfFind?capturePdfFindPin(windowId,source):undefined;
  if(type==='pdf')retirePdfMatch();
  const owner={...live.current},request=++sequence.current;pending.current=true;textAdvance.current=null;setBusy(true);setFailedRead(null);setError('');
  try{
   const result=await bridge.command<Json>('attachmentWorkspace.viewer',{windowId:owner.windowId,viewerId:owner.id,command,...args});
   if(request!==sequence.current||live.current.id!==owner.id||live.current.windowId!==owner.windowId||live.current.retired||document.visibilityState==='hidden')return null;
   if((pdfFind||textFind)&&bridge.getDocumentEpoch()!==epoch)return null;
   if(command==='find'){
    if(pdfFind){
     if(pdfInput.current!==pdfFind)return null;
     if(!verifiedPdfFind(obj(result),sha,pdfFind.text,start,pdfPages)){
      pdfFind.receipt=undefined;setFound(null);return null;
     }
     pdfFind.receipt={value:result,epoch,pin:pdfFindPin};
    }else if(textFind&&textScope){
     if(textFindInput.current!==textFind||!currentTextFindSource(textScope))return null;
     const matches=verifiedTextFind(obj(result),textScope,textFind.text,start);
     if(!matches){textFind.receipt=undefined;setFound(null);return null}
     textFind.receipt={value:result,epoch,scope:textScope,matches};
    }
    setFound(result);
   }
   else if(type==='pdf'){
    const raster=pdfRaster(obj(result));
    if(!raster||target&&raster.page!==target.page)throw new Error('这次 PDF 页面显示未确认');
    pdfPending.current={raster,acknowledging:false,target};setPdfAttempt(raster);
   }else{
    if(advance&&(!advance.findCurrent||advance.findCurrent())&&advance.epoch===bridge.getDocumentEpoch()&&advance.interaction===readerInteraction.current&&
       textRangeMatches(obj(result),advance.range))textAdvance.current=advance;
    setValue(result);
   }
   return result;
  }
  catch(cause){if(request===sequence.current&&!live.current.retired&&(!pdfFind&&!textFind||bridge.getDocumentEpoch()===epoch)){
   setFailedRead({command,args,pdfFind,textFind});setError(cause instanceof Error?cause.message:'原件读取未完成')
  }return null}
  finally{if(request===sequence.current){pending.current=false;setBusy(false)}}
 };
 useEffect(()=>{setValue(obj(source.information));setError('');setFound(null);setZoom(1);setTurn(0);pending.current=false;void invoke('load');return()=>{sequence.current++;pending.current=false;pdfPending.current=null}},[source.id,windowId]);
 useEffect(()=>{const hide=()=>{if(document.visibilityState==='hidden'){sequence.current++;pending.current=false;textAdvance.current=null;clearPdfAttempt();setBusy(false);if(type==='pdf')retirePdfInput(pdfInput.current.text);if(type==='text')retireTextFind(textFindInput.current.text);setFound(null)}};document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide)},[]);
 useEffect(()=>{
  if(type!=='pdf')return;
  let epoch=bridge.getDocumentEpoch();
  return bridge.subscribeDocumentEpoch(()=>{const next=bridge.getDocumentEpoch();if(next!==epoch){epoch=next;retirePdfMatch()}});
 },[type]);
 useLayoutEffect(()=>{
  const pane=trackTextAdvance?readerSurface.current?.closest<HTMLElement>('.pw-file-page-scroll'):null;
  if(!pane)return;
  const markInteraction=()=>{readerInteraction.current++;textAdvance.current=null};
  const events=['pointerdown','wheel','touchmove','keydown'];
  events.forEach(event=>pane.addEventListener(event,markInteraction,{capture:true,passive:true}));
  return()=>events.forEach(event=>pane.removeEventListener(event,markInteraction,true));
 },[trackTextAdvance]);
 useEffect(()=>{if(type!=='pdf'&&!retired&&Object.keys(details).length)setValue(details)},[source.information,retired]);
 useEffect(()=>{if(retired){sequence.current++;pending.current=false;clearPdfAttempt();setPdfShown(null);setBusy(false);setValue({});changeQuery('');setFailedRead(null)}},[retired]);
 const nativeError=typeof source.error==='string'?source.error:'';
 const previousPdfFindFailed=!!failedRead?.pdfFind&&failedRead.pdfFind!==pdfInput.current;
 const previousTextFindFailed=!!failedRead?.textFind&&failedRead.textFind!==textFindInput.current;
 const failed=retired?'原来源已失效，请返回后重新打开。':previousPdfFindFailed?'上一次 PDF 查找未完成；当前关键词尚未查找。':
  previousTextFindFailed?'上一次文字查找未完成；当前关键词尚未查找。':nativeError||error;
 const priorRange=type==='text'&&typeof details.content==='string'||type==='office'&&['ok','partial'].includes(details.status)&&
  Array.isArray(details.chunks)&&Number.isSafeInteger(details.unit)&&details.unit>=1&&details.unit<=details.totalUnits;
 // A client-only error cannot authorize displaying old content. Keep the node mounted while the native verdict settles.
 const retaining=!retired&&source.sourceUnavailable===false&&!!nativeError&&(!error||error===nativeError)&&
  (type==='pdf'?!!pdfShown&&details.pdfRasterId===pdfShown.pdfRasterId:priorRange);
 const actual:Json=retired?{}:type==='pdf'?pdfShown??{}:retaining?details:value;
 const officeIssue=type==='office'&&actual.status?(Object.hasOwn(officePreviewIssue,String(actual.status))?officePreviewIssue[String(actual.status)]:!['docx','xlsx','pptx'].includes(actual.format)?'未读到支持的 Office 文档结构。':''):'';
 const unavailable=actual.previewAvailable===false?'这个格式没有内置预览。':officeIssue;
 const ready=(!failed||retaining)&&!unavailable&&type!=='unsupported'&&Object.keys(actual).length>0,page=Number(actual.page||1),pages=Number(actual.pages||0);
 const readerBusy=busy||source.busy||pdfAttempt!==null;
 const readingWord=type==='office'&&actual.format==='docx';
 const findDetour=useOriginalFindDetour({root:readerSurface,windowId,source,actual,
  ready:ready&&!readerBusy&&!failed,enabled:!showingPdfPageText&&!showingWordExcerpt&&!(type==='text'&&markdown),failed:!!failed,
  read:point=>invoke('read',point.kind==='pdf'?{page:point.page}:point.kind==='text'?{encoding:point.encoding,offset:point.offset}:
   {unit:point.unit,chunk:point.chunk}),beforeReturn:()=>{
    if(type==='pdf')retirePdfMatch();
    if(type==='office')setWordReturnRevision(revision=>revision+1);
   }});
 const mayReadPdfText=!!onReadPdfPageText&&type==='pdf'&&ready&&!failed&&!readerBusy&&!showingPdfPageText&&
  !!pdfShown&&details.pdfRasterId===pdfShown.pdfRasterId&&details.page===pdfShown.page;
 const readPdfPageText=()=>{
  const raster=pdfShown,pane=pdfBody.current,pin=capturePdfFindPin(windowId,source);
  const img=raster&&pane?.querySelector<HTMLImageElement>(`[data-pdf-raster="${raster.pdfRasterId}"] img`);
  if(!mayReadPdfText||!pane?.isConnected||!raster||!pin||!img?.complete||img.naturalWidth<=0||document.hidden)return;
  pdfTextReturn.current={pin,raster,epoch:bridge.getDocumentEpoch(),pane,top:pane.scrollTop,left:pane.scrollLeft,opened:false};
  onReadPdfPageText?.(pin.viewerId,raster.pdfRasterId);
 };
 useLayoutEffect(()=>{
  const from=pdfTextReturn.current;
  if(!from)return;
  if(showingPdfPageText){from.opened=true;return}
  if(!from.opened)return;
  pdfTextReturn.current=null;
  if(!document.hidden&&bridge.getDocumentEpoch()===from.epoch&&pdfShown===from.raster&&pdfBody.current===from.pane&&
    from.pane.isConnected&&matchesPdfFindPin(from.pin,windowId,source)){
   from.pane.scrollTop=from.top;from.pane.scrollLeft=from.left;
  }
 },[showingPdfPageText,pdfShown,windowId,source.id,source.identity,source.sha256]);
 const previousWorksheet=ready&&type==='office'&&source.sourceUnavailable===false?previousWorksheetRange(actual):undefined;
 const worksheets=ready&&type==='office'&&source.sourceUnavailable===false&&actual.format==='xlsx'&&actual.totalUnits===details.totalUnits
  ?worksheetChoices(details):[];
 const wordHeadings=ready&&type==='office'&&source.sourceUnavailable===false&&actual.format==='docx'&&actual.totalUnits===details.totalUnits
  ?confirmedWordHeadings(details):[];
 const canChooseOfficeUnit=!readerBusy&&actual.unit===details.unit&&actual.chunk===details.chunk;
 const shownText=ready&&!readerBusy&&!failed&&type==='text'&&source.sourceUnavailable===false&&typeof actual.content==='string'&&!!actual.content&&
  textEncodings.some(row=>row.id===actual.encoding)&&Number.isSafeInteger(actual.offset)&&actual.offset>=0;
 const textRange={windowId,viewerId:source.id,identity:source.identity,sha256:source.sha256,encoding:actual.encoding,offset:actual.offset};
 const previousText=trackTextAdvance&&ready&&!markdown&&source.sourceUnavailable===false&&
  Number.isSafeInteger(actual.offset)&&Number.isSafeInteger(actual.previousOffset)&&actual.previousOffset>=0&&actual.previousOffset<actual.offset&&
  details.previousOffset===actual.previousOffset&&textRangeMatches(actual,textRange)&&textRangeMatches(details,textRange)?
  Number(actual.previousOffset):undefined;
 useLayoutEffect(()=>{
  if(!onTextDisplayed||!shownText||!textBody.current?.isConnected)return;
  onTextDisplayed({windowId,viewerId:source.id,identity:source.identity,sha256:source.sha256,encoding:actual.encoding,offset:actual.offset});
 },[onTextDisplayed,shownText,windowId,source.id,source.identity,source.sha256,actual.encoding,actual.offset]);
 useLayoutEffect(()=>{
  const advance=textAdvance.current,active=document.activeElement;
  if(!advance)return;
  if(!trackTextAdvance||retired||document.hidden||advance.findCurrent&&!advance.findCurrent()||bridge.getDocumentEpoch()!==advance.epoch||
     advance.interaction!==readerInteraction.current||(active!==advance.focus&&active!==document.body)){
   textAdvance.current=null;return;
  }
  if(!shownText||!textRangeMatches(actual,advance.range)||!textRangeMatches(currentTextRange(advance.range),advance.range))return;
  textAdvance.current=null;
  const start=textBody.current?.querySelector<HTMLElement>(':scope > .pw-file-text');
  if(!start?.isConnected||start.closest('.pw-file-page-scroll')!==advance.pane)return;
  // Only the deliberate in-place advance moves this viewport; reopen still uses the existing resume position.
  advance.pane.scrollTop+=start.getBoundingClientRect().top-advance.pane.getBoundingClientRect().top;
  start.focus({preventScroll:true});
 },[actual,source.information,retired,shownText,trackTextAdvance]);
 useLayoutEffect(()=>{
  if(!readingWord)return;
  const pane=readerSurface.current?.closest<HTMLElement>('.pw-file-page-scroll');if(!pane)return;
  const surface=pane.closest('.pw-file-workspace')??pane;
  const steer=(event:Event)=>{if(event.isTrusted)dropWordAdvance()};
  const moved=()=>{const advance=wordAdvance.current;if(advance?.pane===pane&&!wordViewportUnchanged(advance))dropWordAdvance()};
  const hide=()=>{if(document.hidden)dropWordAdvance()};
  const events=['pointerdown','click','input','wheel','touchmove','keydown'];
  events.forEach(event=>surface.addEventListener(event,steer,{capture:true,passive:true}));
  pane.addEventListener('scroll',moved,{passive:true});document.addEventListener('visibilitychange',hide);
  const off=bridge.subscribeDocumentEpoch(dropWordAdvance);
  return()=>{events.forEach(event=>surface.removeEventListener(event,steer,true));pane.removeEventListener('scroll',moved);
   document.removeEventListener('visibilitychange',hide);off();dropWordAdvance()};
 },[readingWord,source.id,windowId]);
 useLayoutEffect(()=>{
  const advance=wordAdvance.current;if(!advance?.confirmed)return;
  const owned=currentWordReader(advance),start=advance.start,active=document.activeElement;
  if(!owned||owned.error!=null||retired||failed||showingWordExcerpt||sequence.current!==advance.request||source.id!==advance.viewerId||
     (active!==advance.focus&&active!==document.body)||!start.isConnected||!readerSurface.current?.contains(start)||
     start.closest('.pw-file-page-scroll')!==advance.pane||start.closest('[hidden],[inert],[aria-hidden="true"]')||
     !wordViewportUnchanged(advance)){dropWordAdvance();return;}
  if(readerBusy||owned.busy!==false)return;
  const observed=obj(owned.information);
  if(!wordRangeIs(observed,advance,advance.next)||!wordRangeIs(actual,advance,advance.next)){
   const follows=(value:Json)=>wordRangeIs(value,advance,advance.next)||wordRangeIs(value,advance,advance.before)&&
    value.nextUnit===advance.next.unit&&value.nextChunk===advance.next.chunk;
   if(!follows(observed)||!follows(actual))dropWordAdvance();
   return;
  }
  if(!ready||!start.getClientRects().length){dropWordAdvance();return;}
  try{
   advance.pane.scrollTop+=start.getBoundingClientRect().top-advance.pane.getBoundingClientRect().top-advance.pane.clientTop;
   start.focus({preventScroll:true});
  }finally{dropWordAdvance()}
 },[actual,source.information,wordAdvanceRevision,ready,readerBusy,failed,retired,showingWordExcerpt]);
 const imageTools=(type==='pdf'||type==='image')&&ready;
 const isMarkdown=String(source.name||'').toLowerCase().endsWith('.md');
 const read=(args:Json)=>{findDetour.clear();void invoke('read',args)};
 const readWordNext=()=>{
  if(pending.current||readerBusy)return;
  const args={unit:actual.nextUnit,chunk:actual.nextChunk};
  const before=wordPoint(actual.unit,actual.chunk),next=wordPoint(args.unit,args.chunk);
  const start=readerSurface.current?.querySelector<HTMLElement>('[data-word-reading-body]');
  const pane=start?.closest<HTMLElement>('.pw-file-page-scroll');
  if(!before||!next||!start||!pane||!readingWord||!ready||failed||!start.isConnected||document.hidden||
     !/^[a-f0-9-]{36}$/.test(windowId)||typeof source.id!=='string'||!/^[a-f0-9-]{36}$/.test(source.id)||
     typeof source.identity!=='string'||!/^[a-f0-9]{64}$/.test(source.identity)||typeof source.sha256!=='string'||!/^[a-f0-9]{64}$/.test(source.sha256)||
     next.unit>actual.totalUnits||next.unit<before.unit||next.unit===before.unit&&next.chunk<=before.chunk){read(args);return;}
  dropWordAdvance();
  const advance:WordAdvance={windowId,viewerId:source.id,identity:source.identity,sha256:source.sha256,epoch:bridge.getDocumentEpoch(),
   before,next,pane,start,focus:document.activeElement,top:pane.scrollTop,left:pane.scrollLeft,
   anchor:pane.style.getPropertyValue('overflow-anchor'),anchorPriority:pane.style.getPropertyPriority('overflow-anchor'),request:0,confirmed:false};
  const owner=currentWordReader(advance),observed=obj(owner?.information);
  if(owner?.busy!==false||owner?.error!=null||!wordRangeIs(actual,advance,before)||!wordRangeIs(observed,advance,before)||
     observed.nextUnit!==next.unit||observed.nextChunk!==next.chunk){read(args);return;}
  // Keep the old scroller position across the exact replacement, not across any other action.
  pane.style.setProperty('overflow-anchor','none');wordAdvance.current=advance;
  findDetour.clear();const reading=invoke('read',args);advance.request=sequence.current;
  void reading.then(receipt=>{
   if(wordAdvance.current!==advance)return;
   if(sequence.current!==advance.request||!currentWordReader(advance)||!wordRangeIs(obj(receipt),advance,next)||!wordViewportUnchanged(advance)){
    dropWordAdvance();return;
   }
   advance.confirmed=true;setWordAdvanceRevision(revision=>revision+1);
  });
 };
 const readPdfPage=(page:number)=>{
  findDetour.clear();
  const body=pdfBody.current,pin=body&&pdfShown&&page!==pdfShown.page?capturePdfFindPin(windowId,source):undefined;
  const target:PdfPageStart|undefined=pin&&body?{kind:'pageStart',page,pin,epoch:bridge.getDocumentEpoch(),
   interaction:readerInteraction.current,focus:document.activeElement,body,top:body.scrollTop,left:body.scrollLeft}:undefined;
  void invoke('read',{page},target);
 };
 const readTextSegment=(direction:'previous'|'next')=>{
  const previous=direction==='previous',offset=previous?previousText:actual.nextOffset;
  const args={encoding:textRange.encoding,offset};
  const pane=textBody.current?.closest<HTMLElement>('.pw-file-page-scroll');
  const native=trackTextAdvance&&shownText&&pane&&!document.hidden?currentTextRange(textRange):{};
  const advance=pane&&Number.isSafeInteger(offset)&&(previous?offset>=0&&offset<textRange.offset:offset>textRange.offset)&&
   native[previous?'previousOffset':'nextOffset']===offset&&textRangeMatches(native,textRange)?
   {range:{...textRange,offset},epoch:bridge.getDocumentEpoch(),interaction:readerInteraction.current,focus:document.activeElement,pane}:undefined;
  if(previous&&!advance)return;
  findDetour.clear();
  void invoke('read',args,undefined,advance);
 };
 const retry=()=>{
  if(failedRead?.pdfFind&&failedRead.pdfFind!==pdfInput.current||failedRead?.textFind&&failedRead.textFind!==textFindInput.current)return;
  if(failedRead?.command!=='find')findDetour.clear();
  void invoke(failedRead?.command||'load',failedRead?.args);
 };
 const encodingChoice=recoveryEncoding||String(actual.encoding||'UTF-8');
 const systemReaderAction=(label:string,variant:'secondary'|'ghost')=>!retired&&recovery?.onOpenInReader&&
  <Button variant={variant} leadingIcon={IconExternalLink} disabled={recovery.busy||readerBusy} onClick={recovery.onOpenInReader}>{label}</Button>;
 // Keep the already existing disclosure above a long plain-text original, not above other files or scopes.
 // The native decoded length also keeps this placement on the final, potentially short range.
 const findBeforeBody=findBeforeLongText&&ready&&type==='text'&&source.sourceUnavailable===false&&
  actual.sourceIdentity===source.identity&&actual.sha256===source.sha256&&typeof actual.content==='string'&&!!actual.content&&
  (actual.atEnd===false&&Number.isSafeInteger(actual.nextOffset)&&actual.nextOffset>actual.offset||
   actual.atEnd===true&&Number.isSafeInteger(actual.decodedCharacters)&&actual.decodedCharacters>=12_000);
 const paintedPdfInput=pdfInput.current,paintedPdfReceipt=paintedPdfInput.receipt;
 const paintedTextInput=textFindInput.current,paintedTextReceipt=paintedTextInput.receipt;
 const ownsPaintedTextFind=()=>!!paintedTextReceipt&&textFindInput.current===paintedTextInput&&
  paintedTextInput.receipt===paintedTextReceipt&&paintedTextReceipt.epoch===bridge.getDocumentEpoch()&&!document.hidden&&
  paintedTextReceipt.scope.encoding===actual.encoding&&currentTextFindSource(paintedTextReceipt.scope);
 const foundInReader=type==='text'?paintedTextReceipt?.value===found&&ownsPaintedTextFind()?found:null:
  type==='pdf'&&(paintedPdfReceipt?.value!==found||paintedPdfReceipt?.epoch!==bridge.getDocumentEpoch())?null:found;
 const ownsPaintedFind=()=>type!=='pdf'||pdfInput.current===paintedPdfInput&&paintedPdfInput.receipt===paintedPdfReceipt&&
  !!paintedPdfReceipt&&paintedPdfReceipt.epoch===bridge.getDocumentEpoch()&&!document.hidden;
 const currentPdfMark=pdfMark&&ready&&!readerBusy&&pdfMark.raster===pdfShown&&ownsPaintedFind()&&
  pdfMark.receipt===paintedPdfReceipt&&pdfMark.receipt.pin&&matchesPdfFindPin(pdfMark.receipt.pin,windowId,source)?pdfMark:null;
 const pdfOccurrenceNav=currentPdfMark&&currentPdfMark.hits.length>1&&isDisplayedPdfRaster(details,currentPdfMark.raster)?currentPdfMark:null;
 const placePdfOccurrence=(body:HTMLDivElement,pin:PdfFindPin,mark:PdfMark)=>{
  const box=mark.hits[mark.index]?.[0],image=box&&displayedPdfImage(body,mark.raster,pin);
  if(!box||!image)return false;
  setPdfMark(mark);
  const [left,top,right,bottom]=box,viewport=body.getBoundingClientRect();
  body.scrollLeft+=image.left-viewport.left-body.clientLeft+(left+right)/2*image.width-body.clientWidth/2;
  body.scrollTop+=image.top-viewport.top-body.clientTop+(top+bottom)/2*image.height-body.clientHeight/2;
  return true;
 };
 const stepPdfOccurrence=(direction:-1|1)=>{
  const mark=pdfOccurrenceNav,body=pdfBody.current,pin=mark?.receipt.pin;
  if(!mark||!pin||!body?.isConnected||body.hidden||!ownsPaintedFind()||pending.current||pdfPending.current)return;
  const view=obj(bridge.getState().state.attachmentWorkspace),reader=obj(view.originalFile);
  if(view.open!==true||view.ready!==true||reader.busy!==false||!matchesPdfFindPin(pin,view.windowId,reader)||
     !isDisplayedPdfRaster(obj(reader.information),mark.raster)){setPdfMark(null);return}
  const index=(mark.index+direction+mark.hits.length)%mark.hits.length;
  if(!placePdfOccurrence(body,pin,{...mark,index}))setPdfMark(null);
 };
 const revealPdfMatch=(target:PdfMatchTarget,raster:PdfRaster,reset:boolean)=>{
  const body=pdfBody.current,active=document.activeElement;
  if(raster.page!==target.page||!body?.isConnected||body.hidden||live.current.retired||document.hidden||readerInteraction.current!==target.interaction||
     (active!==target.focus&&active!==document.body)||bridge.getDocumentEpoch()!==target.receipt.epoch||
     pdfInput.current!==target.input||target.input.receipt!==target.receipt)return;
  const fileView=obj(bridge.getState().state.attachmentWorkspace),reader=obj(fileView.originalFile);
  if(fileView.open!==true||fileView.ready!==true||fileView.windowId!==windowId||reader.id!==source.id||
     reader.identity!==source.identity||reader.sha256!==source.sha256||reader.sourceUnavailable!==false)return;
  const pane=body.closest<HTMLElement>('.pw-file-page-scroll'),view=pdfView.current;
  if(!pane||!view?.contains(body))return;
  const pin=target.receipt.pin;
  const hits=pin&&raster.searchAvailable&&matchesPdfFindPin(pin,windowId,source)&&matchesPdfFindPin(pin,fileView.windowId,reader)?
   pdfHitPositions(target.receipt.value,raster.page):[];
  if(!pin||!placePdfOccurrence(body,pin,{raster,receipt:target.receipt,hits,index:0})){
   setPdfMark(null);
   if(reset)body.scrollTop=0;
  }
  // Move only this reader's scroll surface, not its Work, Library or page underneath.
  pane.scrollTop+=view.getBoundingClientRect().top-pane.getBoundingClientRect().top;
  body.focus({preventScroll:true});
  target.onLanded?.();
 };
 useLayoutEffect(()=>{
  const reveal=pdfReveal.current;
  if(!reveal||reveal.raster!==pdfShown)return;
  pdfReveal.current=null;
  if(!ready)return;
  const {target,raster}=reveal;
  if(!('kind' in target)){revealPdfMatch(target,raster,true);return}
  if(ownsPdfPageStart(target)&&target.body.querySelector(`[data-pdf-raster="${raster.pdfRasterId}"]`))target.body.scrollTop=0;
 },[pdfShown,ready]);
 const pickPdfMatch=(page:number)=>{
  if(!paintedPdfReceipt||!ownsPaintedFind()||readerBusy)return;
  const target={page,input:paintedPdfInput,receipt:paintedPdfReceipt,interaction:readerInteraction.current,focus:document.activeElement,
   onLanded:findDetour.start({kind:'pdf',page})};
  if(ready&&page===pdfShown?.page)revealPdfMatch(target,pdfShown,false);
  else void invoke('read',{page},target);
 };
 const pickTextMatch=(hit:TextFindHit)=>{
  if(!paintedTextReceipt||readerBusy||!ownsPaintedTextFind()||!paintedTextReceipt.matches.includes(hit))return;
  const {scope}=paintedTextReceipt,pane=textBody.current?.closest<HTMLElement>('.pw-file-page-scroll');
  const advance=pane&&trackTextAdvance&&!markdown&&shownText&&textRangeMatches(currentTextRange(textRange),textRange)?
   {range:{...scope,offset:hit.offset},epoch:paintedTextReceipt.epoch,interaction:readerInteraction.current,focus:document.activeElement,
    pane,findCurrent:ownsPaintedTextFind}:undefined;
  const onLanded=findDetour.start({kind:'text',offset:hit.offset,encoding:scope.encoding});
  void invoke('read',{offset:hit.offset,encoding:scope.encoding},undefined,advance).then(result=>{
   if(onLanded&&result&&ownsPaintedTextFind()&&textRangeMatches(result,{...scope,offset:hit.offset})&&
      result.content.startsWith(paintedTextReceipt.value.query))onLanded();
  });
 };
 const selectTextEncoding=(encoding:string)=>{
  retireTextFind(query);setFound(null);read({encoding,offset:0});
 };
 // A second interaction supersedes the reveal, not the already requested native read.
 const noteReaderInteraction=()=>{if(type==='pdf')readerInteraction.current++};
 const zoomOriginal=(next:number|((value:number)=>number))=>{if(type==='pdf')retirePdfMatch();setZoom(next)};
 const pdfMatchPages=type==='pdf'&&foundInReader?[...new Set(rows(foundInReader.matches).map(hit=>hit.page as number))]:null;
 // If the earlier search error hid the page, keep the same explicit search box usable for a different phrase.
 const canFindPdf=type==='pdf'&&toolsVisible&&(ready||previousPdfFindFailed&&source.sourceUnavailable===false&&pdfShown?.searchAvailable===true);
 const findOriginal=(canFindPdf||type==='text'&&ready)&&<Accordion className="pw-original-find"><AccordionItem id="original-find" title="查找原文" description={query?`当前查找：${query}`:undefined}><div className="pw-file-search"><TextField label={type==='pdf'?'查找 PDF 原文层':'查找文字（区分大小写）'} value={query} onChange={changeQuery}/><Button variant="secondary" disabled={readerBusy||!query||type==='pdf'&&!actual.searchAvailable} onClick={()=>void invoke('find',{query,encoding:actual.encoding,cursor:type==='pdf'?1:0})}>查找</Button></div>
 {type==='pdf'&&ready&&!actual.searchAvailable&&<p className="pw-muted">此设备不支持 PDF 原文搜索；可返回文件来源，选择本机文字识别。</p>}
 {foundInReader&&<>
  {type==='text'&&!!paintedTextReceipt?.matches.length&&<div className="pw-outcome-files" aria-label="当前文字匹配">
   {paintedTextReceipt.matches.map(hit=><Button key={hit.offset} variant="ghost" disabled={readerBusy} onClick={()=>pickTextMatch(hit)}>
    {hit.before.trim()||hit.after.trim()?<span><bdi>{hit.before}<strong>{foundInReader.query}</strong>{hit.after}</bdi>
     <small>位置 {hit.offset+1}</small></span>:`位置 ${hit.offset+1}`}
   </Button>)}
  </div>}
  <div className="pw-action-row">
  {pdfMatchPages?.map(page=><Button key={page} variant="ghost" disabled={readerBusy}
   aria-label={`查看原 PDF 第 ${page} 页的匹配`} onClick={()=>pickPdfMatch(page)}>第 {page} 页</Button>)}
  {(type==='pdf'?foundInReader.nextPage!=null:Number.isSafeInteger(foundInReader.nextOffset)&&foundInReader.nextOffset>foundInReader.startOffset)&&
   <Button variant="secondary" disabled={readerBusy}
   onClick={()=>{if(type==='pdf'?ownsPaintedFind():ownsPaintedTextFind())void invoke('find',
    {query:foundInReader.query,encoding:actual.encoding,cursor:foundInReader[type==='pdf'?'nextPage':'nextOffset']})}}>继续后续范围</Button>}
  {!rows(foundInReader.matches).length&&<p>{type==='pdf'&&foundInReader.scannedThrough<foundInReader.firstPage?
   '本次还没有查到页面。':type==='text'&&foundInReader.atEnd===false&&foundInReader.scannedUntil===foundInReader.startOffset?
    '本次还未扫描到文字。':'当前查找范围内没有匹配文字。'}</p>}
  {foundInReader.pageMatchesOmitted&&<p>部分命中未显示，请缩小查找范围。</p>}
 </div></>}</AccordionItem></Accordion>;
 return <section ref={readerSurface} className="pw-original-file" aria-label={`原件：${source.name}`}
  style={showingPdfPageText||showingWordExcerpt?{display:'none'}:undefined} onPointerDownCapture={noteReaderInteraction}
  onClickCapture={noteReaderInteraction} onInputCapture={noteReaderInteraction}
  onWheelCapture={noteReaderInteraction} onTouchMoveCapture={noteReaderInteraction} onKeyDownCapture={noteReaderInteraction}>
 {showTitle&&<header><h3>{source.name}</h3><p className="pw-muted">{source.mimeType}</p></header>}
 {findDetour.available&&<div className="pw-reader-find-return">
  <Button variant="ghost" size="small" onClick={findDetour.back}>回到查找前</Button>
 </div>}
 {!retired&&readerBusy&&<p role="status">正在读取原件…</p>}
 {!failed&&unavailable&&<p role="status">{unavailable}{recovery?.canSave&&' 可以另存这份原件，文件内容不变。'}</p>}
 {failed&&<section aria-label="原件读取失败">
  <p className="pw-error" role="alert">{failed}</p>
  {retaining&&ready&&<p className="pw-muted">这次操作没有更新内容，仍显示同一原件上次已读的范围。</p>}
  {!source.sourceUnavailable&&!previousPdfFindFailed&&!previousTextFindFailed&&<Button variant="secondary" disabled={readerBusy} onClick={retry}>{failedRead?.command==='find'?'重试本次查找':type==='unsupported'?'重新核对文件':'重试本次读取'}</Button>}
  {type==='pdf'&&!retired&&!pdfShown&&failedRead?.command==='read'&&Number(failedRead.args.page)>1&&
   <Button variant="ghost" disabled={readerBusy} onClick={()=>read({page:1})}>从第 1 页读取</Button>}
  {type==='text'&&!retired&&failedRead?.command!=='find'&&actual.previewAvailable!==false&&<div className="pw-action-row">
   <RadioOptions label="重新读取时使用的文本编码" value={encodingChoice} options={textEncodings} isDisabled={busy||source.busy} onChange={setRecoveryEncoding}/>
   <Button variant="secondary" disabled={busy||source.busy} onClick={()=>selectTextEncoding(encodingChoice)}>用此编码从头读取</Button>
  </div>}
 </section>}
 {(unavailable||failed&&!ready&&failedRead?.command!=='find')&&!retired&&recovery?.onOpenInReader&&
  <div className="pw-action-row">{systemReaderAction('用其他应用打开原件','secondary')}</div>}
 {imageTools&&<div className="pw-action-row">
  {type==='pdf'&&<><Button variant="ghost" disabled={readerBusy||page<=1} onClick={()=>readPdfPage(page-1)}>上一页</Button><span>{page} / {pages} 页</span><Button variant="ghost" disabled={readerBusy||page>=pages} onClick={()=>readPdfPage(page+1)}>下一页</Button></>}
  <Button variant="ghost" aria-expanded={toolsVisible} onClick={()=>setToolsVisible(value=>!value)}>{toolsVisible?'收起阅读工具':'阅读工具'}</Button>
  {mayReadPdfText&&<Button variant="ghost" size="small" aria-label={`读取原 PDF 第 ${page} 页的文字`} onClick={readPdfPageText}>本页文字</Button>}
 </div>}
 {type==='pdf'&&ready&&<div hidden={!toolsVisible} className="pw-action-row pw-original-pagination pw-material-navigation"><TextField label="页码" value={position} onChange={setPosition}/><Button variant="secondary" disabled={readerBusy||!/^\d+$/.test(position)||Number(position)<1||Number(position)>pages} onClick={()=>readPdfPage(Number(position))}>跳转</Button></div>}
 {findBeforeBody&&findOriginal}
 {ready&&type==='text'&&<div ref={textBody} className="pw-original-text" data-find-before={findBeforeBody?true:undefined}>
  {previousText!==undefined&&<div className="pw-action-row"><Button variant="ghost" disabled={!shownText} onClick={()=>readTextSegment('previous')}>读取上一段</Button></div>}
  {markdown?<MarkdownBody text={String(actual.content||'')}/>:<pre className="pw-file-text" tabIndex={trackTextAdvance?-1:undefined}>{actual.content}</pre>}
  <div className="pw-action-row">{!!actual.offset&&<Button variant="ghost" disabled={busy} onClick={()=>read({encoding:actual.encoding,offset:0})}>从开头</Button>}
   {actual.nextOffset!=null&&<Button variant="secondary" disabled={busy} onClick={()=>readTextSegment('next')}>读取后续</Button>}
   {(!!actual.offset||actual.nextOffset!=null)&&<small>字符位置 {Number(actual.offset||0)+1}</small>}</div>
 </div>}
 {!findBeforeBody&&findOriginal}
 {imageTools&&<div hidden={!toolsVisible} className="pw-action-row pw-original-zoom pw-material-navigation" role="group" aria-label="原件显示比例"><Button variant="ghost" disabled={zoom<=1} onClick={()=>zoomOriginal(x=>Math.max(1,x/1.5))}>缩小</Button><Button variant="ghost" onClick={()=>{zoomOriginal(1);setTurn(0)}}>适合页面 · {Math.round(zoom*100)}%</Button><Button variant="ghost" disabled={zoom>=8} onClick={()=>zoomOriginal(x=>Math.min(8,x*1.5))}>放大</Button>{type==='image'&&<Button variant="ghost" onClick={()=>setTurn(x=>(x+90)%360)}>旋转查看</Button>}</div>}
 {ready&&type==='image'&&<><p className="pw-muted">原尺寸 {actual.width} × {actual.height}{actual.sampled?' · 显示时已采样，导出保留完整原图':''}</p><div className="pw-original-raster"><img src={source.url} style={{width:`${zoom*100}%`,transform:`rotate(${turn}deg)`}} alt="选定的固定原图" onError={()=>setError('原件显示已关闭或未完成，请返回后重新打开')}/></div></>}
 {/* Keep the previous decoded image mounted until the next exact raster is acknowledged; never fetch it again as a fallback. */}
 {type==='pdf'&&!retired&&(pdfShown||pdfAttempt)&&<div ref={pdfView} hidden={!ready}>
  {pdfOccurrenceNav&&<div role="group" aria-label="本页查找位置" className="pw-action-row pw-original-pdf-hit-navigation">
   <IconButton icon={IconChevronLeft} size="small" aria-label="定位上一处匹配" onClick={()=>stepPdfOccurrence(-1)}/>
   <span className="pw-muted" role="status">本页可定位 {pdfOccurrenceNav.index+1} / {pdfOccurrenceNav.hits.length}</span>
   <IconButton icon={IconChevronRight} size="small" aria-label="定位下一处匹配" onClick={()=>stepPdfOccurrence(1)}/>
  </div>}
  <div ref={pdfBody} tabIndex={-1} role="region"
  aria-label={pdfShown?`原 PDF 第 ${pdfShown.page} 页`:'原 PDF'} className="pw-original-raster" hidden={!ready}>
  {[pdfShown,pdfAttempt].map(raster=>raster&&<div key={raster.pdfRasterId} data-pdf-raster={raster.pdfRasterId} className="pw-original-pdf-page"
   style={{display:pdfShown===raster?'block':'none',width:`${zoom*100}%`}}>
   <img src={`${source.url}?page=${raster.page}&raster=${raster.pdfRasterId}`} loading="eager" style={{width:'100%'}}
    alt={`原 PDF 第 ${raster.page} 页`} onLoad={()=>void confirmRaster(raster)} onError={()=>failRaster(raster)}/>
   {currentPdfMark?.raster===raster&&currentPdfMark.hits[currentPdfMark.index]?.map(([left,top,right,bottom],index)=><span key={index}
    aria-hidden="true" className="pw-original-pdf-match"
    style={{left:`${left*100}%`,top:`${top*100}%`,width:`${(right-left)*100}%`,height:`${(bottom-top)*100}%`}}/>)}
  </div>)}
  </div>
 </div>}
 {ready&&(type==='video'||type==='audio')&&<OriginalMediaPlayer key={`${windowId}:${source.id}`} url={source.url} kind={type} name={String(source.name||'媒体原件')} onError={()=>setError('此设备未能播放这份原媒体。')} />}
 {ready&&type==='office'&&<>
  <div className="pw-action-row"><p className="pw-muted">本机只显示文字和表格结构，不还原完整排版和图表。</p>
   {previousWorksheet&&<Button variant="ghost" disabled={readerBusy} onClick={()=>read(previousWorksheet)}>读取本表上一段</Button>}
   {systemReaderAction('用其他应用核对原件','ghost')}
  </div>
  {!!worksheets.length&&<SelectField label="工作表" placeholder="选择可见工作表"
   value={worksheets.some(sheet=>sheet.unit===actual.unit)?String(actual.unit):''} options={worksheets}
   isDisabled={!canChooseOfficeUnit} onChange={id=>{
    const sheet=worksheets.find(choice=>choice.id===id);
    if(sheet&&sheet.unit!==actual.unit&&canChooseOfficeUnit&&!document.hidden)read({unit:sheet.unit,chunk:0});
   }}/>}
  {!!wordHeadings.length&&<SelectField label="按标题定位" placeholder="选择已识别的标题"
   value={wordHeadings.some(heading=>heading.unit===actual.unit)?String(actual.unit):''}
   options={wordHeadings.map(heading=>({id:String(heading.unit),
    label:wordHeadings.some(other=>other.unit!==heading.unit&&other.text.trim()===heading.text.trim())?
     `${heading.text} · 段落 ${heading.unit}`:heading.text,description:`${heading.level} 级标题`}))}
   isDisabled={!canChooseOfficeUnit} onChange={id=>{
    const heading=wordHeadings.find(choice=>String(choice.unit)===id);
    if(heading&&heading.unit!==actual.unit&&canChooseOfficeUnit&&!document.hidden)read({unit:heading.unit,chunk:0});
   }}/>}
  {(!worksheets.length||worksheets.length<actual.totalUnits)&&
   <div className="pw-action-row"><TextField label={actual.format==='pptx'?'幻灯片编号':actual.format==='xlsx'?'工作表编号':'段落起点'} value={position} onChange={setPosition}/><Button variant="secondary" disabled={busy||!/^\d+$/.test(position)||Number(position)<1} onClick={()=>read({unit:Number(position),chunk:0})}>定位</Button></div>}
  {actual.format==='docx'?<OriginalWordFind source={source} windowId={windowId} value={actual} busy={readerBusy}
   find={query=>invoke('find',{query})} read={(unit,chunk)=>invoke('read',{unit,chunk})}
   onExcerpt={onReadWordExcerpt} onSavedExcerpt={onOpenSavedWordExcerpt}
   returnRevision={wordReturnRevision} beforeFindJump={(unit,chunk)=>findDetour.start({kind:'word',unit,chunk})}>
   {(match,action)=><DocumentProjection value={actual} orderedOriginalWord wordMatch={match} wordExcerptAction={action}/>}
  </OriginalWordFind>:<DocumentProjection value={actual} orderedOriginalWord/>}
  {Number(actual.nextUnit)>0&&<Button variant="secondary" disabled={actual.format==='docx'?readerBusy:busy}
   onClick={actual.format==='docx'?readWordNext:()=>read({unit:actual.nextUnit,chunk:actual.nextChunk})}>读取后续内容</Button>}
 </>}
 {ready&&type==='text'&&<Accordion className="pw-original-find"><AccordionItem id="text-options" title="文本显示选项">
  <RadioOptions label="文本编码" value={actual.encoding||'UTF-8'} options={textEncodings} isDisabled={busy} onChange={encoding=>{setRecoveryEncoding(encoding);selectTextEncoding(encoding)}}/>
  {isMarkdown&&<Button variant="ghost" onClick={()=>{findDetour.clear();setMarkdown(value=>!value)}}>{markdown?'显示原文':'显示 Markdown'}</Button>}
 </AccordionItem></Accordion>}
 </section>;
}
