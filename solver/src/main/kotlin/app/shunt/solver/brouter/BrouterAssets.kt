package app.shunt.solver.brouter

import java.io.File

/**
 * Installs the BRouter profile + tag dictionary bundled in the `:brouter`
 * module (`car-vario.brf`, `lookups.dat`) into a working directory, where
 * BRouter reads them as files. Call once with the app's profile directory.
 */
object BrouterAssets {
    private val FILES = listOf("car-vario.brf", "lookups.dat")

    /** Copy each bundled asset into [profileDir] if it isn't already there. */
    fun install(profileDir: File) {
        profileDir.mkdirs()
        for (name in FILES) {
            val target = File(profileDir, name)
            if (target.exists() && target.length() > 0) continue
            val res = javaClass.getResourceAsStream("/brouter-data/$name")
                ?: error("bundled BRouter asset missing from classpath: $name")
            res.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
        }
    }
}
