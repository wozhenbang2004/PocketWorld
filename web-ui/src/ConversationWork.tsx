import { useId, useState } from 'react';
import { RiArrowDownSLine, RiArrowRightSLine, RiErrorWarningLine, RiInformationLine, RiPauseCircleLine, RiTimeLine } from '@remixicon/react';
import { Button } from './forms-library';
import { ProgressCircle } from './library';
import { getRecentTaskProgress } from './task-presentation';
import { phoneConversationId } from './phone-start';
import { conversationWorkActionLabel, conversationWorkNeedsAttention } from './conversation-work';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };

/** A compact continuation of the conversation; the existing task drawer owns its details. */
export function ConversationWork({ tasks, labelStatus, onOpen, canOpen }: { tasks: Task[]; labelStatus: (status: string) => string; onOpen: (task: Task) => void; canOpen: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  if (!tasks.length) return null;
  const work = tasks.map(task => {
    const progress = getRecentTaskProgress(task), attention = conversationWorkNeedsAttention(task);
    const active = ['running', 'preparing', 'verifying', 'pausing', 'stopping', 'cancelling'].includes(task.status);
    const status = progress.source === 'task' && progress.status === 'unknown' ? progress.stateLabel : attention && (active || task.status === 'queued') ? '需要处理' : labelStatus(task.status);
    return { task, progress, attention, status, busy: active && !attention, action: conversationWorkActionLabel(task) };
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
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? RiPauseCircleLine : task.status === 'failed' ? RiErrorWarningLine : needsAttention ? RiInformationLine : RiTimeLine;
    const recent = !needsAttention && progress.source !== 'task' && progress.title && progress.title !== task.goal && progress.title !== status ? `，最近记录：${progress.title}` : '';
    return <li key={task.id} data-work-task-id={task.id} data-work-state={task.status} data-work-attention={needsAttention || undefined}>
      <Button variant="ghost" className="pw-work-entry" aria-label={`${phone ? '查看手机任务' : '查看工作'}：${task.goal}`} aria-description={`${status}，${action}${recent}`} title={`${task.goal} · ${status}${recent}`} disabled={!canOpen} onClick={() => onOpen(task)}>
        <span className="pw-work-mark" aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" className="pw-work-progress" aria-label={status} /> : <StatusIcon size={16} />}</span>
        <span className="pw-work-entry-title">{task.goal}</span>
        <span className="pw-work-entry-state">{needsAttention ? action : status}</span>
        <RiArrowRightSLine className="pw-work-chevron" size={16} aria-hidden="true" />
      </Button>
    </li>;
  };
  return <section className="pw-conversation-work" aria-label="对话任务">
    {attention.length > 0 && <ol className="pw-work-list pw-work-attention-list" aria-label="需要处理的工作">{attention.map(renderTask)}</ol>}
    {background.length === 1 && <ol className="pw-work-list" aria-label="后台工作">{background.map(renderTask)}</ol>}
    {background.length > 1 && <div className="pw-work-group" data-expanded={expanded}>
      <Button variant="ghost" className="pw-work-summary" aria-label={`${expanded ? '收起' : '展开'}后台工作：${background.length} 项，${summary}`} aria-expanded={expanded} aria-controls={listId} onClick={() => setExpanded(value => !value)}>
        <span className="pw-work-mark" aria-hidden="true">{background.some(item => item.busy) ? <ProgressCircle isIndeterminate size="sm" className="pw-work-progress" aria-label="有工作正在执行" /> : <RiTimeLine size={16} />}</span>
        <span className="pw-work-summary-title">{background.length} 项后台工作</span>
        <span className="pw-work-summary-state">{summary}</span>
        <RiArrowDownSLine className="pw-work-chevron" size={16} aria-hidden="true" />
      </Button>
      <ol id={listId} className="pw-work-list pw-work-expanded-list" aria-label="后台工作" hidden={!expanded}>{background.map(renderTask)}</ol>
    </div>}
  </section>;
}
