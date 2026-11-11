import { useEffect, useState } from 'react';
import { Button, IconButton } from './forms-library';
import { Popover, PopoverTrigger } from './shell-library';
import { IconChevronRight, IconMonitor, IconMore, IconPauseCircle, IconPlay, IconStop } from './icons';
import { ProgressCircle } from './library';
import { phoneRunIdentity } from './phone-start';
import { conversationInlineAttention, conversationWorkActionLabel, conversationWorkNeedsAttention } from './conversation-work';
import { taskNeedsReconciliation } from './task-presentation';
import './phone-task-status.css';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };
type Props = {
  task: Task;
  can: (action: string) => boolean;
  onOpen: () => void;
  onControl: (action: string, identity: { taskId: string; runId: string; epoch: number }) => void;
  labelStatus: (status: string) => string;
};

/** One current-task row, using the retained BoardUI controls and anchored menu.
 * The host can acknowledge the actual stop button before yielding its overlay. */
export function PhoneTaskStatus({ task, can, onOpen, onControl, labelStatus }: Props) {
  const [menuOpen, setMenuOpen] = useState(false);
  const identity = phoneRunIdentity(task);
  useEffect(() => setMenuOpen(false), [task.id, identity?.runId, identity?.epoch, task.status]);
  const needsAttention = conversationWorkNeedsAttention(task);
  const status = taskNeedsReconciliation(task) ? '有操作待核对' : needsAttention ? conversationWorkActionLabel(task) : labelStatus(task.status);
  const busy = ['preparing', 'running', 'verifying', 'pausing', 'stopping'].includes(task.status) && !needsAttention;
  const control = (action: string) => { setMenuOpen(false); if (identity) onControl(action, identity); };
  const canPause = identity && task.status === 'running' && can('phone.pause');
  const canResume = identity && task.status === 'paused' && !conversationInlineAttention(task) && can('phone.resume');
  const canStop = !!identity && can('phone.stop') && task.status !== 'stopping';

  return <section className="pw-phone-activity" aria-label="当前手机任务" data-task-id={task.id}>
    <Button variant="ghost" className="pw-phone-activity-open" disabled={!can('tasks.open')} onClick={onOpen}
      aria-label={`${status}：${task.goal}，查看任务`} title={task.goal}>
      <span className="pw-phone-activity-mark" aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <IconMonitor size={18} />}</span>
      <span className="pw-phone-activity-copy"><strong dir="auto">{task.goal || '手机任务'}</strong><span>{status}</span></span>
      <IconChevronRight size={15} aria-hidden="true" />
    </Button>
    {(canPause || canResume) && <PopoverTrigger isOpen={menuOpen} onOpenChange={setMenuOpen}>
      <IconButton icon={IconMore} aria-label="手机任务操作" title="手机任务操作" />
      <Popover aria-label="手机任务操作" className="pw-phone-activity-menu" placement="bottom end">
        <div className="pw-phone-activity-actions">
          {canPause && <Button variant="ghost" leadingIcon={IconPauseCircle} onClick={() => control('phone.pause')}>暂停任务</Button>}
          {canResume && <Button variant="ghost" leadingIcon={IconPlay} onClick={() => control('phone.resume')}>继续任务</Button>}
          {can('tasks.open') && <Button variant="ghost" leadingIcon={IconMonitor} onClick={() => { setMenuOpen(false); onOpen(); }}>查看任务详情</Button>}
        </div>
      </Popover>
    </PopoverTrigger>}
    {identity && can('phone.stop') && <IconButton icon={IconStop} className="pw-phone-activity-stop"
      aria-label={task.status === 'stopping' ? '正在停止手机任务' : '停止手机任务'} title="停止手机任务" disabled={!canStop}
      data-phone-stop-task-id={identity.taskId} data-phone-stop-run-id={identity.runId} data-phone-stop-epoch={identity.epoch}
      onClick={() => control('phone.stop')} />}
  </section>;
}
