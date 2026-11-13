import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { Accordion, AccordionItem, Button, SearchField } from './forms-library';
import { IconAttention, IconCheckCircle, IconChevronRight, IconClock, IconError, IconEye, IconFolder, IconPauseCircle, IconTerminal } from './icons';
import { DecisionInbox } from './shell-library/DecisionInbox';
import { getRecentTaskProgress } from './task-presentation';
import { workCollectionPresentation, type WorkCollectionFilter } from './work-collection';
import { WorkDirectoryControls, type useWorkDirectory } from './WorkDirectory';
import './project-workspace-overview.css';
import './project-workspace-manga.css';

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
  directory: ReturnType<typeof useWorkDirectory>;
  directoryAvailable: boolean;
  view: ProjectWorkCollectionView;
  onViewChange: (patch: Partial<ProjectWorkCollectionView>) => void;
  canOpenTask: boolean;
  onOpenTask: (id: string) => void;
  onOpenFiles: () => void;
  onOpenPreview: () => void;
  onOpenTerminal: () => void;
  canPreview: boolean;
  onAskButler?: () => void;
  startContent?: ReactNode;
};

export type ProjectWorkCollectionView = { query: string; filter: WorkCollectionFilter; expanded: boolean; selected: string };

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
export function ProjectWorkspaceOverview({ project, directory, directoryAvailable, view, onViewChange, canOpenTask, onOpenTask, onOpenFiles, onOpenPreview, onOpenTerminal, canPreview, onAskButler, startContent }: ProjectWorkspaceOverviewProps) {
  const root = useRef<HTMLElement>(null);
  const hasFilters = Boolean(view.query.trim()) || view.filter !== 'all';
  const openTask = (id: string) => { onViewChange({ selected: id }); onOpenTask(id); };
  // Browsing a project is not execution context. Only a native development
  // context associates a task; names, selected projects and goals never do.
  const work = directory.tasks.filter(task => Boolean(project.id) && task.raw?.developmentContext?.projectId === project.id)
    .sort((a, b) => updatedAt(b) - updatedAt(a))
    .map(task => {
      const presentation = workCollectionPresentation(task);
      const attention = presentation.group === 'attention';
      const progress = getRecentTaskProgress(task);
      const status = presentation.label;
      const detail = task.summary?.trim() || progress.detail || (progress.source !== 'task' ? progress.title : '');
      return { task, attention, status, group: presentation.group, detail: detail === status || detail === task.goal ? '' : detail, action: presentation.actionLabel };
    });
  const attention = work.filter(item => item.attention);
  const active = work.filter(item => item.group === 'running');
  const recent = work.filter(item => item.group === 'ended');
  useLayoutEffect(() => {
    if (!directory.enabled || directory.busy || !directory.loaded || !view.selected || document.activeElement !== document.body) return;
    const row = Array.from(root.current?.querySelectorAll<HTMLElement>('[data-task-id]') || []).find(node => node.dataset.taskId === view.selected);
    row?.querySelector<HTMLElement>('button:not(:disabled)')?.focus({ preventScroll: true });
  }, [directory.enabled, directory.busy, directory.loaded, view.selected]);
  const renderTask = ({ task, attention: needsAttention, status, group, detail, action }: typeof work[number]) => {
    const StatusIcon = needsAttention ? ['paused', 'interrupted'].includes(task.status) ? IconPauseCircle : IconAttention
      : group === 'ended' && task.status === 'completed' ? IconCheckCircle : task.status === 'failed' ? IconError : IconClock;
    const date = updatedDate(task);
    return <li key={task.id} data-task-id={task.id} data-project-task-id={task.id} data-task-state={task.status} data-selected={view.selected === task.id || undefined}>
      <Button variant="ghost" className="pw-project-work-row" disabled={!canOpenTask} aria-label={`${action}：${task.goal || '项目任务'}`} aria-description={[status, detail].filter(Boolean).join('，')} onClick={() => openTask(task.id)}>
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

  return <section ref={root} className="pw-project-overview" aria-label={`${project.name}的项目工作`}>
    <header className="pw-project-overview-heading">
      <div><p className="pw-project-overview-eyebrow">项目工作台</p><h2>{project.name}</h2></div>
      {onAskButler && <Button variant="ghost" size="small" onClick={onAskButler}>问管家</Button>}
    </header>
    <nav className="pw-project-shortcuts" aria-label="项目内容与操作">
      <Button variant="ghost" leadingIcon={IconFolder} onClick={onOpenFiles}>文件</Button>
      <Button variant="ghost" leadingIcon={IconEye} disabled={!canPreview} title={canPreview ? '选择入口，查看页面效果' : '当前宿主尚未提供预览入口'} onClick={onOpenPreview}>预览</Button>
      <Button variant="ghost" leadingIcon={IconTerminal} data-workspace-entry="project-terminal" onClick={onOpenTerminal}>终端</Button>
    </nav>
    <div className="pw-project-overview-layout">
      <div className="pw-project-overview-work">
        <section className="pw-project-work-tools" aria-label="查找这个项目的工作">
          <SearchField value={view.query} onChange={query => onViewChange({ query, selected: '', expanded: false })} isDisabled={!directoryAvailable}
            maxLength={500} aria-label="搜索这个项目保存的工作" placeholder="搜索项目工作目标或摘要…" clearLabel="清空项目工作搜索" />
          <div className="pw-project-work-filters" role="group" aria-label="项目工作状态">
            {([{ id: 'all', label: '全部' }, { id: 'attention', label: '待处理' }, { id: 'running', label: '进行中' }, { id: 'ended', label: '已结束' }] as const).map(option =>
              <Button key={option.id} variant="ghost" aria-pressed={view.filter === option.id} disabled={!directoryAvailable}
                onClick={() => onViewChange({ filter: option.id, selected: '', expanded: false })}>{option.label}</Button>)}
          </div>
        </section>
        {attention.length > 0 && <section className="pw-project-work-group pw-project-work-attention" aria-label="需要你处理">
          <h3><IconAttention size={17} aria-hidden />需要你处理</h3>
          {canOpenTask ? <DecisionInbox key={project.id} compact className="pw-project-decisions" heading="需要你处理" decisions={attention.map(item => ({ id: item.task.id, title: item.task.goal || '项目任务', detail: [item.status, item.detail].filter(Boolean).join(' · '), primary: item.action, secondary: '查看任务' }))} onDecide={decision => openTask(decision.id)} />
            : <ol className="pw-project-work-list">{attention.map(renderTask)}</ol>}
        </section>}
        {active.length > 0 && <section className="pw-project-work-group" aria-label="进行中的项目工作"><h3>进行中</h3><ol className="pw-project-work-list">{active.map(renderTask)}</ol></section>}
        {recent.length > 0 && <section className="pw-project-work-group" aria-label="已结束的项目工作"><h3>{hasFilters ? '已结束' : '最近结果'}</h3><ol className="pw-project-work-list">{(hasFilters ? recent : recent.slice(0, 3)).map(renderTask)}</ol>
          {!hasFilters && recent.length > 3 && <Accordion key={project.id} className="pw-project-work-history" expandedKeys={view.expanded ? ['earlier-project-results'] : []}
            onExpandedChange={keys => onViewChange({ expanded: keys.has('earlier-project-results') })}><AccordionItem id="earlier-project-results" title="更早记录" description={`本次已读取的其余 ${recent.length - 3} 项`}><ol className="pw-project-work-list">{recent.slice(3).map(renderTask)}</ol></AccordionItem></Accordion>}
        </section>}
        {!directoryAvailable && <p className="pw-project-work-unavailable" role="status">当前宿主暂不支持读取项目历史工作，仍可查看项目文件。</p>}
        {directoryAvailable && directory.loaded && !work.length && !directory.busy && !directory.error && <div className="pw-project-work-empty">
          <h3>{directory.hasMore ? '继续查找更早的工作' : directory.hasReadGaps ? '暂未找到可核对的项目工作' : hasFilters ? '没有匹配的项目工作' : '没有查到可读的项目工作'}</h3>
          <p>{directory.hasMore ? '当前已读取范围内还没有匹配项，可以继续查找。' : directory.hasReadGaps ? '部分原记录的项目归属暂不可核对，可以稍后重新读取。' : hasFilters ? '换个关键词，或清除筛选查看这个项目的其他工作。' : '可以开始新工作，也可以先看看已有文件。'}</p>
          {hasFilters && <Button variant="secondary" onClick={() => onViewChange({ query: '', filter: 'all', expanded: false, selected: '' })}>清除筛选</Button>}
        </div>}
        {directoryAvailable && <WorkDirectoryControls directory={directory} />}
        {work.length > 0 && !canOpenTask && <p className="pw-project-work-unavailable" role="status">当前宿主暂不支持打开任务记录。</p>}
        {startContent && <div className="pw-project-new-work">{startContent}</div>}
      </div>

    </div>
  </section>;
}

export default ProjectWorkspaceOverview;
