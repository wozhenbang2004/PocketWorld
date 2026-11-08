#!/usr/bin/env python3
"""Local Windows adapter for the existing APK update tools; never builds or uploads.

Common options may precede or follow the tool name. Remaining arguments are the
original tool's arguments. All temporary files require an explicit --temp-dir.
verify-envelope consumes an already downloaded file, using the App's configured
SPKI pin and production UpdateManifest verifier; it never downloads a manifest.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).resolve()
TOOLS = {"delta": "apk-delta", "preflight": "inspect-update-artifacts",
         "prepare": "prepare-update-release", "sign": "sign-update-manifest"}
sys.dont_write_bytecode = True


class SubprocessAdapter:
    def __init__(self, runtime):
        self.runtime = runtime

    def __getattr__(self, name):
        return getattr(subprocess, name)

    def run(self, command, *args, **kwargs):
        return subprocess.run(self.runtime.command(command), *args, **kwargs)


class Runtime:
    def __init__(self, repo, temp_dir, java_home=None, build_tools=None, openssl=None):
        self.repo = Path(repo).resolve()
        self.temp_dir = Path(temp_dir).resolve()
        self.java_home = java_home or os.environ.get("JAVA_HOME")
        self.build_tools = Path(build_tools).resolve() if build_tools else None
        self.openssl = str(Path(openssl).resolve()) if openssl else None
        self.windows = os.name == "nt"

    @contextlib.contextmanager
    def environment(self):
        if not (self.repo / "tools/apk-delta.py").is_file():
            raise ValueError("--repo must contain the existing PocketWorld update tools")
        self.temp_dir.mkdir(parents=True, exist_ok=True)
        names = ("TEMP", "TMP", "TMPDIR", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "PATH")
        saved = {name: os.environ.get(name) for name in names}
        previous_temp = tempfile.tempdir
        try:
            for name in ("TEMP", "TMP", "TMPDIR"):
                os.environ[name] = str(self.temp_dir)
            tempfile.tempdir = str(self.temp_dir)
            temporary_option = '-Djava.io.tmpdir="' + self.temp_dir.as_posix() + '"'
            options = re.findall(r'''(?:[^\s"']+|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')+''', os.environ.get("JAVA_TOOL_OPTIONS", ""))
            options = [option for option in options if not option.startswith("-Djava.io.tmpdir=")]
            os.environ["JAVA_TOOL_OPTIONS"] = " ".join([*options, temporary_option])
            if self.java_home:
                os.environ["JAVA_HOME"] = str(Path(self.java_home).resolve())
                os.environ["PATH"] = str(Path(self.java_home).resolve() / "bin") + os.pathsep + os.environ.get("PATH", "")
            yield
        finally:
            tempfile.tempdir = previous_temp
            for name, value in saved.items():
                if value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = value

    def java_tools(self, java_home=None):
        home = java_home or self.java_home
        suffix = ".exe" if self.windows else ""
        paths = [Path(home) / "bin" / (name + suffix) if home else
                 Path(shutil.which(name) or "") for name in ("java", "javac")]
        if not all(path.is_file() for path in paths):
            raise ValueError("An existing Java 17+ JDK is required (--java-home or JAVA_HOME)")
        return tuple(str(path.resolve()) for path in paths)

    def command(self, command):
        command = list(command)
        first = Path(command[0])
        if first.name in ("aapt", "zipalign", "apksigner") and self.windows:
            if first.name in ("aapt", "zipalign"):
                executable = first.with_suffix(".exe")
                if not executable.is_file():
                    raise ValueError("Existing official SDK " + executable.name + " is missing")
                command[0] = str(executable)
            else:
                # Official apksigner.bat delegates to this same SDK JAR. Invoke
                # it directly so APK paths never become cmd.exe shell text.
                jar = first.parent / "lib/apksigner.jar"
                if not jar.is_file():
                    raise ValueError("Existing official SDK lib/apksigner.jar is missing")
                command = [self.java_tools()[0], "-jar", str(jar)] + command[1:]
        elif command[0] == "openssl" and self.openssl:
            if not Path(self.openssl).is_file():
                raise ValueError("--openssl must identify an existing OpenSSL executable")
            command[0] = self.openssl
        elif len(command) >= 3 and Path(command[2]).resolve() == self.repo / "tools/apk-delta.py":
            common = ["--repo", str(self.repo), "--temp-dir", str(self.temp_dir)]
            if self.java_home:
                common += ["--java-home", str(self.java_home)]
            command = [sys.executable, "-B", str(SCRIPT)] + common + ["delta"] + command[3:]
        return command

    def existing(self, name):
        path = self.repo / "tools" / (name + ".py")
        spec = importlib.util.spec_from_file_location("windows_release_" + name.replace("-", "_").replace("/", "_"), path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.subprocess = SubprocessAdapter(self)
        if name == "apk-delta":
            module.java_tools = self.java_tools
        elif name == "prepare-update-release":
            module.PREFLIGHT.subprocess = SubprocessAdapter(self)
        return module


@contextlib.contextmanager
def arguments(path, forwarded):
    previous = sys.argv
    try:
        sys.argv = [str(path)] + list(forwarded)
        yield
    finally:
        sys.argv = previous


def call_existing(runtime, tool, forwarded):
    module = runtime.existing(TOOLS[tool])
    forwarded = list(forwarded)
    if tool in ("preflight", "prepare"):
        if runtime.build_tools is None:
            raise ValueError("--build-tools is required for APK signature/identity preflight")
        forwarded += ["--build-tools", str(runtime.build_tools)]
        if runtime.java_home:
            forwarded += ["--java-home", str(runtime.java_home)]
        if tool == "preflight" and runtime.windows:
            # The original CLI checks Unix filenames; the unchanged inspect()
            # still owns all artifact, signer and exact-reconstruction checks.
            parser = argparse.ArgumentParser(description=module.__doc__)
            parser.add_argument("--old", type=Path, required=True)
            parser.add_argument("--new", type=Path, required=True)
            parser.add_argument("--patch", type=Path)
            parser.add_argument("--output", type=Path)
            parser.add_argument("--java-home")
            parser.add_argument("--build-tools", type=Path, required=True)
            opts = parser.parse_args(forwarded)
            result = module.inspect(opts.old.resolve(), opts.new.resolve(), opts.build_tools.resolve(),
                                    opts.patch.resolve() if opts.patch else None, opts.java_home)
            write_json(result, opts.output)
            return 0
    with arguments(module.__file__, forwarded):
        return module.main() or 0


def write_json(value, output=None):
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    if output is None:
        print(text, end="")
    else:
        with Path(output).open("x", encoding="utf-8") as stream:
            stream.write(text)


MANIFEST_PROBE = r'''import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import dev.pocketengine.features.updates.UpdateManifest;
import org.json.JSONObject;
public final class WindowsReleaseManifest {
  public static void main(String[] args) {
    try {
      UpdateManifest value = UpdateManifest.verify(Files.readAllBytes(Path.of(args[0])),
          Files.readString(Path.of(args[1]), StandardCharsets.US_ASCII).trim(),
          "dev.pocketengine", Long.parseLong(args[2]));
      System.out.println(new JSONObject().put("signatureVerified", true)
          .put("packageName", value.packageName).put("versionCode", value.versionCode)
          .put("versionName", value.versionName).put("expiresAt", value.expiresAt)
          .put("apk", new JSONObject().put("url", value.apk.url)
              .put("sha256", value.apk.sha256).put("bytes", value.apk.bytes)));
    } catch (Exception rejected) {
      System.err.println("Production UpdateManifest verifier rejected the envelope.");
      System.exit(2);
    }
  }
}
'''


def configured_pin(repo):
    values = []
    for line in (repo / "gradle.properties").read_text(encoding="utf-8-sig").splitlines():
        name, separator, value = line.partition("=")
        if separator and name.strip() == "pocketworldUpdatePublicKey":
            values.append(value.strip())
    if len(values) != 1 or not values[0]:
        raise ValueError("Configure exactly one App manifest public-key pin in gradle.properties")
    decoded = base64.b64decode(values[0], validate=True)
    if not 1 <= len(decoded) <= 4096:
        raise ValueError("Configured App SPKI pin exceeds the production size limit")
    return values[0], hashlib.sha256(decoded).hexdigest()


def baseline_clock(envelope, now, allow_expired):
    """Untrusted timing selects a verifier clock, never bypasses signature checks."""
    if not allow_expired:
        return now, True, False
    wrapper = json.loads(envelope.decode("utf-8"))
    if not isinstance(wrapper, dict) or not isinstance(wrapper.get("payload"), str):
        raise ValueError("Historical baseline needs a bounded signed-envelope payload")
    raw = base64.b64decode(wrapper["payload"], validate=True)
    if len(raw) > 64 * 1024:
        raise ValueError("Historical baseline payload exceeds the production size limit")
    payload = json.loads(raw.decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Historical baseline payload must be an object")
    issued, expires = payload.get("issuedAt"), payload.get("expiresAt")
    if type(issued) is not int or type(expires) is not int or not 1 <= issued < expires <= 2**63 - 1 \
            or expires - issued > 7 * 86400000 or issued > now + 300000:
        raise ValueError("Historical baseline has invalid or future release timing")
    if expires <= now:
        return expires - 1, False, True
    return now, True, False


def verify_envelope(runtime, forwarded):
    parser = argparse.ArgumentParser(description="Verify a local signed envelope with the App's production verifier and configured pin")
    parser.add_argument("--envelope", type=Path, required=True)
    parser.add_argument("--classpath", required=True, help="Existing locked org.json JAR classpath; never downloads dependencies")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--allow-expired-baseline", action="store_true",
                        help="Accept a correctly signed expired historical baseline only; never a live update")
    args = parser.parse_args(forwarded)
    if args.output is not None and args.output.exists():
        raise ValueError("Output already exists")
    if not args.envelope.is_file() or not 0 < args.envelope.stat().st_size <= 96 * 1024:
        raise ValueError("Envelope exceeds the production size limit or is not a regular file")
    dependencies = [Path(part).resolve() for part in args.classpath.split(os.pathsep)]
    if not all(path.is_file() and path.suffix.lower() == ".jar" for path in dependencies):
        raise ValueError("--classpath must contain existing locked dependency JARs")
    classpath = os.pathsep.join(map(str, dependencies))
    pin, pin_digest = configured_pin(runtime.repo)
    java, javac = runtime.java_tools()
    envelope_bytes = args.envelope.read_bytes()
    if not 0 < len(envelope_bytes) <= 96 * 1024:
        raise ValueError("Envelope exceeds the production size limit")
    verification_now, valid_now, historical = baseline_clock(envelope_bytes, int(time.time() * 1000), args.allow_expired_baseline)
    with tempfile.TemporaryDirectory(prefix="pocketworld-verify-manifest-", dir=runtime.temp_dir) as temporary:
        folder = Path(temporary)
        source, envelope, key = folder / "WindowsReleaseManifest.java", folder / "envelope.json", folder / "pin.txt"
        source.write_text(MANIFEST_PROBE, encoding="utf-8")
        envelope.write_bytes(envelope_bytes)
        key.write_text(pin, encoding="ascii")
        subprocess.run([javac, "--release", "17", "-cp", classpath, "-d", str(folder),
                        str(runtime.repo / "app/src/main/java/dev/pocketengine/features/updates/UpdateManifest.java"), str(source)],
                       stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=120)
        result = subprocess.run([java, "-Xmx64m", "-Djava.io.tmpdir=" + str(folder), "-cp", str(folder) + os.pathsep + classpath,
                                 "WindowsReleaseManifest", str(envelope), str(key), str(verification_now)],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True, timeout=30)
        if len(result.stdout) > 16 * 1024:
            raise ValueError("Unexpected production manifest-verifier output")
        evidence = json.loads(result.stdout)
        if evidence.get("signatureVerified") is not True or evidence.get("packageName") != "dev.pocketengine":
            raise ValueError("Production manifest verification did not succeed")
        evidence.update({"pinSpkiSha256": pin_digest, "networkAccessed": False,
                         "validNow": valid_now, "historicalBaselineOnly": historical})
        write_json(evidence, args.output)
    return 0


def verify_apk(runtime, forwarded):
    parser = argparse.ArgumentParser(description="Verify an existing local APK through the original complete packaged-app verifier")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="New local verification directory; refuses overwrite")
    parser.add_argument("--expected-signer", required=True, help="The persistent Android signing-certificate SHA-256 (64 hex characters)")
    args = parser.parse_args(forwarded)
    if re.fullmatch(r"[A-Fa-f0-9]{64}", args.expected_signer) is None:
        raise ValueError("--expected-signer must be exactly 64 hexadecimal characters")
    expected = args.expected_signer.lower()
    if runtime.build_tools is None:
        raise ValueError("--build-tools is required for complete APK verification")
    apk, out = args.apk.resolve(), args.out.resolve()
    if not apk.is_file() or out.exists():
        raise ValueError("APK must exist and --out must be a new directory")
    module = runtime.existing("ci/verify-web-ui")
    preflight = runtime.existing("inspect-update-artifacts")
    original_evidence = module.run_evidence
    signers = []

    def run_evidence(command, destination):
        command = list(command)
        name = Path(command[0]).name
        if name in ("apksigner", "zipalign", "aapt"):
            command[0] = str(runtime.build_tools / name)
        original_evidence(runtime.command(command), destination)
        if Path(destination).name == "signature.txt":
            actual = preflight.parse_signers(Path(destination).read_text())
            signers[:] = actual["signerCertificateSha256"]
            if set(signers) != {expected}:
                raise ValueError("APK active signer set differs from the expected persistent certificate")

    def current_commit():
        # Local verification uses the explicit checkout, never a stale CI SHA.
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=runtime.repo, text=True).strip()
        if re.fullmatch(r"[a-f0-9]{40}", commit) is None:
            raise ValueError("The explicit checkout must have a complete source commit SHA")
        return commit

    module.run_evidence = run_evidence
    module.current_commit = current_commit
    previous_cwd, previous_sdk = Path.cwd(), os.environ.get("ANDROID_HOME")
    out.mkdir(parents=True, exist_ok=False)
    try:
        os.chdir(runtime.repo)
        os.environ["ANDROID_HOME"] = str(runtime.build_tools.parent.parent)
        # This function retains ZIP, ABI/catalog, display-helper and packaged-Web
        # verification. acquire_apk and its GitHub credential path are never called.
        module.verify_apk(argparse.Namespace(apk=str(apk), out=str(out)))
        if signers != [expected]:
            raise ValueError("Original APK verifier did not produce the expected active-signer evidence")
        identity = out / "apk-identity.json"
        receipt = json.loads(identity.read_text())
        receipt["signature"] = "stable Android signing certificate SHA-256: " + expected
        receipt["signerCertificateSha256"] = signers
        receipt["expectedSignerCertificateSha256"] = expected
        receipt["stableSignerPinVerified"] = True
        identity.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0
    finally:
        os.chdir(previous_cwd)
        if previous_sdk is None:
            os.environ.pop("ANDROID_HOME", None)
        else:
            os.environ["ANDROID_HOME"] = previous_sdk


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--java-home")
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--temp-dir", type=Path)
    parser.add_argument("--openssl", help="Existing OpenSSL executable, without modifying system PATH")
    parser.add_argument("tool", choices=tuple(TOOLS) + ("verify-envelope", "verify-apk"))
    args, forwarded = parser.parse_known_args(argv)
    if args.temp_dir is None:
        parser.error("--temp-dir is required; choose an explicit build-volume directory")
    runtime = Runtime(args.repo, args.temp_dir, args.java_home, args.build_tools, args.openssl)
    try:
        with runtime.environment():
            if args.tool == "verify-envelope":
                return verify_envelope(runtime, forwarded)
            if args.tool == "verify-apk":
                return verify_apk(runtime, forwarded)
            return call_existing(runtime, args.tool, forwarded)
    except subprocess.SubprocessError:
        print("Existing update tool failed. No upload, key generation or publication was performed.", file=sys.stderr)
        return 2
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AssertionError, json.JSONDecodeError) as error:
        print("Local Windows update adapter failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
