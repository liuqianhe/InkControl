# GitHub 云端构建

[English](cloud-build.en.md)

仓库最初为私有。所有 Java、原生库、资源、Dex、Overlay、Magisk ZIP 与 APK 编译均在 `.github/workflows/build.yml` 的 GitHub 托管 Ubuntu 24.04 运行器执行。本地工具保留为可复现底层入口，不再在本地运行编译。

推送 main 自动构建未签名候选。Actions → Cloud build → Run workflow 可选择 unsigned 或 signed。PR 执行无私有输入的 Java/后端回归、翻译检查、原生策略/队列及软件 GLES 核对照；不读取固件和签名 secrets，不使用 pull_request_target。

固定工具链：Python 3.12、Temurin JDK 21（Java 8 目标）、Platform 33、Build Tools 35.0.0、NDK 29.0.14206865、设备 API 29 arm64。依赖安装在 runner 中，未引入本机路径。Actions 引用固定提交 SHA，令牌只读 contents，checkout 不持久化凭据。

## 私有依赖

`ci/private-inputs.zip.enc` 仅包含两个准确匹配的 HWC 输入，经 OpenSSL AES-256-CBC、PBKDF2、200000 次迭代加密。文件哈希及解密后的输入哈希均在 `ci/inputs.json`；构建器还会按自身固定值再次核对。解密密码只在 `INKCONTROL_INPUT_PASSWORD` Actions secret。解密文件位于 runner 临时目录，成员名严格白名单，退出清理，不上传明文依赖或整个临时目录。

这些依赖仍是厂商私有二进制，不属于 MIT 许可。产出的 APK/ZIP 自然包含运行所需的派生库，下载权限由私有仓库控制。源码导出排除加密依赖；改变仓库可见性前须单独审查分发边界。

## 签名

`ci/trusted-certificate.der` 仅为当前已安装版本的公钥证书，不能用于签名。未签名产物带 `-unsigned`，App 和 Overlay 均未签名，不能安装。

signed 构建需要以下 Actions secrets：`INKCONTROL_KEYSTORE_BASE64`、`INKCONTROL_STORE_PASSWORD`、`INKCONTROL_KEY_PASSWORD`、`INKCONTROL_KEY_ALIAS`。应由维护者在 GitHub 设置页面或通过标准输入配置，不写入命令参数、文档或日志。本轮未找到原密钥密码，故不生成新密钥、不更换现有签名，也不声称已完成签名构建。

构建器先签 Overlay，再计算后端 ZIP 哈希并嵌入 App，最后签 App。云端核对输出公钥必须与当前可信证书一致，防止无法覆盖升级。不能只给外层 APK 补签。

## 证据与产物

每次运行生成唯一目录和七天保留的 artifacts：测试输出、APK、后端 ZIP、SHA256SUMS、构建来源清单及包装验证。记录 commit SHA、run ID 和 attempt。不会自动公开 Release、安装设备或改变设备配置。

离线回归与软件 GLES 成功不等于真实 Mali、HWC 集成、面板观感或长稳验收通过。现有设备仍运行 rc10 修复版；rc12 包含 rc11 的分模式算法改动与本轮双语/云构建改造，需原签名可用后独立上机验收。

依据：[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[安全使用 Actions](https://docs.github.com/en/actions/reference/security/secure-use)。
