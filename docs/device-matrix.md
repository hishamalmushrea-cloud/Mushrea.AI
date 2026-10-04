# Device validation matrix

Add a row for every device / API combination validated before tagging a public
stable release.

**Correction (Phase 2):** no workflow executes instrumentation. `android.yml` compiles
`assembleGithubDebugAndroidTest` only, so no CI row below has ever run `connectedDebugAndroidTest`
— the two emulator rows claiming "CI green" were wrong and are marked as such. The first real CI
run of instrumented tests has to be added deliberately (documented as an open problem in
`docs/development/BASELINE.md`).

| Device | API | ABI | Result | Date | Notes |
|--------|-----|-----|--------|------|-------|
| GitHub Actions x86_64 emulator | 26 | x86_64 | Never run | | `connectedDebugAndroidTest` is not wired into any workflow (compile-only today) |
| GitHub Actions x86_64 emulator | 34 | x86_64 | Never run | | `connectedDebugAndroidTest` is not wired into any workflow (compile-only today) |
| API 36 ARM64 emulator | 36 | arm64-v8a | Local smoke passed | 2026-07-19 | local runtime install + chat + diagnostics + delete |
| Xiaomi Android 16 (HyperOS) physical | 36 | arm64-v8a | Pending | | capture FGS battery + OEM-kill behavior |
| Pixel 8 physical | 34 | arm64-v8a | Pending | | |
| Older API 26 physical (e.g. Pixel 2) | 26 | arm64-v8a | Pending | | |

## Battery benchmark results

| Device | Idle drain (%, 30 min) | Chat drain (%, 5 min) | PRoot RSS (MB) | Runtime disk (MB) |
|--------|------------------------|-----------------------|----------------|-------------------|
| (fill from `scripts/battery_benchmark.sh`) | | | | |

Append new rows per release; keep historical data to spot regressions.
