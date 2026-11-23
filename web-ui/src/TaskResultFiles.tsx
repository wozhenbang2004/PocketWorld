import { useEffect, useId, useMemo, useRef, useState, useSyncExternalStore, type MouseEvent } from 'react';
import { attachmentFileFormat, fileBytes, fileRunProvenance } from './attachment-workspace';
import { bridge } from './bridge';
import { ownerWorkIdentity, workIdentityKey } from './conversation-work';
import { Accordion, AccordionItem, Button } from './forms-library';
import { IconChevronRight, IconFile } from './icons';
import { taskAuditPage, type TaskHeader } from './task-history';

type PdfExcerptStart = { page: number; chunk: number; method: 'text_layer' | 'ocr' };
export type TaskResultFile = { id: string; sha256: string; name: string; mimeType: string; size: number; runProvenance?: 'current' | 'earlier' | 'unrecorded'; sourceRunEvidence?: 'original_device_file_receive' | 'original_ssh_file_download' | 'original_node_compiler_output'; hasSavedSupplement?: boolean; pdfExcerptStart?: PdfExcerptStart };

type SavedFileFact = { position: number; id: string; sha256: string };
type FileNotice = SavedFileFact & { scope: string };
type FileNotices = { scope: string; through: number; pending?: FileNotice };
type FileCheck = { window: string; notice: FileNotice };
type FilePage = { key: string; files: TaskResultFile[]; sources: TaskResultFile[]; limited: boolean; completedFileRun?: boolean; sourceUnavailable?: boolean; hasRequestAttachments?: boolean; error?: string; checkedFile?: FileNotice };

function runningFileWork(taskId: string): TaskHeader | undefined {
  const native = bridge.getState(), view = native.view as { taskId?: string; section?: string | null } | undefined;
  if (view?.taskId !== taskId || view.section !== null || !native.capabilities.commands.includes('tasks.resultFiles')) return;
  const tasks = native.state.tasks as TaskHeader[] | undefined;
  const task = Array.isArray(tasks) ? tasks.find(row => row?.id === taskId) : undefined;
  const work = ownerWorkIdentity(task), raw = task?.raw, source = raw?.ownerWork;
  if (!work?.runId || work.epoch < 1 || task?.status !== 'running' || raw?.status !== 'running' ||
      raw.kind !== 'assistant' || raw.executionMode !== 'capabilities' || raw.contextAvailable !== true ||
      raw.contextWithdrawn === true || raw.sourceCurrentReadRequired === true || raw.taskProjection === 'source_unavailable' ||
      raw.origin != null || raw.parentTaskId != null || raw.teamRunId != null || raw.memberId != null || raw.teamAssignment != null ||
      raw.developmentContext != null || raw.ownerPhoneControl != null || raw.ownerDeviceWork != null ||
      typeof raw.conversationId !== 'string' || !raw.conversationId || source?.format !== 'pocketworld.owner-work.v1' ||
      source.workTaskId !== taskId || source.conversationId !== raw.conversationId) return;
  return task;
}

function fileNoticeScope(task: TaskHeader | undefined, windowKey: string) {
  const work = ownerWorkIdentity(task);
  return work ? JSON.stringify([windowKey, workIdentityKey(work)]) : '';
}

const nativeUuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);

/** The original operation reports a committed Book entry, not a fresh file read or a completed Work. */
function localTextSaveWindow(task: TaskHeader | undefined): { settledHead: number; latest?: SavedFileFact } | undefined {
  const raw = task?.raw;
  if (!task || raw?.taskProjection !== 'detail_window' || raw.auditBodyIncluded !== true || raw.taskCoreReadLimited !== false) return;
  try {
    const page = taskAuditPage(raw.historyOperationPage, task.id, 'operations');
    let settledHead = 0;
    for (const op of page.entries) {
      if (op.runId !== raw.runId || op.tool !== 'attachments_create_text' || op.state !== 'receipt' || !nativeUuid(op.operationId)) continue;
      if (!settledHead) settledHead = op.position;
      const receipt = op.receipt, data = receipt?.data, outcome = op.toolOutcome;
      if (op.status !== 'available' || op.readLimited === true || op.bodyAvailable === false ||
          outcome?.format !== 'pocketworld.tool-outcome.v1' || outcome.kind !== 'success' || outcome.completion !== 'terminal' ||
          receipt?.tool !== op.tool || receipt.hostOperationId !== op.operationId || receipt.status !== 'created' ||
          receipt.ok !== true || receipt.accepted !== true || receipt.automaticReplayAllowed !== false ||
          !nativeUuid(data?.id) || typeof data.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(data.sha256)) continue;
      return { settledHead, latest: { position: op.position, id: data.id, sha256: data.sha256 } };
    }
    return { settledHead };
  } catch { /* No new file cue from an unreadable audit window. */ }
}

const hasExactCurrentFile = (files: readonly TaskResultFile[] | undefined, notice: SavedFileFact) =>
  files?.some(file => file.runProvenance === 'current' && file.id === notice.id && file.sha256 === notice.sha256) === true;

export function usableResultFile(value: unknown): value is TaskResultFile {
  if (!value || typeof value !== 'object') return false;
  const file = value as Partial<TaskResultFile>;
  return typeof file.id === 'string' && !!file.id && file.id.length <= 240 && !file.id.includes('\0') &&
    typeof file.sha256 === 'string' && /^[a-f0-9]{64}$/.test(file.sha256) && typeof file.name === 'string' && !!file.name &&
    typeof file.mimeType === 'string' && typeof file.size === 'number' && Number.isSafeInteger(file.size) && file.size >= 0;
}

function pdfExcerptLabel(file: TaskResultFile) {
  const start = file.pdfExcerptStart;
  if (file.mimeType !== 'text/plain' || !start || !Number.isSafeInteger(start.page) || start.page < 1 || start.page > 1_000_000 ||
      !Number.isSafeInteger(start.chunk) || start.chunk < 0 || start.chunk > 1_000_000 ||
      (start.method !== 'text_layer' && start.method !== 'ocr')) return '';
  return `PDF ${start.method === 'ocr' ? 'OCR' : '文字层'}摘录 · 当时从第 ${start.page} 页开始读取`;
}

export function TaskResultFiles({ taskId, active, supported, readIdentity, completedRunId, completedEpoch, showEmptyBody, onOpen, onBrowse, onOriginalRequest }: {
  taskId: string;
  active: boolean;
  supported: boolean;
  readIdentity: string;
  completedRunId?: string;
  completedEpoch?: number;
  showEmptyBody?: boolean;
  onOpen: (file: TaskResultFile, target: HTMLButtonElement) => void;
  onBrowse: () => void;
  onOriginalRequest?: () => void;
}) {
  const heading = useId(), otherHeading = useId();
  const [page, setPage] = useState<FilePage | null>(null);
  const [request, setRequest] = useState<{ retry: number; file?: FileCheck }>({ retry: 0 });
  const [notices, setNotices] = useState<FileNotices | undefined>(undefined);
  const attemptedFileCheck = useRef(0);
  const [expandedSources, setExpandedSources] = useState('');
  const windowEpoch = useSyncExternalStore(bridge.subscribe, () => {
    const view = bridge.getState().view as { taskId?: string; section?: string };
    return !view?.section && view?.taskId === taskId ? bridge.getDocumentEpoch() : -1;
  });
  const readingFile = useSyncExternalStore(bridge.subscribe, () => {
    const view = bridge.getState().state.attachmentWorkspace;
    return view?.open === true && view.taskId === taskId;
  });
  const liveWork = useSyncExternalStore(bridge.subscribe, () => runningFileWork(taskId));
  const fileWindow = JSON.stringify([taskId, windowEpoch, readIdentity]);
  const noticeScope = active && supported && windowEpoch >= 0 ? fileNoticeScope(liveWork, fileWindow) : '';
  const saves = useMemo(() => localTextSaveWindow(liveWork), [liveWork]);
  const key = JSON.stringify([fileWindow, request.retry]);
  const current = page?.key === key ? page : null;
  const busy = active && supported && !current;
  const checkingFile = request.file?.window === fileWindow ? request.file.notice : undefined;
  useEffect(() => {
    setNotices(previous => {
      if (!noticeScope) return undefined;
      const inScope = previous?.scope === noticeScope ? previous : undefined;
      // The first actual audit window is a baseline; do not call pre-existing receipts new.
      if (!inScope) return saves ? { scope: noticeScope, through: saves.settledHead } : undefined;
      const latest = saves?.latest;
      if (latest && latest.position > inScope.through) return {
        scope: noticeScope, through: latest.position,
        pending: hasExactCurrentFile(current?.files, latest) ? undefined : { ...latest, scope: noticeScope },
      };
      return inScope.pending && hasExactCurrentFile(current?.files, inScope.pending) ? { ...inScope, pending: undefined } : inScope;
    });
  }, [noticeScope, saves, current]);
  useEffect(() => {
    if (!active || !supported || windowEpoch < 0 || readingFile) return;
    let alive = true;
    let sequence = 0;
    const read = (wanted?: FileNotice) => {
      const request = ++sequence;
      const stillOpen = () => {
        const native = bridge.getState(), view = native.view as { taskId?: string; section?: string } | undefined;
        return alive && sequence === request && !document.hidden && bridge.getDocumentEpoch() === windowEpoch && !view?.section && view?.taskId === taskId &&
          (!wanted || !native.state.attachmentWorkspace?.open && fileNoticeScope(runningFileWork(taskId), fileWindow) === wanted.scope);
      };
      if (!stillOpen()) return;
      void bridge.command('tasks.resultFiles', { taskId }).then(value => {
        if (!stillOpen()) return;
        if (value?.taskId !== taskId || !Array.isArray(value.files) || value.files.length > 8 || typeof value.readLimited !== 'boolean') {
          throw new Error('文件列表与原工作不符，请重新读取。');
        }
        // Older hosts only returned outputs. Never infer inputs from a message body or filename.
        const sources: unknown = value.sourceFiles === undefined ? [] : value.sourceFiles;
        if (!Array.isArray(sources) || sources.length > 4) throw new Error('来源文件尚未核对，请到原工作文件查看。');
        // Older hosts can still open their exact files, but cannot attest to an
        // empty completed run. Never use list order, a filename or the work status as that proof.
        const proof = value.completedFileRun;
        const completedFileRun = !!completedRunId && Number.isSafeInteger(completedEpoch) &&
          proof?.runId === completedRunId && proof.epoch === completedEpoch;
        if (completedRunId && proof !== undefined && !completedFileRun) throw new Error('工作轮次已变化，请重新读取原工作文件。');
        const files: TaskResultFile[] = value.files.filter(usableResultFile);
        const inputs = sources.filter(usableResultFile);
        setPage({ key, files, sources: inputs, completedFileRun, checkedFile: wanted, sourceUnavailable: value.sourceUnavailable === true || inputs.length !== sources.length,
          hasRequestAttachments: value.hasRequestAttachments === true,
          limited: value.readLimited || files.length !== value.files.length || value.moreSourceFiles === true });
      }).catch(error => {
        if (stillOpen()) setPage({ key, files: [], sources: [], limited: false, error: error instanceof Error ? error.message : '无法读取这件工作的文件。' });
      });
    };
    // A refresh for this notice belongs to its click, not ordinary reader/foreground refreshes.
    const wanted = attemptedFileCheck.current !== request.retry ? checkingFile : undefined;
    if (wanted) attemptedFileCheck.current = request.retry;
    read(wanted);
    const visible = () => read();
    document.addEventListener('visibilitychange', visible);
    return () => {
      alive = false;
      document.removeEventListener('visibilitychange', visible);
    };
  }, [active, supported, key, taskId, windowEpoch, readingFile, checkingFile, fileWindow, request.retry, completedRunId, completedEpoch]);

  if (windowEpoch < 0) return null;
  const outputs = current?.files ?? [], sources = current?.sources ?? [];
  // A native run marker, not the name or list order, distinguishes current
  // output. Keep the same keyed file rows in each group as the list updates.
  const currentOutputs = outputs.filter(file => file.runProvenance === 'current');
  const otherOutputs = outputs.filter(file => file.runProvenance !== 'current');
  const mixedOutputs = currentOutputs.length > 0 && otherOutputs.length > 0;
  const earlier = otherOutputs.filter(file => file.runProvenance === 'earlier');
  const unrecorded = otherOutputs.filter(file => file.runProvenance !== 'earlier');
  const finished = current?.completedFileRun === true;
  const hasSources = !!(sources.length || current?.sourceUnavailable || current?.hasRequestAttachments);
  const notice = notices?.scope === noticeScope ? notices.pending : undefined;
  const pendingFile = notice && !hasExactCurrentFile(current?.files, notice) ? notice : undefined;
  const fileCue = !readingFile && pendingFile && (current || checkingFile === pendingFile) ? <div className="pw-action-row">
    <Button variant="ghost" disabled={busy} onClick={() => {
      if (document.hidden || bridge.getDocumentEpoch() !== windowEpoch || bridge.getState().state.attachmentWorkspace?.open ||
          fileNoticeScope(runningFileWork(taskId), fileWindow) !== pendingFile.scope) return;
      setRequest(value => ({ retry: value.retry + 1, file: { window: fileWindow, notice: pendingFile } }));
    }}>{busy ? '正在核对新文件…' : '有新文件，查看'}</Button>
    {current?.checkedFile === pendingFile && <p className="pw-reading-note" role="status">这份原文件暂未在当前清单里核对到。</p>}
  </div> : null;
  if (supported && !busy && current && !outputs.length && !hasSources && !current.limited && !current.error)
    return showEmptyBody && !pendingFile ? <p className="pw-reading-note" role="status">{finished
      ? '工作已结束，当前没有可读取的结果正文或文件。' : '当前工作记录没有可读取的结果正文。'}</p> : fileCue;
  const fileList = (items: TaskResultFile[], input: boolean) => <div className="pw-outcome-files">{items.map(file => {
      const format = attachmentFileFormat(file.mimeType,file.name).label;
      const savedTransfer = file.sourceRunEvidence === 'original_ssh_file_download' && file.runProvenance === 'current' && file.mimeType === 'application/octet-stream';
      const excerpt = pdfExcerptLabel(file);
      const provenance = savedTransfer ? '本次传输已保存到手机' : fileRunProvenance(file.runProvenance, file.sourceRunEvidence) || '生成轮次未记录';
      const metadata = [format, input ? '' : provenance, input ? '' : excerpt, fileBytes(file.size)].filter(Boolean).join(' · ');
      return <Button key={`${file.id}:${file.sha256}`} variant="secondary" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight}
      aria-label={`${input ? '打开来源文件' : '打开原件'}：${file.name}，${metadata}${!input && file.hasSavedSupplement === true ? '，有补充草稿' : ''}`} disabled={!active} onClick={(event: MouseEvent<HTMLButtonElement>) => onOpen(file, event.currentTarget)}>
        <span className="pw-outcome-file-copy"><span dir="auto">{file.name}</span><small>{metadata}{!input && file.hasSavedSupplement === true && ' · 有补充草稿'}</small></span>
      </Button>;
    })}</div>;
  const sourceContent = <>
    {sources.length > 0 && fileList(sources, true)}
    {current?.sourceUnavailable && <p className="pw-reading-note" role="status">有来源文件的原版本暂不可读取。</p>}
    {current?.hasRequestAttachments && (onOriginalRequest
      ? <Button variant="ghost" disabled={!active} onClick={onOriginalRequest}>回原话题找交办附件</Button>
      : <p className="pw-reading-note">交办消息含有附件，可回原话题查看。</p>)}
  </>;
  return <section className="pw-outcome-deliverables" aria-label={mixedOutputs ? '已保存的文件成果' : undefined}
    aria-labelledby={mixedOutputs ? undefined : heading} aria-busy={busy || undefined}>
    <div className="pw-reading-section-heading"><h4 id={heading}>{finished
      ? currentOutputs.length ? '本轮已保存的文件' : '本轮文件'
      : mixedOutputs ? '本轮已保存的文件' : outputs.length ? '已保存的文件成果' : hasSources ? '来源文件' : '工作文件'}</h4></div>
    {busy && !fileCue && <p className="pw-reading-note" role="status">正在读取工作文件…</p>}
    {current?.error && <p className="pw-reading-note pw-error" role="alert">{current.error}</p>}
    {currentOutputs.length > 0 && fileList(currentOutputs, false)}
    {fileCue}
    {finished && !currentOutputs.length && !current?.limited && !pendingFile && <p className="pw-reading-note" role="status">当前清单没有可确认为本轮成果的文件。</p>}
    {finished && otherOutputs.length > 0 ? <Accordion key={fileWindow} variant="divided" className="pw-reading-disclosures" aria-label="其他已保存文件">
      {earlier.length > 0 && <AccordionItem id="earlier-files" title={`较早轮次的文件（${earlier.length}）`}>{fileList(earlier, false)}</AccordionItem>}
      {unrecorded.length > 0 && <AccordionItem id="unrecorded-files" title={`生成轮次未记录的文件（${unrecorded.length}）`}>{fileList(unrecorded, false)}</AccordionItem>}
    </Accordion> : otherOutputs.length > 0 && <section className="pw-work-other-files" data-secondary={mixedOutputs || undefined}
      aria-labelledby={mixedOutputs ? otherHeading : undefined}>
      {mixedOutputs && <div className="pw-reading-section-heading">
        <h4 id={otherHeading}>其他已保存文件</h4>
        <span>{[earlier.length > 0 ? `较早轮次 ${earlier.length} 份` : '', unrecorded.length > 0 ? `轮次未记录 ${unrecorded.length} 份` : ''].filter(Boolean).join(' · ')}</span>
      </div>}
      {fileList(otherOutputs, false)}
    </section>}
    {hasSources && (outputs.length || finished ? <Accordion variant="divided" className="pw-reading-disclosures"
      expandedKeys={expandedSources === taskId ? ['source-files'] : []} onExpandedChange={keys => setExpandedSources(keys.has('source-files') ? taskId : '')}>
      <AccordionItem id="source-files" title="来源文件">{sourceContent}</AccordionItem></Accordion> : sourceContent)}
    {current?.limited && <p className="pw-reading-note" role="status">文件清单未列全。</p>}
    {(!supported || current?.limited || current?.error || current?.sourceUnavailable) && <div className="pw-action-row">
      <Button variant="ghost" disabled={!active} onClick={onBrowse}>查看原工作文件</Button>
      {current?.error && !fileCue && <Button variant="ghost" disabled={!active} onClick={() => setRequest(value => ({ retry: value.retry + 1 }))}>重新读取</Button>}
    </div>}
  </section>;
}
