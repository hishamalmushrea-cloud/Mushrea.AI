"""Real OpenSSL tests: no product keys, no Android SDK, no third-party Python dependencies."""

import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "signing_handoff.sh"


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required")
class SigningHandoffTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root_context = tempfile.TemporaryDirectory()
        cls.root = Path(cls.root_context.name)
        for name in ("recipient", "wrong-recipient"):
            subprocess.run(
                [
                    "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes",
                    "-days", "1", "-subj", f"/CN={name}",
                    "-keyout", str(cls.root / f"{name}.key"),
                    "-out", str(cls.root / f"{name}.crt"),
                ],
                check=True, capture_output=True,
            )

    @classmethod
    def tearDownClass(cls):
        cls.root_context.cleanup()

    def setUp(self):
        self.context = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.context.cleanup)
        self.work = Path(self.context.name)
        self.private = self.work / "private"
        self.private.mkdir()
        self.keystore = os.urandom(5000)  # opaque test bytes, NOT an actual signing identity
        (self.private / "mushrea-code-release.p12").write_bytes(self.keystore)
        (self.private / "KEYSTORE-PASSWORD.txt").write_text("test-password-only")
        (self.private / "do-not-export.txt").write_text("not part of the allowlist")
        self.envelope = self.work / "identity.cms"
        self.dest = self.work / "restored"

    def run_script(self, *args, success=True):
        result = subprocess.run(["bash", str(SCRIPT), *map(str, args)], capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def seal(self):
        return self.run_script("seal", self.private, self.root / "recipient.crt", self.envelope)

    def open_envelope(self, identity="recipient", success=True):
        return self.run_script(
            "open", self.envelope, self.root / f"{identity}.crt", self.root / f"{identity}.key",
            self.dest, success=success,
        )

    def test_roundtrip_preserves_bytes_and_exports_only_allowlist(self):
        self.seal()
        self.open_envelope()
        self.assertEqual({p.name for p in self.dest.iterdir()}, {"mushrea-code-release.p12", "KEYSTORE-PASSWORD.txt"})
        self.assertEqual((self.dest / "mushrea-code-release.p12").read_bytes(), self.keystore)
        self.assertEqual((self.dest / "KEYSTORE-PASSWORD.txt").read_text(), "test-password-only")
        self.assertEqual(self.dest.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.dest / "KEYSTORE-PASSWORD.txt").stat().st_mode & 0o777, 0o600)

    def test_ciphertext_and_logs_do_not_contain_private_bytes(self):
        result = self.seal()
        content = self.envelope.read_bytes() + result.stdout + result.stderr
        self.assertNotIn(b"test-password-only", content)
        self.assertNotIn(self.keystore[:64], content)

    def test_wrong_recipient_is_rejected_without_extraction(self):
        self.seal()
        self.open_envelope("wrong-recipient", success=False)
        self.assertFalse(self.dest.exists())

    def test_tampering_is_rejected_before_extraction(self):
        self.seal()
        blob = bytearray(self.envelope.read_bytes())
        blob[len(blob) // 2] ^= 1  # inside ciphertext, not merely a public metadata field
        self.envelope.write_bytes(blob)
        self.open_envelope(success=False)
        self.assertFalse(self.dest.exists())

    def test_seal_refuses_existing_envelope(self):
        self.seal()
        original = hashlib.sha256(self.envelope.read_bytes()).digest()
        self.run_script("seal", self.private, self.root / "recipient.crt", self.envelope, success=False)
        self.assertEqual(hashlib.sha256(self.envelope.read_bytes()).digest(), original)

    def test_seal_rejects_missing_password(self):
        (self.private / "KEYSTORE-PASSWORD.txt").unlink()
        self.run_script("seal", self.private, self.root / "recipient.crt", self.envelope, success=False)
        self.assertFalse(self.envelope.exists())

    def test_seal_rejects_symlink_private_file(self):
        password = self.private / "KEYSTORE-PASSWORD.txt"
        password.unlink()
        password.symlink_to(self.private / "do-not-export.txt")
        self.run_script("seal", self.private, self.root / "recipient.crt", self.envelope, success=False)

    def test_open_refuses_nonempty_destination(self):
        self.seal()
        self.dest.mkdir()
        (self.dest / "keep.txt").write_text("unchanged")
        self.open_envelope(success=False)
        self.assertEqual((self.dest / "keep.txt").read_text(), "unchanged")

    def test_authenticated_archive_path_traversal_is_rejected(self):
        archive = self.work / "malicious.tar"
        with tarfile.open(archive, "w") as tar:
            for name in ("mushrea-code-release.p12", "../KEYSTORE-PASSWORD.txt"):
                data = b"test-bytes"
                member = tarfile.TarInfo(name)
                member.size = len(data)
                tar.addfile(member, io.BytesIO(data))
        subprocess.run(
            [
                "openssl", "cms", "-encrypt", "-binary", "-aes-256-gcm", "-outform", "DER",
                "-recip", str(self.root / "recipient.crt"), "-keyopt", "rsa_padding_mode:oaep",
                "-keyopt", "rsa_oaep_md:sha256", "-in", str(archive), "-out", str(self.envelope),
            ],
            check=True, capture_output=True,
        )
        self.open_envelope(success=False)
        self.assertFalse(self.dest.exists())
        self.assertFalse((self.work / "KEYSTORE-PASSWORD.txt").exists())


if __name__ == "__main__":
    unittest.main()
