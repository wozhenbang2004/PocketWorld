# Structured Windows Computer Use

PocketWorld combines two native executors inside its existing Companion. The
Android Agent, original task budget, operation ledger, grants and owner remain
the authority. Neither executor starts another Agent or receives model keys.

## Exact upstream contract

The source pin is [vercel-labs/agent-desktop at
6660fbac768dc7e7332837b90ed465684859afa3](https://github.com/vercel-labs/agent-desktop/tree/6660fbac768dc7e7332837b90ed465684859afa3),
package version **1.1.0**, JSON contract **2.6**, Apache-2.0. This is a fixed main
commit, not a claim that the 1.1.0 release archive includes the same features.
The integration uses the CLI. MCP support is not required or advertised as a
dependency. Full upstream license text is preserved in
`licenses/agent-desktop-Apache-2.0.txt`; binary redistributors must preserve it.

The admitted argv forms were read from the pinned source and documentation:

```text
agent-desktop.exe version
agent-desktop.exe snapshot --window-id w-456 --include-bounds --max-depth 12 --timeout-ms 5000
agent-desktop.exe click @s...:e2 --timeout-ms 0
agent-desktop.exe set-value @s...:e1 "replacement value" --timeout-ms 0
```

Commands use argv directly, without a shell. All actions stay in native strict
headless mode; `--headed` is never added. The original qualified native ref is
kept private and is not rebound to a fresh name/index after preflight. A fresh
UIA observation must find exactly one unchanged semantic target; the native
executor then revalidates the original ref's process, window and element
identity before dispatch. Incomplete/ambiguous observations refuse input.

Upstream `delivery_uncertain` or `unknown` remains unknown even if a later
window condition happens to match. `not_delivered` is a known no-input result;
it never authorizes automatic replay or switching to a physical path. Other
delivered dispositions require a separate fresh observation to verify the
requested window condition. That condition cannot prove an entire user goal.

## Observation modes

| Request | Returned evidence | Admitted action route |
| --- | --- | --- |
| Exact window, default `semantic` | UIA structure and bounded original refs; no screen capture or image artifact | Headless native click, UIA full-value replacement |
| Exact window, `hybrid` | Structure plus Cua PNG artifact | Semantic refs; explicit image-grounded visual input |
| Exact window, `visual` | Cua PNG artifact without an element tree | Explicit image-grounded input |
| Primary desktop, default `hybrid` | Primary desktop PNG and window metadata | Image-grounded input; window directory is not an inner UIA tree |

The mode is retained in the server's original observation, not accepted from
an action caller as new authority. Tokens are one-use, audience/grant bound,
and expire after 30 seconds. Old Cua-only configurations still support pure
semantic SetValue; Cua element clicks require image evidence because the
pinned Cua click implementation can fall back to physical input.

This bounded adapter exposes up to 150 elements and requires a complete,
unambiguous fresh target assertion. Very large, deeply nested, custom-drawn or
inaccessible trees may need an explicit visual observation. It never labels a
truncated tree complete or asserts that every application exposes useful UIA.

## Build and package

Use an already prepared Windows x64 builder with Rust 1.89 or newer, the MSVC
target and the exact dependency cache. `Build-AgentDesktop.ps1` requires a
clean checkout at the pinned commit and verifies the fixed Cargo.lock blob.
It uses `--frozen --locked`, does not download or install dependencies and
does not change the source checkout. Invoke it with an unused output directory:

```powershell
.\Build-AgentDesktop.ps1 -SourceRoot C:\Build\agent-desktop -OutputDirectory C:\Build\pocket-agent-desktop
```

Outputs are `agent-desktop.exe`, `agent-desktop-build-provenance.json` and the
build log. Provenance includes exact source/lock identity, actual executable
SHA-256, compiler/Cargo versions, target, command and UTC build time. The
read-only `version` command checks the actual built package, OS and 2.6 JSON
contract. No executable hash is fabricated in `agent-desktop-pin.json` before
a native build exists. Cua is built independently using `Build-CuaDriver.ps1`.

The original installer supplies the actual artifact paths and hashes:

```json
{
  "desktop": {
    "enabled": true,
    "binary": "C:\\PocketWorld\\cua-driver.exe",
    "sha256": "ACTUAL_CUA_SHA256",
    "agentDesktop": {
      "binary": "C:\\PocketWorld\\agent-desktop.exe",
      "sha256": "ACTUAL_AGENT_DESKTOP_SHA256"
    }
  }
}
```

Companion holds the pinned semantic executable without write/delete sharing
while it is in use, bounds JSON size and subprocess deadlines, and owns every
native child through a named Windows Job. Recovery checks the original Cua
Job **and** original semantic Job; stopping one is not proof that both stopped.
The `AGENT_DESKTOP_HOME` state belongs to this original Companion instance.

## Human viewer control

The phone viewer has a separate owner-only route. Taking control waits for any
bounded original AI action to finish; a known idle AI lease can be fenced.
Unknown, paused or other-owner leases cannot be forced. The human lease binds
the exact stream and grant for at most five minutes; release invalidates AI
observations and requires a new lease and observation before AI can continue.

Each manual input is durably deduplicated by the original operation router,
uses a live frame reference no more than two seconds old and rechecks native
process/window/display geometry under the same adapter lock. Click coordinates
are mapped from the encoded viewport to a fresh native capture identity. This
proves recent geometry, not that changing video pixels are identical. Scroll
accepts a current pointer; a desktop toolbar scroll without coordinates uses
only the same lease's last successful click at the same geometry revision.
There is no screen-center guess. Single keys are Enter/Escape/Tab; hotkeys are
distinct CTRL/ALT/SHIFT/WIN modifiers plus one letter/digit. Unknown input
delivery blocks further dispatch and cannot be replayed on reconnect.

## Verification boundary

Python fixtures exercise actual parsing, argv, lease, grant, mode and no-replay
logic. They do not execute Win32 UIA, physical input, WebRTC or an Android phone.
Native Windows testing must still cover supported apps, empty and nonempty
values, focus/DPI/session changes, a physical local-stop, packaging and the
paired phone journey. No Linux test result is a native GUI acceptance receipt.
