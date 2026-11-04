import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { RiArrowLeftLine, RiArrowRightSLine } from '@remixicon/react';
import { Button } from './forms-library';

/** Presentation-only navigation over the original native data workspace.
 * BoardUI settings rows keep operation discovery separate from its review.
 * No native object, selection, review or credential is cached here. */
export function useDataSettingsJourney(initial = 'list') {
  const [view, setView] = useState(initial);
  const root = useRef<HTMLDivElement>(null);
  const positions = useRef(new Map<string, number>());
  const returnTo = useRef('');
  const previous = useRef(initial);
  const open = (next: string) => {
    const scroller = root.current?.closest('.pw-secondary-scroll');
    positions.current.set(view, scroller?.scrollTop || 0);
    if (view === 'list') returnTo.current = next;
    setView(next);
  };
  useLayoutEffect(() => {
    if (previous.current === view) return;
    previous.current = view;
    const scroller = root.current?.closest('.pw-secondary-scroll');
    if (scroller) scroller.scrollTop = positions.current.get(view) || 0;
    const target = view === 'list'
      ? Array.from(root.current?.querySelectorAll<HTMLElement>('[data-operation-entry]') || []).find(row => row.dataset.operationEntry === returnTo.current)
      : root.current?.querySelector<HTMLElement>('[data-operation-heading]');
    (target || root.current)?.focus({ preventScroll: true });
  }, [view]);
  return { view, root, open, back: () => { if (view === 'list') return false; open('list'); return true; } };
}

export function DataOperationRow({ id, title, detail, onOpen, disabled = false }: { id: string; title: string; detail: string; onOpen: (id: string) => void; disabled?: boolean }) {
  return <Button variant="ghost" className="pw-domain-link pw-data-operation-row h-auto whitespace-normal" trailingIcon={RiArrowRightSLine} data-operation-entry={id} disabled={disabled} onClick={() => onOpen(id)}>
    <span><span>{title}</span><small>{detail}</small></span>
  </Button>;
}

export function DataOperationHeading({ title, parent, description, onBack, children }: { title: string; parent: string; description: string; onBack: () => void; children?: ReactNode }) {
  return <header className="pw-data-operation-heading">
    <Button variant="ghost" leadingIcon={RiArrowLeftLine} onClick={onBack}>返回{parent}</Button>
    <div tabIndex={-1} data-operation-heading><p className="pw-model-eyebrow">{parent}</p><h2>{title}</h2><p>{description}</p></div>
    {children}
  </header>;
}
