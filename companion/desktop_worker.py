"""Packaged user-session entry; delegates execution to the original Companion.

Local pause/health work without a phone or public network. This is not a
Session-0 service, a second task host, or another Agent/model loop.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import uuid

from desktop_sessions import WindowsDesktopHealth


def _state(config, value=None):
    value = json.loads(config.read_text(encoding="utf-8")) if value is None else value
    state = Path(value.get("stateDir", "~/.pocketengine/companion")).expanduser().absolute()
    if not state.exists():
        state.mkdir(mode=0o700)  # The explicit operator's existing config parent only.
    if not state.is_dir():
        raise ValueError("Original Companion state path is not a directory")
    if os.name == "nt":
        from platform_windows import WindowsBackend
        with WindowsBackend().directory(str(state)):
            pass
    return state


def _mutex(state, *, probe=False):
    if os.name != "nt":
        raise ValueError("Windows user-session launcher requires Windows")
    from ctypes import wintypes as w
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateMutexW.argtypes, kernel.CreateMutexW.restype = [ctypes.c_void_p, w.BOOL, w.LPCWSTR], w.HANDLE
    name = "Local\\PocketWorldCompanion-" + hashlib.sha256(str(state).casefold().encode()).hexdigest()
    handle = kernel.CreateMutexW(None, not probe, name)
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    exists = ctypes.get_last_error() == 183
    if exists or probe:
        kernel.CloseHandle.argtypes = [w.HANDLE]
        kernel.CloseHandle(handle)
        if exists:
            raise ValueError("An original Companion already owns this user's interactive state; do not start another executor")
        return None
    return handle


def main():
    if sys.argv[1:] == ["--team-mcp-stdio"]:
        # The frozen executable can host its bundled stdio module directly.
        # This mode has only the original job's loopback token, no host config,
        # desktop session, account discovery or second execution manager.
        from team_mcp import main as serve_team_mcp
        serve_team_mcp()
        return
    parser = argparse.ArgumentParser(description="PocketWorld lightweight Windows Companion (user-session only)")
    parser.add_argument("--config", type=Path)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--serve", action="store_true")
    actions.add_argument("--local-stop", action="store_true")
    actions.add_argument("--clear-local-stop", action="store_true")
    actions.add_argument("--health", action="store_true")
    actions.add_argument("--init", action="store_true")
    actions.add_argument("--recover-desktop", action="store_true")
    actions.add_argument("--pairing", action="store_true")
    actions.add_argument("--list-harnesses", action="store_true", help="List installed/configured external harnesses without starting them")
    actions.add_argument("--enable-harness", metavar="WORKER_ID", help="Enable an owner-installed harness in the fixed private configuration while Companion is stopped")
    actions.add_argument("--stream-self-test", action="store_true", help="Encode one synthetic VP8 frame; no desktop, network, config or identity access")
    parser.add_argument("--identity-dir", type=Path)
    parser.add_argument("--manual-takeover-after-os-restart", action="store_true")
    parser.add_argument("--workspace", default=".")
    parser.add_argument("--environment", help="Existing owner environment ID for harness discovery/enablement")
    parser.add_argument("--harness-definition", type=Path, help="Owner-local JSON command definition; never a package installer")
    parser.add_argument("--allow-harness-write", action="store_true", help="Explicitly enable owner-managed external tools in the selected environment")
    parser.add_argument("--development-state", action="store_true", help="Source-tree development only; never allowed by a frozen installed executable")
    args = parser.parse_args()
    if (args.environment or args.harness_definition or args.allow_harness_write) and not (args.list_harnesses or args.enable_harness):
        parser.error("Harness configuration arguments require --list-harnesses or --enable-harness")
    if args.list_harnesses and (args.harness_definition or args.allow_harness_write):
        parser.error("Discovery cannot change a harness definition or write policy")
    if args.enable_harness and not args.environment:
        parser.error("--enable-harness requires the existing --environment ID")
    if args.stream_self_test:
        # This branch runs before installation/config/state/identity initialization.
        # Build smoke checks must neither register a host nor open a desktop/peer.
        try:
            from desktop_stream import runtime_probe
            result = runtime_probe()
        except Exception:
            print(json.dumps({"format": "pocketworld.desktop-stream-probe.v1", "available": False,
                              "reason": "prepared_stream_runtime_unavailable",
                              "networkOpened": False, "desktopCaptured": False}))
            raise SystemExit(2)
        print(json.dumps(result, sort_keys=True))
        return
    if args.health:
        print(json.dumps(WindowsDesktopHealth().probe(), ensure_ascii=False))
        return
    installed, launch = None, None
    if args.development_state:
        if getattr(sys, "frozen", False):
            raise ValueError("Installed executables cannot opt into external development state")
        if args.config is None:
            parser.error("Source development requires an explicit --config")
        config = args.config.expanduser().absolute()
    else:
        from windows_installation import WindowsInstalledContext
        installed = WindowsInstalledContext(args.config, current_executable=sys.executable if getattr(sys, "frozen", False) else None)
        config = Path(installed.config)
    if args.init and installed is not None:
        print(json.dumps(installed.initialize(args.workspace), ensure_ascii=False))
        return
    if args.init:
        # Explicit operator initialization only; builds/installers never mint
        # real user credentials in CI or embed a shared token in an artifact.
        from pocket_companion import initialize
        initialize(config, args.workspace)
        return
    if installed is not None:
        launch = installed.launch_config()
    state = _state(config, launch.json() if launch is not None else None)
    if args.list_harnesses or args.enable_harness:
        if args.manual_takeover_after_os_restart or args.identity_dir is not None:
            raise ValueError("Recovery arguments cannot authorize a harness configuration action")
        import harness_cli
        if args.list_harnesses:
            value = launch.json() if launch is not None else json.loads(config.read_text(encoding="utf-8-sig"))
            print(json.dumps(harness_cli.discover(value, args.environment), ensure_ascii=False))
            return
        definition = None
        if args.harness_definition is not None:
            with args.harness_definition.open("rb") as stream:
                raw_definition = stream.read(65537)
            if len(raw_definition) > 65536:
                raise ValueError("Owner harness definition exceeds 64 KiB")
            definition = json.loads(raw_definition.decode("utf-8-sig"))
        handle = _mutex(state)
        try:
            if installed is not None:
                receipt = harness_cli.enable_installed(installed, launch, args.environment, args.enable_harness,
                                                       definition, args.allow_harness_write)
            else:
                before = config.read_bytes()
                value = harness_cli.enable(json.loads(before.decode("utf-8-sig")), args.environment, args.enable_harness,
                                           definition, args.allow_harness_write)
                harness_cli.write_configuration(config, before, value)
                receipt = {"configurationChanged": True, "environmentId": args.environment, "workerId": args.enable_harness,
                           "packagesInstalled": False, "executablesStarted": False, "restartRequired": True}
            print(json.dumps(receipt, ensure_ascii=False))
            if not receipt.get("configurationChanged"):
                raise SystemExit(2)
        finally:
            if handle is not None:
                from ctypes import wintypes as w
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel.ReleaseMutex.argtypes, kernel.CloseHandle.argtypes = [w.HANDLE], [w.HANDLE]
                kernel.ReleaseMutex(handle); kernel.CloseHandle(handle)
        return
    marker = state / "desktop-local-stop"
    if args.pairing:
        if args.manual_takeover_after_os_restart:
            raise ValueError("Recovery attestation cannot confirm device pairing")
        if installed is not None and args.identity_dir is not None:
            from windows_installation import same_path
            if not same_path(str(args.identity_dir), installed.identity_dir):
                raise ValueError("Installed pairing only uses its registered existing device identity")
        handle = _mutex(state)  # Never open a second manager beside the original --serve.
        try:
            from desktop_pairing import run_native_pairing
            run_native_pairing(config, installed=installed, launch_config=launch, identity_dir=args.identity_dir)
        finally:
            if handle is not None:
                from ctypes import wintypes as w
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel.ReleaseMutex.argtypes, kernel.CloseHandle.argtypes = [w.HANDLE], [w.HANDLE]
                kernel.ReleaseMutex(handle); kernel.CloseHandle(handle)
        return
    if args.recover_desktop:
        _mutex(state, probe=True)
        if installed is not None:
            from windows_installation import same_path
            if args.identity_dir is not None and not same_path(str(args.identity_dir), installed.identity_dir):
                raise ValueError("Installed recovery only uses this installation's original device identity")
            args.identity_dir = Path(installed.identity_dir)
        if args.identity_dir is None or not (args.identity_dir / "certificate.der").is_file() or not (args.identity_dir / "instance-id").is_file():
            raise ValueError("Recovery uses the EXISTING native local identity directory; no new credentials are created")
        from device_identity import LocalIdentity
        from desktop_recovery import prepare_local_request
        from platform_windows import WindowsBackend
        receipt = prepare_local_request(state, LocalIdentity(args.identity_dir), WindowsBackend(), manual_after_os_restart=args.manual_takeover_after_os_restart)
        print(json.dumps(receipt, ensure_ascii=False))
        return
    if args.manual_takeover_after_os_restart or args.identity_dir is not None:
        raise ValueError("Recovery-only arguments cannot authorize an ordinary serve/command action")
    if args.local_stop:
        descriptor = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600) if not marker.exists() else None
        if descriptor is not None:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(json.dumps({"format": "pocketworld.desktop-local-stop.v1", "requestId": str(uuid.uuid4()),
                                         "requestedAt": int(time.time() * 1000), "message": "Local owner paused input; do not auto-resume"}).encode())
                stream.flush(); os.fsync(stream.fileno())
        print("Local desktop stop requested. Previously dispatched input may remain unknown; it will not be replayed.")
        return
    if args.clear_local_stop:
        _mutex(state, probe=True)  # Never clear a running host's pause marker.
        from platform_windows import WindowsBackend
        backend = WindowsBackend()
        raw = backend.read_bytes(str(state), "desktop-local-stop")
        if raw is not None:
            result = backend.delete_local_control_file(str(state), "desktop-local-stop", hashlib.sha256(raw).hexdigest(), len(raw))
            if not result.get("verified"):
                raise ValueError("Original stop-marker deletion was not verified; inspect local state without replaying operations")
        print("Local stop marker cleared while Companion is stopped. Old leases/unknown operations remain fenced; no task resumed.")
        return
    health = WindowsDesktopHealth().probe()
    # Commands can still be useful without GUI health; do not claim the entire
    # computer offline just because its screen is locked/disconnected.
    if not health.get("available"):
        print("Desktop unavailable: " + health.get("reason", "unknown") + "; explicit command capability remains independently probed.", file=sys.stderr)
    handle = _mutex(state)
    try:
        from pocket_companion import main as original_main
        sys.argv = [sys.argv[0], "--config", str(config)]
        original_main(config_override=launch)
    finally:
        if handle is not None:
            from ctypes import wintypes as w
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.ReleaseMutex.argtypes, kernel.CloseHandle.argtypes = [w.HANDLE], [w.HANDLE]
            kernel.ReleaseMutex(handle); kernel.CloseHandle(handle)


if __name__ == "__main__":
    main()
