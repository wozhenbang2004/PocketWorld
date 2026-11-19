import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog} from './forms-library';
import {remoteObject as object,type RemoteData as Data} from './remote-task-workspace';

type Head={id:string;revision:number;mode:'steer'|'next_turn';localState:'draft'|'queued'|'withdrawn'|'claimed';scopeHash:string;updatedAt:number};
const label={draft:'手机草稿',queued:'手机待发',withdrawn:'已从手机撤下',claimed:'原操作已被领取或状态未知'};
const sha=(s:unknown)=>typeof s==='string'&&/^[a-f0-9]{64}$/.test(s);
const head=(r:Data):r is Head=>!!r&&/^[a-f0-9-]{36}$/.test(r.id)&&Number.isSafeInteger(r.revision)&&r.revision>0&&
    ['steer','next_turn'].includes(r.mode)&&Object.hasOwn(label,r.localState)&&sha(r.scopeHash)&&Number.isSafeInteger(r.updatedAt)&&r.updatedAt>=0;
const only=(r:Head)=>({id:r.id,revision:r.revision,scopeHash:r.scopeHash});
const hash=async(s:string)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s))),v=>v.toString(16).padStart(2,'0')).join('');

export function RemotePhoneDraftShelf({taskId,runId,epoch}:{taskId:string;runId:string;epoch:number}){
  const [opened,show]=useState(false),[rows,setRows]=useState<Head[]>([]),[review,setReview]=useState<{row:Head;text:string;token:string}|null>(null),
    [busy,spin]=useState(false),[notice,tell]=useState(''),[error,fail]=useState(''),flight=useRef(false),generation=useRef(0),liveness=useRef(true);
  const reset=()=>{++generation.current;flight.current=false;spin(false);setRows([]);setReview(null);tell('');fail('');show(false)};
  useEffect(()=>{liveness.current=true;reset();const paused=()=>{if(document.hidden)reset()};document.addEventListener('visibilitychange',paused);
    return()=>{liveness.current=false;++generation.current;document.removeEventListener('visibilitychange',paused)}},[taskId,runId,epoch]);
  const where=()=>bridge.getDocumentEpoch()+':'+String(object(bridge.getState().view).resumeRevision);
  const can=()=>!document.hidden&&object(bridge.getState().view).taskId===taskId&&['list','read','withdraw'].every(m=>bridge.getState().capabilities.commands.includes('remoteTask.phoneDrafts.'+m));
  async function access(mode:'list'|'read'|'withdraw',row?:Head){
    if(flight.current||!can()||mode==='read'&&(!row||!['draft','queued'].includes(row.localState))||mode==='withdraw'&&!review)return;
    const target=review,n=++generation.current,window=where(),current=()=>liveness.current&&generation.current===n&&can()&&where()===window;
    flight.current=true;spin(true);show(true);fail('');tell('');if(mode!=='withdraw')setReview(null);
    try{
      const p={taskId,runId,coordinatorEpoch:epoch,...(mode==='read'&&row?only(row):mode==='withdraw'?{reviewHandle:target!.token}:{})};
      const r=await bridge.command<Data>('remoteTask.phoneDrafts.'+mode,p);if(!current())return;
      if(r.taskId!==taskId||r.runId!==runId||r.coordinatorEpoch!==epoch||r.networkUsed!==false||r.sent!==false)throw Error('没有取得这次本机原稿的回执。');
      if(mode==='list'){
        if(!Array.isArray(r.entries)||r.entries.length>16||!r.entries.every((e:Data)=>head(e)&&!('text' in e)))throw Error('本机原稿清单不能核对。');
        setRows(r.entries);
      }else if(mode==='read'&&row){
        const v=object(r.item),text=v.text,digest=v.textSha256;
        if(!head(v)||v.id!==row.id||v.revision!==row.revision||v.scopeHash!==row.scopeHash||v.localState!==row.localState||
          typeof text!=='string'||text.length>12000||!sha(digest)||await hash(text)!==digest||typeof r.reviewHandle!=='string')throw Error('原稿文字、版本或发送状态已变化。');
        if(current())setReview({row,text,token:r.reviewHandle});
      }else if(target){
        if(r.hostRecall!==false||r.localState!=='withdrawn'||r.id!==target.row.id||r.previousRevision!==target.row.revision||r.revision!==r.previousRevision+1)throw Error('本机撤下回执未核对，请重读清单；不会自动重试。');
        setReview(null);setRows(old=>old.map(v=>v.id===r.id?{...v,localState:'withdrawn',revision:r.revision}:v));
        tell('这份尚未发送的原稿已从手机撤下。没有向主机发送或撤回任何指令。');
      }
    }catch(e){if(current()){if(mode==='withdraw')setReview(null);fail(e instanceof Error?e.message:'本机原稿暂不能核对。')}}
    finally{if(liveness.current&&generation.current===n){flight.current=false;spin(false)}}
  }
  if(!bridge.getState().capabilities.commands.includes('remoteTask.phoneDrafts.list'))return null;
  return <div><Button variant="ghost" size="small" disabled={busy||!can()} onClick={()=>void access('list')}>查看本机原会话草稿</Button>
    {opened&&<SettingsDialog isOpen title={review?'核对这份未发送的原稿':'这次作业的手机草稿'} closeLabel="返回原任务" onClose={reset}>
      {review?<><p className="pw-field-hint">{label[review.row.localState]} · 版本 {review.row.revision}</p>
        <pre className="pw-file-revision-content">{review.text}</pre><p className="pw-field-hint">只从手机撤下这份草稿，不会撤回主机已收到的内容。</p>
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={()=>void access('list')}>保留原稿</Button>
          <Button disabled={busy} onClick={()=>void access('withdraw')}>撤下这份未发草稿</Button></div></>:
        <><p className="pw-field-hint">只查当前原任务在手机登记的版本，不联系主机。已领取的内容不能在此撤回。</p>
          {rows.map(v=><div key={v.id} className="pw-remote-file"><div><strong>{v.mode==='steer'?'当前轮次补充':'下一轮说明'}</strong><small>{label[v.localState]} · v{v.revision} · {new Date(v.updatedAt).toLocaleString('zh-CN',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})}</small></div>
            {['draft','queued'].includes(v.localState)&&<Button size="small" variant="ghost" disabled={busy} onClick={()=>void access('read',v)}>核对原稿</Button>}</div>)}
          {!busy&&!rows.length&&!error&&<p>这个执行轮次没有仍可核对的手机草稿。未列出不代表已经撤下。</p>}
          <Button variant="ghost" size="small" disabled={busy} onClick={()=>void access('list')}>重读本机清单</Button></>}
      {notice&&<p role="status">{notice}</p>}{error&&<p role="alert">{error}</p>}
    </SettingsDialog>}</div>;
}
