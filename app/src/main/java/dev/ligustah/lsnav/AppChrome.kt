package dev.ligustah.lsnav

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary) {
            Box(contentAlignment = Alignment.Center) {
                Text("LS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("LSNav", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("SUNSHINE NAVIGATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SharedPlacePreviewCard(text: String?, supportingText: String) {
    val preview = remember(text) { text?.takeIf(String::isNotBlank)?.let(::parseSharedPlacePreview) } ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SHARED PLACE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(preview.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            preview.coordinates?.let { Text(it.display(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class SharedPlacePreview(val label: String, val coordinates: Coordinates?)

fun parseSharedPlacePreview(text: String): SharedPlacePreview {
    val input = MapsIntentParser.extractUrlOrText(text)
    val uri = Uri.parse(input)
    val coordinates = MapsIntentParser.extractCoordinates(uri) ?: MapsIntentParser.parsePair(input)
    val label = MapsIntentParser.extractPlaceName(uri)?.takeIf(String::isNotBlank)
        ?: coordinates?.display()
        ?: if (uri.scheme == null && uri.host == null) Uri.decode(input).replace('+', ' ').trim() else "Shared map place"
    return SharedPlacePreview(label, coordinates)
}
