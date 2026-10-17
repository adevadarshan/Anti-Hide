package com.antihide.v2.domain

enum class AlarmState { IDLE, RINGING }

/** Fixed to ARMED for Slice 2. Slice 6 introduces the Arm/Disarm toggle. */
enum class ArmState { ARMED }

enum class StopSource { REMOTE, LOCAL, TIMEOUT }

/**
 * The single source of truth for the phone's alarm state.
 *
 * [stateVersion] advances only on a real transition, so clients can use it to detect change
 * without seeing churn from duplicate or no-op commands.
 */
data class SystemState(
    val alarmState: AlarmState,
    val armState: ArmState,
    val volumePercent: Int,
    val stateVersion: Long,
    val ringStartedAtEpochMs: Long?,
    val lastStopSource: StopSource?,
) {
    companion object {
        fun idle(): SystemState = SystemState(
            alarmState = AlarmState.IDLE,
            armState = ArmState.ARMED,
            volumePercent = 100,
            stateVersion = 1,
            ringStartedAtEpochMs = null,
            lastStopSource = null,
        )
    }
}

sealed interface CommandRequest {
    val commandId: String

    data class Ring(override val commandId: String) : CommandRequest

    data class Stop(override val commandId: String, val source: StopSource) : CommandRequest
}

/**
 * @param duplicate true when [CommandRequest.commandId] was already processed; the state was not
 *   touched and no audio callback fired.
 * @param rejected true for a malformed command id, which maps to HTTP 400.
 */
data class CommandResult(
    val state: SystemState,
    val duplicate: Boolean,
    val rejected: Boolean = false,
    val error: String? = null,
)

object CommandIds {
    const val MAX_LENGTH = 64
    private val PATTERN = Regex("^[A-Za-z0-9._:-]{1,64}$")

    /** Command ids arrive from the network, so validate shape before touching any state. */
    fun isValid(commandId: String?): Boolean = commandId != null && PATTERN.matches(commandId)
}