# GitHub cloud builds

[简体中文](cloud-build.md)

The repository starts private. All Java, native, resource, Dex, Overlay, Magisk ZIP, and APK compilation runs on GitHub-hosted Ubuntu 24.04 through `.github/workflows/build.yml`. Low-level build tools remain for reproducibility but are no longer run locally.

Pushes to main produce unsigned candidates. In Actions → Cloud build → Run workflow, choose unsigned or signed. Pull requests run translation, Java/backend regression, native policy/queue, and software GLES tests without private firmware or signing secrets. The workflow does not use pull_request_target.

Toolchain: Python 3.12, Temurin JDK 21 targeting Java 8, Platform 33, Build Tools 35.0.0, NDK 29.0.14206865, Android API 29 arm64. Actions are pinned to commit SHAs, contents permission is read-only, and checkout does not persist credentials.

## Private dependencies

`ci/private-inputs.zip.enc` contains exactly two matching HWC dependencies, encrypted with OpenSSL AES-256-CBC and PBKDF2 at 200000 iterations. `ci/inputs.json` records encrypted and plaintext hashes; the build tool checks its own pinned input hashes as well. The passphrase exists only as the `INKCONTROL_INPUT_PASSWORD` Actions secret. Decryption uses a temporary runner directory, validates member names, and removes temporary inputs on exit. Neither plaintext input archives nor complete temporary directories are uploaded.

Vendor binaries are outside the MIT license. Built APK/ZIP artifacts necessarily contain their runtime derivatives and remain protected by private repository access. Source archives exclude the encrypted dependencies. Review redistribution rights separately before changing visibility.

## Signing

`ci/trusted-certificate.der` is the current installation's public certificate, not a private key. Unsigned candidates have `-unsigned` names; both the app and Overlay are unsigned and cannot be installed.

Signed builds require `INKCONTROL_KEYSTORE_BASE64`, `INKCONTROL_STORE_PASSWORD`, `INKCONTROL_KEY_PASSWORD`, and `INKCONTROL_KEY_ALIAS` Actions secrets. Configure these using GitHub settings or standard input, never source files, command arguments, or logs. The original passwords have not been located, so this migration does not generate a replacement key or claim a signed build.

The Overlay is signed first, then the backend ZIP is hashed and embedded into the app, which is signed last. CI rejects a certificate different from the trusted certificate to preserve update compatibility. Signing only the outer APK is insufficient.

## Evidence and artifacts

Each run has a unique directory. Artifacts retained for seven days contain test evidence, APK, backend ZIP, SHA256SUMS, a source manifest, and package verification. Commit SHA, run ID, and attempt are recorded. CI does not publish public releases, install on devices, or modify device settings.

Software GLES and regression success do not prove Mali execution, HWC integration, panel quality, or long-term stability. The device still runs rc10 with the GPU fix. rc12 includes rc11 algorithm selection plus this bilingual/cloud migration and needs separate device acceptance after original signing is available.

References: [GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [Actions security](https://docs.github.com/en/actions/reference/security/secure-use).
