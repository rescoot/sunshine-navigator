package dev.ligustah.lsnav

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Screen { Navigation, Settings }

class SettingsActivity : ComponentActivity() {
    private val settingsModel: SettingsViewModel by viewModels()
    private val authorizationService by lazy { AuthorizationService(this) }
    private val sharedInput = mutableStateOf<String?>(null)
    private val authorizationResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsModel.completeLogin(it.data)
    }

    private fun receiveShare(intent: Intent?) {
        sharedInput.value = runCatching { intent?.let(MapsIntentParser::extractIntentText) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MapsIntentParser.MAX_INPUT_LENGTH }
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { authorizationService.dispose(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveShare(intent)
        else sharedInput.value = savedInstanceState.getString("pending_shared_text")
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        configureNavigationWindow(window, dark)
        setContent {
            val settings by settingsModel.state.collectAsStateWithLifecycle()
            NavigationTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Navigation) }
                val onboarding = !settings.profile.onboardingComplete
                val destination = if (onboarding) Screen.Settings else screen
                BackHandler(enabled = destination == Screen.Settings && !onboarding) { screen = Screen.Navigation }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        when (destination) {
                            Screen.Navigation -> NavigationScreen(
                                onSettings = { screen = Screen.Settings },
                                onDone = { sharedInput.value = null },
                                sharedText = sharedInput.value,
                                onShareConsumed = { sharedInput.value = null }
                            )
                            Screen.Settings -> SettingsScreen(settingsModel, ::signIn, { screen = Screen.Navigation }, sharedInput.value)
                        }
                    }
                }
            }
        }
    }
}
