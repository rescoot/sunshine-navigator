package dev.ligustah.lsnav

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

internal val NavigationState.networkLoading: Boolean get() = searching || sending || favoriteEditor.saving || scooters.loading || destination.loading || personalPlaces.loading || savedLocations.loading || checkingUncertain || pendingCommandLoading

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(state: NavigationState, query: String = state.query, results: List<PlaceResult> = state.results, onQuery: (String) -> Unit = {}, onOpen: (PlaceResult, Long?) -> Unit = { _, _ -> }, onEdit: (SavedPlace) -> Unit = {}, onStar: (SavedPlace) -> Unit = {}, onAdd: () -> Unit = {}, onSettings: () -> Unit = {}, onRefresh: () -> Unit = {}, onClear: () -> Unit = {}, onChange: () -> Unit = {}, recovery: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        NavigatorBar(loading = state.networkLoading, onRefresh = onRefresh, onAccount = onSettings)
        Box(Modifier.fillMaxWidth().height(92.dp)) {
            Box(Modifier.fillMaxWidth().height(72.dp).background(if (MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)) Indigo950 else Indigo800, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)))
            OutlinedTextField(query, onQuery, placeholder = { Text("Where to?") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) TextButton({ onQuery("") }) { Text("×") } }, singleLine = true, enabled = !state.sending,
                shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = Color.Transparent), modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp))
        }
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            recovery()
            AnimatedVisibility(results.isNotEmpty(), enter = fadeIn() + expandVertically()) { GroupedPlaces(results, onOpen = { onOpen(it, null) }, leading = "⌖") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.configuration.isReady) {
                val scooter = state.scooters.value.orEmpty().firstOrNull { it.id == state.configuration.scooterId }
                SunshineCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (scooter != null) Box(Modifier.size(80.dp, 64.dp)) { ScooterArt(scooter, Modifier.fillMaxSize()); StatusDot(scooter, Modifier.align(Alignment.TopEnd)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("NAVIGATING TO", fontSize = 12.sp, letterSpacing = .8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when { state.destination.error != null -> "Couldn't check destination"; state.destination.value == null -> "Checking destination…"; state.destination.value.place == null -> "No destination set"; else -> state.destination.value.place.displayTitle() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.destination.error != null) TextButton(onRefresh) { Text("↻ Retry") }
                                else if (state.destination.value?.place != null) SunshineButton(onClear, enabled = !state.sending && state.uncertainTarget == null && !state.pendingCommandForOtherAccount && !state.pendingCommandLoading, tone = ButtonTone.Secondary, modifier = Modifier.width(90.dp)) { Text("Clear") }
                                if (state.defaultScooterId == null) TextButton(onChange, enabled = !state.sending) { Text("Change") }
                            }
                        }
                    }
                }
            } else if (state.scooters.value.orEmpty().isNotEmpty()) TextButton(onChange) { Text("Choose a scooter to browse") }
            if (!state.configuration.isAuthenticated) ProblemPanel("Connect Sunshine", "Sign in to find your scooters and places.", "Get started", onSettings, BadgeTone.Indigo)
            val personal = state.personalPlaces.value
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("Favourites")
                if (state.configuration.favoritePermission != PermissionCapability.UpgradeRequired) TextButton(onAdd) { Text("+ Add") }
            }
            if (state.configuration.favoritePermission == PermissionCapability.UpgradeRequired) {
                Box(Modifier.clickable(onClick = onSettings)) { SunshineBadge("Reconnect to edit favourites") }
            }
            if (state.personalPlaces.loading && personal == null) Skeleton(Modifier.fillMaxWidth().height(96.dp))
            state.personalPlaces.error?.let { ProblemPanel("Couldn't load places", it, "Retry", onRefresh) }
            if (!personal?.favorites.isNullOrEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                personal!!.favorites.forEach { saved ->
                    SunshineCard(Modifier.width(148.dp).height(96.dp).combinedClickable(onClick = { onOpen(saved.place, saved.id) }, onLongClick = { onEdit(saved) })) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("★", color = Color(0xFFFBBF24)); Text("⋮", Modifier.clickable { onEdit(saved) }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(saved.place.displayTitle(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(saved.place.displayDetail(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            if (!personal?.recent.isNullOrEmpty()) { SectionTitle("Recent"); SunshineCard(Modifier.fillMaxWidth()) { personal!!.recent.forEachIndexed { index, saved ->
                PlaceListRow(saved.place, "◷", { onOpen(saved.place, saved.id) }) { TextButton({ onStar(saved) }, enabled = !state.favoriteEditor.saving) { Text(if (saved.isFavorite) "★" else "☆", color = Color(0xFFFBBF24)) } }
                if (index != personal.recent.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            } } }
            if (state.configuration.isReady) {
                SectionTitle("On the scooter")
                state.savedLocations.error?.let { ProblemPanel("Couldn't load scooter places", it, "Retry", onRefresh) }
                if (state.savedLocations.value.orEmpty().isNotEmpty()) GroupedPlaces(state.savedLocations.value.orEmpty(), { onOpen(it, null) }, "▱")
                if (state.defaultScooterId != null) TextButton(onChange, enabled = !state.sending) { Text("Browse another scooter") }
            }
            if (personal != null && personal.favorites.isEmpty() && personal.recent.isEmpty()) EmptyPlaces()
        }
    }
}

@Composable
fun SectionTitle(text: String) { Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .96.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
fun PlaceListRow(place: PlaceResult, leading: String, onOpen: () -> Unit, trailing: @Composable () -> Unit = { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(leading, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
        Column(Modifier.weight(1f)) { Text(place.displayTitle(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(place.displayDetail(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        trailing()
    }
}

@Composable
fun GroupedPlaces(places: List<PlaceResult>, onOpen: (PlaceResult) -> Unit, leading: String) { SunshineCard(Modifier.fillMaxWidth()) { places.forEachIndexed { index, place -> AppearingItem(index) { PlaceListRow(place, leading, { onOpen(place) }) }; if (index != places.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) } } }

@Composable
fun SendScreen(state: NavigationState, place: PlaceResult?, shared: Boolean = false, acceptedTarget: CommandTarget? = null, sendingTarget: CommandTarget? = null, enabled: Boolean = false, results: List<PlaceResult> = emptyList(), onCandidate: (PlaceResult) -> Unit = {}, onSend: (ScooterSummary) -> Unit = {}, onBack: () -> Unit = {}, onSettings: () -> Unit = {}, onRefresh: () -> Unit = {}, onSave: () -> Unit = {}, onEdit: () -> Unit = {}, onDone: () -> Unit = {}, onAnother: () -> Unit = {}, recovery: @Composable () -> Unit = {}) {
    var showFailure by remember(state.error) { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        NavigatorBar("Send to scooter", state.networkLoading, onBack, onAccount = onSettings)
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DestinationTicket(place, shared, state.incomingResolutionRequired && place == null, state.sending, onSave, onEdit)
            if (place == null && results.isNotEmpty()) GroupedPlaces(results, onCandidate, "⌖")
            if (place == null) TextButton(onEdit) { Text("Edit place") }
            recovery()
            if (showFailure && state.uncertainTarget == null && !state.pendingCommandForOtherAccount) state.error?.let {
                val resolving = place == null
                val reconnect = state.configuration.needsPermissionUpgrade || it.contains("login", true) || it.contains("token", true) || it.contains("Settings", true)
                ProblemPanel(if (resolving) "Couldn't find this place" else "Couldn't send this place", it, if (resolving) "Edit place" else if (reconnect) "Reconnect" else "Choose another scooter", if (resolving) onEdit else if (reconnect) onSettings else { { showFailure = false } })
            }
            Text("☝  Tap a scooter to send", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Sending replaces the scooter's current destination.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                !state.configuration.isAuthenticated -> ProblemPanel("Connect Sunshine", "Your place will stay here.", "Connect Sunshine", onSettings, BadgeTone.Indigo)
                state.configuration.missingOAuthScopes.contains("scooter_control") -> ProblemPanel("Scooter access needed", "Approve scooter navigation access to send a place.", "Reconnect", onSettings, BadgeTone.Warning)
                state.scooters.error != null -> ProblemPanel("Couldn't load your scooters", state.scooters.error, if (state.scooterReconnectRequired) "Reconnect" else "Retry", if (state.scooterReconnectRequired) onSettings else onRefresh)
                state.scooters.value == null -> ScooterSkeletons()
                state.scooters.value.isEmpty() -> { EmptyPlaces("No scooters on this account yet"); SunshineButton(onRefresh, tone = ButtonTone.Secondary) { Text("Refresh") } }
                else -> ScooterPicker(state.scooters.value, enabled, state.defaultScooterId, onSend, sendingId = sendingTarget?.configuration?.scooterId?.takeIf { state.sending }, acceptedId = acceptedTarget?.configuration?.scooterId,
                    replacement = state.destination.value?.place?.let { current -> state.configuration.scooterId?.let { it to current.label } })
            }
        }
        AnimatedVisibility(acceptedTarget != null, enter = slideInVertically { it / 4 } + fadeIn()) {
            Box(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) { acceptedTarget?.let { AcceptedPanel(it.configuration.scooterName, onDone, onAnother) } }
        }
    }
}

@Composable
fun AcceptedPanel(name: String, onDone: () -> Unit = {}, onAnother: () -> Unit = {}) {
    SunshineCard(Modifier.fillMaxWidth(), panel = true, accent = Color(0xFF22C55E)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AcceptedCheck()
            Text("Sent to $name", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Sunshine accepted the destination. Check the dashboard to confirm.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SunshineButton(onDone) { Text("Done") }
            SunshineButton(onAnother, tone = ButtonTone.Secondary) { Text("Send to another") }
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
