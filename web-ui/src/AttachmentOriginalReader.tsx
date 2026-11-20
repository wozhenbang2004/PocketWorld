import {useEffect,useRef,useState} from 'react';
import {Accordion,AccordionItem,Button,RadioOptions,TextField} from './forms-library';
import {IconExternalLink} from './icons';
import {MarkdownBody} from './library';
import {bridge} from './bridge';
import {documentTable,fileObject as obj,fileRows as rows,type FileJson as Json} from './attachment-workspace';
import {OriginalMediaPlayer} from './workspace-library/OriginalMediaPlayer';

export function FileDetails({value,title='来源详情'}:{value:unknown;title?:string}) {
 return value == null ? null : <details className="pw-file-details"><summary>{title}</summary><pre>{typeof value==='string'?value:JSON.stringify(value,null,2)}</pre></details>;
}
export function DocumentProjection({value}:{value:Json}) {
 const chunks=rows(value.chunks),tables=new Map<string,Json[]>(),paragraphs:Json[]=[];
 for(const block of chunks){const at=obj(block.locator);if(at.cell||at.table){const key=`${at.sheet||''}:${at.table||''}:${block.unit}`;tables.set(key,[...(tables.get(key)||[]),block])}else paragraphs.push(block)}
 return <section className="pw-file-projection" aria-label="当前读取范围"><p className="pw-muted">{value.coverage||'这里只显示当前已读取的范围。'} {value.status&&`读取状态：${value.status}`}</p>
 {!chunks.length&&<p>当前范围没有可显示的文字，不代表原文件为空。</p>}
 {paragraphs.map((block,i)=><div key={`${block.unit}:${block.chunk}:${i}`}><small className="pw-muted">{value.format==='pptx'?'幻灯片':'单元'} {block.unit}</small><p className="pw-file-text">{block.text}</p></div>)}
 {[...tables].map(([key,blocks])=>{const table=documentTable(blocks);return <div className="pw-file-table" key={key}><table><caption>{obj(blocks[0]?.locator).sheet||'原文档表格 · 当前范围'}</caption><thead><tr><th>行</th>{table.columns.map(col=><th key={col}>{col}</th>)}</tr></thead><tbody>{table.rows.map(row=><tr key={row}><th>{row}</th>{table.columns.map(col=><td key={col}>{table.text(row,col)}</td>)}</tr>)}</tbody></table></div>})}
 {value.format==='xlsx'&&<p className="pw-muted">公式显示原文件保存的值或公式文字，本次没有重新计算。</p>}
 {Array.isArray(value.warnings)&&value.warnings.length>0&&<details><summary>格式与读取说明</summary>{value.warnings.map((text:string,i:number)=><p key={i}>{text}</p>)}</details>}
 </section>;
}

const textEncodings=['UTF-8','UTF-16LE','UTF-16BE','GB18030','ISO-8859-1'].map(id=>({id,label:id}));
type OriginalReaderProps={source:Json;windowId:string;showTitle?:boolean;recovery?:{canSave:boolean;busy:boolean;onOpenInReader?:()=>void}};
type PdfRaster={pdfRasterId:string;page:number;pages:number;searchAvailable:boolean};
function pdfRaster(value:Json):PdfRaster|null {
 const {pdfRasterId,page,pages,searchAvailable}=value;
 return typeof pdfRasterId==='string'&&/^[a-f0-9-]{36}$/.test(pdfRasterId)&&
  Number.isSafeInteger(page)&&Number.isSafeInteger(pages)&&page>=1&&page<=pages&&typeof searchAvailable==='boolean'
  ?{pdfRasterId,page,pages,searchAvailable}:null;
}
const officePreviewIssue:Record<string,string>={
 unsupported:'这个文档格式没有内置预览。', encrypted:'这份文档受加密保护，无法在这里预览。',
 invalid:'未能读出这份文档的结构。', limit_exceeded:'这份文档超出了内置预览的读取范围。', unavailable:'这份文档暂时不能在这里预览。',
};

export function AttachmentOriginalReader(props:OriginalReaderProps) {
 return <OriginalReader key={JSON.stringify([props.windowId,props.source.id])} {...props}/>;
}

function OriginalReader({source,windowId,showTitle=true,recovery}:OriginalReaderProps) {
 const [toolsVisible,setToolsVisible]=useState(false);
 const [value,setValue]=useState<Json>(obj(source.information)),[error,setError]=useState(''),[busy,setBusy]=useState(false),[query,setQuery]=useState(''),[found,setFound]=useState<Json|null>(null),[zoom,setZoom]=useState(1),[turn,setTurn]=useState(0),[position,setPosition]=useState('1'),[markdown,setMarkdown]=useState(String(source.name||'').toLowerCase().endsWith('.md'));
 const [recoveryEncoding,setRecoveryEncoding]=useState('');
 const [failedRead,setFailedRead]=useState<{command:string;args:Json}|null>(null);
 const [pdfShown,setPdfShown]=useState<PdfRaster|null>(null),[pdfAttempt,setPdfAttempt]=useState<PdfRaster|null>(null);
 const pdfPending=useRef<{raster:PdfRaster;acknowledging:boolean}|null>(null);
 const retired=source.sourceUnavailable===true;
 const live=useRef({id:source.id,windowId,retired}),sequence=useRef(0),pending=useRef(false);live.current={id:source.id,windowId,retired};
 const type=String(source.kind),details=obj(source.information);
 const clearPdfAttempt=()=>{pdfPending.current=null;setPdfAttempt(null)};
 const currentRaster=(raster:PdfRaster)=>!live.current.retired&&document.visibilityState!=='hidden'&&pdfPending.current?.raster===raster;
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
   setPdfShown(raster);clearPdfAttempt();
  } catch {failRaster(raster)}
 };
 const invoke=async(command:string,args:Json={})=>{
  if(pending.current||pdfPending.current||live.current.retired)return null;const owner={...live.current},request=++sequence.current;pending.current=true;setBusy(true);setFailedRead(null);setError('');
  try{
   const result=await bridge.command<Json>('attachmentWorkspace.viewer',{windowId:owner.windowId,viewerId:owner.id,command,...args});
   if(request!==sequence.current||live.current.id!==owner.id||live.current.windowId!==owner.windowId||live.current.retired||document.visibilityState==='hidden')return null;
   if(command==='find')setFound(result);
   else if(type==='pdf'){
    const raster=pdfRaster(obj(result));
    if(!raster)throw new Error('这次 PDF 页面显示未确认');
    pdfPending.current={raster,acknowledging:false};setPdfAttempt(raster);
   }else setValue(result);
   return result;
  }
  catch(cause){if(request===sequence.current&&!live.current.retired){setFailedRead({command,args});setError(cause instanceof Error?cause.message:'原件读取未完成')}return null}
  finally{if(request===sequence.current){pending.current=false;setBusy(false)}}
 };
 useEffect(()=>{setValue(obj(source.information));setError('');setFound(null);setZoom(1);setTurn(0);pending.current=false;void invoke('load');return()=>{sequence.current++;pending.current=false;pdfPending.current=null}},[source.id,windowId]);
 useEffect(()=>{const hide=()=>{if(document.visibilityState==='hidden'){sequence.current++;pending.current=false;clearPdfAttempt();setBusy(false);setFound(null)}};document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide)},[]);
 useEffect(()=>{if(type!=='pdf'&&!retired&&Object.keys(details).length)setValue(details)},[source.information,retired]);
 useEffect(()=>{if(retired){sequence.current++;pending.current=false;clearPdfAttempt();setPdfShown(null);setBusy(false);setValue({});setFound(null);setQuery('');setFailedRead(null)}},[retired]);
 const nativeError=typeof source.error==='string'?source.error:'';
 const failed=retired?'原来源已失效，请返回后重新打开。':nativeError||error;
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
 const imageTools=(type==='pdf'||type==='image')&&ready;
 const isMarkdown=String(source.name||'').toLowerCase().endsWith('.md');
 const read=(args:Json)=>void invoke('read',args);
 const retry=()=>void invoke(failedRead?.command||'load',failedRead?.args);
 const encodingChoice=recoveryEncoding||String(actual.encoding||'UTF-8');
 const systemReaderAction=(label:string,variant:'secondary'|'ghost')=>!retired&&recovery?.onOpenInReader&&
  <Button variant={variant} leadingIcon={IconExternalLink} disabled={recovery.busy||readerBusy} onClick={recovery.onOpenInReader}>{label}</Button>;
 return <section className="pw-original-file" aria-label={`原件：${source.name}`}>
 {showTitle&&<header><h3>{source.name}</h3><p className="pw-muted">{source.mimeType}</p></header>}
 {!retired&&readerBusy&&<p role="status">正在读取原件…</p>}
 {!failed&&unavailable&&<p role="status">{unavailable}{recovery?.canSave&&' 可以另存这份原件，文件内容不变。'}</p>}
 {failed&&<section aria-label="原件读取失败">
  <p className="pw-error" role="alert">{failed}</p>
  {retaining&&ready&&<p className="pw-muted">这次操作没有更新内容，仍显示同一原件上次已读的范围。</p>}
  {!source.sourceUnavailable&&<Button variant="secondary" disabled={readerBusy} onClick={retry}>{failedRead?.command==='find'?'重试本次查找':type==='unsupported'?'重新核对文件':'重试本次读取'}</Button>}
  {type==='pdf'&&!retired&&!pdfShown&&failedRead?.command==='read'&&Number(failedRead.args.page)>1&&
   <Button variant="ghost" disabled={readerBusy} onClick={()=>read({page:1})}>从第 1 页读取</Button>}
  {type==='text'&&!retired&&failedRead?.command!=='find'&&actual.previewAvailable!==false&&<div className="pw-action-row">
   <RadioOptions label="重新读取时使用的文本编码" value={encodingChoice} options={textEncodings} isDisabled={busy||source.busy} onChange={setRecoveryEncoding}/>
   <Button variant="secondary" disabled={busy||source.busy} onClick={()=>{setFound(null);read({encoding:encodingChoice,offset:0})}}>用此编码从头读取</Button>
  </div>}
 </section>}
 {(unavailable||failed&&!ready&&failedRead?.command!=='find')&&!retired&&recovery?.onOpenInReader&&
  <div className="pw-action-row">{systemReaderAction('用其他应用打开原件','secondary')}</div>}
 {imageTools&&<div className="pw-action-row">
  {type==='pdf'&&<><Button variant="ghost" disabled={readerBusy||page<=1} onClick={()=>read({page:page-1})}>上一页</Button><span>{page} / {pages} 页</span><Button variant="ghost" disabled={readerBusy||page>=pages} onClick={()=>read({page:page+1})}>下一页</Button></>}
  <Button variant="ghost" aria-expanded={toolsVisible} onClick={()=>setToolsVisible(value=>!value)}>{toolsVisible?'收起阅读工具':'阅读工具'}</Button>
 </div>}
 {type==='pdf'&&ready&&<div hidden={!toolsVisible} className="pw-action-row pw-original-pagination pw-material-navigation"><TextField label="页码" value={position} onChange={setPosition}/><Button variant="secondary" disabled={readerBusy||!/^\d+$/.test(position)||Number(position)<1||Number(position)>pages} onClick={()=>read({page:Number(position)})}>跳转</Button></div>}
 {ready&&type==='text'&&<div className="pw-original-text">{markdown?<MarkdownBody text={String(actual.content||'')}/>:<pre className="pw-file-text">{actual.content}</pre>}
  <div className="pw-action-row">{!!actual.offset&&<Button variant="ghost" disabled={busy} onClick={()=>read({encoding:actual.encoding,offset:0})}>从开头</Button>}
   {actual.nextOffset!=null&&<Button variant="secondary" disabled={busy} onClick={()=>read({encoding:actual.encoding,offset:actual.nextOffset})}>读取后续</Button>}
   {(!!actual.offset||actual.nextOffset!=null)&&<small>字符位置 {Number(actual.offset||0)+1}</small>}</div>
 </div>}
 {((type==='pdf'&&toolsVisible)||type==='text')&&ready&&<Accordion className="pw-original-find"><AccordionItem id="original-find" title="查找原文" description={query?`当前查找：${query}`:undefined}><div className="pw-file-search"><TextField label={type==='pdf'?'查找 PDF 原文层':'查找文字（区分大小写）'} value={query} onChange={text=>{setQuery(text.slice(0,256));setFound(null)}}/><Button variant="secondary" disabled={readerBusy||!query||type==='pdf'&&!actual.searchAvailable} onClick={()=>void invoke('find',{query,encoding:actual.encoding,cursor:type==='pdf'?1:0})}>查找</Button></div>
 {type==='pdf'&&ready&&!actual.searchAvailable&&<p className="pw-muted">此设备不支持 PDF 原文搜索；可返回文件来源，选择本机文字识别。</p>}
 {found&&<div className="pw-action-row">{rows(found.matches).map((hit,i)=><Button key={i} variant="ghost" disabled={readerBusy} onClick={()=>read(type==='pdf'?{page:hit.page}:{offset:hit.offset,encoding:actual.encoding})}>{type==='pdf'?`第 ${hit.page} 页`:`位置 ${Number(hit.offset)+1}`}</Button>)}{found[type==='pdf'?'nextPage':'nextOffset']!=null&&<Button variant="secondary" disabled={readerBusy} onClick={()=>void invoke('find',{query,encoding:actual.encoding,cursor:found[type==='pdf'?'nextPage':'nextOffset']})}>继续后续范围</Button>}{!rows(found.matches).length&&<p>当前查找范围内没有匹配文字。</p>}{found.pageMatchesOmitted&&<p>部分命中未显示，请缩小查找范围。</p>}</div>}</AccordionItem></Accordion>}
 {imageTools&&<div hidden={!toolsVisible} className="pw-action-row pw-original-zoom pw-material-navigation" role="group" aria-label="原件显示比例"><Button variant="ghost" disabled={zoom<=1} onClick={()=>setZoom(x=>Math.max(1,x/1.5))}>缩小</Button><Button variant="ghost" onClick={()=>{setZoom(1);setTurn(0)}}>适合页面 · {Math.round(zoom*100)}%</Button><Button variant="ghost" disabled={zoom>=8} onClick={()=>setZoom(x=>Math.min(8,x*1.5))}>放大</Button>{type==='image'&&<Button variant="ghost" onClick={()=>setTurn(x=>(x+90)%360)}>旋转查看</Button>}</div>}
 {ready&&type==='image'&&<><p className="pw-muted">原尺寸 {actual.width} × {actual.height}{actual.sampled?' · 显示时已采样，导出保留完整原图':''}</p><div className="pw-original-raster"><img src={source.url} style={{width:`${zoom*100}%`,transform:`rotate(${turn}deg)`}} alt="选定的固定原图" onError={()=>setError('原件显示已关闭或未完成，请返回后重新打开')}/></div></>}
 {/* Keep the previous decoded image mounted until the next exact raster is acknowledged; never fetch it again as a fallback. */}
 {type==='pdf'&&!retired&&(pdfShown||pdfAttempt)&&<div className="pw-original-raster" hidden={!ready}>
  {[pdfShown,pdfAttempt].map(raster=>raster&&<img key={raster.pdfRasterId}
   src={`${source.url}?page=${raster.page}&raster=${raster.pdfRasterId}`} loading="eager"
   style={{display:pdfShown===raster?'block':'none',width:`${zoom*100}%`}}
   alt={`原 PDF 第 ${raster.page} 页`} onLoad={()=>void confirmRaster(raster)} onError={()=>failRaster(raster)}/>)}
 </div>}
 {ready&&(type==='video'||type==='audio')&&<OriginalMediaPlayer key={`${windowId}:${source.id}`} url={source.url} kind={type} name={String(source.name||'媒体原件')} onError={()=>setError('此设备未能播放这份原媒体。')} />}
 {ready&&type==='office'&&<>
  <div className="pw-action-row"><p className="pw-muted">本机只显示文字和表格结构，不还原完整排版和图表。</p>
   {systemReaderAction('用其他应用核对原件','ghost')}
  </div>
  <div className="pw-action-row"><TextField label={actual.format==='pptx'?'幻灯片编号':actual.format==='xlsx'?'工作表编号':'段落起点'} value={position} onChange={setPosition}/><Button variant="secondary" disabled={busy||!/^\d+$/.test(position)||Number(position)<1} onClick={()=>read({unit:Number(position),chunk:0})}>定位</Button></div>
  <DocumentProjection value={actual}/>{Number(actual.nextUnit)>0&&<Button variant="secondary" disabled={busy} onClick={()=>read({unit:actual.nextUnit,chunk:actual.nextChunk})}>读取后续内容</Button>}
 </>}
 {ready&&type==='text'&&<Accordion className="pw-original-find"><AccordionItem id="text-options" title="文本显示选项">
  <RadioOptions label="文本编码" value={actual.encoding||'UTF-8'} options={textEncodings} isDisabled={busy} onChange={encoding=>{setRecoveryEncoding(encoding);setFound(null);read({encoding,offset:0})}}/>
  {isMarkdown&&<Button variant="ghost" onClick={()=>setMarkdown(value=>!value)}>{markdown?'显示原文':'显示 Markdown'}</Button>}
 </AccordionItem></Accordion>}
 </section>;
}
