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
    var destinationOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var destinationIsExternal by rememberSaveable { mutableStateOf(false) }
    var destinationSavedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var favoriteDraftPlace by rememberSaveable(stateSaver = destinationSaver) { mutableStateOf<PlaceResult?>(null) }
    var favoriteDraftId by rememberSaveable { mutableStateOf<Long?>(null) }
    var favoriteDraftIsFavorite by rememberSaveable { mutableStateOf(false) }
    var favoriteDraftOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var favoriteLabel by rememberSaveable { mutableStateOf("") }
    var favoriteSaveStartSequence by rememberSaveable { mutableStateOf(state.favoriteMutationSequence) }
    var deleteFavoritePending by remember { mutableStateOf<SavedPlace?>(null) }
    var showBrowseVehicles by rememberSaveable { mutableStateOf(false) }
    var clearWarning by remember { mutableStateOf(false) }
    var sendingTarget by remember { mutableStateOf<CommandTarget?>(null) }
    val ownerFingerprint = state.configuration.accountFingerprint()
    val visibleDestination = destination?.takeIf { destinationIsExternal || (state.configurationLoaded && destinationOwner == ownerFingerprint) }
    val visibleFavoriteDraft = favoriteDraftPlace?.takeIf { state.configurationLoaded && favoriteDraftOwner == ownerFingerprint }
    val openPlace: (PlaceResult, Long?) -> Unit = { place, savedId ->
        destination = place
        destinationOwner = ownerFingerprint
        destinationIsExternal = false
        destinationSavedId = savedId
    }
    val clearPlace = {
        destination = null
        destinationOwner = null
        destinationIsExternal = false
        destinationSavedId = null
    }
    val openFavoriteEditor: (Long?, PlaceResult, String) -> Unit = { id, place, label ->
        favoriteDraftPlace = place
        favoriteDraftId = id
        favoriteDraftIsFavorite = id != null && state.personalPlaces.value?.favorites.orEmpty().any { it.id == id }
        favoriteDraftOwner = ownerFingerprint
        favoriteLabel = label
        favoriteSaveStartSequence = state.favoriteMutationSequence
        model.editFavorite(id, FavoriteDraft(label, place))
    }
    LaunchedEffect(state.configurationLoaded, ownerFingerprint) {
        if (!state.configurationLoaded) return@LaunchedEffect
        if (destinationOwner != null && destinationOwner != ownerFingerprint && !destinationIsExternal) clearPlace()
        if (favoriteDraftOwner != null && favoriteDraftOwner != ownerFingerprint) {
            favoriteDraftPlace = null; favoriteDraftId = null; favoriteDraftIsFavorite = false; favoriteDraftOwner = null
            model.editFavorite(null, null)
        }
    }
    LaunchedEffect(favoriteDraftPlace, favoriteDraftId, favoriteDraftOwner) {
        if (favoriteDraftPlace != null && favoriteDraftOwner == ownerFingerprint && !state.favoriteEditor.saving) {
            model.editFavorite(favoriteDraftId, FavoriteDraft(favoriteLabel, favoriteDraftPlace!!))
        }
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
            destinationOwner = null
            destinationIsExternal = true
            destinationSavedId = null
            onShareConsumed()
        }
    }
    LaunchedEffect(state.favoriteMutationSequence) {
        if (favoriteDraftPlace != null && state.favoriteMutationSequence > favoriteSaveStartSequence) favoriteDraftPlace = null
    }
    BackHandler(destination != null) { clearPlace() }

    if (visibleDestination != null) {
        val place = visibleDestination
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UncertainRecovery(state, model)
            if (state.pendingCommandLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Restoring previous request status…") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = clearPlace) { Text("‹  Places") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Account and settings") }
            }
            Text("Send place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(place.label, style = MaterialTheme.typography.titleLarge)
                Text(place.coordinates.display(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap a scooter to send this place", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = clearPlace) { Text("Choose a different place") }
                    TextButton(onClick = {
                        val existing = state.personalPlaces.value?.let { it.favorites + it.recent }?.firstOrNull {
                            it.id == destinationSavedId || it.place.coordinates == place.coordinates
                        }
                        openFavoriteEditor(existing?.id, place, place.label)
                    }) { Text(if (destinationSavedId == null) "Save favorite" else "Edit saved place") }
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
                else -> ScooterPicker(state.scooters.value.orEmpty(), !state.sending && (state.uncertainTarget == null || state.uncertainCheckedDestination != null) && !state.pendingCommandLoading && !state.pendingCommandForOtherAccount, state.defaultScooterId,
                    onSelect = { scooter ->
                        model.selectScooter(scooter)
                        val captured = state.configuration.copy(scooterId = scooter.id, scooterName = scooter.name)
                        sendingTarget = CommandTarget(captured, place)
                        model.sendToScooter(CommandTarget(captured, place))
                    }, placeLabel = place.label)
            }
            if (state.sending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Sending to ${sendingTarget?.configuration?.scooterName.orEmpty()}…") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (state.lastCommandOutcome?.takeIf { result ->
                val target = when (result) {
                    is CommandOutcome.ServerAccepted -> result.target
                    is CommandOutcome.DeliveryUncertain -> result.target
                }
                target.configuration == state.configuration && target.place == place
            }) {
                is CommandOutcome.ServerAccepted -> ResultCard("Request accepted", "Sunshine accepted this destination. Scooter receipt is not confirmed.") { clearPlace(); onDone() }
                is CommandOutcome.DeliveryUncertain -> Unit
                else -> Unit
            }
        }
    } else {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UncertainRecovery(state, model)
            if (state.pendingCommandLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Restoring previous request status…") }
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
                state.results.forEach { place -> PlaceRow(place.label, place.coordinates.display(), onClick = { openPlace(place, null); if (!sharedText.isNullOrBlank()) onShareConsumed() }) }

                val personal = state.personalPlaces
                if (personal.loading && personal.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                ResourceFeedback(personal.error, personal.value != null, { model.refreshPersonalPlaces(true) })
                personal.value?.let { saved ->
                    if (saved.favorites.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Your favorites", style = MaterialTheme.typography.titleLarge)
                        }
                        saved.favorites.forEach { row -> LibraryRow(row, "Favorite", { openPlace(row.place, row.id) }, { openFavoriteEditor(row.id, row.place, row.place.label) }) }
                    }
                    if (saved.recent.isNotEmpty()) {
                        Text("Recent places", style = MaterialTheme.typography.titleLarge)
                        saved.recent.forEach { row -> LibraryRow(row, "Recent", { openPlace(row.place, row.id) }, null) }
                    }
                    if (saved.favorites.isEmpty() && saved.recent.isEmpty()) Text("Places you save and use will appear here.")
                }
                if (state.scooters.value.orEmpty().isNotEmpty()) {
                    if (state.defaultScooterId != null && state.configuration.scooterId == state.defaultScooterId) {
                        TextButton(onClick = { showBrowseVehicles = !showBrowseVehicles }) { Text(if (showBrowseVehicles) "Hide other scooters" else "Browse another scooter") }
                    }
                    if (showBrowseVehicles || state.defaultScooterId == null || state.configuration.scooterId != state.defaultScooterId) {
                        Text("Browse scooter places · read-only", style = MaterialTheme.typography.titleLarge)
                        Text("Browsing does not change your default or send a navigation request.", style = MaterialTheme.typography.bodySmall)
                        state.scooters.value.orEmpty().forEach { scooter ->
                            OutlinedButton(onClick = { model.selectScooter(scooter); showBrowseVehicles = false }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending && state.configuration.scooterId != scooter.id) {
                                Text("View ${scooter.name}'s places${if (scooter.id == state.defaultScooterId) " · default" else ""}")
                            }
                        }
                    }
                }
                if (state.configuration.isReady) {
                    Text("${state.configuration.scooterName}'s places", style = MaterialTheme.typography.titleLarge)
                    if (state.defaultScooterId != state.configuration.scooterId) Text("Browsing only · your default is unchanged", style = MaterialTheme.typography.bodySmall)
                    val locations = state.savedLocations
                    ResourceFeedback(locations.error, locations.value != null, { model.refresh() })
                    locations.value.orEmpty().forEach { place -> PlaceRow(place.label, place.coordinates.display(), { openPlace(place, null) }) }
                    val current = state.destination.value?.place
                    Text("Current destination", style = MaterialTheme.typography.titleLarge)
                    if (state.destination.loading && state.destination.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    ResourceFeedback(state.destination.error, state.destination.value != null, { model.refresh() })
                    if (current == null) Text("No active destination") else Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.label, style = MaterialTheme.typography.titleMedium)
                        Text(current.coordinates.display(), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { clearWarning = true }, enabled = !state.sending && state.uncertainTarget == null && !state.pendingCommandForOtherAccount) { Text("Cancel navigation") }
                    } }
                }
            }
        }
    }

    visibleFavoriteDraft?.let { place ->
        AlertDialog(onDismissRequest = { if (!state.favoriteEditor.saving) favoriteDraftPlace = null }, title = { Text(if (favoriteDraftId == null) "Save favorite" else "Edit saved place") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(favoriteLabel, { favoriteLabel = it }, label = { Text("Name") }, singleLine = true, enabled = !state.favoriteEditor.saving)
                Text(place.coordinates.display(), style = MaterialTheme.typography.bodySmall)
                if (favoriteDraftId != null) {
                    TextButton(onClick = {
                        val saved = (state.personalPlaces.value?.favorites.orEmpty() + state.personalPlaces.value?.recent.orEmpty()).firstOrNull { it.id == favoriteDraftId }
                        if (saved != null) { model.editFavorite(saved.id, FavoriteDraft(favoriteLabel.trim(), place)); model.setFavorite(saved, !favoriteDraftIsFavorite) }
                    }, enabled = !state.favoriteEditor.saving) { Text(if (favoriteDraftIsFavorite) "Remove from favorites" else "Add to favorites") }
                    TextButton(onClick = {
                        val saved = (state.personalPlaces.value?.favorites.orEmpty() + state.personalPlaces.value?.recent.orEmpty()).firstOrNull { it.id == favoriteDraftId }
                        if (saved != null) deleteFavoritePending = saved
                    }, enabled = !state.favoriteEditor.saving) { Text("Delete saved place") }
                }
                state.favoriteEditor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.configuration.favoritePermission == PermissionCapability.UpgradeRequired) TextButton(onClick = onSettings, enabled = !state.favoriteEditor.saving) { Text("Reconnect to enable saved-place changes") }
            } },
            confirmButton = { TextButton(onClick = {
                model.editFavorite(favoriteDraftId, FavoriteDraft(favoriteLabel.trim(), place))
                model.saveFavorite()
            }, enabled = favoriteLabel.isNotBlank() && !state.favoriteEditor.saving) { Text("Save") } },
            dismissButton = { TextButton(onClick = { favoriteDraftPlace = null; model.editFavorite(null, null) }, enabled = !state.favoriteEditor.saving) { Text("Cancel") } })
    }
    deleteFavoritePending?.let { saved ->
        AlertDialog(onDismissRequest = { if (!state.favoriteEditor.saving) deleteFavoritePending = null },
            title = { Text("Delete saved place?") },
            text = { Text("${saved.place.label} will be permanently removed from your personal places.") },
            confirmButton = { TextButton(onClick = {
                model.editFavorite(saved.id, FavoriteDraft(favoriteLabel, saved.place))
                model.deleteFavorite(saved)
                deleteFavoritePending = null
            }, enabled = !state.favoriteEditor.saving) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = { deleteFavoritePending = null }, enabled = !state.favoriteEditor.saving) { Text("Keep place") } })
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
