import {useEffect,useRef,useState} from 'react';
import {Accordion,AccordionItem,Button,SelectField,TextField} from './forms-library';
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

export function AttachmentOriginalReader({source,windowId}:{source:Json;windowId:string}) {
 const [value,setValue]=useState<Json>(obj(source.information)),[error,setError]=useState(''),[busy,setBusy]=useState(false),[query,setQuery]=useState(''),[found,setFound]=useState<Json|null>(null),[zoom,setZoom]=useState(1),[turn,setTurn]=useState(0),[position,setPosition]=useState('1'),[markdown,setMarkdown]=useState(String(source.name||'').toLowerCase().endsWith('.md'));
 const live=useRef({id:source.id,windowId}),sequence=useRef(0),pending=useRef(false);live.current={id:source.id,windowId};
 const type=String(source.kind),details=obj(source.information);
 const invoke=async(command:string,args:Json={})=>{
  if(pending.current)return null;const owner={...live.current},request=++sequence.current;pending.current=true;setBusy(true);setError('');
  try{const result=await bridge.command<Json>('attachmentWorkspace.viewer',{windowId:owner.windowId,viewerId:owner.id,command,...args});if(request!==sequence.current||live.current.id!==owner.id||live.current.windowId!==owner.windowId||document.visibilityState==='hidden')return null;if(command==='find')setFound(result);else setValue(result);return result}
  catch(cause){if(request===sequence.current)setError(cause instanceof Error?cause.message:'原件读取未完成');return null}
  finally{if(request===sequence.current){pending.current=false;setBusy(false)}}
 };
 useEffect(()=>{setValue(obj(source.information));setError('');setFound(null);setZoom(1);setTurn(0);pending.current=false;void invoke('load');return()=>{sequence.current++;pending.current=false}},[source.id,windowId]);
 useEffect(()=>{const hide=()=>{if(document.visibilityState==='hidden'){sequence.current++;pending.current=false;setBusy(false);setFound(null)}};document.addEventListener('visibilitychange',hide);return()=>document.removeEventListener('visibilitychange',hide)},[]);
 useEffect(()=>{if(Object.keys(details).length)setValue(details)},[source.information]);
 const actual=value,failed=String(source.error||error||''),ready=!failed&&Object.keys(actual).length>0,page=Number(actual.page||1),pages=Number(actual.pages||0);
 const read=(args:Json)=>void invoke('read',args);
 return <section className="pw-original-file" aria-label={`原件：${source.name}`}>
 <header><h3>{source.name}</h3><p className="pw-muted">选定的固定原件 · {source.mimeType}</p></header>
 {(busy||source.busy)&&<p role="status">正在读取原件…</p>}{failed&&<p className="pw-error" role="alert">{failed}</p>}
 {type==='pdf'&&ready&&<div className="pw-action-row"><Button variant="ghost" disabled={busy||page<=1} onClick={()=>read({page:page-1})}>上一页</Button><span>{page} / {pages} 页</span><Button variant="ghost" disabled={busy||page>=pages} onClick={()=>read({page:page+1})}>下一页</Button><TextField label="页码" value={position} onChange={setPosition}/><Button variant="secondary" disabled={busy||!/^\d+$/.test(position)||Number(position)<1||Number(position)>pages} onClick={()=>read({page:Number(position)})}>跳转</Button></div>}
 {(type==='pdf'||type==='text')&&ready&&<Accordion className="pw-original-find"><AccordionItem id="original-find" title="查找原文" description={query?`当前查找：${query}`:'按需要查找当前原文件'}>{(type==='pdf'||type==='text')&&ready&&<div className="pw-file-search"><TextField label={type==='pdf'?'查找 PDF 原文层':'查找文字（区分大小写）'} value={query} onChange={text=>{setQuery(text.slice(0,256));setFound(null)}}/><Button variant="secondary" disabled={busy||!query||type==='pdf'&&!actual.searchAvailable} onClick={()=>void invoke('find',{query,encoding:actual.encoding,cursor:type==='pdf'?1:0})}>查找</Button></div>}
 {type==='pdf'&&ready&&!actual.searchAvailable&&<p className="pw-muted">此设备不支持 PDF 原文搜索；可返回文件来源，选择本机文字识别。</p>}
 {found&&<div className="pw-action-row">{rows(found.matches).map((hit,i)=><Button key={i} variant="ghost" disabled={busy} onClick={()=>read(type==='pdf'?{page:hit.page}:{offset:hit.offset,encoding:actual.encoding})}>{type==='pdf'?`第 ${hit.page} 页`:`位置 ${Number(hit.offset)+1}`}</Button>)}{found[type==='pdf'?'nextPage':'nextOffset']!=null&&<Button variant="secondary" disabled={busy} onClick={()=>void invoke('find',{query,encoding:actual.encoding,cursor:found[type==='pdf'?'nextPage':'nextOffset']})}>继续后续范围</Button>}{!rows(found.matches).length&&<p>当前查找范围内没有匹配文字。</p>}{found.pageMatchesOmitted&&<p>部分命中未显示，请缩小查找范围。</p>}</div>}</AccordionItem></Accordion>}
 {ready&&(type==='image'||type==='pdf')&&<><div className="pw-action-row"><Button variant="ghost" onClick={()=>setZoom(x=>Math.max(1,x/1.5))}>缩小</Button><Button variant="ghost" onClick={()=>{setZoom(1);setTurn(0)}}>适合页面</Button><Button variant="ghost" onClick={()=>setZoom(x=>Math.min(8,x*1.5))}>放大</Button>{type==='image'&&<Button variant="ghost" onClick={()=>setTurn(x=>(x+90)%360)}>旋转查看</Button>}</div>{type==='image'&&<p className="pw-muted">原尺寸 {actual.width} × {actual.height}{actual.sampled?' · 显示时已采样，导出保留完整原图':''}</p>}<div className="pw-original-raster"><img key={`${source.id}:${page}`} src={`${source.url}${type==='pdf'?`?page=${page}`:''}`} style={{width:`${zoom*100}%`,transform:`rotate(${turn}deg)`}} alt={type==='pdf'?`原 PDF 第 ${page} 页`:'选定的固定原图'} onError={()=>setError('原件显示已关闭或未完成，请返回后重新打开')}/></div></>}
 {ready&&(type==='video'||type==='audio')&&<OriginalMediaPlayer key={`${windowId}:${source.id}`} url={source.url} kind={type} name={String(source.name||'媒体原件')} onError={()=>setError('原媒体无法播放，请返回后用其他阅读器打开')} />}
 {ready&&type==='office'&&<><div className="pw-action-row"><TextField label={actual.format==='pptx'?'幻灯片编号':actual.format==='xlsx'?'工作表编号':'段落起点'} value={position} onChange={setPosition}/><Button variant="secondary" disabled={busy||!/^\d+$/.test(position)||Number(position)<1} onClick={()=>read({unit:Number(position),chunk:0})}>定位</Button></div><p className="pw-muted">当前显示文字与表格结构，字体、分页和图表应在原件中核对。</p><DocumentProjection value={actual}/>{Number(actual.nextUnit)>0&&<Button variant="secondary" disabled={busy} onClick={()=>read({unit:actual.nextUnit,chunk:actual.nextChunk})}>读取后续内容</Button>}</>}
 {ready&&type==='text'&&<><SelectField label="文本编码" value={actual.encoding||'UTF-8'} options={['UTF-8','UTF-16LE','UTF-16BE','GB18030','ISO-8859-1'].map(id=>({id,label:id}))} isDisabled={busy} onChange={encoding=>{setFound(null);read({encoding,offset:0})}}/>{String(source.name).toLowerCase().endsWith('.md')&&<Button variant="ghost" onClick={()=>setMarkdown(x=>!x)}>{markdown?'显示原文':'显示 Markdown'}</Button>}{markdown?<MarkdownBody text={String(actual.content||'')}/>:<pre className="pw-file-text">{actual.content}</pre>}<div className="pw-action-row"><Button variant="ghost" disabled={busy||!actual.offset} onClick={()=>read({encoding:actual.encoding,offset:0})}>从开头</Button>{actual.nextOffset!=null&&<Button variant="secondary" disabled={busy} onClick={()=>read({encoding:actual.encoding,offset:actual.nextOffset})}>读取后续</Button>}<small>字符位置 {Number(actual.offset||0)+1}</small></div></>}
 </section>;
}
