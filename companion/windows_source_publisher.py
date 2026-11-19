"""Native publisher ONLY for an explicitly reviewed registered local Git source.

Does not accept a device envelope/package, arbitrary destination or worker. Holds
original Windows objects; uses a same-volume absent-only final directory rename.
"""
from contextlib import ExitStack
import ctypes
import hashlib
import ntpath
import os
import re

from device_project_packages import path as project_path
from platform_backend import PlatformError
from platform_windows import WindowsHandleReader, relative_parts
from windows_git_contents import MAX_FILES, MAX_FILE, MAX_TOTAL
from windows_git_tree import MAX_DIRS


def _bad():
    raise PlatformError("Original local source copy boundary cannot be verified")


def _origin(api,info):
    # File indexes can be reused after deletion. A later original-object
    # review also binds the creation timestamp; no zero/unknown index proof.
    identity = api.identity(info)
    birth = [info.created.dwHighDateTime,info.created.dwLowDateTime]
    if not any(identity[1:]) or not any(birth): _bad()
    return [*identity,*birth]


def _ext(path):
    return "\\\\?\\"+path  # caller obtained this absolute fixed-drive path from a checked ancestor


def disk(ctx, facts):
    with ctx.backend.directory(ctx.state) as (path,handle,_identity):
        api = ctx.backend.api
        api.private_access(handle,ctx.sid,require_protected=True)
        sectors,sector_bytes,unused,clusters = (api.w.DWORD() for _ in range(4))
        word = ctypes.POINTER(api.w.DWORD)
        unit_query = api.k.GetDiskFreeSpaceW
        unit_query.argtypes,unit_query.restype = [api.w.LPCWSTR,word,word,word,word],api.w.BOOL
        api.checked(unit_query(path[:3],*[ctypes.byref(i) for i in (sectors,sector_bytes,unused,clusters)]))
        unit = sectors.value*sector_bytes.value
        if unit<512 or unit>32*1024*1024 or unit&(unit-1): _bad()
        quota = ctypes.c_uint64()
        wide = ctypes.POINTER(ctypes.c_uint64)
        query = api.k.GetDiskFreeSpaceExW
        query.argtypes,query.restype = [api.w.LPCWSTR,wide,wide,wide],api.w.BOOL
        api.checked(query(_ext(path)+"\\",ctypes.byref(quota),None,None))
        # Conservative allocation rounding, directories/receipts and headroom.
        # This is an observation for the current caller, never a reservation.
        need = facts["bytes"]+(facts["files"]+facts["directories"]+8)*unit+14*1024*1024  # includes saved inventory and headroom
        return {"available":quota.value,"estimate":need,"enough":quota.value>=need}


class DirectoryTree:
    def __init__(self,ctx,base,stack,root_id,*,create_root=False):
        self.api, self.sid, self.stack = ctx.backend.api,ctx.sid,stack
        self.base,self.nodes,self.origins = base,{},{}
        if create_root: self.api.create_private_directory(_ext(base),self.sid)
        self.bind_dir("",root_id)

    def bind_dir(self,path,expected,*,create=False):
        if path:
            parts = relative_parts(path)
            parent = "/".join(parts[:-1])
            if parent not in self.nodes: _bad()
        physical = ntpath.join(self.base,*path.split("/")) if path else self.base
        if create: self.api.create_private_directory(_ext(physical),self.sid)
        handle = self.api.open(_ext(physical),directory=True)
        self.stack.callback(self.api.k.CloseHandle,handle)
        info,actual = self.api.info(handle),self.api.final(handle)
        identity,origin = self.api.identity(info),_origin(self.api,info)
        if (not info.attributes&0x10 or info.attributes&0x400 or actual.casefold()!=_ext(physical).casefold()
                or ntpath.basename(actual)!=ntpath.basename(physical) or expected is not None and expected not in (identity,origin)): _bad()
        self.api.private_access(handle,self.sid,require_protected=True)
        self.nodes[path] = (physical,handle,identity)
        self.origins[path] = origin
        return origin

    def open_file(self,path,create=False):
        parts = relative_parts(path)
        parent,_,_ = self.nodes["/".join(parts[:-1])]
        physical = ntpath.join(parent,parts[-1])
        handle = self.api.open(_ext(physical),create=create,write=create)
        try:
            info,actual = self.api.info(handle),self.api.final(handle)
            if (info.attributes&(0x10|0x400) or info.links!=1 or actual.casefold()!=_ext(physical).casefold()
                    or ntpath.basename(actual)!=parts[-1]): _bad()
            self.api.private_access(handle,self.sid)
            return handle,_origin(self.api,info)
        except BaseException:
            self.api.k.CloseHandle(handle); raise

    def put(self,path,raw):
        handle,identity = self.open_file(path,True)  # CREATE_NEW, original private staging tree only
        try: self.api.write(handle,raw)
        finally: self.api.k.CloseHandle(handle)
        return identity

    def verify(self,children,rows,identities,current,notice):
        stamps = {}
        def stamp(handle):
            info = self.api.info(handle)
            return info.written.dwHighDateTime,info.written.dwLowDateTime
        for name,(physical,handle,_) in self.nodes.items():
            current()
            stamps[name] = stamp(handle)
            expected, found = children[name],set()
            with os.scandir(_ext(physical)) as listing:
                for entry in listing:
                    if entry.name not in expected or entry.name in found: _bad()
                    found.add(entry.name)
            if found!=expected: _bad()
        for n,(name,row) in enumerate(rows.items(),1):
            current()
            handle,identity = self.open_file(name)
            try:
                reader = WindowsHandleReader(self.api,handle,MAX_FILE)
                if identity!=identities[name] or reader.size!=row["bytes"]: _bad()
                reader.seek(0)
                sha = hashlib.sha256()
                while reader.position<reader.size: sha.update(reader.read(min(262144, reader.size-reader.position)))
                if sha.hexdigest()!=row["sha256"] or not reader.verify_unchanged(): _bad()
            finally: self.api.k.CloseHandle(handle)
            if not n%32 or n==len(rows): notice(n,len(rows))
        for name,(_,handle,_) in self.nodes.items():
            current()
            if stamps[name]!=stamp(handle): _bad()


def _plan(manifest):
    source = manifest["source"]
    declared,files = manifest["directories"],manifest["files"]
    if (not isinstance(declared,list) or not isinstance(files,list) or len(declared)>=MAX_DIRS
            or len(declared)!=source["directories"] or not 1<=len(files)<=MAX_FILES or len(files)!=source["files"]): _bad()
    dirs,rows,children,total = set(),{},{"":set()},0
    for path in declared:
        project_path(path,generated=False)
        if path in dirs: _bad()
        dirs.add(path); children[path]=set()
    for row in files:
        path = project_path(row["path"],generated=False)
        if (path in rows or path in dirs or type(row["bytes"]) is not int or not 0<=row["bytes"]<=MAX_FILE
                or row["mode"] not in ("100644","100755") or not re.fullmatch(r"[a-f0-9]{64}",row["sha256"])
                or not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}",row["object"])): _bad()
        rows[path]=row; total+=row["bytes"]
    if total!=source["bytes"] or total>MAX_TOTAL: _bad()
    for path in dirs|set(rows):
        parent,_,name = path.rpartition("/")
        if parent not in children or name in children[parent]: _bad()
        children[parent].add(name)
    return sorted(dirs,key=lambda p:(p.count("/"),p)),rows,children


def publish(ctx,op,digest,manifest,feed,current,record,phase):
    # Local source staging has a different operation and scope from device
    # package import. Recheck the exact identity before any destination write.
    from assignment_projects import manifest_identity
    from windows_git_copy import PROFILE, _key
    if (not re.fullmatch(r"[0-9a-f]{32}",op) or manifest.get("format")!=PROFILE
            or _key(ctx.install_state["installId"],manifest["source"])!=op or manifest_identity(manifest)!=digest): _bad()
    target,stage = "source-"+op,".pocketworld-project-"+op
    dirs,rows,children = _plan(manifest)
    backend,api = ctx.backend,ctx.backend.api
    attempted = False
    with backend.directory(ctx.state) as (parent,parent_handle,parent_id):
        api.private_access(parent_handle,ctx.sid,require_protected=True)
        before,after = ntpath.join(parent,stage),ntpath.join(parent,target)
        info = {"target":target,"temporary":stage,"manifestDigest":digest,"parentIdentity":parent_id}
        dir_ids,files = {},{}
        try:
            current()
            with ExitStack() as stack:
                tree = DirectoryTree(ctx,before,stack,None,create_root=True)
                root_id = tree.nodes[""][2]
                root_origin = tree.origins[""]
                info["temporaryIdentity"] = root_id
                record("opened",info)
                for name in dirs:
                    current()
                    dir_ids[name] = tree.bind_dir(name,None,create=True)
                def append(path,mode,oid,raw,sha):
                    current()
                    row = rows.get(path)
                    if (row is None or path in files or row["mode"]!=mode or row["object"]!=oid
                            or row["bytes"]!=len(raw) or row["sha256"]!=sha): _bad()
                    files[path]=tree.put(path,raw)
                feed(append)  # rebind original repository/commit/tree; per-blob OID + SHA before each private write
                if files.keys()!=rows.keys(): _bad()
                phase("正在逐文件核对暂存目录的原身份和实际字节")
                tree.verify(children,rows,files,current,lambda n,t:phase(f"已回读暂存原件：{n}/{t} 个文件"))
                record("stage",info)
            # Close all child handles, then reacquire the EXACT stage with DELETE
            # for this one absent-only rename. Don't trust its pathname alone.
            current()
            handle = api.open(_ext(before),directory=True,delete=True)
            try:
                checked = api.info(handle)
                if (not checked.attributes&0x10 or checked.attributes&0x400 or _origin(api,checked)!=root_origin
                        or api.final(handle).casefold()!=_ext(before).casefold()): _bad()
                api.private_access(handle,ctx.sid,require_protected=True)
                current(True)
                attempted=True
                api.publish_handle(handle,parent_handle,target)
                if _origin(api,api.info(handle))!=root_origin or api.final(handle).casefold()!=_ext(after).casefold(): _bad()
            finally: api.k.CloseHandle(handle)
            phase("原目录已发布，正在核对目标中的原文件身份和字节")
            with ExitStack() as stack:
                landed = DirectoryTree(ctx,after,stack,root_origin)
                for name in dirs:
                    current()
                    landed.bind_dir(name,dir_ids[name])
                landed.verify(children,rows,files,current,lambda n,t:phase(f"已回读最终目标：{n}/{t} 个文件"))
            # Persist the exact output-file and directory identities after
            # readback. Older or absent inventories are never reconstructed
            # from a newer checkout or guessed from the current output names.
            items = record("items",{**info,"rootOrigin":root_origin,"directories":[dir_ids[p] for p in manifest["directories"]],
                                    "files":[files[r["path"]] for r in manifest["files"]]})
            return {"status":"completed","verified":True,"delivered":True,"path":target,
                    "manifestDigest":digest,"directoryIdentity":root_id,"publicationAttempted":True,"items":items}
        except BaseException:
            # No automatic delete, corrective overwrite, second name or replay.
            return {"status":"unknown" if attempted else "failed","verified":False,"delivered":False,
                    "publicationAttempted":attempted,"cleanupPending":True}
