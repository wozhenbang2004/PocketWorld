import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { IconQuote } from './icons';
import { ComposerQuoteReview, type ComposerQuoteSelection, type StoredQuoteReview } from './ComposerQuoteReview';
import { draftQuoteKey, draftQuotePin, draftQuoteView, loadedQuoteReceipt, storedDraftQuote, storedDraftQuoteCurrent, type DraftQuotePin, type StoredDraftQuoteRead } from './composer-quote-review';
import './composer-context.css';

type Data = Record<string, any>;

/** Reference collections use the same native draft CAS, without replaying a removal. */
export function useComposerReferenceRemoval(composer: Data, disabled: boolean, kind: 'source' | 'preview' | 'object') {
  // A source edit publishes before its disk receipt. Only a different draft/window abandons it.
  const identity = JSON.stringify([composer.key, composer.epoch, kind === 'source' ? null : composer.revision]);
  const latestRevision = useRef(composer.revision);
  latestRevision.current = composer.revision;
  const renderedEpoch = bridge.getDocumentEpoch();
  const currentDisabled = useRef(disabled);
  currentDisabled.current = disabled;
  const current = useRef(identity), entered = useRef(false), alive = useRef(true), generation = useRef(0);
  const [phase, setPhase] = useState<'idle' | 'removing' | 'refreshing'>('idle');
  const [error, setError] = useState(''), [unknown, setUnknown] = useState(false);
  const [saved, setSaved] = useState<{ identity: string; revision: number; sourceKey?:string } | null>(null);
  const [removedRevision, setRemovedRevision] = useState<number|null>(null);
  current.current = identity;
  useEffect(() => {
    generation.current++; entered.current = false; setPhase('idle'); setError(''); setUnknown(false); setSaved(null); setRemovedRevision(null);
  }, [identity]);
  useEffect(() => {
    if (saved?.identity === identity && composer.ready===true && Array.isArray(composer.sourceRefs) && composer.revision >= saved.revision) {
      const matches = saved.sourceKey && Array.isArray(composer.sourceRefs) && composer.sourceRefs.some((raw:unknown) => {
        const pin = draftQuotePin(raw); return pin && draftQuoteKey(pin)===saved.sourceKey;
      });
      entered.current = !!matches; setPhase('idle'); setSaved(null);
      if (matches) { setUnknown(true); setError('原草稿仍显示这条引用，暂不再次移除。'); }
      else setRemovedRevision(composer.revision);
    }
  }, [identity, composer.ready, composer.revision, composer.sourceRefs, saved]);
  useEffect(() => {
    alive.current = true; let epoch = bridge.getDocumentEpoch();
    const unsubscribe = bridge.subscribe(() => {
      if (epoch === bridge.getDocumentEpoch()) return;
      epoch = bridge.getDocumentEpoch(); generation.current++; setSaved(null);
      if (entered.current) {
        setUnknown(true); setError('移除结果尚未核对，请核对原草稿；没有重复移除。');
      }
      entered.current = false; setPhase('idle');
    });
    return () => { alive.current = false; generation.current++; unsubscribe(); };
  }, []);
  const remove = (index: number) => {
    if (!alive.current || current.current !== identity || latestRevision.current !== composer.revision || bridge.getDocumentEpoch() !== renderedEpoch || entered.current || currentDisabled.current || unknown) return;
    const original = { key: composer.key, epoch: composer.epoch, revision: composer.revision, index };
    const source = kind==='source' ? draftQuotePin(composer.sourceRefs?.[index]) : null;
    const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch();
    const accepts = () => alive.current && generation.current === request && current.current === identity && bridge.getDocumentEpoch() === documentEpoch;
    entered.current = true; setPhase('removing'); setError(''); setRemovedRevision(null);
    void bridge.command(`composer.${kind}.remove`, original).then(value => {
      if (!accepts()) return;
      if (kind === 'source') {
        const originalEdit = value?.key === original.key && value?.epoch === original.epoch &&
          value?.fromRevision === original.revision && Number.isSafeInteger(value?.revision) && value.revision === original.revision + 1;
        if (!originalEdit || value?.removed !== true || value?.persisted !== true) {
          setUnknown(true); setPhase('idle');
          setError(originalEdit && value?.status === 'storage_unconfirmed'
            ? '本页已移除，但未确认保存。重开原草稿时引用可能仍在，请先核对。'
            : '没有收到原草稿的保存回执，暂不能确认这条引用已移除。');
          return;
        }
        setSaved({ identity, revision: value.revision, sourceKey: source ? draftQuoteKey(source) : undefined });
      } else if (value?.removed !== true) {
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
  return { remove, error, unknown, busy: phase !== 'idle', phase, removed: removedRevision!==null&&removedRevision===composer.revision };
}

/** Reuse the existing draft disclosure for explicitly selected work objects. */
export function ComposerObjectReferences({ composer, disabled }: { composer: Data; disabled: boolean }) {
  const refs: Data[] = Array.isArray(composer.objectRefs) ? composer.objectRefs : [];
  const removal = useComposerReferenceRemoval(composer, disabled, 'object');
  if (!refs.length && !removal.error) return null;
  const kinds: Record<string, string> = { work: '工作', project: '项目', artifact: '文件', team: '团队' };
  return <section className="pw-composer-context" aria-label="这条消息关联的对象">
    {refs.length > 0 && <Accordion className="pw-composer-context-disclosure"><AccordionItem id="object-references" title={`关于 · ${refs.map(ref => ref.label).join('、')}`} description={composer.saving===true ? "已在当前草稿关联，本机保存待确认。" : "这是这份草稿的关联；发送时会核对所选对象。"}>
      <div className="pw-composer-context-scroll" role="region" aria-label="关联对象清单" tabIndex={0}>
        {refs.map((ref, index) => <div className="pw-composer-context-row" key={`${ref.kind}:${ref.id}`}>
          <div className="pw-composer-context-object"><strong>{String(ref.label)}</strong><p className="pw-composer-context-hint">{kinds[ref.kind] || '对象'}{ref.kind==='artifact'?' · 固定版本'+(typeof ref.sha256==='string'&&/^[a-f0-9]{64}$/.test(ref.sha256)? ` ${ref.sha256.slice(0,12)}`:''):ref.kind==='work'?' · 当前执行回合':''}</p></div>
          <Button variant="ghost" size="small" className="pw-composer-context-remove" disabled={disabled || removal.busy || removal.unknown} onClick={() => removal.remove(index)} aria-label={`移除关联：${ref.label}`}>移除</Button>
        </div>)}
        <p className="pw-composer-context-hint">关联对象保留在这份草稿中，发送前可以移除。</p>
      </div>
    </AccordionItem></Accordion>}
    {removal.busy && <p className="pw-composer-context-feedback" role="status">{removal.phase === 'refreshing' ? '正在核对草稿…' : '正在移除关联…'}</p>}
    {removal.error && <p className="pw-composer-context-feedback pw-error" role="alert">{removal.error}</p>}
  </section>;
}

/** BoardUI disclosure with OpenMuse's compact rows. The local review joins only
 * the selected reference with an original already published in this private chat. */
export function ComposerSourceReferences({ composer, disabled }: { composer: Data; disabled: boolean }) {
  const refs: Data[] = Array.isArray(composer.sourceRefs) ? composer.sourceRefs : [];
  const removal = useComposerReferenceRemoval(composer, disabled, 'source');
  const [selection,setSelection] = useState<ComposerQuoteSelection|null>(null);
  const [stored,setStored] = useState<StoredQuoteReview|null>(null);
  const storedOwner=useRef<ComposerQuoteSelection|null>(null);
  useEffect(()=>()=>{storedOwner.current=null},[]);
  const retireStored=useCallback((scope:ComposerQuoteSelection)=>{
    if(storedOwner.current===scope)storedOwner.current=null;
    setStored(value=>value?.selection===scope?null:value);
  },[]);
  const close=useCallback(()=>{storedOwner.current=null;setSelection(null);setStored(null)},[]);
  const open = (pin:DraftQuotePin,index:number,trigger:HTMLButtonElement)=>{
    if(document.hidden||!trigger.isConnected||storedOwner.current)return;
    const native=bridge.getState(),owner={key:String(composer.key||''),epoch:Number(composer.epoch),conversation:String(composer.conversationId||'')};
    const receipt=loadedQuoteReceipt(native,owner.conversation),sourceRevision=native.state.traceSourceRevision,documentEpoch=bridge.getDocumentEpoch();
    const form=trigger.closest('.pw-composer-region'),editors=form?.querySelectorAll<HTMLTextAreaElement>('.pw-composer-row textarea:not(:disabled)');
    const input=editors?.length===1?editors[0]:null,view=draftQuoteView(native,owner,pin,receipt);
    const selection:ComposerQuoteSelection={owner,pin,receipt,documentEpoch,sourceRevision,
      confirmedAtOpen:Number.isSafeInteger(sourceRevision)&&view.kind==='selected',trigger,
      caret:input?{input,length:input.value.length,revision:Number(composer.revision),start:input.selectionStart,end:input.selectionEnd,direction:input.selectionDirection,top:input.scrollTop}:undefined};
    setSelection(selection);
    const resumeRevision=(native.view as {resumeRevision?:number}|undefined)?.resumeRevision,memoryRevision=native.state.memoryRefreshRevision;
    if(view.kind!=='not_loaded'||!native.capabilities.commands.includes('composer.source.read')||
      typeof resumeRevision!=='number'||!Number.isSafeInteger(resumeRevision)||!Number.isSafeInteger(sourceRevision)||
      !Number.isSafeInteger(owner.epoch)||!Number.isSafeInteger(memoryRevision)||!Number.isSafeInteger(composer.revision)||composer.revision<1)return;
    const request:StoredDraftQuoteRead={owner,pin,index,revision:composer.revision,resumeRevision,sourceRevision,memoryRevision};
    if(!storedDraftQuoteCurrent(native,request))return;
    storedOwner.current=selection;
    setStored({selection,request,state:'reading'});
    const current=()=>storedOwner.current===selection&&!document.hidden&&bridge.getDocumentEpoch()===documentEpoch&&
      storedDraftQuoteCurrent(bridge.getState(),request);
    void bridge.command<unknown>('composer.source.read',{key:owner.key,epoch:owner.epoch,revision:request.revision,index}).then(value=>{
      if(!current())return;
      const quote=storedDraftQuote(value,request);
      setStored(quote?{selection,request,state:'verified',quote}:{selection,request,state:'unavailable'});
    },()=>{if(current())setStored({selection,request,state:'unavailable'})});
  };
  if (!refs.length && !removal.error && !removal.busy && !removal.removed && !selection) return null;
  return <section className="pw-composer-context" aria-label="草稿中的原文引用">
    {refs.length > 0 && <Accordion className="pw-composer-context-disclosure"><AccordionItem id="source-references" title={`原文引用 · ${refs.length}`} description={composer.saving===true ? "引用已在当前草稿，本机保存待确认。" : "已关联到这份草稿，发送时会核对原消息。"}>
      <div className="pw-composer-context-scroll" role="region" aria-label="原文引用清单" tabIndex={0}>
        <p className="pw-composer-context-hint">只查看实际关联的选段。取消引用只影响这份草稿，问题正文和原消息都保留。</p>
        {refs.map((ref, index) => {
          const pin = draftQuotePin(ref), id = pin ? draftQuoteKey(pin) : `unverified:${index}`;
          return <div className="pw-composer-context-row" key={id}>
            {pin?<Button variant="ghost" size="small" className="pw-composer-context-object pw-composer-context-read" leadingIcon={IconQuote}
              disabled={removal.busy} onClick={event=>open(pin,index,event.currentTarget)}>查看引用 {index+1} · {pin.chars} 位</Button>:
              <p className="pw-composer-context-object pw-composer-context-hint">引用 {index+1} · 原范围待核对</p>}
            <Button variant="ghost" size="small" className="pw-composer-context-remove" disabled={disabled || removal.busy || removal.unknown} onClick={() => removal.remove(index)} aria-label={`取消原文引用 ${index + 1}`}>取消引用</Button>
          </div>;
        })}
      </div>
    </AccordionItem></Accordion>}
    {removal.busy && <p className="pw-composer-context-feedback" role="status">{removal.phase === 'refreshing' ? '原草稿已保存，正在核对引用清单…' : '正在从本机草稿移除引用…'}</p>}
    {removal.error && <p className="pw-composer-context-feedback pw-error" role="alert">{removal.error}</p>}
    {removal.unknown && <p className="pw-composer-context-hint">结果待核对，暂不再次移除。</p>}
    {removal.removed && <p className="pw-composer-context-feedback" role="status">已取消这条引用。输入和原消息都保留。</p>}
    <ComposerQuoteReview selection={selection} stored={stored} retireStored={retireStored} onClose={close}/>
  </section>;
}
