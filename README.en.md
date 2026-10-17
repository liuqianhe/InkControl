<p align="center"><img src="docs/brand/banner.svg" alt="InkControl — Hisense A5 Pro 经典版" width="100%"></p>

<p align="center"><a href="README.md">简体中文</a> · <a href="docs/wiki/README.en.md"><b>Wiki</b></a> · <a href="docs/wiki/agent-handbook.en.md">Start here, agents</a> · <a href="https://github.com/wzp100/InkControl/releases">Downloads</a></p>

<p align="center"><a href="https://github.com/wzp100/InkControl/actions/workflows/build.yml"><img src="https://github.com/wzp100/InkControl/actions/workflows/build.yml/badge.svg" alt="Cloud build and validation"></a></p>

# More control over your E-ink display

**InkControl · 墨控** brings display algorithms, transitions, lock-screen controls, and stock-launcher themes to the **Hisense A5 Pro 经典版 (Classic Edition)** with its **monochrome E-ink screen**. One app contains its matching Magisk backend.

All **52 enhancements are off by default**. Enable what you need, retain your selections, and see configuration intent separately from runtime acknowledgement.

| Display | Motion | Lock screen | Stock launcher |
| --- | --- | --- | --- |
| Per-mode algorithms; separate video settings | Effects, previews, temperature timing curves | Weather, lyrics, styles, refresh policy | Three themes; independent icons, fonts, widgets |
| Grayscale and binary groups; independent hysteresis | Existing display path and mode boundaries | Individual controls and conditional restoration | Original layout and app positions retained |

## Choose your starting point

| I want to… | Read |
| --- | --- |
| Install and get started | [Getting started](docs/wiki/getting-started.en.md) |
| Understand modes, video, and hysteresis | [Display algorithms](docs/wiki/algorithms.en.md) |
| Diagnose slow refresh or inactive settings | [Operations and recovery](docs/wiki/operations.en.md) |
| Continue development and understand prior work | [Agent handbook](docs/wiki/agent-handbook.en.md) → [Research](docs/wiki/research.en.md) |
| Locate code, evidence, and release automation | [Architecture](docs/wiki/architecture.en.md) · [Evidence](docs/wiki/evidence.en.md) · [Build and release](docs/wiki/build-and-release.en.md) |

## Getting started

1. Download a signed APK from [Releases](https://github.com/wzp100/InkControl/releases). Read its notes and checksums. `rc` versions are prereleases.
2. Open **Install**, check compatibility, export the bundled backend ZIP, and install it through Magisk.
3. Enable InkControl in a Vector/LSPosed-compatible framework for System Framework, System UI, and the stock launcher.
4. Restart fully, check **Diagnostics**, then enable individual enhancements.

The backend checks `HLTE203T / Android 10 / arm64 / L1732.6.01.02` and exact vendor-input hashes. Without Root you can browse guides, switch languages, preview effects, and export the installer. See [installation and recovery](docs/wiki/getting-started.en.md).

> **Signing migration:** rc13 introduced a fixed new release identity. Back up configuration and uninstall the old signed app before migrating to the new app and matching backend. Subsequent updates retain that identity.

## A look inside

<table><tr><th>简体中文</th><th>English</th></tr><tr><td><img src="docs/media/ui-zh.png" alt="Chinese features page in the cloud emulator" width="260"></td><td><img src="docs/media/ui-en.png" alt="English features page in the cloud emulator" width="260"></td></tr></table>

Actual rc13 cloud-emulator screenshots; they do not establish physical-panel quality. The app follows the system language by default and supports independent Chinese/English selection.

## Project status

The source targets **0.1.0-rc14**, with per-mode algorithms, independent hysteresis, bilingual UI, a shared visual identity, and a versioned knowledge base. All compilation and validation runs in the cloud. New versions publish a private Release after every check passes; rerunning an existing version never overwrites its Release.

The recorded device installation as of 2026-10-18 remains **rc10 app + rc10-gpu-fix1 backend**. rc13 passed cloud signing, regressions, and four bilingual UI configurations. Integrated HWC behavior, panel quality, power, temperature coverage, and long-term stability need separate acceptance. The historical first GPU error remains unexplained; the firmware's Google split-resource icon overlay should stay off. See [evidence boundaries](docs/wiki/evidence.en.md).

## Developers and agents

Start with [AGENTS.md](AGENTS.md) and the [agent handbook](docs/wiki/agent-handbook.en.md). [llms.txt](llms.txt) provides a short entry point; the [knowledge index](docs/wiki/knowledge-index.json) maps topics to code and evidence.

The feature catalog is the single configuration definition. The UI calls application services. See [contributing](CONTRIBUTING.en.md), [architecture](docs/wiki/architecture.en.md), and [localization](docs/localization.en.md).

This repository remains **private**. Original code and artwork follow [MIT](LICENSE). Vendor binaries, third-party assets, and external frameworks retain their own terms; see [NOTICE](NOTICE.en.md). Keys, passwords, plaintext firmware, personal backups, and raw device logs stay outside Git.
