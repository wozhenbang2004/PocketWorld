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
