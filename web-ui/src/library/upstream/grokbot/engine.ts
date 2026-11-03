// Ported from nasawz/GrokBot (BSD-3-Clause): lib/src/grokbot.dart
import { EYE_COUNT, RING_POINTS, expressionRings } from './data/expressions';
import { type Cadence, stateData } from './data/states';
import { shapeData } from './data/shapes';
import { easeInOutCubic } from './easing';
import { clamp } from './geometry';
import { type Canvas2DLike, type PaintFrame, paintGrokBot } from './painter';
import { lightTheme, resolveTheme } from './theme';
import type { GrokBotExpression, GrokBotOptions, GrokBotState } from './types';

/** Blink duration in seconds: 42% closing, 58% opening. */
const BLINK_SECONDS = 0.32;
const BLINK_CLOSE_FRACTION = 0.42;
const BLINK_MINIMUM_SCALE = 0.04;
/** Fixed spring sub-step, matching the Flutter original's ~120 Hz integration. */
const SPRING_STEP = 1 / 120;
/** Largest real frame delta the spring will integrate, so a stalled tab cannot explode it. */
const MAX_FRAME_SECONDS = 0.1;
const SETTLE_EPSILON = 0.001;

const defaultOptions: GrokBotOptions = {
  state: 'idle',
  shape: 'blob',
  expression: null,
  gaze: { x: 0, y: 0 },
  turn: 0,
  eyeScale: 1,
  springFrequency: 7,
  autoBlink: true,
  autoExpression: false,
  flipX: false,
  emphasis: false,
  showGuides: false,
  theme: lightTheme,
  size: 'auto',
  ariaLabel: 'GrokBot',
  respectReducedMotion: true,
  random: Math.random,
};

/** Constructor input: every option is optional, `theme` may be partial. */
export type GrokBotInit = Partial<Omit<GrokBotOptions, 'theme'>> & {
  theme?: Partial<GrokBotOptions['theme']> | null;
};

export interface SpinOptions {
  /** Full turns to rotate through. Negative values spin the other way. Default `1`. */
  turns?: number;
  /** Duration in milliseconds. Default `1200`. */
  durationMs?: number;
}

function cloneRing(source: readonly number[]): number[] {
  return Array.from(source);
}

function ringsFor(expression: GrokBotExpression): number[][] {
  return expressionRings[expression].map(cloneRing);
}

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * An animated GrokBot bound to one canvas.
 *
 * Unlike the Flutter original there is no separate controller type: a Flutter
 * widget is immutable so transient animations need an external handle, while
 * this instance is long-lived and owns both the declarative options
 * (`update()`) and the imperative moments (`blink()`, `spin()`, `reset()`).
 */
export class GrokBot {
  readonly #canvas: HTMLCanvasElement;
  readonly #ctx: Canvas2DLike;
  #options: GrokBotOptions;

  #currentRings: number[][];
  #targetRings: number[][];
  #currentExpression: GrokBotExpression;
  #morph = 1;
  #velocity = 0;

  #blinkSeconds: number | null = null;
  #blinkResolve: (() => void) | null = null;
  #spinSeconds: number | null = null;
  #spinDurationSeconds = 1.2;
  #spinTurns = 1;
  #spinAngle = 0;
  #spinResolve: (() => void) | null = null;

  #blinkTimer: ReturnType<typeof setTimeout> | null = null;
  #expressionTimer: ReturnType<typeof setTimeout> | null = null;

  #frame: number | null = null;
  #lastTick: number | null = null;
  #destroyed = false;

  #cssWidth = 0;
  #cssHeight = 0;
  #resizeObserver: ResizeObserver | null = null;
  #motionQuery: MediaQueryList | null = null;
  #onMotionChange: (() => void) | null = null;
  #onVisibilityChange: (() => void) | null = null;

  constructor(canvas: HTMLCanvasElement, init: GrokBotInit = {}) {
    this.#canvas = canvas;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('GrokBot: canvas 2d context is unavailable.');
    this.#ctx = ctx as unknown as Canvas2DLike;

    this.#options = { ...defaultOptions, ...init, theme: resolveTheme(init.theme) };
    assertOptions(this.#options);

    this.#currentExpression = this.#resolveExpression();
    this.#currentRings = ringsFor(this.#currentExpression);
    this.#targetRings = this.#currentRings.map(cloneRing);

    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', this.#options.ariaLabel);

    this.#observeSize();
    this.#observeMotionPreference();
    this.#observeVisibility();
    this.#measure();
    this.#scheduleBlink();
    this.#scheduleExpression();
    this.#requestPaint();
  }

  /** The current, fully resolved options. */
  get options(): Readonly<GrokBotOptions> {
    return this.#options;
  }

  /** The expression currently being displayed or morphed toward. */
  get expression(): GrokBotExpression {
    return this.#currentExpression;
  }

  /** Whether any animation is in flight. */
  get isAnimating(): boolean {
    return this.#morphActive() || this.#blinkSeconds !== null || this.#spinSeconds !== null;
  }

  /** Applies a partial options patch, restarting only what the change affects. */
  update(patch: GrokBotInit): void {
    if (this.#destroyed) return;
    const previous = this.#options;
    const next: GrokBotOptions = {
      ...previous,
      ...patch,
      theme: patch.theme ? resolveTheme(patch.theme, previous.theme) : previous.theme,
    };
    assertOptions(next);
    this.#options = next;

    if (next.ariaLabel !== previous.ariaLabel) {
      this.#canvas.setAttribute('aria-label', next.ariaLabel);
    }
    if (next.size !== previous.size) {
      this.#observeSize();
      this.#measure();
    }

    const stateChanged = next.state !== previous.state;
    const expressionChanged = next.expression !== previous.expression;
    if (stateChanged || expressionChanged) {
      this.#selectExpression(this.#resolveExpression());
    }
    if (stateChanged || next.autoBlink !== previous.autoBlink) {
      this.#scheduleBlink();
    }
    if (stateChanged || expressionChanged || next.autoExpression !== previous.autoExpression) {
      this.#scheduleExpression();
    }
    this.#requestPaint();
  }

  /** Runs one 320 ms blink. Restarting replaces any blink already in flight. */
  blink(): Promise<void> {
    if (this.#destroyed) return Promise.resolve();
    this.#blinkResolve?.();
    this.#blinkSeconds = 0;
    return new Promise<void>((resolve) => {
      this.#blinkResolve = resolve;
      this.#requestPaint();
    });
  }

  /** Spins by whole turns. Starting another spin completes and replaces the active one. */
  spin({ turns = 1, durationMs = 1200 }: SpinOptions = {}): Promise<void> {
    if (this.#destroyed || turns === 0) return Promise.resolve();
    if (!(durationMs > 0)) {
      throw new RangeError(`GrokBot.spin: durationMs must be greater than zero, got ${durationMs}.`);
    }
    this.#spinResolve?.();
    this.#spinSeconds = 0;
    this.#spinDurationSeconds = durationMs / 1000;
    this.#spinTurns = turns;
    this.#spinAngle = 0;
    return new Promise<void>((resolve) => {
      this.#spinResolve = resolve;
      this.#requestPaint();
    });
  }

  /**
   * Cancels transient animations and snaps back to the resting expression.
   * Options supplied by the caller are left untouched.
   */
  reset(): void {
    if (this.#destroyed) return;
    this.#blinkResolve?.();
    this.#blinkResolve = null;
    this.#blinkSeconds = null;
    this.#spinResolve?.();
    this.#spinResolve = null;
    this.#spinSeconds = null;
    this.#spinAngle = 0;
    this.#selectExpression(this.#resolveExpression(), false);
    this.#scheduleBlink();
    this.#scheduleExpression();
    this.#requestPaint();
  }

  /** Paints one frame immediately, outside the animation loop. */
  render(): void {
    if (this.#destroyed) return;
    this.#paint();
  }

  /** Stops every timer and observer and releases the canvas. */
  destroy(): void {
    if (this.#destroyed) return;
    this.#destroyed = true;
    if (this.#frame !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(this.#frame);
    }
    this.#frame = null;
    if (this.#blinkTimer) clearTimeout(this.#blinkTimer);
    if (this.#expressionTimer) clearTimeout(this.#expressionTimer);
    this.#blinkTimer = null;
    this.#expressionTimer = null;
    this.#blinkResolve?.();
    this.#spinResolve?.();
    this.#blinkResolve = null;
    this.#spinResolve = null;
    this.#resizeObserver?.disconnect();
    this.#resizeObserver = null;
    if (this.#motionQuery && this.#onMotionChange) {
      this.#motionQuery.removeEventListener('change', this.#onMotionChange);
    }
    this.#motionQuery = null;
    this.#onMotionChange = null;
    if (this.#onVisibilityChange && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.#onVisibilityChange);
    }
    this.#onVisibilityChange = null;
  }

  // ---------------------------------------------------------------- internals

  get #stateData() {
    return stateData[this.#options.state];
  }

  #reducedMotion(): boolean {
    return this.#options.respectReducedMotion && prefersReducedMotion();
  }

  #resolveExpression(): GrokBotExpression {
    return this.#options.expression ?? this.#stateData.expressions[0];
  }

  #randomDuration(cadence: Cadence): number {
    const range = cadence.max - cadence.min;
    return cadence.min + (range <= 0 ? 0 : Math.floor(this.#options.random() * (range + 1)));
  }

  #scheduleBlink(): void {
    if (this.#blinkTimer) clearTimeout(this.#blinkTimer);
    this.#blinkTimer = null;
    const cadence = this.#stateData.blinkCadence;
    if (!this.#options.autoBlink || !cadence || this.#reducedMotion()) return;
    this.#blinkTimer = setTimeout(() => {
      if (this.#destroyed) return;
      void this.blink();
      this.#scheduleBlink();
    }, this.#randomDuration(cadence));
  }

  #scheduleExpression(): void {
    if (this.#expressionTimer) clearTimeout(this.#expressionTimer);
    this.#expressionTimer = null;
    const { autoExpression, expression } = this.#options;
    if (!autoExpression || expression !== null || this.#reducedMotion()) return;
    this.#expressionTimer = setTimeout(() => {
      if (this.#destroyed) return;
      const pool = this.#stateData.expressions;
      const alternatives = pool.filter((item) => item !== this.#currentExpression);
      const next = alternatives.length === 0
        ? pool[0]
        : alternatives[Math.min(alternatives.length - 1, Math.floor(this.#options.random() * alternatives.length))];
      this.#selectExpression(next);
      this.#scheduleExpression();
    }, this.#randomDuration(this.#stateData.expressionCadence));
  }

  #displayedRings(): number[][] {
    const amount = clamp(this.#morph, 0, 1);
    const rings: number[][] = [];
    for (let eye = 0; eye < EYE_COUNT; eye += 1) {
      const from = this.#currentRings[eye];
      const to = this.#targetRings[eye];
      const ring = new Array<number>(RING_POINTS * 2);
      for (let i = 0; i < ring.length; i += 1) {
        ring[i] = from[i] + (to[i] - from[i]) * amount;
      }
      rings.push(ring);
    }
    return rings;
  }

  #selectExpression(expression: GrokBotExpression, animate = true): void {
    if (expression === this.#currentExpression && this.#morph === 1) return;
    if (animate && !this.#reducedMotion()) {
      this.#currentRings = this.#displayedRings();
      this.#targetRings = ringsFor(expression);
      this.#morph = 0;
      this.#velocity = 0;
      this.#currentExpression = expression;
    } else {
      this.#currentExpression = expression;
      this.#currentRings = ringsFor(expression);
      this.#targetRings = this.#currentRings.map(cloneRing);
      this.#morph = 1;
      this.#velocity = 0;
    }
    this.#requestPaint();
  }

  #morphActive(): boolean {
    return Math.abs(this.#morph - 1) >= SETTLE_EPSILON || Math.abs(this.#velocity) >= SETTLE_EPSILON;
  }

  #blinkScale(): number {
    const seconds = this.#blinkSeconds;
    if (seconds === null) return 1;
    const progress = seconds / BLINK_SECONDS;
    const value = progress < BLINK_CLOSE_FRACTION
      ? 1 - progress / BLINK_CLOSE_FRACTION
      : (progress - BLINK_CLOSE_FRACTION) / (1 - BLINK_CLOSE_FRACTION);
    return Math.max(value, BLINK_MINIMUM_SCALE);
  }

  #requestPaint(): void {
    // A hidden tab never runs animation frames, so falling back to a synchronous
    // paint is what keeps the canvas from staying blank until it is focused.
    if (!this.#ensureLoop()) this.render();
  }

  /** Returns whether an animation frame is pending or already scheduled. */
  #ensureLoop(): boolean {
    if (this.#destroyed) return true;
    if (this.#frame !== null) return true;
    if (typeof requestAnimationFrame !== 'function') return false;
    if (typeof document !== 'undefined' && document.hidden) return false;
    this.#lastTick = null;
    this.#frame = requestAnimationFrame(this.#tick);
    return true;
  }

  readonly #tick = (timestamp: number): void => {
    this.#frame = null;
    if (this.#destroyed) return;

    const previous = this.#lastTick;
    this.#lastTick = timestamp;
    const rawDt = previous === null ? 0 : (timestamp - previous) / 1000;
    if (rawDt > 0) this.#advance(rawDt);

    this.#paint();

    // A first frame only establishes the timebase, so keep going whenever an
    // animation is live; a lone dirty repaint stops here.
    if (this.isAnimating) {
      this.#frame = requestAnimationFrame(this.#tick);
    }
  };

  #advance(rawDt: number): void {
    const springDt = Math.min(rawDt, MAX_FRAME_SECONDS);
    const omega = this.#options.springFrequency;

    if (this.#morphActive()) {
      let remaining = springDt;
      while (remaining > 0) {
        const step = Math.min(remaining, SPRING_STEP);
        this.#velocity +=
          (-2 * omega * this.#velocity - omega * omega * (this.#morph - 1)) * step;
        this.#morph += this.#velocity * step;
        remaining -= step;
      }
      if (!this.#morphActive()) {
        this.#morph = 1;
        this.#velocity = 0;
        this.#currentRings = this.#targetRings.map(cloneRing);
      }
    }

    if (this.#blinkSeconds !== null) {
      this.#blinkSeconds += rawDt;
      if (this.#blinkSeconds >= BLINK_SECONDS) {
        this.#blinkSeconds = null;
        this.#blinkResolve?.();
        this.#blinkResolve = null;
      }
    }

    if (this.#spinSeconds !== null) {
      this.#spinSeconds += rawDt;
      const progress = clamp(this.#spinSeconds / this.#spinDurationSeconds, 0, 1);
      this.#spinAngle = easeInOutCubic(progress) * Math.PI * 2 * this.#spinTurns;
      if (progress >= 1) {
        this.#spinSeconds = null;
        this.#spinAngle = 0;
        this.#spinResolve?.();
        this.#spinResolve = null;
      }
    }
  }

  #frameData(): PaintFrame {
    const options = this.#options;
    return {
      rings: this.#displayedRings(),
      shape: shapeData[options.shape],
      gaze: options.gaze,
      turn: options.turn + this.#spinAngle,
      eyeScale: options.eyeScale,
      blinkScale: this.#blinkScale(),
      flipX: options.flipX,
      emphasis: options.emphasis,
      showGuides: options.showGuides,
      theme: options.theme,
    };
  }

  #paint(): void {
    const { width, height } = this.#measure();
    if (width <= 0 || height <= 0) return;
    this.#ctx.save();
    this.#ctx.clearRect(0, 0, width, height);
    paintGrokBot(this.#ctx, this.#frameData(), width, height);
    this.#ctx.restore();
  }

  /** Syncs the backing store to the CSS box and the device pixel ratio. */
  #measure(): { width: number; height: number } {
    const canvas = this.#canvas;
    const { size } = this.#options;
    let cssWidth: number;
    let cssHeight: number;

    if (size === 'auto') {
      cssWidth = canvas.clientWidth || canvas.width || 0;
      cssHeight = canvas.clientHeight || canvas.height || 0;
    } else {
      cssWidth = size;
      cssHeight = size;
      if (canvas.style) {
        canvas.style.width = `${size}px`;
        canvas.style.height = `${size}px`;
      }
    }

    const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
    const backingWidth = Math.max(1, Math.round(cssWidth * dpr));
    const backingHeight = Math.max(1, Math.round(cssHeight * dpr));
    if (canvas.width !== backingWidth) canvas.width = backingWidth;
    if (canvas.height !== backingHeight) canvas.height = backingHeight;
    // Resetting width/height clears the transform, so re-apply it every measure.
    const ctx = this.#ctx as unknown as CanvasRenderingContext2D;
    if (typeof ctx.setTransform === 'function') ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    this.#cssWidth = cssWidth;
    this.#cssHeight = cssHeight;
    return { width: cssWidth, height: cssHeight };
  }

  #observeSize(): void {
    this.#resizeObserver?.disconnect();
    this.#resizeObserver = null;
    if (this.#options.size !== 'auto') return;
    if (typeof ResizeObserver !== 'function') return;
    this.#resizeObserver = new ResizeObserver(() => {
      if (this.#destroyed) return;
      if (this.#canvas.clientWidth === this.#cssWidth && this.#canvas.clientHeight === this.#cssHeight) return;
      this.#requestPaint();
    });
    this.#resizeObserver.observe(this.#canvas);
  }

  #observeMotionPreference(): void {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    this.#motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    this.#onMotionChange = () => {
      if (this.#destroyed) return;
      this.#scheduleBlink();
      this.#scheduleExpression();
      this.#requestPaint();
    };
    this.#motionQuery.addEventListener('change', this.#onMotionChange);
  }

  #observeVisibility(): void {
    if (typeof document === 'undefined') return;
    this.#onVisibilityChange = () => {
      if (this.#destroyed) return;
      if (!document.hidden) this.#requestPaint();
    };
    document.addEventListener('visibilitychange', this.#onVisibilityChange);
  }
}

function assertOptions(options: GrokBotOptions): void {
  if (!(options.eyeScale > 0)) {
    throw new RangeError(`GrokBot: eyeScale must be greater than zero, got ${options.eyeScale}.`);
  }
  if (!(options.springFrequency > 0)) {
    throw new RangeError(
      `GrokBot: springFrequency must be greater than zero, got ${options.springFrequency}.`,
    );
  }
  if (options.size !== 'auto' && !(options.size > 0)) {
    throw new RangeError(`GrokBot: size must be 'auto' or greater than zero, got ${options.size}.`);
  }
  if (!(options.state in stateData)) {
    throw new RangeError(`GrokBot: unknown state ${String(options.state)}.`);
  }
  if (!(options.shape in shapeData)) {
    throw new RangeError(`GrokBot: unknown shape ${String(options.shape)}.`);
  }
  if (options.expression !== null && !expressionRings[options.expression]) {
    throw new RangeError(`GrokBot: expression must be 0-24, got ${String(options.expression)}.`);
  }
}

/** Re-exported so callers can enumerate valid values without importing data modules. */
export type { GrokBotState };
