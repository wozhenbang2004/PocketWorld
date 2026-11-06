/** Keeps a split between 0 and 100, snapped to a world's step (0 leaves it continuous). */
export function snapSplit(value: number, step = 0): number {
  const v = Math.max(0, Math.min(100, value));
  return step > 0 ? Math.round(v / step) * step : v;
}

/** The split under a pointer, as a percentage of the view's width. */
export function splitAt(clientX: number, left: number, width: number): number {
  return width > 0 ? ((clientX - left) / width) * 100 : 50;
}

/** Where a key moves the split, or undefined for keys it ignores. */
export function splitForKey(key: string, value: number, step: number): number | undefined {
  const page = Math.max(step, 10);
  const moves: Record<string, number> = {
    ArrowRight: step,
    ArrowUp: step,
    ArrowLeft: -step,
    ArrowDown: -step,
    PageUp: page,
    PageDown: -page,
  };
  if (key === "Home") return 0;
  if (key === "End") return 100;
  return key in moves ? snapSplit(value + moves[key], step) : undefined;
}

/** A ten-cell block meter for the split, as Retro prints it. */
export function splitMeter(value: number): string {
  const lit = Math.round(snapSplit(value) / 10);
  return "▓".repeat(lit) + "░".repeat(10 - lit);
}
