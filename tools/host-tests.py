#!/usr/bin/env python3
"""Run explicitly selected, registered host integration tests from a clean checkout.

Uses the project's existing locked AGP/JUnit classpath, not private compiled classes.
The two Android packaging-only preBuild tasks are excluded, never replaced by stubs.
The selected actual host test bodies must run and produce nonempty JUnit XML.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / 'tools/host-tests.json'
SDK_JAR = 'android-all-instrumented-15-robolectric-13954326-i7.jar'


class PrerequisiteError(RuntimeError):
    pass


def executable(value, label):
    found = shutil.which(value)
    if not found:
        raise PrerequisiteError('Missing ' + label + ': ' + value)
    return str(Path(found).resolve())


def select(registry, groups, classes):
    chosen = list(dict.fromkeys(groups))
    for name in classes:
        found = [group for group, entry in registry.items() if name in [entry['class']] + entry.get('additionalClasses', [])]
        if not found:
            raise PrerequisiteError('Unregistered exact host test class: ' + name + '; use --list')
        chosen.extend(found)
    chosen = list(dict.fromkeys(chosen))
    if not chosen:
        raise PrerequisiteError('Select at least one --group or exact --test; use --list (no implicit all-tests run)')
    unknown = [group for group in chosen if group not in registry]
    if unknown:
        raise PrerequisiteError('Unknown host-test group: ' + ', '.join(unknown))
    return chosen


def preflight(args, registry, groups):
    entries = [registry[group] for group in groups]
    if any(entry.get('nativeCompiler', False) for entry in entries):
        executable(os.environ.get('CC', 'cc'), 'C compiler for the native CLI protocol fixture')
    java_home = args.java_home or os.environ.get('JAVA_HOME')
    if not java_home:
        java = executable('javac', 'Java 17 JDK')
        java_home = str(Path(java).parent.parent)
    java_home = Path(java_home).resolve()
    for tool in ('java', 'javac'):
        executable(str(java_home / 'bin' / tool), 'JDK ' + tool)
    version = subprocess.check_output([str(java_home / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
    if not ('version "17.' in version or 'version "17"' in version):
        raise PrerequisiteError('Host entrypoint requires project Java 17; set JAVA_HOME or --java-home (found ' + version.splitlines()[0] + ')')
    sdk = args.android_home or os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk or not (Path(sdk) / 'platforms/android-35/android.jar').is_file():
        raise PrerequisiteError('Missing Android SDK 35; set ANDROID_HOME or --android-home')
    gradle = executable(args.gradle or str(ROOT / 'gradlew'), 'project Gradle 8.9/Wrapper')
    if not args.online and Path(gradle).resolve() == (ROOT / 'gradlew').resolve():
        home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
        distributions = home / 'wrapper/dists/gradle-8.9-bin'
        ready = [item for item in distributions.glob('*/gradle-8.9/bin/gradle')
                 if (item.parents[2] / 'gradle-8.9-bin.zip.ok').is_file()]
        if not ready:
            raise PrerequisiteError('Missing offline Gradle 8.9 Wrapper distribution in ' + str(distributions)
                                    + '; set --gradle to an installed Gradle 8.9 or explicitly use --online')
    props = {}
    if any(entry.get('robolectric') for entry in entries):
        if args.robolectric_dependency_dir:
            directory = Path(args.robolectric_dependency_dir).resolve()
            if not (directory / SDK_JAR).is_file():
                raise PrerequisiteError('Missing Robolectric SDK 35 offline dependency: ' + str(directory / SDK_JAR))
            props['robolectric.dependency.dir'] = str(directory)
        elif not args.online:
            raise PrerequisiteError('Offline Robolectric needs --robolectric-dependency-dir containing ' + SDK_JAR + '; or explicitly use --online')
    python = None
    if any(entry.get('native') or entry.get('python') for entry in entries):
        python = executable(args.python, 'Python 3')
        version = json.loads(subprocess.check_output([python, '-c',
            'import json,sys; print(json.dumps(list(sys.version_info[:3])))'], text=True))
        minimum = (3, 11) if any(entry.get('python') for entry in entries) else (3, 8)
        if version[0] != 3 or tuple(version[:2]) < minimum:
            raise PrerequisiteError('Selected group requires Python ' + '.'.join(map(str, minimum)) + '+: ' + python)
    if any(entry.get('native') for entry in entries):
        if not sys.platform.startswith('linux'):
            raise PrerequisiteError('managed-runtime currently requires Linux desktop JNI; not an Android/device test')
        executable(args.cxx, 'C++17 compiler for actual host JNI')
        if not (java_home / 'include/linux/jni_md.h').is_file():
            raise PrerequisiteError('Missing Linux JDK JNI headers: ' + str(java_home / 'include/linux/jni_md.h'))
        props['a25.python'] = python
        props['a25.node'] = executable(args.node, 'Node.js')
        executable('/bin/sh', 'POSIX shell')
    if any(entry.get('python') for entry in entries):
        props['pocket.gateway.python'] = python
    return java_home, Path(sdk).resolve(), gradle, props


def junit_directory(report):
    """Accept only the real task's XML destination inside this fresh invocation."""
    selection = report / 'gradle-selection.json'
    try:
        recorded = json.loads(selection.read_text())
    except (OSError, ValueError) as error:
        raise PrerequisiteError('Gradle did not record its actual JUnit XML report location: ' + str(selection)) from error
    if not isinstance(recorded, dict) or recorded.get('junitXmlRequired') is not True:
        raise PrerequisiteError('JUnit XML reporting was not enabled by the selected Gradle task')
    location = recorded.get('junitXmlDirectory')
    expected = (report / 'junit').resolve()
    if not isinstance(location, str) or not Path(location).is_absolute() or Path(location).resolve() != expected:
        raise PrerequisiteError('JUnit XML destination was not the current run: ' + str(location) + '; expected ' + str(expected))
    return expected


def junit_results(directory, selected):
    reports = sorted(directory.glob('TEST-*.xml'))
    if not reports:
        raise PrerequisiteError('No JUnit XML was produced in ' + str(directory) + '; zero tests is not success')
    counts = {}
    for report in reports:
        suite = ET.parse(report).getroot()
        for case in suite.iter('testcase'):
            name = case.attrib.get('classname', suite.attrib.get('name', ''))
            if name not in selected:
                raise PrerequisiteError('Unexpected test executed: ' + name)
            counts[name] = counts.get(name, 0) + 1
            if case.find('failure') is not None or case.find('error') is not None or case.find('skipped') is not None:
                raise PrerequisiteError('Selected test failed or skipped: ' + name + '.' + case.attrib.get('name', ''))
    missing = set(selected) - set(counts)
    if missing:
        raise PrerequisiteError('Selected tests did not execute: ' + ', '.join(sorted(missing)))
    return counts


def run(args):
    registry = json.loads(REGISTRY.read_text())
    if args.list:
        for group, entry in registry.items():
            print(group + '\t' + entry['class'] + '\n  ' + entry['description'])
        return 0
    groups = select(registry, args.group, args.test)
    selected = list(dict.fromkeys(name for group in groups for name in [registry[group]['class']] + registry[group].get('additionalClasses', [])))
    print('Selected: ' + ', '.join(selected), flush=True)
    print('Scope: host integration only; Android packaging/device/WAN tests are not performed.', flush=True)
    java_home, sdk, gradle, props = preflight(args, registry, groups)
    if args.check:
        print('Prerequisites available (check only; no tests executed).')
        return 0
    report_root = Path(args.output).resolve() if args.output else ROOT / 'app/build/reports/host-tests'
    report_root.mkdir(parents=True, exist_ok=True)
    # Unique run directories prevent stale XML or an interrupted previous run becoming green.
    report = Path(tempfile.mkdtemp(prefix='run-', dir=report_root))
    props.update(pocketworldHostTests=','.join(groups), pocketworldHostReport=str(report))
    env = os.environ.copy()
    env.update(JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    env.setdefault('ANDROID_USER_HOME', str(report / 'android-user-home'))
    Path(env['ANDROID_USER_HOME']).mkdir(parents=True, exist_ok=True)
    if any(registry[group].get('native') for group in groups):
        native = report / 'native'
        native.mkdir()
        source = ROOT / 'app/src/main/java/dev/pocketengine/features/local/runtime/native'
        command = [executable(args.cxx, 'C++17 compiler'), '-std=c++17', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                   '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
                   str(source / 'process_supervisor.cpp'), str(source / 'pty_supervisor.cpp'), str(source / 'pipe_supervisor.cpp'), '-o', str(native / 'libpocketprocess.so')]
        print('Building actual desktop JNI from project sources.', flush=True)
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        props['pocketworldHostNativeDir'] = str(native)
        if 'native-normal-cleanup' in groups:
            fixture = ROOT / 'app/src/test/java/dev/pocketengine/features/local/runtime/native_wait_reap_fixture.cpp'
            for name, original in [('LostWait', source / 'process_supervisor.cpp'),
                                   ('OldLostWait', ROOT / 'app/src/test/resources/runtime/ordinary-process-protocol2.cpp')]:
                isolated = report / ('native-' + name)
                isolated.mkdir()
                fault_command = command[:command.index(str(source / 'process_supervisor.cpp'))] + [
                    str(original), str(fixture), '-pthread', '-Wl,--wrap=waitid', '-Wl,--wrap=kill',
                    '-o', str(isolated / 'libpocketprocess.so')]
                subprocess.run(fault_command, cwd=ROOT, env=env, check=True)
                props['pocketworldHost' + name + 'Dir'] = str(isolated)
    command = [gradle, '--no-daemon', '--no-parallel', '--max-workers=1', '--console=plain', '-p', str(ROOT)]
    if not args.online:
        command.append('--offline')
    command += ['-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.vfs.watch=false']
    if os.environ.get('POCKETWORLD_HOST_GRADLE_JVMARGS'):
        command += ['-Dorg.gradle.jvmargs=' + os.environ['POCKETWORLD_HOST_GRADLE_JVMARGS']]
    command += ['-P' + key + '=' + value for key, value in sorted(props.items())]
    # These generate APK payloads, not Java/Kotlin or Android resources. No task replacement.
    command += ['-x', ':app:buildAndroidRuntime', '-x', ':app:buildDisplayHelper', ':app:testDebugUnitTest']
    (report / 'invocation.json').write_text(json.dumps({'groups': groups, 'classes': selected, 'command': command, 'deviceTest': False}, indent=2) + '\n')
    print('Reports: ' + str(report), flush=True)
    code = subprocess.run(command, cwd=ROOT, env=env).returncode
    if code:
        print('Host Gradle run failed (exit ' + str(code) + '). Offline runs require the existing locked dependencies in GRADLE_USER_HOME; no dependency or test was skipped.', file=sys.stderr)
        return code
    xml = junit_directory(report)
    counts = junit_results(xml, selected)
    (report / 'result.json').write_text(json.dumps({'status': 'passed', 'executed': counts, 'skipped': [], 'junitDirectory': str(xml), 'deviceTest': False}, indent=2) + '\n')
    print('Executed: ' + json.dumps(counts, sort_keys=True) + '; skipped: []; result: ' + str(report / 'result.json'))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        'Requirements: JDK 17; SDK 35/build-tools 35.0.0 and the declared NDK 28.2.13676358 '
        'for AGP configuration. managed-runtime additionally needs Linux, C++17/JNI headers, '
        '/bin/sh, Python 3.8+ and Node.js; gateway-continuation needs Python 3.11+ and CC/cc for its native CLI fixture. '
        'Robolectric offline groups need the pinned SDK 35 jar. '
        'See --list for exact registered classes and scope. Results: a unique run directory '
        'with invocation/selection, exact locked classpath, JUnit XML and result.json; '
        'zero/failed/skipped tests fail. --check does not resolve Gradle dependencies or run tests. '
        'GitHub: existing build-apk.yml manual target=host-tests, host_group=<one listed group>; '
        'no APK, signing secrets, Release, device test or full matrix.'))
    parser.add_argument('--list', action='store_true')
    parser.add_argument('--group', action='append', default=[], help='registered group; repeat to select multiple groups')
    parser.add_argument('--test', action='append', default=[], help='exact registered fully qualified class; no wildcards')
    parser.add_argument('--check', action='store_true', help='check prerequisites only, not a test pass')
    parser.add_argument('--online', action='store_true', help='explicitly allow Gradle/Robolectric dependency downloads (offline by default)')
    parser.add_argument('--java-home')
    parser.add_argument('--android-home')
    parser.add_argument('--gradle', default=os.environ.get('POCKETENGINE_GRADLE'))
    parser.add_argument('--robolectric-dependency-dir')
    parser.add_argument('--python', default='python3')
    parser.add_argument('--node', default='node')
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    parser.add_argument('--output', help='report parent directory; default app/build/reports/host-tests')
    args = parser.parse_args(argv)
    if args.list and (args.group or args.test or args.check):
        parser.error('--list cannot be combined with test selection or --check')
    try:
        return run(args)
    except (PrerequisiteError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print('Host test error: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
