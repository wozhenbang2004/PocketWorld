import {useEffect,useId,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,SettingsDialog,TextAreaField} from './forms-library';
import {registerOverlayBack} from './overlay-back';
import {ProjectDraftFileCopy} from './ProjectDraftFileCopy';
import {ProjectDraftWorkCopy} from './ProjectDraftWorkCopy';
import {InlineDiff} from './shell-library/InlineDiff';

type Revision={taskId:string;draftId:string;path:string;revisionId:string;parentId?:string;ordinal:number;text:string;sha256:string;bytes:number;
  editHandle:string;retained:boolean;sourceChanged:boolean;executionAuthorized:boolean};
export type SavedPin=Pick<Revision,'taskId'|'draftId'|'path'|'revisionId'|'ordinal'|'sha256'|'bytes'>&{window:string};
const EDIT='projectDraft.editLocal',KEEP='projectDraft.keepRevision',CLOSE='projectDraft.closeEditor',sha=/^[a-f0-9]{64}$/;
function view(){return bridge.getState().view as {section?:string;taskId?:string;resumeRevision?:number}|undefined;}
function ticket(){const v=view();return JSON.stringify([bridge.getDocumentEpoch(),v?.resumeRevision,v?.section,v?.taskId]);}
export {ticket as projectDraftWindowTicket};

/** Continuation of an actual retained after-text, not saving into the original project or replacing the AI's receipt. */
export function ProjectDraftOwnerRevision({task,root,path,originalHash,protect,retainedHead,leaveRequested,onDismissLeave}:{task:string;root:string;path:string;originalHash:string;protect:(value:boolean)=>void;
  retainedHead?:SavedPin;leaveRequested:boolean;onDismissLeave:()=>void}){
  const editSource=useId();
  const [head,setHead]=useState<Revision|null>(null),[text,change]=useState(''),[note,state]=useState(''),[busy,spin]=useState(false);
  const [fileLock,lockFile]=useState(false),[workLock,lockWork]=useState(false);
  const [compare,preview]=useState(false),[discard,ask]=useState(false),live=useRef(false),active=useRef(''),generation=useRef(0),request=useRef(false);
  const saved=useRef<SavedPin|null>(retainedHead??null),editorHeading=useRef<HTMLHeadingElement>(null);
  const can=()=>!document.hidden&&!view()?.section&&view()?.taskId===task&&[EDIT,KEEP,CLOSE].every(k=>bridge.getState().capabilities.commands.includes(k));
  const editing=!!head,dirty=!!head&&text!==head.text,count=head?new TextEncoder().encode(text).length:0;
  const protectedNow=busy||fileLock||workLock||dirty,leaveOpen=leaveRequested&&protectedNow&&can();
  const mayKeep=()=>!!head&&dirty&&active.current===ticket()&&!busy&&!fileLock&&!workLock&&count<=24576&&head.ordinal<8;
  useLayoutEffect(()=>protect(protectedNow),[protect,protectedNow]);
  useLayoutEffect(()=>()=>protect(false),[protect]);
  useLayoutEffect(()=>{if(leaveOpen)return registerOverlayBack(onDismissLeave)},[leaveOpen,onDismissLeave]);
  useLayoutEffect(()=>{if(editing)editorHeading.current?.focus({preventScroll:true})},[editing]);
  function reset(forget=true){if(forget)saved.current=null;++generation.current;active.current='';request.current=false;setHead(null);change('');state('');spin(false);preview(false);ask(false)}
  function savedHere(){const pin=saved.current;return pin&&pin.taskId===task&&pin.draftId===root&&pin.path===path&&pin.window===ticket()?pin:null}
  useEffect(()=>{live.current=true;saved.current=retainedHead??null;reset(false);const leave=()=>{const opened=active.current||saved.current?.window;if(opened&&(!can()||ticket()!==opened))reset()};
    const off=bridge.subscribe(leave);document.addEventListener('visibilitychange',leave);return()=>{live.current=false;++generation.current;off();document.removeEventListener('visibilitychange',leave)};
  },[task,root,path,originalHash,retainedHead]);
  async function run(keep:boolean){
    if(request.current||!can()||keep&&!mayKeep())return;
    const token=ticket(),turn=++generation.current,prior=head,input=text,expected=keep?null:savedHere();active.current=token;request.current=true;spin(true);state('');ask(false);
    const valid=()=>live.current&&turn===generation.current&&can()&&active.current===ticket();
    try{
      const r=await bridge.command<Revision>(keep?KEEP:EDIT,{taskId:task,draftId:root,...(keep?{parentId:prior!.revisionId,editHandle:prior!.editHandle,text:input}:{})});if(!valid())return;
      if(r.taskId!==task||r.draftId!==root||r.path!==path||typeof r.editHandle!=='string'||!r.editHandle||!sha.test(r.revisionId)||!sha.test(r.sha256)||
        !Number.isSafeInteger(r.bytes)||r.bytes<0||r.bytes>24576||typeof r.text!=='string'||new TextEncoder().encode(r.text).length!==r.bytes||
        !Number.isInteger(r.ordinal)||r.ordinal<0||r.ordinal>8||r.retained!==keep||r.sourceChanged!==false||r.executionAuthorized!==false||
        r.ordinal===0&&(r.revisionId!==root||r.sha256!==originalHash)||keep&&(r.parentId!==prior!.revisionId||r.ordinal!==prior!.ordinal+1||r.text!==input))
        throw Error('没有核对到这份原稿的准确后续版本，未换成其他文件。');
      if(expected&&(r.revisionId!==expected.revisionId||r.ordinal!==expected.ordinal||r.sha256!==expected.sha256||r.bytes!==expected.bytes)){
        void bridge.command(CLOSE,{taskId:task,editHandle:r.editHandle}).catch(()=>{});
        // Only a confirmed newer head retires the named action; an unknown read can be retried.
        if(r.ordinal>expected.ordinal){saved.current=null;throw Error('已有更新的保留稿，未打开其他版本。可点「继续编辑独立稿」核对当前稿。');}
        throw Error(`暂不能核对第 ${expected.ordinal} 稿，没有打开其他版本。`);
      }
      if(r.ordinal>0)saved.current={taskId:r.taskId,draftId:r.draftId,path:r.path,revisionId:r.revisionId,ordinal:r.ordinal,sha256:r.sha256,bytes:r.bytes,window:token};
      setHead(r);change(r.text);preview(false);state(keep?'已经从手机回读这一稿。原工程和最初的差异都没变。':'');
    }catch(e){if(valid())state(e instanceof Error?e.message:'暂时不能核对，输入仍在此处，没有自动重交。');}
    finally{if(live.current&&turn===generation.current){request.current=false;spin(false)}}
  }
  function close(){if(head&&can())void bridge.command(CLOSE,{taskId:task,editHandle:head.editHandle}).catch(()=>{});reset(false)}
  if(!can())return null;
  const keepAction=<Button size="small" variant="secondary" disabled={!mayKeep()} onClick={()=>void run(true)}>{busy?'正在核对…':'保留为新稿'}</Button>;
  const limitMessage=count>24576?'本次完整稿超过 24 KiB；不会截断保存。':head&&head.ordinal>=8?'这份任务已保留八个后续版本。当前完整稿仍可阅读。':'';
  const leaveDialog=<SettingsDialog isOpen={leaveOpen} title={dirty?'保留这份独立稿？':'先处理当前稿'} closeLabel="回到编辑" onClose={onDismissLeave}>
    <p>{dirty?'可以保留为原任务的新稿。保留后仍留在这里，不会写回工程。':'请等当前稿件操作核对完成；不会替你离开或放弃。'}</p>
    {limitMessage&&<p className="pw-field-hint">{limitMessage}</p>}
    {note&&<p role="status" className="pw-field-hint">{note}</p>}
    <div className="pw-form-actions">{dirty&&keepAction}<Button variant={dirty?'ghost':'secondary'} onClick={onDismissLeave}>回到当前稿</Button></div>
  </SettingsDialog>;
  const retained=savedHere();
  if(!head)return <div className="pw-draft-actions">
    <Button size="small" variant="secondary" disabled={busy||fileLock||workLock} aria-describedby={editSource} onClick={()=>void run(false)}>{busy?'正在读取独立稿…':retained?`继续编辑第 ${retained.ordinal} 稿`:'继续编辑独立稿'}</Button>
    <p id={editSource} className="pw-field-hint">{retained?'上方仍是最初的差异；核对后才会打开这一稿。':'只编辑独立稿，不改原项目。已有保留稿时打开最后一稿，否则从当前改稿开始。'}</p>
    <ProjectDraftFileCopy task={task} root={root} revision={root} sha={originalHash} path={path} ordinal={0} disabled={busy||workLock} lock={lockFile}/>
    <ProjectDraftWorkCopy task={task} root={root} revision={root} sha={originalHash} path={path} ordinal={0} disabled={busy||fileLock} lock={lockWork}/>
    {!leaveOpen&&note&&<p className="pw-field-hint" role="status">{note}</p>}{leaveDialog}</div>;
  return <section aria-label="继续编辑独立稿"><h4 ref={editorHeading} tabIndex={-1}>{head.ordinal===0?'编辑这份独立改稿':'编辑我保留的第 '+head.ordinal+' 稿'}</h4>
    <p className="pw-field-hint">只保留在这个原任务里，不写入工程，也不发给模型。</p>
    <TextAreaField label={`独立稿 · ${path}`} value={text} onChange={v=>{change(v);ask(false);state('')}} isReadOnly={busy||fileLock||workLock||head.ordinal>=8} className="pw-code-editor"/>
    <div className="pw-form-actions">{dirty&&<Button size="small" variant="ghost" onClick={()=>preview(!compare)}>{compare?'收起比较':'对比上一稿'}</Button>}
      {keepAction}
      <Button size="small" variant="ghost" disabled={busy||fileLock||workLock} onClick={()=>dirty?ask(true):close()}>结束编辑</Button></div>
    {!dirty&&<ProjectDraftFileCopy key={head.revisionId} task={task} root={root} revision={head.revisionId} sha={head.sha256} path={path} ordinal={head.ordinal} disabled={busy||workLock} lock={lockFile}/>}
    {!dirty&&<ProjectDraftWorkCopy key={head.revisionId+'-work'} task={task} root={root} revision={head.revisionId} sha={head.sha256} path={path} ordinal={head.ordinal} disabled={busy||fileLock} lock={lockWork}/>}
    {discard&&<div role="group" aria-label="放弃尚未保留的文字"><p>这次输入还没保留。</p><Button size="small" variant="secondary" onClick={()=>ask(false)}>留在这里</Button>
      <Button size="small" variant="ghost" onClick={close}>放弃本次输入</Button></div>}
    {compare&&dirty&&<InlineDiff before={head.text} after={text} beforeLabel="起始稿" afterLabel="当前输入" versionLabel={head.sha256.slice(0,8)} readOnly/>}
    {!leaveOpen&&limitMessage&&<p className={count>24576?'pw-field-error':'pw-field-hint'}>{limitMessage}</p>}
    {!leaveOpen&&(note||dirty)&&<p role="status" className="pw-field-hint">{note||'尚未保留；切到后台会清除这次输入。要留下，请先点“保留为新稿”。'}</p>}
    {leaveDialog}
  </section>;
}
