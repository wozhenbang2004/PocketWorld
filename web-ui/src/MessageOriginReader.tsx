import { useEffect, useRef, useState } from 'react';
import { RiCloseLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, IconButton } from './forms-library';
import { ShimmerText } from './library';

export type MessageOriginTarget = { messageId: string; sourceMessageId: string };
type Data = Record<string, any>;

/** A bounded read of the native reply's original request. It never selects another composer. */
export function MessageOriginReader({ target, onClose }: { target: MessageOriginTarget; onClose: () => void }) {
  const [page, setPage] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), entered = useRef(false);
  const read = async (offset: number) => {
    if (entered.current) return; entered.current = true; setBusy(true); setError('');
    try {
      const result = await bridge.command('message.origin', { ...target, offset });
      if (alive.current) setPage(result);
    } catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  useEffect(() => { alive.current = true; void read(0); return () => { alive.current = false; }; }, []);
  return <Drawer.Root open onOpenChange={open => { if (!open) onClose(); }} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer" aria-describedby="pw-message-origin-description">
    <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>这次答复的原请求</Drawer.Title><Drawer.Description id="pw-message-origin-description">查看当时交代的内容，关闭后回到原答复。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="返回原答复" onClick={onClose} /></header>
    <div className="pw-drawer-body">{busy && <ShimmerText>正在读取原请求…</ShimmerText>}{error && <p role="alert">{error}</p>}
      {page?.available === false ? <p role="status">{page.message || '原请求目前不可读取。答复和任务入口仍保留。'}</p> : typeof page?.text === 'string' && <><p className="pw-reading-copy">{page.text}</p>{page.totalChars > page.text.length && <p className="pw-muted">已显示 {page.offset}–{page.nextOffset} / {page.totalChars} 字符</p>}</>}
      {page?.hasMore && Number.isSafeInteger(page.nextOffset) && <Button variant="ghost" disabled={busy} onClick={() => { void read(page.nextOffset); }}>继续读取原请求</Button>}
      {error && <Button variant="secondary" disabled={busy} onClick={() => { void read(page?.offset || 0); }}>重新读取这一段</Button>}
    </div></Drawer.Content></Drawer.Portal></Drawer.Root>;
}
