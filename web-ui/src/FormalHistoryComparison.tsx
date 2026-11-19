import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {originalHistoryPins,originalHistoryDifference,type RevisionPin} from './formal-history-compare';
import './formal-history-compare.css';

const read='projects.historyComparison';
type Data=Record<string,any>;
export function FormalHistoryComparison({rows,project,path,version}:{rows:unknown;project:string;path:string;version:number}){
  if(!Array.isArray(rows)||rows.length<2||rows.length>8||!rows.every(r=>Number.isSafeInteger(r?.version)&&r.version>0)||!Number.isSafeInteger(version)||version<1||!bridge.getState().capabilities.commands.includes(read))return null;
  const versions=rows.map(r=>r.version).join(',');
  return <Request key={JSON.stringify([project,path,version,versions])} project={project} path={path} version={version} versions={versions}/>;
}
function Request({project,path,version,versions}:{project:string;path:string;version:number;versions:string}){
  const [pins,view]=useState<RevisionPin[]|null>(null),[error,tell]=useState(''),[pending,spin]=useState(false),generation=useRef(0),flight=useRef(false);
  const where=()=>bridge.getDocumentEpoch()+':'+String(((bridge.getState().view||{}) as Data).resumeRevision);
  const visible=()=>{const s=bridge.getState(),v=(s.view||{}) as Data;return !document.hidden&&!v.section&&!v.taskId&&s.state.projectId===project&&s.state.filePath===path&&s.state.fileVersion===version;};
  const hide=()=>{++generation.current;flight.current=false;view(null);tell('');spin(false)};
  useEffect(()=>{let ticket=where();const check=()=>{const next=where();if(!visible()||ticket!==next)hide();ticket=next};const off=bridge.subscribe(check);document.addEventListener('visibilitychange',check);
    return()=>{++generation.current;off();document.removeEventListener('visibilitychange',check)}},[project,path,version,versions]);
  async function inspect(){
    if(flight.current||!visible())return;const n=++generation.current,window=where();flight.current=true;spin(true);tell('');
    const current=()=>n===generation.current&&window===where()&&visible();
    try{const v=await bridge.command<Data>(read,{projectId:project,path,version,versions});if(!current())return;
      const found=originalHistoryPins(v?.items,project,path);
      if(!v||v.format!=='pocketworld.formal-file-observed-history.v1'||v.projectId!==project||v.path!==path||v.version!==version||v.versions!==versions||
        found.length<2||v.items.map((r:Data)=>r.version).join(',')!==versions)throw Error('未取得这个原文件的完整版本依据。');
      view(found);
    }catch(e){if(current())tell(e instanceof Error?e.message:'尚未取得版本依据，可重新读取原文件历史。')}
    finally{if(current()){flight.current=false;spin(false)}}
  }
  return <div><Button size="small" variant="secondary" aria-expanded={!!pins} disabled={pending} onClick={()=>pins?hide():void inspect()}>{pins?'收起版本对照':pending?'正在核对两版依据':'比较两个保留版本'}</Button>
    {error&&<p role="alert" className="pw-field-error">{error}</p>}{pins&&<ObservedFormalHistoryPair pins={pins}/>}</div>;
}
export function ObservedFormalHistoryPair({pins}:{pins:RevisionPin[]}){
  const [first,chooseA]=useState(pins.length-2),[last,chooseB]=useState(pins.length-1),[editing,edit]=useState<'a'|'b'|null>(null);
  const a=pins[first],b=pins[last],result=originalHistoryDifference(a,b);
  const row=(p:RevisionPin)=><div key={p.revisionId}><dt>v{p.version} · {p.kind==='tombstone'?'删除记录':(p.kind==='binary'?'二进制':'文字')+' · '+p.bytes.toLocaleString('zh-CN')+' 字节'}</dt><dd>修订 {p.revisionId}{p.kind!=='tombstone'&&<><br/>SHA-256 {p.sha256}</>}</dd></div>;
  return <section className="pw-retained-pair" aria-label="同一正式文件的版本对照"><div className="pw-form-actions" role="group" aria-label="选择要对照的正式版本">
      <Button size="small" variant="ghost" aria-expanded={editing==='a'} onClick={()=>edit(editing==='a'?null:'a')}>从 v{a.version}</Button>
      <span aria-hidden="true">→</span><Button size="small" variant="ghost" aria-expanded={editing==='b'} onClick={()=>edit(editing==='b'?null:'b')}>到 v{b.version}</Button></div>
      {editing&&<div className="pw-form-actions" role="group" aria-label={editing==='a'?'选择较早版本':'选择较晚版本'}>{pins.map((p,i)=>
        (editing==='a'?i<last:i>first)&&<Button key={p.revisionId} size="small" variant="ghost" aria-pressed={i===(editing==='a'?first:last)} onClick={()=>{if(editing==='a')chooseA(i);else chooseB(i);edit(null)}}>v{p.version}</Button>)}</div>}
      <p role="status">{result||'这两份版本身份不能一致核对。'}{a.kind!==b.kind&&a.kind!=='tombstone'&&b.kind!=='tombstone'?' 文件记录的类型也发生了变化。':''}</p>
      {b.version>a.version+1&&<p className="pw-field-hint">只对照这两个版本，不判断中间版本。</p>}
      <details><summary>项目、路径与两版依据</summary><p>项目 {a.projectId}<br/><bdi>{a.path}</bdi></p><dl>{row(a)}{row(b)}</dl>
        <p className="pw-field-hint">只比较本次读到的两个正式记录，不含编辑草稿，也不会替换文件。</p></details>
    </section>;
}
