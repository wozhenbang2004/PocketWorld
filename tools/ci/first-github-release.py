"""First-release preparation: new manifest trust root, full APK only, no old base.
Uses production APK inspection, signing, and App verifier. Does not publish.
The caller provides a trusted main Runner and separately project-signed build.
"""
import base64
import hashlib
import json
from pathlib import Path
import re

PIN='f869eae76eab3da1e0ab6f914da94f75b10f382e06c41338e887e06caee2d960'
SIGNER='b58e014e2a2e49b2e85ebd5b47a034a28cb0d313d71f379a5208d21afe388e58'
MANIFEST='https://github.com/wozhenbang2004/PocketWorld/releases/latest/download/release.json'

def version_tag(code):
    if type(code) is not int or not 1000 <= code <= 2100000000:
        raise ValueError('Public release requires Android versionCode >= 1000')
    return 'v0.1.' + str(code - 1000)

def version_code(tag):
    match = re.fullmatch(r'v0\.1\.(0|[1-9][0-9]*)', tag) if isinstance(tag, str) else None
    if match is None:
        raise ValueError('Expected canonical public release tag v0.1.N')
    code = 1000 + int(match.group(1))
    if code > 2100000000:
        raise ValueError('Public release exceeds Android versionCode bound')
    return code

def public_key(path):
    data=Path(path).read_bytes()
    if hashlib.sha256(data).hexdigest()!=PIN:raise ValueError('Unapproved bootstrap public key')
    return base64.b64encode(data).decode('ascii')

def require_version_floor(version, floor):
    # Explicit operator-configured floor, not a claimed online/latest observation.
    if type(floor) is not int or not 1<=floor<2100000000:
        raise ValueError('Explicit trusted release version floor is required')
    if type(version) is not int or not floor<version<=2100000000:
        raise ValueError('Release version must exceed configured floor')

def build_command(der,version,name,minimum_version):
    require_version_floor(version,minimum_version)
    if type(version) is not int or not 1<=version<=2100000000:raise ValueError('Invalid explicit version')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+\-]{0,79}',name):raise ValueError('Invalid version name')
    return ['./gradlew','--no-daemon',':app:testDebugUnitTest',':app:assembleDebug',
            '-PpocketworldVersionCode='+str(version),'-PpocketworldVersionName='+name,
            '-PpocketworldUpdateManifestUrl='+MANIFEST,'-PpocketworldUpdatePublicKey='+public_key(der)]

def check_resources(text,spki):
    for key,wanted in [('pocketworld_update_manifest_url',MANIFEST),('pocketworld_update_public_key',spki)]:
        blocks=re.findall(r'(?m)^[ \t]*resource\s+0x[0-9a-fA-F]+\s+[^\n]*string/'+re.escape(key)+r'(?=[:\s])[^\n]*\n(?:(?![^\n]*resource\s+0x)[^\n]*\n?)*',text)
        if len(blocks)!=1:raise ValueError('Missing or duplicate packaged update resource')
        values=re.findall(r'\(string(?:8|16)?\)\s+"([^"\n]*)"',blocks[0])
        if not values or any(v!=wanted for v in values):raise ValueError('Packaged update configuration mismatch')

def payload(metadata,tag,now,minimum_version):
    require_version_floor(metadata.get('versionCode'),minimum_version)
    if (metadata.get('packageName')!='dev.pocketengine' or metadata.get('signerCertificateSha256')!=[SIGNER] or
        type(metadata.get('versionCode')) is not int or not 1<=metadata['versionCode']<=2100000000 or
        type(metadata.get('bytes')) is not int or not 1<=metadata['bytes']<=256*1024*1024 or
        not re.fullmatch('[a-f0-9]{64}',metadata.get('sha256','')) or
        type(now) is not int or now<=0):raise ValueError('Invalid inspected APK identity')
    if tag!=version_tag(metadata['versionCode']):raise ValueError('First-release tag must bind explicit APK version')
    filename=metadata['sha256']+'-app-'+str(metadata['versionCode'])+'.apk'
    return {'schema':1,'packageName':'dev.pocketengine','versionCode':metadata['versionCode'],
            'versionName':metadata['versionName'],'minSdk':metadata['minSdk'],'issuedAt':now,
            'expiresAt':now+7*86400000,'notes':'','deltas':[],
            'apk':{'url':'https://github.com/wozhenbang2004/PocketWorld/releases/download/'+tag+'/'+filename,
                   'bytes':metadata['bytes'],'sha256':metadata['sha256']}}

def prepare(core,runner,inspector,apk,receipt,head,der,tag,now):
    c,work=runner.c,runner.directory
    floor=c.get('minimum_version')
    if type(floor) is not int or not 1<=floor<2100000000:
        raise ValueError('Explicit trusted release version floor is required')
    spki=public_key(der)
    for key in ('manifest_private_key','manifest_public_key'):
        p=Path(c.get(key,''))
        if not p.is_absolute() or not p.is_file():raise ValueError('Required existing signing file missing')
        if key=='manifest_private_key' and (p.stat().st_mode&0o077 or p.resolve().is_relative_to(runner.repo.resolve())):
            raise ValueError('Private key must be protected outside checkout')
    # CI uses an explicit public pin; tracked defaults remain untouched until release succeeds.
    configured_der=c.get('manifest_public_der')
    if configured_der is None or public_key(configured_der)!=spki:
        raise ValueError('Explicit CI verifier public key differs from bootstrap build')
    if core.source_state(runner)!=head:raise ValueError('Source changed')
    record=json.loads(Path(receipt).read_text())
    if record.get('commit')!=head or record.get('sha256')!=core.digest(apk) or record.get('bytes')!=apk.stat().st_size:
        raise ValueError('Trusted build receipt mismatch')
    runner.tool('verify-apk','--apk',apk,'--out',work/'apk-check','--expected-signer',SIGNER)
    metadata=inspector.inspect_apk(apk,Path(c['build_tools']))
    _,resources=runner.command([Path(c['build_tools'])/'aapt','dump','--values','resources',apk])
    check_resources(resources,spki)
    floor=c.get('minimum_version')
    data=payload(metadata,tag,now,floor)
    unsigned=work/'bootstrap-unsigned.json'
    with unsigned.open('x') as f:json.dump(data,f,separators=(',',':'))
    signed=work/'release.json'
    runner.tool('sign','--payload',unsigned,'--private-key',c['manifest_private_key'],'--public-key',c['manifest_public_key'],'--output',signed)
    verified=work/'bootstrap-verified.json'
    runner.tool('verify-envelope','--envelope',signed,'--classpath',c['org_json_classpath'],'--output',verified)
    proof=json.loads(verified.read_text())
    if proof.get('signatureVerified') is not True or proof.get('pinSpkiSha256')!=PIN or proof.get('apk')!=data['apk'] or proof.get('versionCode')!=data['versionCode']:
        raise ValueError('Production App verifier rejected bootstrap identity')
    if core.source_state(runner)!=head or core.digest(apk)!=metadata['sha256']:raise ValueError('Inputs changed')
    return {'status':'first_release_prepared_only','published':False,'deviceVerified':False,
            'deltaBuilt':False,'versionFloor':floor,'versionFloorSource':'operator_configured_not_online_verified','manifest':str(signed),'apk':str(apk),'payload':data,'sourceCommit':head}
