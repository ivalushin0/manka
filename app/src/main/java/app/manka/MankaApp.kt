package app.manka

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.manka.autoselect.AutoSelector
import app.manka.core.Applier
import app.manka.core.PresetRepository
import app.manka.core.Prefs
import app.manka.store.StoreRepository
import app.manka.work.HealthWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MankaApp : Application() {
    /** Work that must outlive screens (applying settings, installs). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var prefs: Prefs
        private set
    lateinit var presets: PresetRepository
        private set
    lateinit var applier: Applier
        private set
    lateinit var store: StoreRepository
        private set
    lateinit var autoSelector: AutoSelector
        private set

    override fun onCreate() {
        super.onCreate()
        // every test request must open a fresh connection through the engine under test
        System.setProperty("http.keepAlive", "false")
        prefs = Prefs(this)
        presets = PresetRepository(this, prefs)
        applier = Applier(this, prefs, presets)
        store = StoreRepository(this, prefs, presets)
        autoSelector = AutoSelector(prefs, presets, applier)
        createChannels()
        HealthWorker.schedule(this, prefs)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"

        fun of(context: Context) = context.applicationContext as MankaApp
    }
}
