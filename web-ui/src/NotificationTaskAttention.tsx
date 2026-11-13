import './notification-attention.css';
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {DecisionInbox} from './shell-library/DecisionInbox';
import {IconRefresh,IconCheckCircle,IconError,IconClock,IconChevronRight} from './icons';
import type {NotificationViewMemory} from './RemoteInboxWorkspace';

type Task = {id:string;goal?:string;status?:string;summary?:string;updatedAt?:number;historyHasUnconfirmedOperations?:boolean};
type Page = {tasks:Task[];nextCursor:string;hasMore:boolean;headerRowsExamined?:number};
const labels:Record<string,string>={needs_confirmation:'结果等待你确认',awaiting_user:'等待你补充',waiting_user:'等待你补充',waiting_approval:'等待你授权',awaiting_approval:'等待你授权',waiting_permission:'等待你授权',awaiting_permission:'等待你授权',paused:'任务已暂停',interrupted:'执行已中断',failed:'执行失败',reconciling:'操作结果待核对',reconciliation_required:'操作结果待核对',unknown:'执行结果未知'};

/** Paged original task headers. Reading this queue never answers, approves or resumes work. */
export function NotificationTaskAttention({enabled,onOpenTask,memory}:{enabled:boolean;onOpenTask:(id:string,review?:boolean)=>void;memory:NotificationViewMemory}) {
  const [managing,setManaging]=useState(false);
  const [mode,setMode]=useState<'attention'|'taskUpdates'>(memory.taskMode||'attention');
  const [page,setPage]=useState<Page|null>(null),[cursor,setCursor]=useState(memory.taskCursor||''),[prior,setPrior]=useState<string[]>(memory.taskPrior||[]);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const request=useRef(0),entering=useRef(false),alive=useRef(true);
  const section=useRef<HTMLElement>(null),restoreFocus=useRef(memory.taskFocus||'');
  useLayoutEffect(()=>{
    if(!page||!restoreFocus.current)return;
    const row=Array.from(section.current?.querySelectorAll<HTMLElement>('[data-task-id]')||[]).find(item=>item.dataset.taskId===restoreFocus.current);
    if(!row)return;
    restoreFocus.current='';
    if(document.activeElement!==document.body&&!section.current?.contains(document.activeElement))return;
    row.scrollIntoView({block:'nearest'});
    (row.matches('button')?row:row.querySelector<HTMLElement>('button'))?.focus({preventScroll:true});
  },[page]);
  const openTask=(id:string,review:boolean)=>{memory.taskFocus=id;onOpenTask(id,review)};
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++}},[]);
  const read=async(next:string,history:string[])=>{
    if(!enabled||entering.current)return;
    entering.current=true;const ticket=++request.current;setBusy(true);setError('');
    try {
      const result=await bridge.command('history.read',{section:mode,cursor:next}) as Page;
      if(alive.current&&ticket===request.current){setPage(result);setCursor(next);setPrior(history);memory.taskCursor=next;memory.taskPrior=history;}
    } catch(failure){if(alive.current&&ticket===request.current)setError(failure instanceof Error?failure.message:String(failure));}
    finally{if(alive.current&&ticket===request.current){entering.current=false;setBusy(false);}}
  };
  useEffect(()=>{request.current++;entering.current=false;setPage(null);setBusy(false);setError('');if(enabled)void read(cursor,prior);},[enabled,mode]);
  const switchMode=(next:'attention'|'taskUpdates')=>{if(mode===next)return;request.current++;entering.current=false;restoreFocus.current='';memory.taskFocus='';setCursor('');setPrior([]);setPage(null);memory.taskCursor='';memory.taskPrior=[];memory.taskMode=next;setMode(next)};
  const resultLabel=(status:string)=>status==='completed'?'任务已结束':status==='failed'?'这次执行未完成':status==='cancelled'?'任务已停止':labels[status]||status||'状态未提供';
  const category=(task:Task)=>task.historyHasUnconfirmedOperations?'操作待核对':['waiting_approval','awaiting_approval','waiting_permission','awaiting_permission','needs_confirmation'].includes(task.status||'')?'确认与授权':['awaiting_user','waiting_user'].includes(task.status||'')?'需要补充':['failed','interrupted','reconciling','reconciliation_required','unknown'].includes(task.status||'')?'执行异常':task.status==='paused'?'已暂停':'其他待处理';
  const groups=mode==='attention'
    ? ['操作待核对','确认与授权','需要补充','执行异常','已暂停','其他待处理'].map(title=>({title,tasks:(page?.tasks||[]).filter(task=>category(task)===title)}))
    : [{title:'已完成',tasks:(page?.tasks||[]).filter(task=>task.status==='completed')},{title:'未完成与其他结果',tasks:(page?.tasks||[]).filter(task=>task.status!=='completed')}];
  return <section ref={section} className="pw-notification-attention" aria-label="原任务通知">
    <header className="pw-remote-section-title"><h2>我的任务</h2><IconButton icon={IconRefresh} aria-label="重新读取任务记录" disabled={!enabled||busy} onClick={()=>void read('',[])}/></header>
    <div className="pw-remote-filters pw-material-navigation" role="group" aria-label="任务通知类型"><Button variant="ghost" aria-pressed={mode==='attention'} onClick={()=>switchMode('attention')}>待我处理</Button><Button variant="ghost" aria-pressed={mode==='taskUpdates'} onClick={()=>switchMode('taskUpdates')}>任务结果</Button></div>
    <p className="pw-remote-footnote">查看待办或结果；打开通知不会执行任务。</p>
    {page&&page.tasks.length>0&&<div className="pw-notification-view-tools"><span>本页 {page.tasks.length} 项</span>{mode==='attention'&&<Button variant="ghost" aria-pressed={managing} onClick={()=>setManaging(value=>!value)}>{managing?'完成管理':'管理待办'}</Button>}</div>}
    {groups.filter(group=>group.tasks.length>0).map(group=><section className="pw-notification-group" key={group.title} aria-label={group.title}>
      {mode==='attention'&&managing?<DecisionInbox compact heading={group.title} decisions={group.tasks.map(task=>({id:task.id,title:task.goal||'原任务',detail:task.historyHasUnconfirmedOperations?'有操作结果尚未核实':labels[task.status||'']||task.summary||'请查看原任务',primary:'查看并处理',secondary:'查看原任务'}))} onDecide={(decision,answer)=>openTask(decision.id,answer==='primary')}/>:<><header className="pw-notification-group-heading"><h3>{group.title}</h3><span>{group.tasks.length}</span></header><div className="pw-task-list">{group.tasks.map(task=><Button variant="ghost" key={task.id} className="pw-task-row" data-task-id={task.id} data-task-status={task.status} onClick={()=>openTask(task.id,false)}><span className="pw-task-row-status">{task.status==='completed'?<IconCheckCircle size={20} aria-hidden/>:task.status==='failed'?<IconError size={20} aria-hidden/>:<IconClock size={20} aria-hidden/>}</span><span className="pw-task-row-copy"><b>{task.goal||'原任务'}</b>{task.summary&&<span className="pw-task-row-summary">{task.summary}</span>}<span className="pw-task-row-meta">{mode==='attention'?(task.historyHasUnconfirmedOperations?'有操作待核对':labels[task.status||'']||task.status||'待查看'):resultLabel(task.status||'')}{!!task.updatedAt&&<time>{new Date(task.updatedAt).toLocaleString('zh-CN')}</time>}</span></span><IconChevronRight size={18} aria-hidden/></Button>)}</div></>}
    </section>)}
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
