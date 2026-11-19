import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
import {IconRefresh} from './icons';
import {PermissionReview} from './shell-library/Review';
import {bridge} from './bridge';
import {phoneRunIdentity} from './phone-start';
import {isPrivateLocalPhone} from './PhonePauseInstruction';

type Row=Record<string,any>;
type Original={taskId:string;runId:string;epoch:number};
const names:Record<string,string>={
  login_code:'登录验证', payment_confirmation:'付款确认', transfer_confirmation:'转账确认',
  permission_elevation:'安装或权限确认', sensitive_target_uncertain:'待你辨认的敏感页面'
};
const identity=(a:Row|undefined,b:Original)=>a?.taskId===b.taskId&&a.runId===b.runId&&a.epoch===b.epoch;
const op=(v:unknown):v is string=>typeof v==='string'&&/^[\da-f-]{36}$/i.test(v);
const original=(t:Row)=>isPrivateLocalPhone(t)&&(t.executionMode||t.raw?.executionMode)==='main_screen'?phoneRunIdentity(t):null;
function returned(t:Row,id:Original){const r=t.raw?.mainPhoneOwnerReturn;
  return r&&identity(r,id)&&op(r.operationId)&&r.ownerHandedBack===true&&r.businessResultVerified===false?r:null;
}
export function mainPhoneStep(t:Row){
  const id=original(t);if(!id||t.status!=='awaiting_user')return null;
  const e=t.raw?.evidence,r=e?.nativeSensitiveStep,proof=e?.mainPhoneOwnerStep||t.raw?.mainPhoneOwnerStep;
  const verified=r?.code==='native_sensitive_owner_required'&&r.status==='not_dispatched'&&
    [r.ok,r.accepted,r.nativeDispatched,r.automaticReplayAllowed,r.executionAuthorized].every(value=>value===false)&&e.automaticReplayAllowed===false&&e.originalEffectsRetained===true;
  const reason=verified?r.reason:identity(proof,id)&&t.raw?.taskCoreReadLimited===true?proof.reason:null;
  if(typeof reason!=='string'||!Object.hasOwn(names,reason))return null;
  const operation=identity(proof,id)&&proof.reason===reason&&op(proof.operationId)?proof.operationId:null;
  const ack=returned(t,id),check=e?.mainOwnerPageCheck;
  const pageNotVerified=proof?.returnPageNotVerified===true||check?.state==='not_verified'&&check?.operationId===operation&&check?.pauseRevision===ack?.pauseRevision;
  if(operation&&ack?.operationId===operation&&!pageNotVerified)return null;
  const surfaceKnown=typeof proof?.observedSurfaceKey==='string'&&/^[a-f0-9]{64}$/.test(proof.observedSurfaceKey)||proof?.observedSurfaceKnown===true;
  return {...id,operationId:operation,label:names[reason],pageNotVerified,surfaceKnown};
}

/** This page can verify the original notice or leave PocketWorld. It cannot return control to the agent. */
export function PhoneMainOwnerStep({task,visible,can,run,onProcess}:{task:Row;visible:boolean;can:(action:string)=>boolean;run:(action:string,args:Row)=>Promise<any>;onProcess:()=>void}){
  const id=original(task),step=mainPhoneStep(task),ack=id&&returned(task,id);
  const [busy,setBusy]=useState(false),[message,setMessage]=useState(''),[failure,setFailure]=useState('');
  const revision=useRef(0),flight=useRef(false),life=useRef(true);
  const key=JSON.stringify([id?.taskId,id?.runId,id?.epoch,step?.operationId,task.status]);
  const current=useRef({key,visible});current.current={key,visible};
  useEffect(()=>{life.current=true;return()=>{life.current=false;revision.current++}},[]);
  useEffect(()=>{const invalidate=()=>{revision.current++;flight.current=false;setBusy(false);setMessage('');setFailure('')};invalidate();
    const changed=()=>{if(document.hidden)invalidate()};document.addEventListener('visibilitychange',changed);return()=>document.removeEventListener('visibilitychange',changed);
  },[key,visible]);
  if(!id||(!step&&!ack))return null;
  if(!step)return <section className="pw-work-current-progress" aria-label="本人交还记录">
    <h3>已交还原任务</h3><p>你交还了控制。人工步骤的业务结果没有因此被标为成功。</p>
    <Button variant="ghost" onClick={onProcess}>查看原执行记录</Button>
  </section>;
  const act=async(leave:boolean)=>{
    const action=leave?'phone.ownerStep.leave':'phone.ownerStep.review';
    if(!step.operationId||!visible||document.hidden||flight.current||!can(action))return;
    const target={taskId:step.taskId,runId:step.runId,epoch:step.epoch,operationId:step.operationId};
    const ticket=++revision.current,epoch=bridge.getDocumentEpoch();flight.current=true;setBusy(true);setMessage('');setFailure('');
    const accepts=()=>life.current&&ticket===revision.current&&current.current.visible&&current.current.key===key&&!document.hidden&&bridge.getDocumentEpoch()===epoch;
    try{
      const r=await run(action,leave?{...target,confirmed:true}:target);if(!accepts())return;
      if(!identity(r,target)||r.operationId!==target.operationId||r.resumed!==false||typeof r.returnNoticePosted!=='boolean'||typeof r.returnControlVisible!=='boolean')throw Error('没有收到原任务的完整回执。');
      setMessage(leave?'任务仍暂停，没有代你操作手机。':r.returnControlVisible?'这次任务的悬浮控制条已有交还按钮。':r.returnNoticePosted?'系统已登记这次任务的通知交还按钮。':'暂未核对到这次任务的交还按钮。仍可查看或停止原任务。');
    }catch(error){if(accepts())setFailure(error instanceof Error?error.message:'原任务交还入口无法核对，未继续执行。')}
    finally{if(accepts()){flight.current=false;setBusy(false)}}
  };
  return <PermissionReview title={step.pageNotVerified?'还没核对到原应用页面':'这一步由你亲自处理'} context={step.label} pending={busy} error={failure}>
    <p>{step.pageNotVerified?'上次交还后没有核对到原应用的非敏感页面。本次页面没有发给模型，没有派发新操作。':'这一步尚未派发，请在原应用亲自决定。'} 不要把密码、验证码或付款信息发到这里。</p>
    {step.operationId&&step.surfaceKnown?<><p className="pw-field-hint">离开敏感页面，回到原应用，展开悬浮的「任务」，点「本人已处理，交还」。也可用这件工作的安卓通知。点通知正文只会打开原任务。</p>
      <div className="pw-action-row">
        {can('phone.ownerStep.leave')&&<Button variant="primary" disabled={!visible||busy} onClick={()=>void act(true)}>切回手机，亲自处理</Button>}
        {can('phone.ownerStep.review')&&<Button variant="ghost" leadingIcon={IconRefresh} disabled={!visible||busy} onClick={()=>void act(false)}>核对交还入口</Button>}
      </div></>:<p className="pw-field-hint">没有可核对的原操作或原应用页面，保留现有记录，不从这里交还或重开一轮。</p>}
    {message&&<p role="status">{message}</p>}
  </PermissionReview>;
}
