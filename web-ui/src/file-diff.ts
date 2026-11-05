import { diffChars, diffLines } from 'diff';

export type FileDiffPart = { text: string; changed: boolean };
export type FileComparison = { before: FileDiffPart[]; after: FileDiffPart[]; mode: 'character' | 'line' | 'unmarked'; whitespaceChanged: boolean };

/** Display only. Retain exact source text; never generate a save or patch payload. */
export function compareFileText(before: string, after: string): FileComparison {
  const plain = (text: string): FileDiffPart[] => text ? [{ text, changed: false }] : [];
  if (before === after) return { before: plain(before), after: plain(after), mode: 'character', whitespaceChanged: false };
  const size = before.length + after.length;
  // Bound token allocation as well as Myers search. A limit never changes the file itself.
  if (size > 500_000) return { before: plain(before), after: plain(after), mode: 'unmarked', whitespaceChanged: false };
  const characters = size <= 200_000 ? diffChars(before, after, { timeout: 40, maxEditLength: 2048 }) : undefined;
  const changes = characters ?? diffLines(before, after, { timeout: 40, maxEditLength: 1024 });
  if (!changes) return { before: plain(before), after: plain(after), mode: 'unmarked', whitespaceChanged: false };
  return {
    before: changes.filter(part => !part.added).map(part => ({ text: part.value, changed: !!part.removed })),
    after: changes.filter(part => !part.removed).map(part => ({ text: part.value, changed: !!part.added })),
    mode: characters ? 'character' : 'line',
    whitespaceChanged: changes.some(part => (part.added || part.removed) && /^\s+$/u.test(part.value)),
  };
}
