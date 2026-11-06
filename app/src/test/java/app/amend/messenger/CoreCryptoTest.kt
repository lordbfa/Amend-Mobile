package app.amend.messenger

import app.amend.messenger.core.crypto.Hashes
import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.core.crypto.Nip44
import app.amend.messenger.core.crypto.PureSecp256k1
import app.amend.messenger.core.crypto.hexToBytes
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Checks the protocol core against independent references:
 *  - secp_vectors.json: generated with libsecp256k1 (coincurve)
 *  - nip44.vectors.json: the official NIP-44 v2 test vectors (paulmillr/nip44, public domain)
 *  - event_vector.json: an event id computed with Python's json module
 */
@Suppress("UNCHECKED_CAST")
class CoreCryptoTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    private val nip44 by lazy { Json.parseObject(resource("nip44.vectors.json"))["v2"] as Map<String, Any?> }
    private val valid get() = nip44["valid"] as Map<String, Any?>
    private val invalid get() = nip44["invalid"] as Map<String, Any?>

    @Test
    fun bip340OfficialVector0() {
        val sk = ("00".repeat(31) + "03").hexToBytes()
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", PureSecp256k1.xOnlyPublicKey(sk).toHex())
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            PureSecp256k1.signSchnorr(ByteArray(32), sk, ByteArray(32)).toHex()
        )
    }

    @Test
    fun secp256k1MatchesLibsecp256k1() {
        val vectors = Json.parseArray(resource("secp_vectors.json")) as List<Map<String, String>>
        assertTrue(vectors.size >= 20)
        for (v in vectors) {
            val sk = v.getValue("sk").hexToBytes()
            val msg = v.getValue("msg").hexToBytes()
            val pub = v.getValue("pub").hexToBytes()
            assertEquals(v["pub"], PureSecp256k1.xOnlyPublicKey(sk).toHex())
            assertEquals(v["sig"], PureSecp256k1.signSchnorr(msg, sk, v.getValue("aux").hexToBytes()).toHex())
            assertTrue(PureSecp256k1.verifySchnorr(v.getValue("sig").hexToBytes(), msg, pub))
            val tampered = v.getValue("sig").hexToBytes().also { it[63] = (it[63].toInt() xor 1).toByte() }
            assertFalse(PureSecp256k1.verifySchnorr(tampered, msg, pub))
            assertEquals(v["shared"], PureSecp256k1.sharedX(sk, v.getValue("pub2").hexToBytes()).toHex())
        }
    }

    @Test
    fun nip44ConversationKeys() {
        for (v in valid["get_conversation_key"] as List<Map<String, String>>) {
            assertEquals(
                v["conversation_key"],
                Nip44.conversationKey(v.getValue("sec1").hexToBytes(), v.getValue("pub2").hexToBytes()).toHex()
            )
        }
    }

    @Test
    fun nip44InvalidConversationKeys() {
        for (v in invalid["get_conversation_key"] as List<Map<String, String>>) {
            try {
                Nip44.conversationKey(v.getValue("sec1").hexToBytes(), v.getValue("pub2").hexToBytes())
                fail("expected failure: ${v["note"]}")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test
    fun nip44PaddedLengths() {
        for (v in valid["calc_padded_len"] as List<List<Long>>) {
            assertEquals(v[1].toInt(), Nip44.calcPaddedLen(v[0].toInt()))
        }
    }

    @Test
    fun nip44EncryptDecrypt() {
        for (v in valid["encrypt_decrypt"] as List<Map<String, String>>) {
            val sec1 = v.getValue("sec1").hexToBytes()
            val sec2 = v.getValue("sec2").hexToBytes()
            val ck = Nip44.conversationKey(sec1, PureSecp256k1.xOnlyPublicKey(sec2))
            assertEquals(v["conversation_key"], ck.toHex())
            assertEquals(v["payload"], Nip44.encrypt(v.getValue("plaintext"), ck, v.getValue("nonce").hexToBytes()))
            assertEquals(v["plaintext"], Nip44.decrypt(v.getValue("payload"), ck))
            val ck2 = Nip44.conversationKey(sec2, PureSecp256k1.xOnlyPublicKey(sec1))
            assertTrue(ck.contentEquals(ck2))
        }
    }

    @Test
    fun nip44LongMessages() {
        for (v in valid["encrypt_decrypt_long_msg"] as List<Map<String, Any?>>) {
            val pt = (v["pattern"] as String).repeat((v["repeat"] as Long).toInt())
            val ck = (v["conversation_key"] as String).hexToBytes()
            val payload = Nip44.encrypt(pt, ck, (v["nonce"] as String).hexToBytes())
            assertEquals(v["payload_sha256"], Hashes.sha256(payload.toByteArray()).toHex())
            assertEquals(pt, Nip44.decrypt(payload, ck))
        }
    }

    @Test
    fun nip44RejectsInvalidPayloads() {
        for (v in invalid["decrypt"] as List<Map<String, String>>) {
            try {
                Nip44.decrypt(v.getValue("payload"), v.getValue("conversation_key").hexToBytes())
                fail("expected failure: ${v["note"]}")
            } catch (e: Exception) {
                // expected
            }
        }
        for (len in invalid["encrypt_msg_lengths"] as List<Long>) {
            try {
                Nip44.encrypt("a".repeat(len.toInt()), ByteArray(32) { 1 })
                fail("expected failure for length $len")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test
    fun eventIdMatchesReference() {
        val ev = Json.parseObject(resource("event_vector.json"))
        val id = Event.computeId(
            ev["pubkey"] as String,
            ev["created_at"] as Long,
            (ev["kind"] as Long).toInt(),
            ev["tags"] as List<List<String>>,
            ev["content"] as String,
        )
        assertEquals(ev["id"], id)
    }

    @Test
    fun signedEventRoundTrip() {
        val kp = KeyPair.generate()
        val e = Event.sign(kp.secretKey, Kinds.METADATA, "{\"name\":\"tree\"}", listOf(listOf("t", "amend")))
        val parsed = Event.fromJson(e.toJson())
        assertEquals(e, parsed)
        assertTrue(parsed.isValid())
        assertFalse(parsed.copy(content = "changed").isValid())
    }

    @Test
    fun bech32MatchesReference() {
        val (hex, npub) = resource("npub.txt").trim().split(" ")
        assertEquals(hex, Nip19.parsePublicKey(npub))
        assertEquals(npub, Nip19.npub(hex))
        assertEquals(hex, Nip19.parsePublicKey("nostr:$npub"))
        assertNull(Nip19.parsePublicKey("npub1notreal"))
        val kp = KeyPair.generate()
        assertTrue(kp.secretKey.contentEquals(Nip19.parseSecretKey(kp.nsec)))
    }

    @Test
    fun giftWrapRoundTrip() {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "Hello 🌍 from amend", listOf(listOf("p", bob.publicKeyHex)))
        val wrap = GiftWrap.wrap(rumor, alice, bob.publicKeyHex)
        assertTrue(wrap.isValid())
        assertNotEquals(alice.publicKeyHex, wrap.pubkey)
        assertEquals(listOf(bob.publicKeyHex), wrap.tagValues("p"))
        assertTrue(wrap.createdAt <= Event.nowSeconds())

        val opened = GiftWrap.unwrap(Event.fromJson(wrap.toJson()), bob)
        assertEquals(rumor.id, opened.id)
        assertEquals("Hello 🌍 from amend", opened.content)
        assertEquals(alice.publicKeyHex, opened.pubkey)

        try {
            GiftWrap.unwrap(wrap, KeyPair.generate())
            fail("a third party must not be able to open the wrap")
        } catch (e: GiftWrap.UnwrapException) {
            // expected
        }
    }

    @Test
    fun deeplyNestedJsonIsRejectedNotACrash() {
        val ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        Json.parseArray(ok)
        for (n in listOf(Json.MAX_DEPTH + 1, 200_000)) {
            val bomb = "[".repeat(n) + "]".repeat(n)
            try {
                Json.parse(bomb)
                fail("depth $n accepted")
            } catch (e: Json.JsonException) {
                // expected: an ordinary exception the message handlers already catch
            }
        }
        // the same bomb sealed inside a gift wrap is just an unreadable message
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val sealKey = Nip44.conversationKey(alice.secretKey, bob.publicKeyHex.hexToBytes())
        val seal = Event.sign(alice.secretKey, Kinds.SEAL, Nip44.encrypt("[".repeat(40_000), sealKey))
        val oneTime = KeyPair.generate()
        val wrap = Event.sign(
            oneTime.secretKey, Kinds.GIFT_WRAP,
            Nip44.encrypt(seal.toJson(), Nip44.conversationKey(oneTime.secretKey, bob.publicKeyHex.hexToBytes())),
            listOf(listOf("p", bob.publicKeyHex)),
        )
        try {
            GiftWrap.unwrap(wrap, bob)
            fail("bomb opened")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun jsonRoundTrip() {
        val tricky = "q\"uote \\ back\nnl\u0001ctl 🌱"
        assertEquals(tricky, Json.parseArray(Json.write(listOf(tricky)))[0])
        val m = Json.parseObject("""{"a":[1,2.5,-3e2,true,null,"xé"],"b":{}}""")
        assertEquals(listOf(1L, 2.5, -300.0, true, null, "xé"), m["a"])
    }
}
