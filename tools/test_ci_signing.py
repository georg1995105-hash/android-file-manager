"""Signing regression checks using disposable keys and mocked Android tools."""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / "tools", self.root / "tools")
        (self.root / "app").mkdir()
        shutil.copy(ROOT / "app/build.gradle.kts", self.root / "app/build.gradle.kts")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, HOME=str(self.root), PFM_DEBUG_KEYSTORE_B64="",
                        PATH=f"{self.bin}:{os.environ['PATH']}",
                        GITHUB_REPOSITORY="fixture/repo", DEVICE="phone")
        self.pin = (self.root / "tools/debug-signing.sha256").read_text().strip()

    def run_script(self, name):
        return subprocess.run(["bash", f"tools/{name}.sh"], cwd=self.root,
                              env=self.env, capture_output=True, text=True)

    def executable(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/bash\nset -eu\n" + body)
        path.chmod(0o700)
        return str(path)

    def test_missing_or_invalid_secret_preserves_existing_key(self):
        key = self.root / ".android/debug.keystore"
        key.parent.mkdir()
        key.write_bytes(b"existing fixture")
        for secret in ("", "invalid base64!"):
            with self.subTest(secret=secret):
                self.env["PFM_DEBUG_KEYSTORE_B64"] = secret
                self.assertNotEqual(self.run_script("restore_debug_keystore").returncode, 0)
                self.assertEqual(key.read_bytes(), b"existing fixture")

    def test_wrong_certificate_rejected_and_matching_key_restored(self):
        fixture = self.root / "fixture.jks"
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(fixture),
                        "-storepass", "android", "-keypass", "android", "-alias",
                        "androiddebugkey", "-dname", "CN=Disposable CI Test",
                        "-keyalg", "RSA", "-validity", "1"], check=True, capture_output=True)
        self.env["PFM_DEBUG_KEYSTORE_B64"] = base64.b64encode(fixture.read_bytes()).decode()
        key = self.root / ".android/debug.keystore"
        key.parent.mkdir()
        key.write_bytes(b"existing fixture")
        self.assertNotEqual(self.run_script("restore_debug_keystore").returncode, 0)
        self.assertEqual(key.read_bytes(), b"existing fixture")
        cert = subprocess.check_output(["keytool", "-exportcert", "-keystore", str(fixture),
                                        "-storepass", "android", "-alias", "androiddebugkey"])
        (self.root / "tools/debug-signing.sha256").write_text(hashlib.sha256(cert).hexdigest())
        self.assertEqual(self.run_script("restore_debug_keystore").returncode, 0)
        self.assertEqual(key.read_bytes(), fixture.read_bytes())
        self.assertEqual(key.stat().st_mode & 0o777, 0o600)

    def test_bad_apk_signatures_stop_before_adb(self):
        self.executable("gh", 'if [ "$2" = list ]; then echo v0.8.0-debug-build22; '
                        'else mkdir -p prev-apk; touch prev-apk/old.apk; fi\n')
        self.executable("adb", 'touch adb-called; exit 99\n')
        self.env["APK_SIGNER"] = self.executable(
            "apksigner", 'if [[ "$3" == *"$BAD_APK"* ]]; then echo "certificate SHA-256 digest: wrong"; '
            f'else echo "Signer #1: certificate SHA-256 digest: {self.pin}"; fi\n')
        for apk in ("universal", "androidTest", "old.apk"):
            with self.subTest(apk=apk):
                self.env["BAD_APK"] = apk
                result = self.run_script("device_tests")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Signing certificate mismatch", result.stderr)
                self.assertFalse((self.root / "adb-called").exists())

    def test_missing_previous_release_is_failure_before_adb(self):
        self.executable("gh", "exit 1\n")
        self.executable("adb", "touch adb-called\n")
        self.assertNotEqual(self.run_script("device_tests").returncode, 0)
        self.assertFalse((self.root / "adb-called").exists())


if __name__ == "__main__":
    unittest.main()
