package dev.ligustah.lsnav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!onboarding) TextButton(onClick = onBack) { Text("‹  Back") }
        if (!sharedText.isNullOrBlank()) Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Shared place", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(sharedText, style = MaterialTheme.typography.bodyLarge)
            Text("Connect to continue. Nothing is sent during setup.", style = MaterialTheme.typography.bodySmall)
        } }
        Text(if (onboarding && authenticated) "Meet your scooters" else if (authenticated) "Account & preferences" else "Welcome to LSNav", style = MaterialTheme.typography.headlineMedium)
        if (!authenticated) {
            Card { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("A simple way to send a place to your scooter.", style = MaterialTheme.typography.titleLarge)
                Text("Sign in securely with Sunshine. Your account and vehicle access stay with Sunshine; nothing is sent during setup.")
                Button(onClick = { if (model.beginLogin()) onSignIn(state.draft.baseUrl, state.draft.oauthClientId) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Continue with Sunshine") }
            } }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Advanced connection options") }
            if (advanced) ConnectionFields(state, model)
        } else {
            if (onboarding) Text("Choose a default, or choose a scooter each time. This is only a preference—no destination is sent.")
            else {
                Text("Connected to ${state.profile.configuration.baseUrl}")
                if (state.profile.configuration.needsPermissionUpgrade) Text("This session may need renewed access for scooter navigation or favorite management.")
                TextButton(onClick = { editConnection = !editConnection }) { Text(if (editConnection) "Hide connection options" else "Change connection or reconnect") }
                if (editConnection) ConnectionFields(state, model, reconnect = { if (model.beginLogin()) onSignIn(state.draft.baseUrl, state.draft.oauthClientId) })
            }
            if (state.scooters.loading && state.scooters.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.scooters.error?.let { message -> Row(horizontalArrangement = Arrangement.SpaceBetween) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)); TextButton(onClick = { model.fetch(true) }) { Text("Retry") }
            } }
            if (state.scooters.value.isNullOrEmpty() && !state.scooters.loading) {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No scooters available", style = MaterialTheme.typography.titleMedium)
                    Text("Check your Sunshine account and scooter access, then refresh. You can still save a shared place for later.")
                    OutlinedButton(onClick = { model.fetch(true) }) { Text("Refresh scooters") }
                } }
            }
            state.scooters.value.orEmpty().forEach { scooter ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.select(scooter); if (onboarding) model.save() }, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScooterArt(scooter, Modifier.size(88.dp))
                        Column(Modifier.weight(1f)) { Text(scooter.name, style = MaterialTheme.typography.titleMedium); Text(if (scooter.online == true) "Online" else "Offline; preference only") }
                        if (scooter.id == state.draft.scooterId) Text("DEFAULT", color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!onboarding) TextButton(onClick = { model.select(scooter) }) { Text("Set ${scooter.name} as default") }
            }
            Card(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.chooseOnDemand(); if (onboarding) model.save() }, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choose each time", style = MaterialTheme.typography.titleMedium)
                    Text("Always show the scooter grid before sending.")
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
