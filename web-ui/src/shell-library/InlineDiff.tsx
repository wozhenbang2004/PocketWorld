/* PW-24: controlled file adaptation of library Affect InlineDiffDemo v3.
 * Original pro-more.tsx is retained. The adopted del/ins surface now receives
 * actual jsdiff fragments instead of treating the complete file as a suggestion.
 */
import { useMemo } from 'react';
import { IconCheck, IconClose, IconCode } from '../icons';
import { compareFileText, type FileDiffPart } from '../file-diff';

export function InlineDiff({ before, after, versionLabel, onApply, onCancel, pending = false }: { before: string; after: string; versionLabel: string; onApply?: () => void; onCancel?: () => void; pending?: boolean }) {
  const same = before === after;
  const comparison = useMemo(() => compareFileText(before, after), [before, after]);
  const content = (parts: FileDiffPart[], side: 'before' | 'after') => parts.map((part, index) =>
    !part.changed ? part.text : side === 'before' ? <del key={index}>{part.text}</del> : <ins key={index}>{part.text}</ins>);
  return <section className="acc-inline acc-card pw-inline-diff" aria-label="当前文件差异" data-diff-mode={comparison.mode}>
    <header className="acc-head"><span><IconCode size={17} /> 当前修改 <span className="acc-muted">· {versionLabel}</span></span></header>
    {same ? <p className="pw-empty-small">当前内容与原版本一致。</p> : <>
      {comparison.mode !== 'character' && <p className="pw-diff-note" role="status">{comparison.mode === 'line' ? '当前按整行标记变化，行内细节未标记。' : '修改范围较大，未逐处标记。两侧内容完整保留。'}</p>}
      {comparison.whitespaceChanged && <p className="pw-diff-note">标记中包含空白或换行变化。</p>}
      <div className="pw-diff-columns">
        <section><h4>原版本</h4><pre aria-label="原版本内容">{before ? content(comparison.before, 'before') : <span className="acc-muted">（空文件）</span>}</pre></section>
        <section><h4>当前草稿</h4><pre aria-label="当前草稿内容">{after ? content(comparison.after, 'after') : <span className="acc-muted">（空文件）</span>}</pre></section>
      </div>
    </>}
    <footer><span className="acc-muted">保存仍以原文件版本核对</span><span className="acc-spacer" />{onCancel && <button type="button" className="acc-btn" onClick={onCancel} disabled={pending}><IconClose size={15} />返回编辑</button>}{onApply && <button type="button" className="acc-btn acc-btn-primary" onClick={onApply} disabled={pending || same}><IconCheck size={15} />{pending ? '正在保存…' : '保存这份修改'}</button>}</footer>
  </section>;
}
