# 墨控仓库 / InkControl repository

- 面向用户的回复用中文；文件统一 UTF-8。PowerShell `Get-Content` 必须显式 `-Encoding utf8`，不得用 `-Raw`。
- All compilation and build validation runs in GitHub-hosted Actions. Do not compile APKs, native libraries or Java locally. Local text edits and static checks are allowed.
- 所有编译及构建验证走 GitHub 托管 Actions；本地只编辑和做静态检查。
- Keep feature IDs, backend properties, configuration syntax and default values independent of UI language. Run `python tools/check_i18n.py` after changing user-visible source phrases; maintain English translations in `assets/i18n/en.tsv`.
- Never commit private signing keys, unencrypted HWC inputs, device backups, raw logs, build outputs or local acceptance reports. CI dependencies in `ci/private-inputs.zip.enc` are encrypted; the password belongs in Actions secrets.
- Do not install, mount, flash, reboot or run device-changing tests as part of repository maintenance or CI. Real-device acceptance is a separate explicit task.
- 默认配置不因仓库整理、语言切换或云构建而改变；历史报告与发布版本不可覆盖。
- Keep this repository private. Public publication requires a separate user request and a review of binary dependencies and artwork licensing.
