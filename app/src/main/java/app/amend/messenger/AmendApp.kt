package app.amend.messenger

import android.app.Application
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.NativeSecp
import app.amend.messenger.data.SecureKeyStore
import app.amend.messenger.util.Notifications
import app.amend.messenger.util.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class AmendApp : Application() {

    lateinit var keyStore: SecureKeyStore
        private set

    private val _repository = MutableStateFlow<AmendRepository?>(null)
    /** Null until the user has created or restored an identity. */
    val repository: StateFlow<AmendRepository?> = _repository

    var nativeCrypto = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        nativeCrypto = NativeSecp.install()
        keyStore = SecureKeyStore(this)
        Notifications.createChannels(this)
        keyStore.load()?.let { openRepository(it) }
    }

    private fun dataDir() = File(filesDir, "amend")

    fun openRepository(keys: KeyPair): AmendRepository {
        _repository.value?.let { if (it.me == keys.publicKeyHex) return it }
        val repo = AmendRepository(dataDir(), keys)
        repo.start()
        _repository.value = repo
        SyncScheduler.schedule(this, repo.eco.value.carbonAwareSync, repo.eco.value.efficiencyMode)
        return repo
    }

    fun createIdentity(keys: KeyPair): AmendRepository {
        keyStore.save(keys)
        return openRepository(keys)
    }

    /** Removes the identity and all local data from this device. */
    fun signOut() {
        _repository.value?.goOffline()
        _repository.value = null
        keyStore.clear()
        dataDir().deleteRecursively()
        SyncScheduler.cancel(this)
    }

    companion object {
        lateinit var instance: AmendApp
            private set
    }
}
