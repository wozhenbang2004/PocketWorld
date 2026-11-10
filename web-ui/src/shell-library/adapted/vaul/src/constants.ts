import { motionDuration, motionEase } from '../../../../library/motion-tokens';

export const TRANSITIONS = {
  DURATION: motionDuration.drawer / 1000,
  EXIT_DURATION: motionDuration.drawerExit / 1000,
  EASE: motionEase.enter,
  EXIT_EASE: motionEase.exit,
  SETTLE_EASE: motionEase.settle,
};

export const VELOCITY_THRESHOLD = 0.4;

export const CLOSE_THRESHOLD = 0.25;

export const SCROLL_LOCK_TIMEOUT = 100;

export const BORDER_RADIUS = 8;

export const NESTED_DISPLACEMENT = 16;

export const WINDOW_TOP_OFFSET = 26;

export const DRAG_CLASS = 'vaul-dragging';
