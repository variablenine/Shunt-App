package app.shunt.app.diag

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the rolling log into a file and offers it to whatever the user wants to
 * send it with.
 *
 * The separation from [DiagnosticLog] is deliberate: the log itself is pure
 * Kotlin and exhaustively tested, and everything that touches Android — the
 * cache directory, the content URI, the share sheet — lives here where it
 * cannot be. Nothing in this file decides *what* is in the export; that is
 * [DiagnosticLog.Export], which the user fills in.
 *
 * **The user is the transport.** No upload, no endpoint, no address baked in.
 * The share sheet hands them a file and they choose where it goes, which is the
 * only arrangement compatible with an app whose purpose is to stop somebody
 * being tracked.
 */
object DiagnosticExport {

    /**
     * Write the export and return an intent that offers it for sending.
     *
     * Returns null if the file cannot be written — an export that silently
     * shares nothing would be worse than one that visibly fails.
     */
    fun shareIntent(context: Context, log: DiagnosticLog, options: DiagnosticLog.Export): Intent? {
        val text = log.export(options)
        val file = runCatching {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            // Overwritten each time rather than accumulating: old exports in the
            // cache are copies of a log that has since expired, which quietly
            // outlives the retention the log itself promises.
            File(dir, FILE_NAME).apply { writeText(text) }
        }.getOrNull() ?: return null

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        }.getOrNull() ?: return null

        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Shunt diagnostic log — $stamp")
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    appendLine("Shunt diagnostic log attached.")
                    appendLine()
                    appendLine("What went wrong, and what you expected instead:")
                    appendLine()
                    appendLine()
                    appendLine(
                        if (options.includeLocations) {
                            "This log includes coordinates of where the car went."
                        } else {
                            "Locations have been removed from this log."
                        },
                    )
                },
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private const val FILE_NAME = "shunt-diagnostics.txt"
}
