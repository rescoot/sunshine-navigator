package dev.ligustah.lsnav

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test fun pendingRecoveryDisablesEverySendCard() {
        compose.setContent { NavigationTheme { ScooterPicker(listOf(online, offline), false, 1, {}) } }
        compose.onNodeWithContentDescription("Send to Bluebird, online").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send to Clementine, offline").assertIsNotEnabled()
    }
}
