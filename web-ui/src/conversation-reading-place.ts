/** One in-memory return point for an explicit visit to the latest reply. It is
 * not the continuously updated native reading position or a saved transcript. */
export type ReadingPlace = {
  messageId: string;
  source: string;
  offset: number;
  width: number;
  height: number;
  line?: { index: number; context: string; contextStart: number; top: number };
};

const answerBody = (row: HTMLElement) => row.querySelector<HTMLElement>('.pw-markdown, .pw-user-text');

// Use a glyph, not a screenful number. After wrapping changes the same line no
// longer starts at the same row offset. Only an explicit jump walks this body.
function captureLine(body: HTMLElement, top: number, bottom: number): ReadingPlace['line'] {
  const bounds = body.getBoundingClientRect();
  // If the top is still in a task disclosure or in the gap before the message,
  // it is not a line of this answer. Preserve the signed row offset instead.
  if (bounds.top > top + 20) return;
  const y = Math.max(top + 3, bounds.top + 2);
  if (y >= Math.min(bottom, bounds.bottom)) return;
  const x = bounds.left + Math.min(bounds.width * .25, 72);
  const doc = body.ownerDocument as Document & {
    caretRangeFromPoint?: (x: number, y: number) => Range | null;
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
  };
  const caret = doc.caretPositionFromPoint?.(x, y);
  const range = caret ? null : doc.caretRangeFromPoint?.(x, y);
  const node = caret?.offsetNode ?? range?.startContainer;
  if (!node || node.nodeType !== Node.TEXT_NODE || !body.contains(node) || !node.textContent?.length) return;
  const offset = Math.min(caret?.offset ?? range!.startOffset, node.textContent.length - 1);
  const slice = doc.createRange();
  slice.setStart(node, offset); slice.setEnd(node, offset + 1);
  const rect = slice.getClientRects()[0];
  if (!rect?.height || rect.top >= bottom || rect.bottom < top - 1) return;
  slice.selectNodeContents(body); slice.setEnd(node, offset);
  const index = slice.toString().length, text = body.textContent || '';
  const contextStart = Math.max(0, index - 16);
  return { index, contextStart, context: text.slice(contextStart, index + 17), top: rect.top - top };
}

function lineTop(body: HTMLElement, line: NonNullable<ReadingPlace['line']>) {
  if (!(body.textContent || '').startsWith(line.context, line.contextStart)) return;
  const walker = body.ownerDocument.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  let offset = line.index;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const length = node.textContent?.length || 0;
    if (offset < length) {
      const glyph = body.ownerDocument.createRange();
      glyph.setStart(node, offset); glyph.setEnd(node, offset + 1);
      const rect = glyph.getClientRects()[0];
      return rect?.height ? rect.top : undefined;
    }
    offset -= length;
  }
}

export function captureReadingPlace(row: HTMLElement, source: string, top: number, bottom: number): ReadingPlace | null {
  const rect = row.getBoundingClientRect(), messageId = row.dataset.messageId;
  if (!messageId || rect.bottom <= top || rect.top >= bottom) return null;
  const body = answerBody(row);
  return { messageId, source, offset: top - rect.top, width: rect.width, height: rect.height,
    line: body ? captureLine(body, top, bottom) : undefined };
}

/** Returns a signed viewport delta. If the original text or its layout is no
 * longer identifiable, show that same message from the top and say so. */
export function readingReturnDelta(row: HTMLElement, source: string, top: number, place: ReadingPlace) {
  const rect = row.getBoundingClientRect();
  if (place.source === source) {
    const body = answerBody(row);
    const glyphTop = place.line && body ? lineTop(body, place.line) : undefined;
    if (glyphTop !== undefined && place.line) return { delta: glyphTop - top - place.line.top, exact: true };
    if (!place.line && Math.abs(place.width - rect.width) < 1 && Math.abs(place.height - rect.height) < 1)
      return { delta: rect.top - top + place.offset, exact: true };
  }
  return { delta: rect.top - top, exact: false };
}
