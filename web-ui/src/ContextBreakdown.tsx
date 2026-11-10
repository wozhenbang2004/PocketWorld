import { useEffect, useId, useState, type CSSProperties } from 'react';
import type { ContextComposition, ContextMetric } from './composer-context-metric';
import { contextPercentLabel } from './composer-context-metric';
import './context-breakdown.css';

const number = (value: number | null) => value === null ? '未测得' : value.toLocaleString('zh-CN');
const explanation: Record<string, string> = {
  system: '本次请求发送的系统规则与角色指令。',
  tools: '提供给模型的工具名称、参数和使用说明；不包含工具运行结果。',
  reference: '本次实际带入的记忆、能力说明、时间与恢复参考。尚不能单独拆出纯记忆占用。',
  memory: '本次请求实际带入的记忆参考。',
  summary: '本次请求使用的上下文摘要。原始记录仍按原任务保留。',
  history: '本次保留的对话消息，以及无法进一步可靠分类的上下文。',
  current_input: '已发送到这次请求的本轮输入，不包含编辑框里尚未发送的草稿。',
  tool_results: '工具返回给模型的内容，如网页、文件、手机观察与执行回执。',
  attachments: '请求内图片的保守预算。文档抽取出的文字归入所在消息；未取得可靠图像预算时显示未测得。',
  overhead: '消息封装、协议结构开销与请求估计器的保守预留。',
};

function partStyle(index: number): CSSProperties {
  return { '--pw-context-tone': `var(--pw-context-tone-${index % 5})` } as CSSProperties;
}

/** Adapts the retained, approved Affect reference family's CompactBreakdownDemo
 * (metrics.tsx), UsageMeter (inputs.tsx) and ContextWindowDemo (context.tsx).
 * Archive 0e69029b: these are independently implemented public-preview references,
 * not Affect Pro source. Native numbers replace every sample and simulated action.
 */
export function ContextBreakdown({ value, identity = '' }: { value: ContextComposition | null; identity?: string }) {
  const [hover, setHover] = useState<string | null>(null), [picked, setPicked] = useState<string | null>(null);
  const descriptionId = useId();
  useEffect(() => { setHover(null); setPicked(null); }, [identity]);
  const parts = value?.parts || [], active = hover ?? picked;
  const selected = parts.find(part => part.id === active);
  const total = value?.partsAgree ? value.localInputTokens : null;
  const ratio = (tokens: number | null) => tokens !== null && total !== null && total > 0 ? tokens / total * 100 : null;
  return <section className="pw-context-composition" aria-label="上下文构成">
    <header><h3>上下文构成</h3><span>本地估算</span></header>
    {!value ? <p className="pw-context-composition-note">这次请求没有保留构成明细；不能由累计 Token 反推。</p> : <>
      {total !== null && total > 0 && <div className="pw-context-composition-bar" aria-hidden="true">
        {parts.map((part, index) => part.tokens !== null && part.tokens > 0 && <i key={part.id}
          data-faded={active !== null && active !== part.id}
          style={{ ...partStyle(index), width: `${ratio(part.tokens)}%` }} />)}
      </div>}
      {!value.additive && <p className="pw-context-composition-note">
        {value.partsAgree ? '构成占比基于独立的本地结构估算；服务校准后的占窗单独显示。' : '部分分项未能完整计量，暂不绘制比例。'}
      </p>}
      <div className="pw-context-parts" role="list" aria-label="按请求内容分类">
        {parts.map((part, index) => <div key={part.id} role="listitem">
          <button type="button" className="pw-context-part" data-selected={active === part.id} style={partStyle(index)}
            aria-pressed={picked === part.id} aria-describedby={active === part.id ? descriptionId : undefined}
            onMouseEnter={() => setHover(part.id)} onMouseLeave={() => setHover(null)}
            onFocus={() => setHover(part.id)} onBlur={() => setHover(null)} onClick={() => setPicked(current => current === part.id ? null : part.id)}>
            <i aria-hidden="true"/><span>{part.label}</span><strong>{part.tokens === null ? '未测得' : `≈${number(part.tokens)}`}</strong>
            <small>{ratio(part.tokens) === null ? '—' : contextPercentLabel(ratio(part.tokens))}</small>
          </button>
        </div>)}
      </div>
      <div className="pw-context-part-detail" id={descriptionId} aria-live="polite">
        {selected ? <><strong>{selected.label}</strong><p>{explanation[selected.id]}</p>
          {(selected.messages !== null && selected.messages > 0 || selected.images !== null && selected.images > 0) && <small>
            {selected.messages !== null && selected.messages > 0 ? `${number(selected.messages)} 条消息` : ''}
            {selected.messages !== null && selected.messages > 0 && selected.images !== null && selected.images > 0 ? ' · ' : ''}
            {selected.images !== null && selected.images > 0 ? `${number(selected.images)} 个图像输入` : ''}
          </small>}
        </> : <p>点选分项查看来源。分项估算并非服务实际分词计数；命中缓存的内容仍然占用上下文。</p>}
      </div>
      {!value.additive && (value.providerInputTokens !== null || value.providerAdjustmentTokens !== null) && <dl className="pw-context-calibration">
        {value.localInputTokens !== null && <div><dt>本地结构估算</dt><dd>≈{number(value.localInputTokens)}</dd></div>}
        {value.providerInputTokens !== null && <div><dt>服务输入采样</dt><dd>{number(value.providerInputTokens)}</dd></div>}
        {value.providerAdjustmentTokens !== null && <div><dt>预算校准差额</dt><dd>{value.providerAdjustmentTokens > 0 ? '+' : ''}{number(value.providerAdjustmentTokens)}</dd></div>}
      </dl>}
    </>}
  </section>;
}

/** The window is one request's occupancy, not accumulated provider consumption. */
export function ContextWindowSummary({ metric }: { metric: ContextMetric }) {
  const { inputTokens: input, inputLimit: budget, windowTokens: window, outputReserve: output } = metric;
  const hasWindow = input !== null && budget !== null && window !== null && window > 0 && output !== null;
  const withinBudget = hasWindow && input <= budget;
  const free = withinBudget ? budget - input : null;
  const safety = withinBudget ? window - budget - output : null;
  return <section className="pw-context-window-summary" aria-label="上次请求的上下文窗口">
    <div className="pw-context-window-heading"><div><span>上次请求占用</span><strong>{input === null ? '未测得' : `≈${number(input)}`}<small>tokens</small></strong>
      <p>{window === null ? '模型窗口未知' : `模型窗口 ${number(window)} tokens`}</p></div>
      {metric.windowPercent !== null && <b data-attention={metric.attention || undefined}>{contextPercentLabel(metric.windowPercent)}</b>}
    </div>
    {hasWindow && <>
      <div className="pw-context-window-bar" role="img" aria-label={`输入约 ${number(input)}，模型窗口 ${number(window)}，输出预留 ${number(output)} tokens`}>
        <i className="pw-context-window-used" style={{ width: `${Math.min(100, input / window * 100)}%` }}/>
        {withinBudget && <><i className="pw-context-window-free" style={{ width: `${free! / window * 100}%` }}/>
          <i className="pw-context-window-reserved" style={{ width: `${output / window * 100}%` }}/>
          {safety! > 0 && <i className="pw-context-window-safety" style={{ width: `${safety! / window * 100}%` }}/>}</>}
      </div>
      <dl className="pw-context-window-key">
        <div><dt><i className="pw-context-window-free"/>可用输入</dt><dd>{number(Math.max(0, budget - input))}</dd></div>
        <div><dt><i className="pw-context-window-reserved"/>输出预留</dt><dd>{number(output)}</dd></div>
        {safety !== null && safety > 0 && <div><dt><i className="pw-context-window-safety"/>安全余量</dt><dd>{number(safety)}</dd></div>}
      </dl>
    </>}
  </section>;
}
