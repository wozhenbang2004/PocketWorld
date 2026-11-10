import { useId, useLayoutEffect, useRef, type ReactNode, type SyntheticEvent } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, animate, motion, useMotionValue, usePresence } from 'motion/react';
import { FocusScope } from 'react-aria';
import { RiCloseLine } from '@remixicon/react';
import { useMotionAllowed } from '../library/core';
import { motionTransition } from '../library/motion-tokens';

type SettingsDialogProps = {
  isOpen: boolean; title: string; onClose: () => void; children: ReactNode; className?: string; closeLabel?: string;
};

/** PW-34. Controlled port of BoardUI SettingsModal's fixed heading and
 * separately scrolling content. Motion owns the real exit lifetime; the last
 * rendered body remains in place when the caller clears a completed review.
 * Source: upstream/components/application/settings/settings-modal.tsx.source.
 */
export function SettingsDialog({ isOpen, ...props }: SettingsDialogProps) {
  return createPortal(<AnimatePresence>
    {isOpen && <SettingsSurface key="settings" {...props} />}
  </AnimatePresence>, document.body);
}

function SettingsSurface({ title, onClose, children, className = '', closeLabel = '关闭设置' }: Omit<SettingsDialogProps, 'isOpen'>) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const [present, safeToRemove] = usePresence();
  const remove = useRef(safeToRemove);
  const allowed = useMotionAllowed();
  const opacity = useMotionValue(allowed ? 0 : 1);
  const scale = useMotionValue(allowed ? .97 : 1);
  const y = useMotionValue(allowed ? 6 : 0);
  useLayoutEffect(() => { remove.current = safeToRemove; }, [safeToRemove]);

  useLayoutEffect(() => {
    let cancelled = false;
    if (!allowed) {
      opacity.jump(present ? 1 : 0); scale.jump(1); y.jump(0);
      if (!present) remove.current?.();
      return;
    }
    const transition = present ? motionTransition.enter : motionTransition.exit;
    // Retarget the existing values, including a reversal before exit finishes.
    const animations = [
      animate(opacity, present ? 1 : 0, transition),
      animate(scale, present ? 1 : .97, transition),
      animate(y, present ? 0 : 6, transition),
    ];
    void Promise.all(animations).then(() => {
      if (!cancelled && !present) remove.current?.();
    });
    return () => { cancelled = true; animations.forEach(animation => animation.stop()); };
  }, [allowed, present, opacity, scale, y]);

  useLayoutEffect(() => {
    if (present && panel.current && !panel.current.contains(document.activeElement)) panel.current.focus({ preventScroll: true });
  }, [present]);

  const blockClosed = (event: SyntheticEvent) => {
    if (!present) { event.preventDefault(); event.stopPropagation(); }
  };
  return <div className="pw-settings-layer" data-visible={present} inert={!present}
    onClickCapture={blockClosed} onPointerDownCapture={blockClosed} onKeyDownCapture={blockClosed} onSubmitCapture={blockClosed}>
    <motion.button className="pw-settings-backdrop" style={{ opacity }} type="button" aria-label={closeLabel}
      tabIndex={-1} onClick={() => { if (present) onClose(); }} />
    <FocusScope contain={present} restoreFocus>
      <motion.div ref={panel} style={{ opacity, scale, y }} className={`pw-settings-dialog ${className}`}
        role="dialog" data-state={present ? 'open' : 'closed'} aria-modal="true" aria-labelledby={titleId} tabIndex={-1}
        onKeyDown={event => {
          if (present && event.key === 'Escape' && !event.defaultPrevented && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) { event.preventDefault(); event.stopPropagation(); onClose(); }
        }}>
        <header><h2 id={titleId}>{title}</h2><button type="button" className="pw-icon-action" aria-label={closeLabel} onClick={() => { if (present) onClose(); }}><RiCloseLine aria-hidden size={20} /></button></header>
        <div className="pw-settings-dialog-content">{children}</div>
      </motion.div>
    </FocusScope>
  </div>;
}
