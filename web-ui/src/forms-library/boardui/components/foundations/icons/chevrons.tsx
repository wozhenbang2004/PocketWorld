import type { SVGProps } from "react";
import { IconChevronDown, IconChevronRight, IconChevronUpDown } from "../../../../../icons";
import { cx } from "../../../utils/cx";

/**
 * Board UI's public chevron slots use PocketWorld's shared icon family.
 * Keep the source component names, size utilities and directional behavior.
 * The original Figma vectors remain in the retained upstream source.
 */

type IconProps = SVGProps<SVGSVGElement>;

/** Select triggers and disclosure affordances. */
export function ChevronDownSmall(props: IconProps) {
  return <IconChevronDown size={16} aria-hidden {...props} />;
}

/** Breadcrumb separator, mirrored in right-to-left layouts. */
export function ChevronRightSmall({ className, ...props }: IconProps) {
  return <IconChevronRight size={12} aria-hidden {...props} className={cx("rtl:rotate-180", className)} />;
}

/** Workspace and account switchers. */
export function ChevronUpDownSmall(props: IconProps) {
  return <IconChevronUpDown size={16} aria-hidden {...props} />;
}

/** Single-direction table sort indicator; the table rotates it for ascending. */
export function ChevronSortDown(props: IconProps) {
  return <IconChevronDown size={24} aria-hidden {...props} />;
}
