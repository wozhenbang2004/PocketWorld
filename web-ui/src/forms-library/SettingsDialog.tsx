import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { RiCloseLine } from '@remixicon/react';

/** PW-34. Controlled product port of BoardUI SettingsModal's two-phase
 * mount/visible animation, fixed title and separately scrolling content.
 * Source: upstream/components/application/settings/settings-modal.tsx.source.
 * Demo profile, billing rail, fake Saved toast and local setting writes removed.
 */
export function SettingsDialog({ isOpen, title, onClose, children, className = '', closeLabel = '关闭设置' }: {
  isOpen: boolean; title: string; onClose: () => void; children: ReactNode; className?: string; closeLabel?: string;
}) {
  const titleId = useId();
  const [mounted, setMounted] = useState(isOpen);
  const [visible, setVisible] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let frame1 = 0, frame2 = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    if (isOpen) {
      setMounted(true);
      frame1 = requestAnimationFrame(() => { frame2 = requestAnimationFrame(() => { setVisible(true); }); });
    } else {
      setVisible(false);
      timer = setTimeout(() => setMounted(false), 320);
    }
    return () => { cancelAnimationFrame(frame1); cancelAnimationFrame(frame2); if (timer) clearTimeout(timer); };
  }, [isOpen]);
  // Focus after React has actually mounted the panel; animation frames can run
  // before the deferred mount commits, especially on a busy WebView.
  useLayoutEffect(() => {
    if (!isOpen || !mounted || !panel.current) return;
    const origin = document.activeElement as HTMLElement | null;
    panel.current.focus({ preventScroll: true });
    return () => { if (origin?.isConnected) origin.focus({ preventScroll: true }); };
  }, [isOpen, mounted]);
  useEffect(() => {
    if (!isOpen) return;
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onClose(); return; }
      if (event.key !== 'Tab') return;
      const targets = Array.from(panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[href],[tabindex="0"]') ?? []).filter(x => x.getClientRects().length > 0);
      const first = targets[0], last = targets[targets.length - 1];
      if (!first) { event.preventDefault(); panel.current?.focus(); }
      else if (event.shiftKey && (document.activeElement === first || !panel.current?.contains(document.activeElement))) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || !panel.current?.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', key);
    return () => document.removeEventListener('keydown', key);
  }, [isOpen, onClose]);
  if (!mounted) return null;
  return createPortal(<div className="pw-settings-layer" data-visible={visible} inert={!isOpen}>
    <button className="pw-settings-backdrop" type="button" aria-label={closeLabel} tabIndex={-1} onClick={onClose} />
    <div ref={panel} className={`pw-settings-dialog ${className}`} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
      <header><h2 id={titleId}>{title}</h2><button type="button" className="pw-icon-action" aria-label={closeLabel} onClick={onClose}><RiCloseLine aria-hidden size={20} /></button></header>
      <div className="pw-settings-dialog-content">{children}</div>
    </div>
  </div>, document.body);
}
