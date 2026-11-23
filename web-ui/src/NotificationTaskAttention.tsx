import './notification-attention.css';
import {useCallback,useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {IconRefresh,IconCheckCircle,IconError,IconClock,IconChevronRight} from './icons';
import type {NotificationViewMemory} from './RemoteInboxWorkspace';

type Task = {id:string;actorId?:string;spaceId?:string;goal?:string;status?:string;summary?:string;updatedAt?:number;historyHasUnconfirmedOperations?:boolean};
type Page = {tasks:Task[];nextCursor:string;hasMore:boolean};
type Mode = 'attention'|'taskUpdates';
type Surface = {document:number;resume:number;task:string|null};
type TaskVisit = {id:string;surface:Surface;cursor:string;prior:string[];trigger:HTMLElement;scroller:HTMLElement;
  top:number;inset:number;opened:boolean;yielded:boolean;expires:number};
const originalSurface=():Surface|null=>{
  const snapshot=bridge.getState();
  const view=snapshot.view as {section?:unknown;taskId?:unknown;resumeRevision?:unknown}|undefined;
  const doc=bridge.getDocumentEpoch(),resume=view?.resumeRevision,task=view?.taskId;
  if(document.hidden||doc<1||!snapshot.host?.ready||view?.section!==null||
    !Number.isSafeInteger(resume)||Number(resume)<0||task!==null&&typeof task!=='string')return null;
  return {document:doc,resume:Number(resume),task};
};
const sameSurface=(a:Surface,b:Surface|null)=>b!==null&&a.document===b.document&&a.resume===b.resume;
const inaccessible='[hidden],[inert],[aria-hidden="true"]';
function inboxInteractive(scroll:HTMLElement){
  if(!scroll.isConnected||!scroll.getClientRects().length||scroll.closest(inaccessible))return false;
  const style=getComputedStyle(scroll);
  return style.visibility==='visible'&&style.pointerEvents!=='none';
}
function notificationPage(value:unknown,mode:Mode):value is Page{
  const p=value as (Page&{executionAuthorized?:boolean;auditBodyIncluded?:boolean;coverage?:string})|null;
  return !!p&&p.executionAuthorized===false&&p.auditBodyIncluded===false&&
    p.coverage===(mode==='attention'?'bounded_original_private_task_attention_updates_require_refresh':'bounded_original_private_task_results_updates_require_refresh')&&
    Array.isArray(p.tasks)&&p.tasks.length<=40&&p.tasks.every(task=>task?.actorId==='owner'&&task.spaceId==='private'&&typeof task.id==='string'&&!!task.id)&&
    new Set(p.tasks.map(task=>task.id)).size===p.tasks.length&&
    typeof p.nextCursor==='string'&&typeof p.hasMore==='boolean'&&p.hasMore===!!p.nextCursor;
}
const labels:Record<string,string>={needs_confirmation:'结果等待你确认',awaiting_user:'等待你补充',waiting_user:'等待你补充',waiting_approval:'等待你授权',awaiting_approval:'等待你授权',waiting_permission:'等待你授权',awaiting_permission:'等待你授权',paused:'任务已暂停',interrupted:'执行已中断',failed:'执行失败',reconciling:'操作结果待核对',reconciliation_required:'操作结果待核对',unknown:'执行结果未知'};

/** Paged original task headers. Reading this queue never answers, approves or resumes work. */
export function NotificationTaskAttention({enabled,onOpenTask,memory,mode}:{enabled:boolean;mode:Mode;onOpenTask:(id:string,review?:boolean)=>void;memory:NotificationViewMemory}) {
  const pages=memory.taskPages??(memory.taskPages={});
  const stored=pages[mode]??(pages[mode]={});
  const [page,setPage]=useState<Page|null>(null),[cursor,setCursor]=useState(stored.cursor||''),[prior,setPrior]=useState<string[]>(stored.prior||[]);
  const [busy,setBusy]=useState(false),[fresh,setFresh]=useState(false),[error,setError]=useState(''),[returnNote,setReturnNote]=useState('');
  const request=useRef(0),entering=useRef(false),alive=useRef(true),section=useRef<HTMLElement>(null);
  const acknowledged=useRef<Surface|null>(null),visit=useRef<TaskVisit|null>(null),initialRead=useRef(false);
  const position=useRef<TaskVisit|null>(null);
  const scroller=()=>section.current?.closest<HTMLElement>('.pw-remote-inbox');
  const actual=originalSurface();
  const shown=enabled&&acknowledged.current&&sameSurface(acknowledged.current,actual)?page:null;
  const read=useCallback(async(next:string,history:string[],entry?:TaskVisit)=>{
    const origin=originalSurface();
    if(!enabled||entering.current)return;
    if(!origin||origin.task!==null||entry&&!sameSurface(entry.surface,origin)){
      if(!entry)setError('请在原通知页面重新读取任务记录。');
      return;
    }
    initialRead.current=false;
    if(!entry){visit.current=null;position.current=null;}
    entering.current=true;const ticket=++request.current;
    setBusy(true);setFresh(false);setError('');setReturnNote('');
    const current=()=>{
      const latest=originalSurface();
      return alive.current&&ticket===request.current&&latest?.task===null&&sameSurface(origin,latest);
    };
    try {
      const result=await bridge.command('history.read',{section:mode,cursor:next});
      if(!current())return;
      if(!notificationPage(result,mode))throw new Error('原任务记录的页面未核对，请重新读取。');
      acknowledged.current=origin;setFresh(true);setPage(result);setCursor(next);setPrior(history);
      stored.cursor=next;stored.prior=history;
      if(entry&&!result.tasks.some(task=>task.id===entry.id))setReturnNote('原工作不在刚才这页，没有打开其他工作。');
    } catch(failure){
      if(current()){
        position.current=null;
        setError(failure instanceof Error?failure.message:String(failure));
      }
    } finally{if(alive.current&&ticket===request.current){entering.current=false;setBusy(false);}}
  },[enabled,mode,stored]);
  const readCurrent=useRef(read);readCurrent.current=read;
  const loadInitial=useCallback(()=>{
    if(initialRead.current&&originalSurface()?.task===null&&section.current?.getClientRects().length){
      void readCurrent.current(stored.cursor||'',stored.prior||[]);
    }
  },[stored]);
  const openTask=(trigger:HTMLElement,id:string,review:boolean)=>{
    const origin=originalSurface(),scroll=scroller(),pageOrigin=acknowledged.current;
    if(!fresh||!shown||!shown.tasks.some(task=>task.id===id)||!origin||!pageOrigin||!sameSurface(pageOrigin,origin)||
      origin.task!==null||entering.current||error||!scroll||!inboxInteractive(scroll)||!trigger.isConnected||!trigger.getClientRects().length||
      trigger.closest(inaccessible))return;
    position.current=null;setReturnNote('');
    visit.current={id,surface:origin,cursor,prior,trigger,scroller:scroll,top:scroll.scrollTop,
      inset:trigger.getBoundingClientRect().top-scroll.getBoundingClientRect().top-scroll.clientTop,
      opened:false,yielded:false,expires:performance.now()+5000};
    onOpenTask(id,review);
  };
  useLayoutEffect(()=>{
    const entry=position.current;if(busy||!fresh||!shown||!entry)return;
    let frame=0;
    const deadline=performance.now()+1000;
    const restore=()=>{
      if(position.current!==entry)return;
      const native=originalSurface(),scroll=entry.scroller;
      if(entry.yielded||error||native?.task!==null||!sameSurface(entry.surface,native)||
        scroll!==scroller()||cursor!==entry.cursor||scroll.scrollTop!==entry.top){position.current=null;return;}
      // Native close and the modal's exit are independent. Never align behind the drawer.
      if(!inboxInteractive(scroll)){
        if(performance.now()<deadline)frame=requestAnimationFrame(restore);
        else position.current=null;
        return;
      }
      position.current=null;
      const target=Array.from(section.current?.querySelectorAll<HTMLElement>('button[data-task-id]')||[])
        .find(item=>item.dataset.taskId===entry.id);
      if(!target||!target.getClientRects().length||target.closest(inaccessible))return;
      const inset=Math.max(0,Math.min(entry.inset,scroll.clientHeight-target.offsetHeight));
      scroll.scrollTop+=target.getBoundingClientRect().top-scroll.getBoundingClientRect().top-scroll.clientTop-inset;
      const focused=document.activeElement;
      if(!focused||focused===document.body||focused===entry.trigger)target.focus({preventScroll:true});
    };
    restore();
    return()=>cancelAnimationFrame(frame);
  },[shown,busy,fresh,error,cursor]);
  useEffect(()=>{
    alive.current=true;
    const scroll=scroller();
    const yieldPosition=(event:Event)=>{
      const entry=visit.current??position.current,current=originalSurface();
      // The opening press comes before this visit exists; modal autofocus is not user input.
      if(!event.isTrusted||!entry?.opened||!scroll||entry.scroller!==scroll||!sameSurface(entry.surface,current)||
        (current?.task!==null&&current?.task!==entry.id)||!inboxInteractive(scroll))return;
      if(event.type==='scroll'&&scroll.scrollTop===entry.top)return;
      entry.yielded=true;position.current=null;
    };
    const invalidate=()=>{
      visit.current=null;position.current=null;initialRead.current=false;acknowledged.current=null;
      request.current++;entering.current=false;setBusy(false);setFresh(false);setPage(null);setReturnNote('');
      setError('窗口已变化，请重新读取原任务记录。');
    };
    const observe=()=>{
      const entry=visit.current,current=originalSurface();
      if(!entry){
        const held=position.current;
        if(held&&(!sameSurface(held.surface,current)||current?.task!==null||held.scroller!==scroller()))position.current=null;
        loadInitial();return;
      }
      if(!current||!sameSurface(entry.surface,current)||!entry.opened&&performance.now()>entry.expires||
        current.task!==null&&current.task!==entry.id){visit.current=null;return;}
      if(current.task===entry.id){entry.opened=true;return;}
      if(!entry.opened)return;
      visit.current=null;
      if(entering.current||entry.scroller!==scroller())return;
      if(!entry.yielded&&entry.scroller.scrollTop===entry.top)position.current=entry;
      void readCurrent.current(entry.cursor,entry.prior,entry);
    };
    const hidden=()=>{if(document.hidden)invalidate();};
    let doc=bridge.getDocumentEpoch();
    const rebound=()=>{const next=bridge.getDocumentEpoch();if(doc!==next){doc=next;invalidate();}};
    const off=bridge.subscribe(observe),offDocument=bridge.subscribeDocumentEpoch(rebound);
    document.addEventListener('visibilitychange',hidden);window.addEventListener('pagehide',invalidate);
    scroll?.addEventListener('scroll',yieldPosition,{passive:true});
    scroll?.addEventListener('pointerdown',yieldPosition,true);
    scroll?.addEventListener('wheel',yieldPosition,true);
    scroll?.addEventListener('keydown',yieldPosition,true);
    return()=>{
      alive.current=false;request.current++;initialRead.current=false;visit.current=null;position.current=null;off();offDocument();
      document.removeEventListener('visibilitychange',hidden);window.removeEventListener('pagehide',invalidate);
      scroll?.removeEventListener('scroll',yieldPosition);scroll?.removeEventListener('pointerdown',yieldPosition,true);
      scroll?.removeEventListener('wheel',yieldPosition,true);scroll?.removeEventListener('keydown',yieldPosition,true);
    };
  },[loadInitial]);
  useEffect(()=>{
    request.current++;entering.current=false;acknowledged.current=null;visit.current=null;position.current=null;
    setPage(null);setBusy(false);setFresh(false);setError('');setReturnNote('');
    // Closing the previous native section and mounting this inbox are not one event.
    initialRead.current=enabled;
    loadInitial();
  },[enabled,mode,loadInitial]);
  const resultLabel=(status:string)=>status==='completed'?'任务已结束':status==='failed'?'这次执行未完成':status==='cancelled'?'任务已停止':labels[status]||status||'状态未提供';
  const category=(task:Task)=>task.historyHasUnconfirmedOperations?'操作待核对':['waiting_approval','awaiting_approval','waiting_permission','awaiting_permission','needs_confirmation'].includes(task.status||'')?'确认与授权':['awaiting_user','waiting_user'].includes(task.status||'')?'需要补充':['failed','interrupted','reconciling','reconciliation_required','unknown'].includes(task.status||'')?'执行异常':task.status==='paused'?'已暂停':'其他待处理';
  const canReview=mode==='attention'&&bridge.getState().capabilities.commands.includes('tasks.review');
  const groups=mode==='attention'
    ? ['操作待核对','确认与授权','需要补充','执行异常','已暂停','其他待处理'].map(title=>({title,tasks:(shown?.tasks||[]).filter(task=>category(task)===title)}))
    : [{title:'已完成',tasks:(shown?.tasks||[]).filter(task=>task.status==='completed')},{title:'未完成与其他结果',tasks:(shown?.tasks||[]).filter(task=>task.status!=='completed')}];
  return <section ref={section} className="pw-notification-attention" aria-label="原任务通知">
    <header className="pw-remote-section-title"><h2 tabIndex={-1}>{mode==='attention'?'原工作':'最近工作结果'}</h2><IconButton icon={IconRefresh} aria-label="重新读取任务记录" disabled={!enabled||busy} onClick={()=>void read(cursor,prior)}/></header>
    {returnNote&&<p className="pw-muted" role="status">{returnNote}</p>}
    {groups.filter(group=>group.tasks.length).map(group=><section className="pw-notification-group" key={group.title} aria-label={group.title}>
      <header className="pw-notification-group-heading"><h3>{group.title}</h3><span aria-label={`本页 ${group.tasks.length} 项`}>{group.tasks.length}</span></header>
      <div className="pw-task-list">{group.tasks.map(task=>{
        const state=task.status||'';
        const direct=canReview&&(task.historyHasUnconfirmedOperations===true||['needs_confirmation','waiting_approval','awaiting_approval','waiting_permission','awaiting_permission','awaiting_user','waiting_user'].includes(state));
        const next=mode==='attention'?(task.historyHasUnconfirmedOperations?'核对操作结果':labels[state]||state||'查看原工作'):resultLabel(state);
        return <Button variant="ghost" className="pw-task-row" key={task.id} data-task-id={task.id} data-task-status={state} disabled={!enabled||busy||!fresh||!!error||actual?.task!==null} onClick={event=>openTask(event.currentTarget,task.id,direct)}>
          <span className="pw-task-row-status">{state==='completed'?<IconCheckCircle size={20} aria-hidden/>:state==='failed'?<IconError size={20} aria-hidden/>:<IconClock size={20} aria-hidden/>}</span>
          <span className="pw-task-row-copy"><b>{task.goal||'原工作'}</b><span className="pw-task-row-meta">{next}{!!task.updatedAt&&<time>{new Date(task.updatedAt).toLocaleString('zh-CN')}</time>}</span>{task.summary&&<span className="pw-task-row-summary">{task.summary}</span>}</span><IconChevronRight size={18} aria-hidden/>
        </Button>;
      })}</div></section>)}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    {busy&&<p className="pw-muted" role="status">{shown?'正在重新核对，暂显示上次读取的记录。':'正在查找原任务记录…'}</p>}
    {!busy&&!fresh&&shown&&<p className="pw-muted">上次读取的记录仅供查看。重新核对后才能打开。</p>}
    {!busy&&!error&&shown?.tasks.length===0&&<p className="pw-muted">{shown.hasMore?'当前读取窗口没有匹配项，仍有较早记录可继续查找。':prior.length?'已到记录末尾，本页没有匹配记录。':mode==='attention'?'当前原任务记录中没有待处理项。':'当前没有已结束的原任务记录。'}</p>}
    {enabled&&!shown&&!busy&&!error&&<p className="pw-muted" role="status">原任务清单尚未读取，可重新读取本页。</p>}
    {!enabled&&<p className="pw-muted">请返回本机主人界面读取任务。</p>}
    <div className="pw-remote-buttons">
      {prior.length>0&&<Button variant="ghost" disabled={busy} onClick={()=>void read(prior[prior.length-1],prior.slice(0,-1))}>较近记录</Button>}
      {shown?.hasMore&&<Button variant="secondary" disabled={busy||!fresh||!!error} onClick={()=>void read(shown.nextCursor,[...prior,cursor])}>{shown.tasks.length?'较早记录':'继续查找较早任务'}</Button>}
      {(returnNote||error)&&cursor&&<Button variant="ghost" disabled={busy||!enabled} onClick={()=>void read('',[])}>回到最近记录</Button>}
      {error&&<Button variant="ghost" disabled={busy||!enabled} onClick={()=>void read(cursor,prior)}>重新读取</Button>}
    </div>
  </section>;
}
