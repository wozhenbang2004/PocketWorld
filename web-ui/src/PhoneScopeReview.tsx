import {useEffect, useRef, useState} from 'react';
import {bridge} from './bridge';
import {Button} from './forms-library';
import {PermissionReview} from './shell-library';

type Remedy='notifications'|'accessibility';
type Condition={state:'ready'|'blocked'|'unknown';message?:string;settings?:Remedy};
// Older or partially delivered private reviews have no reliable slot evidence.
export type PhoneConditions={notification:Condition;screen:Condition;capacity?:Pick<Condition,'state'|'message'>};
export type PhoneReviewDisplay={
  goal:string;mode:string;toolLimit:number;modelCallLimit:number;scopeMinutes:number;
  ownerPickedMessage?:boolean;message?:string;conversationId?:string;conversationTitle?:string;modelName?:string;screenDisclosure?:string;
  conditions?:PhoneConditions;
};
type Live={reviewHandle:string;expiresInMs?:number;documentEpoch?:number};

// Exit retains the original description only. Re-checking prepares a review, never a task.
export function PhoneScopeReview({shown,live,pending,unknown,onStart,onClose,onCheck,onSettings,error}:{
  shown:PhoneReviewDisplay;live:Live|null;pending:boolean;unknown:boolean;onStart?:()=>void;onClose:()=>void;
  onCheck?:(handle:string)=>void;onSettings?:(handle:string,target:Remedy)=>void;error?:string;
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
  const stated=!conditions||unknown?[]:expired?[{label:'本机条件',value:'上次核对已失效。原目标还在，请重新核对。'}]:[
    {label:'执行位置',value:capacity?.message||'暂不能核实这个执行位置是否空闲。'},
    ...(!hasNotification?[{label:'任务通知',value:notification?.message||'任务通知状态无法确认，暂不能提交。'}]:
      hasSlot?[{label:planned+'准备',value:screen?.state==='ready'?'准备条件已就绪；本次核对没有读屏。':screen?.message||'屏幕准备条件暂不能核实。'}]:[]),
  ];
  return <PermissionReview title={shown.goal} context={conditions?'计划使用'+planned:'在'+planned+'执行'}
    reason={[shown.message,shown.screenDisclosure].filter(Boolean).join(' ')}
    fields={[...stated,...(shown.ownerPickedMessage===true?[{label:'目标来源',value:'你在这段对话选定的原消息。若原话或最新交代改变，将暂停提交。'}]:[]),...(shown.conversationId?[{label:'结果返回',value:shown.conversationTitle||'原私人对话'}]:[]),
      ...(shown.modelName?[{label:'本次模型',value:shown.modelName}]:[]),{label:'工具调用上限',value:shown.toolLimit},
      {label:'模型调用上限',value:shown.modelCallLimit},{label:'本次期限',value:shown.scopeMinutes<0?'使用原授权期限':shown.scopeMinutes===0?'不设截止时间':shown.scopeMinutes+' 分钟'}]}
    error={error} onReject={live&&!pending?onClose:undefined}
    onApprove={live&&onStart&&!unknown&&canSubmit&&!expired?()=>{if(current())onStart()}:undefined}
    pending={pending} approveLabel={!conditions?'开始这次任务':screen?.state==='ready'?'确认提交目标':'先提交目标'}>
    {conditions&&!unknown&&retry&&<div>
      {!expired&&<p className="pw-field-hint">{!canSubmit?(!hasNotification?'需要任务通知，让你能随时停止。核对本身不会读屏。':'保留原目标，等执行位置释放后可手动重新核对，不会自动开跑。'):'可以先提交目标。第一次需要用屏时会再检查，实际读到的画面以原任务记录为准。'}</p>}
      <div className="pw-action-row">
        {!expired&&issue?.settings&&onSettings&&<Button variant="secondary" disabled={!live||pending}
          onClick={()=>{if(!handle||!issue.settings||!current())return;invalidate(handle);onSettings(handle,issue.settings)}}>
          {issue.settings==='notifications'?'查看任务通知设置':'查看无障碍设置'}
        </Button>}
        {onCheck&&<Button variant="ghost" disabled={!live||pending} onClick={()=>{if(handle&&!pending&&!document.hidden){invalidate(handle);onCheck(handle)}}}>重新核对</Button>}
      </div>
    </div>}
  </PermissionReview>;
}
