package dev.ligustah.lsnav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.OkHttpClient
import kotlin.math.*

val LocalArtworkLoader = staticCompositionLocalOf<ImageLoader?> { null }

internal fun PlaceResult.displayTitle() = label.substringBefore(", ")
internal fun PlaceResult.displayDetail() = if (label.contains(", ")) label.substringAfter(", ") else coordinates.display()

internal data class MapTile(val x: Int, val y: Int, val offsetX: Double, val offsetY: Double)
internal fun mapTiles(coordinates: Coordinates): List<MapTile> {
    val n = 65536.0
    val x = (coordinates.longitude + 180) / 360 * n
    val latitude = coordinates.latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
    val y = ((1 - ln(tan(latitude) + 1 / cos(latitude)) / PI) / 2 * n).coerceIn(0.0, n)
    val firstY = floor(y - .5).toInt().coerceIn(0, 65534)
    return (firstY..firstY + 1).flatMap { ty -> (floor(x).toInt() - 1..floor(x).toInt() + 1).map { tx -> MapTile((tx % 65536 + 65536) % 65536, ty, (tx - x) * 256, (ty - y) * 256) } }
}

@Composable
fun StaticDestinationMap(coordinates: Coordinates, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Artwork and public CARTO tiles never use the Sunshine bearer transport.
    val loader = LocalArtworkLoader.current ?: remember(context) { ImageLoader.Builder(context).okHttpClient(OkHttpClient()).build() }
    val dark = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
    val base = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val grid = 32.dp.toPx()
            for (x in 0..(size.width / grid).toInt()) drawLine(Color.Gray.copy(alpha = .1f), Offset(x * grid, 0f), Offset(x * grid, size.height))
            for (y in 0..(size.height / grid).toInt()) drawLine(Color.Gray.copy(alpha = .1f), Offset(0f, y * grid), Offset(size.width, y * grid))
        }
        val width = maxWidth
        mapTiles(coordinates).forEach { tile ->
            AsyncImage(model = ImageRequest.Builder(context).data("https://a.basemaps.cartocdn.com/${if (dark) "dark_all" else "light_all"}/16/${tile.x}/${tile.y}.png").crossfade(220).build(), imageLoader = loader, contentDescription = null,
                modifier = Modifier.offset(x = width / 2 + tile.offsetX.dp, y = 70.dp + tile.offsetY.dp).wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(256.dp))
        }
        Icon(Icons.Default.LocationOn, "Destination", tint = Color(0xFF4F46E5), modifier = Modifier.align(Alignment.Center).offset(y = (-16).dp).size(40.dp))
        Box(Modifier.align(Alignment.Center).offset(y = (-20).dp).size(8.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
        Text("© OpenStreetMap © CARTO", Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.White.copy(alpha = .7f), RoundedCornerShape(3.dp)).padding(3.dp), color = Color(0xFF374151), fontSize = 8.sp)
    }
}

@Composable
fun DestinationTicket(place: PlaceResult?, shared: Boolean = false, loading: Boolean = false, active: Boolean = false, onSave: () -> Unit = {}, onEdit: () -> Unit = {}) {
    SunshineCard(Modifier.fillMaxWidth(), panel = true, accent = if (active) Color(0xFF818CF8) else null, elevated = true) {
        if (place != null) StaticDestinationMap(place.coordinates) else Skeleton(Modifier.fillMaxWidth().height(140.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (loading) { Skeleton(Modifier.fillMaxWidth(.65f).height(22.dp)); Text("Finding this place…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (place != null) {
                Text(place.displayTitle(), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (place.label.contains(", ")) Text(place.displayDetail(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text(place.coordinates.display(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shared) SunshineBadge("Shared place")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onSave) { Text("☆ Save") }
                    TextButton(onEdit) { Text("Edit") }
                }
            }
        }
    }
}
