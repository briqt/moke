<div align="center">

# Moke · 墨客

**Native Android SSH / mosh terminal with multiple sessions, proxies, built-in Tailscale, tmux, and SFTP.**

[![CI](https://github.com/briqt/moke/actions/workflows/ci.yml/badge.svg)](https://github.com/briqt/moke/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/briqt/moke?sort=semver)](https://github.com/briqt/moke/releases)
[![Android](https://img.shields.io/badge/Android-11%2B-blue)](#install)

</div>

<p align="center"><b>English</b> · <a href="README.zh-CN.md">简体中文</a></p>

## Features

- **SSH / mosh**: password and PEM private-key authentication, host-key fingerprint confirmation, SSH jump hosts, and per-host startup commands.
- **Networks**: HTTP / HTTPS / SOCKS4 / SOCKS5 proxies and built-in userspace Tailscale, including self-hosted control servers, without taking the system VPN slot. Credentials and tailnet login state are encrypted with the Android Keystore.
- **Sessions & tmux**: multiple sessions, background connections, remote tmux session management, and port forwarding that listens only on the device's loopback interface.
- **Files**: SFTP browsing, uploads and downloads with resume, and sending file paths to the terminal.
- **Terminal**: customizable extra keys, IME input, text-block input, links, clipboard and notification support, fonts and color schemes, and English / Chinese interfaces.

## Screenshots

<div align="center">
<img src="docs/screenshot-connections.png" alt="Hosts" width="160"/>&nbsp;<img src="docs/screenshot-terminal.png" alt="Terminal" width="160"/>&nbsp;<img src="docs/screenshot-tmux.png" alt="tmux panel" width="160"/>&nbsp;<img src="docs/screenshot-files.png" alt="Remote files" width="160"/>&nbsp;<img src="docs/screenshot-appearance.png" alt="Appearance (light theme)" width="160"/>
<br/><sub>Hosts · Terminal · tmux panel · Remote files · Appearance (light theme)</sub>
</div>

## Install

Requires **Android 11 or newer** and an **arm64-v8a** device. Download the APK from [Releases](https://github.com/briqt/moke/releases) and allow installation from that source. Published APKs use a stable signing key and support in-place upgrades. In-app update checks offer stable releases by default; pre-releases can be enabled in Settings.

mosh requires `mosh-server` on the remote host and a reachable UDP path. Proxy use requires a SOCKS5 server that supports UDP forwarding; HTTP, HTTPS and SOCKS4 proxies cannot carry mosh. SSH jump hosts carry only the bootstrap connection, not mosh UDP traffic, so the target still needs a directly reachable UDP path. Session continuity also depends on network availability and Android background restrictions.

## Build & feedback

Build requirements, commands and release conventions are maintained in [CONTRIBUTING](CONTRIBUTING.md). Report problems through [issues](https://github.com/briqt/moke/issues); pull requests are not accepted.

## License

The product layer (`app/`) is **GPL-3.0-or-later**; see [LICENSE](LICENSE) and [COPYRIGHT.md](COPYRIGHT.md). The vendored terminal modules are Apache-2.0, and the standalone mosh executable is GPLv3. Component sources, modifications and font licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Distributors must meet the applicable license and corresponding-source requirements.
