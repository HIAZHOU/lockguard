package com.buddy.lockguard.core

import org.junit.Assert.*
import org.junit.Test

/** 测试实际 Kotlin 状态机；时间和定位输入均可控。 */
class RideStateMachineTest {
    private fun fix(t: Long, speed: Float = 0f, meters: Double = 0.0, accuracy: Float = 5f) =
        LocationFix(t, 30.0 + meters / 111194.93, 114.0, speed, accuracy)

    private fun feed(machine: RideStateMachine, from: Long, to: Long, speed: Float, meters: Double = 0.0): List<RideEvent> =
        (from..to step 5_000L).flatMap { machine.onLocation(fix(it, speed, meters)) }

    private fun riding(): RideStateMachine = RideStateMachine().also {
        feed(it, 5_000, 60_000, 4f)
        assertEquals(RideState.RIDING, it.state)
    }

    private fun paused(): RideStateMachine = riding().also {
        feed(it, 65_000, 75_000, 0f)
        assertEquals(RideState.PAUSED, it.state)
    }

    @Test fun slowCampusRideStartsAutomatically() {
        val machine = RideStateMachine()
        val events = feed(machine, 5_000, 30_000, 2.3f)
        assertEquals(1, events.filterIsInstance<RideEvent.RideStarted>().size)
        assertEquals(RideState.RIDING, machine.state)
        assertEquals(5_000L, machine.rideStartedAtMs)
    }

    @Test fun singleSpeedSpikeDoesNotStartRide() {
        val machine = RideStateMachine()
        machine.onLocation(fix(5_000, 4f))
        feed(machine, 10_000, 120_000, 0f)
        assertEquals(RideState.IDLE, machine.state)
    }

    @Test fun ordinaryWalkingDoesNotStartRide() {
        val machine = RideStateMachine()
        assertTrue(feed(machine, 5_000, 300_000, 1.6f).isEmpty())
    }

    @Test fun carSpeedDoesNotStartRide() {
        val machine = RideStateMachine()
        assertTrue(feed(machine, 5_000, 300_000, 15f).isEmpty())
    }

    @Test fun sparseRidingSamplesDoNotCountAsContinuousEvidence() {
        val machine = RideStateMachine()
        machine.onLocation(fix(5_000, 4f))
        machine.onLocation(fix(100_000, 4f))
        assertEquals(RideState.IDLE, machine.state)
    }

    @Test fun parkedInPlaceGetsReminderWithinTwoMinutesAfterPause() {
        val machine = paused()
        val events = feed(machine, 80_000, 200_000, 0f)
        val alert = events.filterIsInstance<RideEvent.AlertRaised>().single()
        assertEquals(AlertReason.STOP_TIMEOUT, alert.reason)
        assertTrue(alert.atMs <= 195_000)
        assertEquals(0f, alert.meters, 0.1f)
    }

    @Test fun walkingOnlyFiveOrTenMetersStillGetsReminder() {
        for (distance in listOf(5.0, 10.0)) {
            val machine = paused()
            machine.onLocation(fix(80_000, 1.2f, distance))
            val events = feed(machine, 85_000, 200_000, 0f, distance)
            assertTrue(events.any { it is RideEvent.AlertRaised && it.reason == AlertReason.STOP_TIMEOUT })
        }
    }

    @Test fun pausedThenIndoorGpsLossDoesNotBlockTimer() {
        val machine = paused()
        assertTrue(machine.onTick(110_000).contains(RideEvent.ParkedDetected))
        val alert = machine.onTick(195_000).filterIsInstance<RideEvent.AlertRaised>().single()
        assertEquals(AlertReason.STOP_TIMEOUT, alert.reason)
        assertEquals(RideState.ALERT_ARMED, machine.state)
    }

    @Test fun ninetySecondRedLightDoesNotAlertAndResumes() {
        val machine = riding()
        val events = feed(machine, 65_000, 155_000, 0f) + feed(machine, 160_000, 230_000, 4f)
        assertTrue(events.none { it is RideEvent.AlertRaised })
        assertEquals(RideState.RIDING, machine.state)
    }

    @Test fun directlyWalkingAfterRideCanPauseWithoutZeroSpeedSample() {
        val machine = riding()
        val events = feed(machine, 65_000, 200_000, 1.2f)
        assertTrue(events.any { it is RideEvent.AlertRaised })
    }

    @Test fun signalLossAsksForConfirmationWithoutClaimingParking() {
        val machine = riding()
        assertTrue(machine.onTick(239_000).isEmpty())
        val event = machine.onTick(240_000).filterIsInstance<RideEvent.AlertRaised>().single()
        assertEquals(AlertReason.SIGNAL_LOST, event.reason)
        assertEquals(RideState.RIDING, machine.state)
        assertTrue(machine.onTick(900_000).isEmpty())
    }

    @Test fun stepsAfterRideSupportIndoorStop() {
        val machine = riding()
        (80_000L..85_000L step 1_000L).forEach { machine.onStep(it) }
        assertEquals(RideState.PAUSED, machine.state)
        val events = machine.onTick(205_000)
        assertTrue(events.any { it is RideEvent.AlertRaised && it.reason == AlertReason.STOP_TIMEOUT })
    }

    @Test fun isolatedStepAndIdleStepsDoNotTriggerReminders() {
        val idle = RideStateMachine()
        (1_000L..200_000L step 1_000L).forEach { assertTrue(idle.onStep(it).isEmpty()) }
        val machine = riding()
        machine.onStep(80_000)
        assertEquals(RideState.RIDING, machine.state)
    }

    @Test fun recentRidingGpsOverridesSpuriousSteps() {
        val machine = riding()
        (61_000L..66_000L step 1_000L).forEach { machine.onStep(it) }
        assertEquals(RideState.RIDING, machine.state)
    }

    @Test fun poorAndOutOfOrderLocationsCannotChangeState() {
        val machine = riding()
        machine.onLocation(fix(65_000, 0f, 1000.0, 150f))
        machine.onLocation(fix(10_000, 0f))
        machine.onLocation(fix(70_000, Float.NaN))
        assertEquals(RideState.RIDING, machine.state)
    }

    @Test fun confirmationCancelsTimersAndFutureAlerts() {
        val machine = paused()
        machine.confirmLocked()
        assertTrue(machine.onTick(2_000_000).isEmpty())
        assertTrue(feed(machine, 80_000, 300_000, 0f).isEmpty())
        assertEquals(0L, machine.rideDurationMs(3_000_000))
    }

    @Test fun escalationWorksWithoutFurtherLocationCallbacks() {
        val machine = paused()
        val events = machine.onTick(195_000) + machine.onTick(375_000) + machine.onTick(1_095_000)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<RideEvent.AlertRaised>().map { it.level })
        assertTrue(machine.onTick(2_000_000).isEmpty())
    }

    @Test fun snoozeSuppressesThenRemindsAgain() {
        val machine = paused()
        machine.onTick(195_000)
        machine.snooze(200_000)
        assertTrue(machine.onTick(799_999).isEmpty())
        assertTrue(machine.onTick(800_000).any { it is RideEvent.AlertRaised })
    }

    @Test fun newRideDoesNotInheritOldSnooze() {
        val machine = paused()
        machine.snooze(80_000)
        machine.confirmLocked()
        machine.startRide(90_000)
        machine.onLocation(fix(95_000, 0f))
        assertTrue(machine.onTick(220_000).any { it is RideEvent.AlertRaised })
    }

    @Test fun resumedRidingClearsParkingAlert() {
        val machine = paused()
        machine.onTick(195_000)
        val events = feed(machine, 200_000, 240_000, 4f)
        assertTrue(events.any { it is RideEvent.RolledBack })
        assertEquals(RideState.RIDING, machine.state)
        assertTrue(machine.onTick(245_000).isEmpty())
    }

    @Test fun reliableLongDistanceCanRemindEarlier() {
        val machine = paused()
        machine.onTick(110_000)
        val events = machine.onLocation(fix(115_000, 1.2f, 100.0))
        assertTrue(events.any { it is RideEvent.AlertRaised && it.reason == AlertReason.DISTANCE })
    }

    @Test fun gpsDriftWithinUncertaintyDoesNotCreateDistanceAlert() {
        val machine = paused()
        machine.onTick(110_000)
        val events = machine.onLocation(fix(115_000, 1.2f, 90.0, 30f))
        assertTrue(events.none { it is RideEvent.AlertRaised })
    }

    @Test fun recoveredRidingSignalClearsLostSignalPrompt() {
        val machine = riding()
        machine.onTick(240_000)
        val events = machine.onLocation(fix(245_000, 4f))
        assertTrue(events.any { it is RideEvent.RolledBack })
        assertEquals(RideState.RIDING, machine.state)
        assertTrue(machine.onTick(250_000).isEmpty())
    }
}
