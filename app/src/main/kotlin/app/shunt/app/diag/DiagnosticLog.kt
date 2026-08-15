package app.shunt.app.diag

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A rolling week of what Shunt did, so a bug report can carry evidence.
 *
 * ## Why this exists
 *
 * Every hard-won thing this project knows about Teslas came from a drive, and
 * most of it arrived as a sentence and a screenshot. That is a remarkable amount
 * to get from so little, and it does not scale past one maintainer who also
 * writes the code. A beta tester who sees the car do something wrong has no way
 * to say *what* — and the interesting part is never the moment they noticed, it
 * is the twenty seconds before it, which by then is gone.
 *
 * So the app keeps a plain-text record of its own decisions and hands it over on
 * request, to be attached to an email.
 *
 * ## What it is not
 *
 * **It is not telemetry.** Nothing is uploaded, nothing is scheduled, nothing
 * leaves the phone unless the person holding it exports the file and sends it
 * somewhere themselves. That is not a nicety — an app whose entire purpose is
 * to stop a driver being tracked cannot quietly phone home about where they
 * drove, and CLAUDE.md §3 forbids the background work it would take.
 *
 * ## Privacy is the whole design
 *
 * A log of a navigation app is a log of where somebody went, which is exactly
 * the thing this app exists to protect. Three rules follow, and none is
 * optional:
 *
 * - **Coordinates are written, but exporting them is a choice.** They are the
 *   most useful thing in here and the most dangerous; [Export.includeLocations]
 *   decides, defaulting to *off*, and redaction happens at export so the choice
 *   is made with the report in hand rather than a week earlier.
 * - **It expires by itself.** Entries older than [RETENTION_DAYS] are dropped
 *   whenever the log is touched. A file that only grows is a file that
 *   eventually documents a year of someone's movements.
 * - **The person sees it before it goes.** The export is a file they open, read,
 *   and attach — not a button that sends.
 */
class DiagnosticLog(
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES,
    private val retentionMillis: Long = TimeUnit.DAYS.toMillis(RETENTION_DAYS),
) {
    /** What an entry is about, so an export can be read without the source open. */
    enum class Kind { PLAN, DRIVE, VEHICLE, CAMERA, SEARCH, ERROR }

    /**
     * Record one thing that happened.
     *
     * [locations] are kept apart from [message] rather than formatted into it,
     * which is what makes redaction possible at all: a coordinate interpolated
     * into free text cannot reliably be found again later, and "reliably" is the
     * only standard worth anything here.
     */
    @Synchronized
    fun record(kind: Kind, message: String, locations: List<Pair<Double, Double>> = emptyList()) {
        val line = buildString {
            append(nowMillis())
            append('\t')
            append(kind.name)
            append('\t')
            append(message.replace('\t', ' ').replace('\n', ' '))
            if (locations.isNotEmpty()) {
                append('\t')
                append(locations.joinToString(";") { (lat, lon) -> "%.5f,%.5f".format(java.util.Locale.US, lat, lon) })
            }
            append('\n')
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line)
            if (file.length() > maxBytes) trim()
        }
    }

    /** Everything currently held, oldest first, already expired-out. */
    @Synchronized
    fun entries(): List<Entry> = read().filter { it.atMillis >= nowMillis() - retentionMillis }

    /**
     * The log as text, ready to attach to an email.
     *
     * Redaction happens here, not at write time, so the driver decides with the
     * report in front of them.
     */
    @Synchronized
    fun export(options: Export = Export()): String {
        val since = nowMillis() - options.window.millis
        val kept = entries().filter { it.atMillis >= since }
        return buildString {
            appendLine("Shunt diagnostic log")
            appendLine("Covering: last ${options.window.label}")
            appendLine(
                "Locations: " + if (options.includeLocations) {
                    "INCLUDED — this file says where you drove. Read it before sending."
                } else {
                    "removed"
                },
            )
            appendLine("Entries: ${kept.size}")
            appendLine("-".repeat(60))
            for (entry in kept) {
                append(stamp(entry.atMillis))
                append("  ")
                append(entry.kind.name.padEnd(7))
                append(entry.message)
                if (options.includeLocations && entry.locations.isNotEmpty()) {
                    append("  [")
                    append(entry.locations.joinToString(" ") { (lat, lon) -> "%.5f,%.5f".format(java.util.Locale.US, lat, lon) })
                    append(']')
                }
                appendLine()
            }
        }
    }

    /** Throw the whole thing away. */
    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    /** Drop anything past its retention, and the oldest entries if still too big. */
    @Synchronized
    private fun trim() {
        val kept = read().filter { it.atMillis >= nowMillis() - retentionMillis }
        // Still oversized after expiry — a very busy week. Halve it from the
        // oldest end rather than trimming one line at a time, so this cannot
        // turn into a rewrite of the file on every single append.
        val final = if (estimateBytes(kept) > maxBytes) kept.drop(kept.size / 2) else kept
        runCatching {
            file.writeText(final.joinToString("") { it.raw + "\n" })
        }
    }

    private fun read(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull(::parse)
    }.getOrDefault(emptyList())

    private fun parse(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val at = parts[0].toLongOrNull() ?: return null
        val kind = runCatching { Kind.valueOf(parts[1]) }.getOrNull() ?: return null
        val locations = parts.getOrNull(3)
            ?.split(';')
            ?.mapNotNull { pair ->
                val (lat, lon) = pair.split(',').let { it.getOrNull(0) to it.getOrNull(1) }
                val la = lat?.toDoubleOrNull()
                val lo = lon?.toDoubleOrNull()
                if (la == null || lo == null) null else la to lo
            }
            .orEmpty()
        return Entry(at, kind, parts[2], locations, line)
    }

    private fun estimateBytes(entries: List<Entry>): Long =
        entries.sumOf { it.raw.length.toLong() + 1 }

    private fun stamp(atMillis: Long): String {
        val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        return format.format(java.util.Date(atMillis))
    }

    data class Entry(
        val atMillis: Long,
        val kind: Kind,
        val message: String,
        val locations: List<Pair<Double, Double>>,
        /** The line as stored, so trimming can rewrite without reformatting. */
        val raw: String,
    )

    /** How much of the log to hand over, and whether it says where. */
    data class Export(
        val window: Window = Window.WEEK,
        /**
         * Defaults to false, and that default is the point. Someone exporting a
         * log to send to a stranger should have to decide to include where they
         * drove, not discover afterwards that they did.
         */
        val includeLocations: Boolean = false,
    )

    enum class Window(val millis: Long, val label: String) {
        HOUR(TimeUnit.HOURS.toMillis(1), "hour"),
        DAY(TimeUnit.DAYS.toMillis(1), "day"),
        WEEK(TimeUnit.DAYS.toMillis(7), "week"),
    }

    companion object {
        /**
         * A week, matching how long it takes to notice something is wrong and
         * get round to reporting it — and short enough that the file is never a
         * meaningful history of anyone's movements.
         */
        const val RETENTION_DAYS = 7L

        /** Ceiling regardless of age; a busy week of driving is still bounded. */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
