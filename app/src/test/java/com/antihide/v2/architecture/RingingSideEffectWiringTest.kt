package com.antihide.v2.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the completeness of the RINGING side-effect set in the foreground service.
 *
 * During Slice 3 an edit to `onCreate` silently deleted the `audio.start()` call. Every other
 * signal still looked correct — the state machine reported RINGING, the wake lock was acquired, the
 * notification was posted, the lock screen showed a STOP button — so the only symptom was that the
 * alarm was completely silent. Nothing in the unit suite noticed, because the observers are driven
 * by coroutines inside a real Service.
 *
 * This test is deliberately a source guard rather than a behaviour test: a behavioural test would
 * only exercise a copy of the observer living in the test file, and would keep passing while the
 * production copy lost its audio.
 */
class RingingSideEffectWiringTest {

    private fun serviceSource(): File {
        val start = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "src/main").isDirectory) {
                return File(dir, "src/main/java/com/antihide/v2/service/AntiHideForegroundService.kt")
            }
            dir = dir.parentFile
        }
        error("could not locate src/main from $start")
    }

    @Test
    @DisplayName("the foreground service file exists")
    fun serviceFileExists() {
        assertTrue(serviceSource().isFile, "AntiHideForegroundService.kt is missing at ${serviceSource()}")
    }

    @Test
    @DisplayName("every RINGING side effect is wired: sound, wake lock and lock-screen notification")
    fun allRingingSideEffectsAreWired() {
        val file = serviceSource()
        val text = file.readText()

        // Each of these must appear; a missing one means the alarm silently degrades to a
        // half-working state that still looks healthy in dumpsys output.
        val required = listOf(
            "audio.start()" to "the alarm makes no sound",
            "audio.stop()" to "the alarm never falls silent on STOP",
            "wakeLock?.acquire()" to "the CPU sleeps mid-alarm",
            "wakeLock?.release()" to "a wake lock leaks and drains the battery",
            "notifier.showRinging(" to "no lock-screen STOP surface",
            "notifier.clearRinging(" to "a stale RINGING notification is left on screen",
            "ACTION_LOCAL_STOP" to "the lock-screen STOP button cannot reach the service",
        )

        val missing = required.filter { (token, _) -> !text.contains(token) }
        assertTrue(
            missing.isEmpty(),
            "RINGING side effects missing from ${file.name}:\n" +
                missing.joinToString("\n") { "  missing '$it'" },
        )
    }

    @Test
    @DisplayName("the alarm observer starts sound before it posts the stop surface")
    fun audioStartsBeforeTheNotification() {
        val text = serviceSource().readText()
        val startIdx = text.indexOf("audio.start()")
        val notifyIdx = text.indexOf("notifier.showRinging(")
        assertTrue(startIdx >= 0 && notifyIdx >= 0, "both calls must exist")
        assertTrue(
            startIdx < notifyIdx,
            "audio.start() must precede notifier.showRinging(): if audio throws, the user must " +
                "still get a tap-to-stop surface rather than an unstoppable silent lock screen",
        )
    }
}