import { motion, useTransform } from "motion/react";
import { cn } from "@/lib/utils";
import type { CompareWorldProps } from "../compare-slider";
import { CompareScreen } from "../parts";
import { useCompareWorld, type CompareFeel } from "../use-compare";

/** Minimal: a stiff spring with no overshoot; the divider keeps up with the pointer. */
const feel: CompareFeel = { follow: { stiffness: 700, damping: 60 } };

const LABEL =
  "top-3 rounded-sm bg-white/85 px-1.5 py-0.5 font-(family-name:--ovio-mono) text-[10px] tracking-[0.14em] text-(--ovio-muted) uppercase";

export default function MinimalCompare(props: CompareWorldProps) {
  const compare = useCompareWorld(props, feel);
  const readout = useTransform(() => {
    const v = Math.round(compare.split.get());
    return `${v} / ${100 - v}`;
  });

  return (
    <div
      data-ovio-world="minimal"
      className={cn(
        "flex w-80 max-w-full flex-col gap-3 font-(family-name:--ovio-font) text-(--ovio-ink)",
        props.className,
      )}
    >
      <CompareScreen
        world={props}
        compare={compare}
        className="rounded-[14px] border border-(--ovio-line) bg-(--ovio-surface) shadow-[0_1px_2px_rgba(0,0,0,.04),0_18px_30px_-18px_rgba(0,0,0,.25)]"
        backClassName="bg-(--ovio-stage)"
        beforeLabelClassName={cn(LABEL, "left-3")}
        afterLabelClassName={cn(LABEL, "right-3")}
        dividerClassName="w-px bg-(--ovio-ink)"
        handle={
          <span
            className={cn(
              "absolute top-1/2 left-1/2 flex size-7.5 -translate-1/2 items-center justify-center rounded-full border border-(--ovio-ink) transition-colors duration-150",
              compare.dragging
                ? "bg-(--ovio-ink) text-(--ovio-on-accent)"
                : "bg-(--ovio-surface) text-(--ovio-ink)",
            )}
          >
            <svg width="14" height="12" viewBox="0 0 14 12" fill="none">
              <path
                d="M5 3 2 6l3 3M9 3l3 3-3 3"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </span>
        }
      />
      <div className="flex justify-between gap-3 font-(family-name:--ovio-mono) text-[11px] text-(--ovio-muted)">
        <span className="min-w-0 truncate">{props.label}</span>
        <motion.span className="shrink-0 text-(--ovio-ink)">{readout}</motion.span>
      </div>
    </div>
  );
}
