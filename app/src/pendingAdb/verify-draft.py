#!/usr/bin/env python3
"""Compile the pending production files against real release APIs; run controlled host tests.

No Gradle, Android device, TLS pairing, Android Conscrypt JNI or SIGKILL is exercised.
Dependencies are supplied separately, matched to the observed release archive hashes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

base = Path(__file__).resolve().parent
repo = base.parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--dependency-dir', type=Path, required=True)
parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')))
parser.add_argument('--android-home', type=Path, default=Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/nonexistent-android-sdk'))))
parser.add_argument('--gradle-cache', type=Path, default=Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle')))/'caches')
parser.add_argument('--instrumented-sdk-dir', type=Path, default=Path.home()/'.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7')
parser.add_argument('--main-classes', type=Path, help='Optional unchanged mainline app contract classes; also checks current DisplayModule/display sources')
args = parser.parse_args()
sdk = args.android_home/'platforms/android-35/android.jar'
if not sdk.is_file():
    raise SystemExit('ANDROID_HOME must provide the actual Android SDK35 android.jar')
metadata = json.loads((base/'DEPENDENCIES.json').read_text())
for artifact in metadata['artifacts']:
    path = args.dependency_dir/artifact['file']
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != artifact['sha256']:
        raise SystemExit(f'Missing or changed observed release archive: {path.name}; review before testing')
cached = list((args.gradle_cache/'modules-2/files-2.1').rglob('*.jar'))
cached += list((args.gradle_cache/'8.9/transforms').rglob('classes.jar'))
host_provider = [path for path in cached if path.name == 'conscrypt-openjdk-uber-2.5.2.jar']
if not host_provider:
    raise SystemExit('Robolectric host-only Conscrypt2.5.2 must already be cached')
files = list((base/'java').rglob('*.java')) + list((base/'test').rglob('*.java'))
files.append(repo/'app/src/main/java/dev/pocketengine/store/SecretVault.java')
test_names = ['dev.pocketengine.phone.display.adb.AdbBootstrapPolicyTest', 'dev.pocketengine.phone.display.adb.AdbBootstrapLifecycleTest', 'dev.pocketengine.phone.display.adb.LocalAdbDiscoveryTest']
main_classes = []
if args.main_classes:
    if not args.main_classes.is_dir():
        raise SystemExit('--main-classes must contain previously compiled unchanged app contracts')
    main_classes = [args.main_classes]
    files += list((repo/'app/src/main/java/dev/pocketengine/phone/display').glob('*.java'))
    files += list((repo/'display-helper/src').rglob('*.java'))
    files += [repo/'app/src/test/java/dev/pocketengine/phone/display'/name for name in ['DisplayModuleTest.java', 'DisplayAdbIntegrationTest.java']]
    test_names += ['dev.pocketengine.phone.display.DisplayModuleTest', 'dev.pocketengine.phone.display.DisplayAdbIntegrationTest']
with tempfile.TemporaryDirectory(prefix='pocketworld-pending-adb-') as temporary:
    work = Path(temporary)
    releases = []
    for artifact in metadata['artifacts']:
        path = args.dependency_dir/artifact['file']
        if path.suffix == '.aar':
            target = work/(path.stem+'-classes.jar')
            with zipfile.ZipFile(path) as archive:
                target.write_bytes(archive.read('classes.jar'))
            releases.append(target)
        else:
            releases.append(path)
    classes = work/'classes'
    classes.mkdir()
    compile_cp = os.pathsep.join(map(str, [sdk] + main_classes + releases + cached))
    subprocess.run([str(args.java_home/'bin/javac'), '--release', '17', '-proc:none', '-cp', compile_cp,
                    '-sourcepath', '', '-d', str(classes)] + list(map(str, files)), check=True)
    # Android's JNI provider cannot run on a Linux JVM. The test fixture never calls
    # pair/connect; only Robolectric's ordinary OpenJDK provider is used at runtime.
    runtime_releases = [path for path in releases if not path.name.startswith('conscrypt-android-')]
    runtime_cp = os.pathsep.join(map(str, [classes, sdk] + main_classes + host_provider + runtime_releases + cached))
    result = subprocess.run([str(args.java_home/'bin/java'), '-Drobolectric.offline=true',
        '-Drobolectric.dependency.dir='+str(args.instrumented_sdk_dir), '-cp', runtime_cp,
        'org.junit.runner.JUnitCore'] + test_names, capture_output=True, text=True, timeout=90)
    print(result.stdout, end='')
    print(result.stderr, end='')
    raise SystemExit(result.returncode)
