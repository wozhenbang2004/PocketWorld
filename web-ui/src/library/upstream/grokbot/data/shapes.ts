// Ported from nasawz/GrokBot (BSD-3-Clause): lib/src/data/shape_data.dart
import type { GrokBotShape } from '../types';

export interface ShapeData {
  /** Face origin offset from the body center, in face units. */
  faceX: number;
  faceY: number;
  /** Face geometry correction applied to every eye point. */
  faceScaleX: number;
  faceScaleY: number;
  /** Extra eye size multiplier for this silhouette. */
  eyeScale: number;
  /** Whether the body squashes horizontally while the head turns. */
  squashOnTurn: boolean;
  /** CSS `border-radius` shorthand describing the silhouette. */
  radius: string;
  /** Body box aspect against the 210-unit square. */
  aspectX: number;
  aspectY: number;
}

export const shapeData: Record<GrokBotShape, ShapeData> = {
  blob: { faceX: 0, faceY: 0, faceScaleX: 1, faceScaleY: 1, eyeScale: 1, squashOnTurn: false, radius: '50%', aspectX: 1, aspectY: 1 },
  pebble: { faceX: 2, faceY: 0, faceScaleX: 0.97, faceScaleY: 0.86, eyeScale: 1, squashOnTurn: false, radius: '43% 57% 48% 52%', aspectX: 1.02, aspectY: 0.94 },
  bean: { faceX: 0, faceY: 0, faceScaleX: 0.72, faceScaleY: 0.9, eyeScale: 0.87, squashOnTurn: true, radius: '58% 42% 54% 46%', aspectX: 0.92, aspectY: 1.02 },
  egg: { faceX: 0, faceY: 2, faceScaleX: 0.77, faceScaleY: 0.97, eyeScale: 0.93, squashOnTurn: false, radius: '50% 50% 56% 44%', aspectX: 0.88, aspectY: 1.05 },
  squircle: { faceX: 0, faceY: 0, faceScaleX: 1, faceScaleY: 1, eyeScale: 1, squashOnTurn: false, radius: '28%', aspectX: 1, aspectY: 1 },
  tablet: { faceX: 0, faceY: 0, faceScaleX: 0.94, faceScaleY: 0.65, eyeScale: 0.82, squashOnTurn: true, radius: '28%', aspectX: 0.78, aspectY: 1.08 },
  capsule: { faceX: 0, faceY: 0, faceScaleX: 0.64, faceScaleY: 0.92, eyeScale: 0.81, squashOnTurn: false, radius: '50% / 32%', aspectX: 0.64, aspectY: 1.08 },
  cylinder: { faceX: 0, faceY: 0, faceScaleX: 0.85, faceScaleY: 0.96, eyeScale: 0.99, squashOnTurn: false, radius: '42% / 18%', aspectX: 0.82, aspectY: 1.04 },
  hex: { faceX: 0, faceY: 0, faceScaleX: 0.91, faceScaleY: 0.91, eyeScale: 1, squashOnTurn: false, radius: '18%', aspectX: 1, aspectY: 1 },
  gem: { faceX: 0, faceY: 0, faceScaleX: 0.89, faceScaleY: 0.89, eyeScale: 0.99, squashOnTurn: false, radius: '35% 15% 38% 18%', aspectX: 0.95, aspectY: 1.02 },
  crystal: { faceX: 0, faceY: 0, faceScaleX: 0.71, faceScaleY: 0.89, eyeScale: 0.86, squashOnTurn: false, radius: '12% 38% 16% 42%', aspectX: 0.9, aspectY: 1.05 },
  wedge: { faceX: 0, faceY: 24, faceScaleX: 0.7, faceScaleY: 0.7, eyeScale: 0.79, squashOnTurn: false, radius: '15% 50% 50% 15%', aspectX: 1, aspectY: 1 },
  shield: { faceX: 0, faceY: 0, faceScaleX: 0.79, faceScaleY: 0.98, eyeScale: 0.95, squashOnTurn: false, radius: '45% 45% 58% 58%', aspectX: 0.95, aspectY: 1.05 },
  dome: { faceX: 0, faceY: 4, faceScaleX: 0.85, faceScaleY: 0.68, eyeScale: 0.82, squashOnTurn: false, radius: '50% 50% 30% 30%', aspectX: 1.02, aspectY: 0.92 },
  arch: { faceX: 0, faceY: 0, faceScaleX: 0.67, faceScaleY: 0.97, eyeScale: 0.85, squashOnTurn: false, radius: '50% 50% 22% 22%', aspectX: 0.9, aspectY: 1.05 },
  cloud: { faceX: 0, faceY: 4, faceScaleX: 0.79, faceScaleY: 0.7, eyeScale: 0.81, squashOnTurn: true, radius: '42% 58% 48% 52%', aspectX: 1.05, aspectY: 0.88 },
  teardrop: { faceX: 0, faceY: 22, faceScaleX: 0.79, faceScaleY: 0.79, eyeScale: 0.88, squashOnTurn: false, radius: '50% 50% 62% 38%', aspectX: 0.9, aspectY: 1.05 },
  leaf: { faceX: 0, faceY: 0, faceScaleX: 0.73, faceScaleY: 0.91, eyeScale: 0.88, squashOnTurn: false, radius: '62% 38% 62% 38%', aspectX: 0.88, aspectY: 1.05 },
};

/** Every shape name, in declaration order. */
export const shapeNames = Object.keys(shapeData) as GrokBotShape[];
