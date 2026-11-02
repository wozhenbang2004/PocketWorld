// Ported from nasawz/GrokBot (BSD-3-Clause): lib/src/geometry.dart
import type { ShapeData } from './data/shapes';
import type { Vec2 } from './types';

/** Center of the face coordinate space. */
export const FACE_CENTER = 114.2705;
/** Width of the body box in face units. */
export const BODY_WIDTH = 228.541;
/** Side of the square view box the avatar is drawn into. */
export const VIEW_BOX_SIZE = 259;
/** Padding between the view box edge and the body box. */
export const VIEW_BOX_INSET = 15;

export function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}

/** Mean of a flat `[x0, y0, x1, y1, ...]` ring. */
export function ringCentroid(ring: readonly number[]): Vec2 {
  let x = 0;
  let y = 0;
  const count = ring.length / 2;
  for (let i = 0; i < ring.length; i += 2) {
    x += ring[i];
    y += ring[i + 1];
  }
  return { x: x / count, y: y / count };
}

/** Maps normalized gaze in [-1, 1] to a face-space offset of about ±13.2 × ±8.4. */
export function mapNormalizedGaze(gaze: Vec2): Vec2 {
  return {
    x: clamp(gaze.x, -1, 1) * 13.2,
    y: clamp(gaze.y, -1, 1) * 8.4,
  };
}

/** One elliptical corner radius. */
export interface CornerRadius {
  x: number;
  y: number;
}

function expandRadius(values: string[]): string[] {
  switch (values.length) {
    case 0:
      return ['0', '0', '0', '0'];
    case 1:
      return [values[0], values[0], values[0], values[0]];
    case 2:
      return [values[0], values[1], values[0], values[1]];
    case 3:
      return [values[0], values[1], values[2], values[1]];
    default:
      return values.slice(0, 4);
  }
}

function parseRadiusToken(token: string, axisSize: number): number {
  const value = token.trim();
  if (value.endsWith('%')) {
    const percent = Number.parseFloat(value.slice(0, -1));
    return (Number.isFinite(percent) ? percent : 0) / 100 * axisSize;
  }
  const absolute = Number.parseFloat(value);
  return Number.isFinite(absolute) ? absolute : 0;
}

/**
 * Parses a CSS `border-radius` shorthand into four elliptical corner radii
 * ordered top-left, top-right, bottom-right, bottom-left, then applies the CSS
 * overlap clamp so adjacent radii never exceed the box.
 */
export function parseBorderRadius(css: string, width: number, height: number): CornerRadius[] {
  const parts = css.split('/');
  const split = (source: string) => source.trim().split(/\s+/).filter((token) => token.length > 0);
  const horizontal = expandRadius(split(parts[0]));
  const vertical = expandRadius(split(parts.length > 1 ? parts[1] : parts[0]));

  const radii: CornerRadius[] = [0, 1, 2, 3].map((index) => ({
    x: parseRadiusToken(horizontal[index], width),
    y: parseRadiusToken(vertical[index], height),
  }));

  const clampPair = (a: number, b: number, size: number, horizontalAxis: boolean) => {
    const first = horizontalAxis ? radii[a].x : radii[a].y;
    const second = horizontalAxis ? radii[b].x : radii[b].y;
    const sum = first + second;
    if (sum <= size || sum <= 0) return;
    const scale = size / sum;
    for (const index of [a, b]) {
      if (horizontalAxis) radii[index] = { x: radii[index].x * scale, y: radii[index].y };
      else radii[index] = { x: radii[index].x, y: radii[index].y * scale };
    }
  };

  clampPair(0, 1, width, true);
  clampPair(3, 2, width, true);
  clampPair(0, 3, height, false);
  clampPair(1, 2, height, false);
  return radii;
}

/** Body box and corner radii for a silhouette, in face units. */
export interface BodyBox {
  left: number;
  top: number;
  width: number;
  height: number;
  radii: CornerRadius[];
}

export function bodyBoxFor(shape: ShapeData): BodyBox {
  const width = 210 * shape.aspectX;
  const height = 210 * shape.aspectY;
  return {
    left: FACE_CENTER - width / 2,
    top: FACE_CENTER - height / 2,
    width,
    height,
    radii: parseBorderRadius(shape.radius, width, height),
  };
}

/** Result of projecting one eye onto the head sphere. */
export interface ProjectedEye {
  center: Vec2;
  scaleX: number;
  scaleY: number;
  visible: boolean;
}

/**
 * Maps an eye centroid onto the head sphere: its horizontal offset becomes a
 * longitude, the turn rotates it, and the cosine of the result both narrows the
 * eye and hides it once it passes behind the head.
 */
export function projectEye(params: {
  centroid: Vec2;
  origin: Vec2;
  radius: number;
  turn: number;
  gaze: Vec2;
  scale: number;
  blinkScale: number;
}): ProjectedEye {
  const { centroid, origin, radius, turn, gaze, scale, blinkScale } = params;
  const offset = centroid.x - origin.x;
  const baseLongitude = Math.asin(clamp(offset / Math.max(radius, 1), -1, 1));
  const longitude = baseLongitude + turn;
  const depth = Math.cos(longitude);
  const perspective = Math.max(depth, 0.02) / Math.max(Math.cos(baseLongitude), 0.02);
  return {
    center: {
      x: origin.x + radius * Math.sin(longitude) + gaze.x,
      y: centroid.y + gaze.y,
    },
    scaleX: clamp(perspective * scale, 0.02, 2.4),
    scaleY: clamp(blinkScale * scale, 0.02, 2.4),
    visible: depth > 0.02,
  };
}
