package com.buddy.lockguard.core

/** 定位开关策略与 Android 解耦，确保待机/确认锁车不会意外留下持续 GPS。 */
class LocationDutyCycle {
    private var previousState: RideState? = null
    private var probeUntil = 0L
    private var nextMotionProbeAt = 0L
    private var nextFallbackAt = 0L

    fun motion(now: Long) {
        if (now < nextMotionProbeAt || now < probeUntil) return
        probeUntil = now + 60_000L
        nextMotionProbeAt = now + 90_000L
    }

    fun update(now: Long, state: RideState, motionWakeAvailable: Boolean): Boolean {
        if (state != previousState) {
            val initial = previousState == null
            previousState = state
            if (state == RideState.IDLE || state == RideState.PARKED || state == RideState.ALERT_ARMED) {
                probeUntil = if (initial) now + 60_000L else 0L
                nextMotionProbeAt = now + if (state == RideState.IDLE && !initial) 90_000L else 0L
                nextFallbackAt = now + 180_000L
            }
        }
        if (state == RideState.RIDING || state == RideState.PAUSED) return true
        // 有运动唤醒的静止待机不周期性开启 GPS；无该能力或仍未锁车则间歇兜底。
        if ((!motionWakeAvailable || state != RideState.IDLE) && now >= nextFallbackAt) {
            probeUntil = now + 45_000L
            nextFallbackAt = now + 180_000L
        }
        return now < probeUntil
    }

    fun nextCheckMs(now: Long, state: RideState, motionWakeAvailable: Boolean): Long? = when {
        state == RideState.RIDING || state == RideState.PAUSED -> null
        now < probeUntil -> probeUntil
        !motionWakeAvailable || state != RideState.IDLE -> nextFallbackAt
        else -> null
    }

    fun remainingProbeMs(now: Long): Long = (probeUntil - now).coerceAtLeast(1_000L)
}
