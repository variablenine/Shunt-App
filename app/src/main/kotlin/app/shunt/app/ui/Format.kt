package app.shunt.app.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/** "+12 min" / "same time" / "−3 min" for an added-seconds-vs-fastest delta. */
fun formatAddedTime(seconds: Int): String {
    if (seconds == 0) return "same time as fastest"
    val minutes = (abs(seconds) / 60.0).roundToInt().coerceAtLeast(1)
    val sign = if (seconds > 0) "+" else "−"
    return "$sign$minutes min vs fastest"
}

/** "1 h 12 min" / "8 min" for a duration. */
fun formatDuration(seconds: Int): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

/** "camera" / "cameras" agreement. */
fun cameraCount(n: Int): String = if (n == 1) "1 camera" else "$n cameras"
