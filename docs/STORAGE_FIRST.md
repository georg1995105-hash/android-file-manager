# Storage-first evolution — 0.13.0 development

The original package io.personal.filemanager, release signing, SQLite data, link IDs, existing received directory and OTA stay unchanged. No destructive migration. The legacy file browser and utilities remain secondary while the four primary sections become Storage, Transfer, Links, Settings. The current implementation is deliberately local: do not label local files as uploaded or available when the TV is off.

## Next milestone: asynchronous personal storage
- A small independent HTTPS backend with authenticated device identities; no shared secret embedded in the APK and no production VPN node dependency.
- Pair once by scanning a short-lived single-use QR invitation (or entering a code); store device credentials in Android Keystore, revoke individually. Changing local IP must not invalidate pairing.
- Persist upload jobs and file IDs, support retries/resume, atomic completion, quotas, integrity check and explicit delete. A TV that is off has no live connection; files must be durably held by an online service until accessed. Clearly distinguish local-only, uploading, stored remotely and downloaded.
- Encrypt content on the sending device, keep decryption keys on paired devices, never publish unencrypted private content to GitHub or public URLs. Use a reviewed standard authenticated encryption protocol; do not invent crypto. No credentials in repository, CI or logs.
- Test cold start, offline sender/receiver, interruption, duplicate name, corrupt payload, revoked device, expiry, low storage, upgrade from signed 0.12.32 without uninstall, and actual TV D-pad navigation.
- Backend endpoint, hosting/quota policy and real credentials are not provisioned in this branch. Do not release an unverified cloud UI to OTA.
