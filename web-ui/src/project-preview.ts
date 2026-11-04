/** Public presentation metadata only. The native document and source registry own admission. */
export type ProjectPreviewState = {
  kind: 'bundle';
  sessionId: string;
  taskId: string;
  conversationId: string;
  documentGeneration: number;
  ready: boolean;
  selecting: boolean;
  visible: boolean;
  error: string | null;
  metadata: Record<string, unknown>;
};

export type PreviewWindow = { projectId: string; documentEpoch: number; resumeRevision: number };
export type PreviewRect = { x: number; y: number; width: number; height: number };

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

export function projectPreviewState(value: unknown): ProjectPreviewState | null {
  const source = record(value);
  if (source.kind !== undefined && source.kind !== 'bundle') return null;
  if (typeof source.sessionId !== 'string' || !source.sessionId || typeof source.taskId !== 'string' || !source.taskId ||
      typeof source.conversationId !== 'string' || !source.conversationId ||
      !Number.isSafeInteger(source.documentGeneration) || Number(source.documentGeneration) < 0) return null;
  return {
    kind: 'bundle', sessionId: source.sessionId, taskId: source.taskId, conversationId: source.conversationId,
    documentGeneration: Number(source.documentGeneration), ready: source.ready === true && Number(source.documentGeneration) > 0,
    selecting: source.selecting === true && source.ready === true && Number(source.documentGeneration) > 0, visible: source.visible === true,
    error: typeof source.error === 'string' ? source.error : null,
    metadata: record(source.metadata),
  };
}

export function samePreviewWindow(original: PreviewWindow, current: PreviewWindow): boolean {
  return original.projectId === current.projectId && original.documentEpoch === current.documentEpoch &&
    original.resumeRevision === current.resumeRevision;
}

export function previewReturned(value: unknown, original: Pick<ProjectPreviewState, 'sessionId' | 'conversationId'>,
  currentConversationId: unknown, currentComposerConversationId: unknown): boolean {
  const receipt = record(value);
  return receipt.returned === true && receipt.sessionId === original.sessionId &&
    receipt.conversationId === original.conversationId && currentConversationId === original.conversationId &&
    currentComposerConversationId === original.conversationId;
}

/** The rectangle is a layout hint, never an HTML/source/selection authority. */
export function previewViewport(sessionId: string, visible: boolean, rect: PreviewRect | null,
  viewportWidth: number, viewportHeight: number) {
  const validViewport = Number.isFinite(viewportWidth) && viewportWidth > 0 && Number.isFinite(viewportHeight) && viewportHeight > 0;
  const validRect = rect && Object.values(rect).every(Number.isFinite) && rect.width > 0 && rect.height > 0;
  const width = validViewport ? viewportWidth : 0, height = validViewport ? viewportHeight : 0;
  const left = validRect ? Math.max(0, Math.min(width, rect.x)) : 0;
  const top = validRect ? Math.max(0, Math.min(height, rect.y)) : 0;
  const right = validRect ? Math.max(left, Math.min(width, rect.x + rect.width)) : 0;
  const bottom = validRect ? Math.max(top, Math.min(height, rect.y + rect.height)) : 0;
  const shown = visible && validViewport && !!validRect && right > left && bottom > top;
  return { sessionId, visible: shown, rect: shown ? { x: left, y: top, width: right - left, height: bottom - top } : { x: 0, y: 0, width: 0, height: 0 }, viewportWidth: width, viewportHeight: height };
}

export function previewReferenceMessage(available: unknown): string {
  return available === true ? '发送时会重新核对预览和真实源码。' : '原预览已变化或不可用，请重新选择；保留的片段不能直接发送。';
}
