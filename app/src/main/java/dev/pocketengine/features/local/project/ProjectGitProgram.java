package dev.pocketengine.features.local.project;

import java.nio.charset.StandardCharsets;

/** Fixed adapter over the managed runtime's verified Git; no shell or second scheduler.
 * Git owns object/index/ref/diff algorithms. No repository script is executed by this adapter. */
public final class ProjectGitProgram {
    public static final String VERSION="pocketworld.project-git.v1";
    private ProjectGitProgram(){}
    public static String source(){return SOURCE;}
    public static String identity()throws Exception{return ProjectSources.sha256(SOURCE.getBytes(StandardCharsets.UTF_8));}
    private static final String SOURCE="""
# Fixed PocketWorld local Git protocol. Git itself owns object/index/ref algorithms.
# Called only by the original managed job. No shell, network verbs, hooks or filters.
import hashlib, json, os, re, selectors, shutil, stat, subprocess, sys, time, uuid
from pathlib import Path

FORMAT = 'pocketworld.project-git.v1'
MAX_COMMAND_BYTES = 4 * 1024 * 1024
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 16 * 1024 * 1024
MAX_FILES = 2000
MAX_METADATA_ENTRIES = 20000
MAX_DIFF_BYTES = 16000
SOURCE_OMIT = {'.git', 'node_modules', '.venv', 'venv', '__pycache__', '.gradle', '.pocketworld', '.pytest_cache'}
ACTIONS = {'status', 'history', 'diff', 'init', 'stage', 'unstage', 'resolve', 'commit', 'isolate'}
HEX = re.compile(r'^(?:[a-f0-9]{40}|[a-f0-9]{64})$')

class Rejected(Exception):
    def __init__(self, code, message):
        self.code, self.message = code, message
        super().__init__(message)

def digest(value):
    return hashlib.sha256(value).hexdigest()

def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode('utf-8')

def path_value(value):
    if not isinstance(value, str) or not value or len(value) > 240 or value != value.strip() or '\\\\' in value or any(ord(c) < 32 for c in value):
        raise Rejected('git_path_unsupported', 'Use one ordinary UTF-8 project-relative file path.')
    value.encode('utf-8', errors='strict')
    parts = value.split('/')
    if any(p in ('', '.', '..') or p.lower() == '.git' for p in parts) or value.startswith('/') or len(parts) > 40:
        raise Rejected('git_path_outside_project', 'Git metadata and paths outside this checkout are not selectable.')
    return value

def own_regular(path, maximum, absent=False):
    try:
        info = path.lstat()
    except FileNotFoundError:
        if absent:
            return None
        raise Rejected('git_file_missing', 'An original Git or project file is missing.')
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > maximum:
        raise Rejected('git_file_unsupported', 'Linked, special or oversized files are not followed.')
    with path.open('rb') as source:
        value = source.read(maximum + 1)
    now = path.lstat()
    if len(value) > maximum or (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns) != (now.st_dev, now.st_ino, now.st_size, now.st_mtime_ns):
        raise Rejected('git_file_changed_during_read', 'A file changed while it was being checked; refresh first.')
    return value

def file_pin(root, relative):
    path_value(relative)
    path = root
    for component in relative.split('/')[:-1]:
        path = path / component
        try:
            info = path.lstat()
        except FileNotFoundError:
            return {'sha256': 'absent', 'bytes': 0, 'mode': 'absent'}
        if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
            raise Rejected('git_path_not_regular', 'Project path traverses a link or non-directory.')
    path = root / relative
    raw = own_regular(path, MAX_FILE_BYTES, True)
    if raw is None:
        return {'sha256': 'absent', 'bytes': 0, 'mode': 'absent'}
    return {'sha256': digest(raw), 'bytes': len(raw), 'mode': '100755' if path.stat().st_mode & 0o111 else '100644'}

def source_pins(root):
    pins, total = {}, 0
    for directory, directories, files in os.walk(root, followlinks=False):
        parent = Path(directory)
        relative_parent = parent.relative_to(root)
        keep = []
        for name in sorted(directories):
            if name in SOURCE_OMIT or relative_parent == Path('.') and name in ('build', 'dist'):
                continue
            info = (parent / name).lstat()
            if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
                raise Rejected('git_project_link_unsupported', 'Source directory links are not followed.')
            keep.append(name)
        directories[:] = keep
        for name in sorted(files):
            if name in SOURCE_OMIT:
                continue
            relative = (parent / name).relative_to(root).as_posix()
            pin = file_pin(root, relative)
            total += pin['bytes']
            if len(pins) >= MAX_FILES or total > MAX_TOTAL_BYTES:
                raise Rejected('git_project_size_unsupported', 'This bounded checkout exceeds 2000 source files or 16MiB.')
            pins[relative] = pin
    return pins

def config_guard(raw):
    # A deliberately restricted local-config envelope is checked BEFORE Git setup
    # can follow include/includeIf. Git remains the authoritative value parser.
    text = raw.decode('utf-8', errors='strict')
    if chr(0) in text or chr(92) in text:
        raise Rejected('git_config_syntax_unsupported', 'Escaped or continued local config is not enabled by this bounded workflow.')
    section = ''
    for line in text.splitlines():
        clean = line.strip()
        if not clean or clean.startswith(('#', ';')):
            continue
        if clean.startswith('['):
            match = re.fullmatch(r'\\[\\s*([A-Za-z0-9.-]+)(?:\\s+"[^"\\r\\n]*")?\\s*\\]\\s*(?:[#;].*)?', clean)
            if not match:
                raise Rejected('git_config_syntax_unsupported', 'Local Git config has an unsupported section form.')
            section = match.group(1).lower()
            if section in ('include', 'includeif', 'filter'):
                raise Rejected('git_config_execution_unsupported', 'External config includes and clean/smudge filters are not enabled by this local workflow.')
        else:
            match = re.fullmatch(r'([A-Za-z][A-Za-z0-9-]*)\\s*(?:=.*)?', clean)
            if not section or not match:
                raise Rejected('git_config_syntax_unsupported', 'Local Git config has an unsupported key form.')
            key = section + '.' + match.group(1).lower()
            if key in ('extensions.partialclone', 'extensions.worktreeconfig') or key.endswith('.promisor'):
                raise Rejected('git_config_execution_unsupported', 'Partial clones and worktree-specific config are not enabled by this local workflow.')


def metadata_guard(git):
    if not git.is_dir() or git.is_symlink() or git.resolve() != git:
        raise Rejected('git_repository_layout_unsupported', 'Only an ordinary .git directory inside this exact checkout is supported; linked worktrees and gitfiles are not followed.')
    count = 0
    for directory, directories, files in os.walk(git, followlinks=False):
        parent = Path(directory)
        for name in directories + files:
            count += 1
            if count > MAX_METADATA_ENTRIES:
                raise Rejected('git_metadata_size_unsupported', 'Repository metadata exceeds this bounded local Git view.')
            path = parent / name
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)) or stat.S_ISREG(info.st_mode) and info.st_nlink != 1:
                raise Rejected('git_metadata_link_unsupported', 'Git metadata links or special files are not followed.')
    for path in ('commondir', 'objects/info/alternates', 'objects/info/http-alternates', 'shallow'):
        if (git / path).exists():
            raise Rejected('git_repository_layout_unsupported', 'Shared object stores, linked worktrees, shallow or external object layouts require a separate reviewed workflow.')
    config_guard(own_regular(git / 'config', 65536))
    own_regular(git / 'HEAD', 4096)
    own_regular(git / 'index', 8 * 1024 * 1024, True)

class Git:
    def __init__(self, root, expected_binary, git_directory=None, common_directory=None, bare=False):
        self.root, self.git = root, root / '.git' if git_directory is None else git_directory
        self.common = self.git if common_directory is None else common_directory
        self.private_owner = None
        binary = shutil.which('git')
        if binary is None:
            raise Rejected('git_tool_unavailable', 'The managed Git tool is not installed.')
        self.binary = str(Path(binary).resolve())
        raw = Path(self.binary).read_bytes()
        if not re.fullmatch(r'[a-f0-9]{64}', expected_binary) or digest(raw) != expected_binary:
            raise Rejected('git_tool_changed', 'Git executable differs from the original reviewed runtime tool.')
        self.environment = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'LANG') if key in os.environ}
        self.environment.update({'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_SYSTEM': '/dev/null', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_OPTIONAL_LOCKS': '0', 'GIT_TERMINAL_PROMPT': '0', 'GIT_NO_LAZY_FETCH': '1', 'GIT_NO_REPLACE_OBJECTS': '1', 'GIT_PROTOCOL_FROM_USER': '0', 'GIT_ALLOW_PROTOCOL': '', 'LC_ALL': 'C.UTF-8'})
        self.options = ['--no-pager', '--literal-pathspecs', '-c', 'core.fsmonitor=false', '-c', 'core.untrackedCache=false', '-c', 'core.hooksPath=/dev/null', '-c', 'core.attributesFile=/dev/null', '-c', 'core.excludesFile=/dev/null', '-c', 'core.bare=' + ('true' if bare else 'false'), '-c', 'core.pager=cat', '-c', 'diff.orderFile=/dev/null', '-c', 'gc.auto=0', '-c', 'maintenance.auto=false', '-c', 'submodule.recurse=false', '-c', 'core.quotePath=false', '-c', 'commit.gpgSign=false', '-c', 'tag.gpgSign=false', '--git-dir=' + str(self.git)]
        if not bare:
            self.options += ['--work-tree=' + str(root)]

    def command(self, args, data=None, index=None, accepted=(0,), identity=None):
        env = dict(self.environment)
        if index is not None:
            env['GIT_INDEX_FILE'] = str(index)
        if identity:
            env.update(identity)
        process = subprocess.Popen([self.binary] + self.options + args, cwd=self.root, env=env, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False)
        if data is not None:
            try:
                process.stdin.write(data)
                process.stdin.close()
            except BrokenPipeError:
                process.stdin.close()
        selector = selectors.DefaultSelector()
        stdout, stderr, total, started = bytearray(), bytearray(), 0, time.monotonic()
        for stream, bucket in [(process.stdout, stdout), (process.stderr, stderr)]:
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, bucket)
        try:
            while selector.get_map():
                if time.monotonic() - started > 20:
                    raise Rejected('git_command_timeout', 'The bounded Git command did not finish.')
                for key, _ in selector.select(0.1):
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                    else:
                        key.data.extend(chunk)
                        total += len(chunk)
                        if total > MAX_COMMAND_BYTES:
                            raise Rejected('git_output_limit', 'Git output exceeds this bounded view; no complete result is claimed.')
            code = process.wait(timeout=2)
            if code not in accepted:
                # Repository text/config stderr is not copied into the protocol or audit.
                failure = Rejected('git_command_failed', 'A fixed Git command failed (exit %s); no success is inferred.' % code)
                safe = bytes(stderr).decode('utf-8', errors='replace')
                safe = re.sub(r'(https?://)[^/\\s@]+@', r'\\1[redacted]@', safe)
                safe = re.sub(r'(?i)(token|password|secret|authorization|api[_-]?key)\\s*[:=]\\s*[^\\s]+', r'\\1=[redacted]', safe)
                sys.stderr.write(safe[:16384])
                failure.native_stderr = bytes(stderr)
                failure.fixed_command = args[0]
                raise failure
            return bytes(stdout)
        finally:
            selector.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=1)
            for stream in (process.stdout, process.stderr):
                if not stream.closed:
                    stream.close()

    def guard(self):
        if self.private_owner is None:
            metadata_guard(self.git)
        else:
            pool, actual = private_pool(self.private_owner)
            expected_admin = pool / 'common/worktrees/files'
            if self.git != expected_admin or self.common != pool / 'common' or not os.path.samefile(self.root, pool / 'files'):
                raise Rejected('git_private_pool_changed', 'The original owned checkout/common mapping changed.')
            metadata_guard(self.common)
            if own_regular(self.root / '.git', 8192) != ('gitdir: ' + str(expected_admin) + '\\n').encode('utf-8') or own_regular(self.git / 'commondir', 8192) != b'../..\\n' or own_regular(self.git / 'gitdir', 8192) != (str(pool / 'files/.git') + '\\n').encode('utf-8'):
                raise Rejected('git_private_pool_changed', 'The private gitfile/common/back-reference tuple changed.')
            if own_regular(self.git / 'HEAD', 4096) != ('ref: refs/heads/' + actual['branch'] + '\\n').encode('ascii'):
                raise Rejected('git_private_pool_changed', 'The original private worktree branch changed.')
        config = self.command(['config', '--local', '--no-includes', '--null', '--list'])
        for item in config.split(bytes([0])):
            if not item:
                continue
            key = item.split(b'\\n', 1)[0].decode('ascii', errors='strict').lower()
            if key.startswith(('include.', 'includeif.', 'filter.')) or key in ('extensions.partialclone', 'extensions.worktreeconfig') or key.endswith('.promisor'):
                raise Rejected('git_config_execution_unsupported', 'External config includes, clean/smudge filters, partial clones and worktree config are not enabled by this local workflow.')
        fmt = self.command(['rev-parse', '--show-object-format']).decode('ascii').strip()
        if fmt not in ('sha1', 'sha256'):
            raise Rejected('git_object_format_unsupported', 'Repository object format is not supported.')

    def head(self):
        raw_header = own_regular(self.git / 'HEAD', 4096)
        header = raw_header.decode('ascii', errors='strict').strip()
        if header.startswith('ref: '):
            branch = header[5:]
            if not re.fullmatch(r'refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,180}', branch) or '..' in branch or '@{' in branch or branch.endswith(('/', '.lock')):
                raise Rejected('git_head_ref_unsupported', 'HEAD is not an ordinary local branch reference.')
        elif HEX.fullmatch(header):
            branch = 'detached'
        else:
            raise Rejected('git_head_invalid', 'Git HEAD is not a valid local branch or detached commit.')
        value = self.command(['rev-parse', '--verify', 'HEAD'], accepted=(0, 128)).decode('ascii').strip()
        if value and not HEX.fullmatch(value):
            raise Rejected('git_head_invalid', 'Git returned an invalid original HEAD identity.')
        return branch, value or 'unborn', digest(raw_header)

    def index_entries(self, index=None):
        output = self.command(['ls-files', '--stage', '-z'], index=index)
        result = {}
        for line in output.split(bytes([0])):
            if not line:
                continue
            first, name = line.split(b'\\t', 1)
            mode, oid, stage = first.decode('ascii').split(' ')
            path = path_value(name.decode('utf-8', errors='strict'))
            if mode not in ('100644', '100755', '120000', '160000') or not HEX.fullmatch(oid) or stage not in ('0', '1', '2', '3'):
                raise Rejected('git_index_invalid', 'Git returned an unsupported index entry.')
            result.setdefault(path, []).append({'mode': mode, 'objectId': oid, 'stage': int(stage)})
            if len(result) > MAX_FILES:
                raise Rejected('git_index_size_unsupported', 'Repository index exceeds 2000 tracked file paths.')
        return result

    def snapshot(self):
        self.guard()
        branch, head, header = self.head()
        index = own_regular(self.git / 'index', 8 * 1024 * 1024, True)
        config = own_regular(self.common / 'config', 65536)
        merge = own_regular(self.git / 'MERGE_HEAD', 4096, True)
        before = {'head': head, 'headRef': branch, 'headFileSha256': header, 'indexSha256': 'absent' if index is None else digest(index), 'configSha256': digest(config), 'mergeHeadSha256': 'absent' if merge is None else digest(merge)}
        entries, pins = self.index_entries(), source_pins(self.root)
        output = self.command(['status', '--porcelain=v2', '-z', '--untracked-files=all', '--ignore-submodules=all', '--no-renames'])
        records = []
        for line in output.split(bytes([0])):
            if not line:
                continue
            kind = chr(line[0])
            if kind == '1':
                parts = line.decode('utf-8', errors='strict').split(' ', 8)
                name, xy = path_value(parts[8]), parts[1]
                record = {'path': name, 'indexStatus': xy[0], 'worktreeStatus': xy[1], 'conflict': False}
            elif kind == 'u':
                parts = line.decode('utf-8', errors='strict').split(' ', 10)
                name, xy = path_value(parts[10]), parts[1]
                record = {'path': name, 'indexStatus': xy[0], 'worktreeStatus': xy[1], 'conflict': True}
            elif kind == '?':
                name = path_value(line[2:].decode('utf-8', errors='strict'))
                record = {'path': name, 'indexStatus': '?', 'worktreeStatus': '?', 'conflict': False}
            elif kind == '!':
                continue
            else:
                raise Rejected('git_status_format_unsupported', 'Git status has an unexpected record; it is not guessed.')
            parts = name.split('/')
            source_scope = not any(part in SOURCE_OMIT for part in parts) and parts[0] not in ('build', 'dist')
            record['sourceScope'] = source_scope
            record['file'] = pins[name] if name in pins else file_pin(self.root, name) if source_scope else {'contentRead': False, 'reason': 'runtime_cache_or_generated_root'}
            record['indexEntries'] = entries.get(name, [])
            records.append(record)
        after_branch, after_head, after_header = self.head()
        current_index = own_regular(self.git / 'index', 8 * 1024 * 1024, True)
        if (branch, head, header) != (after_branch, after_head, after_header) or before['indexSha256'] != ('absent' if current_index is None else digest(current_index)) or config != own_regular(self.common / 'config', 65536) or merge != own_regular(self.git / 'MERGE_HEAD', 4096, True):
            raise Rejected('git_state_changed_during_read', 'HEAD or index changed during Git observation; refresh first.')
        if pins != source_pins(self.root):
            raise Rejected('git_worktree_changed_during_read', 'Worktree files changed during Git observation; refresh first.')
        before['worktreeSha256'] = digest(canonical(pins))
        before['statusSha256'] = digest(canonical(records))
        before['snapshotDigest'] = digest(canonical(before))
        return {'pin': before, 'records': records, 'filePins': pins, 'indexEntries': entries, 'mergeParents': [] if merge is None else merge.decode('ascii', errors='strict').splitlines(), 'indexLocked': (self.git / 'index.lock').exists(), 'gitVersion': self.command(['--version']).decode('ascii').strip()}

    def require(self, expected):
        actual = self.snapshot()
        if canonical(actual['pin']) != canonical(expected):
            raise Rejected('git_review_stale', 'HEAD, index or worktree differs from the reviewed Git state; original files and index were not overwritten.')
        return actual

    def history(self, request):
        before = self.require(request['expected'])
        offset, limit = request.get('offset', 0), request.get('limit', 10)
        if type(offset) is not int or not 0 <= offset <= 2000 or type(limit) is not int or not 1 <= limit <= 10:
            raise Rejected('git_history_cursor_invalid', 'Choose one bounded history page of the original reviewed HEAD.')
        commits = []
        ids = [] if before['pin']['head'] == 'unborn' else self.command(['rev-list', '--max-count=' + str(limit + 1), '--skip=' + str(offset), before['pin']['head'], '--']).decode('ascii', errors='strict').splitlines()
        for oid in ids[:limit]:
            if not HEX.fullmatch(oid):
                raise Rejected('git_history_identity_invalid', 'Git returned an invalid commit identity.')
            # No patch, notes, signature verifier, pager, hook or repository command.
            fields = self.command(['show', '--no-patch', '--no-notes', '--no-show-signature', '--format=%H%x00%T%x00%P%x00%an%x00%ae%x00%at%x00%s', oid, '--']).rstrip(b'\\n').split(bytes([0]))
            if len(fields) != 7:
                raise Rejected('git_history_format_unsupported', 'Git commit metadata is not one supported complete record.')
            identity, tree, parents, author, email, timestamp, subject = [value.decode('utf-8', errors='strict') for value in fields]
            if identity != oid or not HEX.fullmatch(tree) or any(not HEX.fullmatch(parent) for parent in parents.split()) or not re.fullmatch(r'-?[0-9]{1,16}', timestamp):
                raise Rejected('git_history_identity_invalid', 'Git commit/tree/parent or timestamp does not match the original record.')
            subject_bytes = subject.encode('utf-8'); preview = subject_bytes[:512]
            while preview:
                try:
                    preview = preview.decode('utf-8'); break
                except UnicodeDecodeError:
                    preview = preview[:-1]
            if not preview:
                preview = ''
            commits.append({'commitId': oid, 'treeId': tree, 'parents': parents.split(), 'authorName': author[:100], 'authorEmail': email[:251], 'authorTimeEpochSeconds': int(timestamp), 'subject': preview, 'subjectTruncated': len(subject_bytes) > 512, 'metadataOnly': True})
        self.require(before['pin'])
        return {'pin': before['pin'], 'commits': commits, 'offset': offset, 'nextOffset': offset + len(commits), 'truncated': len(ids) > limit, 'readOnly': True, 'revisionRoot': before['pin']['head'], 'messageBodiesNotRead': True, 'networkUsed': False}

    def diff(self, request):
        old = self.require(request['expected'])
        path = path_value(request['path'])
        layer = request['layer']
        if layer not in ('head_index', 'index_worktree'):
            raise Rejected('git_diff_layer_invalid', 'Choose HEAD to index or index to worktree explicitly.')
        args = ['diff', '--no-ext-diff', '--no-textconv', '--no-color', '--no-renames', '--src-prefix=a/', '--dst-prefix=b/', '--unified=3']
        if layer == 'head_index':
            args += ['--cached', '--', path]
        elif path not in old['indexEntries'] and path in old['filePins']:
            args += ['--no-index', '--', '/dev/null', path]
        else:
            args += ['--', path]
        output = self.command(args, accepted=(0, 1))
        self.require(old['pin'])
        offset = request.get('offset', 0)
        if type(offset) is not int or offset < 0 or offset > len(output):
            raise Rejected('git_diff_cursor_invalid', 'Diff byte offset is outside this original diff.')
        # Stable UTF-8 character boundaries; binary differences remain Git's own summary.
        try:
            output.decode('utf-8', errors='strict')
        except UnicodeDecodeError:
            raise Rejected('git_diff_not_utf8', 'Git returned non-UTF-8 diff bytes. This text view does not replace or edit binary/non-UTF-8 content.')
        start = offset
        if start < len(output) and (output[start] & 0xc0) == 0x80:
            raise Rejected('git_diff_cursor_invalid', 'Diff offset must be a UTF-8 character boundary.')
        end = min(len(output), start + MAX_DIFF_BYTES)
        while end < len(output) and end > start and (output[end] & 0xc0) == 0x80:
            end -= 1
        return {'pin': old['pin'], 'path': path, 'layer': layer, 'diff': output[start:end].decode('utf-8'), 'diffSha256': digest(output), 'offset': start, 'nextOffset': end, 'totalBytes': len(output), 'truncated': end < len(output), 'readOnly': True}

    def tree_entry(self, tree, path):
        if tree == 'unborn':
            return None
        raw = self.command(['ls-tree', '-z', tree, '--', path])
        if not raw:
            return None
        records = raw.rstrip(bytes([0])).split(bytes([0]))
        if len(records) != 1:
            raise Rejected('git_tree_entry_invalid', 'Selected path is not one original tree file.')
        header, name = records[0].split(b'\\t', 1)
        mode, kind, oid = header.decode('ascii').split(' ')
        if name.decode('utf-8', errors='strict') != path or kind != 'blob' or mode not in ('100644', '100755') or not HEX.fullmatch(oid):
            raise Rejected('git_tree_entry_unsupported', 'Links, submodules and directories are not staged as ordinary files.')
        return {'mode': mode, 'objectId': oid, 'stage': 0}

    def update_entry(self, index, path, entry):
        # Mode0 first clears all original conflict stages; index encoding is delegated to Git.
        zero = '0' * (64 if self.command(['rev-parse', '--show-object-format']).strip() == b'sha256' else 40)
        data = ('0 ' + zero + '\\t' + path).encode('utf-8') + bytes([0])
        if entry is not None:
            data += (entry['mode'] + ' ' + entry['objectId'] + '\\t' + path).encode('utf-8') + bytes([0])
        self.command(['update-index', '-z', '--index-info'], data=data, index=index)

    def mutate(self, request):
        action = request['action']
        before = self.require(request['expected'])
        selected = request.get('paths')
        if not isinstance(selected, list) or not 1 <= len(selected) <= 40 or len(set(selected)) != len(selected):
            raise Rejected('git_selected_paths_invalid', 'Explicitly select between 1 and 40 distinct ordinary file paths.')
        for path in selected:
            path_value(path)
        if before['indexLocked']:
            raise Rejected('git_index_locked', 'Another Git index lock exists; it was not removed.')
        index_lock = self.git / 'index.lock'
        lock = os.open(index_lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        inode = os.fstat(lock)
        os.close(lock)
        temporary = self.git / ('index.pocketworld-' + uuid.uuid4().hex)
        published = False
        publication_entered = False
        try:
            self.require(before['pin'])
            original = own_regular(self.git / 'index', 8 * 1024 * 1024, True)
            if original is not None:
                temporary.write_bytes(original)
            else:
                self.command(['read-tree', '--empty'], index=temporary)
            if action in ('stage', 'resolve', 'unstage'):
                for path in selected:
                    old = before['indexEntries'].get(path, [])
                    conflict = any(x['stage'] != 0 for x in old)
                    if conflict and action != 'resolve':
                        raise Rejected('git_conflict_requires_explicit_resolution', 'Read and CAS-save the conflict file, then explicitly mark it resolved; normal stage/unstage will not silently clear conflicts.')
                    if action == 'resolve' and not conflict:
                        raise Rejected('git_conflict_state_changed', 'The selected file is no longer an original unresolved conflict.')
                    if action == 'unstage':
                        self.update_entry(temporary, path, self.tree_entry(before['pin']['head'], path))
                    else:
                        # Validate the actual selected ordinary source before Git reads it.
                        # Git add itself applies its own index/mode/EOL semantics; repository
                        # external filters were rejected before Git setup and cannot execute.
                        file_pin(self.root, path)
                        self.command(['add', '--all', '--', path], index=temporary)
                        staged_entries = self.index_entries(temporary).get(path, [])
                        if any(row['stage'] != 0 or row['mode'] not in ('100644', '100755') for row in staged_entries):
                            raise Rejected('git_staged_entry_unsupported', 'Git did not produce an ordinary exact stage-0 source entry.')
                self.require(before['pin'])
                with temporary.open('rb') as stream:
                    os.fsync(stream.fileno())
                # Standard Git cooperation: our exclusive original index.lock blocks other Git writers.
                # An arbitrary same-UID process ignoring Git's locks is not an OS sandbox guarantee.
                os.replace(temporary, index_lock)
                inode = index_lock.lstat()
                publication_entered = True
                os.replace(index_lock, self.git / 'index')
                published = True
            elif action == 'commit':
                if before['pin']['headRef'] == 'detached':
                    raise Rejected('git_detached_commit_not_enabled', 'Choose a local branch before this bounded owner commit workflow.')
                if any(x['stage'] != 0 for rows in before['indexEntries'].values() for x in rows):
                    raise Rejected('git_unresolved_conflicts', 'Resolve and explicitly stage all conflict files before committing.')
                staged = {row['path'] for row in before['records'] if row['indexStatus'] not in ('.', '?')}
                if not set(selected) <= staged:
                    raise Rejected('git_commit_selection_not_staged', 'Every selected commit path must be in the original staged changes.')
                merge = own_regular(self.git / 'MERGE_HEAD', 4096, True)
                merge_admin = {name: own_regular(self.git / name, 65536, True) for name in ('MERGE_HEAD', 'MERGE_MSG', 'MERGE_MODE', 'AUTO_MERGE')} if merge is not None else {}
                reviewed_entries = []
                for path in selected:
                    rows = before['indexEntries'].get(path, [])
                    reviewed_entries.append({'path': path, 'mode': rows[0]['mode'] if rows else '0', 'objectId': rows[0]['objectId'] if rows else 'absent'})
                if request.get('reviewedEntries') != reviewed_entries:
                    raise Rejected('git_commit_tree_review_mismatch', 'Selected original index blob/mode/deletion entries differ from the reviewed commit tree inputs.')
                expected_parents = [] if before['pin']['head'] == 'unborn' else [before['pin']['head']]
                for parent in before['mergeParents']:
                    if not HEX.fullmatch(parent):
                        raise Rejected('git_merge_head_invalid', 'The original merge parent identity is invalid.')
                    if parent not in expected_parents:
                        expected_parents.append(parent)
                if request.get('parents') != expected_parents:
                    raise Rejected('git_commit_parent_review_mismatch', 'Commit parents differ from the original reviewed HEAD/merge parents.')
                if merge is not None and set(selected) != staged:
                    raise Rejected('git_merge_partial_commit_unsupported', 'An in-progress merge requires explicitly reviewing all staged changes, not a partial commit.')
                temporary.unlink()
                self.command(['read-tree', '--empty'] if before['pin']['head'] == 'unborn' else ['read-tree', before['pin']['head']], index=temporary)
                for path in selected:
                    entries = before['indexEntries'].get(path, [])
                    self.update_entry(temporary, path, entries[0] if entries else None)
                tree = self.command(['write-tree'], index=temporary).decode('ascii').strip()
                if not HEX.fullmatch(tree):
                    raise Rejected('git_tree_identity_invalid', 'Git did not return an exact commit tree identity.')
                author = request.get('authorName', '')
                email = request.get('authorEmail', '')
                message = request.get('message', '')
                if not isinstance(author, str) or not 1 <= len(author) <= 100 or any(c in author for c in '\\r\\n<>') or not isinstance(email, str) or not re.fullmatch(r'[^\\s<>@]{1,100}@[^\\s<>@]{1,150}', email) or not isinstance(message, str) or not message.strip() or len(message.encode('utf-8')) > 4096 or chr(0) in message:
                    raise Rejected('git_commit_identity_invalid', 'Review a bounded commit message and explicit ordinary author name/email.')
                args = ['commit-tree', '--no-gpg-sign', tree]
                parents = expected_parents
                for parent in parents:
                    args += ['-p', parent]
                identity = {'GIT_AUTHOR_NAME': author, 'GIT_AUTHOR_EMAIL': email, 'GIT_COMMITTER_NAME': author, 'GIT_COMMITTER_EMAIL': email}
                commit = self.command(args, data=message.encode('utf-8'), identity=identity).decode('ascii').strip()
                if not HEX.fullmatch(commit):
                    raise Rejected('git_commit_identity_invalid', 'Git did not return an exact commit object identity.')
                self.require(before['pin'])
                zero = '0' * len(commit)
                publication_entered = True
                self.command(['update-ref', '--create-reflog', '-m', 'PocketWorld reviewed local commit', before['pin']['headRef'], commit, zero if before['pin']['head'] == 'unborn' else before['pin']['head']], identity=identity)
                published = True
                if self.head()[0] != before['pin']['headRef']:
                    raise Rejected('git_head_ref_changed_after_publication', 'The original branch CAS completed but HEAD selection changed; outcome requires original-operation reconciliation, not replay.')
                # Remove only the original merge administration files and only after HEAD CAS succeeds.
                if merge is not None:
                    for name, original_admin in merge_admin.items():
                        path = self.git / name
                        if own_regular(path, 65536, True) != original_admin:
                            raise Rejected('git_merge_administration_changed', 'Original commit published but merge administration changed; newer metadata was not removed.')
                        if original_admin is not None:
                            path.unlink()
                return {'action': action, 'paths': selected, 'before': before['pin'], 'after': self.snapshot()['pin'], 'commitId': commit, 'treeId': tree, 'reviewedEntries': reviewed_entries, 'parents': parents, 'headPublished': True, 'worktreeWritten': False, 'unselectedIndexPreserved': True, 'networkUsed': False, 'hooksExecuted': False}
            else:
                raise Rejected('git_action_invalid', 'This action is not a fixed Git mutation.')
            return {'action': action, 'paths': selected, 'before': before['pin'], 'after': self.snapshot()['pin'], 'indexPublished': published, 'worktreeWritten': False, 'networkUsed': False, 'hooksExecuted': False, 'filtersExecuted': False, 'gitBuiltinAttributeNormalizationMayApply': action in ('stage', 'resolve')}
        except Exception as error:
            if published or publication_entered:
                raise Rejected('git_publication_observation_unknown', 'The original Git publication may have completed but final observation/cleanup failed. Read the original operation and current Git state; do not replay this mutation.') from error
            raise
        finally:
            if temporary.exists():
                temporary.unlink()
            if index_lock.exists():
                now = index_lock.lstat()
                if (now.st_dev, now.st_ino) == (inode.st_dev, inode.st_ino):
                    index_lock.unlink()

def private_pool(owner=None):
    value = os.environ.get('POCKETWORLD_PRIVATE_GIT_POOL', '')
    if not value or not value.startswith('/') or len(value) > 4096:
        raise Rejected('git_private_pool_not_admitted', 'This private worktree requires its original native pool admission.')
    pool = Path(value)
    if not pool.is_dir() or pool.is_symlink() or pool.resolve() != pool:
        raise Rejected('git_private_pool_changed', 'The original private pool is not a canonical directory.')
    actual = json.loads(own_regular(pool / 'git-isolation-owner.json', 8192).decode('utf-8'))
    keys = {'format', 'targetCheckoutId', 'sourceCheckoutId', 'projectId', 'baseCommit', 'branch', 'ownerTaskId', 'ownerRunId', 'ownerOperationId', 'ownerEpoch', 'originPlanDigest', 'originSourceDigest'}
    if not isinstance(actual, dict) or set(actual) != keys or actual.get('format') != 'pocketworld.private-git-worktree.v1' or actual.get('targetCheckoutId') != pool.name or owner is not None and canonical(actual) != canonical(owner):
        raise Rejected('git_private_pool_changed', 'The private pool is not owned by this exact original operation.')
    return pool, actual

def source_stamp(pins):
    value = ''.join(str(len(path.encode('utf-8'))) + ':' + path + ':' + pin['sha256'] + ':' + str(pin['bytes']) + '\\n' for path, pin in sorted(pins.items(), key=lambda item: item[0].encode('utf-16-be')))
    return digest(value.encode('utf-8'))


def isolate(git, request):
    expected = request.get('expected')
    before = git.snapshot()
    if canonical(expected) != canonical(before['pin']):
        raise Rejected('git_review_stale', 'Original HEAD/index/worktree changed after isolation review.')
    base, branch = request.get('baseCommit'), request.get('branch')
    if not isinstance(base, str) or not HEX.fullmatch(base) or not isinstance(branch, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]{0,79}', branch):
        raise Rejected('git_isolation_selection_invalid', 'Use an exact local commit and an ordinary new branch name.')
    git.command(['check-ref-format', 'refs/heads/' + branch])
    resolved = git.command(['rev-parse', '--verify', '--end-of-options', base + '^{commit}']).decode('ascii').strip()
    if resolved != base or before['pin']['head'] == 'unborn':
        raise Rejected('git_isolation_base_invalid', 'The selected exact object is not an existing local commit.')
    git.command(['merge-base', '--is-ancestor', base, before['pin']['head']])
    existing = git.command(['for-each-ref', '--format=%(refname)', 'refs/heads/' + branch]).decode('utf-8').splitlines()
    if 'refs/heads/' + branch in existing:
        raise Rejected('git_isolation_branch_exists', 'That branch already exists in the source; nothing is overwritten.')
    tree = git.command(['ls-tree', '-r', '-l', '-z', '--full-tree', base])
    total, count = 0, 0
    for record in tree.split(bytes([0])):
        if not record:
            continue
        header, raw_path = record.split(b'\\t', 1)
        mode, kind, oid, size = header.split()
        path = path_value(raw_path.decode('utf-8', errors='strict'))
        if mode not in (b'100644', b'100755') or kind != b'blob' or any(p in SOURCE_OMIT for p in path.split('/')) or path.split('/')[0] in ('build', 'dist'):
            raise Rejected('git_isolation_tree_unsupported', 'The selected commit includes a symlink, submodule, metadata or excluded cache/output path; no worktree was created.')
        count += 1; total += int(size)
        if int(size) > MAX_FILE_BYTES or count > MAX_FILES or total > MAX_TOTAL_BYTES:
            raise Rejected('git_isolation_tree_limit', 'The selected commit exceeds the original bounded source limits.')
    pool, owner = private_pool()
    if owner['targetCheckoutId'] != request.get('targetCheckoutId') or owner['baseCommit'] != base or owner['branch'] != branch:
        raise Rejected('git_private_pool_changed', 'The original pool does not match the approved branch/base/target.')
    target, common = pool / 'files', pool / 'common'
    if target.exists() or target.is_symlink() or common.exists() or common.is_symlink():
        raise Rejected('git_isolation_target_exists', 'An existing or failed private worktree is never overwritten or replayed.')
    git.require(expected)
    entered = False
    try:
        target.mkdir(); entered = True
        private = Git(target, request['gitExecutableSha256'], common, common, True)
        fmt = git.command(['rev-parse', '--show-object-format']).decode('ascii').strip()
        private.command(['init', '--bare', '--template=', '--initial-branch=pocketworld-base', '--object-format=' + fmt])
        # Git enumerates exactly this reviewed commit's reachable object graph. No config,
        # hooks, remotes, credentials, unrelated refs, hardlinks or network are copied.
        import resource
        limits = resource.getrlimit(resource.RLIMIT_FSIZE)
        hard = limits[1]
        soft = 64 * 1024 * 1024 if hard == resource.RLIM_INFINITY else min(hard, 64 * 1024 * 1024)
        try:
            resource.setrlimit(resource.RLIMIT_FSIZE, (soft, hard))
            pack = git.command(['pack-objects', '--revs', '--delta-base-offset', '--no-reuse-delta', '--window=0', str(common / 'objects/pack/pack')], data=(base + '\\n').encode('ascii')).decode('ascii').strip()
        finally:
            resource.setrlimit(resource.RLIMIT_FSIZE, limits)
        if not HEX.fullmatch(pack):
            raise Rejected('git_isolation_objects_incomplete', 'Git did not return a complete private object pack identity.')
        packed = [common / 'objects/pack' / ('pack-' + pack + suffix) for suffix in ('.pack', '.idx')]
        if sum(p.stat().st_size for p in packed) > 64 * 1024 * 1024:
            raise Rejected('git_isolation_objects_limit', 'The reachable Git objects exceed the private 64MiB pool budget.')
        for file in packed:
            if file.is_symlink() or not file.is_file() or file.stat().st_nlink != 1:
                raise Rejected('git_isolation_objects_incomplete', 'Only independent ordinary private object files are accepted.')
        git.require(expected)
        private.command(['worktree', 'add', '--no-checkout', '-b', branch, '--', str(target), base])
        active = Git(target, request['gitExecutableSha256'], common / 'worktrees/files', common)
        active.private_owner = owner
        active.guard()
        active.command(['read-tree', '--reset', '-u', base])
        final = active.snapshot()
        if final['pin']['head'] != base or final['pin']['headRef'] != 'refs/heads/' + branch:
            raise Rejected('git_isolation_head_mismatch', 'The created worktree did not retain its exact reviewed commit/branch.')
        git.require(expected)
        return {'targetCheckoutId': owner['targetCheckoutId'], 'sourceCheckoutId': owner['sourceCheckoutId'], 'baseCommit': base, 'branch': branch, 'ownership': owner, 'sourceStamp': source_stamp(final['filePins']), 'fileCount': len(final['filePins']), 'pin': final['pin'], 'privateCommonRepository': True, 'directlyLinkedToSourceRepository': False, 'sourceRepositoryWritten': False, 'sourceWorktreeWritten': False, 'networkUsed': False, 'hooksExecuted': False, 'filtersExecuted': False, 'failedPoolAutomaticallyDeleted': False}
    except Exception as failure:
        if entered:
            raise Rejected('git_isolation_incomplete', 'The private pool may contain objects or a partial worktree. The source and current selection are preserved; keep the original operation and failed pool, do not replay or delete it automatically.') from failure
        raise

def main(request):
    if not isinstance(request, dict) or request.get('version') != 1 or request.get('action') not in ACTIONS:
        raise Rejected('git_request_invalid', 'Only the fixed project Git protocol is accepted.')
    root = Path.cwd()
    if root.is_symlink() or root.resolve() != root:
        raise Rejected('git_checkout_not_canonical', 'Original checkout identity is not canonical.')
    git = Git(root, request['gitExecutableSha256'])
    if request.get('privateWorktree') is not None:
        pool, owner = private_pool(request['privateWorktree'])
        git = Git(root, request['gitExecutableSha256'], pool / 'common/worktrees/files', pool / 'common')
        git.private_owner = owner
    action = request['action']
    if action == 'init':
        if (root / '.git').exists() or (root / '.git').is_symlink():
            raise Rejected('git_repository_already_exists', 'An existing repository is never reinitialized or overwritten.')
        expected = request.get('expectedWorktreeSha256')
        if expected != digest(canonical(source_pins(root))):
            raise Rejected('git_review_stale', 'Source files differ from the reviewed new local repository.')
        try:
            git.command(['init', '--template=', '--initial-branch=main', '--object-format=sha1'])
            git.guard()
            snapshot = git.snapshot()
            return {'initialized': True, 'sourceFilesAutomaticallyStaged': False, 'pin': snapshot['pin'], 'gitVersion': snapshot['gitVersion'], 'repositoryPresent': True}
        except Exception as error:
            raise Rejected('git_publication_observation_unknown', 'Git initialization was entered but its complete result is not confirmed. Inspect the original operation and repository; do not silently initialize again.') from error
    if action == 'status' and not git.git.exists() and not git.git.is_symlink():
        pins = source_pins(root)
        return {'repositoryPresent': False, 'worktreeSha256': digest(canonical(pins)), 'fileCount': len(pins), 'sourceFilesAutomaticallyStaged': False, 'readOnly': True, 'canInitializeLocal': True, 'gitVersion': git.command(['--version']).decode('ascii').strip()}
    git.guard()
    if action == 'status':
        snapshot = git.snapshot()
        offset, limit = request.get('offset', 0), request.get('limit', 40)
        if type(offset) is not int or offset < 0 or type(limit) is not int or not 1 <= limit <= 40:
            raise Rejected('git_status_cursor_invalid', 'Choose a bounded status page.')
        if request.get('expected') is not None and canonical(request['expected']) != canonical(snapshot['pin']):
            raise Rejected('git_review_stale', 'Status cursor belongs to another HEAD/index/worktree version; refresh first.')
        records = snapshot['records'];end = min(len(records), offset + limit)
        return {'pin': snapshot['pin'], 'gitVersion': snapshot['gitVersion'], 'entries': records[offset:end], 'offset': offset, 'nextOffset': end, 'totalEntries': len(records), 'truncated': end < len(records), 'indexLocked': snapshot['indexLocked'], 'mergeParentIds': snapshot['mergeParents'], 'readOnly': True, 'statusLayers': ['head_index', 'index_worktree'], 'repositoryPresent': True, 'repositoryRoot': 'original_checkout_only', 'atomicFilesystemSnapshot': False}
    if action == 'isolate':
        return isolate(git, request)
    if action == 'history':
        return git.history(request)
    if action == 'diff':
        return git.diff(request)
    return git.mutate(request)

if __name__ == '__main__':
    try:
        request = json.loads(sys.argv[1])
        result = main(request)
        rendered = json.dumps({'format': FORMAT, 'ok': True, 'code': 'git_' + request['action'] + '_observed', 'result': result}, ensure_ascii=False, separators=(',', ':'))
        if len(rendered.encode('utf-8')) > 60000:
            raise Rejected('git_publication_observation_unknown' if request['action'] in ('init', 'stage', 'unstage', 'resolve', 'commit', 'isolate') else 'git_result_page_limit', 'Original result exceeds this bounded native page. No truncated result is accepted as complete; inspect the original operation without replaying mutations.')
        print(rendered)
    except Rejected as failure:
        print(json.dumps({'format': FORMAT, 'ok': False, 'code': failure.code, 'message': failure.message, 'effectStatus': 'unknown' if failure.code in ('git_publication_observation_unknown', 'git_isolation_incomplete') else 'not_published'}, ensure_ascii=True, separators=(',', ':')))
        sys.exit(2)
    except Exception as failure:
        print(json.dumps({'format': FORMAT, 'ok': False, 'code': 'git_observation_failed', 'message': 'Original Git observation failed; no complete or successful result is inferred.', 'exceptionType': type(failure).__name__, 'effectStatus': 'unknown'}, ensure_ascii=True, separators=(',', ':')))
        sys.exit(3)
""";
}
