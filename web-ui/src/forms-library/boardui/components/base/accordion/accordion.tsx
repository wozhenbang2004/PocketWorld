"use client";

import { createContext, useContext } from "react";
import type { ComponentType, ReactNode } from "react";
import {
  Button as AriaButton,
  Disclosure as AriaDisclosure,
  DisclosureGroup as AriaDisclosureGroup,
  DisclosurePanel as AriaDisclosurePanel,
  Heading as AriaHeading,
} from "react-aria-components";
import type { DisclosureGroupProps as AriaDisclosureGroupProps, DisclosureProps as AriaDisclosureProps } from "react-aria-components";
import { RiAddLine, RiArrowDownSLine } from "@remixicon/react";
import { cx, sortCx } from "../../../utils/cx";
import { useMotionAllowed } from "../../../../../library/core";

/**
 * Accordion — stacked sections that expand in place: FAQs, settings groups,
 * order details. Built on React Aria's DisclosureGroup and Disclosure, so
 * each header is a real button with aria-expanded, and a collapsed panel is
 * `hidden="until-found"`: find-in-page still reaches its text and opens it.
 *
 *   <Accordion defaultExpandedKeys={["billing"]}>
 *     <AccordionItem id="billing" title="How does billing work?">…</AccordionItem>
 *     <AccordionItem id="seats" title="Can I add seats later?">…</AccordionItem>
 *   </Accordion>
 *
 * Variants: `divided` (hairlines between rows), `contained` (the landing
 * FAQ's tray of cards), and `separated` (one bordered card per item).
 *
 * Motion follows the agent limits card and Tree View: the panel's height
 * follows the product layout timing while its content fades and settles by
 * 4px; closing uses the shorter exit timing. The indicator turns with it
 * (a chevron flips, a plus turns into a close). React Aria
 * measures the panel and exposes `--disclosure-panel-height`, so this is
 * all CSS. Reduced motion switches every transition off.
 */

type IconComponent = ComponentType<{ className?: string; "aria-hidden"?: boolean | "true" | "false" }>;

export type AccordionVariant = "divided" | "contained" | "separated";

const EASE = "ease-(--pw-ease-move)";

const styles = sortCx({
  group: {
    divided: "flex flex-col divide-y divide-separator-border",
    // The landing FAQ: cards on a tray, inverted in dark mode so the cards
    // stay a step lighter than the page.
    contained: "flex flex-col gap-[3px] rounded-[26px] bg-background-secondary-default p-[3px] dark:bg-background-primary-default",
    separated: "flex flex-col gap-2",
  },
  item: {
    divided: "",
    contained: "rounded-[23px] bg-background-primary-default dark:bg-background-secondary-default",
    // Modal's ground rather than the card surface: in dark mode the inputs
    // and switch tracks a settings group holds keep an edge against it.
    separated: "rounded-2xl border border-border-button-default bg-background-full shadow-xs",
  },
  trigger: {
    divided: "rounded-lg py-4",
    contained: "rounded-[23px] px-5 py-4",
    separated: "rounded-2xl px-4 py-3.5",
  },
  content: {
    divided: "pb-4",
    contained: "px-5 pb-5",
    separated: "px-4 pb-4",
  },
});

interface AccordionContextValue {
  variant: AccordionVariant;
  indicator: "chevron" | "plus";
}

const AccordionContext = createContext<AccordionContextValue>({ variant: "divided", indicator: "chevron" });

export interface AccordionProps extends Omit<AriaDisclosureGroupProps, "className" | "children"> {
  variant?: AccordionVariant;
  /** `chevron` flips to point up; `plus` turns into a close. Default `chevron`. */
  indicator?: "chevron" | "plus";
  className?: string;
  children: ReactNode;
}

export function Accordion({ variant = "divided", indicator = "chevron", className, children, ...props }: AccordionProps) {
  return (
    <AccordionContext.Provider value={{ variant, indicator }}>
      <AriaDisclosureGroup {...props} className={cx(styles.group[variant], className)}>
        {children}
      </AriaDisclosureGroup>
    </AccordionContext.Provider>
  );
}

export interface AccordionItemProps extends Omit<AriaDisclosureProps, "className" | "children"> {
  title: ReactNode;
  /** Supporting line under the title, e.g. a summary of the settings inside. */
  description?: ReactNode;
  /** Remix Icon before the title. */
  icon?: IconComponent;
  /** Heading level of the title. Default 3. */
  headingLevel?: 2 | 3 | 4 | 5 | 6;
  className?: string;
  children: ReactNode;
}

export function AccordionItem({
  title,
  description,
  icon: Icon,
  headingLevel = 3,
  className,
  children,
  ...props
}: AccordionItemProps) {
  const { variant, indicator } = useContext(AccordionContext);
  const allowed = useMotionAllowed();
  const Indicator = indicator === "plus" ? RiAddLine : RiArrowDownSLine;

  return (
    <AriaDisclosure
      {...props}
      className={cx("group/item data-[disabled]:opacity-50", styles.item[variant], className)}
    >
      {({ isExpanded }) => <>
      <AriaHeading level={headingLevel} className="flex">
        <AriaButton
          slot="trigger"
          className={({ isFocusVisible }) =>
            cx(
              "group/trigger flex w-full cursor-pointer items-center gap-3 text-start outline-none",
              "disabled:cursor-not-allowed",
              isFocusVisible && "ring-2 ring-border-focus-ring ring-inset",
              styles.trigger[variant],
            )
          }
        >
          {Icon ? <Icon className="size-5 shrink-0 text-foreground-icon-secondary" aria-hidden /> : null}
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="text-headline-medium text-text-primary">{title}</span>
            {description ? <span className="text-body-regular text-text-secondary">{description}</span> : null}
          </span>
          <Indicator
            aria-hidden
            className={cx(
              "size-5 shrink-0",
              allowed ? "transition-[rotate,color] duration-(--pw-motion-layout) ease-(--pw-ease-settle)" : "transition-none",
              isExpanded ? "text-foreground-icon-primary" : "text-foreground-icon-tertiary group-hover/trigger:text-foreground-icon-secondary",
              isExpanded ? indicator === "plus" ? "rotate-45" : "rotate-180" : "rotate-0",
            )}
          />
        </AriaButton>
      </AriaHeading>
      <AriaDisclosurePanel
        className={cx("h-(--disclosure-panel-height) overflow-clip", allowed ? "transition-[height]" : "transition-none", isExpanded ? "duration-(--pw-motion-layout)" : "duration-(--pw-motion-exit)", EASE)}
      >
        <div
          className={cx(
            "text-body-regular text-text-secondary",
            allowed ? "transition-[opacity,translate]" : "transition-none",
            isExpanded ? "duration-(--pw-motion-enter) ease-(--pw-ease-enter)" : "duration-(--pw-motion-exit) ease-(--pw-ease-exit)",
            isExpanded ? "translate-y-0 opacity-100" : allowed ? "opacity-0 -translate-y-1" : "opacity-0",
            styles.content[variant],
          )}
        >
          {children}
        </div>
      </AriaDisclosurePanel>
      </>}
    </AriaDisclosure>
  );
}
