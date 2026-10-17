package com.antihide.v2.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One readiness probe plus the action that fixes it. */
data class ReadinessItem(
    val label: String,
    val granted: Boolean,
    val requiredFor: String,
    val actionLabel: String,
    val action: Intent?,
)

/**
 * Collects the three OS grants AntiHide needs to survive ColorOS background restrictions, plus the
 * manual steps Android has no API for. Pure function over the managers so it can be unit tested.
 */
fun collectReadiness(context: Context): List<ReadinessItem> {
    val pkg = context.packageName
    val items = mutableListOf<ReadinessItem>()

    val power = context.getSystemService(PowerManager::class.java)
    val ignoringBattery = power?.isIgnoringBatteryOptimizations(pkg) == true
    items += ReadinessItem(
        label = "Battery optimisation exemption",
        granted = ignoringBattery,
        requiredFor = "keeps the alarm reachable when the screen is off",
        actionLabel = "Request",
        action = if (ignoringBattery) null else Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$pkg"),
        ),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val granted = notifications?.areNotificationsEnabled() == true
        items += ReadinessItem(
            label = "Notification permission",
            granted = granted,
            requiredFor = "lock-screen STOP ALARM button",
            actionLabel = "Open settings",
            action = if (granted) null else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val granted = notifications?.canUseFullScreenIntent() == true
        items += ReadinessItem(
            label = "Full-screen intent",
            granted = granted,
            requiredFor = "wakes the lock screen when RING starts",
            actionLabel = "Open settings",
            action = if (granted) null else Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        )
    }

    return items
}

/** OPPO/ColorOS steps that have no API and must be done by hand in system settings. */
val COLOR_OS_MANUAL_STEPS = listOf(
    "Settings -> Apps -> App management -> AntiHide V2 -> Battery usage -> Allow background activity (ON)",
    "Recents screen -> swipe down on the AntiHide V2 card (or tap the lock icon) to pin it",
    "Settings -> Battery -> App battery management -> AntiHide V2 -> Don't allow auto-sleep",
)

@Composable
fun ColorOsReadinessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items = collectReadiness(context)
    val allGranted = items.all { it.granted }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (allGranted) "OPPO ColorOS readiness: READY" else "OPPO ColorOS readiness: ACTION NEEDED",
                style = MaterialTheme.typography.titleMedium,
                color = if (allGranted) Color(0xFF2E7D32) else Color(0xFFE65100),
            )
            items.forEach { item ->
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            (if (item.granted) "[OK] " else "[WARN] ") + item.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (item.granted) {
                            Text("granted", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                        }
                    }
                    Text("   needed for: ${item.requiredFor}", style = MaterialTheme.typography.labelSmall)
                    if (!item.granted) {
                        OutlinedButton(
                            onClick = { item.action?.let { context.startActivity(it) } },
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text(item.actionLabel) }
                    }
                }
            }
            Text("Manual ColorOS steps (no API):", style = MaterialTheme.typography.labelLarge)
            COLOR_OS_MANUAL_STEPS.forEach {
                Text("- $it", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Open app info") }
        }
    }
}