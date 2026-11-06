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

Both the APK and the verified delta are uploaded through the original Site's
authenticated `__publisher` API. The server checks chunks and whole objects;
the client checks public full bytes and Range responses. Only then does one
conditional activation replace the ed896 manifest. The old stable channel and
historical objects remain available. An inefficient delta is still retained and
uploaded, but omitted from the App's delta choices so the full APK is used.
If uploading fails after preparation, the next run with the same source reuses
the signed bundle and reconciles existing parts instead of rebuilding it.

The upload credential is protected with Windows current-user DPAPI and a local
file ACL. Sites stores only its digest as the secret `PUBLISHER_TOKEN_SHA256`.
No signing key, credential or source-repository access token is shipped to the
website or placed in command arguments. The authenticated endpoint does not
depend on an expiring interactive Codex source credential.

Read-only previews:

```powershell
& E:\PocketWorld-dev\repo\tools\release.ps1 -Plan
& E:\PocketWorld-dev\repo\tools\release.ps1 -Check
```

`-PrepareOnly` is an explicit local build-and-bundle operation which does not
upload. Normal execution can build, verify and upload candidate artifacts before
end-to-end acceptance is complete. Formal update activation requires the existing
`qa/functional-results.json` record's `releaseAcceptance.status` and its five
core cases (`account_model`, `conversation`, `native_files`, `agent_execution`,
`owner_controls`) to be `passed`. The tested commit must be an ancestor and its
application, Web, runtime/dependency and build inputs must still match. Scripts
and documentation alone can change without changing those tested App inputs.
The orchestrator never marks a missing, failed or partial core case passed.
If that record is missing, stale or incomplete, successful preparation returns
`staged_awaiting_acceptance`, with artifact URLs and `activated: false`. It does
not advertise the candidate on the homepage or replace the App update manifest.
Running again with unchanged source preserves that candidate without rebuilding
or reuploading it. Once the real acceptance record passes, a subsequent run uses
the same signed bundle for activation. The script never marks acceptance passed.

This entry automates publishing an accepted batch. Writing or checking the
script does not start a build or publish the local candidate.

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
