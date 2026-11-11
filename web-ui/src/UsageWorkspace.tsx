import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type CSSProperties, type ReactNode } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, SearchField } from './forms-library';
import { Select, SelectItem } from './forms-library/boardui/components/base/select/select';
import { IconRefresh, IconChevronDown, IconChevronLeft, IconChevronRight, IconFileText } from './icons';
import { useSurfaceEnter } from './library/surface-motion';
import { WorkingStatus } from './library/journey-motion';
import { countCoverage, countLabel, isPartialCount, metricLabels, purposeLabel, rateLabel, tokenNumber, usageRange, usageReport, type UsageCount, type UsageMetric, type UsageQuery, type UsageReport, type UsageSummary } from './usage-metrics';
import './usage-workspace.css';

type Back = (handler: (() => boolean) | null) => void;
type Props = {
  supported: (action: string) => boolean; records?: ReactNode; diagnostics?: ReactNode; registerTransientBack?: Back;
  renderBudget?: (onEditingChange: (value: boolean) => void, registerBack: Back) => ReactNode;
};
type Dimension = 'model' | 'purpose' | 'conversation';
type View = { kind: 'overview' | 'trends' | 'analysis' | 'group' | 'metrics' | 'records' | 'budget' | 'diagnostics' | 'about'; day?: string; groupKey?: string; dimension?: Dimension };
type OwnedReport = { owner: string; value: UsageReport };
type TrailEntry = { view: View; scroll: number; focus: string | null };
const periods = [7, 30, 90];
const chartMetrics: UsageMetric[] = ['total', 'input', 'output', 'cacheRead'];
const dimensionNames: Record<Dimension, string> = { model: '按模型', purpose: '按用途', conversation: '按会话' };
const fullDate = (day: string) => /^\d{4}-\d{2}-\d{2}$/.test(day) ? `${Number(day.slice(5, 7))}月${Number(day.slice(8))}日` : day;
const conciseDate = (day: string) => /^\d{4}-\d{2}-\d{2}$/.test(day) ? `${Number(day.slice(5, 7))}/${Number(day.slice(8))}` : day;
const ownerTicket = () => {
  const view = bridge.getState().view as Record<string, unknown> | undefined;
  return JSON.stringify([bridge.getDocumentEpoch(), view?.section || '', view?.taskId || '', view?.resumeRevision || 0]);
};
const sameQuery = (a: UsageQuery, b: UsageQuery) => a.fromInclusive === b.fromInclusive && a.toExclusive === b.toExclusive && a.zoneId === b.zoneId;
const groupKey = (row: UsageSummary, dimension: Dimension) => JSON.stringify(dimension === 'model' ? [row.provider, row.model] : dimension === 'purpose' ? [row.purpose] : [row.conversationId]);
const groupTitle = (row: UsageSummary, dimension: Dimension) => dimension === 'model' ? row.model || '未记录模型' : dimension === 'purpose' ? purposeLabel(row.purpose) : row.title || (row.conversationId ? `对话 ${row.conversationId.slice(0, 8)}` : '未关联会话');
const groupSubtitle = (row: UsageSummary, dimension: Dimension) => dimension === 'model' ? row.provider || '服务未记录' : dimension === 'purpose' ? `${row.attempts} 次调用` : row.latestTaskLabel ? `最近任务：${row.latestTaskLabel}` : '任务与辅助调用';
const groupRows = (report: UsageReport, dimension: Dimension) => dimension === 'model' ? report.byModel : dimension === 'purpose' ? report.byPurpose : report.byConversation;
const sortedGroups = (report: UsageReport, dimension: Dimension) => [...groupRows(report, dimension)].sort((a, b) => (b.normalized.total.knownSubtotal ?? -1) - (a.normalized.total.knownSubtotal ?? -1));
const isPartialReport = (report: UsageReport) => report.queryLimited || report.conflictingCalls > 0 || report.invalidReceipts > 0 || report.invalidTimestamps > 0 || isPartialCount(report.totals.normalized.total);

function Count({ value, compact = false }: { value: UsageCount; compact?: boolean }) {
  return <span title={countCoverage(value)}>{tokenNumber(value.knownSubtotal, compact)}{value.knownSubtotal !== null && isPartialCount(value) && <small className="pw-usage-known">已知</small>}</span>;
}
function CompactSelect({ label, value, options, onChange }: { label: string; value: string; options: Array<{ id: string; label: string }>; onChange: (value: string) => void }) {
  return <Select aria-label={label} selectedKey={value} onSelectionChange={key => { if (key != null) onChange(String(key)); }} size="sm"
    className="pw-usage-select" triggerClassName="pw-usage-select-trigger" popoverClassName="pw-select-popover" renderValue={options.find(option => option.id === value)?.label}>
    {options.map(option => <SelectItem key={option.id} id={option.id} textValue={option.label}>{option.label}</SelectItem>)}
  </Select>;
}
function MetricStrip({ value, onCache }: { value: UsageSummary; onCache?: () => void }) {
  return <div className="pw-usage-metric-strip"><dl>{(['input', 'output'] as const).map(key => <div key={key}><dt>{metricLabels[key]}</dt><dd><Count value={value.normalized[key]} compact/></dd></div>)}</dl>
    {onCache ? <Button variant="ghost" className="pw-usage-cache-link" onClick={onCache} data-usage-focus="cache"><span>缓存命中<IconChevronRight size={12} aria-hidden/></span><strong>{rateLabel(value.cache.rate)}</strong></Button>
      : <dl><div><dt>缓存命中</dt><dd>{rateLabel(value.cache.rate)}</dd></div></dl>}
  </div>;
}
function Total({ report, title, onCache }: { report: UsageReport; title: string; onCache: () => void }) {
  const value = report.totals;
  return <div className="pw-usage-total"><div className="pw-usage-total-label"><span>{title}</span>{isPartialReport(report) && <small>部分数据</small>}</div>
    <div className="pw-usage-total-number" title={`${countLabel(value.normalized.total)} tokens`}><strong>{tokenNumber(value.normalized.total.knownSubtotal, true)}</strong><span>tokens</span></div>
    <p>{value.attempts} 次调用 · {report.taskCount} 个任务</p><MetricStrip value={value} onCache={onCache}/>
  </div>;
}

/** Uses the approved Affect UsageMeter's linked daily bars and local readout.
 * Default overview has one period total; a selected day is an explicit drilldown.
 */
function DailyChart({ report, days, metric, selected, onSelect, onOpenDay }: { report: UsageReport; days: string[]; metric: UsageMetric; selected: string; onSelect: (day: string) => void; onOpenDay: (day: string) => void }) {
  const buttons = useRef<Array<HTMLButtonElement | null>>([]), track = useRef<HTMLDivElement>(null);
  const byDay = useMemo(() => new Map(report.daily.map(row => [row.day, row])), [report.daily]);
  const measured = days.map(day => byDay.get(day)?.normalized[metric].knownSubtotal ?? null).filter((value): value is number => value !== null);
  const largest = measured.length ? Math.max(...measured) : null, maximum = Math.max(1, largest ?? 0);
  const selectedIndex = days.indexOf(selected), keyboardIndex = selectedIndex < 0 ? days.length - 1 : selectedIndex;
  const select = (index: number, focus = false) => {
    const next = Math.max(0, Math.min(days.length - 1, index));
    onSelect(days[next]); if (focus) buttons.current[next]?.focus({ preventScroll: true });
    buttons.current[next]?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  };
  useLayoutEffect(() => {
    const element = buttons.current[selectedIndex < 0 ? days.length - 1 : selectedIndex];
    if (element && track.current) track.current.scrollLeft = Math.max(0, element.offsetLeft - track.current.offsetLeft - track.current.clientWidth / 2 + element.clientWidth / 2);
  }, [days.length]);
  const row = byDay.get(selected), longRange = days.length > 30;
  const tickStep = days.length <= 7 ? 1 : longRange ? 10 : 7;
  return <div className="pw-usage-trend">
    <div className="pw-usage-chart-scale"><span>{tokenNumber(largest, true)}</span><span>每日{metricLabels[metric]}</span></div>
    <div className="pw-usage-chart-scroll" ref={track}>
      <div className="pw-usage-chart" data-long={longRange || undefined} role="group" aria-label="每日用量，方向键选择日期，回车查看当天"
        onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault(); select(event.key === 'Home' ? 0 : event.key === 'End' ? days.length - 1 : keyboardIndex + (event.key === 'ArrowLeft' ? -1 : 1), true);
        }}>
        {days.map((day, index) => {
          const value = byDay.get(day)?.normalized[metric], number = value?.knownSubtotal ?? null;
          return <Button key={day} ref={node => { buttons.current[index] = node; }} variant="ghost" className="pw-usage-day" data-selected={selected === day || undefined}
            data-missing={number === null || undefined} data-partial={value && isPartialCount(value) || undefined} aria-pressed={selected === day}
            data-usage-focus={`day-${day}`} tabIndex={index === keyboardIndex ? 0 : -1} onClick={() => onSelect(day)}
            onKeyDown={event => { if (event.key === 'Enter' && selected === day) { event.preventDefault(); onOpenDay(day); } }}
            aria-label={`${fullDate(day)}，${value ? countLabel(value) + ' tokens' : '无保留记录'}`}>
            <span className="pw-usage-day-column"><i style={{ '--bar-size': number === null ? '0%' : `${number / maximum * 100}%` } as CSSProperties}/></span>
          </Button>;
        })}
      </div>
      <div className="pw-usage-chart-dates" data-long={longRange || undefined} aria-hidden="true">{days.map((day, index) => <span key={day}>{index === 0 || index === days.length - 1 || index % tickStep === 0 && (days.length <= 7 || index < days.length - 3) ? conciseDate(day) : ''}</span>)}</div>
    </div>
    {longRange && <p className="pw-usage-chart-hint">左右滑动查看更早的日期</p>}
    <div className="pw-usage-day-inspector" data-active={!!selected || undefined}>
      <div className="pw-usage-day-controls"><IconButton icon={IconChevronLeft} aria-label={selected ? "前一天" : "选择最近一天"} disabled={selectedIndex === 0} onClick={() => select(selectedIndex < 0 ? days.length - 1 : selectedIndex - 1)}/>
        <div aria-live="polite">{selected ? <><span>{fullDate(selected)}</span><strong>{row ? <Count value={row.normalized[metric]} compact/> : '无记录'}</strong></> : <span>点选日期查看用量</span>}</div>
        <IconButton icon={IconChevronRight} aria-label={selected ? "后一天" : "选择最近一天"} disabled={selectedIndex === days.length - 1} onClick={() => select(selectedIndex < 0 ? days.length - 1 : selectedIndex + 1)}/></div>
      {selected && <Button variant="ghost" trailingIcon={IconChevronRight} className="pw-usage-day-open" onClick={() => onOpenDay(selected)} data-usage-focus="day-detail">当天明细</Button>}
    </div>
  </div>;
}

function GroupList({ entries, dimension, onOpen }: { entries: UsageSummary[]; dimension: Dimension; onOpen: (row: UsageSummary) => void }) {
  const maximum = Math.max(1, ...entries.map(row => row.normalized.total.knownSubtotal ?? 0));
  return <div className="pw-usage-ranked-list">{entries.map(row => <Button key={groupKey(row, dimension)} variant="ghost" className="pw-usage-ranked-row" onClick={() => onOpen(row)} data-usage-focus={`group-${groupKey(row, dimension)}`}>
    <span className="pw-usage-ranked-content"><span className="pw-usage-ranked-copy"><strong>{groupTitle(row, dimension)}</strong><small>{groupSubtitle(row, dimension)}</small></span>
      <span className="pw-usage-ranked-value"><Count value={row.normalized.total} compact/><IconChevronRight size={16} aria-hidden/></span>
      <span className="pw-usage-ranked-track" aria-hidden="true"><i style={{ width: `${(row.normalized.total.knownSubtotal ?? 0) / maximum * 100}%` }}/></span>
    </span>
  </Button>)}</div>;
}
function TokenDetails({ value }: { value: UsageSummary }) {
  return <div className="pw-usage-token-details"><dl className="pw-usage-detail-facts">
    {(['total', 'input', 'cacheRead', 'cacheWrite', 'output', 'reasoning'] as UsageMetric[]).map(key => <div key={key} data-subitem={['cacheRead', 'cacheWrite', 'reasoning'].includes(key) || undefined}><dt>{metricLabels[key]}</dt><dd><Count value={value.normalized[key]}/></dd></div>)}
  </dl><p className="pw-usage-caption">缓存读写属于输入细项，推理属于输出细项。</p></div>;
}
function CacheDetails({ value }: { value: UsageSummary }) {
  const cache = value.cache;
  return <section className="pw-usage-detail-section"><header className="pw-usage-section-heading"><h3>缓存效率</h3><strong>{rateLabel(cache.rate)}</strong></header>
    <div className="pw-usage-cache-track" role="img" aria-label={cache.rate === null ? '缓存命中率未报告' : `已报告输入的 ${rateLabel(cache.rate)} 来自缓存`} data-unknown={cache.rate === null || undefined}>{cache.rate !== null && <i style={{ width: `${cache.rate * 100}%` }}/>}</div>
    <p className="pw-usage-caption">{cache.eligibleCalls ? `${cache.eligibleCalls} 次调用可比较` : '暂无可比较的缓存回执'}{cache.unknownCalls ? ` · ${cache.unknownCalls} 次未知` : ''}{cache.partialCalls ? ` · ${cache.partialCalls} 次为部分计数` : ''}</p>
    <Accordion variant="divided" className="pw-usage-inline-disclosure"><AccordionItem id="usage-cache-method" title="计算方式">
      <p>缓存读取总数 ÷ 同一批调用的完整输入总数；不平均每次请求的百分比。</p>{cache.rate !== null && <p>{tokenNumber(cache.readTokens)} ÷ {tokenNumber(cache.inputTokens)} tokens。</p>}<p>命中缓存的内容仍占上下文。未报告字段保持未知。</p>
    </AccordionItem></Accordion>
  </section>;
}
function Money({ value }: { value: UsageSummary }) {
  const money = value.money;
  const amount = (raw: string) => { const value = Number(raw); return !Number.isFinite(value) ? raw : value > 0 && value < .000001 ? '<0.000001' : value.toLocaleString('zh-CN', { maximumFractionDigits: 6 }); };
  return <section className="pw-usage-detail-section"><h3>费用估算</h3>
    {money.currencies.length ? <dl className="pw-usage-detail-facts">{money.currencies.map(row => <div key={row.currency}><dt>{row.currency}</dt><dd>{amount(row.knownTokenSubtotal)}<small className="pw-usage-known">{row.partlyPricedCalls || row.nonfinalCalls || money.unknownCalls ? '已知部分' : '估算'}</small></dd></div>)}</dl> : <p className="pw-usage-caption">暂无可核对的价目。</p>}
    <p className="pw-usage-caption">{money.unknownCalls ? `${money.unknownCalls} 次调用未定价。` : ''}按调用时保存的 API 价目估算，不代表实际账单或套餐余额。</p>
  </section>;
}
function Coverage({ report }: { report: UsageReport }) {
  const total = report.totals;
  return <div className="pw-usage-explanation"><h3>统计哪些调用</h3><p>本机主人私人范围内保留的实际调用。按请求开始时间与设备时区归入自然日；重试、压缩与辅助请求分别计入。</p><p>其它设备、服务账单和未保留的历史不在其中。缺少记录与服务报告的 0 含义不同。</p>
    <h3>数据完整性</h3><p>总量：{countCoverage(total.normalized.total)}。输入与输出的小计可能覆盖不同样本，不能由两项部分小计拼成精确总量。</p>
    {report.queryLimited && <p>本次读取达到上限，目前显示已读取部分。</p>}
    {(report.conflictingCalls > 0 || report.invalidReceipts > 0 || report.invalidTimestamps > 0 || report.oversizedRows > 0) && <p>未计入无法核对的记录：{report.conflictingCalls} 条冲突、{report.invalidReceipts} 条无效回执、{report.invalidTimestamps} 条缺少时间、{report.oversizedRows} 条超出读取大小。</p>}
    <p>失败 {total.outcomes.FAILED || 0} 次 · 取消 {total.outcomes.CANCELLED || 0} 次 · 尚未结束 {total.outcomes.STARTED || 0} 次。有报告的消耗仍保留。</p>
    <h3>与上下文有什么区别</h3><p>这里是多次调用的累计消耗。单次请求的上下文占用与构成，请在对话输入框的小圆环查看。</p><p>时区：{report.query.zoneId}。{report.sourceReadAt > 0 ? `读取于 ${new Date(report.sourceReadAt).toLocaleString('zh-CN')}。` : ''}</p>
  </div>;
}

export function UsageWorkspace({ supported, records, renderBudget, diagnostics, registerTransientBack }: Props) {
  const [view, setView] = useState<View>({ kind: 'overview' }), [period, setPeriod] = useState(30), [metric, setMetric] = useState<UsageMetric>('total');
  const [range, setRange] = useState(() => usageRange(30)), [periodReport, setPeriodReport] = useState<OwnedReport | null>(null), [dayReport, setDayReport] = useState<OwnedReport | null>(null);
  const [selectedDay, setSelectedDay] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState(''), [refresh, setRefresh] = useState(0);
  const [budgetEditing, setBudgetEditing] = useState(false), [dimension, setDimension] = useState<Dimension>('model'), [filter, setFilter] = useState(''), [limit, setLimit] = useState(10);
  const root = useRef<HTMLElement>(null), sequence = useRef(0), latest = useRef({ supported }), childBack = useRef<(() => boolean) | null>(null);
  const trail = useRef<TrailEntry[]>([]), restore = useRef<TrailEntry | null>(null), navigating = useRef(false);
  latest.current = { supported };
  const registerChildBack = useCallback<Back>(handler => { childBack.current = handler; }, []);
  const available = supported('usage.trends'), owner = useSyncExternalStore(bridge.subscribe, ownerTicket, ownerTicket);
  const day = view.day || '', needsReport = !['records', 'budget', 'diagnostics'].includes(view.kind);
  const query = useMemo(() => {
    if (!day) return range.query;
    const [year, month, date] = day.split('-').map(Number);
    return usageRange(1, new Date(year, month - 1, date)).query;
  }, [day, range.query]);
  const envelope = day ? dayReport : periodReport;
  const current = envelope?.owner === owner && available && sameQuery(envelope.value.query, query) ? envelope.value : null;
  const scopeTitle = day ? fullDate(day) : `近 ${period} 天`, scopeDates = day ? day : `${conciseDate(range.days[0])} — ${conciseDate(range.days.at(-1) || '')}`;
  useSurfaceEnter(root, view.kind + day, { selector: '' });

  const open = (next: View) => {
    if (budgetEditing) return;
    const active = document.activeElement?.closest<HTMLElement>('[data-usage-focus]');
    trail.current.push({ view, scroll: root.current?.closest('.pw-secondary-scroll')?.scrollTop || 0, focus: active?.dataset.usageFocus || null });
    navigating.current = true; setView(next);
  };
  const back = () => {
    if (childBack.current?.()) return true;
    if (budgetEditing) return true;
    if (view.kind === 'overview') return false;
    const previous = trail.current.pop() || { view: { kind: 'overview' } as View, scroll: 0, focus: null };
    restore.current = previous; navigating.current = true; setView(previous.view); return true;
  };
  const latestBack = useRef(back); latestBack.current = back;
  useEffect(() => { registerTransientBack?.(() => latestBack.current()); return () => registerTransientBack?.(null); }, [registerTransientBack]);
  useLayoutEffect(() => {
    if (!navigating.current) return;
    navigating.current = false;
    const scroller = root.current?.closest<HTMLElement>('.pw-secondary-scroll'), previous = restore.current;
    if (scroller) scroller.scrollTop = previous?.scroll || 0;
    if (previous?.focus) {
      const target = Array.from(root.current?.querySelectorAll<HTMLElement>('[data-usage-focus]') || []).find(node => node.dataset.usageFocus === previous.focus);
      target?.focus({ preventScroll: true });
    } else root.current?.querySelector<HTMLElement>('[data-usage-heading]')?.focus({ preventScroll: true });
    restore.current = null;
  }, [view]);

  useEffect(() => {
    if (!needsReport || !available || document.visibilityState !== 'visible') { setBusy(false); setError(''); return; }
    const request = ++sequence.current, requestOwner = owner;
    let disposed = false;
    setBusy(true); setError('');
    const ownsRequest = () => !disposed && sequence.current === request && ownerTicket() === requestOwner && document.visibilityState === 'visible';
    void bridge.command('usage.trends', query).then(value => {
      if (!ownsRequest()) return;
      const result = { owner: requestOwner, value: usageReport(value, query) };
      if (day) setDayReport(result); else setPeriodReport(result);
    }).catch(failure => { if (ownsRequest()) setError(failure instanceof Error ? failure.message : '用量暂未读取。'); })
      .finally(() => { if (ownsRequest()) setBusy(false); });
    return () => { disposed = true; sequence.current++; if (latest.current.supported('usage.trends.close')) void bridge.command('usage.trends.close', {}).catch(() => {}); };
  }, [query, day, available, owner, refresh, needsReport]);

  useEffect(() => {
    const visibility = () => {
      if (document.visibilityState !== 'visible') {
        sequence.current++; setBusy(false); setPeriodReport(null); setDayReport(null); setError('');
        if (latest.current.supported('usage.trends.close')) void bridge.command('usage.trends.close', {}).catch(() => {});
      } else { setRange(usageRange(period)); setRefresh(value => value + 1); }
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, [period]);

  const showMetrics = () => open({ kind: 'metrics', day });
  const showDay = (selected: string) => { setDimension('model'); setFilter(''); setLimit(10); open({ kind: 'analysis', day: selected }); };
  const showGroup = (row: UsageSummary, selectedDimension = dimension) => open({ kind: 'group', day, dimension: selectedDimension, groupKey: groupKey(row, selectedDimension) });
  const selectedGroup = current && view.kind === 'group' ? groupRows(current, view.dimension || 'model').find(row => groupKey(row, view.dimension || 'model') === view.groupKey) : null;
  const entries = current ? sortedGroups(current, dimension).filter(row => `${groupTitle(row, dimension)} ${groupSubtitle(row, dimension)}`.toLocaleLowerCase().includes(filter.trim().toLocaleLowerCase())) : [];
  const viewTitle = view.kind === 'trends' ? '每日趋势' : view.kind === 'analysis' ? day ? `${fullDate(day)}用量` : '用量分布' : view.kind === 'group' ? selectedGroup ? groupTitle(selectedGroup, view.dimension || 'model') : '来源明细' : view.kind === 'metrics' ? 'Token 与费用' : view.kind === 'records' ? '调用记录' : view.kind === 'budget' ? '预算与限制' : view.kind === 'diagnostics' ? '模型诊断' : '统计说明';
  return <section ref={root} className="pw-usage-workspace" aria-label="用量统计">
    {view.kind === 'overview' ? <header className="pw-usage-toolbar"><CompactSelect label="统计时间范围" value={String(period)} options={periods.map(value => ({ id: String(value), label: `近 ${value} 天` }))} onChange={value => { const days = Number(value); setPeriod(days); setRange(usageRange(days)); setPeriodReport(null); setDayReport(null); setSelectedDay(''); }}/>
      <div className="pw-usage-toolbar-copy"><span>{scopeDates}</span><WorkingStatus active={busy}>{current ? '正在更新…' : '正在读取用量…'}</WorkingStatus></div><IconButton icon={IconRefresh} aria-label="刷新用量统计" title="刷新用量统计" disabled={busy || !available} onClick={() => { setRange(usageRange(period)); setRefresh(value => value + 1); }}/></header>
      : <header className="pw-usage-detail-heading"><Button variant="ghost" leadingIcon={IconChevronLeft} onClick={back}>返回{trail.current.at(-1)?.view.kind === 'analysis' ? '分布' : trail.current.at(-1)?.view.kind === 'trends' ? '趋势' : '概览'}</Button><h2 tabIndex={-1} data-usage-heading>{viewTitle}</h2>{needsReport && <p>{scopeDates} · {day ? '当天' : `近 ${period} 天`}</p>}</header>}
    {needsReport && <>
      {!available && <p className="pw-usage-state">当前版本暂未提供趋势统计，可查看调用记录。</p>}
      {error && <div className="pw-usage-error" role="alert"><p>{error}</p>{current && <small>当前保留上次成功读取的统计。</small>}<Button variant="ghost" disabled={busy} onClick={() => setRefresh(value => value + 1)}>重新读取</Button></div>}
      {view.kind !== 'overview' && <WorkingStatus active={busy}>{current ? '正在更新…' : '正在读取用量…'}</WorkingStatus>}
      {current && view.kind === 'overview' && <>
        {current.totals.attempts > 0 ? <section className="pw-usage-primary" aria-label="当前时段用量"><Total report={current} title="累计用量" onCache={showMetrics}/>
          <div className="pw-usage-trend-heading"><h3>每日消耗</h3><Button variant="ghost" trailingIcon={IconChevronRight} data-usage-focus="trends" onClick={() => open({ kind: 'trends' })}>分析趋势</Button></div>
          <DailyChart report={current} days={range.days} metric="total" selected={selectedDay} onSelect={setSelectedDay} onOpenDay={showDay}/>
        </section> : <div className="pw-usage-empty"><IconFileText size={28} aria-hidden/><h3>这段时间还没有记录</h3><p>完成模型调用后，这里会按天显示保留的用量。</p></div>}
        {current.byModel.length > 0 && <section className="pw-usage-sources"><header className="pw-usage-section-heading"><h3>主要模型</h3><Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => { setDimension('model'); setFilter(''); setLimit(10); open({ kind: 'analysis' }); }} data-usage-focus="sources">查看分布</Button></header>
          <GroupList entries={sortedGroups(current, 'model').slice(0, 3)} dimension="model" onOpen={row => showGroup(row, 'model')}/>
        </section>}

      </>}
      {current && view.kind === 'trends' && <section className="pw-usage-primary"><div className="pw-usage-section-heading"><span className="pw-usage-caption">选择想查看的指标</span><CompactSelect label="趋势指标" value={metric} options={chartMetrics.map(key => ({ id: key, label: metricLabels[key] }))} onChange={value => setMetric(value as UsageMetric)}/></div>
        <div className="pw-usage-trend-total"><span>{scopeTitle} · {metricLabels[metric]}</span><strong><Count value={current.totals.normalized[metric]} compact/><small>tokens</small></strong></div>
        <DailyChart report={current} days={range.days} metric={metric} selected={selectedDay} onSelect={setSelectedDay} onOpenDay={showDay}/>
        <p className="pw-usage-caption">选择日期后可查看当天的模型、用途与会话分布。</p>
      </section>}
      {current && view.kind === 'analysis' && <><div className="pw-usage-analysis-summary"><span>{scopeTitle} · {isPartialReport(current) ? '已知小计' : '已记录'}</span><strong><Count value={current.totals.normalized.total} compact/><small>tokens</small></strong><MetricStrip value={current.totals} onCache={showMetrics}/></div>
        <section className="pw-usage-all-sources"><header className="pw-usage-section-heading"><CompactSelect label="分组方式" value={dimension} options={(Object.keys(dimensionNames) as Dimension[]).map(key => ({ id: key, label: dimensionNames[key] }))} onChange={value => { setDimension(value as Dimension); setFilter(''); setLimit(10); }}/><span className="pw-usage-caption">{groupRows(current, dimension).length} 项</span></header>
          {groupRows(current, dimension).length > 8 && <SearchField aria-label="查找用量来源" placeholder="查找用量来源" value={filter} onChange={value => { setFilter(value); setLimit(10); }}/>}
          {entries.length ? <GroupList entries={entries.slice(0, limit)} dimension={dimension} onOpen={row => showGroup(row)}/> : <p className="pw-usage-state">{filter ? '没有匹配项。' : dimension === 'conversation' ? '没有可核对的会话归属。' : '这段时间没有保留记录。'}</p>}
          {entries.length > limit && <Button variant="ghost" trailingIcon={IconChevronDown} onClick={() => setLimit(value => value + 20)}>再显示 {Math.min(20, entries.length - limit)} 项</Button>}
          {dimension === 'conversation' && current.conversationsLimited && <p className="pw-usage-caption">仅列出本次返回的会话，已读取的全部调用仍计入总量。</p>}
        </section><Button variant="ghost" trailingIcon={IconChevronRight} onClick={showMetrics}>查看{day ? '当天' : '区间'} Token 与费用</Button></>}
      {current && view.kind === 'group' && (selectedGroup ? <><p className="pw-usage-group-subtitle">{groupSubtitle(selectedGroup, view.dimension || 'model')}</p><TokenDetails value={selectedGroup}/><CacheDetails value={selectedGroup}/><Money value={selectedGroup}/><p className="pw-usage-caption">{selectedGroup.attempts} 次调用 · {countCoverage(selectedGroup.normalized.total)}{selectedGroup.outcomes.FAILED ? ` · ${selectedGroup.outcomes.FAILED} 次失败` : ''}{selectedGroup.outcomes.CANCELLED ? ` · ${selectedGroup.outcomes.CANCELLED} 次取消` : ''}</p></> : <p className="pw-usage-state">当前读取结果中已没有这项记录，请返回分布重新选择。</p>)}
      {current && view.kind === 'metrics' && <><TokenDetails value={current.totals}/><CacheDetails value={current.totals}/><Money value={current.totals}/><Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => open({ kind: 'about', day })}>统计范围与完整性</Button></>}
      {current && view.kind === 'about' && <Coverage report={current}/>}
    </>}
    {view.kind === 'overview' && <>
        <div className="pw-usage-utility-list">{current && <Button variant="ghost" trailingIcon={IconChevronRight} className="pw-usage-utility" onClick={showMetrics} data-usage-focus="metrics"><span>Token 与费用<small>输入、输出、缓存与估算</small></span></Button>}
          {records && <Button variant="ghost" trailingIcon={IconChevronRight} className="pw-usage-utility" onClick={() => open({ kind: 'records' })} data-usage-focus="records"><span>调用记录<small>逐次查看原始用量</small></span></Button>}
          {renderBudget && <Button variant="ghost" trailingIcon={IconChevronRight} className="pw-usage-utility" onClick={() => open({ kind: 'budget' })} data-usage-focus="budget"><span>预算与限制<small>管理调用上限</small></span></Button>}
        </div>
    </>}
    {view.kind === 'overview' && current && <footer className="pw-usage-footer"><Button variant="ghost" onClick={() => open({ kind: 'about' })} data-usage-focus="about">{isPartialReport(current) ? '部分数据 · 统计说明' : '本机保留记录 · 统计说明'}</Button>{current.sourceReadAt > 0 && <span>{new Date(current.sourceReadAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 更新</span>}</footer>}
    {view.kind === 'records' && records}
    {view.kind === 'budget' && <>{budgetEditing && <p className="pw-usage-caption" role="status">完成当前操作后即可返回；结果待确认时，请读取原设置核对。</p>}{renderBudget?.(setBudgetEditing, registerChildBack)}</>}
    {view.kind === 'diagnostics' && diagnostics}
    {view.kind === 'overview' && diagnostics && <Button variant="ghost" trailingIcon={IconChevronRight} className="pw-usage-diagnostic-link" onClick={() => open({ kind: 'diagnostics' })} data-usage-focus="diagnostics">模型能力诊断</Button>}
  </section>;
}
