# 当前状态与证据

[English](evidence.en.md) · [Wiki](README.md) · [来源登记](knowledge-index.json)

## 状态快照

| 对象 | 已记录状态 | 能证明什么 |
| --- | --- | --- |
| 手机，2026-10-18 交接 | rc10 App + rc10-gpu-fix1 后端 | 本轮文档/发布任务没有更新设备 |
| GPU 队列修复候选 | 完整重启、交替视频、12轮窗口、3轮睡眠唤醒；取证时1024波次/12032扫描、CPU波次0、GPU错误0 | 这批场景短期冒烟通过；历史首错与长稳未结案 |
| rc11 算法候选 | 核心810、策略1785、目标Mali与软件GLES各59场景零差异；六种模拟器算法页 | 选择规则、独立核与页面；未安装完整新App/后端 |
| rc13 云端候选 | 签名、包装、回归、软件GPU与四组双语页面全部通过 | 云构建与模拟器范围，尚无新版实机迁移 |
| 当前源码 | `AndroidManifest.xml` 中的版本；Release 包与该 commit/run 绑定 | 当前验证以对应 Release 附件为准 |

rc13 参考云运行：[39046131228](https://github.com/wzp100/InkControl/actions/runs/39046131228)，提交 `c9e4d7d7ca75214ab6b7a204392ad7adcb16a479`。其第一次运行曾在模拟器安装60秒超时；延长到180秒并补充失败日志后复核通过，失败记录没有删除。

## 已知问题与未完成项

- 历史慢路径和动画的首个 GPU 错误原日志缺失；队列竞态修复不能自动认定为全部历史故障根因。
- Google 图标位于 split 资源，当前固件 Overlay 建图未通过，保持关闭。
- system_server/HWC 重载后曾出现 Vector 重新注入失败，整机重启可恢复；自动自愈未完成。
- 新版 HWC 集成、温区、真实面板、真实歌词覆盖、拔线功耗及长期稳定需分别验收。

## 如何引用证据

Release 附 `build-manifest.json`、`package-verification.json`、`release-verification.json`、SHA256SUMS、双语发布说明；原始测试与 UI 证据留在同轮 Actions artifacts。清单含准确 commit、run ID、输入和源码哈希。它们不含实际设备验收声明。

本 Wiki 的 `Rxx` 引用解析到 [knowledge-index.json](knowledge-index.json)。`repository` 来源可随克隆读取；`workspace_only` 来源需完整本地工作区，SHA256 标识本次整理读取的报告版本。不要伪造仓库内不存在的原始日志链接，也不要为了方便阅读提交私有日志、签名或备份。

新增实验记录输入版本、设备状态、参数、原始日志、结论、恢复方式；保留失败尝试和旧报告，新增验证以新目录记录。来源：`R02`、`R03`、`R04`、`R11`。
