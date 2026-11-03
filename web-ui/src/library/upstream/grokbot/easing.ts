// Reproduces Flutter's Cubic curve so spin timing matches nasawz/GrokBot
// (BSD-3-Clause): lib/src/grokbot.dart uses Curves.easeInOutCubic.
/**
 * Solves a CSS-style unit cubic Bezier, the same curve family Flutter's
 * `Cubic` uses. Newton-Raphson with a bisection fallback, matching the
 * tolerance Flutter applies (1e-6).
 */
export function cubicBezier(x1: number, y1: number, x2: number, y2: number) {
  const sampleCurve = (a: number, b: number, t: number) =>
    3 * a * (1 - t) * (1 - t) * t + 3 * b * (1 - t) * t * t + t * t * t;

  return (input: number): number => {
    if (input <= 0) return 0;
    if (input >= 1) return 1;
    let start = 0;
    let end = 1;
    // Bisection is enough here: the curve is monotonic in t and ~20 halvings
    // land well inside the 1e-6 tolerance.
    for (let i = 0; i < 24; i += 1) {
      const midpoint = (start + end) / 2;
      const estimate = sampleCurve(x1, x2, midpoint);
      if (Math.abs(input - estimate) < 1e-6) return sampleCurve(y1, y2, midpoint);
      if (estimate < input) start = midpoint;
      else end = midpoint;
    }
    return sampleCurve(y1, y2, (start + end) / 2);
  };
}

/** Flutter's `Curves.easeInOutCubic`. */
export const easeInOutCubic = cubicBezier(0.645, 0.045, 0.355, 1);
