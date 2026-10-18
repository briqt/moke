# Contributing to Moke

**English** · [简体中文](CONTRIBUTING.zh-CN.md)

## How to report

This project **only accepts issue-based reports and does not accept pull requests**. PRs received will be closed automatically.

Please file bugs or suggestions via [issues](https://github.com/briqt/moke/issues). For device-specific problems, include: device model, Android / OS version, and the server environment (whether mosh is installed, locale, etc.).

## Local build

- JDK 17 or newer, Android SDK (compileSdk 37 / build-tools 36)
- Create `local.properties` at the project root: `sdk.dir=/path/to/Android/sdk`
- Build the network bridge: `ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-netbridge.sh`
- Build: `./gradlew assembleDebug`; test: `./gradlew testDebugUnitTest` (for the release, mosh native and bundled font builds, see the Build section of the README)

## Conventions

- Do not change the core behavior of `terminal-view/` (vendored upstream, Apache-2.0); customize in `app/` or through `TerminalTransport`.
- Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages.
- Follow the official Kotlin code style (`kotlin.code.style=official`).

## Release convention

- Stable releases use `vX.Y.Z`; pre-releases use a SemVer suffix such as `vX.Y.Z-rc.1`. `versionName` is the tag without the leading `v`.
- `versionCode` must increase for every distributed APK. A stable release following a pre-release must use a higher `versionCode`, even when both share the same `X.Y.Z`.
- Tags with a SemVer suffix are published as GitHub Pre-releases and never become Latest. The in-app update check follows GitHub's latest stable release and therefore does not offer pre-releases.
- Before tagging, update `CHANGELOG.md`, run unit tests, assemble the debug APK, and run Android Lint. The release workflow validates that the tag and `versionName` match, builds the signed release APK, and verifies its native libraries and bundled font.

## License

By contributing, you agree that your changes are licensed under **GPL-3.0-or-later**, the same license as this project (see [LICENSE](LICENSE)).
