package app.shunt.solver.brouter

import java.io.File
import java.io.InputStream

/**
 * Installs the BRouter profile + tag dictionary (`car-vario.brf`,
 * `lookups.dat`) into a working directory, where BRouter reads them as files.
 *
 * The bytes come from an injected [open] function rather than the classpath,
 * because `Class.getResourceAsStream` is unreliable on Android — the app passes
 * an `AssetManager`-backed opener, JVM tests pass a classpath one.
 */
object BrouterAssets {
    val FILES = listOf("car-vario.brf", "lookups.dat")

    /** Copy each bundled asset into [profileDir] if it isn't already there. */
    fun install(profileDir: File, open: (name: String) -> InputStream) {
        profileDir.mkdirs()
        for (name in FILES) {
            val target = File(profileDir, name)
            if (target.exists() && target.length() > 0) continue
            open(name).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
        }
    }
}
