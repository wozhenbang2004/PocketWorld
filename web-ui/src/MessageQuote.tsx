import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { messageQuoteRange } from './message-quote';
import { originalPhoneReplyText } from './phone-reply-text';

export type MessageQuoteTarget = { messageId: string; sourceRevision: number; key: string; epoch: number; prior?: { reader: number; request: string; revision: number; offset: number; chars: number } };
type QuoteWindow = { windowId: string; text: string; maxChars: number; draftRevision: number; shelfEligible?: boolean };

/** Selection only; the original locator and draft transaction remain in the native controller. */
export function MessageQuote({ target, onClose, onStaged, phoneReceipt }: { target: MessageQuoteTarget; onClose: () => void; onStaged?: () => void; phoneReceipt?: 'full'|'excerpt' }) {
  const [view, setView] = useState<QuoteWindow | null>(null), [selection, setSelection] = useState({ start: 0, end: 0 });
  const [busy, setBusy] = useState(true), [attempted, setAttempted] = useState(false), [error, setError] = useState('');
  const [pinRange, setPinRange] = useState<{offset:number;chars:number}|null>(null), [label, setLabel] = useState(''), [pinNotice, setPinNotice] = useState('');
  const savingPin = useRef(false), remembered = useRef<{start:number;end:number}|null>(null);
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
      if (typeof value?.windowId !== 'string' || !value.windowId || typeof value.text !== 'string' || value.maxChars !== 4000 || target.prior && (value.registeredRangeOnly !== true || value.text.length !== target.prior.chars) || !Number.isSafeInteger(value.draftRevision) || value.draftRevision < 0) {
        if (typeof value?.windowId === 'string') void bridge.command('messageQuote.close', { windowId: value.windowId }).catch(() => {});
        throw new Error('原消息读取回执不完整。');
      }
      windowId.current = value.windowId; setView(value);
    }).catch(failure => { if (alive.current && generation.current === request) setError(failure instanceof Error ? failure.message : String(failure)); }).finally(() => { if (alive.current && generation.current === request) setBusy(false); });
    return () => { alive.current = false; generation.current++; off(); document.removeEventListener('visibilitychange', visibility); const id = windowId.current; windowId.current = ''; if (id) void bridge.command('messageQuote.close', { windowId: id }).catch(() => {}); };
  }, []);
  const sourceText = view ? (phoneReceipt==='excerpt' ? originalPhoneReplyText(view.text,true) : view.text) : '';
  useEffect(() => {
    const element = textArea.current;
    if (!element || !view) return;
    const changed = () => setSelection({ start: element.selectionStart, end: element.selectionEnd });
    element.addEventListener('select', changed);
    const prior=remembered.current; element.setSelectionRange(prior?.start ?? 0, prior?.end ?? (sourceText.length <= 4000 ? element.value.length : 0)); remembered.current=null; changed();
    return () => element.removeEventListener('select', changed);
  }, [view, sourceText, !!pinRange]);
  const range = view ? messageQuoteRange(sourceText, selection.start, selection.end) : null;
  const stage = () => {
    if (!range || !windowId.current || submitted.current || busy) return;
    submitted.current = true; setAttempted(true); setBusy(true); setError('');
    void bridge.command('messageQuote.stage', { windowId: windowId.current, ...range }).then(value => {
      if (!alive.current) return;
      if (value?.selectionValidated !== true || value.draftStaged !== true || value.persisted !== true || value.key !== target.key ||
          value.epoch !== target.epoch || !view || value.fromRevision !== view.draftRevision || value.revision !== view.draftRevision + 1)
        throw new Error('原选区尚未确认保存到这份草稿，请先核对原输入。');
      onClose(); onStaged?.();
    }).catch(failure => { if (alive.current) { setView(null); setError(failure instanceof Error ? failure.message : String(failure)); } }).finally(() => { if (alive.current) setBusy(false); });
  };
  const pin = () => {
    if (!pinRange || !view?.shelfEligible || savingPin.current || busy || !windowId.current) return;
    const id = windowId.current, serial = generation.current; savingPin.current = true; setBusy(true); setError('');
    void bridge.command('messageQuote.pin', {windowId:id, ...pinRange, label:label.trim()}).then(value => {
      if (!alive.current || generation.current !== serial) return;
      if (value?.windowId !== id || target.key !== 'conversation:' + value.conversationId || value.chars !== pinRange.chars || value.bodyIncluded !== false ||
          typeof value.alreadyPinned !== 'boolean' || typeof value.id !== 'string' || !value.id) throw new Error('未确认原对话已保留这段，请先查看本对话备忘。');
      setPinNotice(value.alreadyPinned ? '这段原话已在本对话备忘里，没有新增一条。' : '已留在本对话备忘。草稿没有变化。');
      setPinRange(null); setLabel('');
    }).catch(failure => { if (alive.current && generation.current === serial) { setPinRange(null); setView(null); setError(failure instanceof Error ? failure.message : String(failure)); } })
      .finally(() => { savingPin.current = false; if (alive.current && generation.current === serial) setBusy(false); });
  };
  const canPin = view?.shelfEligible === true && bridge.getState().capabilities.commands.includes('messageQuote.pin');
  return <SettingsDialog isOpen onClose={onClose} closeLabel={phoneReceipt?'返回原报告':undefined} title={pinRange ? '留在本对话' : phoneReceipt ? '在聊天里追问这份回执' : target.prior ? '再次引用这段原话' : '引用这条消息'}>
    {pinRange && view ? <><p>只在这段私聊保留原话的位置，不加入草稿或个人记忆，也不触发模型请求。</p>
      <blockquote className="pw-source-excerpt" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{view.text.slice(pinRange.offset,pinRange.offset+pinRange.chars)}</blockquote>
      <TextField label="备忘名（可选）" value={label} onChange={setLabel} maxLength={60} isDisabled={busy} hint="方便以后在本对话中认出它。没有标题时按保留时间显示。" />
      <div className="pw-form-actions"><Button disabled={busy} onClick={pin}>{busy?'正在保留…':'保留这段'}</Button><Button variant="secondary" disabled={busy} onClick={()=>{setPinRange(null);setLabel('');}}>返回选区</Button></div></> : <>
    <p>{phoneReceipt ? (phoneReceipt==='excerpt'?'从已回到原私聊的节选里选择原话，未投递的报告段落不在这里。':'选择要追问的回执原话，不附带截图或操作日志。')+' 加入草稿后再写问题，不会启动手机任务。' : target.prior ? '只显示上次登记的原段。可缩小选区，再加入本对话当前草稿；不会直接发送或保存为记忆。' : '选择这次要用的原话。只把所选范围的定位保存到本草稿，不是长期记忆；发送前仍可移除，发送时会再次核对原消息。'}</p>
    {view && <TextAreaField ref={textArea} label="长按或拖动选择原文" value={sourceText} isReadOnly isDisabled={busy || attempted} rows={6} />}
    {view && <p className="pw-muted">{range ? `已选择 ${range.chars} / 4000 个字符` : '请选择完整的一段原话，最多 4000 个字符。'}</p>}
    {pinNotice && <p role="status">{pinNotice}</p>}
    {attempted && error && <p className="pw-muted">请返回核对本草稿的引用，未确认前不要重复添加。</p>}
    <div className="pw-form-actions"><Button disabled={busy || attempted || !range} onClick={stage}>{busy ? (attempted ? '正在保存原草稿…' : '正在核对原文…') : phoneReceipt?'加入草稿，去写问题':'加入这次草稿'}</Button>{canPin && <Button variant="secondary" disabled={busy || attempted || !range} onClick={()=>{setPinNotice('');setError('');remembered.current=selection;setPinRange(range);}}>留在本对话</Button>}<Button variant="ghost" onClick={onClose}>{phoneReceipt?'返回原报告':'返回消息'}</Button></div></>}
    {error && <p className="pw-error" role="alert">{error}</p>}
  </SettingsDialog>;
}
