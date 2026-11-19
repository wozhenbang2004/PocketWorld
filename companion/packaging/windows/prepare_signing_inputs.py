"""Prepare seven exact original build files for a trusted human signer. Never sign, use a key or publish.
For an already-built candidate on a protected local Windows builder. No executable from the ZIP is run.
"""
import argparse
from contextlib import contextmanager
import ctypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import zipfile

sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("pw_candidate_inputs",HERE/"build_candidate.py")
candidate=importlib.util.module_from_spec(spec); spec.loader.exec_module(candidate)
pack,rt=candidate.pack,candidate.rt
RUNTIME="app/PocketWorld-Companion.exe"
CUA="driver/cua-driver.exe"
AGENT="driver/agent-desktop.exe"
TARGETS=(RUNTIME,CUA,AGENT,*("tools/"+n for n in pack.MANAGEMENT_SCRIPTS))
LIMIT=512*1024**2


def must(ok,message):
    if not ok: raise ValueError(message)


def hash_ok(s): return isinstance(s,str) and bool(re.fullmatch("[0-9a-f]{64}",s))


def obj(raw):
    def unique(pairs):
        d={}
        for k,v in pairs:
            if k in d: raise ValueError("Duplicate metadata key")
            d[k]=v
        return d
    value=json.loads(raw.decode("utf-8"),object_pairs_hook=unique)
    must(isinstance(value,dict),"A bounded original metadata object is required"); return value


@contextmanager
def locked(path,maximum):
    # Pin the actual archive while hashing and taking its seven bounded members, not the name of a
    # file later reopened for extraction. Reject network/linked/recall-on-open inputs.
    from ctypes import wintypes as w
    import msvcrt
    p=candidate.local(path,directory=False); api=ctypes.WinDLL("kernel32",use_last_error=True)
    api.CreateFileW.argtypes=(w.LPCWSTR,w.DWORD,w.DWORD,ctypes.c_void_p,w.DWORD,w.DWORD,w.HANDLE); api.CreateFileW.restype=w.HANDLE
    api.CloseHandle.argtypes=(w.HANDLE,); api.CloseHandle.restype=w.BOOL
    api.GetFinalPathNameByHandleW.argtypes=(w.HANDLE,w.LPWSTR,w.DWORD,w.DWORD); api.GetFinalPathNameByHandleW.restype=w.DWORD
    h=api.CreateFileW(str(p),0x80000000,1,None,3,0x00200000,None)
    if h==ctypes.c_void_p(-1).value: raise ctypes.WinError(ctypes.get_last_error())
    try:
        name=ctypes.create_unicode_buffer(32768); n=api.GetFinalPathNameByHandleW(h,name,len(name),0)
        must(0<n<len(name) and os.path.normcase(name.value)==os.path.normcase("\\\\?\\"+str(p)),"Input path is no longer the reviewed file")
        fd=msvcrt.open_osfhandle(h,os.O_RDONLY|os.O_BINARY); h=None
        with os.fdopen(fd,"rb") as stream:
            info=os.fstat(stream.fileno())
            must(stat.S_ISREG(info.st_mode) and info.st_nlink==1 and 0<info.st_size<=maximum and not getattr(info,"st_file_attributes",0)&0x441400,
                 "Input is not one bounded ordinary, local original")
            yield stream,info.st_size
    finally:
        if h is not None: api.CloseHandle(h)


def archive_map(z):
    listing=z.infolist(); must(0<len(listing)<=20000,"Archive member count outside producer bound")
    index={}; folded=set(); total=0
    for f in listing:
        name=pack.safe_name(f.filename); key=name.casefold(); total+=f.file_size
        must(not f.is_dir() and key not in folded and not f.flag_bits&1 and not stat.S_ISLNK(f.external_attr>>16)
             and f.compress_type in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED) and 0<=f.file_size<=LIMIT and total<=2*1024**3,
             "Unexpected, aliased, encrypted or oversized archive entry")
        index[name]=f; folded.add(key)
    return index


def digest_entry(z,entry,expected_sha,sink=None):
    must(hash_ok(expected_sha),"Original SHA-256 missing")
    n=0; dig=hashlib.sha256()
    with z.open(entry) as f:
        for chunk in iter(lambda:f.read(262144),b""):
            n+=len(chunk); must(n<=entry.file_size,"Archive member expanded beyond declaration")
            dig.update(chunk)
            if sink is not None: sink.write(chunk)
    must(n==entry.file_size and dig.hexdigest()==expected_sha,"Actual archive bytes differ from admitted pre-sign bytes")


def prepare(args):
    rt.require_windows_x64()
    must(hash_ok(args.expected_zip_sha256) and bool(re.fullmatch("[0-9a-f]{40}",args.source_commit)),"External archive SHA-256 and exact product commit required")
    path=args.candidate
    must(path.name=="candidate.json","Select the original build-development candidate.json")
    with locked(path,16384) as (source,_): c=obj(source.read(16385))
    v=c.get("version"); must(isinstance(v,str) and bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}",v)),"Invalid original candidate version")
    original="PocketWorld-Companion-"+v+"-dev.zip"
    must(c.get("format")=="pocketworld.windows-local-development-candidate.v1" and c.get("zip")==original and c.get("sourceCommit")==args.source_commit
         and c.get("sha256")==args.expected_zip_sha256 and c.get("codeSigning")=="unsigned_development" and c.get("published") is False
         and c.get("installed") is False and c.get("guiAcceptance")=="unverified" and hash_ok(c.get("runtimeInputLockSha256"))
         and c.get("cuaSourceCommit")==pack.CUA_COMMIT and c.get("agentDesktopSourceCommit")==pack.AGENT_DESKTOP_COMMIT,"Candidate receipt does not identify these original build inputs")
    out=args.output
    if args.action=="prepare":
        must(out is not None and not out.exists() and not out.is_symlink(),"A new local preparation directory is required")
        candidate.local(out.parent,directory=True); pack.safe_name(out.name)
        must(candidate.distinct(out,path.parent) and candidate.distinct(out,candidate.PRODUCT),"Keep the new output separate from the source candidate and product checkout")
    else: must(out is None,"A check never accepts or writes a destination")
    with locked(path.parent/original,2*1024**3) as (file,size):
        must(type(c.get("bytes")) is int and size==c["bytes"],"Original candidate size differs")
        sha=hashlib.sha256()
        for block in iter(lambda:file.read(262144),b""): sha.update(block)
        must(sha.hexdigest()==args.expected_zip_sha256,"Candidate archive does not match its independently supplied SHA-256")
        file.seek(0)
        with zipfile.ZipFile(file) as z:
            entries=archive_map(z)
            def metadata(name,limit,sha=None):
                e=entries[name]; must(0<e.file_size<=limit,"Metadata exceeds its bound")
                with z.open(e) as stream: raw=stream.read(limit+1)
                must(len(raw)==e.file_size and (sha is None or hash_ok(sha) and hashlib.sha256(raw).hexdigest()==sha),"Metadata identity mismatch")
                return obj(raw)
            m=metadata("bundle-manifest.json",8*1024**2)
            must(m.get("product")=="PocketWorld-Companion" and m.get("platform")=="windows-x64" and m.get("schemaVersion")==1
                 and m.get("version")==v and m.get("sourceCommit")==args.source_commit and m.get("codeSigning")=="unsigned_development"
                 and m.get("credentialsIncluded") is False and "signing-provenance.json" not in entries,"Not the expected pre-sign Windows candidate")
            rows=m.get("files"); must(isinstance(rows,list) and len(rows)==len(entries)-1,"Incomplete bundle inventory")
            declared={}
            for row in rows:
                name=row["path"]
                must(name in entries and name!="bundle-manifest.json" and name not in declared and hash_ok(row["sha256"])
                     and type(row["bytes"]) is int and row["bytes"]==entries[name].file_size,"Bundle manifest and ZIP differ")
                declared[name]=row
            rp="runtime-build-provenance.json"; rs=declared[rp]["sha256"]
            r=metadata(rp,12*1024**2,rs)
            must(rs==m.get("runtimeProvenanceSha256") and r.get("format")=="pocketworld.windows-runtime-build.v1" and r.get("sourceClean") is True
                 and r.get("platform")=="windows-x64" and r.get("sourceCommit")==args.source_commit and r.get("codeSigning")=="not_performed"
                 and r.get("inputLockSha256")==c["runtimeInputLockSha256"],"Original runtime build identity missing")
            d="driver/cua-build-provenance.json"; sem="driver/agent-desktop-build-provenance.json"
            a=metadata(d,131072,declared[d]["sha256"]); b=metadata(sem,131072,declared[sem]["sha256"])
            for evidence,commit,manifest in ((a,pack.CUA_COMMIT,m["cua"]),(b,pack.AGENT_DESKTOP_COMMIT,m["agentDesktop"])):
                must(evidence.get("sourceClean") is True and evidence.get("platform")=="windows-x64" and evidence.get("sourceCommit")==commit
                     and manifest.get("sourceCommit")==commit and evidence.get("artifactSha256")==manifest.get("sha256"),"Original native driver identity differs")
            expected={RUNTIME:r["unsignedFiles"]["PocketWorld-Companion.exe"],CUA:a["artifactSha256"],AGENT:b["artifactSha256"]}
            must(set(r["managementUnsignedFiles"])==set(pack.MANAGEMENT_SCRIPTS),"Original four management scripts unclear")
            expected.update({"tools/"+n:h for n,h in r["managementUnsignedFiles"].items()})
            fields=[]
            for name in TARGETS:
                e=entries[name]; h=expected[name]
                must(declared[name]["sha256"]==h and 0<e.file_size<=(1048576 if name.startswith("tools/") else LIMIT),"Pre-sign member contract differs")
                digest_entry(z,e,h); fields.append({"path":name,"bytes":e.file_size,"unsignedSha256":h})
            request={"format":"pocketworld.windows-signing-input-request.v1","sourceCommit":args.source_commit,"version":v,
                     "candidateZipSha256":args.expected_zip_sha256,"runtimeInputLockSha256":c["runtimeInputLockSha256"],"inputs":fields,
                     "signingPerformed":False,"signerSelected":False,"published":False,"installableRelease":False}
            if out is not None:
                # Stage a complete closed set; on Windows rename cannot replace an existing directory.
                with tempfile.TemporaryDirectory(prefix=".pw-pre-sign-",dir=out.parent) as tmp:
                    root=Path(tmp)/"prepared"; root.mkdir()
                    for parent in ("app","driver","tools"): (root/parent).mkdir()
                    for row in fields:
                        name=row["path"]; target=root/name
                        with target.open("xb") as dst: digest_entry(z,entries[name],row["unsignedSha256"],dst)
                        must(target.stat().st_size==row["bytes"] and rt.file_hash(target)==row["unsignedSha256"],"Prepared individual file differs")
                    note=(json.dumps(request,sort_keys=True,indent=2)+"\n").encode("utf-8")
                    with (root/"signing-request.json").open("xb") as f: f.write(note)
                    complete={row["path"]:row["unsignedSha256"] for row in fields}
                    complete["signing-request.json"]=hashlib.sha256(note).hexdigest()
                    must(rt.inventory(root)==complete,"Prepared files do not form exactly this seven-file handoff plus its request")
                    root.rename(out)
            return request


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("action",choices=("check","prepare")); p.add_argument("--candidate",required=True,type=Path)
    p.add_argument("--expected-zip-sha256",required=True); p.add_argument("--source-commit",required=True)
    p.add_argument("--output",type=Path,help="prepare only: new directory outside both the original candidate and product")
    args=p.parse_args()
    try:
        request=prepare(args); print(json.dumps({"prepared":args.action=="prepare","request":request},indent=2)); return 0
    except Exception as failure:
        print("Original signing inputs not confirmed; no signing or release was performed. "+str(failure),file=sys.stderr); return 1


if __name__=="__main__": raise SystemExit(main())
