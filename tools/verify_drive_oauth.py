#!/usr/bin/env python3
"""Check the registered public native device client without obtaining user access.
Never log credentials, device codes, or authorization URLs containing a code.
"""
import json
import os
import urllib.parse
import urllib.request
import urllib.error

client = os.environ.get("PFM_DRIVE_TV_CLIENT_ID", "")
secret = os.environ.get("PFM_DRIVE_TV_CLIENT_SECRET", "")
if not client or not secret:
    raise SystemExit("Cloud-enabled release requires the configured TV client and Actions secret")
payload = urllib.parse.urlencode({
    "client_id": client,
    "scope": "https://www.googleapis.com/auth/drive.file",
}).encode()
req = urllib.request.Request("https://oauth2.googleapis.com/device/code", data=payload,
                             headers={"Content-Type": "application/x-www-form-urlencoded"})
try:
    with urllib.request.urlopen(req, timeout=20) as response:
        result = json.load(response)
except urllib.error.HTTPError as e:
    raise SystemExit(f"Google device authorization endpoint returned HTTP {e.code}") from None
assert result.get("device_code") and result.get("user_code"), "No device authorization challenge"
url = urllib.parse.urlsplit(result.get("verification_url", ""))
assert url.scheme == "https" and url.hostname in ("www.google.com", "google.com", "accounts.google.com")
assert int(result.get("expires_in", 0)) > 0
print("Google accepted the registered TV client and drive.file scope; real account consent still requires a device test.")
