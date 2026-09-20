package com.buddy.lockguard.core

import org.junit.Assert.*
import org.junit.Test

class SpeedResolverTest {
    private fun fix(t: Long, meters: Double, accuracy: Float = 5f) = LocationFix(t, 30 + meters / 111194.93, 114.0, 0f, accuracy)

    @Test fun networkJumpCannotBecomeRidingSpeed() {
        val resolver = SpeedResolver()
        resolver.resolve(fix(1_000, 0.0), "gps", 0f, 0.2f)
        assertNull(resolver.resolve(fix(6_000, 105.0, 30f), "network", 21.1f, null).speedMps)
        assertEquals(0f, resolver.resolve(fix(11_000, 0.0), "gps", 0f, 0.2f).speedMps)
    }

    @Test fun changingProvidersCannotDeriveSpeedFromDifferentOrigins() {
        val resolver = SpeedResolver()
        resolver.resolve(fix(1_000, 1000.0), "network", null, null)
        assertNull(resolver.resolve(fix(6_000, 0.0), "gps", null, null).speedMps)
    }

    @Test fun zeroNativeSpeedSurvivesCoordinateDrift() {
        val resolver = SpeedResolver()
        resolver.resolve(fix(1_000, 0.0), "gps", 0f, 0.2f)
        assertEquals(0f, resolver.resolve(fix(6_000, 50.0), "gps", 0f, 0.2f).speedMps)
    }

    @Test fun gpsJumpAndUncertainSpeedAreDiscarded() {
        val resolver = SpeedResolver()
        assertNull(resolver.resolve(fix(1_000, 0.0), "gps", 21f, null).speedMps)
        assertNull(resolver.resolve(fix(6_000, 0.0), "gps", 2f, 5f).speedMps)
    }

    @Test fun inferredSpeedUsesOnlyDisplacementAboveError() {
        val resolver = SpeedResolver()
        resolver.resolve(fix(1_000, 0.0), "gps", null, null)
        val speed = resolver.resolve(fix(11_000, 30.0), "gps", null, null).speedMps
        assertEquals(2f, speed!!, 0.02f)
    }

    @Test fun uncertainDisplacementIsNotInventedAsStationary() {
        val resolver = SpeedResolver()
        resolver.resolve(fix(1_000, 0.0), "gps", null, null)
        assertNull(resolver.resolve(fix(6_000, 3.0), "gps", null, null).speedMps)
    }
}
