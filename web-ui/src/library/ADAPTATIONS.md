# Selected library ports — controlled presentation API

This directory contains **20 selected component groups**, PW-01–PW-14 and PW-37–PW-42.
An exported adapter is not proof of application wiring, native bridge correctness, visual acceptance,
or device interaction. Those remain separate rows in the project's integration evidence.

## Source identity

Unmodified originals are retained in `upstream/`. Demonstration source is stored with a
`.source.txt` suffix so fixture applications are not compiled into the product. `PROVENANCE.json`
records SHA-256 for each original. The following distinctions are deliberate:

- **GrokBot**: actual `grokbot-web` commit `616c1dd451f105a10a903e01eed8f82370968681`,
  BSD-3-Clause, itself a declared port of `nasawz/GrokBot`. Both BSD notices are retained.
  It is a selected open source route; it is not claimed to be the exact myLeaf video avatar.
- **Affect official free controls**: actual upstream commit
  `37b91fa302dea59e28ce1eb2ba7acd7ce23296be`, MIT. Original MIT notice is retained.
- **Affect library v3 controls**: saved independent implementation
  `0e69029b0f617de3e4d40d3241dd9cdd1d50abc4`. These are not official Affect Pro source.
- **Nick execution**: the existing library's video-reference reproduction, not the video author's code.
- **Josh**: actual tutorial source and the specifically approved neutral adaptation. No independent
  SPDX license is invented. The tutorial's source and attribution are preserved.
- **Sonner**: actual package `2.0.8`, fixed source commit
  `8e4662b39255120b62138312058f5d77c0139a5e`, MIT, consumed through its published API.
- **BoardUI progress**: official free registry snapshot from 2026-11-01,
  `https://www.boardui.com/r/progress.json`. Its 7,732 original bytes match the saved registry
  member, SHA-256 `ff4ade7942d51231568b750637f90a4e7230b92c5657346cc0673448e3c21c32`.
- **Message format compatibility**: actual `react-markdown@10.1.0`
  (`44d2e4a44b37461ab7778d6870c1a9eb36393ad2`) and `remark-gfm@4.0.1`
  (`109972e8a773bf5dac1d6d2da0776557f36971aa`), both MIT, consumed through their
  published APIs. These repair existing Markdown message rendering inside the selected source surfaces.

## Actual adaptations

| PW | Adapter | Retained source qualities | Product-state change |
| --- | --- | --- | --- |
| 01 | `identity.tsx` + unchanged Grok TS engine/React adapter | Original eye geometry, springs, blink, visibility and reduced-motion lifecycle | Stable Agent ID/silhouette, all six theme channels neutral, real status mapping; compact idle instances do not continuously change expressions |
| 02 | `composer.tsx::ChatComposer` | Source composer surface, file chips, editor/tool row structure | Fully controlled draft and callbacks, IME composition respected, no fake send/approval/voice text; send and stop remain separate actions |
| 03 | `composer.tsx::ModelPicker` | Actual model menu/name/description structure and arrow-key behavior | Real model options; sample quality/speed/cost bars, estimates and unsupported toggles removed |
| 04 | `composer.tsx::AgentSteering` | Plan, queued note rows, applied-note relationship, input structure | No automatic consumption; stable input IDs and original step references provided by host; action callbacks do not mark adopted locally |
| 05 | `composer.tsx::AIAnswer` | Answer body, sources and local action structure | Real streamed content; no character ticker, sample research, fabricated duration or completion |
| 06 | `message.tsx` + `adapted/message-actions-clean.tsx` | Actual official action bar, version motion and copy feedback | Copy waits for a successful clipboard/host callback; speech is controlled by host, without estimated playback; unavailable actions are omitted |
| 07 | `context.tsx::CitedAnswer` | Source tabs, numbered references, selected passage and source relation | Real source ID/version/excerpt; source opening is a host callback, not a sample link or current-page substitution |
| 08 | `message.tsx` + `adapted/tool-call-card-clean.tsx` | Actual official disclosure, JSON highlighting, cap/show-all, copy, status motion | Returned/unknown/waiting/approval/stopped remain distinct. Running time requires an actual recorded start. Terminal duration requires a fact; real zero remains zero |
| 09 | `execution-stepper.js` + `execution.tsx` | Existing Web Component's DOM builder, CSS disclosure and measured bent spine | Demo steps, play/seek clock and auto-completion removed. Status/duration/children are controlled; manual disclosure retained by ID; pass task/run `scope` to reset across owners |
| 10 | `agents.tsx::AgentTree` + `adapted/agent-motion.tsx` / `agent-layout.css` | Actual source tree nodes, measured connectors, complete fidelity link styling and FLIP | Real roots/status; inactive branches initially fold on phone. No sample agents, accumulated pretend cost, fake approval or retry |
| 11 | `agents.tsx::AgentHandoff` | Source agent/material/route cards | Real agents, materials and reasons; measured route endpoints; no sample payload or ACK-to-complete shortcut |
| 12 | `agents.tsx::AgentFindings` | Member filters, finding feed and expanded evidence | Counts/progress only from facts. Review is a host action and never toggles a fake reviewed state |
| 13 | `context.tsx::MemoryPanel` | Source category/search/edit/detail structure | Records and revisions are host-owned. Save/create/forget/source are callbacks; no fixture mutation, fake save, fake delete or synthetic Undo |
| 14 | `context.tsx::ContextWindow` | Source compact cells, totals and segment disclosure | Ratios only when real used/total are supplied. No sample model capacity, artificial compaction or local new-chat completion |
| 37 | `press.tsx` | Actual approved shadow/base/edge/front/material stack and 78/210ms curves | Per-instance pointer/keyboard/cancel cleanup; unscaled label; compact 2px travel / 3px normal travel; solid neutral material |
| 38 | `feedback.tsx::ThinkingLoader` + official source adapter | Width fitting, spinner and rolling/blurred label | Single host-selected phase and done label; no automatic stage advancement or view-start duration |
| 39 | `feedback.tsx::ShimmerLoader` + official source | Actual source skeleton and shimmer text | Controlled loading only; use for known read-only structure, not executing owners or editable content |
| 40 | `feedback.tsx::Toaster` / actual Sonner package | Original stack, expansion, focus, swipe and interruption behavior | Neutral surface, above-composer default offset. Persistent task/error/receipt data must remain in its source object |
| 41 | `adapted/boardui-progress.tsx` | Actual React Aria/DOM/SVG, translate fill, dashoffset arc and indeterminate sweep | Imports routed to the shared actual BoardUI Label/Spinner/Direction/cx helpers; caller supplies real value/total and accessible label |
| 42 | `feedback.tsx::BlurCrossfade` | Saved source height measurement and outgoing/incoming content choreography | Host identity replaces sample release index. Only pure presentation belongs inside; editors, WebViews and executing owners stay outside |

`adapted/affect-source.css` is derived from the saved complete style sources. Chromatic hex/RGB
values are mapped to neutral luminance while alpha and relationships are preserved. Product
host overrides in `../library.css` remove demo-stage dimensions and restore normal phone type,
44px targets and role-based shadows. Source imagery/media is not shipped in this product folder.

`MarkdownBody.tsx` delegates parsing to react-markdown and remark-gfm. Its citation extension
only visits already parsed text nodes and links markers to supplied source IDs; it skips code
and existing links. Tables and code scroll locally; code copy acknowledges only actual success.
GFM task boxes are read-only document content. Raw HTML is skipped. The default URL sanitizer
is preserved and narrowed to HTTP(S) and fragments, with an optional host opening callback.
Remote images do not fetch automatically; an explicit resolver may provide a trusted local asset.

The source archive now contains 53 registered original files, including Sonner's six source
files, package and MIT notice, the exact BoardUI registry JSON, and the complete source FLIP helper.
The complete library `components-b/fidelity.css` is also retained, SHA-256
`287a7b43dfa919fde2167706b49c340d103f8e0d50a48e9a26b733f83d0d8b7a`.
All 53 SHA-256 records matched at this handoff. Original files and adapted product leaves remain
separate; changes to controlled behavior belong only in the latter.

CI19 formal Web screenshots with isolated test fixtures (not Android/device evidence) exposed
missing fidelity rules: the SVG occupied an in-flow 300×150 default box and its paths inherited
the SVG default fill. `agent-layout.css` restores the source absolute positioning, no-fill strokes,
draw/beam timing and closing choreography. Handoff keeps measured endpoints and uses a reserved
24px outside rail when source or destination cards stack; single-row layouts retain the source
rounded wire-band route. No SVG is hidden to disguise the defect.

The same fixture review found an unknown-duration unit leaking because authored `.time` display
overrode the HTML hidden state. The adapter now explicitly hides the entire time element when
unknown. ToolCall's narrow header reserves a separate second line for factual status/time;
expanding its existing disclosure reveals full tool/summary text rather than dropping metadata.
These changes still require the next formal Web screenshot comparison; they do not establish
Android or actual user-task acceptance.

## Verification boundaries

- `node --check execution-stepper.js`: passed.
- Controlled execution input checks: returned stays returned, unknown stays unknown, zero duration
  stays zero, duplicate IDs fail, approval alias stays approval, no `play`/`seek` timeline API.
  This used a minimal host stub and does not claim DOM rendering.
- Relative module/file paths: checked and present at handoff.
- TypeScript build, real DOM visuals, pointer/keyboard journeys, Android WebView, performance,
  application route wiring and end-to-end owner/receipt contracts: await root's CI/device evidence.


## Composer send availability (mobile composition refinement)

`composer.tsx` retains the saved Affect Composer DOM, textarea auto-sizing, voice state,
attachments, toolbar and controlled input owner/epoch behavior. Optional `sendDisabled`
and `sendLabel` props default to the original behavior. The existing `canSend` guard now
protects both its source PressButton and textarea Enter submission; disabling send does
not disable editing or clear the controlled draft. This allows the product to retain a
first draft while the user connects a model. No timer, synthetic submission or new
composer implementation is introduced.
