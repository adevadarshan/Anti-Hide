package com.antihide.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antihide.v2.audio.AlarmAudioController
import com.antihide.v2.auth.EncryptedSessionStore
import com.antihide.v2.auth.KeystoreCsrfKeyProvider
import com.antihide.v2.auth.SessionAuthManager
import com.antihide.v2.domain.AlarmState
import com.antihide.v2.domain.AntiHideStateMachine
import com.antihide.v2.net.AssetSource
import com.antihide.v2.net.FallbackSocketBinder
import com.antihide.v2.net.LocatingSocketBinder
import com.antihide.v2.net.NetLog
import com.antihide.v2.net.TailscaleAddressLocator
import com.antihide.v2.net.TailscaleInterfaceServer
import com.antihide.v2.net.VpnNetworkSocketBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the Slice 1 server. The Compose UI and the foreground service both talk to
 * this single instance, so the pairing window and session table survive Activity recreation.
 */
object AntiHideRuntime {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    var vpnNetwork: Network? = null

    @Volatile
    private var instance: TailscaleInterfaceServer? = null

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Created eagerly so the watchdog scope exists before any command can arrive. */
    val stateMachine: AntiHideStateMachine by lazy { AntiHideStateMachine(runtimeScope) }

    val server: TailscaleInterfaceServer
        get() = instance ?: synchronized(this) {
            instance ?: build().also { instance = it }
        }

    /**
     * Local stop from the phone UI. Deliberately in-process: it must keep working with Wi-Fi and
     * Tailscale switched off (roadmap invariant #3).
     */
    fun stopAlarmLocally() {
        runtimeScope.launch {
            stateMachine.stopLocally("local-${System.currentTimeMillis()}")
        }
    }

    private fun build(): TailscaleInterfaceServer {
        val context = requireNotNull(appContext) { "AntiHideRuntime.install() was never called" }
        val auth = SessionAuthManager(EncryptedSessionStore(context), KeystoreCsrfKeyProvider())
        return TailscaleInterfaceServer(
            auth = auth,
            stateMachine = stateMachine,
            assets = object : AssetSource {
                override fun read(name: String): ByteArray? =
                    runCatching { context.assets.open("dashboard/$name").use { it.readBytes() } }.getOrNull()
            },
            binderProvider = {
                val network = vpnNetwork
                val locating = LocatingSocketBinder(TailscaleAddressLocator.fromSystem())
                if (network == null) locating else FallbackSocketBinder(VpnNetworkSocketBinder(network), locating)
            },
        )
    }
}

/**
 * Minimal foreground service hosting the server. It also watches the Tailscale VPN network:
 * `ConnectivityManager.NetworkCallback` is the fast path, and a one-second signature poll is the
 * guaranteed path on OEM builds that withhold VPN callbacks from non-VPN-owner apps.
 */
class AntiHideForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectivityManager: ConnectivityManager? = null
    private var lastNetworkSignature = ""
    private var pollJob: kotlinx.coroutines.Job? = null
    private var lockJob: kotlinx.coroutines.Job? = null
    private lateinit var audio: AlarmAudioController
    private lateinit var notifier: AlarmNotifier

    // Built in onCreate, not at field-initialiser time: Android instantiates a Service before
    // attachBaseContext, so touching getSystemService() from a property initialiser NPEs.
    internal var wakeLock: WakeLockController? = null
    internal var wifiLock: WifiLockController? = null

    override fun onCreate() {
        super.onCreate()
        AntiHideRuntime.install(this)
        wakeLock = AndroidWakeLockController(this)
        wifiLock = AndroidWifiLockController(this)
        audio = AlarmAudioController(this)
        notifier = AlarmNotifier(this).also { it.ensureChannels() }
        // Invariant #2: a fresh process always starts IDLE. AntiHideStateMachine() seeds IDLE and no
        // code path below can set RINGING, so service creation and restart can never ring.
        wifiLock?.acquire()
        startAlarmObserver()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startForegroundCompat()
        registerVpnCallback()
        // Seed the signature before start() so the first poll does not fire a redundant rebind
        // that would race the initial bind and produce two listeners on the same port.
        lastNetworkSignature = currentSignature()
        AntiHideRuntime.server.start()
        pollJob = scope.launch {
            while (isActive) {
                rebindIfChanged()
                delay(REBIND_POLL_MS)
            }
        }
        NetLog.record("SERVICE_STARTED")
    }

    /**
     * The single owner of every RINGING side effect: sound, vibration, the CPU wake lock and the
     * lock-screen notification. Deliberately one observer rather than several so a future edit
     * cannot start the alarm's sound on one path and its notification on another.
     */
    private fun startAlarmObserver() {
        lockJob?.cancel()
        lockJob = scope.launch {
            AntiHideRuntime.stateMachine.state
                .map { it.alarmState }
                .distinctUntilChanged()
                .collect { alarm ->
                    if (alarm == AlarmState.RINGING) {
                        wakeLock?.acquire()
                        // Audio before the notification: if start() throws, the user still gets the
                        // tap-to-stop surface instead of an unstoppable silent lock screen.
                        audio.start()
                        notifier.showRinging(
                            AntiHideRuntime.stateMachine.currentState.ringStartedAtEpochMs
                                ?: System.currentTimeMillis(),
                        )
                    } else {
                        // Silence first, then tear the surfaces down: IDLE always unwinds fully.
                        runCatching { audio.stop() }
                        runCatching { notifier.clearRinging() }
                        runCatching { wakeLock?.release() }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCAL_STOP) {
            // Trust boundary 4: a locked-screen stop needs no session and no network.
            AntiHideRuntime.stopAlarmLocally()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        lockJob?.cancel()
        // Never leave the phone making noise, ringing, or holding a wake lock.
        if (::audio.isInitialized) audio.stop()
        if (::notifier.isInitialized) notifier.clearRinging()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        connectivityManager?.let { runCatching { it.unregisterNetworkCallback(vpnCallback) } }
        AntiHideRuntime.server.stop()
        scope.cancel()
        NetLog.record("SERVICE_STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentSignature(): String {
        val addresses = TailscaleAddressLocator.fromSystem()
            .joinToString(",") { it.hostAddress ?: "" }
            .ifEmpty { "none" }
        return "${AntiHideRuntime.vpnNetwork}|$addresses"
    }

    private fun rebindIfChanged() {
        val signature = currentSignature()
        if (signature != lastNetworkSignature) {
            lastNetworkSignature = signature
            NetLog.record("TUN0_SIGNATURE_CHANGED signature=$signature")
            AntiHideRuntime.server.rebind()
        }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AntiHideRuntime.vpnNetwork = network
            NetLog.record("VPN_NETWORK_AVAILABLE")
            AntiHideRuntime.server.rebind()
        }

        override fun onLost(network: Network) {
            if (AntiHideRuntime.vpnNetwork == network) AntiHideRuntime.vpnNetwork = null
            NetLog.record("VPN_NETWORK_LOST")
            AntiHideRuntime.server.rebind()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            NetLog.record("VPN_LINK_PROPERTIES_CHANGED")
            AntiHideRuntime.server.rebind()
        }
    }

    private fun registerVpnCallback() {
        val manager = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching { manager.registerNetworkCallback(request, vpnCallback) }
            .onFailure { NetLog.record("VPN_CALLBACK_UNAVAILABLE ${it.javaClass.simpleName}") }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // specialUse only: connectedDevice would require one of BLUETOOTH_*/NFC/USB, none of
            // which AntiHide holds, and Android 14+ rejects the FGS start without one.
            startForeground(
                NOTIFICATION_ID,
                notifier.buildServiceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notifier.buildServiceNotification())
        }
    }

    companion object {
        const val ACTION_LOCAL_STOP = "com.antihide.v2.action.LOCAL_STOP"
        private const val NOTIFICATION_ID = 1
        private const val REBIND_POLL_MS = 1_000L
    }
}

/** Restarts AntiHide after a reboot so the phone is findable without being unlocked first. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            context.startForegroundService(Intent(context, AntiHideForegroundService::class.java))
        }
    }
}