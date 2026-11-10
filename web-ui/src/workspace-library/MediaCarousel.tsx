import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import useEmblaCarousel from 'embla-carousel-react';
import { useDocumentVisible, useMotionAllowed } from '../library/core';
import { IconArrowLeft, IconArrowRight } from '../icons';
import { IconButton } from '../forms-library';
import './workspace-media.css';

/** Embla owns scrolling, pointer gestures and focus-driven scrolling. The
 * unequal-width composition follows the approved Material multi-browse image. */
export function MediaCarousel({ label, items }: { label: string; items: { id: string; content: ReactNode }[] }) {
  const motionAllowed = useMotionAllowed(), visible = useDocumentVisible();
  const allowed = useRef(motionAllowed); allowed.current = motionAllowed;
  const gesture = useRef({ start: 0, dragging: false, cancelled: false });
  const [viewport, carousel] = useEmblaCarousel({ align: 'start', containScroll: 'trimSnaps', skipSnaps: false, duration: motionAllowed ? 25 : 0, watchDrag: visible });
  const [previous, setPrevious] = useState(false), [next, setNext] = useState(false), [selected, setSelected] = useState(0);
  const update = useCallback(() => { if (carousel) { setPrevious(carousel.canScrollPrev()); setNext(carousel.canScrollNext()); setSelected(carousel.selectedScrollSnap()); } }, [carousel]);
  useEffect(() => { if (!carousel) return; update(); carousel.on('select', update).on('reInit', update); return () => { carousel.off('select', update).off('reInit', update); }; }, [carousel, update]);
  useLayoutEffect(() => {
    if (!motionAllowed && carousel) carousel.scrollTo(carousel.selectedScrollSnap(), true);
  }, [carousel, motionAllowed]);
  useEffect(() => {
    if (!carousel) return;
    const root = carousel.rootNode();
    const reset = () => { gesture.current = { start: carousel.selectedScrollSnap(), dragging: false, cancelled: false }; };
    const down = () => { gesture.current = { start: carousel.selectedScrollSnap(), dragging: true, cancelled: false }; };
    const up = () => {
      const current = gesture.current; current.dragging = false;
      // Embla's drag release uses its own speed rather than options.duration.
      // Its public pointerUp event fires after the target snap is chosen.
      if (current.cancelled) carousel.scrollTo(current.start, true);
      else if (!allowed.current) carousel.scrollTo(carousel.selectedScrollSnap(), true);
      current.cancelled = false;
    };
    const cancel = () => { gesture.current.cancelled = true; };
    const pause = () => {
      const startIndex = gesture.current.dragging ? gesture.current.start : carousel.selectedScrollSnap();
      gesture.current.dragging = false;
      carousel.reInit({ startIndex, duration: 0, watchDrag: false });
    };
    const hide = () => { if (document.hidden) pause(); };
    const show = () => carousel.reInit({ duration: !document.hidden && !window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 25 : 0, watchDrag: !document.hidden });
    carousel.on('pointerDown', down).on('pointerUp', up).on('reInit', reset);
    root.addEventListener('touchcancel', cancel, true);
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', pause); window.addEventListener('pageshow', show);
    return () => {
      carousel.off('pointerDown', down).off('pointerUp', up).off('reInit', reset);
      root.removeEventListener('touchcancel', cancel, true);
      document.removeEventListener('visibilitychange', hide);
      window.removeEventListener('pagehide', pause); window.removeEventListener('pageshow', show);
    };
  }, [carousel]);
  return <section className="pw-media-carousel" aria-label={label} aria-roledescription="轮播">
    <div className="pw-media-carousel-heading"><h3>{label}</h3><div><span className="pw-muted">{items.length} 项</span><IconButton icon={IconArrowLeft} aria-label={`${label}：上一项`} disabled={!visible || !previous} onClick={() => carousel?.scrollPrev(!motionAllowed)} /><IconButton icon={IconArrowRight} aria-label={`${label}：下一项`} disabled={!visible || !next} onClick={() => carousel?.scrollNext(!motionAllowed)} /></div></div>
    <div ref={viewport} className="pw-media-carousel-viewport"><div className="pw-media-carousel-track">{items.map((item, index) => <div className="pw-media-carousel-slide" key={item.id} role="group" aria-roledescription="幻灯片" aria-label={`${index + 1} / ${items.length}`} data-primary={index === selected || undefined}>{item.content}</div>)}</div></div>
  </section>;
}
