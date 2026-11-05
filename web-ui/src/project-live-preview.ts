export type PreviewRect = { x: number; y: number; width: number; height: number };

/** Clip native child views to the actual scroll viewport before publishing their screen rectangle. */
export function projectLiveBounds(rect: PreviewRect, viewportWidth: number, viewportHeight: number, clips: PreviewRect[] = [], unobscured = true) {
  if (![rect.x, rect.y, rect.width, rect.height, viewportWidth, viewportHeight, ...clips.flatMap(value => [value.x, value.y, value.width, value.height])].every(Number.isFinite)) return { visible: false, rect: { x: 0, y: 0, width: 0, height: 0 } };
  let left = Math.max(0, rect.x), top = Math.max(0, rect.y), right = Math.min(viewportWidth, rect.x + Math.max(0, rect.width)), bottom = Math.min(viewportHeight, rect.y + Math.max(0, rect.height));
  for (const clip of clips) { left = Math.max(left, clip.x); top = Math.max(top, clip.y); right = Math.min(right, clip.x + Math.max(0, clip.width)); bottom = Math.min(bottom, clip.y + Math.max(0, clip.height)); }
  const result = { x: left, y: top, width: Math.max(0, right - left), height: Math.max(0, bottom - top) };
  return { visible: unobscured && result.width >= 1 && result.height >= 1, rect: result };
}
