# HANDOFF — read me first (session continuity)

Written for the next Arena coding session on this repository by the previous agent.
User-facing language with the owner is **Arabic**; this file is the agent-facing digest.

## Who the user is (binding conventions)

- Project: **Mushrea Code** (`com.mushrea.code`) in repo `Mushrea.AI`, fully owned by the user.
- Deliver every phase with an **honesty report in Arabic**: what works, what is
  implemented-but-untested, what is deferred — never claim success without verification.
- User QA loop: agent ships a debug-APK artifact link + install steps → user installs,
  reports malfunctions in Arabic → agent fixes root cause and ships a fresh link.
  There is **no emulator** in the sandbox — say so when a path is only compile/unit tested.
- «أريد دمج هذه الميزات فعليًا وليس مجرد إنشاء أسماء أو واجهات» — every system must be
  wired end-to-end: firewall + bridge + MCP tool/handler + agent-context + tests.
- Inventory-before-proposing: classify existing/missing with grep evidence before coding.
- Commit messages must satisfy the repo's commitlint regex. No auto tags/releases.
  Never bypass permissions/lock screen; degrade honestly instead.
- «تابع» = explicit go-ahead on the named next roadmap item.

## Current state (end of the 3-feature mega-session)

- **GitHub `main` = `3e19b42`, fully green** (static-analysis, lint, test-and-build
  including the R8 `assembleGithubRelease`). Version **v1.2.26 / versionCode 65**
  (`.release-version` + `app/build.gradle.kts` + `ReleaseMetadataTest` must stay in sync).
- PRs #1–#5 merged. PR #1 carried the whole build; #2–#5 were CI-diagnosis + R8 fixes.
- Device agent: **88 MCP tools == 88 handlers** in
  `app/src/main/assets/scripts/mushreacode-device-mcp.py`; firewall
  `ALL 89 == AUTO 57 + confirm 32` (codec test enforces the equation).
  The Phase 1+2 on-device/Termux feature is on the branch but **not yet merged** — see
  `docs/ON_DEVICE_AGENT.md`; the counts above are the branch state.
- The three requested systems, all merged:
  1. **USB manager** — detection/classification for all kinds, MTP list/download,
     raw HID read, storage volumes, camera list, ADB/fastboot/serial bidirectional.
     Deferred (labeled honestly in-app): MTP upload, HID key decoding, camera capture.
  2. **Remote manager** — mDNS `net_browse` (7 service types), `remote_list`/`remote_download`
     for smb/ftp/webdav/scp (+ pre-existing ssh/sftp incl. `ssh_upload`). SCP uses TOFU
     pinning at `filesDir/remote/scp_known_hosts.json`. **Never tested against real
     Windows/NAS hardware** — expect real-LAN bug reports.
  3. **Network layer** — `NetworkExecutor.kt` (wifi_info, dns_lookup, net_ping,
     port_check, http_request, websocket) + `BluetoothExecutor.kt` (bt_info, bt_devices,
     bt_scan, ble_scan). Android hides SSID without location permission (handled honestly).
- Last known-good run: **36712133493** (debug artifact 11094414824, unsigned release 11095300528).
- **Signed release v1.2.26 is blocked on the user's 4 repo secrets**
  (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`). Once the user adds them: `gh workflow run release.yml -f tag=v1.2.26`.
- Roadmap queue the user has touched or approved in principle: SMS package, wake-word call
  commands, old menu items 1/2/4/6/7, SPAKE2 wireless pairing, double-consent flash.

## On-device agent + Termux bridge (branch `arena/01a0f370-mushrea-ai`, not yet merged)

The owner's proposal: the PRoot container cannot see USB (`/dev/bus/usb` is invisible there), so
the *app* process has to be the executor, with the user's Termux as a second executor for the
commands Android's Java APIs cannot express (`termux-fastboot`, the miunlock helper).

- **Docs**: [docs/ON_DEVICE_AGENT.md](docs/ON_DEVICE_AGENT.md) — two-executor table, the safety
  gates in force, the 3 phases, the Termux setup checklist, the related-file list.
- **Phase 1 (read/verify)**: `usb_mode`, `usb_diagnostics`, `fastboot_getvar_full` (token masked
  unless `reveal_token`), `payload_guard`, `safety_preflight`, `audit_export`, Read-Only mode
  (default on) + the Device Agent UI cards, mirror/remote-control view-only notices.
- **Phase 2 (Termux)**: `termux_status`, `termux_run`, `termux_fastboot_run`, `mitool_wrapper`;
  manifest has the `RUN_COMMAND` permission, the `com.termux`/`com.termux.api` `<queries>` entries
  and the `TermuxResultReceiver`.
- **Phase 3 (write path) is deliberately not built**: `TermuxCommandPolicy` refuses `stage`,
  `unlock`, `flash`, `erase`, `lock` and every `oem …` with a stated reason; no code path may call
  them until the typed confirmation + mandatory dry-run + immutable audit exist.
- **Nothing here is compile-verified yet** (no JDK/Android SDK in the dev sandbox): the PR's CI run
  is the first real build. Unit tests added: `termux/TermuxCommandPolicyTest`,
  `payload/PayloadGuardTest`, plus the extended `DeviceActionFirewallTest`/`DeviceCommandCodecTest`.
- **Not QA'd on hardware**: no emulator or device here, so the Termux round-trip, the `termux-usb`
  permission prompt and the real fastboot reads are untested by construction.

## CI survival guide (hard-won, do not relearn)

- Heavy jobs (`lint`, `test-and-build` incl. `assembleGithubRelease`) run **only on pushes
  to main**; pushes to `arena/**` run `auto-format` only. To see a full build: open a small
  PR from `arena/...` and merge it, then watch the main run.
- **Never select runs with `--limit 1`** — the bot's spotless run races yours. Pick runs by
  head SHA: `gh run list -w android.yml --jq '[.[] | select(.headSha | startswith("SHA"))][0].databaseId'`.
- The bot pushes `style: apply spotless formatting` commits. Adopt recipe:
  `git fetch origin <branch>` → `git reset --mixed FETCH_HEAD` → `git status`/`git diff`
  **must** show formatting-only changes → recommit. Never `git checkout -- .` after a reset.
- Job logs are **not retrievable by the bot account** (results receiver EOF / 401). Read
  failures via the check-runs **annotations** API; the workflow has dedicated steps that
  print compile/lint/test/R8 errors as annotations ("Surface release build failures").
- ktlint 1.2.1 through spotless **oscillates** on `multiline-expression-wrapping` and
  `function-signature`; both are disabled in `.editorconfig` on purpose. Do not re-enable.
  Prefer hand-wrapping long lines and hoisting `@Suppress`/`@SuppressLint` onto the class.
- R8 (`proguard-rules.pro`): `-dontwarn` families already added for `org.ietf.jgss`,
  `javax.el`, `sun.security.x509`. When R8 reports more missing classes, verify they are
  unreachable-from-Android library paths and add `-dontwarn <package>.**` in the same style.
- **Bouncy Castle on Android:** the 1.79 jars (`bcprov`, `bcpkix`, `bcutil`) each ship
  `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, and AGP's Java-resource merge fails on the duplicate
  ("3 files found with path ...") — that path is excluded in `app/build.gradle.kts` packaging. The
  three modules must resolve to one version (constraints + a CI gate assert this); `bcpkix/bcutil`
  <= 1.78 carry CVE-2025-8916. `-dontwarn sun.security.x509.**` is required by
  `net.i2p.crypto.eddsa`'s published bytecode — do not remove it on the strength of a source search.
- **Sandbox gremlin:** the local checkout sometimes re-parents HEAD to `3ae40fc` (Initial
  commit) with all work sitting as uncommitted changes. The working tree stays intact.
  Recovery: `git fetch` + `git reset --mixed origin/main` when the network allows; otherwise
  keep carrying the tree and recommit.
- Kotlin/Android traps hit in this repo (do not rediscover): `JSONArray` has no
  (Collection, lambda) ctor; `runCatching{}.getOrElse{}` with an inner lambda breaks
  inference (use try/catch); `runCatching{}.getOrDefault(null)` breaks (use `getOrNull()`);
  `smbj` → `SMBClient()` no-arg + `connection.authenticate(...)`, `fileAttributes` is a
  `long` mask; `sshj` → `newSCPFileTransfer().download(path, dir)`; `FTPFile.timestamp` is a
  `Calendar`; MTP `getObjectHandles` returns `IntArray?` (use `getOrNull`), file date is
  `Long`; NSD `onServiceLost(serviceInfo)` takes ONE arg on this SDK; BLE
  `startScan(null, settings, callback)`; `STATE_BLE_ON` is hidden → literal `15`;
  receivers unregister via `context.unregisterReceiver(...)`; manifest needs
  `ACCESS_COARSE_LOCATION` beside FINE (lint `CoarseFineLocation`); imports must be
  alphabetized for spotless.

## First actions for the new session

1. Read this file fully.
