# Phone live viewer — implementation and verification

These captures use the **production PocketWorld React bundle with an isolated synthetic native host**. Phone pixels are generated, visibly labelled test frames. They demonstrate Web UI and protocol behavior, not Android capture, physical-device frame rate or device acceptance.

Implementation/build entry: [web-ui README](../../web-ui/README.md). Third-party copyright and license text is consolidated in [THIRD-PARTY-NOTICES.md](../../web-ui/THIRD-PARTY-NOTICES.md).

## Verification checkpoint

| Check | Result | Scope |
| --- | --- | --- |
| Earlier full Web suite | **518 / 518 passed** | Integration checkpoint before the final startup-handling change; the full suite was not repeated after that change. |
| Final focused phone protocol tests | **13 / 13 passed** | Includes same-handle startup recovery and the previous lifecycle/identity regressions. |
| Final production build | **Passed** | 180 locally bundled files verified. |
| Final production-browser journeys | **8 / 8 passed**, zero page errors | See the copied, unmodified [results.json](results.json). |
| Final native feature tests | **23 / 23 passed** | Five actual test classes, no failures/errors/skips; see [native-validation.json](native-validation.json). |
| APK / assembleDebug | **Passed** | Original runtime, helper, Web, CMake, signing and packaging gates retained; see [apk-verification.json](apk-verification.json). |
| Physical Android device | **Not verified** | The synthetic host and screenshots do not establish real device operation. |

The final journeys cover startup recovery; one serial frame request; shared card/sheet feed; exact-task pause/resume and back; task switching with a late old frame; hidden-document cleanup/reopen; substituted-display rejection and retained-frame labelling; desktop/nested workspace return; and end-of-task cleanup. The mobile sheet also checks that the full image and caption fit inside their figure, with scrolling owned by the sheet body.

The startup trace uses one `viewer-1` handle and one open: cursor `-1` → `phone_viewer_starting`, cursor `-1` → `pending`, cursor `0` → `ready`. It recovers without an error surface or a manual retry.

The native feature group covers 8 App/SQL cases, 8 viewer lifecycle cases, 2 real-controller window cases, 2 helper bitmap cases and 3 authenticated socket cases. The final test runtime matched all 8,880 compiled classes byte-for-byte. Existing host groups and the old gesture/OCR test files remain unchanged. Earlier broader exploratory runs exposed failures in those older fixtures; temporary fixture repairs were subsequently restored, and no clean-base control or full-suite success is claimed. The final evidence records those runs and an executor-interrupted attempt separately from the successful 23-case gate.

## Packaged development APK

The built package is `dev.pocketengine`, version `0.3.0-dev` / code `3`, for `arm64-v8a`, with a verified v2 signature. Its 119,782,914 bytes have SHA-256 `8ad77c4a43bf07054e7c99420480109f75f3aad352702a640a53ddb7f3e239fd`. All 181 packaged Web files (180 resources plus build identity), the helper JAR and native catalog payloads match the verified build inputs. The 16 recorded production/build/Web inputs remained unchanged through final native validation. The APK is a development validation build; physical installation and device acceptance have not been performed.

## Final production bundle

After the documentation/notice cleanup, the package was rebuilt with exactly three metadata entries changed: build identity, third-party notices and the dependency-license index. All DEX, native, helper, JS and CSS entries are byte-identical to the tested package. The same behavioral results therefore apply; they were not reported as a second test run. Reference-only source archives and original reference images are excluded from this product change.

| File | SHA-256 |
| --- | --- |
| `assets/index-Cp4HYogZ.js` | `8b460ee7bcc326f47aa0847ee00b9d540ea95e08fd925ece9b9f6d2fffc35069` |
| `assets/index-BFW5RBsm.css` | `b8cb2b60f6c678a8912f5d7ce4d67b46f3ae27987a3dd0b2832a1bcd809daff7` |

The build records source base `2d978f23e99e8396c87c93e8679f75e3db09ae73` plus the local viewer changes; the asset hashes identify the actual tested output. Existing build notices include the large-chunk advisory and license collector `missing: 1, errors: 0`; no dependency was added for this viewer.

Commands from the repository root:

```bash
npm run build --prefix web-ui
node --experimental-strip-types --test web-ui/tests/phone-viewer-protocol.test.ts
node web-ui/tests/phone-viewer-ui.mjs
python3 tools/host-tests.py --group phone-live-preview
./gradlew :app:assembleDebug
```

The browser journey requires the installed Playwright Chromium; the native runner requires the project's JDK/Android SDK prerequisites. This validation used a task-local Chromium/toolchain and the runner's existing offline Robolectric dependency option, with the exact SDK JAR checksum verified.

## Selected final screenshots

Copied byte-for-byte from `app/build/reports/phone-viewer-web/` after the final production journey run:

- [Mobile card, 390 × 844](phone-viewer-card-390.png)
- [Mobile workspace, 390 × 844](phone-viewer-sheet-390.png)
- [Desktop workspace, 1280 × 900](phone-viewer-sheet-1280.png)

Only these three representative screenshots are retained here. The original result receipt also lists loading and stale-state captures produced in the build report directory; those two images are not duplicated in this handoff.
