import { useLayoutEffect } from 'react';
import { usePocketTypography } from './usePocketTypography';

/** Host preference is authoritative; no separate Web-only persisted setting. */
export function usePocketAppearance(value: unknown) {
  usePocketTypography(value);
  const appearance = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  const dark = appearance.dark;
  const mode = appearance.mode;
  useLayoutEffect(() => {
    if (typeof dark !== 'boolean' || !['system', 'light', 'dark'].includes(String(mode))) return;
    const root = document.documentElement;
    root.classList.toggle('dark', dark);
    root.dataset.theme = dark ? 'dark' : 'light';
    root.dataset.appearance = String(mode);
    root.style.colorScheme = dark ? 'dark' : 'light';
  }, [dark, mode]);
}
