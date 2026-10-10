package dev.ligustah.lsnav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ResultDock(tone: BadgeTone, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
    val color = when (tone) {
        BadgeTone.Success -> if (dark) Color(0xFF162D29) else Color(0xFFF0FDF4)
        BadgeTone.Warning -> if (dark) Color(0xFF29251F) else Color(0xFFFEFCE8)
        else -> if (dark) Color(0xFF331E2B) else Color(0xFFFEF2F2)
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(32.dp).background(Brush.verticalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = .85f)))))
        Surface(Modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 12.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 3.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f), RoundedCornerShape(2.dp)))
                content()
            }
        }
    }
}
