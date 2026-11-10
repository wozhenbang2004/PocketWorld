import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconChevronRight, IconClose, IconFileText, IconRefresh } from './icons';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, IconButton } from './forms-library';
import { ShimmerText } from './library';
import './source-reading.css';

type Data = Record<string, any>;
export type AnswerSourceTarget = { messageId?: string; taskId?: string };
type SourceSelection = { handle: string; key: string; title: string };

// Presentation identity only: native rotates read handles on every list read.
// The current handle, never this key or the public pin, authorizes a detail read.
function sourceKey(entry: Data): string {
  const pin = entry.pin || {};
  return JSON.stringify([pin.kind, pin.sourceId, pin.sourceRevision, pin.messageIndex, pin.messageRevision]);
}

/** Retained answer evidence through the same native source reader as the existing owner sheet. */
export function AnswerSources({ target, revision, onClose }: { target: AnswerSourceTarget; revision: number; onClose: () => void }) {
  const [loaded, setLoaded] = useState<{ key: string; value: Data } | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(true);
  const [selected, setSelected] = useState<SourceSelection | null>(null), [cursor, setCursor] = useState(0), [offset, setOffset] = useState(0);
  const [previous, setPrevious] = useState<number[]>([]), [refresh, setRefresh] = useState(0);
  const windowId = useRef(''), request = useRef(0), alive = useRef(true);
  const body = useRef<HTMLDivElement>(null), positions = useRef(new Map<string, number>()), returnFocus = useRef<string | null>(null);
  const viewKey = JSON.stringify([selected?.key || null, cursor, offset]);
  const result = loaded?.key === viewKey ? loaded.value : null;
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; request.current++; const id = windowId.current; windowId.current = ''; if (id) void bridge.command('sources.close', { windowId: id }).catch(() => {}); };
  }, []);
  useEffect(() => {
    const attempt = ++request.current;
    setBusy(true); setLoaded(null); setError('');
    const reading = windowId.current ? bridge.command('sources.read', { windowId: windowId.current, sourceHandle: selected?.handle || '', cursor, offset }) : bridge.command('sources.open', target);
    void reading.then(value => {
      if (!alive.current || request.current !== attempt) {
        if (value?.windowId && value.windowId !== windowId.current) void bridge.command('sources.close', { windowId: value.windowId }).catch(() => {});
        return;
      }
      windowId.current = value.windowId; setLoaded({ key: viewKey, value });
    }).catch(failure => { if (alive.current && request.current === attempt) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (alive.current && request.current === attempt) setBusy(false); });
  }, [selected, cursor, offset, refresh, revision]);
  useLayoutEffect(() => {
    if (busy || !result || !body.current) return;
    body.current.scrollTop = positions.current.get(viewKey) || 0;
    if (selected || !returnFocus.current) return;
    const original = returnFocus.current; returnFocus.current = null;
    body.current.querySelectorAll<HTMLButtonElement>('[data-source-key]').forEach(button => {
      if (button.dataset.sourceKey === original) button.focus({ preventScroll: true });
    });
  }, [busy, result, selected, viewKey]);
  const entries: Data[] = Array.isArray(result?.entries) ? result.entries : [];
  const detail = result?.detail, range = detail?.readRange, current = result?.status === 'current';
  const list = () => { returnFocus.current = selected?.key || null; setSelected(null); setOffset(0); setPrevious([]); };
  return <Drawer.Root open onOpenChange={open => { if (!open) onClose(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer pw-answer-sources" aria-describedby="answer-source-description">
      <div className="pw-drawer-handle" /><header className="pw-drawer-header">
        {selected && <IconButton icon={IconArrowLeft} aria-label="返回来源列表" onClick={list} />}
        <div><Drawer.Title>{selected ? current && detail?.context?.title || selected.title || '原保留内容' : '回答来源'}</Drawer.Title></div>
        <IconButton icon={IconRefresh} aria-label="重新核对原来源" title="重新核对原来源" disabled={busy} onClick={() => setRefresh(value => value + 1)} />
        <IconButton icon={IconClose} aria-label="返回原回答" onClick={onClose} />
      </header>
      <Drawer.Description id="answer-source-description" className="pw-source-description">仅显示这次回答前保留的来源，不代表逐项采用或已经核实。</Drawer.Description>
      <div ref={body} className="pw-drawer-body" data-source-view={result ? viewKey : undefined} onScroll={event => {
        const key = event.currentTarget.dataset.sourceView;
        if (key) positions.current.set(key, event.currentTarget.scrollTop);
      }}>
        {busy && <ShimmerText>正在读取原保留范围…</ShimmerText>}
        {error && <p className="pw-error" role="alert">{error}</p>}
        {result && !current && <p className="pw-muted">{result.reason || '原来源已不可读；未用当前页面或其他任务内容代替。'}</p>}
        {current && !selected && <>{!entries.length && <p className="pw-muted">这个保留窗口没有可准确关联的来源；普通链接没有被当作来源证明。</p>}<div className="pw-source-list">{entries.map((entry, index) => entry.status === 'available' && entry.sourceHandle ?
          <Button variant="ghost" className="pw-source-row" key={entry.sourceHandle} data-source-key={sourceKey(entry)} leadingIcon={IconFileText} trailingIcon={IconChevronRight} aria-label={`查看原保留内容：${entry.title || '原来源'}`} onClick={() => {
            if (body.current) positions.current.set(viewKey, body.current.scrollTop);
            setSelected({ handle: entry.sourceHandle, key: sourceKey(entry), title: entry.title || '原来源' }); setOffset(0); setPrevious([]);
          }}><span className="pw-source-row-copy"><b>{entry.title || '原来源'}</b>{entry.label && <small>{entry.label}</small>}</span></Button> :
          <div className="pw-source-unavailable" key={entry.sourceHandle || index}><IconFileText size={20} aria-hidden="true"/><div><h3>{entry.title || '原来源'}</h3>{entry.label && <p>{entry.label}</p>}<p className="pw-muted">{entry.reason || '此来源当前不可读'}</p></div></div>
        )}</div><div className="pw-action-row pw-source-pagination">{cursor > 0 && <Button variant="ghost" onClick={() => setCursor(value => Math.max(0, value - 20))}>上一组来源</Button>}{Number.isInteger(result.nextCursor) && result.nextCursor >= 0 && <Button variant="secondary" onClick={() => setCursor(result.nextCursor)}>下一组来源</Button>}</div><p className="pw-muted">仅覆盖原执行当前保留的记录，不等于完整历史。</p></>}
        {current && selected && <>{detail?.context?.url && <p className="pw-source-url">{detail.context.url}</p>}{result.frame?.status === 'retained' && ['image/png', 'image/jpeg', 'image/webp'].includes(result.frame.mimeType) && typeof result.frame.imageBase64 === 'string' ? <img className="pw-task-image" alt="与原观察关联的保留屏帧" src={'data:' + result.frame.mimeType + ';base64,' + result.frame.imageBase64} /> : result.frame?.reason && <p className="pw-muted">{result.frame.reason}</p>}<div className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detail?.body || '没有可显示的原保留文本。'}</div><nav className="pw-source-read-navigation" aria-label="来源原文分段">{range && <p>原文本 {range.offset}–{Number(range.offset || 0) + Number(range.chars || 0)} / {range.totalChars} 字符</p>}<div className="pw-action-row">{previous.length > 0 && <Button variant="ghost" onClick={() => { setOffset(previous.at(-1)!); setPrevious(value => value.slice(0, -1)); }}>上一段</Button>}{Number.isInteger(detail?.nextOffset) && detail.nextOffset >= 0 && <Button variant="secondary" onClick={() => { setPrevious(value => [...value, offset]); setOffset(detail.nextOffset); }}>下一段</Button>}</div></nav></>}
      </div>
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
