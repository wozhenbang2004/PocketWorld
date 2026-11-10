import { useLayoutEffect, useRef } from 'react';
import { continuousRectPath } from './continuous-corners';
import '../composer-native-detail.css';

/** Background only: never clip text, controls, popovers or keyboard focus. */
export function SmoothComposerSurface({ radius = 30, tone = 'surface' }: { radius?: number; tone?: 'surface' | 'context' }) {
  const ref = useRef<SVGSVGElement>(null);
  const path = useRef<SVGPathElement>(null);
  useLayoutEffect(() => {
    const svg = ref.current, shape = path.current, parent = svg?.parentElement;
    if (!svg || !shape || !parent) return;
    let lastWidth = 0, lastHeight = 0;
    const draw = (width: number, height: number) => {
      if (width <= 0 || height <= 0) { lastWidth = 0; lastHeight = 0; delete parent.dataset.continuousShape; return; }
      if (width === lastWidth && height === lastHeight) return;
      lastWidth = width; lastHeight = height;
      // Only the background path follows the actual animated size. This avoids
      // a React render for every ResizeObserver delivery during editor growth.
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      shape.setAttribute('d', continuousRectPath(width, height, radius));
      if (parent.dataset.continuousShape !== 'true') parent.dataset.continuousShape = 'true';
    };
    const measure = () => draw(parent.clientWidth, parent.clientHeight);
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(entries => {
      const box = entries.find(entry => entry.target === parent)?.borderBoxSize[0];
      // Both retained surfaces are borderless. Use the supplied fractional CSS
      // size so the curve follows the height without another geometry read.
      if (box) draw(box.inlineSize, box.blockSize); else measure();
    });
    observer?.observe(parent);
    window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); delete parent.dataset.continuousShape; };
  }, [radius]);
  return <svg ref={ref} className="pw-continuous-surface" data-tone={tone} aria-hidden="true" focusable="false"
    width="100%" height="100%">
    <path ref={path} />
  </svg>;
}
