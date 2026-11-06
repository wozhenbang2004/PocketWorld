# Task execution traces

The task's **过程 → 轨迹** view reads the original native execution journal.
It presents an Input / Model / Tools overview above native Turn sections. The
overview can use event order or duration weights; selecting a segment locates
and highlights the original event. Assistant events have a separate Request
entry for the physical model call. The interval timeline, call tree and existing
event and operation tabs remain available.
The view is local and read-only; opening, refreshing or closing it does not run,
retry, approve, pause or cancel a task.

## Source and identity

- `InferenceCapture` remains the source of each physical model call, including
  failed attempts. `logicalRequestId` and `attemptNo` associate retries without
  overwriting their individual time or provider usage.
- `ToolTraceCapture` observes the existing Runner and owner-skill invocation
  paths. The coordinator's native `coordinator_read` path records the same
  observation schema through its existing begin/finish operations.
- `TaskTraceQuery` projects the existing SQLite `task_events`, `runs` and
  `operations` tables, plus retained original coordinator checkpoints. It does
  not create a second task store or execution loop.
- Model node IDs are `model:<physical-call-id>`. Tool node IDs use a separate
  native UUID as `tool:<native-trace-call-id>`. Provider-supplied tool IDs are
  retained only as correlation metadata.
- `kind` remains `model`, `tool` or `event`. `role` distinguishes `input`,
  `context`, `assistant`, `request`, `tool`, `subtool` and other retained events.
  The physical model node is the sole usage-accounting node; an assistant event
  references it through `requestId` and does not duplicate its tokens or calls.
- Ordinary task Turns come from the Runner's actual model/tool cycle. The
  journal binds its original run and round to the receipt's native call site;
  retries remain in that Turn. `turnId`, `turnIndex`, `stepId` and `stepIndex`
  describe observed execution sites. Team calls retain their existing native
  turn identity and use the actual request ordinal for the displayed Turn;
  the discussion round remains a separate fact. Epochs and timestamps are never
  substituted for a Turn.
- Input events come from actually consumed owner messages; recovery prompts and
  observation messages are not relabelled as new user input. Context/assistant
  events retain source identities and message fingerprints, rather than a second
  permanent copy of message bodies.
- Parents come from the native model/tool identity recorded during execution.
  Missing or ambiguous ancestry remains absent. Time proximity never establishes
  parentage. A parent outside the current page is labelled accordingly.
- External team runs retain their real epoch zero. Native owner/private team
  child tasks can be read through their own original task. This view does not
  flatten unrelated child tasks into a fabricated common execution.

## Team coordinator and retained context

The production `TeamsModule` uses the typed `TeamCoordinator.InferenceCall`
factory to observe the same admitted MEMBER and SUMMARY calls. Its original
goal and actually claimed owner inbox messages form Input events; composed
instructions and prior outputs remain Context. Peer tools, material reads and
summary `team_read_output` capture the tool schema offered to that request,
provider call ID, original operation identity, result and observed timing at
their real invocation sites. The existing admission, operation and budget
hosts remain authoritative. Reading a trace does not dispatch another request
or consume a coordinator budget.

Coordinator messages previously existed only in the live coordinator's
message array. Database schema 11 adds `team_coordinator_checkpoints` as the
original durable context for newly observed coordinator requests and accepted
responses. It is keyed by the original task/run/checkpoint identities; it never
creates a HostRun or changes the team's external epoch zero. Content-free
trace events retain references and fingerprints into those originals. The
checkpoint records the request's material scope, original parent sources and
the source dependencies of prior member/research outputs. Missing ancestor
provenance makes the affected body unavailable, rather than treating it as
source-independent.

Research child HostRuns freeze their own content-free
`execution_trace_sources` plan in the actual physical STARTED transaction.
Historical details use that execution-time plan; a later review or material
scope renewal cannot replace the old run's source identity. Older records
without retained coordinator context or a required original source plan keep
their available metadata and report that the body was not retained.

The native upgrade creates the new table without synthesizing historical
requests. Migration coverage exercises schema versions 1 through 10 upgrading
to 11. Maintenance staging includes the table in its existing empty-staging
checks. Recovery copies continue to require an exact schema match; this change
does not silently upgrade a read-only older recovery copy.

## Timing and outcomes

Model elapsed time starts at the original physical dispatch. Tool elapsed time
is the observed native invocation processing interval. Runner measurements
include the checks and receipt handling inside that interval. Coordinator reads
start after their original SQL admission and before invoking the reader, and
finish after the original receipt is committed. Neither interval is the lifetime
of an asynchronous external effect. Start/end wall-clock timestamps locate calls on the timeline;
monotonic elapsed nanoseconds establish duration. An observed clock adjustment
does not replace the elapsed measurement with wall-clock subtraction.

A returned tool can have a measured duration while its effect is still unknown,
awaiting owner input or running externally. The outcome and the duration are
separate facts. Missing terminal records on an ended run remain unconfirmed;
the reader does not invent a finish timestamp. Old records without timing show
that timing was not recorded. In-flight calls show their start marker and state,
not an invented completed duration.

Event order follows the native journal sequence even when wall time moves
backward. The duration-weighted overview is a sequence of recorded durations;
its width is not a claim that parallel calls ran sequentially. The separate
interval timeline preserves actual start positions and overlap.

`firstVisibleTextAt` and `firstVisibleTextElapsedNanos` record the first valid
provider text delta observed locally, before the presentation layer's buffering.
`timingEvidence` is `provider_text_delta`. This is labelled **首段文字**, rather
than universal token latency: reasoning tokens, tool arguments and metadata are
not visible text. Non-streaming JSON fallback has no such observation. No prompt
or response text is added to the content-free inference receipt by this timing
instrumentation.

`lastVisibleTextAt` / `lastVisibleTextElapsedNanos` observe the last such delta.
`generationDurationMs` is the measured first-to-last visible-text interval and
`generationTimingMeaning` is `client_first_to_last_visible_text_delta`; it is
not total request time minus TTFT. A single observed delta has no positive
generation interval. Any displayed token rate states both its provider counter
and this client observation interval; it is not asserted as the provider's
precise visible-text generation speed.

## Usage and content

Token counts come from `ProviderUsage`, preserving the service's reported fields,
source and partial/final stage. Display and OTLP normalization respect
`inputIncludesCacheRead`, `inputIncludesCacheWrite` and
`outputIncludesReasoning`. Cache and reasoning components already included in
input/output are not added again. Missing counters remain unknown rather than
zero. Tool nodes do not acquire model token costs.

The UI accumulates compact pages for the selected original run and identifies
backfill in progress. Whole-run counts and cumulative request usage use only
that run's loaded physical model calls; incomplete coverage remains visible.
Detailed original task usage remains available through the existing usage
view. This change does not infer prices for unpriced models or subscriptions.

Compact pages retain usage, observed timing, status, `hasError` and correlation
metadata, while detailed bodies are read only for the selected node. Request
options and tool schemas are safe execution-time snapshots, not a lookup of the
current configuration or registry. Credentials and full wire messages are not
stored in the content-free accounting receipt.

Detail reads select an original task/run/node and its native source references,
then enter the existing `MemorySourceBridge.withCurrentUserSources` forget
fence. The native read also rechecks repository revision/retirement and original
output-source context. Bodies come from retained original messages/checkpoints
or operations under those checks. Edited, forgotten, withdrawn or no-longer-
retained sources return metadata with an explicit unavailable reason. Reading
an audit event does not restore removed content or grant execution authority.
Large retained bodies use the existing bounded sanitization and report limits.

Team material metadata is checked outside the TaskStore monitor both before
and after reading a selected body. The owner read window, original source
plan and memory forget fence are checked as part of the same read journey.
Unavailable source checks return fresh metadata and an explicit reason, never
a cached body. Source mutations also invalidate an already-open detail:
memory/attachment removal, skill changes, committed workspace changes and
successful team material approval or withdrawal advance the native source
revision. The Web view clears the body immediately and rejects late responses
from the old revision. Workspace notifications are queued after the original
commit, avoiding a workspace-to-TaskStore lock inversion.

## Web bridge

`trace.read` accepts:

```json
{"taskId":"original-task-id","runId":"","cursor":"","limit":100}
```

An empty `runId` selects the current original run. `limit` is bounded to 1–200;
the UI requests 100. The response format is
`pocketworld.execution-trace.v1`, with task/run/epoch, capture time, retained run
choices, nodes, an opaque cursor, next cursor and explicit completeness flags.
Every result has `readOnly: true` and `executionAuthorized: false`. The UI
automatically follows older cursors in order, merges nodes by their native IDs,
and retains chronological sequence across page boundaries.

Paging fixes event membership to the native high-water mark. Existing inference
receipt rows can acquire their real terminal state. Returning to the latest page
opens a fresh read window. Source limits, invalid/oversized records and unbound
legacy events remain explicit; they are not silently counted as complete runs.
The run chooser is bounded to 200 retained choices and reports a limited list.

`trace.detail` accepts the exact selection, independently of the page cursor:

```json
{"taskId":"original-task-id","runId":"original-run-id","epoch":7,"nodeId":"model:physical-call-id"}
```

The response format is `pocketworld.execution-trace-detail.v1`, with
`taskId`, `runId`, `epoch`, `capturedAt`, `node`, `sourceState`, `sourceReason`,
`readOnly: true` and `executionAuthorized: false`. `sourceState` is `current`,
`unavailable` or `metadata_only`. Depending on the role and retained original,
the node provides message/preview/raw, input/output, options, schema, error and
source references. The bridge checks task, run, epoch and node identity before
returning the detail. Request, tool and message tabs expose the corresponding
observed facts rather than empty success placeholders.
Request context is labelled as retained original checkpoint context. It does
not claim to be the complete final wire prompt after provider-specific
projection, budget trimming or transformations.

`trace.close` accepts `{}` and cancels both read slots. Opening a detail never
cancels older-page backfill. The bridge binds
responses to the original owner window; the UI independently rejects late
responses after task/run/document changes. Live reads are sequential, every
three seconds while the current task's latest trace is visible and active.
Hidden documents and closed task layers do not poll. The process view starts
reading only after `tasks.open` has established the original native window.
A read error retains the last successful metadata and offers an explicit refresh;
source invalidation clears any no-longer-current detail body.

## Existing OTLP export

`DiagnosticTraceProjection` continues to feed the existing owner-controlled
`OtlpTraceExport`. Measured spans use the real start plus monotonic elapsed time;
the capture timestamp is never substituted for an unknown tool end. Where the
native relationship exists, the export supplies `parentSpanId` and
`openinference.span.kind`. Known final usage is mapped to the corresponding
`gen_ai.usage.*` counters without double counting.

Export remains explicit and redacted. This implementation does not provision
a cloud service, configure a collector, add external credentials or claim an
authenticated direct Langfuse/Phoenix connection. A compatible existing OTLP
HTTP JSON collector can remain the external integration boundary.

## Design and research decisions

The confirmed target is **Whaleness / DSH Mobile on iOS**, rather than the
earlier DSH-Go Flutter sheet. The pinned Swift source at
`fc12ae44dcb777f9429553048cba21d53849f427` and the author's light screenshots were
inspected. The source and MIT notice are retained under
`web-ui/src/library/upstream/whaleness/`.

- [Whaleness trajectory source](https://github.com/Clarklevis1995/dsh-mobile/blob/fc12ae44dcb777f9429553048cba21d53849f427/DeepSeekHarnessMobile/Views/TrajectoryView.swift)
- [Author overview screenshot](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/fc12ae44dcb777f9429553048cba21d53849f427/Docs/images/screenshots/trajectory-light.png)
- [Author detail screenshot](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/fc12ae44dcb777f9429553048cba21d53849f427/Docs/images/screenshots/trajectory-detail-light.png)

The prior research also inspected the Flutter implementation at
`20447ce63b9ce38ab1757d14137467f7d268224a`; it is retained as supplementary
research, not the accepted reproduction target:

- [Trajectory sheet](https://github.com/ccch1mneyyy/dsh-Go/blob/20447ce63b9ce38ab1757d14137467f7d268224a/app/lib/ui/chat/trajectory_sheet.dart)
- [Original golden image](https://raw.githubusercontent.com/ccch1mneyyy/dsh-Go/20447ce63b9ce38ab1757d14137467f7d268224a/app/test/chat/goldens/run_trajectory_light.png)
- [DeepSeek Harness trajectory package](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/client/ui-trajectory)

The default three-lane, Turn and detail interactions are adapted from the
Whaleness Swift source to existing PocketWorld React components and neutral
project surfaces. The app's auxiliary React tree/timeline is adapted from the retained approved library
`TraceWaterfallDemo`, with its precise identity recorded in
`web-ui/src/library/TRACE-PROVENANCE.json`. It is an independent library
reconstruction, not Affect UI Pro source. Demo data, timers and cost arithmetic
are removed. DSH-Go Flutter code is a research reference rather than a new
runtime dependency. Existing forms, tabs and drawers provide mobile interaction.

Complementary systems were compared:

| Candidate | Relevant capability | Decision for PocketWorld |
| --- | --- | --- |
| Whaleness / DSH Mobile iOS | Three lanes, native Turns, separate Request entry and full request/tool/event tabs | Adapt the pinned Swift interaction model to the existing React task process view and original PocketWorld facts. |
| DSH-Go / Harness trajectory | Per-turn ledger, call details, waterfall and distinct timing modes | Supplementary research; the earlier simplified Flutter reference is not the accepted target. |
| [dsh-devtools](https://github.com/izz-BLUE/dsh-devtools) | Community runtime-profiler plugin for Harness: turns/steps, timing, retries, errors and trace-completeness indicators | Reference its read-only projection and explicit incomplete-trace semantics. Its Cordis/profile mounting targets Harness, rather than PocketWorld's Android host. |
| [Langfuse](https://langfuse.com/docs/observability/features/agent-graphs) | Trace tree, timeline and agent graph | Preserve an OTLP boundary for a separately configured service. Its inferred agent graph is not proof of native causal relationships. |
| [Phoenix / OpenInference](https://github.com/Arize-ai/openinference) | LLM/tool/agent semantic conventions and tracing integrations | Reuse relevant semantic attributes. Phoenix's [current ELv2 license](https://github.com/Arize-ai/phoenix/blob/main/LICENSE) is distinct from OpenInference's own licensing. |
| [LangWatch](https://github.com/langwatch/langwatch) | Agent observability and evaluation platform | Useful external-platform alternative; no new server dependency is required by the Android trace view. |
| [Perfetto](https://perfetto.dev/docs/visualization/trace-viewer) | Detailed offline trace analysis | A possible separate export surface; no Perfetto exporter is claimed in this change. |
| [OpenTelemetry GenAI](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/) / [OTLP](https://opentelemetry.io/docs/specs/otlp/) | Portable trace transport and usage semantics | Extend the existing exporter with genuine durations, native parentage and compatible usage fields. |

The local view is the default because it fits PocketWorld's Android task workflow
and existing native journal. An external observability server can be adopted
independently when centralized analysis is needed.

## Verification

Build the production Web bundle before native host tests:

```sh
npm run build --prefix web-ui
node --experimental-strip-types --test web-ui/tests/execution-trace.test.ts
cd web-ui
TRACE_CHROMIUM=/path/to/chromium-or-headless-shell node tests/execution-trace-ui.mjs
cd ..
python3 tools/host-tests.py --group execution-trace \
  --java-home /path/to/jdk17 --android-home /path/to/android-sdk \
  --gradle /path/to/gradle-8.9/bin/gradle \
  --robolectric-dependency-dir /path/to/offline-robolectric
```

The registered native group exercises original SQLite/Runner/Gateway paths,
epoch-zero MEMBER/SUMMARY coordinator requests and tools, database reopen,
source forget and material withdrawal, schema migration, workspace source
notifications, physical attempts, timing, retained-run reads and the existing
OTLP path using controlled local HTTP fixtures. Web interaction fixtures are
confined to `web-ui/tests`. These checks do not establish physical Android
WebView, live provider or live external-collector acceptance.


## Integration checkpoint · 2026-11-05

The local integration rebases the second trajectory implementation from PR #60
(`996578e7424d4ff5308bd8e0a3e736c0b0621fa4`) onto main
`fea4511cffc54122170adff10322d5712a28a691`, together with the private-stop,
Compose observer, account-catalog and diagnostics boundary refactors. The earlier
DSH-Go-based delivery is not applied a second time. Later native approval facts,
source/history fences, bootstrap diagnostics and all existing host-test groups
are retained. This checkpoint is prepared locally, not published or released.

Tool outcomes use the existing native classifier. A receipt obtained before
cancellation retains its original planning-read operation ID after durable
settlement, without dispatching the remaining batch. Native message references
retain their exact `sha256:` fingerprint format, while bodies and unrecognized
locator fields remain excluded from metadata. The real long-context regression
requires all 350 original references and messages to remain recoverable.

Coordinator reads now distinguish the original in-process active call from a
persistent unknown outcome. Only that exact still-current call can keep its
already-running task running during the synchronous read. Other unresolved
operations, cancellation, stale generations, failed settlement and process
restart do not acquire this exception. Model admission checks unresolved facts
directly rather than waiting for a later presentation event.

Focused verification uses actual changed Java sources and real SQLite tests,
with cached application dependencies. This is not a complete locked AGP build.
The native cancellation and fingerprint fixes each have same-test red/green
proof. The TypeScript trace contract and isolated Compose observer checks pass.
Two existing ExecutionPlanningRunnerTest fixtures also fail against exact
unmodified main in this dependency closure: they request an unoffered write and
are rejected by protocol validation before reaching their expected Runner branch.
They have not been weakened or reported as passing.

No workflow or automatic CI-selection change is included. The execution-trace
group is registered for explicit local host runs. No workflow was dispatched;
any separately authorized publication must use `[skip ci]` after checking current
triggers and remote HEAD. PR #72 and the proposed Runner-settlement extraction
remain separate and are not claimed as merged.

Full mixed Java/Kotlin compilation, complete selected host groups, production
Web build, current rendered browser interactions, APK and physical Android
acceptance remain separate gates. Run them against one exact final checkout in
the Android build environment, not a mixture of cached application classes.
