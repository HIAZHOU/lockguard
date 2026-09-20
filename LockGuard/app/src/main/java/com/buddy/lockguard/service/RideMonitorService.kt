package com.buddy.lockguard.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.*
import android.os.*
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
import java.util.Locale

object RideBus {
    val state = MutableStateFlow(RideState.IDLE)
    val rideDurationMs = MutableStateFlow(0L)
    val lastMeters = MutableStateFlow(0f)
    val lastAlertLevel = MutableStateFlow(0)
    val monitoring = MutableStateFlow(false)
    val locationStatus = MutableStateFlow("等待启动监控")
    val stepStatus = MutableStateFlow("未启用步行辅助")
    val powerStatus = MutableStateFlow("等待省电监控")
    val uiVisible = MutableStateFlow(false)
    val logLines = MutableStateFlow<List<String>>(emptyList())

    fun log(line: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        logLines.value = (logLines.value + "$stamp  $line").takeLast(250)
        android.util.Log.i("LockGuard", line)
    }
}

class RideMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var initialization: Job
    private var ready = false
    private var machine = RideStateMachine()
    private val dutyCycle = LocationDutyCycle()
    private val speedResolver = SpeedResolver()
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private var tickerJob: Job? = null
    private var gpsOn: Boolean? = null
    private var gpsSubscriptionUntil = 0L
    private var stepRegistered = false
    private var motionRegistered = false
    private var lastAcceptedAt = 0L
    private var lastRawAt = 0L
    private var lastLogAt = 0L
    private var lastRejectLogAt = 0L
    private var lastMotionRegistrationAt = 0L
    private var scheduledAlarmAt: Long? = null
    private var activeWakeLock: PowerManager.WakeLock? = null
    private var wakeLeaseUntil = 0L
    private var gpsStartedAt: Long? = null
    private var gpsTotalMs = 0L
    private val timerIntent by lazy {
        PendingIntent.getBroadcast(this, 9002, Intent(this, MonitorTimerReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        initialization = scope.launch {
            val config = try { SettingsStore(applicationContext).current() }
                catch (_: Exception) { RideBus.log("配置读取失败，使用默认参数"); RideConfig() }
            machine = RideStateMachine(config)
            ready = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAsForeground()) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            initialization.join()
            registerSteps()
            registerMotion()
            when (intent?.action) {
                ACTION_START_RIDE -> handleEvents(machine.startRide(now()))
                ACTION_CONFIRM_LOCKED -> handleEvents(machine.confirmLocked())
                ACTION_SNOOZE -> {
                    machine.snooze(now())
                    AlertDispatcher.cancel(this@RideMonitorService)
                    RideBus.log("已暂缓 10 分钟，未确认会话继续保留")
                }
            }
            if (tickerJob == null) {
                RideBus.log("监控 v0.3：双档骑行识别；待机运动唤醒；网络点不用于速度")
                tickerJob = scope.launch {
                    while (isActive) {
                        tick()
                        delay(if (RideBus.uiVisible.value) 1_000L else 5_000L)
                    }
                }
            }
            applyPowerPolicy(force = true)
            publish()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        locations?.removeUpdates(locationListener)
        sensors?.unregisterListener(stepListener)
        sensors?.cancelTriggerSensor(motionListener, significantMotion())
        alarms?.cancel(timerIntent)
        activeWakeLock?.let { if (it.isHeld) it.release() }
        if (current === this) current = null
        RideBus.monitoring.value = false
        RideBus.locationStatus.value = "监控已停止，请打开应用恢复"
        super.onDestroy()
    }

    private fun tick() {
        if (!ready) return
        handleEvents(machine.onTick(now()))
        applyPowerPolicy()
        publish(updateNotification = false)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)
        override fun onProviderEnabled(provider: String) { applyPowerPolicy(force = true) }
        override fun onProviderDisabled(provider: String) { applyPowerPolicy(force = true) }
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private fun significantMotion() = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // TriggerEventListener 的线程由平台决定，统一回主线程再修改状态。
            scope.launch {
                motionRegistered = false
                dutyCycle.motion(now())
                applyPowerPolicy()
                publish()
            }
        }
    }

    private fun registerMotion() {
        if (motionRegistered || now() - lastMotionRegistrationAt < 5_000L) return
        lastMotionRegistrationAt = now()
        significantMotion()?.let { sensor ->
            motionRegistered = try { sensors?.requestTriggerSensor(motionListener, sensor) == true }
                catch (_: RuntimeException) { false }
        }
    }

    private fun applyPowerPolicy(force: Boolean = false) {
        if (!ready) return
        registerMotion()
        val currentTime = now()
        val desired = dutyCycle.update(currentTime, powerState(), motionRegistered)
        if (force || gpsOn != desired || desired && currentTime >= gpsSubscriptionUntil) {
            configureLocations(desired)
        }
        val total = gpsTotalMs + (gpsStartedAt?.let { currentTime - it } ?: 0L)
        RideBus.powerStatus.value = when {
            gpsOn == true && (machine.state == RideState.RIDING || machine.state == RideState.PAUSED) -> "骑行定位中"
            gpsOn == true -> "短时检测中，随后自动休眠"
            motionRegistered -> "省电守候 · 运动时检测"
            else -> "省电守候 · 间歇检测"
        } + " · 本次 GPS 请求 ${total / 60_000} 分钟"
        updateWakeLock(currentTime)
        val reminderAt = machine.nextDeadlineMs(currentTime)
        val powerAt = dutyCycle.nextCheckMs(currentTime, powerState(), motionRegistered)
        val due = listOfNotNull(reminderAt, powerAt).minOrNull()
        if (due != scheduledAlarmAt) {
            scheduledAlarmAt = due
            if (due == null) alarms?.cancel(timerIntent)
            else alarms?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, timerIntent)
        }
    }

    private fun powerState() = if (machine.state == RideState.RIDING && RideBus.lastAlertLevel.value > 0)
        RideState.ALERT_ARMED else machine.state

    @SuppressLint("MissingPermission")
    private fun configureLocations(enableGps: Boolean) {
        val manager = locations ?: return
        manager.removeUpdates(locationListener)
        val time = now()
        if (gpsOn == true) {
            gpsTotalMs += time - (gpsStartedAt ?: time)
            gpsStartedAt = null
        }
        gpsOn = false
        speedResolver.reset()
        if (manager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) try {
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5_000L, 0f, locationListener, Looper.getMainLooper())
        } catch (_: SecurityException) { }
        if (enableGps && manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            val active = powerState() == RideState.RIDING || powerState() == RideState.PAUSED
            val duration = if (active) Long.MAX_VALUE else dutyCycle.remainingProbeMs(time)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val request = LocationRequest.Builder(3_000L).setMinUpdateIntervalMillis(2_000L)
                        .setDurationMillis(duration).build()
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, mainExecutor, locationListener)
                } else {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 0f, locationListener, Looper.getMainLooper())
                }
                gpsOn = true
                gpsStartedAt = time
                gpsSubscriptionUntil = if (active) Long.MAX_VALUE else time + duration
            } catch (_: SecurityException) { RideBus.log("定位权限不足，请完成运行设置") }
        }
        RideBus.log(if (gpsOn == true) "GPS 开启：${if (machine.state == RideState.RIDING) "骑行" else "限时检测"}"
            else "GPS 关闭：保留运动唤醒与提醒会话")
    }

    private fun handleLocation(loc: Location) {
        if (!ready) return
        val timestamp = loc.elapsedRealtimeNanos / 1_000_000L
        val time = now()
        if (timestamp <= 0 || time - timestamp !in 0..30_000L || timestamp <= lastRawAt) return
        lastRawAt = timestamp
        val fix = LocationFix(timestamp, loc.latitude, loc.longitude, 0f, if (loc.hasAccuracy()) loc.accuracy else 999f)
        val reading = speedResolver.resolve(fix, loc.provider,
            if (loc.hasSpeed()) loc.speed else null,
            if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null)
        if (reading.speedMps == null) {
            if (time - lastRejectLogAt >= 15_000L) {
                RideBus.log("忽略 ${loc.provider}：${reading.reason}")
                lastRejectLogAt = time
            }
            return
        }
        lastAcceptedAt = time
        handleEvents(machine.onLocation(fix.copy(speedMps = reading.speedMps)))
        if (time - lastLogAt >= 10_000L) {
            RideBus.log(String.format(Locale.ROOT, "gps · %.1f km/h · 精度 ±%.0f m · %s", reading.speedMps * 3.6f, loc.accuracy, machine.state.label))
            lastLogAt = time
        }
        applyPowerPolicy()
        publish()
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!ready) return
            val timestamp = event.timestamp / 1_000_000L
            if (now() - timestamp !in 0..20_000L) return
            // IDLE 时也记录步频，以便慢骑通道排除普通步行。
            handleEvents(machine.onStep(timestamp))
            if (machine.state == RideState.IDLE) dutyCycle.motion(now())
            applyPowerPolicy()
            publish(updateNotification = false)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerSteps() {
        if (stepRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            RideBus.stepStatus.value = "未授权身体活动，慢骑误判风险较高"
            return
        }
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        stepRegistered = try { sensor != null && sensors?.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0) == true }
            catch (_: SecurityException) { false }
        RideBus.stepStatus.value = if (stepRegistered) "步频辅助已启用" else "无计步辅助，使用持续速度确认"
    }

    private fun handleEvents(events: List<RideEvent>) {
        for (event in events) when (event) {
            RideEvent.RideStarted -> { clearAlert(); RideBus.log("进入骑行态，开始本次计时") }
            RideEvent.ParkedDetected -> RideBus.log("等待锁车确认，停止持续 GPS")
            RideEvent.LockConfirmed -> { clearAlert(); RideBus.log("已确认锁车，返回省电守候") }
            is RideEvent.RolledBack -> { clearAlert(); RideBus.log(event.reason) }
            is RideEvent.Separated -> RideBus.log("离开停车点约 ${event.meters.toInt()} 米")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ID_SERVICE, buildServiceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID_SERVICE, buildServiceNotification())
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
        return NotificationCompat.Builder(this, LockGuardApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_app).setContentTitle("锁车卫士")
            .setContentText(if (machine.state == RideState.IDLE) RideBus.powerStatus.value else machine.state.label)
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(pi).build()
    }

    private fun updateWakeLock(time: Long) {
        // 首次停车提醒前以及限时检测时保证计时；提醒发出后释放，不无限期保持唤醒。
        val needed = gpsOn == true || machine.state in listOf(RideState.PAUSED, RideState.PARKED) && RideBus.lastAlertLevel.value == 0
        if (needed) {
            if (activeWakeLock == null) activeWakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LockGuard:detectWindow")
            if (activeWakeLock?.isHeld != true || time + 15_000L >= wakeLeaseUntil) {
                activeWakeLock?.let { if (it.isHeld) it.release(); it.acquire(90_000L) }
                wakeLeaseUntil = time + 90_000L
            }
        } else activeWakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun publish(updateNotification: Boolean = true) {
        RideBus.state.value = machine.state
        RideBus.rideDurationMs.value = machine.rideDurationMs(now())
        RideBus.locationStatus.value = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && locations?.isLocationEnabled == false -> "系统定位已关闭"
            gpsOn != true -> "定位休眠，运动时自动检测"
            lastAcceptedAt == 0L || now() - lastAcceptedAt > 30_000L -> "等待可靠定位，楼旁可能较慢"
            else -> "定位正常 · 自动识别中"
        }
        if (updateNotification) try {
            NotificationManagerCompat.from(this).notify(ID_SERVICE, buildServiceNotification())
        } catch (_: SecurityException) { }
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        private var current: RideMonitorService? = null
        const val ACTION_START_RIDE = "com.buddy.lockguard.START_RIDE"
        const val ACTION_CONFIRM_LOCKED = "com.buddy.lockguard.CONFIRM_LOCKED"
        const val ACTION_SNOOZE = "com.buddy.lockguard.SNOOZE"
        const val ID_SERVICE = 1000

        fun onTimer() { current?.let { it.scheduledAlarmAt = null; it.tick() } }

        fun start(context: Context, action: String? = null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                RideBus.locationStatus.value = "请先完成运行设置"
                return
            }
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, RideMonitorService::class.java).apply { this.action = action })
            } catch (e: Exception) { RideBus.log("服务启动被系统拦截：${e.javaClass.simpleName}") }
        }
    }
}

class MonitorTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RideMonitorService.onTimer()
}
