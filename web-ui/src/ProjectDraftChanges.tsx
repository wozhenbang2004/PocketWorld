import {useMemo,useState} from 'react';
import {diffLines} from 'diff';
import {Button} from './forms-library';
import {InlineDiff} from './shell-library/InlineDiff';
import './project-draft-changes.css';

type Row={kind:'same'|'add'|'remove';before?:number;after?:number;body:string;ending:'LF'|'CRLF'|'无换行'}|{kind:'skip';count:number};
function lineTokens(value:string){return value.match(/[^\n]*\n|[^\n]+$/g)??[];}
function focusedChanges(before:string,after:string){
  const groups=diffLines(before,after,{timeout:45,maxEditLength:1800,ignoreWhitespace:false});
  if(!groups)return null;
  let a=1,b=1,added=0,removed=0;
  const rows:Row[]=[];
  for(const [i,group] of groups.entries()){
    const lines=lineTokens(group.value),n=lines.length;
    const kind=group.added?'add':group.removed?'remove':'same';
    if(kind==='add')added+=n;
    if(kind==='remove')removed+=n;
    let omitted=0;
    for(const [j,text] of lines.entries()){
      const beforeNumber=kind==='add'?undefined:a++,afterNumber=kind==='remove'?undefined:b++;
      if(kind==='same'&&n>4&&!(i>0&&j<2)&&!(i<groups.length-1&&j>=n-2)){omitted++;continue}
      if(omitted){rows.push({kind:'skip',count:omitted});omitted=0}
      const ending=text.endsWith('\r\n')?'CRLF':text.endsWith('\n')?'LF':'无换行';
      rows.push({kind,before:beforeNumber,after:afterNumber,body:ending==='无换行'?text:text.slice(0,ending==='CRLF'?-2:-1),ending});
    }
    if(omitted)rows.push({kind:'skip',count:omitted});
  }
  return {rows,added,removed,whitespaceOnly:before!==after&&before.replace(/\s/gu,'')===after.replace(/\s/gu,'')};
}

/** Lead with the changes on a phone. The same complete, byte-verified texts stay available below. */
export function ProjectDraftChanges({before,after,version}:{before:string;after:string;version:string}){
  const summary=useMemo(()=>focusedChanges(before,after),[before,after]);
  const [limit,setLimit]=useState(80),[full,setFull]=useState(false);
  return <div className="pw-draft-changes">
    {summary?<><p className="pw-draft-change-count" aria-label={`新增 ${summary.added} 行，移除 ${summary.removed} 行`}>
      <span>+{summary.added}</span><span>−{summary.removed}</span>{summary.whitespaceOnly&&<small>只改了空白或换行</small>}</p>
      <div className="pw-draft-lines" role="list" aria-label="改动行及相邻内容">{summary.rows.slice(0,limit).map((row,i)=>row.kind==='skip'?
        <div key={i} role="listitem" className="pw-draft-skip">⋯ {row.count} 行未改动</div>:
        <div key={i} role="listitem" className="pw-draft-line" data-kind={row.kind}>
          <span className="pw-draft-line-no" aria-hidden="true">{row.after??row.before}</span>
          <span className="pw-draft-line-sign" aria-label={row.kind==='add'?'新增':row.kind==='remove'?'移除':undefined}>{row.kind==='add'?'+':row.kind==='remove'?'−':' '}</span>
          <code>{summary.whitespaceOnly&&row.kind!=='same'?row.body.replace(/ /g,'·').replace(/\t/g,'⇥'):row.body||' '}{row.kind!=='same'&&(summary.whitespaceOnly||row.ending!=='LF')&&<span className="pw-draft-line-end"> {row.ending}</span>}</code>
        </div>)}</div>
      {limit<summary.rows.length&&<Button size="small" variant="ghost" onClick={()=>setLimit(n=>n+80)}>显示更多改动</Button>}
    </>:<p className="pw-field-hint">改动较多，下面仍可对照两份完整文件。</p>}
    <details className="pw-draft-full" onToggle={e=>setFull(e.currentTarget.open)}>
      <summary>对照两份完整文件</summary>
      {full&&<InlineDiff before={before} after={after} beforeLabel="交办时的原文件" afterLabel="独立改稿" versionLabel={version} readOnly/>}
    </details>
  </div>;
}
