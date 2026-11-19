"""A prepared Windows builder's single entry: check, then explicitly produce a LOCAL unsigned candidate.
No tool/dependency setup, signing, publishing, installation, pairing or GUI acceptance. A checked
input does not prove that the native toolchain has all cached crates, SDK libs or media DLLs.
"""
import argparse
import ctypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys

HERE = Path(__file__).resolve().parent
PRODUCT = HERE.parents[2]
sys.dont_write_bytecode = True   # even the read-only check must not write caches to the checkout


def sibling(name):
    spec = importlib.util.spec_from_file_location("pw_"+name, HERE / (name+".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rt, pack = sibling("build_runtime"), sibling("build_bundle")


def local(path, *, directory):
    p = Path(path)
    get_drive=ctypes.windll.kernel32.GetDriveTypeW
    get_drive.argtypes=(ctypes.c_wchar_p,); get_drive.restype=ctypes.c_uint
    if (not re.fullmatch(r"[a-zA-Z]:", p.drive) or not p.is_absolute() or ".." in p.parts
            or get_drive(str(p.anchor)) != 3):
        raise ValueError("Choose an absolute path on a local fixed drive")
    for piece in [*reversed(p.parents), p]:
        info = piece.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info,"st_file_attributes",0) & 0x441400:
            raise ValueError("Input is linked, not on disk or recall-on-access")
    if not (p.is_dir() if directory else p.is_file()): raise ValueError("Required ordinary file/folder missing")
    return p


def distinct(a,b):
    return not (a.is_relative_to(b) or b.is_relative_to(a))


def environment(out):
    env = rt.build_environment(out)
    # Only normal prepared native compiler/location variables. No arbitrary build prefix, signing
    # secrets, Git credential configuration or shell code from the caller.
    keys = {"CARGO_HOME","RUSTUP_HOME","LIB","LIBPATH","INCLUDE","VSINSTALLDIR","VCINSTALLDIR","VCTOOLSINSTALLDIR",
            "WINDOWSSDKDIR","WINDOWSSDKVERSION","UNIVERSALCRTSDKDIR","UCRTVERSION","PROGRAMFILES","PROGRAMFILES(X86)",
            "PROGRAMW6432","COMMONPROGRAMFILES","COMMONPROGRAMFILES(X86)","COMMONPROGRAMW6432"}
    env.update({k:v for k,v in os.environ.items() if k.upper() in keys})
    env.update(CARGO_NET_OFFLINE="true",GIT_OPTIONAL_LOCKS="0",GIT_TERMINAL_PROMPT="0",GIT_NO_LAZY_FETCH="1",
               GIT_CONFIG_NOSYSTEM="1",GIT_CONFIG_GLOBAL=os.devnull)
    return env


def new_output(args):
    out, wheels, lock = args.output,args.wheelhouse,args.input_lock
    local(out.parent,directory=True); pack.safe_name(out.name)
    if out.exists() or out.is_symlink() or not out.is_absolute(): raise ValueError("Choose a fresh, never-used output directory")
    roots=[PRODUCT,args.cua_source,args.agent_source]
    if (any(not distinct(a,b) for i,a in enumerate(roots) for b in roots[i+1:])
            or any(not distinct(out,p) for p in (*roots,wheels,lock,Path(sys.base_prefix),Path(sys.prefix)))):
        raise ValueError("Output, source checkouts and prepared inputs must not overlap")
    pack.safe_name(args.version)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}",args.version): raise ValueError("Invalid bundle version")
    return out


def git_lock(root, relative, env, pinned=None):
    # Checks exact committed Cargo.lock bytes. A partially cloned object is not fetched.
    full=local(root / relative,directory=False)
    if full.stat().st_size>16*1024*1024: raise ValueError("Cargo.lock exceeds the input bound")
    raw=full.read_bytes()
    if len(raw)>16*1024*1024: raise ValueError("Cargo.lock changed")
    actual=hashlib.sha1(b"blob "+str(len(raw)).encode("ascii")+b"\0"+raw).hexdigest()
    git=shutil.which("git.exe")
    oid=subprocess.run([git,"-c","core.fsmonitor=false","-C",str(root),"rev-parse","--verify","HEAD:"+relative],
        env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=30).stdout.decode("ascii").strip()
    if actual!=oid or pinned is not None and actual!=pinned: raise ValueError("Source Cargo.lock bytes differ from its pinned Git object")
    return "exact Git lock "+oid


def audit(args):
    records=[]
    def record(name, fn):
        try:
            detail=fn()
        except Exception as exc:  # show failure of this bounded gate; never convert it to readiness
            records.append({"check":name,"state":"blocked","reason":str(exc)[:1024]}); return False
        records.append({"check":name,"state":"checked","detail":detail}); return True

    if not record("Windows x64",lambda: (rt.require_windows_x64() or "native Windows x64 process")): return records
    env=environment(args.output)
    def sources(root, commit, lock=None, oid=None):
        local(root,directory=True)
        rt.clean_source(root,commit,env)
        if lock: git_lock(root,lock,env,oid)
        return commit
    record("fresh isolated destination",lambda:str(new_output(args)))
    good=record("PocketWorld source",lambda:sources(PRODUCT,args.source_commit))
    record("Cua Driver source and Cargo.lock",lambda:sources(args.cua_source,pack.CUA_COMMIT,"libs/cua-driver/rust/Cargo.lock"))
    record("agent-desktop source and Cargo.lock",lambda:sources(args.agent_source,pack.AGENT_DESKTOP_COMMIT,"Cargo.lock","de3997ec90e91217baa87fefaa3363b7784c9be9"))
    def tools():
        local(Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe",directory=False)
        for exe in ("git.exe","cargo.exe","rustc.exe"):
            found=shutil.which(exe)
            if not found: raise ValueError("Already-installed tool missing: "+exe)
            local(Path(found),directory=False)
        return "paths present; no compiler/driver/GUI was run"
    record("prepared native tools",tools)
    locked={}
    def read_lock():
        lock=rt.read_lock(local(args.input_lock,directory=False),args.input_lock_sha256)
        locked.update(lock); return "matches the independently supplied SHA-256"
    trusted=record("independent full Windows runtime lock",read_lock)
    if good and trusted:
        def python():
            versions,_=rt.prepared_environment(locked,local(args.wheelhouse,directory=True))
            return f"complete interpreter/environment, {len(versions)} locked distributions, wheel bytes and license inputs"
        record("Python, frozen-runtime inputs and licenses",python)
    else: records.append({"check":"Python, frozen-runtime inputs and licenses","state":"not_checked","reason":"requires the exact product checkout and an independently verified complete runtime lock"})
    return records


def produce(args):
    out=new_output(args); out.mkdir()  # a racing or partial prior attempt never gets overwritten or resumed
    env=environment(out)
    shell=local(Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe",directory=False)
    def native(script,source,target):
        # Fixed named scripts only, not shell=True, downloadable commands, or a policy override.
        rt.clean_source(PRODUCT,args.source_commit,env)
        selected=local(HERE/script,directory=False)
        subprocess.run([str(shell),"-NoLogo","-NoProfile","-NonInteractive","-File",str(selected),
                        "-SourceRoot",str(source),"-OutputDirectory",str(target)],env=env,cwd=out,
                       stdin=subprocess.DEVNULL,check=True,timeout=3600)
    print("1/4  Cua Driver: building the pinned local source",flush=True)
    cua=out/"cua"; native("Build-CuaDriver.ps1",args.cua_source,cua)
    print("2/4  agent-desktop: building the pinned local source",flush=True)
    agent=out/"agent-desktop"; native("Build-AgentDesktop.ps1",args.agent_source,agent)
    print("3/4  Companion: freezing the reviewed local runtime",flush=True)
    runtime=rt.build(args.source_commit,args.input_lock,args.input_lock_sha256,args.wheelhouse,out/"runtime")
    exe=cua/"cua-driver.exe"
    print("4/4  Development ZIP: matching the frozen files, driver provenance and license bytes",flush=True)
    result=pack.bundle(runtime["frozenDirectory"],exe,rt.file_hash(exe),args.source_commit,args.version,
        out/("PocketWorld-Companion-"+args.version+"-dev.zip"),cua/"cua-build-provenance.json",
        runtime_provenance=runtime["runtimeProvenance"],runtime_notices=runtime["runtimeNotices"],
        agent_desktop_binary=agent/"agent-desktop.exe",agent_desktop_provenance=agent/"agent-desktop-build-provenance.json",
        allow_unsigned_development=True)
    rt.clean_source(PRODUCT,args.source_commit,env)
    archive=local(result["path"],directory=False)
    if archive.stat().st_size!=result["bytes"] or rt.file_hash(archive)!=result["sha256"]:
        raise ValueError("Final unsigned candidate no longer matches its archive receipt")
    receipt={"format":"pocketworld.windows-local-development-candidate.v1","version":args.version,"sourceCommit":args.source_commit,
             "cuaSourceCommit":pack.CUA_COMMIT,"agentDesktopSourceCommit":pack.AGENT_DESKTOP_COMMIT,
             "runtimeInputLockSha256":args.input_lock_sha256,"zip":archive.name,"bytes":result["bytes"],"sha256":result["sha256"],
             "codeSigning":"unsigned_development","installed":False,"published":False,"guiAcceptance":"unverified"}
    with (out/"candidate.json").open("x",encoding="utf-8",newline="\n") as f:
        json.dump(receipt,f,sort_keys=True,indent=2); f.write("\n")
    print(json.dumps(receipt,indent=2)); return 0


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("action",choices=("check","build-development"),help="check writes no output; build-development explicitly creates an unsigned LOCAL candidate, never a release")
    for option in ("cua-source","agent-source","input-lock","wheelhouse","output"):
        p.add_argument("--"+option,type=Path,required=True)
    for option in ("source-commit","input-lock-sha256","version"): p.add_argument("--"+option,required=True)
    args=p.parse_args(); checks=audit(args)
    print(json.dumps({"checks":checks,"built":False,"notChecked":["complete Cargo cache and MSVC SDK","working frozen program","Windows GUI","production signing"]},indent=2),flush=True)
    if any(r["state"]!="checked" for r in checks): return 2
    if args.action=="check": return 0
    try: return produce(args)
    except Exception as failure:  # preserve partial build artifacts and never continue to release/install
        print("Candidate not confirmed. Preserve this partial output for inspection; nothing restarted, signed or installed. "+str(failure),file=sys.stderr)
        return 1


if __name__=="__main__": raise SystemExit(main())
