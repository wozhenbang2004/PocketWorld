import { useCallback, useLayoutEffect, useRef, type ReactNode } from 'react';

/** Keep the existing navigation and notices above the real page scroller.
 * Their measured height also reserves the readable start of that same page. */
export function NavigationChrome({ children }: { children: ReactNode }) {
  const root = useRef<HTMLDivElement>(null);
  const measure = useCallback(() => {
    const element = root.current;
    if (!element?.parentElement) return;
    const height = `${element.offsetHeight}px`;
    const workspace = element.parentElement;
    if (workspace.style.getPropertyValue('--pw-navigation-chrome-height') !== height) {
      workspace.style.setProperty('--pw-navigation-chrome-height', height);
    }
  }, []);

  // Run before App restores its reading anchors, including after a route or
  // notice changes. ResizeObserver also covers fonts and a resized viewport.
  useLayoutEffect(measure);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element) return;
    const workspace = element.parentElement;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => {
      observer.disconnect();
      workspace?.style.removeProperty('--pw-navigation-chrome-height');
    };
  }, [measure]);

  return <div ref={root} className="pw-navigation-chrome">{children}</div>;
}
