package app.shunt.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-emulator smoke tests — the check JVM unit tests can't do: actually launch
 * the app and drive the UI. This is what would have caught the System.Logger
 * launch crash, and what reproduces the "enter key -> blank" report.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * If ShuntApplication/AppContainer construction or the first composition
     * throws, launching the activity fails here — a real launch-crash guard.
     */
    @Test
    fun launches_and_shows_the_search_field() {
        compose.waitForIdle()
        compose.onNodeWithText("Where to?").assertIsDisplayed()
    }

    /**
     * Reproduces the reported bug: entering a HERE key must not blank the
     * screen — the search field must still be there afterward.
     */
    @Test
    fun entering_a_here_key_keeps_the_search_field_visible() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Paste your HERE API key").performTextInput("test-key-123")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Where to?").assertIsDisplayed()
    }
}
