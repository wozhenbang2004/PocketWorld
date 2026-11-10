import { useEffect, useId, useRef, useState } from 'react';

/**
 * PW-28. Port of the exact library-approved 2026-10-09 17:03 press/stretch
 * switch. The spring constants, 32/20 track, 16/19 thumb and event paths
 * are retained from upstream/press-stretch-switch.html.source.
 * Product value is controlled: pressing never invents a saved setting.
 */
export function StretchSwitch({ label, description, checked, onChange, disabled = false, busy = false }: {
  label: string; description?: string; checked: boolean; onChange: (next: boolean) => void;
  disabled?: boolean; busy?: boolean;
}) {
  const labelId = useId();
  const thumb = useRef<HTMLSpanElement>(null);
  const physical = useRef({ x: checked ? 12 : 0, width: 16, xv: 0, wv: 0, frame: 0, previous: 0 });
  const [pressed, setPressed] = useState(false);
  useEffect(() => {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const p = physical.current;
    const render = () => {
      if (!thumb.current) return;
      thumb.current.style.transform = `translateX(${p.x}px)`;
      thumb.current.style.width = `${p.width}px`;
    };
    const targets = () => { const width = pressed && !disabled && !busy && !motion.matches && !document.hidden ? 19 : 16; return [checked ? 28 - width : 0, width]; };
    const step = (now: number) => {
      const dt = Math.min((now - p.previous) / 1000 || 1 / 60, 0.024); p.previous = now;
      const [tx, tw] = targets();
      p.xv += (300 * (tx - p.x) - 25 * p.xv) * dt;
      p.wv += (300 * (tw - p.width) - 25 * p.wv) * dt;
      p.x += p.xv * dt; p.width += p.wv * dt; render();
      if (Math.abs(tx - p.x) + Math.abs(tw - p.width) + Math.abs(p.xv) + Math.abs(p.wv) > 0.015) p.frame = requestAnimationFrame(step);
      else { p.x = tx; p.width = tw; p.xv = p.wv = 0; p.frame = 0; render(); }
    };
    const update = () => {
      cancelAnimationFrame(p.frame); p.frame = 0;
      if (motion.matches || document.hidden) { [p.x, p.width] = targets(); p.xv = p.wv = 0; render(); }
      else { p.previous = performance.now(); p.frame = requestAnimationFrame(step); }
    };
    const visibility = () => { if (document.hidden) setPressed(false); update(); };
    const blur = () => setPressed(false);
    update(); motion.addEventListener('change', update); document.addEventListener('visibilitychange', visibility); window.addEventListener('blur', blur);
    return () => { cancelAnimationFrame(p.frame); p.frame = 0; motion.removeEventListener('change', update); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('blur', blur); };
  }, [checked, pressed, disabled, busy]);
  return <div className="pw-stretch-setting">
    <div><span id={labelId} className="pw-stretch-label">{label}</span>{description && <span className="pw-stretch-hint">{description}</span>}</div>
    <button type="button" role="switch" aria-checked={checked} aria-labelledby={labelId}
      aria-busy={busy || undefined} disabled={disabled || busy} className="pw-stretch-hit"
      onPointerDown={() => setPressed(true)} onPointerUp={() => setPressed(false)}
      onPointerCancel={() => setPressed(false)} onPointerLeave={() => setPressed(false)} onBlur={() => setPressed(false)}
      onKeyDown={e => { if (e.key === ' ' || e.key === 'Enter') setPressed(true); }} onKeyUp={() => setPressed(false)}
      onClick={() => { setPressed(false); onChange(!checked); }}>
      <span className="pw-stretch-track" aria-hidden="true"><span ref={thumb} className="pw-stretch-thumb" /></span>
    </button>
  </div>;
}
