import { cssEase, motionDuration, motionEase } from '../../../../library/motion-tokens';

export type DrawerMotionPhase = 'enter' | 'exit' | 'settle';

export function drawerMotionAllowed() {
  return (
    typeof window !== 'undefined' &&
    !document.hidden &&
    !window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export function drawerDuration(phase: DrawerMotionPhase = 'enter') {
  if (!drawerMotionAllowed()) return 0;
  return phase === 'exit' ? motionDuration.drawerExit : motionDuration.drawer;
}

export function drawerTransition(property: string, phase: DrawerMotionPhase = 'enter') {
  return `${property} ${drawerDuration(phase)}ms ${cssEase(motionEase[phase])}`;
}
