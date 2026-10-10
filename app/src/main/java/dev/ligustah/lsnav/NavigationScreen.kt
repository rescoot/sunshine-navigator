package dev.ligustah.lsnav

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onSettings: () -> Unit,
    onDone: () -> Unit = {},
    sharedText: String? = null,
    incomingRequestId: Long? = null,
    onShareConsumed: () -> Unit = {},
    model: NavigationViewModel = viewModel()
) {
    val state by model.state.collectAsStateWithLifecycle()
    val sharedPreview = remember(sharedText) { sharedText?.takeIf(String::isNotBlank)?.let(::parseSharedPlacePreview) }
    val displayedQuery = if (state.query == sharedText) sharedPreview?.label ?: state.query else state.query
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
    var destinationContextRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var favoriteDraftPlace by rememberSaveable(stateSaver = destinationSaver) { mutableStateOf<PlaceResult?>(null) }
    var favoriteDraftId by rememberSaveable { mutableStateOf<Long?>(null) }
    var favoriteDraftIsFavorite by rememberSaveable { mutableStateOf(false) }
    var favoriteDraftOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var favoriteLabel by rememberSaveable { mutableStateOf("") }
    var favoriteSaveStartSequence by rememberSaveable { mutableStateOf(state.favoriteMutationSequence) }
    var deleteFavoritePending by remember { mutableStateOf<SavedPlace?>(null) }
    var showBrowseVehicles by rememberSaveable { mutableStateOf(false) }
    var addingFavorite by rememberSaveable { mutableStateOf(false) }
    var clearWarning by remember { mutableStateOf(false) }
    var sendingTarget by remember { mutableStateOf<CommandTarget?>(null) }
    val ownerFingerprint = state.configuration.accountFingerprint()
    val visibleDestination = destination?.takeIf {
        destinationContextRequestId == incomingRequestId &&
            (if (destinationIsExternal) destinationContextRequestId != null else state.configurationLoaded && destinationOwner == ownerFingerprint)
    }
    val visibleFavoriteDraft = favoriteDraftPlace?.takeIf { state.configurationLoaded && favoriteDraftOwner == ownerFingerprint }
    val openPlace: (PlaceResult, Long?, Boolean) -> Unit = { place, savedId, external ->
        destination = place
        destinationOwner = if (external) null else ownerFingerprint
        destinationIsExternal = external
        destinationSavedId = savedId
        destinationContextRequestId = incomingRequestId
    }
    val clearPlace = {
        destination = null
        destinationOwner = null
        destinationIsExternal = false
        destinationSavedId = null
        destinationContextRequestId = null
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
    LaunchedEffect(incomingRequestId) {
        val requestId = incomingRequestId ?: return@LaunchedEffect
        model.beginIncomingRequest(requestId, requireResolution = !sharedText.isNullOrBlank())
        if (destination != null && destinationContextRequestId != requestId) clearPlace()
    }
    LaunchedEffect(incomingRequestId, sharedText, state.sending) {
        val requestId = incomingRequestId ?: return@LaunchedEffect
        model.beginIncomingRequest(requestId, requireResolution = !sharedText.isNullOrBlank())
        if (!sharedText.isNullOrBlank() && !state.sending && state.resolvedIncomingRequestId != requestId) {
            model.resolveIncoming(sharedText, requestId)
        }
    }
    val currentSharedResults = incomingRequestId != null && state.currentIncomingRequestId == incomingRequestId &&
        state.resolvedIncomingRequestId == incomingRequestId
    val visibleResults = if (!sharedText.isNullOrBlank() && !currentSharedResults) emptyList() else state.results
    LaunchedEffect(state.results, state.resolvedIncomingText, state.resolvedIncomingRequestId, sharedText, incomingRequestId) {
        if (!sharedText.isNullOrBlank() && currentSharedResults && state.resolvedIncomingText == sharedText && state.results.size == 1) {
            openPlace(state.results.single(), null, true)
            onShareConsumed()
        }
    }
    LaunchedEffect(state.favoriteMutationSequence) {
        if (favoriteDraftPlace != null && state.favoriteMutationSequence > favoriteSaveStartSequence) favoriteDraftPlace = null
    }
    BackHandler(destination != null) { clearPlace() }

    var hideAccepted by remember { mutableStateOf(false) }
    val outcome = state.lastCommandOutcome?.takeIf { result ->
        val target = when (result) {
            is CommandOutcome.ServerAccepted -> result.target
            is CommandOutcome.DeliveryUncertain -> result.target
        }
        target.configuration == state.configuration && target.place == visibleDestination &&
            state.lastCommandIncomingRequestId == destinationContextRequestId
    }
    val hapticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(outcome, state.error) {
        if (outcome is CommandOutcome.ServerAccepted) {
            hapticView.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } else if (state.error != null && sendingTarget != null) {
            hapticView.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
    val accepted = (outcome as? CommandOutcome.ServerAccepted)?.target?.takeUnless { hideAccepted }
    val canSend = visibleDestination != null && !state.sending && !state.incomingResolutionRequired &&
        (state.uncertainTarget == null || state.uncertainCheckedDestination != null) &&
        !state.pendingCommandLoading && !state.pendingCommandForOtherAccount
    val queryChanged: (String) -> Unit = { value ->
        val correctingRequestId = incomingRequestId?.takeIf { !sharedText.isNullOrBlank() && state.currentIncomingRequestId == it }
        model.updateQuery(value, correctingRequestId)
        if (correctingRequestId != null) onShareConsumed()
    }
    val recovery: @Composable (Boolean) -> Unit = { docked ->
        UncertainRecovery(state, model, onSettings, docked)
        if (state.pendingCommandLoading) Text("Restoring previous request status…", style = MaterialTheme.typography.bodySmall)
    }
    val showSend = visibleDestination != null || state.incomingResolutionRequired || (!sharedText.isNullOrBlank() && visibleResults.isNotEmpty())
    androidx.compose.animation.AnimatedContent(showSend, transitionSpec = {
        (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220, easing = SunshineEase)) { it / 16 } + androidx.compose.animation.fadeIn()) togetherWith
            (androidx.compose.animation.slideOutHorizontally { -it / 16 } + androidx.compose.animation.fadeOut())
    }, label = "place journey") { sendingScreen ->
        if (sendingScreen) SendScreen(state, visibleDestination, destinationIsExternal || !sharedText.isNullOrBlank(), accepted, sendingTarget, canSend, visibleResults,
            onCandidate = { place -> openPlace(place, null, currentSharedResults); if (currentSharedResults) onShareConsumed() },
            onSend = { scooter ->
                visibleDestination?.let { place ->
                    hideAccepted = false
                    model.selectScooter(scooter)
                    val captured = state.configuration.copy(scooterId = scooter.id, scooterName = scooter.name)
                    val target = CommandTarget(captured, place)
                    sendingTarget = target
                    model.sendToScooter(target, destinationContextRequestId)
                }
            }, onBack = { clearPlace(); if (!sharedText.isNullOrBlank()) { model.updateQuery("", incomingRequestId); onShareConsumed() }; onDone() },
            onSettings = onSettings, onRefresh = { model.fetchScooters(true) },
            onSave = { visibleDestination?.let { place ->
                val existing = state.personalPlaces.value?.let { it.favorites + it.recent }?.firstOrNull { it.id == destinationSavedId || it.place.coordinates == place.coordinates }
                openFavoriteEditor(existing?.id, place, place.label)
            } }, onEdit = { clearPlace(); queryChanged(displayedQuery) },
            onDone = { clearPlace(); onDone() }, onAnother = { hideAccepted = true }, recovery = { recovery(true) })
        else HomeScreen(state, displayedQuery, visibleResults, queryChanged,
            onOpen = { place, id -> openPlace(place, id, currentSharedResults && id == null); if (currentSharedResults && !sharedText.isNullOrBlank()) onShareConsumed() },
            onEdit = { saved -> openFavoriteEditor(saved.id, saved.place, saved.place.label) },
            onStar = { saved -> model.setFavorite(saved, !saved.isFavorite) },
            onAdd = { addingFavorite = true },
            onSettings = onSettings, onRefresh = model::refresh, onClear = { clearWarning = true }, onChange = { showBrowseVehicles = true }, recovery = { recovery(false) })
    }
    if (showBrowseVehicles) SunshineSheet(onDismissRequest = { showBrowseVehicles = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Browse scooter places", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Read-only. Your default stays the same.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.scooters.value.orEmpty().forEach { scooter ->
                TextButton(onClick = { model.selectScooter(scooter); showBrowseVehicles = false }, enabled = !state.sending) { Text(scooter.name) }
            }
        }
    }
    if (addingFavorite) SunshineSheet(onDismissRequest = { addingFavorite = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add favourite", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(displayedQuery, queryChanged, placeholder = { Text("Search or paste a map link") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (state.searching) TurboBar()
            if (visibleResults.isNotEmpty()) GroupedPlaces(visibleResults, { place -> addingFavorite = false; openFavoriteEditor(null, place, place.label) }, Icons.Default.Place)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    visibleFavoriteDraft?.let { place ->
        SunshineSheet(onDismissRequest = { if (!state.favoriteEditor.saving) favoriteDraftPlace = null }) {
            PlaceEditorContent(favoriteLabel, place, state.favoriteEditor.saving, state.favoriteEditor.error,
                title = if (favoriteDraftId != null) "Edit place" else "Save place", onLabel = { favoriteLabel = it }, onSave = {
                    model.editFavorite(favoriteDraftId, FavoriteDraft(favoriteLabel.trim(), place)); model.saveFavorite()
                }, onCancel = { favoriteDraftPlace = null; model.editFavorite(null, null) }, actions = {
                    if (favoriteDraftId != null) {
                        PlaceEditorActions(favoriteDraftIsFavorite, enabled = !state.favoriteEditor.saving, onToggle = {
                            val saved = (state.personalPlaces.value?.favorites.orEmpty() + state.personalPlaces.value?.recent.orEmpty()).firstOrNull { it.id == favoriteDraftId }
                            if (saved != null) { model.editFavorite(saved.id, FavoriteDraft(favoriteLabel.trim(), place)); model.setFavorite(saved, !favoriteDraftIsFavorite) }
                        }, onDelete = {
                            val saved = (state.personalPlaces.value?.favorites.orEmpty() + state.personalPlaces.value?.recent.orEmpty()).firstOrNull { it.id == favoriteDraftId }
                            if (saved != null) deleteFavoritePending = saved
                        })
                    }
                    if (state.configuration.favoritePermission == PermissionCapability.UpgradeRequired) TextButton(onSettings, enabled = !state.favoriteEditor.saving) { Text("Reconnect to edit favourites") }
                })
        }
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
        text = { Text("This clears the current destination for ${state.configuration.scooterName}. It leaves other scooter settings unchanged.") },
        confirmButton = { TextButton(onClick = { clearWarning = false; model.send(null, state.configuration, incomingRequestId) }) { Text("Cancel navigation") } },
        dismissButton = { TextButton(onClick = { clearWarning = false }) { Text("Keep destination") } })
}

