package dev.ligustah.lsnav

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: SettingsViewModel = viewModel(), onSignIn: (String, String) -> Unit = { _, _ -> }, onBack: () -> Unit = {}, sharedText: String? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var ready by rememberSaveable { mutableStateOf(false) }
    var selectedInitially by rememberSaveable(state.profile.configuration.accountFingerprint()) { mutableStateOf(false) }
    val authenticated = state.profile.configuration.isAuthenticated
    val onboarding = !state.profile.onboardingComplete
    LaunchedEffect(authenticated) { if (!authenticated) ready = false }
    LaunchedEffect(state.scooters.value, onboarding) {
        if (onboarding && state.scooters.value != null && !selectedInitially) {
            selectedInitially = true
            if (state.scooters.value!!.size == 1) model.select(state.scooters.value!!.single()) else model.chooseOnDemand()
        }
    }
    val login = { if (model.beginLogin()) onSignIn(state.draft.baseUrl, state.draft.oauthClientId) }
    BackHandler(onboarding && ready) { ready = false }
    if (onboarding) {
        val step = if (!authenticated) 0 else if (ready) 2 else 1
        AnimatedContent(step, transitionSpec = { (slideInHorizontally(tween(220, easing = SunshineEase)) { it / 16 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(220)) { -it / 16 } + fadeOut(tween(220))) }, label = "setup") { current ->
            when (current) {
                0 -> WelcomeScreen(sharedText, state.busy, state.error, login, { advanced = true })
                1 -> SetupScootersScreen(state, model::select, model::chooseOnDemand, { ready = true }, { model.fetch(true) }, login)
                else -> ReadyScreen(state.busy, state.error, { ready = false }, model::save)
            }
        }
    } else {
        AccountScreen(state, onBack, model::select, model::chooseOnDemand, model::save, login, { advanced = true }, model::signOut, { model.fetch(true) })
    }
    if (advanced) ModalBottomSheet(onDismissRequest = { advanced = false }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            ConnectionFields(state, model, if (authenticated && state.profile.configuration.oauthSessionId != null) login else null)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun StepDots(step: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { Box(Modifier.size(if (it == step) 8.dp else 6.dp).background(if (it == step) Color(0xFF818CF8) else Color(0xFF9CA3AF).copy(alpha = .4f), CircleShape)) } }
}

@Composable
fun WelcomeScreen(sharedText: String?, busy: Boolean = false, error: String? = null, onContinue: () -> Unit = {}, onAdvanced: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Indigo800, Color(0xFF312E81)))).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StepDots(0, Modifier.padding(top = 20.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(224.dp).background(Brush.radialGradient(listOf(Color(0xFF818CF8).copy(alpha = .25f), Color.Transparent))), contentAlignment = Alignment.Center) { SunMark(Modifier.size(128.dp), animated = true) }
            Text("Sunshine Navigator", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Send places from any map app straight to your scooter's dashboard.", Modifier.padding(top = 16.dp), color = Color(0xFFA5B4FC), fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        sharedText?.let { SharedPlacePreviewCard(it, "We'll keep this place for you.") }
        Text("Nothing is sent during setup.", Modifier.padding(vertical = 16.dp), fontSize = 12.sp, color = Color(0xFFA5B4FC))
        error?.let { Text(it, Modifier.padding(bottom = 12.dp), color = Color(0xFFFCA5A5)) }
        SunshineButton(onContinue, Modifier.fillMaxWidth(), enabled = !busy, tone = ButtonTone.Welcome) { Text("Continue with Sunshine") }
        TextButton(onAdvanced, enabled = !busy) { Text("Other server or API token", color = Color(0xFFA5B4FC)) }
        if (busy) TurboBar()
    }
}

@Composable
fun SetupScootersScreen(state: SettingsState, onSelect: (ScooterSummary) -> Unit = {}, onAsk: () -> Unit = {}, onContinue: () -> Unit = {}, onRefresh: () -> Unit = {}, onReconnect: () -> Unit = {}) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        NavigatorBar(loading = state.scooters.loading || state.busy)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.TopCenter).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepDots(1)
            Text("Pick your scooter", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Use one by default, or choose each time you send.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state.scooters.loading && state.scooters.value == null -> { ScooterSkeletons(); ScooterSkeletons() }
                state.scooters.error != null -> ProblemPanel("Couldn't load your scooters", state.scooters.error, if (state.scooterReconnectRequired) "Reconnect" else "Retry", if (state.scooterReconnectRequired) onReconnect else onRefresh)
                state.scooters.value?.isEmpty() == true -> {
                    EmptyPlaces("No scooters on this account yet")
                    SunshineButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.profile.configuration.baseUrl.removeSuffix("/api/v1").trimEnd('/') + "/scooters"))) }, tone = ButtonTone.Secondary) { Text("Open Sunshine") }
                    TextButton(onRefresh) { Text("Refresh") }
                }
                else -> ScooterPicker(state.scooters.value.orEmpty(), !state.busy, state.draft.scooterId, onSelect, selectionMode = true)
            }
            SunshineCard(Modifier.fillMaxWidth().clickable(enabled = !state.busy, onClick = onAsk), panel = true, accent = if (state.draft.scooterId == null) Color(0xFF4F46E5) else null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.GridView, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Text("Ask every time", fontWeight = FontWeight.SemiBold) }; Text("Choose a scooter each time you send.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp)) {
            SunshineButton(onContinue, enabled = !state.busy && state.scooters.value != null && state.scooters.error == null, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
        }
    }
}

@Composable
fun ReadyScreen(busy: Boolean = false, error: String? = null, onBack: (() -> Unit)? = null, onStart: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        NavigatorBar(loading = busy, onBack = onBack)
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepDots(2, Modifier.padding(top = 12.dp))
            Column(Modifier.weight(1f).padding(top = 72.dp), verticalArrangement = Arrangement.Top, horizontalAlignment = Alignment.CenterHorizontally) {
                ReadyIllustration(Modifier.fillMaxWidth().height(216.dp))
                Spacer(Modifier.height(24.dp))
                Text("You're set", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("In Google Maps or any map app, tap Share, then Send to scooter. Or search right here.", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            error?.let { Text(it, Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.error) }
            SunshineButton(onStart, Modifier.fillMaxWidth(), enabled = !busy) { Text("Start navigating") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountScreen(state: SettingsState, onBack: () -> Unit = {}, onSelect: (ScooterSummary) -> Unit = {}, onAsk: () -> Unit = {}, onSave: () -> Unit = {}, onReconnect: () -> Unit = {}, onConnection: () -> Unit = {}, onSignOut: () -> Unit = {}, onRefresh: () -> Unit = {}) {
    val configuration = state.profile.configuration
    Column(Modifier.fillMaxSize()) {
        NavigatorBar("Settings", state.busy || state.scooters.loading, onBack)
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SunshineCard(panel = true) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Account", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(Color(0xFF6B7280), CircleShape), contentAlignment = Alignment.Center) { Text("S", color = Color.White, fontWeight = FontWeight.Bold) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Uri.parse(configuration.baseUrl).host ?: configuration.baseUrl, fontWeight = FontWeight.SemiBold)
                        Text(if (configuration.oauthSessionId != null) "Sunshine sign-in" else "API token", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (configuration.canRead) SunshineBadge("Read", BadgeTone.Success)
                    if (configuration.canManageFavorites) SunshineBadge("Favourites", BadgeTone.Success)
                    if (configuration.canControlScooter) SunshineBadge("Scooter control", BadgeTone.Success)
                    if (configuration.oauthSessionId == null) SunshineBadge("Token access not verified", BadgeTone.Neutral)
                }
                if (configuration.needsPermissionUpgrade) { SunshineBadge("Additional access needed", BadgeTone.Warning); SunshineButton(onReconnect) { Text("Reconnect") } }
            } }
            SunshineCard(panel = true) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default scooter", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                DefaultScooterOptions(state.scooters.value.orEmpty(), state.draft.scooterId, !state.busy, onSelect, onAsk)
                state.scooters.error?.let { ProblemPanel("Couldn't load your scooters", it, if (state.scooterReconnectRequired) "Reconnect" else "Retry", if (state.scooterReconnectRequired) onReconnect else onRefresh) }
                SunshineButton(onSave, enabled = !state.busy) { Text("Save preference") }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            } }
            SunshineCard(Modifier.fillMaxWidth(), panel = true) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onConnection).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Link, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Connection", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SunshineCard(Modifier.fillMaxWidth(), panel = true) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("About", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Sunshine Navigator · ${BuildConfig.VERSION_NAME}"); Text("Uses Sunshine by rescoot", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Maps © OpenStreetMap contributors", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            SunshineButton(onSignOut, enabled = !state.busy, tone = ButtonTone.Danger) { Text("Sign out") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ConnectionFields(state: SettingsState, model: SettingsViewModel, reconnect: (() -> Unit)? = null) {
    OutlinedTextField(state.draft.baseUrl, model::updateUrl, label = { Text("Sunshine server (HTTPS)") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state.draft.oauthClientId, model::updateClientId, label = { Text("Public OAuth client ID") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (reconnect != null) SunshineButton(reconnect, enabled = !state.busy) { Text("Reconnect with Sunshine") }
    HorizontalDivider()
    Text("Manual token", fontWeight = FontWeight.SemiBold)
    Text("Use a token from a trusted Sunshine server. It stays in this app's private, non-backed-up settings storage.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(state.draft.token, model::updateToken, label = { Text("API token") }, visualTransformation = PasswordVisualTransformation(), enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    SunshineButton(model::connectManual, enabled = !state.busy && state.draft.token.isNotBlank(), tone = ButtonTone.Secondary) { Text("Connect with token") }
}

@Composable
private fun DefaultScooterOptions(scooters: List<ScooterSummary>, selected: Long?, enabled: Boolean, onSelect: (ScooterSummary) -> Unit, onAsk: () -> Unit) {
    val options = scooters + listOf<ScooterSummary?>(null)
    run {
        val columns = when {
            LocalDensity.current.fontScale >= 1.3f -> 1
            options.size > 4 -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { scooter -> PreferenceChip(scooter, scooter?.id == selected, enabled, { if (scooter == null) onAsk() else onSelect(scooter) }, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PreferenceChip(scooter: ScooterSummary?, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    SunshineCard(modifier.clickable(enabled = enabled, onClick = onClick), panel = true, accent = if (selected) Color(0xFF4F46E5) else null) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (scooter != null) ScooterArt(scooter, Modifier.fillMaxWidth().height(48.dp))
            else Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.GridView, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
            Text(scooter?.name ?: "Ask every time", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
