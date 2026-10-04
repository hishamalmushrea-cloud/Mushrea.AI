"""Guard tests use an apksigner CLI stub, not a claim of Android signature verification."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "verify_release_apks.sh"
CERT = "ab" * 32
OTHER_CERT = "cd" * 32


class VerifyReleaseApksTest(unittest.TestCase):
    def setUp(self):
        self.context = tempfile.TemporaryDirectory()
        self.addCleanup(self.context.cleanup)
        self.root = Path(self.context.name)
        self.github = self.root / "github.apk"
        self.fdroid = self.root / "fdroid.apk"
        for apk in (self.github, self.fdroid):
            apk.write_bytes(b"test input, NOT an APK")
            Path(str(apk) + ".report").write_text(self.report(CERT))
        self.output = self.root / "verified"
        self.apksigner = self.root / "apksigner"
        self.apksigner.write_text(
            '#!/usr/bin/env bash\nset -euo pipefail\n'
            '[ "$1" = verify ] && [ "$2" = --min-sdk-version ] && [ "$3" = 23 ]\n'
            'cat "${@: -1}.report"\n'
        )
        self.apksigner.chmod(0o700)

    @staticmethod
    def report(cert, signer_count=1, v3=True):
        return (
            "Verifies\n"
            "Verified using v1 scheme (JAR signing): true\n"
            "Verified using v2 scheme (APK Signature Scheme v2): true\n"
            f"Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}\n"
            f"Number of signers: {signer_count}\n"
            f"Signer #1 certificate SHA-256 digest: {cert}\n"
        )

    def verify(self, expected=CERT, success=False):
        env = dict(os.environ, APKSIGNER=str(self.apksigner))
        result = subprocess.run(
            ["bash", str(SCRIPT), expected, str(self.output), str(self.github), str(self.fdroid)],
            env=env, capture_output=True,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stderr.decode())
        else:
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.output.exists(), "failure must not produce a success receipt")
        return result

    def test_same_pinned_certificate_emits_both_reports(self):
        self.verify(success=True)
        self.assertEqual((self.output / "signing-cert-sha256.txt").read_text(), CERT + "\n")
        self.assertTrue((self.output / "github-apksigner.txt").is_file())
        self.assertTrue((self.output / "fdroid-apksigner.txt").is_file())

    def test_same_wrong_certificate_on_both_flavours_is_rejected(self):
        for apk in (self.github, self.fdroid):
            Path(str(apk) + ".report").write_text(self.report(OTHER_CERT))
        self.verify()

    def test_different_flavour_certificate_is_rejected(self):
        Path(str(self.fdroid) + ".report").write_text(self.report(OTHER_CERT))
        self.verify()

    def test_missing_fdroid_apk_is_rejected(self):
        self.fdroid.unlink()
        self.verify()

    def test_multiple_signers_are_rejected(self):
        Path(str(self.fdroid) + ".report").write_text(self.report(CERT, signer_count=2))
        self.verify()

    def test_extra_certificate_digest_is_rejected(self):
        Path(str(self.fdroid) + ".report").write_text(
            self.report(CERT) + f"Signer #2 certificate SHA-256 digest: {OTHER_CERT}\n"
        )
        self.verify()

    def test_missing_v3_is_rejected(self):
        Path(str(self.fdroid) + ".report").write_text(self.report(CERT, v3=False))
        self.verify()

    def test_failed_official_verifier_is_rejected(self):
        self.apksigner.write_text("#!/usr/bin/env bash\nexit 1\n")
        self.verify()

    def test_verbose_sdk_range_and_repeated_same_certificate_are_supported(self):
        for apk in (self.github, self.fdroid):
            report = self.report(CERT).replace(
                "Signer #1 certificate", "V3.0 Signer: certificate"
            )
            report += f"Signer #1 in APK Signature Scheme v3 certificate SHA-256 digest: {CERT}\n"
            Path(str(apk) + ".report").write_text(report)
        self.verify(success=True)

    def test_missing_pin_is_rejected(self):
        self.verify(expected="")

    def test_uppercase_pin_is_normalized(self):
        self.verify(expected=CERT.upper(), success=True)


if __name__ == "__main__":
    unittest.main()
