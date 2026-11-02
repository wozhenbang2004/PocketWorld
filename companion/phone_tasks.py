#!/usr/bin/env python3
"""Official thin CLI: one selected phone, existing Companion, original task IDs."""
import argparse
import json
import signal
import sys
import threading
import uuid

from phone_task_client import Client, ClientError


def parser():
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--endpoint", required=True, help="Existing local Companion origin, e.g. http://127.0.0.1:8765")
    command.add_argument("--token-file", required=True, help="Existing private Companion bearer file; never sent to browser")
    command.add_argument("--ca-file", help="Explicit owner CA for an HTTPS Companion; certificate/hostname verification stays enabled")
    command.add_argument("--phone", required=True, help="Exact already paired phone device ID; never auto-select another")
    sub = command.add_subparsers(dest="action", required=True)
    sub.add_parser("doctor"); sub.add_parser("list"); sub.add_parser("mcp", help="MCP stdio server (2025-11-25)")
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
    for action in ("status", "wait", "events", "cancel"):
        item = sub.add_parser(action); item.add_argument("operation_id")
        if action == "wait": item.add_argument("--timeout-ms", type=int, default=30000)
        if action == "events": item.add_argument("--cursor"); item.add_argument("--limit", type=int, default=100)
        if action == "cancel": item.add_argument("--cancel-operation-id")
    return command


class SelectedPhoneClient:
    def __init__(self, client, peer): self.client, self.peer = client, peer
    def execute(self, action, params):
        if params.get("peerDeviceId") != self.peer:
            raise ClientError("The CLI remains bound to the explicitly selected phone")
        return self.client.execute(action, params)


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        client = SelectedPhoneClient(Client(args.endpoint, args.token_file, args.ca_file), args.phone)
        if args.action == "mcp":
            from phone_task_mcp import stdio
            stdio(client, sys.stdin.buffer, sys.stdout.buffer)
            return 0
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
        result = client.execute(args.action.replace("-", "_"), params)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 3 if result.get("status") == "unknown" else 0
    except (OSError, ValueError, ClientError) as failure:
        print("Phone task action unavailable: " + str(failure), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
