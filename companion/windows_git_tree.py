"""Review committed directory records only; no blob, worktree or destination I/O."""
import ctypes
from functools import cmp_to_key
import hashlib
import re
import unicodedata

from platform_backend import PlatformError
from platform_windows import relative_parts

PROFILE = "pocketworld.windows-committed-name-review.v1"
MAX_DIRS, MAX_ROWS, MAX_TREE_BYTES = 1024, 12000, 8*1024*1024
FILE, EXEC, DIR, LINK, SUB = b"100644", b"100755", b"40000", b"120000", b"160000"


class TreeIssue(Exception):
    def __init__(self, reason, example=None): self.reason, self.example = reason, example


def entries(body, width):
    pos, previous, seen = 0, b"", set()
    while pos<len(body):
        space = body.find(b" ",pos,pos+8)
        nul = body.find(b"\0",pos,pos+4096)
        if space<pos or nul<space or nul+1+width>len(body) or len(seen)>=8192: raise TreeIssue("invalid_object")
        mode, name, oid = body[pos:space], body[space+1:nul], body[nul+1:nul+1+width]
        ordered = name+(b"/" if mode==DIR else b"\0")
        if (mode not in (FILE,EXEC,DIR,LINK,SUB) or name in (b"",b".",b"..") or b"/" in name
                or name in seen or ordered<=previous or not any(oid)): raise TreeIssue("invalid_object")
        seen.add(name)
        previous,pos = ordered,nul+1+width
        yield mode,name,oid.hex()


def _component(prefix, raw):
    # Import only the existing package's path gate; do not pick a transport,
    # load a package, create an operation or call the project/worker capability.
    from device_project_packages import path as package_path
    try: name = raw.decode("utf-8")
    except UnicodeError: raise TreeIssue("unmapped_name") from None
    path = prefix+name
    # Keep names exact and display-safe; reject unsafe mappings rather than
    # renaming. The current destination/volume hasn't been selected or checked.
    if (len(name.encode("utf-16-le"))>510 or any(unicodedata.category(c) in ("Cf","Cc","Cs") for c in name)
            or len(path.encode("utf-8"))>2048): raise TreeIssue("unmapped_name")
    alias = unicodedata.normalize("NFKC",name)
    for form in (name,alias) if name!=alias else (name,):
        stem = form.split(".",1)[0].rstrip(" ").upper()
        if (stem in {"CON","PRN","AUX","NUL","CONIN$","CONOUT$"} or re.fullmatch(r"(?:COM|LPT)[0-9]",stem)
                or re.search(r"~[0-9]+(?:\.|$)",form) or "/" in form): raise TreeIssue("unmapped_name")
        try: relative_parts(prefix+form)
        except PlatformError: raise TreeIssue("unmapped_name") from None
        try: package_path(prefix+form,generated=False)
        except PlatformError: raise TreeIssue("protected_or_unselected") from None  # no private name in report
    return path, name


def _native_compare(api,checkpoint):
    compare = api.k.CompareStringOrdinal
    compare.argtypes = [api.w.LPCWSTR,ctypes.c_int,api.w.LPCWSTR,ctypes.c_int,api.w.BOOL]
    compare.restype = ctypes.c_int
    calls = 0
    def by_name(left,right):
        nonlocal calls
        calls += 1
        if not calls%128: checkpoint()
        value = compare(left,-1,right,-1,True)
        if value not in (1,2,3): raise TreeIssue("name_comparison_unavailable")
        return value-2
    return by_name


def review(root_oid, root_bytes, width, read_tree, api, checkpoint, *, collect=None, directories=None):
    compare = _native_compare(api,checkpoint)
    directory_count, file_count, executable_count, rows = 0, 0, 0, 0
    metadata_bytes, cache = 0, {}
    digest = hashlib.sha256(PROFILE.encode()+b"\0"+bytes.fromhex(root_oid))

    def walk(oid, prefix, parents, original=None):
        nonlocal directory_count,file_count,executable_count,metadata_bytes,rows
        checkpoint()
        if oid in parents: raise TreeIssue("invalid_object")
        if len(parents)>39 or directory_count>=MAX_DIRS: raise TreeIssue("tree_limit")
        directory_count += 1
        if oid not in cache:
            body = original if original is not None else read_tree(oid)
            metadata_bytes += len(body)
            if metadata_bytes>MAX_TREE_BYTES: raise TreeIssue("tree_limit")
            cache[oid] = tuple(entries(body,width))
        nodes = cache[oid]
        rows += len(nodes)
        if rows>MAX_ROWS: raise TreeIssue("tree_limit")
        children, folded, names = [], set(), []
        # Resolve aliases for ALL siblings before looking inside any of them.
        for i,(mode, raw, child) in enumerate(nodes):
            if not i%32: checkpoint()
            path,name = _component(prefix,raw)
            folding = unicodedata.normalize("NFKC",name).casefold()
            if folding in folded: raise TreeIssue("name_collision",path)
            folded.add(folding); names.append(name)
            if mode==LINK: raise TreeIssue("symbolic_entry",path)
            if mode==SUB: raise TreeIssue("submodule_entry",path)
            children.append((mode,path,child))
        ordered = sorted(names,key=cmp_to_key(compare))
        if any(compare(a,b)==0 for a,b in zip(ordered,ordered[1:])): raise TreeIssue("name_collision")
        for i,(mode,path,child) in enumerate(children):
            if not i%32: checkpoint()
            encoded = path.encode("utf-8")
            digest.update(mode+b"\0"+len(encoded).to_bytes(4,"big")+encoded+bytes.fromhex(child))
            if mode==DIR:
                if directories is not None: directories.append(path)
                walk(child,path+"/",parents|{oid})
            else:
                file_count += 1
                executable_count += mode==EXEC
                if collect is not None: collect.append((mode,path,child))

    walk(root_oid,"",set(),root_bytes)
    return {"profile":PROFILE,"complete":True,"directories":directory_count-1,"files":file_count,
            "gitExecutableFiles":executable_count,"mappingDigest":digest.hexdigest(),
            "blobContentsVerified":False,"destinationVerified":False}
