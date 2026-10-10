package dev.ligustah.lsnav

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class ScooterPickerTest {
    @get:Rule val compose = createComposeRule()
    private val online = ScooterSummary(1, "Bluebird", true)
    private val offline = ScooterSummary(2, "Clementine", false)

    @Test fun gridAlignsCardsWithDifferentNamesAndConnectivityWithoutModelNoise() {
        val first = online.copy(state = "stand-by", model = ScooterModelInfo(modelName = "Unneeded model", enginePowerRatingW = 4000), licensePlate = "176 JLG")
        val second = online.copy(id = 2, name = "A scooter with a longer name", state = "hibernating")
        compose.setContent { NavigationTheme { ScooterPicker(listOf(first, second), true, 1, {}) } }
        compose.waitForIdle()
        val a = compose.onNodeWithContentDescription("Send to Bluebird, online").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithContentDescription("Send to A scooter with a longer name, online").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top, b.top, 1f)
        assertEquals(a.bottom, b.bottom, 1f)
        assertEquals(a.width, b.width, 1f)
        compose.onNodeWithText("Unneeded model", substring = true).assertDoesNotExist()
        compose.onNodeWithText("kW", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Send to A scooter with a longer name, online").assertIsEnabled()
    }

    @Test fun preferenceListIsCompactAndIncludesAskEveryTime() {
        val fleet = (1L..5L).map { ScooterSummary(it, "Scooter $it", true) }
        compose.setContent { NavigationTheme { AccountScreen(SettingsState(initialized = true, scooters = RemoteData.loaded(fleet))) } }
        val a = compose.onNodeWithText("Scooter 1").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("Scooter 2").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithText("Scooter 3").fetchSemanticsNode().boundsInRoot
        assertTrue(b.top > a.bottom)
        assertTrue(c.top > b.bottom)
        compose.onNodeWithText("Ask every time").performScrollTo().assertIsDisplayed()
    }

    @Test fun unavailablePreferencesAreHiddenUntilExplicitlyExpanded() {
        val fleet = listOf(online, offline, ScooterSummary(3, "Read only", true, canNavigate = false))
        compose.setContent { NavigationTheme { AccountScreen(SettingsState(initialized = true, scooters = RemoteData.loaded(fleet))) } }
        compose.onNodeWithText("Clementine").assertDoesNotExist()
        compose.onNodeWithText("Read only").assertDoesNotExist()
        compose.onNodeWithText("Show unavailable scooters (2)").performScrollTo().performClick()
        compose.onNodeWithText("Clementine").assertExists()
        compose.onNodeWithText("Read only").assertExists()
    }

    @Test fun onlyOnlineExplicitTapSelectsSendTarget() {
        var selections = 0
        compose.setContent { NavigationTheme { ScooterPicker(listOf(online, offline), true, 1, { selections++ }) } }
        compose.waitForIdle()
        assertEquals(0, selections)
        compose.onNodeWithContentDescription("Send to Clementine, offline").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send to Bluebird, online").performClick()
        assertEquals(1, selections)
    }

    @Test fun preferenceSelectionDoesNotCompleteSetup() {
        var selections = 0
        var continues = 0
        compose.setContent { NavigationTheme { SetupScootersScreen(SettingsState(scooters = RemoteData.loaded(listOf(online, offline))), onSelect = { selections++ }, onContinue = { continues++ }) } }
        compose.onNodeWithContentDescription("Choose Bluebird as default").performScrollTo().performClick()
        assertEquals(1, selections)
        assertEquals(0, continues)
    }

    @Test fun acceptedScooterStaysAboveResultPanel() {
        val cloud = ScooterSummary(3, "Cloud", true)
        val configuration = AppConfiguration(token = "fixture", scooterId = cloud.id, scooterName = cloud.name)
        val place = PlaceResult(Coordinates(52.5, 13.3), "Café am Tiergarten")
        val target = CommandTarget(configuration, place)
        val state = NavigationState(configuration = configuration, scooters = RemoteData.loaded(listOf(online, offline, cloud)), defaultScooterId = online.id, pendingCommandLoading = false)
        compose.setContent { NavigationTheme { SendScreen(state, place, acceptedTarget = target) } }
        compose.waitForIdle()
        val card = compose.onNodeWithContentDescription("Send to Cloud, online").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Sent to Cloud").fetchSemanticsNode().boundsInRoot
        assertTrue("Accepted scooter must stay above the result panel", card.bottom < title.top)
        compose.onNodeWithContentDescription("Send to Cloud, online").assertIsDisplayed()
    }

    @Test fun pendingRecoveryDisablesEverySendCard() {
        compose.setContent { NavigationTheme { ScooterPicker(listOf(online, offline), false, 1, {}) } }
        compose.onNodeWithContentDescription("Send to Bluebird, online").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send to Clementine, offline").assertDoesNotExist()
    }
}
