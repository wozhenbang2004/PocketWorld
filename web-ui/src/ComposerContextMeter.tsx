import { useEffect, useState } from 'react';
import { SettingsDialog } from './forms-library';
import { usePocketSnapshot } from './usePocketSnapshot';
import { composerContextMetric, composerDraftPreview, contextPercentLabel } from './composer-context-metric';
import './composer-native-detail.css';

export function ComposerContextMeter({ composerKey, draftText = '', attachmentCount = 0 }: { composerKey?: string; draftText?: string; attachmentCount?: number }) {
  const snapshot = usePocketSnapshot();
  const metric = composerContextMetric(snapshot, composerKey);
  const draft = composerDraftPreview(draftText, attachmentCount);
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [composerKey, snapshot.state.conversationId]);
  const ratio = metric.windowPercent === null ? 0 : Math.min(100, Math.max(0, metric.windowPercent));
  const label = metric.available ? `上次请求估算占窗 ${contextPercentLabel(metric.windowPercent)}；当前草稿${draft.tokens===null?'含未计量附件':`约 ${draft.tokens} tokens`}` : `上下文未测得。${metric.reason}`;
  const number = (value: number | null) => value === null ? '未测得' : value.toLocaleString('zh-CN');
  return <>
    <button type="button" className="pw-context-meter-button" onClick={() => setOpen(true)} aria-label={`查看上下文：${label}`} title={label}>
      <svg className="pw-context-meter-ring" width="16" height="16" viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="10" cy="10" r="7.5" className="pw-context-meter-track" />
        {metric.available && ratio > 0 && <circle cx="10" cy="10" r="7.5" className="pw-context-meter-value" pathLength="100" strokeDasharray={`${ratio} 100`} transform="rotate(-90 10 10)" />}
      </svg>
      <span className="pw-context-meter-caption">{metric.available ? '上次' : ''}</span>
      <span>{metric.available ? `≈${contextPercentLabel(metric.windowPercent)}` : '未测得'}</span>
      <span className="pw-context-meter-draft" title={draft.reason}>{draft.tokens === null ? '草稿待核对' : `草稿≈${draft.tokens.toLocaleString('zh-CN')}`}</span>
    </button>
    <SettingsDialog isOpen={open} onClose={() => setOpen(false)} title="上下文占用">
      <section className="pw-context-meter-detail" aria-label="原请求上下文检查">
        <p>{metric.reason}</p>
        <p role="status">当前草稿：{draft.tokens===null?'未能估算':`约 ${number(draft.tokens)} tokens`}。{draft.reason}</p>
        {metric.available && <>
          <dl>
            <div><dt>输入占模型窗口</dt><dd>≈{contextPercentLabel(metric.windowPercent)}</dd></div>
            <div><dt>输入预算占用</dt><dd>≈{contextPercentLabel(metric.inputPercent)}</dd></div>
            <div><dt>估算输入</dt><dd>{number(metric.inputTokens)} tokens</dd></div>
            <div><dt>输入预算</dt><dd>{number(metric.inputLimit)} tokens</dd></div>
            <div><dt>模型窗口</dt><dd>{number(metric.windowTokens)} tokens</dd></div>
            <div><dt>输出预留</dt><dd>{number(metric.outputReserve)} tokens</dd></div>
          </dl>
          <p>{metric.summaryUsed ? '该次请求使用过上下文摘要。' : '该次检查未证明使用过上下文摘要。'}</p>
          {metric.attention && <p role="status">原检查需要处理，不会在这里继续执行、扩展预算或自动重试。</p>}
          <p>圆环展示的是最近一次已核对请求。草稿数字随输入变化，但不叠加为虚假的「当前总占比」；模型切换、历史回复及附件必须等下一次原生请求检查。</p>
        </>}
        {metric.at !== null && <p>检查时间：{new Date(metric.at).toLocaleString('zh-CN')}</p>}
        {!metric.available && <p>没有可靠检查记录时不显示 0%，也不使用累计 token 用量代替上下文占用。</p>}
      </section>
    </SettingsDialog>
  </>;
}
