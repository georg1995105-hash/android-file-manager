"""Release identity guards and an actual age backup/recovery round trip on fixtures."""
import base64
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import create_release_identity
import release_signing as signing


class ReleaseSigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.key = cls.root / "fixture.p12"
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(cls.key), "-storetype", "PKCS12",
                        "-storepass", "fixture-password", "-keypass", "fixture-password", "-alias", "fixture",
                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Disposable Test"],
                       check=True, capture_output=True)
        cls.expected, _ = signing.certificate(cls.key, "fixture-password", "fixture")
        cls.values = {"PFM_KEYSTORE_B64": base64.b64encode(cls.key.read_bytes()).decode(),
                      "PFM_STORE_PASSWORD": "fixture-password", "PFM_KEY_PASSWORD": "fixture-password",
                      "PFM_KEY_ALIAS": "fixture"}

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.destination = Path(self.temp.name) / "restored.p12"

    def test_missing_partial_configuration_never_writes_a_key(self):
        for name in signing.SECRET_NAMES:
            values = dict(self.values)
            del values[name]
            with self.subTest(name=name), self.assertRaises(ValueError):
                signing.restore(self.destination, self.expected, values)
            self.assertFalse(self.destination.exists())

    def test_wrong_identity_and_invalid_encoding_leave_no_key(self):
        with self.assertRaises(ValueError):
            signing.restore(self.destination, "0" * 64, self.values)
        self.assertFalse(self.destination.exists())
        with self.assertRaises(ValueError):
            signing.restore(self.destination, self.expected, dict(self.values, PFM_KEYSTORE_B64="invalid!"))
        self.assertFalse(self.destination.exists())
        self.assertEqual(list(self.destination.parent.iterdir()), [])

    def test_matching_identity_is_restored_with_private_permissions(self):
        self.assertEqual(signing.restore(self.destination, self.expected, self.values), self.expected)
        self.assertEqual(self.key.read_bytes(), self.destination.read_bytes())
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o600)

    def test_existing_key_is_never_overwritten(self):
        self.destination.write_bytes(b"preserve this existing key")
        with self.assertRaises(ValueError):
            signing.restore(self.destination, self.expected, self.values)
        self.assertEqual(self.destination.read_bytes(), b"preserve this existing key")

    def test_creation_refuses_even_one_existing_release_secret(self):
        for name in signing.SECRET_NAMES:
            with self.subTest(name=name), patch.dict(os.environ, {name: "existing"}, clear=True):
                with patch.object(create_release_identity, "encrypted_backup") as encryption:
                    with self.assertRaises(RuntimeError):
                        create_release_identity.main()
                    encryption.assert_not_called()

    @unittest.skipUnless(Path("tools/release-signing.sha256").exists(), "identity not bootstrapped yet")
    def test_pinned_release_identity_blocks_regeneration_even_without_secrets(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(create_release_identity, "encrypted_backup") as encryption:
            with self.assertRaisesRegex(RuntimeError, "already pinned"):
                create_release_identity.main()
            encryption.assert_not_called()

    def test_apk_inspection_rejects_debuggable_or_wrong_certificate(self):
        self.destination.write_bytes(b"APK fixture")
        def fake_command(args, **kwargs):
            if "apksigner" in args[0]:
                return f"Signer #1: certificate SHA-256 digest: {self.expected}\n".encode()
            if "aapt2" in args[0]:
                return b"package: name='io.personal.filemanager' versionCode='10' versionName='0.9.0'\napplication-debuggable\n"
            return b""
        with patch.object(signing, "command", side_effect=fake_command):
            with self.assertRaisesRegex(ValueError, "debuggable"):
                signing.inspect(self.destination, self.expected, Path("/tools"))
            with self.assertRaisesRegex(ValueError, "certificate"):
                signing.inspect(self.destination, "0" * 64, Path("/tools"))

    @unittest.skipUnless(shutil.which("age") and shutil.which("age-keygen"), "age round trip runs in CI")
    def test_owner_encrypted_backup_can_be_restored_independently(self):
        owner = Path(self.temp.name) / "disposable-owner.txt"
        subprocess.run(["age-keygen", "-o", str(owner)], check=True, capture_output=True)
        recipient = subprocess.check_output(["age-keygen", "-y", str(owner)]).decode().strip()
        files = {"release.p12": self.key.read_bytes(), "secrets.json": b'{"fixture":"not production"}'}
        encrypted = create_release_identity.encrypted_backup(files, recipient)
        self.assertTrue(encrypted.startswith(b"age-encryption.org/v1"))
        plaintext = subprocess.run(["age", "--decrypt", "--identity", str(owner)], input=encrypted,
                                   check=True, capture_output=True).stdout
        with tarfile.open(fileobj=io.BytesIO(plaintext)) as archive:
            for name, value in files.items():
                self.assertEqual(archive.extractfile(name).read(), value)
                self.assertEqual(archive.getmember(name).mode, 0o600)


if __name__ == "__main__":
    unittest.main()
