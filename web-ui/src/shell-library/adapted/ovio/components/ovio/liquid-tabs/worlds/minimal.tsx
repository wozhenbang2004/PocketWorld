import { LiquidBar, type LiquidLook } from "../parts";
import type { LiquidTabsWorldProps } from "../liquid-tabs";

const look: LiquidLook = {
  world: "minimal",
  track: { background: "var(--ovio-track)", borderRadius: 14 },
  blob: "var(--ovio-accent)",
  blobRadius: "999px",
  on: "#ffffff",
  off: "#4a4a4a",
  tab: "min-h-11 py-2.5 text-sm font-medium tracking-[-0.01em]",
  label: "text-xs",
  statusText: "text-lg font-medium",
  // A clean glide: both edges critically damped, so it stretches and gathers without overshoot.
  liquid: {
    lead: { stiffness: 520, damping: 46 },
    trail: { stiffness: 230, damping: 30 },
    squash: 0.06,
    round: true,
  },
  dots: ["#c9c9c9", "#979797", "#161616"],
};

export default function MinimalLiquidTabs(props: LiquidTabsWorldProps) {
  return <LiquidBar look={look} {...props} />;
}
