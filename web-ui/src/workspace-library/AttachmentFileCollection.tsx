import { RiFileImageLine } from '@remixicon/react';
import { Button } from '../forms-library';
import { exactFileTarget, fileBytes, type FileJson } from '../attachment-workspace';
import { MediaCarousel } from './MediaCarousel';

export function AttachmentFileCollection({ label, files, busy, choosing, onSelect }: { label: string; files: FileJson[]; busy: boolean; choosing: boolean; onSelect: (target: FileJson) => void }) {
  if (!files.length) return null;
  const images = files.filter(file => String(file.mimeType).startsWith('image/'));
  const carousel = images.length >= 2;
  const remaining = carousel ? files.filter(file => !images.includes(file)) : files;
  return <section className="pw-outcome-files" aria-label={label}>
    {carousel ? <MediaCarousel label={label} items={images.map((item, index) => {
      const target = exactFileTarget(item);
      return { id: `${item.id}:${item.sha256}:${index}`, content: <Button variant="ghost" className="pw-file-collection-card" disabled={busy || !target} onClick={() => target && onSelect(target)}><span className="pw-collection-icon" aria-hidden="true"><RiFileImageLine size={44} /></span><strong>{item.name || '图片原件'}</strong><small>图片原件 · {fileBytes(item.size ?? item.sizeBytes)}</small><small>{target ? choosing ? '选择这个来源' : '打开固定原件' : '原文件或版本当前不可用'}</small></Button> };
    })} /> : <h3>{label}</h3>}
    {remaining.map((item, index) => { const target = exactFileTarget(item); return <button type="button" key={`${item.id}:${item.sha256}:${index}`} disabled={busy || !target} onClick={() => target && onSelect(target)}><span><strong>{item.name || '原文件'}</strong><small>{target ? `${item.mimeType || '文件'} · ${fileBytes(item.size ?? item.sizeBytes)}` : '原文件或版本当前不可用'}</small></span><span>{choosing ? '关联' : '查看'}</span></button>; })}
  </section>;
}
