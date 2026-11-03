import { GrokBot, type GrokBotInit } from './engine';
import { shapeData } from './data/shapes';
import { stateData } from './data/states';
import type { GrokBotExpression, GrokBotShape, GrokBotState, GrokBotTheme } from './types';

const BOOLEAN_ATTRIBUTES = ['auto-blink', 'auto-expression', 'flip-x', 'emphasis', 'show-guides'] as const;

const OBSERVED = [
  'state',
  'shape',
  'expression',
  'gaze-x',
  'gaze-y',
  'turn',
  'eye-scale',
  'spring-frequency',
  'size',
  'label',
  'body-color',
  'eye-color',
  ...BOOLEAN_ATTRIBUTES,
] as const;

function readNumber(value: string | null, fallback: number): number {
  if (value === null) return fallback;
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

/**
 * `<grok-bot>` — the framework-agnostic wrapper.
 *
 * Attributes mirror the options in kebab-case; booleans follow HTML rules
 * (present means true), except `auto-blink`, which defaults to on and is turned
 * off with `auto-blink="false"`.
 */
export class GrokBotElement extends HTMLElement {
  static get observedAttributes(): readonly string[] {
    return OBSERVED;
  }

  #bot: GrokBot | null = null;
  #canvas: HTMLCanvasElement | null = null;
  #themeOverride: Partial<GrokBotTheme> = {};

  connectedCallback(): void {
    if (this.#bot) return;
    const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
    root.innerHTML =
      '<style>:host{display:inline-block;line-height:0}canvas{display:block;width:100%;height:100%}</style><canvas></canvas>';
    this.#canvas = root.querySelector('canvas');
    if (!this.#canvas) return;
    this.#bot = new GrokBot(this.#canvas, this.#readInit());
  }

  disconnectedCallback(): void {
    this.#bot?.destroy();
    this.#bot = null;
  }

  attributeChangedCallback(): void {
    this.#bot?.update(this.#readInit());
  }

  /** The underlying engine, once the element is connected. */
  get bot(): GrokBot | null {
    return this.#bot;
  }

  blink(): Promise<void> {
    return this.#bot?.blink() ?? Promise.resolve();
  }

  spin(options?: { turns?: number; durationMs?: number }): Promise<void> {
    return this.#bot?.spin(options) ?? Promise.resolve();
  }

  reset(): void {
    this.#bot?.reset();
  }

  /** Merges extra theme colors that have no dedicated attribute. */
  setTheme(theme: Partial<GrokBotTheme>): void {
    this.#themeOverride = { ...this.#themeOverride, ...theme };
    this.#bot?.update({ theme: this.#readInit().theme });
  }

  #boolean(name: (typeof BOOLEAN_ATTRIBUTES)[number], fallback: boolean): boolean {
    const raw = this.getAttribute(name);
    if (raw === null) return fallback;
    return raw !== 'false' && raw !== '0';
  }

  #readInit(): GrokBotInit {
    const state = this.getAttribute('state');
    const shape = this.getAttribute('shape');
    const expressionRaw = this.getAttribute('expression');
    const sizeRaw = this.getAttribute('size');
    const theme: Partial<GrokBotTheme> = { ...this.#themeOverride };
    const bodyColor = this.getAttribute('body-color');
    const eyeColor = this.getAttribute('eye-color');
    if (bodyColor) theme.bodyColor = bodyColor;
    if (eyeColor) theme.eyeColor = eyeColor;

    const expression =
      expressionRaw === null || expressionRaw === '' ? null : (Number.parseInt(expressionRaw, 10) as GrokBotExpression);

    return {
      state: state && state in stateData ? (state as GrokBotState) : 'idle',
      shape: shape && shape in shapeData ? (shape as GrokBotShape) : 'blob',
      expression: expression !== null && Number.isFinite(expression) ? expression : null,
      gaze: { x: readNumber(this.getAttribute('gaze-x'), 0), y: readNumber(this.getAttribute('gaze-y'), 0) },
      turn: readNumber(this.getAttribute('turn'), 0),
      eyeScale: readNumber(this.getAttribute('eye-scale'), 1),
      springFrequency: readNumber(this.getAttribute('spring-frequency'), 7),
      autoBlink: this.#boolean('auto-blink', true),
      autoExpression: this.#boolean('auto-expression', false),
      flipX: this.#boolean('flip-x', false),
      emphasis: this.#boolean('emphasis', false),
      showGuides: this.#boolean('show-guides', false),
      size: sizeRaw === null || sizeRaw === 'auto' ? 'auto' : readNumber(sizeRaw, 160),
      ariaLabel: this.getAttribute('label') ?? 'GrokBot',
      theme: Object.keys(theme).length > 0 ? theme : null,
    };
  }
}

/** Registers `<grok-bot>` once. Safe to call repeatedly. */
export function defineGrokBotElement(tagName = 'grok-bot'): void {
  if (typeof customElements === 'undefined') return;
  if (customElements.get(tagName)) return;
  customElements.define(tagName, GrokBotElement);
}

defineGrokBotElement();
