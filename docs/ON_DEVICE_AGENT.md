# On-Device Agent (USB + Termux bridge)

This document describes the flashing-adjacent half of the Device Agent: reading a phone in
bootloader mode, judging whether a ROM belongs to it, and handing allowlisted commands to the
user's Termux when the app's own USB path is not enough.

It exists because the on-device Linux runtime (PRoot) cannot see USB: `ls /dev/bus/usb` is empty
there, and `fastboot devices` lists nothing even while the app itself sees the device. The app's
own process, not the container, is what owns USB access (USB Host permission) — so the app is the
executor, and Termux is a second executor for the commands Android's Java APIs cannot express
(`termux-fastboot` and the Mi unlock helper).

## Two executors

| | App-native path | Termux bridge |
|---|---|---|
| Transport | `UsbManager` + `FastbootAgent` (USB class `0xFF`, subclass `0x42`, protocol `0x03`) | `com.termux.RUN_COMMAND` intent to Termux's `RunCommandService` |
| Used for | reading identity (`getvar`), diagnostics, ADB/MTP work | `termux-fastboot` (USB file descriptor via `termux-usb`), the miunlock helper |
| Needs | USB permission per attach | Termux + Termux:API installed, RUN_COMMAND permission granted, `allow-external-apps=true` |
| Speed | fast, stays connected | slower: `termux-fastboot` has no daemon and re-scans USB per invocation |

Reads stay on the app-native path by default. Nothing in this feature requires Termux to be
installed; without it the tools degrade with a message that names exactly what is missing.

## Safety gates in force today

- **Read-Only by default.** `DeviceActionFirewall.READ_ONLY_ACTIONS` is the allowlist; every other
  action is refused by the bridge until the user turns the switch off, and each refusal says so.
- **No destructive bootloader command exists yet.** `TermuxCommandPolicy` refuses `flash`,
  `flashall`, `erase`, `format`, `stage`, `lock`, `unlock`, `update`, `set_active` — and every
  `oem …` command — with a human-readable reason. Wiring those is the write phase below.
- **The token is a secret.** `fastboot_getvar_full` masks it unless `reveal_token` is explicitly
  true, `TermuxCommandPolicy` allowlists `getvar token` but the same decision text pins down that
  it is never written to the activity log, the audit log, or anywhere off the phone, and the
  command-level summary never contains it.
- **Codename mismatch blocks.** `PayloadGuard` compares the ROM's declared codename
  (`META-INF/com/android/metadata` `pre-device`, the updater-script assertion, or the
  `sky_global_images_…` folder of a fastboot ROM) against the attached device: `sky` never flashes
  a `flourite` archive, and the mismatch sets `blocking`.
- **No forged signatures, no shortcuts.** The official Xiaomi unlock is the only path. The app does
  not sign tokens, does not bypass the 72 h/168 h waiting period, and refuses MediaTek's
  `oem get_token` on a Qualcomm device rather than mixing toolchains.
- **Honest limits.** `safety_preflight` returns *reminders* (backup persist/nvram, unlock wipes
  userdata, the emergency stop cannot cancel a command the bootloader already received) separately
  from *checks* it can actually verify, so a green list is never mistaken for a guarantee.

## Phases

1. **Read/verify (this build).** USB classification, one diagnostics page, `payload_guard`,
   `safety_preflight`, `fastboot_getvar_full`, Read-Only mode, audit-log export, the token rules.
2. **Termux bridge (this build).** Termux/permission/`allow-external-apps` detection with a probe,
   the RUN_COMMAND command-and-result plumbing, `termux_fastboot_run`, `mitool_wrapper`
   (status/install/help of the termux-miunlock checkout).
3. **Write phase (not built).** `stage`/`oem unlock`/`flash`/`erase`/`lock` behind the typed
   codename confirmation, a mandatory dry-run, the risk acknowledgment and the immutable audit log.
   The policy's destructive list is the checklist for what this phase must gate.

## Termux setup checklist

1. Install **Termux** (F-Droid or GitHub — not the Play Store build) and **Termux:API**.
2. In Termux, set `allow-external-apps=true` in `~/.termux/termux.properties` and run
   `termux-reload-settings`.
3. In Android's App info for Termux → *Additional permissions*, grant
   **Run commands in Termux environment**.
4. For USB work install `termux-api` and the patched `termux-fastboot` from
   [nohajc/termux-adb](https://github.com/nohajc/termux-adb). Stock `android-tools` fastboot cannot
   see devices without root and will only return empty results.
5. Termux asks for USB permission per device and again after a reconnect or a Termux restart; the
   bridge reports the refusal instead of pretending the device is gone.

## Related files

- `device/termux/TermuxCommandPolicy.kt` — the default-deny command gate (JVM-pure, unit-tested)
- `device/termux/TermuxBridge.kt`, `TermuxExecutor.kt`, `TermuxMiunlock.kt`, `TermuxResultReceiver.kt`
- `device/payload/PayloadGuard.kt` — the ROM codename/region/integrity guard (JVM-pure, unit-tested)
- `device/DeviceSafetyPreflight.kt`, `device/DeviceAuditLog.kt`
- `device/usb/UsbExecutor.kt` — `usb_mode`, `usb_diagnostics`, `fastboot_getvar_full`
