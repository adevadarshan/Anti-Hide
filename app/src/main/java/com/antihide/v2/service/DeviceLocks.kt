package com.antihide.v2.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Seams over the two OS locks so they can be unit tested off-device.
 *
 * The interfaces exist so tests assert acquire/release *ordering* rather than poking real
 * PowerManager state, which is unavailable in a JVM unit test.
 */
interface WakeLockController {
    fun acquire()
    fun release()
    val held: Boolean
}

interface WifiLockController {
    fun acquire()
    fun release()
    val held: Boolean
}

class AndroidWakeLockController(
    context: Context,
    private val timeoutMs: Long = WAKE_LOCK_TIMEOUT_MS,
) : WakeLockController {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
        setReferenceCounted(false)
    }

    override var held: Boolean = false
        private set

    @Synchronized
    override fun acquire() {
        if (held) return
        // Hard ceiling in case the release path ever fails: the CPU cannot stay awake forever.
        runCatching { lock.acquire(timeoutMs) }
            .onFailure { return }
        held = true
    }

    @Synchronized
    override fun release() {
        if (!held) return
        // Always attempt the release, even if we think it was not held: a leaked wake lock drains
        // the battery and keeps the CPU awake, which is worse than a spurious release.
        runCatching { if (lock.isHeld) lock.release() }
        held = false
    }

    companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 185_000L
        private const val LOCK_TAG = "AntiHideV2:alarm"
    }
}

class AndroidWifiLockController(context: Context) : WifiLockController {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, LOCK_TAG)

    override var held: Boolean = false
        private set

    @Suppress("DEPRECATION")
    @Synchronized
    override fun acquire() {
        if (held) return
        runCatching { if (!lock.isHeld) lock.acquire() }
        held = lock.isHeld
    }

    @Suppress("DEPRECATION")
    @Synchronized
    override fun release() {
        if (!held) return
        runCatching { if (lock.isHeld) lock.release() }
        held = false
    }

    private companion object {
        const val LOCK_TAG = "AntihideV2:wifi"
    }
}

/** In-memory stand-ins for unit tests. */
class FakeWakeLockController : WakeLockController {
    var acquireCount = 0
        private set
    var releaseCount = 0
        private set
    override var held: Boolean = false
        private set

    override fun acquire() {
        acquireCount++
        held = true
    }

    override fun release() {
        releaseCount++
        held = false
    }
}

class FakeWifiLockController : WifiLockController {
    var acquireCount = 0
        private set
    var releaseCount = 0
        private set
    override var held: Boolean = false
        private set

    override fun acquire() {
        acquireCount++
        held = true
    }

    override fun release() {
        releaseCount++
        held = false
    }
}