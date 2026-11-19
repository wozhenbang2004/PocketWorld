import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {bridge} from './bridge';
type Data=Record<string,any>;type Source='journal'|'callback';type Channel='summary'|'stderr'|'stdout';
const sha=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const frame=()=>JSON.stringify([bridge.getDocumentEpoch(),(bridge.getState().view as Data).taskId,(bridge.getState().view as Data).resumeRevision]);
// Display controls as pictures/escaped code points, not terminal instructions.
const shown=(s:string)=>s.replace(/\r\n/g,'\n').replace(/[\u0000-\u0008\u000b-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g,c=>'⟦U+'+c.charCodeAt(0).toString(16).toUpperCase().padStart(4,'0')+'⟧');
type Props={task:Data;row:Data;active:boolean;run:(action:string,args?:Data)=>Promise<any>};
export function ProjectTermuxOriginalReceipt({task,row,active,run}:Props){
  const [page,setPage]=useState<Data|null>(null),[busy,setBusy]=useState(false),[error,fail]=useState('');
  const pending=useRef<object|null>(null),live=useRef(false),original=useRef(frame()),ctx=task.raw?.developmentContext;
  useEffect(()=>{
    live.current=active;original.current=frame();pending.current=null;setPage(null);setBusy(false);fail('');
    const retire=()=>{if(document.hidden||frame()!==original.current){live.current=false;pending.current=null;setPage(null);setBusy(false)}};
    const off=bridge.subscribe(retire);document.addEventListener('visibilitychange',retire);
    return()=>{live.current=false;pending.current=null;off();document.removeEventListener('visibilitychange',retire)};
  },[active,task.id,row.operationId]);
  const read=async(source:Source,channel:Channel='summary')=>{
    if(!live.current||!active||document.hidden||pending.current||frame()!==original.current||(bridge.getState().view as Data).taskId!==task.id)return;
    const expected=channel==='summary'?'':page?.recordDigest,token={};if(channel!=='summary'&&(!sha(expected)||page?.readSource!==source))return;
    pending.current=token;setBusy(true);fail('');if(channel==='summary')setPage(null);
    try{
      const value=await run('local.execution.savedTermux',{taskId:task.id,executionId:row.operationId,source,channel,expectedRecord:expected});
      if(!live.current||pending.current!==token||document.hidden||frame()!==original.current)return;
      if(value.format!=='pocketworld.owner-termux-command-record.v1'||value.taskId!==task.id||value.executionId!==row.operationId||value.runId!==row.runId||
        value.projectId!==ctx?.projectId||value.checkoutId!==ctx?.checkoutId||value.readSource!==source||!Number.isSafeInteger(value.epoch)||value.epoch<1||
        !sha(value.planDigest)||!sha(value.sourceDigest)||typeof value.hasJob!=='boolean'||!['exit_code','not_recorded','incomplete'].includes(value.callbackState)||
        value.hasJob&&(!sha(value.recordDigest)||!sha(value.receiptIdentity)||![value.stdoutChars,value.stderrChars].every(n=>Number.isSafeInteger(n)&&n>=-1))||
        value.callbackState==='exit_code'&&(!Number.isInteger(value.exitCode)||value.exitCode<=-2147483648||value.exitCode>2147483647)||
        channel==='summary'&&('text' in value||'channel' in value)||channel!=='summary'&&(value.recordDigest!==expected||value.channel!==channel||typeof value.text!=='string'||value.text.length>2000||
          !Number.isSafeInteger(value.start)||value.start<0||value.start+value.text.length!==value.total||value.total!==value[channel+'Chars']))
        throw Error('回执不属于刚选的原任务、执行与版本，未显示替代记录。');
      setPage(value);
    }catch(e){if(live.current&&pending.current===token){setPage(null);fail(e instanceof Error?e.message:String(e))}}
    finally{if(pending.current===token){pending.current=null;setBusy(false)}}
  };
  return <section aria-label="原 Termux 命令回执" aria-busy={busy}>
    <p className="pw-field-hint">这是手机外部 Termux 的项目命令，不是 App 本机终端。只读手机上已存的文件或任务记录，不会打开 Termux。</p>
    <div className="pw-form-actions"><Button variant="secondary" size="small" disabled={!active||busy} onClick={()=>void read('callback')}>读取本机已存回调</Button>
      <Button variant="ghost" size="small" disabled={!active||busy} onClick={()=>void read('journal')}>查看原任务当时的记录</Button></div>
    {busy&&<p role="status">正在读取所选的本机记录…</p>}{error&&<p role="alert">{error}</p>}
    {page&&<><p role="status"><strong>{page.readSource==='callback'?'本机保存的 Termux 回调记录':'原任务里当时保存的记录'}</strong> · {page.stepId}。
      {page.callbackState==='exit_code'?`Termux 已回报退出码 ${page.exitCode}。${page.exitCode===0?'这不证明已生成或交付产物。':''}`:
       page.callbackState==='incomplete'?'已收到回调，但没有可确认的命令退出结果。':'所选记录没有已确认的 Termux 回调；不据此判断仍在运行或执行失败。'}</p>
      {page.cancelRequested&&<p className="pw-field-hint">手机登记过停止请求，不能证明外部进程已停止。</p>}
      <small>原执行 {row.operationId} · 第 {page.epoch} 轮</small>
      {page.hasJob&&<div className="pw-form-actions">{(['stderr','stdout'] as const).filter(c=>page[c+'Chars']>=0).map(c=><Button key={c} size="small" variant="ghost" disabled={!active||busy}
        onClick={()=>void read(page.readSource,c)}>{c==='stderr'?'查看所留错误输出':'查看所留标准输出'} · {page[c+'Chars']} 字符</Button>)}</div>}
      {typeof page.text==='string'&&<div><p className="pw-field-hint">{page.channel==='stderr'?'错误输出':'标准输出'} · {page.total===0?'此通道记录为空。':page.start>0?'仅显示这份记录的末段。':'已显示这份记录所留的此通道。'}{page.outputTruncated===true&&' 原回调输出有截断，不是完整日志。'}</p>
        {page.text&&<pre className="pw-checkout-review-diff" dir="auto">{shown(page.text)}</pre>}</div>}</>}
  </section>;
}
