#!/usr/bin/env bash
# Isolate this authority-fence regression class; the normal test suite is unchanged.
set -euo pipefail

PW_TEST_REPO=$(git rev-parse --show-toplevel)
cd "$PW_TEST_REPO"
PW_TEST_SOURCE=app/src/test/kotlin/dev/pocketengine/ui/web/PocketWebTaskReviewsTest.kt
PW_TEST_CLASS=dev.pocketengine.ui.web.PocketWebTaskReviewsTest
PW_TEST_DIR=build-artifacts/web-bridge-tests
PW_TEST_SOURCES=build-artifacts/web-bridge-test-src
mkdir -p "$PW_TEST_DIR" "$PW_TEST_SOURCES/dev/pocketengine/ui/web"
cp "$PW_TEST_SOURCE" "$PW_TEST_SOURCES/dev/pocketengine/ui/web/"
cat > build-artifacts/web-bridge-only.init.gradle <<'GRADLE'
allprojects {
    afterEvaluate {
        if (plugins.hasPlugin('com.android.application')) {
            def isolatedSources = rootProject.file('build-artifacts/web-bridge-test-src')
            android.sourceSets.getByName('test').java.setSrcDirs([isolatedSources])
            kotlin.sourceSets.getByName('test').kotlin.setSrcDirs([isolatedSources])
        }
    }
}
GRADLE

PW_TEST_EXIT=0
./gradlew --no-daemon --no-parallel --max-workers=1 --console=plain \
    --init-script build-artifacts/web-bridge-only.init.gradle \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Dorg.gradle.vfs.watch=false \
    :app:testDebugUnitTest --tests "$PW_TEST_CLASS" \
    2>&1 | tee build-artifacts/web-bridge-tests.log || PW_TEST_EXIT=$?

PW_TEST_EXIT="$PW_TEST_EXIT" python3 - <<'PY' 2>&1 | tee -a build-artifacts/web-bridge-tests.log
import hashlib, json, os, pathlib, shutil, subprocess, xml.etree.ElementTree as ET

name = 'dev.pocketengine.ui.web.PocketWebTaskReviewsTest'
root = pathlib.Path('build-artifacts/web-bridge-tests')
source = pathlib.Path('app/src/test/kotlin/dev/pocketengine/ui/web/PocketWebTaskReviewsTest.kt')
subject = pathlib.Path('app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebBridge.kt')
report = pathlib.Path('app/build/test-results/testDebugUnitTest/TEST-' + name + '.xml')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
receipt = {
    'commit': commit, 'run_id': os.environ.get('GITHUB_RUN_ID'),
    'run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
    'test_class': name, 'gradle_exit_code': int(os.environ['PW_TEST_EXIT']),
    'scope': 'Only the requested pure JVM review-fence class is compiled and run; the normal legacy test suite is unchanged and unverified',
    'test_source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'subject_source_sha256': hashlib.sha256(subject.read_bytes()).hexdigest(),
    'tests': 0, 'failures': None, 'errors': None, 'skipped': None,
}
if report.is_file():
    shutil.copyfile(report, root / report.name)
    try:
        suite = ET.parse(report).getroot()
        receipt.update({key: int(suite.attrib.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')})
        receipt['test_cases'] = [case.attrib.get('name') for case in suite.findall('testcase')]
    except (ET.ParseError, ValueError) as error:
        receipt['report_error'] = str(error)
else:
    receipt['report_error'] = 'Requested JUnit XML is missing'
receipt['status'] = 'passed' if receipt['gradle_exit_code'] == 0 and receipt['tests'] > 0 and receipt['failures'] == receipt['errors'] == receipt['skipped'] == 0 else 'failed'
(root / 'identity.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({key: receipt[key] for key in ('test_class', 'gradle_exit_code', 'tests', 'failures', 'errors', 'skipped', 'status')}))
assert not os.environ.get('GITHUB_SHA') or commit == os.environ['GITHUB_SHA'], 'Source commit differs from the workflow revision'
assert receipt['status'] == 'passed', 'Isolated Web task review regression failed; inspect the retained log and XML'
PY
