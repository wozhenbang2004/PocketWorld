import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  type CSSProperties,
} from 'react';
import { GrokBot, type GrokBotInit, type SpinOptions } from './engine';
import type { GrokBotExpression, GrokBotShape, GrokBotState, GrokBotTheme, Vec2 } from './types';

export interface GrokBotHandle {
  blink(): Promise<void>;
  spin(options?: SpinOptions): Promise<void>;
  reset(): void;
  /** The live engine, for anything the props do not cover. */
  readonly bot: GrokBot | null;
}

export interface GrokBotProps {
  state?: GrokBotState;
  shape?: GrokBotShape;
  expression?: GrokBotExpression | null;
  gaze?: Vec2;
  turn?: number;
  eyeScale?: number;
  springFrequency?: number;
  autoBlink?: boolean;
  autoExpression?: boolean;
  flipX?: boolean;
  emphasis?: boolean;
  showGuides?: boolean;
  theme?: Partial<GrokBotTheme> | null;
  size?: number | 'auto';
  respectReducedMotion?: boolean;
  random?: () => number;
  /** Accessible label. Defaults to `'GrokBot'`. */
  label?: string;
  className?: string;
  style?: CSSProperties;
}

/**
 * React wrapper around the canvas engine.
 *
 * The engine is created once per mount and then patched, so React re-renders
 * never restart an in-flight morph or blink.
 */
export const GrokBotView = forwardRef<GrokBotHandle, GrokBotProps>(function GrokBotView(props, ref) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const botRef = useRef<GrokBot | null>(null);
  const initRef = useRef<GrokBotInit>({});
  initRef.current = toInit(props);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return undefined;
    const bot = new GrokBot(canvas, initRef.current);
    botRef.current = bot;
    return () => {
      bot.destroy();
      botRef.current = null;
    };
    // Mount-only on purpose: every prop change flows through the patch effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    state,
    shape,
    expression,
    turn,
    eyeScale,
    springFrequency,
    autoBlink,
    autoExpression,
    flipX,
    emphasis,
    showGuides,
    size,
    label,
    respectReducedMotion,
  } = props;
  const gazeX = props.gaze?.x ?? 0;
  const gazeY = props.gaze?.y ?? 0;
  const themeKey = props.theme ? JSON.stringify(props.theme) : '';

  useEffect(() => {
    botRef.current?.update(initRef.current);
  }, [
    state,
    shape,
    expression,
    gazeX,
    gazeY,
    turn,
    eyeScale,
    springFrequency,
    autoBlink,
    autoExpression,
    flipX,
    emphasis,
    showGuides,
    size,
    label,
    respectReducedMotion,
    themeKey,
  ]);

  useImperativeHandle(
    ref,
    (): GrokBotHandle => ({
      blink: () => botRef.current?.blink() ?? Promise.resolve(),
      spin: (options?: SpinOptions) => botRef.current?.spin(options) ?? Promise.resolve(),
      reset: () => botRef.current?.reset(),
      get bot() {
        return botRef.current;
      },
    }),
    [],
  );

  return <canvas ref={canvasRef} className={props.className} style={props.style} />;
});

function toInit(props: GrokBotProps): GrokBotInit {
  const init: GrokBotInit = {};
  if (props.state !== undefined) init.state = props.state;
  if (props.shape !== undefined) init.shape = props.shape;
  if (props.expression !== undefined) init.expression = props.expression;
  if (props.gaze !== undefined) init.gaze = props.gaze;
  if (props.turn !== undefined) init.turn = props.turn;
  if (props.eyeScale !== undefined) init.eyeScale = props.eyeScale;
  if (props.springFrequency !== undefined) init.springFrequency = props.springFrequency;
  if (props.autoBlink !== undefined) init.autoBlink = props.autoBlink;
  if (props.autoExpression !== undefined) init.autoExpression = props.autoExpression;
  if (props.flipX !== undefined) init.flipX = props.flipX;
  if (props.emphasis !== undefined) init.emphasis = props.emphasis;
  if (props.showGuides !== undefined) init.showGuides = props.showGuides;
  if (props.size !== undefined) init.size = props.size;
  if (props.label !== undefined) init.ariaLabel = props.label;
  if (props.respectReducedMotion !== undefined) init.respectReducedMotion = props.respectReducedMotion;
  if (props.random !== undefined) init.random = props.random;
  if (props.theme !== undefined) init.theme = props.theme;
  return init;
}
