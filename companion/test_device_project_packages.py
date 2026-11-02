"""Bounded original package codec + production Windows directory orchestration API models.

The in-memory/POSIX Win32 API model is NOT evidence of a Windows kernel run.
"""
from contextlib import contextmanager
import hashlib
import io
import json
import ntpath
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid
import zipfile

import device_project_packages as packages
from platform_windows import WindowsBackend
from platform_backend import PlatformError


def package(raw=b'original source', name='src/main.txt'):
    project, checkout=str(uuid.uuid4()),str(uuid.uuid4())
    directories=['src','empty'] if name.startswith('src/') else ['empty']
    manifest=dict(format='pocketworld.project-package.v1',mode='source_project',project={'id':project,'name':'Controlled project'},
                  source={'projectId':project,'checkoutId':checkout,'sourceDigest':hashlib.sha256(raw).hexdigest()},entryPath='',previewRoot='',
                  directories=directories,files=[dict(path=name,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),kind='text',mimeType='text/plain',executable=False)],
                  bytes=len(raw),omitted=[],includesHistory=False,includesToolEnvironment=False,atomicSourceFence=False,
                  policy={'version':1,'generatedRootsIncluded':False,'privateFilesExcluded':True})
    return archive(manifest,{name:raw}),manifest,{name:raw}


def archive(manifest, files):
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as output:
        output.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False))
        for name,raw in files.items(): output.writestr('project/'+name,raw)
    return stream.getvalue()


class PackageCodecTests(unittest.TestCase):
    def test_original_archive_digest_and_binary_bounds(self):
        raw,manifest,files=package('受控源码'.encode())
        actual,body=packages.read(raw)
        self.assertEqual(manifest,actual);self.assertEqual(files,body)
        self.assertEqual(packages.manifest_identity(manifest),packages.summary(actual)['manifestDigest'])
        self.assertFalse(packages.summary(actual)['restoresExecutionAuthority'])

    def test_traversal_private_case_aliases_duplicate_and_malformed_archive_refused(self):
        for name in ('../escape','.env','CON.txt','src/trailing.','src/file:stream','.GIT/config','.SSH/config'):
            raw,_,_=package(name=name)
            with self.assertRaises(Exception): packages.read(raw)
        raw,manifest,files=package()
        manifest['directories'].append('SRC')
        with self.assertRaises(Exception): packages.read(archive(manifest,files))
        for altered in (raw+b'trailing',raw[:-10],b'PK'+b'x'*100):
            with self.assertRaises(Exception): packages.read(altered)
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as output:
            output.writestr('manifest.json','{}')
            output.writestr('project/a','a');output.writestr('project/a','a')
        with self.assertRaises(Exception): packages.read(stream.getvalue())

    def test_oversize_expansion_wrong_hash_and_authority_metadata_refused(self):
        raw,manifest,files=package()
        manifest['files'][0]['sha256']='0'*64
        with self.assertRaises(Exception): packages.read(archive(manifest,files))
        raw,manifest,files=package(b'x'*(packages.MAX_FILE+1))
        with self.assertRaises(Exception): packages.read(raw)
        raw,manifest,files=package();manifest['grants']=['forged']
        with self.assertRaises(Exception): packages.read(archive(manifest,files))


class TreeApiModel:
    """Controlled file objects modeling original handle identity/absent-only publication."""
    def __init__(self, physical):
        self.physical=Path(physical); self.handles={}; self.serial=0; self.publications=[]; self.fail_after_publish=False
        self.k=SimpleNamespace(CloseHandle=lambda handle:self.handles.pop(handle,None))
    def local(self, value):
        if not str(value).startswith('C:\\fixture'): raise PlatformError('Fixture root mismatch')
        rest=str(value)[len('C:\\fixture'):].strip('\\')
        return self.physical.joinpath(*rest.split('\\')) if rest else self.physical
    def create_private_directory(self,value,sid): self.local(value).mkdir(mode=0o700)
    def open(self,value,**options):
        path=self.local(value)
        if not path.exists(): raise FileNotFoundError(value)
        self.serial+=1;self.handles[self.serial]=value;return self.serial
    def info(self,handle):
        path=self.local(self.handles[handle]);info=path.stat()
        return SimpleNamespace(attributes=0x10 if path.is_dir() else 0,stamp=[info.st_dev,0,info.st_ino])
    def identity(self,info): return info.stamp
    def final(self,handle): return '\\\\?\\'+self.handles[handle]
    def private_access(self,*args,**kwargs): return {'fixture':'not_windows_dacl_proof'}
    def disposition(self,handle):
        path=self.local(self.handles[handle]);path.rmdir() if path.is_dir() else path.unlink()
    def publish_handle(self,handle,parent,name):
        source=self.handles[handle];target=ntpath.join(self.handles[parent],name)
        if self.local(target).exists(): raise FileExistsError(target)
        self.local(source).rename(self.local(target));self.handles[handle]=target
        self.publications.append((source,target))
        if self.fail_after_publish: raise OSError('Fixture ACK lost after actual rename')


class TreeBackendModel(WindowsBackend):
    def logon_context(self): return {'userSid':'fixture-not-real-windows'}
    @contextmanager
    def directory(self,root,parts=()):
        physical=ntpath.join(root,*parts) if parts else root
        handle=self.api.open(physical)
        try: yield physical,handle,self.api.identity(self.api.info(handle))
        finally: self.api.k.CloseHandle(handle)
    def read_bytes(self,root,name,maximum=262144):
        value=self.api.local(ntpath.join(root,*name.split('/'))).read_bytes()
        if len(value)>maximum: raise PlatformError('Fixture maximum')
        return value
    def create_private_snapshot(self,root,name,raw,maximum,*,current):
        if len(raw)>maximum: raise PlatformError('Fixture maximum')
        current()
        with self.api.local(ntpath.join(root,*name.split('/'))).open('xb') as stream: stream.write(raw)
    def delete_private_snapshot(self,root,name,digest,size,current=lambda:None):
        target=self.api.local(ntpath.join(root,*name.split('/')));raw=target.read_bytes();current()
        if len(raw)!=size or hashlib.sha256(raw).hexdigest()!=digest: raise PlatformError('Fixture changed object')
        target.unlink();return {'deleted':True,'verified':True}


class WindowsProjectCopyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.api=TreeApiModel(self.root);self.backend=TreeBackendModel(api=self.api)
        self.listdir=os.listdir
        self.patch=patch('platform_windows.os.listdir',side_effect=lambda value:self.listdir(self.api.local(value)) if str(value).startswith('C:\\fixture') else self.listdir(value))
        self.patch.start()
    def tearDown(self): self.patch.stop();self.temp.cleanup()
    def test_original_directory_handle_new_copy_with_empty_directory_and_readback(self):
        _,manifest,files=package();intents=[]
        result=self.backend.import_project_copy('C:\\fixture','reviewed-copy',manifest,files,lambda:None,record_intent=intents.append)
        self.assertTrue(result['verified'],result);self.assertTrue((self.root/'reviewed-copy'/'empty').is_dir())
        self.assertEqual(files['src/main.txt'],(self.root/'reviewed-copy'/'src'/'main.txt').read_bytes())
        self.assertEqual('dispatch_intent_persisted',intents[0]['phase']);self.assertEqual(1,len(self.api.publications))
        self.assertFalse(result['executionStarted'])
    def test_existing_destination_never_overwritten_and_unknown_rename_not_replayed(self):
        _,manifest,files=package();(self.root/'reviewed-copy').mkdir();(self.root/'reviewed-copy'/'keep').write_text('owner')
        result=self.backend.import_project_copy('C:\\fixture','reviewed-copy',manifest,files,lambda:None,record_intent=lambda value:None)
        self.assertFalse(result['verified']);self.assertEqual('owner',(self.root/'reviewed-copy'/'keep').read_text());self.assertEqual([],self.api.publications)
        self.api.fail_after_publish=True
        result=self.backend.import_project_copy('C:\\fixture','new-copy',manifest,files,lambda:None,record_intent=lambda value:None)
        self.assertEqual('unknown',result['status']);self.assertEqual(1,len(self.api.publications));self.assertTrue((self.root/'new-copy').exists())
    def test_revoked_at_intent_does_not_publish_and_cleans_only_owned_staging(self):
        _,manifest,files=package();allow=[True]
        def current():
            if not allow[0]: raise PermissionError('fixture revoked')
        def intent(value): allow[0]=False
        result=self.backend.import_project_copy('C:\\fixture','reviewed-copy',manifest,files,current,record_intent=intent)
        self.assertFalse(result['publicationAttempted']);self.assertFalse((self.root/'reviewed-copy').exists())
        self.assertFalse(result['cleanupPending'],result);self.assertEqual([],list(self.root.iterdir()))
