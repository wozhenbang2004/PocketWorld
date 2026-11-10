import { useEffect, useState, type ReactNode } from 'react';
import { Accordion, AccordionItem, SettingsDialog } from './forms-library';
import { usePocketSnapshot } from './usePocketSnapshot';
import { composerContextMetric, composerDraftPreview, contextPercentLabel } from './composer-context-metric';
import { ContextBreakdown, ContextWindowSummary } from './ContextBreakdown';
import './composer-native-detail.css';

export function ComposerContextMeter({ composerKey, draftText = '', attachmentCount = 0, contextStatus }: { composerKey?: string; draftText?: string; attachmentCount?: number; contextStatus?: ReactNode }) {
  const snapshot = usePocketSnapshot();
  const metric = composerContextMetric(snapshot, composerKey);
  const draft = composerDraftPreview(draftText, attachmentCount);
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [composerKey, snapshot.state.conversationId]);
  const ratio = metric.windowPercent === null ? 0 : Math.min(100, Math.max(0, metric.windowPercent));
  const number = (value: number | null) => value === null ? '未测得' : value.toLocaleString('zh-CN');
  const label = `${metric.attention ? '上次检查需要核对。' : ''}${metric.available ?
    `上次请求约 ${number(metric.inputTokens)} tokens，${metric.windowPercent === null ? '模型窗口未知' : `占窗 ${contextPercentLabel(metric.windowPercent)}`}；当前草稿${draft.tokens===null?'含未计量附件':`约 ${number(draft.tokens)} tokens`}` : `上下文未测得。${metric.reason}`}`;
  return <>
    <button type="button" className="pw-context-meter-button" data-available={metric.windowPercent !== null} data-attention={metric.attention || undefined} onClick={() => setOpen(true)} aria-label={`查看上下文：${label}`} aria-haspopup="dialog" aria-expanded={open} title={label}>
      <svg className="pw-context-meter-ring" width="16" height="16" viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="10" cy="10" r="7.5" className="pw-context-meter-track" />
        {metric.windowPercent !== null && ratio > 0 && <circle cx="10" cy="10" r="7.5" className="pw-context-meter-value" pathLength="100" strokeDasharray={`${ratio} 100`} transform="rotate(-90 10 10)" />}
      </svg>
    </button>
    <SettingsDialog isOpen={open} onClose={() => setOpen(false)} title="上下文" closeLabel="关闭上下文详情" className="pw-context-dialog">
      <section className="pw-context-meter-detail" aria-label="原请求上下文检查">
        {contextStatus && <div className="pw-context-meter-source" aria-label="关联上下文">{contextStatus}</div>}
        <ContextWindowSummary metric={metric}/>
        {!metric.available && <p className="pw-context-composition-note">{metric.reason}</p>}
        {metric.attention && <p className="pw-context-meter-attention" role="status">上次检查需要处理，请核对原请求。</p>}
        <ContextBreakdown value={metric.breakdown} identity={`${metric.taskId}:${metric.at}`}/>
        <div className="pw-context-draft">
          <div><span>当前草稿</span><strong>{draft.tokens === null ? '待计量' : `≈${number(draft.tokens)}`}</strong><small>{draft.tokens !== null ? 'tokens' : attachmentCount > 0 ? '含附件' : ''}</small></div>
          <p>尚未发送，未计入上次占用。{draft.reason}</p>
        </div>
        {metric.summaryUsed && <div className="pw-context-summary-state"><span>上次请求已使用摘要</span>
          {metric.beforeInputTokens !== null && metric.inputTokens !== null && metric.beforeInputTokens !== metric.inputTokens &&
            <strong>≈{number(metric.beforeInputTokens)} → ≈{number(metric.inputTokens)}</strong>}
        </div>}
        <Accordion defaultExpandedKeys={[]}><AccordionItem id="context-details" title="预算与计量口径">
          <div className="pw-context-meter-breakdown">
            {metric.available && <dl>
              {metric.inputPercent !== null && <div><dt>输入预算占用</dt><dd>≈{contextPercentLabel(metric.inputPercent)}</dd></div>}
              <div><dt>估算输入</dt><dd>{number(metric.inputTokens)} tokens</dd></div>
              <div><dt>输入预算</dt><dd>{number(metric.inputLimit)} tokens</dd></div>
              <div><dt>模型窗口</dt><dd>{number(metric.windowTokens)} tokens</dd></div>
              <div><dt>输出预留</dt><dd>{number(metric.outputReserve)} tokens</dd></div>
            </dl>}
            <p>{metric.reason}</p>
            <p>这里显示最近一次请求的上下文；新增回复、工具结果和当前草稿将在下次发送前重新核对。累计 Token 消耗与这个窗口分别统计。</p>
            {metric.at !== null && <p>检查时间：{new Date(metric.at).toLocaleString('zh-CN')}</p>}
          </div>
        </AccordionItem></Accordion>
      </section>
    </SettingsDialog>
  </>;
}
