package app.manka.autoselect

import android.os.Process
import app.manka.core.Applier
import app.manka.core.Args
import app.manka.core.Engine
import app.manka.core.Module
import app.manka.core.Preset
import app.manka.core.PresetRenderer
import app.manka.core.PresetRepository
import app.manka.core.PresetSource
import app.manka.core.Prefs
import app.manka.core.Strategies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class StrategyResult(
    val candidate: Strategies.Candidate,
    val sites: List<SiteResult>,
    /** Engine output when it refused to start with these arguments. */
    val startError: String? = null,
) {
    val startFailed get() = startError != null
    val ok get() = sites.sumOf { it.ok }
    val total get() = sites.sumOf { it.total }
    val percent get() = if (total == 0) 0 else ok * 100 / total
    val avgMs get() = sites.filter { it.ok > 0 }.map { it.avgMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0
}

enum class Phase { IDLE, BASELINE, TESTING, DONE, CANCELLED, ERROR }

data class AutoState(
    val phase: Phase = Phase.IDLE,
    val engine: Engine? = null,
    val current: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val targets: List<String> = emptyList(),
    val baseline: List<SiteResult> = emptyList(),
    val results: List<StrategyResult> = emptyList(),
    val error: String? = null,
) {
    val running get() = phase == Phase.BASELINE || phase == Phase.TESTING
    val sorted get() = results.sortedWith(compareByDescending<StrategyResult> { it.percent }.thenBy { it.avgMs.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE })
    val best get() = sorted.firstOrNull { it.percent > 0 }
    val baselinePercent get() = baseline.sumOf { it.ok }.let { ok -> baseline.sumOf { it.total }.takeIf { it > 0 }?.let { ok * 100 / it } ?: 0 }
}

data class AutoRequest(
    val engine: Engine,
    val targets: List<String>,
    val full: Boolean,
    val includeStore: Boolean,
    val requests: Int,
    val timeoutSec: Int,
    /** Stop after this many strategies passed every request (0 = test everything). */
    val stopAfterPerfect: Int = 3,
)

class AutoSelector(
    private val prefs: Prefs,
    private val presets: PresetRepository,
    private val applier: Applier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state
    private var job: Job? = null

    fun start(request: AutoRequest) {
        if (_state.value.running) return
        job = scope.launch { run(request) }
    }

    fun cancel() {
        job?.cancel()
    }

    fun candidatesFor(request: AutoRequest): List<Strategies.Candidate> {
        val current = presets.active(request.engine)
        val list = mutableListOf(Strategies.Candidate(name = current.name, template = current.template, preset = current))
        if (request.includeStore) {
            presets.all(request.engine).filter { it.source == PresetSource.STORE && it.id != current.id }
                .forEach { list += Strategies.Candidate(name = it.name, template = it.template, preset = it) }
        }
        list += Strategies.candidates(request.engine, request.full)
        return list.distinctBy { it.preset?.id ?: it.template }
    }

    private fun argsOf(c: Strategies.Candidate): List<String> {
        val p = c.preset
        return if (p != null) {
            applier.engineConfig(p.engine, p).args
        } else {
            Args.resolve(PresetRenderer.fromWinws(Args.split(c.template), null, null).args, fakeSni = prefs.fakeSni)
        }
    }

    /** Runs a full selection and returns the final state. Safe to call from a worker. */
    suspend fun run(request: AutoRequest): AutoState {
        val candidates = candidatesFor(request)
        _state.value = AutoState(
            phase = Phase.BASELINE, engine = request.engine, total = candidates.size, targets = request.targets,
        )
        val uid = Process.myUid()
        val socks = if (request.engine == Engine.BYEDPI) BYEDPI_TEST_PORT else null
        var perfect = 0
        var failedInRow = 0
        try {
            applier.writeConfig()
            Module.run("engine-stop", 60)
            val baseline = SiteChecker().check(request.targets, 1, request.timeoutSec)
            _state.update { it.copy(baseline = baseline, phase = Phase.TESTING) }

            for ((i, c) in candidates.withIndex()) {
                if (!coroutineContext.isActive) break
                _state.update { it.copy(current = i + 1, currentName = c.name) }
                val argsFile = applier.writeTestArgs(argsOf(c))
                val error = Module.testStart(request.engine, argsFile, uid)
                val result = if (error != null) {
                    StrategyResult(c, emptyList(), startError = error.ifBlank { "?" })
                } else {
                    // hostlists of big store presets take a moment to load
                    delay(if (c.preset?.source == PresetSource.STORE) 1500 else 600)
                    val sites = SiteChecker(socks).check(request.targets, request.requests, request.timeoutSec)
                    StrategyResult(c, sites)
                }
                _state.update { it.copy(results = it.results + result) }
                failedInRow = if (result.startFailed) failedInRow + 1 else 0
                if (failedInRow >= 3 && i == failedInRow - 1) {
                    // the engine does not start at all, testing the rest is pointless
                    throw IllegalStateException(result.startError)
                }
                if (result.total > 0 && result.ok == result.total) perfect++
                if (request.stopAfterPerfect in 1..perfect) break
            }
            _state.update { it.copy(phase = Phase.DONE) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(phase = Phase.CANCELLED) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            withContext(NonCancellable) {
                Module.testStop()
                // bring the regular configuration back
                Module.start()
            }
        }
        return _state.value
    }

    /** Saves a tested strategy as the active preset of its engine and applies it. */
    suspend fun applyResult(result: StrategyResult, engine: Engine) {
        val c = result.candidate
        val preset = c.preset ?: Preset(
            id = presets.newId("auto"),
            engine = engine,
            name = "${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())} · ${c.name.take(48)}",
            source = PresetSource.AUTO,
            template = c.template,
            score = result.percent,
        ).also { presets.save(it) }
        prefs.setActivePreset(engine, preset.id)
        prefs.engine = engine
        prefs.baselineRate = result.percent
        prefs.healthTargets = _state.value.targets.ifEmpty { prefs.healthTargets }
        prefs.enabled = true
        applier.apply()
    }

    fun reset() {
        if (!_state.value.running) _state.value = AutoState()
    }

    companion object {
        /** BYEDPI_TEST_PORT in manka.sh */
        const val BYEDPI_TEST_PORT = 10899
    }
}
