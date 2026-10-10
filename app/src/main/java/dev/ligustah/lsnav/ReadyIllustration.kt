package dev.ligustah.lsnav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable
fun ReadyIllustration(modifier: Modifier = Modifier) {
    val indigo = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(size.width * .34f, size.height * .52f)
                cubicTo(size.width * .44f, size.height * .98f, size.width * .53f, size.height * .98f, size.width * .65f, size.height * .64f)
            }
            drawPath(path, indigo, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp).width(132.dp).height(184.dp).shadow(6.dp, RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(28.dp, 3.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Default.Share, null, Modifier.size(16.dp), tint = indigo); Text("Share place", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(4) { Box(Modifier.size(17.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) } }
            Column(Modifier.fillMaxWidth().background(indigo.copy(alpha = .08f), RoundedCornerShape(10.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(40.dp).background(Indigo800, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { SunMark(Modifier.size(28.dp)) }
                Text("Send to scooter", color = indigo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).width(150.dp).height(110.dp).shadow(6.dp, RoundedCornerShape(14.dp)).background(Color(0xFF111827), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Navigation, null, Modifier.size(30.dp), tint = Color.White)
                Text("350 m", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Text("Potsdamer Straße", color = Color(0xFFD1D5DB), fontSize = 11.sp)
            Box(Modifier.fillMaxWidth(.6f).height(3.dp).background(Color(0xFF6366F1), RoundedCornerShape(2.dp)))
        }
        Icon(Icons.Default.Navigation, null, Modifier.offset(x = maxWidth * .65f - 12.dp, y = maxHeight * .64f - 12.dp).size(24.dp).rotate(38f), tint = indigo)
    }
}
