import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Button, SearchField, SelectField, Accordion, AccordionItem } from './forms-library';
import { DataOperationHeading, DataOperationRow, useDataSettingsJourney } from './DataSettingsJourney';
import { categoryLabels, toolSource, toolTitle, type CatalogTool } from './tool-catalog';
import { IconPhone, IconSearch, IconTerminal, IconFolder, IconDevices, IconTeam, IconClock, IconPlugin, IconSkill, IconFileSettings, IconImage } from './icons';
import './tool-catalog.css';

const categoryIcons: Record<string, typeof IconPhone> = { phone: IconPhone, semantic: IconPhone, browser: IconSearch, memory: IconSearch, project: IconTerminal, ssh: IconTerminal, attachments: IconFolder, workspace: IconFolder, remote: IconDevices, devices: IconDevices, teams: IconTeam, automation: IconClock, plugins: IconPlugin, skills: IconSkill, media: IconImage };

type Props = {
  tools: CatalogTool[]; error?: string; loading?: boolean; initialTool?: string;
  onReload?: () => void; status?: (tool: CatalogTool) => string; detail?: (tool: CatalogTool) => ReactNode;
  registerBack?: (back: (() => boolean) | null) => void;
};
/** Adapts the retained Affect Prompt Library list/detail relationship and
 * existing BoardUI search/select/operation rows. All rows are native metadata. */
export function ToolCatalogBrowser({ tools, error, loading, initialTool, onReload, status, detail, registerBack }: Props) {
  const journey = useDataSettingsJourney(initialTool || 'list');
  const [query, setQuery] = useState(''), [category, setCategory] = useState('all'), [source, setSource] = useState('all'), [limit, setLimit] = useState(24);
  const drawerPosition = useRef(0), previous = useRef(journey.view);
  const open = (name: string) => { drawerPosition.current = journey.root.current?.closest('.pw-drawer-body')?.scrollTop || 0; journey.open(name); };
  useLayoutEffect(() => {
    if (previous.current === journey.view) return; previous.current = journey.view;
    const root = journey.root.current, scroller = root?.closest('.pw-drawer-body');
    if (root && scroller && !root.closest('.pw-secondary-scroll')) scroller.scrollTop = journey.view === 'list' ? drawerPosition.current : Math.max(0, scroller.scrollTop + root.getBoundingClientRect().top - scroller.getBoundingClientRect().top - 8);
  }, [journey.view]);
  const selected = tools.find(tool => tool.name === journey.view);
  const description = selected?.description || '';
  const firstSentence = description.split('。')[0];
  const summary = firstSentence.length > 180 ? `${firstSentence.slice(0, 180)}…` : firstSentence;
  useEffect(() => { registerBack?.(journey.back); return () => registerBack?.(null); }, [registerBack, journey.view]);
  useEffect(() => { setLimit(24); }, [query, category, source]);
  const categories = Array.from(new Set(tools.map(tool => tool.category))).sort((a, b) => (categoryLabels[a] || a).localeCompare(categoryLabels[b] || b, 'zh'));
  const search = query.trim().toLowerCase();
  const listed = tools.filter(tool => (category === 'all' || category === tool.category)
    && (source === 'all' || (source === 'plugin' ? tool.source === 'installed_plugin' : source === 'rule' ? tool.source === 'saved_rule' : source === 'task' ? tool.source === 'original_task' : !['installed_plugin', 'saved_rule', 'original_task'].includes(tool.source)))
    && (!search || `${toolTitle(tool)} ${tool.name} ${tool.description} ${categoryLabels[tool.category] || tool.category} ${toolSource(tool)}`.toLowerCase().includes(search)));
  return <div className="pw-tool-browser" ref={journey.root} tabIndex={-1}>
    {selected ? <section className="pw-tool-detail" aria-label="工具详情">
      <DataOperationHeading title={toolTitle(selected)} parent="工具列表" description={categoryLabels[selected.category] || selected.category} onBack={() => journey.open('list')} />
      <div className="pw-tool-overview pw-material-content"><p className="pw-tool-description">{summary || '此定义暂未提供用途说明。'}</p><dl className="pw-tool-metadata"><div><dt>来源</dt><dd>{toolSource(selected)}</dd></div><div><dt>审批</dt><dd>{selected.configurable ? '可单独设置规则' : '由原生流程控制'}</dd></div><div><dt>工具名</dt><dd className="pw-tool-identifier">{selected.name}</dd></div></dl></div>
      <p className="pw-tool-boundary">实际调用取决于任务接入和授权。审批规则不会自动连接服务或扩大权限。</p>
      {detail?.(selected)}
      {description && description !== summary && <Accordion variant="divided" defaultExpandedKeys={[]}><AccordionItem id="tool-definition" title="完整工具说明与限制"><p className="pw-tool-description">{description}</p></AccordionItem></Accordion>}
    </section> : <>
      {tools.length > 0 && <div className="pw-tool-toolbar pw-material-content">
        <SearchField aria-label="按用途或名称搜索工具" placeholder="搜索用途、工具名…" value={query} onChange={setQuery} />
        <div className="pw-tool-filters">
          <SelectField label="用途" compactValue value={category} onChange={setCategory} options={[{ id: 'all', label: '全部用途' }, ...categories.map(id => ({ id, label: categoryLabels[id] || id }))]} />
          <SelectField label="来源" compactValue value={source} onChange={setSource} options={[{ id: 'all', label: '全部来源' }, { id: 'builtin', label: '内置定义' }, { id: 'plugin', label: '已注册插件' }, ...(tools.some(tool => tool.source === 'saved_rule') ? [{ id: 'rule', label: '仅有规则' }] : []), ...(tools.some(tool => tool.source === 'original_task') ? [{ id: 'task', label: '原任务登记' }] : [])]} />
        </div>
      </div>}
      {loading && <p className="pw-tool-status" role="status">正在读取工具定义…</p>}
      {error && <div className="pw-tool-status" role="alert"><p>{error}</p>{onReload && <Button variant="secondary" onClick={onReload}>重新读取目录</Button>}</div>}
      {!loading && <p className="pw-tool-result-count"><span>{listed.length} 个工具{search || category !== 'all' || source !== 'all' ? '符合筛选' : ''}</span><span>可用性由具体任务核对</span></p>}
      {!loading && listed.length === 0 && <div className="pw-tool-status"><p>{tools.length ? '没有匹配的工具，试试另一种用途或清除筛选。' : '尚未读到工具定义。可以先管理来源，或按实际工具名设置规则。'}</p>{tools.length > 0 && <Button variant="ghost" onClick={() => { setQuery(''); setCategory('all'); setSource('all'); }}>清除筛选</Button>}</div>}
      <div className="pw-tool-list pw-material-content">{listed.slice(0, limit).map(tool => <DataOperationRow key={tool.name} id={tool.name} title={toolTitle(tool)} icon={categoryIcons[tool.category] || IconFileSettings} detail={status ? status(tool) : `${categoryLabels[tool.category] || tool.category} · ${toolSource(tool)}`} onOpen={open} />)}</div>
      {listed.length > limit && <Button variant="secondary" onClick={() => setLimit(value => value + 24)}>再显示 {Math.min(24, listed.length - limit)} 项</Button>}
      <Accordion variant="divided" defaultExpandedKeys={[]}><AccordionItem id="tool-boundaries" title="为什么有工具却不一定能用？"><p className="pw-tool-boundary">这里仅列定义。来源安装与连接、工作配置选择、原任务范围、设备权限和这次动作的审批需要分别满足。实际结果以原任务回执为准。</p></AccordionItem></Accordion>
    </>}
  </div>;
}
