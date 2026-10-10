package dev.ligustah.lsnav

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.core.tween

internal val NavigationState.networkLoading: Boolean get() = searching || sending || favoriteEditor.saving || scooters.loading || destination.loading || personalPlaces.loading || savedLocations.loading || checkingUncertain || pendingCommandLoading

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(state: NavigationState, query: String = state.query, results: List<PlaceResult> = state.results, onQuery: (String) -> Unit = {}, onOpen: (PlaceResult, Long?) -> Unit = { _, _ -> }, onEdit: (SavedPlace) -> Unit = {}, onStar: (SavedPlace) -> Unit = {}, onAdd: () -> Unit = {}, onSettings: () -> Unit = {}, onRefresh: () -> Unit = {}, onClear: () -> Unit = {}, onChange: () -> Unit = {}, recovery: @Composable () -> Unit = {}) {
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    Column(Modifier.fillMaxSize()) {
        NavigatorBar(loading = state.networkLoading, onRefresh = onRefresh, onAccount = onSettings, hero = true)
        Box(Modifier.fillMaxWidth().height(92.dp)) {
            val night = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
            Box(Modifier.fillMaxWidth().height(74.dp).background(if (night) Indigo800 else Color(0xFF4338CA), RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)))
            Box(Modifier.fillMaxWidth().height(72.dp).background(if (night) Indigo950 else Indigo800, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)))
            OutlinedTextField(query, onQuery, placeholder = { Text("Where to?") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Default.Close, "Clear search", Modifier.size(22.dp)) } }, singleLine = true, enabled = !state.sending,
                interactionSource = searchInteraction, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = Color.Transparent), modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp).shadow(if (searchFocused) 6.dp else 3.dp, RoundedCornerShape(12.dp)))
        }
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            recovery()
            AnimatedVisibility(results.isNotEmpty(), enter = fadeIn() + expandVertically()) { GroupedPlaces(results, onOpen = { onOpen(it, null) }, leading = Icons.Default.Place) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.configuration.isReady) {
                val scooter = state.scooters.value.orEmpty().firstOrNull { it.id == state.configuration.scooterId }
                SunshineCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (scooter != null) ScooterArt(scooter, Modifier.size(64.dp, 56.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (scooter != null) StatusDot(scooter)
                                    Text("${scooter?.name ?: state.configuration.scooterName} · Navigating to", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(when { state.destination.error != null -> "Couldn't check destination"; state.destination.value == null -> "Checking destination…"; state.destination.value.place == null -> "No destination set"; else -> state.destination.value.place.displayTitle() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onChange, enabled = !state.sending, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Change scooter") }
                            Spacer(Modifier.weight(1f))
                            if (state.destination.error != null) TextButton(onRefresh) { Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("Retry") }
                            else if (state.destination.value?.place != null) SunshineButton(onClear, enabled = !state.sending && state.uncertainTarget == null && !state.pendingCommandForOtherAccount && !state.pendingCommandLoading, tone = ButtonTone.Secondary, modifier = Modifier.width(80.dp)) { Text("Clear") }
                        }
                    }
                }
            } else if (state.scooters.value.orEmpty().isNotEmpty()) TextButton(onChange) { Text("Choose a scooter to browse") }
            if (!state.configuration.isAuthenticated) ProblemPanel("Connect Sunshine", "Sign in to find your scooters and places.", "Get started", onSettings, BadgeTone.Indigo)
            val personal = state.personalPlaces.value
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("Favourites")
                if (state.configuration.favoritePermission != PermissionCapability.UpgradeRequired) TextButton(onAdd) { Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Text("Add") }
            }
            if (state.configuration.favoritePermission == PermissionCapability.UpgradeRequired) {
                Box(Modifier.clickable(onClick = onSettings)) { SunshineBadge("Reconnect to edit favourites") }
            }
            if (state.personalPlaces.loading && personal == null) Skeleton(Modifier.fillMaxWidth().height(96.dp))
            state.personalPlaces.error?.let { ProblemPanel("Couldn't load places", it, "Retry", onRefresh) }
            if (!personal?.favorites.isNullOrEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(personal!!.favorites, key = { it.id }) { saved ->
                    SunshineCard(Modifier.width(148.dp).heightIn(min = 132.dp).combinedClickable(onClick = { onOpen(saved.place, saved.id) }, onLongClick = { onEdit(saved) })) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null, Modifier.size(22.dp), tint = Color(0xFFFBBF24))
                                IconButton({ onEdit(saved) }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "Edit ${saved.place.label}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Text(saved.place.displayTitle(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(saved.place.displayDetail(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (!personal?.recent.isNullOrEmpty()) { SectionTitle("Recent"); SunshineCard(Modifier.fillMaxWidth()) { personal!!.recent.forEachIndexed { index, saved ->
                PlaceListRow(saved.place, Icons.Default.Schedule, { onOpen(saved.place, saved.id) }) { IconButton({ onStar(saved) }, enabled = !state.favoriteEditor.saving, modifier = Modifier.size(48.dp)) { Icon(if (saved.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Toggle favourite for ${saved.place.label}", Modifier.size(22.dp), tint = Color(0xFFFBBF24)) } }
                if (index != personal.recent.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            } } }
            if (state.configuration.isReady) {
                SectionTitle("On the scooter")
                state.savedLocations.error?.let { ProblemPanel("Couldn't load scooter places", it, "Retry", onRefresh) }
                if (state.savedLocations.value.orEmpty().isNotEmpty()) GroupedPlaces(state.savedLocations.value.orEmpty(), { onOpen(it, null) }, Icons.Default.ElectricScooter)
            }
            if (personal != null && personal.favorites.isEmpty() && personal.recent.isEmpty()) EmptyPlaces()
        }
    }
}

@Composable
fun SectionTitle(text: String) { Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .96.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
fun PlaceListRow(place: PlaceResult, leading: ImageVector, onOpen: () -> Unit, trailing: @Composable () -> Unit = { Icon(Icons.Default.ChevronRight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(leading, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) { Text(place.displayTitle(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(place.displayDetail(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        trailing()
    }
}

@Composable
fun GroupedPlaces(places: List<PlaceResult>, onOpen: (PlaceResult) -> Unit, leading: ImageVector) { SunshineCard(Modifier.fillMaxWidth()) { places.forEachIndexed { index, place -> AppearingItem(index) { PlaceListRow(place, leading, { onOpen(place) }) }; if (index != places.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) } } }

@Composable
fun SendScreen(state: NavigationState, place: PlaceResult?, shared: Boolean = false, acceptedTarget: CommandTarget? = null, sendingTarget: CommandTarget? = null, enabled: Boolean = false, results: List<PlaceResult> = emptyList(), onCandidate: (PlaceResult) -> Unit = {}, onSend: (ScooterSummary) -> Unit = {}, onBack: () -> Unit = {}, onSettings: () -> Unit = {}, onRefresh: () -> Unit = {}, onSave: () -> Unit = {}, onEdit: () -> Unit = {}, onDone: () -> Unit = {}, onAnother: () -> Unit = {}, recovery: @Composable () -> Unit = {}) {
    var showFailure by remember(state.error) { mutableStateOf(true) }
    val hasRecovery = state.uncertainTarget != null || state.pendingCommandForOtherAccount
    val hasFailure = showFailure && state.error != null && !hasRecovery
    val hasResult = hasRecovery || acceptedTarget != null || hasFailure
    val focusedId = acceptedTarget?.configuration?.scooterId ?: state.uncertainTarget?.configuration?.scooterId ?: sendingTarget?.configuration?.scooterId
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(0) }
    var viewportTop by remember { mutableStateOf(0f) }
    var focusedBounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(hasResult, sheetHeight, focusedId) {
        if (hasResult && sheetHeight > 0 && focusedId != null) {
            val bottom = focusedBounds?.bottom ?: return@LaunchedEffect
            val availableBottom = viewportTop + viewportHeight - sheetHeight - with(density) { 8.dp.toPx() }
            if (bottom > availableBottom) scroll.animateScrollBy(bottom - availableBottom, tween(220, easing = SunshineEase))
        }
    }
    Column(Modifier.fillMaxSize()) {
        NavigatorBar("Send to scooter", state.networkLoading, onBack, onAccount = onSettings)
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height }.onGloballyPositioned { viewportTop = it.positionInRoot().y }) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().align(Alignment.TopCenter).navigationBarsPadding().imePadding().verticalScroll(scroll).padding(20.dp).padding(bottom = if (hasResult) with(density) { sheetHeight.toDp() } else 0.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DestinationTicket(place, shared, state.incomingResolutionRequired && place == null, state.sending, onSave, onEdit)
            if (place == null && results.isNotEmpty()) GroupedPlaces(results, onCandidate, Icons.Default.Place)
            if (place == null) TextButton(onEdit) { Text("Edit place") }
            if (!hasRecovery) recovery()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.TouchApp, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Tap a scooter to send", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Sending replaces the scooter's current destination.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                !state.configuration.isAuthenticated -> ProblemPanel("Connect Sunshine", "Your place will stay here.", "Connect Sunshine", onSettings, BadgeTone.Indigo)
                state.configuration.missingOAuthScopes.contains("scooter_control") -> ProblemPanel("Scooter access needed", "Approve scooter navigation access to send a place.", "Reconnect", onSettings, BadgeTone.Warning)
                state.scooters.error != null -> ProblemPanel("Couldn't load your scooters", state.scooters.error, if (state.scooterReconnectRequired) "Reconnect" else "Retry", if (state.scooterReconnectRequired) onSettings else onRefresh)
                state.scooters.value == null -> ScooterSkeletons()
                state.scooters.value.isEmpty() -> { EmptyPlaces("No scooters on this account yet"); SunshineButton(onRefresh, tone = ButtonTone.Secondary) { Text("Refresh") } }
                else -> ScooterPicker(state.scooters.value, enabled, state.defaultScooterId, onSend, sendingId = sendingTarget?.configuration?.scooterId?.takeIf { state.sending }, acceptedId = acceptedTarget?.configuration?.scooterId,
                    replacement = state.destination.value?.place?.let { current ->
                        state.configuration.scooterId?.takeIf { it != acceptedTarget?.configuration?.scooterId }?.let { id ->
                            id to "Current: ${current.displayTitle()}"
                        }
                    },
                    onCardBounds = { id, bounds -> if (id == focusedId) focusedBounds = bounds })
            }
        }
        if (hasResult) {
            val rejected = hasRecovery && state.error != null && state.lastCommandOutcome !is CommandOutcome.DeliveryUncertain
            val tone = if (rejected || hasFailure) BadgeTone.Error else if (hasRecovery) BadgeTone.Warning else BadgeTone.Success
            ResultDock(tone, Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).onSizeChanged { sheetHeight = it.height }) {
                when {
                    hasRecovery -> recovery()
                    acceptedTarget != null -> AcceptedPanel(acceptedTarget.configuration.scooterName, onDone, onAnother)
                    hasFailure -> {
                        val message = state.error!!
                        val resolving = place == null
                        val reconnect = state.configuration.needsPermissionUpgrade || message.contains("login", true) || message.contains("token", true) || message.contains("Settings", true)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { ResultIcon(BadgeTone.Error); Text(if (resolving) "Couldn't find this place" else "Couldn't send this place", fontWeight = FontWeight.SemiBold, fontSize = 19.sp) }
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SunshineButton(if (resolving) onEdit else if (reconnect) onSettings else { { showFailure = false } }) { Text(if (resolving) "Edit place" else if (reconnect) "Reconnect" else "Choose another scooter") }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
fun AcceptedPanel(name: String, onDone: () -> Unit = {}, onAnother: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { ResultIcon(); Text("Sent to $name", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        Text("Sunshine accepted the destination. Check the dashboard to confirm.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SunshineButton(onDone, Modifier.weight(1f)) { Text("Done") }
            SunshineButton(onAnother, Modifier.weight(1f), tone = ButtonTone.Secondary) { Text("Send to another") }
        }
    }
}

@Composable
fun PlaceEditorContent(label: String, place: PlaceResult, saving: Boolean = false, error: String? = null, onLabel: (String) -> Unit = {}, onSave: () -> Unit = {}, onCancel: () -> Unit = {}, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Save place", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(label, onLabel, label = { Text("Name") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
        Text(place.coordinates.display(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SunshineButton(onSave, enabled = label.isNotBlank() && !saving) { Text("Save") }
        TextButton(onCancel, enabled = !saving) { Text("Cancel") }
    }
}
