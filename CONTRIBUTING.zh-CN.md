# 参与 Moke

[English](CONTRIBUTING.md) · **简体中文**

## 反馈与贡献

欢迎反馈问题、提出建议和提交 Pull Request。

- **问题反馈**：请提供复现步骤、预期与实际表现、Android 版本，以及相关服务器或代理的能力。
- **Pull Request**：每个 PR 聚焦一个问题，说明改动目的、实现方式与验证结果。行为变更请补充或更新测试，并运行下文的相关检查。

请勿在代码、日志或截图中包含密码、私钥、令牌及其他敏感信息。

## 构建条件

以下命令在仓库根目录执行，需要 Bash、GNU coreutils、Git、curl、tar 与 unzip。

- **JDK 25**，与 CI 一致；使用仓库自带的 Gradle wrapper。Gradle、Android Gradle Plugin 与 Kotlin 版本锁定在 `gradle/wrapper/gradle-wrapper.properties` 和 `gradle/libs.versions.toml`。
- **Android SDK 平台 37**、锁定的 Android Gradle Plugin 所需的 Build-Tools，以及 **NDK r30（`30.0.16248370`）**。应用支持 Android 11 及以上，仅打包 `arm64-v8a`。
- **Go**：安装 `netbridge/go.mod` 中 `toolchain` 指定的准确版本。网络构建脚本发现本机版本不符会报错，不会自动下载工具链。Go 依赖与 gomobile/gobind 由 `netbridge/go.mod`、`netbridge/go.sum` 锁定。

设置 SDK、NDK 路径；若尚无 `local.properties`，创建并填写 SDK 路径：

```bash
export ANDROID_HOME=/path/to/Android/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
# local.properties: sdk.dir=/path/to/Android/sdk
```

两个 native 构建脚本均接受 `ANDROID_NDK_HOME` 或 `ANDROID_NDK`，前者优先。

## 网络桥接与 Debug 构建

每次应用构建都需要 Go 网络桥接产物：

```bash
bash scripts/build-netbridge.sh
bash scripts/verify-netbridge.sh check
./gradlew assembleDebug
```

脚本生成 `app/libs/netbridge.aar` 及其内容指纹。已验证且与当前输入一致的产物会直接复用，否则按锁定输入重新构建。Gradle 构建前核对生产 Go 源码、依赖锁、构建参数与 AAR 摘要；产物缺失、陈旧或损坏时，须重新运行构建脚本，Gradle 不会自动重建。不要只复制 AAR 而遗漏生成的指纹，也不要手工修改指纹。

Debug APK 位于 `app/build/outputs/apk/debug/`。Debug 可以不准备 mosh native 与 Maple 字体；此时 mosh 不可用，Maple 可在应用内下载而非内置。

## mosh native 与 Release 构建

```bash
bash scripts/build-mosh-native.sh arm64-v8a
bash scripts/fetch-maple-font.sh
./gradlew assembleRelease
```

mosh 脚本从仓库的 PTY JNI 构建 `libtermux.so`，从 mosh 1.4.0 源码及锁定的 rjyo/mosh-android 预编译库构建 `libmosh-client.so`，输出到 `app/src/main/jniLibs/arm64-v8a/`。修改 JNI 或 native 构建输入后须重建；二进制不入库。

Release 构建必须先通过字体脚本取得 Maple Mono NF CN。APK 位于 `app/build/outputs/apk/release/`。发布签名通过 `MOKE_KEYSTORE`、`MOKE_KEYSTORE_PASSWORD`、`MOKE_KEY_ALIAS`、`MOKE_KEY_PASSWORD` 配置；本地未配置签名时会使用 debug 密钥，不能替代固定签名的公开发布包。

分发时须遵守 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中的许可与对应源码义务，包括 native 组件及其链接依赖。

## 测试与检查

```bash
./gradlew testDebugUnitTest lintDebug
(cd netbridge && go test -race -count=1 ./...)
bash scripts/test-verify-netbridge.sh
```

不带模块前缀的 Gradle 任务覆盖全部模块。Go race 测试需要主机 C 编译器。可选的真实 mosh 中继互通测试还需要 `PATH` 中有 `mosh-server`、`mosh-client` 与 `script`：

```bash
(cd netbridge && go test -race -count=1 -tags moshlab ./internal/relay)
```

CI 执行 Go race 测试、网络桥接与 Debug 构建、JVM 测试和 Android Lint。发布工作流另行构建 mosh native、取得内置字体、签名，并检查 APK 中的 native 库与字体。

## 代码约定

- 应用定制优先放在 `app/` 或 `TerminalTransport` 后；尽量减少对内置 Apache-2.0 终端模块的修改，必要改动在模块 README 中说明。
- 提交信息使用 [Conventional Commits](https://www.conventionalcommits.org/)，Kotlin 遵循官方代码风格（`kotlin.code.style=official`）。

## 发布规范

- 正式版 tag 使用 `vX.Y.Z`，预发布使用 `vX.Y.Z-rc.1` 之类的 SemVer 后缀；`versionName` 等于去掉前导 `v` 的 tag。
- 每个分发的 APK 都必须递增 `versionCode`，同一 `X.Y.Z` 的预发布转正式版也不例外。
- 带 SemVer 后缀的 tag 发布为 GitHub Pre-release，不占用 Latest。应用内默认检查最新正式版，用户可在设置中开启包含预览版。
- 打 tag 前更新 `CHANGELOG.md`，执行上述测试、Debug 构建与 lint。发布工作流校验 tag 与 `versionName`，并随签名 APK 发布对应版本的变更记录。

## 许可

贡献采用 **GPL-3.0-or-later**，见 [LICENSE](LICENSE)。第三方组件保留各自许可。
