import {useEffect,useRef,useState} from 'react';
import {Button,TextField} from './forms-library';

type Data=Record<string,any>;
export type CheckoutPathHit={hit:number;path:string;sha256:string;bytes:number;binary:boolean;change:string;beforeHash?:string};
export type CheckoutPathPage={searchHandle:string;sourceDigest:string;query:string;filter:string;fileCount:number;total:number;start:number;next:number;files:CheckoutPathHit[];
  baselineIdentity?:string;removedCount?:number};
type Props={project:string;checkout:string;filesHandle:string;busy:boolean;visible:(value:boolean)=>void;
  invoke:(action:string,args:Data,accept:(v:Data)=>void)=>Promise<void>;open:(handle:string,hit:CheckoutPathHit)=>void};
const sha=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
export function checkoutPathPage(r:Data,project:string,checkout:string,filesHandle:string,q:string,mode:'all'|'changed'|'review_changes',start=0,last?:CheckoutPathPage|null):CheckoutPathPage{
  const review=mode==='review_changes',removed=review?r.removedCount:0;
  if(r.projectId!==project||r.checkoutId!==checkout||r.filesHandle!==filesHandle||typeof r.searchHandle!=='string'||!r.searchHandle||
    !sha(r.sourceDigest)||r.query!==q||r.filter!==mode||!Number.isInteger(r.total)||r.total<0||r.total>(review?4000:2000)||
    !Number.isInteger(r.fileCount)||r.fileCount<0||r.fileCount>2000||!Number.isInteger(removed)||removed<0||removed>r.total||r.total-removed>r.fileCount||
    review&&(q!==''||!sha(r.baselineIdentity)||r.readOnly!==true)||
    r.start!==start||last&&(r.searchHandle!==last.searchHandle||r.sourceDigest!==last.sourceDigest||r.total!==last.total||
      review&&(r.baselineIdentity!==last.baselineIdentity||removed!==last.removedCount||r.fileCount!==last.fileCount))||
    !Array.isArray(r.files)||r.files.length!==Math.min(40,r.total-r.start)||r.next!==(r.start+r.files.length<r.total?r.start+r.files.length:-1)||
    !r.files.every((v:Data,i:number)=>v.hit===r.start+i&&typeof v.path==='string'&&v.path.length>0&&v.path.length<=240&&
      Number.isSafeInteger(v.bytes)&&v.bytes>=0&&typeof v.binary==='boolean'&&(v.change==='deleted'?
        review&&v.sha256==='absent'&&sha(v.beforeHash)&&v.bytes===0:
        sha(v.sha256)&&['added','modified','unchanged'].includes(v.change))))
    throw Error('这页命中不属于原副本的同一次查询，请重新查找。');
  return r as CheckoutPathPage;
}
export function ProjectCheckoutPathSearch({project,checkout,filesHandle,busy,invoke,visible,open}:Props){
  const [query,type]=useState(''),[changed,filter]=useState(false),[page,show]=useState<CheckoutPathPage|null>(null),alive=useRef(true);
  useEffect(()=>{alive.current=true;visible(false);return()=>{alive.current=false}},[filesHandle,visible]);
  function clear(){show(null);visible(false)}
  function find(start?:number){
    const more=typeof start==='number',mode=changed?'changed':'all',q=query.trim(),last=page;
    if(busy||more&&!last||!more&&!q&&!changed)return;
    const args=more?{searchHandle:last!.searchHandle,start}:{query:q,filter:mode};
    void invoke(more?'findNext':'find',args,r=>{
      if(!alive.current)return;
      show(checkoutPathPage(r,project,checkout,filesHandle,q,mode,start??0,more?last:undefined));visible(true);
    });
  }
  return <section aria-label="在整个副本找文件"><TextField label="找文件" placeholder="输入文件名或路径的一部分" value={query} isDisabled={busy}
    onChange={s=>{if(s.length<=128){type(s);clear()}}} onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();find()}}}/>
    <div className="pw-form-actions"><Button variant={changed?'secondary':'ghost'} size="small" aria-pressed={changed} disabled={busy}
       onClick={()=>{filter(!changed);clear()}}>只看新增或修改</Button>
      <Button variant="secondary" size="small" disabled={busy||!query.trim()&&!changed} onClick={()=>find()}>{page?'重新查找':'搜索整个副本'}</Button>
      {page&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>{type('');filter(false);clear()}}>返回完整清单</Button>}</div>
    {page&&<><p role="status" className="pw-field-hint">{page.total?`这次从 ${page.fileCount} 个实际文件中找到 ${page.total} 个，现列 ${page.start+1} 至 ${page.start+page.files.length}。`:
      `这次在 ${page.fileCount} 个实际文件中未找到匹配项。`} 只查路径；尚未创建的草稿仍在原清单。</p>
      <div className="pw-checkout-file-list">{page.files.map(v=><Button key={v.hit} variant="ghost" disabled={busy||v.binary||v.bytes>262144} onClick={()=>open(page.searchHandle,v)}>
        <span dir="auto">{v.path}</span><small>{v.binary?'二进制，仅信息':v.bytes>262144?'超过编辑上限，仅信息':v.change==='modified'?'已修改':v.change==='added'?'副本新增':'与基线一致'} · {v.bytes} B</small></Button>)}</div>
      <div className="pw-form-actions">{page.start>0&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>find(page.start-40)}>上一页命中</Button>}
        {page.next!==-1&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>find(page.next)}>下一页命中</Button>}</div></>}
  </section>;
}
