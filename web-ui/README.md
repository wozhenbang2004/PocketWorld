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
