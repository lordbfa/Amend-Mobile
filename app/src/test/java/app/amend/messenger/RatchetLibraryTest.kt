package app.amend.messenger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/** Proves libsignal loads on the JVM and a PQXDH + Double Ratchet session works both ways. */
class RatchetLibraryTest {
    private class Party(val name: String, val device: Int) {
        val identity: IdentityKeyPair = IdentityKeyPair.generate()
        val store = InMemorySignalProtocolStore(identity, 1234 + device)
        val address = SignalProtocolAddress(name, device)

        fun bundle(): PreKeyBundle {
            val spk = ECKeyPair.generate()
            val spkSig = identity.privateKey.calculateSignature(spk.publicKey.serialize())
            store.storeSignedPreKey(1, SignedPreKeyRecord(1, System.currentTimeMillis(), spk, spkSig))
            val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSig = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
            store.storeKyberPreKey(2, KyberPreKeyRecord(2, System.currentTimeMillis(), kyber, kyberSig))
            return PreKeyBundle(
                store.localRegistrationId, device, PreKeyBundle.NULL_PRE_KEY_ID, null,
                1, spk.publicKey, spkSig, identity.publicKey, 2, kyber.publicKey, kyberSig,
            )
        }
    }

    @Test fun sessionBothWaysOutOfOrder() {
        val alice = Party("alice", 1)
        val bob = Party("bob", 7)
        SessionBuilder(alice.store, bob.address).process(bob.bundle())
        val aToB = SessionCipher(alice.store, bob.address)
        val bFromA = SessionCipher(bob.store, alice.address)

        val first = aToB.encrypt("hello".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        assertArrayEquals("hello".toByteArray(), bFromA.decrypt(PreKeySignalMessage(first.serialize())))

        val reply = bFromA.encrypt("hi".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        assertArrayEquals("hi".toByteArray(), aToB.decrypt(SignalMessage(reply.serialize())))

        // out of order: the third arrives before the second
        val m2 = aToB.encrypt("two".toByteArray())
        val m3 = aToB.encrypt("three".toByteArray())
        assertArrayEquals("three".toByteArray(), bFromA.decrypt(SignalMessage(m3.serialize())))
        assertArrayEquals("two".toByteArray(), bFromA.decrypt(SignalMessage(m2.serialize())))

        // a re-delivered message can't be decrypted again
        val dup = runCatching { bFromA.decrypt(SignalMessage(m2.serialize())) }
        assert(dup.isFailure)
        println("libsignal ok: prekey message ${first.serialize().size} bytes, normal ${m3.serialize().size} bytes")
    }
}
