import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { bridge } from './bridge';
import { captureReadingPlace, readingReturnDelta, type ReadingPlace } from './conversation-reading-place';

type Message = { id?: string; role: string; at?: number; content: string };
type Reading = { top: number; index: number; offset: number; anchor: string; observed: string; following: boolean; unread: boolean };
type ContentMarker = { count: number; lastId: string; content: string | undefined; stream: string };
type ReadingWindow = { key: string; draftKey: string; conversationId: string; ready: boolean; reading: Reading; marker: ContentMarker | null; followMessages: boolean };
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
const pageTail = (node: HTMLElement) => node.scrollHeight - node.clientHeight;
const atEnd = (node: HTMLElement) => pageTail(node) - node.scrollTop <= 4;
const awayFromTail = (node: HTMLElement, end = pageTail(node)) => end - node.scrollTop > Math.min(160, Math.max(72, node.clientHeight / 4));
/** The existing expanded progress group must be the only visible section after the actual messages. */
function messageTail(node: HTMLElement, list: HTMLElement | null) {
  if (!list?.lastElementChild || !node.contains(list)) return null;
  let quietWork = false;
  for (let section = list.nextElementSibling; section; section = section.nextElementSibling) {
    if (section instanceof HTMLElement && section.hidden || !section.childElementCount && section.getBoundingClientRect().height === 0) continue;
    if (quietWork || !section.hasAttribute('data-quiet-reading-tail')) return null;
    quietWork = true;
  }
  if (!quietWork) return null;
  const end = Math.max(0, node.scrollTop + list.getBoundingClientRect().bottom -
    node.getBoundingClientRect().top - node.clientTop - node.clientHeight);
  return end + 4 < pageTail(node) ? end : null;
}
function followedMessageTail(current: ReadingWindow, node: HTMLElement, list: HTMLElement | null) {
  if (!current.followMessages) return null;
  const end = messageTail(node, list);
  if (end === null) {
    // A decision, result or closed work group must not send the reader to a different tail.
    current.followMessages = false;
    current.reading.following = false;
  }
  return end;
}
const readingNotices = {
  '': '', loading: '正在读取对话，稍后可再试。',
  unanchored: '无法标记刚才的位置。可以只查看最新，或关闭提示留在这里。',
  missing: '当前记录未包含原消息，未跳到其他位置。',
  changed: '原文或排版已变化，已回到原消息。', edge: '已回到原消息，窗口边缘限制了对齐位置。',
} as const;
const messageId = (message?: Message, index = 0) => message ? message.id || `${message.role}:${message.at}:${index}` : '';
const contentTop = (node: HTMLElement, row: HTMLElement) => row.getBoundingClientRect().top - node.getBoundingClientRect().top - node.clientTop + node.scrollTop;
// The navigation can overlap the scroller. Anchors describe the readable area
// below it, so reopening a conversation never tucks the saved line under glass.
const readingTopInset = (node: HTMLElement) => Math.max(0, parseFloat(getComputedStyle(node).getPropertyValue('--pw-reading-top-inset')) || 0);
// A live reply may be the final direct child, but it is not a saved reading
// anchor. Use the committed DOM so a draft becoming a saved row needs no reset.
function savedRowCount(list: HTMLElement) {
  const tail = list.lastElementChild;
  return list.childElementCount - (tail && !tail.hasAttribute('data-message-index') ? 1 : 0);
}
// Messages are vertically ordered. Finding the first row below the viewport top
// must not measure every preceding answer on each scroll event in a long thread.
function readingRow(list: HTMLElement | null, viewportTop: number) {
  if (!list) return;
  // Saved articles are direct children, followed by at most one transient reply.
  // Inspect their positions without collecting descendants of every Markdown body.
  const items = list.children as HTMLCollectionOf<HTMLElement>;
  const count = savedRowCount(list);
  if (!count) return;
  let start = 0, end = count;
  while (start < end) {
    const middle = start + Math.floor((end - start) / 2);
    if (items[middle].getBoundingClientRect().bottom > viewportTop) end = middle;
    else start = middle + 1;
  }
  return items[Math.min(start, count - 1)];
}

/** Preserve user intent before content grows. Measuring the new bottom gap after
 * a long reply renders incorrectly treats that growth as the user scrolling up.
 * The native reading anchor is also restored after asynchronous history arrives. */
export function useConversationReading({ scroll, active, conversationId, composer, messages, streamMarker, paused }: Options) {
  const content = useRef<HTMLDivElement>(null), positions = useRef(new Map<string, Reading>());
  const window = useRef<ReadingWindow | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const messageList = useRef<HTMLDivElement>(null);
  const [hasNewReplies, setHasNewReplies] = useState(false);
  const [away, setAway] = useState(false);
  const [returnPoint, setReturnPoint] = useState<ReadingPlace | null>(null);
  const [readingNotice, setReadingNotice] = useState<keyof typeof readingNotices>('');
  const documentEpoch = bridge.getDocumentEpoch();
  const key = conversationId || composer.key || '';
  const last = messages.at(-1), lastId = messageId(last, messages.length - 1);
  const lastContent = last?.content;
  const latest = useRef({ lastId, paused });
  latest.current = { lastId, paused };

  const rememberChatPosition = useCallback(() => {
    const current = window.current, node = scroll.current;
    if (!current?.ready || !node?.isConnected || node.dataset.conversationId !== current.conversationId) return;
    const viewportTop = node.getBoundingClientRect().top + node.clientTop + readingTopInset(node);
    const row = readingRow(messageList.current, viewportTop);
    const index = Number(row?.dataset.messageIndex || 0);
    current.reading = { ...current.reading, top: node.scrollTop, index,
      offset: row ? Math.max(0, Math.round(viewportTop - row.getBoundingClientRect().top)) : 0,
      anchor: row?.dataset.messageId || '', observed: latest.current.lastId };
    // Reopening uses the original anchor, not a remembered jump to the work list.
    positions.current.set(current.key, { ...current.reading, following: current.reading.following && !current.followMessages });
  }, [scroll]);

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
    const end = followedMessageTail(current, node, messageList.current);
    // The reading controls can resize the scroller after the click. A scroll
    // event still at the position we set is not a manual move away from it.
    if (end !== null && current.reading.following && Math.abs(node.scrollTop - current.reading.top) <= 1)
      node.scrollTop = end;
    current.followMessages = end !== null && Math.abs(end - node.scrollTop) <= 4;
    current.reading.following = current.followMessages || atEnd(node);
    setAway(awayFromTail(node, current.followMessages ? end ?? undefined : undefined));
    // A one-way choice only applies to the place that could not be captured.
    setReadingNotice(previous => previous === 'unanchored' ? '' : previous);
    if (current.reading.following && !latest.current.paused) {
      current.reading.unread = false;
      setHasNewReplies(false);
    }
    rememberChatPosition();
    persistReading();
  }, [scroll, rememberChatPosition, persistReading]);

  const goToLatest = useCallback((preferMessages = false) => {
    const current = window.current, node = scroll.current;
    if (!current) return;
    const poised = current.ready && node?.isConnected && node.dataset.conversationId === current.conversationId;
    const end = preferMessages && poised ? messageTail(node, messageList.current) : null;
    current.followMessages = end !== null;
    current.reading.following = true;
    current.reading.unread = false;
    // Sending is an explicit return to the live tail, even while the original
    // conversation history is still loading or the composer is changing epoch.
    positions.current.set(current.key, { ...current.reading, following: !current.followMessages });
    setHasNewReplies(false);
    setAway(false);
    if (!poised) return;
    // Streaming must not race a smooth scroll's intermediate scroll events.
    node.scrollTop = end ?? node.scrollHeight;
    rememberChatPosition();
    persistReading();
  }, [scroll, rememberChatPosition, persistReading]);

  // A peek never becomes a native bookmark or a command on a conversation.
  // Keep it separate from the current position: scrolling the tail, opening a
  // task or sending an explicit reply must not replace the one-step way back.
  useLayoutEffect(() => { setReturnPoint(null); setReadingNotice(''); }, [conversationId, documentEpoch]);

  const readingWindow = () => {
    const current = window.current, node = scroll.current, list = messageList.current;
    if (!active || paused || document.hidden || !current || !node?.isConnected ||
      node.dataset.conversationId !== conversationId || current.conversationId !== conversationId ||
      bridge.getDocumentEpoch() !== documentEpoch || String(bridge.getState().state.conversationId || '') !== conversationId) return;
    return { current, node, list };
  };
  const viewLatest = () => {
    const win = readingWindow(); if (!win) return;
    const { current, node, list } = win;
    if (!current.ready || !list) { setReadingNotice('loading'); return; }
    // Another reply may arrive while the first visit still has a way back.
    // Keep that exact line instead of replacing it with a second bookmark.
    if (returnPoint || readingNotice === 'unanchored') {
      setReadingNotice(''); goToLatest(true); node.focus({ preventScroll: true }); return;
    }
    const bounds = node.getBoundingClientRect(), top = bounds.top + node.clientTop + readingTopInset(node);
    const row = readingRow(list, top), message = row ? messages[Number(row.dataset.messageIndex)] : undefined;
    // A synthetic index is fine for rendering but cannot identify a retained
    // message after the native history window has moved.
    const place = row && message?.id && message.id === row.dataset.messageId
      ? captureReadingPlace(row, message.content, top, bounds.top + node.clientTop + node.clientHeight) : null;
    if (!place) { setReadingNotice('unanchored'); return; }
    setReturnPoint(place); setReadingNotice('');
    goToLatest(true); node.focus({ preventScroll: true });
  };
  const returnToPlace = () => {
    const win = readingWindow(); if (!win || !returnPoint) return;
    const { current, node, list } = win;
    if (!current.ready || !list) { setReadingNotice('loading'); return; }
    const index = messages.findIndex(message => message.id === returnPoint.messageId);
    const row = index >= 0 ? list.children.item(index) as HTMLElement | null : null;
    if (!row || row.dataset.messageId !== returnPoint.messageId || row.dataset.messageIndex !== String(index)) {
      setReadingNotice('missing'); return;
    }
    const top = node.getBoundingClientRect().top + node.clientTop + readingTopInset(node);
    const target = readingReturnDelta(row, messages[index].content, top, returnPoint);
    const scrollTop = node.scrollTop + target.delta;
    current.followMessages = false;
    current.reading.following = false;
    node.scrollTop = scrollTop;
    // Focus the article, not a task/permission button or the keyboard.
    row.focus({ preventScroll: true });
    setReturnPoint(null);
    setReadingNotice(!target.exact ? 'changed' : Math.abs(node.scrollTop - scrollTop) > 1 ? 'edge' : '');
    setAway(awayFromTail(node));
    rememberChatPosition(); persistReading();
  };
  const dismissReturn = () => {
    const win = readingWindow(); if (!win) return;
    setReturnPoint(null); setReadingNotice('');
    win.node.focus({ preventScroll: true });
  };

  const stopFollowing = useCallback(() => {
    if (window.current) {
      window.current.followMessages = false;
      window.current.reading.following = false;
    }
  }, []);

  useLayoutEffect(() => {
    setAway(false);
    if (!active || !key) { window.current = null; return; }
    const saved = positions.current.get(key);
    const belongs = composer.conversationId === conversationId;
    const hasAnchor = belongs && !!(composer.anchorMessage || composer.observedMessage || composer.scrollIndex || composer.scrollOffset);
    const reading = saved ? { ...saved } : { top: 0, index: belongs ? Number(composer.scrollIndex || 0) : 0,
      offset: belongs ? Number(composer.scrollOffset || 0) : 0, anchor: belongs ? String(composer.anchorMessage || '') : '',
      observed: belongs ? String(composer.observedMessage || '') : '', following: !hasAnchor, unread: belongs && composer.newReplies === true };
    window.current = { key, draftKey: String(composer.key || ''), conversationId, ready: false, reading, marker: null, followMessages: false };
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
    const end = followedMessageTail(current, node, messageList.current);
    if (!current.ready) {
      const reading = current.reading;
      if (reading.following) node.scrollTop = end ?? node.scrollHeight;
      else {
        const list = messageList.current, items = list?.children as HTMLCollectionOf<HTMLElement> | undefined;
        const count = list ? savedRowCount(list) : 0;
        let row = items && count ? items[Math.min(Math.max(0, reading.index), count - 1)] : undefined;
        if (reading.anchor && items) {
          for (let i = 0; i < count; i++) {
            if (items[i].dataset.messageId === reading.anchor) { row = items[i]; break; }
          }
        }
        node.scrollTop = row ? contentTop(node, row) + reading.offset - readingTopInset(node) : reading.top;
        reading.unread ||= !!reading.observed && reading.observed !== lastId;
        reading.following = atEnd(node) && !paused;
        if (reading.following) reading.unread = false;
      }
      current.ready = true;
      current.marker = marker;
      setHasNewReplies(reading.unread);
      setAway(awayFromTail(node));
      rememberChatPosition();
      return;
    }
    const before = current.marker;
    const changed = !before || before.count !== marker.count || before.lastId !== marker.lastId ||
      before.content !== marker.content || before.stream !== marker.stream;
    current.marker = marker;
    if (current.reading.following && !paused) {
      node.scrollTop = end ?? node.scrollHeight;
      current.reading.unread = false;
      setHasNewReplies(false);
      rememberChatPosition();
    } else if (changed) {
      // During a reply update the navigation can still be moving. Keep the
      // user's reading anchor until the scroller reports a new position.
      current.reading.following = false;
      current.reading.unread = true;
      positions.current.set(current.key, { ...current.reading });
      setHasNewReplies(true);
    }
  }, [active, key, composer.key, composer.epoch, conversationId, lastContent, paused, messages.length, lastId, streamMarker, scroll, rememberChatPosition]);

  useEffect(() => {
    const node = scroll.current, body = content.current;
    if (!active || !node || !body || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => {
      const current = window.current;
      if (!current?.ready || !node.isConnected || node.dataset.conversationId !== current.conversationId) return;
      const end = followedMessageTail(current, node, messageList.current);
      // This also covers images, Markdown layout and the input/keyboard resizing.
      if (current.reading.following && !latest.current.paused) {
        node.scrollTop = end ?? node.scrollHeight;
        rememberChatPosition();
      }
      setAway(awayFromTail(node, end ?? undefined));
    });
    observer.observe(node);
    observer.observe(body);
    return () => observer.disconnect();
  }, [active, key, scroll, rememberChatPosition]);

  useEffect(() => () => { if (saveTimer.current) clearTimeout(saveTimer.current); }, []);
  return { content, messageList, saveReading, rememberChatPosition, goToLatest, stopFollowing,
    readingNav: { showLatest: away || hasNewReplies, unread: hasNewReplies, canReturn: !!returnPoint,
      oneWay: readingNotice === 'unanchored', notice: readingNotices[readingNotice], viewLatest, returnToPlace, dismissReturn } };
}
