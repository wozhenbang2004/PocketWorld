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
