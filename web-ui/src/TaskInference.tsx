import { useEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight, IconRefresh } from './icons';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { ContextBreakdown } from './ContextBreakdown';
import { readContextBreakdown } from './composer-context-metric';
import './task-reading.css';

type Data = Record<string, any>;
const list = (value: any): any[] => Array.isArray(value) ? value : [];
const shown = (value: unknown, missing = '未知'): string => value === null || value === undefined || value === '' ? missing : String(value);
const time = (value: unknown) => typeof value === 'number' && value > 0 && Number.isFinite(value) ? new Date(value).toLocaleString() : '时间未记录';

function Counters({ values }: { values: any }) {
  return <dl className="pw-usage-counters">{list(values).map((row, index) => <div key={row.name + ':' + index}><dt>{row.name}</dt><dd>{row.text}</dd></div>)}</dl>;
}
function Lines({ values }: { values: any }) {
  return <div className="pw-usage-lines">{list(values).map((line, index) => <p key={index}>{line}</p>)}</div>;
}

const measuredNumber = (value: unknown): number | null => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
function NativeCount({ label, value }: { label: string; value: any }) {
  const tokens = measuredNumber(value?.knownSubtotal), unknown = measuredNumber(value?.unknownCalls), partial = measuredNumber(value?.partialCalls);
  const knownOnly = (unknown !== null && unknown > 0) || value?.overflow === true;
  return <div><dt>{knownOnly ? `已知${label}` : label}</dt><dd>{tokens === null || value?.overflow === true ? <span className="pw-usage-missing">{value?.overflow === true ? '无法汇总' : '未上报'}</span> : tokens.toLocaleString('zh-CN')}</dd>
    {partial !== null && partial > 0 && <small>{partial.toLocaleString('zh-CN')} 次尚非最终计数</small>}
  </div>;
}

/** All totals and the token-weighted cache ratio are native receipt projections.
 * Do not re-sum raw provider fields in the renderer. */
function UsageSummary({ value }: { value: Data }) {
  const normalized = value.normalized, cache = value.cache;
  if (!normalized || typeof normalized !== 'object' || Array.isArray(normalized)) return null;
  const rate = typeof cache?.rate === 'number' && Number.isFinite(cache.rate) && cache.rate >= 0 && cache.rate <= 1 ? cache.rate : null;
  const eligible = measuredNumber(cache?.eligibleCalls), unknown = measuredNumber(cache?.unknownCalls), partial = measuredNumber(cache?.partialCalls);
  const percent = rate === null ? null : new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 }).format(rate);
  return <section className="pw-usage-summary" aria-label="本任务累计 Token 与缓存">
    <dl className="pw-usage-primary-counts"><NativeCount label="输入 Token" value={normalized.input}/><NativeCount label="输出 Token" value={normalized.output}/></dl>
    <div className="pw-usage-cache"><header><span>输入缓存命中率</span><strong>{percent ?? '未上报'}</strong></header>
      {rate !== null && <div className="pw-usage-cache-meter" role="meter" aria-label="可核对输入 Token 的缓存读取比例" aria-valuemin={0} aria-valuemax={100} aria-valuenow={rate * 100} aria-valuetext={percent!}><i style={{ width: `${rate * 100}%` }}/></div>}
      <p>{rate === null ? '未取得完整的输入与缓存读取计数。' : `按 Token 加权${eligible === null ? '' : ` · ${eligible.toLocaleString('zh-CN')} 次可核对调用`}`}
        {unknown !== null && unknown > 0 ? ` · ${unknown.toLocaleString('zh-CN')} 次未纳入比例` : ''}
        {partial !== null && partial > 0 ? ` · ${partial.toLocaleString('zh-CN')} 次尚非最终计数` : ''}</p>
    </div>
    <dl className="pw-usage-secondary-counts"><NativeCount label="缓存读取" value={normalized.cacheRead}/><NativeCount label="缓存写入" value={normalized.cacheWrite}/><NativeCount label="推理 Token" value={normalized.reasoning}/></dl>
    <p className="pw-reading-note">输入包含已归一化的缓存读写；缓存与推理为分项，不再次加到输入／输出。缓存命中也不减少上下文占用。</p>
  </section>;
}

/** A readable view over the native quote. Exact decimal strings are never rounded or summed here. */
function AlternateEstimate({ value }: { value: Data }) {
  const quote = value.estimate || {}, price = value.currentPrice || {};
  const money = (amount: unknown) => amount === null || amount === undefined ? '未知（未按 0 计算）' : `${shown(quote.currency || price.currency, '币种未知')} ${String(amount)}`;
  const reasons: Record<string, string> = {
    subscription_or_original_receipt_unavailable: '原调用不可用于当前价目试算。',
    current_connection_not_available: '当前连接不可用，无法另行试算。',
    current_price_not_observed: '尚未观察到可核对的当前价目。',
    price_protocol_mismatch: '当前价目与原调用协议不一致。',
    unsupported_pricing_protocol: '此协议暂不支持费用试算。',
    receipt_not_found: '未找到原调用回执，无法另行试算。',
    receipt_identity_conflict: '原调用回执身份存在冲突，无法另行试算。',
    receipt_read_limited: '原调用回执超出当前读取范围，无法另行试算。',
    price_host_unavailable: '当前价目服务不可用，无法另行试算。',
  };
  return <section className="pw-usage-estimate" aria-label="本次另行试算">
    <h4>本次另行试算</h4>
    <p className="pw-reading-note">历史估算未改变。仅 token 分项估算，不是实际账单。</p>
    {value.known === true ? <>
      <dl className="pw-usage-counters">
        <div><dt>已知分项小计</dt><dd>{money(quote.knownTokenSubtotal)}</dd></div>
        <div><dt>全部 token 分项</dt><dd>{money(quote.estimatedTokenCost)}</dd></div>
        <div><dt>完整性</dt><dd>{quote.allTokenComponentsPriced === true ? 'token 分项可估' : '仍有未知分项'} · {quote.finalCounters === true ? '计数为最终返回' : '含非最终或不完整计数'}</dd></div>
        <div><dt>试算时间</dt><dd>{time(value.calculatedAt)}</dd></div>
      </dl>
      <p className="pw-reading-note">未包含请求费、媒体／工具费、税费或折扣；不代表套餐剩余量或金额硬上限。</p>
    </> : <p className="pw-usage-unavailable">{reasons[value.reason] || '本次试算没有可核对的金额。'} 未知金额不代表免费。</p>}
    <Accordion variant="divided" className="pw-reading-disclosures pw-usage-quote-details">
      {list(quote.lines).length > 0 && <AccordionItem id="quote-components" title="试算分项与价目">
        <dl className="pw-usage-counters">{list(quote.lines).map((line, index) => <div key={index}><dt>{line.label}</dt><dd>{money(line.estimatedCost)}<small>{shown(line.tokens, '计数未知')}{line.tokens == null ? '' : ' tokens'} · 每百万 tokens 单价 {shown(line.pricePerMillion)}</small></dd></div>)}</dl>
        <dl className="pw-usage-counters">
          <div><dt>原价目版本</dt><dd>{shown(value.originalPriceVersion)}</dd></div>
          <div><dt>当前价目版本</dt><dd>{shown(price.priceVersion)}</dd></div>
          <div><dt>价目来源</dt><dd>{shown(price.sourceRef)}</dd></div>
        </dl>
      </AccordionItem>}
      <AccordionItem id="quote-receipt" title="原试算回执" description="完整字段与未知项"><pre className="pw-usage-receipt">{JSON.stringify(value, null, 2)}</pre></AccordionItem>
    </Accordion>
  </section>;
}

export function TaskInference({ taskId }: { taskId: string }) {
  const [value, setValue] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [estimate, setEstimate] = useState<{ callId: string; result: Data } | null>(null);
  const alive = useRef(true), entered = useRef(false), handle = useRef(0);
  useEffect(() => {
    alive.current = true;
    handle.current++;
    entered.current = false;
    setValue(null); setEstimate(null); setError(''); setBusy(false);
    return () => { alive.current = false; handle.current++; };
  }, [taskId]);
  const read = async (cursor = 0) => {
    if (entered.current) return;
    entered.current = true;
    const request = ++handle.current;
    setBusy(true); setError('');
    try {
      const result = await bridge.command('usage.task', { taskId, cursor });
      if (!alive.current || handle.current !== request) return;
      if (result.taskId !== taskId || result.readOnly !== true || result.executionAuthorized !== false) throw new Error('原任务用量身份不可核对。');
      setValue(result); setEstimate(null);
    } catch (failure) {
      if (alive.current && handle.current === request) setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      if (alive.current && handle.current === request) { entered.current = false; setBusy(false); }
    }
  };
  const reestimate = async (callId: string) => {
    if (entered.current) return;
    entered.current = true;
    const request = ++handle.current;
    setBusy(true); setError(''); setEstimate(null);
    try {
      const result = await bridge.command('usage.reestimate', { taskId, callId });
      if (!alive.current || handle.current !== request) return;
      // The native host also returns explicit non-quote outcomes before it can
      // load the original receipt or price service. Keep those facts readable;
      // they must never become a quote or a fabricated zero-cost result.
      const receiptUnavailable = result.available === false && result.callId === callId &&
        ['receipt_not_found', 'receipt_identity_conflict', 'receipt_read_limited'].includes(result.reason);
      const priceUnavailable = result.known === false && result.reason === 'price_host_unavailable' && result.newInference === false &&
        (result.callId === undefined || result.callId === callId);
      const quote = result.format === 'pocketworld.inference-reestimate.v1' && result.callId === callId && result.originalReceiptUnchanged === true && result.newInference === false;
      if (!quote && !receiptUnavailable && !priceUnavailable) throw new Error('原调用的试算回执身份不可核对。');
      setEstimate({ callId, result });
    } catch (failure) {
      if (alive.current && handle.current === request) setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      if (alive.current && handle.current === request) { entered.current = false; setBusy(false); }
    }
  };
  // Keep records tied to this task even during a prop change before effect cleanup.
  const current = value?.taskId === taskId ? value : null;
  const groups = list(current?.groups), calls = list(current?.calls);
  const composition = readContextBreakdown(current?.context?.details?.breakdown);
  return <section className="pw-task-inference pw-task-reading" aria-label="原任务模型调用与用量" aria-busy={busy}>
    <header className="pw-usage-heading">
      <div>{current && <p className="pw-reading-eyebrow">模型调用与用量</p>}<h3>{current ? current.headline : '模型调用与用量'}</h3>
        {!current && <p className="pw-reading-note">按需读取此任务保留的调用与估算。</p>}</div>
      <Button variant="ghost" leadingIcon={current ? IconRefresh : undefined} disabled={busy} aria-label={current ? '重新核对累计用量' : '查看累计模型调用与用量'} onClick={() => { void read(); }}>{busy ? '核对中…' : current ? '刷新' : '查看用量'}</Button>
    </header>
    {error && <div className="pw-usage-notice" role="alert"><p>{error}</p>{current && <p className="pw-reading-note">下方仍是上次成功读取的记录。</p>}</div>}
    {current && <>
      <UsageSummary value={current}/>
      <p className="pw-usage-coverage">{current.coverage}</p>
      {list(current.warnings).length > 0 && <aside className="pw-usage-notice" aria-label="用量记录提醒"><Lines values={current.warnings}/></aside>}
      <Accordion variant="divided" className="pw-reading-disclosures pw-usage-sections" defaultExpandedKeys={current.context?.attention ? ['native-usage-context'] : []}>
        <AccordionItem id="native-usage-cost" title="费用估算与未知项" description="沿用冻结价目，不是实际账单">
          <Lines values={current.money}/>
          <p className="pw-reading-note">历史估算沿原冻结价目保留。实际账单和套餐剩余量未观察；服务冷却时间不是套餐重置时间。</p>
        </AccordionItem>
        <AccordionItem id="native-usage-context" title={current.context?.title || '原上下文信息'} description={current.context?.attention ? '需要核对' : '内容构成与窗口预算'}>
          {composition ? <><ContextBreakdown value={composition} identity={`${taskId}:${current.context?.at}`}/>
            <Accordion variant="divided" className="pw-reading-disclosures"><AccordionItem id="native-usage-context-receipt" title="原请求检查" headingLevel={4}><Lines values={current.context?.lines}/></AccordionItem></Accordion>
          </> : <Lines values={current.context?.lines}/>}
        </AccordionItem>
        <AccordionItem id="native-usage-groups" title="模型与用途分组" description={`${groups.length} 组 · 保留原服务计数口径`}>
          {groups.length ? <Accordion variant="divided" className="pw-reading-disclosures pw-usage-records">
            {groups.map((group, index) => <AccordionItem key={index} id={'native-usage-group-' + index} title={group.title} headingLevel={4}>
              <p className="pw-usage-route">{group.route}</p><Counters values={group.counters}/><p className="pw-reading-note">{group.semantics}</p>
            </AccordionItem>)}
          </Accordion> : <p className="pw-reading-note">当前读取没有可展示的分组；不代表实际用量为 0。</p>}
        </AccordionItem>
        <AccordionItem id="native-usage-calls" title="逐次调用记录" description={`当前读取 ${calls.length} 条 · 展开核对原调用`}>
          {calls.length ? <Accordion variant="divided" className="pw-reading-disclosures pw-usage-records">
            {calls.map(call => <AccordionItem key={call.id} id={call.id} headingLevel={4}
              title={<span className="pw-usage-call-title"><span>{call.purpose}</span><small>{call.status}</small></span>} description={time(call.at)}>
              <p className="pw-usage-route">{call.route}</p><p className="pw-reading-note">{call.binding} · {time(call.at)}</p>
              {list(call.failure).length > 0 && <aside className="pw-usage-notice" aria-label="原调用失败或取消信息"><Lines values={call.failure}/></aside>}
              <Counters values={call.counters}/>
              <div className="pw-usage-pricing"><h5>本次保留的估价</h5><Lines values={call.pricing}/></div>
              {call.apiKey && <Button variant="secondary" className="pw-usage-estimate-action" disabled={busy} onClick={() => { void reestimate(call.id); }}>按当前价目另行试算</Button>}
              {estimate && estimate.callId === call.id && <AlternateEstimate value={estimate.result}/>}
            </AccordionItem>)}
          </Accordion> : <p className="pw-reading-note">这一页没有保留的调用记录；不代表实际未发生调用。</p>}
          {(current.cursor > 0 || current.nextCursor >= 0) && <nav className="pw-usage-pagination" aria-label="模型调用记录翻页">
            {current.cursor > 0 && <Button variant="ghost" leadingIcon={IconChevronLeft} disabled={busy} onClick={() => { void read(Math.max(0, current.cursor - 20)); }}>较近调用</Button>}
            {current.nextCursor >= 0 && <Button variant="ghost" trailingIcon={IconChevronRight} disabled={busy} onClick={() => { void read(current.nextCursor); }}>较早调用</Button>}
          </nav>}
        </AccordionItem>
      </Accordion>
    </>}
  </section>;
}
