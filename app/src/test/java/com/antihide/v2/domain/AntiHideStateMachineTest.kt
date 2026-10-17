package com.antihide.v2.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AntiHideStateMachineTest {

    /** Counts audio transitions the way [com.antihide.v2.audio.AlarmAudioController] observes them. */
    private class AudioProbe {
        var startCount = 0
        var stopCount = 0
    }

    /**
     * Mirrors the service's alarm observation exactly: distinctUntilChanged on alarmState.
     *
     * Runs on its own Unconfined scope so emissions are observed synchronously and the collector
     * never has to finish for runTest to complete.
     */
    private val observerScope = CoroutineScope(Dispatchers.Unconfined)

    private fun observe(machine: AntiHideStateMachine, probe: AudioProbe) {
        observerScope.launch {
            machine.state
                .map { it.alarmState }
                .distinctUntilChanged()
                .collect { if (it == AlarmState.RINGING) probe.startCount++ else probe.stopCount++ }
        }
    }

    @Test
    @DisplayName("the machine starts IDLE with armState ARMED and volume 100")
    fun initialStateIsSafe() = runTest {
        val machine = AntiHideStateMachine(this)
        val s = machine.currentState
        assertEquals(AlarmState.IDLE, s.alarmState)
        assertEquals(ArmState.ARMED, s.armState)
        assertEquals(100, s.volumePercent)
        assertNull(s.ringStartedAtEpochMs)
        assertNull(s.lastStopSource)
    }

    @Test
    @DisplayName("IDLE -> RINGING -> IDLE on a remote ring and stop")
    fun remoteRingStopCycle() = runTest {
        val machine = AntiHideStateMachine(this)
        val ring = machine.process(CommandRequest.Ring("ring-1"))
        assertEquals(AlarmState.RINGING, ring.state.alarmState)
        assertNotNull(ring.state.ringStartedAtEpochMs)
        assertEquals(2L, ring.state.stateVersion)

        val stop = machine.process(CommandRequest.Stop("stop-1", StopSource.REMOTE))
        assertEquals(AlarmState.IDLE, stop.state.alarmState)
        assertEquals(StopSource.REMOTE, stop.state.lastStopSource)
        assertNull(stop.state.ringStartedAtEpochMs)
        assertEquals(3L, stop.state.stateVersion)
    }

    @Test
    @DisplayName("IDLE -> RINGING -> IDLE on a local stop")
    fun localStopCycle() = runTest {
        val machine = AntiHideStateMachine(this)
        machine.ring("ring-local")
        val stop = machine.stopLocally("stop-local")
        assertEquals(AlarmState.IDLE, stop.state.alarmState)
        assertEquals(StopSource.LOCAL, stop.state.lastStopSource)
    }

    @Test
    @DisplayName("a stop while already IDLE is a 200-style no-op, not an error")
    fun stopWhileIdleIsNoOp() = runTest {
        val machine = AntiHideStateMachine(this)
        val result = machine.process(CommandRequest.Stop("stop-idle", StopSource.REMOTE))
        assertFalse(result.rejected)
        assertFalse(result.duplicate)
        assertEquals(AlarmState.IDLE, result.state.alarmState)
        assertEquals(1L, result.state.stateVersion, "a no-op must not advance stateVersion")
    }

    @Test
    @DisplayName("ten identical RING commandIds start audio once and bump stateVersion once")
    fun duplicateRingIdempotency() = runTest {
        val machine = AntiHideStateMachine(this)
        val probe = AudioProbe()
        observe(machine, probe)

        var last: CommandResult? = null
        repeat(10) { last = machine.process(CommandRequest.Ring("same-id")) }
        runCurrent() // let the audio observer run, without advancing the 180s watchdog

        assertEquals(1, probe.startCount, "audio must start exactly once for ten duplicate RINGs")
        assertEquals(2L, machine.currentState.stateVersion)
        assertEquals(AlarmState.RINGING, machine.currentState.alarmState)
        assertNotNull(last)
    }

    @Test
    @DisplayName("a NEW commandId while already RINGING does not restart audio or change state")
    fun newRingWhileRingingDoesNotRestartAudio() = runTest {
        val machine = AntiHideStateMachine(this)
        val probe = AudioProbe()
        observe(machine, probe)

        machine.process(CommandRequest.Ring("ring-a"))
        val firstStart = machine.currentState.ringStartedAtEpochMs
        val version = machine.currentState.stateVersion

        repeat(5) { machine.process(CommandRequest.Ring("ring-" + it)) }
        runCurrent()

        assertEquals(1, probe.startCount, "a second distinct RING must not restart the MediaPlayer")
        assertEquals(firstStart, machine.currentState.ringStartedAtEpochMs, "original start time preserved")
        assertEquals(version, machine.currentState.stateVersion)
        assertEquals(AlarmState.RINGING, machine.currentState.alarmState)
    }

    @Test
    @DisplayName("the 180s watchdog auto-stops with lastStopSource TIMEOUT")
    fun watchdogAutoStops() = runTest {
        val machine = AntiHideStateMachine(this)
        machine.ring("ring-wd")
        assertEquals(AlarmState.RINGING, machine.currentState.alarmState)

        advanceTimeBy(179_999)
        assertEquals(AlarmState.RINGING, machine.currentState.alarmState, "must not stop one millisecond early")

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(StopSource.TIMEOUT, machine.currentState.lastStopSource)
        assertNull(machine.currentState.ringStartedAtEpochMs)
    }

    @Test
    @DisplayName("a manual stop cancels the watchdog so it cannot fire a second transition")
    fun manualStopCancelsWatchdog() = runTest {
        val machine = AntiHideStateMachine(this)
        machine.ring("ring-cancel")
        machine.stopLocally("stop-cancel")
        assertEquals(StopSource.LOCAL, machine.currentState.lastStopSource)

        val versionAfterStop = machine.currentState.stateVersion
        advanceTimeBy(200_000)
        advanceUntilIdle()

        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(StopSource.LOCAL, machine.currentState.lastStopSource, "watchdog must not overwrite LOCAL")
        assertEquals(versionAfterStop, machine.currentState.stateVersion)
    }

    @Test
    @DisplayName("50 concurrent RING and STOP coroutines never corrupt state")
    fun concurrentRacesAreSafe() = runTest {
        val machine = AntiHideStateMachine(this)
        val jobs = (0 until 50).map { i ->
            async {
                if (i % 2 == 0) {
                    machine.process(CommandRequest.Ring("race-ring-$i"))
                } else {
                    machine.process(CommandRequest.Stop("race-stop-$i", StopSource.REMOTE))
                }
            }
        }
        jobs.awaitAll()
        advanceUntilIdle()

        val s = machine.currentState
        assertTrue(s.alarmState == AlarmState.IDLE || s.alarmState == AlarmState.RINGING)
        assertEquals(ArmState.ARMED, s.armState)
        // IDLE implies a cleared start timestamp; RINGING implies one present.
        if (s.alarmState == AlarmState.IDLE) assertNull(s.ringStartedAtEpochMs)
        else assertNotNull(s.ringStartedAtEpochMs)
        assertTrue(s.stateVersion >= 1)
    }

    @Test
    @DisplayName("malformed commandIds are rejected with 400 semantics and do not mutate state")
    fun malformedCommandIdsAreRejected() = runTest {
        val machine = AntiHideStateMachine(this)
        val bad = listOf(null, "", "   ", "has space", "x".repeat(65), "bad/slash", "bad\nnewline")
        for (id in bad) {
            val result = machine.process(CommandRequest.Ring(id ?: ""))
            assertTrue(result.rejected, "expected rejection for '$id'")
            assertEquals("INVALID_COMMAND_ID", result.error)
        }
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(1L, machine.currentState.stateVersion, "rejections must not advance stateVersion")
    }

    @Test
    @DisplayName("only an explicit Ring can ever set RINGING; reads never do")
    fun readsNeverRing() = runTest {
        val machine = AntiHideStateMachine(this)
        // Simulate everything the server does on connect / bootstrap / heartbeat: read only.
        repeat(20) {
            machine.state.value
            machine.currentState
        }
        advanceTimeBy(300_000)
        advanceUntilIdle()
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
    }

    @Test
    @DisplayName("the command ring evicts oldest-first and keeps exactly 64 entries")
    fun commandRingIsBounded() = runTest {
        val machine = AntiHideStateMachine(this)
        repeat(70) { machine.process(CommandRequest.Ring("id-$it")) }
        // id-0 fell out of the ring, so it is no longer a duplicate and rings again.
        val again = machine.process(CommandRequest.Ring("id-0"))
        assertFalse(again.duplicate, "the 64-entry ring must have evicted the oldest commandId")
    }
}