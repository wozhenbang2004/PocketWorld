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
