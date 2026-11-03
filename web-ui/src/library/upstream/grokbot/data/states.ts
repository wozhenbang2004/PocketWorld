// Ported from nasawz/GrokBot (BSD-3-Clause): lib/src/data/state_data.dart
import type { GrokBotExpression, GrokBotState } from '../types';

/** Inclusive millisecond range a timer picks uniformly from. */
export interface Cadence {
  min: number;
  max: number;
}

export interface StateData {
  /** Expression pool. The first entry is the state's resting face. */
  expressions: readonly GrokBotExpression[];
  /** How often the pool is re-sampled while `autoExpression` is on. */
  expressionCadence: Cadence;
  /** How often an automatic blink fires, or `null` for a state that never blinks. */
  blinkCadence: Cadence | null;
}

const c = (min: number, max: number): Cadence => ({ min, max });
const e = (...values: number[]) => values as GrokBotExpression[];

export const stateData: Record<GrokBotState, StateData> = {
  sleeping: { expressions: e(13, 22, 4), expressionCadence: c(6000, 10000), blinkCadence: null },
  waking: { expressions: e(13), expressionCadence: c(800, 800), blinkCadence: null },
  idle: { expressions: e(0, 8), expressionCadence: c(9000, 16000), blinkCadence: c(6000, 14000) },
  listening: { expressions: e(10, 1, 19), expressionCadence: c(2800, 5000), blinkCadence: c(3000, 7000) },
  thinking: { expressions: e(8, 16, 14, 17, 5), expressionCadence: c(2000, 3600), blinkCadence: c(3500, 7000) },
  searching: { expressions: e(15, 9, 3, 20, 12, 18), expressionCadence: c(1000, 1800), blinkCadence: c(1600, 4000) },
  working: { expressions: e(7, 16, 11, 10), expressionCadence: c(1800, 3200), blinkCadence: c(2800, 5500) },
  excited: { expressions: e(2, 17, 21, 3, 11), expressionCadence: c(1100, 2000), blinkCadence: c(2000, 4000) },
  surprised: { expressions: e(3, 21), expressionCadence: c(2500, 4000), blinkCadence: c(1800, 3500) },
  suspicious: { expressions: e(14, 5, 23), expressionCadence: c(2600, 4500), blinkCadence: c(4500, 8000) },
  angry: { expressions: e(7, 16), expressionCadence: c(2200, 3800), blinkCadence: c(3500, 7000) },
  drowsy: { expressions: e(4, 22, 13), expressionCadence: c(4000, 8000), blinkCadence: null },
  happy: { expressions: e(2, 11, 17, 19), expressionCadence: c(2500, 4500), blinkCadence: c(2500, 5000) },
  curious: { expressions: e(3, 21, 0, 15), expressionCadence: c(1800, 3200), blinkCadence: c(2500, 5500) },
  confused: { expressions: e(14, 5, 8), expressionCadence: c(2200, 3800), blinkCadence: c(2800, 5500) },
  bored: { expressions: e(4, 22, 0), expressionCadence: c(3500, 6000), blinkCadence: c(4000, 8000) },
  proud: { expressions: e(15, 8, 2), expressionCadence: c(3500, 6000), blinkCadence: c(3500, 7000) },
  shy: { expressions: e(0, 24, 13), expressionCadence: c(3000, 5500), blinkCadence: c(3000, 6000) },
  sad: { expressions: e(4, 13, 22), expressionCadence: c(4000, 7000), blinkCadence: c(4000, 8000) },
  laughing: { expressions: e(2, 11, 17), expressionCadence: c(1200, 2400), blinkCadence: c(2500, 5000) },
  scared: { expressions: e(3, 21), expressionCadence: c(900, 1800), blinkCadence: c(1200, 3000) },
  playful: { expressions: e(2, 17, 11, 8), expressionCadence: c(1500, 3000), blinkCadence: c(2000, 4500) },
  celebrate: { expressions: e(2, 8, 17), expressionCadence: c(1400, 2600), blinkCadence: c(2200, 4500) },
  orbit: { expressions: e(0, 8), expressionCadence: c(4000, 8000), blinkCadence: null },
  radar: { expressions: e(0, 8), expressionCadence: c(4000, 8000), blinkCadence: null },
  progress: { expressions: e(0, 8), expressionCadence: c(4000, 8000), blinkCadence: null },
  spawning: { expressions: e(3, 0), expressionCadence: c(1200, 1200), blinkCadence: null },
  humming: { expressions: e(0, 8), expressionCadence: c(5000, 9000), blinkCadence: c(4000, 8000) },
  loading: { expressions: e(0, 8), expressionCadence: c(6000, 10000), blinkCadence: null },
  dictating: { expressions: e(10, 1, 19), expressionCadence: c(4000, 8000), blinkCadence: null },
  writing: { expressions: e(15, 9), expressionCadence: c(4000, 8000), blinkCadence: null },
  sending: { expressions: e(0, 8), expressionCadence: c(4000, 8000), blinkCadence: null },
  receiving: { expressions: e(19, 0, 8), expressionCadence: c(4000, 8000), blinkCadence: null },
  uploading: { expressions: e(15, 9, 8), expressionCadence: c(4000, 8000), blinkCadence: null },
  notifying: { expressions: e(3, 21, 0), expressionCadence: c(1500, 2600), blinkCadence: c(2000, 4000) },
  alerting: { expressions: e(3, 21), expressionCadence: c(2000, 3600), blinkCadence: null },
  dragging: { expressions: e(3, 15, 0), expressionCadence: c(1600, 3000), blinkCadence: c(2200, 4500) },
  bouncing: { expressions: e(2, 17), expressionCadence: c(3000, 6000), blinkCadence: null },
  'powering-down': { expressions: e(13, 22), expressionCadence: c(6000, 9000), blinkCadence: null },
};

/** Every state name, in declaration order. */
export const stateNames = Object.keys(stateData) as GrokBotState[];
