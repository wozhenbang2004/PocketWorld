import { Accordion, AccordionItem, Button } from './forms-library';
import { IconAttention, IconCheckCircle, IconChevronRight, IconClock, IconError, IconEye, IconFolder, IconPauseCircle, IconTerminal } from './icons';
import { DecisionInbox } from './shell-library/DecisionInbox';
import { conversationWorkActionLabel } from './conversation-work';
import { getRecentTaskProgress, taskNeedsAttention, taskNeedsReconciliation } from './task-presentation';
import './project-workspace-overview.css';
import type { ReactNode } from 'react';

export type ProjectWorkspaceTask = {
  id: string;
  goal: string;
  status: string;
  summary?: string;
  updatedAt?: number;
  raw?: Record<string, any>;
};

export type ProjectWorkspaceOverviewProps = {
  project: { id: string; name: string };
  tasks: ProjectWorkspaceTask[];
  canOpenTask: boolean;
  onOpenTask: (id: string) => void;
  onOpenFiles: () => void;
  onOpenPreview: () => void;
  onOpenTerminal: () => void;
  canPreview: boolean;
  startContent?: ReactNode;
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
const updatedAt = (task: ProjectWorkspaceTask) => typeof task.updatedAt === 'number' && Number.isFinite(task.updatedAt) ? task.updatedAt : 0;
const updatedDate = (task: ProjectWorkspaceTask) => {
  const date = new Date(updatedAt(task));
  return updatedAt(task) > 0 && Number.isFinite(date.getTime()) ? date : null;
};

/** Product adaptation of the approved Affect workspace Folder composition
 * (upstream/affect/src/demos/screens/workspace.tsx), combined with the retained
 * MIT DecisionInbox. The approved Agent Run image informs work/decision/result
 * hierarchy; it is not a claim to Affect Pro source. All task records and routes
 * come from the host. This view neither starts work nor settles a decision. */
export function ProjectWorkspaceOverview({ project, tasks, canOpenTask, onOpenTask, onOpenFiles, onOpenPreview, onOpenTerminal, canPreview, startContent }: ProjectWorkspaceOverviewProps) {
  // Browsing a project is not execution context. Only a native development
  // context associates a task; names, selected projects and goals never do.
  const work = tasks.filter(task => Boolean(project.id) && task.raw?.developmentContext?.projectId === project.id)
    .sort((a, b) => updatedAt(b) - updatedAt(a))
    .map(task => {
      const reconcile = taskNeedsReconciliation(task);
      const attention = taskNeedsAttention(task) || !activeStates.has(task.status) && !endedStates.has(task.status);
      const progress = getRecentTaskProgress(task);
      const status = reconcile ? '操作结果待核对' : statusLabels[task.status] || '状态待核对';
      const detail = task.summary?.trim() || progress.detail || (progress.source !== 'task' ? progress.title : '');
      return { task, attention, status, detail: detail === status || detail === task.goal ? '' : detail, action: conversationWorkActionLabel(task) };
    });
  const attention = work.filter(item => item.attention);
  const active = work.filter(item => !item.attention && activeStates.has(item.task.status));
  const recent = work.filter(item => !item.attention && endedStates.has(item.task.status));
  const renderTask = ({ task, attention: needsAttention, status, detail, action }: typeof work[number]) => {
    const StatusIcon = needsAttention ? ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : IconAttention
      : task.status === 'completed' ? IconCheckCircle : task.status === 'failed' ? IconError : IconClock;
    const date = updatedDate(task);
    return <li key={task.id} data-project-task-id={task.id} data-task-state={task.status}>
      <Button variant="ghost" className="pw-project-work-row" disabled={!canOpenTask} aria-label={`${action}：${task.goal || '项目任务'}`} aria-description={[status, detail].filter(Boolean).join('，')} onClick={() => onOpenTask(task.id)}>
        <span className="pw-project-work-mark" data-attention={needsAttention || undefined}><StatusIcon size={19} aria-hidden /></span>
        <span className="pw-project-work-copy">
          <span className="pw-project-work-meta"><span>{status}</span>{date && <time dateTime={date.toISOString()}>{date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}</span>
          <strong dir="auto">{task.goal || '项目任务'}</strong>
          {detail && <span className="pw-project-work-detail" dir="auto">{detail}</span>}
        </span>
        <IconChevronRight size={17} className="pw-project-work-chevron" aria-hidden />
      </Button>
    </li>;
  };

  return <section className="pw-project-overview" aria-label={`${project.name}的项目工作`}>
    <header className="pw-project-overview-heading">
      <h2>项目工作</h2>
    </header>
    <div className="pw-project-overview-layout">
      <div className="pw-project-overview-work">
        {startContent}
        {attention.length > 0 && <section className="pw-project-work-group pw-project-work-attention" aria-label="需要你处理">
          <h3><IconAttention size={17} aria-hidden />需要你处理</h3>
          {canOpenTask ? <DecisionInbox key={project.id} compact className="pw-project-decisions" heading="需要你处理" decisions={attention.map(item => ({ id: item.task.id, title: item.task.goal || '项目任务', detail: [item.status, item.detail].filter(Boolean).join(' · '), primary: item.action, secondary: '查看任务' }))} onDecide={decision => onOpenTask(decision.id)} />
            : <ol className="pw-project-work-list">{attention.map(renderTask)}</ol>}
        </section>}
        {active.length > 0 && <section className="pw-project-work-group" aria-label="进行中的项目工作"><h3>进行中</h3><ol className="pw-project-work-list">{active.map(renderTask)}</ol></section>}
        {recent.length > 0 && <section className="pw-project-work-group" aria-label="最近的项目结果"><h3>最近结果</h3><ol className="pw-project-work-list">{recent.slice(0, 3).map(renderTask)}</ol>
          {recent.length > 3 && <Accordion key={project.id} className="pw-project-work-history"><AccordionItem id="earlier-project-results" title="更早记录" description={`本次已读取的其余 ${recent.length - 3} 项`}><ol className="pw-project-work-list">{recent.slice(3).map(renderTask)}</ol></AccordionItem></Accordion>}
        </section>}
        {!work.length && <div className="pw-project-work-empty">{startContent ? <p>这个项目的任务和结果会留在这里。</p> : <><IconFolder size={28} aria-hidden /><h3>先看看项目内容</h3><p>当前读取范围内没有这个项目的关联任务。打开文件查看已有内容，需要手动操作时，再准备私人工作副本。</p></>}</div>}
        {work.length > 0 && <p className="pw-project-work-unavailable">以上为本次已读取的项目任务。</p>}
        {work.length > 0 && !canOpenTask && <p className="pw-project-work-unavailable" role="status">当前宿主暂不支持打开任务记录。</p>}
      </div>
      <aside className="pw-project-overview-tools" aria-label="项目内容与操作">
        <h3>项目内容</h3>
        <nav aria-label="查看项目内容">
          <Button variant="ghost" className="pw-project-tool-row" data-primary={!work.length || undefined} leadingIcon={IconFolder} trailingIcon={IconChevronRight} onClick={onOpenFiles}><span><strong>项目文件</strong><small>查看已保存的项目内容</small></span></Button>
          <Button variant="ghost" className="pw-project-tool-row" leadingIcon={IconEye} trailingIcon={IconChevronRight} disabled={!canPreview} onClick={onOpenPreview}><span><strong>项目预览</strong><small>{canPreview ? '选择入口，查看页面效果' : '当前宿主尚未提供预览入口'}</small></span></Button>
        </nav>
        <div className="pw-project-overview-manual"><h3>需要手动操作时</h3><Button variant="ghost" className="pw-project-tool-row" data-workspace-entry="project-terminal" leadingIcon={IconTerminal} trailingIcon={IconChevronRight} onClick={onOpenTerminal}><span><strong>手动终端</strong><small>在私人工作副本中操作</small></span></Button></div>
      </aside>
    </div>
  </section>;
}

export default ProjectWorkspaceOverview;
