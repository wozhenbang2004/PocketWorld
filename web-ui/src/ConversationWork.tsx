import { useState } from 'react';
import { RiArrowRightSLine } from '@remixicon/react';
import { Button } from './forms-library';
import { ProgressCircle } from './library';
import { getRecentTaskProgress } from './task-presentation';
import { phoneConversationId } from './phone-start';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };

/** A compact continuation of the conversation; the existing task drawer owns its details. */
export function ConversationWork({ tasks, labelStatus, onOpen, canOpen }: { tasks: Task[]; labelStatus: (status: string) => string; onOpen: (task: Task) => void; canOpen: boolean }) {
  const [expanded, setExpanded] = useState(false);
  if (!tasks.length) return null;
  const visible = expanded ? tasks : tasks.slice(0, 2);
  return <section className="pw-conversation-work pw-conversation-phone-tasks" aria-label="管家正在办理的工作">
    <div className="pw-work-heading"><span>正在办理 · {tasks.length}</span>{tasks.length > 2 && <Button variant="ghost" size="small" onClick={() => setExpanded(value => !value)}>{expanded ? '收起' : '展开全部'}</Button>}</div>
    <ol>{visible.map(task => {
      const progress = getRecentTaskProgress(task), busy = ['running', 'preparing', 'queued', 'verifying', 'stopping'].includes(task.status), phone = phoneConversationId(task) !== null;
      return <li key={task.id} data-work-task-id={task.id}><Button variant="ghost" className="pw-work-row pw-conversation-phone-task" aria-label={`${phone ? '查看手机任务' : '查看工作'}：${task.goal}`} disabled={!canOpen} onClick={() => onOpen(task)}>
        <span className="pw-work-mark">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={labelStatus(task.status)} /> : <span aria-hidden="true">·</span>}</span>
        <span className="pw-work-copy"><b>{task.goal}</b><small>{labelStatus(task.status)}{progress?.title && progress.title !== task.goal ? ` · ${progress.title}` : ''}</small></span><RiArrowRightSLine size={16} aria-hidden="true" />
      </Button></li>;
    })}</ol>
  </section>;
}
