import { Accordion, AccordionItem, Button } from './forms-library';
import { previewReferenceMessage } from './project-preview';
import { useComposerReferenceRemoval } from './ComposerSourceReferences';
import './composer-context.css';

type Data = Record<string, any>;

/** Escaped observations stay separate from original quotes and attachments. */
export function ComposerPreviewReferences({ composer, disabled }: { composer: Data; disabled: boolean }) {
  const refs: Data[] = Array.isArray(composer.previewRefs) ? composer.previewRefs : [];
  const removal = useComposerReferenceRemoval(composer, disabled, 'preview');
  if (!refs.length && !removal.error) return null;
  return <section className="pw-composer-context pw-preview-references" aria-label="草稿中的元素引用">
    {refs.length > 0 && <Accordion className="pw-composer-context-disclosure"><AccordionItem id="preview-references" title={`元素引用 · ${refs.length}`} description="来自预览页面的片段，仅作参考。">
      <div className="pw-composer-context-scroll" role="region" aria-label="元素引用清单" tabIndex={0}>
        <p className="pw-composer-context-hint">定位提示不是源码行号。展开查看原片段和采集来源。</p>
        {refs.map((ref, index) => {
          const id = String(ref.referenceId || JSON.stringify([ref.previewId, ref.documentGeneration, ref.path, ref.selectorHint, index]));
          return <div className="pw-composer-context-row" key={id}>
            <Accordion className="pw-composer-context-object"><AccordionItem id={id} headingLevel={4} title={`${index + 1} · ${typeof ref.path === 'string' ? ref.path : '预览元素'}`} description={<span className="pw-composer-context-excerpt">{typeof ref.text === 'string' && ref.text ? ref.text : '没有可显示文字，展开查看采集信息。'}</span>}>
              {typeof ref.text === 'string' && ref.text && <p className="pw-composer-context-quote pw-preview-quoted-text">{ref.text}</p>}
              <dl className="pw-composer-context-metadata">
                <dt>来源</dt><dd>{ref.sourceKind === 'fixed_workspace_project_artifact' ? '原任务固定项目包' : '原活动工作区预览'}</dd>
                <dt>定位提示</dt><dd>{typeof ref.selectorHint === 'string' ? ref.selectorHint : '无可靠定位'}</dd>
                <dt>采集版本</dt><dd>{String(ref.previewId || '')}</dd>
                <dt>文档代次</dt><dd>{String(ref.documentGeneration ?? '')}</dd>
                <dt>工作副本</dt><dd>{String(ref.checkoutId || '')}</dd>
                <dt>源码摘要</dt><dd>{String(ref.sourceDigest || '')}</dd>
              </dl>
              {ref.sourceKind === 'fixed_workspace_project_artifact' && <dl className="pw-composer-context-metadata" aria-label="固定包引用来源">
                <dt>固定版本</dt><dd>{String(ref.artifactId || '')}</dd>
                <dt>归档摘要</dt><dd>{String(ref.artifactSha256 || '')}</dd>
                <dt>原保存操作</dt><dd>{String(ref.packageOperationId || '')}</dd>
              </dl>}
              {typeof ref.htmlSnippet === 'string' && <pre className="pw-composer-context-snippet" aria-label={`元素片段 ${index + 1}`}>{ref.htmlSnippet}</pre>}
              {Object.values(ref.truncated || {}).some(value => value === true) && <p className="pw-composer-context-hint">这处片段已按长度限制截取。</p>}
            </AccordionItem></Accordion>
            <Button variant="ghost" size="small" className="pw-composer-context-remove" disabled={disabled || removal.busy || removal.unknown} aria-label={`移除元素引用 ${index + 1}`} onClick={() => removal.remove(index)}>移除</Button>
          </div>;
        })}
      </div>
    </AccordionItem></Accordion>}
    {refs.length > 0 && composer.previewRefsAvailable !== true && <p className="pw-composer-context-feedback pw-error" role="alert">{previewReferenceMessage(composer.previewRefsAvailable)}</p>}
    {removal.busy && <p className="pw-composer-context-feedback" role="status">{removal.phase === 'refreshing' ? '已收到移除回执，正在核对草稿…' : '正在移除这条元素引用…'}</p>}
    {removal.error && <p className="pw-composer-context-feedback pw-error" role="alert">{removal.error}</p>}
    {removal.unknown && <p className="pw-composer-context-hint">结果待核对，暂不再次移除。</p>}
  </section>;
}
