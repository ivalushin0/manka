package app.manka.core

import android.content.Context
import app.manka.store.KitLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Built-in, store, user and auto-selected presets. */
class PresetRepository(private val context: Context, private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val file = File(context.filesDir, "presets.json")

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets

    private var userPresets: List<Preset> = emptyList()
    private var storePresets: List<Preset> = emptyList()
    private var catalog: List<Preset> = emptyList()

    init {
        catalog = buildCatalog()
        userPresets = runCatching { json.decodeFromString<List<Preset>>(file.readText()) }.getOrDefault(emptyList())
        reloadStore()
    }

    /** Own strategies were replaced on disk (backup restore). */
    fun reloadUser() {
        userPresets = runCatching { json.decodeFromString<List<Preset>>(file.readText()) }.getOrDefault(emptyList())
        publish()
    }

    fun reloadStore() {
        storePresets = KitLoader.loadInstalled(context)
        publish()
    }

    /** Picks up a freshly downloaded ByeByeDPI list. */
    fun reloadCatalog() {
        catalog = buildCatalog()
        publish()
    }

    /** Every strategy auto selection knows, selectable by hand. */
    private fun buildCatalog(): List<Preset> {
        fun id(prefix: String, template: String) = prefix + "-" + Integer.toHexString(template.hashCode())
        val ours = Engine.entries.flatMap { e ->
            Strategies.candidates(e, full = true).map { c ->
                Preset(id = id("cat-${e.id}", c.template), engine = e, name = c.name, source = PresetSource.CATALOG, template = c.template)
            }
        }
        val byeByeDpi = File(context.filesDir, "external/byebyedpi.list").takeIf { it.exists() }
            ?.readLines()?.map { it.trim() }?.filter { it.startsWith("-") }?.distinct()
            ?.map { s ->
                Preset(
                    id = id("bbd", s), engine = Engine.BYEDPI, name = s, source = PresetSource.EXTERNAL,
                    template = s, description = "ByeByeDPI",
                )
            } ?: emptyList()
        return byeByeDpi + ours
    }

    private fun publish() {
        _presets.value = Strategies.builtinPresets() + storePresets + userPresets + catalog
    }

    fun all(engine: Engine): List<Preset> = _presets.value.filter { it.engine == engine }

    fun byId(id: String?): Preset? = id?.let { i -> _presets.value.firstOrNull { it.id == i } }

    /** Active preset of the engine in network profile [key]; falls back to the first built-in one. */
    fun active(engine: Engine, key: String): Preset =
        byId(prefs.activePreset(engine, key))?.takeIf { it.engine == engine } ?: all(engine).first()

    fun save(preset: Preset) {
        userPresets = userPresets.filter { it.id != preset.id } + preset
        persist()
    }

    fun delete(id: String) {
        userPresets = userPresets.filter { it.id != id }
        persist()
    }

    fun newId(prefix: String) = "$prefix-" + UUID.randomUUID().toString().take(8)

    private fun persist() {
        file.writeText(json.encodeToString(userPresets))
        publish()
    }
}
