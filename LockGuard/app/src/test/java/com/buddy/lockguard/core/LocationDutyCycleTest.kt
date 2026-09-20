package com.buddy.lockguard.core

import org.junit.Assert.*
import org.junit.Test

class LocationDutyCycleTest {
    @Test fun stationaryIdleStopsGpsAfterInitialMinute() {
        val policy = LocationDutyCycle()
        assertTrue(policy.update(1_000, RideState.IDLE, true))
        assertFalse(policy.update(61_000, RideState.IDLE, true))
        assertFalse(policy.update(3_600_000, RideState.IDLE, true))
        assertNull(policy.nextCheckMs(3_600_000, RideState.IDLE, true))
    }

    @Test fun lockingImmediatelyStopsContinuousGps() {
        val policy = LocationDutyCycle()
        assertTrue(policy.update(1_000, RideState.RIDING, true))
        assertFalse(policy.update(10_000, RideState.IDLE, true))
        policy.motion(11_000)
        assertFalse(policy.update(11_000, RideState.IDLE, true))
    }

    @Test fun laterMotionWakesBoundedProbe() {
        val policy = LocationDutyCycle()
        policy.update(1_000, RideState.IDLE, true)
        policy.update(61_000, RideState.IDLE, true)
        policy.motion(100_000)
        assertTrue(policy.update(100_000, RideState.IDLE, true))
        policy.motion(150_000)
        assertFalse(policy.update(160_000, RideState.IDLE, true))
    }

    @Test fun missingMotionSensorUsesIntermittentFallback() {
        val policy = LocationDutyCycle()
        policy.update(1_000, RideState.IDLE, false)
        assertFalse(policy.update(61_000, RideState.IDLE, false))
        assertEquals(181_000L, policy.nextCheckMs(61_000, RideState.IDLE, false))
        assertTrue(policy.update(181_000, RideState.IDLE, false))
        assertFalse(policy.update(226_000, RideState.IDLE, false))
    }

    @Test fun unconfirmedParkingStopsContinuousGpsButKeepsFallback() {
        val policy = LocationDutyCycle()
        policy.update(1_000, RideState.RIDING, true)
        assertFalse(policy.update(30_000, RideState.PARKED, true))
        assertTrue(policy.update(210_000, RideState.PARKED, true))
    }
}
