# 构建与归档

[English](cloud-build.en.md) · [当前云端流程](cloud-build.md)

2026-10-17 起所有编译迁到 GitHub 托管 Actions。本页原命令用于说明底层构建机制，请在云端运行，不再在本机执行编译。签名保留原证书，不能因缺密码新建替代密钥。

## 工具链

固定 Android SDK Platform 33、Build Tools 35.0.0、NDK r29；JDK 21（本机实际为 21.0.8），Java 源码目标为 8，设备最低 API 29。Python 3.12 或更高版本，只使用标准库。电脑端 shell 测试使用 Bash；Windows 推荐 Git Bash。

通过 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_NDK_HOME` 或命令参数传入路径。不能将工具、用户名或私钥路径写进源码。`stubs/` 只是 Xposed 编译声明，不进入 APK；框架 API 由用户安装的框架提供。构建时不联网下载依赖。

## 私有输入

`--inputs` 目录必须包含以下两个文件：

| 文件 | SHA256 |
| --- | --- |
| `hwcomposer.stock.so` | `d1ba01bf135151580c41dc7de13dd1c80876094c8022f58fe9ceecd0c9fb0988` |
| `hwcomposer.motion.so` | `adbdf9aeb1868e1fb9bf01394a8f67942adb9b3c97a93d6ec2d168f83e60a675` |

前者是目标固件的原厂库，后者是准确匹配 lock-motion-v6 的既有 HWC 派生输入。构建默认空闲策略时只恢复 0x92bcc–0x92bd4 的原厂 8 字节，其余修改不变。Google 图标覆盖从 `overlay/` 源码编译，不使用输入 APK。输入不属于公开源码，不自动下载、不猜测版本、不跳过校验。

## 命令

以下 PowerShell 示例在项目根目录执行，中文环境先设置 UTF-8：

```powershell
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python tools/test.py --jdk $env:JAVA_HOME
python tools/build.py --name candidate-new --jdk $env:JAVA_HOME --sdk $env:ANDROID_HOME --ndk $env:ANDROID_NDK_HOME --keystore $env:INKCONTROL_KEYSTORE --key-alias inkcontrol
```

签名密码从 `INKCONTROL_STORE_PASSWORD`、`INKCONTROL_KEY_PASSWORD` 环境变量读取。首次构建请自行创建签名密钥；本地候选使用独立测试证书，私钥不随源码或交付包发布，后续公开发行前要固定正式签名。

现有密钥密码暂不可用时，可以增加 `--unsigned --certificate <现有cert.der>` 生成待签名候选，不需要提供 `--keystore`。公钥证书用于保留既有签名校验契约，不能替代签名密码。输出 App 和后端 ZIP 名称均含 `-unsigned`；其中 App 和内置 Overlay 未签名，不能用于安装发布。取得原密码后以原密钥重新运行完整构建，使 Overlay、后端 ZIP、内嵌哈希和 App 一致；不能只给外层 APK 补签。正式归档命令拒绝 unsigned 候选。

构建顺序是核心检查、目录白名单生成、原生库及观察器、图标覆盖、Magisk ZIP、Java/Dex/资源、APK 签名与完整性。新建输出目录，已有构建名称直接拒绝；中间失败留在该候选目录供排查。

Windows 下资源编译使用项目相对路径；嵌套 assets 由 Python 以标准 ZIP 路径写入，避免 aapt2 的反斜线头部差异。签名校验之外再检查整个 ZIP 的 CRC 和嵌套 ZIP 哈希。

完成实际验证并保存摘要后归档，例如：

```powershell
python tools/verify_package.py --build out/candidate-new
python tools/archive.py --build out/candidate-new --tests out/tests-current/results.json --verification docs/2026-10-15_墨控实机验收.md --device-report out/device-acceptance.json --destination '../../06_发布产物/inkcontrol/v版本号'
```

示例路径应替换成当次实际结果。归档要求完整电脑测试、当前 APK/ZIP 对应的实机或模拟器摘要及包装验证记录；只做模拟器验证时改用 `--emulator-report`，不能冒充实机。摘要必须包含失败项与未覆盖范围，归档不会自动生成通过结论。公开源码 ZIP 使用显式目录白名单，排除 `inputs/signing/out/results/.git`、缓存和测试临时目录；交付目录首次创建后不覆盖。构建和归档均不会执行 adb、安装、挂载、刷写或重启。

`tools/test_device_*.py` 是独立实机验收工具，会修改开关、操作界面，恢复脚本还会切换模块并重启；只能在用户明确授权后使用。每次传入已核实的会话文件，始终指定实体设备序列号。原始结果和备份留在本地，不随源码发布。
