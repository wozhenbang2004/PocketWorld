import { createContext } from 'react';
import type {MessageQuoteTarget} from './MessageQuote';

/** Identifies what must be re-read; a frozen preview alone never authorizes a quote. */
export type ReadingQuoteSource={target:MessageQuoteTarget;documentEpoch:number};

/** An in-memory reading snapshot; it grants no task, source or draft authority. */
export type ReadingSnapshot = {
  kind: 'message' | 'table';
  title: string;
  text: string;
  copyText?: string;
  quoteSource?: ReadingQuoteSource;
  tableDocument?: { markdown: string; offset: number };
  returnFocus?: HTMLElement | null;
};

export type ReadingDocument = ReadingSnapshot & { key: number };
export const ReadingContext = createContext<((snapshot: ReadingSnapshot) => void) | null>(null);
