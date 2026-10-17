package com.antihide.v2.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antihide.v2.domain.AlarmState
import com.antihide.v2.service.AntiHideForegroundService
import com.antihide.v2.service.AntiHideRuntime

/**
 * Lock-screen STOP surface #2.
 *
 * SECURITY (Trust Boundary 4): this activity deliberately exposes exactly one action — STOP. It
 * renders no pairing code, no session list and no settings, so anyone holding a locked phone can
 * silence the alarm but cannot reach the pairing window or revoke sessions.
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()

        // Bounce straight to the service so a volume-key stop works even if this UI never showed.
        startService(Intent(this, AntiHideForegroundService::class.java))

        setContent {
            val machine = AntiHideRuntime.stateMachine
            val state by machine.state.collectAsStateWithLifecycle()
            val ringing = state.alarmState == AlarmState.RINGING

            // Finish as soon as the alarm stops, however it was stopped.
            LaunchedEffect(ringing) {
                if (!ringing) finish()
            }

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (ringing) Color(0xFFB00020) else Color(0xFF1B5E20))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (ringing) "RINGING" else "IDLE",
                        color = Color.White,
                        fontSize = 56.sp,
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        "Ringing for ${((System.currentTimeMillis() - (state.ringStartedAtEpochMs ?: 0L)) / 1000).coerceAtLeast(0)} s",
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                    Text(
                        "Press STOP, or either volume key",
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = { AntiHideRuntime.stopAlarmLocally() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp)
                            .height(140.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.Black),
                    ) {
                        Text("STOP ALARM", fontSize = 40.sp)
                    }
                }
            }
        }
    }

    private fun showWhenLockedAndTurnScreenOn() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Both volume keys stop the alarm: the fastest possible gesture on a ringing phone. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
            AntiHideRuntime.stopAlarmLocally()
            finish()
            true
        }

        else -> super.onKeyDown(keyCode, event)
    }

    companion object {
        /** True when the device is locked; used by tests to assert the trust boundary. */
        fun isDeviceLocked(context: android.content.Context): Boolean {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return keyguard?.isDeviceLocked == true
        }
    }
}