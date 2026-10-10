package dev.ligustah.lsnav

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun UncertainRecovery(state: NavigationState, model: NavigationViewModel, onReconnect: () -> Unit = {}, docked: Boolean = false) {
    val target = state.uncertainTarget
    if (target == null && !state.pendingCommandForOtherAccount) return
    var confirmAcknowledgement by remember { mutableStateOf(false) }
    UncertainPanel(state, model::checkUncertainCommand, model::dismissCheckedUncertainCommand, { confirmAcknowledgement = true }, onReconnect, docked)
    if (confirmAcknowledgement) AlertDialog(
        onDismissRequest = { confirmAcknowledgement = false },
        title = { Text("Confirm destination check") },
        text = { Text("Only continue if you manually checked the scooter's current destination in Sunshine. Sunshine Navigator will clear its pending request record; it will not send or retry a command.") },
        confirmButton = { TextButton(onClick = { confirmAcknowledgement = false; model.acknowledgePendingCommandCheckedOutsideApp() }) { Text("Clear after checking") } },
        dismissButton = { TextButton(onClick = { confirmAcknowledgement = false }) { Text("Cancel") } }
    )
}

@Composable
fun UncertainPanel(state: NavigationState, onCheck: () -> Unit = {}, onDismissChecked: () -> Unit = {}, onAcknowledge: () -> Unit = {}, onReconnect: () -> Unit = {}, docked: Boolean = false) {
    val target = state.uncertainTarget
    val rejected = target != null && state.error != null && state.lastCommandOutcome !is CommandOutcome.DeliveryUncertain
    val reconnect = rejected && state.configuration.oauthSessionId != null &&
        (state.configuration.needsPermissionUpgrade || listOf("login", "token", "permission", "reconnect").any { state.error!!.contains(it, true) })
    val dark = MaterialTheme.colorScheme.background == androidx.compose.ui.graphics.Color(0xFF0F0E1F)
    val panelColor = androidx.compose.ui.graphics.Color(if (rejected) { if (dark) 0xFF331E2B else 0xFFFEF2F2 } else { if (dark) 0xFF29251F else 0xFFFEFCE8 })
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (docked) 0.dp else 16.dp, vertical = if (docked) 8.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { ResultIcon(if (rejected) BadgeTone.Error else BadgeTone.Warning); Text(if (rejected) "Couldn't send to ${target!!.configuration.scooterName}" else "Not sure it arrived", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (rejected) Text(state.error!!, color = MaterialTheme.colorScheme.error)
            val checked = state.uncertainCheckedDestination
            when {
                checked != null -> {
                    val actual = checked.place
                    Text(if (actual == null) "The scooter currently has no destination." else "Current destination: ${actual.label} (${actual.coordinates.display()}).")
                    Text("Review this state before sending another request.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissChecked) { Text("Done checking") }
                }
                target == null -> Text("A previous request could not be verified with this connection. Reconnect the original connection to check it, or verify the scooter's destination in Sunshine before acknowledging this request.")
                rejected -> Text("Check ${target.configuration.scooterName}'s destination before sending another request.", style = MaterialTheme.typography.bodySmall)
                else -> Text("${target.configuration.scooterName} may or may not have applied ${target.place?.label ?: "the navigation cancellation"}. Check its current destination before sending another request.")
            }
            state.uncertainCheckError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (target != null && checked == null) {
                SunshineButton(onClick = onCheck, enabled = !state.checkingUncertain && !state.pendingCommandLoading) {
                    Text(if (state.checkingUncertain) "Checking destination…" else "Check scooter")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reconnect) TextButton(onClick = onReconnect, enabled = !state.checkingUncertain && !state.pendingCommandLoading) { Text("Reconnect") }
                TextButton(onClick = onAcknowledge, enabled = !state.checkingUncertain && !state.pendingCommandLoading) { Text("I'll check myself") }
            }
        }
    }
    if (docked) body() else SunshineCard(panel = true, accent = if (rejected) androidx.compose.ui.graphics.Color(0xFFEF4444) else androidx.compose.ui.graphics.Color(0xFFEAB308), containerColor = panelColor) { body() }
}
