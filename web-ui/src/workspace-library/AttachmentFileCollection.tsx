
import { IconChevronRight, IconFile, IconFileImage } from '../icons';
import { Button } from '../forms-library';
import { exactFileTarget, fileBytes, type FileJson } from '../attachment-workspace';

const fileTypes: Record<string, string> = {
  'application/pdf': 'PDF', 'text/plain': '文本', 'text/markdown': 'Markdown', 'application/json': 'JSON', 'application/zip': 'ZIP',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'Word 文档',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'Excel 表格',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'PowerPoint 演示文稿',
};

export function AttachmentFileCollection({ label, files, busy, choosing, onSelect }: { label: string; files: FileJson[]; busy: boolean; choosing: boolean; onSelect: (target: FileJson) => void }) {
  if (!files.length) return null;
  return <section className="pw-outcome-files" aria-label={label}>
    <h3>{label}</h3>
    <div className="pw-file-collection-list">{files.map((item, index) => {
      const target = exactFileTarget(item), mime = String(item.mimeType || ''), size = item.size ?? item.sizeBytes;
      const type = fileTypes[mime] || (mime.startsWith('image/') ? '图片' : mime.startsWith('video/') ? '视频' : mime.startsWith('audio/') ? '音频' : mime || '文件');
      const action = choosing ? '选择来源' : '查看版本';
      return <Button key={`${item.id}:${item.sha256}:${index}`} variant="ghost" className="pw-file-collection-row" leadingIcon={mime.startsWith('image/') ? IconFileImage : IconFile} trailingIcon={IconChevronRight} aria-label={`${action}：${item.name || '原文件'}`} disabled={busy || !target} onClick={() => target && onSelect(target)}><span className="pw-file-collection-copy"><strong>{item.name || '原文件'}</strong><small>{type} · {typeof size === 'number' && Number.isFinite(size) ? fileBytes(size) : '大小未记录'}</small>{!target && <small>原文件或版本当前不可用</small>}</span><span className="pw-file-collection-action">{target ? action : '不可用'}</span></Button>;
    })}</div>
  </section>;
}
