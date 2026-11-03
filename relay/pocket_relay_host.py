#!/usr/bin/env python3
"""Operator-local setup for the existing opaque relay; never a device authority.

`lease` writes one-hour-or-shorter rendezvous slots for explicit native import.
`companion-route` writes one slot token into the existing private Companion state
and prints a reachability fragment for its existing devices.routes configuration.
`check` is read-only; `run` starts the original TLS relay, on loopback by default.
No command registers a device, grants task permission or starts a model.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.metadata
import ipaddress
import json
import ntpath
import os
from pathlib import Path
import re
import secrets
import ssl
import stat
import sys
import time
from urllib.parse import urlsplit, urlunsplit

import pocket_relay

FORMAT = "pocketworld.relay-lease.v1"
DEVICE_ID = re.compile(r"pw1_[a-f0-9]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}\Z")


class SetupError(ValueError):
    pass


def service_origin(value):
    if (not isinstance(value, str) or len(value) > 512
            or any(ord(char) <= 32 or ord(char) >= 127 for char in value)):
        raise SetupError("Choose an explicit TLS service origin")
    try:
        uri = urlsplit(value)
        valid_port = uri.port is None or 1 <= uri.port <= 65535
    except ValueError as error:
        raise SetupError("Invalid TLS service origin") from error
    if (uri.scheme not in {"wss", "https"} or not uri.hostname or not valid_port
            or uri.username is not None or uri.password is not None or uri.query or uri.fragment
            or uri.path not in {"", "/"} or "\\" in uri.netloc):
        raise SetupError("TLS origin cannot contain credentials, parameters or a route path")
    return urlunsplit(("wss", uri.netloc, "", "", ""))


def _private_directory(path):
    if os.name == "nt":
        raise SetupError("Use the audited POSIX relay operator deployment")
    root = Path(path)
    info = root.stat(follow_symlinks=False)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise SetupError("Use an existing owner-only directory")
    return root


def _write_new(directory, name, value):
    # Unique file creation and no-follow directory/file handles avoid overwriting
    # an unrelated file or following a substituted token pathname.
    directory = _private_directory(directory)
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        file = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=descriptor)
        with os.fdopen(file, "wb") as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
    finally:
        os.close(descriptor)


def _json(value):
    return (json.dumps(value, ensure_ascii=True, indent=2) + "\n").encode("ascii")


def _windows_backend():
    # This standalone operator entry shares the original Companion backend; it
    # never substitutes POSIX mode bits for Windows owner/DACL verification.
    companion = str(Path(__file__).resolve().parents[1] / "companion")
    if companion not in sys.path:
        sys.path.insert(0, companion)
    from platform_windows import WindowsBackend
    return WindowsBackend()


def _windows_lease_bytes(backend, sid, path):
    directory, name = ntpath.split(os.fspath(path))
    if not directory or not name:
        raise SetupError("Use an explicit existing private native lease file")
    # Original backend checks local-drive ancestors, exact file handle, SID,
    # DACL, links/reparse points and size before returning any credential bytes.
    return backend.read_private_bytes(directory, name, sid, maximum=4096)


def create_lease_bundle(service_base, inviter_device_id, output_directory, *, ttl_seconds=900, owner_bucket=""):
    origin = service_origin(service_base)
    if not DEVICE_ID.fullmatch(inviter_device_id):
        raise SetupError("Select the exact native inviter device ID")
    if type(ttl_seconds) is not int or not 60 <= ttl_seconds <= 3600:
        raise SetupError("Rendezvous leases last 60 to 3600 seconds")
    if not isinstance(owner_bucket, str) or owner_bucket and not re.fullmatch(r"[A-Za-z0-9_-]{8,80}", owner_bucket):
        raise SetupError("Invalid operator resource quota bucket")
    route = secrets.token_urlsafe(18)
    a, b = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    if secrets.compare_digest(a, b):
        raise SetupError("Independent relay slots were not generated")
    expires = int(time.time() * 1000) + ttl_seconds * 1000
    config_route = {"routeId": route, "aTokenSha256": hashlib.sha256(a.encode("ascii")).hexdigest(),
                    "bTokenSha256": hashlib.sha256(b.encode("ascii")).hexdigest(), "expiresAt": expires / 1000}
    if owner_bucket:
        config_route["ownerBucket"] = owner_bucket
    common = {"format": FORMAT, "serviceBase": origin, "inviterDeviceId": inviter_device_id,
              "routeId": route, "expiresAt": expires}
    root = Path(output_directory)
    # An existing bundle is never silently rotated or overwritten. Rotation is an
    # explicit operator replacement of the existing relay's private config.
    root.mkdir(mode=0o700)
    _write_new(root, "relay-config.json", _json({"routes": [config_route]}))
    _write_new(root, "endpoint-a.json", _json({**common, "side": "a", "routeToken": a}))
    _write_new(root, "endpoint-b.json", _json({**common, "side": "b", "routeToken": b}))
    return {"format": FORMAT, "relayConfig": str(root / "relay-config.json"),
            "endpointA": str(root / "endpoint-a.json"), "endpointB": str(root / "endpoint-b.json"),
            "expiresAt": expires, "devicePermissionGranted": False}


def read_native_lease(path):
    if os.name == "nt":
        backend = _windows_backend()
        raw = _windows_lease_bytes(backend, backend.logon_context()["userSid"], path)
    else:
        path = Path(path)
        _private_directory(path.parent)
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.getuid() or info.st_mode & 0o077:
                raise SetupError("Native relay lease must be an owner-only regular file")
            raw = source.read(4097)
    return _parse_native_lease(raw)


def _parse_native_lease(raw):
    if not isinstance(raw, bytes) or len(raw) > 4096:
        raise SetupError("Native relay lease exceeds its fixed bound")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise SetupError("Duplicate native relay lease field")
            result[key] = value
        return result
    value = json.loads(raw, object_pairs_hook=unique)
    fields = {"format", "serviceBase", "inviterDeviceId", "routeId", "side", "routeToken", "expiresAt"}
    if not isinstance(value, dict) or set(value) != fields or value["format"] != FORMAT:
        raise SetupError("Use the existing native relay lease schema")
    service_origin(value["serviceBase"])
    if (not DEVICE_ID.fullmatch(value["inviterDeviceId"]) or not re.fullmatch(r"[A-Za-z0-9_-]{22,80}", value["routeId"])
            or value["side"] not in {"a", "b"} or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", value["routeToken"])):
        raise SetupError("Invalid native relay slot")
    now = int(time.time() * 1000)
    if type(value["expiresAt"]) is not int or not now < value["expiresAt"] <= now + 3600000:
        raise SetupError("Native relay lease expired or exceeds one hour")
    return value


def _windows_companion_route(lease_path, existing_state_directory):
    backend = _windows_backend()
    sid = backend.logon_context()["userSid"]
    original = _windows_lease_bytes(backend, sid, lease_path)
    value = _parse_native_lease(original)
    state = os.fspath(existing_state_directory)
    # Installed jobs/state may inherit the already-private installation DACL.
    # Verify its current native owner/allow list; never repair an existing ACL.
    backend.assert_private_directory(state, sid, require_protected=False)
    binding = backend.root_binding(state)
    name = "relay-" + value["routeId"] + "-" + value["side"] + ".token"
    token = (value["routeToken"] + "\n").encode("ascii")

    def current():
        if backend.logon_context()["userSid"] != sid:
            raise SetupError("Original Windows owner session changed")
        backend.assert_private_directory(state, sid, require_protected=False)
        if backend.root_binding(state) != binding:
            raise SetupError("Original Companion state directory changed")
        if not secrets.compare_digest(original, _windows_lease_bytes(backend, sid, lease_path)):
            raise SetupError("Original native relay lease changed")
        _parse_native_lease(original)  # Recheck its short expiry at publication.

    # Hold the existing native directory/ancestor handles during absent-only
    # publication. Failure, including an unknown rename result, never triggers a
    # replacement, deletion, retry or a second Companion state directory.
    with backend.directory(state):
        current()
        backend.create_private_snapshot(state, name, token, maximum=512, current=current)
        current()
        actual = backend.read_private_bytes(state, name, sid, maximum=512)
        if not secrets.compare_digest(token, actual):
            raise SetupError("Original private relay token publication was not confirmed")
    return value


def companion_route(lease_path, peer_device_id, grant_id, existing_state_directory):
    if not DEVICE_ID.fullmatch(peer_device_id) or not IDENTIFIER.fullmatch(grant_id):
        raise SetupError("Use an existing paired peer and its native grant ID")
    if os.name == "nt":
        value = _windows_companion_route(lease_path, existing_state_directory)
    else:
        value = read_native_lease(lease_path)
        state = _private_directory(existing_state_directory)
        name = "relay-" + value["routeId"] + "-" + value["side"] + ".token"
        _write_new(state, name, (value["routeToken"] + "\n").encode("ascii"))
    name = "relay-" + value["routeId"] + "-" + value["side"] + ".token"
    # This reachability fragment is consumed by the original native route parser;
    # it contains no grant document and cannot authorize a task or pairing.
    return {"peerDeviceId": peer_device_id, "grantId": grant_id, "expiresAt": value["expiresAt"],
            "relay": {"baseUrl": service_origin(value["serviceBase"]) + "/v1/tunnel/" + value["routeId"] + "/" + value["side"],
                      "routeTokenSecretRef": {"kind": "state_file", "path": name}}}


def check_configuration(args):
    host = ipaddress.ip_address(args.host)
    if not host.is_loopback and not args.allow_non_loopback:
        raise SetupError("Non-loopback binding requires explicit --allow-non-loopback")
    if not 1 <= args.port <= 65535 or not 1 <= args.reload_seconds <= 60:
        raise SetupError("Listener port or reload interval is outside its bound")
    key = Path(args.key)
    info = key.stat(follow_symlinks=False)
    if os.name == "nt" or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_nlink != 1:
        raise SetupError("Existing TLS key must be an owner-only regular file")
    leases = pocket_relay.load_leases(args.config)
    relay = pocket_relay.ByteRelay(leases)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    version = importlib.metadata.version("websockets")
    if version != "16.0":
        raise SetupError("Install the pinned relay/requirements.txt dependency")
    return {"ready": True, "host": args.host, "port": args.port, "routeCount": len(relay.leases),
            "tlsMinimum": "1.2", "websocketsVersion": version, "devicePermissionGranted": False,
            "publicListenerStarted": False, "longTermDeviceCredentialsCreated": False}


def parser():
    command = argparse.ArgumentParser(description=__doc__)
    actions = command.add_subparsers(dest="action", required=True)
    lease = actions.add_parser("lease", help="Write short native rendezvous imports; no device grant")
    lease.add_argument("--service-base", required=True)
    lease.add_argument("--inviter-device", required=True)
    lease.add_argument("--output-dir", required=True)
    lease.add_argument("--ttl-seconds", type=int, default=900)
    lease.add_argument("--quota-bucket", default="")
    route = actions.add_parser("companion-route", help="Write a short slot token into the existing Companion state")
    route.add_argument("--lease-file", required=True)
    route.add_argument("--peer", required=True)
    route.add_argument("--grant", required=True)
    route.add_argument("--state-dir", required=True)
    for action in ("check", "run"):
        service = actions.add_parser(action)
        service.add_argument("--config", required=True)
        service.add_argument("--cert", required=True)
        service.add_argument("--key", required=True)
        service.add_argument("--host", default="127.0.0.1")
        service.add_argument("--port", type=int, default=8443)
        service.add_argument("--reload-seconds", type=int, default=5)
        service.add_argument("--allow-non-loopback", action="store_true")
    return command


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.action == "lease":
            result = create_lease_bundle(args.service_base, args.inviter_device, args.output_dir,
                                         ttl_seconds=args.ttl_seconds, owner_bucket=args.quota_bucket)
        elif args.action == "companion-route":
            result = companion_route(args.lease_file, args.peer, args.grant, args.state_dir)
        else:
            result = check_configuration(args)
            if args.action == "run":
                asyncio.run(pocket_relay.main_async(args))
                return 0
        print(json.dumps(result, ensure_ascii=True))
        return 0
    except KeyboardInterrupt:
        return 130
    except Exception:
        # File/parser/TLS errors may contain credentials or operator private paths.
        # No traceback or backend exception text is exposed by this local command.
        print("Relay setup rejected; check the native lease, private file permissions and pinned TLS configuration. No automatic retry.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
