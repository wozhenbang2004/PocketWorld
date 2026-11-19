import './notification-attention.css';
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {IconRefresh,IconCheckCircle,IconError,IconClock,IconChevronRight} from './icons';
import type {NotificationViewMemory} from './RemoteInboxWorkspace';

type Task = {id:string;goal?:string;status?:string;summary?:string;updatedAt?:number;historyHasUnconfirmedOperations?:boolean};
type Page = {tasks:Task[];nextCursor:string;hasMore:boolean;headerRowsExamined?:number};
const labels:Record<string,string>={needs_confirmation:'结果等待你确认',awaiting_user:'等待你补充',waiting_user:'等待你补充',waiting_approval:'等待你授权',awaiting_approval:'等待你授权',waiting_permission:'等待你授权',awaiting_permission:'等待你授权',paused:'任务已暂停',interrupted:'执行已中断',failed:'执行失败',reconciling:'操作结果待核对',reconciliation_required:'操作结果待核对',unknown:'执行结果未知'};

/** Paged original task headers. Reading this queue never answers, approves or resumes work. */
export function NotificationTaskAttention({enabled,onOpenTask,memory,mode}:{enabled:boolean;mode:'attention'|'taskUpdates';onOpenTask:(id:string,review?:boolean)=>void;memory:NotificationViewMemory}) {
  const pages=memory.taskPages??(memory.taskPages={});
  const stored=pages[mode]??(pages[mode]=memory.taskMode===mode?{cursor:memory.taskCursor,prior:memory.taskPrior,focus:memory.taskFocus}:{});
  const [page,setPage]=useState<Page|null>(null),[cursor,setCursor]=useState(stored.cursor||''),[prior,setPrior]=useState<string[]>(stored.prior||[]);
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[returnRevision,reloadReturn]=useState(0),[returnNote,setReturnNote]=useState('');
  const request=useRef(0),entering=useRef(false),alive=useRef(true),section=useRef<HTMLElement>(null);
  const restoreFocus=useRef(stored.focus||''),inTask=useRef<{id:string;seen:boolean}|null>(null);
  useLayoutEffect(()=>{
    const id=restoreFocus.current;if(!page||!id)return;
    const row=Array.from(section.current?.querySelectorAll<HTMLElement>('button[data-task-id]')||[]).find(item=>item.dataset.taskId===id);
    restoreFocus.current='';stored.focus=undefined;
    if(!row)setReturnNote('已重新读取，先前打开的工作不在当前页。');
    if(document.activeElement!==document.body&&!section.current?.contains(document.activeElement))return;
    const target=row||section.current?.querySelector<HTMLElement>('h2');target?.scrollIntoView({block:'nearest'});target?.focus({preventScroll:true});
  },[page,stored]);
  const openTask=(id:string,review:boolean)=>{stored.focus=id;inTask.current={id,seen:false};setReturnNote('');onOpenTask(id,review)};
  // The native drawer covers this page without unmounting it. Refresh the
  // actual cursor after this same native task closes, not on every snapshot.
  useEffect(()=>{
    const observe=()=>{const source=inTask.current;if(!source||document.hidden)return;
      const taskId=(bridge.getState().view as {taskId?:string}|undefined)?.taskId;
      if(taskId===source.id){source.seen=true;return}
      if(source.seen&&!taskId){inTask.current=null;restoreFocus.current=source.id;reloadReturn(value=>value+1)}
    };
    const off=bridge.subscribe(observe);document.addEventListener('visibilitychange',observe);
    return()=>{off();document.removeEventListener('visibilitychange',observe)};
  },[]);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++}},[]);
  const read=async(next:string,history:string[])=>{
    if(!enabled||entering.current)return;
    entering.current=true;const ticket=++request.current;setBusy(true);setError('');
    try {
      const result=await bridge.command('history.read',{section:mode,cursor:next}) as Page;
      if(alive.current&&ticket===request.current){setPage(result);setCursor(next);setPrior(history);stored.cursor=next;stored.prior=history;}
    } catch(failure){if(alive.current&&ticket===request.current)setError(failure instanceof Error?failure.message:String(failure));}
    finally{if(alive.current&&ticket===request.current){entering.current=false;setBusy(false);}}
  };
  useEffect(()=>{request.current++;entering.current=false;setPage(null);setBusy(false);setError('');if(enabled)void read(cursor,prior);},[enabled,mode,returnRevision]);
  const resultLabel=(status:string)=>status==='completed'?'任务已结束':status==='failed'?'这次执行未完成':status==='cancelled'?'任务已停止':labels[status]||status||'状态未提供';
  const category=(task:Task)=>task.historyHasUnconfirmedOperations?'操作待核对':['waiting_approval','awaiting_approval','waiting_permission','awaiting_permission','needs_confirmation'].includes(task.status||'')?'确认与授权':['awaiting_user','waiting_user'].includes(task.status||'')?'需要补充':['failed','interrupted','reconciling','reconciliation_required','unknown'].includes(task.status||'')?'执行异常':task.status==='paused'?'已暂停':'其他待处理';
  const groups=mode==='attention'
    ? ['操作待核对','确认与授权','需要补充','执行异常','已暂停','其他待处理'].map(title=>({title,tasks:(page?.tasks||[]).filter(task=>category(task)===title)}))
    : [{title:'已完成',tasks:(page?.tasks||[]).filter(task=>task.status==='completed')},{title:'未完成与其他结果',tasks:(page?.tasks||[]).filter(task=>task.status!=='completed')}];
  return <section ref={section} className="pw-notification-attention" aria-label="原任务通知">
    <header className="pw-remote-section-title"><h2 tabIndex={-1}>{mode==='attention'?'原工作':'最近工作结果'}</h2><IconButton icon={IconRefresh} aria-label="重新读取任务记录" disabled={!enabled||busy} onClick={()=>void read(cursor,prior)}/></header>
    {returnNote&&<p className="pw-muted" role="status">{returnNote}</p>}
    {groups.filter(group=>group.tasks.length).map(group=><section className="pw-notification-group" key={group.title} aria-label={group.title}>
      <header className="pw-notification-group-heading"><h3>{group.title}</h3><span aria-label={`本页 ${group.tasks.length} 项`}>{group.tasks.length}</span></header>
      <div className="pw-task-list">{group.tasks.map(task=>{
        const state=task.status||'',canReview=mode==='attention'&&bridge.getState().capabilities.commands.includes('tasks.review');
        const direct=canReview&&(task.historyHasUnconfirmedOperations===true||['needs_confirmation','waiting_approval','awaiting_approval','waiting_permission','awaiting_permission','awaiting_user','waiting_user'].includes(state));
        const next=mode==='attention'?(task.historyHasUnconfirmedOperations?'核对操作结果':labels[state]||state||'查看原工作'):resultLabel(state);
        return <div className="pw-inbox-original-line" key={task.id}><Button variant="ghost" className="pw-task-row" data-task-id={task.id} data-task-status={state} onClick={()=>openTask(task.id,direct)}>
          <span className="pw-task-row-status">{state==='completed'?<IconCheckCircle size={20} aria-hidden/>:state==='failed'?<IconError size={20} aria-hidden/>:<IconClock size={20} aria-hidden/>}</span>
          <span className="pw-task-row-copy"><b>{task.goal||'原工作'}</b><span className="pw-task-row-meta">{next}{!!task.updatedAt&&<time>{new Date(task.updatedAt).toLocaleString('zh-CN')}</time>}</span>{task.summary&&<span className="pw-task-row-summary">{task.summary}</span>}</span><IconChevronRight size={18} aria-hidden/></Button>
          {canReview&&<Button variant="ghost" size="small" className="pw-inbox-row-alt" aria-label={(direct?'查看工作概览：':'查看这件工作的处理页：')+(task.goal||'原工作')} onClick={()=>openTask(task.id,!direct)}>{direct?'概览':'处理页'}</Button>}
        </div>;
      })}</div></section>)}
    {error&&<p className="pw-error" role="alert">{error}</p>}
    {busy&&<p className="pw-muted" role="status">正在查找原任务记录…</p>}
    {!busy&&!error&&page?.tasks.length===0&&<p className="pw-muted">{page.hasMore?'当前读取窗口没有匹配项，仍有较早记录可继续查找。':prior.length?'已到记录末尾，本页没有匹配记录。':mode==='attention'?'当前原任务记录中没有待处理项。':'当前没有已结束的原任务记录。'}</p>}
    {!enabled&&<p className="pw-muted">请返回本机主人界面读取任务。</p>}
    <div className="pw-remote-buttons">
      {prior.length>0&&<Button variant="ghost" disabled={busy} onClick={()=>void read(prior[prior.length-1],prior.slice(0,-1))}>较近记录</Button>}
      {page?.hasMore&&<Button variant="secondary" disabled={busy} onClick={()=>void read(page.nextCursor,[...prior,cursor])}>{page.tasks.length?'较早记录':'继续查找较早任务'}</Button>}
      {error&&<Button variant="ghost" disabled={busy||!enabled} onClick={()=>void read(cursor,prior)}>重新读取</Button>}
    </div>
  </section>;
}
