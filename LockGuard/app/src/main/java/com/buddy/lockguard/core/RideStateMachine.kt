package com.buddy.lockguard.core

/** 单线程调用；所有时间使用单调时钟。提醒是锁车确认请求，不代表检测到了车锁。 */
class RideStateMachine(val config: RideConfig = RideConfig()) {
    private val velocity = MedianVelocity(config.velocityWindow)
    var state = RideState.IDLE
        private set
    var rideStartedAtMs = 0L
        private set
    var parkedAtMs = 0L
        private set
    var thresholdMeters = config.separationMeters
        private set

    private var candidateSince: Long? = null
    private var fastCandidateSince: Long? = null
    private var resumeSince: Long? = null
    private var lastFixAt: Long? = null
    private var lastRideEvidenceAt: Long? = null
    private var stopSince: Long? = null
    private var parkedAnchor: LocationFix? = null
    private var alertSince: Long? = null
    private var alertReason = AlertReason.STOP_TIMEOUT
    private var lastDistance = 0f
    private var lastAlertLevel = 0
    private var lastAlertAt: Long? = null
    private var snoozeUntil = 0L
    private val steps = ArrayDeque<Long>()

    fun rideDurationMs(nowMs: Long): Long =
        if (state == RideState.IDLE) 0L else (nowMs - rideStartedAtMs).coerceAtLeast(0L)

    fun startRide(nowMs: Long): List<RideEvent> {
        if (state != RideState.IDLE) return emptyList()
        resetDetection()
        rideStartedAtMs = nowMs
        state = RideState.RIDING
        return listOf(RideEvent.RideStarted)
    }

    fun confirmLocked(): List<RideEvent> {
        if (state == RideState.IDLE) return emptyList()
        state = RideState.IDLE
        resetDetection()
        return listOf(RideEvent.LockConfirmed)
    }

    fun snooze(nowMs: Long) {
        if (state == RideState.IDLE) return
        snoozeUntil = nowMs + config.snoozeMs
        thresholdMeters = config.separationMeters * 3f
        // 到期后仍需确认，即使之前已经发过三级提醒。
        lastAlertLevel = 0
    }

    fun onLocation(fix: LocationFix): List<RideEvent> {
        if (!fix.speedMps.isFinite() || fix.speedMps !in 0f..config.rideSpeedMaxMps ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..config.accuracyFilterMeters ||
            !fix.lat.isFinite() || fix.lat !in -90.0..90.0 ||
            !fix.lon.isFinite() || fix.lon !in -180.0..180.0 ||
            (lastFixAt != null && fix.timestampMs <= lastFixAt!!)) return emptyList()

        val now = fix.timestampMs
        if (lastFixAt != null && now - lastFixAt!! > config.rideCandidateGapMs) {
            candidateSince = null
            fastCandidateSince = null
            resumeSince = null
            velocity.clear()
        }
        lastFixAt = now
        val v = velocity.push(fix.speedMps)
        val out = mutableListOf<RideEvent>()
        if (state == RideState.IDLE) {
            val walking = hasWalkingCadence(now)
            if (v >= config.slowRideMinMps && fix.speedMps >= config.slowRideMinMps &&
                fix.accuracyMeters <= 25f && (!walking || v >= 2.8f)) {
                val since = candidateSince ?: now.also { candidateSince = it }
                if (v >= config.rideSpeedMinMps && fix.speedMps >= config.rideSpeedMinMps) {
                    if (fastCandidateSince == null) fastCandidateSince = now
                } else fastCandidateSince = null
                if (now - since >= config.slowRideConfirmMs ||
                    fastCandidateSince?.let { now - it >= config.rideConfirmMs } == true) {
                    out += startRide(since)
                    lastFixAt = now
                    lastRideEvidenceAt = now
                }
            } else { candidateSince = null; fastCandidateSince = null }
            return out
        }

        when {
            v >= config.slowRideMinMps && (!hasWalkingCadence(now) || v >= 2.8f) -> {
                lastRideEvidenceAt = now
                val since = resumeSince ?: now.also { resumeSince = it }
                val required = if (v >= config.rideSpeedMinMps) config.rideConfirmMs else config.slowRideConfirmMs
                if (state == RideState.RIDING && lastAlertLevel > 0 && alertReason == AlertReason.SIGNAL_LOST ||
                    state != RideState.RIDING && now - since >= required) {
                    resetStop()
                    state = RideState.RIDING
                    out += RideEvent.RolledBack(RideState.RIDING, "恢复骑行，撤销停车提醒")
                }
            }
            else -> {
                resumeSince = null
                if (v <= config.stopSpeedMaxMps || hasWalkingCadence(now)) beginStop(now, fix)
                parkedAnchor?.let { anchor ->
                    lastDistance = Geo.distanceMeters(anchor, fix)
                    // 距离只作提前提醒的辅助证据，扣除两个点的不确定度。
                    val reliableDistance = lastDistance - anchor.accuracyMeters - fix.accuracyMeters
                    if (state == RideState.PARKED && reliableDistance >= thresholdMeters) {
                        arm(now, AlertReason.DISTANCE)
                        out += RideEvent.Separated(now, lastDistance)
                    }
                }
            }
        }
        out += onTick(now)
        return out
    }

    /** 原生计步器辅助识别下车步行，不把摇晃直接当成骑行。 */
    fun onStep(nowMs: Long): List<RideEvent> {
        if (steps.lastOrNull()?.let { nowMs <= it } == true) return emptyList()
        steps.addLast(nowMs)
        while (steps.isNotEmpty() && nowMs - steps.first() > 20_000L) steps.removeFirst()
        if (state == RideState.IDLE) return emptyList()
        val recentRide = lastRideEvidenceAt?.let { nowMs - it < 15_000L } ?: false
        if (steps.size >= 6 && !recentRide) beginStop(steps.first(), null)
        return onTick(nowMs)
    }

    private fun hasWalkingCadence(now: Long) = steps.count { now - it in 0..8_000L } >= 4

    /** 供系统非精确唤醒闹钟兜底；普通进程存活时仍用单调时钟实时推进。 */
    fun nextDeadlineMs(now: Long): Long? {
        val due = when (state) {
            RideState.IDLE -> null
            RideState.RIDING -> if (lastAlertLevel == 0) (lastFixAt ?: rideStartedAtMs) + config.signalLostReminderMs else null
            RideState.PAUSED -> stopSince?.plus(config.stillConfirmMs)
            RideState.PARKED -> stopSince?.plus(config.stopReminderMs)
            RideState.ALERT_ARMED -> when (lastAlertLevel) {
                0 -> alertSince
                1 -> alertSince?.plus(config.level2DurationMs)
                2 -> alertSince?.plus(config.escalationDurationMs)
                else -> null
            }
        } ?: return null
        return maxOf(due, snoozeUntil, lastAlertAt?.plus(config.alertCooldownMs) ?: 0L, now + 1_000L)
    }

    /** 由服务定时驱动，不依赖下一次 GPS 回调。 */
    fun onTick(nowMs: Long): List<RideEvent> {
        if (state == RideState.IDLE) return emptyList()
        val out = mutableListOf<RideEvent>()
        val stoppedAt = stopSince
        if (state == RideState.PAUSED && stoppedAt != null &&
            nowMs - stoppedAt >= config.stillConfirmMs) {
            parkedAtMs = stoppedAt + config.stillConfirmMs
            state = RideState.PARKED
            out += RideEvent.ParkedDetected
        }
        if (state == RideState.PARKED && stoppedAt != null &&
            nowMs - stoppedAt >= config.stopReminderMs) {
            arm(stoppedAt + config.stopReminderMs, AlertReason.STOP_TIMEOUT)
        }
        if (state == RideState.RIDING &&
            nowMs - (lastFixAt ?: rideStartedAtMs) >= config.signalLostReminderMs) {
            // 保持骑行状态：失去定位不等于停车，只发一次核实提示。
            if (lastAlertLevel == 0) {
                alertReason = AlertReason.SIGNAL_LOST
                alertSince = nowMs
                raise(nowMs, 1, out)
            }
        }
        if (state == RideState.ALERT_ARMED) {
            val elapsed = nowMs - (alertSince ?: nowMs)
            val level = when {
                elapsed >= config.escalationDurationMs -> 3
                elapsed >= config.level2DurationMs ||
                    (alertReason == AlertReason.DISTANCE && lastDistance >= config.level2Meters) -> 2
                else -> 1
            }
            raise(nowMs, level, out)
        }
        return out
    }

    private fun beginStop(now: Long, fix: LocationFix?) {
        if (state != RideState.RIDING) return
        resetStop()
        stopSince = now
        parkedAnchor = fix
        state = RideState.PAUSED
    }

    private fun arm(now: Long, reason: AlertReason) {
        state = RideState.ALERT_ARMED
        alertSince = now
        alertReason = reason
    }

    private fun raise(now: Long, level: Int, out: MutableList<RideEvent>) {
        if (level <= lastAlertLevel || now < snoozeUntil ||
            lastAlertAt?.let { now - it < config.alertCooldownMs } == true) return
        lastAlertAt = now
        lastAlertLevel = level
        val duration = rideDurationMs(now)
        out += RideEvent.AlertRaised(now, level, lastDistance,
            (now - (stopSince ?: alertSince ?: now)).coerceAtLeast(0), duration,
            (config.freeMinutes - (duration / 60_000).toInt()).coerceAtLeast(0), alertReason)
    }

    private fun resetStop() {
        stopSince = null
        parkedAnchor = null
        parkedAtMs = 0L
        alertSince = null
        lastDistance = 0f
        lastAlertAt = null
        lastAlertLevel = 0
        steps.clear()
        resumeSince = null
    }

    private fun resetDetection() {
        resetStop()
        candidateSince = null
        fastCandidateSince = null
        lastFixAt = null
        lastRideEvidenceAt = null
        snoozeUntil = 0L
        thresholdMeters = config.separationMeters
        velocity.clear()
    }
}

internal class MedianVelocity(private val window: Int) {
    private val buffer = ArrayDeque<Float>()
    fun push(value: Float): Float {
        buffer.addLast(value)
        while (buffer.size > window.coerceAtLeast(1)) buffer.removeFirst()
        return buffer.sorted()[buffer.size / 2]
    }
    fun clear() = buffer.clear()
}
