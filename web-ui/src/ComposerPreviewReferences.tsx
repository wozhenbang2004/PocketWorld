import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { previewReferenceMessage } from './project-preview';

type Data = Record<string, any>;

/** Independent, escaped observation display; it does not replace original quotes or attachments. */
export function ComposerPreviewReferences({ composer, disabled }: { composer: Data; disabled: boolean }) {
  const refs: Data[] = Array.isArray(composer.previewRefs) ? composer.previewRefs : [];
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [unknown, setUnknown] = useState(false);
  const entered = useRef(false), alive = useRef(true), generation = useRef(0);
  useEffect(() => {
    alive.current = true; let epoch = bridge.getDocumentEpoch();
    const invalidate = () => {
      if (epoch === bridge.getDocumentEpoch()) return;
      epoch = bridge.getDocumentEpoch(); generation.current++;
      if (entered.current) { setUnknown(true); setError('移除结果尚未核对，原引用仍保留；没有重发。'); }
      entered.current = false; setBusy(false);
    };
    const unsubscribe = bridge.subscribe(invalidate);
    return () => { alive.current = false; generation.current++; unsubscribe(); };
  }, []);
  if (!refs.length) return null;
  const remove = (index: number) => {
    if (entered.current || disabled || unknown) return;
    const original = { key: composer.key, epoch: composer.epoch, revision: composer.revision, index };
    const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch();
    entered.current = true; setBusy(true); setError('');
    void bridge.command('composer.preview.remove', original).then(value => {
      if (!alive.current || generation.current !== request || bridge.getDocumentEpoch() !== documentEpoch) return;
      if (value?.removed !== true) throw new Error('原元素引用尚未确认移除，请核对这份草稿。');
    }).catch(failure => {
      if (!alive.current || generation.current !== request) return;
      setUnknown(failure?.code === 'outcome_unknown'); setError(failure instanceof Error ? failure.message : String(failure));
    }).finally(() => { if (alive.current && generation.current === request) { entered.current = false; setBusy(false); } });
  };
  return <section className="pw-preview-references" aria-label="草稿中的元素引用">
    <Accordion><AccordionItem id="preview-references" title={`元素引用 · ${refs.length}`} description={previewReferenceMessage(composer.previewRefsAvailable)}>
      <p className="pw-muted">这些片段来自预览页面，仅作参考。定位提示不是源码行号。</p>
      <div className="pw-preview-reference-list">{refs.map((ref, index) => <section className="pw-preview-reference" key={String(ref.referenceId || index)}>
        <header><strong>{typeof ref.path === 'string' ? ref.path : '预览元素'} · {index + 1}</strong><Button variant="ghost" size="small" disabled={disabled || busy || unknown} aria-label={`移除元素引用 ${index + 1}`} onClick={() => remove(index)}>移除</Button></header>
        {typeof ref.text === 'string' && <p className="pw-preview-quoted-text">{ref.text}</p>}
        <Accordion><AccordionItem id={`preview-source-${index}`} title="查看片段与来源" headingLevel={4}>
          <dl className="pw-preview-source-details"><dt>定位提示</dt><dd>{typeof ref.selectorHint === 'string' ? ref.selectorHint : '无可靠定位'}</dd><dt>采集版本</dt><dd>{String(ref.previewId || '')}</dd><dt>文档代次</dt><dd>{String(ref.documentGeneration ?? '')}</dd><dt>工作副本</dt><dd>{String(ref.checkoutId || '')}</dd><dt>源码摘要</dt><dd>{String(ref.sourceDigest || '')}</dd></dl>
          {ref.sourceKind === 'fixed_workspace_project_artifact' && <dl className="pw-preview-source-details" aria-label="固定包引用来源"><dt>来源</dt><dd>原任务固定项目包</dd><dt>固定版本</dt><dd>{String(ref.artifactId || '')}</dd><dt>归档摘要</dt><dd>{String(ref.artifactSha256 || '')}</dd><dt>原保存操作</dt><dd>{String(ref.packageOperationId || '')}</dd></dl>}
          {typeof ref.htmlSnippet === 'string' && <pre aria-label={`元素片段 ${index + 1}`}>{ref.htmlSnippet}</pre>}
          {Object.values(ref.truncated || {}).some(value => value === true) && <p className="pw-muted">这处片段已按长度限制截取。</p>}
        </AccordionItem></Accordion>
      </section>)}</div>
      {error && <p className="pw-error" role="alert">{error}</p>}
    </AccordionItem></Accordion>
  </section>;
}
