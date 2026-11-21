import {useLayoutEffect,useRef} from 'react';
import {Button,TextAreaField} from './forms-library';
import {PressButton} from './library';

type Data=Record<string,any>;
const obj=(value:unknown):Data=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Data:{};
const list=(value:unknown):unknown[]=>Array.isArray(value)?value:[];
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9-]{36}$/.test(value);
const sha=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const number=(value:unknown,min:number):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=min;
const format='pocketworld.owner-work-team-report.v1';

export type TeamReportParent={taskId:string;runId:string;epoch:number;digest:string};
export type TeamReportChoice={teamRunId:string;generation:number;revision:number};
type Contract={goal:string;deliverable?:string;completionCriteria?:string;constraints?:string};
export type TeamReportRead={handle:string;evidence:Data;contract:Contract;body:string;expires:number};

export function teamReportParent(task:unknown):TeamReportParent|undefined {
 const {id,status}=obj(task),raw=obj(obj(task).raw),meta=obj(raw.ownerWork);
 if(!uuid(id)||id!==raw.id||status!=='needs_confirmation'||raw.status!==status||raw.taskProjection!=='detail_window'||raw.taskCoreReadLimited!==false||
   raw.contextAvailable!==true||raw.contextWithdrawn||raw.sourceCurrentReadRequired||raw.hasUnresolvedOperations!==false||raw.cancelRequested!==false||
   raw.executionRole!=='owner_work'||raw.actorId!=='owner'||raw.spaceId!=='private'||raw.origin!=null||raw.developmentContext!=null||
   meta.format!=='pocketworld.owner-work.v1'||meta.workTaskId!==id||meta.conversationId!==raw.conversationId||
   !uuid(raw.conversationId)||!uuid(raw.runId)||!number(raw.coordinatorEpoch,1)||!sha(raw.resultReviewDigest))return;
 return {taskId:id,runId:raw.runId,epoch:raw.coordinatorEpoch,digest:raw.resultReviewDigest};
}
export function linkedTeamReportChoice(task:unknown):TeamReportChoice|undefined {
 const source=teamReportParent(task),raw=obj(obj(task).raw),rows=list(raw.ownerWorkTeams);
 if(!source||rows.length!==1||raw.ownerWorkTeamsUnavailable===true)return;
 const row=obj(rows[0]),candidate=obj(row.reportForReview),id=row.teamRunId;
 if(!uuid(id)||row.taskId!==id||row.runId!==id||row.epoch!==0||
    row.parentTaskId!==source.taskId||row.parentRunId!==source.runId||row.parentEpoch!==source.epoch||row.conversationId!==raw.conversationId||
    row.readOnly!==true||row.cancelRequested!==false||row.status!=='completed'||row.phase!=='completed'||row.active!==false||
    candidate.teamRunId!==id||!number(candidate.generation,0)||!number(candidate.revision,1))return;
 return {teamRunId:id,generation:candidate.generation,revision:candidate.revision};
}
export function sameTeamReportChoice(left:TeamReportChoice|undefined,right:TeamReportChoice):boolean {
 return !!left&&left.teamRunId===right.teamRunId&&left.generation===right.generation&&left.revision===right.revision;
}
export function teamReportRead(value:unknown,parent:TeamReportParent,choice:TeamReportChoice):TeamReportRead {
 const packet=obj(value),report=obj(packet.teamReport),contract=obj(report.contract),body=report.body;
 const {format:kind,taskId,runId,epoch,resultReviewDigest,teamRunId,generation,revision,messageId,sha256,bytes}=report;
 if(!uuid(packet.reviewHandle)||packet.expiresInMs!==120_000||kind!==format||taskId!==parent.taskId||runId!==parent.runId||
    epoch!==parent.epoch||resultReviewDigest!==parent.digest||teamRunId!==choice.teamRunId||generation!==choice.generation||revision!==choice.revision||
    !uuid(messageId)||!sha(sha256)||!number(bytes,1)||bytes>65_536||typeof body!=='string'||!body.trim()||body.length>65_536||
    new TextEncoder().encode(body).length!==bytes||contract.format!=='pocketworld.owner-work-contract.v1'||!number(contract.revision,1)||
    typeof contract.goal!=='string'||!contract.goal.trim()||contract.goal.length>12_000||
    !['deliverable','completionCriteria','constraints'].every(key=>contract[key]===undefined||typeof contract[key]==='string'&&contract[key].length<=4000))
    throw Error('没有取得这份汇总和原工作的完整同版内容，没有选用其他证据。');
 return {handle:packet.reviewHandle,expires:performance.now()+packet.expiresInMs,
   evidence:{format:kind,taskId,runId,epoch,resultReviewDigest,teamRunId,generation,revision,messageId,sha256,bytes},
   contract:{goal:contract.goal,deliverable:contract.deliverable,completionCriteria:contract.completionCriteria,constraints:contract.constraints},body};
}
export function teamReportDecisionSaved(value:unknown,report:TeamReportRead,completed:boolean):boolean {
 const receipt=obj(value),saved=obj(receipt.teamReportEvidence);
 return receipt.ownerDecisionRecorded===true&&receipt.completed===completed&&number(receipt.confirmedResultRevision,1)&&
  Object.entries(report.evidence).every(([key,expected])=>saved[key]===expected);
}

export function OwnerWorkTeamReportReview({reading,error,done,pending,disabled,note,edit,decide,leave}:{
 reading?:TeamReportRead;error?:string;done?:boolean;pending:boolean;disabled:boolean;note:string;
 edit:(text:string)=>void;decide:(completed:boolean)=>void;leave:()=>void;
}) {
 const title=useRef<HTMLHeadingElement>(null);
 useLayoutEffect(()=>{title.current?.focus({preventScroll:true});title.current?.scrollIntoView({block:'nearest'})},[]);
 const contract=reading?.contract;
 return <section className="pw-task-overview pw-task-review" aria-label="用原团队汇总核对原工作">
  <header className="pw-section-heading"><h3 ref={title} tabIndex={-1}>用团队汇总核对原工作</h3>
   <Button variant="ghost" disabled={pending} onClick={leave}>返回原工作</Button></header>
  {contract&&<section className="pw-work-goal" aria-label="本次工作的目标和交付要求">
   <h4>本次工作的目标</h4><p className="pw-work-goal-text" dir="auto">{contract.goal}</p>
   <dl>{contract.deliverable&&<div><dt>交付内容</dt><dd dir="auto">{contract.deliverable}</dd></div>}
    {contract.completionCriteria&&<div><dt>本次工作记录的完成条件</dt><dd dir="auto">{contract.completionCriteria}</dd></div>}
    {contract.constraints&&<div><dt>已记录的范围</dt><dd dir="auto">{contract.constraints}</dd></div>}</dl>
  </section>}
  {reading&&<section aria-label="原团队汇总正文">
   <h4>原团队汇总</h4><div className="pw-markdown" dir="auto" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{reading.body}</div>
  </section>}
  {!reading&&!error&&<p role="status">正在读取原团队完整汇总，尚未登记决定。</p>}
  {error&&<p role="alert">{error}</p>}
  {done!==undefined&&<p role="status">{done?'已按这份汇总记录：原工作达到目标。':'已按这份汇总记录：原工作尚未达到目标。'}原报告保留。</p>}
  {reading&&done===undefined&&!error&&<div className="pw-form-flow">
   <TextAreaField label="本次结果反馈（可选）" value={note} maxLength={8000} onChange={edit} isDisabled={disabled||pending}/>
   <div className="pw-action-row">
    <Button variant="secondary" disabled={disabled||pending} onClick={()=>decide(false)}>原工作目标尚未达到</Button>
    <PressButton disabled={disabled||pending} label="确认原工作达到目标" onClick={()=>decide(true)}/>
   </div>
   {pending&&<p role="status">正在登记这次原工作决定。</p>}
  </div>}
 </section>;
}
