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

Android accepts an app update only when it is signed by the **same certificate** as the installed
copy, and `AllowedAPKSigningKeys` in the F-Droid metadata pins that certificate publicly. So the
key must be backed up like a password and never regenerated after a version has been published: a new
key means users must uninstall and reinstall, and it invalidates every pin.

**The adopted identity** is the key generated for this branch (2026-10-04). Its fingerprint is what
`AllowedAPKSigningKeys` must carry from now on:

| Identity | SHA-256 of the signing certificate | Where it stands |
|---|---|---|
| **adopted** — the release key | `7b935169e3997f9b742c9ad87189168ab89a7fde854d0af2a335d17ca1726669` | RSA 4096, self-signed, valid until 2054-02-19, alias `mushrea-code`, PKCS#12. **Not in this repository, and never will be** - the encrypted copy on `signing-handoff-1` is a delivery envelope, and the plaintext lives with the owner. Generated inside CI run `37174670023`, which also produced the first signed APKs (github and fdroid, `1.2.26` / versionCode 65). The owner confirmed (2026-10-04) that the previous key is not in his possession |
| retired | `f036e07002d8c2e6a5a64000f1211398d4831ff37cf280456a9a26d2f12617df` | recorded earlier in this file as extracted from a release APK; it appears in no tag and no release of this repository, and the private key is not recoverable. Nothing can be signed with it again, so it must not be left in the F-Droid metadata: an app already installed from that identity cannot be updated and needs a reinstall |

Replacing the pin is unconditional rather than optional only because nothing was ever published from
this repository: no tag and no GitHub release exists, so no user's copy is bound to the retired
fingerprint by anything this repository produced. Had a release been published, the old key would have
had to stay in use instead.

This key was generated inside a GitHub Actions run (`37174670023`) rather than on a workstation,
because the four repository secrets could not be created by the account that maintains this branch
(403 on `actions/secrets`) and the owner had no way to run a workflow by hand. The run signed both
flavours with `apksigner`, verified the result, and handed the keystore back on the branch
`signing-handoff-1` - encrypted under a random AES-256 key, itself wrapped with RSA-OAEP-SHA256 to a
one-time public key. Nothing sensitive was ever committed: the branch carries ciphertext, the
certificate, the signed APKs, and a fingerprint. The one-time transport key was destroyed once the
hand-back had been opened, so that envelope is archival - the plaintext keystore is held by the owner,
who was told to keep it in two places.

Two earlier keys were generated on 2026-10-04 and lost when the working environment was recycled,
before either had signed anything (`876b116e...`, `54adbcc2...`; both are recorded in the development
log). That was survivable *only* because no release had been published - the window this identity
still sits in, which closes the moment a release ships. From then on the keystore is unrecoverable
data and belongs in at least two places; the CI secret is a copy, not a backup.

Both workflows print the signer fingerprint on every run (`apksigner verify --print-certs`, in the
job log and the run summary), so the value can be compared instead of trusted.

The guard itself is exercised twice: run `37173096811` (push, `.sign-release-request`) reached the
secret check and stopped there with a per-secret error, leaving the build, verification and upload
steps skipped - a run without secrets cannot produce an APK at all, signed or otherwise - while run
`37174670023` built both flavours with a keystore generated in the job, and `apksigner verify` on the
runner plus an independent reading of the APK signing blocks agreed on the certificate.

Until the four secrets exist, `.github/workflows/sign-release-runner-key.yml` is the way to build a
signed APK: it needs no secrets, generates a key per run, and hands it back encrypted. Its recipient
key is single-use - replace `RECIPIENT_PUBLIC_KEY` before running it again - and once the secrets hold
the adopted keystore, `sign-release.yml` is the normal path, because every future build must carry the
*same* identity as the installed app.

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

AllowedAPKSigningKeys: 7b935169e3997f9b742c9ad87189168ab89a7fde854d0af2a335d17ca1726669

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
