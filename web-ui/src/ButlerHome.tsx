import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from './forms-library';
import {bridge} from './bridge';
import { IconCheckCircle, IconChevronRight, IconClock, IconError, IconInfo, IconPauseCircle } from './icons';
import { ProgressCircle } from './library';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { getRecentTaskProgress } from './task-presentation';
import { isButlerHomeWork, workCollectionPresentation } from './work-collection';
import './butler-home.css';

export type ButlerHomeTask = {
  id: string;
  goal: string;
  status: string;
  summary?: string;
  updatedAt?: number;
  raw?: Record<string, any>;
};

export type ButlerHomeProps = {
  ready: boolean;
  modelReady: boolean;
  modelSetup: ReactNode;
  composer: ReactNode;
  preparations?: (visible: ButlerHomeTask[]) => ReactNode;
  moreWork?: boolean;
  active?: boolean;
  phoneActivity?: ReactNode;
  hasPreparations?: boolean;
  tasks: ButlerHomeTask[];
  canOpen: boolean;
  onOpen: (task: ButlerHomeTask, nativeAlreadyOpen?: boolean) => void;
  onAllWork: (filter?:'all'|'attention') => void;
  onScrollTop?: (top: number) => void;
  initialScrollTop?: number;
};

const updatedAt = (task: ButlerHomeTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;

/** Adapt the retained Affect AssistantHome / Composer composition around the
 * real shared input and BoardUI task rows. This view reads
 * the native private-work projection; only the original task owns decisions. */
export function ButlerHome({ ready, modelReady, modelSetup, composer, preparations, phoneActivity, hasPreparations = false, tasks, canOpen, onOpen, onAllWork, onScrollTop, initialScrollTop = 0, moreWork = false, active = true }: ButlerHomeProps) {
  const scroller = useRef<HTMLDivElement>(null), workRoot = useRef<HTMLElement>(null);
  const allWorkButton = useRef<HTMLButtonElement>(null);
  const focused = useRef<{ node: HTMLElement; id: string } | null>(null);
  const savedScroll = useRef(Number.isFinite(initialScrollTop) ? Math.max(0, initialScrollTop) : 0);
  const restored = useRef(false), reportScroll = useRef(onScrollTop);
  reportScroll.current = onScrollTop;
  const workHeading = useId(), decisionList = useId();
  const [showDecisions,setShowDecisions]=useState(false),[opening,setOpening]=useState(''),[notice,setNotice]=useState('');
  const live=useRef(false),activeRef=useRef(active),flight=useRef(false);activeRef.current=active;
  useLayoutEffect(()=>{live.current=true;return()=>{live.current=false}},[]);
  const seenTaskIds = new Set<string>();

  // Keep the host's redacted goal/summary as supplied. Do not recover content
  // from another conversation, a generic task list or an unprojected source.
  const work = (ready ? tasks : []).filter(isButlerHomeWork)
    .sort((a, b) => updatedAt(b) - updatedAt(a))
    .filter(task => {
      if (seenTaskIds.has(task.id)) return false;
      seenTaskIds.add(task.id);
      return true;
    })
    .map(task => {
      const contextAvailable = task.raw?.contextAvailable !== false;
      const progress = contextAvailable ? getRecentTaskProgress(task) : null;
      const presentation = workCollectionPresentation(task), { handling } = presentation;
      const attention = presentation.group === 'attention';
      const unknown = progress?.source === 'task' && progress.status === 'unknown';
      const status = presentation.label;
      const detail = !contextAvailable ? task.summary?.trim() || '' : unknown ? progress.detail : handling?.waitingForButler || handling?.waitingForChildren || handling?.needsAttention ? handling.reason : task.summary?.trim() || (progress && progress.source !== 'task' ? progress.detail || progress.title : '');
      const runId=task.raw?.runId,epoch=task.raw?.coordinatorEpoch;
      const decision=handling?.ownerDecision&&typeof runId==='string'&&runId.trim()&&Number.isSafeInteger(epoch)&&epoch>0?{runId,epoch}:null;
      return { task, decision, attention, status, detail: detail === status || detail === task.goal ? '' : detail,
        group: presentation.group, busy: presentation.busy, completed: presentation.completed, action: presentation.actionLabel };
    });
  // An older request must not be displaced by a newer pause or unknown receipt.
  // Counts describe this host window, not the entire private work directory.
  const decisions=work.filter(item=>item.decision);
  const shownDecisions=showDecisions?decisions:decisions.slice(0,2);
  const recent = (['attention', 'running', 'ended'] as const)
    .flatMap(group => work.filter(item => !item.decision && item.group === group).slice(0, 2));
  const checking=recent.filter(item=>item.attention),other=recent.filter(item=>!item.attention);
  const visible=[...shownDecisions,...recent];
  const revision = JSON.stringify([ready, canOpen, visible.map(({ task, status, detail }) => [task.id, status, detail])]);
  useCollectionMotion(workRoot, 'butler:owner:private', revision, { active: ready, layout: false });
  const empty = ready && !work.length && !hasPreparations && !phoneActivity && !moreWork;

  useLayoutEffect(() => {
    if (!ready || restored.current || !scroller.current) return;
    scroller.current.scrollTop = savedScroll.current;
    restored.current = true;
  }, [ready]);
  useLayoutEffect(() => {
    const node = scroller.current;
    return () => { if (node && restored.current) reportScroll.current?.(node.scrollTop); };
  }, []);
  // A changing status can reorder or remove a focused task. Restore only
  // a lost focus; never move focus out of the shared composer or another page.
  useLayoutEffect(() => {
    if (!ready) return;
    const original = focused.current;
    if (!original) return;
    const stillFocusable = original.node.isConnected && !original.node.closest('[inert],[hidden]') && !original.node.matches(':disabled');
    if (stillFocusable || document.activeElement !== document.body) return;
    const row = Array.from(workRoot.current?.querySelectorAll<HTMLElement>('[data-task-id]') || []).find(node => node.dataset.taskId === original.id);
    const target = Array.from(row?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || []).find(node => !node.closest('[inert],[hidden]')) || allWorkButton.current;
    focused.current = null;
    if (target && !target.disabled) target.focus({ preventScroll: true });
  }, [revision]);

  const open=async(item:typeof work[number])=>{
    if(!activeRef.current||!canOpen||flight.current)return;
    if(!item.decision){onOpen(item.task);return}
    const id=item.task.id,{runId,epoch}=item.decision,documentEpoch=bridge.getDocumentEpoch();
    const current=()=>live.current&&activeRef.current&&!document.hidden&&bridge.getDocumentEpoch()===documentEpoch;
    const same=()=>{
      const rows=bridge.getState().state.ownerWork;
      return Array.isArray(rows)&&rows.some((row:ButlerHomeTask)=>row?.id===id&&row.raw?.runId===runId&&
        row.raw?.coordinatorEpoch===epoch&&row.status===item.task.status&&workCollectionPresentation(row).handling?.ownerDecision);
    };
    flight.current=true;setOpening(id);setNotice('');
    try{
      if(!current()||!same())throw new Error('这项工作的待办状态已变化，请核对当前记录。');
      // Read-only native preflight. Only this exact task and execution may
      // open; its own sheet retains all answers, proof and decision controls.
      const receipt=await bridge.command('tasks.open',{id,requestNavigation:false,expectedRunId:runId,expectedEpoch:epoch});
      if(!current())return;
      if(receipt?.taskId!==id||receipt.readOnly!==true||receipt.executionAuthorized!==false)throw new Error('尚未核对到原工作，没有打开其他任务。');
      if(!same())throw new Error('这项工作的待办状态已变化，请核对当前记录。');
      onOpen(item.task,true);
    }catch(error){if(current())setNotice(error instanceof Error?error.message:'原工作暂不可读取。')}
    finally{flight.current=false;if(live.current)setOpening('')}
  };
  const renderTask = (item:typeof work[number]) => {
    const { task, decision, attention: needsAttention, group, status, detail, busy, action, completed }=item;
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError
      : needsAttention ? IconInfo : completed ? IconCheckCircle : IconClock;
    const date = new Date(updatedAt(task)), hasDate = updatedAt(task) > 0 && Number.isFinite(date.getTime());
    const readAction=decision?'查看原工作':needsAttention?'查看状态':action;
    return <li key={task.id} data-task-id={task.id} data-motion-key={task.id} data-task-state={task.status} data-attention={needsAttention || undefined}>
      <Button variant="ghost" className="pw-butler-work-row" disabled={!canOpen} aria-disabled={!canOpen||!!opening} aria-busy={opening===task.id} aria-label={`${readAction}：${task.goal || '委派工作'}`} aria-description={[status, detail].filter(Boolean).join('，')} onClick={() => void open(item)}>
        <span className="pw-butler-work-mark" data-attention={needsAttention || undefined} aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <StatusIcon size={18} />}</span>
        <span className="pw-butler-work-copy">
          <strong dir="auto">{task.goal || '委派工作'}</strong>
          <span className="pw-butler-work-meta"><MotionFeedback as="span" identity={status} initial={false}>{status}</MotionFeedback>{group === 'ended' && hasDate && <time dateTime={date.toISOString()}>{date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}</span>
          {group !== 'ended' && detail && <span className="pw-butler-work-detail" dir="auto">{detail}</span>}
        </span>
        <span className="pw-butler-work-next" aria-hidden="true">{opening===task.id?'核对中':readAction}<IconChevronRight size={16} /></span>
      </Button>
    </li>;
  };
  const modelConnection = ready && !modelReady && <div className="pw-butler-model-setup"><p>草稿可以先写。连接模型后发送。</p>{modelSetup}</div>;

  return <main className="pw-chat-workspace pw-butler-workspace" aria-label="管家" data-empty={empty || undefined}>
    <div ref={scroller} className="pw-butler-scroll" onScroll={event => { if (restored.current) reportScroll.current?.(event.currentTarget.scrollTop); }}>
      <div className="pw-butler-content">
        <section className="pw-butler-intake" aria-label="交办">
          <header className="pw-butler-welcome"><h2>想交给我什么事？</h2></header>
          {composer}
          {modelConnection}
        </section>
        {(!ready || work.length > 0 || phoneActivity || hasPreparations || moreWork || notice) && <section ref={workRoot} className="pw-butler-work" aria-labelledby={workHeading} aria-busy={!ready}
          onFocusCapture={event => { const node = event.target as HTMLElement, id = node.closest<HTMLElement>('[data-task-id]')?.dataset.taskId; focused.current = id ? { node, id } : null; }}
          onBlurCapture={event => { const node = event.target as HTMLElement; if (event.relatedTarget instanceof Node ? !event.currentTarget.contains(event.relatedTarget) : node.isConnected && !node.closest('[inert],[hidden]') && !node.matches(':disabled')) focused.current = null; }}>
          <header className="pw-butler-work-heading"><h2 id={workHeading}>{decisions.length?'等你处理':checking.length?'状态需核对':'最近工作'}</h2>{ready && <Button ref={allWorkButton} variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('all')}>全部工作</Button>}</header>
          {!ready ? <p className="pw-butler-reading" role="status"><ProgressCircle isIndeterminate size="sm" aria-label="正在恢复工作记录" /><span>正在恢复工作记录…</span></p> : <>
            {decisions.length>0&&<>
              <p className="pw-butler-scope">{`当前读到 ${decisions.length} 项`}</p>
              <ol id={decisionList} className="pw-butler-work-list">{shownDecisions.map(renderTask)}</ol>
              {decisions.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-controls={decisionList} aria-expanded={showDecisions} onClick={()=>setShowDecisions(value=>!value)}>{showDecisions?'收起待处理工作':`再看 ${decisions.length-2} 项待处理工作`}</Button>}
            </>}
            {notice&&<p className="pw-butler-work-note" role="status">{notice}</p>}
            {!!decisions.length&&checking.length>0&&<h3 className="pw-butler-recent-heading">状态需核对</h3>}
            {checking.length>0&&<ol className="pw-butler-work-list">{checking.map(renderTask)}</ol>}
            {(!!decisions.length||!!checking.length)&&(other.length>0||phoneActivity)&&<h3 className="pw-butler-recent-heading">其他最近工作</h3>}
            {phoneActivity}
            {other.length > 0 && <ol className="pw-butler-work-list">{other.map(renderTask)}</ol>}
            {moreWork&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('attention')}>到工作目录查找待处理项</Button>}
            {!!work.length && !canOpen && <p className="pw-butler-work-note" role="status">当前暂时无法打开原任务记录。</p>}
          </>}
        </section>}
        {preparations?.(visible.map(item=>item.task))}
      </div>
    </div>
  </main>;
}

export default ButlerHome;
