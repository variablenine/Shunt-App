package app.shunt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The standing caveat about the vehicle integration.
 *
 * Planning, the map, and the drive alerts are exercised and trustworthy. The
 * part that hands a route to the car — and anything that then happens under
 * FSD — is **not** proven on real vehicles yet. That gap is invisible from the
 * UI (a pushed route looks identical whether or not the car honours it), so it
 * is stated wherever the user is about to depend on it, not buried in an About
 * screen.
 *
 * **Kept to one line, on purpose.** This is read in a car, often by someone
 * about to set off, and a paragraph in that setting is not a stronger warning
 * than a sentence — it is a weaker one, because it does not get read. The
 * maintainer put it plainly: "no driver is going to be reading a whole paragraph
 * about the limitations. Just say something like 'FSD nav is in early testing,
 * use at your own risk!'"
 *
 * So the whole caveat is the headline, and [compact] now differs only in weight,
 * not in how much there is to read. The detail that used to live here belongs in
 * the README and in vehicle settings, where somebody is sitting still.
 */
@Composable
fun TeslaWipWarning(modifier: Modifier = Modifier, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.errorContainer.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .border(1.dp, scheme.error.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "FSD nav is in early testing — stay ready to take over.",
            style = if (compact) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
            color = scheme.onSurface,
        )
    }
}
