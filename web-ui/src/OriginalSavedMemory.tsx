import { useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { ShimmerText } from './library';
import { VisibleOriginalMemoryQuote } from './VisibleOriginalMemoryQuote';
import type { ConversationDraftTarget } from './SkillDraftReview';

type Read = { status: 'read' | 'changed' | 'not_active'; currentRevision: number; readExpiresAt: number; text?: string; visibleQuoteKey?: string };
const positive = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n > 0;
export const originalMemoryWindow = () => {
  const snapshot = bridge.getState(), s = snapshot.state, v = snapshot.view as Record<string, unknown> | undefined;
  return JSON.stringify([bridge.getDocumentEpoch(), s.conversationId, s.memoryRefreshRevision, s.traceSourceRevision, v?.section, v?.taskId, v?.resumeRevision]);
};

/** Mounted only by an explicit tap. Nothing subscribes in order to read again. */
export function OriginalSavedMemory({ target, id, revision, duplicate, windowKey, manage, close }: {
  target: ConversationDraftTarget; id: string; revision: number; duplicate: boolean; windowKey: string;
  manage?: () => void; close: () => void;
}) {
  const [read, setRead] = useState<Read | null>(null), [phase, setPhase] = useState<'loading' | 'settled' | 'unavailable' | 'closed'>('loading');
  const life = useRef({ live: false, seq: 0, timer: undefined as number | undefined, busy: false });
  const current = () => life.current.live && !document.hidden && originalMemoryWindow() === windowKey;
  const retire = () => {
    const s = life.current; s.live = false; s.seq++; clearTimeout(s.timer); setRead(null); setPhase('closed');
  };
  const load = async (chosenRevision?: number) => {
    const s = life.current;
    if (!current()) { retire(); return; }
    if (s.busy) return;
    s.busy = true; const seq = ++s.seq; clearTimeout(s.timer); setRead(null); setPhase('loading');
    try {
      const value = await bridge.command('memoryDraft.query', { ...target, view: 'saved_record', ...(chosenRevision == null ? {} : { chosenRevision }) });
      if (seq !== s.seq || !current()) return;
      if (value.format !== 'pocketworld.original-saved-personal-memory.v1' || Object.entries(target).some(([k, v]) => value[k] !== v)
          || value.id !== id || value.originalRevision !== revision || value.duplicate !== duplicate || !positive(value.currentRevision) || value.currentRevision < revision
          || !positive(value.readExpiresAt) || !['read', 'changed', 'not_active'].includes(value.status)
          || (value.status === 'read' ? (value.currentRevision !== (chosenRevision ?? revision) || typeof value.text !== 'string' || !value.text.trim()) : value.text !== undefined)
          || (value.status === 'changed' && value.currentRevision === (chosenRevision ?? revision))) throw new Error();
      const left = value.readExpiresAt - Date.now();
      if (left <= 0) { retire(); return; }
      s.timer = window.setTimeout(retire, left);
      setRead({ status: value.status, currentRevision: value.currentRevision, readExpiresAt: value.readExpiresAt, text: value.text, visibleQuoteKey: typeof value.visibleQuoteKey === 'string' && /^[a-f0-9]{64}$/.test(value.visibleQuoteKey) ? value.visibleQuoteKey : undefined });
      setPhase('settled');
    } catch {
      if (seq === s.seq && current()) { setRead(null); setPhase('unavailable'); }
    } finally { if (seq === s.seq) s.busy = false; }
  };
  useLayoutEffect(() => {
    const s = life.current; s.live = true; s.busy = false;
    const hidden = () => { if (document.hidden) retire(); };
    const off = bridge.subscribe(() => { if (s.live && !current()) retire(); });
    document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', retire);
    const first = ++s.seq; queueMicrotask(() => { if (s.live && s.seq === first) void load(); });
    return () => { s.live = false; s.seq++; clearTimeout(s.timer); off(); document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', retire); };
    // Each opened answer/record is keyed separately and never retargets a read.
  }, [windowKey, id, revision]);
  const withCurrentRead = (act: () => void) => { if (current() && read && Date.now() < read.readExpiresAt) act(); else retire(); };
  return <section className="pw-memory-draft-receipt" aria-label="这一条个人记忆">
    {phase === 'loading' && <ShimmerText>正在核对这条正式记忆…</ShimmerText>}
    {phase === 'closed' && <p role="status">原阅读窗口已关闭，未保留正文。回到原回答可重新核对。</p>}
    {phase === 'unavailable' && <p role="status">没能核对这条正式记忆，未显示旧正文。原保存回执不等于现在仍可读取。</p>}
    {read?.status === 'not_active' && <p role="status">回读时，这条记忆不在当前可用范围，未显示正文。</p>}
    {read?.status === 'changed' && <><p role="status">回执是第 {revision} 版。这条记忆已变为第 {read.currentRevision} 版，没有显示旧版或新版正文。</p>
      <Button variant="secondary" onClick={() => withCurrentRead(() => { void load(read.currentRevision); })}>查看这一条的第 {read.currentRevision} 版</Button></>}
    {read?.status === 'read' && <><p className="pw-muted">{read.currentRevision !== revision ? '这条记忆的后续版本' : duplicate ? '当时匹配到的同一版本，没有重复保存' : '与这次保存回执同一版本'} · 第 {read.currentRevision} 版</p>
      <p className="pw-reading-copy">{read.text}</p><p className="pw-muted">这是回读时的个人记忆，不证明内容属实，也不说明哪次回答用过它。</p>
      {read.visibleQuoteKey && read.currentRevision === revision && <VisibleOriginalMemoryQuote target={target} id={id} revision={revision} duplicate={duplicate}
        spanKey={read.visibleQuoteKey} deadline={read.readExpiresAt} current={current} />}
      {manage && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="manage-receipted-memory" title="管理这条记忆" description="编辑内容或核对来源">
        <p className="pw-muted">进入个人中心会加载个人资料与记忆目录。</p><Button variant="secondary" onClick={() => withCurrentRead(manage)}>进入个人中心</Button>
      </AccordionItem></Accordion>}</>}
    <Button variant="ghost" onClick={close}>返回原回答</Button>
  </section>;
}
