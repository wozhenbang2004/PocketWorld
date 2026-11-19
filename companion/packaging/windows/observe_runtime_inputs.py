"""Release engineer's OFFLINE observation, not a build input lock or permission to build.

Run from a trusted source checkout with the dedicated Windows x64 BASE Python
-I -S -B. Do not run the target venv or its .pth/startup hooks. No subprocess,
network, package install, subprocess probe, signature, compilation or Companion.
"""
import argparse
from contextlib import contextmanager, nullcontext
from email.parser import BytesHeaderParser
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import stat
import sys
import zipfile

HERE = Path(__file__).resolve().parent
COMPANION = HERE.parents[1]
FORMAT = "pocketworld.windows-prepared-runtime-observation.v1"
NO_RECALL = 0x441400
MIB = 1024**2
MAX_FILES = 100000


class Incomplete(ValueError):
    pass


def must(ok, message):
    if not ok: raise Incomplete(message)


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, file)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def bytes_for(v):
    return (json.dumps(v, ensure_ascii=True, sort_keys=True, indent=2) + "\n").encode("ascii")


def fingerprint(v):
    return hashlib.sha256(v).hexdigest()


def hash_stream(f):
    f.seek(0)
    h = hashlib.sha256()
    for data in iter(lambda: f.read(262144), b""): h.update(data)
    return h.hexdigest()


def x64_launcher(stream, size):
    # Static PE header only. Do not load or execute the selected venv launcher.
    d = stream.read(64)
    must(len(d)==64 and d[:2]==b"MZ","Selected interpreter is not a PE image")
    offset = int.from_bytes(d[60:64],"little")
    must(64<=offset<=min(size-26,4*MIB),"Unsupported PE header bounds")
    stream.seek(offset); h=stream.read(26)
    must(len(h)==26 and h[:4]==b"PE\0\0" and int.from_bytes(h[4:6],"little")==0x8664
         and 1<=int.from_bytes(h[6:8],"little")<=96 and int.from_bytes(h[20:22],"little")>=112
         and int.from_bytes(h[22:24],"little")&2 and int.from_bytes(h[24:26],"little")==0x20b,
         "Selected interpreter is not an x64 executable image")


def checked_path(p, *, directory):
    must(p.is_absolute() and len(p.parts) >= 2 and re.fullmatch(r"[A-Za-z]:", p.drive) and ".." not in p.parts,
         "Choose explicit dedicated paths on a local fixed drive; never a drive root or network path")
    for node in [*reversed(p.parents), p]:
        s = node.lstat()
        must(not stat.S_ISLNK(s.st_mode) and not getattr(s, "st_file_attributes", 0) & NO_RECALL,
             "A chosen tree is linked, offline or would recall cloud data")
    must(p.is_dir() if directory else p.is_file(), "A required local file or directory is missing")


def identities(path):
    out = []
    for p in (path, *path.parents):
        s = p.lstat()
        must(s.st_ino != 0, "Local directory identity unavailable")
        out.append((s.st_dev, s.st_ino))
    return out


@contextmanager
def selected_native(path, backend, maximum, *, parent_handle=None):
    # Lock the original non-reparse ancestors + the actual regular single-link file while
    # reading. The descriptor below consumes this SAME native handle, not a name re-open.
    import msvcrt
    api = backend.api
    if parent_handle is None:
        checked_path(path, directory=False)
    else:
        must(api.final(parent_handle).rstrip("\\").casefold() == ("\\\\?\\" + str(path.parent)).rstrip("\\").casefold()
             and not getattr(path.lstat(),"st_file_attributes",0) & NO_RECALL, "Original input parent or file attributes changed")
    with (backend.directory(str(path.parent)) if parent_handle is None else nullcontext()):
        handle = api.open(str(path))
        try:
            info = api.info(handle)
            size = (info.sizeHigh << 32) + info.sizeLow
            must(info.links == 1 and not info.attributes & (0x10 | NO_RECALL) and size <= maximum
                 and api.final(handle).casefold() == ("\\\\?\\" + str(path)).casefold(), "Unsafe or oversized native build file")
            start = (api.identity(info), info.written.dwHighDateTime, info.written.dwLowDateTime, size)
            fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY); handle = None
            with os.fdopen(fd, "rb") as source:
                yield source, start
                info = api.info(msvcrt.get_osfhandle(source.fileno()))
                must(start == (api.identity(info), info.written.dwHighDateTime, info.written.dwLowDateTime,
                               (info.sizeHigh << 32) + info.sizeLow), "Build file changed during observation")
        finally:
            if handle is not None: api.k.CloseHandle(handle)


def contents(path, backend, maximum):
    with selected_native(path, backend, maximum) as (stream, _):
        return stream.read(maximum + 1)


def tree(root, backend, rt):
    # Match the ORIGINAL build tree hash convention, excluding its bytecode caches.
    # Report only count/bytes/digest, never local filenames or a user's path.
    files = {}; stamps = {}; total = 0; nodes = 0
    def visit(parts, second=False):
        nonlocal total, nodes
        with backend.directory(str(root), parts) as (path, handle, _):
            must(not backend.api.info(handle).attributes & NO_RECALL, "Tree contains a placeholder/offline directory")
            with os.scandir(path) as entries:
                names = []
                for e in entries:
                    nodes += 1
                    must(nodes <= 2*(MAX_FILES+10000), "Prepared tree exceeds entry bound")
                    rel = rt.safe_name("/".join((*parts,e.name)))
                    s = e.stat(follow_symlinks=False)
                    must(not stat.S_ISLNK(s.st_mode) and not getattr(s, "st_file_attributes", 0) & NO_RECALL, "Prepared tree contains a link/placeholder")
                    if stat.S_ISDIR(s.st_mode) and e.name == "__pycache__": continue
                    if stat.S_ISREG(s.st_mode) and Path(e.name).suffix.lower() in {".pyc", ".pyo"}: continue
                    names.append((e.name, rel, s))
            seen = set()
            for name, rel, s in names:
                must(name.casefold() not in seen, "Case-aliased names in prepared tree"); seen.add(name.casefold())
                if stat.S_ISDIR(s.st_mode):
                    visit((*parts, name), second); continue
                must(stat.S_ISREG(s.st_mode), "Non-file material in prepared tree")
                with selected_native(root / rel, backend, 2*1024*MIB, parent_handle=handle) as (stream, stamp):
                    if second:
                        must(stamps.pop(rel, None) == stamp, "Prepared tree changed or was substituted")
                    else:
                        total += stamp[3]; must(total <= 2*1024*MIB and len(files) < MAX_FILES, "Prepared tree exceeds size/count bound")
                        stamps[rel] = stamp; files[rel] = hash_stream(stream)
    visit(()); visit((), True)
    must(not stamps, "Prepared files disappeared before observation was complete")
    raw = json.dumps(files, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    return {"regularFiles":len(files), "bytes":total, "treeSha256":fingerprint(raw), "bytecodeCachesExcluded":True}


def metadata(raw, rt):
    m = BytesHeaderParser().parsebytes(raw)
    names, versions = m.get_all("Name",[]), m.get_all("Version",[])
    must(len(names) == len(versions) == 1 and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.!+_-]{0,127}", versions[0]),
         "One exact distribution name and version required")
    return rt.normalized(names[0]), versions[0]


def observed_packages(env, wheels, backend, rt):
    site = env / "Lib/site-packages"; installed = {}; packages=[]; startup=0
    with backend.directory(str(site)) as (path,_,_):
        with os.scandir(path) as entries:
            for i,e in enumerate(entries,1):
                must(i <= 4096, "Site directory exceeds its bound")
                lower=e.name.casefold()
                must(not lower.endswith((".egg-info",".egg-link",".egg")), "Only dedicated wheel installations are supported")
                if lower.endswith(".pth"): startup += 1
                if not lower.endswith(".dist-info"): continue
                raw = contents(Path(e.path)/"METADATA",backend,MIB); name,ver=metadata(raw,rt)
                must(len(installed)<64 and name not in installed, "Duplicate or excessive installed distributions")
                installed[name]=(ver,fingerprint(raw))
    total=0
    with backend.directory(str(wheels)) as (path,wh_handle,_):
        with os.scandir(path) as entries:
            for i,e in enumerate(entries,1):
                must(i<=64 and e.name.endswith(".whl") and rt.safe_name(e.name)==e.name, "Use exactly the flat selected wheelhouse, one wheel per distribution")
                with selected_native(Path(e.path),backend,256*MIB,parent_handle=wh_handle) as (source,stamp):
                    total+=stamp[3]; must(total<=2*1024*MIB,"Selected wheels exceed 2 GiB")
                    sha=hash_stream(source)
                    with zipfile.ZipFile(source) as archive:
                        members=archive.infolist(); must(len(members)<=100000,"Excessive wheel archive")
                        ms=[m for m in members if re.fullmatch(r"[^/]+\.dist-info/METADATA",m.filename)]
                        must(len(ms)==1 and 0<ms[0].file_size<=MIB and not ms[0].flag_bits&1 and
                             not stat.S_ISLNK(ms[0].external_attr>>16), "One bounded ordinary wheel metadata member required")
                        raw=archive.read(ms[0]); name,ver=metadata(raw,rt)
                    must(installed.pop(name,None)==(ver,fingerprint(raw)), "Wheel metadata does not match exactly one installed distribution")
                    packages.append({"name":name,"version":ver,"wheel":e.name,"sha256":sha,"bytes":stamp[3]})
    must(not installed and 4<=len(packages)<=64,"Not every installed distribution has its exact selected wheel")
    return sorted(packages,key=lambda item:item["name"]),startup


def compare(obs, approved, rt):
    py=obs["observedPython"]
    expected={"pythonVersion":py["version"],"pythonExecutableSha256":py["venvLauncherSha256"],"basePrefixSha256":py["base"]["treeSha256"],
              "environmentSha256":py["environment"]["treeSha256"],"pythonLicenseSha256":py["baseLicenseSha256"]}
    differences=[k for k,v in expected.items() if approved[k]!=v]
    ref={rt.normalized(p["name"]): (p["version"],p["wheel"],p["sha256"]) for p in approved["packages"]}
    actual={p["name"]:(p["version"],p["wheel"],p["sha256"]) for p in obs["observedWheels"]}
    for name in sorted(ref.keys() | actual.keys()):
        if ref.get(name)!=actual.get(name): differences.append("distribution:"+name)
    return {"byteEvidenceMatches":not differences,"differingItems":differences,
            "scope":"selected runtime bytes and distribution versions only; not release, origin, toolchain or native acceptance"}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=["observe","compare"])
    for field in ("base","venv","wheelhouse","report"): p.add_argument("--"+field, type=Path, required=True)
    p.add_argument("--input-lock",type=Path); p.add_argument("--input-lock-sha256"); args=p.parse_args()
    must((args.action=="compare")==bool(args.input_lock and args.input_lock_sha256) and
         bool(args.input_lock)==bool(args.input_lock_sha256),"Comparison requires a separate existing lock and independently approved SHA-256")
    must(sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode, "Launch the trusted BASE Python with -I -S -B")
    rt=module("pw_observer_runtime",HERE/"build_runtime.py"); rt.require_windows_x64()
    # Only these two original source modules, not site-packages or arbitrary modules supplied by input.
    pb=module("platform_backend",COMPANION/"platform_backend.py"); sys.modules["platform_backend"]=pb
    native=module("pw_observer_win32",COMPANION/"platform_windows.py"); backend=native.WindowsBackend()
    base,env,wh,out=args.base,args.venv,args.wheelhouse,args.report
    roots=(base,env,wh); ids=[]
    for root in (*roots,HERE.parents[2],out.parent):
        checked_path(root,directory=True); ids.append(identities(root))
        with backend.directory(str(root)): pass
    must(all(a[0] not in b and b[0] not in a for i,a in enumerate(ids[:4]) for b in ids[:i]) and
         all(a[0] not in ids[4] for a in ids[:4]),"Builder inputs, product checkout and output location must be isolated")
    must(rt.safe_name(out.name)==out.name and out.suffix.lower()==".json" and not os.path.lexists(out),"Choose a new report filename; nothing is overwritten")
    must(os.path.samefile(base,sys.base_prefix) and os.path.samefile(Path(sys.executable),base/"python.exe"),"Run THIS chosen base interpreter, not a venv or another Python")
    cfg=contents(env/"pyvenv.cfg",backend,16384).decode("utf-8-sig"); vals={}
    for line in cfg.splitlines():
        if not line.strip(): continue
        key,found,val=line.partition("="); key=key.strip().lower()
        must(found and key not in vals,"Ambiguous pyvenv.cfg"); vals[key]=val.strip()
    must(os.path.normcase(vals.get("home",""))==os.path.normcase(str(base)) and vals.get("include-system-site-packages","").lower()=="false"
         and vals.get("version")==platform.python_version(),"This venv was not created from the selected exact isolated base")
    # Never follow 'home', 'command', executable or URLs read from metadata.
    original=None
    if args.input_lock:
        checked_path(args.input_lock,directory=False)
        original=rt.read_lock(args.input_lock,args.input_lock_sha256)
    python={"version":platform.python_version()}
    for name,file in (("baseLauncherSha256",base/"python.exe"),("venvLauncherSha256",env/"Scripts/python.exe"),("baseLicenseSha256",base/"LICENSE.txt")):
        with selected_native(file,backend,64*MIB) as (stream,stamp):
            if name!="baseLicenseSha256": x64_launcher(stream,stamp[3])
            python[name]=hash_stream(stream)
    packages,startup=observed_packages(env,wh,backend,rt)
    partial={"packages":packages}
    for fn,name in ((rt.require_pairing_wheels,"windows-pairing-dependencies.json"),(rt.require_desktop_wheel,"windows-desktop-dependencies.json"),(rt.require_stream_wheels,"windows-stream-dependencies.json")):
        fn(partial,json.loads(contents(COMPANION/"packaging"/name,backend,262144)))
    versions={p["name"]:p["version"] for p in packages}
    must(versions.get("cryptography")=="50.0.0" and versions.get("pyinstaller","").split(".")[0]=="6","Current product freezing/identity requirements do not match")
    python.update(base=tree(base,backend,rt),environment=tree(env,backend,rt))
    # Recheck distribution membership after walking both roots. This is still not a global filesystem snapshot.
    must(observed_packages(env,wh,backend,rt)==(packages,startup),"Selected package material changed")
    obs={"format":FORMAT,"classification":"unapproved_local_observation_not_a_build_lock","mayAuthorizeBuild":False,"observedPython":python,
         "observedWheels":packages,"siteStartupFilesObserved":startup,"venvOrSiteHooksExecuted":False,
         "matchedProductWheelPins":["aiortc","av","colorama","pillow","qrcode"],"baseAndVenvLauncherHaveX64ImageHeaders":True,
         "independentSupplierProvenanceVerified":False,"nativeRuntimeTested":False,"atomicWholeTreeSnapshot":False}
    if original is not None:
        obs["referenceComparison"]=compare(obs,original,rt)
    raw=bytes_for(obs)
    with backend.directory(str(out.parent)):
        with out.open("x+b") as f:
            must(f.write(raw)==len(raw),"Incomplete new report"); f.flush(); os.fsync(f.fileno()); f.seek(0)
            must(f.read(len(raw)+1)==raw,"New report bytes not confirmed")
    matched=obs.get("referenceComparison",{}).get("byteEvidenceMatches")
    print("Observation saved; NOT an approved lock, package or runtime validation." +
          ("" if matched is None else " Selected reference bytes "+("match." if matched else "DIFFER.")))
    return 2 if matched is False else 0


if __name__=="__main__":
    try: raise SystemExit(main())
    except Incomplete as e: raise SystemExit("Observation stopped: "+str(e))
    except Exception as e: raise SystemExit("Observation stopped at a native input gate ("+type(e).__name__+"). No verified package or lock was produced.")
