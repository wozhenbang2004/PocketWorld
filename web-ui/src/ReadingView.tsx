import { memo, useEffect, useLayoutEffect, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { IconArrowLeft, IconCopy } from './icons';
import { MarkdownBody } from './library/MarkdownBody';
import { ReadingContext, type ReadingDocument } from './reading-context';
import './reading.css';

const ReadingPage = memo(function ReadingPage({ document: snapshot, active }: {
  document: ReadingDocument; active: boolean;
}) {
  const [mode, setMode] = useState<'read' | 'select'>('read');
  const [selected, setSelected] = useState('');
  const [feedback, setFeedback] = useState('');
  const [copying, setCopying] = useState(false);
  const body = useRef<HTMLDivElement>(null), original = useRef<HTMLTextAreaElement>(null);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (!active || mode !== 'read') return;
    const changed = () => {
      const selection = window.getSelection();
      setSelected(selection && !selection.isCollapsed && body.current?.contains(selection.anchorNode)
        && body.current.contains(selection.focusNode) ? selection.toString() : '');
    };
    document.addEventListener('selectionchange', changed);
    return () => document.removeEventListener('selectionchange', changed);
  }, [active, mode]);
  const selectText = () => {
    const field = original.current;
    if (field) setSelected(field.value.slice(field.selectionStart, field.selectionEnd));
  };
  const copy = async (text: string) => {
    if (copying) throw new Error('正在复制，请稍后再试。');
    setCopying(true); setFeedback('');
    try {
      if (!navigator.clipboard?.writeText) throw new Error('复制暂不可用，请选中文字后使用系统复制。');
      await navigator.clipboard.writeText(text);
      if (alive.current) setFeedback('已复制');
    } catch (error) {
      if (alive.current) setFeedback(error instanceof Error ? error.message : '复制未完成，请重试。');
      throw error;
    } finally { if (alive.current) setCopying(false); }
  };
  const changeMode = (next: 'read' | 'select') => {
    if (mode === next) return;
    setMode(next); setSelected(''); setFeedback('');
    if (next === 'select') requestAnimationFrame(() => { original.current?.focus({ preventScroll: true }); selectText(); });
  };
  const content = <MarkdownBody text={snapshot.tableDocument?.markdown ?? snapshot.text} onlyTableAt={snapshot.tableDocument?.offset} onCopyCode={copy} />;
  return <section className="pw-reading-page" data-kind={snapshot.kind} hidden={!active}>
    <div className="pw-reading-tools">
      <div className="pw-reading-modes" role="group" aria-label="阅读方式">
        <button type="button" aria-pressed={mode === 'read'} onClick={() => changeMode('read')}>阅读</button>
        <button type="button" aria-pressed={mode === 'select'} onClick={() => changeMode('select')}>选字</button>
      </div>
      <span className="pw-reading-tool-space" />
      {mode === 'select' && <button type="button" onClick={() => { original.current?.focus({ preventScroll: true }); original.current?.select(); selectText(); }}>全选</button>}
      <button type="button" disabled={copying} aria-label={selected ? '复制选中文字' : snapshot.kind === 'table' ? '复制表格' : '复制全文'}
        onPointerDown={event => event.preventDefault()}
        onClick={() => { void copy(selected || (mode === 'select' ? snapshot.text : snapshot.copyText ?? snapshot.text)).catch(() => {}); }}>
        <IconCopy size={18} aria-hidden="true" /><span>{copying ? '复制中' : '复制'}</span>
      </button>
    </div>
    <p className="pw-reading-feedback" role="status" aria-live="polite">{feedback || (mode === 'select' && selected ? `已选 ${Array.from(selected).length} 字` : '')}</p>
    <div ref={body} className="pw-reading-scroll" hidden={mode !== 'read'} tabIndex={0} aria-label="阅读正文">
      {snapshot.kind === 'table' ? <ReadingContext.Provider value={null}>{content}</ReadingContext.Provider> : content}
    </div>
    <textarea ref={original} className="pw-reading-original" aria-label="选择原文" hidden={mode !== 'select'}
      value={snapshot.text} readOnly spellCheck={false} onSelect={selectText} />
  </section>;
});

export function ReadingView({ documents, onBack }: { documents: ReadingDocument[]; onBack: () => void }) {
  const current = documents.at(-1);
  const closeFocus = useRef<HTMLElement | null>(null);
  const previousDocuments = useRef(documents);
  if (documents.length === 1) closeFocus.current = current?.returnFocus ?? null;
  useLayoutEffect(() => {
    const previous = previousDocuments.current;
    previousDocuments.current = documents;
    if (!current || previous.length <= documents.length) return;
    // Restore only after the parent page is visible in the committed DOM.
    // A frame scheduled alongside setState can run while it is still hidden.
    const target = previous.at(-1)?.returnFocus;
    if (target?.isConnected) target.focus({ preventScroll: true });
  }, [documents, current]);
  return <Dialog.Root open={!!current} onOpenChange={open => { if (!open) onBack(); }}>
    <Dialog.Portal>
      <Dialog.Overlay className="pw-reading-overlay" />
      <Dialog.Content className="pw-reading-dialog" aria-describedby="pw-reading-description"
        onEscapeKeyDown={event => {
          // Radix handles Escape in document capture. Consume it here once,
          // before older settings dialogs can also close their own layer.
          event.preventDefault(); event.stopPropagation();
          if (!event.isComposing && event.keyCode !== 229) onBack();
        }}
        onKeyDown={event => { if (event.key === 'Tab') event.stopPropagation(); }}
        onPointerDownOutside={event => event.preventDefault()}
        onCloseAutoFocus={event => { event.preventDefault(); if (closeFocus.current?.isConnected) closeFocus.current.focus({ preventScroll: true }); }}>
        <header className="pw-reading-header">
          <button type="button" aria-label={documents.length > 1 ? '返回回复阅读' : '返回原工作'} onClick={onBack}><IconArrowLeft size={22} aria-hidden="true" /></button>
          <Dialog.Title>{current?.title}</Dialog.Title>
          <span>打开时的内容</span>
        </header>
        <Dialog.Description id="pw-reading-description" className="pw-reading-description">独立阅读当前内容，后续回复不会改变这里的文字。返回可继续原工作。</Dialog.Description>
        {documents.map((entry, index) => <ReadingPage key={entry.key} document={entry} active={index === documents.length - 1} />)}
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>;
}
