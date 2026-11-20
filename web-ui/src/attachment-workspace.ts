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
export type AttachmentFileCategory = 'document' | 'table' | 'text' | 'media' | 'other';
const fileFormats: Record<string, { category: AttachmentFileCategory; label: string }> = {
  'application/pdf': {category:'document',label:'PDF'},
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': {category:'document',label:'Word 文档'},
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': {category:'document',label:'PowerPoint'},
  'application/msword': {category:'document',label:'旧版 Word'},
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': {category:'table',label:'Excel 表格'},
  'application/vnd.ms-excel': {category:'table',label:'旧版 Excel'},
  'text/csv': {category:'table',label:'CSV 表格'},
  'text/tab-separated-values': {category:'table',label:'TSV 表格'},
  'text/plain': {category:'text',label:'文本'},
  'text/markdown': {category:'text',label:'Markdown 文本'},
  'application/json': {category:'text',label:'JSON 文本'},
  'application/zip': {category:'other',label:'ZIP 压缩包'},
};
const genericExtensions: Record<string,string> = {
  pdf:'application/pdf', doc:'application/msword', xls:'application/vnd.ms-excel',
  docx:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx:'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  txt:'text/plain', md:'text/markdown', json:'application/json', csv:'text/csv', tsv:'text/tab-separated-values', zip:'application/zip',
};
/** Catalog description only. Actual viewer support and exact bytes are checked on open. */
export function attachmentFileFormat(mimeType: unknown, name?: unknown): { category: AttachmentFileCategory; label: string } {
  let mime = typeof mimeType === 'string' ? mimeType.split(';', 1)[0].trim().toLowerCase() : '';
  if ((!mime || mime === 'application/octet-stream') && typeof name === 'string') {
    const ext = name.match(/\.([a-z0-9]+)$/i)?.[1].toLowerCase() || '';
    if (Object.hasOwn(genericExtensions, ext)) mime = genericExtensions[ext];
  }
  if (Object.hasOwn(fileFormats, mime)) return fileFormats[mime];
  if (mime.startsWith('text/') || mime === 'application/xml' || mime.endsWith('+json') || mime.endsWith('+xml'))
    return {category:'text',label:'文本'};
  if (mime.startsWith('image/')) return {category:'media',label:'图片'};
  if (mime.startsWith('video/')) return {category:'media',label:'视频'};
  if (mime.startsWith('audio/')) return {category:'media',label:'音频'};
  return {category:'other',label:'文件'};
}
export function fileRunProvenance(value: unknown, evidence?: unknown): string {
  if (evidence === 'original_device_file_receive') return value === 'current' ? '本轮接收自原设备' : value === 'earlier' ? '较早轮次接收自原设备' : '';
  switch (value) {
    case 'current': return '本轮生成';
    case 'earlier': return '较早执行生成';
    case 'unrecorded': return '生成轮次未记录';
    default: return '';
  }
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

export type SavedDocumentVersion={label:string;parent:string;savedAt:string};
function documentVersionLabel(id:unknown,sha:unknown):string {
  return typeof id==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id)&&
    typeof sha==='string'&&/^[a-f0-9]{64}$/.test(sha)?id.slice(-12):'';
}
export function documentVersionKey(file:FileJson):string {
  const fixed=exactFileTarget(file);
  return fixed&&documentVersionLabel(fixed.fileId,fixed.sha256)?`${fixed.fileId}:${fixed.sha256}`:'';
}
/** Display only. A shared name calls for a label, never a parent relationship or a file lookup. */
export function savedDocumentVersions(files:FileJson[]):Map<string,SavedDocumentVersion> {
  const documents=new Map<string,{file:FileJson;name:string;parent:FileJson;parentKey:string}>();
  const sameNames=new Map<string,number>(),parents=new Set<string>();
  for(const file of files){
    const key=documentVersionKey(file),source=fileObject(file.source);
    if(!key||documents.has(key)||(file.sourceKind??source.kind)!=='created_document')continue;
    const name=typeof file.name==='string'?file.name.normalize('NFKC').trim().toLowerCase():'';
    const parent=fileObject(file.parentArtifact??source.parentArtifact);
    const parentKey=parent.id!==file.id?documentVersionKey(parent):'';
    documents.set(key,{file,name,parent,parentKey});
    if(name)sameNames.set(name,(sameNames.get(name)??0)+1);
    if(parentKey)parents.add(parentKey);
  }
  const versions=new Map<string,SavedDocumentVersion>();
  let dateFormatter:Intl.DateTimeFormat|undefined;
  for(const [key,{file,name,parent,parentKey}] of documents){
    if(!parentKey&&!parents.has(key)&&(sameNames.get(name)??0)<2)continue;
    const date=typeof file.createdAt==='number'&&file.createdAt>0?new Date(file.createdAt):null;
    const savedAt=date&&Number.isFinite(date.getTime())?
      (dateFormatter??=new Intl.DateTimeFormat('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false})).format(date):'';
    versions.set(key,{label:documentVersionLabel(file.id,file.sha256),parent:parentKey?documentVersionLabel(parent.id,parent.sha256):'',savedAt});
  }
  return versions;
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
