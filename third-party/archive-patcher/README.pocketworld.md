# Pinned APK delta engine

Source: [Google archive-patcher](https://github.com/google/archive-patcher), fixed
commit [`50ca40a3de7983392a383ed3cc7b48e25f1b69b3`](https://github.com/google/archive-patcher/commit/50ca40a3de7983392a383ed3cc7b48e25f1b69b3)
(2019-03-26). On 2026-11-04 the official repository reported it was archived on
2024-06-24 and is read-only. This is a deliberately small, vendored, unmodified
implementation, **not an actively maintained dependency**. Reassess the pin and
adapter on a new security report or supported Android platform change.

`UPSTREAM.json` records each copied upstream path, byte length, SHA-256 and Git
blob ID. All copied bytes were matched to the official pinned Git tree. The
complete upstream `LICENSE` must accompany distribution: Apache-2.0, with the
additional DivSuffixSorter MIT notice. Source copyright headers are unchanged.

## Runtime boundary

- Compile only `applier/src/main/java` into Android. `generator/` and canonical
  fixtures are host-only and must not be added to Android's source set.
- `app/.../features/updates/ApkDelta.java` is the separate PocketWorld adapter.
  It calls the unmodified `BsPatch.applyPatch` with an exact target size.
- Wire label: `bsdiff43-gzip-v1`. The downloaded file is **one standard GZIP
  member** whose decoded content is the official `ENDSLEY/BSDIFF43` stream.
  No private patch encoding or File-by-File ZIP recompression is used. Raw bsdiff
  payload is as long as the target, so transport compression is necessary for
  useful bandwidth savings.
- Old and target files are limited to 512 MiB. The compressed patch is limited
  to 256 MiB. Decoded bytes are limited to target bytes + 24 bytes per allowed
  instruction + the 23-byte header; at most 1,000,000 instructions are accepted.
  These policy limits are intentionally below the upstream integer ceiling.
- The first streaming pass validates GZIP CRC/length, a single member with no
  compressed tail, the official header and signed-magnitude integers, all old
  reads/seeks, target length, instruction budget, truncation and decoded tail.
  The second pass runs the official algorithm with bounded writes. Neither
  pass stores a full decoded patch or target in memory.
- Cancellation and thread interruption are checked before work and at bounded
  input/output intervals. Interrupt status is retained. Output must not exist;
  `CREATE_NEW` prevents replacing an APK or another file. Failure or cancellation
  removes only the output created by this invocation. A completed output is
  forced to disk before returning.
- GZIP framing uses protected inflater byte counts to check header + DEFLATE +
  8-byte RFC1952 trailer against the complete compressed length. This avoids
  `GZIPInputStream`'s permissive trailing-data behavior. The implementation was
  checked against OpenJDK 17 and Android 11/API 30's [official implementation](https://android.googlesource.com/platform/libcore/+/refs/heads/android11-release/ojluni/src/main/java/java/util/zip/GZIPInputStream.java).

Inputs must be private, immutable files owned by the caller. The update manager
must authenticate release metadata, verify the downloaded compressed patch hash
and exact installed base APK hash **before** calling the adapter, then verify
the entire rebuilt APK hash, package identity/version and Android signing
certificate before offering installation. This module never authenticates a
release, grants install permission, bypasses package-signature checks or installs
anything. A failed/uneconomical delta should use the authenticated full-APK path.

## Offline release tool and regression

Java 17+ JDK and Python 3 are required. No dependencies are downloaded; source
integrity is checked before compilation. Temporary compilation, suffix-sort
storage and rebuilt verification artifacts are isolated under the host temp
directory and removed. Generation checks available disk before running.

```
python3 tools/apk-delta.py --java-home /path/to/jdk17 test
python3 tools/apk-delta.py --java-home /path/to/jdk17 generate \
  --old /path/to/exact-previous-signed.apk \
  --new /path/to/exact-next-signed.apk \
  --output /path/to/new.patch.gz
python3 tools/apk-delta.py --java-home /path/to/jdk17 verify \
  --old /path/to/exact-previous-signed.apk \
  --new /path/to/exact-next-signed.apk --patch /path/to/new.patch.gz
```

Generation uses the official `BsDiffDeltaGenerator`, standard GZIP, reconstructs
with the actual production adapter and compares complete SHA-256 before moving
the result into place. It refuses to replace an existing output. It reports
base/target/patch lengths and SHA-256 values, plus whether the patch is smaller.
Only publish a delta when it is worthwhile; unrelated or recompressed APKs may
compress poorly. There is no signing, upload, Git push, CI trigger or installation.

The focused regression covers the upstream canonical fixture, Google-generator
round-trip on an APK-shaped ZIP, bandwidth savings for a small change, malformed
GZIP/header/CRC/trailer, compressed and decoded tails, extra members, integer and
seek bounds, size/instruction limits, preflight and mid-write cancellation,
thread interruption and refusal to replace existing inputs/outputs. APK-shaped
fixtures are not signed or installable APKs. Host tests do not establish Android
device behavior, actual-release delta ratios or Android installer acceptance.

## Source-independent release preflight

Before a private update host or manifest key is chosen, inspect the exact signed
artifacts locally with the existing official Android SDK build-tools:

```
export JAVA_HOME=/path/to/jdk17
python3 tools/inspect-update-artifacts.py --java-home "$JAVA_HOME" \
  --build-tools /path/to/android-sdk/build-tools/35.0.0 \
  --old /path/to/exact-previous-signed.apk --new /path/to/exact-next-signed.apk \
  --patch /path/to/new.patch.gz --output artifact-evidence.json
python3 -B -m unittest -v tools/tests/test_update_artifact_preflight.py
```

The preflight reads whole-file hashes, package/version/minimum SDK with `aapt`,
and verified modern APK signatures/certificate sets with `apksigner` for API 30.
It refuses a different package, split APK, different signer set, nonincreasing
version or changing input. When a patch is provided it reconstructs through the
production adapter and compares the complete target hash, not a mock checksum.
The new JSON is explicitly **evidence only, not a publishable/signed manifest**.
It contains no server URL, credentials or release authority. Existing output is
never replaced. Without `--patch` the tool verifies APK identities only.

On 2026-11-04 a real signed version-3 APK previously delivered through Space
(118,618,327 bytes, SHA-256 `2e10e73b162aacdafbc41aa0e48e74b59e54a25fabee2460ad25f808008872a0`)
and signed updater-enabled version 246
(118,641,968 bytes, SHA-256 `6570f6eb537a57119cfc6586b538dc3fffef080bb47a61addced766141442b4a`)
passed this offline path. Both were package `dev.pocketengine`, minSdk 30,
v2-verified, with the same certificate SHA-256
`a97ba2ae1362e007ea160800bf50478e4719f39f6b5733664fcd830884e47d9e`.
The generated patch was 2,895,197 bytes (2.44% of the full target), SHA-256
`970d1f03f8475d745e97f4c128b85b8ce3ec4222d3a7c794326ce16f19f13757`,
and reconstructed the complete signed target exactly. This is evidence for that
specific pair, not a general compression guarantee. No endpoint was configured,
key created, remote service deployed or device installation performed. An older
app without the updater still needs an authorized first manual bridge install.
