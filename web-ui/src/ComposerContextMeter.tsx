import { useEffect, useState } from 'react';
import { Accordion, AccordionItem, SettingsDialog } from './forms-library';
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
    </button>
    <SettingsDialog isOpen={open} onClose={() => setOpen(false)} title="上下文占用">
      <section className="pw-context-meter-detail" aria-label="原请求上下文检查">
        <div className="pw-context-meter-overview">
          <div><span>上次请求占用</span><strong>{metric.available ? `≈${contextPercentLabel(metric.windowPercent)}` : '未测得'}</strong></div>
          <div><span>当前草稿估算</span><strong>{draft.tokens === null ? '待核对' : <>{number(draft.tokens)} <small>tokens</small></>}</strong></div>
        </div>
        <p>{metric.available ? '占用来自上次请求；草稿单独估算，发送时重新核对。' : metric.reason}</p>
        {metric.attention && <p className="pw-context-meter-attention" role="status">上次检查需要处理，请核对原请求。</p>}
        <Accordion defaultExpandedKeys={[]}><AccordionItem id="context-details" title="用量明细">
          <div className="pw-context-meter-breakdown">
            {metric.available && <dl>
              <div><dt>输入预算占用</dt><dd>≈{contextPercentLabel(metric.inputPercent)}</dd></div>
              <div><dt>估算输入</dt><dd>{number(metric.inputTokens)} tokens</dd></div>
              <div><dt>输入预算</dt><dd>{number(metric.inputLimit)} tokens</dd></div>
              <div><dt>模型窗口</dt><dd>{number(metric.windowTokens)} tokens</dd></div>
              <div><dt>输出预留</dt><dd>{number(metric.outputReserve)} tokens</dd></div>
            </dl>}
            <p>{draft.reason}</p>
            {metric.available && <><p>{metric.reason}</p><p>{metric.summaryUsed ? '上次请求使用了上下文摘要。' : '上次记录未确认使用上下文摘要。'}</p></>}
            {metric.at !== null && <p>检查时间：{new Date(metric.at).toLocaleString('zh-CN')}</p>}
          </div>
        </AccordionItem></Accordion>
      </section>
    </SettingsDialog>
  </>;
}
