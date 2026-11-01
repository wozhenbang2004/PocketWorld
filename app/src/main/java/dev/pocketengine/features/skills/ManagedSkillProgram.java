package dev.pocketengine.features.skills;

import java.nio.charset.StandardCharsets;

/** Fixed native glue around original Python/venv/pip/sh. Not a model command string,
 * interpreter sandbox or independent scheduler. All work stays in the original job. */
public final class ManagedSkillProgram {
    private ManagedSkillProgram(){}
    public static String source(){return SOURCE;}
    public static String identity()throws Exception{return SkillExecution.sha256(SOURCE.getBytes(StandardCharsets.UTF_8));}
    private static final String SOURCE="""
import hashlib, json, os, pathlib, resource, signal, stat, subprocess, sys, time
ROOT = pathlib.Path.cwd()
MAX_TOTAL = 256 * 1024 * 1024
MAX_FILE = 64 * 1024 * 1024
MAX_ENTRIES = 20000

def raw_file(path, maximum):
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > maximum:
        raise ValueError('original_skill_regular_file_required')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    try:
        with os.fdopen(fd, 'rb') as source:
            value = source.read(maximum + 1)
    except BaseException:
        raise
    after = path.lstat()
    if len(value) > maximum or (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns) != (after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns):
        raise ValueError('original_skill_source_changed')
    return value

def relative(value):
    if not isinstance(value, str) or not value or value.startswith('/') or '\\\\' in value or any(p in ('','..','.') for p in value.split('/')):
        raise ValueError('original_skill_relative_path_required')
    return value

def within(base, value):
    target = base
    for part in relative(value).split('/'):
        target = target / part
        if target.is_symlink(): raise ValueError('original_skill_link_rejected')
    if not target.resolve().is_relative_to(base.resolve()): raise ValueError('original_skill_path_changed')
    return target

def progress(phase, code=None):
    value = {'format':'pocketworld.managed-skill-progress.v1','phase':phase,'operationId':job['operationId'],'planDigest':job['planDigest'],'exitCode':code}
    temporary = ROOT / '.native-skill-progress.pending'
    with temporary.open('x', encoding='utf-8') as out:
        json.dump(value, out, separators=(',', ':'));out.flush();os.fsync(out.fileno())
    os.replace(temporary, ROOT / '.native-skill-progress.json')

def disk_budget():
    total = count = 0
    for folder, dirs, files in os.walk(ROOT, followlinks=False):
        for name in dirs + files:
            item = pathlib.Path(folder) / name
            data = item.lstat();count += 1
            if count > MAX_ENTRIES: raise ValueError('skill_observed_file_count_exceeded')
            if stat.S_ISREG(data.st_mode): total += data.st_size
            if total > MAX_TOTAL: raise ValueError('skill_observed_storage_budget_exceeded')
    # Observation limit, NOT a filesystem/cgroup quota or restriction on escaped writes.

def execute(argv, cwd, env, phase):
    progress(phase)
    child = subprocess.Popen(argv, cwd=str(cwd), env=env, stdin=subprocess.DEVNULL, close_fds=True)
    try:
        while child.poll() is None:
            if time.monotonic() >= end: raise TimeoutError('original_skill_deadline')
            disk_budget();time.sleep(.25)
        progress(phase, child.returncode)
        return child.returncode
    except BaseException:
        # The original native supervisor owns group cancellation/exit evidence.
        # Do not guess other tasks' PIDs or claim all detached descendants stopped.
        if child.poll() is None: child.terminate()
        try: child.wait(timeout=2)
        except subprocess.TimeoutExpired: child.kill();child.wait()
        raise

job = json.loads(raw_file(ROOT / '.native-skill.json', 65536).decode('utf-8', errors='strict'))
if job.get('format') != 'pocketworld.managed-skill-job.v1' or job.get('network') != 'possible_same_uid' or job.get('osSandbox') is not False:
    raise ValueError('original_native_phone_contract_required')
package = ROOT / 'skill-package';workspace = ROOT / 'workspace'
if package.is_symlink() or workspace.is_symlink() or not package.is_dir() or not workspace.is_dir():
    raise ValueError('original_native_mounts_required')
for item in job['sourceFiles']:
    path = within(ROOT, item['path']);value = raw_file(path, 2 * 1024 * 1024)
    if len(value) != item['bytes'] or hashlib.sha256(value).hexdigest() != item['sha256']:
        raise ValueError('original_native_source_sha_changed')
request = job['request'];script = within(package, request['scriptPath'])
if not request['scriptPath'].startswith('scripts/') or not script.is_file(): raise ValueError('original_skill_script_missing')
argv = job['interpreterArgv']
if not isinstance(argv, list) or not argv or not isinstance(argv[0], str) or not argv[0].startswith('/') or any(x in ('-c','-e','--eval','--command') for x in argv):
    raise ValueError('original_fixed_interpreter_required')
end = time.monotonic() + request['timeoutSeconds']
# Real per-file size limit, inherited by child processes. This is not a total-disk quota.
resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))
environment = {'PATH':job['path'],'LANG':'C.UTF-8','HOME':str(ROOT / 'home'),'TMPDIR':str(ROOT / 'tmp'),
               'PYTHONNOUSERSITE':'1','PYTHONDONTWRITEBYTECODE':'1','PIP_CONFIG_FILE':'/dev/null',
               'PIP_NO_INPUT':'1','PIP_DISABLE_PIP_VERSION_CHECK':'1'}
for name in ('home','tmp'): (ROOT / name).mkdir(exist_ok=True)
cwd = workspace if request['workingDirectory'] == '.' else within(workspace, request['workingDirectory'])
if not cwd.is_dir(): raise ValueError('original_skill_cwd_missing')
deps = job['dependencies']
if deps.get('packages'):
    if job['runtimeId'] != 'python3' or not job['dependencyPreparationAllowed']:
        raise ValueError('original_pinned_dependency_approval_required')
    lock = within(package, 'scripts/requirements.lock')
    if hashlib.sha256(raw_file(lock,16384)).hexdigest() != deps['requirementsSha256']:
        raise ValueError('original_requirements_sha_changed')
    if execute(argv + ['-m','venv',str(ROOT / 'venv')], ROOT, environment, 'creating_venv') != 0: sys.exit(66)
    python = str(ROOT / 'venv/bin/python')
    install = [python,'-m','pip','install','--require-hashes','--no-deps','--only-binary=:all:','--no-build-isolation','--no-compile',
               '--disable-pip-version-check','--index-url','https://pypi.org/simple','--timeout','15','--retries','0','-r',str(lock)]
    if execute(install, ROOT, environment, 'preparing_exact_wheels') != 0: sys.exit(67)
    argv = [python]
status = execute(argv + [str(script)] + request['arguments'], cwd, environment, 'running_original_script')
progress('script_exited', status)
sys.exit(status if 0 <= status <= 255 else 1)
""";
}
