# 隐私说明 / Privacy Notice

锁车卫士（LockGuard）· 最近更新：2026-09-21

## 一句话

**这个 App 读取位置与运动数据，在本机进行提醒判断，不主动上传。**
它在 `AndroidManifest.xml` 里**没有声明 `INTERNET` 权限**，不能以自身权限直接建立互联网连接。系统定位提供者可能使用系统自身服务；你主动使用诊断分享、保存图片或打开其他应用时，适用下文说明。

---

## 一、读取哪些信息

| 信息 | 来源权限 | 用途 | 是否离开设备 |
|---|---|---|---|
| 精确/粗略位置 | `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` | 推算速度、判断停车点与是否走开 | **否** |
| 后台位置 | `ACCESS_BACKGROUND_LOCATION` | 离开 App 界面后仍需判断 | **否** |
| 身体活动（步数类信号） | `ACTIVITY_RECOGNITION` | 区分步行与骑行，降低误报 | **否** |
| 设备型号与系统版本 | 系统 API，无需权限 | 生成诊断文本时标注机型 | **否** |

App 不读取、也无法读取：通讯录、短信、相册、通话记录、其他应用的内部数据、剪贴板。

## 二、数据存在哪里

- **位置与运动数据只在内存中参与实时计算**，用于状态机判定，不写入文件。
- 只有**阈值设置**（如提醒距离、暂停时长）通过 Android DataStore 保存在本机私有目录。
- 诊断日志（「更多 → 分享诊断」）由你主动触发导出，**不含经纬度坐标**，包含权限状态、机型、时间、速度、定位精度、离开停车点的相对距离和识别原因。日志在进程内最多保留 250 条，并写入 Android 系统日志缓冲区；你可以自行决定是否分享。
- 应用声明 `android:allowBackup="false"` 禁用系统云备份。厂商的设备迁移行为可能不同，不能据此承诺所有迁移工具都无法复制应用数据。
- 「支持作者 → 保存收款码」会按你的操作把二维码图片写入相册（Android 10 及以上）或你选择的文件位置。该图片不含骑行坐标；相册或所选文件服务可能按其自身设置同步图片。

## 三、会不会上传、会不会分享给第三方

- **不会上传。** App 未申请网络权限，代码中没有网络请求逻辑，也没有集成任何统计、广告、崩溃上报或支付 SDK。
- **不会自动向第三方发送数据。** 「分享诊断」会打开系统分享面板；只有你选择接收应用并完成发送，诊断文本才会交给该应用，之后按其规则处理。发送前请检查内容。
- **不会读取共享单车平台的订单或车锁状态。** 本 App 与美团单车、哈啰、青桔等平台没有任何接口对接，也不使用其数据。

`AndroidManifest.xml` 里的 `<queries>` 声明仅用于「支持作者」页面判断微信/支付宝是否已安装，用途是跳转打开对应 App，不读取其内部数据。

## 四、你可能授予的全部权限及原因

| 权限 | 为什么需要 | 不给会怎样 |
|---|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 判断骑行与步行 | 无法可靠自动识别；近似定位不能替代精确定位 |
| `ACCESS_BACKGROUND_LOCATION` | 便于后台恢复监控 | 已运行的定位前台服务仍可能工作，但后台重新启动可能受限 |
| `ACTIVITY_RECOGNITION` | 提高骑行/步行区分准确度 | 仍可用，但误报可能增加 |
| `POST_NOTIFICATIONS` | 发出提醒 | 收不到任何提醒 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 保持监控常驻 | 后台被系统挂起 |
| `RECEIVE_BOOT_COMPLETED` | 重启后恢复监控 | 重启后需手动打开 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 避免被省电策略掐断 | 厂商系统会限制后台定位 |
| `USE_FULL_SCREEN_INTENT` | 锁屏时弹出强提醒 | 降级为普通通知 |
| `WAKE_LOCK` | 判定期间保持 CPU 唤醒 | 判定延迟 |
| `VIBRATE` | 振动提醒 | 无振动反馈 |

**没有 `INTERNET`、没有 `READ_CONTACTS`、没有 `READ_SMS`、没有 `QUERY_ALL_PACKAGES`。**

你可以在手机「设置 → 应用 → 锁车卫士 → 权限」逐条核对。

## 五、关于位置信息（敏感个人信息）的告知

精确位置与行踪轨迹涉及敏感个人信息。这里说明处理目的和方式；**当前版本尚未提供应用内的单独告知同意界面，系统权限授权及本说明不能代替该流程，公开分发前仍需补齐。**

- 处理目的：仅用于在本地判断「骑行—停车—走开」，触发锁车确认提醒。
- 处理方式：设备本机实时计算，不存储、不传输。
- 必要性：这是本 App 唯一的核心功能，不处理位置就无法工作。
- 拒绝后果：你可以不授予位置权限，App 将无法可靠自动识别骑行与停车（可随时卸载）。

**你不授予权限即可不使用；授予后可随时在系统设置中撤回。卸载会删除应用私有数据；你已保存的二维码图片或主动分享的诊断文本需在相应位置另行删除。**

## 六、未成年人

本 App 不面向未成年人设计。App 内不收集年龄信息，也无账号体系。
「支持作者」为完全自愿行为且不影响任何功能；未成年人请勿使用该入口。

## 七、第三方组件

当前构建直接使用 AndroidX（含 Jetpack Compose、DataStore）与 Kotlin 协程；定位调用 Android 系统 LocationManager，未声明 Google Play Services Location 依赖。代码未集成统计、广告、崩溃上报或支付 SDK。二维码工具 ZXing 仅用于维护者**本机构建时**处理二维码，不作为 App 运行依赖。系统定位服务和你主动打开的支付应用按各自的规则运行。

## 八、联系方式

如对本说明有疑问，或发现与上述描述不符的行为，请在项目仓库提交 Issue。

---

## English Summary

LockGuard reads your location and motion activity **only on-device** to detect when you have parked a shared bike and walked away, so it can remind you to check the lock.

- The app **does not declare the `INTERNET` permission** and makes no direct internet requests. System location providers and external apps operate under their own rules.
- No analytics, ads, crash reporting, or payment SDKs are included.
- Coordinates are used in memory for real-time computation. Diagnostics contain timing, speed, accuracy and relative distance, but no latitude/longitude.
- Only your threshold settings are stored locally via DataStore. `android:allowBackup="false"`.
- Diagnostics logs are also written to Android's log buffer. You can explicitly share diagnostics with another app. Saved QR images and shared text may persist outside the app after uninstalling it.
- The current app still needs an in-app separate disclosure and consent flow before public distribution; this document does not replace that flow.
- The app has **no integration** with Meituan Bike, Hellobike, or Qingju.

Full permission list and rationale are in section 四 above.
