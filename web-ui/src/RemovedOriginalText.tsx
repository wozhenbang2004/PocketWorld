import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {bridge} from './bridge';
import {verifiedRemovedText,type RemovedPosition} from './removed-original-text';
import type {RevisionPin} from './formal-history-compare';

type Data=Record<string,any>;
const command='projects.removedFiles.text';
export function RemovedOriginalText({versions,removed,choice,handle,project}:{versions:RevisionPin[];removed:RemovedPosition;choice:string;handle:string;project:string}){
  const [view,show]=useState<{v:RevisionPin;state:'complete'|'limited';text?:string}|null>(null),[notice,tell]=useState(''),[busy,spin]=useState(false);
  const alive=useRef(true),pending=useRef(false),serial=useRef(0);
  const where=()=>bridge.getDocumentEpoch()+':'+String(((bridge.getState().view||{}) as Data).resumeRevision);
  const visible=()=>{const s=bridge.getState(),v=(s.view||{}) as Data;return !document.hidden&&!v.section&&!v.taskId&&!s.state.filePath&&s.state.projectId===project};
  function clear(){++serial.current;pending.current=false;show(null);tell('');spin(false)}
  useEffect(()=>{alive.current=true;let win=where();const check=()=>{const now=where();if(!visible()||win!==now)clear();win=now};
    const off=bridge.subscribe(check);document.addEventListener('visibilitychange',check);return()=>{alive.current=false;++serial.current;off();document.removeEventListener('visibilitychange',check)}},[choice,handle,removed.revisionId,project]);
  const eligible=versions.filter(v=>v.kind==='text'&&v.version<removed.version);
  if(!eligible.length||!bridge.getState().capabilities.commands.includes(command))return null;
  async function inspect(v:RevisionPin){
    if(pending.current||!visible()||!eligible.includes(v))return;const n=++serial.current,win=where();pending.current=true;show(null);tell('');spin(true);
    const current=()=>alive.current&&serial.current===n&&where()===win&&visible();
    try{const r=await bridge.command<Data>(command,{projectId:project,choice,handle,index:removed.index,version:v.version,revisionId:v.revisionId});
      if(!current())return;const value=await verifiedRemovedText(r,v,removed,choice,handle);if(!current())return;
      if(!value)throw Error('没有取得所选原修订的完整文字，未显示替代版本。');show({v,...value});
    }catch(e){if(current())tell(e instanceof Error?e.message:'原文字尚未读到。')}finally{if(current()){pending.current=false;spin(false)}}
  }
  return <details onToggle={e=>{if(!e.currentTarget.open)clear()}}><summary>查看删除前的文字</summary><p className="pw-field-hint">只打开所选的原版本，不恢复文件。</p>
    <div className="pw-form-actions" role="group" aria-label="选择原文字版本">{eligible.map(v=><Button key={v.revisionId} size="small" variant="ghost" disabled={busy} aria-pressed={view?.v.revisionId===v.revisionId} onClick={()=>void inspect(v)}>v{v.version} · {v.bytes.toLocaleString('zh-CN')} 字节</Button>)}</div>
    {busy&&<p role="status">正在核对原字节…</p>}{notice&&<p role="alert" className="pw-field-error">{notice}</p>}
    {view&&(view.state==='limited'?<p role="status">v{view.v.version} 超过现有编辑器的完整文字上限。只保留版本依据，未把截断内容冒充原件。</p>:
      <section aria-label={'原文字 v'+view.v.version}><p>已核对 v{view.v.version} · {view.v.bytes} 字节</p>{view.text===''?<p>这版是完整的空文字。</p>:<pre className="pw-file-revision-content" dir="auto">{view.text}</pre>}</section>)}
  </details>;
}
