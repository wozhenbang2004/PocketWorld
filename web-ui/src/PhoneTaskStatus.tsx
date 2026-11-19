import { useEffect, useState } from 'react';
import { Button, IconButton } from './forms-library';
import { Popover, PopoverTrigger } from './shell-library';
import { IconChevronRight, IconMonitor, IconMore, IconPauseCircle, IconPlay, IconStop } from './icons';
import { ProgressCircle } from './library';
import { phoneRunIdentity } from './phone-start';
import { isPrivateLocalPhone } from './PhonePauseInstruction';
import { phoneSurfaceLabel, type PrivatePhoneSurface } from './private-phone-surface';
import { conversationInlineAttention } from './conversation-work';
import { workCollectionPresentation } from './work-collection';
import './phone-task-status.css';

type Task = { id: string; goal: string; status: string; raw?: Record<string, any> };
type Props = {
  task: Task;
  surface?: PrivatePhoneSurface;
  can: (action: string) => boolean;
  onOpen: () => void;
  onControl: (action: string, identity: { taskId: string; runId: string; epoch: number }) => void;
};

/** One current-task row, using the retained BoardUI controls and anchored menu.
 * The host can acknowledge the actual stop button before yielding its overlay. */
export function PhoneTaskStatus({ task, surface, can, onOpen, onControl }: Props) {
  const [menuOpen, setMenuOpen] = useState(false);
  const identity = phoneRunIdentity(task);
  useEffect(() => setMenuOpen(false), [task.id, identity?.runId, identity?.epoch, task.status]);
  const { label: status, busy } = workCollectionPresentation(task);
  const surfaceLabel = surface ? phoneSurfaceLabel(surface) : '';
  const stopping = task.status === 'stopping' || surface?.stopRequested === true;
  const stage = stopping ? '正在停止' : surface?.paused && task.status === 'running' ? '已请求暂停，等待任务回执' : status;
  const control = (action: string) => { setMenuOpen(false); if (identity) onControl(action, identity); };
  const canPause = identity && !stopping && (!surface || !surface.paused) && task.status === 'running' && can('phone.pause');
  const canResume = identity && !stopping && (!surface || surface.paused) && task.status === 'paused' && !conversationInlineAttention(task) && can('phone.resume');
  const canStop = !!identity && can('phone.stop') && !stopping;

  return <section className="pw-phone-activity" aria-label="当前手机任务" data-task-id={task.id}>
    <Button variant="ghost" className="pw-phone-activity-open" disabled={!can('tasks.open')} onClick={onOpen}
      aria-label={`${stage}：${task.goal}。 ${surfaceLabel}，查看任务`} title={surfaceLabel||task.goal}>
      <span className="pw-phone-activity-mark" aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <IconMonitor size={18} />}</span>
      <span className="pw-phone-activity-copy"><strong dir="auto">{task.goal || '手机任务'}</strong><span>{[stage,surfaceLabel].filter(Boolean).join(' · ')}</span></span>
      <IconChevronRight size={15} aria-hidden="true" />
    </Button>
    {(canPause || canResume) && <PopoverTrigger isOpen={menuOpen} onOpenChange={setMenuOpen}>
      <IconButton icon={IconMore} aria-label="手机任务操作" title="手机任务操作" />
      <Popover aria-label="手机任务操作" className="pw-phone-activity-menu" placement="bottom end">
        <div className="pw-phone-activity-actions">
          {canPause && <Button variant="ghost" leadingIcon={IconPauseCircle} onClick={() => control('phone.pause')}>暂停任务</Button>}
          {canResume && <Button variant="ghost" leadingIcon={IconPlay} onClick={() => control('phone.resume')}>继续任务</Button>}
          {canResume && can('tasks.open') && can('phone.pauseNote.read') && isPrivateLocalPhone(task) &&
            <Button variant="ghost" onClick={() => { setMenuOpen(false); onOpen(); }}>打开原任务补充后继续</Button>}
          {can('tasks.open') && <Button variant="ghost" leadingIcon={IconMonitor} onClick={() => { setMenuOpen(false); onOpen(); }}>查看任务详情</Button>}
        </div>
      </Popover>
    </PopoverTrigger>}
    {identity && can('phone.stop') && <IconButton icon={IconStop} className="pw-phone-activity-stop"
      aria-label={(stopping?'正在停止：':'停止手机任务：')+(task.goal||'本次执行')} title={'停止：'+(task.goal||'本次执行')} disabled={!canStop}
      data-phone-stop-task-id={identity.taskId} data-phone-stop-run-id={identity.runId} data-phone-stop-epoch={identity.epoch}
      onClick={() => control('phone.stop')} />}
  </section>;
}
