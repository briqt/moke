# 参与 Moke

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
