# FULL_PROJECT_AUDIT_REPORT

Date: 2026-10-04  
Branch: `arena/01a1073e-mushrea-ai`  
HEAD at report time: this commit (audit fixes on top of `9ce944b`)

This is an honesty report. Nothing below is claimed as device-proven unless marked **Verified**.

## 1. Executive Summary

Mushrea Code is a single-module Android app (`:app`, plus `:benchmark`) that hosts coding agents (OpenCode, Claude Code, Codex, Antigravity) inside an on-device PRoot/Alpine environment, plus a Device Agent (accessibility, USB, MTP, calls, network).

Architecture is real and enforced: `core < data < runtime < device < feature < ui`, gated by `scripts/check_architecture.py` (**Verified**: check exits 0, 426 sources, 5 scheduled exceptions).

The app is **not** “ready to ship to millions” in this environment’s meaning of the phrase:

- Last public signed build is `v1.2.26`. Repository Actions **signing secrets are missing**, so a same-key update cannot be produced from here (**Verified** on run 37222549251).
- On-device OpenCode install crashed because `libtalloc.so` was omitted from jniLibs (**Verified** from user log; **fixed** in `9ce944b`, not re-run on a phone here).
- Instrumented UI tests (23) have never been run in this sandbox. There is no emulator/JDK here.

This pass did **not** rewrite the architecture. It fixed concrete P0/P1 defects found by reading the code, then added unit tests for those defects.

## 2. Project Architecture

```
core  <  data  <  runtime  <  device  <  feature  <  ui
startup / application root = composition root
```

| Area | Reality |
|---|---|
| Modules | `:app` (application), `:benchmark` (macrobenchmark). No extra feature modules. |
| Flavors | `github` (Firebase), `fdroid` (no Play services; `-Pmushreacode.fdroidBuild=true`) |
| min/target/compile | 26 / 35 / 35 |
| ABI | `arm64-v8a`, `x86_64` only |
| UI | Jetpack Compose, Material 3 |
| Persistence | Encrypted settings / files; Room was removed (architecture docs). |
| Networking | OkHttp profiles in `HttpClients` (api / download / short). Cleartext allowed in NSC for LAN; gated in `OpenCodeUrl`. |
| Runtime | PRoot + Alpine in app-private dir; OpenCode downloaded on setup, not on cold start. |
| Device tools | Catalog 105 / agent 103 / bridge 104 (**Verified** by `check_tool_catalog.py`) |
| CI | Android CI on PR: lint, static-analysis (architecture/catalog/permission + script tests), test-and-build. Push to `arena/**` is auto-format only. |

Entry points: `MainActivity`, `DeviceAgentActivity` (second launcher), share target, voice interaction, accessibility service, widgets, call/record/runtime/schedule/wake-word foreground services.

## 3. Issues Found

| ID | Severity | Category | Problem | Status |
|---|---|---|---|---|
| A1 | P0 | Runtime | `libtalloc.so` missing; PRoot `CANNOT LINK EXECUTABLE`; UI called it a network timeout | Fixed in `9ce944b` (prior commit). **Not device-retested here.** |
| A2 | P1 | Security | `DeviceFileAgent` used `path.startsWith(root.path)` so `/storage/emulated/0-evil` could pass | **Fixed** this commit + unit tests |
| A3 | P1 | Correctness | `copyFile` treated `IOException` as success (`getOrElse { it is IOException }`) | **Fixed** this commit |
| A4 | P1 | Security | Guest WebView: JS on, file access defaults true on API 26–29, any http(s) URL allowed | **Fixed** this commit + unit tests |
| A5 | P1 | Crash | `IncomingCallReceiver` called `startForegroundService` without catching Android 12+ background refusal | **Fixed** this commit. **Not device-tested.** |
| A6 | P1 | Ops | Four `RELEASE_*` GitHub secrets absent; signed APK cannot be built | **Unfixed** (needs owner). Not a code change. |
| A7 | P2 | Honesty | Agent-context blurb still said MTP upload / HID / camera were “later” | Fixed in `cb30b40` |
| A8 | P2 | Testing | 23 `androidTest` classes never run here | Remaining |
| A9 | P2 | Compatibility | Android 15 edge-to-edge / 16 KB page size for bundled native libs | **Unverified** |
| A10 | P2 | Product | Destructive fastboot (`flash`/`unlock`/`erase`) refused by design | Remaining (intentional) |
| A11 | P2 | Product | Call recording is near-end microphone only | Remaining (honest limitation) |
| A12 | P3 | Hygiene | `.gitignore` lists `*.pem` / `.signing-handoff/` but tracked copies exist | Remaining (public certs; not deleted) |
| A13 | P3 | UX | `StopAgentActivity` is `exported=true` so any app can request agent stop | Remaining (shortcut may need export) |
| A14 | P3 | Network | NSC `cleartextTrafficPermitted=true` globally; app-layer gate exists | Remaining (documented constraint of IP LAN) |
| A15 | P4 | Architecture | 5 approved upward imports (voice/settings ports) | Remaining, scheduled in ARCHITECTURE.md |

## 4. Fixes Applied

### A1 — PRoot / libtalloc (previous commit `9ce944b`)

- **Problem:** Install failed with `library "libtalloc.so" not found`.
- **Root cause:** `prepare_android_runtime_native_libs.py` copied `libtalloc.so.2.4.3` optionally; lock pins Termux **2.5.0**, so the file was skipped. DT_NEEDED was already patched to `libtalloc.so`.
- **Change:** Glob the real `libtalloc.so*`, fail the build if missing, patch RPATH to `$ORIGIN`, stop calling linker failures a timeout.
- **Tests:** `scripts/tests/test_prepare_android_runtime_native_libs.py` **Verified** locally (3 tests OK). PR Android CI for `9ce944b` was green (**Verified**).
- **Risk:** Needs a new APK on the phone. Debug APK ≠ `v1.2.26` signature.

### A2 — Path prefix

- **Problem:** `"/storage/emulated/0-evil".startsWith("/storage/emulated/0")` is true.
- **Root cause:** Prefix check without a directory separator. Other code (`RuntimeArchive`, `DeviceStorage`) already used `root + File.separator`.
- **Change:** `isUnderDirectory()`; search/move/copy use canonical paths.
- **Tests:** `DeviceFilePathTest` (JVM). **Not** run via Gradle here (no JDK).

### A3 — copyFile

- **Problem:** Failed copy returning `true` when the throwable was `IOException`.
- **Root cause:** `getOrElse { it is IOException }` used a type check as the fallback value.
- **Change:** `getOrDefault(false)`. Byte-count verification still remains as a second check.

### A4 — Guest WebView

- **Problem:** In-app “guest” browser could open the public internet, `file://`, `javascript:`; file-access defaults on API 26–29.
- **Change:** Allow only hosts `OpenCodeUrl.isTrustedCleartextHost` accepts; disable file/content access; block mixed content; intercept navigation.
- **Tests:** `GuestBrowserUrlTest`. **WebView runtime Unverified.**

### A5 — Incoming call FGS

- **Problem:** Background `PHONE_STATE` → `startForegroundService` can throw on API 31+ and kill the process.
- **Change:** `runCatching { startForegroundService(...) }` — fail closed (do not answer) instead of crashing.
- **Runtime Unverified.**

## 5. Security Findings

Already in good shape (spot-checked, not a pentest):

- `allowBackup=false`, data extraction rules present.
- FileProvider `exported=false`, `grantUriPermissions=true`, external-path only.
- Accessibility / voice services require platform bind permissions.
- `SecretRedaction` on logs; `OpenCodeUrl` rejects public cleartext HTTP.
- Debug WebView debugging is `BuildConfig.DEBUG` only.
- No private key material printed in this report. Firebase `google-services.json` is a **client** config (normal to ship); treat as public-ish, restrict by SHA in Firebase console (**Unverified** that the Firebase app restriction is set).
- Encrypted backup / keystore files are gitignored; do not publish plaintext P12.

Fixed this pass: path prefix (A2), guest WebView (A4).

Not a code secret: GitHub Actions `RELEASE_*` secrets are **absent** (A6). Owner must restore them for a signed upgrade over 1.2.26.

## 6. Performance Findings

No new performance rewrite. Observations:

- Shared OkHttp pools (**good**).
- Download client has no read timeout by design (large Alpine/OpenCode).
- Device file search is visit-capped (`FILE_VISIT_LIMIT = 5_000`).
- No profiler run here. **Unverified.**

## 7. Testing

| Suite | Result | Evidence |
|---|---|---|
| `scripts/check_architecture.py` | OK | Run this session, exit 0 |
| `scripts/check_tool_catalog.py` | OK | 105 / 103 / 104 |
| `scripts/check_permission_hook.py` | OK | 20 behaviours |
| `scripts/check_permission_center.py` | OK | 914 rules |
| `scripts/tests/test_prepare_android_runtime_native_libs.py` | OK | 3 tests, this session |
| Other `scripts/tests` | OK | 23 tests, prior session after talloc commit |
| `:app:testGithubDebugUnitTest` | Not run here | No JDK in sandbox |
| PR Android CI `9ce944b` | success | lint + static-analysis + test-and-build |
| This commit’s JVM tests | Not run in Gradle | Will run on PR CI after push |
| `androidTest` (23) | **Not run** | No device/emulator |
| Install/runtime of new APK | **Not run** | No phone in this environment |

## 8. Build Verification

| Artifact | Status |
|---|---|
| Local `./gradlew` | **Not testable** (no JDK/Android SDK in sandbox) |
| PR debug APK `9ce944b` | CI built `mushrea-code-debug` (~40.6 MB), run `37225897109` |
| Signed release this branch | **Failed** — secrets missing |
| AAB | Not produced this pass |
| R8/minify | Rules exist and are evidence-commented; **release APK not rebuilt here** |

## 9. Remaining Issues

- Owner must restore signing secrets (or install the debug APK after uninstalling 1.2.26).
- Re-test OpenCode setup on a physical arm64 device after installing an APK that includes `libtalloc.so`.
- Run instrumented tests on a device.
- Destructive fastboot still later-phase by policy.
- Android 15 edge-to-edge and 16 KB native alignment: do not claim support.
- `StopAgentActivity` export vs launcher shortcut: needs a product decision before changing.
- Five architecture exceptions still scheduled (voice/settings ports).

## 10. Unverified Items

- Any on-device behaviour: camera, MTP upload, HID decode, call record, incoming-call FGS, WebView, PRoot after talloc fix, process death, rotation, RTL layout on a real phone, low storage.
- Whether Firebase Crashlytics is configured with the intended app IDs.
- Whether Termux 2.5.0 `libtalloc` ELF RPATH is exactly `/data/data/com.termux/files/usr/lib` (string patch is best-effort; `$ORIGIN` + same-dir jniLibs is the intended fallback).
- Play / F-Droid store listing.

## 11. Recommendations

1. Restore the four release signing secrets, then request a signed build (same key as 1.2.26) so users do not uninstall.
2. After that APK is installed, run first-open setup on arm64 with a stable network and save the tool-install log.
3. Add a device farm / one instrumented job when a runner with an emulator exists — not in this sandbox.
4. Do not file an official F-Droid catalog MR until the talloc-bearing version is the published one.
5. Do not mint a second F-Droid signing identity.

## 12. New Discoveries (not in the original task list)

- The “network timeout” copy hid a **packaging** bug (talloc).
- `copyFile`’s `getOrElse { it is IOException }` is a boolean mix-up, not a style issue.
- Guest browser was a general browser with JS, not a loopback viewer.
- File-agent path check was weaker than the rest of the codebase’s own archive/workspace guards.
- Incoming-call path was the only FGS start from a receiver that did not already `runCatching` (runtime/wake-word/schedule already did).

## What was deliberately not done

- No architecture rewrite.
- No mass dependency upgrades.
- No Detekt/R8/lint suppression to “go green”.
- No merge/close of PRs.
- No new F-Droid key.
- No plaintext keystore in git.
- No claim that the application “works on a phone” without a phone.
