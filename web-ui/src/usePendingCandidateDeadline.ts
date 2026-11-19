import { useEffect, useState } from 'react';

/** Expires the visible choice only; the native owner receipt decides if anything was written. */
export function usePendingCandidateDeadline(expiresAt: number): boolean {
  const [, render] = useState(0);
  useEffect(() => {
    if (!expiresAt) return;
    let timer: number | undefined;
    const refresh = () => {
      window.clearTimeout(timer);
      const left = expiresAt - Date.now();
      if (left > 0) timer = window.setTimeout(refresh, Math.min(left, 0x7fffffff));
      else render(value => value + 1);
    };
    refresh(); document.addEventListener('visibilitychange', refresh);
    return () => { window.clearTimeout(timer); document.removeEventListener('visibilitychange', refresh); };
  }, [expiresAt]);
  return expiresAt > 0 && Date.now() >= expiresAt;
}
