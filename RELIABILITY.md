# Login → model → message → tools: reliability contract

This is the shared maintenance contract for the native execution path and its diagnostics.
It does not grant execution authority and must not become a second task or account store.

## Phone capability completion — 2026-11-10

Active owner requirements: “研究 mika dshgo 和我们 pocketworld phone use 能力的差异 细节差异然后去彻底实现对齐改进”, followed by “还差什么就继续彻底实现啊只要是会影响到正常功能的”. The continuation covers concrete normal-task blockers after v0.1.71: complex-page target coverage, long UI waits, bounded target search and picker convergence, OCR/text collection, visual settling, current-profile launch ownership and observable alarm/recovery state.

The next owner instruction, “那就继续啊直接领先”, follows the explicit assessment that v0.1.74 still lacks dedicated drawn-wheel convergence, OCR-aware bounded search, permission-gated accessibility registration recovery and direct batch calendar creation. This slice implements those concrete gaps on the current main branch; it does not treat feature counts or a successful release as proof of comparative task success. The fixed reference mechanisms are Mika `996f886278bb8982f70475e8c2fc68631ccd4e6a` (wheel observation/drag correction, calendar provider batches and finite navigation/editing keys) and DSH-Go `3edc945fcdd950bd06083f9789eaa63f1693564b` (owner-gated registration repair). Implementations remain native to the original execution and permission flows.

Following the actual Mika `phone_system_command` call to `ShellService.runSystemCommand` and `exec` confirms a general `sh -c` execution domain under the shell helper, not a finite system-action list; its display environment variable does not enforce display isolation. That domain is not imported. The same review identified a concrete normal-page gap: Enter, Tab, direction and editing keys had no Android phone primitive. This slice adds an eighteen-key `phone_press_key` contract using actual native platform support, original display/focus identity and original sensitive-step handling; unsupported platform paths return a refusal rather than pretending to send input.

Acceptance for this slice: each wheel or region-scroll step dispatches at most one physical action and uses a fresh fixed native target plus the authorized local frame; OCR findings carry their actual source and cannot manufacture node references. Recovery requires both an explicit owner setting for registration writes and the actual Android secure-settings grant, preserves other services and stops after bounded attempts. Calendar batches require the current host profile, granted calendar permissions, an exact reviewed calendar and events, and preserve uncertain or partial provider receipts without replay. Full pending approvals must remain reviewable even when the task exceeds its ordinary UI document budget; no event may be clipped to make an approval fit. Existing independent UI, memory and MCP changes remain intact. Static review and the existing remote release build are the available evidence; real-device behavior remains pending owner experience.

`TaskStore`, the original `AgentRunner` and its `RunControl` remain the only execution authority. `phone_find` and `phone_prepare_wheel` produce run-local bounded native plans: each child action uses the original argument validation, permission/approval, prepared operation, journal settlement and cancellation gates. A fresh read may finish the plan early; an accepted action is never inferred from a step count, and an unknown effect never advances the plan. Restart does not restore plan authority.

Complete native trees remain adapter-local. The model receives bounded projections with explicit coverage, while queries can search the retained text. A partial unrelated window does not invalidate a completely revalidated target window; partial coverage cannot establish absence or whole-page stability. Text collection retains page provenance and explicit overlap, and paginated delivery does not claim that the first result contains the whole collection. Collection cursors expire with their original run, pause and application-policy scope.

The normal tree budget is 2048 nodes with depth 96; target-window revalidation may inspect 4096 nodes with depth 128. Standalone waits allow 60 seconds with cancellation-aware backoff. Search and picker plans allow at most 30 physical steps, while both collection modes retain at most 30 pages and 1000 rows or text segments within 256 KiB. These bounds do not prove that an entire app page or list was observed.

Extraction returns every delivered row/field reference with its actual ancestors, under a 120 KiB result budget, and supports same-observation offset pagination. Route learning, unique-target checks and replay conditions consult the corresponding adapter-local source snapshot; presentation clipping must neither invalidate complete native evidence nor make omitted nodes disappear from uniqueness checks. Historical collection pages contain evidence only and cannot replace current action references. Oversized retained rows and long escaped or multilingual text must still advance pagination without silently dropping content.

OCR values tied to tree geometry reject a screenshot that changed during capture; local-frame acquisition permits one bounded read-only retry and no repeated input. Visual settling uses authorized local pixel statistics and actual source-frame timestamps, including sparse-tree pages; it does not establish business success. Alarm observations report only what the current profile's next-alarm API exposes. Ordinary background launch retains exact package/profile ownership while allowing a bounded resource wait.

Accessibility recovery first waits for an already authorized service connection. A separately enabled owner setting and actual `WRITE_SECURE_SETTINGS` grant may then rebind only this application's still-registered service, at most three times with backoff. Fresh list comparisons preserve unrelated entries and stop on observed changes; Settings has no compare-and-set here, so cross-application concurrent writes remain a documented platform limit. The global accessibility flag is not written. Only a live verified removal ticket can restore this attempt's component through Android's derived last-service-disabled transition. Revocation, lock, ambiguous changes or process restart do not recreate that ticket; unresolved removal remains visible for owner repair. No business action resumes from this setup recovery.

The finite key path is integrated. Foreground Enter uses the actual focused editor's declared `ACTION_IME_ENTER` where available; Back and Android 13+ directional global actions require an unambiguous original display. Other Android 13+ editor keys retain the exact accessibility InputMethod connection and editor revision, but its void API supplies no reliable acceptance receipt, so attempted input remains unknown and is not replayed. Background keys require the helper's original display, task/profile and resolved target UID; unsupported targeted injection is not replaced with global input. An attempted DOWN is followed by at most one original-key UP cleanup, including cancellation; an unconfirmed background release blocks later input until the original display is released. Fresh frame acquisition does not change the original focus or input authority.

Large pending approvals use the original SQL snapshot to read one complete current-run item, with a 1 MiB item bound and 64 KiB identity/source context. The ordinary task-core limit remains 256 KiB. The projection retains the original task/run/epoch/update binding and only the original approve action; it cannot resume a task, confirm a result or change scope. The full batch is displayed without clipping, and any exceeded read/presentation bound withholds the approval action. Static cross-review covered this path and the key cleanup/focus boundary; integration preserved the concurrent chat, context, project and UI fixes on main.

Current validation policy for this delivery is source review and `git diff --check`, followed by the existing remote compilation, signing, packaging and publishing workflow. No tests are added or run, including CI/E2E, and no local build is performed. Older verification recipes below describe historical work and do not override this owner instruction. Compilation and release evidence do not establish real-device or OEM runtime behavior.

Delivery checkpoint: [v0.1.85](https://github.com/wozhenbang2004/PocketWorld/releases/tag/v0.1.85) was published on 2026-11-10 at 19:49:59 Asia/Shanghai from `4f50c7b60974279ddac2ccfff9c4d0dabb5f00c1`. [Release run 42809559410](https://github.com/wozhenbang2004/PocketWorld/actions/runs/42809559410) completed build, prepare and publish successfully. The APK is 148132394 bytes with SHA-256 `a8d5a8365111c8e844d3960dd1520668494ae750bf1be5e3d86361439715a068`. The first actual compilation exposed a cross-package validation-method visibility error; the released source fixes that visibility without changing original-control, run or pause validation. Real-device experience and cross-project comparative performance remain unverified.

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

## One native tool-outcome contract

`ToolOutcome` classifies the original native receipt. Runner continuation, planning-read settlement, both operation journals, repeated-failure handling, optional model routing and native/Web presentation consume that classification. The original receipt remains the evidence; incoming or nested provider `toolOutcome` objects cannot choose the classification.

| `toolOutcome.kind` | Meaning | Next boundary |
| --- | --- | --- |
| `success` | This native operation returned a known successful result or acknowledgement. | Continue only within the existing scope and control gates. |
| `correctable_failure` | A known failure can be explained or the request revised. | The model may revise its next request; the original operation is never automatically replayed. |
| `needs_user` | The original task needs owner input, approval or return of control. | Stop the batch and preserve the undispatched tail. |
| `effect_unknown` | The original effect/result is not sufficiently confirmed. | Preserve reconciliation and inspect the original operation; do not resend it. |

The versioned `pocketworld.tool-outcome.v1` projection contains `kind`, `nextAction` and `automaticReplayAllowed:false`. It does not establish dispatch, termination, service readiness, verified business success or new authority. Native running-service acknowledgements still require their exact journal/process proof. The separate owner-reconciliation `outcome=occurred|not_occurred` is unchanged.

Explicit uncertainty outranks positive `ok` and owner-input flags. Malformed control fields fail closed. Data-only native success exits are marked at the actual workspace, memory, remote-read or draft-source adapter. MCP protocol errors and input requirements are classified at each confirmed original-response adapter, including remote MCP Tasks and native local MCP; arbitrary HTTP response bodies remain nested data and cannot claim host business verification.

`RunJournal.finishOperationReceipt` returns the canonical receipt actually retained by the native journal. All journal decorators forward it. Runner classifies and delivers that returned receipt, so a simplified synchronous return cannot replace a more accurate native callback already stored for the same operation. `TaskOperationReceipts` owns the shared storage classification; TaskStore retains its monitor, transactions, native receipt preservation, original task/run/epoch checks and budget ownership.

`CapabilityPort.ReceiptAfterStop` binds an already obtained receipt to the same original `RunControl` object. Runner may settle that issued operation or planning-read claim, then rethrows the original stop; it neither sends the withdrawn body to another model request nor admits new work. A FeatureHost notification failure after successful settlement cannot overwrite the committed receipt with synthetic unknown. A genuine late receipt can still settle an original unknown operation after cancellation.

On upgrade, one transactional pass restores explicit uncertainty accidentally stored as `state=receipt`. Raw bodies, identities, budgets, owner resolutions and native nonterminal states remain intact. Large bodies are read in SQL chunks and only their top-level uncertainty flags are parsed; exceeding the inline read size alone never turns a known result into unknown. Database read failures abort the migration rather than committing a false business outcome. A terminal task whose omitted unknown barrier is restored becomes reconciling.

`PocketWebTaskActions` separates task review/decision logic from Web bridge protocol and lifecycle handling. The original one-use review handles, current-window checks, native receipt completion and device/special-task exclusions remain the authority. Attention/progress shows unresolved effects even when a task has a terminal label or a later successful event; an unavailable operation ID never manufactures a resolve action.

## Operational logs

`RuntimeDiagnostics` records bounded metadata before and after the task database becomes ready. Existing task events, usage records and detailed native traces retain their own authority; runtime logs supplement them with startup/login/admission failures and swallowed persistence errors.

Stages: `bootstrap`, `login`, `account_refresh`, `model_catalog`, `model_prepare`, `message_admission`, `model_request`, `tool_execution`, `run_settlement`, `diagnostics`.

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

The repair covers the reproduced account recovery failures, shared tool outcomes and canonical receipt settlement, cold recovery, recoverable reads and independent image delivery, cancellation/executor settlement, task-review separation and usable diagnostics. It preserves source/task authority and token accounting. Receipt persistence and task-review dispatch are the first concrete responsibility extractions; the remaining TaskStore and bridge responsibilities are still substantial. The separate detailed trajectory UI remains independently reviewed.

Review changes in this order: authority invariants → actual request/receipt behavior → persistence/recovery → user-visible feedback → diagnostic evidence. A large class should be split when a concrete responsibility boundary is being changed, with its callers and regression evidence moved together; an arbitrary file split is not a reliability fix.

## Review checkpoint — 2026-11-05 continuation

This continuation keeps the original draft PR #69 (`3997689f9203049db78cbfcd443b90f48d71108d`) and integrates main through `0e192743a77c82f7a38fc5f20e2c1f83fea2042f`, including the motion changes and fifth boundary-fix batch. The original SUMMARY candidate/deadline preparation and the additional first-stream account-authority continuity fix are both retained. No workflow or lockfile is changed by this continuation.

Evidence for the integrated source, with scopes kept separate:

| Verification | Observed result | Boundary |
| --- | --- | --- |
| Shared outcomes, MCP receipt helper, stopped-run receipt binding and repeated-failure guard | 50 JUnit tests passed; 15 actual Java sources | No behavior substitutes; not the Android journal or Runner. |
| Account/provider/token receipt-time tests | 136 passed | Real account/provider logic and synthetic localhost services; ModelConfig, context-budget/request-policy and catalog-TTL compile boundaries remain. Cached OkHttp 4.12.0 is not the locked Android dependency closure. |
| Actual ModelGateway and incomplete-response/retry boundaries | 37 passed across five classes | 78 actual production sources, nine actual test/fixture sources and three declared compile-only markers/constants; no replacement of Gateway/account/transport behavior. Full ContextCompactor is outside this closure. |
| Web task presentation and diagnostics handlers | 17 passed | Task projection functions are real; log component handlers use hook/host doubles. Not a rendered WebView acceptance. |
| Host-test selection/reporting tooling | 50 passed | Tests the entrypoint contract, not application execution. |
| Full Java dependency-closure comparison | Baseline and integrated source both report 569 missing-dependency diagnostics; no added/removed diagnostics | 941 versus 944 parsed sources; both compilation exits are nonzero. This is not a successful build. |
| New native/Runner regression syntax | Five Java test files parse without syntax errors | No type analysis or JUnit execution. |

The fixed main-chain selection now contains 83 exact test classes. Its final preflight reports `not_run`: the pinned `android-all-instrumented-15-robolectric-13954326-i7.jar` is unavailable. Full Android host execution, SQLite upgrade/cold-recovery and cancellation journeys, full Kotlin/React type checks, APK packaging and device acceptance remain unverified. Existing read-range and diagnostic tests from the prior checkpoint remain separate evidence, not substitutes for those gates.

Next acceptance action: in the complete locked host environment, run the fixed main-chain suite, including the original MCP canonical-settlement, large-receipt migration, before/after-result cancellation and native task-review tests; then exercise real conversation → tool → stored result → reopen on the current Android build. Keep this PR draft until those integration gaps are resolved. No CI dispatch or merge is part of this continuation.
