package com.antihide.v2.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.antihide.v2.auth.PairingWindow
import com.antihide.v2.domain.AlarmState
import com.antihide.v2.net.NetLog
import com.antihide.v2.service.AntiHideForegroundService
import com.antihide.v2.service.AntiHideRuntime
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AntiHideRuntime.install(this)
        startService(Intent(this, AntiHideForegroundService::class.java))
        requestNotificationPermissionIfNeeded()
        setContent { AntiHideScreen() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun AntiHideScreen() {
    val server = remember { AntiHideRuntime.server }
    val machine = remember { AntiHideRuntime.stateMachine }
    val alarmState by machine.state.collectAsState()
    val serverState by server.state.collectAsState()
    val boundAddresses by server.boundAddresses.collectAsState()
    val lastError by server.lastError.collectAsState()
    val pairingWindow by server.auth.pairingWindow.collectAsState()
    val events by NetLog.events.collectAsState()
    var sessionCount by remember { mutableLongStateOf(0L) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            sessionCount = server.auth.activeSessionCount().toLong()
            delay(500)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("AntiHide V2 — Slice 2", style = MaterialTheme.typography.headlineSmall)
                Text("State machine, alarm playback and remote/local stop")

                // Alarm badge: the single most important thing on the screen.
                val ringing = alarmState.alarmState == AlarmState.RINGING
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (ringing) Color(0xFFB00020) else Color(0xFF1B5E20),
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "ALARM STATE: ${alarmState.alarmState}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                        Text(
                            "stateVersion ${alarmState.stateVersion}   stopSource ${alarmState.lastStopSource ?: "-"}",
                            color = Color.White,
                        )
                        Button(
                            onClick = { AntiHideRuntime.stopAlarmLocally() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF5252),
                                contentColor = Color.Black,
                            ),
                        ) {
                            Text("STOP ALARM (LOCAL)", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Server state: ${serverState.name}")
                        Text("Port: ${server.port}   Loopback allowed: ${server.allowLoopbackForLocalTests}")
                        Text("Bound tun0 addresses:")
                        if (boundAddresses.isEmpty()) {
                            Text("  none yet — waiting for Tailscale")
                        } else {
                            boundAddresses.forEach { Text("  $it") }
                        }
                        lastError?.let { Text("Last bind error: $it") }
                        Text("SSE clients: ${server.sseSubscriberCount}")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paired session slots: $sessionCount / ${server.auth.maxConcurrentSessions}")
                        when (val window = pairingWindow) {
                            is PairingWindow.Closed -> Text("Pairing window: closed")
                            is PairingWindow.LockedOut -> Text("Pairing window: locked out after ${server.auth.maxFailedAttempts} failed attempts")
                            is PairingWindow.Open -> {
                                val remaining = ((window.expiresAtEpochMs - now) / 1000).coerceAtLeast(0)
                                Text("Pairing code: ${window.code}")
                                Text("Expires in ${remaining}s   Failed attempts: ${window.failedAttempts}/${server.auth.maxFailedAttempts}")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { server.openPairingWindow() }) {
                                Text("Open 120s Pairing Window")
                            }
                            OutlinedButton(onClick = { server.revokeAllSessions() }) {
                                Text("Revoke All Sessions")
                            }
                        }
                    }
                }

                ColorOsReadinessCard()

                Text("Last ${NetLog.MAX_EVENTS} admission / auth events", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    items(events) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}