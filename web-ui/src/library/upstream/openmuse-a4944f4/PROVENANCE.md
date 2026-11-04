# OpenMuse computer viewing UI — retained source and phone adaptation

## Upstream identity

- Repository: https://github.com/CopilotKit/openmuse
- Fixed commit: `a4944f4c471d3dc93b8bfc060b5da309b5216ab2` (default `main` head read for this adaptation).
- License: MIT, `Copyright (c) 2026 OpenMuse contributors`; the exact original `LICENSE` is retained here.
- `SOURCE-MANIFEST.json` records each preserved file's upstream Git blob identity and local SHA-256. Original files are excluded from the production TypeScript build by the existing `**/upstream/**` rule.

## Original-to-adapted map

| Preserved original | Original scope | PocketWorld adaptation |
| --- | --- | --- |
| `apps/mobile/src/computer.tsx` | `ComputerEntry` and `ComputerSheet` | `phone-viewer.tsx` capsule, selected-task entry and workspace; the native phone has no Browser, Terminal or Files tabs. |
| `apps/mobile/src/desktop-tool-card.tsx` | Desktop title/action/status, grey card and open-workspace control | Phone preview card retaining padding 13, max width 640, 36px icon box, header hierarchy and expansion control. |
| `apps/mobile/src/ui.tsx` | Shared colors, buttons, card and responsive `Sheet` | `phone-viewer.css` ports dimensions and spacing. Phone viewing uses a neutral grey palette; existing Vaul/Radix supply touch, focus, Escape and dismissal. |
| `apps/mobile/src/desktop-stream.tsx` | Stable shared viewer, loading/error and platform branches | A single scoped phone feed is shared by card and sheet. E2B VNC and native external-browser opening are replaced by the existing Android display session's bounded JPEG preview. |
| `apps/mobile/src/computer-workspace.tsx` | Linux workspace status/control region and `ComputerDesktop` | Phone task status, actual pause/resume callbacks and preview; no Linux commands, cloud start/stop, files or unrelated capabilities. |
| `apps/mobile/src/BrowserConsole.web.tsx` | E2B viewer iframe | Preserved as transport provenance only. The phone component renders the exact run's image; it does not claim that JPEG preview is E2B, WebRTC or a VNC implementation. |

The mobile breakpoint remains 600px. Narrow viewports use a bottom sheet with a 34×4px handle, radius 26, bottom safe area and max height 94dvh. Wider viewports use the centered sheet, max width 790, with fade behavior. The original handle is decorative; this adaptation uses the existing Vaul handle for actual drag-to-close. Card and sheet share one connection, and closing viewing does not stop execution.

The phone transport and identity guards in `phone-viewer-protocol.ts` are PocketWorld-specific code: exact task/run/epoch; session/display pinned on first successful response; serial polling at most 5 Hz; document/visibility cleanup; a fresh cursor for every new viewer handle; retained, explicitly marked prior image on interruption. Native `capturedAt` is monotonic uptime, so displayed receipt time comes from the browser's local clock.

## Actual visual reference and evidence limits

`assets/computer.png` is the original upstream image retrieved at the fixed commit and visually inspected for this adaptation. It shows the mobile **Agent computer** sheet, Browser/Files tabs, a Chromium browser card and the Example Domain preview. It is **not an E2B Desktop screenshot**. It confirms the workspace's sheet/header/card/spacing language; current Desktop behavior was read in the preserved actual source files.

The image is reference evidence only. It is not imported by the product, not used as a phone task's frame, and not represented as a PocketWorld device screenshot. Repository source licensing is not asserted as a blanket license to unrelated pictured branding or website content.

The upstream application was not run for this adaptation. Source reading, official-image inspection, local type/unit checks, production-browser journeys and actual Android-device validation are separate evidence levels. Browser tests with a marked fake native host do not prove real device delivery or frame rate.

## Public references

- Computer implementation notes: https://github.com/CopilotKit/openmuse/blob/a4944f4c471d3dc93b8bfc060b5da309b5216ab2/docs/COMPUTER.md
- Official image: https://github.com/CopilotKit/openmuse/blob/a4944f4c471d3dc93b8bfc060b5da309b5216ab2/assets/computer.png
- Demo notes: https://github.com/CopilotKit/openmuse/blob/a4944f4c471d3dc93b8bfc060b5da309b5216ab2/docs/DEMO.md

The September 16 recordings linked by those demo notes show actual Chromium browsing and mail with scripted model responses. They are not proof of an E2B desktop or PocketWorld's native continuous-preview integration.
