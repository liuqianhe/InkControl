# 墨控仓库 / InkControl repository

- Target device: **Hisense A5 Pro 经典版 (Classic Edition), monochrome E-ink**. Use this name in current product documentation.
- Documentation is maintained in **Chinese only** (since 2026-10-23): the English `README.en.md` and other English docs are **no longer updated or maintained**; `README.md` (Chinese) is the primary document. Keep the Chinese Wiki and source references current. Run `python tools/check_docs.py` after Chinese documentation edits.
- Signed builds publish a versioned Release only after all checks pass, and only when triggered manually (`workflow_dispatch` on `main`, since 2026-10-24): pushing to `main` no longer starts a cloud build; pull requests still run the regression checks only. Increment app/backend/build versions together for behavior or asset changes; never overwrite an existing published version.
- 签名构建在所有检查通过后发布版本化 Release，且自 2026-10-24 起仅限**手动触发**（在 Actions 页对 `main` 分支 Run workflow）；推送 `main` 不再自动构建，PR 仍只跑回归检查。行为或素材变更时同步递增 app/后端/构建版本号，不得覆盖已发布版本。
- 面向用户的回复用中文；文件统一 UTF-8。PowerShell `Get-Content` 必须显式 `-Encoding utf8`，不得用 `-Raw`。
- All compilation and build validation runs in GitHub-hosted Actions. Do not compile APKs, native libraries or Java locally. Local text edits and static checks are allowed.
- 所有编译及构建验证走 GitHub 托管 Actions；本地只编辑和做静态检查。
- Keep feature IDs, backend properties, configuration syntax and default values independent of UI language. Run `python tools/check_i18n.py` after changing user-visible source phrases. English UI translations are **no longer maintained** (since 2026-10-24): `check_i18n.py` reports missing entries as notes instead of failing the build; existing `assets/i18n/en.tsv` entries stay for the in-app English UI.
- 功能 ID、后端属性、配置语法与默认值不得依赖界面语言。修改用户可见源码文案后运行 `python tools/check_i18n.py`。**英文界面翻译自 2026-10-24 起停止维护**：`check_i18n.py` 对缺失翻译只提示不阻断；`assets/i18n/en.tsv` 已有条目保留（App 内英文界面仍可用）。
- Never commit private signing keys, unencrypted HWC inputs, device backups, raw logs, build outputs or local acceptance reports. CI dependencies in `ci/private-inputs.zip.enc` are encrypted; the password belongs in Actions secrets.
- Do not install, mount, flash, reboot or run device-changing tests as part of repository maintenance or CI. Real-device acceptance is a separate explicit task.
- 默认配置不因仓库整理、语言切换或云构建而改变；历史报告与发布版本不可覆盖。
- The user authorized public publication after the 2026-10-18 sensitive-data review. Maintain the native GitHub Wiki from docs/wiki; audit full Git history, Actions logs/artifacts and Release assets before expanding public content. Third-party binaries and artwork remain outside MIT; see NOTICE.md. Never put personal access tokens in workflows or Wiki pages.
- Keep bilingual risk notices in README and Wiki installation guidance; since rc16 Release descriptions/notes (`docs/releases/*.md`) are Chinese-only. Every Release description must still recommend spare/test devices, require an off-device backup reminder, and disclose boot failure, data loss and potentially unrecoverable damage; never promise rollback can rescue the device.

## 锁屏与本机验收经验

- 自定义锁屏样式 5–8（编辑排版/瑞士/像素/玻璃）在锁屏时会与原厂 `BottomBarController.getBottomBar()` 画的底部图标条（电量 + 上滑解锁，亮屏锁屏右侧还有静音）重叠。`BottomBarHook` 在 **system_server** 进程 hook 该方法，判定样式键 `Settings.Global.eink_keyguard_style` 处于 **5–8** 区间时直接 `setResult` 跳过原厂 bottom bar（见 `BottomBarHook.java` 第 49 行 `style >= LockStyle.EDITORIAL && style <= LockStyle.GLASS`）。
- **判定必须用样式范围 5–8，禁止依赖 `LockStyle.custom()` / `RuntimeConfig.enabled("lock.style")`**：`BottomBarHook` 在 system_server 内运行，其 `RuntimeConfig` 是**开机时的配置快照**；用户在 App 端后来开启 `lock.style` 不会重新加载 system_server，导致 `custom()` 误判未启用而把原厂图标条叠上去。样式键 5–8 本身即代表已选用墨控锁屏，是最可靠的信号。（早期版本要求 `status == 1` 且样式 5–8 才跳过，是不完整的——现仅按样式范围判定。）
- **改 `BottomBarHook` 后必须整机重启**：它活在 system_server，`pkill com.android.systemui` 只重启 SystemUI，不会重载 system_server 模块，修复会“看似无效”。只有整机重启才能让 hook 与 `RuntimeConfig` 快照刷新。
- “环境未就绪 / 安装未完成 / `sys.inkcontrol.ready=0`”有两类独立成因，Recovery 路径不同：
  1. **配置校验 `ic_validate`**：`configuration.txt` 中出现 `lib/features.tsv` 未登记的未知 key（如新增功能项没同步生成后端 tsv）。修复＝保持 features.tsv 与所有配置 key 同步（重建并安装同版本后端模块）。
  2. **模块完整性校验 `post-fs-data.sh:7` 的 `sha256sum -c checksums.sha256`**（本次真实成因）：在设备上直接改 `lib/features.tsv` / `lib/defaults.txt` 后若没重算 `checksums.sha256`，哈希对不上 → 写 `error="模块文件校验失败"`、打 `skip_mount`、模块不挂载、`ready=0`。修复＝重算 `checksums.sha256`（用 `find . -type f ! -path "./system/*" ! -name "*.bak" ! -name "checksums.sha256" -exec sha256sum {} \;`，排除开机 `display.gpu=1` 时动态复制进 `system/` 的 GPU .so 覆盖层、`*.bak` 备份及清单自身）后整机重启；或干净地重建并安装同版本后端模块，让 features.tsv/defaults.txt/checksums 由构建一次性生成。注：沙箱内 `adb push` 整目录可能被拦截，设备上就地重算 `checksums.sha256` 是可行的回退手段。
- 真机验收时用 `inkctl status` 确认 `ready=1`、HWC 为 patched d1ba（`a95b...`）；不要用旧后端模块搭配新 APK 做锁屏测试。
