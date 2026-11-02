"""Fixed native project archive codec. Bytes/provenance only, never task or grant authority.

Uses the original Android JSONStringer manifest identity and package policy. The POSIX
assignment preparation/worker protocol is deliberately NOT used for Windows imports.
"""
import hashlib
import io
import json
import re
import stat
import struct
import uuid
import zipfile

from assignment_projects import manifest_identity, manifest_bytes, path as package_path, PRIVATE
from platform_backend import PlatformError
from platform_windows import relative_parts

MAX_ARCHIVE = 20 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024
MAX_FILE = 2 * 1024 * 1024
MAX_MANIFEST = 1024 * 1024
MAX_FILES = 2000
GENERATED = {'build', 'dist', 'out', 'target', 'coverage'}
HEADER = {'format','mode','project','source','entryPath','previewRoot','directories','files','bytes','omitted',
          'includesHistory','includesToolEnvironment','atomicSourceFence','policy'}


def fail(message):
    raise PlatformError(message, 400)


def keys(value, expected):
    if not isinstance(value, dict) or set(value) != set(expected): fail('Project package has unknown/missing fields')


def integer(value, maximum):
    if type(value) is not int or not 0 <= value <= maximum: fail('Project package integer exceeds explicit bound')
    return value


def path(value, generated=True):
    package_path(value, PlatformError)
    if any(part.casefold() in PRIVATE or part.casefold().startswith(".pocket-") for part in value.split("/")):
        fail('Windows case-insensitive private directory alias refused')
    relative_parts(value)  # Windows aliases/streams/trailing-dot rules apply before any staging.
    if not generated and value.split('/')[0].casefold() in GENERATED: fail('Generated root was not selected')
    return value


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result: fail('Duplicate manifest JSON key')
        result[key] = value
    return result


def _preflight(raw):
    if not isinstance(raw, bytes) or not 22 <= len(raw) <= MAX_ARCHIVE: fail('Project archive size outside bound')
    end = raw[-22:]
    if end[:4] != b'PK\x05\x06': fail('Project ZIP must have exact uncommented directory tail')
    _, disk, central_disk, on_disk, count, size, offset, comment = struct.unpack('<4s4H2IH', end)
    if disk or central_disk or comment or count != on_disk or not 1 <= count <= MAX_FILES+1 or not 46*count <= size <= 2*1024*1024 or offset+size != len(raw)-22:
        fail('Project ZIP directory bound/multidisk/ZIP64 refused')
    at, total, offsets = offset, 0, set()
    for _ in range(count):
        if at+46 > offset+size or raw[at:at+4] != b'PK\x01\x02': fail('Incomplete ZIP central header')
        flags, method = struct.unpack_from('<HH',raw,at+8)
        compressed, expanded = struct.unpack_from('<II',raw,at+20)
        name, extra, note, entry_disk = struct.unpack_from('<4H',raw,at+28)
        local = struct.unpack_from('<I',raw,at+42)[0]
        total += expanded
        if flags & 1 or method not in (0,8) or not 1 <= name <= 1024 or extra > 4096 or note or entry_disk or local in offsets or local+30 > offset or expanded > MAX_FILE or total > MAX_TOTAL+MAX_MANIFEST or at+46+name+extra > offset+size:
            fail('ZIP entry count/type/expanded size or duplicate local header invalid')
        offsets.add(local)
        if raw[local:local+4] != b'PK\x03\x04': fail('Missing original ZIP local header')
        local_flags, local_method = struct.unpack_from('<HH',raw,local+6)
        local_name, local_extra = struct.unpack_from('<HH',raw,local+26)
        if local_flags & 1 or local_method != method or local_name != name or local_extra > 4096 or local+30+name+local_extra+compressed > offset or raw[local+30:local+30+name] != raw[at+46:at+46+name]:
            fail('ZIP local name/data differs from central directory')
        at += 46+name+extra
    if at != offset+size: fail('ZIP directory length differs')


def read(raw):
    _preflight(raw)
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = {}
        for entry in archive.infolist():
            mode = entry.external_attr >> 16
            if entry.is_dir() or entry.flag_bits & 1 or mode & 0o7000 or stat.S_IFMT(mode) not in (0,stat.S_IFREG) or entry.filename in entries:
                fail('Project ZIP link/special/directory/duplicate entry refused')
            if entry.filename != 'manifest.json':
                if not entry.filename.startswith('project/'): fail('Project ZIP has undeclared namespace')
                path(entry.filename[8:])
            entries[entry.filename] = entry
        item = entries.pop('manifest.json',None)
        if item is None or item.file_size > MAX_MANIFEST: fail('Original project manifest missing or oversized')
        manifest = json.loads(archive.read(item).decode('utf-8'), object_pairs_hook=_unique)
        keys(manifest,HEADER)
        if manifest['format'] != 'pocketworld.project-package.v1' or manifest['mode'] != 'source_project' or any(manifest[key] is not False for key in ('includesHistory','includesToolEnvironment','atomicSourceFence')):
            fail('Only source-project bytes without history/environment/authority are supported')
        keys(manifest['project'], {'id','name'}); keys(manifest['source'], {'projectId','checkoutId','sourceDigest'})
        source, project = manifest['source'], manifest['project']
        for key in ('projectId','checkoutId'):
            if not isinstance(source[key],str) or str(uuid.UUID(source[key])) != source[key]: fail('Project source identity is not canonical')
        if project['id'] != source['projectId'] or not isinstance(project['name'],str) or not project['name'].strip() or len(project['name']) > 80 or not re.fullmatch('[a-f0-9]{64}',source['sourceDigest']): fail('Project descriptive provenance invalid')
        keys(manifest['policy'], {'version','generatedRootsIncluded','privateFilesExcluded'})
        policy = manifest['policy']
        if type(policy['version']) is not int or policy['version'] != 1 or type(policy['generatedRootsIncluded']) is not bool or policy['privateFilesExcluded'] is not True: fail('Project selection policy invalid')
        if manifest['previewRoot'] != '' or not isinstance(manifest['entryPath'],str): fail('Source project cannot replace a preview root')
        declared, directories, omitted = manifest['files'], manifest['directories'], manifest['omitted']
        if not isinstance(declared,list) or not 1 <= len(declared) <= MAX_FILES or len(entries) != len(declared) or not isinstance(directories,list) or len(directories)>MAX_FILES or not isinstance(omitted,list) or len(omitted)>MAX_FILES: fail('Project file/directory count invalid')
        seen, files, total = {}, {}, 0
        for directory in directories:
            path(directory,policy['generatedRootsIncluded'])
            folded=directory.casefold()
            if folded in seen: fail('Case-insensitive directory collision')
            seen[folded]='directory'
        for row in declared:
            keys(row,{'path','bytes','sha256','kind','mimeType','executable'})
            name=path(row['path'],policy['generatedRootsIncluded']); folded=name.casefold()
            if folded in seen: fail('Case-insensitive project path collision')
            seen[folded]='file'
            length=integer(row['bytes'],MAX_FILE)
            if type(row['executable']) is not bool or not isinstance(row['sha256'],str) or not re.fullmatch('[a-f0-9]{64}',row['sha256']): fail('Project file identity invalid')
            entry=entries.pop('project/'+name,None)
            if entry is None or entry.file_size != length: fail('Project ZIP file size differs from manifest')
            data=archive.read(entry)  # ZipFile verifies CRC; declared expansion was bounded before allocation.
            if len(data)!=length or hashlib.sha256(data).hexdigest()!=row['sha256']: fail('Project file digest differs')
            if (row['kind'],row['mimeType'])!=kind_and_mime(name,data): fail('Project file type differs from original package codec')
            files[name]=data; total+=len(data)
        actual_directories={name for name,kind in seen.items() if kind=='directory'}
        for name in seen:
            pieces=name.split('/')
            for index in range(1,len(pieces)):
                ancestor='/'.join(pieces[:index])
                if seen.get(ancestor)=='file': fail('File is another entry ancestor')
                actual_directories.add(ancestor)
        if len(actual_directories)>MAX_FILES: fail('Implied project directories exceed explicit bound')
        for row in omitted:
            keys(row,{'path','reason'})
            # Omitted entries may name excluded credentials; only validate syntax, never import them.
            relative_parts(row['path'])
            if not isinstance(row['reason'],str) or len(row['reason'])>120: fail('Omission reason invalid')
        if entries or total != integer(manifest['bytes'],MAX_TOTAL): fail('Project package actual total differs')
        if manifest['entryPath'] and manifest['entryPath'] not in files: fail('Project entry is not a selected file')
        return manifest, files


def summary(manifest):
    return {'manifestDigest':manifest_identity(manifest),'project':manifest['project'],'source':manifest['source'],
            'fileCount':len(manifest['files']),'directoryCount':len(manifest['directories']),'bytes':manifest['bytes'],
            'restoresHistory':False,'restoresEnvironment':False,'restoresExecutionAuthority':False,'newCopyOnly':True}


def kind_and_mime(name, raw):
    try:
        text=raw.decode('utf-8');kind='binary' if '\0' in text else 'text'
    except UnicodeError: kind='binary'
    types={'html':'text/html','htm':'text/html','css':'text/css','js':'text/javascript','mjs':'text/javascript','cjs':'text/javascript',
           'json':'application/json','map':'application/json','svg':'image/svg+xml','png':'image/png','jpg':'image/jpeg','jpeg':'image/jpeg',
           'gif':'image/gif','webp':'image/webp','ico':'image/x-icon','woff':'font/woff','woff2':'font/woff2','ttf':'font/ttf','wasm':'application/wasm'}
    return kind,types.get(name.lower().rsplit('.',1)[-1], 'text/plain' if kind=='text' else 'application/octet-stream')


def write_modified(manifest, files):
    # Preserve descriptive lineage but bind sourceDigest to the actually frozen modified bytes.
    value=json.loads(json.dumps(manifest)); total=0
    for row in value['files']:
        raw=files[row['path']];total+=len(raw)
        row.update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
        row['kind'],row['mimeType']=kind_and_mime(row['path'],raw)
    value['bytes']=total
    value['source']['sourceDigest']=hashlib.sha256(manifest_bytes(value['files'])).hexdigest()
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED,allowZip64=False) as output:
        output.writestr('manifest.json',manifest_bytes(value))
        for name,raw in files.items(): output.writestr('project/'+name,raw)
    raw=stream.getvalue()
    read(raw)  # Same strict decoder as intake, not a parallel package dialect.
    return raw
