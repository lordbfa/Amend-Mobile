package app.amend.messenger

import app.amend.messenger.util.SoundKind
import app.amend.messenger.util.Sounds
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every sound in the picker exists, ids are unique, and each kind offers the phone's sound and silence. */
class SoundsTest {
    private val raw = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }

    @Test fun everyAmendSoundShipsWithTheApp() {
        for ((kind, options) in Sounds.options) {
            assertEquals("$kind ids", options.size, options.map { it.id }.toSet().size)
            assertTrue("$kind has the phone's sound", options.any { it.id == Sounds.SYSTEM })
            assertTrue("$kind has silence", options.any { it.id == Sounds.SILENT })
            for (o in options.filter { it.raw != null }) {
                val f = File(raw, o.raw + ".ogg")
                assertTrue("missing ${f.path}", f.isFile && f.length() > 1000)
            }
        }
        assertEquals(SoundKind.entries.toSet(), Sounds.options.keys)
    }
}
