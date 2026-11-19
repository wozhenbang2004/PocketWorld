import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,TextAreaField} from './forms-library';
import {InlineDiff} from './shell-library/InlineDiff';

type Revision={taskId:string;draftId:string;path:string;revisionId:string;parentId?:string;ordinal:number;text:string;sha256:string;bytes:number;
  editHandle:string;retained:boolean;sourceChanged:boolean;executionAuthorized:boolean};
const EDIT='projectDraft.editLocal',KEEP='projectDraft.keepRevision',CLOSE='projectDraft.closeEditor',sha=/^[a-f0-9]{64}$/;
function view(){return bridge.getState().view as {section?:string;taskId?:string;resumeRevision?:number}|undefined;}
function ticket(){const v=view();return JSON.stringify([bridge.getDocumentEpoch(),v?.section,v?.taskId,v?.resumeRevision]);}

/** Continuation of an actual retained after-text, not saving into the original project or replacing the AI's receipt. */
export function ProjectDraftOwnerRevision({task,root,path,originalHash,protect}:{task:string;root:string;path:string;originalHash:string;protect:(value:boolean)=>void}){
  const [head,setHead]=useState<Revision|null>(null),[text,change]=useState(''),[note,state]=useState(''),[busy,spin]=useState(false);
  const [compare,preview]=useState(false),[discard,ask]=useState(false),live=useRef(false),active=useRef(''),generation=useRef(0),request=useRef(false);
  const can=()=>!document.hidden&&!view()?.section&&view()?.taskId===task&&[EDIT,KEEP,CLOSE].every(k=>bridge.getState().capabilities.commands.includes(k));
  useEffect(()=>{protect(busy||!!head&&text!==head.text);return()=>protect(false)},[busy,head,text,protect]);
  function reset(){++generation.current;active.current='';request.current=false;setHead(null);change('');state('');spin(false);preview(false);ask(false)}
  useEffect(()=>{live.current=true;reset();const leave=()=>{if(active.current&&(!can()||ticket()!==active.current))reset()};
    const off=bridge.subscribe(leave);document.addEventListener('visibilitychange',leave);return()=>{live.current=false;++generation.current;off();document.removeEventListener('visibilitychange',leave)};
  },[task,root,path,originalHash]);
  async function run(keep:boolean){
    if(request.current||!can()||keep&&(!head||head.text===text||active.current!==ticket()))return;
    const token=ticket(),turn=++generation.current,prior=head,input=text;active.current=token;request.current=true;spin(true);state('');ask(false);
    const valid=()=>live.current&&turn===generation.current&&can()&&active.current===ticket();
    try{
      const r=await bridge.command<Revision>(keep?KEEP:EDIT,{taskId:task,draftId:root,...(keep?{parentId:prior!.revisionId,editHandle:prior!.editHandle,text:input}:{})});if(!valid())return;
      if(r.taskId!==task||r.draftId!==root||r.path!==path||typeof r.editHandle!=='string'||!r.editHandle||!sha.test(r.revisionId)||!sha.test(r.sha256)||
        !Number.isSafeInteger(r.bytes)||r.bytes<0||r.bytes>24576||typeof r.text!=='string'||new TextEncoder().encode(r.text).length!==r.bytes||
        !Number.isInteger(r.ordinal)||r.ordinal<0||r.ordinal>8||r.retained!==keep||r.sourceChanged!==false||r.executionAuthorized!==false||
        r.ordinal===0&&(r.revisionId!==root||r.sha256!==originalHash)||keep&&(r.parentId!==prior!.revisionId||r.ordinal!==prior!.ordinal+1||r.text!==input))
        throw Error('没有核对到这份原稿的准确后续版本，未换成其他文件。');
      setHead(r);change(r.text);preview(false);state(keep?'已经从手机回读这一稿。原工程和最初的差异都没变。':'');
    }catch(e){if(valid())state(e instanceof Error?e.message:'暂时不能核对，输入仍在此处，没有自动重交。');}
    finally{if(live.current&&turn===generation.current){request.current=false;spin(false)}}
  }
  function close(){if(head&&can())void bridge.command(CLOSE,{taskId:task,editHandle:head.editHandle}).catch(()=>{});reset()}
  if(!can())return null;
  if(!head)return <div><Button size="small" variant="secondary" disabled={busy} onClick={()=>void run(false)}>{busy?'读取独立稿…':'接着改这一稿'}</Button>{note&&<p className="pw-field-hint" role="status">{note}</p>}</div>;
  const dirty=text!==head.text,count=new TextEncoder().encode(text).length;
  return <section aria-label="继续编辑独立稿"><h4>{head.ordinal===0?'从原改稿继续':'你的第 '+head.ordinal+' 稿'}</h4>
    <p className="pw-field-hint">只保留在这个原任务里，不写入工程，也不发给模型。</p>
    <TextAreaField label={path} value={text} onChange={v=>{change(v);ask(false);state('')}} isReadOnly={busy||head.ordinal>=8} className="pw-code-editor"/>
    <div className="pw-form-actions">{dirty&&<Button size="small" variant="ghost" onClick={()=>preview(!compare)}>{compare?'收起比较':'对比上一稿'}</Button>}
      <Button size="small" variant="secondary" disabled={busy||!dirty||count>24576||head.ordinal>=8} onClick={()=>void run(true)}>{busy?'正在核对…':'保留为新稿'}</Button>
      <Button size="small" variant="ghost" disabled={busy} onClick={()=>dirty?ask(true):close()}>结束编辑</Button></div>
    {discard&&<div role="group" aria-label="放弃尚未保留的文字"><p>这次输入还没保留。</p><Button size="small" variant="secondary" onClick={()=>ask(false)}>留在这里</Button>
      <Button size="small" variant="ghost" onClick={close}>放弃本次输入</Button></div>}
    {compare&&dirty&&<InlineDiff before={head.text} after={text} beforeLabel="起始稿" afterLabel="当前输入" versionLabel={head.sha256.slice(0,8)} readOnly/>}
    {count>24576&&<p className="pw-field-error">本次完整稿超过 24 KiB；不会截断保存。</p>}
    {head.ordinal>=8&&<p className="pw-field-hint">这份任务已保留八个后续版本。当前完整稿仍可阅读。</p>}
    {(note||dirty)&&<p role="status" className="pw-field-hint">{note||'尚未保留，请在离开原任务前保存。'}</p>}
  </section>;
}
