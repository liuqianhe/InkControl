# Contributing

[简体中文](CONTRIBUTING.md)

Read the [agent handbook](docs/wiki/agent-handbook.en.md) and [evidence](docs/wiki/evidence.en.md) first. The target is Hisense A5 Pro 经典版 (Classic Edition), monochrome. Maintain paired Wiki pages and sources; run `python tools/check_docs.py`. Successful new versions publish Releases automatically. Increment versions for code/resource changes; never replace published versions.

Use UTF-8. Make changes on a branch and use pull requests for cloud regression checks. Do not compile locally. Pushes to main run regression tests, GPU kernel comparisons, native compilation, and APK/Magisk packaging in GitHub-hosted Actions. Artifacts are retained for seven days. Pull requests run checks without private firmware inputs or signing secrets.

Update `assets/i18n/en.tsv` when changing UI wording, then run `python tools/check_i18n.py`. Translation is restricted to presentation. Persisted IDs, routes, property names, and internal status comparisons retain their original stable values. Changing language never changes feature configuration. Language selection needs no Root access.

New features must declare dependencies, default to off, support restoration, and report actual runtime status. Test observable behavior rather than duplicating implementation. Cloud success does not verify device panel behavior, framework hooks, or long-term stability; record device acceptance separately.

Keep source in `src/native/backend`, documentation in `docs`, and tests in `test`. Build outputs (`out`), raw device results (`results`), and signing material (`signing`) are local only. Existing dated local acceptance reports stay in place under Git ignore rules.

See [cloud builds](docs/cloud-build.en.md), [architecture](docs/architecture.en.md), and [localization](docs/localization.en.md).
