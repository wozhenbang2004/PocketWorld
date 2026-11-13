import type {Transition} from 'motion/react';

// Shared timing roles adapted from MangaFlow 246a8ae / MangaMotion.kt.
// Retains the existing Ovio, Affect, BoardUI and Vaul components.
// Compose spring damping ratios are not copied as Motion damping coefficients.
// Durations are milliseconds here; Motion transitions below use seconds.
export const motionDuration = {
 press: 120,
 release: 220,
 feedback: 180,
 listEnter: 200,
 enter: 260,
 exit: 180,
 layout: 280,
 drawer: 320,
 drawerExit: 240,
} as const;

export const motionEase = {
 enter: [.22, 1, .36, 1],
 settle: [.22, 1, .36, 1],
 exit: [.4, 0, 1, 1],
 move: [.42, 0, .58, 1],
 listFade: [.2, 0, .15, 1],
} as const;

export const motionTransition = {
 enter: {duration: motionDuration.enter / 1000, ease: motionEase.enter},
 exit: {duration: motionDuration.exit / 1000, ease: motionEase.exit},
 settle: {duration: motionDuration.layout / 1000, ease: motionEase.settle},
 layout: {type: 'spring', stiffness: 420, damping: 36, mass: .85},
 // MangaFlow Panel: unit-mass k=840, damping ratio=1.38 -> c≈80.
 panel: {type: 'spring', stiffness: 840, damping: 80, mass: 1},
 selection: {type: 'spring', stiffness: 440, damping: 30, mass: .8},
 // MangaFlow MotionTabs: independent leading/trailing edges, unit mass.
 tabLeading: {type: 'spring', stiffness: 520, damping: 46.02, mass: 1},
 tabTrailing: {type: 'spring', stiffness: 230, damping: 30, mass: 1},
 feedback: {duration: motionDuration.feedback / 1000, ease: motionEase.settle},
 // MangaFlow: attack k=1600, damping ratio=1 with unit mass.
 // Motion mass=.6: k=960 and c=48 preserve that response.
 pressAttack: {type: 'spring', stiffness: 960, damping: 48, mass: .6},
 // Release preserves the original beUI 500/30/.6 source spring.
 press: {type: 'spring', stiffness: 500, damping: 30, mass: .6},
 instant: {duration: 0},
} as const satisfies Record<string, Transition>;

export const cssEase = (value: readonly number[]) => `cubic-bezier(${value.join(',')})`;
