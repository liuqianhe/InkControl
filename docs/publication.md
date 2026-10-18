# 公开仓库与文档维护

[English](publication.en.md) · [原生 Wiki](https://github.com/wzp100/InkControl/wiki)

2026-10-18 经用户授权完成公开前敏感信息审查。范围包括完整 Git 历史、10 次 Actions 日志、28 份构建附件、已发布候选及嵌套 APK/ZIP、原生 Wiki 导出。Gitleaks v8.30.1 历史扫描没有密钥发现；日志中的 15 项通用密钥告警全部确认为 APK 公钥指纹。另对实际签名密码、私钥库片段及云端依赖密码进行精确匹配，未发现泄漏。

审查发现历史提交使用个人邮箱，按用户选择将全部作者和提交者改为 GitHub 隐藏邮箱，保持源码文件树一致。为隔离历史 Release/Actions 引用，旧仓库保留为私有历史归档，清理后的仓库接续 `wzp100/InkControl` 地址；旧 SHA 与新 SHA 的映射及完整备份只在维护者本地保存。早期云端运行与私有发布属于历史证据，其提交 SHA 不能直接视为清理后历史的 SHA。

公开候选从 rc15 继续；旧私有 rc14 不覆盖。新仓库沿用 rc13 起的固定发行签名，云端依赖仍为相同输入哈希，解密密码已更新。公开可见性不向第三方二进制或图形授予 MIT 权利，范围见 [NOTICE](../NOTICE.md)。私钥、密码、个人备份、固件镜像及原始设备日志继续留在仓库外。

## Wiki 的维护源与发布

人类在线阅读使用 GitHub 原生 Wiki；`docs/wiki` 是同一内容的维护源，便于审查、离线克隆和 agent 检索。修改源文件的中英配对，运行 `python tools/check_docs.py`，再执行：

```text
python tools/export_wiki.py --destination out/native-wiki-<唯一编号>
git clone https://github.com/wzp100/InkControl.wiki.git out/wiki-checkout-<唯一编号>
```

将导出的 24 个 Markdown 文件复制到 Wiki checkout，检查差异后用 GitHub 隐藏邮箱提交并推送其默认分支。导出器转换正文链接，生成 Home、双语侧边栏与页脚。首次 Wiki 需要在 GitHub 网页保存 Home 页面后才能克隆。不要把源码仓库整体复制进 Wiki，不要上传签名、原始日志或个人访问令牌；网页直接修改的内容应先同步回维护源。

所有 Java、原生库、资源、APK 编译及构建测试继续在 GitHub 托管 Actions 执行。文档导出与静态核查可以本地执行。云端验证、公开发布和实机验收分别记录；仓库维护不操作手机。
