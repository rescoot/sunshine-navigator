package dev.ligustah.lsnav

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import java.time.*

@Composable
fun ScooterPicker(scooters: List<ScooterSummary>, enabled: Boolean, defaultScooterId: Long?, onSelect: (ScooterSummary) -> Unit, selectionMode: Boolean = false, sendingId: Long? = null, acceptedId: Long? = null, onCardBounds: (Long, Rect) -> Unit = { _, _ -> }) {
    val available = scooters.filter { it.availableForNavigation || it.id == sendingId || it.id == acceptedId }
    if (available.isEmpty()) {
        EmptyPlaces("No scooters available to send to. Bring a scooter online, then refresh.")
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            available.sortedBy { if (it.id == defaultScooterId) 0 else 1 }.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { scooter -> key(scooter.id) { AppearingItem(scooters.indexOf(scooter), Modifier.weight(1f).fillMaxHeight()) { ScooterTile(scooter, scooter.id == defaultScooterId, enabled, { onSelect(scooter) }, Modifier.fillMaxSize().onGloballyPositioned { onCardBounds(scooter.id, it.boundsInRoot()) }, selectionMode, sendingId == scooter.id, acceptedId == scooter.id, sendingId != null && sendingId != scooter.id) } } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun StatusDot(scooter: ScooterSummary, modifier: Modifier = Modifier) {
    val color = when (scooter.online) { true -> Color(0xFF22C55E); false -> Color(0xFFEF4444); null -> Color(0xFFEAB308) }
    var pulse = 0f
    if (scooter.online == true && motionEnabled()) {
        val transition = rememberInfiniteTransition(label = "online")
        pulse = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "ring").value
    }
    Box(modifier.size(18.dp).semantics { contentDescription = when (scooter.online) { true -> "Online"; false -> "Offline"; null -> "Connection unknown" } }, contentAlignment = Alignment.Center) {
        Box(Modifier.size((8 + pulse * 10).dp).border(2.dp, color.copy(alpha = .4f * (1 - pulse)), CircleShape))
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

@Composable
fun ScooterArt(scooter: ScooterSummary, modifier: Modifier = Modifier) {
    val offline = scooter.online != true
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = scooter.artworkUrl()
        AsyncImage(imageLoader = LocalArtworkLoader.current ?: coil.Coil.imageLoader(androidx.compose.ui.platform.LocalContext.current), model = image, contentDescription = null, fallback = painterResource(R.drawable.scooter_placeholder), error = painterResource(R.drawable.scooter_placeholder), placeholder = painterResource(R.drawable.scooter_placeholder),
            colorFilter = if (offline) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.5f) }) else null,
            modifier = Modifier.fillMaxSize().alpha(if (offline) .75f else 1f))
    }
}

internal fun ScooterSummary.artworkUrl() = listOf(images.right, images.left, images.front).firstOrNull { it?.startsWith("https://") == true }

internal fun ScooterSummary.vehicleStateLabel(): String = when {
    state.isNullOrBlank() || state == "unknown" -> "State unknown"
    state == "ready-to-drive" -> "Ready to drive"
    state == "stand-by" -> "Stand-by"
    else -> state.replace('-', ' ').replaceFirstChar(Char::uppercase)
}

@Composable
fun ScooterRow(scooter: ScooterSummary, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}, modifier: Modifier = Modifier, dropdown: Boolean = false, description: String? = null) {
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(10.dp))
        .background(if (selected) primary.copy(alpha = .07f) else Color.Transparent)
        .border(if (selected) 2.dp else 1.dp, if (selected) primary.copy(alpha = .65f) else if (dropdown) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, RoundedCornerShape(10.dp))
        .semantics(mergeDescendants = true) { this.selected = selected; if (description != null) contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScooterArt(scooter, Modifier.size(96.dp, 76.dp).testTag("scooter-art-${scooter.id}"))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(scooter.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusDot(scooter)
            }
            Text(scooter.vehicleStateLabel(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dropdown) Icon(Icons.Default.ArrowDropDown, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScooterDropdown(scooters: List<ScooterSummary>, selectedId: Long?, enabled: Boolean = true, onSelect: (ScooterSummary) -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val scooter = scooters.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        if (scooter != null) ScooterRow(scooter, enabled = enabled, dropdown = true, onClick = { expanded = true }, description = "Browse scooter places: ${scooter.name}")
        else SunshineButton({ expanded = true }, enabled = enabled && scooters.isNotEmpty(), tone = ButtonTone.Secondary) { Text("Choose a scooter"); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.width(with(density) { width.toDp() }).heightIn(max = 360.dp).background(MaterialTheme.colorScheme.surface)) {
            scooters.forEach { item ->
                ScooterRow(item, selected = item.id == selectedId, onClick = { expanded = false; onSelect(item) })
            }
        }
    }
}

internal fun lastSeenLabel(value: String?): String {
    val hours = value?.let { runCatching { Duration.between(Instant.parse(it), Instant.now()).toHours().coerceAtLeast(0) }.getOrNull() } ?: return "Offline"
    return "Offline · ${if (hours < 1) "just now" else if (hours < 24) "$hours h ago" else "${hours / 24} d ago"}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScooterTile(scooter: ScooterSummary, isDefault: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, selectionMode: Boolean = false, sending: Boolean = false, accepted: Boolean = false, dimmed: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(), label = "tile press")
    SunshineCard(modifier.scale(scale).alpha(if (dimmed) .6f else 1f).semantics(mergeDescendants = true) { contentDescription = if (selectionMode) "Choose ${scooter.name} as default" else "Send to ${scooter.name}, ${if (scooter.online == true) "online" else "offline"}" }
        .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled && (selectionMode || scooter.availableForNavigation), role = Role.Button, onClick = onClick), accent = if (accepted) Color(0xFF22C55E) else if (focused || selectionMode && isDefault || sending) Color(0xFF4F46E5) else null) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                scooter.licensePlate?.takeIf(String::isNotBlank)?.let { Text(it.uppercase(), Modifier.background(Color.White, RoundedCornerShape(3.dp)).border(1.dp, Color.Black, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp), color = Color.Black, fontFamily = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1) }
                Spacer(Modifier.weight(1f))
                if (isDefault) Text("Default", Modifier.background(Color(0xFF4338CA).copy(alpha = .1f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                StatusDot(scooter)
            }
            ScooterArt(scooter, Modifier.fillMaxWidth().aspectRatio(1.25f))
            Text(scooter.name, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                sending -> "Sending…"
                accepted -> "Sent"
                else -> scooter.vehicleStateLabel()
            }
            Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (accepted) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                Text(if (sending || accepted) "${scooter.vehicleStateLabel()} · $status" else status, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (scooter.online == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (sending) TurboBar()
    }
}

@Composable
fun ScooterSkeletons() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SunshineCard(Modifier.weight(1f)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Skeleton(Modifier.fillMaxWidth().height(112.dp)); Skeleton(Modifier.fillMaxWidth(.7f).height(18.dp)); Skeleton(Modifier.fillMaxWidth(.5f).height(12.dp)) } } } }
}
