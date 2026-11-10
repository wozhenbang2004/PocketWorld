import { useEffect, useRef, useState } from 'react';
import { IconCloudUpload, IconFileText } from '../icons';

/** PW-36. Controlled adaptation of BoardUI FileUpload. The rounded progress
 * path, measured perimeter and content layers come from the source component.
 * Random progress, completion timers and DOM-file ingestion are removed: the
 * existing Android SAF/import owner is the sole file and result authority.
 */
function ringPath(width: number, height: number, inset: number, radius: number) {
  const x0 = inset, y0 = inset, x1 = width - inset, y1 = height - inset, mid = width / 2;
  const arc = (x: number, y: number) => `A ${radius} ${radius} 0 0 1 ${x} ${y}`;
  return [`M ${mid} ${y0}`, `H ${x1 - radius}`, arc(x1, y0 + radius), `V ${y1 - radius}`, arc(x1 - radius, y1), `H ${x0 + radius}`, arc(x0, y1 - radius), `V ${y0 + radius}`, arc(x0 + radius, y0), 'Z'].join(' ');
}
export function formatFileSize(bytes: number) {
  if (bytes >= 1024 * 1024) { const mb = bytes / (1024 * 1024); return `${mb >= 10 ? Math.round(mb) : Math.round(mb * 10) / 10} MB`; }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
export function HostFileUpload({ onChoose, status = 'idle', file, error, progress, disabled = false, label = '选择文件', statusDescription }: {
  onChoose: () => void; status?: 'idle' | 'importing' | 'complete' | 'unknown' | 'error';
  file?: { name: string; size?: number }; error?: string; progress?: number; disabled?: boolean; label?: string;
  statusDescription?: string;
}) {
  const boxRef = useRef<HTMLButtonElement>(null);
  const [box, setBox] = useState({ width: 533, height: 164 });
  useEffect(() => { const el = boxRef.current; if (!el) return; const observer = new ResizeObserver(([entry]) => { if (entry.contentRect.width > 0) setBox({ width: entry.contentRect.width, height: entry.contentRect.height }); }); observer.observe(el); return () => observer.disconnect(); }, []);
  const busy = status === 'importing';
  const hasProgress = typeof progress === 'number' && Number.isFinite(progress) && progress >= 0 && progress <= 100;
  return <div className="pw-host-upload-wrap">
    <button ref={boxRef} type="button" className="pw-host-upload" onClick={onChoose} disabled={disabled || busy} aria-busy={busy || undefined}>
      <svg className="pw-upload-ring" aria-hidden viewBox={`0 0 ${box.width} ${box.height}`} preserveAspectRatio="none"><rect x={1} y={1} width={Math.max(0, box.width - 2)} height={Math.max(0, box.height - 2)} rx={15} fill="none" stroke="currentColor" strokeWidth={1} />
        {hasProgress && <path d={ringPath(box.width, box.height, 1, 15)} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" pathLength={100} strokeDasharray={`${progress} 200`} />}
      </svg>
      <span className="pw-upload-icon">{file ? <IconFileText size={24} aria-hidden /> : <IconCloudUpload size={24} aria-hidden />}</span>
      <span className="pw-upload-title">{file?.name ?? label}</span>
      <span className="pw-upload-description">{statusDescription ?? (status === 'importing' ? '正在读取原文件…' : status === 'complete' ? '已收到导入回执' : status === 'unknown' ? '提交结果待核对，请勿重复导入' : file?.size != null ? formatFileSize(file.size) : '使用系统文件选择器，保留文件来源')}</span>
    </button>
    {error && <p className="pw-field-error" role="alert">{error}</p>}
  </div>;
}
