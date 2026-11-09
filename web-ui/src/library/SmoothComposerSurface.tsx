import { useLayoutEffect, useRef, useState } from 'react';
import { continuousRectPath } from './continuous-corners';
import '../composer-native-detail.css';

/** Background only: never clip text, controls, popovers or keyboard focus. */
export function SmoothComposerSurface({ radius = 30, tone = 'surface' }: { radius?: number; tone?: 'surface' | 'context' }) {
  const ref = useRef<SVGSVGElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  useLayoutEffect(() => {
    const parent = ref.current?.parentElement;
    if (!parent) return;
    const measure = () => {
      const width = parent.clientWidth, height = parent.clientHeight;
      if (width <= 0 || height <= 0) { delete parent.dataset.continuousShape; return; }
      parent.dataset.continuousShape = 'true';
      setSize(old => old.width === width && old.height === height ? old : { width, height });
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(parent);
    window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); delete parent.dataset.continuousShape; };
  }, []);
  return <svg ref={ref} className="pw-continuous-surface" data-tone={tone} aria-hidden="true" focusable="false"
    width="100%" height="100%" viewBox={size.width && size.height ? `0 0 ${size.width} ${size.height}` : undefined}>
    <path d={continuousRectPath(size.width, size.height, radius)} />
  </svg>;
}
