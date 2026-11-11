import { useId, useLayoutEffect, useRef, type ReactNode } from 'react';
import { Button, IconButton } from './forms-library';
import { IconCheckCircle, IconChevronRight, IconClock, IconError, IconFolder, IconInfo, IconPauseCircle } from './icons';
import { ProgressCircle } from './library';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { getRecentTaskProgress } from './task-presentation';
import { isButlerHomeWork, workCollectionPresentation, type WorkCollectionFilter, type WorkCollectionGroup } from './work-collection';
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
  preparations?: ReactNode;
  hasPreparations?: boolean;
  tasks: ButlerHomeTask[];
  canOpen: boolean;
  onOpen: (task: ButlerHomeTask) => void;
  onAllWork: (filter?: WorkCollectionFilter) => void;
  onArtifacts?: (task: ButlerHomeTask) => void;
  onScrollTop?: (top: number) => void;
  initialScrollTop?: number;
};

const updatedAt = (task: ButlerHomeTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;

/** Adapt the retained Affect AssistantHome / Composer composition around the
 * real shared input and BoardUI task rows. This view reads
 * the native private-work projection; only the original task owns decisions. */
export function ButlerHome({ ready, modelReady, modelSetup, composer, preparations, hasPreparations = false, tasks, canOpen, onOpen, onAllWork, onArtifacts, onScrollTop, initialScrollTop = 0 }: ButlerHomeProps) {
  const scroller = useRef<HTMLDivElement>(null), workRoot = useRef<HTMLElement>(null);
  const allWorkButton = useRef<HTMLButtonElement>(null);
  const focused = useRef<{ node: HTMLElement; id: string } | null>(null);
  const savedScroll = useRef(Number.isFinite(initialScrollTop) ? Math.max(0, initialScrollTop) : 0);
  const restored = useRef(false), reportScroll = useRef(onScrollTop);
  reportScroll.current = onScrollTop;
  const workHeading = useId();
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
      const status = presentation.label;
      const detail = !contextAvailable ? task.summary?.trim() || '' : unknown ? progress.detail : handling?.waitingForButler || handling?.needsAttention ? handling.reason : task.summary?.trim() || (progress && progress.source !== 'task' ? progress.detail || progress.title : '');
      return { task, attention, status, detail: detail === status || detail === task.goal ? '' : detail,
        handling, group: presentation.group, busy: presentation.busy, action: presentation.actionLabel };
    });
  const groups = ([
    { id: 'attention', label: '需要你处理', limit: 4 },
    { id: 'running', label: '正在推进', limit: 3 },
    { id: 'ended', label: '近期结果', limit: 3 },
  ] satisfies { id: WorkCollectionGroup; label: string; limit: number }[])
    .map(group => ({ ...group, items: work.filter(item => item.group === group.id) })).filter(group => group.items.length > 0);
  const visible = groups.flatMap(group => group.items.slice(0, group.limit));
  const revision = JSON.stringify([ready, canOpen, Boolean(onArtifacts), visible.map(({ task, attention: needsAttention, handling }) => [task.id, task.status, needsAttention, handling?.state, handling?.resultAvailable, handling?.delivered])]);
  useCollectionMotion(workRoot, 'butler:owner:private', revision, { active: ready, layout: false });
  const empty = ready && !work.length && !hasPreparations;

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

  const renderTask = ({ task, attention: needsAttention, status, detail, busy, action, handling, group }: typeof work[number]) => {
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError
      : needsAttention ? IconInfo : group === 'ended' && task.status === 'completed' ? IconCheckCircle : IconClock;
    const date = new Date(updatedAt(task)), hasDate = updatedAt(task) > 0 && Number.isFinite(date.getTime());
    const artifactsAvailable = onArtifacts && task.raw?.contextAvailable !== false && (handling?.resultAvailable || handling?.delivered);
    return <li key={task.id} data-task-id={task.id} data-motion-key={task.id} data-task-state={task.status} data-attention={needsAttention || undefined}>
      <Button variant="ghost" className="pw-butler-work-row" disabled={!canOpen} aria-label={`${action}：${task.goal || '委派工作'}`} aria-description={[status, detail].filter(Boolean).join('，')} onClick={() => onOpen(task)}>
        <span className="pw-butler-work-mark" data-attention={needsAttention || undefined} aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <StatusIcon size={18} />}</span>
        <span className="pw-butler-work-copy">
          <strong dir="auto">{task.goal || '委派工作'}</strong>
          <span className="pw-butler-work-meta"><MotionFeedback as="span" identity={status} initial={false}>{status}</MotionFeedback>{hasDate && <time dateTime={date.toISOString()}>{date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}</span>
          {needsAttention && detail && <span className="pw-butler-work-detail" dir="auto">{detail}</span>}
        </span>
        <span className="pw-butler-work-action"><span>{action}</span><IconChevronRight size={15} aria-hidden="true" /></span>
      </Button>
      {artifactsAvailable && <IconButton icon={IconFolder} className="pw-butler-artifacts" aria-label={`文件与版本：${task.goal || '委派工作'}`} title="文件与版本" onClick={() => onArtifacts?.(task)} />}
    </li>;
  };
  const modelConnection = ready && !modelReady && <div className="pw-butler-model-setup"><p>连接模型后即可开始。</p>{modelSetup}</div>;

  return <main className="pw-chat-workspace pw-butler-workspace" aria-label="管家" data-empty={empty || undefined}>
    <div ref={scroller} className="pw-butler-scroll" onScroll={event => { if (restored.current) reportScroll.current?.(event.currentTarget.scrollTop); }}>
      <div className="pw-butler-content">
        {empty ? <header className="pw-butler-welcome"><h1>有什么事交给我？</h1>{modelConnection}</header> : modelConnection}
        {preparations}
        {(!ready || work.length > 0) && <section ref={workRoot} className="pw-butler-work" aria-labelledby={workHeading} aria-busy={!ready}
          onFocusCapture={event => { const node = event.target as HTMLElement, id = node.closest<HTMLElement>('[data-task-id]')?.dataset.taskId; focused.current = id ? { node, id } : null; }}
          onBlurCapture={event => { const node = event.target as HTMLElement; if (event.relatedTarget instanceof Node ? !event.currentTarget.contains(event.relatedTarget) : node.isConnected && !node.closest('[inert],[hidden]') && !node.matches(':disabled')) focused.current = null; }}>
          <header className="pw-butler-work-heading"><h2 id={workHeading}>最近工作</h2>{work.length > 0 && <Button ref={allWorkButton} variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} onClick={() => onAllWork('all')}>全部工作</Button>}</header>
          {!ready ? <p className="pw-butler-reading" role="status"><ProgressCircle isIndeterminate size="sm" aria-label="正在恢复工作记录" /><span>正在恢复工作记录…</span></p> : <>
            {groups.map(group => <section key={group.id} className="pw-butler-work-group" aria-labelledby={`${workHeading}-${group.id}`}>
              <header className="pw-butler-group-heading"><h3 id={`${workHeading}-${group.id}`}>{group.label}</h3>
                {group.items.length > group.limit && <Button variant="ghost" size="small" onClick={() => onAllWork(group.id)} aria-label={`前往全部工作，查看其他${group.label}的工作`}>查看更多<IconChevronRight size={14} aria-hidden="true" /></Button>}
              </header>
              <ol className="pw-butler-work-list">{group.items.slice(0, group.limit).map(renderTask)}</ol>
            </section>)}
            {!!work.length && !canOpen && <p className="pw-butler-work-note" role="status">当前暂时无法打开原任务记录。</p>}
          </>}
        </section>}
      </div>
    </div>
    {composer}
  </main>;
}

export default ButlerHome;
