package com.buddy.lockguard.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 两点球面距离（haversine）。判定距离只有几十到几百米，容差远大于公式误差。 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG_TO_RAD = Math.PI / 180.0

    fun distanceMeters(a: LocationFix, b: LocationFix): Float {
        val dLat = (b.lat - a.lat) * DEG_TO_RAD
        val dLon = (b.lon - a.lon) * DEG_TO_RAD
        val la1 = a.lat * DEG_TO_RAD
        val la2 = b.lat * DEG_TO_RAD

        val sinHalfLat = sin(dLat / 2)
        val sinHalfLon = sin(dLon / 2)
        val h = sinHalfLat * sinHalfLat + cos(la1) * cos(la2) * sinHalfLon * sinHalfLon

        return (2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))).toFloat()
    }
}
