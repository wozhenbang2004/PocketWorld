/** Curvature-continuous, two-cubic approximation of each n=4 superellipse
 * quadrant. The fitted control gives <0.001 radius vertical deviation.
 * Straight edges join at zero curvature; the two halves share tangent and
 * curvature. The complete elongated shape is a rounded rectangle, not a
 * globally stretched squircle. No runtime browser corner-shape dependency. */
export function continuousRectPath(width: number, height: number, requestedRadius: number): string {
  if (![width, height, requestedRadius].every(Number.isFinite) || width <= 0 || height <= 0 || requestedRadius < 0) return '';
  const r = Math.min(requestedRadius, width / 2, height / 2);
  const f = (n: number) => Number(n.toFixed(5)).toString();
  if (!r) return `M0 0H${f(width)}V${f(height)}H0Z`;
  const m = Math.pow(2, -.25), b = 2 * m - 1, a = .4341953546605872;
  const controls = [[a, -1], [b, -1], [m, -m], [1, -b], [1, -a], [1, 0]];
  const corner = (cx: number, cy: number, quarter: number): string => {
    const points = controls.map(([x, y]) => {
      const [rx, ry] = quarter === 0 ? [x, y] : quarter === 1 ? [-y, x] : quarter === 2 ? [-x, -y] : [y, -x];
      return `${f(cx + rx * r)} ${f(cy + ry * r)}`;
    });
    return `C${points.slice(0, 3).join(' ')}C${points.slice(3).join(' ')}`;
  };
  return `M${f(r)} 0H${f(width-r)}${corner(width-r,r,0)}V${f(height-r)}${corner(width-r,height-r,1)}H${f(r)}${corner(r,height-r,2)}V${f(r)}${corner(r,r,3)}Z`;
}
