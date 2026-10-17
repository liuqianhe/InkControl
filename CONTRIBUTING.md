# 参与开发

[English](CONTRIBUTING.en.md)

先读 [Agent 手册](docs/wiki/agent-handbook.md) 和 [证据](docs/wiki/evidence.md)。目标设备统一称 Hisense A5 Pro 经典版，黑白屏。Wiki 修改需维护中英配对与来源，执行 `python tools/check_docs.py`。新版本全检查通过后自动私有 Release；代码和资源变化递增版本，已有发布不覆盖。

使用 UTF-8。修改源码后提交到分支，通过 Pull Request 的云端回归检查。不要在本地编译；推送 main 后 GitHub Actions 完成回归、GPU 核对照、原生编译、APK 与 Magisk ZIP 构建，产物保存七天。PR 仅跑无私有输入的回归，不读取密钥或解密厂商依赖。

更改界面文字时更新 `assets/i18n/en.tsv`，运行 `python tools/check_i18n.py`。界面层翻译显示内容，配置 ID、路由、属性名及状态判断继续使用稳定的原值，语言切换不修改功能配置。无需 Root 即可选择语言。

新增功能须登记依赖、默认关闭、停用恢复及实际生效状态。测试验证用户可见行为，避免只复制实现逻辑。上机验收与云端构建分别记录；Actions 成功不代表目标机面板、Hook 或长期稳定性通过。

源码、文档、测试分别放在 `src/native/backend`、`docs`、`test`；输出放 `out/`，原始实机结果放 `results/`，签名放 `signing/`，后几项不提交。原有本地历史验收报告仍保留在 `docs/20*.md`，由 Git 忽略。

详细说明：[云端构建](docs/cloud-build.md) · [架构](docs/architecture.md) · [双语维护](docs/localization.md)。
