package dev.ligustah.lsnav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

internal enum class PlateKind { Insurance, Registration, Other }
internal data class PlateLayout(val text: String, val kind: PlateKind, val top: String = "", val bottom: String = "", val suffix: String? = null, val ink: Color = Color(0xFF1A1A1A), val expired: Boolean = false)
internal fun plateSeason(date: LocalDate) = if (date.monthValue >= 3) date.year else date.year - 1
internal fun ScooterSummary.plateLayout(today: LocalDate = LocalDate.now(ZoneId.of("Europe/Berlin"))): PlateLayout? {
    val text = licensePlate?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    if (licensePlateType == "versicherungskennzeichen") {
        val match = Regex("^(\\d{1,3})([A-Z]{1,3})$").matchEntire(text.replace(Regex("[\\s·-]"), ""))
            ?: return PlateLayout(text, PlateKind.Other)
        val date = licensePlateSetAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.of("Europe/Berlin")).toLocalDate() }.getOrNull() } ?: today
        val ink = when (plateSeason(date) % 3) { 0 -> Color(0xFF1A6E1A); 1 -> Color(0xFF1A1A1A); else -> Color(0xFF1A3A8A) }
        return PlateLayout(text, PlateKind.Insurance, match.groupValues[1], match.groupValues[2], ink = ink, expired = plateSeason(date) < plateSeason(today))
    }
    if (licensePlateType == "zulassungskennzeichen") {
        val tokens = text.split(Regex("[\\s·]+"))
        if (tokens.size < 2) return PlateLayout(text, PlateKind.Other)
        val suffix = tokens.last().takeIf { tokens.size >= 4 && it in setOf("E", "H") }
        return PlateLayout(text, PlateKind.Registration, tokens.first(), tokens.drop(1).dropLast(if (suffix == null) 0 else 1).joinToString(" "), suffix)
    }
    return PlateLayout(text, PlateKind.Other)
}

@Composable
fun LicensePlateBadge(scooter: ScooterSummary, modifier: Modifier = Modifier) {
    val plate = scooter.plateLayout() ?: return
    val style = TextStyle(fontFamily = FontFamily(Font(R.font.plate)), fontSize = 20.sp, lineHeight = 18.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false), color = plate.ink)
    Row(modifier.clearAndSetSemantics { contentDescription = "License plate ${plate.text}${if (plate.expired) ", expired insurance season" else ""}" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.alpha(if (plate.expired) .6f else 1f), color = Color.White,
            shape = RoundedCornerShape(4.dp), border = BorderStroke(if (plate.kind == PlateKind.Insurance) 2.dp else 1.dp, plate.ink)) {
            when (plate.kind) {
                PlateKind.Insurance -> Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(plate.top, style = style, maxLines = 1)
                    Text(plate.bottom, style = style, maxLines = 1)
                }
                PlateKind.Registration -> Column(Modifier.width(IntrinsicSize.Max).padding(1.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(12.dp).height(18.dp).background(Color(0xFF003399)), contentAlignment = Alignment.Center) { Text("D", color = Color.White, fontSize = 8.sp) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(plate.top, style = style, maxLines = 1) }
                    }
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(plate.bottom, style = style, maxLines = 1)
                        plate.suffix?.let { Text(it, Modifier.background(if (it == "E") Color(0xFF00823A) else Color(0xFFD4A800), RoundedCornerShape(2.dp)).padding(horizontal = 2.dp), color = if (it == "E") Color.White else Color.Black, fontSize = 10.sp, lineHeight = 12.sp) }
                    }
                }
                PlateKind.Other -> Text(plate.text, Modifier.padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFF1F2937), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            }
        }
        if (plate.expired) Text("Expired", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
    }
}
