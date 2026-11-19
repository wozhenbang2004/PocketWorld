"""Reassemble one original candidate after an independent publisher has actually signed and returned ALL seven files.
No compilation, signing/provenance generation, key access, download, installation, deployment, publishing or GUI claim.
"""
import argparse
from contextlib import ExitStack, contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("pw_original_signing_inputs", HERE / "prepare_signing_inputs.py")
pre = importlib.util.module_from_spec(spec); spec.loader.exec_module(pre)
build, pack, rt = pre.candidate, pre.pack, pre.rt
must = pre.must
RECEIPT = "signing-provenance.json"
RUNTIME_META = "runtime-build-provenance.json"
CUA_META, AGENT_META = "driver/cua-build-provenance.json", "driver/agent-desktop-build-provenance.json"
CUA_NOTICE = "licenses/cua-driver-THIRD-PARTY-NOTICES.md"
FIXED = {RUNTIME_META, CUA_META, AGENT_META, CUA_NOTICE, "licenses/agent-desktop-Apache-2.0.txt", "licenses/cua-driver-MIT.txt"}


def sha_stream(stream, out=None):
    stream.seek(0)
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(262144), b""):
        h.update(block)
        if out is not None: out.write(block)
    return h.hexdigest()


def closed(folder, required, optional=()):
    names = set()
    with os.scandir(folder) as contents:
        for child in contents:
            must(child.name in required or child.name in optional, "Unexpected name in publisher return; use only the selected return packet")
            names.add(child.name)
    must(required <= names, "Publisher return packet is incomplete")
    return names


@contextmanager
def originals(args, request, files):
    # Parent handles prevent path replacement while the platform's existing Authenticode verifier
    # resolves these names. Keep each original file handle through its re-read or copy as well.
    with ExitStack() as held:
        root = build.local(args.signed_return, directory=True)
        for folder in (root, root/"app", root/"driver", root/"tools"):
            held.enter_context(files.directory(str(folder)))
        names = closed(root, {"app", "driver", "tools", RECEIPT}, {"signing-request.json"})
        for group in ("app", "driver", "tools"):
            closed(root/group, {n.split("/", 1)[1] for n in pre.TARGETS if n.startswith(group+"/")})
        if "signing-request.json" in names:
            file, _ = held.enter_context(pre.locked(root/"signing-request.json", 65536))
            must(pre.obj(file.read(65537)) == request, "The optional pre-sign request is not this original set; it is never signing evidence")
        src, size = held.enter_context(pre.locked(root/RECEIPT, 65536))
        raw = src.read(65537)
        must(len(raw) == size and hashlib.sha256(raw).hexdigest() == args.receipt_sha256, "Publisher receipt differs from its separately approved SHA-256")
        note = pre.obj(raw)
        must(set(note) == {"format", "sourceCommit", "complete", "artifacts"} and note["format"] == "pocketworld.windows-signing.v1"
             and note["sourceCommit"] == args.source_commit and note["complete"] is True and isinstance(note["artifacts"], dict)
             and set(note["artifacts"]) == set(pre.TARGETS), "Expected actual complete publisher receipt for exactly these seven files, not a pre-sign request")
        unsigned = {r["path"]: r["unsignedSha256"] for r in request["inputs"]}
        streams = {}; accepted = {}
        for name in pre.TARGETS:
            row = note["artifacts"][name]
            must(isinstance(row, dict) and set(row) == {"unsignedSha256", "finalSha256", "signatureStatus", "signerThumbprint"}
                 and row["unsignedSha256"] == unsigned[name] and pre.hash_ok(row["finalSha256"]) and row["finalSha256"] != unsigned[name]
                 and row["signatureStatus"] == "Valid" and isinstance(row["signerThumbprint"], str)
                 and row["signerThumbprint"].upper() == args.publisher_thumbprint.upper(), "Publisher receipt does not bind original and final identity for " + name)
            file, _ = held.enter_context(pre.locked(root/name, 1048576 if name.startswith("tools/") else pre.LIMIT))
            must(sha_stream(file) == row["finalSha256"], "Final member differs from independently approved publisher receipt: " + name)
            pack.verify_authenticode(root/name, args.publisher_thumbprint)
            streams[name] = file; accepted[name] = row["finalSha256"]
        yield raw, note, streams, accepted


def original_materials(args, request, stage):
    """Second open re-verifies the externally pinned archive under ONE handle when checking/copying all unchanged bytes."""
    archive = args.candidate.parent/("PocketWorld-Companion-" + request["version"] + "-dev.zip")
    with pre.locked(archive, 2*1024**3) as (src, _):
        must(sha_stream(src) == args.expected_zip_sha256, "Original candidate changed before materialization")
        src.seek(0)
        with zipfile.ZipFile(src) as z:
            items = pre.archive_map(z)
            manifest = items["bundle-manifest.json"]
            must(0 < manifest.file_size <= 8*1024**2, "Original manifest exceeds bound")
            rows = pre.obj(z.read(manifest))["files"]
            hashes = {row["path"]: row["sha256"] for row in rows}
            must(len(hashes) == len(rows) and set(hashes) == set(items)-{"bundle-manifest.json"}
                 and FIXED.union(pre.TARGETS) <= set(hashes), "Original candidate inventory incomplete")
            for name, digest in hashes.items():
                allowed = name in FIXED or name in pre.TARGETS or name.startswith(("app/", "licenses/runtime/"))
                must(allowed and pre.hash_ok(digest), "Not an original supported candidate member")
                # The seven original hashes were individually proved by prepare(check) and tied to this ZIP SHA.
                # Never reintroduce their unsigned bytes into the frozen tree or touch the original candidate.
                if name in pre.TARGETS: continue
                if stage is None:
                    pre.digest_entry(z, items[name], digest)
                else:
                    target = stage/name; target.parent.mkdir(parents=True, exist_ok=True)
                    with target.open("xb") as out: pre.digest_entry(z, items[name], digest, out)
            return hashes


def final_inventory(archive, request, expected, note, publisher, kit, result):
    with pre.locked(archive, 2*1024**3) as (src, size):
        must(size == result["bytes"] and sha_stream(src) == result["sha256"], "Final ZIP no longer matches its own assembly receipt")
        src.seek(0)
        with zipfile.ZipFile(src) as z:
            idx = pre.archive_map(z)
            must(set(idx) == set(expected) | {RECEIPT, "bundle-manifest.json"}, "Final archive changed its original member set")
            # The graphical preview can read 8 MiB, but WindowsInstalledContext opens at most 4 MiB when finding the actual desk.
            must(0 < idx["bundle-manifest.json"].file_size <= 4*1024**2 and 0 < idx[RECEIPT].file_size <= 65536,
                 "Final inventory or signing record exceeds bound")
            m = pre.obj(z.read(idx["bundle-manifest.json"]))
            final_note = z.read(idx[RECEIPT])
            must(pre.obj(final_note) == note and m["sourceCommit"] == request["sourceCommit"] and m["version"] == request["version"]
                 and m["platform"] == "windows-x64" and m["codeSigning"] == "verified_final_bytes" and m["guiAcceptance"] == "unverified",
                 "Final package does not carry the approved signing record and original product identity")
            actual = {r["path"]: r["sha256"] for r in m["files"]}
            must(len(actual) == len(m["files"]) and actual == {**expected, RECEIPT: hashlib.sha256(final_note).hexdigest()},
                 "Final bundle changed an unrelated runtime, notice, script or build record")
            for name, digest in actual.items(): pre.digest_entry(z, idx[name], digest)
    with pre.locked(kit/"Install-Companion.ps1", 1048576) as (entry, entry_size):
        must(sha_stream(entry) == expected["tools/Install-Companion.ps1"], "Standalone first-install script differs from approved signed member")
        pack.verify_authenticode(kit/"Install-Companion.ps1", publisher)
    with pre.locked(kit/"first-install.json", 16384) as (src, _):
        side = pre.obj(src.read(16385))
        must(side["sourceCommit"] == request["sourceCommit"] and side["expectedSignerThumbprint"].upper() == publisher.upper()
             and side["zip"] == {"name": archive.name, "bytes": result["bytes"], "sha256": result["sha256"]}
             and side["installer"] == {"name":"Install-Companion.ps1", "bytes":entry_size, "sha256":expected["tools/Install-Companion.ps1"]}
             and side["independentOwnerVerificationRequired"] is True,
             "Final handoff no longer names this archive, installer and approved publisher")


def run(args):
    rt.require_windows_x64()
    must(pre.hash_ok(args.receipt_sha256) and bool(re.fullmatch(r"[A-Fa-f0-9]{40}", args.publisher_thumbprint)),
         "Independently approved signing-receipt SHA-256 and publisher certificate thumbprint are both required")
    out = args.output
    must((args.action == "assemble") == (out is not None), "Only explicit assemble takes a new local output directory")
    root = build.local(args.signed_return, directory=True)
    protect = (args.candidate.parent, root, build.PRODUCT)
    must(all(build.distinct(a,b) for i,a in enumerate(protect) for b in protect[i+1:]), "Keep the selected source, publisher response and original candidate separate")
    if out is not None:
        build.local(out.parent, directory=True); pack.safe_name(out.name)
        must(out.is_absolute() and not out.exists() and not out.is_symlink() and all(build.distinct(out,p) for p in protect),
             "Choose a new, separate local assembly directory; existing outputs are never replaced")
    env = build.environment(args.candidate.parent)
    rt.clean_source(build.PRODUCT, args.source_commit, env)
    # Load only the original repository's read-only directory-handle implementation under the checked commit.
    sys.path.insert(0, str(build.PRODUCT/"companion"))
    try: import platform_windows, platform_backend
    finally: sys.path.pop(0)
    for module in (platform_windows, platform_backend):
        must(Path(module.__file__).resolve() == build.PRODUCT/"companion"/(module.__name__+".py"), "Original source-tree Windows reader required")
    files = platform_windows.WindowsBackend()
    request = pre.prepare(argparse.Namespace(action="check", candidate=args.candidate, expected_zip_sha256=args.expected_zip_sha256,
                                              source_commit=args.source_commit, output=None))
    with originals(args, request, files) as (raw, note, returned, verified), ExitStack() as stack:
        report = {"sourceCommit": args.source_commit, "version": request["version"], "candidateZipSha256": args.expected_zip_sha256,
                  "externalPublisherReceiptSha256": args.receipt_sha256, "approvedPublisher": args.publisher_thumbprint.upper(),
                  "signaturesChecked": len(verified), "assembled": False, "signedHere": False, "published": False,
                  "installed": False, "guiAcceptance": "unverified"}
        if out is None:
            original_materials(args, request, None)
            return report
        stack.enter_context(files.directory(str(out.parent)))
        tmp = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix=".pw-final-", dir=out.parent)))
        stage = tmp/"original"; stage.mkdir()
        product = tmp/"delivery"; product.mkdir()
        expected = original_materials(args, request, stage)
        for name, stream in returned.items():
            p = stage/name; p.parent.mkdir(parents=True, exist_ok=True)
            with p.open("xb") as dest: must(sha_stream(stream, dest) == verified[name], "Signed return changed before staging")
        with (stage/RECEIPT).open("xb") as dest: dest.write(raw)  # verbatim external receipt, not a locally fabricated attestation
        version = request["version"]; archive = product/("PocketWorld-Companion-"+version+"-windows-x64.zip"); kit = product/"FirstInstallFiles"
        result = pack.bundle(stage/"app", stage/pre.CUA, verified[pre.CUA], args.source_commit, version, archive, stage/CUA_META,
                    agent_desktop_binary=stage/pre.AGENT, agent_desktop_provenance=stage/AGENT_META,
                    runtime_provenance=stage/RUNTIME_META, runtime_notices=stage/"licenses/runtime", driver_notices=stage/CUA_NOTICE,
                    signing_provenance=stage/RECEIPT, management_dir=stage/"tools", first_install_handoff=kit)
        expected.update(verified)
        final_inventory(archive, request, expected, note, args.publisher_thumbprint, kit, result)
        rt.clean_source(build.PRODUCT, args.source_commit, env)
        product.rename(out)  # native Windows absent-only directory rename, only after the entire delivery is checked
        report.update(assembled=True, zip=archive.name, zipSha256=result["sha256"], zipBytes=result["bytes"], firstInstall="FirstInstallFiles")
        return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("check", "assemble"))
    for name in ("candidate", "signed-return"): p.add_argument("--"+name, type=Path, required=True)
    for name in ("expected-zip-sha256", "source-commit", "receipt-sha256", "publisher-thumbprint"):
        p.add_argument("--"+name, required=True)
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    try: print(json.dumps(run(args), indent=2)); return 0
    except Exception as exc:
        print("Signed candidate not confirmed; no signing, publication or install performed. " + str(exc), file=sys.stderr); return 1


if __name__ == "__main__": raise SystemExit(main())
