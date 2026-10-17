package com.antihide.v2.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutex-guarded alarm state machine and the only thing that may set [AlarmState.RINGING].
 *
 * Invariant #1: the sole writer to RINGING is an explicit [CommandRequest.Ring]. Service startup,
 * session bootstrap, SSE connect and heartbeats all only ever read [state].
 * Invariant #6: a repeated [CommandRequest.commandId] returns the current state without mutating
 * anything, so audio is never restarted by a duplicate or a retry.
 */
class AntiHideStateMachine(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    val autoStopMs: Long = AUTO_STOP_MS,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SystemState.idle())

    /** Newest first. Consumers (audio, SSE) observe this. */
    val state: StateFlow<SystemState> = _state.asStateFlow()

    private val commandRing = LinkedHashSet<String>()
    private var watchdogJob: Job? = null

    val currentState: SystemState get() = _state.value

    suspend fun process(command: CommandRequest): CommandResult = mutex.withLock {
        if (!CommandIds.isValid(command.commandId)) {
            return@withLock CommandResult(
                state = _state.value,
                duplicate = false,
                rejected = true,
                error = "INVALID_COMMAND_ID",
            )
        }
        if (!commandRing.add(command.commandId)) {
            return@withLock CommandResult(state = _state.value, duplicate = true)
        }
        trimRing()

        val current = _state.value
        val next = when (command) {
            is CommandRequest.Ring -> when (current.alarmState) {
                // Already ringing: keep the original start time so the watchdog is not restarted
                // and the playing MediaPlayer is never stopped and restarted underneath us.
                AlarmState.RINGING -> return@withLock CommandResult(state = current, duplicate = false)
                AlarmState.IDLE -> current.copy(
                    alarmState = AlarmState.RINGING,
                    ringStartedAtEpochMs = clock(),
                    lastStopSource = null,
                )
            }

            is CommandRequest.Stop -> when (current.alarmState) {
                // Already idle: a valid stop is a no-op success, not an error.
                AlarmState.IDLE -> return@withLock CommandResult(state = current, duplicate = false)
                AlarmState.RINGING -> current.copy(
                    alarmState = AlarmState.IDLE,
                    ringStartedAtEpochMs = null,
                    lastStopSource = command.source,
                )
            }
        }

        val transitioned = next.copy(stateVersion = current.stateVersion + 1)
        _state.value = transitioned

        if (transitioned.alarmState == AlarmState.RINGING) {
            startWatchdog()
        } else {
            watchdogJob?.cancel()
            watchdogJob = null
        }

        CommandResult(state = transitioned, duplicate = false)
    }

    /** Local stop from the phone UI: identical semantics to a remote STOP, different source. */
    suspend fun stopLocally(commandId: String): CommandResult =
        process(CommandRequest.Stop(commandId, StopSource.LOCAL))

    /**
     * Starts the alarm on an explicit ring. Used by the phone UI and tests; the HTTP layer goes
     * through [process] so that Layer 2 authorisation cannot be bypassed.
     */
    suspend fun ring(commandId: String): CommandResult = process(CommandRequest.Ring(commandId))

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(autoStopMs)
            // Re-enter through process() so the watchdog stop is audited and cancels cleanly.
            process(CommandRequest.Stop("watchdog-${_state.value.stateVersion}", StopSource.TIMEOUT))
        }
    }

    private fun trimRing() {
        while (commandRing.size > RING_CAPACITY) {
            val oldest = commandRing.first()
            commandRing.remove(oldest)
        }
    }

    companion object {
        const val AUTO_STOP_MS = 180_000L
        const val RING_CAPACITY = 64
    }
}