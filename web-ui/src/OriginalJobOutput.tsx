import {useState} from 'react';
import {Button} from './forms-library';
import {receivedOutputParts} from './remote-output-excerpt';

type Row=Record<string,any>;
export function OriginalJobOutput({result,source}:{result:Row;source:Row}){
  const parts=receivedOutputParts(result,source);
  // A known process/driver failure makes the received end useful; no guessing from words in untrusted output.
  const [end,setEnd]=useState(result.status==='failed'||Number.isInteger(result.exitCode)&&result.exitCode!==0);
  if(!parts)return <section><h4>输出</h4><pre>{result.output}</pre>{(result.outputViewTruncated||result.outputTruncated)&&<p className="pw-field-hint">这份输出有截断。</p>}</section>;
  return <section aria-label="原作业已收到的输出"><h4>输出</h4><div className="pw-form-actions" role="group" aria-label="选择已收到的输出片段">
    <Button size="small" variant={end?'ghost':'secondary'} aria-pressed={!end} onClick={()=>setEnd(false)}>已收开头</Button>
    <Button size="small" variant={end?'secondary':'ghost'} aria-pressed={end} onClick={()=>setEnd(true)}>已收末段</Button></div>
    <p className="pw-field-hint">开头和末段不连续，中间内容未显示。{result.outputTruncated===true?'主机也截断了输出，这不是已证明的进程最后一行。':'这里只显示这次原主机查询已经返回的文字。'}</p>
    <pre dir="auto" aria-label={end?'此次已收输出的末段':'此次已收输出的开头'}>{end?parts.tail:result.output}</pre></section>;
}
