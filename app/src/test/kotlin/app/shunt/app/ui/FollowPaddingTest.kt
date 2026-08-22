package app.shunt.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The driving card floats over the bottom of the map, so the follow camera
 * frames the strip above it rather than the whole view. Reported from a drive:
 * "the pov centering needs to match the window that the card isn't covering".
 */
class FollowPaddingTest {

    @Test
    fun `the card's height is added to the bottom padding`() {
        assertEquals(100 + 600, followBottomPadding(pad = 100, bottomInsetPx = 600, viewHeight = 2000))
    }

    @Test
    fun `no card means the ordinary padding`() {
        assertEquals(100, followBottomPadding(pad = 100, bottomInsetPx = 0, viewHeight = 2000))
    }

    @Test
    fun `a card covering most of the screen cannot squeeze the frame to nothing`() {
        // An expanded sheet is over half the view. Honouring that literally
        // would leave a frame a few pixels tall.
        val bottom = followBottomPadding(pad = 400, bottomInsetPx = 1800, viewHeight = 2000)
        assertTrue(bottom < 2000, "bottom padding $bottom must leave some view")
        assertTrue(bottom + 400 < 2000, "top and bottom padding together must fit the view")
    }

    @Test
    fun `the clamp never returns less than the ordinary padding`() {
        // A short view: the clamp would otherwise cut into the padding that
        // keeps the driver's dot off the edge of the screen.
        assertEquals(400, followBottomPadding(pad = 400, bottomInsetPx = 500, viewHeight = 200))
    }
}
