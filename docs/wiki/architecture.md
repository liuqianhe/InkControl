# 源码地图与架构

[English](architecture.en.md) · [Wiki](README.md) · [详细业务规则](../architecture.md)

## 去哪里改代码

| 仓库相对路径 | 职责 |
| --- | --- |
| `src/com/inkcontrol/core` | 功能目录、配置、依赖、恢复规则；无 Android 依赖 |
| `src/com/inkcontrol/ui` | 页面、Guide、预览；只调用应用服务 |
| `src/com/inkcontrol/platform` | Android 组合、AtomicFile、Provider、RootBridge、Hook 路由与回执 |
| `src/com/inkcontrol/features` | motion、lock、desktop、fix 处理器 |
| `src/com/inkcontrol/shared` | 天气图标／解析、媒体快照等实际共用逻辑 |
| `src/com/inkcontrol/i18n` | 纯 Java 显示短语翻译 |
| `native/service_gpu.cpp` | GPU 服务与生产着色器 |
| `native/service_algorithm_policy.hpp` | 纯算法选择策略 |
| `native/service_queue_retry.hpp` | 输入队列稳定选择与有界重试 |
| `backend` | Magisk 生命周期、白名单控制器、启动保护与条件恢复 |
| `test`、`tools` | 回归／探针与云端构建、包装、发布工具 |
| `ci`、`.github/workflows` | 固定工具链、加密输入、公钥与 CI |

## 配置从哪里来

唯一用户真源是 App 设备保护存储 `files/configuration.txt`，AtomicFile 提交。后端 `/data/adb/inkcontrol/configuration.txt` 是生成的启动输入；`applied.txt`、`boot.txt` 是回执。不要把后端副本当成另一份用户配置编辑。

`FeatureCatalog` 同时驱动 UI、校验、测试与构建生成的 `features.tsv/defaults.txt`。ID、有限选项、依赖、角色、HOT／REBOOT 与实验标记集中定义。缺失的新算法字段按兼容规则保留默认，不自动重写旧修订号。

用户修改经过依赖／参数检查 → 保存期望快照 → 执行 → 等待回执。失败保留期望值并明确提示重试；不会仅因保存成功就显示已生效。父级关闭保留子项选择。

## 回执、权限与恢复

Hook 回执要匹配 PID、`/proc/PID/stat` starttime、版本、修订号；启动项还要匹配 boot_id 与启动快照。GPU 另需当前 helper、有效遥测、零错误。进程更新或回执丢失不能复用旧成功。

Provider `com.inkcontrol.state` 按 UID／角色限制访问，无外部任意配置写入、文件访问或 Root 执行接口。显示服务权限增强绑定构建时公钥摘要。RootBridge 仅调用固定 `inkctl`，用户值走 stdin 与白名单解析。

接管原厂状态保存 before/written 账本；只有当前值仍等于自身写入值时恢复。每应用 XML 使用字段级合并，保留后来用户改动。独立 Magisk 停用／卸载入口负责恢复。监测复用启动服务，不新增 Android 常驻服务。

来源：`R01`、`R04`；原始迁入来源见 [source-provenance.json](../source-provenance.json)。迁入哈希不是当前修改后源码哈希，当前来源以构建清单为准。
