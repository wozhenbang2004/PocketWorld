/**
 * grokbot-web — a dependency-free TypeScript port of the GrokBot animated
 * avatar, rendered with the Canvas 2D API.
 *
 * Ported from nasawz/GrokBot (Flutter, BSD-3-Clause).
 */
export { GrokBot } from './engine';
export type { GrokBotInit, SpinOptions } from './engine';

export type {
  GrokBotExpression,
  GrokBotOptions,
  GrokBotShape,
  GrokBotState,
  GrokBotTheme,
  Vec2,
} from './types';

export { darkTheme, lightTheme, resolveTheme } from './theme';

export { shapeData, shapeNames } from './data/shapes';
export type { ShapeData } from './data/shapes';
export { stateData, stateNames } from './data/states';
export type { Cadence, StateData } from './data/states';
export { EXPRESSION_COUNT, EYE_COUNT, RING_POINTS, expressionRings } from './data/expressions';

export {
  BODY_WIDTH,
  FACE_CENTER,
  VIEW_BOX_INSET,
  VIEW_BOX_SIZE,
  bodyBoxFor,
  clamp,
  mapNormalizedGaze,
  parseBorderRadius,
  projectEye,
  ringCentroid,
} from './geometry';
export type { BodyBox, CornerRadius, ProjectedEye } from './geometry';

export { cubicBezier, easeInOutCubic } from './easing';

export { paintGrokBot, traceRoundRect } from './painter';
export type { Canvas2DLike, PaintFrame } from './painter';

/** Every expression index, for building pickers. */
export const expressionIndexes = Array.from({ length: 25 }, (_, index) => index) as import('./types').GrokBotExpression[];
