package dev.ligustah.lsnav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

val LocalAccountIdentity = staticCompositionLocalOf<AccountIdentity?> { null }

@Composable
fun AccountAvatar(identity: AccountIdentity?, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).background(Color(0xFF6366F1)), contentAlignment = Alignment.Center) {
        val initial = identity?.name?.firstOrNull()?.uppercase()
        if (initial != null) Text(initial, color = Color.White) else Icon(Icons.Default.Person, null, tint = Color.White)
        identity?.avatarUrl?.takeIf { it.startsWith("https://") }?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
