import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
import {bridge} from './bridge';
import {reportedHomeWork,sameHomeReport,verifyHomeReport,type VerifiedHomeReport} from './ButlerReturnedReport';
import { IconCheckCircle, IconChevronDown, IconChevronRight, IconClock, IconDevices, IconError, IconFileText, IconFolder, IconInfo, IconMonitor, IconPauseCircle, IconPhone } from './icons';
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
  onReadLocalFiles?: () => void;
  onReadPairedWindowsFile?: (trigger: HTMLButtonElement) => void;
  onOpenDeviceInbox?: (trigger: HTMLButtonElement) => void;
  onImportProject?: () => void;
  stagingLocal?: boolean;
  modelSetup: ReactNode;
  firstDraftConnect?: boolean;
  firstPhoneOriginal?: boolean;
  firstHostSave?: { hint: string; disabled: boolean; busy: boolean; save: () => void };
  composer: ReactNode;
  preparations?: (visible: ButlerHomeTask[]) => ReactNode;
  moreWork?: boolean;
  active?: boolean;
  phoneActivity?: ReactNode;
  phoneAttention?: { key: string; row: ReactNode }[];
  hasPreparations?: boolean;
  tasks: ButlerHomeTask[];
  canOpen: boolean;
  onOpen: (task: ButlerHomeTask, nativeAlreadyOpen?: boolean, report?:VerifiedHomeReport, stillOriginal?:()=>boolean, returnWorkFocus?:(confirmedResultRevision?:number)=>boolean) => void;
  canReadReports?:boolean;
  onAllWork: (filter?: WorkCollectionFilter) => void;
  onOpenSchedule?: (trigger: HTMLButtonElement) => void;
  onScrollTop?: (top: number) => void;
  initialScrollTop?: number;
};

const updatedAt = (task: ButlerHomeTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;
const resumeRevision = () => Number((bridge.getState().view as {resumeRevision?:number}|undefined)?.resumeRevision||0);
export function needsOwnerOnHome(task: ButlerHomeTask, view = workCollectionPresentation(task), exact = homeWorkIdentity(task)) {
  // The parent can be waiting for its one-off team, not waiting for an owner verdict.
  if (view.handling?.originalOneOffTeamReview)
    return !view.reconciliation && (view.pendingApproval || ['question', 'report'].includes(view.handling.originalOneOffTeamReview));
  return !view.reconciliation && (view.pendingApproval || view.handling?.needsAttention === true ||
    !!exact && ['awaiting_user','waiting_user','waiting_permission','awaiting_permission','waiting_approval','awaiting_approval','needs_confirmation'].includes(task.status));
}
/** A native pending result in the already-loaded private Work collection, not
 * a report, child decision or an executable review handle. */
export function homePendingResultReview(task:ButlerHomeTask|undefined) {
  const raw=task?.raw, source=raw?.ownerWork;
  if(!task||!raw||task.status!=='needs_confirmation'||raw.status!==task.status||
     raw.kind!=='assistant'||raw.executionMode!=='capabilities'||raw.executionRole!=='owner_work'||
     raw.contextAvailable!==true||raw.contextWithdrawn===true||raw.taskCoreReadLimited===true||
     raw.cancelRequested!==false||raw.hasUnresolvedOperations!==false||raw.historyHasUnconfirmedOperations===true||
     raw.teamRunId!=null||raw.teamAssignment!=null||raw.memberId!=null||raw.ownerPhoneControl!=null||
     !Array.isArray(raw.ownerWorkTeams)||raw.ownerWorkTeams.length||raw.ownerWorkTeamsUnavailable===true||
     source?.format!=='pocketworld.owner-work.v1'||source.workTaskId!==task.id||source.phase!=='ended'||
     !raw.conversationId||source.conversationId!==raw.conversationId) return;
  const exact=homeWorkIdentity(task), view=workCollectionPresentation(task), digest=raw.resultReviewDigest, revision=raw.resultRevision;
  if(exact?.domain!=='butler'||view.reconciliation||view.pendingApproval||!view.handling?.ownerDecision||
     view.handling.waitingForChildren||typeof digest!=='string'||!/^[a-f0-9]{64}$/.test(digest)||
     !Number.isSafeInteger(revision)||revision<1)return;
  const {runId,epoch}=exact;
  return {taskId:task.id,runId,epoch,digest,revision,key:JSON.stringify([task.id,runId,epoch,digest,revision])};
}
type WorkReturn = {
  target:Pick<VerifiedHomeReport['target'],'taskId'|'runId'|'epoch'>;
  report?:VerifiedHomeReport['target']; domain?:HomeDomain; intent:'row'|'report'; offset?:number;
  documentEpoch:number; resume:number; confirmedResultRevision?:number;
};

/** A private original may have a saved result without a butler message.
 * This is an entry to its existing result surface, never a write receipt. */
function originalSavedResult(task: ButlerHomeTask, view = workCollectionPresentation(task)) {
  const raw=task.raw, runId=raw?.runId, epoch=raw?.coordinatorEpoch;
  const identity=homeWorkIdentity(task), original=raw?.originalLocalPhoneSavedResult;
  // The cold list only has the native header. Do not treat completed status or a task headline as an answer.
  const savedPhone=identity?.domain==='phone'&&raw?.nativePrivatePhoneResultSource===true&&raw.status===task.status&&
    raw.contextWithdrawn!==true&&original?.taskId===task.id&&original.runId===runId&&original.epoch===epoch&&
    Number.isSafeInteger(raw.resultRevision)&&raw.resultRevision>0&&original.resultRevision===raw.resultRevision;
  const nativeLabel=savedPhone?'原任务已记录结果':identity?homeDomainResult(task,identity.domain):'';
  const label=view.handling?.resultAvailable===true?'成果已保存':nativeLabel;
  return view.completed && !!label && !reportedHomeWork(task, view.handling) &&
    typeof runId === 'string' && runId.trim() && Number.isSafeInteger(epoch) && (epoch > 0 || epoch === 0 && !!identity?.direct) ? {runId, epoch, label} : null;
}

/** A non-completed run may still have a real report. Move only a settled failure
 * whose attention classification is for optional continuation, not an unresolved decision. */
function partialFailureReport(task: ButlerHomeTask, view = workCollectionPresentation(task), exact = homeWorkIdentity(task)) {
  const raw=task.raw, work=raw?.ownerWork, handling=view.handling;
  if(task.status!=='failed'||view.group!=='attention'||view.reconciliation||view.pendingApproval||!handling||
    handling.needsAttention||handling.waitingForChildren||handling.teamsUnavailable||
    exact?.domain!=='butler'||raw?.kind!=='assistant'||raw.executionMode!=='capabilities'||raw.executionRole!=='owner_work'||
    raw.status!==task.status||raw.contextAvailable!==true||raw.contextWithdrawn===true||raw.taskCoreReadLimited===true||
    raw.cancelRequested!==false||raw.hasUnresolvedOperations!==false||raw.controls?.canContinue!==true||
    work?.format!=='pocketworld.owner-work.v1'||work.workTaskId!==task.id||work.phase!=='ended'||work.conversationId!==raw.conversationId)return null;
  return reportedHomeWork(task,handling);
}

/** Adapt the retained Affect AssistantHome / Composer composition around the
 * real shared input and BoardUI task rows. This view reads
 * the native private-work projection; only the original task owns decisions. */
export function ButlerHome({ ready, modelReady, localDisplayDraft=false, onTryLocal, onReadLocalFiles, onReadPairedWindowsFile, onOpenDeviceInbox, onImportProject, stagingLocal=false, modelSetup, firstDraftConnect=false, firstPhoneOriginal=false, firstHostSave, composer, preparations, phoneActivity, phoneAttention=[], hasPreparations = false, tasks, canOpen, onOpen, onAllWork, onOpenSchedule, onScrollTop, initialScrollTop = 0, moreWork = false, active = true, canReadReports=false }: ButlerHomeProps) {
  const scroller = useRef<HTMLDivElement>(null), workRoot = useRef<HTMLElement>(null);
  const allWorkButton = useRef<HTMLButtonElement>(null), decisionHeading=useRef<HTMLHeadingElement>(null);
  const focused = useRef<{ node: HTMLElement; id: string } | null>(null);
  const savedScroll = useRef(Number.isFinite(initialScrollTop) ? Math.max(0, initialScrollTop) : 0);
  const restored = useRef(false), homeScroll = useRef(onScrollTop);
  homeScroll.current = onScrollTop;
  const workHeading = useId(), decisionList = useId(), returnedList = useId(), checkingList = useId(), nextNote = useId(), localNote = useId();
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
      const needsOwner = !!decision || needsOwnerOnHome(task,presentation,exact);
      const partialReport=canReadReports&&!needsOwner?partialFailureReport(task,presentation,exact):null;
      // The butler may have filed its report before the owner accepts the work.
      // Keep that decision in place; the saved report is something to read, not a verdict.
      const report=presentation.group==='ended'||decision?reportedHomeWork(task,handling):partialReport;
      return { task, decision, needsOwner, report, partialReport, saved, exact, attention, status, detail: detail === status || detail === task.goal ? '' : detail,
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
  const decisionCount=decisions.length+phoneAttention.length, hasPhone=!!phoneActivity||phoneAttention.length>0;
  const shownDecisions=showDecisions?decisions:recent(decisions,2);
  const checking=work.filter(item=>!item.needsOwner&&item.group==='attention'&&!item.partialReport);
  const running=work.filter(item=>item.group==='running');
  const results=work.filter(item=>item.partialReport||item.group==='ended'&&(item.report||item.saved));
  const ended=work.filter(item=>item.group==='ended'&&!item.report&&!item.saved);
  const hasEndedResults=results.some(item=>item.group==='ended');
  const shownRunning=recent(running,2), shownResults=allResults?results:recent(results,2);
  const decisionsExpanded=shownDecisions.length===decisions.length, resultsExpanded=shownResults.length===results.length;
  const showChecking=(!!returned&&checking.includes(returned))||(checkingExpanded??(!decisionCount&&!running.length&&!hasEndedResults&&!hasPhone));
  const shownEnded=recent(ended,1), shownChecking=showChecking?recent(checking,2):[];
  const homeNotice=notice||(ready&&retained&&!returned?'首页暂不能定位这轮原工作，可到「全部工作」核对。':'');
  // Present each job once under the human question it answers. Fold only
  // diagnostics; real decisions and the newest original results stay one tap away.
  const visible=[...shownDecisions,...shownRunning,...shownResults,...shownChecking,...shownEnded];
  const revision = JSON.stringify([ready, canOpen, canReadReports, phoneAttention.map(item=>item.key), visible.map(({ task, status, detail, report }) => [task.id, status, detail, report?.reportMessageId])]);
  useCollectionMotion(workRoot, 'butler:owner:private', revision, { active: ready, layout: false, enter:!retained||restoredWork.current===retained });
  const hasWorkOverview = work.length > 0 || hasPhone || moreWork || !!homeNotice;
  const scheduleEntry = ready ? onOpenSchedule : undefined;
  const empty = ready && !hasWorkOverview && !hasPreparations;

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
    const rows=Array.from(workRoot.current?.querySelectorAll<HTMLElement>('[data-task-id]')||[]);
    const row=returned&&rows.find(node=>node.dataset.taskId===returned.task.id);
    const originalReport=!!retained.report&&!!returned?.report&&sameHomeReport(returned.report,retained.report);
    const reportButton=row&&originalReport&&retained.intent==='report'&&row.querySelector<HTMLButtonElement>('.pw-butler-decision-report:not(:disabled)');
    const rowButton=reportButton||(row&&row.querySelector<HTMLButtonElement>('.pw-butler-work-row:not(:disabled)'));
    const target=rowButton||allWorkButton.current;
    if(!view||!target||target.closest('[inert],[hidden]'))return;
    const frame=view.getBoundingClientRect(), bounds=target.getBoundingClientRect(), style=getComputedStyle(view);
    const min=parseFloat(style.scrollPaddingBlockStart)||0;
    const bottom=frame.height-(parseFloat(style.scrollPaddingBlockEnd)||0), max=Math.max(min,bottom-bounds.height);
    const currentOffset=bounds.top-frame.top, offset=rowButton?retained.offset??currentOffset:currentOffset;
    const originalDelta=currentOffset-Math.min(max,Math.max(min,offset)), heading=decisionHeading.current;
    if(rowButton&&heading&&!heading.closest('[inert],[hidden],[aria-hidden="true"]')&&!retained.report&&
       retained.confirmedResultRevision!==undefined&&returned?.task.status==='completed'&&
       returned.task.raw?.resultRevision===retained.confirmedResultRevision){
      const native=bridge.getState(), place=native.view as {taskId?:string|null;section?:string|null}|undefined;
      if(place?.taskId===null&&place.section===null&&['tasks.open','tasks.review','tasks.confirm'].every(action=>native.capabilities.commands.includes(action))){
        const group=heading.closest('.pw-butler-work-inbox'), loaded=native.state.ownerWork;
        const waiting=shownDecisions.flatMap(({task})=>{
          if(task.id===retained.target.taskId||task.id===native.state.activeTaskId||!Array.isArray(loaded))return [];
          const proof=homePendingResultReview(task), exact=loaded.filter(value=>value?.id===task.id);
          if(!proof||exact.length!==1||homePendingResultReview(exact[0])?.key!==proof.key)return [];
          const next=rows.find(node=>node.dataset.taskId===task.id&&group?.contains(node))
            ?.querySelector<HTMLButtonElement>('.pw-butler-work-row:not(:disabled):not([aria-disabled="true"])');
          return next&&!next.closest('[inert],[hidden],[aria-hidden="true"]')?[next.getBoundingClientRect()]:[];
        });
        const headingBounds=heading.getBoundingClientRect(), top=view.scrollTop, limit=Math.max(0,view.scrollHeight-view.clientHeight);
        const clamp=(value:number)=>Math.min(limit,Math.max(0,value));
        const original=clamp(top+originalDelta), section=clamp(top+headingBounds.top-frame.top-min);
        const startAt=(rect:DOMRect,scroll:number)=>rect.top-frame.top+top-scroll;
        const visibleAt=(rect:DOMRect,scroll:number)=>rect.height>0&&startAt(rect,scroll)>=min-1&&startAt(rect,scroll)+rect.height<=bottom+1;
        const alreadyVisible=waiting.some(rect=>rect.height>0&&startAt(rect,original)<bottom&&startAt(rect,original)+rect.height>min);
        // Keep A's usual focus if any part of a real waiting row would already be visible.
        // Otherwise place the existing heading only when it really reveals a decision.
        if(!alreadyVisible&&visibleAt(headingBounds,section)&&waiting.some(rect=>visibleAt(rect,section))){
          view.scrollTop=section;heading.focus({preventScroll:true});return;
        }
      }
    }
    view.scrollTop+=originalDelta;
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
        (reportTarget?sameHomeReport(item.partialReport?partialFailureReport(row):reportedHomeWork(row),reportTarget):item.saved?!!originalSavedResult(row):
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
      onOpen(latest,true,verified,()=>originalDocument()&&same(),confirmedResultRevision=>{
        if(!live.current||!originalDocument())return false;
        // The same work can move groups after the owner acts. Keep its current
        // projection in view, not the approval or progress label that opened it.
        setWorkReturn({target:{taskId:id,runId,epoch},report:verified?.target,domain:item.exact?.domain,intent,offset,documentEpoch,resume:startResume,confirmedResultRevision});return true;
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
  const workFirst = !firstHostSave && !stagingLocal && (work.length > 0 || hasPhone);
  const firstUse = firstDraftConnect && empty;
  const showLocal = stagingLocal || empty && !!onTryLocal;
  const showSetup = ready && !modelReady && !localDisplayDraft && !stagingLocal && !firstPhoneOriginal;
  const showFiles = ready && active && !localDisplayDraft && !stagingLocal && !!onReadLocalFiles;
  const showPairedFile = ready && active && !localDisplayDraft && !stagingLocal && !!onReadPairedWindowsFile;
  const showDeviceInbox = ready && active && !localDisplayDraft && !stagingLocal && !!onOpenDeviceInbox;
  const showProjectImport = empty && active && !localDisplayDraft && !stagingLocal && !firstHostSave && !!onImportProject;
  const localHint=showLocal?(stagingLocal?'正在填入问题…':'只填入问题，按发送才读取。不用模型。'):'';
  const nextHint=(!firstUse&&localHint)||
    firstHostSave?.hint||(showSetup&&!workFirst?'可以先写好草稿，连接后再发送。':'');
  const localExample=showLocal&&<Button className="pw-butler-try" variant="ghost" leadingIcon={IconPhone} trailingIcon={IconChevronRight} disabled={stagingLocal} aria-describedby={firstUse?localNote:nextNote} onClick={onTryLocal}>查这台手机的屏幕信息</Button>;
  const localImport=showProjectImport&&<Button variant="ghost" leadingIcon={IconFolder} onClick={onImportProject}>导入本机工程</Button>;
  const localFiles=showFiles&&<Button variant="ghost" size="small" leadingIcon={IconFileText} onClick={onReadLocalFiles}>本机文件与草稿</Button>;
  const pairedFile = showPairedFile && <Button variant="ghost" size="small" leadingIcon={IconMonitor}
    data-workspace-entry="home-windows-file" onClick={event => onReadPairedWindowsFile?.(event.currentTarget)}>从配对电脑读取文件</Button>;
  const deviceInbox = showDeviceInbox && <Button variant="ghost" size="small" leadingIcon={IconDevices}
    data-workspace-entry="home-device-inbox" onClick={event => onOpenDeviceInbox?.(event.currentTarget)}>查看设备来单</Button>;
  const localTools = showLocal || showProjectImport || showFiles;
  const scheduleAction=scheduleEntry&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight}
    data-workspace-entry="home-schedule" disabled={!active} onClick={event=>scheduleEntry(event.currentTarget)}>提醒与目标</Button>;
  const nextSteps=(showPairedFile||showDeviceInbox||localTools||showSetup&&!!(modelSetup||firstHostSave)||!!nextHint||firstUse&&!!scheduleAction)&&<div key="butler-next-steps" className="pw-butler-model-setup pw-butler-next-steps" data-quiet-setup={showLocal||workFirst||undefined}>
    {!firstUse&&localExample}
    {!firstUse&&localImport}
    {showSetup&&modelSetup}
    {showSetup&&firstHostSave&&<Button variant="ghost" size="small" disabled={firstHostSave.disabled} aria-busy={firstHostSave.busy||undefined}
      aria-describedby={nextNote} onClick={firstHostSave.save}>{firstHostSave.busy?'正在保存原话…':'仅保存到本机'}</Button>}
    {!firstUse&&localFiles}
    {nextHint&&<p id={nextNote} role={stagingLocal?'status':undefined}>{nextHint}</p>}
    {firstUse&&(localTools||scheduleAction)&&<div className="pw-butler-first-use-utilities">
      {localTools&&<Accordion className="pw-butler-first-use-local"><AccordionItem headingLevel={2} title="先用本机功能">
        <div className="pw-butler-first-use-actions">
          {localExample}{localHint&&<p id={localNote}>{localHint}</p>}{localImport}{localFiles}
        </div>
      </AccordionItem></Accordion>}
      {scheduleAction}
    </div>}
    {pairedFile}{deviceInbox}
  </div>;

  return <main className="pw-chat-workspace pw-butler-workspace" aria-label="管家" data-empty={empty || undefined}>
    <div ref={scroller} className="pw-butler-scroll" onScroll={event => { if (restored.current) homeScroll.current?.(event.currentTarget.scrollTop); }}>
      <div className="pw-butler-content">
        <section className="pw-butler-intake" aria-label="交办">
          <header className="pw-butler-welcome"><h2>想交给我什么事？</h2></header>
          {composer}
        </section>
        {!workFirst&&nextSteps}
        {(!ready || hasWorkOverview || !firstUse && scheduleEntry) && <section ref={workRoot} className="pw-butler-work" aria-labelledby={workHeading} aria-busy={!ready}
          onFocusCapture={event => { const node = event.target as HTMLElement, id = node.closest<HTMLElement>('[data-task-id]')?.dataset.taskId; focused.current = id ? { node, id } : null; }}
          onBlurCapture={event => { const node = event.target as HTMLElement; if (event.relatedTarget instanceof Node ? !event.currentTarget.contains(event.relatedTarget) : node.isConnected && !node.closest('[inert],[hidden]') && !node.matches(':disabled')) focused.current = null; }}>
          <header className="pw-butler-work-heading"><h2 id={workHeading}>我的工作</h2>
            {scheduleAction}
            {ready && hasWorkOverview && <Button ref={allWorkButton} variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('all')}>全部工作</Button>}
          </header>
          {!ready ? <p className="pw-butler-reading" role="status"><ProgressCircle isIndeterminate size="sm" aria-label="正在恢复工作记录" /><span>正在恢复工作记录…</span></p> : <>
            {homeNotice&&<p className="pw-butler-work-note" role="status">{homeNotice}</p>}
            {decisionCount>0&&<section className="pw-butler-work-group pw-butler-work-inbox" aria-label="等你处理">
              <header className="pw-butler-group-heading"><h3 ref={decisionHeading} tabIndex={-1}>等你处理</h3><span className="pw-butler-group-count" aria-label={`眼前的 ${decisionCount} 项待处理工作`}>{decisionCount}</span></header>
              {phoneAttention.map(item=>item.row)}
              {shownDecisions.length>0&&<ol id={decisionList} className="pw-butler-work-list">{shownDecisions.map(renderTask)}</ol>}
              {decisions.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-controls={decisionList} aria-expanded={decisionsExpanded} onClick={()=>{setWorkReturn(null);setShowDecisions(!decisionsExpanded)}}>{decisionsExpanded?'收起待处理工作':`再看 ${decisions.length-shownDecisions.length} 项`}</Button>}
            </section>}
            {(shownRunning.length>0||phoneActivity)&&<section className="pw-butler-work-group" aria-label="正在跟进">
              <header className="pw-butler-group-heading"><h3>正在跟进</h3>{running.length>shownRunning.length&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('running')}>查看全部</Button>}</header>
              {phoneActivity}
              {shownRunning.length>0&&<ol className="pw-butler-work-list">{shownRunning.map(renderTask)}</ol>}
            </section>}
            {shownResults.length>0&&<section className="pw-butler-work-group" aria-label="成果">
              <header className="pw-butler-group-heading"><h3>成果</h3>{moreWork&&hasEndedResults&&
                <Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('ended')}>找更早的成果</Button>}</header>
              <ol id={returnedList} className="pw-butler-work-list">{shownResults.map(renderTask)}</ol>
              {results.length>2&&<Button variant="ghost" className="pw-butler-all-work" aria-expanded={resultsExpanded} aria-controls={returnedList} onClick={()=>{setWorkReturn(null);setAllResults(!resultsExpanded)}}>{resultsExpanded?'收起成果':`再看 ${results.length-shownResults.length} 项成果`}</Button>}
            </section>}
            {(checking.length>0||shownEnded.length>0)&&<div className="pw-butler-work-group pw-butler-work-secondary">
              {checking.length>0&&<>
                <Button variant="ghost" className="pw-butler-all-work pw-butler-check-toggle" aria-expanded={showChecking} aria-controls={checkingList} trailingIcon={IconChevronDown} onClick={()=>{setWorkReturn(null);setCheckingExpanded(!showChecking)}}>暂停或待核对 · {checking.length}</Button>
                <div id={checkingList} hidden={!showChecking}>
                  <ol className="pw-butler-work-list">{shownChecking.map(renderTask)}</ol>
                  {checking.length>shownChecking.length&&<Button variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={()=>onAllWork('attention')}>查看待处理工作</Button>}
                </div>
              </>}
              {shownEnded.length>0&&<ol className="pw-butler-work-list" aria-label="最近结束的其他工作">{shownEnded.map(renderTask)}</ol>}
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
