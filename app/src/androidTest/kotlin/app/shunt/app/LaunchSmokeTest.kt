package app.shunt.app

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-emulator smoke test — the check JVM unit tests can't do: actually launch
 * the app and drive the UI. This is what would have caught the System.Logger
 * launch crash (construction/first-composition failures surface right here).
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    // Grant location + notifications first (order 0), so launching the activity
    // doesn't raise the system permission dialog on top of it — which would
    // pause MainActivity and hide its Compose hierarchy from the test.
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1)
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
}
