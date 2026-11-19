import { useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import type { ConversationDraftTarget } from './SkillDraftReview';

type Span = { messageId: string; revision: number; offset: number; chars: number; sha256: string; key: string };
type Quote = { span: Span; text: string; expires: number };
const hex = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const whole = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v);
const splitPair = (text: string, n: number) => n > 0 && n < text.length && /[\uDC00-\uDFFF]/.test(text[n]);
function visibleText(span: Span) {
  const snapshot = bridge.getState();
  if ((snapshot.view as {taskId?: string} | undefined)?.taskId || !Array.isArray(snapshot.state.messages)) return null;
  const matching = snapshot.state.messages.filter((m: Record<string, unknown>) => m?.id === span.messageId);
  const row = matching.length === 1 ? matching[0] : null;
  if (!row || row.role !== 'user' || row.sourceRevision !== span.revision || row.quoteAvailable !== true ||
      row.automaticLearningExcluded === true || typeof row.content !== 'string') return null;
  const end = span.offset + span.chars;
  if (end > row.content.length || splitPair(row.content, span.offset) || splitPair(row.content, end)) return null;
  return row.content.slice(span.offset, end);
}

/** Verify only the selected UTF-16 span already in this chat's native snapshot. No fallback source read. */
export function VisibleOriginalMemoryQuote({ target, id, revision, duplicate, spanKey, deadline, current }: {
  target: ConversationDraftTarget; id: string; revision: number; duplicate: boolean; spanKey: string; deadline: number; current: () => boolean;
}) {
  const [quote, setQuote] = useState<Quote | null>(null), [phase, setPhase] = useState<'idle' | 'busy' | 'lost'>('idle');
  const life = useRef({ live: false, seq: 0, busy: false, shown: null as Quote | null, timer: undefined as number | undefined });
  const erase = (lost: boolean) => { const s=life.current; s.seq++; s.busy=false; s.shown=null; clearTimeout(s.timer); setQuote(null); setPhase(lost?'lost':'idle'); };
  useLayoutEffect(() => {
    const s=life.current; s.live=true;
    const hide = () => { if (document.hidden) erase(true); }, leave = () => erase(true);
    const off = bridge.subscribe(() => { if (!current() || s.shown && visibleText(s.shown.span) !== s.shown.text) erase(true); });
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', leave);
    return () => { s.live=false; s.seq++; s.shown=null; clearTimeout(s.timer); off(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', leave); };
  }, [id, revision, spanKey, deadline]);
  const show = async () => {
    const s=life.current; if (!s.live || s.busy || !current() || Date.now() >= deadline || !hex(spanKey)) return;
    erase(false); s.busy=true; setPhase('busy'); const seq=s.seq;
    const valid = () => s.live && s.seq===seq && current() && Date.now()<deadline;
    try {
      const result=await bridge.command('memoryDraft.query',{ ...target, view:'saved_quote', chosenRevision:revision, spanKey });
      if (!valid()) return;
      const span=result.span, expires=Math.min(deadline,result.readExpiresAt);
      if (result.format!=='pocketworld.original-saved-personal-memory.v1'||result.status!=='quote_pin'||Object.entries(target).some(([k,v])=>result[k]!==v)||
          result.id!==id||result.originalRevision!==revision||result.currentRevision!==revision||result.duplicate!==duplicate||result.text!==undefined||
          !whole(result.readExpiresAt)||Date.now()>=expires||span?.key!==spanKey||typeof span.messageId!=='string'||!span.messageId||!hex(span.sha256)||
          !whole(span.revision)||span.revision<=0||!whole(span.offset)||span.offset<0||!whole(span.chars)||span.chars<1||span.chars>4000) throw new Error();
      const text=visibleText(span);
      if (text===null || text.length!==span.chars) throw new Error();
      const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text));
      if (!valid()) return;
      if (Date.now()>=expires || Array.from(new Uint8Array(bytes),n=>n.toString(16).padStart(2,'0')).join('')!==span.sha256 || visibleText(span)!==text) throw new Error();
      const shown={span,text,expires}; s.shown=shown; s.timer=window.setTimeout(()=>erase(true),expires-Date.now()); setQuote(shown); setPhase('idle');
    } catch { if (valid()) erase(true); }
    finally { if (s.seq===seq) s.busy=false; }
  };
  return <section aria-label="原回答登记的原句">
    {quote ? <><p className="pw-muted">原回答登记的引文，与此刻原对话中的这段文字一致。</p><blockquote className="pw-reading-copy">{quote.text}</blockquote>
      <Button variant="ghost" onClick={()=>erase(false)}>收起原句</Button></> : <>
      {phase==='lost' && <p className="pw-muted" role="status">这段原话未能核对。没有打开其他历史，也未显示旧引文。</p>}
      <Button variant="secondary" disabled={phase==='busy'} onClick={()=>{void show();}}>{phase==='busy'?'正在核对…':'核对当时引用的原句'}</Button></>}
  </section>;
}
