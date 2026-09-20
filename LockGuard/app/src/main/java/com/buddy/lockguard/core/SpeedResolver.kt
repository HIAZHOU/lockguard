package com.buddy.lockguard.core

data class SpeedReading(val speedMps: Float?, val reason: String)

/** 网络位置只能辅助唤醒，不能与 GPS 两点混算速度或直接清理会话。 */
class SpeedResolver {
    private var previousGps: LocationFix? = null

    fun resolve(fix: LocationFix, provider: String?, reportedSpeed: Float?, speedAccuracy: Float?): SpeedReading {
        if (provider != "gps") return SpeedReading(null, "网络定位不参与速度判定")
        if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f ||
            !fix.lat.isFinite() || fix.lat !in -90.0..90.0 || !fix.lon.isFinite() || fix.lon !in -180.0..180.0)
            return SpeedReading(null, "定位精度不足")
        val prev = previousGps
        if (prev != null && fix.timestampMs <= prev.timestampMs) return SpeedReading(null, "过期定位")
        previousGps = fix
        if (reportedSpeed != null) {
            if (!reportedSpeed.isFinite() || reportedSpeed !in 0f..7f)
                return SpeedReading(null, "速度异常，保留未确认会话")
            if (speedAccuracy != null && (!speedAccuracy.isFinite() || speedAccuracy !in 0f..2f))
                return SpeedReading(null, "速度精度不足")
            return SpeedReading(reportedSpeed, "GPS 原生速度")
        }
        if (prev == null) return SpeedReading(null, "等待同源定位")
        val seconds = (fix.timestampMs - prev.timestampMs) / 1000f
        if (seconds !in 5f..30f || prev.accuracyMeters > 15f || fix.accuracyMeters > 15f)
            return SpeedReading(null, "不足以推算速度")
        val distance = Geo.distanceMeters(prev, fix)
        val uncertainty = prev.accuracyMeters + fix.accuracyMeters
        if (distance < uncertainty + 5f) return SpeedReading(null, "位移未超出定位误差")
        // 使用位移下界，不把不确定度算成真实移动。
        val lowerSpeed = (distance - uncertainty) / seconds
        return if (lowerSpeed in 0f..7f) SpeedReading(lowerSpeed, "GPS 同源位移下界")
            else SpeedReading(null, "定位跳点，保留未确认会话")
    }

    fun reset() { previousGps = null }
}
