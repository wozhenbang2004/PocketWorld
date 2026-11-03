"""Build an integrity-pinned user bundle from an already-frozen Windows runtime.

No download, dependency installation, credential generation, deployment, signing,
or public release. A bundle hash proves bytes, NOT source/binary correspondence.
Input and staging paths still require a builder protected from concurrent writers;
digest checks do not make path-based copies or signature verification atomic.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile
import zipfile

CUA_COMMIT = "f595a4fb6f524e555e5e422aab81c3457fe4a713"
CUA_VERSION = "0.38.0"
CUA_NOTICES_SHA256 = "5860ee54b73d3d84f8af8a3c3fc996739755297d91e259314416378548ee8f6d"
MANAGEMENT_SCRIPTS = ("Install-Companion.ps1", "Manage-Companion.ps1", "Launch-Companion.ps1", "Companion-InstallState.ps1")


def file_hash(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(262144):
            value.update(chunk)
    return value.hexdigest()


def safe_name(name):
    path = PurePosixPath(name)
    parts = name.split("/")
    if (not name or "\\" in name or ":" in name or path.is_absolute()
            or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF or c in '<>"|?*' for c in name)
            or any(part in {"", ".", ".."} or part[-1:] in {".", " "}
                   or re.fullmatch(r"(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", part) for part in parts)):
        raise ValueError("Bundle member is not a safe Windows-relative path")
    if any(part.lower() in {"companion-token.txt", "operations.sqlite3", "identity-key", ".aws", ".ssh", ".codex", ".claude"}
           or part.lower().startswith(".env") or part.lower().endswith((".key", ".pfx", ".p12")) for part in parts):
        raise ValueError("Private runtime state/credentials must never be bundled")
    return name


def tree(root):
    result, names = [], set()
    for directory, directories, files in os.walk(root, followlinks=False):
        for name in directories + files:
            path = Path(directory) / name
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("Bundle input must not contain links/reparse points")
            relative = safe_name(path.relative_to(root).as_posix())
            folded = relative.casefold()
            if folded in names:
                raise ValueError("Case-colliding Windows bundle members")
            names.add(folded)
            if path.is_file():
                result.append((relative, path))
    return sorted(result)


def bundle(frozen_dir, driver_binary, driver_sha256, source_commit, version, output, driver_provenance,
           *, runtime_provenance=None, runtime_notices=None, driver_notices=None,
           signing_provenance=None, management_dir=None, allow_unsigned_development=False):
    frozen_dir, driver_binary, output = Path(frozen_dir), Path(driver_binary), Path(output)
    if type(allow_unsigned_development) is not bool:
        raise ValueError("Development mode must be an explicit boolean")
    if not allow_unsigned_development and os.name != "nt":
        raise ValueError("Release bundles require the Windows signature-verification builder")
    if not re.fullmatch(r"[a-f0-9]{64}", driver_sha256) or file_hash(driver_binary) != driver_sha256:
        raise ValueError("Driver artifact SHA-256 mismatch")
    provenance = json.loads(Path(driver_provenance).read_text(encoding="utf-8"))
    if (provenance.get("sourceCommit") != CUA_COMMIT or provenance.get("sourceClean") is not True
            or not re.fullmatch(r"[a-f0-9]{64}", provenance.get("artifactSha256", "")) or provenance.get("driverVersion") != CUA_VERSION
            or provenance.get("platform") != "windows-x64" or provenance.get("spacesIncluded") is not False
            or provenance.get("perceptionIncluded") is not False or provenance.get("features") != []
            or provenance.get("releaseStatus") != "prerelease" or not re.fullmatch(r"[a-f0-9]{64}", provenance.get("cargoLockSha256", ""))):
        raise ValueError("Driver source-build provenance differs from pinned source/artifact/feature boundary")
    if not re.fullmatch(r"[a-f0-9]{40}", source_commit) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", version):
        raise ValueError("Exact product commit and safe bundle version required")
    if not (frozen_dir / "PocketWorld-Companion.exe").is_file():
        raise ValueError("Already-frozen user runtime missing; the user bundle never asks for a Python venv")
    if output.exists():
        raise ValueError("Refusing to overwrite an existing build artifact")
    source = Path(__file__).resolve().parents[3]
    management_dir = Path(management_dir) if management_dir else Path(__file__).parent
    notices = Path(driver_notices) if driver_notices else source / "licenses/cua-driver-THIRD-PARTY-NOTICES.md"
    if not notices.is_file() or file_hash(notices) != CUA_NOTICES_SHA256:
        raise ValueError("Exact pinned Cua Windows third-party notices are required")
    if runtime_provenance is None or runtime_notices is None:
        raise ValueError("Runtime build provenance and license materials must be supplied")
    runtime = json.loads(Path(runtime_provenance).read_text(encoding="utf-8"))
    if (runtime.get("format") != "pocketworld.windows-runtime-build.v1" or runtime.get("sourceCommit") != source_commit
            or runtime.get("platform") != "windows-x64"
            or any(not isinstance(runtime.get(k), str) or not runtime[k] for k in ("pythonVersion", "pyinstallerVersion", "cryptographyVersion", "toolchainDescription"))
            or not re.fullmatch(r"[a-f0-9]{64}", runtime.get("inputLockSha256", ""))):
        raise ValueError("Complete fixed Windows runtime build inputs are required")
    frozen_files = tree(frozen_dir)
    if set(runtime.get("unsignedFiles", {})) != {name for name, _ in frozen_files}:
        raise ValueError("Runtime provenance must cover every frozen file exactly")
    license_files = tree(Path(runtime_notices))
    if not license_files or runtime.get("licenseFiles") != {name: file_hash(path) for name, path in license_files}:
        raise ValueError("Runtime license files differ from recorded build materials")
    signing = json.loads(Path(signing_provenance).read_text(encoding="utf-8")) if signing_provenance else None
    if signing is not None and (signing.get("format") != "pocketworld.windows-signing.v1" or signing.get("sourceCommit") != source_commit or signing.get("complete") is not True):
        raise ValueError("Incomplete or mismatched signing provenance")
    signed = signing.get("artifacts", {}) if signing else {}
    required_signed = {"app/PocketWorld-Companion.exe", "driver/cua-driver.exe"} | {"tools/"+name for name in MANAGEMENT_SCRIPTS}
    if not allow_unsigned_development and not required_signed.issubset(signed):
        raise ValueError("Final runtime and management signatures must precede bundle hashing")
    def admitted_hash(name, path, unsigned_hash):
        actual = file_hash(path)
        record = signed.get(name)
        if record is None:
            if actual != unsigned_hash or (name in required_signed and not allow_unsigned_development):
                raise ValueError("Unsigned build identity changed without signing evidence")
            return actual
        if (record.get("unsignedSha256") != unsigned_hash or record.get("finalSha256") != actual
                or record.get("signatureStatus") != "Valid" or not record.get("signerThumbprint")):
            raise ValueError("Pre/post-sign artifact identity does not match")
        return actual
    admitted = {}
    for name, path in frozen_files:
        admitted["app/"+name] = admitted_hash("app/"+name, path, runtime["unsignedFiles"][name])
    admitted["driver/cua-driver.exe"] = admitted_hash("driver/cua-driver.exe", driver_binary, provenance["artifactSha256"])
    if admitted["driver/cua-driver.exe"] != driver_sha256:
        raise ValueError("Driver admitted identity differs from supplied SHA-256")
    for name in MANAGEMENT_SCRIPTS:
        path = management_dir / name
        if not path.is_file(): raise ValueError("Management entry missing from package")
        unsigned = runtime.get("managementUnsignedFiles", {}).get(name)
        if not re.fullmatch(r"[a-f0-9]{64}", unsigned or ""): raise ValueError("Management source/build hash missing")
        admitted["tools/"+name] = admitted_hash("tools/"+name, path, unsigned)
    signers = {name: signed[name]["signerThumbprint"] for name in admitted if name in signed}
    admitted.update({"licenses/runtime/"+name: runtime["licenseFiles"][name] for name, _ in license_files})
    admitted["licenses/cua-driver-THIRD-PARTY-NOTICES.md"] = CUA_NOTICES_SHA256
    admitted["licenses/cua-driver-MIT.txt"] = file_hash(source / "licenses/cua-driver-MIT.txt")
    with tempfile.TemporaryDirectory(prefix="pocketworld-bundle-") as temporary:
        root = Path(temporary)
        def write_metadata(name, value):
            raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
            (root / name).write_bytes(raw)
            admitted[name] = hashlib.sha256(raw).hexdigest()
        # Copy only the admitted members. Paths can change after the input checks;
        # staged and archived bytes must still match the captured identities.
        for name, path in frozen_files:
            target = root / "app" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
        (root / "driver").mkdir()
        shutil.copyfile(driver_binary, root / "driver" / "cua-driver.exe")
        write_metadata("driver/cua-build-provenance.json", provenance)
        (root / "licenses").mkdir()
        shutil.copyfile(source / "licenses" / "cua-driver-MIT.txt", root / "licenses" / "cua-driver-MIT.txt")
        shutil.copyfile(notices, root / "licenses" / "cua-driver-THIRD-PARTY-NOTICES.md")
        for name, path in license_files:
            target = root / "licenses" / "runtime" / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(path, target)
        (root / "tools").mkdir()
        for name in MANAGEMENT_SCRIPTS: shutil.copyfile(management_dir / name, root / "tools" / name)
        write_metadata("runtime-build-provenance.json", runtime)
        if signing is not None: write_metadata("signing-provenance.json", signing)
        staged_files = tree(root)
        if {name for name, _ in staged_files} != set(admitted):
            raise ValueError("Staged bundle members differ from admitted inputs")
        entries = []
        for name, path in staged_files:
            if file_hash(path) != admitted[name]:
                raise ValueError("Staged bundle bytes differ from admitted identity: " + name)
            if name in signers and not allow_unsigned_development:
                verify_authenticode(path, signers[name])
            entries.append({"path": name, "sha256": admitted[name], "bytes": path.stat().st_size})
        manifest = {"schemaVersion": 1, "product": "PocketWorld-Companion", "version": version,
                    "sourceCommit": source_commit, "platform": "windows-x64", "protocol": "device-v1",
                    "cua": {"version": CUA_VERSION, "sourceCommit": CUA_COMMIT, "sha256": driver_sha256,
                            "releaseStatus": "prerelease", "spacesIncluded": False, "perceptionIncluded": False},
                    "credentialsIncluded": False, "guiAcceptance": "unverified", "codeSigning": "unsigned_development" if allow_unsigned_development else "verified_final_bytes",
                    "installationSchemaVersion": 2, "runtimeProvenanceSha256": admitted["runtime-build-provenance.json"],
                    "files": entries}
        write_metadata("bundle-manifest.json", manifest)
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "x", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            archive_files = tree(root)
            if {name for name, _ in archive_files} != set(admitted):
                raise ValueError("Archive members differ from admitted inputs")
            sizes = {entry["path"]: entry["bytes"] for entry in entries}
            for name, path in archive_files:
                raw = path.read_bytes()
                if (hashlib.sha256(raw).hexdigest() != admitted[name]
                        or (name in sizes and len(raw) != sizes[name])):
                    raise ValueError("Archive bytes differ from admitted identity: " + name)
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type, info.external_attr = zipfile.ZIP_DEFLATED, 0o100600 << 16
                archive.writestr(info, raw)
    return {"path": str(output), "sha256": file_hash(output), "bytes": output.stat().st_size,
            "sourceCommit": source_commit, "guiAcceptance": "unverified"}



def verify_authenticode(path, thumbprint):
    """Use the platform verifier; never treat a caller's status string as signature proof."""
    import subprocess
    shell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    environment = os.environ.copy(); environment["PW_VERIFY_FILE"] = str(Path(path).absolute()); environment["PW_VERIFY_SIGNER"] = thumbprint
    command = "$s=Get-AuthenticodeSignature -LiteralPath $env:PW_VERIFY_FILE; if($s.Status -ne 'Valid' -or $s.SignerCertificate.Thumbprint -ne $env:PW_VERIFY_SIGNER){exit 1}"
    subprocess.run([str(shell), "-NoProfile", "-NonInteractive", "-Command", command], env=environment, check=True, timeout=30, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frozen-dir", type=Path, required=True)
    parser.add_argument("--driver-binary", type=Path, required=True)
    parser.add_argument("--driver-sha256", required=True)
    parser.add_argument("--driver-provenance", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-provenance", type=Path, required=True)
    parser.add_argument("--runtime-notices", type=Path, required=True)
    parser.add_argument("--driver-notices", type=Path)
    parser.add_argument("--signing-provenance", type=Path)
    parser.add_argument("--management-dir", type=Path)
    parser.add_argument("--allow-unsigned-development", action="store_true")
    args = parser.parse_args()
    print(json.dumps(bundle(args.frozen_dir, args.driver_binary, args.driver_sha256, args.source_commit, args.version, args.output, args.driver_provenance,
        runtime_provenance=args.runtime_provenance, runtime_notices=args.runtime_notices, driver_notices=args.driver_notices,
        signing_provenance=args.signing_provenance, management_dir=args.management_dir,
        allow_unsigned_development=args.allow_unsigned_development)))


if __name__ == "__main__":
    main()
