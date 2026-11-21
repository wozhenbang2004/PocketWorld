import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { IconChevronDown, IconChevronRight, IconClock, IconError, IconInfo, IconPauseCircle } from './icons';
import { Button } from './forms-library';
import { ProgressCircle } from './library';
import { getRecentTaskProgress } from './task-presentation';
import { phoneConversationId } from './phone-start';
import { MotionDisclosure, MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { ownerWorkPresentation } from './owner-work-presentation';
import { ConversationTeamDecisions, type OpenTeamDecision } from './ConversationTeamDecisions';
import { workCollectionPresentation } from './work-collection';
import { ownerWorkIdentity } from './conversation-work';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };
type ConversationMessage = { role: string; id?: string; sourceRevision?: number; quoteAvailable?: boolean };

/** Only the native admission names the new Work. A chat send receipt names input, not a handoff. */
function latestRequestWork(tasks: Task[], pinned: Task | undefined, messages: readonly ConversationMessage[] | undefined, conversation: string) {
  if (!conversation || !messages) return;
  const request = messages.filter(message => message.role === 'user').at(-1);
  if (!request?.id || request.quoteAvailable !== true || typeof request.sourceRevision !== 'number' ||
      !Number.isSafeInteger(request.sourceRevision) || request.sourceRevision < 1 || messages.filter(message => message.id === request.id).length !== 1) return;
  const matchesRequest = (task: Task) => {
    const raw = task.raw, work = raw?.ownerWork, refs = work?.sourceMessageRefs;
    if (!raw || !ownerWorkIdentity(task) || raw.kind !== 'assistant' || raw.executionMode !== 'capabilities' || raw.status !== task.status ||
        raw.origin != null || raw.parentTaskId != null || raw.developmentContext != null || raw.ownerDeviceWork != null || raw.ownerPhoneControl != null ||
        raw.contextAvailable !== true || raw.contextWithdrawn === true || raw.sourceCurrentReadRequired === true || raw.metadataLimited === true ||
        raw.taskProjection === 'source_unavailable' || raw.conversationId !== conversation || work?.format !== 'pocketworld.owner-work.v1' ||
        work.workTaskId !== task.id || work.conversationId !== conversation || !Array.isArray(refs) || refs.length !== 1) return false;
    const ref = refs[0];
    return ref?.kind === 'message' && ref.ownerId === 'owner' && ref.spaceId === 'private' &&
      ref.sourceId === request.id && ref.revision === request.sourceRevision;
  };
  const matched = tasks.filter(matchesRequest);
  return matched.length === 1 && (!pinned || !matchesRequest(pinned)) ? matched[0].id : undefined;
}

/** A compact continuation of the conversation; the existing task drawer owns its details. */
export function ConversationWork({ tasks, onOpen, canOpen, identity='', onEmptyFocus, onTeamDecision, pinnedTask, allowMessageTail, messages, stagedSupplement }: { tasks: Task[]; onOpen: (task: Task, trigger: HTMLButtonElement) => void; canOpen: boolean; identity?:string; onEmptyFocus?:()=>void; onTeamDecision?:OpenTeamDecision; pinnedTask?:Task; allowMessageTail?:boolean; messages?:readonly ConversationMessage[]; stagedSupplement?:(task:Task)=>ReactNode }) {
  const [expanded, setExpanded] = useState(false);
  const root=useRef<HTMLElement>(null),focused=useRef<{node:HTMLElement;id:string;identity:string}|null>(null);
  const listId = useId();
  const decisionsFor=(handling:ReturnType<typeof ownerWorkPresentation>)=>onTeamDecision&&handling?.teamNeedsAttention ? handling.teams.filter(team=>team.conversationId===identity && team.needsAttention && !team.cancelRequested && ((team.pendingDecisionCount ?? team.pendingQuestionCount ?? 0)>0 || (team.pendingParentRevisionCount??0)>0 || !!team.fileReview || !!team.pendingFileOperation && team.pendingFileOperation.expiresAt>Date.now())) : [];
  // The composer's pinned parent can have its own decision at the same time as a
  // member does. Do not remove the member's entry or duplicate the parent button.
  const pinned=decisionsFor(ownerWorkPresentation(pinnedTask));
  const work = tasks.map(task => {
    const { group, label: status, busy, actionLabel: action, handling } = workCollectionPresentation(task);
    const quietProgress=group==='running'&&['queued','preparing','running','verifying'].includes(task.status)&&
      task.raw?.kind==='assistant'&&task.raw.executionRole==='owner_work'&&task.raw.origin==null&&task.raw.contextAvailable===true&&task.raw.cancelRequested!==true&&
      handling?.state==='none'&&handling.conversationId===identity&&!handling.resultAvailable&&!handling.delivered&&
      !handling.waitingForChildren&&!handling.teamsUnavailable&&handling.teams.length===0;
    return { task, teams:decisionsFor(handling), progress: getRecentTaskProgress(task), attention: group === 'attention', status, busy, action, quietProgress };
  });
  // Decisions stay visible independently of the background-work disclosure.
  // The caller continues to own admission, settled filtering and task identity.
  const attention = work.filter(item => item.attention);
  const background = work.filter(item => !item.attention);
  const requested = canOpen && background.length > 1 ? latestRequestWork(tasks, pinnedTask, messages, identity) : undefined;
  const primary = requested ? background.find(item => item.task.id === requested && item.quietProgress &&
    (item.task.raw?.coordinatorEpoch === 1 || item.task.raw?.coordinatorEpoch === 0)) : undefined;
  const folded = primary ? background.filter(item => item !== primary) : background;
  const quietTail=allowMessageTail&&expanded&&background.length>1&&!attention.length&&!pinned.length&&background.every(item=>item.quietProgress);
  const revision=JSON.stringify([expanded,primary?.task.id,work.map(({task,attention,status})=>[task.id,task.status,attention,status])]);
  useCollectionMotion(root,identity,revision,{active:tasks.length>0,layout:false});
  useLayoutEffect(()=>{
    const original=focused.current;
    if(!original)return;
    if(original.identity!==identity){focused.current=null;return}
    if(original.node.isConnected&&!original.node.closest('[inert]')||document.activeElement!==document.body)return;
    const row=Array.from(root.current?.querySelectorAll<HTMLElement>('[data-work-task-id]')||[]).find(node=>node.dataset.workTaskId===original.id);
    const visibleButton=(within:HTMLElement|null|undefined)=>Array.from(within?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')||[]).find(node=>!node.closest('[inert],[hidden]'));
    const target=visibleButton(row)||root.current?.querySelector<HTMLButtonElement>('.pw-work-summary')||visibleButton(root.current);
    focused.current=null;
    if(target)target.focus({preventScroll:true});else if(!tasks.length)onEmptyFocus?.();
  },[revision,identity]);
  if (!tasks.length && !pinned.length) return null;
  const counts = new Map<string, number>();
  for (const item of folded) counts.set(item.status, (counts.get(item.status) || 0) + 1);
  const summary = [...counts].map(([status, count]) => `${count} ${status}`).join(' · ');
  const renderTask = ({ task, progress, attention: needsAttention, status, busy, action, teams }: typeof work[number]) => {
    const phone = phoneConversationId(task) !== null;
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError : needsAttention ? IconInfo : IconClock;
    const recent = !needsAttention && progress.source !== 'task' && progress.title && progress.title !== task.goal && progress.title !== status ? `，最近记录：${progress.title}` : '';
    return <li key={task.id} data-motion-key={task.id} data-work-task-id={task.id} data-work-state={task.status} data-work-attention={needsAttention || undefined}>
      <Button variant="ghost" className="pw-work-entry" aria-label={`${phone ? '查看手机任务' : '查看工作'}：${task.goal}`} aria-description={`${status}，${action}${recent}`} title={`${task.goal} · ${status}${recent}`} disabled={!canOpen} onClick={event => onOpen(task, event.currentTarget)}>
        <span className="pw-work-mark" aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" className="pw-work-progress" aria-label={status} /> : <StatusIcon size={16} />}</span>
        <span className="pw-work-entry-title">{task.goal}</span>
        <MotionFeedback as="span" identity={needsAttention ? action : status} initial={false} className="pw-work-entry-state">{needsAttention ? action : status}</MotionFeedback>
        <IconChevronRight className="pw-work-chevron" size={16} aria-hidden="true" />
      </Button>
      {stagedSupplement?.(task)}
      {onTeamDecision && teams.map(team=><ConversationTeamDecisions key={team.teamRunId} team={team} distinguish={teams.length>1} onOpen={onTeamDecision}/>)}
    </li>;
  };
  return <section ref={root} className="pw-conversation-work" aria-label="对话任务" data-quiet-reading-tail={quietTail||undefined} onFocusCapture={event=>{const node=event.target as HTMLElement,id=node.closest<HTMLElement>('[data-work-task-id]')?.dataset.workTaskId||'';focused.current={node,id,identity}}} onBlurCapture={event=>{if(event.relatedTarget instanceof Node?!event.currentTarget.contains(event.relatedTarget):(event.target as HTMLElement).isConnected&&!(event.target as HTMLElement).closest('[inert]'))focused.current=null}}>
    {onTeamDecision && pinned.map(team=><ConversationTeamDecisions key={team.teamRunId} team={team} distinguish onOpen={onTeamDecision}/>)}
    {attention.length > 0 && <ol className="pw-work-list pw-work-attention-list" aria-label="需要处理的工作">{attention.map(renderTask)}</ol>}
    {primary && <ol className="pw-work-list" aria-label="本次交办的工作">{renderTask(primary)}</ol>}
    {background.length === 1 && <ol className="pw-work-list" aria-label="后台工作">{background.map(renderTask)}</ol>}
    {background.length > 1 && <div className="pw-work-group" data-expanded={expanded}>
      <Button variant="ghost" className="pw-work-summary" aria-label={`${expanded ? '收起' : '展开'}${primary ? '其他' : ''}后台工作：${folded.length} 项，${summary}`} aria-expanded={expanded} aria-controls={listId} onClick={() => setExpanded(value => !value)}>
        <span className="pw-work-mark" aria-hidden="true">{folded.some(item => item.busy) ? <ProgressCircle isIndeterminate size="sm" className="pw-work-progress" aria-label="有工作正在执行" /> : <IconClock size={16} />}</span>
        <span className="pw-work-summary-title">{folded.length} 项{primary ? '其他' : ''}后台工作</span>
        <MotionFeedback as="span" identity={summary} initial={false} className="pw-work-summary-state">{summary}</MotionFeedback>
        <IconChevronDown className="pw-work-chevron" size={16} aria-hidden="true" />
      </Button>
      <MotionDisclosure open={expanded}><ol id={listId} className="pw-work-list pw-work-expanded-list" aria-label={primary ? '其他后台工作' : '后台工作'}>{folded.map(renderTask)}</ol></MotionDisclosure>
    </div>}
  </section>;
}
