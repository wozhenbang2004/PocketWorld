import {useEffect, useRef, useState} from 'react';
import {bridge} from './bridge';
import type {ReviewedPlace} from './phone-review-place';
import {Accordion, AccordionItem, Button} from './forms-library';
import {PermissionReview} from './shell-library';
import './phone-scope-review.css';

type Remedy='notifications'|'accessibility';
type Condition={state:'ready'|'blocked'|'unknown';message?:string;settings?:Remedy|ReviewedPlace};
// Older or partially delivered private reviews have no reliable slot evidence.
export type PhoneConditions={notification:Condition;screen:Condition;capacity?:Pick<Condition,'state'|'message'>};
export type PhoneReviewDisplay={
  goal:string;mode:string;toolLimit:number;modelCallLimit:number;scopeMinutes:number;
  ownerPickedMessage?:boolean;message?:string;conversationId?:string;conversationTitle?:string;modelName?:string;screenDisclosure?:string;
  conditions?:PhoneConditions;
};
type Live={reviewHandle:string;expiresInMs?:number;documentEpoch?:number;draftRequestId?:string;conversationId?:string;ownerPickedMessage?:boolean};

function privateDecision(conditions:PhoneConditions,expired:boolean){
  const {notification,capacity,screen}=conditions;
  if(expired)return {title:'需要重新核对',detail:'上次核对已失效。原目标还在，请重新核对本机条件。'};
  if(notification?.state!=='ready')return {
    title:notification?.state==='blocked'?'任务通知还没就绪':'暂不能核实任务通知',
    detail:notification?.message||'需要先核实任务通知，让你能随时停止。这次核对没有读屏。'};
  if(capacity?.state!=='ready')return {
    title:capacity?.state==='blocked'?'这个执行位置暂不可用':'暂不能核实执行位置',
    detail:capacity?.message||'暂不能提交目标。原草稿保留，不会排队自动开跑。'};
  if(screen?.state!=='ready')return {
    title:screen?.state==='blocked'?'目标可提交，屏幕还没就绪':'目标可提交，屏幕条件待核实',
    detail:screen?.message||'第一次需要用屏时会再检查；现在没有读屏。'};
  return {title:'可以提交这次目标',detail:'本机准备条件已就绪。这次核对没有启动任务，也没有读屏。'};
}

/** Explain only an actual original route. No lookup, new task or result inferred from a review. */
function PrivateReviewDetails({shown,live,capacityFact,past=false}:{shown:PhoneReviewDisplay;live:Live|null;capacityFact?:string;past?:boolean}){
  const linked=!!shown.conversationId,blank=!linked&&!!live?.draftRequestId&&!live.conversationId;
  const picked=linked&&(shown.ownerPickedMessage===true||live?.ownerPickedMessage===true&&live.conversationId===shown.conversationId);
  const duration=shown.scopeMinutes<0?'使用原授权期限':shown.scopeMinutes===0?'不设截止时间':shown.scopeMinutes+' 分钟';
  return <div className="pw-phone-review-details">
    <section aria-label="目标与原记录"><h3>这次要做什么</h3><p className="pw-phone-review-goal" dir="auto">{shown.goal}</p>
      {picked&&<p>目标来自你选定的原消息；原话或最新交代改变时不会按旧内容提交。</p>}
      {(linked||blank)&&<div className="pw-phone-review-destination"><strong>去哪里看进展和结果</strong>
        {linked?<p>回到<bdi>「{shown.conversationTitle||'原私人对话'}」</bdi>查看这项任务的原记录。</p>:
          <p>回到这份手机草稿查看启动回执。有首轮记录后，可从回执打开那次任务。</p>}</div>}
    </section>
    <section aria-label="执行范围"><h3>{past?'上次核对的范围':'本次核对的范围'}</h3>
      {shown.message&&<p>{shown.message}</p>}{shown.screenDisclosure&&<p>{shown.screenDisclosure}</p>}
      <dl className="pw-phone-review-limits">
        {past&&<div><dt>计划位置</dt><dd>{shown.mode==='background'?'后台独立屏':'手机主屏'}</dd></div>}
        {capacityFact&&<div><dt>执行位置</dt><dd>{capacityFact}</dd></div>}
        {shown.modelName&&<div><dt>使用模型</dt><dd><bdi>{shown.modelName}</bdi></dd></div>}
        <div><dt>调用上限</dt><dd>工具 {shown.toolLimit} 次 · 模型 {shown.modelCallLimit} 次</dd></div>
        <div><dt>本次期限</dt><dd>{duration}</dd></div>
      </dl>
    </section>
  </div>;
}

// Exit retains the original description only. Re-checking prepares a review, never a task.
export function PhoneScopeReview({shown,live,pending,unknown,onStart,onClose,onCheck,onSettings,onPlace,error,terms}:{
  shown:PhoneReviewDisplay;live:Live|null;pending:boolean;unknown:boolean;onStart?:()=>void;onClose:()=>void;
  onCheck?:(handle:string)=>void;onSettings?:(handle:string,target:Remedy)=>void;onPlace?:(handle:string,place:ReviewedPlace)=>void;error?:string;terms?:{constraints?:string;deliverable?:string;completionCriteria?:string};
}){
  const conditions=shown.conditions,handle=live?.reviewHandle,epoch=live?.documentEpoch;
  const [invalid,setInvalid]=useState(''),invalidNow=useRef('');
  function invalidate(id:string){invalidNow.current=id;setInvalid(id)}
  useEffect(()=>{
    if(!conditions||!handle)return;
    const leave=()=>{if(document.hidden)invalidate(handle)};
    const timer=setTimeout(()=>invalidate(handle),Math.max(0,live?.expiresInMs??0));
    document.addEventListener('visibilitychange',leave);leave();
    return()=>{clearTimeout(timer);document.removeEventListener('visibilitychange',leave)};
  },[conditions,handle,live?.expiresInMs]);
  const expired=!!conditions&&!!handle&&(invalid===handle||bridge.getDocumentEpoch()!==epoch);
  const current=()=>!!handle&&!pending&&!document.hidden&&!unknown&&(!conditions||invalidNow.current!==handle&&bridge.getDocumentEpoch()===epoch);
  const notification=conditions?.notification,screen=conditions?.screen,capacity=conditions?.capacity;
  const hasNotification=notification?.state==='ready',hasSlot=capacity?.state==='ready';
  const canSubmit=!conditions||hasNotification&&hasSlot;
  const planned=shown.mode==='background'?'后台独立屏':'手机主屏';
  const issue=!hasNotification?notification:!hasSlot?undefined:screen?.state!=='ready'?screen:undefined;
  const retry=!!conditions&&(expired||!canSubmit||screen?.state!=='ready');
  const controls={error,onReject:live&&!pending?onClose:undefined,pending,
    onApprove:live&&onStart&&!unknown&&canSubmit&&!expired?()=>{if(current())onStart()}:undefined};
  // No private original/conditions in a channel review. Retain that flow unchanged.
  if(!conditions)return <PermissionReview {...controls} title={shown.goal} context={'在'+planned+'执行'}
    reason={[shown.message,shown.screenDisclosure].filter(Boolean).join(' ')} approveLabel="开始这次任务"
    fields={[...(shown.ownerPickedMessage===true?[{label:'目标来源',value:'你在这段对话选定的原消息。若原话或最新交代改变，将暂停提交。'}]:[]),
      ...(shown.conversationId?[{label:'结果返回',value:shown.conversationTitle||'原私人对话'}]:[]),
      ...(shown.modelName?[{label:'本次模型',value:shown.modelName}]:[]),{label:'工具调用上限',value:shown.toolLimit},
      {label:'模型调用上限',value:shown.modelCallLimit},{label:'本次期限',value:shown.scopeMinutes<0?'使用原授权期限':shown.scopeMinutes===0?'不设截止时间':shown.scopeMinutes+' 分钟'}]}/>;
  // The owning drawer renders the original receipt and its own read-only lookup immediately after this block.
  if(unknown)return <div className="pw-phone-review-unknown">
    <Accordion><AccordionItem id="previous-phone-review" title="查看上次核对的目标与范围">
      <PrivateReviewDetails shown={shown} live={live} past/>
    </AccordionItem></Accordion>
    {error&&<p role="alert" className="pw-error">{error}</p>}
  </div>;
  const decision=pending?{title:'正在等待回执',detail:'暂不能确认这一步的结果，请勿重复提交。'}:privateDecision(conditions,expired);
  const target=issue?.state==='blocked'&&!expired?issue.settings:undefined;
  const system=target==='notifications'||target==='accessibility'?target:undefined;
  const place=shown.mode==='background'&&(target==='background_setup'||target==='main_screen_choice')?target:undefined;
  return <div className="pw-phone-scope-review"><PermissionReview {...controls} title={decision.title} context={'计划使用'+planned}
    approveLabel={screen?.state==='ready'?'确认提交目标':'先提交目标'}>
    <div className="pw-phone-review-step"><p role="status">{decision.detail}</p>
      {!expired&&canSubmit&&screen?.state!=='ready'&&screen?.message&&<p>第一次需要用屏时会再检查。实际读到的内容以原任务记录为准。</p>}
      {retry&&<div className="pw-phone-review-remedies">
        {system&&onSettings&&<Button variant={canSubmit?'secondary':'primary'} disabled={!live||pending}
          onClick={()=>{if(!handle||!current())return;invalidate(handle);onSettings(handle,system)}}>
          {system==='notifications'?'查看任务通知设置':'查看无障碍设置'}
        </Button>}
        {place&&onPlace&&<Button variant="secondary" disabled={!live||pending} onClick={()=>{
          if(!handle||!current())return;onPlace(handle,place);
        }}>{place==='background_setup'?'查看独立屏准备':'回原草稿选择执行位置'}</Button>}
        {onCheck&&<Button variant={!system&&(expired||!canSubmit)?'primary':'ghost'} disabled={!live||pending}
          onClick={()=>{if(handle&&!pending&&!document.hidden){invalidate(handle);onCheck(handle)}}}>重新核对</Button>}
      </div>}
      {place&&onPlace&&<p className="pw-field-hint">{place==='background_setup'?'在准备页选「后台独立屏 → 连接与检查」。回来后再核对原目标，不会自动连接或提交。':'主屏会占用手中的屏幕。只有你在原草稿选择后才会更换位置；仍需重新核对。'}</p>}
    </div>
    <PrivateReviewDetails shown={shown} live={live}
      capacityFact={!expired&&(!hasNotification||hasSlot)?capacity?.message||(hasSlot?'本次核对时有可用位置。':capacity?.state==='blocked'?'这个执行位置暂不可用。':'暂不能核实这个执行位置是否空闲。'):undefined}/>
    {terms&&<dl className="pw-phone-review-limits">
      {terms.constraints&&<div><dt>本次限制</dt><dd dir="auto">{terms.constraints}</dd></div>}
      {terms.deliverable&&<div><dt>要带回什么</dt><dd dir="auto">{terms.deliverable}</dd></div>}
      {terms.completionCriteria&&<div><dt>怎样核对完成</dt><dd dir="auto">{terms.completionCriteria}</dd></div>}
    </dl>}
  </PermissionReview></div>;
}
