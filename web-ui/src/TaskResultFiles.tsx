import { useEffect, useId, useState, type MouseEvent } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { IconChevronRight, IconFile } from './icons';

export type TaskResultFile = { id: string; sha256: string; name: string; mimeType: string; size: number };

type FilePage = { key: string; files: TaskResultFile[]; limited: boolean; error?: string };

export function TaskResultFiles({ taskId, active, supported, readIdentity, onOpen, onBrowse }: {
  taskId: string;
  active: boolean;
  supported: boolean;
  readIdentity: string;
  onOpen: (file: TaskResultFile, target: HTMLButtonElement) => void;
  onBrowse: () => void;
}) {
  const heading = useId();
  const [page, setPage] = useState<FilePage | null>(null);
  const [retry, setRetry] = useState(0);
  const key = JSON.stringify([taskId, readIdentity, retry]);
  const current = page?.key === key ? page : null;
  const busy = active && supported && !current;
  useEffect(() => {
    if (!active || !supported) return;
    let alive = true;
    let sequence = 0;
    const epoch = bridge.getDocumentEpoch();
    const read = () => {
      const request = ++sequence;
      if (document.hidden) return;
      const stillOpen = () => {
        const view = bridge.getState().view as { taskId?: string } | undefined;
        return alive && sequence === request && !document.hidden && bridge.getDocumentEpoch() === epoch && view?.taskId === taskId;
      };
      void bridge.command('tasks.resultFiles', { taskId }).then(value => {
        if (!stillOpen()) return;
        if (value?.taskId !== taskId || !Array.isArray(value.files) || value.files.length > 8 || typeof value.readLimited !== 'boolean') {
          throw new Error('文件列表与原工作不符，请重新读取。');
        }
        setPage({ key, files: value.files, limited: value.readLimited });
      }).catch(error => {
        if (stillOpen()) setPage({ key, files: [], limited: false, error: error instanceof Error ? error.message : '无法读取这件工作的文件。' });
      });
    };
    read();
    document.addEventListener('visibilitychange', read);
    return () => {
      alive = false;
      document.removeEventListener('visibilitychange', read);
    };
  }, [active, supported, key, taskId]);

  if (supported && !busy && current && !current.files.length && !current.limited && !current.error) return null;
  return <section className="pw-outcome-deliverables" aria-labelledby={heading} aria-busy={busy || undefined}>
    <div className="pw-reading-section-heading"><h4 id={heading}>工作文件</h4></div>
    {busy && <p className="pw-reading-note" role="status">正在读取已保存的文件…</p>}
    {current?.error && <p className="pw-reading-note pw-error" role="alert">{current.error}</p>}
    {current?.files.length ? <div className="pw-outcome-files">{current.files.map(file =>
      <Button key={`${file.id}:${file.sha256}`} variant="secondary" className="pw-outcome-file" leadingIcon={IconFile} trailingIcon={IconChevronRight}
        disabled={!active} onClick={(event: MouseEvent<HTMLButtonElement>) => onOpen(file, event.currentTarget)}>
        <span className="pw-outcome-file-copy"><span dir="auto">{file.name}</span><small>打开原件</small></span>
      </Button>)}</div> : null}
    {current?.limited && <p className="pw-reading-note" role="status">部分文件未在此处列出。</p>}
    {(!supported || current?.limited || current?.error) && <div className="pw-action-row">
      <Button variant="ghost" disabled={!active} onClick={onBrowse}>查看原工作文件</Button>
      {current?.error && <Button variant="ghost" disabled={!active} onClick={() => setRetry(value => value + 1)}>重新读取</Button>}
    </div>}
  </section>;
}
