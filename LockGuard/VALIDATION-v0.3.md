# v0.3 构建验证记录

- 日期：2026-09-20
- 包名：com.buddy.lockguard
- 版本：0.3 / versionCode 3
- minSdk 26，targetSdk 35
- Kotlin JUnit：44 项，0 失败（状态机 30、速度过滤 6、省电策略 5、品牌映射 3）
- Android Lint：0 错误、3 条原有低版本兼容性警告
- PowerShell 诊断脚本：语法解析通过
- 构建副本与当前 app/src 的 22 个文件逐一散列一致
- APK 签名与 v0.2 一致，可覆盖安装
- APK SHA-256：4403A9C8B91AF4F8A642B97FAD9F5ACA5E86B7F3E9C72F3DC9240CEFD7916FC2

验证命令为 tools/build_apk.ps1，执行 testDebugUnitTest、assembleDebug、lintDebug；另用 apksigner、aapt 核对签名、版本与 SDK 配置。

当前未连接手机，未验证真实识别率、GPS 关闭后的硬件统计、息屏送达延迟、厂商后台限制、跨品牌 UI 或实际耗电降幅。
