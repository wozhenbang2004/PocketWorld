import { useEffect, useId, useState, useSyncExternalStore, type MouseEvent } from 'react';
import { attachmentFileFormat, fileBytes, fileRunProvenance } from './attachment-workspace';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { IconChevronRight, IconFile } from './icons';

type PdfExcerptStart = { page: number; chunk: number; method: 'text_layer' | 'ocr' };
export type TaskResultFile = { id: string; sha256: string; name: string; mimeType: string; size: number; runProvenance?: 'current' | 'earlier' | 'unrecorded'; sourceRunEvidence?: 'original_device_file_receive' | 'original_ssh_file_download'; hasSavedSupplement?: boolean; pdfExcerptStart?: PdfExcerptStart };

type FilePage = { key: string; files: TaskResultFile[]; sources: TaskResultFile[]; limited: boolean; sourceUnavailable?: boolean; hasRequestAttachments?: boolean; error?: string };

function usableFile(value: unknown): value is TaskResultFile {
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

export function TaskResultFiles({ taskId, active, supported, readIdentity, onOpen, onBrowse, onOriginalRequest }: {
  taskId: string;
  active: boolean;
  supported: boolean;
  readIdentity: string;
  onOpen: (file: TaskResultFile, target: HTMLButtonElement) => void;
  onBrowse: () => void;
  onOriginalRequest?: () => void;
}) {
  const heading = useId();
  const [page, setPage] = useState<FilePage | null>(null);
  const [retry, setRetry] = useState(0);
  const [expandedSources, setExpandedSources] = useState('');
  const windowEpoch = useSyncExternalStore(bridge.subscribe, () => {
    const view = bridge.getState().view as { taskId?: string; section?: string };
    return !view?.section && view?.taskId === taskId ? bridge.getDocumentEpoch() : -1;
  });
  const readingFile = useSyncExternalStore(bridge.subscribe, () => {
    const view = bridge.getState().state.attachmentWorkspace;
    return view?.open === true && view.taskId === taskId;
  });
  const key = JSON.stringify([taskId, windowEpoch, readIdentity, retry]);
  const current = page?.key === key ? page : null;
  const busy = active && supported && !current;
  useEffect(() => {
    if (!active || !supported || windowEpoch < 0 || readingFile) return;
    let alive = true;
    let sequence = 0;
    const read = () => {
      const request = ++sequence;
      const stillOpen = () => {
        const view = bridge.getState().view as { taskId?: string; section?: string } | undefined;
        return alive && sequence === request && !document.hidden && bridge.getDocumentEpoch() === windowEpoch && !view?.section && view?.taskId === taskId;
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
        const files: TaskResultFile[] = value.files.filter(usableFile);
        const inputs = sources.filter(usableFile);
        setPage({ key, files, sources: inputs, sourceUnavailable: value.sourceUnavailable === true || inputs.length !== sources.length,
          hasRequestAttachments: value.hasRequestAttachments === true,
          limited: value.readLimited || files.length !== value.files.length || value.moreSourceFiles === true });
      }).catch(error => {
        if (stillOpen()) setPage({ key, files: [], sources: [], limited: false, error: error instanceof Error ? error.message : '无法读取这件工作的文件。' });
      });
    };
    read();
    document.addEventListener('visibilitychange', read);
    return () => {
      alive = false;
      document.removeEventListener('visibilitychange', read);
    };
  }, [active, supported, key, taskId, windowEpoch, readingFile]);

  if (windowEpoch < 0) return null;
  const outputs = current?.files ?? [], sources = current?.sources ?? [];
  const hasSources = !!(sources.length || current?.sourceUnavailable || current?.hasRequestAttachments);
  if (supported && !busy && current && !outputs.length && !hasSources && !current.limited && !current.error) return null;
  const fileList = (items: TaskResultFile[], input: boolean) => <div className="pw-outcome-files">{items.map(file => {
      const format = attachmentFileFormat(file.mimeType,file.name).label;
      const savedTransfer = file.sourceRunEvidence === 'original_ssh_file_download' && file.runProvenance === 'current' && file.mimeType === 'application/octet-stream';
      const provenance = savedTransfer ? '本次传输已保存到手机' : pdfExcerptLabel(file) || fileRunProvenance(file.runProvenance, file.sourceRunEvidence) || '生成轮次未记录';
      const metadata = [format, input ? '' : provenance, fileBytes(file.size)].filter(Boolean).join(' · ');
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
  return <section className="pw-outcome-deliverables" aria-labelledby={heading} aria-busy={busy || undefined}>
    <div className="pw-reading-section-heading"><h4 id={heading}>{outputs.length ? '已保存的文件成果' : hasSources ? '来源文件' : '工作文件'}</h4></div>
    {busy && <p className="pw-reading-note" role="status">正在读取工作文件…</p>}
    {current?.error && <p className="pw-reading-note pw-error" role="alert">{current.error}</p>}
    {outputs.length > 0 && fileList(outputs, false)}
    {hasSources && (outputs.length ? <Accordion variant="divided" className="pw-reading-disclosures"
      expandedKeys={expandedSources === taskId ? ['source-files'] : []} onExpandedChange={keys => setExpandedSources(keys.has('source-files') ? taskId : '')}>
      <AccordionItem id="source-files" title="来源文件">{sourceContent}</AccordionItem></Accordion> : sourceContent)}
    {current?.limited && <p className="pw-reading-note" role="status">文件清单未列全。</p>}
    {(!supported || current?.limited || current?.error || current?.sourceUnavailable) && <div className="pw-action-row">
      <Button variant="ghost" disabled={!active} onClick={onBrowse}>查看原工作文件</Button>
      {current?.error && <Button variant="ghost" disabled={!active} onClick={() => setRetry(value => value + 1)}>重新读取</Button>}
    </div>}
  </section>;
}
