#!/usr/bin/env python3
"""Cross-build the APK's pinned ARM64 PRoot payloads; never run target code."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import shlex
import subprocess
import sys
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "app/src/main/java/dev/pocketengine/features/local/runtime/native"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, cwd, env):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=env, check=True)


# Upstream and distribution caches for one strictly pinned source.
# Every byte is still checked against source-lock.json before being accepted.
TALLOC_PRIMARY = "https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz"
TALLOC_MIRROR = "https://download.samba.org/pub/talloc/talloc-2.5.0.tar.gz"
TALLOC_DISTFILE_MIRRORS = (
    "https://distfiles.macports.org/talloc/talloc-2.5.0.tar.gz",
    "https://distfiles.gentoo.org/distfiles/32/talloc-2.5.0.tar.gz",
)
TALLOC_SHA256 = "912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"


def source_urls(source):
    primary = source["url"]
    if (source["name"] == "talloc" and primary == TALLOC_PRIMARY
            and source["sha256"] == TALLOC_SHA256 and source["archiveBytes"] == 690172):
        return (primary, TALLOC_MIRROR, *TALLOC_DISTFILE_MIRRORS)
    return (primary,)


def archive(source, cache, offline):
    path = cache / (source["name"] + "-" + source["sha256"] + "." + source["format"])
    if not path.exists():
        if offline:
            raise RuntimeError("Missing verified source archive in offline cache: " + str(path))
        temporary = path.with_suffix(path.suffix + ".part")
        # Use trusted HTTPS sources only. A retry or fallback never bypasses the pinned hash.
        failures = []
        for url in source_urls(source):
            temporary.unlink(missing_ok=True)
            try:
                run(["curl", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https", "--tlsv1.2",
                     "--retry", "1", "--retry-delay", "2", "--retry-all-errors",
                     "--connect-timeout", "10", "--max-time", "75",
                     "--speed-limit", "1024", "--speed-time", "20",
                     "--silent", "--show-error", url, "--output", temporary], cache, os.environ.copy())
                if temporary.stat().st_size != source["archiveBytes"] or digest(temporary) != source["sha256"]:
                    raise RuntimeError("Archive differs from reviewed source lock")
                temporary.replace(path)
                break
            except (subprocess.CalledProcessError, OSError, RuntimeError) as failure:
                failures.append(type(failure).__name__)
                temporary.unlink(missing_ok=True)
        else:
            raise RuntimeError("All verified source download endpoints failed for "
                               + source["name"] + ": " + ", ".join(failures))
    if path.stat().st_size != source["archiveBytes"] or digest(path) != source["sha256"]:
        raise RuntimeError("Source cache failed integrity verification: " + str(path))
    return path


def extract(path, source, target):
    expected = source["directory"]
    if source["format"] == "zip":
        with zipfile.ZipFile(path) as data:
            for entry in data.infolist():
                name = PurePosixPath(entry.filename)
                if name.is_absolute() or ".." in name.parts or name.parts[0] != expected:
                    raise RuntimeError("Unexpected source archive path: " + entry.filename)
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    # The pinned PRoot archive has an AGENTS/CLAUDE documentation alias.
                    # It is not a build input. Never materialize source ZIP symlinks.
                    continue
                data.extract(entry, target)
    else:
        with tarfile.open(path) as data:
            for entry in data.getmembers():
                name = PurePosixPath(entry.name)
                if name.is_absolute() or ".." in name.parts or name.parts[0] != expected:
                    raise RuntimeError("Unexpected source archive path: " + entry.name)
            data.extractall(target, filter="data")


def elf_info(path, readelf, executable):
    header = subprocess.check_output([str(readelf), "-h", str(path)], text=True)
    program = subprocess.check_output([str(readelf), "-lW", str(path)], text=True)
    dynamic = subprocess.check_output([str(readelf), "-dW", str(path)], text=True)
    if "AArch64" not in header or "ELF64" not in header:
        raise RuntimeError("Payload is not ELF64 AArch64: " + str(path))
    if executable and ("DYN" not in header or not any(
            "(FLAGS_1)" in line and "PIE" in line for line in dynamic.splitlines())):
        raise RuntimeError("PRoot must be a PIE executable: " + str(path))
    loads = [line.split() for line in program.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line[-1], 16) < 16384 for line in loads):
        raise RuntimeError("Payload PT_LOAD alignment is below 16 KiB: " + str(path))
    dependencies = []
    for line in dynamic.splitlines():
        if "(NEEDED)" in line:
            dependencies.append(line.split("[", 1)[1].split("]", 1)[0])
    allowed = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libandroid.so"}
    if set(dependencies) - allowed:
        raise RuntimeError("Unpackaged native dependency: " + str(dependencies))
    if "RPATH" in dynamic or "RUNPATH" in dynamic:
        raise RuntimeError("Build-host RPATH must not enter APK payloads")
    if executable and "/system/bin/linker64" not in program:
        raise RuntimeError("PRoot must use the Android system linker")
    if not executable and ("INTERP" in program or dependencies):
        raise RuntimeError("The PRoot loader must be freestanding/static")
    return {"file": path.name, "sha256": digest(path), "bytes": path.stat().st_size,
            "needed": dependencies, "minimumLoadAlignment": min(int(x[-1], 16) for x in loads)}


def loader_info(loader, readelf, destination):
    # Same two symbol values as upstream loader-info.awk, without a GNU awk dependency.
    symbols = {}
    for line in subprocess.check_output([str(readelf), "-sW", str(loader)], text=True).splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[-1] in {"_start", "pokedata_workaround"}:
            symbols[fields[-1]] = int(fields[1], 16)
    if set(symbols) != {"_start", "pokedata_workaround"}:
        raise RuntimeError("The pinned ARM64 loader did not expose its workaround symbols")
    offset = symbols["pokedata_workaround"] - symbols["_start"]
    destination.write_text("#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround="
                           + str(offset) + ";\n")


def build(args):
    lock = json.loads((NATIVE / "source-lock.json").read_text())
    ndk = args.ndk.resolve()
    properties = (ndk / "source.properties").read_text()
    if "Pkg.Revision = " + lock["ndkVersion"] not in properties:
        raise RuntimeError("Use the exact NDK version in source-lock.json")
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    cc = toolchain / ("aarch64-linux-android" + str(lock["api"]) + "-clang")
    if not cc.is_file():
        raise RuntimeError("This build entry requires the official Linux x86_64 NDK host tools")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    cache = args.cache.resolve() if args.cache else output / "downloads"
    cache.mkdir(parents=True, exist_ok=True)
    sources = [(source, archive(source, cache, args.offline)) for source in lock["sources"]]
    work = output / "work"
    signature = hashlib.sha256((digest(Path(__file__)) + digest(NATIVE / "source-lock.json")
                               + digest(NATIVE / "talloc-cross-answers.txt")).encode()).hexdigest()
    stamp = work / ".pocketworld-runtime-build"
    if work.is_symlink():
        raise RuntimeError("Refusing a symlink as a native build work directory")
    if work.exists() and (not stamp.is_file() or stamp.read_text() != signature
                          or not (work / ".sources-ready").is_file()):
        if not stamp.is_file():
            raise RuntimeError("Refusing to replace a work directory not owned by this builder")
        shutil.rmtree(work)
    if not work.exists():
        work.mkdir()
        stamp.write_text(signature)
        for source, path in sources:
            extract(path, source, work)
        for patch in lock.get("patches", []):
            file = NATIVE / patch["path"]
            if digest(file) != patch["sha256"]:
                raise RuntimeError("Native source patch failed integrity verification")
            source = next(s for s in lock["sources"] if s["name"] == patch["source"])
            run(["patch", "--batch", "--fuzz=0", "-p1", "--input", file],
                work / source["directory"], os.environ.copy())
        (work / ".sources-ready").touch()
    prefix = work / "prefix"
    (prefix / "include/sys").mkdir(parents=True, exist_ok=True)
    (prefix / "lib").mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(CC=str(cc), AR=str(toolchain / "llvm-ar"), RANLIB=str(toolchain / "llvm-ranlib"),
               STRIP=str(toolchain / "llvm-strip"), PYTHON=sys.executable, PYTHONHASHSEED="1",
               SOURCE_DATE_EPOCH="0", LC_ALL="C", TZ="UTC", JOBS="1")
    cflags = ["-O2", "-fPIC", "-ffile-prefix-map=" + str(work) + "=/src/pocketworld-runtime"]
    flags = shlex.join(cflags)
    env["CFLAGS"] = flags
    env["LDFLAGS"] = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    talloc = work / "talloc-2.5.0"
    if not (talloc / "bin/default/config.h").exists():
        run(["sh", "./configure", "--prefix=" + str(prefix), "--disable-rpath", "--disable-python",
             "--cross-compile", "--cross-answers=" + str(NATIVE / "talloc-cross-answers.txt")], talloc, env)
    run([sys.executable, "buildtools/bin/waf", "build", "--targets=talloc", "--jobs=1"], talloc, env)
    objects = sorted((talloc / "bin/default").glob("talloc*.o"))
    if not objects:
        raise RuntimeError("Upstream talloc build did not produce its allocator object")
    # Waf's builtin replace objects supply e.g. rep_memset_explicit on Bionic API 30.
    objects += sorted((talloc / "bin/default/lib/replace").glob("*.c.2.o"))
    run([toolchain / "llvm-ar", "rcs", prefix / "lib/libtalloc.a", *objects], work, env)
    shutil.copyfile(talloc / "talloc.h", prefix / "include/talloc.h")
    shmem = work / "libandroid-shmem-0.7"
    shutil.copyfile(shmem / "shm.h", prefix / "include/sys/shm.h")
    run([cc, *cflags, "-std=c11", "-c", "shmem.c", "-o", "shmem.o"], shmem, env)
    run([toolchain / "llvm-ar", "rcs", prefix / "lib/libandroid-shmem.a", shmem / "shmem.o"], work, env)
    proot = work / "proot-5.1.107.101/src"
    env["CPPFLAGS"] = "-DARG_MAX=131072 -DVERSION=\\\"5.1.107.101\\\" -I" + shlex.quote(str(prefix / "include"))
    env["LDFLAGS"] += " -L" + shlex.quote(str(prefix / "lib")) + " -llog -landroid -Wl,-z,noexecstack"
    env["LOADER_LDFLAGS"] = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    # Upstream's loader uses a fixed high address and must not become a normal PIE.
    # The invalid fallback ensures an omitted PROOT_LOADER never executes from writable home.
    make = ["make", "-j1", "-C", proot, "GIT=false", "HAS_LOADER_32BIT=", "PROOT_WITH_LIBANDROID_SHMEM=true",
         "PROOT_UNBUNDLE_LOADER=/pocketworld-requires-apk-native-library-dir",
         "OBJCOPY=" + str(toolchain / "llvm-objcopy"),
         "OBJDUMP=" + str(toolchain / "llvm-objdump")]
    run([*make, "loader/loader"], work, env)
    loader_info(proot / "loader/loader", toolchain / "llvm-readelf", proot / "loader/loader-info.c")
    run([*make, "proot"], work, env)
    destination = output / "jniLibs" / lock["abi"]
    destination.mkdir(parents=True, exist_ok=True)
    for origin, name in [(proot / "proot", "libproot.so"), (proot / "loader/loader", "libloader.so")]:
        target = destination / name
        shutil.copyfile(origin, target)
        target.chmod(0o755)
        run([toolchain / "llvm-strip", "--strip-unneeded", target], output, env)
    info = [elf_info(destination / name, toolchain / "llvm-readelf", name == "libproot.so")
            for name in ["libproot.so", "libloader.so"]]
    if not args.no_catalog_check:
        catalog = json.loads((ROOT / "app/src/main/assets/runtime/catalog.json").read_text())
        expected = [catalog["engine"]["prootSha256"], catalog["engine"]["loaderSha256"]]
        if [entry["sha256"] for entry in info] != expected:
            raise RuntimeError("Native payload bytes differ from the reviewed runtime catalog")
    receipt = {"schema": "pocketworld.android-native-build.v1", "abi": lock["abi"], "api": lock["api"],
               "ndkVersion": lock["ndkVersion"], "sourceLockSha256": digest(NATIVE / "source-lock.json"),
               "builderSha256": digest(Path(__file__)), "sources": lock["sources"],
               "patches": lock.get("patches", []), "payloads": info,
               "targetExecution": "not-run; cross-compilation and ELF verification only"}
    (output / "native-build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--no-catalog-check", action="store_true", help="Source maintainer's initial pin only")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / ".build.lock").open("w") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        build(args)


if __name__ == "__main__":
    main()
