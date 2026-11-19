import {useSyncExternalStore} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {phoneConversationId, phoneRunIdentity, phoneStartReceipt, messagePhoneResult} from './phone-start';
import {phoneReportInSource, phoneReportTarget, type PhoneReportTarget} from './PhoneReturnedReport';
import type {PhoneConditions, PhoneReviewDisplay} from './PhoneScopeReview';
import type {ButlerPreparationTarget} from './ButlerPreparations';

type Row=Record<string,any>;
export type ProposalPhoneRun={taskId:string;runId:string;epoch:number;conversationId:string};
const id=(value:unknown):value is string=>typeof value==='string'&&!!value.trim()&&value.length<=240;
const original=(view:Row,t:ButlerPreparationTarget)=>view.format==='pocketworld.owner-work-preparation.v1'&&view.kind==='phone'&&view.executionAuthorized===false&&
  view.sourceTaskId===t.sourceTaskId&&view.proposalId===t.proposalId&&view.sourceConversationId===t.sourceConversationId&&id(view.targetRequestId);
const sameRun=(a:ProposalPhoneRun|null,b:ProposalPhoneRun|null)=>!!a&&!!b&&a.conversationId===b.conversationId&&a.taskId===b.taskId&&a.runId===b.runId&&a.epoch===b.epoch;
const state=(row:unknown)=>!!row&&typeof row==='object'&&['ready','blocked','unknown'].includes((row as Row).state);

/** Never fall back to an unscoped/channel review when a private proposal's preflight is missing. */
export function reviewedPhone(value:Row,view:Row|null,t:ButlerPreparationTarget,mode:string):PhoneReviewDisplay|null{
  const c=value.conditions,contract=view?.contract,terms=value.contract;
  if(!view||!original(view,t)||view.contextAvailable!==true||view.canPrepare!==true||!id(value.reviewHandle)||
    value.requestId!==view.targetRequestId||value.conversationId!==t.sourceConversationId||!['main_screen','background'].includes(mode)||value.mode!==mode||
    !contract||!terms||typeof value.goal!=='string'||!value.goal.trim()||value.goal!==contract.goal||
    !['goal','constraints','deliverable','completionCriteria'].every(k=>terms[k]===contract[k])||
    !Number.isSafeInteger(value.expiresInMs)||value.expiresInMs<=0||value.expiresInMs>120000||
    ![0,30,120].includes(value.scopeMinutes)||!Number.isSafeInteger(value.toolLimit)||value.toolLimit<1||
    !Number.isSafeInteger(value.modelCallLimit)||value.modelCallLimit<1||typeof value.modelName!=='string'||!value.modelName.trim()||
    typeof value.screenDisclosure!=='string'||!value.screenDisclosure.trim()||!state(c?.notification)||!state(c?.screen)||!state(c?.capacity)||c.capacity.mode!==mode)return null;
  return {goal:value.goal, mode, scopeMinutes:value.scopeMinutes, toolLimit:value.toolLimit, modelCallLimit:value.modelCallLimit,
    modelName:value.modelName, conversationId:t.sourceConversationId, conversationTitle:typeof value.conversationTitle==='string'?value.conversationTitle:undefined,
    screenDisclosure:value.screenDisclosure, conditions:c as PhoneConditions};
}

const phaseText={admitted:'Android 已接纳首次服务请求。这不是已看屏或已完成的证明。',service_denied:'Android 拒绝了首次服务启动，没有按照这张回执开跑。',
  component_unconfirmed:'Android 没有返回原服务组件，首次启动未得到确认。',run_recorded:'已登记第 1 轮；尚不能核实系统是否接纳服务。',
  no_run:'原任务已建，但本次查询没有找到运行轮次。',unconfirmed:'已找到原任务；暂不能核实首次运行。'} as const;
const runText:Record<string,string>={preparing:'准备中',running:'登记为执行中',completed:'执行结束',cancelled:'已取消',stopped:'已停止',failed:'执行失败',paused:'已暂停',
  awaiting_user:'等待你处理',needs_confirmation:'等待你确认',interrupted:'已中断',reconciling:'有操作结果待核对'};
function runFrom(value:Row|undefined,view:Row,t:ButlerPreparationTarget):ProposalPhoneRun|null{
  return value&&value.taskId===view.targetRequestId&&value.conversationId===t.sourceConversationId&&id(value.runId)&&Number.isSafeInteger(value.epoch)&&value.epoch>0
    ?{taskId:view.targetRequestId,runId:value.runId,epoch:value.epoch,conversationId:t.sourceConversationId}:null;
}
function currentRun(origin:ProposalPhoneRun):boolean{
  const rows=bridge.getState().state.tasks;
  return Array.isArray(rows)&&rows.some(task=>{
    const r=phoneRunIdentity(task);return r&&r.taskId===origin.taskId&&r.runId===origin.runId&&r.epoch===origin.epoch&&phoneConversationId(task)===origin.conversationId;
  });
}
function delivered(origin:ProposalPhoneRun){
  const {state,view,capabilities}=bridge.getState(),v=view as Row|undefined;
  if(document.hidden||v?.section||v?.taskId||state.conversationReader||state.conversationId!==origin.conversationId||!capabilities.commands.includes('phone.result.original')||!Array.isArray(state.messages))return null;
  const matches=state.messages.flatMap(m=>{
    if(!id(m?.id)||m.sourceRevision!==1||m.taskId!==origin.taskId)return [];
    const result=messagePhoneResult(m);return result&&result.runId===origin.runId&&result.epoch===origin.epoch?[{id:m.id as string,result}]:[];
  });
  return matches.length===1?matches[0]:null;
}
type Props={view:Row;raw:Row|null;target:ButlerPreparationTarget;inline:boolean;disabled:boolean;onCheck?:()=>void;
  onRun?:(t:ProposalPhoneRun)=>void;onReport?:(t:PhoneReportTarget)=>void};

/** Preparation to its original work: admission, execution and delivered answer are separate records. */
export function ButlerPhoneReceipt({view,raw,target,inline,disabled,onCheck,onRun,onReport}:Props){
  useSyncExternalStore(bridge.subscribe,bridge.getState);
  if(!original(view,target))return null;
  const match=!!raw&&raw.kind==='phone'&&raw.requestId===view.targetRequestId&&raw.conversationId===target.sourceConversationId;
  const receipt=match?phoneStartReceipt(raw,{requestId:view.targetRequestId,conversationId:target.sourceConversationId}):null;
  const accepted=receipt?.status==='accepted';
  const first=accepted&&receipt?.runId&&receipt.epoch===1?runFrom(raw!,view,target):null;
  const linked=view.target?.taskId===view.targetRequestId&&view.target.conversationId===target.sourceConversationId?view.target:undefined;
  const current=runFrom(linked,view,target);
  if(!raw&&!linked)return null;
  // A later task headline is never a substitute for this proposal's immutable association.
  const pinned=linked?runFrom({...linked,runId:linked.originalRunId,epoch:linked.originalEpoch},view,target):null;
  const conflict=!!first&&!!pinned&&!sameRun(first,pinned);
  const origin=conflict?null:first??pinned??(current?.epoch===1?current:null);
  const navigable=!!origin&&linked?.canOpenOriginalRun!==false&&(sameRun(current,origin)||currentRun(origin));
  const answer=origin&&inline&&view.contextAvailable===true?delivered(origin):null;
  const phase=receipt?.admissionState;
  const firstStatus=first&&receipt?.taskStatus?runText[receipt.taskStatus]:undefined;
  const detail=phase?phaseText[phase]:accepted?'已核实这份提案的原手机任务，尚未核实首次系统启动。':
    match&&raw?.status==='rejected'?'本次未进入任务启动。可在下方重新核对目标和条件。':linked?'已关联这份提案的原手机任务。':'启动回执尚未核实，请查原请求，不要重复提交。';
  const showReport=(trigger:HTMLElement)=>{
    if(!answer||!origin||!onReport||disabled)return;
    const exact=delivered(origin);
    if(!exact||exact.id!==answer.id||exact.result.deliveryId!==answer.result.deliveryId)return;
    // Focus returns to the original message, not the now-closed preparation button.
    const nodes=Array.from(document.querySelectorAll<HTMLElement>('[data-message-id]')).filter(e=>e.dataset.messageId===exact.id&&e.closest<HTMLElement>('[data-conversation-id]')?.dataset.conversationId===origin.conversationId);
    const t=phoneReportTarget(origin.conversationId,exact.id,1,exact.result,nodes.length===1?nodes[0]:trigger);
    if(phoneReportInSource(t))onReport(t);
  };
  return <section className="pw-preparation-receipt" aria-label="这次手机工作的原记录">
    <strong role="status">{answer?`第 ${origin!.epoch} 轮已写回原私聊 · ${runText[answer.result.status]}`:firstStatus?`第 1 轮 · ${firstStatus}`:accepted||linked?'原手机任务已建':'原手机启动回执'}</strong>
    <p>{detail}</p>{match&&raw.latestReadUnconfirmed===true&&<p className="pw-muted">这次未读到新的确证，保留上次核实的记录。</p>}
    {match&&typeof raw.error==='string'&&raw.error&&<p className="pw-error">{raw.error}</p>}
    <div className="pw-action-row">
      {answer&&onReport&&<Button variant="primary" disabled={disabled} onClick={e=>showReport(e.currentTarget)}>读第 {origin!.epoch} 轮原回执</Button>}
      {origin&&navigable&&onRun&&<Button variant="secondary" disabled={disabled} onClick={()=>{if(!disabled&&(sameRun(current,origin)||currentRun(origin)))onRun(origin)}}>查看第 {origin.epoch} 轮原任务</Button>}
      {match&&onCheck&&['pending','unknown','accepted'].includes(raw.status)&&<Button variant="ghost" disabled={disabled} onClick={onCheck}>核对原启动记录</Button>}
    </div>
    {(conflict||!origin&&current&&current.epoch>1)&&<p className="pw-muted">没有核实这份提案所对应的轮次。不会改用后来的运行记录。</p>}
    {origin&&inline&&!answer&&!navigable&&<p className="pw-muted">这页没有定位到第 {origin.epoch} 轮的原回执。不会打开另一轮代替。</p>}
  </section>;
}
