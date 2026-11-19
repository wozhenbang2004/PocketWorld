import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from './forms-library';
import {bridge} from './bridge';
import {reportedHomeWork,sameHomeReport,verifyHomeReport,type VerifiedHomeReport} from './ButlerReturnedReport';
import { IconCheckCircle, IconChevronDown, IconChevronRight, IconClock, IconError, IconInfo, IconPauseCircle } from './icons';
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
  onOpen: (task: ButlerHomeTask, nativeAlreadyOpen?: boolean, report?:VerifiedHomeReport, stillOriginal?:()=>boolean) => void;
  canReadReports?:boolean;
  onAllWork: (filter?:'all'|'attention'|'running') => void;
  onScrollTop?: (top: number) => void;
  initialScrollTop?: number;
};

const updatedAt = (task: ButlerHomeTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;

/** A private original may have a saved result without a butler message.
 * This is an entry to its existing result surface, never a write receipt. */
function originalSavedResult(task: ButlerHomeTask, view = workCollectionPresentation(task)) {
  const runId = task.raw?.runId, epoch = task.raw?.coordinatorEpoch;
  return view.completed && view.handling?.resultAvailable === true &&
    !reportedHomeWork(task, view.handling) && typeof runId === 'string' && runId.trim() && Number.isSafeInteger(epoch) && epoch > 0
    ? {runId, epoch} : null;
}

/** Adapt the retained Affect AssistantHome / Composer composition around the
 * real shared input and BoardUI task rows. This view reads
 * the native private-work projection; only the original task owns decisions. */
export function ButlerHome({ ready, modelReady, modelSetup, composer, preparations, phoneActivity, hasPreparations = false, tasks, canOpen, onOpen, onAllWork, onScrollTop, initialScrollTop = 0, moreWork = false, active = true, canReadReports=false }: ButlerHomeProps) {
  const scroller = useRef<HTMLDivElement>(null), workRoot = useRef<HTMLElement>(null);
  const allWorkButton = useRef<HTMLButtonElement>(null);
  const focused = useRef<{ node: HTMLElement; id: string } | null>(null);
  const savedScroll = useRef(Number.isFinite(initialScrollTop) ? Math.max(0, initialScrollTop) : 0);
  const restored = useRef(false), reportScroll = useRef(onScrollTop);
  reportScroll.current = onScrollTop;
  const workHeading = useId(), decisionList = useId(), returnedList = useId(), checkingList = useId();
  const [allResults,setAllResults]=useState(false),[unavailableReport,setUnavailableReport]=useState<ReturnType<typeof reportedHomeWork>>(null);
  const [showDecisions,setShowDecisions]=useState(false),[checkingExpanded,setCheckingExpanded]=useState<boolean>();
  const [opening,setOpening]=useState(''),[notice,setNotice]=useState('');
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
      const saved = originalSavedResult(task,presentation);
      const status = saved ? '成果已保存' : presentation.label;
      const detail = !contextAvailable ? task.summary?.trim() || '' : unknown ? progress.detail : handling?.waitingForButler || handling?.waitingForChildren || handling?.needsAttention ? handling.reason : task.summary?.trim() || (progress && progress.source !== 'task' ? progress.detail || progress.title : '');
      const runId=task.raw?.runId,epoch=task.raw?.coordinatorEpoch;
      const decision=handling?.ownerDecision&&typeof runId==='string'&&runId.trim()&&Number.isSafeInteger(epoch)&&epoch>0?{runId,epoch}:null;
      const report=presentation.group==='ended'?reportedHomeWork(task,handling):null;
      const needsOwner = !!decision || !presentation.reconciliation && (presentation.pendingApproval || handling?.needsAttention === true);
      return { task, decision, needsOwner, report, saved, attention, status, detail: detail === status || detail === task.goal ? '' : detail,
        group: presentation.group, busy: presentation.busy, completed: presentation.completed, action: presentation.actionLabel };
    });
  // An older request must not be displaced by a newer pause or unknown receipt.
  // Counts describe this host window, not the entire private work directory.
  const decisions=work.filter(item=>item.needsOwner);
  const shownDecisions=showDecisions?decisions:decisions.slice(0,2);
  const checking=work.filter(item=>!item.needsOwner&&item.group==='attention');
  const running=work.filter(item=>item.group==='running');
  const results=work.filter(item=>item.report||item.saved);
  const ended=work.filter(item=>item.group==='ended'&&!item.report&&!item.saved);
  const shownRunning=running.slice(0,2), shownResults=allResults?results:results.slice(0,2);
  const showChecking=checkingExpanded??(!decisions.length&&!running.length&&!results.length&&!phoneActivity);
  const shownEnded=ended.slice(0,1), shownChecking=showChecking?checking.slice(0,2):[];
  // Present each job once under the human question it answers. Fold only
  // diagnostics; real decisions and the newest original results stay one tap away.
  const visible=[...shownDecisions,...shownRunning,...shownResults,...shownChecking,...shownEnded];
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
    const originalOnly=!!item.report&&!!unavailableReport&&sameHomeReport(item.report,unavailableReport);
    const report=canReadReports&&!originalOnly?item.report:null,binding=item.decision||item.report||item.saved;
    if(!binding){onOpen(item.task);return}
    const id=item.task.id,{runId,epoch}=binding,documentEpoch=bridge.getDocumentEpoch();
    const resume=()=>Number((bridge.getState().view as {resumeRevision?:number}|undefined)?.resumeRevision||0),startResume=resume();
    const originalDocument=()=>!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&resume()===startResume;
    const current=()=>live.current&&activeRef.current&&originalDocument();
    const same=()=>{
      const rows=bridge.getState().state.ownerWork;
      return Array.isArray(rows)&&rows.some((row:ButlerHomeTask)=>row?.id===id&&row.raw?.runId===runId&&row.raw?.coordinatorEpoch===epoch&&
        (item.report?sameHomeReport(reportedHomeWork(row),item.report):item.saved?!!originalSavedResult(row):row.status===item.task.status&&workCollectionPresentation(row).handling?.ownerDecision));
    };
    let ownsWindow=false,handedOff=false;
    flight.current=true;setOpening(id);setNotice('');
    try{
      if(!current()||!same())throw new Error('原工作已变化，请核对当前记录。');
      const receipt=await bridge.command('tasks.open',{id,requestNavigation:false,expectedRunId:runId,expectedEpoch:epoch});
      if(receipt?.taskId!==id||receipt.readOnly!==true||receipt.executionAuthorized!==false)throw new Error('尚未核对到原工作，没有打开其他任务。');
      ownsWindow=true;
      if(!current())return;
      if(!same())throw new Error('原工作已变化，请核对当前记录。');
      const verified=report?await verifyHomeReport(report):undefined;
      if(!current())return;
      if(!same())throw new Error('原工作或汇报已变化，没有改读其他轮次。');
      onOpen(item.task,true,verified,()=>originalDocument()&&same());handedOff=true;
    }catch(error){
      if(current()){
        const reason=error instanceof Error?error.message:'原工作暂不可读取。';
        if(report&&ownsWindow&&same()){setUnavailableReport(report);setNotice(`${reason} 可点「查看原工作」核对同一轮记录。`)}
        else setNotice(reason);
      }
    }
    finally{
      // Release only our own provisional read if the current Home gesture
      // failed; do not close a view that another route has since entered.
      if((item.report||item.saved)&&ownsWindow&&!handedOff&&current()&&(bridge.getState().view as {taskId?:string}|undefined)?.taskId===id)
        await bridge.command('tasks.close',{}).catch(()=>{});
      flight.current=false;if(live.current)setOpening('');
    }
  };

  const renderTask = (item:typeof work[number]) => {
    const { task, decision, attention: needsAttention, group, status, detail, busy, action, completed }=item;
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError
      : needsAttention ? IconInfo : completed ? IconCheckCircle : IconClock;
    const date = new Date(updatedAt(task)), hasDate = updatedAt(task) > 0 && Number.isFinite(date.getTime());
    const readAction=decision?'查看原工作':item.report?(canReadReports&&(!unavailableReport||!sameHomeReport(item.report,unavailableReport))?'阅读原汇报':'查看原工作'):item.saved?'查看已保存成果':needsAttention?'查看状态':action;
    return <li key={task.id} data-opening={opening===task.id||undefined} data-task-id={task.id} data-motion-key={task.id} data-task-state={task.status} data-attention={needsAttention || undefined}>
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
  const modelConnection = ready && !modelReady && <div className="pw-butler-model-setup">{modelSetup}<p>草稿可以先写，连接后再发送。</p></div>;

  return <main className="pw-chat-workspace pw-butler-workspace" aria-label="管家" data-empty={empty || undefined}>
    <div ref={scroller} className="pw-butler-scroll" onScroll={event => { if (restored.current) reportScroll.current?.(event.currentTarget.scrollTop); }}>
      <div className="pw-butler-content">
        <section className="pw-butler-intake" aria-label="交办">
          <header className="pw-butler-welcome"><h2>想交给我什么事？</h2></header>
          {composer}
          {modelConnection}
        </section>
        {(!ready || work.length > 0 || phoneActivity || moreWork || notice) && <section ref={workRoot} className="pw-butler-work" aria-labelledby={workHeading} aria-busy={!ready}
          onFocusCapture={event => { const node = event.target as HTMLElement, id = node.closest<HTMLElement>('[data-task-id]')?.dataset.taskId; focused.current = id ? { node, id } : null; }}
          onBlurCapture={event => { const node = event.target as HTMLElement; if (event.relatedTarget instanceof Node ? !event.currentTarget.contains(event.relatedTarget) : node.isConnected && !node.closest('[inert],[hidden]') && !node.matches(':disabled')) focused.current = null; }}>
          <header className="pw-butler-work-heading"><h2 id={workHeading}>我的工作</h2>{ready && <Button ref={allWorkButton} variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('all')}>全部工作</Button>}</header>
          {!ready ? <p className="pw-butler-reading" role="status"><ProgressCircle isIndeterminate size="sm" aria-label="正在恢复工作记录" /><span>正在恢复工作记录…</span></p> : <>
            {notice&&<p className="pw-butler-work-note" role="status">{notice}</p>}
            {decisions.length>0&&<section className="pw-butler-work-group pw-butler-work-inbox" aria-label="等你处理">
              <header className="pw-butler-group-heading"><h3>等你处理</h3><span className="pw-butler-group-count" aria-label={`眼前的 ${decisions.length} 项待处理工作`}>{decisions.length}</span></header>
              <ol id={decisionList} className="pw-butler-work-list">{shownDecisions.map(renderTask)}</ol>
              {decisions.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-controls={decisionList} aria-expanded={showDecisions} onClick={()=>setShowDecisions(value=>!value)}>{showDecisions?'收起待处理工作':`再看 ${decisions.length-2} 项`}</Button>}
            </section>}
            {(shownRunning.length>0||phoneActivity)&&<section className="pw-butler-work-group" aria-label="正在跟进">
              <header className="pw-butler-group-heading"><h3>正在跟进</h3>{running.length>shownRunning.length&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('running')}>查看全部</Button>}</header>
              {phoneActivity}
              {shownRunning.length>0&&<ol className="pw-butler-work-list">{shownRunning.map(renderTask)}</ol>}
            </section>}
            {shownResults.length>0&&<section className="pw-butler-work-group" aria-label="成果">
              <header className="pw-butler-group-heading"><h3>成果</h3></header>
              <ol id={returnedList} className="pw-butler-work-list">{shownResults.map(renderTask)}</ol>
              {results.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-expanded={allResults} aria-controls={returnedList} onClick={()=>setAllResults(value=>!value)}>{allResults?'收起成果':`再看 ${results.length-2} 项成果`}</Button>}
            </section>}
            {(checking.length>0||shownEnded.length>0||moreWork)&&<div className="pw-butler-work-group pw-butler-work-secondary">
              {checking.length>0&&<>
                <Button variant="ghost" className="pw-butler-all-work pw-butler-check-toggle" aria-expanded={showChecking} aria-controls={checkingList} trailingIcon={IconChevronDown} onClick={()=>setCheckingExpanded(!showChecking)}>暂停或待核对 · {checking.length}</Button>
                <div id={checkingList} hidden={!showChecking}>
                  <ol className="pw-butler-work-list">{shownChecking.map(renderTask)}</ol>
                  {checking.length>shownChecking.length&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('attention')}>查看待处理工作</Button>}
                </div>
              </>}
              {shownEnded.length>0&&<ol className="pw-butler-work-list" aria-label="最近结束的其他工作">{shownEnded.map(renderTask)}</ol>}
              {moreWork&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('attention')}>到目录查找其他待处理工作</Button>}
            </div>}
            {!!work.length && !canOpen && <p className="pw-butler-work-note" role="status">当前暂时无法打开原任务记录。</p>}
          </>}
        </section>}
        {preparations?.(visible.map(item=>item.task))}
      </div>
    </div>
  </main>;
}

export default ButlerHome;
