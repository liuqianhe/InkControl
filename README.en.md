<p align="center"><img src="docs/brand/banner.svg" alt="InkControl — Hisense A5 Pro 经典版" width="100%"></p>

<p align="center"><a href="README.md">简体中文</a> · <a href="https://github.com/wzp100/InkControl/wiki/Home.en"><b>Wiki</b></a> · <a href="https://github.com/wzp100/InkControl/wiki/agent-handbook.en">Start here, agents</a> · <a href="https://github.com/wzp100/InkControl/releases">Downloads</a></p>

<p align="center"><a href="https://github.com/wzp100/InkControl/actions/workflows/build.yml"><img src="https://github.com/wzp100/InkControl/actions/workflows/build.yml/badge.svg" alt="Cloud build and validation"></a></p>

# More control over your E-ink display

**InkControl · 墨控** brings display algorithms, transitions, lock-screen controls, and stock-launcher themes to the **Hisense A5 Pro 经典版 (Classic Edition)** with its **monochrome E-ink screen**. One app contains its matching Magisk backend.

All **61 enhancements are off by default**. Enable what you need, retain your selections, and see configuration intent separately from runtime acknowledgement.

| Display | Motion | Lock screen | Stock launcher |
| --- | --- | --- | --- |
| Per-mode algorithms; separate video settings | Effects, previews, temperature timing curves | Weather, lyrics, styles, refresh policy | Three themes; independent icons, fonts, widgets |
| Grayscale and binary groups; independent hysteresis | Existing display path and mode boundaries | Individual controls and conditional restoration | Original layout and app positions retained |

> **Test devices only recommended: back up important data to a computer or independent storage first. Boot failure, permanent data loss, and unrecoverable device damage are possible. Read [risks and disclaimer](#risks-and-disclaimer) before installation.**

## Choose your starting point

| I want to… | Read |
| --- | --- |
| Install and get started | [Getting started](https://github.com/wzp100/InkControl/wiki/getting-started.en) |
| Understand modes, video, and hysteresis | [Display algorithms](https://github.com/wzp100/InkControl/wiki/algorithms.en) |
| Diagnose slow refresh or inactive settings | [Operations and recovery](https://github.com/wzp100/InkControl/wiki/operations.en) |
| Continue development and understand prior work | [Agent handbook](https://github.com/wzp100/InkControl/wiki/agent-handbook.en) → [Research](https://github.com/wzp100/InkControl/wiki/research.en) |
| Locate code, evidence, and release automation | [Architecture](https://github.com/wzp100/InkControl/wiki/architecture.en) · [Evidence](https://github.com/wzp100/InkControl/wiki/evidence.en) · [Build and release](https://github.com/wzp100/InkControl/wiki/build-and-release.en) |

## Getting started

1. Download a signed APK from [Releases](https://github.com/wzp100/InkControl/releases). Read its notes and checksums. `rc` versions are prereleases.
2. Open **Install**, check compatibility, export the bundled backend ZIP, and install it through Magisk.
3. Enable InkControl in a Vector/LSPosed-compatible framework for System Framework, System UI, and the stock launcher.
4. Restart fully, check **Diagnostics**, then enable individual enhancements.

The backend checks `HLTE203T / Android 10 / arm64 / L1732.6.01.02` and exact vendor-input hashes. Without Root you can browse guides, switch languages, preview effects, and export the installer. See [installation and recovery](https://github.com/wzp100/InkControl/wiki/getting-started.en).

> **Verified device and system:** The only model accepted on physical hardware so far is the **Hisense A5 Pro 经典版 `HLTE203T`**, Android 10 (API 29), `arm64-v8a`, system firmware **`L1732.6.14.01.B10`** (`ro.software.version`; `ro.build.display.id` is `QP1A.190711.020`, and `ro.build.fingerprint` still reads `L1732.6.01.02`). InkControl depends on that firmware's stock launcher (`com.hmct.vision`), stock theme, and display-backend internals, so **other models, other system versions, or modified ROMs are likely incompatible** and are not guaranteed to work or be recoverable. Before changing devices or upgrading the system, review the risks below and back up first.

> **Signing migration:** rc13 introduced a fixed new release identity. Back up configuration and uninstall the old signed app before migrating to the new app and matching backend. Subsequent updates retain that identity.

## A look inside

<table><tr><th>简体中文</th><th>English</th></tr><tr><td><img src="docs/media/ui-zh.png" alt="Chinese features page in the cloud emulator" width="260"></td><td><img src="docs/media/ui-en.png" alt="English features page in the cloud emulator" width="260"></td></tr></table>

Actual rc13 cloud-emulator screenshots; they do not establish physical-panel quality. The app follows the system language by default and supports independent Chinese/English selection.

## Project status

The source targets **0.1.0-rc16**, with per-mode algorithms, independent hysteresis, auto refresh (tap/key/reading counters plus periodic triggering), a bilingual UI, a shared visual identity, and a versioned knowledge base. All compilation and validation runs in the cloud. New versions publish a Release after every check passes; rerunning an existing version never overwrites its Release.

The recorded device installation as of 2026-10-18 remains **rc10 app + rc10-gpu-fix1 backend**. [rc15 passed cloud signing, regressions, and four bilingual UI configurations](https://github.com/wzp100/InkControl/actions/runs/39098632837). Integrated HWC behavior, panel quality, power, temperature coverage, and long-term stability need separate acceptance. The historical first GPU error remains unexplained; the firmware's Google split-resource icon overlay should stay off. See [evidence boundaries](https://github.com/wzp100/InkControl/wiki/evidence.en).

## Developers and agents

Start with [AGENTS.md](AGENTS.md) and the [agent handbook](https://github.com/wzp100/InkControl/wiki/agent-handbook.en). [llms.txt](llms.txt) provides a short entry point; the [knowledge index](docs/wiki/knowledge-index.json) maps topics to code and evidence.

The feature catalog is the single configuration definition. The UI calls application services. See [contributing](CONTRIBUTING.en.md), [architecture](https://github.com/wzp100/InkControl/wiki/architecture.en), and [localization](docs/localization.en.md).

The repository is public, with Chinese and English documentation on the [native GitHub Wiki](https://github.com/wzp100/InkControl/wiki/Home.en). Original code and artwork follow [MIT](LICENSE). Vendor binaries, third-party assets, and external frameworks retain their own terms; see [NOTICE](NOTICE.en.md). Keys, passwords, plaintext firmware, personal backups, and raw device logs stay outside Git.

[Publication and Wiki maintenance](docs/publication.en.md)

## Risks and disclaimer

This project is experimental and remains a release candidate. **Use a spare device or a dedicated test device; it is not recommended for a primary phone used for communication, payments, or important data.** Passing cloud builds and automated tests does not certify stability on a physical device or compatibility with every device and firmware.

Root, Magisk modules, system-framework hooks, and display-backend changes may cause freezes, boot loops, failure to boot, data loss, or device damage. **Some failures may be unrecoverable. Disabling, uninstalling, or rolling back the module is not guaranteed to recover the device or its data.**

**Before installing, upgrading, or enabling features, back up photos, documents, chats, app data, and important configuration. Copy the backup to a computer or other independent storage and check that it is readable. Do not keep the only backup on the phone being tested.** Prepare recovery materials and a recovery method matching the device and firmware. If you do not know how to disable a module or restore the system, do not proceed yet.

The project is provided as is, without a promise of fault-free operation, data preservation, or recovery. Understand the risks and decide whether to use it; see the warranty disclaimer in [LICENSE](LICENSE). A backup reduces the risk of losing data but does not guarantee that the device can be repaired.
