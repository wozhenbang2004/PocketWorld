import {useEffect,useRef} from 'react';
import {Drawer} from './shell-library';
import {Button,IconButton} from './forms-library';
import {IconClose} from './icons';
import {registerOverlayBack} from './overlay-back';
import {phoneRecordedActionLabel} from './PhoneOriginalActionReview';

export type PhoneNotice={token:string;activityHandle?:string;taskId:string;runId:string;epoch:number;current:boolean;checking:boolean;status:string;createdAt:number;updatedAt:number;readAt:number;lastAction?:{tool:string;state:string;createdAt:number}};
const pos=(x:unknown):x is number=>typeof x==='number'&&Number.isSafeInteger(x)&&x>0;
const id=(x:unknown):x is string=>typeof x==='string'&&!!x.trim()&&x.length<=100;
export function originalPhoneNotice(value:unknown):PhoneNotice|null{
  if(!value||typeof value!=='object')return null;const v=value as Record<string,any>;
  if(v.format!=='pocketworld.private-phone-notification.v1'||!id(v.noticeHandle)||!id(v.taskId)||!id(v.runId)||!pos(v.epoch)||
    typeof v.current!=='boolean'||typeof v.status!=='string'||!pos(v.createdAt)||!pos(v.updatedAt)||!pos(v.readAt))return null;
  if(v.returnSource==='activity'&&!id(v.activityHandle))return null;
  const a=v.lastAction;return {token:v.noticeHandle,...(v.returnSource==='activity'?{activityHandle:v.activityHandle}:{}),taskId:v.taskId,runId:v.runId,epoch:v.epoch,current:v.current,checking:v.checking===true,status:v.status,createdAt:v.createdAt,updatedAt:v.updatedAt,readAt:v.readAt,
    ...(a&&phoneRecordedActionLabel(a.tool)&&typeof a.state==='string'&&pos(a.createdAt)?{lastAction:{tool:a.tool,state:a.state,createdAt:a.createdAt}}:{})};
}
const states:Record<string,string>={running:'登记为执行中',preparing:'准备中',paused:'已暂停',completed:'已结束',cancelled:'已取消',stopped:'已停止',failed:'失败',awaiting_user:'等待你处理',needs_confirmation:'等待确认',interrupted:'已中断',reconciling:'待核对'};
const stepStates:Record<string,string>={prepared:'仅准备，未确认发生',unknown:'是否发生待核对',receipt:'有原工具回执',failed:'记录为失败',resolved:'主人已核对，非自动验证'};
const time=(n:number)=>new Date(n).toLocaleString(undefined,{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
export function PhoneNotificationReturn({notice,busy,changed,onRefresh,onSame,onClose}:{notice:PhoneNotice;busy:boolean;changed:boolean;onRefresh?:()=>void;onSame?:()=>void;onClose:()=>void}){
  const close=useRef(onClose);close.current=onClose;useEffect(()=>registerOverlayBack(()=>close.current()),[]);
  const action=notice.lastAction, source=notice.activityHandle?'所选任务':'原通知';
  return <Drawer.Root open onOpenChange={next=>{if(!next)onClose()}} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay"/>
    <Drawer.Content className="pw-drawer" aria-describedby="pw-phone-notice-description"><div className="pw-drawer-handle"/>
      <header className="pw-drawer-header"><div><Drawer.Title>{notice.activityHandle?'所选任务的':'通知里的'}第 {notice.epoch} 轮手机执行</Drawer.Title>
        <Drawer.Description id="pw-phone-notice-description">{changed||!notice.current?'这里是'+source+'的记录，没有切换到后来的执行。':'这里只读'+source+'的记录，没有发送手机操作。'}</Drawer.Description></div>
        <IconButton icon={IconClose} aria-label={notice.activityHandle?'收起所选轮次记录':'收起原手机通知'} onClick={onClose}/></header>
      <div className="pw-drawer-body"><section className="pw-selected-object" aria-label="这轮的原始记录">
        <h3>{states[notice.status]||'已保存执行记录'}</h3><p>开始登记：{time(notice.createdAt)}<br/>轮次记录更新：{time(notice.updatedAt)}<br/>本次查阅：{time(notice.readAt)}</p>
        {action?<><h4>最后一笔已记账的手机动作</h4><p>{phoneRecordedActionLabel(action.tool)} · {stepStates[action.state]||'已有动作记录'} · 登记于 {time(action.createdAt)}</p><small>记账不等于目标完成。</small></>
        :<p className="pw-muted">本轮没有可核对的手机动作记录。这不证明设备上什么都没发生。</p>}
      </section><div className="pw-protected-actions">{onSame&&notice.current&&!changed&&<Button variant="secondary" size="small" disabled={busy} onClick={onSame}>打开原轮任务</Button>}{onRefresh&&<Button variant="ghost" size="small" disabled={busy} onClick={onRefresh}>{busy?'正在核对…':'重新核对这轮'}</Button>}</div></div>
    </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
