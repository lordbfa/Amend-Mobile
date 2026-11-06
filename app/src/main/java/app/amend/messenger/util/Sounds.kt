package app.amend.messenger.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/** What a sound is for. Messages and other notifications play through their channel; calls amend rings itself. */
enum class SoundKind(val key: String) { MESSAGE("message"), CALL("call"), OTHER("other") }

/** One choice in the sound picker. [raw] is amend's own sound (res/raw), null for the phone's default or silence. */
data class SoundOption(val id: String, val label: String, val raw: String? = null)

/**
 * amend's own sound library — original sounds synthesised by tools/sounds/generate.py, so
 * they carry no third-party rights — and the user's pick for each kind of sound.
 */
object Sounds {
    const val SYSTEM = "system"
    const val SILENT = "silent"
    private const val PREFS = "amend_sounds"

    val options: Map<SoundKind, List<SoundOption>> = mapOf(
        SoundKind.MESSAGE to listOf(
            SoundOption("dewdrop", "Dewdrop", "amend_msg_dewdrop"),
            SoundOption("leaf", "Leaf", "amend_msg_leaf"),
            SoundOption("bamboo", "Bamboo", "amend_msg_bamboo"),
            SoundOption("petal", "Petal", "amend_msg_petal"),
            SoundOption(SYSTEM, "Phone's default sound"),
            SoundOption(SILENT, "Silent"),
        ),
        SoundKind.CALL to listOf(
            SoundOption("morning_forest", "Morning forest", "amend_ring_morning_forest"),
            SoundOption("riverbank", "Riverbank", "amend_ring_riverbank"),
            SoundOption("wind_chimes", "Wind chimes", "amend_ring_wind_chimes"),
            SoundOption(SYSTEM, "Phone's ringtone"),
            SoundOption(SILENT, "Vibrate only"),
        ),
        SoundKind.OTHER to listOf(
            SoundOption("sprout", "Sprout", "amend_note_sprout"),
            SoundOption("sunbeam", "Sunbeam", "amend_note_sunbeam"),
            SoundOption("glow", "Glow", "amend_note_glow"),
            SoundOption(SYSTEM, "Phone's default sound"),
            SoundOption(SILENT, "Silent"),
        ),
    )

    /** amend's sounds for new installs. */
    private val defaults = mapOf(SoundKind.MESSAGE to "dewdrop", SoundKind.CALL to "morning_forest", SoundKind.OTHER to "sprout")

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Once per install: people who already had amend keep the phone's sounds they're used to
     * (and any channel settings they made); new installs start with amend's own sounds.
     */
    fun initDefaults(c: Context, existingInstall: Boolean) {
        val p = prefs(c)
        if (p.getBoolean("init", false)) return
        val e = p.edit()
        if (existingInstall) SoundKind.entries.forEach { e.putString(it.key, SYSTEM) }
        e.putBoolean("init", true).apply()
    }

    fun choice(c: Context, kind: SoundKind): String {
        val id = prefs(c).getString(kind.key, null) ?: defaults.getValue(kind)
        return if (options.getValue(kind).any { it.id == id }) id else defaults.getValue(kind)
    }

    fun option(c: Context, kind: SoundKind): SoundOption = options.getValue(kind).first { it.id == choice(c, kind) }

    /** Saves the pick. Notification sounds live in their channel, so those are rebuilt with it. */
    fun set(c: Context, kind: SoundKind, id: String) {
        prefs(c).edit().putString(kind.key, id).apply()
        if (kind != SoundKind.CALL) Notifications.createChannels(c.applicationContext)
    }

    /** Where the sound plays from, or null for silence. */
    fun uri(c: Context, kind: SoundKind, option: SoundOption = option(c, kind)): Uri? = when {
        option.raw != null -> Uri.parse("android.resource://${c.packageName}/raw/${option.raw}")
        option.id == SYSTEM -> RingtoneManager.getDefaultUri(if (kind == SoundKind.CALL) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
        else -> null
    }

    // ------------------------------------------------------------------ preview in settings

    private var preview: MediaPlayer? = null

    /** Plays a choice once so the user can hear it (the previous preview stops). */
    fun preview(c: Context, kind: SoundKind, option: SoundOption) {
        stopPreview()
        val u = uri(c, kind, option) ?: return
        preview = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (kind == SoundKind.CALL) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(c.applicationContext, u)
                setOnCompletionListener { it.release(); if (preview === it) preview = null }
                prepare()
                start()
            }
        }.getOrNull()
    }

    fun stopPreview() {
        preview?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        preview = null
    }
}
