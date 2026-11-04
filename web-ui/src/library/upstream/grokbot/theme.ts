// Ported from nasawz/GrokBot (BSD-3-Clause): lib/src/theme.dart
import type { GrokBotTheme } from './types';

/** Default light-surface palette. */
export const lightTheme: GrokBotTheme = {
  bodyColor: '#5b7fe5',
  eyeColor: '#fffdf7',
  guideColor: '#6e7067',
  centroidColor: '#e36f3d',
  badgeColor: '#5b7fe5',
  particleColor: '#e36f3d',
};

/** Dark-surface palette. */
export const darkTheme: GrokBotTheme = {
  bodyColor: '#6689ea',
  eyeColor: '#181a15',
  guideColor: '#a5a89d',
  centroidColor: '#ff8b5e',
  badgeColor: '#6689ea',
  particleColor: '#ff8b5e',
};

/** Merges a partial palette onto a base one. */
export function resolveTheme(
  patch?: Partial<GrokBotTheme> | null,
  base: GrokBotTheme = lightTheme,
): GrokBotTheme {
  return patch ? { ...base, ...patch } : { ...base };
}
