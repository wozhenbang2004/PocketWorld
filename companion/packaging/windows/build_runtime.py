"""Freeze a clean product commit on an already-prepared Windows x64 builder.

No install, download, signing, deployment, pairing or credential creation. The
input lock is reviewed OUTSIDE this command, then supplied with its approved
SHA-256; there is deliberately no 'trust current environment' lock generator.
PyInstaller hooks are trusted build code, not an OS network/security sandbox.
"""
import argparse
from email.parser import BytesParser
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import struct
import subprocess
import sys
import zipfile

FORMAT = "pocketworld.windows-runtime-inputs.v1"
MANAGEMENT = ("Install-Companion.ps1", "Manage-Companion.ps1", "Launch-Companion.ps1", "Companion-InstallState.ps1")
HASH = re.compile(r"[a-f0-9]{64}")
SOURCE = Path(__file__).resolve().parents[3]


def require_windows_x64():
    if os.name != "nt" or sys.platform != "win32" or struct.calcsize("P") != 8 or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Actual Windows x64 builder required; no cross-build or simulated success")


def safe_name(value):
    if (not isinstance(value, str) or not value or len(value) > 1024 or "\\" in value or ":" in value
            or PurePosixPath(value).is_absolute() or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF or c in '<>"|?*' for c in value)
            or any(p in {"", ".", ".."} or p[-1:] in {".", " "} or re.fullmatch(r"(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", p) for p in value.split("/"))):
        raise ValueError("Unsafe Windows-relative build material")
    if any(p.lower() in {"companion-token.txt", "operations.sqlite3", "identity-key", ".aws", ".ssh", ".codex", ".claude"}
           or p.lower().startswith(".env") or p.lower().endswith((".key", ".pfx", ".p12")) for p in value.split("/")):
        raise ValueError("Private state/credential material is not a build input/output")
    return value


def regular(path):
    path = Path(path)
    for parent in [*path.parents, path]:
        info = parent.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("Build materials must not traverse links/reparse points")
    if not path.is_file():
        raise ValueError("Actual regular build material missing")
    return path


def file_hash(path):
    path = regular(path)
    before = path.stat()
    value = hashlib.sha256()
    with path.open("rb") as stream:
        if (os.fstat(stream.fileno()).st_dev, os.fstat(stream.fileno()).st_ino) != (before.st_dev, before.st_ino):
            raise ValueError("Build input changed before opening")
        while raw := stream.read(262144):
            value.update(raw)
        after = os.fstat(stream.fileno())
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise ValueError("Build input changed during hashing")
    return value.hexdigest()


def inventory(root, *, ignore_caches=False):
    root, result, folded, total = Path(root), {}, set(), 0
    if not root.is_dir():
        raise ValueError("Actual build directory missing")
    # Also reject a reparse point at the root/any ancestor.
    for parent in [*root.parents, root]:
        info = parent.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("Build directory traverses a link/reparse point")
    for directory, directories, files in os.walk(root, followlinks=False):
        if ignore_caches:
            directories[:] = [name for name in directories if name != "__pycache__"]
        for name in directories + files:
            path = Path(directory) / name
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("Build inventory contains a link/reparse point")
            if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
                raise ValueError("Build inventory contains a non-regular object")
            relative = safe_name(path.relative_to(root).as_posix())
            if relative.casefold() in folded:
                raise ValueError("Case-colliding Windows build materials")
            folded.add(relative.casefold())
            if path.is_file() and not (ignore_caches and path.suffix.lower() in {".pyc", ".pyo"}):
                total += info.st_size
                if len(result) >= 100000 or total > 2 * 1024**3:
                    raise ValueError("Prepared build material exceeds bounded inventory")
                result[relative] = file_hash(path)
    return dict(sorted(result.items()))


def tree_hash(root):
    # Relocatable tree digest: no absolute path, mtime or random build ID.
    raw = json.dumps(inventory(root, ignore_caches=True), sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    return hashlib.sha256(raw).hexdigest()


def normalized(name):
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name):
        raise ValueError("Invalid locked distribution name")
    return re.sub(r"[-_.]+", "-", name).lower()


def read_lock(path, approved_sha256):
    with regular(path).open("rb") as stream:
        raw = stream.read(262145)
    if len(raw) > 262144:
        raise ValueError("Input lock exceeds bound")
    if not isinstance(approved_sha256, str) or not HASH.fullmatch(approved_sha256) or hashlib.sha256(raw).hexdigest() != approved_sha256:
        raise ValueError("Input lock differs from its separately approved SHA-256")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate input-lock key")
            result[key] = value
        return result
    value = json.loads(raw, object_pairs_hook=unique)
    required = {"format", "pythonVersion", "pythonExecutableSha256", "basePrefixSha256", "environmentSha256", "pythonLicenseSha256", "packages", "toolchainDescription"}
    if not isinstance(value, dict) or set(value) != required or value.get("format") != FORMAT:
        raise ValueError("Closed, reviewed Windows runtime input lock required")
    for key in ("pythonExecutableSha256", "basePrefixSha256", "environmentSha256", "pythonLicenseSha256"):
        if not isinstance(value[key], str) or not HASH.fullmatch(value[key]):
            raise ValueError("Complete actual Python/environment input hashes required")
    if not isinstance(value["pythonVersion"], str) or not re.fullmatch(r"3\.[0-9]+\.[0-9]+", value["pythonVersion"]):
        raise ValueError("Exact Python version required")
    if not isinstance(value["toolchainDescription"], str) or not 1 <= len(value["toolchainDescription"]) <= 2000:
        raise ValueError("Reviewed toolchain description required")
    if not isinstance(value["packages"], list) or not 4 <= len(value["packages"]) <= 64:
        raise ValueError("Complete dedicated-builder wheel lock required")
    names, wheels = set(), set()
    for item in value["packages"]:
        if not isinstance(item, dict) or set(item) != {"name", "version", "wheel", "sha256"}:
            raise ValueError("Closed wheel lock entry required")
        name = normalized(item["name"])
        if name in names or not isinstance(item["version"], str) or not 1 <= len(item["version"]) <= 128:
            raise ValueError("Duplicate distribution or invalid locked version")
        wheel = safe_name(item["wheel"])
        if "/" in wheel or not wheel.endswith(".whl") or wheel.casefold() in wheels or not isinstance(item["sha256"], str) or not HASH.fullmatch(item["sha256"]):
            raise ValueError("Unique local hash-locked wheel filenames required")
        names.add(name); wheels.add(wheel.casefold())
    if not {"pyinstaller", "cryptography", "qrcode", "colorama", "pillow"} <= names:
        raise ValueError("PyInstaller, cryptography, pinned native QR and desktop PNG decoder dependencies required")
    return value


def wheel_licenses(path, item):
    """Verify actual local wheel, metadata and all contained license/notice files."""
    with regular(path).open("rb") as stream:
        raw = stream.read(256 * 1024**2 + 1)
    if len(raw) > 256 * 1024**2 or hashlib.sha256(raw).hexdigest() != item["sha256"]:
        raise ValueError("Actual prepared wheel differs from lock")
    found, metadata, seen, size = {}, [], set(), 0
    # Inspect the SAME bytes just hashed, never hash a filename then reopen a
    # potentially replaced wheel under that filename.
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if len(archive.infolist()) > 100000:
            raise ValueError("Wheel member bound exceeded")
        for entry in archive.infolist():
            name = safe_name(entry.filename.rstrip("/"))
            if name.casefold() in seen or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Aliased/link wheel member")
            seen.add(name.casefold()); size += entry.file_size
            if size > 1024**3:
                raise ValueError("Wheel expanded-byte bound exceeded")
            if name.endswith(".dist-info/METADATA"):
                metadata.append(BytesParser().parsebytes(archive.read(entry)))
            parts = [part.lower() for part in name.split("/")]
            license_member = any(part in {"licenses", "licences"} or re.match(r"^(license|licence|copying|notice)(?:[._-]|$)", part) for part in parts)
            if not entry.is_dir() and license_member:
                if entry.file_size > 1024 * 1024:
                    raise ValueError("Wheel license material exceeds bound")
                found[name] = archive.read(entry)
    if (len(metadata) != 1 or normalized(metadata[0].get("Name")) != normalized(item["name"])
            or metadata[0].get("Version") != item["version"] or not found):
        raise ValueError("Locked wheel identity or license material missing")
    return found


def prepared_environment(lock, wheelhouse):
    if (platform.python_version() != lock["pythonVersion"] or file_hash(sys.executable) != lock["pythonExecutableSha256"]
            or tree_hash(sys.base_prefix) != lock["basePrefixSha256"] or tree_hash(sys.prefix) != lock["environmentSha256"]
            or file_hash(Path(sys.base_prefix) / "LICENSE.txt") != lock["pythonLicenseSha256"]):
        raise ValueError("Actual prepared Python/interpreter/environment differs from reviewed lock")
    installed = {}
    for distribution in importlib.metadata.distributions():
        name = normalized(distribution.metadata["Name"])
        if name in installed:
            raise ValueError("Ambiguous installed distribution")
        installed[name] = distribution.version
    locked = {normalized(item["name"]): item["version"] for item in lock["packages"]}
    if installed != locked:
        raise ValueError("Prepared distribution set/versions differ; use an already-prepared dedicated builder")
    if locked["cryptography"] != "50.0.0" or locked["qrcode"] != "8.2" or locked["colorama"] != "0.4.6" or locked["pillow"] != "13.0.0" or locked["pyinstaller"].split(".")[0] != "6":
        raise ValueError("Runtime dependencies differ from current product/spec contract")
    pairing = json.loads(regular(SOURCE / "companion/packaging/windows-pairing-dependencies.json").read_text(encoding="utf-8"))
    require_pairing_wheels(lock, pairing)
    desktop = json.loads(regular(SOURCE / "companion/packaging/windows-desktop-dependencies.json").read_text(encoding="utf-8"))
    require_desktop_wheel(lock, desktop)
    licenses = {"python/LICENSE.txt": regular(Path(sys.base_prefix) / "LICENSE.txt").read_bytes()}
    for item in lock["packages"]:
        for name, raw in wheel_licenses(Path(wheelhouse) / item["wheel"], item).items():
            licenses[normalized(item["name"]) + "/" + name] = raw
    return locked, licenses


def require_pairing_wheels(lock, product_pairing_lock):
    package_records = {normalized(item["name"]): item for item in lock["packages"]}
    for item in product_pairing_lock["components"]:
        actual = package_records.get(normalized(item["name"]), {})
        if any(actual.get(key) != item[key] for key in ("version", "wheel", "sha256")):
            raise ValueError("QR wheels differ from the product's fixed official wheel lock")


def require_desktop_wheel(lock, product_lock):
    actual = next((item for item in lock["packages"] if normalized(item["name"]) == product_lock["component"]), {})
    if actual.get("version") != product_lock["version"] or not any(all(actual.get(key) == wheel[key] for key in ("wheel", "sha256")) for wheel in product_lock["wheels"]):
        raise ValueError("Desktop PNG decoder differs from the product's fixed official wheel lock")


def build_environment(output):
    allowed = {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PROGRAMDATA", "COMSPEC", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE"}
    result = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    result.update(PIP_NO_INDEX="1", PIP_DISABLE_PIP_VERSION_CHECK="1", PYINSTALLER_CONFIG_DIR=str(output / "pyinstaller-cache"),
                  PYTHONPYCACHEPREFIX=str(output / "python-cache"), PYTHONDONTWRITEBYTECODE="1", PYTHONNOUSERSITE="1")
    return result


def clean_source(source, expected_commit, environment):
    if not re.fullmatch(r"[a-f0-9]{40}", expected_commit):
        raise ValueError("Exact product commit required")
    git = shutil.which("git.exe")
    if not git:
        raise ValueError("Prepared Git required; no installation attempted")
    def read(*args):
        return subprocess.run([git, "-c", "core.fsmonitor=false", "-C", str(source), *args], env=environment, check=True,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout.decode("utf-8").strip()
    if read("rev-parse", "HEAD") != expected_commit or read("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Exact clean product checkout required; no checkout/reset/clean performed")


def record_outputs(source, source_commit, lock, lock_sha256, versions, frozen, notices):
    """Describe ONLY existing output bytes; never manufactures a runnable-build claim."""
    unsigned = inventory(frozen)
    if "PocketWorld-Companion.exe" not in unsigned:
        raise ValueError("Actual frozen Companion executable missing; no provenance published")
    licenses = inventory(notices)
    if not licenses:
        raise ValueError("Actual runtime license material missing")
    management = {name: file_hash(Path(source) / "companion/packaging/windows" / name) for name in MANAGEMENT}
    return {"format": "pocketworld.windows-runtime-build.v1", "sourceCommit": source_commit,
            "platform": "windows-x64", "pythonVersion": lock["pythonVersion"], "pyinstallerVersion": versions["pyinstaller"],
            "cryptographyVersion": versions["cryptography"], "qrcodeVersion": versions["qrcode"], "coloramaVersion": versions["colorama"], "pillowVersion": versions["pillow"],
            "toolchainDescription": lock["toolchainDescription"], "inputLockSha256": lock_sha256, "inputs": lock,
            "unsignedFiles": unsigned, "licenseFiles": licenses, "managementUnsignedFiles": management,
            "specSha256": file_hash(Path(source) / "companion/packaging/windows/pocket-companion.spec"),
            "codeSigning": "not_performed", "guiAcceptance": "unverified", "credentialsCreated": False,
            "licenseReview": "actual_locked_materials_not_legal_approval"}


def build(source_commit, input_lock, input_lock_sha256, wheelhouse, output):
    require_windows_x64()  # First action: Linux cannot create a fake successful build.
    source = SOURCE
    output, wheelhouse = (Path(os.path.abspath(os.fspath(path))) for path in (output, wheelhouse))
    if output.exists() or not output.parent.is_dir():
        raise ValueError("New output directory under an existing approved parent required")
    for parent in [*output.parent.parents, output.parent]:
        info = parent.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("Build output parent traverses a link/reparse point")
    for protected in (source, Path(sys.prefix), Path(sys.base_prefix), wheelhouse):
        if output.is_relative_to(protected.absolute()) or protected.absolute().is_relative_to(output):
            raise ValueError("Build output must be separate from source, interpreter and wheel inputs")
    regular(Path(__file__))  # Ancestor links rejected before owned output creation.
    lock = read_lock(input_lock, input_lock_sha256)
    environment = build_environment(output)
    clean_source(source, source_commit, environment)
    versions, licenses = prepared_environment(lock, wheelhouse)
    for name in MANAGEMENT:
        regular(source / "companion/packaging/windows" / name)
    spec = regular(source / "companion/packaging/windows/pocket-companion.spec")
    output.mkdir()  # Never reuses/overwrites an earlier build or deletes recovery material.
    notices = output / "runtime-notices"; notices.mkdir()
    for name, raw in licenses.items():
        target = notices / safe_name(name); target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(raw)
    command = [sys.executable, "-I", "-B", "-X", "pycache_prefix=" + str(output / "python-cache"), "-m", "PyInstaller", "--clean", "--noconfirm",
               "--distpath", str(output / "dist"), "--workpath", str(output / "work"), str(spec)]
    with (output / "build.log").open("xb") as log:
        subprocess.run(command, cwd=source, env=environment, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
    # Recheck exact source AND input bytes after executing trusted build hooks.
    clean_source(source, source_commit, environment)
    if read_lock(input_lock, input_lock_sha256) != lock or prepared_environment(lock, wheelhouse)[0] != versions:
        raise ValueError("Build inputs changed; no provenance published")
    frozen = output / "dist/PocketWorld-Companion"
    executable = regular(frozen / "PocketWorld-Companion.exe")
    # --help exits in argparse before config/state, identity, mutex or GUI work.
    with (output / "runtime-help.log").open("xb") as log:
        subprocess.run([str(executable), "--help"], cwd=output, env=environment, stdin=subprocess.DEVNULL,
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=15)
    with (output / "runtime-help.log").open("rb") as log:
        help_output = log.read(65537)
    if len(help_output) > 65536 or any(option not in help_output for option in (b"--serve", b"--pairing", b"--config")):
        raise ValueError("Actual frozen runtime entry contract not confirmed")
    provenance = record_outputs(source, source_commit, lock, input_lock_sha256, versions, frozen, notices)
    provenance.update(sourceClean=True, runtimeHelpProbe="passed")
    path = output / "runtime-build-provenance.json"
    with path.open("x", encoding="utf-8") as stream:
        json.dump(provenance, stream, sort_keys=True, indent=2); stream.write("\n"); stream.flush(); os.fsync(stream.fileno())
    return {"frozenDirectory": str(frozen), "runtimeProvenance": str(path), "runtimeNotices": str(notices),
            "guiAcceptance": "unverified", "codeSigning": "not_performed"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--input-lock", required=True, type=Path)
    parser.add_argument("--input-lock-sha256", required=True)
    parser.add_argument("--wheelhouse", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(build(args.source_commit, args.input_lock, args.input_lock_sha256, args.wheelhouse, args.output), indent=2))


if __name__ == "__main__":
    main()
