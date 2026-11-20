import { Button, CheckboxField, IconButton } from './forms-library';
import { Drawer } from './shell-library';
import { PressButton, ProgressBar } from './library';
import { IconAdd, IconClose, IconShieldCheck } from './icons';
import { exactFileTarget, fileBytes, fileObject, fileRows, type FileJson } from './attachment-workspace';

// Mirror the definite limits in AttachmentRequestProjection; final payload/budget stay native.
const mib = 1024 * 1024;
const maxSelections = 4;
const imageFormats = new Set(['image/png', 'image/jpeg', 'image/webp']);
export const attachmentPagingTool = 'attachments_read_document';

function fileUse(file: FileJson, model: FileJson): { reason?: string; hint?: string; paged?: true } {
  if (file.outputSourceRetired === true) return { reason: '这个版本已撤回，不能作为本条的附件。' };
  if (!exactFileTarget(file) || typeof file.mimeType !== 'string' || !Number.isSafeInteger(file.size))
    return { reason: '尚未核对到原文件，不能加入这条消息。' };
  if (file.size < 1) return { reason: '这个文件没有可提供的内容。' };
  if (file.size > 20 * mib) return { reason: '超过 20 MiB 的附件大小上限。' };
  if (file.mimeType.startsWith('image/')) {
    if (!imageFormats.has(file.mimeType)) return { reason: '普通对话只接收 PNG、JPEG 或 WebP 图片。' };
    if (file.size > 4 * mib) return { reason: '图片超过 4 MiB，请换一份较小的图片。' };
    if (model.modelRoute === 'account' && model.sendScreenshots === false)
      return { reason: '当前账号连接未允许发送图片。可以改附文字。' };
    if (model.supportsImageInput !== true) return { reason: model.available === true
      ? '当前回复的模型未确认支持图片。可先补充文字。'
      : '所选模型未确认支持图片。可回输入框核对模型。' };
    return {};
  }
  if (file.mimeType.startsWith('video/') || file.mimeType.startsWith('audio/')) {
    const text = '普通对话不会读取音视频内容，可改附文字稿或截图。';
    // The paged document route explicitly rejects media. Smaller files still
    // pass through native content detection; metadata alone cannot reject those.
    return file.size > 2 * mib ? { reason: text } : { hint: text };
  }
  if (file.size > 2 * mib) return { paged: true };
  return {};
}

type Props = {
  open: boolean; inventory: unknown; selected: unknown; inputModel: unknown;
  importing: boolean; onClose: () => void; onImport?: () => void;
  pagingDenied: boolean; onReviewPagingRule?: () => void; openingPagingRule?: boolean;
  onToggle?: (id: string, sha256: string, selected: boolean) => void;
  original?: {fileId:string;sha256:string;name:string;draftLabel:string};
};

/** Keep fit guidance with the original picker. Native send still verifies source, model and scope. */
export function ComposerAttachmentPicker({ open, inventory, selected, inputModel, importing, pagingDenied, onReviewPagingRule, openingPagingRule, onClose, onImport, onToggle, original }: Props) {
  const catalog = fileObject(inventory);
  const files = fileRows(catalog.attachments).filter(row=>!original||row.id===original.fileId&&row.sha256===original.sha256);
  const choices = fileRows(selected), model = fileObject(inputModel);
  const rows = files.map(file => ({ file, use: fileUse(file, model) }));
  const incomplete = fileObject(catalog.indexDiagnostics).incomplete === true;
  return <Drawer.Root open={open} onOpenChange={next => { if (!next) onClose(); }} shouldScaleBackground={false}>
    <Drawer.Portal>
      <Drawer.Overlay className="pw-overlay"/>
      <Drawer.Content className="pw-drawer pw-attachment-drawer" aria-describedby="pw-attachment-description">
        <div className="pw-drawer-handle"/>
        <header className="pw-drawer-header">
          <div>
            <Drawer.Title>{original?'选作草稿附件':'选择附件'}</Drawer.Title>
            <Drawer.Description id="pw-attachment-description">{original&&`「${original.draftLabel}」 · `}已选 {choices.length}/{maxSelections} · 只加入草稿，不发送。</Drawer.Description>
          </div>
          <IconButton icon={IconClose} aria-label="关闭附件选择" onClick={onClose}/>
        </header>
        <div className="pw-drawer-body">
          {incomplete && <p className="pw-error" role="alert">部分附件尚未核对完整，可读文件仍可选择。未确认的原件已保留，请核对后再备份。</p>}
          {!original && pagingDenied && !!onReviewPagingRule && rows.some(row => row.use.paged) &&
            <div className="pw-form-actions"><Button variant="ghost" leadingIcon={IconShieldCheck}
              disabled={importing || openingPagingRule} onClick={onReviewPagingRule}>核对分段读取规则</Button></div>}
          <div className="pw-attachment-objects">
            {importing && <ProgressBar label="正在导入附件库" isIndeterminate showValueLabel={false} size="sm"/>}
            {rows.map(({ file, use: { reason, hint, paged } }, index) => {
              const target = exactFileTarget(file);
              const chosen = choices.find(row => row.id === file.id);
              const selectedHere = !!target && chosen?.sha256 === target.sha256;
              const note = chosen && !selectedHere ? '草稿选的是另一版本，可回输入框移除。'
                : reason ?? (paged ? (pagingDenied
                  ? '只提供文件引用。当前规则禁止分段读取正文。'
                  : '只提供文件引用。能否分段读取，发送后由原任务核对。') : hint);
              const disabled = !onToggle || !target || (!selectedHere && (!!chosen || !!reason || choices.length >= maxSelections));
              return <div className="pw-attachment-object" key={target ? `${target.fileId}:${target.sha256}` : index}>
                <CheckboxField isSelected={selectedHere} isDisabled={disabled} onChange={selected => {
                  if (!disabled && target) onToggle?.(target.fileId, target.sha256, selected);
                }}>
                  <span className="pw-attachment-copy">
                    <b>{typeof file.name === 'string' && file.name ? file.name : '未命名附件'}</b>
                    {note ? <small>{note}</small> : typeof file.size === 'number' && <small>{fileBytes(file.size)}</small>}
                  </span>
                </CheckboxField>
              </div>;
            })}
          </div>
          {!files.length && !importing && <div className="pw-empty-small">{original ? `「${original.name}」的原版本不再可选，没有改用其他文件。` : incomplete ? '暂未读到可选择的附件。' : '还没有附件，可以先导入。'}</div>}
          {files.length > 0 && <p className="pw-muted">发送时才核对原文件与模型。图片提供原图，文档提供提取文字或引用。</p>}
        </div>
        <footer className="pw-drawer-actions">
          {onImport && <Button variant="secondary" leadingIcon={IconAdd} disabled={importing} onClick={onImport}>{importing ? '正在导入…' : '导入新附件'}</Button>}
          <span className="pw-grow"/>
          <PressButton label={choices.length ? '保留选择' : '返回输入'} onClick={onClose}/>
        </footer>
      </Drawer.Content>
    </Drawer.Portal>
  </Drawer.Root>;
}
