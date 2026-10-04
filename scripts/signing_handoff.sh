#!/usr/bin/env bash
# Transport a first-release identity without exposing the keystore or password in Git/artifacts.
# OpenSSL CMS AuthEnvelopedData: AES-256-GCM, with its key wrapped using RSA-OAEP-SHA256.
set -euo pipefail
umask 077

usage() {
  echo "usage: $0 seal PRIVATE_DIR RECIPIENT_CERT OUTPUT.cms [FILE ...]" >&2
  echo "   or: $0 open INPUT.cms RECIPIENT_CERT RECIPIENT_KEY EMPTY_OUTPUT_DIR" >&2
  exit 2
}

[ "$#" -ge 1 ] || usage
MODE="$1"
shift
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

case "$MODE" in
  seal)
    [ "$#" -ge 3 ] || usage
    PRIVATE_DIR="$1"
    RECIPIENT_CERT="$2"
    OUTPUT="$3"
    shift 3
    if [ "$#" -eq 0 ]; then
      FILES=(mushrea-code-release.p12 KEYSTORE-PASSWORD.txt)
    else
      FILES=("$@")
    fi
    [ ! -e "$OUTPUT" ] || { echo 'refusing to overwrite an existing envelope' >&2; exit 1; }
    for NAME in "${FILES[@]}"; do
      [[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "unsafe private file name: $NAME" >&2; exit 1; }
      [ -f "$PRIVATE_DIR/$NAME" ] && [ ! -L "$PRIVATE_DIR/$NAME" ] && [ -s "$PRIVATE_DIR/$NAME" ] \
        || { echo "missing/non-regular private file: $NAME" >&2; exit 1; }
    done
    tar -C "$PRIVATE_DIR" -cf "$TMP/identity.tar" "${FILES[@]}"
    openssl cms -encrypt -binary -aes-256-gcm -outform DER \
      -recip "$RECIPIENT_CERT" -keyopt rsa_padding_mode:oaep -keyopt rsa_oaep_md:sha256 \
      -in "$TMP/identity.tar" -out "$TMP/identity.cms"
    cp "$TMP/identity.cms" "$OUTPUT"
    ;;
  open)
    [ "$#" -eq 4 ] || usage
    INPUT="$1"
    RECIPIENT_CERT="$2"
    RECIPIENT_KEY="$3"
    DEST="$4"
    # GCM authentication must succeed BEFORE the tar is read or extracted.
    openssl cms -decrypt -binary -inform DER -recip "$RECIPIENT_CERT" -inkey "$RECIPIENT_KEY" \
      -in "$INPUT" -out "$TMP/identity.tar"
    python3 - "$TMP/identity.tar" "$DEST" <<'PY'
import os
import sys
import tarfile
from pathlib import Path

archive, destination = sys.argv[1:]
dest = Path(destination)
if dest.is_symlink() or (dest.exists() and (not dest.is_dir() or any(dest.iterdir()))):
    raise SystemExit("destination must be an empty directory, not a symlink")
with tarfile.open(archive, "r:") as tar:
    members = tar.getmembers()
    names = {member.name for member in members}
    if len(members) != 2 or any(
        "/" in name or "\\" in name or name.startswith(".") or not name.replace("_", "a").replace("-", "a").replace(".", "a").isalnum()
        for name in names
    ):
        raise SystemExit("envelope contains unexpected paths")
    if any(not member.isfile() or member.size == 0 or member.size > 1048576 for member in members):
        raise SystemExit("envelope contains non-regular, empty or oversized files")
    dest.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(dest, 0o700)
    for member in members:
        data = tar.extractfile(member)
        if data is None:
            raise SystemExit("could not read identity file")
        with (dest / member.name).open("xb") as out:
            out.write(data.read())
        os.chmod(dest / member.name, 0o600)
PY
    ;;
  *) usage ;;
esac
