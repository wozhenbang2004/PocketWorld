#!/usr/bin/python3 -I
"""Owner-installed Companion command, not an Android interpreter or a task scheduler.

Install explicitly as one allowlisted command in a POSIX local Companion environment.
The administrator controls the policy, immutable runtime image and this executable;
Skill code controls none of those. No shell, package installation, network fallback,
automatic retry or task database. Uses bubblewrap/libseccomp's published APIs only.
"""
import base64
import ctypes
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import selectors
import signal
import stat
import struct
import subprocess
import sys
import time

FORMAT = "pocketworld.skill-canonical-bwrap.v1"
POLICY = ".pwskill-policy.json"
READY = ".pwskill-ready.json"
MAX_PACKAGE = 131072
MAX_WORKSPACE = 64 * 1024 * 1024
DENIED_CALLS = ("socket", "socketpair", "connect", "accept", "accept4", "bind", "listen",
                 "sendto", "sendmsg", "sendmmsg", "recvfrom", "recvmsg", "recvmmsg",
                 "getsockopt", "setsockopt", "shutdown", "socketcall", "io_uring_setup", "fork", "vfork", "clone", "clone3")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def read_regular(path, maximum=262144):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
            raise ValueError("Only bounded regular files are accepted")
        value = stream.read(maximum + 1)
        if len(value) > maximum:
            raise ValueError("File exceeds the byte budget")
        return value


def atomic(path, value):
    target = Path(path)
    pending = target.with_name(target.name + ".pending-" + str(os.getpid()))
    fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(pending, target)
        directory = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        try:
            pending.unlink()
        except FileNotFoundError:
            pass


def relative(value, root=False, rule=False):
    if root and value == ".":
        return value
    if not isinstance(value, str) or not 1 <= len(value) <= 240 or any(ord(c) < 32 or 127 <= ord(c) <= 159 or 0xD800 <= ord(c) <= 0xDFFF for c in value):
        raise ValueError("Noncanonical relative path")
    if len(value.encode("utf-16-be")) // 2 > 240:
        raise ValueError("Path exceeds the canonical Java UTF-16 budget")
    checked = value[:-1] if rule and value.endswith("/") else value
    if checked.startswith("/") or any(c in checked for c in "\\:*?") or any(part in {"", ".", ".."} for part in checked.split("/")):
        raise ValueError("Path traversal is forbidden")
    return value



def trusted_directory(value):
    path = Path(value)
    if not path.is_absolute() or str(path.resolve(strict=True)) != str(path) or not path.is_dir():
        raise ValueError("A fixed existing directory without symlinks is required")
    return path


def policy():
    if sys.platform != "linux" or not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("This launcher requires Linux/POSIX, bubblewrap and libseccomp")
    raw = read_regular(POLICY)
    config = json.loads(raw)
    for key in ("installationId", "grantIdentity", "ownerId", "spaceId", "workspaceId", "runtime", "helperSha256", "runtimeImageDigest", "runtimeSha256", "runtimeVersion", "bwrapSha256"):
        if not isinstance(config.get(key), str) or not config[key] or len(config[key]) > 200:
            raise ValueError("Missing fixed installation field: " + key)
    if config["helperSha256"] != digest(read_regular(Path(__file__).resolve(), 262144)):
        raise ValueError("The installed launcher changed")
    config["runtimeRoot"] = trusted_directory(config["runtimeRoot"])
    config["workspaceRoot"] = trusted_directory(config["workspaceRoot"])
    config["stateRoot"] = trusted_directory(config["stateRoot"])
    config["spoolRoot"] = trusted_directory(config["spoolRoot"])
    if config["spoolRoot"] != Path.cwd().resolve():
        raise ValueError("Companion environment root changed")
    roots = [config[k] for k in ("runtimeRoot", "workspaceRoot", "stateRoot", "spoolRoot")]
    if any(a == b or a in b.parents or b in a.parents for i, a in enumerate(roots) for b in roots[i + 1:]):
        raise ValueError("Runtime, workspace, state and transport spool must be separate")
    if stat.S_IMODE(config["stateRoot"].stat().st_mode) & 0o077:
        raise ValueError("Executor receipt state must be private (0700)")
    # Untrusted writes go only to this execution-copy filesystem. A bounded tmpfs prevents a script
    # from filling the owner's project disk by creating many individually small files. This scratch
    # receipt may vanish on reboot; Companion's durable original-operation ledger still forbids replay.
    state_filesystem(config["stateRoot"])
    config["bwrap"] = str(Path(config["bwrap"]).resolve(strict=True))
    if digest(read_regular(config["bwrap"], 4 * 1024 * 1024)) != config["bwrapSha256"]:
        raise ValueError("The pinned bubblewrap executable changed")
    if config["runtime"] != "python3" or config.get("launcher") != ["/usr/bin/python3", "-I", "-B"]:
        raise ValueError("This version supports only the fixed Python3 isolated launcher")
    if config.get("processPolicy") != "single_process":
        raise ValueError("This deployment supports one process without child processes or threads")
    if not 1 <= config.get("maxSeconds", 0) <= 120 or not 1 <= config.get("maxOutputBytes", 0) <= 65536:
        raise ValueError("Invalid execution limits")
    if not isinstance(config.get("grantExpiresAt"), int) or config["grantExpiresAt"] <= int(time.time() * 1000):
        raise ValueError("Executor grant expired")
    validate_layout(config, config["workspaceRoot"])
    config["policySha256"] = digest(raw)
    return config


def state_filesystem(root):
    descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        libc.fstatfs.argtypes = [ctypes.c_int, ctypes.c_void_p]
        libc.fstatfs.restype = ctypes.c_int
        data = ctypes.create_string_buffer(512)
        if libc.fstatfs(descriptor, data) != 0 or ctypes.c_long.from_buffer(data).value != 0x01021994:  # TMPFS_MAGIC, Linux
            raise ValueError("Execution copies require an administrator-provisioned bounded tmpfs")
        space = os.fstatvfs(descriptor)
        if space.f_blocks * space.f_frsize > 256 * 1024 * 1024:
            raise ValueError("Execution-copy tmpfs exceeds the fixed 256MiB filesystem ceiling")
        if not 1 <= space.f_files <= 8192:
            raise ValueError("Execution-copy tmpfs needs an explicit inode ceiling of at most8192")
    finally:
        os.close(descriptor)


def tree(root, destination=None, maximum=MAX_WORKSPACE, max_files=2048, runtime=False, read_rules=None):
    """Descriptor-relative no-follow reads. The workspace snapshot has no symlink, device or hardlink."""
    total = 0
    entries = []
    for directory, folders, files, fd in os.fwalk(root, follow_symlinks=False):
        folders.sort()
        files.sort()
        for name in folders + files:
            info = os.stat(name, dir_fd=fd, follow_symlinks=False)
            path = Path(directory, name).relative_to(root).as_posix()
            if len(entries) >= max_files:
                raise ValueError("Filesystem entry limit exceeded")
            target = Path(destination, path) if destination is not None else None
            if stat.S_ISDIR(info.st_mode):
                entries.append([path, "d", ""])
                if target is not None and (read_rules is None or any(rule.endswith("/") and (path == rule[:-1] or path.startswith(rule)) for rule in read_rules)):
                    target.mkdir(mode=0o700, parents=True, exist_ok=True)
            elif stat.S_ISREG(info.st_mode):
                if not runtime and info.st_nlink != 1:
                    raise ValueError("Workspace hardlinks are unsupported")
                size = min(maximum - total, 16 * 1024 * 1024 if not runtime else maximum)
                child = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                with os.fdopen(child, "rb") as stream:
                    opened = os.fstat(stream.fileno())
                    if not stat.S_ISREG(opened.st_mode) or opened.st_ino != info.st_ino or opened.st_dev != info.st_dev or opened.st_size > size:
                        raise ValueError("Filesystem changed or exceeds the byte limit")
                    value = stream.read(size + 1)
                    if len(value) > size:
                        raise ValueError("Filesystem exceeds the byte limit")
                total += len(value)
                entries.append([path, "f", digest(value)])
                if target is not None and (read_rules is None or matches(read_rules, path)):
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    target.write_bytes(value)
                    target.chmod(0o600)
            elif runtime and stat.S_ISLNK(info.st_mode):
                # Runtime symlinks resolve inside the new read-only root; the host tree is administrator-owned.
                entries.append([path, "l", os.readlink(name, dir_fd=fd)])
            else:
                raise ValueError("Symlinks and special filesystem entries are unsupported")
    return "sha256:" + digest(canonical(sorted(entries)))


def matches(rules, path):
    return any(rule == path or rule.endswith("/") and path.startswith(rule) for rule in rules)


def validate_layout(config, workspace):
    for name in ("readPaths", "writePaths"):
        values = config.get(name)
        if not isinstance(values, list) or len(values) > 16 or values != sorted(set(values)):
            raise ValueError("Invalid exact-file/subtree rules")
        for rule in values:
            relative(rule, rule=True)
            path = Path(workspace, rule.rstrip("/"))
            if path.is_symlink() or path.exists() and (path.is_dir() != rule.endswith("/")):
                raise ValueError("A non-slash rule is one exact regular file, never a directory")
            if name == "readPaths" and not path.exists():
                raise ValueError("Reviewed read source is missing")
    outputs = config.get("outputPaths")
    if not isinstance(outputs, list) or len(outputs) > 8 or outputs != sorted(set(outputs)):
        raise ValueError("At most eight exact output files are supported")
    for path in outputs:
        relative(path)
        if not matches(config["writePaths"], path):
            raise ValueError("Exact output file exceeds write grant")
    cwd = config.get("workingDirectory")
    relative(cwd, root=True)
    path = Path(workspace) if cwd == "." else Path(workspace, cwd)
    if not path.is_dir() or path.is_symlink():
        raise ValueError("Working directory is missing or not a directory")


def operation_key(review, grant):
    run, backend = review["run"], review["backend"]
    return digest("\n".join(["pwskill-canonical-v1", run["ownerId"], run["spaceId"], run["taskId"], run["runId"], str(run["epoch"]),
                              grant["operationId"], backend["backendId"], backend["installationId"]]).encode())


def validate_bundle(config, raw):
    bundle = json.loads(raw)
    p, grant = bundle["review"], bundle["grant"]
    if bundle.get("format") != "pocketworld.skill-canonical-bundle.v1" or p.get("format") != "pocketworld.skill-execution.v1":
        raise ValueError("Only the canonical W04 nested review protocol is accepted")
    # Hash EXACT bytes emitted by canonical SkillExecution.canonicalReviewBytes(), rather than
    # guessing another JSON library's quoting of slash/Unicode/control characters.
    encoded = base64.b64decode(bundle["canonicalReviewBase64"], validate=True)
    unsigned = dict(p); unsigned.pop("planDigest", None)
    if json.loads(encoded) != unsigned or digest(encoded) != p["planDigest"]:
        raise ValueError("Canonical nested review digest mismatch")
    backend_raw = base64.b64decode(bundle["canonicalBackendBase64"], validate=True)
    b, q, run = p["backend"], p["request"], p["run"]
    required_fences = {"EXACT_BYTES", "EXACT_ARGV", "EMPTY_ENVIRONMENT", "FILE_READS", "FILE_WRITES", "NETWORK_DENY", "OUTPUT_LIMIT", "TIMEOUT", "CANCELLATION"}
    if set(b["fences"]) != required_fences:
        raise ValueError("Canonical environment enforcement set is incomplete")
    if json.loads(backend_raw) != b or digest(backend_raw) != grant["environmentBinding"]:
        raise ValueError("Canonical backend grant fingerprint mismatch")
    if b.get("backendId") != "companion-skill-canonical-v1" or b.get("enforcementId") != "sha256:" + config["policySha256"]:
        raise ValueError("Wrong pinned executor policy")
    if b.get("workspaceMode") != "isolated_copy" or b.get("fileResultsRequireImport") is not True:
        raise ValueError("Execution copy semantics must be included in the original approval")
    if b.get("processPolicy") != "single_process" or config.get("processPolicy") != "single_process":
        raise ValueError("Single-process policy must be included in the original approval")
    for name in ("installationId", "runtimeSha256", "runtimeVersion"):
        if b[name] != config[name]:
            raise ValueError("Runtime binary/version/installation changed")
    if b["runtimeId"] != config["runtime"] or b["runtimeImageSha256"] != config["runtimeImageDigest"] or b["runtimePrefix"] != config["launcher"]:
        raise ValueError("Runtime image is not the approved binary or prefix")
    if (run["ownerId"], run["spaceId"], b["workspaceId"]) != (config["ownerId"], config["spaceId"], config["workspaceId"]):
        raise ValueError("Native scope/workspace changed")
    for name in ("readPaths", "writePaths", "outputPaths", "workingDirectory"):
        if q[name] != config[name]:
            raise ValueError("Exact-file/subtree/output layout differs from the pinned deployment")
    if q["runtimeId"] != b["runtimeId"] or q["workspaceId"] != b["workspaceId"]:
        raise ValueError("Requested runtime/workspace mismatch")
    for name in ("taskId", "runId", "epoch"):
        if run[name] != grant[name]:
            raise ValueError("Grant belongs to another native run")
    for value in (grant["operationId"], grant["taskId"], grant["runId"]):
        if not isinstance(value, str) or re.fullmatch(r"[a-f0-9-]{36}", value) is None:
            raise ValueError("Native operation/run identifier is invalid")
    if grant["planDigest"] != p["planDigest"] or grant["backendGrantId"] != config["grantIdentity"]:
        raise ValueError("Wrong exact approval/backend grant")
    now = int(time.time() * 1000)
    if not now < grant["expiresAt"] <= p["expiresAt"] <= now + 120000 or now >= config["grantExpiresAt"]:
        raise ValueError("Expired or overlong canonical review/grant")
    if not 1 <= q["timeoutSeconds"] <= config["maxSeconds"] or not 1 <= q["maxOutputBytes"] <= config["maxOutputBytes"] or not 2048 <= q["maxResultChars"] <= 32000:
        raise ValueError("Execution/result budget exceeds grant")
    if (q["network"], q["environment"], q["stdin"]) != ("deny", "empty", "closed") or p.get("packageReadOnly") is not True:
        raise ValueError("Unsupported confinement policy")
    if b["workspaceRoot"] != "/skill-workspace" or b["packageRoot"] != "/skill-package":
        raise ValueError("Unsupported immutable mount layout")
    script = relative(q["scriptPath"])
    if not script.startswith("scripts/") or p["argv"] != config["launcher"] + [b["packageRoot"] + "/" + script] + q["arguments"]:
        raise ValueError("Exact argv/script mismatch")
    cwd = b["workspaceRoot"] + ("" if q["workingDirectory"] == "." else "/" + q["workingDirectory"])
    if p["cwd"] != cwd:
        raise ValueError("Exact working directory mismatch")
    files = {relative(path): base64.b64decode(value, validate=True) for path, value in bundle["resources"].items()}
    expected = {item["path"]: item for item in p["resources"]}
    if len(files) > 41 or sum(map(len, files.values())) > MAX_PACKAGE or set(files) != set(expected) or script not in files:
        raise ValueError("Complete package resource set/budget mismatch")
    for name, value in files.items():
        if digest(value) != expected[name]["sha256"] or len(value) != expected[name]["bytes"]:
            raise ValueError("Exact resource bytes changed")
    package = hashlib.sha256()
    for path in sorted(files):
        package.update(path.encode() + b"\0" + files[path] + b"\0")
    if package.hexdigest() != p["package"]["digest"] or digest(files[script]) != p["scriptSha256"]:
        raise ValueError("Complete package/script digest mismatch")
    key = operation_key(p, grant)
    if bundle.get("operationKey") != key:
        raise ValueError("Original native operation key mismatch")
    return p, key, files


def seccomp_fd():
    # Native-architecture libseccomp filter. Unsupported architectures are killed by libseccomp's bad-arch action.
    lib = ctypes.CDLL("libseccomp.so.2", use_errno=True)
    lib.seccomp_init.argtypes = [ctypes.c_uint32]
    lib.seccomp_init.restype = ctypes.c_void_p
    lib.seccomp_release.argtypes = [ctypes.c_void_p]
    lib.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    lib.seccomp_syscall_resolve_name.restype = ctypes.c_int
    lib.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    lib.seccomp_rule_add.restype = ctypes.c_int
    lib.seccomp_export_bpf.argtypes = [ctypes.c_void_p, ctypes.c_int]
    lib.seccomp_export_bpf.restype = ctypes.c_int
    context = lib.seccomp_init(0x7fff0000)  # SCMP_ACT_ALLOW
    if not context:
        raise ValueError("libseccomp cannot create a filter")
    fd = os.memfd_create("pwskill-no-network", 0)
    try:
        for name in DENIED_CALLS:
            number = lib.seccomp_syscall_resolve_name(name.encode())
            if number >= 0 and lib.seccomp_rule_add(context, 0x00050000 | errno.EPERM, number, 0) < 0:
                raise ValueError("libseccomp rejected a mandatory deny rule")
        if lib.seccomp_export_bpf(context, fd) < 0:
            raise ValueError("Cannot export the network deny filter")
        os.lseek(fd, 0, os.SEEK_SET)
        return fd
    except BaseException:
        os.close(fd)
        raise
    finally:
        lib.seccomp_release(context)


def sandbox_argv(config, package, workspace, p, filter_fd):
    q, b = p["request"], p["backend"]
    args = [config["bwrap"], "--die-with-parent", "--new-session", "--unshare-all", "--unshare-user", "--disable-userns", "--assert-userns-disabled",
            "--cap-drop", "ALL", "--clearenv", "--ro-bind", str(config["runtimeRoot"]), "/", "--ro-bind", str(package), b["packageRoot"],
            "--tmpfs", b["workspaceRoot"]]
    scopes = [(path, False) for path in q["readPaths"]] + [(path, True) for path in q["writePaths"]]
    scopes.sort(key=lambda item: (item[0].rstrip("/").count("/"), item[1]))
    for rule, writable in scopes:
        relative(rule, rule=True)
        path = rule.rstrip("/")
        source = Path(workspace, path)
        if not source.exists() or source.is_symlink() or source.is_dir() != rule.endswith("/"):
            raise ValueError("Exact-file/subtree mount type mismatch")
        args += ["--bind" if writable else "--ro-bind", str(source), b["workspaceRoot"] + "/" + path]
    args += ["--remount-ro", b["workspaceRoot"], "--chdir", p["cwd"], "--seccomp", str(filter_fd), "--", *p["argv"]]
    return args


def limits():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
    resource.setrlimit(resource.RLIMIT_FSIZE, (16 * 1024 * 1024, 16 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 1024 * 1024, 1024 * 1024 * 1024))
    # Fork/clone are denied in the runtime seccomp filter. Do not apply a per-user process-count
    # limit to trusted bwrap setup: an ordinary desktop may already have many unrelated threads.


def supervise(argv, filter_fd, seconds, budget, cancelled):
    process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env={}, close_fds=True, pass_fds=(filter_fd,), start_new_session=True, preexec_fn=limits)
    output = bytearray()
    truncated = False
    state = None
    until = time.monotonic() + seconds
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while selector.get_map() or process.poll() is None:
            if state is None and (cancelled() or time.monotonic() >= until):
                state = "CANCELLED" if cancelled() else "TIMED_OUT"
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            for event, _ in selector.select(0.02):
                value = os.read(event.fileobj.fileno(), 4096)
                if not value:
                    selector.unregister(event.fileobj)
                    continue
                room = budget - len(output)
                output.extend(value[:room])
                truncated |= len(value) > room
            if time.monotonic() > until + 2:
                raise TimeoutError("Process-tree or output pipe did not stop")
        code = process.wait(timeout=1)
        return state or ("COMPLETED" if code == 0 else "FAILED"), code, bytes(output), truncated
    finally:
        selector.close()
        process.stdout.close()
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=2)


def export_artifacts(config, p, key, workspace, remaining, environment_binding):
    q = p["request"]
    manifest = {"format": "pocketworld.skill-artifacts.v1", "operationKey": key, "operationId": environment_binding["operationId"],
                "planDigest": p["planDigest"], "environmentBinding": environment_binding["environmentBinding"], "installationId": config["installationId"],
                "outputPaths": q["outputPaths"], "workspaceMode": "isolated_copy", "canonicalWorkspaceModified": False, "files": [], "truncated": False, "missingPaths": []}
    for name in q["outputPaths"]:
        relative(name)
        if workspace is None:
            manifest["missingPaths"].append(name)
            continue
        path = Path(workspace)
        for part in name.split("/"):
            path = path / part
            if path.is_symlink():
                raise ValueError("Exact output contains a symbolic link")
        if not path.exists():
            manifest["missingPaths"].append(name)
            continue
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError("Exact output is not one regular file")
        if info.st_size > remaining:
            manifest["truncated"] = True
            continue
        value = read_regular(path, remaining); remaining -= len(value)
        manifest["files"].append({"path": name, "sha256": digest(value), "bytes": len(value), "base64": base64.b64encode(value).decode("ascii")})
    raw = canonical(manifest)
    if len(raw) > 262144:
        raise ValueError("Artifact manifest exceeds transfer budget")
    name = ".pwskill-result-" + key + ".json"
    atomic(config["spoolRoot"] / name, raw)
    return name, digest(raw), manifest["truncated"]


def invalidate_ready():
    """Invalidate only the fixed marker in the explicitly configured administrator-check cwd."""
    directory = os.open(".", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        try:
            os.unlink(READY, dir_fd=directory)
        except FileNotFoundError:
            return
        os.fsync(directory)
    finally:
        os.close(directory)


def check(config):
    """An explicit administrator check, never triggered by a model request or inspect()."""
    root_hash = tree(config["runtimeRoot"], maximum=2 * 1024 * 1024 * 1024, max_files=50000, runtime=True)
    if root_hash != "sha256:" + config["runtimeImageDigest"]:
        raise ValueError("Pinned runtime image digest changed")
    workspace_hash = tree(config["workspaceRoot"])
    binary_hash = digest(read_regular(config["runtimeRoot"] / "usr/bin/python3", 64 * 1024 * 1024))
    if binary_hash != config["runtimeSha256"]:
        raise ValueError("The actual runtime binary SHA differs from its image SHA")
    validate_layout(config, config["workspaceRoot"])
    fd = seccomp_fd()
    try:
        # Fixed non-user program: verify empty environment, denied sockets (including loopback/Unix),
        # no host /proc or /dev, a read-only runtime/package, and real user/PID namespace setup.
        probe = "import os,socket; assert set(os.environ) <= {'LC_CTYPE'}; " \
                "assert not os.path.exists('/proc/self/environ'); " \
                "assert not os.path.exists('/dev/tty'); " \
                "\ntry: socket.socket(); raise AssertionError('network allowed')\nexcept PermissionError: pass\n" \
                "\ntry: open('/.pwskill-probe','w'); raise AssertionError('runtime writable')\nexcept OSError: pass\n" \
                "import json,sys; print(json.dumps({'marker':'pwskill-probe-ok','version':sys.version.split()[0]}))"
        p = {"request": {"readPaths": [], "writePaths": []}, "backend": {"workspaceRoot": "/skill-workspace", "packageRoot": "/skill-package"},
             "cwd": "/skill-workspace", "argv": [*config["launcher"], "-c", probe]}
        state, code, output, _ = supervise(sandbox_argv(config, config["spoolRoot"], config["workspaceRoot"], p, fd), fd, 3, 4096, lambda: False)
        expected_probe = {"marker": "pwskill-probe-ok", "version": config["runtimeVersion"]}
        if state != "COMPLETED" or code != 0 or json.loads(output) != expected_probe:
            raise ValueError("Actual bubblewrap confinement probe failed: " + output.decode(errors="replace")[:300])
    finally:
        os.close(fd)
    now = int(time.time() * 1000)
    ready = {"format": FORMAT, "available": True, "installationId": config["installationId"], "helperSha256": config["helperSha256"],
             "policySha256": config["policySha256"], "runtimeImageDigest": config["runtimeImageDigest"], "runtimeSha256": binary_hash,
             "runtimeVersion": config["runtimeVersion"], "workspaceRevision": workspace_hash,
             "generatedAt": now, "expiresAt": min(config["grantExpiresAt"], now + 86400000)}
    atomic(READY, canonical(ready))
    return ready


def execute(config, path, expected_sha):
    if not isinstance(path, str) or re.fullmatch(r"\.pwskill-[a-f0-9]{64}\.json", path) is None:
        raise ValueError("Only the original operation's flat staged bundle is accepted")
    if not isinstance(expected_sha, str) or len(expected_sha) != 64 or any(c not in "0123456789abcdef" for c in expected_sha):
        raise ValueError("Invalid exact bundle hash")
    raw = read_regular(path)
    if digest(raw) != expected_sha:
        raise ValueError("Staged bundle bytes changed after transfer")
    p, key, files = validate_bundle(config, raw)
    grant = json.loads(raw)["grant"]
    q, b = p["request"], p["backend"]
    if path != ".pwskill-" + key + ".json":
        raise ValueError("Staged filename does not match the native operation")
    location = config["stateRoot"] / key
    location.mkdir(mode=0o700, exist_ok=True)
    if location.is_symlink():
        raise ValueError("Executor state cannot be a symbolic link")
    lock = os.open(location / "lock", os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        receipt_file = location / "receipt.json"
        if receipt_file.exists():
            previous = json.loads(read_regular(receipt_file))
            if previous["planDigest"] != p["planDigest"]:
                raise ValueError("Operation already belongs to another exact plan")
            return previous  # Prepared/interrupted remains UNKNOWN; never launch again.
        receipt = {"format": "pocketworld.skill-receipt.v1", "operationKey": key, "operationId": grant["operationId"], "planDigest": p["planDigest"], "environmentBinding": grant["environmentBinding"],
                   "installationId": config["installationId"], "state": "UNKNOWN", "exitCode": None, "outputBase64": "", "outputSha256": digest(b""), "truncated": False,
                   "workspaceMode": "isolated_copy", "canonicalWorkspaceModified": False}
        atomic(receipt_file, canonical(receipt))  # Executor receipt reservation BEFORE filesystem staging or process creation.
        cancel_file = ".pwskill-cancel-" + key
        def cancelled():
            try:
                marker = read_regular(cancel_file, 64).decode("ascii")
                if marker != p["planDigest"]:
                    raise ValueError("Cancellation marker belongs to another plan")
                return True
            except FileNotFoundError:
                return False
        workspace = None
        if cancelled():
            receipt["state"] = "CANCELLED"
        else:
            package, workspace = location / "package", location / "workspace"
            package.mkdir(mode=0o700)
            workspace.mkdir(mode=0o700)
            for name, value in files.items():
                target = package / name
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                target.write_bytes(value)
                target.chmod(0o400)
            revision = tree(config["workspaceRoot"], workspace, read_rules=q["readPaths"])
            if revision != b["workspaceRevision"]:
                raise ValueError("Workspace bytes changed after owner review")
            if tree(config["runtimeRoot"], maximum=2 * 1024 * 1024 * 1024, max_files=50000, runtime=True) != "sha256:" + b["runtimeImageSha256"]:
                raise ValueError("Runtime image bytes changed after owner review")
            if digest(read_regular(config["runtimeRoot"] / "usr/bin/python3", 64 * 1024 * 1024)) != b["runtimeSha256"]:
                raise ValueError("Runtime binary changed after approval")
            # Mount only approved paths. Never copy original write-only contents into the execution view.
            for rule in q["readPaths"]:
                if rule.endswith("/"):
                    (workspace / rule[:-1]).mkdir(mode=0o700, parents=True, exist_ok=True)
            for rule in q["writePaths"]:
                target = workspace / rule.rstrip("/")
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                if rule.endswith("/"):
                    target.mkdir(mode=0o700, exist_ok=True)
                elif not target.exists():
                    target.write_bytes(b"")
            (workspace if q["workingDirectory"] == "." else workspace / q["workingDirectory"]).mkdir(mode=0o700, parents=True, exist_ok=True)
            fd = seccomp_fd()
            try:
                argv = sandbox_argv(config, package, workspace, p, fd)
                remaining = min(q["timeoutSeconds"], (min(p["expiresAt"], grant["expiresAt"], config["grantExpiresAt"]) - int(time.time() * 1000)) / 1000)
                if cancelled() or remaining <= 0:
                    receipt["state"] = "CANCELLED" if cancelled() else "TIMED_OUT"
                else:
                    state, code, output, truncated = supervise(argv, fd, remaining, q["maxOutputBytes"], cancelled)
                    receipt.update(state=state, exitCode=code, outputBase64=base64.b64encode(output).decode("ascii"), outputSha256=digest(output), truncated=truncated)
            finally:
                os.close(fd)
        artifact_path, artifact_sha, truncated = export_artifacts(config, p, key, workspace,
            q["maxOutputBytes"] - len(base64.b64decode(receipt["outputBase64"])), grant)
        receipt.update(artifactManifestPath=artifact_path, artifactManifestSha256=artifact_sha, artifactsTruncated=truncated)
        atomic(receipt_file, canonical(receipt))
        return receipt
    finally:
        os.close(lock)


def main():
    # Exactly one fixed policy file in the preconfigured Companion environment; no user-selected shell/options.
    admin_check = sys.argv[1:] == ["check"]
    if admin_check:
        # Even parsing/identity/tmpfs failures must invalidate an earlier readiness claim.
        # This fixed cwd/name comes from the installed command, not a policy or model path.
        invalidate_ready()
    config = policy()
    if admin_check:
        result = check(config)
    elif len(sys.argv) == 4 and sys.argv[1] == "run":
        result = execute(config, sys.argv[2], sys.argv[3])
    else:
        raise ValueError("Usage: fixed-launcher check | run exact-bundle-filename exact-sha256")
    sys.stdout.buffer.write(canonical(result) + b"\n")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # No host paths, policy, environment, credentials or uncontrolled exception text in job output.
        sys.stderr.write("Skill launcher rejected the request or cannot verify execution; reconcile the original operation.\n")
        sys.exit(2)
