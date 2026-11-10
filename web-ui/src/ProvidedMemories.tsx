import { useEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight, IconClose, IconRefresh } from './icons';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, IconButton } from './forms-library';
import { memoryHistoryStatus } from './memory-history';
import './source-reading.css';

type Data = Record<string, any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const statusLabel = (status: string) => ({ current: '当前仍有效', record_changed: '这条记忆后来已修改', record_removed: '这条记忆已删除', source_forgotten: '原来源已遗忘', superseded: '已被新记忆替代', expired: '已到期', revoked: '原引用已撤回', run_changed: '原执行已变化' } as Record<string, string>)[status] || '原版本当前不可读';

/** Uses the original answer's native request receipt, with the existing bounded source reader. */
export function ProvidedMemories({ messageId, revision, onClose, onManage, registerBack }: {
  messageId: string; revision: number; onClose: () => void; onManage: () => void;
  registerBack: (handler: (() => boolean) | null) => void;
}) {
  const [receipt, setReceipt] = useState<Data | null>(null), [selected, setSelected] = useState<Data | null>(null);
  const [source, setSource] = useState<Data | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const session = useRef(''), generation = useRef(0), live = useRef(true), reading = useRef(false);
  const closeSession = () => { const id = session.current; session.current = ''; if (id) void bridge.command('memoryHistory.close', { handle: id }).catch(() => {}); };
  useEffect(() => { live.current = true; return () => { live.current = false; generation.current++; closeSession(); }; }, []);
  useEffect(() => {
    const version = ++generation.current; closeSession(); setReceipt(null); setSelected(null); setSource(null); setError(''); setBusy(true);
    const current = () => live.current && generation.current === version;
    void (async () => {
      try {
        const opened = await bridge.command('memoryHistory.open', { view: 'tasks' });
        if (!current()) { void bridge.command('memoryHistory.close', { handle: opened.sessionHandle }).catch(() => {}); return; }
        session.current = opened.sessionHandle;
        const answer = await bridge.command('taskMemory.answer', { sessionHandle: session.current, messageId });
        if (!current()) return;
        const result = await bridge.command('taskMemory.receipt', { taskHandle: answer.taskHandle });
        if (!current()) return;
        if (result.modelUseVerified !== false || result.executionAuthorized !== false || !Array.isArray(result.records)) throw new Error('原记忆提供回执不完整，请重新核对。');
        setReceipt(result);
      } catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : String(failure)); }
      finally { if (current()) setBusy(false); }
    })();
  }, [messageId, revision, refresh]);
  useEffect(() => {
    const hidden = () => {
      if (document.visibilityState !== 'visible') {
        generation.current++; closeSession(); setReceipt(null); setSelected(null); setSource(null); setBusy(false);
        setError('已离开原阅读窗口，返回后请重新核对这条回答。');
      }
    };
    document.addEventListener('visibilitychange', hidden); return () => document.removeEventListener('visibilitychange', hidden);
  }, []);
  useEffect(() => {
    registerBack(() => { if (selected) { generation.current++; setSelected(null); setSource(null); setBusy(false); } else onClose(); return true; });
    return () => registerBack(null);
  }, [selected, registerBack, onClose]);
  const readSource = async (index: number, offset = 0) => {
    if (!selected?.readHandle || reading.current || !session.current) return;
    reading.current = true; const version = generation.current; setBusy(true); setSource(null); setError('');
    try {
      const value = await bridge.command('taskMemory.source', { readHandle: selected.readHandle, index, offset });
      if (live.current && version === generation.current) setSource({ ...value, index });
    } catch (failure) { if (live.current && version === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { reading.current = false; if (live.current && version === generation.current) setBusy(false); }
  };
  const provided = receipt?.phase === 'provided_to_request';
  const records = rows(receipt?.records);
  return <Drawer.Root open onOpenChange={open => { if (!open) onClose(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer pw-answer-sources" aria-describedby="provided-memory-description">
      <div className="pw-drawer-handle"/><header className="pw-drawer-header">
        {selected && <IconButton icon={IconChevronLeft} aria-label="返回本次记忆列表" disabled={busy} onClick={() => { setSelected(null); setSource(null); setError(''); }}/>}<div><Drawer.Title>{selected ? '这次提供的记忆' : '本次提供的记忆'}</Drawer.Title></div>
        <IconButton icon={IconRefresh} aria-label="重新核对原记忆回执" disabled={busy} onClick={() => setRefresh(value => value + 1)}/><IconButton icon={IconClose} aria-label="返回原回答" onClick={onClose}/>
      </header>
      <Drawer.Description id="provided-memory-description" className="pw-source-description">这些条目曾提供给这条回答的模型请求；不代表模型逐条采用或证实内容。</Drawer.Description>
      <div className="pw-drawer-body">
        {busy && <p className="pw-muted" role="status">正在核对原请求与记忆版本…</p>}{error && <p className="pw-error" role="alert">{error}</p>}
        {receipt && !provided && <p className="pw-muted">这条回答的原生提供回执当前不可读，未用新的检索结果代替。</p>}
        {provided && !selected && <>
          {!records.length && <p className="pw-muted">这次请求没有提供已保存的记忆条目。</p>}
          <div className="pw-source-list">{records.map(row => <Button key={row.id} variant="ghost" className="pw-source-row" trailingIcon={IconChevronRight} disabled={busy || row.status !== 'current' || !row.readHandle} onClick={() => { setSelected(row); setSource(null); setError(''); }}><span className="pw-source-row-copy"><b>{row.status === 'current' ? row.text : statusLabel(row.status)}</b><small>{row.selectionReason === 'task_query_match' ? '与这次问题相关' : row.selectionReason === 'owner_core' ? '你设定的常用记忆' : row.selectionReason === 'owner_current' ? '当前个人背景' : '原回执未保留选入原因'} · {statusLabel(row.status)}</small></span></Button>)}</div>
          <div className="pw-action-row"><Button variant="ghost" onClick={onManage}>管理我的记忆</Button></div>
        </>}
        {selected && <>
          <p className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{selected.text}</p>
          <p className="pw-muted">{selected.source || '原记忆记录'} · {statusLabel(selected.status)}{selected.expiresAt ? ` · 有效至 ${new Date(selected.expiresAt).toLocaleDateString()}` : ''}</p>
          {rows(selected.sourceRefs).length ? <div className="pw-action-row">{rows(selected.sourceRefs).map((_, index) => <Button key={index} variant="secondary" disabled={busy} onClick={() => void readSource(index)}>查看原来源 {index + 1}</Button>)}</div> : <p className="pw-muted">这条记录未附原文来源；显示的内容是保存的记忆。</p>}
          {source && <section className="pw-source-record"><h3>{source.title || '原来源'}</h3>{source.status && source.status !== 'current' ? <p className="pw-muted">{memoryHistoryStatus(source.status)}，未显示旧正文。</p> : <p className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</p>}{source.hasMore && <Button variant="ghost" disabled={busy} onClick={() => void readSource(source.index, source.nextOffset)}>继续读取原文</Button>}</section>}
        </>}
      </div>
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
