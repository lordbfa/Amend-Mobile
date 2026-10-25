package app.amend.messenger

import app.amend.messenger.core.crypto.FileCrypto
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.MediaInfo
import app.amend.messenger.data.MediaKind
import app.amend.messenger.net.Blossom
import app.amend.messenger.ui.screens.VoiceRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MediaTest {
    @Test
    fun fileCryptoRoundTripAndTamper() {
        val plain = ByteArray(10_000) { it.toByte() }
        val enc = FileCrypto.encrypt(plain)
        assertEquals(32, enc.key.size)
        assertEquals(12, enc.nonce.size)
        assertArrayEquals(plain, FileCrypto.decrypt(enc.data, enc.key, enc.nonce))
        val bad = enc.data.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertTrue(runCatching { FileCrypto.decrypt(bad, enc.key, enc.nonce) }.isFailure)
    }

    @Test
    fun mediaInfoSurvivesJsonAndRumorTags() {
        val peer = KeyPair.generate().publicKeyHex
        val enc = FileCrypto.encrypt(ByteArray(100))
        val m = MediaInfo(
            MediaKind.VOICE, listOf("https://a.example/x", "https://b.example/x"), "audio/mp4",
            enc.key.toHex(), enc.nonce.toHex(), enc.sha256Hex, enc.data.size.toLong(), 4200, ByteArray(64) { it.toByte() },
        )
        val back = MediaInfo.fromJson(m.toJson())
        assertEquals(m, back)
        assertEquals(4200L, back!!.durationMs)
        assertArrayEquals(m.waveform, back.waveform)

        val rumor = GiftWrap.rumor(KeyPair.generate().publicKeyHex, Kinds.FILE_MESSAGE, m.urls.first(), m.tags(peer))
        val parsed = MediaInfo.fromRumor(rumor)
        assertNotNull(parsed)
        assertEquals(m.urls, parsed!!.urls)
        assertEquals(MediaKind.VOICE, parsed.kind)
        assertEquals(m.keyHex, parsed.keyHex)
        assertArrayEquals(m.waveform, parsed.waveform)

        // not https → rejected
        assertNull(MediaInfo.fromRumor(GiftWrap.rumor(peer, Kinds.FILE_MESSAGE, "http://x", m.tags(peer))))
    }

    @Test
    fun waveformIsNormalised() {
        val w = VoiceRecorder.waveform(List(500) { it * 10 })
        assertEquals(64, w.size)
        assertEquals(255, w.last().toInt() and 0xff)
        assertTrue(w.all { (it.toInt() and 0xff) >= 8 })
    }

    /** Probes Blossom servers: upload, download, hash check. -Damend.blobScan=true */
    @Test
    fun blossomScan(): Unit = runBlocking {
        assumeTrue(System.getProperty("amend.blobScan") == "true")
        val candidates = Blossom.DEFAULT_SERVERS + listOf(
            "https://blossom.band", "https://cdn.nostrcheck.me", "https://blossom.nostr.build",
            "https://cdn.satellite.earth", "https://blossom.sector01.com", "https://haven.slidestr.net",
        )
        val keys = KeyPair.generate()
        val blossom = Blossom()
        File("build").mkdirs()
        val out = File("build/blob-scan.txt").also { it.writeText("") }
        val rnd = java.util.Random(7)
        // a voice-sized file and a video-sized one (a 30-60 s round video is 4-10 MB)
        for (size in listOf(50_000, 3_000_000, 9_000_000)) {
        val data = FileCrypto.encrypt(ByteArray(size).also { rnd.nextBytes(it) }).data
        for (s in Blossom.DEFAULT_SERVERS) for (type in listOf("application/octet-stream", "video/mp4")) {
            val t0 = System.currentTimeMillis()
            val line = try {
                val blob = blossom.upload(s, data, keys, type)
                val back = blossom.download(listOf(blob.url), blob.sha256)
                "OK  $s [$type] ${size / 1000}kB ${System.currentTimeMillis() - t0}ms got=${back.size}B url=${blob.url.take(70)}"
            } catch (e: Exception) {
                "BAD $s [$type] ${size / 1000}kB ${e.javaClass.simpleName}: ${e.message?.take(160)}"
            }
            println("BLOB-SCAN: $line")
            out.appendText(line + "\n")
        }
        }
    }
}
