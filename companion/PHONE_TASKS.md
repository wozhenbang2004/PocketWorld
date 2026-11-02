# Phone tasks from an existing Companion

`phone_tasks.py` is a thin terminal for one explicitly selected phone. The existing
Companion must already be running locally, and its native device service must have
an owner-confirmed pairing, a signed receiving-phone grant and an online mTLS
control channel. This adapter never provisions identity, grants or model keys.

```sh
python phone_tasks.py --endpoint http://127.0.0.1:8765 \
  --token-file /absolute/private/companion-token --phone pw1_<exact-64-hex-id> doctor
```

The token file is an existing private Companion token. POSIX checks owner/mode and
refuses symbolic links; Windows uses the original native owner/DACL file reader.
The client opens loopback directly, ignores proxy environment variables, follows
no redirects and retries no requests. A connection check proves native transport
and paired metadata only, not model inference.

Use `run --grant ... --resource '{...}' --capability-version ... 'goal'` with the
exact resource shown by the paired phone. Budgets (`--tools`, `--models`) and
`--ttl-ms` are bounded again by the phone's current native grant. The CLI prints
an operation ID before its first possible write; `--operation-id` can preserve an
already chosen ID. Android accepts the goal in its original TaskStore/Runner.
The source ledger contains dispatch identity and receipt metadata, without copies
of the goal or phone result text.

`status ID`, `wait ID --timeout-ms 30000`, `events ID --cursor ...`, and
`cancel ID --cancel-operation-id ...` address that same original operation.
Wait timeout, terminal closure and disconnected desktop windows do not cancel a
phone task. Unknown modification outcomes return exit code 3; query the same ID.
Do not choose a new operation ID to retry an unconfirmed write. Cancellation has
its own original operation ID and reports requested/stopped separately.

`acquire-control`, `answer` and `steer` use the receiving phone's original native
GoalSession and Runner mailbox. Read `status ID` first and copy its exact `taskId`,
`runId`, `executorEpoch` and `result.ownerInput.allocationId`. Acquisition uses a
separate `--control-operation-id` and `--task`, `--run`, `--epoch`, `--allocation`;
renewal requires the exact `--previous-lease`. It changes no task/run budget.
Use `answer ID 'text'` with that scope, `--control-lease`, native `--question` UUID
and `--wait-revision`; `steer ID 'text'` accepts guidance only while the original
goal is active. Each input has its own control operation ID, printed before the
first possible write. Query an unknown input with `status CONTROL_ID`; neither
the input nor its question is automatically replayed.

These verbs require the current pinned protocol profile and an explicitly
owner-reviewed receiving-phone grant containing the chosen method. Old profile
or grant records are never upgraded automatically. Saved text is native paired
task input, without a private conversation USER source or memory identity.
Answer continuation keeps the original run, captured model, allocation and
remaining authorization; unavailable original dependencies leave the answer
saved. `task.respond` remains a separate native approval operation. Phone
approvals and takeover stay on the phone.

## Local Desk and MCP

`ui` starts an ephemeral server on `127.0.0.1` and prints its local URL. Its session
capability is a URL fragment removed by the page immediately. The browser never
receives the Companion token or any phone credential. Host, Origin, bearer session
capability, JSON bounds and fixed phone identity are checked on each action.
The Desk retains a goal draft in the current page and displays original IDs before
submitting. Check connection reads the selected phone's current signed scope
receipts and lets the owner choose an exact existing scope; backend pairing and
grant validation still run before submission. It makes no automatic mutation or
polling retries. After a current task read, its input controls display only the
native ordinary question or running guidance route and the current control
lease. Answer and guidance drafts follow the selected original operation in the
same page. An unconfirmed control write remains locked to its visible ID until
the owner reads that original receipt; changing tasks preserves the draft.

`mcp` runs a newline-framed stdio MCP server. The Desk also serves `/mcp` with an
independent local session bearer, `Accept: application/json, text/event-stream`,
and MCP protocol version `2025-11-25`. POST replies are JSON; GET returns 405
because no standalone SSE stream is offered. HTTP initialization negotiates the
version; subsequent requests need `MCP-Protocol-Version: 2025-11-25`. No sampling,
remote model execution, phone approval or device-pairing tool is exposed.
`phone_task_acquire_control`, `phone_task_answer` and `phone_task_steer` use the
same closed scope contract and existing local Client; approval/decision/takeover
fields are rejected.

MCP transport/lifecycle/tool contracts were checked against the official
[2025-11-25 specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).
This version is explicitly negotiated; no compatibility with later revisions is
implied.

## Optional owner-requested USB transport

`usb-open --adb /absolute/path/to/adb --serial EXACT --boot-count N
--phone-control-port PORT --grant GRANT` creates one `adb forward --no-rebind
tcp:0 tcp:PORT`. `PORT` must be the existing phone's owner-enabled native mTLS
control listener. There is no app launch, bootstrap broadcast, token read,
package install, device auto-selection or weaker USB authentication. Every task
still enters the same DeviceIngress with current pairing and grant checks.

The selected serial and boot count are checked before creating the forward and
again before connecting. The original service owns the resulting connection;
`usb-status ID` reads its local dispatch identity and `usb-close ID` closes that
original link. Cleanup removes only the exact serial/local/remote forward created
by this instance, never all forwards. If ADB acknowledgement is lost before a
port is known, the source operation stays unknown and is never retried or broadly
cleaned. Reboot/disconnect does not queue a reconnect or replay task mutations.

## Focused checks

```sh
python -m unittest -v test_phone_tasks.PhoneDispatchTests \
  test_phone_tasks.LocalSurfaceTests test_phone_tasks.UsbOwnershipTests
```

These use real temporary native mTLS identity/pairing and SQLite, real loopback
Desk/MCP HTTP, a controlled phone-response fixture and an injected ADB process
fixture. They do not call a model, operate a real phone/ADB device, prove Windows
native behavior, or substitute for an owner-authorized Android integration run.
