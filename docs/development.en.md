# Adding a feature

[简体中文](development.md)

Define one observable behavior per feature. Give it a stable ID, finite validated values, dependency, process role, HOT/REBOOT policy, and experimental status in `core/FeatureCatalog`. Switches default to off.

The feature handler reads an immutable configuration snapshot and follows the stock path while disabled. Save the real original value before taking ownership of system state. On disable, restore it only if InkControl still owns that value; preserve independent user changes.

The UI calls application services. Storage, Root operations, IPC, and platform capabilities belong in adapters, not page callbacks or catalog definitions. Hooks collect events and delegate; renderers consume models without subscribing to unrelated system services.

Add focused tests for parameters, dependencies, defaults, disable/re-enable cycles, runtime acknowledgement, and restoration. Update `assets/i18n/en.tsv` for any changed display phrases and run the local static check. Push the change to run all compilation and regression in GitHub Actions. Do not compile locally or silently run a device-changing test.

The build exports backend `features.tsv` and `defaults.txt` from the Java catalog. Do not maintain a second independent schema. Runtime handlers report readiness with `RuntimeConfig.markReady()` and failures with `RuntimeConfig.fail()`; a log entry alone is not proof of success. Startup overrides require boot acknowledgements and a restoration path.

See [architecture](architecture.en.md), [cloud builds](cloud-build.en.md), and [localization](localization.en.md).
