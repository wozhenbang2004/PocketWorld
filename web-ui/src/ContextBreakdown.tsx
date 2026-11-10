import { useEffect, useId, useState } from 'react';
import { Button } from './forms-library';
import { IconChevronDown } from './icons';
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

/** Adapts the retained, approved Affect reference family's CompactBreakdownDemo
 * linked segments and ranked value columns (metrics.tsx), plus ContextWindowDemo
 * (context.tsx).
 * Archive 0e69029b: these are independently implemented public-preview references,
 * not Affect Pro source. Native numbers replace every sample and simulated action.
 */
export function ContextBreakdown({ value, identity = '' }: { value: ContextComposition | null; identity?: string }) {
  const [picked, setPicked] = useState<string | null>(null), [expanded, setExpanded] = useState(false);
  const listId = useId();
  useEffect(() => { setPicked(null); setExpanded(false); }, [identity]);
  const parts = [...(value?.parts || [])].sort((a, b) => (b.tokens ?? -1) - (a.tokens ?? -1));
  const primary = parts.slice(0, 3), visible = expanded ? parts : primary, unknown = parts.filter(part => part.tokens === null).length;
  const total = value?.partsAgree ? value.localInputTokens : null;
  const ratio = (tokens: number | null) => tokens !== null && total !== null && total > 0 ? tokens / total * 100 : null;
  const tone = (id: string) => { const index = primary.findIndex(part => part.id === id); return index < 0 ? 3 : index; };
  const selectedTone = picked === null ? null : tone(picked);
  // A composition partitions the independently estimated input. It is never
  // scaled against the model window or a provider-calibrated input total.
  const segments = total !== null && total > 0 ? [
    ...primary.map((part, index) => ({ id: part.id, label: part.label, tokens: part.tokens!, tone: index })),
    ...(parts.length > 3 ? [{ id: 'other', label: `其余 ${parts.length - 3} 项`, tokens: parts.slice(3).reduce((sum, part) => sum + part.tokens!, 0), tone: 3 }] : []),
  ].filter(segment => segment.tokens > 0) : [];
  return <section className="pw-context-composition" aria-label="上下文构成">
    <header><h3>{expanded ? '上下文构成' : '主要构成'}</h3><span>本地估算 · tokens</span></header>
    {!value ? <p className="pw-context-composition-note">这次请求未保留构成明细。</p> : <>
      {segments.length > 0 && <div className="pw-context-composition-bar" role="img"
        aria-label={`本地估算构成：${segments.map(segment => `${segment.label}约 ${number(segment.tokens)} tokens`).join('，')}`}>
        {segments.map(segment => (
          <i key={segment.id} data-tone={segment.tone}
            data-faded={selectedTone !== null && selectedTone !== segment.tone || undefined}
            style={{ width: `${segment.tokens / total! * 100}%` }}/>
        ))}
      </div>}
      <div className="pw-context-parts" id={listId} role="list" aria-label="按占用从高到低排列">
        {visible.map(part => <div key={part.id} role="listitem">
          <Button variant="ghost" className="pw-context-part" data-selected={picked === part.id || undefined}
            aria-expanded={picked === part.id} aria-controls={picked === part.id ? `${listId}-${part.id}` : undefined}
            aria-label={`${part.label}，${part.tokens === null ? '未测得' : `约 ${number(part.tokens)} tokens`}，查看来源`}
            onClick={() => setPicked(current => current === part.id ? null : part.id)}>
            <span className="pw-context-part-label"><i aria-hidden="true" data-tone={tone(part.id)}/><span>{part.label}</span></span>
            <strong>{part.tokens === null ? '未测得' : `≈${number(part.tokens)}`}</strong>
            <small>{ratio(part.tokens) === null ? '—' : contextPercentLabel(ratio(part.tokens))}</small>
          </Button>
          {picked === part.id && <div className="pw-context-part-detail" id={`${listId}-${part.id}`}>
            <p>{explanation[part.id]}</p>
            {(part.messages !== null && part.messages > 0 || part.images !== null && part.images > 0) && <small>
              {part.messages !== null && part.messages > 0 ? `${number(part.messages)} 条消息` : ''}
              {part.messages !== null && part.messages > 0 && part.images !== null && part.images > 0 ? ' · ' : ''}
              {part.images !== null && part.images > 0 ? `${number(part.images)} 个图像输入` : ''}
            </small>}
          </div>}
        </div>)}
      </div>
      {parts.length > 3 && <Button variant="ghost" className="pw-context-show-parts" trailingIcon={IconChevronDown}
        aria-expanded={expanded} aria-controls={listId} onClick={() => {
          if (expanded && !parts.slice(0, 3).some(part => part.id === picked)) setPicked(null);
          setExpanded(current => !current);
        }}>{expanded ? '收起明细' : `全部 ${parts.length} 项`}</Button>}
      {unknown > 0 && <p className="pw-context-composition-note">{unknown} 项未测得，暂不显示构成比例。</p>}
      {unknown === 0 && !value.partsAgree && <p className="pw-context-composition-note">分项与本地总量尚未核对，暂不显示比例。</p>}
    </>}
  </section>;
}

/** Kept behind a disclosure in both the composer and the task receipt. */
export function ContextMeasurementDetails({ value }: { value: ContextComposition | null }) {
  return <div className="pw-context-measurement">
    <p>构成按本地估算排序，占比以本地输入总量为分母。它不是服务实际分词计数；命中缓存的内容仍占上下文。</p>
    {value && !value.additive && <>
      <p>构成估算与服务校准后的占窗分别保留，不把校准差额摊入各项。</p>
      <dl className="pw-context-calibration">
        <div><dt>本地结构估算</dt><dd>{value.localInputTokens === null ? '未测得' : `≈${number(value.localInputTokens)}`}</dd></div>
        <div><dt>服务输入采样</dt><dd>{value.providerInputTokens === null ? '未取得' : number(value.providerInputTokens)}</dd></div>
        <div><dt>预算校准差额</dt><dd>{value.providerAdjustmentTokens === null ? '未取得' : `${value.providerAdjustmentTokens > 0 ? '+' : ''}${number(value.providerAdjustmentTokens)}`}</dd></div>
      </dl>
    </>}
  </div>;
}

/** The window is one request's occupancy, not accumulated provider consumption. */
export function ContextWindowSummary({ metric }: { metric: ContextMetric }) {
  const { inputTokens: input, inputLimit: budget, windowTokens: window } = metric;
  const hasWindow = input !== null && window !== null && window > 0;
  const remaining = input !== null && budget !== null ? budget - input : null;
  return <section className="pw-context-window-summary" aria-label="最近请求的上下文窗口">
    <div className="pw-context-window-heading"><div><span>最近请求占用</span><strong>{input === null ? '未测得' : `≈${number(input)}`}{input !== null && <small>tokens</small>}</strong></div>
      {metric.windowPercent !== null && <b data-attention={metric.attention || undefined}>{contextPercentLabel(metric.windowPercent)}</b>}
    </div>
    {hasWindow && <div className="pw-context-window-bar" role="img" aria-label={`输入约 ${number(input)}，模型窗口 ${number(window)} tokens`}>
        <i className="pw-context-window-used" style={{ width: `${Math.min(100, input / window * 100)}%` }}/>
    </div>}
    <div className="pw-context-window-caption">
      <span>{remaining === null ? '输入余量未测得' : remaining < 0 ? `超出输入预算 ≈${number(-remaining)}` : `还可输入 ≈${number(remaining)}`}</span>
      <small>{window === null ? '模型窗口未知' : `窗口 ${number(window)}`}</small>
    </div>
  </section>;
}
