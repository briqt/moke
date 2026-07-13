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
