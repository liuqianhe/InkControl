# 墨控仓库 / InkControl repository

- Target device: **Hisense A5 Pro 经典版 (Classic Edition), monochrome E-ink**. Use this name in current product documentation.
- Documentation is maintained in **Chinese only** (since 2026-10-23): the English `README.en.md` and other English docs are **no longer updated or maintained**; `README.md` (Chinese) is the primary document. Keep the Chinese Wiki and source references current. Run `python tools/check_docs.py` after Chinese documentation edits.
- Signed builds on main publish a versioned Release only after all checks pass. Increment app/backend/build versions together for behavior or asset changes; never overwrite an existing published version.
- 面向用户的回复用中文；文件统一 UTF-8。PowerShell `Get-Content` 必须显式 `-Encoding utf8`，不得用 `-Raw`。
- All compilation and build validation runs in GitHub-hosted Actions. Do not compile APKs, native libraries or Java locally. Local text edits and static checks are allowed.
- 所有编译及构建验证走 GitHub 托管 Actions；本地只编辑和做静态检查。
- Keep feature IDs, backend properties, configuration syntax and default values independent of UI language. Run `python tools/check_i18n.py` after changing user-visible source phrases; maintain English translations in `assets/i18n/en.tsv`.
- Never commit private signing keys, unencrypted HWC inputs, device backups, raw logs, build outputs or local acceptance reports. CI dependencies in `ci/private-inputs.zip.enc` are encrypted; the password belongs in Actions secrets.
- Do not install, mount, flash, reboot or run device-changing tests as part of repository maintenance or CI. Real-device acceptance is a separate explicit task.
- 默认配置不因仓库整理、语言切换或云构建而改变；历史报告与发布版本不可覆盖。
- The user authorized public publication after the 2026-10-18 sensitive-data review. Maintain the native GitHub Wiki from docs/wiki; audit full Git history, Actions logs/artifacts and Release assets before expanding public content. Third-party binaries and artwork remain outside MIT; see NOTICE.md. Never put personal access tokens in workflows or Wiki pages.
- Keep bilingual risk notices in README and Wiki installation guidance; since rc16 Release descriptions/notes (`docs/releases/*.md`) are Chinese-only. Every Release description must still recommend spare/test devices, require an off-device backup reminder, and disclose boot failure, data loss and potentially unrecoverable damage; never promise rollback can rescue the device.

## 锁屏与本机验收经验

- 自定义锁屏样式 5–8 在亮屏锁屏时，原厂 `BottomBarController.getBottomBar()` 仍会叠加电量、`上滑解锁`和静音图标；`BottomBarHook` 必须在 `status == 1` 且样式为 5–8 时跳过，不能只在熄屏（`!screenOn`）时跳过。
- 新增或修改 App 配置项后，必须同步生成并安装同一版本的 `inkcontrol_backend` 模块，尤其是 `backend/lib/features.tsv`。只更新 APK 会让 `ic_validate` 拒绝既有配置，表现为“环境未就绪”、`sys.inkcontrol.ready=0`，并写入 `skip_mount`；安装匹配模块并完整重启后恢复。
- 真机验收时用 `inkctl status` 确认 `ready=1`、HWC 为 patched d1ba（`a95b...`）；不要用旧后端模块搭配新 APK 做锁屏测试。
