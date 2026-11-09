import {useEffect,useMemo,useState} from 'react';
import {RiAlarmWarningLine,RiArrowRightUpLine,RiCheckLine,RiNotification3Line,RiRefreshLine,RiSettings4Line} from '@remixicon/react';
import {Button,TextField} from './forms-library';
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
export default function RemoteInboxWorkspace({state}: {state?:State}) {
  const [endpoint,setEndpoint]=useState('');
  const [token,setToken]=useState('');
  const [configOpen,setConfigOpen]=useState(false);
  const [working,setWorking]=useState(false);
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  const [filter,setFilter]=useState<'all'|'unread'|'issues'>('all');
  useEffect(()=>{if(state?.endpoint)setEndpoint(previous=>previous||state.endpoint||'')},[state?.endpoint]);
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
      <div><span className="pw-remote-overline">POCKETWORLD / INBOX</span><h1>通知</h1>
        <p>远程 Issue、PR 回复与服务事件。接收消息不会启动 Agent 或执行任务。</p></div>
      <div className="pw-remote-inbox-actions">
        <Button variant="ghost" leadingIcon={RiRefreshLine} disabled={working||!state?.configured} onClick={()=>void act('remoteInbox.sync')}>同步</Button>
        <Button variant="ghost" leadingIcon={RiSettings4Line} onClick={()=>setConfigOpen(value=>!value)}>配置</Button>
      </div>
    </header>
    {state?.error&&<div role="alert" className="pw-remote-note is-error">{state.error}</div>}
    {error&&<div role="alert" className="pw-remote-note is-error">{error}</div>}
    {message&&<div role="status" className="pw-remote-note">{message}</div>}
    <section className="pw-remote-status" aria-label="接收状态">
      <span className={state?.running?'pw-remote-dot is-connected':'pw-remote-dot'} aria-hidden="true"/>
      <div><strong>{state?.running?'实时接收运行中':state?.configured?'已连接服务 · 定期补收':'未连接远程服务'}</strong>
        <small>{state?.running?'后台检测约每 50 秒一次，不保证系统杀进程后实时送达':state?.configured?'系统可在后台执行周期补收；立即获取请点击同步':'连接你自己的 HTTPS 事件服务后才能接收远程消息'}</small></div>
      <span className="pw-remote-count">{Number(state?.unread||0)} <small>未读</small></span>
    </section>
    {state?.configured&&<section className="pw-remote-receiver">
      <div><strong>主动接收</strong><p>可选的前台接收服务，适合需要快速发现新 Issue 时使用。会显示常驻系统通知。</p></div>
      <Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.notificationPermission')}>通知权限</Button>
      {state?.live&&state?.running?<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.stopLive')}>停止接收</Button>
        :<Button variant="secondary" disabled={working} onClick={()=>void act('remoteInbox.startLive')}>开启接收</Button>}
    </section>}
    {(configOpen||!state?.configured)&&<section className="pw-remote-config" aria-label="通知服务配置">
      <div className="pw-remote-section-title"><h2>连接 HTTPS 通知服务</h2><span>本机凭据由 Android Keystore 加密</span></div>
      <div className="pw-remote-fields">
        <TextField label="服务根地址" value={endpoint} onChange={setEndpoint} placeholder="https://your-worker.workers.dev" isDisabled={working}/>
        <TextField label="只读接收 Token" value={token} onChange={setToken} type="password" autoComplete="off" placeholder="至少 32 位" isDisabled={working}/>
      </div>
      <div className="pw-remote-config-footer">
        <span>仅接受 HTTPS。Token 不会显示在通知、记录或 React 快照中。</span>
        <div className="pw-remote-buttons">
          <Button variant="secondary" disabled={working||!endpoint||token.length<32} onClick={()=>void act('remoteInbox.configure',{endpoint,token})}>保存并启用补收</Button>
          {state?.configured&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.disconnect')}>断开并清除</Button>}
        </div>
      </div>
    </section>}
    <div className="pw-remote-list-toolbar">
      <div role="group" aria-label="筛选通知" className="pw-remote-filters">
        {([{key:'all',label:'全部'},{key:'unread',label:'未读'},{key:'issues',label:'开源 Issue'}] as const).map(option=><Button key={option.key} variant="ghost" aria-pressed={filter===option.key} onClick={()=>setFilter(option.key)}>{option.label}</Button>)}
      </div>
      <small>{Array.isArray(state?.events)?state.events.length:0} 条已保留</small>
    </div>
    <section className="pw-remote-list" aria-label="通知列表">
      {entries.length===0?<div className="pw-remote-empty"><RiNotification3Line size={28}/><h2>没有{filter==='unread'?'未读':filter==='issues'?'开源 Issue ':''}通知</h2>
        <p>{state?.configured?'服务有新事件后会自动出现在这里。':'配置服务后可接收来自 MergeAtlas 的 Issue 和 PR 消息。'}</p></div>
      :entries.map(item=><article key={item.seq} className="pw-remote-item" data-read={item.read?'true':'false'}>
        <div className="pw-remote-icon">{item.priority==='attention'?<RiAlarmWarningLine size={18}/>:<RiNotification3Line size={18}/>}</div>
        <div className="pw-remote-item-main">
          <div className="pw-remote-meta"><span>{item.origin}</span><span>{when(item.created_at)}</span>
            {item.priority==='attention'&&<span className="pw-remote-urgent">优先关注</span>}
            {!item.read&&<span className="pw-remote-unread">未读</span>}
          </div>
          <h3>{item.title}</h3>
          {item.body&&<p>{item.body}</p>}
          <div className="pw-remote-item-actions">
            {safeUrl(item.url)&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.open',{seq:item.seq})}>查看来源 <RiArrowRightUpLine size={14}/></Button>}
            {!item.read&&<Button variant="ghost" disabled={working} onClick={()=>void act('remoteInbox.mark',{seq:item.seq})}><RiCheckLine size={14}/>标记已读</Button>}
          </div>
        </div>
      </article>)}
    </section>
    <p className="pw-remote-footnote">“暂无 assignee”不等于可直接认领。请先核对仓库规则、评论和关联 PR，再决定是否贡献。</p>
  </main>;
}
