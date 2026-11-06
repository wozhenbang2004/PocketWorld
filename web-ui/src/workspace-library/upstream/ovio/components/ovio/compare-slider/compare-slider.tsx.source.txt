"use client";

import { type ComponentType, type ReactNode, useState } from "react";
import { useWorld } from "@/components/shared/world-provider";
import { everyWorld, type InstalledWorld } from "@/lib/world";
import * as worlds from "./worlds";

export interface CompareSliderProps {
  variant?: InstalledWorld<typeof worlds>;
  /** Shown left of the divider. Images fill the view. */
  before: ReactNode;
  /** Shown right of the divider. */
  after: ReactNode;
  beforeLabel?: string;
  afterLabel?: string;
  /** How much of `before` shows, 0–100. */
  value?: number;
  defaultValue?: number;
  /** Fires while dragging. */
  onValueChange?: (value: number) => void;
  /** Accessible name for the divider. */
  label?: string;
  /** CSS aspect ratio of the view. */
  aspectRatio?: string;
  disabled?: boolean;
  className?: string;
}

export type CompareWorldProps = Required<
  Pick<
    CompareSliderProps,
    "before" | "after" | "beforeLabel" | "afterLabel" | "label" | "aspectRatio"
  >
> &
  Pick<CompareSliderProps, "disabled" | "className"> & {
    value: number;
    setValue: (value: number) => void;
  };

/** The installed worlds; a single-world install draws its one world for any provider world. */
const VIEWS = everyWorld<ComponentType<CompareWorldProps>>(worlds);

export function CompareSlider({
  variant,
  value: valueProp,
  defaultValue = 50,
  onValueChange,
  beforeLabel = "Before",
  afterLabel = "After",
  label = "Compare",
  aspectRatio = "4 / 3",
  ...rest
}: CompareSliderProps) {
  const world = useWorld(variant);
  const [inner, setInner] = useState(defaultValue);
  const value = Math.max(0, Math.min(100, valueProp ?? inner));

  const setValue = (next: number) => {
    if (valueProp === undefined) setInner(next);
    onValueChange?.(next);
  };

  const View = VIEWS[world] ?? VIEWS.minimal;
  return (
    <View
      {...rest}
      beforeLabel={beforeLabel}
      afterLabel={afterLabel}
      label={label}
      aspectRatio={aspectRatio}
      value={value}
      setValue={setValue}
    />
  );
}
