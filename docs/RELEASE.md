# Release guide

## Unsigned CI artifacts

The branch/pull-request jobs of `Android CI` upload `app-github-release-unsigned.apk`: they prove the
release build compiles with R8, nothing more. Those artifacts are for smoke testing only and are
never published.

The **Release** workflow (`release.yml`) is the only thing that publishes, and it refuses to publish
anything it cannot prove is signed: it decodes the keystore from the repository secrets, builds both
flavours, and then runs `apksigner verify --print-certs` on each APK - failing when an APK is
unsigned, when the two flavours disagree on the signer, or when the signed APK is missing. The
signer certificate's SHA-256 is printed in the run summary and in the job log, which is the value
`AllowedAPKSigningKeys` in the F-Droid submission must carry. If the four secrets below are absent,
the workflow stops at "Decode release keystore" and publishes nothing.

## Signed release APK / AAB (local)

1. Create a keystore (once):

```bash
keytool -genkey -v \
  -keystore mushrea-code-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias mushrea-code
```

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
run **Actions → "Signed release build (no publish)" → Run workflow**. It builds both flavours, signs
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

Android accepts an app update only when it is signed by the **same certificate** as the installed
copy, and `AllowedAPKSigningKeys` in the F-Droid metadata pins that certificate publicly. So the
key must be backed up like a password and never regenerated after a version has been published: a new
key means users must uninstall and reinstall, and it invalidates every pin.

Two fingerprints are on record:

| Identity | SHA-256 of the signing certificate | Where it stands |
|---|---|---|
| previously published builds | `f036e07002d8c2e6a5a64000f1211398d4831ff37cf280456a9a26d2f12617df` | recorded in the F-Droid metadata example further down this file; its private key is **not** in the repository, so it can only be used by whoever holds it |
| the key generated for this branch | `876b116e0031230d2541ef8078e8f0fbf0d7e111a4de6e5573c691b08089b6a2` | RSA 4096, self-signed, valid until 2054-02-19, alias `mushrea-code`, PKCS#12 (a JKS copy exists) — **not committed**; adopting it means the fingerprint above is replaced everywhere, and only before a release is published |

Both workflows print the signer fingerprint on every run (`apksigner verify --print-certs`, in the
job log and the run summary), so the value can be compared instead of trusted.

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

AllowedAPKSigningKeys: f036e07002d8c2e6a5a64000f1211398d4831ff37cf280456a9a26d2f12617df

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.2.22
CurrentVersionCode: 61
```

This is the exact field order/quoting `fdroid rewritemeta` produces (it also
drops YAML comments, so any explanatory comments only live in this file and
the MR's discussion thread, not in the metadata itself).

`AllowedAPKSigningKeys` is the SHA-256 of the app's signing certificate,
extracted directly from a published release APK's APK Signing Block v2 (not
from the keystore) — `keytool`/`apksigner` weren't available locally, so this
was parsed by hand from the APK's binary signing block. `Binaries:` is a URL
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
