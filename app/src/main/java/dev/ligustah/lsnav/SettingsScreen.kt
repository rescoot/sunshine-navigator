package dev.ligustah.lsnav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(model: SettingsViewModel = viewModel(), onSignIn: (String, String) -> Unit = { _, _ -> }, onBack: () -> Unit = {}, sharedText: String? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editConnection by rememberSaveable { mutableStateOf(false) }
    val authenticated = state.profile.configuration.isAuthenticated
    val onboarding = !state.profile.onboardingComplete
    val configuration = state.profile.configuration
    val reconnect = { if (model.beginLogin()) onSignIn(configuration.baseUrl, configuration.oauthClientId) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!onboarding) TextButton(onClick = onBack) { Text("‹  Back") }
        BrandHeader()
        SharedPlacePreviewCard(sharedText, "Connect to continue. Nothing is sent during setup.")
        when {
            onboarding && authenticated -> Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Meet your scooters", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Choose a default, or choose a scooter each time. This is only a preference—no destination is sent.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            authenticated -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Account & preferences", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Connected to ${configuration.baseUrl}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (configuration.needsPermissionUpgrade) Text("This session may need renewed access for scooter navigation or favorite management.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { editConnection = !editConnection }) { Text(if (editConnection) "Hide connection options" else "Change connection or reconnect") }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Welcome to LSNav", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Connect Sunshine to see your scooters and choose a destination.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!authenticated) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your next stop, simply.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Sign in securely with Sunshine. Your account and vehicle access stay with Sunshine; nothing is sent during setup.")
                    Button(onClick = { if (model.beginLogin()) onSignIn(state.draft.baseUrl, state.draft.oauthClientId) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Continue with Sunshine") }
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Advanced connection options") }
            if (advanced) ConnectionFields(state, model)
        } else {
            val reconnectField = if (configuration.oauthSessionId != null) reconnect else null
            if (editConnection) ConnectionFields(state, model, reconnect = reconnectField)
            if (state.scooters.loading && state.scooters.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.scooters.error?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scooters couldn't be loaded", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.scooterReconnectRequired) {
                                Button(onClick = reconnect, enabled = !state.busy) { Text("Reconnect with Sunshine") }
                                TextButton(onClick = { model.fetch(true) }, enabled = !state.busy) { Text("Retry") }
                            } else {
                                Button(onClick = { model.fetch(true) }, enabled = !state.busy) { Text("Retry") }
                                TextButton(onClick = { editConnection = true }) { Text("Connection options") }
                            }
                        }
                    }
                }
            }
            if (!state.scooters.loading && state.scooters.error == null && state.scooters.value?.isEmpty() == true) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No scooters available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Sunshine returned an empty scooter list. Check account sharing if you expected a vehicle.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { model.fetch(true) }, enabled = !state.busy) { Text("Refresh scooters") }
                    }
                }
            }
            state.scooters.value.orEmpty().forEach { scooter ->
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.select(scooter); if (onboarding) model.save() },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScooterArt(scooter, Modifier.size(92.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(scooter.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (scooter.online == true) "Online" else "Offline · preference only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (scooter.id == state.draft.scooterId) Text("DEFAULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (!onboarding) TextButton(onClick = { model.select(scooter) }) { Text("Set ${scooter.name} as default") }
            }
            Card(
                Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.chooseOnDemand(); if (onboarding) model.save() },
                shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choose each time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Always show the scooter grid before sending.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!onboarding) Button(onClick = model::save, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save preference") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!onboarding) TextButton(onClick = model::signOut, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ConnectionFields(state: SettingsState, model: SettingsViewModel, reconnect: (() -> Unit)? = null) {
    OutlinedTextField(state.draft.baseUrl, model::updateUrl, label = { Text("Sunshine server (HTTPS)") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state.draft.oauthClientId, model::updateClientId, label = { Text("Public OAuth client ID") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (reconnect != null) Button(onClick = reconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Reconnect with Sunshine") }
    HorizontalDivider()
    Text("Manual token", style = MaterialTheme.typography.titleMedium)
    Text("Use a token from a trusted Sunshine server. It stays in this app's private, non-backed-up settings storage.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(state.draft.token, model::updateToken, label = { Text("API token") }, visualTransformation = PasswordVisualTransformation(),
        enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = model::connectManual, enabled = !state.busy && state.draft.token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect with token") }
}
