# PocketWorld native fixed Git intake glue. Git owns transport/object validation,
# refs and index transactions. This program never executes repository scripts.
# Original ManagedProcessJobs owns the deadline, logs, stop and real exit proof.
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import fcntl
import socket
import shutil
import stat
import subprocess
import sys
import time

ROOT = Path.cwd()
MAX_METADATA = 256 * 1024 * 1024
MAX_COMMAND_OUTPUT = 256 * 1024
MAX_SOURCE = 16 * 1024 * 1024
MAX_FILE = 2 * 1024 * 1024
MAX_FILES = 2000
MAX_ENTRIES = 20000
FORMAT = 'pocketworld.native-git-import-job.v1'
RESULT = 'pocketworld.native-git-import-result.v1'
credential_config_fd = None

class Rejected(Exception):
    def __init__(self, code):
        self.code = code

def checked_file(path, maximum):
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > maximum:
        raise Rejected('git_intake_regular_file_required')
    with os.fdopen(os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)), 'rb') as source:
        raw = source.read(maximum + 1)
    after = path.lstat()
    if len(raw) > maximum or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise Rejected('git_intake_bytes_changed')
    return raw

def relative(raw):
    try:
        path = raw.decode('utf-8', errors='strict') if isinstance(raw, bytes) else raw
        path.encode('utf-8', errors='strict')
    except (UnicodeError, AttributeError):
        raise Rejected('git_intake_utf8_path_required')
    if not isinstance(path, str) or len(path) > 240 or path != path.strip() or path.startswith('/') or '\\' in path or ':' in path or any(ord(c) < 32 or ord(c) == 127 for c in path):
        raise Rejected('git_intake_path_unsupported')
    parts = path.split('/')
    if len(parts) > 40 or any(p in ('', '.', '..') or p.lower() == '.git' for p in parts):
        raise Rejected('git_intake_path_escape')
    return path

def metadata_budget(extra_bytes=0):
    count, total = 0, extra_bytes
    for folder, directories, files in os.walk(ROOT, followlinks=False):
        for name in directories + files:
            entry = Path(folder) / name
            attrs = entry.lstat()
            count += 1
            if count > MAX_ENTRIES:
                raise Rejected('git_intake_metadata_entries_exceeded')
            if stat.S_ISLNK(attrs.st_mode) or not (stat.S_ISREG(attrs.st_mode) or stat.S_ISDIR(attrs.st_mode)):
                raise Rejected('git_intake_metadata_link_or_special')
            if stat.S_ISREG(attrs.st_mode):
                if attrs.st_nlink != 1:
                    raise Rejected('git_intake_metadata_hardlink')
                total += attrs.st_size
                if total > MAX_METADATA:
                    raise Rejected('git_intake_metadata_observed_budget_exceeded')
    # This is an observation budget, not a cgroup/filesystem quota.

def command(arguments, network=False, maximum=MAX_COMMAND_OUTPUT):
    if time.monotonic() >= deadline:
        raise Rejected('git_intake_deadline')
    config = [
        '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false',
        '-c', 'core.pager=', '-c', 'credential.helper=', '-c', 'credential.interactive=false',
        '-c', 'http.followRedirects=false', '-c', 'http.sslVerify=true',
        '-c', 'http.proxy=' + (proxy if network else 'http://127.0.0.1:1'),
        '-c', 'http.noProxy=', '-c', 'protocol.version=0',
        '-c', 'protocol.file.allow=never', '-c', 'protocol.ext.allow=never',
        '-c', 'protocol.http.allow=never', '-c', 'protocol.https.allow=' + ('always' if network else 'never'),
        '-c', 'submodule.recurse=false', '-c', 'fetch.recurseSubmodules=false',
        '-c', 'fetch.fsckObjects=true', '-c', 'transfer.fsckObjects=true',
        '-c', 'core.autocrlf=false', '-c', 'core.safecrlf=true',
        '-c', 'gc.auto=0', '-c', 'maintenance.auto=false', '-c', 'core.logAllRefUpdates=false',
        '-c', 'pack.threads=1', '-c', 'index.threads=1', '-c', 'pack.windowMemory=8m',
        '-c', 'pack.deltaCacheSize=8m', '-c', 'core.deltaBaseCacheLimit=8m'
    ]
    # No shell, pager, helper chosen by a repository, inherited env or installation
    # script. Both Git output pipes stay in bounded RAM and are NEVER forwarded
    # to the original job log: a provider cannot reflect a credential into it.
    child_environment = dict(environment)
    inherited = ()
    if network and credential_config_fd is not None:
        child_environment['GIT_CONFIG_GLOBAL'] = '/proc/self/fd/' + str(credential_config_fd)
        inherited = (credential_config_fd,)
    child = subprocess.Popen(git_argv + config + arguments, cwd=str(repository), env=child_environment,
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             close_fds=True, pass_fds=inherited)
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ, 1)
    selector.register(child.stderr, selectors.EVENT_READ, 2)
    output = bytearray()
    received = 0
    try:
        while selector.get_map():
            if time.monotonic() >= deadline:
                raise Rejected('git_intake_deadline')
            metadata_budget()
            for key, _ in selector.select(.2):
                raw = os.read(key.fileobj.fileno(), 8192)
                if not raw:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                received += len(raw)
                if received > maximum:
                    raise Rejected('git_intake_command_output_exceeded')
                if key.data == 1:
                    output.extend(raw)
        code = child.wait(timeout=max(.01, deadline - time.monotonic()))
        if code != 0:
            raise Rejected('git_intake_git_command_failed')
        return bytes(output)
    except BaseException:
        if child.poll() is None:
            child.terminate()
        try:
            child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
        raise
    finally:
        selector.close()
        for pipe in (child.stdout, child.stderr):
            if not pipe.closed:
                pipe.close()

def credential_descriptor(url, authorization):
    # One sealed anonymous, read-only descriptor: no token/header in argv,
    # child environment, persistent git config or filesystem temporary file.
    # Same-UID descriptor inspection is NOT an OS isolation boundary.
    # Fixed Linux/Bionic ABI from the pinned official NDK linux/fcntl.h and
    # asm-generic/fcntl.h. Some Python builds omit these names despite a capable
    # kernel. This supplies symbols, NOT a fallback from the actual seal checks.
    abi = {'F_ADD_SEALS': 1033, 'F_GET_SEALS': 1034, 'F_SEAL_SEAL': 1,
           'F_SEAL_SHRINK': 2, 'F_SEAL_GROW': 4, 'F_SEAL_WRITE': 8}
    if not sys.platform.startswith('linux') or not hasattr(os, 'memfd_create'):
        raise Rejected('git_intake_native_credential_fd_unsupported')
    if any(getattr(fcntl, name, value) != value for name, value in abi.items()):
        raise Rejected('git_intake_native_credential_fd_unsupported')
    writer = reader = None
    payload = bytearray(('[http "' + url.rstrip('/') + '/"]\nextraHeader = Authorization: Basic ' + authorization + '\n').encode('utf-8'))
    try:
        writer = os.memfd_create('pw-one-git-auth', os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING)
        cursor = 0
        while cursor < len(payload):
            count = os.write(writer, payload[cursor:])
            if count <= 0:
                raise Rejected('git_intake_native_credential_fd_unsupported')
            cursor += count
        seals = abi['F_SEAL_SEAL'] | abi['F_SEAL_SHRINK'] | abi['F_SEAL_GROW'] | abi['F_SEAL_WRITE']
        fcntl.fcntl(writer, abi['F_ADD_SEALS'], seals)
        if fcntl.fcntl(writer, abi['F_GET_SEALS']) & seals != seals:
            raise Rejected('git_intake_native_credential_fd_unsupported')
        reader = os.open('/proc/self/fd/' + str(writer), os.O_RDONLY | os.O_CLOEXEC)
        if fcntl.fcntl(reader, fcntl.F_GETFL) & os.O_ACCMODE != os.O_RDONLY:
            raise Rejected('git_intake_native_credential_fd_unsupported')
        result, reader = reader, None
        return result
    except (OSError, AttributeError):
        raise Rejected('git_intake_native_credential_fd_unsupported')
    finally:
        payload[:] = b'\0' * len(payload)
        if writer is not None:
            os.close(writer)
        if reader is not None:
            os.close(reader)

def phase(value, **facts):
    # Native closed progress only, not a process exit/lease receipt or a copy of
    # remote error text. The operation/plan identity is already bound by host.
    print(json.dumps(dict({'format': RESULT, 'phase': value}, **facts), separators=(',', ':')), flush=True)

def finish(data):
    raw = json.dumps(data, ensure_ascii=False, separators=(',', ':'), sort_keys=True).encode('utf-8')
    if len(raw) > 4 * 1024 * 1024:
        raise Rejected('git_intake_result_manifest_exceeded')
    metadata_budget(extra_bytes=len(raw))
    pending = ROOT / '.native-git-import-result.pending'
    with pending.open('xb') as target:
        target.write(raw)
        target.flush()
        os.fsync(target.fileno())
    os.replace(pending, ROOT / '.native-git-import-result.json')
    phase('fixed_result_available', manifestSha256=hashlib.sha256(raw).hexdigest())

def main():
    global deadline, repository, environment, proxy, git_argv, credential_config_fd
    job = json.loads(checked_file(ROOT / '.native-git-import.json', 16384).decode('utf-8', errors='strict'))
    if job.get('format') != FORMAT or job.get('version') != 1 or job.get('networkScope') != 'one_reviewed_https_origin_and_pinned_ip' or job.get('repositoryScriptsAllowed') is not False:
        raise Rejected('git_intake_native_scope_required')
    proxy = job.get('nativeProxy', '')
    if not re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', proxy):
        raise Rejected('git_intake_original_tunnel_missing')
    git_argv = job['gitArgv']
    if not isinstance(git_argv, list) or len(git_argv) != 1 or not isinstance(git_argv[0], str) or not git_argv[0].startswith('/'):
        raise Rejected('git_intake_exact_git_binary_required')
    if hashlib.sha256(checked_file(Path(git_argv[0]), 64 * 1024 * 1024)).hexdigest() != job['gitExecutableSha256']:
        raise Rejected('git_intake_git_binary_changed')
    branch = job['branch']
    if not re.fullmatch(r'refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,180}', branch) or '..' in branch or branch.endswith(('/', '.lock')) or '@{' in branch:
        raise Rejected('git_intake_explicit_branch_required')
    url = job['url']
    from urllib.parse import urlsplit
    parsed = urlsplit(url)
    if parsed.scheme != 'https' or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment or not parsed.hostname or not parsed.path.startswith('/'):
        raise Rejected('git_intake_https_url_required')
    if parsed.hostname.lower() != job['host'] or (parsed.port or 443) != job['port']:
        raise Rejected('git_intake_original_origin_changed')
    deadline = time.monotonic() + min(600, job['timeoutSeconds'])
    repository = ROOT / 'repository'
    if repository.exists() or repository.is_symlink():
        raise Rejected('git_intake_new_empty_repository_required')
    repository.mkdir()
    for name in ('home', 'tmp', 'empty-template'):
        (ROOT / name).mkdir()
    # Every child inherits only these fixed native variables. All proxy aliases
    # are locked; NO_PROXY is empty. No ambient CA override, askpass, Git include,
    # trace, loader variable, token in URL/argv or credential-store inheritance.
    environment = {
        'PATH': job['path'], 'HOME': str(ROOT / 'home'), 'TMPDIR': str(ROOT / 'tmp'), 'LANG': 'C.UTF-8',
        'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_SYSTEM': '/dev/null',
        'GIT_TERMINAL_PROMPT': '0', 'GIT_ASKPASS': '/bin/false', 'SSH_ASKPASS': '/bin/false',
        'GIT_NO_REPLACE_OBJECTS': '1', 'GIT_NO_LAZY_FETCH': '1', 'GIT_SMART_HTTP': '1',
        'GIT_ALLOW_PROTOCOL': 'https', 'GIT_PROTOCOL_FROM_USER': '0', 'GIT_PAGER': 'cat',
        'http_proxy': proxy, 'https_proxy': proxy, 'all_proxy': proxy,
        'HTTP_PROXY': proxy, 'HTTPS_PROXY': proxy, 'ALL_PROXY': proxy, 'NO_PROXY': '', 'no_proxy': ''
    }
    token = username = ''
    if job.get('credentialProvided'):
        # One exact original-job native memory exchange; no token in argv, env,
        # URL, job file, raw stdout/stderr or persisted Git configuration. A failed
        # delivery is never retried or interpreted as permission to re-dispatch.
        nonce = job.get('nativeCredentialNonce', '')
        if not re.fullmatch(r'[0-9a-f]{64}', nonce):
            raise Rejected('git_intake_native_credential_scope_missing')
        port = int(proxy.rsplit(':', 1)[1])
        with socket.create_connection(('127.0.0.1', port), timeout=5) as exchange:
            exchange.settimeout(5)
            exchange.sendall(('PW-NATIVE-CREDENTIAL ' + nonce + '\r\n\r\n').encode('ascii'))
            raw_secret = bytearray()
            while True:
                part = exchange.recv(1024)
                if not part:
                    break
                raw_secret.extend(part)
                if len(raw_secret) > 16384:
                    raise Rejected('git_intake_native_credential_limit')
        try:
            secret = json.loads(raw_secret.decode('utf-8', errors='strict'))
            username, token = secret['username'], secret['token']
        finally:
            raw_secret[:] = b'\0' * len(raw_secret)
    if job.get('credentialProvided'):
        if not token or not username or any(c in username + token for c in ('\r', '\n', '\0')):
            raise Rejected('git_intake_original_credential_missing')
        # Only this original repository URL receives the ephemeral header. No
        # persistent git-config write, remote helper/redirect or token log.
        if len(username.encode('utf-8')) > 256 or len(token.encode('utf-8')) > 4096:
            raise Rejected('git_intake_native_credential_limit')
        auth = base64.b64encode((username + ':' + token).encode('utf-8')).decode('ascii')
        credential_config_fd = credential_descriptor(url, auth)
        auth = ''
    elif token or username:
        raise Rejected('git_intake_unreviewed_credential')
    token = username = ''
    if 'secret' in locals():
        secret.clear()
    phase('creating_new_repository')
    command(['check-ref-format', branch])
    command(['init', '--quiet', '--template=' + str(ROOT / 'empty-template'), '--initial-branch=' + branch[len('refs/heads/'):], '.'])
    phase('fetching_one_original_branch')
    # Git's documented dumb walker cannot serve deepen requests and is refused
    # before object walking. The finite native proxy is still the actual origin
    # connection boundary; protocol.version alone is not treated as one.
    command(['fetch', '--quiet', '--no-tags', '--no-recurse-submodules', '--depth=2147483647', url, branch], network=True)
    git = repository / '.git'
    if (git / 'shallow').exists():
        raise Rejected('git_intake_shallow_or_incomplete_unsupported')
    for name in ('commondir', 'objects/info/alternates', 'objects/info/http-alternates'):
        if (git / name).exists() or (git / name).is_symlink():
            raise Rejected('git_intake_external_object_store_unsupported')
    head = command(['rev-parse', '--verify', 'FETCH_HEAD^{commit}']).decode('ascii', errors='strict').strip()
    if not re.fullmatch(r'(?:[0-9a-f]{40}|[0-9a-f]{64})', head):
        raise Rejected('git_intake_commit_identity_invalid')
    phase('checking_reachable_objects')
    command(['fsck', '--full', '--strict', '--no-reflogs', head])
    raw_tree = command(['ls-tree', '-r', '-z', '--full-tree', head], maximum=1024 * 1024)
    manifest = []
    source_total = 0
    seen = set()
    for record in raw_tree.split(b'\0'):
        if not record:
            continue
        header, raw_path = record.split(b'\t', 1)
        mode, kind, oid = header.decode('ascii', errors='strict').split(' ')
        path = relative(raw_path)
        if path in seen or mode not in ('100644', '100755') or kind != 'blob' or not re.fullmatch(r'(?:[0-9a-f]{40}|[0-9a-f]{64})', oid):
            raise Rejected('git_intake_symlink_submodule_duplicate_or_special_tree')
        seen.add(path)
        if len(seen) > MAX_FILES:
            raise Rejected('git_intake_source_file_count_exceeded')
        size = command(['cat-file', '-s', oid]).decode('ascii', errors='strict').strip()
        if not size.isdecimal() or int(size) > MAX_FILE:
            raise Rejected('git_intake_source_file_size_exceeded')
        body = command(['cat-file', 'blob', oid], maximum=MAX_FILE + 16384)
        if len(body) != int(size):
            raise Rejected('git_intake_blob_size_changed')
        source_total += len(body)
        if source_total > MAX_SOURCE:
            raise Rejected('git_intake_source_total_exceeded')
        if body.startswith(b'version https://git-lfs.github.com/spec/v1\n') or body.startswith(b'version https://git-lfs.github.com/spec/v1\r\n'):
            raise Rejected('git_intake_lfs_pointer_requires_separate_authorization')
        manifest.append({'path': path, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest(),
                         'gitObjectId': oid, 'executable': mode == '100755'})
    if not manifest:
        raise Rejected('git_intake_empty_source_repository')
    phase('materializing_checked_tree')
    # Fresh private config + empty template; no configured filter/hook. Git does
    # the actual index/tree/ref operations, preserving executable file modes.
    command(['update-ref', branch, head, '0' * len(head)])
    command(['read-tree', '--reset', '-u', branch])
    for item in manifest:
        path = repository
        for part in item['path'].split('/'):
            path = path / part
            if path.is_symlink():
                raise Rejected('git_intake_materialized_link')
        raw = checked_file(path, MAX_FILE)
        if len(raw) != item['bytes'] or hashlib.sha256(raw).hexdigest() != item['sha256']:
            raise Rejected('git_intake_materialized_bytes_changed')
    metadata_budget()
    git_manifest = []
    for folder, directories, files in os.walk(git, followlinks=False):
        directories.sort()
        files.sort()
        for name in files:
            path = Path(folder) / name
            before = path.lstat()
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
                raise Rejected('git_intake_metadata_link_or_special')
            digest = hashlib.sha256()
            count = 0
            with os.fdopen(os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)), 'rb') as original:
                while True:
                    part = original.read(32768)
                    if not part:
                        break
                    count += len(part)
                    if count > MAX_METADATA or time.monotonic() >= deadline:
                        raise Rejected('git_intake_metadata_observed_budget_exceeded')
                    digest.update(part)
            after = path.lstat()
            if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or count != before.st_size:
                raise Rejected('git_intake_metadata_changed')
            git_manifest.append({'path': str(path.relative_to(git)), 'bytes': count, 'sha256': digest.hexdigest()})
            if len(git_manifest) > MAX_ENTRIES:
                raise Rejected('git_intake_metadata_entries_exceeded')
    finish({'format': RESULT, 'version': 1, 'operationId': job['operationId'], 'planDigest': job['planDigest'],
            'url': url, 'branch': branch, 'commitId': head, 'files': sorted(manifest, key=lambda row: row['path']),
            'fileCount': len(manifest), 'sourceBytes': source_total, 'gitFiles': sorted(git_manifest, key=lambda row: row['path']), 'repositoryScriptsExecuted': False,
            'submodulesLoaded': False, 'lfsFetched': False, 'credentialsSaved': False,
            'projectPublished': False, 'runtimeOrGrantsRestored': False})

try:
    main()
except Rejected as failed:
    # Never echo raw Git stderr, arguments, environment or exception messages.
    print(json.dumps({'format': RESULT, 'status': 'failed', 'code': failed.code}, separators=(',', ':')), flush=True)
    sys.exit(70)
except BaseException:
    print(json.dumps({'format': RESULT, 'status': 'failed', 'code': 'git_intake_native_processing_failed'}, separators=(',', ':')), flush=True)
    sys.exit(71)
finally:
    if credential_config_fd is not None:
        os.close(credential_config_fd)
