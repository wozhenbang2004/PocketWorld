"use client";

import { calcGeneratorDuration, spring } from "motion";
import { useCallback, useLayoutEffect, useRef } from "react";
import { useMotionAllowed } from "../../../../../../library/core";

type Spring = { stiffness: number; damping: number };

/**
 * How the indicator travels. Its two edges are separate springs: the one leading the move is stiff
 * and pulls ahead, the other is softer and follows, so the pill stretches toward the new tab and
 * gathers itself up behind, like a drop of liquid.
 */
export type Liquid = {
  lead: Spring;
  trail: Spring;
  /** How much thinner the pill gets per tab of stretch, as a share of its height. */
  squash: number;
  /** Rounded caps (a stadium) or square ones. */
  round: boolean;
  /** For a stepped look: frames per second, and the pixel grid edges land on. */
  fps?: number;
  snap?: number;
};

type PillFrame = { left: number; right: number; scale: number };
type Run = {
  frames: PillFrame[];
  dt: number;
  stepped: boolean;
  anims: Animation[];
};

/** Longest a move may take, in ms, whatever the springs say. */
const MAX = 1500;

function currentFrame(run: Run) {
  // The native animation may still be pending, or the page may have been busy
  // since it started. Its playback time, not a second wall clock, owns the pose.
  const animation = run.anims[0];
  const time = animation?.currentTime;
  const frame = Math.max(0, Math.min(run.frames.length - 1, (typeof time === "number" ? time : 0) / run.dt));
  const index = Math.floor(frame);
  const from = run.frames[index];
  const to = run.frames[Math.min(index + 1, run.frames.length - 1)];
  const mix = run.stepped ? 1 : frame - index;
  const velocity = run.stepped || animation?.pending || typeof time !== "number" ? 0 : 1000 / run.dt;
  return {
    left: from.left + (to.left - from.left) * mix,
    right: from.right + (to.right - from.right) * mix,
    scale: from.scale + (to.scale - from.scale) * mix,
    vL: (to.left - from.left) * velocity,
    vR: (to.right - from.right) * velocity,
  };
}

/**
 * Moves the indicator between tabs. Both edge springs are sampled up front and played as native
 * keyframe animations on transform (and on the labels' clip-path), so the pill keeps moving while
 * the page is busy. A new tab mid-move restarts from where the edges are, with their velocity.
 * At rest the CSS pill shows instead: the moving pieces end in exactly its shape, so the swap
 * can't be seen.
 */
export function useLiquid({
  index,
  count,
  liquid,
  reduced,
}: {
  index: number;
  count: number;
  liquid: Liquid;
  reduced: boolean;
}) {
  // The row the pill moves in; the pill at rest, placed by CSS; the moving pill, a wrapper that
  // squashes around a left cap, a body and a right cap; and the labels in the "on" colour.
  const box = useRef<HTMLDivElement>(null);
  const rest = useRef<HTMLDivElement>(null);
  const pieces = useRef<HTMLDivElement>(null);
  const capL = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const capR = useRef<HTMLDivElement>(null);
  const labels = useRef<HTMLDivElement>(null);
  const state = useRef({ from: index, count, W: 0, H: 0, run: null as Run | null });
  const allowed = useMotionAllowed();
  const settle = useCallback(() => {
    if (rest.current) rest.current.style.visibility = "";
    if (pieces.current) pieces.current.style.visibility = "hidden";
    state.current.run?.anims.forEach((a) => a.cancel());
    state.current.run = null;
  }, []);

  // The row's size, for the pixel geometry the moving pieces need.
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => {
      const s = state.current;
      // A width/font/viewport change invalidates the moving pill's pixel frames.
      // The resting pill and labels already use the current proportional layout.
      if (s.W !== el.clientWidth || s.H !== el.clientHeight || s.count !== count) settle();
      s.W = el.clientWidth;
      s.H = el.clientHeight;
      s.count = count;
      if (body.current) body.current.style.width = `${s.W / count}px`;
      for (const cap of [capL.current, capR.current]) if (cap) cap.style.width = `${s.H}px`;
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [count, settle]);

  useLayoutEffect(() => {
    const s = state.current;
    const prev = s.from;
    s.from = index;
    // Preferences and host visibility can change without a new selection.
    if (reduced || !allowed || document.hidden || !s.W) {
      settle();
      return;
    }
    if (prev === index) return;

    const { W, H } = s;
    const tab = W / count;
    // Start from where the edges are: mid-move, sampled with their velocity; at rest, the old tab.
    let L0 = prev * tab;
    let R0 = (prev + 1) * tab;
    let vL = 0;
    let vR = 0;
    let scale0: number | undefined;
    if (s.run) {
      const current = currentFrame(s.run);
      L0 = current.left;
      R0 = current.right;
      vL = current.vL;
      vR = current.vR;
      scale0 = current.scale;
    }
    const L1 = index * tab;
    const R1 = (index + 1) * tab;
    const toRight = L1 > L0;
    const edge = (from: number, to: number, velocity: number, leads: boolean) =>
      spring({
        keyframes: [from, to],
        velocity,
        ...(leads ? liquid.lead : liquid.trail),
        restDelta: 0.3,
        restSpeed: 8,
      });
    const gL = edge(L0, L1, vL, !toRight);
    const gR = edge(R0, R1, vR, toRight);
    const duration = Math.min(MAX, Math.max(calcGeneratorDuration(gL), calcGeneratorDuration(gR)));

    // One keyframe per frame; a stepped world jumps to each as its frame begins and holds it.
    const dt = 1000 / (liquid.fps ?? 60);
    const frames = Math.max(2, Math.ceil(duration / dt) + 1);
    const snap = (v: number) => (liquid.snap ? Math.round(v / liquid.snap) * liquid.snap : v);
    const easing = liquid.fps ? "steps(1, start)" : "linear";
    const leftCap: Keyframe[] = [];
    const rightCap: Keyframe[] = [];
    const middle: Keyframe[] = [];
    const squash: Keyframe[] = [];
    const clip: Keyframe[] = [];
    const samples: PillFrame[] = [];
    for (let k = 0; k < frames; k++) {
      const t = Math.min(duration, k * dt);
      // The last frame lands exactly on the resting pill, grid or not.
      const last = k === frames - 1;
      const L = last ? L1 : k === 0 ? L0 : snap(gL.next(t).value);
      const R = last ? R1 : k === 0 ? R0 : Math.max(L + H, snap(gR.next(t).value));
      // Thinner the further it stretches, never below 80% of its height.
      const sy = k === 0 && scale0 !== undefined ? scale0 : Math.max(0.8, 1 - liquid.squash * Math.max(0, (R - L) / tab - 1));
      samples.push({ left: L, right: R, scale: sy });
      const inset = (H * (1 - sy)) / 2;
      const radius = liquid.round ? (H / 2) * sy : 0;
      leftCap.push({ transform: `translateX(${L}px)`, easing });
      rightCap.push({ transform: `translateX(${R - H}px)`, easing });
      // The body runs between the caps' centres, under them, so no seam shows.
      middle.push({ transform: `translateX(${L + H / 2}px) scaleX(${(R - L - H) / tab})`, easing });
      squash.push({ transform: `scaleY(${sy})`, easing });
      clip.push({
        clipPath: `inset(${inset}px ${W - R}px ${inset}px ${L}px round ${radius}px)`,
        easing,
      });
    }

    const timing: KeyframeAnimationOptions = { duration: (frames - 1) * dt, fill: "both" };
    const anims = [
      capL.current?.animate(leftCap, timing),
      capR.current?.animate(rightCap, timing),
      body.current?.animate(middle, timing),
      pieces.current?.animate(squash, timing),
      labels.current?.animate(clip, timing),
    ].filter((a): a is Animation => !!a);

    // The new run takes over before the old one is dropped, so no frame shows neither.
    s.run?.anims.forEach((a) => a.cancel());
    const run: Run = { frames: samples, dt, stepped: !!liquid.fps, anims };
    s.run = run;
    if (pieces.current) pieces.current.style.visibility = "visible";
    if (rest.current) rest.current.style.visibility = "hidden";
    anims[0]?.finished.then(
      () => s.run === run && settle(),
      () => {},
    );
  }, [index, count, liquid, reduced, allowed, settle]);

  useLayoutEffect(() => {
    const s = state.current;
    return () => s.run?.anims.forEach((a) => a.cancel());
  }, []);

  return { box, rest, pieces, capL, body, capR, labels };
}
