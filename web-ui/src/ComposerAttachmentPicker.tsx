import { useEffect, useMemo, useState } from 'react';
import { Button, CheckboxField, IconButton, TextField } from './forms-library';
import { Drawer } from './shell-library';
import { PressButton, ProgressBar } from './library';
import { IconAdd, IconClose, IconSearch, IconShieldCheck } from './icons';
import { bridge } from './bridge';
import { exactFileKey, exactFileTarget, fileBytes, fileObject, fileRows, libraryFileOrigins, normalizedFileName, type FileJson } from './attachment-workspace';

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
  importing: boolean; onClose: () => void; onCloseAutoFocus?: (event: Event) => void; onImport?: () => void;
  pagingDenied: boolean; onReviewPagingRule?: () => void; openingPagingRule?: boolean;
  listPhase?: 'loading' | 'ready' | 'failed' | 'changed';
  onToggle?: (id: string, sha256: string, selected: boolean) => void;
  original?: {fileId:string;sha256:string;name:string;draftLabel:string};
  resultAction?: {pending:boolean;error?:string;confirm:(id:string,sha256:string,returnOnly:boolean)=>void};
};

/** Keep fit guidance with the original picker. Native send still verifies source, model and scope. */
export function ComposerAttachmentPicker({ open, inventory, selected, inputModel, importing, pagingDenied, onReviewPagingRule, openingPagingRule, listPhase, onClose, onCloseAutoFocus, onImport, onToggle, original, resultAction }: Props) {
  const catalog = fileObject(inventory), focused = !!original, changed = listPhase === 'changed';
  const [query, setQuery] = useState('');
  const [pickError, setPickError] = useState('');
  const snapshot = bridge.getState(), input = snapshot.composer, picker = snapshot.state.chatAttachmentPickerRequest;
  const addFromPhone = !focused && !!onImport && !!input?.ready && typeof picker === 'string' &&
    snapshot.state.chatFilePickReadyFor === picker &&
    snapshot.capabilities.commands.includes('attachments.pickForDraft');
  useEffect(() => { setPickError(''); if (!open || focused) setQuery(''); }, [open, focused, picker]);
  const pickForDraft = () => {
    if (!addFromPhone || !input || importing) return;
    setPickError('');
    void bridge.command('attachments.pickForDraft', {key:input.key, epoch:input.epoch, revision:input.revision, pickerRequestId:picker})
      .catch(error => {
        const now = bridge.getState();
        if (now.state.chatAttachmentPickerRequest === picker && now.composer?.key === input.key && now.composer?.epoch === input.epoch)
          setPickError(error instanceof Error ? error.message : '没有确认打开文件选择，请核对当前输入。');
      });
  };
  const inventoryRows = useMemo(() => fileRows(catalog.attachments), [catalog.attachments]);
  const files = original ? inventoryRows.filter(row => row.id === original.fileId && row.sha256 === original.sha256) : inventoryRows;
  const needle = focused ? '' : normalizedFileName(query);
  // The ordinary picker may use only its own already-projected metadata, not another workspace's task list.
  const origins = useMemo(() => open && !focused ? libraryFileOrigins(inventoryRows.flatMap(file => {
    const source = fileObject(file.source);
    if (file.principalId !== 'owner' || file.spaceId !== 'private' || file.outputSourceRetired === true ||
        Object.hasOwn(source, 'groupSpaceId')) return [];
    const { id, sha256, name, mimeType, createdAt, availability } = file;
    return [{ id, sha256, name, mimeType, createdAt, availability, sourceKind: source.kind, sourceTaskId: source.taskId }];
  }), []) : undefined, [open, focused, inventoryRows]);
  const choices = fileRows(selected), model = fileObject(inputModel);
  const photoAllowance = addFromPhone ? fileObject(snapshot.state.chatFilePickPhotos) : {};
  const photoSlotsFull = photoAllowance.accountSlots === 0 && Number.isSafeInteger(photoAllowance.accountMax);
  const rows = files.filter(file => !needle || normalizedFileName(file.name).includes(needle))
    .map(file => ({ file, use: fileUse(file, model) }));
  // Keep a way to clear a selection without replacing the current search. These
  // controls only use removable originals from this ordinary picker's exact list.
  const hiddenChoices = !focused && listPhase === 'ready' && needle && onToggle ? choices.flatMap(choice => {
    const target = exactFileTarget(choice);
    if (!target) return [];
    const matches = files.filter(file => file.id === target.fileId && file.sha256 === target.sha256);
    const file = matches[0];
    if (!file || matches.length !== 1 || !exactFileTarget(file) || file.principalId !== 'owner' || file.spaceId !== 'private' ||
        file.outputSourceRetired === true || Object.hasOwn(fileObject(file.source), 'groupSpaceId') ||
        normalizedFileName(file.name).includes(needle)) return [];
    const name = typeof file.name === 'string' && file.name ? file.name : '未命名附件';
    const origin = origins?.get(exactFileKey(file));
    return [{ target, name: origin ? `${name} · ${origin}` : name }];
  }) : [];
  const incomplete = fileObject(catalog.indexDiagnostics).incomplete === true;
  return <Drawer.Root open={open} onOpenChange={next => { if (!next) onClose(); }} shouldScaleBackground={false}>
    <Drawer.Portal>
      <Drawer.Overlay className="pw-overlay"/>
      <Drawer.Content className="pw-drawer pw-attachment-drawer" aria-describedby="pw-attachment-description" onCloseAutoFocus={onCloseAutoFocus}>
        <div className="pw-drawer-handle"/>
        <header className="pw-drawer-header">
          <div>
            <Drawer.Title>{original?'选作草稿附件':'选择附件'}</Drawer.Title>
            <Drawer.Description id="pw-attachment-description">{changed ? '原输入已变化。' :
              <>{original&&`「${original.draftLabel}」 · `}{!resultAction&&`已选 ${choices.length}/${maxSelections} · `}只加入草稿，不发送。</>}</Drawer.Description>
          </div>
          <IconButton icon={IconClose} aria-label="关闭附件选择" disabled={resultAction?.pending} onClick={onClose}/>
        </header>
        <div className="pw-drawer-body">
          {resultAction?.error && <p className="pw-error" role="alert">{resultAction.error}</p>}
          {resultAction?.pending && <p className="pw-muted" role="status">正在核对原话题草稿…</p>}
          {!changed && (pickError ? <p className="pw-error" role="alert">{pickError}</p> : !focused && typeof snapshot.state.chatFilePickFeedback === 'string' &&
            <p className="pw-muted" role="status">{snapshot.state.chatFilePickFeedback}</p>)}
          {changed && <p className="pw-muted" role="status">这次选择已不可用。关闭后，可在当前输入重新点「添加附件」。</p>}
          {photoSlotsFull && <p className="pw-muted" role="status">当前账号每条最多加入 {photoAllowance.accountMax} 张图；可移除一张后替换，文档不计入这个上限。</p>}
          {listPhase === 'loading' && <p className="pw-muted" role="status">正在读取本机附件…</p>}
          {listPhase === 'failed' && <p className="pw-error" role="alert">未能读取本机附件清单，草稿未改动。要从清单勾选，请关闭后在原输入点「添加附件」重新读取。已保存到附件库的文件不用重新导入。</p>}
          {incomplete && <p className="pw-error" role="alert">部分附件尚未核对完整，可读文件仍可选择。未确认的原件已保留，请核对后再备份。</p>}
          {!changed && !focused && (files.length > 0 || query !== '') && <div role="search" aria-label="在当前附件清单中查找">
            <TextField label="文件名" leadingIcon={IconSearch} type="search" placeholder="查找当前清单" value={query}
              onChange={value => setQuery(value.slice(0, 180))}/>
          </div>}
          {hiddenChoices.length > 0 && <div className="flex flex-wrap items-center gap-x-2 gap-y-1" role="group" aria-label="未匹配搜索的已选附件">
            <small className="pw-muted">已选</small>
            {hiddenChoices.map(({ target, name }) => <span key={target.fileId} className="flex min-w-0 max-w-full items-center">
              <bdi className="truncate text-sm" title={name}>{name}</bdi>
              <Button variant="ghost" size="small" iconOnly leadingIcon={IconClose} className="shrink-0"
                aria-label={`从草稿移除「${name}」`} onClick={() => onToggle?.(target.fileId, target.sha256, false)}/>
            </span>)}
          </div>}
          {!original && pagingDenied && !!onReviewPagingRule && files.some(file => fileUse(file, model).paged) &&
            <div className="pw-form-actions"><Button variant="ghost" leadingIcon={IconShieldCheck}
              disabled={importing || openingPagingRule} onClick={onReviewPagingRule}>核对分段读取规则</Button></div>}
          <div className="pw-attachment-objects">
            {importing && <ProgressBar label="正在导入附件库" isIndeterminate showValueLabel={false} size="sm"/>}
            {rows.map(({ file, use: { reason, hint, paged } }, index) => {
              const target = exactFileTarget(file), origin = origins?.get(exactFileKey(file));
              const chosen = choices.find(row => row.id === file.id);
              const selectedHere = !!target && chosen?.sha256 === target.sha256;
              const newPhoto = !selectedHere && !reason && imageFormats.has(file.mimeType);
              const overPhotoBytes = newPhoto && !photoSlotsFull && Number.isSafeInteger(photoAllowance.nextPhotoBytes) &&
                file.size > photoAllowance.nextPhotoBytes;
              const note = chosen && !selectedHere ? '草稿选的是另一版本，可回输入框移除。'
                : reason ?? (overPhotoBytes ? '加上这张原图会超过本条容量，可换较小的图片。' : paged ? (pagingDenied
                  ? '只提供文件引用。当前规则禁止分段读取正文。'
                  : '只提供文件引用。能否分段读取，发送后由原任务核对。') : hint);
              const blocked = !target || (!selectedHere && (!!chosen || !!reason || choices.length >= maxSelections ||
                overPhotoBytes || newPhoto && photoSlotsFull));
              const checkboxDisabled = blocked || !onToggle;
              const copy = <span className="pw-attachment-copy">
                <b>{typeof file.name === 'string' && file.name ? file.name : '未命名附件'}</b>
                {note ? <small>{note}</small> : typeof file.size === 'number' && <small>{fileBytes(file.size)}</small>}
                {origin && <small>{origin}</small>}
              </span>;
              return <div className="pw-attachment-object" key={target ? `${target.fileId}:${target.sha256}` : index}>
                {resultAction ? <>{copy}{selectedHere&&<small className="pw-muted">已在原话题草稿</small>}
                  {rows.length===1&&target&&<PressButton label={selectedHere?'返回原话题草稿':'加入原话题草稿并返回'}
                    disabled={!!resultAction.pending||!selectedHere&&blocked}
                    onClick={()=>resultAction.confirm(target.fileId,target.sha256,selectedHere)}/>}
                </> : <CheckboxField isSelected={selectedHere} isDisabled={checkboxDisabled} onChange={selected => {
                  if (!checkboxDisabled && target) onToggle?.(target.fileId, target.sha256, selected);
                }}>{copy}</CheckboxField>}
              </div>;
            })}
          </div>
          {!rows.length && !importing && (!listPhase || listPhase === 'ready') && <div className="pw-empty-small" role={needle ? 'status' : undefined}>
            {needle ? '当前已读取的附件没有匹配的文件名。' : original ? `「${original.name}」的原版本不再可选，没有改用其他文件。` : incomplete ? '暂未读到可选择的附件。' : addFromPhone ? '从手机选照片、PDF 等文件，返回后可在草稿中移除或发送。' : '还没有附件，可以先导入。'}
          </div>}
          {rows.length > 0 && <p className="pw-muted">发送时才核对原文件与模型。图片提供原图，文档提供提取文字或引用。</p>}
        </div>
        {!resultAction&&<footer className="pw-drawer-actions">
          {onImport && <Button variant="secondary" leadingIcon={IconAdd} disabled={importing || (addFromPhone && choices.length >= maxSelections)}
            onClick={addFromPhone ? pickForDraft : onImport}>{importing ? '正在导入…' : addFromPhone ? '从手机添加到草稿' : '导入到附件库'}</Button>}
          <span className="pw-grow"/>
          <PressButton label={choices.length ? '保留选择' : '返回输入'} onClick={onClose}/>
        </footer>}
      </Drawer.Content>
    </Drawer.Portal>
  </Drawer.Root>;
}
