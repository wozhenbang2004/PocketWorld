import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SearchField } from './forms-library';
import { IconArrowLeft, IconChevronRight, IconFile } from './icons';
import { registerOverlayBack } from './overlay-back';
import type { ProjectPackageOutcome, SourceMember } from './project-preview';
import { fileBytes } from './attachment-workspace';

const command = 'tasks.projectPackageFiles', pageSize = 24, textLimit = 65536;
export const canReadProjectPackageFiles = () => bridge.getState().capabilities.commands.includes(command);
type Entry = { path: string; sha256: string; bytes: number; kind: 'text' | 'binary' };
type Page = { offset: number; query: string; total: number; matched: number; entries: Entry[] };
type Choice = { offset: number; query: string } | { file: Entry };
const integer = (n: unknown, max: number): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0 && n <= max;
const readable = (f: Entry) => f.kind === 'text' && f.bytes <= textLimit;
function entry(value: unknown): value is Entry {
  const f = value as Partial<Entry> | null;
  return !!f && typeof f.path === 'string' && f.path.length <= 240 && !/[\\:\u0000-\u001f]/.test(f.path) &&
    f.path.split('/').every(p => p && p !== '.' && p !== '..') && typeof f.sha256 === 'string' && /^[a-f0-9]{64}$/.test(f.sha256) &&
    integer(f.bytes, 2 * 1024 * 1024) && (f.kind === 'text' || f.kind === 'binary');
}
function windowKey(task: string) {
  const native = bridge.getState(), view = native.view as { taskId?: string; section?: string; resumeRevision?: number } | undefined;
  return !document.hidden && view && view.taskId === task && view.section == null && !native.state.attachmentWorkspace?.open
    ? JSON.stringify([bridge.getDocumentEpoch(), view.resumeRevision, task]) : '';
}

/** Mounted only for the source package the owner just selected from its original work. */
export type PackageFileSource = Pick<ProjectPackageOutcome,'taskId'|'operationId'|'artifactId'|'archiveSha256'|'runId'|'epoch'|'projectId'|'checkoutId'|'name'> & { currentRun?:boolean };
type TeamFileWindow={handle:string; key:()=>string; fromParentWork?:boolean};
export function ProjectPackageFiles({ item, onClose, onWholePackage, onCheckout, team }: {
  item: PackageFileSource; onClose: () => void; onWholePackage?: () => void; onCheckout?: (member: SourceMember) => void; team?:TeamFileWindow;
}) {
  const { taskId, operationId, artifactId, archiveSha256: artifactSha256 } = item;
  const key=()=>team?team.key():windowKey(taskId);
  const origin = useRef(key()), current = useRef({ onClose, item }); current.current = { onClose, item };
  const alive = useRef(false), serial = useRef(0), pending = useRef(false);
  const [page, setPage] = useState<Page | null>(null), [query, setQuery] = useState('');
  const [file, setFile] = useState<Entry | null>(null), [text, setText] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [failed, setFailed] = useState<Choice | null>(null);
  const originalButton = useRef<HTMLButtonElement | null>(null), heading = useRef<HTMLHeadingElement>(null);
  const listChoice = useRef({ offset: 0, query: '' });
  const goBack = () => {
    if (!file) { onClose(); return; }
    ++serial.current; pending.current = false; setFile(null); setText(null); setError(''); setFailed(null); setBusy(false);
    if (!page) void read(listChoice.current);
  };
  const back = useRef(goBack); back.current = goBack;
  useLayoutEffect(() => {
    if (!file && originalButton.current?.isConnected) originalButton.current.focus();
    else heading.current?.focus();
  }, [file]);
  const read = async (choice: Choice) => {
    if (pending.current || !alive.current || !origin.current || key() !== origin.current || !(team ? bridge.getState().capabilities.commands.includes('teams.readFinalPackageFiles') : canReadProjectPackageFiles())) return;
    const generation = ++serial.current;
    const accepts = () => alive.current && generation === serial.current && origin.current === key() &&
      current.current.item.artifactId === artifactId && current.current.item.archiveSha256 === artifactSha256;
    pending.current = true; setBusy(true); setError(''); setFailed(null); setText(null);
    try {
      const args = 'file' in choice ? { path: choice.file.path, fileSha256: choice.file.sha256 } : choice;
      const v = await bridge.command(team?'teams.readFinalPackageFiles':command, { ...(team?{viewHandle:team.handle}:{}), taskId, operationId, artifactId, artifactSha256, ...args });
      if (!accepts()) return;
      if (v?.taskId !== taskId || v.operationId !== operationId || v.artifactId !== artifactId || v.artifactSha256 !== artifactSha256 ||
          v.runId !== item.runId || v.epoch !== item.epoch || v.readOnly !== true) throw new Error('回执不属于这份原固定包。');
      if ('file' in choice) {
        const f = choice.file;
        if (!readable(f) || v.path !== f.path || v.fileSha256 !== f.sha256 || v.bytes !== f.bytes ||
            typeof v.text !== 'string' || v.text.length > textLimit || new TextEncoder().encode(v.text).length !== f.bytes) throw new Error('所选文件的版本或大小不符。');
        setText(v.text);
      } else {
        if (v.offset !== choice.offset || v.query !== choice.query || !integer(v.total, 2000) || !integer(v.matched, v.total) ||
            (v.offset !== 0 && v.offset >= v.matched) || !Array.isArray(v.entries) ||
            v.entries.length !== Math.min(pageSize, v.matched - v.offset) || !v.entries.every(entry) ||
            new Set(v.entries.map((f: Entry) => f.path)).size !== v.entries.length) throw new Error('未能核对这页文件清单。');
        listChoice.current = choice;
        setPage({ offset: v.offset, query: v.query, total: v.total, matched: v.matched, entries: v.entries });
      }
    } catch (cause) {
      if (accepts()) { setPage(null); setFailed(choice); setError(cause instanceof Error ? cause.message : '原固定包暂不可读取。'); }
    } finally { if (accepts()) { pending.current = false; setBusy(false); } }
  };
  useEffect(() => {
    alive.current = true;
    const invalidate = () => { if (!origin.current || origin.current !== key()) { ++serial.current; current.current.onClose(); } };
    const unsubscribe = bridge.subscribe(invalidate), removeBack = registerOverlayBack(() => back.current());
    document.addEventListener('visibilitychange', invalidate);
    if (origin.current) void read({ offset: 0, query: '' }); else invalidate();
    return () => { alive.current = false; ++serial.current; pending.current = false; unsubscribe(); removeBack(); document.removeEventListener('visibilitychange', invalidate); };
  }, []);
  return <section className="pw-package-members" aria-label="固定包内文件">
    <Button variant="ghost" size="small" leadingIcon={IconArrowLeft} onClick={goBack}>{file ? '返回包内文件' : team?.fromParentWork ? '返回发起协作的工作' : team ? '返回原团队成果' : '返回原工作成果'}</Button>
    <h4 ref={heading} tabIndex={-1} dir="auto">{file ? file.path : item.name}</h4>
    <p className="pw-field-hint">{file ? `${fileBytes(file.bytes)} · UTF-8 原文` : team ? '这次协作已签收的固定版本 · 只读，不运行代码' : `${item.currentRun ? '本轮' : '较早一轮'}保存的固定版本 · 只读，不运行代码`}</p>
    <div hidden={!!file}>
      {page && page.total > pageSize && <form className="pw-action-row" onSubmit={e => { e.preventDefault(); void read({ offset: 0, query }); }}>
        <SearchField value={query} onChange={setQuery} maxLength={120} aria-label="在固定包中查找文件" placeholder="按文件名或路径查找" clearLabel="清空查找文字" />
        <Button type="submit" variant="secondary" size="small" disabled={busy}>查找</Button>
      </form>}
      {page && <><p className="pw-field-hint">{page.query ? `“${page.query}”匹配 ${page.matched} 个文件` : `包内共 ${page.total} 个文件`}{page.matched > 0 && ` · 显示 ${page.offset + 1}–${page.offset + page.entries.length}`}</p>
        <div className="pw-outcome-files">{page.entries.map(f => readable(f)
          ? <Button key={f.path} variant="secondary" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight} disabled={busy} onClick={e => { originalButton.current = e.currentTarget; setFile(f); void read({ file: f }); }}>
            <span className="pw-outcome-file-copy"><bdi>{f.path}</bdi><small>{fileBytes(f.bytes)} · 查看原文</small></span></Button>
          : <div key={f.path} className="pw-outcome-file-copy pw-package-member-static"><bdi>{f.path}</bdi><small>{fileBytes(f.bytes)} · {f.kind === 'binary' ? '二进制文件' : '超过 64 KiB'}{team?'，此处仅列出':'，可随整包导出'}</small></div>)}</div>
        {page.matched > pageSize && <div className="pw-action-row">
          <Button size="small" variant="ghost" disabled={busy || page.offset === 0} onClick={() => void read({ offset: page.offset - pageSize, query: page.query })}>上一页</Button>
          <Button size="small" variant="ghost" disabled={busy || page.offset + pageSize >= page.matched} onClick={() => void read({ offset: page.offset + pageSize, query: page.query })}>下一页</Button>
        </div>}
      </>}
    </div>
    {file && text !== null && (text === ''
      ? <p className="pw-muted">此固定文件为空（0 字节）。</p> : <pre className="pw-file-text" tabIndex={0} aria-label="固定文件原文">{text}</pre>)}
    {file && text !== null && onCheckout && bridge.getState().capabilities.commands.includes('projects.development.files.openSource') &&
      <Button size="small" variant="secondary" disabled={busy} onClick={() => { if (origin.current === key()) onCheckout({ path: file.path, fileSha256: file.sha256, bytes: file.bytes,
        projectId: item.projectId, checkoutId: item.checkoutId, artifactId, artifactSha256 }); }}>在原副本继续修改</Button>}
    {busy && <p role="status" className="pw-muted">{file ? '正在读取所选原文…' : '正在读取固定包清单…'}</p>}
    {error && <p role="alert" className="pw-error">{error}</p>}
    {failed && <Button size="small" variant="secondary" disabled={busy} onClick={() => void read(failed)}>重读同一选择</Button>}
    {!file && onWholePackage && <Button size="small" variant="ghost" disabled={busy} onClick={onWholePackage}>查看整包预览与导出选项</Button>}
  </section>;
}
