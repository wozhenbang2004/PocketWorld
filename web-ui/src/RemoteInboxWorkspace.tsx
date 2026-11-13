import {useEffect,useId,useLayoutEffect,useMemo,useRef,useState,type ReactNode} from 'react';
import {MotionFeedback,useCollectionMotion} from './library/journey-motion';
import { IconArrowLeft, IconArrowUpRight, IconAttention, IconCheck, IconLink, IconNotification, IconRefresh, IconSettings } from './icons';
import {Button,IconButton,SettingsDialog,TextField} from './forms-library';
import {bridge} from './bridge';
import './remote-inbox.css';
import {NotificationTaskAttention} from './NotificationTaskAttention';
import {NotificationTeamAttention,type TeamAttentionLocator} from './NotificationTeamAttention';
export type NotificationViewMemory={remoteFilter?:'all'|'unread'|'issues'|'archived';teamCursor?:string;teamItemCursor?:string;teamPrior?:{run:string;item:string}[];teamFocus?:string;taskMode?:'attention'|'taskUpdates';taskCursor?:string;taskPrior?:string[];taskFocus?:string};

type Event = {
  seq:number; source_key:string; kind:string; title:string; body:string;
  origin:string; url:string; priority:'attention'|'normal'; created_at:string; read:boolean; archived?:boolean;
};
type State = {
  available?:boolean; configured?:boolean; endpoint?:string; live?:boolean; running?:boolean;backgroundScheduled?:boolean;
  cursor?:number; unread?:number; error?:string; events?:Event[]; configId?:string;
  openRevision?:number; selectedSeq?:number; selectionError?:string;
  lastSync?:{status:string;finishedAt:number;lastSuccessAt:number};
  push?:{supported:boolean;enabled:boolean;status?:string;distributor?:string;distributors?:{id:string;label:string}[];registeredAt?:number;verifiedAt?:number;lastWakeAt?:number;lastCatchUpAt?:number};
  notificationPermission?:{appEnabled:boolean;normalEnabled:boolean;attentionEnabled:boolean};
};
type Result = {status?:string;newCount?:number;message?:string;state?:State};

function safeUrl(url:string):string|undefined {
  try {
    const parsed = new URL(url);
    return parsed.protocol==='https:' && !parsed.username && !parsed.password && !parsed.hash ? parsed.href : undefined;
  } catch { return undefined; }
}
function when(iso:string) {
  const ms=Date.parse(iso);
  return Number.isFinite(ms)?new Date(ms).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}):'时间未知';
}
export default function RemoteInboxWorkspace({state,navigationTrigger,onBack,registerBack,onOpenTask,onOpenTeamAttention,memory}: {state?:State;onOpenTask:(id:string,review?:boolean)=>void;onOpenTeamAttention:(locator:TeamAttentionLocator)=>void;memory:NotificationViewMemory;navigationTrigger?:ReactNode;onBack:()=>void;registerBack?:(handler:(()=>boolean)|null)=>void}) {
  const [endpoint,setEndpoint]=useState('');
  const [token,setToken]=useState('');
  const [pushToken,setPushToken]=useState('');
  const [distributor,setDistributor]=useState('');
  const [configOpen,setConfigOpen]=useState(false);
  const [working,setWorking]=useState(false);
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  const [filter,setFilter]=useState<'all'|'unread'|'issues'|'archived'>(memory.remoteFilter||'all');
  useEffect(()=>{memory.remoteFilter=filter},[filter]);
  const entering=useRef(false),alive=useRef(true);
  const [disconnectConfirm,setDisconnectConfirm]=useState(false);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  useEffect(()=>{setError('');setMessage('');setToken('');setPushToken('');setDistributor('');setEndpoint(state?.endpoint||'');setDisconnectConfirm(false)},[state?.configId]);
  useEffect(()=>{if(!state?.selectedSeq)return;const item=state.events?.find(item=>item.seq===state.selectedSeq);setFilter(item?.archived?'archived':'all')},[state?.openRevision]);
  useEffect(()=>{if(!state?.selectedSeq)return;const row=list.current?.querySelector<HTMLElement>(`[data-motion-key="${state.selectedSeq}"]`);row?.scrollIntoView({block:'nearest'});row?.focus({preventScroll:true})},[state?.openRevision,filter]);
  const list=useRef<HTMLElement>(null),configId=useId();
  const readFocus=useRef<{seq:number;index:number;origin:HTMLElement;identity:string}|null>(null);
  useEffect(()=>{if(state?.endpoint)setEndpoint(previous=>previous||state.endpoint||'')},[state?.endpoint]);
  useEffect(()=>{registerBack?.(configOpen?()=>{if(!working){if(disconnectConfirm)setDisconnectConfirm(false);else setConfigOpen(false)}return true}:null);return()=>registerBack?.(null)},[configOpen,disconnectConfirm,working,registerBack]);
  const hasSnapshot=typeof state?.configured==='boolean'&&Array.isArray(state.events);
  const hasEvents=Array.isArray(state?.events)&&state.events.some(item=>item&&Number.isSafeInteger(item.seq));
  const entries=useMemo(()=>{
    const all=Array.isArray(state?.events)?state.events:[];
    return [...all].filter(item=>item&&Number.isSafeInteger(item.seq))
      .filter(item=>filter==='archived'?item.archived:!item.archived&&(filter==='all'||filter==='unread'&&!item.read||filter==='issues'&&item.kind==='github_issue'))
      .sort((a,b)=>b.seq-a.seq);
  },[state?.events,filter]);
  const listIdentity=JSON.stringify([state?.configId||'',filter]);
  const listRevision=JSON.stringify(entries.map(item=>[item.seq,item.read,item.archived,item.priority]));
  useCollectionMotion(list,listIdentity,listRevision,{active:!!state?.configured&&Array.isArray(state?.events)&&!configOpen});
  useLayoutEffect(()=>{
    const returning=readFocus.current;
    if(!returning)return;
    if(returning.identity!==listIdentity){readFocus.current=null;return;}
    const currentIndex=entries.findIndex(item=>item.seq===returning.seq);
    if(working||currentIndex>=0&&!entries[currentIndex].read)return;
    readFocus.current=null;
    if(document.activeElement!==document.body&&document.activeElement!==returning.origin)return;
    const items=list.current?.querySelectorAll<HTMLElement>('[data-motion-key]');
    const next=items?.[currentIndex>=0?currentIndex:Math.min(returning.index,Math.max(0,items.length-1))];
    (next?.querySelector<HTMLElement>('button:not(:disabled)')||next||list.current)?.focus({preventScroll:true});
  },[listRevision,listIdentity,working]);
  const markRead=(seq:number)=>{
    const origin=document.activeElement;
    if(origin instanceof HTMLElement&&origin.closest('[data-motion-key]')?.getAttribute('data-motion-key')===String(seq))readFocus.current={seq,index:entries.findIndex(item=>item.seq===seq),origin,identity:listIdentity};
    const item=entries.find(item=>item.seq===seq);if(item)void act('remoteInbox.mark',identity(item));
  };
  const identity=(item:Event)=>({configId:state?.configId,seq:item.seq,source_key:item.source_key});
  const update=(items:Event[],field:'read'|'archived',value:boolean)=>void act('remoteInbox.update',{configId:state?.configId,items:items.map(item=>({seq:item.seq,source_key:item.source_key})),field,value});
  async function act(name:string,args:Record<string,unknown>={}) {
    if(entering.current)return;
    entering.current=true;
    setWorking(true);setError('');setMessage('');
    try {
      const response=await bridge.command(name,args) as Result;
      if(!alive.current)return;
      if(name==='remoteInbox.configure'){setToken('');setConfigOpen(false);setMessage('已保存服务配置；后台补收按系统调度，可另行开启约 50 秒主动检测。');}
      else if(name==='remoteInbox.sync'){
        if(response.status==='failed')setError(response.message||'同步失败');
        else setMessage(response.status==='waiting_network'?'当前没有网络，已请求系统在网络可用时补收':response.status==='more_pending'?'本批同步完成，仍有历史消息待补收，请继续同步':response.status==='already_running'?'已有同步正在进行':response.status==='not_configured'?'请先配置通知服务':'同步完成 · 新增 '+String(response.newCount||0)+' 条');
      } else if(name==='remoteInbox.enablePush'){setPushToken('');setMessage('已请求登记接收器。收到加密唤醒并成功补收后，才会出现确认记录。');}
      else if(name==='remoteInbox.disablePush')setMessage('已在本机停用，已请求退订。服务端注销若遇断网，将由 30 天登记租约到期清理；也可在自己的服务端撤销。');
      else if(name==='remoteInbox.startLive')setMessage('已请求启动主动检测；运行状态不代表网络畅通或消息已送达。');
      else if(name==='remoteInbox.notificationPermission')setMessage(response.status==='permission_requested'?'请在系统弹窗中允许通知；已接收的本机消息会在权限允许后尝试显示。':'已读取通知权限。若系统或某个渠道已关闭，请打开系统通知设置。');
      else if(name==='remoteInbox.disconnect'){setToken('');setEndpoint('');setMessage('已断开，已清除本机凭据和历史远程通知。');}
    } catch(e) {if(alive.current)setError(e instanceof Error?e.message:String(e));}
    finally{entering.current=false;if(alive.current)setWorking(false)}
  }
  return <main className="pw-remote-inbox pw-page" aria-label="通知与待处理中心">
    <header className="pw-remote-inbox-head">
      <div className="pw-remote-title"><IconButton icon={IconArrowLeft} aria-label="返回对话" onClick={onBack}/><h1>通知</h1></div>
      <div className="pw-remote-inbox-actions">
        {state?.configured&&<><IconButton icon={IconRefresh} aria-label="同步通知" disabled={working} onClick={()=>void act('remoteInbox.sync')}/><IconButton icon={IconSettings} aria-label="通知连接与接收设置" aria-haspopup="dialog" aria-expanded={configOpen} aria-controls={configId} onClick={()=>setConfigOpen(true)}/></>}
        {navigationTrigger}
      </div>
    </header>
    {state?.error&&<MotionFeedback identity={state.error} role="alert" className="pw-remote-note is-error">{state.error}</MotionFeedback>}
    {error&&<MotionFeedback identity={error} role="alert" className="pw-remote-note is-error">{error}</MotionFeedback>}
    {message&&<MotionFeedback identity={message} role="status" className="pw-remote-note">{message}</MotionFeedback>}
    <NotificationTaskAttention enabled={state?.available===true} onOpenTask={onOpenTask} memory={memory}/>
    <NotificationTeamAttention enabled={state?.available===true&&bridge.getState().capabilities.commands.includes('teams.attention')} onOpen={onOpenTeamAttention} memory={memory}/>
    <section className="pw-remote-message-group" aria-label="远程消息"><header className="pw-remote-section-title"><h2>远程消息</h2><span>最近消息 · 最多 300 条，长消息会更少</span></header>
    {state?.selectionError&&<MotionFeedback identity={state.selectionError+String(state.openRevision)} role="alert" className="pw-remote-note is-error">{state.selectionError}</MotionFeedback>}
    {state?.notificationPermission&&(!state.notificationPermission.appEnabled||!state.notificationPermission.normalEnabled||!state.notificationPermission.attentionEnabled)&&<div className="pw-remote-note">{!state.notificationPermission.appEnabled?'系统通知已关闭；应用内消息仍保留。':'部分远程消息渠道已关闭；对应消息可能不会显示系统提醒。'}<Button variant="ghost" onClick={()=>void act('remoteInbox.notificationSettings')}>系统通知设置</Button></div>}
    {state?.configured&&<section className="pw-remote-status" aria-label="接收状态">
      <span className="pw-remote-dot" aria-hidden="true"/>
      <MotionFeedback initial={false} identity={`${!!state?.running}:${!!state?.configured}`}><strong>{state?.running?'主动检测已启动':state?.backgroundScheduled?'已登记后台补收':'已保存连接 · 后台补收未登记'}</strong>
        <small>{state?.lastSync?.status==='waiting_network'?'等待网络，尚未尝试本次同步':state?.lastSync?.status==='credentials_required'?'凭据已失效，请重新配置':state?.lastSync?.status==='connection_failed'?'最近一次同步失败，请检查网络后重试':state?.lastSync?.status==='more_pending'?'仍有历史消息待补收':state?.lastSync?.lastSuccessAt?'最近成功同步 '+when(new Date(state.lastSync.lastSuccessAt).toISOString()):'尚无成功同步记录'}。系统限制、离线或进程停止均可能延迟消息。</small></MotionFeedback>
      <MotionFeedback initial={false} identity={Number(state?.unread||0)} className="pw-remote-count">{Number(state?.unread||0)} <small>未读</small></MotionFeedback>
    </section>}
    <SettingsDialog isOpen={configOpen} title="连接与接收设置" closeLabel="返回通知" onClose={()=>{if(!working){if(disconnectConfirm)setDisconnectConfirm(false);else setConfigOpen(false)}}}>
    <section id={configId} className="pw-remote-config" aria-label="通知服务配置">
      <p className="pw-remote-config-intro">连接自己的 HTTPS 事件服务，接收 Issue、PR 回复及服务消息。</p>
      <div className="pw-remote-fields">
        <TextField label="服务根地址" value={endpoint} onChange={setEndpoint} placeholder="https://your-worker.workers.dev" isDisabled={working}/>
        <TextField label="只读接收 Token" value={token} onChange={setToken} type="password" autoComplete="off" placeholder="至少 32 位" isDisabled={working}/>
      </div>
      <div className="pw-remote-config-footer">
        <span>凭据在本机加密保存，不会显示在通知和记录中。</span>
        <div className="pw-remote-buttons">
          <Button disabled={working||!hasSnapshot||state?.available===false||!endpoint||token.length<32} onClick={()=>void act('remoteInbox.configure',{endpoint,token})}>保存并启用补收</Button>
          {state?.configured&&<Button variant="ghost" disabled={working} onClick={()=>setDisconnectConfirm(true)}>断开并清除</Button>}
        </div>
      </div>
      {disconnectConfirm&&<div className="pw-remote-note" role="alert">将清除本机连接凭据和全部远程通知，无法在本机撤销。原任务和服务端事件不受影响。<div className="pw-remote-buttons"><Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.disconnect')}>确认断开并清除</Button><Button variant="ghost" disabled={working} onClick={()=>setDisconnectConfirm(false)}>保留连接</Button></div></div>}
      {(error||state?.error)&&<MotionFeedback identity={error||state?.error||''} className="pw-field-error" role="alert">{error||state?.error}</MotionFeedback>}
      {message&&<MotionFeedback identity={message} className="pw-remote-note" role="status">{message}</MotionFeedback>}
    </section>
    {state?.configured&&!state?.backgroundScheduled&&<div className="pw-remote-note">后台补收尚未登记，系统不会按此任务自动拉取。<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.schedule')}>登记后台补收</Button></div>}
    {state?.configured&&<section className="pw-remote-receiver" aria-label="可选推送唤醒">
      <div><strong>推送唤醒</strong><p>接收器收到加密信号后，请求 Android 补收原通知。不会把任务正文发给推送商，也不会启动 Agent。</p>
        {!state.push?.supported?<p>此构建未包含可选 UnifiedPush 模块。后台补收和主动检测仍可使用；启用推送版需要操作者按项目部署说明配置 SDK 与服务端。</p>:<>
          {state.push.enabled?<>
            <p>{state.push.status==='caught_up'?'已完成一次唤醒确认与补收':state.push.status==='wake_received'?'已收到加密唤醒，等待系统补收':state.push.status==='credentials_required'?'设备登记凭据已失效，请停用后以新凭据启用':state.push.status==='service_unavailable'?'推送服务暂不可用，系统稍后重试':state.push.status==='registration_missing'?'服务端登记已失效，等待重新登记':state.push.status==='encryption_unavailable'?'接收器没有提供可用加密参数，请另选支持的接收器':state.push.status==='endpoint_rejected'?'接收地址不在服务端允许名单内':state.push.status==='distributor_unavailable'?'所选接收器已不可用':state.push.status==='unregistered'?'接收器已注销登记，请重新启用':state.push.status==='registration_failed'?'接收器登记失败，请检查后重新启用':state.push.status==='temporary_receiver'?'接收器提出临时切换，尚未把临时端点交给通知服务；可停用后重新选择接收器':state.push.status==='temporarily_unavailable'?'接收器服务暂不可用':state.push.registeredAt?'服务端已登记，等待加密唤醒确认':'正在登记接收器，尚未证实唤醒可达'}。</p>
            <p>{state.push.lastWakeAt?'最近唤醒 '+when(new Date(state.push.lastWakeAt).toISOString())+'。':''}{state.push.lastCatchUpAt?'最近确认补收 '+when(new Date(state.push.lastCatchUpAt).toISOString())+'。':''}登记、收到信号与收到完整通知是不同状态，均不代表用户已读。系统强行停止、禁后台或接收器离线仍可能延迟。</p>
            <Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.disablePush')}>停用并退订推送</Button>
          </>:<>
            <p>先自行安装并配置可信 UnifiedPush 接收器。启用会把推送端点及加密参数交给当前通知服务 {state.endpoint}，并让所选接收器维持推送连接。接收 Token 不会交给接收器。</p>
            <div className="pw-remote-buttons" role="group" aria-label="选择已安装的接收器">{(state.push.distributors||[]).map(item=><Button key={item.id} variant="ghost" aria-pressed={distributor===item.id} disabled={working} onClick={()=>setDistributor(item.id)}>{item.label}</Button>)}</div>
            {!state.push.distributors?.length&&<p>尚未找到可用接收器；安装后重新进入通知设置。应用不会自动安装、登录或注册第三方服务。</p>}
            <TextField label="专用设备登记 Token" type="password" autoComplete="off" value={pushToken} onChange={setPushToken} placeholder="与只读接收 Token 分开配置" isDisabled={working}/>
            <Button variant="secondary" disabled={working||!distributor||pushToken.length<32} onClick={()=>void act('remoteInbox.enablePush',{configId:state.configId,distributor,registrationToken:pushToken})}>同意共享端点并启用</Button>
          </>}
        </>}
      </div>
    </section>}
    {state?.configured&&<section className="pw-remote-receiver">
      <div><strong>主动检测</strong><p>约每 50 秒拉取一次，会显示常驻通知，可能增加耗电。已有推送时通常无需同时开启。不是系统推送，断网或系统停止进程后可能延迟。接收不会启动 Agent。</p></div>
      <div className="pw-remote-buttons"><Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.notificationPermission')}>请求通知权限</Button><Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.notificationSettings')}>系统通知设置</Button>
      {state?.live&&state?.running?<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.stopLive')}>停止接收</Button>
        :<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.startLive')}>开启接收</Button>}</div>
    </section>}
    </SettingsDialog>
    {(hasEvents||filter!=='all')&&<div className="pw-remote-list-toolbar">
      <div role="group" aria-label="筛选通知" className="pw-remote-filters">
        {([{key:'all',label:'全部'},{key:'unread',label:'未读'},{key:'issues',label:'开源 Issue'},{key:'archived',label:'已归档'}] as const).map(option=><Button key={option.key} variant="ghost" aria-pressed={filter===option.key} onClick={()=>setFilter(option.key)}>{option.label}</Button>)}
      </div>
      <Button variant="ghost" disabled={working||!entries.some(item=>!item.read)} onClick={()=>update(entries.filter(item=>!item.read),'read',true)}>当前已载入消息全部已读</Button>
    </div>}
    <section ref={list} className="pw-remote-list" aria-label="通知列表" tabIndex={-1}>
      {entries.length===0?<div className="pw-remote-empty"><IconNotification size={30} aria-hidden/><h2>{!hasSnapshot?'尚未取得通知状态':filter==='unread'?'没有未读通知':filter==='archived'?'没有已归档通知':filter==='issues'?'没有开源 Issue 通知':state?.configured?'还没有收到通知':'把新的消息收在这里'}</h2>
        <p>{!hasSnapshot?'连接与通知尚未读取完成，请核对当前状态后再继续。':state?.configured?'服务有新事件后会出现在这里。接收消息不会启动 Agent 或执行任务。':'连接自己的通知服务，接收 Issue、PR 回复和服务事件。'}</p>
        {filter!=='all'?<Button variant="secondary" onClick={()=>setFilter('all')}>查看全部通知</Button>:hasSnapshot&&!state?.configured&&<Button leadingIcon={IconLink} disabled={working||state?.available===false} onClick={()=>setConfigOpen(true)}>连接通知服务</Button>}
        {state?.available===false&&<p>当前状态不可用，请返回本机主人界面后重新进入。</p>}
      </div>
      :entries.map(item=><article key={item.seq} data-motion-key={String(item.seq)} tabIndex={-1} className="pw-remote-item" data-read={item.read?'true':'false'} data-selected={state?.selectedSeq===item.seq||undefined}>
        <div className="pw-remote-icon">{item.priority==='attention'?<IconAttention size={18}/>:<IconNotification size={18}/>}</div>
        <div className="pw-remote-item-main">
          <div className="pw-remote-meta"><span>{item.origin}</span><span>{when(item.created_at)}</span>
            {item.priority==='attention'&&<span className="pw-remote-urgent">优先关注</span>}
            {!item.read&&<span className="pw-remote-unread">未读</span>}
          </div>
          <h3>{item.title}</h3>
          {item.body&&<p>{item.body}</p>}
          <div className="pw-remote-item-actions">
            {safeUrl(item.url)&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.open',identity(item))}>查看来源 <IconArrowUpRight size={14}/></Button>}
            {item.read?<Button variant="ghost" disabled={working} onClick={()=>update([item],'read',false)}>标记未读</Button>:<Button variant="ghost" disabled={working} onClick={()=>markRead(item.seq)}><IconCheck size={14}/>标记已读</Button>}
            <Button variant="ghost" disabled={working} onClick={()=>update([item],'archived',!item.archived)}>{item.archived?'移回收件箱':'归档'}</Button>
          </div>
        </div>
      </article>)}
    </section>
    {entries.some(item=>item.kind==='github_issue')&&<p className="pw-remote-footnote">Issue 未分配不等于可直接认领；贡献前请先核对仓库规则、评论和关联 PR。</p>}
    </section>
  </main>;
}
