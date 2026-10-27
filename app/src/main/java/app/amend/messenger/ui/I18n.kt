package app.amend.messenger.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import app.amend.messenger.core.json.Json
import java.util.Locale

/**
 * amend's languages. The English text in the code is the key; each language is a
 * list of translations in the same order as assets/i18n/keys.json. Text with
 * changing parts ("5 of 6 relays online") is matched by templates ("{0} of {1} …").
 *
 * The language is Compose state, so switching it re-renders the app at once —
 * no restart needed.
 */
object I18n {
    data class Language(val code: String, val native: String, val english: String, val rtl: Boolean = false)

    val languages = listOf(
        Language("en", "English", "English"),
        Language("bn", "বাংলা", "Bangla"),
        Language("fi", "Suomi", "Finnish"),
        Language("ru", "Русский", "Russian"),
        Language("pt", "Português", "Portuguese"),
        Language("es", "Español", "Spanish"),
        Language("fr", "Français", "French"),
        Language("de", "Deutsch", "German"),
        Language("ar", "العربية", "Arabic", rtl = true),
        Language("hi", "हिन्दी", "Hindi"),
        Language("ur", "اردو", "Urdu", rtl = true),
        Language("zh", "中文（简体）", "Chinese (Simplified)"),
        Language("ja", "日本語", "Japanese"),
        Language("tr", "Türkçe", "Turkish"),
        Language("id", "Bahasa Indonesia", "Indonesian"),
    )

    /** The language in use (resolved: never "system"). */
    var current by mutableStateOf("en")
        private set
    /** What the user picked: "system" or a language code. */
    var choice by mutableStateOf("system")
        private set

    val isRtl: Boolean get() = languages.firstOrNull { it.code == current }?.rtl == true

    private var appContext: Context? = null
    private var exact: Map<String, String> = emptyMap()
    private var templates: List<Pair<Regex, String>> = emptyList()
    private val cache = HashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        choice = context.getSharedPreferences("amend_lang", Context.MODE_PRIVATE).getString("choice", "system") ?: "system"
        apply(resolve(choice))
    }

    /** "system" follows the phone's language when amend has it, otherwise English. */
    fun resolve(pick: String): String {
        if (pick != "system") return pick
        val sys = Locale.getDefault().language
        return languages.firstOrNull { it.code == sys }?.code ?: "en"
    }

    fun select(context: Context, pick: String) {
        context.getSharedPreferences("amend_lang", Context.MODE_PRIVATE).edit().putString("choice", pick).apply()
        choice = pick
        apply(resolve(pick))
        // notification channel names follow the language too
        runCatching { app.amend.messenger.util.Notifications.createChannels(context.applicationContext) }
    }

    private fun apply(code: String) {
        val ctx = appContext
        synchronized(cache) {
            cache.clear()
            if (code == "en" || ctx == null) {
                exact = emptyMap(); templates = emptyList()
            } else {
                val (e, t) = runCatching { load(ctx, code) }.getOrDefault(emptyMap<String, String>() to emptyList())
                exact = e; templates = t
            }
        }
        current = code
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(ctx: Context, code: String): Pair<Map<String, String>, List<Pair<Regex, String>>> {
        val keys = Json.parseArray(ctx.assets.open("i18n/keys.json").bufferedReader().readText()).map { it as String }
        val values = Json.parseArray(ctx.assets.open("i18n/$code.json").bufferedReader().readText()).map { it as? String }
        val map = HashMap<String, String>(keys.size * 2)
        val tpl = ArrayList<Pair<Regex, String>>()
        keys.forEachIndexed { i, k ->
            val v = values.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            if (PLACEHOLDER.containsMatchIn(k)) tpl += templateRegex(k) to v else map[k] = v
        }
        // longest templates first: the most specific wins
        tpl.sortByDescending { it.first.pattern.length }
        return map to tpl
    }

    private val PLACEHOLDER = Regex("\\{(\\d+)\\}")

    /** "Sent · stored on {0} relay" → ^\QSent · stored on \E(?<p0>.+?)\Q relay\E$ */
    fun templateRegex(key: String): Regex {
        val sb = StringBuilder("^")
        var last = 0
        PLACEHOLDER.findAll(key).forEach { m ->
            if (m.range.first > last) sb.append(Regex.escape(key.substring(last, m.range.first)))
            sb.append("(?<p").append(m.groupValues[1]).append(">.+?)")
            last = m.range.last + 1
        }
        if (last < key.length) sb.append(Regex.escape(key.substring(last)))
        sb.append("$")
        return Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    /** Fills a translated template with the parts matched from the English text. */
    fun fill(template: Regex, translation: String, text: String): String? {
        val m = template.matchEntire(text) ?: return null
        return PLACEHOLDER.replace(translation) { p ->
            runCatching { m.groups["p" + p.groupValues[1]]?.value }.getOrNull() ?: p.value
        }
    }

    /** Translate a piece of UI text (unknown text, like a friend's message, is returned as it is). */
    fun tr(text: String): String {
        if (current == "en" || text.isBlank()) return text
        synchronized(cache) {
            cache[text]?.let { return it }
            val out = exact[text] ?: templates.firstNotNullOfOrNull { (re, v) -> fill(re, v, text) } ?: text
            if (cache.size > 4000) cache.clear()
            cache[text] = out
            return out
        }
    }
}

/** Shorthand for non-Compose code (notifications…). */
fun tr(text: String): String = I18n.tr(text)

/**
 * Drop-in replacement for Material 3's Text that translates its string. Every screen
 * imports this instead of androidx.compose.material3.Text, so all UI text follows
 * the chosen language without touching each call.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    @Suppress("UNUSED_VARIABLE") val lang = I18n.current // re-render when the language changes
    androidx.compose.material3.Text(
        text = I18n.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
        onTextLayout = onTextLayout, style = style,
    )
}

/** Rich text (a friend's message with links…) is shown as it is. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = emptyMap(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
        inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
    )
}
