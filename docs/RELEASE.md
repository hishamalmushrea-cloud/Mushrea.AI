# Release guide

## First-publication safety gate (2026-10-04)

The owner has now explicitly requested publication. `v1.2.26` is a **draft**, not a public release.
On 2026-10-04 the owner chose to **regenerate** the signing identity because the previous
transport private key is not in this workspace and backup custody was never confirmed.
The `27e45f68…` identity (CI `37177108852`) is therefore **retired before publication** and
must not be used for a public release. A new identity is bootstrapped on
`arena/01a1073e-mushrea-ai`; the owner must download and confirm the decrypted backup
before any APKs are published. Once a release is public, that adopted key must never be
replaced.

`sign-release-runner-key.yml` is now a **one-time bootstrap**, not a repeatable update signer. It
refuses existing tags/non-draft releases and requires a deliberate request on the session branch.
It seals the private keystore and password from a runner-private directory with OpenSSL CMS
(AES-256-GCM / RSA-OAEP-SHA256), then commits only an explicit public allowlist to that same branch.
The owner must download and confirm the complete decrypted signing backup before publication.
Nine offline OpenSSL tests exercise recovery, tampering, wrong recipient, file permissions,
allowlisting, overwrite refusal, missing/symlink files and archive path traversal.

The previous public handoff accidentally included a standalone keystore password (but not the
private keystore in clear). That identity is retired before publication; never reuse that envelope
or that password. The repaired exporter never stages a directory wholesale. Historical commits
are not rewritten. Once a release is public, the adopted key must never be replaced.

## Unsigned CI artifacts

The branch/pull-request jobs of `Android CI` upload `app-github-release-unsigned.apk`: they prove the
release build compiles with R8, nothing more. Those artifacts are for smoke testing only and are
never published.

The normal **Release** workflow (`release.yml`) refuses to publish anything it cannot prove is
signed by the adopted identity: it decodes the keystore from the repository secrets, builds both
flavours, and then runs `apksigner verify --print-certs` on each APK - failing when an APK is
unsigned, when the two flavours disagree on the signer, or when the signed APK is missing. The
signer certificate's SHA-256 is printed in the run summary and in the job log, which is the value
`AllowedAPKSigningKeys` in the F-Droid submission must carry. If the four secrets below are absent,
the workflow stops at "Decode release keystore" and publishes nothing.

## Signed release APK / AAB (local)

1. Restore the **adopted** `mushrea-code-release.p12` and its password from the owner's private backup.
   Do not generate a new keystore for this package after its first publication. A different key cannot
   update an installed copy, even if the version number and package name match.

2. Add to `~/.gradle/gradle.properties` (do not commit). The names must match what `app/build.gradle.kts` reads (`AND_CODE_*`, the same prefix the workflows use — GitHub Actions rejects a `GITHUB_` prefix for repo variables):

```properties
AND_CODE_STORE_FILE=/absolute/path/mushrea-code-release.jks
AND_CODE_STORE_PASSWORD=...
AND_CODE_KEY_ALIAS=mushrea-code
AND_CODE_KEY_PASSWORD=...
```

3. Build. `app/build.gradle.kts` already wires them: `hasReleaseSigning` is true only when all four
   values are present, and only then does the `release` build type get the signing config (v1 + v2 +
   v3 signing enabled, because some store upload verifiers still read the legacy v1 block). With any
   of the four missing the build still succeeds and silently produces
   `app-github-release-unsigned.apk` - which is why the Release workflow verifies the signature
   instead of trusting the file name:

```bash
./gradlew assembleGithubRelease
# or
./gradlew bundleGithubRelease
```

4. Verify - the same check the Release workflow runs before it publishes:

```bash
apksigner verify --print-certs app/build/outputs/apk/github/release/app-github-release.apk
```

   The `certificate SHA-256 digest` it prints is the app's signing identity: it must be the same in
   every release (Android updates are only accepted from the same signer), it is what
   `AllowedAPKSigningKeys` in the F-Droid metadata pins (see the F-Droid section below), and it is
   what the Release workflow writes into its summary on every run so the two can be compared without
   parsing an APK by hand.

## Signing in CI without publishing

`Release` signs and *publishes* (it creates a tag and a GitHub release). To produce a signed APK
without announcing a version - the way to check a build, or to hand someone an installable file -
run **Actions → "Signed release build (no publish)" → Run workflow**.

Until the workflow file is on `main` (that is when GitHub offers the "Run workflow" button - a
manual-only workflow is not dispatchable from a branch that has never been merged), a signed build on
the session branch is requested by changing `.sign-release-request`: that is the workflow's only push
trigger, so only a deliberate edit spends a signing run. It builds both flavours, signs
them with the repository keystore, verifies the signature with `apksigner` and uploads the signed
APKs plus the signer fingerprint as an artifact (30 days). It never creates a tag or a release.

### Setting the signing secrets (once, by the repository owner)

Both workflows read the same four secrets, so this is done once:

```bash
# The keystore is never committed: it is base64-encoded into a secret instead.
base64 -w0 mushrea-code-release.p12 > keystore.b64        # Linux
# macOS: base64 -i mushrea-code-release.p12 -o keystore.b64

gh secret set RELEASE_KEYSTORE_BASE64   < keystore.b64
gh secret set RELEASE_KEYSTORE_PASSWORD   # prompts, so the value stays out of the shell history
gh secret set RELEASE_KEY_ALIAS           # the alias inside the keystore, e.g. mushrea-code
gh secret set RELEASE_KEY_PASSWORD        # same value as the store password for a PKCS#12 keystore

rm keystore.b64
```

`--repo <owner>/<name>` is only needed when the command is not run inside the checkout.

### The signing identity, and the one rule that cannot be broken

Android accepts an update only when its certificate matches the installed copy. The first public
**preview/prerelease also freezes the identity**: do not wait for a stable release to back it up.

The authoritative public pin is `.github/signing-identity.sha256`. Both normal signing/release
workflows now use `scripts/verify_release_apks.sh` to reject missing APKs, an invalid pin, multiple
signers, a different certificate (even when both flavours agree), or unverified v1/v2/v3 signing.
`--min-sdk-version 23` forces the tool to actually verify v1; it does not change the app's minSdk 26.
The parser supports the current `V3.0 Signer:` label rather than assuming a legacy label.

| Identity | Certificate SHA-256 | Status |
|---|---|---|
| **adopted for first publication (backup confirmation pending)** | `ed8842ed83ff7362c0a93be160ec46a8cf522e0993547ca02587476ccf09ff3e` | RSA 4096 / SHA256withRSA / alias `mushrea-code` / PKCS#12. Generated in CI `37208874499` on `arena/01a1073e-mushrea-ai`. Both flavours verified v1+v2+v3 with one signer. **Do not publish until the owner confirms the private backup download.** |
| retired unconfirmed identity | `27e45f68f9a5c043749b6a99c49c9b556fe963e9622a8cb658a256d5fd413b57` | Generated in CI `37177108852`. Never published. Transport private key is not in this workspace and owner backup custody was not confirmed. Do not ship APKs signed with it. |
| retired trial identity | `7b935169e3997f9b742c9ad87189168ab89a7fde854d0af2a335d17ca1726669` | Signed experimental APKs in `37174670023`, but the local key/transport were lost and owner backup custody was not confirmed. Its old public delivery also accidentally included a standalone password; never reuse it. No release shipped under it. |
| retired older identity | `f036e07002d8c2e6a5a64000f1211398d4831ff37cf280456a9a26d2f12617df` | Recorded earlier as an extracted certificate; the owner does not possess the private key. Not used for a release from this repository. |

The bootstrap used **immutable CI-built APKs** from compile run `37174670023`, source
`e538eaf1b7b1968114bd84a2dedbb169771ab3d5`, handoff commit
`ec11d5aaa5fc2a47d309d32614eea8bce7e765fb`. Every application/build input was checked unchanged before
re-signing with the official SDK. After signing, all **374 / 336** non-signature ZIP payload entries
were independently compared to the original and matched byte-for-byte. This is re-signing, not an
unmeasured rebuild. The generated key and password were preserved in an authenticated **OpenSSL CMS
AES-256-GCM / RSA-OAEP-SHA256** envelope on the session branch before any later step could fail.

Run `37177108852` verified v1/v2/v3 and one signer for both flavours. Independent local verification
used OpenSSL for the v1 detached signature, SHA-256 for the signed manifest and every payload entry,
and Androguard to read the v2/v3 certificates. Androguard parsing is **not** claimed as cryptographic
v2/v3 verification; that verification was performed by the official `apksigner` in CI.

The private P12/password/transport key are **never release assets** and never committed in clear.
The owner receives a separate private ZIP through the file viewer and must keep it in two safe places;
the public CMS envelope is recoverable only with that owner's transport key. Do not destroy that
recovery key before its backup is secured. Workspace persistence is not a signing-key backup.

`sign-release-runner-key.yml` is one-time bootstrap only: existing tags, public releases or an existing
identity envelope forbid a fresh key. Once a version is public, use the adopted keystore in the
normal workflows (the four repository secrets), or another explicitly authorized secure signing
channel that preserves the **same identity**, never regenerate it. Setting secrets remains unavailable
to this integration; the owner need not set them to complete the current publication.

Before the first official **F-Droid** publication, measure the signer from the actually published
F-Droid APK again and update its `AllowedAPKSigningKeys` pin; a CI-generated fingerprint must not be
assumed to match an older MR submission. GitHub publication does not imply F-Droid acceptance.

### First publication asset relay (completed)

Direct uploads to `uploads.github.com` failed from the workspace. A temporary, draft-only job inside
`release.yml` staged the public allowlist in run `37177728960` and never published or created a tag.
All **eight** server-reported SHA-256 digests and file sizes match the independently verified local
files. The temporary job/request were removed after success; the final explicit publication still
waits for owner backup confirmation. The normal workflow now creates a draft first, refuses to
modify an already-public version, and does not use `--clobber` on assets.

## Versioning

- `versionName` / `versionCode` live in `app/build.gradle.kts`
- Tag releases as `vX.Y.Z` matching `versionName`
- Update `CHANGELOG.md` (or GitHub Release notes) with user-facing changes

## Pre-release checklist

- [ ] `./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubRelease`
- [ ] Manual smoke: local install, chat, permission approve/reject, remote connect
- [ ] `THIRD_PARTY_NOTICES.md` still accurate
- [ ] No secrets in git history

## F-Droid-compatible binary repository

The repository also has a `Publish F-Droid repository` workflow. It publishes
the signed APKs from GitHub Releases as a self-hosted F-Droid binary repository on
GitHub Pages. This is not an application submission to the official F-Droid
repository and does not require an F-Droiddata review.

Before enabling the workflow, create a dedicated repository signing keystore
and add these GitHub Actions secrets:

- `F_DROID_REPO_KEYSTORE_BASE64`: base64-encoded repository keystore
- `F_DROID_REPO_KEYSTORE_PASSWORD`: keystore password
- `F_DROID_REPO_KEY_ALIAS`: repository key alias
- `F_DROID_REPO_KEY_PASSWORD`: repository key password

For example, create the keystore locally with:

```bash
keytool -genkeypair -v \
  -keystore fdroid-repo.keystore \
  -alias mushrea-code-fdroid \
  -keyalg RSA -keysize 4096 -validity 10000
base64 fdroid-repo.keystore | tr -d '\n'
```

Put the final command's output in `F_DROID_REPO_KEYSTORE_BASE64`. The other
three values must match the keystore when it is created. Do not commit the
keystore or its passwords.

The repository key is separate from the APK signing key. Back it up securely;
changing it makes existing clients treat the repository as a new repository.

Enable GitHub Pages with `GitHub Actions` as the source. After a published
release, users can add:

```text
https://hishamalmushrea-cloud.github.io/mushrea-code/fdroid/repo/
```

The workflow retains the latest 100 non-draft, non-prerelease GitHub releases.

## Official F-Droid catalog (build-from-source)

The self-hosted repository above only republishes the GitHub-signed APK; it does
not put Mushrea Code in the official F-Droid catalog (browsable by category, e.g.
"AI Chat"). That requires F-Droid's own build server to compile the app from
source, which does not accept Firebase/Google Play services.

The `app` module has a `distribution` flavor dimension for this:

- `github` — current behavior, includes Firebase Analytics/Crashlytics.
- `fdroid` — no Firebase code at all (`app/src/fdroid/.../diagnostics/`
  provides no-op `AnalyticsReporter`/`CrashReporter` in place of
  `app/src/github/.../diagnostics/`, which keeps the Firebase-backed ones).

Build it locally with:

```bash
./gradlew -Pmushreacode.fdroidBuild=true :app:assembleFdroidRelease
```

The `-Pmushreacode.fdroidBuild=true` property additionally skips applying the
`com.google.gms.google-services` / `com.google.firebase.crashlytics` Gradle
plugins outright (they process `google-services.json` project-wide regardless
of flavor, so leaving them applied would still embed inert Google project
identifiers in the fdroid build).

Submitting to the official catalog means opening a merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata). This has been done:
[!48005](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/48005). Current
metadata, after several rounds of review feedback:

```yaml
AntiFeatures:
  NonFreeNet:
    en-US: optional GitHub OAuth sign-in, not required for core functionality
  TetheredNet:
    en-US: the on-demand Vosk wake-word speech model is only ever fetched from alphacephei.com
Categories:
  - AI Chat
License: MIT
AuthorName: Yu-ga
SourceCode: https://github.com/hishamalmushrea-cloud/Mushrea.AI
IssueTracker: https://github.com/hishamalmushrea-cloud/Mushrea.AI/issues
Changelog: https://github.com/hishamalmushrea-cloud/Mushrea.AI/releases

AutoName: MushreaCode

RepoType: git
Repo: https://github.com/hishamalmushrea-cloud/Mushrea.AI
Binaries: 
  https://github.com/hishamalmushrea-cloud/Mushrea.AI/releases/download/v%v/mushrea-code-v%v-fdroid-release.apk

Builds:
  - versionName: 1.2.22
    versionCode: 61
    commit: aa5474d0c0c0a4e1c01b436d56ea18342187e516
    subdir: app
    gradle:
      - fdroid
    prebuild: sed -i -e '/firebase/d' -e '/gms/d' {..,.}/build.gradle.kts
    gradleprops:
      - mushreacode.fdroidBuild=true

AllowedAPKSigningKeys: ed8842ed83ff7362c0a93be160ec46a8cf522e0993547ca02587476ccf09ff3e

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.2.22
CurrentVersionCode: 61
```

This is the exact field order/quoting `fdroid rewritemeta` produces (it also
drops YAML comments, so any explanatory comments only live in this file and
the MR's discussion thread, not in the metadata itself).

`AllowedAPKSigningKeys` is the SHA-256 of the app's signing certificate. The value above is the
adopted release key (see "The signing identity" above); read it back from a signed APK rather than
trusting this file, with `apksigner verify --print-certs <apk>` - which is what both signing workflows
do on every run, and what the F-Droid build server effectively repeats. The fingerprint originally
recorded here had been parsed by hand from a release APK's binary signing block before any signing
tooling was available; that key is retired and must not be reinstated. `Binaries:` is a URL
template (`%v` = versionName) F-Droid's build server uses to fetch the
officially-published binary and diff it against what it builds from source,
as a supply-chain check.

The `Binaries:` URL points at a `-fdroid-release.apk` asset, not the plain
`-release.apk` one — the latter is the `github` flavor (Firebase included)
and will never byte-diff-match a `fdroid` flavor build. The Release workflow
(`.github/workflows/release.yml`) now also runs
`./gradlew -Pmushreacode.fdroidBuild=true :app:assembleFdroidRelease` (deliberately
without `GITHUB_CLIENT_ID`, matching how F-Droid's own build server invokes
it) and publishes that APK alongside the existing assets so this comparison
has something correct to compare against. It's signed with the same release
signing config as the `github` flavor, hence the shared `AllowedAPKSigningKeys`
fingerprint above.
