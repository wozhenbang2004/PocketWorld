import {useState} from 'react';
import {Button,TextField} from './forms-library';
import {findReceivedOutput,outputHitContext,type OutputHit} from './remote-output-find';

export function OriginalJobOutputFind({head,tail,clipped,locate}:{head:string;tail?:string;clipped:boolean;locate:(h:OutputHit,n:string)=>void}){
  const [input,setInput]=useState(''),[found,setFound]=useState<(ReturnType<typeof findReceivedOutput>&{head:string;tail?:string})|null>(null),[index,move]=useState(0);
  function search(){if(!input.trim()||input.length>128)return;setFound({...findReceivedOutput(head,tail,input),head,tail});move(0);}
  // A status poll may return identical text with a new capture time; only an actual change retires the hit list.
  const changed=!!found&&(found.head!==head||found.tail!==tail);
  const h=changed?undefined:found?.hits[index],ctx=h&&outputHitContext(h.part==='tail'?tail||'':head,h,input);
  return <section className="pw-job-output-search" aria-label="查找这份已收输出"><TextField label="查找输出中的文字" value={input} maxLength={128}
    onChange={value=>{setInput(value);setFound(null);move(0)}} onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();search()}}}
    placeholder="错误码、文件名或原文" hint="按原字面查找，区分大小写。不联系主机。"/>
    <Button variant="secondary" size="small" disabled={!input.trim()} onClick={search}>查找</Button>
    {changed&&<p role="status" className="pw-field-hint">已收输出已更新。可保留查找词，重新查找当前文字。</p>}
    {found&&!changed&&<><p role="status" className="pw-field-hint">{found.count?`找到 ${found.count.toLocaleString('zh-CN')} 处，当前第 ${index+1} 处${tail!==undefined?(h?.part==='tail'?' · 已收末段':' · 已收开头'):''}。`:'已收片段中没有找到。'}
      {found.count>found.hits.length&&`最多逐处定位前 ${found.hits.length} 处，可输入更具体的文字。`}{clipped&&'未显示的内容不在查找范围内。'}</p>
      {h&&ctx&&<><pre aria-label="命中的原文片段"><bdi>{ctx.before}<mark className="pw-job-output-mark">{ctx.needle}</mark>{ctx.after}</bdi></pre>
        <div className="pw-form-actions"><Button variant="ghost" size="small" disabled={index===0} onClick={()=>move(index-1)}>上一处</Button>
          <Button variant="ghost" size="small" disabled={index+1>=found.hits.length} onClick={()=>move(index+1)}>下一处</Button>
          <Button variant="secondary" size="small" onClick={()=>locate(h,input)}>在原输出定位</Button></div></>}</>}
  </section>;
}
