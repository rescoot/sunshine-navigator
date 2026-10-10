package dev.ligustah.lsnav

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

val Indigo800 = Color(0xFF3730A3)
val Indigo950 = Color(0xFF1E1B4B)
val SunshineEase = CubicBezierEasing(0f, .6f, .2f, 1f)

@Composable
fun motionEnabled(): Boolean = Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

@Composable
fun SunMark(modifier: Modifier = Modifier, animated: Boolean = false) {
    val animate = animated && motionEnabled()
    var rotation = 0f
    var scale = 1f
    if (animate) {
        val transition = rememberInfiniteTransition(label = "sun")
        rotation = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(40000, easing = LinearEasing)), label = "rotation").value
        scale = transition.animateFloat(1f, 1.03f, infiniteRepeatable(tween(3000, easing = SunshineEase), RepeatMode.Reverse), label = "breathing").value
    }
    Image(painterResource(R.drawable.sun_mark), "Sunshine", modifier.rotate(rotation).scale(scale))
}

enum class ButtonTone { Primary, Secondary, Danger, Welcome }

@Composable
fun SunshineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tone: ButtonTone = ButtonTone.Primary, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val down by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val dark = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
    val colors = when {
        !enabled -> listOf(Color(0xFF9CA3AF), Color(0xFF6B7280))
        tone == ButtonTone.Welcome -> listOf(Color.White, Color(0xFFEEF2FF))
        tone == ButtonTone.Danger -> listOf(Color(0xFFEF4444), Color(0xFFDC2626))
        tone == ButtonTone.Secondary -> if (dark) listOf(Color(0xFF2A2944), Color(0xFF1A1933)) else listOf(Color.White, Color(0xFFF9FAFB))
        else -> listOf(Color(0xFF6366F1), Color(0xFF4F46E5))
    }
    val border = when (tone) { ButtonTone.Danger -> Color(0xFF991B1B); ButtonTone.Secondary -> Color(0xFF9CA3AF); ButtonTone.Welcome -> Indigo950.copy(alpha = .4f); else -> Indigo800 }
    val foreground = when (tone) { ButtonTone.Welcome -> Indigo800; ButtonTone.Secondary -> MaterialTheme.colorScheme.onSurface; else -> Color.White }
    Box(modifier.heightIn(min = 48.dp).border(if (focused) 2.dp else if (tone == ButtonTone.Secondary) 1.dp else 0.dp, if (focused) Color(0xFF818CF8) else if (tone == ButtonTone.Secondary) Color(0xFFD1D5DB) else Color.Transparent, RoundedCornerShape(6.dp)).offset(y = down.dp).alpha(if (enabled) 1f else .5f).shadow(if (pressed) 0.dp else 2.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(border)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick).padding(bottom = (4 - 2 * down).dp)) {
        CompositionLocalProvider(LocalContentColor provides foreground, LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(colors)).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
fun SunshineCard(modifier: Modifier = Modifier, panel: Boolean = false, accent: Color? = null, containerColor: Color? = null, elevated: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
    Surface(modifier, shape = RoundedCornerShape(if (panel) 12.dp else 8.dp), color = containerColor ?: MaterialTheme.colorScheme.surface,
        border = if (accent != null || panel || dark) BorderStroke(if (accent != null) 2.dp else 1.dp, accent ?: if (dark) Color.White.copy(alpha = .06f) else Color(0xFFE5E7EB)) else null,
        shadowElevation = if (dark) 0.dp else if (panel && !elevated) 1.dp else 4.dp) { Column(content = content) }
}

enum class BadgeTone(val color: Color) { Success(Color(0xFF15803D)), Info(Color(0xFF1D4ED8)), Warning(Color(0xFFA16207)), Error(Color(0xFFB91C1C)), Neutral(Color(0xFF6B7280)), Indigo(Color(0xFF4338CA)) }

@Composable
fun SunshineBadge(text: String, tone: BadgeTone = BadgeTone.Indigo) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
    val textColor = if (dark) when(tone) { BadgeTone.Success -> Color(0xFF86EFAC); BadgeTone.Warning -> Color(0xFFFDE047); BadgeTone.Error -> Color(0xFFFCA5A5); BadgeTone.Info -> Color(0xFF93C5FD); BadgeTone.Indigo -> Color(0xFFA5B4FC); BadgeTone.Neutral -> Color(0xFFD1D5DB) } else tone.color
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(tone.color.copy(alpha = if (dark) .15f else .07f)).border(1.dp, tone.color.copy(alpha = .2f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun TurboBar(modifier: Modifier = Modifier) {
    var travel = .5f
    if (motionEnabled()) {
        val transition = rememberInfiniteTransition(label = "turbo")
        travel = transition.animateFloat(-.3f, 1f, infiniteRepeatable(tween(1400, easing = SunshineEase)), label = "travel").value
    }
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val left = size.width * travel
        val right = left + size.width * .4f
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF06B6D4), Color(0xFF22D3EE), Color(0xFF2DD4BF))), topLeft = androidx.compose.ui.geometry.Offset(left, 0f), size = androidx.compose.ui.geometry.Size(size.width * .4f, size.height))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF22D3EE).copy(alpha = .7f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(right, size.height / 2), radius = 12.dp.toPx()), 12.dp.toPx(), androidx.compose.ui.geometry.Offset(right, size.height / 2))
    }
}

@Composable
fun NavigatorBar(title: String = "Navigator", loading: Boolean = false, onBack: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null, onAccount: (() -> Unit)? = null, hero: Boolean = false) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF0F0E1F)
    Column(Modifier.fillMaxWidth().background(if (dark) Indigo950 else Indigo800).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } else SunMark(Modifier.padding(6.dp).size(22.dp))
            Text(title, Modifier.weight(1f).padding(start = 6.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (onRefresh != null) IconButton(onRefresh) { Icon(Icons.Default.Refresh, "Refresh places and scooters", tint = Color.White) }
            if (onAccount != null) IconButton(onAccount) { AccountAvatar(LocalAccountIdentity.current, Modifier.size(32.dp).semantics { contentDescription = "Account and settings" }) }
        }
        if (!hero) Box(Modifier.fillMaxWidth().height(2.dp).background(if (dark) Indigo800 else Color(0xFF4338CA)))
        if (loading) TurboBar()
    }
}

@Composable
fun ProblemPanel(title: String, body: String, action: String, onAction: () -> Unit, tone: BadgeTone = BadgeTone.Error) {
    SunshineCard(Modifier.fillMaxWidth(), panel = true) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tone.color))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.ErrorOutline, null, tint = tone.color, modifier = Modifier.size(22.dp)); Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SunshineButton(onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    var offset = .5f
    if (motionEnabled()) {
        val transition = rememberInfiniteTransition(label = "shimmer")
        offset = transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1500)), label = "offset").value
    }
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(base, MaterialTheme.colorScheme.surface, base), start = androidx.compose.ui.geometry.Offset(offset * 500, 0f), end = androidx.compose.ui.geometry.Offset(offset * 500 + 250, 200f))))
}

@Composable
fun EmptyPlaces(text: String = "Share a place from your map app, or search above.") {
    SunshineCard(Modifier.fillMaxWidth(), panel = true) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { SunMark(Modifier.size(48.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
fun AppearingItem(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animate = motionEnabled()
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) { kotlinx.coroutines.delay(index * 24L); progress.animateTo(1f, tween(220, easing = SunshineEase)) }
    }
    Box(modifier.alpha(progress.value).offset(y = ((1 - progress.value) * 12).dp)) { content() }
}

@Composable
fun ResultIcon(tone: BadgeTone = BadgeTone.Success) {
    val animate = motionEnabled()
    val scale = remember { Animatable(if (animate) .6f else 1f) }
    LaunchedEffect(Unit) { if (animate) scale.animateTo(1f, spring(dampingRatio = .55f, stiffness = 400f)) }
    Icon(when (tone) { BadgeTone.Success -> Icons.Default.CheckCircle; BadgeTone.Warning -> Icons.Default.Help; else -> Icons.Default.Error }, null, Modifier.size(24.dp).scale(scale.value), tint = when (tone) { BadgeTone.Success -> Color(0xFF22C55E); BadgeTone.Warning -> Color(0xFFEAB308); else -> Color(0xFFEF4444) })
}
