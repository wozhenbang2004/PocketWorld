# Public web research in PocketWorld

This change extends the existing host-owned AgentRunner + TaskStore tool surface. It does not create a second runtime, agent queue, browser permission mechanism, or model credential store.

## Tool routes

| Tool | Authorized audience | Effect | Limits |
| --- | --- | --- | --- |
| `web_search` | Original private owner task | Public provider query | 1 query, up to 8 hits |
| `web_fetch` | Original private owner task | Anonymous HTTP GET | Original PublicHttpReader budgets, no cookies |
| `web_research` | Original private owner task | Multiple provider queries followed by public text reading | 1–4 queries, 0–4 page reads, up to 16 deduplicated hits |
| `web_sources` | The same original task/run/epoch | Read the native TaskStore source events | 40 entries per page at most |
| `web_provider_search` | Private owner task with an official supported API model and explicit owner opt-in | A separately billable vendor-native search request | One physical HTTP request per tool execution; max two executions in one supervisor lifetime |
| `team_web_search` | Read-only team research child with approved web origins | Anonymous public search filtered strictly to the owner-approved origins | The original research read allocation and up to 4 origins |
| `group_public_search` | Explicit group public.search capability after owner opt-in | Same native search provider | No private browser profile or owner memory |

The private supervisor conservatively reserves up to 24 searches, 16 public page reads and two paid vendor requests per supervisor lifetime. Tool validation and original-run controls remain authoritative. Group and research-child budgets are separately bounded by their existing native hosts.

## Public read and dynamic fallback

`web_research` returns `results` (search hit snippets) and `pages` (actually retrieved HTTP text where available). `read` is not proof that a JavaScript page was rendered. If an anonymous read needs WebView, a result can carry `nextAction: requires_authorized_browser_task`. This is a **handoff suggestion**, not an automatic browser grant or an automatic new task. Existing `browser_task_open`, `browser_task_observe` and `browser_task_retain_source` may only run if the owner has actually opened and authorized the browser task surface.

No new arbitrary HTTP fetch tool, private-IP access, automatic cross-origin redirects, download, login bypass, or invisible cookie/session reuse is introduced.

## Source and citation behavior

A native `web_public_sources` TaskStore event keeps the original task/run/epoch, current operation ID, a list of actual source URLs, redacted display URLs, source titles, bounded excerpts, excerpt SHA-256 and retrieval timestamps. `web_sources` exposes only sources already stored for that same run. Each returned reference has `sourceId`, `url`, `displayUrl`, `coverage`, `bodyComplete`, and `verified: false`.

The assistant is instructed to cite URLs actually present in this receipt, not to invent a reference. A source being saved does **not** prove model reliance or independent verification of its contents. Existing source windows from browser tasks remain separate, and the web ledger does not replace them.

## Hosted vendor search

Owner setting `providerHostedEnabled` defaults to false. The extra tool is mounted only for the original pinned, official HTTPS OpenAI Responses, Anthropic Messages, or Gemini GenerateContent API endpoint. It is not mounted for ChatGPT plan login, third-party "compatible" gateways, Bedrock, or ordinary Chat Completions. It also requires an exact tool call approval. The vendor request gets only the bounded public query, not full conversation history, cookies, private files or account material. Responses are parsed for vendor-reported sources, and no link means no confirmed search.

Claude supports an explicit request `max_uses`. OpenAI and Gemini receive a best-effort textual uses instruction, **not an enforced vendor-side search budget**. Each tool execution remains a single physical API request. Provider pricing, availability, tool names and model support may change; a server rejection is returned as unavailable and no automatic replay is allowed.

Reference documentation:
- https://developers.openai.com/api/docs/guides/tools-web-search
- https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
- https://ai.google.dev/gemini-api/docs/generate-content/google-search

## Usage and diagnostics

The native TaskStore event records search, HTTP reads, visited pages, separately returned vendor input/output tokens and whether those fields were actually reported by the vendor. `web_sources` exposes per-run totals. The Web Search settings page and Usage > Web Activity show **current process counters** only, clearly separate from date-scoped inference totals. Actual vendor fees are `unknown` until independently reconciled with the provider bill.

For privacy, RuntimeDiagnostics logs statuses and generic error codes without search queries or full URLs.

## Manual checks after building (not run as part of this change)

1. Select DuckDuckGo with no API key; ask a private assistant about current public information; confirm `web_search`, `web_research`, and then `web_sources` deliver genuine URLs and timestamps.
2. Check a static article can be read via `web_fetch`. A JavaScript-only or login-gated page should request an authorized browser handoff and must not be marked fully read.
3. Try a `web_research` request with four distinct queries and then a second request near budget exhaustion; verify native limit is enforced and partial results aren't presented as exhaustive.
4. Try a private address, HTTP URL, token, email and third-party redirect; confirm rejection before any forbidden network dispatch.
5. Check a team research member with two owner-approved site origins only receives matches from those domains. Without approval it should have no `team_web_search`.
6. Keep `groupSearchEnabled` false and verify group public search isn't available; enable it and validate group content does not leak private sources.
7. Keep `providerHostedEnabled` false and verify no vendor search tool. Enable with an official API model and approve exactly one search; verify provider tokens and URLs. Use a third-party relay URL and confirm the tool is not mounted.
8. Verify closing or canceling an original run interrupts active network calls without retrying an ambiguous attempt.
9. Check the Usage > Web Activity counters and the task's persisted `web_sources` disagree only for stated scope differences, never mix vendor search tokens into inference totals.
10. Verify normal chat, owner butler, and research tasks do not create a second AgentRunner/TaskStore or bypass the original tool permission policy.

No APK build or device tests were performed for this change. Compile status and actual network success must be established by the user's build.
