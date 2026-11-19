import { useLayoutEffect, useMemo, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';

type Row = Record<string, any>;
type Resolved = { decision: 'replace' | 'coexist'; selectedId: string; reason: string; at: number; refs: Map<string, number>; key: string };
const id = (v: unknown): v is string => typeof v === 'string' && v.length > 0;
const revision = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0;
export const cognitionWindow = () => { const s = bridge.getState().state; return JSON.stringify([bridge.getDocumentEpoch(), s.memoryRefreshRevision, s.traceSourceRevision]); };
const rows = (v: any) => Array.isArray(v) ? v as Row[] : [];

function resolution(v: Row | undefined): Resolved | null {
  if (!v || !['replace', 'coexist'].includes(v.decision) || typeof v.selectedId !== 'string' || !id(v.reason) || !v.reason.trim() || v.reason.length > 1000 ||
      !revision(v.at) || v.at > 8.64e15 || !Array.isArray(v.records) || v.records.length < 1 || v.records.length > 20) return null;
  const refs = new Map<string, number>();
  for (const r of v.records) { if (!r || !id(r.id) || !revision(r.revision) || refs.has(r.id)) return null; refs.set(r.id, r.revision); }
  if (v.decision === 'replace' ? !refs.has(v.selectedId) : v.selectedId !== '' || refs.size < 2) return null;
  return { decision: v.decision, selectedId: v.selectedId, reason: v.reason, at: v.at, refs,
    key: JSON.stringify([v.decision, v.selectedId, v.reason, v.at, [...refs].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)]) };
}

// Account only for the already displayed private projection. Never look up a missing peer or copy its text.
function inThisRead(row: Row, view: Row | null) {
  const stamp = resolution(row.assertionResolution), a = row.assertion;
  if (!stamp || !id(row.id) || !revision(row.revision) || !id(row.text) || !id(row.assertionStatus) || !a || !id(a.subjectId) || !id(a.predicate) || typeof a.condition !== 'string' || stamp.refs.get(row.id) !== row.revision ||
      view?.format !== 'pocketworld.memory-cognition-view.v1' || ![view.current, view.history, view.pendingConflicts].every(Array.isArray) || view.executionAuthorized !== false || view.subjectId !== a.subjectId ||
      view.scope?.ownerId !== 'owner' || view.scope?.spaceId !== 'private' || row.ownerId !== 'owner' || row.spaceId !== 'private' ||
      !revision(view.conflictReadExpiresAt) || !revision(view.generatedAt) || stamp.at > view.generatedAt || view.conflictReadExpiresAt <= view.generatedAt ||
      rows(view.pendingConflicts).some(peer => peer.ownerId === row.ownerId && peer.spaceId === row.spaceId && peer.assertion &&
        ['subjectId', 'predicate', 'condition'].every(k => peer.assertion[k] === a[k]))) return null;
  const members = [...rows(view.current), ...rows(view.history)], peers = new Map<string, Row>();
  let deadline = view.conflictReadExpiresAt;
  for (const peer of members) {
    if (!stamp.refs.has(peer.id)) continue;
    if (peers.has(peer.id) || peer.revision !== stamp.refs.get(peer.id) || peer.ownerId !== row.ownerId || peer.spaceId !== row.spaceId ||
        !peer.assertion || !['subjectId', 'predicate', 'condition'].every(k => peer.assertion[k] === a[k]) ||
        resolution(peer.assertionResolution)?.key !== stamp.key) return null;
    if (revision(peer.expiresAt)) deadline = Math.min(deadline, peer.expiresAt);
    peers.set(peer.id, peer);
  }
  return peers.size === stamp.refs.size && peers.get(row.id)?.text === row.text && peers.get(row.id)?.assertionStatus === row.assertionStatus
    ? { stamp, deadline } : null;
}

export function MemoryResolutionHistory({ row, cognition, originalWindow }: { row: Row; cognition: Row | null; originalWindow: string }) {
  const checked = useMemo(() => inThisRead(row, cognition), [row, cognition]), until = checked?.deadline || 0;
  const [open, setOpen] = useState(false), [lost, setLost] = useState(false);
  useLayoutEffect(() => {
    setOpen(false); setLost(false);
    const retire = () => { setOpen(false); setLost(true); };
    const verify = () => { if (document.hidden || cognitionWindow() !== originalWindow || Date.now() >= until) retire(); };
    const remaining = until - Date.now(), timer = remaining > 0 ? globalThis.setTimeout(retire, remaining) : undefined;
    verify(); const off = bridge.subscribe(verify); document.addEventListener('visibilitychange', verify); globalThis.addEventListener('pagehide', retire);
    return () => { clearTimeout(timer); off(); document.removeEventListener('visibilitychange', verify); globalThis.removeEventListener('pagehide', retire); };
  }, [row.id, row.revision, originalWindow, until]);
  if (!checked || lost || document.hidden || cognitionWindow() !== originalWindow || Date.now() >= until)
    return <p className="pw-muted">这条有过冲突处理，但这次没有可完整核对的同批版本；不展开旧理由，也不另查其他条目。</p>;
  const { stamp } = checked;
  return <section aria-label="这条记忆以往的冲突决定"><Button variant="ghost" aria-expanded={open} onClick={() => setOpen(v => !v)}>{open ? '收起当时的决定' : '为什么当时保留或替换这一版？'}</Button>
    {open && <div className="pw-source-record"><h3>{stamp.decision === 'coexist' ? `当时允许这 ${stamp.refs.size} 条并存` : stamp.selectedId === row.id ? '当时选择保留这一版' : '当时选择保留同组的另一版'}</h3>
      <p className="pw-muted">{new Date(stamp.at).toLocaleString('zh-CN')} · 当时这条是修订 {stamp.refs.get(row.id)}</p><p className="pw-reading-copy">你当时给的理由：{stamp.reason}</p>
      <p className="pw-muted">这里只说明那次决定，不证明内容为真，也不说明后来哪次回答采用过它。</p></div>}
  </section>;
}
