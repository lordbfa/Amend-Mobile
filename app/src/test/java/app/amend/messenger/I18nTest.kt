package app.amend.messenger

import app.amend.messenger.core.json.Json
import app.amend.messenger.ui.I18n
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every language file lines up with keys.json and keeps the {n} placeholders. */
class I18nTest {
    private val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n")).first { it.isDirectory }
    private fun read(name: String) = Json.parseArray(File(dir, name).readText())
    private val placeholder = Regex("\\{\\d+\\}")

    @Test fun everyLanguageHasEveryKey() {
        val keys = read("keys.json").map { it as String }
        assertTrue(keys.size > 500)
        for (lang in I18n.languages.filter { it.code != "en" }) {
            val values = read("${lang.code}.json")
            assertEquals("${lang.code} size", keys.size, values.size)
            keys.forEachIndexed { i, k ->
                val v = values[i] as String
                assertTrue("${lang.code} #$i is empty", v.isNotBlank())
                assertEquals("${lang.code} #$i placeholders: $k → $v",
                    placeholder.findAll(k).map { it.value }.sorted().toList(),
                    placeholder.findAll(v).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun fifteenLanguagesWithRtlForArabicAndUrdu() {
        assertEquals(15, I18n.languages.size)
        assertEquals(setOf("ar", "ur"), I18n.languages.filter { it.rtl }.map { it.code }.toSet())
    }

    @Test fun templatesFillChangingParts() {
        val re = I18n.templateRegex("Reached {0} of {1} contacts. Open amend to stop.")
        assertEquals(
            "{1} জনের মধ্যে 3 জনের কাছে পৌঁছেছে।".replace("{1}", "5"),
            I18n.fill(re, "{1} জনের মধ্যে {0} জনের কাছে পৌঁছেছে।", "Reached 3 of 5 contacts. Open amend to stop."),
        )
        // text that doesn't match is left alone
        assertEquals(null, I18n.fill(re, "x", "Hello"))
    }
}
