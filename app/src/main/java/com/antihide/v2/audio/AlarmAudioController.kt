package com.antihide.v2.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.antihide.v2.R
import com.antihide.v2.net.NetLog

/**
 * Plays the alarm on [AudioAttributes.USAGE_ALARM] so it is audible in silent and vibrate modes.
 *
 * Every public method is synchronized and [start] refuses to run while a player already exists,
 * which is what guarantees the roadmap's "no overlapping MediaPlayer instances" requirement under
 * duplicate RING commands. [stop] releases the player, the asset descriptor, the vibrator and the
 * audio focus inside try/finally, so a throwing call can never leave the phone making noise.
 */
class AlarmAudioController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var player: MediaPlayer? = null
    private var assetDescriptor: android.content.res.AssetFileDescriptor? = null
    private var focusRequest: AudioFocusRequest? = null
    private var usingFallbackRingtone = false

    val isPlaying: Boolean get() = synchronized(this) { player != null }

    /** True when the bundled asset failed and the system alarm tone is being used instead. */
    val usingFallback: Boolean get() = synchronized(this) { usingFallbackRingtone }

    @Synchronized
    fun start() {
        if (player != null) {
            // Already sounding. Never stop/restart the active player: that would stutter the alarm.
            return
        }
        requestAudioFocus()
        player = buildBundledPlayer() ?: buildFallbackPlayer()
        if (player == null) {
            NetLog.record("ALARM_NO_PLAYER")
            abandonAudioFocus()
            startVibration()
            return
        }
        try {
            player?.start()
            NetLog.record("ALARM_STARTED fallback=$usingFallbackRingtone")
        } catch (e: Exception) {
            NetLog.record("ALARM_START_FAILED ${e.javaClass.simpleName}")
            releasePlayer()
            if (buildFallbackPlayer() != null) {
                runCatching { player?.start() }
                NetLog.record("ALARM_STARTED_FALLBACK")
            }
        }
        startVibration()
    }

    @Synchronized
    fun stop() {
        stopVibration()
        try {
            player?.let { mp -> runCatching { if (mp.isPlaying) mp.stop() } }
        } finally {
            releasePlayer()
            abandonAudioFocus()
            NetLog.record("ALARM_STOPPED")
        }
    }

    private fun buildBundledPlayer(): MediaPlayer? {
        val descriptor = runCatching { context.resources.openRawResourceFd(R.raw.antihide_alarm) }.getOrNull()
        if (descriptor == null) {
            NetLog.record("ALARM_ASSET_MISSING")
            return null
        }
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(alarmAttributes())
            mp.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                NetLog.record("ALARM_MEDIA_ERROR what=$what extra=$extra")
                true
            }
            mp.prepare()
            assetDescriptor = descriptor
            usingFallbackRingtone = false
            mp
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            NetLog.record("ALARM_BUNDLED_FAILED ${e.javaClass.simpleName}")
            null
        }
    }

    /** Roadmap fallback: if the bundled asset will not decode, ring the system alarm tone. */
    private fun buildFallbackPlayer(): MediaPlayer? {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return null
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(alarmAttributes())
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            usingFallbackRingtone = true
            NetLog.record("ALARM_USING_SYSTEM_RINGTONE")
            mp
        } catch (e: Exception) {
            NetLog.record("ALARM_FALLBACK_FAILED ${e.javaClass.simpleName}")
            null
        }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        runCatching { assetDescriptor?.close() }
        assetDescriptor = null
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(alarmAttributes())
            .setOnAudioFocusChangeListener { change ->
                NetLog.record("ALARM_AUDIO_FOCUS change=$change")
            }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
            .onFailure { NetLog.record("ALARM_FOCUS_REQUEST_FAILED ${it.javaClass.simpleName}") }
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        runCatching { audioManager.abandonAudioFocusRequest(request) }
            .onFailure { NetLog.record("ALARM_FOCUS_ABANDON_FAILED ${it.javaClass.simpleName}") }
        focusRequest = null
    }

    private fun vibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    } catch (_: Exception) {
        null
    }

    private fun startVibration() {
        // A missing or disabled vibrator is handled by the null check and the catch below.
        val vib = vibrator() ?: return
        // amplitude 0 = default; repeating pattern, no explicit loop flag needed
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vib.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    @Suppress("DEPRECATION") vib.vibrate(effect, alarmAttributes())

                else -> {
                    @Suppress("DEPRECATION")
                    vib.vibrate(effect)
                }
            }
        } catch (e: Exception) {
            NetLog.record("ALARM_VIBRATE_FAILED ${e.javaClass.simpleName}")
        }
    }

    private fun stopVibration() {
        runCatching { vibrator()?.cancel() }
    }

    private companion object {
        val VIBRATION_PATTERN = longArrayOf(0, 600, 400)
    }
}