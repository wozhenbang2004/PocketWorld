import { useId, useLayoutEffect, useRef, type ReactNode } from 'react';
import { Button } from './forms-library';
import { IconCheckCircle, IconChevronRight, IconClock, IconError, IconInfo, IconPauseCircle } from './icons';
import { ProgressCircle } from './library';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import { DecisionInbox } from './shell-library/DecisionInbox';
import { conversationWorkActionLabel, conversationWorkNeedsAttention } from './conversation-work';
import { getRecentTaskProgress, taskNeedsReconciliation } from './task-presentation';
import { ownerWorkPresentation } from './owner-work-presentation';
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
  tasks: ButlerHomeTask[];
  canOpen: boolean;
  onOpen: (task: ButlerHomeTask) => void;
  onAllWork: () => void;
  onArtifacts?: (task: ButlerHomeTask) => void;
  onScrollTop?: (top: number) => void;
  initialScrollTop?: number;
};

const activeStates = new Set(['queued', 'preparing', 'running', 'verifying', 'pausing', 'stopping', 'cancelling']);
const endedStates = new Set(['completed', 'failed', 'cancelled', 'canceled', 'stopped']);
const statusLabels: Record<string, string> = {
  queued: '等待执行', preparing: '准备执行', running: '正在执行', verifying: '核对结果中',
  pausing: '正在暂停', stopping: '正在停止', cancelling: '正在停止',
  waiting_approval: '等待你的授权', awaiting_approval: '等待你的授权',
  waiting_permission: '等待你的授权', awaiting_permission: '等待你的授权',
  needs_confirmation: '结果等你核对', awaiting_user: '等你补充信息', waiting_user: '等你补充信息',
  paused: '已暂停', interrupted: '已中断', completed: '已完成', failed: '本次未完成',
  cancelled: '已停止', canceled: '已停止', stopped: '已停止',
};
const updatedAt = (task: ButlerHomeTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;

/** Adapt the retained Affect AssistantHome / Composer composition around the
 * real shared input, MIT DecisionInbox and BoardUI task rows. This view reads
 * the native private-work projection; only the original task owns decisions. */
export function ButlerHome({ ready, modelReady, modelSetup, composer, preparations, tasks, canOpen, onOpen, onAllWork, onArtifacts, onScrollTop, initialScrollTop = 0 }: ButlerHomeProps) {
  const scroller = useRef<HTMLDivElement>(null), workRoot = useRef<HTMLElement>(null);
  const allWorkButton = useRef<HTMLButtonElement>(null);
  const focused = useRef<{ node: HTMLElement; id: string } | null>(null);
  const savedScroll = useRef(Number.isFinite(initialScrollTop) ? Math.max(0, initialScrollTop) : 0);
  const restored = useRef(false), reportScroll = useRef(onScrollTop);
  reportScroll.current = onScrollTop;
  const workHeading = useId();

  // Keep the host's redacted goal/summary as supplied. Do not recover content
  // from another conversation, a generic task list or an unprojected source.
  const work = (ready ? tasks : []).filter(task => Boolean(task.id) && task.raw?.id === task.id &&
    task.raw.actorId === 'owner' && task.raw.spaceId === 'private' && task.raw.executionRole === 'owner_work')
    .sort((a, b) => updatedAt(b) - updatedAt(a))
    .map(task => {
      const contextAvailable = task.raw?.contextAvailable !== false;
      const progress = contextAvailable ? getRecentTaskProgress(task) : null;
      const handling = ownerWorkPresentation(task);
      const reconciliation = taskNeedsReconciliation(task);
      const attention = conversationWorkNeedsAttention(task) || handling?.needsAttention || !activeStates.has(task.status) && !endedStates.has(task.status);
      const unknown = progress?.source === 'task' && progress.status === 'unknown';
      const status = reconciliation ? '结果待核对' : unknown ? progress.stateLabel : handling && endedStates.has(task.status) ? handling.handlingLabel : attention && activeStates.has(task.status) ? '需要你处理' : statusLabels[task.status] || '状态待核对';
      const detail = !contextAvailable ? task.summary?.trim() || '' : unknown ? progress.detail : handling?.waitingForButler || handling?.needsAttention ? handling.reason : task.summary?.trim() || (progress && progress.source !== 'task' ? progress.detail || progress.title : '');
      return { task, attention, status, detail: detail === status || detail === task.goal ? '' : detail,
        handling, busy: !attention && (activeStates.has(task.status) && task.status !== 'queued' || handling?.state === 'running'), action: conversationWorkActionLabel(task) };
    });
  const attention = work.filter(item => item.attention), active = work.filter(item => !item.attention && (activeStates.has(item.task.status) || item.handling?.waitingForButler));
  const recent = work.filter(item => !item.attention && !item.handling?.waitingForButler && endedStates.has(item.task.status));
  const featured = recent.find(item => item.handling?.delivered);
  const otherRecent = recent.filter(item => item !== featured);
  const visible = [...attention.slice(0, 4), ...active.slice(0, 3), ...(featured ? [featured] : []), ...otherRecent.slice(0, 3)];
  const revision = JSON.stringify([ready, canOpen, visible.map(({ task, attention: needsAttention, handling }) => [task.id, task.status, needsAttention, handling?.state])]);
  useCollectionMotion(workRoot, 'butler:owner:private', revision, { active: ready, layout: false });
  const briefing = attention.length ? `最近的工作中，有 ${attention.length} 件需要你处理。${featured ? '也有一份已整理的结果可以查看。' : ''}`
    : featured ? '有已整理的结果可以查看，也可以继续交代新的目标。'
    : active.length ? '手头的工作正在跟进。你可以继续交代新的目标。'
    : work.length ? '最近的工作都在这里，可以查看结果或继续处理。' : '说说你的目标，我来安排、跟进，把结果带回来。';

  useLayoutEffect(() => {
    if (!ready || restored.current || !scroller.current) return;
    scroller.current.scrollTop = savedScroll.current;
    restored.current = true;
  }, [ready]);
  useLayoutEffect(() => {
    const node = scroller.current;
    return () => { if (node && restored.current) reportScroll.current?.(node.scrollTop); };
  }, []);
  // A changing status can move a focused task between groups. Restore only
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

  const renderTask = ({ task, attention: needsAttention, status, detail, busy, action }: typeof work[number]) => {
    const StatusIcon = ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : task.status === 'failed' ? IconError
      : needsAttention ? IconInfo : task.status === 'completed' ? IconCheckCircle : IconClock;
    const date = new Date(updatedAt(task)), hasDate = updatedAt(task) > 0 && Number.isFinite(date.getTime());
    return <li key={task.id} data-task-id={task.id} data-motion-key={task.id} data-task-state={task.status}>
      <Button variant="ghost" className="pw-butler-work-row" disabled={!canOpen} aria-label={`${action}：${task.goal || '委派工作'}`} aria-description={[status, detail].filter(Boolean).join('，')} onClick={() => onOpen(task)}>
        <span className="pw-butler-work-mark" data-attention={needsAttention || undefined} aria-hidden="true">{busy ? <ProgressCircle isIndeterminate size="sm" aria-label={status} /> : <StatusIcon size={18} />}</span>
        <span className="pw-butler-work-copy">
          <strong dir="auto">{task.goal || '委派工作'}</strong>
          <span className="pw-butler-work-meta"><MotionFeedback as="span" identity={status} initial={false}>{status}</MotionFeedback>{hasDate && <time dateTime={date.toISOString()}>{date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}</span>
          {detail && <span className="pw-butler-work-detail" dir="auto">{detail}</span>}
        </span>
        <IconChevronRight className="pw-butler-work-chevron" size={16} aria-hidden="true" />
      </Button>
    </li>;
  };
  const moreWork = (count: number, label: string) => count > 0 && <Button variant="ghost" className="pw-butler-more" trailingIcon={IconChevronRight} onClick={onAllWork}>另有 {count} 项{label}</Button>;

  return <main className="pw-chat-workspace pw-butler-workspace" aria-label="管家" data-returning={work.length > 0 || undefined}>
    <div ref={scroller} className="pw-butler-scroll" onScroll={event => { if (restored.current) reportScroll.current?.(event.currentTarget.scrollTop); }}>
      <div className="pw-butler-content">
        <header className="pw-butler-welcome">
          <h1>{work.length ? '接着把事情做好' : '有什么事交给我？'}</h1>
          <p>{briefing}</p>
          {ready && !modelReady && <div className="pw-butler-model-setup"><p>先连接一个模型，就可以开始。</p>{modelSetup}</div>}
        </header>
        {composer}
        {preparations}
        <section ref={workRoot} className="pw-butler-work" aria-labelledby={workHeading} aria-busy={!ready}
          onFocusCapture={event => { const node = event.target as HTMLElement, id = node.closest<HTMLElement>('[data-task-id]')?.dataset.taskId; focused.current = id ? { node, id } : null; }}
          onBlurCapture={event => { const node = event.target as HTMLElement; if (event.relatedTarget instanceof Node ? !event.currentTarget.contains(event.relatedTarget) : node.isConnected && !node.closest('[inert],[hidden]') && !node.matches(':disabled')) focused.current = null; }}>
          <h2 id={workHeading}>最近的工作</h2>
          {!ready ? <p className="pw-butler-reading" role="status"><ProgressCircle isIndeterminate size="sm" aria-label="正在恢复工作记录" /><span>正在恢复工作记录…</span></p> : <>
            {attention.length > 0 && <section className="pw-butler-work-group pw-butler-attention" aria-label="待你处理">
              <h3>待你处理</h3>
              {canOpen ? <DecisionInbox compact className="pw-butler-decisions" heading="待你处理"
                decisions={attention.slice(0, 4).map(item => ({ id: item.task.id, title: item.task.goal || '委派工作', detail: [item.status, item.detail].filter(Boolean).join(' · '), primary: item.action, secondary: '查看工作' }))}
                onDecide={decision => { const item = attention.find(value => value.task.id === decision.id); if (canOpen && item) onOpen(item.task); }} />
                : <ol className="pw-butler-work-list">{attention.slice(0, 4).map(renderTask)}</ol>}
              {moreWork(attention.length - 4, '待处理事项')}
            </section>}
            {featured && <section className="pw-butler-work-group pw-butler-delivery" aria-label="已整理的结果" data-task-id={featured.task.id}>
              <p className="pw-butler-delivery-eyebrow">管家已汇报</p><h3 dir="auto">{featured.task.goal}</h3>
              <p>执行结果已保存，管家的汇报已回到原话题。</p>
              <div className="pw-butler-delivery-actions"><Button variant="secondary" trailingIcon={IconChevronRight} disabled={!canOpen} onClick={() => onOpen(featured.task)}>查看这份结果</Button>{onArtifacts && <Button variant="ghost" onClick={() => onArtifacts(featured.task)}>文件与版本</Button>}</div>
            </section>}
            {active.length > 0 && <section className="pw-butler-work-group" aria-label="正在跟进"><h3>正在跟进</h3><ol className="pw-butler-work-list">{active.slice(0, 3).map(renderTask)}</ol>{moreWork(active.length - 3, '正在跟进的工作')}</section>}
            {otherRecent.length > 0 && <section className="pw-butler-work-group pw-butler-recent" aria-label="最近记录"><h3>最近记录</h3><ol className="pw-butler-work-list">{otherRecent.slice(0, 3).map(renderTask)}</ol>{moreWork(otherRecent.length - 3, '近期工作')}</section>}
            {!work.length && <p className="pw-butler-work-empty">最近委派工作的进度、需要你决定的事和结果，会显示在这里。</p>}
            {!!work.length && !canOpen && <p className="pw-butler-work-note" role="status">当前暂时无法打开原任务记录。</p>}
          </>}
          <footer className="pw-butler-work-footer"><Button ref={allWorkButton} variant="ghost" className="pw-butler-all-work" trailingIcon={IconChevronRight} disabled={!ready} onClick={onAllWork}>查看我的工作</Button></footer>
        </section>
      </div>
    </div>
  </main>;
}

export default ButlerHome;
