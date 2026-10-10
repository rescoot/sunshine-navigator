package dev.ligustah.lsnav

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Screen { Navigation, Settings }

class SettingsActivity : ComponentActivity() {
    private val settingsModel: SettingsViewModel by viewModels()
    private val navigationModel: NavigationViewModel by viewModels()
    private val authorizationService by lazy { AuthorizationService(this) }
    private val sharedInput = mutableStateOf<String?>(null)
    private val sharedRequestId = mutableStateOf<Long?>(null)
    private var nextSharedRequestId = 0L
    private val sharedJourney = mutableStateOf(false)
    private val authorizationResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsModel.completeLogin(it.data)
    }

    private fun receiveShare(intent: Intent?) {
        val value = runCatching { intent?.let(MapsIntentParser::extractIntentText) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MapsIntentParser.MAX_INPUT_LENGTH }
        if (value != null) {
            nextSharedRequestId = Math.addExact(nextSharedRequestId, 1L)
            sharedRequestId.value = nextSharedRequestId
            navigationModel.beginIncomingRequest(nextSharedRequestId, requireResolution = true)
            sharedInput.value = value
            sharedJourney.value = true
        }
        else if (intent?.action == Intent.ACTION_MAIN) { sharedInput.value = null; sharedJourney.value = false }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun signIn(baseUrl: String, clientId: String) {
        lifecycleScope.launch {
            try {
                authorizationResult.launch(OAuthManager.get(this@SettingsActivity).authorizationIntent(baseUrl, clientId, authorizationService))
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) { settingsModel.authError(error) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_shared_text", sharedInput.value)
        outState.putLong("next_shared_request_id", nextSharedRequestId)
        outState.putLong("shared_request_id", sharedRequestId.value ?: 0L)
        outState.putBoolean("shared_journey", sharedJourney.value)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { authorizationService.dispose(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveShare(intent)
        else {
            sharedInput.value = savedInstanceState.getString("pending_shared_text")
            nextSharedRequestId = savedInstanceState.getLong("next_shared_request_id")
            sharedRequestId.value = savedInstanceState.getLong("shared_request_id").takeIf { it > 0L }
            sharedJourney.value = savedInstanceState.getBoolean("shared_journey")
        }
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        configureNavigationWindow(window, dark)
        setContent {
            val settings by settingsModel.state.collectAsStateWithLifecycle()
            NavigationTheme {
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark && (settings.profile.onboardingComplete || settings.profile.configuration.isAuthenticated)
                }
                val saveableStateHolder = rememberSaveableStateHolder()
                var screen by rememberSaveable { mutableStateOf(Screen.Navigation) }
                val onboarding = !settings.profile.onboardingComplete
                val destination = if (onboarding) Screen.Settings else screen
                BackHandler(enabled = destination == Screen.Settings && !onboarding) { screen = Screen.Navigation }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AnimatedContent(destination, transitionSpec = {
                        (slideInVertically(tween(320, easing = SunshineEase)) { it / 12 } + fadeIn(tween(220))) togetherWith fadeOut(tween(220))
                    }, label = "account") { current ->
                        when (current) {
                            Screen.Navigation -> saveableStateHolder.SaveableStateProvider(Screen.Navigation.name) {
                                NavigationScreen(
                                    onSettings = { screen = Screen.Settings },
                                    onDone = { if (sharedJourney.value) finish() },
                                    sharedText = sharedInput.value,
                                    incomingRequestId = sharedRequestId.value,
                                    onShareConsumed = { sharedInput.value = null }
                                )
                            }
                            Screen.Settings -> SettingsScreen(settingsModel, ::signIn, { screen = Screen.Navigation }, sharedInput.value)
                        }
                    }
                }
            }
        }
    }
}
