#!/usr/bin/env python3
"""Restore and verify a preserved release identity without logging secret material."""
import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

SECRET_NAMES = ("PFM_KEYSTORE_B64", "PFM_STORE_PASSWORD", "PFM_KEY_ALIAS", "PFM_KEY_PASSWORD")
PACKAGE = "io.personal.filemanager"


def command(args, *, env=None):
    result = subprocess.run(args, env=env, capture_output=True, check=False)
    if result.returncode:
        # Command arguments/environment may contain credentials. Do not echo them.
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode})")
    return result.stdout


def certificate(keystore, password, alias):
    env = dict(os.environ, PFM_CERTIFICATE_PASSWORD=password)
    der = command(["keytool", "-exportcert", "-keystore", str(keystore),
                   "-storepass:env", "PFM_CERTIFICATE_PASSWORD", "-alias", alias], env=env)
    return hashlib.sha256(der).hexdigest(), der


def pinned_certificate(path):
    value = Path(path).read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Invalid pinned release certificate")
    return value


def restore(destination, expected, values):
    missing = [name for name in SECRET_NAMES if not values.get(name)]
    if missing:
        raise ValueError("Missing release signing configuration: " + ", ".join(missing))
    try:
        data = base64.b64decode(values["PFM_KEYSTORE_B64"], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Invalid release keystore encoding") from None
    destination = Path(destination)
    if destination.exists():
        raise ValueError("Refusing to overwrite an existing keystore")
    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, name = tempfile.mkstemp(dir=destination.parent, prefix=".release-")
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
        actual, _ = certificate(name, values["PFM_STORE_PASSWORD"], values["PFM_KEY_ALIAS"])
        if actual != expected:
            raise ValueError(f"Release identity mismatch: expected {expected}, got {actual}")
        os.replace(name, destination)
        return actual
    finally:
        Path(name).unlink(missing_ok=True)


def build_tools():
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    versions = [p for p in sdk.iterdir() if (p / "apksigner").is_file()]
    return max(versions, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))


def inspect(apk, expected, tools):
    verified = command([str(tools / "apksigner"), "verify", "--verbose", "--print-certs",
                        "--min-sdk-version", "30", str(apk)]).decode()
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", verified)
    if digests != [expected]:
        raise ValueError(f"APK certificate does not match the pinned release identity: {apk.name}")
    badging = command([str(tools / "aapt2"), "dump", "badging", str(apk)]).decode()
    package = re.search(r"^package: name='([^']+)'", badging, re.M)
    version_code = re.search(r"versionCode='(\d+)'", badging)
    version_name = re.search(r"versionName='([^']+)'", badging)
    if not package or package[1] != PACKAGE or not version_code or not version_name:
        raise ValueError("Unexpected release APK package/version")
    if "application-debuggable" in badging:
        raise ValueError("A RELEASE APK must not be debuggable")
    command([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)])
    return {"package": package[1], "version_code": int(version_code[1]),
            "version_name": version_name[1], "certificate_sha256": expected,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "signature_verified": True, "debuggable": False, "zipalign_16k": True}


def publish_outputs(apk_dir, output_dir, expected):
    tools = build_tools()
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    reports = []
    for source in sorted(Path(apk_dir).glob("*.apk")):
        report = inspect(source, expected, tools)
        abi = "arm64" if "arm64-v8a" in source.name else "universal" if "universal" in source.name else None
        if not abi:
            raise ValueError("Unexpected release APK ABI")
        target = output_dir / f"personal-file-manager-{report['version_name']}-release-vc{report['version_code']}-{abi}.apk"
        shutil.copyfile(source, target)
        report.update(file=target.name, abi=abi)
        reports.append(report)
        print(json.dumps(report, ensure_ascii=False))
    if {r["abi"] for r in reports} != {"arm64", "universal"}:
        raise ValueError("Expected arm64 and universal RELEASE APKs")
    (output_dir / "RELEASE_IDENTITY.json").write_text(json.dumps(reports, indent=2) + "\n")
    sums = [f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in sorted(output_dir.glob("*.apk"))]
    (output_dir / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    restore_parser = sub.add_parser("restore")
    restore_parser.add_argument("--destination", required=True)
    restore_parser.add_argument("--certificate-file", required=True)
    inspect_parser = sub.add_parser("inspect")
    inspect_parser.add_argument("--apk-dir", required=True)
    inspect_parser.add_argument("--output-dir", required=True)
    inspect_parser.add_argument("--certificate-file", required=True)
    args = parser.parse_args()
    expected = pinned_certificate(args.certificate_file)
    if args.action == "restore":
        print("Release signing certificate: " + restore(args.destination, expected, os.environ))
    else:
        publish_outputs(args.apk_dir, args.output_dir, expected)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        raise SystemExit(str(error))
