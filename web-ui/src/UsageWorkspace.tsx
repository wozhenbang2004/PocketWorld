import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type CSSProperties, type ReactNode } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, SearchField } from './forms-library';
import { LiquidTabs, Tabs, Tab, TabList, TabPanel } from './shell-library';
import { IconRefresh, IconChevronDown, IconChevronLeft, IconChevronRight, IconFileText } from './icons';
import { useSurfaceEnter } from './library/surface-motion';
import { countCoverage, countLabel, isPartialCount, metricLabels, purposeLabel, rateLabel, tokenNumber, usageRange, usageReport, type UsageCount, type UsageMetric, type UsageReport, type UsageSummary } from './usage-metrics';
import './usage-workspace.css';

type Props = { supported: (action: string) => boolean;
  records?: ReactNode; renderBudget?: (onEditingChange: (value: boolean) => void) => ReactNode; diagnostics?: ReactNode };
const periods = [7, 30, 90];
const chartMetrics: UsageMetric[] = ['total', 'input', 'output', 'cacheRead'];
const fullDate = (day: string) => /^\d{4}-\d{2}-\d{2}$/.test(day) ? `${Number(day.slice(5, 7))}月${Number(day.slice(8))}日` : day;
const conciseDate = (day: string) => /^\d{4}-\d{2}-\d{2}$/.test(day) ? `${Number(day.slice(5, 7))}/${Number(day.slice(8))}` : day;
const ownerTicket = () => {
  const view = bridge.getState().view as Record<string, unknown> | undefined;
  return JSON.stringify([bridge.getDocumentEpoch(), view?.section || '', view?.taskId || '', view?.resumeRevision || 0]);
};

function Count({ value, compact = false }: { value: UsageCount; compact?: boolean }) {
  return <span title={countCoverage(value)}>{tokenNumber(value.knownSubtotal, compact)}{value.knownSubtotal !== null && isPartialCount(value) && <small className="pw-usage-known">已知</small>}</span>;
}
function SummaryFacts({ value, detail = false }: { value: UsageSummary; detail?: boolean }) {
  const keys: UsageMetric[] = detail ? ['input', 'output', 'cacheRead', 'cacheWrite', 'reasoning'] : ['input', 'output'];
  return <dl className="pw-usage-facts">{keys.map(key => <div key={key}><dt>{metricLabels[key]}</dt><dd><Count value={value.normalized[key]} /></dd></div>)}
    {detail && <><div><dt>调用次数</dt><dd>{value.attempts.toLocaleString('zh-CN')}</dd></div><div><dt>缓存命中</dt><dd>{rateLabel(value.cache.rate)}</dd></div></>}
  </dl>;
}

/** Date selection and linked readout adapt the saved UsageMeter interaction to native receipts. */
function DailyChart({ report, days, metric, selected, onSelect }: { report: UsageReport; days: string[]; metric: UsageMetric; selected: string; onSelect: (day: string) => void }) {
  const buttons = useRef<Array<HTMLButtonElement | null>>([]), track = useRef<HTMLDivElement>(null);
  const byDay = useMemo(() => new Map(report.daily.map(row => [row.day, row])), [report.daily]);
  const measured = days.map(day => byDay.get(day)?.normalized[metric].knownSubtotal ?? null).filter((value): value is number => value !== null);
  const largest = measured.length ? Math.max(...measured) : null, maximum = Math.max(1, largest ?? 0);
  const selectedIndex = Math.max(0, days.indexOf(selected)), selectedRow = byDay.get(selected);
  const selectAdjacent = (offset: number) => {
    const index = Math.max(0, Math.min(days.length - 1, selectedIndex + offset));
    onSelect(days[index]); buttons.current[index]?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  };
  useEffect(() => { const element = buttons.current[selectedIndex]; if (element && track.current) track.current.scrollLeft = Math.max(0, element.offsetLeft - track.current.offsetLeft - track.current.clientWidth / 2 + element.clientWidth / 2); }, [days.length]);
  return <section className="pw-usage-trend" aria-label="每日 Token 趋势">
    <div className="pw-usage-trend-readout"><div aria-live="polite"><span>{fullDate(selected)}</span><strong>{selectedRow ? <Count value={selectedRow.normalized[metric]} /> : '无记录'}</strong></div><div className="pw-usage-day-navigation"><IconButton icon={IconChevronLeft} aria-label="前一天" disabled={selectedIndex === 0} onClick={() => selectAdjacent(-1)}/><IconButton icon={IconChevronRight} aria-label="后一天" disabled={selectedIndex === days.length - 1} onClick={() => selectAdjacent(1)}/></div></div>
    <div className="pw-usage-chart-scale"><span>{tokenNumber(largest, true)}</span><span>每日{metricLabels[metric]}</span></div>
    <div className="pw-usage-chart-scroll" ref={track}>
      <div className="pw-usage-chart" role="group" aria-label="选择日期，方向键查看相邻日期" onKeyDown={event => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? days.length - 1 : Math.max(0, Math.min(days.length - 1, selectedIndex + (event.key === 'ArrowLeft' ? -1 : 1)));
        onSelect(days[next]); buttons.current[next]?.focus({ preventScroll: true }); buttons.current[next]?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      }}>
        {days.map((day, index) => {
          const row = byDay.get(day), value = row?.normalized[metric], number = value?.knownSubtotal ?? null;
          const missing = number === null, chosen = selected === day;
          return <button key={day} ref={node => { buttons.current[index] = node; }} type="button" className="pw-usage-day" data-selected={chosen || undefined}
            data-missing={missing || undefined} data-partial={value && isPartialCount(value) || undefined} aria-pressed={chosen}
            tabIndex={chosen ? 0 : -1} onClick={() => onSelect(day)} onPointerEnter={event => { if (event.pointerType === 'mouse') onSelect(day); }}
            aria-label={`${fullDate(day)}，${row ? value ? countLabel(value) + ' tokens' : '未报告' : '无保留记录'}`}>
            <span className="pw-usage-day-column"><i style={{ '--bar-size': number === null ? '0%' : `${number / maximum * 100}%` } as CSSProperties} /></span>
            <span className="pw-usage-day-label">{days.length <= 7 || index % 5 === 0 || index === days.length - 1 ? conciseDate(day) : '·'}</span>
          </button>;
        })}
      </div>
    </div>
    <div className="pw-usage-day-detail">{selectedRow ? <><SummaryFacts value={selectedRow}/><p>{selectedRow.attempts} 次调用 · 缓存命中 {rateLabel(selectedRow.cache.rate)}{isPartialCount(selectedRow.normalized[metric]) ? ' · 当前为已知小计' : ''}</p></> : <p>这一天没有保留的调用回执，不能据此确认实际用量为零。</p>}</div>
  </section>;
}

function CacheEfficiency({ value }: { value: UsageSummary }) {
  const cache = value.cache, known = cache.rate !== null;
  return <section className="pw-usage-cache" aria-label="缓存效率">
    <header className="pw-usage-section-heading"><div><h3>缓存效率</h3><p>输入 Token 中从缓存读取的比例</p></div><strong>{rateLabel(cache.rate)}</strong></header>
    <div className="pw-usage-cache-track" role="img" aria-label={known ? `已报告输入的 ${rateLabel(cache.rate)} 来自缓存` : '缓存命中率未报告'} data-unknown={!known || undefined}>
      {known && <i style={{ width: `${cache.rate! * 100}%` }} />}
    </div>
    <dl className="pw-usage-facts"><div><dt>缓存读取</dt><dd><Count value={value.normalized.cacheRead}/></dd></div><div><dt>缓存写入</dt><dd><Count value={value.normalized.cacheWrite}/></dd></div></dl>
    <p className="pw-usage-caption">{cache.eligibleCalls ? `${cache.eligibleCalls} 次调用可比较` : '尚无可比较的缓存回执'}{cache.unknownCalls ? ` · ${cache.unknownCalls} 次未知` : ''}{cache.partialCalls ? ` · ${cache.partialCalls} 次只有部分计数` : ''}。缓存内容仍占上下文。</p>
    <Accordion variant="divided" className="pw-usage-inline-disclosure"><AccordionItem id="usage-cache-calculation" title="怎样计算命中率">
      <p>将同时报告输入与缓存读取的调用放在一起，用缓存读取总数除以这些调用的完整输入总数；不会把每次请求的百分比直接取平均。</p>
      {known && <p>本次可比较样本：{tokenNumber(cache.readTokens)} ÷ {tokenNumber(cache.inputTokens)} tokens。</p>}
      <p>缓存读取、缓存写入属于输入的细项；推理属于输出的细项。服务协议差异由原调用回执统一后展示。字段未报告时保留未知。</p>
    </AccordionItem></Accordion>
  </section>;
}

function UsageGroups({ report }: { report: UsageReport }) {
  const [dimension, setDimension] = useState(0), [filter, setFilter] = useState(''), [limit, setLimit] = useState(10);
  const sources = [report.byModel, report.byPurpose, report.byConversation];
  const tabs = ['模型', '用途', '会话'];
  const title = (row: UsageSummary) => dimension === 0 ? row.model || '未记录模型' : dimension === 1 ? purposeLabel(row.purpose) : row.title || (row.conversationId ? `对话 ${row.conversationId.slice(0, 8)}` : '未关联会话');
  const subtitle = (row: UsageSummary) => dimension === 0 ? row.provider || '服务未记录' : dimension === 1 ? `${row.attempts} 次调用` : row.latestTaskLabel ? `最近任务：${row.latestTaskLabel}` : row.conversationId ? row.conversationId.slice(0, 8) : '任务与辅助调用';
  const entries = sources[dimension].filter(row => `${title(row)} ${subtitle(row)}`.toLocaleLowerCase().includes(filter.trim().toLocaleLowerCase()))
    .sort((a, b) => (b.normalized.total.knownSubtotal ?? -1) - (a.normalized.total.knownSubtotal ?? -1));
  const maximum = Math.max(1, ...entries.map(row => row.normalized.total.knownSubtotal ?? 0));
  return <section className="pw-usage-groups" aria-label="用量分布"><header className="pw-usage-section-heading"><div><h3>用在哪里</h3><p>按已记录 Token 排序</p></div></header>
    <LiquidTabs tabs={tabs} value={dimension} onValueChange={value => { setDimension(value); setFilter(''); setLimit(10); }} label="用量分组" variant="minimal" className="pw-usage-dimension"/>
    {sources[dimension].length > 8 && <SearchField aria-label={`查找${tabs[dimension]}`} placeholder={`查找${tabs[dimension]}`} value={filter} onChange={value => { setFilter(value); setLimit(10); }} />}
    {entries.length ? <Accordion variant="divided" className="pw-usage-distribution">{entries.slice(0, limit).map((row, index) => <AccordionItem key={`${dimension}:${row.provider}:${row.model}:${row.purpose}:${row.conversationId}:${index}`} id={`usage-group-${dimension}-${index}`}
      title={<div className="pw-usage-group-heading"><span><strong>{title(row)}</strong><small>{subtitle(row)}</small></span><span className="pw-usage-group-value"><Count value={row.normalized.total} compact/><i aria-hidden="true" style={{ width: `${(row.normalized.total.knownSubtotal ?? 0) / maximum * 100}%` }}/></span></div>}>
      <SummaryFacts value={row} detail/><p className="pw-usage-caption">{countCoverage(row.normalized.total)}{row.outcomes.FAILED ? ` · ${row.outcomes.FAILED} 次失败调用` : ''}{row.outcomes.CANCELLED ? ` · ${row.outcomes.CANCELLED} 次取消调用` : ''}</p>
    </AccordionItem>)}</Accordion> : <p className="pw-usage-caption">{filter ? '没有匹配项。' : dimension === 2 ? '当前回执尚无可核对的会话归属。' : '当前范围暂无分组记录。'}</p>}
    {entries.length > limit && <Button variant="ghost" trailingIcon={IconChevronDown} onClick={() => setLimit(value => value + 20)}>再显示 {Math.min(20, entries.length - limit)} 项</Button>}
    {dimension === 2 && report.conversationsLimited && <p className="pw-usage-caption">仅展示本次返回的会话分组，全部已读取调用仍计入上方小计。</p>}
  </section>;
}

function Money({ report }: { report: UsageReport }) {
  const money = report.totals.money;
  const amount = (raw: string) => {
    const value = Number(raw);
    if (!Number.isFinite(value)) return raw;
    if (value > 0 && value < .000001) return '<0.000001';
    return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
  };
  return <section className="pw-usage-money"><header className="pw-usage-section-heading"><div><h3>费用估算</h3><p>使用调用时保存的 API 价目</p></div></header>
    {money.currencies.length ? <dl className="pw-usage-facts">{money.currencies.map(row => <div key={row.currency}><dt>{row.currency}</dt><dd>{amount(row.knownTokenSubtotal)}<small className="pw-usage-known">{row.partlyPricedCalls || row.nonfinalCalls || money.unknownCalls ? '已知部分' : '估算'}</small></dd></div>)}</dl> : <p className="pw-usage-caption">暂无可核对的费用。Token 统计仍可使用。</p>}
    <p className="pw-usage-caption">{money.unknownCalls ? `${money.unknownCalls} 次调用未定价。` : ''}此处不是账单，不由 Token 反推订阅套餐余额。</p>
  </section>;
}

export function UsageWorkspace({ supported, records, renderBudget, diagnostics }: Props) {
  const [tab, setTab] = useState('overview'), [period, setPeriod] = useState(1), [metricIndex, setMetricIndex] = useState(0);
  const [range, setRange] = useState(() => usageRange(30)), [report, setReport] = useState<{ owner: string; value: UsageReport } | null>(null);
  const [selectedDay, setSelectedDay] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [budgetEditing, setBudgetEditing] = useState(false), [refresh, setRefresh] = useState(0);
  const root = useRef<HTMLElement>(null), sequence = useRef(0), latest = useRef({ supported });
  latest.current = { supported };
  const available = supported('usage.trends'), owner = useSyncExternalStore(bridge.subscribe, ownerTicket, ownerTicket);
  useSurfaceEnter(root, tab);

  useEffect(() => {
    if (tab !== 'overview' || !available || document.visibilityState !== 'visible') { setBusy(false); setError(''); return; }
    const request = ++sequence.current, requestOwner = owner;
    let disposed = false;
    setBusy(true); setError('');
    const ownsRequest = () => !disposed && sequence.current === request && ownerTicket() === requestOwner && document.visibilityState === 'visible';
    void bridge.command('usage.trends', range.query).then(value => {
      if (!ownsRequest()) return;
      const result = usageReport(value, range.query); setReport({ owner: requestOwner, value: result });
      setSelectedDay(day => range.days.includes(day) ? day : range.days.at(-1) || '');
    }).catch(failure => { if (ownsRequest()) setError(failure instanceof Error ? failure.message : '用量暂未读取。'); })
      .finally(() => { if (ownsRequest()) setBusy(false); });
    return () => { disposed = true; sequence.current++; if (latest.current.supported('usage.trends.close')) void bridge.command('usage.trends.close', {}).catch(() => {}); };
  }, [range, available, owner, refresh, tab]);

  useEffect(() => {
    const visibility = () => {
      if (document.visibilityState !== 'visible') {
        sequence.current++; setBusy(false); setReport(null); setError('');
        if (latest.current.supported('usage.trends.close')) void bridge.command('usage.trends.close', {}).catch(() => {});
      } else { setRange(usageRange(periods[period])); setRefresh(value => value + 1); }
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, [period]);

  const value = report?.owner === owner && available ? report.value : null;
  const current = value && value.query.fromInclusive === range.query.fromInclusive && value.query.toExclusive === range.query.toExclusive && value.query.zoneId === range.query.zoneId ? value : null;
  const totals = current?.totals, partial = !!current && (current.queryLimited || current.conflictingCalls > 0 || current.invalidReceipts > 0 || current.invalidTimestamps > 0 || isPartialCount(current.totals.normalized.total));
  return <section ref={root} className="pw-usage-workspace" aria-label="用量统计">
    <Tabs selectedKey={tab} disabledKeys={budgetEditing ? ['overview', 'records'] : []} onSelectionChange={key => { if (!budgetEditing) setTab(String(key)); }} className="pw-usage-main-tabs"><TabList aria-label="用量视图"><Tab id="overview">概览</Tab>{records && <Tab id="records">调用记录</Tab>}{renderBudget && <Tab id="budget">预算</Tab>}</TabList>
      <TabPanel id="overview">
        <header className="pw-usage-range"><LiquidTabs tabs={['7 天', '30 天', '90 天']} value={period} onValueChange={value => { setPeriod(value); setRange(usageRange(periods[value])); setReport(null); setSelectedDay(''); }} label="统计时间范围" variant="minimal"/>
          <IconButton icon={IconRefresh} aria-label="刷新用量统计" title="刷新用量统计" disabled={busy || !available} onClick={() => { setRange(usageRange(periods[period])); setRefresh(value => value + 1); }}/></header>
        <div className="pw-usage-range-caption"><span>{conciseDate(range.days[0])} — {conciseDate(range.days.at(-1) || '')}</span><span>{range.query.zoneId} · 自然日</span></div>
        {!available && <p className="pw-usage-state">当前应用版本未提供趋势统计，可先查看调用记录。</p>}
        {error && <div className="pw-usage-error" role="alert"><p>{error}</p>{current && <small>下方保留上次成功读取的统计。</small>}<Button variant="ghost" disabled={busy} onClick={() => setRefresh(value => value + 1)}>重新读取</Button></div>}
        {busy && <p className="pw-usage-loading" role="status">正在读取调用回执…</p>}
        {current && <>
          <section className="pw-usage-overview" aria-label="区间用量汇总"><div className="pw-usage-total"><span>{partial ? '已知 Token 小计' : '已记录 Token'}</span><strong title={`${countLabel(totals!.normalized.total)} tokens`}>{tokenNumber(totals!.normalized.total.knownSubtotal, true)}</strong><p>{totals!.attempts} 次调用 · {current.taskCount} 个任务{partial ? ' · 部分数据' : ''}</p></div><SummaryFacts value={totals!}/></section>
          {totals!.attempts === 0 && <div className="pw-usage-empty"><IconFileText size={22} aria-hidden/><span><strong>这个时间范围暂无调用记录</strong><p>开始对话后，这里会逐日显示保留的 Token 用量。</p></span></div>}
          <section className="pw-usage-chart-section"><header className="pw-usage-section-heading"><h3>每日趋势</h3></header><LiquidTabs tabs={chartMetrics.map(key => metricLabels[key])} value={metricIndex} onValueChange={setMetricIndex} label="趋势指标" variant="minimal" className="pw-usage-chart-metrics"/>
            <DailyChart report={current} days={range.days} metric={chartMetrics[metricIndex]} selected={selectedDay || range.days.at(-1) || ''} onSelect={setSelectedDay}/></section>
          <CacheEfficiency value={totals!}/><UsageGroups report={current}/><Money report={current}/>
          <Accordion variant="divided" className="pw-usage-coverage-details"><AccordionItem id="usage-coverage" title="统计范围与数据完整性" description={partial ? '有未报告或未完整读取的部分' : '本机保留的调用回执'}>
            <p>按请求开始时间归入设备时区的自然日。统计本机主人私人范围内保留的实际调用，重试、压缩与辅助请求分别保留；其它设备、服务账单和未保留的历史不在其中。</p>
            <p>总量：{countCoverage(totals!.normalized.total)}。输入与输出的小计可能覆盖不同样本，不能拿两项部分小计拼成精确总量。</p>
            {current.queryLimited && <p>本次读取达到数据上限，只能显示已读取部分。</p>}
            {(current.conflictingCalls > 0 || current.invalidReceipts > 0 || current.invalidTimestamps > 0 || current.oversizedRows > 0) && <p>未计入无法核对的记录：{current.conflictingCalls} 条冲突、{current.invalidReceipts} 条无效回执、{current.invalidTimestamps} 条缺少时间、{current.oversizedRows} 条超出单条读取大小。</p>}
            <p>失败 {totals!.outcomes.FAILED || 0} 次 · 取消 {totals!.outcomes.CANCELLED || 0} 次 · 尚未结束 {totals!.outcomes.STARTED || 0} 次。有报告的消耗仍保留。</p>
            <p>缺记录、未报告、本地估算和服务报告的 0 各有不同含义。上下文占用请在对话输入框的小圆环查看。</p>
          </AccordionItem></Accordion>
          {current.sourceReadAt > 0 && <p className="pw-usage-updated">更新于 {new Date(current.sourceReadAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</p>}
        </>}
      </TabPanel>
      {records && <TabPanel id="records">{records}</TabPanel>}
      {renderBudget && <TabPanel id="budget">{budgetEditing && <p className="pw-usage-caption" role="status">请先完成当前预算操作；保存结果待确认时，可读取原设置核对。</p>}{renderBudget(setBudgetEditing)}</TabPanel>}
    </Tabs>
    {diagnostics && <div className="pw-usage-diagnostics">{diagnostics}</div>}
  </section>;
}
