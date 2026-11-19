import {useEffect,useRef,useState} from 'react';
import {Button,TextField} from './forms-library';
type Data=Record<string,any>;
export type TextHit={path:string;sha256:string;bytes:number;line:number;column:number;endColumn:number;text:string;linePreviewOffset:number;linePreviewTruncated:boolean};
type Found={textHandle:string;manifestHash:string;query:string;hasMore:boolean;coverageComplete:boolean;scannedFiles:number;scannedBytes:number;skippedCount:number;binarySkipped:number;matches:TextHit[]};
const sha=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const count=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0;
const excerpt=(h:TextHit)=>h.column-1-h.linePreviewOffset;
type Props={project:string;checkout:string;filesHandle:string;busy:boolean;visible:(v:boolean)=>void;
  invoke:(action:string,args:Data,accept:(v:Data)=>void)=>Promise<void>;open:(id:string,index:number,hit:TextHit,query:string)=>void};
export function ProjectCheckoutTextSearch({project,checkout,filesHandle,busy,visible,invoke,open}:Props){
  const [literal,type]=useState(''),[page,show]=useState<Found|null>(null),[number,turn]=useState(1),[searching,scan]=useState(false),alive=useRef(true);
  useEffect(()=>{alive.current=true;visible(false);return()=>{alive.current=false}},[visible,filesHandle]);
  function reset(){show(null);turn(1);visible(false)}
  function find(more=false){
    const previous=page,q=literal;
    if(busy||!q.trim()||more&&!previous?.hasMore)return;
    scan(true);void invoke(more?'findTextNext':'findText',more?{textHandle:previous!.textHandle}:{query:q},r=>{
      if(!alive.current)return;
      if(r.projectId!==project||r.checkoutId!==checkout||r.filesHandle!==filesHandle||r.query!==q||typeof r.textHandle!=='string'||!r.textHandle||
        !sha(r.manifestHash)||more&&(previous!.manifestHash!==r.manifestHash||r.textHandle===previous!.textHandle)||
        typeof r.hasMore!=='boolean'||typeof r.coverageComplete!=='boolean'||!count(r.scannedFiles)||r.scannedFiles>2000||!count(r.scannedBytes)||r.scannedBytes>16*1024*1024||
        !count(r.skippedCount)||!count(r.binarySkipped)||r.binarySkipped>r.scannedFiles||!Array.isArray(r.matches)||r.matches.length>16||(more||r.hasMore)&&!r.matches.length||
        !r.matches.every((h:TextHit)=>h&&typeof h.path==='string'&&h.path.length>0&&h.path.length<=240&&sha(h.sha256)&&count(h.bytes)&&
          count(h.line)&&h.line>=1&&count(h.column)&&h.column>=1&&h.endColumn===h.column+q.length&&count(h.linePreviewOffset)&&typeof h.text==='string'&&
          typeof h.linePreviewTruncated==='boolean'&&excerpt(h)>=0&&h.text.slice(excerpt(h),excerpt(h)+q.length)===q))
        throw Error('没有取得原副本同一版的代码搜索结果，请从头重新查找。');
      turn(more?number+1:1);show(r as Found);visible(true);
    }).finally(()=>{if(alive.current)scan(false)});
  }
  return <section aria-label="找这份副本里的代码文字" aria-busy={searching}>
    <TextField label="代码或报错原文" value={literal} isDisabled={busy} placeholder="例如一个方法名、一句提示"
      onChange={v=>{if(v.length<=128){type(v);reset()}}} onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();find()}}}/>
    <div className="pw-form-actions"><Button size="small" variant="secondary" disabled={busy||!literal.trim()} onClick={()=>find()}>{page?'从头重新查找':'查找代码文字'}</Button>
      {page&&<Button size="small" variant="ghost" disabled={busy} onClick={()=>{type('');reset()}}>回到文件清单</Button>}</div>
    {searching&&<p role="status" className="pw-field-hint">正在核对这份副本已保存的代码…</p>}
    {page?<><p role="status" className="pw-field-hint">
      {page.matches.length?`第 ${number} 页 · ${page.matches.length} 行命中`:page.coverageComplete?'可搜索的源码文字中没有命中':'已扫描的部分没有命中'}。
      已核对 {page.scannedFiles} 个文件；{!page.coverageComplete&&'本次未覆盖整个范围；'}{page.skippedCount>0&&`未纳入 ${page.skippedCount} 项；`}{page.binarySkipped>0&&`跳过 ${page.binarySkipped} 份二进制；`}不查隐藏项、构建输出或未保存草稿。</p>
      <div className="pw-checkout-file-list pw-checkout-text-matches">{page.matches.map((h,i)=><Button variant="ghost" key={page.textHandle+':'+i} disabled={busy||h.bytes>262144}
        onClick={()=>open(page.textHandle,i,h,literal)}><span dir="auto">{h.path} · 第 {h.line} 行{h.bytes>262144?' · 超过编辑上限，仅片段':''}</span>
        <code dir="auto">{h.linePreviewOffset>0?'…':''}{h.text.slice(0,excerpt(h))}<mark>{h.text.slice(excerpt(h),excerpt(h)+literal.length)}</mark>{h.text.slice(excerpt(h)+literal.length)}</code></Button>)}</div>
      {page.hasMore&&<Button size="small" variant="secondary" disabled={busy} onClick={()=>find(true)}>读取下一页命中</Button>}
    </>:<p className="pw-field-hint">只查这个副本已保存的源码，区分大小写。每行列出第一次匹配，不运行代码。</p>}
  </section>;
}

export function checkoutEditorSelection(source:string,anchor:Data,domText:string):[number,number]|null{
  // Android/HTML textarea normalizes CR and CRLF; native coordinates use raw UTF-16 and LF boundaries.
  if(!Number.isSafeInteger(anchor.start)||!Number.isSafeInteger(anchor.end)||anchor.start<0||anchor.end>source.length||
      anchor.end<=anchor.start||source.slice(anchor.start,anchor.end)!==anchor.query)return null;
  const normalize=(s:string)=>s.replace(/\r\n?/g,'\n');
  if(domText!==normalize(source))return null;
  const start=normalize(source.slice(0,anchor.start)).length,end=start+anchor.query.length;
  return domText.slice(start,end)===anchor.query?[start,end]:null;
}
