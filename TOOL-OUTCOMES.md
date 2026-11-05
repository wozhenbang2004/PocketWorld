# Tool outcome and completion contract

PocketWorld treats a tool's **outcome** and **completion** as separate native facts.

- `success` means the native request or operation produced a valid positive receipt. It does not by itself mean the work is finished.
- `completion=pending` covers accepted, queued, dispatched, starting, running and stopping receipts. These stay visible as in-flight work.
- `completion=terminal` is reserved for explicit terminal success.
- correctable failures may be revised, but an original side effect is never replayed automatically.
- owner action and effect-unknown states keep their existing stop/reconciliation barriers.

The native receipt remains authoritative. Nested provider data and a caller-supplied
`toolOutcome` cannot grant success.

## Cancellation boundary

A real tool receipt is journaled before post-tool extension callbacks. If cancellation
arrives after that receipt, the receipt remains preserved but `afterTool`, remaining
tool calls and the next model request must not run.

## SQLite recovery

Android `rawQuery(String[])` binds selection arguments as text. The receipt-size
threshold is explicitly cast to INTEGER before deciding whether an old receipt can be
read inline. Oversized receipts stay on bounded chunk parsing. The v2 migration marker
forces rows previously checked under the old comparison to be reconsidered.

## Focused checks

```sh
npm run typecheck
node --experimental-strip-types --test web-ui/tests/task-presentation.test.ts
```

The Android host/SQLite suite and physical-device acceptance remain separate release
gates; this document does not claim they ran in GitHub Actions.

## Presentation regression checkpoint (2026-11-05)

A native `project_running` or `project_stopping` record does not hide a canonical
failure or owner-intervention receipt. Those outcomes take precedence over the running
label. An operation marked `read_limited`, `readLimited=true` or `bodyAvailable=false`
must not expose embedded arguments, receipts, output notes or an output-read locator.
Unread event rows likewise cannot expose an embedded event body.

The published base `0a3f5fcc74cd5a6cabb53d82e71a981fb96de38a` was independently
materialized for these checks. Four new native presentation regressions failed on
that base and passed after the fix; the 17 existing ToolOutcome tests and 22 existing
presentation tests also passed (43 executed tests total). Two output-capture-dependent
existing tests were explicitly not run. The actual Java classifier and Kotlin presentation sources were
compiled. The unrelated output-capture metadata interface was a compile-only boundary
whose invocation count was asserted to be zero; output capture, Android and SQLite
were not verified by this scoped harness.

The existing Web presentation suite passed 15 tests on the base. Adding regressions
for canonical and legacy failure/owner-intervention precedence reproduced the
misclassification, then all 18 tests passed on the corrected source, including unread event pages/snapshots. This is Node execution of presentation
functions, not a rendered WebView or a full frontend typecheck.

The older continuation workspace's 32-file patch and broader test counts are not
claimed to match this reconstructed PR. Full Runner, pinned Android host/SQLite,
APK and device acceptance remain outstanding; keep the PR draft and do not trigger CI.

