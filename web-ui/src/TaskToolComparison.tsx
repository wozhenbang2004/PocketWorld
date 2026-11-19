import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SearchField } from './forms-library';
import { DataOperationHeading, DataOperationRow } from './DataSettingsJourney';
import { decisionLabels, toolTitle, type ToolDecision } from './tool-catalog';

type CurrentPolicy = { appliesToTask?: boolean; mode?: string; modeSource?: string; revision?: number; observedAt?: number; rules?: Record<string, { value: string; source: string }>; message?: string };
export type TaskToolRecord = { state?: string; taskId?: string; runId?: string; epoch?: number; names?: string[]; observedAt?: number; message?: string; policy?: CurrentPolicy; selection?: { kind: string; profileId?: string; revision?: number } };
export function taskRuleText(name: string, policy?: CurrentPolicy) {
  if (policy?.appliesToTask === false) return '专用原生范围，不套用个人策略';
  if (policy?.appliesToTask !== true) return '当前审批策略尚未核对';
  const rule = policy.rules?.[name];
  if (rule?.value && (rule.source === 'conversation' || rule.source === 'global') && Object.hasOwn(decisionLabels, rule.value)) return `${rule.source === 'conversation' ? '任务所属对话' : '全局'}规则 · ${decisionLabels[rule.value as ToolDecision]}`;
  const mode = policy.mode === 'full_access' ? '直接允许可配置审批' : policy.mode === 'auto_review' ? '更改类动作由 AI 审批' : policy.mode === 'custom' ? '沿用原生要求' : '';
  return mode && (policy.modeSource === 'conversation' || policy.modeSource === 'global') ? `${policy.modeSource === 'conversation' ? '任务所属对话' : '全局'}默认 · ${mode}` : '当前审批策略尚未核对';
}
export type TaskCatalogReading = {
  record: TaskToolRecord;
  preparation: { summary: string; description: string; items: { key: string; title: string; detail: string; requiredBy: string; state: 'gap' | 'unknown' | 'ready' }[] };
};
type Props = {
  initialTool: string; read: (current: () => boolean) => Promise<TaskCatalogReading>;
  onClose: () => void; registerBack: (handler: (() => boolean) | null) => void;
};
const nativeName = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
export function recordedNames(record?: TaskToolRecord): string[] | undefined {
  return record?.state === 'recorded' && typeof record.runId === 'string' && !!record.runId && Number.isSafeInteger(record.epoch)
    && Array.isArray(record.names) && record.names.every(name => typeof name === 'string' && nativeName.test(name)) && new Set(record.names).size === record.names.length ? record.names : undefined;
}
const preparationLabel = { gap: '已知缺项', unknown: '待核对', ready: '已准备' } as const;
const titleOf = (name: string) => toolTitle({ name, category: '', description: '', source: '', configurable: false });

/** Read-only cross-check in a live original task. No catalog or record survives this view. */
export function TaskToolComparison({ initialTool, read, onClose, registerBack }: Props) {
  const [reading, setReading] = useState<TaskCatalogReading | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState(initialTool ? 'tool:' + initialTool : ''), [query, setQuery] = useState('');
  const [needLimit, setNeedLimit] = useState(4), [toolLimit, setToolLimit] = useState(12);
  const root = useRef<HTMLDivElement>(null), lastList = useRef({ top: 0, name: '' }), prev = useRef<string | undefined>(undefined);
  const generation = useRef(0), pending = useRef(false), readRef = useRef(read); readRef.current = read;
  const reload = async () => {
    if (pending.current || document.hidden) return;
    const ticket = ++generation.current, current = () => ticket === generation.current && !document.hidden;
    pending.current = true; setBusy(true); setError(''); setReading(null);
    try { const value = await readRef.current(current); if (current()) setReading(value); }
    catch (e) { if (current()) setError(e instanceof Error ? e.message : '这次没有读到原任务，请返回核对。'); }
    finally { if (current()) { pending.current = false; setBusy(false); } }
  };
  useLayoutEffect(() => { return () => { ++generation.current; pending.current = false; }; }, []);
  useEffect(() => { void reload(); }, []);
  const back = () => { if (selected && !initialTool) setSelected(''); else onClose(); return true; };
  useLayoutEffect(() => { registerBack(back); return () => registerBack(null); }, [selected, initialTool, onClose, registerBack]);
  useLayoutEffect(() => {
    if (prev.current === selected) return;
    const entering = prev.current === undefined; prev.current = selected;
    const node = root.current, scroller = node?.closest('.pw-task-body'); if (!node || !scroller) return;
    const returning = !entering && !selected;
    const row = returning && Array.from(node.querySelectorAll<HTMLElement>('[data-operation-entry]')).find(el => el.dataset.operationEntry === lastList.current.name);
    scroller.scrollTop = returning ? lastList.current.top : Math.max(0, scroller.scrollTop + node.getBoundingClientRect().top - scroller.getBoundingClientRect().top - 8);
    (row || node.querySelector<HTMLElement>('[data-operation-heading]') || node).focus({ preventScroll: true });
  }, [selected]);
  const record = reading?.record, names = recordedNames(record);
  const tools = (names || []).map(name => ({ key: 'tool:' + name, name, title: titleOf(name), detail: taskRuleText(name, record?.policy) }));
  const tool = tools.find(row => row.key === selected);
  const needs = reading?.preparation.items || [], need = needs.find(row => row.key === selected);
  const search = query.trim().toLowerCase();
  const matchedTools = tools.filter(row => (row.title + ' ' + row.name).toLowerCase().includes(search));
  const matchedNeeds = needs.filter(row => (row.title + ' ' + row.detail + ' ' + row.requiredBy).toLowerCase().includes(search));
  const open = (key: string) => { lastList.current = { top: root.current?.closest('.pw-task-body')?.scrollTop || 0, name: key }; setSelected(key); };
  return <div ref={root} tabIndex={-1} className="pw-tool-browser" aria-label="这次工作的能力目录">
    <DataOperationHeading title={tool?.title || need?.title || '这次工作的能力'} parent={initialTool ? '原工具详情' : selected ? '本次能力目录' : '本轮工具'} description="" onBack={back}/>
    {busy && <p role="status" className="pw-tool-status">正在核对原任务的执行记录和工作配置…</p>}
    {error && <p role="alert" className="pw-tool-status">{error}</p>}
    {reading && <>
      {selected ? tool ? <div className="pw-tool-detail">
        <dl className="pw-facts"><div><dt>执行记录</dt><dd>读取时，本轮已登记这个工具名。</dd></div><div><dt>当前审批规则</dt><dd>{tool.detail}</dd></div></dl>
        <p className="pw-tool-identifier">{tool.name}</p>
        <p className="pw-tool-boundary">登记记录不包含原安装版本或具体动作的许可。无法凭工具名判断当前来源或批准过哪次调用，请在原任务的授权与回执中核对。</p>
      </div> : need ? <div className="pw-tool-detail"><p className="pw-tool-description">{preparationLabel[need.state]}：{need.detail}</p>{need.requiredBy && <p className="pw-tool-boundary">来自原工作配置：{need.requiredBy}</p>}<p className="pw-tool-boundary">这是所选技能的准备情况，不是本轮工具清单，也不是某次调用的批准回执。</p></div> : <p role="status" className="pw-tool-status">同一轮最新记录已不能核对这一项，当前权限未知。没有换成其他工具。</p> : <>
        <p className="pw-tool-boundary">{names ? `读取时，这轮执行登记了 ${names.length} 个工具。` : '本轮登记清单尚不可核对。'} {reading.preparation.summary}。</p>
        <SearchField aria-label="搜索本次工具、技能或准备事项" placeholder="在这次工作中查找…" value={query} onChange={value => { setQuery(value); setNeedLimit(4); setToolLimit(12); }}/>
        <section aria-label="原工作配置的准备情况"><h3>所选技能的准备情况</h3><p className="pw-tool-boundary">{reading.preparation.description}</p>
          {matchedNeeds.length > 0 && <div className="pw-tool-list">{matchedNeeds.slice(0, needLimit).map(row => <DataOperationRow key={row.key} id={row.key} title={row.title} detail={preparationLabel[row.state] + ' · ' + row.detail} onOpen={open}/>)}</div>}
          {matchedNeeds.length > needLimit && <Button variant="ghost" onClick={() => setNeedLimit(n => n + 12)}>再显示 {Math.min(12, matchedNeeds.length - needLimit)} 个准备项</Button>}
          {search && needs.length > 0 && !matchedNeeds.length && <p className="pw-tool-status">准备事项中没有匹配项。</p>}
        </section>
        <section aria-label="本轮已登记的工具"><h3>本轮已登记</h3>
          {matchedTools.length > 0 ? <div className="pw-tool-list">{matchedTools.slice(0, toolLimit).map(row => <DataOperationRow key={row.key} id={row.key} title={row.title} detail={row.detail} onOpen={open}/>)}</div>
            : <p className="pw-tool-status">{!names ? '原记录不可读，本轮有无此项未知。' : search ? '本轮登记记录中没有匹配的工具。' : '本轮通用工具登记为零。'} 是否已安装、是否另有专用授权均未知。</p>}
          {matchedTools.length > toolLimit && <Button variant="ghost" onClick={() => setToolLimit(n => n + 24)}>再显示 {Math.min(24, matchedTools.length - toolLimit)} 个工具</Button>}
        </section>
        {search && !matchedTools.length && !matchedNeeds.length && <Button variant="ghost" onClick={() => setQuery('')}>清除搜索</Button>}
      </>}
      <Accordion variant="divided"><AccordionItem id="task-catalog-origin" title="核对本次读取的依据"><p className="pw-tool-identifier">任务 {record?.taskId} · 执行 {record?.runId || '未记录'}{Number.isSafeInteger(record?.epoch) ? ` / ${record?.epoch}` : ''}</p>{record?.observedAt && <p className="pw-tool-boundary">原记录读取：{new Date(record.observedAt).toLocaleString()}</p>}<p className="pw-tool-boundary">只查看这次工作能证明的内容。没有读取本机通用目录；缺少记录的工具，其来源与安装授权未知。</p></AccordionItem></Accordion>
    </>}
    <Button variant="ghost" disabled={busy} onClick={() => void reload()}>重新核对原任务</Button>
  </div>;
}
