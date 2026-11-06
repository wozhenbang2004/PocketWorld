"use client";

import {
  animate,
  PresenceContext,
  useMotionTemplate,
  useMotionValue,
  useSpring,
  useTransform,
  type SpringOptions,
} from "motion/react";
import {
  useContext,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import { steps, useReducedMotionSafe } from "@/lib/motion";
import { snapSplit, splitAt, splitForKey } from "./compare";
import type { CompareWorldProps } from "./compare-slider";

export type CompareFeel = {
  /** How the divider follows the pointer: a spring, or "step" to jump straight there. */
  follow: SpringOptions | "step";
  /** Snap in percent, and what an arrow key moves. Left out, dragging is continuous and keys move 5. */
  snap?: number;
};

/** Where the entrance starts: `before` covering the whole view. */
const ENTER_FROM = 100;
/** Lets the docs stage settle before the divider sweeps in, in ms. */
const ENTER_DELAY = 200;

/**
 * A world's divider: drag or click anywhere on the view, arrow keys on it. Returns the split as a
 * motion value (clamped, so an overshooting spring never leaves the view), the clip for `before`,
 * label fades, and the props for the view element.
 *
 * On mount the divider sweeps in from the right edge to `value` in the world's own feel, so the
 * view shows it can be dragged. Like a Motion entrance it is skipped inside
 * `<AnimatePresence initial={false}>` (a world switched in on the docs stage) and under reduced
 * motion. The server renders the starting position, so the sweep never begins with a jump.
 */
export function useCompareWorld(
  { value, setValue, label, beforeLabel, disabled }: CompareWorldProps,
  feel: CompareFeel,
) {
  const reduced = useReducedMotionSafe();
  // Read once, at mount: the presence context drops `initial` on later renders.
  const [enters] = useState(useContext(PresenceContext)?.initial !== false);
  const target = useMotionValue(enters ? ENTER_FROM : value);
  const sprung = useSpring(
    target,
    feel.follow === "step" ? { stiffness: 1, damping: 1 } : feel.follow,
  );
  const raw = reduced || feel.follow === "step" ? target : sprung;
  const split = useTransform(() => Math.max(0, Math.min(100, raw.get())));
  const rest = useTransform(() => 100 - split.get());
  const clip = useMotionTemplate`inset(0 ${rest}% 0 0)`;
  const left = useMotionTemplate`${split}%`;
  const beforeFade = useTransform(split, [0, 18], [0.3, 1]);
  const afterFade = useTransform(split, [82, 100], [1, 0.3]);
  const [dragging, setDragging] = useState(false);
  const drag = useRef<{ pointer: number; left: number; width: number } | null>(null);
  const last = useRef(value);
  const entrance = useRef<() => void>(undefined);

  useEffect(() => {
    if (!enters) return;
    if (reduced) {
      target.set(last.current);
      return;
    }
    // A spring world just retargets and lets its spring carry the sweep; Retro clunks across in
    // 10% steps.
    const to = last.current;
    if (feel.follow === "step") {
      const frames = Math.max(1, Math.round(Math.abs(ENTER_FROM - to) / 10));
      const run = animate(target, to, {
        duration: frames * 0.06,
        ease: steps(frames),
        delay: ENTER_DELAY / 1000,
      });
      entrance.current = () => run.stop();
    } else {
      const timer = setTimeout(() => target.set(to), ENTER_DELAY);
      entrance.current = () => clearTimeout(timer);
    }
    return () => entrance.current?.();
  }, [enters, reduced, feel.follow, target]);

  // Follow the controlled value when it changes from outside (not mid-drag).
  useEffect(() => {
    if (drag.current || last.current === value) return;
    last.current = value;
    target.set(value);
  }, [value, target]);

  const moveTo = (next: number) => {
    entrance.current?.();
    const v = snapSplit(next, feel.snap);
    target.set(v);
    if (v !== last.current) {
      last.current = v;
      setValue(v);
    }
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (disabled || !e.isPrimary || e.button !== 0) return;
    const r = e.currentTarget.getBoundingClientRect();
    drag.current = { pointer: e.pointerId, left: r.left, width: r.width };
    e.currentTarget.setPointerCapture(e.pointerId);
    e.currentTarget.focus();
    setDragging(true);
    moveTo(splitAt(e.clientX, r.left, r.width));
  };

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d || d.pointer !== e.pointerId) return;
    moveTo(splitAt(e.clientX, d.left, d.width));
  };

  const release = (e: PointerEvent<HTMLDivElement>) => {
    if (drag.current?.pointer !== e.pointerId) return;
    drag.current = null;
    setDragging(false);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (disabled) return;
    const next = splitForKey(e.key, last.current, feel.snap || 5);
    if (next === undefined) return;
    e.preventDefault();
    moveTo(next);
  };

  return {
    split,
    clip,
    left,
    beforeFade,
    afterFade,
    dragging,
    view: {
      role: "slider",
      tabIndex: disabled ? -1 : 0,
      "aria-label": label,
      "aria-valuemin": 0,
      "aria-valuemax": 100,
      "aria-valuenow": Math.round(value),
      "aria-valuetext": `${Math.round(value)}% ${beforeLabel}`,
      "aria-disabled": disabled || undefined,
      onPointerDown,
      onPointerMove,
      onPointerUp: release,
      onPointerCancel: release,
      onKeyDown,
    },
  };
}
