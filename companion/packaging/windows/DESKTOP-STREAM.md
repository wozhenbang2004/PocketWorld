# Desktop video runtime

The original Companion now negotiates one send-only WebRTC video track through
the owner-only `desktop.viewer` method on the existing paired-device transport.
The receiver supplies a fully gathered receive-only offer. aiortc returns a real
SDP answer and sends DTLS-SRTP video; no unauthenticated HTTP/MJPEG endpoint is
created. Input uses the existing owner grant, human lease and durable operation
ledger, never a WebRTC input channel.

## Prepared Windows build

The product media pins are `../windows-stream-dependencies.json` and
`../../requirements-desktop-stream.txt`: aiortc 1.15.0 and PyAV 17.0.1. The manifest
records immutable upstream source commits, official Windows wheel hashes and
the original code licenses. PyAV 19 is outside this aiortc release's declared
dependency range. Capture reuses the separately pinned Pillow 13.0.0.

Use the project's normal independently reviewed, complete
`pocketworld.windows-runtime-inputs.v1` environment lock and prepared wheelhouse.
The media requirements file pins only the two product media wheels and therefore
uses `--no-deps` when preparing a builder. All direct and transitive distributions
must already be installed from the reviewed complete lock. `build_runtime.py`
checks their exact versions, every actual wheel SHA-256, the whole interpreter
and environment digests, and installed dependency compatibility. It does not
download, install, resolve, generate an automatically trusted lock, or create
device credentials.

The standard `pocket-companion.spec` collects aiortc, PyAV, SRTP/CRC extensions,
their Windows wheel DLL directories, Pillow capture, the owner viewer router,
and agent-desktop pin/license material. The actual build must run both
`PocketWorld-Companion.exe --help` and `--stream-self-test` before recording
provenance. The latter imports the real media libraries and encodes one 64×48
synthetic VP8 keyframe entirely in memory, before configuration, installation or
identity access. It opens no peer/socket and captures no desktop. Missing native
dependencies produce a nonzero exit; a successful help screen alone is
insufficient. The structured probe and log hash are retained in provenance.

The PyAV source license does not cover every bundled FFmpeg/native library.
The runtime producer retains all actual locked wheels' LICENSE, NOTICE and
COPYING material in `runtime-notices`; the bundle must carry those notices.
The frozen probe verifies dependency loading and encoding, not Windows capture,
Android playback, interactive input, hardware acceleration or WAN connectivity.

## Capture and lifetime

Window grants use a native HWND `PrintWindow` call into a bounded top-down DIB,
then Pillow scales the RGB image. Pillow 13's own `ImageGrab(window=...)` uses
client-area `GetClientRect`/`BitBlt` and is deliberately not used for the full
`GetWindowRect` input viewport. The `PW_RENDERFULLCONTENT` flag follows the
[Chromium Windows capture implementation](https://chromium.googlesource.com/chromium/src/+/f50f372d7a7ba63d24b0b5aef7a4aec791e19c19/ui/gl/direct_composition_surface_win_unittest.cc)
to include DirectComposition content on the supported Windows versions; it is
not a promise that every protected or unusual renderer can be captured. The
call uses the original authorized window and cannot broaden into a
rectangle of the desktop containing other foreground windows. A whole-primary
display view requires the corresponding desktop grant. The bridge verifies the
interactive session, default input desktop, original process generation, HWND,
primary display and physical bounds before and after capture. Lock/disconnect,
revocation, capture failure or expiry ends the stream. Unsupported/off-primary
window captures fail explicitly. Native successful capture of a protected or
unsupported renderer may still appear blank and needs real Windows acceptance.

Default video is at most 10 frames per second and 1200 pixels on the long edge,
with a configurable hard ceiling of 15 frames per second, at most two viewers,
and an explicit maximum five-minute lifetime. The implementation uses software
encoding. One bounded daemon capture worker prevents a blocked native capture
from creating unbounded threads. Timed-out/closed captures cannot publish late
pixels. The caller explicitly closes the stream; expiry and grant checks also
run while no video packets are requested.

The browser-created `pocketworld.desktop.frames` data channel receives only frame
metadata. The bridge retains at most 32 references for at most two seconds.
Human input must cite an authentic recent reference, the original owner and
resource, and unchanged native geometry, foreground and local-input state. The
adapter checks health again under its dispatch lock. Control handoffs invalidate
all references. Metadata delivery and video decoding have independent buffers;
this is bounded recent geometry evidence, not proof of exact displayed pixels
or a business operation's success. The stream never becomes an LLM image
artifact and never refreshes an Agent's semantic observation.

`desktop.streaming.iceServers` is an explicit operator configuration; the default
is empty. No third-party STUN/TURN service or credentials are hardcoded. With no
configured TURN, NAT/firewall combinations may prevent connection; direct-LAN
success does not establish internet reachability. TURN credentials are exposed
only through authorized viewer configuration and excluded from general stream
descriptions/status. WebRTC failure is reported explicitly; this module does not
pretend that periodic snapshots are a live media stream.

## Verification

From `companion`, run:

```text
python -B -m unittest -v test_desktop_stream test_desktop_worker_stream test_windows_runtime_build test_windows_packaging
```

Contract fixtures exercise bounds, revocation, scope, stale input, cancellation,
and packaging without a real desktop. If the prepared aiortc/PyAV SDKs exist, the
same suite also negotiates a real no-STUN local DTLS-SRTP connection, decodes a
labelled synthetic video frame, receives metadata, and executes the native
codec probe. Missing SDKs skip only those actual-media tests and do not yield a
success claim. Source-only development without the media SDK reports viewing
unavailable. A real Windows build/capture and Android/remote-network acceptance
remain separate required platform checks.
