package dev.ligustah.lsnav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h568dp")
class PlaceSurfacesTest {
    @get:Rule val compose = createComposeRule()
    private val place = PlaceResult(Coordinates(52.5, 13.4), "Home")

    @Test fun editorActionsRemainReachableAtLargeFontAndCancelDoesNotSave() {
        var saved = 0
        var cancelled = 0
        compose.setContent {
            NavigationTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    SunshineSheet({}) {
                        PlaceEditorContent("Home", place, title = "Edit place", onSave = { saved++ }, onCancel = { cancelled++ }, actions = { PlaceEditorActions(true) })
                    }
                }
            }
        }
        compose.onNodeWithText("Delete saved place").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }

    @Test fun savingDisablesBothGroupedActions() {
        compose.setContent { NavigationTheme { PlaceEditorActions(true, enabled = false) } }
        compose.onNodeWithText("Remove from favourites").assertIsNotEnabled()
        compose.onNodeWithText("Delete saved place").assertIsNotEnabled()
    }

    @Test fun emptyScooterPlacesAreNotIndicatedAsStillLoading() {
        val state = NavigationState(configuration = AppConfiguration("token", "https://example.org", 1, "Scooter"), pendingCommandLoading = false,
            destination = RemoteData.loaded(NavigationTarget()), savedLocations = RemoteData.loaded(emptyList()), personalPlaces = RemoteData.loaded(PersonalPlaces()))
        compose.setContent { NavigationTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { HomeScreen(state) } } }
        compose.onNodeWithText("No saved places on this scooter.").assertExists()
        compose.onNodeWithText("Loading scooter places…").assertDoesNotExist()
    }
}
