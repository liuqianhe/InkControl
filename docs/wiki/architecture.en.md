# Code map and architecture

[简体中文](architecture.md) · [Wiki](README.en.md) · [Architecture overview](../architecture.en.md)

| Repository path | Responsibility |
| --- | --- |
| `src/com/inkcontrol/core` | Catalog, configuration, dependencies, restoration; Android-free |
| `src/com/inkcontrol/ui` | Pages, guides, previews; application-service calls only |
| `src/com/inkcontrol/platform` | Android composition, AtomicFile, Provider, RootBridge, Hook routing, acknowledgements |
| `src/com/inkcontrol/features` | Motion, lock, desktop, and fix handlers |
| `src/com/inkcontrol/shared` | Actual shared weather/media logic |
| `src/com/inkcontrol/i18n` | Pure Java display translation |
| `native/service_gpu.cpp` | GPU service and production shaders |
| `native/service_algorithm_policy.hpp` | Pure algorithm-selection policy |
| `native/service_queue_retry.hpp` | Stable input-queue selection and bounded retry |
| `backend` | Magisk lifecycle, fixed controller, protection, conditional restoration |
| `test`, `tools`, `ci`, `.github/workflows` | Tests, cloud build/publish tools, encrypted inputs, trust certificate |

The single user configuration source is device-protected app `files/configuration.txt`, committed through AtomicFile. Backend `/data/adb/inkcontrol/configuration.txt` is generated boot input; `applied.txt` and `boot.txt` are acknowledgements, not another user-editable source.

FeatureCatalog drives UI, validation, tests, and generated `features.tsv/defaults.txt`. IDs, finite values, dependencies, process roles, HOT/REBOOT, and experimental flags are defined together. New optional algorithm fields preserve legacy defaults and revisions.

Changes pass validation, save desired intent, apply, and await acknowledgement. Application failure retains intent and offers retry. Parent disable preserves child selections.

Hook acknowledgement matches PID, `/proc/PID/stat` starttime, version, and revision. Startup behavior also matches boot_id and boot snapshot. GPU confirmation requires the current helper, telemetry, and zero errors. Stale process success is not reused.

The Provider restricts callers by UID and role and exposes no arbitrary configuration writes, file access, or Root execution. Permission enhancement binds the build certificate. RootBridge calls only fixed `inkctl`, with values through stdin and whitelist parsing.

Restoration uses before/written ledgers and restores only values still owned by InkControl. Field-level XML merges preserve later user edits. Magisk disable/uninstall handles recovery. Monitoring reuses the startup service; no additional Android resident service exists.

Sources: `R01`, `R04`. [source-provenance.json](../source-provenance.json) records imported originals, not current modified-file hashes. Current source provenance belongs to the build manifest.
