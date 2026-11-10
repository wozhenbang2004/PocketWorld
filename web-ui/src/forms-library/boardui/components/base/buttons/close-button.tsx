import type { ButtonHTMLAttributes, Ref } from "react";
import { IconClose } from "../../../../../icons";
import { cx, sortCx } from "../../../utils/cx";

/**
 * Compact dismiss control for announcements, toasts, modals and drawers.
 * Retains Board UI's container sizes and interactions, using the shared
 * close glyph. Its size accounts for the source icon's own viewBox inset.
 */

type CloseButtonSize = "2xs" | "xs" | "sm" | "md";

export interface CloseButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children"> {
  size?: CloseButtonSize;
  /** Accessible name — required since there is no visible label. */
  "aria-label": string;
  ref?: Ref<HTMLButtonElement>;
}

const GLYPH_SIZE: Record<CloseButtonSize, number> = {
  "2xs": 12,
  xs: 16,
  sm: 18,
  md: 24,
};

const styles = sortCx({
  base: [
    "inline-flex shrink-0 items-center justify-center rounded-full",
    "bg-background-tertiary-default text-foreground-icon-secondary",
    "select-none cursor-pointer",
    "transition-colors duration-150 ease",
    "hover:text-text-primary",
    "outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-border-focus-ring",
  ].join(" "),
  container: {
    "2xs": "size-4",
    xs: "size-5",
    sm: "size-6",
    md: "size-8",
  },
});

export function CloseButton({
  size = "xs",
  className,
  type = "button",
  ref,
  ...props
}: CloseButtonProps) {
  const glyph = GLYPH_SIZE[size];

  return (
    <button
      ref={ref}
      type={type}
      className={cx(styles.base, styles.container[size], className)}
      {...props}
    >
      <IconClose size={glyph} aria-hidden />
    </button>
  );
}
