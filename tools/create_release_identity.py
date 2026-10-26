#!/usr/bin/env python3
"""One-time identity creation, owner-encrypted backup and GitHub-sealed secret values."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tarfile
import tempfile

from release_signing import SECRET_NAMES, certificate, command


def encrypted_backup(files, recipient):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size, info.mode = len(data), 0o600
            archive.addfile(info, io.BytesIO(data))
    return subprocess.run(["age", "--encrypt", "--recipient", recipient],
                          input=stream.getvalue(), capture_output=True, check=True).stdout


def main():
    # Even partial existing configuration must block new identity generation.
    if any(os.environ.get(name) for name in SECRET_NAMES):
        raise RuntimeError("Release signing already exists or is partial; refusing to create another key")
    if Path("tools/release-signing.sha256").exists():
        raise RuntimeError("A release certificate is already pinned; refusing key rotation")
    recipient = os.environ["PFM_BACKUP_RECIPIENT"]
    # Verify that age accepts the owner's recipient before generating any signing key.
    encrypted_backup({"recipient-check.txt": b"recipient validation"}, recipient)
    from nacl.public import PublicKey, SealedBox
    public_key = PublicKey(base64.b64decode(os.environ["PFM_GITHUB_PUBLIC_KEY"], validate=True))
    box = SealedBox(public_key)
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="pfm-release-identity-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        keystore = Path(temporary) / "release.p12"
        password = secrets.token_urlsafe(48)
        alias = "pfm-release"
        env = dict(os.environ, PFM_SETUP_PASSWORD=password)
        command(["keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
                 "-storepass:env", "PFM_SETUP_PASSWORD", "-keypass:env", "PFM_SETUP_PASSWORD",
                 "-alias", alias, "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
                 "-dname", "CN=Personal File Manager, OU=Personal Android"], env=env)
        fingerprint, der = certificate(keystore, password, alias)
        values = {"PFM_KEYSTORE_B64": base64.b64encode(keystore.read_bytes()).decode(),
                  "PFM_STORE_PASSWORD": password, "PFM_KEY_ALIAS": alias, "PFM_KEY_PASSWORD": password}
        identity = {"certificate_sha256": fingerprint, "alias": alias, "key_algorithm": "RSA",
                    "key_size": 4096, "validity_days": 36500, "backup_recipient": recipient}
        files = {"release.p12": keystore.read_bytes(), "certificate.der": der,
                 "identity.json": (json.dumps(identity, indent=2) + "\n").encode(),
                 "secrets.json": (json.dumps(values, indent=2) + "\n").encode()}
        files["SHA256SUMS"] = ("\n".join(f"{hashlib.sha256(data).hexdigest()}  {name}"
                                            for name, data in files.items()) + "\n").encode()
        backup = encrypted_backup(files, recipient)
        # Output is ciphertext/public metadata only. Persist backup independently before applying secrets.
        print("PFM_RELEASE_IDENTITY=" + json.dumps(identity))
        print("PFM_RELEASE_BACKUP_SHA256=" + hashlib.sha256(backup).hexdigest())
        print("PFM_RELEASE_BACKUP_ENCRYPTED=" + base64.b64encode(backup).decode())
        payload = {name: base64.b64encode(box.encrypt(value.encode())).decode() for name, value in values.items()}
        print("PFM_RELEASE_SEALED_SECRETS=" + json.dumps({"key_id": os.environ["PFM_GITHUB_PUBLIC_KEY_ID"],
                                                          "values": payload}))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Do not expose captured subprocess input, secret values or a traceback of locals.
        raise SystemExit("Release identity setup failed; do not retry blindly or create a replacement key") from None
