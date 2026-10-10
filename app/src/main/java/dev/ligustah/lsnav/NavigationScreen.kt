package dev.ligustah.lsnav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NavigationScreen(
    onSettings: () -> Unit,
    onDone: () -> Unit = {},
    sharedText: String? = null,
    onShareConsumed: () -> Unit = {},
    model: NavigationViewModel = viewModel()
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val destinationSaver = remember { Saver<PlaceResult?, Any>(save = { place ->
        place?.let { mapOf("latitude" to it.coordinates.latitude, "longitude" to it.coordinates.longitude, "label" to it.label) }
    }, restore = { value ->
        val fields = value as? Map<*, *>
        val latitude = fields?.get("latitude") as? Double
        val longitude = fields?.get("longitude") as? Double
        val label = fields?.get("label") as? String
        if (latitude != null && longitude != null && label != null) PlaceResult(Coordinates(latitude, longitude), label) else null
    }) }
    var destination by rememberSaveable(stateSaver = destinationSaver) { mutableStateOf<PlaceResult?>(null) }
    var favoriteDraftPlace by remember { mutableStateOf<PlaceResult?>(null) }
    var favoriteDraftId by remember { mutableStateOf<Long?>(null) }
    var favoriteLabel by remember { mutableStateOf("") }
    var favoriteSaveStarted by remember { mutableStateOf(false) }
    var clearWarning by remember { mutableStateOf(false) }
    var sendingTarget by remember { mutableStateOf<CommandTarget?>(null) }
    var uncertainNeedsCheck by remember { mutableStateOf(false) }
    var uncertainCheckStartedAt by remember { mutableStateOf<Long?>(null) }
    val uncertainTarget = (state.lastCommandOutcome as? CommandOutcome.DeliveryUncertain)?.target
    LaunchedEffect(uncertainTarget) {
        if (uncertainTarget != null) { uncertainNeedsCheck = true; uncertainCheckStartedAt = null }
    }
    LaunchedEffect(state.destination.updatedAt, state.destination.loading, uncertainCheckStartedAt) {
        val startedAt = uncertainCheckStartedAt
        if (startedAt != null && !state.destination.loading && state.destination.updatedAt > startedAt) uncertainNeedsCheck = false
    }

    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshIfStale() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sharedText, state.sending) { if (!sharedText.isNullOrBlank() && !state.sending) model.resolveIncoming(sharedText) }
    LaunchedEffect(state.results, state.resolvedIncomingText, sharedText) {
        if (!sharedText.isNullOrBlank() && state.resolvedIncomingText == sharedText && state.results.size == 1) {
            destination = state.results.single()
            onShareConsumed()
        }
    }
    LaunchedEffect(state.favoriteEditor) {
        if (favoriteSaveStarted && !state.favoriteEditor.saving && state.favoriteEditor.draft == null && state.favoriteEditor.error == null) {
            favoriteDraftPlace = null
            favoriteSaveStarted = false
        }
    }
    BackHandler(destination != null) { destination = null }

    if (destination != null) {
        val place = destination!!
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { destination = null }) { Text("‹  Places") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Account and settings") }
            }
            Text("Send place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(place.label, style = MaterialTheme.typography.titleLarge)
                Text(place.coordinates.display(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap a scooter to send this place", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { destination = null }) { Text("Choose a different place") }
                    TextButton(onClick = { favoriteDraftPlace = place; favoriteDraftId = null; favoriteLabel = place.label }) { Text("Save favorite") }
                }
            } }
            if (state.configuration.isReady) {
                state.destination.value?.place?.let { current ->
                    Text("Current route for ${state.configuration.scooterName}: ${current.label}. Sending a place to that scooter replaces its current destination.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                !state.configuration.isAuthenticated -> RecoveryCard("Connect Sunshine to choose a scooter. This place will stay here.", "Connect Sunshine", onSettings)
                state.configuration.needsPermissionUpgrade && state.configuration.missingOAuthScopes.contains("scooter_control") -> RecoveryCard("Reconnect and approve scooter navigation access to send a place.", "Reconnect", onSettings)
                state.scooters.loading && state.scooters.value == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.scooters.error != null && state.scooters.value == null -> RecoveryCard("${state.scooters.error}. Your place is safe.", "Refresh scooters", { model.fetchScooters(true) })
                state.scooters.value.isNullOrEmpty() -> RecoveryCard("No scooters are available to this account. Check Sunshine access, then refresh.", "Refresh scooters", { model.fetchScooters(true) })
                else -> ScooterPicker(state.configuration, state.scooters.value.orEmpty(), !state.sending && !uncertainNeedsCheck,
                    onSelect = { scooter ->
                        model.selectScooter(scooter)
                        val captured = state.configuration.copy(scooterId = scooter.id, scooterName = scooter.name)
                        sendingTarget = CommandTarget(captured, place)
                        model.sendToScooter(CommandTarget(captured, place))
                    }, placeLabel = place.label)
            }
            if (state.sending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Sending to ${sendingTarget?.configuration?.scooterName.orEmpty()}…") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (val outcome = state.lastCommandOutcome?.takeIf { result ->
                val target = when (result) {
                    is CommandOutcome.ServerAccepted -> result.target
                    is CommandOutcome.DeliveryUncertain -> result.target
                }
                target.configuration == state.configuration && target.place == place
            }) {
                is CommandOutcome.ServerAccepted -> ResultCard("Request accepted", "Sunshine accepted this destination. Scooter receipt is not confirmed.", onDone)
                is CommandOutcome.DeliveryUncertain -> Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Check destination before retrying", style = MaterialTheme.typography.titleMedium)
                    Text("${outcome.target.configuration.scooterName} may have received this destination. A successful server response is not scooter confirmation.")
                    if (!uncertainNeedsCheck) Text("Latest destination: ${state.destination.value?.place?.label ?: "No active destination"}")
                    TextButton(onClick = {
                        val scooter = state.scooters.value.orEmpty().firstOrNull { it.id == outcome.target.configuration.scooterId }
                        if (scooter != null && outcome.target.configuration.accountKey == state.configuration.accountKey) {
                            model.selectScooter(scooter)
                            uncertainCheckStartedAt = System.currentTimeMillis()
                            model.refresh()
                        }
                    }, enabled = !state.sending && !state.destination.loading && outcome.target.configuration.accountKey == state.configuration.accountKey) { Text(if (uncertainNeedsCheck) "Check current destination" else "Check again") }
                    TextButton(onClick = onDone) { Text("Done") }
                } }
                else -> Unit
            }
        }
    } else {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Navigate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text("A place for your scooter") }
                Row {
                    IconButton(onClick = model::refresh, enabled = state.configuration.isAuthenticated && !state.sending) { Icon(Icons.Default.Refresh, "Refresh places and scooters") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Account and settings") }
                }
            }
            if (!state.configuration.isAuthenticated) {
                Card { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your next stop, simply.", style = MaterialTheme.typography.headlineSmall)
                    Text("Connect Sunshine to see your scooters and send a destination. Nothing is sent until you tap a scooter.")
                    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
                } }
            } else {
                if (state.configuration.needsPermissionUpgrade) Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reconnect to finish access", style = MaterialTheme.typography.titleMedium)
                    Text("This session is missing ${state.configuration.missingOAuthScopes.joinToString()} access. Reconnect only when you need that capability.")
                    OutlinedButton(onClick = onSettings) { Text("Review connection") }
                } }
                OutlinedTextField(value = state.query, onValueChange = model::updateQuery, label = { Text("Search or paste a map link") },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.sending, singleLine = true)
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.results.forEach { place -> PlaceRow(place.label, place.coordinates.display(), onClick = { destination = place; if (!sharedText.isNullOrBlank()) onShareConsumed() }) }

                val personal = state.personalPlaces
                if (personal.loading && personal.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                ResourceFeedback(personal.error, personal.value != null, { model.refreshPersonalPlaces(true) })
                personal.value?.let { saved ->
                    if (saved.favorites.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Your favorites", style = MaterialTheme.typography.titleLarge)
                        }
                        saved.favorites.forEach { row -> LibraryRow(row, "Favorite", { destination = row.place }, { favoriteDraftPlace = row.place; favoriteDraftId = row.id; favoriteLabel = row.place.label }) }
                    }
                    if (saved.recent.isNotEmpty()) {
                        Text("Recent places", style = MaterialTheme.typography.titleLarge)
                        saved.recent.forEach { row -> LibraryRow(row, "Recent", { destination = row.place }, null) }
                    }
                    if (saved.favorites.isEmpty() && saved.recent.isEmpty()) Text("Places you save and use will appear here.")
                }
                if (state.configuration.isReady) {
                    Text("${state.configuration.scooterName}'s places", style = MaterialTheme.typography.titleLarge)
                    val locations = state.savedLocations
                    ResourceFeedback(locations.error, locations.value != null, { model.refresh() })
                    locations.value.orEmpty().forEach { place -> PlaceRow(place.label, place.coordinates.display(), { destination = place }) }
                    val current = state.destination.value?.place
                    Text("Current destination", style = MaterialTheme.typography.titleLarge)
                    if (state.destination.loading && state.destination.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    ResourceFeedback(state.destination.error, state.destination.value != null, { model.refresh() })
                    if (current == null) Text("No active destination") else Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.label, style = MaterialTheme.typography.titleMedium)
                        Text(current.coordinates.display(), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { clearWarning = true }, enabled = !state.sending) { Text("Cancel navigation") }
                    } }
                }
            }
        }
    }

    favoriteDraftPlace?.let { place ->
        AlertDialog(onDismissRequest = { favoriteDraftPlace = null }, title = { Text(if (favoriteDraftId == null) "Save favorite" else "Edit favorite") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(favoriteLabel, { favoriteLabel = it }, label = { Text("Name") }, singleLine = true)
                Text(place.coordinates.display(), style = MaterialTheme.typography.bodySmall)
                if (favoriteDraftId != null) TextButton(onClick = {
                    val saved = state.personalPlaces.value?.favorites?.firstOrNull { it.id == favoriteDraftId }
                    if (saved != null) model.setFavorite(saved, false)
                    favoriteDraftPlace = null
                }) { Text("Remove favorite") }
                state.favoriteEditor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.configuration.favoritePermission == PermissionCapability.UpgradeRequired) TextButton(onClick = onSettings) { Text("Reconnect to enable favorite editing") }
            } },
            confirmButton = { TextButton(onClick = {
                model.editFavorite(favoriteDraftId, FavoriteDraft(favoriteLabel.trim(), place))
                model.saveFavorite()
                favoriteSaveStarted = true
            }, enabled = favoriteLabel.isNotBlank() && !state.favoriteEditor.saving) { Text("Save") } },
            dismissButton = { TextButton(onClick = { favoriteDraftPlace = null }) { Text("Cancel") } })
    }
    if (clearWarning) AlertDialog(onDismissRequest = { clearWarning = false }, title = { Text("Cancel navigation?") },
        text = { Text("This clears the current destination for ${state.configuration.scooterName}. It does not lock the scooter.") },
        confirmButton = { TextButton(onClick = { clearWarning = false; model.send(null, state.configuration) }) { Text("Cancel navigation") } },
        dismissButton = { TextButton(onClick = { clearWarning = false }) { Text("Keep destination") } })
}

@Composable
private fun LibraryRow(place: SavedPlace, badge: String, onClick: () -> Unit, onManage: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(place.place.label, style = MaterialTheme.typography.titleMedium)
                Text(place.place.coordinates.display(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (onManage != null) TextButton(onClick = onManage) { Text("Edit") }
        }
    }
}

@Composable
private fun PlaceRow(title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
private fun RecoveryCard(message: String, action: String, onAction: () -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge); OutlinedButton(onClick = onAction) { Text(action) }
    } }
}

@Composable
private fun ResultCard(title: String, message: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(message)
            TextButton(onClick = onAction) { Text("Done") }
        }
    }
}

@Composable
private fun ResourceFeedback(error: String?, hasValue: Boolean, retry: () -> Unit) {
    if (error != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (hasValue) "Showing saved data · $error" else error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = retry) { Text("Retry") }
    }
}
