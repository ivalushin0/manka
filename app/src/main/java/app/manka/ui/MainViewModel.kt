package app.manka.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.manka.MankaApp
import app.manka.R
import android.net.Uri
import app.manka.autoselect.ServiceCheck
import app.manka.core.Backup
import app.manka.core.Engine
import app.manka.core.Module
import app.manka.core.ModuleStatus
import app.manka.core.Profiles
import app.manka.core.StatusNotifier
import app.manka.core.Updater
import app.manka.work.HealthWorker
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as MankaApp
    val prefs get() = app.prefs
    val presets get() = app.presets
    val store get() = app.store
    val autoSelector get() = app.autoSelector

    private val _status = MutableStateFlow(ModuleStatus())
    val status: StateFlow<ModuleStatus> = _status
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var kitsImported = false

    /** Self-update state for the settings screen and the home banner. */
    data class UpdateUi(
        val checking: Boolean = false,
        val checked: Boolean = false,
        val info: Updater.Info? = null,
        /** Download progress 0..1, -1 = unknown size, null = not downloading. */
        val progress: Float? = null,
        val installing: Boolean = false,
        val error: String? = null,
    ) {
        val available get() = info?.newer == true
    }

    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update

    init {
        refresh()
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > UPDATE_CHECK_INTERVAL) checkUpdate()
    }

    fun checkUpdate() {
        if (_update.value.checking || _update.value.progress != null || _update.value.installing) return
        _update.value = _update.value.copy(checking = true, error = null)
        viewModelScope.launch {
            _update.value = try {
                val info = Updater.check()
                prefs.lastUpdateCheck = System.currentTimeMillis()
                UpdateUi(checked = true, info = info)
            } catch (e: Exception) {
                UpdateUi(checked = true, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun installUpdate() {
        val info = _update.value.info ?: return
        if (_update.value.progress != null || _update.value.installing) return
        _update.value = _update.value.copy(progress = 0f, error = null)
        app.appScope.launch {
            try {
                val apk = Updater.download(app, info) { p -> _update.value = _update.value.copy(progress = p) }
                _update.value = _update.value.copy(progress = null, installing = true)
                prefs.moduleUpdatePending = true
                val error = Updater.install(app, apk)
                prefs.moduleUpdatePending = false
                _update.value = _update.value.copy(installing = false, error = error)
                if (error == null) say(R.string.update_no_restart)
            } catch (e: Exception) {
                prefs.moduleUpdatePending = false
                _update.value = _update.value.copy(progress = null, installing = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun say(@StringRes res: Int, vararg args: Any) {
        _messages.tryEmit(app.getString(res, *args))
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    fun refresh() {
        viewModelScope.launch {
            _status.value = Module.status()
            _loaded.value = true
            StatusNotifier.update(app, _status.value)
            val s = _status.value
            if (prefs.enabled && s.engineRunning && !autoSelector.state.value.running &&
                System.currentTimeMillis() - prefs.serviceStatusTime > SERVICE_CHECK_INTERVAL
            ) {
                checkServices()
            }
            if (prefs.moduleUpdatePending && s.rootOk) {
                // first start after a self-update: bring the module up to the bundled version
                prefs.moduleUpdatePending = false
                if (s.installed && Module.bundledVersionCode(app) > s.versionCode) {
                    kitsImported = true
                    installModule()
                    return@launch
                }
            }
            if (_status.value.usable && !kitsImported) {
                kitsImported = true
                runCatching { store.importBundledKits() }
            }
        }
    }

    /** Runs [block] in the application scope (survives leaving the screen), then refreshes the status. */
    fun op(@StringRes error: Int = R.string.error_generic, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        app.appScope.launch {
            try {
                block()
            } catch (e: Exception) {
                say(error, e.message ?: e.javaClass.simpleName)
            } finally {
                _status.value = Module.status()
                _busy.value = false
                StatusNotifier.update(app, _status.value)
            }
        }
    }

    fun apply() = op {
        val s = app.applier.apply()
        if (prefs.enabled && s.usable && !s.engineRunning) {
            // give the supervisor a moment, crashes show up in "failed"
            delay(1500)
            val again = Module.status()
            if (!again.engineRunning) say(R.string.engine_failed, prefs.engine(again.ownProfile).title)
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        apply()
    }

    /** Profile picked on the home screen; null = the profile of the current network. */
    val pickedProfile = MutableStateFlow<String?>(null)

    /** A Wi-Fi network gets its own profile the first time it is configured. */
    private fun remember(key: String) {
        val s = _status.value
        if (Profiles.isSsid(key) && key == s.ownProfile && s.ssid != null) prefs.rememberWifi(s.ssid!!)
    }

    fun setEngine(key: String, engine: Engine) {
        if (prefs.hasOwnEngine(key) && prefs.engine(key) == engine) return
        remember(key)
        prefs.setEngine(key, engine)
        if (prefs.enabled) apply()
    }

    fun forgetWifi(key: String) {
        prefs.forgetWifi(key)
        pickedProfile.value = null
        if (prefs.enabled) apply()
    }

    fun setTgws(on: Boolean) {
        prefs.tgws = on
        apply()
    }

    fun selectPreset(engine: Engine, key: String, id: String) {
        remember(key)
        if (!prefs.hasOwnEngine(key)) prefs.setEngine(key, engine)
        prefs.setActivePreset(engine, key, id)
        if (prefs.enabled && prefs.engine(key) == engine) apply()
    }

    // ---- service status on the home screen
    private val _checkingServices = MutableStateFlow(false)
    val checkingServices: StateFlow<Boolean> = _checkingServices

    fun checkServices() {
        if (_checkingServices.value) return
        _checkingServices.value = true
        app.appScope.launch {
            try {
                ServiceCheck.run(prefs, prefs.autoTimeoutSec)
            } finally {
                _checkingServices.value = false
            }
        }
    }

    // ---- backup
    fun exportBackup(uri: Uri) = op {
        val text = Backup.export(app, prefs)
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw IOException("cannot write")
        }
        say(R.string.backup_saved)
    }

    fun importBackup(uri: Uri) = op(R.string.backup_failed) {
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IOException("cannot read")
        }
        Backup.import(app, prefs, presets, text)
        HealthWorker.schedule(app, prefs)
        if (prefs.enabled || prefs.tgws) app.applier.apply()
        say(R.string.backup_restored)
    }

    /** Own strategy of a service in a profile: preset id, Services.OFF, or null = the main strategy. */
    fun selectServicePreset(engine: Engine, key: String, service: String, id: String?) {
        remember(key)
        if (!prefs.hasOwnEngine(key)) prefs.setEngine(key, engine)
        prefs.setServicePreset(engine, key, service, id)
        if (prefs.enabled && prefs.engine(key) == engine) apply()
    }

    fun installModule() = op(R.string.module_install_failed) {
        val r = Module.install(app)
        if (!r.ok) throw IllegalStateException(r.out.lines().lastOrNull { it.isNotBlank() } ?: "code ${r.code}")
        store.resyncKits()
        runCatching { store.importBundledKits() }
        kitsImported = true
        app.applier.apply()
        say(R.string.module_installed)
    }

    companion object {
        private const val UPDATE_CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        private const val SERVICE_CHECK_INTERVAL = 60 * 60 * 1000L
    }
}
