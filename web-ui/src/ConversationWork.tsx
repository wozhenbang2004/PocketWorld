import { useState } from 'react';
import { RiArrowRightSLine, RiErrorWarningLine, RiInformationLine, RiPauseCircleLine, RiTimeLine } from '@remixicon/react';
import { Button } from './forms-library';
import { ProgressCircle } from './library';
import { getRecentTaskProgress } from './task-presentation';
import { phoneConversationId } from './phone-start';
import { conversationWorkActionLabel, conversationWorkNeedsAttention } from './conversation-work';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };

/** A compact continuation of the conversation; the existing task drawer owns its details. */
export function ConversationWork({ tasks, labelStatus, onOpen, canOpen }: { tasks: Task[]; labelStatus: (status: string) => string; onOpen: (task: Task) => void; canOpen: boolean }) {
  const [expanded, setExpanded] = useState(false);
  if (!tasks.length) return null;
  const visible = expanded ? tasks : tasks.slice(0, 2);
  const attentionCount = tasks.filter(conversationWorkNeedsAttention).length;
  return <section className="pw-conversation-work pw-conversation-phone-tasks" aria-label="对话任务">
    <div className="pw-work-heading"><h3>对话任务</h3><span className="pw-work-count">{attentionCount > 0 ? `${attentionCount} 待处理 · ` : ''}{tasks.length} 项</span>{tasks.length > 2 && <Button variant="ghost" size="small" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>{expanded ? '收起' : '展开全部'}</Button>}</div>
    <ol>{visible.map(task => {
      const progress = getRecentTaskProgress(task), attention = conversationWorkNeedsAttention(task), action = conversationWorkActionLabel(task), phone = phoneConversationId(task) !== null;
      const active = ['running', 'preparing', 'verifying', 'pausing', 'stopping', 'cancelling'].includes(task.status), busy = active && !attention;
      const status = progress.source === 'task' && progress.status === 'unknown' ? progress.stateLabel : attention && (active || task.status === 'queued') ? '需要处理' : labelStatus(task.status);
      const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? RiPauseCircleLine : task.status === 'failed' ? RiErrorWarningLine : attention ? RiInformationLine : RiTimeLine;
      return <li key={task.id} data-work-task-id={task.id} data-work-state={task.status} data-work-attention={attention || undefined}><Button variant="ghost" className="pw-work-row pw-conversation-phone-task" aria-label={`${phone ? '查看手机任务' : '查看工作'}：${task.goal}`} aria-description={`${status}，${action}`} disabled={!canOpen} onClick={() => onOpen(task)}>
        <span className="pw-work-mark">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <StatusIcon size={18} aria-hidden="true" />}</span>
        <span className="pw-work-copy"><b>{task.goal}</b><small>{status}{!attention && progress.source !== 'task' && progress.title && progress.title !== task.goal && progress.title !== status ? ` · ${progress.title}` : ''}</small></span><span className="pw-work-action">{action}<RiArrowRightSLine size={16} aria-hidden="true" /></span>
      </Button></li>;
    })}</ol>
  </section>;
}
