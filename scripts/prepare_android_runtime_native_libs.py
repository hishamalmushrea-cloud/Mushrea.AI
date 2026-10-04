#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import re
from pathlib import Path


ANDROID_ABIS = ("arm64-v8a", "x86_64")
NATIVE_EXECUTABLES = {
    "bin/proot": "libopencode_android_proot.so",
    "libexec/proot/loader": "libopencode_android_proot_loader.so",
    "libexec/proot/loader32": "libopencode_android_proot_loader32.so",
}
# Android's JNI packager only ships names matching lib*.so (no extra suffix). Termux ships
# versioned files such as libtalloc.so.2.5.0 and records DT_NEEDED as libtalloc.so.2.
# The lock file currently pins libtalloc 2.5.0; an older recipe used 2.4.3. Matching by
# glob (and failing the build when the real file is absent) is what stops the APK from
# shipping a PRoot that cannot start: "library libtalloc.so not found".
REQUIRED_RUNTIME_LIBRARIES = (
    ("libtalloc.so*", "libtalloc.so"),
    ("libandroid-shmem.so*", "libandroid-shmem.so"),
)
OPTIONAL_RUNTIME_LIBRARIES = (("libc++_shared.so", "libc++_shared.so"),)
NEEDED_NAME_PREFIXES = (b"libtalloc.so", b"libandroid-shmem.so")
TERMUX_LIB_RPATH = b"/data/data/com.termux/files/usr/lib"
ORIGIN_RPATH = b"$ORIGIN"
NATIVE_EXECUTABLE_SEARCH_DIRS = ("bin", "libexec")


def native_executable_name(relative_path: str) -> str:
    existing = NATIVE_EXECUTABLES.get(relative_path)
    if existing:
        return existing
    safe = re.sub(r"[^0-9A-Za-z_]+", "_", relative_path.replace("\\", "/"))
    safe = safe.strip("_") or "command"
    return f"libopencode_exec_{safe}.so"


def resolve_library(lib_dir: Path, pattern: str) -> Path | None:
    if not lib_dir.is_dir():
        return None
    matches = [path for path in lib_dir.glob(pattern) if path.is_file()]
    if not matches:
        return None
    # Prefer the real versioned file (libtalloc.so.2.5.0) over a shorter name. The asset
    # extractor does not materialize Termux's soname symlinks, so the longest regular file
    # is the implementation.
    return max(matches, key=lambda path: (len(path.name), path.name))


def patch_nul_terminated(payload: bytes, old: bytes, new: bytes) -> bytes:
    if old == new:
        return payload
    if len(new) > len(old):
        raise ValueError(f"replacement {new!r} is longer than {old!r}")
    token = old + b"\0"
    if token not in payload:
        return payload
    padded = new + (b"\0" * (len(old) - len(new)))
    return payload.replace(token, padded + b"\0")


def patch_needed_prefix(payload: bytes, prefix: bytes, replacement: bytes) -> bytes:
    """Rewrite NUL-terminated sonames that start with [prefix] to [replacement]."""
    out = bytearray(payload)
    start = 0
    while True:
        idx = payload.find(prefix, start)
        if idx < 0:
            break
        end = payload.find(b"\0", idx)
        if end < 0:
            break
        original = payload[idx:end]
        rest = original[len(prefix) :]
        if rest and not rest.startswith(b"."):
            start = end + 1
            continue
        if original != replacement:
            if len(replacement) > len(original):
                raise ValueError(
                    f"replacement {replacement!r} is longer than soname {original!r}"
                )
            out[idx:end] = replacement + (b"\0" * (len(original) - len(replacement)))
        start = end + 1
    return bytes(out)


def patch_binary(path: Path) -> None:
    if not path.is_file():
        return
    payload = path.read_bytes()
    updated = payload
    for prefix in NEEDED_NAME_PREFIXES:
        updated = patch_needed_prefix(updated, prefix, prefix)
    updated = patch_nul_terminated(updated, TERMUX_LIB_RPATH, ORIGIN_RPATH)
    if updated != payload:
        path.write_bytes(updated)


def copy_abi(linux_assets_dir: Path, output_dir: Path, abi: str) -> None:
    prefix_dir = linux_assets_dir / "opencode-runtime" / abi / "prefix"
    abi_output = output_dir / abi
    abi_output.mkdir(parents=True, exist_ok=True)
    for source_relative, destination_name in sorted(NATIVE_EXECUTABLES.items()):
        source = prefix_dir / source_relative
        if not source.is_file():
            raise FileNotFoundError(f"Required Android runtime executable missing: {source}")
        destination = abi_output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
    lib_dir = prefix_dir / "lib"
    for pattern, destination_name in REQUIRED_RUNTIME_LIBRARIES:
        source = resolve_library(lib_dir, pattern)
        if source is None:
            listing = (
                ", ".join(sorted(path.name for path in lib_dir.iterdir()))
                if lib_dir.is_dir()
                else "(missing lib directory)"
            )
            raise FileNotFoundError(
                f"Required Android runtime library {pattern} missing under {lib_dir} "
                f"(found: {listing})"
            )
        destination = abi_output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
    for pattern, destination_name in OPTIONAL_RUNTIME_LIBRARIES:
        source = resolve_library(lib_dir, pattern)
        if source is None:
            continue
        destination = abi_output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
    for binary in sorted(path for path in abi_output.iterdir() if path.is_file()):
        patch_binary(binary)


def prepare_native_libs(linux_assets_dir: Path, output_dir: Path) -> None:
    if output_dir.exists():
        for item in output_dir.rglob("*"):
            if item.is_file():
                item.unlink()
        for item in sorted((p for p in output_dir.rglob("*") if p.is_dir()), reverse=True):
            item.rmdir()
    output_dir.mkdir(parents=True, exist_ok=True)
    for abi in ANDROID_ABIS:
        copy_abi(linux_assets_dir, output_dir, abi)


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android-packaged native launcher libraries")
    parser.add_argument("--linux-assets-dir", required=True, help="Generated MushreaCode runtime assets directory")
    parser.add_argument("--output-dir", required=True, help="Generated jniLibs output directory")
    args = parser.parse_args()
    prepare_native_libs(
        linux_assets_dir=Path(args.linux_assets_dir).expanduser().resolve(),
        output_dir=Path(args.output_dir).expanduser().resolve(),
    )


if __name__ == "__main__":
    main()
