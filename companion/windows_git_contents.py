"""Ephemeral content evidence from this machine's already-reviewed commit.

No worktree reads, filters, LFS hydration, credentials, copying or remote I/O.
"""
import hashlib

PROFILE = "pocketworld.windows-committed-file-content.v1"
MAX_FILES, MAX_FILE, MAX_TOTAL = 8192, 16*1024*1024, 128*1024*1024
LFS_HEADERS = (b"version https://git-lfs.github.com/spec/v1", b"version https://hawser.github.com/spec/v1")


class ContentProblem(Exception):
    def __init__(self, reason): self.reason = reason


def measure(rows, mapping, width, read_blob, checkpoint, on_count):
    if len(rows)>MAX_FILES: raise ContentProblem("content_limit")
    digest = hashlib.sha256(PROFILE.encode()+b"\0"+bytes.fromhex(mapping))
    metadata, total, pointer_like = {}, 0, 0
    on_count(0,len(rows),0)
    for n,(mode,path,oid) in enumerate(rows,1):
        checkpoint()
        if oid not in metadata:
            # No bytes escape this iteration. This is an observation of exact
            # canonical Git bytes, not a persistent source lease for a worker.
            raw = read_blob(oid, MAX_FILE)
            length = len(raw)
            if length>MAX_FILE or length>MAX_TOTAL-total: raise ContentProblem("content_limit")
            first = raw[:64].partition(b"\n")[0].removesuffix(b"\r")
            metadata[oid] = length,hashlib.sha256(raw).digest(),first in LFS_HEADERS
            del raw
        length,sha,looks_pointer = metadata[oid]
        total += length
        if total>MAX_TOTAL: raise ContentProblem("content_limit")
        pointer_like += looks_pointer
        name, obj = path.encode("utf-8"), bytes.fromhex(oid)
        if len(obj)!=width: raise ContentProblem("invalid_object")
        digest.update(mode+b"\0"+len(name).to_bytes(4,"big")+name+obj+length.to_bytes(8,"big")+sha)
        if not n%16 or n==len(rows): on_count(n,len(rows),total)
    return {"profile":PROFILE,"complete":True,"files":len(rows),"logicalBytes":total,
            "uniqueBlobObjects":len(metadata),"lfsHeaderFiles":pointer_like,
            "contentManifestDigest":digest.hexdigest(),"scope":"raw_committed_git_blobs",
            "filtersApplied":False,"lfsHydrated":False,"copied":False,"workingTreeChecked":False}
