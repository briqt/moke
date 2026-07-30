# Contributing to Moke

**English** · [简体中文](CONTRIBUTING.zh-CN.md)

## How to report

This project **only accepts issue-based reports and does not accept pull requests**. PRs received will be closed automatically.

Please file bugs or suggestions via [issues](https://github.com/briqt/moke/issues). For device-specific problems, include: device model, Android / OS version, and the server environment (whether mosh is installed, locale, etc.).

## Local build

- JDK 17, Android SDK (compileSdk 35 / build-tools 35)
- Create `local.properties` at the project root: `sdk.dir=/path/to/Android/sdk`
- Build: `./gradlew assembleDebug`; test: `./gradlew testDebugUnitTest`

## Conventions

- Do not change the core behavior of `terminal-view/` (vendored upstream, Apache-2.0); customize in `app/` or through `TerminalTransport`.
- Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages.
- Follow the official Kotlin code style (`kotlin.code.style=official`).

## Release convention

- Stable releases use `vX.Y.Z`; pre-releases use a SemVer suffix such as `vX.Y.Z-rc.1`. `versionName` is the tag without the leading `v`.
- `versionCode` must increase for every distributed APK. A stable release following a pre-release must use a higher `versionCode`, even when both share the same `X.Y.Z`.
- Tags with a SemVer suffix are published as GitHub Pre-releases and never become Latest. The in-app update check follows GitHub's latest stable release and therefore does not offer pre-releases.
- Standard and Maple APKs share `com.briqt.moke` and the same stable release signature. Either variant can upgrade the other when its `versionCode` is higher.
- Before tagging, update `CHANGELOG.md`, run unit tests, assemble the Standard debug APK, and run Android Lint. The release workflow validates that the tag and `versionName` match, builds both signed release variants, and verifies their required native libraries.
