#!/usr/bin/env python3
"""Explicit post-step for host_group=browser-long-page: actual emitted programs, loopback Chromium.

The existing host-test entrypoint must have completed first. This never launches Gradle,
installs packages, reads a logged-in browser, or claims Android provider/device evidence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EMITTER = 'dev.pocketengine.browser.EmitBrowserLongPageRead'


def classpath(report_root):
    paths = list(report_root.glob('run-*/classpath.txt'))
    if len(paths) != 1:
        raise RuntimeError('Expected exactly one fresh host-test classpath; do not use a stale run')
    result = json.loads((paths[0].parent / 'result.json').read_text())
    if result.get('status') != 'passed' or not result.get('executed'):
        raise RuntimeError('Original selected host tests did not pass')
    entries = [line.strip() for line in paths[0].read_text().splitlines() if line.strip()]
    # Emitters are ordinary Java, not Robolectric: real JSON must precede Android stubs.
    real_json = [item for item in entries if Path(item).name == 'json-20240303.jar']
    if len(real_json) != 1:
        raise RuntimeError('Expected the project-locked real org.json 20240303 jar')
    return os.pathsep.join(real_json + [item for item in entries if item not in real_json])


def run(command, log, expected=0, output=None):
    print('Running ' + log.name, flush=True)
    with log.open('w') as errors:
        if output:
            with output.open('w') as data:
                code = subprocess.run(command, cwd=ROOT, stdout=data, stderr=errors).returncode
        else:
            code = subprocess.run(command, cwd=ROOT, stdout=errors, stderr=subprocess.STDOUT).returncode
    if code != expected:
        raise RuntimeError(f'{log.name}: exit {code}, expected {expected}\n' + log.read_text()[-6000:])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report-root', type=Path, required=True)
    parser.add_argument('--baseline-source', type=Path, required=True,
                        help='BrowserSemanticRead.java from the fixed pre-fix afba870 checkout')
    args = parser.parse_args()
    reports = args.report_root.resolve()
    cp = classpath(reports)
    baseline = args.baseline_source.resolve()
    if baseline.name != 'BrowserSemanticRead.java' or not baseline.is_file() or digest(baseline) != 'd5533b882e2e7d77d51c0fab1dddb52d48f4008546c7cc8d11aad22cf5c990c6':
        raise RuntimeError('Missing exact pre-fix production source')
    out = reports / 'browser-long-page'
    out.mkdir(exist_ok=False)  # Never turn earlier artifacts into a fresh pass.
    java_home = Path(os.environ['JAVA_HOME']) / 'bin'
    java, javac = str(java_home / 'java'), str(java_home / 'javac')
    baseline_classes = out / 'baseline-classes'
    baseline_classes.mkdir()
    empty = out / 'empty-sourcepath'
    empty.mkdir()
    source_paths = ['app/src/main/java/dev/pocketengine/browser/BrowserSemanticRead.java',
                    'app/src/main/java/dev/pocketengine/browser/BrowserVisibleSubtree.java',
                    'app/src/main/java/dev/pocketengine/browser/BrowserFrameObserver.java',
                    'app/src/test/java/dev/pocketengine/browser/EmitBrowserLongPageRead.java',
                    'app/src/test/js/browser-long-page.chromium.cjs',
                    'app/src/test/js/browser-long-page.test.mjs',
                    'app/src/test/js/browser-composed-cursor.test.mjs']
    identity = dict(commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    baselineCommit='afba870564e83034339ad084c2e7247260ea0c9c',
                    baselineSourceSha256=digest(baseline),
                    classpathSha256=hashlib.sha256(cp.encode()).hexdigest(),
                    sourceSha256={path: digest(ROOT / path) for path in source_paths},
                    androidProviderVerified=False, deviceVerified=False)
    (out / 'source-identity.json').write_text(json.dumps(identity, indent=2) + '\n')
    run([java, '-cp', cp, EMITTER], out / 'emit-current.log', output=out / 'current-scripts.json')
    run(['node', str(ROOT / 'app/src/test/js/browser-long-page.test.mjs'),
         str(out / 'current-scripts.json'), str(out / 'bounded-interface-receipts.json')], out / 'bounded-interfaces.log')
    run([java, '-cp', cp, EMITTER, 'verify', str(out / 'bounded-interface-receipts.json')], out / 'bounded-validation.log')
    run(['node', str(ROOT / 'app/src/test/js/browser-composed-cursor.test.mjs'),
         str(out / 'current-scripts.json'), str(out / 'composed-interface-receipts.json')], out / 'composed-interfaces.log')
    run([java, '-cp', cp, EMITTER, 'verify', str(out / 'composed-interface-receipts.json')], out / 'composed-validation.log')
    # Same compiled dependencies/emitter, only the original production class comes first.
    run([javac, '-cp', cp, '-sourcepath', str(empty), '-d', str(baseline_classes), str(baseline)], out / 'baseline-javac.log')
    run([java, '-cp', str(baseline_classes) + os.pathsep + cp, EMITTER], out / 'emit-baseline.log', output=out / 'baseline-scripts.json')
    run(['node', str(ROOT / 'app/src/test/js/browser-long-page.chromium.cjs'),
         str(out / 'baseline-scripts.json'), str(out / 'baseline-chromium.json'), '--expect-prefix-failure'],
        out / 'baseline-chromium.log', expected=3)
    red = json.loads((out / 'baseline-chromium.json').read_text())
    if red.get('status') != 'EXPECTED_PREFIX_TRUNCATION' or red.get('tailMissing') is not True:
        raise RuntimeError('Baseline did not establish the original real-DOM tail failure')
    run(['node', str(ROOT / 'app/src/test/js/browser-long-page.chromium.cjs'),
         str(out / 'current-scripts.json'), str(out / 'current-chromium.json')], out / 'current-chromium.log')
    run([java, '-cp', cp, EMITTER, 'verify', str(out / 'current-chromium.json')], out / 'chromium-validation.log')
    run([java, '-cp', cp, 'dev.pocketengine.browser.EmitBrowserFrameRead', str(out / 'frame-scripts.json')], out / 'emit-frame.log')
    run(['node', str(ROOT / 'app/src/test/js/browser-frame-read.test.mjs'), str(out / 'frame-scripts.json')], out / 'frame-regression.log')
    dynamic = {}
    request = json.dumps(dict(mode='table', query='Ledger', settle=dict(timeoutMs=6000, stableMs=600)))
    for name, observation in [('table', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), ('page2', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')]:
        dynamic[name] = subprocess.check_output([java, '-cp', cp, 'dev.pocketengine.browser.EmitBrowserDynamicRead', request, observation], cwd=ROOT, text=True)
    (out / 'dynamic-scripts.json').write_text(json.dumps(dynamic))
    run(['node', str(ROOT / 'app/src/test/js/browser-dynamic-read.chromium.cjs'),
         str(out / 'dynamic-scripts.json'), str(out / 'dynamic-chromium.json')], out / 'dynamic-regression.log')
    run([java, '-cp', cp, EMITTER, 'verify', str(out / 'dynamic-chromium.json')], out / 'dynamic-validation.log')
    (out / 'result.json').write_text(json.dumps(dict(status='passed', **identity,
        baseline='real Chromium original production prefix failure, exact expected exit 3',
        current='real Chromium tail/query/row-range/version + original dynamic regression passed',
        boundedInterfaceIsBrowserEvidence=False), indent=2) + '\n')
    print('PASS: real loopback Chromium red/green and native receipt validation; not Android/provider/device acceptance')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError, KeyError, ValueError) as error:
        print('Browser long-page error: ' + str(error), file=sys.stderr)
        sys.exit(1)
