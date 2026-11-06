import { useCallback, useEffect, useState, type ReactNode } from 'react';
import useEmblaCarousel from 'embla-carousel-react';
import { useReducedMotion } from 'motion/react';
import { RiArrowLeftLine, RiArrowRightLine } from '@remixicon/react';
import { IconButton } from '../forms-library';
import './workspace-media.css';

/** Embla owns scrolling, pointer gestures and focus-driven scrolling. The
 * unequal-width composition follows the approved Material multi-browse image. */
export function MediaCarousel({ label, items }: { label: string; items: { id: string; content: ReactNode }[] }) {
  const reduced = useReducedMotion();
  const [viewport, carousel] = useEmblaCarousel({ align: 'start', containScroll: 'trimSnaps', skipSnaps: false, duration: reduced ? 0 : 25 });
  const [previous, setPrevious] = useState(false), [next, setNext] = useState(false), [selected, setSelected] = useState(0);
  const update = useCallback(() => { if (carousel) { setPrevious(carousel.canScrollPrev()); setNext(carousel.canScrollNext()); setSelected(carousel.selectedScrollSnap()); } }, [carousel]);
  useEffect(() => { if (!carousel) return; update(); carousel.on('select', update).on('reInit', update); return () => { carousel.off('select', update).off('reInit', update); }; }, [carousel, update]);
  return <section className="pw-media-carousel" aria-label={label} aria-roledescription="轮播">
    <div className="pw-media-carousel-heading"><h3>{label}</h3><div><span className="pw-muted">{items.length} 项</span><IconButton icon={RiArrowLeftLine} aria-label={`${label}：上一项`} disabled={!previous} onClick={() => carousel?.scrollPrev()} /><IconButton icon={RiArrowRightLine} aria-label={`${label}：下一项`} disabled={!next} onClick={() => carousel?.scrollNext()} /></div></div>
    <div ref={viewport} className="pw-media-carousel-viewport"><div className="pw-media-carousel-track">{items.map((item, index) => <div className="pw-media-carousel-slide" key={item.id} role="group" aria-roledescription="幻灯片" aria-label={`${index + 1} / ${items.length}`} data-primary={index === selected || undefined}>{item.content}</div>)}</div></div>
  </section>;
}
