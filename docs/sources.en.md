# Sources and dependencies

[简体中文](sources.md)

InkControl consolidates the workspace's transition animation, lock-screen/lyrics, stock-launcher theme, e-ink fixes, GPU display, and configuration guard projects. The authoritative integration lives in this repository; normal cloud builds do not read sibling projects or machine-specific folders.

The native baseline comes from the lock-motion-v6 implementation, with independent feature gates, queue retry protection, per-mode algorithm policy, and runtime telemetry. This is distinct from older experimental v17 work. Xposed stubs are compile-time declarations only; the framework is supplied at runtime. Lyric Getter's Parcel compatibility declaration retains its protocol-required package name.

Build tools are Python 3.12, JDK 21 targeting Java 8, Android Platform 33, Build Tools 35.0.0, and NDK r29. Magisk and Vector/LSPosed are external runtime requirements. No complete SDK, NDK, framework, or firmware image is included in source.

Two version-locked HWC dependencies are required. Their hashes are listed in `ci/inputs.json` and independently checked by `tools/build.py`. Only encrypted copies are in the repository; their password is an Actions secret. Private signing keys, user backups, raw device logs, build outputs, and local dated acceptance reports are excluded.

Original code and self-created theme assets use MIT. Vendor binaries, Google artwork, trademarks, and external tools retain their own rights. See [NOTICE](../NOTICE.en.md). Public visibility does not grant MIT rights to third-party assets. The release signing identity is fixed from rc13 onward; published assets and provenance are recorded on GitHub Releases.
