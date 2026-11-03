// Ported from nasawz/GrokBot (BSD-3-Clause): lib/src/grokbot_painter.dart
import type { ShapeData } from './data/shapes';
import {
  BODY_WIDTH,
  FACE_CENTER,
  VIEW_BOX_INSET,
  VIEW_BOX_SIZE,
  bodyBoxFor,
  mapNormalizedGaze,
  projectEye,
  ringCentroid,
  type CornerRadius,
} from './geometry';
import type { GrokBotTheme, Vec2 } from './types';

/**
 * The slice of `CanvasRenderingContext2D` the painter actually uses.
 *
 * Declaring it structurally keeps the painter runnable against a recording
 * stub in tests and against an `OffscreenCanvas` context in a worker, with no
 * DOM types required.
 */
export interface Canvas2DLike {
  save(): void;
  restore(): void;
  translate(x: number, y: number): void;
  scale(x: number, y: number): void;
  beginPath(): void;
  moveTo(x: number, y: number): void;
  lineTo(x: number, y: number): void;
  closePath(): void;
  ellipse(
    x: number,
    y: number,
    radiusX: number,
    radiusY: number,
    rotation: number,
    startAngle: number,
    endAngle: number,
  ): void;
  arc(x: number, y: number, radius: number, startAngle: number, endAngle: number): void;
  fill(): void;
  stroke(): void;
  clip(): void;
  clearRect(x: number, y: number, width: number, height: number): void;
  fillStyle: string | CanvasGradient | CanvasPattern;
  strokeStyle: string | CanvasGradient | CanvasPattern;
  lineWidth: number;
}

const HALF_PI = Math.PI / 2;

/**
 * Traces a rounded rectangle with independent elliptical corners.
 *
 * Written out with `ellipse` arcs rather than `ctx.roundRect` so the geometry
 * matches Flutter's `RRect` exactly and so the painter keeps working on the
 * ~2022 Safari and Firefox builds that predate `roundRect`.
 */
export function traceRoundRect(
  ctx: Canvas2DLike,
  left: number,
  top: number,
  width: number,
  height: number,
  radii: CornerRadius[],
): void {
  const [tl, tr, br, bl] = radii;
  const right = left + width;
  const bottom = top + height;
  ctx.beginPath();
  ctx.moveTo(left + tl.x, top);
  ctx.lineTo(right - tr.x, top);
  ctx.ellipse(right - tr.x, top + tr.y, tr.x, tr.y, 0, -HALF_PI, 0);
  ctx.lineTo(right, bottom - br.y);
  ctx.ellipse(right - br.x, bottom - br.y, br.x, br.y, 0, 0, HALF_PI);
  ctx.lineTo(left + bl.x, bottom);
  ctx.ellipse(left + bl.x, bottom - bl.y, bl.x, bl.y, 0, HALF_PI, Math.PI);
  ctx.lineTo(left, top + tl.y);
  ctx.ellipse(left + tl.x, top + tl.y, tl.x, tl.y, 0, Math.PI, Math.PI + HALF_PI);
  ctx.closePath();
}

/** Everything one frame needs, already resolved by the engine. */
export interface PaintFrame {
  /** Two eyes, each a flat `[x0, y0, ...]` ring of 48 points. */
  rings: readonly (readonly number[])[];
  shape: ShapeData;
  gaze: Vec2;
  /** Base turn plus any active spin, in radians. */
  turn: number;
  eyeScale: number;
  blinkScale: number;
  flipX: boolean;
  emphasis: boolean;
  showGuides: boolean;
  theme: GrokBotTheme;
}

function tracePolygon(ctx: Canvas2DLike, ring: readonly number[]): void {
  ctx.beginPath();
  ctx.moveTo(ring[0], ring[1]);
  for (let i = 2; i < ring.length; i += 2) ctx.lineTo(ring[i], ring[i + 1]);
  ctx.closePath();
}

function applyBodySquash(ctx: Canvas2DLike, bodyScale: number): void {
  ctx.translate(FACE_CENTER, FACE_CENTER);
  ctx.scale(bodyScale, 1);
  ctx.translate(-FACE_CENTER, -FACE_CENTER);
}

/**
 * Paints one frame into `ctx`, in CSS pixels. The caller owns the device pixel
 * ratio transform and any clearing of the surface.
 */
export function paintGrokBot(
  ctx: Canvas2DLike,
  frame: PaintFrame,
  width: number,
  height: number,
): void {
  const { shape, theme } = frame;
  const side = Math.min(width, height);
  const scaleToCanvas = side / VIEW_BOX_SIZE;

  ctx.save();
  ctx.translate((width - side) / 2, (height - side) / 2);
  ctx.scale(scaleToCanvas, scaleToCanvas);
  ctx.translate(VIEW_BOX_INSET, VIEW_BOX_INSET);

  if (frame.flipX) {
    ctx.translate(BODY_WIDTH, 0);
    ctx.scale(-1, 1);
  }

  const body = bodyBoxFor(shape);
  const bodyScale = shape.squashOnTurn ? Math.max(Math.cos(frame.turn), 0.55) : 1;

  ctx.save();
  applyBodySquash(ctx, bodyScale);
  traceRoundRect(ctx, body.left, body.top, body.width, body.height, body.radii);
  ctx.fillStyle = theme.bodyColor;
  ctx.fill();
  ctx.restore();

  const origin: Vec2 = {
    x: FACE_CENTER + shape.faceX,
    y: FACE_CENTER + shape.faceY,
  };

  if (frame.showGuides) {
    ctx.beginPath();
    ctx.ellipse(origin.x, origin.y, 105 * shape.faceScaleX, 35, 0, 0, Math.PI * 2);
    ctx.strokeStyle = theme.guideColor;
    ctx.lineWidth = 1;
    ctx.stroke();
  }

  ctx.save();
  applyBodySquash(ctx, bodyScale);
  traceRoundRect(ctx, body.left, body.top, body.width, body.height, body.radii);
  ctx.clip();
  // Undo the squash for the eyes: the body narrows on turn, the face does not.
  ctx.translate(FACE_CENTER, FACE_CENTER);
  ctx.scale(1 / bodyScale, 1);
  ctx.translate(-FACE_CENTER, -FACE_CENTER);

  const mappedGaze = mapNormalizedGaze(frame.gaze);
  const baseScale = frame.eyeScale * (frame.emphasis ? 1.18 : 1) * shape.eyeScale;
  const radius = 105 * Math.min(shape.faceScaleX, shape.faceScaleY);
  const projectedCenters: Vec2[] = [];

  ctx.fillStyle = theme.eyeColor;
  for (const ring of frame.rings) {
    const corrected = new Array<number>(ring.length);
    for (let i = 0; i < ring.length; i += 2) {
      corrected[i] = origin.x + (ring[i] - FACE_CENTER) * shape.faceScaleX;
      corrected[i + 1] = origin.y + (ring[i + 1] - FACE_CENTER) * shape.faceScaleY;
    }
    const center = ringCentroid(corrected);
    const projection = projectEye({
      centroid: center,
      origin,
      radius,
      turn: frame.turn,
      gaze: mappedGaze,
      scale: baseScale,
      blinkScale: frame.blinkScale,
    });
    projectedCenters.push(projection.center);
    if (!projection.visible) continue;
    ctx.save();
    ctx.translate(projection.center.x, projection.center.y);
    ctx.scale(projection.scaleX, projection.scaleY);
    ctx.translate(-center.x, -center.y);
    tracePolygon(ctx, corrected);
    ctx.fill();
    ctx.restore();
  }
  ctx.restore();

  if (frame.showGuides) {
    ctx.fillStyle = theme.centroidColor;
    for (const point of projectedCenters) {
      ctx.beginPath();
      ctx.arc(point.x, point.y, 3.5, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  ctx.restore();
}
