package app.shunt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * [compact] is the one-line form for tight spots like the driving sheet.
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
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        if (compact) {
            Text(
                "Tesla control is unproven — stay alert and be ready to take over.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
            )
        } else {
            Column {
                Text(
                    "Tesla/FSD support is a work in progress",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.error,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Route planning and the camera alerts on this phone are working. " +
                        "Sending the route to the car is NOT proven on real vehicles yet: " +
                        "it may be rejected, altered, or driven differently than shown. " +
                        "Never rely on it to avoid a camera, and stay fully attentive and " +
                        "ready to take over at any moment when using this with FSD.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                )
            }
        }
    }
}
