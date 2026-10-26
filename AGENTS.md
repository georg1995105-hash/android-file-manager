# Agent instructions — Android File Manager

Use `PROJECT_STATUS.md` and the relevant part of `PROJECT_SPEC.md` as the source of truth for the existing app; read other files only as needed. Work only in this repository. Do not modify the separate gallery or other projects.

- Finish the requested work autonomously: identify the problem, make a minimal compatible change, run necessary targeted checks, and produce an installable APK when requested. Do not stop at a plan, intermediate progress update, or a code-only result when an APK was requested.
- Protect personal files and existing features. For copy/move/delete/restore/backup operations, test against disposable fixtures; do not touch real user data. Preserve app ID, stored settings, version upgrade path, and signing identity.
- Keep test effort proportional: compile/targeted tests first, then one critical scenario; run phone and tablet emulators only for relevant cross-device behavior or release acceptance. Do not launch repeated full CI runs or new branches/tags merely to retry the same failed test.
- CI build uses `.github/workflows/android.yml` and JDK 21. Verify test compilation before long device workflows. When CI fails, inspect the specific failure and rerun only the affected scope after a fix. Never call a successful build proof of real-device behavior.
- Keep owner release keys and credentials out of Git. Do not rotate debug signing casually: changing signing identity can prevent updates over installed APKs. Publishing to app stores is separate work and requires an explicit request.
- Respect concurrent work: use an appropriate task branch; no force pushes, resets or overwriting someone else's changes. Existing working code is more important than refactoring.
- Report succinctly: what changed, commit/branch, what actually passed, artifact link when applicable, and exact remaining device test/blocker. Solve routine environment problems yourself if possible; otherwise preserve verified progress rather than loop on identical retries.
