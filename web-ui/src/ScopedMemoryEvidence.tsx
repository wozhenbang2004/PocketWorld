import {useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';

type Row=Record<string,any>;
type Page={title:string;text:string;next?:number;index:number;more?:boolean;offset?:number;previous?:number[];currentTaskSummary?:boolean};
type Invoke=(action:string,args:Row,receive:(result:Row)=>void)=>Promise<unknown>;
const refKeys=['kind','sourceId','revision','ownerId','spaceId'];
const fileKeys=['projectId','path','revisionId','sha256','offset','chars'];
const sourceWindow=()=>{const s=bridge.getState().state;return JSON.stringify([bridge.getDocumentEpoch(),s.memoryRefreshRevision,s.traceSourceRevision])};
const sameRef=(actual:Row|undefined,expected:Row)=>actual&&refKeys.every(k=>actual[k]===expected[k])&&
  (expected.kind!=='workspace_file'||actual.workspace&&expected.workspace&&fileKeys.every(k=>actual.workspace[k]===expected.workspace[k]));

/** Only the current explicitly displayed row; no observer initiates another memory lookup. */
export function ScopedMemoryEvidence({record,candidate,project,handle,expires,busy,invoke,back,proposal=false}:{
  record:Row;candidate:boolean;project:boolean;handle:string;expires:number;busy:boolean;invoke:Invoke;back:()=>void;proposal?:boolean;
}) {
  const [page,setPage]=useState<Page|null>(null),[closed,setClosed]=useState(false);
  const life=useRef({live:false,epoch:0,window:sourceWindow()});
  const refs:Row[]=Array.isArray(record.sourceRefs)?record.sourceRefs:[];
  const parents:Row[]=candidate&&Array.isArray(record.recordRefs)?record.recordRefs:[];
  useLayoutEffect(()=>{
    const state=life.current;state.live=true;state.window=sourceWindow();setPage(null);setClosed(false);
    const retire=()=>{if(!state.live)return;state.live=false;state.epoch++;setPage(null);setClosed(true)};
    const hidden=()=>{if(document.hidden)retire()};
    const off=bridge.subscribe(()=>{if(sourceWindow()!==state.window)retire()});
    const left=Number.isSafeInteger(expires)?expires-Date.now():0;
    const timer=left>0?window.setTimeout(retire,left):undefined;
    if(left<=0||document.hidden)retire();
    document.addEventListener('visibilitychange',hidden);window.addEventListener('pagehide',retire);
    return()=>{state.live=false;state.epoch++;if(timer!==undefined)clearTimeout(timer);off();document.removeEventListener('visibilitychange',hidden);window.removeEventListener('pagehide',retire)};
  },[handle,expires]);
  const read=(parent:boolean,index:number,offset=0,previous:number[]=[])=>{
    const state=life.current;if(busy||!state.live||document.hidden||Date.now()>=expires)return;
    const epoch=++state.epoch;setPage(null);
    if(proposal&&(parent||refs[index]?.kind!=='message'||!record.reviewDigest))return;
    void invoke(proposal?'memoryDraft.source':parent?'knowledge.parent':'knowledge.source',proposal?{handle,index,offset}:{readHandle:handle,index,...(parent?{}:{offset})},value=>{
      if(!state.live||state.epoch!==epoch||document.hidden||sourceWindow()!==state.window||Date.now()>=expires)return;
      if(value.index!==index||value.readExpiresAt!==expires||proposal&&value.reviewDigest!==record.reviewDigest)throw new Error('这不是原阅读窗口的来源回执。');
      if(parent){
        const ref=parents[index],r=value.record;if(!ref||!r||ref.id!==r.id||ref.revision!==r.revision||typeof r.text!=='string')throw new Error('父记忆原版本无法核对。');
        setPage({title:'父记忆的精确版本',text:r.text,index});return;
      }
      const ref=refs[index];
      if(!ref||!sameRef(value.sourceRef,ref)||value.kind!==ref.kind||typeof value.text!=='string'||value.text.length>8000||
        value.offset!==offset||!Number.isSafeInteger(value.nextOffset)||value.nextOffset!==offset+value.text.length||
        !Number.isSafeInteger(value.totalChars)||value.totalChars<value.nextOffset||value.hasMore!==(value.nextOffset<value.totalChars)||
        value.hasMore&&value.nextOffset<=offset||ref.kind==='task_evidence'&&typeof value.historicalBodySnapshot!=='boolean')throw new Error('原来源或本段范围无法核对。');
      setPage({title:value.title||'原来源',text:value.text,index,offset,previous,next:value.nextOffset,more:value.hasMore,currentTaskSummary:ref.kind==='task_evidence'&&value.historicalBodySnapshot===false});
    });
  };
  if(!refs.length&&!parents.length)return null;
  if(closed)return <div className="pw-form-actions"><p className="pw-muted" role="status">{proposal?'原来源阅读窗口已关闭。请在原回答重新核对。':'原来源阅读窗口已关闭。请回到列表，在原范围重新选择这条记录。'}</p><Button variant="ghost" disabled={busy} onClick={back}>{proposal?'返回原回答':'返回原列表'}</Button></div>;
  return <><div className="pw-form-actions">{refs.map((r,i)=><Button key={i} variant="ghost" disabled={busy} onClick={()=>read(false,i)}>{project&&r.kind==='workspace_file'&&r.workspace?.path?`查看原段 · ${r.workspace.path} · v${r.revision}`:`查看来源 ${i+1}`}</Button>)}
    {parents.map((_,i)=><Button key={'parent-'+i} variant="ghost" disabled={busy} onClick={()=>read(true,i)}>查看父记忆 {i+1}</Button>)}</div>
    {page&&<section className="pw-source-record"><h3>{page.title}</h3>{page.currentTaskSummary&&<p className="pw-muted">这里是任务当前可核对的摘要，不是当时保留的原文快照。</p>}
      <pre className="pw-source-copy" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{page.text||'原来源没有文字内容。'}</pre>
      {proposal&&!!page.previous?.length&&<Button variant="ghost" disabled={busy} onClick={()=>read(false,page.index,page.previous!.at(-1),page.previous!.slice(0,-1))}>上一段</Button>}
      {page.more&&<Button variant="ghost" disabled={busy} onClick={()=>read(false,page.index,page.next,proposal?[...(page.previous||[]),page.offset||0]:[])}>继续读取原文</Button>}</section>}
  </>;
}
