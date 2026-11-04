import { useEffect, useRef, useState } from 'react';
import { RiArrowLeftLine, RiCloseLine, RiRefreshLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, IconButton } from './forms-library';
import { ShimmerText } from './library';

type Data = Record<string, any>;
export type AnswerSourceTarget = { messageId?: string; taskId?: string };

/** Retained answer evidence through the same native source reader as the existing owner sheet. */
export function AnswerSources({ target, revision, onClose }: { target: AnswerSourceTarget; revision: number; onClose: () => void }) {
  const [result, setResult] = useState<Data | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(true);
  const [selected, setSelected] = useState<string | null>(null), [cursor, setCursor] = useState(0), [offset, setOffset] = useState(0);
  const [previous, setPrevious] = useState<number[]>([]), [refresh, setRefresh] = useState(0);
  const windowId = useRef(''), request = useRef(0), alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; request.current++; const id = windowId.current; windowId.current = ''; if (id) void bridge.command('sources.close', { windowId: id }).catch(() => {}); };
  }, []);
  useEffect(() => {
    const attempt = ++request.current;
    setBusy(true); setResult(null); setError('');
    const reading = windowId.current ? bridge.command('sources.read', { windowId: windowId.current, sourceHandle: selected || '', cursor, offset }) : bridge.command('sources.open', target);
    void reading.then(value => {
      if (!alive.current || request.current !== attempt) {
        if (!alive.current && value?.windowId) void bridge.command('sources.close', { windowId: value.windowId }).catch(() => {});
        return;
      }
      windowId.current = value.windowId; setResult(value);
    }).catch(failure => { if (alive.current && request.current === attempt) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (alive.current && request.current === attempt) setBusy(false); });
  }, [selected, cursor, offset, refresh, revision]);
  const entries: Data[] = Array.isArray(result?.entries) ? result.entries : [];
  const detail = result?.detail, range = detail?.readRange, current = result?.status === 'current';
  const list = () => { setSelected(null); setOffset(0); setPrevious([]); };
  return <Drawer.Root open onOpenChange={open => { if (!open) onClose(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer" aria-describedby="answer-source-description">
      <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>{selected ? '原保留内容' : '这次回答的来源'}</Drawer.Title><Drawer.Description id="answer-source-description">仅显示该次回答前保留的来源与观察，不表示模型逐项采用或回答已经核实。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="返回原回答" onClick={onClose} /></header>
      <div className="pw-drawer-body">
        <div className="pw-action-row">{selected && <Button variant="ghost" leadingIcon={RiArrowLeftLine} onClick={list}>返回来源列表</Button>}<Button variant="ghost" leadingIcon={RiRefreshLine} disabled={busy} onClick={() => setRefresh(value => value + 1)}>重新核对原来源</Button></div>
        {busy && <ShimmerText>正在读取原保留范围…</ShimmerText>}
        {error && <p className="pw-error" role="alert">{error}</p>}
        {result && !current && <p className="pw-muted">{result.reason || '原来源已不可读；未用当前页面或其他任务内容代替。'}</p>}
        {current && !selected && <>{!entries.length && <p className="pw-muted">这个保留窗口没有可准确关联的来源；普通链接没有被当作来源证明。</p>}{entries.map((entry, index) => <div className="pw-source-record" key={entry.sourceHandle || index}><h3>{entry.title || '原来源'}</h3>{entry.label && <p>{entry.label}</p>}{entry.status === 'available' && entry.sourceHandle ? <Button variant="secondary" onClick={() => { setSelected(entry.sourceHandle); setOffset(0); setPrevious([]); }}>查看原保留内容</Button> : <p className="pw-muted">{entry.reason || '此来源当前不可读'}</p>}</div>)}<div className="pw-action-row">{cursor > 0 && <Button variant="ghost" onClick={() => setCursor(value => Math.max(0, value - 20))}>上一组来源</Button>}{Number.isInteger(result.nextCursor) && result.nextCursor >= 0 && <Button variant="secondary" onClick={() => setCursor(result.nextCursor)}>下一组来源</Button>}</div><p className="pw-muted">仅覆盖原执行当前保留的记录，不等于完整历史。</p></>}
        {current && selected && <>{detail?.context?.title && <h3>{detail.context.title}</h3>}{detail?.context?.url && <p className="pw-muted">{detail.context.url}</p>}{result.frame?.status === 'retained' && ['image/png', 'image/jpeg', 'image/webp'].includes(result.frame.mimeType) && typeof result.frame.imageBase64 === 'string' ? <img className="pw-task-image" alt="与原观察关联的保留屏帧" src={'data:' + result.frame.mimeType + ';base64,' + result.frame.imageBase64} /> : result.frame?.reason && <p className="pw-muted">{result.frame.reason}</p>}<div className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detail?.body || '没有可显示的原保留文本。'}</div>{range && <p className="pw-muted">原文本 {range.offset}–{Number(range.offset || 0) + Number(range.chars || 0)} / {range.totalChars} 字符</p>}<div className="pw-action-row">{previous.length > 0 && <Button variant="ghost" onClick={() => { setOffset(previous.at(-1)!); setPrevious(value => value.slice(0, -1)); }}>上一段</Button>}{Number.isInteger(detail?.nextOffset) && detail.nextOffset >= 0 && <Button variant="secondary" onClick={() => { setPrevious(value => [...value, offset]); setOffset(detail.nextOffset); }}>下一段</Button>}</div></>}
      </div>
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
