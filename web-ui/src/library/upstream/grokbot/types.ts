// State, shape and expression names ported from nasawz/GrokBot
// (BSD-3-Clause): lib/src/models.dart
/** Normalized 2D vector used for gaze and geometry helpers. */
export interface Vec2 {
  x: number;
  y: number;
}

/**
 * The 39 behavior states. Each one selects an expression pool plus expression
 * and blink cadences.
 *
 * These are kebab-case strings rather than an enum so they survive JSON,
 * HTML attributes and query params unchanged.
 */
export type GrokBotState =
  // lifecycle
  | 'sleeping'
  | 'waking'
  | 'idle'
  | 'listening'
  | 'thinking'
  | 'searching'
  | 'working'
  // reactions
  | 'excited'
  | 'surprised'
  | 'suspicious'
  | 'angry'
  | 'drowsy'
  | 'happy'
  | 'curious'
  | 'confused'
  | 'bored'
  | 'proud'
  | 'shy'
  | 'sad'
  | 'laughing'
  | 'scared'
  | 'playful'
  | 'celebrate'
  // agent shapes
  | 'orbit'
  | 'radar'
  | 'progress'
  // product lifecycle
  | 'spawning'
  | 'humming'
  | 'loading'
  | 'dictating'
  | 'writing'
  | 'sending'
  | 'receiving'
  | 'uploading'
  | 'notifying'
  | 'alerting'
  | 'dragging'
  | 'bouncing'
  | 'powering-down';

/** The 18 body silhouettes. */
export type GrokBotShape =
  | 'blob'
  | 'pebble'
  | 'bean'
  | 'egg'
  | 'squircle'
  | 'tablet'
  | 'capsule'
  | 'cylinder'
  | 'hex'
  | 'gem'
  | 'crystal'
  | 'wedge'
  | 'shield'
  | 'dome'
  | 'arch'
  | 'cloud'
  | 'teardrop'
  | 'leaf';

/** Index of one of the 25 built-in eye expressions. */
export type GrokBotExpression =
  | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9
  | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19
  | 20 | 21 | 22 | 23 | 24;

/** Colors used by the painter. All values are CSS color strings. */
export interface GrokBotTheme {
  /** Body fill. */
  bodyColor: string;
  /** Eye fill. */
  eyeColor: string;
  /** Spherical guide stroke, drawn only when `showGuides` is on. */
  guideColor: string;
  /** Projected eye centroid markers, drawn only when `showGuides` is on. */
  centroidColor: string;
  /** Reserved for a future badge effect. Not painted. */
  badgeColor: string;
  /** Reserved for future particle effects. Not painted. */
  particleColor: string;
}

/** Every tunable input of a GrokBot instance. */
export interface GrokBotOptions {
  /** Selects the expression pool and the automatic cadences. Default `'idle'`. */
  state: GrokBotState;
  /** Selects the silhouette and its face geometry correction. Default `'blob'`. */
  shape: GrokBotShape;
  /** Pins one expression. `null` hands control back to the state pool. Default `null`. */
  expression: GrokBotExpression | null;
  /** Normalized gaze, each axis clamped to [-1, 1]. Default `{ x: 0, y: 0 }`. */
  gaze: Vec2;
  /** Base head turn in radians; `spin()` adds on top of it. Default `0`. */
  turn: number;
  /** Multiplies the base eye size. Must be > 0. Default `1`. */
  eyeScale: number;
  /** Natural frequency of the expression morph spring. Must be > 0. Default `7`. */
  springFrequency: number;
  /** Blink on the state's cadence. Default `true`. */
  autoBlink: boolean;
  /** Rotate through the state's expression pool. Ignored while `expression` is pinned. Default `false`. */
  autoExpression: boolean;
  /** Mirror the whole avatar horizontally. Default `false`. */
  flipX: boolean;
  /** Enlarge the eyes by 18%. Default `false`. */
  emphasis: boolean;
  /** Paint the spherical guide and the projected eye centroids. Default `false`. */
  showGuides: boolean;
  /** Colors. Partial themes are merged onto the light preset. */
  theme: GrokBotTheme;
  /**
   * CSS pixel side length, or `'auto'` to track the canvas element's own box
   * via `ResizeObserver`. Default `'auto'`.
   */
  size: number | 'auto';
  /** Accessible label written to the canvas element. Default `'GrokBot'`. */
  ariaLabel: string;
  /**
   * When true and the user asks for reduced motion, automatic blinking and
   * expression changes stop and morphs land instantly. Default `true`.
   *
   * Web-only: there is no equivalent in the Flutter original.
   */
  respectReducedMotion: boolean;
  /** Randomness source for cadence jitter and pool picks. Default `Math.random`. */
  random: () => number;
}
