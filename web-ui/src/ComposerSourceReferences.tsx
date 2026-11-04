import { useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';

type Data = Record<string, any>;

export function ComposerSourceReferences({ composer, disabled }: { composer: Data; disabled: boolean }) {
  const refs: Data[] = Array.isArray(composer.sourceRefs) ? composer.sourceRefs : [];
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const entered = useRef(false);
  if (!refs.length) return null;
  const remove = (index: number) => {
    if (entered.current || disabled) return;
    entered.current = true; setBusy(true); setError('');
    void bridge.command('composer.source.remove', { key: composer.key, epoch: composer.epoch, revision: composer.revision, index }).then(value => {
      if (value?.removed !== true) throw new Error('原引用尚未确认移除，请核对草稿。');
    }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))).finally(() => { entered.current = false; setBusy(false); });
  };
  return <section className="pw-source-record" aria-label="草稿中的原文引用"><h3>原文引用</h3><p className="pw-muted">已加入草稿，发送时会再次核对原消息。</p>
    {refs.map((ref, index) => <div className="pw-form-actions" key={`${index}:${ref.sourceRef?.sourceId}:${ref.offset}:${ref.chars}`}><details><summary>引用 {index + 1} · {ref.chars} 字符</summary><p className="pw-muted">原消息修订 {ref.sourceRef?.revision} · 原文范围 {ref.offset}–{Number(ref.offset) + Number(ref.chars)}</p></details><Button variant="ghost" size="small" disabled={disabled || busy} onClick={() => remove(index)} aria-label={`移除原文引用 ${index + 1}`}>移除</Button></div>)}
    {error && <p className="pw-error" role="alert">{error}</p>}
  </section>;
}
