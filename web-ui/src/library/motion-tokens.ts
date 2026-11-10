import type {Transition} from 'motion/react';

// Product timings for the retained Ovio, Affect, BoardUI and Vaul adaptations.
// Durations are milliseconds here; Motion transitions below use seconds.
export const motionDuration = {
 press: 80,
 release: 220,
 feedback: 140,
 enter: 220,
 exit: 160,
 layout: 280,
 drawer: 320,
 drawerExit: 240,
} as const;

export const motionEase = {
 enter: [0, 0, .58, 1],
 settle: [.22, 1, .36, 1],
 exit: [.42, 0, 1, 1],
 move: [.42, 0, .58, 1],
} as const;

export const motionTransition = {
 enter: {duration: motionDuration.enter / 1000, ease: motionEase.enter},
 exit: {duration: motionDuration.exit / 1000, ease: motionEase.exit},
 settle: {duration: motionDuration.layout / 1000, ease: motionEase.settle},
 layout: {type: 'spring', stiffness: 420, damping: 36, mass: .85},
 selection: {type: 'spring', stiffness: 440, damping: 30, mass: .8},
 feedback: {duration: motionDuration.feedback / 1000, ease: motionEase.settle},
 instant: {duration: 0},
} as const satisfies Record<string, Transition>;

export const cssEase = (value: readonly number[]) => `cubic-bezier(${value.join(',')})`;
