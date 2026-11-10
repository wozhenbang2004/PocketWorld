import {useEffect,useMemo,useState,type ReactNode} from 'react';
import { IconArrowLeft, IconArrowUpRight, IconAttention, IconCheck, IconLink, IconNotification, IconRefresh, IconSettings } from './icons';
import {Button,IconButton,SettingsDialog,TextField} from './forms-library';
import {bridge} from './bridge';
import './remote-inbox.css';

type Event = {
  seq:number; source_key:string; kind:string; title:string; body:string;
  origin:string; url:string; priority:'attention'|'normal'; created_at:string; read:boolean;
};
type State = {
  available?:boolean; configured?:boolean; endpoint?:string; live?:boolean; running?:boolean;
  cursor?:number; unread?:number; error?:string; events?:Event[];
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
export default function RemoteInboxWorkspace({state,navigationTrigger,onBack,registerBack}: {state?:State;navigationTrigger?:ReactNode;onBack:()=>void;registerBack?:(handler:(()=>boolean)|null)=>void}) {
  const [endpoint,setEndpoint]=useState('');
  const [token,setToken]=useState('');
  const [configOpen,setConfigOpen]=useState(false);
  const [working,setWorking]=useState(false);
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  const [filter,setFilter]=useState<'all'|'unread'|'issues'>('all');
  useEffect(()=>{if(state?.endpoint)setEndpoint(previous=>previous||state.endpoint||'')},[state?.endpoint]);
  useEffect(()=>{registerBack?.(configOpen?()=>{if(!working)setConfigOpen(false);return true}:null);return()=>registerBack?.(null)},[configOpen,working,registerBack]);
  const hasSnapshot=typeof state?.configured==='boolean'&&Array.isArray(state.events);
  const hasEvents=Array.isArray(state?.events)&&state.events.some(item=>item&&Number.isSafeInteger(item.seq));
  const entries=useMemo(()=>{
    const all=Array.isArray(state?.events)?state.events:[];
    return [...all].filter(item=>item&&Number.isSafeInteger(item.seq))
      .filter(item=>filter==='all'||filter==='unread'&&!item.read||filter==='issues'&&item.kind==='github_issue')
      .sort((a,b)=>b.seq-a.seq);
  },[state?.events,filter]);
  async function act(name:string,args:Record<string,unknown>={}) {
    if(working)return;
    setWorking(true);setError('');setMessage('');
    try {
      const response=await bridge.command(name,args) as Result;
      if(name==='remoteInbox.configure'){setToken('');setConfigOpen(false);setMessage('已保存服务配置；后台补收按系统调度，实时接收需要另行开启。');}
      else if(name==='remoteInbox.sync'){
        if(response.status==='failed')setError(response.message||'同步失败');
        else setMessage(response.status==='already_running'?'已有同步正在进行':response.status==='not_configured'?'请先配置通知服务':'同步完成 · 新增 '+String(response.newCount||0)+' 条');
      } else if(name==='remoteInbox.startLive')setMessage('已请求启动实时接收；请核对上方状态和系统常驻通知。');
      else if(name==='remoteInbox.notificationPermission')setMessage(response.status==='permission_requested'?'请在系统弹窗中允许通知，随后再开启接收。':'系统通知权限已允许。');
      else if(name==='remoteInbox.disconnect'){setToken('');setEndpoint('');setMessage('已断开，已清除本机凭据和历史远程通知。');}
    } catch(e) {setError(e instanceof Error?e.message:String(e));}
    finally{setWorking(false)}
  }
  return <main className="pw-remote-inbox pw-page" aria-label="远程通知中心">
    <header className="pw-remote-inbox-head">
      <div className="pw-remote-title"><IconButton icon={IconArrowLeft} aria-label="返回对话" onClick={onBack}/><h1>通知</h1></div>
      <div className="pw-remote-inbox-actions">
        {state?.configured&&<><IconButton icon={IconRefresh} aria-label="同步通知" disabled={working} onClick={()=>void act('remoteInbox.sync')}/><IconButton icon={IconSettings} aria-label="通知连接与接收设置" onClick={()=>setConfigOpen(true)}/></>}
        {navigationTrigger}
      </div>
    </header>
    {state?.error&&<div role="alert" className="pw-remote-note is-error">{state.error}</div>}
    {error&&<div role="alert" className="pw-remote-note is-error">{error}</div>}
    {message&&<div role="status" className="pw-remote-note">{message}</div>}
    {state?.configured&&<section className="pw-remote-status" aria-label="接收状态">
      <span className={state?.running?'pw-remote-dot is-connected':'pw-remote-dot'} aria-hidden="true"/>
      <div><strong>{state?.running?'实时接收运行中':state?.configured?'已连接服务 · 定期补收':'未连接远程服务'}</strong>
        <small>{state?.running?'后台检测约每 50 秒一次，不保证系统杀进程后实时送达':state?.configured?'系统可在后台执行周期补收；立即获取请点击同步':'连接你自己的 HTTPS 事件服务后才能接收远程消息'}</small></div>
      <span className="pw-remote-count">{Number(state?.unread||0)} <small>未读</small></span>
    </section>}
    <SettingsDialog isOpen={configOpen} title="连接与接收设置" closeLabel="返回通知" onClose={()=>{if(!working)setConfigOpen(false)}}>
    <section className="pw-remote-config" aria-label="通知服务配置">
      <p className="pw-remote-config-intro">连接自己的 HTTPS 事件服务，接收 Issue、PR 回复及服务消息。</p>
      <div className="pw-remote-fields">
        <TextField label="服务根地址" value={endpoint} onChange={setEndpoint} placeholder="https://your-worker.workers.dev" isDisabled={working}/>
        <TextField label="只读接收 Token" value={token} onChange={setToken} type="password" autoComplete="off" placeholder="至少 32 位" isDisabled={working}/>
      </div>
      <div className="pw-remote-config-footer">
        <span>凭据在本机加密保存，不会显示在通知和记录中。</span>
        <div className="pw-remote-buttons">
          <Button disabled={working||!hasSnapshot||state?.available===false||!endpoint||token.length<32} onClick={()=>void act('remoteInbox.configure',{endpoint,token})}>保存并启用补收</Button>
          {state?.configured&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.disconnect')}>断开并清除</Button>}
        </div>
      </div>
      {(error||state?.error)&&<p className="pw-field-error" role="alert">{error||state?.error}</p>}
      {message&&<p className="pw-remote-note" role="status">{message}</p>}
    </section>
    {state?.configured&&<section className="pw-remote-receiver">
      <div><strong>主动接收</strong><p>需要更快发现事件时可以开启，会显示常驻系统通知。接收不会启动 Agent 或执行任务。</p></div>
      <div className="pw-remote-buttons"><Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.notificationPermission')}>通知权限</Button>
      {state?.live&&state?.running?<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.stopLive')}>停止接收</Button>
        :<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.startLive')}>开启接收</Button>}</div>
    </section>}
    </SettingsDialog>
    {(hasEvents||filter!=='all')&&<div className="pw-remote-list-toolbar">
      <div role="group" aria-label="筛选通知" className="pw-remote-filters">
        {([{key:'all',label:'全部'},{key:'unread',label:'未读'},{key:'issues',label:'开源 Issue'}] as const).map(option=><Button key={option.key} variant="ghost" aria-pressed={filter===option.key} onClick={()=>setFilter(option.key)}>{option.label}</Button>)}
      </div>
      <small>{Array.isArray(state?.events)?state.events.length:0} 条已保留</small>
    </div>}
    <section className="pw-remote-list" aria-label="通知列表">
      {entries.length===0?<div className="pw-remote-empty"><IconNotification size={30} aria-hidden/><h2>{!hasSnapshot?'尚未取得通知状态':filter==='unread'?'没有未读通知':filter==='issues'?'没有开源 Issue 通知':state?.configured?'还没有收到通知':'把新的消息收在这里'}</h2>
        <p>{!hasSnapshot?'连接与通知尚未读取完成，请核对当前状态后再继续。':state?.configured?'服务有新事件后会出现在这里。接收消息不会启动 Agent 或执行任务。':'连接自己的通知服务，接收 Issue、PR 回复和服务事件。'}</p>
        {filter!=='all'?<Button variant="secondary" onClick={()=>setFilter('all')}>查看全部通知</Button>:hasSnapshot&&!state?.configured&&<Button leadingIcon={IconLink} disabled={working||state?.available===false} onClick={()=>setConfigOpen(true)}>连接通知服务</Button>}
        {state?.available===false&&<p>当前状态不可用，请返回本机主人界面后重新进入。</p>}
      </div>
      :entries.map(item=><article key={item.seq} className="pw-remote-item" data-read={item.read?'true':'false'}>
        <div className="pw-remote-icon">{item.priority==='attention'?<IconAttention size={18}/>:<IconNotification size={18}/>}</div>
        <div className="pw-remote-item-main">
          <div className="pw-remote-meta"><span>{item.origin}</span><span>{when(item.created_at)}</span>
            {item.priority==='attention'&&<span className="pw-remote-urgent">优先关注</span>}
            {!item.read&&<span className="pw-remote-unread">未读</span>}
          </div>
          <h3>{item.title}</h3>
          {item.body&&<p>{item.body}</p>}
          <div className="pw-remote-item-actions">
            {safeUrl(item.url)&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.open',{seq:item.seq})}>查看来源 <IconArrowUpRight size={14}/></Button>}
            {!item.read&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.mark',{seq:item.seq})}><IconCheck size={14}/>标记已读</Button>}
          </div>
        </div>
      </article>)}
    </section>
    {entries.some(item=>item.kind==='github_issue')&&<p className="pw-remote-footnote">Issue 未分配不等于可直接认领；贡献前请先核对仓库规则、评论和关联 PR。</p>}
  </main>;
}
