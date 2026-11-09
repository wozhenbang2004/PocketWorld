"""Prepare GitHub release assets using existing production APK/delta validators.
No network or publishing. Runner is the bounded adapter in ci-release.py; caller
must supply a verified trusted main checkout and protected key files.
"""
import importlib.util
import base64
import hashlib
import json
from pathlib import Path

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('github_plan',HERE/'github-release-plan.py')
planner=importlib.util.module_from_spec(spec);spec.loader.exec_module(planner)
first_spec=importlib.util.spec_from_file_location('first_release_checks',HERE/'first-github-release.py')
first=importlib.util.module_from_spec(first_spec);first_spec.loader.exec_module(first)
SIGNER='b58e014e2a2e49b2e85ebd5b47a034a28cb0d313d71f379a5208d21afe388e58'
PIN='f869eae76eab3da1e0ab6f914da94f75b10f382e06c41338e887e06caee2d960'
MANIFEST='https://github.com/wozhenbang2004/PocketWorld/releases/latest/download/release.json'

def read(p):return json.loads(Path(p).read_text())

def verify_target_resources(runner, apk):
    # Never adopt a key from the APK or overwrite the checkout's default update chain.
    path=runner.c.get('manifest_public_der')
    if path is None:raise ValueError('Explicit approved CI public DER is required')
    spki=first.public_key(path)
    code,resources=runner.command([Path(runner.c['build_tools'])/'aapt','dump','--values','resources',apk])
    if code:raise ValueError('Cannot inspect packaged update resources')
    first.check_resources(resources,spki)

def prepare(core,runner,base_envelope,old,new,receipt,head,tag):
    work,c=runner.directory,runner.c
    # Keys must be supplied, never fabricated. This stage must not create them.
    for name in ('manifest_private_key','manifest_public_key'):
        path=Path(c.get(name,''))
        if not path.is_absolute() or not path.is_file():raise ValueError('Required signing material absent')
        if name=='manifest_private_key' and (path.resolve().is_relative_to(runner.repo.resolve()) or path.stat().st_mode & 0o077):
            raise ValueError('Signing key must be protected outside checkout')
    if core.source_state(runner)!=head:raise ValueError('Source identity mismatch')
    identity=read(receipt)
    if identity.get('commit')!=head or identity.get('sha256')!=core.digest(new) or identity.get('bytes')!=new.stat().st_size:
        raise ValueError('Target does not match trusted build receipt')
    verified_base=work/'baseline-verified.json'
    runner.tool('verify-envelope','--envelope',base_envelope,'--classpath',c['org_json_classpath'],
                '--allow-expired-baseline','--output',verified_base)
    baseline=read(verified_base)
    if (baseline.get('signatureVerified') is not True or baseline.get('pinSpkiSha256')!=PIN or
        baseline.get('apk',{}).get('sha256')!=core.digest(old) or baseline['apk'].get('bytes')!=old.stat().st_size):
        raise ValueError('Exact base APK does not match pinned signed historical manifest')
    runner.tool('verify-apk','--apk',new,'--out',work/'apk-check','--expected-signer',SIGNER)
    verify_target_resources(runner,new)
    preflight=work/'preflight.json'
    runner.tool('preflight','--old',old,'--new',new,'--output',preflight)
    proof=read(preflight)
    target=proof.get('target',{})
    if (proof.get('sameSignerSet') is not True or proof.get('strictlyNewerVersion') is not True or
        target.get('signerCertificateSha256')!=[SIGNER] or target.get('versionCode',0)<=baseline['versionCode']):
        raise ValueError('Target version/signature rejected')
    patch=work/'update.patch.gz'
    runner.tool('delta','generate','--old',old,'--new',new,'--output',patch)
    bundle=work/'production-bundle'
    runner.tool('prepare','--origin','https://github.com','--old',old,'--new',new,'--patch',patch,
                '--build-tools',c['build_tools'],'--java-home',c['java_home'],'--valid-hours','168','--output-dir',bundle)
    if read(bundle/'release-plan.json').get('manifestUrl')!=MANIFEST:
        raise ValueError('Checkout still configures a different update endpoint')
    planned=planner.plan(bundle,tag)
    payload=work/'github-unsigned-payload.json'
    with payload.open('x') as f:json.dump(planned['payload'],f,separators=(',',':'))
    signed=work/'release.json'
    runner.tool('sign','--payload',payload,'--private-key',c['manifest_private_key'],
                '--public-key',c['manifest_public_key'],'--output',signed)
    verified=work/'github-signed-verified.json'
    runner.tool('verify-envelope','--envelope',signed,'--classpath',c['org_json_classpath'],'--output',verified)
    result=read(verified)
    if (result.get('signatureVerified') is not True or result.get('pinSpkiSha256')!=PIN or
        result.get('versionCode')!=target['versionCode'] or result.get('apk')!=planned['payload']['apk']):
        raise ValueError('App verifier does not bind the planned target')
    if core.source_state(runner)!=head:raise ValueError('Source changed')
    # Evidence remains explicitly distinct from real device acceptance or publication.
    return {'status':'prepared_only','published':False,'deviceVerified':False,
            'manifest':str(signed),'assets':planned['assets'],'tag':tag,'sourceCommit':head}
