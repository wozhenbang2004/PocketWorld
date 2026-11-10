import {useEffect,useRef,useState} from 'react';
import type {ReactNode} from 'react';
import { IconArchive, IconArrowLeft, IconCopy, IconDownload, IconPause, IconPlay, IconRefresh } from './icons';
import {bridge} from './bridge';
import {Accordion,AccordionItem,Button,CheckboxField,IconButton,SearchField} from './forms-library';
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
        <IconButton icon={IconArrowLeft} aria-label="返回" onClick={onBack}/>
        <div><h1>开发诊断</h1><p>实时运行日志 · 脱敏归档</p></div>
      </div>
      {navigationTrigger}
    </header>
    <div className="pw-developer-stats">
      <i className="pw-developer-live-dot" aria-hidden data-following={!!windowId&&!!storage&&follow}/>
      <span>{!storage&&error?'日志状态尚未取得':!windowId?'诊断窗口待连接':!follow?'视图已暂停':!storage?'正在读取日志':'跟随最新日志'}</span>
      {storage&&<span>{storage.enabled?'深度归档中':'基础诊断常开'}</span>}
    </div>
    <section className="pw-developer-controls">
      <SearchField className="pw-developer-search" data-input-size="medium" value={query} onChange={setQuery}
        placeholder="搜索代码、阶段或异常" aria-label="搜索当前显示的日志" clearLabel="清除搜索"/>
      <CheckboxField className="pw-developer-filter" isSelected={onlyErrors} onChange={setOnlyErrors}>只看错误</CheckboxField>
    </section>
    {error&&<p className="pw-developer-error" role="alert">{error}</p>}
    {notice&&<p className="pw-developer-note" role="status">{notice}</p>}
    {Boolean(storage?.writeFailures||storage?.rejectedRows)&&
      <p className="pw-developer-note">写入失败 {storage?.writeFailures||0} · 过滤异常记录 {storage?.rejectedRows||0}</p>}
    <section className="pw-developer-console" aria-label="日志控制台">
      <div className="pw-developer-console-top">
        <div className="pw-developer-console-meta"><span>运行日志{storage?` · ${shown.length} 条`:''}</span>
          {hasOlder&&<Button variant="ghost" size="small" disabled={!windowId||busy} onClick={loadOlder}>加载更早</Button>}
        </div>
        <div className="pw-developer-actions pw-developer-console-actions">
          <Button variant="ghost" iconOnly leadingIcon={follow?IconPause:IconPlay} aria-label={follow?'暂停跟随日志':'继续跟随日志'} title={follow?'暂停跟随日志':'继续跟随日志'} onClick={()=>setFollow(value=>!value)}/>
          <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="读取最新日志" title="读取最新日志" onClick={()=>{nextCursor.current=null;setEvents([]);setOlder(null);setHasOlder(false);setFollow(true);}}/>
          <Button variant="ghost" iconOnly leadingIcon={IconCopy} aria-label="复制当前日志" title="复制当前日志" disabled={!shown.length} onClick={copy}/>
          <Button variant="ghost" iconOnly leadingIcon={IconDownload} aria-label="导出全部归档" title="导出全部归档" disabled={!windowId} onClick={exportAll}/>
        </div>
      </div>
      <pre ref={viewport} role="log" aria-live="off">{shown.length?shown.map(formatEvent).join('\n'):!storage?error?'日志暂未读到，请查看上方提示。':!windowId?'诊断窗口尚未连接。':!follow?'视图已暂停，继续跟随后读取日志。':'正在读取诊断日志…':query||onlyErrors?'当前条件下没有匹配的日志。':'暂无诊断事件，等待新的日志…'}</pre>
    </section>
    <Accordion className="pw-developer-storage" defaultExpandedKeys={[]}>
    <AccordionItem id="developer-archive" title="归档与保留" icon={IconArchive} description={storage?`${storage.enabled?'深度归档已开启':'深度归档已关闭'} · ${bytesLabel(storage.bytes)} / ${storage.capacityMiB>=1024?storage.capacityMiB/1024+' GB':storage.capacityMiB+' MB'}`:error?'归档状态尚未取得':!windowId?'连接诊断窗口后读取':!follow?'继续跟随后读取归档状态':'正在读取本机归档状态…'}>
    <footer className="pw-developer-footer">
      <CheckboxField className="pw-developer-capture" isSelected={storage?.enabled===true} isDisabled={!windowId||captureBusy}
        onChange={selected=>void toggleCapture(selected)}>深度日志归档</CheckboxField>
      <small>默认关闭深度写盘，基础诊断始终运行。开启后持续保存大量脱敏运行事件；关闭只停止新增归档，不删除现有记录。</small>
      {storage&&<small>本机已保留 {storage.segments} 个归档片段，占用 {bytesLabel(storage.bytes)}。</small>}
      <span>保留容量</span>
      {[256,512,1024,2048].map(miB=><Button key={miB} variant="secondary" aria-pressed={storage?.capacityMiB===miB}
        className={storage?.capacityMiB===miB?'active':''} disabled={!windowId||busy}
        onClick={()=>void setCapacity(miB)}>{miB>=1024?miB/1024+' GB':miB+' MB'}</Button>)}
      <small>开启归档时超过容量只轮换最早的完整片段；屏幕最多显示 600 条。导出全部仅包含已归档历史，不包含系统 Logcat 的其他应用日志。</small>
    </footer>
    </AccordionItem>
    </Accordion>
  </main>;
}
