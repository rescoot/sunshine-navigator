package dev.ligustah.lsnav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
internal fun UncertainRecovery(state: NavigationState, model: NavigationViewModel) {
    val target = state.uncertainTarget
    if (target == null && !state.pendingCommandForOtherAccount) return
    var confirmAcknowledgement by remember { mutableStateOf(false) }
    UncertainPanel(state, model::checkUncertainCommand, model::dismissCheckedUncertainCommand, { confirmAcknowledgement = true })
    if (confirmAcknowledgement) AlertDialog(
        onDismissRequest = { confirmAcknowledgement = false },
        title = { Text("Confirm destination check") },
        text = { Text("Only continue if you manually checked the scooter's current destination in Sunshine. Sunshine Navigator will clear its pending request record; it will not send or retry a command.") },
        confirmButton = { TextButton(onClick = { confirmAcknowledgement = false; model.acknowledgePendingCommandCheckedOutsideApp() }) { Text("Clear after checking") } },
        dismissButton = { TextButton(onClick = { confirmAcknowledgement = false }) { Text("Cancel") } }
    )
}

@Composable
fun UncertainPanel(state: NavigationState, onCheck: () -> Unit = {}, onDismissChecked: () -> Unit = {}, onAcknowledge: () -> Unit = {}) {
    val target = state.uncertainTarget
    SunshineCard(panel = true, accent = androidx.compose.ui.graphics.Color(0xFFEAB308)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Not sure it arrived", style = MaterialTheme.typography.titleMedium)
            val checked = state.uncertainCheckedDestination
            when {
                checked != null -> {
                    val actual = checked.place
                    Text(if (actual == null) "The scooter currently has no destination." else "Current destination: ${actual.label} (${actual.coordinates.display()}).")
                    Text("Review this state before sending another request.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissChecked) { Text("Done checking") }
                }
                target == null -> Text("A previous request could not be verified with this connection. Reconnect the original connection to check it, or verify the scooter's destination in Sunshine before acknowledging this request.")
                else -> Text("${target.configuration.scooterName} may or may not have applied ${target.place?.label ?: "the navigation cancellation"}. Check its current destination before sending another request.")
            }
            state.uncertainCheckError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (target != null && checked == null) {
                SunshineButton(onClick = onCheck, enabled = !state.checkingUncertain && !state.pendingCommandLoading) {
                    Text(if (state.checkingUncertain) "Checking destination…" else "Check scooter")
                }
            }
            TextButton(onClick = onAcknowledge, enabled = !state.checkingUncertain && !state.pendingCommandLoading) {
                Text("I'll check myself")
            }
        }
    }
}
