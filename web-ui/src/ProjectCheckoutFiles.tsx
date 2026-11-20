import type { HostEditorFile } from './ProjectHostFileComparison';
import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { InlineDiff } from './shell-library';
import {checkoutEditorSelection,ProjectCheckoutTextSearch} from './ProjectCheckoutTextSearch';
import {ProjectCheckoutPathSearch} from './ProjectCheckoutPathSearch';
import {checkoutGitView, ProjectCheckoutGitChanges, type CheckoutGitView} from './ProjectCheckoutGitChanges';
import { bridge } from './bridge';
import type { SourceMember } from './project-preview';
import './project-development.css';
import './project-workspace-manga.css';

type Data = Record<string, any>;
type SavedEdit = { id: string; filesHandle: string; path: string; beforeHash: string; savedHash: string; before: string; after: string };
type EditorPosition = { start: number; end: number; scrollTop: number; scrollLeft: number };
// A UI bookmark, not a reusable native save handle or a copy of the file.
export type CheckoutSavedPosition = { projectId: string; checkoutId: string; path: string; savedHash: string; caret?: EditorPosition };
type FileRead = { gitStatusOperation?: string; saved?: SavedEdit; position?: CheckoutSavedPosition; currentOnly?: boolean };
type Props = { projectId: string; checkoutId: string; viewHandle: string; run: (action: string, args?: Data) => Promise<any>;
  hostFile?: HostEditorFile; sourceMember?: SourceMember & { taskId: string; operationId: string };
  resumeSaved?: CheckoutSavedPosition; onClose: (saved?: CheckoutSavedPosition) => void; registerBack: (handler: (() => boolean) | null) => void };
const command = 'projects.development.files.';
const windowKey = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const fileHash = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const textareaText = (value: string) => value.replace(/\r\n?/g, '\n');
function observedSave(submitted: Data, response: Data): SavedEdit | null {
  const proof = response.savedEdit, actual = response.latest;
  if (response.saveStatus !== 'observed' || response.executionAuthorized !== false || response.projectVersionWritten !== false ||
      !proof || typeof proof.id !== 'string' || !/^[a-f0-9-]{36}$/.test(proof.id) ||
      proof.path !== submitted.path || proof.beforeHash !== submitted.baseHash ||
      !(fileHash(proof.beforeHash) || proof.beforeHash === 'absent' && submitted.baseContent === '') ||
      !fileHash(proof.savedHash) || proof.savedHash === proof.beforeHash || actual?.deleted !== false ||
      actual.projectId !== submitted.projectId || actual.checkoutId !== submitted.checkoutId || actual.path !== submitted.path ||
      actual.expectedHash !== proof.savedHash || actual.content !== submitted.text || typeof actual.content !== 'string' ||
      typeof submitted.baseContent !== 'string') return null;
  return { id: proof.id, filesHandle: response.filesHandle, path: proof.path, beforeHash: proof.beforeHash,
    savedHash: proof.savedHash, before: submitted.baseContent, after: actual.content };
}
// Last unacknowledged interface text only. Native ProjectFileDraftStore remains
// the durable cache. A different restored draft always requires a visible choice.
const interfaceDrafts = new Map<string, string>();
const failureText = (error: unknown) => error instanceof Error ? error.message : String(error);

export function ProjectCheckoutFiles({ projectId, checkoutId, viewHandle, run, onClose, registerBack, sourceMember, hostFile, resumeSaved }: Props) {
  const resume = !sourceMember && !hostFile && resumeSaved?.projectId===projectId && resumeSaved.checkoutId===checkoutId &&
    fileHash(resumeSaved.savedHash) ? resumeSaved : undefined;
  const [listing, setListing] = useState<Data | null>(null), [file, setFile] = useState<Data | null>(null), [text, setText] = useState('');
  const [finding,setFinding]=useState(false),[finder,setFinder]=useState<'path'|'text'|'git'>('path');
  const [gitView,setGitView]=useState<CheckoutGitView|null>(null);
  const [savedEdit,setSavedEdit]=useState<SavedEdit|null>(null), [listingStale,setListingStale]=useState(false);
  const [searchVersion,setSearchVersion]=useState(0);
  const editorContainer=useRef<HTMLElement>(null);
  const [newPath, setNewPath] = useState(''), [busy, setBusy] = useState(false), [editing, setEditing] = useState(false), [error, setError] = useState('');
  const [treeOpen, setTreeOpen] = useState(true), [reviewed, setReviewed] = useState(false);
  const editorHeading = useRef<HTMLHeadingElement>(null);
  const [orphan, setOrphan] = useState<string | null>(null), [choice, setChoice] = useState<'keep_mine' | 'use_latest' | null>(null);
  const [adoption, setAdoption] = useState<Data | null>(null), [adoptionExpired, setAdoptionExpired] = useState(false);
  const activeAdoption = useRef<Data | null>(null), adoptionHeading = useRef<HTMLHeadingElement>(null); activeAdoption.current = adoption;
  const alive = useRef(false), generation = useRef(0), handle = useRef(''), currentFile = useRef<Data | null>(null), currentText = useRef('');
  const entered = useRef<object | null>(null), edits = useRef(0), editSequence = useRef(0), blockedEdit = useRef(false);
  const latest = useRef({ run, onClose }); latest.current = { run, onClose };
  const originalWindow = useRef(windowKey());
  const recentSave = savedEdit?.filesHandle===handle.current ? savedEdit : null;
  const showingSavedFile = !!recentSave && !!file && !file.dirty && !file.conflict && !file.saveUnknown &&
    orphan===null && !blockedEdit.current && file.path===recentSave.path && file.latest?.expectedHash===recentSave.savedHash && text===recentSave.after;
  const keyFor = (path: string) => `${projectId}|${checkoutId}|${path}`;
  const live = (sequence: number) => alive.current && generation.current === sequence && originalWindow.current === windowKey() && !document.hidden;
  const apply = (value: Data, body = true) => {
    if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.filesHandle !== handle.current) throw new Error('回执不属于这个原私人副本。');
    const next = { ...(currentFile.current?.fileHandle === value.fileHandle ? currentFile.current : {}), ...value };
    if (body) for (const key of ['saveStatus', 'message', 'saveError', 'importStatus', 'importReceipt', 'importMessage', 'importError', 'projectListRefreshMessage']) if (!(key in value)) delete next[key];
    if (!('sourceHit' in value)) delete next.sourceHit;
    if (!('formalFile' in value)) delete next.formalFile;
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
      const value = await latest.current.run(command + action, { viewHandle, ...(action === 'open' || action === 'openSource' || action === 'openHostChange' ? { checkoutId } : { filesHandle: handle.current }), ...args });
      if (live(sequence) && entered.current === token) { accept?.(value); return value; }
    } catch (failure) { if (live(sequence)) setError(failureText(failure)); }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const readList = (more = false) => invoke('list', { afterPath: more ? listing?.nextPath || '' : '', sourceDigest: more ? listing?.sourceDigest || '' : '' }, value => {
    if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.filesHandle !== handle.current) throw new Error('文件列表不属于原副本。');
    setListing(previous => ({ ...value, files: more ? [...rows(previous?.files), ...rows(value.files)] : rows(value.files) }));
    setListingStale(false);
  });
  const readFile = (path: string, { gitStatusOperation, saved, position, currentOnly }: FileRead = {}) => {
    if (sourceMember || hostFile) {
      if (path !== (sourceMember || hostFile)!.path || entered.current || edits.current) return;
      setReviewed(false); currentFile.current = null; setFile(null);
    }
    return invoke('read', { path, ...(gitStatusOperation?{gitStatusOperation}:{}), ...(saved?{savedEditId:saved.id}:{}) }, value => {
      if ((sourceMember || hostFile || gitStatusOperation || saved || position || currentOnly) && (value.path !== path || value.latest?.deleted !== false ||
          !fileHash(value.latest?.expectedHash) || typeof value.latest.content !== 'string'))
        throw new Error('没有取得原副本这个文件的当前内容，未打开替代文件。');
      if (saved && (value.savedEditId !== saved.id || value.latest.expectedHash !== saved.savedHash || value.latest.content !== saved.after))
        throw new Error('未核对到这次保存的原文件内容，未打开其他版本。');
      if ((position || currentOnly) && (value.latest.projectId !== projectId || value.latest.checkoutId !== checkoutId || value.latest.path !== path))
        throw new Error('读取内容不属于这份副本的这个路径。');
      if (position && value.latest.expectedHash !== position.savedHash)
        throw new Error('此路径当前字节不再是上次保存的版本，没有切换到其他版本。');
      setChoice(null); setAdoption(null); apply(value); setTreeOpen(false);
      const sequence = generation.current, caret = position?.caret;
      requestAnimationFrame(() => {
        if (!live(sequence) || currentFile.current?.fileHandle !== value.fileHandle) return;
        editorHeading.current?.scrollIntoView({ block: 'nearest' });
        const area = editorContainer.current?.querySelector('textarea');
        if (caret && area && value.dirty===false && !value.conflict && !value.saveUnknown &&
          value.text===value.latest.content && currentText.current===value.text && !interfaceDrafts.has(keyFor(path)) &&
          area.value===textareaText(value.text) && caret.start>=0 && caret.start<=caret.end && caret.end<=area.value.length) {
          area.focus({ preventScroll: true }); area.setSelectionRange(caret.start, caret.end);
          area.scrollTop=caret.scrollTop; area.scrollLeft=caret.scrollLeft;
        } else editorHeading.current?.focus({ preventScroll: true });
      });
    });
  };
  const openFiles = async () => {
    const pin = sourceMember && { taskId: sourceMember.taskId, operationId: sourceMember.operationId, artifactId: sourceMember.artifactId,
      artifactSha256: sourceMember.artifactSha256, path: sourceMember.path, fileSha256: sourceMember.fileSha256 };
    const result = await invoke(hostFile ? 'openHostChange' : pin ? 'openSource' : 'open', hostFile ? { hostFile } : pin ? { sourceMember: pin } : {}, value => {
      if (value.projectId !== projectId || value.checkoutId !== checkoutId || !value.filesHandle) throw new Error('没有取得原私人副本文件窗口。');
      if (hostFile && (value.executionAuthorized !== false || !value.hostFile || !Object.entries(hostFile).every(([k, v]) => value.hostFile[k] === v)))
        throw new Error('没有取得这份本机版本的原文件范围。');
      if (pin && (value.executionAuthorized !== false || !value.savedMember || !Object.entries(pin).every(([k, v]) => v === value.savedMember[k]) ||
          value.savedMember.projectId !== projectId || value.savedMember.checkoutId !== checkoutId || value.savedMember.bytes !== sourceMember!.bytes ||
          typeof value.savedMember.text !== 'string' || new TextEncoder().encode(value.savedMember.text).length !== sourceMember!.bytes)) throw new Error('原任务保存的文件与所选副本不符。');
      handle.current = value.filesHandle; setListing(value);
    });
    const target = pin || hostFile || resume;
    if (result && target && handle.current === result.filesHandle && live(generation.current))
      await readFile(target.path, { position: resume });
  };
  const edit = (value: string) => {
    const original = currentFile.current;
    if (!original || sourceMember && !reviewed || entered.current || orphan !== null || activeAdoption.current || document.hidden) return;
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
  const close = () => {
    if (entered.current || edits.current) return;
    if (activeAdoption.current) { dismissAdoption(); return; }
    if (choice) { setChoice(null); return; }
    if (!showingSavedFile || !recentSave || !live(generation.current) || currentFile.current!==file) {
      latest.current.onClose(); return;
    }
    const area = editorContainer.current?.querySelector('textarea');
    const caret = area?.value===textareaText(recentSave.after) ? {
      start: area.selectionStart, end: area.selectionEnd, scrollTop: area.scrollTop, scrollLeft: area.scrollLeft,
    } : undefined;
    latest.current.onClose({ projectId, checkoutId, path: recentSave.path, savedHash: recentSave.savedHash, caret });
  };
  const closeCurrent = useRef(close); closeCurrent.current = close;
  useEffect(() => {
    alive.current = true; originalWindow.current = windowKey();
    const retire = () => {
      generation.current++; entered.current = null; const old = handle.current; handle.current = '';
      if (old) void latest.current.run(command + 'close', { viewHandle, filesHandle: old }).catch(() => {});
    };
    const hidden = () => { if (document.hidden || originalWindow.current !== windowKey()) { retire(); latest.current.onClose(); } };
    const unsubscribe = bridge.subscribe(hidden); document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', hidden);
    void openFiles();
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', hidden); };
  }, [projectId, checkoutId, viewHandle]);
  useEffect(() => {
    registerBack(() => { closeCurrent.current(); return true; });
    return () => registerBack(null);
  }, [registerBack]);
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
  const saveFile = () => {
    const submitted = currentFile.current;
    if (!submitted) return;
    void invoke('save', {fileHandle:submitted.fileHandle,revision:submitted.revision,editSequence:submitted.editSequence},value=>{
      if (value.fileHandle!==submitted.fileHandle || value.path!==submitted.path) throw new Error('不属于这次保存的文件回执，草稿仍保留。');
      const known = observedSave(submitted,value);
      apply(value);
      if (value.saveStatus==='observed' && interfaceDrafts.get(keyFor(submitted.path))===value.text) interfaceDrafts.delete(keyFor(submitted.path));
      if (!sourceMember && !hostFile && (value.saveStatus==='observed' || value.saveStatus==='unknown')) {
        if (known) setSavedEdit(known);
        // Inventory, path and text hits may refer to the pre-save tree. A
        // new list/search is explicit and never executes Git in the background.
        setListingStale(true); setFinding(false); setSearchVersion(epoch=>epoch+1);
        setGitView(last=>last?.pendingOperation ? {pendingOperation:last.pendingOperation} : null);
      }
    });
  };
  const reopenedVersion = resume && file?.path===resume.path && file?.latest?.expectedHash===resume.savedHash ? resume : undefined;
  const editState = orphan!==null || blockedEdit.current ? '界面文字尚未核对' : file?.saveUnknown ? '保存结果待核对' :
    editing ? '正在保留编辑文字，未写入副本' : file?.dirty ?
    file.draftDurable===true && !file.draftError ? '手机草稿已保留，未写入副本' : '草稿仍在当前会话，尚未确认写入手机' : '没有未保存修改';
  const locked = busy || editing || !!adoption, nativeText = file?.text || '';
  const canFindText=['findText','findTextNext','readTextHit'].every(a=>bridge.getState().capabilities.commands.includes(command+a));
  const sourceHit=file?.sourceHit,anchorable=!!(sourceHit&&file&&file.path===sourceHit.path&&file.latest?.expectedHash===sourceHit.sha256&&
    !file.dirty&&!file.saveUnknown&&!file.conflict&&orphan===null&&typeof file.latest?.content==='string'&&text===file.latest.content);
  const savedSource=typeof file?.latest?.content==='string'&&sourceHit&&file.latest.expectedHash===sourceHit.sha256?file.latest.content as string:null;
  const savedExcerpt=!anchorable&&sourceHit&&savedSource!==null&&savedSource.slice(sourceHit.start,sourceHit.end)===sourceHit.query?
    savedSource.slice(Math.max(savedSource.lastIndexOf('\n',sourceHit.start-1)+1,sourceHit.start-70),sourceHit.start)+sourceHit.query+
      savedSource.slice(sourceHit.end,sourceHit.end+100).split('\n',1)[0]:null;
  const backLabel = sourceMember ? '返回原工作' : hostFile ? '返回本机项目' : '返回项目工作区';
  const formalLink = (pin: Data | undefined, evidence: 'import_receipt' | 'equal_content') => {
    if (!file || !pin || pin.evidence !== evidence || pin.projectId !== projectId || pin.checkoutId !== checkoutId || pin.path !== file.path ||
        typeof pin.handle !== 'string' || !pin.handle || !Number.isInteger(pin.version) || pin.version < 1 || !/^[a-f0-9-]{36}$/.test(pin.revisionId) ||
        !/^[a-f0-9]{64}$/.test(pin.sha256) || !Number.isInteger(pin.bytes) || pin.bytes < 0 || pin.bytes > 262144) return null;
    return <div className="pw-form-actions"><Button variant="secondary" disabled={busy || editing || orphan !== null || blockedEdit.current || !!file.dirty || !!file.saveUnknown}
      onClick={() => { if (evidence === 'equal_content') { activeAdoption.current = null; setAdoption(null); } void invoke('openFormal', { fileHandle: file.fileHandle, formalHandle: pin.handle }); }}>
      {evidence === 'equal_content' ? '查看内容相同的正式版本' : '打开已核对的正式文件'} v{pin.version}
    </Button></div>;
  };
  return <SettingsDialog isOpen title={sourceMember ? '原工作副本' : hostFile ? '已核对的本机文件' : '这份私人副本的文件'} onClose={close} closeLabel={backLabel}><div className="pw-checkout-files">
    <p className="pw-muted">{sourceMember ? '保存只改这个副本。原任务的固定包不会改变，也不会重新运行。' : hostFile ? '这是刚核对的手机副本。已有草稿会单独提示；保存只修改手机文件，采纳到本机正式项目仍须另行审阅，不会连回或改写主机。' : '这里是 AI 实际工作的副本，不是“项目文件”中的正式版本。保存只改副本；另行比较并采纳才会导回正式项目，不会运行代码或放开热修改；已有进程和未知回执仍会阻止不安全的保存。'}</p>
    <small className="pw-checkout-identity">{projectId} / {checkoutId}</small>
    {error && <p role="alert">{error}</p>}
    {!listing ? <Button variant="secondary" disabled={busy} onClick={() => void openFiles()}>{busy ? '正在核对原副本…' : '重新读取副本文件'}</Button> : <>
      {resume && !file && !busy && <div className="pw-form-actions">
        <Button variant="secondary" disabled={locked} onClick={()=>void readFile(resume.path,{position:resume})}>重新核对原保存版本</Button>
        <Button variant="ghost" disabled={locked} onClick={()=>void readFile(resume.path,{currentOnly:true})}>读取这一路径的当前内容</Button>
      </div>}
      {sourceMember || hostFile ? !file && <div><p dir="auto">{(sourceMember || hostFile)!.path}</p><Button variant="secondary" disabled={locked} onClick={() => void readFile((sourceMember || hostFile)!.path)}>{busy ? '正在读取同一文件…' : '重读原副本中的这个文件'}</Button></div> : <Accordion expandedKeys={new Set(treeOpen ? ['checkout-tree'] : [])} onExpandedChange={keys => setTreeOpen(Array.from(keys).includes('checkout-tree'))}><AccordionItem id="checkout-tree" title={file ? '切换文件或新建' : '选择要编辑的文件'} description={listingStale?'完整清单待重新核对':`${listing.fileCount} 个实际源码文件；另列保留草稿`}>
        {recentSave && <div className="pw-checkout-file-list" aria-label="最近一次已核对的保存">
          <Button variant="secondary" disabled={locked} onClick={()=>void readFile(recentSave.path,{saved:recentSave})}>
            <span dir="auto">{recentSave.path}</span><small>最近保存 · {recentSave.savedHash.slice(0,8)}</small>
          </Button>
        </div>}
        {listing.draftError && <p role="alert">{listing.draftError}</p>}
        {(canFindText||listing.gitAvailable===true)&&<div role="group" aria-label="查看副本文件" className="pw-form-actions">{(['path','text','git'] as const)
          .filter(v=>v==='path'||v==='text'&&canFindText||v==='git'&&listing.gitAvailable===true).map(v=><Button key={v}
          size="small" variant={finder===v?'secondary':'ghost'} aria-pressed={finder===v} disabled={locked} onClick={()=>{if(v!==finder){setFinding(false);setFinder(v)}}}>
          {v==='path'?'文件名':v==='text'?'代码文字':'Git 变更'}</Button>)}</div>}
        {finder==='git'&&listing.gitAvailable===true&&<ProjectCheckoutGitChanges value={gitView} busy={locked}
          inspect={request=>void invoke('list',{gitRead:request},v=>setGitView(checkoutGitView(v,projectId,checkoutId,handle.current)))}
          open={(path,op)=>void readFile(path,{gitStatusOperation:op})}/>}
        {finder==='text'&&canFindText&&<ProjectCheckoutTextSearch key={searchVersion} project={projectId} checkout={checkoutId} filesHandle={handle.current} busy={locked} visible={setFinding} invoke={invoke}
          open={(textHandle,hit,row,query)=>void invoke('readTextHit',{textHandle,hit},v=>{
            const a=v.sourceHit;
            if(!a||v.path!==row.path||a.path!==row.path||a.sha256!==row.sha256||a.line!==row.line||a.column!==row.column||a.query!==query||
              v.latest?.expectedHash!==row.sha256||v.latest?.deleted!==false||typeof v.latest.content!=='string'||!Number.isSafeInteger(a.start)||!Number.isSafeInteger(a.end)||
              a.start<0||a.end>v.latest.content.length||a.end-a.start!==query.length||v.latest.content.slice(a.start,a.end)!==query)throw Error('不能核对这行代码的原版，未定位到替代文件。');
            setChoice(null);setAdoption(null);apply(v);setTreeOpen(false);requestAnimationFrame(()=>{editorHeading.current?.scrollIntoView({block:'nearest'});editorHeading.current?.focus({preventScroll:true})});
          })}/>}
        {finder==='path'&&['find','findNext','readFound'].every(a=>bridge.getState().capabilities.commands.includes(command+a))&&<ProjectCheckoutPathSearch key={searchVersion} project={projectId} checkout={checkoutId}
          filesHandle={handle.current} busy={locked} visible={setFinding} invoke={invoke} open={(searchHandle,hit)=>void invoke('readFound',{searchHandle,hit:hit.hit},v=>{
            if(v.path!==hit.path||v.matchedFileSha256!==hit.sha256||v.filesHandle!==handle.current||v.projectId!==projectId||v.checkoutId!==checkoutId)throw Error('不能核对命中的原文件，未打开其他路径。');
            setChoice(null);setAdoption(null);apply(v);setTreeOpen(false);requestAnimationFrame(()=>{editorHeading.current?.scrollIntoView({block:'nearest'});editorHeading.current?.focus({preventScroll:true})});
          })}/>}
        {finder!=='git'&&!finding&&<>        <div className="pw-form-actions"><Button variant="ghost" disabled={locked} onClick={() => void readList()}>重新读取文件列表</Button></div>
        {listingStale ? <p className="pw-field-hint" role="status">旧列表不再显示，可重读完整清单。{recentSave && ' 也可直接核对上方已保存的文件。'}</p> : <>
        <div className="pw-checkout-file-list">{rows(listing.files).map(row => <Button key={row.path} aria-current={file?.path === row.path ? 'true' : undefined} variant="ghost" disabled={locked || row.binary === true} onClick={() => void readFile(row.path)}><span>{row.path}</span><small>{row.binary ? '二进制' : row.hasDraft ? '有保留草稿' : row.change === 'deleted' ? '副本已删除' : row.change === 'added' ? '副本新增' : row.change === 'modified' ? '副本已修改' : '与原基线一致'}</small></Button>)}</div>
        {!rows(listing.files).length && <p>副本中还没有文件。可以在下方填写相对路径创建，或返回目标让 AI 开始。</p>}
        {listing.nextPath && <Button variant="secondary" disabled={locked} onClick={() => void readList(true)}>读取下一页文件</Button>}</>}</>}
        {finder!=='git'&&<><TextField label="相对文件路径" value={newPath} onChange={setNewPath} isDisabled={locked} hint="例如 index.html 或 src/main.ts；不操作运行缓存和 Git 内部目录。" />
        <Button variant="secondary" disabled={locked || !newPath.trim()} onClick={() => void readFile(newPath.trim())}>打开或准备新文件草稿</Button></>}
      </AccordionItem></Accordion>}
      {file && <section ref={editorContainer} className="pw-checkout-editor"><header><h3 ref={editorHeading} tabIndex={-1}>{file.path}</h3><small>{file.baseHash === 'absent' ? '文件尚未存在' : '来自此副本的实际文件'} · {editState}</small>
        {reopenedVersion && <p className="pw-field-hint">已从同一副本重读保存版本 <code>{reopenedVersion.savedHash.slice(0,10)}</code>。{(file.dirty||orphan!==null)&&'编辑框仍保留未保存文字，不套用原光标位置。'}</p>}
      </header>
        {sourceMember && !reviewed ? <div>
          <p role="status">{file.latest.expectedHash === sourceMember.fileSha256 ? '刚读取的副本与任务当时保存的内容一致。' : '副本在交付后已改变。编辑的是当前副本，不会用旧包覆盖它。'}
            {(file.dirty || orphan !== null) && ' 此文件另有未保存草稿，进入编辑后可核对。'}</p>
          {file.latest.expectedHash !== sourceMember.fileSha256 && <Accordion><AccordionItem id="source-to-current" title="比较固定版本与当前副本">
            <InlineDiff before={listing.savedMember.text} after={file.latest.content} versionLabel="任务交付时 → 当前副本" beforeLabel="任务固定版本" afterLabel="刚读取的副本" />
          </AccordionItem></Accordion>}
          <Button disabled={locked} onClick={() => setReviewed(true)}>编辑当前副本</Button>
        </div> : <>
        {orphan !== null && <div role="alert"><p>上次界面文字尚未确认缓存，与手机恢复的草稿不同。下面保留的是界面文字，选择前不会替换任一份。</p><InlineDiff before={nativeText} after={orphan} versionLabel="手机恢复的草稿" /><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => { const retained = orphan; blockedEdit.current = true; setOrphan(null); currentText.current = retained; setText(retained); /* explicit cache edit, never a source write */ const sequence = ++editSequence.current; void invoke('edit', { fileHandle: file.fileHandle, editSequence: sequence, text: retained }, value => { blockedEdit.current = false; apply({ ...value, text: retained }, false); }); }}>保留界面文字为草稿</Button><Button variant="secondary" disabled={locked} onClick={() => { interfaceDrafts.delete(keyFor(file.path)); setOrphan(null); currentText.current = nativeText; setText(nativeText); }}>采用已恢复的手机草稿</Button></div></div>}
        {sourceHit&&<div><p role="status">已核对已保存文件第 {sourceHit.line} 行、第 {sourceHit.column} 列的「{sourceHit.query}」。
          {(!anchorable)&&' 当前显示保留的草稿，不把已保存版本的行号套用到草稿。'}</p>
          {savedExcerpt!==null&&<p className="pw-field-hint">已保存的行片段：<code dir="auto">{savedExcerpt}</code></p>}
          {anchorable&&<Button size="small" variant="secondary" disabled={locked} onClick={()=>{
            const area=editorContainer.current?.querySelector('textarea'),range=area&&checkoutEditorSelection(file.latest.content,sourceHit,area.value);
            if(!area||!range){setError('编辑框和已读取的版本不一致，没有替你移动光标。');return}
            area.focus();area.setSelectionRange(...range);
          }}>在编辑框选中这一处</Button>}</div>}
        <TextAreaField label="副本文件内容" value={text} onChange={edit} isReadOnly={busy || !!adoption || orphan !== null} className="pw-code-editor" />
        {(file.conflict || file.saveUnknown) && <p role="status">{file.saveUnknown ? '保存结果待核对，请读取同一文件。' : '副本文件与保留草稿的基线不同。请比较后决定，未自动覆盖。'}</p>}
        <Accordion>{(recentSave?.path!==file.path || file.dirty || file.conflict || orphan!==null) &&
          <AccordionItem id="checkout-file-diff" title="比较保留的修改"><InlineDiff before={file.baseContent || ''} after={text} versionLabel="草稿原基线" />{file.latest && file.conflict && <><h4>当前副本与保留草稿</h4><InlineDiff before={file.latest.content || ''} after={text} versionLabel="刚读取的副本" /></>}</AccordionItem>}
          {recentSave && recentSave.path===file.path && <AccordionItem id="checkout-last-save" title="比较最近一次已核对的保存" description={recentSave.savedHash.slice(0,8)}>
            <p className="pw-field-hint">本次文件窗口只保留这一份对照，不含之后的未保存输入，也不是正式版本历史。</p>
            <InlineDiff before={recentSave.before} after={recentSave.after} versionLabel="保存前 → 已写入副本"
              beforeLabel={recentSave.beforeHash==='absent'?'保存前（路径不存在）':'保存前'} afterLabel="本次写入" readOnly />
          </AccordionItem>}
        </Accordion>
        {choice ? <div role="region" aria-label="处理文件冲突"><p>{choice === 'keep_mine' ? '把当前副本作为新基线，保留我的草稿文字？下一次保存才会改写文件。' : '放弃这份草稿，采用刚读取的副本内容？不会写入文件。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => setChoice(null)}>返回</Button><Button disabled={locked} onClick={() => void invoke('resolve', { fileHandle: file.fileHandle, revision: file.revision, choice }, value => { interfaceDrafts.delete(keyFor(file.path)); setChoice(null); apply(value); })}>确认这个选择</Button></div></div> : <div className="pw-form-actions">
          <Button variant="secondary" disabled={locked || orphan !== null} onClick={() => void readFile(file.path)}>读取同一文件核对</Button>
          {file.conflict && <><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => setChoice('keep_mine')}>保留我的修改…</Button><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => setChoice('use_latest')}>采用当前副本…</Button></>}
          {showingSavedFile ? <Button disabled={locked} onClick={close}>已保存，回到项目工作区</Button> :
            <Button disabled={locked || orphan !== null || blockedEdit.current || !file.dirty || file.conflict || file.saveUnknown} onClick={saveFile}>保存到这个副本</Button>}
        </div>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={locked || orphan !== null || blockedEdit.current || file.dirty || file.saveUnknown} onClick={() => void invoke('reviewImport', { fileHandle: file.fileHandle }, value => {
          if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.fileHandle !== file.fileHandle || value.path !== file.path || value.filesHandle !== handle.current || !value.importHandle) throw new Error('采纳审阅不属于原文件。');
          setAdoption(value);
        })}>比较并采纳到正式项目…</Button></div>
        {adoption && <section className="pw-checkout-adoption" aria-label="采纳一个文件"><h3 ref={adoptionHeading} tabIndex={-1}>只采纳 {adoption.path}</h3>
          <p>正式项目 v{adoption.expectedVersion} → 这份已保存的私人副本。{adoption.deleted ? '这会在正式项目中登记此文件删除，可从原版本历史核对。' : '不会采纳其他文件或尚未保存的草稿。'}</p>
          <p>{adoption.message}</p>{adoption.diff ? <pre className="pw-checkout-review-diff" aria-label="正式版本与副本差异">{adoption.diff}</pre> : <p>{adoption.same ? '正式内容与副本已经相同。' : '文字相同，但文件存在或删除状态不同，请核对上方说明。'}</p>}
          {adoption.truncated && <p role="alert">差异超出完整展示范围，不能采纳。请缩小改动后重新比较。</p>}
          {adoption.same && formalLink(adoption.formalFile, 'equal_content')}
          {adoption.same && adoption.baselineNeedsRefresh && <p>本次只登记已一致的副本基线，不创建新的正式版本。</p>}
          <p className="pw-muted">确认前仍核对副本 SHA 和正式版本。不是跨两处存储的原子事务；确认后离开页面不等于撤销已写入的版本。回执不明时重新比较，不会自动重试。</p>
          {adoptionExpired && <p role="status">这次审阅已过期，尚未采纳。返回后重新比较。</p>}
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy || editing} onClick={dismissAdoption}>返回文件</Button><Button disabled={busy || editing || adoptionExpired || !adoption.importable || adoption.truncated} onClick={() => { const selected = activeAdoption.current; if (!selected || Number(selected.expiresAt) <= Date.now()) return; activeAdoption.current = null; setAdoption(null); void invoke('import', { importHandle: selected.importHandle }, value => apply(value)); }}>{adoption.same ? '只登记已一致的基线' : adoption.deleted ? '采纳此文件删除' : '采纳这个文件'}</Button></div>
        </section>}
        {file.importStatus && <p role="status">{file.importReceipt?.message || file.importMessage} {file.importError || ''} {file.projectListRefreshMessage || ''}</p>}
        {!adoption && file.importStatus === 'observed' && formalLink(file.formalFile, 'import_receipt')}
        {file.message && <p role="status">{file.message} {file.saveError || ''}</p>}
        {file.draftError && <p role="alert">{file.draftError}</p>}
        {(file.draftError || blockedEdit.current) && <div className="pw-form-actions"><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => { if (blockedEdit.current) edit(currentText.current); else void invoke('cache', { fileHandle: file.fileHandle, retry: true }, value => apply(value, false)); }}>重试保留草稿</Button></div>}
        </>}
      </section>}
    </>}
    <Button variant="secondary" disabled={busy || editing} onClick={close}>{adoption || choice ? '返回文件' : backLabel}</Button>
  </div></SettingsDialog>;
}
