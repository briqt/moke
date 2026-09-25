<div align="center">

# Moke · 墨客

**Android 原生 SSH / mosh 终端**（Kotlin + Jetpack Compose）

[![CI](https://github.com/briqt/moke/actions/workflows/ci.yml/badge.svg)](https://github.com/briqt/moke/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/briqt/moke?sort=semver)](https://github.com/briqt/moke/releases)
[![minSdk](https://img.shields.io/badge/minSdk-24-blue)](#)

</div>

<p align="center"><a href="README.md">English</a> · <b>简体中文</b></p>

## 这是什么

Moke 是一个 Android 原生 SSH / mosh 终端。它在 app 内直接连接远程服务器——跑 shell、tmux、vim，或 Claude Code 这类全屏 TUI 工具；终端渲染复用 termux 的 `terminal-view` / `terminal-emulator`（Apache-2.0），不自研 ANSI 解析。

## 功能

- **SSH**：密码 / 私钥（PEM）认证；主机密钥 TOFU 校验，首次连接先确认指纹；跳板机（ProxyJump）；按主机指定启动命令（如在 Windows 主机上选择 cmd、PowerShell 或 WSL）；登录后自动执行命令；保活心跳。
- **mosh**：随包 native `mosh-client`，独立子进程 + PTY 运行；UDP 漫游（关屏 / 切网不断线）；关闭会话时一并结束远端 `mosh-server`。
- **tmux**：面板内附加 / 新建 / 重命名 / 离开 / 关闭远端会话；可设置连接即自动进入；可让 tmux 接管滑动滚屏。
- **文件**：经 SFTP 浏览远端目录（SSH 与 mosh 主机均可）；上传 / 下载支持断点续传；传输在后台继续；可把文件路径发送到终端。
- **会话与连接**：多会话常驻，前台服务在后台保持连接；分组 / 排序（含手动拖动）；创建副本；自定义会话标题；一键清理已结束会话；复制连接命令。
- **终端**：双排附加键 + 可展开的全键盘（编辑键、F1–F12、Ctrl 组合键），修饰键可锁定；文本段输入；三种键盘模式（字符模式 / 标准 / 输入法优先），中文输入顺畅；复制粘贴，支持远端写入剪贴板（OSC 52）；捏合缩放；全屏 TUI 内滑动滚屏（模式可选）与「跳到底部」；顶部状态条（协议 / 主机 / 延迟）。
- **外观**：实时预览；浅色 / 深色 / 跟随系统，两种模式可分别指定终端配色；字号 / 行距 / 字间距、光标样式可调；字体管理（主字体 + 中文回退——内置思源黑体子集，可下载 Fira Code / Maple Mono / Hack 等，也可导入本地字体）。
- **安全、多语言与更新**：连接凭据经 Android Keystore（AES-GCM）加密后存储，且不进入系统备份；中英双语，默认跟随系统语言，设置内可切换；应用内检查更新（可选包含预览版）。

## 截图

<div align="center">
<img src="docs/screenshot-connections.png" alt="连接" width="160"/>&nbsp;<img src="docs/screenshot-terminal.png" alt="终端 SSH 跑 Claude Code" width="160"/>&nbsp;<img src="docs/screenshot-tmux.png" alt="tmux 面板" width="160"/>&nbsp;<img src="docs/screenshot-files.png" alt="远端文件" width="160"/>&nbsp;<img src="docs/screenshot-appearance.png" alt="外观（浅色主题）" width="160"/>
<br/><sub>连接 · 终端（SSH 跑 Claude Code）· tmux 面板 · 远端文件 · 外观（浅色主题）</sub>
</div>

## 安装

前往 [Releases](https://github.com/briqt/moke/releases) 择一下载：

- `moke-vX.Y.Z.apk` — 常规包，中文回退用内置思源黑体子集（体积小）。
- `moke-vX.Y.Z-maple.apk` — 自带 Maple Mono 并设为默认中文回退，开箱中英等宽（体积更大）。

允许"安装未知来源应用"后安装。发布包使用固定签名，可覆盖升级。

## 模块

| 模块 | 说明 | 许可 |
|---|---|---|
| `app` | 产品层（Compose UI / 会话编排 / 传输实现） | GPL-3.0-or-later |
| `terminal-emulator` | 终端解析 / 状态内核（vendored；`TerminalSession` 改为传输无关；保留 PTY JNI 供 mosh 子进程使用） | Apache-2.0 |
| `terminal-view` | 终端渲染 View（vendored；为行距 / 字间距与滑动滚屏做加法式改动） | Apache-2.0 |

## 构建

需要 JDK 17 + Android SDK（compileSdk 35 / build-tools 35）。在项目根创建 `local.properties` 指向 SDK：`sdk.dir=/path/to/Android/sdk`。

```bash
./gradlew assembleStandardDebug   # 常规调试 APK
./gradlew testDebugUnitTest       # 单元测试

# maple 变体自带 Maple Mono 作默认中文回退；先取字体（OFL，约 20MB，不入库）再构建：
./scripts/fetch-maple-font.sh && ./gradlew assembleMapleDebug
```

mosh native 产物由 [`scripts/build-mosh-native.sh`](scripts/build-mosh-native.sh) 从公开源码（mosh 1.4.0 + rjyo/mosh-android 预编译库）复现构建，需 NDK r29；GPLv3 二进制不入库。maple 变体内置字体由 [`scripts/fetch-maple-font.sh`](scripts/fetch-maple-font.sh) 获取（OFL，不入库）。

## 反馈

仅接受 [issue](https://github.com/briqt/moke/issues) 形式的问题反馈，暂不接受 PR（详见 [CONTRIBUTING](CONTRIBUTING.zh-CN.md)）。

交流社区：[学AI，linuxdo](https://linux.do/)

## 致谢与第三方

终端内核复用 [termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator` / `terminal-view`（Apache-2.0，源自 [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)）。SSH 传输使用 [sshj](https://github.com/hierynomus/sshj)。mosh native 基于 [mobile-shell/mosh](https://github.com/mobile-shell/mosh)（GPLv3）与 [rjyo/mosh-android](https://github.com/rjyo/mosh-android)。内置中文字体为 [Noto Sans SC / 思源黑体](https://github.com/notofonts/noto-cjk)（OFL）子集。完整清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

moke 产品层（`app/`）以 **GNU General Public License v3.0 或更高版本**发布，见 [LICENSE](LICENSE) 与 [COPYRIGHT.md](COPYRIGHT.md)。vendored 的 `terminal-*` 模块为 Apache-2.0（与 GPLv3 兼容）；mosh native 组件为 GPLv3，以独立可执行文件分发。
