import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion, useIsPresent, useReducedMotion } from 'motion/react';
import { motionTransition } from './library/motion-tokens';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { CommandMenu, type WorkCommand } from './shell-library/CommandMenu';
import { IconChat, IconFile, IconFolder, IconLayers } from './icons';
import './work-search.css';

export type WorkSearchKind = 'all' | 'conversation' | 'work' | 'artifact' | 'project';
export type WorkSearchItem = { kind: Exclude<WorkSearchKind, 'all'>; id: string; title: string; excerpt?: string; updatedAt?: number; status?: string; conversationId?: string; projectId?: string; taskId?: string; artifactId?: string; sha256?: string; recordId?: string; ownerLocator?: Record<string, unknown>; readRange?: { offset?: number; chars?: number }; completedRun?: unknown };
export type WorkSearchConversation = { conversationId: string; composerEpoch: number };
type SearchReturnAnchor = { scope: string; item: string; version: string; inset: number };
export type WorkSearchMemory = { query?: string; kind?: WorkSearchKind; selectedId?: string; scrollTop?: number; pages?: number;
  conversationPage?: { query: string; from: string; reply: SearchReply }; returnAnchor?: SearchReturnAnchor };
type SearchReply = { items: WorkSearchItem[]; nextCursor?: string; notices: string[] };
const kinds = { conversation: '对话', work: '工作', artifact: '成果', project: '项目' };
const scopeHints = { all: '查找保存的工作、对话内容、成果和项目', conversation: '查找对话标题与保存的原文', work: '查找工作目标与摘要', artifact: '查找文件名与文件信息', project: '查找已保存的项目名称' };
const filters: { id: WorkSearchKind; label: string }[] = [{ id: 'all', label: '全部' }, { id: 'work', label: '工作' }, { id: 'conversation', label: '对话' }, { id: 'artifact', label: '成果' }, { id: 'project', label: '项目' }];
const icons = { conversation: IconChat, work: IconLayers, artifact: IconFile, project: IconFolder };
const itemKey = (item: WorkSearchItem) => `${item.kind}:${item.id}:${item.sha256 || ''}`;
// Stable result IDs alone do not distinguish changed originals in the same conversation.
function conversationHitVersion(item: WorkSearchItem) {
  const { conversationId, recordId, ownerLocator: locator } = item;
  if (item.kind !== 'conversation' || typeof conversationId !== 'string' || !conversationId) return;
  if (locator === undefined && recordId === undefined && item.id === conversationId) return JSON.stringify(['title', conversationId]);
  if (!locator || typeof recordId !== 'string' || !recordId || locator.conversationId !== conversationId || locator.recordId !== recordId) return;
  const { kind, generation, recordVersion, fingerprint } = locator;
  if (typeof generation !== 'string' || !generation || typeof recordVersion !== 'number' ||
    !Number.isSafeInteger(recordVersion) || recordVersion < 1 || typeof fingerprint !== 'string') return;
  if (!(kind === 'owner_native_original' && /^sha256:[0-9a-f]{64}$/.test(fingerprint) ||
    kind === 'owner_imported_original' && /^[0-9a-f]{64}$/.test(fingerprint))) return;
  return JSON.stringify([kind, generation, conversationId, recordId, recordVersion, fingerprint]);
}
// CommandMenu renders one option per remote result, in the supplied order.
const resultOption = (list: HTMLElement, items: WorkSearchItem[], key: string, version: string) =>
  list.querySelectorAll<HTMLElement>('[role="option"]')[items.findIndex(item => itemKey(item) === key && conversationHitVersion(item) === version)];
type ListPosition = { list: HTMLElement; top: number };
const listMoved = (list: HTMLElement, previous: ListPosition | null) =>
  previous?.list === list && Math.abs(list.scrollTop - Math.min(previous.top, Math.max(0, list.scrollHeight - list.clientHeight))) > 1;
const scopeLabel = (kind: WorkSearchKind) => kind === 'all' ? '工作、对话、成果与项目' : kinds[kind];
const record = (value: unknown): Record<string, any> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : {};
const searchIdentity = (value: unknown, maximum: number): value is string =>
  typeof value === 'string' && !!value && value.length <= maximum && value === value.trim() && !value.includes('\0');
export function completedWorkSearchMatch(item: WorkSearchItem) {
  const { runId, epoch, report } = record(item.completedRun);
  if (item.kind !== 'work' || item.status !== 'completed' || item.taskId !== item.id ||
    !searchIdentity(runId, 80) || typeof epoch !== 'number' || !Number.isSafeInteger(epoch) || epoch < 1 || epoch > 0x7fffffff) return;
  const { reportMessageId, deliveryId } = record(report);
  const reportTarget = searchIdentity(item.id, 80) && searchIdentity(item.conversationId, 512) &&
    searchIdentity(reportMessageId, 512) && searchIdentity(deliveryId, 512)
    ? { taskId: item.id, runId, epoch, reportMessageId, conversationId: item.conversationId, deliveryId } : undefined;
  return { runId, epoch, report: reportTarget };
}
// Keep the matching passage visible even when a small screen clips the native excerpt.
function passagePreview(excerpt: string, query: string) {
  const body = excerpt.replace(/\s+/gu, ' ').trim();
  const needle = query.replace(/\s+/gu, ' ').trim();
  if (!needle) return body;
  const at = body.toLowerCase().indexOf(needle.toLowerCase());
  if (at < 0) return body;
  const before = Array.from(body.slice(0, at));
  return before.length > 12 ? '…' + before.slice(-12).join('') + body.slice(at) : body;
}
const hitDate = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

function readReply(value: unknown, kind: WorkSearchKind, query: string, cursor: string, prior: readonly string[], conversation?: WorkSearchConversation): SearchReply {
  const data = record(value);
  if (data.kind !== kind || data.query !== query || data.executionAuthorized !== false || !Array.isArray(data.items) || data.items.length > 40 || data.items.some((item: any) =>
    !item || !Object.hasOwn(kinds, item.kind) || kind !== 'all' && item.kind !== kind || typeof item.id !== 'string' || !item.id || typeof item.title !== 'string'))
    throw new Error('搜索结果的范围已变化，请重新搜索。');
  if (conversation && (data.coverage !== 'current_conversation_native_originals' || data.modelCalled !== false ||
    !matchesWorkSearchConversation(data.conversationScope, conversation) ||
    data.items.some((item: WorkSearchItem) => item.conversationId !== conversation.conversationId ||
      !item.recordId || !item.ownerLocator || item.ownerLocator.kind !== 'owner_native_original' ||
      item.ownerLocator.conversationId !== conversation.conversationId || item.ownerLocator.recordId !== item.recordId ||
      !Number.isSafeInteger(item.ownerLocator.recordVersion) || Number(item.ownerLocator.recordVersion) < 1 ||
      !/^sha256:[0-9a-f]{64}$/.test(String(item.ownerLocator.fingerprint || '')))))
    throw new Error('没有核对到此对话的本机原文，未显示其他记录。');
  if (typeof data.nextCursor !== 'string' || data.nextCursor.length > 16000 || data.nextCursor && (data.nextCursor === cursor || prior.includes(data.nextCursor)))
    throw new Error('搜索位置没有继续前进，请重新搜索。');
  return { items: data.items, nextCursor: data.nextCursor, notices: Array.isArray(data.notices) ? data.notices.filter((notice: unknown) => typeof notice === 'string') : [] };
}
export function matchesWorkSearchConversation(value: unknown, expected: WorkSearchConversation): boolean {
  const scope = record(value);
  return scope.conversationId === expected.conversationId && scope.composerEpoch === expected.composerEpoch;
}

/** The retained Affect/BeUI search surface reads only the selected native stores.
 * Query, category and return position are presentation memory, never authority. */
export function WorkSearch({ supported, identity, memory, onOpen, onClose, labelStatus, conversation, onFindConversation, onFindAll }: {
  supported: boolean; identity: string; memory: WorkSearchMemory;
  onOpen: (item: WorkSearchItem, stillCurrent: () => boolean, armReturn?: () => void) => void | Promise<void>;
  onClose: () => void; labelStatus: (status: string) => string;
  conversation?: WorkSearchConversation; onFindConversation?: () => void; onFindAll?: () => void;
}) {
  const present = useIsPresent(), reduce = useReducedMotion();
  const [query, setQuery] = useState(memory.query || ''), [kind, setKind] = useState<WorkSearchKind>(conversation ? 'conversation' : memory.kind || 'all');
  const [reply, setReply] = useState<SearchReply | null>(null), [replyScope, setReplyScope] = useState('');
  const [loading, setLoading] = useState(false), [opening, setOpening] = useState(false), [error, setError] = useState(''), [attempt, setAttempt] = useState(0);
  const [openFailure, setOpenFailure] = useState<{ scope: string; itemKey: string; message: string } | null>(null);
  const root = useRef<HTMLDivElement>(null), request = useRef(0), alive = useRef(false), restored = useRef(false), pending = useRef(false);
  const pages = useRef(0), cursors = useRef<string[]>([]), returnPosition = useRef(memory.scrollTop || 0);
  const explicitlyTyped = useRef(false);
  const current = useRef({ query, kind, identity }); current.current = { query, kind, identity };
  const scope = JSON.stringify([query, kind, identity]);
  const [returning, setReturning] = useState(() => memory.returnAnchor?.scope === scope ? memory.returnAnchor : undefined);
  const interruptedReturn = useRef('');
  const listPosition = useRef<ListPosition | null>(null);
  const openingRef = useRef(false), openingRequest = useRef(0);
  const active = useRef(present); active.current = present;
  const read = useCallback(async (cursor = '') => {
    if (!active.current || !supported || pending.current || conversation && !current.current.query.trim()) return;
    const owner = ++request.current, expected = { ...current.current }, epoch = bridge.getDocumentEpoch();
    const expectedScope = JSON.stringify([expected.query, expected.kind, expected.identity]);
    pending.current = true; setLoading(true); setError('');
    const accepts = () => active.current && alive.current && owner === request.current && expected.query === current.current.query &&
      expected.kind === current.current.kind && expected.identity === current.current.identity && epoch === bridge.getDocumentEpoch() && !document.hidden;
    try {
      const count = cursor || conversation ? 1 : Math.max(1, memory.pages || 1);
      let nextCursor = cursor;
      for (let page = 0; page < count; page++) {
        const append = !!nextCursor && !conversation;
        const next = readReply(await bridge.command('work.search', { query: expected.query.trim(), kind: expected.kind, limit: 30,
          ...(nextCursor ? { cursor: nextCursor } : {}), ...(conversation ? { conversationScope: conversation } : {}) }),
          expected.kind, expected.query.trim(), nextCursor, cursors.current, conversation);
        if (!accepts()) return;
        if (conversation) {
          // This visit keeps only the one displayed snippet page, never full originals or an unbounded history.
          memory.conversationPage = { query: expected.query, from: nextCursor, reply: next };
          if (nextCursor) { restored.current = false; returnPosition.current = 0; memory.scrollTop = 0; memory.selectedId = undefined; }
        }
        pages.current = append ? pages.current + 1 : 1;
        // An interrupted return should not replace the previously loaded depth with its first page.
        memory.pages = next.nextCursor && !conversation ? Math.max(memory.pages || 1, pages.current) : pages.current;
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
  }, [supported, memory, conversation]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; openingRequest.current++; pending.current = false; }; }, []);
  useEffect(() => {
    const anchor = memory.returnAnchor?.scope === scope ? memory.returnAnchor : undefined;
    if (!anchor) memory.returnAnchor = undefined;
    else interruptedReturn.current = '';
    request.current++; pending.current = false; pages.current = 0; cursors.current = []; restored.current = interruptedReturn.current === scope;
    openingRequest.current++; openingRef.current = false; setOpening(false); setOpenFailure(null);
    returnPosition.current = memory.scrollTop || 0; setReply(null); setError('');
    setReturning(anchor);
    if (conversation) {
      const retained = memory.conversationPage;
      if (supported && attempt === 0 && !explicitlyTyped.current && retained?.query === query) {
        cursors.current = [retained.from]; setReply(retained.reply); setReplyScope(scope); setLoading(false); return;
      }
      if (!supported || !query.trim() || (!explicitlyTyped.current && attempt === 0)) { setLoading(false); return; }
    }
    setLoading(supported);
    const timer = window.setTimeout(() => { void read(); }, query.trim() ? 220 : 0);
    return () => window.clearTimeout(timer);
  }, [query, kind, identity, attempt, supported, read, conversation, memory, scope]);
  useEffect(() => {
    const visibility = () => {
      if (document.hidden) { request.current++; openingRequest.current++; openingRef.current = false; pending.current = false; setOpening(false); setOpenFailure(null); setLoading(false); setReply(null); }
      else if (!conversation) setAttempt(value => value + 1);
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, [conversation]);
  const wasExiting = useRef(false);
  useEffect(() => {
    if (present) {
      if (wasExiting.current) { wasExiting.current = false; if (!conversation) setAttempt(value => value + 1); }
      return;
    }
    wasExiting.current = true;
    request.current++; openingRequest.current++; pending.current = false; openingRef.current = false;
    const focused = document.activeElement;
    if (focused instanceof HTMLElement && root.current?.contains(focused)) focused.blur();
  }, [present, conversation]);
  const focusSearch = () => {
    if (!active.current || document.hidden) return;
    // The panel has reached its readable width before the IME takes space.
    root.current?.querySelector<HTMLInputElement>('input[role="combobox"]')?.focus({ preventScroll: true });
  };
  const visibleReply = replyScope === scope && (!conversation || supported) ? reply : null;
  const yieldReturnToUser = useCallback(() => {
    const anchor = memory.returnAnchor;
    if (!anchor || anchor.scope !== scope) return;
    memory.returnAnchor = undefined;
    if (memory.selectedId === anchor.item) memory.selectedId = undefined;
    interruptedReturn.current = scope;
    restored.current = true;
    setReturning(undefined);
  }, [memory, scope]);
  useEffect(() => {
    const list = root.current?.querySelector<HTMLElement>('.afa-commandlist');
    if (!list) return;
    listPosition.current = { list, top: list.scrollTop };
    const remember = () => {
      if (active.current && !openingRef.current && listMoved(list, listPosition.current)) yieldReturnToUser();
      listPosition.current = { list, top: list.scrollTop };
      if (restored.current && active.current && !openingRef.current) memory.scrollTop = list.scrollTop;
    };
    list.addEventListener('scroll', remember, { passive: true });
    return () => { list.removeEventListener('scroll', remember); };
  }, [memory, yieldReturnToUser]);
  useEffect(() => {
    if (!visibleReply || loading || restored.current) return;
    const frame = requestAnimationFrame(() => {
      const list = root.current?.querySelector<HTMLElement>('.afa-commandlist');
      if (!list || !active.current || document.hidden || restored.current || returning !== memory.returnAnchor) return;
      // An accessibility or inertial scroll may precede its DOM scroll event.
      // A shorter result list merely clamps the previous offset and doesn't cancel return.
      if (returning && listMoved(list, listPosition.current)) {
        yieldReturnToUser(); memory.scrollTop = list.scrollTop; return;
      }
      const row = returning && resultOption(list, visibleReply.items, returning.item, returning.version);
      if (returning && !row && memory.selectedId === returning.item) memory.selectedId = undefined;
      restored.current = true;
      if (returning) {
        memory.returnAnchor = undefined;
        setReturning(undefined);
      }
      if (returning && row) {
        const inset = Math.max(1 - row.offsetHeight, Math.min(returning.inset, list.clientHeight - 1));
        list.scrollTop += row.getBoundingClientRect().top - list.getBoundingClientRect().top - list.clientTop - inset;
      } else {
        list.scrollTop = returnPosition.current;
      }
      memory.scrollTop = list.scrollTop;
      listPosition.current = { list, top: list.scrollTop };
    });
    return () => cancelAnimationFrame(frame);
  }, [visibleReply, loading, memory, returning, yieldReturnToUser]);
  const resetPosition = () => {
    request.current++; pending.current = false; memory.selectedId = undefined; memory.returnAnchor = undefined;
    interruptedReturn.current = ''; setReturning(undefined); memory.scrollTop = 0; memory.pages = 1; restored.current = false;
  };
  const changeQuery = (value: string) => {
    if (openingRef.current) return;
    if (conversation) { explicitlyTyped.current = true; memory.conversationPage = undefined; }
    resetPosition(); memory.query = value; setQuery(value);
  };
  const changeKind = (value: WorkSearchKind) => { if (value === kind || openingRef.current) return; resetPosition(); memory.kind = value; setKind(value); };
  const openItem = useCallback((item: WorkSearchItem) => {
    if (openingRef.current) return;
    const owner = ++openingRequest.current, expected = { ...current.current }, epoch = bridge.getDocumentEpoch();
    const stillCurrent = () => active.current && alive.current && owner === openingRequest.current && openingRef.current
      && expected.query === current.current.query && expected.kind === current.current.kind && expected.identity === current.current.identity
      && epoch === bridge.getDocumentEpoch() && !document.hidden;
    const key = itemKey(item), list = root.current?.querySelector<HTMLElement>('.afa-commandlist');
    const version = !conversation && conversationHitVersion(item);
    const row = list && visibleReply && version ? resultOption(list, visibleReply.items, key, version) : undefined;
    const inset = row && list ? row.getBoundingClientRect().top - list.getBoundingClientRect().top - list.clientTop : undefined;
    const anchor = version && inset !== undefined && list && row && inset < list.clientHeight && inset + row.offsetHeight > 0
      ? { scope, item: key, version, inset } : undefined;
    const scrollTop = list?.scrollTop ?? memory.scrollTop;
    memory.scrollTop = scrollTop;
    // The host calls this only when explicitly returning from the accepted match.
    const armReturn = anchor ? () => {
      if ((memory.query || '') !== expected.query || (memory.kind || 'all') !== expected.kind) return;
      memory.scrollTop = scrollTop;
      memory.selectedId = key;
      memory.returnAnchor = anchor;
    } : undefined;
    openingRef.current = true; setOpening(true); setOpenFailure(null); memory.selectedId = key;
    void Promise.resolve().then(() => { if (stillCurrent()) return onOpen(item, stillCurrent, armReturn); }).catch(failure => {
      if (stillCurrent()) setOpenFailure({ scope, itemKey: key,
        message: failure instanceof Error ? failure.message : '尚未打开这条结果，请重试。' });
    }).finally(() => { if (owner === openingRequest.current) { openingRef.current = false; if (alive.current) setOpening(false); } });
  }, [onOpen, memory, scope, visibleReply, conversation]);
  const commands = useMemo<WorkCommand[]>(() => (visibleReply?.items || []).map(item => {
    const passage = item.kind === 'conversation' && !!item.recordId;
    const preview = passagePreview(item.excerpt || '', query);
    const timestamp = new Date(item.updatedAt || 0);
    const date = item.updatedAt && Number.isFinite(timestamp.getTime()) ? hitDate.format(timestamp) : '';
    return {
      id: itemKey(item), group: '', title: (passage && preview) || item.title || '未命名', icon: icons[item.kind],
      meta: [passage ? '对话原文' : kinds[item.kind], item.status ? labelStatus(item.status) : '', date].filter(Boolean).join(' · '),
      description: passage ? undefined : preview,
      onSelect: () => openItem(item),
    };
  }), [visibleReply, query, labelStatus, openItem]);
  const failedSelection = openFailure?.scope === scope ? openFailure : null;
  const retryItem = failedSelection && visibleReply?.items.find(item => itemKey(item) === failedSelection.itemKey);
  const shown = visibleReply?.items.length || 0;
  const footerSummary = conversation ? '仅查找此对话里已保存的本机消息。' : shown
    ? `已显示 ${shown} 条${query.trim() ? '匹配结果' : '记录'}`
    : scopeHints[kind];
  return <motion.div className="pw-work-search" ref={root} inert={!present} aria-hidden={!present || undefined}
    onPointerDownCapture={yieldReturnToUser} onWheelCapture={yieldReturnToUser}
    onKeyDownCapture={event => {
      if (!event.nativeEvent.isComposing && ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', 'Enter', 'Tab', ' '].includes(event.key)) yieldReturnToUser();
    }}
    initial={reduce ? false : { opacity: 0, clipPath: 'inset(0 0 calc(100% - 48px) 0 round 20px)' }}
    animate={{ opacity: 1, clipPath: 'inset(0 0 0 0 round 20px)' }}
    exit={{ opacity: 0, clipPath: 'inset(0 0 calc(100% - 48px) 0 round 20px)' }}
    transition={reduce ? motionTransition.instant : present ? motionTransition.enter : motionTransition.exit}
    onAnimationComplete={() => { if (present) focusSearch(); }}>

    <CommandMenu autoFocusInput={!!reduce} animateReveal={false} scrollActive={conversation ? 'auto' : 'keyboard'} commands={commands} query={query}
      queryLabel={conversation ? '查找此对话的本机原文' : undefined} clearQueryLabel="清除搜索词" closeOnFooterEscape queryReadOnly={opening} onQueryChange={changeQuery} scopeKey={kind} remote loading={loading || opening}
      closeOnSelect={false} onClose={() => { openingRequest.current++; openingRef.current = false; onClose(); }} selectedId={memory.returnAnchor?.scope === scope ? undefined : memory.selectedId}
      controls={<div className="pw-search-kind-filters" role="group" aria-label="查找对象">{conversation ?
        <Button variant="ghost" disabled={opening} onClick={onFindAll}>返回全部记录搜索</Button> : <>
        {onFindConversation && <Button variant="ghost" disabled={opening} onClick={onFindConversation}>查找此对话</Button>}
        {filters.map(option => <Button key={option.id} variant="ghost" aria-pressed={kind === option.id} disabled={opening} onClick={() => changeKind(option.id)}>{option.label}</Button>)}
      </>}</div>}
      emptyMessage={visibleReply?.nextCursor ? '当前已读取范围内没有命中，可以继续查找更早的记录。' : visibleReply?.notices.length ? '已读取范围内没有命中；还有部分记录尚未读全，请查看下方说明。' : conversation ? query.trim() ? '此对话已读取的本机消息没有匹配。' : '输入要在此对话里找的文字。' : query.trim() ? `没有找到匹配的${scopeLabel(kind)}。试试目标中的关键词或文件名。` : `还没有可显示的${scopeLabel(kind)}记录。`}
      status={!supported ? <p className="pw-search-status" role="status">{conversation ? '原对话已变化，未查找其他记录。' : '当前版本尚未提供工作搜索。'}</p> : opening ? <p className="pw-search-status" role="status">正在打开原记录…</p> : failedSelection ? <div className="pw-search-status" role="alert"><p>{failedSelection.message}</p>{retryItem && <Button variant="secondary" disabled={loading} onClick={() => openItem(retryItem)}>重试打开原记录</Button>}</div> : error ? <div className="pw-search-status" role="alert"><p>{error}</p><Button variant="secondary" onClick={() => { if (visibleReply?.nextCursor) void read(visibleReply.nextCursor); else setAttempt(value => value + 1); }}>重新读取</Button></div> : loading ? <p className="pw-search-status" role="status">{visibleReply ? '正在读取更多结果…' : conversation ? '正在查找此对话…' : `正在搜索${scopeLabel(kind)}…`}</p> : undefined}
      footer={<><p>{footerSummary}</p>
        {visibleReply?.nextCursor && <Button variant="ghost" disabled={loading || opening} onClick={() => void read(visibleReply.nextCursor)}>{conversation ? '查找更早的消息' : visibleReply.items.length ? '查看更多结果' : '继续查找'}</Button>}
        {visibleReply?.notices.map((notice, index) => <p className="pw-search-notice" key={index}>{notice}</p>)}</>} />
  </motion.div>;
}
