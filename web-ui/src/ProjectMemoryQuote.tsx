import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type FileRow = { path: string; version: number };
type Catalog = { catalog: string; files: FileRow[]; matches: number };
type Located = FileRow & { review: string; offset: number; chars: number };
type Props = { name: string; spaceId: string; run: Run; close: () => void; revisit: (id?: string) => void; back: { current: (() => void) | null } };

/** No file body is fetched into the editor. Pasted text stays transient until the separate proposal. */
export function ProjectMemoryQuote({ name, spaceId, run, close, revisit, back }: Props) {
  const [filter, setFilter] = useState(''), [catalog, setCatalog] = useState<Catalog | null>(null), [index, setIndex] = useState('');
  const [quote, setQuote] = useState(''), [located, setLocated] = useState<Located | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [attempted, setAttempted] = useState(false);
  const live = useRef(true), entered = useRef(false), handles = useRef({ catalog: '', review: '' });
  const owner = useRef({ run, close }); owner.current = { run, close };
  const release = (key: 'catalog' | 'review') => {
    const handle = handles.current[key]; handles.current[key] = '';
    if (handle) void owner.current.run('knowledge.close', { handle }).catch(() => {});
  };
  const edit = () => { release('review'); setLocated(null); setError(''); };
  const cancel = () => { if (!entered.current) close(); };
  useEffect(() => { back.current = cancel; return () => { back.current = null; }; }, [back, close]);
  useEffect(() => {
    live.current = true; const epoch = bridge.getDocumentEpoch();
    const leave = () => { live.current = false; owner.current.close(); };
    const off = bridge.subscribe(() => { if (epoch !== bridge.getDocumentEpoch()) leave(); });
    const hide = () => { if (document.visibilityState === 'hidden') leave(); };
    document.addEventListener('visibilitychange', hide);
    return () => { live.current = false; off(); document.removeEventListener('visibilitychange', hide); release('review'); release('catalog'); };
  }, []);
  const request = async (work: () => Promise<void>) => {
    if (!live.current || entered.current) return;
    entered.current = true; setBusy(true); setError('');
    try { await work(); } catch (failure) { if (live.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (live.current) setBusy(false); }
  };
  const find = () => { void request(async () => {
    edit(); release('catalog'); setCatalog(null); setIndex('');
    const rows = await run('knowledge.project.files', { filter });
    if (!live.current) return;
    if (rows?.spaceId !== spaceId || typeof rows.catalog !== 'string' || !Array.isArray(rows.files) || !Number.isInteger(rows.matches) || rows.matches < rows.files.length) throw new Error('没有取得这份项目的文件清单，请重新查找。');
    handles.current.catalog = rows.catalog; setCatalog(rows);
  }); };
  const file = index !== '' ? catalog?.files[Number(index)] : null;
  const locate = () => { if (!catalog || !file || !quote.trim() || attempted) return; void request(async () => {
    edit();
    const value = await run('knowledge.project.locate', { catalog: catalog.catalog, index: Number(index), quote });
    if (!live.current) return;
    if (value?.spaceId !== spaceId || typeof value.review !== 'string' || value.path !== file.path || value.version !== file.version || !Number.isInteger(value.offset) || value.offset < 0 || !Number.isInteger(value.chars) || value.chars < 1 || value.chars > 4000) throw new Error('没有取得唯一的原文件选段，未生成候选。');
    handles.current.review = value.review; setLocated(value);
  }); };
  const propose = () => { if (!located || attempted) return; void request(async () => {
    setAttempted(true);
    const value = await run('knowledge.project.propose', { review: located.review });
    if (!live.current) return;
    if (value?.spaceId !== spaceId || typeof value.candidateId !== 'string' || !value.candidateId || !value.status) throw new Error('尚未取得项目候选回执。');
    revisit(value.candidateId);
  }); };
  return <SettingsDialog className="pw-memory-modal" isOpen title="摘录项目原文" onClose={cancel}>
    <div className="pw-knowledge-review"><p className="pw-detail-location">仅此项目 · {name}</p>
      {!located ? <>
        <TextField label="查找已提交的文字文件" placeholder="文件名或路径，可留空" maxLength={160} value={filter} isDisabled={busy} onChange={value => { setFilter(value); release('catalog'); setCatalog(null); setIndex(''); setError(''); }} />
        <Button variant="secondary" disabled={busy} onClick={find}>查找文件</Button>
        {catalog && (catalog.files.length ? <><SelectField label="原文件与版本" value={index} isDisabled={busy} options={[{ id: '', label: '请选择文件' }, ...catalog.files.map((row, i) => ({ id: String(i), label: row.path + ' · v' + row.version }))]} onChange={value => { setIndex(value); setError(''); }} />
          {catalog.matches > catalog.files.length && <p className="pw-muted">匹配 {catalog.matches} 个文件，仅列出前 {catalog.files.length} 个。可以输入更具体的路径。</p>}</> : <p role="status">这次查找没有匹配的已提交文字文件。</p>)}
        {file && <><TextAreaField label="粘贴要留下的原文" rows={5} maxLength={4000} value={quote} isDisabled={busy} onChange={value => { setQuote(value); setError(''); }} />
          <p className="pw-muted">只在选定版本定位这段话，不打开整份文件、不保存。重复或不匹配时会请你调整。</p></>}
      </> : <><h3>{located.path} · v{located.version}</h3><p className="pw-reading-copy">{quote}</p>
        <p className="pw-muted">原文件中唯一匹配 · {located.chars} 个字符。加入待审后仍须另行采用；不会成为个人偏好，也不向正在进行的任务提供资料。</p></>}
      {busy && <p role="status">{attempted ? '正在核对原候选回执…' : '正在核对原项目…'}</p>}
      {error && <p className="pw-error" role="alert">{error}</p>}
      {attempted && error && <p className="pw-muted">没有确认这次写入。请先查看本项目候选，不要重复提交。</p>}
      <div className="pw-form-actions">{!located && file && <Button disabled={busy || !quote.trim()} onClick={locate}>核对这段原文</Button>}
        {located && !attempted && <><Button disabled={busy} onClick={propose}>加入本项目待审</Button><Button variant="ghost" disabled={busy} onClick={edit}>调整选段</Button></>}
        {attempted && error && <Button variant="secondary" disabled={busy} onClick={() => revisit()}>核对本项目候选</Button>}
        <Button variant="ghost" disabled={busy} onClick={cancel}>{attempted ? '返回项目记忆' : '取消，不保存'}</Button></div>
    </div></SettingsDialog>;
}
