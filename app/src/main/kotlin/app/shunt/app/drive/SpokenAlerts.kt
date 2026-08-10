package app.shunt.app.drive

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Says the drive alerts out loud.
 *
 * Until this existed there was **no audio at all** — alerts were a vibration and
 * a notification, which is exactly the wrong channel for the one moment they
 * matter. A camera warning is useful in the seconds before a junction, and
 * reading a phone then is both unsafe and, under FSD, the thing the driver is
 * least able to do.
 *
 * Three choices worth keeping if this is rewritten:
 *
 *  - **`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`.** This is what makes a phone
 *    route the audio into a car over Bluetooth the way turn-by-turn does, and
 *    duck music rather than talk over it or be swallowed by it. Plain media or
 *    notification usage does neither reliably.
 *  - **Offline, no account, no key.** Android's TTS runs on-device, which is
 *    what a keyless offline-first app needs (CLAUDE.md §3). A cloud voice would
 *    also be silent on the 2am rural drive that the whole fallback exists for.
 *  - **Speech is best-effort and never load-bearing.** Every alert still
 *    vibrates and still posts a notification. If the engine is missing, still
 *    initialising, or the language is unavailable, the driver loses the nicety
 *    and keeps the warning.
 */
class SpokenAlerts(context: Context) {

    private val ready = AtomicBoolean(false)
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val tts = engine ?: return@TextToSpeech
            val available = runCatching { tts.setLanguage(Locale.getDefault()) }.getOrNull()
            if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fall back rather than go silent: the default locale may have
                // no voice data installed while English does.
                runCatching { tts.setLanguage(Locale.US) }
            }
            runCatching {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit
                @Deprecated("Required by the platform base class")
                override fun onError(utteranceId: String?) = Unit
            })
            ready.set(true)
        }
    }

    /**
     * Speak [text]. [urgent] jumps the queue — a camera 150 m ahead is worth
     * cutting off a sentence about a charging stop, and the reverse never is.
     */
    fun say(text: String, urgent: Boolean) {
        if (!ready.get()) return
        val tts = engine ?: return
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching { tts.speak(text, mode, null, text.hashCode().toString()) }
    }

    /** Release the engine. Failing to do this leaks a service connection. */
    fun shutdown() {
        ready.set(false)
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }
}
