#!/usr/bin/env python3
"""Official thin CLI: one selected phone, existing Companion, original task IDs."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import uuid

from phone_task_client import Client, ClientError


def parser():
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--endpoint", help="Existing local Companion origin, e.g. http://127.0.0.1:8766")
    command.add_argument("--token-file", help="Existing private Companion bearer file; never sent to browser")
    command.add_argument("--ca-file", help="Explicit owner CA for an HTTPS Companion; certificate/hostname verification stays enabled")
    command.add_argument("--phone", help="Exact already paired phone device ID; never auto-select another")
    sub = command.add_subparsers(dest="action", required=True)
    sub.add_parser("doctor"); sub.add_parser("connect", help="Verify the selected phone and read its current capabilities; no automatic fallback")
    sub.add_parser("capabilities", help="Read the selected phone's current native capabilities and signed scope receipts")
    sub.add_parser("list"); sub.add_parser("mcp", help="MCP stdio server (2026-07-28 per-request metadata; 2025-11-25 initialize)")
    sub.add_parser("mcp-protocols", help="Show inbound server and outbound Android plugin-client support separately; no phone connection")
    diagnostic = sub.add_parser("diagnostic-export", help="Preview a bounded redacted report from one original task; no uploads")
    diagnostic.add_argument("operation_id")
    diagnostic.add_argument("--output", type=Path, help="New local file; requires terminal confirmation of the exact displayed preview SHA-256")
    pair = sub.add_parser("pair", help="Open the existing native Windows pairing window; both devices still require owner review")
    pair.add_argument("--config", required=True, type=Path, help="Existing native Companion configuration; no identity or token will be provisioned")
    pair.add_argument("--development-state", action="store_true", help="Explicitly use the original source-tree development pairing entry")
    pair.add_argument("--identity-dir", type=Path, help="Existing device identity directory (development state only)")
    desk = sub.add_parser("ui", help="Ephemeral loopback Task Desk and MCP HTTP endpoint"); desk.add_argument("--port", type=int, default=0)
    usb = sub.add_parser("usb-open", help="Owner-selected serial/boot only; same paired mTLS, no app/token bootstrap")
    usb.add_argument("--operation-id"); usb.add_argument("--adb", required=True); usb.add_argument("--serial", required=True)
    usb.add_argument("--boot-count", required=True, type=int); usb.add_argument("--phone-control-port", required=True, type=int); usb.add_argument("--grant", required=True)
    for action in ("usb-status", "usb-close"):
        item = sub.add_parser(action); item.add_argument("operation_id")
    run = sub.add_parser("run")
    run.add_argument("goal"); run.add_argument("--operation-id", default=None)
    run.add_argument("--grant", required=True); run.add_argument("--resource", required=True, help="Exact resource-binding JSON")
    run.add_argument("--capability-version", required=True); run.add_argument("--tools", type=int, default=10)
    run.add_argument("--models", type=int, default=10); run.add_argument("--ttl-ms", type=int, default=600000)
    run.add_argument("--watch", action="store_true", help="After the one submission, stream read-only progress from that operation")
    watch = sub.add_parser("watch", help="Stream NDJSON progress/results from the same operation; Ctrl+C only stops watching")
    watch.add_argument("operation_id"); watch.add_argument("--cursor"); watch.add_argument("--interval", type=float, default=1)
    watch.add_argument("--reconnect", action="store_true", help="Continue read-only queries of this exact phone/operation after a disconnect")
    for action in ("status", "wait", "events", "cancel"):
        item = sub.add_parser(action); item.add_argument("operation_id")
        if action == "wait": item.add_argument("--timeout-ms", type=int, default=30000)
        if action == "events": item.add_argument("--cursor"); item.add_argument("--limit", type=int, choices=range(1, 33), default=32)
        if action == "cancel": item.add_argument("--cancel-operation-id")
    for action in ("acquire-control", "answer", "steer", "resume"):
        item = sub.add_parser(action, help="Use the exact current original task/run/allocation; native approvals stay on phone")
        item.add_argument("operation_id", help="Original goal submission operation ID")
        item.add_argument("--control-operation-id", help="Assigned once before this write; unknown writes are never resent")
        item.add_argument("--task", required=True); item.add_argument("--run", required=True)
        item.add_argument("--epoch", required=True, type=int); item.add_argument("--allocation", required=True)
        if action == "acquire-control":
            item.add_argument("--ttl-ms", type=int, default=30000); item.add_argument("--previous-lease")
        elif action == "resume":
            item.add_argument("--control-lease", required=True)
            item.add_argument("--expected-revision", required=True, type=int,
                              help="Current native task revision; this only requests approval on the phone")
        else:
            item.add_argument("text"); item.add_argument("--control-lease", required=True)
        if action == "answer":
            item.add_argument("--question", required=True, help="Native ordinary question request UUID")
            item.add_argument("--wait-revision", required=True, type=int)
    return command


class SelectedPhoneClient:
    def __init__(self, client, peer):
        import re
        if not isinstance(peer, str) or not re.fullmatch(r"pw1_[a-f0-9]{64}", peer):
            raise ClientError("Select the exact paired phone ID from its native device page")
        self.client, self.peer = client, peer
    def execute(self, action, params):
        if params.get("peerDeviceId") != self.peer:
            raise ClientError("The CLI remains bound to the explicitly selected phone")
        return self.client.execute(action, params)
    def redact_diagnostic_text(self, text):
        return self.client.redact_diagnostic_text(text)


SETTLED = {"succeeded", "failed", "cancelled", "interrupted", "expired", "rejected"}


def watch(client, peer, operation_id, *, cursor=None, interval=1, reconnect=False, emit=None, stop=None):
    """Incremental original event reads. This function never dispatches a mutation."""
    if not isinstance(interval, (int, float)) or not 0.2 <= interval <= 30:
        raise ClientError("Watch interval must be between 0.2 and 30 seconds")
    stop = stop or threading.Event()
    emit = emit or (lambda value: print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True))
    identity, last_snapshot, last_sequence, disconnected, current_revision = None, None, 0, False, 0
    while not stop.is_set():
        try:
            snapshot = client.execute("status", {"peerDeviceId": peer, "operationId": operation_id})
            if snapshot.get("phoneStateUnconfirmed") or snapshot.get("currentRead") == "unavailable":
                raise ClientError("Selected phone is disconnected; the original task state is unconfirmed")
            task = snapshot.get("task")
            if isinstance(task, dict):
                if task.get("executorDeviceId") != peer:
                    raise ClientError("Snapshot belongs to another phone; no device was substituted")
                current_identity = tuple(task.get(field) for field in ("executorDeviceId", "instanceId", "taskId", "runId", "executorEpoch"))
                if identity is not None and current_identity != identity:
                    cursor, last_sequence, current_revision = task.get("eventCursor"), 0, 0
                    emit({"type": "source_changed", "operationId": operation_id, "taskId": task.get("taskId"), "cursor": cursor,
                          "message": "Original task source changed; observing its current snapshot without replay."})
                identity = current_identity
                revision = task.get("revision")
                if type(revision) is not int or revision < current_revision:
                    raise ClientError("The original task snapshot revision regressed; no stale state was applied")
                current_revision = revision
            if disconnected:
                emit({"type": "reconnected", "peerDeviceId": peer, "operationId": operation_id, "automaticRetry": False})
                disconnected = False
            serialized = json.dumps(snapshot, sort_keys=True, separators=(",", ":"))
            if serialized != last_snapshot:
                emit({"type": "snapshot", "operationId": operation_id, "result": snapshot})
                last_snapshot = serialized
            has_more = False
            if isinstance(task, dict):
                # Bound each batch. A busy phone cannot prevent cancellation of the local watcher.
                for _ in range(8):
                    if stop.is_set():
                        return 0
                    parameters = {"peerDeviceId": peer, "operationId": operation_id, "limit": 32}
                    if cursor is not None:
                        parameters["cursor"] = cursor
                    page = client.execute("events", parameters)
                    if page.get("snapshotRequired"):
                        cursor = task.get("eventCursor")
                        emit({"type": "snapshot_required", "operationId": operation_id, "cursor": cursor})
                        break
                    if not isinstance(page.get("events"), list) or len(page["events"]) > 32 or not isinstance(page.get("nextCursor"), str):
                        raise ClientError("Invalid original event page")
                    for event in page["events"]:
                        if (not isinstance(event, dict) or event.get("executorDeviceId") != peer
                                or event.get("taskId") != task.get("taskId") or event.get("instanceId") != task.get("instanceId")):
                            raise ClientError("Event belongs to another original phone task")
                        sequence = event.get("sequence")
                        if type(sequence) is not int or sequence < 1:
                            raise ClientError("Original event sequence is unavailable")
                        if sequence > last_sequence:
                            emit({"type": "event", "operationId": operation_id, "event": event})
                            last_sequence = sequence
                    next_cursor = page["nextCursor"]
                    if page.get("hasMore") and next_cursor == cursor:
                        raise ClientError("Original event cursor did not advance")
                    cursor = next_cursor
                    has_more = page.get("hasMore") is True
                    if not page.get("hasMore"):
                        break
            state = task.get("state") if isinstance(task, dict) else snapshot.get("status")
            if state in SETTLED and not has_more:
                emit({"type": "complete", "operationId": operation_id, "state": state, "result": snapshot})
                return 0 if state == "succeeded" else 4
        except ClientError as failure:
            if not disconnected:
                emit({"type": "disconnected", "peerDeviceId": peer, "operationId": operation_id,
                      "message": str(failure), "nextAction": "query_original_operation", "automaticRetry": False})
            disconnected = True
            if not reconnect or failure.status in {401, 403, 404, 409}:
                return 3
        stop.wait(interval)
    return 0


def pair_native(args):
    """Delegate to the established user-session mutex and native scope review."""
    if os.name != "nt":
        raise ClientError("Native pairing currently requires the Windows Companion owner session. Open its Pair devices entry, approve on both devices, then use connect with the exact phone ID.")
    if args.identity_dir is not None and not args.development_state:
        raise ClientError("Installed pairing uses its registered identity; an identity directory requires explicit development state")
    command = [sys.executable, str(Path(__file__).with_name("desktop_worker.py")), "--pairing", "--config", str(args.config.expanduser().absolute())]
    if args.development_state:
        command.append("--development-state")
    if args.identity_dir is not None:
        command += ["--identity-dir", str(args.identity_dir.expanduser().absolute())]
    # No shell, HTTP invocation, credentials, or new local authority. The native
    # entry itself rejects a second manager beside an already running owner.
    return subprocess.call(command)


def main(argv=None):
    command = parser()
    args = command.parse_args(argv)
    try:
        if args.action == "mcp-protocols":
            from phone_task_mcp import protocol_support
            print(json.dumps(protocol_support(), ensure_ascii=False, separators=(",", ":")))
            return 0
        if args.action == "pair":
            return pair_native(args)
        if not args.endpoint or not args.token_file or not args.phone:
            command.error("This action requires --endpoint, --token-file and --phone. Use pair --help for first connection.")
        client = SelectedPhoneClient(Client(args.endpoint, args.token_file, args.ca_file), args.phone)
        if args.action == "diagnostic-export":
            import hashlib
            from phone_task_diagnostics import collect, preview_bytes, save_reviewed
            report = collect(client, args.phone, args.operation_id)
            raw = preview_bytes(report)
            print(raw.decode("utf-8"), end="", flush=True)
            digest = hashlib.sha256(raw).hexdigest()
            print("Diagnostic preview SHA-256: " + digest, file=sys.stderr, flush=True)
            if args.output is not None:
                print("Local destination: " + str(args.output.expanduser().absolute()), file=sys.stderr, flush=True)
                if not sys.stdin.isatty():
                    raise ClientError("Review the preview in a terminal before exporting; no file was written")
                print("To export exactly this preview, type its full SHA-256 (anything else cancels): ", file=sys.stderr, end="", flush=True)
                confirmed = sys.stdin.readline().strip()
                if confirmed != digest:
                    print("Export cancelled; no file was written.", file=sys.stderr)
                    return 2
                save_reviewed(raw, args.output, confirmed)
                print("Diagnostic exported to the reviewed local destination. Nothing was uploaded.", file=sys.stderr)
            return 0 if report["outcome"] == "captured" and not report["partial"] else 3
        if args.action == "mcp":
            from phone_task_mcp import stdio
            stdio(client, sys.stdin.buffer, sys.stdout.buffer)
            return 0
        if args.action == "watch":
            return watch(client, args.phone, args.operation_id, cursor=args.cursor, interval=args.interval, reconnect=args.reconnect)
        if args.action == "ui":
            from phone_task_desk import TaskDesk
            desk = TaskDesk(client, args.phone, args.port)
            print(desk.start(), flush=True)  # Session capability in a fragment, never phone/Companion token.
            stop = threading.Event()
            signal.signal(signal.SIGINT, lambda *_: stop.set())
            signal.signal(signal.SIGTERM, lambda *_: stop.set())
            try: stop.wait()
            finally: desk.close()
            return 0
        params = {"peerDeviceId": args.phone}
        if args.action == "usb-open":
            operation_id = args.operation_id or str(uuid.uuid4())
            print("Original USB operation: " + operation_id, file=sys.stderr, flush=True)
            params.update(operationId=operation_id, grantId=args.grant, adbPath=args.adb, serial=args.serial, bootCount=args.boot_count, phoneControlPort=args.phone_control_port)
        elif args.action in {"usb-status", "usb-close"}:
            params["operationId"] = args.operation_id
        if args.action == "run":
            operation_id = args.operation_id or str(uuid.uuid4())
            print("Original operation: " + operation_id, file=sys.stderr, flush=True)
            params.update(operationId=operation_id, goal=args.goal, grantId=args.grant, resourceBinding=json.loads(args.resource),
                          capabilityVersion=args.capability_version, toolCalls=args.tools, modelCalls=args.models, ttlMs=args.ttl_ms)
        elif args.action in {"status", "wait", "events", "cancel"}:
            params["operationId"] = args.operation_id
            if args.action == "wait": params["timeoutMs"] = args.timeout_ms
            if args.action == "events":
                params["limit"] = args.limit
                if args.cursor is not None: params["cursor"] = args.cursor
            if args.action == "cancel":
                params["cancelOperationId"] = args.cancel_operation_id or str(uuid.uuid4())
                print("Original cancellation: " + params["cancelOperationId"], file=sys.stderr, flush=True)
        elif args.action in {"acquire-control", "answer", "steer", "resume"}:
            control_id = args.control_operation_id or str(uuid.uuid4())
            print("Original control operation: " + control_id, file=sys.stderr, flush=True)
            params.update(operationId=args.operation_id, controlOperationId=control_id, taskId=args.task, runId=args.run,
                          executorEpoch=args.epoch, allocationId=args.allocation)
            if args.action == "acquire-control":
                params["ttlMs"] = args.ttl_ms
                if args.previous_lease is not None: params["previousLeaseId"] = args.previous_lease
            elif args.action == "resume":
                params.update(controlLeaseId=args.control_lease, expectedRevision=args.expected_revision)
            else:
                params.update(controlLeaseId=args.control_lease, text=args.text)
            if args.action == "answer": params.update(originalRequestId=args.question, waitRevision=args.wait_revision)
        result = client.execute("doctor" if args.action in {"connect", "capabilities"} else args.action.replace("-", "_"), params)
        if args.action == "acquire-control" and result.get("status") == "succeeded" and result.get("effectState") != "unknown":
            # Acquiring/renewing a lease changes the original TaskStore revision.
            # Return its subsequent snapshot for an explicitly reviewed resume.
            try:
                latest = client.execute("status", {"peerDeviceId":args.phone, "operationId":params["operationId"]})
                if isinstance(latest.get("task"), dict) and not latest.get("phoneStateUnconfirmed"):
                    result["task"] = latest["task"]
                else:
                    result["currentRead"] = "unavailable"
            except ClientError:
                result["currentRead"] = "unavailable"
        print(json.dumps(result, ensure_ascii=False, indent=2))
        unconfirmed = (result.get("status") == "unknown" or result.get("effectState") == "unknown"
                       or result.get("currentRead") == "unavailable" or result.get("phoneStateUnconfirmed") is True)
        if not unconfirmed and (result.get("status") == "rejected" or result.get("accepted") is False):
            return 4
        if args.action == "run" and args.watch:
            return watch(client, args.phone, params["operationId"])
        return 3 if unconfirmed else 0
    except KeyboardInterrupt:
        print("Stopped local observation. Phone tasks were not cancelled or resubmitted.", file=sys.stderr)
        return 130
    except (OSError, ValueError, ClientError) as failure:
        print("Phone task action unavailable: " + (str(failure) if isinstance(failure, ClientError) else type(failure).__name__), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
