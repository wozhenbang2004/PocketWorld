/* PW-26 official MIT source adaptation; original retained upstream. Business settlement is host-controlled, no timed fake answer/fold. */
"use client";

import { useState } from "react";
import { IconCheckCircle, IconChevronRight, IconWarning } from "../icons";
import { Button } from "../forms-library";

/*
 * Decision Inbox (clean style) – open decisions with two quick answers each, in one white card, in a single self-contained file.
 * A heading row with the count sits at the top of the card; every decision is a row under a hairline.
 * Host-provided resolved state controls completion; the compact task layout keeps the next action visible.
 * The styles are injected below, so no extra files are needed.
 */

export type Decision = {
  id: string;
  title: string;
  /** Short context under the title */
  detail?: string;
  /** Main answer, shown as the dark button */
  primary: string;
  /** Other answer, shown as the light button */
  secondary: string;
  /** Starts folded to one muted line – click to open */
  collapsed?: boolean;
  resolved?: string;
};

export type DecisionInboxProps = {
  decisions: Decision[];
  /** Product section title; omit to retain the source's count heading. */
  heading?: string;
  /** Continuous task rows with immediately available next actions. */
  compact?: boolean;
  /** Called with the decision and the chosen answer */
  onDecide?: (decision: Decision, answer: "primary" | "secondary") => void | Promise<void>;
  className?: string;
};

type State = { open: boolean; pending?: boolean; error?: string };

export function DecisionInbox({ decisions, onDecide, className, heading, compact = false }: DecisionInboxProps) {
  const [state, setState] = useState<Record<string, State>>(() =>
    Object.fromEntries(decisions.map((d) => [d.id, { open: !d.collapsed }])),
  );
  const patch = (id: string, next: Partial<State>) => setState((s) => ({ ...s, [id]: { ...s[id], ...next } }));

  const open = decisions.filter((d) => !d.resolved).length;
  // 1284 → "1,284"
  const count = new Intl.NumberFormat("en").format(open);

  const decide = async (d: Decision, answer: "primary" | "secondary") => {
    if (!onDecide || state[d.id]?.pending) return;
    patch(d.id, { pending: true, error: undefined });
    try { await onDecide(d, answer); }
    catch (error) { patch(d.id, { error: error instanceof Error ? error.message : "操作未确认，请核对原任务" }); }
    finally { patch(d.id, { pending: false }); }
    // The row remains until the host supplies a resolved state or removes it.
  };

  return (
    <>
      <style href="ui-decision-inbox-clean" precedence="default">
        {css}
      </style>
      <section className={["ui-dic", className].filter(Boolean).join(" ")} data-clear={open === 0 || undefined} data-compact={compact || undefined}>
        <header className="ui-dic__head" aria-live="polite">
          {open > 0 ? (
            <>
              <AlertIcon />
              <span>
                {heading || `${count} 项待你处理`}{compact && heading && <span className="ui-dic__count">{count}</span>}
              </span>
            </>
          ) : (
            <>
              <DoneIcon />
              <span>当前没有待处理项</span>
            </>
          )}
        </header>

        <div className="ui-dic__card">
          {decisions.map((d) => {
            const s = state[d.id] ?? { open: !d.collapsed };
            const answered = d.resolved;
            if (compact) return (
              <div key={d.id} className="ui-dic__compact-item" data-task-id={d.id} data-motion-key={d.id} data-answered={answered ? true : undefined} aria-busy={s.pending || undefined}>
                <Button variant="ghost" className="ui-dic__compact-open" disabled={s.pending || Boolean(answered)} aria-label={`${d.secondary}：${d.title}`} onClick={() => decide(d, "secondary")}>
                  <span className="ui-dic__compact-copy">
                    <span className="ui-dic__title" dir="auto" title={d.title}>{d.title}</span>
                    {d.detail && <span className="ui-dic__detail" dir="auto">{d.detail}</span>}
                  </span>
                </Button>
                <div className="ui-dic__compact-actions">
                  {answered ? <span className="ui-dic__compact-done" role="status"><DoneIcon/><span dir="auto">{answered}</span></span> : <>
                    <Button variant="ghost" className="ui-dic__next" trailingIcon={IconChevronRight} disabled={s.pending} onClick={() => decide(d, "primary")}>{d.primary}</Button>
                  </>}
                </div>
                {s.error && <p role="alert" className="ui-dic__compact-error">{s.error}</p>}
              </div>
            );
            return (
              <div key={d.id} className="ui-dic__row" data-task-id={d.id} data-motion-key={d.id} data-gone={undefined}>
                <div className="ui-dic__row-inner">
                  <div className="ui-dic__item" data-open={s.open || undefined} data-answered={answered ? true : undefined}>
                    {s.open ? (
                      <p className="ui-dic__title" dir="auto">
                        {d.title}
                      </p>
                    ) : (
                      <button type="button" className="ui-dic__title ui-dic__title--folded" dir="auto" title={d.title} onClick={() => patch(d.id, { open: true })}>
                        {d.title}
                      </button>
                    )}
                    <div className="ui-dic__body" data-open={s.open || undefined}>
                      <div className="ui-dic__body-inner">
                        {d.detail && (
                          <p className="ui-dic__detail" dir="auto">
                            {d.detail}
                          </p>
                        )}
                        <div className="ui-dic__actions">
                          {answered ? (
                            <span className="ui-dic__done" role="status">
                              <DoneIcon />
                              <span dir="auto">{answered}</span>
                            </span>
                          ) : (
                            <>
                              <button type="button" className="ui-dic__btn ui-dic__btn--primary" dir="auto" disabled={s.pending} onClick={() => decide(d, "primary")}>
                                {d.primary}
                              </button>
                              <button type="button" className="ui-dic__btn" dir="auto" disabled={s.pending} onClick={() => decide(d, "secondary")}>
                                {d.secondary}
                              </button>
                            </>
                          )}
                        </div>
                        {s.error && <p role="alert" className="ui-dic__detail">{s.error}</p>}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
          <p className="ui-dic__empty">需要你介入的事项会保留在这里。</p>
        </div>
      </section>
    </>
  );
}

/* ---------- Icons ---------- */

const AlertIcon = () => <IconWarning className="ui-dic__glyph" size={16} aria-hidden="true" />;

const DoneIcon = () => <IconCheckCircle className="ui-dic__glyph" size={16} aria-hidden="true" />;

/* ---------- Styles ---------- */

const css = /* css */ `
/* Colors and motion as variables – override them on .ui-dic */
.ui-dic {
  --ui-dic-card: #ffffff;
  --ui-dic-strong: #000000;
  --ui-dic-ink: rgba(0, 0, 0, 0.88);
  --ui-dic-text: rgba(0, 0, 0, 0.61);
  --ui-dic-muted: rgba(0, 0, 0, 0.45);
  --ui-dic-faint: rgba(0, 0, 0, 0.27);
  --ui-dic-line: rgba(0, 0, 0, 0.06);
  --ui-dic-wash: rgba(0, 0, 0, 0.03);
  --ui-dic-alert: #e8a20c;
  --ui-dic-ok: #1a9a52;
  --ui-dic-ok-bg: rgba(26, 154, 82, 0.08);
  --ui-dic-solid: rgba(0, 0, 0, 0.88);
  --ui-dic-solid-hover: rgba(0, 0, 0, 0.8);
  --ui-dic-on-solid: #fcfcfc;
  /* Strokes are shadows: a 0.5px hairline plus a soft lift */
  --ui-dic-shadow: 0 0 0 0.5px rgba(0, 0, 0, 0.06), 0 1px 1px -1px rgba(0, 0, 0, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  --ui-dic-shadow-hover: 0 0 0 0.5px rgba(0, 0, 0, 0.1), 0 1px 1px -1px rgba(0, 0, 0, 0.12), 0 1px 3px 0 rgba(0, 0, 0, 0.07);
  --ui-dic-focus: rgba(0, 96, 226, 0.35);
  --ui-dic-ease: cubic-bezier(0.22, 1, 0.36, 1);
  box-sizing: border-box;
  container-type: inline-size;
  width: 100%;
  max-width: 440px;
  overflow: hidden;
  /* One white card – the edge is a shadow, not a border */
  border-radius: 10px;
  background: var(--ui-dic-card);
  box-shadow: var(--ui-dic-shadow);
  font-family: "Inter", "InterVariable", ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  font-size: 13px;
  font-weight: 400;
  line-height: 20px;
  color: var(--ui-dic-ink);
  text-align: left;
  -webkit-font-smoothing: antialiased;
}
.ui-dic *, .ui-dic *::before, .ui-dic *::after { box-sizing: border-box; }
:where(.ui-dic) button { margin: 0; padding: 0; border: 0; background: none; font: inherit; color: inherit; cursor: pointer; -webkit-tap-highlight-color: transparent; }
:where(.ui-dic) svg { display: block; flex: none; }
.ui-dic button:focus-visible { outline: 2px solid var(--ui-dic-focus); outline-offset: 1px; }

.ui-dic__glyph { width: 16px; height: 16px; }

/* The heading row at the top of the card: an amber mark while something waits, a green one when all is done */
.ui-dic__head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 16px;
  border-bottom: 0.5px solid var(--ui-dic-line);
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  color: var(--ui-dic-ink);
}
.ui-dic__head .ui-dic__glyph { color: var(--ui-dic-alert); animation: ui-dic-pop 360ms var(--ui-dic-ease) both; }
.ui-dic[data-clear] .ui-dic__head .ui-dic__glyph { color: var(--ui-dic-ok); }
.ui-dic__head span { animation: ui-dic-in 320ms var(--ui-dic-ease) both; }

/* Rows fold away with a height transition once answered */
.ui-dic__row {
  display: grid;
  grid-template-rows: 1fr;
  transition: grid-template-rows 460ms var(--ui-dic-ease), opacity 300ms ease;
}
.ui-dic__row[data-gone] { grid-template-rows: 0fr; opacity: 0; }
.ui-dic__row-inner { min-height: 0; overflow: hidden; }
.ui-dic__row + .ui-dic__row .ui-dic__item { border-top: 0.5px solid var(--ui-dic-line); }
.ui-dic__row[data-gone] + .ui-dic__row .ui-dic__item { border-top-color: transparent; }

.ui-dic__item { padding: 14px 16px 16px; }
.ui-dic__item:not([data-open]) { padding: 0; }
.ui-dic__title {
  display: block;
  width: 100%;
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  text-align: start;
  color: var(--ui-dic-strong);
  overflow-wrap: anywhere;
}
/* A folded decision: one muted line that opens on click */
:where(.ui-dic) .ui-dic__title--folded {
  /* Still one line however long the title – it opens in full on click */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  padding: 14px 16px;
  font-weight: 400;
  color: var(--ui-dic-muted);
  transition: color 140ms ease, background-color 140ms ease;
}
.ui-dic__title--folded:hover { background: var(--ui-dic-wash); color: var(--ui-dic-ink); }
.ui-dic button.ui-dic__title--folded:focus-visible { outline-offset: -2px; }

.ui-dic__body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 420ms var(--ui-dic-ease);
}
.ui-dic__body[data-open] { grid-template-rows: 1fr; }
/* Room around the buttons so the clipping for the fold never cuts their focus ring and shadow */
.ui-dic__body-inner {
  min-height: 0;
  overflow: hidden;
  margin: 0 -4px -4px;
  padding: 0 4px 4px;
}
.ui-dic__detail {
  margin: 2px 0 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--ui-dic-text);
  white-space: pre-line;
  overflow-wrap: anywhere;
}
.ui-dic__actions {
  display: flex;
  align-items: center;
  /* Long or translated answers go on a second line instead of out of the card */
  flex-wrap: wrap;
  gap: 8px;
  min-height: 28px;
  margin-top: 12px;
}

/* Secondary: white with the card's hairline shadow */
.ui-dic__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  min-height: 28px;
  padding: 4px 10px;
  border-radius: 6px;
  text-align: center;
  overflow-wrap: anywhere;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  transition: background-color 140ms ease, box-shadow 140ms ease, transform 160ms var(--ui-dic-ease);
}
:where(.ui-dic) .ui-dic__btn { background: var(--ui-dic-card); box-shadow: var(--ui-dic-shadow); color: var(--ui-dic-strong); }
.ui-dic__btn:hover { box-shadow: var(--ui-dic-shadow-hover); }
.ui-dic__btn:active { transform: scale(0.97); }
/* Primary: a dark chip */
:where(.ui-dic) .ui-dic__btn--primary { background: var(--ui-dic-solid); box-shadow: none; color: var(--ui-dic-on-solid); }
.ui-dic__btn--primary:hover { background: var(--ui-dic-solid-hover); box-shadow: none; }

/* The answer: a minimal green fill in the place of the buttons */
.ui-dic__done {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  min-height: 28px;
  padding: 4px 10px 4px 8px;
  border-radius: 6px;
  background: var(--ui-dic-ok-bg);
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  color: var(--ui-dic-ok);
  overflow-wrap: anywhere;
  animation: ui-dic-in 360ms var(--ui-dic-ease);
}
.ui-dic__done > span { min-width: 0; }

.ui-dic__empty {
  display: none;
  margin: 0;
  padding: 14px 16px;
  font-size: 13px;
  line-height: 20px;
  color: var(--ui-dic-muted);
}
.ui-dic[data-clear] .ui-dic__empty { display: block; animation: ui-dic-in 420ms var(--ui-dic-ease) 300ms both; }

/* PocketWorld keeps the adopted decision behavior in a continuous task list. */
.ui-dic[data-compact] {
  max-width: none;
  overflow: visible;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  --ui-dic-alert: var(--ui-dic-text);
  --ui-dic-ok: var(--ui-dic-text);
}
.ui-dic[data-compact] .ui-dic__head {
  height: 32px;
  padding: 0;
  border: 0;
  font-size: 12px;
  color: var(--ui-dic-text);
}
.ui-dic[data-compact] .ui-dic__head > .ui-dic__glyph { display: none; }
.ui-dic__count { margin-left: 8px; color: var(--ui-dic-muted); font-variant-numeric: tabular-nums; }
.ui-dic__compact-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 0 4px;
  min-height: 64px;
  padding: 8px 0;
}
.ui-dic__compact-copy { min-width: 0; }
.ui-dic[data-compact] .ui-dic__title {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: normal;
  overflow-wrap: anywhere;
  font-size: 15px;
  font-weight: 500;
  line-height: 22px;
}
.ui-dic[data-compact] .ui-dic__detail { margin: 2px 0 0; font-size: 12px; line-height: 18px; }
.ui-dic__compact-actions { display: flex; align-items: center; gap: 0; }
.ui-dic__compact-actions button { min-width: 48px; min-height: 48px; }
.ui-dic__compact-actions .ui-dic__next { min-height: 48px; padding: 0 8px; font-size: 13px; white-space: nowrap; }
.ui-dic__compact-done { display: inline-flex; align-items: center; gap: 6px; color: var(--ui-dic-text); }
.ui-dic__compact-error { grid-column: 1 / -1; margin: 4px 0; font-size: 13px; color: var(--ui-dic-ink); }
.ui-dic[data-compact] .ui-dic__empty { padding: 8px 0; }

@keyframes ui-dic-in {
  from { opacity: 0; transform: translateY(3px); }
}
@keyframes ui-dic-pop {
  from { opacity: 0; transform: scale(0.6); }
}

/* Narrow cards (phones) */
@container (max-width: 340px) {
  .ui-dic__head { padding: 0 14px; }
  .ui-dic__item { padding: 12px 14px 14px; }
  :where(.ui-dic) .ui-dic__title--folded { padding: 14px 14px; }
  .ui-dic__empty { padding: 14px; }
}
/* Touch screens: bigger tap targets */
@media (pointer: coarse) {
  .ui-dic__btn, .ui-dic__done { min-height: 36px; padding: 8px 14px; }
  .ui-dic__done { padding: 8px 14px 8px 12px; }
  .ui-dic__actions { min-height: 36px; }
  :where(.ui-dic) .ui-dic__title--folded { padding-top: 14px; padding-bottom: 14px; }
}

@media (prefers-reduced-motion: reduce) {
  .ui-dic__row, .ui-dic__body { transition: none; }
  .ui-dic__done, .ui-dic__empty, .ui-dic__head .ui-dic__glyph, .ui-dic__head span { animation: none !important; }
}

/* Dark mode: follows a .dark class or data-theme="dark" on any ancestor, e.g. <html> */
:where(.dark, [data-theme="dark"]) .ui-dic {
  --ui-dic-card: #1c1c1e;
  --ui-dic-strong: #ffffff;
  --ui-dic-ink: rgba(255, 255, 255, 0.9);
  --ui-dic-text: rgba(255, 255, 255, 0.62);
  --ui-dic-muted: rgba(255, 255, 255, 0.46);
  --ui-dic-faint: rgba(255, 255, 255, 0.3);
  --ui-dic-line: rgba(255, 255, 255, 0.08);
  --ui-dic-wash: rgba(255, 255, 255, 0.04);
  --ui-dic-alert: #f0b232;
  --ui-dic-ok: #3ec27a;
  --ui-dic-ok-bg: rgba(26, 154, 82, 0.14);
  /* The primary inverts: a light chip with dark text */
  --ui-dic-solid: rgba(255, 255, 255, 0.9);
  --ui-dic-solid-hover: rgba(255, 255, 255, 0.82);
  --ui-dic-on-solid: #161618;
  --ui-dic-shadow: 0 0 0 0.5px rgba(255, 255, 255, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.4);
  --ui-dic-shadow-hover: 0 0 0 0.5px rgba(255, 255, 255, 0.16), 0 1px 2px 0 rgba(0, 0, 0, 0.4);
  --ui-dic-focus: rgba(77, 141, 255, 0.5);
}
`;

