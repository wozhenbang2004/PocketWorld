
import { IconChevronRight, IconFile, IconFileImage } from '../icons';
import { Button } from '../forms-library';
import { attachmentFileFormat, documentVersionKey, exactFileTarget, fileBytes, fileRunProvenance, type FileJson, type SavedDocumentVersion } from '../attachment-workspace';

export function AttachmentFileCollection({ label, files, busy, choosing, opensOriginal, versions, onSelect }: { label: string; files: FileJson[]; busy: boolean; choosing: boolean; opensOriginal?: boolean | ((file: FileJson) => boolean); versions?: ReadonlyMap<string,SavedDocumentVersion>; onSelect: (target: NonNullable<ReturnType<typeof exactFileTarget>>, row: HTMLButtonElement, direct: boolean) => void }) {
  if (!files.length) return null;
  return <section className="pw-outcome-files" aria-label={label} data-attachment-collection={label}>
    <h3>{label}</h3>
    <div className="pw-file-collection-list">{files.map((item, index) => {
      const target = exactFileTarget(item), mime = String(item.mimeType || ''), size = item.size ?? item.sizeBytes;
      const type = item.sourceKind === 'document_source' ? '文档编辑源' : attachmentFileFormat(mime,item.name).label;
      const metadata = [type, typeof size === 'number' && Number.isFinite(size) ? fileBytes(size) : '大小未记录', fileRunProvenance(item.runProvenance, item.sourceRunEvidence)].filter(Boolean).join(' · ');
      const version = versions?.get(documentVersionKey(item));
      const identity = version?['版本 '+version.label,version.parent&&'续自版本 '+version.parent,version.savedAt].filter(Boolean).join('，'):'';
      const direct = typeof opensOriginal === 'function' ? opensOriginal(item) : opensOriginal === true;
      const action = choosing ? '选择来源' : direct ? '查看原件' : '查看版本';
      return <Button key={`${item.id}:${item.sha256}:${index}`} variant="ghost" className="pw-file-collection-row"
        leadingIcon={mime.startsWith('image/') ? IconFileImage : IconFile} trailingIcon={IconChevronRight}
        aria-label={[action+'：'+(item.name || '原文件'),identity,metadata].filter(Boolean).join('，')}
        data-attachment-file-id={target?.fileId} data-attachment-file-sha={target?.sha256}
        disabled={busy || !target} onClick={event => target && onSelect(target, event.currentTarget, direct)}>
          <span className="pw-file-collection-copy">
            <strong>{item.name || '原文件'}</strong><small>{metadata}</small>
            {version&&<small><span className="pw-preview-selection-state">版本 <code>{version.label}</code></span>
              {version.parent&&<> · 续自 <code>{version.parent}</code></>}{version.savedAt&&<> · {version.savedAt}</>}</small>}
            {!target && <small>原文件或版本当前不可用</small>}
          </span>
          <span className="pw-file-collection-action">{target ? action : '不可用'}</span>
        </Button>;
    })}</div>
  </section>;
}
