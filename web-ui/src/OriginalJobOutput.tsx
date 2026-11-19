import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {receivedOutputParts} from './remote-output-excerpt';
import {OriginalJobOutputFind} from './OriginalJobOutputFind';
import type {OutputHit} from './remote-output-find';
import './original-job-output.css';

type Row=Record<string,any>;
export function OriginalJobOutput({result,source}:{result:Row;source:Row}){
  const parts=receivedOutputParts(result,source),head=typeof result.output==='string'?result.output:'';
  // A known process/driver failure makes the received end useful; no guessing from words in untrusted output.
  const [end,setEnd]=useState(result.status==='failed'||Number.isInteger(result.exitCode)&&result.exitCode!==0),[find,setFind]=useState(false);
  const [location,setLocation]=useState<(OutputHit&{needle:string;head:string;tail?:string})|null>(null),mark=useRef<HTMLElement>(null);
  const part=end&&parts?'tail':'head',text=part==='tail'?parts!.tail:head;
  const exact=location&&location.head===head&&location.tail===parts?.tail&&location.part===part&&text.slice(location.from,location.to)===location.needle?location:null;
  useEffect(()=>{if(location&&mark.current){mark.current.focus({preventScroll:true});mark.current.scrollIntoView({block:'nearest'});}},[location]);
  const segment=(last:boolean)=>{setLocation(null);setEnd(last)};
  const searchable=head.length+(parts?.tail.length||0)>=1024;
  return <section aria-label="原作业已收到的输出"><h4>输出</h4>{(parts||searchable)&&<div className="pw-form-actions" role="group" aria-label="已收到的输出工具">
    {parts&&<><Button size="small" variant={end?'ghost':'secondary'} aria-pressed={!end} onClick={()=>segment(false)}>已收开头</Button>
      <Button size="small" variant={end?'secondary':'ghost'} aria-pressed={end} onClick={()=>segment(true)}>已收末段</Button></>}
    {searchable&&<Button variant="ghost" size="small" aria-expanded={find} onClick={()=>{setFind(!find);setLocation(null)}}>{find?'收起查找':'查找输出'}</Button>}</div>}
    {find&&<OriginalJobOutputFind head={head} tail={parts?.tail} clipped={result.outputTruncated===true||result.outputViewTruncated===true}
      locate={(h,needle)=>{if((h.part==='tail'?parts?.tail:head)?.slice(h.from,h.to)!==needle)return;setEnd(h.part==='tail');setLocation({...h,needle,head,tail:parts?.tail});}}/>}
    {parts?<p className="pw-field-hint">开头和末段不连续，中间内容未显示。{result.outputTruncated===true?'主机也截断了输出，这不是已证明的进程最后一行。':'这里只显示这次原主机查询已经返回的文字。'}</p>:
      (result.outputViewTruncated||result.outputTruncated)&&<p className="pw-field-hint">这份输出有截断。</p>}
    <pre dir="auto" aria-label={part==='tail'?'此次已收输出的末段':'此次已收输出的开头'}>{exact?<>{text.slice(0,exact.from)}<mark ref={mark} tabIndex={-1} className="pw-job-output-mark">{text.slice(exact.from,exact.to)}</mark>{text.slice(exact.to)}</>:text}</pre></section>;
}
