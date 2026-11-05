export type FileJson = Record<string, any>;
export const fileObject = (value: unknown): FileJson => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as FileJson : {};
export const fileRows = (value: unknown): FileJson[] => Array.isArray(value) ? value.map(fileObject) : [];
export type AttachmentFrame = {windowId: string; frameId: string};
export type AttachmentAction = (command: string, args?: FileJson, after?: AttachmentFrame) => Promise<FileJson>;
/** Capture the displayed native frame before an action enters the queue. */
export function attachmentFrame(value: unknown): AttachmentFrame | undefined {
  const view = fileObject(value);
  return view.ready === true && typeof view.windowId === 'string' && view.windowId !== '' && typeof view.frameId === 'string' && view.frameId !== ''
    ? {windowId: view.windowId, frameId: view.frameId} : undefined;
}
export function attachmentActionPayload(frame: AttachmentFrame | undefined, command: string, args: FileJson = {}): FileJson {
  if (!frame) throw new Error('原文件页面已变化，请重新核对');
  return {...args, ...frame, command};
}
export function attachmentDocumentKey(view: FileJson): string {
  const draft = fileObject(view.documentDraft);
  return JSON.stringify([view.taskId || '', draft.parentArtifactId || '', draft.parentSha256 || '', draft.editableSourceId || '', draft.editableSourceSha256 || '']);
}
export function fileBytes(value: unknown): string {
  const count = typeof value === 'number' && Number.isFinite(value) ? value : 0;
  return count >= 1048576 ? `${(count / 1048576).toFixed(1)} MiB` : count >= 1024 ? `${(count / 1024).toFixed(1)} KiB` : `${count} bytes`;
}
/** Text edits retain every original slide, paragraph, shape and source identity. */
export function editSlideText(draft: FileJson, slide: number, field: 'title' | 'notes' | 'paragraph' | 'shape', value: string, item?: number): FileJson {
  const next = structuredClone(draft);
  if (next.format !== 'pptx' || !Array.isArray(next.presentation?.slides) || !Number.isInteger(slide) || slide < 0 || slide >= next.presentation.slides.length) throw new Error('请重新选择原幻灯片');
  const selected = next.presentation.slides[slide];
  if (field === 'title' || field === 'notes') selected[field] = value;
  else {
    const entries = field === 'paragraph' ? selected.paragraphs : selected.shapes;
    if (!Array.isArray(entries) || !Number.isInteger(item) || item! < 0 || item! >= entries.length) throw new Error('原段落或形状已变化');
    if (field === 'paragraph') entries[item!] = value;
    else entries[item!].text = value;
  }
  return next;
}
export function processingDefaults(info: FileJson): FileJson {
  if (Number.isInteger(info.width) && Number.isInteger(info.height)) {
    const scale = Math.min(1, 2000 / Math.max(info.width, info.height));
    return {left: 0, top: 0, right: info.width, bottom: info.height, width: Math.max(1, Math.floor(info.width * scale)), height: Math.max(1, Math.floor(info.height * scale)), rotation: 0, format: 'png', quality: 90};
  }
  return {startMillis: 0, endMillis: Math.min(Number(info.durationMillis) || 0, 120000)};
}
export function exactFileTarget(file: FileJson): {fileId: string; sha256: string} | null {
  return typeof file.id === 'string' && file.id !== '' && typeof file.sha256 === 'string' && /^[a-f0-9]{64}$/.test(file.sha256) && (file.availability === undefined || file.availability === 'available') ? {fileId: file.id, sha256: file.sha256} : null;
}
/** A table cell is grouped by its original coordinate, with paragraph boundaries intact. */
export function documentTable(chunks: FileJson[]) {
  const cells = chunks.map(block => {
    const at = fileObject(block.locator), cell = String(at.cell || '');
    return {row: cell ? cell.replace(/[^0-9]/g, '') : String(at.row || ''), column: cell ? cell.replace(/[^a-z]/gi, '') : String(at.column || ''), block};
  }).filter(cell => cell.row && cell.column);
  const sort = (a: string, b: string) => a.length - b.length || a.localeCompare(b);
  const rows = [...new Set(cells.map(c => c.row))].sort(sort), columns = [...new Set(cells.map(c => c.column))].sort(sort);
  return {rows, columns, text(row: string, column: string) {
    let previous = '', result = '';
    for (const {block} of cells.filter(cell => cell.row === row && cell.column === column)) {
      const paragraph = `${block.unit}:${fileObject(block.locator).paragraph || ''}`;
      if (result && previous !== paragraph) result += '\n';
      result += String(block.text || ''); previous = paragraph;
    }
    return result;
  }};
}
