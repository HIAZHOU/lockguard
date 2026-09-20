package com.buddy.lockguard.core

/**
 * 一次定位采样。
 *
 * timestampMs 使用单调时钟。[speedMps] 为 0 表示真实的静止速度。
 * 无速度时，服务层仅在位移超过定位误差后推导；未知速度不传入状态机。
 */
data class LocationFix(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val accuracyMeters: Float,
)

enum class RideState {
    IDLE,
    RIDING,
    PAUSED,
    PARKED,
    ALERT_ARMED;

    val label: String
        get() = when (this) {
            IDLE -> "待机"
            RIDING -> "骑行中"
            PAUSED -> "已停下"
            PARKED -> "等待锁车确认"
            ALERT_ARMED -> "请确认锁车"
        }
}

/**
 * 校园场景启发式参数，需要真机路测校准。
 * 所有时间单位均为毫秒，速度单位 m/s。
 */
data class RideConfig(
    /** 约 8 km/h，需持续确认，避免单点速度触发。 */
    val rideSpeedMinMps: Float = 2.2f,
    val rideConfirmMs: Long = 15_000L,
    val rideCandidateGapMs: Long = 30_000L,
    /** 从开始减速/步行计时，不要求离开停车点。 */
    val stopReminderMs: Long = 120_000L,
    /** 已有骑行会话但失去可靠定位时，只提示核实，不宣称已停车。 */
    val signalLostReminderMs: Long = 180_000L,
    /** 判定「这不是自行车」的速度上限：25 km/h，超过则回 IDLE，防止开车通勤误判 */
    val rideSpeedMaxMps: Float = 7.0f,
    /** 减速/步行多久进入等待确认状态 */
    val stillConfirmMs: Long = 30_000L,
    /** 定位精度差于此值的采样点直接丢弃，防高楼/楼道漂移 */
    val accuracyFilterMeters: Float = 50f,
    /** 距离仅用于提前提醒，不再是触发提醒的必要条件。 */
    val separationMeters: Float = 80f,
    /** 二级提醒距离 */
    val level2Meters: Float = 300f,
    /** 二级提醒时长：分离满 3 分钟 */
    val level2DurationMs: Long = 180_000L,
    /** 三级升级时长：分离满 15 分钟（超过典型骑行时长） */
    val escalationDurationMs: Long = 900_000L,
    /** 同级提醒冷却 */
    val alertCooldownMs: Long = 60_000L,
    /** 用户点「暂缓」后的静默时长，同时把阈值放宽 3 倍 */
    val snoozeMs: Long = 600_000L,
    /** 速度中位数平滑窗口。中位数而非均值：抗单点突刺 */
    val velocityWindow: Int = 3,
    /** 骑行卡免费时长：你的卡是 60 分钟 */
    val freeMinutes: Int = 60,
    /** 校园场景典型骑行时长 */
    val typicalRideMinutes: Int = 15,
)

sealed interface RideEvent {
    /** 进入骑行态（手动点开始，或自动识别到骑行速度） */
    data object RideStarted : RideEvent

    /** 已判定停车，锁定停车点 */
    data object ParkedDetected : RideEvent

    /** 状态回滚（等红灯、重新骑走、人走回来等） */
    data class RolledBack(val from: RideState, val reason: String) : RideEvent

    /** 人车分离，首次越过距离阈值 */
    data class Separated(val atMs: Long, val meters: Float) : RideEvent

    /**
     * 触发分级提醒。
     * [minutesUntilCharge] 把「1 小时免费卡」变成紧迫感来源：
     * 距开始计费还有多少分钟。
     */
    data class AlertRaised(
        val atMs: Long,
        val level: Int,
        val meters: Float,
        val separatedMs: Long,
        val rideMs: Long,
        val minutesUntilCharge: Int,
        val reason: AlertReason = AlertReason.DISTANCE,
    ) : RideEvent

    /** 用户确认已锁车 */
    data object LockConfirmed : RideEvent
}

enum class AlertReason { DISTANCE, STOP_TIMEOUT, SIGNAL_LOST }
