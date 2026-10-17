# InkControl · 墨控

[简体中文](README.md) · [Cloud builds](https://github.com/wzp100/InkControl/actions) · [Contributing](CONTRIBUTING.en.md)

E-ink enhancements for the **Hisense A5 Pro CC, HLTE203T, Android 10, firmware L1732.6.01.02**. One `com.inkcontrol` APK includes the matching Magisk backend. All **52 enhancements are off by default**.

The repository starts **private**. All compilation runs in GitHub-hosted Actions. The app supports **Simplified Chinese and English**, following the system by default, with an in-app language selector.

## Current status

The source targets **0.1.0-rc12**, including rc11's per-mode algorithms and independent hysteresis plus bilingual UI and cloud builds. Existing device validation applies to rc10 with the GPU queue fix. rc11 passed host regressions, 59 standalone GPU comparisons on Mali-G52, and six emulator page configurations. Those results do not establish integrated rc12 device acceptance.

The original signing key exists locally, but its passwords have not been found. Default cloud output is an **unsigned candidate that cannot be installed**. No replacement signing identity is generated. Signed builds require the original key secrets; see [cloud build setup](docs/cloud-build.en.md).

Known limits: the historical first GPU error lacks its original log and remains unresolved; the Google Settings icon overlay does not work with this firmware's split resources and should remain disabled. Physical-panel quality, power consumption, and long-term stability need separate acceptance.

## Features

- GPU display processing and separate algorithms for Clear, Reading, Fast, Extreme, and video. Grayscale and black-and-white algorithms cannot be mixed. Independent hysteresis appears only where supported. Existing defaults are preserved.
- Per-scenario transitions, waveform effects, temperature-dependent keyframe timing, and previews.
- Lock-screen styles, weather, lyrics, stock-launcher themes, and independently selectable components.
- Display-service protection, configuration restoration, lifecycle cleanup, and runtime status checks.

## Installation of a signed, verified build

1. Install the APK and open **Install** to check compatibility.
2. Export its matching backend ZIP and install it in Magisk.
3. Enable InkControl in a Vector/LSPosed-compatible framework for System Framework (`android`), System UI (`com.android.systemui`), and the stock launcher (`com.hmct.vision`).
4. Fully restart, check Diagnostics, and enable only the features you need.

Root is not needed to browse descriptions, choose a language, view demos, or export the bundled installer. Startup overrides require a full restart. Runtime acknowledgement, rather than a saved switch alone, determines whether a feature is active.

## Repository and builds

| Path | Purpose |
| --- | --- |
| `src`, `native`, `backend` | App, GPU implementation, Magisk backend |
| `assets`, `res`, `overlay`, `stubs` | Themes, translations, localized metadata, resource overlay, compile declarations |
| `test`, `tools` | Regression, build, verification, and archive tools |
| `.github/workflows`, `ci` | Cloud pipeline and pinned encrypted build dependencies |
| `docs` | Architecture, build, localization, and source documentation |

Push main for an automatic unsigned cloud build. Use Actions → Cloud build → Run workflow for explicit signing selection. PR checks receive no firmware or signing secrets. Tests and candidate artifacts are retained for seven days. CI does not install, flash, reboot, or publish a public release.

`inputs`, `signing`, `out`, `results`, raw device evidence, and backups remain local and are excluded from Git. Encrypted vendor build dependencies are separate from source and excluded from source exports. No SDK or NDK is checked in.

[Architecture](docs/architecture.en.md) · [Cloud builds and signing](docs/cloud-build.en.md) · [Localization](docs/localization.en.md) · [Sources](docs/sources.en.md) · [License boundaries](NOTICE.en.md)

Original implementations use MIT. Vendor binaries, third-party artwork, trademarks, and external runtimes retain their own rights. This repository alone cannot recreate the vendor HWC inputs. Keep the repository private until a separate public-distribution review.
