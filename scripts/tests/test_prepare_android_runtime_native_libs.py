"""The jniLibs copy must ship libtalloc even when Termux versions the filename."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "prepare_android_runtime_native_libs.py"


def load_module():
    spec = importlib.util.spec_from_file_location("prepare_android_runtime_native_libs", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PrepareAndroidRuntimeNativeLibsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.context = tempfile.TemporaryDirectory()
        self.addCleanup(self.context.cleanup)
        self.root = Path(self.context.name)
        self.assets = self.root / "assets"
        self.output = self.root / "jni"
        for abi in self.module.ANDROID_ABIS:
            prefix = self.assets / "opencode-runtime" / abi / "prefix"
            (prefix / "bin").mkdir(parents=True)
            (prefix / "libexec" / "proot").mkdir(parents=True)
            (prefix / "lib").mkdir(parents=True)
            blob = (
                b"ELF\0libtalloc.so.2\0"
                b"libtalloc.so.2.5.0\0"
                b"/data/data/com.termux/files/usr/lib\0"
                b"libandroid-shmem.so\0"
            )
            (prefix / "bin" / "proot").write_bytes(blob)
            (prefix / "libexec" / "proot" / "loader").write_bytes(blob)
            (prefix / "libexec" / "proot" / "loader32").write_bytes(blob)
            (prefix / "lib" / "libtalloc.so.2.5.0").write_bytes(b"talloc-2.5.0")
            (prefix / "lib" / "libandroid-shmem.so").write_bytes(b"shmem")

    def test_copies_versioned_talloc_as_unversioned_jni_name(self):
        self.module.prepare_native_libs(self.assets, self.output)
        for abi in self.module.ANDROID_ABIS:
            talloc = self.output / abi / "libtalloc.so"
            self.assertTrue(talloc.is_file(), abi)
            self.assertEqual(talloc.read_bytes(), b"talloc-2.5.0")
            shmem = self.output / abi / "libandroid-shmem.so"
            self.assertTrue(shmem.is_file(), abi)
            proot = self.output / abi / "libopencode_android_proot.so"
            payload = proot.read_bytes()
            self.assertNotIn(b"libtalloc.so.2\0", payload)
            self.assertNotIn(b"libtalloc.so.2.5.0\0", payload)
            self.assertIn(b"libtalloc.so\0", payload)
            self.assertNotIn(b"/data/data/com.termux/files/usr/lib\0", payload)
            self.assertIn(b"$ORIGIN\0", payload)

    def test_missing_talloc_fails_the_build(self):
        for abi in self.module.ANDROID_ABIS:
            (self.assets / "opencode-runtime" / abi / "prefix" / "lib" / "libtalloc.so.2.5.0").unlink()
        with self.assertRaises(FileNotFoundError) as raised:
            self.module.prepare_native_libs(self.assets, self.output)
        self.assertIn("libtalloc.so", str(raised.exception))

    def test_legacy_2_4_3_filename_still_copies(self):
        for abi in self.module.ANDROID_ABIS:
            lib = self.assets / "opencode-runtime" / abi / "prefix" / "lib"
            (lib / "libtalloc.so.2.5.0").unlink()
            (lib / "libtalloc.so.2.4.3").write_bytes(b"talloc-2.4.3")
        self.module.prepare_native_libs(self.assets, self.output)
        talloc = self.output / "arm64-v8a" / "libtalloc.so"
        self.assertEqual(talloc.read_bytes(), b"talloc-2.4.3")


if __name__ == "__main__":
    unittest.main()
