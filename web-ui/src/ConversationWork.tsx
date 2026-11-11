import { useId, useLayoutEffect, useRef, useState } from 'react';
import { IconChevronDown, IconChevronRight, IconClock, IconError, IconInfo, IconPauseCircle } from './icons';
import { Button } from './forms-library';
import { ProgressCircle } from './library';
import { getRecentTaskProgress } from './task-presentation';
import { phoneConversationId } from './phone-start';
import { conversationWorkActionLabel, conversationWorkNeedsAttention } from './conversation-work';
import { MotionDisclosure, MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { ownerWorkPresentation } from './owner-work-presentation';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };

/** A compact continuation of the conversation; the existing task drawer owns its details. */
export function ConversationWork({ tasks, labelStatus, onOpen, canOpen, identity='', onEmptyFocus }: { tasks: Task[]; labelStatus: (status: string) => string; onOpen: (task: Task) => void; canOpen: boolean; identity?:string; onEmptyFocus?:()=>void }) {
  const [expanded, setExpanded] = useState(false);
  const root=useRef<HTMLElement>(null),focused=useRef<{node:HTMLElement;id:string;identity:string}|null>(null);
  const listId = useId();
  const revision=JSON.stringify([expanded,tasks.map(task=>{const work=ownerWorkPresentation(task);return [task.id,task.status,conversationWorkNeedsAttention(task),work?.state,work?.handlingLabel]})]);
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
  if (!tasks.length) return null;
  const work = tasks.map(task => {
    const progress = getRecentTaskProgress(task), attention = conversationWorkNeedsAttention(task);
    const handling = ownerWorkPresentation(task);
    const active = ['running', 'preparing', 'verifying', 'pausing', 'stopping', 'cancelling'].includes(task.status);
    const status = progress.source === 'task' && progress.status === 'unknown' ? progress.stateLabel : handling?.waitingForButler || handling?.waitingForChildren || handling?.needsAttention ? handling.handlingLabel : attention && (active || task.status === 'queued') ? '需要处理' : labelStatus(task.status);
    const teamBusy = handling?.teams.some(team => ['queued', 'running', 'preparing', 'stopping'].includes(team.status));
    return { task, progress, attention, status, busy: (active || handling?.waitingForButler && handling.state === 'running' || teamBusy) && !attention, action: conversationWorkActionLabel(task) };
  });
  // Decisions stay visible independently of the background-work disclosure.
  // The caller continues to own admission, settled filtering and task identity.
  const attention = work.filter(item => item.attention);
  const background = work.filter(item => !item.attention);
  const counts = new Map<string, number>();
  for (const item of background) counts.set(item.status, (counts.get(item.status) || 0) + 1);
  const summary = [...counts].map(([status, count]) => `${count} ${status}`).join(' · ');
  const renderTask = ({ task, progress, attention: needsAttention, status, busy, action }: typeof work[number]) => {
    const phone = phoneConversationId(task) !== null;
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError : needsAttention ? IconInfo : IconClock;
    const recent = !needsAttention && progress.source !== 'task' && progress.title && progress.title !== task.goal && progress.title !== status ? `，最近记录：${progress.title}` : '';
    return <li key={task.id} data-motion-key={task.id} data-work-task-id={task.id} data-work-state={task.status} data-work-attention={needsAttention || undefined}>
      <Button variant="ghost" className="pw-work-entry" aria-label={`${phone ? '查看手机任务' : '查看工作'}：${task.goal}`} aria-description={`${status}，${action}${recent}`} title={`${task.goal} · ${status}${recent}`} disabled={!canOpen} onClick={() => onOpen(task)}>
        <span className="pw-work-mark" aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" className="pw-work-progress" aria-label={status} /> : <StatusIcon size={16} />}</span>
        <span className="pw-work-entry-title">{task.goal}</span>
        <MotionFeedback as="span" identity={needsAttention ? action : status} initial={false} className="pw-work-entry-state">{needsAttention ? action : status}</MotionFeedback>
        <IconChevronRight className="pw-work-chevron" size={16} aria-hidden="true" />
      </Button>
    </li>;
  };
  return <section ref={root} className="pw-conversation-work" aria-label="对话任务" onFocusCapture={event=>{const node=event.target as HTMLElement,id=node.closest<HTMLElement>('[data-work-task-id]')?.dataset.workTaskId||'';focused.current={node,id,identity}}} onBlurCapture={event=>{if(event.relatedTarget instanceof Node?!event.currentTarget.contains(event.relatedTarget):(event.target as HTMLElement).isConnected&&!(event.target as HTMLElement).closest('[inert]'))focused.current=null}}>
    {attention.length > 0 && <ol className="pw-work-list pw-work-attention-list" aria-label="需要处理的工作">{attention.map(renderTask)}</ol>}
    {background.length === 1 && <ol className="pw-work-list" aria-label="后台工作">{background.map(renderTask)}</ol>}
    {background.length > 1 && <div className="pw-work-group" data-expanded={expanded}>
      <Button variant="ghost" className="pw-work-summary" aria-label={`${expanded ? '收起' : '展开'}后台工作：${background.length} 项，${summary}`} aria-expanded={expanded} aria-controls={listId} onClick={() => setExpanded(value => !value)}>
        <span className="pw-work-mark" aria-hidden="true">{background.some(item => item.busy) ? <ProgressCircle isIndeterminate size="sm" className="pw-work-progress" aria-label="有工作正在执行" /> : <IconClock size={16} />}</span>
        <span className="pw-work-summary-title">{background.length} 项后台工作</span>
        <MotionFeedback as="span" identity={summary} initial={false} className="pw-work-summary-state">{summary}</MotionFeedback>
        <IconChevronDown className="pw-work-chevron" size={16} aria-hidden="true" />
      </Button>
      <MotionDisclosure open={expanded}><ol id={listId} className="pw-work-list pw-work-expanded-list" aria-label="后台工作">{background.map(renderTask)}</ol></MotionDisclosure>
    </div>}
  </section>;
}
