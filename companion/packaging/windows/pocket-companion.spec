# Run only in an audited, already-prepared Windows x64 builder. No dependency install here.
from pathlib import Path
import hashlib
import importlib.metadata
import json
from PyInstaller.utils.hooks import collect_all, collect_delvewheel_libs_directory, copy_metadata, get_package_paths

ROOT = Path(SPECPATH).resolve().parents[2]
COMPANION = ROOT / "companion"
if not COMPANION.is_dir():
    raise RuntimeError("Spec must live in the product companion/packaging/windows directory")
required = ["device_protocol.py", "device_identity.py", "device_pairing.py", "device_artifacts.py", "desktop_runtime.py",
            "desktop_stream.py", "desktop_rtp.py", "desktop_viewer.py", "agent_desktop_driver.py"]
if any(not (COMPANION / name).is_file() for name in required):
    raise RuntimeError("Build the integrated product tree; required shared protocol/identity/artifact modules are missing")
hidden = [path.stem for path in COMPANION.glob("*.py")
          if not path.stem.startswith("test_") and not path.stem.endswith("_fixture_server") and path.stem != "windows_gui_fixture"]
# The build environment is prepared from the hash-locked wheels separately.
# Never install dependencies or create credentials while freezing the runtime.
pairing_lock_path = COMPANION / "packaging" / "windows-pairing-dependencies.json"
pairing_lock = json.loads(pairing_lock_path.read_text(encoding="utf-8"))
pairing_data = [(str(pairing_lock_path), "build-materials"),
                (str(COMPANION / "requirements-windows-pairing.txt"), "build-materials"),
                (str(ROOT / "licenses" / "windows-pairing-THIRD-PARTY-NOTICES.txt"), "licenses")]
expected_pairing_versions = {"qrcode": "8.2", "colorama": "0.4.6"}
if {item["name"]: item["version"] for item in pairing_lock["components"]} != expected_pairing_versions:
    raise RuntimeError("Unexpected pairing dependency contract")
for item in pairing_lock["components"]:
    if importlib.metadata.version(item["name"]) != item["version"]:
        raise RuntimeError("Prepare the exact hash-locked pairing dependency before building")
    license_path = ROOT / item["licenseFile"]
    if hashlib.sha256(license_path.read_bytes()).hexdigest() != item["licenseSha256"]:
        raise RuntimeError("Pinned pairing license material changed")
    pairing_data += copy_metadata(item["name"])
    pairing_data.append((str(license_path), "licenses"))
hidden += ["qrcode", "qrcode.main", "qrcode.constants", "qrcode.exceptions", "qrcode.util",
           "qrcode.base", "qrcode.LUT", "qrcode.image.base", "qrcode.image.pure",
           "qrcode.compat.png", "qrcode.image.styles.moduledrawers.base", "colorama"]
desktop_lock_path = COMPANION / "packaging" / "windows-desktop-dependencies.json"
desktop_lock = json.loads(desktop_lock_path.read_text(encoding="utf-8"))
if desktop_lock["component"] != "pillow" or desktop_lock["version"] != "13.0.0" or importlib.metadata.version("pillow") != desktop_lock["version"]:
    raise RuntimeError("Prepare the exact hash-locked desktop PNG decoder before building")
desktop_license = ROOT / desktop_lock["licenseFile"]
if hashlib.sha256(desktop_license.read_bytes()).hexdigest() != desktop_lock["licenseSha256"]:
    raise RuntimeError("Pinned desktop decoder license material changed")
desktop_data = [(str(desktop_lock_path), "build-materials"),
                (str(COMPANION / "requirements-windows-desktop.txt"), "build-materials"),
                (str(desktop_license), "licenses")] + copy_metadata("pillow")
hidden += ["PIL.Image", "PIL.PngImagePlugin"]
hidden += ["PIL.ImageGrab", "_cffi_backend"]
stream_lock_path = COMPANION / "packaging" / "windows-stream-dependencies.json"
stream_lock = json.loads(stream_lock_path.read_text(encoding="utf-8"))
if {item["name"]: item["version"] for item in stream_lock["components"]} != {"aiortc": "1.15.0", "av": "17.0.1"}:
    raise RuntimeError("Unexpected desktop stream dependency contract")
stream_data = [(str(stream_lock_path), "build-materials"),
               (str(COMPANION / "requirements-desktop-stream.txt"), "build-materials")]
stream_binaries = []
for item in stream_lock["components"]:
    if importlib.metadata.version(item["name"]) != item["version"]:
        raise RuntimeError("Prepare the exact hash-locked media dependency before building")
    license_path = ROOT / item["licenseFile"]
    if hashlib.sha256(license_path.read_bytes()).hexdigest() != item["licenseSha256"]:
        raise RuntimeError("Pinned media license material changed")
    stream_data.append((str(license_path), "licenses"))
# Explicit collection handles dynamic codec imports and Windows wheel DLLs.
# The actual frozen --stream-self-test must encode VP8 before provenance exists.
for package in ("aiortc", "av", "aioice", "pylibsrtp", "google_crc32c", "pyee", "OpenSSL", "ifaddr", "dns"):
    package_data, package_binaries, package_hidden = collect_all(package)
    stream_data += package_data
    stream_binaries += package_binaries
    hidden += package_hidden
for package in ("av", "pylibsrtp", "google_crc32c"):
    stream_data, stream_binaries = collect_delvewheel_libs_directory(package, datas=stream_data, binaries=stream_binaries)
# Exact source accompanies the loaded bytecode so the bounded RTP observer can
# verify both under this interpreter, including in an archived frozen runtime.
_, aiortc_root = get_package_paths("aiortc")
for relative in ("rtcrtpsender.py", "rtcdtlstransport.py", "codecs/vpx.py"):
    stream_data.append((str(Path(aiortc_root) / relative), "aiortc/" + str(Path(relative).parent)))
agent_pin_path = COMPANION / "packaging" / "agent-desktop-pin.json"
agent_pin = json.loads(agent_pin_path.read_text(encoding="utf-8"))
agent_license = ROOT / agent_pin["licenseFile"]
if hashlib.sha256(agent_license.read_bytes()).hexdigest() != agent_pin["licenseSha256"]:
    raise RuntimeError("Pinned agent-desktop license material changed")
agent_data = [(str(agent_pin_path), "build-materials"), (str(agent_license), "licenses")]
a = Analysis([str(COMPANION / "desktop_worker.py")], pathex=[str(COMPANION)], binaries=stream_binaries,
             datas=[(str(ROOT / "licenses" / "cua-driver-MIT.txt"), "licenses")] + pairing_data + desktop_data + stream_data + agent_data,
             hiddenimports=hidden, hookspath=[], hooksconfig={}, runtime_hooks=[], excludes=["png", "qrcode.image.pil"], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="PocketWorld-Companion", debug=False,
          strip=False, upx=False, console=True, manifest=str(Path(SPECPATH) / "PocketWorld-Companion.manifest"))
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="PocketWorld-Companion")
