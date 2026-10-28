# Contributing to Moke

**English** · [简体中文](CONTRIBUTING.zh-CN.md)

## Issues and pull requests

Bug reports, suggestions and pull requests are welcome.

- **Bug reports**: Include steps to reproduce, expected and actual behavior, Android version, and relevant server or proxy capabilities.
- **Pull requests**: Keep each PR focused on one issue. Describe the motivation, implementation and validation. Add or update tests for behavior changes, and run the relevant checks below.

Do not include passwords, private keys, tokens or other sensitive information in code, logs or screenshots.

## Build requirements

Run the commands below from the repository root in a Bash environment with GNU coreutils, Git, curl, tar and unzip.

- **JDK 25**, matching CI; use the checked-in Gradle wrapper. Gradle, Android Gradle Plugin and Kotlin versions are locked in `gradle/wrapper/gradle-wrapper.properties` and `gradle/libs.versions.toml`.
- **Android SDK platform 37**, with the Build-Tools required by the locked Android Gradle Plugin, and **NDK r30 (`30.0.16248370`)**. The app requires Android 11 or newer and packages only `arm64-v8a`.
- **Go**: install the exact `toolchain` version in `netbridge/go.mod`. The network builder rejects a different local version rather than downloading a toolchain. Go dependencies and gomobile/gobind are locked in `netbridge/go.mod` and `netbridge/go.sum`.

Set the SDK and NDK paths, and create `local.properties` if it does not exist:

```bash
export ANDROID_HOME=/path/to/Android/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
# local.properties: sdk.dir=/path/to/Android/sdk
```

Both native build scripts accept `ANDROID_NDK_HOME` or `ANDROID_NDK`; `ANDROID_NDK_HOME` takes precedence.

## Network bridge and debug build

Every app build requires the Go network bridge:

```bash
bash scripts/build-netbridge.sh
bash scripts/verify-netbridge.sh check
./gradlew assembleDebug
```

The builder creates `app/libs/netbridge.aar` and its content fingerprint. It reuses a verified current artifact; otherwise it rebuilds with the locked inputs. Gradle checks production Go sources, dependency locks, build settings and the AAR's digest before building. Missing, stale or damaged artifacts must be rebuilt with the builder; Gradle does not rebuild them automatically. Do not copy an AAR without its generated fingerprint or edit the fingerprint manually.

The debug APK is written to `app/build/outputs/apk/debug/`. Debug builds can omit the mosh native artifacts and Maple font: mosh is then unavailable, and Maple remains downloadable rather than bundled.

## mosh native and release build

```bash
bash scripts/build-mosh-native.sh arm64-v8a
bash scripts/fetch-maple-font.sh
./gradlew assembleRelease
```

The mosh script builds `libtermux.so` from this repository's PTY JNI and `libmosh-client.so` from mosh 1.4.0 sources plus the pinned rjyo/mosh-android prebuilt libraries. Outputs are placed in `app/src/main/jniLibs/arm64-v8a/`. Rebuild them after changing the JNI or native build inputs; the binaries are not tracked in Git.

Release builds require the Maple Mono NF CN font fetched by the font script. The APK is written to `app/build/outputs/apk/release/`. Configure `MOKE_KEYSTORE`, `MOKE_KEYSTORE_PASSWORD`, `MOKE_KEY_ALIAS` and `MOKE_KEY_PASSWORD` for release signing. Without signing configuration, a local release build uses the debug key and is not a replacement for the published stable-signature APK.

Redistribution must meet the license and corresponding-source obligations in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), including those of the native components and linked dependencies.

## Tests and checks

```bash
./gradlew testDebugUnitTest lintDebug
(cd netbridge && go test -race -count=1 ./...)
bash scripts/test-verify-netbridge.sh
```

The unqualified Gradle tasks cover all modules. Go race tests require a host C compiler. Optional real-mosh relay interoperability tests also require `mosh-server`, `mosh-client` and `script` on `PATH`:

```bash
(cd netbridge && go test -race -count=1 -tags moshlab ./internal/relay)
```

CI runs Go race tests, builds the network bridge and debug APK, and runs JVM tests and Android Lint. The release workflow additionally builds mosh native artifacts, fetches the bundled font, signs the APK and checks that the native libraries and font are packaged.

## Code conventions

- Keep app-specific behavior in `app/` or behind `TerminalTransport`; minimize changes to the vendored Apache-2.0 terminal modules and document necessary modifications in their READMEs.
- Use [Conventional Commits](https://www.conventionalcommits.org/) and the official Kotlin code style (`kotlin.code.style=official`).

## Release convention

- Stable tags use `vX.Y.Z`; pre-releases use a SemVer suffix such as `vX.Y.Z-rc.1`. `versionName` is the tag without the leading `v`.
- Increase `versionCode` for every distributed APK, including a stable release following a pre-release of the same `X.Y.Z`.
- Tags with a SemVer suffix are published as GitHub Pre-releases and never become Latest. In-app update checks use the latest stable release by default; users can opt into pre-releases in Settings.
- Before tagging, update `CHANGELOG.md` and run the tests, debug build and lint checks above. The release workflow validates the tag against `versionName` and publishes the signed APK with that version's changelog section.

## License

Contributions are licensed under **GPL-3.0-or-later**; see [LICENSE](LICENSE). Third-party components retain their own licenses.
