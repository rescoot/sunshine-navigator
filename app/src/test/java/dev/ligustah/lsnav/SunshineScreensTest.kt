package dev.ligustah.lsnav

import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SunshineScreensTest(private val scene: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class, com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun sunshineScreen() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val loader = ImageLoader.Builder(context).okHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val file = if (path.startsWith("/scooters/")) path.removePrefix("/") else "map_tile.png"
            val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream(file)).use { it.readBytes() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()).build()
        val scooters = listOf(
            ScooterSummary(1, "Bluebird", true, "parked", model = ScooterModelInfo(fullName = "unu · 4 kW"), licensePlate = "B · SC 42", images = ScooterImages(right = "https://art.test/scooters/left_side_3.png")),
            ScooterSummary(2, "Clementine", false, "hibernating", model = ScooterModelInfo(modelName = "unu · 3 kW"), licensePlate = "HH · 108", lastSeenAt = java.time.Instant.now().minusSeconds(12600).toString(), images = ScooterImages(right = "https://art.test/scooters/left_side_5.png")),
            ScooterSummary(3, "Cloud with a longer name", true, "stand-by", images = ScooterImages(right = "https://art.test/scooters/left_side_0.png")),
            ScooterSummary(4, "Evening ride", null, images = ScooterImages(right = "https://art.test/scooters/left_side_5.png")),
            ScooterSummary(5, "Red", true, "parked", images = ScooterImages(right = "https://art.test/scooters/left_side_3.png")),
            ScooterSummary(6, "Hubert", false, images = ScooterImages(right = "https://art.test/scooters/left_side_0.png")),
            ScooterSummary(7, "Benchtop Reddish", false, images = ScooterImages(right = "https://art.test/scooters/left_side_5.png"))
        )
        val config = AppConfiguration(baseUrl = "https://sunshine.example", scooterId = 1, scooterName = "Bluebird", oauthSessionId = "fixture", grantedScopes = setOf("read", "write", "scooter_control"))
        val place = PlaceResult(Coordinates(52.5145, 13.3501), "Café am Tiergarten, Straße des 17. Juni, Berlin")
        val home = PlaceResult(Coordinates(52.529, 13.401), "Home")
        val park = PlaceResult(Coordinates(52.49, 13.41), "Tempelhofer Feld")
        val state = NavigationState(configuration = config, configurationLoaded = true, scooters = RemoteData.loaded(scooters), defaultScooterId = 1, pendingCommandLoading = false,
            destination = RemoteData.loaded(NavigationTarget(place)), savedLocations = RemoteData.loaded(listOf(home)), personalPlaces = RemoteData.loaded(PersonalPlaces(listOf(SavedPlace(1, home), SavedPlace(2, park)), listOf(SavedPlace(3, place, false)))))
        val setup = SettingsState(initialized = true, profile = AppProfile(config), draft = config, scooters = RemoteData.loaded(scooters), account = RemoteData.loaded(AccountIdentity(1, "Alex Rider", "alex@example.org")))
        val target = CommandTarget(config, place)
        compose.setContent {
            CompositionLocalProvider(LocalArtworkLoader provides loader) {
                NavigationTheme(dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (scene) {
                            "welcome" -> WelcomeScreen(null)
                            "welcome_share" -> WelcomeScreen("geo:52.5145,13.3501?q=52.5145,13.3501(Caf%C3%A9%20am%20Tiergarten)")
                            "scooters" -> SetupScootersScreen(setup)
                            "scooters_error" -> SetupScootersScreen(setup.copy(scooters = RemoteData(error = "Couldn't reach Sunshine. Check your connection and retry.")))
                            "scooters_empty" -> SetupScootersScreen(setup.copy(scooters = RemoteData.loaded(emptyList())))
                            "ready" -> ReadyScreen()
                            "home" -> HomeScreen(state)
                            "home_empty" -> HomeScreen(state.copy(destination = RemoteData.loaded(NavigationTarget()), savedLocations = RemoteData.loaded(emptyList()), personalPlaces = RemoteData.loaded(PersonalPlaces())))
                            "search" -> HomeScreen(state, query = "Tiergarten", results = listOf(place, park))
                            "send_idle" -> SendScreen(state, place, shared = true, enabled = true)
                            "send_sending" -> SendScreen(state.copy(sending = true), place, shared = true, sendingTarget = target)
                            "send_accepted" -> SendScreen(state, place, acceptedTarget = target, enabled = true)
                            "send_uncertain" -> {
                                val uncertain = state.copy(uncertainTarget = target)
                                SendScreen(uncertain, place, recovery = { UncertainPanel(uncertain, docked = true) })
                            }
                            "send_failed" -> {
                                val failed = state.copy(error = "This account lacks permission for Bluebird. Check its sharing permissions.", uncertainTarget = target)
                                SendScreen(failed, place, recovery = { UncertainPanel(failed, docked = true) })
                            }
                            "settings" -> AccountScreen(setup.copy(profile = AppProfile(config, true)))
                            "editor" -> {
                                HomeScreen(state)
                                ModalBottomSheet(onDismissRequest = {}) { PlaceEditorContent("Café am Tiergarten", place) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        val directory = File("build/outputs/roborazzi").apply { mkdirs() }
        val path = File(directory, "${scene}_${if (dark) "dark" else "light"}.png").path
        if (scene == "editor") captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
        loader.shutdown()
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}") fun scenes(): List<Array<Any>> = listOf("welcome", "welcome_share", "scooters", "scooters_error", "scooters_empty", "ready", "home", "home_empty", "search", "send_idle", "send_sending", "send_accepted", "send_uncertain", "send_failed", "settings", "editor").flatMap { scene -> listOf(arrayOf(scene, false), arrayOf(scene, true)) }
    }
}
