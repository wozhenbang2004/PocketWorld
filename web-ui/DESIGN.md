# PocketWorld: continuous work and review

## Scope and user direction · 2026-11-03

The user rejected the current experience: “pocketworld 的 uiux 到底该怎么改进？现在好恶心”, then authorized “我需要你直接提 pr 大胆重构 uiux”. This change is a proposed implementation for review, not a claim of aesthetic approval.

The starting product UI is `uiux/library-web-20261102` at `748a6bc581e99ecabe4104f6a05a66d217828a16`, integrated with main `3136a5d9e86bf0fd11c305feec748964eaded1ed`. The earlier CI20 composition is rejected historical evidence. Keep the approved component selection and the later file-first project layout; repair the remaining fragmented workflow.

The supplied external review is useful on shared task authority, isolated untrusted web content, and Android input/back integration. Its proposal to rebuild the main UI in Compose is not adopted: the current business interface already uses the approved bundled React components. Android retains execution, storage, permissions, activity lifecycle and the original controller. No new UI framework, remote webpage shell or parallel task store is introduced.

## Design and acceptance

| ID | Decision and implementation | Required observation |
| --- | --- | --- |
| UIX-01 | A task-linked answer opens the current decision directly. A task-list entry still opens the overview. `App.tsx` retains the real entry layer. | Message → evidence and decision → one Back returns to the same message and draft. List → overview → decision preserves its own hierarchy. |
| UIX-02 | Results occupy the reading surface before feedback. Overview no longer repeats the same summary as a separate recent-progress block. Project files remain near the top. | On 360/412 widths, actual output is readable before the form; the first project file remains within the existing 240px gate. |
| UIX-03 | White result documents sit on a quiet gray task surface. Composer and modal surfaces retain purposeful elevation; ordinary settings and file rows remain lighter. | Review current screenshots with empty and long content. Sparse data must not be filled with invented cards. |
| UIX-04 | Reading text is 15–16px, headings are 18px, and secondary text remains readable. Long titles wrap where needed. | Long Chinese/model names and keyboard-open layouts preserve the main action. |
| UIX-05 | Solid black/white/gray tokens and the existing icon family continue. Physical press motion is retained. | No green/default native fallback, page-wide decorative blur or new icon family. |
| UIX-06 | `TaskOutcome` composes PW-05 Answer with PW-22 file affordances. PW-15 Vaul owns layers, PW-16 Sidebar owns navigation, PW-03 ModelPicker owns model choice, PW-25 owns decisions, BoardUI owns forms, PW-37 owns primary press behavior. | Original source/provenance remains intact. New composition is product adaptation, not upstream code. |
| UIX-07 | App-owned task opening explicitly suppresses external-navigation echo. Other task entry points retain navigation. Back consumes the real top layer. | Android Back, IME, dismissal and reduced motion need current-build verification. Web evidence remains separate. |
| UIX-08 | Fresh native detail loads before a review handle is issued. Closing/changing the task invalidates late reads. Errors remain visible; acceptance is distinct from completion. | Reject stale digest/handle, unavailable detail and closed-window callbacks; retrying a read cannot replay a task side effect. |
| UIX-09 | Show `review.task.raw.evidence` and summary from the same snapshot as the handle. Same-task chat answers are labelled related conversation because messages lack run identity. | Evidence wins over incidental events. Unread/foreign/older-run bodies cannot become current output. Mark known truncation; infer no files or progress totals. |
| UIX-10 | Frequent controls use 48px touch areas with approved smaller icons; text can reflow. | Check narrow widths, overflow, focus restoration, large system text and actual Android input. Unrun checks remain unverified. |

## Feature coverage

- Work/task review: evidence, direct entry, navigation, feedback, scope and failure recovery.
- Projects: preserve list/tree/grid and editing/diff/history; remove duplicate filename/path and incidental version badges from ordinary rows.
- Models/settings: one current-model focal point, searchable choice, connection management separated from everyday switching, frequent settings reachable from the root.
- Teams/memory: current shared work and remembered content precede management metadata; original action and source/version identities remain authoritative.
- Existing browser, device, runtime, plugin and automation domains remain reachable. This PR does not claim completion of every outstanding domain integration.

## Evidence boundaries

`npm run build --prefix web-ui`, `npm test --prefix web-ui`, the existing `capture-library-web.mjs` journey, APK compilation and Android capture are the evidence sources. Synthetic isolated host data can verify UI behavior but cannot establish real task success. The PR must report actual results, including failures or unavailable device checks. Component-selection approval does not imply approval of this assembled redesign.
