import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { InlineDiff } from './shell-library';
import { bridge } from './bridge';
import './project-development.css';

type Data = Record<string, any>;
type Props = { projectId: string; checkoutId: string; viewHandle: string; run: (action: string, args?: Data) => Promise<any>;
  onClose: () => void; registerBack: (handler: (() => boolean) | null) => void };
const command = 'projects.development.files.';
const windowKey = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
// Last unacknowledged interface text only. Native ProjectFileDraftStore remains
// the durable cache. A different restored draft always requires a visible choice.
const interfaceDrafts = new Map<string, string>();
const failureText = (error: unknown) => error instanceof Error ? error.message : String(error);

export function ProjectCheckoutFiles({ projectId, checkoutId, viewHandle, run, onClose, registerBack }: Props) {
  const [listing, setListing] = useState<Data | null>(null), [file, setFile] = useState<Data | null>(null), [text, setText] = useState('');
  const [newPath, setNewPath] = useState(''), [busy, setBusy] = useState(false), [editing, setEditing] = useState(false), [error, setError] = useState('');
  const [orphan, setOrphan] = useState<string | null>(null), [choice, setChoice] = useState<'keep_mine' | 'use_latest' | null>(null);
  const [adoption, setAdoption] = useState<Data | null>(null), [adoptionExpired, setAdoptionExpired] = useState(false);
  const activeAdoption = useRef<Data | null>(null), adoptionHeading = useRef<HTMLHeadingElement>(null); activeAdoption.current = adoption;
  const alive = useRef(false), generation = useRef(0), handle = useRef(''), currentFile = useRef<Data | null>(null), currentText = useRef('');
  const entered = useRef<object | null>(null), edits = useRef(0), editSequence = useRef(0), blockedEdit = useRef(false);
  const latest = useRef({ run, onClose }); latest.current = { run, onClose };
  const originalWindow = useRef(windowKey());
  const keyFor = (path: string) => `${projectId}|${checkoutId}|${path}`;
  const live = (sequence: number) => alive.current && generation.current === sequence && originalWindow.current === windowKey() && !document.hidden;
  const apply = (value: Data, body = true) => {
    if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.filesHandle !== handle.current) throw new Error('回执不属于这个原私人副本。');
    const next = { ...(currentFile.current?.fileHandle === value.fileHandle ? currentFile.current : {}), ...value };
    if (body) for (const key of ['saveStatus', 'message', 'saveError', 'importStatus', 'importReceipt', 'importMessage', 'importError', 'projectListRefreshMessage']) if (!(key in value)) delete next[key];
    currentFile.current = next; setFile(next);
    if (body && typeof value.text === 'string') {
      const retained = interfaceDrafts.get(keyFor(value.path));
      const unmatched = retained !== undefined && retained !== value.text;
      setOrphan(unmatched ? retained! : null);
      currentText.current = unmatched ? retained! : value.text; setText(currentText.current);
      editSequence.current = value.editSequence || 0; blockedEdit.current = false;
    }
    // Acknowledged native text is already in the shared DraftStore. Its separate
    // durable flag governs cold-process recovery; this map only holds UI text
    // the native owner has not acknowledged, so closed files cannot fill it up.
    if (next.editSequence === editSequence.current && interfaceDrafts.get(keyFor(next.path)) === currentText.current && next.text === currentText.current) interfaceDrafts.delete(keyFor(next.path));
  };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void) => {
    if (entered.current || edits.current || document.hidden) return;
    const sequence = generation.current, token = {}; entered.current = token; setBusy(true); setError('');
    try {
      const value = await latest.current.run(command + action, { viewHandle, ...(action === 'open' ? { checkoutId } : { filesHandle: handle.current }), ...args });
      if (live(sequence) && entered.current === token) { accept?.(value); return value; }
    } catch (failure) { if (live(sequence)) setError(failureText(failure)); }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const readList = (more = false) => invoke('list', { afterPath: more ? listing?.nextPath || '' : '', sourceDigest: more ? listing?.sourceDigest || '' : '' }, value => {
    if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.filesHandle !== handle.current) throw new Error('文件列表不属于原副本。');
    setListing(previous => ({ ...value, files: more ? [...rows(previous?.files), ...rows(value.files)] : rows(value.files) }));
  });
  const readFile = (path: string) => invoke('read', { path }, value => { setChoice(null); setAdoption(null); apply(value); });
  const edit = (value: string) => {
    const original = currentFile.current;
    if (!original || entered.current || orphan !== null || activeAdoption.current || document.hidden) return;
    const key = keyFor(original.path);
    if (value.length > 262144 || new TextEncoder().encode(value).length > 262144) { setError('编辑上限为 256 KiB，已有文字保留。'); return; }
    if (!interfaceDrafts.has(key) && interfaceDrafts.size >= 32) { setError('有 32 份界面文字尚未确认缓存，请先处理已有草稿。'); return; }
    interfaceDrafts.set(key, value); currentText.current = value; setText(value);
    const sequence = ++editSequence.current, epoch = generation.current, fileHandle = original.fileHandle;
    edits.current++; setEditing(true); setError('');
    void latest.current.run(command + 'edit', { viewHandle, filesHandle: handle.current, fileHandle, editSequence: sequence, text: value }).then(result => {
      if (!live(epoch) || currentFile.current?.fileHandle !== fileHandle || sequence !== editSequence.current || result.editSequence !== sequence) return;
      blockedEdit.current = false;
      apply({ ...result, text: value }, false);
    }).catch(failure => { if (live(epoch) && currentFile.current?.fileHandle === fileHandle && sequence === editSequence.current) { blockedEdit.current = true; setError(`这次文字尚未写入手机草稿缓存，界面文字仍保留：${failureText(failure)}`); } })
      .finally(() => { edits.current--; if (alive.current) setEditing(edits.current > 0); });
  };
  const dismissAdoption = () => { if (entered.current || edits.current) return; const selected = activeAdoption.current; activeAdoption.current = null; setAdoption(null); if (selected) void latest.current.run(command + 'dismissImport', { viewHandle, filesHandle: handle.current, importHandle: selected.importHandle }).catch(() => {}); };
  const close = () => { if (entered.current || edits.current) return; if (activeAdoption.current) { dismissAdoption(); return; } latest.current.onClose(); };
  useEffect(() => {
    alive.current = true; originalWindow.current = windowKey();
    const retire = () => {
      generation.current++; entered.current = null; const old = handle.current; handle.current = '';
      if (old) void latest.current.run(command + 'close', { viewHandle, filesHandle: old }).catch(() => {});
    };
    const hidden = () => { if (document.hidden || originalWindow.current !== windowKey()) { retire(); latest.current.onClose(); } };
    const unsubscribe = bridge.subscribe(hidden); document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', hidden);
    void invoke('open', {}, value => {
      if (value.projectId !== projectId || value.checkoutId !== checkoutId || !value.filesHandle) throw new Error('没有取得原私人副本文件窗口。');
      handle.current = value.filesHandle; setListing(value);
    });
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', hidden); };
  }, [projectId, checkoutId, viewHandle]);
  useEffect(() => {
    registerBack(() => { if (entered.current || edits.current) return true; if (activeAdoption.current) { dismissAdoption(); return true; } if (choice) { setChoice(null); return true; } close(); return true; });
    return () => registerBack(null);
  }, [registerBack, choice]);
  useEffect(() => {
    if (adoption || !file || file.draftDurable || file.draftError || busy || editing || blockedEdit.current || orphan !== null || document.hidden) return;
    const timer = window.setTimeout(() => { void invoke('cache', { fileHandle: file.fileHandle, retry: false }, value => apply(value, false)); }, 600);
    return () => window.clearTimeout(timer);
  }, [file, busy, editing, orphan, adoption]);
  useEffect(() => {
    setAdoptionExpired(false); if (!adoption) return; adoptionHeading.current?.focus();
    const remaining = Number(adoption.expiresAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) { setAdoptionExpired(true); return; }
    const timer = window.setTimeout(() => setAdoptionExpired(true), remaining); return () => window.clearTimeout(timer);
  }, [adoption]);
  const locked = busy || editing || !!adoption, nativeText = file?.text || '';
  return <SettingsDialog isOpen title="这份私人副本的文件" onClose={close} closeLabel="返回项目目标"><div className="pw-checkout-files">
    <p className="pw-muted">这里是 AI 实际工作的副本，不是“项目文件”中的正式版本。保存只改副本；另行比较并采纳才会导回正式项目，不会运行代码或放开热修改；已有进程和未知回执仍会阻止不安全的保存。</p>
    <small className="pw-checkout-identity">{projectId} / {checkoutId}</small>
    {error && <p role="alert">{error}</p>}
    {!listing ? <Button variant="secondary" disabled={busy} onClick={() => void invoke('open', {}, value => { if (value.projectId !== projectId || value.checkoutId !== checkoutId || !value.filesHandle) throw new Error('没有取得原私人副本文件窗口。'); handle.current = value.filesHandle; setListing(value); })}>重新读取副本文件</Button> : <>
      <Accordion><AccordionItem id="checkout-tree" title={file ? '切换文件或新建' : '选择要编辑的文件'} description={`${listing.fileCount} 个实际源码文件；另列保留草稿`}>
        {listing.draftError && <p role="alert">{listing.draftError}</p>}
        <div className="pw-form-actions"><Button variant="ghost" disabled={locked} onClick={() => void readList()}>重新读取文件列表</Button></div>
        <div className="pw-checkout-file-list">{rows(listing.files).map(row => <Button key={row.path} variant="ghost" disabled={locked || row.binary === true} onClick={() => void readFile(row.path)}><span>{row.path}</span><small>{row.binary ? '二进制' : row.hasDraft ? '有保留草稿' : row.change === 'deleted' ? '副本已删除' : row.change === 'added' ? '副本新增' : row.change === 'modified' ? '副本已修改' : '与原基线一致'}</small></Button>)}</div>
        {!rows(listing.files).length && <p>副本中还没有文件。可以在下方填写相对路径创建，或返回目标让 AI 开始。</p>}
        {listing.nextPath && <Button variant="secondary" disabled={locked} onClick={() => void readList(true)}>读取下一页文件</Button>}
        <TextField label="相对文件路径" value={newPath} onChange={setNewPath} isDisabled={locked} hint="例如 index.html 或 src/main.ts；不操作运行缓存和 Git 内部目录。" />
        <Button variant="secondary" disabled={locked || !newPath.trim()} onClick={() => void readFile(newPath.trim())}>打开或准备新文件草稿</Button>
      </AccordionItem></Accordion>
      {file && <section className="pw-checkout-editor"><header><h3>{file.path}</h3><small>{file.baseHash === 'absent' ? '文件尚未存在' : '来自此副本的实际文件'} · {file.dirty ? '有未保存修改' : '没有未保存修改'}</small></header>
        {orphan !== null && <div role="alert"><p>上次界面文字尚未确认缓存，与手机恢复的草稿不同。下面保留的是界面文字，选择前不会替换任一份。</p><InlineDiff before={nativeText} after={orphan} versionLabel="手机恢复的草稿" /><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => { const retained = orphan; blockedEdit.current = true; setOrphan(null); currentText.current = retained; setText(retained); /* explicit cache edit, never a source write */ const sequence = ++editSequence.current; void invoke('edit', { fileHandle: file.fileHandle, editSequence: sequence, text: retained }, value => { blockedEdit.current = false; apply({ ...value, text: retained }, false); }); }}>保留界面文字为草稿</Button><Button variant="secondary" disabled={locked} onClick={() => { interfaceDrafts.delete(keyFor(file.path)); setOrphan(null); currentText.current = nativeText; setText(nativeText); }}>采用已恢复的手机草稿</Button></div></div>}
        <TextAreaField label="副本文件内容" value={text} onChange={edit} isReadOnly={busy || !!adoption || orphan !== null} className="pw-code-editor" />
        {(file.conflict || file.saveUnknown) && <p role="status">{file.saveUnknown ? '保存结果待核对，请读取同一文件。' : '副本文件与保留草稿的基线不同。请比较后决定，未自动覆盖。'}</p>}
        <Accordion><AccordionItem id="checkout-file-diff" title="比较保留的修改"><InlineDiff before={file.baseContent || ''} after={text} versionLabel="草稿原基线" />{file.latest && file.conflict && <><h4>当前副本与保留草稿</h4><InlineDiff before={file.latest.content || ''} after={text} versionLabel="刚读取的副本" /></>}</AccordionItem></Accordion>
        {choice ? <div role="region" aria-label="处理文件冲突"><p>{choice === 'keep_mine' ? '把当前副本作为新基线，保留我的草稿文字？下一次保存才会改写文件。' : '放弃这份草稿，采用刚读取的副本内容？不会写入文件。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => setChoice(null)}>返回</Button><Button disabled={locked} onClick={() => void invoke('resolve', { fileHandle: file.fileHandle, revision: file.revision, choice }, value => { interfaceDrafts.delete(keyFor(file.path)); setChoice(null); apply(value); })}>确认这个选择</Button></div></div> : <div className="pw-form-actions">
          <Button variant="secondary" disabled={locked || orphan !== null} onClick={() => void readFile(file.path)}>读取同一文件核对</Button>
          {file.conflict && <><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => setChoice('keep_mine')}>保留我的修改…</Button><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => setChoice('use_latest')}>采用当前副本…</Button></>}
          <Button disabled={locked || orphan !== null || blockedEdit.current || !file.dirty || file.conflict || file.saveUnknown} onClick={() => void invoke('save', { fileHandle: file.fileHandle, revision: file.revision, editSequence: file.editSequence }, value => { if (value.saveStatus === 'observed' && interfaceDrafts.get(keyFor(file.path)) === value.text) interfaceDrafts.delete(keyFor(file.path)); apply(value); })}>保存到这个副本</Button>
        </div>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={locked || orphan !== null || blockedEdit.current || file.dirty || file.saveUnknown} onClick={() => void invoke('reviewImport', { fileHandle: file.fileHandle }, value => {
          if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.fileHandle !== file.fileHandle || value.path !== file.path || value.filesHandle !== handle.current || !value.importHandle) throw new Error('采纳审阅不属于原文件。');
          setAdoption(value);
        })}>比较并采纳到正式项目…</Button></div>
        {adoption && <section className="pw-checkout-adoption" aria-label="采纳一个文件"><h3 ref={adoptionHeading} tabIndex={-1}>只采纳 {adoption.path}</h3>
          <p>正式项目 v{adoption.expectedVersion} → 这份已保存的私人副本。{adoption.deleted ? '这会在正式项目中登记此文件删除，可从原版本历史核对。' : '不会采纳其他文件或尚未保存的草稿。'}</p>
          <p>{adoption.message}</p>{adoption.diff ? <pre className="pw-checkout-review-diff" aria-label="正式版本与副本差异">{adoption.diff}</pre> : <p>{adoption.same ? '正式内容与副本已经相同。' : '文字相同，但文件存在或删除状态不同，请核对上方说明。'}</p>}
          {adoption.truncated && <p role="alert">差异超出完整展示范围，不能采纳。请缩小改动后重新比较。</p>}
          {adoption.same && adoption.baselineNeedsRefresh && <p>本次只登记已一致的副本基线，不创建新的正式版本。</p>}
          <p className="pw-muted">确认前仍核对副本 SHA 和正式版本。不是跨两处存储的原子事务；确认后离开页面不等于撤销已写入的版本。回执不明时重新比较，不会自动重试。</p>
          {adoptionExpired && <p role="status">这次审阅已过期，尚未采纳。返回后重新比较。</p>}
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy || editing} onClick={dismissAdoption}>返回文件</Button><Button disabled={busy || editing || adoptionExpired || !adoption.importable || adoption.truncated} onClick={() => { const selected = activeAdoption.current; if (!selected || Number(selected.expiresAt) <= Date.now()) return; activeAdoption.current = null; setAdoption(null); void invoke('import', { importHandle: selected.importHandle }, value => apply(value)); }}>{adoption.same ? '只登记已一致的基线' : adoption.deleted ? '采纳此文件删除' : '采纳这个文件'}</Button></div>
        </section>}
        {file.importStatus && <p role="status">{file.importReceipt?.message || file.importMessage} {file.importError || ''} {file.projectListRefreshMessage || ''}</p>}
        {file.message && <p role="status">{file.message} {file.saveError || ''}</p>}
        <p role="status">{editing ? '正在保留这次文字…' : file.draftError || (file.draftDurable ? '草稿缓存已核对' : '文字仍在当前会话，正在保存到手机缓存。')}</p>
        {(file.draftError || blockedEdit.current) && <div className="pw-form-actions"><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => { if (blockedEdit.current) edit(currentText.current); else void invoke('cache', { fileHandle: file.fileHandle, retry: true }, value => apply(value, false)); }}>重试保留草稿</Button></div>}
      </section>}
    </>}
    <Button variant="secondary" disabled={busy || editing} onClick={close}>{adoption ? '返回文件' : '返回项目目标'}</Button>
  </div></SettingsDialog>;
}
