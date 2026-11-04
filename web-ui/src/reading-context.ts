import { createContext } from 'react';

/** An in-memory reading snapshot; it grants no task, source or draft authority. */
export type ReadingSnapshot = {
  kind: 'message' | 'table';
  title: string;
  text: string;
  copyText?: string;
  tableDocument?: { markdown: string; offset: number };
  returnFocus?: HTMLElement | null;
};

export type ReadingDocument = ReadingSnapshot & { key: number };
export const ReadingContext = createContext<((snapshot: ReadingSnapshot) => void) | null>(null);
