import { useRef, useState } from 'react';
import { Button } from './forms-library';

const reasons: Record<string,string> = {
  admission_unverified: '还没有核对到原主机的接单回执。可先打开原主机工作，不会再交办。',
  not_ready: '这项原工作还没有交回最终文字，不会重跑。',
  no_verified_text: '未能取回可核对的最终文字。状态或日志不是成果。',
  saved: '原结果已保存。',
  saved_reader_limited: '原结果已存手机。此历史窗口已满，可刷新后重新打开这条原话。'
};

/** Only these exact original bytes can appear here. Plain text can't load remote markup or images. */
export function OriginalPrivateHostAnswer({result,read}:{result:{text?:string};read?:()=>Promise<{stage?:string;executionAuthorized?:boolean}>}){
  const [busy,setBusy]=useState(false),[notice,setNotice]=useState('');
  const reading=useRef(false);
  if(typeof result.text==='string'&&result.text)
    return <section aria-label="原主机结果"><p className="pw-field-hint">原主机结果 · 已存手机</p><div className="pw-user-text">{result.text}</div></section>;
  if(!read)return null;
  const start=async()=>{
    if(reading.current)return;
    reading.current=true;setBusy(true);setNotice('');
    try{
      const reply=await read();
      setNotice(reply?.executionAuthorized===false&&typeof reply.stage==='string'&&Object.hasOwn(reasons,reply.stage)
        ?reasons[reply.stage]:'未能核对这份原结果，没有重跑。');
    }catch{setNotice('未能核对这份原结果，没有重跑。');}
    finally{reading.current=false;setBusy(false);}
  };
  return <section aria-label="原主机结果">
    <Button variant="secondary" size="small" disabled={busy} onClick={()=>void start()}>{busy?'正在取回…':'取回原主机结果'}</Button>
    {notice&&<p className="pw-field-hint" role="status">{notice}</p>}
  </section>;
}
