import {useEffect, useRef, useState} from 'react';
import {bridge} from './bridge';
import type {ReviewedPlace} from './phone-review-place';
import {Accordion, AccordionItem, Button, CheckboxField} from './forms-library';
import {PermissionReview} from './shell-library';
import './phone-scope-review.css';

type Remedy='notifications'|'accessibility';
type Condition={state:'ready'|'blocked'|'unknown';message?:string;settings?:Remedy|ReviewedPlace};
// Older or partially delivered private reviews have no reliable slot evidence.
export type PhoneConditions={notification:Condition&{settingsDetail?:'phone_task_channel'};screen:Condition;capacity?:Pick<Condition,'state'|'message'>};
export type PhoneReviewDisplay={
  goal:string;mode:string;toolLimit:number;modelCallLimit:number;scopeMinutes:number;
  ownerPickedMessage?:boolean;message?:string;conversationId?:string;conversationTitle?:string;modelName?:string;screenDisclosure?:string;
  conditions?:PhoneConditions;manualFirstMainAvailable?:true;firstBackgroundResultWatchAvailable?:true;readCurrentOnce?:true;
};
type Live={reviewHandle:string;mode?:string;expiresInMs?:number;documentEpoch?:number;draftRequestId?:string;conversationId?:string;originalDraft?:{composer:string;conversation:string};ownerPickedMessage?:boolean;manualFirstMainAvailable?:true;firstBackgroundResultWatchAvailable?:true;readCurrentOnce?:true};

const privatePlace = (mode:string) => mode==='background'?'后台独立屏':mode==='main_screen'?'手机主屏':undefined;
const readinessLine = (condition:Pick<Condition,'state'|'message'>|undefined, ready:string) => {
  if(condition?.state==='ready')return condition.message||ready;
  const state=condition?.state==='blocked'?'需要准备':'尚未核实';
  return condition?.message?state+'：'+condition.message:state;
};

function privateDecision(conditions:PhoneConditions,expired:boolean,planned?:string,inspection=false,canInspect=false){
  const {notification,capacity,screen}=conditions;
  if(expired)return {title:'需要重新核对',detail:'上次核对已失效。原目标还在，请重新核对本机条件。'};
  if(!planned)return {title:'原执行屏幕尚未核实',detail:'本次不提交目标，也不会替你选另一个屏。请回到原准备重新核对。'};
  if(notification?.state!=='ready')return {
    title:notification?.state==='blocked'?'任务通知还没就绪':'暂不能核实任务通知',
    detail:'先核对任务通知，让你能随时停止。原目标保留，没有提交。'};
  if(capacity?.state!=='ready')return {
    title:capacity?.state==='blocked'?'这个执行位置暂不可用':'暂不能核实执行位置',
    detail:'原目标保留，不会排队自动开跑，也不会换屏。'};
  if(inspection)return canInspect?
    {title:'这次只读一次',detail:'打开你要读的页面，再在任务控制条确认。这里只登记目标，不会立即读屏、点击或调用模型。'}:
    {title:'还不能确认一次只读',detail:'先准备主屏并重新核对。若本机不能核对眼前窗口，本次不会读取或换作其他任务。'};
  if(screen?.state!=='ready')return {
    title:screen?.state==='blocked'?'所选屏幕还没就绪':'所选屏幕准备待核实',
    detail:'如果现在提交，会开始原任务，模型可先处理目标。需要用屏时才重新核对所选屏幕，条件不足不会改用另一块屏。'};
  return {title:'可以提交这次目标',detail:'本次核对没有启动任务，也没有读屏。真正用屏时仍会检查原位置。'};
}

/** Explain only an actual original route. No lookup, new task or result inferred from a review. */
function PrivateReviewDetails({shown,live,past=false}:{shown:PhoneReviewDisplay;live:Live|null;past?:boolean}){
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
      <p>{shown.readCurrentOnce?'只读你确认时显示的窗口，不替你打开或锁定某个应用。若不能核对唯一数字，会如实说明，不再读一次。':'本次不单独锁定应用副本。可在目标中写明要用的应用；实际应用和窗口仍按现有规则核对，不会因此获得新权限。'}</p>
      <dl className="pw-phone-review-limits">
        {past&&<div><dt>计划位置</dt><dd>{privatePlace(shown.mode)||'未核实'}</dd></div>}
        {!shown.readCurrentOnce&&shown.modelName&&<div><dt>使用模型</dt><dd><bdi>{shown.modelName}</bdi></dd></div>}
        <div><dt>调用上限</dt><dd>工具 {shown.readCurrentOnce?0:shown.toolLimit} 次 · 模型 {shown.readCurrentOnce?0:shown.modelCallLimit} 次</dd></div>
        <div><dt>本次期限</dt><dd>{duration}</dd></div>
      </dl>
    </section>
  </div>;
}

// Exit retains the original description only. Re-checking prepares a review, never a task.
export function PhoneScopeReview({shown,live,pending,unknown,onStart,onClose,onCheck,onSettings,onPlace,error,terms}:{
  shown:PhoneReviewDisplay;live:Live|null;pending:boolean;unknown:boolean;onStart?:(manualFirstMain?:true,watchReviewedHandle?:string,readCurrentOnce?:true)=>void;onClose:()=>void;
  onCheck?:(handle:string)=>void;onSettings?:(handle:string,target:Remedy)=>void;onPlace?:(handle:string,place:ReviewedPlace)=>void;error?:string;terms?:{constraints?:string;deliverable?:string;completionCriteria?:string};
}){
  const conditions=shown.conditions,handle=live?.reviewHandle,epoch=live?.documentEpoch;
  const [invalid,setInvalid]=useState(''),invalidNow=useRef('');
  const [watchHandle,setWatchHandle]=useState(''),watchNow=useRef('');
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
  const planned=privatePlace(shown.mode);
  const inspection=shown.readCurrentOnce===true||live?.readCurrentOnce===true;
  const canInspect=!!conditions&&shown.readCurrentOnce===true&&live?.readCurrentOnce===true&&shown.mode==='main_screen'&&
    !!shown.conversationId&&shown.conversationId===live.conversationId&&live?.manualFirstMainAvailable===true&&
    hasNotification&&hasSlot&&screen?.state==='ready';
  const canSubmit=inspection?canInspect:!conditions||!!planned&&hasNotification&&hasSlot;
  const backgroundWatch=shown.mode==='background'&&live?.firstBackgroundResultWatchAvailable===true&&
    !!shown.conversationId&&shown.conversationId===live.conversationId&&hasNotification&&hasSlot&&screen?.state==='ready';
  const issue=!hasNotification?notification:screen?.state!=='ready'?screen:undefined;
  const retry=!!conditions&&(expired||!canSubmit||screen?.state!=='ready');
  // This is a stock Settings remedy on the original linked draft, not proof that a particular permission is off.
  const original=live?.originalDraft;
  const mainSettingsFirst=!!handle&&canSubmit&&!inspection&&shown.mode==='main_screen'&&live?.mode==='main_screen'&&
    !!shown.conversationId&&shown.conversationId===live.conversationId&&!!original?.composer&&original.conversation===shown.conversationId&&
    screen?.state==='blocked'&&screen.settings==='accessibility'&&!!onSettings&&!!onCheck&&!!onStart;
  const submit=live&&onStart&&!unknown&&canSubmit&&!expired?()=>{
    if(!current())return;
    if(inspection)onStart(true,undefined,true);
    else onStart(undefined,backgroundWatch&&watchNow.current===handle?handle:undefined);
  }:undefined;
  const controls={error,onReject:live&&!pending?onClose:undefined,pending,onApprove:mainSettingsFirst?undefined:submit};
  // No private original/conditions in a channel review. Retain that flow unchanged.
  if(!conditions)return <PermissionReview {...controls} title={shown.goal} context={'在'+(shown.mode==='background'?'后台独立屏':'手机主屏')+'执行'}
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
  const decision=pending?{title:'正在等待回执',detail:'暂不能确认这一步的结果，请勿重复提交。'}:
    mainSettingsFirst&&!expired?{title:'先准备手机主屏',detail:'原目标保留。查看设置后请回来重新核对，不会自动提交。仍可先提交让模型处理；若之后需要用屏但主屏仍不可用，任务可能停住。'}:
    privateDecision(conditions,expired,planned,inspection,canInspect);
  const target=issue?.state==='blocked'&&!expired?issue.settings:undefined;
  const system=target==='notifications'||planned&&target==='accessibility'?target:undefined;
  const taskChannel=shown.mode==='main_screen'&&system==='notifications'&&notification?.settingsDetail==='phone_task_channel';
  const screenTarget=screen?.state==='blocked'&&!expired?screen.settings:undefined;
  const place=shown.mode==='background'&&(screenTarget==='background_setup'||screenTarget==='main_screen_choice')?screenTarget:undefined;
  return <div className="pw-phone-scope-review"><PermissionReview {...controls} title={decision.title} context={planned?'计划使用'+planned:undefined}
    approveLabel={inspection?'到眼前页面后只读一次':screen?.state==='ready'?'确认提交并开始':'仍提交并开始'}>
    <div className="pw-phone-review-step"><p role="status">{decision.detail}</p>
      {!expired&&!pending&&live&&<dl className="pw-phone-review-limits" aria-label="这次本机检查">
        <div><dt>任务通知</dt><dd>{readinessLine(notification,'满足通知前置')}</dd></div>
        <div><dt>运行位置</dt><dd>{planned?readinessLine(capacity,'有可用执行位置'):'未核实原位置'}</dd></div>
        <div><dt>{planned||'原屏幕'}准备</dt><dd>{planned?readinessLine(screen,'满足原屏幕准备条件'):'未核实原屏幕'}</dd></div>
      </dl>}
      {retry&&<div className="pw-phone-review-remedies">
        {system&&onSettings&&<Button variant={canSubmit&&!mainSettingsFirst?'secondary':'primary'} disabled={!live||pending}
          onClick={()=>{if(!handle||!current())return;invalidate(handle);onSettings(handle,system)}}>
          {taskChannel?'查看「手机任务」通知类别':system==='notifications'?'查看任务通知设置':mainSettingsFirst?'核对主屏设置':'查看无障碍设置'}
        </Button>}
        {place&&onPlace&&<Button variant="secondary" disabled={!live||pending} onClick={()=>{
          if(!handle||!current())return;onPlace(handle,place);
        }}>{place==='background_setup'?'查看独立屏准备':'回原草稿选择执行位置'}</Button>}
        {onCheck&&<Button variant={!system&&!place&&(expired||!canSubmit)?'primary':'ghost'} disabled={!live||pending}
          onClick={()=>{if(handle&&!pending&&!document.hidden){invalidate(handle);onCheck(handle)}}}>重新核对</Button>}
        {mainSettingsFirst&&submit&&<Button variant="secondary" disabled={pending} onClick={submit}>仍提交，需用屏时再核对</Button>}
      </div>}
      {taskChannel&&onSettings&&<p className="pw-field-hint">若只打开应用通知设置，请在其中选择「手机任务」。返回后重新核对原目标。</p>}
      {place&&onPlace&&<p className="pw-field-hint">{place==='background_setup'?'在准备页选「后台独立屏 → 连接与检查」。回来后再核对原目标，不会自动连接或提交。':'主屏会占用手中的屏幕。只有你在原草稿选择后才会更换位置；仍需重新核对。'}</p>}
      {backgroundWatch&&!expired&&onStart&&<div className="pw-phone-review-remedies">
        <CheckboxField isDisabled={pending} isSelected={!!handle&&watchHandle===handle} onChange={checked=>{
          if(!handle||!current())return;watchNow.current=checked?handle:'';setWatchHandle(watchNow.current);
        }}>本轮结束后提醒我查看</CheckboxField>
        <p className="pw-field-hint">仅尝试在本机通知栏提示，不带任务内容。没有收到也可回原私聊查看。</p>
      </div>}
      {!inspection&&shown.mode==='main_screen'&&live?.manualFirstMainAvailable===true&&canSubmit&&screen?.state==='ready'&&!expired&&onStart&&
        <div className="pw-phone-review-remedies">
          <CheckboxField isDisabled={pending} isSelected={!!handle&&watchHandle===handle} onChange={checked=>{
            if(!handle||!current())return;watchNow.current=checked?handle:'';setWatchHandle(watchNow.current);
          }}>手动开始的这轮结束时提醒我</CheckboxField>
          <p className="pw-field-hint">只在这台手机提醒。点通知回原工作；若没有收到，也可回原私聊查看。</p>
          <Button variant="secondary" disabled={pending} onClick={()=>{if(current())onStart(true,watchNow.current===handle?handle:undefined)}}>到目标页面后手动开始</Button>
          <p className="pw-field-hint">切到你要用的页面，再从任务控制条确认。首次只读取当前窗口的界面结构，不截图，也不锁定某个 App。可读文字仍按本次模型范围处理。</p>
        </div>}
    </div>
    <PrivateReviewDetails shown={shown} live={live}/>
    {terms&&<dl className="pw-phone-review-limits">
      {terms.constraints&&<div><dt>本次限制</dt><dd dir="auto">{terms.constraints}</dd></div>}
      {terms.deliverable&&<div><dt>要带回什么</dt><dd dir="auto">{terms.deliverable}</dd></div>}
      {terms.completionCriteria&&<div><dt>怎样核对完成</dt><dd dir="auto">{terms.completionCriteria}</dd></div>}
    </dl>}
  </PermissionReview></div>;
}
