# PocketWorld library Web UI

The bundled React interface owns the app's business screens. Android retains
task execution, storage, OS permissions, document selection, and the existing
controller's ownership and receipt rules.

## Build

Use Node 22.22.0. After the initial dependency lock has been reviewed and retained:

```sh
npm ci --prefix web-ui
npm run build --prefix web-ui
npm test --prefix web-ui
./gradlew :app:assembleDebug
```

The Web build prepares pinned, full official fonts and dependency notices,
checks TypeScript, and bundles the UI into `app/src/main/assets/pocket-ui`.
Android `preBuild` rejects a missing or changed verified bundle. Build output
is generated from source rather than committed as a second source of truth.

`THIRD-PARTY-NOTICES.md` and each library's source manifest
identify upstream code, retained originals, adaptations, and library-authored
reconstructions separately. Retained upstream reference files are excluded
from the application TypeScript roots.

## Host boundary

Only the fixed local asset origin receives `PocketNative`. The versioned bridge
binds commands to one document and the controller's original composer epoch,
task identity, review handle, and file version. A command's accepted receipt is
not a completed task. User projects and external content must use an isolated
view without this bridge.

No production fixture records are seeded. Playwright component evidence uses
test-only host fixtures and is labelled separately from Android WebView captures.
Android capture requires the exact verified APK, supported ARM64 execution, and
an actual WebView provider at least version 111. Unsupported device images fail
instead of changing the APK or weakening the selected components.

## Conversation phone outcomes

Each native phone-result message exposes an immutable `phoneResult` containing
its task/run/epoch, delivery identity, ended status/time and summary limits.
The message shows that ended run even when the task is running again. Its task
link reads the current original task; it does not resume execution or reuse an
old review. Ordinary or imported messages cannot supply this native read target.

A saved task that has no result message is linked from its original conversation.
Native task headers carry the compact `conversationPhoneLink` display association;
detail windows retain the original association. This uses the existing bounded
task window, not a complete conversation task directory. Older tasks remain
available through task-history pagination. No phone-goal chat message is invented.

After building the Web bundle, run the isolated production-page regression:

```sh
node web-ui/tests/phone-handoff-ui.mjs \
  --bundle app/src/main/assets/pocket-ui --output app/build/reports/phone-handoff-web
```

The fixture covers unknown-receipt recovery, explicit rejection, two ended runs,
saved-but-unadmitted creation, and a reopened conversation using a summary header.
It models native responses and does not prove SQLite, Android admission or device
execution. Native tests are registered in the `conversation-phone-handoff` host
group; full Android compilation and a source-matched APK are separate checks.

## Continuous phone viewing

The conversation and original task detail can show one selected background phone
run continuously, in an inline preview or expanded workspace. Card and expanded
workspace share one feed. The owner can select another eligible task, pause or
resume the selected original run, and close viewing without stopping execution
or losing the conversation draft.

This is a bounded JPEG preview of an already attached Android independent
display, not E2B/VNC or video sent to the model. Reads are sequential, scheduled
no faster than every 200ms, with a native admission limit and one in-flight
request. Frames have a maximum long edge of 960px at JPEG quality 65. Actual
device delivery rate depends on capture and transport. The model's existing
observe/action path remains separate; viewing neither creates an observation
reference nor changes its cache, OCR, action geometry or input authority.

`phone.viewer.open`, `phone.viewer.frame` and `phone.viewer.close` bind an opaque
viewer to the exact task/run/epoch and owner navigation window. The first
successful read pins its session/display; later substitutions and regressions
are rejected. The retained native run, canonical stored run and private phone
lease must still match. Local owner/private background tasks and their eligible
team phone runs are supported; shared/remote origins and the main display are
excluded. Selecting a task never follows `activeTaskId` as a fallback.

Display preparation waits automatically on the same viewer. A transient read
failure backs off; a changed run/session/window closes the feed and requires an
explicit retry where appropriate. Hiding the WebView, navigating away or ending
the task releases the viewer. An interrupted or ended task may retain its last
image in memory with an explicit stale/last-frame label. Frames are not written
to storage, the conversation history, command-receipt cache or full state
snapshots. Pausing execution does not by itself stop viewing, including when the
run has released phone input while doing browser work.

After building the production bundle, run the focused browser journey:

```sh
node web-ui/tests/phone-viewer-ui.mjs \
  --bundle app/src/main/assets/pocket-ui --output app/build/reports/phone-viewer-web
```

It uses an isolated native-host fixture and visibly labelled synthetic phone
frames. It checks continuous decoding, shared mobile/desktop viewing, task
switching with a late frame, pause/resume, hidden cleanup, stale-display refusal,
draft/back continuity and ended-run cleanup. Protocol tests are part of
`npm test --prefix web-ui`; the actual native/helper/controller tests are
registered as `phone-live-preview` in `tools/host-tests.json` and run through
`python3 tools/host-tests.py --group phone-live-preview` with the documented
Android/JDK prerequisites. These host checks do not replace physical Android
capture, multi-display isolation, thermal or frame-rate acceptance.

## Reading and team continuity

The message menu opens a frozen, in-memory reading view. Later host snapshots
continue updating the original conversation without moving the reading text or
the background scroll position. Read and select modes keep their own position;
expanded tables return to their parent reader before returning to the original
message. Tables are parsed with their full Markdown context, including quoted
tables and reference links. Table copy uses tab-separated cells; select mode
exposes the original Markdown. Clipboard failure remains visible and retryable.

Team details show member identity, frozen model information and actual current
work before the summary. Details expand by member/work-item ID, including when
the host reorders them. List/detail positions, search and per-run input context
remain local to the workspace. Existing native read leases and review handles
remain responsible for authority; these views grant no new command capability.

After a production Web build, run the focused browser regressions from the
repository root:

```sh
node web-ui/tests/reading-continuity-ui.mjs
node web-ui/tests/team-continuity-ui.mjs
```

They write labelled screenshots and JSON results beside the repository in
`qa-reading/current` and `qa-team`. `READING_UI_EVIDENCE` and the team script's
first positional argument can select another output directory. These tests use
an isolated synthetic host, block external requests and refuse business actions.
They cover reading/selection, copy feedback, nested return paths, draft/scroll
continuity and team identity. They do not prove physical Android selection
handles, the system clipboard, native execution or APK acceptance.

## Reminder workspace

The reminder page uses the original `AutomationSchedule` preview and the
controller's existing automation feature requests. A window-scoped, expiring,
one-use host review binds create, edit, reschedule, pause, enable and delete to
the original rule revision. Previewing or saving never grants phone execution.
Editing preserves the original schedule unless timing changed, and retains
the original pause state, budgets and conditions. Rescheduling creates a new
future rule without replaying its source occurrence. Its frozen input carries
only the original rule ID/revision as a restrictive `rescheduleSource` reference.
The original AutomationBook writer checks that reference under the same lock as
the new-rule write. Missing, changed, invalidated or pending sources are rejected.
Rules with native conversation provenance are currently refused as reschedule
sources, even when a stale Web snapshot omits that provenance. The owner must
make a new explicit request in the original conversation instead. This reference
never supplies provenance or grants authority. Enabling also rejects pending
source checks under the original writer lock; pausing remains allowed.
The Book mints `manualOriginVersion: 1` only on its source-less manual creation
route, never from caller metadata or a native proposal. Edits preserve but never
retrofit that proof. Rescheduling additionally requires this positive stored
fact, so legacy/unknown provenance is refused even when both an origin carrier
and its proposal are missing. Those reminders can be left intact while the
owner explicitly creates a blank new reminder.

The UI waits for the matching feature request's real receipt. An accepted bridge
response is not a save, and an uncertain response is not automatically retried.
Deleting a rule also deletes its delivery receipts in the original AutomationBook;
the confirmation discloses this and offers pausing to preserve those records.

## Account hierarchy and workspace recovery

The account page has one page title, followed by a full-width reading lead and
its primary action. Section descriptions stay below their title. The existing
BoardUI-derived SettingsDialog owns modal focus after mount and restores the
opening control on close; its animation and approved controls remain in place.
The dark close control stays secondary to the login action.

The native workspace entry lives in the existing navigation drawer. A healthy
Web document occupies the full Activity, including system insets; it has no
second native toolbar above its modals. If the packaged entry, critical bundled
script/style, secure bridge, or renderer is unavailable, the host shows a
separate recovery panel with reopen and native-workspace entries. Both entries
retain startup and pending-operation guards; they do not repair data or replay
tasks. Optional font/media failure does not discard a usable workspace.

This implements the user's 2026-11-05 UI repair request, including “修完之后就赶紧去合并提交，
然后呢你再让这个本地测试的这个去拉代码” (parent delegation; exact room-message ID unavailable).
It reuses the adopted BoardUI/Vaul/Josh components and DP-05/06/18/27 principles;
it adds no personal aesthetic-library records or new dependencies.

Run the focused layout/focus/navigation checks with
node web-ui/tests/account-hierarchy-ui.mjs from the repository root (Edge is
required). The fixture uses production React components with a synthetic host;
it does not prove Android, OAuth, IME or device acceptance. For the existing
account/model regression runner, UI_BROWSER_CHANNEL=msedge selects Edge and
UI_NO_SCREENSHOTS=1 disables persistent screenshots.

## File comparison fragments

The adopted Affect PW-24 surface keeps exact original-version and current-draft
text in its two existing columns. Unchanged context stays ordinary text;
only removed fragments use del and added fragments use ins. Empty-file labels
are presentation text, never a removed or added fragment. Whitespace-only
changes remain exact and receive a short notice.

The pinned jsdiff 9.0.0 algorithm is adapted behind this existing surface.
Character search has a 40ms search budget and edit-distance limit; larger
comparisons use a bounded line comparison with an explicit precision notice.
If token-allocation or search limits are reached, both complete source texts
remain visible without invented change marks. This affects display only:
file content, original revision, save, adoption and host commands are unchanged.

This completes the parent's 2026-11-05 follow-up request to fix the confirmed
whole-file-highlighting defect under the user's existing review/fix/merge
authorization. No new aesthetic-library record or design component is added.
Run node web-ui/tests/file-diff-ui.mjs for the headless Edge production-component
fixture and npm test --prefix web-ui for exact-text and fallback checks.
The fixture uses a synthetic file session; Android16/WebView/IME acceptance
belongs to the existing device-testing owner.
