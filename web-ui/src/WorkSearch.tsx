import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { CommandMenu, type WorkCommand } from './shell-library/CommandMenu';
import { IconChat, IconFile, IconFolder, IconLayers } from './icons';
import './work-search.css';

export type WorkSearchKind = 'all' | 'conversation' | 'work' | 'artifact' | 'project';
export type WorkSearchItem = { kind: Exclude<WorkSearchKind, 'all'>; id: string; title: string; excerpt?: string; updatedAt?: number; status?: string; conversationId?: string; projectId?: string; taskId?: string; artifactId?: string; sha256?: string; recordId?: string; ownerLocator?: Record<string, unknown>; readRange?: { offset?: number; chars?: number } };
export type WorkSearchMemory = { query?: string; kind?: WorkSearchKind; selectedId?: string; scrollTop?: number; pages?: number };
type SearchReply = { items: WorkSearchItem[]; nextCursor?: string; notices: string[] };
const kinds = { conversation: '对话', work: '工作', artifact: '成果', project: '项目' };
const filters: { id: WorkSearchKind; label: string }[] = [{ id: 'all', label: '全部' }, { id: 'work', label: '工作' }, { id: 'conversation', label: '对话' }, { id: 'artifact', label: '成果' }, { id: 'project', label: '项目' }];
const icons = { conversation: IconChat, work: IconLayers, artifact: IconFile, project: IconFolder };
const itemKey = (item: WorkSearchItem) => `${item.kind}:${item.id}:${item.sha256 || ''}`;
const scopeLabel = (kind: WorkSearchKind) => kind === 'all' ? '工作、对话、成果与项目' : kinds[kind];
const record = (value: unknown): Record<string, any> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : {};
function readReply(value: unknown, kind: WorkSearchKind, query: string, cursor: string, prior: readonly string[]): SearchReply {
  const data = record(value);
  if (data.kind !== kind || data.query !== query || data.executionAuthorized !== false || !Array.isArray(data.items) || data.items.length > 40 || data.items.some((item: any) =>
    !item || !Object.hasOwn(kinds, item.kind) || kind !== 'all' && item.kind !== kind || typeof item.id !== 'string' || !item.id || typeof item.title !== 'string'))
    throw new Error('搜索结果的范围已变化，请重新搜索。');
  if (typeof data.nextCursor !== 'string' || data.nextCursor.length > 16000 || data.nextCursor && (data.nextCursor === cursor || prior.includes(data.nextCursor)))
    throw new Error('搜索位置没有继续前进，请重新搜索。');
  return { items: data.items, nextCursor: data.nextCursor, notices: Array.isArray(data.notices) ? data.notices.filter((notice: unknown) => typeof notice === 'string') : [] };
}

/** The retained Affect/BeUI search surface reads only the selected native stores.
 * Query, category and return position are presentation memory, never authority. */
export function WorkSearch({ supported, identity, memory, onOpen, onClose, labelStatus }: {
  supported: boolean; identity: string; memory: WorkSearchMemory; onOpen: (item: WorkSearchItem, stillCurrent: () => boolean) => void | Promise<void>;
  onClose: () => void; labelStatus: (status: string) => string;
}) {
  const [query, setQuery] = useState(memory.query || ''), [kind, setKind] = useState<WorkSearchKind>(memory.kind || 'all');
  const [reply, setReply] = useState<SearchReply | null>(null), [replyScope, setReplyScope] = useState('');
  const [loading, setLoading] = useState(false), [opening, setOpening] = useState(false), [error, setError] = useState(''), [attempt, setAttempt] = useState(0);
  const root = useRef<HTMLDivElement>(null), request = useRef(0), alive = useRef(false), restored = useRef(false), pending = useRef(false);
  const pages = useRef(0), cursors = useRef<string[]>([]), returnPosition = useRef(memory.scrollTop || 0);
  const current = useRef({ query, kind, identity }); current.current = { query, kind, identity };
  const scope = JSON.stringify([query, kind, identity]);
  const openingRef = useRef(false), openingRequest = useRef(0);
  const read = useCallback(async (cursor = '') => {
    if (!supported || pending.current) return;
    const owner = ++request.current, expected = { ...current.current }, epoch = bridge.getDocumentEpoch();
    const expectedScope = JSON.stringify([expected.query, expected.kind, expected.identity]);
    pending.current = true; setLoading(true); setError('');
    const accepts = () => alive.current && owner === request.current && expected.query === current.current.query &&
      expected.kind === current.current.kind && expected.identity === current.current.identity && epoch === bridge.getDocumentEpoch() && !document.hidden;
    try {
      const count = cursor ? 1 : Math.max(1, memory.pages || 1);
      let nextCursor = cursor;
      for (let page = 0; page < count; page++) {
        const append = !!nextCursor;
        const next = readReply(await bridge.command('work.search', { query: expected.query.trim(), kind: expected.kind, limit: 30, ...(nextCursor ? { cursor: nextCursor } : {}) }), expected.kind, expected.query.trim(), nextCursor, cursors.current);
        if (!accepts()) return;
        pages.current = append ? pages.current + 1 : 1; memory.pages = pages.current;
        cursors.current = append ? [...cursors.current, nextCursor] : [nextCursor];
        setReply(previous => append && previous ? { ...next,
          items: [...new Map([...previous.items, ...next.items].map(item => [itemKey(item), item])).values()],
          notices: [...new Set([...previous.notices, ...next.notices])],
        } : next);
        setReplyScope(expectedScope);
        nextCursor = next.nextCursor || ''; if (!nextCursor) break;
      }
    } catch (failure) { if (accepts()) setError(failure instanceof Error ? failure.message : '搜索暂不可用，请重试。'); }
    finally { if (owner === request.current) { pending.current = false; if (alive.current) setLoading(false); } }
  }, [supported, memory]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; openingRequest.current++; pending.current = false; }; }, []);
  useEffect(() => {
    request.current++; pending.current = false; pages.current = 0; cursors.current = []; restored.current = false;
    openingRequest.current++; openingRef.current = false; setOpening(false);
    returnPosition.current = memory.scrollTop || 0; setReply(null); setError(''); setLoading(supported);
    const timer = window.setTimeout(() => { void read(); }, query.trim() ? 220 : 0);
    return () => window.clearTimeout(timer);
  }, [query, kind, identity, attempt, supported, read]);
  useEffect(() => {
    const visibility = () => {
      if (document.hidden) { request.current++; openingRequest.current++; openingRef.current = false; pending.current = false; setOpening(false); setLoading(false); setReply(null); }
      else setAttempt(value => value + 1);
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, []);
  const visibleReply = replyScope === scope ? reply : null;
  useEffect(() => {
    if (!visibleReply || loading || restored.current) return;
    const frame = requestAnimationFrame(() => { const scroller = root.current?.querySelector('.afa-commandlist'); if (scroller) scroller.scrollTop = returnPosition.current; restored.current = true; });
    return () => cancelAnimationFrame(frame);
  }, [visibleReply, loading]);
  useEffect(() => {
    const scroller = root.current?.querySelector('.afa-commandlist');
    const remember = () => { if (scroller && restored.current) memory.scrollTop = scroller.scrollTop; };
    scroller?.addEventListener('scroll', remember, { passive: true });
    return () => { remember(); scroller?.removeEventListener('scroll', remember); };
  }, [memory]);
  const resetPosition = () => { request.current++; pending.current = false; memory.selectedId = undefined; memory.scrollTop = 0; memory.pages = 1; restored.current = false; };
  const changeQuery = (value: string) => { if (openingRef.current) return; resetPosition(); memory.query = value; setQuery(value); };
  const changeKind = (value: WorkSearchKind) => { if (value === kind || openingRef.current) return; resetPosition(); memory.kind = value; setKind(value); };
  const commands = useMemo<WorkCommand[]>(() => (visibleReply?.items || []).map(item => ({
    id: itemKey(item), group: '', title: item.title || '未命名', icon: icons[item.kind],
    meta: [kinds[item.kind], item.status ? labelStatus(item.status) : '', item.updatedAt ? new Date(item.updatedAt).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' }) : ''].filter(Boolean).join(' · '),
    description: item.excerpt,
    onSelect: () => {
      if (openingRef.current) return;
      const owner = ++openingRequest.current, expected = { ...current.current }, epoch = bridge.getDocumentEpoch();
      const stillCurrent = () => alive.current && owner === openingRequest.current && openingRef.current
        && expected.query === current.current.query && expected.kind === current.current.kind && expected.identity === current.current.identity
        && epoch === bridge.getDocumentEpoch() && !document.hidden;
      openingRef.current = true; setOpening(true); memory.selectedId = itemKey(item);
      void Promise.resolve().then(() => { if (stillCurrent()) return onOpen(item, stillCurrent); }).catch(failure => { if (stillCurrent()) setError(failure instanceof Error ? failure.message : '尚未打开这条结果，请重试。'); })
        .finally(() => { if (owner === openingRequest.current) { openingRef.current = false; if (alive.current) setOpening(false); } });
    },
  })), [visibleReply, onOpen, labelStatus, memory]);
  return <div className="pw-work-search" ref={root}>
    <CommandMenu commands={commands} query={query} queryReadOnly={opening} onQueryChange={changeQuery} scopeKey={kind} remote loading={loading || opening}
      closeOnSelect={false} onClose={() => { openingRequest.current++; openingRef.current = false; onClose(); }} selectedId={memory.selectedId}
      controls={<div className="pw-search-kind-filters" role="group" aria-label="查找对象">{filters.map(option => <Button key={option.id} variant="ghost" aria-pressed={kind === option.id} disabled={opening} onClick={() => changeKind(option.id)}>{option.label}</Button>)}</div>}
      emptyMessage={visibleReply?.nextCursor ? '当前已读取范围内没有命中，可以继续查找更早的记录。' : visibleReply?.notices.length ? '已读取范围内没有命中；还有部分记录尚未读全，请查看下方说明。' : query.trim() ? `没有找到匹配的${scopeLabel(kind)}。试试目标中的关键词或文件名。` : `还没有可显示的${scopeLabel(kind)}记录。`}
      status={!supported ? <p className="pw-search-status" role="status">当前版本尚未提供工作搜索。</p> : opening ? <p className="pw-search-status" role="status">正在打开原记录…</p> : error ? <div className="pw-search-status" role="alert"><p>{error}</p><Button variant="secondary" onClick={() => { if (visibleReply?.nextCursor) void read(visibleReply.nextCursor); else setAttempt(value => value + 1); }}>重新读取</Button></div> : loading ? <p className="pw-search-status" role="status">{visibleReply ? '正在读取更多结果…' : `正在搜索${scopeLabel(kind)}…`}</p> : undefined}
      footer={<><p>{kind === 'artifact' ? '查找文件名与文件信息' : kind === 'conversation' ? '查找对话标题与保存的原文' : kind === 'work' ? '查找工作目标与摘要' : kind === 'project' ? '查找已保存的项目名称' : '查找保存的工作、对话内容、成果和项目'}</p>
        {visibleReply?.nextCursor && <Button variant="ghost" disabled={loading || opening} onClick={() => void read(visibleReply.nextCursor)}>{visibleReply.items.length ? '查看更多结果' : '继续查找'}</Button>}
        {visibleReply?.notices.map((notice, index) => <p className="pw-search-notice" key={index}>{notice}</p>)}</>} />
  </div>;
}
