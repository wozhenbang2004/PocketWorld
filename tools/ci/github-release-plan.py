"""Offline, unsigned GitHub asset remapping AFTER production bundle validation.
Does not sign, upload, create a release, change the app, or claim acceptance.
Caller must run existing production preflight/prepare and then sign this exact
new payload, verify it with the App verifier, and verify all uploaded assets.
"""
import copy
import hashlib
import json
from pathlib import Path
import re

REPOSITORY='wozhenbang2004/PocketWorld'

def plan(bundle, tag):
    bundle=Path(bundle).resolve()
    if not re.fullmatch(r'v[0-9]+(?:\.[0-9]+)*(?:-[a-f0-9]{7,40})?',tag):
        raise ValueError('Use an immutable version tag')
    if (bundle/'release.json').exists():
        raise ValueError('Never rewrite an already signed bundle')
    payload=json.loads((bundle/'unsigned-payload.json').read_text())
    evidence=json.loads((bundle/'artifact-evidence.json').read_text())
    uploads=json.loads((bundle/'release-plan.json').read_text())['artifactUploads']
    target=evidence.get('target',{})
    if (evidence.get('kind')!='pocketworld-update-artifact-evidence' or
        evidence.get('sameSignerSet') is not True or evidence.get('strictlyNewerVersion') is not True or
        payload.get('versionCode')!=target.get('versionCode') or
        payload.get('packageName')!='dev.pocketengine' or
        payload.get('apk',{}).get('sha256')!=target.get('sha256')):
        raise ValueError('Require existing production preflight evidence')
    result=copy.deepcopy(payload)
    assets=[]
    by_identity={}
    for row in uploads:
        path=(bundle/row['file']).resolve()
        if not path.is_relative_to(bundle) or not path.is_file():
            raise ValueError('Artifact escapes bundle or is missing')
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        if digest!=row['sha256'] or path.stat().st_size!=row['bytes']:
            raise ValueError('Artifact bytes changed')
        name=digest+'-'+path.name
        if not re.fullmatch(r'[a-zA-Z0-9._-]+',name): raise ValueError('Unsafe asset name')
        key=(row['sha256'],row['bytes'])
        if key in by_identity: raise ValueError('Duplicate artifact identity')
        url=f'https://github.com/{REPOSITORY}/releases/download/{tag}/{name}'
        by_identity[key]=url
        assets.append({'file':str(path),'name':name,'url':url,'bytes':row['bytes'],'sha256':digest})
    artifacts=[result['apk'],*result.get('deltas',[])]
    if len(artifacts)!=len(assets): raise ValueError('Artifact set mismatch')
    for item in artifacts:
        key=(item['sha256'],item['bytes'])
        if key not in by_identity: raise ValueError('Manifest artifact missing')
        item['url']=by_identity.pop(key)
    if by_identity: raise ValueError('Unreferenced artifact')
    if result.get('deltas'):
        delta=evidence.get('delta',{})
        if delta.get('reconstructedByteExact') is not True: raise ValueError('Delta lacks production reconstruction evidence')
        if len(result['deltas'])!=1 or result['deltas'][0]['sha256']!=delta.get('sha256'):
            raise ValueError('Delta evidence mismatch')
    return {'payload':result,'assets':assets,'tag':tag,'repository':REPOSITORY,
            'manifestUrl':f'https://github.com/{REPOSITORY}/releases/latest/download/release.json',
            'signed':False,'published':False,'installedClientCompatible':False,
            'required':['controlled HTTPS redirect support in bootstrap APK',
                        'same APK signer and newer version than every distributed build',
                        'sign exact remapped payload and verify existing App signature pin',
                        'draft release: upload all assets, verify bytes/ranges, then publish',
                        'real device bootstrap and update acceptance']}
