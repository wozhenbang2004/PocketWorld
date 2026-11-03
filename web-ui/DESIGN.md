# PocketWorld: continuous work and review

## Scope and user direction · 2026-11-03

The user rejected the current experience: “pocketworld 的 uiux 到底该怎么改进？现在好恶心”, then authorized “我需要你直接提 pr 大胆重构 uiux”. This change is a proposed implementation for review, not a claim of aesthetic approval.

After the initial PR was created, the user explicitly corrected the scope: “我要的是彻底重做 uiux 兄弟现在的太恶心了而且最好结合我们审美库”. The initial CI63 composition is therefore an intermediate baseline, not the accepted result. Scope now includes the complete root-page composition and the major secondary pages, using the approved component library throughout.

## Complete composition revision

- **Stable roots:** the approved Ovio LiquidTabs minimal now owns three actual panels: 工作 / 任务 / 项目. Its liquid indicator is kept intact; the panel is the real page, not an empty navigation placeholder. The bar is visually beneath the reading surface. WorkSidebar is reserved for recent work, teams, memory, history and settings.
- **Work:** one current-work identity above a continuous conversation, with a separate grounded input surface. Task-related actions belong to their messages. Android's actual IME visibility hides the root switcher while typing; focus or viewport guesses are not keyboard evidence.
- **Tasks:** the approved Affect DecisionInbox gives waiting items explicit next steps before ongoing and finished work. Its primary entry opens the decision directly; its secondary entry retains the general overview. Filters and history remain secondary.
- **Review:** one continuous result document, followed by the question and feedback. Related conversation remains available in the approved Accordion, collapsed by default. The task goal stays visible in the sheet header; Vaul preserves foreground/background continuity.
- **Projects:** the current project opens directly into files and paths. The large, often unavailable Work/Preview mode switch is removed; a real preview capability is a contextual action. Affect's original type artwork is reused in file rows; it illustrates file type and never claims to preview file content. Tree/list/grid and all original editing/version identities remain.
- **Models and settings:** the model catalogue is the selection surface; connections are a separate management layer. Settings uses purpose groups and direct common entries. Teams use work/team object views; memory groups actual records by their content layer and reveals their source and ownership in place. The approved source components retain their own input, selection and motion behavior.

The visible hierarchy is a quiet gray application canvas, a white reading surface, and clearly bounded input/decision layers. Ordinary records are not all promoted to floating cards. Frequent touch targets remain 48px; native insets are applied once by the Android host. The source panel's demonstration opacity, font size and spacing are explicitly replaced by application layout values without changing Ovio's indicator implementation.

The starting product UI is `uiux/library-web-20261102` at `748a6bc581e99ecabe4104f6a05a66d217828a16`, integrated with main `3136a5d9e86bf0fd11c305feec748964eaded1ed`. The earlier CI20 composition is rejected historical evidence. Keep the approved component selection and the later file-first project layout; repair the remaining fragmented workflow.

The supplied external review is useful on shared task authority, isolated untrusted web content, and Android input/back integration. Its proposal to rebuild the main UI in Compose is not adopted: the current business interface already uses the approved bundled React components. Android retains execution, storage, permissions, activity lifecycle and the original controller. No new UI framework, remote webpage shell or parallel task store is introduced.

## Design and acceptance

| ID | Decision and implementation | Required observation |
| --- | --- | --- |
| UIX-01 | Persistent Work/Tasks/Projects roots; both the message and priority-task primary action open the current decision directly. The task secondary action opens overview. `App.tsx` retains the real entry layer. | Every root is one tap away. Direct decision → one Back restores its source; overview → decision preserves its own hierarchy. |
| UIX-02 | Results occupy the reading surface before feedback. Overview no longer repeats the same summary as a separate recent-progress block. Project files remain near the top. | On 360/412 widths, actual output is readable before the form; the first project file remains within the existing 240px gate. |
| UIX-03 | A continuous result document leads to its decision. Related conversation is collapsed. Composer, root switcher and modal surfaces retain purposeful elevation; ordinary settings/file rows remain lighter. | The representative short result and decision question are visible together. Related content expands without being deleted; sparse data is not filled with invented cards. |
| UIX-04 | Reading text is 15–16px, headings are 18px, and secondary text remains readable. Long titles wrap where needed. | Long Chinese/model names and keyboard-open layouts preserve the main action. |
| UIX-05 | Solid black/white/gray tokens and the existing icon family continue. Physical press motion is retained. | No green/default native fallback, page-wide decorative blur or new icon family. |
| UIX-06 | `TaskOutcome` composes PW-05 Answer and approved Accordion. Ovio LiquidTabs owns root panels; PW-15 Vaul owns layers, PW-16 Sidebar owns history/management, PW-03 ModelPicker owns choice, PW-25/26 own review/decision, BoardUI owns forms, PW-37 owns primary press behavior. | Original source/provenance remains intact. New composition is product adaptation, not upstream code. |
| UIX-07 | App-owned task opening suppresses external-navigation echo. Other task entries retain navigation. Back consumes the actual top layer. Real host IME visibility controls the bottom switcher. | Android Back, real IME hide/restore, Vaul dismissal and reduced motion need current-build verification. Controlled Web host configuration remains separate evidence. |
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
