import { useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';

type Data = Record<string, any>;
type Invoke = (action: string, args: Data, receive: (value: Data) => void) => Promise<unknown>;
type Props = { group: Data; pending: Data[]; deadline: number; busy: boolean; invoke: Invoke };
type Page = { index: number; text: string; from: number; next: number; more: boolean; previous: number[] };
const rows = (a: any): Data[] => Array.isArray(a) ? a : [];
const integer = (a: any) => Number.isSafeInteger(a);
const scope = () => { const s = bridge.getState().state; return JSON.stringify([bridge.getDocumentEpoch(), s.memoryRefreshRevision, s.traceSourceRevision]); };
const refKey = (r?: Data) => r && ['message', 'task_evidence'].includes(r.kind) && r.ownerId === 'owner' && r.spaceId === 'private' &&
  typeof r.sourceId === 'string' && r.sourceId.length > 0 && integer(r.revision) && r.revision > 0 && r.workspace === undefined
  ? JSON.stringify([r.kind, r.sourceId, r.revision, r.ownerId, r.spaceId]) : '';
const validSpan = (v?: Data) => v && refKey(v.sourceRef) && integer(v.offset) && v.offset >= 0 && integer(v.chars) && v.chars > 0 && v.chars <= 24000 && typeof v.sha256 === 'string' && /^[a-f0-9]{64}$/.test(v.sha256);
const sameSpan = (a?: Data, b?: Data) => validSpan(a) && validSpan(b) && a!.offset === b!.offset && a!.chars === b!.chars && a!.sha256 === b!.sha256 && refKey(a!.sourceRef) === refKey(b!.sourceRef);
const sourceName = (r: Data) => r.kind === 'message' ? '原消息' : '任务证据';
function memberInWindow(row: Data, pending: Data[]) {
  const hits = pending.filter(r => r.id === row.id); if (hits.length !== 1) return;
  const r = hits[0];
  if (typeof row.id !== 'string' || !row.id || !integer(row.revision) || row.revision <= 0 || row.ownerId !== 'owner' || row.spaceId !== 'private' ||
      typeof r.readHandle !== 'string' || !r.readHandle || !r.assertion || !row.assertion ||
      !['revision', 'ownerId', 'spaceId', 'text', 'assertionStatus', 'reviewRole'].every(k => r[k] === row[k]) ||
      !['subjectId', 'predicate', 'condition'].every(k => typeof row.assertion[k] === 'string' && r.assertion[k] === row.assertion[k])) return;
  return r;
}

/** Opening the disclosure or choosing a version only changes local focus. Each source read is a separate tap. */
export function MemoryConflictEvidence(props: Props) {
  const [open, setOpen] = useState(false);
  if (!rows(props.group.records).some(r => rows(r.assertion?.evidenceSpans).some(v => validSpan(v)))) return <p className="pw-muted">这组未登记可核对的私人原句，不把记忆正文当成原话。</p>;
  return <section aria-label="冲突版本依据">
    <Button variant="ghost" aria-expanded={open} disabled={!open && props.busy} onClick={() => setOpen(v => !v)}>{open ? '收起依据' : '逐条核对原句依据'}</Button>
    {open && <Reader {...props} />}
  </section>;
}

function Reader({ group, pending, deadline, busy, invoke }: Props) {
  const originals = rows(group.records), [member, setMember] = useState<number | null>(null);
  const [page, setPage] = useState<Page | null>(null), [failed, setFailed] = useState(false), [closed, setClosed] = useState(false);
  const life = useRef({ active: false, seq: 0, scope: '' });
  const erase = () => { life.current.seq++; setPage(null); setFailed(false); };
  useLayoutEffect(() => {
    const s = life.current; s.active = true; s.scope = scope(); setPage(null); setFailed(false); setClosed(false);
    const retire = () => { s.active = false; s.seq++; setPage(null); setClosed(true); };
    const hidden = () => { if (document.hidden) retire(); };
    const off = bridge.subscribe(() => { if (s.scope !== scope()) retire(); });
    const left = integer(deadline) ? deadline - Date.now() : 0;
    const timer = left > 0 ? window.setTimeout(retire, left) : undefined;
    if (left <= 0 || document.hidden) retire();
    document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', retire);
    return () => { s.active = false; s.seq++; clearTimeout(timer); off(); document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', retire); };
  }, [group.conflictHandle, deadline]);
  const chosen = member === null ? undefined : originals[member], record = chosen && memberInWindow(chosen, pending);
  const spans = rows(chosen?.assertion?.evidenceSpans), actual = rows(record?.assertion?.evidenceSpans);
  const eligible = (i: number) => record && sameSpan(spans[i], actual[i]);
  const read = async (i: number, from = 0, previous: number[] = []) => {
    const s = life.current;
    if (busy || !chosen || !record || !eligible(i) || !s.active || document.hidden || scope() !== s.scope || Date.now() >= deadline) return;
    erase(); const seq = s.seq, ref = spans[i], valid = () => s.active && s.seq === seq && !document.hidden && scope() === s.scope && Date.now() < deadline;
    let received = false;
    try {
      await invoke('memoryHistory.source', { readHandle: record.readHandle, conflictHandle: group.conflictHandle, span: true, index: i, offset: from }, v => {
        if (!valid()) return;
        if (v.format !== 'pocketworld.conflict-span-page.v1' || v.recordId !== chosen.id || v.recordRevision !== chosen.revision || v.index !== i ||
            v.readExpiresAt !== deadline || v.verifiedOriginalSpan !== true || v.assertionTruthVerified !== false || v.executionAuthorized !== false ||
            v.spanOffset !== ref.offset || v.chars !== ref.chars || v.sha256 !== ref.sha256 || refKey(v.sourceRef) !== refKey(ref.sourceRef) ||
            v.offset !== from || typeof v.text !== 'string' || v.text.length < 1 || v.text.length > 8000 || !integer(v.nextOffset) ||
            v.nextOffset !== from + v.text.length || v.nextOffset > ref.chars || v.hasMore !== (v.nextOffset < ref.chars)) return;
        received = true; setPage({ index: i, text: v.text, from, next: v.nextOffset, more: v.hasMore, previous });
      });
    } catch { /* Failure is not a replacement source. */ } finally { if (valid() && !received) setFailed(true); }
  };
  if (closed) return <p className="pw-muted" role="status">原阅读窗口已关闭。请重新读取当前认知；未保留旧引文。</p>;
  if (!chosen) return <div className="pw-memory-list"><p className="pw-muted">选要核对的版本，不会改变保留或并存的选择。</p>
    {originals.map((r, i) => <Button key={r.id} variant="ghost" disabled={busy} onClick={() => { erase(); setMember(i); }}><span className="pw-memory-row-copy"><strong>{r.text}</strong><small>核对依据 · 修订 {r.revision}</small></span></Button>)}</div>;
  return <section className="pw-source-record" aria-label="这一版本的登记依据">
    <Button variant="ghost" onClick={() => { erase(); setMember(null); }}>返回版本对照</Button><p className="pw-reading-copy">{chosen.text}</p><p className="pw-muted">正在核对修订 {chosen.revision} 的依据</p>
    {!spans.some((_, i) => eligible(i)) ? <p className="pw-muted">这版没有可核对的私人原句。没有打开整份来源或其他对话。</p> :
      spans.map((v, i) => eligible(i) && <Button key={i} variant="ghost" disabled={busy} onClick={() => { void read(i); }}>读第 {i + 1} 处 · {sourceName(v.sourceRef)}</Button>)}
    {failed && <p className="pw-muted" role="status">这段登记原文未能核对。未展示旧引文，也未更改你的选择。</p>}
    {page && <section aria-label="已核对的原文片段"><p className="pw-muted">第 {page.index + 1} 处 · {sourceName(spans[page.index].sourceRef)}修订 {spans[page.index].sourceRef.revision}。只核对原文范围，不证明记忆为真。</p>
      <blockquote className="pw-reading-copy">{page.text}</blockquote>
      <details><summary>核对来源位置</summary><p className="pw-muted" style={{ overflowWrap: 'anywhere' }}>{sourceName(spans[page.index].sourceRef)}：{spans[page.index].sourceRef.sourceId}<br />原文起点 {spans[page.index].offset}，长度 {spans[page.index].chars}（UTF-16）。</p></details>
      {page.previous.length > 0 && <Button variant="ghost" disabled={busy} onClick={() => { void read(page.index, page.previous.at(-1), page.previous.slice(0, -1)); }}>上一段</Button>}
      {page.more && <Button variant="ghost" disabled={busy} onClick={() => { void read(page.index, page.next, [...page.previous, page.from]); }}>继续读这处引文</Button>}</section>}
  </section>;
}
