# Login → model → message → tools: reliability contract

This is the shared maintenance contract for the native execution path and its diagnostics.
It does not grant execution authority and must not become a second task or account store.

## Boundaries that must stay separate

| Boundary | Authority | Required behavior |
| --- | --- | --- |
| Owner account mutation | `AccountBook` + exact reviewed version | A stale settings review cannot disable, log out, or replace a newer account version. |
| Credential renewal | `AccountGateway` | A temporary refresh failure/cooldown does not manufacture a new authorization. Verified renewal of the same issuer, subject and client may continue that authorization. |
| Inference preparation | `ModelGateway.prepareAccount` | Renew credentials and stale catalog evidence explicitly, before context/image budgeting. Honor cancellation; do not perform network I/O in facts/readiness getters or under task-store read locks. |
| Admitted inference | Native account lease + original `RunControl` | A later ordinary token rotation does not invalidate a response already dispatched. Logout, disable, reauthorization, source withdrawal and cancellation still fence it. Preserve the version actually used by that request. |
| Message admission | Original `TaskStore` mailbox and run identity | Accepted input is durable evidence, not proof that an executor started. A rejected executor or uncertain receipt must remain queryable; do not silently replay the message. |
| Tool outcome | Native capability/source checks | A deterministic, source-checked read-range mistake returns a failed tool receipt that the model can correct. Cancellation, revoked access, changing source bytes and unknown I/O remain hard stops. |
| Run completion | Original task/run/epoch | Cleanup and persistence refer to the original run. Logging or a model answer cannot establish a committed terminal state. |

The private account `authorizationEpoch` is the first version for which the current authorization is proven. Token publication can advance `version` without advancing this epoch. Owner mutations still require exact versions. Existing stores without the field migrate conservatively to their current version; unknown earlier authorization is never reconstructed from chat history.

Run-local account bindings accept only the same account/model and native-proven authorization continuity. Opaque provider history can refer only to versions that this run actually admitted. Preparing a later round must not rewrite the provenance of an earlier response.

Frozen purpose plans retain their original candidates. Preparing a top-level MAIN configuration does not refresh the SUMMARY candidate inside that plan. Prepare the actual candidate before its exact-version budget admission, and prepare SUMMARY before cold-history reconstruction reads its budget. Metadata preparation must not establish the first execution binding; preparation and the resulting summary request share one absolute deadline.

## Read receipts and independent observations

`ProjectFileQueries.readFileOutcome` is the typed boundary for recoverable reads. Current stable range codes are:

- `project_read_invalid_range`: a schema-valid offset/line is beyond the actual source.
- `project_read_split_unicode`: the selected range splits a UTF-8 character or UTF-16 surrogate pair.
- `project_read_page_too_small`: the page cannot hold one complete Unicode character.

Before returning one of these receipts, recheck the original source hash and cancellation. Do not replace all `IllegalArgumentException`, `IOException` or `SecurityException` failures with model-retry hints.

The Runner tracks dependent sequence continuation separately from delivery of independent observations. A missing text file in an ordinary independent tool batch must not discard a successful image read. Native plans/steps, pause, steer, changed sources, lost authority and exhausted image/request budgets keep their original stop rules. An image receipt alone is insufficient: the next actual model request must contain the image and its matching tool receipt.

## Operational logs

`RuntimeDiagnostics` records bounded metadata before and after the task database becomes ready. Existing task events, usage records and detailed native traces retain their own authority; runtime logs supplement them with startup/login/admission failures and swallowed persistence errors.

Stages: `bootstrap`, `login`, `account_refresh`, `model_catalog`, `model_prepare`, `message_admission`, `model_request`, `tool_execution`, `run_settlement`.

Each span has a session ID and operation ID, start/terminal result, stable code and elapsed milliseconds. A bound `RunControl` supplies task ID, run ID and epoch. UUID task/run identities remain readable; other identifiers and account/tool references are hashed. Failures retain exception types and bounded application code locations, never exception messages. Emit phase boundaries, not every streaming token or polling iteration.

Never pass prompts, message bodies, tool arguments/results, file/image contents, credentials, OAuth codes, callback URLs or arbitrary provider error bodies. Use a constant stable code and the original exception object. The logger deliberately does not serialize `Throwable.getMessage()` or full stack traces.

Retrieval:

1. In model settings, open **诊断与运行日志**, choose **读取最近日志**, then **复制日志**. Reading logs does not run a model probe or upload anything. The separate capability probe still requires its existing explicit review.
2. For startup failures or development, use `adb logcat -v threadtime -s PocketWorld:I`.
3. Debug builds also permit `adb exec-out run-as dev.pocketengine cat files/diagnostics/runtime.jsonl` and the previous rotation at `files/diagnostics/runtime.previous.jsonl`.

The in-memory owner view retains at most 256 events. Each file is capped at 256 KiB, two rotations total. Oversized damaged diagnostic files are discarded without reading them into memory. Writes use a bounded background queue; disk failures and dropped writes are visible in the snapshot and must not change the original business outcome. Logcat receives the same sanitized events. The app never automatically uploads these logs. Queueing is best effort: abrupt process death can lose pending file writes, so a missing log line is not proof that an operation did not happen.

The Web command is limited to the current visible private-owner model-settings window and is excluded from result replay. Closing, navigating, replacing the document or backgrounding invalidates old results and copy notifications.

## Repeatable local verification

Use the fixed registry suite rather than a historical feature branch name:

```sh
python3 -B tools/host-tests.py --suite main-chain --check
python3 -B tools/host-tests.py --suite main-chain
```

Supply `--java-home`, `--android-home` and `--gradle` when the local toolchain is not on the default paths. The suite reuses existing semantic groups for accounts, model selection, streaming, private run lifecycle, project reads/images and diagnostics. Add a regression to its owning group when adding or repairing one of these boundaries.

`--check` performs prerequisites only and reports `not_run`. A successful compile or a runner that selected zero tests must never be reported as a passing test run. `result.json` records the selection, stage, whether Gradle ran and whether execution was verified. Actual execution must have complete JUnit XML with no failed/skipped/zero-test classes. Missing locked dependencies fail with a nonzero exit code.

For a fix, retain a failing example from the previous behavior and rerun the same journey after the change. Pair happy-path checks with cancellation, logout/disable/source withdrawal and uncertain-receipt cases. Do not weaken security assertions merely to accommodate new version arithmetic. Pure JVM checks, loopback provider tests, Robolectric/Android integration, a packaged app and a real account/device session are distinct evidence levels.

The reliability repair does not alter workflow triggers. Local verification and a `[skip ci]` review commit do not authorize a workflow dispatch. A future decision to enable an automatic main-chain gate must explicitly account for the project's current CI execution policy.

## Scope and review

The repair is bounded to the reproduced account recovery failures, recoverable read receipts, independent image delivery, inherited cancellation/executor settlement, missing main-chain registration and usable safe diagnostics. It preserves current source/task authority, UI components, token accounting and unknown-outcome reconciliation. It does not replay inference POSTs, rewrite the entire task store or merge the separate detailed trace UI project.

Review changes in this order: authority invariants → actual request/receipt behavior → persistence/recovery → user-visible feedback → diagnostic evidence. A large class should be split when a concrete responsibility boundary is being changed, with its callers and regression evidence moved together; an arbitrary file split is not a reliability fix.
