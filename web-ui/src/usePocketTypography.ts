import { useLayoutEffect } from 'react';
import { normalizeTypography, typographyKey, typographyStacks } from './typography';
import './typography.css';

/** Every first-party Web surface follows the same native preference, including first paint. */
export function usePocketTypography(value: unknown) {
  const appearance = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
  const preference = normalizeTypography(appearance.typography);
  const key = typographyKey(preference);
  useLayoutEffect(() => {
    const root = document.documentElement;
    const fonts = typographyStacks(preference);
    root.style.setProperty('--pw-font-ui', fonts.ui);
    root.style.setProperty('--pw-font', fonts.ui);
    root.style.setProperty('--ovio-font', fonts.ui);
    root.style.setProperty('--pw-font-code', fonts.code);
    root.style.setProperty('--pw-font-cjk', fonts.chinese);
    root.style.setProperty('--font-ui', fonts.ui);
    root.style.setProperty('--font-code', fonts.code);
    root.style.setProperty('--font-reading', fonts.ui);
    root.style.setProperty('--font-sans', fonts.ui);
    root.style.setProperty('--font-mono', fonts.code);
    root.dataset.fontChinese = preference.chinese;
    root.dataset.fontLatin = preference.latin;
    root.dataset.fontCode = preference.code;
  }, [key]);
}
