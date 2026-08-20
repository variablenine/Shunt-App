package app.shunt.app.diag

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the rolling log to a file the user picks.
 *
 * The separation from [DiagnosticLog] is deliberate: the log itself is pure
 * Kotlin and exhaustively tested, and everything that touches Android — the
 * document picker, the content URI, the output stream — lives here where it
 * cannot be. Nothing in this file decides *what* is in the export; that is
 * [DiagnosticLog.Export], which the user fills in.
 *
 * **The user is the transport.** No upload, no endpoint, no address baked in.
 *
 * **A download, not a share**, and that is the point of this file rather than a
 * detail of it. A share sheet asks *who to send it to* before the person has
 * read a word of it, and it hands the file straight to whatever they tap — on a
 * log that can contain every road they drove, that is the wrong order of
 * operations. Saving it puts the file where they chose, on their own device, to
 * open and read and then send or not. Asked for in those words: "make the export
 * log download not share".
 *
 * The file says which it is in its own first lines — see [DiagnosticLog.export]
 * — so it stays self-describing wherever it ends up, with no covering note to
 * be separated from it.
 */
object DiagnosticExport {

    /**
     * The name offered to the document picker.
     *
     * Stamped to the minute so exporting twice while chasing one bug produces
     * two files rather than a silent overwrite of the first.
     */
    fun fileName(at: Date = Date()): String =
        "shunt-diagnostics-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(at)}.txt"

    /**
     * Write [text] to the document the user picked.
     *
     * Returns false if it could not be written, which the caller must say out
     * loud: an export that silently produces nothing is worse than one that
     * visibly fails, because the person goes on believing they have a log.
     */
    fun writeTo(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray())
        } != null
    }.getOrDefault(false)
}
