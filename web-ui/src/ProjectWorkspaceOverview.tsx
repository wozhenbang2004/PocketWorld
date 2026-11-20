import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { Accordion, AccordionItem, Button, SearchField } from './forms-library';
import { IconAttention, IconChevronRight, IconFile, IconTerminal } from './icons';
import { readFollowUp, WorkQueueRow, type WorkEntry, type WorkReading } from './WorkFollowUp';
import type { WorkCollectionFilter } from './work-collection';
import { WorkDirectoryControls, type useWorkDirectory } from './WorkDirectory';
import { ProjectSavedWebLink, type FindSavedBuildWeb } from './ProjectSavedBuildWeb';
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
  canReview: boolean;
  canContinue: (task: ProjectWorkspaceTask) => boolean;
  onOpenTask: (id: string, entry: ProjectWorkEntry, query: string) => void;
  onOpenTerminal: () => void;
  onAskButler?: () => void;
  onEditImported?: () => void;
  importedSourceFirst?: boolean;
  startContent?: ReactNode;
  savedWebContent?: ReactNode;
};

export type ProjectWorkEntry = WorkEntry | 'outcomes';
export type ProjectWorkCollectionView = { query: string; filter: WorkCollectionFilter; expanded: boolean; selected: string; selectedEntry?: ProjectWorkEntry };

/** Project work directory; saved Web versions separately use exact original B4 receipts. */
export function ProjectWorkspaceOverview({ project, directory, directoryAvailable, view, onViewChange, canOpenTask, canReview, canContinue, onOpenTask, onOpenTerminal, onAskButler, onEditImported, importedSourceFirst = false, startContent, savedWebContent }: ProjectWorkspaceOverviewProps) {
  const root = useRef<HTMLElement>(null);
  const savedWebLink = useRef<FindSavedBuildWeb|null>(null);
  const hasFilters = Boolean(view.query.trim()) || view.filter !== 'all';
  const visit = (task: ProjectWorkspaceTask, entry: ProjectWorkEntry) => {
    if (!canOpenTask) return;
    onViewChange({ selected: task.id, selectedEntry: entry });
    onOpenTask(task.id, entry, view.query.trim());
  };
  const work = directory.tasks.filter(task => project.id && task.raw?.developmentContext?.projectId === project.id)
    .map(task => readFollowUp(task, canReview, canContinue(task)));
  const attention = work.filter(item => item.group === 'attention');
  const active = work.filter(item => item.group === 'running');
  const recent = work.filter(item => item.group === 'ended');
  useLayoutEffect(() => {
    if (!directory.enabled || directory.busy || !directory.loaded || !view.selected || document.activeElement !== document.body) return;
    const row = Array.from(root.current?.querySelectorAll<HTMLElement>('[data-project-task-id]') || []).find(node => node.dataset.projectTaskId === view.selected);
    const buttons = Array.from(row?.querySelectorAll<HTMLElement>('button:not(:disabled)') || []);
    (buttons.find(button => button.dataset.workEntry === view.selectedEntry) || buttons[0])?.focus({ preventScroll: true });
  }, [directory.enabled, directory.busy, directory.loaded, view.selected, view.selectedEntry]);
  const renderTask = (item: WorkReading) => {
    // Stopping/failing work does not erase files saved before that point.
    // Without a receipt we offer to inspect the original, not claim a file exists.
    const fallback = ['failed', 'paused', 'stopped'].includes(item.kind) ? '核对原工作成果' : '';
    const mainIsResult = !!item.receipt && item.next.entry === 'overview' && item.next.label === item.receipt.label;
    const resultAction = item.readable && !mainIsResult ? item.receipt?.label || (item.next.entry !== 'overview' ? fallback : '') : '';
    return <li key={item.task.id} data-project-task-id={item.task.id} data-selected={view.selected === item.task.id || undefined}>
      <WorkQueueRow item={item} canOpen={canOpenTask} visit={(task, entry) => visit(task, mainIsResult ? 'outcomes' : entry)}/>
      {resultAction && <Button variant="ghost" size="small" className="pw-project-result-link" leadingIcon={IconFile}
        data-work-entry="outcomes" aria-label={item.goal + '：' + resultAction} disabled={!canOpenTask}
        onClick={() => visit(item.task, 'outcomes')}>{resultAction}</Button>}
    </li>;
  };

  const askButler = onAskButler && <Button variant="ghost" size="small" onClick={onAskButler}>问管家</Button>;
  const tools = directoryAvailable && <section key="work-search" className="pw-project-work-tools" aria-label="查找这个项目的工作">
    <SearchField value={view.query} onChange={query => onViewChange({ query, selected: '', expanded: false })}
      maxLength={500} aria-label="搜索这个项目保存的工作" placeholder="搜索项目工作目标或摘要…" clearLabel="清空项目工作搜索" />
    <div className="pw-project-work-filters" role="group" aria-label="项目工作状态">
      {([{ id: 'all', label: '全部' }, { id: 'attention', label: '待处理' }, { id: 'running', label: '进行中' }, { id: 'ended', label: '已结束' }] as const).map(option =>
        <Button key={option.id} variant="ghost" aria-pressed={view.filter === option.id}
          onClick={() => onViewChange({ filter: option.id, selected: '', expanded: false })}>{option.label}</Button>)}
    </div>
  </section>;
  const needsOwner = attention.length > 0 && <section key="work-attention" className="pw-project-work-group pw-project-work-attention" aria-label="需要你处理">
    <h3><IconAttention size={17} aria-hidden />需要你处理</h3>
    <ol className="pw-project-work-list pw-depth-tasks">{attention.map(renderTask)}</ol>
  </section>;
  const running = active.length > 0 && <section key="work-running" className="pw-project-work-group" aria-label="进行中的项目工作"><h3>进行中</h3><ol className="pw-project-work-list pw-depth-tasks">{active.map(renderTask)}</ol></section>;
  const start = startContent && <section key="work-start" className="pw-project-new-work" aria-label="开始或继续项目工作">{startContent}</section>;
  // Same keyed launcher under the same provider in either order. Reordering the
  // directory never starts a second native file, setup or review window.
  const sections = importedSourceFirst ? [needsOwner, start, tools, running] : [tools, needsOwner, running, start];
  return <section ref={root} className="pw-project-overview" aria-label={`${project.name}的项目工作`}>
    <header className="pw-project-overview-heading">
      <div><p className="pw-project-overview-eyebrow">项目工作台</p><h2>{project.name}</h2></div>
      {onEditImported ? <div className="pw-action-row">
        <Button variant="primary" leadingIcon={IconFile} onClick={onEditImported}>编辑导入源码</Button>
        {askButler}
      </div> : askButler}
    </header>
    <div className="pw-project-overview-layout">
      <div className="pw-project-overview-work" data-imported-source-first={importedSourceFirst || undefined}>
        <ProjectSavedWebLink.Provider value={savedWebContent ? savedWebLink : null}>
          {sections}
          {savedWebContent && <section className="pw-project-saved-web" aria-label="找回这个项目的已保存网页">{savedWebContent}</section>}
        </ProjectSavedWebLink.Provider>
        {recent.length > 0 && <section className="pw-project-work-group" aria-label="已结束的项目工作"><h3>{hasFilters ? '已结束' : '最近结束'}</h3><ol className="pw-project-work-list pw-depth-tasks">{(hasFilters ? recent : recent.slice(0, 3)).map(renderTask)}</ol>
          {!hasFilters && recent.length > 3 && <Accordion key={project.id} className="pw-project-work-history" expandedKeys={view.expanded ? ['earlier-project-results'] : []}
            onExpandedChange={keys => onViewChange({ expanded: keys.has('earlier-project-results') })}><AccordionItem id="earlier-project-results" title="更早记录" description={`本次已读取的其余 ${recent.length - 3} 项`}><ol className="pw-project-work-list pw-depth-tasks">{recent.slice(3).map(renderTask)}</ol></AccordionItem></Accordion>}
        </section>}
        {!directoryAvailable && <p className="pw-project-work-unavailable" role="status">当前宿主暂不支持读取项目历史工作，仍可查看项目文件。</p>}
        {directoryAvailable && directory.loaded && !work.length && !directory.busy && !directory.error && <div className="pw-project-work-empty">
          <h3>{directory.hasMore ? '继续查找更早的工作' : directory.hasReadGaps ? '暂未找到可核对的项目工作' : hasFilters ? '没有匹配的项目工作' : '没有查到可读的项目工作'}</h3>
          <p>{directory.hasMore ? '当前已读取范围内还没有匹配项，可以继续查找。' : directory.hasReadGaps ? '部分原记录的项目归属暂不可核对，可以稍后重新读取。' : hasFilters ? '换个关键词，或清除筛选查看这个项目的其他工作。' : '可以开始新工作，也可以先看看已有文件。'}</p>
          {hasFilters && <Button variant="secondary" onClick={() => onViewChange({ query: '', filter: 'all', expanded: false, selected: '' })}>清除筛选</Button>}
        </div>}
        {directoryAvailable && <WorkDirectoryControls directory={directory} />}
        {work.length > 0 && !canOpenTask && <p className="pw-project-work-unavailable" role="status">当前宿主暂不支持打开任务记录。</p>}
        <Button className="pw-project-terminal-link" variant="ghost" size="small" leadingIcon={IconTerminal} trailingIcon={IconChevronRight}
          data-workspace-entry="project-terminal" onClick={onOpenTerminal}>项目终端</Button>
      </div>

    </div>
  </section>;
}

export default ProjectWorkspaceOverview;
