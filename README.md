# 墨控 · InkControl

[English](README.en.md) · [云端构建](https://github.com/wzp100/InkControl/actions) · [参与开发](CONTRIBUTING.md)

面向 **海信 A5 Pro CC（HLTE203T，Android 10，固件 L1732.6.01.02）** 的墨水屏增强套件。一个 `com.inkcontrol` APK 内置配套 Magisk ZIP，**52 个增强全部默认关闭**。

仓库首先以**私有仓库**维护。**所有编译走 GitHub Actions 云端**。应用支持**简体中文和英文**，默认跟随系统，右上角可独立选择语言。

## 当前状态

源码版本为 **0.1.0-rc12**：包含 rc11 分模式算法和独立迟滞、本轮双语及云构建改造。手机已验收范围仍是 rc10 App + GPU 队列修复后端。此前 rc11 已通过电脑回归、目标 Mali-G52 独立 GPU 核 59 场景对照及六种模拟器页面配置；这些不能替代 rc12 完整设备集成验收。

原签名密钥保存在本机，但暂未找到密码。默认云构建生成**不可安装的未签名候选**；不创建替代签名。原签名 secrets 配置后，可选择 signed 构建。详见[云端构建与签名](docs/cloud-build.md)。

已知限制：历史 GPU 首错原日志丢失，根因未确定；本固件 Google 拆分资源图标覆盖未生效，应保持关闭。面板观感、功耗和长稳仍需独立验收。

## 功能

- GPU 显示处理，清晰、阅读、快速、极速和视频各自选择算法；灰阶与黑白不能混选，迟滞仅在支持时显示，保留现有默认。
- 按场景过渡动画、波形效果、随温度调整的关键帧停留、效果预览。
- 锁屏样式、天气、歌词、原厂桌面主题及独立换肤项目。
- 显示服务保护、配置恢复、生命周期清理、真实运行状态检查。

## 已签名并验证版本的安装

1. 安装 APK，进入“安装”检查适配环境。
2. 从 App 导出匹配的后端 ZIP，在 Magisk 中从本地安装。
3. 在 Vector/LSPosed 类框架中启用墨控，勾选系统框架 `android`、系统界面 `com.android.systemui` 和原厂桌面 `com.hmct.vision`。
4. 完整重启，检查“诊断”，再按需开启增强。

无 Root 可浏览说明、选择语言、看演示和导出安装包。启动期覆盖需要完整重启。保存开关不等于已生效，以当前进程回执和后端确认判断状态。

## 目录与构建

| 路径 | 用途 |
| --- | --- |
| `src`、`native`、`backend` | App、GPU 实现、Magisk 后端 |
| `assets`、`res`、`overlay`、`stubs` | 主题、翻译、双语资源、图标覆盖和编译声明 |
| `test`、`tools` | 回归、构建、校验和归档工具 |
| `.github/workflows`、`ci` | 云端流水线、固定工具链及加密私有依赖 |
| `docs` | 架构、构建、双语和来源说明 |

推送 main 自动云构建未签名候选；Actions → Cloud build → Run workflow 可显式选择签名模式。PR 检查不读取固件或签名 secrets。测试和候选产物保留七天。CI 不操作手机、不刷写、不重启、不自动公开发布 Release。

`inputs`、`signing`、`out`、`results`、实机原始证据和备份留在本地，不进入 Git。厂商构建输入加密单独保存，源码导出不包含它们。仓库不附 SDK/NDK。历史带日期的本地验收报告保留原路径，但不随仓库上传。

[架构](docs/architecture.md) · [云端构建](docs/cloud-build.md) · [双语维护](docs/localization.md) · [来源依赖](docs/sources.md) · [许可边界](NOTICE.md)

原创实现按 MIT 整理；厂商二进制、第三方图形、商标和外部运行环境仍适用原权利。仅凭本仓库不能重新生成厂商 HWC 输入。公开分发前应单独复核许可与签名，本轮保持私有。
