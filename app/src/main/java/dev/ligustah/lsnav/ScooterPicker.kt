package dev.ligustah.lsnav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage

@Composable
fun ScooterPicker(
    scooters: List<ScooterSummary>,
    enabled: Boolean,
    defaultScooterId: Long?,
    onSelect: (ScooterSummary) -> Unit,
    placeLabel: String = "this place"
) {
    if (scooters.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            scooters.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { scooter ->
                        ScooterTile(scooter, scooter.id == defaultScooterId, enabled, { onSelect(scooter) }, placeLabel, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun ScooterArt(scooter: ScooterSummary, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = listOf(scooter.images.left, scooter.images.right, scooter.images.front).firstOrNull { it?.startsWith("https://") == true }
        if (image != null) AsyncImage(model = image, contentDescription = "${scooter.name} scooter", modifier = Modifier.fillMaxSize().padding(8.dp))
        else Text("Scooter", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val color = scooter.colorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        if (color != null) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(14.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun ScooterTile(scooter: ScooterSummary, isDefault: Boolean, enabled: Boolean, onClick: () -> Unit, placeLabel: String, modifier: Modifier = Modifier) {
    val online = scooter.online == true
    val accessibleName = "Send $placeLabel to ${scooter.name}"
    Card(
        modifier.semantics { contentDescription = accessibleName }.clickable(enabled = enabled && online, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScooterArt(scooter, Modifier.fillMaxWidth().height(126.dp))
            Text(scooter.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            scooter.model?.displayName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            scooter.licensePlate?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (online) "Online" else if (scooter.online == false) "Offline · refresh or choose another" else "Status unknown", style = MaterialTheme.typography.bodySmall,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (isDefault) Text("DEFAULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!online) Text("Not available to send", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
