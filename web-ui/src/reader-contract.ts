/** Dedicated saved-browser-source wire contract. It has no workspace or answer-sources handles. */
export type ReaderCell = { column: number; role: string; text: string; partial: boolean; colSpan: number; rowSpan: number; fieldRef: string };
export type ReaderRow = { index: number; text?: string; partial?: boolean; fieldRef?: string; cells?: ReaderCell[] };
export type ReaderSource = {
  title: string; safeUrl: string; excerpt: string; capturedAt: number; sourceId: string;
  sourceFingerprint: string; runId: string; epoch: number; untrusted: true; verified: false; urlQueryOmitted: boolean;
  coverage: { kind: 'semantic' | 'live' | 'http' | 'excerpt'; partial: boolean; wholePage: false; embeddedFrame: boolean; inlineHostPage: boolean; continuationAvailable: boolean };
  projection?: { mode: 'table' | 'list'; revision: string; partial: boolean; omittedRows: number; rows: ReaderRow[] };
};
export type ReaderField = { fieldRef: string; revision: string; mode: 'table' | 'list'; text: string; rowIndex: number; columnIndex: number; headers: string[]; partial: boolean; untrusted: true; verified: false };
export type ReaderState = { windowId: string; generation: number; sourceFingerprint: string; ready: boolean; loading: boolean; error: string; source: ReaderSource | null; field: ReaderField | null };
export type ReaderPin = Pick<ReaderState, 'windowId' | 'generation' | 'sourceFingerprint'>;

export function browserReaderSurface(snapshot: { surface?: unknown; host?: Record<string, unknown> }): boolean {
  return snapshot.surface === 'browser_sources' || snapshot.host?.surface === 'browser_sources';
}
export function readerPin(state: ReaderState): ReaderPin {
  if (!state.ready || !state.windowId || !Number.isSafeInteger(state.generation) || state.generation < 0) throw new Error('原阅读窗口尚未就绪。');
  return { windowId: state.windowId, generation: state.generation, sourceFingerprint: state.sourceFingerprint };
}
export function sameReader(pin: ReaderPin, state: ReaderState | null | undefined): boolean {
  return !!state?.ready && state.windowId === pin.windowId && state.generation === pin.generation && state.sourceFingerprint === pin.sourceFingerprint;
}
export function sourceFieldRefs(source: ReaderSource): string[] {
  return source.projection?.rows.flatMap(row => row.fieldRef ? [row.fieldRef] : (row.cells ?? []).map(cell => cell.fieldRef)) ?? [];
}
export function readerFieldPayload(state: ReaderState, fieldRef: string): ReaderPin & { fieldRef: string } {
  const pin = readerPin(state);
  if (!state.source || state.loading || !sourceFieldRefs(state.source).includes(fieldRef)) throw new Error('这个字段不属于当前原文版本。');
  return { ...pin, fieldRef };
}
export function readerFieldCurrent(field: ReaderField, source: ReaderSource): boolean {
  return field.revision === source.projection?.revision && sourceFieldRefs(source).includes(field.fieldRef);
}
export function readerCoverage(source: ReaderSource): string {
  const scope = source.coverage;
  if (scope.kind === 'http') return scope.partial ? '匿名 HTTP 响应的正文片段，未读取完整，也不包含渲染后的网页。' : '匿名 HTTP 响应的文字投影，不包含渲染后的网页。';
  const place = scope.embeddedFrame ? '选中的嵌入页面' : '当时的网页';
  return `${place}${scope.partial ? '只保存了部分内容' : '只保存了所选范围的文字'}，不代表整页。${scope.continuationAvailable ? '采集时仍有可续读内容。' : ''}${scope.inlineHostPage ? '显示的地址属于内联内容的宿主页。' : ''}`;
}
/** Display only. Even valid source addresses never become links or navigation commands. */
export function readerDomain(value: string): string {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash ? url.hostname : '已保存来源'; }
  catch { return '已保存来源'; }
}

export function readerTime(capturedAt: number): { label: string; iso?: string } {
  const date = new Date(capturedAt);
  return Number.isFinite(capturedAt) && capturedAt > 0 && Number.isFinite(date.getTime())
    ? { label: date.toLocaleString(), iso: date.toISOString() } : { label: '保存时间待核对' };
}

export type ReaderPosition = { identity: string; scrollTop: number; tableScrollLeft: number };
/** Presentation identity only. It never supplies a native read target or a field handle. */
export function readerIdentity(source: ReaderSource): string {
  return JSON.stringify([source.sourceId, source.sourceFingerprint, source.runId, source.epoch, source.projection?.revision ?? null]);
}
export function captureReaderPosition(source: ReaderSource, scrollTop: number, tableScrollLeft: number): ReaderPosition {
  const bounded = (value: number) => Number.isFinite(value) ? Math.max(0, value) : 0;
  return { identity: readerIdentity(source), scrollTop: bounded(scrollTop), tableScrollLeft: bounded(tableScrollLeft) };
}
export function restoreReaderPosition(source: ReaderSource, saved: ReaderPosition | null): ReaderPosition {
  return saved?.identity === readerIdentity(source) ? saved : captureReaderPosition(source, 0, 0);
}
