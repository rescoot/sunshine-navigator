package dev.ligustah.lsnav

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SharedPlacePreviewCard(text: String?, supportingText: String) {
    val preview = remember(text) { text?.takeIf(String::isNotBlank)?.let(::parseSharedPlacePreview) } ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = .12f), contentColor = androidx.compose.ui.graphics.Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.Place, null, Modifier.size(22.dp), tint = androidx.compose.ui.graphics.Color(0xFFA5B4FC)); Text("SHARED PLACE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFFA5B4FC)) }
            Text(preview.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            preview.coordinates?.let { Text(it.display(), style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFA5B4FC)) }
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFA5B4FC))
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
