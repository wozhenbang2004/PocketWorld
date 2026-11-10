import { useEffect, useRef, useState } from 'react';
import { IconClose } from './icons';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, IconButton, SelectField } from './forms-library';
import { ShimmerText } from './library';

export type MessageOriginTarget = { conversationId: string; messageId: string; sourceMessageIds: string[]; returnFocus?: HTMLElement | null };
type Data = Record<string, any>;

/** A bounded read of the native reply's original requests. It never selects another composer. */
export function MessageOriginReader({ target, onClose }: { target: MessageOriginTarget; onClose: () => void }) {
  const [sourceMessageId, setSourceMessageId] = useState(target.sourceMessageIds[0] || '');
  return <Drawer.Root open onOpenChange={open => { if (!open) onClose(); }} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer" aria-describedby="pw-message-origin-description" onCloseAutoFocus={event => { event.preventDefault(); if (target.returnFocus?.isConnected) target.returnFocus.focus({ preventScroll: true }); }}>
    <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>这次答复的原请求</Drawer.Title><Drawer.Description id="pw-message-origin-description">查看当时交代的内容，关闭后回到原答复。</Drawer.Description></div><IconButton icon={IconClose} aria-label="返回原答复" onClick={onClose} /></header>
    <div className="pw-drawer-body">
      {target.sourceMessageIds.length > 1 && <SelectField label="对应原请求" value={sourceMessageId} options={target.sourceMessageIds.map((id, index) => ({ id, label: `原请求 ${index + 1}` }))} onChange={id => { if (target.sourceMessageIds.includes(id)) setSourceMessageId(id); }} />}
      {sourceMessageId && <OriginalRequestPage key={sourceMessageId} conversationId={target.conversationId} messageId={target.messageId} sourceMessageId={sourceMessageId} />}
    </div></Drawer.Content></Drawer.Portal></Drawer.Root>;
}

function OriginalRequestPage({ conversationId, messageId, sourceMessageId }: { conversationId: string; messageId: string; sourceMessageId: string }) {
  const [page, setPage] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [offsets, setOffsets] = useState<number[]>([]);
  const alive = useRef(true), entered = useRef(false), requestedOffset = useRef(0);
  const read = async (offset: number) => {
    if (entered.current) return; entered.current = true; requestedOffset.current = offset; setBusy(true); setError('');
    try {
      const result = await bridge.command('message.origin', { messageId, sourceMessageId, offset });
      if (result.conversationId !== conversationId || result.replyMessageId !== messageId || result.sourceMessageId !== sourceMessageId || typeof result.available !== 'boolean') throw new Error('原请求的读取身份尚不可核对，请重新打开原答复。');
      if (result.available && (typeof result.text !== 'string' || result.offset !== offset || !Number.isSafeInteger(result.nextOffset) || result.nextOffset !== offset + result.text.length || !Number.isSafeInteger(result.totalChars) || result.totalChars < result.nextOffset || result.hasMore !== (result.nextOffset < result.totalChars) || result.hasMore && result.nextOffset <= offset)) throw new Error('原请求的读取范围尚不可核对，请重新读取这一段。');
      if (alive.current) {
        setPage(result);
        setOffsets(previous => { const index = previous.indexOf(offset); return index < 0 ? [...previous, offset] : previous.slice(0, index + 1); });
      }
    } catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  useEffect(() => { alive.current = true; void read(0); return () => { alive.current = false; }; }, []);
  return <>
    {busy && <ShimmerText>正在读取原请求…</ShimmerText>}{error && <p role="alert">{error}</p>}
    {page?.available === false ? <p role="status">{page.message || '原请求目前不可读取。答复和任务入口仍保留。'}</p> : typeof page?.text === 'string' && <><p className="pw-reading-copy">{page.text || '这条原请求没有文字内容。'}</p>{page.totalChars > page.text.length && <p className="pw-muted">已显示 {page.offset}–{page.nextOffset} / {page.totalChars} 字符</p>}</>}
    {page?.available && <div className="pw-action-row">
      {offsets.length > 1 && <Button variant="ghost" disabled={busy} onClick={() => { void read(offsets[offsets.length - 2]); }}>上一段原请求</Button>}
      {page.hasMore && <Button variant="ghost" disabled={busy} onClick={() => { void read(page.nextOffset); }}>继续读取原请求</Button>}
    </div>}
    {error && <Button variant="secondary" disabled={busy} onClick={() => { void read(requestedOffset.current); }}>重新读取这一段</Button>}
  </>;
}
