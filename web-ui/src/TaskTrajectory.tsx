import { useCallback, useEffect, useId, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { RiCloseLine, RiListUnordered, RiRefreshLine, RiTimeLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, SearchField } from './forms-library';
import { Select, SelectItem } from './forms-library/boardui/components/base/select/select';
import { Spinner } from './forms-library/boardui/components/base/spinner/spinner';
import { Drawer, LiquidTabs, Tabs, TabList, Tab, TabPanel } from './shell-library';
import { TraceWaterfall } from './library/adapted/trace-waterfall';
import { TraceOverview } from './library/adapted/trace-overview';
import { TraceEventList } from './library/adapted/trace-event-list';
import { MarkdownBody } from './library/MarkdownBody';
import { durationLabel, executionTraceDetail, executionTracePage, mergeTraceNodes, nodeDurationLabel, tokenLabel, traceBody, traceBounds, traceCount,
  traceEventMatches, traceEvents, traceFailed, traceInProgress, traceRequestTiming, traceRole, traceRoleLabel, traceRows, traceStatus, traceSummary,
  traceTime, traceTurnCount, traceUsageBreakdown, type ExecutionTraceDetail, type ExecutionTracePage, type TraceAggregate, type TraceFilter, type TraceNode } from './execution-trace';
import { taskHistoryIdentity, type TaskHeader } from './task-history';
import './execution-trace.css';

type TraceWindow = { scope: string; page: ExecutionTracePage; complete: boolean; backfilling: boolean; pages: number };
type Selection = { node: TraceNode; taskId: string; runId: string; epoch: number; documentEpoch: number };
const filters: TraceFilter[] = ['all', 'model', 'tool', 'failed'];
const POLL_MS = 3_000;
const errorMessage = (error: unknown) => error instanceof Error ? error.message : String(error);
const aggregateLabel = (value: TraceAggregate) => `${tokenLabel(value.value)}${value.value !== null && (value.missing > 0 || !value.final) ? ' +' : ''}`;
const bodyKeys = ['message', 'preview', 'raw', 'input', 'output', 'options', 'schema', 'error', 'sourceRefs'] as const;
const getSourceRevision = () => String(bridge.getState().state.traceSourceRevision ?? 0);
function metadataOnly(node: TraceNode): TraceNode {
  const metadata = { ...node }; for (const key of bodyKeys) delete metadata[key]; return metadata;
}

/** The native source has independent page/detail slots. Complete the bounded
 * compact pages in sequence, keeping node identity stable while live data grows. */
function useTraceWindow(task: TaskHeader, selectedRun: string, active: boolean, canRead: boolean, documentEpoch: number) {
  const scope = JSON.stringify([taskHistoryIdentity(task), selectedRun, documentEpoch]);
  const [visible, setVisible] = useState(() => !document.hidden);
  const [loaded, setLoaded] = useState<TraceWindow | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const enabled = active && canRead && visible;
  const alive = useRef(false), request = useRef(0), pending = useRef<number | null>(null);
  const loadedRef = useRef<TraceWindow | null>(null), pollFailed = useRef(false);
  const queuedRefresh = useRef<string | null>(null);
  const current = useRef({ scope, enabled }); current.current = { scope, enabled };
  const view = loaded?.scope === scope ? loaded : null;
  useEffect(() => {
    const changed = () => setVisible(!document.hidden), hide = () => setVisible(false);
    document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', hide); window.addEventListener('pageshow', changed);
    return () => { document.removeEventListener('visibilitychange', changed); window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', changed); };
  }, []);
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; }; }, []);
  useEffect(() => { request.current++; pending.current = null; queuedRefresh.current = null; loadedRef.current = null; pollFailed.current = false; setLoaded(null); setBusy(false); setError(''); }, [scope]);
  useEffect(() => {
    if (!enabled) { request.current++; pending.current = null; queuedRefresh.current = null; setBusy(false); return; }
    return () => {
      request.current++; pending.current = null; queuedRefresh.current = null;
      // Closing a read window cannot stop the task. A run change uses its next
      // read directly, so a late close cannot cancel the new run's read.
      if (bridge.getState().capabilities.commands.includes('trace.close')) void bridge.command('trace.close').catch(() => {});
    };
  }, [enabled]);
  const read = useCallback(async (automatic = false) => {
    if (!current.current.enabled || current.current.scope !== scope) return;
    if (pending.current !== null) {
      // A live→terminal task update must read once after the in-flight snapshot,
      // whose native cursor may have been captured before completion.
      if (!automatic) queuedRefresh.current = scope;
      return;
    }
    const previous = loadedRef.current?.scope === scope ? loadedRef.current : null;
    const ticket = ++request.current; pending.current = ticket; setBusy(true);
    if (!automatic) { setError(''); pollFailed.current = false; }
    const ownsWindow = () => alive.current && current.current.enabled && current.current.scope === scope && request.current === ticket;
    const valid = () => ownsWindow() && bridge.getDocumentEpoch() === documentEpoch;
    try {
      let cursor = '', runId = selectedRun, epoch: number | undefined, pages = 0, limited = false;
      let collected: TraceNode[] = [], head: ExecutionTracePage | undefined;
      const prior: string[] = [];
      do {
        const result = await bridge.command('trace.read', { taskId: task.id, runId, cursor, limit: 100 });
        if (!valid()) return;
        const page = executionTracePage(result, task.id, runId, cursor, prior, epoch);
        if (!head) { head = page; runId = page.runId; epoch = page.epoch; }
        collected = mergeTraceNodes(collected, page.nodes); pages++; limited ||= page.limited;
        const sameRun = previous?.page.runId === runId && previous.page.epoch === epoch;
        // While refreshing, keep earlier visible rows until the new snapshot is
        // complete. The completed cycle replaces them, so removed IDs can expire.
        const nodes = page.hasMore && sameRun ? mergeTraceNodes(previous.page.nodes, collected) : collected;
        const next = { scope, page: { ...head, nodes, hasMore: page.hasMore, nextCursor: page.nextCursor, limited },
          complete: !page.hasMore && !limited, backfilling: page.hasMore, pages };
        loadedRef.current = next; setLoaded(next); setError(''); pollFailed.current = false;
        prior.push(page.cursor); cursor = page.hasMore ? page.nextCursor : '';
      } while (cursor && valid());
    } catch (failure) {
      if (valid()) {
        setError(errorMessage(failure)); pollFailed.current = true;
        const partial = loadedRef.current;
        if (partial?.scope === scope && partial.backfilling) {
          const stopped = { ...partial, backfilling: false, complete: false }; loadedRef.current = stopped; setLoaded(stopped);
        }
      }
    } finally {
      if (pending.current === ticket) pending.current = null;
      if (ownsWindow()) setBusy(false);
      if (valid() && queuedRefresh.current === scope) {
        queuedRefresh.current = null; void readRef.current(false);
      }
    }
  }, [scope, selectedRun, task.id, documentEpoch]);
  const readRef = useRef(read); readRef.current = read;
  const live = !selectedRun && traceInProgress(task.status);
  useEffect(() => {
    if (!enabled) return;
    let stopped = false, timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async (first = false) => {
      if (stopped || current.current.scope !== scope || !current.current.enabled) return;
      if ((first || live) && (!pollFailed.current || first)) await readRef.current(!first);
      if (!stopped && live) timer = setTimeout(() => { void poll(); }, POLL_MS);
    };
    void poll(true);
    return () => { stopped = true; if (timer) clearTimeout(timer); };
  }, [enabled, scope, live]);
  return { view, busy, error, enabled, live: enabled && live && !error, refresh: () => { void read(); } };
}

function useTraceDetail(selection: Selection | null, active: boolean, metadataRevision: string, sourceRevision: string) {
  const key = selection ? JSON.stringify([selection.taskId, selection.runId, selection.epoch, selection.node.id, selection.documentEpoch, sourceRevision]) : '';
  const [loaded, setLoaded] = useState<{ key: string; value: ExecutionTraceDetail } | null>(null);
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null), [loadingKey, setLoadingKey] = useState(''), [retry, setRetry] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    const ticket = ++generation.current;
    if (!selection || !active) { setLoadingKey(''); return; }
    let disposed = false;
    const valid = () => !disposed && generation.current === ticket && bridge.getDocumentEpoch() === selection.documentEpoch && getSourceRevision() === sourceRevision;
    setFailure(null); setLoaded(previous => previous?.key === key ? previous : null); setLoadingKey(key);
    if (!bridge.getState().capabilities.commands.includes('trace.detail')) {
      setLoadingKey(''); setFailure({ key, message: '当前应用未提供正文详情读取，已保留调用元数据。' }); return;
    }
    void bridge.command('trace.detail', { taskId: selection.taskId, runId: selection.runId, epoch: selection.epoch, nodeId: selection.node.id })
      .then(value => { if (valid()) setLoaded({ key, value: executionTraceDetail(value, selection.taskId, selection.runId, selection.epoch, selection.node.id) }); })
      .catch(error => { if (valid()) setFailure({ key, message: errorMessage(error) }); })
      .finally(() => { if (valid()) setLoadingKey(''); });
    return () => { disposed = true; generation.current++; };
    // The identity tuple owns the body. Compact metadata refreshes must not
    // restart an independent detail read or disturb the selected tab.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, active, retry, metadataRevision]);
  return { detail: loaded?.key === key ? loaded.value : null, error: failure?.key === key ? failure.message : '',
    busy: !!key && loadingKey === key, retry: () => setRetry(value => value + 1) };
}

function TraceBody({ value, missing, preview = false }: { value: unknown; missing: string; preview?: boolean }) {
  const body = traceBody(value);
  return body.text ? <>{preview ? <MarkdownBody text={body.text} className="pw-trace-markdown"/> : <pre className="pw-trace-body-copy">{body.text}</pre>}
    {body.truncated && <p className="pw-trace-note">正文较长，当前展示前 65,536 个字符。</p>}</> : <p className="pw-trace-note">{missing}</p>;
}

function TokenDetails({ node, nodes, complete }: { node: TraceNode; nodes: TraceNode[]; complete: boolean }) {
  const [scope, setScope] = useState(0);
  const values = traceUsageBreakdown(scope === 0 ? [node] : nodes);
  function split(label: string, total: TraceAggregate, left: TraceAggregate, leftLabel: string, right: TraceAggregate, rightLabel: string) {
    const ratio = total.value !== null && total.value > 0n && left.value !== null && right.value !== null && left.missing === 0 && right.missing === 0
      && left.value + right.value === total.value ? Number(left.value * 10_000n / total.value) / 100 : null;
    return <section className="pw-trace-usage-group" aria-label={`${label}用量`}><header><h4>{label}</h4><strong>{aggregateLabel(total)} <small>tokens</small></strong></header>
      {ratio !== null && <div className="pw-trace-usage-bar" aria-hidden="true"><i style={{ width: `${ratio}%` }}/></div>}
      <dl className="pw-trace-facts"><div><dt>{leftLabel}</dt><dd>{aggregateLabel(left)}</dd></div><div><dt>{rightLabel}</dt><dd>{aggregateLabel(right)}</dd></div></dl>
    </section>;
  }
  return <section className="pw-trace-token-details" aria-label="请求用量">
    <LiquidTabs tabs={['本次请求', '本次执行累计']} value={scope} onValueChange={setScope} label="用量范围" variant="minimal" className="pw-trace-usage-scope"/>
    {scope === 1 && <p className="pw-trace-note" data-cumulative-state={complete ? 'complete' : 'partial'}>{complete ? '本次执行的已保存请求均已读取' : '本次执行仍有记录未补齐'} · 累计 {values.models} 次物理模型请求。</p>}
    {split('输入', values.input, values.cacheRead, '缓存读取', values.otherInput, '其他输入')}
    {split('输出', values.output, values.reasoning, '思考', values.content, '内容')}
    <dl className="pw-trace-facts pw-trace-usage-extra"><div><dt>缓存写入</dt><dd>{aggregateLabel(values.cacheWrite)}</dd></div><div><dt>服务总量</dt><dd>{aggregateLabel(values.providerTotal)}</dd></div></dl>
    <p className="pw-trace-note">仅统计服务回执。缓存和思考分项按服务的包含关系计算，不会重复相加；“+”表示只读到部分用量。未报告的分项保留“未记录”。</p>
    {scope === 0 && <Accordion variant="divided"><AccordionItem id="provider-usage" title="服务原始用量" headingLevel={4}><TraceBody value={node.usage} missing="这次请求未保留服务用量回执。"/></AccordionItem></Accordion>}
  </section>;
}

function TimingDetails({ node }: { node: TraceNode }) {
  const timing = traceRequestTiming(node);
  return <section className="pw-trace-timing-details"><dl className="pw-trace-facts">
    <div><dt>开始</dt><dd>{traceTime(node.startedAt, true)}</dd></div>
    <div><dt>结束</dt><dd>{node.finishedAt === null && traceInProgress(node.status) ? '尚未结束' : traceTime(node.finishedAt, true)}</dd></div>
    <div><dt>总耗时</dt><dd>{nodeDurationLabel(node)}</dd></div>
    {node.kind === 'model' && <><div><dt>首段文字</dt><dd>{timing.firstText === null ? '未记录' : durationLabel(timing.firstText)}</dd></div>
      <div><dt>生成阶段</dt><dd>{timing.generation === null ? '未记录' : durationLabel(timing.generation)}</dd></div>
      <div><dt>输出速率</dt><dd>{timing.throughput === null ? '未记录' : `${timing.throughput.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} token/s`}<small>输出 token / 客户端文字区间</small></dd></div></>}
    {node.elapsedNanos !== null && <div><dt>原耗时</dt><dd>{node.elapsedNanos} ns</dd></div>}
  </dl>
  {node.kind === 'model' && <p className="pw-trace-note">首段文字来自实际可见文字增量。生成阶段为客户端首次至最后一次可见文字的观测区间。输出速率使用服务最终输出计数，可能包含思考或工具参数，也受网络影响，不是精确文字生成速度。没有观测时不估算。</p>}
  {node.wallClockAdjusted && <p className="pw-trace-note">执行期间系统时钟有调整，耗时仍使用本机单调计时。</p>}
  {node.timingMeaning === 'native_tool_handling_interval_including_checks_and_receipt_persistence' && <p className="pw-trace-note">工具耗时包含执行检查与回执保存。</p>}
  </section>;
}

function SourceDetails({ node, selection }: { node: TraceNode; selection: Selection }) {
  const route = node.route || {};
  return <Accordion variant="divided" className="pw-trace-disclosures"><AccordionItem id="trace-source" title="来源与关联" headingLevel={4}>
    <dl className="pw-trace-facts"><div><dt>来源</dt><dd>{traceBody(node.source).text || '未记录'}</dd></div>
      <div><dt>执行</dt><dd>{selection.runId}</dd></div><div><dt>恢复版本</dt><dd>{selection.epoch}</dd></div>
      <div><dt>记录标识</dt><dd>{node.id}</dd></div>
      {node.turnIndex != null && <div><dt>轮次</dt><dd>Turn {node.turnIndex}</dd></div>}
      {node.turnId && <div><dt>轮次标识</dt><dd>{node.turnId}</dd></div>}
      {node.stepId && <div><dt>步骤标识</dt><dd>{node.stepId}</dd></div>}
      {node.stepIndex != null && <div><dt>步骤</dt><dd>{node.stepIndex}</dd></div>}
      {node.requestId && <div><dt>关联请求</dt><dd>{node.requestId}</dd></div>}
      {node.parentId && <div><dt>上级记录</dt><dd>{node.parentId}</dd></div>}
      {node.callId && <div><dt>调用标识</dt><dd>{node.callId}</dd></div>}
      {node.providerToolCallId && <div><dt>服务工具标识</dt><dd>{node.providerToolCallId}</dd></div>}
      {node.operationId && <div><dt>动作记录</dt><dd>{node.operationId}</dd></div>}
      {node.memberId && <div><dt>团队成员</dt><dd>{node.memberId}</dd></div>}
      {node.sourceStatus && <div><dt>原记录状态</dt><dd>{node.sourceStatus}</dd></div>}
      {!!(node.logicalRequestId || route.logicalRequestId) && <div><dt>逻辑请求</dt><dd>{String(node.logicalRequestId || route.logicalRequestId)}</dd></div>}
      {node.completionMeaning && <div><dt>回执语义</dt><dd>{node.completionMeaning}</dd></div>}
    </dl>
    {!!Object.keys(route).length && <TraceBody value={route} missing=""/>}
    {node.sourceRefs != null && <TraceBody value={node.sourceRefs} missing=""/>}
  </AccordionItem></Accordion>;
}

/** Category tabs and the two drawer detents are adapted from Whaleness
 * EventDetailSheet (MIT, Chaofan Li), using the project's adopted Vaul/BoardUI. */
function TraceDetails({ selection, page, complete, close, active }: { selection: Selection | null; page?: ExecutionTracePage; complete: boolean; close: () => void; active: boolean }) {
  const descriptionId = useId();
  const sourceRevision = useSyncExternalStore(bridge.subscribe, getSourceRevision, getSourceRevision);
  const compact = selection && page?.runId === selection.runId && page.epoch === selection.epoch ? page.nodes.find(node => node.id === selection.node.id) : undefined;
  const read = useTraceDetail(selection, active, compact ? `${compact.status}:${compact.finishedAt}:${compact.hasError}` : '', sourceRevision);
  const [tab, setTab] = useState('summary'), [snap, setSnap] = useState<number | string | null>(.64);
  const key = selection ? `${selection.runId}:${selection.epoch}:${selection.node.id}` : '';
  useEffect(() => { setTab('summary'); setSnap(.64); }, [key]);
  // Preserve fetched body fields, while a live page can update measured metadata.
  const metadata = compact || read.detail?.node || selection?.node;
  const node = read.detail?.sourceState === 'current' ? { ...read.detail.node, ...(compact || {}), ...Object.fromEntries(bodyKeys
    .map(key => [key, read.detail!.node[key]])) } : metadata ? metadataOnly(metadata) : undefined;
  const role = node ? traceRole(node) : 'event';
  const tabs = node?.kind === 'model' ? [['summary', '摘要'], ['options', '选项'], ['usage', '用量'], ['timing', '耗时']]
    : node?.kind === 'tool' ? [['summary', '摘要'], ['input', '参数'], ['output', '结果'], ...(role === 'subtool' ? [] : [['schema', 'Schema']]), ['timing', '耗时']]
      : [['summary', '摘要'], ['preview', '预览'], ['raw', '原始']];
  const sourceUnavailable = read.detail && read.detail.sourceState !== 'current';
  const missing = read.busy ? '正在读取原记录正文…' : sourceUnavailable ? read.detail!.sourceReason || '原记录正文已不可用，调用元数据仍保留。' : '原记录未保留这项内容。';
  return <Drawer.NestedRoot open={active && !!selection} onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}
    snapPoints={[.64, .94]} activeSnapPoint={snap} setActiveSnapPoint={setSnap} fadeFromIndex={0}>
    <Drawer.Portal><Drawer.Overlay className="pw-overlay pw-trace-overlay"/>
      <Drawer.Content className="pw-drawer pw-trace-detail" aria-describedby={descriptionId}>
        <Drawer.Handle aria-label="拖动调整详情高度"/>
        <header className="pw-drawer-header"><div><Drawer.Title>{node?.kind === 'model' ? 'Request' : node ? traceRoleLabel(node) : '记录详情'}{node?.sequence != null && <small>#{node.sequence}</small>}</Drawer.Title>
          <Drawer.Description id={descriptionId}>{node ? `${node.name} · ${traceStatus(node.status)}` : '原任务的一次调用'}</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="关闭调用详情" onClick={close}/></header>
        {node && selection && <Tabs selectedKey={tab} onSelectionChange={value => { setTab(String(value)); setSnap(.94); }} className="pw-trace-detail-tabs">
          <TabList aria-label="记录详情页签">{tabs.map(([id, label]) => <Tab id={id} key={id}>{label}</Tab>)}</TabList>
          <div className="pw-trace-detail-scroll" style={{ maxHeight: `calc(${typeof snap === 'number' ? snap * 100 : 94}dvh - 148px)` }} data-vaul-no-drag>
            {read.busy && <p className="pw-trace-note pw-trace-detail-loading" role="status"><Spinner size="xs" aria-hidden/>正在读取原记录正文…</p>}
            {read.error && <div className="pw-trace-detail-source" role="alert"><p>{read.error}</p><Button variant="ghost" onClick={read.retry}>重读详情</Button></div>}
            {sourceUnavailable && <p className="pw-trace-detail-source" role="status" data-source-state={read.detail!.sourceState}>{missing}</p>}
            {node.contentLimited && <p className="pw-trace-note">正文仅保留已记录的部分。</p>}
            <TabPanel id="summary">
              <dl className="pw-trace-facts pw-trace-primary-facts"><div><dt>状态</dt><dd>{traceStatus(node.status)}</dd></div><div><dt>耗时</dt><dd>{nodeDurationLabel(node)}</dd></div>
                {node.model && <div><dt>模型</dt><dd>{node.model}</dd></div>}
                {node.turnIndex != null && <div><dt>轮次</dt><dd>Turn {node.turnIndex}{node.stepIndex != null ? ` · Step ${node.stepIndex}` : ''}</dd></div>}
                {!!(node.attemptNo || node.route?.attemptNo) && <div><dt>本次尝试</dt><dd>{String(node.attemptNo || node.route?.attemptNo)}</dd></div>}
                {node.purpose && <div><dt>用途</dt><dd>{node.purpose}</dd></div>}
              </dl>
              {node.legacy && <p className="pw-trace-note">早先记录只展示已保存字段，缺失的轮次、内容和耗时不会补造。</p>}
              {node.effectPending && <p className="pw-trace-note">工具处理已返回；动作效果仍等待原执行确认。</p>}
              {(traceFailed(node) || traceBody(node.error).text) && <section className="pw-trace-error-detail" aria-label="调用错误"><h4>错误</h4><TraceBody value={node.error} missing={read.busy ? missing : '这条记录标记了错误，错误正文未保留。'}/></section>}
              {node.kind === 'model' ? <>
                <p className="pw-trace-note">这是一次物理模型请求。消息记录与请求共用关联标识，用量只计入请求一次。</p>
                <dl className="pw-trace-facts pw-trace-request-tokens"><div><dt>输入 token</dt><dd>{tokenLabel(traceCount(node.usage, 'INPUT')?.value ?? null)}</dd></div><div><dt>输出 token</dt><dd>{tokenLabel(traceCount(node.usage, 'OUTPUT')?.value ?? null)}</dd></div></dl>
                <Accordion variant="divided"><AccordionItem id="trace-request-context" title="保留的原上下文" headingLevel={4}><p className="pw-trace-note">请求来源中的原上下文可能与裁剪、投影后的实际发送内容不同。</p><TraceBody value={node.input} missing={missing}/></AccordionItem>
                  <AccordionItem id="trace-request-output" title="返回内容" headingLevel={4}><TraceBody value={node.output} missing={missing}/></AccordionItem></Accordion>
              </> : node.kind === 'tool' ? <section className="pw-trace-event-detail"><h4>结果摘要</h4><TraceBody value={node.preview ?? node.message ?? node.output} missing={missing}/></section>
                : <section className="pw-trace-event-detail"><TraceBody value={node.message ?? node.preview} missing={missing} preview/></section>}
              <SourceDetails node={node} selection={selection}/>
            </TabPanel>
            {node.kind === 'model' ? <>
              <TabPanel id="options"><h4>请求选项</h4><p className="pw-trace-note">已记录的最终请求选项；敏感字段不在展示范围。</p><TraceBody value={node.options} missing={missing}/></TabPanel>
              <TabPanel id="usage"><TokenDetails node={node} nodes={page?.nodes || []} complete={complete}/></TabPanel>
              <TabPanel id="timing"><TimingDetails node={node}/></TabPanel>
            </> : node.kind === 'tool' ? <>
              <TabPanel id="input"><h4>调用参数</h4><TraceBody value={node.input} missing={missing}/></TabPanel>
              <TabPanel id="output"><h4>工具结果</h4><TraceBody value={node.output} missing={traceInProgress(node.status) ? '等待原执行返回。' : missing}/></TabPanel>
              {role !== 'subtool' && <TabPanel id="schema"><h4>调用时的工具定义</h4><TraceBody value={node.schema} missing={missing}/></TabPanel>}
              <TabPanel id="timing"><TimingDetails node={node}/></TabPanel>
            </> : <>
              <TabPanel id="preview"><TraceBody value={node.preview ?? node.message} missing={missing} preview/></TabPanel>
              <TabPanel id="raw"><TraceBody value={node.raw ?? node.message} missing={missing}/></TabPanel>
            </>}
          </div>
        </Tabs>}
      </Drawer.Content>
    </Drawer.Portal>
  </Drawer.NestedRoot>;
}

export function TaskTrajectory({ task, active, canRead, registerTransientBack }: { task: TaskHeader; active: boolean; canRead: boolean; registerTransientBack?: (handler: (() => boolean) | null) => void }) {
  const documentEpoch = useSyncExternalStore(bridge.subscribe, bridge.getDocumentEpoch, bridge.getDocumentEpoch);
  const [popoverContainer, setPopoverContainer] = useState<Element>();
  const bindRoot = useCallback((element: HTMLElement | null) => { if (element) setPopoverContainer(element.closest('[role="dialog"]') || undefined); }, []);
  const [selectedRun, setSelectedRun] = useState(''), [filter, setFilter] = useState<TraceFilter>('all'), [query, setQuery] = useState('');
  const [viewMode, setViewMode] = useState<'events' | 'intervals'>('events'), [overviewMode, setOverviewMode] = useState<'order' | 'duration'>('order');
  const [intervalMode, setIntervalMode] = useState<'timeline' | 'tree'>('timeline'), [folded, setFolded] = useState<Set<string>>(new Set());
  const [selection, setSelection] = useState<Selection | null>(null), [highlighted, setHighlighted] = useState<string | null>(null), [locateTicket, setLocateTicket] = useState(0);
  const readWindow = useTraceWindow(task, selectedRun, active, canRead, documentEpoch), page = readWindow.view?.page;
  const identity = taskHistoryIdentity(task), complete = readWindow.view?.complete === true;
  useEffect(() => { setSelectedRun(''); setSelection(null); setFolded(new Set()); setQuery(''); setFilter('all'); setHighlighted(null); }, [identity, documentEpoch]);
  useEffect(() => { if (!active) setSelection(null); }, [active]);
  useEffect(() => { if (page) setSelection(previous => previous && (previous.runId !== page.runId || previous.epoch !== page.epoch) ? null : previous); }, [page?.runId, page?.epoch]);
  const close = useCallback(() => setSelection(null), []), endHighlight = useCallback(() => setHighlighted(null), []);
  const selected = selection && selection.documentEpoch === documentEpoch && selection.taskId === task.id && page?.runId === selection.runId && page.epoch === selection.epoch ? selection : null;
  useEffect(() => {
    if (!active || !registerTransientBack) return;
    registerTransientBack(() => { if (selected) { close(); return true; } return false; });
    return () => registerTransientBack(null);
  }, [active, selected, close, registerTransientBack]);
  // Discard an expired selection once the full replacement snapshot is known.
  useEffect(() => { if (selected && complete && !page?.nodes.some(node => node.id === selected.node.id)) close(); }, [selected, complete, page, close]);
  const chooseRun = (key: string) => { if (key === selectedRun) return; setSelectedRun(key); setSelection(null); setHighlighted(null); setFolded(new Set()); };
  const toggle = (id: string) => setFolded(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  const nodes = page?.nodes || [];
  const events = useMemo(() => traceEvents(nodes), [page?.nodes]);
  const filteredEvents = useMemo(() => events.filter(row => traceEventMatches(row, filter, query)), [events, filter, query]);
  const stats = traceSummary(nodes), filtering = filter !== 'all' || query.trim() !== '';
  const select = (node: TraceNode) => { if (page) setSelection({ node, taskId: task.id, runId: page.runId, epoch: page.epoch, documentEpoch }); };
  const locate = (node: TraceNode) => { setQuery(''); setFilter('all'); setViewMode('events'); setHighlighted(node.id); setLocateTicket(value => value + 1); };
  const runs = page?.runs || [], currentRunId = typeof task.raw?.runId === 'string' ? task.raw.runId : '';
  return <section ref={bindRoot} className="pw-execution-trace" aria-label="执行调用轨迹" aria-busy={readWindow.busy} data-completeness={complete ? 'complete' : readWindow.view?.backfilling ? 'loading' : 'partial'}>
    <header className="pw-trace-toolbar">
      <Select aria-label="选择执行记录" selectedKey={selectedRun || 'current'} onSelectionChange={key => chooseRun(key === 'current' ? '' : String(key))}
        triggerClassName="pw-trace-run-trigger" popoverClassName="pw-trace-run-popover" popoverContainer={popoverContainer} className="pw-trace-run-select" isDisabled={!canRead}>
        <SelectItem id="current" textValue="当前执行">当前执行</SelectItem>
        {runs.filter(run => run.runId !== currentRunId).map(run => <SelectItem key={run.runId} id={run.runId} textValue={`${traceTime(run.startedAt)} · ${traceStatus(run.status)}`}><span>{traceTime(run.startedAt)}<small>{traceStatus(run.status)}</small></span></SelectItem>)}
      </Select>
      <span className="pw-trace-read-status" role="status">{readWindow.busy ? <><Spinner size="xs" aria-hidden/>{readWindow.view?.backfilling ? '补齐中' : page ? '刷新中' : '读取中'}</>
        : readWindow.live ? <><span className="pw-trace-live-dot"/>实时</> : page ? '已读取' : ''}</span>
      <IconButton icon={RiRefreshLine} aria-label="刷新执行轨迹" disabled={!readWindow.enabled || readWindow.busy} onClick={readWindow.refresh}/>
    </header>
    {!canRead && <p className="pw-trace-note">当前应用尚未开放这份轨迹读取，可在进展记录和工具调用中查看原记录。</p>}
    {readWindow.error && <div className="pw-trace-read-error" role="alert"><p>{readWindow.error}</p>{page && <small>下方保留已读取的轨迹。自动刷新已暂停，刷新后继续补齐。</small>}</div>}
    {page && <>
      <TraceOverview rows={events} mode={overviewMode} onModeChange={setOverviewMode} highlightedId={highlighted} turnCount={traceTurnCount(nodes)} toolCount={stats.tools} onLocate={locate}/>
      <div className="pw-trace-completeness" role="status"><span>{readWindow.view?.backfilling ? '正在补齐较早记录' : complete ? '当前快照已完整读取' : '当前为部分记录'} · {nodes.length} 条</span><span>{stats.models} 次请求{stats.failed ? ` · ${stats.failed} 条异常` : ''}</span></div>
      {stats.models > 0 && <dl className="pw-trace-token-summary" aria-label={complete ? '本次执行模型用量' : '已读取请求的部分用量'}><div><dt>输入 token</dt><dd>{aggregateLabel(stats.input)}</dd></div><div><dt>输出 token</dt><dd>{aggregateLabel(stats.output)}</dd></div></dl>}
      <div className="pw-trace-search-row"><SearchField aria-label="搜索执行轨迹" placeholder="搜索调用、模型、轮次" clearLabel="清空轨迹搜索" value={query} onChange={setQuery}/>
        <IconButton icon={viewMode === 'events' ? RiTimeLine : RiListUnordered} aria-label={viewMode === 'events' ? '查看执行区间' : '返回轮次列表'} onClick={() => setViewMode(viewMode === 'events' ? 'intervals' : 'events')}/>
      </div>
      <LiquidTabs className="pw-trace-filters" variant="minimal" tabs={['全部', '模型', '工具', '异常']} value={filters.indexOf(filter)} onValueChange={index => setFilter(filters[index])} label="轨迹筛选"/>
      {page.limited && <p className="pw-trace-note pw-trace-read-limit">原记录有部分内容未包含；即使已读到分页末尾，仍不是完整记录。</p>}
      {viewMode === 'events' ? filteredEvents.length ? <TraceEventList rows={filteredEvents} selectedId={selected?.node.id || null} highlightedId={highlighted} locateTicket={locateTicket} onHighlightEnd={endHighlight} onSelect={select} complete={complete}/>
        : <div className="pw-trace-empty"><p>{filtering ? '已读取记录中没有匹配项。' : readWindow.live ? '正在等待原执行产生轨迹…' : '这次执行没有已保存的调用轨迹。'}</p>{filtering && <Button variant="ghost" onClick={() => { setFilter('all'); setQuery(''); }}>清除筛选</Button>}</div>
        : <><div className="pw-trace-interval-controls"><p className="pw-trace-note">原始父子关系和实际执行区间</p><Button variant="ghost" onClick={() => setIntervalMode(intervalMode === 'timeline' ? 'tree' : 'timeline')}>{intervalMode === 'timeline' ? '调用树' : '时间轴'}</Button></div>
          <TraceWaterfall rows={traceRows(nodes, folded, filter, query)} bounds={traceBounds(nodes)} mode={intervalMode} selectedId={selected?.node.id || null} filtering={filtering} onToggle={toggle} onSelect={select}/></>}
      <p className="pw-trace-note pw-trace-timing-note">轮次来自原执行记录；总览的累计耗时包含并行重叠。缺失的计时、用量与正文保持可见。</p>
    </>}
    {!page && canRead && !readWindow.busy && !readWindow.error && <p className="pw-trace-note">打开此页后读取原任务轨迹。</p>}
    <TraceDetails selection={selected} page={page} complete={complete} close={close} active={active && readWindow.enabled}/>
  </section>;
}
