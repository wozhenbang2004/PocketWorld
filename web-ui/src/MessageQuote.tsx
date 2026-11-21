import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { confirmedQuoteStage, messageQuoteRange, selectedQuoteRange } from './message-quote';
import { originalPhoneReplyText } from './phone-reply-text';

export type MessageQuoteTarget = { messageId: string; sourceRevision: number; key: string; epoch: number; prior?: { reader: number; request: string; revision: number; offset: number; chars: number } };
export type OpenedMessageDocumentDraft = { library: string; windowId: string };
type QuoteWindow = { windowId: string; text: string; maxChars: number; draftRevision: number; shelfEligible?: boolean };
type DocumentReceipt = { name: string; savedAt: number };
const token=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value);
const sourceFingerprint = async (text: string) => 'sha256:' +
  Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))),
    n => n.toString(16).padStart(2,'0')).join('');

/** Selection only; the original locator and draft transaction remain in the native controller. */
export function MessageQuote({ target, onClose, onStaged, phoneReceipt, documentSource, onOpenSavedDocument }: {
  target: MessageQuoteTarget; onClose: () => void; onStaged?: () => void; phoneReceipt?: 'full'|'excerpt'; documentSource?: string;
  onOpenSavedDocument?: (visit:OpenedMessageDocumentDraft)=>boolean;
}) {
  const documentCopy = documentSource !== undefined && !phoneReceipt && !target.prior;
  const [view, setView] = useState<QuoteWindow | null>(null), [selection, setSelection] = useState({ start: 0, end: 0 });
  const [busy, setBusy] = useState(true), [attempted, setAttempted] = useState(false), [error, setError] = useState('');
  const [staged, setStaged] = useState(false);
  const [documentAttempt, setDocumentAttempt] = useState<'unknown'|'quota'|DocumentReceipt>();
  const [documentOpenUnconfirmed,setDocumentOpenUnconfirmed]=useState(false);
  const copyingDocument = useRef(false);
  const [pinRange, setPinRange] = useState<{offset:number;chars:number}|null>(null), [label, setLabel] = useState(''), [pinNotice, setPinNotice] = useState('');
  const savingPin = useRef(false), remembered = useRef<{start:number;end:number}|null>(null);
  const alive = useRef(true), windowId = useRef(''), generation = useRef(0), textArea = useRef<HTMLTextAreaElement>(null), submitted = useRef(false);
  const openEpoch = useRef(bridge.getDocumentEpoch());
  const closeOwner = useRef(onClose); closeOwner.current = onClose;
  useEffect(() => {
    alive.current = true; const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch();
    const leave = () => { alive.current = false; generation.current++; setView(null); closeOwner.current(); };
    const visibility = () => { if (document.visibilityState === 'hidden') leave(); };
    if (document.visibilityState === 'hidden') { leave(); return; }
    const off = bridge.subscribe(() => { if (documentEpoch !== bridge.getDocumentEpoch()) leave(); });
    document.addEventListener('visibilitychange', visibility);
    void bridge.command(documentCopy?'messageQuote.document.open':'messageQuote.open', target).then(async value => {
      const id=typeof value?.windowId==='string'?value.windowId:'';
      const release=()=>{if(id)void bridge.command('messageQuote.close',{windowId:id}).catch(()=>{})};
      if (!alive.current || generation.current !== request) { release(); return; }
      if (!id || value.maxChars !== 4000 || !Number.isSafeInteger(value.draftRevision) || value.draftRevision < 0) {
        release(); throw new Error('原消息读取回执不完整。');
      }
      if (documentCopy) {
        windowId.current = id;
        if(value.documentCopy!==true||value.registeredRangeOnly!==false||value.text!==undefined||
           typeof documentSource!=='string'||value.chars!==documentSource.length||value.fingerprint!==await sourceFingerprint(documentSource)){
          windowId.current='';release();throw new Error('当前原消息与所选版本不一致，没有使用其他文字。');
        }
        if(!alive.current||generation.current!==request)return;
        setView({...value,text:documentSource});
      } else {
        if(typeof value.text!=='string'||target.prior&&(value.registeredRangeOnly!==true||value.text.length!==target.prior.chars)){
          release();throw new Error('原消息读取回执不完整。');
        }
        windowId.current = id; setView(value);
      }
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
  const stillInDraft = () => {
    const {composer, state} = bridge.getState();
    return alive.current && !document.hidden && bridge.getDocumentEpoch() === openEpoch.current &&
      composer?.ready === true && composer.key === target.key && composer.epoch === target.epoch && !state.chatSubmitting;
  };
  const stage = () => {
    if (!view || !windowId.current || submitted.current || busy) return;
    const element = textArea.current, actual = element && selectedQuoteRange(sourceText, element);
    if (!actual) { setError('没有加入草稿。请重新选中要用的原话。'); return; }
    if (!stillInDraft() || bridge.getState().composer?.revision !== view.draftRevision) {
      setView(null); setError('草稿已变化，这次没有加入。请在原消息重新选择。'); return;
    }
    const id = windowId.current, serial = generation.current;
    submitted.current = true; setAttempted(true); setBusy(true); setError('');
    void bridge.command('messageQuote.stage', { windowId: id, ...actual }).then(value => {
      if (!alive.current || generation.current !== serial) return;
      if (!confirmedQuoteStage(value, target, view.draftRevision) || !stillInDraft())
        throw new Error('未确认所选原文已保存到这份草稿。');
      if (phoneReceipt) { onClose(); onStaged?.(); }
      else setStaged(true);
    }).catch(failure => {
      if (alive.current && generation.current === serial) {
        setView(null); setError(failure instanceof Error ? failure.message : '未确认所选原文已保存到这份草稿。');
      }
    }).finally(() => { if (alive.current && generation.current === serial) setBusy(false); });
  };
  const retainDocument = (checking=false) => {
    if (!documentCopy || !view || !windowId.current || busy || copyingDocument.current ||
        (checking ? documentAttempt!=='unknown' : !!documentAttempt)) return;
    const element=textArea.current, actual=!checking&&element?selectedQuoteRange(view.text,element):undefined;
    if(!checking&&(!actual||!view.text.slice(actual.offset,actual.offset+actual.chars).trim())){
      setError('请选中要保留的文字，最多 4000 个完整字符。');return;
    }
    if(!stillInDraft()||bridge.getState().composer?.revision!==view.draftRevision){
      setError('原对话或草稿已经变化。请回到原消息重新选择。');return;
    }
    const id=windowId.current, serial=generation.current;
    copyingDocument.current=true;setBusy(true);setError('');setDocumentAttempt('unknown');
    void bridge.command(checking?'messageQuote.document.check':'messageQuote.document.retain',{windowId:id,...(actual||{})}).then(receipt=>{
      if(!alive.current||generation.current!==serial||!stillInDraft())return;
      if(receipt?.windowId!==id)throw new Error('草稿保留尚未核对。');
      if(receipt.status==='QUOTA'){setDocumentAttempt('quota');return}
      if(['SAVED','VERIFIED'].includes(receipt.status)&&typeof receipt.name==='string'&&!!receipt.name&&receipt.name.length<=80&&
         Number.isSafeInteger(receipt.savedAt)&&receipt.savedAt>0)setDocumentAttempt({name:receipt.name,savedAt:receipt.savedAt});
    }).catch(()=>{
      if(alive.current&&generation.current===serial)setError('暂不能核对这次保留。可先在本机文档草稿查找，不会重复保存。');
    }).finally(()=>{copyingDocument.current=false;if(alive.current&&generation.current===serial)setBusy(false)});
  };
  const dismiss = () => { if ((!submitted.current || !busy) && !copyingDocument.current) onClose(); };
  const openSavedDocument=()=>{
    if(!documentCopy||typeof documentAttempt!=='object'||!view||!windowId.current||busy||copyingDocument.current||
       documentOpenUnconfirmed||!onOpenSavedDocument)return;
    const native=bridge.getState();
    if(!stillInDraft()||native.composer?.revision!==view.draftRevision||native.state.attachmentWorkspace?.open){
      setError('原对话已变化。草稿仍保留在本机，没有打开其他草稿。');return;
    }
    const id=windowId.current,serial=generation.current;
    copyingDocument.current=true;setBusy(true);setError('');
    void bridge.command('messageQuote.document.openSaved',{windowId:id}).then(value=>{
      if(!alive.current||generation.current!==serial)return;
      if(!stillInDraft()||value?.opened!==true||value.quoteWindowId!==id||!token(value.windowId)||!token(value.library)||
         !onOpenSavedDocument({windowId:value.windowId,library:value.library}))throw new Error();
    }).catch(()=>{
      if(alive.current&&generation.current===serial){
        setDocumentOpenUnconfirmed(true);setError('草稿已保留，打开尚未核对。可返回原消息，或到「成果 → 本机文档草稿」核对。');
      }
    }).finally(()=>{copyingDocument.current=false;if(alive.current&&generation.current===serial)setBusy(false)});
  };
  const finish = () => {
    if (!stillInDraft()) { setError('原草稿已切换；请选择原来的草稿继续。'); return; }
    onClose(); onStaged?.();
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
  if(documentCopy){
    const saved=typeof documentAttempt==='object'?documentAttempt:undefined;
    return <SettingsDialog isOpen onClose={dismiss} closeLabel="返回原消息" title={saved?'已保留文档草稿':'选择文档文字'}>
      {saved?<p role="status">「{saved.name}」已保留在「成果 → 本机文档草稿」（{new Date(saved.savedAt).toLocaleString()}）。原消息和输入未改。</p>:<>
        <p>只把选中的原文保留为可编辑草稿，不生成文件，也不发送消息。</p>
        {view&&<TextAreaField ref={textArea} label="长按或拖动选择原文" value={sourceText} isReadOnly isDisabled={busy||!!documentAttempt} rows={6}/>}
        {view&&!documentAttempt&&<p className="pw-muted">{range?`已选择 ${range.chars} / 4000 个字符`:'请选择完整的一段原文，最多 4000 个字符。'}</p>}
        {documentAttempt==='unknown'&&!busy&&<p role="status">是否已保留仍待核对，不会创建另一份。</p>}
        {documentAttempt==='quota'&&<p role="status">本机文档草稿已达容量上限，没有保留这次选段。</p>}
      </>}
      {error&&<p className="pw-error" role="alert">{error}</p>}
      <div className="pw-form-actions">
        {!documentAttempt&&<Button disabled={busy||!range} onPointerDown={event=>event.preventDefault()} onClick={()=>retainDocument()}>保留为文档草稿</Button>}
        {documentAttempt==='unknown'&&<Button variant="secondary" disabled={busy} onClick={()=>retainDocument(true)}>{busy?'正在核对…':'核对这份草稿'}</Button>}
        {saved&&!documentOpenUnconfirmed&&onOpenSavedDocument&&bridge.getState().capabilities.commands.includes('messageQuote.document.openSaved')&&
          <Button disabled={busy} onClick={openSavedDocument}>{busy?'正在打开…':'打开这份草稿'}</Button>}
        <Button variant="ghost" disabled={copyingDocument.current} onClick={dismiss}>返回原消息</Button>
      </div>
    </SettingsDialog>;
  }
  if (staged) return <SettingsDialog isOpen onClose={onClose} closeLabel="关闭引用回执" title="已加入这次草稿">
    <p role="status">所选原文已关联并保存在这份草稿里。问题还没发送。</p>
    {error && <p className="pw-error" role="alert">{error}</p>}
    <div className="pw-form-actions"><Button onClick={finish}>{onStaged?'回到草稿':'完成'}</Button>
      {onStaged&&<Button variant="ghost" onClick={onClose}>留在这里</Button>}</div>
  </SettingsDialog>;
  return <SettingsDialog isOpen onClose={dismiss} closeLabel={phoneReceipt?'返回原报告':undefined} title={pinRange ? '留在本对话' : phoneReceipt ? '在聊天里追问这份回执' : target.prior ? '再次引用这段原话' : '引用这条消息'}>
    {pinRange && view ? <><p>只在这段私聊保留原话的位置，不加入草稿或个人记忆，也不触发模型请求。</p>
      <blockquote className="pw-source-excerpt" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{view.text.slice(pinRange.offset,pinRange.offset+pinRange.chars)}</blockquote>
      <TextField label="备忘名（可选）" value={label} onChange={setLabel} maxLength={60} isDisabled={busy} hint="方便以后在本对话中认出它。没有标题时按保留时间显示。" />
      <div className="pw-form-actions"><Button disabled={busy} onClick={pin}>{busy?'正在保留…':'保留这段'}</Button><Button variant="secondary" disabled={busy} onClick={()=>{setPinRange(null);setLabel('');}}>返回选区</Button></div></> : <>
    <p>{phoneReceipt ? (phoneReceipt==='excerpt'?'从已回到原私聊的节选里选择原话，未投递的报告段落不在这里。':'选择要追问的回执原话，不附带截图或操作日志。')+' 加入草稿后再写问题，不会启动手机任务。' : target.prior ? '只显示上次登记的原段。可缩小选区，再加入本对话当前草稿；不会直接发送或保存为记忆。' : '选择这次要用的原话。只把所选范围的定位保存到本草稿，不是长期记忆；发送前仍可移除，发送时会再次核对原消息。'}</p>
    {view && <TextAreaField ref={textArea} label="长按或拖动选择原文" value={sourceText} isReadOnly isDisabled={busy || attempted} rows={6} />}
    {view && <p className="pw-muted">{range ? `已选择 ${range.chars} / 4000 个字符` : '请选择完整的一段原话，最多 4000 个字符。'}</p>}
    {pinNotice && <p role="status">{pinNotice}</p>}
    {attempted && error && <p className="pw-muted">先回原草稿检查「原文引用」。没有再次添加或发送。</p>}
    <div className="pw-form-actions"><Button disabled={busy || attempted || !range} onPointerDown={event=>event.preventDefault()} onClick={stage}>{busy ? (attempted ? '正在保存原草稿…' : '正在核对原文…') : phoneReceipt?'加入草稿，去写问题':'加入这次草稿'}</Button>{canPin && <Button variant="secondary" disabled={busy || attempted || !range} onClick={()=>{setPinNotice('');setError('');remembered.current=selection;setPinRange(range);}}>留在本对话</Button>}<Button variant="ghost" disabled={attempted&&busy} onClick={dismiss}>{phoneReceipt?'返回原报告':'返回消息'}</Button></div></>}
    {error && <p className="pw-error" role="alert">{error}</p>}
  </SettingsDialog>;
}
