import {useCallback,useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {ProjectDraftChanges} from './ProjectDraftChanges';
import {ProjectDraftOwnerRevision,projectDraftWindowTicket as ticket,type SavedPin} from './ProjectDraftOwnerRevision';
import {pinKey,type RevisionPin} from './OwnerRevisionChoice';
import type {OriginalProjectDraft} from './ButlerPreparations';
import type {FixedDraftGoalDecision} from './task-presentation';

type Diff={taskId:string;available:boolean;draftId:string;projectId:string;checkoutId:string;path:string;runId:string;epoch:number;code:string;sourceChanged:boolean;executionAuthorized:boolean;
 ownerRevision?:{taskId:string;draftId:string;revisionId:string;sha256:string};before:string;after:string;beforeSha256:string;afterSha256:string;beforeBytes:number;afterBytes:number};
type ShownDiff=Diff&{retainedHead?:SavedPin};
const action='projectDraft.readDifference',sha=/^[a-f0-9]{64}$/;
const visible=()=>bridge.getState().view as {resumeRevision?:number;section?:string;taskId?:string}|undefined;
function retainedPin(value:unknown,diff:Diff,window:string):SavedPin|undefined{
  if(!value||typeof value!=='object'||Array.isArray(value))return;
  const row=value as Partial<{ownerRevision:RevisionPin;ordinal:number;bytes:number;path:string;runId:string;epoch:number;readOnly:boolean;executionAuthorized:boolean}>;
  const ref=row.ownerRevision;
  if(row.readOnly!==true||row.executionAuthorized!==false||typeof diff.runId!=='string'||!diff.runId||
    !Number.isSafeInteger(diff.epoch)||diff.epoch<1||row.runId!==diff.runId||row.epoch!==diff.epoch||row.path!==diff.path||
    !ref||!pinKey(ref)||ref.taskId!==diff.taskId||ref.draftId!==diff.draftId||
    typeof row.ordinal!=='number'||!Number.isSafeInteger(row.ordinal)||row.ordinal<1||row.ordinal>8||
    typeof row.bytes!=='number'||!Number.isSafeInteger(row.bytes)||row.bytes<0||row.bytes>24576)return;
  return {taskId:ref.taskId,draftId:ref.draftId,path:diff.path,revisionId:ref.revisionId,sha256:ref.sha256,ordinal:row.ordinal,bytes:row.bytes,window};
}
export function ProjectDraftDifference({id,active,project,checkout,pin,requested,goalDecision,registerBeforeLeave}:{id:string;active:boolean;project:string;checkout:string;pin?:string;requested?:OriginalProjectDraft;goalDecision?:FixedDraftGoalDecision;registerBeforeLeave?:(guard:()=>boolean)=>()=>void}){
  const [data,show]=useState<ShownDiff|null>(null),[note,tell]=useState(''),[busy,spin]=useState(false),turn=useRef(0),held=useRef(''),flight=useRef(false);
  const [editorProtected,setProtected]=useState(false),shownFromChat=useRef('');
  const locked=useRef(false),[leaveNotice,warn]=useState(false);
  const protect=useCallback((value:boolean)=>{locked.current=value;setProtected(value);if(!value)warn(false)},[]);
  const dismissLeave=useCallback(()=>warn(false),[]);
  const result=useRef<HTMLElement>(null),arrival=useRef<{scroller:HTMLElement;top:number;ticket:string}|null>(null);
  const exact=requested?.taskId===id&&requested.draftId===pin?requested:undefined;
  const exactKey=exact?JSON.stringify([exact.taskId,exact.runId,exact.epoch,exact.draftId]):'';
  const originalDecision=data&&goalDecision?.taskId===data.taskId&&goalDecision.draftId===data.draftId&&
    goalDecision.runId===data.runId&&goalDecision.epoch===data.epoch?goalDecision:undefined;
  const current=()=>{const s=bridge.getState();return active&&!document.hidden&&!visible()?.section&&visible()?.taskId===id&&s.capabilities.commands.includes(action)};
  useLayoutEffect(()=>{
    if(!active||!registerBeforeLeave)return;
    return registerBeforeLeave(()=>{
      if(!locked.current||!held.current||held.current!==ticket()||!current())return false;
      warn(true);return true;
    });
  },[id,project,checkout,pin,active,registerBeforeLeave]);
  const reset=()=>{++turn.current;held.current='';flight.current=false;show(null);tell('');spin(false);protect(false)};
  useEffect(()=>{reset();const leave=()=>{if(held.current&&(!current()||held.current!==ticket()))reset()};
    const off=bridge.subscribe(leave);document.addEventListener('visibilitychange',leave);return()=>{++turn.current;off();document.removeEventListener('visibilitychange',leave)};
  },[id,active,pin,project,checkout]);
  async function read(){
    if(flight.current||!current()||requested&&!exact)return;
    const origin=ticket(),n=++turn.current;flight.current=true;held.current=origin;spin(true);tell('');
    const scroller=exact?result.current?.closest<HTMLElement>('.pw-task-body'):null;
    arrival.current=scroller?{scroller,top:scroller.scrollTop,ticket:origin}:null;
    const valid=()=>turn.current===n&&current()&&ticket()===origin;
    try{
      const r=await bridge.command<Diff&{retainedHead?:unknown}>(action,{taskId:id,...(exact?{runId:exact.runId,epoch:String(exact.epoch),draftId:exact.draftId}:{})});if(!valid())return;
      if(r.taskId!==id)throw Error('不是这次原交办的回执');
      if(r.available===false){tell('还没有实际生成并保存的改稿。模型的文字答复不算差异。');return;}
      if(r.available!==true||r.code!=='project_edit_draft_saved'||r.projectId!==project||r.checkoutId!==checkout||!sha.test(r.draftId)||
        (pin&&pin!==r.draftId)||(exact&&(exact.runId!==r.runId||exact.epoch!==r.epoch))||r.sourceChanged!==false||r.executionAuthorized!==false||typeof r.path!=='string'||
        (r.ownerRevision&&(!sha.test(r.ownerRevision.draftId)||!sha.test(r.ownerRevision.revisionId)||r.ownerRevision.sha256!==r.beforeSha256||typeof r.ownerRevision.taskId!=="string"||!r.ownerRevision.taskId||r.ownerRevision.taskId===id))||typeof r.before!=='string'||typeof r.after!=='string'||r.before===r.after||!sha.test(r.beforeSha256)||!sha.test(r.afterSha256))throw Error('原工程、文件或两版文字不能同时核对。');
      const {retainedHead,...difference}=r;
      show({...difference,retainedHead:retainedPin(retainedHead,difference,origin)});
    }catch(e){if(valid())tell(e instanceof Error?e.message:'这份真实草稿暂不可读。');}finally{if(turn.current===n){flight.current=false;spin(false)}}
  }
  const nativeReady=current();
  useEffect(()=>{
    if(!nativeReady||!exactKey||shownFromChat.current===exactKey||!current())return;
    shownFromChat.current=exactKey;void read();
  },[exactKey,nativeReady]);
  useLayoutEffect(()=>{
    if(!data)return;
    const a=arrival.current,node=result.current;arrival.current=null;
    if(!a||!node||!a.scroller.contains(node)||Math.abs(a.scroller.scrollTop-a.top)>1||a.ticket!==ticket()||!current())return;
    a.scroller.scrollTop+=node.getBoundingClientRect().top-a.scroller.getBoundingClientRect().top-12;
  },[data]);
  if(!active||!bridge.getState().capabilities.commands.includes(action))return null;
  if(requested&&!exact)return <p role="status" className="pw-field-hint">没有打开原对话的这份改稿；不会用其他版本代替。</p>;
  return <section className="pw-project-draft" ref={result} aria-label="这次交办的独立改稿"><div className="pw-section-heading"><h4>独立改稿</h4><Button size="small" variant="ghost" disabled={busy||editorProtected}
      onClick={()=>data?reset():void read()}>{busy?'读取中…':data?'收起差异':pin?'看文件差异':'查看草稿结果'}</Button></div>
    {note&&<p className="pw-field-hint" role="status">{note}</p>}
    {data&&held.current===ticket()&&current()&&<><strong className="pw-project-draft-path">{data.path}</strong><p className="pw-field-hint">原工程未写回，没有构建或执行。{data.ownerRevision?'以你点选的已存稿为起点，不是工程原件或最新稿。':'这里比较的是本次固定的两版文件。'}</p>
      {originalDecision&&<p className="pw-field-hint" role="status">{originalDecision.completed?'这版原改稿已确认达到目标。':'这版原改稿的验收已记录：目标尚未达到。'}</p>}
      <ProjectDraftChanges key={data.draftId} before={data.before} after={data.after} version={data.afterSha256.slice(0,8)} beforeLabel={data.ownerRevision?'选中的已存稿':undefined}/>
      <ProjectDraftOwnerRevision key={data.draftId} task={id} root={data.draftId} path={data.path} originalHash={data.afterSha256} protect={protect}
        retainedHead={data.retainedHead} leaveRequested={leaveNotice&&editorProtected} onDismissLeave={dismissLeave}/>
      <details className="pw-field-hint"><summary>文件与版本</summary>工程 {data.projectId} · 副本 {data.checkoutId}<br/>原文 {data.beforeBytes} 字节 · {data.beforeSha256}<br/>改稿 {data.afterBytes} 字节 · {data.afterSha256}</details></>}
  </section>;
}
