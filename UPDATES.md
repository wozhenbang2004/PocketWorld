# Owner-controlled signed application updates

## Windows one-click release

The configured local entry is `E:\PocketWorld-dev\一键拉取构建发布.cmd`.
It calls `tools/release.ps1` and `tools/release.py`; machine paths live in
`E:\PocketWorld-dev\release.local.json`, outside Git. Start from
`tools/release.config.example.json` on another machine.

The default run preserves the current branch, requires a clean working tree,
fetches and normally merges `origin/main`, and stops with conflict files intact.
It never resets, cleans, stashes, force-pushes or starts GitHub Actions. A source
update to the orchestrator restarts it once before any build. All bulk output,
dependency caches and temporary files use the configured non-system volumes.

The run verifies the signed online baseline,
chooses a version above the real published/local APK versions, runs local checks,
builds the current Web assets, and invokes one local APK build with explicit
version properties. It checks the fixed Android signer and complete APK contents,
generates the Google BSDIFF43/GZIP delta, reconstructs and hashes the whole target,
and signs the new manifest with the existing publisher RSA key. The original APK
and manifest keys are never regenerated.

With the configured proxy, Node 24 font downloads use the existing HTTP(S) proxy.
Complete pinned fonts are cached on the project volume. Downloads show byte
progress, allow 180 seconds without new data and at most 30 minutes overall,
and retain temporary prefixes for a checked Range resume on the next run.
Only a complete file passing the original size, hashes and glyph/license checks
replaces the cached font; an ignored Range response restarts only its temporary file.

Both the APK and the verified delta are uploaded through the original Site's
authenticated `__publisher` API. The server checks chunks and streams the whole
assembled object through SHA-256 before marking it ready. The publisher checks
the public HEAD length and a 64-byte Range against the local artifact, without
downloading the whole APK or delta again. Only then does one conditional
activation replace the ed896 manifest. The old stable channel and
historical objects remain available. An inefficient delta is still retained and
uploaded, but omitted from the App's delta choices so the full APK is used.
If uploading fails after preparation, the next run with the same source reuses
the signed bundle and reconciles existing parts instead of rebuilding it.

Missing parts of each artifact upload with two concurrent workers by default.
`upload_concurrency` accepts only `1` or `2`; `1` retains serial uploads. Each
worker opens its own file handle and HTTP client, checks the part SHA, and holds
at most one 16 MiB payload. Parts may finish out of order. Any failure stops new
work and waits for in-flight requests before returning; successful remote parts
remain available for the next reconciliation. Blob completion, public HEAD/Range
verification and manifest activation remain ordered after all uploads
succeed. Connection concurrency does not guarantee a fixed speed increase.

The upload credential is protected with Windows current-user DPAPI and a local
file ACL. Sites stores only its digest as the secret `PUBLISHER_TOKEN_SHA256`.
No signing key, credential or source-repository access token is shipped to the
website or placed in command arguments. The authenticated endpoint does not
depend on an expiring interactive Codex source credential.

Read-only publisher JSON queries allow three transport attempts, each with a
30 second socket timeout. Authorization, certificate and protocol failures stop
immediately. Upload and activation requests are never automatically replayed.
Each run's `network.log` records the method, path, transport stage and error
class without credentials, query strings or response bodies.

Generating a delta requires the exact signed baseline APK. If no matching copy
is already cached locally, the optional `aria2` tool downloads that necessary
baseline with bounded retries, TLS verification and its signed SHA-256 checksum;
the completed baseline's size and hash are independently checked. Without that
tool, the existing Python download path remains available. This baseline download
is separate from publication checks, which never re-download complete artifacts.
An interrupted upload can reuse an older signed bundle after script-only source
updates when its build commit is an ancestor and all application inputs are
unchanged. The registered source commit remains the APK's actual build commit;
the current checkout is checked separately before activation.

Read-only previews:

```powershell
& E:\PocketWorld-dev\repo\tools\release.ps1 -Plan
& E:\PocketWorld-dev\repo\tools\release.ps1 -Check
```

Normal execution publishes automatically: successful local checks, APK signature
verification, exact delta reconstruction, upload, server whole-object SHA and
public HEAD/Range verification are followed by conditional manifest activation. It reports
`published` only after the public update manifest matches the signed bytes.
Failed checks, changed source or baseline, and unconfirmed activation stop the
run with the existing artifacts preserved. A stale external core-QA record
cannot silently turn this default operation into a candidate upload. The release
script neither reads nor changes the separate functional acceptance record.

Candidate preparation requires an explicit option:

- `-PrepareOnly` builds and signs a local bundle without uploading or activating.
- `-StageOnly` uploads and verifies the bundle, then reports `staged` with
  `activated: false`. It does not replace the App update manifest.
- A subsequent normal run reuses an unchanged staged bundle and activates it
  after verification, without rebuilding or reuploading ready artifacts.

These options are mutually exclusive with each other and with `-Plan`/`-Check`.
Legacy `uploaded_waiting_acceptance` records are also resumable by a normal run;
they no longer require editing a QA file or a separate manual activation step.
Already published, unchanged application inputs return `up_to_date` without a
new build. Plan and readiness checks do not start builds or publication.

The application now has a Settings → 应用更新 entry. It checks a signed
release manifest, announces an available version, downloads an exact-base delta
when available, falls back to the full APK, verifies the result, and opens Android's
installer after the owner taps Install. Existing app data is never removed.

The approved HTTPS endpoint and RSA public trust root are pinned in
`gradle.properties`. No token or private key is included. The server must provide
a valid signed envelope before a release can be discovered; public artifact links
alone cannot activate updates. The preparation tools never publish anything.

## Trust and release requirements

- Build with both `-PpocketworldUpdateManifestUrl=https://PRIVATE-HOST/release.json`
  and `-PpocketworldUpdatePublicKey=BASE64_RSA_SPKI_PUBLIC_KEY`. The public key is
  RSA 2048 bits or stronger; its private counterpart stays with the publisher.
  These properties now have approved public defaults in `gradle.properties`;
  explicit build overrides must still supply a matching approved URL/key pair.
- The manifest URL must use HTTPS, without credentials or query tokens embedded
  in the APK. APK and delta URLs may use owner-approved public hosting. Public
  hosting does not grant release authority: the client still requires its pinned
  manifest signing key and the exact Android APK signer. This client does not
  acquire GitHub credentials or make private Actions artifacts publicly reachable.
- Manifest signatures are SHA256withRSA over the exact decoded payload bytes,
  separate from the APK's Android signing key. Replacing the server or obtaining
  its TLS certificate does not grant release authority. A future manifest-key
  change needs a trusted app release; runtime content cannot replace the root.
- The final APK must have the same package name, a strictly higher version code,
  and exactly the currently installed signer set. Key rotation is deliberately
  refused until a separately reviewed migration exists. Full-file SHA-256 and
  size must match the signed manifest. Android performs installation verification.
- Every installed base needs its exact whole-APK SHA-256 and version code in the
  delta record. Split APK installations use full-package fallback. A failed,
  unsupported, inapplicable or inefficient delta uses the full APK.
- **The already distributed local debug APK (version code 3) used an ephemeral
  Android debug key. A new APK signed with a different stable project key cannot
  cover-install it.** Preserve the original debug keystore for a compatible
  bridge release or plan a user-controlled migration. Never uninstall the current
  app just to bypass this condition. The first updater-enabled app itself must
  be installed through an existing authorized channel; an old app without this
  updater cannot discover it automatically.

## Delivery behavior and recovery

When configured and enabled, app startup checks if the last successful check is
at least six hours old. Android JobScheduler also requests checks about every six
hours with network access and persistence across reboot. Android may defer these
jobs. This is **periodic pull checking, not real-time server push**; no FCM account,
push token, persistent socket or remotely executable update instruction is added.
Downloads and installation are always owner actions. A notification opens the
native update page; it never installs. Android 13+ notification permission is
requested only from the page's explicit button. Disabled notifications do not hide
the in-app update.

While the native update page is visible, existing phone-input admission returns
the existing `OwnerRequired` handoff instead of dispatching model input. Ordinary
page backgrounding releases that page window. An actually launched system installer
or unknown-source settings flow holds a separate window until its own activity
result returns; configuration recreation retains that pending flag. The final
installer uses `ACTION_INSTALL_PACKAGE` with `EXTRA_RETURN_RESULT`, never silently
resumes on permission return, and keeps the Android confirmation. Native installer
node/coordinate checks also refuse uncertain installation targets. OEM-specific
activity results, process death during the system handoff and rootless installer
windows still need real-device validation. Host policy tests do not establish
universal installer or arbitrary external automation protection.

Partial downloads live in the app's no-backup private directory under their signed
artifact hash. Resume requires an exact Content-Range; a server returning a full
200 response restarts safely. Redirects, excessive/mismatched lengths and hashes
are rejected. Partial or unverified files never receive an installer URI. A process
restart re-verifies the cached signed manifest and final APK. Expired manifests
must be refreshed. Pausing retains the partial file. No background process edits
the installed APK or app data.

## Manifest payload

The envelope contains base64 `payload` and `signature` strings. The decoded UTF-8
payload is JSON with the following fields. Times are Unix milliseconds, validity
at most seven days. `sha256` values are lower-case whole-file hex digests.

```json
{
  "schema": 1,
  "packageName": "dev.pocketengine",
  "versionCode": 4,
  "versionName": "0.3.1",
  "minSdk": 30,
  "issuedAt": 0,
  "expiresAt": 0,
  "notes": "Human-readable release notes",
  "apk": {"url": "https://PRIVATE-HOST/app.apk", "bytes": 1, "sha256": "ACTUAL_APK_SHA256"},
  "deltas": [{
    "format": "bsdiff43-gzip-v1",
    "baseVersionCode": 3,
    "baseSha256": "ACTUAL_INSTALLED_BASE_APK_SHA256",
    "url": "https://PRIVATE-HOST/base-to-target.patch.gz",
    "bytes": 1,
    "sha256": "ACTUAL_GZIP_PATCH_SHA256"
  }]
}
```

This example is illustrative and intentionally not a publishable release. Omit
`deltas` when no verified, smaller exact-base patch exists. Produce the standard
Google BSDIFF43 stream wrapped in standard GZIP with `tools/apk-delta.py`; see
`third-party/archive-patcher/UPSTREAM.json` and the tool's help. The client uses the
fixed official Apache-2.0 implementation with additional size, framing and
cancellation limits. The upstream was archived in June 2024; no maintenance or
compatibility guarantee is implied.

Review actual APK identity and signing evidence, then sign locally:

```sh
python3 tools/sign-update-manifest.py --payload reviewed-release.json \
  --private-key /secure/operator/manifest-key.pem \
  --public-key /secure/operator/manifest-public.pem --output release.json
```

This signs only. It does not generate a key, configure credentials, upload an APK,
edit a remote release, or deploy a service. All release publishing remains a
separately authorized action. A compatible version must retain a version code
above every already distributed build, including CI builds.

## Local release preparation and Sites handoff

`tools/prepare-update-release.py` runs the real APK identity/signature and delta
preflight, then prepares a new local directory with immutable hash-addressed
files, `unsigned-payload.json`, artifact evidence and a manifest-last upload plan.
It never signs, uploads, deploys, creates keys or replaces an existing bundle:

```sh
export JAVA_HOME=/path/to/existing/jdk17
python3 tools/prepare-update-release.py --origin https://YOUR-SITE.chatgpt.site \
  --old exact-previous-signed.apk --new exact-target-signed.apk --patch exact.patch.gz \
  --build-tools /path/to/android-sdk/build-tools/35.0.0 --java-home "$JAVA_HOME" \
  --output-dir /path/to/new-local-bundle
```

The manifest upload URL and bootstrap template use
`pocketworldUpdateManifestUrl` from the existing `gradle.properties`. `--origin`
sets the immutable APK/delta artifact origin; it does not select another update
channel. If a build uses an explicit manifest URL override, align the project
configuration with that actual APK before preparing its release.

Hosting contract:
- `/artifacts/<whole-file-sha256>/<filename>`: immutable exact bytes, no HTTP
  content encoding, direct 200/206 responses, exact Content-Length/Content-Range,
  `Accept-Ranges: bytes`. Long-lived immutable caching is appropriate here.
- `/releases/ed896/release.json`: the current configured channel's verified signed envelope, uploaded
  **last**, with `Cache-Control: no-store, max-age=0`. Never place the unsigned
  payload or an example signature at this URL. Do not depend on a redirect.

Keep the old `/releases/stable/release.json` v248 envelope and its immutable
objects unchanged. It belongs to the previous manifest trust root. The current
ed896 channel uses the existing pinned RSA public key and Android signer; a
later release must preserve those identities and increase the version code
above every distributed build. Verify the packaged resource values before delivery.
Public v250 still has the previous RSA pin and needs a same-Android-signer full
bootstrap before it can discover this new channel. A valid delta alone cannot
change that trust root. Historical v246 and v3-to-v246 evidence above does not
establish discovery or installation on the current channel.

`tools/verify-update-host.py` is an explicit read-only network probe using the
production `UpdateDownloads` implementation and existing locked JVM dependencies.
It starts a real HTTPS download, cancels it, resumes its actual partial file with
Range, requires 206 and verifies the entire SHA-256 against a trusted local file.
It does not verify a signed manifest or install an APK:

```sh
python3 tools/verify-update-host.py --url https://YOUR-SITE.chatgpt.site/artifacts/HASH/app.apk \
  --expected-file exact-target-signed.apk --kind apk --java-home "$JAVA_HOME" \
  --dependency-cache /path/to/existing/readonly-gradle-cache --output new-host-evidence.json
```

Cloud environments may require `--environment-proxy` to use their existing HTTPS
proxy. An optional `--runtime-java` selects an existing Java runtime with its
normal default trust store. Neither option disables TLS validation or changes a
trust store. A proxy/JVM certificate failure is a blocked host check, not proof
of an Android device failure or a successful download.

Focused local tooling tests (no key generation or publication):
`python3 -B -m unittest -v tools/tests/test_update_release_bundle.py tools/tests/test_update_artifact_preflight.py`.

## Verification

`tools/test-app-updates.sh` runs focused JVM tests without triggering an APK or CI
build. `tools/apk-delta.py` and its tests cover the actual Google generator/applier
path and hostile framing. The Android integration must additionally be exercised
on a supported device with a configured trusted HTTPS endpoint and two same-signer APKs,
including notification denial, network interruption, process death, installer
cancellation, unknown-source permission denial/return, wrong signer, and a genuine
successful cover-install. A Java compile or fixture pass does not establish those
device results or claim that a service is live.

Primary contracts:
- https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
- https://developer.android.com/reference/android/app/job/JobScheduler
- https://github.com/google/archive-patcher/tree/50ca40a3de7983392a383ed3cc7b48e25f1b69b3
