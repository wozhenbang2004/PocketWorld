#!/usr/bin/env python3
"""Thin CI release adapter for a prebuilt, project-signed APK.
No build, checkout, credential storage, DPAPI, or implicit publication.
Requires a clean named main checkout at GITHUB_SHA. Work directory MUST be
outside checkout; build identity receipt MUST come from that same trusted job.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ORIGIN = 'https://binary-range-probe-nov04.k01075888660.chatgpt.site'
SIGNER = 'ed896ec1199f5be4e591a3222ddd60486ea1fce6853f1bb850aa5b699f8b01a0'
PIN = '115f38b275012709a56d037a4a52df4f7a760b5aebdd5e1ccbf63c62756c8e4a'

class Stop(RuntimeError):
    pass

def trusted(env):
    if not (env.get('GITHUB_ACTIONS') == 'true' and
            env.get('GITHUB_REPOSITORY') == 'wozhenbang2004/PocketWorld' and
            env.get('GITHUB_REF') == 'refs/heads/main' and
            env.get('GITHUB_EVENT_NAME') in ('push', 'workflow_dispatch') and
            re.fullmatch('[a-f0-9]{40}', env.get('GITHUB_SHA', ''))):
        raise Stop('Trusted canonical main push/dispatch is required')
    return env['GITHUB_SHA']

def load_core(repo):
    spec = importlib.util.spec_from_file_location('pocketworld_release_core', repo / 'tools/release.py')
    core = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(core)
    return core

class Runner:
    def __init__(self, repo, work, config):
        self.repo, self.directory, self.c = repo, work, config
        self.env = {k:v for k,v in os.environ.items() if not k.startswith('POCKETWORLD_')}
    def command(self, command, check=True, **unused):
        result = subprocess.run(list(map(str, command)), cwd=self.repo, env=self.env,
                                capture_output=True, text=True)
        if check and result.returncode:
            if any(Path(str(arg)).name == 'ci-update-tools.py' for arg in command):
                for line in result.stderr.splitlines():
                    if re.fullmatch(r'PWTRACE (?:exception [A-Za-z_]+|frame [A-Za-z0-9_.-]+:[0-9]+:[A-Za-z_<>]+)',line) or (line.startswith('PWERROR ') and len(line)<300):print(line,file=sys.stderr)
            print('PWTRACE frame ci-release.py:0:subprocess_failed',file=sys.stderr)
            # No secret-bearing stdout/stderr or command arguments in exceptions/logs.
            raise Stop('Release subprocess failed; output withheld')
        return result.returncode, result.stdout.strip()
    def git(self, *args, check=True):
        return self.command(['git', *args], check=check)
    def tool(self, name, *args):
        if name in ('verify-envelope', 'prepare'):
            if not self.c.get('manifest_public_der'): raise Stop('Explicit approved CI public DER is required')
            return self.command([sys.executable, '-B', Path(__file__).with_name('ci-update-tools.py'),
                '--repo', self.repo, '--public-key-der', self.c['manifest_public_der'],
                '--java-home', self.c['java_home'], '--build-tools', self.c['build_tools'],
                '--temp-dir', self.directory / 'temp', '--openssl', self.c['openssl'], name, *args])
        print('PWTRACE frame ci-release.py:0:'+name.replace('-','_'),file=sys.stderr)
        return self.command([sys.executable, '-B', self.repo / 'tools/windows-release.py',
            '--repo', self.repo, '--java-home', self.c['java_home'],
            '--build-tools', self.c['build_tools'], '--temp-dir', self.directory / 'temp',
            '--openssl', self.c['openssl'], name, *args])

def read(path):
    return json.loads(Path(path).read_text())

def assert_status(core, http, config, baseline_sha):
    state = http.json('/__publisher/status')
    if (state.get('current', {}).get('manifestSha256') != baseline_sha or
        state.get('pinSpkiSha256') != PIN or state.get('partBytes') != core.PART_BYTES or
        state.get('digestStreamAvailable') is not True):
        raise Stop('Publisher baseline, pin, or upload protocol mismatch')

def prepare(core, runner, http, apk, receipt, head):
    """All artifact validation uses the existing production release tools."""
    c, work = runner.c, runner.directory
    if core.source_state(runner) != head:
        raise Stop('Checkout differs from trusted source SHA')
    identity = read(receipt)
    if (identity.get('commit') != head or identity.get('sha256') != core.digest(apk) or
        identity.get('bytes') != apk.stat().st_size):
        raise Stop('APK does not match this trusted build receipt')
    base, base_apk, base_sha = core.baseline(runner, http)
    runner.tool('verify-apk', '--apk', apk, '--out', work / 'apk-check', '--expected-signer', SIGNER)
    preflight = work / 'preflight.json'
    runner.tool('preflight', '--old', base_apk, '--new', apk, '--output', preflight)
    evidence = read(preflight)
    target = evidence.get('target', {})
    version = target.get('versionCode')
    if (evidence.get('sameSignerSet') is not True or evidence.get('strictlyNewerVersion') is not True or
        type(version) is not int or not base['versionCode'] < version < 2100000000 or
        target.get('signerCertificateSha256') != [SIGNER]):
        raise Stop('APK version or signer mismatch')
    patch = work / 'update.patch.gz'
    runner.tool('delta', 'generate', '--old', base_apk, '--new', apk, '--output', patch)
    bundle = work / 'bundle'
    # prepare runs the existing production-adapter reconstruction check; do not replace it.
    runner.tool('prepare', '--origin', ORIGIN, '--old', base_apk, '--new', apk, '--patch', patch,
                '--build-tools', c['build_tools'], '--java-home', c['java_home'],
                '--valid-hours', '168', '--output-dir', bundle)
    proof = read(bundle / 'artifact-evidence.json')
    if proof.get('target', {}).get('versionCode') != version or proof['target'].get('signerCertificateSha256') != [SIGNER]:
        raise Stop('Prepared bundle differs from verified APK')
    if read(bundle / 'release-plan.json').get('manifestUrl') != ORIGIN + c['manifest_path']:
        raise Stop('Unexpected manifest destination')
    runner.tool('sign', '--payload', bundle / 'unsigned-payload.json', '--private-key', c['manifest_private_key'],
                '--public-key', c['manifest_public_key'], '--output', bundle / 'release.json')
    verified = bundle / 'signed-verified.json'
    runner.tool('verify-envelope', '--envelope', bundle / 'release.json', '--classpath', c['org_json_classpath'], '--output', verified)
    signed = read(verified)
    if (signed.get('signatureVerified') is not True or signed.get('pinSpkiSha256') != PIN or
        signed.get('versionCode') != version or signed.get('apk', {}).get('sha256') != core.digest(apk)):
        raise Stop('Signed manifest does not bind the verified APK')
    core.artifact_descriptors(bundle)
    if core.source_state(runner) != head:
        raise Stop('Source changed during preparation')
    return bundle, base_sha

def execute(core, runner, http, apk, receipt, head, publish=False):
    bundle, baseline_sha = prepare(core, runner, http, apk, receipt, head)
    if not publish:
        return {'status':'prepared_local_only', 'activated':False, 'sourceCommit':head}
    assert_status(core, http, runner.c, baseline_sha)
    # Existing uploader checks immutable artifacts, public ranges, source stability,
    # performs CAS activation last, and reconciles a lost activation response.
    result = core.upload_release(runner, http, bundle, baseline_sha, head, stage_only=False)
    if result.get('status') != 'published' or result.get('activated') is not True:
        raise Stop('Publication was not verified')
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('repo','work','apk','receipt','config'):
        p.add_argument('--'+name, required=True, type=Path)
    p.add_argument('--publish', action='store_true')
    args=p.parse_args()
    head=trusted(os.environ)
    repo,work=args.repo.resolve(),args.work.resolve()
    if work.is_relative_to(repo) or work.exists():
        raise Stop('Use a new release directory outside the checkout')
    c=read(args.config)
    required=('java_home','build_tools','openssl','org_json_classpath','manifest_public_key','manifest_private_key')
    if any(not isinstance(c.get(k),str) for k in required):
        raise Stop('Missing tool or protected key path')
    for key in ('manifest_private_key','manifest_public_key','org_json_classpath','openssl'):
        path=Path(c[key])
        if not path.is_absolute() or not path.is_file(): raise Stop('Required file unavailable')
    key=Path(c['manifest_private_key']).resolve()
    if key.is_relative_to(repo) or key.stat().st_mode & 0o077:
        raise Stop('Manifest signing key must be private and outside checkout')
    if any(k in c for k in ('token','password','access_token','publisher_token_file','proxy')):
        raise Stop('Credentials/proxy must not appear in CI config')
    token=os.environ.pop('POCKETWORLD_PUBLISHER_TOKEN','')
    if args.publish and not re.fullmatch('[A-Za-z0-9_-]{40,100}',token):
        raise Stop('Missing or malformed publisher secret')
    c.update(origin=ORIGIN, manifest_path='/releases/ed896/release.json',
             public_pin_sha256=PIN, apk_signer_sha256=SIGNER,
             artifact_root=str(work/'artifacts'), upload_concurrency=2)
    work.mkdir(mode=0o700, parents=True)
    (work/'artifacts').mkdir()
    core=load_core(repo)
    runner=Runner(repo,work,c)
    http=core.Http(ORIGIN)
    if args.publish: http.token=token
    result=execute(core,runner,http,args.apk.resolve(),args.receipt.resolve(),head,args.publish)
    print(json.dumps(result))

if __name__ == '__main__':
    try: main()
    except Exception:
        # Never emit provider response, subprocess output, secret value, or traceback.
        print('CI release stopped safely; preparation or verification failed. No success claimed.', file=sys.stderr)
        sys.exit(1)
