import React from 'react';
import { animate, motionValue, type MotionValue } from 'motion';
import { motionEase, motionTransition } from '../../../../library/motion-tokens';
import { drawerDuration, drawerMotionAllowed } from './motion';
import { getTranslate } from './helpers';

type Sample = { x: number; time: number };
type Gesture = {
  kind: 'touch' | 'pointer';
  id: number;
  startX: number;
  startY: number;
  x: number;
  claimed: boolean;
  base: number | null;
  samples: Sample[];
  released?: { open: boolean; velocity: number };
};

type Options = {
  enabled: boolean;
  area: string;
  isOpen: boolean;
  autoFocus: boolean;
  drawerRef: React.RefObject<HTMLDivElement | null>;
  overlayRef: React.RefObject<HTMLDivElement | null>;
  onOpen: () => void;
  onClose: () => void;
};

const INTERACTIVE =
  'input,textarea,select,button,a,label,summary,audio,video,iframe,[role="button"],[role="slider"],[role="tab"],[contenteditable]:not([contenteditable="false"]),[data-vaul-no-drag]';
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

function sample(current: Gesture, x: number) {
  const time = performance.now();
  current.samples = current.samples.filter((point) => time - point.time <= 100);
  current.samples.push({ x, time });
  current.x = x;
}

function releaseVelocity(current: Gesture) {
  const first = current.samples[0];
  const last = current.samples[current.samples.length - 1];
  return last.time > first.time ? ((last.x - first.x) / (last.time - first.time)) * 1000 : 0;
}

/** Horizontal dragging must yield to controls, text selection and horizontal scrollers. */
export function canDragHorizontally(target: EventTarget | null, boundary?: HTMLElement | null) {
  if (!(target instanceof HTMLElement) || target.closest(INTERACTIVE) || window.getSelection()?.toString()) return false;
  for (let node: HTMLElement | null = target; node; node = node.parentElement) {
    if (node.scrollWidth > node.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(node).overflowX)) return false;
    if (node === boundary) break;
  }
  return true;
}

/**
 * Extends Vaul's existing left drawer with document-owned opening and Content
 * pointer gestures. Both hand the same position to the release spring.
 * The same Radix Content stays mounted while Motion settles it; visual presence
 * never delays the controlled open state or creates a second navigation tree.
 */
export function useNavigationSwipe(options: Options) {
  const optionsRef = React.useRef(options);
  optionsRef.current = options;
  const [present, setPresent] = React.useState(false);
  const [isTracking, setIsTracking] = React.useState(false);
  const presentRef = React.useRef(false);
  const tracking = React.useRef(false);
  const gesture = React.useRef<Gesture | null>(null);
  const width = React.useRef(0);
  const measuredDrawer = React.useRef<HTMLDivElement | null>(null);
  const resizeObserver = React.useRef<ResizeObserver | null>(null);
  const position = React.useRef<MotionValue<number> | null>(null);
  if (!position.current) position.current = motionValue(0);
  const animation = React.useRef<{ stop: () => void } | null>(null);
  const animationTarget = React.useRef<boolean | null>(null);
  const epoch = React.useRef(0);
  const wasOpen = React.useRef(options.isOpen);
  const pendingEnter = React.useRef(false);
  const shouldFocus = React.useRef(false);
  const focusRequested = React.useRef(false);
  const skipReturnFocus = React.useRef(false);

  function keepPresent(value: boolean) {
    presentRef.current = value;
    setPresent(value);
  }

  function stopAnimation() {
    epoch.current += 1;
    animation.current?.stop();
    animation.current = null;
    animationTarget.current = null;
  }

  function renderPosition(value: number) {
    const { drawerRef, overlayRef } = optionsRef.current;
    const x = clamp(value, -width.current, 0);
    if (drawerRef.current) drawerRef.current.style.transform = `translate3d(${x}px, 0, 0)`;
    if (overlayRef.current) overlayRef.current.style.opacity = `${width.current ? 1 + x / width.current : 0}`;
  }

  function ownMotion(measure = false) {
    const { drawerRef, overlayRef } = optionsRef.current;
    const drawer = drawerRef.current;
    if (drawer && (measure || drawer !== measuredDrawer.current || !width.current)) {
      width.current = drawer.getBoundingClientRect().width || width.current;
      if (drawer !== measuredDrawer.current) {
        resizeObserver.current?.disconnect();
        measuredDrawer.current = drawer;
        resizeObserver.current = new ResizeObserver(([entry]) => {
          if (!entry || entry.target !== measuredDrawer.current) return;
          const nextWidth = entry.borderBoxSize[0]?.inlineSize ?? drawer.getBoundingClientRect().width;
          if (!nextWidth || Math.abs(nextWidth - width.current) < 0.5) return;
          width.current = nextWidth;
          converge();
        });
        resizeObserver.current.observe(drawer);
      }
    }
    for (const node of [drawerRef.current, overlayRef.current]) {
      if (!node) continue;
      // The open-state marker prevents CSS entry from restarting after the spring.
      if (!node.hasAttribute('data-vaul-gesture')) node.setAttribute('data-vaul-gesture', '');
      if (!node.hasAttribute('data-vaul-navigation-swipe')) node.setAttribute('data-vaul-navigation-swipe', '');
    }
  }

  function releaseCapture(current: Gesture | null) {
    const drawer = optionsRef.current.drawerRef.current;
    if (current?.kind === 'pointer' && drawer?.hasPointerCapture(current.id)) drawer.releasePointerCapture(current.id);
  }

  function finish(open: boolean) {
    stopAnimation();
    ownMotion(true);
    position.current!.jump(open ? 0 : -width.current);
    renderPosition(position.current!.get());
    if (open) {
      optionsRef.current.drawerRef.current?.removeAttribute('data-vaul-navigation-swipe');
      optionsRef.current.overlayRef.current?.removeAttribute('data-vaul-navigation-swipe');
    } else {
      resizeObserver.current?.disconnect();
      measuredDrawer.current = null;
    }
    // A closing surface keeps the no-animation marker until Radix unmounts it.
    keepPresent(false);
  }

  function settle(open: boolean, velocity = 0, updateOpen = true, phase?: 'enter' | 'exit') {
    const hadGesture = tracking.current;
    const current = gesture.current;
    tracking.current = false;
    setIsTracking(false);
    gesture.current = null;
    releaseCapture(current);
    stopAnimation();
    ownMotion(true);
    keepPresent(true);
    if (!open) {
      focusRequested.current = false;
      if (hadGesture) skipReturnFocus.current = true;
      if (updateOpen) optionsRef.current.onClose();
    } else if (shouldFocus.current && optionsRef.current.autoFocus) focusRequested.current = true;
    shouldFocus.current = false;
    if (!drawerMotionAllowed() || !width.current) {
      finish(open);
      return;
    }
    animationTarget.current = open;
    const version = epoch.current;
    animation.current = animate(position.current!, open ? 0 : -width.current, {
      ...(phase
        ? { duration: drawerDuration(phase) / 1000, ease: motionEase[phase] }
        : { ...motionTransition.layout, velocity, restDelta: 0.35, restSpeed: 8 }),
      onUpdate: renderPosition,
      onComplete: () => {
        if (epoch.current === version) finish(open);
      },
    });
  }

  function showGesture(current: Gesture) {
    ownMotion();
    if (!width.current) return;
    const base = current.base ?? -width.current;
    position.current!.jump(clamp(base + (current.claimed ? current.x - current.startX : 0), -width.current, 0));
    renderPosition(position.current!.get());
  }

  function syncContent() {
    const currentOptions = optionsRef.current;
    if (!currentOptions.enabled || !currentOptions.isOpen || !currentOptions.drawerRef.current) return;
    const current = gesture.current;
    if (current && (current.claimed || current.kind === 'pointer')) {
      showGesture(current);
      if (current.released) settle(current.released.open, current.released.velocity);
    } else if (pendingEnter.current) {
      pendingEnter.current = false;
      ownMotion();
      position.current!.jump(-width.current);
      renderPosition(position.current!.get());
      settle(true, 0, false, 'enter');
    }
  }

  function abort(immediate = false) {
    const current = gesture.current;
    if (!current) return;
    if (!current.claimed && current.kind === 'touch') {
      gesture.current = null;
      return;
    }
    // Cancelling an in-drawer gesture returns to the existing open navigation.
    // An uncommitted opening swipe still returns to its original closed state.
    const open = current.kind === 'pointer' && optionsRef.current.isOpen;
    if (immediate) {
      gesture.current = null;
      tracking.current = false;
      setIsTracking(false);
      releaseCapture(current);
      skipReturnFocus.current = !open;
      shouldFocus.current = false;
      focusRequested.current = false;
      if (!open) optionsRef.current.onClose();
      finish(open);
    } else {
      settle(open, 0);
    }
  }

  function startPointer(event: React.PointerEvent<HTMLDivElement>) {
    const currentOptions = optionsRef.current;
    const drawer = currentOptions.drawerRef.current;
    if (!currentOptions.enabled || !currentOptions.isOpen || !drawer || !event.isPrimary || gesture.current) return;
    const base = getTranslate(drawer, 'left') ?? 0;
    stopAnimation();
    ownMotion(true);
    pendingEnter.current = false;
    shouldFocus.current = false;
    focusRequested.current = false;
    skipReturnFocus.current = false;
    gesture.current = {
      kind: 'pointer', id: event.pointerId, startX: event.clientX, startY: event.clientY,
      x: event.clientX, claimed: false, base,
      samples: [{ x: event.clientX, time: performance.now() }],
    };
    tracking.current = true;
    // Content must remain an input target for its captured pointer. The inert
    // state is reserved for the document-owned opening touch outside Content.
    drawer.setPointerCapture(event.pointerId);
    keepPresent(true);
    showGesture(gesture.current);
  }

  function ownsPointer(id: number) {
    return gesture.current?.kind === 'pointer' && gesture.current.id === id;
  }

  function dragPointer(event: React.PointerEvent<HTMLDivElement>) {
    const current = gesture.current;
    if (!current || !ownsPointer(event.pointerId)) return null;
    if (window.getSelection()?.toString()) {
      abort();
      return 0;
    }
    sample(current, event.clientX);
    current.claimed ||= Math.abs(current.x - current.startX) >= (event.pointerType === 'touch' ? 10 : 2);
    if (current.claimed && event.cancelable) event.preventDefault();
    showGesture(current);
    return width.current ? -position.current!.get() / width.current : 0;
  }

  function releasePointer(event: React.PointerEvent<HTMLDivElement> | null) {
    const current = gesture.current;
    if (!current || current.kind !== 'pointer' || event && !ownsPointer(event.pointerId)) return null;
    const dragged = current.claimed && !!event;
    let open = true, velocity = 0;
    if (dragged && event) {
      sample(current, event.clientX);
      showGesture(current);
      velocity = releaseVelocity(current);
      // A recent reversal reopens even after crossing the distance threshold.
      const close = velocity < -400 || (velocity <= 250 && -position.current!.get() >= Math.max(48, width.current * 0.25));
      open = !close;
    }
    settle(open, velocity);
    return { open, dragged };
  }

  function converge() {
    if (tracking.current) abort(true);
    else {
      gesture.current = null;
      if (presentRef.current) finish(optionsRef.current.isOpen);
    }
  }

  React.useLayoutEffect(() => {
    if (!options.enabled) {
      pendingEnter.current = false;
      if (tracking.current) abort(true);
      else if (presentRef.current) finish(options.isOpen);
      wasOpen.current = options.isOpen;
      return;
    }
    if (wasOpen.current && !options.isOpen && animationTarget.current !== false) {
      pendingEnter.current = false;
      // Native Back / Escape / another controlled close wins over the active finger.
      const interrupted = presentRef.current;
      if (!interrupted && options.drawerRef.current) {
        position.current!.jump(getTranslate(options.drawerRef.current, 'left') ?? 0);
      }
      settle(false, position.current!.getVelocity(), false, interrupted ? undefined : 'exit');
    } else if (!wasOpen.current && options.isOpen && animationTarget.current === false) {
      skipReturnFocus.current = false;
      shouldFocus.current = options.autoFocus;
      settle(true, position.current!.getVelocity(), false);
    } else if (!wasOpen.current && options.isOpen && !gesture.current?.claimed) {
      // Button opening shares the same position owner as subsequent gestures.
      pendingEnter.current = true;
    }
    wasOpen.current = options.isOpen;
    syncContent();
    if (focusRequested.current && options.isOpen && !tracking.current) {
      focusRequested.current = false;
      options.drawerRef.current?.focus({ preventScroll: true });
    }
  });

  React.useEffect(() => {
    if (!options.enabled) return;
    const area = options.area;
    const onStart = (event: TouchEvent) => {
      // A touch inside the open drawer also produces Pointer Events. Its
      // captured pointer already owns this gesture; do not start a second one.
      if (gesture.current?.kind === 'pointer') {
        if (event.touches.length !== 1) abort();
        return;
      }
      if (gesture.current) {
        abort();
        return;
      }
      const currentOptions = optionsRef.current;
      if (currentOptions.isOpen || event.touches.length !== 1 || document.hidden) return;
      const target = event.target;
      if (!(target instanceof HTMLElement) || !canDragHorizontally(target)) return;
      const continuesExit = presentRef.current && (
        currentOptions.overlayRef.current?.contains(target) || currentOptions.drawerRef.current?.contains(target)
      );
      if (!target.closest(area) && !continuesExit) return;
      if (document.querySelector('[role="dialog"]:not([data-state="closed"]),[role="alertdialog"],[aria-modal="true"]:not([data-state="closed"])')) return;
      const touch = event.touches[0];
      gesture.current = {
        kind: 'touch',
        id: touch.identifier,
        startX: touch.clientX,
        startY: touch.clientY,
        x: touch.clientX,
        claimed: false,
        base: null,
        samples: [{ x: touch.clientX, time: performance.now() }],
      };
    };
    const onMove = (event: TouchEvent) => {
      const current = gesture.current;
      if (!current || current.kind !== 'touch') return;
      if (event.touches.length !== 1 || window.getSelection()?.toString()) {
        abort();
        return;
      }
      const touch = Array.from(event.touches).find((item) => item.identifier === current.id);
      if (!touch) return;
      const dx = touch.clientX - current.startX;
      const dy = touch.clientY - current.startY;
      if (!current.claimed) {
        if (dx < -12 || (Math.abs(dy) >= 12 && Math.abs(dy) >= Math.abs(dx))) {
          gesture.current = null;
          return;
        }
        if (dx < 12 || dx < Math.abs(dy) * 1.5 || !event.cancelable) return;
        // Read the visible frame before cancelling a previous closing spring.
        const drawer = optionsRef.current.drawerRef.current;
        current.base = drawer ? getTranslate(drawer, 'left') : null;
        stopAnimation();
        ownMotion(true);
        current.claimed = true;
        tracking.current = true;
        setIsTracking(true);
        skipReturnFocus.current = false;
        shouldFocus.current = optionsRef.current.autoFocus;
        keepPresent(true);
        optionsRef.current.onOpen();
      }
      event.preventDefault();
      sample(current, touch.clientX);
      if (optionsRef.current.drawerRef.current) showGesture(current);
    };
    const onEnd = (event: TouchEvent) => {
      const current = gesture.current;
      if (!current || current.kind !== 'touch') return;
      const touch = Array.from(event.changedTouches).find((item) => item.identifier === current.id);
      if (!touch) return;
      if (!current.claimed) {
        gesture.current = null;
        return;
      }
      if (event.cancelable) event.preventDefault();
      sample(current, touch.clientX);
      const velocity = releaseVelocity(current);
      const distance = Math.max(0, (current.base ?? -width.current) + current.x - current.startX + width.current);
      // A deliberate reversal cancels even after crossing the distance threshold.
      const open = velocity > 400 || (velocity >= -250 && distance >= Math.max(48, width.current * 0.25));
      current.released = { open, velocity };
      if (optionsRef.current.drawerRef.current) {
        showGesture(current);
        settle(open, velocity);
      }
    };
    const onSelection = () => {
      if (window.getSelection()?.toString()) abort();
    };
    const onCancel = () => abort();
    const onVisibility = () => {
      if (document.hidden) converge();
    };
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    // These listeners deliberately do not depend on isOpen: opening must retain
    // ownership of the original touch, even after Radix locks outside scrolling.
    document.addEventListener('touchstart', onStart, { passive: true, capture: true });
    document.addEventListener('touchmove', onMove, { passive: false, capture: true });
    document.addEventListener('touchend', onEnd, { passive: false, capture: true });
    document.addEventListener('touchcancel', onCancel, true);
    document.addEventListener('contextmenu', onCancel, true);
    document.addEventListener('selectionchange', onSelection);
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('resize', converge);
    window.addEventListener('blur', converge);
    preference.addEventListener('change', converge);
    return () => {
      document.removeEventListener('touchstart', onStart, true);
      document.removeEventListener('touchmove', onMove, true);
      document.removeEventListener('touchend', onEnd, true);
      document.removeEventListener('touchcancel', onCancel, true);
      document.removeEventListener('contextmenu', onCancel, true);
      document.removeEventListener('selectionchange', onSelection);
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('resize', converge);
      window.removeEventListener('blur', converge);
      preference.removeEventListener('change', converge);
      stopAnimation();
      resizeObserver.current?.disconnect();
      measuredDrawer.current = null;
      const current = gesture.current;
      gesture.current = null;
      tracking.current = false;
      releaseCapture(current);
    };
  }, [options.enabled, options.area]);

  return {
    enabled: options.enabled,
    // Retain the already mounted instance across the render that requests close,
    // before the layout effect starts its motion from the last visible position.
    present: present || (options.enabled && wasOpen.current && !options.isOpen),
    isTracking,
    tracking,
    syncContent,
    startPointer,
    ownsPointer,
    dragPointer,
    releasePointer,
    deferAutoFocus(event: Event) {
      if (!tracking.current) return;
      shouldFocus.current = optionsRef.current.autoFocus && !event.defaultPrevented;
      event.preventDefault();
    },
    preserveCloseFocus(event: Event) {
      if (!skipReturnFocus.current) return false;
      skipReturnFocus.current = false;
      event.preventDefault();
      return true;
    },
    interrupt() {
      if (tracking.current) return;
      stopAnimation();
      optionsRef.current.drawerRef.current?.removeAttribute('data-vaul-navigation-swipe');
      optionsRef.current.overlayRef.current?.removeAttribute('data-vaul-navigation-swipe');
      if (presentRef.current) keepPresent(false);
    },
    cancelGesture() {
      if (!tracking.current) return;
      const current = gesture.current;
      tracking.current = false;
      setIsTracking(false);
      gesture.current = null;
      releaseCapture(current);
      skipReturnFocus.current = true;
      shouldFocus.current = false;
      focusRequested.current = false;
    },
    blocksInteraction: () => tracking.current || animationTarget.current === false,
  };
}

export type NavigationSwipe = ReturnType<typeof useNavigationSwipe>;
