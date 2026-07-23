package app.shunt.solver.brouter

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Asset-install test. Camera-vision geometry is covered by [CameraVisionTest];
 * real routing against an rd5 tile is a local harness, not CI (the tile is a
 * 10 MB binary we don't commit) — see docs/brouter-spike.md.
 */
class BrouterRouterTest {

    @Test
    fun `install writes the bundled profile and dictionary`() {
        val dir = Files.createTempDirectory("brouter-assets").toFile()
        try {
            BrouterAssets.install(dir) { name ->
                requireNotNull(javaClass.getResourceAsStream("/brouter-data/$name")) { "missing resource $name" }
            }
            assertTrue(File(dir, "car-vario.brf").length() > 0, "profile not installed")
            assertTrue(File(dir, "lookups.dat").length() > 0, "lookups.dat not installed")
        } finally {
            dir.deleteRecursively()
        }
    }
}
