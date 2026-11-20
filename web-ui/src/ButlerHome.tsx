import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from './forms-library';
import {bridge} from './bridge';
import {reportedHomeWork,sameHomeReport,verifyHomeReport,type VerifiedHomeReport} from './ButlerReturnedReport';
import { IconCheckCircle, IconChevronDown, IconChevronRight, IconClock, IconError, IconFolder, IconInfo, IconPauseCircle, IconPhone } from './icons';
import { ProgressCircle } from './library';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { getRecentTaskProgress } from './task-presentation';
import { isButlerHomeWork, workCollectionPresentation, type WorkCollectionFilter } from './work-collection';
import { domainLabel, homeDomainResult, homeWorkIdentity, type HomeDomain } from './butler-work-entry';
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
  localDisplayDraft?: boolean;
  onTryLocal?: () => void;
  onImportProject?: () => void;
  stagingLocal?: boolean;
  modelSetup: ReactNode;
  firstHostSave?: { hint: string; disabled: boolean; busy: boolean; save: () => void };
  composer: ReactNode;
  preparations?: (visible: ButlerHomeTask[]) => ReactNode;
  moreWork?: boolean;
  active?: boolean;
  phoneActivity?: ReactNode;
  hasPreparations?: boolean;
  tasks: ButlerHomeTask[];
  canOpen: boolean;
  onOpen: (task: ButlerHomeTask, nativeAlreadyOpen?: boolean, report?:VerifiedHomeReport, stillOriginal?:()=>boolean, returnWorkFocus?:()=>boolean) => void;
  canReadReports?:boolean;
  onAllWork: (filter?: WorkCollectionFilter) => void;
  onScrollTop?: (top: number) => void;
  initialScrollTop?: number;
};

const updatedAt = (task: ButlerHomeTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;
const resumeRevision = () => Number((bridge.getState().view as {resumeRevision?:number}|undefined)?.resumeRevision||0);
type WorkReturn = {
  target:Pick<VerifiedHomeReport['target'],'taskId'|'runId'|'epoch'>;
  report?:VerifiedHomeReport['target']; domain?:HomeDomain; intent:'row'|'report'; offset?:number;
  documentEpoch:number; resume:number;
};

/** A private original may have a saved result without a butler message.
 * This is an entry to its existing result surface, never a write receipt. */
function originalSavedResult(task: ButlerHomeTask, view = workCollectionPresentation(task)) {
  const runId = task.raw?.runId, epoch = task.raw?.coordinatorEpoch;
  const identity=homeWorkIdentity(task), nativeLabel=identity?homeDomainResult(task,identity.domain):'';
  const label=view.handling?.resultAvailable===true?'成果已保存':nativeLabel;
  return view.completed && !!label && !reportedHomeWork(task, view.handling) &&
    typeof runId === 'string' && runId.trim() && Number.isSafeInteger(epoch) && (epoch > 0 || epoch === 0 && !!identity?.direct) ? {runId, epoch, label} : null;
}

/** Adapt the retained Affect AssistantHome / Composer composition around the
 * real shared input and BoardUI task rows. This view reads
 * the native private-work projection; only the original task owns decisions. */
export function ButlerHome({ ready, modelReady, localDisplayDraft=false, onTryLocal, onImportProject, stagingLocal=false, modelSetup, firstHostSave, composer, preparations, phoneActivity, hasPreparations = false, tasks, canOpen, onOpen, onAllWork, onScrollTop, initialScrollTop = 0, moreWork = false, active = true, canReadReports=false }: ButlerHomeProps) {
  const scroller = useRef<HTMLDivElement>(null), workRoot = useRef<HTMLElement>(null);
  const allWorkButton = useRef<HTMLButtonElement>(null);
  const focused = useRef<{ node: HTMLElement; id: string } | null>(null);
  const savedScroll = useRef(Number.isFinite(initialScrollTop) ? Math.max(0, initialScrollTop) : 0);
  const restored = useRef(false), homeScroll = useRef(onScrollTop);
  homeScroll.current = onScrollTop;
  const workHeading = useId(), decisionList = useId(), returnedList = useId(), checkingList = useId(), nextNote = useId();
  const [allResults,setAllResults]=useState(false),[unavailableReport,setUnavailableReport]=useState<ReturnType<typeof reportedHomeWork>>(null);
  const [showDecisions,setShowDecisions]=useState(false),[checkingExpanded,setCheckingExpanded]=useState<boolean>();
  const [opening,setOpening]=useState<{id:string;intent:'row'|'report'}|null>(null),[notice,setNotice]=useState('');
  const [workReturn,setWorkReturn]=useState<WorkReturn|null>(null),restoredWork=useRef<WorkReturn|null>(null);
  const live=useRef(false),activeRef=useRef(active),flight=useRef(false);activeRef.current=active;
  useLayoutEffect(()=>{live.current=true;return()=>{live.current=false}},[]);
  const seenTaskIds = new Set<string>();

  // Keep the host's redacted goal/summary as supplied. Do not recover content
  // from another conversation, a generic task list or an unprojected source.
  const work = (ready ? tasks : []).filter(task=>isButlerHomeWork(task)||homeWorkIdentity(task))
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
      const exact=homeWorkIdentity(task);
      const status = saved?.label || presentation.label;
      const detail = !contextAvailable ? task.summary?.trim() || '' : unknown ? progress.detail : handling?.waitingForButler || handling?.waitingForChildren || handling?.needsAttention ? handling.reason : task.summary?.trim() || (progress && progress.source !== 'task' ? progress.detail || progress.title : '');
      const runId=task.raw?.runId,epoch=task.raw?.coordinatorEpoch;
      const decision=handling?.ownerDecision&&typeof runId==='string'&&runId.trim()&&Number.isSafeInteger(epoch)&&epoch>0?{runId,epoch}:null;
      // The butler may have filed its report before the owner accepts the work.
      // Keep that decision in place; the saved report is something to read, not a verdict.
      const report=presentation.group==='ended'||decision?reportedHomeWork(task,handling):null;
      const needsOwner = !!decision || !presentation.reconciliation && (presentation.pendingApproval || handling?.needsAttention === true ||
        !!exact && ['awaiting_user','waiting_user','waiting_permission','awaiting_permission','waiting_approval','awaiting_approval','needs_confirmation'].includes(task.status));
      return { task, decision, needsOwner, report, saved, exact, attention, status, detail: detail === status || detail === task.goal ? '' : detail,
        group: presentation.group, busy: presentation.busy, completed: presentation.completed, action: presentation.actionLabel };
    });
  // An older request must not be displaced by a newer pause or unknown receipt.
  // Counts describe this host window, not the entire private work directory.
  const retained=workReturn?.documentEpoch===bridge.getDocumentEpoch()&&workReturn.resume===resumeRevision()?workReturn:null;
  const returned = retained && work.find(({task,exact})=>task.id===retained.target.taskId&&
    task.raw?.runId===retained.target.runId&&task.raw?.coordinatorEpoch===retained.target.epoch&&
    (retained.report||!retained.domain?isButlerHomeWork(task):exact?.domain===retained.domain)&&
    task.raw?.contextAvailable!==false&&task.raw?.contextWithdrawn!==true&&task.raw?.taskProjection!=='source_unavailable'&&task.raw?.sourceCurrentReadRequired!==true);
  // Keep just the original work within its current group, without displacing
  // the actual newest rows or expanding the entire recent list.
  const recent = (items:typeof work, count:number) => {
    const rows=items.slice(0,count);
    if(returned&&items.includes(returned)&&!rows.includes(returned))rows.push(returned);
    return rows;
  };
  const decisions=work.filter(item=>item.needsOwner);
  const shownDecisions=showDecisions?decisions:recent(decisions,2);
  const checking=work.filter(item=>!item.needsOwner&&item.group==='attention');
  const running=work.filter(item=>item.group==='running');
  const results=work.filter(item=>item.group==='ended'&&(item.report||item.saved));
  const ended=work.filter(item=>item.group==='ended'&&!item.report&&!item.saved);
  const shownRunning=recent(running,2), shownResults=allResults?results:recent(results,2);
  const decisionsExpanded=shownDecisions.length===decisions.length, resultsExpanded=shownResults.length===results.length;
  const showChecking=(!!returned&&checking.includes(returned))||(checkingExpanded??(!decisions.length&&!running.length&&!results.length&&!phoneActivity));
  const shownEnded=recent(ended,1), shownChecking=showChecking?recent(checking,2):[];
  const homeNotice=notice||(ready&&retained&&!returned?'首页暂不能定位这轮原工作，可到「全部工作」核对。':'');
  // Present each job once under the human question it answers. Fold only
  // diagnostics; real decisions and the newest original results stay one tap away.
  const visible=[...shownDecisions,...shownRunning,...shownResults,...shownChecking,...shownEnded];
  const revision = JSON.stringify([ready, canOpen, canReadReports, visible.map(({ task, status, detail, report }) => [task.id, status, detail, report?.reportMessageId])]);
  useCollectionMotion(workRoot, 'butler:owner:private', revision, { active: ready, layout: false, enter:!retained||restoredWork.current===retained });
  const empty = ready && !work.length && !hasPreparations && !phoneActivity && !moreWork && !homeNotice;

  useLayoutEffect(() => {
    if (!ready || restored.current || !scroller.current) return;
    scroller.current.scrollTop = savedScroll.current;
    restored.current = true;
  }, [ready]);
  useLayoutEffect(() => {
    const node = scroller.current;
    return () => { if (node && restored.current) homeScroll.current?.(node.scrollTop); };
  }, []);
  // A changing status can reorder or remove a focused task. Restore only
  // a lost focus; never move focus out of the shared composer or another page.
  useLayoutEffect(() => {
    if (!ready || !active) return;
    const original = focused.current;
    if (!original) return;
    const stillFocusable = original.node.isConnected && !original.node.closest('[inert],[hidden]') && !original.node.matches(':disabled');
    if (stillFocusable || document.activeElement !== document.body) return;
    const row = Array.from(workRoot.current?.querySelectorAll<HTMLElement>('[data-task-id]') || []).find(node => node.dataset.taskId === original.id);
    const target = Array.from(row?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || []).find(node => !node.closest('[inert],[hidden]')) || allWorkButton.current;
    focused.current = null;
    if (target && !target.disabled) target.focus({ preventScroll: true });
  }, [revision]);

  useLayoutEffect(()=>{
    if(!ready||!active||!retained||restoredWork.current===retained)return;
    restoredWork.current=retained;
    // A return only positions the current list. Neither a different run nor
    // a later user focus should be treated as the control that opened the work.
    if(document.hidden||document.activeElement!==document.body)return;
    const view=scroller.current;
    const row=returned&&Array.from(workRoot.current?.querySelectorAll<HTMLElement>('[data-task-id]')||[]).find(node=>node.dataset.taskId===returned.task.id);
    const originalReport=!!retained.report&&!!returned?.report&&sameHomeReport(returned.report,retained.report);
    const reportButton=row&&originalReport&&retained.intent==='report'&&row.querySelector<HTMLButtonElement>('.pw-butler-decision-report:not(:disabled)');
    const rowButton=reportButton||(row&&row.querySelector<HTMLButtonElement>('.pw-butler-work-row:not(:disabled)'));
    const target=rowButton||allWorkButton.current;
    if(!view||!target||target.closest('[inert],[hidden]'))return;
    const frame=view.getBoundingClientRect(), bounds=target.getBoundingClientRect(), style=getComputedStyle(view);
    const min=parseFloat(style.scrollPaddingBlockStart)||0;
    const max=Math.max(min,frame.height-bounds.height-(parseFloat(style.scrollPaddingBlockEnd)||0));
    const currentOffset=bounds.top-frame.top, offset=rowButton?retained.offset??currentOffset:currentOffset;
    view.scrollTop+=currentOffset-Math.min(max,Math.max(min,offset));
    target.focus({preventScroll:true});
  },[active,ready,retained,returned]);

  const open=async(item:typeof work[number],trigger:HTMLButtonElement,intent:'row'|'report'='row')=>{
    if(!activeRef.current||!canOpen||flight.current)return;
    const reportTarget=intent==='report'||!item.decision?item.report:null;
    if(intent==='report'&&(!reportTarget||!canReadReports))return;
    const unavailable=!!reportTarget&&!!unavailableReport&&sameHomeReport(reportTarget,unavailableReport);
    const report=canReadReports&&(intent==='report'||!unavailable)?reportTarget:null;
    const binding=reportTarget||item.decision||item.saved||item.exact;
    if(!binding){onOpen(item.task);return}
    const id=item.task.id,{runId,epoch}=binding,documentEpoch=bridge.getDocumentEpoch();
    const frame=scroller.current?.getBoundingClientRect();
    const offset=frame?trigger.getBoundingClientRect().top-frame.top:undefined;
    const startResume=resumeRevision();
    const originalDocument=()=>!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&resumeRevision()===startResume;
    const current=()=>live.current&&activeRef.current&&originalDocument();
    const originalRow=()=>{
      const native=bridge.getState().state;
      const rows=reportTarget||item.decision?native.ownerWork:native.tasks;
      return Array.isArray(rows)?rows.find((row:ButlerHomeTask)=>row?.id===id&&row.raw?.runId===runId&&row.raw?.coordinatorEpoch===epoch&&
        (reportTarget?sameHomeReport(reportedHomeWork(row),reportTarget):item.saved?!!originalSavedResult(row):
          item.decision?row.status===item.task.status&&workCollectionPresentation(row).handling?.ownerDecision:
            !!item.exact&&homeWorkIdentity(row)?.domain===item.exact.domain)):undefined;
    };
    const same=()=>!!originalRow();
    let ownsWindow=false,handedOff=false;
    flight.current=true;setOpening({id,intent});setNotice('');
    try{
      if(!current()||!same())throw new Error('原工作已变化，请核对当前记录。');
      const receipt=await bridge.command('tasks.open',{id,requestNavigation:false,expectedRunId:runId,expectedEpoch:epoch,...(epoch===0&&item.exact?.direct?{expectedExternalKind:item.exact.direct}:{})});
      if(receipt?.taskId!==id||receipt.readOnly!==true||receipt.executionAuthorized!==false)throw new Error('尚未核对到原工作，没有打开其他任务。');
      ownsWindow=true;
      if(!current())return;
      if(!same())throw new Error('原工作已变化，请核对当前记录。');
      const verified=report?await verifyHomeReport(report):undefined;
      if(!current())return;
      const latest=originalRow();
      if(!latest)throw new Error('原工作或汇报已变化，没有改读其他轮次。');
      if(verified&&unavailable)setUnavailableReport(null);
      focused.current=null;setWorkReturn(null);
      onOpen(latest,true,verified,()=>originalDocument()&&same(),()=>{
        if(!live.current||!originalDocument())return false;
        // The same work can move groups after the owner acts. Keep its current
        // projection in view, not the approval or progress label that opened it.
        setWorkReturn({target:{taskId:id,runId,epoch},report:verified?.target,domain:item.exact?.domain,intent,offset,documentEpoch,resume:startResume});return true;
      });handedOff=true;
    }catch(error){
      if(current()){
        const reason=error instanceof Error?error.message:'原工作暂不可读取。';
        if(report&&ownsWindow&&same()){setUnavailableReport(report);setNotice(item.decision?`${reason} 仍可打开原工作处理待办。`:`${reason} 可点「查看原工作」核对同一轮记录。`)}
        else setNotice(reason);
      }
    }
    finally{
      // Release only our own provisional read if the current Home gesture
      // failed; do not close a view that another route has since entered.
      if((item.report||item.saved||item.exact)&&ownsWindow&&!handedOff&&current()&&(bridge.getState().view as {taskId?:string}|undefined)?.taskId===id)
        await bridge.command('tasks.close',{}).catch(()=>{});
      flight.current=false;if(live.current)setOpening(null);
    }
  };

  const renderTask = (item:typeof work[number]) => {
    const { task, decision, attention: needsAttention, group, status, detail, busy, action, completed }=item;
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError
      : needsAttention ? IconInfo : completed ? IconCheckCircle : IconClock;
    const date = new Date(updatedAt(task)), hasDate = updatedAt(task) > 0 && Number.isFinite(date.getTime());
    const alongside=!!(decision&&item.report&&canReadReports);
    const unavailable=!!item.report&&!!unavailableReport&&sameHomeReport(item.report,unavailableReport);
    const readAction=decision?(alongside?action:'查看原工作'):item.report?(canReadReports&&!unavailable?'阅读原汇报':'查看原工作'):item.saved?'查看已保存成果':needsAttention?'查看状态':action;
    const rowOpening=opening?.id===task.id&&opening.intent==='row',reportOpening=opening?.id===task.id&&opening.intent==='report';
    return <li key={task.id} data-opening={rowOpening||undefined} data-decision-report={alongside||undefined} data-task-id={task.id} data-motion-key={task.id} data-task-state={task.status} data-attention={needsAttention || undefined}>
      <Button variant="ghost" className="pw-butler-work-row" disabled={!canOpen} aria-disabled={!canOpen||!!opening} aria-busy={rowOpening} aria-label={`${readAction}：${task.goal || '委派工作'}`} aria-description={[item.exact&&domainLabel[item.exact.domain],status, detail].filter(Boolean).join('，')} onClick={event=>void open(item,event.currentTarget)}>
        <span className="pw-butler-work-mark" data-attention={needsAttention || undefined} aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <StatusIcon size={18} />}</span>
        <span className="pw-butler-work-copy">
          <strong dir="auto">{task.goal || '委派工作'}</strong>
          <span className="pw-butler-work-meta">{item.exact&&domainLabel[item.exact.domain]&&<span className="pw-butler-work-domain">{domainLabel[item.exact.domain]}</span>}<MotionFeedback as="span" identity={status} initial={false}>{status}</MotionFeedback>{group === 'ended' && hasDate && <time dateTime={date.toISOString()}>{date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}</span>
          {group !== 'ended' && detail && <span className="pw-butler-work-detail" dir="auto">{detail}</span>}
        </span>
        <span className="pw-butler-work-next" aria-hidden="true">{rowOpening?'核对中':readAction}<IconChevronRight size={16} /></span>
      </Button>
      {alongside&&<Button variant="ghost" size="small" className="pw-butler-all-work pw-butler-decision-report" disabled={!canOpen}
        aria-disabled={!canOpen||!!opening} aria-busy={reportOpening} aria-label={`阅读已保存的汇报：${task.goal || '委派工作'}`} aria-description="只阅读，不会替你处理这项决定。"
        onClick={event=>void open(item,event.currentTarget,'report')}>{reportOpening?'正在核对汇报…':unavailable?'重新读取原汇报':'先看已保存的汇报'}</Button>}
    </li>;
  };
  // The editor never moves. An optional capability is not the next step ahead
  // of work the owner already has, except for an action on the current draft.
  const workFirst = !firstHostSave && !stagingLocal && (work.length > 0 || !!phoneActivity);
  const showLocal = stagingLocal || empty && !!onTryLocal;
  const showSetup = ready && !modelReady && !localDisplayDraft && !stagingLocal;
  const showProjectImport = empty && active && !localDisplayDraft && !stagingLocal && !firstHostSave && !!onImportProject;
  const nextHint=showLocal?(stagingLocal?'正在填入问题…':'只填入问题，按发送才读取。不用模型。'):
    firstHostSave?.hint||(!workFirst?'可以先写好草稿，连接后再发送。':'');
  const nextSteps=(showLocal||showSetup||showProjectImport)&&<div key="butler-next-steps" className="pw-butler-model-setup pw-butler-next-steps" data-quiet-setup={showLocal||workFirst||undefined}>
    {showLocal&&<Button className="pw-butler-try" variant="ghost" leadingIcon={IconPhone} trailingIcon={IconChevronRight} disabled={stagingLocal} aria-describedby={nextNote} onClick={onTryLocal}>查这台手机的屏幕信息</Button>}
    {showProjectImport&&<Button variant="ghost" leadingIcon={IconFolder} onClick={onImportProject}>导入本机工程</Button>}
    {showSetup&&modelSetup}
    {showSetup&&firstHostSave&&<Button variant="ghost" size="small" disabled={firstHostSave.disabled} aria-busy={firstHostSave.busy||undefined}
      aria-describedby={nextNote} onClick={firstHostSave.save}>{firstHostSave.busy?'正在保存原话…':'仅保存到本机'}</Button>}
    {nextHint&&<p id={nextNote} role={stagingLocal?'status':undefined}>{nextHint}</p>}
  </div>;

  return <main className="pw-chat-workspace pw-butler-workspace" aria-label="管家" data-empty={empty || undefined}>
    <div ref={scroller} className="pw-butler-scroll" onScroll={event => { if (restored.current) homeScroll.current?.(event.currentTarget.scrollTop); }}>
      <div className="pw-butler-content">
        <section className="pw-butler-intake" aria-label="交办">
          <header className="pw-butler-welcome"><h2>想交给我什么事？</h2></header>
          {composer}
        </section>
        {!workFirst&&nextSteps}
        {(!ready || work.length > 0 || phoneActivity || moreWork || homeNotice) && <section ref={workRoot} className="pw-butler-work" aria-labelledby={workHeading} aria-busy={!ready}
          onFocusCapture={event => { const node = event.target as HTMLElement, id = node.closest<HTMLElement>('[data-task-id]')?.dataset.taskId; focused.current = id ? { node, id } : null; }}
          onBlurCapture={event => { const node = event.target as HTMLElement; if (event.relatedTarget instanceof Node ? !event.currentTarget.contains(event.relatedTarget) : node.isConnected && !node.closest('[inert],[hidden]') && !node.matches(':disabled')) focused.current = null; }}>
          <header className="pw-butler-work-heading"><h2 id={workHeading}>我的工作</h2>{ready && <Button ref={allWorkButton} variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('all')}>全部工作</Button>}</header>
          {!ready ? <p className="pw-butler-reading" role="status"><ProgressCircle isIndeterminate size="sm" aria-label="正在恢复工作记录" /><span>正在恢复工作记录…</span></p> : <>
            {homeNotice&&<p className="pw-butler-work-note" role="status">{homeNotice}</p>}
            {decisions.length>0&&<section className="pw-butler-work-group pw-butler-work-inbox" aria-label="等你处理">
              <header className="pw-butler-group-heading"><h3>等你处理</h3><span className="pw-butler-group-count" aria-label={`眼前的 ${decisions.length} 项待处理工作`}>{decisions.length}</span></header>
              <ol id={decisionList} className="pw-butler-work-list">{shownDecisions.map(renderTask)}</ol>
              {decisions.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-controls={decisionList} aria-expanded={decisionsExpanded} onClick={()=>{setWorkReturn(null);setShowDecisions(!decisionsExpanded)}}>{decisionsExpanded?'收起待处理工作':`再看 ${decisions.length-shownDecisions.length} 项`}</Button>}
            </section>}
            {(shownRunning.length>0||phoneActivity)&&<section className="pw-butler-work-group" aria-label="正在跟进">
              <header className="pw-butler-group-heading"><h3>正在跟进</h3>{running.length>shownRunning.length&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('running')}>查看全部</Button>}</header>
              {phoneActivity}
              {shownRunning.length>0&&<ol className="pw-butler-work-list">{shownRunning.map(renderTask)}</ol>}
            </section>}
            {shownResults.length>0&&<section className="pw-butler-work-group" aria-label="成果">
              <header className="pw-butler-group-heading"><h3>成果</h3>{moreWork&&
                <Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('ended')}>找更早的成果</Button>}</header>
              <ol id={returnedList} className="pw-butler-work-list">{shownResults.map(renderTask)}</ol>
              {results.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-expanded={resultsExpanded} aria-controls={returnedList} onClick={()=>{setWorkReturn(null);setAllResults(!resultsExpanded)}}>{resultsExpanded?'收起成果':`再看 ${results.length-shownResults.length} 项成果`}</Button>}
            </section>}
            {(checking.length>0||shownEnded.length>0||moreWork)&&<div className="pw-butler-work-group pw-butler-work-secondary">
              {checking.length>0&&<>
                <Button variant="ghost" className="pw-butler-all-work pw-butler-check-toggle" aria-expanded={showChecking} aria-controls={checkingList} trailingIcon={IconChevronDown} onClick={()=>{setWorkReturn(null);setCheckingExpanded(!showChecking)}}>暂停或待核对 · {checking.length}</Button>
                <div id={checkingList} hidden={!showChecking}>
                  <ol className="pw-butler-work-list">{shownChecking.map(renderTask)}</ol>
                  {checking.length>shownChecking.length&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('attention')}>查看待处理工作</Button>}
                </div>
              </>}
              {shownEnded.length>0&&<ol className="pw-butler-work-list" aria-label="最近结束的其他工作">{shownEnded.map(renderTask)}</ol>}
              {moreWork&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('all')}>到目录查找更早的工作</Button>}
            </div>}
            {!!work.length && !canOpen && <p className="pw-butler-work-note" role="status">当前暂时无法打开原任务记录。</p>}
          </>}
        </section>}
        {workFirst&&nextSteps}
        {preparations?.(visible.map(item=>item.task))}
      </div>
    </div>
  </main>;
}

export default ButlerHome;
