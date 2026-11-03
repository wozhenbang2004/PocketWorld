# PocketWorld library Web UI

The bundled React interface owns the app's business screens. Android retains
task execution, storage, OS permissions, document selection, and the existing
controller's ownership and receipt rules.

The current workflow redesign and its acceptance criteria are in [DESIGN.md](DESIGN.md).

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

`SOURCES.json`, `THIRD-PARTY-NOTICES.md`, and each library's source manifest
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
