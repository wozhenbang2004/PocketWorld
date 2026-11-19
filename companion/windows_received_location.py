"""Original on-disk file, separate from both its history and its private retained bytes.

Only the receiver owner's explicit local choice can select it in Explorer. No
opening files, writing, replay, new grants or remote path/byte endpoint.
"""
from contextlib import ExitStack
import hashlib
import json
import ntpath
import time

import device_protocol as protocol
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
from platform_windows import local_root, relative_parts
from windows_file_revisions import MAX_BYTES, _hash, _identity, _origin
from windows_received_file import PROFILE, project
from windows_received_job import FILE
from windows_source_inventory import show_selected_child


# Unlike a private change.patch, a phone can send a shortcut, script, shell
# namespace document or executable. Do not give such names (or dot-files) to
# the Explorer parser. Other formats remain available in the inert byte viewer.
DOCUMENTS=frozenset(".txt .md .log .csv .tsv .json .yaml .yml .toml .xml .diff .patch .pdf .png .jpg .jpeg .gif .bmp .webp .zip .docx .xlsx .pptx .odt .ods".split())


def _document(name):
    return not name.startswith(".") and ntpath.splitext(name)[1].casefold() in DOCUMENTS


def _roots(files,env,stack):
    if env.get("kind","local")!="local": raise PlatformError("No local original workspace")
    backend=files.backend; sid=backend.logon_context()["userSid"]
    _,state,_=stack.enter_context(backend.directory(files.versions.root))
    backend.api.private_access(state,sid,require_protected=False)
    root,folder,_=stack.enter_context(backend.directory(env["root"]))
    return root,(sid,_identity(backend.api,state),_identity(backend.api,folder))


def _proof(roots,path,op,fp,sha,size,parent,leaf):
    if (not _origin(parent) or not _origin(leaf) or not _hash(fp) or not _hash(sha)
            or type(size) is not int or not 0<=size<=MAX_BYTES): raise PlatformError("Original file location not bound")
    return hashlib.sha256(protocol.canonical_bytes(["windows-received-live-location-v1",list(roots),path,op,fp,sha,size,parent,leaf])).hexdigest()


def bind(files,env,path,op,fp,version,size,parent,leaf,fact):
    # Called only by the already admitted Windows writer after verified readback.
    # Record a commitment privately; never send Windows file/birth IDs or a locator to the phone.
    if (not version.startswith("sha256:") or fact.get("state")!="present" or fact.get("bytes")!=size
            or version[7:]!=fact.get("sha256") or fact.get("fileIdentity")!=leaf[:3]): return
    relative_parts(path)
    with ExitStack() as stack:
        _,roots=_roots(files,env,stack)
        seal=_proof(roots,path,op,fp,version[7:],size,parent,leaf)
        files.versions.bind_direct_witness("landing",{"seal":seal},op,fp)


def access(manager,op,row,original,grant,now,revision,ticket,read):
    # local_record already checked the direct original job, source visibility,
    # exact peer, audience, still-current authorization epoch and operation.get.
    try:
        source,accepted=original
        if (source["toolName"]!=FILE or type(revision) is not int or revision<=0 or now>=grant["expiresAt"]
                or "tool.invoke" not in grant["capabilities"]
                or not {"workspace.list","workspace.location","workspace.export"}.issubset(grant["toolNames"])): return None
        w=json.loads(row[3])["windowsIncomingFile"]; landing=w["landing"]; seal=landing["seal"]; fp=w["fileOperationFingerprint"]
        if set(landing)!={"seal"} or w["format"]!=PROFILE or not _hash(seal) or not _hash(fp): return None
        eid=accepted["resourceBinding"]["environmentId"]
        from device_companion import workspace_binding
        if eid not in manager.environments or not protocol.resource_equal(accepted["resourceBinding"],workspace_binding(manager,eid)): return None
        fixed=(op,row[0],row[1],revision,fp,seal)
        if ticket is not None and ticket!=fixed: return None
        if not _document(relative_parts(manager.jobs[row[1]]["nativeInput"]["path"])[-1]): return None
        if not read: return fixed
        if ticket!=fixed: return None
        e=project(manager,op,row,original)
        if e["phase"] not in ("delivered","write_verified_only") or e["revision"]!=revision: return None
        args=manager.jobs[row[1]]["nativeInput"]; path=args["path"]
        if (hashlib.sha256(protocol.canonical_bytes(args)).hexdigest()!=e["inputDigest"]
                or hashlib.sha256(path.encode()).hexdigest()!=e["pathDigest"] or e["version"]!="sha256:"+e["sha256"]): return None
        return (manager._environment(eid),path,op,fp,e["sha256"],e["bytes"],seal)
    except (ValueError,TypeError,KeyError,AttributeError,OSError,RecursionError,PlatformError): return None


def _separate(manager,parent):
    # A containing folder is more than a selected leaf. Do not open a drive
    # root or a private manager/password/identity tree or its containing folder.
    parent=ntpath.normcase(local_root(parent))
    if parent==ntpath.splitdrive(parent)[0]+"\\": return False
    private=[str(manager.state)]
    token=manager.config.get("tokenFile")
    if token: private.append(ntpath.dirname(token))
    devices=manager.config.get("devices") or {}
    if devices.get("identityDir"): private.append(devices["identityDir"])
    for name in private:
        protected=ntpath.normcase(local_root(name))
        if ntpath.splitdrive(protected)[0]!=ntpath.splitdrive(parent)[0]: continue
        if ntpath.commonpath([parent,protected]) in (parent,protected): return False
    return True


def select(manager,material,active):
    env,path,op,fp,expected,size,seal=material
    parts=relative_parts(path); files=manager.files; backend=files.backend
    with ExitStack() as stack:
        root,roots=_roots(files,env,stack)
        parent,pdir,_=stack.enter_context(backend.directory(root,parts[:-1]))
        if not _document(parts[-1]): return "type"
        if not _separate(manager,parent): return "private"
        # Open for metadata first. Recheck the EXACT original physical identity,
        # including creation times, BEFORE reading any body. Never read a substitute.
        if not active(): return "unavailable"
        r=stack.enter_context(backend.open_reader(root,path,maximum=MAX_BYTES))
        actual=_proof(roots,path,op,fp,expected,size,_identity(backend.api,pdir),_identity(backend.api,r.handle))
        if actual!=seal: return "unavailable"
        if r.size!=size: return "changed"
        if not active(): return "unavailable"
        r.seek(0); data=r.read(size)
        if not r.verify_unchanged() or len(data)!=size or hashlib.sha256(data).hexdigest()!=expected: return "changed"
        # Win32 resolves parent + child PIDLs with these read-only handles still
        # pinned. Explorer only selects; it never ShellExecutes the document.
        show_selected_child(backend.api,stack,parent,ntpath.join(parent,parts[-1]),active)
        return "selected"


def open_selected(view,peer,item,receipt,ticket):
    from desktop_received_commands import local_record
    until=time.monotonic()+60
    def use(**kwargs): return local_record(view,peer,item,revision=receipt["revision"],locate=True,ticket=ticket,**kwargs)
    def active():
        try: return (time.monotonic()<until and view.active() and use()==ticket
                     and WindowsDesktopHealth().probe(session_only=True).get("available") is True)
        except Exception: return False
    if not active(): return
    try:
        material=use(read=True)
        status=select(view.manager,material,active) if material is not None and active() else "unavailable"
    except (OSError,ValueError,AttributeError,TypeError,PlatformError): status="unavailable"
    if status!="selected" and active():
        reason={"type":"此类型不交给资源管理器解析，尤其是快捷方式、脚本、可执行文件和未知类型；若原留存仍可用，可用本机只读阅读器。",
                "private":"这个位置同时是驱动器根、伴侣私有数据目录或包含它们；不能在此展开文件夹。",
                "changed":"仍能对应原文件对象，但它当前的长度或字节不再符合原交付；没有展示改动正文。",
                "unavailable":"可能已移动、替换、被占用，或系统未能核对原父目录和这一份文件；没有改开上级目录。"}[status]
        view._dialog("没有在资源管理器中定位文件",reason+" 原落盘回执不变；这不是再次发送或覆盖的理由。",[],active)
