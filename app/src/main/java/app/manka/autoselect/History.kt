package app.manka.autoselect

import android.content.Context
import app.manka.core.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedResult(
    val name: String,
    val template: String,
    val presetId: String? = null,
    val engine: Engine? = null,
    val percent: Int,
    val ok: Int,
    val total: Int,
    val avgMs: Long,
    val sites: List<SiteResult> = emptyList(),
)

@Serializable
data class HistoryRun(
    val id: String,
    val time: Long,
    /** null = all engines were tested. */
    val engine: Engine? = null,
    val profile: String,
    val profileLabel: String? = null,
    /** Service the selection was for (see Services); null = the main strategy. */
    val service: String? = null,
    val targets: List<String>,
    val baselineOk: Int,
    val baselineTotal: Int,
    val tested: Int,
    /** Only strategies that opened at least something, best first. */
    val results: List<SavedResult>,
)

/** Finished auto selections, so good strategies can be applied later without testing again. */
class AutoHistory(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val _runs = MutableStateFlow(load())
    val runs: StateFlow<List<HistoryRun>> = _runs

    private fun load(): List<HistoryRun> = dir.listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { f -> runCatching { json.decodeFromString<HistoryRun>(f.readText()) }.getOrNull() }
        ?.sortedByDescending { it.time }
        ?: emptyList()

    fun byId(id: String?): HistoryRun? = _runs.value.firstOrNull { it.id == id }

    fun add(run: HistoryRun) {
        File(dir, "${run.id}.json").writeText(json.encodeToString(run))
        val all = load()
        all.drop(MAX_RUNS).forEach { File(dir, "${it.id}.json").delete() }
        _runs.value = all.take(MAX_RUNS)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _runs.value = load()
    }

    companion object {
        private const val MAX_RUNS = 50
    }
}
