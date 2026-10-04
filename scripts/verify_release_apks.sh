#!/usr/bin/env bash
# One measurement shared by all signing/release workflows: the pinned certificate, not file names.
# Real signature verification is performed exclusively by the official Android SDK apksigner.
set -euo pipefail

[ "$#" -eq 4 ] || { echo "usage: $0 CERT_SHA256 PUBLIC_REPORT_DIR GITHUB_APK FDROID_APK" >&2; exit 2; }
EXPECTED="${1,,}"
REPORT_DIR="$2"
GITHUB_APK="$3"
FDROID_APK="$4"
[[ "$EXPECTED" =~ ^[0-9a-f]{64}$ ]] || { echo '::error::missing/invalid pinned signing certificate'; exit 1; }
for APK in "$GITHUB_APK" "$FDROID_APK"; do
  [ -f "$APK" ] && [ -s "$APK" ] || { echo "::error::required signed APK missing: $APK" >&2; exit 1; }
done

APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
if [ -z "$APKSIGNER" ]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
  if [ -d "$SDK/build-tools" ]; then
    APKSIGNER="$(find "$SDK/build-tools" -type f -name apksigner | sort -V | tail -1)"
  fi
fi
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] \
  || { echo '::error::official apksigner is unavailable; refusing to trust APK filenames' >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for FLAVOUR in github fdroid; do
  APK="$GITHUB_APK"
  [ "$FLAVOUR" != fdroid ] || APK="$FDROID_APK"
  REPORT="$TMP/$FLAVOUR-apksigner.txt"
  # The APK's minSdk is 26: default verification can skip legacy v1 checks entirely.
  # API 23 forces an actual v1 check as well as v2/v3, without changing the app's minSdk.
  "$APKSIGNER" verify --min-sdk-version 23 --verbose --print-certs "$APK" > "$REPORT" \
    || { echo "::error::apksigner rejected $FLAVOUR APK" >&2; exit 1; }
  # Recent apksigner versions include the SDK range/scheme in verbose signer labels.
  # Multiple schemes may repeat the same certificate; distinct certificates are still rejected.
  mapfile -t SIGNERS < <(awk '/certificate SHA-256 digest:/ {print tolower($NF)}' "$REPORT" | sort -u)
  [ "${#SIGNERS[@]}" -eq 1 ] && [ "${SIGNERS[0]}" = "$EXPECTED" ] \
    || { echo "::error::$FLAVOUR signer does not match the adopted certificate" >&2; exit 1; }
  grep -qx 'Number of signers: 1' "$REPORT" \
    || { echo "::error::$FLAVOUR APK has an unexpected signer count" >&2; exit 1; }
  for SCHEME in v1 v2 v3; do
    grep -Eq "^Verified using $SCHEME scheme .*: true$" "$REPORT" \
      || { echo "::error::$FLAVOUR APK lacks verified $SCHEME signing" >&2; exit 1; }
  done
  cat "$REPORT"
done
# No success receipt is emitted until BOTH APKs pass all checks.
mkdir -p "$REPORT_DIR"
cp "$TMP/github-apksigner.txt" "$TMP/fdroid-apksigner.txt" "$REPORT_DIR/"
printf '%s\n' "$EXPECTED" > "$REPORT_DIR/signing-cert-sha256.txt"
printf 'Both release flavours match the pinned certificate: %s\n' "$EXPECTED"
