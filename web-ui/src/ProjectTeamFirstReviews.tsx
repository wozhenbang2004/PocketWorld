import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {IconChevronRight} from './icons';
import {bridge} from './bridge';
import {projectTeamReviewOrigin,sameProjectTeamReview,type ProjectTeamReviewOrigin} from './project-team-review-origin';

type Row={source:ProjectTeamReviewOrigin;title:string};
type Data=Record<string,any>;
/** Entered only from the visible ordinary private project task. No operation or model is started by opening this directory. */
export function ProjectTeamFirstReviews({id,run,epoch,project,checkout,conversation,active,onOpen}:{
  id:string;run:string;epoch:number;project:string;checkout:string;conversation:string;active:boolean;onOpen:(mark:ProjectTeamReviewOrigin)=>void;
}){
  const [rows,setRows]=useState<Row[]|null>(null),[after,setAfter]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const generation=useRef(0),pending=useRef(false),documentEpoch=bridge.getDocumentEpoch();
  const visible=()=>active&&!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&(bridge.getState().view as {taskId?:string}|undefined)?.taskId===id;
  const clear=()=>{generation.current++;pending.current=false;setBusy(false);setRows(null);setAfter('');setError('')};
  const original=(s:ProjectTeamReviewOrigin)=>s.parentTaskId===id&&s.parentRunId===run&&s.parentEpoch===epoch&&s.projectId===project&&s.checkoutId===checkout&&s.conversationId===conversation;
  const check=async(source?:ProjectTeamReviewOrigin,cursor='')=>{
    if(!visible()||pending.current||source&&!original(source))return;const n=++generation.current;pending.current=true;setBusy(true);setError('');
    try{
      const value=await bridge.command(source?'tasks.projectTeamReview.original':'tasks.projectTeamReviews',{taskId:id,runId:run,epoch,...(source?{source}:{after:cursor})}) as Data;
      if(n!==generation.current||!visible())return;
      if(value?.readOnly!==true||value.executionAuthorized!==false)throw new Error('没有取得这次原工作的只读定位。');
      if(source){if(!sameProjectTeamReview(source,value.source))throw new Error('原独审、文件或项目已变，没有离开当前任务。');onOpen(source);return;}
      if(value.taskId!==id||value.runId!==run||value.epoch!==epoch||value.after!==cursor||!Array.isArray(value.rows)||value.rows.length>4||
        typeof value.nextAfter!=='string'||value.nextAfter.length>240||value.nextAfter&&value.nextAfter===cursor)throw new Error('文件审阅目录不属于这次原工作。');
      const found:Row[]=value.rows.map((row:Data)=>{const s=projectTeamReviewOrigin(row.source);if(!s||!original(s))throw new Error('目录中有一项来源已变化，未显示未确认的原件。');
        return {source:s,title:typeof row.title==='string'?row.title:''}});
      const all=cursor?[...(rows??[]),...found]:found;
      if(new Set(all.map(row=>row.source.teamRunId)).size!==all.length)throw new Error('原目录在阅读期间变化，请重新读取。');
      setRows(all);setAfter(value.nextAfter);
    }catch(e){if(n===generation.current&&visible())setError(e instanceof Error?e.message:String(e));}
    finally{if(n===generation.current){pending.current=false;setBusy(false)}}
  };
  // Opening this exact parent reads just its first four linked-team records; never the files or a grant.
  useEffect(()=>{clear();if(visible())void check();const visibility=()=>{clear();if(visible())void check()};
    document.addEventListener('visibilitychange',visibility);
    return()=>{generation.current++;pending.current=false;document.removeEventListener('visibilitychange',visibility)}},[id,run,epoch,project,checkout,conversation,active,documentEpoch]);
  if(!active||!busy&&!error&&rows!==null&&!rows.length&&!after)return null;
  const repeatedTitle=(title:string)=>rows?.filter(row=>row.title===title).length!==1;
  return <section aria-label="原项目的协作文件">
    {rows===null?(!busy&&error?<Button variant="secondary" size="small" onClick={()=>void check()}>重新读取原协作</Button>:null):<>
      {rows.map(({source,title})=><Button variant="secondary" size="small" key={source.teamRunId} disabled={busy} trailingIcon={IconChevronRight}
        onClick={()=>void check(source)}>{rows.length>1?(title||'原团队')+(repeatedTitle(title)?'（'+source.teamRunId.slice(-6)+'）':'')+' · ':''}{source.registeredReviewId?'查看已登记的 '+source.fileCount+' 份原文件':'核对独审交回的 '+source.fileCount+' 份原文件'}</Button>)}
      {after&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>void check(undefined,after)}>读取其余原协作</Button>}
      <Button variant="ghost" size="small" disabled={busy} onClick={()=>void check()}>重新核对目录</Button>
    </>}{busy&&<p role="status">正在核对原项目的团队审阅…</p>}{error&&<p role="alert">{error}</p>}
  </section>;
}
