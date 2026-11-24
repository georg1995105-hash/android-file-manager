# Storage-first 0.13.1
Owner rejected hosting a private relay. Previous prototype was never published and is retained in Git history only.
- Existing local transfer is primary: QR/browser and native peer discovery, no internet or Google account.
- Optional personal Drive: scope drive.file, app-created files only, same Google account across user's devices; separate accounts are isolated. No public sharing, no owner server.
- Register an Android OAuth client for io.personal.filemanager and official APK SHA-1, and TV/limited-input client in the same Google Cloud project. Enable Drive API; configure consent screen and production publication before enabling PFM_DRIVE_ENABLED=true. Set repository variables PFM_DRIVE_TV_CLIENT_ID and secret PFM_DRIVE_TV_CLIENT_SECRET; TV native client cannot keep a confidential secret. Verify Google authorization on phone and TV with actual account and upload/download. No passwords or tokens in source.
- If not configured, cloud screen explicitly reports unavailable. Local features remain operational and can receive signed OTA; do not label cloud as tested.
- TLS and Google account access protect cloud data; not end-to-end encrypted against Google. Resumable upload retries during active operation; persistence across process death is not implemented.
- Preserve official package and release certificate; versionCode monotonically increases, publish only from established release branch after tests. User confirms Android installer update; no uninstall.

## 0.13.3 — branding
Owner requested the launcher name «Передача» and a new bidirectional transfer icon on phone/tablet/TV. Package, signing certificate, file directories, protocol identifiers and OTA artifact naming stay unchanged for backward compatibility. Google Cloud branding should use «Передача».

## 0.13.4 — OAuth activation candidate
Drive API enabled; owner created Android and TV clients in Google Cloud project personal-file-manager-515715 and added a test account. The public TV OAuth client ID is configured in the signed-release workflow. Its native credential is provided only through the encrypted Actions secret. Device authorization endpoint smoke verifies the client and scope without user tokens; actual same-account phone→Drive→TV transfer remains a required device test. Public branding/audience must be completed in Google Cloud; test-mode grants can expire. No cloud server. Website and policies: docs/index.html, privacy.html, terms.html.
