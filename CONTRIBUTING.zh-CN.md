# 参与 Moke

[English](CONTRIBUTING.md) · **简体中文**

## 反馈方式

本项目**只接受 issue 形式的问题反馈，不接受 Pull Request**。收到的 PR 会被自动关闭。

请通过 [issue](https://github.com/briqt/moke/issues) 提交 bug 或建议。真机相关的问题请附上：设备型号、Android / 系统版本、服务器环境（是否装 mosh、locale 等）。

## 本地构建

- JDK 17、Android SDK（compileSdk 35 / build-tools 35）
- 在项目根创建 `local.properties`：`sdk.dir=/path/to/Android/sdk`
- 构建：`./gradlew assembleDebug`；测试：`./gradlew testDebugUnitTest`

## 约定

- 不修改 `terminal-view/`（vendored 上游 Apache-2.0）的核心行为；定制在 `app/` 或通过 `TerminalTransport` 完成。
- 提交信息用 [Conventional Commits](https://www.conventionalcommits.org/)。
- Kotlin 官方代码风格（`kotlin.code.style=official`）。

## 发布规范

- 正式版使用 `vX.Y.Z`；预发布使用 `vX.Y.Z-rc.1` 之类的 SemVer 后缀。`versionName` 等于去掉前导 `v` 的 tag。
- 每一个分发出去的 APK 都必须递增 `versionCode`。预发布之后的正式版即使共用同一个 `X.Y.Z`，也必须使用更大的 `versionCode`。
- 带 SemVer 后缀的 tag 发布为 GitHub Pre-release，绝不占用 Latest。应用内检查更新只跟随 GitHub 最新正式版，因此不会提示预发布。
- Standard 与 Maple APK 共用 `com.briqt.moke` 和同一稳定发布签名；只要新包的 `versionCode` 更大，两种变体可以互相覆盖升级。
- 打 tag 前须更新 `CHANGELOG.md`，运行单元测试、Standard Debug 构建和 Android Lint。发布工作流会校验 tag 与 `versionName` 一致，构建两个稳定签名的 release 变体，并检查必需的 native 库。
