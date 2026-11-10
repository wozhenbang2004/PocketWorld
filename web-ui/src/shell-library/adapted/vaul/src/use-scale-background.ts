import React, { useMemo } from 'react';
import { useDrawerContext } from './context';
import { assignStyle, chain, isVertical, reset } from './helpers';
import { BORDER_RADIUS, TRANSITIONS, WINDOW_TOP_OFFSET } from './constants';
import { drawerDuration, drawerMotionAllowed } from './motion';

const noop = () => () => {};

export function useScaleBackground() {
  const { direction, isOpen, shouldScaleBackground, setBackgroundColorOnScale, noBodyStyles } = useDrawerContext();
  const timeoutIdRef = React.useRef<number | null>(null);
  const initialBackgroundColor = useMemo(() => document.body.style.backgroundColor, []);
  const [motionAllowed, setMotionAllowed] = React.useState(drawerMotionAllowed);
  const resumeWithoutMotion = React.useRef(false);

  React.useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => {
      if (!drawerMotionAllowed()) resumeWithoutMotion.current = true;
      setMotionAllowed(drawerMotionAllowed());
    };
    preference.addEventListener('change', update);
    document.addEventListener('visibilitychange', update);
    return () => {
      preference.removeEventListener('change', update);
      document.removeEventListener('visibilitychange', update);
    };
  }, []);

  function getScale() {
    return (window.innerWidth - WINDOW_TOP_OFFSET) / window.innerWidth;
  }

  React.useEffect(() => {
    if (isOpen && shouldScaleBackground && motionAllowed) {
      if (timeoutIdRef.current) clearTimeout(timeoutIdRef.current);
      const wrapper =
        (document.querySelector('[data-vaul-drawer-wrapper]') as HTMLElement) ||
        (document.querySelector('[vaul-drawer-wrapper]') as HTMLElement);

      if (!wrapper) return;

      chain(
        setBackgroundColorOnScale && !noBodyStyles ? assignStyle(document.body, { background: 'black' }) : noop,
        assignStyle(wrapper, {
          transformOrigin: isVertical(direction) ? 'top' : 'left',
          transitionProperty: 'transform, border-radius',
          transitionDuration: `${resumeWithoutMotion.current ? 0 : drawerDuration()}ms`,
          transitionTimingFunction: `cubic-bezier(${TRANSITIONS.EASE.join(',')})`,
        }),
      );
      resumeWithoutMotion.current = false;

      const wrapperStylesCleanup = assignStyle(wrapper, {
        borderRadius: `${BORDER_RADIUS}px`,
        overflow: 'hidden',
        ...(isVertical(direction)
          ? {
              transform: `scale(${getScale()}) translate3d(0, calc(env(safe-area-inset-top) + 14px), 0)`,
            }
          : {
              transform: `scale(${getScale()}) translate3d(calc(env(safe-area-inset-top) + 14px), 0, 0)`,
            }),
      });

      return () => {
        wrapperStylesCleanup();
        wrapper.style.transitionDuration = `${drawerDuration('exit')}ms`;
        wrapper.style.transitionTimingFunction = `cubic-bezier(${TRANSITIONS.EXIT_EASE.join(',')})`;
        timeoutIdRef.current = window.setTimeout(() => {
          if (initialBackgroundColor) {
            document.body.style.background = initialBackgroundColor;
          } else {
            document.body.style.removeProperty('background');
          }
        }, drawerDuration('exit'));
      };
    }
  }, [isOpen, shouldScaleBackground, initialBackgroundColor, motionAllowed]);
}
