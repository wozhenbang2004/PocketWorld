/** Map a read-only textarea selection back to the native message's exact UTF-16 range. */
export function messageQuoteRange(text: string, start: number, end: number): { offset: number; chars: number } | null {
  const visible = text.replace(/\r\n?/g, '\n');
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > visible.length) return null;
  const boundary = (index: number) => !(index > 0 && index < visible.length && /[\uD800-\uDBFF]/.test(visible[index - 1]) && /[\uDC00-\uDFFF]/.test(visible[index]));
  if (!boundary(start) || !boundary(end)) return null;
  let original = 0, displayed = 0, offset = 0;
  while (displayed < end) {
    if (displayed === start) offset = original;
    if (text[original] === '\r' && text[original + 1] === '\n') original++;
    original++; displayed++;
  }
  const chars = original - offset;
  return chars >= 1 && chars <= 4000 ? { offset, chars } : null;
}

/** A selection is valid only in the read-only control that showed this source. */
export function selectedQuoteRange(text: string, element: HTMLTextAreaElement) {
  return element.value === text.replace(/\r\n?/g, '\n')
    ? messageQuoteRange(text, element.selectionStart, element.selectionEnd) : null;
}

/** The native stage result distinguishes a provisional UI change from a saved draft. */
export function confirmedQuoteStage(value: unknown, target: {key: string; epoch: number}, fromRevision: number): boolean {
  const result = value as Record<string, unknown> | null;
  return !!result && result.selectionValidated === true && result.draftStaged === true && result.persisted === true &&
    result.key === target.key && result.epoch === target.epoch &&
    Number.isSafeInteger(fromRevision) && fromRevision >= 0 && Number.isSafeInteger(fromRevision + 1) &&
    result.fromRevision === fromRevision && result.revision === fromRevision + 1;
}
