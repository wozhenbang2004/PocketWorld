import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, TextAreaField } from './forms-library';
import { messageQuoteRange } from './message-quote';

export type MessageQuoteTarget = { messageId: string; sourceRevision: number; key: string; epoch: number };
type QuoteWindow = { windowId: string; text: string; maxChars: number; draftRevision: number };

/** Selection only; the original locator and draft transaction remain in the native controller. */
export function MessageQuote({ target, onClose }: { target: MessageQuoteTarget; onClose: () => void }) {
  const [view, setView] = useState<QuoteWindow | null>(null), [selection, setSelection] = useState({ start: 0, end: 0 });
  const [busy, setBusy] = useState(true), [attempted, setAttempted] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), windowId = useRef(''), generation = useRef(0), textArea = useRef<HTMLTextAreaElement>(null), submitted = useRef(false);
  const closeOwner = useRef(onClose); closeOwner.current = onClose;
  useEffect(() => {
    alive.current = true; const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch();
    const leave = () => { alive.current = false; generation.current++; setView(null); closeOwner.current(); };
    const visibility = () => { if (document.visibilityState === 'hidden') leave(); };
    if (document.visibilityState === 'hidden') { leave(); return; }
    const off = bridge.subscribe(() => { if (documentEpoch !== bridge.getDocumentEpoch()) leave(); });
    document.addEventListener('visibilitychange', visibility);
    void bridge.command('messageQuote.open', target).then(value => {
      if (!alive.current || generation.current !== request) { if (value?.windowId) void bridge.command('messageQuote.close', { windowId: value.windowId }).catch(() => {}); return; }
      if (typeof value?.windowId !== 'string' || !value.windowId || typeof value.text !== 'string' || value.maxChars !== 4000 || !Number.isSafeInteger(value.draftRevision) || value.draftRevision < 0) {
        if (typeof value?.windowId === 'string') void bridge.command('messageQuote.close', { windowId: value.windowId }).catch(() => {});
        throw new Error('原消息读取回执不完整。');
      }
      windowId.current = value.windowId; setView(value);
    }).catch(failure => { if (alive.current && generation.current === request) setError(failure instanceof Error ? failure.message : String(failure)); }).finally(() => { if (alive.current && generation.current === request) setBusy(false); });
    return () => { alive.current = false; generation.current++; off(); document.removeEventListener('visibilitychange', visibility); const id = windowId.current; windowId.current = ''; if (id) void bridge.command('messageQuote.close', { windowId: id }).catch(() => {}); };
  }, []);
  useEffect(() => {
    const element = textArea.current;
    if (!element || !view) return;
    const changed = () => setSelection({ start: element.selectionStart, end: element.selectionEnd });
    element.addEventListener('select', changed);
    element.setSelectionRange(0, view.text.length <= 4000 ? element.value.length : 0); changed();
    return () => element.removeEventListener('select', changed);
  }, [view]);
  const range = view ? messageQuoteRange(view.text, selection.start, selection.end) : null;
  const stage = () => {
    if (!range || !windowId.current || submitted.current || busy) return;
    submitted.current = true; setAttempted(true); setBusy(true); setError('');
    void bridge.command('messageQuote.stage', { windowId: windowId.current, ...range }).then(value => {
      if (!alive.current) return;
      if (value?.selectionValidated !== true || value.draftStaged !== true || value.persisted !== true || value.key !== target.key ||
          value.epoch !== target.epoch || !view || value.fromRevision !== view.draftRevision || value.revision !== view.draftRevision + 1)
        throw new Error('原选区尚未确认保存到这份草稿，请先核对原输入。');
      onClose();
    }).catch(failure => { if (alive.current) { setView(null); setError(failure instanceof Error ? failure.message : String(failure)); } }).finally(() => { if (alive.current) setBusy(false); });
  };
  return <SettingsDialog isOpen onClose={onClose} title="引用这条消息">
    <p>选择这次要用的原话。只把所选范围的定位保存到本草稿，不是长期记忆；发送前仍可移除，发送时会再次核对原消息。</p>
    {view && <TextAreaField ref={textArea} label="长按或拖动选择原文" value={view.text} isReadOnly isDisabled={busy || attempted} rows={6} />}
    {view && <p className="pw-muted">{range ? `已选择 ${range.chars} / 4000 个字符` : '请选择完整的一段原话，最多 4000 个字符。'}</p>}
    {error && <p className="pw-error" role="alert">{error}</p>}
    {attempted && error && <p className="pw-muted">请返回核对本草稿的引用，未确认前不要重复添加。</p>}
    <div className="pw-form-actions"><Button disabled={busy || attempted || !range} onClick={stage}>{busy ? (attempted ? '正在保存原草稿…' : '正在核对原文…') : '加入这次草稿'}</Button><Button variant="secondary" onClick={onClose}>返回消息</Button></div>
  </SettingsDialog>;
}
