# 诊断记录：GPU 显示后端「全刷后回到上一页」

- 日期：2026-10-19
- 分支：`fix/full-refresh-stale-frame`（本地，未推送）
- 状态：**已修复，真机验证通过**
- 关联文档（工作区）：`墨控全刷扫过BUG诊断报告.md`

## 1. 现象

开启 `display.gpu`（GPU 显示后端）时：

- 翻页后按指纹键 / 官方刷新接口触发**全刷（Clear / GC16）**，画面会回到**上一页 / 上一帧**；
- 再全刷一次恢复正常；
- 本质是「全刷取到了滞后的输入帧」。

关闭 `display.gpu` + 完整重启后现象消失 → 病灶锁定在 GPU 显示后端（见工作区报告步骤 D）。

## 2. 设备与软件状态（2026-10-19 复现时快照）

| 项 | 值 |
| --- | --- |
| 机型 | 海信 A5 Pro 经典版，`ro.product.device = HLTE203T` |
| 序列号 | `20200725224907` |
| 固件 | `ro.software.version = L1732.6.14.01.B10` |
| 构建号 | `ro.build.display.id = QP1A.190711.020 release-keys`，`ro.build.version.incremental` 门槛值 `L1732.6.01.02` |
| Android | SDK 29 / arm64-v8a / `zh-Hans-CN` |
| 墨控 App | `0.1.0-rc15`（versionCode 15） |
| 后端模块 | `inkcontrol_backend` `0.1.0-rc15`，`sys.inkcontrol.ready=1` |
| composer | `android.hardware.graphics.composer@2.1-service`（pid 3679） |
| 实际挂载 HWC | `/vendor/lib64/hw/hwcomposer.ums512.so` sha256 `a95b9f92…` = `payload/hwcomposer.default.so` |
| 实际挂载 GPU 库 | `/vendor/lib64/hw/libeinkgpu.so` sha256 `68adff36…`（本次为 `EINK_VERBOSE` 诊断构建，3220168 字节） |
| GPU 运行 | 全程 `gpu_errors=0`、无 fallback、无 `CONTROL_VERIFY_FAIL`；所有波 `CONTROL_VERIFY_END pass=1` |

### 复现时的配置（`/data/adb/inkcontrol/configuration.txt`，revision 108）

```
display.gpu=1
display.alg.clear=1          value=stock
display.alg.reading=0        value=default
display.alg.fast=0           value=default
display.alg.extreme=0        value=default
display.alg.video=0          value=default
display.hyst.*=0             value=default
display.sampling=0
display.idle=0
display.dynamic=0
display.apps=0
motion.master=0              （所有 motion.* 全关）
lock.*=0
experiment.cache=0
experiment.video=0           value=blue
```

实时属性：

```
debug.eink.alg.clear   = stock
debug.eink.alg.reading = default
debug.eink.alg.fast    = default
debug.eink.lock.dither = stock
debug.inkcontrol.sampling = 0
```

> 注意：复现时 `motion.master=0`、所有动画关，**BUG 仍出现** → 与 `gcsweep`/动画无关，纯 GPU 前端取帧问题。

## 3. 复现证据（verbose 日志，标签 `EinkGPU`）

一次全刷 = 一个 `CANDIDATE mode=2` 的波（Clear 组）。日志字段：
`FRONT wave=… input=<当前取到的缓冲> prev=<上一帧缓冲> diff=<差异像素数>`。
前端三缓冲在 `0..2` 间轮转；正常时 `prev` 就是上一波显示的帧。

### 3.1 第二次复现（13:06，用户报「13:06:16 又出现了」）

| wave | 时间 | 类型 | input | prev | diff | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| 277 | 13:06:03.179 | 内容 | 0 | 1 | 4760 | 显示旧页（buffer0） |
| 278 | 13:06:03.638 | 内容 | 2 | 0 | 215662 | 翻页 → 显示**当前页**（buffer2） |
| **279** | **13:06:04.698** | **Clear 全刷** | **0** | 2 | **215662** | 全刷取到 **buffer0 = 第 277 波的旧页** |
| 280 | 13:06:05.178 | 内容 | 2 | 0 | 214378 | 又切回当前页 |

第 279 波是 13:06:00–13:06:11 窗口内**唯一**的清屏全刷候选（`CANDIDATE mode=2`）。
它的输入 `buffer0` 正是翻页前那一页 → 全刷把面板刷回上一页；随后第 280 波又把当前页刷回来，观感即「闪一下 / 滞留」。

### 3.2 第一次复现（12:59–13:00，同样命中）

| wave | 类型 | input | prev | diff | 说明 |
| --- | --- | --- | --- | --- | --- |
| 222 | 内容 | 1 | 2 | 323260 | 显示旧页（buffer1） |
| 223 | 内容 | 0 | 1 | 8715 | 切到当前页（buffer0） |
| **224** | **Clear 全刷** | **1** | 0 | **8715** | 全刷取到 **buffer1 = 第 222 波的旧页** |

两次复现是**同一机制**：全刷波取到的输入缓冲是「本次内容变更之前」的那一帧。

## 4. 根因

GPU 输入前端（`native/service_input.hpp`）维护 `raw / pending / prev` 三块私有 `AHardwareBuffer` 的轮转：

- `hookTranspose`（`:78`）在每波开始读取 `*VAR_RAW` 得到当前输入缓冲；
- `runFront`（`:208`）在 GPU 上做转置 + 灰度 + 差异统计，`diff = |cur - prev|`；
- `hookStore`（`:255-262`，即 `:262`）在每波结束时：
  ```
  storePtr(VAR_RAW, inputs[prevRaw].map);   // 把“上一帧”缓冲轮转成下一个 raw 目标
  prevRaw = frontRaw;                        // 已消费帧成为 prev
  ```

该轮转**假定每一波 SurfaceFlinger 都会产出新帧**（注释：`SurfaceFlinger only fills raw while waiting`）。
但「指纹键 / 系统全刷（Clear）」由系统发起、**没有新的合成帧**：
`StockBridge::attempt()`（`service_import.hpp:288`，仅由 `hookDumpFrameBuffer` 在 SF dump layer 时调用）不会被触发，于是轮转出来的 `raw` 缓冲仍是旧内容。

前端对此**无从察觉**：`diff=215662` 说明它把「旧页面 vs 当前页」当成一次正常的大变更，于是照常把旧帧全刷上屏。

简言之：**全刷波在 SF 未出新帧时，取到了轮转出的旧缓冲，把「上一页」当成新画面全刷。**

## 5. 修复方案

只改前端取帧，不动 GPU 算法 / 波形：

- 用原厂自己的「最新目标」标志判定是否真有新帧——`hookDumpFrameBuffer` 发布 raw 帧时置 `base+0x9e350=1`，消费时清 0（`service_import.hpp:306,315`）；
- 在 `hookTranspose` / `hookStore`：**只有确认 SF 出了新帧才轮转/消费该缓冲**；否则复用**上一次已上屏的帧**（记住 `lastConsumed`），使全刷重放当前画面而非旧缓冲；
- 加 `VLOG`（`FRONT`/新增标记）便于复测核对。

### 5.1 改动点（`native/service_input.hpp`）

1. 新增状态：`lastConsumed`（上次已上屏的输入缓冲）、`sfPublishSeq` / `consumedSeq`（SF 出帧计数），以及安全开关
   `frontReuseEnabled()`（读 `debug.eink.front.reuse`，默认开，设 `0` 退回旧行为）。
2. `importRelease(...,commit=true)`：SF 每出一帧 `++sfPublishSeq`（即 `hookDumpFrameBuffer → StockBridge` 真正发布时）。
3. `hookTranspose`：`fresh = sfPublishSeq!=consumedSeq`；若**没有新帧**且存在 `lastConsumed`，则 `frontRaw=lastConsumed`
   （复用屏上那帧）并打印 `FRONT_REUSE`。
4. `hookStore`：消费后记录 `lastConsumed=frontRaw; consumedSeq=sfPublishSeq;`；仅当 `frontRaw!=prevRaw`（即本轮确实消费了新帧）时才轮转
   `raw/pending`，复用分支下保持角色不变。（这一行是必需的，否则复用分支会把 `VAR_RAW` 指到 `prevRaw`，导致 SF 覆写上一帧缓冲。）

编译：本地 NDK（`--target=aarch64-linux-android29 -std=c++17 -O2 -fPIC -shared -static-libstdc++ -DEINK_VERBOSE=1`）。

### 5.2 回滚 / 恢复

- 运行时退回旧行为（无需重启）：`su -c 'setprop debug.eink.front.reuse 0'`
- 完全回到原厂：`/data/local/tmp/ic_restore/` 内有原厂 `libeinkgpu.so.orig` 与 `checksums.sha256.orig`（覆盖回
  `payload/` + 更新 `checksums.sha256` 后重启）。

## 6. 复现 / 验证步骤

1. 本地 NDK 编译 `-DEINK_VERBOSE=1` 的 `libeinkgpu.so`；
2. 覆盖 `/data/adb/modules/inkcontrol_backend/payload/libeinkgpu.so` + 同步 `checksums.sha256`（脚本 `/data/local/tmp/ic_debug_swap.sh`）；
3. 完整重启；确认 `sys.inkcontrol.ready=1`、`/vendor/lib64/hw/libeinkgpu.so` 哈希 = 新库、composer 已加载；
4. 复现动作：翻到特征页 → 翻下一页 → 指纹键全刷；观察是否还回到上一页；
5. 抓 `logcat -s EinkGPU`，核对该 Clear 波的 `FRONT` 的 `input` 是否 = 上一波内容帧（而不是两波前的旧帧）。

## 7. 边界

- 新鲜帧判定采用 SF 经 import bridge 的实际发布计数（`sfPublishSeq`），不依赖未公开的原厂标志位，自洽且可回退。
- 该改动触及最敏感的取帧链路；覆盖范围见 §8，长期稳定（滚动手感、功耗）仍建议继续观察。
- `display.alg.clear=stock` 等子项与 BUG 的因果关系此前有矛盾，本记录以「GPU 前端取帧」为准。

## 8. 实机验证结果（2026-10-19）

部署：本地编译 → 覆盖 `payload/libeinkgpu.so` → 更新 `checksums.sha256`（`sha256sum -c` 全 OK）→ 完整重启。

| 项 | 结果 |
| --- | --- |
| 修复库 sha256 | `9a9ee151fd1a297e7013a4f34e109db499a2aa94e9ac4b292e9062cef14e9777`（3220584 字节） |
| 加载校验 | `/vendor/lib64/hw/libeinkgpu.so` 哈希 = 上式；composer（pid 3680）已映射；`sys.inkcontrol.ready=1`；无 error |
| 运行时遥测 | `helper_loaded=true`、`gpu_errors=0`、`cpu_waves=0`、`input_gpu_frames=750` |
| 修复触发实证 | 开机阶段即出现 `FRONT_REUSE wave=162 raw=0 reused=2` —— 正是「SF 无新帧、raw 指向旧缓冲」的场景 |
| 用户复现 | 翻页 + 指纹键全刷：**不再回到上一页**；滚动/锁屏等操作画面正常更新 |

结论：根因（全刷波取到轮转出的滞后缓冲）已修复并真机验证通过。
