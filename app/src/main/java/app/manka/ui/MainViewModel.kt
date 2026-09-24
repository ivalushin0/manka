package app.manka.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.manka.MankaApp
import app.manka.R
import app.manka.core.Engine
import app.manka.core.Module
import app.manka.core.ModuleStatus
import app.manka.core.Profiles
import kotlinx.coroutines.delay
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

    init {
        refresh()
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

    fun installModule() = op(R.string.module_install_failed) {
        val r = Module.install(app)
        if (!r.ok) throw IllegalStateException(r.out.lines().lastOrNull { it.isNotBlank() } ?: "code ${r.code}")
        store.resyncKits()
        runCatching { store.importBundledKits() }
        kitsImported = true
        app.applier.apply()
        say(R.string.module_installed)
    }
}
