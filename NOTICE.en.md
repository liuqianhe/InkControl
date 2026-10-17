# Sources and license boundaries

[简体中文](NOTICE.md)

Original Java, C++, Python and shell implementations and self-created theme assets are organized under the MIT license. This does not grant additional rights over:

- Hisense/Unisoc stock HWC libraries and their binary derivatives. These are user-supplied private build dependencies, outside the MIT license.
- Google G artwork in `overlay/res/drawable/googleg_light_color_24.xml`. The compatibility overlay follows the target resource shape in black; graphic and trademark rights remain with their owners. Redistribution terms require review before public release.
- Android SDK/NDK, JDK, Magisk, Xposed/LSPosed/Vector and other external tools or runtimes, which retain their own licenses.

`src/cn/lyric/getter/api/data/LyricData.java` is a Parcel protocol compatibility implementation retaining the required package name. `stubs` contains minimal compile-time declarations, not framework implementations. The Magisk installer delegates to the device's existing `util_functions.sh`.

Private keys, personal backups, firmware images, raw logs, and device data are excluded from source. Encrypted vendor dependencies are retained only for the private cloud build and are excluded from source export. The repository is initially private; public publication requires a separate request and licensing review.
