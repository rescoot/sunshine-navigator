package dev.ligustah.lsnav

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()

    @Test fun connectionDraftSurvivesActivityRecreation() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Other server or API token") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Other server or API token").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Sunshine server (HTTPS)") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        val field = compose.onNodeWithText("Sunshine server (HTTPS)")
        field.performTextClearance()
        field.performTextInput("https://draft.example.org")
        compose.onNodeWithText("Sunshine server (HTTPS)").assertTextContains("https://draft.example.org")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sunshine server (HTTPS)").assertTextContains("https://draft.example.org")
    }
}
