import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { bridge } from './bridge';

type Message = { id?: string; role: string; at?: number; content: string };
type Reading = { top: number; index: number; offset: number; anchor: string; observed: string; following: boolean; unread: boolean };
type ContentMarker = { count: number; lastId: string; content: string | undefined; stream: string };
type NativeReading = { key?: string; epoch?: number; conversationId?: string; scrollIndex?: number; scrollOffset?: number; anchorMessage?: string; observedMessage?: string; newReplies?: boolean };
type Options = {
  scroll: RefObject<HTMLDivElement | null>;
  active: boolean;
  conversationId: string;
  composer: NativeReading;
  messages: Message[];
  streamMarker: string;
  paused: boolean;
};
const atEnd = (node: HTMLElement) => node.scrollHeight - node.clientHeight - node.scrollTop <= 4;
const messageId = (message?: Message, index = 0) => message ? message.id || `${message.role}:${message.at}:${index}` : '';
const rows = (node: HTMLElement) => Array.from(node.querySelectorAll<HTMLElement>('[data-message-index]'));
const contentTop = (node: HTMLElement, row: HTMLElement) => row.getBoundingClientRect().top - node.getBoundingClientRect().top - node.clientTop + node.scrollTop;
// Messages are vertically ordered. Finding the first row below the viewport top
// must not measure every preceding answer on each scroll event in a long thread.
function readingRow(items: HTMLElement[], viewportTop: number) {
  let start = 0, end = items.length;
  while (start < end) {
    const middle = start + Math.floor((end - start) / 2);
    if (items[middle].getBoundingClientRect().bottom > viewportTop) end = middle;
    else start = middle + 1;
  }
  return items[Math.min(start, items.length - 1)];
}

/** Preserve user intent before content grows. Measuring the new bottom gap after
 * a long reply renders incorrectly treats that growth as the user scrolling up.
 * The native reading anchor is also restored after asynchronous history arrives. */
export function useConversationReading({ scroll, active, conversationId, composer, messages, streamMarker, paused }: Options) {
  const content = useRef<HTMLDivElement>(null), positions = useRef(new Map<string, Reading>());
  const window = useRef<{ key: string; draftKey: string; conversationId: string; ready: boolean; reading: Reading; marker: ContentMarker | null } | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const messageRows = useRef<{ node: HTMLElement; items: HTMLElement[] } | null>(null);
  // Article identity can change on a React commit, but scrolling and resizing do
  // not replace those nodes. Share one collection until the next committed layout.
  useLayoutEffect(() => { messageRows.current = null; });
  const currentRows = useCallback((node: HTMLElement) => {
    if (messageRows.current?.node === node) return messageRows.current.items;
    const items = rows(node);
    messageRows.current = { node, items };
    return items;
  }, []);
  const [hasNewReplies, setHasNewReplies] = useState(false);
  const key = conversationId || composer.key || '';
  const last = messages.at(-1), lastId = messageId(last, messages.length - 1);
  const lastContent = last?.content;
  const latest = useRef({ lastId, paused });
  latest.current = { lastId, paused };

  const rememberChatPosition = useCallback(() => {
    const current = window.current, node = scroll.current;
    if (!current?.ready || !node?.isConnected || node.dataset.conversationId !== current.conversationId) return;
    const items = currentRows(node), viewportTop = node.getBoundingClientRect().top + node.clientTop;
    const row = readingRow(items, viewportTop);
    const index = Number(row?.dataset.messageIndex || 0);
    current.reading = { ...current.reading, top: node.scrollTop, index,
      offset: row ? Math.max(0, Math.round(viewportTop - row.getBoundingClientRect().top)) : 0,
      anchor: row?.dataset.messageId || '', observed: latest.current.lastId };
    positions.current.set(current.key, { ...current.reading });
  }, [scroll, currentRows]);

  const persistReading = useCallback(() => {
    const current = window.current;
    if (!current?.ready || !current.draftKey) return;
    const { index, offset, anchor, observed, unread } = current.reading;
    const args = { key: current.draftKey, index, offset, anchor, observed, unread };
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      saveTimer.current = null;
      if (bridge.getState().capabilities.commands.includes('composer.reading')) {
        // Navigation does not retarget this captured draft. Native validates it.
        void bridge.command('composer.reading', args).catch(() => {});
      }
    }, 180);
  }, []);

  const saveReading = useCallback(() => {
    const current = window.current, node = scroll.current;
    if (!current?.ready || !node?.isConnected || node.dataset.conversationId !== current.conversationId) return;
    current.reading.following = atEnd(node);
    if (current.reading.following && !latest.current.paused) {
      current.reading.unread = false;
      setHasNewReplies(false);
    }
    rememberChatPosition();
    persistReading();
  }, [scroll, rememberChatPosition, persistReading]);

  const goToLatest = useCallback(() => {
    const current = window.current, node = scroll.current;
    if (!current?.ready || !node?.isConnected) return;
    current.reading.following = true;
    current.reading.unread = false;
    // Streaming must not race a smooth scroll's intermediate scroll events.
    node.scrollTop = node.scrollHeight;
    setHasNewReplies(false);
    rememberChatPosition();
    persistReading();
  }, [scroll, rememberChatPosition, persistReading]);

  const stopFollowing = useCallback(() => {
    if (window.current) window.current.reading.following = false;
  }, []);

  useLayoutEffect(() => {
    if (!active || !key) { window.current = null; return; }
    const saved = positions.current.get(key);
    const belongs = composer.conversationId === conversationId;
    const hasAnchor = belongs && !!(composer.anchorMessage || composer.observedMessage || composer.scrollIndex || composer.scrollOffset);
    const reading = saved ? { ...saved } : { top: 0, index: belongs ? Number(composer.scrollIndex || 0) : 0,
      offset: belongs ? Number(composer.scrollOffset || 0) : 0, anchor: belongs ? String(composer.anchorMessage || '') : '',
      observed: belongs ? String(composer.observedMessage || '') : '', following: !hasAnchor, unread: belongs && composer.newReplies === true };
    window.current = { key, draftKey: String(composer.key || ''), conversationId, ready: false, reading, marker: null };
    setHasNewReplies(reading.unread);
    return () => { window.current = null; };
  }, [active, key, composer.key, composer.epoch, conversationId]);

  useLayoutEffect(() => {
    const current = window.current, node = scroll.current;
    if (!current || !node?.isConnected || node.dataset.conversationId !== current.conversationId) return;
    // Reopening the same conversation can also publish an empty loading snapshot.
    // Keep the original anchor until real history arrives, even after initialization.
    if (conversationId && messages.length === 0) { current.ready = false; return; }
    const marker: ContentMarker = { count: messages.length, lastId, content: lastContent, stream: streamMarker };
    if (!current.ready) {
      const reading = current.reading;
      if (reading.following) node.scrollTop = node.scrollHeight;
      else {
        const items = currentRows(node), anchor = items.find(row => row.dataset.messageId === reading.anchor);
        const row = anchor || items[Math.min(Math.max(0, reading.index), Math.max(0, items.length - 1))];
        node.scrollTop = row ? contentTop(node, row) + reading.offset : reading.top;
        reading.unread ||= !!reading.observed && reading.observed !== lastId;
        reading.following = atEnd(node) && !paused;
        if (reading.following) reading.unread = false;
      }
      current.ready = true;
      current.marker = marker;
      setHasNewReplies(reading.unread);
      rememberChatPosition();
      return;
    }
    const before = current.marker;
    const changed = !before || before.count !== marker.count || before.lastId !== marker.lastId ||
      before.content !== marker.content || before.stream !== marker.stream;
    current.marker = marker;
    if (current.reading.following && !paused) {
      node.scrollTop = node.scrollHeight;
      current.reading.unread = false;
      setHasNewReplies(false);
    } else if (changed) {
      current.reading.following = false;
      current.reading.unread = true;
      setHasNewReplies(true);
    }
    rememberChatPosition();
  }, [active, key, composer.key, composer.epoch, conversationId, lastContent, paused, messages.length, lastId, streamMarker, scroll, currentRows, rememberChatPosition]);

  useEffect(() => {
    const node = scroll.current, body = content.current;
    if (!active || !node || !body || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => {
      const current = window.current;
      if (!current?.ready || !node.isConnected || node.dataset.conversationId !== current.conversationId) return;
      // This also covers images, Markdown layout and the input/keyboard resizing.
      if (current.reading.following && !latest.current.paused) {
        node.scrollTop = node.scrollHeight;
        rememberChatPosition();
      }
    });
    observer.observe(node);
    observer.observe(body);
    return () => observer.disconnect();
  }, [active, key, scroll, rememberChatPosition]);

  useEffect(() => () => { if (saveTimer.current) clearTimeout(saveTimer.current); }, []);
  return { content, hasNewReplies, saveReading, rememberChatPosition, goToLatest, stopFollowing };
}
