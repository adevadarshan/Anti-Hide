package com.antihide.v2.service

import com.antihide.v2.domain.AlarmState
import com.antihide.v2.domain.AntiHideStateMachine
import com.antihide.v2.domain.CommandRequest
import com.antihide.v2.domain.StopSource
import com.antihide.v2.domain.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Slice 3 service-lifecycle guarantees, exercised off-device against the fake lock controllers.
 *
 * The observer under test mirrors the production one in AntiHideForegroundService.startLockObserver
 * exactly: distinctUntilChanged over alarmState, acquire on RINGING, unconditional release on IDLE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceLifecycleTest {

    private val observerScope = CoroutineScope(Dispatchers.Unconfined)

    private fun startLockObserver(machine: AntiHideStateMachine, wake: WakeLockController) {
        observerScope.launch {
            machine.state
                .map { it.alarmState }
                .distinctUntilChanged()
                .collect { alarm ->
                    if (alarm == AlarmState.RINGING) wake.acquire() else wake.release()
                }
        }
    }

    // ---------------------------------------------------------------- WakeLock

    @Test
    @DisplayName("the wake lock is not held while the alarm is IDLE")
    fun wakeLockNotHeldWhileIdle() = runTest {
        val machine = AntiHideStateMachine(this)
        val wake = FakeWakeLockController()
        startLockObserver(machine, wake)

        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertFalse(wake.held, "no wake lock may be held before any RING")
        assertEquals(0, wake.acquireCount)
    }

    @Test
    @DisplayName("the wake lock is acquired exactly once on RINGING and never re-acquired")
    fun wakeLockAcquiredOnlyOnRinging() = runTest {
        val machine = AntiHideStateMachine(this)
        val wake = FakeWakeLockController()
        startLockObserver(machine, wake)

        machine.process(CommandRequest.Ring("w-1"))
        assertTrue(wake.held, "wake lock must be held while ringing")
        assertEquals(1, wake.acquireCount)

        // Five more RINGs with distinct ids while already ringing must not re-acquire.
        repeat(5) { machine.process(CommandRequest.Ring("w-extra-$it")) }
        assertEquals(1, wake.acquireCount, "a repeated RING must not re-acquire the lock")
        assertTrue(wake.held)
    }

    @Test
    @DisplayName("the wake lock is released unconditionally the moment the alarm goes IDLE")
    fun wakeLockReleasedOnIdle() = runTest {
        val machine = AntiHideStateMachine(this)
        val wake = FakeWakeLockController()
        startLockObserver(machine, wake)
        // The observer's first emission is the seeded IDLE, which releases once; baseline it here.
        val baseline = wake.releaseCount

        machine.process(CommandRequest.Ring("w-2"))
        assertTrue(wake.held)

        machine.process(CommandRequest.Stop("w-2-stop", StopSource.REMOTE))
        assertFalse(wake.held, "wake lock must be released on STOP")
        assertEquals(baseline + 1, wake.releaseCount, "exactly one release per RINGING -> IDLE transition")
        assertEquals(1, wake.releaseCount - baseline)
    }

    @Test
    @DisplayName("the wake lock is released on LOCAL stop and on the TIMEOUT watchdog alike")
    fun wakeLockReleasedOnEveryStopPath() = runTest {
        val machine = AntiHideStateMachine(this)
        val wake = FakeWakeLockController()
        startLockObserver(machine, wake)

        machine.process(CommandRequest.Ring("local-path"))
        machine.stopLocally("local-stop")
        assertFalse(wake.held)
        assertEquals(StopSource.LOCAL, machine.currentState.lastStopSource)

        val wake2 = FakeWakeLockController()
        val machine2 = AntiHideStateMachine(this)
        startLockObserver(machine2, wake2)
        machine2.process(CommandRequest.Ring("timeout-path"))
        assertTrue(wake2.held)
        advanceUntilIdle() // runs the 180s watchdog
        assertEquals(StopSource.TIMEOUT, machine2.currentState.lastStopSource)
        assertFalse(wake2.held, "the watchdog path must also release the wake lock")
    }

    @Test
    @DisplayName("a lock release is idempotent and a repeated release is harmless")
    fun releaseIsIdempotent() = runTest {
        val wake = FakeWakeLockController()
        wake.release()
        assertFalse(wake.held)
        wake.acquire(); wake.acquire()
        assertTrue(wake.held)
        wake.release(); wake.release()
        assertFalse(wake.held)
    }

    // ---------------------------------------------------------------- WifiLock

    @Test
    @DisplayName("the wifi lock is held for the service lifetime, independent of alarm state")
    fun wifiLockLifetimeIsIndependentOfAlarm() = runTest {
        val machine = AntiHideStateMachine(this)
        val wifi = FakeWifiLockController()
        wifi.acquire() // what onCreate does
        assertTrue(wifi.held)

        machine.process(CommandRequest.Ring("wifi-1"))
        assertTrue(wifi.held, "ringing must not drop the wifi lock")
        machine.process(CommandRequest.Stop("wifi-1-stop", StopSource.LOCAL))
        assertTrue(wifi.held, "idle must not drop the wifi lock; it is released in onDestroy")
    }

    // ---------------------------------------------------------------- Invariant #2

    @Test
    @DisplayName("a freshly constructed state machine is always IDLE (Invariant #2)")
    fun freshMachineIsAlwaysIdle() {
        repeat(5) {
            val state: SystemState = AntiHideStateMachine(CoroutineScope(Dispatchers.Unconfined)).currentState
            assertEquals(AlarmState.IDLE, state.alarmState, "service restart must never auto-ring")
            assertEquals(null, state.ringStartedAtEpochMs)
            assertEquals(null, state.lastStopSource)
        }
    }

    @Test
    @DisplayName("recreating the service after a RING still starts IDLE and silent")
    fun serviceRestartDoesNotResumeRinging() = runTest {
        val machine = AntiHideStateMachine(this)
        machine.process(CommandRequest.Ring("before-restart"))
        assertEquals(AlarmState.RINGING, machine.currentState.alarmState)

        // Simulates the OS tearing the process down and the service coming back up.
        val afterRestart = AntiHideStateMachine(this)
        assertEquals(AlarmState.IDLE, afterRestart.currentState.alarmState)

        // And the restarted machine cannot inherit the old watcher: no wake lock, no audio.
        val wake = FakeWakeLockController()
        startLockObserver(afterRestart, wake)
        assertFalse(wake.held)
    }

    // ---------------------------------------------------------------- ACTION_LOCAL_STOP

    @Test
    @DisplayName("ACTION_LOCAL_STOP intent maps to a LOCAL stop with source LOCAL")
    fun localStopIntentSemantics() = runTest {
        val machine = AntiHideStateMachine(this)
        machine.process(CommandRequest.Ring("local-1"))

        // This is exactly what onStartCommand does when it receives ACTION_LOCAL_STOP.
        machine.stopLocally("local-${System.currentTimeMillis()}")

        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(StopSource.LOCAL, machine.currentState.lastStopSource)
    }

    @Test
    @DisplayName("ACTION_LOCAL_STOP from a stopped alarm is a harmless no-op")
    fun localStopWhileIdleIsHarmless() = runTest {
        val machine = AntiHideStateMachine(this)
        val version = machine.currentState.stateVersion
        machine.stopLocally("local-idle")
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(version, machine.currentState.stateVersion, "a local stop while idle must not mutate state")
    }
}