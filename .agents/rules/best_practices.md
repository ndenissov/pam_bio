---
trigger: always_on
description: General workflow and best practices for the PamBio repository
---

# PamBio AI Workflow and Best Practices

This document contains critical rules and workflows that the AI must follow when assisting with the PamBio project.

## 1. Code Quality and Pre-flight Checks
Before finalizing a task, declaring it "done", or committing code, the AI MUST:
1. **Run local linters**: Run `gradlew lint` for Android or `./fix_lint.py` if working on Go/Python.
2. **Attempt local builds**: Ensure the project still compiles (e.g., `make build`, `go build`, or `gradlew assembleDebug`).
3. **Run CodeQL (if applicable)**: Ensure no new security vulnerabilities (like implicit `PendingIntents`) are introduced. 

## 2. Android Security (PendingIntents)
- When creating `PendingIntent`s in Android, **ALWAYS** use the `FLAG_IMMUTABLE` flag unless mutability is strictly required.
- **IMPORTANT**: Make sure the underlying `Intent` is explicitly routed by setting the target package or component, even if the class is specified in the constructor. Use `intent.setPackage(context.packageName)` to satisfy CodeQL and prevent implicit intent vulnerabilities.

## 3. Versioning and Changelogs
- When creating a new release or updating versions, remember to update:
  - `CHANGELOG.md`
  - Android `build.gradle.kts` (version code and name)
  - `flake.nix` or related packages if applicable.
  - `$VERSION` in `packaging/build-deb.sh`

## 4. Cross-Platform Context (Windows & SEO)
- PamBio is a cross-platform tool supporting both Linux and Windows.
- When writing documentation (like `README.md`) or considering features, **always keep Windows use-cases in mind**. 
- Highlight phrases like "Windows Hello alternative", "PC biometric unlock without PIN", and "log in to Windows" to improve SEO and user awareness. 
- Do not default to a Linux-only perspective.
- Use platform-neutral terminology where appropriate (e.g., use `lockScreen` instead of `sddm` or `gdm` in configurations when the concept applies universally).