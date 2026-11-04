#!/usr/bin/env bash
# Isolate these authority-fence regression classes; the normal test suite is unchanged.
set -euo pipefail

PW_TEST_REPO=$(git rev-parse --show-toplevel)
cd "$PW_TEST_REPO"
PW_TEST_SOURCE=app/src/test/kotlin/dev/pocketengine/ui/web/PocketWebTaskReviewsTest.kt
PW_TEST_CLASS=dev.pocketengine.ui.web.PocketWebTaskReviewsTest
PW_FENCE_SOURCE=app/src/test/kotlin/dev/pocketengine/ui/web/PocketWebDocumentFenceTest.kt
PW_FENCE_CLASS=dev.pocketengine.ui.web.PocketWebDocumentFenceTest
PW_TEST_DIR=build-artifacts/web-bridge-tests
PW_TEST_SOURCES=build-artifacts/web-bridge-test-src
rm -rf "$PW_TEST_SOURCES" "$PW_TEST_DIR"
mkdir -p "$PW_TEST_DIR" "$PW_TEST_SOURCES/dev/pocketengine/ui/web"
rm -f "app/build/test-results/testDebugUnitTest/TEST-${PW_TEST_CLASS}.xml" \
      "app/build/test-results/testDebugUnitTest/TEST-${PW_FENCE_CLASS}.xml"
cp "$PW_TEST_SOURCE" "$PW_TEST_SOURCES/dev/pocketengine/ui/web/"
cp "$PW_FENCE_SOURCE" "$PW_TEST_SOURCES/dev/pocketengine/ui/web/"
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
    :app:testDebugUnitTest --tests "$PW_TEST_CLASS" --tests "$PW_FENCE_CLASS" \
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
    'scope': 'Only the requested pure JVM review-fence and document-fence classes are compiled and run; the normal legacy test suite is unchanged and unverified',
    'test_source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'subject_source_sha256': hashlib.sha256(subject.read_bytes()).hexdigest(),
    'tests': 0, 'failures': None, 'errors': None, 'skipped': None,
}
def read_class(class_name, class_source, class_subject):
    xml = pathlib.Path('app/build/test-results/testDebugUnitTest/TEST-' + class_name + '.xml')
    result = {
        'test_class': class_name,
        'test_source_sha256': hashlib.sha256(class_source.read_bytes()).hexdigest(),
        'subject_source_sha256': hashlib.sha256(class_subject.read_bytes()).hexdigest(),
        'tests': 0, 'failures': None, 'errors': None, 'skipped': None,
    }
    if not xml.is_file():
        result['report_error'] = 'Requested JUnit XML is missing'
        return result
    shutil.copyfile(xml, root / xml.name)
    try:
        suite = ET.parse(xml).getroot()
        if suite.tag != 'testsuite' or suite.attrib.get('name') != class_name:
            raise ValueError('JUnit suite does not match requested class')
        counts = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        cases = suite.findall('testcase')
        if counts['tests'] != len(cases) or any(case.attrib.get('classname') != class_name for case in cases):
            raise ValueError('JUnit test cases do not match requested class and count')
        if any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
            raise ValueError('JUnit test case contains failure, error, or skip')
        result.update(counts)
        result['test_cases'] = [case.attrib.get('name') for case in cases]
    except (ET.ParseError, ValueError, KeyError) as error:
        result['report_error'] = str(error)
    return result

classes = [
    read_class(name, source, subject),
    read_class('dev.pocketengine.ui.web.PocketWebDocumentFenceTest',
               pathlib.Path('app/src/test/kotlin/dev/pocketengine/ui/web/PocketWebDocumentFenceTest.kt'),
               pathlib.Path('app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebDocumentFence.kt')),
]
# Preserve legacy identity fields for the original class and include both class receipts.
receipt.update(classes[0])
receipt['classes'] = classes
receipt['status'] = 'passed' if (not os.environ.get('GITHUB_SHA') or commit == os.environ['GITHUB_SHA']) and receipt['gradle_exit_code'] == 0 and all(
    item['tests'] > 0 and item['failures'] == item['errors'] == item['skipped'] == 0
    and 'report_error' not in item for item in classes) else 'failed'
(root / 'identity.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({key: receipt[key] for key in ('test_class', 'gradle_exit_code', 'tests', 'failures', 'errors', 'skipped', 'status')}))
assert not os.environ.get('GITHUB_SHA') or commit == os.environ['GITHUB_SHA'], 'Source commit differs from the workflow revision'
assert receipt['status'] == 'passed', 'Isolated Web fence regressions failed; inspect the retained log and XML'
PY
