package com.buddy.lockguard.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buddy.lockguard.LockGuardApp
import com.buddy.lockguard.R
import com.buddy.lockguard.core.*
import com.buddy.lockguard.data.SettingsStore
import com.buddy.lockguard.notify.AlertDispatcher
import com.buddy.lockguard.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

object RideBus {
    val state = MutableStateFlow(RideState.IDLE)
    val rideDurationMs = MutableStateFlow(0L)
    val lastMeters = MutableStateFlow(0f)
    val lastAlertLevel = MutableStateFlow(0)
    val monitoring = MutableStateFlow(false)
    val locationStatus = MutableStateFlow("等待启动监控")
    val stepStatus = MutableStateFlow("未启用步行辅助")
    val logLines = MutableStateFlow<List<String>>(emptyList())

    fun log(line: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        logLines.value = (logLines.value + "$stamp  $line").takeLast(120)
    }
}

class RideMonitorService : Service() {
    // 回调、用户动作和定时器统一在主线程，避免状态机并发写入。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var initialization: Job
    private var machine = RideStateMachine()
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var tickerJob: Job? = null
    private var subscribed = false
    private var stepRegistered = false
    private var previousFix: LocationFix? = null
    private var lastGpsAt = 0L
    private var lastAcceptedAt = 0L
    private var lastLogAt = 0L
    private var activeWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        initialization = scope.launch {
            val config = try { SettingsStore(applicationContext).current() }
            catch (e: Exception) {
                RideBus.log("配置读取失败，使用默认参数")
                RideConfig()
            }
            machine = RideStateMachine(config)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            initialization.join()
            // 从设置返回时重新订阅，覆盖权限/定位提供者发生变化的情况。
            subscribed = false
            requestLocationUpdates()
            registerSteps()
            when (intent?.action) {
                ACTION_START_RIDE -> handleEvents(machine.startRide(now()))
                ACTION_CONFIRM_LOCKED -> handleEvents(machine.confirmLocked())
                ACTION_SNOOZE -> {
                    machine.snooze(now())
                    AlertDispatcher.cancel(this@RideMonitorService)
                    RideBus.log("已暂缓 10 分钟，到期后如未确认会再次提醒")
                }
            }
            if (tickerJob == null) {
                RideBus.log("监控启动：原生定位 + 步行辅助；停下约 2 分钟提醒")
                tickerJob = scope.launch {
                    var tick = 0
                    while (isActive) {
                        handleEvents(machine.onTick(now()))
                        publish(updateNotification = tick % 15 == 0)
                        if (tick % 30 == 0 && !subscribed) requestLocationUpdates()
                        tick++
                        delay(1_000)
                    }
                }
            }
            publish()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        locations?.removeUpdates(locationListener)
        sensors?.unregisterListener(stepListener)
        activeWakeLock?.let { if (it.isHeld) it.release() }
        RideBus.monitoring.value = false
        RideBus.locationStatus.value = "监控已停止，请打开应用恢复"
        super.onDestroy()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)
        override fun onProviderEnabled(provider: String) {
            subscribed = false
            requestLocationUpdates()
        }
        override fun onProviderDisabled(provider: String) {
            subscribed = false
            requestLocationUpdates()
        }
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (subscribed) return
        val manager = locations ?: return
        manager.removeUpdates(locationListener)
        var registered = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.allProviders.contains(provider)) continue
            try {
                // 不依赖 Google Play 服务，不批量等待；0 米位移也需要收到静止采样。
                manager.requestLocationUpdates(provider, 5_000L, 0f, locationListener, Looper.getMainLooper())
                registered = true
            } catch (e: SecurityException) {
                RideBus.log("定位权限不足，请在更多中开启精确定位")
            } catch (e: IllegalArgumentException) {
                RideBus.log("定位源不可用：$provider")
            }
        }
        subscribed = registered
        RideBus.locationStatus.value = if (registered) "等待可靠定位" else "定位不可用，请检查权限和系统定位开关"
    }

    private fun handleLocation(loc: Location) {
        val timestamp = loc.elapsedRealtimeNanos / 1_000_000L
        val current = now()
        if (timestamp <= 0 || current - timestamp !in 0..30_000L ||
            !loc.hasAccuracy() || loc.accuracy > machine.config.accuracyFilterMeters) return
        // 避免网络点覆盖刚收到的 GPS 速度。
        if (loc.provider != LocationManager.GPS_PROVIDER && current - lastGpsAt < 15_000L) return
        if (timestamp <= (previousFix?.timestampMs ?: -1L)) return
        val raw = LocationFix(timestamp, loc.latitude, loc.longitude, 0f, loc.accuracy)
        val speed = if (loc.hasSpeed()) {
            // 0 是合法静止速度，不能改成 GPS 漂移距离 / 时间。
            if (loc.hasSpeedAccuracy() && loc.speedAccuracyMetersPerSecond > 1.5f) return
            loc.speed
        } else {
            deriveSpeed(raw)
        }
        previousFix = raw
        if (speed == null || !speed.isFinite() || speed < 0f) return
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsAt = current
        lastAcceptedAt = current
        handleEvents(machine.onLocation(raw.copy(speedMps = speed)))
        if (current - lastLogAt >= 30_000L) {
            RideBus.log("${loc.provider} · ${(speed * 3.6f).roundToInt()} km/h · 精度 ±${loc.accuracy.roundToInt()} m · ${machine.state.label}")
            lastLogAt = current
        }
        publish()
    }

    private fun deriveSpeed(fix: LocationFix): Float? {
        val prev = previousFix ?: return null
        val dt = (fix.timestampMs - prev.timestampMs) / 1000f
        if (dt !in 2f..30f) return null
        val distance = Geo.distanceMeters(prev, fix)
        // 无速度且位移落在误差内，表示未知，不能虚构为静止。
        if (distance <= prev.accuracyMeters + fix.accuracyMeters) return null
        return distance / dt
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val timestamp = event.timestamp / 1_000_000L
            if (now() - timestamp !in 0..20_000L) return
            handleEvents(machine.onStep(timestamp))
            publish()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerSteps() {
        if (stepRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            RideBus.stepStatus.value = "未授权身体活动，使用定位和定时兜底"
            return
        }
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (sensor == null) {
            RideBus.stepStatus.value = "设备无计步检测器，使用定位和定时兜底"
            return
        }
        stepRegistered = try {
            sensors?.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0) == true
        } catch (_: SecurityException) { false }
        RideBus.stepStatus.value = if (stepRegistered) "步行辅助已启用" else "步行辅助不可用"
    }

    private fun handleEvents(events: List<RideEvent>) {
        for (event in events) when (event) {
            RideEvent.RideStarted -> {
                clearAlert()
                RideBus.log("进入骑行态，开始本次计时")
            }
            RideEvent.ParkedDetected -> RideBus.log("已持续减速或步行，等待锁车确认；无需走出 80 米")
            RideEvent.LockConfirmed -> {
                clearAlert()
                RideBus.log("已确认锁车，结束本次计时")
            }
            is RideEvent.RolledBack -> {
                clearAlert()
                RideBus.log(event.reason)
            }
            is RideEvent.Separated -> RideBus.log("离开停车点约 ${event.meters.roundToInt()} 米")
            is RideEvent.AlertRaised -> {
                RideBus.lastMeters.value = event.meters
                RideBus.lastAlertLevel.value = event.level
                RideBus.log("提醒 ${event.level} 级 · ${event.reason}")
                AlertDispatcher.dispatch(this, event)
            }
        }
    }

    private fun clearAlert() {
        AlertDispatcher.cancel(this)
        RideBus.lastMeters.value = 0f
        RideBus.lastAlertLevel.value = 0
    }

    private fun startAsForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_SERVICE, buildServiceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(ID_SERVICE, buildServiceNotification())
        RideBus.monitoring.value = true
        true
    } catch (e: Exception) {
        RideBus.log("前台监控未启动：${e.javaClass.simpleName}")
        RideBus.monitoring.value = false
        false
    }

    private fun buildServiceNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 3001,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val duration = machine.rideDurationMs(now()) / 1000
        val text = if (machine.state == RideState.IDLE) RideBus.locationStatus.value
            else "${machine.state.label} · 本次已开始 ${duration / 60}:%02d".format(duration % 60)
        return NotificationCompat.Builder(this, LockGuardApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_app).setContentTitle("锁车卫士")
            .setContentText(text).setOngoing(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(pi).build()
    }

    @SuppressLint("WakelockTimeout")
    private fun publish(updateNotification: Boolean = true) {
        RideBus.state.value = machine.state
        RideBus.rideDurationMs.value = machine.rideDurationMs(now())
        if (subscribed) {
            RideBus.locationStatus.value = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && locations?.isLocationEnabled == false -> "系统定位已关闭，请在更多中开启"
                lastAcceptedAt == 0L -> "等待可靠定位，室外更容易识别"
                now() - lastAcceptedAt > 30_000L -> "定位信号弱，已启用定时兜底"
                else -> "定位正常 · 自动识别中"
            }
        }
        // 仅在已识别骑行会话期间保住定时器；待机不持有 CPU 锁。
        if (machine.state != RideState.IDLE) {
            if (activeWakeLock == null) activeWakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LockGuard:activeRide")
            if (activeWakeLock?.isHeld == false) activeWakeLock?.acquire(30 * 60_000L)
        } else activeWakeLock?.let { if (it.isHeld) it.release() }
        if (updateNotification) try {
            NotificationManagerCompat.from(this).notify(ID_SERVICE, buildServiceNotification())
        } catch (_: SecurityException) { }
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val ACTION_START_RIDE = "com.buddy.lockguard.START_RIDE"
        const val ACTION_CONFIRM_LOCKED = "com.buddy.lockguard.CONFIRM_LOCKED"
        const val ACTION_SNOOZE = "com.buddy.lockguard.SNOOZE"
        const val ID_SERVICE = 1000

        fun start(context: Context, action: String? = null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                RideBus.locationStatus.value = "请先在更多中开启精确定位"
                return
            }
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, RideMonitorService::class.java).apply { this.action = action })
            } catch (e: Exception) {
                RideBus.log("服务启动被系统拦截：${e.javaClass.simpleName}")
            }
        }
    }
}
