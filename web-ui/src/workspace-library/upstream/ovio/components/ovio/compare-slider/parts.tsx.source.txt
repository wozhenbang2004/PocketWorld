import { motion } from "motion/react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import type { CompareWorldProps } from "./compare-slider";
import type { useCompareWorld } from "./use-compare";

/** What the element carrying the slider props needs: a resize cursor, vertical scroll left to the page. */
export const GRAB = "cursor-ew-resize touch-pan-y select-none";

const FILL =
  "absolute inset-0 overflow-hidden [&_img]:pointer-events-none [&_img]:size-full [&_img]:object-cover [&>*]:size-full";

type ScreenProps = {
  world: CompareWorldProps;
  compare: ReturnType<typeof useCompareWorld>;
  /** Puts the slider props on the screen; Toy passes false and puts them on a wrapper. */
  slider?: boolean;
  className?: string;
  /** Backs `before`, so `after` never shows through it. */
  backClassName: string;
  beforeLabelClassName: string;
  afterLabelClassName: string;
  dividerClassName: string;
  /** Drawn on the divider, at its centre or wherever the world places it. */
  handle?: ReactNode;
  /** Drawn over everything, like Retro's scanlines. */
  children?: ReactNode;
};

/**
 * The view every world draws: both versions stacked, `before` clipped to the left of the divider,
 * the corner labels fading as the divider covers them, and the divider itself. Worlds style it.
 */
export function CompareScreen({
  world,
  compare,
  slider = true,
  className,
  backClassName,
  beforeLabelClassName,
  afterLabelClassName,
  dividerClassName,
  handle,
  children,
}: ScreenProps) {
  return (
    <div
      {...(slider ? compare.view : undefined)}
      className={cn("relative w-full overflow-hidden", slider && GRAB, className)}
      style={{ aspectRatio: world.aspectRatio }}
    >
      <div className={FILL}>{world.after}</div>
      <motion.div className={cn(FILL, backClassName)} style={{ clipPath: compare.clip }}>
        {world.before}
      </motion.div>
      <motion.span
        aria-hidden
        className={cn("absolute", beforeLabelClassName)}
        style={{ opacity: compare.beforeFade }}
      >
        {world.beforeLabel}
      </motion.span>
      <motion.span
        aria-hidden
        className={cn("absolute", afterLabelClassName)}
        style={{ opacity: compare.afterFade }}
      >
        {world.afterLabel}
      </motion.span>
      <motion.div
        aria-hidden
        className={cn("absolute inset-y-0 -translate-x-1/2", dividerClassName)}
        style={{ left: compare.left }}
      >
        {handle}
      </motion.div>
      {children}
    </div>
  );
}
