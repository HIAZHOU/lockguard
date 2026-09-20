package com.buddy.lockguard.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buddy.lockguard.LockGuardApp
import com.buddy.lockguard.core.RideState
import com.buddy.lockguard.core.DeviceGuide
import com.buddy.lockguard.service.RideBus
import com.buddy.lockguard.service.RideMonitorService

class MainActivity : ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme())
                darkColorScheme(primary = Color(0xFF9FD5BF)) else
                lightColorScheme(primary = Color(0xFF22634E), background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7))) {
                AppRoot(permissionRevision)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
        RideMonitorService.start(this)
    }

    override fun onStart() {
        super.onStart()
        RideBus.uiVisible.value = true
    }

    override fun onStop() {
        RideBus.uiVisible.value = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) permissionRevision++
    }
}

@Composable
private fun AppRoot(permissionRevision: Int) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var requestedPermissions by rememberSaveable { mutableStateOf(false) }
    val deviceGuide = remember { DeviceGuide.forDevice(Build.BRAND, Build.MANUFACTURER) }
    val state by RideBus.state.collectAsState()
    val duration by RideBus.rideDurationMs.collectAsState()
    val level by RideBus.lastAlertLevel.collectAsState()
    val monitoring by RideBus.monitoring.collectAsState()
    val locationStatus by RideBus.locationStatus.collectAsState()
    val stepStatus by RideBus.stepStatus.collectAsState()
    val powerStatus by RideBus.powerStatus.collectAsState()
    val logs by RideBus.logLines.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshKey++
        RideMonitorService.start(context)
    }
    val permissionItems = remember(permissionRevision, refreshKey) { buildPermissionItems(context) }
    val essentialsReady = permissionItems.filter { it.required }.all { it.ok }
    if (showSupport) {
        SupportAuthorScreen(onBack = { showSupport = false })
        return
    }
    BackHandler(enabled = showMore) { showMore = false }

    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (showMore) "更多" else "锁车卫士", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showMore = !showMore }) { Text(if (showMore) "返回" else "更多") }
            }
            if (showMore) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("运行保障", style = MaterialTheme.typography.titleMedium)
                    Text(if (essentialsReady) "基础设置已就绪" else "还有 ${permissionItems.count { it.required && !it.ok }} 项基础设置待完成",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        val missing = runtimePermissions().filter { !granted(context, it) }.toTypedArray()
                        // 精确定位必须与近似定位一起请求，Android 12+ 才接受该升级请求。
                        if (missing.isNotEmpty() && !requestedPermissions) {
                            requestedPermissions = true
                            permissionLauncher.launch(runtimePermissions())
                        } else {
                            val next = permissionItems.firstOrNull { it.required && !it.ok }
                                ?: permissionItems.firstOrNull { !it.manual && !it.ok && it.title == "电池优化白名单" }
                            next?.open?.invoke(context) ?: openAppDetail(context)
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("一键检查与设置")
                    }
                    Text("本机建议 · ${deviceGuide.family}", style = MaterialTheme.typography.labelLarge)
                    Text(deviceGuide.hint + " 设置名称可能随系统版本变化。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showPermissions = !showPermissions }) { Text(if (showPermissions) "收起设置详情" else "查看设置详情") }
                    if (showPermissions) permissionItems.forEach { PermissionRow(it) }
                    HorizontalDivider()
                    Text("省电监控", style = MaterialTheme.typography.titleMedium)
                    Text(powerStatus, style = MaterialTheme.typography.bodyMedium)
                    Text(locationStatus, style = MaterialTheme.typography.bodyMedium)
                    Text(stepStatus, style = MaterialTheme.typography.bodyMedium)
                    Text("支持慢骑识别。确认锁车后进入省电守候；尚未确认时保留提醒，不会因定位跳点结束。",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "收起日志" else "运行日志") }
                        TextButton(onClick = { shareDiagnostics(context, logs, powerStatus, stepStatus) }) { Text("分享诊断") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("锁车卫士 · ${appVersion(context)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showSupport = true }) { Text("支持作者", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (showLogs) {
                        if (logs.isEmpty()) Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                        logs.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(when {
                        !monitoring -> "监控未启动"
                        state == RideState.IDLE -> "等待骑行"
                        state == RideState.RIDING -> "骑行中"
                        else -> "记得锁车"
                    }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(20.dp))
                    if (state != RideState.IDLE) {
                        Text(formatDuration(duration), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(8.dp))
                        Text("本次已开始 · 含停车等待", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(if (monitoring) locationStatus else "开启权限后即可自动识别",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (level > 0) {
                        Spacer(Modifier.height(16.dp))
                        Text("请确认车辆已锁好", color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!essentialsReady || !monitoring) {
                    TextButton(onClick = { showMore = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("需要完成设置，点击查看")
                    }
                }
                Button(onClick = {
                    if (state == RideState.IDLE && !essentialsReady) showMore = true
                    else RideMonitorService.start(context, if (state == RideState.IDLE)
                        RideMonitorService.ACTION_START_RIDE else RideMonitorService.ACTION_CONFIRM_LOCKED)
                }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (state == RideState.IDLE) "手动开始骑行" else "我已锁车")
                }
                if (state != RideState.IDLE) {
                    TextButton(onClick = { RideMonitorService.start(context, RideMonitorService.ACTION_SNOOZE) },
                        modifier = Modifier.fillMaxWidth()) { Text("稍后提醒 · 10 分钟") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(when { item.manual -> "需手动核对"; item.ok -> "已开启"; else -> "未开启" },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.ok && !item.manual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.hint, style = MaterialTheme.typography.bodySmall)
        }
        item.open?.let { action ->
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { action(context) }) { Text("设置") }
        }
    }
}

private data class PermissionItem(val title: String, val hint: String, val ok: Boolean,
    val manual: Boolean = false, val required: Boolean = false, val open: ((Context) -> Unit)? = null)

private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
}.toTypedArray()

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun buildPermissionItems(context: Context): List<PermissionItem> {
    val nm = context.getSystemService(NotificationManager::class.java)
    val lm = context.getSystemService(LocationManager::class.java)
    val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm?.isLocationEnabled == true
        else lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    val backgroundOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val activityOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACTIVITY_RECOGNITION)
    val fullScreenOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm?.canUseFullScreenIntent() == true
    val batteryOk = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    val channelsOk = listOf(LockGuardApp.CH_LIGHT, LockGuardApp.CH_HEAVY, LockGuardApp.CH_URGENT)
        .all { nm?.getNotificationChannel(it)?.importance?.let { level -> level > NotificationManager.IMPORTANCE_NONE } == true }
    return listOf(
        PermissionItem("精确定位", "识别骑行速度；近似定位可能无法自动开始", granted(context, Manifest.permission.ACCESS_FINE_LOCATION), required = true, open = ::openAppDetail),
        PermissionItem("系统定位", "同时开启手机的定位开关", locationEnabled, required = true,
            open = { safeStart(it, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }),
        PermissionItem("通知权限", "用于发送锁车确认提醒", NotificationManagerCompat.from(context).areNotificationsEnabled(), required = true, open = ::openNotifications),
        PermissionItem("提醒通知通道", "开启各级提醒的声音、振动与锁屏显示", channelsOk, required = true, open = ::openNotifications),
        PermissionItem("身体活动（可选）", "计步器辅助识别下车步行", activityOk, open = ::openAppDetail),
        PermissionItem("后台定位", "建议始终允许，便于后台恢复；前台服务运行时可使用前台定位权限", backgroundOk, open = ::openAppDetail),
        PermissionItem("电池优化白名单", "减少后台运行受限", batteryOk,
            open = { safeStart(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }),
        PermissionItem("全屏提醒（可选）", "未开启仍可发送普通通知", fullScreenOk, open = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                safeStart(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.fromParts("package", it.packageName, null)))
            else openAppDetail(it)
        }),
        PermissionItem("本机后台管理", "按上方本机建议检查；系统私有开关需要手动确认", false, manual = true, open = ::openAppDetail),
    )
}

private fun openNotifications(context: Context) = safeStart(context,
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))

private fun shareDiagnostics(context: Context, logs: List<String>, power: String, steps: String) {
    val text = buildString {
        appendLine("LockGuard v${appVersion(context)} · ${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine(power)
        appendLine(steps)
        buildPermissionItems(context).filter { !it.manual }.forEach { appendLine("${it.title}: ${if (it.ok) "已开启" else "未开启"}") }
        appendLine("--- 本次进程日志（不含坐标）---")
        logs.forEach { appendLine(it) }
    }
    safeStart(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text), "分享诊断日志"))
}

private fun openAppDetail(context: Context) = safeStart(context,
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)))

private fun safeStart(context: Context, intent: Intent) {
    try { context.startActivity(intent) }
    catch (_: Exception) { RideBus.log("无法打开该设置页，请从系统设置进入") }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun appVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
