import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import './composer-context.css';

type Data = Record<string, any>;

/** Both reference collections use the same native draft CAS, without replaying a removal. */
export function useComposerReferenceRemoval(composer: Data, disabled: boolean, kind: 'source' | 'preview') {
  const identity = JSON.stringify([composer.key, composer.epoch, composer.revision]);
  const renderedEpoch = bridge.getDocumentEpoch();
  const currentDisabled = useRef(disabled);
  currentDisabled.current = disabled;
  const current = useRef(identity), entered = useRef(false), alive = useRef(true), generation = useRef(0);
  const [phase, setPhase] = useState<'idle' | 'removing' | 'refreshing'>('idle');
  const [error, setError] = useState(''), [unknown, setUnknown] = useState(false);
  current.current = identity;
  useEffect(() => {
    generation.current++; entered.current = false; setPhase('idle'); setError(''); setUnknown(false);
  }, [identity]);
  useEffect(() => {
    alive.current = true; let epoch = bridge.getDocumentEpoch();
    const unsubscribe = bridge.subscribe(() => {
      if (epoch === bridge.getDocumentEpoch()) return;
      epoch = bridge.getDocumentEpoch(); generation.current++;
      if (entered.current) {
        setUnknown(true); setError('移除结果尚未核对，请核对原草稿；没有重复移除。');
      }
      entered.current = false; setPhase('idle');
    });
    return () => { alive.current = false; generation.current++; unsubscribe(); };
  }, []);
  const remove = (index: number) => {
    if (!alive.current || current.current !== identity || bridge.getDocumentEpoch() !== renderedEpoch || entered.current || currentDisabled.current || unknown) return;
    const original = { key: composer.key, epoch: composer.epoch, revision: composer.revision, index };
    const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch();
    const accepts = () => alive.current && generation.current === request && current.current === identity && bridge.getDocumentEpoch() === documentEpoch;
    entered.current = true; setPhase('removing'); setError('');
    void bridge.command(`composer.${kind}.remove`, original).then(value => {
      if (!accepts()) return;
      if (value?.removed !== true) {
        setUnknown(true); setError('尚未收到确定的移除回执，请核对原草稿；没有重复移除。'); setPhase('idle');
        return;
      }
      // Only the next native draft snapshot changes the visible collection.
      setPhase('refreshing');
    }).catch(failure => {
      if (!accepts()) return;
      const uncertain = failure?.code === 'outcome_unknown';
      setUnknown(uncertain); setError(failure instanceof Error ? failure.message : String(failure));
      entered.current = uncertain; setPhase('idle');
    });
  };
  return { remove, error, unknown, busy: phase !== 'idle', phase };
}

/** BoardUI disclosure with OpenMuse LinkRow's compact title/detail hierarchy.
 * Original ranges contain metadata only. Opening them does not fetch or authorize text. */
export function ComposerSourceReferences({ composer, disabled }: { composer: Data; disabled: boolean }) {
  const refs: Data[] = Array.isArray(composer.sourceRefs) ? composer.sourceRefs : [];
  const removal = useComposerReferenceRemoval(composer, disabled, 'source');
  if (!refs.length && !removal.error) return null;
  return <section className="pw-composer-context" aria-label="草稿中的原文引用">
    {refs.length > 0 && <Accordion className="pw-composer-context-disclosure"><AccordionItem id="source-references" title={`原文引用 · ${refs.length}`} description="已加入这份草稿，发送时核对原消息。">
      <div className="pw-composer-context-scroll" role="region" aria-label="原文引用清单" tabIndex={0}>
        <p className="pw-composer-context-hint">当前保留的是原文定位，未读取正文。</p>
        {refs.map((ref, index) => {
          const source = ref.sourceRef || {};
          const id = JSON.stringify([ref.generation, ref.sourceConversationId, source.kind, source.sourceId, source.revision, ref.offset, ref.chars]);
          return <div className="pw-composer-context-row" key={id}>
            <Accordion className="pw-composer-context-object"><AccordionItem id={id} headingLevel={4} title={`引用 ${index + 1} · ${ref.chars} 字符`} description={`原消息修订 ${source.revision}`}>
              <dl className="pw-composer-context-metadata">
                <dt>原消息</dt><dd>{String(source.sourceId || '未提供')}</dd>
                <dt>原会话</dt><dd>{String(ref.sourceConversationId || '未提供')}</dd>
                <dt>原文范围</dt><dd>{ref.offset}–{Number(ref.offset) + Number(ref.chars)} · {String(ref.unit || 'UTF-16')}</dd>
                <dt>来源代次</dt><dd>{String(ref.generation || '未提供')}</dd>
                <dt>内容校验值</dt><dd>{String(ref.bodyFingerprint || '未提供')}</dd>
              </dl>
            </AccordionItem></Accordion>
            <Button variant="ghost" size="small" className="pw-composer-context-remove" disabled={disabled || removal.busy || removal.unknown} onClick={() => removal.remove(index)} aria-label={`移除原文引用 ${index + 1}`}>移除</Button>
          </div>;
        })}
      </div>
    </AccordionItem></Accordion>}
    {removal.busy && <p className="pw-composer-context-feedback" role="status">{removal.phase === 'refreshing' ? '已收到移除回执，正在核对草稿…' : '正在移除这条原文引用…'}</p>}
    {removal.error && <p className="pw-composer-context-feedback pw-error" role="alert">{removal.error}</p>}
    {removal.unknown && <p className="pw-composer-context-hint">结果待核对，暂不再次移除。</p>}
  </section>;
}
