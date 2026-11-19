import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {InlineDiff} from './shell-library/InlineDiff';
import {ProjectDraftOwnerRevision} from './ProjectDraftOwnerRevision';

type Diff={taskId:string;available:boolean;draftId:string;projectId:string;checkoutId:string;path:string;runId:string;epoch:number;code:string;sourceChanged:boolean;executionAuthorized:boolean;
 before:string;after:string;beforeSha256:string;afterSha256:string;beforeBytes:number;afterBytes:number};
const action='projectDraft.readDifference',sha=/^[a-f0-9]{64}$/;
const visible=()=>bridge.getState().view as {resumeRevision?:number;section?:string;taskId?:string}|undefined;
const ticket=()=>{const v=visible();return JSON.stringify([bridge.getDocumentEpoch(),v?.resumeRevision,v?.section,v?.taskId])};
export function ProjectDraftDifference({id,active,project,checkout,pin}:{id:string;active:boolean;project:string;checkout:string;pin?:string}){
  const [data,show]=useState<Diff|null>(null),[note,tell]=useState(''),[busy,spin]=useState(false),turn=useRef(0),held=useRef(''),flight=useRef(false);
  const [editorProtected,protect]=useState(false);
  const current=()=>{const s=bridge.getState();return active&&!document.hidden&&!visible()?.section&&visible()?.taskId===id&&s.capabilities.commands.includes(action)};
  const reset=()=>{++turn.current;held.current='';flight.current=false;show(null);tell('');spin(false);protect(false)};
  useEffect(()=>{reset();const leave=()=>{if(held.current&&(!current()||held.current!==ticket()))reset()};
    const off=bridge.subscribe(leave);document.addEventListener('visibilitychange',leave);return()=>{++turn.current;off();document.removeEventListener('visibilitychange',leave)};
  },[id,active,pin,project,checkout]);
  async function read(){
    if(flight.current||!current())return;
    const origin=ticket(),n=++turn.current;flight.current=true;held.current=origin;spin(true);tell('');
    const valid=()=>turn.current===n&&current()&&ticket()===origin;
    try{
      const r=await bridge.command<Diff>(action,{taskId:id});if(!valid())return;
      if(r.taskId!==id)throw Error('不是这次原交办的回执');
      if(r.available===false){tell('还没有实际生成并保存的改稿。模型的文字答复不算差异。');return;}
      if(r.available!==true||r.code!=='project_edit_draft_saved'||r.projectId!==project||r.checkoutId!==checkout||!sha.test(r.draftId)||
        (pin&&pin!==r.draftId)||r.sourceChanged!==false||r.executionAuthorized!==false||typeof r.path!=='string'||
        typeof r.before!=='string'||typeof r.after!=='string'||r.before===r.after||!sha.test(r.beforeSha256)||!sha.test(r.afterSha256))throw Error('原工程、文件或两版文字不能同时核对。');
      show(r);
    }catch(e){if(valid())tell(e instanceof Error?e.message:'这份真实草稿暂不可读。');}finally{if(turn.current===n){flight.current=false;spin(false)}}
  }
  if(!active||!bridge.getState().capabilities.commands.includes(action))return null;
  return <section aria-label="这次交办的独立改稿"><div className="pw-section-heading"><h4>独立改稿</h4><Button size="small" variant="ghost" disabled={busy||editorProtected}
      onClick={()=>data?reset():void read()}>{busy?'读取中…':data?'收起差异':pin?'看文件差异':'查看草稿结果'}</Button></div>
    {note&&<p className="pw-field-hint" role="status">{note}</p>}
    {data&&held.current===ticket()&&current()&&<><strong>{data.path}</strong><p className="pw-field-hint">原工程未写回，没有构建或执行。这里比较的是本次固定的两版文件。</p>
      <InlineDiff before={data.before} after={data.after} beforeLabel="交办时的原文件" afterLabel="独立改稿" versionLabel={data.afterSha256.slice(0,8)} readOnly/>
      <ProjectDraftOwnerRevision key={data.draftId} task={id} root={data.draftId} path={data.path} originalHash={data.afterSha256} protect={protect}/>
      <details className="pw-field-hint"><summary>文件与版本</summary>工程 {data.projectId} · 副本 {data.checkoutId}<br/>原文 {data.beforeBytes} 字节 · {data.beforeSha256}<br/>改稿 {data.afterBytes} 字节 · {data.afterSha256}</details></>}
  </section>;
}
