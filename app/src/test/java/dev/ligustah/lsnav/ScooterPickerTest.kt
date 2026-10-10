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

    @Test fun onlyOnlineExplicitTapSelectsSendTarget() {
        var selections = 0
        compose.setContent { NavigationTheme { ScooterPicker(listOf(online, offline), true, 1, { selections++ }) } }
        compose.waitForIdle()
        assertEquals(0, selections)
        compose.onNodeWithContentDescription("Send to Clementine, offline").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send to Bluebird, online").performClick()
        assertEquals(1, selections)
    }

    @Test fun preferenceSelectionDoesNotCompleteSetup() {
        var selections = 0
        var continues = 0
        compose.setContent { NavigationTheme { SetupScootersScreen(SettingsState(scooters = RemoteData.loaded(listOf(online, offline))), onSelect = { selections++ }, onContinue = { continues++ }) } }
        compose.onNodeWithContentDescription("Choose Clementine as default").performScrollTo().performClick()
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
        compose.onNodeWithContentDescription("Send to Clementine, offline").assertIsNotEnabled()
    }
}
