import {useEffect,useRef,useState} from 'react';
import type {ReactNode} from 'react';
import {RiArrowLeftLine,RiDownloadLine,RiFileCopyLine,RiPauseLine,RiPlayLine,RiRefreshLine} from '@remixicon/react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import './developer-diagnostics.css';

type Event = {eventId:string;time:number;stage:string;outcome:string;code:string;durationMs?:number;taskId?:string;exceptionTypes?:string[];frames?:string[]};
type Storage = {bytes:number;capacityMiB:number;segments:number;writeFailures:number;rejectedRows:number;enabled:boolean};
type Page = {entries:Event[];older?:string|null;next?:string|null;hasOlder:boolean;expired:boolean;storage:Storage};
const DISPLAY_CAP = 600;
const bytesLabel = (count:number) => count>=1073741824?(count/1073741824).toFixed(2)+' GB':(count/1048576).toFixed(1)+' MB';
const formatEvent = (event:Event) => [
  new Date(event.time).toLocaleString('zh-CN',{hour12:false}),
  event.outcome==='failed'?'ERROR':event.outcome==='rejected'?'WARN':event.outcome==='started'?'START':'INFO',
  event.stage,event.code,event.durationMs?event.durationMs+'ms':'',
  ...(event.exceptionTypes||[]),...(event.frames||[])
].filter(Boolean).join('  ');

export default function DeveloperDiagnosticsWorkspace({
  onBack,navigationTrigger
}: {onBack:()=>void;navigationTrigger?:ReactNode}) {
  const [windowId,setWindowId] = useState('');
  const [events,setEvents] = useState<Event[]>([]);
  const [follow,setFollow] = useState(true);
  const [older,setOlder] = useState<string|null>(null);
  const [hasOlder,setHasOlder] = useState(false);
  const [storage,setStorage] = useState<Storage|null>(null);
  const [query,setQuery] = useState('');
  const [onlyErrors,setOnlyErrors] = useState(false);
  const [busy,setBusy] = useState(false);
  const [captureBusy,setCaptureBusy] = useState(false);
  const [error,setError] = useState('');
  const [notice,setNotice] = useState('');
  const viewport=useRef<HTMLPreElement>(null);
  const mounted=useRef(false);
  const currentWindow=useRef('');
  const nextCursor=useRef<string|null>(null);
  const pending=useRef(false);
  const active=useRef(true);
  const revision=useRef(0);
  const documentEpoch=useRef(bridge.getDocumentEpoch());

  const open=async(reset:boolean)=>{
    try {
      const session=await bridge.command<{windowId:string}>('developerLogs.open');
      if (!mounted.current||documentEpoch.current!==bridge.getDocumentEpoch()) return;
      currentWindow.current=session.windowId;setWindowId(session.windowId);
      if(reset){nextCursor.current=null;setEvents([]);setOlder(null);setHasOlder(false);}
      setError('');
    } catch {
      if(mounted.current)setError('开发诊断尚未接入，请返回后重新打开');
    }
  };

  useEffect(()=>{
    mounted.current=true;documentEpoch.current=bridge.getDocumentEpoch();
    let wasHidden=false;
    let lastEpoch=documentEpoch.current;
    const visibility=()=>{
      active.current=document.visibilityState==='visible';
      if(!active.current)wasHidden=true;
      else if(wasHidden){
        wasHidden=false;
        if(documentEpoch.current!==bridge.getDocumentEpoch()){documentEpoch.current=bridge.getDocumentEpoch();revision.current++;nextCursor.current=null;void open(true);}
      }
    };
    visibility();
    document.addEventListener('visibilitychange',visibility);
    const unsubscribe=bridge.subscribe(()=>{
      const epoch=bridge.getDocumentEpoch();
      if(epoch!==lastEpoch && bridge.getState().capabilities.commands.includes('developerLogs.open')){
        lastEpoch=epoch; documentEpoch.current=epoch;revision.current++;
        currentWindow.current='';setWindowId('');nextCursor.current=null;setEvents([]);
        void open(true);
      }
    });
    void open(false);
    return ()=>{
      mounted.current=false;revision.current++;
      document.removeEventListener('visibilitychange',visibility);
      unsubscribe();
      const id=currentWindow.current;
      if(id)void bridge.command('developerLogs.close',{windowId:id}).catch(()=>{});
    };
  },[]);

  useEffect(()=>{
    if(!windowId)return;
    let stopped=false;
    const ticket=++revision.current;
    let timer:ReturnType<typeof setTimeout>;
    const loop=async()=>{
      if(stopped)return;
      let nextDelay=1000;
      if(active.current&&follow&&!pending.current) {
        pending.current=true;
        try{
          const mode=nextCursor.current?'after':'tail';
          const result=await bridge.command<Page>('developerLogs.page',{
            windowId,mode,cursor:nextCursor.current||'',limit:150
          });
          if(!stopped&&mounted.current&&ticket===revision.current&&documentEpoch.current===bridge.getDocumentEpoch()){
            const rows=Array.isArray(result.entries)?result.entries:[];
            setStorage(result.storage);
            if(result.expired) {
              setNotice('较早日志已超过磁盘保留额度，当前定位已重置');
              nextCursor.current=null;
              setEvents(rows.slice(-DISPLAY_CAP));
              setOlder(result.older||null);
              setHasOlder(result.hasOlder);
            } else if(mode==='tail'){
              setEvents(rows.slice(-DISPLAY_CAP));
              setOlder(result.older||null);
              setHasOlder(result.hasOlder);
            } else if(rows.length) {
              setEvents(previous=>{
                const existing=new Set(previous.map(row=>row.eventId));
                const fresh=rows.filter(row=>!existing.has(row.eventId));
                return previous.concat(fresh).slice(-DISPLAY_CAP);
              });
            }
            if(typeof result.next==='string'&&result.next)nextCursor.current=result.next;
            nextDelay=rows.length===150?120:1000;
            setError('');
          }
        }catch{
          if(!stopped&&mounted.current&&ticket===revision.current)setError('增量读取失败，重新进入页面后可继续查看磁盘记录');
        }finally{pending.current=false;}
      }
      if(!stopped)timer=setTimeout(loop,nextDelay);
    };
    void loop();
    return()=>{stopped=true;clearTimeout(timer);};
  },[windowId,follow]);

  useEffect(()=>{if(follow&&viewport.current)viewport.current.scrollTop=viewport.current.scrollHeight;},[events,follow]);

  const loadOlder=async()=>{
    if(!windowId||!older||busy||pending.current)return;
    setFollow(false);setBusy(true);
    const ticket=revision.current;
    try{
      const result=await bridge.command<Page>('developerLogs.page',{windowId,mode:'before',cursor:older,limit:150});
      if(!mounted.current||revision.current!==ticket)return;
      setStorage(result.storage);
      if(result.expired){nextCursor.current=null;setEvents(result.entries);setNotice('更早的片段已被清理，已回到最新记录');}
      else setEvents(previous=>{
        const existing=new Set(previous.map(row=>row.eventId));
        return result.entries.filter(row=>!existing.has(row.eventId)).concat(previous).slice(0,DISPLAY_CAP);
      });
      setOlder(result.older||null);setHasOlder(result.hasOlder);
      setError('');
    }catch{if(mounted.current)setError('较早日志读取失败');}
    finally{if(mounted.current)setBusy(false);}
  };

  const shown=events.filter(event=>(!onlyErrors||event.outcome==='failed'||event.outcome==='rejected')
    &&(!query||formatEvent(event).toLocaleLowerCase().includes(query.toLocaleLowerCase())));
  const copy=async()=>{
    try{
      await navigator.clipboard.writeText(shown.map(row=>JSON.stringify(row)).join('\n'));
      setNotice('已复制 '+shown.length+' 条当前显示的脱敏日志');
    }catch{setError('复制失败，请使用系统导出保存日志');}
  };
  const exportAll=async()=>{
    try{
      await bridge.command('developerLogs.export',{windowId});
      setNotice('系统保存窗口已打开，选择位置后将流式写入完整归档');
    }catch{setError('系统保存窗口不可用');}
  };
  const toggleCapture=async(enabled:boolean)=>{
    if(!windowId||captureBusy)return;
    setCaptureBusy(true);
    try {
      const result=await bridge.command<Storage>('developerLogs.capture',{windowId,enabled});
      if(!mounted.current)return;
      setStorage(result);nextCursor.current=null;setEvents([]);setOlder(null);setHasOlder(false);
      setNotice(enabled?'深度归档已启用，后续运行日志持续写入本机':'深度归档已停止写入；基础错误日志和已存历史继续保留');
      setError('');
    }catch{if(mounted.current)setError('日志采集模式未能保存');}
    finally{if(mounted.current)setCaptureBusy(false);}
  };
  const setCapacity=async(miB:number)=>{
    setBusy(true);
    try{
      const value=await bridge.command<Storage>('developerLogs.capacity',{windowId,miB});
      if(!mounted.current)return;
      setStorage(value);
      setNotice('磁盘保留上限改为 '+(miB>=1024?miB/1024+' GB':miB+' MB'));
    }catch{if(mounted.current)setError('容量保存失败，请检查应用存储');}
    finally{if(mounted.current)setBusy(false);}
  };

  return <main className="pw-developer-logs" aria-label="开发诊断">
    <header className="pw-developer-header">
      <div className="pw-developer-heading">
        {navigationTrigger}
        <IconButton icon={RiArrowLeftLine} aria-label="返回" onClick={onBack}/>
        <div><h1>开发诊断</h1><p>实时运行日志 · 脱敏归档</p></div>
      </div>
      <div className="pw-developer-actions">
        <Button variant="ghost" size="small" onClick={()=>setFollow(value=>!value)}>
          {follow?<><RiPauseLine size={15}/> 暂停跟随</>:<><RiPlayLine size={15}/> 继续跟随</>}
        </Button>
        <Button variant="ghost" size="small" onClick={()=>{nextCursor.current=null;setEvents([]);setOlder(null);setHasOlder(false);setFollow(true);}}>
          <RiRefreshLine size={15}/> 最新
        </Button>
      </div>
    </header>
    <div className="pw-developer-stats">
      <i className="pw-developer-live-dot"/>{follow?'实时更新':'视图已暂停'} · {storage?.enabled?'深度归档中':'基础诊断常开'}
      <span>{storage?bytesLabel(storage.bytes):'…'} / {storage?.capacityMiB||512} MB</span>
      <span>{storage?.segments||0} 个片段</span>
      <span>屏幕缓存 {events.length} 条</span>
    </div>
    <section className="pw-developer-controls">
      <input type="search" value={query} onChange={event=>setQuery(event.target.value)}
        placeholder="搜索代码、阶段或异常" aria-label="搜索当前显示的日志"/>
      <label><input type="checkbox" checked={onlyErrors} onChange={event=>setOnlyErrors(event.target.checked)}/> 只看错误</label>
      <div className="pw-developer-actions">
        <Button variant="secondary" size="small" disabled={!shown.length} onClick={copy}><RiFileCopyLine size={15}/> 一键复制</Button>
        <Button variant="secondary" size="small" disabled={!windowId} onClick={exportAll}><RiDownloadLine size={15}/> 导出全部</Button>
      </div>
    </section>
    {error&&<p className="pw-developer-error" role="alert">{error}</p>}
    {notice&&<p className="pw-developer-note" role="status">{notice}</p>}
    <section className="pw-developer-console" aria-label="日志控制台">
      <div className="pw-developer-console-top"><span>RUNTIME / JSONL</span>
        {hasOlder&&<Button variant="ghost" size="small" disabled={!windowId||busy} onClick={loadOlder}>加载更早</Button>}
      </div>
      <pre ref={viewport} role="log" aria-live="off">{shown.length?shown.map(formatEvent).join('\n'):'当前没有匹配的日志，等待新的诊断事件…'}</pre>
    </section>
    <footer className="pw-developer-footer">
      <label className="pw-developer-capture"><input type="checkbox" checked={storage?.enabled===true} disabled={!windowId||captureBusy}
        onChange={event=>void toggleCapture(event.target.checked)}/> 深度日志归档</label>
      <small>默认关闭深度写盘，基础诊断始终运行。开启后持续保存大量脱敏运行事件；关闭只停止新增归档，不删除现有记录。</small>
      <span>保留容量</span>
      {[256,512,1024,2048].map(miB=><button key={miB} type="button"
        className={storage?.capacityMiB===miB?'active':''} disabled={!windowId||busy}
        onClick={()=>void setCapacity(miB)}>{miB>=1024?miB/1024+' GB':miB+' MB'}</button>)}
      <small>开启归档时超过容量只轮换最早的完整片段；屏幕最多显示 600 条。导出全部仅包含已归档历史，不包含系统 Logcat 的其他应用日志。</small>
      {Boolean(storage?.writeFailures||storage?.rejectedRows)&&
        <small>写入失败 {storage?.writeFailures||0} · 过滤异常记录 {storage?.rejectedRows||0}</small>}
    </footer>
  </main>;
}
