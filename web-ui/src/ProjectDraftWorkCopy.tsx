import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {bridge} from './bridge';

const find='projectDraft.findWorkCopy',offer='projectDraft.reviewWorkCopy',create='projectDraft.createWorkCopy',open='projectDraft.openWorkCopy',moveReview='projectDraft.reviewUnusedMove',move='projectDraft.moveUnusedCopy';
const hash=/^[a-f0-9]{64}$/;
type Copy={taskId:string;draftId:string;revisionId:string;sha256:string;path:string;ordinal:number;runId:string;epoch:number;sourceProjectId:string;sourceCheckoutId:string;
  projectId:string;checkoutId:string;name?:string;originalSourceDigest:string;initialCopyDigest?:string;fileCount?:number;sourceBytes?:number;omitted?:number;found:boolean;
  reviewHandle?:string;moveHandle?:string;recyclable?:boolean;status?:string;currentFileState?:string;executionAuthorized:boolean};
function page(){return bridge.getState().view as {resumeRevision?:number;taskId?:string;section?:string}|undefined;}
function windowKey(){const v=page();return JSON.stringify([bridge.getDocumentEpoch(),v?.resumeRevision,v?.taskId,v?.section]);}
export function ProjectDraftWorkCopy({task,root,revision,sha,path,ordinal,disabled,lock}:{task:string;root:string;revision:string;sha:string;path:string;ordinal:number;disabled:boolean;lock:(v:boolean)=>void}){
  const [copy,setCopy]=useState<Copy|null>(null),[action,spin]=useState(''),[note,tell]=useState('');
  const seq=useRef(0),flight=useRef(false),seen=useRef('');
  const can=()=>!document.hidden&&page()?.taskId===task&&!page()?.section&&[find,offer,create,open,moveReview,move].every(k=>bridge.getState().capabilities.commands.includes(k));
  const phase=copy?.moveHandle?'confirmMove':copy?.reviewHandle?'review':copy?.found?(copy.status==='shelved'?'shelved':'ready'):'none';
  useLayoutEffect(()=>{lock(phase==='review'||phase==='confirmMove'||action===create||action===offer||action===move||action===moveReview);return()=>lock(false)},[phase,action,lock]);
  useEffect(()=>{
    function clear(){++seq.current;flight.current=false;setCopy(null);spin('');tell('');seen.current='';}
    clear();
    const leave=()=>{if(seen.current&&(!can()||windowKey()!==seen.current))clear();};
    const off=bridge.subscribe(leave);document.addEventListener('visibilitychange',leave);
    // Locate only the exact existing work copy; this never captures the checkout or creates, opens or runs anything.
    if(can())void run(find);
    return()=>{++seq.current;off();document.removeEventListener('visibilitychange',leave);};
  },[task,root,revision,sha,path,ordinal]);
  async function run(cmd:string){
    if(flight.current||!can()||cmd!==find&&disabled||cmd===create&&!copy?.reviewHandle||cmd===move&&!copy?.moveHandle)return;
    const version=copy,n=++seq.current,ticket=windowKey();seen.current=ticket;flight.current=true;spin(cmd);tell('');
    const valid=()=>n===seq.current&&can()&&windowKey()===ticket;
    try{
      const r=await bridge.command<Copy>(cmd,{taskId:task,draftId:root,revisionId:revision,sha256:sha,...(cmd===create?{reviewHandle:version!.reviewHandle}:cmd===move?{fromState:version!.status,reviewHandle:version!.moveHandle}:cmd===moveReview?{fromState:version!.status}:{})});
      if(!valid())return;
      if(r.taskId!==task||r.draftId!==root||r.revisionId!==revision||r.sha256!==sha||r.path!==path||r.ordinal!==ordinal||!r.runId||!Number.isSafeInteger(r.epoch)||r.epoch<=0||typeof r.found!=='boolean'||
         r.executionAuthorized!==false||!hash.test(r.originalSourceDigest)||!r.projectId||!r.checkoutId||r.projectId===r.sourceProjectId||r.checkoutId===r.sourceCheckoutId)
        throw Error('没有核对到这一稿的独立工程，未打开原副本。');
      if(r.found||cmd===offer||cmd===create){
        if(r.found&&!['ready','shelved'].includes(String(r.status))||cmd===moveReview&&(!r.moveHandle||r.status!==version?.status)||cmd===move&&r.status!==(version?.status==='ready'?'shelved':'ready')||!r.name||!r.initialCopyDigest||!hash.test(r.initialCopyDigest)||!Number.isSafeInteger(r.fileCount)||Number(r.fileCount)<1||
          (cmd===offer&&!r.found&&!r.reviewHandle)||(cmd===create&&(!r.found)))throw Error('未收到原生工程和副本的完整回执。');
      }
      setCopy(r);
    }catch(e){if(valid()){setCopy(null);tell(e instanceof Error?e.message:'不能核对这次副本。回来可以查询同一份，不会自动重建。');}}
    finally{if(n===seq.current){flight.current=false;spin('');}}
  }
  if(!can())return null;
  const busy=!!action||disabled,pending=copy?.found===false&&copy.status==='publication_pending';
  return <div className="pw-form-flow">
    {phase==='none'&&pending&&<p className="pw-field-hint" role="status">上次已保留这份稿的建立动作，但还没有完整工作副本。可重新核对同一动作，不会另建空工程。</p>}
    {phase==='none'&&<Button variant="secondary" size="small" disabled={busy} onClick={()=>void run(offer)}>{action===offer?'核对工程原版…':pending?'继续核对这份副本':'用这一稿建立独立工作副本'}</Button>}
    {phase==='review'&&<div role="group" aria-label="确认新建独立工程"><strong>{copy?.name}</strong>
      <p className="pw-field-hint">从已核对的工程原版复制 {copy?.fileCount} 个源码文件，只替换 {path} 为当前{ordinal?'第 '+ordinal+' 稿':'改稿'}（{sha.slice(0,8)}）。副本只留在本机，不覆盖原工程，不会运行命令。{(copy?.omitted||0)>0?'原目录里有 '+copy?.omitted+' 项未纳入源码包。':''}</p>
      <div className="pw-form-actions"><Button size="small" variant="primary" disabled={busy} onClick={()=>void run(create)}>{action===create?'正在建立并核对…':'建立独立工作副本'}</Button>
        <Button size="small" variant="ghost" disabled={busy} onClick={()=>void run(find)}>取消</Button></div></div>}
    {phase==='ready'&&<div className="pw-form-flow"><p className="pw-field-hint" role="status">{copy?.name} · 此稿已建立独立工作副本。后续编辑留在副本，构建要另行确认。{copy?.currentFileState!=='selected_version'&&'原路径现在已变化或不可读，打开的是实际副本，不会用旧稿覆盖。'}</p>
      <Button size="small" variant="secondary" disabled={busy} onClick={()=>void run(open)}>打开这个工作副本</Button>
      {copy?.recyclable&&<Button size="small" variant="ghost" disabled={busy} onClick={()=>void run(moveReview)}>收起未使用的副本</Button>}</div>}
    {phase==='shelved'&&<div><p role="status" className="pw-field-hint">这份未使用的副本已在本机回收区，文件没有删除。</p>
      <Button size="small" variant="secondary" disabled={busy} onClick={()=>void run(moveReview)}>还原这个副本</Button></div>}
    {phase==='confirmMove'&&<div role="group" aria-label="确认副本位置"><p className="pw-field-hint">{copy?.status==='ready'?'移到本机回收区，不删除文件。请保留这项原交办，以便回到这里还原。':'按原身份移回工作区，不重新复制原工程，也不打开或运行。'}</p>
      <Button size="small" variant="secondary" disabled={busy} onClick={()=>void run(move)}>{copy?.status==='ready'?'确认收起':'确认还原'}</Button>
      <Button size="small" variant="ghost" disabled={busy} onClick={()=>void run(find)}>取消</Button></div>}
    {note&&<p role="status" className="pw-field-hint">{note}</p>}
  </div>;
}
