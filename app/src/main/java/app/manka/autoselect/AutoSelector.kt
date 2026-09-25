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
import app.manka.core.Service
import app.manka.core.Services
import app.manka.core.Prefs
import app.manka.core.Profiles
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
    val engine get() = candidate.engine
    val startFailed get() = startError != null
    val ok get() = sites.sumOf { it.ok }
    val total get() = sites.sumOf { it.total }
    val percent get() = if (total == 0) 0 else ok * 100 / total
    val avgMs get() = sites.filter { it.ok > 0 }.map { it.avgMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0
}

enum class Phase { IDLE, BASELINE, TESTING, DONE, CANCELLED, ERROR }

private val bestFirst = compareByDescending<StrategyResult> { it.percent }
    .thenBy { it.avgMs.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE }

data class AutoState(
    val phase: Phase = Phase.IDLE,
    /** Engines under test; more than one for "all engines". */
    val engines: List<Engine> = emptyList(),
    /** Network profile the result belongs to and its name (SSID for a Wi-Fi network). */
    val profile: String? = null,
    val profileLabel: String? = null,
    /** Service (see Services) the selection is for; null = the main strategy. */
    val service: String? = null,
    val current: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val currentEngine: Engine? = null,
    val targets: List<String> = emptyList(),
    val baseline: List<SiteResult> = emptyList(),
    val results: List<StrategyResult> = emptyList(),
    val error: String? = null,
) {
    val running get() = phase == Phase.BASELINE || phase == Phase.TESTING
    val allEngines get() = engines.size > 1
    val sorted get() = results.sortedWith(bestFirst)
    val best get() = sorted.firstOrNull { it.percent > 0 }

    /** Best working result of every tested engine, best engine first. */
    val bestByEngine: List<StrategyResult>
        get() = results.filter { it.percent > 0 }.groupBy { it.engine }
            .mapNotNull { (_, list) -> list.minWithOrNull(bestFirst) }
            .sortedWith(bestFirst)
}

data class AutoRequest(
    /** One engine, or all of them to find out which one suits the network. */
    val engines: List<Engine>,
    /** Network profile the result is saved for (the network the test runs on). */
    val profile: String,
    /** SSID for a Wi-Fi network profile. */
    val profileLabel: String? = null,
    val targets: List<String>,
    val full: Boolean,
    val includeStore: Boolean,
    val requests: Int,
    val timeoutSec: Int,
    /** Per engine: stop after this many strategies passed every request (0 = test everything). */
    val stopAfterPerfect: Int = 3,
    /** Also test the ByeByeDPI strategy list (downloaded from its repository). */
    val byeByeDpi: Boolean = true,
    /** Select a strategy for one service only (see Services); null = the main strategy. */
    val service: String? = null,
)

class AutoSelector(
    private val context: android.content.Context,
    private val prefs: Prefs,
    private val presets: PresetRepository,
    private val applier: Applier,
    val history: AutoHistory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state
    private var job: Job? = null

    fun start(request: AutoRequest) {
        if (_state.value.running || _queue.value.active) return
        job = scope.launch { run(request) }
    }

    fun cancel() {
        job?.cancel()
    }

    fun candidatesFor(request: AutoRequest, external: List<String> = emptyList()): List<Strategies.Candidate> = request.engines.flatMap { engine ->
        val current = request.service?.let { s -> presets.byId(prefs.servicePreset(engine, request.profile, s))?.takeIf { it.engine == engine } }
            ?: presets.active(engine, request.profile)
        val list = mutableListOf(Strategies.Candidate(current.name, current.template, current, engine))
        if (request.includeStore && engine != Engine.BYEDPI) {
            presets.all(engine).filter { it.source == PresetSource.STORE && it.id != current.id }
                .forEach { list += Strategies.Candidate(it.name, it.template, it, engine) }
        }
        if (engine == Engine.BYEDPI) {
            external.forEach { list += Strategies.Candidate("ByeByeDPI: $it", it, null, engine) }
        }
        list += Strategies.candidates(engine, request.full)
        list.distinctBy { it.preset?.id ?: it.template }
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
        val external = if (request.byeByeDpi && Engine.BYEDPI in request.engines) {
            ExternalStrategies.byeByeDpi(context).also { presets.reloadCatalog() }
        } else {
            emptyList()
        }
        val candidates = candidatesFor(request, external)
        _state.value = AutoState(
            phase = Phase.BASELINE,
            engines = request.engines,
            profile = request.profile,
            profileLabel = request.profileLabel,
            service = request.service,
            total = candidates.size,
            targets = request.targets,
        )
        val uid = Process.myUid()
        val perfect = HashMap<Engine, Int>()
        val failedInRow = HashMap<Engine, Int>()
        val brokenEngines = HashSet<Engine>()
        try {
            applier.writeConfig()
            Module.run("engine-stop", 60)
            val baseline = SiteChecker().check(request.targets, 1, request.timeoutSec)
            _state.update { it.copy(baseline = baseline, phase = Phase.TESTING) }

            for ((i, c) in candidates.withIndex()) {
                if (!coroutineContext.isActive) break
                val engine = c.engine
                if (engine in brokenEngines) continue
                if (request.stopAfterPerfect in 1..(perfect[engine] ?: 0)) continue
                _state.update { it.copy(current = i + 1, currentName = c.name, currentEngine = engine) }

                val argsFile = applier.writeTestArgs(argsOf(c))
                val error = Module.testStart(engine, argsFile, uid)
                val result = if (error != null) {
                    StrategyResult(c, emptyList(), startError = error.ifBlank { "?" })
                } else {
                    // hostlists of big store presets take a moment to load
                    delay(if (c.preset?.source == PresetSource.STORE) 1500 else 600)
                    val socks = if (engine == Engine.BYEDPI) BYEDPI_TEST_PORT else null
                    val sites = SiteChecker(socks).check(request.targets, request.requests, request.timeoutSec)
                    StrategyResult(c, sites)
                }
                _state.update { it.copy(results = it.results + result) }

                val fails = if (result.startFailed) (failedInRow[engine] ?: 0) + 1 else 0
                failedInRow[engine] = fails
                val testedOfEngine = _state.value.results.count { it.engine == engine }
                if (fails >= 3 && fails == testedOfEngine) {
                    // this engine does not start at all, testing the rest of it is pointless
                    if (request.engines.size == 1) throw IllegalStateException(result.startError)
                    brokenEngines += engine
                }
                if (result.total > 0 && result.ok == result.total) perfect[engine] = (perfect[engine] ?: 0) + 1
            }
            _state.update { it.copy(phase = Phase.DONE, currentEngine = null) }
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
                saveHistory(request)
            }
        }
        return _state.value
    }

    private fun StrategyResult.saved() = SavedResult(
        name = candidate.name,
        template = candidate.template,
        presetId = candidate.preset?.id,
        engine = engine,
        percent = percent,
        ok = ok,
        total = total,
        avgMs = avgMs,
        sites = sites,
    )

    private fun saveHistory(request: AutoRequest) {
        val s = _state.value
        val good = s.sorted.filter { it.percent > 0 }
        if (good.isEmpty()) return
        runCatching {
            history.add(
                HistoryRun(
                    id = System.currentTimeMillis().toString(),
                    time = System.currentTimeMillis(),
                    engine = request.engines.singleOrNull(),
                    profile = request.profile,
                    profileLabel = request.profileLabel,
                    service = request.service,
                    targets = request.targets,
                    baselineOk = s.baseline.count { it.ok > 0 },
                    baselineTotal = s.baseline.size,
                    tested = s.results.size,
                    results = good.map { it.saved() },
                ),
            )
        }
    }

    /** Saves a tested strategy as the active preset of its engine in [profile] and applies it. */
    suspend fun applyResult(result: StrategyResult, profile: String, label: String?, service: String? = _state.value.service) =
        applyStrategy(result.saved().copy(sites = emptyList()), profile, label, _state.value.targets, service)

    /**
     * Also used from the history screen. With [service] the strategy becomes that service's own
     * strategy (zapret / zapret2 only), otherwise the main strategy of the profile.
     */
    suspend fun applyStrategy(
        result: SavedResult,
        profile: String,
        label: String?,
        targets: List<String>,
        service: String? = null,
        applyNow: Boolean = true,
    ) {
        val engine = result.engine ?: presets.byId(result.presetId)?.engine ?: Engine.BYEDPI
        if (Profiles.isSsid(profile) && !label.isNullOrBlank()) prefs.rememberWifi(label)
        val preset = presets.byId(result.presetId)?.takeIf { it.engine == engine }
            ?: presets.all(engine).firstOrNull { it.source == PresetSource.AUTO && it.template == result.template }
            ?: Preset(
                id = presets.newId("auto"),
                engine = engine,
                name = "${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())} · " +
                    (Services.byId(service)?.let { "${it.title} · " } ?: "") + result.name.take(48),
                source = PresetSource.AUTO,
                template = result.template,
                score = result.percent,
            ).also { presets.save(it) }
        if (service != null && engine != Engine.BYEDPI) {
            prefs.setServicePreset(engine, profile, service, preset.id)
        } else {
            prefs.setActivePreset(engine, profile, preset.id)
            prefs.setBaselineRate(profile, result.percent)
            if (targets.isNotEmpty()) prefs.healthTargets = targets
        }
        prefs.setEngine(profile, engine)
        prefs.enabled = true
        if (applyNow) applier.apply()
    }

    // ------------------------------------------------------------------ every service in a row

    data class QueueState(
        val services: List<String> = emptyList(),
        /** Service id -> best result (null = nothing worked); filled as services finish. */
        val done: Map<String, StrategyResult?> = emptyMap(),
        val current: String? = null,
        val finished: Boolean = false,
    ) {
        val active get() = services.isNotEmpty() && !finished
    }

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue

    /**
     * Selects a strategy for every service one after another and applies the winners as the
     * services' own strategies. [base] gives the engine and options, targets come from each service.
     */
    fun startServices(base: AutoRequest, services: List<Service>) {
        if (_state.value.running || _queue.value.active) return
        job = scope.launch {
            _queue.value = QueueState(services = services.map { it.id })
            try {
                for (s in services) {
                    if (!coroutineContext.isActive) break
                    _queue.update { it.copy(current = s.id) }
                    val targets = Targets.groups.filter { it.id in s.targetGroups }.flatMap { it.urls }
                    val final = run(base.copy(service = s.id, targets = targets))
                    if (final.phase == Phase.CANCELLED) break
                    val best = final.best
                    _queue.update { it.copy(done = it.done + (s.id to best)) }
                    if (best != null) {
                        applyStrategy(best.saved().copy(sites = emptyList()), base.profile, base.profileLabel, targets, s.id, applyNow = false)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    _queue.update { it.copy(current = null, finished = true) }
                    if (_queue.value.done.values.any { it != null }) applier.apply()
                }
            }
        }
    }

    fun resetQueue() {
        if (!_queue.value.active) _queue.value = QueueState()
    }

    fun reset() {
        if (!_state.value.running) _state.value = AutoState()
    }

    companion object {
        /** BYEDPI_TEST_PORT in manka.sh */
        const val BYEDPI_TEST_PORT = 10899
    }
}
